package dev.agentcraft.building;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The rules of a building design request (docs/HUB.md "Generated buildings", foreman/src/protocol.ts
 * {@code DesignRequest}), shared by the hub's design form and its tests: the choices with their
 * descriptions, the limits, the S/M/L size presets, client-side validation with the Foreman's limits,
 * and the geometry of a marked plot (its size limit, and where and how a finished design goes on it).
 * Pure logic, no Minecraft classes.
 */
public final class DesignSpec {
	public static final String SINGLE = "single";
	public static final String GROUP = "group";

	/** A choice: wire id, label, one-line description. */
	public record Choice(String id, String label, String description) {
	}

	public static final List<Choice> STYLES = List.of(
		new Choice("modern", "Modern", "glass, plaster, flat or hip roof"),
		new Choice("cabin", "Cabin", "logs and stone, gable roof"),
		new Choice("townhouse", "Townhouse", "brick, gable roof, tall and narrow"),
		new Choice("workshop", "Workshop", "the look of the bundled workshop"),
		new Choice("campus", "Campus", "a hall with wings around it"),
		new Choice("custom", "Custom", "described only by your notes"));

	public static final List<Choice> MATERIALS = List.of(
		new Choice("agentcraft", "AgentCraft look", "the AgentCraft style in vanilla blocks"),
		new Choice("vanilla", "Any vanilla", "any vanilla look"));

	public static final List<Choice> FEATURES = List.of(
		new Choice("porch", "Porch", "a covered porch at the entrance"),
		new Choice("skylights", "Skylights", "light from the roof"),
		new Choice("courtyard", "Courtyard", "an open court inside"),
		new Choice("big_windows", "Big windows", "tall windows"),
		new Choice("garden", "Garden", "planted ground around it"));

	public static final List<String> SIZES = List.of("S", "M", "L");

	public static final int MIN_WINGS_GROUP = 2;
	public static final int MAX_WINGS = 8;
	public static final int MIN_XZ = 9;
	public static final int MAX_XZ = 128;
	public static final int MIN_Y = 6;
	public static final int MAX_Y = 48;
	public static final int DEFAULT_PLOT_HEIGHT = 16;
	public static final int MAX_NAME = 40;
	public static final int MAX_NOTES = 2000;

	private DesignSpec() {
	}

	public static @Nullable Choice style(@Nullable String id) {
		return find(STYLES, id);
	}

	public static @Nullable Choice find(List<Choice> list, @Nullable String id) {
		for (Choice c : list) {
			if (c.id().equals(id)) {
				return c;
			}
		}
		return null;
	}

	public static List<String> ids(List<Choice> list) {
		return list.stream().map(Choice::id).toList();
	}

	// ------------------------------------------------------------------ size presets

	/**
	 * The size limit {x, y, z} of preset {@code size} (S, M or L). Single: S 24x14x24, M 36x16x36,
	 * L 56x18x40. Group (N wings): wider per wing, S (24+12N)x16x30, M (35+14N)x18x36 (the bundled campus
	 * 2..4 fit exactly: 63/77/91 x 18 x 34), L (44+18N)x22x48; x clamped to {@value #MAX_XZ}.
	 */
	public static int[] preset(String size, String kind, int wings) {
		String s = size.toUpperCase(Locale.ROOT);
		if (!GROUP.equals(kind)) {
			return switch (s) {
				case "S" -> new int[] {24, 14, 24};
				case "M" -> new int[] {36, 16, 36};
				case "L" -> new int[] {56, 18, 40};
				default -> throw new IllegalArgumentException("size must be S, M or L");
			};
		}
		int n = Math.max(MIN_WINGS_GROUP, Math.min(MAX_WINGS, wings));
		int[] out = switch (s) {
			case "S" -> new int[] {24 + 12 * n, 16, 30};
			case "M" -> new int[] {35 + 14 * n, 18, 36};
			case "L" -> new int[] {44 + 18 * n, 22, 48};
			default -> throw new IllegalArgumentException("size must be S, M or L");
		};
		out[0] = Math.min(MAX_XZ, out[0]);
		return out;
	}

	public static int clampXZ(int v) {
		return Math.max(MIN_XZ, Math.min(MAX_XZ, v));
	}

	public static int clampY(int v) {
		return Math.max(MIN_Y, Math.min(MAX_Y, v));
	}

	// ------------------------------------------------------------------ validation

	/** What the form would send (blank strings mean "omitted"). */
	public record Draft(String kind, int wings, String style, String materials, List<String> features, int maxX, int maxY, int maxZ,
		@Nullable String remix, @Nullable String name, @Nullable String notes, String outDir) {
	}

	/**
	 * Field -> problem, in form order (empty = the Foreman's schema accepts it). Field keys: kind, wings,
	 * style, materials, features, maxSize, remix, name, notes, outDir.
	 */
	public static Map<String, String> validate(Draft d) {
		Map<String, String> e = new LinkedHashMap<>();
		if (SINGLE.equals(d.kind())) {
			if (d.wings() != 1) {
				e.put("wings", "a single building has exactly 1 wing");
			}
		} else if (GROUP.equals(d.kind())) {
			if (d.wings() < MIN_WINGS_GROUP || d.wings() > MAX_WINGS) {
				e.put("wings", "a group has " + MIN_WINGS_GROUP + " to " + MAX_WINGS + " wings");
			}
		} else {
			e.put("kind", "for one repo (single) or a group");
		}
		if (style(d.style()) == null) {
			e.put("style", "pick a style (" + String.join(", ", ids(STYLES)) + ")");
		}
		if (find(MATERIALS, d.materials()) == null) {
			e.put("materials", "agentcraft or vanilla");
		}
		Set<String> seen = new HashSet<>();
		for (String f : d.features()) {
			if (find(FEATURES, f) == null) {
				e.put("features", "unknown feature " + f + " (" + String.join(", ", ids(FEATURES)) + ")");
				break;
			}
			if (!seen.add(f)) {
				e.put("features", "duplicate feature " + f);
				break;
			}
		}
		List<String> size = new ArrayList<>();
		if (d.maxX() < MIN_XZ || d.maxX() > MAX_XZ) {
			size.add("x " + d.maxX() + " is outside " + MIN_XZ + ".." + MAX_XZ);
		}
		if (d.maxY() < MIN_Y || d.maxY() > MAX_Y) {
			size.add("height " + d.maxY() + " is outside " + MIN_Y + ".." + MAX_Y);
		}
		if (d.maxZ() < MIN_XZ || d.maxZ() > MAX_XZ) {
			size.add("z " + d.maxZ() + " is outside " + MIN_XZ + ".." + MAX_XZ);
		}
		if (!size.isEmpty()) {
			e.put("maxSize", String.join("; ", size));
		}
		if (!blank(d.remix()) && !Blueprint.ID.matcher(d.remix()).matches()) {
			e.put("remix", "a blueprint id (a-z, 0-9, _)");
		}
		if (!blank(d.name()) && d.name().strip().length() > MAX_NAME) {
			e.put("name", "at most " + MAX_NAME + " characters (" + d.name().strip().length() + ")");
		}
		if (d.notes() != null && d.notes().length() > MAX_NOTES) {
			e.put("notes", "at most " + MAX_NOTES + " characters (" + d.notes().length() + ")");
		}
		String out = outDirProblem(d.outDir());
		if (out != null) {
			e.put("outDir", out);
		}
		return e;
	}

	/** The Foreman's rule for {@code outDir}: absolute, no . or .. segments, ending in agentcraft/blueprints. Null = fine. */
	public static @Nullable String outDirProblem(@Nullable String p) {
		if (p == null || p.isEmpty()) {
			return "missing";
		}
		boolean absolute = p.startsWith("/") || p.matches("^[A-Za-z]:[\\\\/].*") || p.startsWith("\\\\");
		if (!absolute) {
			return "must be an absolute path";
		}
		List<String> segs = new ArrayList<>();
		for (String s : p.split("[\\\\/]+")) {
			if (!s.isEmpty()) {
				segs.add(s);
			}
		}
		if (segs.contains(".") || segs.contains("..")) {
			return "must not contain . or .. segments";
		}
		if (segs.size() < 3 || !segs.get(segs.size() - 2).equals("agentcraft") || !segs.get(segs.size() - 1).equals("blueprints")) {
			return "must be the <gameDir>/agentcraft/blueprints folder";
		}
		return null;
	}

	public static boolean blank(@Nullable String s) {
		return s == null || s.isBlank();
	}

	// ------------------------------------------------------------------ plots

	/**
	 * A marked plot: the world rectangle {@code minX..minX+dx-1, minZ..minZ+dz-1} on the ground at
	 * {@code y} (the surface: the first open block above the ground), a height limit, the side facing
	 * the player who marked it ({@code front}, where the entrance should go) and the dimension.
	 */
	public record Plot(int minX, int y, int minZ, int dx, int dz, int height, String front, String dimension) {
		/**
		 * The plot spanned by two corner blocks (inclusive) at surface heights {@code y1}, {@code y2}: the
		 * ground is the lower of the two (the building sits on the lowest corner).
		 */
		public static Plot of(int x1, int y1, int z1, int x2, int y2, int z2, int height, String front, String dimension) {
			return new Plot(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.abs(x2 - x1) + 1, Math.abs(z2 - z1) + 1, height, front,
				dimension);
		}

		public int maxX() {
			return minX + dx - 1;
		}

		public int maxZ() {
			return minZ + dz - 1;
		}

		/** Length of the entrance side (along the front) and the depth behind it. */
		public int width() {
			return frontAlongX() ? dx : dz;
		}

		public int depth() {
			return frontAlongX() ? dz : dx;
		}

		private boolean frontAlongX() {
			return front.equals("north") || front.equals("south");
		}

		/**
		 * The design's size limit {x, y, z} in the template's own frame, clamped to the request limits:
		 * x = along the entrance side, z = the depth (designs, like the bundled blueprints, have their
		 * entrance on a z side, so for a plot fronting east or west the world's dx and dz swap).
		 */
		public int[] maxSize() {
			return new int[] {clampXZ(width()), clampY(height), clampXZ(depth())};
		}

		/** True when the plot is smaller than a request may ask for (the limit was clamped up). */
		public boolean tooSmall() {
			return dx < MIN_XZ || dz < MIN_XZ;
		}

		public boolean tooLarge() {
			return dx > MAX_XZ || dz > MAX_XZ;
		}

		/**
		 * Where a blueprint goes on this plot: {ox, oy, oz, turns}: rotated so its entrance ({@code bpFront})
		 * faces {@link #front}, its rotated footprint centred on the plot, its ground row on the plot's
		 * surface ({@code oy = y - groundY}, as placement mode does). A footprint larger than the plot still
		 * gets centred (it overhangs; see {@link #fits}).
		 */
		public int[] placement(String bpFront, int sizeX, int sizeZ, int groundY) {
			int turns = BlueprintTransform.turnsToFace(bpFront, front);
			int rsx = BlueprintTransform.rotatedSizeX(sizeX, sizeZ, turns);
			int rsz = BlueprintTransform.rotatedSizeZ(sizeX, sizeZ, turns);
			return new int[] {minX + Math.floorDiv(dx - rsx, 2), y - groundY, minZ + Math.floorDiv(dz - rsz, 2), turns};
		}

		/** Whether that rotated footprint fits inside the plot. */
		public boolean fits(String bpFront, int sizeX, int sizeZ) {
			int turns = BlueprintTransform.turnsToFace(bpFront, front);
			return BlueprintTransform.rotatedSizeX(sizeX, sizeZ, turns) <= dx && BlueprintTransform.rotatedSizeZ(sizeX, sizeZ, turns) <= dz;
		}
	}
}
