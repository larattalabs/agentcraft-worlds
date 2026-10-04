package dev.agentcraft.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.walk.OutdoorPlanner.Limits;
import dev.agentcraft.walk.OutdoorPlanner.Point;
import dev.agentcraft.walk.OutdoorPlanner.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutdoorPlannerTest {
	static final Limits SMALL = new Limits(60_000, 6, 256, 32);

	static Point p(double x, double y, double z) {
		return new Point(x, y, z);
	}

	static OutdoorPlanner plan(Terrain t, Point a, Point b, Limits l) {
		OutdoorPlanner p = new OutdoorPlanner(t, a, b, l);
		p.runAll();
		return p;
	}

	/** Every raw cell is standable, consecutive cells are neighbours, and no step rises > 1 or drops > 3. */
	static void assertWalkable(Terrain t, OutdoorPlanner p) {
		long[] cells = p.cells();
		assertNotNull(cells);
		assertTrue(OutdoorPlanner.stillWalkable(t, cells));
		for (int i = 1; i < cells.length; i++) {
			long a = cells[i - 1];
			long b = cells[i];
			assertTrue(Math.abs(WalkCell.unpackX(a) - WalkCell.unpackX(b)) <= 1 && Math.abs(WalkCell.unpackZ(a) - WalkCell.unpackZ(b)) <= 1);
			double fa = WalkCell.floor(t, WalkCell.unpackX(a), WalkCell.unpackY(a), WalkCell.unpackZ(a));
			double fb = WalkCell.floor(t, WalkCell.unpackX(b), WalkCell.unpackY(b), WalkCell.unpackZ(b));
			assertTrue(fb - fa <= 1.0 + 1e-9, "rise " + (fb - fa));
			assertTrue(fa - fb <= 3.0 + 1e-9, "drop " + (fa - fb));
		}
		List<Point> path = p.path();
		assertNotNull(path);
		assertTrue(path.size() >= 2);
	}

	@Test
	void flatGroundIsOneStraightSegment() {
		GridTerrain t = GridTerrain.flat();
		OutdoorPlanner p = plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL);
		assertEquals(Status.FOUND, p.status());
		assertWalkable(t, p);
		assertEquals(List.of(p(0.5, 65, 0.5), p(20.5, 65, 0.5)), p.path());
		assertEquals(20, p.length(), 1e-9);
	}

	@Test
	void sameCellIsTrivial() {
		OutdoorPlanner p = plan(GridTerrain.flat(), p(0.2, 65, 0.2), p(0.8, 65, 0.7), SMALL);
		assertEquals(Status.FOUND, p.status());
		assertEquals(2, p.path().size());
	}

	@Test
	void wallIsWalkedAround() {
		GridTerrain t = GridTerrain.flat();
		for (int z = -4; z <= 4; z++) {
			t.column(10, z, 65, 67, WalkCell.solid(16));
		}
		OutdoorPlanner p = plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL);
		assertEquals(Status.FOUND, p.status());
		assertWalkable(t, p);
		assertTrue(p.length() > 21, "detour " + p.length());
		for (long c : p.cells()) {
			assertTrue(WalkCell.unpackX(c) != 10 || Math.abs(WalkCell.unpackZ(c)) > 4);
		}
	}

	@Test
	void stepsUpOneButNotTwo() {
		GridTerrain one = new GridTerrain((x, z) -> x >= 10 ? 65 : 64);
		OutdoorPlanner up = plan(one, p(0.5, 65, 0.5), p(20.5, 66, 0.5), SMALL);
		assertEquals(Status.FOUND, up.status());
		assertWalkable(one, up);
		// the agent reaches the edge on the low level, then steps up (no gliding through the block's corner)
		assertTrue(up.path().contains(p(9.92, 65, 0.5)), up.path().toString());
		assertTrue(up.path().contains(p(10.5, 66, 0.5)), up.path().toString());
		GridTerrain two = new GridTerrain((x, z) -> x >= 10 ? 66 : 64);
		assertEquals(Status.NO_PATH, plan(two, p(0.5, 65, 0.5), p(20.5, 67, 0.5), SMALL).status());
	}

	@Test
	void dropsUpToThreeOneWay() {
		GridTerrain three = new GridTerrain((x, z) -> x >= 10 ? 61 : 64);
		assertEquals(Status.FOUND, plan(three, p(0.5, 65, 0.5), p(20.5, 62, 0.5), SMALL).status());
		// and never back up a 3-block cliff
		assertEquals(Status.NO_PATH, plan(three, p(20.5, 62, 0.5), p(0.5, 65, 0.5), SMALL).status());
		GridTerrain four = new GridTerrain((x, z) -> x >= 10 ? 60 : 64);
		assertEquals(Status.NO_PATH, plan(four, p(0.5, 65, 0.5), p(20.5, 61, 0.5), SMALL).status());
	}

	@Test
	void roadRoutesDropAtMostOneBlock() {
		// a 2-block drop straight ahead, a ramp 6 blocks to the side: agents drop, a road takes the ramp
		GridTerrain t = new GridTerrain((x, z) -> x < 10 ? 64 : z >= 6 && x < 12 ? 64 - (x - 9) : 62);
		Limits road = SMALL.withMaxDrop(1);
		OutdoorPlanner agent = plan(t, p(0.5, 65, 0.5), p(20.5, 63, 0.5), SMALL);
		assertEquals(Status.FOUND, agent.status());
		OutdoorPlanner p = plan(t, p(0.5, 65, 0.5), p(20.5, 63, 0.5), new Limits(60_000, 12, 256, 32, 12, 1));
		assertEquals(Status.FOUND, p.status(), p.explain());
		long[] cells = p.cells();
		for (int i = 1; i < cells.length; i++) {
			assertTrue(Math.abs(WalkCell.unpackY(cells[i]) - WalkCell.unpackY(cells[i - 1])) <= 1, "a step of more than one block");
		}
		// so the road is walkable both ways
		assertEquals(Status.FOUND, plan(t, p(20.5, 63, 0.5), p(0.5, 65, 0.5), new Limits(60_000, 12, 256, 32, 12, 1)).status());
		assertEquals(1, road.maxDrop());
		assertEquals(1, Limits.ROAD.maxDrop());
		assertEquals(3, Limits.DEFAULT.maxDrop());
	}

	@Test
	void routesPreferLaidRoads() {
		// a road along z = 4 from x 2 to 98; the straight line is along z = 0
		GridTerrain t = GridTerrain.flat();
		it.unimi.dsi.fastutil.longs.LongOpenHashSet road = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (int x = 2; x <= 98; x++) {
			road.add(WalkCell.pack(x, 65, 4));
		}
		Point a = p(0.5, 65, 0.5);
		Point b = p(100.5, 65, 0.5);
		OutdoorPlanner without = plan(t, a, b, Limits.DEFAULT);
		OutdoorPlanner with = new OutdoorPlanner(t, a, b, Limits.DEFAULT, road);
		with.runAll();
		assertEquals(Status.FOUND, with.status());
		assertWalkable(t, with);
		long onRoad = java.util.Arrays.stream(with.cells()).filter(road::contains).count();
		long before = java.util.Arrays.stream(without.cells()).filter(road::contains).count();
		assertEquals(0, before);
		assertTrue(onRoad > 80, "the route keeps to the road: " + onRoad + " cells on it");
		assertTrue(with.expanded() < 30_000, "expansions " + with.expanded());
		System.out.printf("V1 road preference 100: %d nodes with roads, %d without%n", with.expanded(), without.expanded());
		assertTrue(with.heuristicWeight() < 1.0, "a road in the box scales the heuristic");
		// a road far off the way is not worth the detour
		it.unimi.dsi.fastutil.longs.LongOpenHashSet far = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (int x = 2; x <= 98; x++) {
			far.add(WalkCell.pack(x, 65, 40));
		}
		OutdoorPlanner p = new OutdoorPlanner(t, a, b, Limits.DEFAULT, far);
		p.runAll();
		assertEquals(0, java.util.Arrays.stream(p.cells()).filter(far::contains).count());
		// on rough terrain the budget still holds
		GridTerrain m = new GridTerrain(OutdoorPlannerTest::mountains);
		it.unimi.dsi.fastutil.longs.LongSet none = it.unimi.dsi.fastutil.longs.LongSet.of(WalkCell.pack(5000, 64, 5000));
		Point ma = p(0.5, mountains(0, 0) + 1, 0.5);
		Point mb = p(160.5, mountains(160, 30) + 1, 30.5);
		OutdoorPlanner mp = new OutdoorPlanner(m, ma, mb, Limits.DEFAULT, none);
		mp.runAll();
		assertEquals(Status.FOUND, mp.status(), mp.explain());
		System.out.printf("V1 road weighting on mountains 160: %d nodes%n", mp.expanded());
		OutdoorPlanner mNone = plan(m, ma, mb, Limits.DEFAULT);
		assertTrue(mp.expanded() <= mNone.expanded() * 3 / 2, "unrelated road: " + mp.expanded() + " vs " + mNone.expanded());
	}

	/** A road elsewhere in the dimension must not slow every agent's search (the heuristic stays unscaled). */
	@Test
	void unrelatedRoadsDoNotSlowTheCorridor() {
		GridTerrain t = hills();
		Point a = p(0.5, height(0, 0) + 1, 0.5);
		Point b = p(250.5, height(250, 40) + 1, 40.5);
		it.unimi.dsi.fastutil.longs.LongOpenHashSet road = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (int x = 0; x <= 250; x++) {
			road.add(WalkCell.pack(x, height(x, 400) + 1, 400)); // a long road, far outside the search box
		}
		OutdoorPlanner without = plan(t, a, b, Limits.DEFAULT);
		OutdoorPlanner with = new OutdoorPlanner(t, a, b, Limits.DEFAULT, road);
		with.runAll();
		assertEquals(Status.FOUND, without.status());
		assertEquals(Status.FOUND, with.status(), with.explain());
		assertEquals(1.08, with.heuristicWeight(), 1e-9);
		System.out.printf("V1 corridor 256 with an unrelated road: %d nodes, %d without%n", with.expanded(), without.expanded());
		assertTrue(with.expanded() <= without.expanded() * 3 / 2, "expansions " + with.expanded() + " vs " + without.expanded());		// a short road inside the box (a village): the heuristic is scaled, the search must still finish within budget
		it.unimi.dsi.fastutil.longs.LongOpenHashSet near = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (int x = 100; x <= 130; x++) {
			near.add(WalkCell.pack(x, height(x, 20) + 1, 20));
		}
		OutdoorPlanner in = new OutdoorPlanner(t, a, b, Limits.DEFAULT, near);
		in.runAll();
		assertEquals(Status.FOUND, in.status(), in.explain());
		System.out.printf("V1 corridor 256 with a 30-block road in the box: %d nodes%n", in.expanded());
		assertTrue(in.expanded() < Limits.DEFAULT.maxNodes() / 2, "expansions " + in.expanded());
	}

	@Test
	void hazardsAreAvoided() {
		GridTerrain t = GridTerrain.flat();
		for (int z = -8; z <= 8; z++) {
			if (z != 5) {
				t.set(10, 64, z, WalkCell.HAZARD); // a lava channel with one stepping stone at z = 5
			}
		}
		OutdoorPlanner p = plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL);
		assertEquals(Status.FOUND, p.status());
		assertWalkable(t, p);
		boolean crossed = false;
		for (long c : p.cells()) {
			if (WalkCell.unpackX(c) == 10) {
				assertEquals(5, WalkCell.unpackZ(c));
				crossed = true;
			}
		}
		assertTrue(crossed);
		t.set(10, 64, 5, WalkCell.HAZARD);
		assertEquals(Status.NO_PATH, plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL).status());
		// fire in the feet cell, powder snow as the floor: same thing
		GridTerrain f = GridTerrain.flat();
		for (int z = -8; z <= 8; z++) {
			f.set(10, 65, z, z % 2 == 0 ? WalkCell.HAZARD : WalkCell.OPEN);
			f.set(10, 64, z, z % 2 == 0 ? WalkCell.solid(16) : WalkCell.HAZARD);
		}
		assertEquals(Status.NO_PATH, plan(f, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL).status());
	}

	@Test
	void shallowWaterIsWadedDeepWaterIsNot() {
		GridTerrain shallow = GridTerrain.flat();
		GridTerrain deep = GridTerrain.flat();
		for (int x = 8; x <= 12; x++) {
			for (int z = -8; z <= 8; z++) {
				shallow.set(x, 64, z, WalkCell.WATER);
				deep.set(x, 64, z, WalkCell.WATER).set(x, 63, z, WalkCell.WATER);
			}
		}
		OutdoorPlanner p = plan(shallow, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL);
		assertEquals(Status.FOUND, p.status());
		assertWalkable(shallow, p);
		assertEquals(Status.NO_PATH, plan(deep, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL).status());
	}

	@Test
	void doorsArePassedThrough() {
		GridTerrain t = GridTerrain.flat();
		for (int z = -8; z <= 8; z++) {
			t.column(10, z, 65, 68, WalkCell.solid(16));
		}
		t.set(10, 65, 0, WalkCell.DOOR).set(10, 66, 0, WalkCell.DOOR);
		OutdoorPlanner p = plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL);
		assertEquals(Status.FOUND, p.status());
		assertWalkable(t, p);
		assertEquals(20, p.length(), 1e-9);
	}

	@Test
	void leavesAndFencesAreNoFloor() {
		GridTerrain t = GridTerrain.flat();
		t.set(3, 64, 0, WalkCell.LEAVES);
		assertTrue(Double.isNaN(WalkCell.floor(t, 3, 65, 0)));
		t.set(4, 65, 0, WalkCell.solid(24));
		assertTrue(Double.isNaN(WalkCell.floor(t, 4, 66, 0)));
		// a bottom slab is stood on within its own cell; a carpet needs no extra headroom
		t.set(5, 65, 0, WalkCell.solid(8));
		assertEquals(65.5, WalkCell.floor(t, 5, 65, 0), 1e-9);
		t.set(6, 65, 0, WalkCell.solid(1)).set(6, 67, 0, WalkCell.solid(16));
		assertEquals(65 + 1 / 16.0, WalkCell.floor(t, 6, 65, 0), 1e-9);
		t.set(5, 67, 0, WalkCell.solid(16));
		assertTrue(Double.isNaN(WalkCell.floor(t, 5, 65, 0)));
	}

	@Test
	void unloadedChunksReportUnloaded() {
		GridTerrain t = GridTerrain.flat();
		t.unloadedFromX = 12;
		assertEquals(Status.UNLOADED, plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL).status());
	}

	@Test
	void budgetAndDistanceLimits() {
		GridTerrain t = GridTerrain.flat();
		for (int z = -40; z <= 40; z++) {
			t.column(10, z, 65, 67, WalkCell.solid(16));
		}
		assertEquals(Status.BUDGET, plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), new Limits(50, 40, 256, 12)).status());
		assertEquals(Status.TOO_FAR, plan(GridTerrain.flat(), p(0.5, 65, 0.5), p(300.5, 65, 0.5), Limits.DEFAULT).status());
		assertEquals(Status.NO_START, plan(GridTerrain.flat(), p(0.5, 80, 0.5), p(10.5, 65, 0.5), SMALL).status());
	}

	@Test
	void incrementalStepsMatchOneShot() {
		GridTerrain t = hills();
		OutdoorPlanner whole = plan(t, p(0.5, height(0, 0) + 1, 0.5), p(60.5, height(60, 7) + 1, 7.5), Limits.DEFAULT);
		OutdoorPlanner inc = new OutdoorPlanner(t, p(0.5, height(0, 0) + 1, 0.5), p(60.5, height(60, 7) + 1, 7.5), Limits.DEFAULT);
		int calls = 0;
		while (inc.step(5, Long.MAX_VALUE) == Status.RUNNING) {
			calls++;
		}
		assertEquals(Status.FOUND, whole.status());
		assertEquals(whole.status(), inc.status());
		assertEquals(whole.path(), inc.path());
		assertTrue(calls > 3, "spread over " + calls + " steps");
		assertWalkable(t, inc);
	}

	@Test
	void stillWalkableNoticesChanges() {
		GridTerrain t = GridTerrain.flat();
		OutdoorPlanner p = plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL);
		assertTrue(OutdoorPlanner.stillWalkable(t, p.cells()));
		t.set(7, 65, 0, WalkCell.solid(16)).set(7, 66, 0, WalkCell.solid(16));
		assertFalse(OutdoorPlanner.stillWalkable(t, p.cells()));
	}

	@Test
	void packRoundTrips() {
		for (int[] c : new int[][] {{0, 0, 0}, {-1, -64, -1}, {12345, 319, -54321}, {-30_000_000, 100, 29_999_999}}) {
			long k = WalkCell.pack(c[0], c[1], c[2]);
			assertEquals(c[0], WalkCell.unpackX(k));
			assertEquals(c[1], WalkCell.unpackY(k));
			assertEquals(c[2], WalkCell.unpackZ(k));
		}
	}

	/** Rolling hills (height 60..70), a river 2 deep along z = 30 with a ford, and tree clumps. */
	static int height(int x, int z) {
		return 64 + (int) Math.round(3 * Math.sin(x / 9.0) + 2 * Math.cos(z / 7.0) + Math.sin((x + z) / 13.0));
	}

	static GridTerrain hills() {
		GridTerrain t = new GridTerrain(OutdoorPlannerTest::height);
		for (int x = -60; x <= 320; x++) {
			for (int dz = -2; dz <= 2; dz++) {
				int z = 30 + dz;
				int h = height(x, z);
				boolean ford = Math.floorMod(x, 64) < 3;
				t.set(x, h, z, WalkCell.WATER);
				if (!ford) {
					t.set(x, h - 1, z, WalkCell.WATER);
				}
			}
			if (Math.floorMod(x * 7, 23) == 0) {
				int z = Math.floorMod(x * 13, 50) - 25;
				for (int dx = 0; dx < 3; dx++) {
					for (int dz = 0; dz < 3; dz++) {
						t.column(x + dx, z + dz, height(x + dx, z + dz) + 1, height(x + dx, z + dz) + 5, WalkCell.LEAVES);
					}
				}
			}
		}
		return t;
	}

	/** W8 performance: a 256-block corridor over hills, a river and trees. Prints time and nodes; per-tick work is bounded by step(). */
	@Test
	void corridor256Performance() {
		GridTerrain t = hills();
		Point a = p(0.5, height(0, 0) + 1, 0.5);
		Point b = p(250.5, height(250, 40) + 1, 40.5);
		// warm up the JIT once so the measurement means something
		plan(t, a, b, Limits.DEFAULT);
		OutdoorPlanner p = new OutdoorPlanner(t, a, b, Limits.DEFAULT);
		int ticks = 0;
		long worst = 0;
		while (true) {
			long t0 = System.nanoTime();
			Status s = p.step(2500, System.nanoTime() + 2_000_000);
			worst = Math.max(worst, System.nanoTime() - t0);
			ticks++;
			if (s != Status.RUNNING) {
				break;
			}
		}
		assertEquals(Status.FOUND, p.status());
		assertWalkable(t, p);
		System.out.printf("W8 corridor 256: %d nodes, %.2f ms total, %d ticks, worst tick %.2f ms, route %.1f blocks, %d points, %d lookups%n",
			p.expanded(), p.micros() / 1000.0, ticks, worst / 1e6, p.length(), p.path().size(), t.lookups);
		assertTrue(p.expanded() < Limits.DEFAULT.maxNodes(), "nodes " + p.expanded());
		assertTrue(p.micros() < 3_000_000, "took " + p.micros() + " us"); // generous: CI machines vary
	}

	/** 2-block terraces everywhere except a 1-per-block ramp far to the side: a detour beyond the first search box. */
	@Test
	void terracesNeedTheRampAndAWiderBox() {
		GridTerrain t = new GridTerrain((x, z) -> x < 10 ? 64 : z >= 20 && z <= 22 ? Math.min(66, 64 + Math.max(0, x - 9)) : 66);
		Point a = p(0.5, 65, 0.5);
		Point b = p(20.5, 67, 0.5);
		OutdoorPlanner narrow = plan(t, a, b, SMALL);
		assertEquals(Status.NO_PATH, narrow.status());
		assertTrue(narrow.explain().contains("search box"), narrow.explain());
		OutdoorPlanner wide = plan(t, a, b, new Limits(60_000, 6, 256, 32, 48));
		assertEquals(Status.FOUND, wide.status(), wide.explain());
		assertWalkable(t, wide);
		assertTrue(wide.widenings() >= 1);
		assertTrue(java.util.Arrays.stream(wide.cells()).anyMatch(c -> WalkCell.unpackZ(c) >= 20), "via the ramp");
	}

	/** A canopy at head height over the whole way: agents brush through leaves (they collide with nothing). */
	@Test
	void lowCanopyIsWalkedUnder() {
		GridTerrain t = GridTerrain.flat();
		for (int x = 5; x <= 15; x++) {
			for (int z = -12; z <= 12; z++) {
				t.set(x, 66, z, WalkCell.LEAVES);
			}
		}
		OutdoorPlanner p = plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), SMALL);
		assertEquals(Status.FOUND, p.status());
		assertWalkable(t, p);
	}

	@Test
	void aWalledInStartIsExplained() {
		GridTerrain t = GridTerrain.flat();
		for (int d = -2; d <= 2; d++) {
			t.column(d, -2, 65, 66, WalkCell.solid(16)).column(d, 2, 65, 66, WalkCell.solid(16));
			t.column(-2, d, 65, 66, WalkCell.solid(16)).column(2, d, 65, 66, WalkCell.solid(16));
		}
		OutdoorPlanner p = plan(t, p(0.5, 65, 0.5), p(20.5, 65, 0.5), Limits.DEFAULT);
		assertEquals(Status.NO_PATH, p.status());
		assertTrue(p.explain().contains("walled in"), p.explain());
		assertNotNull(p.closestCell());
	}

	/** Steep sine mountains (slopes up to ~2 per block): routes wind along the contours; 150+ blocks. */
	static int mountains(int x, int z) {
		return 80 + (int) Math.round(14 * Math.sin(x / 8.0) * Math.cos(z / 11.0) + 6 * Math.sin((x - 2 * z) / 17.0));
	}

	@Test
	void steepMountainsAreCrossed() {
		GridTerrain t = new GridTerrain(OutdoorPlannerTest::mountains);
		Point a = p(0.5, mountains(0, 0) + 1, 0.5);
		Point b = p(160.5, mountains(160, 30) + 1, 30.5);
		plan(t, a, b, Limits.DEFAULT); // JIT warm-up
		OutdoorPlanner p = new OutdoorPlanner(t, a, b, Limits.DEFAULT);
		long worst = 0;
		while (true) {
			long t0 = System.nanoTime();
			Status s = p.step(2500, System.nanoTime() + 2_000_000);
			worst = Math.max(worst, System.nanoTime() - t0);
			if (s != Status.RUNNING) {
				break;
			}
		}
		assertEquals(Status.FOUND, p.status(), p.explain());
		assertWalkable(t, p);
		System.out.printf("W8 mountains 160: %d nodes, %.2f ms total, %d steps, worst step %.2f ms, route %.1f blocks, pad %d%n", p.expanded(),
			p.micros() / 1000.0, p.steps(), worst / 1e6, p.length(), p.pad());
	}
}
