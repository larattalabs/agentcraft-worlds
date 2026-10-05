package dev.agentcraft.client.hud;

import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hud.AlertLine;
import dev.agentcraft.hud.HudRules;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Panel overlay style (the wave 2 HUD, now placed by {@link HudOverlay} instead of the top centre): the goal with
 * its status dot and percentage, a progress bar and the task counts by column; under it the decisions badge ("2 waiting
 * · J", pulsing clay) while decisions are open, the alert line (docs/WAVE2.md W5: "2 decisions · 1 blocked · 3 replies ·
 * usage paused until 14:20 [H]") while anything needs the player, and the peek. With no goal yet a small hint says how
 * to give one. Dimmed while the Foreman link is down (last known state). Several open goals: the most urgent pinned,
 * else 8 s turns, "+N more" ({@link HudRules#pickGoal}). The blocks stack in a column aligned to the overlay's side.
 * Drawn in overlay px from the origin. Also keeps the QA fields {@code dev.hud.state} has always reported.
 */
public final class GoalBar {
	private static final int MAX_W = 300;
	private static final long DONE_FADE_MS = 60_000;
	private static final int GAP = 2;

	/** QA: the goal shown last frame and the cycle (dev.hud.state goalBar). */
	public static volatile HudRules.@org.jspecify.annotations.Nullable Pick lastPick;
	/** QA: the alert line drawn last frame: level (full/short/dots, null = not drawn), box (GUI px) and widths. */
	public static volatile @org.jspecify.annotations.Nullable String alertLevel;
	public static volatile int[] alertBox = new int[4];
	public static volatile int alertNeeded;
	public static volatile int alertAvailable;
	public static volatile boolean alertOverflow;

	/** Bottom edge of what the overlay drew last frame (GUI px; 0 = nothing). */
	public static int bottom = 0;
	/** Right edge of what the overlay drew last frame (0 = nothing). */
	public static int right = 0;
	/** QA: something drawn last frame intersected the connection pill. */
	public static boolean pillClash;
	/** Drawing the settings preview: leave the QA fields alone. */
	static boolean preview;

	private GoalBar() {
	}

	private enum Kind {
		GOAL, NO_GOAL, BADGE, ALERTS, PEEK
	}

	private record Block(Kind kind, int w, int h) {
	}

	/** The panel's blocks for {@code maxW} overlay px (the alert line's level is picked to fit). */
	private static List<Block> blocks(HudModel m, Font font, int maxW) {
		Kit.Padding p = Kit.padding("tooltip");
		List<Block> out = new ArrayList<>();
		int line = p.top() + 10 + p.bottom() + 1;
		if (m.goal != null) {
			out.add(new Block(Kind.GOAL, Math.min(MAX_W, maxW), p.top() + 9 + 4 + 6 + 4 + 9 + p.bottom()));
		} else if (!m.stale) {
			String key = Keys.console == null ? "Backtick" : Keys.label(Keys.console);
			int w = p.left() + 11 + font.width("No goal yet · press") + 4 + UiBits.keycapWidth(font, key) + 4 + font.width("to give the team one") + p.right()
				+ 2;
			out.add(new Block(Kind.NO_GOAL, Math.min(w, maxW), line));
		}
		if (m.waiting > 0) {
			String key = Keys.decisions == null ? "J" : Keys.label(Keys.decisions);
			out.add(new Block(Kind.BADGE, Math.min(maxW, p.left() + 13 + font.width(m.waiting + " waiting · press") + 4 + UiBits.keycapWidth(font, key)
				+ p.right() + 1), line));
		}
		if (m.alert.visible()) {
			out.add(new Block(Kind.ALERTS, Math.min(maxW, alertWidth(m, font, maxW)), line));
		}
		if (m.peek != null) {
			out.add(new Block(Kind.PEEK, Math.min(maxW, p.left() + 12 + font.width(m.peek.text()) + p.right() + 1), line));
		}
		return out;
	}

	/** Overlay px: width and height of the panel. */
	static int[] measure(HudModel m, Font font, int maxW) {
		int w = 0;
		int h = 0;
		for (Block b : blocks(m, font, maxW)) {
			w = Math.max(w, b.w());
			h += (h > 0 ? GAP : 0) + b.h();
		}
		return new int[] {w, h};
	}

	/**
	 * Draws the panel at the origin, {@code w} wide; blocks narrower than that align right when {@code alignRight}.
	 * {@code gx, gy, k}: where the origin is in GUI px and the scale (QA boxes).
	 */
	static void draw(GuiGraphicsExtractor g, Font font, HudModel m, int w, boolean alignRight, int gx, int gy, float k) {
		int alpha = m.stale ? 200 : 255;
		if (m.goal != null && m.goal.status() == GoalStatus.DONE && System.currentTimeMillis() - m.goal.updatedAt() > DONE_FADE_MS) {
			alpha = Math.min(alpha, 190);
		}
		int y = 0;
		for (Block b : blocks(m, font, w)) {
			int x = alignRight ? w - b.w() : 0;
			switch (b.kind()) {
				case GOAL -> drawGoal(g, font, m, x, y, b.w(), b.h(), alpha);
				case NO_GOAL -> drawNoGoal(g, font, x, y, b.w(), b.h());
				case BADGE -> drawBadge(g, font, m, x, y, b.w(), b.h(), alpha);
				case ALERTS -> {
					drawAlerts(g, font, m, x, y, b.w(), b.h(), alpha);
					if (preview) {
						break;
					}
					alertBox = new int[] {gx + (int) Math.floor(x * k), gy + (int) Math.floor(y * k), dev.agentcraft.hud.HudLayout.scaled(b.w(), k),
						dev.agentcraft.hud.HudLayout.scaled(b.h(), k)};
				}
				case PEEK -> drawPeek(g, font, m, x, y, b.w(), b.h(), alpha);
			}
			y += b.h() + GAP;
		}
	}

	/** While planning: the lead is planning, unless it is waiting on your answer (a question before the plan). */
	static String planningLine(ForemanState s) {
		for (var a : s.agents().values()) {
			if (a.role() == dev.agentcraft.client.foreman.Protocol.AgentRole.LEAD) {
				return a.state() == dev.agentcraft.client.foreman.Protocol.AgentState.WAITING_USER
					? a.name() + " needs your answer before planning"
					: a.name() + " is planning the tasks…";
			}
		}
		return "Marlow is planning the tasks…";
	}

	private static void drawGoal(GuiGraphicsExtractor g, Font font, HudModel m, int x, int y, int w, int h, int alpha) {
		HudModel.GoalView goal = m.goal;
		boolean stale = m.stale;
		Kit.Padding p = Kit.padding("tooltip");
		int tint = (alpha << 24) | 0xFFFFFF;
		Panels.sprite(g, Kit.TOOLTIP, x, y, w, h);
		int ix = x + p.left() + 1;
		int iw = w - p.left() - p.right() - 2;
		int ty = y + p.top();

		String family;
		String fill;
		String prefix = "";
		switch (goal.status()) {
			case PLANNING -> {
				family = "thinking";
				fill = "brass";
				prefix = "Planning · ";
			}
			case DONE -> {
				family = "done";
				fill = "sage";
				prefix = "Done " + UiBits.CHECK + "  ";
			}
			case FAILED -> {
				family = "error";
				fill = "red";
				prefix = "Failed · ";
			}
			case CANCELLED -> {
				family = "idle";
				fill = "brass";
				prefix = "Cancelled · ";
			}
			default -> {
				family = "working";
				fill = "teal";
			}
		}
		if (stale) {
			// last known state: no live status colour (the pill says it is reconnecting)
			family = "idle";
		}
		Panels.sprite(g, Kit.dot(family, false), ix, ty + 1, 7, 7, tint);
		String pctS = goal.pct() + "%";
		int pctW = font.width(pctS);
		int more = m.more;
		// several open goals: "+2 more" before the percentage (the bar takes turns, or pins the urgent one)
		String moreS = more > 0 ? "+" + more + " more" : "";
		int moreW = more > 0 ? font.width(moreS) + 6 : 0;
		if (more > 0 && iw - 11 - pctW - 8 - moreW < 60) {
			moreS = "+" + more;
			moreW = font.width(moreS) + 6;
		}
		String text = prefix + goal.text();
		int textColor = stale ? UiBits.activityOnInk() : UiBits.cream();
		g.text(font, TextUtil.ellipsize(font, text, iw - 11 - pctW - 8 - moreW), ix + 11, ty, UiStyle.withAlpha(textColor, alpha), false);
		if (more > 0) {
			g.text(font, moreS, ix + iw - pctW - 6 - font.width(moreS), ty, UiStyle.withAlpha(UiBits.activityOnInk(), alpha), false);
		}
		g.text(font, pctS, ix + iw - pctW, ty, UiStyle.withAlpha(stale ? UiBits.activityOnInk() : UiStyle.BRASS, alpha), false);

		int by = ty + 9 + 4;
		Panels.sprite(g, Kit.PROGRESS_TRACK, ix, by, iw, 6, tint);
		int fw = (int) Math.round(Math.max(0, Math.min(1, goal.progress())) * iw);
		if (fw >= 4) {
			Panels.sprite(g, Kit.progressFill(fill), ix, by, fw, 6, tint);
		}

		// task counts by column, each with its status dot
		int cy = by + 6 + 4;
		String[] labels = {"doing", "review", "todo", "blocked", "done"};
		String[] fams = {"working", "thinking", "idle", "error", "done"};
		int cx = ix;
		int act = UiStyle.withAlpha(UiBits.activityOnInk(), alpha);
		int[] counts = m.counts;
		if (m.totalTasks == 0) {
			g.text(font, goal.status() == GoalStatus.PLANNING ? m.planningLine : "no tasks yet", cx, cy, act, false);
		} else {
			// done count on the right ("2/9 done"), the open columns on the left with labels when they fit
			String done = counts[4] + "/" + m.totalTasks + " done";
			int doneW = font.width(done);
			Panels.sprite(g, Kit.dot("done", false), ix + iw - doneW - 9, cy + 1, 7, 7, tint);
			g.text(font, done, ix + iw - doneW, cy, act, false);
			int room = iw - doneW - 9 - 10;
			boolean withLabels = countsWidth(font, counts, labels, true) <= room;
			int open = counts[0] + counts[1] + counts[2] + counts[3];
			if (open == 0) {
				String all = goal.status() == GoalStatus.DONE ? "finished " + UiBits.ago(goal.updatedAt()) : "nothing open right now";
				g.text(font, TextUtil.ellipsize(font, all, room), cx, cy, act, false);
			}
			for (int i = 0; i < 4; i++) {
				if (counts[i] == 0) {
					// empty columns say nothing ("0 doing" next to "7/7 done" reads like a problem)
					continue;
				}
				String c = counts[i] + (withLabels ? " " + labels[i] : "");
				if (cx + 9 + font.width(c) > ix + room) {
					break;
				}
				Panels.sprite(g, Kit.dot(fams[i], false), cx, cy + 1, 7, 7, tint);
				g.text(font, c, cx + 9, cy, act, false);
				cx += 9 + font.width(c) + 8;
			}
		}
	}

	private static int countsWidth(Font font, int[] counts, String[] labels, boolean withLabels) {
		int w = 0;
		for (int i = 0; i < 4; i++) {
			if (counts[i] == 0) {
				continue;
			}
			w += 9 + font.width(counts[i] + (withLabels ? " " + labels[i] : "")) + 8;
		}
		return Math.max(0, w - 8);
	}

	private static void drawNoGoal(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h) {
		String key = Keys.console == null ? "Backtick" : Keys.label(Keys.console);
		String a = "No goal yet · press";
		String b = "to give the team one";
		Kit.Padding p = Kit.padding("tooltip");
		int kw = UiBits.keycapWidth(font, key);
		Panels.sprite(g, Kit.TOOLTIP, x, y, w, h, 0xE6FFFFFF);
		int cx = x + p.left() + 1;
		int ty = y + p.top() + 1;
		Panels.dot(g, "idle", cx, ty, false);
		cx += 11;
		g.text(font, a, cx, ty, UiBits.activityOnInk(), false);
		cx += font.width(a) + 4;
		UiBits.keycap(g, font, key, cx, ty - 2);
		cx += kw + 4;
		g.text(font, b, cx, ty, UiBits.activityOnInk(), false);
	}

	private static void drawBadge(GuiGraphicsExtractor g, Font font, HudModel m, int x, int y, int w, int h, int alpha) {
		String key = Keys.decisions == null ? "J" : Keys.label(Keys.decisions);
		String text = m.waiting + " waiting · press";
		Kit.Padding p = Kit.padding("tooltip");
		Panels.sprite(g, Kit.TOOLTIP, x, y, w, h);
		int cx = x + p.left() + 2;
		int ty = y + p.top() + 1;
		if (m.stale) {
			// can't be answered until the Foreman is back: no pulse, no clay
			Panels.sprite(g, Kit.dot("idle", false), cx, ty, 7, 7);
		} else {
			UiBits.pulsingDot(g, "waiting", cx, ty);
		}
		cx += 11;
		g.text(font, text, cx, ty, UiStyle.withAlpha(m.stale ? UiBits.activityOnInk() : UiStyle.CLAY, alpha), false);
		cx += font.width(text) + 4;
		UiBits.keycap(g, font, key, cx, ty - 2);
	}

	private static void drawPeek(GuiGraphicsExtractor g, Font font, HudModel m, int x, int y, int w, int h, int alpha) {
		Kit.Padding p = Kit.padding("tooltip");
		Panels.sprite(g, Kit.TOOLTIP, x, y, w, h);
		int cx = x + p.left() + 2;
		int ty = y + p.top() + 1;
		Panels.sprite(g, Kit.dot(HudPeeks.family(m.peek.kind()), false), cx, ty, 7, 7, (alpha << 24) | 0xFFFFFF);
		g.text(font, TextUtil.ellipsize(font, m.peek.text(), w - p.left() - p.right() - 13), cx + 10, ty, UiStyle.withAlpha(UiBits.cream(), alpha), false);
	}

	private static int alertWidth(HudModel m, Font font, int maxW) {
		return alertFit(m, font, maxW).w;
	}

	private record AlertFit(AlertLine.Level level, boolean withVerb, boolean overflow, int rowW, int w, int needed, int available) {
	}

	/** Widest of full / short / dots that fits {@code maxW}; even the dots too wide: cut at the edge (overflow). */
	private static AlertFit alertFit(HudModel m, Font font, int maxW) {
		long now = System.currentTimeMillis();
		ZoneId zone = ZoneId.systemDefault();
		AlertLine line = m.alert;
		List<AlertLine.Part> parts = line.parts(zone, now);
		String key = Keys.hub == null ? "H" : Keys.label(Keys.hub);
		Kit.Padding p = Kit.padding("tooltip");
		int dotW = 10;
		int sepW = font.width(AlertLine.SEP);
		int kw = UiBits.keycapWidth(font, key);
		int frame = p.left() + p.right() + 3;
		int avail = maxW - frame;
		int fullTail = 6 + kw + 3 + font.width("open");
		int tail = 6 + kw;
		int needed = AlertLine.rowWidth(parts, AlertLine.Level.FULL, font::width, dotW, sepW) + fullTail + frame;
		AlertLine.Level level = line.fit(font::width, avail, dotW, sepW, fullTail, zone, now);
		boolean withVerb = level == AlertLine.Level.FULL;
		if (level == null) {
			level = line.fit(font::width, avail, dotW, sepW, tail, zone, now);
		}
		boolean overflow = level == null;
		if (overflow) {
			level = AlertLine.Level.DOTS;
		}
		int sep = level == AlertLine.Level.DOTS ? font.width("  ") : sepW;
		int rowW = AlertLine.rowWidth(parts, level, font::width, dotW, sep) + (withVerb ? fullTail : tail);
		int w = Math.min(rowW + frame, maxW);
		return new AlertFit(level, withVerb, overflow || rowW + frame > w, rowW, w, needed, avail + frame);
	}

	/**
	 * The alert line (W5): each non-zero part with its status dot, separated by " · ", then the hub key's keycap. Widest
	 * of full / short / dots that fits; even the dots too wide (a tiny window): the row is cut at the edge and
	 * {@link #alertOverflow} says so.
	 */
	private static void drawAlerts(GuiGraphicsExtractor g, Font font, HudModel m, int x, int y, int w, int h, int alpha) {
		long now = System.currentTimeMillis();
		ZoneId zone = ZoneId.systemDefault();
		AlertFit fit = alertFit(m, font, w);
		AlertLine.Level level = fit.level();
		boolean stale = m.stale;
		List<AlertLine.Part> parts = m.alert.parts(zone, now);
		String key = Keys.hub == null ? "H" : Keys.label(Keys.hub);
		Kit.Padding p = Kit.padding("tooltip");
		int dotW = 10;
		int kw = UiBits.keycapWidth(font, key);
		int sep = level == AlertLine.Level.DOTS ? font.width("  ") : font.width(AlertLine.SEP);
		if (!preview) {
			alertNeeded = fit.needed();
			alertAvailable = fit.available();
			alertLevel = level.name().toLowerCase(java.util.Locale.ROOT);
			alertOverflow = fit.overflow();
		}
		int tint = (alpha << 24) | 0xFFFFFF;
		Panels.sprite(g, Kit.TOOLTIP, x, y, w, h);
		int cx = x + p.left() + 2;
		int ty = y + p.top() + 1;
		int limit = x + w - p.right();
		int textColor = UiStyle.withAlpha(stale ? UiBits.activityOnInk() : UiBits.cream(), alpha);
		int sepColor = UiStyle.withAlpha(UiBits.activityOnInk(), alpha);
		for (int i = 0; i < parts.size(); i++) {
			AlertLine.Part part = parts.get(i);
			if (i > 0) {
				if (level != AlertLine.Level.DOTS) {
					g.text(font, AlertLine.SEP, cx, ty, sepColor, false);
				}
				cx += sep;
			}
			String t = part.at(level);
			if (cx + dotW + font.width(t) > limit) {
				break;
			}
			Panels.sprite(g, Kit.dot(stale ? "idle" : part.family(), false), cx, ty, 7, 7, tint);
			cx += dotW;
			g.text(font, t, cx, ty, part.kind().equals("decisions") && !stale ? UiStyle.withAlpha(UiStyle.CLAY, alpha) : textColor, false);
			cx += font.width(t);
		}
		cx += 6;
		if (cx + kw <= limit + 1) {
			UiBits.keycap(g, font, key, cx, ty - 2);
			if (fit.withVerb()) {
				g.text(font, "open", cx + kw + 3, ty, sepColor, false);
			}
		}
	}

	/** Resets the per-frame QA fields (the overlay calls it before drawing). */
	static void beginFrame() {
		bottom = 0;
		right = 0;
		pillClash = false;
		alertLevel = null;
		alertOverflow = false;
	}
}
