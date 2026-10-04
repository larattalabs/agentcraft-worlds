package dev.agentcraft.walk;

/**
 * What one block means to a walking agent outdoors (docs/WAVE2.md W8). The client classifies the level's
 * block states into these int codes ({@code LevelTerrain}); everything else here and in {@link OutdoorPlanner}
 * is pure, so it is unit-tested on synthetic grids.
 *
 * <p>A code is a kind in the high bits and, for {@link #SOLID}, the collision top in sixteenths (1..24) in
 * the low byte:
 * <ul>
 *   <li>{@link #OPEN}: nothing to collide with and harmless (air, grass, flowers, a 1-layer snow cover);</li>
 *   <li>{@link #WATER}: water without a collision shape (wading is fine 1 deep);</li>
 *   <li>{@link #HAZARD}: lava, fire, magma, powder snow, campfires, cacti, berry bushes, wither roses: never
 *       stand in, on or under it;</li>
 *   <li>{@link #DOOR}: doors, fence gates and trapdoors: agents pass through them (client-only entities open
 *       nothing in the world; they walk through the closed door like a ghost), never stand on them;</li>
 *   <li>{@link #LEAVES}: solid, but not a floor (routes never cross tree canopies);</li>
 *   <li>{@link #UNLOADED}: the chunk is not loaded on the client;</li>
 *   <li>{@link #BLOCKED}: below the world (solid, not a floor);</li>
 *   <li>{@link #SOLID}: a collision shape with that top (16 = a full block, 8 = a slab, 24 = a fence).</li>
 * </ul>
 */
public final class WalkCell {
	public static final int OPEN = 0;
	public static final int WATER = 1 << 8;
	public static final int HAZARD = 2 << 8;
	public static final int DOOR = 3 << 8;
	public static final int LEAVES = 4 << 8;
	public static final int UNLOADED = 5 << 8;
	public static final int BLOCKED = 6 << 8;
	public static final int SOLID = 7 << 8;

	/** Feet layers up to this high (sixteenths: carpet, glow strips) need no extra headroom. */
	static final int THIN = 2;
	/** The highest feet block an agent stands on top of within its own cell (a bottom slab). */
	static final int STEP_IN = 8;
	/** A floor block's top must be at least this (dirt path 15, soul sand 14) and at most a full block. */
	static final int FLOOR_MIN = 14;

	private WalkCell() {
	}

	/** A solid block whose collision top is {@code top16} sixteenths (clamped to 1..24). */
	public static int solid(int top16) {
		return SOLID | Math.max(1, Math.min(24, top16));
	}

	public static int kind(int code) {
		return code & ~0xFF;
	}

	public static int top(int code) {
		return code & 0xFF;
	}

	/** Can an agent's body be in this cell (feet or head)? */
	public static boolean passable(int code) {
		return code == OPEN || code == DOOR;
	}

	/**
	 * Feet height of an agent standing in cell (x,y,z), or NaN when it cannot stand there: a floor below (a
	 * solid top of 14..16 sixteenths) with open or 1-deep water feet, or a low block in the feet cell (up to a
	 * slab) with room above; the head cell must be open (no water: deeper than 1); hazards nowhere.
	 */
	public static double floor(Terrain t, int x, int y, int z) {
		int head = t.at(x, y + 1, z);
		if (!passable(head)) {
			return Double.NaN;
		}
		int feet = t.at(x, y, z);
		if (feet == OPEN || feet == DOOR || feet == WATER) {
			int below = t.at(x, y - 1, z);
			if (kind(below) != SOLID) {
				return Double.NaN; // air, water, leaves, doors/trapdoors, hazards, unloaded: no floor
			}
			int top = top(below);
			if (top < FLOOR_MIN || top > 16) {
				return Double.NaN; // a slab or carpet one down (stand in that cell instead), a fence or wall
			}
			return y - (16 - top) / 16.0;
		}
		if (kind(feet) == SOLID && top(feet) <= STEP_IN) {
			if (top(feet) > THIN && !passable(t.at(x, y + 2, z))) {
				return Double.NaN; // on a slab the head reaches into the cell above
			}
			return y + top(feet) / 16.0;
		}
		return Double.NaN;
	}

	/** Can something drop through this cell (an agent stepping off a ledge)? Water counts. */
	static boolean fallThrough(int code) {
		return code == OPEN || code == DOOR || code == WATER;
	}

	/** The cell y an agent's feet at height {@code feetY} are in (a dirt path's 15/16 top and a slab's 1/2 both belong to their own cell). */
	public static int cellY(double feetY) {
		return (int) Math.floor(feetY + 0.1);
	}

	/** Packs a cell like {@code BlockPos.asLong} (x, z 26 bits, y 12 bits). */
	public static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	public static int unpackX(long p) {
		return (int) (p >> 38);
	}

	public static int unpackY(long p) {
		return (int) (p << 52 >> 52);
	}

	public static int unpackZ(long p) {
		return (int) (p << 26 >> 38);
	}
}
