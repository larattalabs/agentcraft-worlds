package dev.agentcraft.building;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The placement math, free of Minecraft types so it is unit-tested without a game.
 *
 * <p><b>Rotation.</b> A rotation is a number of clockwise quarter turns seen from above:
 * 0 = {@code NONE}, 1 = {@code CLOCKWISE_90}, 2 = {@code CLOCKWISE_180}, 3 = {@code COUNTERCLOCKWISE_90}
 * (the order of Minecraft's {@code Rotation} enum). Vanilla rotates template block {@code (x, z)} about
 * the pivot block (0,0) to {@code (-z, x)} for one clockwise turn. Translated so the rotated template's
 * minimum corner lands on the placement origin, a template of size {@code sx x sz} maps
 *
 * <pre>
 * block (bx, bz)                     continuous point (x, z) (blocks span [b, b+1])
 * NONE   (bx,          bz)           (x,      z)
 * CW_90  (sz-1-bz,     bx)           (sz - z, x)
 * 180    (sx-1-bx,     sz-1-bz)      (sx - x, sz - z)
 * CCW_90 (bz,          sx-1-bx)      (z,      sx - x)
 * </pre>
 *
 * The continuous form is the exact rotation of the template's footprint {@code [0,sx] x [0,sz]} onto the
 * rotated footprint, so a feet spot at a block centre ({@code bx + .5}) stays at the centre of the
 * block that block went to, and a surface point on a block face ({@code z = 15.0}) stays on that face.
 * Y is unchanged. Yaw (0 = south/+Z, 90 = west/-X) gains 90 degrees per clockwise turn.
 */
public final class BlueprintTransform {
	/** Clockwise order seen from above; {@link #directionIndex} indexes it. */
	public static final List<String> DIRECTIONS = List.of("north", "east", "south", "west");
	/** Lower-case names of Minecraft's {@code Rotation} constants, indexed by quarter turns. */
	public static final List<String> ROTATIONS = List.of("none", "clockwise_90", "clockwise_180", "counterclockwise_90");

	private static final Pattern WING_BINDING = Pattern.compile("([A-Za-z0-9_.\\-]+):#(\\d+)");

	private BlueprintTransform() {
	}

	// ------------------------------------------------------------------ rotation basics

	public static int directionIndex(String dir) {
		return DIRECTIONS.indexOf(dir.toLowerCase(Locale.ROOT));
	}

	/** Quarter turns clockwise that make a template whose entrance faces {@code front} face {@code wanted}. */
	public static int turnsToFace(String front, String wanted) {
		int f = directionIndex(front);
		int w = directionIndex(wanted);
		if (f < 0 || w < 0) {
			throw new IllegalArgumentException("bad direction " + front + " / " + wanted);
		}
		return Math.floorMod(w - f, 4);
	}

	/** The direction {@code dir} points to after {@code turns} clockwise quarter turns. */
	public static String rotateDirection(String dir, int turns) {
		return DIRECTIONS.get(Math.floorMod(directionIndex(dir) + turns, 4));
	}

	/**
	 * Parses a rotation: Minecraft names ({@code none}, {@code clockwise_90}, {@code clockwise_180},
	 * {@code counterclockwise_90}) and short forms ({@code 0}, {@code cw}, {@code 90}, {@code 180},
	 * {@code ccw}, {@code 270}, {@code -90}). Returns quarter turns, or -1 when not a rotation.
	 */
	public static int parseTurns(String s) {
		return switch (s.toLowerCase(Locale.ROOT)) {
			case "none", "0" -> 0;
			case "clockwise_90", "cw", "90", "cw90" -> 1;
			case "clockwise_180", "180", "cw180", "ccw180" -> 2;
			case "counterclockwise_90", "ccw", "270", "-90", "ccw90" -> 3;
			default -> -1;
		};
	}

	public static String rotationName(int turns) {
		return ROTATIONS.get(Math.floorMod(turns, 4));
	}

	/** Rotated template size along X / Z. */
	public static int rotatedSizeX(int sx, int sz, int turns) {
		return (turns & 1) == 0 ? sx : sz;
	}

	public static int rotatedSizeZ(int sx, int sz, int turns) {
		return (turns & 1) == 0 ? sz : sx;
	}

	/** Continuous template-local (x, z) to rotated-local (x, z) in the rotated footprint (see class doc). */
	public static double[] rotatePoint(double x, double z, int sx, int sz, int turns) {
		return switch (Math.floorMod(turns, 4)) {
			case 1 -> new double[] {sz - z, x};
			case 2 -> new double[] {sx - x, sz - z};
			case 3 -> new double[] {z, sx - x};
			default -> new double[] {x, z};
		};
	}

	/** Template-local block (bx, bz) to rotated-local block (see class doc). */
	public static int[] rotateBlock(int bx, int bz, int sx, int sz, int turns) {
		return switch (Math.floorMod(turns, 4)) {
			case 1 -> new int[] {sz - 1 - bz, bx};
			case 2 -> new int[] {sx - 1 - bx, sz - 1 - bz};
			case 3 -> new int[] {bz, sx - 1 - bx};
			default -> new int[] {bx, bz};
		};
	}

	/** Yaw after {@code turns} clockwise quarter turns, normalised to (-180, 180] (north stays 180). */
	public static float rotateYaw(float yaw, int turns) {
		double y = ((yaw + 90.0 * Math.floorMod(turns, 4)) % 360.0 + 360.0) % 360.0;
		return (float) (y > 180.0 ? y - 360.0 : y);
	}

	/** A template-local anchor in world space for a template placed with its rotated minimum corner at (ox, oy, oz). */
	public static Anchor toWorld(Anchor a, int sx, int sz, int turns, int ox, int oy, int oz) {
		double[] p = rotatePoint(a.x(), a.z(), sx, sz, turns);
		return new Anchor(a.name(), ox + p[0], oy + a.y(), oz + p[1], rotateYaw(a.yaw(), turns), a.pitch());
	}

	/** A template-local block box (inclusive) in world block coordinates. */
	public static Anchors.Bounds boxToWorld(Anchors.Bounds b, int sx, int sz, int turns, int ox, int oy, int oz) {
		int[] p = rotateBlock(b.minX(), b.minZ(), sx, sz, turns);
		int[] q = rotateBlock(b.maxX(), b.maxZ(), sx, sz, turns);
		return new Anchors.Bounds(ox + Math.min(p[0], q[0]), oy + Math.min(b.minY(), b.maxY()), oz + Math.min(p[1], q[1]),
			ox + Math.max(p[0], q[0]), oy + Math.max(b.minY(), b.maxY()), oz + Math.max(p[1], q[1]));
	}

	// ------------------------------------------------------------------ wings

	/** The wing number of a per-wing anchor name ({@code task_wall@2} -> 2), 0 when it has no suffix, -1 when the suffix is bad. */
	public static int wingOf(String anchorName) {
		int at = anchorName.lastIndexOf('@');
		if (at < 0) {
			return 0;
		}
		try {
			int n = Integer.parseInt(anchorName.substring(at + 1));
			return n >= 1 ? n : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * Renames per-wing anchors for placement (docs/BUILDINGS.md "Buildings in a world"):
	 * <ul>
	 * <li>single building: {@code name@1 -> name}; other wings are dropped;</li>
	 * <li>group: {@code name@n -> name:<repos[n-1]>}; wing 1 is also kept as plain {@code name} (unless the
	 * blueprint has a plain {@code name} itself), so consumers that only know {@code task_wall} work in a
	 * group building too; wings without a repo are dropped.</li>
	 * </ul>
	 * Anchors without a suffix keep their name; a bad suffix drops the anchor.
	 */
	public static Map<String, Anchor> renameWings(Map<String, Anchor> anchors, boolean group, List<String> repos) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		for (Anchor a : anchors.values()) {
			if (wingOf(a.name()) == 0) {
				out.put(a.name(), a);
			}
		}
		for (Anchor a : anchors.values()) {
			int n = wingOf(a.name());
			if (n == 0 || n < 0 || n > repos.size()) {
				continue;
			}
			String base = a.name().substring(0, a.name().lastIndexOf('@'));
			if (!group) {
				if (n == 1) {
					out.put(base, a.withName(base));
				}
				continue;
			}
			String named = base + ":" + repos.get(n - 1);
			out.put(named, a.withName(named));
			if (n == 1 && !out.containsKey(base)) {
				out.put(base, a.withName(base));
			}
		}
		return out;
	}

	/**
	 * All sidecar anchors in world space with wing names resolved: what a placed building stores.
	 *
	 * @param ox world X of the rotated template's minimum corner (likewise oy, oz)
	 */
	public static Map<String, Anchor> worldAnchors(Blueprint bp, int turns, int ox, int oy, int oz, List<String> repos) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		for (Anchor a : renameWings(bp.anchors(), bp.isGroup(), repos).values()) {
			out.put(a.name(), toWorld(a, bp.sizeX(), bp.sizeZ(), turns, ox, oy, oz));
		}
		return out;
	}

	/** Every sidecar anchor in world space with its raw name ({@code task_wall@2}): what a building pins ({@link Building.Pin}). */
	public static Map<String, Anchor> rawWorldAnchors(Blueprint bp, int turns, int ox, int oy, int oz) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		for (Anchor a : bp.anchors().values()) {
			out.put(a.name(), toWorld(a, bp.sizeX(), bp.sizeZ(), turns, ox, oy, oz));
		}
		return out;
	}

	/**
	 * A building's stored anchors after its repos changed from {@code before} to {@code after} when its own wing
	 * anchors are unknown (a record placed before pins, docs/BUILDINGS.md "Blueprint versions"): positions are kept,
	 * only names change. {@code name:<before[n]>} becomes {@code name:<after[n]>}, or is dropped when wing n has no
	 * repo now; other names stay. Pure.
	 */
	public static Map<String, Anchor> rebindAnchors(Map<String, Anchor> anchors, List<String> before, List<String> after) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		for (Anchor a : anchors.values()) {
			int colon = a.name().indexOf(':');
			int wing = colon < 0 ? -1 : before.indexOf(a.name().substring(colon + 1));
			if (wing < 0) {
				out.put(a.name(), a);
			} else if (wing < after.size()) {
				String named = a.name().substring(0, colon + 1) + after.get(wing);
				out.put(named, a.withName(named));
			}
		}
		return out;
	}

	/** World layout bounds: the sidecar's walk box (or the whole template) in world space. */
	public static Anchors.Bounds worldBounds(Blueprint bp, int turns, int ox, int oy, int oz) {
		Anchors.Bounds walk = bp.walk() != null ? bp.walk() : new Anchors.Bounds(0, 0, 0, bp.sizeX() - 1, bp.sizeY() - 1, bp.sizeZ() - 1);
		return boxToWorld(walk, bp.sizeX(), bp.sizeZ(), turns, ox, oy, oz);
	}

	// ------------------------------------------------------------------ bindings

	/**
	 * Rewrites a wing placeholder binding: {@code repo:#2} -> {@code repo:<repos[1]>}, {@code ci:#1} ->
	 * {@code ci:<repos[0]>} (any {@code <prefix>:#<n>}). Returns null when the binding is not a placeholder
	 * or names a wing without a repo (left as is, so it matches no repo).
	 */
	public static @Nullable String rewriteBinding(String binding, List<String> repos) {
		Matcher m = WING_BINDING.matcher(binding);
		if (!m.matches()) {
			return null;
		}
		int n;
		try {
			n = Integer.parseInt(m.group(2));
		} catch (NumberFormatException e) {
			return null;
		}
		if (n < 1 || n > repos.size()) {
			return null;
		}
		return m.group(1) + ":" + repos.get(n - 1);
	}

	/**
	 * A station binding after a building's repos changed from {@code before} to {@code after} (wing n =
	 * repos[n-1]): {@code repo:<old wing n repo>} / {@code ci:<old wing n repo>} and unfilled {@code repo:#n} /
	 * {@code ci:#n} become wing n's new repo, or {@code #n} again when wing n has none now. Returns null when the
	 * binding is unchanged (other prefixes, agents, repos that were not a wing).
	 */
	public static @Nullable String rebindBinding(String binding, List<String> before, List<String> after) {
		int colon = binding.indexOf(':');
		if (colon <= 0) {
			return null;
		}
		String prefix = binding.substring(0, colon);
		if (!prefix.equals("repo") && !prefix.equals("ci")) {
			return null;
		}
		String rest = binding.substring(colon + 1);
		int wing;
		if (rest.startsWith("#")) {
			try {
				wing = Integer.parseInt(rest.substring(1));
			} catch (NumberFormatException e) {
				return null;
			}
		} else {
			wing = before.indexOf(rest) + 1;
		}
		if (wing < 1) {
			return null;
		}
		String to = prefix + ":" + (wing <= after.size() ? after.get(wing - 1) : "#" + wing);
		return to.equals(binding) ? null : to;
	}

	// ------------------------------------------------------------------ placement in front of a player

	/**
	 * Origin (rotated minimum corner) for a building placed in front of a player: the template's ground
	 * row at the player's feet, the near edge {@code gap} blocks ahead along {@code facing}, centred
	 * sideways on the player.
	 *
	 * @param rsx rotated size along X, {@code rsz} along Z
	 */
	public static int[] originInFront(int px, int py, int pz, String facing, int rsx, int rsz, int groundY, int gap) {
		int oy = py - groundY;
		return switch (facing.toLowerCase(Locale.ROOT)) {
			case "south" -> new int[] {px - rsx / 2, oy, pz + gap};
			case "north" -> new int[] {px - rsx / 2, oy, pz - gap - (rsz - 1)};
			case "east" -> new int[] {px + gap, oy, pz - rsz / 2};
			case "west" -> new int[] {px - gap - (rsx - 1), oy, pz - rsz / 2};
			default -> throw new IllegalArgumentException("bad facing " + facing);
		};
	}

	/**
	 * When no blueprint takes {@code n} repos (docs/BUILDINGS.md "Too few wings"): how many to place in the first of
	 * two buildings: half, rounded up, but no more than any blueprint takes ({@code maxWings}, 1 with only single
	 * blueprints); 0 when nothing fits even one repo or there is nothing to split.
	 */
	public static int splitAt(int n, int maxWings, boolean anySingle) {
		int fits = Math.max(maxWings, anySingle ? 1 : 0);
		if (fits == 0 || n <= 1) {
			return 0;
		}
		return Math.min(fits, (n + 1) / 2);
	}

	/** Splits a comma list of repo ids, trimming blanks; keeps order, drops duplicates. */
	public static List<String> parseRepos(String list) {
		List<String> out = new ArrayList<>();
		for (String s : list.split(",")) {
			String t = s.trim();
			if (!t.isEmpty() && !out.contains(t)) {
				out.add(t);
			}
		}
		return out;
	}
}
