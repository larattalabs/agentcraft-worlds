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
}
