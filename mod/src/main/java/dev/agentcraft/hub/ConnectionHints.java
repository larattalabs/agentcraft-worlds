package dev.agentcraft.hub;

/**
 * Recovery hints the HUD shows when the Foreman link is down or cannot authenticate. They depend on how the game was
 * started (a dev run, {@code gradlew runClient} started by tools/unix.mjs, versus a built jar in a normal launcher) and
 * on what the mod's Foreman launcher is doing (docs/HUB.md "Foreman launcher"): the game starts the Foreman itself, so
 * the pill says so while it starts, and points at the hub's Status tab when it could not. Pure, so it is unit-tested;
 * keep the lines short (they are drawn in a small pill).
 */
public final class ConnectionHints {
	private ConnectionHints() {
	}

	/** First line of the pill while the link has never connected, by launcher state ({@code LauncherPlan.State.wire()}). */
	public static String title(String launcherState) {
		return switch (launcherState) {
			case "installing", "starting" -> "Starting the Foreman…";
			case "running", "running-older" -> "Connecting to the Foreman…";
			case "crashed" -> "The Foreman stopped";
			case "node-missing" -> "Foreman needs Node.js";
			case "blocked" -> "Foreman could not start";
			default -> "Foreman not running";
		};
	}

	/** Second line, by launcher state; {@code hubKey} is the hub key's label. */
	public static String detail(String launcherState, boolean devRun, String hubKey) {
		return switch (launcherState) {
			case "installing" -> "installing its packages (first run)";
			case "starting", "running", "running-older" -> "the game starts it; connecting…";
			case "crashed", "blocked", "no-source" -> "see the hub's Status tab (" + hubKey + ")";
			case "node-missing" -> "install Node.js 22+; Status tab (" + hubKey + ")";
			default -> notRunning(devRun);
		};
	}

	/** Second line under "Foreman not running" when the launcher is off or idle. */
	public static String notRunning(boolean devRun) {
		return devRun ? "start it: node tools/unix.mjs launch" : "Start it in the hub's Status tab";
	}

	/**
	 * The auth banner's text when the Foreman sent no message of its own. {@code startedByGame}: the mod's launcher
	 * started it, so the Status tab's Restart applies; else whoever started it restarts it.
	 */
	public static String authFailed(boolean devRun, boolean startedByGame) {
		if (startedByGame) {
			return "run `claude` and /login, then Restart in the hub's Status tab";
		}
		return devRun ? "run `claude` and /login, then restart the Foreman"
			: "run `claude` and /login, then tools/foreman-daemon.sh restart";
	}
}
