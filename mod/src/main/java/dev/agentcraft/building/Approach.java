package dev.agentcraft.building;

import com.google.gson.JsonObject;
import dev.agentcraft.layout.Anchors;
import java.util.Arrays;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The entrance approach (docs/BUILDINGS.md "Entrance approach"): a strip in front of the entrance, outside the
 * template's box, that placement turns into a walkable path down or up to the natural terrain, so a door never
 * opens onto a bank or a drop. Shared by the server ({@link Buildings#place}) and the wizard's ghost.
 *
 * <p>Geometry: the strip is {@link Spec#width} cells wide, centred on the entrance anchor's column, and runs
 * outward from the box's front face for {@link Spec#length} rows, plus up to {@link #EXTEND} more while the path
 * has not met the ground. Row 0 is the template's own last row at the door's feet height. Each row's target is
 * the median ground height of its columns (logs and leaves are not ground, water's surface is); the path moves
 * towards it by at most one block per row, so it never steps more than one block. Per row and column:
 * <ul>
 * <li>the <b>path</b> block one below the feet ({@link Spec#block});</li>
 * <li>a bottom <b>slab</b> ({@link Spec#slab}) in the feet cell of a row that sits lower than the row before it, or
 * at the foot of a climb from a level stretch, so steps read (and are walked) as half steps where they can be, and no
 * step is ever more than one block ({@link #floor});</li>
 * <li><b>fill</b> with the foundation block below the path down to solid ground (at most {@link TerrainFit#MAX_FILL});</li>
 * <li><b>clear</b> to air: the {@link #HEADROOM} cells above the path (anything but air and water: plants, terrain,
 * logs, leaves, a player's blocks), then natural terrain further up (a bank) to {@link #MAX_CUT} above the path,
 * so the cut is open to the sky rather than a tunnel.</li>
 * </ul>
 * Block entities on the strip are reported (placement refuses them unless forced, like in the box), lava on or beside
 * it refuses, water is a warning. Pure (the world comes in through {@link TerrainFit.World}), unit-tested.
 */
public final class Approach {
	/** Clear cells above the path surface (feet, head and one more). */
	public static final int HEADROOM = 3;
	/** Natural terrain above the path is cleared up to this many cells above the path surface. */
	public static final int MAX_CUT = 8;
	/** Rows added beyond {@link Spec#length} while the path has not reached the ground. */
	public static final int EXTEND = 8;
	/** How far above the previous row's feet a row's ground is looked for. */
	static final int SCAN_UP = 12;
	public static final int DEFAULT_LENGTH = 6;
	public static final int DEFAULT_WIDTH = 3;
	public static final String DEFAULT_BLOCK = "minecraft:dirt_path";
	public static final String DEFAULT_SLAB = "minecraft:stone_brick_slab";

	/**
	 * A blueprint's {@code approach} (sidecar): rows out from the box (0 = no approach), cells across, the path block
	 * and the half-step slab.
	 */
	public record Spec(int length, int width, String block, String slab) {
		public static final Spec DEFAULT = new Spec(DEFAULT_LENGTH, DEFAULT_WIDTH, DEFAULT_BLOCK, DEFAULT_SLAB);
		public static final Spec NONE = new Spec(0, DEFAULT_WIDTH, DEFAULT_BLOCK, DEFAULT_SLAB);
		public static final int MAX_LENGTH = 16;
		public static final int MAX_WIDTH = 7;

		public Spec {
			if (length < 0 || length > MAX_LENGTH) {
				throw new IllegalArgumentException("approach.length must be 0.." + MAX_LENGTH + " (got " + length + ")");
			}
			if (width < 1 || width > MAX_WIDTH) {
				throw new IllegalArgumentException("approach.width must be 1.." + MAX_WIDTH + " (got " + width + ")");
			}
		}

		public boolean enabled() {
			return length > 0;
		}

		/** {@code "approach": false | {length?, width?, block?, slab?}}; missing = {@link #DEFAULT}. */
		public static Spec fromJson(com.google.gson.@Nullable JsonElement e) {
			if (e == null || e.isJsonNull()) {
				return DEFAULT;
			}
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
				return e.getAsBoolean() ? DEFAULT : NONE;
			}
			if (!e.isJsonObject()) {
				throw new IllegalArgumentException("\"approach\" must be an object or false");
			}
			JsonObject o = e.getAsJsonObject();
			int length = o.has("length") ? o.get("length").getAsInt() : DEFAULT_LENGTH;
			int width = o.has("width") ? o.get("width").getAsInt() : DEFAULT_WIDTH;
			return new Spec(length, width, blockId(o, "block", DEFAULT_BLOCK), blockId(o, "slab", DEFAULT_SLAB));
		}

		private static String blockId(JsonObject o, String key, String def) {
			if (!o.has(key) || o.get(key).isJsonNull()) {
				return def;
			}
			String s = o.get(key).getAsString().strip().toLowerCase(Locale.ROOT);
			if (s.isEmpty()) {
				return def;
			}
			return s.indexOf(':') < 0 ? "minecraft:" + s : s;
		}

		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("length", length);
			o.addProperty("width", width);
			o.addProperty("block", block);
			o.addProperty("slab", slab);
			return o;
		}
	}

	/**
	 * The terrain work of one approach. Cells are world (x, y, z) triples.
	 *
	 * @param path the path blocks (one per row and column), {@code slabs} the half-step slabs
	 * @param fill foundation below the path, {@code clear} cells cleared to air above it
	 * @param blockEntities block entities on the strip (placement refuses them unless forced)
	 * @param feet per row (index 0 = the template's own row at the door), the path's feet height (block y)
	 * @param bounds every cell the approach touches, or null when there is no approach
	 * @param end the walkable centre of the last row (feet position: x, y, z), or null when there is no approach
	 */
	public record Plan(int[] path, int[] slabs, int[] fill, int[] clear, int[] water, int waterCount, int[] lava, int lavaCount,
		int[] blockEntities, int[] feet, Anchors.@Nullable Bounds bounds, double @Nullable [] end, int ground) {
		public static final Plan EMPTY = new Plan(new int[0], new int[0], new int[0], new int[0], new int[0], 0, new int[0], 0, new int[0],
			new int[0], null, null, Integer.MIN_VALUE);

		public int rows() {
			return Math.max(0, feet.length - 1);
		}

		public int pathCount() {
			return path.length / 3;
		}

		public int fillCount() {
			return fill.length / 3;
		}

		public int clearCount() {
			return clear.length / 3;
		}

		public int blockEntityCount() {
			return blockEntities.length / 3;
		}

		/** Cells the approach changes (path, slabs, fill, clear). */
		public int changed() {
			return (path.length + slabs.length + fill.length + clear.length) / 3;
		}

		/** The box covering {@code box} and this approach (the snapshot's box is this, grown down to the foundation). */
		public Anchors.Bounds union(Anchors.Bounds box) {
			Anchors.Bounds b = bounds;
			if (b == null) {
				return box;
			}
			return new Anchors.Bounds(Math.min(box.minX(), b.minX()), Math.min(box.minY(), b.minY()), Math.min(box.minZ(), b.minZ()),
				Math.max(box.maxX(), b.maxX()), Math.max(box.maxY(), b.maxY()), Math.max(box.maxZ(), b.maxZ()));
		}
	}

	private Approach() {
	}

	/**
	 * The approach of {@code bp} placed with {@code turns} at the rotated {@code box}: from its entrance anchor, or
	 * {@link Plan#EMPTY} when it has none or its sidecar turns the approach off.
	 */
	public static Plan forBlueprint(Blueprint bp, int turns, Anchors.Bounds box, TerrainFit.World w) {
		dev.agentcraft.layout.Anchor e = BlueprintTransform.rawWorldAnchors(bp, turns, box.minX(), box.minY(), box.minZ())
			.get(dev.agentcraft.layout.AnchorNames.ENTRANCE);
		if (e == null || !bp.approach().enabled()) {
			return Plan.EMPTY;
		}
		return plan(box, BlueprintTransform.rotateDirection(bp.front(), turns), e.x(), e.z(), box.minY() + bp.groundY(), bp.approach(), w);
	}

	/** The outward step (dx, dz) of a rotated front (north = -z, east = +x, south = +z, west = -x). */
	public static int[] outward(String front) {
		return switch (BlueprintTransform.directionIndex(front)) {
			case 0 -> new int[] {0, -1};
			case 1 -> new int[] {1, 0};
			case 2 -> new int[] {0, 1};
			case 3 -> new int[] {-1, 0};
			default -> throw new IllegalArgumentException("bad front " + front);
		};
	}

	/**
	 * The approach of a building whose rotated box is {@code box}, entrance facing {@code front} (rotated), with the
	 * entrance anchor at world ({@code ex}, {@code ez}) and the door's feet at {@code feetY} (box minY + groundY).
	 */
	public static Plan plan(Anchors.Bounds box, String front, double ex, double ez, int feetY, Spec spec, TerrainFit.World w) {
		if (!spec.enabled()) {
			return Plan.EMPTY;
		}
		int[] out = outward(front);
		int dx = out[0];
		int dz = out[1];
		boolean alongX = dz != 0; // the strip's columns run along x (front north/south)
		// first row outside the box, and the lateral centre column
		int face = dz > 0 ? box.maxZ() : dz < 0 ? box.minZ() : dx > 0 ? box.maxX() : box.minX();
		int centre = (int) Math.floor(alongX ? ex : ez);
		int lo = -(spec.width() - 1) / 2;
		int hi = spec.width() / 2;
		int maxRows = spec.length() + EXTEND;
		int[] feet = new int[maxRows + 1];
		feet[0] = feetY;
		int rows = 0;
		int lastTarget = Integer.MIN_VALUE;
		for (int i = 1; i <= maxRows; i++) {
			int[] ground = new int[hi - lo + 1];
			for (int c = lo; c <= hi; c++) {
				int[] xz = cell(face, centre, i, c, dx, dz, alongX);
				ground[c - lo] = groundFeet(w, xz[0], xz[1], feet[i - 1]);
			}
			// no ground within reach: a deep drop, keep going down (the fill holds the path up)
			int target = TerrainFit.medianSurface(ground, feet[i - 1] - TerrainFit.MAX_FILL);
			feet[i] = feet[i - 1] + Integer.signum(target - feet[i - 1]);
			lastTarget = target;
			rows = i;
			if (i >= spec.length() && feet[i] == target) {
				break; // met the ground
			}
		}
		feet = Arrays.copyOf(feet, rows + 1);
		Cells path = new Cells();
		Cells slabs = new Cells();
		Cells fill = new Cells();
		Cells clear = new Cells();
		Cells water = new Cells();
		Cells lava = new Cells();
		Cells be = new Cells();
		int[] bb = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (int i = 1; i <= rows; i++) {
			int f = feet[i];
			boolean slab = slab(feet, i);
			for (int c = lo - 1; c <= hi + 1; c++) {
				int[] xz = cell(face, centre, i, c, dx, dz, alongX);
				int x = xz[0];
				int z = xz[1];
				if (c < lo || c > hi) {
					// beside the strip: lava there refuses (a path next to lava floods and burns)
					for (int y = f - 1; y < f + HEADROOM; y++) {
						if ((w.flags(x, y, z) & TerrainFit.LAVA) != 0) {
							lava.add(x, y, z);
						}
					}
					continue;
				}
				// the path block
				int s = w.flags(x, f - 1, z);
				note(s, x, f - 1, z, water, lava, be);
				path.add(x, f - 1, z);
				grow(bb, x, f - 1, z);
				// foundation below it
				for (int d = 1; d <= TerrainFit.MAX_FILL; d++) {
					int y = f - 1 - d;
					int g = w.flags(x, y, z);
					if ((g & TerrainFit.LAVA) != 0) {
						lava.add(x, y, z);
					}
					if ((g & TerrainFit.FILLABLE) == 0 || (g & TerrainFit.BLOCK_ENTITY) != 0) {
						break;
					}
					if ((g & TerrainFit.WATER) != 0) {
						water.add(x, y, z);
					}
					fill.add(x, y, z);
					grow(bb, x, y, z);
				}
				// headroom
				for (int y = f; y < f + HEADROOM; y++) {
					int g = w.flags(x, y, z);
					note(g, x, y, z, water, lava, be);
					if (slab && y == f) {
						slabs.add(x, y, z);
						grow(bb, x, y, z);
						continue;
					}
					if ((g & (TerrainFit.WATER | TerrainFit.LAVA)) != 0) {
						continue; // water stays (a wade, warned), lava refuses
					}
					if (g == TerrainFit.FILLABLE) {
						continue; // air
					}
					clear.add(x, y, z);
					grow(bb, x, y, z);
				}
				// a bank: natural terrain further up, open to the sky
				for (int y = f + HEADROOM; y < f + MAX_CUT; y++) {
					int g = w.flags(x, y, z);
					if ((g & TerrainFit.NATURAL) == 0 || (g & (TerrainFit.WATER | TerrainFit.LAVA | TerrainFit.BLOCK_ENTITY)) != 0) {
						break;
					}
					clear.add(x, y, z);
					grow(bb, x, y, z);
				}
			}
		}
		int[] last = cell(face, centre, rows, 0, dx, dz, alongX);
		int lf = feet[rows];
		boolean lastSlab = rows > 0 && slab(feet, rows);
		double[] end = {last[0] + 0.5, lf + (lastSlab ? 0.5 : 0), last[1] + 0.5};
		Anchors.Bounds bounds = new Anchors.Bounds(bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]);
		return new Plan(path.all(), slabs.all(), fill.all(), clear.all(), water.drawn(), water.n, lava.drawn(), lava.n, be.all(), feet, bounds, end, lastTarget);
	}

	/**
	 * Whether row {@code i} gets a half-step slab: it sits lower than the row before it (going down from the door), or
	 * it is the foot of a climb from a level stretch. Every step between rows then stays within one block, counting
	 * the slabs (a slab at the foot of every row of a climb would make the first step 1.5).
	 */
	static boolean slab(int[] feet, int i) {
		int f = feet[i];
		return feet[i - 1] > f || i + 1 < feet.length && feet[i + 1] > f && feet[i - 1] == f;
	}

	/** The walking height of row {@code i} (its feet, plus a half for a slab; row 0 is the template's own). */
	public static double floor(int[] feet, int i) {
		return feet[i] + (i > 0 && slab(feet, i) ? 0.5 : 0);
	}

	/** The world (x, z) of row {@code i} (1 = just outside the box), column {@code c} (0 = the entrance's). */
	static int[] cell(int face, int centre, int i, int c, int dx, int dz, boolean alongX) {
		return alongX ? new int[] {centre + c, face + i * dz} : new int[] {face + i * dx, centre + c};
	}

	private static void note(int g, int x, int y, int z, Cells water, Cells lava, Cells be) {
		if ((g & TerrainFit.BLOCK_ENTITY) != 0) {
			be.add(x, y, z);
		}
		if ((g & TerrainFit.LAVA) != 0) {
			lava.add(x, y, z);
		} else if ((g & TerrainFit.WATER) != 0) {
			water.add(x, y, z);
		}
	}

	/**
	 * The feet height of the ground in column (x, z) near {@code ref}: one above the highest cell from
	 * {@code ref + SCAN_UP} down to {@code ref - MAX_FILL - 1} that is ground (solid and not a tree) or water; or
	 * {@link Integer#MIN_VALUE} when there is none in that range.
	 */
	static int groundFeet(TerrainFit.World w, int x, int z, int ref) {
		for (int y = ref + SCAN_UP; y >= ref - TerrainFit.MAX_FILL - 1; y--) {
			int g = w.flags(x, y, z);
			if ((g & (TerrainFit.WATER | TerrainFit.LAVA)) != 0) {
				return y + 1;
			}
			if ((g & TerrainFit.FILLABLE) == 0 && (g & TerrainFit.TREE) == 0) {
				return y + 1;
			}
		}
		return Integer.MIN_VALUE;
	}

	private static void grow(int[] bb, int x, int y, int z) {
		bb[0] = Math.min(bb[0], x);
		bb[1] = Math.min(bb[1], y);
		bb[2] = Math.min(bb[2], z);
		bb[3] = Math.max(bb[3], x);
		bb[4] = Math.max(bb[4], y);
		bb[5] = Math.max(bb[5], z);
	}

	/** The refusal for an approach's lava, or null. */
	public static @Nullable String lavaRefusal(Plan p) {
		return p.lavaCount() == 0 ? null : "lava on or beside the entrance approach (" + p.lavaCount() + " block" + (p.lavaCount() == 1 ? "" : "s") + ")";
	}

	/**
	 * The warning for an approach that ran out of rows before meeting the ground (a slope steeper than one block per
	 * block), or null: walkers may not get past its end.
	 */
	public static @Nullable String shortWarning(Plan p) {
		int[] f = p.feet();
		if (f.length < 2 || p.ground() == Integer.MIN_VALUE || p.ground() == f[f.length - 1]) {
			return null;
		}
		int d = p.ground() - f[f.length - 1];
		return "the entrance path ends " + Math.abs(d) + " block" + (Math.abs(d) == 1 ? "" : "s") + (d > 0 ? " below" : " above")
			+ " the ground (too steep here: turn or move the building)";
	}

	/** The warning for an approach's water, or null. */
	public static @Nullable String waterWarning(Plan p) {
		return p.waterCount() == 0 ? null : p.waterCount() + " water block" + (p.waterCount() == 1 ? "" : "s") + " on the entrance approach";
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
			return Arrays.copyOf(a, Math.min(len, TerrainFit.MAX_DRAWN * 3));
		}
	}
}
