package dev.agentcraft.building;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqWorld;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The buildings of the running world (docs/BUILDINGS.md "Buildings in a world"): placed blueprints, one
 * per repo, persisted in {@code <world>/agentcraft-buildings.json}, loaded when any world starts and
 * cleared when it stops. The world is only changed by {@link #place} and {@link #remove}, which run on
 * explicit commands (or a wizard confirm) on the server thread; reads are safe from any thread.
 *
 * <p><b>Layouts.</b> {@link Anchors#current()} stays the HQ studio in the AgentCraft HQ world. In every
 * other world it is the home building's layout while buildings exist (shown with
 * {@link Anchors#showDerived}, not saved as {@code agentcraft-anchors.json}). Per-repo routing uses
 * {@link #layoutFor}.
 */
public final class Buildings {
	public static final String FILE = "agentcraft-buildings.json";
	public static final String SNAPSHOT_DIR = "agentcraft-buildings";
	/** Block update flags for placing and restoring: sync to clients, no drops, no container spills (no duplicated items on restore). */
	static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Immutable state snapshot: buildings by id (placement order) + the next id number. */
	private record State(Map<String, Building> byId, int next) {
		static final State EMPTY = new State(Map.of(), 1);
	}

	private static volatile State state = State.EMPTY;
	/** Whether the running world publishes the home layout through {@link Anchors} (false in the HQ world). */
	private static volatile boolean drivesAnchors;
	/** The buildings file exists but could not be parsed: placing would overwrite it, so it is refused. */
	private static volatile boolean loadFailed;
	private static final List<Consumer<List<Building>>> LISTENERS = new CopyOnWriteArrayList<>();

	/** Thrown by {@link #place} / {@link #remove} with a message meant for the player. */
	public static final class BuildingException extends Exception {
		public BuildingException(String message) {
			super(message);
		}
	}

	private Buildings() {
	}

	/** Register after {@link Anchors#init()} so the HQ layout is loaded first. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			drivesAnchors = !HqWorld.isHq(server);
			load(server);
			publishHome();
			notifyListeners();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			state = State.EMPTY;
			drivesAnchors = false;
			notifyListeners();
		});
	}

	// ------------------------------------------------------------------ reads

	/** All buildings in placement order. */
	public static List<Building> all() {
		return List.copyOf(state.byId().values());
	}

	public static @Nullable Building get(String id) {
		return state.byId().get(id);
	}

	/** The building that hosts {@code repoId}, or null. */
	public static @Nullable Building forRepo(String repoId) {
		for (Building b : state.byId().values()) {
			if (b.hasRepo(repoId)) {
				return b;
			}
		}
		return null;
	}

	/** The home building (idle agents and repos without a building go there), or null when there are none. */
	public static @Nullable Building home() {
		for (Building b : state.byId().values()) {
			if (b.home()) {
				return b;
			}
		}
		return null;
	}

	/**
	 * The layout agents working on {@code repoId} use: that repo's building, otherwise
	 * {@link Anchors#current()} (the home building in a world with buildings, the studio in the HQ world,
	 * empty otherwise). A building's layout is named {@code building:<id>} and its revision is its
	 * {@code placedAt}; the home layout seen through {@code Anchors.current()} has Anchors' own revision.
	 * {@code null} repo = home. Safe from any thread.
	 */
	public static Anchors.Layout layoutFor(@Nullable String repoId) {
		Building b = repoId == null ? null : forRepo(repoId);
		return b != null ? b.layout() : Anchors.current();
	}

	/** Called with the new list after every change (place, remove, home, load, world stop), on the thread that made it. */
	public static void addListener(Consumer<List<Building>> listener) {
		LISTENERS.add(listener);
	}

	// ------------------------------------------------------------------ place

	/**
	 * Places {@code bp} with its rotated minimum corner at {@code origin} and records it as a building for
	 * {@code repos} (wing n = repos[n-1]). Refuses when: a repo already has a building, there are more
	 * repos than wings, the box leaves the build height, it overlaps another building (never forced), or
	 * (unless {@code force}) it would overwrite block entities that are not AgentCraft stations. The
	 * blocks and block entities of the box are saved first; {@link #remove} restores them. Server thread.
	 */
	public static Building place(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rotation, List<String> repos, boolean force)
		throws BuildingException {
		MinecraftServer server = level.getServer();
		if (repos.isEmpty()) {
			throw new BuildingException("Name at least one repo");
		}
		if (repos.size() > bp.wings()) {
			throw new BuildingException("Blueprint " + bp.id() + " has " + bp.wings() + " wing(s); " + repos.size() + " repos given");
		}
		for (String r : repos) {
			if (r.isBlank() || repos.indexOf(r) != repos.lastIndexOf(r)) {
				throw new BuildingException("Bad or repeated repo id '" + r + "'");
			}
			Building has = forRepo(r);
			if (has != null) {
				throw new BuildingException("Repo " + r + " already has building " + has.id() + " (" + has.blueprint() + ")");
			}
		}
		StructureTemplate template = Blueprints.template(bp.id());
		if (template == null) {
			throw new BuildingException("Blueprint " + bp.id() + " has no loaded template");
		}
		int turns = rotation.ordinal();
		StructurePlaceSettings settings = settings(rotation);
		// vanilla rotates about the template's origin cell, so a rotated template extends to negative
		// offsets: shift the placement position so the rotated box's minimum corner is `origin`
		BoundingBox atZero = template.getBoundingBox(settings, BlockPos.ZERO);
		BlockPos placePos = origin.offset(-atZero.minX(), -atZero.minY(), -atZero.minZ());
		BoundingBox bb = template.getBoundingBox(settings, placePos);
		if (bb.minX() != origin.getX() || bb.minY() != origin.getY() || bb.minZ() != origin.getZ()) {
			throw new BuildingException("Internal: rotated box " + bb + " does not start at " + origin.toShortString());
		}
		Anchors.Bounds box = new Anchors.Bounds(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
		if (box.minY() < level.getMinY() || box.maxY() > level.getMaxY()) {
			throw new BuildingException("Box " + str(box) + " leaves the build height (" + level.getMinY() + ".." + level.getMaxY() + ")");
		}
		for (Building other : state.byId().values()) {
			if (Building.intersects(other.box(), box)) {
				throw new BuildingException("Box " + str(box) + " overlaps building " + other.id() + " " + str(other.box())
					+ " (remove it first or place elsewhere)");
			}
		}
		if (!force) {
			List<String> foreign = foreignBlockEntities(level, box);
			if (!foreign.isEmpty()) {
				throw new BuildingException("Box " + str(box) + " contains " + foreign.size() + " block entit" + (foreign.size() == 1 ? "y" : "ies")
					+ " the mod did not place (" + String.join(", ", foreign.subList(0, Math.min(4, foreign.size())))
					+ (foreign.size() > 4 ? ", ..." : "") + "); add force to overwrite them (they come back on remove)");
			}
		}

		if (loadFailed) {
			throw new BuildingException(FILE + " could not be read when the world started (see the log); fix or move it, then restart");
		}
		State s = state;
		int next = s.next();
		while (Files.exists(snapshotFile(server, "b" + next))) {
			next++; // never reuse an id whose snapshot is still on disk
		}
		String id = "b" + next;
		Path snap = snapshotFile(server, id);
		snapshot(level, box, snap);

		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		Building b;
		int connected;
		int bound;
		try {
			if (!template.placeInWorld(level, placePos, placePos, settings, level.getRandom(), FLAGS)) {
				throw new IllegalStateException("template " + bp.id() + " placed nothing (empty template?)");
			}
			connected = connectPanels(level, box);
			bound = rewriteBindings(level, box, repos);
			clearDrops(level, box);
			b = new Building(id, bp.id(), repos, map.isEmpty(), BlueprintTransform.rotationName(turns), box,
				BlueprintTransform.worldBounds(bp, turns, box.minX(), box.minY(), box.minZ()),
				BlueprintTransform.worldAnchors(bp, turns, box.minX(), box.minY(), box.minZ(), repos), System.currentTimeMillis());
		} catch (RuntimeException e) {
			// never leave a half-built, unrecorded box behind: put the snapshot back
			AgentCraft.LOGGER.error("Placing {} at {} failed; restoring box {}", bp.id(), origin.toShortString(), str(box), e);
			restore(level, box, snap);
			try {
				Files.deleteIfExists(snap);
			} catch (IOException io) {
				AgentCraft.LOGGER.warn("Could not delete {}", snap, io);
			}
			throw new BuildingException("Placing " + bp.id() + " failed (" + e.getMessage() + "); the area was restored");
		}
		map.put(id, b);
		commit(server, new State(Collections.unmodifiableMap(map), next + 1));
		AgentCraft.LOGGER.info("Placed building {} ({}) for {} at {} rotation {}: box {}, {} anchors, {} panels connected, {} bindings rewritten{}",
			id, bp.id(), repos, origin.toShortString(), b.rotation(), str(box), b.anchors().size(), connected, bound, force ? " (forced)" : "");
		return b;
	}

	// ------------------------------------------------------------------ remove / home

	/** Puts back exactly what was in the building's box before it was placed, then forgets the building. Server thread. */
	public static Building remove(ServerLevel level, String id) throws BuildingException {
		Building b = get(id);
		if (b == null) {
			throw new BuildingException("No building " + id + " (see /agentcraft buildings)");
		}
		MinecraftServer server = level.getServer();
		Path snap = snapshotFile(server, id);
		if (!Files.exists(snap)) {
			throw new BuildingException("Snapshot " + snap.getFileName() + " is missing, so " + id + " cannot be restored; "
				+ "/agentcraft remove " + id + " forget drops the record and leaves the blocks");
		}
		restore(level, b.box(), snap);
		try {
			Files.delete(snap);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not delete {}", snap, e);
		}
		forget(server, id);
		AgentCraft.LOGGER.info("Removed building {} ({}): restored box {}", id, b.blueprint(), str(b.box()));
		return b;
	}

	/** Drops the record of a building without touching the world (and deletes its snapshot). */
	public static void forget(MinecraftServer server, String id) throws BuildingException {
		State s = state;
		Building b = s.byId().get(id);
		if (b == null) {
			throw new BuildingException("No building " + id);
		}
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		if (b.home() && !map.isEmpty()) {
			var first = map.entrySet().iterator().next();
			first.setValue(first.getValue().withHome(true));
		}
		try {
			Files.deleteIfExists(snapshotFile(server, id));
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not delete the snapshot of {}", id, e);
		}
		commit(server, new State(Collections.unmodifiableMap(map), s.next()));
	}

	/** Makes {@code id} the home building. Server thread. */
	public static Building setHome(MinecraftServer server, String id) throws BuildingException {
		State s = state;
		if (!s.byId().containsKey(id)) {
			throw new BuildingException("No building " + id);
		}
		Map<String, Building> map = new LinkedHashMap<>();
		s.byId().forEach((k, v) -> map.put(k, v.withHome(k.equals(id))));
		commit(server, new State(Collections.unmodifiableMap(map), s.next()));
		return map.get(id);
	}

	// ------------------------------------------------------------------ world work

	private static StructurePlaceSettings settings(Rotation rotation) {
		return new StructurePlaceSettings().setRotation(rotation).setMirror(Mirror.NONE).setIgnoreEntities(true)
			.setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
	}

	/** Positions + types of block entities in the box that are not AgentCraft stations. */
	private static List<String> foreignBlockEntities(ServerLevel level, Anchors.Bounds box) {
		List<String> out = new ArrayList<>();
		forEachBlockEntity(level, box, be -> {
			if (!(be instanceof StationBlockEntity)) {
				out.add(be.getBlockPos().toShortString() + " " + be.getBlockState().getBlock().getDescriptionId().replace("block.minecraft.", ""));
			}
		});
		return out;
	}

	private static void forEachBlockEntity(ServerLevel level, Anchors.Bounds box, Consumer<BlockEntity> action) {
		for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
			for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
				for (BlockEntity be : List.copyOf(level.getChunk(cx, cz).getBlockEntities().values())) {
					BlockPos p = be.getBlockPos();
					if (box.contains(p.getX(), p.getY(), p.getZ())) {
						action.accept(be);
					}
				}
			}
		}
	}

	/**
	 * Saves the box's blocks (air included) and block entities as a structure template. Verifies the
	 * capture is complete (one entry per cell) and readable before anything is placed.
	 */
	private static void snapshot(ServerLevel level, Anchors.Bounds box, Path file) throws BuildingException {
		BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
		net.minecraft.core.Vec3i size = new net.minecraft.core.Vec3i(box.maxX() - box.minX() + 1, box.maxY() - box.minY() + 1, box.maxZ() - box.minZ() + 1);
		StructureTemplate before = new StructureTemplate();
		before.setAuthor("agentcraft");
		before.fillFromWorld(level, min, size, false, List.of());
		CompoundTag tag = before.save(new CompoundTag());
		long volume = (long) size.getX() * size.getY() * size.getZ();
		int captured = tag.getListOrEmpty("blocks").size();
		if (captured != volume) {
			throw new BuildingException("Snapshot captured " + captured + " of " + volume + " blocks; nothing was placed");
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			NbtIo.writeCompressed(tag, tmp);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not write snapshot {}", file, e);
			throw new BuildingException("Could not save the snapshot " + file.getFileName() + " (" + e.getMessage() + "); nothing was placed");
		}
	}

	private static void restore(ServerLevel level, Anchors.Bounds box, Path file) throws BuildingException {
		StructureTemplate before = new StructureTemplate();
		try {
			before.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
		} catch (IOException e) {
			throw new BuildingException("Could not read the snapshot " + file.getFileName() + ": " + e.getMessage());
		}
		BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
		before.placeInWorld(level, min, min, settings(Rotation.NONE), level.getRandom(), FLAGS);
		clearDrops(level, box);
	}

	/** Recomputes up/down/left/right of every monitor / task board in the box (templates store them false). */
	private static int connectPanels(ServerLevel level, Anchors.Bounds box) {
		int n = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int y = box.minY(); y <= box.maxY(); y++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				for (int x = box.minX(); x <= box.maxX(); x++) {
					m.set(x, y, z);
					BlockState cur = level.getBlockState(m);
					if (cur.getBlock() instanceof PanelBlock panel) {
						BlockState want = panel.connect(cur, level, m);
						if (want != cur) {
							level.setBlock(m, want, FLAGS | Block.UPDATE_KNOWN_SHAPE);
							n++;
						}
					}
				}
			}
		}
		return n;
	}

	/** {@code repo:#n} / {@code ci:#n} -> the n-th repo in every station block entity of the box. */
	private static int rewriteBindings(ServerLevel level, Anchors.Bounds box, List<String> repos) {
		int[] n = {0};
		forEachBlockEntity(level, box, be -> {
			if (be instanceof StationBlockEntity station) {
				String rewritten = BlueprintTransform.rewriteBinding(station.binding(), repos);
				if (rewritten != null) {
					station.setBinding(rewritten);
					n[0]++;
				}
			}
		});
		return n[0];
	}

	private static void clearDrops(ServerLevel level, Anchors.Bounds box) {
		AABB area = new AABB(box.minX() - 1, box.minY() - 1, box.minZ() - 1, box.maxX() + 2, box.maxY() + 2, box.maxZ() + 2);
		level.getEntitiesOfClass(ItemEntity.class, area).forEach(e -> e.discard());
		level.getEntitiesOfClass(ExperienceOrb.class, area).forEach(e -> e.discard());
	}

	// ------------------------------------------------------------------ state, persistence, layout

	private static void commit(MinecraftServer server, State s) {
		state = s;
		save(server, s);
		publishHome();
		notifyListeners();
	}

	/** Outside the HQ world: Anchors.current() = the home building's layout (EMPTY without buildings). */
	private static void publishHome() {
		if (!drivesAnchors) {
			return;
		}
		Building home = home();
		Anchors.showDerived(home == null ? Anchors.Layout.EMPTY : home.layout());
	}

	private static void notifyListeners() {
		List<Building> list = all();
		for (Consumer<List<Building>> l : LISTENERS) {
			try {
				l.accept(list);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("Buildings listener failed", t);
			}
		}
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	static Path snapshotFile(MinecraftServer server, String id) {
		return server.getWorldPath(LevelResource.ROOT).resolve(SNAPSHOT_DIR).resolve(id + ".before.nbt");
	}

	private static void save(MinecraftServer server, State s) {
		if (loadFailed) {
			return;
		}
		Path f = file(server);
		try {
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, GSON.toJson(Building.fileJson(List.copyOf(s.byId().values()), s.next())), StandardCharsets.UTF_8);
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
			Building.FileData data = Building.fileFromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
			Map<String, Building> map = new LinkedHashMap<>();
			for (Building b : data.buildings()) {
				map.put(b.id(), b);
			}
			if (!map.isEmpty() && map.values().stream().noneMatch(Building::home)) {
				var first = map.entrySet().iterator().next();
				first.setValue(first.getValue().withHome(true));
			}
			state = new State(Collections.unmodifiableMap(map), data.next());
			AgentCraft.LOGGER.info("Loaded {} building(s) {}", map.size(), map.keySet());
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("Could not read {}; no buildings loaded (the file is left as is)", f, e);
			state = State.EMPTY;
			loadFailed = true;
		}
	}

	static String str(Anchors.Bounds b) {
		return b.minX() + "," + b.minY() + "," + b.minZ() + " .. " + b.maxX() + "," + b.maxY() + "," + b.maxZ();
	}
}
