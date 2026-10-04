package dev.agentcraft.client.leads;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.LeadRouting;
import dev.agentcraft.client.agents.AgentManager;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.LinkStatus;
import dev.agentcraft.client.foreman.Protocol;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Tells the Foreman which buildings this world has, so each gets a lead (docs/PRWATCH.md "A lead per
 * building"). The one place that does it: a {@link Buildings} change listener (placing through the
 * wizard, the hub, {@code /agentcraft place} or the design's "Place on the plot" all end in
 * {@code Buildings.place}; the hub's remove and {@code /agentcraft remove [forget]} in {@code forget}):
 * <ul>
 *   <li>the home building is always Marlow's: it is never sent ({@link LeadRouting#leadBuildings}); making
 *       another building home (hub, {@code /agentcraft home}) sends a {@code lead.sync}, so the old home
 *       gets a lead and the new home's lead is released;</li>
 *   <li>a world was loaded (the world id changed): {@code lead.sync {world, buildings}};</li>
 *   <li>a building appeared: {@code lead.assign {building, repos}}; one disappeared: {@code lead.sync} (releases it, then the freed lead takes a building that overflowed to marlow);</li>
 *   <li>the link (re)connected: {@code lead.sync} (the reconciler: a placement while offline is caught up here);</li>
 *   <li>the world stopped: forgotten, nothing sent (the Foreman keeps its leads for the next load).</li>
 * </ul>
 * Building keys are {@code "<worldId>/<buildingId>"} ({@link LeadRouting#key}). Nothing is sent without
 * a singleplayer world. Acks are best effort: a refusal (an older Foreman) is logged once per type.
 */
public final class LeadsFeature {
	private static final int SENT_TAIL = 20;
	/** The world whose buildings {@link #known} holds (null = no world). Client thread. */
	private static @Nullable String world;
	/** This world's buildings that get a lead (all but the home one): building id -> repos. */
	private static Map<String, List<String>> known = Map.of();
	/** The home building of {@link #world} (never sent), null when it has none. */
	private static @Nullable String home;
	private static boolean wasSynced;
	/** This world's buildings file could not be read: nothing is sent for it (see {@link #onBuildings}). */
	private static boolean blocked;
	private static final Deque<JsonObject> SENT = new ArrayDeque<>();
	private static final Set<String> LOGGED_REFUSALS = new HashSet<>();

	private LeadsFeature() {
	}

	public static void init() {
		Buildings.addListener(list -> {
			// on the thread that changed them (integrated server): capture the world with the list, then hand over
			String w = Buildings.worldId();
			boolean unreadable = Buildings.loadFailed();
			Map<String, List<String>> now = repos(list);
			String h = list.stream().filter(Building::home).map(Building::id).findFirst().orElse(null);
			Minecraft.getInstance().execute(() -> onBuildings(w, unreadable, LeadRouting.leadBuildings(now, h), h));
		});
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onConnection(LinkStatus status) {
				boolean synced = status.synced();
				if (synced && !wasSynced) {
					sync("connect");
				}
				wasSynced = synced;
			}
		});
		registerDev();
	}

	private static Map<String, List<String>> repos(List<Building> list) {
		Map<String, List<String>> m = new LinkedHashMap<>();
		for (Building b : list) {
			m.put(b.id(), List.copyOf(b.repos()));
		}
		return m;
	}

	/** Client thread: the world's buildings changed (or a world started / stopped). */
	static void onBuildings(@Nullable String w, boolean unreadable, Map<String, List<String>> now, @Nullable String homeId) {
		if (w == null) {
			world = null;
			known = Map.of();
			home = null;
			blocked = false;
			return;
		}
		if (!w.equals(world)) {
			world = w;
			known = now;
			home = homeId;
			// an unreadable buildings file loads as "no buildings": syncing that would release every lead of the world
			blocked = unreadable;
			sync("world");
			return;
		}
		if (blocked) {
			return;
		}
		if (!java.util.Objects.equals(home, homeId)) {
			// a home change: one sync (releases the new home's lead, gives the old home a lead), not assign + sync
			String was = home;
			home = homeId;
			known = now;
			sync("home " + was + " -> " + homeId);
			return;
		}
		LeadRouting.Diff d = LeadRouting.diff(known, now);
		known = now;
		for (String id : d.assign()) {
			JsonObject p = new JsonObject();
			p.addProperty("building", LeadRouting.key(w, id));
			p.add("repos", array(now.get(id)));
			send("lead.assign", p, id);
		}
		// a removal syncs instead of a bare lead.release: the sync releases the removed building first and
		// then assigns, so the freed lead goes to a building that overflowed to marlow (the Foreman keeps no
		// record of overflowed buildings)
		if (!d.release().isEmpty()) {
			sync("removed " + String.join(",", d.release()));
		}
	}

	/** {@code lead.sync} for this world's buildings, when there is a world and a live link. Returns what happened. */
	static String sync(String why) {
		if (world == null) {
			return "no singleplayer world";
		}
		if (blocked) {
			return "not syncing: " + Buildings.FILE + " could not be read (it would release every lead of this world)";
		}
		if (!Foreman.connected()) {
			return "Foreman not connected (synced on connect)";
		}
		JsonObject p = new JsonObject();
		p.addProperty("world", world);
		JsonArray bs = new JsonArray();
		for (var e : known.entrySet()) {
			JsonObject b = new JsonObject();
			b.addProperty("building", LeadRouting.key(world, e.getKey()));
			b.add("repos", array(e.getValue()));
			bs.add(b);
		}
		p.add("buildings", bs);
		send("lead.sync", p, why);
		return "sent";
	}

	private static JsonArray array(@Nullable List<String> list) {
		JsonArray a = new JsonArray();
		if (list != null) {
			list.forEach(a::add);
		}
		return a;
	}

	private static void send(String type, JsonObject payload, String note) {
		JsonObject rec = new JsonObject();
		rec.addProperty("type", type);
		rec.addProperty("note", note);
		rec.addProperty("at", System.currentTimeMillis());
		rec.add("payload", payload.deepCopy());
		SENT.addLast(rec);
		while (SENT.size() > SENT_TAIL) {
			SENT.removeFirst();
		}
		if (!Foreman.connected()) {
			rec.addProperty("ok", false);
			rec.addProperty("error", "not connected (the next connect syncs)");
			return;
		}
		Foreman.send(type, payload).whenComplete((ack, err) -> {
			if (err != null) {
				rec.addProperty("ok", false);
				rec.addProperty("error", DevBridge.describeError(err));
				return;
			}
			rec.addProperty("ok", ack.ok());
			if (ack.error() != null) {
				rec.addProperty("error", ack.error());
			}
			if (ack.result() != null) {
				rec.add("result", ack.result());
			}
			if (!ack.ok() && LOGGED_REFUSALS.add(type)) {
				AgentCraft.LOGGER.info("Foreman refused {} ({}); leads per building need a newer Foreman", type, ack.error());
			}
		});
	}

	// ------------------------------------------------------------------ dev

	private static void registerDev() {
		DevBridge.register("dev.leads.state", 10_000,
			"{} -> world id, whether the Foreman publishes leads, its assignments, each building's key + lead, each lead's routed building/target"
				+ " (departing = released, walking home to despawn), each podium's building, and the last lead.* messages sent with their acks",
			(req, mc) -> DevBridge.onClient(mc, LeadsFeature::stateJson));
		DevBridge.register("dev.leads.sync", 10_000, "{} - send lead.sync for this world now (also sent on connect and world load)",
			(req, mc) -> DevBridge.onClient(mc, () -> {
				JsonObject o = new JsonObject();
				o.addProperty("result", sync("dev"));
				o.add("state", stateJson());
				return o;
			}));
		DevBridge.addStateContributor((mc, o) -> o.add("leads", summaryJson()));
	}

	/** Compact (dev.state): world, known, assignments here. Client thread. */
	static JsonObject summaryJson() {
		Leads.View v = Leads.view();
		JsonObject o = new JsonObject();
		o.addProperty("worldId", v.worldId());
		o.addProperty("known", v.known());
		JsonObject here = new JsonObject();
		v.assignedHere().forEach(here::addProperty);
		o.add("assignedHere", here);
		return o;
	}

	/** Full dev.leads.state. Client thread. */
	public static JsonObject stateJson() {
		Leads.View v = Leads.view();
		ForemanState st = Foreman.state();
		JsonObject o = summaryJson();
		o.addProperty("syncedWorld", world);
		o.addProperty("blocked", blocked);
		o.addProperty("homeBuilding", v.homeBuilding());
		o.addProperty("syncedHome", home);
		JsonArray raw = new JsonArray();
		for (LeadRouting.Lead l : v.leads()) {
			JsonObject j = new JsonObject();
			j.addProperty("leadId", l.leadId());
			j.addProperty("building", l.building());
			j.add("repos", array(l.repos()));
			raw.add(j);
		}
		o.add("assignments", raw);
		JsonArray bs = new JsonArray();
		for (Building b : Buildings.buildings()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", b.id());
			j.addProperty("key", v.worldId() == null ? null : LeadRouting.key(v.worldId(), b.id()));
			j.add("repos", array(b.repos()));
			j.addProperty("home", b.home());
			String lead = v.leadOf(b.id());
			j.addProperty("lead", lead);
			j.addProperty("leadLabel", leadLabel(b));
			j.addProperty("hasPodium", b.anchors().containsKey(dev.agentcraft.layout.AnchorNames.DECISION_PODIUM));
			bs.add(j);
		}
		o.add("buildings", bs);
		JsonObject owners = new JsonObject();
		v.podiumOwners().forEach(owners::addProperty);
		o.add("podiumOwners", owners);
		JsonArray agents = new JsonArray();
		if (st != null) {
			for (Protocol.Agent a : st.agents().values()) {
				if (a.role() == Protocol.AgentRole.LEAD) {
					agents.add(AgentManager.get().leadDebug(a.id()));
				}
			}
		}
		for (String id : AgentManager.get().departingIds()) {
			if (st == null || st.agent(id) == null || st.agent(id).role() != Protocol.AgentRole.LEAD) {
				agents.add(AgentManager.get().leadDebug(id));
			}
		}
		o.add("leads", agents);
		JsonArray sent = new JsonArray();
		SENT.forEach(s -> sent.add(s.deepCopy()));
		o.add("sent", sent);
		return o;
	}

	/**
	 * What the hub shows as a building's lead: the lead's name, "Marlow (home)" when Marlow leads it (or the
	 * Foreman does not publish leads), "no lead: Foreman offline" while the link is down. Client thread.
	 */
	public static String leadLabel(Building b) {
		if (!Foreman.connected()) {
			return "no lead: Foreman offline";
		}
		String lead = Leads.view().leadOf(b.id());
		return lead != null ? Leads.name(lead) : Leads.name(LeadRouting.MARLOW) + " (home)";
	}
}
