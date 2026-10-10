package dev.agentcraft.client.hud;

import dev.agentcraft.client.decisions.DecisionScreen;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.hud.HudLayout;
import dev.agentcraft.hud.ToastStack;
import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * In-game paper toasts for the Foreman's {@code notify}: the agent's framed portrait, who it is
 * about, two lines of text; "need you" toasts get a clay stripe and a key hint: the decisions key ({@code J})
 * when the toast is about a decision, else the hub key ({@code H}, e.g. a blocked task or a reply); a toast
 * can also carry its own hint ({@link #push(Notify, String, String)}, the away toast). They slide
 * in beside the overlay ({@link HudOverlay#toastColumn}: under it, or above it when it sits at the bottom), stack (need-you toasts first, then newest on top, three at most,
 * {@link ToastStack}), and leave early once their decision is answered. Toasts that do not fit (three already, or the
 * screen is too short: 426x240) wait their turn and are counted on one "+N more" line; their time starts when they
 * show. Info toasts never push out or hide a need-you toast. Not shown while the decision screen is open.
 */
public final class Toasts implements HudElement {
	static final int W = 196;
	/** A toast that never got room is dropped after this long (need-you toasts wait longer). */
	private static final long QUEUE_MS = 30_000;
	private static final long QUEUE_NEED_MS = 120_000;
	private static final int MORE_H = 13;
	private static final int SLIDE_MS = 180;
	private static final int FADE_MS = 350;
	private static final List<Toast> ACTIVE = new ArrayList<>();
	private static int shown;
	private static final List<java.util.function.Predicate<Notify>> DROP = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** {@code start}: when it first showed (-1 while it waits); {@code life} counts from then. */
	private static final class Toast {
		final Notify n;
		final @Nullable String agentId;
		final String title;
		final String body;
		final long arrived;
		long start = -1;
		long life;
		final @Nullable String decisionId;
		final @Nullable String hintKey;
		final String hintVerb;

		Toast(Notify n, @Nullable String agentId, String title, String body, long arrived, long life, @Nullable String decisionId,
			@Nullable String hintKey, String hintVerb) {
			this.n = n;
			this.agentId = agentId;
			this.title = title;
			this.body = body;
			this.arrived = arrived;
			this.life = life;
			this.decisionId = decisionId;
			this.hintKey = hintKey;
			this.hintVerb = hintVerb;
		}

		boolean need() {
			return n.level() == NotifyLevel.NEED_USER;
		}

		boolean over(long now) {
			return start >= 0 ? now - start > life : now - arrived > (need() ? QUEUE_NEED_MS : QUEUE_MS);
		}
	}

	/** The last layout (QA, {@code dev.hud.state toasts}). */
	private static ToastStack.Layout lastLayout = new ToastStack.Layout(List.of(), List.of(), 0, 0, -1);
	private static List<Toast> lastOrder = List.of();
	private static int lastX;

	/** QA: the key hint of the newest toast ("J answer", "H open"), null = none. */
	public static @Nullable String lastHint() {
		if (ACTIVE.isEmpty()) {
			return null;
		}
		Toast t = ACTIVE.get(0);
		return t.hintKey == null ? null : t.hintKey + " " + t.hintVerb;
	}

	/** QA: the newest toast's title and body. */
	public static @Nullable String lastText() {
		return ACTIVE.isEmpty() ? null : ACTIVE.get(0).title + ": " + ACTIVE.get(0).body;
	}

	public static void init() {
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onNotify(Notify n) {
				for (java.util.function.Predicate<Notify> d : DROP) {
					if (d.test(n)) {
						return;
					}
				}
				// hub Settings > General > HUD > Toasts: "Needs you" (default) or "All"
				if (!dev.agentcraft.hud.HudVisibility.toastFor(HudConfig.get().toasts(), n.level() == NotifyLevel.NEED_USER)) {
					return;
				}
				push(n);
			}

			@Override
			public void onDecision(@Nullable Decision previous, Decision decision) {
				if (!decision.isOpen()) {
					expireFor(decision.id());
				}
			}
		});
	}

	/**
	 * Drop Foreman notifies matching {@code drop} (a feature that shows its own, richer toast for the same
	 * event, e.g. building designs). {@link #push} itself is never filtered.
	 */
	public static void addFilter(java.util.function.Predicate<Notify> drop) {
		DROP.add(drop);
	}

	public static int shown() {
		return shown;
	}

	public static int active() {
		return ACTIVE.size();
	}

	/** Add a toast for a notify (client thread); "need you" toasts get the right key hint. */
	public static void push(Notify n) {
		push(n, null, null);
	}

	/**
	 * Add a toast with its own key hint ({@code hintKey} null = the default: for "need you", the decisions key when
	 * it is about a decision, else the hub key).
	 */
	public static void push(Notify n, @Nullable String hintKey, @Nullable String hintVerb) {
		ForemanState s = Foreman.state();
		String agentId = null;
		String body = n.text();
		if (n.decisionId() != null && s != null && s.decision(n.decisionId()) != null) {
			agentId = s.decision(n.decisionId()).agentId();
		}
		// "Marlow: Merge t2 ..." -> agent Marlow, body after the colon
		int colon = body.indexOf(": ");
		if (colon > 0 && colon < 24 && s != null) {
			String who = body.substring(0, colon).strip().toLowerCase(Locale.ROOT);
			for (Agent a : s.agents().values()) {
				if (a.name().toLowerCase(Locale.ROOT).equals(who) || a.id().equals(who)) {
					agentId = agentId == null ? a.id() : agentId;
					body = body.substring(colon + 2);
					break;
				}
			}
		}
		String title = switch (n.level()) {
			case NEED_USER -> (agentId != null ? AgentBits.agentName(agentId) : "Your team") + " needs you";
			case WARN -> agentId != null ? AgentBits.agentName(agentId) : "Heads up";
			default -> agentId != null ? AgentBits.agentName(agentId) : "Foreman";
		};
		long life = switch (n.level()) {
			case NEED_USER -> 9000;
			case WARN -> 8000;
			default -> 5500;
		};
		if (hintKey == null && n.level() == NotifyLevel.NEED_USER) {
			if (n.decisionId() != null) {
				hintKey = Keys.decisions == null ? "J" : Keys.label(Keys.decisions);
				hintVerb = "answer";
			} else {
				hintKey = Keys.hub == null ? "H" : Keys.label(Keys.hub);
				hintVerb = "open hub";
			}
		}
		ACTIVE.add(0, new Toast(n, agentId, title, body, Util.getMillis(), life, n.decisionId(), hintKey, hintVerb == null ? "" : hintVerb));
		for (int drop = ToastStack.evict(needs(ACTIVE), ToastStack.MAX_KEPT); drop >= 0; drop = ToastStack.evict(needs(ACTIVE), ToastStack.MAX_KEPT)) {
			ACTIVE.remove(drop); // the oldest info toast goes first: a need-you toast is never pushed out by news
		}
		shown++;
	}

	private static List<Boolean> needs(List<Toast> ts) {
		List<Boolean> out = new ArrayList<>(ts.size());
		for (Toast t : ts) {
			out.add(t.need());
		}
		return out;
	}

	private static void expireFor(String decisionId) {
		long now = Util.getMillis();
		ACTIVE.removeIf(t -> decisionId.equals(t.decisionId) && t.start < 0); // answered before it showed: never show it
		for (Toast t : ACTIVE) {
			if (decisionId.equals(t.decisionId)) {
				long end = Math.min(t.start + t.life, now + FADE_MS);
				t.life = Math.max(0, end - t.start);
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		long now = Util.getMillis();
		ACTIVE.removeIf(t -> t.over(now));
		if (mc.player == null || ACTIVE.isEmpty() || mc.gui.screen() instanceof DecisionScreen || mc.gui.hud.isHidden()) {
			return;
		}
		Font font = mc.font;
		// the column beside the overlay (HudOverlay draws first): under it at the top / right middle, above it at the bottom,
		// clear of boss bars, effect icons, the connection pill, the hotbar with its status rows and the chat
		HudLayout.Column col = HudOverlay.toastColumn();
		int y = col.top();
		int limit = col.bottom();
		// never over the placement panel (426x240: the stack reached it); the rest wait their turn
		int[] placing = dev.agentcraft.client.building.BuildPlacement.hudRect();
		if (placing != null && !col.up() && placing[0] < col.x() + W && col.x() < placing[0] + placing[2]) {
			limit = Math.min(limit, placing[1] - 4);
		}
		// need-you toasts first (newest first), then the rest; what does not fit waits and counts on the "+N more" line
		List<Toast> order = new ArrayList<>();
		List<ToastStack.Item> items = new ArrayList<>();
		for (int i : ToastStack.order(needs(ACTIVE))) {
			Toast t = ACTIVE.get(i);
			order.add(t);
			items.add(new ToastStack.Item(t.need(), height(font, t)));
		}
		ToastStack.Layout l = ToastStack.layout(items, y, limit, 4, ToastStack.MAX_SHOWN, MORE_H);
		if (col.up() && !l.shown().isEmpty()) {
			// above the overlay: the stack hugs its bottom edge (the layout ran top-down, shift it)
			int last = l.shown().size() - 1;
			int end = l.moreY() >= 0 ? l.moreY() + MORE_H : l.ys().get(last) + items.get(l.shown().get(last)).height();
			int dy = Math.max(0, limit - end);
			List<Integer> ys = new ArrayList<>();
			for (int v : l.ys()) {
				ys.add(v + dy);
			}
			l = new ToastStack.Layout(l.shown(), List.copyOf(ys), l.hidden(), l.hiddenNeed(), l.moreY() >= 0 ? l.moreY() + dy : -1);
		}
		lastLayout = l;
		lastX = col.x();
		lastOrder = order;
		for (int k = 0; k < l.shown().size(); k++) {
			Toast t = order.get(l.shown().get(k));
			if (t.start < 0) {
				t.start = now; // its time and its slide start now that it has room
			}
			draw(g, font, t, now, col.x(), l.ys().get(k));
		}
		if (l.moreY() >= 0) {
			drawMore(g, font, l, col.x(), l.moreY());
		}
	}

	/** "+2 more · 1 needs you": a small paper tab under the stack, aligned with the toasts' outer edge. */
	private static void drawMore(GuiGraphicsExtractor g, Font font, ToastStack.Layout l, int colX, int y) {
		String text = ToastStack.moreText(l.hidden(), l.hiddenNeed());
		int w = Math.min(W, font.width(text) + 12);
		int x = colX + W / 2 < g.guiWidth() / 2 ? colX : colX + W - w;
		Panels.sprite(g, Kit.PANEL_PAPER, x, y, w, MORE_H, 0xFFFFFFFF);
		g.text(font, TextUtil.ellipsize(font, text, w - 12), x + 6, y + 3, l.hiddenNeed() > 0 ? UiStyle.CLAY_DARK : UiBits.muted(), false);
	}

	/** The toast's wrapped body lines (at most two) for the text column. */
	private static List<FormattedCharSequence> lines(Font font, Toast t) {
		Kit.Padding p = Kit.padding("panel_paper");
		int textW = W - (p.left() + 26) - p.right();
		List<FormattedCharSequence> lines = TextUtil.wrap(font, UiBits.oneLine(t.body), textW);
		if (lines.size() > 2) {
			String second = TextUtil.wrapPlain(font, UiBits.oneLine(t.body), textW).get(1);
			lines = List.of(lines.get(0), net.minecraft.network.chat.Component.literal(TextUtil.ellipsize(font, second + " …", textW))
				.getVisualOrderText());
		}
		return lines;
	}

	private static int height(Font font, Toast t) {
		Kit.Padding p = Kit.padding("panel_paper");
		boolean hint = t.hintKey != null;
		return Math.max(p.top() + 20 + p.bottom() - 2, p.top() + 10 + lines(font, t).size() * 10 + (hint ? 12 : 0) + p.bottom() - 2);
	}

	/** QA: the stack as last drawn ({@code dev.hud.state toasts}). */
	public static com.google.gson.JsonObject json() {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		com.google.gson.JsonArray shownArr = new com.google.gson.JsonArray();
		ToastStack.Layout l = lastLayout;
		for (int k = 0; k < l.shown().size() && l.shown().get(k) < lastOrder.size(); k++) {
			Toast t = lastOrder.get(l.shown().get(k));
			com.google.gson.JsonObject j = new com.google.gson.JsonObject();
			j.addProperty("level", t.n.level().name().toLowerCase(Locale.ROOT));
			j.addProperty("title", t.title);
			j.addProperty("y", l.ys().get(k));
			shownArr.add(j);
		}
		o.add("shown", shownArr);
		o.addProperty("hidden", l.hidden());
		o.addProperty("hiddenNeed", l.hiddenNeed());
		o.addProperty("moreLine", l.moreY() >= 0 ? ToastStack.moreText(l.hidden(), l.hiddenNeed()) : null);
		o.addProperty("moreY", l.moreY());
		o.addProperty("queued", ACTIVE.size());
		o.addProperty("x", lastX);
		o.addProperty("w", W);
		return o;
	}

	/** Draws the toast at {@code y} (its place in the stack, {@link ToastStack#layout}). */
	private static void draw(GuiGraphicsExtractor g, Font font, Toast t, long now, int colX, int y) {
		Kit.Padding p = Kit.padding("panel_paper");
		boolean need = t.need();
		int textX = p.left() + 26;
		int textW = W - textX - p.right();
		List<FormattedCharSequence> lines = lines(font, t);
		boolean hint = t.hintKey != null;
		int h = height(font, t);
		long age = now - t.start;
		float slide = Math.min(1f, age / (float) SLIDE_MS);
		slide = 1f - (1f - slide) * (1f - slide);
		long left = t.life - age;
		float fade = left < FADE_MS ? Math.max(0f, left / (float) FADE_MS) : 1f;
		// slides in from the nearer edge
		boolean fromLeft = colX + W / 2 < g.guiWidth() / 2;
		int off = (int) ((1f - slide) * (W + 10));
		int x = fromLeft ? colX - off : colX + off;
		int a = (int) (255 * fade);
		if (a < 8) {
			return;
		}
		int tint = (a << 24) | 0xFFFFFF;
		Panels.sprite(g, Kit.PANEL_PAPER, x, y, W, h, tint);
		if (need) {
			g.fill(x + 3, y + 4, x + 5, y + h - 6, UiStyle.withAlpha(UiStyle.CLAY, a));
		}
		int px = x + p.left();
		int py = y + p.top() - 1;
		if (t.agentId != null && AgentBits.hasPortrait(t.agentId)) {
			AgentBits.framedPortrait(g, t.agentId, px, py, 1);
		} else {
			String icon = t.body.startsWith("Merged") ? "merge" : t.body.startsWith("Goal") ? "decision" : need ? "decision" : "message";
			Panels.sprite(g, Kit.icon(icon), px + 4, py + 4, 12, 12, tint);
		}
		int tx = x + textX;
		int nameColor = t.agentId != null ? AgentBits.nameOnLight(t.agentId) : UiBits.ink();
		String title = TextUtil.ellipsize(font, t.title, textW);
		if (t.agentId != null && title.startsWith(AgentBits.agentName(t.agentId))) {
			String nm = AgentBits.agentName(t.agentId);
			g.text(font, nm, tx, py + 1, UiStyle.withAlpha(nameColor, a), false);
			g.text(font, title.substring(nm.length()), tx + font.width(nm), py + 1, UiStyle.withAlpha(need ? UiStyle.CLAY_DARK : UiBits.muted(), a), false);
		} else {
			g.text(font, title, tx, py + 1, UiStyle.withAlpha(need ? UiStyle.CLAY_DARK : t.n.level() == NotifyLevel.WARN ? UiBits.errorText() : UiBits.ink(),
				a), false);
		}
		int ly = py + 12;
		for (FormattedCharSequence line : lines) {
			g.text(font, line, tx, ly, UiStyle.withAlpha(UiBits.ink(), a), false);
			ly += 10;
		}
		if (hint && a > 200) {
			int hw = UiBits.hintsWidth(font, t.hintKey, t.hintVerb);
			UiBits.hints(g, font, x + W - p.right() - hw, ly, false, t.hintKey, t.hintVerb);
		}
	}
}
