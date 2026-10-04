package dev.agentcraft.client.hud;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.hub.HubGoals;
import dev.agentcraft.hud.HudPrefs;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The client's one {@link HudPrefs} ({@code <gameDir>/agentcraft/hub-hud.json}), keyed by the same world name as
 * {@code hub-seen.json} ({@link HubGoals#world()}). Client thread.
 */
public final class HudMemory {
	private static @Nullable HudPrefs prefs;
	private static long lastSave;

	private HudMemory() {
	}

	public static String world() {
		return HubGoals.world();
	}

	public static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("agentcraft").resolve(HudPrefs.FILE);
	}

	public static HudPrefs prefs() {
		if (prefs == null) {
			prefs = HudPrefs.load(file());
		}
		return prefs;
	}

	/**
	 * The replies read mark of {@code world}; a world seen for the first time starts at now (the feed is the
	 * Foreman's, not the world's: its history is not news here).
	 */
	public static long repliesBaseline(String world) {
		HudPrefs p = prefs();
		if (!p.known(world)) {
			p.markRepliesSeen(world, System.currentTimeMillis());
		}
		return p.repliesSeen(world);
	}

	/** Saves when changed (throttled to every 2 s unless {@code now}). */
	public static void flush(boolean now) {
		HudPrefs p = prefs;
		if (p == null || !p.dirty() || !now && System.currentTimeMillis() - lastSave < 2000) {
			return;
		}
		lastSave = System.currentTimeMillis();
		try {
			p.save(file());
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", file(), e);
		}
	}
}
