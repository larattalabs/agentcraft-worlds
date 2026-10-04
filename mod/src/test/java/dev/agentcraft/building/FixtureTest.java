package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.layout.Anchors;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fixture blueprints and records (docs/VILLAGE.md V2: the village board is a placeable fixture, not a building). */
class FixtureTest {
	static final String SIDECAR = """
		{
		  "id": "village_board",
		  "name": "Village board",
		  "kind": "fixture",
		  "wings": 0,
		  "size": { "x": 7, "y": 6, "z": 4 },
		  "groundY": 1,
		  "front": "south",
		  "approach": false,
		  "walk": { "minX": 0, "minY": 1, "minZ": 2, "maxX": 6, "maxY": 2, "maxZ": 3 },
		  "anchors": {
		    "board": { "x": 3.5, "y": 2.5, "z": 1.0, "yaw": 0.0, "pitch": 0.0 },
		    "spawn": { "x": 3.5, "y": 1.0, "z": 3.5, "yaw": 180.0, "pitch": 0.0 }
		  }
		}
		""";

	static JsonObject sidecar() {
		return JsonParser.parseString(SIDECAR).getAsJsonObject();
	}

	@Test
	void fixtureSidecarParses() {
		Blueprint bp = Blueprint.fromJson(sidecar());
		assertTrue(bp.isFixture());
		assertFalse(bp.isGroup());
		assertEquals(0, bp.wings());
		assertFalse(bp.approach().enabled());
		// no desks, monitors, stations or task walls are expected of a fixture
		assertEquals(List.of(), bp.warnings());
		assertEquals(bp, Blueprint.fromJson(JsonParser.parseString(bp.toJson().toString()).getAsJsonObject()));
	}

	@Test
	void fixtureWingsDefaultToZeroAndOthersAreRefused() {
		JsonObject o = sidecar();
		o.remove("wings");
		assertEquals(0, Blueprint.fromJson(o).wings());
		o.addProperty("wings", 1);
		assertThrows(IllegalArgumentException.class, () -> Blueprint.fromJson(o));
		JsonObject wing = sidecar();
		wing.getAsJsonObject("anchors").add("board@1", wing.getAsJsonObject("anchors").get("board"));
		assertThrows(IllegalArgumentException.class, () -> Blueprint.fromJson(wing));
		JsonObject bad = sidecar();
		bad.addProperty("kind", "kiosk");
		assertThrows(IllegalArgumentException.class, () -> Blueprint.fromJson(bad));
	}

	@Test
	void aRecordWithoutReposIsAFixtureAndSaysSo() {
		Anchors.Bounds box = new Anchors.Bounds(0, 64, 0, 6, 69, 3);
		Building f = new Building("b4", "village_board", List.of(), false, "none", box, box, Map.of(), 1L, Building.OVERWORLD);
		assertTrue(f.isFixture());
		JsonObject j = f.toJson();
		assertEquals("fixture", j.get("kind").getAsString());
		assertEquals(0, j.getAsJsonArray("repos").size());
		Building back = Building.fromJson(j);
		assertTrue(back.isFixture());
		assertEquals(f, back);
		Building b = new Building("b1", "workshop", List.of("api"), true, "none", box, box, Map.of(), 1L, Building.OVERWORLD);
		assertFalse(b.isFixture());
		assertFalse(b.toJson().has("kind"));
	}

	static Building rec(String id, boolean fixture, boolean home) {
		Anchors.Bounds box = new Anchors.Bounds(0, 64, 0, 6, 69, 3);
		return new Building(id, fixture ? "village_board" : "workshop", fixture ? List.of() : List.of("repo-" + id), home, "none", box, box, Map.of(), 1L,
			Building.OVERWORLD);
	}

	static Map<String, Building> map(Building... bs) {
		Map<String, Building> m = new java.util.LinkedHashMap<>();
		for (Building b : bs) {
			m.put(b.id(), b);
		}
		return m;
	}

	@Test
	void aFixtureIsNeverHome() {
		// placed first: the next building is still the first one (place() uses noBuilding)
		assertTrue(Buildings.noBuilding(map()));
		assertTrue(Buildings.noBuilding(map(rec("b1", true, false))));
		assertFalse(Buildings.noBuilding(map(rec("b1", true, false), rec("b2", false, true))));
		// the home building removed with only a fixture left: no home at all
		Map<String, Building> m = map(rec("b1", true, false));
		Buildings.rehome(m, rec("b2", false, true));
		assertFalse(m.get("b1").home());
		// ... with a building after the fixture: that building
		m = map(rec("b1", true, false), rec("b3", false, false));
		Buildings.rehome(m, rec("b2", false, true));
		assertFalse(m.get("b1").home());
		assertTrue(m.get("b3").home());
		// a file whose fixture says home: cleared, the first building becomes home
		m = map(rec("b1", true, true), rec("b2", false, false), rec("b3", false, false));
		Buildings.normalizeHome(m);
		assertFalse(m.get("b1").home());
		assertTrue(m.get("b2").home());
		assertFalse(m.get("b3").home());
		// only fixtures: nobody is home
		m = map(rec("b1", true, true));
		Buildings.normalizeHome(m);
		assertFalse(m.get("b1").home());
	}

	@Test
	void ghostRefusalsForAFixture() {
		assertEquals(List.of(), GhostModel.refusals(true, List.of(), 0, List.of(), 60, 70, -64, 319, List.of(), 0, false));
		assertEquals(List.of("a fixture takes no repos"), GhostModel.refusals(true, List.of("a"), 0, List.of(), 60, 70, -64, 319, List.of(), 0,
			false));
		assertEquals(List.of("overlaps building b2"), GhostModel.refusals(true, List.of(), 0, List.of(), 60, 70, -64, 319, List.of("b2"), 0,
			false));
		// a building still needs its repo
		assertEquals(List.of("no repo chosen"), GhostModel.refusals(false, List.of(), 1, List.of(), 60, 70, -64, 319, List.of(), 0, false));
	}

	@Test
	void allIsEverySiteSoSpatialCallersSeeFixturesAndBuildingsLeavesThemOut() {
		Anchors.Bounds boardBox = new Anchors.Bounds(0, 64, 0, 6, 69, 3);
		Anchors.Bounds shopBox = new Anchors.Bounds(20, 64, 0, 30, 72, 10);
		Building shop = new Building("b1", "workshop", List.of("api"), true, "none", shopBox, shopBox, Map.of(), 1L, Building.OVERWORLD);
		Building board = new Building("b2", "village_board", List.of(), false, "none", boardBox, boardBox, Map.of(), 2L, Building.OVERWORLD);
		try {
			Buildings.setForTest(List.of(shop, board));
			// all() is the safe default for overlap and collision (roads, the ghost): a road must never be laid into a
			// fixture's restore box, or removing either one restores its snapshot over the other
			assertEquals(List.of(shop, board), Buildings.all());
			assertTrue(Buildings.all().stream().anyMatch(b -> b.restoreBox().equals(boardBox)));
			assertEquals(List.of(shop), Buildings.buildings());
			assertEquals(List.of(board), Buildings.fixtures());
			assertEquals(List.of(shop), Buildings.withoutFixtures(List.of(board, shop)));
		} finally {
			Buildings.setForTest(List.of());
		}
	}
}
