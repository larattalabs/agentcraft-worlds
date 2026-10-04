package dev.agentcraft.client.hub;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hud.Alerts;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Counts on the hub's tabs (docs/WAVE2.md W5), keyed by tab id so a tab added later (the Inbox) picks its badge
 * up by name: Inbox = what needs the player ({@link Alerts}, the alert line's counts), Goals = goals with
 * activity not looked at, Repos = repos whose CI fails, Team = agents blocked or in error. Cached per Foreman
 * revision for at most a second (read marks change without one). Client thread.
 */
final class TabBadges {
	/** A badge: the count and the status-dot family it is drawn with. */
	record Badge(int count, String family) {
	}

	private static final long MAX_AGE_MS = 1000;
	private static final Map<String, Badge> CACHE = new HashMap<>();
	private static long cachedRev = Long.MIN_VALUE;
	private static long cachedAt;

	private TabBadges() {
	}

	/** The badge of a tab, or null (nothing to count). */
	static @Nullable Badge of(HubTab t) {
		ForemanState s = Foreman.state();
		long rev = (s == null ? -1 : s.revision()) * 31 + Inbox.revision();
		long now = System.currentTimeMillis();
		if (rev != cachedRev || now - cachedAt > MAX_AGE_MS) {
			CACHE.clear();
			cachedRev = rev;
			cachedAt = now;
			if (s != null && s.hasData()) {
				compute(s);
			}
		}
		return CACHE.get(t.id);
	}

	private static void compute(ForemanState s) {
		put("inbox", Alerts.line().needsYou(), "waiting");
		int unread = 0;
		for (Protocol.Goal g : HubGoals.goals(null)) {
			if (HubGoals.unread(g)) {
				unread++;
			}
		}
		put("goals", unread, "thinking");
		int failing = 0;
		for (Protocol.Repo r : s.repos().values()) {
			if (r.ci() == Protocol.CiStatus.FAIL) {
				failing++;
			}
		}
		put("repos", failing, "error");
		int blocked = 0;
		for (Protocol.Agent a : s.agents().values()) {
			if (a.state() == Protocol.AgentState.BLOCKED || a.state() == Protocol.AgentState.ERROR) {
				blocked++;
			}
		}
		put("team", blocked, "error");
	}

	private static void put(String tab, int n, String family) {
		if (n > 0) {
			CACHE.put(tab, new Badge(n, family));
		}
	}
}
