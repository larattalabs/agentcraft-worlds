package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.LeadRouting;
import dev.agentcraft.client.building.BuildingWizardFeature;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.diff.ReviewKit;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.CiStatus;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.RepoSettingsView;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.leads.Leads;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The hub's Repos tab (docs/HUB.md "Repos and Goals tabs"): every repo the Foreman knows with branch@head,
 * uncommitted changes, CI, worktrees, its building ("&lt;blueprint&gt; · wing 2") and lead, open PRs; the
 * detail adds the path, the read-only settings view ({@code Repo.settings}: land mode, base branch, CI and
 * setup commands, PR options, protected files, roles, review defaults, env keys) and its goals. Actions:
 * Add repo (path -> {@code repo.add}), Remove (two-step -> {@code repo.remove}), Place a building (the
 * wizard with this repo picked), Refresh PRs ({@code pr.refresh}), New goal (the Goals tab's form for it).
 * "Edit settings…" opens the repo's {@code repoSettings} as a form ({@link SettingsForm} on the repo's
 * {@link ConfigScope}: land, base branch, CI/setup, copy, protect, subagents, PR options, review defaults,
 * roles picked from the repo's {@code .claude/agents}; env read-only), applied with one {@code config.set {repoId}}.
 */
final class ReposTab implements HubPane {
	static final long CONFIRM_MS = 6000;
	private final HubScreen hub;
	private final PaneList list = new PaneList(22);
	private final HubField path = new HubField("repo_path", 1000, false, "absolute path of a git checkout, e.g. /Users/you/code/app");
	private boolean adding;
	private boolean focused;
	private @Nullable String selected;
	private boolean detailOpen;
	private @Nullable String armedRemove;
	private long armedAt;
	private boolean busy;
	private @Nullable String note;
	private boolean noteError;
	private final TextUtil.Scroll detailScroll = new TextUtil.Scroll();
	private boolean detailTop = true;
	private int[] detailArea = new int[4];
	private final List<int[]> goalRects = new ArrayList<>();
	private final List<String> goalIds = new ArrayList<>();
	private boolean compact;
	private int needed;
	private int available;
	/** The repo whose settings are being edited (the detail shows the form), null = none. */
	private @Nullable String editing;
	final SettingsForm form;

	ReposTab(HubScreen hub) {
		this.hub = hub;
		this.form = new SettingsForm(hub, "repo");
		form.onCtrlEnter(() -> {
			if (editing != null) {
				form.apply(List.of(HubConfig.repo(editing)), false);
			}
		});
	}

	@Nullable String editing() {
		return editing;
	}

	/** Opens (or closes, null) the settings form of a repo. */
	void edit(@Nullable String repoId) {
		if (repoId != null) {
			select(repoId);
			form.toTop();
		}
		form.setFocus(null);
		editing = repoId;
	}

	/** The repo form's rows: landing and checks, pull requests, review, roles (a row per agent), other, env (read-only). */
	List<SettingsForm.Row> editorRows(String repoId) {
		ConfigScope sc = HubConfig.repo(repoId);
		List<SettingsForm.Row> rows = new ArrayList<>();
		if (sc.phase() != ConfigScope.Phase.READY) {
			if (sc.phase() != ConfigScope.Phase.FAILED) {
				rows.add(new SettingsForm.Text(Foreman.connected() ? "Loading " + repoId + "'s settings…" : "The Foreman is not connected.", false));
			}
			return rows;
		}
		List<String> general = new ArrayList<>();
		List<String> pr = new ArrayList<>();
		List<String> review = new ArrayList<>();
		List<String> roles = new ArrayList<>();
		List<String> env = new ArrayList<>();
		for (dev.agentcraft.hub.SettingDef d : sc.view().settings()) {
			String k = d.key();
			if (k.startsWith("prReview.")) {
				review.add(k);
			} else if (k.startsWith("pr.")) {
				pr.add(k);
			} else if (k.startsWith("roles.")) {
				roles.add(k);
			} else if (k.equals("env") || k.startsWith("env.")) {
				env.add(k);
			} else if (!k.equals("roles")) {
				general.add(k);
			}
		}
		section(rows, sc, "Landing, checks and worktrees", general, null);
		section(rows, sc, "Pull requests (land: pr)", pr, null);
		section(rows, sc, "PR review", review, null);
		rows.add(new SettingsForm.Section("Roles (one of the repo's .claude/agents files per agent)"));
		java.util.LinkedHashSet<String> agents = new java.util.LinkedHashSet<>(TeamTab.roster());
		for (String k : roles) {
			agents.add(k.substring(6));
		}
		for (String a : agents) {
			rows.add(new SettingsForm.Setting(sc, "roles." + a, UiBits.agentName(a)));
		}
		Repo r = Foreman.state() == null ? null : Foreman.state().repo(repoId);
		List<String> keys = r != null && r.settings() != null ? r.settings().envKeys() : List.of();
		if (!env.isEmpty() || !keys.isEmpty()) {
			rows.add(new SettingsForm.Section("Env (read-only: values are never sent; edit config.json)"));
			rows.add(new SettingsForm.Text(keys.isEmpty() ? "see config.json" : String.join(", ", keys), false));
		}
		return rows;
	}

	private static void section(List<SettingsForm.Row> rows, ConfigScope sc, String title, List<String> keys, @Nullable String none) {
		if (keys.isEmpty()) {
			return;
		}
		rows.add(new SettingsForm.Section(title));
		for (String k : keys) {
			rows.add(new SettingsForm.Setting(sc, k, null));
		}
	}

	private void drawEditor(GuiGraphicsExtractor g, Repo r, int x, int y, int w, int h, int mx, int my) {
		int y0 = y;
		ConfigScope sc = HubConfig.repo(r.id());
		sc.tick();
		String done = "Done";
		int dw = hub.bw(done);
		g.text(font(), TextUtil.ellipsize(font(), "Settings of " + r.id() + (sc.view().file() != null && !compact ? "  ·  " + sc.view().file() : ""),
			w - dw - 6), x, y + 6, UiBits.ink(), false);
		hub.button(g, "repo_settings_done", done, x + w - dw, y, dw, false, false, false, mx, my, () -> edit(null));
		y += 23;
		List<ConfigScope> scopes = List.of(sc);
		y += form.banners(g, scopes, x, y, w, mx, my);
		int actionsY = y0 + h - 20;
		int formH = actionsY - 4 - y;
		needed += y - y0 + 24 + 30;
		if (formH > 12) {
			form.draw(g, editorRows(r.id()), x, y, w, formH, mx, my);
		}
		form.actions(g, scopes, x, actionsY, w, mx, my);
	}

	private Font font() {
		return hub.font();
	}

	static List<Repo> repos() {
		ForemanState s = Foreman.state();
		return s == null ? List.of() : List.copyOf(s.repos().values());
	}

	@Nullable String selected() {
		return selected;
	}

	boolean select(String id) {
		ForemanState s = Foreman.state();
		if (s == null || s.repo(id) == null) {
			return false;
		}
		if (!id.equals(selected)) {
			armedRemove = null;
			note = null;
			detailScroll.update(0, 1);
			detailTop = true;
		}
		selected = id;
		detailOpen = true;
		return true;
	}

	void startAdd() {
		adding = true;
		note = null;
		setFocus(true);
	}

	void setPath(String p) {
		path.set(p);
	}

	private void setFocus(boolean on) {
		if (on != focused) {
			focused = on;
			if (on) {
				path.model.touch();
			}
			hub.textFocus(on);
		}
	}

	private void setNote(@Nullable String n, boolean error) {
		note = n;
		noteError = error;
	}

	// ------------------------------------------------------------------ actions

	CompletableFuture<HubGoals.Note> add() {
		busy = true;
		setNote("Adding " + path.value().strip() + "…", false);
		return HubGoals.addRepo(path.value()).thenApply(n -> {
			busy = false;
			setNote(n.message(), !n.ok());
			if (n.ok()) {
				String id = n.result() != null && n.result().has("repoId") ? n.result().get("repoId").getAsString() : null;
				path.set("");
				adding = false;
				setFocus(false);
				if (id != null) {
					select(id);
				}
			}
			return n;
		});
	}

	/** Remove: the first call arms (null), the second within {@link #CONFIRM_MS} sends {@code repo.remove}. */
	@Nullable CompletableFuture<HubGoals.Note> removeClick(String id) {
		if (id.equals(armedRemove) && System.currentTimeMillis() - armedAt < CONFIRM_MS) {
			armedRemove = null;
			busy = true;
			setNote("Removing " + id + "…", false);
			return HubGoals.removeRepo(id).thenApply(n -> {
				busy = false;
				setNote(n.message(), !n.ok());
				return n;
			});
		}
		armedRemove = id;
		armedAt = System.currentTimeMillis();
		setNote("Click Confirm remove to unregister " + id + " (refused while it has open tasks; worktrees and branches stay on disk)", true);
		return null;
	}

	private boolean armed(String id) {
		return id.equals(armedRemove) && System.currentTimeMillis() - armedAt < CONFIRM_MS;
	}

	CompletableFuture<HubGoals.Note> refreshPrs() {
		busy = true;
		return HubGoals.refreshPrs().thenApply(n -> {
			busy = false;
			setNote(n.message(), !n.ok());
			return n;
		});
	}

	void placeBuilding(String id) {
		BuildingWizardFeature.openWithRepos(List.of(id));
	}

	void newGoal(String id) {
		hub.setTab(HubTab.GOALS);
		hub.goals.newGoal(id, null);
	}

	void openGoal(String goalId) {
		hub.setTab(HubTab.GOALS);
		hub.goals.open(goalId, GoalsTab.View.THREAD);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (editing != null) {
			if (form.keyPressed(e)) {
				return true;
			}
			if (e.isEscape()) {
				edit(null);
				return true;
			}
		}
		if (focused) {
			if (e.isEscape()) {
				setFocus(false);
				if (path.value().isEmpty()) {
					adding = false;
				}
				return true;
			}
			if (TextKeys.isEnter(e)) {
				add();
				return true;
			}
			if (k == InputConstants.KEY_TAB) {
				return true;
			}
			path.key(font(), e);
			return true;
		}
		if (e.isEscape() && compact && detailOpen) {
			detailOpen = false;
			return true;
		}
		if (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) {
			List<Repo> rs = repos();
			if (!rs.isEmpty()) {
				int i = 0;
				for (int j = 0; j < rs.size(); j++) {
					if (rs.get(j).id().equals(selected)) {
						i = j;
					}
				}
				select(rs.get(Math.max(0, Math.min(rs.size() - 1, i + (k == InputConstants.KEY_UP ? -1 : 1)))).id());
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (focused && e.codepoint() >= 32) {
			path.model.insert(e.codepointAsString());
			return true;
		}
		return editing != null && form.charTyped(e);
	}

	@Override
	public boolean mouseClicked(double x, double y, boolean doubleClick) {
		if (editing != null && form.mouseClicked(x, y)) {
			return true;
		}
		if (adding && path.click(font(), x, y)) {
			setFocus(true);
			return true;
		}
		for (int i = 0; i < goalRects.size(); i++) {
			int[] r = goalRects.get(i);
			if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) {
				openGoal(goalIds.get(i));
				return true;
			}
		}
		String id = list.hit(x, y);
		if (id != null) {
			select(id);
			return true;
		}
		setFocus(false);
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, int dir) {
		if (editing != null && form.mouseScrolled(x, y, dir)) {
			return true;
		}
		if (detailArea[2] > 0 && x >= detailArea[0] && x < detailArea[0] + detailArea[2] && y >= detailArea[1] && y < detailArea[1] + detailArea[3]) {
			detailScroll.scrollBy(dir * 20);
			return true;
		}
		return list.scroll(x, y, dir);
	}

	@Override
	public @Nullable String focus() {
		if (editing != null && form.focusKey() != null) {
			return form.focusKey();
		}
		return focused ? path.id : null;
	}

	@Override
	public void unfocus() {
		setFocus(false);
		form.setFocus(null);
	}

	@Override
	public void shown(boolean on) {
	}

	@Override
	public String[] hints() {
		if (focused) {
			return new String[] {"Enter", "add", "Esc", "done typing"};
		}
		if (editing != null) {
			return form.focus() != null ? new String[] {"Ctrl+Enter", "apply", "Tab", "next field", "Esc", "done typing"} : new String[] {"Ctrl+Enter",
				"apply", "Esc", "done"};
		}
		return compact && detailOpen ? new String[] {"↑↓", "repo", "Esc", "back"} : new String[] {"Tab", "next tab", "↑↓", "repo", "Esc", "close"};
	}

	// ------------------------------------------------------------------ drawing

	static String ciFamily(CiStatus ci) {
		return switch (ci) {
			case PASS -> "done";
			case FAIL -> "error";
			case RUNNING -> "working";
			default -> "idle";
		};
	}

	/** "Workshop · wing 2 (b3)" or "no building". */
	static String buildingOf(String repoId) {
		HubGoals.Wing w = HubGoals.wingOf(repoId);
		if (w == null) {
			return "no building";
		}
		Blueprint bp = Blueprints.get(w.building().blueprint());
		String name = bp != null ? bp.name() : w.building().blueprint();
		return name + (w.building().repos().size() > 1 ? " · wing " + w.wing() : "") + " (" + w.building().id() + (w.building().home() ? ", home" : "")
			+ ")";
	}

	/** The repo's lead: its building's (shared by the building's repos), Marlow for the home building and repos without one. */
	static String leadOf(String repoId) {
		return Leads.view().leadForRepo(repoId);
	}

	static String leadLabel(String repoId) {
		String lead = leadOf(repoId);
		HubGoals.Wing w = HubGoals.wingOf(repoId);
		String name = UiBits.agentName(lead);
		if (LeadRouting.MARLOW.equals(lead)) {
			return name + (w == null ? " (no building)" : w.building().home() ? " (home)" : " (no lead of its own yet)");
		}
		return w != null && w.building().repos().size() > 1 ? name + " (shared by " + w.building().repos().size() + " repos)" : name;
	}

	static int openPrs(String repoId) {
		ForemanState s = Foreman.state();
		int n = 0;
		if (s != null) {
			for (Task t : s.tasks().values()) {
				if (repoId.equals(t.repoId()) && t.pr() != null && t.pr().isOpen()) {
					n++;
				}
			}
		}
		return n;
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		form.begin();
		goalRects.clear();
		goalIds.clear();
		path.beginFrame();
		detailArea = new int[4];
		compact = w < 470 || h < 200;
		form.compact(compact);
		available = h;
		needed = 0;
		int muted = UiBits.muted();
		List<Repo> rs = repos();
		Repo cur = null;
		for (Repo r : rs) {
			if (r.id().equals(selected)) {
				cur = r;
			}
		}
		if (cur == null && !rs.isEmpty()) {
			cur = rs.get(0);
			selected = cur.id();
		}
		if (editing != null && (cur == null || !cur.id().equals(editing))) {
			editing = null;
		}
		if (editing != null) {
			// the repo's settings form takes the whole tab
			list.hide();
			drawEditor(g, cur, x, y, w, h, mx, my);
			return;
		}
		boolean showList = !compact || !detailOpen || cur == null;
		boolean showDetail = cur != null && (!compact || detailOpen);
		// top bar
		if (compact && showDetail && !showList) {
			hub.button(g, "repo_back", "‹ Repos", x, y, hub.bw("‹ Repos"), false, false, false, mx, my, () -> detailOpen = false);
		} else {
			String add = adding ? "Adding a repo" : "Add repo…";
			hub.button(g, "repo_add_open", add, x, y, hub.bw(add), !adding, adding, false, mx, my, this::startAdd);
			g.text(font(), UiBits.plural(rs.size(), "repo", "repos"), x + hub.bw(add) + 8, y + 6, muted, false);
		}
		String rp = "Refresh PRs";
		hub.button(g, "refresh_prs", rp, x + w - hub.bw(rp), y, hub.bw(rp), false, busy || !Foreman.connected(), false, mx, my, this::refreshPrs);
		y += 24;
		h -= 24;
		needed += 24;
		if (adding) {
			String ok = "Add";
			String cancel = "Cancel";
			int ow = hub.bw(ok);
			int cw = hub.bw(cancel);
			int fw = w - ow - cw - 8;
			path.draw(g, font(), x, y + 1, fw, 1, focused);
			hub.button(g, "repo_add", ok, x + fw + 4, y, ow, true, busy || !Foreman.connected(), false, mx, my, this::add);
			hub.button(g, "repo_add_cancel", cancel, x + fw + 8 + ow, y, cw, false, false, false, mx, my, () -> {
				adding = false;
				setFocus(false);
			});
			y += 24;
			h -= 24;
			needed += 24;
		}
		if (note != null) {
			g.text(font(), TextUtil.ellipsize(font(), note, w), x, y, noteError ? UiBits.errorText() : muted, false);
			y += 12;
			h -= 12;
			needed += 12;
		}
		if (rs.isEmpty()) {
			list.hide();
			Panels.inset(g, x, y, w, h);
			String msg = !Foreman.connected() ? "The Foreman is not connected: repos show up when it is."
				: "No repos yet. \"Add repo…\" registers a git checkout by its path (also /repo add <path> in the console).";
			int ly = y + 10;
			for (String line : TextUtil.wrapPlain(font(), msg, w - 16)) {
				g.text(font(), line, x + 8, ly, muted, false);
				ly += 10;
			}
			return;
		}
		int lw = showList && showDetail ? Math.max(150, Math.min(210, w * 2 / 5)) : w;
		if (showList) {
			Repo sel = cur;
			list.draw(g, x, y, lw, h, rs.size(), sel == null ? -1 : rs.indexOf(sel), mx, my, (i, rx, ry, rw) -> {
				Repo r = rs.get(i);
				Panels.dot(g, ciFamily(r.ci()), rx + rw - 7, ry + 1, false);
				String head = r.id() + "  " + r.branch() + (r.head() != null ? "@" + r.head() : "") + (r.dirty() ? " *" : "");
				g.text(font(), TextUtil.ellipsize(font(), head, rw - 12), rx, ry, UiBits.ink(), false);
				int prs = openPrs(r.id());
				String second = buildingOf(r.id()) + " · " + UiBits.agentName(leadOf(r.id())) + " · " + UiBits.plural(r.worktrees().size(), "worktree",
					"worktrees") + (prs > 0 ? " · " + UiBits.plural(prs, "PR", "PRs") + " open" : "");
				g.text(font(), TextUtil.ellipsize(font(), second, rw), rx, ry + 10, muted, false);
				return r.id();
			});
		} else {
			list.hide();
		}
		if (showDetail) {
			int dx = showList ? x + lw + 10 : x;
			int dw = showList ? w - lw - 10 : w;
			drawDetail(g, cur, dx, y, dw, h, mx, my);
		}
	}

	private void drawDetail(GuiGraphicsExtractor g, Repo r, int x, int y, int w, int h, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		int bottom = y + h;
		int buttonsY = bottom - 20;
		// facts, settings and goals in a scrolled area above the buttons
		List<String[]> rows = new ArrayList<>(); // label, value ("" label = section title; "§" = goal row id)
		rows.add(new String[] {"Path", r.path()});
		rows.add(new String[] {"Branch", r.branch() + (r.head() != null ? " @ " + r.head() : "") + (r.dirty() ? " · uncommitted changes (merges wait)"
			: " · clean")});
		rows.add(new String[] {"CI", r.ci().wire()});
		long active = r.worktrees().stream().filter(wt -> wt.status() == Protocol.WorktreeStatus.ACTIVE).count();
		rows.add(new String[] {"Worktrees", r.worktrees().size() + (active > 0 ? " (" + active + " active)" : "")});
		rows.add(new String[] {"Building", buildingOf(r.id())});
		rows.add(new String[] {"Lead", leadLabel(r.id())});
		int prs = openPrs(r.id());
		rows.add(new String[] {"Open PRs", prs == 0 ? "none" : Integer.toString(prs)});
		rows.add(new String[] {"", "Settings"});
		RepoSettingsView st = r.settings();
		if (st == null) {
			rows.add(new String[] {" ", "Not reported: the settings view needs a newer Foreman."});
		} else {
			rows.add(new String[] {"Lands by", st.land().equals("pr") ? "pull request" : "merge into " + (st.baseBranch() != null ? st.baseBranch()
				: r.branch())});
			if (st.baseBranch() != null) {
				rows.add(new String[] {"Base", st.baseBranch()});
			}
			rows.add(new String[] {"CI command", st.ci() == null ? "default (--ci or detected)" : st.ci()});
			rows.add(new String[] {"Setup", st.setup() == null ? "default" : st.setup()});
			if (st.pr() != null) {
				List<String> p = new ArrayList<>();
				if (st.pr().remote() != null) {
					p.add("remote " + st.pr().remote());
				}
				if (st.pr().branchPrefix() != null) {
					p.add("branches " + st.pr().branchPrefix() + "…");
				}
				if (Boolean.TRUE.equals(st.pr().draft())) {
					p.add("draft");
				}
				if (Boolean.TRUE.equals(st.pr().squash())) {
					p.add("squash");
				}
				rows.add(new String[] {"PR options", p.isEmpty() ? "defaults" : String.join(", ", p)});
			}
			rows.add(new String[] {"Protected", st.protect().isEmpty() ? "none" : String.join(", ", st.protect())});
			if (!st.roles().isEmpty()) {
				List<String> rl = new ArrayList<>();
				st.roles().forEach((a, role) -> rl.add(UiBits.agentName(a) + ": " + role));
				rows.add(new String[] {"Roles", String.join("; ", rl)});
			}
			if (st.subagents() != null) {
				rows.add(new String[] {"Subagents", st.subagents()});
			}
			if (st.prReview() != null) {
				rows.add(new String[] {"PR review", (st.prReview().autoSeverities().isEmpty() ? "no auto-fix" : "auto-fix " + String.join(", ", st
					.prReview().autoSeverities())) + (st.prReview().maxRounds() != null ? " · max " + st.prReview().maxRounds() + " rounds" : "")});
			}
			rows.add(new String[] {"Env", st.envKeys().isEmpty() ? "none" : String.join(", ", st.envKeys()) + " (values hidden)"});
		}
		rows.add(new String[] {"", "Goals"});
		List<Goal> goals = new ArrayList<>();
		for (Goal gl : HubGoals.goals(null)) {
			if (gl.allRepos().contains(r.id())) {
				goals.add(gl);
			}
		}
		if (goals.isEmpty()) {
			rows.add(new String[] {" ", "None yet: \"New goal…\" starts one for this repo."});
		}
		for (Goal gl : goals) {
			rows.add(new String[] {"§", gl.id()});
		}
		int labelW = 0;
		for (String[] f : rows) {
			if (!f[0].isBlank() && !f[0].equals("§")) {
				labelW = Math.max(labelW, font().width(f[0]));
			}
		}
		// measure
		int vx = labelW + 8;
		int total = 0;
		List<List<String>> wrapped = new ArrayList<>();
		for (String[] f : rows) {
			List<String> ls;
			if (f[0].isEmpty()) {
				ls = List.of(f[1]);
				total += 17;
			} else if (f[0].equals("§")) {
				ls = List.of(f[1]);
				total += 12;
			} else {
				ls = TextUtil.wrapPlain(font(), f[1], Math.max(30, w - 8 - (f[0].isBlank() ? 0 : vx)));
				total += ls.size() * 10 + 1;
			}
			wrapped.add(ls);
		}
		int areaH = Math.max(20, buttonsY - 4 - y);
		detailArea = new int[] {x, y, w, areaH};
		detailScroll.update(total, areaH);
		if (detailTop) {
			detailScroll.scrollBy(-1_000_000); // details read from the top
			detailTop = false;
		}
		needed += Math.min(total, 120) + 24;
		g.enableScissor(x, y, x + w, y + areaH);
		int dy = y - detailScroll.offset();
		for (int i = 0; i < rows.size(); i++) {
			String[] f = rows.get(i);
			List<String> ls = wrapped.get(i);
			if (f[0].isEmpty()) {
				dy += 3;
				g.text(font(), f[1], x, dy, UiStyle.CLAY_DARK, false);
				Panels.divider(g, x, dy + 10, w - 8);
				dy += 14;
				continue;
			}
			if (f[0].equals("§")) {
				Goal gl = HubGoals.goal(f[1]);
				if (gl != null) {
					String pill = gl.status().wire();
					int pw = UiBits.dotPillWidth(font(), pill);
					UiBits.dotPill(g, font(), GoalsTab.family(gl.status()), pill, x, dy - 1, muted);
					boolean hover = mx >= x && mx < x + w && my >= dy - 1 && my < dy + 10 && my >= y && my < y + areaH;
					g.text(font(), TextUtil.ellipsize(font(), gl.id() + "  " + UiBits.oneLine(gl.text()), w - pw - 12), x + pw + 4, dy, hover ? UiStyle
						.CLAY_DARK : ink, false);
					if (dy >= y && dy + 10 <= y + areaH) {
						goalRects.add(new int[] {x, dy - 1, w, 11});
						goalIds.add(gl.id());
					}
				}
				dy += 12;
				continue;
			}
			if (!f[0].isBlank()) {
				g.text(font(), f[0], x, dy, muted, false);
			}
			int tx = f[0].isBlank() ? x : x + vx;
			if (f[0].equals("Lead")) {
				ReviewKit.face(g, font(), leadOf(r.id()), tx, dy - 1, 8);
				tx += 11;
			}
			for (String l : ls) {
				g.text(font(), l, tx, dy, f[0].isBlank() ? muted : ink, false);
				dy += 10;
			}
			dy += 1;
		}
		g.disableScissor();
		Panels.scrollbar(g, x + w - 6, y, areaH, detailScroll, false);
		// buttons
		String id = r.id();
		boolean sp = hub.mc().getSingleplayerServer() != null;
		int bx = x;
		String ng = "New goal…";
		hub.button(g, "repo_new_goal", ng, bx, buttonsY, hub.bw(ng), true, false, false, mx, my, () -> newGoal(id));
		bx += hub.bw(ng) + 4;
		if (HubGoals.wingOf(id) == null) {
			String pl = compact ? "Place…" : "Place a building…";
			hub.button(g, "repo_place", pl, bx, buttonsY, hub.bw(pl), false, !sp, false, mx, my, () -> placeBuilding(id));
			bx += hub.bw(pl) + 4;
		}
		String es = compact ? "Settings…" : "Edit settings…";
		hub.button(g, "repo_edit_settings", es, bx, buttonsY, hub.bw(es), false, !Foreman.connected(), false, mx, my, () -> edit(id));
		bx += hub.bw(es) + 4;
		boolean armedHere = armed(id);
		String rm = armedHere ? "Confirm remove" : "Remove…";
		int rmw = hub.bw(rm);
		if (bx + rmw <= x + w) {
			hub.button(g, "repo_remove", rm, x + w - rmw, buttonsY, rmw, armedHere, busy || !Foreman.connected(), !armedHere, mx, my, () -> removeClick(id));
		}
	}

	// ------------------------------------------------------------------ DevBridge

	boolean focusPath() {
		if (!adding) {
			startAdd();
		}
		setFocus(true);
		return true;
	}

	@Override
	public JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("selected", selected);
		o.addProperty("detailOpen", detailOpen);
		o.addProperty("adding", adding);
		o.addProperty("path", path.value());
		o.addProperty("focus", focus());
		o.addProperty("armedRemove", armedRemove != null && armed(armedRemove) ? armedRemove : null);
		o.addProperty("busy", busy);
		o.addProperty("note", note);
		o.addProperty("noteError", noteError);
		o.addProperty("editing", editing);
		if (editing != null) {
			o.add("scope", HubConfig.repo(editing).state());
			o.add("form", form.state());
			o.add("config", HubConfig.state());
		}
		JsonObject layout = new JsonObject();
		layout.addProperty("guiWidth", hub.width);
		layout.addProperty("guiHeight", hub.height);
		layout.addProperty("compact", compact);
		layout.addProperty("needed", needed);
		layout.addProperty("available", available);
		layout.addProperty("overflow", needed > available);
		layout.addProperty("detailContent", detailScroll.content());
		layout.addProperty("detailView", detailScroll.view());
		o.add("layout", layout);
		JsonArray rs = new JsonArray();
		for (Repo r : repos()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", r.id());
			j.addProperty("path", r.path());
			j.addProperty("branch", r.branch());
			j.addProperty("head", r.head());
			j.addProperty("dirty", r.dirty());
			j.addProperty("ci", r.ci().wire());
			j.addProperty("worktrees", r.worktrees().size());
			j.addProperty("building", buildingOf(r.id()));
			HubGoals.Wing wing = HubGoals.wingOf(r.id());
			j.addProperty("buildingId", wing == null ? null : wing.building().id());
			j.addProperty("wing", wing == null ? null : wing.wing());
			j.addProperty("lead", leadOf(r.id()));
			j.addProperty("leadLabel", leadLabel(r.id()));
			j.addProperty("openPrs", openPrs(r.id()));
			j.addProperty("hasSettings", r.settings() != null);
			if (r.settings() != null) {
				j.add("settings", dev.agentcraft.client.foreman.ForemanJson.GSON.toJsonTree(r.settings()));
			}
			rs.add(j);
		}
		o.add("repos", rs);
		return o;
	}
}
