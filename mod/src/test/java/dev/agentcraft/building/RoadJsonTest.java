package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** agentcraft-roads.json round trips and the world-start settling of removed roads. */
class RoadJsonTest {
	static Road road(String id) {
		return new Road(id, "b1", "b2", "minecraft:overworld", 2, true, false, 1759500000000L, 40, new int[] {1, 65, 2, 2, 65, 2},
			new int[] {6, 66, 4}, new int[] {1, 64, 2, 2, 64, 2, 6, 65, 4, 6, 66, 4}, List.of("3 cells of shallow water skipped (bridge off)"));
	}

	@Test
	void roundTrips() {
		Road r = road("r3");
		Road back = Road.fromJson(JsonParser.parseString(r.toJson().toString()).getAsJsonObject());
		assertEquals(r.id(), back.id());
		assertEquals(r.a(), back.a());
		assertEquals(r.dimension(), back.dimension());
		assertEquals(2, back.width());
		assertTrue(back.lanterns());
		assertFalse(back.bridge());
		assertArrayEquals(r.cells(), back.cells());
		assertArrayEquals(r.lanternCells(), back.lanternCells());
		assertArrayEquals(r.changes(), back.changes());
		assertEquals(r.notes(), back.notes());
		assertEquals(2, back.cellCount());
		assertEquals(4, back.changeCount());
		assertTrue(back.between("b2", "b1"));
		assertEquals("b2", back.other("b1"));
	}

	@Test
	void fileSkipsBrokenEntriesAndKeepsIdsUnique() {
		Road.FileData d = new Road.FileData(List.of(road("r3"), road("r7")), 4, List.of(new Road.Pending(road("r9"), "r9.removed-1.nbt", 5)));
		String json = d.toJson().toString().replace("\"roads\":[", "\"roads\":[{\"id\":\"broken\"},");
		Road.FileData back = Road.FileData.fromJson(JsonParser.parseString(json).getAsJsonObject());
		assertEquals(2, back.roads().size());
		assertEquals(10, back.next(), "never reuses an id, pending ones included");
		assertEquals("r9.removed-1.nbt", back.pending().get(0).snapshot());
		assertEquals(0, Road.number("x3"));
		assertArrayEquals(new int[] {1, 2, 3}, Road.triples(JsonParser.parseString("[1,2,3,4]")));
	}

	@Test
	void removedRoadsSettleOnTheCells() {
		assertEquals(Road.Settle.RELEASE, Road.settle(0, 40, 40));
		assertEquals(Road.Settle.RELEASE, Road.settle(5, 30, 40), "mostly restored: the player changed a few since");
		assertEquals(Road.Settle.RECORD_BACK, Road.settle(38, 0, 40), "the removal never reached the disk");
		assertEquals(Road.Settle.KEEP, Road.settle(0, 0, 40));
		assertEquals(Road.Settle.RELEASE, Road.settle(0, 0, 0));
	}

	static Road walk(String id, long created, int... cells) {
		return new Road(id, "b1", "b3", "minecraft:overworld", 2, true, false, created, cells.length / 3, cells, new int[0], new int[0], List.of());
	}

	@Test
	void removingARoadHandsTheCellsAnotherRoadWalksOnToIt() {
		// r6 walks over r3's cells at x 10..11 (feet y 65) and leaves at x 20; r3's changes: the path under x 10 and 11,
		// the cleared cell above x 11, a lantern post beside x 11, and a stretch r6 never uses (x 30)
		Road r6 = walk("r6", 2_000, 10, 65, 5, 11, 65, 5, 20, 70, 5);
		int[] r3changes = {10, 64, 5, 11, 64, 5, 11, 66, 5, 11, 65, 6, 30, 64, 5, 10, 60, 5};
		java.util.Map<Integer, String> h = Road.handover(r3changes, List.of(r6));
		assertEquals(java.util.Map.of(0, "r6", 1, "r6", 2, "r6", 3, "r6"), h, "x 30 and the cell five below the feet go back");
		// two roads over one cell: the nearer walker cell, then the newer road
		Road r7 = walk("r7", 3_000, 11, 65, 6);
		assertEquals("r7", Road.handover(new int[] {11, 64, 6}, List.of(r6, r7)).get(0));
		assertEquals("r6", Road.handover(new int[] {11, 64, 5}, List.of(r6, r7)).get(0));
		Road r8 = walk("r8", 4_000, 11, 65, 5);
		assertEquals("r8", Road.handover(new int[] {11, 64, 5}, List.of(r6, r8)).get(0));
		assertTrue(Road.handover(r3changes, List.of()).isEmpty());
		// the receiving road owns them: its changes grow, so another road leaves them alone and its own removal restores them
		Road more = r6.withChanges(new int[] {10, 64, 5});
		assertEquals(r6.changeCount() + 1, more.changeCount());
		assertArrayEquals(r6.cells(), more.cells());
		// a removal whose cells were all handed over keeps an empty snapshot: the next start releases it (never brings it back)
		assertEquals(Road.Settle.RELEASE, Road.settle(0, 0, 0));
	}
}
