package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.agentcraft.layout.Anchors;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The entrance approach (docs/BUILDINGS.md "Entrance approach") against fake worlds. */
class ApproachTest {
	/** A 10 x 10 x 10 box at (0, 10, 0); the door's feet at y 11 (groundY 1), entrance column x 4. */
	static final Anchors.Bounds BOX = new Anchors.Bounds(0, 10, 0, 9, 19, 9);
	static final int FEET = 11;

	interface Height {
		int top(int x, int z);
	}

	/** Natural ground up to {@code h.top}, air above. */
	static TerrainFit.World ground(Height h) {
		return (x, y, z) -> y <= h.top(x, z) ? TerrainFit.NATURAL : TerrainFit.FILLABLE;
	}

	static Approach.Plan south(TerrainFit.World w) {
		return Approach.plan(BOX, "south", 4.5, 4.5, FEET, Approach.Spec.DEFAULT, w);
	}

	static Set<Long> cells(int[] xyz) {
		Set<Long> s = new HashSet<>();
		for (int i = 0; i < xyz.length; i += 3) {
			s.add(key(xyz[i], xyz[i + 1], xyz[i + 2]));
		}
		return s;
	}

	static long key(int x, int y, int z) {
		return ((long) x << 40) ^ ((long) y << 20) ^ z;
	}

	/** No step between rows is more than a block, counting the half-step slabs (what an agent walks). */
	static void assertGentle(Approach.Plan p) {
		int[] f = p.feet();
		for (int i = 1; i < f.length; i++) {
			assertTrue(Math.abs(f[i] - f[i - 1]) <= 1, "step " + (f[i] - f[i - 1]) + " at row " + i);
			double d = Approach.floor(f, i) - Approach.floor(f, i - 1);
			assertTrue(Math.abs(d) <= 1.0, "walked step " + d + " at row " + i);
		}
	}

	@Test
	void flatGroundAtTheDoorIsAPathOnTheSurface() {
		Approach.Plan p = south(ground((x, z) -> 10));
		assertEquals(Approach.DEFAULT_LENGTH, p.rows());
		assertArrayEquals(new int[] {11, 11, 11, 11, 11, 11, 11}, p.feet());
		assertEquals(3 * 6, p.pathCount());
		assertEquals(0, p.fillCount());
		assertEquals(0, p.clearCount());
		assertEquals(0, p.slabs().length);
		// rows z 10..15 (just outside the box's south face z 9), columns x 3..5, path one below the feet
		assertEquals(new Anchors.Bounds(3, 10, 10, 5, 10, 15), p.bounds());
		assertArrayEquals(new double[] {4.5, 11, 15.5}, p.end());
		assertEquals(new Anchors.Bounds(0, 10, 0, 9, 19, 15), p.union(BOX));
	}

	@Test
	void theSnapshotBoxReachesOneRowBelowTheLowestWrittenCell() {
		// the ground under the floor and the foundation changes while the site stands (grass -> dirt): Remove puts it back
		Approach.Plan p = south(ground((x, z) -> 10));
		TerrainFit.Plan noFill = new TerrainFit.Plan(new int[0], new int[0], new int[0], 0, new int[0], 0, BOX.minY());
		assertEquals(new Anchors.Bounds(0, 9, 0, 9, 19, 15), Buildings.snapshotBox(BOX, noFill, p, -64));
		TerrainFit.Plan fill = new TerrainFit.Plan(new int[] {4, 7, 4}, new int[0], new int[0], 0, new int[0], 0, 7);
		assertEquals(new Anchors.Bounds(0, 6, 0, 9, 19, 9), Buildings.snapshotBox(BOX, fill, Approach.Plan.EMPTY, -64));
		// at the level's floor the extra row is left out (a written cell below it still leaves the build height)
		assertEquals(new Anchors.Bounds(0, 7, 0, 9, 19, 9), Buildings.snapshotBox(BOX, fill, Approach.Plan.EMPTY, 7));
		assertEquals(new Anchors.Bounds(0, 6, 0, 9, 19, 9), Buildings.snapshotBox(BOX, fill, Approach.Plan.EMPTY, 6));
	}

	@Test
	void aDropIsReachedOneBlockPerRowWithHalfSteps() {
		// the ground falls away right outside the box: top at y 4 (feet 5), six below the door
		Approach.Plan p = south(ground((x, z) -> z <= 9 ? 10 : 4));
		assertArrayEquals(new int[] {11, 10, 9, 8, 7, 6, 5}, p.feet());
		assertGentle(p);
		// every row sits lower than the one before: a slab in each, so each step is two half steps
		assertEquals(3 * 6, p.slabs().length / 3);
		assertTrue(cells(p.slabs()).contains(key(4, 10, 10)));
		// fill below the path down to the ground: row 1 (path y 9) fills y 8..5, ..., row 6 (path y 4) is on the ground
		assertEquals(3 * (4 + 3 + 2 + 1 + 0 + 0), p.fillCount());
		assertTrue(cells(p.fill()).contains(key(4, 5, 10)));
		assertArrayEquals(new double[] {4.5, 5.5, 15.5}, p.end());
		assertEquals(4, p.bounds().minY());
	}

	@Test
	void aBankIsCutIntoOpenToTheSky() {
		// a bank right outside the door: top at y 16 (feet 17), six above
		Approach.Plan p = south(ground((x, z) -> z <= 9 ? 10 : 16));
		assertArrayEquals(new int[] {11, 12, 13, 14, 15, 16, 17}, p.feet());
		assertGentle(p);
		Set<Long> clear = cells(p.clear());
		// row 1 (feet 12): path at y 11, headroom 12..14 cleared, then the bank up to its top (y 16) as well
		for (int y = 12; y <= 16; y++) {
			assertTrue(clear.contains(key(4, y, 10)), "y " + y);
		}
		assertFalse(clear.contains(key(4, 17, 10))); // air above the bank stays
		// a climb straight from the door: full steps (a slab at the foot of each would make the first step 1.5)
		assertEquals(0, p.slabs().length);
		// a level stretch before the climb: its last row gets the half step
		Approach.Plan q = south(ground((x, z) -> z <= 12 ? 10 : 13));
		assertArrayEquals(new int[] {11, 11, 11, 11, 12, 13, 14}, q.feet());
		assertEquals(3, q.slabs().length / 3);
		assertTrue(cells(q.slabs()).contains(key(4, 11, 12)));
		assertGentle(q);
		assertEquals(0, p.fillCount());
	}

	@Test
	void aLongDropExtendsTheApproachUntilItMeetsTheGround() {
		Approach.Plan p = south(ground((x, z) -> z <= 9 ? 10 : 1)); // nine below
		assertEquals(9, p.rows());
		assertEquals(2, p.ground());
		assertEquals(2, p.feet()[9]);
		assertGentle(p);
		assertNull(Approach.shortWarning(p));
		Approach.Plan far = south(ground((x, z) -> z <= 9 ? 10 : -20)); // too deep: stops after length + EXTEND rows
		assertEquals(Approach.DEFAULT_LENGTH + Approach.EXTEND, far.rows());
		assertNotNull(Approach.shortWarning(far));
		assertGentle(far);
	}

	@Test
	void theProfileNeverStepsMoreThanOneOnRoughTerrain() {
		Random r = new Random(7);
		for (int t = 0; t < 200; t++) {
			long seed = r.nextLong();
			Approach.Plan p = south(ground((x, z) -> z <= 9 ? 10 : 10 + (int) (new Random(seed ^ (x * 31L + z * 1009L)).nextGaussian() * 6)));
			assertGentle(p);
			assertTrue(p.rows() >= Approach.DEFAULT_LENGTH && p.rows() <= Approach.DEFAULT_LENGTH + Approach.EXTEND);
		}
	}

	@Test
	void everyFrontPutsTheStripOutsideItsFace() {
		TerrainFit.World flat = ground((x, z) -> 10);
		Approach.Plan n = Approach.plan(BOX, "north", 4.5, 4.5, FEET, Approach.Spec.DEFAULT, flat);
		assertEquals(new Anchors.Bounds(3, 10, -6, 5, 10, -1), n.bounds());
		Approach.Plan e = Approach.plan(BOX, "east", 9.5, 6.5, FEET, Approach.Spec.DEFAULT, flat);
		assertEquals(new Anchors.Bounds(10, 10, 5, 15, 10, 7), e.bounds());
		Approach.Plan w = Approach.plan(BOX, "west", 0.5, 2.5, FEET, Approach.Spec.DEFAULT, flat);
		assertEquals(new Anchors.Bounds(-6, 10, 1, -1, 10, 3), w.bounds());
		assertArrayEquals(new double[] {-5.5, 11, 2.5}, w.end());
		// a wider spec with an even width: columns centre-1 .. centre+2
		Approach.Plan wide = Approach.plan(BOX, "south", 4.5, 4.5, FEET, new Approach.Spec(2, 4, Approach.DEFAULT_BLOCK, Approach.DEFAULT_SLAB), flat);
		assertEquals(new Anchors.Bounds(3, 10, 10, 6, 10, 11), wide.bounds());
	}

	@Test
	void treesAreNotGroundAndAreClearedFromTheHeadroom() {
		// a tree trunk (logs y 11..14) and canopy at x 4, z 12 on flat ground
		TerrainFit.World w = (x, y, z) -> y <= 10 ? TerrainFit.NATURAL : x == 4 && z == 12 && y <= 16 ? TerrainFit.TREE : TerrainFit.FILLABLE;
		Approach.Plan p = south(w);
		assertArrayEquals(new int[] {11, 11, 11, 11, 11, 11, 11}, p.feet());
		Set<Long> clear = cells(p.clear());
		assertTrue(clear.contains(key(4, 11, 12)) && clear.contains(key(4, 13, 12)));
		assertFalse(clear.contains(key(4, 14, 12))); // above the headroom a tree is not terrain: it stays
	}

	@Test
	void waterWarnsLavaRefusesBlockEntitiesAreReported() {
		// a pond (water y 8..10 over ground at y 7) in front of the door
		TerrainFit.World pond = (x, y, z) -> y <= 7 || z <= 9 && y <= 10 ? TerrainFit.NATURAL : y <= 10 ? TerrainFit.WATER | TerrainFit.FILLABLE
			: TerrainFit.FILLABLE;
		Approach.Plan p = south(pond);
		assertArrayEquals(new int[] {11, 11, 11, 11, 11, 11, 11}, p.feet()); // the water's surface is the target
		assertNotNull(Approach.waterWarning(p));
		assertNull(Approach.lavaRefusal(p));
		assertEquals(3 * 6 * 2, p.fillCount()); // y 9 and 8 under each path block (y 10)
		// lava beside the strip refuses
		TerrainFit.World lava = (x, y, z) -> x == 6 && z == 12 && y == 11 ? TerrainFit.LAVA | TerrainFit.FILLABLE : y <= 10 ? TerrainFit.NATURAL
			: TerrainFit.FILLABLE;
		assertNotNull(Approach.lavaRefusal(south(lava)));
		// a chest on the strip
		TerrainFit.World chest = (x, y, z) -> x == 3 && z == 11 && y == 11 ? TerrainFit.BLOCK_ENTITY : y <= 10 ? TerrainFit.NATURAL
			: TerrainFit.FILLABLE;
		assertEquals(1, south(chest).blockEntityCount());
	}

	@Test
	void specParsing() {
		assertEquals(Approach.Spec.DEFAULT, Approach.Spec.fromJson(null));
		assertFalse(Approach.Spec.fromJson(JsonParser.parseString("false")).enabled());
		Approach.Spec s = Approach.Spec.fromJson(JsonParser.parseString("{\"length\": 8, \"width\": 5, \"block\": \"gravel\"}"));
		assertEquals(new Approach.Spec(8, 5, "minecraft:gravel", Approach.DEFAULT_SLAB), s);
		assertEquals(s, Approach.Spec.fromJson(s.toJson()));
		assertThrows(IllegalArgumentException.class, () -> Approach.Spec.fromJson(JsonParser.parseString("{\"width\": 0}")));
		assertThrows(IllegalArgumentException.class, () -> Approach.Spec.fromJson(JsonParser.parseString("{\"length\": 40}")));
		assertEquals(Approach.Plan.EMPTY, Approach.plan(BOX, "south", 4.5, 4.5, FEET, Approach.Spec.NONE, ground((x, z) -> 10)));
	}
}
