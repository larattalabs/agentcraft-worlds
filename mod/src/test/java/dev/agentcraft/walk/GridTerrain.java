package dev.agentcraft.walk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/** Synthetic terrain for tests: a height field of full blocks (top block at {@code height(x,z)}) plus single-cell overrides. */
final class GridTerrain implements Terrain {
	interface Height {
		int at(int x, int z);
	}

	private final Height height;
	private final Long2IntOpenHashMap set = new Long2IntOpenHashMap();
	/** Cells with x beyond this are unloaded (Integer.MAX_VALUE = all loaded). */
	int unloadedFromX = Integer.MAX_VALUE;
	long lookups;

	GridTerrain(Height height) {
		this.height = height;
		set.defaultReturnValue(Integer.MIN_VALUE);
	}

	/** Flat ground: the top block at y = 64 (feet at 65). */
	static GridTerrain flat() {
		return new GridTerrain((x, z) -> 64);
	}

	GridTerrain set(int x, int y, int z, int code) {
		set.put(WalkCell.pack(x, y, z), code);
		return this;
	}

	/** A column of {@code code} from y0 to y1 inclusive. */
	GridTerrain column(int x, int z, int y0, int y1, int code) {
		for (int y = y0; y <= y1; y++) {
			set(x, y, z, code);
		}
		return this;
	}

	@Override
	public int at(int x, int y, int z) {
		lookups++;
		if (x > unloadedFromX) {
			return WalkCell.UNLOADED;
		}
		int c = set.get(WalkCell.pack(x, y, z));
		if (c != Integer.MIN_VALUE) {
			return c;
		}
		return y <= height.at(x, z) ? WalkCell.solid(16) : WalkCell.OPEN;
	}
}
