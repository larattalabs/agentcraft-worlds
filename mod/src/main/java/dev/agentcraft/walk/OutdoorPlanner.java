package dev.agentcraft.walk;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.LongPredicate;
import org.jspecify.annotations.Nullable;

/**
 * One outdoor route between two building entrances (docs/WAVE2.md W8): A* over {@link WalkCell} codes, run
 * incrementally ({@link #step} does a bounded amount of work and returns), so a long search spreads over
 * client ticks and never blocks a frame. Pure: no game classes, unit-tested on synthetic terrain.
 *
 * <p>Rules: a cell is standable per {@link WalkCell#floor}; moves go to the 8 neighbours (diagonals only on
 * one level and never cutting a corner), step up at most 1 block (with headroom to do it), drop at most 3 ({@link Limits#maxDrop}: 1 for roads)
 * (the column above the landing open), so cliffs higher than 3 are never taken; water is waded (1 deep) at
 * a cost, doors and gates are passed through. The search stays inside the endpoints' box padded by
 * {@link Limits#pad} and gives up after {@link Limits#maxNodes} expansions. A route that touched unloaded
 * chunks and found nothing reports {@link Status#UNLOADED} (the caller teleports instead).
 *
 * <p>The cell path is string-pulled (line of sight on one level, the agent's 0.6 width) into straight
 * segments, also incrementally: from each point the farthest point within {@link Limits#smoothAhead} in plain sight.
 */
public final class OutdoorPlanner {
	/** The outcome so far. Everything but {@link #RUNNING} is final. */
	public enum Status {
		RUNNING, FOUND, NO_START, NO_GOAL, NO_PATH, UNLOADED, BUDGET, TOO_FAR;

		public String wire() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** A feet position. */
	public record Point(double x, double y, double z) {
		public double distanceTo(Point o) {
			double dx = o.x - x;
			double dy = o.y - y;
			double dz = o.z - z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	/**
	 * @param maxNodes expansions before giving up ({@link Status#BUDGET}), over all widenings
	 * @param pad horizontal blocks the search may stray outside the endpoints' box (vertical: {@code 2 * pad} / 3)
	 * @param maxDistance horizontal distance between the endpoints beyond which nothing is searched
	 * @param smoothAhead the longest straight segment (in path cells) string pulling tries
	 * @param maxPad when the search runs out of cells after pressing against its box's edge, it starts again with the
	 *               pad doubled, up to this (a ridge or a lake wider than the box is walked around)
	 * @param maxDrop the deepest drop a step may take (3 for agents; 1 for a road, so it is walkable both ways)
	 */
	public record Limits(int maxNodes, int pad, int maxDistance, int smoothAhead, int maxPad, int maxDrop) {
		public static final Limits DEFAULT = new Limits(120_000, 32, 256, 24, 128, 3);
		/** A road's route (docs/VILLAGE.md V1): as {@link #DEFAULT}, but no step drops more than a block. */
		public static final Limits ROAD = DEFAULT.withMaxDrop(1);

		public Limits {
			if (maxDrop < 1 || maxDrop > 3) {
				throw new IllegalArgumentException("maxDrop must be 1..3");
			}
		}

		public Limits(int maxNodes, int pad, int maxDistance, int smoothAhead, int maxPad) {
			this(maxNodes, pad, maxDistance, smoothAhead, maxPad, 3);
		}

		public Limits(int maxNodes, int pad, int maxDistance, int smoothAhead) {
			this(maxNodes, pad, maxDistance, smoothAhead, pad);
		}

		public Limits withMaxDrop(int d) {
			return new Limits(maxNodes, pad, maxDistance, smoothAhead, maxPad, d);
		}
	}

	/**
	 * A step onto a laid road (docs/VILLAGE.md V1) costs this much of a step elsewhere, so routes prefer roads. With roads,
	 * the heuristic is scaled by it too (it stays an estimate of the cheapest way, or the search would rush past them).
	 */
	public static final double ROAD_FACTOR = 0.6;

	private static final double SQRT2 = Math.sqrt(2);
	/** Heuristic weight: slightly greedy (routes within a few % of the shortest, far fewer expansions). */
	private static final double H_WEIGHT = 1.08;
	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
	private static final int[] DYS = {0, 1, -1, -2, -3};
	private static final double UNKNOWN = -1e9;

	private record Node(int x, int y, int z, double g, double f, @Nullable Node parent) {
	}

	private final Terrain terrain;
	private final Limits limits;
	/** Feet cells ({@link WalkCell#pack}) on a laid road, or null. */
	private final @Nullable LongPredicate roads;
	private final double hWeight;
	private final Point from;
	private final Point to;
	private final Long2IntOpenHashMap codes = new Long2IntOpenHashMap();
	private final Long2DoubleOpenHashMap floors = new Long2DoubleOpenHashMap();
	private final Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
	private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> a.f != b.f ? Double.compare(a.f, b.f) : Double.compare(b.g, a.g));
	private int minX;
	private int maxX;
	private int minY;
	private int maxY;
	private int minZ;
	private int maxZ;
	private int gx;
	private int gy;
	private int gz;
	private Status status = Status.RUNNING;
	private int phase;
	private int expanded;
	private int unloadedHits;
	private int pad;
	private int widenings;
	/** Neighbours skipped because they lie outside the search box (this round). */
	private int edgeHits;
	/** The expanded node nearest the goal (horizontal distance), for explaining a failure. */
	private int closeX;
	private int closeY;
	private int closeZ;
	private double closeDist = Double.MAX_VALUE;
	/** The horizontal box of every expanded cell (min x, min z, max x, max z), for explaining a failure. */
	private final int[] reached = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
	/** Start / goal cells, once found. */
	private int sx;
	private int sy;
	private int sz;
	private long nanos;
	private long worstStep;
	private int steps;
	// smoothing
	private final List<Point> raw = new ArrayList<>();
	private final List<Long> rawCells = new ArrayList<>();
	private final List<Point> smooth = new ArrayList<>();
	private int si;
	private @Nullable List<Point> path;
	private long @Nullable [] cells;

	public OutdoorPlanner(Terrain terrain, Point from, Point to, Limits limits) {
		this(terrain, from, to, limits, null);
	}

	/** With {@code roads} (feet cells on a laid road): steps onto them cost {@link #ROAD_FACTOR}. */
	public OutdoorPlanner(Terrain terrain, Point from, Point to, Limits limits, @Nullable LongPredicate roads) {
		this.terrain = terrain;
		this.from = from;
		this.to = to;
		this.limits = limits;
		this.roads = roads;
		this.hWeight = roads == null ? H_WEIGHT : H_WEIGHT * ROAD_FACTOR;
		this.pad = limits.pad();
		floors.defaultReturnValue(UNKNOWN);
		best.defaultReturnValue(Double.MAX_VALUE);
		codes.defaultReturnValue(Integer.MIN_VALUE);
	}

	public OutdoorPlanner(Terrain terrain, Point from, Point to) {
		this(terrain, from, to, Limits.DEFAULT);
	}

	public Status status() {
		return status;
	}

	public boolean done() {
		return status != Status.RUNNING;
	}

	/** Nodes expanded so far. */
	public int expanded() {
		return expanded;
	}

	/** Time spent in {@link #step} so far (microseconds). */
	public long micros() {
		return nanos / 1000;
	}

	/** The longest single {@link #step} call so far (microseconds): the planner's worst cost in one tick. */
	public long worstStepMicros() {
		return worstStep / 1000;
	}

	/** {@link #step} calls so far (= ticks when the caller steps once per tick). */
	public int steps() {
		return steps;
	}

	/** Lookups that hit an unloaded chunk. */
	public int unloadedHits() {
		return unloadedHits;
	}

	/** The search box's horizontal pad now (it doubles when a search presses against the box's edge and fails). */
	public int pad() {
		return pad;
	}

	/** How often the search started again in a wider box. */
	public int widenings() {
		return widenings;
	}

	/** Neighbours the last round skipped because they were outside the search box. */
	public int edgeHits() {
		return edgeHits;
	}

	/** Horizontal distance from the expanded cell nearest the goal to the goal ({@link Double#MAX_VALUE} before any). */
	public double closest() {
		return closeDist;
	}

	/** The expanded cell nearest the goal (x, y, z), or null before any. */
	public int @Nullable [] closestCell() {
		return closeDist == Double.MAX_VALUE ? null : new int[] {closeX, closeY, closeZ};
	}

	/**
	 * Why a finished search found nothing, in words (for dev.walk.state and the log), or null while running / found:
	 * how far it got, whether the start was enclosed, whether it pressed against its box, unloaded chunks.
	 */
	public @Nullable String explain() {
		return switch (status) {
			case RUNNING, FOUND -> null;
			case TOO_FAR -> "entrances more than " + limits.maxDistance() + " blocks apart";
			case NO_START -> "no standable cell at the start " + fmt(from) + " (its door or step is blocked)";
			case NO_GOAL -> "no standable cell at the goal " + fmt(to) + " (its door or step is blocked)";
			case UNLOADED -> "the route needs chunks the client has not loaded (" + unloadedHits + " lookups)";
			case BUDGET -> "searched " + expanded + " cells (the budget) and got within " + Math.round(closeDist) + " blocks of the goal at "
				+ cell(closeX, closeY, closeZ) + "; a long detour (a ridge, a lake, a cliff band) or a maze of trees";
			case NO_PATH -> {
				String where = closeDist == Double.MAX_VALUE ? "" : ", got within " + Math.round(closeDist) + " blocks of the goal at "
					+ cell(closeX, closeY, closeZ);
				if (expanded < 64) {
					yield "the start is walled in: only " + expanded + " cells reachable from " + cell(sx, sy, sz) + where;
				}
				yield "every reachable cell searched (" + expanded + ", x " + reached[0] + ".." + reached[2] + " z " + reached[1] + ".." + reached[3] + ")" + where + (edgeHits > 0 ? "; the search box (pad " + pad
					+ ") was the limit " + edgeHits + " times" : "; the goal's side is cut off (a cliff over 3 blocks, a step over 1, water deeper than 1)");
			}
		};
	}

	private static String fmt(Point p) {
		return cell((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
	}

	private static String cell(int x, int y, int z) {
		return "(" + x + ", " + y + ", " + z + ")";
	}

	/** The smoothed route (first = from, last = to exactly) once {@link Status#FOUND}, else null. */
	public @Nullable List<Point> path() {
		return path;
	}

	/** Every cell of the raw route ({@link WalkCell#pack}), for invalidation, once found. */
	public long @Nullable [] cells() {
		return cells;
	}

	/** Length of the smoothed route (blocks, 3D), 0 until found. */
	public double length() {
		List<Point> p = path;
		if (p == null) {
			return 0;
		}
		double d = 0;
		for (int i = 1; i < p.size(); i++) {
			d += p.get(i).distanceTo(p.get(i - 1));
		}
		return d;
	}

	/** Runs to completion (tests, dev). */
	public Status runAll() {
		while (step(Integer.MAX_VALUE, Long.MAX_VALUE) == Status.RUNNING) {
			// keeps going
		}
		return status;
	}

	/**
	 * Does at most {@code maxWork} units of work (one expansion or one smoothing segment each) and stops at
	 * {@code deadlineNanos} ({@link System#nanoTime}), whichever comes first. Returns the status.
	 */
	public Status step(int maxWork, long deadlineNanos) {
		if (status != Status.RUNNING) {
			return status;
		}
		long t0 = System.nanoTime();
		steps++;
		try {
			if (phase == 0) {
				init();
				if (status != Status.RUNNING) {
					return status;
				}
				phase = 1;
			}
			int work = 0;
			while (status == Status.RUNNING && work < maxWork) {
				if (phase == 1) {
					searchOne();
				} else {
					smoothOne();
				}
				work++;
				// a smoothing unit tries up to smoothAhead line-of-sight checks (hundreds of lookups): check the clock
				// after each; expansions are cheap, so every 32
				if ((phase == 2 || (work & 31) == 0) && System.nanoTime() >= deadlineNanos) {
					break;
				}
			}
			return status;
		} finally {
			long dt = System.nanoTime() - t0;
			nanos += dt;
			worstStep = Math.max(worstStep, dt);
		}
	}

	private void init() {
		double hdx = to.x - from.x;
		double hdz = to.z - from.z;
		if (Math.sqrt(hdx * hdx + hdz * hdz) > limits.maxDistance()) {
			status = Status.TOO_FAR;
			return;
		}
		int fx = (int) Math.floor(from.x);
		int fz = (int) Math.floor(from.z);
		int tx = (int) Math.floor(to.x);
		int tz = (int) Math.floor(to.z);
		int fy = WalkCell.cellY(from.y);
		int ty = WalkCell.cellY(to.y);
		int vpad = Math.max(6, pad * 2 / 3);
		minX = Math.min(fx, tx) - pad;
		maxX = Math.max(fx, tx) + pad;
		minZ = Math.min(fz, tz) - pad;
		maxZ = Math.max(fz, tz) + pad;
		minY = Math.min(fy, ty) - vpad;
		maxY = Math.max(fy, ty) + vpad;
		int[] s = cellAt(fx, fy, fz);
		int[] g = cellAt(tx, ty, tz);
		if (s == null) {
			status = unloadedHits > 0 ? Status.UNLOADED : Status.NO_START;
			return;
		}
		if (g == null) {
			status = unloadedHits > 0 ? Status.UNLOADED : Status.NO_GOAL;
			return;
		}
		gx = g[0];
		gy = g[1];
		gz = g[2];
		sx = s[0];
		sy = s[1];
		sz = s[2];
		best.put(WalkCell.pack(s[0], s[1], s[2]), 0.0);
		open.add(new Node(s[0], s[1], s[2], 0, h(s[0], s[1], s[2]), null));
	}

	/** The standable cell at (x, y±1, z), or null (as {@code GridPathfinder.cellAt}). */
	private int @Nullable [] cellAt(int x, int y, int z) {
		for (int dy : new int[] {0, 1, -1}) {
			if (!Double.isNaN(floor(x, y + dy, z))) {
				return new int[] {x, y + dy, z};
			}
		}
		return null;
	}

	private int code(int x, int y, int z) {
		long k = WalkCell.pack(x, y, z);
		int c = codes.get(k);
		if (c == Integer.MIN_VALUE) {
			c = terrain.at(x, y, z);
			codes.put(k, c);
			if (c == WalkCell.UNLOADED) {
				unloadedHits++;
			}
		}
		return c;
	}

	private final Terrain cached = this::code;

	/** Feet height of a standable cell inside the search box, else NaN (cached). */
	double floor(int x, int y, int z) {
		if (x < minX || x > maxX || z < minZ || z > maxZ || y < minY || y > maxY) {
			return Double.NaN;
		}
		long k = WalkCell.pack(x, y, z);
		double v = floors.get(k);
		if (v == UNKNOWN) {
			v = WalkCell.floor(cached, x, y, z);
			floors.put(k, v);
		}
		return v;
	}

	private double h(int x, int y, int z) {
		int dx = Math.abs(x - gx);
		int dz = Math.abs(z - gz);
		return hWeight * (Math.max(dx, dz) + (SQRT2 - 1) * Math.min(dx, dz)) + 0.1 * Math.abs(y - gy);
	}

	private void searchOne() {
		Node n = open.poll();
		if (n == null) {
			if (edgeHits > 0 && pad < limits.maxPad() && unloadedHits == 0) {
				widen();
				return;
			}
			status = unloadedHits > 0 ? Status.UNLOADED : Status.NO_PATH;
			return;
		}
		if (n.g > best.get(WalkCell.pack(n.x, n.y, n.z)) + 1e-9) {
			return; // a stale entry
		}
		if (n.x == gx && n.y == gy && n.z == gz) {
			found(n);
			return;
		}
		if (expanded >= limits.maxNodes()) {
			status = Status.BUDGET;
			return;
		}
		expanded++;
		reached[0] = Math.min(reached[0], n.x);
		reached[1] = Math.min(reached[1], n.z);
		reached[2] = Math.max(reached[2], n.x);
		reached[3] = Math.max(reached[3], n.z);
		double hd = Math.hypot(n.x - gx, n.z - gz);
		if (hd < closeDist) {
			closeDist = hd;
			closeX = n.x;
			closeY = n.y;
			closeZ = n.z;
		}
		double f0 = floor(n.x, n.y, n.z);
		boolean roomToStepUp = WalkCell.headroom(code(n.x, n.y + 2, n.z));
		for (int[] d : DIRS) {
			boolean diagonal = d[0] != 0 && d[1] != 0;
			int nx = n.x + d[0];
			int nz = n.z + d[1];
			if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
				edgeHits++;
				continue;
			}
			if (diagonal && (Double.isNaN(floor(n.x + d[0], n.y, n.z)) || Double.isNaN(floor(n.x, n.y, n.z + d[1])))) {
				continue; // never cut corners
			}
			for (int dy : DYS) {
				if (diagonal && dy != 0) {
					break; // diagonals only on one level
				}
				if (dy == 1 && !roomToStepUp) {
					continue;
				}
				int ny = n.y + dy;
				double f1 = floor(nx, ny, nz);
				if (Double.isNaN(f1)) {
					continue;
				}
				double rise = f1 - f0;
				if (rise > 1.0 + 1e-6 || rise < -limits.maxDrop() - 1e-6) {
					break;
				}
				if (rise > 0.5 + 1e-6 && !roomToStepUp) {
					break; // a full step up needs headroom
				}
				if (dy < 0 && !dropClear(nx, ny, nz, n.y)) {
					break;
				}
				int feet = code(nx, ny, nz);
				double step = (diagonal ? SQRT2 : 1.0) * (roads != null && roads.test(WalkCell.pack(nx, ny, nz)) ? ROAD_FACTOR : 1.0);
				double cost = step + (rise > 0.01 ? 0.5 * rise : 0) + (rise < -0.01 ? 0.25 * -rise : 0)
					+ (feet == WalkCell.WATER ? 1.5 : 0) + (feet == WalkCell.DOOR || code(nx, ny + 1, nz) == WalkCell.DOOR ? 0.3 : 0)
					+ (code(nx, ny + 1, nz) == WalkCell.LEAVES ? 0.6 : 0);
				double g = n.g + cost;
				long key = WalkCell.pack(nx, ny, nz);
				if (g < best.get(key) - 1e-9) {
					best.put(key, g);
					open.add(new Node(nx, ny, nz, g, g + h(nx, ny, nz), n));
				}
				break; // one landing per column (the standable cells of a column are 3+ apart)
			}
		}
	}

	/**
	 * The open set ran dry against the search box's edge: start again with the pad doubled (the terrain lookups stay
	 * cached; the floors outside the old box are recomputed). The expansions so far count towards the budget.
	 */
	private void widen() {
		pad = Math.min(limits.maxPad(), pad * 2);
		widenings++;
		edgeHits = 0;
		floors.clear();
		best.clear();
		open.clear();
		int fx = (int) Math.floor(from.x);
		int fz = (int) Math.floor(from.z);
		int tx = (int) Math.floor(to.x);
		int tz = (int) Math.floor(to.z);
		int vpad = Math.max(6, pad * 2 / 3);
		minX = Math.min(fx, tx) - pad;
		maxX = Math.max(fx, tx) + pad;
		minZ = Math.min(fz, tz) - pad;
		maxZ = Math.max(fz, tz) + pad;
		minY = Math.min(sy, gy) - vpad;
		maxY = Math.max(sy, gy) + vpad;
		best.put(WalkCell.pack(sx, sy, sz), 0.0);
		open.add(new Node(sx, sy, sz, 0, h(sx, sy, sz), null));
	}

	/** Stepping off a ledge into column (x,z): every cell from above the landing's head up to our head is open. */
	private boolean dropClear(int x, int landingY, int z, int fromY) {
		for (int y = landingY + 2; y <= fromY + 1; y++) {
			if (!WalkCell.fallThrough(code(x, y, z))) {
				return false;
			}
		}
		return true;
	}

	private void found(Node goal) {
		List<Node> nodes = new ArrayList<>();
		for (Node n = goal; n != null; n = n.parent) {
			nodes.add(n);
		}
		java.util.Collections.reverse(nodes);
		raw.add(from);
		for (int i = 0; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			rawCells.add(WalkCell.pack(n.x, n.y, n.z));
			if (i > 0 && i < nodes.size() - 1) {
				raw.add(new Point(n.x + 0.5, floor(n.x, n.y, n.z), n.z + 0.5));
			}
			if (i < nodes.size() - 1) {
				// a step up or a drop: walk to the edge of this cell on its own level first, so the agent climbs or
				// drops at the edge instead of gliding through the block's corner
				Node m = nodes.get(i + 1);
				double f = floor(n.x, n.y, n.z);
				if (Math.abs(floor(m.x, m.y, m.z) - f) > 0.3) {
					raw.add(new Point(n.x + 0.5 + 0.42 * (m.x - n.x), f, n.z + 0.5 + 0.42 * (m.z - n.z)));
				}
			}
		}
		raw.add(to);
		smooth.add(raw.getFirst());
		si = 0;
		phase = 2;
	}

	private void smoothOne() {
		int last = raw.size() - 1;
		if (si >= last) {
			path = List.copyOf(smooth);
			long[] c = new long[rawCells.size()];
			for (int i = 0; i < c.length; i++) {
				c[i] = rawCells.get(i);
			}
			cells = c;
			status = Status.FOUND;
			return;
		}
		// the farthest point within smoothAhead in plain sight, tried from the far end down: one check on a long straight
		// stretch instead of re-checking the growing segment for every point (that was quadratic: ~10 ms in one tick)
		int j = si + 1;
		for (int k = Math.min(last, si + limits.smoothAhead()); k > si + 1; k--) {
			if (clear(raw.get(si), raw.get(k))) {
				j = k;
				break;
			}
		}
		smooth.add(raw.get(j));
		si = j;
	}

	/** Can an agent (0.6 wide) walk the straight segment a->b on one level? (as {@code GridPathfinder.clear}) */
	boolean clear(Point a, Point b) {
		if (Math.abs(a.y - b.y) > 0.2) {
			return false;
		}
		double len = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
		int samples = Math.max(1, (int) Math.ceil(len / 0.25));
		int y = WalkCell.cellY(a.y);
		double nx = len == 0 ? 0 : -(b.z - a.z) / len * 0.3;
		double nz = len == 0 ? 0 : (b.x - a.x) / len * 0.3;
		for (int s = 0; s <= samples; s++) {
			double t = (double) s / samples;
			double px = a.x + (b.x - a.x) * t;
			double pz = a.z + (b.z - a.z) * t;
			if (!level(px, y, pz, a.y) || !level(px + nx, y, pz + nz, a.y) || !level(px - nx, y, pz - nz, a.y)) {
				return false;
			}
		}
		return true;
	}

	private boolean level(double px, int y, double pz, double feet) {
		double f = floor((int) Math.floor(px), y, (int) Math.floor(pz));
		return !Double.isNaN(f) && Math.abs(f - feet) <= 0.2;
	}

	/** True when every cell of a cached route is still standable (a cache entry is checked before reuse). */
	public static boolean stillWalkable(Terrain t, long[] cells) {
		for (long c : cells) {
			if (Double.isNaN(WalkCell.floor(t, WalkCell.unpackX(c), WalkCell.unpackY(c), WalkCell.unpackZ(c)))) {
				return false;
			}
		}
		return true;
	}
}
