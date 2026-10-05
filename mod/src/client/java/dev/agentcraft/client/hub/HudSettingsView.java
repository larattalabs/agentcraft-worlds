package dev.agentcraft.client.hub;

import com.google.gson.JsonObject;
import dev.agentcraft.client.hud.HudConfig;
import dev.agentcraft.client.hud.HudModel;
import dev.agentcraft.client.hud.HudOverlay;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hud.HudLayout;
import dev.agentcraft.hud.HudLayout.Env;
import dev.agentcraft.hud.HudLayout.Placement;
import dev.agentcraft.hud.HudLayout.Rect;
import dev.agentcraft.hud.HudSettings;
import dev.agentcraft.hud.HudSettings.Position;
import dev.agentcraft.hud.HudSettings.Size;
import dev.agentcraft.hud.HudSettings.Style;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Settings > General > HUD: the overlay's client-side settings ({@link HudConfig}, {@code hud.json}; no Foreman needed,
 * so it shows before the Foreman's General settings and also while they load): style, position, size, the top-left
 * offset (room for a minimap), peek on change, auto-hide when idle, hide in combat and which notifies become toasts.
 * Changes apply and save at once (not staged: Apply / Revert are the Foreman's). Under them a live preview: a small
 * 426x240 screen with a boss bar, an effect icon, the hotbar and a few chat lines showing where the overlay goes, and
 * the chosen style at its real size (live data when a goal runs or something needs you, else a sample).
 * Chip ids for {@code dev.hub.action press}: {@code hud:style:<style>}, {@code hud:position:<pos>}, {@code hud:size:<s>},
 * {@code hud:offset:-|+}, {@code hud:peek}, {@code hud:autoHide}, {@code hud:hideInCombat}, {@code hud:toasts:<mode>}.
 */
final class HudSettingsView {
	private static final int LABEL_W = 92;
	private static final int ROW_H = SettingsForm.CHIP_H + 4;
	/** The preview screen's GUI size (1278x720 at auto scale). */
	private static final int PW = 426;
	private static final int PH = 240;

	private final SettingsForm form;
	private Rect lastPreview = Rect.NONE;
	private Rect lastPreviewScreen = Rect.NONE;
	private boolean lastSample;

	HudSettingsView(SettingsForm form) {
		this.form = form;
	}

	/** Draws the HUD controls and the preview at {@code x, y}; returns the height used. */
	int draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mx, int my) {
		HudSettings s = HudConfig.get();
		int y0 = y;
		boolean narrow = w < 300;
		int lw = narrow ? 0 : w < 480 ? 64 : LABEL_W;
		int muted = UiBits.muted();
		int ink = UiBits.ink();
		y = chipRow(g, font, "Style", x, y, w, lw, mx, my, Style.values(), s.style(), Style::label, "hud:style:", Style::wire, st -> s.setStyle(st));
		y = chipRow(g, font, "Position", x, y, w, lw, mx, my, Position.values(), s.position(), Position::label, "hud:position:", Position::wire,
			p -> s.setPosition(p));
		y = chipRow(g, font, "Size", x, y, w, lw, mx, my, Size.values(), s.size(), Size::label, "hud:size:", Size::wire, sz -> s.setSize(sz));
		if (s.position() == Position.TOP_LEFT) {
			// room for a minimap (Xaero's sits there): the overlay starts this far down
			int cx = label(g, font, "Top-left offset", x, y, lw);
			cx += form.rowChip(g, "hud:offset:-", "−", cx, y, false, s.topLeftOffset() > 0, mx, my, () -> change(() -> s.setTopLeftOffset(s.topLeftOffset()
				- HudSettings.OFFSET_STEP))) + 3;
			String v = s.topLeftOffset() + " px";
			g.text(font, v, cx + 2, y + 3, ink, false);
			cx += font.width(v) + 6;
			cx += form.rowChip(g, "hud:offset:+", "+", cx, y, false, s.topLeftOffset() < HudSettings.OFFSET_MAX, mx, my, () -> change(() -> s
				.setTopLeftOffset(s.topLeftOffset() + HudSettings.OFFSET_STEP))) + 6;
			if (cx + 60 < x + w) {
				g.text(font, TextUtil.ellipsize(font, "room for a minimap", x + w - cx), cx, y + 3, muted, false);
			}
			y += ROW_H;
		}
		y = check(g, font, "hud:peek", "Peek on change (task done, PR merged, new decision, goal done)", s.peek(), x, y, w, () -> s.setPeek(!s.peek()));
		y = check(g, font, "hud:autoHide", "Auto-hide when idle (no active goal, nothing needs you)", s.autoHide(), x, y, w, () -> s.setAutoHide(!s
			.autoHide()));
		y = check(g, font, "hud:hideInCombat", "Hide in combat (hurt in the last 5 s or a hostile within 12 blocks)", s.hideInCombat(), x, y, w,
			() -> s.setHideInCombat(!s.hideInCombat()));
		y = chipRow(g, font, "Toasts", x, y, w, lw, mx, my, HudSettings.Toasts.values(), s.toasts(), HudSettings.Toasts::label, "hud:toasts:",
			HudSettings.Toasts::wire, t -> s.setToasts(t));
		y += 2;
		y += preview(g, font, s, x, y, w);
		String key = dev.agentcraft.client.hud.Keys.hudStyle == null || dev.agentcraft.client.hud.Keys.hudStyle.isUnbound() ? null
			: dev.agentcraft.client.hud.Keys.label(dev.agentcraft.client.hud.Keys.hudStyle);
		String note = "Saved for this game in hud.json. F1 hides everything. " + (key == null
			? "Bind \"Cycle the HUD style\" in Options > Controls (AgentCraft) to switch styles in game."
			: "Press " + key + " in game to cycle styles.");
		for (String line : TextUtil.wrapPlain(font, note, w)) {
			g.text(font, line, x, y, muted, false);
			y += 10;
		}
		return y - y0 + 6;
	}

	private int label(GuiGraphicsExtractor g, Font font, String label, int x, int y, int lw) {
		if (lw <= 0) {
			return x;
		}
		g.text(font, TextUtil.ellipsize(font, label, lw - 4), x, y + 3, UiBits.ink(), false);
		return x + lw;
	}

	private static void change(Runnable r) {
		r.run();
		HudConfig.save();
	}

	/** A label and one chip per value, wrapping under the label column when they do not fit. */
	private <E> int chipRow(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, int lw, int mx, int my, E[] values, E current,
		java.util.function.Function<E, String> name, String idPrefix, java.util.function.Function<E, String> wire, Consumer<E> set) {
		if (lw <= 0) {
			g.text(font, label, x, y + 2, UiBits.ink(), false);
			y += 11;
		}
		int cx = label(g, font, label, x, y, lw);
		int left = cx;
		for (E v : values) {
			String l = name.apply(v);
			int cw = form.chipW(l);
			if (cx > left && cx + cw > x + w) {
				cx = left;
				y += ROW_H;
			}
			cx += form.rowChip(g, idPrefix + wire.apply(v), l, cx, y, v == current, true, mx, my, () -> change(() -> set.accept(v))) + 3;
		}
		return y + ROW_H;
	}

	private int check(GuiGraphicsExtractor g, Font font, String id, String label, boolean on, int x, int y, int w, Runnable toggle) {
		Panels.sprite(g, on ? Kit.CHECKBOX_CHECKED : Kit.CHECKBOX, x, y + 1, 10, 10);
		String t = TextUtil.ellipsize(font, label, w - 14);
		form.hit(id, x, y, Math.min(w, 13 + font.width(t) + 4), 12, true, () -> change(toggle));
		g.text(font, t, x + 13, y + 2, UiBits.ink(), false);
		return y + 14;
	}

	/** The preview environment: 426x240 at scale 3, a boss bar, a speed icon, survival bars, three chat lines, the pill. */
	private static Env previewEnv(HudSettings s) {
		Env e = Env.of(PW, PH, 3).boss(1, 70).effects(1, 0).survival(true).chat(320, 28).withMinimap(s.topLeftOffset());
		Rect pill = HudLayout.place(e, Position.TOP_RIGHT, 92, 18).rect();
		return e.withPill(pill);
	}

	private int preview(GuiGraphicsExtractor g, Font font, HudSettings s, int x, int y, int w) {
		int y0 = y;
		g.text(font, "Preview", x, y + 2, UiStyle.CLAY_DARK, false);
		y += 13;
		Minecraft mc = Minecraft.getInstance();
		HudModel live = HudModel.live();
		boolean sample = !(live.hasData && (live.activeGoal() || live.needsPlayer()));
		HudModel m = sample ? HudModel.sample() : live;
		lastSample = sample;
		// the small screen: where it goes, next to what vanilla draws
		int tw = Math.min(w, 176);
		int th = tw * PH / PW;
		float f = tw / (float) PW;
		Env env = previewEnv(s);
		float k = HudLayout.scale(s.size(), 3);
		int[] wh = HudOverlay.measure(s.style(), m, font, (int) (HudLayout.maxWidth(env) / k));
		Placement p = wh[0] > 0 ? HudLayout.place(env, s.position(), HudLayout.scaled(wh[0], k), HudLayout.scaled(wh[1], k)) : null;
		Panels.sprite(g, Kit.PANEL_INSET, x, y, tw, th);
		g.fill(x + 2, y + 2, x + tw - 2, y + th - 2, 0xFF5E7C93); // sky
		g.fill(x + 2, y + th * 2 / 3, x + tw - 2, y + th - 2, 0xFF5B7F3A); // grass
		stub(g, x, y, f, HudLayout.bossBars(env), 0xFFB25BC4);
		stub(g, x, y, f, HudLayout.effects(env), 0xFF3B3836);
		stub(g, x, y, f, HudLayout.hotbar(env), 0xFF3B3836);
		stub(g, x, y, f, HudLayout.chat(env), 0x99000000);
		stub(g, x, y, f, env.pill(), 0xFF2B2927);
		if (s.position() == Position.TOP_LEFT && s.topLeftOffset() > 0) {
			stub(g, x, y, f, HudLayout.minimap(env), 0xFF8A7D63);
		}
		lastPreviewScreen = new Rect(x, y, tw, th);
		if (p != null && p.placed() && s.style() != Style.OFF) {
			Rect r = p.rect();
			int rx = x + Math.round(r.x() * f);
			int ry = y + Math.round(r.y() * f);
			int rw = Math.max(3, Math.round(r.w() * f));
			int rh = Math.max(3, Math.round(r.h() * f));
			g.fill(rx, ry, rx + rw, ry + rh, 0xFF1F1E1D);
			g.fill(rx, ry, rx + rw, ry + 1, UiStyle.BRASS);
			if (PillAccent.on(m)) {
				g.fill(rx, ry + 1, rx + 1, ry + rh, UiStyle.CLAY);
			}
			lastPreview = r;
		} else {
			lastPreview = Rect.NONE;
		}
		// the style at its real size, beside the small screen when there is room, else under it
		int ax = x + tw + 10;
		int ay = y;
		int aw = x + w - ax;
		if (aw < 150) {
			ax = x;
			ay = y + th + 6;
			aw = w;
		}
		int[] real = HudOverlay.measure(s.style(), m, font, (int) (aw / k));
		int used;
		if (s.style() == Style.OFF || real[0] <= 0) {
			String t = s.style() == Style.OFF ? "Off: nothing on screen. Toasts and J still work." : "Nothing to show right now.";
			int ly = ay;
			for (String line : TextUtil.wrapPlain(font, t, aw)) {
				g.text(font, line, ax, ly, UiBits.muted(), false);
				ly += 10;
			}
			used = ly - ay;
		} else {
			// an ink backdrop like the game behind it, so the ink style reads as it does in game
			int rw = HudLayout.scaled(real[0], k);
			int rh = HudLayout.scaled(real[1], k);
			g.fill(ax - 3, ay - 3, ax + rw + 3, ay + rh + 3, 0xFF6F8BA0);
			HudOverlay.drawPreview(g, font, s.style(), m, real[0], real[1], ax, ay, k);
			used = rh + 6;
			String cap = (sample ? "sample data" : "live") + " · " + s.style().label() + " · " + s.size().label() + (p != null && p.fallback()
				? " · no room there, shown top right" : "");
			g.text(font, TextUtil.ellipsize(font, cap, aw), ax, ay + used, UiBits.muted(), false);
			used += 12;
		}
		int h = ax == x ? th + 6 + used : Math.max(th, used);
		return y - y0 + h + 6;
	}

	private static void stub(GuiGraphicsExtractor g, int x, int y, float f, Rect r, int color) {
		if (r.empty()) {
			return;
		}
		int x0 = x + Math.round(r.x() * f);
		int y0 = y + Math.round(r.y() * f);
		int x1 = x + Math.round(r.right() * f);
		int y1 = y + Math.round(r.bottom() * f);
		g.fill(Math.max(x + 2, x0), Math.max(y + 2, y0), Math.max(x0 + 1, x1), Math.max(y0 + 1, y1), color);
	}

	/** {@code dev.hub.state settingsTab.hud}: the settings, where the preview put the overlay, and whether it used sample data. */
	JsonObject state() {
		JsonObject o = HudConfig.get().toJson();
		JsonObject pr = new JsonObject();
		pr.addProperty("sample", lastSample);
		pr.addProperty("placed", !lastPreview.empty());
		if (!lastPreview.empty()) {
			pr.addProperty("x", lastPreview.x());
			pr.addProperty("y", lastPreview.y());
			pr.addProperty("w", lastPreview.w());
			pr.addProperty("h", lastPreview.h());
		}
		pr.addProperty("screenW", lastPreviewScreen.w());
		o.add("preview", pr);
		return o;
	}

	/** The clay stripe rule of the pill styles (something needs the player). */
	private static final class PillAccent {
		static boolean on(HudModel m) {
			return m.waiting > 0 && !m.stale;
		}
	}
}
