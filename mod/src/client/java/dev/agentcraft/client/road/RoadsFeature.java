package dev.agentcraft.client.road;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.Road;
import dev.agentcraft.building.RoadPlan;
import dev.agentcraft.building.RoadTerrain;
import dev.agentcraft.building.Roads;
import dev.agentcraft.client.agents.OutdoorRoutes;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.client.world.ServerTasks;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.larattalabs.labui.ui.Guard;
import dev.agentcraft.walk.OutdoorPlanner;
import dev.agentcraft.walk.WalkRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * Roads between buildings, client side (docs/VILLAGE.md V1, docs/BUILDINGS.md "Roads"): the building pairs of the player's
 * dimension and their road routes (planned by {@link OutdoorRoutes#requestRoad}, the agents' planner with drops of at most a
 * block), the preview (a ghost of the road on the client's terrain, {@link RoadGhost}, with a HUD panel, {@link RoadHud};
 * Enter lays, Esc cancels), laying and removing through the integrated server ({@link Roads#lay} / {@link Roads#remove},
 * which check and plan everything again), and the offer to remove a building's roads when it is removed or moved (never
 * silently). The hub's Buildings tab shows all of it ({@code client.hub.RoadsView}). Client thread unless noted.
 */
public final class RoadsFeature {
	/** The hub's options for the next road (this session). */
	private static int width = RoadPlan.DEFAULT_WIDTH;
	private static boolean lanterns = true;
	private static boolean bridge;
	private static final Map<String, RouteState> routes = new HashMap<>();
	private static @Nullable Preview preview;
	private static @Nullable Result last;
	private static boolean busy;
	private static long lastAt;
	/** Roads whose building was removed or moved, with why (offered for removal in the hub). */
	private static final LinkedHashMap<String, String> offers = new LinkedHashMap<>();
	private static final Map<String, Anchors.Bounds> lastBoxes = new HashMap<>();
	private static @Nullable String lastWorld;
	private static long previewMicros;
	// the hub view's fit last frame (dev.roads.state ui)
	private static int uiNeeded;
	private static int uiAvailable;
	private static boolean uiDrawn;

	/** A pair's road route: planning, found (cells, length) or why not. */
	public static final class RouteState {
		public final String a;
		public final String b;
		public volatile String status = "planning";
		public long @Nullable [] cells;
		public double length;
		public @Nullable String why;
		public long at = System.currentTimeMillis();
		@Nullable CompletableFuture<RouteState> future;

		RouteState(String a, String b) {
			this.a = a;
			this.b = b;
		}

		public boolean found() {
			return "found".equals(status) && cells != null;
		}
	}

	/** The ghost being shown: the route, the plan on the client's terrain, the options. */
	public record Preview(String a, String b, long[] route, RoadPlan.Plan plan, RoadPlan.Options options, String dimension, long at) {
	}

	/** The outcome of a lay or remove (message meant for the player). */
	public record Result(String action, @Nullable String roadId, boolean ok, String message) {
	}

	private RoadsFeature() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("agentcraft_worlds.roads.tick", () -> tick(mc)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
			preview = null;
			routes.clear();
			offers.clear();
			lastBoxes.clear();
		}));
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> Guard.run("agentcraft_worlds.roads.ghost", () -> RoadGhost.submit(ctx)));
		HudElementRegistry.addLast(AgentCraft.id("hud/road_preview"), dev.larattalabs.labui.client.ui.GuardedHud.of("agentcraft_worlds.hud.road_preview", new RoadHud()));
		// listeners run on the thread that changed the buildings (the server's): hop to the client thread
		Buildings.addListener(list -> Minecraft.getInstance().execute(() -> Guard.run("agentcraft_worlds.roads.buildings", () -> buildingsChanged(list))));
		Roads.addListener(list -> Minecraft.getInstance().execute(() -> Guard.run("agentcraft_worlds.roads.changed", RoadsFeature::roadsChanged)));
		registerDev();
	}

	// ------------------------------------------------------------------ options

	public static int width() {
		return width;
	}

	public static void setWidth(int w) {
		width = Math.max(RoadPlan.MIN_WIDTH, Math.min(RoadPlan.MAX_WIDTH, w));
	}

	/** 1 -> 2 -> 3 -> 1. */
	public static void cycleWidth() {
		setWidth(width % RoadPlan.MAX_WIDTH + 1);
	}

	public static boolean lanterns() {
		return lanterns;
	}

	public static void setLanterns(boolean on) {
		lanterns = on;
	}

	public static boolean bridge() {
		return bridge;
	}

	public static void setBridge(boolean on) {
		bridge = on;
	}

	public static RoadPlan.Options options() {
		return new RoadPlan.Options(width, lanterns, bridge);
	}

	public static boolean busy() {
		return busy;
	}

	public static @Nullable Result last() {
		return last;
	}

	public static long lastAt() {
		return lastAt;
	}

	public static @Nullable Preview preview() {
		return preview;
	}

	// ------------------------------------------------------------------ pairs and routes

	/** The player's dimension id, or null outside a world. */
	static @Nullable String dimension() {
		ClientLevel lvl = Minecraft.getInstance().level;
		return lvl == null ? null : lvl.dimension().identifier().toString();
	}

	/** A building pair road key ({@code b1|b2}, ids in placement order). */
	public static String pairKey(String a, String b) {
		return Building.idNumber(a) <= Building.idNumber(b) ? a + "|" + b : b + "|" + a;
	}

	/** One building pair of the player's dimension that a road could join. */
	public record Pair(String a, String b, double distance, @Nullable Road road) {
		public String key() {
			return pairKey(a, b);
		}
	}

	/**
	 * Every pair of buildings in the player's dimension with entrances at most {@link WalkRules#MAX_DISTANCE} apart, nearest
	 * first, with the road between them if any.
	 */
	public static List<Pair> pairs() {
		String dim = dimension();
		List<Pair> out = new ArrayList<>();
		if (dim == null) {
			return out;
		}
		List<Building> bs = new ArrayList<>();
		for (Building b : Buildings.all()) {
			if (b.dimensionOrDefault().equals(dim) && entrance(b) != null) {
				bs.add(b);
			}
		}
		for (int i = 0; i < bs.size(); i++) {
			for (int j = i + 1; j < bs.size(); j++) {
				Anchor ea = entrance(bs.get(i));
				Anchor eb = entrance(bs.get(j));
				double d = Math.hypot(ea.x() - eb.x(), ea.z() - eb.z());
				Road r = Roads.between(bs.get(i).id(), bs.get(j).id());
				if (d <= WalkRules.MAX_DISTANCE || r != null) {
					out.add(new Pair(bs.get(i).id(), bs.get(j).id(), d, r));
				}
			}
		}
		out.sort((x, y) -> Double.compare(x.distance(), y.distance()));
		return out;
	}

	/** Roads of the player's dimension whose building is gone (they lead nowhere): offered for removal. */
	public static List<Road> orphans() {
		String dim = dimension();
		List<Road> out = new ArrayList<>();
		for (Road r : Roads.all()) {
			if (r.dimension().equals(dim) && (Buildings.get(r.a()) == null || Buildings.get(r.b()) == null || offers.containsKey(r.id()))) {
				out.add(r);
			}
		}
		return out;
	}

	static @Nullable Anchor entrance(Building b) {
		return b.anchors().get(AnchorNames.ENTRANCE);
	}

	/** The route state of a pair (null before it was asked for). */
	public static @Nullable RouteState route(String a, String b) {
		return routes.get(pairKey(a, b));
	}

	/**
	 * Plans (or reuses) the road route between {@code a} and {@code b}: entrance to entrance, from the building with the
	 * lower id. Completes on the client thread.
	 */
	public static CompletableFuture<RouteState> plan(String a, String b, boolean fresh) {
		String key = pairKey(a, b);
		String[] ids = key.split("\\|");
		RouteState rs = routes.get(key);
		if (rs != null && rs.future != null && !rs.future.isDone()) {
			return rs.future;
		}
		// otherwise ask again: the walking cache answers at once while the route is still walkable (it is dropped when blocks
		// near it change or a road is laid), so a preview never uses a route the terrain has since broken
		RouteState st = new RouteState(ids[0], ids[1]);
		routes.put(key, st);
		Minecraft mc = Minecraft.getInstance();
		ClientLevel lvl = mc.level;
		Building ba = Buildings.get(ids[0]);
		Building bb = Buildings.get(ids[1]);
		String why = null;
		if (lvl == null) {
			why = "not in a world";
		} else if (ba == null || bb == null) {
			why = "no building " + (ba == null ? ids[0] : ids[1]);
		} else if (!ba.dimensionOrDefault().equals(dimension()) || !bb.dimensionOrDefault().equals(dimension())) {
			why = "both buildings must be in your dimension";
		} else if (entrance(ba) == null || entrance(bb) == null) {
			why = (entrance(ba) == null ? ba.id() : bb.id()) + " has no entrance";
		}
		if (why != null) {
			st.status = "failed";
			st.why = why;
			st.future = CompletableFuture.completedFuture(st);
			return st.future;
		}
		Anchor ea = java.util.Objects.requireNonNull(entrance(ba));
		Anchor eb = java.util.Objects.requireNonNull(entrance(bb));
		CompletableFuture<RouteState> f = OutdoorRoutes.get().requestRoad(lvl, ba.layout().name() + ">" + bb.layout().name(),
			new OutdoorPlanner.Point(ea.x(), ea.y(), ea.z()), new OutdoorPlanner.Point(eb.x(), eb.y(), eb.z()), fresh).thenApply(out -> {
				if (out.route() != null) {
					st.status = "found";
					st.cells = out.route().cells();
					st.length = out.route().length();
				} else {
					st.status = "failed";
					st.why = out.why() != null ? out.why() : WalkRules.Reason.of(out.status()).text;
				}
				st.at = System.currentTimeMillis();
				return st;
			});
		st.future = f;
		return f;
	}

	// ------------------------------------------------------------------ preview

	/** The road plan along {@code route} on the client's terrain, with the same rules the server uses. */
	static RoadPlan.Plan planOnClient(ClientLevel lvl, long[] route, RoadPlan.Options o) {
		String dim = lvl.dimension().identifier().toString();
		it.unimi.dsi.fastutil.longs.LongSet taken = Roads.changedCells(dim);
		return RoadPlan.plan(route, o, new RoadTerrain(lvl), Roads.buildingBoxes(dim), taken::contains);
	}

	/**
	 * Shows the ghost of the road between {@code a} and {@code b} with {@code o} (planning the route first when needed) and
	 * closes the hub, so the player sees it. Completes with the preview, or fails with the reason.
	 */
	public static CompletableFuture<Preview> startPreview(String a, String b, RoadPlan.Options o, boolean closeScreen) {
		if (Roads.between(a, b) != null) {
			return CompletableFuture.failedFuture(new IllegalStateException(Roads.between(a, b).id() + " already joins " + a + " and " + b));
		}
		return plan(a, b, false).thenApply(rs -> {
			if (!rs.found()) {
				throw new IllegalStateException("No road route between " + a + " and " + b + ": " + rs.why);
			}
			Minecraft mc = Minecraft.getInstance();
			ClientLevel lvl = mc.level;
			if (lvl == null) {
				throw new IllegalStateException("not in a world");
			}
			long t0 = System.nanoTime();
			RoadPlan.Plan p = planOnClient(lvl, java.util.Objects.requireNonNull(rs.cells), o);
			previewMicros = (System.nanoTime() - t0) / 1000;
			Preview pv = new Preview(rs.a, rs.b, rs.cells, p, o, lvl.dimension().identifier().toString(), System.currentTimeMillis());
			preview = pv;
			if (closeScreen && mc.gui.screen() != null) {
				mc.gui.setScreen(null);
			}
			return pv;
		});
	}

	public static void cancelPreview() {
		preview = null;
	}

	/**
	 * Enter while previewing: lays the previewed road. The server checks and plans it again, and lays it only when its plan
	 * is the ghost the player confirmed ({@link RoadPlan#hash}); otherwise it asks for a new preview.
	 */
	public static CompletableFuture<Result> layPreview() {
		Preview pv = preview;
		if (pv == null) {
			return CompletableFuture.completedFuture(new Result("lay", null, false, "No road preview"));
		}
		if (pv.plan().refusal() != null) {
			return CompletableFuture.completedFuture(note(new Result("lay", null, false, "Cannot lay this road: " + pv.plan().refusal())));
		}
		return send(pv.a(), pv.b(), pv.route(), pv.options(), RoadPlan.hash(pv.plan().ops())).thenApply(r -> {
			if (r.ok()) {
				preview = null;
			}
			return r;
		});
	}

	/** Lays the road between {@code a} and {@code b} with {@code o} (plans the route first when needed), without a preview. */
	public static CompletableFuture<Result> lay(String a, String b, RoadPlan.Options o) {
		return plan(a, b, false).thenCompose(rs -> {
			if (!rs.found()) {
				return CompletableFuture.completedFuture(note(new Result("lay", null, false, "No road route between " + a + " and " + b + ": " + rs.why)));
			}
			return send(rs.a, rs.b, java.util.Objects.requireNonNull(rs.cells), o, null);
		});
	}

	private static CompletableFuture<Result> send(String a, String b, long[] route, RoadPlan.Options o, @Nullable Long previewHash) {
		busy = true;
		return run("lay", ServerTasks.callAsPlayer((level, player) -> {
			try {
				Roads.Laid l = Roads.lay(level, a, b, route, o, previewHash);
				String notes = l.plan().notes().isEmpty() ? "" : " (" + String.join("; ", l.plan().notes()) + ")";
				return new Result("lay", l.road().id(), true, "Laid road " + l.road().id() + " from " + a + " to " + b + ": " + l.road().cellCount()
					+ " cells, " + l.road().lanternCount() + " lantern" + (l.road().lanternCount() == 1 ? "" : "s") + notes + ". Remove it in the hub to undo");
			} catch (Roads.RoadException e) {
				return new Result("lay", null, false, e.getMessage());
			}
		}));
	}

	/** Removes road {@code id}: every cell it changed and nobody touched since gets its old block back. */
	public static CompletableFuture<Result> remove(String id) {
		busy = true;
		return run("remove", ServerTasks.callAsPlayer((level, player) -> {
			try {
				return new Result("remove", id, true, Roads.remove(level, id).message());
			} catch (Roads.RoadException e) {
				return new Result("remove", id, false, e.getMessage());
			}
		})).thenApply(r -> {
			if (r.ok()) {
				offers.remove(id);
			}
			return r;
		});
	}

	/** Forgets road {@code id} (drops the record, leaves the blocks): for a road whose snapshot is gone. */
	public static CompletableFuture<Result> forget(String id) {
		busy = true;
		return run("forget", ServerTasks.callOnServer(server -> {
			try {
				Roads.forget(server, id);
				return new Result("forget", id, true, "Forgot road " + id + "; its blocks stay as they are");
			} catch (Roads.RoadException e) {
				return new Result("forget", id, false, e.getMessage());
			}
		})).thenApply(r -> {
			if (r.ok()) {
				offers.remove(id);
			}
			return r;
		});
	}

	/** Keeps a road whose building went (dismisses the offer; it stays listed while its building is gone). */
	public static void keep(String id) {
		offers.remove(id);
	}

	public static Map<String, String> offers() {
		return java.util.Collections.unmodifiableMap(offers);
	}

	private static CompletableFuture<Result> run(String action, CompletableFuture<Result> f) {
		return f.exceptionally(t -> {
			Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
			if (!(c instanceof ServerTasks.Refused)) {
				AgentCraft.LOGGER.error("Road {} failed", action, c);
			}
			return new Result(action, null, false, c instanceof ServerTasks.Refused ? c.getMessage() : action + " failed: " + c);
		}).thenApply(RoadsFeature::note).whenComplete((r, t) -> busy = false);
	}

	private static Result note(Result r) {
		last = r;
		lastAt = System.currentTimeMillis();
		Toasts.push(new Notify(r.ok() ? NotifyLevel.INFO : NotifyLevel.WARN, r.message(), null, System.currentTimeMillis()));
		return r;
	}

	// ------------------------------------------------------------------ keys, tick

	/**
	 * Preview keys, called by the keyboard mixin before vanilla handles a key: Enter lays, Esc / Backspace cancel. Only
	 * while previewing with no screen open; releases and every other key pass (the player can walk around the ghost).
	 */
	public static boolean onKey(int action, KeyEvent e) {
		return Guard.call("agentcraft_worlds.roads.key", () -> {
			if (action == InputConstants.RELEASE || preview == null || Minecraft.getInstance().gui.screen() != null) {
				return false;
			}
			boolean repeat = action != InputConstants.PRESS;
			switch (e.key()) {
				case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
					if (!repeat && !busy) {
						layPreview();
					}
				}
				case InputConstants.KEY_ESCAPE, InputConstants.KEY_BACKSPACE -> {
					if (!repeat) {
						cancelPreview();
					}
				}
				default -> {
					return false;
				}
			}
			return true;
		}, false);
	}

	private static void tick(Minecraft mc) {
		Preview pv = preview;
		if (pv != null && (mc.level == null || !pv.dimension().equals(dimension()))) {
			preview = null; // another dimension or no world: the ghost would be wrong
		}
	}

	private static void roadsChanged() {
		// a road laid or removed: routes now prefer it (OutdoorRoutes drops its cache); the pairs' routes may change too
		routes.clear();
		offers.keySet().removeIf(id -> Roads.get(id) == null);
	}

	/**
	 * A building was removed or moved (any path: the hub, the command, Undo move): its roads now lead nowhere or to the old
	 * site. They are offered for removal (a toast, and the hub's Roads list), never removed silently.
	 */
	private static void buildingsChanged(List<Building> list) {
		String world = Buildings.worldId();
		if (world == null || !world.equals(lastWorld)) {
			lastWorld = world;
			lastBoxes.clear();
			offers.clear();
			for (Building b : list) {
				lastBoxes.put(b.id(), b.restoreBox());
			}
			return;
		}
		Map<String, Anchors.Bounds> now = new HashMap<>();
		for (Building b : list) {
			now.put(b.id(), b.restoreBox());
		}
		for (Map.Entry<String, Anchors.Bounds> e : lastBoxes.entrySet()) {
			Anchors.Bounds nb = now.get(e.getKey());
			String why = nb == null ? e.getKey() + " was removed" : !nb.equals(e.getValue()) ? e.getKey() + " moved" : null;
			if (why == null) {
				continue;
			}
			List<Road> rs = Roads.forBuilding(e.getKey());
			for (Road r : rs) {
				offers.put(r.id(), why);
			}
			if (!rs.isEmpty()) {
				String ids = String.join(", ", rs.stream().map(Road::id).toList());
				Toasts.push(new Notify(NotifyLevel.INFO, why + ": " + (rs.size() == 1 ? "its road " + ids + " leads " : "its roads " + ids + " lead ")
					+ (nb == null ? "nowhere now" : "to the old site") + ". Remove " + (rs.size() == 1 ? "it" : "them") + " in the hub: Buildings > Roads",
					null, System.currentTimeMillis()), null, null);
				routes.keySet().removeIf(k -> k.startsWith(e.getKey() + "|") || k.endsWith("|" + e.getKey()));
			}
		}
		lastBoxes.clear();
		lastBoxes.putAll(now);
	}

	/** The hub view reports its fit each frame it is drawn (dev.roads.state ui). */
	public static void reportUi(int needed, int available) {
		uiNeeded = needed;
		uiAvailable = available;
		uiDrawn = true;
	}

	/** The Buildings tab's list switch (Buildings / Blueprints / Designs / Roads + Place new…) reports its fit (dev.roads.state ui.strip). */
	/** The Buildings sub-strip's width: {@code compact} 0 = full labels, 1 = no counts, 2 = no counts and a short right button. */
	public static void reportStrip(int needed, int available, int compact) {
		stripNeeded = needed;
		stripAvailable = available;
		stripCompact = compact;
	}

	private static int stripNeeded;
	private static int stripAvailable;
	private static int stripCompact;

	/** The road HUD panel drawn last frame (x, y, w, h), or null: toasts keep clear of it. */
	public static int @Nullable [] hudRect() {
		return RoadHud.lastRect;
	}

	// ------------------------------------------------------------------ DevBridge

	static JsonObject planJson(RoadPlan.Plan p) {
		JsonObject o = new JsonObject();
		o.addProperty("refusal", p.refusal());
		o.addProperty("centre", p.centre());
		o.addProperty("trimmed", p.trimmed());
		o.addProperty("cells", p.cells().size());
		o.addProperty("ops", p.ops().size());
		JsonObject by = new JsonObject();
		for (RoadPlan.Block b : RoadPlan.Block.values()) {
			int n = p.count(b);
			if (n > 0) {
				by.addProperty(b.wire(), n);
			}
		}
		o.add("blocks", by);
		o.addProperty("lanterns", p.lanternCount());
		JsonObject sk = new JsonObject();
		p.skipped().forEach(sk::addProperty);
		o.add("skipped", sk);
		JsonArray notes = new JsonArray();
		p.notes().forEach(notes::add);
		o.add("notes", notes);
		Anchors.Bounds b = p.box();
		o.addProperty("box", b == null ? null : Buildings.str(b));
		int slabs = 0;
		int bridges = 0;
		int kept = 0;
		for (RoadPlan.Cell c : p.cells()) {
			switch (c.role()) {
				case SLAB -> slabs++;
				case BRIDGE -> bridges++;
				case KEPT -> kept++;
				default -> {
				}
			}
		}
		o.addProperty("halfSteps", slabs);
		o.addProperty("bridgeCells", bridges);
		o.addProperty("keptCells", kept);
		return o;
	}

	static JsonObject resultJson(@Nullable Result r) {
		if (r == null) {
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("action", r.action());
		o.addProperty("roadId", r.roadId());
		o.addProperty("ok", r.ok());
		o.addProperty("message", r.message());
		return o;
	}

	static JsonObject state() {
		JsonObject o = Roads.json();
		o.addProperty("world", OutdoorRoutes.world());
		o.addProperty("dimension", dimension());
		JsonObject opt = new JsonObject();
		opt.addProperty("width", width);
		opt.addProperty("lanterns", lanterns);
		opt.addProperty("bridge", bridge);
		o.add("options", opt);
		JsonArray ps = new JsonArray();
		for (Pair p : pairs()) {
			JsonObject j = new JsonObject();
			j.addProperty("a", p.a());
			j.addProperty("b", p.b());
			j.addProperty("key", p.key());
			j.addProperty("distance", Math.round(p.distance() * 10) / 10.0);
			j.addProperty("road", p.road() == null ? null : p.road().id());
			RouteState rs = route(p.a(), p.b());
			j.addProperty("route", rs == null ? "not planned" : rs.status);
			if (rs != null && rs.found()) {
				j.addProperty("length", Math.round(rs.length * 10) / 10.0);
				j.addProperty("routeCells", rs.cells.length);
			}
			if (rs != null && rs.why != null) {
				j.addProperty("why", rs.why);
			}
			ps.add(j);
		}
		o.add("pairs", ps);
		JsonObject of = new JsonObject();
		offers.forEach(of::addProperty);
		o.add("offers", of);
		JsonArray orph = new JsonArray();
		orphans().forEach(r -> orph.add(r.id()));
		o.add("orphans", orph);
		Preview pv = preview;
		if (pv != null) {
			JsonObject j = planJson(pv.plan());
			j.addProperty("a", pv.a());
			j.addProperty("b", pv.b());
			j.addProperty("width", pv.options().width());
			j.addProperty("lanternsOn", pv.options().lanterns());
			j.addProperty("bridge", pv.options().bridge());
			j.addProperty("routeCells", pv.route().length);
			j.addProperty("planMicros", previewMicros);
			o.add("preview", j);
		} else {
			o.add("preview", null);
		}
		o.addProperty("busy", busy);
		o.add("last", resultJson(last));
		JsonObject ghost = new JsonObject();
		ghost.addProperty("lastFrameQuads", RoadGhost.lastQuads);
		ghost.addProperty("lastFrameMicros", RoadGhost.lastNanos / 1000);
		ghost.addProperty("frames", RoadGhost.frames);
		o.add("ghost", ghost);
		JsonObject ui = new JsonObject();
		ui.addProperty("drawn", uiDrawn);
		ui.addProperty("needed", uiNeeded);
		ui.addProperty("available", uiAvailable);
		ui.addProperty("overflow", uiNeeded > uiAvailable);
		JsonObject strip = new JsonObject();
		strip.addProperty("needed", stripNeeded);
		strip.addProperty("available", stripAvailable);
		strip.addProperty("overflow", stripNeeded > stripAvailable);
		strip.addProperty("compact", stripCompact);
		ui.add("strip", strip);
		int[] hr = RoadHud.lastRect;
		ui.addProperty("hudShown", hr != null);
		o.add("ui", ui);
		return o;
	}

	private static RoadPlan.Options options(Fields f) {
		return new RoadPlan.Options(f.optInt("width", width, RoadPlan.MIN_WIDTH, RoadPlan.MAX_WIDTH), f.optBool("lanterns", lanterns),
			f.optBool("bridge", bridge));
	}

	private static void registerDev() {
		DevBridge.register("dev.roads.state", 10_000, "{} -> {roads[{id, a, b, width, lanterns, bridge, length, cells, changes, lanternCount, notes, orphan}], "
			+ "pending[], pairs[{a, b, key, distance, road, route, length?, why?}], options{width, lanterns, bridge}, offers{roadId: why}, orphans[], "
			+ "preview{a, b, cells, ops, blocks{}, lanterns, skipped{}, notes, halfSteps, bridgeCells, refusal}, last{action, roadId, ok, message}, "
			+ "ghost{lastFrameQuads}, ui{needed, available, overflow, strip{needed, available, overflow, compact}}} - roads between buildings (docs/VILLAGE.md V1)",
			(req, mc) -> DevBridge.onClient(mc, RoadsFeature::state));
		DevBridge.register("dev.roads.preview", 60_000, "{a, b, width?: 1-3, lanterns?: bool, bridge?: bool, cancel?: false} - plan the road route "
			+ "between two buildings (ids) and show its ghost (closes screens; Enter lays, Esc cancels) -> the preview's plan {cells, ops, blocks, lanterns, "
			+ "skipped, notes, halfSteps, bridgeCells, refusal, box}; cancel:true hides it",
			(req, mc) -> {
				Fields f = Fields.of(req);
				if (f.optBool("cancel", false)) {
					return DevBridge.onClient(mc, () -> {
						cancelPreview();
						JsonObject o = new JsonObject();
						o.addProperty("cancelled", true);
						return o;
					});
				}
				String a = f.nonBlank("a");
				String b = f.nonBlank("b");
				RoadPlan.Options o = options(f);
				return DevBridge.onClient(mc, () -> startPreview(a, b, o, true)).thenCompose(x -> x).handle((pv, t) -> {
					if (t != null) {
						Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
						throw new DevBridge.DevException(c.getMessage());
					}
					JsonObject j = planJson(pv.plan());
					j.addProperty("a", pv.a());
					j.addProperty("b", pv.b());
					j.addProperty("routeCells", pv.route().length);
					j.addProperty("planMicros", previewMicros);
					return j;
				});
			});
		DevBridge.register("dev.roads.lay", 60_000, "{a?, b?, width?: 1-3, lanterns?: bool, bridge?: bool} - lay the road between two buildings "
			+ "(through the integrated server, which checks and plans it again); without a and b: the shown preview (Enter) -> {action, roadId, ok, message}",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String a = f.optStr("a", null);
				String b = f.optStr("b", null);
				RoadPlan.Options o = options(f);
				return DevBridge.onClient(mc, () -> a == null || b == null ? layPreview() : lay(a, b, o)).thenCompose(x -> x)
					.thenApply(RoadsFeature::resultJson);
			});
		DevBridge.register("dev.roads.remove", 30_000, "{road, forget?: false} - remove a road (road = its id, r<n>; the field is not `id`, the request id): each cell it changed "
			+ "and nobody touched since gets its old block back; forget:true drops the record and leaves the blocks -> {action, roadId, ok, message}", (req, mc) -> {
				Fields f = Fields.of(req);
				// "id" is the DevBridge's request id (reserved, echoed back): a road named there never reached this handler
				String id = f.nonBlank("road");
				boolean forget = f.optBool("forget", false);
				return DevBridge.onClient(mc, () -> forget ? forget(id) : remove(id)).thenCompose(x -> x).thenApply(RoadsFeature::resultJson);
			});
		DevBridge.register("dev.roads.blocks", 30_000, "{x0, y0, z0, x1, y1, z1} - QA: the block states of a box in the overworld, read on the "
			+ "integrated server (at most 262144 cells) -> {box, palette[state], cells: palette index per cell, x fastest then z then y, "
			+ "blockEntities} (compare a road's area before laying and after removing it)", (req, mc) -> {
				Fields f = Fields.of(req);
				int x0 = (int) f.integer("x0", -30_000_000, 30_000_000);
				int y0 = (int) f.integer("y0", -30_000_000, 30_000_000);
				int z0 = (int) f.integer("z0", -30_000_000, 30_000_000);
				int x1 = (int) f.integer("x1", -30_000_000, 30_000_000);
				int y1 = (int) f.integer("y1", -30_000_000, 30_000_000);
				int z1 = (int) f.integer("z1", -30_000_000, 30_000_000);
				int ax = Math.min(x0, x1);
				int bx = Math.max(x0, x1);
				int ay = Math.min(y0, y1);
				int by = Math.max(y0, y1);
				int az = Math.min(z0, z1);
				int bz = Math.max(z0, z1);
				long n = (long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1);
				if (n > 262_144) {
					throw new DevBridge.DevException("box too big: " + n + " cells (at most 262144)");
				}
				return ServerTasks.callOnServer(server -> {
					net.minecraft.server.level.ServerLevel lv = server.overworld();
					Map<net.minecraft.world.level.block.state.BlockState, Integer> ids = new LinkedHashMap<>();
					JsonArray palette = new JsonArray();
					JsonArray cells = new JsonArray();
					int bes = 0;
					net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
					for (int y = ay; y <= by; y++) {
						for (int z = az; z <= bz; z++) {
							for (int x = ax; x <= bx; x++) {
								m.set(x, y, z);
								var s = lv.getBlockState(m);
								Integer id = ids.get(s);
								if (id == null) {
									id = ids.size();
									ids.put(s, id);
									palette.add(s.toString());
								}
								cells.add(id);
								if (s.hasBlockEntity()) {
									bes++;
								}
							}
						}
					}
					JsonObject o = new JsonObject();
					o.addProperty("box", ax + "," + ay + "," + az + " .. " + bx + "," + by + "," + bz);
					o.add("palette", palette);
					o.add("cells", cells);
					o.addProperty("blockEntities", bes);
					return o;
				});
			});
		DevBridge.register("dev.roads.plan", 60_000, "{a, b, fresh?: false} - plan just the road route between two buildings (no ghost) -> {status, "
			+ "length, cells, why?}", (req, mc) -> {
				Fields f = Fields.of(req);
				String a = f.nonBlank("a");
				String b = f.nonBlank("b");
				boolean fresh = f.optBool("fresh", false);
				return DevBridge.onClient(mc, () -> plan(a, b, fresh)).thenCompose(x -> x).thenApply(rs -> {
					JsonObject o = new JsonObject();
					o.addProperty("a", rs.a);
					o.addProperty("b", rs.b);
					o.addProperty("status", rs.status);
					o.addProperty("length", Math.round(rs.length * 10) / 10.0);
					o.addProperty("cells", rs.cells == null ? 0 : rs.cells.length);
					o.addProperty("why", rs.why);
					return o;
				});
			});
	}
}
