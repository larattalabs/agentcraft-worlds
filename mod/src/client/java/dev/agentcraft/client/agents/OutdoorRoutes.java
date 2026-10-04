package dev.agentcraft.client.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.Routing;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.walk.OutdoorPlanner;
import dev.agentcraft.walk.OutdoorPlanner.Point;
import dev.agentcraft.walk.RouteCache;
import dev.agentcraft.walk.WalkRules;
import dev.agentcraft.walk.WalkRules.Reason;
import dev.agentcraft.walk.WalkSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Outdoor routes between buildings (docs/WAVE2.md W8), client thread: the planner queue (one
 * {@link OutdoorPlanner} job per building pair, stepped with a node and time budget every client tick, so a
 * long search spreads over ticks and never blocks a frame), the {@link RouteCache} (invalidated by block
 * changes near a route, a building change or another level; checked again before reuse), the per-world
 * "Agents walk between buildings" setting ({@link WalkSettings}, {@code walking.json}), the walk-or-teleport
 * decision ({@link WalkRules}) and the stats behind {@code dev.walk.state}. {@link AgentManager} owns the
 * trips (who walks where).
 */
public final class OutdoorRoutes {
	private static final OutdoorRoutes INSTANCE = new OutdoorRoutes();
	/** Planner work per client tick: expansions, and wall time (whichever runs out first). */
	static final int TICK_NODES = 2500;
	static final long TICK_NANOS = 2_000_000L;
	/** Recent teleports/walks kept for dev.walk.state. */
	private static final int RECENT = 12;

	/** A finished request: the route (FOUND) or why there is none. */
	public record Outcome(String key, OutdoorPlanner.Status status, RouteCache.@Nullable Route route, boolean cached, int nodes, long micros,
		int ticks) {
	}

	private static final class Job {
		final String key;
		final OutdoorPlanner planner;
		final List<CompletableFuture<Outcome>> waiters = new ArrayList<>();

		Job(String key, OutdoorPlanner planner) {
			this.key = key;
			this.planner = planner;
		}
	}

	private final RouteCache cache = new RouteCache();
	private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
	private @Nullable ClientLevel level;
	private long regionsSignature = Long.MIN_VALUE;
	private @Nullable WalkSettings settings;
	private long lastSave;
	// stats
	private long plans;
	private long plansFound;
	private long blockChanges;
	private long lastTickMicros;
	private long maxTickMicros;
	private @Nullable JsonObject lastPlan;
	private final Map<Reason, Integer> reasons = new EnumMap<>(Reason.class);
	private final ArrayDeque<JsonObject> recent = new ArrayDeque<>();
	// dev preview (dev.walk.plan show)
	private List<Point> preview = List.of();
	private int previewTicks;
	// the hub toggle's layout at the last frame
	private int uiNeeded;
	private int uiAvailable;
	private boolean uiCompact;
	private boolean uiDrawn;

	private OutdoorRoutes() {
	}

	public static OutdoorRoutes get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------ setting

	/** The key of this world in walking.json: the save folder name, else "multiplayer" (as hub-seen.json). */
	public static String world() {
		String w = Buildings.worldId();
		return w == null ? "multiplayer" : w;
	}

	static Path settingsFile() {
		return FabricLoader.getInstance().getGameDir().resolve("agentcraft").resolve(WalkSettings.FILE);
	}

	private WalkSettings settings() {
		if (settings == null) {
			settings = WalkSettings.load(settingsFile());
		}
		return settings;
	}

	/** "Agents walk between buildings" for this world (default on). */
	public boolean enabled() {
		return settings().enabled(world());
	}

	/** Sets the toggle for this world and saves walking.json now. */
	public void setEnabled(boolean on) {
		WalkSettings s = settings();
		if (s.set(world(), on) || s.dirty()) {
			save();
		}
		AgentCraft.LOGGER.info("Agents walk between buildings: {} (world {})", on ? "on" : "off", world());
	}

	private void save() {
		WalkSettings s = settings;
		if (s == null) {
			return;
		}
		lastSave = System.currentTimeMillis();
		try {
			s.save(settingsFile());
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", settingsFile(), e);
		}
	}

	/** The hub's toggle row reports its fit each frame it is drawn (dev.walk.state "ui"). */
	public void reportUi(int needed, int available, boolean compact) {
		uiNeeded = needed;
		uiAvailable = available;
		uiCompact = compact;
		uiDrawn = true;
	}

	// ------------------------------------------------------------------ decision

	/** The entrance anchor agents walk out of / into ({@link AnchorNames#ENTRANCE}), or null. */
	static @Nullable Anchor entrance(Anchors.@Nullable Layout l) {
		return l == null || l.isEmpty() ? null : l.get(AnchorNames.ENTRANCE);
	}

	/** Walk from layout {@code from} to {@code to} (both of the player's dimension, or null when not found there), or why not. */
	Reason decide(Minecraft mc, ClientLevel lvl, Anchors.@Nullable Layout from, Anchors.@Nullable Layout to) {
		Anchor a = entrance(from);
		Anchor b = entrance(to);
		LocalPlayer p = mc.player;
		boolean same = from != null && to != null && !from.isEmpty() && !to.isEmpty();
		double render = mc.options.getEffectiveRenderDistance() * 16.0;
		WalkRules.Inputs in = new WalkRules.Inputs(enabled(), same, a != null && b != null, a == null ? 0 : a.x(), a == null ? 0 : a.z(),
			b == null ? 0 : b.x(), b == null ? 0 : b.z(), p == null ? Double.MAX_VALUE / 4 : p.getX(), p == null ? 0 : p.getZ(), render);
		return WalkRules.decide(in, c -> lvl.hasChunk(WalkRules.chunkX(c), WalkRules.chunkZ(c)));
	}

	// ------------------------------------------------------------------ planning

	static Point point(Vec3 v) {
		return new Point(v.x, v.y, v.z);
	}

	/**
	 * The route {@code key} from {@code from} to {@code to}: a cached one when it is still walkable, else a
	 * planner job (shared with every request for the same key) that completes on a later client tick.
	 */
	CompletableFuture<Outcome> request(ClientLevel lvl, String key, Point from, Point to, boolean fresh) {
		if (lvl != level) {
			resetFor(lvl);
		}
		if (!fresh) {
			RouteCache.Route r = cache.get(key);
			if (r != null) {
				if (OutdoorPlanner.stillWalkable(new LevelTerrain(lvl), r.cells())) {
					return CompletableFuture.completedFuture(new Outcome(key, OutdoorPlanner.Status.FOUND, r, true, r.nodes(), r.micros(), 0));
				}
				cache.remove(key); // the terrain changed in a way no block update told us (e.g. a chunk reloaded)
			}
		}
		Job j = jobs.get(key);
		if (j == null) {
			j = new Job(key, new OutdoorPlanner(new LevelTerrain(lvl), from, to));
			jobs.put(key, j);
		}
		CompletableFuture<Outcome> f = new CompletableFuture<>();
		j.waiters.add(f);
		return f;
	}

	/** Drops a route (it got blocked while someone walked it). */
	void invalidate(String key) {
		cache.remove(key);
	}

	public int pendingJobs() {
		return jobs.size();
	}

	private void resetFor(@Nullable ClientLevel lvl) {
		level = lvl;
		cache.clear();
		failAll();
		preview = List.of();
	}

	private void failAll() {
		for (Job j : jobs.values()) {
			for (CompletableFuture<Outcome> f : j.waiters) {
				f.complete(new Outcome(j.key, OutdoorPlanner.Status.NO_PATH, null, false, j.planner.expanded(), j.planner.micros(), j.planner.steps()));
			}
		}
		jobs.clear();
	}

	/** Client tick (called by {@link AgentManager#tick} before it moves anyone): plan within the budget, draw the dev preview. */
	void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != level) {
			resetFor(lvl);
		}
		long sig = Buildings.regionsSignature();
		if (sig != regionsSignature) {
			regionsSignature = sig;
			cache.clear(); // a building placed, moved or removed: routes may cross it now
		}
		if (lvl == null) {
			return;
		}
		long t0 = System.nanoTime();
		long deadline = t0 + TICK_NANOS;
		for (Iterator<Job> it = jobs.values().iterator(); it.hasNext() && System.nanoTime() < deadline;) {
			Job j = it.next();
			OutdoorPlanner.Status s = j.planner.step(TICK_NODES, deadline);
			if (s == OutdoorPlanner.Status.RUNNING) {
				break; // it used this tick's budget
			}
			it.remove();
			finish(j);
		}
		long us = (System.nanoTime() - t0) / 1000;
		lastTickMicros = us;
		maxTickMicros = Math.max(maxTickMicros, us);
		if (previewTicks > 0) {
			previewTicks--;
			if (previewTicks % 4 == 0) {
				drawPreview(lvl);
			}
		}
		if (settings != null && settings.dirty() && System.currentTimeMillis() - lastSave > 2000) {
			save();
		}
	}

	private void finish(Job j) {
		OutdoorPlanner p = j.planner;
		plans++;
		RouteCache.Route route = null;
		if (p.status() == OutdoorPlanner.Status.FOUND) {
			plansFound++;
			route = RouteCache.Route.of(j.key, p);
			cache.put(route);
		}
		JsonObject o = new JsonObject();
		o.addProperty("key", j.key);
		o.addProperty("status", p.status().wire());
		o.addProperty("nodes", p.expanded());
		o.addProperty("micros", p.micros());
		o.addProperty("ticks", p.steps());
		o.addProperty("length", round(p.length()));
		o.addProperty("points", p.path() == null ? 0 : p.path().size());
		o.addProperty("unloadedHits", p.unloadedHits());
		lastPlan = o;
		AgentCraft.LOGGER.info("Outdoor route {}: {} ({} nodes, {} us over {} ticks, {} blocks)", j.key, p.status().wire(), p.expanded(), p.micros(),
			p.steps(), Math.round(p.length()));
		Outcome out = new Outcome(j.key, p.status(), route, false, p.expanded(), p.micros(), p.steps());
		for (CompletableFuture<Outcome> f : j.waiters) {
			f.complete(out);
		}
	}

	/** A block changed on the client (ClientLevel.sendBlockUpdated): routes passing near it are dropped. */
	public void onBlockChanged(BlockPos pos) {
		blockChanges++;
		if (cache.size() > 0) {
			cache.invalidateNear(pos.getX(), pos.getY(), pos.getZ());
		}
	}

	// ------------------------------------------------------------------ stats

	/** An agent changing building teleported ({@code r} != WALK) or started walking. */
	void note(String agentId, String from, String to, Reason r, double length) {
		reasons.merge(r, 1, Integer::sum);
		JsonObject o = new JsonObject();
		o.addProperty("agent", agentId);
		o.addProperty("from", from);
		o.addProperty("to", to);
		o.addProperty("outcome", r == Reason.WALK ? "walk" : "teleport");
		o.addProperty("reason", r.wire());
		o.addProperty("why", r.text);
		if (length > 0) {
			o.addProperty("length", round(length));
		}
		o.addProperty("at", System.currentTimeMillis());
		recent.addFirst(o);
		while (recent.size() > RECENT) {
			recent.removeLast();
		}
		if (r != Reason.WALK) {
			AgentCraft.LOGGER.info("Agent {} teleports {} -> {}: {}", agentId, from, to, r.text);
		}
	}

	JsonObject state(AgentManager m) {
		JsonObject o = new JsonObject();
		o.addProperty("enabled", enabled());
		o.addProperty("world", world());
		o.addProperty("walking", m.tripCount(true));
		o.addProperty("planning", m.tripCount(false));
		o.add("trips", m.tripsJson());
		o.addProperty("jobs", jobs.size());
		JsonObject c = new JsonObject();
		c.addProperty("size", cache.size());
		c.addProperty("hits", cache.hits());
		c.addProperty("misses", cache.misses());
		c.addProperty("invalidations", cache.invalidations());
		c.addProperty("blockChanges", blockChanges);
		JsonArray rs = new JsonArray();
		for (RouteCache.Route r : cache.routes()) {
			JsonObject j = new JsonObject();
			j.addProperty("key", r.key());
			j.addProperty("length", round(r.length()));
			j.addProperty("points", r.points().size());
			j.addProperty("cells", r.cells().length);
			j.addProperty("nodes", r.nodes());
			j.addProperty("micros", r.micros());
			rs.add(j);
		}
		c.add("routes", rs);
		o.add("cache", c);
		JsonObject p = new JsonObject();
		p.addProperty("plans", plans);
		p.addProperty("found", plansFound);
		p.addProperty("tickNodes", TICK_NODES);
		p.addProperty("tickBudgetUs", TICK_NANOS / 1000);
		p.addProperty("lastTickUs", lastTickMicros);
		p.addProperty("maxTickUs", maxTickMicros);
		p.add("last", lastPlan);
		o.add("planner", p);
		JsonObject rc = new JsonObject();
		reasons.forEach((k, v) -> rc.addProperty(k.wire(), v));
		o.add("reasons", rc);
		JsonArray rec = new JsonArray();
		recent.forEach(rec::add);
		o.add("recent", rec);
		JsonObject ui = new JsonObject();
		ui.addProperty("drawn", uiDrawn);
		ui.addProperty("needed", uiNeeded);
		ui.addProperty("available", uiAvailable);
		ui.addProperty("overflow", uiNeeded > uiAvailable);
		ui.addProperty("compact", uiCompact);
		o.add("ui", ui);
		return o;
	}

	static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	// ------------------------------------------------------------------ dev

	private void drawPreview(ClientLevel lvl) {
		List<Point> pts = preview;
		for (int i = 1; i < pts.size(); i++) {
			Point a = pts.get(i - 1);
			Point b = pts.get(i);
			double len = a.distanceTo(b);
			int n = Math.max(1, (int) Math.ceil(len / 1.0));
			for (int s = 0; s < n; s++) {
				double t = (double) s / n;
				lvl.addParticle(ParticleTypes.END_ROD, a.x() + (b.x() - a.x()) * t, a.y() + 0.25 + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t, 0,
					0.002, 0);
			}
			lvl.addParticle(ParticleTypes.HAPPY_VILLAGER, b.x(), b.y() + 0.6, b.z(), 0, 0, 0);
		}
	}

	/** A building of the player's dimension by building id or layout name ("home" = the current home), or null. */
	static Anchors.@Nullable Layout layoutByName(String name, String dim) {
		Anchors.Layout current = Buildings.currentIn(dim);
		if (name.equalsIgnoreCase("home")) {
			return current.isEmpty() ? null : current;
		}
		for (Routing.Site s : Routing.sitesIn(Buildings.sites(), dim)) {
			if (s.buildingId().equals(name) || s.layout().name().equals(name)) {
				return s.layout();
			}
		}
		return !current.isEmpty() && current.name().equals(name) ? current : null;
	}

	static void registerDev() {
		DevBridge.addStateContributor((mc, o) -> o.add("walk", get().state(AgentManager.get())));
		DevBridge.register("dev.walk.state", 10_000, "{} -> {enabled, world, walking, planning, trips[{agent, from, to, phase, length, ticks}], jobs, "
			+ "cache{size, hits, misses, invalidations, blockChanges, routes[]}, planner{plans, found, lastTickUs, maxTickUs, last{key, status, nodes, "
			+ "micros, ticks, length}}, reasons{reason: count}, recent[{agent, from, to, outcome, reason, why}], ui{needed, available, overflow, "
			+ "compact}} - agents walking between buildings (docs/WAVE2.md W8)",
			(req, mc) -> DevBridge.onClient(mc, () -> get().state(AgentManager.get())));
		DevBridge.register("dev.walk.toggle", 10_000, "{on?: bool} - set (or flip) \"Agents walk between buildings\" for this world -> {enabled, world}",
			(req, mc) -> {
				Boolean on = Fields.of(req).optBool("on");
				return DevBridge.onClient(mc, () -> {
					OutdoorRoutes r = get();
					r.setEnabled(on != null ? on : !r.enabled());
					JsonObject o = new JsonObject();
					o.addProperty("enabled", r.enabled());
					o.addProperty("world", world());
					return o;
				});
			});
		DevBridge.register("dev.walk.plan", 60_000,
			"{from, to, fresh?: false, show?: true} - plan the outdoor route between two buildings of the player's dimension (building id or 'home'), "
				+ "entrance to entrance, with the agents' planner and cache -> {status, decision, cached, nodes, micros, ticks, length, from, to, "
				+ "points[[x,y,z]]}; show draws it with particles for 20 s (for screenshots)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String from = f.nonBlank("from");
				String to = f.nonBlank("to");
				boolean fresh = f.optBool("fresh", false);
				boolean show = f.optBool("show", true);
				JsonObject head = new JsonObject();
				return DevBridge.onClient(mc, () -> {
					ClientLevel lvl = mc.level;
					if (lvl == null) {
						throw new DevBridge.DevException("not in a world");
					}
					String dim = lvl.dimension().identifier().toString();
					Anchors.Layout a = layoutByName(from, dim);
					Anchors.Layout b = layoutByName(to, dim);
					if (a == null || b == null) {
						throw new DevBridge.DevException("no building '" + (a == null ? from : to) + "' in " + dim + " (building ids: dev.hub.state, or 'home')");
					}
					Anchor ea = entrance(a);
					Anchor eb = entrance(b);
					if (ea == null || eb == null) {
						throw new DevBridge.DevException("building " + (ea == null ? from : to) + " has no entrance anchor");
					}
					head.addProperty("decision", get().decide(mc, lvl, a, b).wire());
					head.add("from", xyz(ea.pos()));
					head.add("to", xyz(eb.pos()));
					return get().request(lvl, RouteCache.key(a.name(), b.name()), point(ea.pos()), point(eb.pos()), fresh);
				}).thenCompose(x -> x).thenApply(out -> {
					JsonObject o = head.deepCopy();
					o.addProperty("key", out.key());
					o.addProperty("status", out.status().wire());
					o.addProperty("cached", out.cached());
					o.addProperty("nodes", out.nodes());
					o.addProperty("micros", out.micros());
					o.addProperty("ticks", out.ticks());
					RouteCache.Route r = out.route();
					if (r != null) {
						o.addProperty("length", round(r.length()));
						o.addProperty("cells", r.cells().length);
						JsonArray pts = new JsonArray();
						for (Point p : r.points()) {
							JsonArray pj = new JsonArray();
							pj.add(round(p.x()));
							pj.add(round(p.y()));
							pj.add(round(p.z()));
							pts.add(pj);
						}
						o.add("points", pts);
						if (show) {
							get().preview = r.points();
							get().previewTicks = 20 * 20;
						}
					} else {
						o.addProperty("reason", Reason.of(out.status()).text);
					}
					return o;
				});
			});
	}

	private static JsonArray xyz(Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(round(v.x));
		a.add(round(v.y));
		a.add(round(v.z));
		return a;
	}
}
