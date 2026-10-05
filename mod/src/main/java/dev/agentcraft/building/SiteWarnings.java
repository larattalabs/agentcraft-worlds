package dev.agentcraft.building;

import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Site warnings (docs/BUILDINGS.md "Site warnings"): what is wrong with the ground in front of a building's entrance and
 * under its entrance approach, which placing does not fix and walkers (the player, agents) meet when they step out of the
 * door. Shared by the ghost (drawn magenta, listed in the HUD), the server's place note and the server verdict (S4).
 * Warnings never refuse a placement. Pure: the world comes in through {@link TerrainFit.World}.
 * <ul>
 * <li><b>in front</b>: the strip the approach covers grown by one column on each side, from the box's front face out to
 * {@link #AHEAD} rows past the approach's end (or {@link #AHEAD} rows when the blueprint has no approach). Per column the
 * ground is searched like the approach does ({@link Approach#groundFeet}) near the path's height there: <b>water</b> or
 * <b>lava</b> on the surface (the approach strip's own cells are the approach's to report), a <b>drop</b> of
 * {@link #DROP} or more blocks below the path, and no ground within reach (more than 13 below: a <b>cave opening</b> or
 * a deep gully). The approach's own strip is skipped for drops (its fill holds the path up);</li>
 * <li><b>under the approach</b>: a path column whose fill reached {@link TerrainFit#MAX_FILL} without meeting the ground
 * (a gully under the path), and air or fluid within {@link #CAVE_DEPTH} blocks under the ground the path stands on (a
 * cave under a thin roof).</li>
 * </ul>
 */
public final class SiteWarnings {
	/** Rows checked past the approach's end. */
	public static final int AHEAD = 4;
	/** A step down this deep (or deeper) in front of the entrance is a drop. */
	public static final int DROP = 3;
	/** How far below the ground under the path a cave is looked for. */
	public static final int CAVE_DEPTH = 3;

	/** What the scan found (cells are world (x, y, z) triples, for drawing; counts are exact). */
	public record Result(int[] cells, int water, int lava, int drops, int maxDrop, int openings, int gullies, int caves) {
		public static final Result NONE = new Result(new int[0], 0, 0, 0, 0, 0, 0, 0);

		public boolean any() {
			return water + lava + drops + openings + gullies + caves > 0;
		}

		/** The warnings for the HUD and the place note, in a fixed order; empty when the site is fine. */
		public List<String> warnings() {
			List<String> out = new ArrayList<>();
			if (water > 0) {
				out.add(water + " water block" + (water == 1 ? "" : "s") + " in front of the entrance");
			}
			if (lava > 0) {
				out.add(lava + " lava block" + (lava == 1 ? "" : "s") + " in front of the entrance");
			}
			if (drops > 0) {
				out.add("a drop of up to " + maxDrop + " blocks in front of the entrance (" + drops + " column" + (drops == 1 ? "" : "s") + ")");
			}
			if (openings > 0) {
				out.add("a cave opening or a deep gully in front of the entrance (" + openings + " column" + (openings == 1 ? "" : "s") + ")");
			}
			if (gullies > 0) {
				out.add("the entrance path crosses a gully deeper than " + TerrainFit.MAX_FILL + " blocks (" + gullies + " column"
					+ (gullies == 1 ? "" : "s") + ")");
			}
			if (caves > 0) {
				out.add("a cave under the entrance path (" + caves + " cell" + (caves == 1 ? "" : "s") + ")");
			}
			return out;
		}
	}

	private SiteWarnings() {
	}

	/**
	 * The warnings of {@code bp} placed with {@code turns} at the rotated {@code box} with its approach {@code approach}
	 * ({@link Approach#forBlueprint}); {@link Result#NONE} without an entrance anchor.
	 */
	public static Result forBlueprint(Blueprint bp, int turns, Anchors.Bounds box, Approach.Plan approach, TerrainFit.World w) {
		dev.agentcraft.layout.Anchor e = BlueprintTransform.rawWorldAnchors(bp, turns, box.minX(), box.minY(), box.minZ())
			.get(dev.agentcraft.layout.AnchorNames.ENTRANCE);
		if (e == null) {
			return Result.NONE;
		}
		return scan(box, BlueprintTransform.rotateDirection(bp.front(), turns), e.x(), e.z(), box.minY() + bp.groundY(), bp.approach().width(),
			approach, w);
	}

	/**
	 * The scan for a building whose rotated box is {@code box}, entrance facing {@code front}, entrance anchor at world
	 * ({@code ex}, {@code ez}), the door's feet at {@code feetY}, approach strip {@code width} wide and approach
	 * {@code a} ({@link Approach.Plan#EMPTY} when there is none). Pure.
	 */
	public static Result scan(Anchors.Bounds box, String front, double ex, double ez, int feetY, int width, Approach.Plan a, TerrainFit.World w) {
		int[] out = Approach.outward(front);
		int dx = out[0];
		int dz = out[1];
		boolean alongX = dz != 0;
		int face = dz > 0 ? box.maxZ() : dz < 0 ? box.minZ() : dx > 0 ? box.maxX() : box.minX();
		int centre = (int) Math.floor(alongX ? ex : ez);
		int lo = -(width - 1) / 2;
		int hi = width / 2;
		int[] feet = a.feet().length > 0 ? a.feet() : new int[] {feetY};
		int rows = a.rows();
		Cells cells = new Cells();
		int water = 0;
		int lava = 0;
		int drops = 0;
		int maxDrop = 0;
		int openings = 0;
		for (int i = 1; i <= rows + AHEAD; i++) {
			int ref = feet[Math.min(i, feet.length - 1)];
			for (int c = lo - 1; c <= hi + 1; c++) {
				int[] xz = Approach.cell(face, centre, i, c, dx, dz, alongX);
				int x = xz[0];
				int z = xz[1];
				boolean strip = i <= rows && c >= lo && c <= hi; // the approach's own cells: its fill holds the path, it notes its water
				int g = Approach.groundFeet(w, x, z, ref);
				if (g == Integer.MIN_VALUE) {
					if (!strip) {
						openings++;
						cells.add(x, ref - 1, z);
					}
					continue;
				}
				int f = w.flags(x, g - 1, z);
				if (!strip && (f & TerrainFit.LAVA) != 0) {
					lava++;
					cells.add(x, g - 1, z);
				} else if (!strip && (f & TerrainFit.WATER) != 0) {
					water++;
					cells.add(x, g - 1, z);
				}
				int d = ref - g;
				if (!strip && d >= DROP) {
					drops++;
					maxDrop = Math.max(maxDrop, d);
					cells.add(x, g - 1, z);
				}
			}
		}
		// under the approach: the fill per path column, then what lies under the ground it meets
		int gullies = 0;
		int caves = 0;
		Map<Long, int[]> lowest = new HashMap<>(); // (x, z) -> {lowest fill y, count}
		int[] fill = a.fill();
		for (int i = 0; i + 2 < fill.length; i += 3) {
			lowest.merge(key(fill[i], fill[i + 2]), new int[] {fill[i + 1], 1}, (p, q) -> new int[] {Math.min(p[0], q[0]), p[1] + q[1]});
		}
		int[] path = a.path();
		for (int i = 0; i + 2 < path.length; i += 3) {
			int x = path[i];
			int y = path[i + 1];
			int z = path[i + 2];
			int[] lf = lowest.get(key(x, z));
			int bottom = lf == null ? y : lf[0]; // the lowest block the approach puts in this column
			if (lf != null && lf[1] >= TerrainFit.MAX_FILL && (w.flags(x, bottom - 1, z) & TerrainFit.FILLABLE) != 0) {
				gullies++;
				cells.add(x, bottom - 1, z);
				continue;
			}
			// the ground under the path (or under its fill): a void a few blocks under it is a cave under a thin roof
			for (int k = 2; k <= CAVE_DEPTH + 1; k++) {
				int cy = bottom - k;
				int g = w.flags(x, cy, z);
				if ((g & (TerrainFit.WATER | TerrainFit.LAVA)) != 0 || g == TerrainFit.FILLABLE) {
					caves++;
					cells.add(x, cy, z);
					break;
				}
			}
		}
		return new Result(cells.all(), water, lava, drops, maxDrop, openings, gullies, caves);
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	/** A growable list of cell triples. */
	private static final class Cells {
		private int[] a = new int[48];
		private int len;

		void add(int x, int y, int z) {
			if (len + 3 > a.length) {
				a = Arrays.copyOf(a, a.length * 2);
			}
			a[len++] = x;
			a[len++] = y;
			a[len++] = z;
		}

		int[] all() {
			return Arrays.copyOf(a, Math.min(len, TerrainFit.MAX_DRAWN * 3));
		}
	}

	/** The note for a place result or a verdict, or null when there is nothing to say. */
	public static @Nullable String note(Result r) {
		List<String> w = r.warnings();
		return w.isEmpty() ? null : "site: " + String.join("; ", w);
	}
}
