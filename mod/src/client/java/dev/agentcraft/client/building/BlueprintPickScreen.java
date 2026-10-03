package dev.agentcraft.client.building;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.BlueprintTransform;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.GhostModel;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;

/**
 * Wizard step 2: pick a blueprint for the chosen repos: single blueprints for one repo, group
 * blueprints with at least as many wings as repos for several. Shows the name, kind, size and
 * description, and a top-down preview of the template (each column's highest block in its map colour,
 * shaded by height, turned so the entrance is at the bottom). Confirm closes the screen and starts
 * placement mode.
 */
final class BlueprintPickScreen extends WizardScreen {
	private static final int ROW = 22;
	private static final int PREVIEW = 116;

	private final List<String> repos;
	private final List<Blueprint> list = new ArrayList<>();
	private final Map<String, int[]> previews = new HashMap<>();
	private final Map<String, GhostModel> previewModels = new HashMap<>();
	private boolean initialized;
	private int selected;
	private int scroll;
	private int listY;
	private int listW;
	private int visibleRows;
	private @Nullable String error;

	BlueprintPickScreen(List<String> repos) {
		super("New building: blueprint");
		this.repos = List.copyOf(repos);
	}

	@Override
	protected void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		int n = repos.size();
		for (Blueprint bp : Blueprints.all()) {
			if (n <= 1 ? !bp.isGroup() : bp.isGroup() && bp.wings() >= n) {
				list.add(bp);
			}
		}
	}

	List<String> repos() {
		return repos;
	}

	@Nullable Blueprint current() {
		return selected >= 0 && selected < list.size() ? list.get(selected) : null;
	}

	/** Selects a blueprint by id (DevBridge); false when it is not offered for these repos. */
	boolean select(String id) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).id().equals(id)) {
				selected = i;
				return true;
			}
		}
		return false;
	}

	private void back() {
		minecraft.gui.setScreen(new RepoPickScreen(repos));
	}

	void place() {
		Blueprint bp = current();
		if (bp == null) {
			return;
		}
		try {
			BuildPlacement.start(bp.id(), repos);
		} catch (IllegalArgumentException e) {
			error = e.getMessage();
			Toasts.push(new Notify(NotifyLevel.WARN, e.getMessage(), null, System.currentTimeMillis()));
		}
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (e.isEscape()) {
			onClose();
			return true;
		}
		if (TextKeys.isEnter(e)) {
			place();
			return true;
		}
		if (k == InputConstants.KEY_BACKSPACE) {
			back();
			return true;
		}
		if (k == InputConstants.KEY_UP) {
			selected = Math.max(0, selected - 1);
			return true;
		}
		if (k == InputConstants.KEY_DOWN) {
			selected = Math.min(list.size() - 1, selected + 1);
			return true;
		}
		int digit = TextKeys.digit(e);
		if (digit > 0 && scroll + digit - 1 < list.size()) {
			selected = scroll + digit - 1;
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.y() >= listY && e.y() < listY + visibleRows * ROW && e.x() >= cx && e.x() < cx + listW) {
			int i = scroll + (int) ((e.y() - listY) / ROW);
			if (i < list.size()) {
				if (i == selected && doubleClick) {
					place();
				}
				selected = i;
			}
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(Math.max(0, list.size() - visibleRows), scroll + (scrollY > 0 ? -1 : 1)));
		return true;
	}

	private @Nullable GhostModel previewModel(Blueprint bp) {
		GhostModel m = previewModels.get(bp.id());
		if (m == null) {
			GhostModel.Cells c = TemplateCells.of(bp.id());
			if (c == null) {
				return null;
			}
			// entrance at the bottom of the preview: front turned to face south
			m = GhostModel.of(c, BlueprintTransform.turnsToFace(bp.front(), "south"));
			previewModels.put(bp.id(), m);
			previews.put(bp.id(), TemplateCells.topDown(m));
		}
		return m;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int muted = UiBits.muted();
		int ink = UiBits.ink();
		int maxBody = height - 110;
		visibleRows = Math.max(1, Math.min(Math.max(1, list.size()), (maxBody - 30) / ROW));
		int bodyH = 14 + Math.max(PREVIEW + 52, visibleRows * ROW) + 4;
		int y = frame(g, "2/2  Choose a blueprint", bodyH);
		String forWhat = repos.size() == 1 ? "Single building for " + repos.get(0) : "Group building for " + String.join(", ", repos);
		g.text(font, TextUtil.ellipsize(font, forWhat, cw), cx, y, muted, false);
		y += 14;
		listW = Math.max(120, cw - PREVIEW - 12);
		listY = y;
		if (list.isEmpty()) {
			String need = repos.size() == 1 ? "No single blueprint is loaded." : "No group blueprint with " + repos.size() + "+ wings is loaded.";
			g.text(font, need, cx, y + 2, UiBits.errorText(), false);
			g.text(font, "/agentcraft blueprints lists them; yours go in", cx, y + 14, muted, false);
			g.text(font, "<game dir>/agentcraft/blueprints.", cx, y + 24, muted, false);
		}
		if (selected < scroll) {
			scroll = selected;
		} else if (selected >= scroll + visibleRows) {
			scroll = selected - visibleRows + 1;
		}
		for (int r = 0; r < visibleRows && scroll + r < list.size(); r++) {
			int i = scroll + r;
			Blueprint bp = list.get(i);
			int ry = y + r * ROW;
			boolean hover = mouseX >= cx && mouseX < cx + listW && mouseY >= ry && mouseY < ry + ROW;
			if (i == selected) {
				g.fill(cx - 2, ry - 1, cx + listW, ry + ROW - 2, 0x30D97757);
			} else if (hover) {
				g.fill(cx - 2, ry - 1, cx + listW, ry + ROW - 2, 0x18000000);
			}
			g.text(font, TextUtil.ellipsize(font, (r < 9 ? (r + 1) + "  " : "") + bp.name(), listW - 4), cx, ry + 1, ink, false);
			String meta = (bp.isGroup() ? "group · " + bp.wings() + " wings" : "single") + " · " + size(bp);
			g.text(font, TextUtil.ellipsize(font, meta, listW - 4), cx + (r < 9 ? 12 : 0), ry + 11, muted, false);
		}
		Blueprint cur = current();
		int pxl = cx + cw - PREVIEW;
		if (cur != null) {
			Panels.inset(g, pxl, y, PREVIEW, PREVIEW);
			GhostModel m = previewModel(cur);
			int[] top = previews.get(cur.id());
			if (m != null && top != null) {
				drawPreview(g, m, top, pxl + 4, y + 4, PREVIEW - 8);
			}
			int dy = y + PREVIEW + 4;
			g.text(font, "entrance ↓  ·  ground row " + cur.groundY(), pxl, dy, muted, false);
			dy += 11;
			for (String line : TextUtil.wrapPlain(font, cur.description().isEmpty() ? cur.id() : cur.description(), PREVIEW)) {
				if (dy > y + PREVIEW + 52) {
					break;
				}
				g.text(font, line, pxl, dy, ink, false);
				dy += 10;
			}
		}
		if (error != null) {
			g.text(font, TextUtil.ellipsize(font, error, cw), cx, y + Math.max(PREVIEW + 52, visibleRows * ROW) - 8, UiBits.errorText(), false);
		}
		footer(g, mouseX, mouseY, new String[] {"Enter", "place", "Bksp", "back"}, btn("Place…", 72, true, cur == null, this::place),
			btn("‹ Back", 64, false, false, this::back));
	}

	private static String size(Blueprint bp) {
		return String.format(Locale.ROOT, "%d×%d×%d", bp.sizeX(), bp.sizeY(), bp.sizeZ());
	}

	/** The top-down colours scaled into a {@code box}-px square (integer pixel scale when it fits, centred). */
	private static void drawPreview(GuiGraphicsExtractor g, GhostModel m, int[] top, int x, int y, int box) {
		int sx = m.sizeX;
		int sz = m.sizeZ;
		double s = Math.min((double) box / sx, (double) box / sz);
		if (s >= 1) {
			s = Math.floor(s);
		}
		int w = (int) Math.round(sx * s);
		int h = (int) Math.round(sz * s);
		int ox = x + (box - w) / 2;
		int oy = y + (box - h) / 2;
		for (int z = 0; z < sz; z++) {
			int y0 = oy + (int) Math.round(z * s);
			int y1 = oy + (int) Math.round((z + 1) * s);
			for (int xx = 0; xx < sx; xx++) {
				int c = top[z * sx + xx];
				if ((c >>> 24) == 0) {
					continue;
				}
				int x0 = ox + (int) Math.round(xx * s);
				int x1 = ox + (int) Math.round((xx + 1) * s);
				if (x1 > x0 && y1 > y0) {
					g.fill(x0, y0, x1, y1, c);
				}
			}
		}
	}
}
