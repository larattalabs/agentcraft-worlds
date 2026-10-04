package dev.agentcraft.client.decisions;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.Worktree;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.permissions.PermissionBody;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The decision GUI: one open decision at a time from {@link DecisionQueue} (permissions, then
 * questions, then merges), with the asking agent, the question, scrollable context, options as
 * buttons (keys 1-9), a free-text answer for questions, Merge / Request changes (with feedback) /
 * Reject plus "Review diff" for merges, and Allow once / Always allow / Deny with the risk and
 * scope for permissions. Tab / Shift+Tab walk the queue. Not a pause screen: the HQ keeps moving.
 *
 * <p>Answering is fast and safe at once:
 * <ul>
 *   <li>the next decision comes up as soon as an answer is sent (the footer reports "Sending d5:
 *       Merge…", then the ack); a refused answer brings its decision back with the error;</li>
 *   <li>option keys are ignored for {@value #ARM_MS} ms after the screen opens or a decision comes
 *       up by itself, and key repeats / keys held down from before the screen are ignored, so a
 *       double-tap or a held Enter never answers the next decision;</li>
 *   <li>Enter fires only a highlighted option: questions start on the first (recommended) one,
 *       merges and permissions on none (pick with 1-9, or the arrows then Enter); Space does nothing.</li>
 * </ul>
 */
public class DecisionScreen extends Screen implements dev.agentcraft.client.ui.HasParent {
	private static final int MAX_W = 440;
	/** Option keys are ignored this long after the screen opens or a decision comes up by itself. */
	static final int ARM_MS = AnswerPanel.ARM_MS;
	/** A decision answered somewhere else stays on screen (read-only) this long before moving on. */
	private static final int ELSEWHERE_MS = 1400;
	/** How long the footer reports the last answer ("✔ d5: Merge"). */
	private static final int LAST_MS = 5000;
	/** Gap between option buttons (the focus ring needs 4 px on each side). */
	private static final int BTN_GAP = 8;

	private @Nullable String currentId;
	private final @Nullable Screen parent;
	private final TextUtil.Scroll scroll = new TextUtil.Scroll();
	private boolean initialized;
	/** The options, the text box and their guards (W2): shared with the Inbox, the goal thread and the agent card. */
	private final AnswerPanel panel = new AnswerPanel(new AnswerPanel.Host() {
		@Override
		public void textFocus(boolean on) {
			if (minecraft != null) {
				minecraft.onTextInputFocusChange(DecisionScreen.this, on);
			}
		}

		@Override
		public boolean send(Decision d, @Nullable String option, @Nullable String text, String label, boolean wasRequestChanges) {
			DecisionScreen.this.send(d, option, text, label, wasRequestChanges);
			return true;
		}

		@Override
		public boolean status(String message, boolean error) {
			setStatus(message, error);
			return true;
		}

		@Override
		public boolean openDiff(Decision d) {
			return DiffLink.open(d.repoId(), d.worktree(), d, DecisionScreen.this);
		}
	}, AnswerPanel.Options.SCREEN);
	private @Nullable String status;
	private boolean statusError;
	private long statusAt;
	/** Decisions answered from this screen (sent, or confirmed): never reported as "answered elsewhere". */
	private final Set<String> mine = new HashSet<>();
	/** Answers waiting for the Foreman's ack: decision id -> the option sent (null = free text). */
	private final Map<String, @Nullable String> sending = new HashMap<>();
	/** The latest answer from this screen, for the footer. */
	private @Nullable Sent last;
	private @Nullable String elsewhereId;
	private long elsewhereUntil;
	private boolean closeWhenEmpty;
	private final Map<String, List<DiffLink.SummaryLine>> diffs = new HashMap<>();
	/** Scancodes physically down when the screen opened (the key that opened it, a held Enter): ignored until released. */
	private final Set<Integer> heldAtOpen = new HashSet<>();
	/** Scancodes pressed while this screen was open and not released yet (a second keyPressed = OS key repeat). */
	private final Set<Integer> downHere = new HashSet<>();

	// layout of the last frame (hit-testing)
	private int px;
	private int py;
	private int pw;
	private int ph;
	private int prevX0;
	private int nextX0;
	private int navY;

	/** An answer sent from this screen: {@code pending} until the ack, {@code error} when refused. */
	private record Sent(String id, String label, long at, boolean pending, @Nullable String error, boolean elsewhere) {
	}

	/** A sample decision shown when nothing real is open (QA / first look); answers are never sent. */
	private @Nullable Decision preview;

	public DecisionScreen(@Nullable String decisionId, @Nullable Screen parent) {
		super(Component.literal("Decisions"));
		this.currentId = decisionId;
		this.parent = parent;
	}

	/**
	 * The queue as one podium shows it (a podium's right-click, docs/BUILDINGS.md "Podiums"): only the decisions
	 * {@code scope} accepts, until the player switches to all of them (the header chip, or {@code A} while not
	 * typing). {@code scopeLabel} names the podium ("b3's podium").
	 */
	public DecisionScreen(@Nullable String decisionId, @Nullable Screen parent, java.util.function.Predicate<Decision> scope, String scopeLabel) {
		this(decisionId, parent);
		this.scope = scope;
		this.scopeLabel = scopeLabel;
	}

	/** The podium's filter (null = the whole queue) and whether the player switched to all decisions anyway. */
	private java.util.function.@Nullable Predicate<Decision> scope;
	private String scopeLabel = "";
	private boolean showAll;
	private int scopeX0 = -100;
	private int scopeX1 = -100;
	private int scopeY = -100;

	/** Whether this screen shows one podium's decisions (and not all of them right now). */
	public boolean scoped() {
		return scope != null && !showAll;
	}

	public @Nullable String scopeLabel() {
		return scope == null ? null : scopeLabel;
	}

	/** Switches between this podium's decisions and all of them (DevBridge, the chip, {@code A}). */
	public void toggleShowAll() {
		if (scope != null) {
			showAll = !showAll;
		}
	}

	private boolean inScope(Decision d) {
		return scope == null || showAll || scope.test(d);
	}

	/** Show {@code sample} as a preview: it looks like the real thing, but nothing is sent to the Foreman. */
	public static DecisionScreen preview(Decision sample) {
		DecisionScreen s = new DecisionScreen(sample.id(), null);
		s.preview = sample;
		s.panel.setPreview(true);
		return s;
	}

	@Override
	public @Nullable Screen parent() {
		return parent;
	}

	public boolean isPreview() {
		return preview != null;
	}

	@Override
	public boolean isPauseScreen() {
		return dev.agentcraft.client.ui.ScreenPause.pauses();
	}

	@Override
	public boolean isInputCaptured() {
		return panel.textFocused();
	}

	@Override
	protected void init() {
		if (initialized) {
			return; // a resize
		}
		initialized = true;
		for (int k : guardedKeys()) {
			if (InputConstants.isKeyDown(k)) {
				heldAtOpen.add(k);
			}
		}
		Decision d = current();
		if (d != null) {
			currentId = d.id();
			panel.rebind(d, false);
		}
		panel.rearm();
	}

	/** Keys that answer or close: watched for "held from before the screen" and repeats. */
	private static int[] guardedKeys() {
		int[] out = new int[9 + 9 + 3];
		int n = 0;
		for (int k = InputConstants.KEY_1; k <= InputConstants.KEY_9; k++) {
			out[n++] = k;
		}
		for (int k = 89; k <= 97; k++) {
			out[n++] = k; // SDL keypad 1-9
		}
		out[n++] = InputConstants.KEY_RETURN;
		out[n++] = InputConstants.KEY_NUMPADENTER;
		out[n++] = InputConstants.KEY_J;
		return out;
	}

	@Override
	public void removed() {
		panel.focusText(false);
		super.removed();
	}

	// ------------------------------------------------------------------ state

	/** Open decisions in queue order that are not being answered right now (from here, the console, ...). */
	private List<Decision> waiting() {
		List<Decision> out = new ArrayList<>();
		for (Decision d : DecisionQueue.open()) {
			if (!DecisionsFeature.isAnswering(d.id()) && inScope(d)) {
				out.add(d);
			}
		}
		return out;
	}

	private @Nullable Decision firstWaiting() {
		for (Decision d : DecisionQueue.open()) {
			if (!DecisionsFeature.isAnswering(d.id()) && inScope(d)) {
				return d;
			}
		}
		return null;
	}

	/**
	 * The shown decision: the current one while it is open (or our answer to it is on its way, or it
	 * was just answered elsewhere and is shown read-only for a moment), else the first waiting one.
	 */
	public @Nullable Decision current() {
		if (preview != null) {
			return preview;
		}
		ForemanState s = Foreman.state();
		if (s == null) {
			return null;
		}
		long now = Util.getMillis();
		if (currentId != null) {
			Decision d = s.decision(currentId);
			if (d != null) {
				if (sending.containsKey(d.id())) {
					return d; // our answer is on its way and nothing else was waiting
				}
				if (d.isOpen() && !DecisionsFeature.isAnswering(d.id())) {
					return d;
				}
				if (!d.isOpen() && !mine.contains(d.id())) {
					// answered somewhere else (console, another client) or withdrawn: say so, then move on
					if (!d.id().equals(elsewhereId)) {
						elsewhereId = d.id();
						elsewhereUntil = now + ELSEWHERE_MS;
						String how = d.status() == Protocol.DecisionStatus.CANCELLED ? "was withdrawn"
							: "answered elsewhere" + (d.answer() != null && d.answer().option() != null ? ": " + d.answer().option() : "");
						last = new Sent(d.id(), how, now, false, null, true);
						panel.focusText(false);
					}
					if (now < elsewhereUntil) {
						return d;
					}
				}
			}
		}
		Decision first = firstWaiting();
		if (first == null) {
			if (currentId != null && currentId.equals(elsewhereId) && !closeWhenEmpty) {
				// the last one was answered somewhere else: done here too
				closeWhenEmpty = true;
				setStatus("All caught up " + UiBits.CHECK, false);
			}
			currentId = null;
			return null;
		}
		if (!first.id().equals(currentId)) {
			switchTo(first.id(), true);
		}
		return first;
	}

	public @Nullable String currentId() {
		return currentId;
	}

	public int highlight() {
		return panel.highlight();
	}

	public boolean textFocused() {
		return panel.textFocused();
	}

	public boolean requestChangesMode() {
		return panel.requestChanges();
	}

	/** The shared answer component (DevBridge state). */
	public AnswerPanel panel() {
		return panel;
	}

	public @Nullable String status() {
		return status;
	}

	/** What the footer reports about the last answer ("sending d5: Merge", "d5: Merge"), or null. */
	public @Nullable String lastAnswer() {
		Sent l = last;
		if (l == null) {
			return null;
		}
		return (l.pending() ? "sending " : l.error() != null ? "failed " : "") + l.id() + ": " + l.label();
	}

	public boolean armed() {
		return panel.armed();
	}

	public String answerText() {
		return panel.text();
	}

	/** Show decision {@code id}; {@code auto}: it came up by itself (after an answer), so input is armed again. */
	private void switchTo(String id, boolean auto) {
		if (id.equals(currentId)) {
			return;
		}
		currentId = id;
		scroll.toTop();
		Decision d = Foreman.state() == null ? null : Foreman.state().decision(id);
		panel.rebind(d, auto);
		closeWhenEmpty = false;
		status = null;
	}

	private void step(int dir) {
		if (preview != null) {
			return;
		}
		List<Decision> q = waiting();
		if (q.isEmpty()) {
			return;
		}
		int i = -1;
		for (int j = 0; j < q.size(); j++) {
			if (q.get(j).id().equals(currentId)) {
				i = j;
			}
		}
		int n = q.size();
		int next = i < 0 ? 0 : ((i + dir) % n + n) % n;
		switchTo(q.get(next).id(), false);
	}

	/** Free text: questions that accept it (C1 {@code textAllowed}), a merge's change request. */
	private boolean allowsText(Decision d) {
		return panel.allowsText(d);
	}

	@Override
	public void tick() {
		ForemanState s = Foreman.state();
		if (s == null) {
			return;
		}
		long now = Util.getMillis();
		if (closeWhenEmpty && firstWaiting() == null && sending.isEmpty() && now - statusAt > 900) {
			onClose();
			return;
		}
		// fetch the diff summary of a merge once
		Decision d = current();
		if (d != null && d.kind() == DecisionKind.MERGE && d.repoId() != null && d.worktree() != null && !diffs.containsKey(d.id())
			&& Foreman.connected()) {
			String id = d.id();
			diffs.put(id, List.of());
			DiffLink.summary(d.repoId(), d.worktree()).thenAccept(lines -> diffs.put(id, lines));
		}
	}

	// ------------------------------------------------------------------ answering

	/** "Merge" -> "merge" for the footer hint, but the agent's own words keep their case ("CLI", "JSON"). */
	static String decap(String s) {
		if (s.isEmpty() || (s.length() > 1 && Character.isUpperCase(s.charAt(1)))) {
			return s;
		}
		return Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}

	/** The panel's answer: sent here, so the next decision comes up at once (the queue is this screen's). */
	private void send(Decision d, @Nullable String option, @Nullable String text, String label, boolean wasRequestChanges) {
		String id = d.id();
		long now = Util.getMillis();
		mine.add(id);
		sending.put(id, option);
		DecisionsFeature.markAnswering(id); // before picking the next one (DecisionsFeature.answer marks it too)
		last = new Sent(id, label, now, true, null, false);
		// the next decision comes up at once (armed again after ARM_MS); with nothing else waiting
		// this one stays, the chosen button pressed, until the ack
		Decision next = firstWaiting();
		if (next != null) {
			switchTo(next.id(), true);
		} else {
			panel.focusText(false);
			status = null;
		}
		DecisionsFeature.answer(id, option, text).thenAccept(error -> {
			sending.remove(id);
			long t = Util.getMillis();
			if (error == null) {
				last = new Sent(id, label, t, false, null, false);
				if (id.equals(currentId)) {
					Decision n = firstWaiting();
					if (n != null) {
						switchTo(n.id(), true);
					} else {
						currentId = null;
						closeWhenEmpty = true;
						setStatus("All caught up " + UiBits.CHECK, false);
					}
				}
				return;
			}
			mine.remove(id);
			String msg = error;
			last = new Sent(id, label, t, false, msg, false);
			if (minecraft != null && minecraft.gui.screen() != this) {
				// closed meanwhile: don't let the refusal go unnoticed
				Toasts.push(new Notify(NotifyLevel.WARN, "Your answer to " + id + " was not sent: " + msg, id, System.currentTimeMillis()));
			}
			// bring it back, with what was typed
			switchTo(id, false);
			if (text != null) {
				panel.restore(text, wasRequestChanges);
			}
			setStatus(id + " was not sent: " + msg, true);
		});
	}

	/** Moving the highlight answers a hint ("pick one with 1-3", "a new decision just came up"): drop it. */
	private void clearHint() {
		if (status != null && !statusError) {
			status = null;
		}
	}

	private void setStatus(String s, boolean error) {
		status = s;
		statusError = error;
		statusAt = Util.getMillis();
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		boolean physical = InputConstants.isKeyDown(k);
		// a key held since before the screen opened, or an OS key repeat (no release in between)
		boolean repeat = physical && (heldAtOpen.contains(k) || downHere.contains(k));
		if (physical) {
			downHere.add(k);
		}
		Decision d = current();
		if (e.isEscape()) {
			if (panel.textFocused() && (panel.requestChanges() || !panel.text().isEmpty())) {
				if (panel.requestChanges()) {
					panel.cancelRequestChanges();
				}
				panel.focusText(false);
				status = null;
				return true;
			}
			onClose();
			return true;
		}
		if (k == InputConstants.KEY_A && scope != null && !panel.textFocused() && !repeat) {
			toggleShowAll(); // this podium's decisions / all of them
			return true;
		}
		if (d == null) {
			if (!repeat && (TextKeys.isEnter(e) || Keys.matches(Keys.decisions, e))) {
				onClose();
			}
			return true;
		}
		if (k == InputConstants.KEY_TAB) {
			if (panel.textFocused() && !panel.requestChanges() && !allowsText(d)) {
				panel.focusText(false);
			}
			step(e.hasShiftDown() ? -1 : 1);
			return true;
		}
		boolean typing = panel.textFocused();
		if (panel.keyPressed(font, e, d, repeat, true)) {
			if (!typing && (k == InputConstants.KEY_LEFT || k == InputConstants.KEY_RIGHT || k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN)) {
				clearHint();
			}
			return true;
		}
		if (k == InputConstants.KEY_PAGEUP) {
			scroll.scrollBy(-4);
			return true;
		}
		if (k == InputConstants.KEY_PAGEDOWN) {
			scroll.scrollBy(4);
			return true;
		}
		if (k == InputConstants.KEY_D && d.kind() == DecisionKind.MERGE) {
			panel.reviewDiff(d);
			return true;
		}
		if (Keys.matches(Keys.decisions, e) && !allowsText(d)) {
			if (!repeat) {
				onClose();
			}
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean keyReleased(KeyEvent e) {
		downHere.remove(e.key());
		heldAtOpen.remove(e.key());
		return super.keyReleased(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		Decision d = current();
		if (d == null) {
			return false;
		}
		return panel.charTyped(e, d, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		Decision d = current();
		double mx = e.x();
		double my = e.y();
		if (scopeHit(mx, my)) {
			toggleShowAll();
			return true;
		}
		if (d == null) {
			return super.mouseClicked(e, doubleClick);
		}
		boolean wasRequestChanges = panel.requestChanges();
		if (panel.mouseClicked(font, d, mx, my, e.hasShiftDown())) {
			if (wasRequestChanges && !panel.requestChanges()) {
				status = null; // Cancel
			}
			return true;
		}
		if (my >= navY && my < navY + 12) {
			if (mx >= prevX0 && mx < prevX0 + 12) {
				step(-1);
				return true;
			}
			if (mx >= nextX0 && mx < nextX0 + 12) {
				step(1);
				return true;
			}
		}
		if (panel.textFocused() && panel.text().isEmpty() && !panel.requestChanges()) {
			panel.focusText(false);
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll.scrollBy(scrollY > 0 ? -2 : 2);
		return true;
	}


	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// blur the room (the player's menu blur setting) under a soft ink veil, so the paper is the focus
		extractBlurredBackground(g);
		int top = UiStyle.withAlpha(UiStyle.INK, 60);
		int bottom = UiStyle.withAlpha(UiStyle.INK, 110);
		g.fillGradient(0, 0, width, height, top, bottom);
	}


	/** The worker whose branch a merge decision is about (from the worktree), or null. */
	private static @Nullable Worktree worktreeOf(Decision d) {
		ForemanState s = Foreman.state();
		if (s == null || d.repoId() == null || d.worktree() == null) {
			return null;
		}
		Repo r = s.repo(d.repoId());
		if (r == null) {
			return null;
		}
		for (Worktree x : r.worktrees()) {
			if (x.id().equals(d.worktree())) {
				return x;
			}
		}
		return null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Decision d = current();
		ForemanState s = Foreman.state();
		if (d == null || s == null) {
			panel.hide();
			drawEmpty(g);
			return;
		}
		List<Decision> queue = preview != null ? List.of() : waiting();
		int qi = -1;
		for (int i = 0; i < queue.size(); i++) {
			if (queue.get(i).id().equals(d.id())) {
				qi = i;
			}
		}
		int qn = queue.size();
		boolean readOnly = preview == null && (!d.isOpen() || sending.containsKey(d.id()));

		pw = Math.min(MAX_W, width - 24);
		Kit.Padding pad = Kit.padding("frame_brass");
		int cx = pad.left();
		int cw = pw - pad.left() - pad.right();

		// ---- measure
		// closed choices (C1 textAllowed false) show no text box: only the options answer them
		int fieldHeight = panel.fieldHeight(font, d, cw, readOnly);
		boolean fieldVisible = fieldHeight > 0;
		int buttonsH = panel.buttonsHeight(font, d, cw) + 8;
		int maxH = height - 20;
		int bodyNatural = bodyNaturalHeight(d, cw);
		// the question gets what the rest leaves (at most 5 lines, at least 2; at 426x240 a long question with six options
		// pushed the frame off the screen): cut lines end in "…" and the whole question is a tooltip on it
		int rest = 18 /*header*/ + 26 /*agent*/ + 6 + (fieldVisible ? fieldHeight + 6 : 0) + buttonsH + 2 + 16 /*footer*/;
		int qRoom = (maxH - pad.top() - pad.bottom() - rest - (bodyNatural > 0 ? Math.min(bodyNatural, 30) + 6 : 0)) / 10;
		List<FormattedCharSequence> qLines = questionLines(d.question(), cw, Math.max(2, Math.min(5, qRoom)));
		int fixed = rest + qLines.size() * 10;
		int bodyMax = Math.max(30, maxH - fixed - pad.top() - pad.bottom());
		int bodyHeight = Math.min(bodyNatural, bodyMax);
		ph = pad.top() + fixed + (bodyHeight > 0 ? bodyHeight + 6 : 0) + pad.bottom();
		px = (width - pw) / 2;
		py = Math.max(8, (height - ph) / 2 - 4);

		// ---- frame
		Panels.framed(g, px, py, pw, ph);
		int x = px + cx;
		int y = py + pad.top();

		// header: kind icon + title, queue position on the right
		String title = switch (d.kind()) {
			case PERMISSION -> "Permission needed";
			case MERGE -> "Merge review";
			default -> "Decision needed";
		};
		Panels.sprite(g, Kit.HEADER, x - 2, y - 2, cw + 4, 16);
		Panels.sprite(g, Kit.icon(d.kind() == DecisionKind.MERGE ? "merge" : d.kind() == DecisionKind.PERMISSION ? "bash" : "decision"), x + 2, y - 1, 12,
			12);
		g.text(font, title, x + 18, y + 1, UiBits.ink(), false);
		if (!readOnlyNow(d)) {
			UiBits.pulsingDot(g, "waiting", x + 18 + font.width(title) + 6, y + 1);
		}
		drawScopeChip(g, x + 18 + font.width(title) + 18, y - 1);
		navY = y - 1;
		if (preview != null) {
			// a sample, not a real request: say so where the queue position would be
			String tag = "preview";
			int tw = font.width(tag) + 10;
			Panels.sprite(g, Kit.PILL, x + cw - tw, navY, tw, 11);
			g.text(font, tag, x + cw - tw + 5, navY + 2, UiBits.muted(), false);
			prevX0 = nextX0 = -100;
		} else if (qn > 1 || qi < 0 && qn > 0) {
			// position in the queue; "2 left" while a decision that left the queue is still on screen
			String pos = qi < 0 ? qn + " left" : (qi + 1) + "/" + qn;
			int pwid = font.width(pos) + 10;
			nextX0 = x + cw - 12;
			Panels.sprite(g, Kit.KEYCAP, nextX0, navY, 12, 12);
			g.text(font, "›", nextX0 + 4, navY + 2, UiBits.ink(), false);
			int pillX = nextX0 - 3 - pwid;
			Panels.sprite(g, Kit.PILL, pillX, navY, pwid, 11);
			g.text(font, pos, pillX + 5, navY + 2, UiBits.ink(), false);
			prevX0 = pillX - 15;
			Panels.sprite(g, Kit.KEYCAP, prevX0, navY, 12, 12);
			g.text(font, "‹", prevX0 + 4, navY + 2, UiBits.ink(), false);
		} else {
			prevX0 = nextX0 = -100;
		}
		y += 18;

		// agent row: who asks, and for a merge whose work it is
		UiBits.framedPortrait(g, d.agentId(), x, y, 1);
		String name = UiBits.agentName(d.agentId());
		Worktree wt = d.kind() == DecisionKind.MERGE ? worktreeOf(d) : null;
		int ax = x + 26;
		g.text(font, name, ax, y + 1, UiBits.nameOnLight(d.agentId()), false);
		ax += font.width(name);
		int room = cw - (ax - x);
		if (d.kind() == DecisionKind.MERGE && wt != null && !wt.agentId().equals(d.agentId())) {
			String worker = UiBits.agentName(wt.agentId());
			String pre = " asks you to merge ";
			String post = "'s work";
			if (font.width(pre + worker + post) <= room) {
				g.text(font, pre, ax, y + 1, UiBits.ink(), false);
				ax += font.width(pre);
				g.text(font, worker, ax, y + 1, UiBits.nameOnLight(wt.agentId()), false);
				ax += font.width(worker);
				g.text(font, post, ax, y + 1, UiBits.ink(), false);
			} else {
				g.text(font, TextUtil.ellipsize(font, " asks you to review a merge", room), ax, y + 1, UiBits.ink(), false);
			}
		} else {
			String verb = switch (d.kind()) {
				case PERMISSION -> " wants your permission";
				case MERGE -> " asks you to review a merge";
				default -> " asks you";
			};
			g.text(font, TextUtil.ellipsize(font, verb, room), ax, y + 1, UiBits.ink(), false);
		}
		// when it was asked and for which task (the title only when the question doesn't already say it)
		StringBuilder sub = new StringBuilder("asked ").append(UiBits.ago(d.createdAt()));
		Task t = d.taskId() == null ? null : s.task(d.taskId());
		if (t != null) {
			sub.append(" · task ").append(t.id());
			String tt = UiBits.oneLine(t.title());
			if (!tt.isEmpty() && !d.question().contains(tt)) {
				sub.append(' ').append(tt);
			}
		}
		g.text(font, TextUtil.ellipsize(font, sub.toString(), cw - 26), x + 26, y + 11, UiBits.muted(), false);
		y += 26;

		// question
		int qy = y;
		for (FormattedCharSequence line : qLines) {
			g.text(font, line, x, y, UiBits.ink(), false);
			y += 10;
		}
		if (questionCut && mouseX >= x && mouseX < x + cw && mouseY >= qy && mouseY < y) {
			g.setTooltipForNextFrame(font, font.split(Component.literal(d.question()), Math.min(cw, 300)), mouseX, mouseY);
		}
		y += 6;

		// body
		if (bodyHeight > 0) {
			drawBody(g, d, x, y, cw, bodyHeight);
			y += bodyHeight + 6;
		}

		// free-text field
		y += panel.drawField(g, font, d, x, y, cw, readOnly);

		// buttons (4 px of room above for the focus ring)
		y += 2;
		long now = Util.getMillis();
		boolean sendingThis = sending.containsKey(d.id());
		boolean busy = readOnly || s.isStale() && preview == null;
		panel.drawButtons(g, font, d, x, y, cw, mouseX, mouseY, busy, sendingThis ? sending.get(d.id()) : null, true);

		// footer: status right (it wins the room), key hints left
		int fy = py + ph - pad.bottom() - 12;
		drawFooter(g, d, x, fy, cw, queue, qi, now);
	}

	private boolean questionCut;

	/** The question in bold, wrapped to {@code w}, at most {@code max} lines; a cut last line ends in "…" ({@link #questionCut}). */
	private List<FormattedCharSequence> questionLines(String q, int w, int max) {
		net.minecraft.network.chat.Style bold = net.minecraft.network.chat.Style.EMPTY.withBold(true);
		List<String> lines = new java.util.ArrayList<>();
		for (net.minecraft.network.chat.FormattedText t : font.getSplitter().splitLines(q, w, bold)) {
			lines.add(t.getString());
		}
		questionCut = lines.size() > max;
		if (questionCut) {
			lines = new java.util.ArrayList<>(lines.subList(0, max));
			String last = lines.get(max - 1).stripTrailing();
			while (!last.isEmpty() && font.width(Component.literal(last + "…").withStyle(bold)) > w) {
				last = last.substring(0, last.length() - 1).stripTrailing();
			}
			lines.set(max - 1, last + "…");
		}
		List<FormattedCharSequence> out = new java.util.ArrayList<>();
		for (String l : lines) {
			out.add(Component.literal(l).withStyle(bold).getVisualOrderText());
		}
		return out;
	}

	/** The podium chip: "b3's podium · A: all" / "all decisions · A: b3's podium" (clickable). */
	private void drawScopeChip(GuiGraphicsExtractor g, int x, int y) {
		scopeX0 = scopeX1 = scopeY = -100;
		if (scope == null) {
			return;
		}
		String label = showAll ? "all decisions · A: " + scopeLabel : scopeLabel + " · A: all";
		int w = font.width(label) + 10;
		Panels.sprite(g, Kit.PILL, x, y, w, 11);
		g.text(font, label, x + 5, y + 2, UiBits.muted(), false);
		scopeX0 = x;
		scopeX1 = x + w;
		scopeY = y;
	}

	private boolean scopeHit(double mx, double my) {
		return mx >= scopeX0 && mx < scopeX1 && my >= scopeY && my < scopeY + 11;
	}

	private void drawEmpty(GuiGraphicsExtractor g) {
		Kit.Padding p = Kit.padding("panel_paper");
		boolean stale = Foreman.state() == null || Foreman.state().isStale();
		boolean caughtUp = status != null && !statusError;
		String head = caughtUp ? status : stale ? "Foreman offline" : "No decisions waiting";
		String sub;
		Sent l = last;
		if (caughtUp && l != null && !l.pending() && l.error() == null && !l.elsewhere()) {
			sub = "last: " + l.id() + " → " + l.label();
		} else {
			sub = stale ? "decisions show up again once it reconnects" : "your team will ask when they need you";
		}
		if (scoped()) {
			int elsewhere = 0;
			for (Decision x : DecisionQueue.open()) {
				if (!DecisionsFeature.isAnswering(x.id())) {
					elsewhere++;
				}
			}
			if (elsewhere > 0) {
				head = "Nothing for " + scopeLabel;
				sub = elsewhere + " waiting elsewhere · A shows all";
			}
		}
		// sized to what it says
		int w = Math.min(width - 24, Math.max(180, Math.max(12 + font.width(head), font.width(sub)) + p.left() + p.right() + 4));
		int h = 64;
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		Panels.panel(g, x, y, w, h);
		Panels.dot(g, stale && !caughtUp ? "idle" : "done", x + p.left(), y + p.top() + 1, false);
		g.text(font, head, x + p.left() + 12, y + p.top(), UiBits.ink(), false);
		g.text(font, TextUtil.ellipsize(font, sub, w - p.left() - p.right()), x + p.left(), y + p.top() + 14, UiBits.muted(), false);
		UiBits.hints(g, font, x + p.left(), y + h - p.bottom() - 13, false, "Esc", "close");
	}

	private void drawFooter(GuiGraphicsExtractor g, Decision d, int x, int y, int w, List<Decision> queue, int qi, long now) {
		// what to say on the right, most important first
		String st = null;
		int color = UiBits.muted();
		Sent l = last;
		if (status != null && (statusError ? now - statusAt < 7000 : now - statusAt < 6000)) {
			st = status;
			color = statusError ? UiBits.errorText() : UiBits.muted();
		} else if (l != null && l.error() == null && (l.pending() || now - l.at() < LAST_MS)) {
			if (l.pending()) {
				st = "Sending " + l.id() + ": " + l.label() + "…";
			} else {
				st = UiBits.CHECK + " " + l.id() + (l.elsewhere() ? " " : ": ") + l.label();
				color = UiBits.okText();
			}
		} else if (preview == null && Foreman.state().isStale()) {
			st = "Foreman offline: read-only";
			color = UiBits.errorText();
		} else if (queue.size() > 1 && preview == null) {
			Decision next = queue.get(((qi < 0 ? -1 : qi) + 1) % queue.size());
			if (!next.id().equals(d.id())) {
				st = "next: " + UiBits.agentName(next.agentId()) + " · " + DecisionQueue.kindLabel(next.kind());
			}
		}
		int stW = st == null ? 0 : Math.min(font.width(st), w - 50);

		// key hints as (key, verb, priority); the least important go first when room is short
		List<String[]> hints = new ArrayList<>();
		boolean requestChanges = panel.requestChanges();
		int highlight = panel.highlight();
		if (panel.textFocused()) {
			hints.add(new String[] {"Enter", requestChanges ? "send feedback" : "send", "5"});
			hints.add(new String[] {"Esc", requestChanges ? "back" : "stop typing", "4"});
			hints.add(new String[] {"Shift+Enter", "new line", "1"});
		} else {
			int n = d.options().size();
			if (n > 0 && !readOnlyNow(d)) {
				hints.add(new String[] {n == 1 ? "1" : "1-" + n, "choose", "5"});
			}
			if (highlight >= 0 && highlight < n && !readOnlyNow(d)) {
				hints.add(new String[] {"Enter", decap(AnswerPanel.labelOf(d, d.options().get(highlight))), "4"});
			}
			if (d.kind() == DecisionKind.MERGE) {
				hints.add(new String[] {"D", "diff", "2"});
			}
			if (queue.size() > 1 && preview == null) {
				hints.add(new String[] {"Tab", "next", "1"});
			}
			hints.add(new String[] {"Esc", "later", "3"});
		}
		// the status wins the room: drop the least important hints until both fit
		int room = w - (stW > 0 ? stW + 12 : 0);
		String[] arr = flatten(hints);
		while (!hints.isEmpty() && UiBits.hintsWidth(font, arr) > room) {
			int drop = 0;
			for (int i = 1; i < hints.size(); i++) {
				if (Integer.parseInt(hints.get(i)[2]) < Integer.parseInt(hints.get(drop)[2])) {
					drop = i;
				}
			}
			hints.remove(drop);
			arr = flatten(hints);
		}
		if (arr.length > 0) {
			UiBits.hints(g, font, x, y, false, arr);
		}
		if (st != null) {
			String shown = TextUtil.ellipsize(font, st, stW);
			g.text(font, shown, x + w - font.width(shown), y + 2, color, false);
		}
	}

	private static String[] flatten(List<String[]> hints) {
		String[] out = new String[hints.size() * 2];
		for (int i = 0; i < hints.size(); i++) {
			out[2 * i] = hints.get(i)[0];
			out[2 * i + 1] = hints.get(i)[1];
		}
		return out;
	}

	/** Nothing can be answered right now: answered / on its way, or the Foreman is offline. */
	private boolean readOnlyNow(Decision d) {
		return preview == null && (!d.isOpen() || sending.containsKey(d.id()) || Foreman.state() == null || Foreman.state().isStale());
	}

	// ------------------------------------------------------------------ bodies

	/** One line of a body well: text runs with colours, an optional row tint, an optional agent face before the text. */
	private record Run(String text, int color) {
	}

	private record Row(List<Run> runs, int bg, List<Run> right, @Nullable String face) {
		Row(List<Run> runs, int bg) {
			this(runs, bg, List.of(), null);
		}

		static Row of(String text, int color) {
			return new Row(List.of(new Run(text, color)), 0);
		}
	}

	private int bodyNaturalHeight(Decision d, int cw) {
		if (d.kind() == DecisionKind.PERMISSION) {
			return PermissionBody.height(font, d, cw);
		}
		List<Row> rows = bodyRows(d, cw - 14);
		if (rows.isEmpty()) {
			return 0;
		}
		return 8 + rows.size() * 10;
	}

	private void drawBody(GuiGraphicsExtractor g, Decision d, int x, int y, int w, int h) {
		if (d.kind() == DecisionKind.PERMISSION) {
			g.enableScissor(x, y, x + w, y + h);
			PermissionBody.draw(g, font, d, x, y, w);
			g.disableScissor();
			return;
		}
		List<Row> rows = bodyRows(d, w - 14);
		Panels.inset(g, x, y, w, h);
		int view = Math.max(1, (h - 8) / 10);
		scroll.update(rows.size(), view);
		if (scroll.following() && rows.size() > view) {
			// start at the top, not the tail: context reads top-down
			scroll.toTop();
		}
		int ly = y + 4;
		g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
		for (int i = scroll.offset(); i < Math.min(rows.size(), scroll.offset() + view); i++) {
			Row r = rows.get(i);
			if (r.bg() != 0) {
				g.fill(x + 3, ly - 1, x + w - (scroll.scrollable() ? 10 : 3), ly + 9, r.bg());
			}
			int rx = x + 6;
			if (r.face() != null) {
				UiBits.face(g, r.face(), rx, ly, 1);
				rx += 11;
			}
			for (Run run : r.runs()) {
				g.text(font, run.text(), rx, ly, run.color(), false);
				rx += font.width(run.text());
			}
			int rw = 0;
			for (Run run : r.right()) {
				rw += font.width(run.text());
			}
			int rxr = x + w - (scroll.scrollable() ? 12 : 6) - rw;
			for (Run run : r.right()) {
				g.text(font, run.text(), rxr, ly, run.color(), false);
				rxr += font.width(run.text());
			}
			ly += 10;
		}
		g.disableScissor();
		Panels.scrollbar(g, x + w - 8, y + 2, h - 4, scroll, false);
	}

	private List<Row> bodyRows(Decision d, int w) {
		List<Row> rows = new ArrayList<>();
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		int path = UiStyle.color("paper.path", 0xFF6C5415);
		int addFg = UiStyle.color("paper.add_fg", 0xFF455746);
		int delFg = UiStyle.color("paper.del_fg", 0xFF873C2A);
		if (d.kind() == DecisionKind.MERGE) {
			Worktree wt = worktreeOf(d);
			if (wt != null) {
				// the branch only when the question doesn't already name it
				if (!d.question().contains(wt.branch())) {
					rows.add(new Row(List.of(new Run(TextUtil.ellipsize(font, wt.branch(), w - 50), path), new Run(" → " + wt.base(), path)), 0));
				}
				// whose work + the stats, the worker's face first
				List<Run> stat = new ArrayList<>();
				String worker = UiBits.agentName(wt.agentId());
				stat.add(new Run(worker, UiBits.nameOnLight(wt.agentId())));
				stat.add(new Run("  ·  " + UiBits.plural(wt.files(), "file", "files") + "  ", ink));
				stat.add(new Run("+" + wt.additions(), addFg));
				stat.add(new Run(" −" + wt.deletions(), delFg));
				stat.add(new Run("  ·  " + UiBits.plural(wt.ahead(), "commit", "commits"), muted));
				String ctxl = d.context() == null ? "" : d.context().toLowerCase(Locale.ROOT);
				if (ctxl.contains("tests: pass")) {
					stat.add(new Run("  ·  tests pass", addFg));
				} else if (ctxl.contains("tests: fail")) {
					stat.add(new Run("  ·  tests fail", UiBits.errorText()));
				}
				rows.add(new Row(stat, 0, List.of(), UiBits.hasPortrait(wt.agentId()) ? wt.agentId() : null));
			}
			List<DiffLink.SummaryLine> files = diffs.get(d.id());
			if (files != null && !files.isEmpty()) {
				for (DiffLink.SummaryLine l : files) {
					if (l.header()) {
						continue;
					}
					if (l.error()) {
						rows.add(Row.of(TextUtil.ellipsize(font, l.text(), w), UiBits.errorText()));
						continue;
					}
					String counts = l.additions() + l.deletions() > 0 ? "+" + l.additions() + " −" + l.deletions() : "";
					String pathPart = l.text().strip();
					int room = w - font.width(counts) - 16;
					List<Run> right = counts.isEmpty() ? List.of() : List.of(new Run("+" + l.additions(), addFg), new Run(" −" + l.deletions(), delFg));
					rows.add(new Row(List.of(new Run(TextUtil.ellipsize(font, pathPart, room), ink)), 0, right, null));
				}
			} else if (files != null && Foreman.connected()) {
				rows.add(Row.of("  loading the file list…", muted));
			}
		}
		String ctx = d.context() == null ? "" : d.context().strip();
		if (!ctx.isEmpty()) {
			if (!rows.isEmpty()) {
				rows.add(Row.of("", ink));
			}
			// +/- lines are tinted as a diff only inside a diff block (after a file path or an @@ header);
			// elsewhere "- item" is just a list
			boolean inDiff = false;
			for (String para : ctx.split("\n")) {
				String p = para.stripTrailing();
				int color = ink;
				int bg = 0;
				boolean isPath = p.matches("^[\\w./\\\\-]+\\.[a-zA-Z0-9]{1,5}$");
				boolean diffLine = p.startsWith("+") || p.startsWith("-") || p.startsWith("@@") || p.startsWith(" ");
				if (isPath || p.startsWith("@@")) {
					inDiff = true;
				} else if (!diffLine) {
					inDiff = false;
				}
				if (p.startsWith("Merge refused:")) {
					color = UiBits.errorText();
				} else if (inDiff && p.startsWith("@@")) {
					color = UiStyle.color("paper.hunk", 0xFF195E5D);
				} else if (inDiff && p.startsWith("+")) {
					color = addFg;
					bg = UiStyle.color("paper.add_bg", 0xFFD6DDCC);
				} else if (inDiff && p.startsWith("-")) {
					color = delFg;
					bg = UiStyle.color("paper.del_bg", 0xFFF2D5C4);
				} else if (isPath) {
					color = path;
				} else if (d.kind() == DecisionKind.MERGE && p.matches("^\\d+ files?, \\+\\d+ -\\d+.*") && !rows.isEmpty()) {
					// the diff stat line duplicates the stats row above
					continue;
				}
				for (String line : TextUtil.wrapPlain(font, p, w)) {
					rows.add(new Row(List.of(new Run(line, color)), bg));
				}
			}
		}
		return rows;
	}
}
