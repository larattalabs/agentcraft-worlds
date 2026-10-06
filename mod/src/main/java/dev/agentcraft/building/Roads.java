package dev.agentcraft.building;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.journal.Journal;
import dev.agentcraft.journal.JournalStore;
import dev.agentcraft.journal.WorldJournal;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Roads between buildings (docs/VILLAGE.md V1, docs/BUILDINGS.md "Roads"), server side: the laid roads of the running
 * world ({@code <world>/agentcraft-roads.json}), laying one from a route the client planned ({@link #lay}: everything is
 * checked and planned again here on the server's own level, since the client's view can be stale) and removing one
 * ({@link #remove}: every cell that still holds what the road put there gets its old block back; cells the player
 * changed since, or that a building now covers, are left alone). Each road is a per-cell entry of the world journal
 * (contract J1, {@code dev.agentcraft.journal}), never a box, so a removal never reverts anything else. Blocks are set with no side effects (no neighbour or shape updates, no drops, no
 * falling), and new drops around the road are cleared anyway. Loaded when a world starts, cleared when it stops.
 * Server thread, except the read-only views ({@link #all}, {@link #feetCells}) which any thread may use.
 */
public final class Roads {
	public static final String FILE = "agentcraft-roads.json";
	/** The folder the roads' snapshots were kept in before the world journal; imported names still resolve against it. */
	public static final String SNAPSHOT_DIR = "agentcraft-roads";
	/** The world journal kind of a road's entry (its owner is the road's id). */
	static final String KIND = "road";
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
		ServerTickEvents.END_SERVER_TICK.register(server -> CellDrops.tick());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			state = State.EMPTY;
			loadFailed = false;
			CellDrops.LATER.clear();
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
		return changedCells(state, dimension);
	}

	private static LongSet changedCells(State st, String dimension) {
		LongOpenHashSet s = new LongOpenHashSet();
		for (Road r : st.byId().values()) {
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
		return lay(level, a, b, route, o, null);
	}

	/**
	 * As {@link #lay(ServerLevel, String, String, long[], RoadPlan.Options)}, but with {@code previewHash} (the
	 * {@link RoadPlan#hash} of the ghost the player confirmed) it lays only that: when this level's plan differs, it
	 * refuses and nothing is done.
	 */
	public static Laid lay(ServerLevel level, String a, String b, long[] route, RoadPlan.Options o, @Nullable Long previewHash) throws RoadException {
		if (loadFailed) {
			throw new RoadException(FILE + " could not be read (see the log): no roads are laid until it is fixed or moved away");
		}
		requireJournal();
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
		if (previewHash != null && RoadPlan.hash(plan.ops()) != previewHash) {
			throw new RoadException("The ground changed since the preview: preview the road again; nothing was done");
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
		refuseOccupied(level, ops, before, after, "laying it");
		State s = state;
		String id = "r" + s.next();
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
		// the journal entry (each changed cell's before and after), then the record, then the blocks
		Journal.Entry entry;
		try {
			String jid = WorldJournal.newId();
			long layer = WorldJournal.newLayer();
			List<Journal.Cell> jc = new ArrayList<>(ops.size());
			for (int i = 0; i < ops.size(); i++) {
				RoadPlan.Op op = ops.get(i);
				jc.add(new Journal.Cell(Journal.pos(op.x(), op.y(), op.z()), layer, new Journal.Value(NbtUtils.writeBlockState(before.get(i)), null),
					new Journal.Value(NbtUtils.writeBlockState(after.get(i)), null)));
			}
			entry = new Journal.Entry(jid, KIND, id, dim, Journal.Policy.CELL, road.created(), Journal.Status.ACTIVE, jc, null, road.toJson());
			WorldJournal.commit(Map.of(jid, entry), List.of());
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save road {} in the world journal", id, e);
			throw new RoadException("Could not save the road's snapshot (" + e.getMessage() + "); nothing was laid");
		}
		Map<String, Road> map = new LinkedHashMap<>(s.byId());
		map.put(id, road);
		try {
			commit(level.getServer(), s, new State(Collections.unmodifiableMap(map), s.next() + 1, s.pending()));
		} catch (RoadException e) {
			try {
				WorldJournal.commit(Map.of(), List.of(entry.id()));
			} catch (IOException ex) {
				AgentCraft.LOGGER.warn("Could not release journal entry {} (the next start brings road {} back from it)", entry.id(), id, ex);
			}
			throw new RoadException(e.getMessage() + "; nothing was laid");
		}
		// the blocks last: a crash before this leaves a record whose cells do not hold the road (Remove then leaves them)
		CellDrops drops = CellDrops.before(level, ops);
		for (int i = 0; i < ops.size(); i++) {
			RoadPlan.Op op = ops.get(i);
			level.setBlock(m.set(op.x(), op.y(), op.z()), after.get(i), FLAGS);
		}
		drops.clearNew(level);
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
	static void refuseOccupied(ServerLevel level, List<RoadPlan.Op> ops, List<BlockState> from, List<BlockState> to, String verb) throws RoadException {
		LongOpenHashSet solid = new LongOpenHashSet();
		int[] bb = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int i = 0; i < ops.size(); i++) {
			RoadPlan.Op op = ops.get(i);
			m.set(op.x(), op.y(), op.z());
			VoxelShape was = from.get(i).getCollisionShape(level, m);
			VoxelShape is = to.get(i).getCollisionShape(level, m);
			if (!RoadPlan.canTrap(was.isEmpty(), was.isEmpty() ? 0 : was.max(Direction.Axis.Y), is.isEmpty(), is.isEmpty() ? 0 : is.max(Direction.Axis.Y))) {
				continue; // nothing new to stand in (a ground swap, a block cleared)
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
				box = box.inflate(0.3, 0, 0.3); // a step away from the edge of a new block (sideways: not into the ground below)
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

	/**
	 * Whether a cell still holds what the road put there. Fences and lanterns are compared by block (a neighbour update
	 * reshapes a fence's connections, a lantern or fence may take water), slabs by block and slab type (waterlogged or
	 * not); everything else by exact state.
	 */
	static boolean stillOurs(BlockState now, BlockState placed) {
		if (now == placed) {
			return true;
		}
		if (now.getBlock() != placed.getBlock()) {
			return false;
		}
		Block b = now.getBlock();
		if (b instanceof FenceBlock || b instanceof LanternBlock) {
			return true;
		}
		if (b instanceof SlabBlock) {
			return now.getValue(SlabBlock.TYPE) == placed.getValue(SlabBlock.TYPE);
		}
		return false;
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

	/**
	 * The dropped items and XP a road change might make, cleared afterwards (now and 3 ticks later), but only those that
	 * are new and lie within a block of a changed cell: a road's box can span the whole village, and the player's own drops
	 * there (a mob they killed, an item they threw, a farm's output) are never touched. The flags already suppress drops;
	 * this is the backstop.
	 */
	static final class CellDrops {
		static final List<Object[]> LATER = new ArrayList<>();
		private final AABB area;
		private final LongSet cells;
		private final java.util.Set<java.util.UUID> before = new java.util.HashSet<>();

		private CellDrops(AABB area, LongSet cells) {
			this.area = area;
			this.cells = cells;
		}

		static CellDrops before(ServerLevel level, List<RoadPlan.Op> ops) {
			LongOpenHashSet cells = new LongOpenHashSet();
			for (RoadPlan.Op op : ops) {
				cells.add(WalkCell.pack(op.x(), op.y(), op.z()));
			}
			Anchors.Bounds b = RoadPlan.box(ops);
			AABB area = b == null ? new AABB(0, 0, 0, 0, 0, 0) : Occupancy.aabb(b).inflate(1);
			CellDrops d = new CellDrops(area, cells);
			if (b != null) {
				level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).forEach(e -> d.before.add(e.getUUID()));
				level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, area).forEach(e -> d.before.add(e.getUUID()));
			}
			return d;
		}

		void clearNew(ServerLevel level) {
			if (cells.isEmpty()) {
				return;
			}
			clear(level);
			LATER.add(new Object[] {this, level, 3});
		}

		private boolean near(Entity e) {
			BlockPos p = e.blockPosition();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (cells.contains(WalkCell.pack(p.getX() + dx, p.getY() + dy, p.getZ() + dz))) {
							return true;
						}
					}
				}
			}
			return false;
		}

		private int clear(ServerLevel level) {
			int n = 0;
			for (Entity e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area)) {
				if (!before.contains(e.getUUID()) && near(e)) {
					e.discard();
					n++;
				}
			}
			for (Entity e : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, area)) {
				if (!before.contains(e.getUUID()) && near(e)) {
					e.discard();
					n++;
				}
			}
			return n;
		}

		static void tick() {
			for (var it = LATER.iterator(); it.hasNext();) {
				Object[] x = it.next();
				int left = (Integer) x[2] - 1;
				if (left > 0) {
					x[2] = left;
					continue;
				}
				it.remove();
				((CellDrops) x[0]).clear((ServerLevel) x[1]);
			}
		}
	}

	// ------------------------------------------------------------------ remove

	/**
	 * What {@link #remove} did: {@code restored} cells got their old block back, {@code changed} were left alone because
	 * the player changed them since, {@code covered} because a building now covers them.
	 */
	public record Removed(Road road, int restored, int changed, int covered, Map<String, Integer> handed) {
		public String message() {
			String m = "Removed road " + road.id() + " (" + road.a() + " to " + road.b() + "): " + restored + " cell" + (restored == 1 ? "" : "s")
				+ " back as they were";
			for (Map.Entry<String, Integer> h : handed.entrySet()) {
				m += "; " + h.getValue() + " cell" + (h.getValue() == 1 ? "" : "s") + " kept for road " + h.getKey() + " (it runs there too)";
			}
			if (changed > 0) {
				m += "; " + changed + " cell" + (changed == 1 ? "" : "s") + " you changed since left alone";
			}
			if (covered > 0) {
				m += "; " + covered + " under a building left alone";
			}
			return m;
		}
	}

	/**
	 * Removes road {@code id}: the undo of its world journal entry (contract J1). Every cell on top that still holds what
	 * the road put there gets its old block back (in the order ground first, then what stood on it); cells the player
	 * changed since are left as they are; cells a newer change covers (a building or fixture placed over the road) are
	 * left in the world and handed down to that change, so its own removal later restores the ground, not the road. Cells
	 * another road still runs on ({@link Road#handover}: a newer road that shared this one's walkway left them to it) stay,
	 * and that road's entry takes them over (keeping their layer, and its changes), so it keeps no holes. Refuses while a
	 * player or a pet stands where an old block comes back. The undone entry is kept (as a pending removal) until the next
	 * world start confirms the restored cells reached the disk. Server thread.
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
		requireJournal();
		String jid = entryOf(id);
		if (jid == null) {
			throw new RoadException("The snapshot of " + id + " is missing, so it cannot be restored; Forget drops the record and leaves the blocks");
		}
		Journal.Entry entry;
		try {
			entry = WorldJournal.load(jid);
		} catch (IOException e) {
			throw new RoadException("Could not read the road's snapshot " + jid + ": " + e.getMessage());
		}
		for (Journal.Cell c : entry.cells()) {
			int x = Journal.x(c.pos());
			int z = Journal.z(c.pos());
			if (!level.hasChunk(x >> 4, z >> 4)) {
				throw new RoadException("Chunks around " + x + ", " + z + " are not loaded: walk closer to the road and try again");
			}
		}
		// what the undo would put back: the cells on top that still hold what the road put there
		Journal.UndoPlan first;
		try {
			first = WorldJournal.planUndo(level, List.of(jid), jid, Roads::stillOurs);
		} catch (IOException e) {
			throw new RoadException("Could not read the world journal (" + e.getMessage() + "); nothing was done");
		}
		// cells another road still runs on stay and become that road's (Road.handover), keeping their layer
		List<Road> others = new ArrayList<>();
		for (Road q : state.byId().values()) {
			if (!q.id().equals(id) && q.dimension().equals(dim)) {
				others.add(q);
			}
		}
		List<Journal.Write> candidates = new ArrayList<>(first.writes());
		int[] cand = new int[candidates.size() * 3];
		for (int i = 0; i < candidates.size(); i++) {
			long pos = candidates.get(i).pos();
			cand[3 * i] = Journal.x(pos);
			cand[3 * i + 1] = Journal.y(pos);
			cand[3 * i + 2] = Journal.z(pos);
		}
		Map<Integer, String> handover = Road.handover(cand, others);
		Map<String, java.util.Set<Long>> given = new LinkedHashMap<>();
		handover.forEach((i, to) -> given.computeIfAbsent(to, k -> new java.util.HashSet<>()).add(candidates.get(i).pos()));
		Map<String, Journal.Entry> transferred = new LinkedHashMap<>();
		Journal.Entry from = entry;
		try {
			for (var g : given.entrySet()) {
				String qj = entryOf(g.getKey());
				if (qj == null) {
					throw new RoadException("The snapshot of " + g.getKey() + " is missing, so it cannot take over " + id + "'s cells");
				}
				Journal.Entry q = transferred.containsKey(qj) ? transferred.get(qj) : WorldJournal.load(qj);
				Map<String, Journal.Entry> t = Journal.transfer(from, q, g.getValue());
				from = t.get(from.id());
				transferred.put(qj, t.get(qj));
			}
		} catch (IOException e) {
			throw new RoadException("Could not read the world journal (" + e.getMessage() + "); nothing was done");
		}
		Journal.UndoPlan plan;
		try {
			plan = transferred.isEmpty() ? first : WorldJournal.planUndo(level, List.of(jid), jid, Roads::stillOurs, Map.of(from.id(), from), transferred);
		} catch (IOException e) {
			throw new RoadException("Could not read the world journal (" + e.getMessage() + "); nothing was done");
		}
		List<RoadPlan.Op> ops = new ArrayList<>();
		List<BlockState> fromStates = new ArrayList<>();
		List<BlockState> to = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Journal.Write w : plan.writes()) {
			ops.add(new RoadPlan.Op(Journal.x(w.pos()), Journal.y(w.pos()), Journal.z(w.pos()), RoadPlan.Block.AIR, 0));
			fromStates.add(level.getBlockState(m.set(Journal.x(w.pos()), Journal.y(w.pos()), Journal.z(w.pos()))));
			to.add(WorldJournal.state(level, w.value()));
		}
		refuseOccupied(level, ops, fromStates, to, "removing it");
		// the journal (the undo, the cells handed over), then the record (a pending removal naming the entry), then the
		// blocks: a crash in between leaves a pending removal settled on the cells at the next start
		Map<String, Journal.Entry> up = new LinkedHashMap<>(plan.updated());
		up.putAll(transferred);
		Map<String, Journal.Entry> previous = new LinkedHashMap<>();
		previous.put(entry.id(), entry);
		try {
			for (String qj : transferred.keySet()) {
				previous.put(qj, WorldJournal.load(qj));
			}
			WorldJournal.commit(up, List.of());
		} catch (IOException e) {
			throw new RoadException("Could not save the world journal (" + e.getMessage() + "); nothing was done");
		}
		State s = state;
		Map<String, Road> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		Map<String, Integer> handed = new LinkedHashMap<>();
		for (var g : given.entrySet()) {
			Road q = s.byId().get(g.getKey());
			int[] more = new int[g.getValue().size() * 3];
			int i = 0;
			for (long pos : g.getValue()) {
				more[i++] = Journal.x(pos);
				more[i++] = Journal.y(pos);
				more[i++] = Journal.z(pos);
			}
			map.put(q.id(), q.withChanges(more));
			handed.put(q.id(), g.getValue().size());
		}
		List<Road.Pending> pending = new ArrayList<>(s.pending());
		pending.add(new Road.Pending(r, jid, System.currentTimeMillis()));
		try {
			commit(server, s, new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(pending)));
		} catch (RoadException ex) {
			try {
				// take the commit back: hand-downs (to a site over the road) reversed, the road and its receivers as they were
				Map<String, Journal.Entry> back = new LinkedHashMap<>(Journal.reactivate(up.values(), jid));
				back.putAll(previous);
				WorldJournal.commit(back, List.of());
			} catch (IOException | RuntimeException again) {
				AgentCraft.LOGGER.warn("Could not put road {}'s journal entries back (the next start settles it)", id, again);
			}
			throw ex;
		}
		CellDrops drops = CellDrops.before(level, ops);
		WorldJournal.apply(level, plan, Buildings.FLAGS, FLAGS);
		drops.clearNew(level);
		Journal.Stats st = plan.stats().get(jid);
		Removed out = new Removed(r, plan.writes().size(), st.changed(), st.covered(), Collections.unmodifiableMap(handed));
		lastNote = out.changed() + out.covered() + handed.size() == 0 ? null : out.message();
		AgentCraft.LOGGER.info("{}", out.message());
		return out;
	}

	/** The journal entry of road {@code id}'s cells (active), or null. */
	static @Nullable String entryOf(String id) {
		List<JournalStore.Meta> ms = WorldJournal.find(KIND, id, Journal.Status.ACTIVE);
		return ms.isEmpty() ? null : ms.get(ms.size() - 1).id();
	}

	/** Refuses world changes while the world journal cannot be read. */
	private static void requireJournal() throws RoadException {
		String why = WorldJournal.unavailable();
		if (why != null) {
			throw new RoadException(why);
		}
	}

	/** Drops road {@code id}'s record and leaves its blocks (its snapshot is missing or unwanted). Server thread. */
	public static Road forget(MinecraftServer server, String id) throws RoadException {
		Road r = get(id);
		if (r == null) {
			throw new RoadException("No road " + id);
		}
		requireJournal();
		// the journal first: a record without its entry only refuses Remove; an entry without a record would bring it back
		List<String> release = new ArrayList<>();
		for (JournalStore.Meta m : WorldJournal.find(KIND, id, Journal.Status.ACTIVE)) {
			release.add(m.id());
		}
		try {
			WorldJournal.commit(Map.of(), release);
		} catch (IOException e) {
			throw new RoadException("Could not save the world journal (" + e.getMessage() + "); " + id + " was not forgotten");
		}
		State s = state;
		Map<String, Road> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		commit(server, s, new State(Collections.unmodifiableMap(map), s.next(), s.pending()));
		AgentCraft.LOGGER.info("Forgot road {} (its blocks stay)", id);
		return r;
	}

	// ------------------------------------------------------------------ crash safety

	/**
	 * World start: first the crash windows between the journal and {@link #FILE} ({@link #repair}), then each removal
	 * recorded as pending is settled on the cells its undo wrote ({@link Road#settle}): the cells show their old blocks
	 * (the removal reached the disk) -> its journal entry is released; the road stands (it did not) -> its record and entry
	 * come back; nothing readable -> kept for the next start.
	 */
	static void reconcile(MinecraftServer server) {
		if (loadFailed) {
			return;
		}
		repairJournal(server);
		State s = state;
		if (s.pending().isEmpty()) {
			return;
		}
		Map<String, Road> map = new LinkedHashMap<>(s.byId());
		List<Road.Pending> keep = new ArrayList<>();
		for (Road.Pending p : s.pending()) {
			String jid = WorldJournal.resolve(SNAPSHOT_DIR, p.snapshot());
			if (jid == null) {
				continue; // nothing left to settle
			}
			ServerLevel level = levelOf(server, p.road().dimension());
			if (level == null) {
				keep.add(p);
				continue;
			}
			try {
				Journal.Entry entry = WorldJournal.load(jid);
				if (entry.active() || entry.undo() == null) {
					continue; // an active entry is a standing road's: nothing to settle
				}
				// cells a standing road changed tell nothing about this removal: a road laid over the same ground later
				// (the pair laid again, a road sharing the walkway) shows road blocks there although the removal reached
				// the disk, and counting them brought removed roads back
				LongSet standing = changedCells(s, p.road().dimension());
				int atAfter = 0;
				int atBefore = 0;
				int telling = 0;
				BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
				for (Journal.Cell c : entry.cells()) {
					if (c.after() == null || !entry.undo().written().containsKey(c.pos())) {
						continue; // only the cells the removal put back tell
					}
					int x = Journal.x(c.pos());
					int y = Journal.y(c.pos());
					int z = Journal.z(c.pos());
					if (standing.contains(WalkCell.pack(x, y, z))) {
						continue;
					}
					telling++;
					BlockState now = level.getBlockState(m.set(x, y, z));
					if (stillOurs(now, WorldJournal.state(level, c.after()))) {
						atAfter++;
					} else if (now == WorldJournal.state(level, entry.undo().written().get(c.pos()))) {
						atBefore++;
					}
				}
				switch (Road.settle(atAfter, atBefore, telling)) {
					case RELEASE -> WorldJournal.commit(Map.of(), List.of(jid));
					case RECORD_BACK -> {
						// against the records being rebuilt here: two removed roads of one pair never both come back
						boolean pairTaken = map.values().stream().anyMatch(q -> q.between(p.road().a(), p.road().b()));
						if (!pairTaken && !map.containsKey(p.road().id())) {
							Map<String, Journal.Entry> loaded = new LinkedHashMap<>();
							loaded.put(jid, entry);
							for (Journal.HandDown h : entry.undo().handed()) {
								if (!loaded.containsKey(h.to()) && WorldJournal.store().meta(h.to()) != null) {
									loaded.put(h.to(), WorldJournal.load(h.to()));
								}
							}
							WorldJournal.commit(Journal.reactivate(loaded.values(), entry.undo().group()), List.of());
							map.put(p.road().id(), p.road());
							AgentCraft.LOGGER.warn("Road {}: its removal did not reach the disk (the road stands), so its record is back", p.road().id());
						}
					}
					case KEEP -> keep.add(p);
				}
			} catch (IOException | RuntimeException e) {
				AgentCraft.LOGGER.warn("Could not settle removed road {}", p.road().id(), e);
				keep.add(p);
			}
		}
		State next = new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(keep));
		state = next;
		save(server, next);
	}

	/** A road entry as {@link #repair} sees it. */
	record RoadEntry(String id, String owner, Journal.Status status, long undoneAt) {
	}

	/** What {@link #repair} made of the records, and what it says. */
	record Repaired(Map<String, Road> roads, List<Road.Pending> pending, List<String> notes) {
	}

	/**
	 * The crash windows between a journal commit and {@link #FILE}'s save, pure (the journal is written first): a road
	 * whose entry was undone with no pending removal naming it becomes a pending removal (settled on the cells); an active
	 * road entry with no record and no pending removal (a road laid whose record was not saved) gets its record back from
	 * the entry's meta, unless its pair has a road. {@code resolve}: a pending snapshot name -> journal id; {@code metaOf}:
	 * an entry's road, or null.
	 */
	static Repaired repair(Map<String, Road> roads, List<Road.Pending> pending, List<RoadEntry> entries,
		java.util.function.Function<String, @Nullable String> resolve, java.util.function.Function<String, @Nullable Road> metaOf) {
		Map<String, Road> map = new LinkedHashMap<>(roads);
		List<Road.Pending> pend = new ArrayList<>(pending);
		List<String> notes = new ArrayList<>();
		// a pending removal whose entry is active again with no record of its road: a world start brought the road back, its
		// journal commit reached the disk and the file did not; the road stands, so its record comes back (else the pending
		// is dropped as settled and the road's entry stays with no record)
		for (Road.Pending p : List.copyOf(pend)) {
			Road gone = p.road();
			String id = resolve.apply(p.snapshot());
			if (id == null || map.containsKey(gone.id())) {
				continue;
			}
			RoadEntry e = entries.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
			if (e == null || e.status() != Journal.Status.ACTIVE || !e.owner().equals(gone.id())) {
				continue;
			}
			if (map.values().stream().anyMatch(q -> q.between(gone.a(), gone.b()))) {
				notes.add(gone.id() + " was brought back before the game stopped but its record was not saved, and its pair has a road now: not added");
				continue;
			}
			pend.remove(p);
			map.put(gone.id(), gone);
			notes.add(gone.id() + " was brought back before the game stopped but " + FILE + " was not saved: its record is back");
		}
		java.util.Set<String> referenced = new java.util.HashSet<>();
		java.util.Set<String> pendingOwners = new java.util.HashSet<>();
		for (Road.Pending p : pend) {
			String id = resolve.apply(p.snapshot());
			if (id != null) {
				referenced.add(id);
			}
			pendingOwners.add(p.road().id());
		}
		for (Road r : List.copyOf(map.values())) {
			boolean active = false;
			RoadEntry undone = null;
			for (RoadEntry e : entries) {
				if (!e.owner().equals(r.id())) {
					continue;
				}
				if (e.status() == Journal.Status.ACTIVE) {
					active = true;
				} else if (!referenced.contains(e.id()) && (undone == null || e.undoneAt() > undone.undoneAt())) {
					undone = e;
				}
			}
			if (!active && undone != null) {
				map.remove(r.id());
				pend.add(new Road.Pending(r, undone.id(), undone.undoneAt()));
				referenced.add(undone.id());
				notes.add(r.id() + "'s removal reached the world journal but not " + FILE + " before the game stopped: it is settled as a removal");
			}
		}
		for (RoadEntry e : entries) {
			if (e.status() != Journal.Status.ACTIVE || map.containsKey(e.owner()) || pendingOwners.contains(e.owner()) || referenced.contains(e.id())) {
				continue;
			}
			Road r = metaOf.apply(e.id());
			if (r == null || !r.id().equals(e.owner())) {
				continue;
			}
			if (map.values().stream().anyMatch(q -> q.between(r.a(), r.b()))) {
				notes.add(r.id() + " was laid before the game stopped but its record was not saved, and its pair has a road now: not added");
				continue;
			}
			map.put(r.id(), r);
			notes.add(r.id() + " was laid before the game stopped but its record was not saved: its record is back");
		}
		return new Repaired(map, pend, notes);
	}

	/** {@link #repair} against the open journal. Server thread, at world start. */
	private static void repairJournal(MinecraftServer server) {
		JournalStore js;
		try {
			js = WorldJournal.store();
		} catch (IOException e) {
			return;
		}
		List<RoadEntry> entries = new ArrayList<>();
		for (JournalStore.Meta m : js.find(m -> m.kind().equals(KIND))) {
			entries.add(new RoadEntry(m.id(), m.owner(), m.status(), m.undoneAt()));
		}
		State s = state;
		Repaired r = repair(s.byId(), s.pending(), entries, snap -> WorldJournal.resolve(SNAPSHOT_DIR, snap), id -> {
			try {
				Journal.Entry e = js.load(id);
				return e.meta() == null ? null : Road.fromJson(e.meta());
			} catch (IOException | RuntimeException ex) {
				AgentCraft.LOGGER.warn("Roads check: could not read the record in journal entry {}", id, ex);
				return null;
			}
		});
		if (r.notes().isEmpty()) {
			return;
		}
		int next = s.next();
		for (Road q : r.roads().values()) {
			next = Math.max(next, Road.number(q.id()) + 1);
		}
		state = new State(Collections.unmodifiableMap(r.roads()), next, List.copyOf(r.pending()));
		save(server, state);
		r.notes().forEach(n -> AgentCraft.LOGGER.warn("Roads check: {}", n));
	}

	private static @Nullable ServerLevel levelOf(MinecraftServer server, String dimension) {
		Identifier key = Identifier.tryParse(dimension);
		return key == null ? null : server.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, key));
	}

	// ------------------------------------------------------------------ state, persistence

	/**
	 * Makes {@code next} the state and writes it; when the file cannot be written, {@code prev} stays the state and this
	 * throws (so no block is touched for a change that would not survive a restart).
	 */
	private static void commit(MinecraftServer server, State prev, State next) throws RoadException {
		if (!save(server, next)) {
			state = prev;
			throw new RoadException("Could not save " + FILE + " (see the log)");
		}
		state = next;
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

	/** Writes {@code s}; false when it could not (the file was unreadable at load, or the write failed). */
	private static boolean save(MinecraftServer server, State s) {
		if (loadFailed) {
			return false;
		}
		Path f = file(server);
		try {
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, GSON.toJson(new Road.FileData(List.copyOf(s.byId().values()), s.next(), s.pending()).toJson()), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", f, e);
			return false;
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
