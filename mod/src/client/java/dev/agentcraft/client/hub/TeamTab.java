package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.Cast;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.LeadRouting;
import dev.agentcraft.client.agents.AgentsFeature;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hud.AgentBits;
import dev.agentcraft.client.leads.Leads;
import dev.agentcraft.hub.SettingDef;
import dev.agentcraft.hub.SettingsLogic;
import dev.agentcraft.ui.UiRules;
import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The hub's Team tab (docs/HUB.md "Team tab (mod)"): the roster (leads in {@code claude.leads} order with
 * their building, model and effort; workers on or off the team ({@code claude.workers})), each with its
 * portrait and live state; an agent's detail (title, specialty prompt, model, effort =
 * {@code claude.agents.<id>.*}; a lead's place in the order; a worker's place on the team; its role in
 * each repo = that repo's {@code roles.<id>}, picked from the repo's {@code .claude/agents} files), and a
 * Models entry (lead / worker / design model and effort, task-size models, concurrency, lead review).
 * Everything is staged ({@link SettingsForm}) and applied with one Apply: one {@code config.set} for the
 * global settings plus one per repo whose roles changed.
 */
final class TeamTab implements HubPane {
	static final String MODELS = "models";
	static final String WORKERS_KEY = "claude.workers";
	static final String LEADS_KEY = "claude.leads";
	private static final List<String> MODEL_KEYS = List.of("claude.leadModel", "claude.leadEffort", "claude.workerModel", "claude.effort",
		"claude.designModel");
	private static final List<String> SIZE_KEYS = List.of("claude.taskModels.small", "claude.taskModels.normal", "claude.taskModels.large");
	private static final List<String> LIMIT_KEYS = List.of("claude.maxConcurrent", "claude.throttleConcurrent", "claude.maxConcurrentTurns");
	private static final List<String> FIELDS = List.of("title", "prompt", "model", "effort");
	private static final List<String> FIELD_LABELS = List.of("Title", "Specialty", "Model", "Effort");

	private final HubScreen hub;
	final SettingsForm form;
	private final PaneList list = new PaneList(22);
	private String selected = MODELS;
	private boolean detailOpen;
	private boolean compact;
	private int needed;
	private int available;
	/** The last lead.releaseWorld outcome (shown under the other worlds), null = none yet. */
	private @Nullable String releaseNote;
	private boolean releaseError;

	TeamTab(HubScreen hub) {
		this.hub = hub;
		this.form = new SettingsForm(hub, "team");
		form.onCtrlEnter(() -> form.apply(scopes(), false));
	}

	/** The global settings and every repo (roles), global first. */
	static List<ConfigScope> scopes() {
		List<ConfigScope> out = new ArrayList<>();
		out.add(HubConfig.global());
		ForemanState s = Foreman.state();
		if (s != null) {
			for (String r : s.repos().keySet()) {
				out.add(HubConfig.repo(r));
			}
		}
		return out;
	}

	String selected() {
		return selected;
	}

	/** Selects "models" or an agent id in the roster; false when it is neither. */
	boolean select(String id) {
		if (!id.equals(MODELS) && !roster().contains(id)) {
			return false;
		}
		if (!id.equals(selected)) {
			form.setFocus(null);
			form.toTop();
		}
		selected = id;
		detailOpen = true;
		return true;
	}

	void back() {
		detailOpen = false;
		form.setFocus(null);
	}

	// ------------------------------------------------------------------ roster model

	static boolean isLead(String id) {
		Cast.Member m = Cast.get(id);
		if (m != null) {
			return "lead".equals(m.role());
		}
		ForemanState s = Foreman.state();
		Agent a = s == null ? null : s.agent(id);
		return a != null && a.role() == Protocol.AgentRole.LEAD || leadsInUse().contains(id);
	}

	/** The leads in use, in order (staged {@code claude.leads}, else the Foreman's lead agents). */
	static List<String> leadsInUse() {
		ConfigScope sc = HubConfig.global();
		if (sc.phase() == ConfigScope.Phase.READY && sc.def(LEADS_KEY) != null) {
			List<String> l = SettingsLogic.strings(sc.value(LEADS_KEY));
			return l.isEmpty() ? List.of(LeadRouting.MARLOW) : l;
		}
		List<String> out = new ArrayList<>();
		ForemanState s = Foreman.state();
		if (s != null) {
			for (Agent a : s.agents().values()) {
				if (a.role() == Protocol.AgentRole.LEAD) {
					out.add(a.id());
				}
			}
		}
		if (!out.contains(LeadRouting.MARLOW)) {
			out.add(0, LeadRouting.MARLOW);
		}
		return out;
	}

	/** Workers on the team (staged {@code claude.workers}, else the Foreman's active workers). */
	static List<String> workersOn() {
		ConfigScope sc = HubConfig.global();
		if (sc.phase() == ConfigScope.Phase.READY && sc.def(WORKERS_KEY) != null) {
			return SettingsLogic.strings(sc.value(WORKERS_KEY));
		}
		List<String> out = new ArrayList<>();
		ForemanState s = Foreman.state();
		if (s != null) {
			for (Agent a : s.agents().values()) {
				if (a.role() != Protocol.AgentRole.LEAD && a.isActive()) {
					out.add(a.id());
				}
			}
		}
		return out;
	}

	/** Every lead (in use first, in order, then the rest of the cast's leads). */
	static List<String> allLeads() {
		LinkedHashSet<String> out = new LinkedHashSet<>(leadsInUse());
		for (Cast.Member m : Cast.members().values()) {
			if ("lead".equals(m.role())) {
				out.add(m.id());
			}
		}
		ConfigScope sc = HubConfig.global();
		SettingDef d = sc.def(LEADS_KEY);
		if (d != null) {
			out.addAll(d.options());
		}
		return List.copyOf(out);
	}

	/** Every worker (the cast's, the Foreman's, the configured ones). */
	static List<String> allWorkers() {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		for (Cast.Member m : Cast.members().values()) {
			if (!"lead".equals(m.role())) {
				out.add(m.id());
			}
		}
		ForemanState s = Foreman.state();
		if (s != null) {
			for (Agent a : s.agents().values()) {
				if (a.role() != Protocol.AgentRole.LEAD) {
					out.add(a.id());
				}
			}
		}
		out.addAll(workersOn());
		SettingDef d = HubConfig.global().def(WORKERS_KEY);
		if (d != null) {
			out.addAll(d.options());
		}
		return List.copyOf(out);
	}

	static List<String> roster() {
		List<String> out = new ArrayList<>(allLeads());
		out.addAll(allWorkers());
		return out;
	}

	/** "opus-4-1 · high": the agent's model and effort (its own, else the lead/worker default). */
	static String modelLabel(String id) {
		ConfigScope sc = HubConfig.global();
		if (sc.phase() != ConfigScope.Phase.READY) {
			return "";
		}
		boolean lead = isLead(id);
		JsonElement m = sc.value("claude.agents." + id + ".model");
		if (m.isJsonNull() || m.isJsonPrimitive() && m.getAsString().equals("default")) {
			m = sc.value(lead ? "claude.leadModel" : "claude.workerModel");
		}
		JsonElement e = sc.value("claude.agents." + id + ".effort");
		if (e.isJsonNull() || e.isJsonPrimitive() && e.getAsString().equals("default")) {
			e = sc.value(lead ? "claude.leadEffort" : "claude.effort");
		}
		String ms = m.isJsonPrimitive() ? m.getAsString().replaceFirst("^claude-", "") : "";
		String es = e.isJsonPrimitive() ? e.getAsString() : "";
		return ms.isEmpty() ? es : es.isEmpty() ? ms : ms + " · " + es;
	}

	/** Where the lead works: "Workshop (b2)", "home" for Marlow and leads without a building here. */
	static String buildingLabel(String leadId) {
		String b = Leads.view().buildingOf(leadId);
		if (b == null) {
			return LeadRouting.MARLOW.equals(leadId) ? "home" : "no building here";
		}
		Building bd = Buildings.get(b);
		Blueprint bp = bd == null ? null : Blueprints.get(bd.blueprint());
		return (bp != null ? bp.name() : bd != null ? bd.blueprint() : b) + " (" + b + ")";
	}

	/** The live state line of an agent: state · activity · station, task, goal, lead. */
	static List<String> liveLines(String id) {
		List<String> out = new ArrayList<>();
		ForemanState s = Foreman.state();
		Agent a = s == null ? null : s.agent(id);
		if (a == null) {
			out.add("Not running (no live state from the Foreman)");
			return out;
		}
		String st = a.state().wire().replace('_', ' ') + (a.activity().isBlank() ? "" : ": " + UiBits.oneLine(a.activity()));
		if (a.isPaused()) {
			st = "paused · " + st;
		}
		if (!a.isActive()) {
			st = "off shift · " + st;
		}
		out.add(st);
		List<String> facts = new ArrayList<>();
		if (a.station() != Protocol.Station.UNKNOWN) {
			facts.add("at the " + a.station().wire());
		}
		Task t = a.taskId() == null ? null : s.task(a.taskId());
		if (t != null) {
			facts.add("task " + t.id() + " " + UiBits.oneLine(t.title()));
			if (t.goalId() != null) {
				facts.add("goal " + t.goalId());
			}
			if (!isLead(id)) {
				facts.add("lead " + AgentBits.agentName(Leads.view().leadForRepo(t.repoId())));
			}
		} else if (a.repoId() != null) {
			facts.add("repo " + a.repoId());
		}
		if (isLead(id)) {
			facts.add(buildingLabel(id));
		}
		if (!facts.isEmpty()) {
			out.add(String.join(" · ", facts));
		}
		return out;
	}

	// ------------------------------------------------------------------ C2: leads held by other worlds

	/** Worlds other than this one holding lead assignments (a dev HQ, a test or deleted save): they make buildings here overflow to Marlow. */
	static List<UiRules.OtherWorld> otherWorlds() {
		ForemanState s = Foreman.state();
		if (s == null) {
			return List.of();
		}
		List<UiRules.LeadWorld> as = new ArrayList<>();
		for (Protocol.LeadAssignment a : s.leads()) {
			String world = a.world();
			if (world == null) {
				LeadRouting.Key k = LeadRouting.parse(a.building());
				world = k == null ? null : k.worldId();
			}
			as.add(new UiRules.LeadWorld(a.leadId(), world, a.lastSync() == null ? 0 : a.lastSync()));
		}
		return UiRules.otherWorlds(as, Buildings.worldId());
	}

	/** {@code lead.releaseWorld {world}}: the Foreman drops that world's assignments (they come back if it is loaded again). */
	java.util.concurrent.CompletableFuture<String> releaseWorld(String world) {
		if (Buildings.worldId() == null) {
			releaseNote = "Load your singleplayer world first (then the other worlds are known)";
			releaseError = true;
			return java.util.concurrent.CompletableFuture.completedFuture(releaseNote);
		}
		if (!Foreman.connected()) {
			releaseNote = "The Foreman is not connected";
			releaseError = true;
			return java.util.concurrent.CompletableFuture.completedFuture(releaseNote);
		}
		JsonObject p = new JsonObject();
		p.addProperty("world", world);
		releaseNote = "Releasing " + world + "'s leads…";
		releaseError = false;
		return Foreman.send("lead.releaseWorld", p).handle((ack, err) -> {
			if (err != null || ack == null || !ack.ok()) {
				releaseError = true;
				releaseNote = Foreman.unsupported(ack) ? "This Foreman cannot release worlds yet (update it)" : Foreman.refusal("Release " + world, ack);
				return releaseNote;
			}
			List<String> released = new ArrayList<>();
			if (ack.result() != null && ack.result().isJsonObject() && ack.result().getAsJsonObject().has("released")) {
				ack.result().getAsJsonObject().getAsJsonArray("released").forEach(e -> released.add(AgentBits.agentName(e.getAsString())));
			}
			releaseError = false;
			releaseNote = released.isEmpty() ? world + " held no leads any more" : "Released " + String.join(", ", released) + " from " + world;
			return releaseNote;
		});
	}

	/** Draws the "other worlds" block (Models view); returns the y after it. */
	private int drawOtherWorlds(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
		List<UiRules.OtherWorld> others = otherWorlds();
		if (others.isEmpty() && releaseNote == null) {
			return y;
		}
		int muted = UiBits.muted();
		if (!others.isEmpty()) {
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), "Leads held by other worlds (buildings here overflow to Marlow)", w), x, y + 2, UiStyle.CLAY_DARK,
				false);
			y += 13;
		}
		for (UiRules.OtherWorld o : others) {
			String rel = "Release";
			int rw = hub.bw(rel);
			List<String> names = new ArrayList<>();
			o.leads().forEach(l -> names.add(AgentBits.agentName(l)));
			String line = o.world() + ": " + String.join(", ", names) + (o.lastSync() > 0 ? " · synced " + UiBits.ago(o.lastSync()) : "");
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), line, w - rw - 6), x, y + 6, UiBits.ink(), false);
			// with no singleplayer world loaded every world reads as "other": no Release then (it could free this world's own leads)
			hub.button(g, "team_release:" + o.world(), rel, x + w - rw, y, rw, false, !Foreman.connected() || Buildings.worldId() == null, false, mx, my,
				() -> releaseWorld(o.world()));
			y += 22;
		}
		if (releaseNote != null) {
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), releaseNote, w), x, y + 2, releaseError ? UiBits.errorText() : muted, false);
			y += 13;
		}
		return y + 4;
	}

	// ------------------------------------------------------------------ actions

	/** Puts a worker on (or off) the team: stages {@code claude.workers}. */
	void setOnTeam(String id, boolean on) {
		ConfigScope sc = HubConfig.global();
		List<String> next = new ArrayList<>(SettingsLogic.strings(sc.value(WORKERS_KEY)));
		next.remove(id);
		if (on) {
			next.add(id);
		}
		sc.set(WORKERS_KEY, SettingDef.strings(next));
	}

	/** Uses a lead (appended to the order) or stops using it; Marlow always leads. */
	void setLeadInUse(String id, boolean on) {
		if (LeadRouting.MARLOW.equals(id)) {
			return;
		}
		List<String> next = new ArrayList<>(leadsInUse());
		next.remove(id);
		if (on) {
			next.add(id);
		}
		HubConfig.global().set(LEADS_KEY, SettingDef.strings(next));
	}

	/** Moves a lead up (-1) or down (+1) in the order; Marlow stays first. */
	boolean moveLead(String id, int d) {
		List<String> next = new ArrayList<>(leadsInUse());
		int i = next.indexOf(id);
		int j = i + d;
		if (i <= 0 || j <= 0 || j >= next.size()) {
			return false;
		}
		next.set(i, next.get(j));
		next.set(j, id);
		HubConfig.global().set(LEADS_KEY, SettingDef.strings(next));
		return true;
	}

	private boolean editable() {
		ConfigScope sc = HubConfig.global();
		return sc.phase() == ConfigScope.Phase.READY && !sc.readOnly() && !sc.busy();
	}

	// ------------------------------------------------------------------ rows

	List<SettingsForm.Row> rows() {
		ConfigScope sc = HubConfig.global();
		List<SettingsForm.Row> rows = new ArrayList<>();
		if (sc.phase() != ConfigScope.Phase.READY) {
			if (sc.phase() != ConfigScope.Phase.FAILED) {
				rows.add(new SettingsForm.Text(Foreman.connected() ? "Loading the settings…" : "The Foreman is not connected: settings load when it is.",
					false));
			}
			return rows;
		}
		if (selected.equals(MODELS)) {
			addAll(rows, sc, "Models", MODEL_KEYS);
			addAll(rows, sc, "Task-size models (the lead picks a size per task; wins over the agent's model)", SIZE_KEYS);
			addAll(rows, sc, "Concurrency", LIMIT_KEYS);
			List<String> other = new ArrayList<>();
			for (SettingDef d : sc.view().settings()) {
				String k = d.key();
				if (SettingsLogic.groupOf(d).equals(SettingsLogic.TEAM) && !MODEL_KEYS.contains(k) && !SIZE_KEYS.contains(k) && !LIMIT_KEYS.contains(k) && !k.equals(WORKERS_KEY)
					&& !k.equals(LEADS_KEY) && SettingsLogic.agentOf(k) == null) {
					other.add(k);
				}
			}
			addAll(rows, sc, "Review and more", other);
			return rows;
		}
		String id = selected;
		rows.add(new SettingsForm.Section("Profile"));
		for (int i = 0; i < FIELDS.size(); i++) {
			rows.add(new SettingsForm.Setting(sc, "claude.agents." + id + "." + FIELDS.get(i), FIELD_LABELS.get(i)));
		}
		rows.add(new SettingsForm.Section("Role per repo (the repo's .claude/agents files)"));
		ForemanState s = Foreman.state();
		if (s == null || s.repos().isEmpty()) {
			rows.add(new SettingsForm.Text("No repos yet.", false));
		} else {
			for (String r : s.repos().keySet()) {
				ConfigScope rs = HubConfig.repo(r);
				if (rs.phase() == ConfigScope.Phase.READY) {
					rows.add(new SettingsForm.Setting(rs, "roles." + id, r));
				} else {
					rows.add(new SettingsForm.Text(r + ": " + (rs.phase() == ConfigScope.Phase.FAILED ? rs.error() : "loading…"), rs.phase()
						== ConfigScope.Phase.FAILED));
				}
			}
		}
		return rows;
	}

	private static void addAll(List<SettingsForm.Row> rows, ConfigScope sc, String title, List<String> keys) {
		boolean any = false;
		for (String k : keys) {
			if (sc.view().get(k) != null) {
				if (!any) {
					rows.add(new SettingsForm.Section(title));
					any = true;
				}
				rows.add(new SettingsForm.Setting(sc, k, null));
			}
		}
	}

	/** "default" / not set reads as the lead/worker default in the roster. */

	// ------------------------------------------------------------------ draw

	@Override
	public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		form.begin();
		compact = w < 470 || h < 200;
		form.compact(compact);
		available = h;
		needed = 0;
		HubConfig.global().tick();
		boolean agentShown = !selected.equals(MODELS);
		if (agentShown) {
			for (ConfigScope rs : scopes()) {
				if (rs.repoId != null) {
					rs.tick();
				}
			}
		}
		List<String> leads = allLeads();
		List<String> workers = allWorkers();
		List<String> ids = new ArrayList<>(); // row ids: "§leads", lead ids, "§workers", worker ids, "models"
		ids.add(MODELS);
		ids.add("§leads");
		ids.addAll(leads);
		ids.add("§workers");
		ids.addAll(workers);
		if (!selected.equals(MODELS) && !ids.contains(selected)) {
			selected = MODELS;
		}
		boolean showList = !compact || !detailOpen;
		boolean showDetail = !compact || detailOpen;
		int lw = showList && showDetail ? Math.max(150, Math.min(190, w * 2 / 5)) : w;
		int y0 = y;
		if (showList) {
			List<String> inUse = leadsInUse();
			List<String> on = workersOn();
			list.draw(g, x, y, lw, h, ids.size(), ids.indexOf(selected), mx, my, (i, rx, ry, rw) -> {
				String id = ids.get(i);
				int muted = UiBits.muted();
				if (id.startsWith("§")) {
					String t = id.equals("§leads") ? "Leads (in order)" : "Workers";
					g.text(hub.font(), t, rx, ry + 5, UiStyle.CLAY_DARK, false);
					Panels.divider(g, rx, ry + 15, rw);
					return id;
				}
				if (id.equals(MODELS)) {
					g.text(hub.font(), "Models and limits", rx, ry, UiBits.ink(), false);
					g.text(hub.font(), TextUtil.ellipsize(hub.font(), "lead, worker, design, task sizes", rw), rx, ry + 10, muted, false);
					return id;
				}
				boolean lead = leads.contains(id);
				boolean active = lead ? inUse.contains(id) : on.contains(id);
				AgentBits.face(g, id, rx, ry + 1, 2);
				Agent a = Foreman.state() == null ? null : Foreman.state().agent(id);
				if (a != null) {
					Panels.dot(g, a.state().family(), rx + rw - 7, ry + 1, false);
				}
				int tx = rx + 20;
				String name = AgentBits.agentName(id) + (lead && active ? "  #" + (inUse.indexOf(id) + 1) : "");
				g.text(hub.font(), TextUtil.ellipsize(hub.font(), name, rw - 30), tx, ry, active ? UiBits.ink() : muted, false);
				String second;
				if (lead) {
					second = active ? buildingLabel(id) + (modelLabel(id).isEmpty() ? "" : " · " + modelLabel(id)) : "not in use";
				} else {
					String title = title(id);
					second = (active ? "on the team" : "off the team") + (title.isEmpty() ? "" : " · " + title) + (a != null && a.taskId() != null
						? " · " + a.taskId() : "");
				}
				g.text(hub.font(), TextUtil.ellipsize(hub.font(), second, rw - 20), tx, ry + 10, muted, false);
				return id;
			});
		} else {
			list.hide();
		}
		if (showDetail) {
			int dx = showList ? x + lw + 10 : x;
			int dw = showList ? w - lw - 10 : w;
			drawDetail(g, dx, y0, dw, h, mx, my);
		}
	}

	/** The agent's title: staged/configured, else the Foreman's, else the cast's. */
	static String title(String id) {
		JsonElement t = HubConfig.global().value("claude.agents." + id + ".title");
		if (t.isJsonPrimitive() && !t.getAsString().isBlank()) {
			return t.getAsString();
		}
		Agent a = Foreman.state() == null ? null : Foreman.state().agent(id);
		if (a != null && a.title() != null) {
			return a.title();
		}
		Cast.Member m = Cast.get(id);
		return m == null ? "" : m.title();
	}

	private void drawDetail(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		int y0 = y;
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		List<ConfigScope> scopes = scopes();
		if (compact) {
			hub.button(g, "team_back", "‹ Team", x, y, hub.bw("‹ Team"), false, false, false, mx, my, this::back);
			y += 22;
		}
		if (selected.equals(MODELS)) {
			g.text(hub.font(), "Models and limits", x, y + 2, ink, false);
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), "Defaults for every agent; an agent's own model/effort wins", w), x, y + 12, muted, false);
			y += 24;
			y = drawOtherWorlds(g, x, y, w, mx, my);
		} else {
			String id = selected;
			boolean lead = isLead(id);
			AgentBits.framedPortrait(g, id, x, y, 1);
			int tx = x + 24;
			String title = title(id);
			// the agent's card (state, task, decisions, log, message / pause / stop); Esc comes back here
			boolean known = Foreman.state() != null && Foreman.state().agent(id) != null;
			String card = "Card";
			int cw = known ? hub.bw(card) : 0;
			if (known) {
				hub.button(g, "team_card:" + id, card, x + w - cw, y, cw, false, false, false, mx, my, () -> AgentsFeature.openCard(id, hub));
			}
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), AgentBits.agentName(id) + "  ·  " + (lead ? "lead" : "worker") + (title.isEmpty() ? "" : " · "
				+ title), w - 24 - cw - 4), tx, y + 1, ink, false);
			List<String> live = liveLines(id);
			int ly = y + 11;
			for (int i = 0; i < live.size() && i < (compact ? 1 : 2); i++) {
				g.text(hub.font(), TextUtil.ellipsize(hub.font(), live.get(i), w - 24 - (i == 0 ? cw + 4 : 0)), tx, ly, muted, false);
				ly += 10;
			}
			y = Math.max(y + 22, ly) + 2;
			// team / order controls
			int cx = x;
			boolean ed = editable();
			if (lead) {
				List<String> inUse = leadsInUse();
				boolean on = inUse.contains(id);
				boolean marlow = LeadRouting.MARLOW.equals(id);
				cx += form.chip(g, "team:lead_in_use:" + id, on ? "In use" : "Not in use", cx, y, on, ed && !marlow, mx, my, () -> setLeadInUse(id,
					!on)) + 6;
				if (on) {
					int i = inUse.indexOf(id);
					String pos = "#" + (i + 1) + (marlow ? " (home, always first)" : "");
					g.text(hub.font(), pos, cx, y + 3, ink, false);
					cx += hub.font().width(pos) + 6;
					if (!marlow) {
						cx += form.chip(g, "team:lead_up:" + id, "↑", cx, y, false, ed && i > 1, mx, my, () -> moveLead(id, -1)) + 3;
						cx += form.chip(g, "team:lead_down:" + id, "↓", cx, y, false, ed && i < inUse.size() - 1, mx, my, () -> moveLead(id, 1)) + 3;
					}
				}
			} else {
				boolean on = workersOn().contains(id);
				form.chip(g, "team:on_team:" + id, on ? "On the team" : "Off the team", cx, y, on, ed, mx, my, () -> setOnTeam(id, !on));
			}
			y += SettingsForm.CHIP_H + 4;
		}
		y += form.banners(g, scopes, x, y, w, mx, my);
		int actionsY = y0 + h - 20;
		int formH = actionsY - 4 - y;
		needed = Math.max(needed, y - y0 + 24 + 30);
		if (formH > 12) {
			form.draw(g, rows(), x, y, w, formH, mx, my);
		}
		form.actions(g, scopes, x, actionsY, w, mx, my);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (form.keyPressed(e)) {
			return true;
		}
		int k = e.key();
		if (e.isEscape() && compact && detailOpen) {
			back();
			return true;
		}
		if (k == InputConstants.KEY_C && !selected.equals(MODELS) && AgentsFeature.openCard(selected, hub)) {
			return true;
		}
		if (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) {
			List<String> ids = new ArrayList<>();
			ids.add(MODELS);
			ids.addAll(roster());
			int i = Math.max(0, ids.indexOf(selected));
			select(ids.get(Math.max(0, Math.min(ids.size() - 1, i + (k == InputConstants.KEY_UP ? -1 : 1)))));
			if (compact) {
				detailOpen = false;
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return form.charTyped(e);
	}

	@Override
	public boolean mouseClicked(double x, double y, boolean doubleClick) {
		if (form.mouseClicked(x, y)) {
			return true;
		}
		String id = list.hit(x, y);
		if (id != null && !id.startsWith("§")) {
			// a double click on an agent opens its card (Esc returns to the Team tab)
			if (doubleClick && id.equals(selected) && !id.equals(MODELS) && AgentsFeature.openCard(id, hub)) {
				return true;
			}
			select(id);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, int dir) {
		return form.mouseScrolled(x, y, dir) || list.scroll(x, y, dir);
	}

	@Override
	public @Nullable String focus() {
		return form.focusKey();
	}

	@Override
	public void unfocus() {
		form.setFocus(null);
	}

	@Override
	public void shown(boolean on) {
	}

	@Override
	public String[] hints() {
		if (form.focus() != null) {
			return new String[] {"Ctrl+Enter", "apply", "Tab", "next field", "Esc", "done typing"};
		}
		return compact && detailOpen ? new String[] {"↑↓", "agent", "C", "card", "Esc", "back"} : new String[] {"Tab", "next tab", "↑↓", "agent", "C",
			"card", "Ctrl+Enter", "apply", "Esc", "close"};
	}

	@Override
	public JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("selected", selected);
		o.addProperty("detailOpen", detailOpen);
		List<String> inUse = leadsInUse();
		List<String> on = workersOn();
		JsonArray roster = new JsonArray();
		for (String id : roster()) {
			JsonObject j = new JsonObject();
			boolean lead = isLead(id);
			j.addProperty("id", id);
			j.addProperty("name", AgentBits.agentName(id));
			j.addProperty("role", lead ? "lead" : "worker");
			j.addProperty("inUse", lead ? inUse.contains(id) : on.contains(id));
			j.addProperty("order", lead && inUse.contains(id) ? inUse.indexOf(id) + 1 : null);
			j.addProperty("title", title(id));
			j.addProperty("model", modelLabel(id));
			j.addProperty("building", lead ? buildingLabel(id) : null);
			JsonArray live = new JsonArray();
			liveLines(id).forEach(live::add);
			j.add("live", live);
			j.addProperty("portrait", AgentBits.hasPortrait(id));
			roster.add(j);
		}
		o.add("roster", roster);
		JsonArray others = new JsonArray();
		for (UiRules.OtherWorld w : otherWorlds()) {
			JsonObject j = new JsonObject();
			j.addProperty("world", w.world());
			JsonArray l = new JsonArray();
			w.leads().forEach(l::add);
			j.add("leads", l);
			j.addProperty("lastSync", w.lastSync());
			others.add(j);
		}
		o.add("otherWorlds", others);
		o.addProperty("releaseNote", releaseNote);
		JsonArray sc = new JsonArray();
		for (ConfigScope s : scopes()) {
			sc.add(s.state());
		}
		o.add("scopes", sc);
		o.add("form", form.state());
		o.add("config", HubConfig.state());
		o.add("layout", SettingsTab.layout(hub, compact, needed, available));
		return o;
	}
}
