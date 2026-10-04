package dev.agentcraft.client.hud;

import dev.agentcraft.client.foreman.Protocol;
import org.jspecify.annotations.Nullable;

/**
 * What needs the player, for the HUD alert line, the away toast and the hub's tab badges (docs/WAVE2.md W5:
 * "same counts as Inbox Needs you"). The wave 2 inbox stream's {@code InboxModel} is the real source; until it is
 * merged, {@link ForemanAlertCounts} computes the same counts from the Foreman state model. Switch with
 * {@link Alerts#setSource}.
 */
public interface AlertCounts {
	/** Open decisions not being answered right now. */
	int decisions();

	/** Blocked tasks. */
	int blocked();

	/** Agent replies to the user not seen yet. */
	int replies();

	/** PRs needing attention (new review threads, failing checks); the Inbox's count. */
	default int prs() {
		return 0;
	}

	/** The backend's hold (contract C9), or null. */
	Protocol.@Nullable ForemanHold hold();
}
