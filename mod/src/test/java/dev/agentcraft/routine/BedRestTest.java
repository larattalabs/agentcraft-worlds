package dev.agentcraft.routine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The client's bed wiring as pure logic: the spot beside a bed, staying in bed, arriving (docs/VILLAGE.md V3). */
class BedRestTest {
	/** (dx, dz) of south, west, north, east: the four facings a bed can have. */
	private static final int[][] FACINGS = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

	@Test
	void theSpotBesideABedIsNeverInsideIt() {
		for (int[] f : FACINGS) {
			int[][] cells = BedRest.approachCells(10, 64, 20, f[0], f[1]);
			assertEquals(5, cells.length);
			Set<List<Integer>> seen = new HashSet<>();
			for (int[] c : cells) {
				assertFalse(c[0] == 10 && c[2] == 20, "head cell for facing " + f[0] + "," + f[1]);
				assertFalse(c[0] == 10 - f[0] && c[2] == 20 - f[1], "foot cell for facing " + f[0] + "," + f[1]);
				assertEquals(64, c[1]);
				// every candidate touches the bed (beside head or foot, or straight beyond the foot)
				int dHead = Math.abs(c[0] - 10) + Math.abs(c[2] - 20);
				int dFoot = Math.abs(c[0] - (10 - f[0])) + Math.abs(c[2] - (20 - f[1]));
				assertEquals(1, Math.min(dHead, dFoot), "adjacent for facing " + f[0] + "," + f[1]);
				assertTrue(seen.add(List.of(c[0], c[1], c[2])), "distinct");
			}
		}
	}

	@Test
	void besideTheHeadFirstThenTheFootThenBeyond() {
		// a bed facing north (head at 10,64,20, foot at 10,64,21): left of north is west
		int[][] c = BedRest.approachCells(10, 64, 20, 0, -1);
		assertArrayEquals(new int[] {9, 64, 20}, c[0], "west of the head");
		assertArrayEquals(new int[] {11, 64, 20}, c[1], "east of the head");
		assertArrayEquals(new int[] {9, 64, 21}, c[2], "west of the foot");
		assertArrayEquals(new int[] {11, 64, 21}, c[3], "east of the foot");
		assertArrayEquals(new int[] {10, 64, 22}, c[4], "beyond the foot");
	}

	@Test
	void approachTakesTheFirstStandableCellAndItsFloor() {
		// only the cell east of the foot is free (a wall and a nightstand elsewhere), one step down (a sunken floor)
		BedRest.Floor floor = (x, y, z) -> x == 11 && y == 63 && z == 21 ? 63.0 : Double.NaN;
		assertArrayEquals(new double[] {11.5, 63.0, 21.5}, BedRest.approach(10, 64, 20, 0, -1, floor));
		BedRest.Floor open = (x, y, z) -> y == 64 ? 64.0 : Double.NaN;
		assertArrayEquals(new double[] {9.5, 64.0, 20.5}, BedRest.approach(10, 64, 20, 0, -1, open), "beside the head when free");
		// a bed boxed in on every side: no way in, so it does not count (Routines marks it taken)
		assertNull(BedRest.approach(10, 64, 20, 0, -1, (x, y, z) -> Double.NaN));
		// the head and foot cells are never asked for, even if the pathfinder would call the mattress standable
		BedRest.Floor onlyBed = (x, y, z) -> x == 10 && (z == 20 || z == 21) ? 64.5625 : Double.NaN;
		assertNull(BedRest.approach(10, 64, 20, 0, -1, onlyBed));
	}

	@Test
	void theRestSpotIsNamedAfterTheBedAndIsNeverASeat() {
		assertEquals("bed_2@rest", BedRest.restAnchor("bed_2"));
		// Seats.seatable skips names with '@': an agent walking to its bed must not be redirected onto a chair (it would
		// never "arrive" at bed_2@rest and never lie down)
		assertTrue(BedRest.restAnchor("bed").contains("@"));
	}

	@Test
	void aLyingAgentStaysOnlyWhileNothingChanged() {
		long head = 42L;
		assertTrue(BedRest.keepLying(false, false, false, true, "bed", head, "bed", head, false), "night, same bed");
		assertTrue(BedRest.keepLying(true, true, true, false, null, 0, "bed", head, true), "stale data: everything freezes");
		assertFalse(BedRest.keepLying(false, false, false, false, null, Long.MIN_VALUE, "bed", head, false), "morning: the plan is no longer rest");
		assertFalse(BedRest.keepLying(false, false, false, true, null, Long.MIN_VALUE, "bed", head, false), "rest without a bed (lounge)");
		assertFalse(BedRest.keepLying(false, false, false, true, "bed_2", 43L, "bed", head, false), "given another bed");
		assertFalse(BedRest.keepLying(false, false, false, true, "bed", 43L, "bed", head, false), "same name, another building's bed");
		assertFalse(BedRest.keepLying(false, true, false, true, "bed", head, "bed", head, false), "sent to another building");
		assertFalse(BedRest.keepLying(false, false, true, true, "bed", head, "bed", head, false), "snapped (teleport)");
		assertFalse(BedRest.keepLying(false, false, false, true, "bed", head, "bed", head, true), "the bed broke or the player lies in it");
	}

	@Test
	void arrivingNeedsTheSameTargetStoppedAndClose() {
		String want = BedRest.restAnchor("bed");
		assertTrue(BedRest.arrived(want, false, want, 0.1 * 0.1));
		assertFalse(BedRest.arrived(want, true, want, 0), "still walking");
		assertFalse(BedRest.arrived(null, false, want, 0), "no target");
		assertFalse(BedRest.arrived("lounge", false, want, 0), "still heading to the lounge");
		assertFalse(BedRest.arrived(want, false, want, 0.4 * 0.4), "not there yet");
		assertNotEquals(BedRest.restAnchor("bed"), BedRest.restAnchor("bed_2"));
	}

	@Test
	void atAStationMeansOneOfItsSlots() {
		assertTrue(BedRest.atStation("library", false, "library", 0));
		assertTrue(BedRest.atStation("library_2", false, "library", 0.5 * 0.5));
		assertTrue(BedRest.atStation("meeting~3", false, "meeting", 0));
		assertFalse(BedRest.atStation("library_2", true, "library", 0), "walking");
		assertFalse(BedRest.atStation("librarian", false, "library", 0), "another station sharing the prefix");
		assertFalse(BedRest.atStation("library", false, "library", 0.7 * 0.7), "too far");
		assertFalse(BedRest.atStation(null, false, "library", 0));
	}

	@Test
	void eachRoutineSendsTheAgentToItsStation() {
		assertEquals("lounge", RoutineRules.stationKey(RoutineRules.Kind.REST, null));
		assertEquals("library", RoutineRules.stationKey(RoutineRules.Kind.LIBRARY, "meeting"));
		assertEquals("meeting", RoutineRules.stationKey(RoutineRules.Kind.STANDUP, "meeting"));
		assertEquals("user", RoutineRules.stationKey(RoutineRules.Kind.STANDUP, "user"));
		assertNull(RoutineRules.stationKey(RoutineRules.Kind.STANDUP, null), "a stand-up not running for it: its own station");
		assertNull(RoutineRules.stationKey(RoutineRules.Kind.NONE, "meeting"), "work: its own station");
	}
}
