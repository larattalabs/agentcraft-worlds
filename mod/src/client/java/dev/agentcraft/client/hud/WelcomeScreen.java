package dev.agentcraft.client.hud;

import com.google.gson.JsonObject;
import dev.agentcraft.client.hub.HubFeature;
import dev.agentcraft.client.hub.HubTab;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The welcome card (docs/WAVE2.md W7): opened once on joining a singleplayer world with AgentCraft and no
 * building yet ({@link HudWatch}). What AgentCraft is (two lines), the three keys that matter (hub,
 * decisions, console, live bindings), how to place the first building, and the Foreman's status. "Open the
 * hub" (Buildings tab) or "Got it" (also Esc) dismiss it for good in this world ({@code hub-hud.json}); the
 * hub's Status > Keys & help can show it again. Pauses like every AgentCraft screen. Scrolls when the
 * window is too short (4K auto scale).
 */
public final class WelcomeScreen extends Screen implements dev.agentcraft.client.ui.HasParent {
	private static final int MAX_W = 400;
	private int scroll;
	private int maxScroll;
	private final List<int[]> btnRects = new ArrayList<>();
	private final List<Runnable> btnActions = new ArrayList<>();
	private final List<String> btnIds = new ArrayList<>();

	/** QA: the last frame's layout. */
	static int needed;
	static int available;
	static boolean overflow;

	private final @org.jspecify.annotations.Nullable Screen parent;

	public WelcomeScreen() {
		this(null);
	}

	/** Opened from {@code parent} (the hub's Keys &amp; help): closing returns there. */
	public WelcomeScreen(@org.jspecify.annotations.Nullable Screen parent) {
		super(Component.literal("Welcome to AgentCraft Worlds"));
		this.parent = parent;
	}

	@Override
	public @org.jspecify.annotations.Nullable Screen parent() {
		return parent;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
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

	private record KeyLine(String key, String text) {
	}

	private List<KeyLine> keyLines() {
		return List.of(new KeyLine(HelpContent.key(Keys.hub, "H"), "the hub: inbox, buildings, goals, team, settings, status"),
			new KeyLine(HelpContent.key(Keys.decisions, "J"), "answer your team's decisions"),
			new KeyLine(HelpContent.key(Keys.console, "`"), "the console: talk to the team, give goals"));
	}

	/** The body's height for a content width. */
	private int bodyHeight(int cw) {
		int h = 0;
		for (String s : HelpContent.ABOUT) {
			h += TextUtil.wrapPlain(font, s, cw).size() * 10 + 2;
		}
		h += 8;
		for (KeyLine k : keyLines()) {
			h += Math.max(14, TextUtil.wrapPlain(font, k.text(), cw - 60).size() * 10 + 4);
		}
		h += 6 + TextUtil.wrapPlain(font, HelpContent.firstBuilding(), cw).size() * 10 + 6;
		h += TextUtil.wrapPlain(font, HelpContent.foreman().text(), cw - 12).size() * 10 + 2;
		return h;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		btnRects.clear();
		btnActions.clear();
		btnIds.clear();
		Kit.Padding pad = Kit.padding("panel_paper");
		int pw = Math.min(MAX_W, width - 16);
		int cw = pw - pad.left() - pad.right();
		int body = bodyHeight(cw);
		int chrome = pad.top() + 18 + 8 + 20 + pad.bottom();
		int ph = Math.min(height - 16, body + chrome);
		int px = (width - pw) / 2;
		int py = Math.max(8, (height - ph) / 2);
		int cx = px + pad.left();
		int cy = py + pad.top();
		int view = ph - chrome;
		needed = body + chrome;
		available = height - 16;
		overflow = body > view;
		maxScroll = Math.max(0, body - view);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		Panels.panel(g, px, py, pw, ph);
		Panels.header(g, font, "Welcome to AgentCraft Worlds", cx - 2, cy - 2, cw + 4);
		int top = cy + 18;
		g.enableScissor(cx - 2, top, cx + cw + 2, top + view);
		int y = top - scroll;
		for (String s : HelpContent.ABOUT) {
			for (String line : TextUtil.wrapPlain(font, s, cw)) {
				g.text(font, line, cx, y, UiBits.ink(), false);
				y += 10;
			}
			y += 2;
		}
		Panels.divider(g, cx, y + 2, cw);
		y += 8;
		for (KeyLine k : keyLines()) {
			int kw = UiBits.keycap(g, font, k.key(), cx, y);
			int tx = cx + Math.max(kw, 52) + 6;
			List<String> lines = TextUtil.wrapPlain(font, k.text(), cw - 60);
			int ly = y + 2;
			for (String line : lines) {
				g.text(font, line, tx, ly, UiBits.ink(), false);
				ly += 10;
			}
			y += Math.max(14, lines.size() * 10 + 4);
		}
		y += 6;
		for (String line : TextUtil.wrapPlain(font, HelpContent.firstBuilding(), cw)) {
			g.text(font, line, cx, y, UiStyle.CLAY_DARK, false);
			y += 10;
		}
		y += 6;
		HelpContent.Status fs = HelpContent.foreman();
		Panels.dot(g, fs.family(), cx, y + 1, false);
		for (String line : TextUtil.wrapPlain(font, fs.text(), cw - 12)) {
			g.text(font, line, cx + 12, y, UiBits.muted(), false);
			y += 10;
		}
		g.disableScissor();
		if (overflow) {
			TextUtil.Scroll sc = new TextUtil.Scroll();
			sc.update(body, view);
			sc.scrollBy(Integer.MIN_VALUE / 2);
			sc.scrollBy(scroll);
			Panels.scrollbar(g, cx + cw - 4, top, view, sc, false);
		}
		// footer
		int by = py + ph - pad.bottom() - 20;
		int bx = cx + cw;
		bx = button(g, "got_it", "Got it", bx, by, false, mouseX, mouseY, this::gotIt);
		bx = button(g, "open_hub", "Open the hub", bx - 6, by, true, mouseX, mouseY, this::openHub);
		String[] hints = {"Esc", "close for good"};
		if (UiBits.hintsWidth(font, hints) <= bx - cx - 8) {
			UiBits.hints(g, font, cx, by + 4, false, hints);
		}
	}

	private int button(GuiGraphicsExtractor g, String id, String label, int right, int y, boolean primary, int mx, int my, Runnable action) {
		int w = UiBits.buttonWidth(font, label, 0);
		int x = right - w;
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + 20;
		UiBits.button(g, font, label, 0, x, y, w, primary, hover ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL, false);
		btnRects.add(new int[] {x, y, w, 20});
		btnActions.add(action);
		btnIds.add(id);
		return x;
	}

	/** Presses a button by id (DevBridge). */
	boolean press(String id) {
		int i = btnIds.indexOf(id);
		if (i < 0) {
			return false;
		}
		btnActions.get(i).run();
		return true;
	}

	void gotIt() {
		HudWatch.dismissWelcome();
		onClose();
	}

	void openHub() {
		HudWatch.dismissWelcome();
		HubFeature.open(HubTab.BUILDINGS);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.isEscape()) {
			gotIt();
			return true;
		}
		if (Keys.matches(Keys.hub, e)) {
			openHub();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		for (int i = 0; i < btnRects.size(); i++) {
			int[] r = btnRects.get(i);
			if (e.x() >= r[0] && e.x() < r[0] + r[2] && e.y() >= r[1] && e.y() < r[1] + r[3]) {
				btnActions.get(i).run();
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (maxScroll > 0) {
			scroll = Math.max(0, Math.min(maxScroll, scroll + (scrollY > 0 ? -12 : 12)));
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	static JsonObject layoutJson() {
		JsonObject o = new JsonObject();
		o.addProperty("needed", needed);
		o.addProperty("available", available);
		o.addProperty("overflow", overflow);
		return o;
	}
}
