package dev.agentcraft.building;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * How a building meets the terrain (docs/BUILDINGS.md "Terrain fit", contract C4), shared by the server
 * ({@link Buildings#place}) and the wizard's ghost so both agree to the block:
 * <ul>
 * <li><b>foundation</b>: below every floor-row cell of the footprint (template row {@code groundY - 1}), the cells
 * that are air, fluid or replaceable after the template is placed are filled downwards with the blueprint's
 * {@code foundationBlock} until solid ground, at most {@link #MAX_FILL} blocks; block entities stop it;</li>
 * <li><b>cleared</b>: natural terrain (dirt, grass, stone, sand, gravel, plants...) and trees (logs, leaves) at or above
 * the ground row inside the box that the template does not write are cleared to air, so a slope does not bury the walls
 * and a canopy does not fill the porch;</li>
 * <li><b>fluids</b>: water and lava in the box grown by one block sideways and one below (and in the fill) are
 * counted: lava refuses the placement, water is a warning.</li>
 * </ul>
 * The snapshot covers the box extended down to the lowest fill cell, so Remove restores all of it.
 * {@link #plan} is pure (the world comes in through {@link World}) and unit-tested; {@link #flags} classifies a
 * real block for it.
 */
public final class TerrainFit {
	/** Deepest foundation fill below the floor row. */
	public static final int MAX_FILL = 12;
	/** Fluid / fill cells kept for drawing (counts stay exact). */
	public static final int MAX_DRAWN = 4000;

	/** Air, a fluid or a replaceable block (grass, snow layer...): the foundation fills it. */
	public static final int FILLABLE = 1;
	public static final int WATER = 2;
	public static final int LAVA = 4;
	/** Natural terrain or plants: cleared when above the ground row inside the box. */
	public static final int NATURAL = 8;
	/** A block entity: never filled over or cleared. */
	public static final int BLOCK_ENTITY = 16;
	/** A log or leaves: solid, but not the ground ({@link Approach} looks through trees for the terrain). */
	public static final int TREE = 32;

	/** The world under a placement: {@link #flags} bits of a world cell. */
	@FunctionalInterface
	public interface World {
		int flags(int x, int y, int z);
	}

	/**
	 * The terrain work of one placement. Cells are world (x, y, z) triples.
	 *
	 * @param fill foundation cells, top to bottom per column
	 * @param clear natural terrain cleared above ground inside the box
	 * @param water / lava cells found (at most {@link #MAX_DRAWN} kept, counts exact)
	 * @param minY the lowest y the placement touches (the snapshot box reaches one row lower, {@code Buildings.snapshotBox})
	 */
	public record Plan(int[] fill, int[] clear, int[] water, int waterCount, int[] lava, int lavaCount, int minY) {
		public int fillCount() {
			return fill.length / 3;
		}

		public int clearCount() {
			return clear.length / 3;
		}
	}

	private TerrainFit() {
	}

	/**
	 * The plan for {@code m} with its rotated box minimum at (ox, oy, oz). Cells the template writes count as
	 * what it writes (a template's own foundation rows stop the fill; its air does not), others as the world is.
	 */
	public static Plan plan(GhostModel m, int ox, int oy, int oz, World w) {
		int sx = m.sizeX;
		int sy = m.sizeY;
		int sz = m.sizeZ;
		// what the template writes per rotated cell: 0 = nothing, 1 = air, 2 = a block
		byte[] writes = new byte[sx * sy * sz];
		for (int i = 0; i < m.count(); i++) {
			int x = m.x(i);
			int y = m.y(i);
			int z = m.z(i);
			if (x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz) {
				writes[(y * sz + z) * sx + x] = (byte) (m.visible(i) ? 2 : 1);
			}
		}
		Cells fill = new Cells();
		Cells water = new Cells();
		Cells lava = new Cells();
		int floor = m.groundY - 1;
		int minY = oy;
		if (floor >= 0 && floor < sy) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					if (writes[(floor * sz + z) * sx + x] != 2) {
						continue; // not a floor cell of the footprint
					}
					for (int d = 1; d <= MAX_FILL; d++) {
						int ly = floor - d;
						int wy = oy + ly;
						int wrote = ly >= 0 ? writes[(ly * sz + z) * sx + x] : 0;
						if (wrote == 2) {
							break; // the template's own foundation
						}
						if (wrote == 1) {
							fill.add(ox + x, wy, oz + z); // the template writes air there: filled after it
							continue;
						}
						int f = w.flags(ox + x, wy, oz + z);
						boolean below = wy < oy - 1; // the fluid scan below covers the box and the row under it
						if ((f & LAVA) != 0 && below) {
							lava.add(ox + x, wy, oz + z);
						}
						if ((f & FILLABLE) == 0 || (f & BLOCK_ENTITY) != 0) {
							break; // solid ground
						}
						if ((f & WATER) != 0 && below) {
							water.add(ox + x, wy, oz + z);
						}
						fill.add(ox + x, wy, oz + z);
						minY = Math.min(minY, wy);
					}
				}
			}
		}
		Cells clear = new Cells();
		for (int y = Math.max(0, m.groundY); y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					if (writes[(y * sz + z) * sx + x] != 0) {
						continue;
					}
					int f = w.flags(ox + x, oy + y, oz + z);
					if ((f & (NATURAL | TREE)) != 0 && (f & BLOCK_ENTITY) == 0 && (f & (WATER | LAVA)) == 0) {
						clear.add(ox + x, oy + y, oz + z);
					}
				}
			}
		}
		// fluids in the box grown by one block sideways and one below
		for (int y = -1; y < sy; y++) {
			for (int z = -1; z <= sz; z++) {
				for (int x = -1; x <= sx; x++) {
					int f = w.flags(ox + x, oy + y, oz + z);
					if ((f & LAVA) != 0) {
						lava.add(ox + x, oy + y, oz + z);
					} else if ((f & WATER) != 0) {
						water.add(ox + x, oy + y, oz + z);
					}
				}
			}
		}
		return new Plan(fill.all(), clear.all(), water.drawn(), water.n, lava.drawn(), lava.n, minY);
	}

	/** The refusal for a plan's fluids (lava inside or next to the footprint), or null. */
	public static String lavaRefusal(Plan p) {
		return p.lavaCount() == 0 ? null : "lava in or next to the footprint (" + p.lavaCount() + " block" + (p.lavaCount() == 1 ? "" : "s") + ")";
	}

	/** The warning for a plan's water, or null. */
	public static String waterWarning(Plan p) {
		return p.waterCount() == 0 ? null : p.waterCount() + " water block" + (p.waterCount() == 1 ? "" : "s") + " in or next to the footprint";
	}

	/**
	 * The ground height under a footprint: the median of the columns' surface heights (feet row: one above the
	 * top solid block), ignoring columns without one ({@link Integer#MIN_VALUE}). Ties round down. Returns
	 * {@code fallback} when no column has a surface.
	 */
	public static int medianSurface(int[] surfaces, int fallback) {
		int[] s = Arrays.stream(surfaces).filter(v -> v != Integer.MIN_VALUE).sorted().toArray();
		if (s.length == 0) {
			return fallback;
		}
		return s[(s.length - 1) / 2];
	}

	// ------------------------------------------------------------------ the real world

	/** {@link World} flags of a block (client or server level). */
	public static int flags(BlockGetter level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		if (s.hasBlockEntity()) {
			return BLOCK_ENTITY;
		}
		FluidState fl = s.getFluidState();
		int f = 0;
		if (fl.is(FluidTags.LAVA)) {
			f |= LAVA;
		} else if (fl.is(FluidTags.WATER)) {
			f |= WATER;
		}
		if (s.isAir() || s.canBeReplaced()) {
			f |= FILLABLE;
			if (!s.isAir() && fl.isEmpty()) {
				f |= NATURAL; // grass, ferns, snow layers...
			}
			return f;
		}
		if (natural(s)) {
			f |= NATURAL;
		} else if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) {
			f |= TREE;
		}
		return f;
	}

	/**
	 * Natural ground and plants (what world generation puts there), not anything a player builds with. In 26.x
	 * {@code #dirt} no longer holds grass blocks, podzol, mycelium, moss or mud: {@code #substrate_overworld} does (before
	 * it was used here, a grassy slope inside the box was never cleared and buried porches and doors).
	 */
	static boolean natural(BlockState s) {
		return s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER)
			|| s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.SNOW) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY)
			|| s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.SANDSTONE)
			|| s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.CACTUS) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)
			|| s.is(Blocks.SWEET_BERRY_BUSH) || s.is(BlockTags.ORES);
	}

	/** A growable list of cell triples. */
	private static final class Cells {
		private int[] a = new int[48];
		private int len;
		int n;

		void add(int x, int y, int z) {
			n++;
			if (len + 3 > a.length) {
				a = Arrays.copyOf(a, a.length * 2);
			}
			a[len++] = x;
			a[len++] = y;
			a[len++] = z;
		}

		int[] all() {
			return Arrays.copyOf(a, len);
		}

		int[] drawn() {
			return Arrays.copyOf(a, Math.min(len, MAX_DRAWN * 3));
		}
	}
}
