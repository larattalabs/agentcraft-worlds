package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.layout.Anchors;
import org.junit.jupiter.api.Test;

/** Site warnings in front of the entrance and under the approach (docs/BUILDINGS.md "Site warnings"). */
class SiteWarningsTest {
	/** The box of {@link ApproachTest}: (0, 10, 0) .. (9, 19, 9), entrance at x 4.5 facing south (+z), door feet y 11. */
	static final Anchors.Bounds BOX = ApproachTest.BOX;
	static final int FEET = ApproachTest.FEET;

	static SiteWarnings.Result scan(TerrainFit.World w) {
		Approach.Plan a = ApproachTest.south(w);
		return SiteWarnings.scan(BOX, "south", 4.5, 4.5, FEET, Approach.DEFAULT_WIDTH, a, w);
	}

	/** Natural ground with its top at {@code top} (feet at top + 1), air above. */
	static TerrainFit.World flat(int top) {
		return (x, y, z) -> y <= top ? TerrainFit.NATURAL : TerrainFit.FILLABLE;
	}

	@Test
	void flatGroundHasNothingToSay() {
		SiteWarnings.Result r = scan(flat(FEET - 1));
		assertFalse(r.any(), r.warnings().toString());
		assertEquals(0, r.cells().length);
		assertNull(SiteWarnings.note(r));
	}

	@Test
	void waterInFrontOfThePathIsWarnedNotTheApproachOwn() {
		// a pond at z 18..19 (past the 6-row approach: rows 1..6 are z 10..15), x 2..6
		TerrainFit.World base = flat(FEET - 1);
		TerrainFit.World w = (x, y, z) -> z >= 18 && z <= 19 && x >= 2 && x <= 6 && y == FEET - 1 ? TerrainFit.FILLABLE | TerrainFit.WATER : base.flags(x, y, z);
		SiteWarnings.Result r = scan(w);
		assertEquals(10, r.water(), "5 columns × 2 rows in the zone (x 2..6 is the strip 3..5 grown by one)");
		assertEquals(0, r.lava());
		assertEquals(0, r.drops());
		assertTrue(r.warnings().get(0).startsWith("10 water blocks in front of the entrance"), r.warnings().toString());
	}

	@Test
	void lavaInFrontIsAWarningOnly() {
		TerrainFit.World base = flat(FEET - 1);
		TerrainFit.World w = (x, y, z) -> z == 18 && x == 4 && y == FEET - 1 ? TerrainFit.FILLABLE | TerrainFit.LAVA : base.flags(x, y, z);
		SiteWarnings.Result r = scan(w);
		assertEquals(1, r.lava());
		assertEquals("1 lava block in front of the entrance", r.warnings().get(0));
	}

	@Test
	void aDropPastTheApproachIsWarnedWithItsDepth() {
		// the ground falls by 5 at z >= 17 (two rows past the approach's end at z 15)
		TerrainFit.World w = (x, y, z) -> y <= (z >= 17 ? FEET - 6 : FEET - 1) ? TerrainFit.NATURAL : TerrainFit.FILLABLE;
		SiteWarnings.Result r = scan(w);
		assertEquals(0, r.openings());
		assertEquals(5, r.maxDrop());
		assertEquals(3 * 5, r.drops(), "rows 7..10 of the zone, 5 columns each, minus the rows before the drop");
		assertTrue(r.warnings().get(0).startsWith("a drop of up to 5 blocks in front of the entrance"), r.warnings().toString());
	}

	@Test
	void aSmallStepIsNoDrop() {
		TerrainFit.World w = (x, y, z) -> y <= (z >= 17 ? FEET - 1 - (SiteWarnings.DROP - 1) : FEET - 1) ? TerrainFit.NATURAL : TerrainFit.FILLABLE;
		assertEquals(0, scan(w).drops());
	}

	@Test
	void aBottomlessHoleIsACaveOpening() {
		// a shaft at x 4, z 17 with no ground within reach
		TerrainFit.World base = flat(FEET - 1);
		TerrainFit.World w = (x, y, z) -> x == 4 && z == 17 ? TerrainFit.FILLABLE : base.flags(x, y, z);
		SiteWarnings.Result r = scan(w);
		assertEquals(1, r.openings());
		assertEquals("a cave opening or a deep gully in front of the entrance (1 column)", r.warnings().get(0));
	}

	@Test
	void aGullyUnderTheApproachIsWarned() {
		// a deep ravine across rows 3..4 of the approach (z 12..13): the fill reaches 12 down and meets nothing
		TerrainFit.World base = flat(FEET - 1);
		TerrainFit.World w = (x, y, z) -> z >= 12 && z <= 13 && y > FEET - 40 ? TerrainFit.FILLABLE : base.flags(x, y, z);
		SiteWarnings.Result r = scan(w);
		assertTrue(r.gullies() > 0, r.warnings().toString());
		assertTrue(r.warnings().stream().anyMatch(s -> s.startsWith("the entrance path crosses a gully deeper than 12 blocks")), r.warnings().toString());
	}

	@Test
	void aCaveUnderAThinRoofUnderThePathIsWarned() {
		// solid ground with a cave two blocks under the surface, under row 2 of the path (z 11)
		TerrainFit.World base = flat(FEET - 1);
		TerrainFit.World w = (x, y, z) -> z == 11 && x == 4 && y == FEET - 4 ? TerrainFit.FILLABLE : base.flags(x, y, z);
		SiteWarnings.Result r = scan(w);
		assertEquals(1, r.caves());
		assertEquals("a cave under the entrance path (1 cell)", r.warnings().get(0));
		// deeper than CAVE_DEPTH under the ground it is no warning
		TerrainFit.World deep = (x, y, z) -> z == 11 && x == 4 && y == FEET - 3 - SiteWarnings.CAVE_DEPTH ? TerrainFit.FILLABLE : base.flags(x, y, z);
		assertEquals(0, scan(deep).caves());
	}

	@Test
	void treesAreNotTheGround() {
		// a tree trunk in front: logs are not ground, so they are no drop or water
		TerrainFit.World base = flat(FEET - 1);
		TerrainFit.World w = (x, y, z) -> x == 6 && z == 17 && y >= FEET && y < FEET + 5 ? TerrainFit.TREE : base.flags(x, y, z);
		assertFalse(scan(w).any());
	}

	@Test
	void withoutAnApproachTheRowsInFrontAreChecked() {
		TerrainFit.World w = (x, y, z) -> y <= (z >= 11 ? FEET - 5 : FEET - 1) ? TerrainFit.NATURAL : TerrainFit.FILLABLE;
		SiteWarnings.Result r = SiteWarnings.scan(BOX, "south", 4.5, 4.5, FEET, Approach.DEFAULT_WIDTH, Approach.Plan.EMPTY, w);
		assertEquals(4, r.maxDrop());
		assertEquals(5 * (SiteWarnings.AHEAD - 1), r.drops());
	}
}
