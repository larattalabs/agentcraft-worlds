package dev.agentcraft.client.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.DevCommands;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.routine.BedPicker;
import dev.agentcraft.routine.BedRest;
import dev.agentcraft.routine.LibraryVisits;
import dev.agentcraft.routine.RoutineRules;
import dev.agentcraft.routine.RoutineRules.Facts;
import dev.agentcraft.routine.RoutineRules.Kind;
import dev.agentcraft.routine.RoutineSettings;
import dev.agentcraft.routine.RoutineSettings.Toggle;
import dev.agentcraft.routine.StandupTracker;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The village routines on the client (docs/VILLAGE.md V3): night rest in the building's beds (else the lounge), stand-ups
 * when a goal's first tasks are assigned, and library visits after an agent's memory note. Pure scheduling lives in
 * {@code dev.agentcraft.routine} ({@link RoutineRules}, {@link BedPicker}, {@link StandupTracker}, {@link LibraryVisits});
 * this class feeds it from the Foreman state and the world, and {@link AgentManager} asks it, per agent and tick, which
 * station to use instead of the usual one ({@link #plan}). Agents are client-only: nothing here changes the world
 * (a "lying" agent is a render pose; the bed block is never touched).
 *
 * <p>Rules that keep it safe: a routine target is always in the layout the agent is routed to (no routine starts a walk
 * between buildings); a stand-up only gathers participants already routed to the lead's building; nothing starts or
 * ends while the Foreman link is stale; every hook is wrapped by the caller (an error here falls back to normal work).
 * Client thread only.
 */
public final class Routines {
	private static final Routines INSTANCE = new Routines();
	/** A stand-up is skipped when the player is farther than this from the building (blocks). */
	static final double STANDUP_RANGE = 64;
	/** Recheck the beds of a layout (the player may sleep in one, a bed may be broken) every this many ticks. */
	private static final int BED_RECHECK = 40;
	/** Vanilla puts a sleeper this high above the bed block's floor (sleep height 0.5625 + 0.125). */
	private static final double LIE_HEIGHT = 0.6875;

	/** A bed of a layout found in the world. {@code approach}: the free standable cell beside it (feet), or null. */
	record BedSpot(String name, BlockPos head, Direction facing, boolean occupied, @Nullable Vec3 approach) {
		/** Where the agent walks to before lying down (stable name: retargeting compares names). */
		Anchor approachAnchor() {
			Vec3 a = approach != null ? approach : Vec3.atBottomCenterOf(head);
			float yaw = (float) Math.toDegrees(Math.atan2(-(head.getX() + 0.5 - a.x), head.getZ() + 0.5 - a.z));
			return new Anchor(BedRest.restAnchor(name), a.x, a.y, a.z, yaw, 0);
		}

		/** The lying position (vanilla's: the head cell's centre, above the mattress), facing the bed's way. */
		Anchor lieAnchor() {
			return new Anchor(name + "@lie", head.getX() + 0.5, head.getY() + LIE_HEIGHT, head.getZ() + 0.5, facing.toYRot(), 0);
		}
	}

	/** This tick's routine for one agent: what it does, the station key it uses instead of its own (null = its own), its bed. */
	record Plan(Kind kind, @Nullable String stationKey, @Nullable BedSpot bed, String layout) {
	}

	private record BedCache(long revision, long tick, List<BedSpot> beds) {
	}

	/** A stand-up that started, with the layout it gathers in. */
	private record Running(StandupTracker.Standup standup, String layout, String station) {
	}

	private @Nullable RoutineSettings settings;
	private final StandupTracker standups = new StandupTracker();
	private final LibraryVisits library = new LibraryVisits();
	/** Stand-ups started this session, by key (the tracker drops ended ones). */
	private final Map<String, Running> running = new LinkedHashMap<>();
	private final Map<String, Plan> plans = new HashMap<>();
	private final Map<String, Kind> kinds = new HashMap<>();
	/** agent id -> layout name + "|" + bed name it slept in (sticky across ticks). */
	private final Map<String, String> beds = new HashMap<>();
	private final Map<String, BedCache> bedCache = new HashMap<>();
	private @Nullable ClientLevel level;
	private long now;
	private long clock;
	private boolean night;
	private boolean seeded;
	// hub toggle row layout at the last frame
	private int uiNeeded;
	private int uiAvailable;
	private String uiMode = "";
	private boolean uiDrawn;

	private Routines() {
	}

	public static Routines get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------ settings (per world, routines.json)

	static Path settingsFile() {
		return FabricLoader.getInstance().getGameDir().resolve("agentcraft").resolve(RoutineSettings.FILE);
	}

	private RoutineSettings settings() {
		if (settings == null) {
			settings = RoutineSettings.load(settingsFile());
		}
		return settings;
	}

	/** A village routine's toggle for this world (default on). */
	public boolean enabled(Toggle t) {
		return settings().enabled(OutdoorRoutes.world(), t);
	}

	/** Sets a routine's toggle for this world and saves routines.json now; turning one off stops what runs. */
	public void setEnabled(Toggle t, boolean on) {
		RoutineSettings s = settings();
		if (s.set(OutdoorRoutes.world(), t, on) || s.dirty()) {
			try {
				s.save(settingsFile());
			} catch (IOException e) {
				AgentCraft.LOGGER.warn("Could not save {}", settingsFile(), e);
			}
		}
		AgentCraft.LOGGER.info("{}: {} (world {})", t.label, on ? "on" : "off", OutdoorRoutes.world());
		if (!on && t == Toggle.STANDUPS) {
			standups.stopAll(now, "stopped: turned off");
			running.clear();
		}
		if (!on && t == Toggle.LIBRARY) {
			for (String id : List.copyOf(library.visits().keySet())) {
				library.cancel(id, "turned off", now);
			}
		}
	}

	/** The hub's toggle row reports its fit each frame it is drawn ({@code dev.routines.state ui}). */
	public void reportUi(int needed, int available, String mode) {
		uiNeeded = needed;
		uiAvailable = available;
		uiMode = mode;
		uiDrawn = true;
	}

	// ------------------------------------------------------------------ Foreman events

	/** A snapshot: goals already under way had their stand-up (no replay on reconnect). */
	void onSnapshot(ForemanState st) {
		standups.seed(goalViews(st), taskViews(st));
		seeded = true;
	}

	/** A memory note: its author walks to the library when it is between steps. */
	void onMemory(Protocol.@Nullable MemoryEntry prev, Protocol.MemoryEntry entry, @Nullable ForemanState st) {
		String author = entry.author();
		if (author == null || st == null || st.agent(author) == null || !enabled(Toggle.LIBRARY)) {
			return;
		}
		if (prev != null && prev.updated() == entry.updated() && prev.body().equals(entry.body())) {
			return; // the same note again (a resend), nothing new was written
		}
		if (library.note(author, now)) {
			AgentCraft.LOGGER.info("Agent {} wrote memory '{}': library visit queued", author, entry.title());
		}
	}

	// ------------------------------------------------------------------ per tick (AgentManager)

	/** Level changed or the link went away: everybody stands, nothing is running. */
	void reset(String why) {
		for (ClientAgentEntity e : AgentManager.get().entities().values()) {
			if (e.life().lyingIn() != null) {
				e.life().lie(null);
			}
			holdBook(e, false);
		}
		standups.stopAll(now, why);
		running.clear();
		plans.clear();
		kinds.clear();
		beds.clear();
		bedCache.clear();
		library.clear();
		seeded = false;
	}

	/**
	 * Start of a tick: the clock, the library queue and the stand-ups that are due (started in the lead's building when
	 * the player is near and it is loaded, else skipped with the reason).
	 *
	 * @param routed agent id -> the layout it is routed to this tick
	 */
	void begin(Minecraft mc, ClientLevel lvl, ForemanState st, long liveTicks, boolean stale, Map<String, Anchors.Layout> routed) {
		if (lvl != level) {
			level = lvl;
			bedCache.clear();
		}
		now = liveTicks;
		clock = lvl.getOverworldClockTime();
		night = RoutineRules.isNight(clock);
		plans.clear();
		if (stale) {
			return;
		}
		if (!seeded) {
			onSnapshot(st); // the first tick after (re)joining with data: what is under way is not a fresh goal
		}
		library.tick(now, id -> st.agent(id) != null);
		if (now % 5 == 0) { // goals and tasks change a few times a minute; the debounce is 60 ticks
			for (StandupTracker.Standup s : standups.due(goalViews(st), taskViews(st), now)) {
				startOrSkip(mc, lvl, s, routed, false);
			}
		}
		Collection<StandupTracker.Standup> live = standups.running(now);
		running.keySet().removeIf(k -> live.stream().noneMatch(s -> s.key.equals(k)));
	}

	/** Starts a due stand-up in its lead's building, or records why not. Also used by {@code dev.routines.standup}. */
	String startOrSkip(Minecraft mc, ClientLevel lvl, StandupTracker.Standup s, Map<String, Anchors.Layout> routed, boolean force) {
		String why = skipReason(mc, lvl, s, routed, force);
		if (why != null) {
			standups.skip(s, why, now);
			AgentCraft.LOGGER.info("Stand-up for goal {} skipped: {}", s.goalId, why);
			return "skipped: " + why;
		}
		Anchors.Layout l = routed.get(anchorAgent(s, routed));
		String station = StationAssigner.slots(l, AnchorNames.MEETING).isEmpty() ? AnchorNames.USER : AnchorNames.MEETING;
		standups.start(s, now);
		running.put(s.key, new Running(s, l.name(), station));
		AgentCraft.LOGGER.info("Stand-up for goal {} in {}: {}", s.goalId, l.name(), s.participants());
		return "started";
	}

	/** The participant whose building hosts the stand-up: the lead, else the first worker routed anywhere. */
	private static @Nullable String anchorAgent(StandupTracker.Standup s, Map<String, Anchors.Layout> routed) {
		if (s.lead != null && routed.containsKey(s.lead)) {
			return s.lead;
		}
		for (String p : s.participants()) {
			if (routed.containsKey(p)) {
				return p;
			}
		}
		return null;
	}

	/** Why a stand-up does not happen, or null. {@code force} (DevBridge) ignores the player's distance and unloaded chunks. */
	private @Nullable String skipReason(Minecraft mc, ClientLevel lvl, StandupTracker.Standup s, Map<String, Anchors.Layout> routed, boolean force) {
		if (!enabled(Toggle.STANDUPS)) {
			return "stand-ups off";
		}
		String host = anchorAgent(s, routed);
		Anchors.Layout l = host == null ? null : routed.get(host);
		if (l == null || l.isEmpty() || l.bounds() == null) {
			return "building not loaded (no participant here)";
		}
		if (StationAssigner.slots(l, AnchorNames.MEETING).isEmpty() && StationAssigner.slots(l, AnchorNames.USER).isEmpty()) {
			return "no meeting spot in " + l.name();
		}
		if (force) {
			return null;
		}
		Anchors.Bounds b = l.bounds();
		LocalPlayer p = mc.player;
		double d = p == null ? Double.MAX_VALUE : distanceToBox(p.position(), b);
		if (d > STANDUP_RANGE) {
			return String.format(Locale.ROOT, "player far (%.0f blocks)", Math.min(d, 99_999));
		}
		if (!lvl.hasChunksAt(b.minX(), b.minZ(), b.maxX(), b.maxZ())) {
			return "building not loaded";
		}
		return null;
	}

	static double distanceToBox(Vec3 p, Anchors.Bounds b) {
		double dx = Math.max(0, Math.max(b.minX() - p.x, p.x - (b.maxX() + 1)));
		double dy = Math.max(0, Math.max(b.minY() - p.y, p.y - (b.maxY() + 1)));
		double dz = Math.max(0, Math.max(b.minZ() - p.z, p.z - (b.maxZ() + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * The routines of one building's agents this tick; returns the station key each agent uses (its own when no routine
	 * changes it). {@code facts}: the inputs of {@link RoutineRules#decide} per agent.
	 */
	Function<Agent, String> plan(ClientLevel lvl, Anchors.Layout layout, @Nullable GridPathfinder pf, List<Agent> agents,
		Function<Agent, Facts> facts, boolean stale, Map<String, @Nullable String> repoOf) {
		Map<String, Kind> decided = new LinkedHashMap<>();
		boolean restOn = night && enabled(Toggle.NIGHT);
		boolean libOn = enabled(Toggle.LIBRARY);
		for (Agent a : agents) {
			Kind prev = kinds.getOrDefault(a.id(), Kind.NONE);
			Running r = standupOf(a.id());
			boolean inStandup = r != null && r.layout.equals(layout.name());
			Kind k = RoutineRules.decide(facts.apply(a), stale, prev, inStandup, libOn && library.due(a.id()), restOn);
			if (k == Kind.LIBRARY && StationAssigner.slots(layout, AnchorNames.LIBRARY).isEmpty()) {
				library.cancel(a.id(), "no library in " + layout.name(), now);
				k = RoutineRules.decide(facts.apply(a), stale, prev, inStandup, false, restOn);
			}
			if (!stale) {
				LibraryVisits.Visit v = library.of(a.id());
				if (k == Kind.LIBRARY && v != null && v.phase() == LibraryVisits.Phase.WAITING) {
					library.start(a.id(), now);
				} else if (k != Kind.LIBRARY && v != null && v.phase() != LibraryVisits.Phase.WAITING) {
					library.cancel(a.id(), k == Kind.STANDUP ? "stand-up" : "work", now);
				}
			}
			decided.put(a.id(), k);
			kinds.put(a.id(), k);
		}
		// beds for the resting agents of this building, nearest their own part of it (a campus wing)
		Map<String, BedSpot> bedFor = new HashMap<>();
		List<BedSpot> spots = bedsOf(lvl, layout, pf);
		if (!spots.isEmpty()) {
			List<BedPicker.Want> wants = new ArrayList<>();
			Map<String, String> prev = new HashMap<>();
			for (Agent a : agents) {
				if (decided.get(a.id()) != Kind.REST) {
					continue;
				}
				wants.add(new BedPicker.Want(a.id(), near(layout, a, repoOf.get(a.id()))));
				String had = beds.get(a.id());
				if (had != null && had.startsWith(layout.name() + "|")) {
					prev.put(a.id(), had.substring(layout.name().length() + 1));
				}
			}
			List<BedPicker.Bed> list = new ArrayList<>();
			for (BedSpot b : spots) {
				// a bed nobody can get into does not count
				list.add(new BedPicker.Bed(b.name(), b.head().getX() + 0.5, b.head().getY(), b.head().getZ() + 0.5, b.occupied() || b.approach() == null));
			}
			Map<String, String> got = BedPicker.assign(wants, list, prev);
			for (var e : got.entrySet()) {
				for (BedSpot b : spots) {
					if (b.name().equals(e.getValue())) {
						bedFor.put(e.getKey(), b);
					}
				}
			}
		}
		Map<String, String> keys = new HashMap<>();
		for (Agent a : agents) {
			Kind k = decided.get(a.id());
			BedSpot bed = bedFor.get(a.id());
			if (bed != null) {
				beds.put(a.id(), layout.name() + "|" + bed.name());
			} else if (k != Kind.REST) {
				beds.remove(a.id());
			}
			Running su = k == Kind.STANDUP ? standupOf(a.id()) : null;
			String key = RoutineRules.stationKey(k, su == null ? null : su.station);
			plans.put(a.id(), new Plan(k, key, bed, layout.name()));
			if (key != null) {
				keys.put(a.id(), key);
			}
		}
		return a -> {
			String k = keys.get(a.id());
			return k != null ? k : StationAssigner.stationKey(a);
		};
	}

	/** Where an agent would be without the routine, for the nearest bed: its wing's task wall, its desk, the lounge. */
	private static double @Nullable [] near(Anchors.Layout layout, Agent a, @Nullable String repo) {
		Anchor x = repo == null ? null : layout.get(AnchorNames.TASK_WALL + ":" + repo);
		if (x == null) {
			x = layout.get(AnchorNames.desk(a.id()));
		}
		if (x == null) {
			x = layout.get(AnchorNames.LOUNGE);
		}
		return x == null ? null : new double[] {x.x(), x.y(), x.z()};
	}

	@Nullable Plan planOf(String agentId) {
		return plans.get(agentId);
	}

	private @Nullable Running standupOf(String agentId) {
		for (Running r : running.values()) {
			if (r.standup.participants().contains(agentId) && r.standup.running(now)) {
				return r;
			}
		}
		return null;
	}

	/**
	 * Called for every agent before it is retargeted. True = it keeps lying in its bed (skip retargeting): the plan still
	 * gives it that bed and nothing moved it. Otherwise a lying agent gets up beside its bed first.
	 */
	boolean keepLying(ClientAgentEntity e, @Nullable Plan plan, boolean moved, boolean snap, boolean stale) {
		BedSpot lying = e.life().lyingIn();
		if (lying == null) {
			return false;
		}
		BedSpot want = plan == null ? null : plan.bed();
		if (BedRest.keepLying(stale, moved, snap, plan != null && plan.kind() == Kind.REST, want == null ? null : want.name(),
			want == null ? Long.MIN_VALUE : want.head().asLong(), lying.name(), lying.head().asLong(), !stale && bedGone(lying))) {
			return true;
		}
		getUp(e);
		return false;
	}

	/** Out of bed: standing on the cell beside it (retargeting then walks on from there). */
	void getUp(ClientAgentEntity e) {
		BedSpot b = e.life().lyingIn();
		e.life().lie(null);
		if (b != null) {
			Anchor up = b.approachAnchor();
			Vec3 p = e.motion().placeAt(up);
			e.snapTo(p, up.yaw());
		}
	}

	private boolean bedGone(BedSpot b) {
		ClientLevel lvl = level;
		if (lvl == null) {
			return true;
		}
		BlockState s = lvl.getBlockState(b.head());
		return !(s.getBlock() instanceof BedBlock) || s.getValue(BedBlock.PART) != BedPart.HEAD || s.getValue(BedBlock.OCCUPIED);
	}

	/**
	 * End of a tick: resting agents that reached their bed lie down; library visitors that reached the shelves read (the
	 * book in hand on the way); stand-ups gather and talk.
	 */
	void after(Map<String, ClientAgentEntity> entities, boolean stale) {
		for (ClientAgentEntity e : entities.values()) {
			Plan p = plans.get(e.agentId());
			e.view().routine = p == null ? Kind.NONE : p.kind();
			LibraryVisits.Visit v = library.of(e.agentId());
			boolean visiting = p != null && p.kind() == Kind.LIBRARY && v != null && v.phase() != LibraryVisits.Phase.WAITING;
			if (stale || p == null) {
				holdBook(e, visiting && v.phase() == LibraryVisits.Phase.WALKING);
				continue;
			}
			if (p.kind() == Kind.REST && p.bed() != null && e.life().lyingIn() == null && arrivedAt(e, p.bed().approachAnchor().name(),
				p.bed().approachAnchor().pos()) && !bedGone(p.bed())) {
				lieDown(e, p.bed());
			}
			if (visiting && v.phase() == LibraryVisits.Phase.WALKING && arrivedAtStation(e, AnchorNames.LIBRARY)) {
				library.arrived(e.agentId(), now);
				v = library.of(e.agentId());
			}
			holdBook(e, visiting && v != null && v.phase() == LibraryVisits.Phase.WALKING);
		}
		if (stale) {
			return;
		}
		for (Running r : List.copyOf(running.values())) {
			StandupTracker.Standup s = r.standup;
			boolean all = true;
			for (String id : s.participants()) {
				ClientAgentEntity e = entities.get(id);
				Plan p = plans.get(id);
				if (e == null || p == null || p.kind() != Kind.STANDUP) {
					continue; // not here (another building, waiting on the player): the others do not wait for it
				}
				if (!arrivedAtStation(e, r.station)) {
					all = false;
				}
			}
			for (StandupTracker.Cue c : s.tick(now, all)) {
				ClientAgentEntity e = entities.get(c.agentId());
				Plan p = plans.get(c.agentId());
				if (e == null || p == null || p.kind() != Kind.STANDUP || c.text().isBlank()) {
					continue;
				}
				e.life().bubble.show(c.text(), c.to(), System.currentTimeMillis(), e.life().age());
				ClientAgentEntity to = c.to() == null ? null : entities.get(c.to());
				if (to != null && to != e) {
					to.life().listen(c.agentId(), 60);
				} else if ("all".equals(c.to())) {
					for (String id : s.participants()) {
						ClientAgentEntity o = entities.get(id);
						if (o != null && o != e) {
							o.life().listen(c.agentId(), 60);
						}
					}
				}
			}
		}
	}

	private void lieDown(ClientAgentEntity e, BedSpot b) {
		e.life().setSeat(null);
		Anchor lie = b.lieAnchor();
		Vec3 p = e.motion().placeAt(lie);
		e.snapTo(p, lie.yaw());
		e.life().lie(b);
	}

	private static boolean arrivedAt(ClientAgentEntity e, String anchorName, Vec3 at) {
		Anchor t = e.motion().target();
		return BedRest.arrived(t == null ? null : t.name(), e.motion().walking(), anchorName, e.position().distanceToSqr(at));
	}

	/** Standing (or seated) at a slot of {@code station} (its own spot: the motion target, reached). */
	private static boolean arrivedAtStation(ClientAgentEntity e, String station) {
		Anchor t = e.motion().target();
		return t != null && BedRest.atStation(t.name(), e.motion().walking(), station, e.position().distanceToSqr(t.pos()));
	}

	private static void holdBook(ClientAgentEntity e, boolean on) {
		ItemStack cur = e.getItemBySlot(EquipmentSlot.MAINHAND);
		if (on && !cur.is(Items.BOOK)) {
			e.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOOK)); // a display item of a client-only entity
		} else if (!on && !cur.isEmpty()) {
			e.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
	}

	// ------------------------------------------------------------------ beds in the world

	/** The layout's beds ({@code bed}, {@code bed_2}.. anchors on a bed's head half), cached and rechecked every 2 s. */
	List<BedSpot> bedsOf(ClientLevel lvl, Anchors.Layout layout, @Nullable GridPathfinder pf) {
		BedCache c = bedCache.get(layout.name());
		if (c != null && c.revision == layout.revision() && now - c.tick < BED_RECHECK && now >= c.tick) {
			return c.beds;
		}
		List<BedSpot> out = new ArrayList<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		List<String> names = new ArrayList<>(layout.anchors().keySet());
		names.sort(Routines::bedOrder);
		for (String n : names) {
			if (!BedPicker.isBedAnchor(n)) {
				continue;
			}
			Anchor a = layout.get(n);
			BlockPos head = BlockPos.containing(a.x(), a.y() + 0.01, a.z());
			if (!lvl.hasChunkAt(head) || !seen.add(head)) {
				continue;
			}
			BlockState s = lvl.getBlockState(head);
			if (!(s.getBlock() instanceof BedBlock) || s.getValue(BedBlock.PART) != BedPart.HEAD) {
				continue; // broken or replaced: not a bed any more
			}
			Direction facing = s.getValue(BedBlock.FACING);
			out.add(new BedSpot(n, head, facing, s.getValue(BedBlock.OCCUPIED), pf == null ? null : approach(pf, head, facing)));
		}
		bedCache.put(layout.name(), new BedCache(layout.revision(), now, List.copyOf(out)));
		return out;
	}

	/** bed, bed_2, bed_3 .. bed_10 (numeric order), then names with a wing suffix. */
	private static int bedOrder(String a, String b) {
		return Integer.compare(bedIndex(a), bedIndex(b)) != 0 ? Integer.compare(bedIndex(a), bedIndex(b)) : a.compareTo(b);
	}

	private static int bedIndex(String n) {
		String base = n.contains(":") ? n.substring(0, n.indexOf(':')) : n;
		int u = base.indexOf('_');
		try {
			return (u < 0 ? 1 : Integer.parseInt(base.substring(u + 1))) + (n.contains(":") ? 1000 : 0);
		} catch (NumberFormatException e) {
			return 9999;
		}
	}

	/** A free standable cell beside the bed: beside the head, then beside the foot, then beyond the foot. */
	private static @Nullable Vec3 approach(GridPathfinder pf, BlockPos head, Direction facing) {
		double[] a = BedRest.approach(head.getX(), head.getY(), head.getZ(), facing.getStepX(), facing.getStepZ(), pf::floor);
		return a == null ? null : new Vec3(a[0], a[1], a[2]);
	}

	// ------------------------------------------------------------------ Foreman views for the stand-up tracker

	static List<StandupTracker.GoalView> goalViews(ForemanState st) {
		List<StandupTracker.GoalView> out = new ArrayList<>();
		for (Protocol.Goal g : st.goals().values()) {
			out.add(new StandupTracker.GoalView(g.id(), g.createdAt(), g.status() == Protocol.GoalStatus.ACTIVE, standupLead(st, g), leadLine(st, g)));
		}
		return out;
	}

	/**
	 * Who opens a goal's stand-up: the goal's lead; a goal without one (the sim's, a goal filed before leads) has the lead
	 * its building shows in the hub: the building's own lead, else Marlow when the building is home ("Marlow (home)").
	 * Before, such a stand-up had no lead, so nobody said the goal's line and only the workers spoke.
	 */
	static @Nullable String standupLead(ForemanState st, Protocol.Goal g) {
		if (g.leadId() != null) {
			return g.leadId();
		}
		dev.agentcraft.building.Building b = g.repoId() == null ? null : dev.agentcraft.building.Buildings.forRepo(g.repoId());
		String lead = b == null ? null : dev.agentcraft.client.leads.Leads.view().leadOf(b.id());
		if (lead != null) {
			return lead;
		}
		boolean home = b == null || b.home();
		return home && st.agents().containsKey(dev.agentcraft.building.LeadRouting.MARLOW) ? dev.agentcraft.building.LeadRouting.MARLOW : null;
	}

	/** What the lead says: its plan note's first line when there is one, else the goal's text. */
	static String leadLine(ForemanState st, Protocol.Goal g) {
		Protocol.MemoryEntry plan = g.planId() == null ? null : st.memory().get(g.planId());
		if (plan != null) {
			for (String line : plan.body().split("\n")) {
				String c = StandupTracker.clean(line);
				if (!c.isEmpty() && !c.startsWith("---")) {
					return c;
				}
			}
		}
		return g.text();
	}

	static List<StandupTracker.TaskView> taskViews(ForemanState st) {
		List<StandupTracker.TaskView> out = new ArrayList<>();
		for (Protocol.Task t : st.tasks().values()) {
			boolean open = t.status() != Protocol.TaskStatus.DONE && t.status() != Protocol.TaskStatus.CANCELLED;
			out.add(new StandupTracker.TaskView(t.id(), t.goalId(), t.assignee(), t.title(), open, t.createdAt()));
		}
		return out;
	}

	// ------------------------------------------------------------------ DevBridge

	static void registerDev() {
		DevBridge.addStateContributor((mc, o) -> o.add("routines", get().summary()));
		DevBridge.register("dev.routines.state", 10_000,
			"-> {settings{night, standups, library}, world, clock, timeOfDay, night, ticksToSwitch, tick, agents[{id, routine, station, layout, bed, lying, "
				+ "target, walking, book, plate, pos}], beds{layout: [{name, head, facing, occupied, approach, agent}]}, standups{running[{goal, layout, "
				+ "station, lead, line, workers[], startedTick, gatheredTick, endTick, ticksLeft}], pending, history[{goal, outcome}]}, library{visits[{agent, "
				+ "phase}], history[]}, ui{drawn, needed, available, mode, overflow}} - the village routines (docs/VILLAGE.md V3)",
			(req, mc) -> DevBridge.onClient(mc, () -> get().state(AgentManager.get().entities())));
		DevBridge.register("dev.routines.toggle", 10_000,
			"{toggle: night|standups|library, on?: bool} - set (or flip) a village routine for this world (hub > Buildings) -> {toggle, enabled, world}",
			(req, mc) -> {
				Fields f = Fields.of(req);
				Toggle t = Toggle.parse(f.nonBlank("toggle"));
				if (t == null) {
					throw new DevBridge.DevException("toggle must be night, standups or library");
				}
				Boolean on = f.optBool("on");
				return DevBridge.onClient(mc, () -> {
					Routines r = get();
					r.setEnabled(t, on != null ? on : !r.enabled(t));
					JsonObject o = new JsonObject();
					o.addProperty("toggle", t.key);
					o.addProperty("enabled", r.enabled(t));
					o.addProperty("world", OutdoorRoutes.world());
					return o;
				});
			});
		DevBridge.register("dev.routines.time", 10_000,
			"{ticks?: 0..23999 | at?: night|midnight|morning|noon|dusk} - set the world's day time through the integrated server (like /time set; "
				+ "night 13000 starts the night routine, morning 23000 ends it) -> the command result + {timeOfDay, night} two frames later",
			(req, mc) -> {
				Fields f = Fields.of(req);
				long ticks;
				if (f.has("ticks")) {
					ticks = f.integer("ticks", 0, Integer.MAX_VALUE);
				} else {
					String at = f.optStr("at", "night").toLowerCase(Locale.ROOT);
					ticks = switch (at) {
						case "night" -> RoutineRules.NIGHT_START + 200;
						case "midnight" -> 18_000;
						case "morning" -> RoutineRules.NIGHT_END;
						case "noon" -> 6_000;
						case "dusk" -> RoutineRules.NIGHT_START - 200;
						default -> throw new DevBridge.DevException("at must be night, midnight, morning, noon or dusk (or give ticks)");
					};
				}
				return DevCommands.serverCommand(mc, "time set " + ticks).thenCompose(r -> dev.agentcraft.client.dev.FrameScheduler.afterFrames(2)
					.thenCompose(v -> DevBridge.onClient(mc, () -> {
						ClientLevel lvl = mc.level;
						long clock = lvl == null ? 0 : lvl.getOverworldClockTime();
						r.addProperty("timeOfDay", RoutineRules.timeOfDay(clock));
						r.addProperty("night", RoutineRules.isNight(clock));
						return r;
					})));
			});
		DevBridge.register("dev.routines.standup", 10_000,
			"{goalId, force?: false} - hold the stand-up of a goal now (its lead + the workers of its open assigned tasks gather at the meeting table, "
				+ "or the podium's user spots); force ignores the player's distance and unloaded chunks (not the toggle) -> {outcome, goal, participants, "
				+ "layout}",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String goalId = f.nonBlank("goalId");
				boolean force = f.optBool("force", false);
				return DevBridge.onClient(mc, () -> {
					ForemanState st = dev.agentcraft.client.foreman.Foreman.state();
					Protocol.Goal g = st == null ? null : st.goals().get(goalId);
					if (g == null || mc.level == null) {
						throw new DevBridge.DevException("no goal '" + goalId + "'");
					}
					StandupTracker.GoalView gv = new StandupTracker.GoalView(g.id(), g.createdAt(), true, standupLead(st, g), leadLine(st, g));
					StandupTracker.Standup s = StandupTracker.build(gv, taskViews(st));
					if (s == null) {
						throw new DevBridge.DevException("goal " + goalId + " has no lead and no assigned open tasks");
					}
					Routines r = get();
					Map<String, Anchors.Layout> routed = AgentManager.get().routedLayoutsNow();
					String outcome = r.startOrSkip(mc, mc.level, s, routed, force);
					JsonObject o = new JsonObject();
					o.addProperty("outcome", outcome);
					o.addProperty("goal", goalId);
					JsonArray ps = new JsonArray();
					s.participants().forEach(ps::add);
					o.add("participants", ps);
					Running run = r.running.get(s.key);
					o.addProperty("layout", run == null ? null : run.layout());
					o.addProperty("station", run == null ? null : run.station());
					return o;
				});
			});
		DevBridge.register("dev.routines.library", 10_000,
			"{agent} - queue a library visit for an agent as if it wrote a memory note (no cooldown); it goes when it is between steps -> {agent, queued}",
			(req, mc) -> {
				String id = Fields.of(req).nonBlank("agent");
				return DevBridge.onClient(mc, () -> {
					ForemanState st = dev.agentcraft.client.foreman.Foreman.state();
					if (st == null || st.agent(id) == null) {
						throw new DevBridge.DevException("no agent '" + id + "'");
					}
					Routines r = get();
					if (!r.enabled(Toggle.LIBRARY)) {
						throw new DevBridge.DevException("library visits are off for this world (dev.routines.toggle {toggle: library, on: true})");
					}
					r.library.force(id, r.now);
					JsonObject o = new JsonObject();
					o.addProperty("agent", id);
					o.addProperty("queued", true);
					return o;
				});
			});
	}

	/** A short summary for {@code dev.state}. */
	private JsonObject summary() {
		JsonObject o = new JsonObject();
		o.addProperty("night", night);
		int rest = 0;
		int lying = 0;
		for (ClientAgentEntity e : AgentManager.get().entities().values()) {
			Plan p = plans.get(e.agentId());
			if (p != null && p.kind() == Kind.REST) {
				rest++;
			}
			if (e.life().lyingIn() != null) {
				lying++;
			}
		}
		o.addProperty("resting", rest);
		o.addProperty("inBed", lying);
		o.addProperty("standups", running.size());
		o.addProperty("libraryVisits", library.visits().size());
		return o;
	}

	StandupTracker standups() {
		return standups;
	}

	LibraryVisits library() {
		return library;
	}

	long now() {
		return now;
	}

	JsonObject state(Map<String, ClientAgentEntity> entities) {
		JsonObject o = new JsonObject();
		JsonObject set = new JsonObject();
		for (Toggle t : Toggle.values()) {
			set.addProperty(t.key, enabled(t));
		}
		o.add("settings", set);
		o.addProperty("world", OutdoorRoutes.world());
		o.addProperty("clock", clock);
		o.addProperty("timeOfDay", RoutineRules.timeOfDay(clock));
		o.addProperty("night", night);
		o.addProperty("ticksToSwitch", RoutineRules.ticksToSwitch(clock));
		o.addProperty("tick", now);
		JsonArray agents = new JsonArray();
		for (ClientAgentEntity e : entities.values()) {
			JsonObject a = new JsonObject();
			a.addProperty("id", e.agentId());
			Plan p = plans.get(e.agentId());
			a.addProperty("routine", p == null ? "none" : p.kind().label);
			ForemanState fst = dev.agentcraft.client.foreman.Foreman.state();
			Protocol.Agent fa = fst == null ? null : fst.agents().get(e.agentId());
			a.addProperty("needsUser", fa != null && AgentManager.needsUser(fst, fa));
			a.addProperty("station", p == null ? null : p.stationKey());
			a.addProperty("layout", p == null ? null : p.layout());
			a.addProperty("bed", p == null || p.bed() == null ? null : p.bed().name());
			BedSpot lying = e.life().lyingIn();
			a.addProperty("lying", lying == null ? null : lying.name());
			Anchor t = e.motion().target();
			a.addProperty("target", t == null ? null : t.name());
			a.addProperty("walking", e.motion().walking());
			a.addProperty("book", e.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.BOOK));
			a.addProperty("plate", e.view().activityLine());
			a.addProperty("pos", String.format(Locale.ROOT, "%.2f %.2f %.2f", e.getX(), e.getY(), e.getZ()));
			agents.add(a);
		}
		o.add("agents", agents);
		JsonObject bs = new JsonObject();
		for (var en : bedCache.entrySet()) {
			JsonArray arr = new JsonArray();
			for (BedSpot b : en.getValue().beds()) {
				JsonObject j = new JsonObject();
				j.addProperty("name", b.name());
				j.addProperty("head", b.head().getX() + " " + b.head().getY() + " " + b.head().getZ());
				j.addProperty("facing", b.facing().getName());
				j.addProperty("occupied", b.occupied());
				j.addProperty("approach", b.approach() == null ? null : String.format(Locale.ROOT, "%.1f %.1f %.1f", b.approach().x, b.approach().y, b.approach().z));
				String by = null;
				for (var bed : beds.entrySet()) {
					if (bed.getValue().equals(en.getKey() + "|" + b.name())) {
						by = bed.getKey();
					}
				}
				j.addProperty("agent", by);
				arr.add(j);
			}
			bs.add(en.getKey(), arr);
		}
		o.add("beds", bs);
		JsonArray su = new JsonArray();
		for (Running r : running.values()) {
			StandupTracker.Standup s = r.standup;
			JsonObject j = new JsonObject();
			j.addProperty("key", s.key);
			j.addProperty("goal", s.goalId);
			j.addProperty("layout", r.layout);
			j.addProperty("station", r.station);
			j.addProperty("lead", s.lead);
			j.addProperty("line", s.line);
			JsonArray ws = new JsonArray();
			for (StandupTracker.Worker w : s.workers) {
				JsonObject wj = new JsonObject();
				wj.addProperty("agent", w.agentId());
				wj.addProperty("task", w.taskId());
				wj.addProperty("line", w.taskTitle());
				ws.add(wj);
			}
			j.add("workers", ws);
			j.addProperty("startedTick", s.start());
			j.addProperty("gatheredTick", s.gatheredAt());
			j.addProperty("endTick", s.end());
			j.addProperty("ticksLeft", Math.max(0, s.end() - now));
			su.add(j);
		}
		JsonObject sj = new JsonObject();
		sj.add("running", su);
		JsonObject pend = new JsonObject();
		standups.pending().forEach((k, v) -> pend.addProperty(k, v));
		sj.add("pending", pend);
		JsonArray hist = new JsonArray();
		for (StandupTracker.Record r : standups.history()) {
			JsonObject j = new JsonObject();
			j.addProperty("key", r.key());
			j.addProperty("goal", r.goalId());
			j.addProperty("outcome", r.outcome());
			j.addProperty("tick", r.tick());
			hist.add(j);
		}
		sj.add("history", hist);
		o.add("standups", sj);
		JsonObject lj = new JsonObject();
		JsonArray vs = new JsonArray();
		for (LibraryVisits.Visit v : library.visits().values()) {
			JsonObject j = new JsonObject();
			j.addProperty("agent", v.agentId());
			j.addProperty("phase", v.phase().name().toLowerCase(Locale.ROOT));
			j.addProperty("sinceTick", v.since());
			j.addProperty("readUntil", v.readUntil());
			vs.add(j);
		}
		lj.add("visits", vs);
		JsonArray lh = new JsonArray();
		for (LibraryVisits.Record r : library.history()) {
			JsonObject j = new JsonObject();
			j.addProperty("agent", r.agentId());
			j.addProperty("outcome", r.outcome());
			j.addProperty("tick", r.tick());
			lh.add(j);
		}
		lj.add("history", lh);
		o.add("library", lj);
		JsonObject ui = new JsonObject();
		ui.addProperty("drawn", uiDrawn);
		ui.addProperty("needed", uiNeeded);
		ui.addProperty("available", uiAvailable);
		ui.addProperty("mode", uiMode);
		ui.addProperty("overflow", uiDrawn && uiNeeded > uiAvailable);
		o.add("ui", ui);
		return o;
	}
}
