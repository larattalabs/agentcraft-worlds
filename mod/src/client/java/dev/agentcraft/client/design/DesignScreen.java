package dev.agentcraft.client.design;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.DesignSpec;
import dev.agentcraft.client.console.TextFieldView;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.console.TextModel;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The design form (docs/HUB.md "Generated buildings": the hub's Buildings -> Design new…, {@code /hub
 * design}): two columns on the hub's paper. Left: For (one repo / a group of N wings), Style (with a
 * line about the selected one), Materials, Features, Size (S / M / L or "Fit a plot…"). Right: Remix,
 * Name, Notes, where the files go. Problems show in red next to the field they concern (the Foreman's
 * own limits, {@link DesignSpec#validate}); "Design it" sends {@code design.request} and opens the
 * hub's Designs list. Tab moves between Name and Notes, Ctrl+Enter sends (plain Enter never does: the
 * Enter that ends plot marking reopens this form, and a held key must not send a request), Esc goes
 * back. On a short window (GUI scale 4 at 1080p) the hint lines are dropped. Not pausing.
 */
public final class DesignScreen extends Screen {
	private static final int MAX_W = 600;
	private static final int MAX_H = 380;
	private static final int CHIP_H = 14;

	enum Focus {
		NONE, NAME, NOTES
	}

	private record Hit(String id, String label, int x, int y, int w, int h, boolean enabled, boolean on, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final DesignForm form;
	private final @Nullable Screen back;
	private Focus focus = Focus.NONE;
	private final TextFieldView nameView = new TextFieldView();
	private final TextFieldView notesView = new TextFieldView();
	private final List<Hit> hits = new ArrayList<>();
	private int nameX;
	private int nameY;
	private int nameW;
	private int notesX;
	private int notesY;
	private int notesW;
	private int notesLines = 4;
	/** Last frame: the left column's needed and available height, and whether hint lines were dropped. */
	private int leftNeeded;
	private int leftAvailable;
	private boolean compact;

	DesignScreen(DesignForm form, @Nullable Screen back) {
		super(Component.literal("Design a new building"));
		this.form = form;
		this.back = back;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		setFocus(Focus.NONE);
		minecraft.gui.setScreen(back);
	}

	@Override
	public void removed() {
		if (focus != Focus.NONE && minecraft != null) {
			minecraft.onTextInputFocusChange(this, false);
		}
		focus = Focus.NONE;
		super.removed();
	}

	String focus() {
		return focus.name().toLowerCase(Locale.ROOT);
	}

	private void setFocus(Focus f) {
		if ((f != Focus.NONE) != (focus != Focus.NONE) && minecraft != null) {
			minecraft.onTextInputFocusChange(this, f != Focus.NONE);
		}
		focus = f;
		TextModel m = model();
		if (m != null) {
			m.touch();
		}
	}

	private @Nullable TextModel model() {
		return switch (focus) {
			case NAME -> form.name;
			case NOTES -> form.notes;
			default -> null;
		};
	}

	// ------------------------------------------------------------------ actions

	/** "Design it": sends the request; on success the hub's Designs list opens with it selected. */
	CompletableFuture<DesignFeature.Sent> submit() {
		return DesignFeature.submit().thenApply(sent -> {
			if (sent.designId() != null && minecraft != null && minecraft.gui.screen() == this) {
				setFocus(Focus.NONE);
				DesignFeature.showInHub(sent.designId());
			}
			return sent;
		});
	}

	private void fitPlot() {
		setFocus(Focus.NONE);
		DesignFeature.startPlot();
	}

	private void cycleRemix(int d) {
		List<String> ids = new ArrayList<>();
		ids.add(null);
		for (Blueprint b : Blueprints.all()) {
			ids.add(b.id());
		}
		int i = ids.indexOf(form.remix);
		form.remix = ids.get(Math.floorMod((i < 0 ? 0 : i) + d, ids.size()));
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (e.isEscape()) {
			onClose();
			return true;
		}
		if (k == InputConstants.KEY_TAB) {
			Focus[] all = Focus.values();
			setFocus(all[Math.floorMod(focus.ordinal() + (e.hasShiftDown() ? -1 : 1), all.length)]);
			return true;
		}
		if (TextKeys.isEnter(e)) {
			if (e.hasControlDown()) {
				submit();
			} else if (focus == Focus.NAME) {
				setFocus(Focus.NOTES);
			} else if (focus == Focus.NOTES) {
				form.notes.insert("\n");
			}
			return true;
		}
		TextModel m = model();
		if (m != null) {
			if (focus == Focus.NOTES && (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN)) {
				form.notes.vertical(font, TextFieldView.wrapWidth(font, form.notes, notesW, notesStyle()), k == InputConstants.KEY_UP ? -1 : 1,
					e.hasShiftDown());
				return true;
			}
			if (TextKeys.handle(e, m)) {
				form.sendError = null;
				return true;
			}
			return true; // a focused field swallows the rest (no hub/inventory keys while typing)
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		TextModel m = model();
		if (m != null && e.codepoint() >= 32) {
			m.insert(e.codepointAsString());
			form.sendError = null;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		for (Hit h : List.copyOf(hits)) {
			if (h.contains(e.x(), e.y())) {
				if (h.enabled()) {
					form.sendError = null;
					h.action().run();
				}
				return true;
			}
		}
		int ni = nameView.hit(font, form.name, nameX, nameY, nameW, nameStyle(), e.x(), e.y());
		if (ni >= 0) {
			setFocus(Focus.NAME);
			form.name.moveTo(ni, false);
			return true;
		}
		int ti = notesView.hit(font, form.notes, notesX, notesY, notesW, notesStyle(), e.x(), e.y());
		if (ti >= 0) {
			setFocus(Focus.NOTES);
			form.notes.moveTo(ti, false);
			return true;
		}
		setFocus(Focus.NONE);
		return super.mouseClicked(e, doubleClick);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractBlurredBackground(g);
		g.fillGradient(0, 0, width, height, UiStyle.withAlpha(UiStyle.INK, 50), UiStyle.withAlpha(UiStyle.INK, 100));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		hits.clear();
		String out = DesignFeature.outDir();
		Map<String, String> errors = form.errors(out);
		int pw = Math.min(MAX_W, width - 16);
		int ph = Math.min(MAX_H, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		Kit.Padding pad = Kit.padding("panel_paper");
		int cx = px + pad.left();
		int cy = py + pad.top();
		int cw = pw - pad.left() - pad.right();
		int bottom = py + ph - pad.bottom();
		Panels.panel(g, px, py, pw, ph);
		Panels.header(g, font, "Design a new building", cx - 2, cy - 2, cw + 4);
		if (!Foreman.connected()) {
			String off = "Foreman offline";
			int ow = UiBits.dotPillWidth(font, off);
			UiBits.dotPill(g, font, "error", off, cx + cw - ow, cy - 1, UiBits.errorText());
		}
		int top = cy + 18;
		int footerY = bottom - 20;
		int colW = (cw - 14) / 2;
		int lx = cx;
		int rx = cx + colW + 14;
		leftAvailable = footerY - 16 - top;
		compact = leftAvailable < 236;
		leftNeeded = drawLeft(g, lx, top, colW, errors, mouseX, mouseY) - top;
		drawRight(g, rx, top, colW, footerY - 16 - top, errors, mouseX, mouseY);
		// status line above the footer
		String status;
		int sc;
		if (DesignFeature.sending()) {
			status = "Sending…";
			sc = UiBits.muted();
		} else if (form.sendError != null) {
			status = form.sendError;
			sc = UiBits.errorText();
		} else if (!errors.isEmpty()) {
			status = errors.size() == 1 ? "1 field needs a fix (in red)" : errors.size() + " fields need a fix (in red)";
			sc = UiBits.errorText();
		} else if (!Foreman.connected()) {
			status = "The Foreman makes designs: start it to send this.";
			sc = UiBits.muted();
		} else {
			status = "Ready: the Foreman designs it in the background; the Designs list shows progress.";
			sc = UiBits.okText();
		}
		g.text(font, TextUtil.ellipsize(font, status, cw), cx, footerY - 13, sc, false);
		// footer: hints + buttons (right to left)
		int bx = cx + cw;
		String go = "Design it";
		int gw = UiBits.buttonWidth(font, go, 0);
		bx -= gw;
		button(g, "submit", go, bx, footerY, gw, true, !DesignFeature.sending(), mouseX, mouseY, this::submit);
		String cancel = back != null ? "Back" : "Cancel";
		int cwid = UiBits.buttonWidth(font, cancel, 0);
		bx -= cwid + 6;
		button(g, "cancel", cancel, bx, footerY, cwid, false, true, mouseX, mouseY, this::onClose);
		String[] hints = {"Tab", "next field", "Ctrl+Enter", "design it", "Esc", "back"};
		if (UiBits.hintsWidth(font, hints) <= bx - cx - 6) {
			UiBits.hints(g, font, cx, footerY + 4, false, hints);
		}
	}

	private void button(GuiGraphicsExtractor g, String id, String label, int x, int y, int w, boolean primary, boolean enabled, int mx, int my,
		Runnable action) {
		Hit h = new Hit(id, label, x, y, w, 20, enabled, primary, action);
		hits.add(h);
		UiBits.ButtonState st = !enabled ? UiBits.ButtonState.DISABLED : h.contains(mx, my) ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL;
		UiBits.button(g, font, label, 0, x, y, w, primary, st, false);
	}

	/** A field label, with the field's problem (if any) in red after it; returns the y below. */
	private int label(GuiGraphicsExtractor g, String text, Map<String, String> errors, int x, int y, int w, String... fields) {
		g.text(font, text, x, y, UiStyle.CLAY_DARK, false);
		for (String f : fields) {
			String err = errors.get(f);
			if (err != null) {
				int ex = x + font.width(text) + 6;
				g.text(font, TextUtil.ellipsize(font, err, Math.max(10, x + w - ex)), ex, y, UiBits.errorText(), false);
				break;
			}
		}
		return y + 11;
	}

	private int chipWidth(String label) {
		return font.width(label) + 12;
	}

	private int chip(GuiGraphicsExtractor g, String id, String label, int x, int y, boolean on, boolean enabled, int mx, int my, Runnable action) {
		int w = chipWidth(label);
		Hit h = new Hit(id, label, x, y, w, CHIP_H, enabled, on, action);
		hits.add(h);
		Panels.sprite(g, on ? Kit.TAB_ACTIVE : Kit.TAB_INACTIVE, x, y, w, CHIP_H, enabled ? 0xFFFFFFFF : 0x90FFFFFF);
		if (enabled && !on && h.contains(mx, my)) {
			g.fill(x + 1, y + 1, x + w - 1, y + CHIP_H - 1, 0x14000000);
		}
		int color = !enabled ? UiStyle.color("paper.disabled", 0xFFA39B8E) : on ? UiBits.ink() : UiBits.muted();
		g.text(font, label, x + 6, y + 3, color, false);
		return w;
	}

	/** Chips that wrap within {@code w}; returns the y below the last row. */
	private int chips(GuiGraphicsExtractor g, String prefix, List<DesignSpec.Choice> choices, String selected, int x, int y, int w, int mx, int my,
		java.util.function.Consumer<String> pick) {
		int cx = x;
		for (DesignSpec.Choice c : choices) {
			int cwid = chipWidth(c.label());
			if (cx > x && cx + cwid > x + w) {
				cx = x;
				y += CHIP_H + 3;
			}
			cx += chip(g, prefix + c.id(), c.label(), cx, y, c.id().equals(selected), true, mx, my, () -> pick.accept(c.id())) + 3;
		}
		return y + CHIP_H + 5;
	}

	/** Draws the left column; returns the y below it. */
	private int drawLeft(GuiGraphicsExtractor g, int x, int y, int w, Map<String, String> errors, int mx, int my) {
		int muted = UiBits.muted();
		// For
		y = label(g, "For", errors, x, y, w, "kind", "wings");
		int cx = x;
		cx += chip(g, "kind:single", "One repo", cx, y, !form.group(), true, mx, my, () -> form.kind = DesignSpec.SINGLE) + 3;
		cx += chip(g, "kind:group", "A group (wings)", cx, y, form.group(), true, mx, my, () -> form.kind = DesignSpec.GROUP) + 8;
		if (form.group()) {
			cx += chip(g, "wings:-", "−", cx, y, false, form.groupWings > DesignSpec.MIN_WINGS_GROUP, mx, my,
				() -> form.groupWings = Math.max(DesignSpec.MIN_WINGS_GROUP, form.groupWings - 1)) + 4;
			String n = form.groupWings + " wings";
			g.text(font, n, cx, y + 3, UiBits.ink(), false);
			cx += font.width(n) + 4;
			chip(g, "wings:+", "+", cx, y, false, form.groupWings < DesignSpec.MAX_WINGS, mx, my,
				() -> form.groupWings = Math.min(DesignSpec.MAX_WINGS, form.groupWings + 1));
		}
		y += CHIP_H + 4;
		if (!compact) {
			g.text(font, TextUtil.ellipsize(font, form.group() ? "One wing per repo; you pick the repos when you place it."
				: "One repo's office; you pick the repo when you place it.", w), x, y, muted, false);
			y += 14;
		}
		// Style
		y = label(g, "Style", errors, x, y, w, "style");
		y = chips(g, "style:", DesignSpec.STYLES, form.style, x, y, w, mx, my, id -> form.style = id);
		DesignSpec.Choice st = DesignSpec.style(form.style);
		if (st != null) {
			g.text(font, TextUtil.ellipsize(font, st.label() + ": " + st.description(), w), x, y - 1, muted, false);
			y += compact ? 10 : 12;
		}
		y += compact ? 0 : 2;
		// Materials
		y = label(g, "Materials", errors, x, y, w, "materials");
		y = chips(g, "materials:", DesignSpec.MATERIALS, form.materials, x, y, w, mx, my, id -> form.materials = id);
		y += compact ? 0 : 2;
		// Features
		y = label(g, "Features", errors, x, y, w, "features");
		int fx = x;
		for (DesignSpec.Choice c : DesignSpec.FEATURES) {
			int fw = 12 + font.width(c.label()) + 10;
			if (fx > x && fx + fw > x + w) {
				fx = x;
				y += 14;
			}
			boolean on = form.features.contains(c.id());
			Hit h = new Hit("feature:" + c.id(), c.label(), fx, y, fw, 12, true, on, () -> {
				if (!form.features.remove(c.id())) {
					form.features.add(c.id());
				}
			});
			hits.add(h);
			Panels.sprite(g, on ? Kit.CHECKBOX_CHECKED : Kit.CHECKBOX, fx, y + 1, 10, 10);
			g.text(font, c.label(), fx + 13, y + 2, h.contains(mx, my) ? UiBits.ink() : on ? UiBits.ink() : muted, false);
			fx += fw;
		}
		y += compact ? 15 : 18;
		// Size
		y = label(g, "Size", errors, x, y, w, "maxSize");
		cx = x;
		for (String s : DesignSpec.SIZES) {
			cx += chip(g, "size:" + s, s, cx, y, s.equals(form.size), true, mx, my, () -> form.size = s) + 3;
		}
		cx += 5;
		String fit = form.plot != null ? "Plot ✓ (re-mark…)" : "Fit a plot…";
		if (form.plot != null) {
			cx += chip(g, "size:plot", "Plot", cx, y, DesignForm.PLOT.equals(form.size), true, mx, my, () -> form.size = DesignForm.PLOT) + 3;
			fit = "Re-mark…";
		}
		chip(g, "fit_plot", fit, cx, y, false, minecraft.player != null, mx, my, this::fitPlot);
		y += CHIP_H + 4;
		int[] m = form.maxSize();
		String lim = "limit " + m[0] + " × " + m[1] + " × " + m[2] + " (wide × high × deep)" + (DesignForm.CUSTOM.equals(form.size) ? ", set exactly"
			: "");
		g.text(font, TextUtil.ellipsize(font, lim, w), x, y, UiBits.ink(), false);
		y += 10;
		DesignSpec.Plot p = form.plot;
		if (p != null && DesignForm.PLOT.equals(form.size)) {
			String pl = "plot " + p.dx() + " × " + p.dz() + " at " + p.minX() + ", " + p.y() + ", " + p.minZ() + " · entrance " + p.front();
			g.text(font, TextUtil.ellipsize(font, pl, w), x, y, muted, false);
			y += 10;
		} else if (!compact) {
			g.text(font, TextUtil.ellipsize(font, form.group() ? "Group sizes grow with the wings."
				: "S fits a small team, M the workshop, L a big office.", w), x, y, muted, false);
			y += 10;
		}
		return y;
	}

	/** DevBridge: the left column's needed vs available height last frame (overflow = it ran into the footer). */
	JsonObject layoutJson() {
		JsonObject o = new JsonObject();
		o.addProperty("guiWidth", width);
		o.addProperty("guiHeight", height);
		o.addProperty("leftNeeded", leftNeeded);
		o.addProperty("leftAvailable", leftAvailable);
		o.addProperty("compact", compact);
		o.addProperty("overflow", leftNeeded > leftAvailable);
		return o;
	}

	private TextFieldView.Style nameStyle() {
		return new TextFieldView.Style(null, 0, "optional, e.g. Lighthouse office", null, form.name.length() + "/" + DesignSpec.MAX_NAME,
			UiBits.muted(), 1);
	}

	private TextFieldView.Style notesStyle() {
		return new TextFieldView.Style(null, 0, "rooms, mood, anything the designer should know", null, null, 0, notesLines);
	}

	private void drawRight(GuiGraphicsExtractor g, int x, int y, int w, int h, Map<String, String> errors, int mx, int my) {
		int muted = UiBits.muted();
		int bottom = y + h;
		// Remix
		y = label(g, "Remix (optional)", errors, x, y, w, "remix");
		int cx = x;
		cx += chip(g, "remix:prev", "‹", cx, y, false, true, mx, my, () -> cycleRemix(-1)) + 3;
		Blueprint rb = form.remix == null ? null : Blueprints.get(form.remix);
		String cur = form.remix == null ? "none: start from scratch" : rb != null ? rb.name() + "  (" + form.remix + ")" : form.remix + " (not loaded)";
		int midW = w - 2 * (chipWidth("‹") + 3);
		Panels.inset(g, cx, y, midW, CHIP_H);
		g.text(font, TextUtil.ellipsize(font, cur, midW - 10), cx + 5, y + 3, form.remix == null ? muted : UiBits.ink(), false);
		if (form.remix != null) {
			hits.add(new Hit("remix:none", "none", cx, y, midW, CHIP_H, true, false, () -> form.remix = null));
		}
		cx += midW + 3;
		chip(g, "remix:next", "›", cx, y, false, true, mx, my, () -> cycleRemix(1));
		y += CHIP_H + 8;
		// Name
		y = label(g, "Name (optional)", errors, x, y, w, "name");
		nameX = x;
		nameY = y;
		nameW = w;
		int nh = nameView.draw(g, font, form.name, x, y, w, focus == Focus.NAME, nameStyle());
		y += nh + 2;
		g.text(font, TextUtil.ellipsize(font, "Names the blueprint: gen_" + slug(form.name.value(), form.style), w), x, y, muted, false);
		y += 14;
		// Notes
		y = label(g, "Notes", errors, x, y, w, "notes");
		int room = bottom - y - 14;
		notesLines = Math.max(2, Math.min(10, (room - TextFieldView.BASE_H) / TextFieldView.LINE + 1));
		notesX = x;
		notesY = y;
		notesW = w;
		int th = notesView.draw(g, font, form.notes, x, y, w, focus == Focus.NOTES, notesStyle());
		y += th + 3;
		String err = errors.get("outDir");
		g.text(font, TextUtil.ellipsize(font, err != null ? "Folder " + err : "Saved to " + DesignFeature.outDir(), w), x, y,
			err != null ? UiBits.errorText() : muted, false);
	}

	/** The Foreman's id rule (foreman/src/designs.ts baseBlueprintId): gen_<slug of the name, else of the style>; a suffix is added when taken. */
	static String slug(String name, String style) {
		String s = slugify(name);
		if (s.isEmpty()) {
			s = style.equals("custom") ? "building" : slugify(style);
		}
		return s.isEmpty() ? "building" : s;
	}

	private static String slugify(String s) {
		String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
			.replaceAll("^_+|_+$", "");
		n = n.length() > 32 ? n.substring(0, 32) : n;
		return n.replaceAll("_+$", "");
	}

	// ------------------------------------------------------------------ DevBridge

	JsonArray buttonsJson() {
		JsonArray a = new JsonArray();
		for (Hit h : hits) {
			JsonObject j = new JsonObject();
			j.addProperty("id", h.id());
			j.addProperty("label", h.label());
			j.addProperty("state", !h.enabled() ? "disabled" : h.on() ? "on" : "normal");
			a.add(j);
		}
		return a;
	}
}
