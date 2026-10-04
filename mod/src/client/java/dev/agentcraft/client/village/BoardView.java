package dev.agentcraft.client.village;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.monitor.DisplayDraw;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.village.VillageBoard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One village board panel's prepared drawing (client thread): the layout for its size, the page on show and every
 * text, rectangle, card and portrait in face pixels, rebuilt only when the content, the page, the size or the density
 * changes ({@link #sync}); the renderer submits it as it is every frame.
 */
final class BoardView {
	static final float Z = DisplayDraw.Z_STEP;

	/** A text run (already ellipsized to its room). */
	record Text(String s, float x, float y, int color) {
	}

	/** A paper card (kit sprite) behind a building row. */
	record Card(float x, float y, float w, float h) {
	}

	/** A portrait (8x8) or a status dot. */
	record Pic(Identifier tex, float x, float y, float size, boolean dot) {
	}

	final BlockPos origin;
	long lastUsedNanos;
	int panelW;
	int panelH;
	int ppb;
	int pw;
	int ph;
	VillageBoard.@Nullable Layout layout;
	VillageBoard.Page page = new VillageBoard.Page(0, 1, 0, 0);
	VillageBoard.Content content = VillageBoard.Content.EMPTY;
	long rebuilds;
	/** Rects on the board plane: background, header rule, hold banner, progress bars (one node). */
	final DisplayDraw.Rects rects = new DisplayDraw.Rects();
	/** Rects drawn over the cards (progress bars). */
	final DisplayDraw.Rects overCards = new DisplayDraw.Rects();
	final List<Card> cards = new ArrayList<>();
	final List<Text> texts = new ArrayList<>();
	final List<Text> cardTexts = new ArrayList<>();
	final List<Pic> pics = new ArrayList<>();
	/** The rows and milestones on show (dev.board.state). */
	final List<String> shownRows = new ArrayList<>();
	int shownMilestones;
	/** Text glyph height in blocks at this density (8 px / ppb): the readability figure for dev.board.state. */
	float textBlocks;

	private @Nullable Object seenContent;
	private int seenPage = -1;
	private int seenPpb = -1;
	private int seenW = -1;
	private int seenH = -1;
	private boolean seenOffline;

	BoardView(BlockPos origin) {
		this.origin = origin.immutable();
	}

	/** Re-plans when anything it draws changed. Returns true when it rebuilt. */
	boolean sync(VillageBoard.Content c, int w, int h, int forcedPage, int densityOverride, boolean offline, long nowMs) {
		int density = densityOverride > 0 ? densityOverride : VillageBoard.density(w, h);
		VillageBoard.Layout l = VillageBoard.layout(w * density, h * density, density, c.hold() != null);
		VillageBoard.Page p = VillageBoard.page(c.rows().size(), l.perPage(), nowMs, forcedPage);
		if (c == seenContent && p.index() == seenPage && density == seenPpb && w == seenW && h == seenH && offline == seenOffline) {
			page = p;
			return false;
		}
		seenContent = c;
		seenPage = p.index();
		seenPpb = density;
		seenW = w;
		seenH = h;
		seenOffline = offline;
		content = c;
		layout = l;
		page = p;
		panelW = w;
		panelH = h;
		ppb = density;
		pw = l.pw();
		ph = l.ph();
		textBlocks = 8f / density;
		build(c, l, p, offline, nowMs);
		rebuilds++;
		return true;
	}

	private void build(VillageBoard.Content c, VillageBoard.Layout l, VillageBoard.Page p, boolean offline, long now) {
		Font font = Minecraft.getInstance().font;
		rects.clear();
		overCards.clear();
		cards.clear();
		texts.clear();
		cardTexts.clear();
		pics.clear();
		shownRows.clear();
		shownMilestones = 0;
		int cream = UiStyle.color("palette.colors.cream", UiStyle.CREAM);
		int brass = UiStyle.color("palette.colors.brass", UiStyle.BRASS);
		int slate = 0xFF2A211B;
		int mutedOnDark = 0xFFB9AD99;
		int ink = UiStyle.color("paper.text", UiStyle.INK);
		int muted = UiStyle.color("paper.muted", 0xFF655E55);
		int error = UiStyle.color("paper.del_fg", 0xFF873C2A);
		// the board itself: a dark walnut slate inside the frame trim, a brass rule under the header
		rects.add(l.trim(), l.trim(), l.pw() - l.trim(), l.ph() - l.trim(), Z * 0.5f, slate, 0);
		rects.add(l.ix0(), l.iy0() + VillageBoard.HEADER_H - 2, l.ix1(), l.iy0() + VillageBoard.HEADER_H - 1, Z, brass, 0);
		// header: title left, counts and page right
		String right = c.rows().size() + (c.rows().size() == 1 ? " building" : " buildings") + (p.count() > 1 ? " · " + (p.index() + 1) + "/" + p.count() : "");
		if (offline) {
			right = "Foreman offline · " + right;
		}
		int rw = font.width(right);
		texts.add(new Text(right, l.ix1() - rw, l.iy0() + 2, offline ? 0xFFE0A589 : mutedOnDark));
		texts.add(new Text(TextUtil.ellipsize(font, "Village board", Math.max(0, l.ix1() - l.ix0() - rw - 6)), l.ix0(), l.iy0() + 2, cream));
		// building rows (paper cards)
		if (c.rows().isEmpty()) {
			float y = l.bodyY() + 4;
			for (String line : List.of("No buildings yet.", "Place one: hub (H) > Buildings > Place new…")) {
				texts.add(new Text(TextUtil.ellipsize(font, line, l.bx1() - l.bx0()), l.bx0(), y, line.startsWith("No") ? cream : mutedOnDark));
				y += 11;
			}
		}
		float y = l.bodyY();
		for (int i = p.from(); i < p.to(); i++) {
			VillageBoard.Row r = c.rows().get(i);
			shownRows.add(r.buildingId());
			float x0 = l.bx0();
			float x1 = l.bx1();
			float h = VillageBoard.ROW_H;
			cards.add(new Card(x0, y, x1 - x0, h - 1));
			float tx = x0 + 4;
			float tw = x1 - x0 - 8;
			// line 1: name (left), PR counts (right)
			String prs = prText(r);
			int prw = prs.isEmpty() ? 0 : font.width(prs);
			if (!prs.isEmpty()) {
				cardTexts.add(new Text(prs, x1 - 4 - prw, y + VillageBoard.CARD_LINE_Y, r.failing() ? error : muted));
			}
			String name = r.name() + (r.home() ? " · home" : "");
			cardTexts.add(new Text(TextUtil.ellipsize(font, name, (int) (tw - prw - (prw > 0 ? 6 : 0))), tx, y + VillageBoard.CARD_LINE_Y, ink));
			// line 2: lead portrait + name, then the repos
			float lx = tx;
			// Marlow leads a building without its own lead; offline nobody does ("no lead: Foreman offline"): no face then
			Identifier face = offline ? null : r.leadId() == null ? portrait("marlow") : portrait(r.leadId());
			if (face != null) {
				pics.add(new Pic(face, lx, y + VillageBoard.CARD_LINE_Y + VillageBoard.CARD_LINE_STEP - 0.5f, 8, false));
				lx += 10;
			}
			cardTexts.add(new Text(TextUtil.ellipsize(font, r.lead() + " · " + r.repos(), (int) (x1 - 4 - lx)), lx, y + VillageBoard.CARD_LINE_Y + VillageBoard.CARD_LINE_STEP, muted));
			// line 3: the active goal and its progress (a bar along the card's bottom edge)
			String goal = r.goal() == null ? "No active goal" : r.goal();
			String pct = r.progress() < 0 ? "" : Math.round(r.progress() * 100) + "%" + (r.moreGoals() > 0 ? " +" + r.moreGoals() : "");
			int pw2 = pct.isEmpty() ? 0 : font.width(pct);
			if (!pct.isEmpty()) {
				cardTexts.add(new Text(pct, x1 - 4 - pw2, y + VillageBoard.CARD_LINE_Y + 2 * VillageBoard.CARD_LINE_STEP, muted));
			}
			cardTexts.add(new Text(TextUtil.ellipsize(font, goal, (int) (tw - pw2 - (pw2 > 0 ? 6 : 0))), tx, y + VillageBoard.CARD_LINE_Y + 2 * VillageBoard.CARD_LINE_STEP, r.goal() == null ? muted : ink));
			if (r.progress() >= 0) {
				float bw = (x1 - x0 - 4) * (float) r.progress();
				overCards.add(x0 + 2, y + h - 3, x1 - 2, y + h - 2, 3.5f * Z, UiStyle.withAlpha(UiStyle.color("palette.ui.edge", 0xFFC9BBA3), 255), 0);
				overCards.add(x0 + 2, y + h - 3, x0 + 2 + bw, y + h - 2, 3.6f * Z, UiStyle.status(r.progress() >= 1 ? "done" : "working"), 0);
			}
			y += h + VillageBoard.ROW_GAP;
		}
		// milestones (right column)
		if (l.twoColumns()) {
			float mx0 = l.mx0();
			float mx1 = l.mx1();
			int mw = (int) (mx1 - mx0);
			float my = l.bodyY();
			texts.add(new Text(TextUtil.ellipsize(font, "Milestones", mw), mx0, my, brass));
			my += 11;
			if (c.milestones().isEmpty()) {
				texts.add(new Text(TextUtil.ellipsize(font, "None yet", mw), mx0, my, mutedOnDark));
			}
			int n = Math.min(l.milestones(), c.milestones().size());
			for (int i = 0; i < n; i++) {
				VillageBoard.Milestone m = c.milestones().get(i);
				String when = VillageBoard.agoShort(m.at(), now);
				int ww = font.width(when);
				float lx = mx0;
				if (m.trophy()) {
					pics.add(new Pic(DisplayDraw.dot("done", false), lx, my + 1, 6, true));
					lx += 8;
				}
				// the label first: when it and the time do not both fit, the time goes (never a bare "…")
				boolean withTime = font.width(m.label()) + 4 + ww <= mx1 - lx;
				if (withTime) {
					texts.add(new Text(when, mx1 - ww, my, mutedOnDark));
				}
				texts.add(new Text(TextUtil.ellipsize(font, m.label(), (int) (mx1 - (withTime ? ww + 4 : 0) - lx)), lx, my, cream));
				String text = m.text().isBlank() ? m.where() : m.text() + " · " + m.where();
				texts.add(new Text(TextUtil.ellipsize(font, text, mw), mx0, my + 9, mutedOnDark));
				my += VillageBoard.MILESTONE_H + VillageBoard.MILESTONE_GAP;
				shownMilestones++;
			}
		}
		// hold banner (clay) and footer: what the right-click does
		if (c.hold() != null) {
			rects.add(l.ix0(), l.holdY(), l.ix1(), l.holdY() + VillageBoard.HOLD_H, Z, UiStyle.color("palette.colors.clay", UiStyle.CLAY), 0);
			texts.add(new Text(TextUtil.ellipsize(font, "Agents paused: " + c.hold(), l.ix1() - l.ix0() - 6), l.ix0() + 3, l.holdY() + 3, cream));
		}
		String foot = c.needsYou() > 0 ? "Needs you: " + c.needsLine() + " · right-click for the Inbox" : "Right-click: hub > Buildings";
		texts.add(new Text(TextUtil.ellipsize(font, foot, l.ix1() - l.ix0()), l.ix0(), l.footerY() + 2, c.needsYou() > 0 ? 0xFFE8A37F : mutedOnDark));
	}

	private static String prText(VillageBoard.Row r) {
		if (r.prsOpen() == 0 && r.prsMerged() == 0) {
			return "";
		}
		List<String> parts = new ArrayList<>();
		if (r.prsOpen() > 0) {
			parts.add(r.prsOpen() + (r.prsOpen() == 1 ? " PR" : " PRs"));
		}
		if (r.prsMerged() > 0) {
			parts.add(r.prsMerged() + " merged");
		}
		return String.join(" · ", parts);
	}

	private static final Map<String, @Nullable Identifier> PORTRAITS = new HashMap<>();

	/** The agent's portrait texture when the mod ships one (cast members), else null (no purple squares). */
	static @Nullable Identifier portrait(String agentId) {
		return PORTRAITS.computeIfAbsent(agentId, id -> {
			Identifier tex = AgentCraft.id("textures/gui/portrait/" + id + ".png");
			return Minecraft.getInstance().getResourceManager().getResource(tex).isPresent() ? tex : null;
		});
	}
}
