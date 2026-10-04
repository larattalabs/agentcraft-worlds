package dev.agentcraft.building;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.ToIntFunction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

/**
 * Every cell a blueprint's template writes (air included; structure voids are never written), read once per
 * loaded {@link Blueprints.Entry} through {@link StructureTemplate#save} (the palettes are private). Shared by the
 * server ({@link Buildings}: what the template writes, where its own block entities are, what a standing building
 * should look like) and the client ghost ({@code TemplateCells} colours it). Template-local, unrotated; use
 * {@link #ghost} for a rotation. Any thread.
 *
 * @param xyz three ints per cell (x, y, z)
 * @param states the block state of each cell
 * @param blockEntity whether the template stores block-entity NBT for the cell (a block the template itself
 *                    brings with a block entity: stations, lecterns, chests...)
 * @param fingerprint {@link #fingerprint(int[], String[], boolean[])} of the cells: what a placed building pins
 *                    ({@link Building.Pin#template()}), so a blueprint regenerated under the same id is noticed
 */
public record TemplateGrid(Blueprint blueprint, int[] xyz, BlockState[] states, boolean[] blockEntity, String fingerprint) {
	private static final Map<Blueprints.Entry, TemplateGrid> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	public int count() {
		return states.length;
	}

	/** The grid of a loaded blueprint entry (cached per entry, so a reload is picked up). */
	public static TemplateGrid of(Blueprints.Entry e) {
		TemplateGrid g = CACHE.get(e);
		if (g == null) {
			g = read(e.blueprint(), e.template());
			CACHE.put(e, g);
		}
		return g;
	}

	/** The grid of a loaded blueprint by id, or null. */
	public static @Nullable TemplateGrid of(String blueprintId) {
		Blueprints.Entry e = Blueprints.entry(blueprintId);
		return e == null ? null : of(e);
	}

	static TemplateGrid read(Blueprint bp, StructureTemplate template) {
		CompoundTag tag = template.save(new CompoundTag());
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
		BlockState[] states = new BlockState[n];
		boolean[] be = new boolean[n];
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
			states[k] = state;
			be[k] = b.contains(StructureTemplate.BLOCK_TAG_NBT) || state.hasBlockEntity();
			k++;
		}
		if (k < n) {
			xyz = java.util.Arrays.copyOf(xyz, k * 3);
			states = java.util.Arrays.copyOf(states, k);
			be = java.util.Arrays.copyOf(be, k);
		}
		String[] names = new String[states.length];
		for (int i = 0; i < names.length; i++) {
			names[i] = states[i].toString();
		}
		return new TemplateGrid(bp, xyz, states, be, fingerprint(xyz, names, be));
	}

	/**
	 * A stable fingerprint of a template's cells (position, block state, block-entity flag), independent of the order
	 * the cells are stored in: 16 hex chars of SHA-256 over the sorted cells. Pure.
	 */
	public static String fingerprint(int[] xyz, String[] states, boolean[] blockEntity) {
		List<String> cells = new ArrayList<>(states.length);
		for (int i = 0; i < states.length; i++) {
			cells.add(xyz[i * 3] + "," + xyz[i * 3 + 1] + "," + xyz[i * 3 + 2] + "=" + states[i] + (blockEntity[i] ? "+be" : ""));
		}
		Collections.sort(cells);
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			for (String c : cells) {
				md.update(c.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				md.update((byte) '\n');
			}
			return java.util.HexFormat.of().formatHex(md.digest(), 0, 8);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The template's own block entities after {@code turns}, as offsets from the rotated box's minimum corner (x,y,z triples). */
	public List<Integer> blockEntityOffsets(int turns) {
		GhostModel m = ghost(turns);
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < m.count(); i++) {
			if (blockEntity[i]) {
				out.add(m.x(i));
				out.add(m.y(i));
				out.add(m.z(i));
			}
		}
		return out;
	}

	/** As ghost cells: {@code colour} per state (alpha 0 = air: written, not drawn). */
	public GhostModel.Cells cells(ToIntFunction<BlockState> colour) {
		int[] argb = new int[count()];
		for (int i = 0; i < argb.length; i++) {
			argb[i] = colour.applyAsInt(states[i]);
		}
		return new GhostModel.Cells(blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ(), blueprint.groundY(), xyz.clone(), argb);
	}

	/** The rotated model with plain colours (non-air opaque white): what the server needs (no drawing). */
	public GhostModel ghost(int turns) {
		return GhostModel.of(cells(s -> s.isAir() ? 0 : 0xFFFFFFFF), turns);
	}
}
