package dev.agentcraft.client.hq;

import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.MergeStationBlock;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.DecisionPodiumBlockEntity;
import dev.agentcraft.block.entity.MergeStationBlockEntity;
import dev.agentcraft.block.entity.MonitorBlockEntity;
import dev.agentcraft.block.entity.StatusLampBlockEntity;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.Routing;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.leads.Leads;
import dev.agentcraft.client.world.ServerTasks;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Drives the HQ's world blocks from the Foreman state (client thread computes, the integrated
 * server applies, see {@link ServerTasks}):
 * <ul>
 *   <li>status lamps by binding: {@code agent:<id>} (the agent's status family, the same one its
 *       nameplate shows: an idle/done agent with a decision waiting on you is {@code waiting}; off
 *       when the agent is off shift or gone), {@code ci:<repoId>} or {@code ci:#<n>} (the n-th repo
 *       in Foreman order), {@code goal} / {@code goal:atrium} (the current goal), {@code decisions}
 *       (waiting while any decision is open), {@code merge} (waiting while a merge decision is open);</li>
 *   <li>the decision podium {@code open} while any decision is open;</li>
 *   <li>merge stations {@code active} while a merge decision is open;</li>
 *   <li>monitors {@code lit} while their agent is on shift;</li>
 *   <li>signal bulbs (copper bulbs within 3 blocks of the {@code decision_podium} anchor or of a
 *       {@code mergestation} slot) lit while that station needs you.</li>
 * </ul>
 * Every region of the world that belongs to a layout is driven ({@link Buildings#regions()}): the HQ
 * studio (its bounds) and every building (its box), so a building's lamps, podium and monitors work
 * like the studio's. {@code ci:#<n>} means the n-th repo only in the studio; in a building it is an
 * unrewritten wing placeholder (no repo) and shows idle.
 * Only blocks whose state differs are written. The set of wanted states is recomputed on every
 * Foreman change and re-applied every 2 s (so rebuilt or newly placed blocks pick it up). While the
 * Foreman link is down the blocks keep their last state (the view is stale, not wrong).
 */
public final class HqWorldDriver {
	private static final int RESYNC_TICKS = 40;
	/** Blocks around the layout bounds that still belong to the HQ (lamps set into the walls). */
	private static final int MARGIN = 3;
	/** Reach of a station's signal bulbs around its anchor (blocks). */
	private static final int SIGNAL_REACH = 3;

	/** What the world should show, by binding. Immutable once built. */
	/**
	 * @param podiumOpen     any decision open (every podium, with a Foreman that does not publish leads)
	 * @param homePodiumOpen a decision for the home podium is open (marlow's, a worker's, a podium-less lead's)
	 * @param podiumOpenIn   building ids whose lead has a decision open (their podium opens)
	 * @param leadsKnown     whether podiums are per building (the Foreman publishes leads)
	 */
	record Wanted(Map<String, LampStatus> lamps, boolean podiumOpen, boolean homePodiumOpen, Set<String> podiumOpenIn, boolean leadsKnown,
		boolean mergeActive, Map<String, Boolean> monitorLit) {

		/** Whether the podiums (and their signal bulbs, {@code decisions} lamps) of an area open. */
		boolean podiumOpen(Area a) {
			if (!leadsKnown) {
				return podiumOpen;
			}
			return a.home() && homePodiumOpen || a.buildingId() != null && podiumOpenIn.contains(a.buildingId());
		}
	}

	/**
	 * One region to drive: its area, whether it is a building, its building id (null: the studio), whether
	 * its podium is the home one, and its stations' signal-bulb centres.
	 */
	record Area(Anchors.Bounds b, boolean building, @Nullable String buildingId, boolean home, List<BlockPos> podiumSignals,
		List<BlockPos> mergeSignals, String dimension) {
		Area(Anchors.Bounds b, boolean building, @Nullable String buildingId, boolean home, List<BlockPos> podiumSignals, List<BlockPos> mergeSignals) {
			this(b, building, buildingId, home, podiumSignals, mergeSignals, dev.agentcraft.building.Building.OVERWORLD);
		}
	}

	private static @Nullable Wanted last;
	private static long lastRevision = -1;
	private static long lastLayout = -1;
	private static int ticks;
	private static volatile int lastChanged;

	private HqWorldDriver() {
	}

	/** Blocks changed by the last apply (QA / debugging). */
	public static int lastChanged() {
		return lastChanged;
	}

	public static @Nullable Wanted wanted() {
		return last;
	}

	static void tick(Minecraft mc) {
		if (mc.level == null || mc.getSingleplayerServer() == null) {
			return;
		}
		ForemanState st = Foreman.state();
		List<Routing.Region> regions = Buildings.regions();
		if (st == null || !st.hasData() || st.isStale() || regions.isEmpty()) {
			return;
		}
		ticks++;
		long layoutSig = Buildings.regionsSignature();
		boolean changed = st.revision() != lastRevision || layoutSig != lastLayout;
		if (!changed && ticks % RESYNC_TICKS != 0) {
			return;
		}
		Wanted w = changed || last == null ? compute(st) : last;
		lastRevision = st.revision();
		lastLayout = layoutSig;
		boolean differs = !Objects.equals(w, last);
		last = w;
		if (differs || ticks % RESYNC_TICKS == 0) {
			List<Area> areas = new ArrayList<>(regions.size());
			List<Routing.Site> sites = Buildings.sites();
			for (Routing.Region r : regions) {
				Routing.Site site = null;
				if (r.building()) {
					for (Routing.Site s : sites) {
						if (s.box().equals(r.area())) {
							site = s;
						}
					}
				}
				areas.add(new Area(r.area(), r.building(), site == null ? null : site.buildingId(), site == null || site.home(),
					signalCenters(r.layout(), AnchorNames.DECISION_PODIUM), signalCenters(r.layout(), AnchorNames.MERGESTATION), r.dimension()));
			}
			// each building in its own dimension (the studio is in the overworld)
			Map<String, List<Area>> byDim = new java.util.LinkedHashMap<>();
			for (Area a : areas) {
				byDim.computeIfAbsent(a.dimension(), k -> new ArrayList<>()).add(a);
			}
			int[] total = {0};
			for (var e : byDim.entrySet()) {
				List<Area> in = List.copyOf(e.getValue());
				ServerTasks.run(e.getKey(), level -> {
					total[0] += apply(level, w, in);
					lastChanged = total[0];
				});
			}
		}
	}

	/** Block positions of the anchors of a station (all its slots). */
	private static List<BlockPos> signalCenters(Anchors.Layout layout, String station) {
		List<BlockPos> out = new ArrayList<>();
		for (Anchor a : layout.anchors().values()) {
			String n = a.name();
			if (n.equals(station) || n.startsWith(station + "_") && n.substring(station.length() + 1).chars().allMatch(Character::isDigit)) {
				out.add(BlockPos.containing(a.x(), a.y(), a.z()));
			}
		}
		return out;
	}

	/**
	 * Agent id -> an open decision waiting on the user for that agent: its own question / permission
	 * prompt first, then merges it asked for, then decisions about its task (the same rule as the
	 * agents' nameplates, so a lamp and its agent's status dot always agree).
	 */
	static Map<String, String> awaiting(ForemanState st) {
		Map<String, String> out = new HashMap<>();
		List<Protocol.Decision> open = st.openDecisions();
		for (Protocol.Decision d : open) {
			if (d.kind() != DecisionKind.MERGE) {
				out.putIfAbsent(d.agentId(), d.id());
			}
		}
		for (Protocol.Decision d : open) {
			out.putIfAbsent(d.agentId(), d.id());
		}
		for (Protocol.Decision d : open) {
			if (d.taskId() != null) {
				Protocol.Task t = st.task(d.taskId());
				if (t != null && t.assignee() != null) {
					out.putIfAbsent(t.assignee(), d.id());
				}
			}
		}
		return out;
	}

	/** The lamp an agent shows: its state family, or waiting when it idles on a decision of yours. */
	static LampStatus agentLamp(Agent a, boolean awaitingUser) {
		if (!a.isActive()) {
			return LampStatus.OFF;
		}
		String fam = a.state().family();
		if (awaitingUser && (fam.equals("idle") || fam.equals("done"))) {
			return LampStatus.WAITING;
		}
		return LampStatus.forAgentState(a.state().wire());
	}

	static Wanted compute(ForemanState st) {
		Map<String, LampStatus> lamps = new HashMap<>();
		Map<String, Boolean> lit = new HashMap<>();
		Map<String, String> waitingOn = awaiting(st);
		for (Agent a : st.agents().values()) {
			lamps.put("agent:" + a.id(), agentLamp(a, waitingOn.containsKey(a.id())));
			lit.put(a.id(), a.isActive());
		}
		int n = 0;
		for (Repo r : st.repos().values()) {
			LampStatus ci = LampStatus.forCi(r.ci().wire());
			lamps.put("ci:" + r.id(), ci);
			lamps.put("ci:#" + (++n), ci);
		}
		LampStatus goal = goalLamp(st.goal());
		lamps.put("goal", goal);
		lamps.put("goal:atrium", goal);
		boolean open = !st.openDecisions().isEmpty();
		lamps.put("decisions", open ? LampStatus.WAITING : LampStatus.OFF);
		boolean merge = st.oldestOpen(DecisionKind.MERGE) != null;
		lamps.put("merge", merge ? LampStatus.WAITING : LampStatus.OFF);
		lamps.put(BEACON_BINDING, beaconLamp(st, open, goal));
		// each podium opens for its building's lead (home: marlow's and everyone else's), like its bubble
		Leads.View leads = Leads.view();
		boolean homeOpen = false;
		Set<String> openIn = new HashSet<>();
		for (Protocol.Decision d : st.openDecisions()) {
			String b = leads.podiumOwners().get(d.agentId());
			if (b != null) {
				openIn.add(b);
			} else {
				homeOpen = true;
			}
		}
		return new Wanted(Map.copyOf(lamps), open, homeOpen, Set.copyOf(openIn), leads.known(), merge, Map.copyOf(lit));
	}

	/** The cupola beacon's binding (the whole studio at a glance, seen from outside). */
	public static final String BEACON_BINDING = "beacon";

	/**
	 * The studio's aggregate state for the cupola beacon, most urgent first: anything waiting on you
	 * (clay), an agent in error/blocked (red), work going on (teal) or thinking (brass), the goal done
	 * (sage), else idle.
	 */
	static LampStatus beaconLamp(ForemanState st, boolean decisionOpen, LampStatus goal) {
		if (decisionOpen) {
			return LampStatus.WAITING;
		}
		boolean error = false;
		boolean working = false;
		boolean thinking = false;
		boolean waiting = false;
		for (Agent a : st.agents().values()) {
			if (!a.isActive()) {
				continue;
			}
			switch (a.state().family()) {
				case "waiting" -> waiting = true;
				case "error" -> error = true;
				case "working" -> working = true;
				case "thinking" -> thinking = true;
				default -> {
				}
			}
		}
		if (waiting) {
			return LampStatus.WAITING;
		}
		if (error) {
			return LampStatus.ERROR;
		}
		if (working) {
			return LampStatus.WORKING;
		}
		if (thinking) {
			return LampStatus.THINKING;
		}
		return goal == LampStatus.DONE ? LampStatus.DONE : LampStatus.IDLE;
	}

	static LampStatus goalLamp(@Nullable Goal g) {
		if (g == null) {
			return LampStatus.IDLE;
		}
		return switch (g.status()) {
			case PLANNING -> LampStatus.THINKING;
			case ACTIVE -> LampStatus.WORKING;
			case DONE -> LampStatus.DONE;
			case FAILED -> LampStatus.ERROR;
			default -> LampStatus.IDLE;
		};
	}

	/** Server thread: set every bound station block in every HQ / building region to its wanted state. */
	static int apply(ServerLevel level, Wanted w, List<Area> areas) {
		List<BlockPos> pos = new ArrayList<>();
		List<BlockState> to = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		for (Area area : areas) {
			scan(level, w, area, seen, pos, to);
		}
		for (Area area : areas) {
			signals(level, area.podiumSignals(), w.podiumOpen(area), pos, to);
			signals(level, area.mergeSignals(), w.mergeActive(), pos, to);
		}
		for (int i = 0; i < pos.size(); i++) {
			level.setBlock(pos.get(i), to.get(i), Block.UPDATE_CLIENTS);
		}
		return pos.size();
	}

	/** The station block entities of one region (bounds + {@link #MARGIN}) whose state differs from the wanted one. */
	private static void scan(ServerLevel level, Wanted w, Area area, Set<BlockPos> seen, List<BlockPos> pos, List<BlockState> to) {
		Anchors.Bounds b = area.b();
		int x0 = (b.minX() - MARGIN) >> 4;
		int x1 = (b.maxX() + MARGIN) >> 4;
		int z0 = (b.minZ() - MARGIN) >> 4;
		int z1 = (b.maxZ() + MARGIN) >> 4;
		for (int cx = x0; cx <= x1; cx++) {
			for (int cz = z0; cz <= z1; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos p = be.getBlockPos();
					if (p.getX() < b.minX() - MARGIN || p.getX() > b.maxX() + MARGIN || p.getZ() < b.minZ() - MARGIN || p.getZ() > b.maxZ() + MARGIN) {
						continue;
					}
					if (!seen.add(p)) {
						continue; // regions closer than the margin: the first one decides
					}
					BlockState s = be.getBlockState();
					BlockState want = wantedState(be, s, w, area);
					if (want != null && want != s) {
						pos.add(p);
						to.add(want);
					}
				}
			}
		}
	}

	/** Copper bulbs around the given station anchors follow {@code on} (no redstone involved). */
	private static void signals(ServerLevel level, List<BlockPos> centers, boolean on, List<BlockPos> pos, List<BlockState> to) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (BlockPos c : centers) {
			for (int dx = -SIGNAL_REACH; dx <= SIGNAL_REACH; dx++) {
				for (int dz = -SIGNAL_REACH; dz <= SIGNAL_REACH; dz++) {
					for (int dy = -1; dy <= 6; dy++) {
						m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
						if (!level.isLoaded(m)) {
							continue;
						}
						BlockState s = level.getBlockState(m);
						if (s.getBlock() instanceof CopperBulbBlock && s.getValue(CopperBulbBlock.LIT) != on) {
							BlockPos p = m.immutable();
							if (!pos.contains(p)) {
								pos.add(p);
								to.add(s.setValue(CopperBulbBlock.LIT, on));
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Whether the podium / {@code decisions} lamp at {@code p} opens: judged by the building whose box holds
	 * it exactly (as the podium's bubble does), so two buildings closer than the scan margin cannot disagree;
	 * outside every box, by the scanned area.
	 */
	private static boolean podiumOpenAt(Wanted w, BlockPos p, Area area) {
		Routing.Site site = Routing.siteAt(Buildings.sites(), area.dimension(), p.getX(), p.getY(), p.getZ(), 0);
		if (site == null) {
			return w.podiumOpen(area);
		}
		return w.podiumOpen(new Area(site.box(), true, site.buildingId(), site.home(), List.of(), List.of(), site.dimension()));
	}

	private static @Nullable BlockState wantedState(BlockEntity be, BlockState s, Wanted w, Area area) {
		if (be instanceof StatusLampBlockEntity lamp && s.getBlock() instanceof StatusLampBlock) {
			// in a building, ci:#n is a wing that got no repo at placement (only the studio numbers its repos)
			LampStatus want = area.building() && Routing.isCiPlaceholder(lamp.binding()) ? null : w.lamps().get(lamp.binding());
			if (lamp.binding().equals("decisions")) {
				// like the podium: this building's lead's decisions (home: marlow's and everyone else's)
				want = podiumOpenAt(w, be.getBlockPos(), area) ? LampStatus.WAITING : LampStatus.OFF;
			}
			if (want == null) {
				// bound to something the Foreman does not have: an agent that left goes dark, an unused CI
				// slot (no second repo yet) shows idle grey rather than a dead lamp
				want = lamp.binding().startsWith("ci:") ? LampStatus.IDLE : lamp.binding().startsWith("agent:") ? LampStatus.OFF : null;
			}
			return want == null ? null : s.setValue(StatusLampBlock.STATUS, want);
		}
		if (be instanceof DecisionPodiumBlockEntity && s.getBlock() instanceof DecisionPodiumBlock) {
			return s.setValue(DecisionPodiumBlock.OPEN, podiumOpenAt(w, be.getBlockPos(), area));
		}
		if (be instanceof MergeStationBlockEntity && s.getBlock() instanceof MergeStationBlock) {
			return s.setValue(MergeStationBlock.ACTIVE, w.mergeActive());
		}
		if (be instanceof MonitorBlockEntity mon && s.getBlock() instanceof MonitorBlock && !mon.binding().isEmpty()) {
			Boolean lit = w.monitorLit().get(mon.binding());
			return s.setValue(MonitorBlock.LIT, lit != null && lit);
		}
		return null;
	}
}
