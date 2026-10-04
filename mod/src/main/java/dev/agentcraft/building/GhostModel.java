package dev.agentcraft.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The placement wizard's ghost of a template, free of Minecraft types so it is unit-tested without a
 * game (the client fills {@link Cells} from the structure template and draws the result).
 *
 * <p>A {@link GhostModel} is the template's written cells for one rotation, in rotated-local block
 * coordinates (the rotated box's minimum corner is {@code 0,0,0}, exactly where
 * {@link Buildings#place} puts a template's blocks relative to its {@code origin}), plus, for every
 * visible cell, which of its six faces are exposed (no visible template cell next to it). Air cells
 * are kept: placing writes them (a building clears its rooms), so they count for obstructions, but
 * they are not drawn.
 *
 * <p>{@link #classify} decides what a cell's world block means for placement; {@link #refusals}
 * mirrors the refusals of {@link Buildings#place} so the wizard can say "this will be refused"
 * before the player confirms.
 */
public final class GhostModel {
	/** Face bits, in {@code Direction} order: down, up, north (-z), south (+z), west (-x), east (+x). */
	public static final int DOWN = 0;
	public static final int UP = 1;
	public static final int NORTH = 2;
	public static final int SOUTH = 3;
	public static final int WEST = 4;
	public static final int EAST = 5;
	private static final int[][] NEIGHBOUR = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	/**
	 * A template's written cells, template-local and unrotated.
	 *
	 * @param xyz three ints per cell (x, y, z)
	 * @param argb one colour per cell; alpha 0 = an air cell (written, not drawn)
	 */
	public record Cells(int sizeX, int sizeY, int sizeZ, int groundY, int[] xyz, int[] argb) {
		public Cells {
			if (xyz.length != argb.length * 3) {
				throw new IllegalArgumentException("xyz has " + xyz.length + " ints for " + argb.length + " cells");
			}
		}

		public int count() {
			return argb.length;
		}
	}

	/** What placing a cell over the current world block means. */
	public enum Conflict {
		/** The world cell is air or replaceable (grass, flowers, water...), or the template does not write it. */
		NONE,
		/** A floor / foundation row (below {@code groundY}) replacing terrain: expected, not shown. */
		TERRAIN,
		/** A row at or above the ground row would replace a solid world block (advisory: placement still works). */
		OBSTRUCTED,
		/** A block entity the mod did not place: {@link Buildings#place} refuses unless forced. */
		BLOCKED
	}

	public final int turns;
	/** Rotated size. */
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	public final int groundY;
	private final int[] xyz;
	private final int[] argb;
	private final byte[] faces;
	private final int visible;
	private final int faceCount;

	private GhostModel(int turns, int sizeX, int sizeY, int sizeZ, int groundY, int[] xyz, int[] argb, byte[] faces, int visible, int faceCount) {
		this.turns = turns;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.groundY = groundY;
		this.xyz = xyz;
		this.argb = argb;
		this.faces = faces;
		this.visible = visible;
		this.faceCount = faceCount;
	}

	/** The ghost of {@code cells} after {@code turns} clockwise quarter turns (rotation as in {@link BlueprintTransform}). */
	public static GhostModel of(Cells cells, int turns) {
		int t = Math.floorMod(turns, 4);
		int sx = cells.sizeX();
		int sz = cells.sizeZ();
		int rsx = BlueprintTransform.rotatedSizeX(sx, sz, t);
		int rsz = BlueprintTransform.rotatedSizeZ(sx, sz, t);
		int sy = cells.sizeY();
		int n = cells.count();
		int[] out = new int[n * 3];
		// occupancy of visible cells in the rotated box, for the exposed-face test
		boolean[] solid = new boolean[rsx * sy * rsz];
		for (int i = 0; i < n; i++) {
			int[] r = BlueprintTransform.rotateBlock(cells.xyz()[i * 3], cells.xyz()[i * 3 + 2], sx, sz, t);
			int y = cells.xyz()[i * 3 + 1];
			out[i * 3] = r[0];
			out[i * 3 + 1] = y;
			out[i * 3 + 2] = r[1];
			if (isVisible(cells.argb()[i]) && inside(r[0], y, r[1], rsx, sy, rsz)) {
				solid[(y * rsz + r[1]) * rsx + r[0]] = true;
			}
		}
		byte[] faces = new byte[n];
		int visible = 0;
		int faceCount = 0;
		for (int i = 0; i < n; i++) {
			if (!isVisible(cells.argb()[i])) {
				continue;
			}
			visible++;
			int mask = 0;
			for (int f = 0; f < 6; f++) {
				int x = out[i * 3] + NEIGHBOUR[f][0];
				int y = out[i * 3 + 1] + NEIGHBOUR[f][1];
				int z = out[i * 3 + 2] + NEIGHBOUR[f][2];
				if (!inside(x, y, z, rsx, sy, rsz) || !solid[(y * rsz + z) * rsx + x]) {
					mask |= 1 << f;
					faceCount++;
				}
			}
			faces[i] = (byte) mask;
		}
		return new GhostModel(t, rsx, sy, rsz, cells.groundY(), out, cells.argb().clone(), faces, visible, faceCount);
	}

	private static boolean isVisible(int argb) {
		return (argb >>> 24) != 0;
	}

	private static boolean inside(int x, int y, int z, int sx, int sy, int sz) {
		return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
	}

	public int count() {
		return argb.length;
	}

	/** Rotated-local position of cell {@code i}. */
	public int x(int i) {
		return xyz[i * 3];
	}

	public int y(int i) {
		return xyz[i * 3 + 1];
	}

	public int z(int i) {
		return xyz[i * 3 + 2];
	}

	/** The cell's colour; alpha 0 for an air cell. */
	public int argb(int i) {
		return argb[i];
	}

	public boolean visible(int i) {
		return isVisible(argb[i]);
	}

	/** Exposed faces of a visible cell (bit {@code 1 << DOWN} ...), 0 for air cells and buried cells. */
	public int faces(int i) {
		return faces[i];
	}

	/** Visible (non-air) cells. */
	public int visibleCount() {
		return visible;
	}

	/** Exposed faces over all visible cells (what the ghost draws). */
	public int faceCount() {
		return faceCount;
	}

	// ------------------------------------------------------------------ outline

	/**
	 * A straight outline segment in rotated-local block-corner coordinates: the box spans
	 * {@code 0..sizeX} x {@code 0..sizeY} x {@code 0..sizeZ}, so cell (x, y, z) fills x..x+1 etc.
	 */
	public record Edge(int x0, int y0, int z0, int x1, int y1, int z1) {
	}

	private volatile int @Nullable [] heights;
	private volatile @Nullable List<Edge> outline;
	@SuppressWarnings("unchecked")
	private final List<Edge>[] frontEdges = new List[4];

	/**
	 * Per column (index {@code z * sizeX + x}) the top of its highest visible cell ({@code y + 1}),
	 * 0 for a column the ghost draws nothing in. The footprint is the columns above 0.
	 */
	public int[] columnHeights() {
		int[] h = heights;
		if (h == null) {
			h = new int[sizeX * sizeZ];
			for (int i = 0; i < count(); i++) {
				if (visible(i) && x(i) >= 0 && z(i) >= 0 && x(i) < sizeX && z(i) < sizeZ) {
					int idx = z(i) * sizeX + x(i);
					h[idx] = Math.max(h[idx], y(i) + 1);
				}
			}
			heights = h;
		}
		return h;
	}

	private boolean in(int[] h, int x, int z) {
		return x >= 0 && z >= 0 && x < sizeX && z < sizeZ && h[z * sizeX + x] > 0;
	}

	private int height(int[] h, int x, int z) {
		return in(h, x, z) ? h[z * sizeX + x] : 0;
	}

	/**
	 * The outline of what the ghost draws (not of the template's box: a template need not write every
	 * cell of its box, e.g. the studio's porch is 7 of its 37 columns wide, and the box corners beside it
	 * stay terrain). Around the footprint's perimeter: an edge at the bottom (y 0) and one at each
	 * column's top, plus vertical edges at the perimeter's corners (0 to the taller side) and where the
	 * top steps along a straight wall. Collinear pieces at the same height are merged, so a full box
	 * gives exactly its 12 edges. Cached.
	 */
	public List<Edge> outline() {
		List<Edge> o = outline;
		if (o == null) {
			o = List.copyOf(computeOutline());
			outline = o;
		}
		return o;
	}

	private List<Edge> computeOutline() {
		int[] h = columnHeights();
		// unit edges keyed by (axis, line, y): x-edges lie on a z line, z-edges on an x line
		java.util.Map<Long, List<Integer>> runs = new java.util.HashMap<>();
		for (int z = 0; z < sizeZ; z++) {
			for (int x = 0; x < sizeX; x++) {
				if (!in(h, x, z)) {
					continue;
				}
				int top = height(h, x, z);
				for (int y : new int[] {0, top}) {
					if (!in(h, x, z - 1)) {
						addRun(runs, 0, z, y, x);
					}
					if (!in(h, x, z + 1)) {
						addRun(runs, 0, z + 1, y, x);
					}
					if (!in(h, x - 1, z)) {
						addRun(runs, 1, x, y, z);
					}
					if (!in(h, x + 1, z)) {
						addRun(runs, 1, x + 1, y, z);
					}
				}
			}
		}
		List<Edge> out = new ArrayList<>();
		runs.keySet().stream().sorted().forEach(key -> {
			int axis = (int) (key >>> 62);
			int line = (int) ((key >>> 31) & 0x7FFFFFFF) - (1 << 30);
			int y = (int) (key & 0x7FFFFFFF) - (1 << 30);
			List<Integer> starts = runs.get(key);
			starts.sort(null);
			int i = 0;
			while (i < starts.size()) {
				int a = starts.get(i);
				int b = a + 1;
				i++;
				while (i < starts.size() && starts.get(i) <= b) {
					b = Math.max(b, starts.get(i) + 1);
					i++;
				}
				out.add(axis == 0 ? new Edge(a, y, line, b, y, line) : new Edge(line, y, a, line, y, b));
			}
		});
		// vertical edges at the perimeter's vertices
		for (int vz = 0; vz <= sizeZ; vz++) {
			for (int vx = 0; vx <= sizeX; vx++) {
				// perimeter edges leaving this vertex: +x, -x, +z, -z, each with the inside column's height (-1 = none)
				int px = perimeter(h, vx, vz - 1, vx, vz);
				int nx = perimeter(h, vx - 1, vz - 1, vx - 1, vz);
				int pz = perimeter(h, vx - 1, vz, vx, vz);
				int nz = perimeter(h, vx - 1, vz - 1, vx, vz - 1);
				int n = (px >= 0 ? 1 : 0) + (nx >= 0 ? 1 : 0) + (pz >= 0 ? 1 : 0) + (nz >= 0 ? 1 : 0);
				if (n == 0) {
					continue;
				}
				int max = Math.max(Math.max(px, nx), Math.max(pz, nz));
				boolean straight = n == 2 && (px >= 0 && nx >= 0 || pz >= 0 && nz >= 0);
				if (!straight) {
					out.add(new Edge(vx, 0, vz, vx, max, vz));
				} else {
					int a = px >= 0 ? px : pz;
					int b = px >= 0 ? nx : nz;
					if (a != b) {
						out.add(new Edge(vx, Math.min(a, b), vz, vx, Math.max(a, b), vz));
					}
				}
			}
		}
		return out;
	}

	/** The height of the inside column when exactly one of the two columns is in the footprint, else -1. */
	private int perimeter(int[] h, int ax, int az, int bx, int bz) {
		boolean a = in(h, ax, az);
		boolean b = in(h, bx, bz);
		return a == b ? -1 : a ? height(h, ax, az) : height(h, bx, bz);
	}

	private static void addRun(java.util.Map<Long, List<Integer>> runs, int axis, int line, int y, int start) {
		long key = ((long) axis << 62) | ((long) (line + (1 << 30)) << 31) | (y + (1 << 30));
		runs.computeIfAbsent(key, k -> new ArrayList<>()).add(start);
	}

	/**
	 * The entrance side of the outline at the ground row: the footprint's perimeter edges facing
	 * {@code front} (rotated direction, north/east/south/west) on its front-most line (for the studio
	 * only the porch, not the hall's front wall beside it). Merged, at {@code y = groundY}. Cached.
	 */
	public List<Edge> frontEdges(String front) {
		int d = BlueprintTransform.directionIndex(front);
		if (d < 0) {
			throw new IllegalArgumentException("bad front " + front);
		}
		List<Edge> f = frontEdges[d];
		if (f != null) {
			return f;
		}
		int[] h = columnHeights();
		// outward step of the front direction (north = -z, east = +x, south = +z, west = -x)
		int dx = d == 1 ? 1 : d == 3 ? -1 : 0;
		int dz = d == 2 ? 1 : d == 0 ? -1 : 0;
		boolean alongX = dz != 0;
		int best = 0;
		boolean any = false;
		List<int[]> pieces = new ArrayList<>(); // {line, start}
		for (int z = 0; z < sizeZ; z++) {
			for (int x = 0; x < sizeX; x++) {
				if (!in(h, x, z) || in(h, x + dx, z + dz)) {
					continue;
				}
				// the line of the face on the outward side
				int line = alongX ? (dz > 0 ? z + 1 : z) : (dx > 0 ? x + 1 : x);
				int start = alongX ? x : z;
				// front-most: largest line for south/east, smallest for north/west
				int rank = (dx + dz) > 0 ? line : -line;
				if (!any || rank > best) {
					best = rank;
					any = true;
					pieces.clear();
				}
				if (rank == best) {
					pieces.add(new int[] {line, start});
				}
			}
		}
		pieces.sort((p, q) -> Integer.compare(p[1], q[1]));
		List<Edge> out = new ArrayList<>();
		int i = 0;
		while (i < pieces.size()) {
			int line = pieces.get(i)[0];
			int a = pieces.get(i)[1];
			int b = a + 1;
			i++;
			while (i < pieces.size() && pieces.get(i)[1] <= b) {
				b = Math.max(b, pieces.get(i)[1] + 1);
				i++;
			}
			out.add(alongX ? new Edge(a, groundY, line, b, groundY, line) : new Edge(line, groundY, a, line, groundY, b));
		}
		f = List.copyOf(out);
		frontEdges[d] = f;
		return f;
	}

	// ------------------------------------------------------------------ placement

	/**
	 * A player-relative step as a world (dx, dz): {@code forward} along {@code facing}, {@code right}
	 * to the player's right (facing south, right is west).
	 */
	public static int[] relativeToWorld(String facing, int forward, int right) {
		return switch (facing.toLowerCase(Locale.ROOT)) {
			case "south" -> new int[] {-right, forward};
			case "north" -> new int[] {right, -forward};
			case "east" -> new int[] {forward, right};
			case "west" -> new int[] {-forward, -right};
			default -> throw new IllegalArgumentException("bad facing " + facing);
		};
	}

	// ------------------------------------------------------------------ conflicts

	/**
	 * What writing template row {@code templateY} over a world block means. A foreign block entity
	 * blocks placement anywhere in the box; otherwise air and replaceable blocks are fine, rows below
	 * the ground row replace terrain on purpose, and anything else at or above it is an obstruction.
	 */
	public static Conflict classify(int templateY, int groundY, boolean worldAir, boolean worldReplaceable, boolean foreignBlockEntity) {
		if (foreignBlockEntity) {
			return Conflict.BLOCKED;
		}
		if (worldAir || worldReplaceable) {
			return Conflict.NONE;
		}
		return templateY < groundY ? Conflict.TERRAIN : Conflict.OBSTRUCTED;
	}

	/**
	 * Why {@link Buildings#place} would refuse this placement, in its order (empty = it goes ahead).
	 * {@code foreignBlockEntities} is only a refusal when not forced.
	 *
	 * @param reposWithBuilding "repo -> building id" for repos that already have one (may be empty)
	 * @param overlapping ids of buildings whose box overlaps the placement box
	 */
	public static List<String> refusals(List<String> repos, int wings, List<String> reposWithBuilding, int boxMinY, int boxMaxY, int levelMinY,
		int levelMaxY, List<String> overlapping, int foreignBlockEntities, boolean force) {
		return refusals(false, repos, wings, reposWithBuilding, boxMinY, boxMaxY, levelMinY, levelMaxY, overlapping, foreignBlockEntities, force);
	}

	/**
	 * {@link #refusals(List, int, List, int, int, int, int, List, int, boolean)} for a building, or for a fixture
	 * ({@code fixture}: docs/VILLAGE.md V2), which takes no repos: any repo is then the refusal, none is fine.
	 */
	public static List<String> refusals(boolean fixture, List<String> repos, int wings, List<String> reposWithBuilding, int boxMinY, int boxMaxY,
		int levelMinY, int levelMaxY, List<String> overlapping, int foreignBlockEntities, boolean force) {
		List<String> out = new ArrayList<>();
		if (fixture) {
			if (!repos.isEmpty()) {
				out.add("a fixture takes no repos");
			}
		} else if (repos.isEmpty()) {
			out.add("no repo chosen");
		}
		for (int i = 0; i < repos.size(); i++) {
			if (repos.get(i).isBlank() || repos.indexOf(repos.get(i)) != i) {
				out.add("bad or repeated repo id '" + repos.get(i) + "'");
			}
		}
		if (!fixture && repos.size() > wings) {
			out.add(repos.size() + " repos for " + wings + " wing" + (wings == 1 ? "" : "s"));
		}
		for (String r : reposWithBuilding) {
			out.add("repo " + r + " already has a building");
		}
		if (boxMinY < levelMinY || boxMaxY > levelMaxY) {
			out.add(String.format(Locale.ROOT, "leaves the build height (%d..%d)", levelMinY, levelMaxY));
		}
		for (String b : overlapping) {
			out.add("overlaps building " + b);
		}
		if (!force && foreignBlockEntities > 0) {
			out.add(foreignBlockEntities + " block entit" + (foreignBlockEntities == 1 ? "y" : "ies") + " in the way (force overwrites them)");
		}
		return out;
	}
}
