package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConnectionHintsTest {
	@Test
	void launcherJarPointsAtTheDaemon() {
		assertTrue(ConnectionHints.notRunning(false).contains("starts with the game"));
		assertTrue(ConnectionHints.notRunning(false).contains("tools/foreman-daemon.sh"));
		assertTrue(ConnectionHints.authFailed(false).contains("/login"));
		assertTrue(ConnectionHints.authFailed(false).contains("foreman-daemon.sh restart"));
	}

	@Test
	void devRunPointsAtTheLauncher() {
		assertTrue(ConnectionHints.notRunning(true).contains("tools/mac.mjs launch"));
		assertTrue(ConnectionHints.authFailed(true).contains("restart the Foreman"));
	}

	@Test
	void hintsStayShort() {
		for (boolean dev : new boolean[] {true, false}) {
			assertTrue(ConnectionHints.notRunning(dev).length() <= 60, ConnectionHints.notRunning(dev));
			assertTrue(ConnectionHints.authFailed(dev).length() <= 70, ConnectionHints.authFailed(dev));
		}
	}
}
