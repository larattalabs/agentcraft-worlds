package dev.agentcraft.client.hub;

import com.google.gson.JsonObject;
import dev.agentcraft.client.hud.HelpContent;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The Status tab: two views behind chips, <b>Overview</b> (the Foreman link, backend, usage, versions:
 * {@link HubScreen#drawStatus}) and <b>Keys &amp; help</b> (docs/WAVE2.md W7): every AgentCraft key with its
 * live binding ({@link HelpContent#keys()}), every in-world interaction, and "Show the welcome card". The help
 * view scrolls (wheel, ↑↓) and reports {needed, available, overflow} in {@code dev.hub.state statusTab}.
 */
final class StatusPane implements HubPane {
	enum View {
		OVERVIEW("overview", "Overview"), HELP("help", "Keys & help");

		final String id;
		final String label;

		View(String id, String label) {
			this.id = id;
			this.label = label;
		}

		static @Nullable View parse(@Nullable String s) {
			if (s == null) {
				return null;
			}
			String k = s.strip().toLowerCase(Locale.ROOT);
			for (View v : values()) {
				if (v.id.equals(k) || k.startsWith("key") && v == HELP) {
					return v;
				}
			}
			return null;
		}
	}

	private final HubScreen hub;
	private View view = View.OVERVIEW;
	private int scroll;
	private int maxScroll;
	private int needed;
	private int available;
	private final List<int[]> chipRects = new ArrayList<>();
	private final List<View> chipViews = new ArrayList<>();
	private int bodyX;
	private int bodyY;
	private int bodyW;
	private int bodyH;

	StatusPane(HubScreen hub) {
		this.hub = hub;
	}

	View view() {
		return view;
	}

	void setView(View v) {
		view = v;
		scroll = 0;
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		chipRects.clear();
		chipViews.clear();
		var font = hub.font();
		int cx = x;
		for (View v : View.values()) {
			int cw = font.width(v.label) + 12;
			boolean on = v == view;
			Panels.sprite(g, on ? Kit.TAB_ACTIVE : Kit.TAB_INACTIVE, cx, y, cw, 14);
			if (!on && mx >= cx && mx < cx + cw && my >= y && my < y + 14) {
				g.fill(cx + 1, y + 1, cx + cw - 1, y + 13, 0x14000000);
			}
			g.text(font, v.label, cx + 6, y + 3, on ? UiBits.ink() : UiBits.muted(), false);
			chipRects.add(new int[] {cx, y, cw, 14});
			chipViews.add(v);
			cx += cw + 4;
		}
		int top = y + 18;
		if (view == View.OVERVIEW) {
			hub.drawStatus(g, x, top, w, h - 18);
			return;
		}
		drawHelp(g, x, top, w, h - 18, mx, my);
	}

	private void drawHelp(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		var font = hub.font();
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		// button row at the bottom of the view (never scrolled away)
		int btnY = y + h - 20;
		int viewH = h - 24;
		bodyX = x;
		bodyY = y;
		bodyW = w;
		bodyH = viewH;
		List<HelpContent.KeyRow> keys = HelpContent.keys();
		int keyColW = 0;
		for (HelpContent.KeyRow k : keys) {
			keyColW = Math.max(keyColW, UiBits.keycapWidth(font, k.key()));
		}
		keyColW = Math.max(keyColW, 40);
		int textW = w - 10 - keyColW - 8;
		int whatW = Math.min(110, w / 3);
		// measure
		int total = 16;
		for (HelpContent.KeyRow k : keys) {
			total += Math.max(14, TextUtil.wrapPlain(font, k.name(), textW).size() * 10 + 4);
		}
		total += 6 + 16;
		for (HelpContent.Interaction it : HelpContent.INTERACTIONS) {
			total += Math.max(1, TextUtil.wrapPlain(font, it.how(), w - 10 - whatW - 6).size()) * 10 + 3;
		}
		total += 6 + TextUtil.wrapPlain(font, "Keys are rebindable: Options > Controls > Key Binds > AgentCraft.", w - 10).size() * 10;
		needed = total + 24;
		available = h;
		maxScroll = Math.max(0, total - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		g.enableScissor(x - 2, y, x + w + 2, y + viewH);
		int ly = y - scroll;
		ly = hub.section(g, "Keys", x, ly, w - 8);
		for (HelpContent.KeyRow k : keys) {
			if (k.bound()) {
				UiBits.keycap(g, font, k.key(), x, ly);
			} else {
				g.text(font, k.key(), x, ly + 2, muted, false);
			}
			List<String> lines = TextUtil.wrapPlain(font, k.name(), textW);
			int ty = ly + 2;
			for (String line : lines) {
				g.text(font, line, x + keyColW + 8, ty, ink, false);
				ty += 10;
			}
			ly += Math.max(14, lines.size() * 10 + 4);
		}
		ly += 6;
		ly = hub.section(g, "In the world", x, ly, w - 8);
		for (HelpContent.Interaction it : HelpContent.INTERACTIONS) {
			g.text(font, TextUtil.ellipsize(font, it.what(), whatW), x, ly, UiStyle.CLAY_DARK, false);
			List<String> lines = TextUtil.wrapPlain(font, it.how(), w - 10 - whatW - 6);
			for (String line : lines) {
				g.text(font, line, x + whatW + 6, ly, ink, false);
				ly += 10;
			}
			ly += 3;
		}
		ly += 6;
		for (String line : TextUtil.wrapPlain(font, "Keys are rebindable: Options > Controls > Key Binds > AgentCraft.", w - 10)) {
			g.text(font, line, x, ly, muted, false);
			ly += 10;
		}
		g.disableScissor();
		if (maxScroll > 0) {
			TextUtil.Scroll sc = new TextUtil.Scroll();
			sc.update(total, viewH);
			sc.scrollBy(Integer.MIN_VALUE / 2);
			sc.scrollBy(scroll);
			Panels.scrollbar(g, x + w - 6, y, viewH, sc, false);
		}
		String label = "Show the welcome card";
		hub.button(g, "help_welcome", label, x, btnY, hub.bw(label), false, false, false, mx, my, () -> hub.mc().gui.setScreen(
			new dev.agentcraft.client.hud.WelcomeScreen(hub)));
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (view == View.HELP && (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_UP || e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_DOWN)) {
			scroll = Math.max(0, Math.min(maxScroll, scroll + (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_UP ? -10 : 10)));
			return true;
		}
		if (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT || e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT) {
			setView(e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT ? View.OVERVIEW : View.HELP);
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return false;
	}

	@Override
	public boolean mouseClicked(double x, double y, boolean doubleClick) {
		for (int i = 0; i < chipRects.size(); i++) {
			int[] r = chipRects.get(i);
			if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) {
				setView(chipViews.get(i));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, int dir) {
		if (view == View.HELP && maxScroll > 0 && x >= bodyX && x < bodyX + bodyW && y >= bodyY && y < bodyY + bodyH) {
			scroll = Math.max(0, Math.min(maxScroll, scroll + dir * 12));
			return true;
		}
		return false;
	}

	@Override
	public @Nullable String focus() {
		return null;
	}

	@Override
	public void unfocus() {
	}

	@Override
	public void shown(boolean on) {
	}

	@Override
	public String[] hints() {
		return view == View.HELP ? new String[] {"Tab", "next tab", "←→", "overview/help", "↑↓", "scroll", "Esc", "close"}
			: new String[] {"Tab", "next tab", "←→", "overview/help", "Esc", "close"};
	}

	@Override
	public JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("view", view.id);
		if (view == View.HELP) {
			JsonObject l = new JsonObject();
			l.addProperty("needed", needed);
			l.addProperty("available", available);
			l.addProperty("overflow", maxScroll > 0);
			l.addProperty("scroll", scroll);
			l.addProperty("maxScroll", maxScroll);
			o.add("layout", l);
			o.add("help", HelpContent.json());
		}
		return o;
	}
}
