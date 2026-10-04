package dev.agentcraft.hub;

/**
 * Recovery hints the HUD shows when the Foreman link is down or cannot authenticate. They depend on
 * how the game was started: a dev run ({@code gradlew runClient}, started by tools/mac.mjs) versus a
 * built jar in a normal launcher, where tools/hardcore-setup.mjs makes the launcher start the Foreman
 * with the game (tools/foreman-daemon.sh). Pure, so it is unit-tested; keep the lines short (they are
 * drawn in a small pill).
 */
public final class ConnectionHints {
	private ConnectionHints() {
	}

	/** Second line under "Foreman not running". */
	public static String notRunning(boolean devRun) {
		return devRun ? "start it: node tools/mac.mjs launch" : "it starts with the game; or run tools/foreman-daemon.sh";
	}

	/** The auth banner's text when the Foreman sent no message of its own. */
	public static String authFailed(boolean devRun) {
		return devRun ? "run `claude` and /login, then restart the Foreman"
			: "run `claude` and /login, then tools/foreman-daemon.sh restart";
	}
}
