package dev.agentcraft.client.road;

import dev.agentcraft.building.RoadPlan;
import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The road preview's HUD panel (above the hotbar, like the placement HUD): the two buildings and the options, the verdict,
 * what the road does (tan / orange / red counts, lanterns), the first skip notes and the keys. After a lay the result stays
 * for a few seconds. Fits 426x240 GUI px (the 4K auto scale): lines are cut with an ellipsis, the key row shrinks.
 */
final class RoadHud implements HudElement {
	private static final long STATUS_MS = 8000;
	private static final int RED = 0xFFF07060;
	private static final int ORANGE = 0xFFF0A060;
	/** The panel drawn last frame (x, y, w, h), null when hidden: toasts stop above it ({@code BuildPlacement.hudRect}). */
	static volatile int @org.jspecify.annotations.Nullable [] lastRect;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.screen() != null) {
			lastRect = null;
			return;
		}
		RoadsFeature.Preview pv = RoadsFeature.preview();
		RoadsFeature.Result last = RoadsFeature.last();
		boolean fresh = last != null && System.currentTimeMillis() - RoadsFeature.lastAt() < STATUS_MS && "lay".equals(last.action());
		if (pv == null && !fresh) {
			lastRect = null;
			return;
		}
		Font font = mc.font;
		int maxW = Math.min(420, g.guiWidth() - 16);
		Kit.Padding p = Kit.padding("tooltip");
		int inner = maxW - p.left() - p.right();
		int cream = UiStyle.CREAM;
		int soft = UiBits.activityOnInk();
		List<Object[]> lines = new ArrayList<>();
		if (pv != null) {
			RoadPlan.Plan plan = pv.plan();
			RoadPlan.Options o = pv.options();
			String opts = "width " + o.width() + (o.lanterns() ? " · lanterns" : "") + (o.bridge() ? " · bridges" : "");
			lines.add(new Object[] {TextUtil.ellipsize(font, "Road " + pv.a() + " → " + pv.b(), inner - font.width(opts) - 8), cream, opts});
			if (RoadsFeature.busy()) {
				lines.add(new Object[] {"Laying…", soft, null});
			} else if (plan.refusal() != null) {
				lines.add(new Object[] {TextUtil.ellipsize(font, "Would be refused: " + plan.refusal(), inner), RED, null});
			} else {
				lines.add(new Object[] {TextUtil.ellipsize(font, "Ready: Enter lays it (Remove in the hub puts every cell back)", inner), UiStyle.SAGE, null});
			}
			int paved = plan.ops().size() - plan.cleared() - plan.count(RoadPlan.Block.FENCE) - plan.count(RoadPlan.Block.LANTERN);
			String counts = plan.cells().size() + " cells · " + paved + " paved (tan)" + (plan.cleared() == 0 ? "" : " · " + plan.cleared() + " cleared (orange)")
				+ (plan.lanternCount() == 0 ? "" : " · " + plan.lanternCount() + " lantern" + (plan.lanternCount() == 1 ? "" : "s"));
			lines.add(new Object[] {TextUtil.ellipsize(font, counts, inner), plan.cleared() > 0 ? ORANGE : soft, null});
			List<String> notes = plan.notes();
			if (!notes.isEmpty()) {
				String n = notes.get(0) + (notes.size() > 1 ? " (+" + (notes.size() - 1) + " more)" : "");
				lines.add(new Object[] {TextUtil.ellipsize(font, "Red: " + n, inner), RED, null});
			}
		}
		if (fresh && last != null) {
			for (String l : TextUtil.wrapPlain(font, last.message(), inner)) {
				if (lines.size() >= 7) {
					break;
				}
				lines.add(new Object[] {l, last.ok() ? cream : RED, null});
			}
		}
		String[] hints = pv == null ? new String[0] : new String[] {"Enter", "lay road", "Esc", "cancel"};
		int textW = hints.length == 0 ? 0 : UiBits.hintsWidth(font, hints);
		for (Object[] l : lines) {
			textW = Math.max(textW, font.width((String) l[0]) + (l[2] == null ? 0 : font.width((String) l[2]) + 8));
		}
		int w = Math.min(maxW, textW + p.left() + p.right());
		int h = p.top() + lines.size() * 10 + (hints.length == 0 ? 0 : 15) + p.bottom() - 1;
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() - 64 - h;
		if (y < g.guiHeight() / 2 + 8) {
			boolean bars = mc.gameMode != null && mc.gameMode.getPlayerMode().isSurvival();
			y = Math.max(g.guiHeight() / 2 + 8, g.guiHeight() - (bars ? 50 : 26) - h);
		}
		lastRect = new int[] {x, y, w, h};
		Panels.sprite(g, Kit.TOOLTIP, x, y, w, h, 0xF0FFFFFF);
		int ty = y + p.top();
		for (Object[] l : lines) {
			g.text(font, (String) l[0], x + p.left(), ty, (Integer) l[1], false);
			if (l[2] != null) {
				g.text(font, (String) l[2], x + w - p.right() - font.width((String) l[2]), ty, soft, false);
			}
			ty += 10;
		}
		if (hints.length > 0) {
			UiBits.hints(g, font, x + p.left(), ty + 2, true, hints);
		}
	}
}
