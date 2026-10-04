package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** C4 terrain fit and the fluid checks (docs/BUILDINGS.md "Terrain fit"), against a fake world. */
class TerrainFitTest {
	/** A 3 x 3 x 3 template: floor row 0 (groundY 1), air above, origin at (0, 10, 0). */
	static GhostModel model() {
		Set<List<Integer>> air = new java.util.HashSet<>();
		for (int y = 1; y < 3; y++) {
			for (int z = 0; z < 3; z++) {
				for (int x = 0; x < 3; x++) {
					air.add(List.of(x, y, z));
				}
			}
		}
		return GhostModel.of(GhostModelTest.box(3, 3, 3, air), 0);
	}

	/** Ground at y <= groundTop, air above. */
	static TerrainFit.World flat(int groundTop) {
		return (x, y, z) -> y <= groundTop ? TerrainFit.NATURAL : TerrainFit.FILLABLE;
	}

	@Test
	void flatGroundNeedsNoFoundation() {
		TerrainFit.Plan p = TerrainFit.plan(model(), 0, 10, 0, flat(9));
		assertEquals(0, p.fillCount());
		assertEquals(10, p.minY());
		assertEquals(0, p.clearCount());
		assertEquals(0, p.waterCount());
	}

	@Test
	void aGapBelowTheFloorIsFilledDownToTheGround() {
		// ground three blocks below the floor: rows 9, 8, 7 are air
		TerrainFit.Plan p = TerrainFit.plan(model(), 0, 10, 0, flat(6));
		assertEquals(9 * 3, p.fillCount());
		assertEquals(7, p.minY());
		// top to bottom per column
		assertEquals(List.of(0, 9, 0), List.of(p.fill()[0], p.fill()[1], p.fill()[2]));
	}

	@Test
	void theFillStopsAfterTwelveBlocks() {
		TerrainFit.Plan p = TerrainFit.plan(model(), 0, 100, 0, flat(-64));
		assertEquals(9 * TerrainFit.MAX_FILL, p.fillCount());
		assertEquals(100 - TerrainFit.MAX_FILL, p.minY());
	}

	@Test
	void blockEntitiesStopTheFill() {
		TerrainFit.World w = (x, y, z) -> x == 1 && z == 1 && y == 8 ? TerrainFit.BLOCK_ENTITY : y <= 5 ? TerrainFit.NATURAL : TerrainFit.FILLABLE;
		TerrainFit.Plan p = TerrainFit.plan(model(), 0, 10, 0, w);
		int centre = 0;
		for (int i = 0; i < p.fill().length; i += 3) {
			if (p.fill()[i] == 1 && p.fill()[i + 2] == 1) {
				centre++;
			}
		}
		assertEquals(1, centre); // y 9 only: the chest at y 8 stops it
	}

	@Test
	void naturalTerrainAboveGroundInsideTheBoxIsClearedWhereTheTemplateWritesNothing() {
		// a template that writes only its floor: the rows above are not written, a hill reaches y 11
		GhostModel.Cells floorOnly = new GhostModel.Cells(3, 3, 3, 1, floorXyz(), solid(9));
		GhostModel m = GhostModel.of(floorOnly, 0);
		TerrainFit.Plan p = TerrainFit.plan(m, 0, 10, 0, flat(11));
		assertEquals(9, p.clearCount()); // row 11 (template row 1); row 12 is air
		// a template that writes air there clears it itself: nothing to clear
		assertEquals(0, TerrainFit.plan(model(), 0, 10, 0, flat(11)).clearCount());
	}

	@Test
	void waterIsCountedAndLavaRefuses() {
		TerrainFit.World lake = (x, y, z) -> y <= 7 ? TerrainFit.NATURAL : y <= 10 ? TerrainFit.WATER | TerrainFit.FILLABLE : TerrainFit.FILLABLE;
		TerrainFit.Plan p = TerrainFit.plan(model(), 0, 11, 0, lake);
		assertEquals(27, p.fillCount()); // y 10, 9, 8 under each floor cell
		assertEquals(8, p.minY());
		assertNotNull(TerrainFit.waterWarning(p));
		assertNull(TerrainFit.lavaRefusal(p));
		TerrainFit.World lava = (x, y, z) -> x == 3 && y == 10 && z == 1 ? TerrainFit.LAVA | TerrainFit.FILLABLE : y <= 9 ? TerrainFit.NATURAL
			: TerrainFit.FILLABLE;
		TerrainFit.Plan q = TerrainFit.plan(model(), 0, 10, 0, lava);
		assertEquals(1, q.lavaCount()); // next to the box: refused
		assertNotNull(TerrainFit.lavaRefusal(q));
	}

	@Test
	void medianSurfaceIgnoresMissingColumns() {
		assertEquals(64, TerrainFit.medianSurface(new int[] {63, 64, 64, 70, Integer.MIN_VALUE}, 0));
		assertEquals(5, TerrainFit.medianSurface(new int[] {Integer.MIN_VALUE}, 5));
		assertEquals(10, TerrainFit.medianSurface(new int[] {10, 20}, 0)); // ties round down
	}

	private static int[] floorXyz() {
		List<Integer> out = new ArrayList<>();
		for (int z = 0; z < 3; z++) {
			for (int x = 0; x < 3; x++) {
				out.addAll(List.of(x, 0, z));
			}
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	private static int[] solid(int n) {
		int[] a = new int[n];
		java.util.Arrays.fill(a, GhostModelTest.SOLID);
		return a;
	}
}
