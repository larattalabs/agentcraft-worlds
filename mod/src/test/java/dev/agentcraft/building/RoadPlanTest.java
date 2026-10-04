package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.building.RoadPlan.Block;
import dev.agentcraft.building.RoadPlan.Cell;
import dev.agentcraft.building.RoadPlan.Op;
import dev.agentcraft.building.RoadPlan.Options;
import dev.agentcraft.building.RoadPlan.Plan;
import dev.agentcraft.building.RoadPlan.Role;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.walk.WalkCell;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Roads derived from route cells (docs/VILLAGE.md V1) on synthetic terrain. */
class RoadPlanTest {
	/** Natural ground: stone below the top, the top block of {@code top(x, z)} kind DIRT, air above; overrides per cell. */
	static final class Grid implements RoadPlan.World {
		interface Top {
			int at(int x, int z);
		}

		final Top top;
		int surface = RoadPlan.DIRT;
		final Map<Long, Integer> set = new HashMap<>();

		Grid(Top top) {
			this.top = top;
		}

		static Grid flat() {
			return new Grid((x, z) -> 64);
		}

		Grid set(int x, int y, int z, int kind) {
			set.put(WalkCell.pack(x, y, z), kind);
			return this;
		}

		@Override
		public int at(int x, int y, int z) {
			Integer k = set.get(WalkCell.pack(x, y, z));
			if (k != null) {
				return k;
			}
			int t = top.at(x, z);
			return y < t ? RoadPlan.STONE : y == t ? surface : RoadPlan.AIR;
		}
	}

	static final Options W1 = new Options(1, false, false);
	static final Options W2 = new Options(2, false, false);
	static final Options W3 = new Options(3, false, false);

	/** A route of feet cells (x, y, z triples). */
	static long[] route(int... xyz) {
		long[] r = new long[xyz.length / 3];
		for (int i = 0; i < r.length; i++) {
			r[i] = WalkCell.pack(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]);
		}
		return r;
	}

	/** A straight route along x at z, feet y. */
	static long[] alongX(int x0, int x1, int y, int z) {
		List<Integer> l = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			l.add(x);
			l.add(y);
			l.add(z);
		}
		return route(l.stream().mapToInt(Integer::intValue).toArray());
	}

	static Plan plan(long[] r, Options o, RoadPlan.World w) {
		return RoadPlan.plan(r, o, w, List.of(), c -> false);
	}

	static Set<Long> columns(Plan p) {
		Set<Long> s = new HashSet<>();
		for (Cell c : p.cells()) {
			s.add(RoadPlan.col(c.x(), c.z()));
		}
		return s;
	}

	/** Every road cell reaches every other through side-by-side road cells (no corner-only joins). */
	static void assertConnected(Plan p) {
		Set<Long> cols = columns(p);
		assertFalse(cols.isEmpty());
		Set<Long> seen = new HashSet<>();
		ArrayDeque<Long> q = new ArrayDeque<>();
		Long first = cols.iterator().next();
		q.add(first);
		seen.add(first);
		while (!q.isEmpty()) {
			long c = q.poll();
			int x = (int) (c >> 32);
			int z = (int) c;
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				long n = RoadPlan.col(x + d[0], z + d[1]);
				if (cols.contains(n) && seen.add(n)) {
					q.add(n);
				}
			}
		}
		assertEquals(cols.size(), seen.size(), "the walkway falls apart");
	}

	/** What a walker stands at on each road cell: feet + 0.5 on a slab or deck. */
	static Map<Long, Double> heights(Plan p) {
		Map<Long, Double> h = new HashMap<>();
		for (Cell c : p.cells()) {
			h.put(RoadPlan.col(c.x(), c.z()), c.feetY() + (c.role() == Role.SLAB || c.role() == Role.BRIDGE ? 0.5 : 0));
		}
		return h;
	}

	static void assertGentle(Plan p) {
		Map<Long, Double> h = heights(p);
		h.forEach((c, y) -> {
			int x = (int) (c >> 32);
			int z = (int) (long) c;
			for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
				Double n = h.get(RoadPlan.col(x + d[0], z + d[1]));
				if (n != null) {
					assertTrue(Math.abs(n - y) <= 1.0 + 1e-9, "a step of " + Math.abs(n - y) + " at " + x + ", " + z);
				}
			}
		});
	}

	static List<Op> at(Plan p, int x, int z) {
		List<Op> out = new ArrayList<>();
		for (Op o : p.ops()) {
			if (o.x() == x && o.z() == z) {
				out.add(o);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ geometry

	@Test
	void offsetsCentreTheWalkway() {
		assertEquals(List.of(0), toList(RoadPlan.offsets(1)));
		assertEquals(List.of(0, 1), toList(RoadPlan.offsets(2)));
		assertEquals(List.of(-1, 0, 1), toList(RoadPlan.offsets(3)));
	}

	static List<Integer> toList(int[] a) {
		List<Integer> l = new ArrayList<>();
		for (int v : a) {
			l.add(v);
		}
		return l;
	}

	@Test
	void straightRoadHasItsWidth() {
		for (int w = 1; w <= 3; w++) {
			Plan p = plan(alongX(0, 20, 65, 0), new Options(w, false, false), Grid.flat());
			assertNull(p.refusal());
			assertEquals(21 * w, p.cells().size(), "width " + w);
			assertEquals(21 * w, p.count(Block.DIRT_PATH));
			assertEquals(21, p.centre());
			for (Op o : p.ops()) {
				assertEquals(64, o.y(), "the path replaces the top block");
			}
		}
		// width 2 goes to the right of the walking direction (+z when walking +x), width 3 either side
		assertTrue(columns(plan(alongX(0, 5, 65, 0), W2, Grid.flat())).contains(RoadPlan.col(3, 1)));
		Set<Long> w3 = columns(plan(alongX(0, 5, 65, 0), W3, Grid.flat()));
		assertTrue(w3.contains(RoadPlan.col(3, 1)) && w3.contains(RoadPlan.col(3, -1)));
	}

	@Test
	void cornersAndDiagonalsStayConnected() {
		// an L: along x, then along z
		List<Integer> l = new ArrayList<>();
		for (int x = 0; x <= 10; x++) {
			l.addAll(List.of(x, 65, 0));
		}
		for (int z = 1; z <= 10; z++) {
			l.addAll(List.of(10, 65, z));
		}
		long[] lr = route(l.stream().mapToInt(Integer::intValue).toArray());
		for (int w = 1; w <= 3; w++) {
			Plan p = plan(lr, new Options(w, false, false), Grid.flat());
			assertConnected(p);
			assertTrue(p.cells().size() >= 19 * w, "width " + w + ": " + p.cells().size());
		}
		// a diagonal: each step goes through a corner cell, so even width 1 is walkable side by side
		List<Integer> d = new ArrayList<>();
		for (int i = 0; i <= 10; i++) {
			d.addAll(List.of(i, 65, i));
		}
		long[] dr = route(d.stream().mapToInt(Integer::intValue).toArray());
		Plan p1 = plan(dr, W1, Grid.flat());
		assertConnected(p1);
		assertEquals(21, p1.cells().size());
		for (int w = 2; w <= 3; w++) {
			assertConnected(plan(dr, new Options(w, false, false), Grid.flat()));
		}
	}

	@Test
	void slopesClimbInHalfSteps() {
		// one block up every 3 cells, then down again
		Grid g = new Grid((x, z) -> 64 + Math.min(x, 30 - x) / 3);
		List<Integer> l = new ArrayList<>();
		for (int x = 0; x <= 30; x++) {
			l.addAll(List.of(x, 64 + Math.min(x, 30 - x) / 3 + 1, 0));
		}
		long[] r = route(l.stream().mapToInt(Integer::intValue).toArray());
		for (int w = 1; w <= 3; w++) {
			Plan p = plan(r, new Options(w, false, false), g);
			assertNull(p.refusal());
			assertGentle(p);
			assertTrue(p.count(Block.PATH_SLAB) > 0, "half steps on the climb");
			for (Cell c : p.cells()) {
				if (c.role() == Role.SLAB) {
					// the ground under a slab stays (a dirt path under a block turns to dirt)
					for (Op o : at(p, c.x(), c.z())) {
						assertTrue(o.y() >= c.feetY(), "no surface change under a slab");
					}
				}
			}
		}
		// the foot of the first climb (x = 2: x = 3 is a block higher) gets the slab, the climb itself does not
		Plan p = plan(r, W1, g);
		assertEquals(Block.PATH_SLAB, at(p, 2, 0).get(0).block());
		assertEquals(65, at(p, 2, 0).get(0).y());
		assertEquals(Block.DIRT_PATH, at(p, 3, 0).get(0).block());
	}

	@Test
	void routeMustBeAChainOfGentleSteps() {
		assertNull(RoadPlan.routeProblem(alongX(0, 3, 65, 0)));
		assertNotNull(RoadPlan.routeProblem(route(0, 65, 0, 2, 65, 0)));
		assertNotNull(RoadPlan.routeProblem(route(0, 65, 0, 1, 67, 0)), "a drop of 2");
		assertNotNull(RoadPlan.routeProblem(route(0, 65, 0)));
		Plan p = plan(route(0, 65, 0, 1, 63, 0), W2, Grid.flat());
		assertNotNull(p.refusal());
		assertTrue(p.ops().isEmpty());
	}

	@Test
	void unloadedChunksRefuse() {
		Grid g = Grid.flat();
		g.set(5, 64, 0, RoadPlan.UNLOADED);
		Plan p = plan(alongX(0, 10, 65, 0), W1, g);
		assertNotNull(p.refusal());
		assertTrue(p.ops().isEmpty());
	}

	// ------------------------------------------------------------------ blocks

	@Test
	void blockChoicePerSurface() {
		assertEquals(Block.DIRT_PATH, RoadPlan.surfaceFor(RoadPlan.DIRT, true));
		assertEquals(Block.DIRT_PATH, RoadPlan.surfaceFor(RoadPlan.PATH, false));
		assertEquals(Block.GRAVEL, RoadPlan.surfaceFor(RoadPlan.SAND, true));
		assertEquals(Block.GRAVEL, RoadPlan.surfaceFor(RoadPlan.STONE, true));
		assertEquals(Block.PACKED_MUD, RoadPlan.surfaceFor(RoadPlan.SAND, false), "gravel never goes where it would fall");
		assertEquals(Block.PACKED_MUD, RoadPlan.surfaceFor(RoadPlan.MUD, true));
		assertNull(RoadPlan.surfaceFor(RoadPlan.BUILT, true));
		assertNull(RoadPlan.surfaceFor(RoadPlan.LOG, true));
		assertEquals(Block.STONE_SLAB, RoadPlan.slabFor(RoadPlan.SAND));
		assertEquals(Block.PATH_SLAB, RoadPlan.slabFor(RoadPlan.DIRT));

		Grid g = Grid.flat();
		g.surface = RoadPlan.SAND;
		g.set(3, 63, 0, RoadPlan.AIR); // a cave under x = 3
		Plan p = plan(alongX(0, 5, 65, 0), W1, g);
		assertEquals(Block.GRAVEL, at(p, 2, 0).get(0).block());
		assertEquals(Block.PACKED_MUD, at(p, 3, 0).get(0).block());
	}

	/** RoadTerrain classes exposed ores as BUILT: never gravelled over (they would be lost if the gravel were mined). */
	@Test
	void exposedOresAreKept() {
		Grid g = Grid.flat();
		g.surface = RoadPlan.STONE;
		g.set(4, 64, 0, RoadPlan.BUILT).set(6, 64, 1, RoadPlan.BUILT); // an ore under the centre, one under a side cell
		Plan p = plan(alongX(0, 10, 65, 0), W2, g);
		assertTrue(at(p, 4, 0).stream().noneMatch(o -> o.y() == 64), "the ore under the centre is kept");
		assertTrue(columns(p).contains(RoadPlan.col(4, 0)), "the centre stays a road cell");
		assertFalse(columns(p).contains(RoadPlan.col(6, 1)), "the side cell on an ore is left out");
		assertTrue(at(p, 5, 0).stream().anyMatch(o -> o.y() == 64 && o.block() == Block.GRAVEL), "natural stone gets gravel");
	}

	@Test
	void previewHashFollowsTheChanges() {
		Grid g = Grid.flat();
		Plan p = plan(alongX(0, 20, 65, 0), W2, g);
		Plan same = plan(alongX(0, 20, 65, 0), W2, Grid.flat());
		assertEquals(RoadPlan.hash(p.ops()), RoadPlan.hash(same.ops()));
		Grid changed = Grid.flat();
		changed.set(7, 64, 0, RoadPlan.SAND);
		assertTrue(RoadPlan.hash(p.ops()) != RoadPlan.hash(plan(alongX(0, 20, 65, 0), W2, changed).ops()), "a changed block changes the hash");
		Grid built = Grid.flat();
		built.set(9, 64, 1, RoadPlan.BUILT);
		assertTrue(RoadPlan.hash(p.ops()) != RoadPlan.hash(plan(alongX(0, 20, 65, 0), W2, built).ops()), "a dropped cell changes the hash");
		List<Op> swapped = new ArrayList<>(p.ops());
		java.util.Collections.swap(swapped, 0, 1);
		assertTrue(RoadPlan.hash(p.ops()) != RoadPlan.hash(swapped));
	}

	@Test
	void onlyNewOrTallerShapesCanTrap() {
		assertTrue(RoadPlan.canTrap(true, 0, false, 0.5)); // air to a slab
		assertTrue(RoadPlan.canTrap(true, 0, false, 1.5)); // air to a fence
		assertFalse(RoadPlan.canTrap(false, 1, false, 0.9375)); // grass to a dirt path
		assertFalse(RoadPlan.canTrap(false, 1, false, 1)); // stone to gravel
		assertFalse(RoadPlan.canTrap(false, 1, true, 0)); // cleared
		assertTrue(RoadPlan.canTrap(false, 0.5, false, 1)); // a slab back to grass
		assertFalse(RoadPlan.canTrap(false, 0.9375, false, 1)); // a path back to grass (1/16)
	}

	@Test
	void ownBlocksAreNeverChanged() {
		Grid g = Grid.flat();
		// the route crosses the player's floor at x = 4..5; a player's block beside the road at (8, 64, 1)
		g.set(4, 64, 0, RoadPlan.BUILT).set(5, 64, 0, RoadPlan.BUILT).set(4, 64, 1, RoadPlan.BUILT).set(8, 64, 1, RoadPlan.BUILT);
		g.set(4, 65, 0, RoadPlan.PLANT);
		Plan p = plan(alongX(0, 10, 65, 0), W2, g);
		for (Op o : p.ops()) {
			assertFalse(o.before() == RoadPlan.BUILT || o.before() == RoadPlan.BUILT_OPEN || o.before() == RoadPlan.BLOCK_ENTITY, "changed " + o);
		}
		// the centre on the player's floor stays a road cell (plants above it cleared), the side cell is left out
		assertEquals(List.of(new Op(4, 65, 0, Block.AIR, RoadPlan.PLANT)), at(p, 4, 0));
		assertTrue(columns(p).contains(RoadPlan.col(4, 0)));
		assertFalse(columns(p).contains(RoadPlan.col(8, 1)));
		assertEquals(2, p.skipped().get("built"));
		// a torch on the route: that cell is kept exactly as it is
		Grid t = Grid.flat();
		t.set(3, 65, 0, RoadPlan.BUILT_OPEN);
		Plan q = plan(alongX(0, 6, 65, 0), W1, t);
		assertTrue(at(q, 3, 0).isEmpty());
		assertEquals(Role.KEPT, q.cells().stream().filter(c -> c.x() == 3).findFirst().orElseThrow().role());
	}

	@Test
	void clearsPlantsLeavesSnowAndWholeStacks() {
		Grid g = Grid.flat();
		g.set(1, 65, 0, RoadPlan.PLANT); // grass
		g.set(2, 66, 0, RoadPlan.LEAVES); // a low branch at head height
		g.set(3, 65, 0, RoadPlan.STACK).set(3, 66, 0, RoadPlan.STACK).set(3, 67, 0, RoadPlan.STACK).set(3, 68, 0, RoadPlan.STACK); // sugar cane
		g.set(4, 67, 0, RoadPlan.LEAVES); // above the headroom: stays
		Plan p = plan(alongX(0, 6, 65, 0), W1, g);
		assertTrue(at(p, 1, 0).contains(new Op(1, 65, 0, Block.AIR, RoadPlan.PLANT)));
		assertTrue(at(p, 2, 0).contains(new Op(2, 66, 0, Block.AIR, RoadPlan.LEAVES)));
		long cane = at(p, 3, 0).stream().filter(o -> o.block() == Block.AIR).count();
		assertEquals(4, cane, "the whole cane goes, nothing floats");
		assertTrue(at(p, 4, 0).stream().noneMatch(o -> o.block() == Block.AIR));
	}

	@Test
	void logsFluidsAndBlockEntitiesBlockSideCells() {
		Grid g = Grid.flat();
		g.set(2, 65, 1, RoadPlan.LOG).set(3, 66, 1, RoadPlan.WATER).set(4, 65, 1, RoadPlan.BLOCK_ENTITY).set(5, 66, 1, RoadPlan.STONE);
		Plan p = plan(alongX(0, 8, 65, 0), W2, g);
		for (int x = 2; x <= 5; x++) {
			assertTrue(at(p, x, 1).isEmpty(), "x " + x);
		}
		assertEquals(4, p.skipped().get("blocked"));
		assertEquals(4 * 3, p.refused().length, "the ghost draws them red");
		assertConnected(p);
	}

	@Test
	void sideCellsFollowTheirGroundWithinABlock() {
		// the ground beside the route (z = 1) is a block higher at x 3..5 and three higher at x 7
		Grid g = new Grid((x, z) -> z == 1 && x >= 3 && x <= 5 ? 65 : z == 1 && x == 7 ? 67 : 64);
		Plan p = plan(alongX(0, 10, 65, 0), W2, g);
		assertEquals(66, p.cells().stream().filter(c -> c.x() == 4 && c.z() == 1).findFirst().orElseThrow().feetY());
		assertFalse(columns(p).contains(RoadPlan.col(7, 1)));
		assertGentle(p);
	}

	// ------------------------------------------------------------------ water

	@Test
	void shallowWaterIsSkippedUnlessBridged() {
		Grid g = Grid.flat();
		for (int x = 8; x <= 10; x++) {
			for (int z = -1; z <= 2; z++) {
				g.set(x, 64, z, RoadPlan.WATER); // 1 deep, stone below
			}
		}
		List<Integer> l = new ArrayList<>();
		for (int x = 0; x <= 18; x++) {
			l.addAll(List.of(x, x >= 8 && x <= 10 ? 64 : 65, 0));
		}
		long[] r = route(l.stream().mapToInt(Integer::intValue).toArray());
		Plan off = plan(r, W2, g);
		assertEquals(6, off.skipped().get("water"));
		for (Op o : off.ops()) {
			assertFalse(o.x() >= 8 && o.x() <= 10, "nothing on the water " + o);
		}
		Plan on = plan(r, new Options(2, false, true), g);
		assertFalse(on.skipped().containsKey("water"));
		assertEquals(6, on.count(Block.DECK));
		for (Op o : on.ops()) {
			if (o.block() == Block.DECK) {
				assertEquals(65, o.y(), "the deck sits above the water, never in it");
				assertEquals(RoadPlan.AIR, o.before());
			}
			assertFalse(o.before() == RoadPlan.WATER, "water is never touched");
		}
		assertGentle(on);
		// deeper water is never bridged
		g.set(9, 63, 0, RoadPlan.WATER);
		assertEquals(1, plan(r, new Options(1, false, true), g).skipped().get("deep"));
	}

	// ------------------------------------------------------------------ lanterns

	@Test
	void lanternsEveryTwelveBlocksBesideTheRoad() {
		Plan p = plan(alongX(0, 60, 65, 0), new Options(2, true, false), Grid.flat());
		int[] l = p.lanterns();
		assertEquals(5, p.lanternCount());
		for (int i = 0; i < l.length; i += 3) {
			assertEquals(6 + 12 * (i / 3), l[i], "x");
			assertEquals(66, l[i + 1], "on a fence post at feet height");
			assertEquals(2, l[i + 2], "just beside the walkway");
		}
		assertEquals(5, p.count(Block.FENCE));
		assertEquals(5, p.count(Block.LANTERN));
		// no room on the right: the left side
		Grid g = Grid.flat();
		g.set(6, 65, 2, RoadPlan.LOG);
		Plan q = plan(alongX(0, 30, 65, 0), new Options(2, true, false), g);
		assertEquals(-1, q.lanterns()[2]);
		assertEquals(6, q.lanterns()[0]);
		// no room on either side: a little further along
		g.set(6, 65, -1, RoadPlan.LOG);
		Plan r = plan(alongX(0, 30, 65, 0), new Options(2, true, false), g);
		assertEquals(7, r.lanterns()[0]);
		// off: none
		assertEquals(0, plan(alongX(0, 60, 65, 0), W2, Grid.flat()).lanternCount());
	}

	// ------------------------------------------------------------------ buildings, other roads, snapshot box

	@Test
	void buildingsAreTrimmedAndNeverTouched() {
		// buildings at both ends (their restore boxes cover x 0..4 and x 16..20)
		List<Anchors.Bounds> boxes = List.of(new Anchors.Bounds(0, 60, -5, 4, 80, 5), new Anchors.Bounds(16, 60, -5, 20, 80, 5));
		Plan p = RoadPlan.plan(alongX(0, 20, 65, 0), new Options(3, true, false), Grid.flat(), boxes, c -> false);
		assertEquals(10, p.trimmed());
		assertEquals(11, p.centre());
		for (Op o : p.ops()) {
			for (Anchors.Bounds b : boxes) {
				assertFalse(b.contains(o.x(), o.y(), o.z()), "inside a building: " + o);
			}
		}
	}

	@Test
	void anotherRoadsCellsAreLeftToIt() {
		Set<Long> taken = Set.of(WalkCell.pack(5, 64, 0), WalkCell.pack(5, 64, 1));
		Plan p = RoadPlan.plan(alongX(0, 10, 65, 0), W2, Grid.flat(), List.of(), taken::contains);
		assertTrue(at(p, 5, 0).isEmpty() && at(p, 5, 1).isEmpty());
		assertEquals(2, p.skipped().get("road"));
		assertEquals(20, p.count(Block.DIRT_PATH));
	}

	@Test
	void snapshotBoxCoversEveryChange() {
		Grid g = Grid.flat();
		g.set(3, 65, -2, RoadPlan.STACK).set(3, 66, -2, RoadPlan.STACK).set(3, 67, -2, RoadPlan.STACK);
		Plan p = plan(alongX(-4, 30, 65, -2), new Options(2, true, false), g);
		Anchors.Bounds b = p.box();
		assertNotNull(b);
		assertEquals(new Anchors.Bounds(-4, 64, -2, 30, 67, 0), b);
		for (Op o : p.ops()) {
			assertTrue(b.contains(o.x(), o.y(), o.z()));
		}
		assertNull(RoadPlan.box(List.of()));
	}

	@Test
	void notesSayWhatWasSkipped() {
		assertEquals("6 cells of shallow water skipped (bridge off)", RoadPlan.note("water", 6));
		assertEquals("1 lantern without room for a post", RoadPlan.note("lantern", 1));
	}
}
