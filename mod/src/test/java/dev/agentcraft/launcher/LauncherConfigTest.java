package dev.agentcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LauncherConfigTest {
	private static final java.util.function.Function<String, String> NO_ENV = k -> null;

	@Test
	void defaultsWithoutAFile() {
		LauncherConfig c = LauncherConfig.parse(null, NO_ENV);
		assertTrue(c.enabled());
		assertFalse(c.stopOnExit());
		assertNull(c.foremanDir());
		assertNull(c.nodePath());
		assertNull(c.stable());
		assertTrue(c.problems().isEmpty());
	}

	@Test
	void readsTheLauncherSectionAndTheStableCheckout() {
		String json = """
			{ "repos": ["~/code/app", 3],
			  "hardcore": { "stable": "~/code/agentcraft-stable", "port": 7880 },
			  "launcher": { "enabled": false, "foremanDir": "~/x", "nodePath": "/opt/homebrew/bin/node", "stopOnExit": true },
			  "claude": { "useClaudeLogin": true } }""";
		LauncherConfig c = LauncherConfig.parse(json, NO_ENV);
		assertFalse(c.enabled());
		assertTrue(c.stopOnExit());
		assertEquals("~/x", c.foremanDir());
		assertEquals("/opt/homebrew/bin/node", c.nodePath());
		assertEquals("~/code/agentcraft-stable", c.stable());
		assertEquals(List.of("~/code/app", "3"), c.repos());
		assertTrue(c.problems().isEmpty(), c.problems().toString());
	}

	@Test
	void environmentOverrides() {
		Map<String, String> env = Map.of("AGENTCRAFT_LAUNCHER", "0", "AGENTCRAFT_LAUNCHER_STOP_ON_EXIT", "1", "AGENTCRAFT_FOREMAN_DIR", " /stable ",
			"AGENTCRAFT_NODE", "/n/node");
		LauncherConfig c = LauncherConfig.parse("{\"launcher\":{\"enabled\":true,\"nodePath\":\"/other\"}}", env::get);
		assertFalse(c.enabled());
		assertTrue(c.stopOnExit());
		assertEquals("/stable", c.foremanDirOverride());
		assertEquals("/n/node", c.nodePath());
		LauncherConfig on = LauncherConfig.parse("{\"launcher\":{\"enabled\":false}}", Map.of("AGENTCRAFT_LAUNCHER", "1")::get);
		assertTrue(on.enabled());
		LauncherConfig junk = LauncherConfig.parse(null, Map.of("AGENTCRAFT_LAUNCHER", "maybe")::get);
		assertTrue(junk.enabled(), "an unknown value keeps the setting");
	}

	@Test
	void badValuesAreNotedAndIgnored() {
		LauncherConfig c = LauncherConfig.parse("{\"launcher\":{\"enabled\":\"no\",\"foremanDir\":5,\"extra\":1}}", NO_ENV);
		assertTrue(c.enabled());
		assertNull(c.foremanDir());
		assertEquals(3, c.problems().size(), c.problems().toString());
		assertEquals(List.of("config.json is not valid JSON"), LauncherConfig.parse("{nope", NO_ENV).problems());
		assertEquals(List.of("launcher must be an object"), LauncherConfig.parse("{\"launcher\":true}", NO_ENV).problems());
	}
}
