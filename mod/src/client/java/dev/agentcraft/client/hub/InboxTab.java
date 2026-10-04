package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.agents.AgentsFeature;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.decisions.AnswerPanel;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.decisions.DiffLink;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.taskwall.TaskScreen;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hub.DetailLayout;
import dev.agentcraft.hub.InboxModel;
import dev.agentcraft.hub.InboxModel.Filter;
import dev.agentcraft.hub.InboxModel.FilterType;
import dev.agentcraft.hub.InboxModel.Group;
import dev.agentcraft.hub.InboxModel.Item;
import dev.agentcraft.hub.InboxModel.Kind;
import dev.agentcraft.hub.InboxModel.Row;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The hub's Inbox tab (docs/WAVE2.md W1-W4): everything that needs the player or happened for them in one list,
 * grouped Needs you / Updates, filtered all / needs you / per building / per agent / per podium, with a detail per
 * item: a decision answers right there ({@link AnswerPanel}: Merge and Reject ask twice), a reply shows the message
 * with a reply box (the goal's thread or {@code @agent}), a blocked task offers Retry / Open task / Open card, a hold
 * says what it means and when it lifts, a PR shows why it needs you; the agent view (agent filter) has the card's
 * summary and the agent's full log, paged back on scroll up. An item counts as read once it was shown for a moment.
 *
 * <p>Compact (under 470 × 200 GUI px: GUI scale 4 at 1080p, 4K auto scale): the list or the detail, never both, with
 * a "‹ Inbox" back button; the detail's body scrolls and the answer / reply area stays pinned at the bottom.
 */
final class InboxTab implements HubPane {
	private static final int TOP_H = 22;
	private static final int ROW_H = 22;
	/** An item counts as read after it was shown this long. */
	private static final long READ_AFTER_MS = 1200;

	private final HubScreen hub;
	private final PaneList list = new PaneList(ROW_H);
	private final HubField reply = new HubField("inbox_reply", 4000, true, "Reply… (Ctrl+Enter sends)");
	private @Nullable HubField focus;
	private final AnswerPanel panel;
	private @Nullable String selected;
	private boolean detailOpen;
	private long shownSince;
	private @Nullable String shownKey;
	private final TextUtil.Scroll bodyScroll = new TextUtil.Scroll();
	private final TextUtil.Scroll logScroll = new TextUtil.Scroll();
	private @Nullable String bodyFor;
	private int[] bodyArea = new int[4];
	private int[] logArea = new int[4];
	private final Map<String, AgentLogView> logs = new HashMap<>();
	private @Nullable String note;
	private boolean noteError;
	private boolean sending;
	private final List<int[]> chipRects = new ArrayList<>();
	private final List<Runnable> chipActions = new ArrayList<>();
	private final List<String> chipIds = new ArrayList<>();
	private int logRowsShown;
	private int logFrom;
	/** Display rows of the list (null entries = group headers). */
	private final List<@Nullable Row> display = new ArrayList<>();

	// a flowing detail (DetailLayout): its scroll offset, its visible column, the clip for clickable buttons
	private int flowOffset;
	private int[] flowArea = new int[4];
	private int clipTop = Integer.MIN_VALUE;
	private int clipBottom = Integer.MAX_VALUE;
	private boolean flowTyping;
	private @Nullable DetailLayout lastLayout;
	/** compact, detail only: the top bar's room right of "‹ Inbox" {x, y, w} (the detail's actions go there) */
	private int @Nullable [] topSlot;
	private boolean actionsInTopBar;
	// the agent log's wrapped lines (rebuilt when its entries or the width change)
	private @Nullable String logKey;
	private List<LogLine> logLines = List.of();
	private int logPrependedRows;
	private boolean logRebuilt;
	private int logRebuilds;

	// layout (DevBridge)
	private boolean compact;
	private int needed;
	private int available;
	private int width;
	private String shownMode = "list";

	InboxTab(HubScreen hub) {
		this.hub = hub;
		this.panel = new AnswerPanel(new AnswerPanel.Host() {
			@Override
			public void textFocus(boolean on) {
				if (on) {
					focus = null; // one text field at a time
				}
				hub.textFocus(on || focus != null);
			}

			@Override
			public boolean openDiff(Decision d) {
				return DiffLink.open(d.repoId(), d.worktree(), d, hub);
			}
		}, AnswerPanel.Options.EMBEDDED);
	}

	private Font font() {
		return hub.font();
	}

	// ------------------------------------------------------------------ state

	@Nullable String selected() {
		return selected;
	}

	AnswerPanel panel() {
		return panel;
	}

	/** Selects item {@code key} and shows its detail (also in compact mode). */
	void select(@Nullable String key) {
		if (!Objects.equals(key, selected)) {
			unfocus();
			bodyScroll.toTop();
			note = null;
		}
		selected = key;
		detailOpen = key != null;
	}

	/** The filter changed (chips, deep links): the selection stays when the new list still shows it. */
	void filterChanged() {
		list.reset();
		if (selected != null && Inbox.rows(Inbox.filter()).stream().noneMatch(r -> r.item().key().equals(selected))) {
			selected = null;
			detailOpen = false;
		}
	}

	void setFilter(Filter f) {
		Inbox.setFilter(f);
		filterChanged();
	}

	void back() {
		unfocus();
		detailOpen = false;
	}

	private void focus(@Nullable HubField f) {
		if (f == focus) {
			return;
		}
		if (f != null) {
			panel.focusText(false);
			f.model.touch();
		}
		focus = f;
		hub.textFocus(f != null || panel.textFocused());
	}

	HubField replyField() {
		return reply;
	}

	/** Focuses the reply box (DevBridge; Enter does it from the list). */
	void focusReply() {
		focus(reply);
	}

	@Override
	public @Nullable String focus() {
		return focus != null ? focus.id : panel.textFocused() ? "inbox_answer" : null;
	}

	@Override
	public void unfocus() {
		focus(null);
		panel.focusText(false);
	}

	@Override
	public void shown(boolean on) {
		if (!on) {
			HubGoals.flush(true);
		}
		shownKey = null;
	}

	void setNote(@Nullable String n, boolean error) {
		note = n;
		noteError = error;
	}

	@Nullable String note() {
		return note;
	}

	/** The rows the list shows now. */
	List<Row> rows() {
		return Inbox.rows(Inbox.filter());
	}

	@Nullable Row current(List<Row> rows) {
		for (Row r : rows) {
			if (r.item().key().equals(selected)) {
				return r;
			}
		}
		return null;
	}

	/** The selected item, or null. */
	@Nullable Item currentItem() {
		Row r = current(rows());
		return r == null ? null : r.item();
	}

	AgentLogView log(String agentId) {
		return logs.computeIfAbsent(agentId, AgentLogView::new);
	}

	// ------------------------------------------------------------------ actions

	/** Retry a blocked task (task.action retry). */
	CompletableFuture<String> retry(String taskId) {
		return act(Foreman.taskAction(taskId, "retry", null), "Retry " + taskId, "Retrying " + taskId);
	}

	CompletableFuture<String> refreshPrs() {
		sending = true;
		return HubGoals.refreshPrs().thenApply(n -> {
			sending = false;
			setNote(n.message(), !n.ok());
			return n.message();
		});
	}

	private CompletableFuture<String> act(CompletableFuture<Protocol.Ack> f, String what, String okMsg) {
		if (!Foreman.connected()) {
			setNote(what + ": the Foreman is not connected", true);
			return CompletableFuture.completedFuture(note);
		}
		sending = true;
		return f.handle((ack, err) -> {
			sending = false;
			if (err != null) {
				setNote(what + " not sent: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()), true);
			} else if (!ack.ok()) {
				setNote(Foreman.refusal(what, ack), true);
			} else {
				setNote(UiBits.CHECK + " " + okMsg, false);
			}
			return note;
		});
	}

	/**
	 * Sends the reply box: to the goal's lead as a goal message when the item is about a goal (it shows in the goal's
	 * thread), else to the agent ({@code user.message}). Returns the note.
	 */
	CompletableFuture<String> sendReply(Item it) {
		String text = reply.value().strip();
		if (text.isEmpty()) {
			focus(reply);
			setNote("Type a reply first", true);
			return CompletableFuture.completedFuture(note);
		}
		if (!Foreman.connected()) {
			setNote("Reply: the Foreman is not connected", true);
			return CompletableFuture.completedFuture(note);
		}
		String goal = it.kind() == Kind.AGENT ? null : it.goalId();
		String agent = it.agentId();
		sending = true;
		reply.set("");
		setNote("Sending…", false);
		CompletableFuture<String> f;
		if (goal != null && HubGoals.goal(goal) != null) {
			f = HubGoals.message(goal, text).thenApply(n -> {
				setNote(n.ok() ? UiBits.CHECK + " " + n.message() + " (goal " + goal + ")" : n.message(), !n.ok());
				return n.ok();
			}).thenApply(ok -> afterSend(ok, text));
		} else if (agent != null) {
			f = Foreman.message(agent, text).handle((ack, err) -> {
				boolean ok = err == null && ack.ok();
				setNote(ok ? UiBits.CHECK + " Sent to " + UiBits.agentName(agent) : err != null ? "Not sent: " + err.getMessage() : Foreman.refusal("Message",
					ack), !ok);
				return afterSend(ok, text);
			});
		} else {
			sending = false;
			reply.set(text);
			setNote("Nobody to reply to", true);
			return CompletableFuture.completedFuture(note);
		}
		return f;
	}

	private String afterSend(boolean ok, String text) {
		sending = false;
		if (!ok && reply.value().isEmpty()) {
			reply.set(text);
		}
		return note;
	}

	void openThread(Item it) {
		if (it.goalId() == null || HubGoals.goal(it.goalId()) == null) {
			setNote("This is not about a goal", true);
			return;
		}
		hub.setTab(HubTab.GOALS);
		hub.goals.open(it.goalId(), GoalsTab.View.THREAD);
	}

	void openGoalTasks(Item it) {
		if (it.goalId() == null || HubGoals.goal(it.goalId()) == null) {
			setNote("This task has no goal in the Foreman's list", true);
			return;
		}
		hub.setTab(HubTab.GOALS);
		hub.goals.open(it.goalId(), GoalsTab.View.TASKS);
	}

	void openTask(String taskId) {
		hub.mc().gui.setScreen(new TaskScreen(taskId).withParent(hub));
	}

	boolean openCard(@Nullable String agentId) {
		if (agentId == null || !AgentsFeature.openCard(agentId, hub)) {
			setNote("No card for " + (agentId == null ? "nobody" : agentId), true);
			return false;
		}
		Inbox.markAgentSeen(agentId);
		return true;
	}

	void openDecisionScreen(String decisionId) {
		DecisionsFeature.openQueue(decisionId, hub);
	}

	// ------------------------------------------------------------------ input

	private void moveSelection(int d) {
		List<Row> rs = rows();
		if (rs.isEmpty()) {
			return;
		}
		int i = -1;
		for (int j = 0; j < rs.size(); j++) {
			if (rs.get(j).item().key().equals(selected)) {
				i = j;
			}
		}
		i = Math.max(0, Math.min(rs.size() - 1, i < 0 ? 0 : i + d));
		String key = rs.get(i).item().key();
		boolean keepList = compact && !detailOpen;
		select(key);
		if (keepList) {
			detailOpen = false;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		Item it = currentItem();
		if (focus != null) {
			if (e.isEscape()) {
				focus(null);
				return true;
			}
			if (TextKeys.isEnter(e)) {
				// the reply box is multi-line: Enter = new line, Ctrl+Enter sends (UiRules.enter)
				if (TextKeys.enter(e, true) == dev.agentcraft.ui.UiRules.EnterAction.SEND) {
					if (it != null) {
						sendReply(it);
					}
				} else {
					focus.model.insert("\n");
				}
				return true;
			}
			focus.key(font(), e);
			return true; // a focused field swallows the rest (no hub keys while typing)
		}
		if (panel.textFocused()) {
			Decision d = it == null ? null : decisionOf(it);
			if (e.isEscape()) {
				if (panel.requestChanges()) {
					panel.cancelRequestChanges();
				}
				panel.focusText(false);
				return true;
			}
			if (d != null) {
				panel.keyPressed(font(), e, d, false, false);
			}
			return true;
		}
		if (e.isEscape() && compact && detailOpen) {
			back();
			return true;
		}
		if (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) {
			moveSelection(k == InputConstants.KEY_UP ? -1 : 1);
			return true;
		}
		if (TextKeys.isEnter(e) && selected != null) {
			if (!detailOpen) {
				detailOpen = true;
			} else if (it != null && (it.kind() == Kind.REPLY || it.kind() == Kind.AGENT)) {
				focus(reply);
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (focus != null && e.codepoint() >= 32) {
			focus.model.insert(e.codepointAsString());
			return true;
		}
		if (panel.textFocused()) {
			Item it = currentItem();
			Decision d = it == null ? null : decisionOf(it);
			if (d != null) {
				panel.charTyped(e, d, false);
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(double x, double y, boolean doubleClick) {
		for (int i = 0; i < chipRects.size(); i++) {
			int[] r = chipRects.get(i);
			if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) {
				chipActions.get(i).run();
				return true;
			}
		}
		Item it = currentItem();
		Decision d = it == null ? null : decisionOf(it);
		// a flowing detail: its answer box, buttons and reply box only take clicks inside the visible column
		boolean hidden = flowArea[2] > 0 && !inside(flowArea, x, y);
		if (d != null && !hidden && panel.mouseClicked(font(), d, x, y, false)) {
			if (panel.textFocused()) {
				focus = null;
			}
			return true;
		}
		if (!hidden && reply.click(font(), x, y)) {
			focus(reply);
			return true;
		}
		String id = list.hit(x, y);
		if (id != null) {
			if (!id.startsWith("hdr:")) {
				select(id);
			}
			return true;
		}
		if (focus != null || panel.textFocused()) {
			unfocus();
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, int dir) {
		if (inside(logArea, x, y)) {
			scrollLog(dir * 3);
			return true;
		}
		if (inside(flowArea, x, y)) {
			scrollDetail(dir * 3);
			return true;
		}
		if (inside(bodyArea, x, y)) {
			bodyScroll.scrollBy(dir * 3);
			return true;
		}
		return list.scroll(x, y, dir);
	}

	/** Scrolls a flowing detail (one that does not fit: DetailLayout) by {@code rows} lines. */
	void scrollDetail(int rows) {
		flowOffset = Math.max(0, flowOffset + rows * DetailLayout.LINE_H); // clamped by the next frame's layout
	}

	/** Scrolls the agent log by {@code rows}; scrolling up at the top asks for the page before. */
	void scrollLog(int rows) {
		if (rows < 0 && logScroll.offset() == 0) {
			Item it = currentItem();
			if (it != null && it.kind() == Kind.AGENT && it.agentId() != null) {
				log(it.agentId()).loadOlder();
			}
		}
		logScroll.scrollBy(rows);
	}

	private static boolean inside(int[] r, double x, double y) {
		return r[2] > 0 && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
	}

	@Override
	public String[] hints() {
		if (focus != null) {
			return new String[] {"Ctrl+Enter", "send", "Enter", "new line", "Esc", "done typing"};
		}
		if (panel.textFocused()) {
			return new String[] {"Enter", panel.requestChanges() ? "send feedback" : "send", "Shift+Enter", "new line", "Esc", "done typing"};
		}
		if (compact && detailOpen) {
			return new String[] {"↑↓", "item", "Esc", "back"};
		}
		return new String[] {"Tab", "next tab", "↑↓", "item", "Enter", compact ? "open" : "reply", "Esc", "close"};
	}

	static @Nullable Decision decisionOf(Item it) {
		if (it.kind() != Kind.DECISION || it.refId() == null) {
			return null;
		}
		ForemanState s = Foreman.state();
		return s == null ? null : s.decision(it.refId());
	}

	// ------------------------------------------------------------------ drawing helpers

	private int chip(GuiGraphicsExtractor g, String id, String label, int x, int y, boolean on, int mx, int my, Runnable action) {
		int w = font().width(label) + 12;
		Panels.sprite(g, on ? Kit.TAB_ACTIVE : Kit.TAB_INACTIVE, x, y, w, 14);
		if (!on && mx >= x && mx < x + w && my >= y && my < y + 14) {
			g.fill(x + 1, y + 1, x + w - 1, y + 13, 0x14000000);
		}
		g.text(font(), label, x + 6, y + 3, on ? UiBits.ink() : UiBits.muted(), false);
		chipRects.add(new int[] {x, y, w, 14});
		chipActions.add(action);
		chipIds.add(id);
		return w;
	}

	private int chipW(String label) {
		return font().width(label) + 12;
	}

	static String family(Item it) {
		return switch (it.kind()) {
			case DECISION -> it.open() ? "waiting" : "done";
			case BLOCKED -> "error";
			case PR -> "error";
			case HOLD -> "waiting";
			case REPLY -> "thinking";
			case AGENT -> "idle";
		};
	}

	static String kindLabel(Item it) {
		return switch (it.kind()) {
			case DECISION -> it.subKind() == null ? "decision" : switch (it.subKind()) {
				case "merge" -> "merge";
				case "permission" -> "permission";
				default -> "question";
			};
			case REPLY -> "reply";
			case BLOCKED -> "blocked";
			case PR -> "PR";
			case HOLD -> "paused";
			case AGENT -> "agent";
		};
	}

	private String rowSub(Item it) {
		List<String> parts = new ArrayList<>();
		if (it.agentId() != null) {
			parts.add(UiBits.agentName(it.agentId()));
		}
		parts.add(kindLabel(it));
		if (it.kind() == Kind.HOLD) {
			parts.add(InboxModel.holdText(new InboxModel.Hold(it.subKind(), it.until(), it.detail()), ZoneId.systemDefault()));
		} else if (it.kind() == Kind.AGENT) {
			if (!it.detail().isBlank()) {
				parts.add(it.detail());
			}
		} else if (it.ts() > 0) {
			parts.add(UiBits.ago(it.ts()));
		}
		return String.join(" · ", parts);
	}

	// ------------------------------------------------------------------ draw

	@Override
	public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		chipRects.clear();
		chipActions.clear();
		chipIds.clear();
		reply.beginFrame();
		bodyArea = new int[4];
		logArea = new int[4];
		flowArea = new int[4];
		lastLayout = null;
		topSlot = null;
		actionsInTopBar = false;
		compact = w < 470 || h < 200;
		panel.fieldLines(compact ? 1 : AnswerPanel.Options.EMBEDDED.fieldLines());
		available = h;
		width = w;
		needed = 0;
		List<Row> rows = rows();
		Row cur = current(rows);
		if (cur == null && selected != null && !rows.isEmpty()) {
			selected = null; // it left the list (answered elsewhere, another filter)
		}
		if (cur == null && !compact && !rows.isEmpty()) {
			selected = rows.get(0).item().key();
			cur = rows.get(0);
		}
		if (cur == null) {
			detailOpen = false;
		}
		boolean showList = !compact || !detailOpen || cur == null;
		boolean showDetail = cur != null && (!compact || detailOpen);
		shownMode = showList && showDetail ? "list+detail" : showDetail ? "detail" : "list";
		// read on view: shown for a moment
		if (showDetail) {
			if (!cur.item().key().equals(shownKey)) {
				shownKey = cur.item().key();
				shownSince = System.currentTimeMillis();
			} else if (System.currentTimeMillis() - shownSince >= READ_AFTER_MS && cur.unread()) {
				Inbox.markRead(cur.item());
			}
		} else {
			shownKey = null;
		}
		// top bar
		if (compact && showDetail && !showList) {
			int backW = hub.bw("‹ Inbox");
			hub.button(g, "inbox_back", "‹ Inbox", x, y, backW, false, false, false, mx, my, this::back);
			topSlot = new int[] {x + backW + 8, y, w - backW - 8};
		} else {
			drawFilters(g, x, y, w, mx, my);
		}
		y += TOP_H;
		h -= TOP_H;
		needed += TOP_H;
		// the "since you were away" digest at the top (docs/WAVE2.md W6: H after the away toast opens the Inbox)
		HubGoals.DigestState away = HubGoals.away();
		if (showList && away != null && !away.dismissed && (away.loading() || away.digest != null && !away.digest.goals().isEmpty())) {
			int ah = drawAway(g, away, x, y, w, mx, my);
			y += ah;
			h -= ah;
			needed += ah;
		}
		if (rows.isEmpty()) {
			list.hide();
			panel.hide();
			panel.focusText(false);
			drawEmpty(g, x, y, w, h);
			needed += 40;
			return;
		}
		int lw = showDetail && showList ? Math.max(150, Math.min(220, w * 2 / 5)) : w;
		if (showList) {
			drawList(g, rows, cur, x, y, lw, h, mx, my);
		} else {
			list.hide();
		}
		if (showDetail) {
			int dx = showList ? x + lw + 10 : x;
			int dw = showList ? w - lw - 10 : w;
			drawDetail(g, cur.item(), dx, y, dw, h, mx, my);
		} else {
			panel.hide();
			panel.focusText(false);
		}
		// the list needs room for two rows; the detail added what it needs (header, body minimum, pinned area)
		needed = Math.max(needed, TOP_H + ROW_H * 2 + 6);
	}

	/** One row: "Since you were away (14:02): 2 goals moved · 1 needs you", click = the Goals tab (its full digest), Dismiss. */
	private int drawAway(GuiGraphicsExtractor g, HubGoals.DigestState st, int x, int y, int w, int mx, int my) {
		String text;
		if (st.loading()) {
			text = "Since you were away: asking the Foreman…";
		} else {
			int n = st.digest.goals().size();
			int needs = dev.agentcraft.client.hud.Alerts.line().needsYou();
			text = (compact ? "Away (" : "Since you were away (") + UiBits.clock(st.since) + "): " + UiBits.plural(n, "goal", "goals") + " moved"
				+ (needs > 0 ? " · " + needs + " need" + (needs == 1 ? "s" : "") + " you" : "");
		}
		String goals = compact ? "Goals ›" : "See Goals ›";
		String dis = compact ? "×" : "Dismiss";
		int dw = hub.bw(dis);
		int gw = font().width(goals) + 12;
		Panels.inset(g, x, y, w, 16);
		hub.button(g, "inbox_away_dismiss", dis, x + w - dw - 1, y + 1, dw, false, false, false, mx, my, HubGoals::dismissAway);
		int gx = x + w - dw - 4 - gw;
		chip(g, "away:goals", goals, gx, y + 1, false, mx, my, () -> hub.setTab(HubTab.GOALS));
		g.text(font(), TextUtil.ellipsize(font(), text, gx - x - 10), x + 6, y + 4, UiStyle.CLAY_DARK, false);
		return 20;
	}

	private void drawFilters(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
		Filter f = Inbox.filter();
		String mark = compact ? "All read" : "Mark all read";
		int markW = hub.bw(mark);
		hub.button(g, "inbox_mark_all_read", mark, x + w - markW, y - 2, markW, false, false, false, mx, my, () -> {
			Inbox.markAllRead();
			setNote("Everything is read", false);
		});
		int room = w - markW - 6;
		int cx = x;
		InboxModel.Counts c = Inbox.counts();
		if (f.type() == FilterType.PODIUM) {
			String label = (compact ? "" : "Podium: ") + (f.arg() == null ? "home" : f.arg()) + " ×";
			cx += chip(g, "filter:podium", label, cx, y, true, mx, my, () -> setFilter(Filter.ALL)) + 3;
			String all = compact ? "All" : "All decisions";
			cx += chip(g, "filter:all", all, cx, y, false, mx, my, () -> setFilter(Filter.ALL)) + 3;
			return;
		}
		cx += chip(g, "filter:all", "All", cx, y, f.type() == FilterType.ALL, mx, my, () -> setFilter(Filter.ALL)) + 3;
		String needs = (compact ? "Needs " : "Needs you ") + c.needsYou();
		cx += chip(g, "filter:needs_you", needs, cx, y, f.type() == FilterType.NEEDS_YOU, mx, my, () -> setFilter(Filter.NEEDS_YOU)) + 3;
		// building: cycles the buildings (with items first), then back to all
		String bLabel = f.type() == FilterType.BUILDING ? (compact ? "" : "Building ") + f.arg() + " ▾" : compact ? "Bldg ▾" : "Building ▾";
		if (cx + chipW(bLabel) <= x + room) {
			cx += chip(g, "filter:building", bLabel, cx, y, f.type() == FilterType.BUILDING, mx, my, this::cycleBuilding) + 3;
		}
		String aLabel = f.type() == FilterType.AGENT ? (compact ? "" : "Agent ") + UiBits.agentName(f.arg()) + " ▾" : "Agent ▾";
		if (cx + chipW(aLabel) <= x + room) {
			chip(g, "filter:agent", aLabel, cx, y, f.type() == FilterType.AGENT, mx, my, this::cycleAgent);
		}
	}

	void cycleBuilding() {
		List<String> ids = new ArrayList<>();
		for (Building b : Buildings.all()) {
			ids.add(b.id());
		}
		Filter f = Inbox.filter();
		if (ids.isEmpty()) {
			setNote("No buildings in this world", true);
			return;
		}
		int i = f.type() == FilterType.BUILDING ? ids.indexOf(f.arg()) + 1 : 0;
		setFilter(i >= ids.size() ? Filter.ALL : Filter.building(ids.get(i)));
	}

	void cycleAgent() {
		List<String> ids = Inbox.agentsForFilter();
		Filter f = Inbox.filter();
		if (ids.isEmpty()) {
			setNote("No agents yet", true);
			return;
		}
		int i = f.type() == FilterType.AGENT ? ids.indexOf(f.arg()) + 1 : 0;
		if (i >= ids.size()) {
			setFilter(Filter.ALL);
		} else {
			setFilter(Filter.agent(ids.get(i)));
			select("agent:" + ids.get(i));
			if (compact) {
				detailOpen = false;
			}
		}
	}

	private void drawEmpty(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Panels.inset(g, x, y, w, h);
		Filter f = Inbox.filter();
		String msg;
		if (Foreman.state() == null || !Foreman.state().hasData()) {
			msg = "The Foreman is not connected: decisions, replies and blocked tasks show up here once it is.";
		} else if (f.type() == FilterType.ALL) {
			msg = "Nothing here yet. Decisions your team asks, their replies to you, blocked tasks and PRs that need you collect here.";
		} else if (f.type() == FilterType.PODIUM) {
			msg = "Nothing waits at this podium. \"All decisions\" shows the whole queue.";
		} else {
			msg = "Nothing for this filter. \"All\" shows everything.";
		}
		int ty = y + 10;
		Panels.dot(g, "done", x + 8, ty + 1, false);
		for (String line : TextUtil.wrapPlain(font(), msg, w - 30)) {
			g.text(font(), line, x + 20, ty, UiBits.muted(), false);
			ty += 10;
		}
		if (note != null) {
			g.text(font(), TextUtil.ellipsize(font(), note, w - 16), x + 8, ty + 6, noteError ? UiBits.errorText() : UiBits.muted(), false);
		}
	}

	private void drawList(GuiGraphicsExtractor g, List<Row> rows, @Nullable Row cur, int x, int y, int w, int h, int mx, int my) {
		display.clear();
		Group last = null;
		for (Row r : rows) {
			if (r.item().kind() != Kind.AGENT && r.group() != last) {
				display.add(null);
				last = r.group();
			}
			display.add(r);
		}
		int needsCount = (int) rows.stream().filter(r -> r.group() == Group.NEEDS_YOU && r.item().kind() != Kind.AGENT).count();
		int sel = cur == null ? -1 : display.indexOf(cur);
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		List<Row> disp = List.copyOf(display);
		list.draw(g, x, y, w, h, disp.size(), sel, mx, my, (i, rx, ry, rw) -> {
			Row r = disp.get(i);
			if (r == null) {
				// group header: the group of the next row
				Row next = i + 1 < disp.size() ? disp.get(i + 1) : null;
				Group gr = next == null ? Group.UPDATES : next.group();
				String label = gr.label() + (gr == Group.NEEDS_YOU ? " · " + needsCount : "");
				g.text(font(), label, rx, ry + 6, UiStyle.CLAY_DARK, false);
				Panels.divider(g, rx + font().width(label) + 6, ry + 10, Math.max(0, rw - font().width(label) - 6));
				return "hdr:" + i;
			}
			Item it = r.item();
			if (it.kind() == Kind.AGENT) {
				UiBits.face(g, it.agentId(), rx, ry, 1);
			} else {
				Panels.dot(g, family(it), rx, ry + 1, false);
			}
			int tx = rx + 11;
			int dotW = r.unread() ? 8 : 0;
			g.text(font(), TextUtil.ellipsize(font(), it.title().isBlank() ? kindLabel(it) : it.title(), rw - 11 - dotW), tx, ry, r.group() == Group.NEEDS_YOU
				? ink : muted, false);
			if (r.unread()) {
				g.fill(rx + rw - 5, ry + 2, rx + rw - 1, ry + 6, UiStyle.CLAY);
			}
			g.text(font(), TextUtil.ellipsize(font(), rowSub(it), rw - 11), tx, ry + 10, muted, false);
			return it.key();
		});
	}

	// ------------------------------------------------------------------ detail

	private void drawDetail(GuiGraphicsExtractor g, Item it, int x, int y, int w, int h, int mx, int my) {
		if (!it.key().equals(bodyFor)) {
			bodyFor = it.key();
			bodyScroll.toTop();
			logScroll.toBottom();
			flowOffset = 0;
		}
		int bottom = y + h;
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		// header: kind pill, title, who · goal · building · when
		int pw = UiBits.dotPill(g, font(), family(it), kindLabel(it), x, y - 1, ink);
		String when = it.kind() == Kind.HOLD || it.kind() == Kind.AGENT || it.ts() <= 0 ? "" : UiBits.ago(it.ts());
		int ww = font().width(when);
		g.text(font(), when, x + w - ww, y + 1, muted, false);
		g.text(font(), TextUtil.ellipsize(font(), it.kind() == Kind.DECISION && it.refId() != null ? it.refId() + (it.goalId() != null ? " · goal "
			+ it.goalId() : "") : it.kind() == Kind.BLOCKED || it.kind() == Kind.PR ? "task " + it.refId() + (it.goalId() != null ? " · goal "
			+ it.goalId() : "") : it.goalId() != null ? "goal " + it.goalId() : "", w - pw - ww - 12), x + pw + 6, y + 1, muted, false);
		y += 14;
		if (it.agentId() != null && it.kind() != Kind.AGENT) {
			UiBits.face(g, it.agentId(), x, y, 1);
			String who = UiBits.agentName(it.agentId()) + (it.buildingId() != null ? "  ·  " + it.buildingId() : "");
			g.text(font(), TextUtil.ellipsize(font(), who, w - 12), x + 11, y, UiBits.nameOnLight(it.agentId()), false);
			y += 12;
		}
		needed += 26;
		switch (it.kind()) {
			case DECISION -> drawDecision(g, it, x, y, w, bottom, mx, my);
			case REPLY -> drawReply(g, it, x, y, w, bottom, mx, my);
			case BLOCKED -> drawBlocked(g, it, x, y, w, bottom, mx, my);
			case HOLD -> drawHold(g, it, x, y, w, bottom, mx, my);
			case PR -> drawPr(g, it, x, y, w, bottom, mx, my);
			case AGENT -> drawAgent(g, it, x, y, w, bottom, mx, my);
		}
		if (it.kind() != Kind.DECISION) {
			panel.hide();
			panel.focusText(false); // a hidden text box must not keep the keys
		}
	}

	/** A detail's text: wrapped lines and their colours. */
	private record Body(List<String> lines, List<Integer> colors) {
		Body() {
			this(new ArrayList<>(), new ArrayList<>());
		}
	}

	/** A detail's pinned area (answer panel, reply box, note, actions), laid out for a width. */
	private interface Pinned {
		int height(int w);

		void draw(int x, int y, int w);
	}

	/**
	 * Lays a detail out under its header ({@link DetailLayout}): the body on top, the pinned area at the bottom; when the
	 * body would get fewer than three lines, the whole column scrolls instead (a scrollbar on its right), so nothing is
	 * ever drawn over the header.
	 */
	private void layoutDetail(GuiGraphicsExtractor g, java.util.function.IntFunction<Body> body, Pinned pinned, int x, int y, int w, int bottom) {
		int avail = bottom - y;
		Body b = body.apply(w - 14);
		int ph = pinned.height(w);
		DetailLayout l = DetailLayout.of(avail, ph, b.lines().size(), flowOffset);
		needed += DetailLayout.needed(ph, b.lines().size());
		boolean typing = focus != null || panel.textFocused();
		if (!l.flow()) {
			lastLayout = l;
			flowTyping = typing;
			drawBody(g, b.lines(), b.colors(), x, y, w, y + l.bodyH());
			pinned.draw(x, y + l.pinnedY(), w);
			return;
		}
		// flows: one column at natural height under a scrollbar
		int cw = w - DetailLayout.BAR;
		b = body.apply(cw - 14);
		ph = pinned.height(cw);
		l = DetailLayout.of(avail, ph, b.lines().size(), flowOffset);
		if (typing && !flowTyping) {
			l = DetailLayout.of(avail, ph, b.lines().size(), l.offsetShowingPinned(avail)); // a field took focus: show it
		}
		flowTyping = typing;
		flowOffset = l.offset();
		lastLayout = l;
		flowArea = new int[] {x, y, w, avail};
		int top = y - l.offset();
		clipTop = y;
		clipBottom = bottom;
		g.enableScissor(x, y, x + w, bottom);
		try {
			drawBody(g, b.lines(), b.colors(), x, top, cw, top + l.bodyH());
			pinned.draw(x, top + l.pinnedY(), cw);
		} finally {
			g.disableScissor();
			clipTop = Integer.MIN_VALUE;
			clipBottom = Integer.MAX_VALUE;
		}
		bodyArea = new int[4]; // the column scrolls, not the body
		TextUtil.Scroll bar = new TextUtil.Scroll().update(l.contentH(), avail);
		bar.scrollBy(l.offset() - bar.max());
		Panels.scrollbar(g, x + w - 6, y + 1, avail - 2, bar, false);
	}

	/** A text well from y to {@code bottom}: the lines with their colours (scrolls inside when they do not fit). */
	private void drawBody(GuiGraphicsExtractor g, List<String> lines, List<Integer> colors, int x, int y, int w, int bottom) {
		int areaH = Math.max(12, bottom - y);
		Panels.inset(g, x, y, w, areaH);
		int view = Math.max(1, (areaH - DetailLayout.PAD) / DetailLayout.LINE_H);
		bodyScroll.update(lines.size(), view);
		if (bodyScroll.following() && lines.size() > view && bodyScroll.offset() == bodyScroll.max()) {
			bodyScroll.toTop();
		}
		bodyArea = new int[] {x, y, w, areaH};
		g.enableScissor(x + 1, y + 1, x + w - 1, y + areaH - 1);
		int ly = y + 4;
		for (int i = bodyScroll.offset(); i < Math.min(lines.size(), bodyScroll.offset() + view); i++) {
			g.text(font(), lines.get(i), x + 5, ly, colors.get(i), false);
			ly += DetailLayout.LINE_H;
		}
		g.disableScissor();
		Panels.scrollbar(g, x + w - 7, y + 1, areaH - 2, bodyScroll, false);
	}

	private void wrapInto(List<String> lines, List<Integer> colors, String text, int w, int color) {
		for (String para : text.split("\n", -1)) {
			List<String> wl = TextUtil.wrapPlain(font(), para, w);
			if (wl.isEmpty()) {
				wl = List.of("");
			}
			for (String l : wl) {
				lines.add(l);
				colors.add(color);
			}
		}
	}

	private void wrapInto(Body b, String text, int w, int color) {
		wrapInto(b.lines(), b.colors(), text, w, color);
	}

	private static void blank(Body b) {
		b.lines().add("");
		b.colors().add(UiBits.ink());
	}

	/**
	 * A hub button that only takes clicks when it is wholly inside the detail's visible column (a flowing detail
	 * scrolls buttons under the top bar; those are drawn, clipped, but not clickable).
	 */
	private void button(GuiGraphicsExtractor g, String id, String label, int x, int y, int w, boolean primary, boolean disabled, int mx, int my,
		Runnable r) {
		if (y >= clipTop && y + 20 <= clipBottom) {
			hub.button(g, id, label, x, y, w, primary, disabled, false, mx, my, r);
		} else {
			UiBits.button(g, font(), label, 0, x, y, w, primary, disabled ? UiBits.ButtonState.DISABLED : UiBits.ButtonState.NORMAL, false);
		}
	}

	/** A row of action buttons (right-aligned, wrapping left); returns its height. */
	private int actions(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, List<Object[]> buttons) {
		int bx = x + w;
		int rows = buttons.isEmpty() ? 0 : 1;
		for (Object[] b : buttons) {
			String label = (String) b[1];
			int bw = hub.bw(label);
			if (bx - bw < x) {
				bx = x + w;
				y += 24;
				rows++;
			}
			bx -= bw;
			button(g, (String) b[0], label, bx, y, bw, (Boolean) b[2], (Boolean) b[3], mx, my, (Runnable) b[4]);
			bx -= 4;
		}
		return rows * 24;
	}

	private int actionsHeight(int w, List<Object[]> buttons) {
		int bx = w;
		int rows = buttons.isEmpty() ? 0 : 1;
		for (Object[] b : buttons) {
			int bw = hub.bw((String) b[1]);
			if (bx - bw < 0) {
				bx = w;
				rows++;
			}
			bx -= bw + 4;
		}
		return rows * 24;
	}

	/**
	 * Compact, detail only: the action buttons go into the top bar right of "‹ Inbox" when they fit there (they leave
	 * the pinned area to the answer or reply). Returns true when drawn there.
	 */
	private boolean toTopBar(GuiGraphicsExtractor g, List<Object[]> buttons, int mx, int my) {
		if (topSlot == null || buttons.isEmpty()) {
			return false;
		}
		int total = -4;
		for (Object[] b : buttons) {
			total += hub.bw((String) b[1]) + 4;
		}
		if (total > topSlot[2]) {
			return false;
		}
		actions(g, topSlot[0], topSlot[1], topSlot[2], mx, my, buttons);
		actionsInTopBar = true;
		return true;
	}

	private static Object[] btn(String id, String label, boolean primary, boolean disabled, Runnable r) {
		return new Object[] {id, label, primary, disabled, r};
	}

	private void drawNoteAt(GuiGraphicsExtractor g, int x, int y, int w) {
		if (note != null) {
			g.text(font(), TextUtil.ellipsize(font(), note, w), x, y, noteError ? UiBits.errorText() : note.startsWith(UiBits.CHECK) ? UiBits.okText()
				: UiBits.muted(), false);
		}
	}

	private void drawDecision(GuiGraphicsExtractor g, Item it, int x, int y, int w, int bottom, int mx, int my) {
		Decision d = decisionOf(it);
		if (d == null) {
			panel.hide();
			panel.focusText(false);
			g.text(font(), "This decision is no longer in the Foreman's list.", x, y + 4, UiBits.muted(), false);
			return;
		}
		boolean readOnly = !d.isOpen() || DecisionsFeature.isAnswering(d.id()) || Foreman.state() == null || Foreman.state().isStale();
		List<Object[]> btns = new ArrayList<>();
		if (it.goalId() != null && HubGoals.goal(it.goalId()) != null) {
			btns.add(btn("inbox_open_thread", compact ? "Thread" : "Open thread", false, false, () -> openThread(it)));
		}
		btns.add(btn("inbox_open_decision", compact ? "Full view" : "Decision screen", false, false, () -> openDecisionScreen(d.id())));
		boolean inTop = toTopBar(g, btns, mx, my);
		// body: the question, then the context
		java.util.function.IntFunction<Body> body = tw -> {
			Body b = new Body();
			wrapInto(b, d.question(), tw, UiBits.ink());
			if (!d.isOpen()) {
				String st = d.status() == Protocol.DecisionStatus.CANCELLED ? "withdrawn" : d.answer() != null ? "answered: " + (d.answer().option() != null
					? d.answer().option() : "") + (d.answer().text() != null ? " " + UiBits.oneLine(d.answer().text()) : "") : d.status().wire();
				blank(b);
				wrapInto(b, st, tw, UiBits.okText());
			} else if (DecisionsFeature.isAnswering(d.id())) {
				blank(b);
				b.lines().add("sending your answer…");
				b.colors().add(UiBits.muted());
			}
			if (d.context() != null && !d.context().isBlank()) {
				blank(b);
				wrapInto(b, d.context().strip(), tw, UiBits.muted());
			}
			return b;
		};
		layoutDetail(g, body, new Pinned() {
			@Override
			public int height(int pw) {
				return (d.isOpen() ? panel.height(font(), d, pw, readOnly, true) : 12) + (inTop ? 0 : actionsHeight(pw, btns) + 2);
			}

			@Override
			public void draw(int px, int py, int pw) {
				if (d.isOpen()) {
					py += panel.draw(g, font(), d, px, py, pw, mx, my, readOnly);
				} else {
					panel.hide();
					panel.focusText(false);
					py += 12;
				}
				if (!inTop) {
					actions(g, px, py + 2, pw, mx, my, btns);
				}
			}
		}, x, y, w, bottom);
	}

	private void drawReplyBox(GuiGraphicsExtractor g, Item it, int x, int y, int w, int mx, int my, int lines) {
		String send = "Send";
		int sw = hub.bw(send);
		int fw = w - sw - 4;
		reply.placeholder(it.kind() == Kind.AGENT ? "Message " + UiBits.agentName(it.agentId()) + "… (Ctrl+Enter sends)" : it.goalId() != null
			? "Reply in goal " + it.goalId() + "'s thread… (Ctrl+Enter)" : "Reply to " + UiBits.agentName(it.agentId()) + "… (Ctrl+Enter)");
		int fh = reply.draw(g, font(), x, y, fw, lines, focus == reply);
		button(g, "inbox_reply_send", send, x + w - sw, y + Math.max(0, fh - 20), sw, true, sending || !Foreman.connected(), mx, my, () -> sendReply(it));
	}

	/** The reply box's line cap: one line at compact sizes (it scrolls to keep the caret in view). */
	private int replyLines(boolean agentView) {
		return compact ? 1 : agentView ? 2 : 3;
	}

	private void drawReply(GuiGraphicsExtractor g, Item it, int x, int y, int w, int bottom, int mx, int my) {
		List<Object[]> btns = new ArrayList<>();
		if (it.goalId() != null && HubGoals.goal(it.goalId()) != null) {
			btns.add(btn("inbox_open_thread", compact ? "Thread" : "Open thread", false, false, () -> openThread(it)));
		}
		btns.add(btn("inbox_open_card", compact ? "Card" : "Open card", false, false, () -> openCard(it.agentId())));
		boolean inTop = toTopBar(g, btns, mx, my);
		int lines = replyLines(false);
		int noteH = note != null ? 11 : 0;
		layoutDetail(g, tw -> {
			Body b = new Body();
			wrapInto(b, it.detail(), tw, UiBits.ink());
			return b;
		}, new Pinned() {
			@Override
			public int height(int pw) {
				return reply.height(font(), pw - hub.bw("Send") - 4, lines) + 2 + noteH + (inTop ? 0 : actionsHeight(pw, btns));
			}

			@Override
			public void draw(int px, int py, int pw) {
				drawReplyBox(g, it, px, py, pw, mx, my, lines);
				py += reply.height(font(), pw - hub.bw("Send") - 4, lines) + 2;
				if (noteH > 0) {
					drawNoteAt(g, px, py + 1, pw);
					py += noteH;
				}
				if (!inTop) {
					actions(g, px, py, pw, mx, my, btns);
				}
			}
		}, x, y, w, bottom);
	}

	private void drawFacts(Body b, String[][] facts, int w) {
		for (String[] f : facts) {
			if (f[1] == null || f[1].isBlank()) {
				continue;
			}
			wrapInto(b, f[0] + ": " + f[1], w, UiBits.ink());
		}
	}

	/** The pinned area of the kinds whose pinned part is a note and the actions (blocked, PR, hold). */
	private Pinned noteAndActions(GuiGraphicsExtractor g, List<Object[]> btns, boolean inTop, boolean withNote, int mx, int my) {
		int noteH = withNote && note != null ? 11 : 0;
		return new Pinned() {
			@Override
			public int height(int pw) {
				return noteH + (inTop ? 0 : actionsHeight(pw, btns));
			}

			@Override
			public void draw(int px, int py, int pw) {
				if (noteH > 0) {
					drawNoteAt(g, px, py, pw);
					py += noteH;
				}
				if (!inTop) {
					actions(g, px, py, pw, mx, my, btns);
				}
			}
		};
	}

	private void drawBlocked(GuiGraphicsExtractor g, Item it, int x, int y, int w, int bottom, int mx, int my) {
		ForemanState s = Foreman.state();
		Task t = s == null || it.refId() == null ? null : s.task(it.refId());
		String tid = it.refId();
		List<Object[]> btns = new ArrayList<>();
		btns.add(btn("inbox_retry", "Retry", true, sending || !Foreman.connected() || t == null || t.status() != Protocol.TaskStatus.BLOCKED, () -> retry(
			tid)));
		btns.add(btn("inbox_open_task", compact ? "Task" : "Open task", false, t == null, () -> openTask(tid)));
		if (it.agentId() != null) {
			btns.add(btn("inbox_open_card", compact ? "Card" : "Open card", false, false, () -> openCard(it.agentId())));
		}
		boolean inTop = toTopBar(g, btns, mx, my);
		layoutDetail(g, tw -> {
			Body b = new Body();
			wrapInto(b, it.title(), tw, UiBits.ink());
			blank(b);
			wrapInto(b, "Why: " + (it.detail().isBlank() ? "no reason given" : it.detail()), tw, UiBits.errorText());
			if (t != null) {
				drawFacts(b, new String[][] {{"Assignee", t.assignee() == null ? "nobody" : UiBits.agentName(t.assignee())}, {"Repo", t.repoId()},
					{"Branch", t.branch()}, {"Updated", UiBits.ago(t.updatedAt())}}, tw);
			}
			blank(b);
			wrapInto(b, "Retry puts it back on the board for its worker to try again; the reason above goes with it.", tw, UiBits.muted());
			return b;
		}, noteAndActions(g, btns, inTop, true, mx, my), x, y, w, bottom);
	}

	private void drawHold(GuiGraphicsExtractor g, Item it, int x, int y, int w, int bottom, int mx, int my) {
		InboxModel.Hold hold = new InboxModel.Hold(it.subKind(), it.until(), it.detail());
		List<Object[]> btns = new ArrayList<>();
		if ("usage".equals(hold.reason())) {
			btns.add(btn("inbox_usage", compact ? "Usage" : "Usage settings", false, false, () -> {
				hub.setTab(HubTab.SETTINGS);
				hub.settings.setGroup("usage");
			}));
		}
		btns.add(btn("inbox_status", compact ? "Status" : "Status tab", false, false, () -> hub.setTab(HubTab.STATUS)));
		boolean inTop = toTopBar(g, btns, mx, my);
		layoutDetail(g, tw -> {
			Body b = new Body();
			wrapInto(b, it.title() + ": " + InboxModel.holdText(hold, ZoneId.systemDefault()), tw, UiBits.ink());
			for (String p : InboxModel.holdExplain(hold, ZoneId.systemDefault())) {
				blank(b);
				wrapInto(b, p, tw, UiBits.muted());
			}
			return b;
		}, noteAndActions(g, btns, inTop, false, mx, my), x, y, w, bottom);
	}

	private void drawPr(GuiGraphicsExtractor g, Item it, int x, int y, int w, int bottom, int mx, int my) {
		ForemanState s = Foreman.state();
		Task t = s == null || it.refId() == null ? null : s.task(it.refId());
		String tid = it.refId();
		List<Object[]> btns = new ArrayList<>();
		if (it.goalId() != null && HubGoals.goal(it.goalId()) != null) {
			btns.add(btn("inbox_open_tasks", compact ? "Tasks" : "Goal's tasks", true, false, () -> openGoalTasks(it)));
		}
		btns.add(btn("inbox_refresh_prs", compact ? "Refresh" : "Refresh PRs", false, sending || !Foreman.connected(), this::refreshPrs));
		btns.add(btn("inbox_open_task", compact ? "Task" : "Open task", false, t == null, () -> openTask(tid)));
		boolean inTop = toTopBar(g, btns, mx, my);
		layoutDetail(g, tw -> {
			Body b = new Body();
			wrapInto(b, it.title(), tw, UiBits.ink());
			wrapInto(b, "Needs you: " + it.detail(), tw, UiBits.errorText());
			if (t != null && t.pr() != null) {
				Protocol.TaskPr pr = t.pr();
				drawFacts(b, new String[][] {{"State", pr.status()}, {"Checks", pr.checks()}, {"Threads", pr.threads() == null ? null : pr.threads().open()
					+ " open" + (pr.threads().newCount() != null && pr.threads().newCount() > 0 ? ", " + pr.threads().newCount() + " new" : "")}, {"Branch",
					pr.branch() == null ? null : pr.branch() + (pr.target() != null ? " → " + pr.target() : "")}, {"Link", pr.url()}, {"Updated",
					pr.updatedAt() > 0 ? UiBits.ago(pr.updatedAt()) : null}}, tw);
			}
			return b;
		}, noteAndActions(g, btns, inTop, true, mx, my), x, y, w, bottom);
	}

	/** The agent view: card summary on top, the full log (paged back on scroll up), a message box and Open card. */
	private void drawAgent(GuiGraphicsExtractor g, Item it, int x, int y, int w, int bottom, int mx, int my) {
		String agentId = Objects.requireNonNull(it.agentId());
		ForemanState s = Foreman.state();
		Protocol.Agent a = s == null ? null : s.agent(agentId);
		AgentLogView lv = log(agentId);
		lv.maintain();
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		// bottom first (the log gets what is left): message box, note, buttons (compact: buttons in the top bar)
		List<Object[]> btns = new ArrayList<>();
		btns.add(btn("inbox_open_card", compact ? "Card" : "Open card", false, a == null, () -> openCard(agentId)));
		if (lv.more()) {
			btns.add(btn("inbox_log_older", lv.loading() ? "Loading…" : compact ? "Older" : "Load older", false, lv.loading(), lv::loadOlder));
		}
		boolean inTop = toTopBar(g, btns, mx, my);
		int lines = replyLines(true);
		int fh = reply.height(font(), w - hub.bw("Send") - 4, lines);
		int actH = inTop ? 0 : actionsHeight(w, btns);
		int noteH = note != null ? 11 : 0;
		int pinned = fh + 4 + noteH + actH;
		int minLog = DetailLayout.bodyHeight(DetailLayout.MIN_LINES);
		// the activity line only when the log keeps its three lines with it
		boolean actLine = a != null && bottom - y - 24 - 12 - pinned - 4 >= minLog;
		needed += 24 + minLog + pinned + 4;
		// summary
		UiBits.framedPortrait(g, agentId, x, y, 1);
		int tx = x + 26;
		g.text(font(), TextUtil.ellipsize(font(), UiBits.agentName(agentId), w - 26), tx, y + 1, UiBits.nameOnLight(agentId), false);
		String sub = a == null ? "not on the team" : (a.title() != null ? a.title() + " · " : "") + (a.role() == Protocol.AgentRole.LEAD ? "lead" : "worker")
			+ (!a.isActive() ? " · off shift" : a.isPaused() ? " · paused" : "");
		if (a != null) {
			int sw = UiBits.dotPillWidth(font(), a.state().wire().replace('_', ' '));
			UiBits.dotPill(g, font(), a.state().family(), a.state().wire().replace('_', ' '), x + w - sw, y, ink);
		}
		g.text(font(), TextUtil.ellipsize(font(), sub, w - 26 - 60), tx, y + 11, muted, false);
		y += 24;
		if (actLine) {
			String act = a.activity().isBlank() ? "-" : a.activity();
			Task t = a.taskId() == null || s == null ? null : s.task(a.taskId());
			String line = act + (t != null ? "  ·  " + t.id() + " " + UiBits.oneLine(t.title()) : "") + (it.buildingId() != null ? "  ·  " + it.buildingId() : "");
			g.text(font(), TextUtil.ellipsize(font(), line, w), x, y, ink, false);
			y += 12;
		}
		// the log keeps at least a line even when the box is short; the pinned area never climbs above it
		int logBottom = Math.max(y + DetailLayout.bodyHeight(1), bottom - pinned - 4);
		drawLog(g, lv, x, y, w, logBottom);
		int py = logBottom + 4;
		drawReplyBox(g, it, x, py, w, mx, my, lines);
		py += fh + 2;
		if (noteH > 0) {
			drawNoteAt(g, x, py + 1, w);
			py += noteH;
		}
		if (!inTop) {
			actions(g, x, py, w, mx, my, btns);
		}
	}

	/** A wrapped log line; its colour is resolved when drawn (a theme change shows at once). */
	private record LogLine(String time, String text, Protocol.LogKind kind, char sign) {
		int color() {
			return switch (kind) {
				case TOOL -> UiStyle.color("paper.path", 0xFF6C5415);
				case RESULT -> UiBits.muted();
				case ERROR -> UiBits.errorText();
				case DIFF -> sign == '+' ? UiStyle.color("paper.add_fg", 0xFF455746) : sign == '-' ? UiStyle.color("paper.del_fg", 0xFF873C2A)
					: UiBits.muted();
				default -> UiBits.ink();
			};
		}
	}

	/** The wrapped log lines, rebuilt only when the entries or the width change (not every frame). */
	private List<LogLine> wrappedLog(AgentLogView lv, int textW) {
		String key = lv.agentId + "/" + textW + "/" + lv.cacheKey();
		if (key.equals(logKey)) {
			logRebuilt = false;
			return logLines;
		}
		List<LogEntry> entries = lv.entries();
		List<LogLine> lines = new ArrayList<>();
		int prepended = lv.takePrepended();
		int prependedRows = 0;
		for (int i = 0; i < entries.size(); i++) {
			LogEntry e = entries.get(i);
			int before = lines.size();
			boolean first = true;
			int n = 0;
			for (String para : e.text().split("\n")) {
				char sign = e.kind() == Protocol.LogKind.DIFF && !para.isEmpty() ? para.charAt(0) : ' ';
				for (String l : TextUtil.wrapPlain(font(), para, textW)) {
					if (n++ >= 8) {
						break;
					}
					lines.add(new LogLine(first ? UiBits.clock(e.ts()) : "", l, e.kind(), sign));
					first = false;
				}
				if (n >= 8) {
					lines.add(new LogLine("", "…", Protocol.LogKind.RESULT, ' '));
					break;
				}
			}
			if (i < prepended) {
				prependedRows += lines.size() - before;
			}
		}
		logKey = key;
		logLines = lines;
		logPrependedRows = prependedRows;
		logRebuilt = true;
		logRebuilds++;
		return lines;
	}

	private void drawLog(GuiGraphicsExtractor g, AgentLogView lv, int x, int y, int w, int bottom) {
		int areaH = Math.max(14, bottom - y);
		Panels.inset(g, x, y, w, areaH);
		logArea = new int[] {x, y, w, areaH};
		int timeW = font().width("00:00 ");
		int textW = w - 14 - timeW;
		List<LogLine> lines = wrappedLog(lv, textW);
		int view = Math.max(1, (areaH - 6) / 10);
		logScroll.update(lines.size(), view);
		if (logRebuilt && logPrependedRows > 0 && !logScroll.following()) {
			logScroll.scrollBy(logPrependedRows); // keep the lines that were on screen where they were
		}
		logFrom = logScroll.offset();
		logRowsShown = view;
		g.enableScissor(x + 1, y + 1, x + w - 1, y + areaH - 1);
		int ly = y + 4;
		if (lines.isEmpty()) {
			g.text(font(), lv.loading() ? "Loading the log…" : "No log yet", x + 5, ly, UiBits.muted(), false);
		}
		for (int i = logScroll.offset(); i < Math.min(lines.size(), logScroll.offset() + view); i++) {
			LogLine l = lines.get(i);
			g.text(font(), l.time(), x + 5, ly, UiBits.muted(), false);
			g.text(font(), l.text(), x + 5 + timeW, ly, l.color(), false);
			ly += 10;
		}
		g.disableScissor();
		Panels.scrollbar(g, x + w - 7, y + 1, areaH - 2, logScroll, false);
		// the top edge says what is above
		String top = lv.loading() ? "loading older lines…" : lv.error() != null ? lv.error() : lv.more() && logScroll.offset() == 0
			? "scroll up for older lines" : null;
		if (top != null) {
			String t = TextUtil.ellipsize(font(), top, w - 20);
			int tw = font().width(t) + 8;
			Panels.sprite(g, Kit.PILL, x + w - 10 - tw, y + 2, tw, 11);
			g.text(font(), t, x + w - 6 - tw, y + 4, lv.error() != null && !lv.unsupported() ? UiBits.errorText() : UiBits.muted(), false);
		}
	}

	// ------------------------------------------------------------------ DevBridge

	@Override
	public JsonObject state() {
		JsonObject o = new JsonObject();
		Filter f = Inbox.filter();
		o.addProperty("filter", f.id());
		o.addProperty("selected", selected);
		o.addProperty("mode", shownMode);
		o.addProperty("detailOpen", detailOpen);
		o.addProperty("focus", focus());
		o.addProperty("note", note);
		o.addProperty("noteError", noteError);
		o.addProperty("reply", reply.value());
		List<Row> rs = rows();
		JsonArray items = new JsonArray();
		for (Row r : rs) {
			items.add(rowJson(r));
		}
		o.add("items", items);
		JsonObject groups = new JsonObject();
		groups.addProperty("needs_you", rs.stream().filter(r -> r.group() == Group.NEEDS_YOU && r.item().kind() != Kind.AGENT).count());
		groups.addProperty("updates", rs.stream().filter(r -> r.group() == Group.UPDATES).count());
		o.add("groups", groups);
		Item it = currentItem();
		o.add("item", it == null ? null : rowJson(current(rs)));
		Decision d = it == null ? null : decisionOf(it);
		o.add("panel", d == null ? null : panel.state());
		if (it != null && it.kind() == Kind.AGENT && it.agentId() != null) {
			o.add("log", log(it.agentId()).state(logFrom, logRowsShown));
		} else {
			o.add("log", null);
		}
		JsonArray chips = new JsonArray();
		chipIds.forEach(chips::add);
		o.add("chips", chips);
		o.add("layout", layout());
		return o;
	}

	static JsonObject rowJson(@Nullable Row r) {
		if (r == null) {
			return new JsonObject();
		}
		Item it = r.item();
		JsonObject j = new JsonObject();
		j.addProperty("key", it.key());
		j.addProperty("kind", it.kind().id());
		j.addProperty("group", r.group().id());
		j.addProperty("unread", r.unread());
		j.addProperty("ts", it.ts());
		j.addProperty("agentId", it.agentId());
		j.addProperty("goalId", it.goalId());
		j.addProperty("buildingId", it.buildingId());
		j.addProperty("title", it.title());
		j.addProperty("detail", it.detail().length() > 200 ? it.detail().substring(0, 200) + "…" : it.detail());
		j.addProperty("ref", it.refId());
		j.addProperty("open", it.open());
		j.addProperty("podium", it.podium());
		j.addProperty("subKind", it.subKind());
		j.addProperty("until", it.until());
		return j;
	}

	JsonObject layout() {
		JsonObject l = new JsonObject();
		var mc = hub.mc();
		l.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth());
		l.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
		l.addProperty("guiScale", mc.getWindow().getGuiScale());
		l.addProperty("compact", compact);
		l.addProperty("width", width);
		l.addProperty("needed", needed);
		l.addProperty("available", available);
		l.addProperty("overflow", needed > available);
		JsonObject detail = new JsonObject();
		DetailLayout dl = lastLayout;
		detail.addProperty("flow", dl != null && dl.flow());
		detail.addProperty("offset", dl == null ? 0 : dl.offset());
		detail.addProperty("max", dl == null ? 0 : dl.maxOffset());
		detail.addProperty("bodyH", dl == null ? 0 : dl.bodyH());
		detail.addProperty("pinnedY", dl == null ? 0 : dl.pinnedY());
		detail.addProperty("contentH", dl == null ? 0 : dl.contentH());
		detail.addProperty("actionsInTopBar", actionsInTopBar);
		detail.addProperty("fieldLines", compact ? 1 : AnswerPanel.Options.EMBEDDED.fieldLines());
		l.add("detail", detail);
		l.addProperty("logRebuilds", logRebuilds);
		JsonObject tabs = new JsonObject();
		tabs.addProperty("needed", hub.tabStripNeeded());
		tabs.addProperty("available", hub.tabStripAvailable());
		tabs.addProperty("overflow", hub.tabStripNeeded() > hub.tabStripAvailable());
		l.add("tabStrip", tabs);
		return l;
	}

	/** Presses a chip drawn last frame by id; false when there is none. */
	boolean pressChip(String id) {
		for (int i = 0; i < chipIds.size(); i++) {
			if (chipIds.get(i).equals(id)) {
				chipActions.get(i).run();
				return true;
			}
		}
		return false;
	}
}
