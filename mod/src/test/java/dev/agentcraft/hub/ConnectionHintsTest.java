package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConnectionHintsTest {
	@Test
	void launcherJarPointsAtTheStatusTab() {
		assertTrue(ConnectionHints.notRunning(false).contains("Status tab"));
		assertTrue(ConnectionHints.authFailed(false, false).contains("/login"));
		assertTrue(ConnectionHints.authFailed(false, false).contains("foreman-daemon.sh restart"));
		assertTrue(ConnectionHints.authFailed(false, true).contains("Restart in the hub's Status tab"));
	}

	@Test
	void devRunPointsAtTheLauncher() {
		assertTrue(ConnectionHints.notRunning(true).contains("tools/mac.mjs launch"));
		assertTrue(ConnectionHints.authFailed(true, false).contains("restart the Foreman"));
	}

	@Test
	void thePillFollowsTheLauncher() {
		assertEquals("Starting the Foreman…", ConnectionHints.title("starting"));
		assertEquals("Starting the Foreman…", ConnectionHints.title("installing"));
		assertEquals("The Foreman stopped", ConnectionHints.title("crashed"));
		assertEquals("Foreman not running", ConnectionHints.title("disabled"));
		assertEquals("Connecting to the Foreman…", ConnectionHints.title("running"), "a reused one before the link is up");
		assertEquals("Connecting to the Foreman…", ConnectionHints.title("running-older"));
		assertTrue(ConnectionHints.detail("crashed", false, "H").contains("Status tab (H)"));
		assertTrue(ConnectionHints.detail("node-missing", false, "H").contains("Node.js 22+"));
		assertEquals(ConnectionHints.notRunning(true), ConnectionHints.detail("disabled", true, "H"));
	}

	@Test
	void hintsStayShort() {
		for (boolean dev : new boolean[] {true, false}) {
			assertTrue(ConnectionHints.notRunning(dev).length() <= 60, ConnectionHints.notRunning(dev));
			for (boolean ours : new boolean[] {true, false}) {
				assertTrue(ConnectionHints.authFailed(dev, ours).length() <= 70, ConnectionHints.authFailed(dev, ours));
			}
			for (String s : new String[] {"idle", "disabled", "node-missing", "no-source", "installing", "starting", "running", "running-older",
				"crashed", "blocked", "stopped"}) {
				assertTrue(ConnectionHints.detail(s, dev, "Ctrl+H").length() <= 60, s);
				assertTrue(ConnectionHints.title(s).length() <= 30, s);
			}
		}
	}
}
