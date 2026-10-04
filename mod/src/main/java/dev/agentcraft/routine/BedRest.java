package dev.agentcraft.routine;

import org.jspecify.annotations.Nullable;

/**
 * The bed side of the night routine (docs/VILLAGE.md V3), pure: where an agent steps in and gets up, whether a lying
 * agent stays in bed, and when a walk counts as arrived. {@code client.agents.Routines} feeds it from the world and
 * the agent's motion. Any thread.
 */
public final class BedRest {
	/** A walk ends "at" its bed spot within this distance (blocks). */
	public static final double ARRIVE_RADIUS = 0.35;
	/** A walk ends at a station slot (library shelves, meeting table) within this distance (blocks). */
	public static final double STATION_RADIUS = 0.6;

	/** The floor height an agent stands on at a cell (feet y), or NaN when it cannot stand there (GridPathfinder#floor). */
	@FunctionalInterface
	public interface Floor {
		double at(int x, int y, int z);
	}

	/**
	 * The cells tried for the spot beside a bed, in order: beside the head (left, then right of the sleeper's facing), beside
	 * the foot (left, right), then beyond the foot. Never the head or foot cell itself. {@code fdx, fdz}: the bed's facing
	 * (from the foot to the head). Three ints per cell.
	 */
	public static int[][] approachCells(int hx, int hy, int hz, int fdx, int fdz) {
		int footX = hx - fdx;
		int footZ = hz - fdz;
		// counter-clockwise of (dx, dz) in Minecraft's x/z: north (0,-1) -> west (-1,0), east (1,0) -> north (0,-1)
		int lx = fdz;
		int lz = -fdx;
		return new int[][] {{hx + lx, hy, hz + lz}, {hx - lx, hy, hz - lz}, {footX + lx, hy, footZ + lz}, {footX - lx, hy, footZ - lz},
			{footX - fdx, hy, footZ - fdz}};
	}

	/**
	 * The free standable spot beside a bed (feet position {x+.5, floor, z+.5}) by {@link #approachCells}, each also one
	 * block up and down; null when there is none (the bed then does not count: nobody could get in or out).
	 */
	public static double @Nullable [] approach(int hx, int hy, int hz, int fdx, int fdz, Floor floor) {
		for (int[] c : approachCells(hx, hy, hz, fdx, fdz)) {
			for (int dy : new int[] {0, 1, -1}) {
				double f = floor.at(c[0], c[1] + dy, c[2]);
				if (!Double.isNaN(f)) {
					return new double[] {c[0] + 0.5, f, c[2] + 0.5};
				}
			}
		}
		return null;
	}

	/** The name of the walk target beside bed {@code bedName}: stable (retargeting compares names) and never a seat ("@"). */
	public static String restAnchor(String bedName) {
		return bedName + "@rest";
	}

	/**
	 * Whether a lying agent stays in its bed this tick. Stale Foreman data freezes everything (stays). Otherwise it stays only
	 * while this tick's plan is still REST with the same bed (name and head cell), it was not sent to another building or
	 * snapped, and the bed is still a free bed head in the world; anything else gets it up.
	 */
	public static boolean keepLying(boolean stale, boolean moved, boolean snap, boolean planRest, @Nullable String planBed, long planHead,
		String lyingBed, long lyingHead, boolean bedGone) {
		if (stale) {
			return true;
		}
		return !moved && !snap && planRest && lyingBed.equals(planBed) && planHead == lyingHead && !bedGone;
	}

	/** Whether a motion has reached the spot {@code want}: its target has that name, it stopped walking, and it stands within {@link #ARRIVE_RADIUS}. */
	public static boolean arrived(@Nullable String targetName, boolean walking, String want, double distSq) {
		return targetName != null && !walking && targetName.equals(want) && distSq < ARRIVE_RADIUS * ARRIVE_RADIUS;
	}

	/**
	 * Whether a motion has reached a slot of {@code station} ({@code library}, {@code library_2}, {@code meeting~3}..): its target is
	 * one of them, it stopped walking, and it stands within {@link #STATION_RADIUS} of that slot.
	 */
	public static boolean atStation(@Nullable String targetName, boolean walking, String station, double distSq) {
		if (targetName == null || walking) {
			return false;
		}
		boolean slot = targetName.equals(station) || targetName.startsWith(station + "_") || targetName.startsWith(station + "~");
		return slot && distSq < STATION_RADIUS * STATION_RADIUS;
	}

	private BedRest() {
	}
}
