package dev.agentcraft.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.agentcraft.walk.OutdoorPlanner.Point;
import dev.agentcraft.walk.WalkRules.Inputs;
import dev.agentcraft.walk.WalkRules.Reason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalkLogicTest {
	static Inputs in(boolean enabled, double bx, double px, double render) {
		return new Inputs(enabled, true, true, 0, 0, bx, 0, px, 0, render);
	}

	@Test
	void decideOrderOfReasons() {
		assertEquals(Reason.DISABLED, WalkRules.decide(in(false, 50, 0, 160), c -> true));
		assertEquals(Reason.OTHER_DIMENSION, WalkRules.decide(new Inputs(true, false, true, 0, 0, 50, 0, 0, 0, 160), c -> true));
		assertEquals(Reason.NO_ENTRANCE, WalkRules.decide(new Inputs(true, true, false, 0, 0, 50, 0, 0, 0, 160), c -> true));
		assertEquals(Reason.TOO_FAR, WalkRules.decide(in(true, 257, 0, 160), c -> true));
		assertEquals(Reason.PLAYER_FAR, WalkRules.decide(new Inputs(true, true, true, 0, 0, 50, 0, 25, 200, 160), c -> true));
		assertEquals(Reason.UNLOADED, WalkRules.decide(in(true, 50, 0, 160), c -> WalkRules.chunkX(c) < 3));
		assertEquals(Reason.WALK, WalkRules.decide(in(true, 50, 0, 160), c -> true));
		assertEquals(Reason.WALK, WalkRules.decide(in(true, 256, 400, 160), c -> true)); // the player stands past b, within range
	}

	@Test
	void corridorCoversTheLineAndMargin() {
		long[] cs = WalkRules.corridorChunks(0.5, 0.5, 40.5, -20.5, 1);
		assertTrue(contains(cs, 0, 0));
		assertTrue(contains(cs, 2, -2));
		assertTrue(contains(cs, -1, 1));
		assertTrue(contains(cs, 3, -3));
		assertFalse(contains(cs, 6, 0));
		assertEquals(cs.length, Arrays.stream(cs).distinct().count());
		assertEquals(-2, WalkRules.chunkX(WalkRules.chunk(-2, 7)));
		assertEquals(7, WalkRules.chunkZ(WalkRules.chunk(-2, 7)));
		assertEquals(-7, WalkRules.chunkZ(WalkRules.chunk(5, -7)));
	}

	static boolean contains(long[] cs, int cx, int cz) {
		long k = WalkRules.chunk(cx, cz);
		return Arrays.stream(cs).anyMatch(c -> c == k);
	}

	@Test
	void segmentDistanceAndStuck() {
		assertEquals(5, WalkRules.segmentDistance(5, 5, 0, 0, 10, 0), 1e-9);
		assertEquals(5, WalkRules.segmentDistance(-3, 4, 0, 0, 10, 0), 1e-9);
		assertEquals(5, WalkRules.segmentDistance(3, 4, 0, 0, 0, 0), 1e-9);
		assertTrue(WalkRules.stuckTicks(100, 0.145) > 100 / 0.145);
		assertEquals(Reason.NO_PATH, Reason.of(OutdoorPlanner.Status.NO_PATH));
		assertEquals(Reason.NO_DOOR_PATH, Reason.of(OutdoorPlanner.Status.NO_GOAL));
	}

	@Test
	void cacheInvalidatesNearRouteOnly() {
		RouteCache cache = new RouteCache();
		GridTerrain t = GridTerrain.flat();
		OutdoorPlanner p = new OutdoorPlanner(t, new Point(0.5, 65, 0.5), new Point(30.5, 65, 0.5));
		p.runAll();
		String key = RouteCache.key("hq", "b2");
		assertEquals("hq>b2", key);
		cache.put(RouteCache.Route.of(key, p));
		assertNull(cache.get("b2>hq"));
		assertNotNull(cache.get(key));
		assertEquals(1, cache.hits());
		assertEquals(1, cache.misses());
		assertEquals(0, cache.invalidateNear(15, 65, 6)); // 6 to the side: untouched
		assertEquals(0, cache.invalidateNear(15, 75, 0)); // far above
		assertEquals(1, cache.invalidateNear(15, 64, 2)); // the ground next to the route
		assertEquals(0, cache.size());
		assertEquals(1, cache.invalidations());
	}

	@Test
	void cacheIsBoundedLru() {
		RouteCache cache = new RouteCache();
		OutdoorPlanner p = new OutdoorPlanner(GridTerrain.flat(), new Point(0.5, 65, 0.5), new Point(3.5, 65, 0.5));
		p.runAll();
		for (int i = 0; i < RouteCache.MAX + 5; i++) {
			cache.put(RouteCache.Route.of("a>" + i, p));
			cache.get("a>0"); // keep the first one fresh
		}
		assertEquals(RouteCache.MAX, cache.size());
		assertNotNull(cache.get("a>0"));
		assertNull(cache.get("a>1"));
		cache.clear();
		assertEquals(0, cache.size());
	}

	@Test
	void settingsDefaultOnAndRoundTrip(@TempDir Path dir) throws Exception {
		WalkSettings s = new WalkSettings();
		assertTrue(s.enabled("New World"));
		assertTrue(s.set("New World", false));
		assertFalse(s.enabled("New World"));
		assertTrue(s.enabled("Other"));
		assertTrue(s.dirty());
		Path f = dir.resolve("agentcraft").resolve(WalkSettings.FILE);
		s.save(f);
		assertFalse(s.dirty());
		WalkSettings back = WalkSettings.load(f);
		assertFalse(back.enabled("New World"));
		assertTrue(back.enabled("Other"));
		assertFalse(back.set("New World", false));
		Files.writeString(f, "{ not json");
		assertTrue(WalkSettings.load(f).enabled("New World"));
		WalkSettings odd = WalkSettings.fromJson(JsonParser.parseString("{\"worlds\":{\"a\":{\"walk\":\"no\"},\"b\":3,\"c\":{\"walk\":false}}}"));
		assertTrue(odd.enabled("a"));
		assertTrue(odd.enabled("b"));
		assertFalse(odd.enabled("c"));
		assertTrue(WalkSettings.load(dir.resolve("missing.json")).enabled("x"));
	}
}
