package dev.agentcraft.client.building;

import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Shared frame of the two wizard steps: a paper panel with a header ("New building · step"), a body
 * the step draws, and a footer with buttons and key hints. Not a pause screen (the studio keeps
 * moving behind it).
 */
abstract class WizardScreen extends Screen {
	protected static final int MAX_W = 420;
	protected int px;
	protected int py;
	protected int pw;
	protected int ph;
	/** Content box inside the panel padding. */
	protected int cx;
	protected int cy;
	protected int cw;
	private final List<Btn> buttons = new ArrayList<>();

	protected record Btn(String label, int x, int y, int w, boolean primary, boolean disabled, Runnable action) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + 20;
		}
	}

	protected WizardScreen(String title) {
		super(Component.literal(title));
	}

	@Override
	public boolean isPauseScreen() {
		return dev.agentcraft.client.ui.ScreenPause.pauses();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractBlurredBackground(g);
		g.fillGradient(0, 0, width, height, UiStyle.withAlpha(UiStyle.INK, 50), UiStyle.withAlpha(UiStyle.INK, 100));
	}

	/** Lays out the panel for a body of {@code bodyH} px; returns the body's top. */
	protected int frame(GuiGraphicsExtractor g, String step, int bodyH) {
		buttons.clear();
		pw = Math.min(MAX_W, width - 24);
		Kit.Padding pad = Kit.padding("panel_paper");
		cw = pw - pad.left() - pad.right();
		ph = pad.top() + 18 + bodyH + 8 + 20 + pad.bottom();
		px = (width - pw) / 2;
		py = Math.max(8, (height - ph) / 2);
		cx = px + pad.left();
		cy = py + pad.top();
		Panels.panel(g, px, py, pw, ph);
		Panels.header(g, font, "New building · " + step, cx - 2, cy - 2, cw + 4);
		return cy + 18;
	}

	/** Footer: hints on the left, buttons right-aligned (given right to left). */
	protected void footer(GuiGraphicsExtractor g, int mouseX, int mouseY, String[] hints, Btn... rightToLeft) {
		int by = py + ph - Kit.padding("panel_paper").bottom() - 20;
		int bx = cx + cw;
		for (Btn b : rightToLeft) {
			bx -= b.w();
			Btn placed = new Btn(b.label(), bx, by, b.w(), b.primary(), b.disabled(), b.action());
			buttons.add(placed);
			UiBits.ButtonState st = b.disabled() ? UiBits.ButtonState.DISABLED : placed.hit(mouseX, mouseY) ? UiBits.ButtonState.HOVER
				: UiBits.ButtonState.NORMAL;
			UiBits.button(g, font, b.label(), 0, bx, by, b.w(), b.primary(), st, false);
			bx -= 6;
		}
		if (hints.length > 0 && UiBits.hintsWidth(font, hints) <= bx - cx - 6) {
			UiBits.hints(g, font, cx, by + 4, false, hints);
		}
	}

	protected static Btn btn(String label, int w, boolean primary, boolean disabled, Runnable action) {
		return new Btn(label, 0, 0, w, primary, disabled, action);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		for (Btn b : buttons) {
			if (b.hit(e.x(), e.y())) {
				if (!b.disabled()) {
					b.action().run();
				}
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	protected static void checkbox(GuiGraphicsExtractor g, int x, int y, boolean checked, boolean disabled) {
		Panels.sprite(g, checked ? Kit.CHECKBOX_CHECKED : Kit.CHECKBOX, x, y, 10, 10, disabled ? 0x80FFFFFF : 0xFFFFFFFF);
	}
}
