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
}
