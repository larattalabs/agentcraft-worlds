package dev.agentcraft.building;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.layout.Anchor;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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

	/** Immutable state snapshot: buildings by id (placement order), the next id number, sites taken down since the last save. */
	private record State(Map<String, Building> byId, int next, List<Building.Pending> pending) {
		static final State EMPTY = new State(Map.of(), 1, List.of());
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
			worldId = LeadRouting.worldIdOf(server.getWorldPath(LevelResource.ROOT));
			load(server);
			reconcile(server);
			publishHome();
			notifyListeners();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> Drops.tick());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			state = State.EMPTY;
			reports.clear();
			Drops.reset();
			drivesAnchors = false;
			worldId = null;
			notifyListeners();
		});
	}

	/** The running world's id (its save folder name) for the Foreman's building keys, or null when no world runs. */
	private static volatile @Nullable String worldId;

	/**
	 * The running world's id: its save folder name ({@link LeadRouting#worldIdOf}); the Foreman keys a
	 * building {@code "<worldId>/<buildingId>"}. Set before the listeners hear about a loaded world, null
	 * before they hear that it stopped. Any thread.
	 */
	public static @Nullable String worldId() {
		return worldId;
	}

	/** Whether the world's buildings file exists but could not be read (no buildings are loaded, placing is refused). */
	public static boolean loadFailed() {
		return loadFailed;
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

	/**
	 * The dimension {@link Anchors#current()} is in: the home building's (outside the HQ world), else the overworld
	 * (the HQ studio; an empty layout). Any thread.
	 */
	public static String homeDimension() {
		Building h = drivesAnchors ? home() : null;
		return h == null ? Building.OVERWORLD : h.dimensionOrDefault();
	}

	/** {@link Anchors#current()} when it is in {@code dimension}, else {@link Anchors.Layout#EMPTY} (home is elsewhere). Any thread. */
	public static Anchors.Layout currentIn(String dimension) {
		return dimension.equals(homeDimension()) ? Anchors.current() : Anchors.Layout.EMPTY;
	}

	/** Published per-state views (built once per state, so per-frame readers never allocate layouts). */
	private record Views(State state, Anchors.Layout current, List<Routing.Site> sites, List<Routing.Region> regions,
		List<Anchors.Layout> layouts, long signature) {
	}

	private static volatile @Nullable Views views;

	private static Views views() {
		State s = state;
		Anchors.Layout cur = Anchors.current();
		Views v = views;
		if (v == null || v.state() != s || v.current() != cur) {
			List<Routing.Site> sites = new ArrayList<>();
			for (Building b : s.byId().values()) {
				sites.add(Routing.Site.of(b));
			}
			List<Routing.Site> list = List.copyOf(sites);
			if (v != null && v.state() == s) {
				list = v.sites(); // only Anchors.current() changed: keep the sites' identity
			}
			List<Routing.Region> regions = Routing.regions(cur, list);
			v = new Views(s, cur, list, regions, Routing.layouts(cur, list), Routing.signature(regions));
			views = v;
		}
		return v;
	}

	/**
	 * The buildings as immutable {@link Routing.Site}s (placement order), rebuilt only when the buildings
	 * change; the same list instance until then. Safe from any thread (the client reads it every tick).
	 */
	public static List<Routing.Site> sites() {
		return views().sites();
	}

	/**
	 * The world regions that belong to a layout ({@link Routing#regions}): {@link Anchors#current()} first,
	 * then every other building. Cached until the buildings or the current layout change. Any thread.
	 */
	public static List<Routing.Region> regions() {
		return views().regions();
	}

	/** Every distinct layout ({@link Routing#layouts}): current first. Cached like {@link #regions()}. Any thread. */
	public static List<Anchors.Layout> layouts() {
		return views().layouts();
	}

	/** Changes whenever {@link #regions()} does (names, revisions, areas). Any thread. */
	public static long regionsSignature() {
		return views().signature();
	}

	/** The layout agents working on {@code repoId} use, from the cached sites ({@link Routing#layoutFor}). Any thread. */
	public static Anchors.Layout routeLayout(@Nullable String repoId) {
		Views v = views();
		return Routing.layoutFor(repoId, v.sites(), v.current());
	}

	/** Called with the new list after every change (place, remove, home, load, world stop), on the thread that made it. */
	public static void addListener(Consumer<List<Building>> listener) {
		LISTENERS.add(listener);
	}

	// ------------------------------------------------------------------ place

	/**
	 * Places {@code bp} with its rotated minimum corner at {@code origin} and records it as a building for
	 * {@code repos} (wing n = repos[n-1]). Refuses when: a repo already has a building, there are more repos than
	 * wings, the box (foundation included) leaves the build height, it overlaps another building (never forced),
	 * lava is in or next to the footprint, a player, pet or other entity that matters is in the way
	 * ({@link Occupancy}), or (unless {@code force}) it would overwrite block entities that are not AgentCraft
	 * stations. The blocks and block entities of the box are saved first; {@link #remove} restores them. Hostile
	 * mobs in the box are removed and water is only a warning: both are in {@link #lastNote()}. Server thread.
	 */
	public static Building place(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rotation, List<String> repos, boolean force)
		throws BuildingException {
		MinecraftServer server = level.getServer();
		checkRepos(bp.id(), bp.wings(), repos, null);
		if (loadFailed) {
			throw new BuildingException(FILE + " could not be read when the world started (see the log); fix or move it, then restart");
		}
		State s = state;
		int next = s.next();
		while (Files.exists(snapshotFile(server, "b" + next))) {
			next++; // never reuse an id whose snapshot is still on disk
		}
		String id = "b" + next;
		Built built = build(level, bp, origin, rotation, repos, force, null, snapshotFile(server, id));
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		long now = System.currentTimeMillis();
		Building b = new Building(id, bp.id(), repos, map.isEmpty(), BlueprintTransform.rotationName(built.turns()), built.box(), built.bounds(),
			built.anchors(), now, dimensionId(level), built.snapshotBox(), now, null, built.pin());
		map.put(id, b);
		commit(server, new State(Collections.unmodifiableMap(map), next + 1, s.pending()));
		lastNote = built.note();
		AgentCraft.LOGGER.info("Placed building {} ({}) for {} at {} rotation {}: box {}, snapshot {}, {} anchors{}{}", id, bp.id(), repos,
			origin.toShortString(), b.rotation(), str(b.box()), str(b.restoreBox()), b.anchors().size(), force ? " (forced)" : "",
			built.note() == null ? "" : "; " + built.note());
		return b;
	}

	/** What the last {@link #place} / {@link #move} had to say beside success (hostile mobs removed, water), or null. Server thread. */
	public static @Nullable String lastNote() {
		return lastNote;
	}

	private static volatile @Nullable String lastNote;

	/** The refusal for "a player in the box" (the ghost uses the same words). */
	public static final String PLAYER_IN_BOX = "you are standing in or next to the box (look further away or nudge it)";

	private static void checkRepos(String blueprint, int wings, List<String> repos, @Nullable String except) throws BuildingException {
		if (repos.isEmpty()) {
			throw new BuildingException("Name at least one repo");
		}
		if (repos.size() > wings) {
			throw new BuildingException("Blueprint " + blueprint + " has " + wings + " wing(s); " + repos.size() + " repos given");
		}
		for (String r : repos) {
			if (r.isBlank() || repos.indexOf(r) != repos.lastIndexOf(r)) {
				throw new BuildingException("Bad or repeated repo id '" + r + "'");
			}
			Building has = forRepo(r);
			if (has != null && !has.id().equals(except)) {
				throw new BuildingException("Repo " + r + " already has building " + has.id() + " (" + has.blueprint() + ")");
			}
		}
	}

	/** A template put into the world (not recorded yet). */
	private record Built(int turns, Anchors.Bounds box, Anchors.Bounds snapshotBox, Anchors.Bounds bounds, Map<String, Anchor> anchors,
		Building.Pin pin, @Nullable String note) {
	}

	/** What a building placed from {@code bp} / {@code grid} with {@code turns} at {@code box} pins (docs/BUILDINGS.md "Blueprint versions"). */
	static Building.Pin pinFor(Blueprint bp, TemplateGrid grid, int turns, Anchors.Bounds box) {
		return new Building.Pin(grid.fingerprint(), bp.wings(), bp.isGroup(), BlueprintTransform.rawWorldAnchors(bp, turns, box.minX(), box.minY(),
			box.minZ()), grid.blockEntityOffsets(turns));
	}

	/** Whether a building's blueprint is loaded as the version it was placed from ({@link #ownGrid}). Any thread. */
	public static boolean ownGridMatches(Building b) {
		return ownGrid(b) != null;
	}

	/**
	 * The loaded template grid of a building's blueprint when it is the one the building was placed from (its pin), else
	 * null: the blueprint is missing, was changed since (regenerated under the same id), or the record predates pins.
	 */
	static @Nullable TemplateGrid ownGrid(Building b) {
		TemplateGrid grid = TemplateGrid.of(b.blueprint());
		return grid != null && b.pin() != null && grid.fingerprint().equals(b.pin().template()) ? grid : null;
	}

	/**
	 * Checks a site and puts the template there: snapshot to {@code snap}, template, panels, bindings, foundation,
	 * cleared terrain, drops caused by it removed. {@code moving}: the building being moved (its own repos are
	 * fine, its own current box is still an overlap). Restores the site and throws when anything fails.
	 */
	private static Built build(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rotation, List<String> repos, boolean force,
		@Nullable Building moving, Path snap) throws BuildingException {
		Blueprints.Entry entry = Blueprints.entry(bp.id());
		if (entry == null) {
			throw new BuildingException("Blueprint " + bp.id() + " has no loaded template");
		}
		StructureTemplate template = entry.template();
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
		TemplateGrid grid = TemplateGrid.of(entry);
		GhostModel model = grid.ghost(turns);
		TerrainFit.Plan plan = TerrainFit.plan(model, box.minX(), box.minY(), box.minZ(), (x, y, z) -> TerrainFit.flags(level, new BlockPos(x, y, z)));
		Anchors.Bounds snapBox = new Anchors.Bounds(box.minX(), Math.min(box.minY(), plan.minY()), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
		if (snapBox.minY() < level.getMinY() || box.maxY() > level.getMaxY()) {
			throw new BuildingException("Box " + str(snapBox) + " leaves the build height (" + level.getMinY() + ".." + level.getMaxY() + ")");
		}
		String here = dimensionId(level);
		for (Building other : state.byId().values()) {
			if (other.dimensionOrDefault().equals(here) && Building.intersects(other.restoreBox(), snapBox)) {
				throw new BuildingException("Box " + str(snapBox) + " overlaps " + (moving != null && other.id().equals(moving.id())
					? "where " + other.id() + " stands now (move it further)" : "building " + other.id() + " " + str(other.box())
					+ " (remove it first or place elsewhere)"));
			}
		}
		String lava = TerrainFit.lavaRefusal(plan);
		if (lava != null) {
			throw new BuildingException("Not here: " + lava + "; a building next to lava burns and floods");
		}
		if (!force) {
			List<String> foreign = foreignBlockEntities(level, snapBox);
			if (!foreign.isEmpty()) {
				throw new BuildingException("Box " + str(snapBox) + " contains " + foreign.size() + " block entit" + (foreign.size() == 1 ? "y" : "ies")
					+ " the mod did not place (" + String.join(", ", foreign.subList(0, Math.min(4, foreign.size()))) + (foreign.size() > 4 ? ", ..." : "")
					+ "); add force to overwrite them (they come back on remove)");
			}
		}
		List<String> doors = straddling(level, snapBox, true);
		if (!doors.isEmpty()) {
			throw new BuildingException("Not placed: a door is cut in half by the box edge (" + String.join(", ", doors.subList(0, Math.min(3, doors.size())))
				+ "); raise, lower or move the building so the door is fully in or out");
		}
		List<Occupancy.Found> found = Occupancy.scan(level, snapBox, e -> false);
		List<String> occupied = Occupancy.refusals(found);
		if (!occupied.isEmpty()) {
			throw new BuildingException("Not placed: " + String.join("; ", occupied));
		}

		snapshot(level, snapBox, snap);
		List<BlockPos> plants = straddlingPositions(level, snapBox, false);
		Drops drops = Drops.before(level, snapBox);
		try {
			int removed = 0;
			for (Entity e : level.getEntities((Entity) null, Occupancy.aabb(snapBox), e -> !(e instanceof Player) && e.isAlive())) {
				if (Occupancy.classify(e).removable()) {
					e.discard();
					removed++;
				}
			}
			if (!template.placeInWorld(level, placePos, placePos, settings, level.getRandom(), FLAGS)) {
				throw new IllegalStateException("template " + bp.id() + " placed nothing (empty template?)");
			}
			connectPanels(level, box);
			rewriteBindings(level, box, repos);
			BlockState foundation = foundationState(bp);
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int i = 0; i < plan.fill().length; i += 3) {
				level.setBlock(m.set(plan.fill()[i], plan.fill()[i + 1], plan.fill()[i + 2]), foundation, FLAGS);
			}
			for (int i = 0; i < plan.clear().length; i += 3) {
				level.setBlock(m.set(plan.clear()[i], plan.clear()[i + 1], plan.clear()[i + 2]), Blocks.AIR.defaultBlockState(), FLAGS);
			}
			// a tall plant whose other half was inside the box (now gone) would float: take its outside half too
			for (BlockPos half : plants) {
				BlockState outside = level.getBlockState(half);
				BlockPos inside = half.getY() < snapBox.minY() ? half.above() : half.below();
				if (!level.getBlockState(inside).is(outside.getBlock())) {
					level.setBlock(half, Blocks.AIR.defaultBlockState(), FLAGS);
				}
			}
			drops.clearNew(level);
			List<String> notes = new ArrayList<>();
			String gone = Occupancy.removalNote(found);
			if (gone != null && removed > 0) {
				notes.add(gone);
			}
			String water = TerrainFit.waterWarning(plan);
			if (water != null) {
				notes.add(water + " (filled below the floor; water next to the walls stays)");
			}
			if (plan.fillCount() > 0) {
				notes.add(plan.fillCount() + " foundation block" + (plan.fillCount() == 1 ? "" : "s"));
			}
			return new Built(turns, box, snapBox, BlueprintTransform.worldBounds(bp, turns, box.minX(), box.minY(), box.minZ()),
				BlueprintTransform.worldAnchors(bp, turns, box.minX(), box.minY(), box.minZ(), repos), pinFor(bp, grid, turns, box),
				notes.isEmpty() ? null : String.join("; ", notes));
		} catch (RuntimeException e) {
			// never leave a half-built, unrecorded box behind: put the snapshot back
			AgentCraft.LOGGER.error("Placing {} at {} failed; restoring box {}", bp.id(), origin.toShortString(), str(snapBox), e);
			restore(level, snapBox, snap);
			try {
				Files.deleteIfExists(snap);
			} catch (IOException io) {
				AgentCraft.LOGGER.warn("Could not delete {}", snap, io);
			}
			throw new BuildingException("Placing " + bp.id() + " failed (" + e.getMessage() + "); the area was restored");
		}
	}

	/**
	 * Two-block-high blocks (doors, tall plants) cut by the box's top or bottom face: one half inside, the other
	 * outside, which the placement would leave orphaned. {@code doors}: only doors (refused, a player's), else only
	 * the others (plants, cleaned up). Server thread.
	 */
	public static List<String> straddling(net.minecraft.world.level.BlockGetter level, Anchors.Bounds box, boolean doors) {
		List<String> out = new ArrayList<>();
		for (BlockPos p : straddlingPositions(level, box, doors)) {
			out.add(p.toShortString());
		}
		return out;
	}

	/** The outside halves of {@link #straddling} (for doors: the inside half's position). */
	private static List<BlockPos> straddlingPositions(net.minecraft.world.level.BlockGetter level, Anchors.Bounds box, boolean doors) {
		List<BlockPos> out = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int z = box.minZ(); z <= box.maxZ(); z++) {
			for (int x = box.minX(); x <= box.maxX(); x++) {
				// bottom face: an upper half inside whose lower half is below the box; top face: the reverse
				for (int[] face : new int[][] {{box.minY(), -1}, {box.maxY(), 1}}) {
					BlockState in = level.getBlockState(m.set(x, face[0], z));
					if (!in.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
						continue;
					}
					DoubleBlockHalf half = in.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
					if (face[1] < 0 ? half != DoubleBlockHalf.UPPER : half != DoubleBlockHalf.LOWER) {
						continue;
					}
					BlockPos outside = new BlockPos(x, face[0] + face[1], z);
					if (!level.getBlockState(outside).is(in.getBlock())) {
						continue;
					}
					boolean door = in.getBlock() instanceof DoorBlock;
					if (door == doors) {
						out.add(doors ? new BlockPos(x, face[0], z) : outside);
					}
				}
			}
		}
		return out;
	}

	/** The blueprint's foundation block, or the default when its id is unknown (logged). */
	static BlockState foundationState(Blueprint bp) {
		Identifier key = Identifier.tryParse(bp.foundationBlock());
		Block b = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
		if (b == null || b == Blocks.AIR) {
			AgentCraft.LOGGER.warn("Blueprint {}: foundationBlock {} is not a block; using {}", bp.id(), bp.foundationBlock(), Blueprint.DEFAULT_FOUNDATION);
			b = Blocks.STONE_BRICKS;
		}
		return b.defaultBlockState();
	}

	// ------------------------------------------------------------------ remove / home / repos / move

	/**
	 * Puts back exactly what was in the building's box (and foundation) before it was placed, then forgets the
	 * building. Refuses, listing them, when the box holds things the building did not bring (containers, beds,
	 * lecterns, item frames, armor stands, dropped items: {@link #removalBlockers}) unless {@code force} (an explicit
	 * second confirm: they are lost). The snapshot is kept until the next world start confirms the restored terrain
	 * reached the disk (crash safety, see {@link #reconcile}). Server thread.
	 */
	public static Building remove(ServerLevel level, String id, boolean force) throws BuildingException {
		Building b = get(id);
		if (b == null) {
			throw new BuildingException("No building " + id + " (see /agentcraft buildings)");
		}
		if (b.dimension() != null && !b.dimension().equals(dimensionId(level))) {
			throw new BuildingException(id + " is in " + b.dimension() + ", not in " + dimensionId(level)
				+ ": remove it from there (removing pastes the saved terrain into the level it is given)");
		}
		MinecraftServer server = level.getServer();
		Path snap = snapshotFile(server, id);
		if (!Files.exists(snap)) {
			throw new BuildingException("Snapshot " + snap.getFileName() + " is missing, so " + id + " cannot be restored; "
				+ "/agentcraft remove " + id + " forget drops the record and leaves the blocks");
		}
		refusePlayerIn(level, b.restoreBox(), id, "removing it");
		if (!force) {
			List<String> blockers = removalBlockers(level, b);
			if (!blockers.isEmpty()) {
				throw new BuildingException(blockersMessage(id, blockers));
			}
		}
		Drops drops = Drops.before(level, b.restoreBox());
		restore(level, b.restoreBox(), snap);
		drops.clearNew(level);
		State s = state;
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		rehome(map, b);
		List<Building.Pending> pending = new ArrayList<>(s.pending());
		pending.add(new Building.Pending(b, snap.getFileName().toString(), System.currentTimeMillis(), "removed"));
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(pending)));
		AgentCraft.LOGGER.info("Removed building {} ({}): restored box {}{}; snapshot kept until the next world start", id, b.blueprint(), str(b.restoreBox()),
			force ? " (forced)" : "");
		return b;
	}

	/** {@link #remove(ServerLevel, String, boolean)} without force. */
	public static Building remove(ServerLevel level, String id) throws BuildingException {
		return remove(level, id, false);
	}

	/**
	 * Refuses when a player stands in (or next to) a box about to get its old terrain back: restoring it would bury
	 * them (death in Hardcore). Every restore path ({@link #remove}, {@link #move}, {@link #undoMove}) checks it,
	 * not only the hub. Server thread.
	 */
	static void refusePlayerIn(ServerLevel level, Anchors.Bounds box, String id, String verb) throws BuildingException {
		for (Occupancy.Found f : Occupancy.scan(level, box, e -> false)) {
			if (f.kind() == Occupancy.Kind.PLAYER) {
				throw new BuildingException("Step out of " + id + " first (" + f.name() + " is in or next to it): " + verb
					+ " puts the old terrain back there; nothing was done");
			}
		}
	}

	/** "Move these first: ..." for a removal (or move) that would destroy them. */
	public static String blockersMessage(String id, List<String> blockers) {
		return "Move these out of " + id + " first (removing it puts the old terrain back over them): "
			+ String.join(", ", blockers.subList(0, Math.min(6, blockers.size()))) + (blockers.size() > 6 ? ", ... (" + blockers.size() + " in all)" : "")
			+ ". Or confirm again with force: they are lost";
	}

	/**
	 * What a removal would destroy that the building did not bring: block entities at positions where the template
	 * has none (a chest, furnace, bed or barrel the player placed), template containers / lecterns the player filled,
	 * dropped items, item frames, paintings, armor stands and other non-living entities in the box. Server thread.
	 */
	public static List<String> removalBlockers(ServerLevel level, Building b) {
		List<String> out = new ArrayList<>();
		java.util.Set<BlockPos> own = ownBlockEntities(b);
		Anchors.Bounds box = b.restoreBox();
		forEachBlockEntity(level, box, be -> {
			BlockPos p = be.getBlockPos();
			String what = be.getBlockState().getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT) + " at " + p.toShortString();
			if (be instanceof StationBlockEntity) {
				return; // AgentCraft stations: the building's own
			}
			if (be instanceof LecternBlockEntity lectern) {
				if (lectern.hasBook() || !own.contains(p)) {
					out.add(what + (lectern.hasBook() ? " (with a book)" : ""));
				}
				return;
			}
			if (be instanceof Container c && !c.isEmpty()) {
				out.add(what + " (" + items(c) + ")");
				return;
			}
			if (!own.contains(p)) {
				out.add(what);
			}
		});
		int dropped = 0;
		for (Entity e : level.getEntities((Entity) null, Occupancy.aabb(box), e -> e.isAlive() && !(e instanceof Player))) {
			Occupancy.Found f = Occupancy.classify(e);
			if (f.kind() == Occupancy.Kind.ITEM && !(e instanceof ItemEntity)) {
				out.add(f.name() + " at " + e.blockPosition().toShortString()); // a trident or an arrow that can be picked up: named
			} else if (f.kind() == Occupancy.Kind.ITEM) {
				dropped++;
			} else if (!f.removable()) {
				out.add(f.name() + " at " + e.blockPosition().toShortString()); // pets, villagers, item frames, armor stands...
			}
		}
		if (dropped > 0) {
			out.add(dropped + " dropped item stack" + (dropped == 1 ? "" : "s"));
		}
		if (!out.isEmpty() && b.pin() == null) {
			out.add("(" + b.id() + " was placed before AgentCraft remembered its blueprint version, so its own chests and barrels are listed too)");
		}
		return out;
	}

	private static String items(Container c) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			n += c.getItem(i).getCount();
		}
		return n + " item" + (n == 1 ? "" : "s");
	}

	/**
	 * World positions of the block entities the building brought: its pin's (the template it was placed from, even when
	 * the blueprint changed since). Empty for a record without a pin: every block entity but the stations then counts
	 * as the player's (the safe side).
	 */
	static java.util.Set<BlockPos> ownBlockEntities(Building b) {
		java.util.Set<BlockPos> out = new java.util.HashSet<>();
		if (b.pin() == null) {
			return out;
		}
		List<Integer> be = b.pin().blockEntities();
		for (int i = 0; i + 2 < be.size(); i += 3) {
			out.add(new BlockPos(b.box().minX() + be.get(i), b.box().minY() + be.get(i + 1), b.box().minZ() + be.get(i + 2)));
		}
		return out;
	}

	/** Drops the record of a building without touching the world (and deletes its snapshot: its blocks stay for good). */
	public static void forget(MinecraftServer server, String id) throws BuildingException {
		State s = state;
		Building b = s.byId().get(id);
		if (b == null) {
			throw new BuildingException("No building " + id);
		}
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		rehome(map, b);
		try {
			Files.deleteIfExists(snapshotFile(server, id));
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not delete the snapshot of {}", id, e);
		}
		reports.remove(id);
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), s.pending()));
	}

	/** After {@code gone} left {@code map}: when it was home, the first remaining building becomes home. */
	private static void rehome(Map<String, Building> map, Building gone) {
		if (gone.home() && !map.isEmpty()) {
			var first = map.entrySet().iterator().next();
			first.setValue(first.getValue().withHome(true));
		}
	}

	/** Makes {@code id} the home building. Server thread. */
	public static Building setHome(MinecraftServer server, String id) throws BuildingException {
		State s = state;
		if (!s.byId().containsKey(id)) {
			throw new BuildingException("No building " + id);
		}
		Map<String, Building> map = new LinkedHashMap<>();
		s.byId().forEach((k, v) -> map.put(k, v.withHome(k.equals(id))));
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), s.pending()));
		return map.get(id);
	}

	/**
	 * Gives a building other repos without re-placing it (docs/BUILDINGS.md "Change a building's repos"): wing n
	 * becomes {@code repos[n-1]}. Task walls and CI lamps bound to a wing's old repo (or its unfilled {@code #n}
	 * placeholder) are rebound, a wing that loses its repo goes back to {@code #n}, and the per-wing anchors are
	 * derived again from the building's pin: the anchors and wing count of the template it was placed from, never the
	 * blueprint's current version (docs/BUILDINGS.md "Blueprint versions"). A record without a pin keeps its anchor
	 * positions and only renames them, and takes at most as many repos as it has now (Move re-places it from the
	 * current blueprint). Refuses more repos than wings and a repo that has another building. The lead sync (the hub's
	 * listener) then sends {@code lead.assign}. Server thread.
	 */
	public static Building setRepos(MinecraftServer server, String id, List<String> repos) throws BuildingException {
		Building b = get(id);
		if (b == null) {
			throw new BuildingException("No building " + id);
		}
		List<String> rs = List.copyOf(repos);
		Building.Pin pin = b.pin();
		if (pin == null && rs.size() > b.repos().size()) {
			throw new BuildingException(id + " was placed before AgentCraft remembered its blueprint version, so its empty wings are unknown: it "
				+ "takes at most " + b.repos().size() + " repo(s) here. Move it (re-placed from the current " + b.blueprint() + ") to fill more wings");
		}
		checkRepos(b.blueprint(), pin != null ? pin.wings() : b.repos().size(), rs, id);
		if (rs.equals(b.repos())) {
			return b;
		}
		ServerLevel level = levelOf(server, b);
		if (level == null) {
			throw new BuildingException(id + " is in " + b.dimensionOrDefault() + ", which is not loaded; nothing was changed");
		}
		int[] n = {0};
		forEachBlockEntity(level, b.box(), be -> {
			if (be instanceof StationBlockEntity station) {
				String to = BlueprintTransform.rebindBinding(station.binding(), b.repos(), rs);
				if (to != null) {
					station.setBinding(to);
					n[0]++;
				}
			}
		});
		Map<String, Anchor> anchors = pin != null ? BlueprintTransform.renameWings(pin.wingAnchors(), pin.group(), rs)
			: BlueprintTransform.rebindAnchors(b.anchors(), b.repos(), rs);
		Building nb = b.withRepos(rs, anchors, Math.max(System.currentTimeMillis(), b.revision() + 1));
		State s = state;
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		map.put(id, nb);
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), s.pending()));
		AgentCraft.LOGGER.info("Building {} repos {} -> {}; {} bindings rebound", id, b.repos(), rs, n[0]);
		return nb;
	}

	/**
	 * Moves a building (docs/BUILDINGS.md "Move a building"): the same id, repos, lead and home flag at a new site
	 * in {@code level}. The new site gets every check of {@link #place} (it may not overlap the old one); the old
	 * site must be clear of the player's things ({@link #removalBlockers}) unless {@code force}. Places at the new
	 * site, then restores the old site from its snapshot (kept until the next world start, as for a removal) and records
	 * the old site in {@link Building#movedFrom()} (the hub's "Undo move" moves it back). Server thread.
	 */
	public static Building move(ServerLevel level, String id, BlockPos origin, Rotation rotation, boolean force) throws BuildingException {
		MinecraftServer server = level.getServer();
		Building b = get(id);
		if (b == null) {
			throw new BuildingException("No building " + id);
		}
		Blueprint bp = Blueprints.get(b.blueprint());
		if (bp == null) {
			throw new BuildingException("Blueprint " + b.blueprint() + " is not loaded; nothing was moved");
		}
		ServerLevel oldLevel = levelOf(server, b);
		Path oldSnap = snapshotFile(server, id);
		if (oldLevel == null || !Files.exists(oldSnap)) {
			throw new BuildingException(oldLevel == null ? b.dimensionOrDefault() + " is not loaded; nothing was moved"
				: "Snapshot " + oldSnap.getFileName() + " is missing, so " + id + "'s old site cannot be restored; nothing was moved");
		}
		if (loadFailed) {
			throw new BuildingException(FILE + " could not be read when the world started; nothing was moved");
		}
		// the new site is placed from the blueprint as it is now, which may have fewer wings than when it was placed
		checkRepos(bp.id(), bp.wings(), b.repos(), id);
		refusePlayerIn(oldLevel, b.restoreBox(), id, "moving it");
		if (!force) {
			List<String> blockers = removalBlockers(oldLevel, b);
			if (!blockers.isEmpty()) {
				throw new BuildingException(blockersMessage(id, blockers).replace("removing it", "moving it"));
			}
		}
		Path newSnap = oldSnap.resolveSibling(id + ".move.nbt");
		Built built = build(level, bp, origin, rotation, b.repos(), force, b, newSnap);
		// snapshots first, then the old site: a failure at any step undoes the steps before it and commits nothing, so the
		// record never points at a site whose snapshot is another site's terrain
		String movedName = id + ".moved-" + System.currentTimeMillis() + ".nbt";
		Path moved = oldSnap.resolveSibling(movedName);
		int step = 0;
		try {
			renameSnapshot(oldSnap, moved);
			step = 1;
			renameSnapshot(newSnap, oldSnap);
			step = 2;
			Drops drops = Drops.before(oldLevel, b.restoreBox());
			restore(oldLevel, b.restoreBox(), moved);
			drops.clearNew(oldLevel);
		} catch (IOException | BuildingException | RuntimeException e) {
			AgentCraft.LOGGER.error("Moving {} failed at step {}; taking the new site down again", id, step, e);
			undoMoveSteps(level, built.snapshotBox(), oldSnap, newSnap, moved, step);
			throw new BuildingException("Moving " + id + " failed (" + e.getMessage() + "); the new site was restored, " + id + " stays where it was");
		}
		long now = System.currentTimeMillis();
		Building nb = new Building(id, b.blueprint(), b.repos(), b.home(), BlueprintTransform.rotationName(built.turns()), built.box(),
			built.bounds(), built.anchors(), b.placedAt(), dimensionId(level), built.snapshotBox(), Math.max(now, b.revision() + 1), b.site(),
			built.pin());
		State s = state;
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		map.put(id, nb);
		List<Building.Pending> pending = new ArrayList<>(s.pending());
		pending.add(new Building.Pending(b, movedName, now, "moved"));
		reports.remove(id);
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(pending)));
		lastNote = built.note();
		AgentCraft.LOGGER.info("Moved building {} from {} ({}) to {} ({}){}", id, str(b.box()), b.dimensionOrDefault(), str(nb.box()),
			nb.dimensionOrDefault(), built.note() == null ? "" : "; " + built.note());
		return nb;
	}

	/**
	 * Rolls back a move that failed after its new site was built: {@code step} 1 = the old snapshot was renamed to
	 * {@code moved}, 2 = the new snapshot to {@code oldSnap} as well. Restores the new site from its snapshot and puts
	 * the files back. Logged, never thrown: the caller is already failing.
	 */
	private static void undoMoveSteps(ServerLevel level, Anchors.Bounds newBox, Path oldSnap, Path newSnap, Path moved, int step) {
		try {
			if (step >= 2) {
				Files.move(oldSnap, newSnap, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			if (step >= 1) {
				Files.move(moved, oldSnap, StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (IOException io) {
			AgentCraft.LOGGER.error("Moving: could not put the snapshots back ({} / {} / {}); check {} by hand", oldSnap, newSnap, moved,
				oldSnap.getParent(), io);
		}
		try {
			restore(level, newBox, newSnap);
			Files.deleteIfExists(newSnap);
		} catch (BuildingException | IOException | RuntimeException e2) {
			AgentCraft.LOGGER.error("Moving: could not take the new site {} down again", str(newBox), e2);
		}
	}

	/** Test hook (DevBridge {@code dev.buildings.failNextRename}): the next snapshot rename of a move throws. */
	static volatile boolean failNextRename;

	/** Arms {@link #failNextRename} (DevBridge only). */
	public static void failNextSnapshotRename() {
		failNextRename = true;
	}

	private static void renameSnapshot(Path from, Path to) throws IOException {
		if (failNextRename) {
			failNextRename = false;
			throw new IOException("injected rename failure (" + from.getFileName() + " -> " + to.getFileName() + ")");
		}
		Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Moves a building back to where it stood before its last move ({@link Building#movedFrom()}): the hub's "Undo move". */
	public static Building undoMove(MinecraftServer server, String id, boolean force) throws BuildingException {
		Building b = get(id);
		if (b == null) {
			throw new BuildingException("No building " + id);
		}
		Building.Site from = b.movedFrom();
		if (from == null) {
			throw new BuildingException(id + " was never moved");
		}
		Identifier key = Identifier.tryParse(from.dimension());
		ServerLevel level = key == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, key));
		if (level == null) {
			throw new BuildingException(from.dimension() + " is not loaded; nothing was moved");
		}
		int turns = Math.max(0, BlueprintTransform.ROTATIONS.indexOf(from.rotation()));
		Building back = move(level, id, new BlockPos(from.x(), from.y(), from.z()), Rotation.values()[turns], force);
		return back;
	}

	/** The server level a building stands in, or null when that dimension is not loaded. */
	public static @Nullable ServerLevel levelOf(MinecraftServer server, Building b) {
		Identifier key = Identifier.tryParse(b.dimensionOrDefault());
		return key == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, key));
	}

	// ------------------------------------------------------------------ crash safety

	/** A check of the records against the world (see {@link #reconcile}); {@code problem} false = a notice. */
	public record Report(String buildingId, boolean problem, String message) {
	}

	private static final Map<String, Report> reports = new java.util.concurrent.ConcurrentHashMap<>();

	/** What the last world load found about the buildings (hub: a "Check" line per building). Any thread. */
	public static Map<String, Report> reports() {
		return Map.copyOf(reports);
	}

	/** Adds a report to a building's line (a second one is appended; a problem stays a problem). */
	private static void report(String id, boolean problem, String message) {
		reports.merge(id, new Report(id, problem, message), (a, b) -> new Report(id, a.problem() || b.problem(), a.message() + " " + b.message()));
	}

	/** The sites taken down (removed, moved away) whose snapshots are kept until the next world start settles them. Any thread. */
	public static List<Building.Pending> pending() {
		return state.pending();
	}

	/**
	 * How much of a building's template stands in the world, by {@code grid} (the building's own, {@link #ownGrid}):
	 * {matching non-air blocks, non-air template blocks}; {0, 0} when it cannot be checked.
	 */
	static int[] standing(MinecraftServer server, Building b, @Nullable TemplateGrid grid) {
		ServerLevel level = levelOf(server, b);
		if (grid == null || level == null) {
			return new int[] {0, 0};
		}
		int turns = Math.max(0, BlueprintTransform.ROTATIONS.indexOf(b.rotation()));
		GhostModel m = grid.ghost(turns);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int total = 0;
		int match = 0;
		for (int i = 0; i < m.count(); i++) {
			BlockState want = grid.states()[i];
			if (want.isAir()) {
				continue;
			}
			total++;
			// by block, not state: the driver flips lamps, podiums, monitors and bulbs, players open doors
			if (level.getBlockState(p.set(b.box().minX() + m.x(i), b.box().minY() + m.y(i), b.box().minZ() + m.z(i))).is(want.getBlock())) {
				match++;
			}
		}
		return new int[] {match, total};
	}

	/** Whether a building stands by its own template; null when that cannot be checked (template changed or missing, dimension not loaded). */
	private static @Nullable Boolean stands(MinecraftServer server, Building b) {
		int[] st = standing(server, b, b.pin() == null ? TemplateGrid.of(b.blueprint()) : ownGrid(b));
		return st[1] == 0 ? null : Reconcile.stands(st[0], st[1]);
	}

	/**
	 * Whether a taken-down site shows its snapshot again (docs/BUILDINGS.md "Crash safety"): over the cells where the
	 * snapshot and the building differ (the building's own template when its pin matches, else its pinned block
	 * entities), the share that hold the snapshot's block ({@link Reconcile#restored}). Null when it cannot be told.
	 */
	static @Nullable Boolean restored(MinecraftServer server, Building b, Path snap) {
		ServerLevel level = levelOf(server, b);
		if (level == null || !Files.exists(snap)) {
			return null;
		}
		StructureTemplate before = new StructureTemplate();
		try {
			before.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), NbtIo.readCompressed(snap, NbtAccounter.unlimitedHeap()));
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Buildings check: could not read {}", snap, e);
			return null;
		}
		TemplateGrid saved = TemplateGrid.read(null, before);
		Anchors.Bounds rb = b.restoreBox();
		Map<Long, BlockState> terrain = new java.util.HashMap<>();
		for (int i = 0; i < saved.count(); i++) {
			terrain.put(BlockPos.asLong(rb.minX() + saved.xyz()[i * 3], rb.minY() + saved.xyz()[i * 3 + 1], rb.minZ() + saved.xyz()[i * 3 + 2]),
				saved.states()[i]);
		}
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int total = 0;
		int match = 0;
		TemplateGrid grid = b.pin() == null ? TemplateGrid.of(b.blueprint()) : ownGrid(b);
		if (grid != null) {
			GhostModel m = grid.ghost(Math.max(0, BlueprintTransform.ROTATIONS.indexOf(b.rotation())));
			for (int i = 0; i < m.count(); i++) {
				p.set(b.box().minX() + m.x(i), b.box().minY() + m.y(i), b.box().minZ() + m.z(i));
				BlockState was = terrain.get(p.asLong());
				if (was == null || was.is(grid.states()[i].getBlock())) {
					continue; // the building's block equals the terrain's here: says nothing
				}
				total++;
				if (level.getBlockState(p).is(was.getBlock())) {
					match++;
				}
			}
		} else if (b.pin() != null) {
			List<Integer> be = b.pin().blockEntities();
			for (int i = 0; i + 2 < be.size(); i += 3) {
				p.set(b.box().minX() + be.get(i), b.box().minY() + be.get(i + 1), b.box().minZ() + be.get(i + 2));
				BlockState was = terrain.get(p.asLong());
				if (was == null || was.hasBlockEntity()) {
					continue;
				}
				total++;
				if (level.getBlockState(p).is(was.getBlock())) {
					match++;
				}
			}
		}
		return Reconcile.restored(match, total);
	}

	/**
	 * At world start, checks the records against the world and settles the sites taken down before the game stopped
	 * (docs/BUILDINGS.md "Crash safety"). Snapshots of removed / moved-away sites are only deleted here, on positive
	 * evidence ({@link Reconcile#decide}): the site shows its snapshot again, or a standing building covers it. A
	 * removal or move that never reached the disk gets its record back; two copies of a building, or a taken-down
	 * building standing under another one, are reported and keep their snapshot. A building whose own template no
	 * longer stands is reported; one placed from an older version of its blueprint is only noted (it cannot be
	 * checked); an old record without a pin gets one when the current blueprint matches it. Never deletes a record or
	 * changes the world. Server thread.
	 */
	static void reconcile(MinecraftServer server) {
		reports.clear();
		State s = state;
		if (s.byId().isEmpty() && s.pending().isEmpty() || loadFailed) {
			return;
		}
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		List<Building.Pending> pending = new ArrayList<>(s.pending());
		boolean changed = false;
		Map<String, Boolean> standsNow = new java.util.HashMap<>();
		for (Building b : List.copyOf(map.values())) {
			if (b.pin() == null) {
				Building pinned = adoptPin(server, b);
				if (pinned != null) {
					map.put(b.id(), pinned);
					changed = true;
					standsNow.put(b.id(), true);
				} else if (TemplateGrid.of(b.blueprint()) != null && levelOf(server, b) != null) {
					report(b.id(), false, b.id() + " was placed before AgentCraft remembered blueprint versions and does not match the current "
						+ b.blueprint() + ", so it was not checked (normal after a blueprint update; it keeps its own layout)");
				}
				continue;
			}
			if (ownGrid(b) == null) {
				if (TemplateGrid.of(b.blueprint()) != null) {
					report(b.id(), false, b.blueprint() + " changed since " + b.id() + " was placed; " + b.id()
						+ " keeps the layout it was placed with and was not checked");
				}
				continue;
			}
			Boolean st = stands(server, b);
			if (st != null) {
				standsNow.put(b.id(), st);
			}
		}
		Path snaps = server.getWorldPath(LevelResource.ROOT).resolve(SNAPSHOT_DIR);
		java.util.Set<String> recovered = new java.util.HashSet<>();
		for (Building.Pending p : List.copyOf(pending)) {
			Building gone = p.building();
			boolean moved = "moved".equals(p.why());
			Path snap = snaps.resolve(p.snapshot());
			if (!Files.exists(snap)) {
				AgentCraft.LOGGER.warn("Buildings check: the snapshot {} of {}'s {} site is missing; dropping the entry", p.snapshot(), gone.id(), p.why());
				pending.remove(p);
				changed = true;
				continue;
			}
			Reconcile.Overlap overlap = Reconcile.Overlap.NONE;
			String over = null;
			for (Building o : map.values()) {
				if (o.id().equals(gone.id()) || !o.dimensionOrDefault().equals(gone.dimensionOrDefault())
					|| !Building.intersects(o.restoreBox(), gone.restoreBox())) {
					continue;
				}
				over = o.id();
				if (Boolean.TRUE.equals(standsNow.get(o.id())) && contains(o.restoreBox(), gone.restoreBox())) {
					overlap = Reconcile.Overlap.COVERED;
					break;
				}
				overlap = Reconcile.Overlap.PARTIAL;
			}
			Building current = map.get(gone.id());
			Boolean stands = stands(server, gone);
			Boolean restored = restored(server, gone, snap);
			Reconcile.Action action = Reconcile.decide(moved, stands, restored, overlap, current != null,
				current == null ? null : standsNow.get(gone.id()));
			AgentCraft.LOGGER.info("Buildings check: {} site of {} at {} (stands {}, restored {}, overlap {}): {}", p.why(), gone.id(),
				str(gone.restoreBox()), stands, restored, overlap, action);
			switch (action) {
				case RELEASE -> {
					try {
						Files.deleteIfExists(snap);
					} catch (IOException e) {
						AgentCraft.LOGGER.warn("Could not delete the snapshot {}", snap, e);
						continue;
					}
					pending.remove(p);
					changed = true;
				}
				case RECOVER -> {
					if (moved) {
						// the move never reached the disk: the building is still at its old site, with that site's snapshot
						try {
							Files.move(snaps.resolve(gone.id() + ".before.nbt"), snaps.resolve(gone.id() + ".unused-" + System.currentTimeMillis() + ".nbt"),
								StandardCopyOption.ATOMIC_MOVE);
							Files.move(snap, snaps.resolve(gone.id() + ".before.nbt"), StandardCopyOption.ATOMIC_MOVE);
						} catch (IOException e) {
							AgentCraft.LOGGER.error("Buildings check {}: could not swap the snapshots back", gone.id(), e);
							report(gone.id(), true, gone.id() + "'s move was not saved before the game stopped and it could not be put back; its old "
								+ "site's saved terrain is kept as " + p.snapshot());
							continue;
						}
						map.put(gone.id(), gone.withHome(current.home()));
						report(gone.id(), false, gone.id() + "'s move was not saved before the game stopped: it is back at its old site");
					} else {
						if (!p.snapshot().equals(gone.id() + ".before.nbt")) {
							continue;
						}
						map.put(gone.id(), gone.withHome(map.isEmpty()));
						report(gone.id(), false, gone.id() + "'s removal was not saved before the game stopped: it stands again (remove it again)");
					}
					recovered.add(gone.id());
					standsNow.put(gone.id(), true);
					pending.remove(p);
					changed = true;
				}
				case REPORT_KEEP -> {
					String on = current != null ? gone.id() : over != null ? over : gone.id();
					String what = over != null && current == null
						? "a copy of " + gone.id() + " (" + p.why() + " before the game stopped, not saved) still stands partly under " + over
						: moved ? gone.id() + " stands at its old site " + str(gone.box()) + " too: the move was only partly saved before the game stopped"
						: "removed " + gone.id() + " stands again but could not get its record back";
					report(on, true, what + ". Its saved terrain is kept as " + SNAPSHOT_DIR + "/" + p.snapshot() + "; nothing was changed");
				}
				case KEEP -> {
				}
			}
		}
		// buildings whose own template no longer stands (and were not just put back)
		for (Building b : map.values()) {
			Boolean st = standsNow.get(b.id());
			if (Boolean.FALSE.equals(st) && !recovered.contains(b.id())) {
				int[] n = standing(server, b, ownGrid(b));
				report(b.id(), true, Reconcile.mismatch(b.id(), n[0], n[1]));
			}
		}
		if (changed) {
			state = new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(pending));
			save(server, state);
		}
		reports.values().forEach(r -> AgentCraft.LOGGER.warn("Buildings check: {}", r.message()));
	}

	/** An old record (no pin) gets one when the current blueprint stands there and gives the same anchors; else null. */
	private static @Nullable Building adoptPin(MinecraftServer server, Building b) {
		Blueprint bp = Blueprints.get(b.blueprint());
		TemplateGrid grid = TemplateGrid.of(b.blueprint());
		if (bp == null || grid == null) {
			return null;
		}
		int[] st = standing(server, b, grid);
		int turns = Math.max(0, BlueprintTransform.ROTATIONS.indexOf(b.rotation()));
		if (!Reconcile.stands(st[0], st[1])
			|| !BlueprintTransform.worldAnchors(bp, turns, b.box().minX(), b.box().minY(), b.box().minZ(), b.repos()).equals(b.anchors())) {
			return null;
		}
		AgentCraft.LOGGER.info("Buildings check: {} matches the current {}; pinned it", b.id(), b.blueprint());
		return b.withPin(pinFor(bp, grid, turns, b.box()));
	}

	private static boolean contains(Anchors.Bounds outer, Anchors.Bounds inner) {
		return outer.minX() <= inner.minX() && outer.minY() <= inner.minY() && outer.minZ() <= inner.minZ() && outer.maxX() >= inner.maxX()
			&& outer.maxY() >= inner.maxY() && outer.maxZ() >= inner.maxZ();
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

	/** The level's dimension id as recorded in {@link Building#dimension()} ({@code minecraft:overworld}, ...). */
	public static String dimensionId(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	/**
	 * AgentCraft station block entities in {@code box} of {@code level}: a building placed in that level has
	 * them, the same box in another dimension almost never does. Buildings record their dimension since the
	 * hub's design wave; for older records (no dimension) the hub uses this before removing (which pastes
	 * the snapshot into the given level) or teleporting. Loads the box's chunks. Server thread.
	 */
	public static int stationCount(ServerLevel level, Anchors.Bounds box) {
		int[] n = {0};
		forEachBlockEntity(level, box, be -> {
			if (be instanceof StationBlockEntity) {
				n[0]++;
			}
		});
		return n[0];
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

	/**
	 * The dropped items and XP around a box before the mod changes it, so only the ones the change makes are
	 * removed afterwards (never the player's own drops lying there). The flags suppress drops already; this
	 * catches what pops anyway, once right after and once a few ticks later (blocks that lose support on the
	 * next tick).
	 */
	static final class Drops {
		private final AABB area;
		private final java.util.Set<java.util.UUID> before = new java.util.HashSet<>();

		private Drops(AABB area) {
			this.area = area;
		}

		static Drops before(ServerLevel level, Anchors.Bounds box) {
			Drops d = new Drops(Occupancy.aabb(box).inflate(1));
			level.getEntitiesOfClass(ItemEntity.class, d.area).forEach(e -> d.before.add(e.getUUID()));
			level.getEntitiesOfClass(ExperienceOrb.class, d.area).forEach(e -> d.before.add(e.getUUID()));
			return d;
		}

		/** Removes the drops that were not there before, now and again a few ticks later. */
		void clearNew(ServerLevel level) {
			clear(level);
			LATER.add(new Object[] {this, level, 3});
		}

		private int clear(ServerLevel level) {
			int n = 0;
			for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, area)) {
				if (!before.contains(e.getUUID())) {
					e.discard();
					n++;
				}
			}
			for (ExperienceOrb e : level.getEntitiesOfClass(ExperienceOrb.class, area)) {
				if (!before.contains(e.getUUID())) {
					e.discard();
					n++;
				}
			}
			return n;
		}

		private static final List<Object[]> LATER = new ArrayList<>();

		/** Server tick: the later passes. */
		static void tick() {
			for (var it = LATER.iterator(); it.hasNext();) {
				Object[] x = it.next();
				int left = (Integer) x[2] - 1;
				if (left > 0) {
					x[2] = left;
					continue;
				}
				it.remove();
				((Drops) x[0]).clear((ServerLevel) x[1]);
			}
		}

		static void reset() {
			LATER.clear();
		}
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
			Files.writeString(tmp, GSON.toJson(Building.fileJson(List.copyOf(s.byId().values()), s.next(), s.pending())), StandardCharsets.UTF_8);
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
			state = new State(Collections.unmodifiableMap(map), data.next(), data.pending());
			AgentCraft.LOGGER.info("Loaded {} building(s) {}{}", map.size(), map.keySet(), data.pending().isEmpty() ? ""
				: "; " + data.pending().size() + " site(s) taken down before the last save");
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("Could not read {}; no buildings loaded (the file is left as is)", f, e);
			state = State.EMPTY;
			loadFailed = true;
		}
	}

	public static String str(Anchors.Bounds b) {
		return b.minX() + "," + b.minY() + "," + b.minZ() + " .. " + b.maxX() + "," + b.maxY() + "," + b.maxZ();
	}
}
