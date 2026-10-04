package dev.agentcraft.building;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Something a repo's building gets a plaque for (docs/BUILDINGS.md "Trophies"): a merged PR, a task merged locally, or
 * a finished goal. Pure data; {@link TrophyText} turns it into sign lines, {@link Trophies#award} hangs it.
 *
 * @param repo the repo whose building gets it
 * @param title the PR or task title, or the goal text (only its first line is used)
 * @param prId the PR number ({@code "612"} or {@code "#612"}) for {@link Kind#PR}
 * @param taskId the task id ({@code t12}) for {@link Kind#MERGE}
 * @param taskCount the goal's non-cancelled task count for {@link Kind#GOAL}; null = not shown
 * @param date the day it happened (the player's local date)
 */
public record Trophy(Kind kind, String repo, @Nullable String title, @Nullable String prId, @Nullable String taskId, @Nullable Integer taskCount,
	LocalDate date) {
	public enum Kind {
		/** A task's PR merged. */
		PR,
		/** A task merged locally (done without a PR). */
		MERGE,
		/** A goal turned done. */
		GOAL
	}

	public static Trophy pr(String repo, String prId, @Nullable String title, LocalDate date) {
		return new Trophy(Kind.PR, repo, title, prId, null, null, date);
	}

	public static Trophy merge(String repo, String taskId, @Nullable String title, LocalDate date) {
		return new Trophy(Kind.MERGE, repo, title, null, taskId, null, date);
	}

	public static Trophy goal(String repo, @Nullable String text, @Nullable Integer taskCount, LocalDate date) {
		return new Trophy(Kind.GOAL, repo, text, null, null, taskCount, date);
	}

	// ------------------------------------------------------------------ ledger keys (one trophy per key, ever)

	/** {@code goal:<goalId>:<createdAt>}: a goal id reused by a new Foreman database is a new goal. */
	public static String goalKey(String goalId, long createdAt) {
		return "goal:" + goalId + ":" + createdAt;
	}

	/** {@code pr:<repo>:<prId>}: a PR number is unique within its repo. */
	public static String prKey(String repo, String prId) {
		return "pr:" + repo + ":" + stripHash(prId);
	}

	/** {@code merge:<taskId>:<createdAt>}: a task merged without a PR. */
	public static String mergeKey(String taskId, long createdAt) {
		return "merge:" + taskId + ":" + createdAt;
	}

	static String stripHash(@Nullable String prId) {
		String s = prId == null ? "" : prId.strip();
		return s.startsWith("#") ? s.substring(1).strip() : s;
	}
}
