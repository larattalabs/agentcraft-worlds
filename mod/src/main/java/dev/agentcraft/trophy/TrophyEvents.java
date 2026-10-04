package dev.agentcraft.trophy;

import dev.agentcraft.building.Trophy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Which Foreman updates earn a trophy (docs/BUILDINGS.md "Trophies"). Pure: the client maps its protocol records to
 * {@link GoalIn} / {@link TaskIn} and {@link dev.agentcraft.building.Trophies#award} hangs what comes out.
 * <ul>
 * <li>a goal that turns {@code done}: {@code goal:<id>:<createdAt>}</li>
 * <li>a task whose PR turns {@code merged}: {@code pr:<repo>:<prId>}</li>
 * <li>a task without a PR that turns {@code done} (merged locally): {@code merge:<id>:<createdAt>}</li>
 * </ul>
 * A live update awards the transition (previous not yet, now yes); {@link #catchUp} awards everything that already
 * qualifies, oldest first, so a full slot wall ends up with the newest trophies. Keys make it all idempotent.
 */
public final class TrophyEvents {
	private TrophyEvents() {
	}

	/** @param taskCount the goal's non-cancelled tasks */
	public record GoalIn(String id, @Nullable String text, boolean done, @Nullable String repoId, long createdAt, long updatedAt, int taskCount) {
	}

	/**
	 * @param prId the PR number as text, null = the task has no PR
	 * @param prAt when the PR last changed (the merge date)
	 */
	public record TaskIn(String id, @Nullable String title, boolean done, @Nullable String repoId, @Nullable String goalId, @Nullable String prId,
		boolean prMerged, long prAt, long createdAt, long updatedAt) {
	}

	/** A trophy to hang, with the time it earned (for ordering). */
	public record Award(String key, Trophy trophy, long at) {
	}

	/** The award for a goal update, if it just turned done ({@code previous} null = new). */
	public static Optional<Award> goal(@Nullable GoalIn previous, GoalIn now, ZoneId zone) {
		if (!now.done() || (previous != null && previous.done())) {
			return Optional.empty();
		}
		return forGoal(now, zone);
	}

	/**
	 * The award for a task update: its PR just merged, or (no PR) it just turned done.
	 *
	 * @param goalRepos goal id to the goal's repo, for tasks that have none of their own
	 */
	public static Optional<Award> task(@Nullable TaskIn previous, TaskIn now, Map<String, String> goalRepos, ZoneId zone) {
		if (now.prId() != null) {
			if (!now.prMerged() || (previous != null && previous.prId() != null && previous.prMerged())) {
				return Optional.empty();
			}
		} else if (!now.done() || (previous != null && previous.prId() == null && previous.done())) {
			return Optional.empty();
		}
		return forTask(now, goalRepos, zone);
	}

	/** Everything in the full state that qualifies, oldest first (ties: by key). */
	public static List<Award> catchUp(List<GoalIn> goals, List<TaskIn> tasks, Map<String, String> goalRepos, ZoneId zone) {
		List<Award> out = new ArrayList<>();
		for (GoalIn g : goals) {
			if (g.done()) {
				forGoal(g, zone).ifPresent(out::add);
			}
		}
		for (TaskIn t : tasks) {
			boolean earned = t.prId() != null ? t.prMerged() : t.done();
			if (earned) {
				forTask(t, goalRepos, zone).ifPresent(out::add);
			}
		}
		out.sort(Comparator.comparingLong(Award::at).thenComparing(Award::key));
		return out;
	}

	/**
	 * Keeps each repo's newest {@code limit(repo)} awards (the rest could only be hung to be replaced at once), in the
	 * given order. A catch-up in a world with a long history hangs a wall's worth, not all of it.
	 */
	public static List<Award> newestPerRepo(List<Award> oldestFirst, java.util.function.ToIntFunction<String> limit) {
		Map<String, Integer> seen = new java.util.HashMap<>();
		List<Award> out = new ArrayList<>();
		for (int i = oldestFirst.size() - 1; i >= 0; i--) {
			Award a = oldestFirst.get(i);
			String repo = a.trophy().repo();
			if (seen.merge(repo, 1, Integer::sum) <= limit.applyAsInt(repo)) {
				out.add(a);
			}
		}
		java.util.Collections.reverse(out);
		return out;
	}

	private static Optional<Award> forGoal(GoalIn g, ZoneId zone) {
		if (g.repoId() == null || g.repoId().isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new Award(Trophy.goalKey(g.id(), g.createdAt()),
			Trophy.goal(g.repoId(), g.text(), g.taskCount(), Instant.ofEpochMilli(g.updatedAt()).atZone(zone).toLocalDate()), g.updatedAt()));
	}

	private static Optional<Award> forTask(TaskIn t, Map<String, String> goalRepos, ZoneId zone) {
		String repo = t.repoId() != null && !t.repoId().isBlank() ? t.repoId() : t.goalId() == null ? null : goalRepos.get(t.goalId());
		if (repo == null || repo.isBlank()) {
			return Optional.empty();
		}
		if (t.prId() != null) {
			long at = t.prAt() > 0 ? t.prAt() : t.updatedAt();
			return Optional.of(new Award(Trophy.prKey(repo, t.prId()),
				Trophy.pr(repo, t.prId(), t.title(), Instant.ofEpochMilli(at).atZone(zone).toLocalDate()), at));
		}
		return Optional.of(new Award(Trophy.mergeKey(t.id(), t.createdAt()),
			Trophy.merge(repo, t.id(), t.title(), Instant.ofEpochMilli(t.updatedAt()).atZone(zone).toLocalDate()), t.updatedAt()));
	}
}
