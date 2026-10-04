package dev.agentcraft.client.ui;

import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.ui.UiRules;
import org.jspecify.annotations.Nullable;

/**
 * Contract C6 (docs/FIXWAVE.md): every AgentCraft screen pauses a singleplayer game, like vanilla menus,
 * so a survival / Hardcore world never runs on behind a screen. Dev runs ({@code gradlew runClient}) and
 * clients with the DevBridge on keep the world running (QA scenes, live agents behind the card).
 * {@code AGENTCRAFT_PAUSE=0|1} overrides both; {@code dev.ui.pause {on}} changes it at runtime (QA).
 * Every screen's {@code isPauseScreen()} returns {@link #pauses()}; vanilla only honours it in
 * singleplayer that is not opened to LAN.
 */
public final class ScreenPause {
	private static volatile boolean pauses = UiRules.screensPause(ClientEnv.DEV_RUN, ClientEnv.DEV_BRIDGE, override());

	private ScreenPause() {
	}

	public static boolean pauses() {
		return pauses;
	}

	/** QA: force pausing on/off for this session (null = back to the environment's default). */
	public static void set(@Nullable Boolean on) {
		pauses = on != null ? on : UiRules.screensPause(ClientEnv.DEV_RUN, ClientEnv.DEV_BRIDGE, override());
	}

	private static @Nullable Boolean override() {
		return ClientEnv.raw("AGENTCRAFT_PAUSE") == null ? null : ClientEnv.flag("AGENTCRAFT_PAUSE", true);
	}
}
