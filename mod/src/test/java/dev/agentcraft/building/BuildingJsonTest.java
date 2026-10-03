package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildingJsonTest {
	/** The sidecar example from docs/BUILDINGS.md. */
	static final String SIDECAR = """
		{
		  "id": "workshop",
		  "name": "Workshop",
		  "description": "A one-repo office: six desks, a task wall, a podium, a small lounge.",
		  "kind": "single",
		  "wings": 1,
		  "size": { "x": 21, "y": 9, "z": 17 },
		  "groundY": 1,
		  "front": "south",
		  "materials": "agentcraft",
		  "walk": { "minX": 1, "minY": 1, "minZ": 1, "maxX": 19, "maxY": 7, "maxZ": 15 },
		  "anchors": {
		    "desk_kit": { "x": 4.5, "y": 1.0, "z": 3.5, "yaw": 180.0, "pitch": 0.0 },
		    "task_wall@1": { "x": 10.5, "y": 3.0, "z": 15.0, "yaw": 0.0, "pitch": 0.0 }
		  }
		}
		""";

	@Test
	void sidecarParses() {
		Blueprint bp = Blueprint.fromJson(JsonParser.parseString(SIDECAR).getAsJsonObject());
		assertEquals("workshop", bp.id());
		assertEquals("Workshop", bp.name());
		assertEquals(21, bp.sizeX());
		assertEquals(9, bp.sizeY());
		assertEquals(17, bp.sizeZ());
		assertEquals(1, bp.groundY());
		assertEquals("south", bp.front());
		assertFalse(bp.isGroup());
		assertEquals(new Anchors.Bounds(1, 1, 1, 19, 7, 15), bp.walk());
		assertEquals(new Anchor("desk_kit", 4.5, 1.0, 3.5, 180f, 0f), bp.anchors().get("desk_kit"));
		assertTrue(bp.warnings().contains("missing anchor spawn"));
		assertFalse(bp.warnings().contains("missing anchor task_wall@1"));
	}

	@Test
	void sidecarRoundTrip() {
		Blueprint bp = Blueprint.fromJson(JsonParser.parseString(SIDECAR).getAsJsonObject());
		Blueprint again = Blueprint.fromJson(JsonParser.parseString(bp.toJson().toString()).getAsJsonObject());
		assertEquals(bp, again);
	}

	@Test
	void brokenSidecarsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> parse(SIDECAR.replace("\"workshop\"", "\"Work Shop\"")));
		assertThrows(IllegalArgumentException.class, () -> parse(SIDECAR.replace("\"kind\": \"single\"", "\"kind\": \"tower\"")));
		assertThrows(IllegalArgumentException.class, () -> parse(SIDECAR.replace("\"wings\": 1", "\"wings\": 2")));
		assertThrows(IllegalArgumentException.class, () -> parse(SIDECAR.replace("\"south\"", "\"up\"")));
		assertThrows(IllegalArgumentException.class, () -> parse(SIDECAR.replace("\"size\": { \"x\": 21, \"y\": 9, \"z\": 17 },", "")));
	}

	@Test
	void placedAnchorsFromTheSidecar() {
		Blueprint bp = parse(SIDECAR);
		// clockwise: the south front faces west; the task wall at z = 15.0 moves to x = 17 - 15 = 2
		Map<String, Anchor> w = BlueprintTransform.worldAnchors(bp, 1, 100, 64, -40, List.of("pocket-api"));
		assertTrue(w.containsKey("task_wall"));
		assertFalse(w.containsKey("task_wall@1"));
		Anchor tw = w.get("task_wall");
		assertEquals(100 + 17 - 15.0, tw.x(), 1e-9);
		assertEquals(64 + 3.0, tw.y(), 1e-9);
		assertEquals(-40 + 10.5, tw.z(), 1e-9);
		assertEquals(90f, tw.yaw(), 1e-4);
		Anchor desk = w.get("desk_kit");
		assertEquals(100 + 17 - 3.5, desk.x(), 1e-9);
		assertEquals(-40 + 4.5, desk.z(), 1e-9);
		assertEquals(-90f, desk.yaw(), 1e-4); // was facing north, now east
		// rotated walk box: x from 17-1-15 = 1 to 17-1-1 = 15, z from 1 to 19
		assertEquals(new Anchors.Bounds(101, 65, -39, 115, 71, -21), BlueprintTransform.worldBounds(bp, 1, 100, 64, -40));
	}

	@Test
	void buildingsFileRoundTrip() {
		Blueprint bp = parse(SIDECAR);
		Building a = new Building("b1", "workshop", List.of("pocket-api"), true, "clockwise_90", new Anchors.Bounds(100, 64, -40, 116, 72, -20),
			BlueprintTransform.worldBounds(bp, 1, 100, 64, -40), BlueprintTransform.worldAnchors(bp, 1, 100, 64, -40, List.of("pocket-api")),
			1759500000000L, "minecraft:overworld");
		Building b = new Building("b3", "campus", List.of("x", "y.z"), false, "none", new Anchors.Bounds(0, 60, 0, 30, 70, 30),
			new Anchors.Bounds(1, 61, 1, 29, 69, 29), Map.of("task_wall:x", new Anchor("task_wall:x", 1.5, 62, 0.0, 0f, 0f)), 1759500001234L,
			"minecraft:the_nether");
		JsonObject file = Building.fileJson(List.of(a, b), 4);
		assertEquals(List.of(100, 64, -40), List.of(file.getAsJsonArray("buildings").get(0).getAsJsonObject().getAsJsonArray("origin").get(0).getAsInt(),
			file.getAsJsonArray("buildings").get(0).getAsJsonObject().getAsJsonArray("origin").get(1).getAsInt(),
			file.getAsJsonArray("buildings").get(0).getAsJsonObject().getAsJsonArray("origin").get(2).getAsInt()));
		Building.FileData back = Building.fileFromJson(JsonParser.parseString(file.toString()).getAsJsonObject());
		assertEquals(4, back.next());
		assertEquals(List.of(a, b), back.buildings());
		assertEquals("building:b1", back.buildings().get(0).layout().name());
		assertEquals(1759500000000L, back.buildings().get(0).layout().revision());
		assertEquals("minecraft:the_nether", back.buildings().get(1).dimension());
		assertEquals("minecraft:the_nether", file.getAsJsonArray("buildings").get(1).getAsJsonObject().get("dimension").getAsString());
	}

	@Test
	void oldRecordsWithoutADimension() {
		// written before buildings recorded their dimension: parses, stays "unknown" (null), reads as the overworld
		JsonObject old = JsonParser.parseString("""
			{"id": "b2", "blueprint": "workshop", "repos": ["r"], "home": true, "rotation": "none",
			 "box": {"minX": 0, "minY": 60, "minZ": 0, "maxX": 28, "maxY": 74, "maxZ": 31}, "placedAt": 5}
			""").getAsJsonObject();
		Building b = Building.fromJson(old);
		assertNull(b.dimension());
		assertEquals(Building.OVERWORLD, b.dimensionOrDefault());
		// and it is written back without one (still an old record, so the hub keeps its fallback check)
		assertFalse(b.toJson().has("dimension"));
		assertEquals(b, Building.fromJson(b.toJson()));
		assertNull(b.withHome(false).dimension());
		Building placed = new Building("b4", "w", List.of("r"), false, "none", b.box(), b.box(), Map.of(), 6L, "minecraft:the_end");
		assertEquals("minecraft:the_end", placed.withHome(true).dimension());
	}

	@Test
	void nextIdNeverGoesBelowExistingIds() {
		JsonObject file = Building.fileJson(List.of(new Building("b7", "w", List.of("r"), true, "none", new Anchors.Bounds(0, 0, 0, 1, 1, 1),
			new Anchors.Bounds(0, 0, 0, 1, 1, 1), Map.of(), 1L, null)), 2);
		assertEquals(8, Building.fileFromJson(file).next());
	}

	@Test
	void boxesIntersect() {
		Anchors.Bounds a = new Anchors.Bounds(0, 0, 0, 9, 9, 9);
		assertTrue(Building.intersects(a, new Anchors.Bounds(9, 9, 9, 12, 12, 12)));
		assertFalse(Building.intersects(a, new Anchors.Bounds(10, 0, 0, 12, 9, 9)));
	}

	private static Blueprint parse(String json) {
		return Blueprint.fromJson(JsonParser.parseString(json).getAsJsonObject());
	}
}
