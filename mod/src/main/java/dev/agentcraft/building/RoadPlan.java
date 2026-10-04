package dev.agentcraft.building;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.walk.WalkCell;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import org.jspecify.annotations.Nullable;

/**
 * A road between two buildings (docs/VILLAGE.md V1, docs/BUILDINGS.md "Roads"), derived from an outdoor route's cells:
 * the centre line is the route agents walk ({@code OutdoorPlanner} with drops of at most one block, so the road is
 * walkable both ways), the walkway is {@link Options#width} cells across, and every change is a single cell with the
 * kind it replaces. Shared by the server ({@link Roads#lay}, which plans again on its own level: the client's view can
 * be stale) and the hub's ghost. Pure: the world comes in through {@link World} kinds, unit-tested on synthetic terrain.
 *
 * <p>Rules:
 * <ul>
 * <li><b>Where</b>: route cells inside any excluded box (every building's restore box: its template, foundation and
 * entrance approach) are trimmed, so the road runs between the approach ends and never touches a building. Each kept
 * route cell stamps the cells across its step ({@link #offsets}); a diagonal step goes through its corner cell (the
 * planner never cuts corners, so it is standable), so the walkway stays 4-connected around corners and on diagonals.</li>
 * <li><b>Height</b>: a centre cell keeps the route's height; a side cell takes its own ground within one block of its
 * centre cell, and is dropped when it differs by more than one block from a neighbouring road cell. A road cell that
 * has a neighbour one block higher and none lower gets a bottom <b>half-step slab</b> (its ground left as it is), so
 * every step on the road is at most half a block where it climbs from a level stretch and never more than one.</li>
 * <li><b>Blocks</b> ({@link #surfaceFor}): {@code dirt_path} on grass, dirt, podzol, mycelium, moss and coarse dirt;
 * {@code gravel} on sand, gravel and natural stone where the block below holds it up, else {@code packed_mud};
 * {@code packed_mud} on mud. Half steps: {@code mud_brick_slab} on dirt and mud roads, {@code cobblestone_slab} on gravel.</li>
 * <li><b>Clearing</b>: plants, flowers, snow layers and leaves in the walkway's two cells of headroom (three over a
 * slab); a tall plant (double plants, sugar cane, bamboo) is cleared with its whole stack so nothing floats and
 * pops. Never block entities, logs, a player's blocks (anything not natural), fluids or anything waterlogged: a side
 * cell where one is in the way is left out; a centre cell where one is in the way keeps everything as it is.</li>
 * <li><b>Water</b>: a centre or side cell wading 1 deep gets a bridge deck ({@code oak_slab}, bottom) in the air cell
 * above the water only when {@link Options#bridge} is on (the water is never touched); otherwise it is skipped with a
 * note.</li>
 * <li><b>Lanterns</b> ({@link Options#lanterns}): an {@code oak_fence} post with a {@code lantern} on top just beside
 * the walkway, the first {@link #LANTERN_FIRST} blocks along and then every {@link #LANTERN_SPACING} blocks, on natural
 * ground within a block of the road; the other side, then the next centre cells are tried when there is no room.</li>
 * <li>Cells already changed by another road ({@code taken}) are left to that road.</li>
 * </ul>
 */
public final class RoadPlan {
	// ------------------------------------------------------------------ cell kinds (World)
	public static final int AIR = 0;
	/** A plant, flower, sapling, grass, fern, snow layer, lily pad (natural, not waterlogged): cleared from the walkway. */
	public static final int PLANT = 1;
	/** Part of a stacked plant (double plants, sugar cane, bamboo): cleared together with the rest of its stack above. */
	public static final int STACK = 2;
	/** Leaves (not waterlogged): cleared from the walkway. */
	public static final int LEAVES = 3;
	/** A log or a cactus: never cleared. */
	public static final int LOG = 4;
	/** Water (any water fluid, also waterlogged blocks). */
	public static final int WATER = 5;
	public static final int LAVA = 6;
	/** Grass block, dirt, coarse dirt, rooted dirt, podzol, mycelium, moss block. */
	public static final int DIRT = 7;
	/** Sand, red sand, gravel. */
	public static final int SAND = 8;
	/** Natural stone (overworld and nether base stone), sandstone, terracotta, clay, snow block, ores. */
	public static final int STONE = 9;
	public static final int MUD = 10;
	/** A dirt path already. */
	public static final int PATH = 11;
	/** Anything else with a collision shape: a player's block (never changed). */
	public static final int BUILT = 12;
	/** Anything else without one (torches, rails, carpets, crops, signs, fire): never changed, blocks the cell. */
	public static final int BUILT_OPEN = 13;
	/** A block entity: never changed. */
	public static final int BLOCK_ENTITY = 14;
	/** A chunk that is not loaded: the plan is refused. */
	public static final int UNLOADED = 15;

	public static final int MIN_WIDTH = 1;
	public static final int MAX_WIDTH = 3;
	public static final int DEFAULT_WIDTH = 2;
	/** Route cells the server accepts (a 256-block route with a detour). */
	public static final int MAX_ROUTE = 1024;
	public static final double LANTERN_SPACING = 12;
	public static final double LANTERN_FIRST = 6;
	/** The cells above a road cell's ground kept clear (feet and head). */
	public static final int HEADROOM = 2;
	/** How far up a tall plant's stack is followed. */
	static final int STACK_MAX = 12;

	/** The world under a road: the kind of block (x, y, z). */
	@FunctionalInterface
	public interface World {
		int at(int x, int y, int z);
	}

	/** What a road puts into a cell. */
	public enum Block {
		AIR("minecraft:air"),
		DIRT_PATH("minecraft:dirt_path"),
		GRAVEL("minecraft:gravel"),
		PACKED_MUD("minecraft:packed_mud"),
		/** Half step on dirt and mud roads (bottom). */
		PATH_SLAB("minecraft:mud_brick_slab"),
		/** Half step on gravel roads (bottom). */
		STONE_SLAB("minecraft:cobblestone_slab"),
		/** Bridge deck over shallow water (bottom). */
		DECK("minecraft:oak_slab"),
		FENCE("minecraft:oak_fence"),
		LANTERN("minecraft:lantern");

		public final String id;

		Block(String id) {
			this.id = id;
		}

		/** Gains a collision shape where it goes (a player or a pet in that cell would be pushed or stuck). */
		public boolean solid() {
			return this != AIR;
		}

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * @param width cells across ({@link #MIN_WIDTH}..{@link #MAX_WIDTH})
	 * @param lanterns fence posts with lanterns along the road
	 * @param bridge decks over shallow (1 deep) water; off = such cells are skipped
	 */
	public record Options(int width, boolean lanterns, boolean bridge) {
		public static final Options DEFAULT = new Options(DEFAULT_WIDTH, true, false);

		public Options {
			if (width < MIN_WIDTH || width > MAX_WIDTH) {
				throw new IllegalArgumentException("width must be " + MIN_WIDTH + ".." + MAX_WIDTH + " (got " + width + ")");
			}
		}
	}

	/** One changed cell: what goes there and the kind it replaces. */
	public record Op(int x, int y, int z, Block block, int before) {
	}

	/** How a road cell is walked. */
	public enum Role {
		/** On its ground (a new surface, or the ground kept as it is). */
		GROUND,
		/** On a half-step slab in its feet cell. */
		SLAB,
		/** On a bridge deck over shallow water. */
		BRIDGE,
		/** A centre cell left exactly as it is (a player's floor, something in the way). */
		KEPT
	}

	/**
	 * A road cell: its column, the feet cell a walker stands in ({@code feetY}: the slab or deck cell for those) and
	 * whether it is on the route itself.
	 */
	public record Cell(int x, int feetY, int z, boolean centre, Role role) {
	}

	/**
	 * @param ops the changes, per column bottom to top (the order they are made in)
	 * @param cells the road's cells (walkable after the changes)
	 * @param lanterns the lantern cells (x, y, z triples)
	 * @param skipped cells left out per reason (see {@link #REASONS})
	 * @param refused where they are (x, feet y, z triples; the ghost draws them red)
	 * @param refusal why nothing may be laid (unloaded chunks, a broken route), or null
	 * @param box the box covering every change (the snapshot box), or null when nothing changes
	 * @param centre route cells outside the excluded boxes (the road's length in cells)
	 * @param trimmed route cells inside an excluded box (a building, its approach)
	 */
	public record Plan(List<Op> ops, List<Cell> cells, int[] lanterns, Map<String, Integer> skipped, int[] refused, @Nullable String refusal,
		Anchors.@Nullable Bounds box, int centre, int trimmed) {

		public int lanternCount() {
			return lanterns.length / 3;
		}

		/** Ops placing {@code b}. */
		public int count(Block b) {
			int n = 0;
			for (Op o : ops) {
				if (o.block() == b) {
					n++;
				}
			}
			return n;
		}

		/** Cells cleared to air. */
		public int cleared() {
			return count(Block.AIR);
		}

		/** One line per skip reason ("5 cells of shallow water skipped (bridge off)"). */
		public List<String> notes() {
			List<String> out = new ArrayList<>();
			skipped.forEach((k, n) -> out.add(note(k, n)));
			return out;
		}
	}

	/** Skip reasons, in the order notes list them. */
	public static final List<String> REASONS = List.of("water", "deep", "built", "blocked", "steep", "building", "road", "lantern");

	static String note(String reason, int n) {
		String cells = n + " cell" + (n == 1 ? "" : "s");
		return switch (reason) {
			case "water" -> cells + " of shallow water skipped (bridge off)";
			case "deep" -> cells + " over water deeper than 1 skipped";
			case "built" -> cells + " on your own blocks kept as they are";
			case "blocked" -> cells + " with something in the way (logs, blocks, block entities, fluids) left out";
			case "steep" -> cells + " beside the road too steep to pave";
			case "building" -> cells + " inside a building left out";
			case "road" -> cells + " already part of another road";
			case "lantern" -> n + " lantern" + (n == 1 ? "" : "s") + " without room for a post";
			default -> cells + " skipped (" + reason + ")";
		};
	}

	private RoadPlan() {
	}

	// ------------------------------------------------------------------ pure helpers

	/** Can it be cleared from the walkway? */
	public static boolean clearable(int kind) {
		return kind == PLANT || kind == STACK || kind == LEAVES;
	}

	/** Air or clearable. */
	public static boolean open(int kind) {
		return kind == AIR || clearable(kind);
	}

	/** Natural ground a road may pave. */
	public static boolean ground(int kind) {
		return kind == DIRT || kind == SAND || kind == STONE || kind == MUD || kind == PATH;
	}

	/** Something a walker stands on (natural ground or a player's block). */
	static boolean floor(int kind) {
		return ground(kind) || kind == BUILT;
	}

	/** Would a falling block (gravel) drop into this cell? */
	public static boolean free(int kind) {
		return kind == AIR || kind == PLANT || kind == STACK || kind == WATER || kind == LAVA;
	}

	/**
	 * The road's surface on ground {@code kind}, or null when it stays as it is: {@code dirt_path} on dirt-like ground,
	 * {@code gravel} on sand and stone when the block below holds it up ({@code supported}), else {@code packed_mud}
	 * (it never falls), {@code packed_mud} on mud.
	 */
	public static @Nullable Block surfaceFor(int kind, boolean supported) {
		return switch (kind) {
			case DIRT, PATH -> Block.DIRT_PATH;
			case MUD -> Block.PACKED_MUD;
			case SAND, STONE -> supported ? Block.GRAVEL : Block.PACKED_MUD;
			default -> null;
		};
	}

	/** The half-step slab on ground {@code kind}. */
	public static Block slabFor(int kind) {
		return kind == SAND || kind == STONE ? Block.STONE_SLAB : Block.PATH_SLAB;
	}

	/** Lateral offsets of a road {@code width} cells wide (0 = the centre): 1 -> [0], 2 -> [0, 1], 3 -> [-1, 0, 1]. */
	public static int[] offsets(int width) {
		int lo = -(width - 1) / 2;
		int hi = width / 2;
		int[] o = new int[hi - lo + 1];
		for (int k = lo; k <= hi; k++) {
			o[k - lo] = k;
		}
		return o;
	}

	static long col(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static boolean excluded(List<Anchors.Bounds> boxes, int x, int y, int z) {
		for (Anchors.Bounds b : boxes) {
			if (b.contains(x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A fingerprint of a plan's changes (each cell's x, y, z and block, in order): the ghost the player confirmed is sent
	 * with it, and {@link Roads#lay} refuses when its own plan's differs (the ground, a road or a building changed since).
	 */
	public static long hash(List<Op> ops) {
		long h = 0xcbf29ce484222325L;
		for (Op o : ops) {
			h = mix(h, o.x());
			h = mix(h, o.y());
			h = mix(h, o.z());
			h = mix(h, o.block().ordinal());
		}
		return mix(h, ops.size());
	}

	private static long mix(long h, int v) {
		for (int i = 0; i < 4; i++) {
			h ^= (v >>> (8 * i)) & 0xff;
			h *= 0x100000001b3L;
		}
		return h;
	}

	/**
	 * Whether changing a cell from one collision shape to another can trap whoever stands in it: it had none and gets
	 * one, or its top rises by more than {@code 1/8} (a slab back to a full block). A ground swap (grass to a dirt path,
	 * stone to gravel) lowers or keeps the top, so it cannot. Tops are in blocks from the cell's floor.
	 */
	public static boolean canTrap(boolean wasEmpty, double wasTop, boolean isEmpty, double isTop) {
		if (isEmpty) {
			return false;
		}
		return wasEmpty || isTop > wasTop + 0.125;
	}

	/** The box covering every op, or null. */
	public static Anchors.@Nullable Bounds box(List<Op> ops) {
		if (ops.isEmpty()) {
			return null;
		}
		int[] bb = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (Op o : ops) {
			bb[0] = Math.min(bb[0], o.x());
			bb[1] = Math.min(bb[1], o.y());
			bb[2] = Math.min(bb[2], o.z());
			bb[3] = Math.max(bb[3], o.x());
			bb[4] = Math.max(bb[4], o.y());
			bb[5] = Math.max(bb[5], o.z());
		}
		return new Anchors.Bounds(bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]);
	}

	/**
	 * Why a route cannot carry a road, or null: too short, too long, or not a chain of neighbouring cells that climb or
	 * drop at most one block per step.
	 */
	public static @Nullable String routeProblem(long[] route) {
		if (route.length < 2) {
			return "the route is too short";
		}
		if (route.length > MAX_ROUTE) {
			return "the route is " + route.length + " cells long (at most " + MAX_ROUTE + ")";
		}
		for (int i = 1; i < route.length; i++) {
			long a = route[i - 1];
			long b = route[i];
			int dx = WalkCell.unpackX(b) - WalkCell.unpackX(a);
			int dy = WalkCell.unpackY(b) - WalkCell.unpackY(a);
			int dz = WalkCell.unpackZ(b) - WalkCell.unpackZ(a);
			if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || Math.abs(dy) > 1 || dx == 0 && dz == 0) {
				return "the route is broken at " + WalkCell.unpackX(a) + ", " + WalkCell.unpackY(a) + ", " + WalkCell.unpackZ(a)
					+ " (each step must go to a neighbouring cell at most one block up or down)";
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ plan

	/** A column the walkway covers, before its height is settled. */
	private static final class Stamp {
		final int x;
		final int z;
		final int refY;
		boolean centre;
		/** Index of the route cell that stamped it (for lanterns and order). */
		final int index;
		// resolved
		int feet = Integer.MIN_VALUE;
		int surface;
		Role role = Role.GROUND;
		boolean accepted;

		Stamp(int x, int z, int refY, boolean centre, int index) {
			this.x = x;
			this.z = z;
			this.refY = refY;
			this.centre = centre;
			this.index = index;
		}
	}

	/**
	 * The road along {@code route} (feet cells, {@link WalkCell#pack}, in walking order).
	 *
	 * @param exclude boxes the road never touches (buildings' restore boxes)
	 * @param taken cells (packed) another road already changed
	 */
	public static Plan plan(long[] route, Options o, World w, List<Anchors.Bounds> exclude, LongPredicate taken) {
		Map<String, Integer> skipped = new LinkedHashMap<>();
		String broken = routeProblem(route);
		if (broken != null) {
			return refused(broken, skipped);
		}
		// 1. trim the route to the cells outside every excluded box (between the approach ends)
		List<int[]> centre = new ArrayList<>();
		int trimmed = 0;
		for (long c : route) {
			int x = WalkCell.unpackX(c);
			int y = WalkCell.unpackY(c);
			int z = WalkCell.unpackZ(c);
			if (excluded(exclude, x, y, z) || excluded(exclude, x, y - 1, z) || excluded(exclude, x, y + 1, z)) {
				trimmed++;
				continue;
			}
			centre.add(new int[] {x, y, z});
		}
		// 2. stamp the walkway
		LinkedHashMap<Long, Stamp> stamps = new LinkedHashMap<>();
		int[] offs = offsets(o.width());
		for (int i = 0; i < centre.size(); i++) {
			int[] c = centre.get(i);
			stamp(stamps, c[0], c[2], c[1], true, i);
			if (i == 0) {
				continue;
			}
			int[] a = centre.get(i - 1);
			int dx = c[0] - a[0];
			int dz = c[2] - a[2];
			if (Math.abs(dx) > 1 || Math.abs(dz) > 1) {
				continue; // a building in between: two stretches
			}
			if (dx != 0 && dz != 0) {
				// a diagonal step on one level: through its corner cell (standable: the planner never cuts corners)
				int cx = a[0] + dx;
				int cz = a[2];
				stamp(stamps, cx, cz, a[1], true, i);
				side(stamps, offs, a[0], a[2], a[1], 0, dx, i);
				side(stamps, offs, cx, cz, a[1], 0, dx, i);
				side(stamps, offs, cx, cz, a[1], -dz, 0, i);
				side(stamps, offs, c[0], c[2], c[1], -dz, 0, i);
			} else {
				int px = -dz;
				int pz = dx;
				side(stamps, offs, a[0], a[2], a[1], px, pz, i);
				side(stamps, offs, c[0], c[2], c[1], px, pz, i);
			}
		}
		// 3. settle each column: its height and what it is
		List<Integer> refused = new ArrayList<>();
		String unloaded = null;
		for (Stamp s : stamps.values()) {
			String why = settle(s, w, o, exclude);
			if (why == null) {
				continue;
			}
			if (why.equals("unloaded")) {
				unloaded = "chunks around " + s.x + ", " + s.z + " are not loaded (walk closer and try again)";
				break;
			}
			if (s.centre && why.equals("kept")) {
				s.accepted = true;
				s.role = Role.KEPT;
				s.feet = s.refY;
				continue;
			}
			if (!why.isEmpty()) {
				bump(skipped, why);
				mark(refused, s.x, s.refY, s.z);
			}
		}
		if (unloaded != null) {
			return refused(unloaded, skipped);
		}
		// 4. side cells more than a block off a neighbouring road cell are left out (a bank, a ditch)
		boolean changed = true;
		while (changed) {
			changed = false;
			for (Stamp s : stamps.values()) {
				if (!s.accepted || s.centre || s.role == Role.KEPT) {
					continue;
				}
				for (Stamp n : neighbours(stamps, s)) {
					if (n.accepted && Math.abs(walkFeet(n) - walkFeet(s)) > 1) {
						s.accepted = false;
						bump(skipped, "steep");
						mark(refused, s.x, s.feet, s.z);
						changed = true;
						break;
					}
				}
			}
		}
		// 5. half steps: a ground cell with a road neighbour one block higher and none lower
		for (Stamp s : stamps.values()) {
			if (!s.accepted || s.role != Role.GROUND || !ground(s.surface) || !open(w.at(s.x, s.feet + HEADROOM, s.z))) {
				continue;
			}
			boolean higher = false;
			boolean lower = false;
			for (Stamp n : neighbours(stamps, s)) {
				if (!n.accepted || n.role == Role.KEPT || n.role == Role.BRIDGE) {
					continue;
				}
				higher |= n.feet == s.feet + 1;
				lower |= n.feet == s.feet - 1;
			}
			if (higher && !lower) {
				s.role = Role.SLAB;
			}
		}
		// 6. the changes, per column; a column touching another road or a building is left out whole
		List<Op> ops = new ArrayList<>();
		List<Cell> cells = new ArrayList<>();
		for (Stamp s : stamps.values()) {
			if (!s.accepted) {
				continue;
			}
			List<Op> col = columnOps(s, w);
			String why = blockedOps(col, exclude, taken);
			if (why != null) {
				bump(skipped, why);
				mark(refused, s.x, s.feet, s.z);
				if (s.centre) {
					cells.add(new Cell(s.x, s.feet, s.z, true, Role.KEPT));
				}
				continue;
			}
			ops.addAll(col);
			cells.add(new Cell(s.x, s.feet, s.z, s.centre, s.role));
		}
		// 7. lanterns beside the walkway
		List<Integer> lanterns = new ArrayList<>();
		if (o.lanterns()) {
			lanterns(centre, stamps, o, w, exclude, taken, ops, lanterns, skipped);
		}
		int[] lan = new int[lanterns.size()];
		for (int i = 0; i < lan.length; i++) {
			lan[i] = lanterns.get(i);
		}
		Map<String, Integer> ordered = new LinkedHashMap<>();
		for (String r : REASONS) {
			Integer n = skipped.get(r);
			if (n != null && n > 0) {
				ordered.put(r, n);
			}
		}
		int[] ref = new int[refused.size()];
		for (int i = 0; i < ref.length; i++) {
			ref[i] = refused.get(i);
		}
		return new Plan(List.copyOf(ops), List.copyOf(cells), lan, ordered, ref, null, box(ops), centre.size(), trimmed);
	}

	private static Plan refused(String why, Map<String, Integer> skipped) {
		return new Plan(List.of(), List.of(), new int[0], skipped, new int[0], why, null, 0, 0);
	}

	private static void mark(List<Integer> l, int x, int y, int z) {
		l.add(x);
		l.add(y);
		l.add(z);
	}

	private static void bump(Map<String, Integer> m, String k) {
		m.merge(k, 1, Integer::sum);
	}

	private static void stamp(Map<Long, Stamp> stamps, int x, int z, int refY, boolean centre, int index) {
		long k = col(x, z);
		Stamp s = stamps.get(k);
		if (s == null) {
			stamps.put(k, new Stamp(x, z, refY, centre, index));
		} else if (centre && !s.centre) {
			stamps.put(k, new Stamp(x, z, refY, true, index)); // the route itself wins over a side stamp
		}
	}

	private static void side(Map<Long, Stamp> stamps, int[] offs, int x, int z, int refY, int px, int pz, int index) {
		for (int k : offs) {
			if (k != 0) {
				stamp(stamps, x + k * px, z + k * pz, refY, false, index);
			}
		}
	}

	private static List<Stamp> neighbours(Map<Long, Stamp> stamps, Stamp s) {
		List<Stamp> out = new ArrayList<>(4);
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			Stamp n = stamps.get(col(s.x + d[0], s.z + d[1]));
			if (n != null) {
				out.add(n);
			}
		}
		return out;
	}

	/** The feet level a walker is at on a settled cell (integer; a deck counts as its own cell). */
	private static int walkFeet(Stamp s) {
		return s.feet;
	}

	/**
	 * Settles a column: its feet height, ground kind and role. Returns null when it is part of the road, "unloaded",
	 * "kept" (a centre cell to leave as it is), "" (dropped silently) or a skip reason.
	 */
	private static @Nullable String settle(Stamp s, World w, Options o, List<Anchors.Bounds> exclude) {
		int[] tries = s.centre ? new int[] {0} : new int[] {0, 1, -1};
		String first = null; // the reason at the stamped height says the most
		for (int dy : tries) {
			String why = settleAt(s, s.refY + dy, w, o, exclude);
			if (why == null || why.equals("unloaded")) {
				return why;
			}
			if (first == null && !why.isEmpty()) {
				first = why;
			}
		}
		return first == null ? "" : first;
	}

	/** {@link #settle} at feet height {@code y}: null when the column is a road cell there, else why not. */
	private static @Nullable String settleAt(Stamp s, int y, World w, Options o, List<Anchors.Bounds> exclude) {
		int below = w.at(s.x, y - 1, s.z);
		int feet = w.at(s.x, y, s.z);
		int head = w.at(s.x, y + 1, s.z);
		if (below == UNLOADED || feet == UNLOADED || head == UNLOADED) {
			return "unloaded";
		}
		if (excluded(exclude, s.x, y - 1, s.z) || excluded(exclude, s.x, y, s.z) || excluded(exclude, s.x, y + 1, s.z)) {
			return "building";
		}
		if (feet == WATER) {
			// shallow: a floor below the water, open air above it (deeper water is never on a route)
			if (!floor(below)) {
				return "deep";
			}
			if (!o.bridge()) {
				return "water";
			}
			int a1 = w.at(s.x, y + 2, s.z);
			int a2 = w.at(s.x, y + 3, s.z);
			if (a1 == UNLOADED || a2 == UNLOADED) {
				return "unloaded";
			}
			// the deck goes into the cell above the water (head), with headroom above it
			if (!open(head) || !open(a1) || !open(a2) || excluded(exclude, s.x, y + 3, s.z)) {
				return "blocked";
			}
			s.feet = y + 1;
			s.surface = WATER;
			s.role = Role.BRIDGE;
			s.accepted = true;
			return null;
		}
		if (!open(feet) || !open(head)) {
			return s.centre && (feet == BUILT || feet == BUILT_OPEN) ? "kept" : "blocked";
		}
		if (!floor(below)) {
			return s.centre ? "kept" : below == LOG || below == LEAVES || below == BLOCK_ENTITY ? "blocked" : "";
		}
		if (below == BUILT && !s.centre) {
			return "built";
		}
		// on the player's own floor (a centre cell) the floor stays: only plants above it are cleared
		s.feet = y;
		s.surface = below;
		s.role = Role.GROUND;
		s.accepted = true;
		return null;
	}

	/** The ops of a settled column, bottom to top. */
	private static List<Op> columnOps(Stamp s, World w) {
		List<Op> ops = new ArrayList<>();
		int x = s.x;
		int z = s.z;
		switch (s.role) {
			case KEPT -> {
				return ops;
			}
			case BRIDGE -> {
				int deck = s.feet; // the cell above the water
				ops.add(new Op(x, deck, z, Block.DECK, w.at(x, deck, z)));
				clearUp(ops, w, x, deck + 1, deck + HEADROOM, z);
			}
			case SLAB -> {
				// ground kept as it is (a dirt path under a slab turns to dirt), a slab in the feet cell
				// (a tall plant's lower half there: the rest of its stack is cleared above)
				ops.add(new Op(x, s.feet, z, slabFor(s.surface), w.at(x, s.feet, z)));
				clearUp(ops, w, x, s.feet + 1, s.feet + HEADROOM, z);
			}
			default -> {
				Block surface = ground(s.surface) ? surfaceFor(s.surface, !free(w.at(x, s.feet - 2, z))) : null;
				if (surface != null) {
					ops.add(new Op(x, s.feet - 1, z, surface, s.surface));
				}
				clearUp(ops, w, x, s.feet, s.feet + HEADROOM - 1, z);
			}
		}
		return ops;
	}

	/**
	 * Clears the clearable cells from {@code y0} to {@code y1}, then the rest of any tall plant's stack above (so
	 * nothing floats); a cell that is not clearable ends the run.
	 */
	private static void clearUp(List<Op> ops, World w, int x, int y0, int y1, int z) {
		int y = y0;
		for (; y <= y1; y++) {
			int k = w.at(x, y, z);
			if (k == AIR) {
				continue;
			}
			if (!clearable(k)) {
				return;
			}
			ops.add(new Op(x, y, z, Block.AIR, k));
		}
		// a stack reaching above the headroom (sugar cane, bamboo, the top half of a tall flower) goes too
		if (y1 >= y0 && w.at(x, y1, z) == STACK) {
			for (int n = 0; n < STACK_MAX; n++, y++) {
				int k = w.at(x, y, z);
				if (k != STACK) {
					return;
				}
				ops.add(new Op(x, y, z, Block.AIR, k));
			}
		}
	}

	/** Why a column's ops may not be made (an op inside a building, on another road's cell), or null. */
	private static @Nullable String blockedOps(List<Op> ops, List<Anchors.Bounds> exclude, LongPredicate taken) {
		for (Op op : ops) {
			if (excluded(exclude, op.x(), op.y(), op.z())) {
				return "building";
			}
			if (taken.test(WalkCell.pack(op.x(), op.y(), op.z()))) {
				return "road";
			}
		}
		return null;
	}

	private static void lanterns(List<int[]> centre, Map<Long, Stamp> stamps, Options o, World w, List<Anchors.Bounds> exclude, LongPredicate taken,
		List<Op> ops, List<Integer> out, Map<String, Integer> skipped) {
		int[] offs = offsets(o.width());
		int hi = offs[offs.length - 1];
		int lo = offs[0];
		Set<Long> posts = new HashSet<>();
		double since = LANTERN_SPACING - LANTERN_FIRST;
		int i = 1;
		while (i < centre.size()) {
			int[] a = centre.get(i - 1);
			int[] b = centre.get(i);
			int dx = b[0] - a[0];
			int dz = b[2] - a[2];
			since += Math.abs(dx) > 1 || Math.abs(dz) > 1 ? 0 : dx != 0 && dz != 0 ? Math.sqrt(2) : 1;
			if (since + 1e-9 < LANTERN_SPACING || i >= centre.size() - 2) {
				i++;
				continue;
			}
			// try this centre cell, then a few further along
			boolean placed = false;
			for (int j = i; j < Math.min(centre.size() - 1, i + 4) && !placed; j++) {
				int[] c = centre.get(j);
				int[] n = centre.get(j + 1);
				int sdx = Integer.signum(n[0] - c[0]);
				int sdz = Integer.signum(n[2] - c[2]);
				if (sdx != 0 && sdz != 0) {
					sdz = 0; // a diagonal: beside its x step
				}
				int px = -sdz;
				int pz = sdx;
				for (int side : new int[] {hi + 1, lo - 1}) {
					int qx = c[0] + side * px;
					int qz = c[2] + side * pz;
					if (stamps.containsKey(col(qx, qz)) || posts.contains(col(qx, qz))) {
						continue;
					}
					List<Op> post = post(qx, qz, c[1], w, exclude, taken);
					if (post != null) {
						ops.addAll(post);
						Op lantern = post.get(post.size() - 1);
						out.add(lantern.x());
						out.add(lantern.y());
						out.add(lantern.z());
						posts.add(col(qx, qz));
						placed = true;
						i = j;
						break;
					}
				}
			}
			if (placed) {
				since = 0;
			} else {
				bump(skipped, "lantern");
				since = LANTERN_SPACING / 2; // try again half a spacing on
			}
			i++;
		}
	}

	/** A fence post with a lantern in column (x, z) near feet height {@code refY}, or null when there is no room. */
	private static @Nullable List<Op> post(int x, int z, int refY, World w, List<Anchors.Bounds> exclude, LongPredicate taken) {
		for (int dy : new int[] {0, 1, -1}) {
			int y = refY + dy;
			int below = w.at(x, y - 1, z);
			int f = w.at(x, y, z);
			int l = w.at(x, y + 1, z);
			int above = w.at(x, y + 2, z);
			if (!ground(below) || !open(f) || !open(l) || above == UNLOADED) {
				continue;
			}
			if (f == STACK || l == STACK || above == STACK) {
				continue; // never half a tall plant
			}
			List<Op> ops = new ArrayList<>();
			ops.add(new Op(x, y, z, Block.FENCE, f));
			ops.add(new Op(x, y + 1, z, Block.LANTERN, l));
			if (blockedOps(ops, exclude, taken) != null || excluded(exclude, x, y - 1, z)) {
				continue;
			}
			return ops;
		}
		return null;
	}
}
