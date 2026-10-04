package dev.agentcraft.building;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.walk.LevelWalk;
import dev.agentcraft.walk.WalkCell;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Roads between buildings (docs/VILLAGE.md V1, docs/BUILDINGS.md "Roads"), server side: the laid roads of the running
 * world ({@code <world>/agentcraft-roads.json}), laying one from a route the client planned ({@link #lay}: everything is
 * checked and planned again here on the server's own level, since the client's view can be stale) and removing one
 * ({@link #remove}: every cell that still holds what the road put there gets its old block back; cells the player
 * changed since, or that a building now covers, are left alone). Each road's snapshot is per cell, never a box, so a
 * removal never reverts anything else. Blocks are set with no side effects (no neighbour or shape updates, no drops, no
 * falling), and new drops around the road are cleared anyway. Loaded when a world starts, cleared when it stops.
 * Server thread, except the read-only views ({@link #all}, {@link #feetCells}) which any thread may use.
 */
public final class Roads {
	public static final String FILE = "agentcraft-roads.json";
	public static final String SNAPSHOT_DIR = "agentcraft-roads";
	/** Sync to clients; no neighbour or shape updates, no drops, no block-entity side effects, no onPlace (gravel never ticks). */
	static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
	/** How far (blocks, horizontal) a route's first and last cells may be from the buildings' entrances. */
	static final double END_SLACK = 4.5;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private record State(Map<String, Road> byId, int next, List<Road.Pending> pending) {
		static final State EMPTY = new State(Map.of(), 1, List.of());
	}

	private static volatile State state = State.EMPTY;
	private static volatile boolean loadFailed;
	private static volatile long signature;
	private static volatile @Nullable Map<String, LongSet> feet;
	private static volatile long feetSig = -1;
	private static final List<Consumer<List<Road>>> LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile @Nullable String lastNote;

	/** Thrown with a message meant for the player. */
	public static final class RoadException extends Exception {
		public RoadException(String message) {
			super(message);
		}
	}

	private Roads() {
	}

	/** Register after {@link Buildings#init()}. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			load(server);
			reconcile(server);
			changed();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			state = State.EMPTY;
			loadFailed = false;
			changed();
		});
	}

	// ------------------------------------------------------------------ reads (any thread)

	public static List<Road> all() {
		return List.copyOf(state.byId().values());
	}

	public static @Nullable Road get(String id) {
		return state.byId().get(id);
	}

	/** The roads that end at {@code building}. */
	public static List<Road> forBuilding(String building) {
		List<Road> out = new ArrayList<>();
		for (Road r : state.byId().values()) {
			if (r.joins(building)) {
				out.add(r);
			}
		}
		return out;
	}

	/** The road between {@code a} and {@code b} (either way round), or null. */
	public static @Nullable Road between(String a, String b) {
		for (Road r : state.byId().values()) {
			if (r.between(a, b)) {
				return r;
			}
		}
		return null;
	}

	/** Changes whenever a road is laid or removed, or the world changes (cheap change check for caches). */
	public static long signature() {
		return signature;
	}

	public static boolean loadFailed() {
		return loadFailed;
	}

	/** The last lay/remove's extra note (cells left alone), or null. */
	public static @Nullable String lastNote() {
		return lastNote;
	}

	/**
	 * The feet cells ({@link WalkCell#pack}) of every road in {@code dimension}: where a walker stands on them. The outdoor
	 * planner makes steps onto them cheaper, so agents keep to the roads. Empty when there are none.
	 */
	public static LongSet feetCells(String dimension) {
		Map<String, LongSet> f = feet;
		long sig = signature;
		if (f == null || feetSig != sig) {
			Map<String, LongSet> m = new HashMap<>();
			for (Road r : state.byId().values()) {
				LongSet s = m.computeIfAbsent(r.dimension(), k -> new LongOpenHashSet());
				int[] c = r.cells();
				for (int i = 0; i + 2 < c.length; i += 3) {
					s.add(WalkCell.pack(c[i], c[i + 1], c[i + 2]));
				}
			}
			f = m;
			feet = m;
			feetSig = sig;
		}
		LongSet s = f.get(dimension);
		return s == null ? new LongOpenHashSet() : s;
	}

	/** The cells ({@link WalkCell#pack}) every road in {@code dimension} changed (another road leaves them alone). */
	public static LongSet changedCells(String dimension) {
		LongOpenHashSet s = new LongOpenHashSet();
		for (Road r : state.byId().values()) {
			if (r.dimension().equals(dimension)) {
				int[] c = r.changes();
				for (int i = 0; i + 2 < c.length; i += 3) {
					s.add(WalkCell.pack(c[i], c[i + 1], c[i + 2]));
				}
			}
		}
		return s;
	}

	/** Called (on the thread that changed them) with the roads after every change. */
	public static void addListener(Consumer<List<Road>> l) {
		LISTENERS.add(l);
	}

	/** The restore boxes of every building in {@code dimension}: a road never touches them. */
	public static List<Anchors.Bounds> buildingBoxes(String dimension) {
		List<Anchors.Bounds> out = new ArrayList<>();
		for (Building b : Buildings.all()) {
			if (b.dimensionOrDefault().equals(dimension)) {
				out.add(b.restoreBox());
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ lay

	/** What {@link #lay} did. */
	public record Laid(Road road, RoadPlan.Plan plan, int changed) {
	}

	/**
	 * Lays a road from building {@code a} to {@code b} along {@code route} (feet cells from the client's planner with
	 * drops of at most one block, entrance to entrance). Checks again here: the buildings (both in this level), no road
	 * between them yet, the route (a chain of gentle steps, starting and ending at the entrances, every cell outside the
	 * buildings still standable, every chunk loaded), then plans the road on this level ({@link RoadPlan#plan}: never
	 * inside a building, never another road's cells) and refuses when a player, a pet or another creature that matters
	 * stands where a block would go. Writes the snapshot, then the record, then the blocks. Server thread.
	 */
	public static Laid lay(ServerLevel level, String a, String b, long[] route, RoadPlan.Options o) throws RoadException {
		if (loadFailed) {
			throw new RoadException(FILE + " could not be read (see the log): no roads are laid until it is fixed or moved away");
		}
		String dim = Buildings.dimensionId(level);
		Building ba = requireHere(a, dim);
		Building bb = requireHere(b, dim);
		if (a.equals(b)) {
			throw new RoadException("A road needs two different buildings");
		}
		Road existing = between(a, b);
		if (existing != null) {
			throw new RoadException(existing.id() + " already joins " + a + " and " + b + ": remove it first to lay it again");
		}
		String broken = RoadPlan.routeProblem(route);
		if (broken != null) {
			throw new RoadException("Cannot lay this road: " + broken);
		}
		checkEnd(ba, route[0], "starts");
		checkEnd(bb, route[route.length - 1], "ends");
		List<Anchors.Bounds> boxes = buildingBoxes(dim);
		LevelWalk walk = new LevelWalk(level);
		for (long c : route) {
			int x = WalkCell.unpackX(c);
			int y = WalkCell.unpackY(c);
			int z = WalkCell.unpackZ(c);
			if (inAny(boxes, x, y, z)) {
				continue;
			}
			if (!level.hasChunk(x >> 4, z >> 4)) {
				throw new RoadException("Chunks around " + x + ", " + z + " are not loaded: walk closer to the road and try again");
			}
			if (Double.isNaN(WalkCell.floor(walk, x, y, z))) {
				throw new RoadException("The ground changed since the route was planned (" + x + ", " + y + ", " + z
					+ " is no longer walkable): preview the road again; nothing was done");
			}
		}
		LongSet taken = changedCells(dim);
		RoadPlan.Plan plan = RoadPlan.plan(route, o, new RoadTerrain(level), boxes, taken::contains);
		if (plan.refusal() != null) {
			throw new RoadException("Cannot lay this road: " + plan.refusal());
		}
		// the changes that change something (a dirt path stays a dirt path)
		List<RoadPlan.Op> ops = new ArrayList<>();
		List<BlockState> after = new ArrayList<>();
		List<BlockState> before = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (RoadPlan.Op op : plan.ops()) {
			BlockState now = level.getBlockState(m.set(op.x(), op.y(), op.z()));
			BlockState want = state(op.block());
			if (now == want) {
				continue;
			}
			ops.add(op);
			after.add(want);
			before.add(now);
		}
		if (ops.isEmpty() && plan.cells().isEmpty()) {
			throw new RoadException("Nothing to lay between " + a + " and " + b + (plan.notes().isEmpty() ? "" : ": " + String.join("; ", plan.notes())));
		}
		refuseOccupied(level, ops, after, "laying it");
		State s = state;
		String id = "r" + s.next();
		Path snap = snapshotFile(level.getServer(), id);
		writeSnapshot(snap, ops, before, after);
		int[] changes = new int[ops.size() * 3];
		for (int i = 0; i < ops.size(); i++) {
			changes[3 * i] = ops.get(i).x();
			changes[3 * i + 1] = ops.get(i).y();
			changes[3 * i + 2] = ops.get(i).z();
		}
		int[] cells = new int[plan.cells().size() * 3];
		for (int i = 0; i < plan.cells().size(); i++) {
			RoadPlan.Cell c = plan.cells().get(i);
			cells[3 * i] = c.x();
			cells[3 * i + 1] = c.feetY();
			cells[3 * i + 2] = c.z();
		}
		Road road = new Road(id, a, b, dim, o.width(), o.lanterns(), o.bridge(), System.currentTimeMillis(), plan.centre(), cells, plan.lanterns(),
			changes, plan.notes());
		Map<String, Road> map = new LinkedHashMap<>(s.byId());
		map.put(id, road);
		commit(level.getServer(), new State(Collections.unmodifiableMap(map), s.next() + 1, s.pending()));
		// the blocks last: a crash before this leaves a record whose cells do not hold the road (Remove then leaves them)
		Anchors.Bounds box = plan.box();
		Buildings.Drops drops = box == null ? null : Buildings.Drops.before(level, box);
		for (int i = 0; i < ops.size(); i++) {
			RoadPlan.Op op = ops.get(i);
			level.setBlock(m.set(op.x(), op.y(), op.z()), after.get(i), FLAGS);
		}
		if (drops != null) {
			drops.clearNew(level);
		}
		lastNote = plan.notes().isEmpty() ? null : String.join("; ", plan.notes());
		AgentCraft.LOGGER.info("Laid road {} {} -> {} in {}: {} cells, {} changes, {} lanterns{}", id, a, b, dim, road.cellCount(), ops.size(),
			road.lanternCount(), lastNote == null ? "" : " (" + lastNote + ")");
		return new Laid(road, plan, ops.size());
	}

	private static Building requireHere(String id, String dim) throws RoadException {
		Building b = Buildings.get(id);
		if (b == null) {
			throw new RoadException("No building " + id);
		}
		if (!b.dimensionOrDefault().equals(dim)) {
			throw new RoadException(id + " is in " + b.dimensionOrDefault() + ", not in " + dim + ": roads join buildings of the player's dimension");
		}
		return b;
	}

	/** The route must start (end) at the building's entrance. */
	private static void checkEnd(Building b, long cell, String verb) throws RoadException {
		Anchor e = b.anchors().get(AnchorNames.ENTRANCE);
		if (e == null) {
			throw new RoadException(b.id() + " has no entrance: roads go from entrance to entrance");
		}
		double dx = WalkCell.unpackX(cell) + 0.5 - e.x();
		double dz = WalkCell.unpackZ(cell) + 0.5 - e.z();
		if (Math.hypot(dx, dz) > END_SLACK) {
			throw new RoadException("The route " + verb + " " + Math.round(Math.hypot(dx, dz)) + " blocks from " + b.id()
				+ "'s entrance: plan it again (the building may have moved)");
		}
	}

	private static boolean inAny(List<Anchors.Bounds> boxes, int x, int y, int z) {
		for (Anchors.Bounds b : boxes) {
			if (b.contains(x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/** The block state a road puts down (bottom slabs, a standing lantern, a fence with no connections: the defaults). */
	static BlockState state(RoadPlan.Block b) {
		Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(b.id)).orElse(Blocks.AIR);
		return block.defaultBlockState();
	}

	/**
	 * Refuses when a player, a pet, a villager or anything else that matters is in a cell about to gain a collision shape
	 * (a slab under their feet, a restored bush at head height: stuck or suffocating, which in Hardcore is the end). Hostile
	 * mobs and dropped items are pushed out by the game.
	 */
	static void refuseOccupied(ServerLevel level, List<RoadPlan.Op> ops, List<BlockState> to, String verb) throws RoadException {
		LongOpenHashSet solid = new LongOpenHashSet();
		int[] bb = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int i = 0; i < ops.size(); i++) {
			RoadPlan.Op op = ops.get(i);
			if (to.get(i).getCollisionShape(level, m.set(op.x(), op.y(), op.z())).isEmpty()) {
				continue;
			}
			solid.add(WalkCell.pack(op.x(), op.y(), op.z()));
			bb[0] = Math.min(bb[0], op.x());
			bb[1] = Math.min(bb[1], op.y());
			bb[2] = Math.min(bb[2], op.z());
			bb[3] = Math.max(bb[3], op.x());
			bb[4] = Math.max(bb[4], op.y());
			bb[5] = Math.max(bb[5], op.z());
		}
		if (solid.isEmpty()) {
			return;
		}
		AABB area = new AABB(bb[0], bb[1], bb[2], bb[3] + 1, bb[4] + 1, bb[5] + 1).inflate(1);
		List<String> who = new ArrayList<>();
		for (Entity e : level.getEntities((Entity) null, area, Entity::isAlive)) {
			if (e instanceof Player p && p.isSpectator()) {
				continue;
			}
			Occupancy.Found f = Occupancy.classify(e);
			boolean matters = f.kind() == Occupancy.Kind.PLAYER || f.kind() == Occupancy.Kind.OWNED || f.kind() == Occupancy.Kind.LIVING
				|| f.kind() == Occupancy.Kind.OTHER || f.kind() == Occupancy.Kind.HOSTILE && f.keep();
			if (!matters) {
				continue;
			}
			AABB box = e.getBoundingBox();
			if (e instanceof Player) {
				box = box.inflate(0.3); // a step away from the edge of a new block
			}
			BlockPos at = touches(box, solid);
			if (at != null) {
				who.add((f.kind() == Occupancy.Kind.PLAYER ? "you" : f.name()) + " at " + at.getX() + ", " + at.getY() + ", " + at.getZ());
			}
		}
		if (!who.isEmpty()) {
			throw new RoadException("Step off the road first: " + String.join(", ", who.subList(0, Math.min(4, who.size())))
				+ (who.size() > 4 ? ", ..." : "") + " (" + verb + " puts blocks where they stand); nothing was done");
		}
	}

	private static @Nullable BlockPos touches(AABB box, LongSet cells) {
		for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
			for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
				for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
					if (cells.contains(WalkCell.pack(x, y, z))) {
						return new BlockPos(x, y, z);
					}
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ remove

	/**
	 * What {@link #remove} did: {@code restored} cells got their old block back, {@code changed} were left alone because
	 * the player changed them since, {@code covered} because a building now covers them.
	 */
	public record Removed(Road road, int restored, int changed, int covered) {
		public String message() {
			String m = "Removed road " + road.id() + " (" + road.a() + " to " + road.b() + "): " + restored + " cell" + (restored == 1 ? "" : "s")
				+ " back as they were";
			if (changed > 0) {
				m += "; " + changed + " cell" + (changed == 1 ? "" : "s") + " you changed since left alone";
			}
			if (covered > 0) {
				m += "; " + covered + " under a building left alone";
			}
			return m;
		}
	}

	/** One snapshot cell. */
	record Entry(int x, int y, int z, BlockState before, BlockState after) {
	}

	/**
	 * Removes road {@code id}: every cell that still holds what the road put there gets its old block back (in the order
	 * ground first, then what stood on it); cells the player changed since and cells a building now covers are left as
	 * they are. Refuses while a player or a pet stands where an old block comes back. The snapshot is kept (as a pending
	 * removal) until the next world start confirms the restored cells reached the disk. Server thread.
	 */
	public static Removed remove(ServerLevel level, String id) throws RoadException {
		Road r = get(id);
		if (r == null) {
			throw new RoadException("No road " + id);
		}
		String dim = Buildings.dimensionId(level);
		if (!r.dimension().equals(dim)) {
			throw new RoadException(id + " is in " + r.dimension() + ", not in " + dim + ": go there to remove it");
		}
		MinecraftServer server = level.getServer();
		Path snap = snapshotFile(server, id);
		if (!Files.exists(snap)) {
			throw new RoadException("The snapshot of " + id + " is missing, so it cannot be restored; Forget drops the record and leaves the blocks");
		}
		List<Entry> entries = readSnapshot(level, snap);
		List<Anchors.Bounds> boxes = buildingBoxes(dim);
		List<Entry> restore = new ArrayList<>();
		int changed = 0;
		int covered = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Entry e : entries) {
			if (inAny(boxes, e.x(), e.y(), e.z())) {
				covered++;
				continue;
			}
			if (!level.hasChunk(e.x() >> 4, e.z() >> 4)) {
				throw new RoadException("Chunks around " + e.x() + ", " + e.z() + " are not loaded: walk closer to the road and try again");
			}
			BlockState now = level.getBlockState(m.set(e.x(), e.y(), e.z()));
			if (now != e.after() || now.hasBlockEntity()) {
				changed++;
				continue;
			}
			restore.add(e);
		}
		restore.sort(Comparator.comparingInt(Entry::y));
		List<RoadPlan.Op> ops = new ArrayList<>();
		List<BlockState> to = new ArrayList<>();
		for (Entry e : restore) {
			ops.add(new RoadPlan.Op(e.x(), e.y(), e.z(), RoadPlan.Block.AIR, 0));
			to.add(e.before());
		}
		refuseOccupied(level, ops, to, "removing it");
		Anchors.Bounds box = RoadPlan.box(ops);
		Buildings.Drops drops = box == null ? null : Buildings.Drops.before(level, box);
		for (Entry e : restore) {
			level.setBlock(m.set(e.x(), e.y(), e.z()), e.before(), FLAGS);
		}
		if (drops != null) {
			drops.clearNew(level);
		}
		Path kept = snap.resolveSibling(id + ".removed-" + System.currentTimeMillis() + ".nbt");
		try {
			Files.move(snap, kept, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ex) {
			kept = snap; // still there under its old name: the next start settles it all the same
			AgentCraft.LOGGER.warn("Could not rename {}", snap, ex);
		}
		State s = state;
		Map<String, Road> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		List<Road.Pending> pending = new ArrayList<>(s.pending());
		pending.add(new Road.Pending(r, kept.getFileName().toString(), System.currentTimeMillis()));
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(pending)));
		Removed out = new Removed(r, restore.size(), changed, covered);
		lastNote = changed + covered == 0 ? null : out.message();
		AgentCraft.LOGGER.info("{}", out.message());
		return out;
	}

	/** Drops road {@code id}'s record and leaves its blocks (its snapshot is missing or unwanted). Server thread. */
	public static Road forget(MinecraftServer server, String id) throws RoadException {
		Road r = get(id);
		if (r == null) {
			throw new RoadException("No road " + id);
		}
		State s = state;
		Map<String, Road> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), s.pending()));
		AgentCraft.LOGGER.info("Forgot road {} (its blocks stay)", id);
		return r;
	}

	// ------------------------------------------------------------------ snapshot

	static Path snapshotFile(MinecraftServer server, String id) {
		return server.getWorldPath(LevelResource.ROOT).resolve(SNAPSHOT_DIR).resolve(id + ".before.nbt");
	}

	private static void writeSnapshot(Path file, List<RoadPlan.Op> ops, List<BlockState> before, List<BlockState> after) throws RoadException {
		CompoundTag root = new CompoundTag();
		root.putInt("version", 1);
		ListTag cells = new ListTag();
		for (int i = 0; i < ops.size(); i++) {
			RoadPlan.Op op = ops.get(i);
			CompoundTag c = new CompoundTag();
			c.putInt("x", op.x());
			c.putInt("y", op.y());
			c.putInt("z", op.z());
			c.put("before", NbtUtils.writeBlockState(before.get(i)));
			c.put("after", NbtUtils.writeBlockState(after.get(i)));
			cells.add(c);
		}
		root.put("cells", cells);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			NbtIo.writeCompressed(root, tmp);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			CompoundTag back = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			if (back.getListOrEmpty("cells").size() != ops.size()) {
				throw new IOException("read back " + back.getListOrEmpty("cells").size() + " of " + ops.size() + " cells");
			}
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not write road snapshot {}", file, e);
			throw new RoadException("Could not save the road's snapshot (" + e.getMessage() + "); nothing was laid");
		}
	}

	private static List<Entry> readSnapshot(ServerLevel level, Path file) throws RoadException {
		CompoundTag root;
		try {
			root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			throw new RoadException("Could not read the road's snapshot " + file.getFileName() + ": " + e.getMessage());
		}
		var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		List<Entry> out = new ArrayList<>();
		for (Tag t : root.getListOrEmpty("cells")) {
			if (!(t instanceof CompoundTag c)) {
				continue;
			}
			out.add(new Entry(c.getIntOr("x", 0), c.getIntOr("y", 0), c.getIntOr("z", 0), NbtUtils.readBlockState(blocks, c.getCompoundOrEmpty("before")),
				NbtUtils.readBlockState(blocks, c.getCompoundOrEmpty("after"))));
		}
		return out;
	}

	// ------------------------------------------------------------------ crash safety

	/**
	 * World start: each removal recorded as pending is settled on the cells ({@link Road#settle}): the cells show their
	 * old blocks (the removal reached the disk) -> the snapshot goes; the road stands (it did not) -> its record comes
	 * back; nothing readable -> kept for the next start.
	 */
	static void reconcile(MinecraftServer server) {
		State s = state;
		if (s.pending().isEmpty() || loadFailed) {
			return;
		}
		Map<String, Road> map = new LinkedHashMap<>(s.byId());
		List<Road.Pending> keep = new ArrayList<>();
		for (Road.Pending p : s.pending()) {
			Path snap = server.getWorldPath(LevelResource.ROOT).resolve(SNAPSHOT_DIR).resolve(p.snapshot());
			ServerLevel level = levelOf(server, p.road().dimension());
			if (level == null || !Files.exists(snap)) {
				if (!Files.exists(snap)) {
					continue; // nothing left to settle
				}
				keep.add(p);
				continue;
			}
			try {
				List<Entry> entries = readSnapshot(level, snap);
				int atAfter = 0;
				int atBefore = 0;
				BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
				for (Entry e : entries) {
					BlockState now = level.getBlockState(m.set(e.x(), e.y(), e.z()));
					if (now == e.after()) {
						atAfter++;
					} else if (now == e.before()) {
						atBefore++;
					}
				}
				switch (Road.settle(atAfter, atBefore, entries.size())) {
					case RELEASE -> Files.deleteIfExists(snap);
					case RECORD_BACK -> {
						if (between(p.road().a(), p.road().b()) == null && !map.containsKey(p.road().id())) {
							Files.move(snap, snapshotFile(server, p.road().id()), StandardCopyOption.REPLACE_EXISTING);
							map.put(p.road().id(), p.road());
							AgentCraft.LOGGER.warn("Road {}: its removal did not reach the disk (the road stands), so its record is back", p.road().id());
						}
					}
					case KEEP -> keep.add(p);
				}
			} catch (IOException | RoadException e) {
				AgentCraft.LOGGER.warn("Could not settle removed road {}", p.road().id(), e);
				keep.add(p);
			}
		}
		State next = new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(keep));
		state = next;
		save(server, next);
	}

	private static @Nullable ServerLevel levelOf(MinecraftServer server, String dimension) {
		Identifier key = Identifier.tryParse(dimension);
		return key == null ? null : server.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, key));
	}

	// ------------------------------------------------------------------ state, persistence

	private static void commit(MinecraftServer server, State s) {
		state = s;
		save(server, s);
		changed();
	}

	private static void changed() {
		signature++;
		List<Road> list = all();
		for (Consumer<List<Road>> l : LISTENERS) {
			try {
				l.accept(list);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("Roads listener failed", t);
			}
		}
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	private static void save(MinecraftServer server, State s) {
		if (loadFailed) {
			return;
		}
		Path f = file(server);
		try {
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, GSON.toJson(new Road.FileData(List.copyOf(s.byId().values()), s.next(), s.pending()).toJson()), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", f, e);
		}
	}

	private static void load(MinecraftServer server) {
		loadFailed = false;
		Path f = file(server);
		if (!Files.exists(f)) {
			state = State.EMPTY;
			return;
		}
		try {
			Road.FileData d = Road.FileData.fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
			Map<String, Road> map = new LinkedHashMap<>();
			for (Road r : d.roads()) {
				map.put(r.id(), r);
			}
			state = new State(Collections.unmodifiableMap(map), d.next(), d.pending());
			AgentCraft.LOGGER.info("Loaded {} road(s) {}", map.size(), map.keySet());
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("Could not read {}; no roads loaded (the file is left as is)", f, e);
			state = State.EMPTY;
			loadFailed = true;
		}
	}

	// ------------------------------------------------------------------ DevBridge

	/** The roads of this world as JSON (dev.roads.state). Any thread. */
	public static JsonObject json() {
		JsonObject o = new JsonObject();
		o.addProperty("loadFailed", loadFailed);
		o.addProperty("signature", signature);
		JsonArray rs = new JsonArray();
		for (Road r : all()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", r.id());
			j.addProperty("a", r.a());
			j.addProperty("b", r.b());
			j.addProperty("dimension", r.dimension());
			j.addProperty("width", r.width());
			j.addProperty("lanterns", r.lanterns());
			j.addProperty("bridge", r.bridge());
			j.addProperty("created", r.created());
			j.addProperty("length", r.length());
			j.addProperty("cells", r.cellCount());
			j.addProperty("changes", r.changeCount());
			j.addProperty("lanternCount", r.lanternCount());
			j.add("lanternCells", Road.ints(r.lanternCells()));
			JsonArray n = new JsonArray();
			r.notes().forEach(n::add);
			j.add("notes", n);
			boolean aGone = Buildings.get(r.a()) == null;
			boolean bGone = Buildings.get(r.b()) == null;
			j.addProperty("orphan", aGone || bGone);
			rs.add(j);
		}
		o.add("roads", rs);
		JsonArray ps = new JsonArray();
		for (Road.Pending p : state.pending()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", p.road().id());
			j.addProperty("snapshot", p.snapshot());
			j.addProperty("at", p.at());
			ps.add(j);
		}
		o.add("pending", ps);
		return o;
	}
}
