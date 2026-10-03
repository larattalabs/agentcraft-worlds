package dev.agentcraft.client.building;

import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.BlueprintTransform;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.GhostModel;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * The top-down plan of a blueprint (each column's highest block in its map colour, shaded by height,
 * turned so the entrance is at the bottom), shared by the wizard's blueprint step and the hub's
 * blueprint browser. Cached per loaded {@link Blueprints.Entry}, so a reload is picked up. Client thread.
 */
public final class BlueprintPreview {
	private record Plan(int sizeX, int sizeZ, int[] colours) {
	}

	private static final Map<Blueprints.Entry, Plan> CACHE = new WeakHashMap<>();

	private BlueprintPreview() {
	}

	private static @Nullable Plan plan(String blueprintId) {
		Blueprints.Entry e = Blueprints.entry(blueprintId);
		if (e == null) {
			return null;
		}
		Plan p = CACHE.get(e);
		if (p == null) {
			GhostModel.Cells c = TemplateCells.of(blueprintId);
			if (c == null) {
				return null;
			}
			Blueprint bp = e.blueprint();
			// entrance at the bottom of the preview: front turned to face south
			GhostModel m = GhostModel.of(c, BlueprintTransform.turnsToFace(bp.front(), "south"));
			p = new Plan(m.sizeX, m.sizeZ, TemplateCells.topDown(m));
			CACHE.put(e, p);
		}
		return p;
	}

	/** Whether a plan can be drawn (the blueprint and its template are loaded). */
	public static boolean available(String blueprintId) {
		return plan(blueprintId) != null;
	}

	/**
	 * Draws the plan scaled into a {@code box}-px square at (x, y): integer pixel scale when it fits,
	 * centred. Returns false (nothing drawn) when the blueprint is not loaded.
	 */
	public static boolean draw(GuiGraphicsExtractor g, String blueprintId, int x, int y, int box) {
		Plan p = plan(blueprintId);
		if (p == null) {
			return false;
		}
		int sx = p.sizeX();
		int sz = p.sizeZ();
		int[] top = p.colours();
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
		return true;
	}
}
