package dev.agentcraft.client.decisions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.TextFieldView;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.console.TextModel;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.permissions.PermissionBody;
import dev.agentcraft.client.ui.TextUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The one answer component (docs/WAVE2.md W2): a decision's options as kit buttons, the free-text box when the
 * decision takes text (C1 {@code textAllowed}) or a merge's change request is being written, and the guards every
 * answer path shares:
 * <ul>
 *   <li>a decision that just came up ignores presses for {@value #ARM_MS} ms ({@link #bind} with {@code arm});</li>
 *   <li>Reject (merges) asks twice within 3 s;</li>
 *   <li>Merge asks twice when {@link Options#confirmMerge} (the Inbox, the goal thread, the agent card: one click there
 *       must never land a branch; the decision screen keeps its single press behind 1-9 and the arm, as before);</li>
 *   <li>Request changes first opens the feedback box and sends only with text;</li>
 *   <li>free text only when the decision accepts it; Foreman offline = nothing is sent.</li>
 * </ul>
 * Every answer goes through {@link DecisionsFeature#answer} unless the {@link Host} sends it itself (the decision
 * screen, which moves on through its queue). Used by {@link DecisionScreen}, the hub Inbox, the goal thread and the
 * agent card. Held keys and OS key repeats are the host's to detect: it passes {@code repeat} to {@link #keyPressed}.
 */
public final class AnswerPanel {
	/** Presses are ignored this long after a decision comes up by itself. */
	public static final int ARM_MS = 350;
	/** Reject / Merge confirms wait this long for the second press. */
	public static final int CONFIRM_MS = 3000;
	private static final int BTN_GAP = 8;
	private static final int ROW_H = 28;

	/** How the host shows and sends things. */
	public interface Host {
		/** The panel's text box gained or lost focus: SDL text input must follow (the host owns it). */
		void textFocus(boolean on);

		/**
		 * Sends the answer itself (true) or leaves it to the panel (false: {@link DecisionsFeature#answer}, a note under
		 * the buttons, the text put back on a refusal). {@code wasRequestChanges}: the change-request box was open.
		 */
		default boolean send(Decision d, @Nullable String option, @Nullable String text, String label, boolean wasRequestChanges) {
			return false;
		}

		/** A line for the player; true = the host shows it, false = the panel draws it under its buttons. */
		default boolean status(String message, boolean error) {
			return false;
		}

		/** Opens a merge's diff (the Review diff button); false = not possible here. */
		default boolean openDiff(Decision d) {
			return false;
		}

		/** An answer was taken (null error) or refused. */
		default void answered(Decision d, @Nullable String option, @Nullable String error) {
		}
	}

	/**
	 * @param confirmMerge Merge asks twice
	 * @param diffButton   a merge gets a "Review diff" button (the host's {@link Host#openDiff})
	 * @param numbers      option buttons show their 1-9 key
	 * @param fieldLines   the text box grows up to this many lines
	 */
	public record Options(boolean confirmMerge, boolean diffButton, boolean numbers, int fieldLines) {
		/** The decision screen: no merge confirm (1-9 + the arm guard it), Review diff, numbered buttons. */
		public static final Options SCREEN = new Options(false, true, true, 4);
		/** Embedded (Inbox, goal thread, agent card): Merge asks twice, no numbers. */
		public static final Options EMBEDDED = new Options(true, true, false, 3);
	}

	/** A button drawn last frame, relative to nothing (absolute GUI coordinates). */
	public record Btn(String id, @Nullable String option, String label, int number, int x, int y, int w, boolean primary, boolean danger,
		Action action) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + 20;
		}
	}

	public enum Action {
		OPTION, REVIEW_DIFF, SEND_TEXT, CANCEL_TEXT
	}

	private final Host host;
	private final Options opts;
	/** the text box's line cap now (the host may lower it: the Inbox at compact sizes uses 1) */
	private int fieldLines;
	private final TextModel text = new TextModel(4000);
	private final TextFieldView textView = new TextFieldView();
	private @Nullable String decisionId;
	private long armedAt;
	private boolean requestChanges;
	private long confirmRejectUntil;
	private long confirmMergeUntil;
	private int highlight = -1;
	private boolean textFocused;
	private boolean preview;
	/** Embedded sends waiting for the ack: the option sent ("" = free text). */
	private @Nullable String sendingId;
	private @Nullable String sendingOption;
	private @Nullable String note;
	private boolean noteError;
	private long noteAt;
	// last frame
	private final List<Btn> buttons = new ArrayList<>();
	private int fieldX;
	private int fieldY;
	private int fieldW;
	private int fieldH;

	public AnswerPanel(Host host, Options opts) {
		this.host = host;
		this.opts = opts;
		this.fieldLines = opts.fieldLines();
	}

	/** Caps the text box at {@code n} lines from now on (compact hosts: 1; the box scrolls to keep the caret in view). */
	public void fieldLines(int n) {
		fieldLines = Math.max(1, n);
	}

	// ------------------------------------------------------------------ state

	/**
	 * Shows {@code d} (null = nothing). A different decision resets the panel (text, confirms, highlight); {@code arm}:
	 * it came up by itself, so presses wait {@link #ARM_MS} again.
	 */
	public void bind(@Nullable Decision d, boolean arm) {
		String id = d == null ? null : d.id();
		if (java.util.Objects.equals(id, decisionId)) {
			return;
		}
		rebind(d, arm);
	}

	/** Like {@link #bind} but always resets, also for the decision already shown (the decision screen's switch). */
	public void rebind(@Nullable Decision d, boolean arm) {
		String id = d == null ? null : d.id();
		decisionId = id;
		requestChanges = false;
		confirmRejectUntil = 0;
		confirmMergeUntil = 0;
		text.clear();
		highlight = defaultHighlight(d);
		// the decision screen starts typing at once for a question without options; embedded hosts never take the keys
		focusText(opts.numbers() && d != null && d.kind() == DecisionKind.QUESTION && d.options().isEmpty() && d.isOpen());
		if (arm) {
			rearm();
		}
		if (note != null && !noteError) {
			note = null;
		}
	}

	/** Presses wait {@link #ARM_MS} from now (the screen opened, a decision came up). */
	public void rearm() {
		armedAt = Util.getMillis() + ARM_MS;
	}

	/** Lifts the arm delay now (the DevBridge: a scripted press is deliberate). Confirms in progress are kept. */
	public void armNow() {
		armedAt = 0;
	}

	public boolean armed() {
		return Util.getMillis() >= armedAt;
	}

	public @Nullable String decisionId() {
		return decisionId;
	}

	public int highlight() {
		return highlight;
	}

	public void setHighlight(int h) {
		highlight = h;
	}

	public boolean textFocused() {
		return textFocused;
	}

	public boolean requestChanges() {
		return requestChanges;
	}

	/** Leaves the change-request box (Esc / Cancel). */
	public void cancelRequestChanges() {
		requestChanges = false;
		focusText(false);
	}

	public String text() {
		return text.value();
	}

	public TextModel textModel() {
		return text;
	}

	/** Puts text back (a refused answer) and reopens the change request when it was one. */
	public void restore(String t, boolean wasRequestChanges) {
		if (text.isEmpty()) {
			text.set(t);
			requestChanges = wasRequestChanges;
			focusText(true);
		}
	}

	public void setText(String t) {
		text.set(t);
	}

	/** Previews never send anything (the decision screen's sample). */
	public void setPreview(boolean on) {
		preview = on;
	}

	public @Nullable String note() {
		return note;
	}

	public boolean noteError() {
		return noteError;
	}

	/** Whether {@code d}'s answer from here is on its way (embedded sends). */
	public boolean sending(Decision d) {
		return d.id().equals(sendingId);
	}

	public void focusText(boolean on) {
		if (textFocused == on) {
			return;
		}
		textFocused = on;
		text.touch();
		host.textFocus(on);
	}

	/** Free text right now: questions that accept it (C1), a merge's change request. */
	public boolean allowsText(Decision d) {
		return d.kind() == DecisionKind.QUESTION && d.freeText() || d.kind() == DecisionKind.MERGE && requestChanges;
	}

	/** Whether the text box is shown for {@code d} ({@code readOnly}: answered / sending / offline). */
	public boolean fieldVisible(Decision d, boolean readOnly) {
		return d.kind() == DecisionKind.QUESTION && d.freeText() && !readOnly || requestChanges;
	}

	public static int defaultHighlight(@Nullable Decision d) {
		// questions start on the first (recommended) option; merges and permissions on none, so a reflex Enter can never
		// merge or grant anything
		return d != null && d.kind() == DecisionKind.QUESTION && !d.options().isEmpty() ? 0 : -1;
	}

	public static String labelOf(Decision d, @Nullable String option) {
		if (option == null) {
			return "your answer";
		}
		return d.kind() == DecisionKind.PERMISSION ? PermissionBody.buttonLabel(option) : option;
	}

	private void status(String msg, boolean error) {
		if (!host.status(msg, error)) {
			note = msg;
			noteError = error;
			noteAt = Util.getMillis();
		}
	}

	/** The note under the buttons while it is fresh (embedded hosts), else null. */
	public @Nullable String shownNote() {
		if (note == null) {
			return null;
		}
		long age = Util.getMillis() - noteAt;
		return age < (noteError ? 9000 : 6000) || sendingId != null ? note : null;
	}

	// ------------------------------------------------------------------ answering

	/** An option was pressed (button, key, DevBridge): every guard applies. Returns true when something was sent. */
	public boolean choose(Decision d, String option) {
		if (preview) {
			status("Preview: \"" + labelOf(d, option) + "\" was not sent", false);
			return false;
		}
		if (d.id().equals(sendingId) || !d.isOpen() || DecisionsFeature.isAnswering(d.id())) {
			return false;
		}
		if (!armed()) {
			status("A new decision just came up: press again to answer it", false);
			return false;
		}
		if (Foreman.state() == null || Foreman.state().isStale() || !Foreman.connected()) {
			status("Foreman offline: answers are disabled until it reconnects", true);
			return false;
		}
		// only a press that counts moves the highlight (a dropped early press must not arm Enter)
		highlight = Math.max(0, d.options().indexOf(option));
		long now = Util.getMillis();
		if (d.kind() == DecisionKind.MERGE && option.equals(Protocol.REQUEST_CHANGES)) {
			if (!requestChanges) {
				requestChanges = true;
				focusText(true);
				status(opts.numbers() ? "Say what should change, then Enter" : "Say what should change, then Send feedback", false);
				return false;
			}
			if (text.value().isBlank()) {
				status("Type the feedback for the worker first", true);
				focusText(true);
				return false;
			}
		}
		if (d.kind() == DecisionKind.MERGE && option.equals(Protocol.REJECT) && now > confirmRejectUntil) {
			confirmRejectUntil = now + CONFIRM_MS;
			confirmMergeUntil = 0;
			status("Reject abandons the branch: press " + (opts.numbers() ? (d.options().indexOf(option) + 1) + " " : "Reject ") + "again", true);
			return false;
		}
		if (opts.confirmMerge() && d.kind() == DecisionKind.MERGE && option.equals(Protocol.MERGE) && now > confirmMergeUntil) {
			confirmMergeUntil = now + CONFIRM_MS;
			confirmRejectUntil = 0;
			status("Merge lands the branch: press Confirm merge", true);
			return false;
		}
		String t = text.value().isBlank() ? null : text.value().strip();
		if (d.kind() == DecisionKind.MERGE && !option.equals(Protocol.REQUEST_CHANGES) || !d.freeText()) {
			t = null;
		}
		submit(d, option, t);
		return true;
	}

	/**
	 * {@link #choose} with the text of a box the host owns (the goal thread's message box): a change request with text
	 * goes out at once (that box is the feedback box).
	 */
	public boolean chooseWith(Decision d, String option, @Nullable String externalText) {
		text.set(externalText == null ? "" : externalText);
		if (d.kind() == DecisionKind.MERGE && option.equals(Protocol.REQUEST_CHANGES) && externalText != null && !externalText.isBlank()) {
			requestChanges = true;
		}
		return choose(d, option);
	}

	/** {@link #sendText} with the text of a box the host owns. */
	public boolean sendTextWith(Decision d, @Nullable String externalText) {
		text.set(externalText == null ? "" : externalText);
		return sendText(d);
	}

	/** Reject is waiting for its confirming press. */
	public boolean confirmingReject() {
		return Util.getMillis() < confirmRejectUntil;
	}

	/** Merge is waiting for its confirming press ({@link Options#confirmMerge}). */
	public boolean confirmingMerge() {
		return Util.getMillis() < confirmMergeUntil;
	}

	/** The text box's Send / Enter: a free-text answer, or the change request's feedback. Returns true when sent. */
	public boolean sendText(Decision d) {
		if (d.kind() == DecisionKind.MERGE && requestChanges) {
			return choose(d, Protocol.REQUEST_CHANGES);
		}
		if (preview) {
			status("Preview: your answer was not sent", false);
			return false;
		}
		String t = text.value().strip();
		if (!d.freeText()) {
			status(d.id() + " takes one of its options only (no free text)", true);
			return false;
		}
		if (t.isEmpty()) {
			status("Type an answer, or pick an option" + (d.options().isEmpty() ? "" : " (1-" + d.options().size() + ")"), true);
			focusText(true);
			return false;
		}
		if (!armed() || d.id().equals(sendingId) || !d.isOpen() || DecisionsFeature.isAnswering(d.id())) {
			return false;
		}
		if (Foreman.state() == null || Foreman.state().isStale() || !Foreman.connected()) {
			status("Foreman offline: answers are disabled until it reconnects", true);
			return false;
		}
		submit(d, null, t);
		return true;
	}

	private void submit(Decision d, @Nullable String option, @Nullable String t) {
		String label = labelOf(d, option);
		boolean wasRequestChanges = requestChanges;
		confirmRejectUntil = 0;
		confirmMergeUntil = 0;
		if (host.send(d, option, t, label, wasRequestChanges)) {
			return;
		}
		// embedded: mark, send, report under the buttons; a refusal puts the text back
		String id = d.id();
		sendingId = id;
		sendingOption = option == null ? "" : option;
		if (t != null) {
			text.clear();
		}
		requestChanges = false;
		focusText(false);
		status("Sending " + id + ": " + label + "…", false);
		DecisionsFeature.answer(id, option, t).thenAccept(error -> {
			if (id.equals(sendingId)) {
				sendingId = null;
				sendingOption = null;
			}
			if (error == null) {
				status(UiBits.CHECK + " " + id + ": " + label, false);
			} else {
				if (t != null && id.equals(decisionId)) {
					restore(t, wasRequestChanges);
				}
				status(id + " was not sent: " + error, true);
			}
			host.answered(d, option, error);
		});
	}

	// ------------------------------------------------------------------ input

	/**
	 * A key while {@code d} is shown. Text editing while the box has focus (Enter sends; Shift+Enter is a new line);
	 * otherwise 1-9 pick options, the arrows move the highlight and Enter fires it. Esc, Tab and screen keys stay with the
	 * host. {@code repeat}: an OS key repeat or a key held since before the screen (ignored for anything that answers).
	 * {@code optionKeys}: digits/arrows/Enter answer (false in hosts that only answer by clicks). True = consumed.
	 */
	public boolean keyPressed(Font font, KeyEvent e, Decision d, boolean repeat, boolean optionKeys) {
		int k = e.key();
		if (textFocused) {
			if (TextKeys.isEnter(e)) {
				if (repeat) {
					return true;
				}
				if (TextKeys.enter(e, false) == dev.agentcraft.ui.UiRules.EnterAction.NEWLINE) {
					text.insert("\n");
				} else if (requestChanges) {
					choose(d, Protocol.REQUEST_CHANGES);
				} else if (text.isEmpty() && !d.options().isEmpty() && highlight >= 0 && optionKeys) {
					choose(d, d.options().get(Math.min(highlight, d.options().size() - 1)));
				} else {
					sendText(d);
				}
				return true;
			}
			if (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) {
				if (!text.vertical(font, TextFieldView.wrapWidth(font, text, Math.max(40, fieldW), style(d)), k == InputConstants.KEY_UP ? -1 : 1,
					e.hasShiftDown()) && k == InputConstants.KEY_UP && text.isEmpty()) {
					focusText(false);
				}
				return true;
			}
			TextKeys.handle(e, text);
			return true;
		}
		if (!optionKeys) {
			if (TextKeys.isPaste(e) && allowsText(d)) {
				focusText(true);
				TextKeys.handle(e, text);
				return true;
			}
			return false;
		}
		int n = d.options().size();
		int digit = TextKeys.digit(e);
		if (digit > 0) {
			if (repeat) {
				return true;
			}
			if (digit <= n) {
				choose(d, d.options().get(digit - 1));
			} else {
				status("No option " + digit, true);
			}
			return true;
		}
		if (TextKeys.isEnter(e)) {
			if (repeat) {
				return true;
			}
			if (n == 0) {
				focusText(true);
			} else if (highlight < 0) {
				status("Pick one with " + (n == 1 ? "1" : "1-" + n) + " (or the arrows, then Enter)", false);
			} else {
				choose(d, d.options().get(Math.min(highlight, n - 1)));
			}
			return true;
		}
		if (k == InputConstants.KEY_LEFT || k == InputConstants.KEY_UP) {
			if (n > 0) {
				highlight = highlight < 0 ? n - 1 : Math.max(0, highlight - 1);
			}
			return true;
		}
		if (k == InputConstants.KEY_RIGHT || k == InputConstants.KEY_DOWN) {
			if (n > 0) {
				highlight = highlight < 0 ? 0 : Math.min(n - 1, highlight + 1);
			}
			return true;
		}
		if (TextKeys.isPaste(e) && allowsText(d)) {
			focusText(true);
			TextKeys.handle(e, text);
			return true;
		}
		return false;
	}

	/** A typed character: into the box when focused; typing starts an answer where text is allowed (not digits). */
	public boolean charTyped(CharacterEvent e, Decision d, boolean startTyping) {
		int cp = e.codepoint();
		if (textFocused) {
			if (cp >= 32) {
				text.insert(e.codepointAsString());
			}
			return true;
		}
		if (startTyping && allowsText(d) && d.isOpen() && cp > 32 && !(cp >= '0' && cp <= '9')) {
			focusText(true);
			text.insert(e.codepointAsString());
			return true;
		}
		return false;
	}

	/** A click: a button of last frame, or the text box. True = it hit the panel. */
	public boolean mouseClicked(Font font, Decision d, double mx, double my, boolean shift) {
		for (Btn b : List.copyOf(buttons)) {
			if (b.hit(mx, my)) {
				run(d, b);
				return true;
			}
		}
		if (fieldH > 0 && mx >= fieldX && mx < fieldX + fieldW && my >= fieldY && my < fieldY + fieldH) {
			focusText(true);
			int idx = textView.hit(font, text, fieldX, fieldY, fieldW, style(d), mx, my);
			if (idx >= 0) {
				text.moveTo(idx, shift);
			}
			return true;
		}
		return false;
	}

	private void run(Decision d, Btn b) {
		switch (b.action()) {
			case OPTION -> choose(d, b.option());
			case REVIEW_DIFF -> reviewDiff(d);
			case SEND_TEXT -> sendText(d);
			case CANCEL_TEXT -> {
				cancelRequestChanges();
				note = null;
			}
		}
	}

	/** Presses a button of last frame by id ({@code opt:<option>}, {@code diff}, {@code send}, {@code cancel}); false when absent. */
	public boolean press(Decision d, String id) {
		for (Btn b : List.copyOf(buttons)) {
			if (b.id().equals(id)) {
				run(d, b);
				return true;
			}
		}
		return false;
	}

	public void reviewDiff(Decision d) {
		if (d.repoId() == null || d.worktree() == null) {
			status("This merge has no worktree to diff", true);
			return;
		}
		if (!host.openDiff(d)) {
			status("No diff screen here: the file list is the summary", false);
		}
	}

	// ------------------------------------------------------------------ layout and drawing

	private TextFieldView.Style style(Decision d) {
		String ph = requestChanges ? "What should change? (Enter sends it to the worker)" : d.options().isEmpty() ? "Type your answer… (Enter sends)"
			: "Or type your own answer…";
		return new TextFieldView.Style(null, 0, ph, null, null, 0, Math.max(1, fieldLines));
	}

	/** The buttons for {@code d} at width {@code w}, positions relative to (0, 0) (rows {@value #ROW_H} apart). */
	private List<Btn> layoutButtons(Font font, Decision d, int w) {
		List<Btn> out = new ArrayList<>();
		int bx = 0;
		int row = 0;
		long now = Util.getMillis();
		if (d.kind() == DecisionKind.MERGE && opts.diffButton()) {
			String l = "Review diff";
			int bw = Math.min(w, UiBits.buttonWidth(font, l, 0));
			out.add(new Btn("diff", null, l, 0, 0, 0, bw, false, false, Action.REVIEW_DIFF));
			bx = bw + BTN_GAP;
		}
		for (int i = 0; i < d.options().size(); i++) {
			String opt = d.options().get(i);
			String label = labelOf(d, opt);
			if (opt.equals(Protocol.REQUEST_CHANGES) && requestChanges) {
				label = "Send feedback";
			}
			if (opt.equals(Protocol.REJECT) && now < confirmRejectUntil) {
				label = "Confirm reject";
			}
			if (opt.equals(Protocol.MERGE) && d.kind() == DecisionKind.MERGE && now < confirmMergeUntil) {
				label = "Confirm merge";
			}
			int number = opts.numbers() ? i + 1 : 0;
			// sized for the label it shows now (Reject grows into "Confirm reject" only while confirming)
			int bw = Math.min(w, UiBits.buttonWidth(font, label, number));
			if (bx > 0 && bx + bw > w) {
				row++;
				bx = 0;
			}
			boolean danger = opt.equals(Protocol.REJECT) || opt.equals(Protocol.DENY);
			boolean primary = i == 0 && !(d.kind() == DecisionKind.MERGE && requestChanges)
				|| d.kind() == DecisionKind.MERGE && requestChanges && opt.equals(Protocol.REQUEST_CHANGES)
				|| opt.equals(Protocol.MERGE) && now < confirmMergeUntil;
			out.add(new Btn("opt:" + opt, opt, label, number, bx, row * ROW_H, bw, primary, danger, Action.OPTION));
			bx += bw + BTN_GAP;
		}
		if (d.kind() == DecisionKind.QUESTION && (d.options().isEmpty() || !opts.numbers() && d.freeText())) {
			String l = d.options().isEmpty() ? "Send answer" : "Send text";
			int bw = Math.min(w, UiBits.buttonWidth(font, l, 0));
			if (bx > 0 && bx + bw > w) {
				row++;
				bx = 0;
			}
			out.add(new Btn("send", null, l, 0, bx, row * ROW_H, bw, d.options().isEmpty(), false, Action.SEND_TEXT));
			bx += bw + BTN_GAP;
		}
		if (requestChanges) {
			int bw = UiBits.buttonWidth(font, "Cancel", 0);
			if (bx > 0 && bx + bw > w) {
				row++;
				bx = 0;
			}
			out.add(new Btn("cancel", null, "Cancel", 0, bx, row * ROW_H, bw, false, false, Action.CANCEL_TEXT));
		}
		return out;
	}

	/** Height of the button rows for {@code d} at width {@code w} (no text box, no note). */
	public int buttonsHeight(Font font, Decision d, int w) {
		int rows = 1;
		for (Btn b : layoutButtons(font, d, w)) {
			rows = Math.max(rows, b.y() / ROW_H + 1);
		}
		return rows * ROW_H - 8;
	}

	/** Height of the text box for {@code d} at width {@code w}, 0 when hidden. */
	public int fieldHeight(Font font, Decision d, int w, boolean readOnly) {
		return fieldVisible(d, readOnly) ? textView.height(font, text, w, style(d)) : 0;
	}

	/** The whole panel's height: text box (+6), buttons, and (embedded) a note line. */
	public int height(Font font, Decision d, int w, boolean readOnly, boolean withNote) {
		int fh = fieldHeight(font, d, w, readOnly);
		return (fh > 0 ? fh + 6 : 0) + 2 + buttonsHeight(font, d, w) + (withNote ? 12 : 0);
	}

	/** Draws the text box (when shown) at (x, y); returns its height + gap (0 when hidden). */
	public int drawField(GuiGraphicsExtractor g, Font font, Decision d, int x, int y, int w, boolean readOnly) {
		fieldH = 0;
		if (!fieldVisible(d, readOnly)) {
			return 0;
		}
		fieldX = x;
		fieldY = y;
		fieldW = w;
		fieldH = textView.draw(g, font, text, x, y, w, textFocused, style(d));
		return fieldH + 6;
	}

	/**
	 * Draws the buttons at (x, y) (4 px of room above for the focus ring); {@code busy}: answered / on its way / offline
	 * (options disabled); {@code chosen}: the option on its way (drawn pressed). Returns the height used.
	 */
	public int drawButtons(GuiGraphicsExtractor g, Font font, Decision d, int x, int y, int w, int mx, int my, boolean busy, @Nullable String chosen,
		boolean keyboardHighlight) {
		buttons.clear();
		int bottom = y;
		String sentOpt = chosen != null ? chosen : d.id().equals(sendingId) ? sendingOption : null;
		boolean isBusy = busy || d.id().equals(sendingId);
		for (Btn b : layoutButtons(font, d, w)) {
			Btn placed = new Btn(b.id(), b.option(), b.label(), b.number(), x + b.x(), y + b.y(), b.w(), b.primary(), b.danger(), b.action());
			buttons.add(placed);
			boolean hover = placed.hit(mx, my);
			int idx = b.action() == Action.OPTION ? d.options().indexOf(b.option()) : -2;
			boolean focused = keyboardHighlight && !isBusy && !textFocused && idx >= 0 && idx == highlight;
			boolean pressed = sentOpt != null && b.action() == Action.OPTION && sentOpt.equals(b.option());
			UiBits.ButtonState st = pressed ? UiBits.ButtonState.PRESSED : isBusy && b.action() != Action.REVIEW_DIFF ? UiBits.ButtonState.DISABLED
				: hover ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL;
			UiBits.button(g, font, b.label(), pressed ? 0 : b.number(), placed.x(), placed.y(), b.w(), b.primary(), st, b.danger(), focused);
			bottom = Math.max(bottom, placed.y() + 20);
		}
		return bottom - y;
	}

	/**
	 * Embedded hosts: the text box, the buttons and the note under them, from (x, y) at width {@code w}. {@code readOnly}:
	 * nothing can be answered right now. Returns the height used.
	 */
	public int draw(GuiGraphicsExtractor g, Font font, Decision d, int x, int y, int w, int mx, int my, boolean readOnly) {
		int y0 = y;
		bind(d, true);
		y += drawField(g, font, d, x, y, w, readOnly);
		y += 2;
		y += drawButtons(g, font, d, x, y, w, mx, my, readOnly, null, false);
		String n = shownNote();
		if (n != null) {
			y += 3;
			g.text(font, TextUtil.ellipsize(font, n, w), x, y, noteError ? UiBits.errorText() : n.startsWith(UiBits.CHECK) ? UiBits.okText()
				: UiBits.muted(), false);
			y += 9;
		}
		return y - y0;
	}

	/** Nothing drawn this frame (no clicks on stale buttons). */
	public void hide() {
		buttons.clear();
		fieldH = 0;
	}

	// ------------------------------------------------------------------ DevBridge

	public List<Btn> buttons() {
		return List.copyOf(buttons);
	}

	public JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("decisionId", decisionId);
		o.addProperty("armed", armed());
		o.addProperty("highlight", highlight);
		o.addProperty("textFocused", textFocused);
		o.addProperty("requestChanges", requestChanges);
		o.addProperty("text", text.value());
		o.addProperty("confirmReject", Util.getMillis() < confirmRejectUntil);
		o.addProperty("confirmMerge", Util.getMillis() < confirmMergeUntil);
		o.addProperty("sending", sendingId);
		o.addProperty("note", note);
		o.addProperty("noteError", noteError);
		o.addProperty("confirmMergeOption", opts.confirmMerge());
		JsonArray bs = new JsonArray();
		for (Btn b : buttons) {
			JsonObject j = new JsonObject();
			j.addProperty("id", b.id());
			j.addProperty("label", b.label());
			bs.add(j);
		}
		o.add("buttons", bs);
		return o;
	}
}
