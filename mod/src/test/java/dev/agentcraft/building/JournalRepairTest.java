package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.journal.Journal;
import dev.agentcraft.layout.Anchors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The crash windows between a world journal commit and the buildings' / roads' file (docs/BUILDINGS.md "World journal"):
 * the journal is written first, so at world start the records catch up with it, and the evidence rules settle the rest.
 */
class JournalRepairTest {
	static final Anchors.Bounds A = new Anchors.Bounds(0, 64, 0, 9, 72, 9);
	static final Anchors.Bounds B = new Anchors.Bounds(40, 64, 0, 49, 72, 9);

	static Building b(String id, Anchors.Bounds box, boolean home) {
		return new Building(id, "workshop", List.of("pocket-notes-" + id), home, "none", box, box, Map.of(), 1L, "minecraft:overworld");
	}

	static int[] box(Anchors.Bounds b) {
		return new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
	}

	static Map<String, Building> records(Building... bs) {
		Map<String, Building> m = new LinkedHashMap<>();
		for (Building x : bs) {
			m.put(x.id(), x);
		}
		return m;
	}

	@Test
	void consistentRecordsAreLeftAlone() {
		Buildings.Repaired r = Buildings.repair(records(b("b1", A, true)), List.of(),
			List.of(new Buildings.SiteEntry("j1", "b1", Journal.Status.ACTIVE, box(A), 0L)), s -> null, id -> null);
		assertTrue(r.notes().isEmpty());
		assertEquals(1, r.records().size());
	}

	@Test
	void aRemovalTheFileMissedBecomesAPendingRemoval() {
		Building b1 = b("b1", A, true);
		Buildings.Repaired r = Buildings.repair(records(b1, b("b2", B, false)), List.of(),
			List.of(new Buildings.SiteEntry("j1", "b1", Journal.Status.UNDONE, box(A), 77L), new Buildings.SiteEntry("j2", "b2", Journal.Status.ACTIVE,
				box(B), 0L)), s -> null, id -> null);
		assertFalse(r.records().containsKey("b1"));
		assertTrue(r.records().get("b2").home(), "home passes on");
		assertEquals(1, r.pending().size());
		assertEquals("j1", r.pending().get(0).snapshot());
		assertEquals("removed", r.pending().get(0).why());
		assertEquals(77L, r.pending().get(0).at());
		assertTrue(r.notes().containsKey("b1"));
	}

	@Test
	void anUndoneEntryAPendingRecordNamesIsNotTakenTwice() {
		Building b1 = b("b1", A, true);
		Buildings.Repaired r = Buildings.repair(records(b1), List.of(new Building.Pending(b1, "j1", 5L, "removed")),
			List.of(new Buildings.SiteEntry("j1", "b1", Journal.Status.UNDONE, box(A), 5L)), s -> s, id -> null);
		assertTrue(r.records().containsKey("b1"), "an older removal of the same box (the building was placed on its old site again)");
		assertEquals(1, r.pending().size());
	}

	@Test
	void aMoveTheFileMissedFollowsTheJournal() {
		Building old = b("b1", A, true);
		Building moved = b("b1", B, false);
		Buildings.Repaired r = Buildings.repair(records(old), List.of(),
			List.of(new Buildings.SiteEntry("j1", "b1", Journal.Status.UNDONE, box(A), 9L), new Buildings.SiteEntry("j2", "b1", Journal.Status.ACTIVE,
				box(B), 0L)), s -> null, id -> id.equals("j2") ? moved : null);
		assertEquals(B, r.records().get("b1").box());
		assertTrue(r.records().get("b1").home(), "the record keeps its home flag");
		assertEquals(1, r.pending().size());
		assertEquals("moved", r.pending().get(0).why());
		assertEquals("j1", r.pending().get(0).snapshot());
		assertEquals(A, r.pending().get(0).building().box());
	}

	@Test
	void aPlacementTheFileMissedGetsItsRecordBack() {
		Building lost = b("b3", B, true);
		Buildings.Repaired r = Buildings.repair(records(b("b1", A, true)), List.of(),
			List.of(new Buildings.SiteEntry("j1", "b1", Journal.Status.ACTIVE, box(A), 0L), new Buildings.SiteEntry("j3", "b3", Journal.Status.ACTIVE,
				box(B), 0L)), s -> null, id -> id.equals("j3") ? lost : null);
		assertTrue(r.records().containsKey("b3"));
		assertFalse(r.records().get("b3").home(), "b1 stays home");
		// over another building now: not added
		Buildings.Repaired over = Buildings.repair(records(b("b1", B, true)), List.of(),
			List.of(new Buildings.SiteEntry("j1", "b1", Journal.Status.ACTIVE, box(B), 0L), new Buildings.SiteEntry("j3", "b3", Journal.Status.ACTIVE,
				box(B), 0L)), s -> null, id -> id.equals("j3") ? lost : null);
		assertFalse(over.records().containsKey("b3"));
	}

	@Test
	void aRecordWithoutAnyEntryStaysAsItIs() {
		// an imported record whose snapshot was missing: Remove refuses, Forget works
		Buildings.Repaired r = Buildings.repair(records(b("b1", A, true)), List.of(), List.of(), s -> null, id -> null);
		assertTrue(r.records().containsKey("b1"));
		assertTrue(r.notes().isEmpty());
	}

	// ------------------------------------------------------------------ roads

	static Road road(String id, String a, String b) {
		return new Road(id, a, b, "minecraft:overworld", 2, true, false, 1L, 3, new int[] {1, 65, 2}, new int[0], new int[] {1, 64, 2}, List.of());
	}

	@Test
	void aRoadRemovalTheFileMissedBecomesPending() {
		Map<String, Road> roads = new LinkedHashMap<>();
		roads.put("r2", road("r2", "b1", "b2"));
		Roads.Repaired r = Roads.repair(roads, List.of(), List.of(new Roads.RoadEntry("j5", "r2", Journal.Status.UNDONE, 8L)), s -> null, id -> null);
		assertTrue(r.roads().isEmpty());
		assertEquals("j5", r.pending().get(0).snapshot());
	}

	@Test
	void aRoadLaidWhoseRecordWasLostComesBackUnlessItsPairHasOne() {
		Road lost = road("r3", "b1", "b2");
		Roads.Repaired r = Roads.repair(new LinkedHashMap<>(), List.of(), List.of(new Roads.RoadEntry("j6", "r3", Journal.Status.ACTIVE, 0L)), s -> null,
			id -> lost);
		assertTrue(r.roads().containsKey("r3"));
		Map<String, Road> taken = new LinkedHashMap<>();
		taken.put("r4", road("r4", "b2", "b1"));
		Roads.Repaired t = Roads.repair(taken, List.of(), List.of(new Roads.RoadEntry("j6", "r3", Journal.Status.ACTIVE, 0L),
			new Roads.RoadEntry("j7", "r4", Journal.Status.ACTIVE, 0L)), s -> null, id -> id.equals("j6") ? lost : null);
		assertFalse(t.roads().containsKey("r3"));
		assertEquals(1, t.notes().size());
	}
}
