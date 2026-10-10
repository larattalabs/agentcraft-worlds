package dev.agentcraft.client.hub;

import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A selectable list in an inset (the hub's list look, {@link HubScreen#drawList}) with its own scroll and
 * hit rects, so a pane can show two lists at once (goals and a goal's tasks). Rows are {@code rowH} tall.
 */
final class PaneList {
	@FunctionalInterface
	interface Row {
		/** Draws row {@code i} at (x, y) in width {@code w}; returns its id. */
		String draw(int i, int x, int y, int w);
	}

	private final int rowH;
	private int scroll;
	private int x;
	private int y;
	private int w;
	private int h;
	private final List<int[]> rects = new ArrayList<>();
	private final List<String> ids = new ArrayList<>();

	PaneList(int rowH) {
		this.rowH = rowH;
	}

	void reset() {
		scroll = 0;
	}

	void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int count, int selected, int mx, int my, Row row) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		rects.clear();
		ids.clear();
		Panels.inset(g, x, y, w, h);
		int rows = Math.max(1, (h - 6) / rowH);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, count - rows)));
		if (selected >= 0 && selected < scroll) {
			scroll = selected;
		} else if (selected >= scroll + rows) {
			scroll = selected - rows + 1;
		}
		for (int r = 0; r < rows && scroll + r < count; r++) {
			int i = scroll + r;
			int ry = y + 3 + r * rowH;
			boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
			if (i == selected) {
				g.fill(x + 2, ry, x + w - 2, ry + rowH - 1, 0x30D97757);
			} else if (hover) {
				g.fill(x + 2, ry, x + w - 2, ry + rowH - 1, 0x18000000);
			}
			ids.add(row.draw(i, x + 6, ry + 2, w - 12 - (count > rows ? 5 : 0)));
			rects.add(new int[] {x, ry, w, rowH});
		}
		if (count > rows) {
			int trackH = h - 6;
			int thumbH = Math.max(8, trackH * rows / count);
			int ty = y + 3 + (trackH - thumbH) * scroll / Math.max(1, count - rows);
			Panels.sprite(g, Kit.SCROLL_TRACK, x + w - 7, y + 3, 4, trackH);
			Panels.sprite(g, Kit.SCROLL_THUMB, x + w - 7, ty, 4, thumbH);
		}
	}

	/** The id of the row under (mx, my) drawn last frame, or null. */
	String hit(double mx, double my) {
		for (int i = 0; i < rects.size(); i++) {
			int[] r = rects.get(i);
			if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
				return ids.get(i);
			}
		}
		return null;
	}

	boolean scroll(double mx, double my, int d) {
		if (w > 0 && mx >= x && mx < x + w && my >= y && my < y + h) {
			scroll = Math.max(0, scroll + d);
			return true;
		}
		return false;
	}

	/** Called when the list is not drawn this frame (no clicks or wheel on it). */
	void hide() {
		rects.clear();
		ids.clear();
		w = 0;
	}

	/** How many rows fit in height {@code h}. */
	int rowsFor(int h) {
		return Math.max(1, (h - 6) / rowH);
	}
}
