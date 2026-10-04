package dev.agentcraft.client.building;

import dev.agentcraft.building.BlueprintTransform;
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

/**
 * The placement HUD: an ink panel above the hotbar with the blueprint, the repos, the rotation, the
 * verdict (ready / what {@code place} would refuse), the conflict counts and the keys. After a
 * placement (or a cancel) the last status line stays for a few seconds.
 */
final class PlacementHud implements HudElement {
	private static final long STATUS_MS = 8000;
	private static final int ORANGE = 0xFFF0A060;
	private static final int RED = 0xFFF07060;
	private static final int WATER_INK = 0xFF8CB4F0;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Font font = mc.font;
		BuildPlacement.View v = BuildPlacement.view();
		String status = BuildPlacement.status();
		boolean fresh = status != null && Util.getMillis() - BuildPlacement.statusAt < STATUS_MS;
		if (v == null && !fresh) {
			lastRect = null;
			return;
		}
		int maxW = Math.min(420, g.guiWidth() - 16);
		Kit.Padding p = Kit.padding("tooltip");
		int inner = maxW - p.left() - p.right();
		int cream = UiStyle.CREAM;
		int soft = UiBits.activityOnInk();

		List<Line> lines = new ArrayList<>();
		if (v != null) {
			String mv = BuildPlacement.moving();
			String title = (mv != null ? "Moving " + mv + " (" + v.bp().name() + ")" : "Placing " + v.bp().name()) + "  for "
				+ String.join(", ", BuildPlacement.repos());
			String rot = BlueprintTransform.rotationName(v.turns()).replace('_', ' ') + " · entrance " + v.front() + (v.locked() ? " · locked" : "");
			lines.add(new Line(TextUtil.ellipsize(font, title, inner - font.width(rot) - 8), cream, rot, soft));
			String verdict;
			int vc;
			if (v.pending()) {
				verdict = "Placing…";
				vc = soft;
			} else if (v.refusals().isEmpty()) {
				verdict = BuildPlacement.moving() != null ? "Ready: Enter moves it here (the old site comes back as it was)" : "Ready: Enter places it";
				vc = UiStyle.SAGE;
			} else {
				verdict = "Would be refused: " + String.join("; ", v.refusals());
				vc = RED;
			}
			lines.add(new Line(TextUtil.ellipsize(font, verdict, inner), vc, null, 0));
			String counts = (v.obstructedCount() == 0 ? "nothing in the way" : v.obstructedCount() + " block" + (v.obstructedCount() == 1 ? "" : "s")
				+ " replaced (orange)") + (v.blockedCount() == 0 ? "" : " · " + v.blockedCount() + " block entit" + (v.blockedCount() == 1 ? "y" : "ies")
				+ " (red)");
			lines.add(new Line(TextUtil.ellipsize(font, counts, inner), v.blockedCount() > 0 ? RED : v.obstructedCount() > 0 ? ORANGE : soft, null, 0));
			String terrain = (v.fillCount() == 0 ? "no foundation needed" : v.fillCount() + " foundation block" + (v.fillCount() == 1 ? "" : "s") + " (grey)")
				+ (v.clearCount() == 0 ? "" : " · " + v.clearCount() + " terrain cleared (pale)") + (v.lavaCount() == 0 ? "" : " · " + v.lavaCount() + " lava (amber)");
			lines.add(new Line(TextUtil.ellipsize(font, terrain, inner), v.lavaCount() > 0 ? RED : soft, null, 0));
			if (v.approach().rows() > 0) {
				dev.agentcraft.building.Approach.Plan ap = v.approach();
				int[] feet = ap.feet();
				int rise = feet[feet.length - 1] - feet[0];
				String path = "Entrance path " + ap.rows() + " blocks (tan)" + (rise == 0 ? ", level" : rise > 0 ? ", up " + rise : ", down " + -rise)
					+ (ap.fillCount() == 0 ? "" : " · " + ap.fillCount() + " filled") + (ap.clearCount() == 0 ? "" : " · " + ap.clearCount() + " cut");
				lines.add(new Line(TextUtil.ellipsize(font, path, inner), soft, null, 0));
			}
			for (String n : v.notes()) {
				lines.add(new Line(TextUtil.ellipsize(font, "Note: " + n, inner), WATER_INK, null, 0));
			}
		}
		if (fresh || v != null && v.forceArmed()) {
			String s = status == null ? "" : status;
			for (String l : TextUtil.wrapPlain(font, s, inner)) {
				lines.add(new Line(l, BuildPlacement.statusError() ? RED : cream, null, 0));
			}
		}
		String[] hints = v == null ? new String[0] : v.forceArmed()
			? new String[] {"Shift+Enter", "force", "R", "rotate", "Esc", "cancel"}
			: new String[] {"R", "rotate", "Arrows", "nudge", "PgUp/Dn", "raise", "L", v.locked() ? "unlock" : "lock", "Enter", "place", "Esc", "cancel"};
		if (hints.length > 0 && UiBits.hintsWidth(font, hints) > inner) {
			// 426x240 (4K at auto GUI scale): the full row ran past the panel
			hints = v.forceArmed() ? new String[] {"Shift+Enter", "force", "Esc", "cancel"}
				: new String[] {"R", "rotate", "L", v.locked() ? "unlock" : "lock", "Enter", "place", "Esc", "cancel"};
			if (UiBits.hintsWidth(font, hints) > inner) {
				hints = new String[] {"Enter", "place", "Esc", "cancel"};
			}
		}
		int hintW = hints.length == 0 ? 0 : UiBits.hintsWidth(font, hints);
		int textW = hintW;
		for (Line l : lines) {
			textW = Math.max(textW, font.width(l.text()) + (l.right() == null ? 0 : font.width(l.right()) + 8));
		}
		int w = Math.min(maxW, textW + p.left() + p.right());
		int h = p.top() + lines.size() * 10 + (hints.length == 0 ? 0 : 15) + p.bottom() - 1;
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() - 64 - h;
		if (y < g.guiHeight() / 2 + 8) {
			// a short screen: never over the crosshair (the ghost is aimed with it); creative has no hearts above the hotbar
			boolean bars = mc.gameMode != null && mc.gameMode.getPlayerMode().isSurvival();
			y = Math.max(g.guiHeight() / 2 + 8, g.guiHeight() - (bars ? 50 : 26) - h);
		}
		lastRect = new int[] {x, y, w, h};
		Panels.sprite(g, Kit.TOOLTIP, x, y, w, h, 0xF0FFFFFF);
		int ty = y + p.top();
		for (Line l : lines) {
			g.text(font, l.text(), x + p.left(), ty, l.color(), false);
			if (l.right() != null) {
				g.text(font, l.right(), x + w - p.right() - font.width(l.right()), ty, l.rightColor(), false);
			}
			ty += 10;
		}
		if (hints.length > 0) {
			UiBits.hints(g, font, x + p.left(), ty + 2, true, hints);
		}
	}

	/** The panel drawn last frame (x, y, w, h), null when hidden: the goal bar and toasts keep clear of it. */
	static volatile int @org.jspecify.annotations.Nullable [] lastRect;

	private record Line(String text, int color, String right, int rightColor) {
	}
}
