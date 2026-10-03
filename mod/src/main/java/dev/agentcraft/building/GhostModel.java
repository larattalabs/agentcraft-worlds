package dev.agentcraft.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
		List<String> out = new ArrayList<>();
		if (repos.isEmpty()) {
			out.add("no repo chosen");
		}
		for (int i = 0; i < repos.size(); i++) {
			if (repos.get(i).isBlank() || repos.indexOf(repos.get(i)) != i) {
				out.add("bad or repeated repo id '" + repos.get(i) + "'");
			}
		}
		if (repos.size() > wings) {
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
