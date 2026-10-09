package dev.agentcraft.building;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.journal.Journal;
import dev.agentcraft.journal.JournalStore;
import dev.agentcraft.journal.WorldJournal;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqWorld;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
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
	/** The folder the buildings' snapshots were kept in before the world journal; imported names still resolve against it. */
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
		PlaceTiming.init();
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

	/**
	 * Every placed site in placement order: buildings and fixtures (village boards). This is the safe default for
	 * anything spatial: overlap and collision (the ghost, placement, roads: a road laid through a fixture's restore box
	 * would be overwritten by the fixture's snapshot on remove and vice versa), commands that act on any site. For
	 * routing, leads, trophies and the hub's building list use {@link #buildings()} (docs/VILLAGE.md V2).
	 */
	public static List<Building> all() {
		return List.copyOf(state.byId().values());
	}

	/**
	 * The buildings only, without the fixtures (docs/VILLAGE.md V2: a village board is no building for routing, leads,
	 * trophies, the Inbox, Goals, the HUD or the hub's building list). Any thread.
	 */
	public static List<Building> buildings() {
		return withoutFixtures(state.byId().values());
	}

	/** The placed fixtures (village boards) in placement order. Any thread. */
	public static List<Building> fixtures() {
		List<Building> out = new ArrayList<>();
		for (Building b : state.byId().values()) {
			if (b.isFixture()) {
				out.add(b);
			}
		}
		return List.copyOf(out);
	}

	static List<Building> withoutFixtures(Collection<Building> sites) {
		List<Building> out = new ArrayList<>();
		for (Building b : sites) {
			if (!b.isFixture()) {
				out.add(b);
			}
		}
		return List.copyOf(out);
	}

	/** Tests only: replace the loaded sites (no world, no file). */
	static void setForTest(List<Building> sites) {
		Map<String, Building> map = new LinkedHashMap<>();
		sites.forEach(b -> map.put(b.id(), b));
		state = new State(Collections.unmodifiableMap(map), sites.size() + 1, List.of());
	}

	/** A building or a fixture by id (remove, move and the hub act on both), or null. */
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
				if (!b.isFixture()) {
					sites.add(Routing.Site.of(b)); // a fixture is no routing site (docs/VILLAGE.md V2)
				}
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
		long t0 = System.nanoTime();
		MinecraftServer server = level.getServer();
		if (bp.isFixture()) {
			if (!repos.isEmpty()) {
				throw new BuildingException(bp.name() + " is a fixture: it takes no repos");
			}
		} else {
			checkRepos(bp.id(), bp.wings(), repos, null);
		}
		if (loadFailed) {
			throw new BuildingException(FILE + " could not be read when the world started (see the log); fix or move it, then restart");
		}
		requireJournal();
		State s = state;
		int next = s.next();
		while (WorldJournal.ownerUsed("b" + next)) {
			next++; // never reuse an id the journal (or an imported snapshot) knows
		}
		String id = "b" + next;
		Built built = build(level, bp, origin, rotation, repos, force, null, id);
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		long now = System.currentTimeMillis();
		Building b = new Building(id, bp.id(), repos, !bp.isFixture() && noBuilding(map), BlueprintTransform.rotationName(built.turns()), built.box(), built.bounds(),
			built.anchors(), now, dimensionId(level), built.snapshotBox(), now, null, built.pin());
		// the journal first (the world change's record, with its held leaves), then the buildings file
		try {
			WorldJournal.commit(built.entries(b), List.of());
		} catch (IOException e) {
			AgentCraft.LOGGER.error("Placing {}: the journal could not be saved; restoring box {}", id, str(built.snapshotBox()), e);
			unbuild(level, built);
			throw new BuildingException("Placing " + bp.id() + " failed: the world journal could not be saved (" + e.getMessage() + "); the area was restored");
		}
		map.put(id, b);
		commit(server, new State(Collections.unmodifiableMap(map), next + 1, s.pending()));
		lastNote = built.note();
		AgentCraft.LOGGER.info("Placed {} {} ({}) for {} at {} rotation {}: box {}, journal {} over {}, {} anchors{}{}", bp.isFixture() ? "fixture" : "building", id,
			bp.id(), repos, origin.toShortString(), b.rotation(), str(b.box()), built.entry().id(), str(b.restoreBox()), b.anchors().size(), force ? " (forced)" : "",
			built.note() == null ? "" : "; " + built.note());
		PlaceTiming.placed(System.nanoTime() - t0);
		return b;
	}

	/** True when {@code map} holds no building (fixtures do not count): the next building placed becomes home. */
	static boolean noBuilding(Map<String, Building> map) {
		return map.values().stream().allMatch(Building::isFixture);
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

	/**
	 * A template put into the world (not recorded yet): {@code entry} its journal entry (drafted, with every cell's after;
	 * not committed), {@code leaves} the leaves outside it the placement holds ({@link LeafGuard}; not committed, null when
	 * none), {@code ring} the leaves around it as they were ({@link LeafGuard#ring}; not committed, null when none; it
	 * changed nothing, so it needs no draft), {@code plants} the hanging plants around it as they were and as the placement
	 * left them ({@link PlantGuard}; not committed, null when none), {@code before} the site as captured before (to put back
	 * when the change is rolled back).
	 */
	private record Built(int turns, Anchors.Bounds box, Anchors.Bounds snapshotBox, Anchors.Bounds bounds, Map<String, Anchor> anchors,
		Building.Pin pin, @Nullable String note, Journal.Entry entry, Journal.@Nullable Entry leaves, Journal.@Nullable Entry ring,
		Journal.@Nullable Entry plants, CompoundTag before) {
		/** The entries a commit records: the site's (with {@code record} as its meta), its held leaves', leaf ring's and plants'. */
		Map<String, Journal.Entry> entries(Building record) {
			Map<String, Journal.Entry> m = new LinkedHashMap<>();
			m.put(entry.id(), entry.withMeta(record.toJson()));
			if (leaves != null) {
				m.put(leaves.id(), leaves);
			}
			if (ring != null) {
				m.put(ring.id(), ring);
			}
			if (plants != null) {
				m.put(plants.id(), plants);
			}
			return m;
		}
	}

	/**
	 * The kinds of the entries undone, released and settled with a building's site (its group): trophies, held leaves (and
	 * cut plants' guard cells), the leaf ring, the hanging plants.
	 */
	static final String[] SITE_KINDS = {"trophy", LeafGuard.KIND, LeafGuard.RING_KIND, PlantGuard.KIND};

	/** Takes a built but uncommitted site down again (the old snapshot restore, its held leaves let go) and drops its draft. */
	private static void unbuild(ServerLevel level, Built built) {
		try {
			Drops drops = Drops.before(level, built.snapshotBox());
			WorldJournal.restoreTemplate(level, built.snapshotBox(), built.before(), FLAGS);
			putBackCut(level, built);
			if (built.leaves() != null) {
				LeafGuard.release(level, built.leaves().cells(), FLAGS);
			}
			if (built.plants() != null) {
				PlantGuard.putBack(level, built.plants().cells(), FLAGS);
			}
			drops.clearNew(level);
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("Could not take the site {} down again (its saved terrain is the journal draft {})", str(built.snapshotBox()),
				built.entry().id(), e);
			return;
		}
		WorldJournal.discardDraft(built.entry().id());
	}

	/** Refuses world changes while the world journal cannot be read. */
	private static void requireJournal() throws BuildingException {
		String why = WorldJournal.unavailable();
		if (why != null) {
			throw new BuildingException(why);
		}
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

	/** Where {@link #checkSite} reports a refusal: {@link #place} throws the first, a {@link #verdict} collects them all. */
	@FunctionalInterface
	private interface Refusals {
		void add(String message) throws BuildingException;
	}

	/** Throws the first refusal (place, move): the player sees what {@link #place} always said. */
	private static final Refusals THROW = m -> {
		throw new BuildingException(m);
	};

	/**
	 * A site that passed {@link #checkSite} (or, for a verdict, was planned as far as it could be): everything the apply
	 * phase of {@link #build} needs, so it never looks at the world twice.
	 */
	private record SitePlan(StructureTemplate template, int turns, StructurePlaceSettings settings, BlockPos placePos, Anchors.Bounds box,
		TemplateGrid grid, TerrainFit.Plan plan, Approach.Plan approach, Anchors.Bounds snapBox, List<Occupancy.Found> found, SiteWarnings.Result site) {
	}

	/**
	 * The checks of a placement (place, move and the dry-run {@link #verdict}, contract S4), in place()'s order: the box
	 * leaves the build height, overlaps another site, lava in or next to it, block entities the mod did not place (unless
	 * {@code force}), a door cut by its edge, who is in the way ({@link Occupancy}). Each failing check goes to
	 * {@code out} with the words place() throws. {@code dryRun}: the world is only read where its chunks are loaded (a
	 * verdict never loads or generates a chunk; an unloaded site is a refusal of its own). Null when the site could not be
	 * planned at all (the reason is in {@code out}). {@code moving}: the building being moved. Server thread.
	 */
	private static @Nullable SitePlan checkSite(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rotation, boolean force,
		@Nullable Building moving, Refusals out, boolean dryRun) throws BuildingException {
		Blueprints.Entry entry = Blueprints.entry(bp.id());
		if (entry == null) {
			out.add("Blueprint " + bp.id() + " has no loaded template");
			return null;
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
			out.add("Internal: rotated box " + bb + " does not start at " + origin.toShortString());
			return null;
		}
		Anchors.Bounds box = new Anchors.Bounds(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
		TemplateGrid grid = TemplateGrid.of(entry);
		GhostModel model = grid.ghost(turns);
		boolean[] unloaded = {false};
		TerrainFit.World world = dryRun ? (x, y, z) -> {
			if (!level.hasChunk(x >> 4, z >> 4)) {
				unloaded[0] = true;
				return 0; // solid: nothing is filled, cleared or found there
			}
			return TerrainFit.flags(level, new BlockPos(x, y, z));
		} : (x, y, z) -> TerrainFit.flags(level, new BlockPos(x, y, z));
		TerrainFit.Plan plan = TerrainFit.plan(model, box.minX(), box.minY(), box.minZ(), world);
		Approach.Plan approach = Approach.forBlueprint(bp, turns, box, world);
		SiteWarnings.Result site = SiteWarnings.forBlueprint(bp, turns, box, approach, world);
		Anchors.Bounds snapBox = snapshotBox(box, plan, approach, level.getMinY());
		if (dryRun && (unloaded[0] || !loaded(level, snapBox))) {
			out.add("the site is not loaded on the server (walk closer)");
			return null;
		}
		if (snapBox.minY() < level.getMinY() || box.maxY() > level.getMaxY()) {
			out.add("Box " + str(snapBox) + " leaves the build height (" + level.getMinY() + ".." + level.getMaxY() + ")");
		}
		String here = dimensionId(level);
		for (Building other : state.byId().values()) {
			if (other.dimensionOrDefault().equals(here) && Building.intersects(other.restoreBox(), snapBox)) {
				out.add("Box " + str(snapBox) + " overlaps " + (moving != null && other.id().equals(moving.id())
					? "where " + other.id() + " stands now (move it further)" : "building " + other.id() + " " + str(other.box())
					+ " (remove it first or place elsewhere)"));
				break;
			}
		}
		String lava = TerrainFit.lavaRefusal(plan);
		if (lava == null) {
			lava = Approach.lavaRefusal(approach);
		}
		if (lava != null) {
			out.add("Not here: " + lava + "; a building next to lava burns and floods");
		}
		if (!force) {
			List<String> foreign = foreignBlockEntities(level, snapBox);
			if (!foreign.isEmpty()) {
				out.add("Box " + str(snapBox) + " contains " + foreign.size() + " block entit" + (foreign.size() == 1 ? "y" : "ies")
					+ " the mod did not place (" + String.join(", ", foreign.subList(0, Math.min(4, foreign.size()))) + (foreign.size() > 4 ? ", ..." : "")
					+ "); add force to overwrite them (they come back on remove)");
			}
		}
		List<String> doors = straddling(level, snapBox, true);
		if (!doors.isEmpty()) {
			out.add("Not placed: a door is cut in half by the box edge (" + String.join(", ", doors.subList(0, Math.min(3, doors.size())))
				+ "); raise, lower or move the building so the door is fully in or out");
		}
		List<Occupancy.Found> found = Occupancy.scan(level, snapBox, e -> false);
		List<String> occupied = Occupancy.refusals(found);
		if (!occupied.isEmpty()) {
			out.add("Not placed: " + String.join("; ", occupied));
		}
		return new SitePlan(template, turns, settings, placePos, box, grid, plan, approach, snapBox, found, site);
	}

	/** Whether every chunk {@code box} touches is loaded in {@code level} (nothing is loaded by asking). */
	private static boolean loaded(ServerLevel level, Anchors.Bounds box) {
		for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
			for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * The server's verdict on a site (contract S4): every reason {@link #place} (or, with {@code movingId}, {@link #move})
	 * would refuse it for, in their order, and what placing there would note (water, a short approach, hostile mobs
	 * removed, the site warnings). Changes nothing; never loads a chunk. Server thread.
	 */
	public record Verdict(List<String> refusals, List<String> notes) {
		public Verdict {
			refusals = List.copyOf(refusals);
			notes = List.copyOf(notes);
		}

		public boolean ok() {
			return refusals.isEmpty();
		}
	}

	/** {@link Verdict} for placing {@code bp} (or moving {@code movingId} with it) at {@code origin}, {@code rotation}. Server thread. */
	public static Verdict verdict(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rotation, List<String> repos, boolean force,
		@Nullable String movingId) {
		return verdict(level, bp, origin, rotation, repos, force, movingId, true);
	}

	/**
	 * {@link #verdict}; {@code dryRun} false reads the site as {@link #place} does, loading its chunks (the confirm, which
	 * places in the same server task right after), true never loads one (the ghost's polling: an unloaded site is a
	 * refusal of its own).
	 */
	public static Verdict verdict(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rotation, List<String> repos, boolean force,
		@Nullable String movingId, boolean dryRun) {
		List<String> refusals = new ArrayList<>();
		Refusals out = refusals::add;
		Building moving = null;
		try {
			if (movingId != null) {
				moving = get(movingId);
				if (moving == null) {
					return new Verdict(List.of("No building " + movingId), List.of());
				}
				ServerLevel oldLevel = levelOf(level.getServer(), moving);
				if (oldLevel == null) {
					refusals.add(moving.dimensionOrDefault() + " is not loaded; nothing was moved");
				}
				if (loadFailed) {
					refusals.add(FILE + " could not be read when the world started; nothing was moved");
				}
				if (moving.isFixture() != bp.isFixture()) {
					refusals.add(moving.blueprint() + " is " + (bp.isFixture() ? "a fixture" : "a building") + " blueprint now, but " + movingId + " is "
						+ (moving.isFixture() ? "a fixture" : "a building") + "; nothing was moved");
				}
				Building m = moving;
				if (!m.isFixture()) {
					collect(refusals, () -> checkRepos(bp.id(), bp.wings(), m.repos(), movingId));
				}
				if (oldLevel != null && (!dryRun || loaded(oldLevel, m.restoreBox()))) {
					collect(refusals, () -> refusePlayerIn(oldLevel, m.restoreBox(), movingId, "moving it"));
					if (!force) {
						List<String> blockers = removalBlockers(oldLevel, moving);
						if (!blockers.isEmpty()) {
							refusals.add(blockersMessage(movingId, blockers).replace("removing it", "moving it"));
						}
					}
				}
			} else {
				if (bp.isFixture()) {
					if (!repos.isEmpty()) {
						refusals.add(bp.name() + " is a fixture: it takes no repos");
					}
				} else {
					collect(refusals, () -> checkRepos(bp.id(), bp.wings(), repos, null));
				}
				if (loadFailed) {
					refusals.add(FILE + " could not be read when the world started (see the log); fix or move it, then restart");
				}
			}
			String journal = WorldJournal.unavailable();
			if (journal != null) {
				refusals.add(journal);
			}
			SitePlan site = checkSite(level, bp, origin, rotation, force, moving, out, dryRun);
			return new Verdict(refusals, site == null ? List.of() : siteNotes(site, site.found()));
		} catch (BuildingException | RuntimeException e) {
			refusals.add(e.getMessage() == null ? e.toString() : e.getMessage());
			return new Verdict(refusals, List.of());
		}
	}

	private interface Check {
		void run() throws BuildingException;
	}

	private static void collect(List<String> refusals, Check c) {
		try {
			c.run();
		} catch (BuildingException e) {
			refusals.add(e.getMessage());
		}
	}

	/** What a placement at a checked site says beside success before anything is built: water, a short approach, hostile mobs removed, site warnings. */
	private static List<String> siteNotes(SitePlan s, List<Occupancy.Found> found) {
		List<String> notes = new ArrayList<>();
		String gone = Occupancy.removalNote(found);
		if (gone != null) {
			notes.add(gone);
		}
		String water = TerrainFit.waterWarning(s.plan());
		if (water != null) {
			notes.add(water + " (filled below the floor; water next to the walls stays)");
		}
		String wet = Approach.waterWarning(s.approach());
		if (wet != null) {
			notes.add(wet);
		}
		String shortOf = Approach.shortWarning(s.approach());
		if (shortOf != null) {
			notes.add(shortOf);
		}
		notes.addAll(s.site().warnings());
		return notes;
	}

	/**
	 * Checks a site ({@link #checkSite}, the first refusal thrown) and puts the template there: the site captured and
	 * drafted in the world journal first ({@link WorldJournal#draft}: a crash leaves it on disk), then the template,
	 * panels, bindings, foundation, cleared terrain, drops caused by it removed, and the site captured again (every cell's
	 * after). {@code moving}: the building being moved (its own repos are fine, its own current box is still an overlap).
	 * Restores the site and throws when anything fails. The caller commits {@link Built#entry}.
	 */
	private static Built build(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rotation, List<String> repos, boolean force,
		@Nullable Building moving, String owner) throws BuildingException {
		SitePlan site = checkSite(level, bp, origin, rotation, force, moving, THROW, false);
		if (site == null) {
			throw new BuildingException("Internal: no site for " + bp.id()); // checkSite threw already
		}
		StructureTemplate template = site.template();
		int turns = site.turns();
		StructurePlaceSettings settings = site.settings();
		BlockPos placePos = site.placePos();
		Anchors.Bounds box = site.box();
		TemplateGrid grid = site.grid();
		TerrainFit.Plan plan = site.plan();
		Approach.Plan approach = site.approach();
		Anchors.Bounds snapBox = site.snapBox();
		List<Occupancy.Found> found = site.found();
		String here = dimensionId(level);

		CompoundTag before;
		Journal.Entry draft;
		try {
			before = WorldJournal.capture(level, snapBox);
			draft = WorldJournal.boxEntry(WorldJournal.newId(), "building", owner, here, WorldJournal.newLayer(), snapBox, before, null, null);
			WorldJournal.draft(draft);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save the snapshot of {}", str(snapBox), e);
			throw new BuildingException("Could not save the snapshot (" + e.getMessage() + "); nothing was placed");
		}
		List<BlockPos> plants = straddlingPositions(level, snapBox, false);
		// the outside halves of the plants the box cuts, as they are: the placement takes them (the inside half goes), so they
		// become guard cells of the held-leaves entry and Remove writes them back right after the box (docs/BUILDINGS.md "Cut plants")
		Map<Long, Journal.Value> cut = new LinkedHashMap<>();
		for (BlockPos half : plants) {
			cut.put(half.asLong(), WorldJournal.valueAt(level, half));
		}
		// the hanging plants around the box as they are (vines on its logs, cocoa, mushrooms beside it): Remove writes back the
		// ones the placement or the restore popped (docs/BUILDINGS.md "Vines and hanging plants")
		Map<Long, Journal.Value> hanging = plantsAround(level, snapBox, here, cut.keySet());
		Drops drops = Drops.before(level, snapBox);
		// leaves outside the box that hang on logs inside it: kept from decaying while the site stands (read before the box
		// changes; their own journal entry, committed with the site's)
		List<Journal.Cell> held = List.of();
		try {
			// the leaves around the box as they are, before anything changes: Remove gives them their distances back
			long r0 = System.nanoTime();
			List<Journal.Cell> ring = ringLeaves(level, snapBox, here, Set.of());
			PlaceTiming.ring(System.nanoTime() - r0, ring.size());
			held = holdLeaves(level, snapBox, here);
			ring = without(ring, held);
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
			BedsOut beds = removeUnsafeBeds(level, grid, turns, box);
			BlockState foundation = foundationState(bp);
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int i = 0; i < plan.fill().length; i += 3) {
				level.setBlock(m.set(plan.fill()[i], plan.fill()[i + 1], plan.fill()[i + 2]), foundation, FLAGS);
			}
			for (int i = 0; i < plan.clear().length; i += 3) {
				level.setBlock(m.set(plan.clear()[i], plan.clear()[i + 1], plan.clear()[i + 2]), Blocks.AIR.defaultBlockState(), FLAGS);
			}
			applyApproach(level, bp, approach, foundation);
			// a tall plant whose other half was inside the box (now gone) would float: take its outside half too
			for (BlockPos half : plants) {
				BlockState outside = level.getBlockState(half);
				BlockPos inside = half.getY() < snapBox.minY() ? half.above() : half.below();
				if (!level.getBlockState(inside).is(outside.getBlock())) {
					level.setBlock(half, Blocks.AIR.defaultBlockState(), FLAGS);
				}
			}
			// plants left without their support go now, quietly and without drops (a bamboo stalk above a cut one would break a
			// segment a tick, dropping items); the outside ones are recorded with air as their after below
			PlantGuard.settle(level, snapBox, hanging.keySet(), FLAGS);
			List<Journal.Cell> guards = guardCells(level, cut);
			if (!guards.isEmpty()) {
				AgentCraft.LOGGER.info("Placing {}: {} two-block plant half(s) outside box {} kept as guard cells: {}", owner, guards.size(), str(snapBox),
					guards.stream().map(c -> BlockPos.of(c.pos()).toShortString() + " " + c.before().name()).toList());
				List<Journal.Cell> both = new ArrayList<>(held);
				both.addAll(guards);
				held = both;
			}
			List<Journal.Cell> hung = hanging.isEmpty() ? List.of() : PlantGuard.cells(level, hanging, newLayer());
			drops.clearNew(level);
			List<String> notes = new ArrayList<>();
			String bedNote = BedSafety.note(beds.heads().size(), here);
			if (bedNote != null) {
				notes.add(bedNote);
			}
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
			if (plan.clearCount() > 0) {
				notes.add(plan.clearCount() + " terrain block" + (plan.clearCount() == 1 ? "" : "s") + " cleared");
			}
			String wet = Approach.waterWarning(approach);
			if (wet != null) {
				notes.add(wet);
			}
			if (approach.rows() > 0) {
				notes.add("entrance approach " + approach.rows() + " rows (" + approach.changed() + " blocks)");
			}
			String shortOf = Approach.shortWarning(approach);
			if (shortOf != null) {
				notes.add(shortOf);
			}
			notes.addAll(site.site().warnings());
			CompoundTag after;
			try {
				after = WorldJournal.capture(level, snapBox);
			} catch (IOException e) {
				throw new IllegalStateException("could not capture the built site: " + e.getMessage(), e);
			}
			return new Built(turns, box, snapBox, BlueprintTransform.worldBounds(bp, turns, box.minX(), box.minY(), box.minZ()),
				BedSafety.withoutBeds(BlueprintTransform.worldAnchors(bp, turns, box.minX(), box.minY(), box.minZ(), repos), beds.heads()),
				BedSafety.strip(pinFor(bp, grid, turns, box), beds.cells(), beds.heads(), box), notes.isEmpty() ? null : String.join("; ", notes),
				WorldJournal.withAfter(draft, snapBox, after), cellEntry(LeafGuard.KIND, owner, here, held),
				cellEntry(LeafGuard.RING_KIND, owner, here, ring), cellEntry(PlantGuard.KIND, owner, here, hung), before);
		} catch (RuntimeException e) {
			// never leave a half-built, unrecorded box behind: put the site back as it was captured
			AgentCraft.LOGGER.error("Placing {} at {} failed; restoring box {}", bp.id(), origin.toShortString(), str(snapBox), e);
			WorldJournal.restoreTemplate(level, snapBox, before, FLAGS);
			putBackCut(level, cut);
			LeafGuard.release(level, held, FLAGS);
			PlantGuard.putBack(level, hanging.entrySet().stream().map(c -> new Journal.Cell(c.getKey(), 0, c.getValue(), null)).toList(), FLAGS);
			WorldJournal.discardDraft(draft.id());
			throw new BuildingException("Placing " + bp.id() + " failed (" + e.getMessage() + "); the area was restored");
		}
	}

	// ------------------------------------------------------------------ cut plants (guard cells)

	/**
	 * The guard cells of a placement (docs/BUILDINGS.md "Cut plants"): each outside half of a two-block plant the box cut
	 * ({@code cut}: position -> the block before) that the placement changed (vanilla drops it with its inside half, or the
	 * placement takes it), as a cell {@code before} = the plant's half, {@code after} = what is there now. They join the
	 * held-leaves entry, so Remove and Move undo them with the site and {@code WorldJournal.apply} writes them right after the box.
	 */
	private static List<Journal.Cell> guardCells(ServerLevel level, Map<Long, Journal.Value> cut) {
		if (cut.isEmpty()) {
			return List.of();
		}
		List<Journal.Cell> out = new ArrayList<>();
		long layer;
		try {
			layer = WorldJournal.newLayer();
		} catch (IOException e) {
			throw new IllegalStateException("no world journal: " + e.getMessage(), e);
		}
		for (var c : cut.entrySet()) {
			Journal.Value now = WorldJournal.valueAt(level, BlockPos.of(c.getKey()));
			if (!now.equals(c.getValue())) {
				out.add(new Journal.Cell(c.getKey(), layer, c.getValue(), now));
			}
		}
		return out;
	}

	/** A placement taken down before its commit: the cut plants' outside halves back (quietly, after the box), where gone. */
	private static void putBackCut(ServerLevel level, Map<Long, Journal.Value> cut) {
		for (var c : cut.entrySet()) {
			BlockPos p = BlockPos.of(c.getKey());
			BlockState want = WorldJournal.state(level, c.getValue());
			if (level.getBlockState(p) != want && level.getBlockState(p).isAir()) {
				level.setBlock(p, want, LeafGuard.quiet(FLAGS));
			}
		}
	}

	/** {@link #putBackCut} of a built site's guard cells (the non-leaf cells of its held-leaves entry). */
	private static void putBackCut(ServerLevel level, Built built) {
		if (built.leaves() == null) {
			return;
		}
		Map<Long, Journal.Value> cut = new LinkedHashMap<>();
		for (Journal.Cell c : built.leaves().cells()) {
			if (!(WorldJournal.state(level, c.before()).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) {
				cut.put(c.pos(), c.before());
			}
		}
		putBackCut(level, cut);
	}

	/** A new journal layer (a placement's changes after its box). */
	private static long newLayer() {
		try {
			return WorldJournal.newLayer();
		} catch (IOException e) {
			throw new IllegalStateException("no world journal: " + e.getMessage(), e);
		}
	}

	// ------------------------------------------------------------------ hanging plants (PlantGuard)

	/**
	 * {@link PlantGuard#read} around {@code box}. Left out, as for the leaf ring: the cells of every active journal entry
	 * near it (they are theirs), the standing sites' boxes, and {@code guards} (the cut plants' outside halves: guard cells
	 * of their own). Before the box changes; changes nothing.
	 */
	private static Map<Long, Journal.Value> plantsAround(ServerLevel level, Anchors.Bounds box, String dimension, Set<Long> guards) {
		int r = PlantGuard.REACH + PlantGuard.RUN;
		Set<Long> owned = new HashSet<>(guards);
		List<Anchors.Bounds> standing = standingBoxes(dimension);
		try {
			for (Journal.Entry e : WorldJournal.activeTouching(dimension, new int[] {box.minX() - PlantGuard.REACH, box.minY() - r,
				box.minZ() - PlantGuard.REACH, box.maxX() + PlantGuard.REACH, box.maxY() + r, box.maxZ() + PlantGuard.REACH})) {
				for (Journal.Cell c : e.cells()) {
					owned.add(c.pos());
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("no world journal: " + e.getMessage(), e);
		}
		return PlantGuard.read(level, box, pos -> owned.contains(pos)
			|| standing.stream().anyMatch(b -> b.contains(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos))));
	}

	// ------------------------------------------------------------------ held leaves (LeafGuard)

	/** The restore boxes of the sites standing in {@code dimension} (a held leaf is never one of their cells). */
	private static List<Anchors.Bounds> standingBoxes(String dimension) {
		return state.byId().values().stream().filter(x -> x.dimensionOrDefault().equals(dimension)).map(Building::restoreBox).toList();
	}

	/** {@link LeafGuard#hold} around {@code box} at a new layer, the standing sites' boxes left out. Before the box changes. */
	private static List<Journal.Cell> holdLeaves(ServerLevel level, Anchors.Bounds box, String dimension) {
		try {
			return LeafGuard.hold(level, box, standingBoxes(dimension), WorldJournal.newLayer(), FLAGS);
		} catch (IOException e) {
			throw new IllegalStateException("no world journal: " + e.getMessage(), e);
		}
	}

	/**
	 * A CELL journal entry of a building's leaves ({@link LeafGuard#KIND} held leaves, {@link LeafGuard#RING_KIND} its leaf
	 * ring; CELL: a leaf the player changed is left), or null for none.
	 */
	private static Journal.@Nullable Entry cellEntry(String kind, String owner, String dimension, List<Journal.Cell> cells) {
		return cellEntry(kind, owner, dimension, cells, null);
	}

	/** The meta key naming the undo group a re-hold or re-ring followed ({@link #followers}). */
	static final String AFTER = "after";

	/** {@link #cellEntry(String, String, String, List)} made after the undo {@code after} (a re-hold, a re-ring), recorded in its meta. */
	private static Journal.@Nullable Entry cellEntry(String kind, String owner, String dimension, List<Journal.Cell> cells, @Nullable String after) {
		if (cells.isEmpty()) {
			return null;
		}
		JsonObject meta = null;
		if (after != null) {
			meta = new JsonObject();
			meta.addProperty(AFTER, after);
		}
		try {
			return new Journal.Entry(WorldJournal.newId(), kind, owner, dimension, Journal.Policy.CELL, System.currentTimeMillis(),
				Journal.Status.ACTIVE, cells, null, meta);
		} catch (IOException e) {
			throw new IllegalStateException("no world journal: " + e.getMessage(), e);
		}
	}

	/**
	 * After {@code box} got its old terrain back (Remove, Move): the sites standing near it hold the leaves that hang on
	 * them now (restored leaves whose logs a standing site cleared, leaves a removed neighbour held), each as a new entry
	 * of its own. A failure is logged and the hold taken back: those leaves then decay as they would without the mod.
	 */
	private static void rehold(ServerLevel level, Anchors.Bounds box, String after) {
		String here = dimensionId(level);
		List<Anchors.Bounds> skip = standingBoxes(here);
		Map<String, Journal.Entry> up = new LinkedHashMap<>();
		try {
			for (Building x : state.byId().values()) {
				if (!x.dimensionOrDefault().equals(here) || !LeafGuard.near(x.restoreBox(), box, 2 * LeafGuard.RADIUS)) {
					continue;
				}
				Journal.Entry e = cellEntry(LeafGuard.KIND, x.id(), here, LeafGuard.hold(level, x.restoreBox(), skip, WorldJournal.newLayer(), FLAGS),
					after);
				if (e != null) {
					up.put(e.id(), e);
				}
			}
			if (!up.isEmpty()) {
				WorldJournal.commit(up, List.of());
				AgentCraft.LOGGER.info("Holding {} more leaves for {} after {} got its terrain back", up.values().stream().mapToInt(e -> e.cells().size())
					.sum(), up.values().stream().map(Journal.Entry::owner).toList(), str(box));
			}
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Could not hold the leaves near {} again; they may decay", str(box), e);
			for (Journal.Entry x : up.values()) {
				LeafGuard.release(level, x.cells(), FLAGS);
			}
		}
	}

	// ------------------------------------------------------------------ leaf ring (LeafGuard.ring)

	/**
	 * {@link LeafGuard#ring} around {@code box} at a new layer. Left out: the cells of every active journal entry near it
	 * (a site's box, held leaves, another ring, a road: those cells are theirs, and a ring cell over them would take their
	 * undo's write as a hand-down), the standing sites' boxes and {@code taken}. Before the box changes; changes nothing.
	 */
	private static List<Journal.Cell> ringLeaves(ServerLevel level, Anchors.Bounds box, String dimension, Set<Long> taken) {
		Anchors.Bounds rb = LeafGuard.ringBounds(box);
		Set<Long> owned = new HashSet<>(taken);
		List<Anchors.Bounds> standing = standingBoxes(dimension);
		try {
			for (Journal.Entry e : WorldJournal.activeTouching(dimension, new int[] {rb.minX(), rb.minY(), rb.minZ(), rb.maxX(), rb.maxY(), rb.maxZ()})) {
				for (Journal.Cell c : e.cells()) {
					owned.add(c.pos());
				}
			}
			return LeafGuard.ring(level, box, pos -> owned.contains(pos)
				|| standing.stream().anyMatch(b -> b.contains(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos))), WorldJournal.newLayer());
		} catch (IOException e) {
			throw new IllegalStateException("no world journal: " + e.getMessage(), e);
		}
	}

	/** {@code ring} without the positions of {@code held} (a held leaf's natural state comes back with its hold). */
	private static List<Journal.Cell> without(List<Journal.Cell> ring, List<Journal.Cell> held) {
		if (held.isEmpty()) {
			return ring;
		}
		Set<Long> h = new HashSet<>();
		held.forEach(c -> h.add(c.pos()));
		return ring.stream().filter(c -> !h.contains(c.pos())).toList();
	}

	/**
	 * After {@code box} got its old terrain back (Remove, Move): the sites standing near it ring the leaves around them that
	 * belong to no journal entry now (the removed site's box, ring and holds had them), as they are now (just restored),
	 * each as a new entry of its own, undone with the site. Without it such a leaf would be in no ring, and what a site
	 * standing next to it changes later (a moved building's new site: its placement's leaf ticks run after the old site is
	 * restored) would stay after that site's Remove. Each new entry names the undo group it followed ({@link #AFTER}): when
	 * the next world start brings that undo back ({@link #followers}), it is released. A failure is logged (nothing changed).
	 */
	private static void rering(ServerLevel level, Anchors.Bounds box, String after) {
		String here = dimensionId(level);
		Map<String, Journal.Entry> up = new LinkedHashMap<>();
		Set<Long> taken = new HashSet<>();
		try {
			for (Building x : state.byId().values()) {
				if (!x.dimensionOrDefault().equals(here) || !LeafGuard.near(x.restoreBox(), box, 2 * LeafGuard.RING)) {
					continue;
				}
				List<Journal.Cell> cells = ringLeaves(level, x.restoreBox(), here, taken);
				cells.forEach(c -> taken.add(c.pos()));
				Journal.Entry e = cellEntry(LeafGuard.RING_KIND, x.id(), here, cells, after);
				if (e != null) {
					up.put(e.id(), e);
				}
			}
			if (!up.isEmpty()) {
				WorldJournal.commit(up, List.of());
				AgentCraft.LOGGER.info("Ringing {} more leaves for {} after {} got its terrain back", taken.size(),
					up.values().stream().map(Journal.Entry::owner).toList(), str(box));
			}
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Could not ring the leaves near {} again; removing the sites near it may leave leaf distances changed", str(box), e);
		}
	}

	/** The template's beds a placement left out ({@link #removeUnsafeBeds}): every removed cell and the head cells, as {@link BlockPos#asLong}. */
	private record BedsOut(java.util.Set<Long> cells, java.util.Set<Long> heads) {
	}

	/**
	 * Takes the template's own beds out again where the level's bed rule makes them dangerous (docs/BUILDINGS.md "Beds":
	 * in the Nether and the End a bed explodes when used, which ends a Hardcore world): both halves become air (no drops,
	 * {@link #FLAGS}). The rule is read at each bed's head cell, as vanilla does. Only cells the template wrote a bed to
	 * are looked at, never a block of the player's. Server thread.
	 */
	private static BedsOut removeUnsafeBeds(ServerLevel level, TemplateGrid grid, int turns, Anchors.Bounds box) {
		java.util.Set<Long> cells = new java.util.HashSet<>();
		java.util.Set<Long> heads = new java.util.HashSet<>();
		GhostModel m = grid.ghost(turns);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int i = 0; i < m.count(); i++) {
			if (!(grid.states()[i].getBlock() instanceof AbstractBedBlock)) {
				continue;
			}
			BlockState s = level.getBlockState(p.set(box.minX() + m.x(i), box.minY() + m.y(i), box.minZ() + m.z(i)));
			if (!(s.getBlock() instanceof AbstractBedBlock bed)) {
				continue;
			}
			Direction facing = s.getValue(AbstractBedBlock.FACING);
			BlockPos head = BedSafety.head(p.getX(), p.getY(), p.getZ(), s.getValue(AbstractBedBlock.PART) == BedPart.HEAD, facing.getStepX(),
				facing.getStepZ());
			BedRule rule = bed.getBedRule(level, head);
			if (!BedSafety.unsafe(rule.canSleep() == BedRule.Rule.NEVER, rule.destroyOnUse(), rule.destroyOnLeave())) {
				continue;
			}
			cells.add(p.asLong());
			heads.add(head.asLong());
		}
		for (long c : cells) {
			level.setBlock(BlockPos.of(c), Blocks.AIR.defaultBlockState(), FLAGS);
		}
		if (!cells.isEmpty()) {
			AgentCraft.LOGGER.info("Left out {} bed(s) of {} in {}: beds are not safe there", heads.size(), grid.blueprint().id(), dimensionId(level));
		}
		return new BedsOut(java.util.Set.copyOf(cells), java.util.Set.copyOf(heads));
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

	/**
	 * The box a placement snapshots and restores: the template's box, grown down to the lowest foundation cell and out
	 * over the entrance approach (docs/BUILDINGS.md "Entrance approach"), plus one row below the lowest written cell: the
	 * ground under the floor and the foundation changes while the site stands (grass under a solid block turns to dirt),
	 * and Remove puts that back too. The extra row is left out where it would be below {@code worldMinY} (the level's
	 * floor); a lower written cell still leaves the build height and is refused.
	 */
	public static Anchors.Bounds snapshotBox(Anchors.Bounds box, TerrainFit.Plan plan, Approach.Plan approach, int worldMinY) {
		Anchors.Bounds u = approach.union(box);
		int lowest = Math.min(u.minY(), plan.minY());
		return new Anchors.Bounds(u.minX(), lowest - 1 >= worldMinY ? lowest - 1 : lowest, u.minZ(), u.maxX(), u.maxY(), u.maxZ());
	}

	/** Builds the entrance approach: clears, fills with the foundation, lays the path and the half-step slabs. */
	private static void applyApproach(ServerLevel level, Blueprint bp, Approach.Plan a, BlockState foundation) {
		if (a.rows() == 0) {
			return;
		}
		BlockState path = blockState(bp.approach().block(), Approach.DEFAULT_BLOCK, bp.id());
		BlockState slab = blockState(bp.approach().slab(), Approach.DEFAULT_SLAB, bp.id());
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int i = 0; i < a.clear().length; i += 3) {
			level.setBlock(m.set(a.clear()[i], a.clear()[i + 1], a.clear()[i + 2]), air, FLAGS);
		}
		for (int i = 0; i < a.fill().length; i += 3) {
			level.setBlock(m.set(a.fill()[i], a.fill()[i + 1], a.fill()[i + 2]), foundation, FLAGS);
		}
		for (int i = 0; i < a.path().length; i += 3) {
			level.setBlock(m.set(a.path()[i], a.path()[i + 1], a.path()[i + 2]), path, FLAGS);
		}
		for (int i = 0; i < a.slabs().length; i += 3) {
			level.setBlock(m.set(a.slabs()[i], a.slabs()[i + 1], a.slabs()[i + 2]), slab, FLAGS);
		}
	}

	/** A block's default state by id, or {@code def}'s when the id is not a block (logged). */
	private static BlockState blockState(@Nullable String id, String def, String bpId) {
		Identifier key = id == null ? null : Identifier.tryParse(id);
		Block b = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
		if (b == null || b == Blocks.AIR) {
			AgentCraft.LOGGER.warn("Blueprint {}: approach block {} is not a block; using {}", bpId, id, def);
			b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(def));
		}
		return b.defaultBlockState();
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
	 * second confirm: they are lost). The restore is the undo of its world journal entry and its trophies' (contract J1:
	 * cells a newer change covers are handed down to it, not overwritten); the undone entries are kept until the next world
	 * start confirms the restored terrain reached the disk (crash safety, see {@link #reconcile}). Server thread.
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
		requireJournal();
		String entry = siteEntry(b);
		if (entry == null) {
			throw new BuildingException("The saved terrain of " + id + " is missing, so it cannot be restored; "
				+ "/agentcraft remove " + id + " forget drops the record and leaves the blocks");
		}
		refusePlayerIn(level, b.restoreBox(), id, "removing it");
		if (!force) {
			List<String> blockers = removalBlockers(level, b);
			if (!blockers.isEmpty()) {
				throw new BuildingException(blockersMessage(id, blockers));
			}
		}
		Journal.UndoPlan plan = undoSite(level, b, entry);
		Drops drops = Drops.before(level, b.restoreBox());
		WorldJournal.apply(level, plan, FLAGS, FLAGS);
		drops.clearNew(level);
		State s = state;
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		rehome(map, b);
		List<Building.Pending> pending = new ArrayList<>(s.pending());
		pending.add(new Building.Pending(b, entry, System.currentTimeMillis(), "removed"));
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(pending)));
		rehold(level, b.restoreBox(), entry);
		rering(level, b.restoreBox(), entry);
		Journal.Stats st = plan.stats().get(entry);
		AgentCraft.LOGGER.info("Removed building {} ({}): restored box {}{}; journal {} kept until the next world start{}", id, b.blueprint(),
			str(b.restoreBox()), force ? " (forced)" : "", entry, st == null || st.covered() == 0 ? ""
				: " (" + st.covered() + " cells under newer changes handed down to them)");
		return b;
	}

	/** The active journal entry of a building's current site (its box), or null when there is none (a record whose snapshot is missing). */
	static @Nullable String siteEntry(Building b) {
		for (JournalStore.Meta m : WorldJournal.find("building", b.id(), Journal.Status.ACTIVE)) {
			if (sameBox(m.box(), b.restoreBox())) {
				return m.id();
			}
		}
		return null;
	}

	static boolean sameBox(int @Nullable [] box, Anchors.Bounds b) {
		return box != null && box[0] == b.minX() && box[1] == b.minY() && box[2] == b.minZ() && box[3] == b.maxX() && box[4] == b.maxY()
			&& box[5] == b.maxZ();
	}

	/**
	 * Plans the undo of a building's site: its entry, the trophies hung in it and the leaves it holds ({@link LeafGuard}:
	 * every active hold of the building belongs to its current site), one group named after the site's entry
	 * ({@link WorldJournal#planUndo}). Nothing is committed or changed.
	 */
	private static Journal.UndoPlan planSite(ServerLevel level, Building b, String entry) throws IOException {
		List<String> ids = new ArrayList<>();
		ids.add(entry);
		for (String kind : SITE_KINDS) {
			for (JournalStore.Meta m : WorldJournal.find(kind, b.id(), Journal.Status.ACTIVE)) {
				ids.add(m.id());
			}
		}
		return WorldJournal.planUndo(level, ids, entry, LeafGuard.or(Roads::stillOurs));
	}

	/**
	 * {@link #planSite} and its commit. Nothing in the world has changed yet when this returns; the caller writes the
	 * plan's blocks. Throws (nothing changed) when the journal cannot be read or saved.
	 */
	private static Journal.UndoPlan undoSite(ServerLevel level, Building b, String entry) throws BuildingException {
		try {
			Journal.UndoPlan plan = planSite(level, b, entry);
			WorldJournal.commit(plan.updated(), List.of());
			return plan;
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.error("Could not undo the journal entry {} of {}", entry, b.id(), e);
			throw new BuildingException("The world journal could not be read or saved (" + e.getMessage() + "); nothing was changed");
		}
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
	 * dropped items (not the trophy signs the mod hung at the building's trophy slots, {@link Trophies}; not natural drops,
	 * {@link NaturalDrops}: what decaying leaves and cleared plants dropped is nobody's), item frames, paintings, armor stands
	 * and other non-living entities in the box. Server thread.
	 */
	public static List<String> removalBlockers(ServerLevel level, Building b) {
		List<String> out = new ArrayList<>();
		java.util.Set<BlockPos> own = ownBlockEntities(b);
		java.util.Set<Long> trophies = Trophies.cells(b);
		Anchors.Bounds box = b.restoreBox();
		forEachBlockEntity(level, box, be -> {
			BlockPos p = be.getBlockPos();
			if (TrophySlots.exempt(trophies, p.getX(), p.getY(), p.getZ(), be instanceof SignBlockEntity)) {
				return; // a trophy the mod hung on the building's own trophy wall: the snapshot puts the cell back
			}
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
				if (!(e instanceof ItemEntity item && NaturalDrops.natural(item))) {
					dropped++; // saplings, sticks, apples, seeds and flowers the trees and plants dropped are nobody's
				}
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

	/**
	 * Drops the record of a building without touching the world: its journal entries (the site's, its trophies' and its
	 * held leaves') are released, so its blocks stay for good (a change made over it later restores them; one under it
	 * never reaches them) and the leaves it held stay persistent, as the building stays.
	 */
	public static void forget(MinecraftServer server, String id) throws BuildingException {
		State s = state;
		Building b = s.byId().get(id);
		if (b == null) {
			throw new BuildingException("No building " + id);
		}
		requireJournal();
		List<String> release = new ArrayList<>();
		for (JournalStore.Meta m : WorldJournal.find("building", id, Journal.Status.ACTIVE)) {
			release.add(m.id());
		}
		for (String kind : SITE_KINDS) {
			for (JournalStore.Meta m : WorldJournal.find(kind, id, Journal.Status.ACTIVE)) {
				release.add(m.id());
			}
		}
		try {
			WorldJournal.commit(Map.of(), release);
		} catch (IOException e) {
			throw new BuildingException("The world journal could not be saved (" + e.getMessage() + "); " + id + " was not forgotten");
		}
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		map.remove(id);
		rehome(map, b);
		reports.remove(id);
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), s.pending()));
		Trophies.forgetBuilding(id);
	}

	/** After {@code gone} left {@code map}: when it was home, the first remaining building becomes home. */
	static void rehome(Map<String, Building> map, Building gone) {
		if (gone.home()) {
			homeFirst(map);
		}
	}

	/**
	 * A loaded file's home rule: a fixture is never home (a file edited by hand could say so), and without a home the first
	 * building (never a fixture) becomes home.
	 */
	static void normalizeHome(Map<String, Building> map) {
		map.replaceAll((k, v) -> v.isFixture() && v.home() ? v.withHome(false) : v);
		if (map.values().stream().noneMatch(Building::home)) {
			homeFirst(map);
		}
	}

	/** Makes the first building (never a fixture) of {@code map} home; nothing when there is none. */
	static void homeFirst(Map<String, Building> map) {
		for (var e : map.entrySet()) {
			if (!e.getValue().isFixture()) {
				e.setValue(e.getValue().withHome(true));
				return;
			}
		}
	}

	/** Makes {@code id} the home building. Server thread. */
	public static Building setHome(MinecraftServer server, String id) throws BuildingException {
		State s = state;
		if (!s.byId().containsKey(id)) {
			throw new BuildingException("No building " + id);
		}
		if (s.byId().get(id).isFixture()) {
			throw new BuildingException(id + " is a fixture (" + s.byId().get(id).blueprint() + "), not a building: it cannot be home");
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
		if (b.isFixture()) {
			throw new BuildingException(id + " is a fixture (" + b.blueprint() + "): it takes no repos");
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
		long t0 = System.nanoTime();
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
		if (oldLevel == null) {
			throw new BuildingException(b.dimensionOrDefault() + " is not loaded; nothing was moved");
		}
		requireJournal();
		String oldEntry = siteEntry(b);
		if (oldEntry == null) {
			throw new BuildingException("The saved terrain of " + id + " is missing, so its old site cannot be restored; nothing was moved");
		}
		if (loadFailed) {
			throw new BuildingException(FILE + " could not be read when the world started; nothing was moved");
		}
		// the new site is placed from the blueprint as it is now, which may have fewer wings than when it was placed
		if (b.isFixture() != bp.isFixture()) {
			throw new BuildingException(b.blueprint() + " is " + (bp.isFixture() ? "a fixture" : "a building") + " blueprint now, but " + id + " is "
				+ (b.isFixture() ? "a fixture" : "a building") + "; nothing was moved");
		}
		if (!b.isFixture()) {
			checkRepos(bp.id(), bp.wings(), b.repos(), id);
		}
		refusePlayerIn(oldLevel, b.restoreBox(), id, "moving it");
		if (!force) {
			List<String> blockers = removalBlockers(oldLevel, b);
			if (!blockers.isEmpty()) {
				throw new BuildingException(blockersMessage(id, blockers).replace("removing it", "moving it"));
			}
		}
		Built built = build(level, bp, origin, rotation, b.repos(), force, b, id);
		long now = System.currentTimeMillis();
		Building nb = new Building(id, b.blueprint(), b.repos(), b.home(), BlueprintTransform.rotationName(built.turns()), built.box(),
			built.bounds(), built.anchors(), b.placedAt(), dimensionId(level), built.snapshotBox(), Math.max(now, b.revision() + 1), b.site(),
			built.pin());
		// one journal commit: the new site's entry and the old site's undo (with its trophies); a failure before it commits
		// nothing and takes the new site down again, so the record never points at a site the journal does not hold
		Journal.UndoPlan plan;
		try {
			plan = planSite(oldLevel, b, oldEntry);
			Map<String, Journal.Entry> up = new LinkedHashMap<>(plan.updated());
			up.putAll(built.entries(nb));
			if (failNextMove) {
				failNextMove = false;
				throw new IOException("injected failure of the move's journal commit (dev.buildings.failNextRename)");
			}
			WorldJournal.commit(up, List.of());
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.error("Moving {} failed before its journal commit; taking the new site down again", id, e);
			unbuild(level, built);
			throw new BuildingException("Moving " + id + " failed (" + e.getMessage() + "); the new site was restored, " + id + " stays where it was");
		}
		try {
			Drops drops = Drops.before(oldLevel, b.restoreBox());
			WorldJournal.apply(oldLevel, plan, FLAGS, FLAGS);
			drops.clearNew(oldLevel);
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("Moving {}: restoring the old site failed; taking the move back", id, e);
			try {
				WorldJournal.commit(Journal.reactivate(plan.updated().values(), oldEntry), List.copyOf(built.entries(nb).keySet()));
			} catch (IOException | RuntimeException e2) {
				AgentCraft.LOGGER.error("Moving {}: could not take the journal commit back; check {} by hand", id, JournalStore.DIR, e2);
			}
			try {
				WorldJournal.restoreTemplate(level, built.snapshotBox(), built.before(), FLAGS);
				putBackCut(level, built);
				if (built.leaves() != null) {
					LeafGuard.release(level, built.leaves().cells(), FLAGS);
				}
				if (built.plants() != null) {
					PlantGuard.putBack(level, built.plants().cells(), FLAGS);
				}
			} catch (RuntimeException e3) {
				AgentCraft.LOGGER.error("Moving: could not take the new site {} down again", str(built.snapshotBox()), e3);
			}
			throw new BuildingException("Moving " + id + " failed (" + e.getMessage() + "); the new site was restored, " + id + " stays where it was");
		}
		State s = state;
		Map<String, Building> map = new LinkedHashMap<>(s.byId());
		map.put(id, nb);
		List<Building.Pending> pending = new ArrayList<>(s.pending());
		pending.add(new Building.Pending(b, oldEntry, now, "moved"));
		reports.remove(id);
		commit(server, new State(Collections.unmodifiableMap(map), s.next(), List.copyOf(pending)));
		rehold(oldLevel, b.restoreBox(), oldEntry);
		rering(oldLevel, b.restoreBox(), oldEntry);
		Trophies.rehang(server, nb);
		lastNote = built.note();
		AgentCraft.LOGGER.info("Moved building {} from {} ({}) to {} ({}){}", id, str(b.box()), b.dimensionOrDefault(), str(nb.box()),
			nb.dimensionOrDefault(), built.note() == null ? "" : "; " + built.note());
		PlaceTiming.placed(System.nanoTime() - t0);
		return nb;
	}

	/** Test hook (DevBridge {@code dev.buildings.failNextRename}): the next move's journal commit fails. */
	private static volatile boolean failNextMove;

	/**
	 * Arms {@link #failNextMove} (DevBridge only): the next {@link #move} fails at its journal commit, before it writes, so it
	 * rolls back (the new site restored, the record unchanged) as it did when a snapshot rename failed. Other commits (a
	 * trophy, a road) do not consume it.
	 */
	public static void failNextSnapshotRename() {
		failNextMove = true;
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
	 * {matching non-air blocks, non-air template blocks}, trophy slot cells left out; {0, 0} when it cannot be checked.
	 */
	static int[] standing(MinecraftServer server, Building b, @Nullable TemplateGrid grid) {
		ServerLevel level = levelOf(server, b);
		if (grid == null || level == null) {
			return new int[] {0, 0};
		}
		int turns = Math.max(0, BlueprintTransform.ROTATIONS.indexOf(b.rotation()));
		GhostModel m = grid.ghost(turns);
		java.util.Set<Long> trophies = Trophies.cells(b);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int total = 0;
		int match = 0;
		for (int i = 0; i < m.count(); i++) {
			BlockState want = grid.states()[i];
			if (want.isAir() || trophies.contains(TrophySlots.cell(b.box().minX() + m.x(i), b.box().minY() + m.y(i), b.box().minZ() + m.z(i)))) {
				continue; // trophy cells hold the mod's signs, not the template's air
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
	 * Whether a taken-down site shows its saved terrain again (docs/BUILDINGS.md "Crash safety"): over the cells where the
	 * terrain (the befores of its journal entry {@code entry}) and the building differ (the building's own template when
	 * its pin matches, else its pinned block entities), the share that hold the terrain's block ({@link Reconcile#restored}).
	 * Null when it cannot be told.
	 */
	static @Nullable Boolean restored(MinecraftServer server, Building b, Journal.Entry entry) {
		ServerLevel level = levelOf(server, b);
		int[] eb = entry.box();
		if (level == null || eb == null) {
			return null;
		}
		StructureTemplate before;
		try {
			before = WorldJournal.beforeTemplate(level, entry);
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.warn("Buildings check: could not read the journal entry {}", entry.id(), e);
			return null;
		}
		if (before == null) {
			return null;
		}
		TemplateGrid saved = TemplateGrid.read(null, before);
		Anchors.Bounds rb = new Anchors.Bounds(eb[0], eb[1], eb[2], eb[3], eb[4], eb[5]);
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
		if (loadFailed) {
			return;
		}
		repairJournal(server);
		State s = state;
		if (s.byId().isEmpty() && s.pending().isEmpty()) {
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
		java.util.Set<String> recovered = new java.util.HashSet<>();
		for (Building.Pending p : List.copyOf(pending)) {
			Building gone = p.building();
			boolean moved = "moved".equals(p.why());
			String jid = WorldJournal.resolve(SNAPSHOT_DIR, p.snapshot());
			Journal.Entry entry = null;
			if (jid != null) {
				try {
					entry = WorldJournal.load(jid);
				} catch (IOException e) {
					AgentCraft.LOGGER.warn("Buildings check: could not read the journal entry {} of {}'s {} site; kept", jid, gone.id(), p.why(), e);
					continue;
				}
			}
			if (entry == null || entry.active()) {
				// missing: nothing to settle; active: the site's entry stands for a current record (an import of an inconsistent file)
				AgentCraft.LOGGER.warn("Buildings check: the saved terrain {} of {}'s {} site is {}; dropping the entry", p.snapshot(), gone.id(), p.why(),
					entry == null ? "missing" : "in use by a standing site");
				pending.remove(p);
				changed = true;
				continue;
			}
			List<Reconcile.Site> sites = new ArrayList<>();
			for (Building o : map.values()) {
				sites.add(new Reconcile.Site(o.id(), o.dimensionOrDefault(), o.restoreBox(), standsNow.get(o.id())));
			}
			Reconcile.Found found = Reconcile.overlap(new Reconcile.Site(gone.id(), gone.dimensionOrDefault(), gone.restoreBox(), null), sites);
			Reconcile.Overlap overlap = found.overlap();
			String over = found.by() == null || found.by().equals(gone.id()) ? null : found.by();
			Building current = map.get(gone.id());
			Boolean stands = stands(server, gone);
			Boolean restored = restored(server, gone, entry);
			Reconcile.Action action = Reconcile.decide(moved, stands, restored, overlap, current != null,
				current == null ? null : standsNow.get(gone.id()));
			AgentCraft.LOGGER.info("Buildings check: {} site of {} at {} (stands {}, restored {}, overlap {}): {}", p.why(), gone.id(),
				str(gone.restoreBox()), stands, restored, overlap, action);
			switch (action) {
				case RELEASE -> {
					try {
						WorldJournal.commit(Map.of(), group(entry));
					} catch (IOException e) {
						AgentCraft.LOGGER.warn("Could not release the journal entry {}", entry.id(), e);
						continue;
					}
					pending.remove(p);
					changed = true;
				}
				case RECOVER -> {
					if (moved) {
						// the move never reached the disk: the building is still at its old site, with that site's entry; the new
						// site's entries (and its trophies) are kept undone as unused (they name no record)
						try {
							Map<String, Journal.Entry> up = new LinkedHashMap<>(reactivation(entry));
							long now = System.currentTimeMillis();
							for (String kind : java.util.stream.Stream.concat(java.util.stream.Stream.of("building"), java.util.Arrays.stream(SITE_KINDS))
								.toList()) {
								for (JournalStore.Meta m : WorldJournal.find(kind, gone.id(), Journal.Status.ACTIVE)) {
									if (!up.containsKey(m.id())) {
										Journal.Entry e = WorldJournal.load(m.id());
										up.put(m.id(), e.undone(new Journal.Undo("unused-" + m.id(), now, Map.of(), List.of())));
									}
								}
							}
							WorldJournal.commit(up, followers(entry, up.keySet()));
						} catch (IOException | RuntimeException e) {
							AgentCraft.LOGGER.error("Buildings check {}: could not put the journal back", gone.id(), e);
							report(gone.id(), true, gone.id() + "'s move was not saved before the game stopped and it could not be put back; its old "
								+ "site's saved terrain is kept as journal entry " + entry.id());
							continue;
						}
						map.put(gone.id(), gone.withHome(current.home()));
						report(gone.id(), false, gone.id() + "'s move was not saved before the game stopped: it is back at its old site");
					} else {
						try {
							Map<String, Journal.Entry> up = reactivation(entry);
							WorldJournal.commit(up, followers(entry, up.keySet()));
						} catch (IOException | RuntimeException e) {
							AgentCraft.LOGGER.error("Buildings check {}: could not put the journal back", gone.id(), e);
							continue;
						}
						map.put(gone.id(), gone.withHome(!gone.isFixture() && noBuilding(map)));
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
					report(on, true, what + ". Its saved terrain is kept as journal entry " + entry.id() + "; nothing was changed");
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

	/** {@link #repair} against the open journal; its notes become reports. Server thread, at world start. */
	private static void repairJournal(MinecraftServer server) {
		JournalStore js;
		try {
			js = WorldJournal.store();
		} catch (IOException e) {
			return; // no journal: nothing is changed this session (place/remove/move refuse)
		}
		List<SiteEntry> entries = new ArrayList<>();
		for (JournalStore.Meta m : js.find(m -> m.kind().equals("building"))) {
			entries.add(new SiteEntry(m.id(), m.owner(), m.status(), m.box(), m.undoneAt()));
		}
		State s = state;
		Repaired r = repair(s.byId(), s.pending(), entries, snap -> WorldJournal.resolve(SNAPSHOT_DIR, snap), id -> {
			try {
				Journal.Entry e = js.load(id);
				return e.meta() == null ? null : Building.fromJson(e.meta());
			} catch (IOException | RuntimeException ex) {
				AgentCraft.LOGGER.warn("Buildings check: could not read the record in journal entry {}", id, ex);
				return null;
			}
		});
		if (r.notes().isEmpty()) {
			return;
		}
		int next = s.next();
		for (Building b : r.records().values()) {
			next = Math.max(next, Building.idNumber(b.id()) + 1);
		}
		state = new State(Collections.unmodifiableMap(r.records()), next, List.copyOf(r.pending()));
		save(server, state);
		r.notes().forEach((id, msg) -> report(id, false, msg));
	}

	/** The ids of the entries undone together with {@code e} (its group: the site and its trophies). */
	private static List<String> group(Journal.Entry e) {
		String g = e.undo() == null ? e.id() : e.undo().group();
		List<String> out = new ArrayList<>();
		JournalStore s;
		try {
			s = WorldJournal.store();
		} catch (IOException ex) {
			return List.of(e.id());
		}
		for (JournalStore.Meta m : s.find(m -> m.status() == Journal.Status.UNDONE && g.equals(m.group()))) {
			out.add(m.id());
		}
		if (!out.contains(e.id())) {
			out.add(e.id());
		}
		return out;
	}

	/**
	 * The active held-leaves and leaf-ring entries made after {@code e}'s undo (a re-hold, a re-ring: {@link #AFTER} names
	 * its group), not in {@code keep}. When that undo is reactivated (it never reached the disk) they are released with it:
	 * their cells may lie in the box that stands again, and a cell over the box's would turn its next undo's write into a
	 * hand-down that writes nothing. A re-ring changed no block; a re-hold's leaves stay as they are.
	 */
	private static List<String> followers(Journal.Entry e, java.util.Set<String> keep) throws IOException {
		String g = e.undo() == null ? e.id() : e.undo().group();
		List<String> out = new ArrayList<>();
		JournalStore s = WorldJournal.store();
		for (JournalStore.Meta m : s.find(m -> m.active() && (m.kind().equals(LeafGuard.KIND) || m.kind().equals(LeafGuard.RING_KIND)))) {
			if (keep.contains(m.id())) {
				continue;
			}
			Journal.Entry x = s.load(m.id());
			if (x.meta() != null && x.meta().has(AFTER) && g.equals(x.meta().get(AFTER).getAsString())) {
				out.add(m.id());
			}
		}
		if (!out.isEmpty()) {
			AgentCraft.LOGGER.info("Buildings check: releasing {} (made after the undo {}, which is taken back)", out, g);
		}
		return out;
	}

	/** {@link Journal#reactivate} of {@code e}'s group: loads the group and the entries it handed cells to. */
	private static Map<String, Journal.Entry> reactivation(Journal.Entry e) throws IOException {
		Map<String, Journal.Entry> loaded = new LinkedHashMap<>();
		for (String id : group(e)) {
			Journal.Entry g = WorldJournal.load(id);
			loaded.put(id, g);
			if (g.undo() != null) {
				for (Journal.HandDown h : g.undo().handed()) {
					if (!loaded.containsKey(h.to()) && WorldJournal.store().meta(h.to()) != null) {
						loaded.put(h.to(), WorldJournal.load(h.to()));
					}
				}
			}
		}
		String g = e.undo() == null ? e.id() : e.undo().group();
		return Journal.reactivate(loaded.values(), g);
	}

	/** A site entry as {@link #repair} sees it: its journal id, owner, status, box, when it was undone, its record (meta; null: none). */
	record SiteEntry(String id, String owner, Journal.Status status, int @Nullable [] box, long undoneAt) {
	}

	/** What {@link #repair} made of the records. */
	record Repaired(Map<String, Building> records, List<Building.Pending> pending, Map<String, String> notes) {
	}

	/**
	 * The crash windows between a journal commit and the buildings file's save (docs/BUILDINGS.md "World journal"), settled
	 * at world start before {@link #reconcile}, pure. The journal is written first, so it is never behind:
	 * <ul>
	 * <li>a record whose site's entry was undone in the journal (a removal) with no pending entry naming it: the record
	 * goes to {@code pending} as removed (the world-start check then decides on evidence, as for any removal);</li>
	 * <li>a record whose site is not the journal's active one, which stands elsewhere (a move): the record follows the
	 * journal's (its meta) and the old site goes to {@code pending} as moved;</li>
	 * <li>an active site entry with no record and no pending entry (a placement whose record was not saved): its record
	 * comes back from the entry's meta.</li>
	 * </ul>
	 * {@code resolve}: a pending entry's snapshot name -> journal id; {@code metaOf}: an entry's record, or null.
	 */
	static Repaired repair(Map<String, Building> records, List<Building.Pending> pending, List<SiteEntry> entries,
		java.util.function.Function<String, @Nullable String> resolve, java.util.function.Function<String, @Nullable Building> metaOf) {
		Map<String, Building> map = new LinkedHashMap<>(records);
		List<Building.Pending> pend = new ArrayList<>(pending);
		Map<String, String> notes = new LinkedHashMap<>();
		// a pending site whose entry is active again with no record of its building: a world start took the removal back
		// (record back), its journal commit reached the disk and the file did not. The journal says the site stands, so the
		// record comes back (else the pending would be dropped as "in use" and the standing building orphaned)
		for (Building.Pending p : List.copyOf(pend)) {
			Building gone = p.building();
			String id = resolve.apply(p.snapshot());
			if (id == null || map.containsKey(gone.id())) {
				continue;
			}
			SiteEntry e = entries.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
			if (e == null || e.status() != Journal.Status.ACTIVE || !e.owner().equals(gone.id()) || !sameBox(e.box(), gone.restoreBox())) {
				continue;
			}
			boolean overlaps = map.values().stream().anyMatch(o -> o.dimensionOrDefault().equals(gone.dimensionOrDefault())
				&& Building.intersects(o.restoreBox(), gone.restoreBox()));
			if (overlaps) {
				notes.put(gone.id(), gone.id() + "'s removal was taken back before the game stopped but not saved, and another building stands there "
					+ "now: not added (its saved terrain is journal entry " + id + ")");
				continue;
			}
			pend.remove(p);
			map.put(gone.id(), gone.withHome(false));
			notes.put(gone.id(), gone.id() + "'s removal was taken back before the game stopped but " + FILE + " was not saved: its record is back");
		}
		java.util.Set<String> referenced = new java.util.HashSet<>();
		java.util.Set<String> pendingOwners = new java.util.HashSet<>();
		for (Building.Pending p : pend) {
			String id = resolve.apply(p.snapshot());
			if (id != null) {
				referenced.add(id);
			}
			pendingOwners.add(p.building().id());
		}
		for (Building r : List.copyOf(map.values())) {
			List<SiteEntry> active = new ArrayList<>();
			SiteEntry undone = null;
			boolean ok = false;
			for (SiteEntry e : entries) {
				if (!e.owner().equals(r.id())) {
					continue;
				}
				if (e.status() == Journal.Status.ACTIVE) {
					active.add(e);
					ok |= sameBox(e.box(), r.restoreBox());
				} else if (sameBox(e.box(), r.restoreBox()) && !referenced.contains(e.id()) && (undone == null || e.undoneAt() > undone.undoneAt())) {
					undone = e;
				}
			}
			if (ok) {
				continue;
			}
			if (!active.isEmpty()) {
				Building moved = metaOf.apply(active.get(active.size() - 1).id());
				if (moved == null || !moved.id().equals(r.id())) {
					continue;
				}
				map.put(r.id(), moved.withHome(r.home()));
				if (undone != null) {
					pend.add(new Building.Pending(r, undone.id(), undone.undoneAt(), "moved"));
					referenced.add(undone.id());
				}
				notes.put(r.id(), r.id() + "'s move reached the world journal but not " + FILE + " before the game stopped: its record follows the journal");
			} else if (undone != null) {
				map.remove(r.id());
				rehome(map, r);
				pend.add(new Building.Pending(r, undone.id(), undone.undoneAt(), "removed"));
				referenced.add(undone.id());
				notes.put(r.id(), r.id() + "'s removal reached the world journal but not " + FILE + " before the game stopped: it is settled as a removal");
			}
		}
		for (SiteEntry e : entries) {
			if (e.status() != Journal.Status.ACTIVE || map.containsKey(e.owner()) || pendingOwners.contains(e.owner()) || referenced.contains(e.id())) {
				continue;
			}
			Building b = metaOf.apply(e.id());
			if (b == null || !b.id().equals(e.owner()) || !sameBox(e.box(), b.restoreBox())) {
				continue;
			}
			boolean overlaps = false;
			for (Building o : map.values()) {
				overlaps |= o.dimensionOrDefault().equals(b.dimensionOrDefault()) && Building.intersects(o.restoreBox(), b.restoreBox());
			}
			if (overlaps) {
				notes.put(b.id(), b.id() + " was placed before the game stopped but its record was not saved, and another building stands there now: not "
					+ "added (its saved terrain is journal entry " + e.id() + ")");
				continue;
			}
			map.put(b.id(), b.withHome(false));
			notes.put(b.id(), b.id() + " was placed before the game stopped but its record was not saved: its record is back");
		}
		normalizeHome(map);
		return new Repaired(map, pend, notes);
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
		List<Building> list = buildings();
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
			normalizeHome(map);
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
