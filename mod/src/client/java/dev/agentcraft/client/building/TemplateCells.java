package dev.agentcraft.client.building;

import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.GhostModel;
import dev.agentcraft.building.TemplateGrid;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * A blueprint's template as {@link GhostModel.Cells}: every cell the template writes (air included,
 * structure voids are not in a template), coloured by the block's map colour. Read once per loaded
 * blueprint entry ({@link TemplateGrid}), so a
 * {@code /agentcraft blueprints reload} (new entries) is picked up. Also the top-down preview of the
 * blueprint screen.
 */
final class TemplateCells {
	/** Map colour for blocks without one (glass, barriers...): a pale blue-grey, so windows still read. */
	static final int GLASS = 0xFFB8D4DC;
	private static final Map<Blueprints.Entry, GhostModel.Cells> CACHE = new WeakHashMap<>();

	private TemplateCells() {
	}

	/** The cells of a blueprint's current template, or null when it is not loaded. Client thread. */
	static GhostModel.@Nullable Cells of(String blueprintId) {
		Blueprints.Entry e = Blueprints.entry(blueprintId);
		if (e == null) {
			return null;
		}
		return CACHE.computeIfAbsent(e, TemplateCells::read);
	}

	private static GhostModel.Cells read(Blueprints.Entry e) {
		return TemplateGrid.of(e).cells(TemplateCells::color);
	}

	/** Opaque map colour of a block, 0 (alpha 0) for air. */
	static int color(BlockState state) {
		if (state.isAir()) {
			return 0;
		}
		MapColor c = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return c == null || c == MapColor.NONE ? GLASS : 0xFF000000 | c.col;
	}

	/**
	 * Top-down preview of a (rotated) ghost: for every (x, z) column the colour of the highest visible
	 * cell, shaded by its height (lower = darker), 0 for empty columns. Index {@code z * sizeX + x}.
	 */
	static int[] topDown(GhostModel m) {
		int sx = m.sizeX;
		int sz = m.sizeZ;
		int[] top = new int[sx * sz];
		int[] height = new int[sx * sz];
		java.util.Arrays.fill(height, -1);
		for (int i = 0; i < m.count(); i++) {
			if (!m.visible(i)) {
				continue;
			}
			int x = m.x(i);
			int y = m.y(i);
			int z = m.z(i);
			if (x < 0 || z < 0 || x >= sx || z >= sz) {
				continue;
			}
			int idx = z * sx + x;
			if (y > height[idx]) {
				height[idx] = y;
				top[idx] = m.argb(i);
			}
		}
		int sy = Math.max(1, m.sizeY - 1);
		for (int i = 0; i < top.length; i++) {
			if (height[i] >= 0) {
				top[i] = shade(top[i], 0.55f + 0.45f * height[i] / sy);
			}
		}
		return top;
	}

	static int shade(int argb, float f) {
		int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
		int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
		int b = Math.min(255, Math.round((argb & 0xFF) * f));
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
}
