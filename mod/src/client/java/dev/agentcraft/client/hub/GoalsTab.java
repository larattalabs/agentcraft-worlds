package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.diff.ReviewKit;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.permissions.PermissionBody;
import dev.agentcraft.client.taskwall.TaskScreen;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hub.GoalLogic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The hub's Goals tab (docs/HUB.md "Repos and Goals tabs"): goals newest first (status, progress, repos,
 * lead, PRs, an unread dot), a building filter, the "since you were away" digest, the new goal form, and a
 * goal's detail with four views: <b>Thread</b> (its feed items and decisions in order, decisions answerable
 * inline with the decision screen's guards, a multi-line message box that sends {@code goal.message} with
 * Ctrl+Enter and shows "sending…" at once), <b>Plan</b> (the plan note wrapped; Edit -> Save sends
 * {@code goal.plan}), <b>Instructions</b> (add / edit / remove -> {@code goal.instructions}) and <b>Tasks</b>
 * (status, assignee, PR; opens the task screen), plus a two-step Cancel goal.
 *
 * <p>Compact (narrow or short content area, GUI scale 4 at 1080p): the list or the detail, never both,
 * with a "‹ Goals" back button; the layout's needed vs available height is in {@link #state()}.
 */
final class GoalsTab implements HubPane {
	enum View {
		THREAD, PLAN, INSTRUCTIONS, TASKS;

		String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		static @Nullable View parse(@Nullable String s) {
			for (View v : values()) {
				if (v.id().equalsIgnoreCase(s)) {
					return v;
				}
			}
			return null;
		}
	}

	/** Option keys / clicks on a decision are ignored this long after it shows up (the decision screen's ARM_MS). */
	static final long ARM_MS = 350;
	static final long CONFIRM_MS = 6000;
	private static final int TOP_H = 24;

	private final HubScreen hub;
	private final PaneList goalList = new PaneList(22);
	private final PaneList taskList = new PaneList(22);
	private final HubField message = new HubField("message", 4000, true, "Message the lead about this goal (Ctrl+Enter sends)");
	private final HubField planEditor = new HubField("plan", 20000, true, "The plan, in markdown");
	private final HubField instruction = new HubField("instruction", 500, false, "A standing instruction, e.g. keep the API backwards compatible");
	private final HubField formText = new HubField("goal_text", 4000, true, "What should be done?");
	private final HubField formBranch = new HubField("goal_branch", 200, false, "optional: an existing branch to continue");
	private final HubField formInstructions = new HubField("goal_instructions", 4000, true, "optional, one per line");
	private @Nullable HubField focus;

	private @Nullable String selected;
	private View view = View.THREAD;
	private boolean detailOpen;
	private boolean formOpen;
	/** "repo:<id>" or "building:<id>" (null = the first repo). */
	private @Nullable String formTarget;
	private @Nullable String buildingFilter;
	private boolean planEditing;
	private int instrEditing = -1;
	private @Nullable String armedCancel;
	private long armedAt;
	private final Map<String, Long> decisionSeenAt = new HashMap<>();
	private @Nullable String confirmReject;
	private long confirmRejectUntil;
	private @Nullable String note;
	private boolean noteError;
	private @Nullable String formNote;
	private boolean formNoteError;
	private boolean sending;
	private final TextUtil.Scroll threadScroll = new TextUtil.Scroll();
	private final TextUtil.Scroll planScroll = new TextUtil.Scroll();
	private int[] threadArea = new int[4];
	private int[] planArea = new int[4];
	private final List<int[]> chipRects = new ArrayList<>();
	private final List<Runnable> chipActions = new ArrayList<>();
	private final List<String> chipIds = new ArrayList<>();

	// layout (DevBridge)
	private boolean compact;
	private int needed;
	private int available;
	private String shownMode = "list";

	GoalsTab(HubScreen hub) {
		this.hub = hub;
	}

	private Font font() {
		return hub.font();
	}

	// ------------------------------------------------------------------ state

	@Nullable String selected() {
		return selected;
	}

	View view() {
		return view;
	}

	boolean formOpen() {
		return formOpen;
	}

	/** Opens a goal's detail (in compact mode instead of the list). */
	void open(String goalId, @Nullable View v) {
		if (!goalId.equals(selected)) {
			planEditing = false;
			instrEditing = -1;
			armedCancel = null;
			note = null;
			threadScroll.update(0, 1);
			planScroll.update(0, 1);
			taskList.reset();
		}
		selected = goalId;
		formOpen = false;
		detailOpen = true;
		if (v != null) {
			view = v;
		}
		unfocus();
		Goal g = HubGoals.goal(goalId);
		if (g != null) {
			HubGoals.openGoal(g);
		}
		HubGoals.view(goalId);
	}

	/** Back to the list (compact) / closes the form. */
	void back() {
		unfocus();
		if (formOpen) {
			formOpen = false;
			return;
		}
		detailOpen = false;
		HubGoals.view(null);
	}

	void setView(View v) {
		if (v != view) {
			unfocus();
			view = v;
			planEditing = false;
			instrEditing = -1;
		}
	}

	/** Opens the new goal form, optionally for a repo or a building. */
	void newGoal(@Nullable String repoId, @Nullable String buildingId) {
		formOpen = true;
		formNote = null;
		if (buildingId != null) {
			formTarget = "building:" + buildingId;
		} else if (repoId != null) {
			formTarget = "repo:" + repoId;
		}
		focus(formText);
	}

	void setFilter(@Nullable String buildingId) {
		buildingFilter = buildingId;
		goalList.reset();
	}

	@Nullable String filter() {
		return buildingFilter;
	}

	/** Fills the form (DevBridge). */
	void fillForm(@Nullable String text, @Nullable String repoId, @Nullable String buildingId, @Nullable String branch, @Nullable String instructions) {
		if (!formOpen) {
			newGoal(repoId, buildingId);
		} else if (buildingId != null) {
			formTarget = "building:" + buildingId;
		} else if (repoId != null) {
			formTarget = "repo:" + repoId;
		}
		if (text != null) {
			formText.set(text);
		}
		if (branch != null) {
			formBranch.set(branch);
		}
		if (instructions != null) {
			formInstructions.set(instructions);
		}
	}

	private void focus(@Nullable HubField f) {
		if (f == focus) {
			return;
		}
		focus = f;
		if (f != null) {
			f.model.touch();
		}
		hub.textFocus(f != null);
	}

	@Override
	public @Nullable String focus() {
		return focus == null ? null : focus.id;
	}

	@Override
	public void unfocus() {
		focus(null);
	}

	@Override
	public void shown(boolean on) {
		HubGoals.goalsTabShown(on);
		if (on) {
			HubGoals.checkAway();
			if (detailOpen && selected != null) {
				HubGoals.view(selected);
			}
		} else {
			HubGoals.view(null);
		}
	}

	private void setNote(@Nullable String n, boolean error) {
		note = n;
		noteError = error;
	}

	private @Nullable Goal current(List<Goal> list) {
		for (Goal g : list) {
			if (g.id().equals(selected)) {
				return g;
			}
		}
		Goal g = HubGoals.goal(selected);
		if (g != null) {
			return g; // filtered out of the list but still open
		}
		if (list.isEmpty()) {
			selected = null;
			return null;
		}
		selected = list.get(0).id();
		return list.get(0);
	}

	// ------------------------------------------------------------------ actions

	/** Ctrl+Enter / Send: the message box's text as {@code goal.message}. */
	CompletableFuture<HubGoals.Note> sendMessage() {
		String id = selected;
		if (id == null) {
			return CompletableFuture.completedFuture(HubGoals.Note.failed("No goal open"));
		}
		String text = message.value();
		if (text.isBlank()) {
			setNote("Type a message first", true);
			focus(message);
			return CompletableFuture.completedFuture(HubGoals.Note.failed("Type a message first"));
		}
		HubGoals.clearFailed(id);
		message.set("");
		threadScroll.scrollBy(1_000_000); // follow: the "sending…" line is at the bottom
		setNote(null, false);
		return HubGoals.message(id, text).thenApply(n -> {
			if (!n.ok()) {
				setNote(n.message(), true);
				if (message.value().isEmpty()) {
					message.set(text.strip()); // give the text back to retry
				}
			}
			return n;
		});
	}

	CompletableFuture<HubGoals.Note> savePlan() {
		String id = selected;
		if (id == null) {
			return CompletableFuture.completedFuture(HubGoals.Note.failed("No goal open"));
		}
		sending = true;
		setNote("Saving the plan…", false);
		return HubGoals.plan(id, planEditor.value()).thenApply(n -> {
			sending = false;
			setNote(n.message(), !n.ok());
			if (n.ok()) {
				planEditing = false;
				unfocus();
			}
			return n;
		});
	}

	void editPlan() {
		Goal g = HubGoals.goal(selected);
		Protocol.MemoryEntry m = g == null ? null : HubGoals.plan(g);
		planEditor.set(m == null ? "" : m.body());
		planEditing = true;
		focus(planEditor);
	}

	void cancelPlan() {
		planEditing = false;
		unfocus();
	}

	private List<String> instructionsOf(@Nullable Goal g) {
		return g == null || g.instructions() == null ? List.of() : g.instructions();
	}

	/** Add (or, while editing one, replace) the instruction in the field; sends the whole list. */
	CompletableFuture<HubGoals.Note> commitInstruction(@Nullable String textOverride) {
		Goal g = HubGoals.goal(selected);
		if (g == null) {
			return CompletableFuture.completedFuture(HubGoals.Note.failed("No goal open"));
		}
		String text = (textOverride != null ? textOverride : instruction.value()).strip();
		if (text.isEmpty()) {
			setNote("Type the instruction first", true);
			return CompletableFuture.completedFuture(HubGoals.Note.failed("Type the instruction first"));
		}
		List<String> list = new ArrayList<>(instructionsOf(g));
		if (instrEditing >= 0 && instrEditing < list.size()) {
			list.set(instrEditing, text);
		} else {
			list.add(text);
		}
		return sendInstructions(g.id(), list, true);
	}

	CompletableFuture<HubGoals.Note> removeInstruction(int index) {
		Goal g = HubGoals.goal(selected);
		List<String> list = new ArrayList<>(instructionsOf(g));
		if (g == null || index < 0 || index >= list.size()) {
			return CompletableFuture.completedFuture(HubGoals.Note.failed("No instruction " + index));
		}
		list.remove(index);
		instrEditing = -1;
		return sendInstructions(g.id(), list, false);
	}

	void editInstruction(int index) {
		List<String> list = instructionsOf(HubGoals.goal(selected));
		if (index >= 0 && index < list.size()) {
			instrEditing = index;
			instruction.set(list.get(index));
			focus(instruction);
		}
	}

	private CompletableFuture<HubGoals.Note> sendInstructions(String goalId, List<String> list, boolean clearField) {
		sending = true;
		setNote("Saving instructions…", false);
		return HubGoals.instructions(goalId, list).thenApply(n -> {
			sending = false;
			setNote(n.message(), !n.ok());
			if (n.ok()) {
				instrEditing = -1;
				if (clearField) {
					instruction.set("");
				}
			}
			return n;
		});
	}

	/** Cancel goal: the first call arms (returns null), a second within {@link #CONFIRM_MS} sends {@code goal.cancel}. */
	@Nullable CompletableFuture<HubGoals.Note> cancelClick() {
		String id = selected;
		if (id == null) {
			return null;
		}
		if (id.equals(armedCancel) && System.currentTimeMillis() - armedAt < CONFIRM_MS) {
			armedCancel = null;
			sending = true;
			setNote("Cancelling " + id + "…", false);
			return HubGoals.cancel(id).thenApply(n -> {
				sending = false;
				setNote(n.message(), !n.ok());
				return n;
			});
		}
		armedCancel = id;
		armedAt = System.currentTimeMillis();
		setNote("Click Confirm cancel to stop " + id + "'s open tasks (running workers stop, worktrees are kept)", true);
		return null;
	}

	private boolean cancelArmed() {
		return selected != null && selected.equals(armedCancel) && System.currentTimeMillis() - armedAt < CONFIRM_MS;
	}

	/** The form's repos: a repo target -> [repo]; a building -> all its repos (wing order). */
	List<String> formRepos() {
		String t = formTarget;
		if (t == null) {
			ForemanState s = Foreman.state();
			if (s != null && !s.repos().isEmpty()) {
				return List.of(s.repos().keySet().iterator().next());
			}
			return List.of();
		}
		if (t.startsWith("building:")) {
			Building b = Buildings.get(t.substring(9));
			return b == null ? List.of() : List.copyOf(b.repos());
		}
		return List.of(t.substring(5));
	}

	CompletableFuture<HubGoals.Note> submitForm() {
		List<String> repos = formRepos();
		if (formText.value().isBlank()) {
			formNote = "Say what the goal is";
			formNoteError = true;
			focus(formText);
			return CompletableFuture.completedFuture(HubGoals.Note.failed(formNote));
		}
		sending = true;
		formNote = "Submitting…";
		formNoteError = false;
		List<String> instr = GoalLogic.instructionLines(formInstructions.value());
		String branch = formBranch.value().isBlank() ? null : formBranch.value().strip();
		return HubGoals.submit(formText.value(), repos, branch, instr).thenApply(n -> {
			sending = false;
			if (!n.ok()) {
				formNote = n.message();
				formNoteError = true;
				return n;
			}
			String goalId = n.result() != null && n.result().has("goalId") ? n.result().get("goalId").getAsString() : null;
			formText.set("");
			formBranch.set("");
			formInstructions.set("");
			formNote = null;
			formOpen = false;
			if (goalId != null) {
				open(goalId, View.THREAD);
				boolean dropped = (branch != null || !instr.isEmpty()) && HubGoals.goal(goalId) != null
					&& HubGoals.goal(goalId).instructions() == null && HubGoals.goal(goalId).branch() == null;
				setNote("Submitted " + goalId + (dropped ? " (this Foreman ignored the branch and instructions: they need a newer Foreman)" : ""),
					dropped);
			}
			return n;
		});
	}

	/** Answers a decision from the thread with the decision screen's guards. Returns the outcome note. */
	CompletableFuture<String> answer(Decision d, @Nullable String option) {
		if (!Foreman.connected() || Foreman.state().isStale()) {
			return done("Foreman offline: answers are disabled until it reconnects", true);
		}
		if (!d.isOpen() || DecisionsFeature.isAnswering(d.id())) {
			return done(d.id() + " is already answered", false);
		}
		long seenAt = decisionSeenAt.getOrDefault(d.id(), 0L);
		if (Util.getMillis() - seenAt < ARM_MS) {
			return done(d.id() + " just came up: press again to answer it", false);
		}
		String text = null;
		if (option == null) {
			text = message.value().strip();
			if (text.isEmpty()) {
				focus(message);
				return done("Type the answer in the message box, then Answer", true);
			}
		} else if (d.kind() == DecisionKind.MERGE && option.equals(Protocol.REQUEST_CHANGES)) {
			text = message.value().strip();
			if (text.isEmpty()) {
				focus(message);
				return done("Type the feedback for the worker in the message box first, then Request changes", true);
			}
		} else if (d.kind() == DecisionKind.MERGE && option.equals(Protocol.REJECT)) {
			if (!d.id().equals(confirmReject) || Util.getMillis() > confirmRejectUntil) {
				confirmReject = d.id();
				confirmRejectUntil = Util.getMillis() + 3000;
				return done("Reject abandons the branch: press Reject again", true);
			}
		} else if (d.kind() == DecisionKind.QUESTION && !message.value().isBlank()) {
			text = message.value().strip(); // an option with a note, like the decision screen's text box
		}
		confirmReject = null;
		String label = option == null ? "your answer" : d.kind() == DecisionKind.PERMISSION ? PermissionBody.buttonLabel(option) : option;
		setNote("Sending " + d.id() + ": " + label + "…", false);
		String sentText = text;
		if (sentText != null) {
			message.set("");
		}
		return DecisionsFeature.answer(d.id(), option, sentText).thenApply(err -> {
			if (err != null) {
				if (sentText != null && message.value().isEmpty()) {
					message.set(sentText);
				}
				setNote(d.id() + " was not sent: " + err, true);
				return note;
			}
			setNote(UiBits.CHECK + " " + d.id() + ": " + label, false);
			return note;
		});
	}

	private CompletableFuture<String> done(String msg, boolean error) {
		setNote(msg, error);
		return CompletableFuture.completedFuture(msg);
	}

	void openTask(String taskId) {
		hub.mc().gui.setScreen(new TaskScreen(taskId).withParent(hub));
	}

	void openDecision(String decisionId) {
		DecisionsFeature.openQueue(decisionId, hub);
	}

	// ------------------------------------------------------------------ input

	private List<HubField> fieldsInContext() {
		if (formOpen) {
			return List.of(formText, formInstructions, formBranch);
		}
		if (selected == null) {
			return List.of();
		}
		return switch (view) {
			case THREAD -> List.of(message);
			case PLAN -> planEditing ? List.of(planEditor) : List.of();
			case INSTRUCTIONS -> List.of(instruction);
			case TASKS -> List.of();
		};
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (focus != null) {
			if (e.isEscape()) {
				if (focus == planEditor) {
					cancelPlan();
				} else {
					unfocus();
				}
				return true;
			}
			if (k == InputConstants.KEY_TAB) {
				List<HubField> fs = fieldsInContext();
				int i = fs.indexOf(focus);
				focus(fs.isEmpty() ? null : fs.get(Math.floorMod(i + (e.hasShiftDown() ? -1 : 1), fs.size())));
				return true;
			}
			if (TextKeys.isEnter(e)) {
				if (e.hasControlDown()) {
					ctrlEnter();
				} else if (focus.multiLine) {
					focus.model.insert("\n");
				} else if (focus == instruction) {
					commitInstruction(null);
				} else if (focus == formBranch) {
					focus(formText);
				}
				return true;
			}
			focus.key(font(), e);
			if (focus == message || focus == instruction) {
				note = noteError ? null : note;
			}
			return true; // a focused field swallows the rest (no hub keys while typing)
		}
		if (TextKeys.isEnter(e) && e.hasControlDown()) {
			ctrlEnter();
			return true;
		}
		if (e.isEscape() && (formOpen || detailOpen && compact)) {
			back();
			return true;
		}
		if (!formOpen && (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN)) {
			List<Goal> list = HubGoals.goals(buildingFilter);
			if (!list.isEmpty()) {
				int i = 0;
				for (int j = 0; j < list.size(); j++) {
					if (list.get(j).id().equals(selected)) {
						i = j;
					}
				}
				i = Math.max(0, Math.min(list.size() - 1, i + (k == InputConstants.KEY_UP ? -1 : 1)));
				if (compact && !detailOpen) {
					selected = list.get(i).id();
				} else {
					open(list.get(i).id(), null);
				}
			}
			return true;
		}
		if (!formOpen && selected != null && (k == InputConstants.KEY_LEFT || k == InputConstants.KEY_RIGHT) && (detailOpen || !compact)) {
			View[] all = View.values();
			setView(all[Math.floorMod(view.ordinal() + (k == InputConstants.KEY_LEFT ? -1 : 1), all.length)]);
			return true;
		}
		if (!formOpen && TextKeys.isEnter(e) && selected != null) {
			open(selected, null);
			if (view == View.THREAD) {
				focus(message);
			}
			return true;
		}
		return false;
	}

	private void ctrlEnter() {
		if (formOpen) {
			submitForm();
		} else if (focus == planEditor) {
			savePlan();
		} else if (focus == instruction) {
			commitInstruction(null);
		} else if (view == View.THREAD && selected != null) {
			sendMessage();
		}
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (focus != null && e.codepoint() >= 32) {
			focus.model.insert(e.codepointAsString());
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
		for (HubField f : fieldsInContext()) {
			if (f.click(font(), x, y)) {
				focus(f);
				return true;
			}
		}
		String id = goalList.hit(x, y);
		if (id != null) {
			if (compact && !doubleClick && !detailOpen) {
				open(id, null);
			} else {
				open(id, null);
			}
			return true;
		}
		String task = taskList.hit(x, y);
		if (task != null) {
			openTask(task);
			return true;
		}
		if (focus != null) {
			unfocus();
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, int dir) {
		if (inside(threadArea, x, y)) {
			threadScroll.scrollBy(dir * 20);
			return true;
		}
		if (inside(planArea, x, y)) {
			planScroll.scrollBy(dir * 20);
			return true;
		}
		return goalList.scroll(x, y, dir) || taskList.scroll(x, y, dir);
	}

	private static boolean inside(int[] r, double x, double y) {
		return r[2] > 0 && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
	}

	@Override
	public String[] hints() {
		if (focus != null) {
			String verb = formOpen ? "submit" : focus == planEditor ? "save" : focus == instruction ? "save" : "send";
			return new String[] {"Ctrl+Enter", verb, "Tab", "next field", "Esc", focus == planEditor ? "cancel" : "done typing"};
		}
		if (formOpen) {
			return new String[] {"Ctrl+Enter", "submit", "Esc", "back"};
		}
		if (compact && detailOpen) {
			return new String[] {"←→", "view", "↑↓", "goal", "Esc", "back"};
		}
		return new String[] {"Tab", "next tab", "↑↓", "goal", "←→", "view", "Enter", "write", "Esc", "close"};
	}

	// ------------------------------------------------------------------ drawing helpers

	private void chip(GuiGraphicsExtractor g, String id, String label, int x, int y, boolean on, int mx, int my, Runnable action) {
		int w = font().width(label) + 12;
		Panels.sprite(g, on ? Kit.TAB_ACTIVE : Kit.TAB_INACTIVE, x, y, w, 14);
		if (!on && mx >= x && mx < x + w && my >= y && my < y + 14) {
			g.fill(x + 1, y + 1, x + w - 1, y + 13, 0x14000000);
		}
		g.text(font(), label, x + 6, y + 3, on ? UiBits.ink() : UiBits.muted(), false);
		chipRects.add(new int[] {x, y, w, 14});
		chipActions.add(action);
		chipIds.add(id);
	}

	private int chipW(String label) {
		return font().width(label) + 12;
	}

	static String family(GoalStatus s) {
		return switch (s) {
			case PLANNING -> "thinking";
			case ACTIVE -> "working";
			case DONE -> "done";
			case FAILED -> "error";
			default -> "idle";
		};
	}

	/** "2 PRs open · 1 merged" from the goal's PR summary (or its tasks' PRs), "" when none. */
	static String prSummary(Goal g) {
		int open = 0;
		int merged = 0;
		if (g.prs() != null) {
			for (Protocol.GoalPr p : g.prs()) {
				if ("merged".equals(p.status())) {
					merged++;
				} else if (!"abandoned".equals(p.status())) {
					open++;
				}
			}
		} else {
			for (Task t : HubGoals.tasks(g.id())) {
				if (t.pr() != null) {
					if (t.pr().status().equals("merged")) {
						merged++;
					} else if (t.pr().isOpen()) {
						open++;
					}
				}
			}
		}
		if (open == 0 && merged == 0) {
			return "";
		}
		String s = open > 0 ? open + (open == 1 ? " PR open" : " PRs open") : "";
		if (merged > 0) {
			s += (s.isEmpty() ? "" : " · ") + merged + " merged";
		}
		return s;
	}

	private static String buildingLabel(Building b) {
		Blueprint bp = Blueprints.get(b.blueprint());
		return (bp != null ? bp.name() : b.blueprint()) + " (" + b.id() + ")";
	}

	// ------------------------------------------------------------------ draw

	@Override
	public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		chipRects.clear();
		chipActions.clear();
		chipIds.clear();
		for (HubField f : List.of(message, planEditor, instruction, formText, formBranch, formInstructions)) {
			f.beginFrame();
		}
		threadArea = new int[4];
		planArea = new int[4];
		compact = w < 470 || h < 200;
		available = h;
		needed = 0;
		List<Goal> goals = HubGoals.goals(buildingFilter);
		Goal cur = current(goals);
		if (focus != null && !fieldsInContext().contains(focus)) {
			unfocus();
		}
		if (formOpen) {
			goalList.hide();
			taskList.hide();
			shownMode = "form";
			drawForm(g, x, y, w, h, mx, my);
			return;
		}
		boolean showList = !compact || !detailOpen || cur == null;
		boolean showDetail = !compact || detailOpen && cur != null;
		shownMode = showList && showDetail ? "list+detail" : showDetail ? "detail" : "list";
		// top bar: back (compact detail) or the building filter; New goal
		int ty = y;
		if (compact && showDetail && !showList) {
			hub.button(g, "goal_back", "‹ Goals", x, ty, hub.bw("‹ Goals"), false, false, false, mx, my, this::back);
		} else {
			drawFilter(g, x, ty, w - hub.bw("New goal…") - 8, mx, my, goals.size());
		}
		String ng = "New goal…";
		hub.button(g, "goal_new", ng, x + w - hub.bw(ng), ty, hub.bw(ng), true, false, false, mx, my, () -> newGoal(null, buildingFilter));
		y += TOP_H;
		h -= TOP_H;
		needed += TOP_H;
		// the away digest
		HubGoals.DigestState away = HubGoals.away();
		if (away != null && !away.dismissed && (showList || !compact)) {
			int dh = drawAway(g, away, x, y, w, Math.max(30, Math.min(compact ? 44 : 80, h / 3)), mx, my);
			y += dh + 4;
			h -= dh + 4;
			needed += dh + 4;
		}
		if (goals.isEmpty() && cur == null) {
			goalList.hide();
			taskList.hide();
			Panels.inset(g, x, y, w, h);
			String msg = !Foreman.connected() && Foreman.state() != null && !Foreman.state().hasData()
				? "The Foreman is not connected. Start it, then goals show up here."
				: buildingFilter != null ? "No goals for this building's repos. \"New goal…\" starts one." : "No goals yet. \"New goal…\" gives the team one: what should be done, for which repo or building.";
			int ly = y + 10;
			for (String line : TextUtil.wrapPlain(font(), msg, w - 16)) {
				g.text(font(), line, x + 8, ly, UiBits.muted(), false);
				ly += 10;
			}
			return;
		}
		int lw = showDetail && showList ? Math.max(150, Math.min(210, w * 2 / 5)) : w;
		if (showList) {
			drawGoalList(g, goals, cur, x, y, lw, h, mx, my);
		} else {
			goalList.hide();
		}
		if (showDetail && cur != null && !cur.id().equals(HubGoals.viewing())) {
			// shown without a click (the first goal, or after a filter change): it is being looked at now
			HubGoals.openGoal(cur);
			HubGoals.view(cur.id());
		}
		if (showDetail && cur != null) {
			int dx = showList ? x + lw + 10 : x;
			int dw = showList ? w - lw - 10 : w;
			drawDetail(g, cur, dx, y, dw, h, mx, my);
		} else {
			taskList.hide();
		}
	}

	private void drawFilter(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, int count) {
		List<Building> withRepos = new ArrayList<>();
		for (Building b : Buildings.all()) {
			if (!b.repos().isEmpty()) {
				withRepos.add(b);
			}
		}
		if (buildingFilter != null && Buildings.get(buildingFilter) == null) {
			buildingFilter = null;
		}
		String label = buildingFilter == null ? "All buildings" : buildingLabel(Buildings.get(buildingFilter));
		label = TextUtil.ellipsize(font(), label + " ▾", Math.max(40, w - 60));
		int cw = chipW(label);
		chip(g, "goal_filter", label, x, y + 3, buildingFilter != null, mx, my, () -> {
			if (withRepos.isEmpty()) {
				buildingFilter = null;
				return;
			}
			int i = -1;
			for (int j = 0; j < withRepos.size(); j++) {
				if (withRepos.get(j).id().equals(buildingFilter)) {
					i = j;
				}
			}
			setFilter(i + 1 >= withRepos.size() ? null : withRepos.get(i + 1).id());
		});
		String n = UiBits.plural(count, "goal", "goals");
		if (x + cw + 8 + font().width(n) <= x + w) {
			g.text(font(), n, x + cw + 8, y + 6, UiBits.muted(), false);
		}
	}

	/** The "since you were away" panel; returns its height. */
	private int drawAway(GuiGraphicsExtractor g, HubGoals.DigestState st, int x, int y, int w, int maxH, int mx, int my) {
		List<String[]> rows = new ArrayList<>(); // text, goal id (or null), color kind
		String head;
		if (st.loading()) {
			head = "Since you were away: asking the Foreman what happened…";
		} else if (st.error != null) {
			head = st.unsupported ? "Since you were away: the digest needs a newer Foreman" : "Since you were away: " + st.error;
		} else {
			int n = st.digest.goals().size();
			head = "Since you were away (" + UiBits.clock(st.since) + "): " + (n == 0 ? "nothing happened" : UiBits.plural(n, "goal", "goals")
				+ " moved");
			for (Protocol.GoalDigest gd : st.digest.goals()) {
				Goal goal = HubGoals.goal(gd.goalId());
				String text = gd.text() != null ? gd.text() : goal != null ? goal.text() : "";
				rows.add(new String[] {gd.goalId() + "  " + UiBits.oneLine(text) + " — " + GoalLogic.summary(HubGoals.lines(gd)), gd.goalId()});
			}
		}
		int lines = Math.min(rows.size(), Math.max(0, (maxH - 18) / 10));
		int ph = 16 + lines * 10 + (rows.size() > lines ? 10 : 0) + 2;
		Panels.inset(g, x, y, w, ph);
		String dis = "Dismiss";
		int dw = hub.bw(dis);
		hub.button(g, "digest_dismiss", dis, x + w - dw - 2, y + 1, dw, false, false, false, mx, my, HubGoals::dismissAway);
		g.text(font(), TextUtil.ellipsize(font(), head, w - dw - 14), x + 6, y + 5, st.error != null ? UiBits.muted() : UiStyle.CLAY_DARK, false);
		int ly = y + 17;
		for (int i = 0; i < lines; i++) {
			String[] r = rows.get(i);
			int lx = x + 6;
			String gid = r[1];
			boolean hover = mx >= x && mx < x + w - dw - 8 && my >= ly - 1 && my < ly + 9;
			g.text(font(), TextUtil.ellipsize(font(), r[0], w - 14), lx, ly, hover ? UiBits.ink() : UiBits.muted(), false);
			chipRects.add(new int[] {x, ly - 1, w - dw - 8, 10});
			chipActions.add(() -> open(gid, View.THREAD));
			chipIds.add("digest:" + gid);
			ly += 10;
		}
		if (rows.size() > lines) {
			g.text(font(), "… " + (rows.size() - lines) + " more", x + 6, ly, UiBits.muted(), false);
		}
		return ph;
	}

	private void drawGoalList(GuiGraphicsExtractor g, List<Goal> goals, @Nullable Goal cur, int x, int y, int w, int h, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		goalList.draw(g, x, y, w, h, goals.size(), cur == null ? -1 : goals.indexOf(cur), mx, my, (i, rx, ry, rw) -> {
			Goal goal = goals.get(i);
			String pill = goal.status().wire();
			int pw = UiBits.dotPillWidth(font(), pill);
			UiBits.dotPill(g, font(), family(goal.status()), pill, rx + rw - pw + 2, ry - 1, goal.status() == GoalStatus.DONE ? UiBits.okText() : muted);
			int tx = rx;
			if (HubGoals.unread(goal)) {
				Panels.sprite(g, Kit.dot("waiting", false), rx - 1, ry + 1, 6, 6);
				tx += 7;
			}
			g.text(font(), TextUtil.ellipsize(font(), goal.id() + "  " + UiBits.oneLine(goal.text()), rx + rw - pw - 4 - tx), tx, ry, ink, false);
			String pr = prSummary(goal);
			String second = String.join(", ", goal.allRepos()) + " · " + UiBits.agentName(goal.lead()) + " · " + Math.round(goal.progress() * 100) + "%"
				+ (pr.isEmpty() ? "" : " · " + pr);
			ReviewKit.face(g, font(), goal.lead(), rx, ry + 9, 8);
			g.text(font(), TextUtil.ellipsize(font(), second, rw - 11), rx + 11, ry + 10, muted, false);
			return goal.id();
		});
	}

	private void drawDetail(GuiGraphicsExtractor g, Goal goal, int x, int y, int w, int h, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		int top = y;
		g.text(font(), TextUtil.ellipsize(font(), goal.id() + " · " + UiBits.oneLine(goal.text()), w), x, y, ink, false);
		y += 12;
		int pw = UiBits.dotPill(g, font(), family(goal.status()), goal.status().wire(), x, y - 1, ink);
		int px = x + pw + 4;
		int barW = Math.min(50, Math.max(20, w / 8));
		Panels.progress(g, px, y + 1, barW, goal.progress(), goal.status() == GoalStatus.DONE ? "sage" : "brass");
		px += barW + 4;
		String pct = Math.round(goal.progress() * 100) + "%";
		g.text(font(), pct, px, y, muted, false);
		px += font().width(pct) + 6;
		ReviewKit.face(g, font(), goal.lead(), px, y - 1, 8);
		px += 11;
		String pr = prSummary(goal);
		String facts = UiBits.agentName(goal.lead()) + " · " + String.join(", ", goal.allRepos()) + (goal.branch() != null ? " · on " + goal.branch() : "")
			+ (pr.isEmpty() ? "" : " · " + pr);
		g.text(font(), TextUtil.ellipsize(font(), facts, x + w - px), px, y, muted, false);
		y += 13;
		// view chips + Cancel goal
		int cx = x;
		int nInstr = goal.instructions() == null ? 0 : goal.instructions().size();
		int nTasks = HubGoals.tasks(goal.id()).size();
		for (View v : View.values()) {
			String label = switch (v) {
				case THREAD -> "Thread";
				case PLAN -> "Plan";
				case INSTRUCTIONS -> compact ? "Instr. " + nInstr : "Instructions " + nInstr;
				case TASKS -> "Tasks " + nTasks;
			};
			chip(g, "view:" + v.id(), label, cx, y, v == view, mx, my, () -> setView(v));
			cx += chipW(label) + 3;
		}
		if (goal.isOpen()) {
			boolean armed = cancelArmed();
			String c = armed ? "Confirm cancel" : compact ? "Cancel…" : "Cancel goal…";
			int cw = hub.bw(c);
			if (cx + 6 + cw <= x + w) {
				hub.button(g, "goal_cancel", c, x + w - cw, y - 3, cw, armed, sending || !Foreman.connected(), !armed, mx, my, this::cancelClick);
			}
		}
		y += 19;
		needed += (y - top);
		int vh = top + h - y;
		switch (view) {
			case THREAD -> drawThread(g, goal, x, y, w, vh, mx, my);
			case PLAN -> drawPlan(g, goal, x, y, w, vh, mx, my);
			case INSTRUCTIONS -> drawInstructions(g, goal, x, y, w, vh, mx, my);
			case TASKS -> drawTasks(g, goal, x, y, w, vh, mx, my);
		}
		if (view != View.TASKS) {
			taskList.hide();
		}
	}

	private void drawNote(GuiGraphicsExtractor g, int x, int y, int w) {
		if (note != null) {
			g.text(font(), TextUtil.ellipsize(font(), note, w), x, y, noteError ? UiBits.errorText() : UiBits.muted(), false);
		}
	}

	// ------------------------------------------------------------------ Thread

	/** A laid-out thread entry: text lines (with colours) and, for an open decision, its option buttons. */
	private record Block(List<String> lines, List<Integer> colors, @Nullable Decision decision, List<String[]> buttons, int height) {
	}

	private List<Block> layoutThread(Goal goal, int w) {
		List<Block> out = new ArrayList<>();
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		long now = Util.getMillis();
		for (HubGoals.Entry e : HubGoals.thread(goal.id())) {
			List<String> lines = new ArrayList<>();
			List<Integer> colors = new ArrayList<>();
			List<String[]> buttons = new ArrayList<>();
			Decision d = e.decision();
			if (e.feed() != null) {
				FeedItem f = e.feed();
				boolean user = f.kind() == Protocol.FeedKind.USER || UiBits.isUser(f.agentId());
				String who = user ? "You" : f.agentId() != null ? UiBits.agentName(f.agentId()) : f.kind().wire();
				String to = f.to() != null && !user ? "" : f.to() != null ? " → " + UiBits.agentName(f.to()) : "";
				lines.add(who + to + "  ·  " + UiBits.clock(f.ts()) + (f.kind() != Protocol.FeedKind.MESSAGE && f.kind() != Protocol.FeedKind.USER ? "  ·  "
					+ f.kind().wire() : ""));
				colors.add(user ? UiStyle.CLAY_DARK : UiBits.nameOnLight(f.agentId()));
				for (String l : TextUtil.wrapPlain(font(), f.text(), w - 8)) {
					lines.add(l);
					colors.add(f.kind() == Protocol.FeedKind.ERROR ? UiBits.errorText() : ink);
				}
			} else if (e.pending() != null) {
				HubGoals.Pending p = e.pending();
				lines.add("You  ·  " + (p.failed() ? "not sent: " + p.error() : "sending…"));
				colors.add(p.failed() ? UiBits.errorText() : muted);
				for (String l : TextUtil.wrapPlain(font(), p.text(), w - 8)) {
					lines.add(l);
					colors.add(muted);
				}
			} else if (d != null) {
				decisionSeenAt.putIfAbsent(d.id(), now);
				String kind = switch (d.kind()) {
					case MERGE -> "asks to merge";
					case PERMISSION -> "asks permission";
					default -> "asks";
				};
				lines.add(UiBits.agentName(d.agentId()) + " " + kind + "  ·  " + d.id() + "  ·  " + UiBits.clock(d.createdAt()));
				colors.add(UiBits.nameOnLight(d.agentId()));
				for (String l : TextUtil.wrapPlain(font(), d.question(), w - 8)) {
					lines.add(l);
					colors.add(ink);
				}
				if (d.isOpen() && !DecisionsFeature.isAnswering(d.id())) {
					for (String o : d.options()) {
						String label = d.kind() == DecisionKind.PERMISSION ? PermissionBody.buttonLabel(o) : o;
						if (d.kind() == DecisionKind.MERGE && o.equals(Protocol.REJECT) && d.id().equals(confirmReject) && now < confirmRejectUntil) {
							label = "Confirm reject";
						}
						buttons.add(new String[] {o, label});
					}
					if (d.kind() == DecisionKind.QUESTION) {
						buttons.add(new String[] {"\u0000text", "Answer with text"});
					}
					buttons.add(new String[] {"\u0000open", "Open…"});
				} else {
					String state = DecisionsFeature.isAnswering(d.id()) ? "sending your answer…" : d.status() == Protocol.DecisionStatus.CANCELLED
						? "withdrawn" : d.answer() != null ? "answered: " + (d.answer().option() != null ? d.answer().option() : "")
						+ (d.answer().text() != null ? " " + UiBits.oneLine(d.answer().text()) : "") : d.status().wire();
					lines.add(TextUtil.ellipsize(font(), state, w - 8));
					colors.add(muted);
				}
			}
			int rows = buttonRows(buttons, w - 8);
			out.add(new Block(lines, colors, d, buttons, lines.size() * 10 + rows * 22 + 5));
		}
		return out;
	}

	private int buttonRows(List<String[]> buttons, int w) {
		if (buttons.isEmpty()) {
			return 0;
		}
		int rows = 1;
		int bx = 0;
		for (String[] b : buttons) {
			int bw = hub.bw(b[1]);
			if (bx > 0 && bx + bw > w) {
				rows++;
				bx = 0;
			}
			bx += bw + 4;
		}
		return rows;
	}

	private void drawThread(GuiGraphicsExtractor g, Goal goal, int x, int y, int w, int h, int mx, int my) {
		int bottom = y + h;
		// this goal's own digest (since its last view)
		HubGoals.DigestState gd = HubGoals.goalDigest(goal.id());
		if (gd != null && !gd.loading() && !gd.dismissed) {
			List<String> dl = new ArrayList<>();
			if (gd.error != null) {
				dl.add(gd.unsupported ? "Since you last looked: (the digest needs a newer Foreman)" : "Since you last looked: " + gd.error);
			} else {
				for (Protocol.GoalDigest d : gd.digest.goals()) {
					if (d.goalId().equals(goal.id())) {
						List<GoalLogic.Line> lines = HubGoals.lines(d);
						dl.add("Since you last looked (" + UiBits.clock(gd.since) + "): " + GoalLogic.summary(lines));
						for (GoalLogic.Section s : GoalLogic.sections(lines)) {
							if (dl.size() >= (compact ? 2 : 4)) {
								break;
							}
							StringBuilder sb = new StringBuilder(s.title() + ": ");
							for (int i = 0; i < s.lines().size(); i++) {
								sb.append(i == 0 ? "" : "; ").append(s.lines().get(i).text());
							}
							dl.add(sb.toString());
						}
					}
				}
			}
			if (!dl.isEmpty()) {
				int dh = dl.size() * 10 + 4;
				Panels.inset(g, x, y, w, dh + 2);
				for (int i = 0; i < dl.size(); i++) {
					g.text(font(), TextUtil.ellipsize(font(), dl.get(i), w - 22), x + 5, y + 3 + i * 10, i == 0 ? UiStyle.CLAY_DARK : UiBits.muted(), false);
				}
				chip(g, "goal_digest_dismiss", "×", x + w - 16, y + 1, false, mx, my, () -> gd.dismissed = true);
				y += dh + 5;
			}
		}
		// the input at the bottom: the message box + Send
		String send = "Send";
		int sw = hub.bw(send);
		int fw = w - sw - 4;
		int maxLines = compact ? 2 : 3;
		int fh = message.height(font(), fw, maxLines);
		int noteH = note != null ? 11 : 0;
		int inputY = bottom - fh;
		int areaBottom = inputY - 3 - noteH;
		needed += 30 + fh + noteH;
		// entries
		List<Block> blocks = layoutThread(goal, w - 8);
		int total = 0;
		for (Block b : blocks) {
			total += b.height();
		}
		int areaH = Math.max(10, areaBottom - y);
		threadArea = new int[] {x, y, w, areaH};
		Panels.inset(g, x, y, w, areaH);
		threadScroll.update(total + 6, areaH);
		if (blocks.isEmpty()) {
			String empty = goal.status() == GoalStatus.PLANNING ? "The lead is planning this goal. Its messages, plan changes and decisions show up here."
				: "Nothing about this goal in the recent feed yet. Messages you send go to its lead.";
			int ly = y + 6;
			for (String l : TextUtil.wrapPlain(font(), empty, w - 12)) {
				g.text(font(), l, x + 6, ly, UiBits.muted(), false);
				ly += 10;
			}
		} else {
			g.enableScissor(x + 1, y + 1, x + w - 1, y + areaH - 1);
			int by = y + 3 - threadScroll.offset();
			for (Block b : blocks) {
				if (by + b.height() >= y && by <= y + areaH) {
					drawBlock(g, b, x + 5, by, w - 14, y, y + areaH, mx, my);
				}
				by += b.height();
			}
			g.disableScissor();
			Panels.scrollbar(g, x + w - 7, y + 1, areaH - 2, threadScroll, false);
		}
		if (noteH > 0) {
			drawNote(g, x, areaBottom + 2, w);
		}
		boolean focused = focus == message;
		message.draw(g, font(), x, inputY, fw, maxLines, focused);
		hub.button(g, "goal_send", send, x + w - sw, inputY + Math.max(0, fh - 20), sw, true, !Foreman.connected(), false, mx, my, this::sendMessage);
	}

	private void drawBlock(GuiGraphicsExtractor g, Block b, int x, int y, int w, int clipTop, int clipBottom, int mx, int my) {
		int ly = y;
		for (int i = 0; i < b.lines().size(); i++) {
			g.text(font(), b.lines().get(i), x, ly, b.colors().get(i), false);
			ly += 10;
		}
		if (b.decision() == null || b.buttons().isEmpty()) {
			return;
		}
		Decision d = b.decision();
		int bx = x;
		int by = ly + 1;
		for (String[] opt : b.buttons()) {
			int bw = hub.bw(opt[1]);
			if (bx > x && bx + bw > x + w) {
				bx = x;
				by += 22;
			}
			// clickable only when fully inside the visible area
			boolean visible = by >= clipTop && by + 20 <= clipBottom;
			if (visible) {
				String o = opt[0];
				Runnable action = o.equals("\u0000open") ? () -> openDecision(d.id()) : o.equals("\u0000text") ? () -> answer(d, null) : () -> answer(d, o);
				String id = "answer:" + d.id() + ":" + (o.equals("\u0000open") ? "open" : o.equals("\u0000text") ? "text" : o);
				boolean danger = d.kind() == DecisionKind.MERGE && o.equals(Protocol.REJECT) || o.equals(Protocol.DENY);
				hub.button(g, id, opt[1], bx, by, bw, false, false, danger, mx, my, action);
			} else {
				UiBits.button(g, font(), opt[1], 0, bx, by, bw, false, UiBits.ButtonState.NORMAL, false);
			}
			bx += bw + 4;
		}
	}

	// ------------------------------------------------------------------ Plan

	private void drawPlan(GuiGraphicsExtractor g, Goal goal, int x, int y, int w, int h, int mx, int my) {
		int bottom = y + h;
		int muted = UiBits.muted();
		int noteH = note != null ? 11 : 0;
		int buttonsY = bottom - 20;
		needed += 40 + noteH;
		if (planEditing) {
			int lines = Math.max(2, (buttonsY - 4 - noteH - y - 18) / 10 + 1);
			planEditor.draw(g, font(), x, y, w, lines, focus == planEditor);
			if (noteH > 0) {
				drawNote(g, x, buttonsY - 13, w);
			}
			String save = "Save plan";
			String cancel = "Cancel";
			int bx = x + w - hub.bw(save);
			hub.button(g, "plan_save", save, bx, buttonsY, hub.bw(save), true, sending || !Foreman.connected(), false, mx, my, this::savePlan);
			bx -= 4 + hub.bw(cancel);
			hub.button(g, "plan_cancel", cancel, bx, buttonsY, hub.bw(cancel), false, sending, false, mx, my, this::cancelPlan);
			if (bx - 8 > x) {
				g.text(font(), TextUtil.ellipsize(font(), "Saving sends the lead the change as a diff.", bx - 8 - x), x, buttonsY + 6, muted, false);
			}
			return;
		}
		Protocol.MemoryEntry m = HubGoals.plan(goal);
		int areaH = Math.max(10, buttonsY - 4 - noteH - y);
		planArea = new int[] {x, y, w, areaH};
		Panels.inset(g, x, y, w, areaH);
		if (m == null) {
			String msg = goal.planId() == null && goal.instructions() == null && goal.prs() == null
				? "No plan note. This Foreman does not report goal plans (a newer one keeps the lead's Plan: note here, editable)."
				: goal.planId() != null ? "The plan note " + goal.planId() + " is not in the memory the Foreman sent." : "No plan yet: the lead writes one "
					+ "while planning. \"Write a plan\" gives it yours.";
			int ly = y + 6;
			for (String l : TextUtil.wrapPlain(font(), msg, w - 12)) {
				g.text(font(), l, x + 6, ly, muted, false);
				ly += 10;
			}
		} else {
			List<String> lines = GoalLogic.wrap(m.body(), w - 16, s -> font().width(s));
			planScroll.update(lines.size() * 10 + 6, areaH);
			if (planScroll.following() && planScroll.offset() > 0 && planScroll.content() > 0) {
				planScroll.scrollBy(-1_000_000); // a plan reads from the top
			}
			g.enableScissor(x + 1, y + 1, x + w - 1, y + areaH - 1);
			int ly = y + 4 - planScroll.offset();
			for (String l : lines) {
				if (ly + 10 >= y && ly <= y + areaH) {
					boolean heading = l.startsWith("#");
					g.text(font(), l, x + 6, ly, heading ? UiStyle.CLAY_DARK : UiBits.ink(), false);
				}
				ly += 10;
			}
			g.disableScissor();
			Panels.scrollbar(g, x + w - 7, y + 1, areaH - 2, planScroll, false);
		}
		if (noteH > 0) {
			drawNote(g, x, buttonsY - 13, w);
		}
		String edit = m == null ? "Write a plan" : "Edit plan";
		int bx = x + w - hub.bw(edit);
		hub.button(g, "plan_edit", edit, bx, buttonsY, hub.bw(edit), false, !Foreman.connected(), false, mx, my, this::editPlan);
		if (m != null) {
			String meta = "Plan note " + m.id() + (m.author() != null ? " · by " + UiBits.agentName(m.author()) : "") + " · " + UiBits.ago(m.updated());
			g.text(font(), TextUtil.ellipsize(font(), meta, bx - 8 - x), x, buttonsY + 6, muted, false);
		}
	}

	// ------------------------------------------------------------------ Instructions

	private void drawInstructions(GuiGraphicsExtractor g, Goal goal, int x, int y, int w, int h, int mx, int my) {
		int bottom = y + h;
		int muted = UiBits.muted();
		List<String> list = instructionsOf(goal);
		boolean unknown = goal.instructions() == null && goal.planId() == null && goal.prs() == null;
		int noteH = note != null ? 11 : 0;
		int fieldY = bottom - 18;
		int areaH = Math.max(10, fieldY - 4 - noteH - y);
		needed += 30 + noteH;
		Panels.inset(g, x, y, w, areaH);
		int ly = y + 5;
		if (list.isEmpty()) {
			String msg = unknown ? "This Foreman does not report standing instructions (a newer one does). You can still add one: it is sent as "
				+ "goal.instructions." : "No standing instructions. Every task of this goal inherits them (in the lead's planning, each task and every "
				+ "worker prompt), e.g. \"keep the API backwards compatible\".";
			for (String l : TextUtil.wrapPlain(font(), msg, w - 12)) {
				g.text(font(), l, x + 6, ly, muted, false);
				ly += 10;
			}
		}
		for (int i = 0; i < list.size() && ly + 12 <= y + areaH; i++) {
			int idx = i;
			boolean editing = i == instrEditing;
			int xw = chipW("×");
			int ew = chipW("Edit");
			chip(g, "instr_remove:" + i, "×", x + w - xw - 4, ly - 2, false, mx, my, () -> removeInstruction(idx));
			chip(g, "instr_edit:" + i, "Edit", x + w - xw - ew - 7, ly - 2, editing, mx, my, () -> editInstruction(idx));
			g.text(font(), "•", x + 6, ly + 1, muted, false);
			g.text(font(), TextUtil.ellipsize(font(), list.get(i), w - xw - ew - 26), x + 14, ly + 1, editing ? UiStyle.CLAY_DARK : UiBits.ink(), false);
			ly += 16;
		}
		if (noteH > 0) {
			drawNote(g, x, fieldY - 13, w);
		}
		String add = instrEditing >= 0 ? "Save" : "Add";
		int aw = hub.bw(add);
		int fw = w - aw - 4;
		if (instrEditing >= 0) {
			String cancel = "Cancel";
			int cw = hub.bw(cancel);
			fw -= cw + 4;
			hub.button(g, "instr_cancel", cancel, x + fw + 4, fieldY - 1, cw, false, false, false, mx, my, () -> {
				instrEditing = -1;
				instruction.set("");
				unfocus();
			});
		}
		instruction.draw(g, font(), x, fieldY, fw, 1, focus == instruction);
		hub.button(g, "instr_add", add, x + w - aw, fieldY - 1, aw, true, sending || !Foreman.connected(), false, mx, my, () -> commitInstruction(null));
	}

	// ------------------------------------------------------------------ Tasks

	private void drawTasks(GuiGraphicsExtractor g, Goal goal, int x, int y, int w, int h, int mx, int my) {
		List<Task> tasks = HubGoals.tasks(goal.id());
		int muted = UiBits.muted();
		int ink = UiBits.ink();
		int noteH = note != null ? 11 : 0;
		int listH = h - noteH - 22;
		needed += 50;
		if (tasks.isEmpty()) {
			taskList.hide();
			Panels.inset(g, x, y, w, listH);
			String msg = goal.status() == GoalStatus.PLANNING ? "The lead is still planning: tasks show up here." : "No tasks for this goal.";
			g.text(font(), TextUtil.ellipsize(font(), msg, w - 12), x + 6, y + 6, muted, false);
		} else {
			taskList.draw(g, x, y, w, listH, tasks.size(), -1, mx, my, (i, rx, ry, rw) -> {
				Task t = tasks.get(i);
				String pill = t.status().wire();
				int pw = UiBits.dotPillWidth(font(), pill);
				String fam = switch (t.status()) {
					case DOING -> "working";
					case REVIEW, PR -> "waiting";
					case DONE -> "done";
					case BLOCKED -> "error";
					default -> "idle";
				};
				UiBits.dotPill(g, font(), fam, pill, rx + rw - pw + 2, ry - 1, t.status() == Protocol.TaskStatus.DONE ? UiBits.okText() : muted);
				g.text(font(), TextUtil.ellipsize(font(), t.id() + "  " + t.title(), rw - pw - 4), rx, ry, ink, false);
				String who = t.assignee() == null ? "unassigned" : UiBits.agentName(t.assignee());
				String pr = t.pr() == null ? "" : " · PR #" + t.pr().id() + " " + t.pr().status() + (t.pr().checks() != null && !t.pr().checks().equals("none")
					? ", checks " + t.pr().checks() : "");
				String second = who + (t.repoId() != null ? " · " + t.repoId() : "") + pr + (t.blockedReason() != null ? " · " + UiBits.oneLine(t.blockedReason())
					: "");
				if (t.assignee() != null) {
					ReviewKit.face(g, font(), t.assignee(), rx, ry + 9, 8);
				}
				g.text(font(), TextUtil.ellipsize(font(), second, rw - 11), rx + 11, ry + 10, muted, false);
				return t.id();
			});
		}
		if (noteH > 0) {
			drawNote(g, x, y + listH + 2, w);
		}
		int by = y + h - 20;
		String rp = "Refresh PRs";
		hub.button(g, "refresh_prs", rp, x + w - hub.bw(rp), by, hub.bw(rp), false, !Foreman.connected(), false, mx, my, () -> HubGoals.refreshPrs()
			.thenAccept(n -> setNote(n.message(), !n.ok())));
		g.text(font(), TextUtil.ellipsize(font(), "Click a task to open it.", w - hub.bw(rp) - 8), x, by + 6, muted, false);
	}

	// ------------------------------------------------------------------ New goal form

	private void drawForm(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		int muted = UiBits.muted();
		int top = y;
		hub.button(g, "goal_form_back", "‹ Back", x, y, hub.bw("‹ Back"), false, false, false, mx, my, this::back);
		g.text(font(), "New goal", x + hub.bw("‹ Back") + 8, y + 6, UiStyle.CLAY_DARK, false);
		if (!Foreman.connected()) {
			String off = "Foreman offline";
			int ow = UiBits.dotPillWidth(font(), off);
			UiBits.dotPill(g, font(), "error", off, x + w - ow, y + 4, UiBits.errorText());
		}
		y += TOP_H;
		int footerY = top + h - 20;
		int colW = (w - 12) / 2;
		int lx = x;
		int rx = x + colW + 12;
		int bodyBottom = footerY - 14;
		// left: the goal, standing instructions
		int ly = y;
		g.text(font(), "What should be done?", lx, ly, UiStyle.CLAY_DARK, false);
		ly += 11;
		int instrBlock = 11 + formInstructions.height(font(), colW, 3) + 2;
		int textLines = Math.max(2, Math.min(8, (bodyBottom - ly - instrBlock - 6 - 18) / 10 + 1));
		ly += formText.draw(g, font(), lx, ly, colW, textLines, focus == formText) + 6;
		g.text(font(), compact ? "Standing instructions" : "Standing instructions (one per line)", lx, ly, UiStyle.CLAY_DARK, false);
		ly += 11;
		ly += formInstructions.draw(g, font(), lx, ly, colW, Math.max(1, Math.min(3, (bodyBottom - ly - 18) / 10 + 1)), focus == formInstructions);
		// right: for (repo or building), continue a branch
		int ry = y;
		g.text(font(), "For", rx, ry, UiStyle.CLAY_DARK, false);
		ry += 11;
		ForemanState s = Foreman.state();
		List<String[]> targets = new ArrayList<>(); // id, label
		if (s != null) {
			for (Repo r : s.repos().values()) {
				targets.add(new String[] {"repo:" + r.id(), r.id()});
			}
		}
		for (Building b : Buildings.all()) {
			if (b.repos().size() > 1) {
				Blueprint bp = Blueprints.get(b.blueprint());
				targets.add(new String[] {"building:" + b.id(), (bp != null ? bp.name() : b.id()) + " (" + b.repos().size() + " repos)"});
			}
		}
		if (formTarget == null && !targets.isEmpty()) {
			formTarget = targets.get(0)[0];
		}
		if (targets.isEmpty()) {
			for (String l : TextUtil.wrapPlain(font(), "No repos: add one in the Repos tab (or the Foreman's default repo is used).", colW)) {
				g.text(font(), l, rx, ry, muted, false);
				ry += 10;
			}
			ry += 4;
		} else {
			int cx = rx;
			for (String[] t : targets) {
				String label = TextUtil.ellipsize(font(), t[1], colW - 12);
				int cw = chipW(label);
				if (cx > rx && cx + cw > rx + colW) {
					cx = rx;
					ry += 17;
				}
				chip(g, "target:" + t[0], label, cx, ry, t[0].equals(formTarget), mx, my, () -> formTarget = t[0]);
				cx += cw + 3;
			}
			ry += 17;
			List<String> repos = formRepos();
			if (formTarget != null && formTarget.startsWith("building:")) {
				g.text(font(), TextUtil.ellipsize(font(), "repos: " + String.join(", ", repos) + " (the lead gives each task its repo)", colW), rx, ry,
					muted, false);
				ry += 11;
			}
			ry += 3;
		}
		g.text(font(), "Continue a branch", rx, ry, UiStyle.CLAY_DARK, false);
		ry += 11;
		ry += formBranch.draw(g, font(), rx, ry, colW, 1, focus == formBranch) + 3;
		List<String> branches = HubGoals.branches(formRepos().isEmpty() ? null : formRepos().get(0));
		int bx = rx;
		for (String b : branches) {
			String label = TextUtil.ellipsize(font(), b, colW - 12);
			int cw = chipW(label);
			if (bx + cw > rx + colW || ry + 14 > bodyBottom) {
				break;
			}
			chip(g, "branch:" + b, label, bx, ry, b.equals(formBranch.value().strip()), mx, my, () -> formBranch.set(b));
			bx += cw + 3;
		}
		if (!branches.isEmpty()) {
			ry += 17;
		}
		needed = Math.max(ly, ry) - top + 14 + 20 + (bodyBottom - Math.max(ly, ry) < 0 ? 0 : 0);
		available = h;
		// status + footer
		String status;
		int sc;
		if (sending) {
			status = "Submitting…";
			sc = muted;
		} else if (formNote != null) {
			status = formNote;
			sc = formNoteError ? UiBits.errorText() : muted;
		} else if (!Foreman.connected()) {
			status = "The Foreman takes goals: start it to submit this.";
			sc = muted;
		} else {
			status = "The goal's lead plans it into tasks; its thread and tasks show here.";
			sc = muted;
		}
		g.text(font(), TextUtil.ellipsize(font(), status, w), x, footerY - 12, sc, false);
		String go = "Submit goal";
		int gw = hub.bw(go);
		hub.button(g, "goal_submit", go, x + w - gw, footerY, gw, true, sending || !Foreman.connected(), false, mx, my, this::submitForm);
		String cancel = "Cancel";
		int cw = hub.bw(cancel);
		hub.button(g, "goal_form_cancel", cancel, x + w - gw - 6 - cw, footerY, cw, false, false, false, mx, my, this::back);
	}

	// ------------------------------------------------------------------ DevBridge

	@Override
	public JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("mode", shownMode);
		o.addProperty("selected", selected);
		o.addProperty("view", view.id());
		o.addProperty("detailOpen", detailOpen);
		o.addProperty("formOpen", formOpen);
		o.addProperty("filter", buildingFilter);
		o.addProperty("focus", focus());
		o.addProperty("note", note);
		o.addProperty("noteError", noteError);
		o.addProperty("sending", sending);
		o.addProperty("armedCancel", cancelArmed() ? armedCancel : null);
		o.addProperty("planEditing", planEditing);
		o.addProperty("instrEditing", instrEditing);
		JsonObject fields = new JsonObject();
		for (HubField f : List.of(message, planEditor, instruction, formText, formBranch, formInstructions)) {
			fields.addProperty(f.id, f.value());
		}
		o.add("fields", fields);
		JsonObject form = new JsonObject();
		form.addProperty("target", formTarget);
		JsonArray fr = new JsonArray();
		formRepos().forEach(fr::add);
		form.add("repos", fr);
		form.addProperty("note", formNote);
		form.addProperty("noteError", formNoteError);
		o.add("form", form);
		JsonObject layout = new JsonObject();
		layout.addProperty("guiWidth", hub.width);
		layout.addProperty("guiHeight", hub.height);
		layout.addProperty("compact", compact);
		layout.addProperty("needed", needed);
		layout.addProperty("available", available);
		layout.addProperty("overflow", needed > available);
		o.add("layout", layout);
		o.add("away", HubGoals.digestJson(HubGoals.away()));
		JsonArray gs = new JsonArray();
		for (Goal g : HubGoals.goals(buildingFilter)) {
			gs.add(HubGoals.goalJson(g));
		}
		o.add("goals", gs);
		Goal cur = HubGoals.goal(selected);
		if (cur != null) {
			JsonObject d = HubGoals.goalJson(cur);
			d.add("digest", HubGoals.digestJson(HubGoals.goalDigest(cur.id())));
			JsonArray th = new JsonArray();
			for (HubGoals.Entry e : HubGoals.thread(cur.id())) {
				JsonObject j = new JsonObject();
				j.addProperty("ts", e.ts());
				if (e.feed() != null) {
					j.addProperty("kind", "feed:" + e.feed().kind().wire());
					j.addProperty("agent", e.feed().agentId());
					j.addProperty("text", e.feed().text());
				} else if (e.decision() != null) {
					j.addProperty("kind", "decision:" + e.decision().kind().wire());
					j.addProperty("decisionId", e.decision().id());
					j.addProperty("status", e.decision().status().wire());
					j.addProperty("text", e.decision().question());
					JsonArray opts = new JsonArray();
					e.decision().options().forEach(opts::add);
					j.add("options", opts);
				} else if (e.pending() != null) {
					j.addProperty("kind", e.pending().failed() ? "pending:failed" : "pending:sending");
					j.addProperty("text", e.pending().text());
					j.addProperty("error", e.pending().error());
				}
				th.add(j);
			}
			d.add("thread", th);
			d.addProperty("threadScroll", threadScroll.offset());
			d.addProperty("threadFollowing", threadScroll.following());
			Protocol.MemoryEntry plan = HubGoals.plan(cur);
			d.addProperty("planBody", plan == null ? null : plan.body());
			d.addProperty("planLines", plan == null ? 0 : GoalLogic.wrap(plan.body(), 300, s -> font().width(s)).size());
			JsonArray ins = new JsonArray();
			instructionsOf(cur).forEach(ins::add);
			d.add("instructionList", ins);
			JsonArray ts = new JsonArray();
			for (Task t : HubGoals.tasks(cur.id())) {
				JsonObject j = new JsonObject();
				j.addProperty("id", t.id());
				j.addProperty("title", t.title());
				j.addProperty("status", t.status().wire());
				j.addProperty("assignee", t.assignee());
				j.addProperty("repoId", t.repoId());
				j.addProperty("pr", t.pr() == null ? null : "#" + t.pr().id() + " " + t.pr().status());
				ts.add(j);
			}
			d.add("taskList", ts);
			d.addProperty("prSummary", prSummary(cur));
			o.add("goal", d);
		} else {
			o.add("goal", null);
		}
		JsonArray chips = new JsonArray();
		chipIds.forEach(chips::add);
		o.add("chips", chips);
		return o;
	}

	/** Clicks a chip drawn last frame (view:*, target:*, branch:*, instr_*, digest:*, goal_filter); false when none. */
	boolean pressChip(String id) {
		int i = chipIds.indexOf(id);
		if (i < 0) {
			return false;
		}
		chipActions.get(i).run();
		return true;
	}

	/** DevBridge: the open goal's decision by id (for answer). */
	@Nullable Decision decision(String id) {
		ForemanState s = Foreman.state();
		Decision d = s == null ? null : s.decision(id);
		if (d != null) {
			decisionSeenAt.putIfAbsent(d.id(), 0L);
		}
		return d;
	}

	/** Focuses a field of the current context by id (DevBridge); false when it is not on screen. */
	boolean focusField(String id) {
		for (HubField f : fieldsInContext()) {
			if (f.id.equals(id)) {
				focus(f);
				return true;
			}
		}
		return false;
	}

	HubField messageField() {
		return message;
	}

	HubField planField() {
		return planEditor;
	}

	HubField instructionField() {
		return instruction;
	}
}
