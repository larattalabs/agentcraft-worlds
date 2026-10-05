package dev.agentcraft.client.hud;

import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hud.AlertLine;
import dev.agentcraft.hud.HudPeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Pill and Pill+ overlay styles, drawn in overlay px at the origin (the overlay scales and places them).
 * <ul>
 *   <li><b>Pill</b>: one ink line: a mini progress bar and "40%", then the counts that are not zero ("1 blocked",
 *   "3 replies", "paused → 14:20"); when a decision waits, a clay stripe, the pulsing clay dot and "2 decisions" with the
 *   decisions keycap. A peek widens it with what changed for a few seconds. Counts fall back to their short and dot
 *   forms ({@link AlertLine.Level}) when the line would be too wide.</li>
 *   <li><b>Pill+</b>: the same ink, a bit taller: one dot per open goal (the shown one bright), the goal title and %, a
 *   thin progress bar, who is working ("Kit · Juniper …") with the shortest plan usage window or the hold ("paused
 *   until 14:20"), the next decision's first words with the keycap, and the peek.</li>
 * </ul>
 */
final class PillStyle {
	static final int PILL_H = 17;
	/** Pill+ width (overlay px). */
	static final int PLUS_W = 188;
	private static final int TEXT_Y = 5;
	private static final int BAR_W = 26;
	private static final String SEP = " · ";

	private PillStyle() {
	}

	/** One piece of the pill line. */
	private interface Seg {
		int w();

		void draw(GuiGraphicsExtractor g, int x, int alpha);
	}

	private record Bar(double progress, String fill) implements Seg {
		@Override
		public int w() {
			return BAR_W;
		}

		@Override
		public void draw(GuiGraphicsExtractor g, int x, int alpha) {
			int tint = (alpha << 24) | 0xFFFFFF;
			Panels.sprite(g, Kit.PROGRESS_TRACK, x, 7, BAR_W, 4, tint);
			int fw = (int) Math.round(Math.max(0, Math.min(1, progress)) * BAR_W);
			if (fw >= 4) {
				Panels.sprite(g, Kit.progressFill(fill), x, 7, fw, 4, tint);
			}
		}
	}

	private record Txt(Font font, String text, int color, String dot, boolean pulse) implements Seg {
		@Override
		public int w() {
			return (dot == null ? 0 : 10) + font.width(text);
		}

		@Override
		public void draw(GuiGraphicsExtractor g, int x, int alpha) {
			int cx = x;
			if (dot != null) {
				if (pulse) {
					UiBits.pulsingDot(g, dot, cx, TEXT_Y);
				} else {
					Panels.sprite(g, Kit.dot(dot, false), cx, TEXT_Y, 7, 7, (alpha << 24) | 0xFFFFFF);
				}
				cx += 10;
			}
			g.text(font, text, cx, TEXT_Y, UiStyle.withAlpha(color, alpha), false);
		}
	}

	private record Gap(int w) implements Seg {
		@Override
		public void draw(GuiGraphicsExtractor g, int x, int alpha) {
		}
	}

	private record Cap(Font font, String key) implements Seg {
		@Override
		public int w() {
			return UiBits.keycapWidth(font, key);
		}

		@Override
		public void draw(GuiGraphicsExtractor g, int x, int alpha) {
			UiBits.keycap(g, font, key, x, 3);
		}
	}

	/** Clay stripe and pulse: something needs the player. */
	static boolean accent(HudModel m) {
		return m.waiting > 0 && !m.stale;
	}

	private static String fill(HudModel m) {
		if (m.stale || m.goal == null) {
			return "brass";
		}
		return switch (m.goal.status()) {
			case PLANNING -> "brass";
			case DONE -> "sage";
			case FAILED -> "red";
			default -> "teal";
		};
	}

	private static String decisionsKey() {
		return Keys.decisions == null ? "J" : Keys.label(Keys.decisions);
	}

	private static String hubKey() {
		return Keys.hub == null ? "H" : Keys.label(Keys.hub);
	}

	// ------------------------------------------------------------------ Pill

	/** The pill's widest line without a peek (overlay px); with a peek it may grow by {@link #PEEK_W}. */
	static final int PILL_MAX = 190;
	/** The peek's text at most (overlay px). */
	static final int PEEK_W = 130;
	/** Decisions level, other counts level: tried in order until the line fits. */
	private static final AlertLine.Level[][] LEVELS = {{AlertLine.Level.FULL, AlertLine.Level.FULL}, {AlertLine.Level.FULL, AlertLine.Level.SHORT},
		{AlertLine.Level.FULL, AlertLine.Level.DOTS}, {AlertLine.Level.SHORT, AlertLine.Level.DOTS}, {AlertLine.Level.DOTS, AlertLine.Level.DOTS}};

	private static List<Seg> pillSegs(HudModel m, Font font, AlertLine.Level dec, AlertLine.Level other, boolean peek) {
		List<Seg> out = new ArrayList<>();
		int cream = m.stale ? UiBits.activityOnInk() : UiBits.cream();
		int muted = UiBits.activityOnInk();
		if (m.activeGoal()) {
			out.add(new Bar(m.goal.progress(), fill(m)));
			out.add(new Gap(4));
			out.add(new Txt(font, m.goal.pct() + "%", m.stale ? muted : UiStyle.BRASS, null, false));
		} else if (!m.needsPlayer() && m.peek == null) {
			out.add(new Txt(font, m.goal != null && m.goal.status() == GoalStatus.DONE ? "Goal done" : "No active goal", muted,
				m.goal != null && m.goal.status() == GoalStatus.DONE ? "done" : "idle", false));
		}
		if (m.waiting > 0) {
			sep(out, font, false);
			String t = dec == AlertLine.Level.FULL ? UiBits.plural(m.waiting, "decision", "decisions") : m.waiting + (dec == AlertLine.Level.SHORT ? " dec"
				: "");
			out.add(new Txt(font, t, m.stale ? muted : UiStyle.CLAY, m.stale ? "idle" : "waiting", !m.stale));
			out.add(new Gap(4));
			out.add(new Cap(font, decisionsKey()));
		}
		long now = System.currentTimeMillis();
		boolean dots = other == AlertLine.Level.DOTS;
		for (AlertLine.Part p : m.alert.parts(ZoneId.systemDefault(), now)) {
			if (p.kind().equals("decisions")) {
				continue; // the clay part above (the waiting count, which leaves out what is being answered)
			}
			sep(out, font, dots && out.size() > 0 && out.get(out.size() - 1) instanceof Txt last && last.dot() != null && !last.pulse());
			out.add(new Txt(font, p.at(other), cream, m.stale ? "idle" : p.family(), false));
		}
		if (m.peek != null && peek) {
			sep(out, font, false);
			out.add(new Txt(font, TextUtil.ellipsize(font, m.peek.text(), PEEK_W), cream, HudPeeks.family(m.peek.kind()), false));
		}
		return out;
	}

	/** " · " between parts; a small gap between two dot counts ("●1 ●3"). */
	private static void sep(List<Seg> out, Font font, boolean tight) {
		if (!out.isEmpty()) {
			out.add(tight ? new Gap(5) : new Txt(font, SEP, UiBits.activityOnInk(), null, false));
		}
	}

	private static int frame(HudModel m) {
		return 5 + 5 + (accent(m) ? 3 : 0);
	}

	private static int width(List<Seg> segs) {
		int w = 0;
		for (Seg s : segs) {
			w += s.w();
		}
		return w;
	}

	/**
	 * The pill's segments that fit (at most {@link #PILL_MAX}, plus the peek while one shows): decisions and the other
	 * counts step down from full to short to dots; a peek that does not fit at any step is left out.
	 */
	private static List<Seg> fitPill(HudModel m, Font font, int maxW) {
		int cap = Math.min(maxW, PILL_MAX + (m.peek != null ? PEEK_W + 20 : 0));
		if (m.peek != null) {
			for (AlertLine.Level[] l : LEVELS) {
				List<Seg> segs = pillSegs(m, font, l[0], l[1], true);
				if (width(segs) + frame(m) <= cap) {
					return segs;
				}
			}
		}
		List<Seg> segs = null;
		for (AlertLine.Level[] l : LEVELS) {
			segs = pillSegs(m, font, l[0], l[1], false);
			if (width(segs) + frame(m) <= Math.min(cap, PILL_MAX)) {
				return segs;
			}
		}
		return segs;
	}

	static int[] measurePill(HudModel m, Font font, int maxW) {
		List<Seg> segs = fitPill(m, font, maxW);
		return new int[] {Math.min(maxW, width(segs) + frame(m)), PILL_H};
	}

	static void drawPill(GuiGraphicsExtractor g, Font font, HudModel m, int w, int alpha) {
		List<Seg> segs = fitPill(m, font, w);
		Panels.sprite(g, Kit.TOOLTIP, 0, 0, w, PILL_H);
		int x = 5;
		if (accent(m)) {
			g.fill(2, 3, 4, PILL_H - 3, UiStyle.withAlpha(UiStyle.CLAY, alpha));
			x += 3;
		}
		g.enableScissor(0, 0, w - 4, PILL_H);
		for (Seg s : segs) {
			s.draw(g, x, alpha);
			x += s.w();
		}
		g.disableScissor();
	}

	// ------------------------------------------------------------------ Pill+

	private static final int ROW = 11;

	/** The rows Pill+ draws (title and bar always). */
	private record PlusRows(boolean agents, boolean ask, boolean alerts, boolean peek) {
		int height() {
			int h = TEXT_Y + 9 + 3 + 4 + 3;
			if (agents) {
				h += ROW;
			}
			if (ask || alerts) {
				h += ROW;
			}
			if (peek) {
				h += ROW;
			}
			return h + 2;
		}
	}

	private static PlusRows plusRows(HudModel m) {
		boolean agents = !m.working.isEmpty() || m.usagePct >= 0 || m.alert.holdReason() != null;
		boolean ask = m.waiting > 0;
		boolean alerts = !ask && hasOtherAlerts(m);
		return new PlusRows(agents, ask, alerts, m.peek != null);
	}

	private static boolean hasOtherAlerts(HudModel m) {
		AlertLine a = m.alert;
		return a.blocked() > 0 || a.replies() > 0 || a.prs() > 0;
	}

	static int[] measurePlus(HudModel m, Font font, int maxW) {
		return new int[] {Math.min(maxW, PLUS_W), plusRows(m).height()};
	}

	static void drawPlus(GuiGraphicsExtractor g, Font font, HudModel m, int w, int h, int alpha) {
		PlusRows rows = plusRows(m);
		int tint = (alpha << 24) | 0xFFFFFF;
		int cream = UiStyle.withAlpha(m.stale ? UiBits.activityOnInk() : UiBits.cream(), alpha);
		int muted = UiStyle.withAlpha(UiBits.activityOnInk(), alpha);
		Panels.sprite(g, Kit.TOOLTIP, 0, 0, w, h);
		int ix = 5;
		if (accent(m)) {
			g.fill(2, 3, 4, h - 3, UiStyle.withAlpha(UiStyle.CLAY, alpha));
			ix += 3;
		}
		int right = w - 5;
		int y = TEXT_Y;
		// title row: goal dots, title, %
		int cx = ix;
		if (m.goalDots.size() > 1) {
			int n = Math.min(m.goalDots.size(), 5);
			for (int i = 0; i < n; i++) {
				boolean cur = i == m.goalIndex;
				Panels.sprite(g, Kit.dot(m.stale ? "idle" : m.goalDots.get(i), false), cx, y + 1, 7, 7, ((cur ? alpha : alpha * 2 / 5) << 24) | 0xFFFFFF);
				cx += 8;
			}
			if (m.goalDots.size() > n) {
				String more = "+" + (m.goalDots.size() - n);
				g.text(font, more, cx, y, muted, false);
				cx += font.width(more);
			}
			cx += 3;
		}
		if (m.goal != null && (m.activeGoal() || m.goal.status() == GoalStatus.DONE)) {
			String pct = m.goal.pct() + "%";
			int pw = font.width(pct);
			String prefix = switch (m.goal.status()) {
				case PLANNING -> "Planning · ";
				case DONE -> "Done " + UiBits.CHECK + " ";
				default -> "";
			};
			if (m.goalDots.size() <= 1) {
				Panels.sprite(g, Kit.dot(m.stale ? "idle" : m.goal.status() == GoalStatus.PLANNING ? "thinking" : m.goal.status() == GoalStatus.DONE ? "done"
					: "working", false), cx, y + 1, 7, 7, tint);
				cx += 10;
			}
			g.text(font, TextUtil.ellipsize(font, prefix + m.goal.text(), right - cx - pw - 6), cx, y, cream, false);
			g.text(font, pct, right - pw, y, m.stale ? muted : UiStyle.withAlpha(UiStyle.BRASS, alpha), false);
		} else {
			Panels.sprite(g, Kit.dot("idle", false), cx, y + 1, 7, 7, tint);
			g.text(font, "No active goal", cx + 10, y, muted, false);
		}
		y += 9 + 3;
		int bw = right - ix;
		Panels.sprite(g, Kit.PROGRESS_TRACK, ix, y, bw, 4, tint);
		double prog = m.goal == null ? 0 : m.goal.progress();
		int fw = (int) Math.round(Math.max(0, Math.min(1, prog)) * bw);
		if (fw >= 4) {
			Panels.sprite(g, Kit.progressFill(fill(m)), ix, y, fw, 4, tint);
		}
		y += 4 + 3;
		long now = System.currentTimeMillis();
		if (rows.agents()) {
			// right: the hold, else the shortest usage window
			String r = null;
			int rc = muted;
			AlertLine a = m.alert;
			if (a.holdReason() != null) {
				r = a.holdReason().equals("usage") && a.holdUntil() != null && a.holdUntil() > 0
					? "paused until " + AlertLine.clock(a.holdUntil(), ZoneId.systemDefault(), now)
					: AlertLine.holdText(a.holdReason(), a.holdUntil(), ZoneId.systemDefault(), now, true);
				rc = UiStyle.withAlpha(m.stale ? UiBits.activityOnInk() : UiStyle.CLAY, alpha);
			} else if (m.usagePct >= 0) {
				String label = m.usageLabel == null || font.width(m.usageLabel) > 40 ? "usage" : m.usageLabel;
				r = label + " " + Math.round(m.usagePct) + "%";
				rc = m.usagePct >= 90 ? UiStyle.withAlpha(UiBits.errorText(), alpha) : muted;
			}
			int rw = r == null ? 0 : font.width(r);
			if (r != null) {
				g.text(font, r, right - rw, y, rc, false);
			}
			drawAgents(g, font, m, ix, y, right - rw - (r == null ? 0 : 6) - ix, alpha, muted);
			y += ROW;
		}
		if (rows.ask()) {
			String key = decisionsKey();
			int kw = UiBits.keycapWidth(font, key);
			if (m.stale) {
				Panels.sprite(g, Kit.dot("idle", false), ix, y, 7, 7, tint);
			} else {
				UiBits.pulsingDot(g, "waiting", ix, y);
			}
			String lead = m.waiting > 1 ? m.waiting + " · " : "";
			String q = m.nextDecision == null || m.nextDecision.isBlank() ? UiBits.plural(m.waiting, "decision", "decisions") : m.nextDecision;
			int clay = UiStyle.withAlpha(m.stale ? UiBits.activityOnInk() : UiStyle.CLAY, alpha);
			g.text(font, TextUtil.ellipsize(font, lead + q, right - kw - 4 - ix - 10), ix + 10, y, clay, false);
			UiBits.keycap(g, font, key, right - kw, y - 2);
			y += ROW;
		} else if (rows.alerts()) {
			String key = hubKey();
			int kw = UiBits.keycapWidth(font, key);
			int ax = ix;
			List<AlertLine.Part> parts = m.alert.parts(ZoneId.systemDefault(), now);
			AlertLine.Level level = m.alert.fit(font::width, right - kw - 4 - ix, 10, font.width(SEP), 0, ZoneId.systemDefault(), now);
			if (level == null) {
				level = AlertLine.Level.DOTS;
			}
			for (AlertLine.Part p : parts) {
				if (p.kind().equals("hold") && rows.agents()) {
					continue; // already on the agents row
				}
				String t = p.at(level);
				if (ax != ix) {
					g.text(font, SEP, ax, y, muted, false);
					ax += font.width(SEP);
				}
				if (ax + 10 + font.width(t) > right - kw - 4) {
					break;
				}
				Panels.sprite(g, Kit.dot(m.stale ? "idle" : p.family(), false), ax, y, 7, 7, tint);
				g.text(font, t, ax + 10, y, cream, false);
				ax += 10 + font.width(t);
			}
			UiBits.keycap(g, font, key, right - kw, y - 2);
			y += ROW;
		}
		if (rows.peek() && m.peek != null) {
			Panels.sprite(g, Kit.dot(HudPeeks.family(m.peek.kind()), false), ix, y, 7, 7, tint);
			g.text(font, TextUtil.ellipsize(font, m.peek.text(), right - ix - 10), ix + 10, y, cream, false);
		}
	}

	/** "Kit · Juniper …" in their name colours (the dots cycle while they work); "+2" when not all fit. */
	private static void drawAgents(GuiGraphicsExtractor g, Font font, HudModel m, int x, int y, int maxW, int alpha, int muted) {
		if (m.working.isEmpty()) {
			g.text(font, TextUtil.ellipsize(font, "nobody at work", maxW), x, y, muted, false);
			return;
		}
		int sepW = font.width(SEP);
		int dotsW = font.width("…") + 2;
		int cx = x;
		for (int i = 0; i < m.working.size(); i++) {
			String id = m.working.get(i);
			String name = UiBits.agentName(id);
			int left = m.working.size() - i;
			String rest = "+" + left;
			int need = (i > 0 ? sepW : 0) + font.width(name) + dotsW;
			if (cx + need > x + maxW) {
				if (i > 0 && cx + font.width(" " + rest) <= x + maxW) {
					g.text(font, " " + rest, cx, y, muted, false);
				}
				return;
			}
			if (i > 0) {
				g.text(font, SEP, cx, y, muted, false);
				cx += sepW;
			}
			g.text(font, name, cx, y, UiStyle.withAlpha(UiBits.nameOnDark(id), alpha), false);
			cx += font.width(name);
		}
		// working: a soft cycling ellipsis
		long ph = (System.currentTimeMillis() / 400) % 3;
		String dots = ph == 0 ? "." : ph == 1 ? ".." : "…";
		g.text(font, " " + dots, cx, y, muted, false);
	}


	/** QA: the pill's text as drawn (segments joined), for {@code dev.hud.state overlay.text}. */
	static String pillText(HudModel m, Font font, int maxW) {
		StringBuilder b = new StringBuilder();
		for (Seg s : fitPill(m, font, maxW)) {
			switch (s) {
				case Txt t -> b.append(t.text());
				case Cap c -> b.append('[').append(c.key()).append(']');
				case Bar bar -> b.append("[bar]");
				default -> b.append(' ');
			}
		}
		return b.toString().replaceAll(" +", " ").strip();
	}

}
