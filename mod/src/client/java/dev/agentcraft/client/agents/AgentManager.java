package dev.agentcraft.client.agents;

import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.LeadRouting;
import dev.agentcraft.building.Routing;
import dev.agentcraft.client.leads.Leads;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentRole;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps one {@link ClientAgentEntity} per Foreman agent in the client level, in sync with the
 * state model and the anchor layout (client thread, every client tick):
 * <ul>
 *   <li>new agent: spawned standing at its target anchor (no walk-in from nowhere);</li>
 *   <li>station/active change: walks there along a {@link GridPathfinder} route (teleports if
 *       there is no route, e.g. the HQ was rebuilt around it);</li>
 *   <li>agent gone after a snapshot: removed;</li>
 *   <li>Foreman link down: agents stay where they are with a dimmed "Foreman offline" plate;</li>
 *   <li>layout republished ({@code /agentcraft hq}): everyone is placed at their new anchors.</li>
 * </ul>
 * Without a layout, agents stand in a row near the world spawn so they are still visible.
 *
 * <p>Buildings (docs/BUILDINGS.md "Client (routing)"): every agent works in the building of its repo
 * ({@link Routing#agentRepo}: its repo, its task's repo, the lead's goal repo), else in
 * {@link Anchors#current()} (the home building, or the HQ studio). Stations, desks, seats and the
 * pathfinder are per layout (keyed by layout name); an agent whose building changes teleports there
 * with a puff of smoke at both ends. In a world without buildings everyone uses {@code Anchors.current()}
 * exactly as before.
 *
 * <p>Phase 3: a station anchor with a seat block ({@link Seats}) is walked to via a free cell next
 * to the seat, then the agent steps in and sits; leaving a seat starts with standing up. An agent
 * that is {@code waiting_user} walks to the user spot by the podium, or, when you are inside the
 * HQ (not spectating), to a free spot about two blocks from you and waits there facing you
 * ({@link #userSpot}). The derived "waiting on you" status ({@link AgentView#awaitingUser}) comes
 * from the open decisions.
 */
public final class AgentManager {
	private static final AgentManager INSTANCE = new AgentManager();
	/** Teleport instead of walking when the route is longer than this (blocks). */
	private static final double MAX_WALK = 96;
	/** Ticks it takes to get up from a seat before walking off. */
	private static final int STAND_UP_TICKS = 8;
	/** A waiting agent re-approaches you once you moved this far from where it chose its spot (blocks). */
	private static final double FOLLOW_SLACK = 2.6;
	/** How far from you a waiting agent stands (blocks). */
	static final double USER_DISTANCE = 3.2;

	private final Map<String, ClientAgentEntity> entities = new LinkedHashMap<>();
	private final Map<Integer, ClientAgentEntity> byEntityId = new HashMap<>();
	/** One assigner per layout name (desks are {@code desk_<id>} in every building; slots are per building). */
	private final Map<String, StationAssigner> assigners = new HashMap<>();
	/** agentId -> the layout name it was last placed in (a change = it moves to another building). */
	private final Map<String, String> agentLayouts = new HashMap<>();
	/** layout name -> the revision last seen (a change = that layout was republished: its agents snap). */
	private final Map<String, Long> layoutRevisions = new HashMap<>();
	private final Seats seats = new Seats();
	private final Map<String, UserSpot> userSpots = new HashMap<>();
	private final Map<String, String> awaiting = new HashMap<>();
	private final Map<String, Integer> awaitingCounts = new HashMap<>();
	private long awaitingRevision = -1;
	private @Nullable ClientLevel level;
	private long regionsSignature = Long.MIN_VALUE;
	/** Released leads walking out: agent id -> the tick by which they are removed even if still walking. */
	private final Map<String, Long> departing = new HashMap<>();
	/** Ids seen as building leads (role lead, not marlow): only they walk out when they leave the view. */
	private final Set<String> leadIds = new HashSet<>();
	/** The level was populated once (a lead appearing after that was newly assigned: it walks in). */
	private boolean populated;
	/** Longest a released lead takes to walk out (ticks). */
	private static final int DEPART_TICKS = 30 * 20;
	private int nextEntityId = -10_000;
	private int pathFailures;
	private long ticks;

	/** Where a waiting agent stands near the player, and where the player was when it was chosen. */
	private record UserSpot(Anchor spot, Vec3 playerAt) {
	}

	private AgentManager() {
	}

	public static AgentManager get() {
		return INSTANCE;
	}

	/** Live agent entities by agent id (client thread). */
	public Map<String, ClientAgentEntity> entities() {
		return Collections.unmodifiableMap(entities);
	}

	public @Nullable ClientAgentEntity entity(String agentId) {
		return entities.get(agentId);
	}

	public @Nullable ClientAgentEntity byEntityId(int id) {
		return byEntityId.get(id);
	}

	public int pathFailures() {
		return pathFailures;
	}

	/**
	 * A snapshot rebuilds the view: forget sticky slots so the assignment depends only on the state
	 * (Foreman order), not on the history of this session. Agents that change slot walk there.
	 */
	void onSnapshot() {
		assigners.values().forEach(StationAssigner::clear);
		awaitingRevision = -1;
	}

	public int movingCount() {
		int n = 0;
		for (ClientAgentEntity e : entities.values()) {
			if (e.motion().walking()) {
				n++;
			}
		}
		return n;
	}

	void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != level) {
			entities.clear(); // the old level and its entities are gone
			byEntityId.clear();
			assigners.clear();
			agentLayouts.clear();
			layoutRevisions.clear();
			seats.clear();
			userSpots.clear();
			departing.clear();
			leadIds.clear();
			populated = false;
			level = lvl;
			regionsSignature = Long.MIN_VALUE;
		}
		if (lvl == null) {
			return;
		}
		ticks++;
		ForemanState st = Foreman.state();
		if (st == null || !st.hasData()) {
			removeAll();
			return;
		}
		// only the buildings of the player's dimension: agents route and spawn there; home in another dimension = hidden
		String dim = lvl.dimension().identifier().toString();
		Anchors.Layout current = Buildings.currentIn(dim);
		boolean homeElsewhere = current.isEmpty() && !Buildings.all().isEmpty();
		long sig = Buildings.regionsSignature() * 31 + current.revision();
		if (sig != regionsSignature) {
			regionsSignature = sig;
			seats.clear();
			userSpots.clear();
		}
		Leads.View leads = Leads.view();
		List<Agent> agents = new ArrayList<>();
		for (Agent a : st.agents().values()) {
			if (isBuildingLead(a)) {
				leadIds.add(a.id());
				if (leads.known() && !LeadRouting.present(a.id(), leads.buildingOf(a.id()))) {
					continue; // released, or leads a building of another world: not here (walks home and leaves if shown)
				}
			}
			agents.add(a);
		}
		// route: agent -> its building's layout; group by layout name (Foreman order kept within a group)
		List<Routing.Site> sites = Routing.sitesIn(Buildings.sites(), dim);
		Map<String, Anchors.Layout> layouts = new LinkedHashMap<>();
		Map<String, List<Agent>> groups = new LinkedHashMap<>();
		for (Agent a : agents) {
			Anchors.Layout l;
			if (leads.known() && a.role() == AgentRole.LEAD) {
				// a lead works in the building it leads (marlow and unassigned: home), not in its goal's repo
				l = Routing.layoutForBuilding(leads.buildingOf(a.id()), sites, current);
			} else {
				l = sites.isEmpty() ? current : Routing.layoutFor(repoOf(st, a), sites, current);
			}
			if (l != current && !Routing.canHost(l, StationAssigner.stationKey(a), a.id())) {
				l = current; // the building has no place for it (no desk, station or lounge): home
			}
			if (l.isEmpty() && homeElsewhere) {
				continue; // its building and home are in another dimension: not shown here
			}
			layouts.putIfAbsent(l.name(), l);
			groups.computeIfAbsent(l.name(), k -> new ArrayList<>()).add(a);
		}
		Set<String> relayout = new HashSet<>();
		for (Anchors.Layout l : layouts.values()) {
			Long seen = layoutRevisions.put(l.name(), l.revision());
			if (seen == null || seen != l.revision()) {
				relayout.add(l.name());
			}
		}
		Map<String, Anchor> targets = new HashMap<>();
		Map<String, GridPathfinder> pfs = new HashMap<>();
		for (var g : groups.entrySet()) {
			Anchors.Layout l = layouts.get(g.getKey());
			if (l.isEmpty()) {
				targets.putAll(fallbackTargets(g.getValue(), lvl));
			} else {
				targets.putAll(assigners.computeIfAbsent(l.name(), k -> new StationAssigner()).assign(g.getValue(), l));
				pfs.put(l.name(), new GridPathfinder(lvl, l.bounds()));
			}
		}
		assigners.keySet().retainAll(groups.keySet());
		layoutRevisions.keySet().retainAll(groups.keySet());
		boolean stale = st.isStale();
		updateAwaiting(st);
		// the player is in at most one building: its waiting agents come to you, the others use their user spot
		Vec3 playerFeet = null;
		String playerLayout = null;
		for (Anchors.Layout l : layouts.values()) {
			GridPathfinder lpf = pfs.get(l.name());
			Vec3 feet = lpf == null ? null : playerInHq(mc, l, lpf);
			if (feet != null) {
				playerFeet = feet;
				playerLayout = l.name();
				break;
			}
		}
		int waitingIndex = 0;
		int waitingCount = 0;
		if (playerFeet != null) {
			for (Agent a : groups.get(playerLayout)) {
				if (followsPlayer(a)) {
					waitingCount++;
				}
			}
		}

		Set<String> keep = new HashSet<>();
		for (var g : groups.entrySet()) {
			String layoutName = g.getKey();
			Anchors.Layout layout = layouts.get(layoutName);
			GridPathfinder pf = pfs.get(layoutName);
			boolean snap = relayout.contains(layoutName);
			boolean playerHere = layoutName.equals(playerLayout);
			for (Agent a : g.getValue()) {
				Anchor target = targets.get(a.id());
				if (target == null) {
					continue;
				}
				keep.add(a.id());
				ClientAgentEntity e = entities.get(a.id());
				boolean spawned = false;
				boolean walkIn = false;
				departing.remove(a.id()); // reassigned while walking out: back to work
				boolean lead = a.role() == AgentRole.LEAD;
				if (e == null || e.isRemoved() || e.level() != lvl) {
					e = spawn(lvl, a, target, layout, pf);
					entities.put(a.id(), e);
					byEntityId.put(e.getId(), e);
					showRecentSay(st, e);
					spawned = true;
					// a lead newly assigned to a building comes in through its door (not on the first population)
					walkIn = populated && leads.known() && isBuildingLead(a) && enterAtDoor(e, layout);
				} else if (!e.getSkin().equals(AgentSkins.get(a.id(), a.skin(), lead))) {
					e.setSkin(AgentSkins.get(a.id(), a.skin(), lead));
				}
				String before = agentLayouts.put(a.id(), layoutName);
				boolean moved = !spawned && before != null && !before.equals(layoutName);
				AgentView v = e.view();
				v.update(a, stale, awaiting.get(a.id()), awaitingCounts.getOrDefault(a.id(), 0));
				v.station = StationAssigner.stationKey(a);
				v.anchor = target.name();
				v.layout = layout;
				v.repo = repoOf(st, a);
				if (playerHere && playerFeet != null && pf != null && !stale && followsPlayer(a)) {
					Anchor near = userSpot(a.id(), e, playerFeet, waitingIndex++, waitingCount, pf);
					if (near != null) {
						target = near;
					}
				} else {
					userSpots.remove(a.id());
				}
				Seats.Seat seat = pf == null ? null : seats.at(lvl, layout, target, ticks, pf);
				Anchor effective = seat != null ? seat.target() : target;
				if (moved) {
					// another building: no walk across the world (for now), a puff where it leaves and where it lands
					poof(lvl, e.position());
					e.life().setSeat(seat);
					place(e, effective);
					poof(lvl, effective.pos());
					AgentCraft.LOGGER.info("Agent {} moved from {} to {} ({})", a.id(), before, layoutName, effective.name());
				} else if (snap && !walkIn) {
					e.life().setSeat(seat);
					place(e, effective);
				} else if (!stale) {
					retarget(lvl, pf, e, effective, seat);
				}
			}
		}
		for (var it = entities.entrySet().iterator(); it.hasNext();) {
			var en = it.next();
			String id = en.getKey();
			if (keep.contains(id)) {
				continue;
			}
			ClientAgentEntity e = en.getValue();
			Long deadline = departing.get(id);
			if (deadline == null && !stale && leads.known() && leadIds.contains(id) && startDeparture(lvl, e, current)) {
				departing.put(id, ticks + DEPART_TICKS);
				continue;
			}
			if (deadline != null && ticks < deadline && e.motion().walking()) {
				continue; // still walking out
			}
			if (deadline != null) {
				poof(lvl, e.position());
				AgentCraft.LOGGER.info("Lead {} left (released)", id);
			}
			departing.remove(id);
			remove(lvl, e);
			byEntityId.remove(e.getId());
			agentLayouts.remove(id);
			it.remove();
		}
		populated = true;
	}

	/** A lead that can lead a building (every lead but marlow). */
	private static boolean isBuildingLead(Agent a) {
		return a.role() == AgentRole.LEAD && !LeadRouting.MARLOW.equals(a.id());
	}

	/** Puts a just-spawned agent at its layout's entrance (else its spawn anchor) so it walks in. False when there is neither. */
	private static boolean enterAtDoor(ClientAgentEntity e, Anchors.Layout layout) {
		Anchor door = layout.get(AnchorNames.ENTRANCE);
		if (door == null) {
			door = layout.get(AnchorNames.SPAWN);
		}
		if (door == null) {
			return false;
		}
		e.life().setSeat(null);
		place(e, door);
		return true;
	}

	/**
	 * A released lead walks to the home building's entrance and leaves there (like an off-shift agent,
	 * home is where it goes). From another building it first moves home (a puff at both ends, as any
	 * move between buildings), into the lounge. False when home has no entrance or spawn (removed at once).
	 */
	private boolean startDeparture(ClientLevel lvl, ClientAgentEntity e, Anchors.Layout home) {
		if (home.isEmpty()) {
			return false;
		}
		Anchor exit = home.get(AnchorNames.ENTRANCE);
		if (exit == null) {
			exit = home.get(AnchorNames.SPAWN);
		}
		if (exit == null) {
			return false;
		}
		String before = agentLayouts.get(e.agentId());
		if (before != null && !before.equals(home.name())) {
			poof(lvl, e.position());
			Anchor from = home.get(AnchorNames.LOUNGE) != null ? home.get(AnchorNames.LOUNGE) : exit;
			e.life().setSeat(null);
			place(e, from);
			poof(lvl, from.pos());
		}
		agentLayouts.put(e.agentId(), home.name());
		userSpots.remove(e.agentId());
		retarget(lvl, new GridPathfinder(lvl, home.bounds()), e, exit, null);
		AgentCraft.LOGGER.info("Lead {} released: walking out of {} ({})", e.agentId(), home.name(), exit.name());
		return true;
	}

	/** Agent id -> the name of the layout it is routed to (spawned agents of the player's level). Client thread. */
	public Map<String, String> routedLayouts() {
		return Map.copyOf(agentLayouts);
	}

	/** Agents walking out to despawn (released leads). Client thread. */
	public Set<String> departingIds() {
		return Set.copyOf(departing.keySet());
	}

	/** dev.leads.state: where a lead is routed, its target and whether it is leaving. Client thread. */
	public JsonObject leadDebug(String agentId) {
		JsonObject o = new JsonObject();
		o.addProperty("id", agentId);
		Leads.View v = Leads.view();
		String b = v.buildingOf(agentId);
		o.addProperty("assignedBuilding", b);
		o.addProperty("routedLayout", agentLayouts.get(agentId));
		ClientAgentEntity e = entities.get(agentId);
		o.addProperty("spawned", e != null);
		Anchor t = e == null ? null : e.motion().target();
		o.addProperty("target", t == null ? null : t.name());
		o.addProperty("walking", e != null && e.motion().walking());
		o.addProperty("departing", departing.containsKey(agentId));
		if (e != null) {
			o.addProperty("pos", String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", e.getX(), e.getY(), e.getZ()));
		}
		return o;
	}

	/** The repo whose building an agent works in ({@link Routing#agentRepo}), or null = home. */
	static @Nullable String repoOf(ForemanState st, Agent a) {
		Protocol.Task task = a.taskId() == null ? null : st.task(a.taskId());
		Protocol.Goal goal = st.goal();
		return Routing.agentRepo(a.repoId(), task == null ? null : task.repoId(), a.role() == AgentRole.LEAD, goal == null ? null : goal.repoId(),
			a.isActive());
	}

	/** A small puff of smoke (vanilla poof) where an agent leaves or arrives by teleport. */
	private static void poof(ClientLevel lvl, Vec3 at) {
		for (int i = 0; i < 8; i++) {
			double dx = (i % 3 - 1) * 0.18;
			double dz = (i / 3 % 3 - 1) * 0.18;
			lvl.addParticle(ParticleTypes.POOF, at.x + dx, at.y + 0.2 + (i % 4) * 0.35, at.z + dz, dx * 0.1, 0.02, dz * 0.1);
		}
	}

	private static boolean followsPlayer(Agent a) {
		return a.state() == AgentState.WAITING_USER && a.isActive() && !a.isPaused();
	}

	/**
	 * agentId -> the first open decision that agent <b>owns</b>. Every open decision has exactly one
	 * owner, so the HQ shows one "!" per decision waiting on the user:
	 * <ul>
	 *   <li>a merge belongs to the worker whose task it merges (the lead files it, but it is the
	 *       worker's finished work that waits; "t4 awaiting your merge"), or to the agent that filed
	 *       it when the task has no known assignee;</li>
	 *   <li>a question or permission prompt belongs to the agent that asked, whatever task it is
	 *       about (a question about Juniper's task is Marlow's question, not Juniper's).</li>
	 * </ul>
	 * An agent's own questions/permissions come before the merges it owns.
	 */
	private void updateAwaiting(ForemanState st) {
		if (st.revision() == awaitingRevision) {
			return;
		}
		awaitingRevision = st.revision();
		awaiting.clear();
		awaitingCounts.clear();
		List<Protocol.Decision> open = st.openDecisions();
		for (int pass = 0; pass < 2; pass++) {
			for (Protocol.Decision d : open) {
				boolean merge = d.kind() == Protocol.DecisionKind.MERGE;
				if (merge != (pass == 1)) {
					continue;
				}
				String owner = owner(st, d);
				awaiting.putIfAbsent(owner, d.id());
				awaitingCounts.merge(owner, 1, Integer::sum);
			}
		}
	}

	/** The agent an open decision belongs to (see {@link #updateAwaiting}). */
	public static String owner(ForemanState st, Protocol.Decision d) {
		if (d.kind() == Protocol.DecisionKind.MERGE && d.taskId() != null) {
			Protocol.Task t = st.task(d.taskId());
			if (t != null && t.assignee() != null && st.agent(t.assignee()) != null) {
				return t.assignee();
			}
		}
		return d.agentId();
	}

	/** The player's feet on the HQ floor when they are inside the HQ and not spectating, else null. */
	private static @Nullable Vec3 playerInHq(Minecraft mc, Anchors.Layout layout, @Nullable GridPathfinder pf) {
		LocalPlayer p = mc.player;
		Anchors.Bounds b = layout.bounds();
		if (p == null || pf == null || b == null || p.isSpectator()) {
			return null;
		}
		// feet a hair below a block top (64.99999) belong to the block above
		BlockPos bp = BlockPos.containing(p.getX(), p.getY() + 0.05, p.getZ());
		if (!b.contains(bp.getX(), bp.getY(), bp.getZ()) && !b.contains(bp.getX(), bp.getY() - 2, bp.getZ())) {
			return null;
		}
		for (int dy = 0; dy <= 3; dy++) {
			double f = pf.floor(bp.getX(), bp.getY() - dy, bp.getZ());
			if (!Double.isNaN(f)) {
				return new Vec3(p.getX(), f, p.getZ());
			}
		}
		return null;
	}

	/**
	 * A free walkable spot about three blocks from the player, on the agent's side (several waiting
	 * agents fan out), facing the player. Three blocks is a conversation distance: the agent, its
	 * plate and its "!" fit on screen at eye level (at two blocks the plate filled the upper middle
	 * of the view and the "!" was cut off). Sticky until the player moves {@value #FOLLOW_SLACK}
	 * blocks away from where they were when it was chosen.
	 */
	private @Nullable Anchor userSpot(String agentId, ClientAgentEntity e, Vec3 player, int index, int count, GridPathfinder pf) {
		UserSpot prev = userSpots.get(agentId);
		if (prev != null && prev.playerAt().distanceTo(player) < FOLLOW_SLACK) {
			return prev.spot();
		}
		double base = Math.atan2(e.getZ() - player.z, e.getX() - player.x);
		if (e.position().distanceToSqr(player) < 0.25) {
			base = 0;
		}
		double spread = Math.toRadians(42);
		double fan = (index - (count - 1) / 2.0) * spread;
		double[] radii = {USER_DISTANCE, USER_DISTANCE + 0.5, USER_DISTANCE - 0.6};
		double[] offs = {0, 0.45, -0.45, 0.9, -0.9, 1.4, -1.4, 2.0, -2.0, Math.PI};
		for (double r : radii) {
			for (double o : offs) {
				double ang = base + fan + o;
				double x = player.x + Math.cos(ang) * r;
				double z = player.z + Math.sin(ang) * r;
				int bx = (int) Math.floor(x);
				int bz = (int) Math.floor(z);
				int by = (int) Math.floor(player.y + 0.01);
				for (int dy : new int[] {0, 1, -1}) {
					double f = pf.floor(bx, by + dy, bz);
					if (Double.isNaN(f)) {
						continue;
					}
					Vec3 at = new Vec3(x, f, z);
					if (!pf.clear(at, at) || taken(agentId, at)) {
						continue;
					}
					float yaw = (float) Math.toDegrees(Math.atan2(-(player.x - x), player.z - z));
					Anchor spot = new Anchor(AnchorNames.USER + "@player", x, f, z, yaw, 0);
					userSpots.put(agentId, new UserSpot(spot, player));
					return spot;
				}
			}
		}
		return null;
	}

	private boolean taken(String agentId, Vec3 at) {
		for (var en : userSpots.entrySet()) {
			if (!en.getKey().equals(agentId) && en.getValue().spot().pos().distanceToSqr(at) < 1.2 * 1.2) {
				return true;
			}
		}
		for (ClientAgentEntity o : entities.values()) {
			if (!o.agentId().equals(agentId) && !o.motion().walking() && o.position().distanceToSqr(at) < 0.9 * 0.9) {
				return true;
			}
		}
		return false;
	}

	/** A fresh agent shows what it said in the last few seconds (e.g. after a reconnect). */
	private static void showRecentSay(ForemanState st, ClientAgentEntity e) {
		Protocol.AgentSay say = st.lastSay(e.agentId());
		if (say == null) {
			return;
		}
		long ago = System.currentTimeMillis() - say.ts();
		if (ago >= 0 && ago < 8000) {
			e.life().bubble.showLate(say, e.life().age(), (int) (ago / 50));
		}
	}

	private ClientAgentEntity spawn(ClientLevel lvl, Agent a, Anchor target, Anchors.Layout layout, @Nullable GridPathfinder pf) {
		ClientAgentEntity e = new ClientAgentEntity(lvl, a.id(), AgentSkins.get(a.id(), a.skin(), a.role() == AgentRole.LEAD));
		// Negative ids never collide with server-assigned entity ids.
		e.setId(nextEntityId--);
		Seats.Seat seat = seats.at(lvl, layout, target, ticks, pf != null ? pf : new GridPathfinder(lvl, layout.bounds()));
		e.life().setSeat(seat);
		place(e, seat != null ? seat.target() : target);
		lvl.addEntity(e);
		AgentCraft.LOGGER.info("Agent {} appeared at {}", a.id(), target.name());
		return e;
	}

	private static void place(ClientAgentEntity e, Anchor target) {
		Vec3 p = e.motion().placeAt(target);
		e.snapTo(p, target.yaw());
	}

	private void retarget(ClientLevel lvl, @Nullable GridPathfinder layoutPf, ClientAgentEntity e, Anchor target, Seats.@Nullable Seat seat) {
		Anchor current = e.motion().target();
		if (current != null && current.name().equals(target.name()) && current.pos().distanceToSqr(target.pos()) < 1e-4) {
			return;
		}
		// no layout (agents in a row near the spawn): an unbounded search, as before buildings
		GridPathfinder pf = layoutPf != null ? layoutPf : new GridPathfinder(lvl, null);
		AgentLife life = e.life();
		List<Vec3> route = new ArrayList<>();
		Vec3 start = e.position();
		int delay = 0;
		Seats.Seat from = life.seat();
		if (from != null && life.sitAmount() > 0f && !e.motion().walking()) {
			// get up first, then step out of the seat to its free side
			delay = STAND_UP_TICKS;
			if (from.approach() != null) {
				route.add(start);
				start = from.approach();
			}
		}
		Vec3 dest = seat != null && seat.approach() != null ? seat.approach() : target.pos();
		List<Vec3> path = start.distanceToSqr(dest) < 1e-6 ? List.of(start, dest) : pf.find(start, dest);
		if (path == null || length(path) > MAX_WALK) {
			pathFailures++;
			AgentCraft.LOGGER.info("Agent {}: no walkable route to {} ({}), teleporting", e.agentId(), target.name(),
				path == null ? "no path" : "too far");
			life.setSeat(seat);
			place(e, target);
			return;
		}
		route.addAll(path);
		if (seat != null && seat.approach() != null) {
			route.add(target.pos()); // the last step: onto the seat
		}
		life.setSeat(seat);
		e.motion().walkTo(target, route, delay);
	}

	private static double length(List<Vec3> route) {
		double d = 0;
		for (int i = 1; i < route.size(); i++) {
			d += route.get(i).distanceTo(route.get(i - 1));
		}
		return d;
	}

	/** Snap every agent to its target now (QA: no one mid-walk in a screenshot). */
	public int settle() {
		int n = 0;
		for (ClientAgentEntity e : entities.values()) {
			Anchor t = e.motion().target();
			if (t != null && e.motion().walking()) {
				place(e, t);
				n++;
			}
		}
		return n;
	}

	private void removeAll() {
		if (level != null) {
			for (ClientAgentEntity e : entities.values()) {
				remove(level, e);
			}
		}
		entities.clear();
		byEntityId.clear();
		departing.clear();
		populated = false;
	}

	private static void remove(ClientLevel lvl, ClientAgentEntity e) {
		lvl.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
	}

	/** No layout yet: a row in front of the world spawn, facing it. */
	private static Map<String, Anchor> fallbackTargets(List<Agent> agents, ClientLevel lvl) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		BlockPos spawn = lvl.getRespawnData().pos();
		int n = agents.size();
		for (int i = 0; i < n; i++) {
			double x = spawn.getX() + 0.5 + (i - (n - 1) / 2.0) * 1.4;
			out.put(agents.get(i).id(), new Anchor(AnchorNames.LOUNGE + "@spawn" + i, x, spawn.getY(), spawn.getZ() + 4.5, 180, 0));
		}
		return out;
	}
}
