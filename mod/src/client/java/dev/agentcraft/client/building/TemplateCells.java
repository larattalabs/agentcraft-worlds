package dev.agentcraft.client.building;

import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.GhostModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * A blueprint's template as {@link GhostModel.Cells}: every cell the template writes (air included,
 * structure voids are not in a template), coloured by the block's map colour. Read once per loaded
 * blueprint entry through {@link StructureTemplate#save} (the palettes are private), so a
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
		Blueprint bp = e.blueprint();
		CompoundTag tag = e.template().save(new CompoundTag());
		HolderGetter<Block> blocks = BuiltInRegistries.BLOCK;
		ListTag paletteTag = tag.getListOrEmpty(StructureTemplate.PALETTE_TAG);
		if (paletteTag.isEmpty()) {
			// templates with random palettes store several; vanilla places one at random, the first is representative
			paletteTag = tag.getListOrEmpty(StructureTemplate.PALETTE_LIST_TAG).getListOrEmpty(0);
		}
		List<BlockState> palette = new ArrayList<>(paletteTag.size());
		for (int i = 0; i < paletteTag.size(); i++) {
			palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(i)));
		}
		ListTag blockList = tag.getListOrEmpty(StructureTemplate.BLOCKS_TAG);
		int n = blockList.size();
		int[] xyz = new int[n * 3];
		int[] argb = new int[n];
		int k = 0;
		for (int i = 0; i < n; i++) {
			CompoundTag b = blockList.getCompoundOrEmpty(i);
			ListTag pos = b.getListOrEmpty(StructureTemplate.BLOCK_TAG_POS);
			int si = b.getIntOr(StructureTemplate.BLOCK_TAG_STATE, -1);
			BlockState state = si >= 0 && si < palette.size() ? palette.get(si) : Blocks.AIR.defaultBlockState();
			if (state.is(Blocks.STRUCTURE_VOID)) {
				continue; // never written
			}
			xyz[k * 3] = pos.getIntOr(0, 0);
			xyz[k * 3 + 1] = pos.getIntOr(1, 0);
			xyz[k * 3 + 2] = pos.getIntOr(2, 0);
			argb[k] = color(state);
			k++;
		}
		if (k < n) {
			xyz = java.util.Arrays.copyOf(xyz, k * 3);
			argb = java.util.Arrays.copyOf(argb, k);
		}
		return new GhostModel.Cells(bp.sizeX(), bp.sizeY(), bp.sizeZ(), bp.groundY(), xyz, argb);
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
