package dev.agentcraft.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pure rules of the wave 2 HUD (docs/WAVE2.md W5-W7), unit-tested in {@code HudRulesTest}: when the away
 * toast is due and what it says, which goal the goal bar shows when several are open, and when the welcome
 * card appears.
 */
public final class HudRules {
	/** Away this long without the hub on screen, the away toast is due (W6). */
	public static final long AWAY_MS = 10 * 60_000L;
	/** A check that found nothing is repeated after this long (the stretch goes on). */
	public static final long RECHECK_MS = 2 * 60_000L;
	/** The goal bar shows each open goal this long when none is urgent. */
	public static final long CYCLE_MS = 8_000L;

	private HudRules() {
	}

	// ------------------------------------------------------------------ away (W6)

	/**
	 * Where an away stretch starts: the later of the hub's last time on screen and the last away toast (a toast
	 * already told the player about what came before it). 0 = unknown (the hub was never opened here).
	 */
	public static long awaySince(long hubSeenAt, long lastToastAt) {
		return hubSeenAt <= 0 ? 0 : Math.max(hubSeenAt, lastToastAt);
	}

	/**
	 * Whether to ask {@code goal.digest {since}} for the away toast now: the Foreman is connected, the hub is not
	 * open, the stretch start is known, and either the world was just joined or the stretch is at least
	 * {@link #AWAY_MS} long; never more often than every {@link #RECHECK_MS}.
	 */
	public static boolean awayDue(long now, long since, boolean joinPending, long lastCheckAt, boolean hubOpen, boolean connected) {
		if (hubOpen || !connected || since <= 0) {
			return false;
		}
		if (joinPending) {
			return true;
		}
		return now - since >= AWAY_MS && now - lastCheckAt >= RECHECK_MS;
	}

	/**
	 * The away toast: "Since you were away: 2 goals moved, 1 needs you". Null (no toast) when no goal moved:
	 * the alert line already shows what needs the player, a toast is for news.
	 */
	public static @Nullable String awayText(int goalsMoved, int needYou) {
		if (goalsMoved <= 0) {
			return null;
		}
		StringBuilder b = new StringBuilder("Since you were away: ");
		b.append(goalsMoved).append(goalsMoved == 1 ? " goal moved" : " goals moved");
		if (needYou > 0) {
			b.append(", ").append(needYou).append(needYou == 1 ? " needs you" : " need you");
		}
		return b.toString();
	}

	// ------------------------------------------------------------------ goal bar (several open goals)

	/** A goal for {@link #pickGoal}: open = planning/active; urgency = its open decisions + blocked tasks. */
	public record GoalCand(String id, boolean open, int urgency, long updatedAt, long createdAt) {
	}

	/** What the goal bar shows: the goal, its place in the cycle (0-based) and how many more are open. */
	public record Pick(String id, int index, int open, int more, boolean pinned) {
	}

	/**
	 * The open goals in bar order: urgent ones first (most urgent, then most recently updated), then the rest
	 * by most recent update (newest created breaks ties).
	 */
	public static List<GoalCand> order(List<GoalCand> all) {
		List<GoalCand> open = new ArrayList<>();
		for (GoalCand g : all) {
			if (g.open()) {
				open.add(g);
			}
		}
		open.sort(Comparator.comparingInt((GoalCand g) -> -g.urgency()).thenComparingLong(g -> -g.updatedAt()).thenComparingLong(g -> -g.createdAt())
			.thenComparing(GoalCand::id));
		return open;
	}

	/**
	 * The goal the bar shows at {@code now}. With an urgent goal it is pinned (the most urgent); otherwise the
	 * open goals take turns every {@link #CYCLE_MS}. No open goal: {@code fallback} (the latest goal, e.g. one
	 * that just finished), or null.
	 */
	public static @Nullable Pick pickGoal(List<GoalCand> all, long now, @Nullable String fallback) {
		List<GoalCand> open = order(all);
		if (open.isEmpty()) {
			return fallback == null ? null : new Pick(fallback, 0, 0, 0, false);
		}
		if (open.get(0).urgency() > 0 || open.size() == 1) {
			return new Pick(open.get(0).id(), 0, open.size(), open.size() - 1, open.get(0).urgency() > 0);
		}
		int i = (int) Math.floorMod(now / CYCLE_MS, (long) open.size());
		return new Pick(open.get(i).id(), i, open.size(), open.size() - 1, false);
	}

	// ------------------------------------------------------------------ onboarding (W7)

	/**
	 * Whether the welcome card opens on joining: singleplayer (the buildings are the integrated server's; a
	 * dedicated server's client never knows them), not the dev HQ, the buildings file read fine, no building
	 * yet, not dismissed in this world, not already shown this session, and nothing else on screen.
	 */
	public static boolean welcomeDue(boolean singleplayer, boolean hq, boolean loadFailed, int buildings, boolean dismissed, boolean shownThisSession,
		boolean screenOpen) {
		return singleplayer && !hq && !loadFailed && buildings == 0 && !dismissed && !shownThisSession && !screenOpen;
	}
}
