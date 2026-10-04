package dev.agentcraft.client.village;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.Trophies;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hub.HubFeature;
import dev.agentcraft.client.hub.HubScreen;
import dev.agentcraft.client.hub.HubTab;
import dev.agentcraft.client.hub.Inbox;
import dev.agentcraft.client.leads.Leads;
import dev.agentcraft.client.leads.LeadsFeature;
import dev.agentcraft.client.monitor.DisplayStats;
import dev.agentcraft.client.trophy.TrophyFeature;
import dev.agentcraft.client.world.StationInteractions;
import dev.agentcraft.client.world.StationRenderer;
import dev.agentcraft.hub.InboxModel;
import dev.agentcraft.trophy.TrophyEvents;
import dev.agentcraft.ui.Guard;
import dev.agentcraft.village.VillageBoard;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The village board (docs/VILLAGE.md V2): the {@code village_board} fixture's display shows every building of the
 * world, the newest milestones and holds ({@link VillageBoardRenderer}, content from the pure {@link VillageBoard});
 * a right-click opens the hub on Buildings, or the Inbox when something needs the player.
 *
 * <p>Data: the Foreman state (goals, PRs), the buildings and their leads (client thread), the Inbox counts and the
 * trophy ledger, which lives on the server thread: it is copied from the integrated server every few seconds
 * ({@link Trophies#hung()}), never read across threads.
 *
 * <p>Dev: {@code dev.board.state}, {@code dev.board.set {page, ppb, lightFloor}}, {@code dev.board.aim {board?, distance?}},
 * {@code dev.board.use {board?}}; screens {@code hub_fixtures}.
 */
public final class VillageBoardFeature {
	/** The blueprint the hub's "Place village board…" places. */
	public static final String BLUEPRINT = "village_board";
	/** Block-light floor of the board (readable at night, like the task wall). {@code dev.board.set {lightFloor}}. */
	static int lightFloor = 13;
	/** DevBridge: hold one page (-1 = pages turn by themselves). */
	static int forcedPage = -1;
	/** DevBridge: force a pixel density (0 = {@link VillageBoard#density}). */
	static int densityOverride;

	private static final Map<BlockPos, BoardView> VIEWS = new HashMap<>();
	private static VillageBoard.Content content = VillageBoard.Content.EMPTY;
	/** What the content was built from (compared every frame without allocating): -1 = never built. */
	private static long keyRevision = Long.MIN_VALUE;
	private static @Nullable Object keySites;
	private static @Nullable String keyWorld;
	private static long keyHung;
	private static long keyInbox;
	private static boolean keyConnected;
	private static long keyBucket;
	/** When the content was last built (ms): Foreman revisions alone rebuild at most once a second. */
	private static long builtAt;
	private static volatile List<Trophies.Hung> hung = List.of();
	private static long hungVersion;
	private static int ticks;

	private VillageBoardFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.VILLAGE_BOARD, ctx -> new VillageBoardRenderer());
		StationInteractions.onUse(ModBlocks.VILLAGE_BOARD, (player, pos, state, be) -> Guard.run("village.board.use", VillageBoardFeature::use));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("village.board.tick", () -> tick(mc)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
			VIEWS.clear();
			hung = List.of();
			content = VillageBoard.Content.EMPTY;
			keyRevision = Long.MIN_VALUE;
			builtAt = 0;
		}));
		DevBridge.registerScreen("hub_fixtures", mc -> {
			HubScreen s = new HubScreen(HubTab.BUILDINGS);
			s.setSub(HubScreen.Sub.FIXTURES);
			return s;
		});
		registerDev();
	}

	/** Right-click: the Inbox when something needs the player, else hub > Buildings. Returns where it went. */
	static String use() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return "none";
		}
		if (Inbox.counts().needsYou() > 0) {
			Inbox.open("needs_you");
			return "inbox";
		}
		HubScreen s = HubFeature.open(HubTab.BUILDINGS);
		s.setSub(HubScreen.Sub.BUILDINGS);
		return "buildings";
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			return;
		}
		ticks++;
		// the trophy ledger, copied on the server thread every 5 s while a board was drawn in the last 30 s
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null && ticks % 100 == 0 && !VIEWS.isEmpty()) {
			server.execute(() -> {
				List<Trophies.Hung> now = Trophies.hung();
				mc.execute(() -> {
					if (!now.equals(hung)) {
						hung = now;
						hungVersion++;
					}
				});
			});
		}
		if (ticks % 200 == 0) {
			long now = System.nanoTime();
			for (Iterator<BoardView> it = VIEWS.values().iterator(); it.hasNext();) {
				if (now - it.next().lastUsedNanos > 30_000_000_000L) {
					it.remove();
				}
			}
		}
	}

	/** The prepared view of the board whose panel origin is {@code origin} (render thread = client thread). */
	static BoardView view(BlockPos origin, int w, int h) {
		long t0 = System.nanoTime();
		BoardView v = VIEWS.computeIfAbsent(origin.immutable(), BoardView::new);
		v.lastUsedNanos = t0;
		if (v.sync(content(), w, h, forcedPage, densityOverride, offline(), System.currentTimeMillis())) {
			DisplayStats.rebuilt(DisplayStats.Kind.BOARD);
		}
		DisplayStats.add(DisplayStats.Kind.BOARD, System.nanoTime() - t0);
		return v;
	}

	/**
	 * The board content, rebuilt when its inputs changed (and every 30 s, for "5 min ago" and the week's start). The Foreman
	 * revision moves on every log line, say and agent event while agents stream output, so a revision change alone rebuilds at
	 * most once a second ({@link VillageBoard#rebuildDue}); and a rebuild that comes out equal keeps the old instance, so the
	 * board views (which compare by identity) do not redraw.
	 */
	static VillageBoard.Content content() {
		ForemanState st = Foreman.state();
		long now = System.currentTimeMillis();
		long rev = st == null ? -1L : st.revision();
		Object sites = Buildings.sites(); // the same instance until the buildings (not the fixtures) change
		String world = Buildings.worldId();
		long inbox = Inbox.revision();
		boolean connected = Foreman.connected();
		long bucket = now / 30_000;
		boolean other = !(sites == keySites && Objects.equals(world, keyWorld) && hungVersion == keyHung && inbox == keyInbox
			&& connected == keyConnected && bucket == keyBucket);
		if (!VillageBoard.rebuildDue(other, rev != keyRevision, keyRevision == Long.MIN_VALUE, now, builtAt)) {
			return content;
		}
		keyRevision = rev;
		keySites = sites;
		keyWorld = world;
		keyHung = hungVersion;
		keyInbox = inbox;
		keyConnected = connected;
		keyBucket = bucket;
		builtAt = now;
		VillageBoard.Content next = VillageBoard.build(input(st, now));
		if (!next.equals(content)) {
			content = next;
		}
		return content;
	}

	/** The link is down (or was never up): the rows stay, without leads; the header says so. */
	static boolean offline() {
		return !Foreman.connected();
	}

	static VillageBoard.Input input(@Nullable ForemanState st, long now) {
		ZoneId zone = ZoneId.systemDefault();
		List<VillageBoard.Site> sites = new ArrayList<>();
		for (Building b : Buildings.buildings()) {
			Blueprint bp = Blueprints.get(b.blueprint());
			String lead = Foreman.connected() ? Leads.view().leadOf(b.id()) : null;
			List<String> repoNames = new ArrayList<>();
			for (String r : b.repos()) {
				Protocol.Repo repo = st == null ? null : st.repo(r);
				// two repos with the same name (two checkouts of one project, the sim's demo repos): the id tells them apart
				boolean shared = repo != null && st.repos().values().stream().anyMatch(o -> o != repo && repo.name().equals(o.name()));
				repoNames.add(repo == null ? r : shared ? r : repo.name());
			}
			sites.add(new VillageBoard.Site(b.id(), b.id() + " " + (bp != null ? bp.name() : b.blueprint()), b.repos(), repoNames, lead,
				LeadsFeature.leadLabel(b), b.home()));
		}
		List<VillageBoard.Goal> goals = new ArrayList<>();
		List<VillageBoard.Pr> prs = new ArrayList<>();
		List<TrophyEvents.Award> awards = List.of();
		if (st != null && st.hasData()) {
			Map<String, String> goalRepos = TrophyFeature.goalRepos(st);
			List<TrophyEvents.GoalIn> gi = new ArrayList<>();
			for (Protocol.Goal g : st.goals().values()) {
				goals.add(new VillageBoard.Goal(g.id(), g.text(), g.isOpen(), g.progress(), g.allRepos(), g.leadId(), g.updatedAt()));
				gi.add(TrophyFeature.goalIn(g, st));
			}
			List<TrophyEvents.TaskIn> ti = new ArrayList<>();
			for (Protocol.Task t : st.tasks().values()) {
				ti.add(TrophyFeature.taskIn(t));
				Protocol.TaskPr pr = t.pr();
				// the PR's repo as the trophies take it (TrophyEvents): the task's, else (missing or blank) its goal's
				String repo = t.repoId() != null && !t.repoId().isBlank() ? t.repoId() : t.goalId() == null ? null : goalRepos.get(t.goalId());
				if (pr != null && repo != null && !repo.isBlank()) {
					prs.add(new VillageBoard.Pr(repo, Integer.toString(pr.id()), pr.isOpen(), "merged".equals(pr.status()),
						pr.updatedAt() > 0 ? pr.updatedAt() : t.updatedAt(), "failing".equals(pr.checks())));
				}
			}
			awards = TrophyEvents.catchUp(gi, ti, goalRepos, zone);
		}
		List<VillageBoard.Hung> hs = new ArrayList<>();
		for (Trophies.Hung h : hung) {
			hs.add(new VillageBoard.Hung(h.building(), h.key(), h.lines(), h.at()));
		}
		InboxModel.Counts counts = Inbox.counts();
		String hold = counts.hold() == null ? null : InboxModel.holdText(counts.hold(), zone);
		// counts without the hold: the hold has its own banner
		InboxModel.Counts needs = new InboxModel.Counts(counts.decisions(), counts.blocked(), counts.replies(), counts.prs(), null);
		return new VillageBoard.Input(sites, goals, prs, awards, hs, hold, counts.needsYou(), needs.line(zone), now, zone);
	}

	// ------------------------------------------------------------------ face <-> world (aim for shots)

	/** World point of board pixel (px, py) on the board plane. */
	static Vec3 toWorld(BlockPos origin, Direction facing, int panelH, int ppb, float px, float py) {
		Vector3f l = new Vector3f(1 - px / ppb - 0.5f, panelH - py / ppb, dev.agentcraft.client.taskwall.TaskBoardRenderer.LINEN_DEPTH - 0.5f);
		new Quaternionf().rotationY((float) Math.toRadians(-StationRenderer.modelRotation(facing))).transform(l);
		return new Vec3(origin.getX() + 0.5 + l.x, origin.getY() + l.y, origin.getZ() + 0.5 + l.z);
	}

	// ------------------------------------------------------------------ dev

	private static @Nullable BoardView pick(@Nullable String board) {
		for (BoardView v : VIEWS.values()) {
			if (board == null || board.equals(v.origin.getX() + " " + v.origin.getY() + " " + v.origin.getZ())) {
				return v;
			}
		}
		return null;
	}

	private static void registerDev() {
		DevBridge.register("dev.board.state", 10_000, "{} - village boards drawn in the last 30 s (origin, size, ppb, text height in blocks, "
			+ "layout: rows per page, page/pages, two columns, milestones shown; the rows and milestones on show), the content (rows, milestones, "
			+ "hold, needsYou, where a right-click goes), the placed fixtures and the overrides", (req, mc) -> DevBridge.onClient(mc, () -> {
				JsonObject o = new JsonObject();
				VillageBoard.Content c = content();
				o.addProperty("lightFloor", lightFloor);
				o.addProperty("forcedPage", forcedPage);
				o.addProperty("ppbOverride", densityOverride);
				o.addProperty("blueprintLoaded", Blueprints.get(BLUEPRINT) != null);
				o.addProperty("clickOpens", Inbox.counts().needsYou() > 0 ? "inbox" : "buildings");
				o.addProperty("hold", c.hold());
				o.addProperty("needsYou", c.needsYou());
				o.addProperty("needsLine", c.needsLine());
				o.addProperty("prsOpen", c.prsOpen());
				o.addProperty("prsMergedThisWeek", c.prsMerged());
				o.addProperty("weekStart", c.weekStart());
				o.addProperty("trophiesHung", hung.size());
				JsonArray rows = new JsonArray();
				for (VillageBoard.Row r : c.rows()) {
					JsonObject j = new JsonObject();
					j.addProperty("building", r.buildingId());
					j.addProperty("name", r.name());
					j.addProperty("repos", r.repos());
					j.addProperty("leadId", r.leadId());
					j.addProperty("lead", r.lead());
					j.addProperty("home", r.home());
					j.addProperty("goal", r.goal());
					j.addProperty("progress", r.progress());
					j.addProperty("moreGoals", r.moreGoals());
					j.addProperty("prsOpen", r.prsOpen());
					j.addProperty("prsMerged", r.prsMerged());
					j.addProperty("failing", r.failing());
					rows.add(j);
				}
				o.add("rows", rows);
				JsonArray ms = new JsonArray();
				long now = System.currentTimeMillis();
				for (VillageBoard.Milestone m : c.milestones()) {
					JsonObject j = new JsonObject();
					j.addProperty("key", m.key());
					j.addProperty("kind", m.kind());
					j.addProperty("label", m.label());
					j.addProperty("text", m.text());
					j.addProperty("where", m.where());
					j.addProperty("building", m.buildingId());
					j.addProperty("ago", VillageBoard.ago(m.at(), now));
					j.addProperty("trophy", m.trophy());
					ms.add(j);
				}
				o.add("milestones", ms);
				JsonArray boards = new JsonArray();
				for (BoardView v : VIEWS.values()) {
					JsonObject j = new JsonObject();
					j.addProperty("origin", v.origin.getX() + " " + v.origin.getY() + " " + v.origin.getZ());
					j.addProperty("size", v.panelW + "x" + v.panelH);
					j.addProperty("ppb", v.ppb);
					j.addProperty("px", v.pw + "x" + v.ph);
					j.addProperty("textHeightBlocks", Math.round(v.textBlocks * 1000) / 1000.0);
					VillageBoard.Layout l = v.layout;
					if (l != null) {
						j.addProperty("rowsPerPage", l.perPage());
						j.addProperty("twoColumns", l.twoColumns());
						j.addProperty("milestoneSlots", l.milestones());
					}
					j.addProperty("page", v.page.index() + 1);
					j.addProperty("pages", v.page.count());
					JsonArray shown = new JsonArray();
					v.shownRows.forEach(shown::add);
					j.add("rowsShown", shown);
					j.addProperty("milestonesShown", v.shownMilestones);
					j.addProperty("rebuilds", v.rebuilds);
					j.addProperty("ageMs", (System.nanoTime() - v.lastUsedNanos) / 1_000_000L);
					JsonArray texts = new JsonArray();
					for (BoardView.Text t : v.texts) {
						texts.add(t.s());
					}
					for (BoardView.Text t : v.cardTexts) {
						texts.add(t.s());
					}
					j.add("texts", texts);
					boards.add(j);
				}
				o.add("boards", boards);
				JsonArray fx = new JsonArray();
				for (Building b : Buildings.fixtures()) {
					JsonObject j = new JsonObject();
					j.addProperty("id", b.id());
					j.addProperty("blueprint", b.blueprint());
					j.addProperty("dimension", b.dimensionOrDefault());
					j.addProperty("box", Buildings.str(b.box()));
					j.addProperty("snapshotBox", Buildings.str(b.restoreBox()));
					j.addProperty("movedFrom", b.movedFrom() == null ? null : b.movedFrom().x() + "," + b.movedFrom().y() + "," + b.movedFrom().z());
					fx.add(j);
				}
				o.add("fixtures", fx);
				return o;
			}));
		DevBridge.register("dev.board.set", 10_000, "{page?: 1.. (0 = pages turn by themselves), ppb?: 0-128 (0 = auto), lightFloor?: 0-15} - "
			+ "hold a page / force a density / the light floor for A/B shots", (req, mc) -> {
				Fields f = Fields.of(req);
				int page = f.optInt("page", -2, 0, 999);
				int ppb = f.optInt("ppb", -1, 0, 128);
				int floor = f.optInt("lightFloor", -1, 0, 15);
				return DevBridge.onClient(mc, () -> {
					if (page != -2) {
						forcedPage = page == 0 ? -1 : page - 1;
					}
					if (ppb >= 0) {
						densityOverride = ppb;
					}
					if (floor >= 0) {
						lightFloor = floor;
					}
					JsonObject o = new JsonObject();
					o.addProperty("forcedPage", forcedPage);
					o.addProperty("ppbOverride", densityOverride);
					o.addProperty("lightFloor", lightFloor);
					return o;
				});
			});
		DevBridge.register("dev.board.aim", 10_000, "{board?: \"x y z\" origin, distance?: 8} - the board's centre in the world and an eye "
			+ "position that far in front of it (then dev.camera; 8 blocks = the contract's reading distance)", (req, mc) -> {
				Fields f = Fields.of(req);
				String board = f.optStr("board", null);
				int dist = f.optInt("distance", 8, 1, 64);
				return DevBridge.onClient(mc, () -> {
					BoardView v = pick(board);
					if (v == null || mc.level == null) {
						throw new DevBridge.DevException("no village board drawn in the last 30 s" + (board == null ? "" : " at " + board)
							+ " (look at one first)");
					}
					BlockState st = mc.level.getBlockState(v.origin);
					if (!(st.getBlock() instanceof PanelBlock)) {
						throw new DevBridge.DevException("no village board at " + v.origin.toShortString() + " any more");
					}
					Direction facing = st.getValue(PanelBlock.FACING);
					Vec3 centre = toWorld(v.origin, facing, v.panelH, v.ppb, v.pw / 2f, v.ph / 2f);
					Vec3 eye = centre.add(facing.getStepX() * dist, 0.0, facing.getStepZ() * dist);
					JsonObject o = new JsonObject();
					o.add("point", vec(centre));
					o.add("eye", vec(eye));
					o.addProperty("facing", facing.getName());
					return o;
				});
			});
		DevBridge.register("dev.board.use", 10_000, "{} - what a right-click on a village board does (Inbox when something needs you, else hub > "
			+ "Buildings); returns where it went and the open screen", (req, mc) -> DevBridge.onClient(mc, () -> {
				JsonObject o = new JsonObject();
				o.addProperty("opened", use());
				o.addProperty("screen", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
				if (mc.gui.screen() instanceof HubScreen h) {
					o.addProperty("tab", h.tab().id);
					o.addProperty("sub", h.sub().name().toLowerCase(java.util.Locale.ROOT));
				}
				return o;
			}));
	}

	private static JsonObject vec(Vec3 v) {
		JsonObject o = new JsonObject();
		o.addProperty("x", v.x);
		o.addProperty("y", v.y);
		o.addProperty("z", v.z);
		return o;
	}
}
