package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.journal.Journal;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Value;
import dev.agentcraft.journal.WorldJournal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Two-block plants cut by a site's box (docs/BUILDINGS.md "Cut plants"): the placement loses the outside half (vanilla drops
 * it with its inside half), so it is a guard cell of the site's held-leaves entry ({@code before} the half, {@code after} what
 * the placement left), undone with the site and written quietly right after the box ({@link WorldJournal#phases}), whose
 * late pass writes the inside half quietly too, so the plant is whole again.
 */
class CutPlantsTest {
	static final Value LOWER = Value.of("minecraft:tall_grass", "half", "lower");
	static final Value UPPER = Value.of("minecraft:tall_grass", "half", "upper");
	static final Value SUN_LOWER = Value.of("minecraft:sunflower", "half", "lower");
	static final Value SUN_UPPER = Value.of("minecraft:sunflower", "half", "upper");
	static final Value PLANKS = Value.of("minecraft:oak_planks");
	static final Value COBBLE = Value.of("minecraft:cobblestone");
	static final Value AIR = Journal.AIR;

	// the box's top row is y = 70: a tall grass at (2, 70, 2) reaches out of it; a sunflower at (5, 63/64, 5) under its floor
	static final long IN_TOP = Journal.pos(2, 70, 2);
	static final long OUT_TOP = Journal.pos(2, 71, 2);
	static final long OUT_BOTTOM = Journal.pos(5, 63, 5);
	static final long IN_BOTTOM = Journal.pos(5, 64, 5);
	static final long LEAF = Journal.pos(9, 71, 2);

	static boolean leaf(Value v) {
		return v.name().endsWith("_leaves");
	}

	/** The world after placing site a over the box cells: the box's plants' inside halves are planks, their outside halves air. */
	static LeafGuardTest.Sim placed() {
		LeafGuardTest.Sim s = new LeafGuardTest.Sim();
		s.world.put(IN_TOP, LOWER);
		s.world.put(OUT_TOP, UPPER);
		s.world.put(OUT_BOTTOM, SUN_LOWER);
		s.world.put(IN_BOTTOM, SUN_UPPER);
		s.world.put(LEAF, LeafGuardTest.leaf(2, false));
		Map<Long, Value> box = new LinkedHashMap<>();
		box.put(IN_TOP, PLANKS);
		box.put(IN_BOTTOM, PLANKS);
		s.change("a-box", "building", Policy.BOX, box);
		// held leaves and the guard cells: one entry
		Map<Long, Value> leaves = new LinkedHashMap<>();
		leaves.put(LEAF, LeafGuardTest.leaf(2, true));
		leaves.put(OUT_TOP, AIR);
		leaves.put(OUT_BOTTOM, AIR);
		s.change("a-leaves", LeafGuard.KIND, Policy.CELL, leaves);
		return s;
	}

	@Test
	void removeWritesTheBoxThenTheOutsideHalves() {
		LeafGuardTest.Sim s = placed();
		Journal.UndoPlan p = s.remove("a");
		assertEquals(UPPER, s.world.get(OUT_TOP));
		assertEquals(LOWER, s.world.get(IN_TOP));
		assertEquals(SUN_LOWER, s.world.get(OUT_BOTTOM));
		assertEquals(SUN_UPPER, s.world.get(IN_BOTTOM));
		assertEquals(LeafGuardTest.leaf(2, false), s.world.get(LEAF));
		WorldJournal.Phases ph = WorldJournal.phases(p, CutPlantsTest::leaf);
		assertEquals(List.of(OUT_BOTTOM, OUT_TOP), ph.guards().stream().map(Journal.Write::pos).toList(), "the halves right after the box, lowest first");
		assertEquals(Map.of(IN_TOP, LOWER, IN_BOTTOM, SUN_UPPER), ph.boxes().get("a-box"));
		assertEquals(List.of(LEAF), ph.cells().stream().map(Journal.Write::pos).toList(), "the held leaf after the box, as before");
	}

	@Test
	void aHalfThePlayerChangedIsLeftAlone() {
		LeafGuardTest.Sim s = placed();
		s.world.put(OUT_TOP, COBBLE);
		s.remove("a");
		assertEquals(COBBLE, s.world.get(OUT_TOP), "the CELL rule: not the placement's air any more");
		assertEquals(SUN_LOWER, s.world.get(OUT_BOTTOM));
	}

	@Test
	void aLaterSiteOverTheOutsideHalfUndoesInEitherOrder() {
		for (boolean aFirst : new boolean[] {true, false}) {
			LeafGuardTest.Sim s = placed();
			s.change("b-box", "building", Policy.BOX, Map.of(OUT_TOP, PLANKS)); // its box takes in the air above a's box
			if (aFirst) {
				s.remove("a");
				assertEquals(PLANKS, s.world.get(OUT_TOP), "b still stands there");
				s.remove("b");
			} else {
				s.remove("b");
				assertEquals(AIR, s.world.get(OUT_TOP), "b gives back what it found");
				s.remove("a");
			}
			assertEquals(UPPER, s.world.get(OUT_TOP), "a first: " + aFirst);
			assertEquals(LOWER, s.world.get(IN_TOP));
			assertTrue(s.entries.values().stream().noneMatch(Journal.Entry::active));
		}
	}
}
