package dev.agentcraft.client.building;

import dev.agentcraft.building.DesignSpec;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

/** The plot-marking HUD: the same ink panel as {@link PlacementHud}, with the step, the plot's size and limit, and the keys. */
final class PlotHud implements HudElement {
	/** The panel drawn last frame (x, y, w, h), null when hidden: toasts stop above it. */
	static volatile int @org.jspecify.annotations.Nullable [] lastRect;

	private static final long STATUS_MS = 6000;
	private static final int ORANGE = 0xFFF0A060;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Font font = mc.font;
		PlotMarker.View v = PlotMarker.view();
		String status = PlotMarker.status();
		boolean fresh = status != null && Util.getMillis() - PlotMarker.statusAt < STATUS_MS;
		if (v == null && !fresh) {
			lastRect = null;
			return;
		}
		int maxW = Math.min(420, g.guiWidth() - 16);
		Kit.Padding p = Kit.padding("tooltip");
		int inner = maxW - p.left() - p.right();
		int cream = UiStyle.CREAM;
		int soft = UiBits.activityOnInk();
		List<String[]> lines = new ArrayList<>(); // text, color
		String[] hints;
		if (v != null) {
			int[] h = v.hover();
			if (v.first() == null) {
				lines.add(new String[] {"Mark a plot: look at the first corner, Enter", Integer.toString(cream)});
				lines.add(new String[] {"corner " + h[0] + ", " + h[1] + ", " + h[2] + " · height limit " + v.height(), Integer.toString(soft)});
				hints = new String[] {"Enter", "first corner", "PgUp/Dn", "height", "Esc", "back to the form"};
			} else {
				DesignSpec.Plot plot = v.plot(PlotMarker.dimension());
				lines.add(new String[] {"Plot " + plot.dx() + " × " + plot.dz() + " (x by z) · height " + plot.height() + " · entrance "
					+ plot.front(), Integer.toString(cream)});
				int[] m = plot.maxSize();
				String limit = "design limit " + m[0] + " × " + m[1] + " × " + m[2] + " (wide × high × deep)";
				lines.add(new String[] {limit, Integer.toString(UiStyle.SAGE)});
				if (plot.tooSmall()) {
					lines.add(new String[] {"Smaller than " + DesignSpec.MIN_XZ + " a side: the limit is raised to " + DesignSpec.MIN_XZ,
						Integer.toString(ORANGE)});
				} else if (plot.tooLarge()) {
					lines.add(new String[] {"Larger than " + DesignSpec.MAX_XZ + " a side: the limit is capped at " + DesignSpec.MAX_XZ,
						Integer.toString(ORANGE)});
				}
				hints = new String[] {"Enter", "done", "PgUp/Dn", "height", "Backspace", "first corner", "Esc", "back"};
			}
		} else {
			lines.add(new String[] {status, Integer.toString(cream)});
			hints = new String[0];
		}
		if (hints.length > 0 && UiBits.hintsWidth(font, hints) > inner) {
			// 426x240 (4K at auto GUI scale): the row was dropped altogether; keep the keys that finish the plot
			hints = v != null && v.first() == null ? new String[] {"Enter", "corner", "PgUp/Dn", "height", "Esc", "back"}
				: new String[] {"Enter", "done", "Bksp", "redo", "Esc", "back"};
		}
		int hintW = hints.length == 0 ? 0 : UiBits.hintsWidth(font, hints);
		int textW = hintW;
		for (String[] l : lines) {
			textW = Math.max(textW, font.width(l[0]));
		}
		int w = Math.min(maxW, textW + p.left() + p.right());
		int hgt = p.top() + lines.size() * 10 + (hints.length == 0 ? 0 : 15) + p.bottom() - 1;
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() - 64 - hgt;
		if (y < g.guiHeight() / 2 + 8) {
			// a short screen: keep the crosshair (the corner is aimed with it) clear
			boolean bars = mc.gameMode != null && mc.gameMode.getPlayerMode().isSurvival();
			y = Math.max(g.guiHeight() / 2 + 8, g.guiHeight() - (bars ? 50 : 26) - hgt);
		}
		lastRect = new int[] {x, y, w, hgt};
		Panels.sprite(g, Kit.TOOLTIP, x, y, w, hgt, 0xF0FFFFFF);
		int ty = y + p.top();
		for (String[] l : lines) {
			g.text(font, TextUtil.ellipsize(font, l[0], inner), x + p.left(), ty, Integer.parseInt(l[1]), false);
			ty += 10;
		}
		if (hints.length > 0 && hintW <= w - p.left() - p.right()) {
			UiBits.hints(g, font, x + p.left(), ty + 2, true, hints);
		}
	}
}
