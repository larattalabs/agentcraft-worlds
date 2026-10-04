package dev.agentcraft.routine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Stand-ups (docs/VILLAGE.md V3), pure: when a goal is active and its first tasks are assigned, its lead and those
 * workers gather for 20-30 s; the lead says the goal's title, each worker its task's title, then everyone goes to work.
 *
 * <ul>
 *   <li>Once per goal ({@code goalId:createdAt}); never for a goal that already had assigned tasks when the client
 *       connected ({@link #seed}), so a reconnect does not replay old stand-ups.</li>
 *   <li>Task upserts arrive one at a time, so the participant list is frozen {@value #DEBOUNCE_TICKS} ticks after the
 *       first assignment was seen ({@link #due}).</li>
 *   <li>The caller starts a due stand-up ({@link #start}) or skips it with a reason ({@link #skip}: far, not loaded,
 *       off, no spot); either way it is never retried.</li>
 *   <li>Timing in client ticks: gathered when everyone arrived or after {@value #GATHER_TICKS} ticks, then the lead
 *       speaks, then one worker every {@value #CUE_GAP} ticks; it ends {@value #TAIL_TICKS} ticks after the last line,
 *       never before {@value #MIN_TICKS} and never after {@value #MAX_TICKS} ticks.</li>
 * </ul>
 */
public final class StandupTracker {
	public static final int DEBOUNCE_TICKS = 60;
	public static final int GATHER_TICKS = 160;
	public static final int FIRST_CUE = 20;
	public static final int CUE_GAP = 60;
	public static final int TAIL_TICKS = 100;
	public static final int MIN_TICKS = 400;
	public static final int MAX_TICKS = 600;
	private static final int HISTORY = 8;

	/** A goal as the tracker sees it. {@code line}: what the lead says (the goal's title or its plan's first line). */
	public record GoalView(String id, long createdAt, boolean active, @Nullable String leadId, String line) {
		public String key() {
			return id + ":" + createdAt;
		}
	}

	/** A task of a goal. {@code open}: not done or cancelled. */
	public record TaskView(String id, @Nullable String goalId, @Nullable String assignee, String title, boolean open, long createdAt) {
	}

	public record Worker(String agentId, String taskId, String taskTitle) {
	}

	/** One line said during a stand-up, {@code at} ticks after it gathered. */
	public record Cue(int at, String agentId, String text, @Nullable String to) {
	}

	/** A stand-up that was skipped (or ended), for {@code dev.routines.state}. */
	public record Record(String key, String goalId, String outcome, long tick) {
	}

	/** A stand-up: due, then running once started. */
	public static final class Standup {
		public final String key;
		public final String goalId;
		public final @Nullable String lead;
		public final String line;
		public final List<Worker> workers;
		private long start = -1;
		private long gatheredAt = -1;
		private int fired;

		Standup(String key, String goalId, @Nullable String lead, String line, List<Worker> workers) {
			this.key = key;
			this.goalId = goalId;
			this.lead = lead;
			this.line = line;
			this.workers = List.copyOf(workers);
		}

		/** The lead (when there is one) then the workers. */
		public List<String> participants() {
			List<String> p = new ArrayList<>();
			if (lead != null) {
				p.add(lead);
			}
			for (Worker w : workers) {
				p.add(w.agentId());
			}
			return p;
		}

		public long start() {
			return start;
		}

		public long gatheredAt() {
			return gatheredAt;
		}

		/** Every line in order: the lead's (to all), then each worker's task title (to the lead). */
		public List<Cue> cues() {
			List<Cue> out = new ArrayList<>();
			int t = FIRST_CUE;
			if (lead != null) {
				out.add(new Cue(t, lead, line, "all"));
				t += CUE_GAP;
			}
			for (Worker w : workers) {
				out.add(new Cue(t, w.agentId(), w.taskTitle(), lead));
				t += CUE_GAP;
			}
			return out;
		}

		/** Tick (absolute) at which it ends: after the last line plus a tail, within [MIN, MAX] after the start. */
		public long end() {
			if (start < 0) {
				return -1;
			}
			if (gatheredAt < 0) {
				return start + MAX_TICKS;
			}
			List<Cue> cs = cues();
			long last = gatheredAt + (cs.isEmpty() ? 0 : cs.getLast().at());
			return Math.max(start + MIN_TICKS, Math.min(start + MAX_TICKS, last + TAIL_TICKS));
		}

		/** Advances one tick: gathers when everyone arrived (or after GATHER_TICKS) and returns the lines due now. */
		public List<Cue> tick(long now, boolean allArrived) {
			if (start < 0) {
				return List.of();
			}
			if (gatheredAt < 0 && (allArrived || now - start >= GATHER_TICKS)) {
				gatheredAt = now;
			}
			if (gatheredAt < 0) {
				return List.of();
			}
			List<Cue> cs = cues();
			List<Cue> due = new ArrayList<>();
			while (fired < cs.size() && gatheredAt + cs.get(fired).at() <= now) {
				due.add(cs.get(fired++));
			}
			return due;
		}

		public boolean running(long now) {
			return start >= 0 && now < end();
		}
	}

	private final Set<String> seen = new HashSet<>();
	private final Map<String, Long> pending = new LinkedHashMap<>();
	private final Map<String, Standup> running = new LinkedHashMap<>();
	private final Deque<Record> history = new ArrayDeque<>();

	/** A snapshot (connect / reconnect): goals that already have assigned tasks had their stand-up (or missed it). */
	public void seed(Collection<GoalView> goals, Collection<TaskView> tasks) {
		pending.clear();
		running.clear();
		for (GoalView g : goals) {
			if (hasAssigned(g, tasks)) {
				seen.add(g.key());
			}
		}
	}

	/**
	 * The stand-ups that are due now: goals that are active with assigned open tasks, first seen at least
	 * {@link #DEBOUNCE_TICKS} ago. Each is returned once (marked seen); the caller starts or skips it.
	 */
	public List<Standup> due(Collection<GoalView> goals, Collection<TaskView> tasks, long now) {
		List<Standup> out = new ArrayList<>();
		Set<String> live = new HashSet<>();
		for (GoalView g : goals) {
			String key = g.key();
			if (seen.contains(key)) {
				continue;
			}
			List<Worker> ws = workersOf(g, tasks);
			if (!g.active() || ws.isEmpty()) {
				continue;
			}
			live.add(key);
			long first = pending.computeIfAbsent(key, k -> now);
			if (now - first >= DEBOUNCE_TICKS) {
				pending.remove(key);
				seen.add(key);
				out.add(new Standup(key, g.id(), g.leadId(), clean(g.line()), ws));
			}
		}
		pending.keySet().retainAll(live);
		return out;
	}

	/** Start a due (or any) stand-up now; one per goal (a second start of the same goal replaces it). */
	public void start(Standup s, long now) {
		s.start = now;
		s.gatheredAt = -1;
		s.fired = 0;
		seen.add(s.key);
		running.put(s.key, s);
		record(s.key, s.goalId, "started", now);
	}

	public void skip(Standup s, String reason, long now) {
		seen.add(s.key);
		record(s.key, s.goalId, "skipped: " + reason, now);
	}

	/** Drops ended stand-ups (recorded as "ended"); returns the running ones. */
	public Collection<Standup> running(long now) {
		for (var it = running.values().iterator(); it.hasNext();) {
			Standup s = it.next();
			if (!s.running(now)) {
				it.remove();
				record(s.key, s.goalId, "ended", now);
			}
		}
		return running.values();
	}

	/** End every running stand-up now (toggle off, level change). */
	public void stopAll(long now, String why) {
		for (Standup s : running.values()) {
			record(s.key, s.goalId, why, now);
		}
		running.clear();
	}

	/** The running stand-up an agent takes part in, or null. */
	public @Nullable Standup of(String agentId, long now) {
		for (Standup s : running(now)) {
			if (s.participants().contains(agentId)) {
				return s;
			}
		}
		return null;
	}

	public boolean seen(String key) {
		return seen.contains(key);
	}

	public Map<String, Long> pending() {
		return Map.copyOf(pending);
	}

	public List<Record> history() {
		return List.copyOf(history);
	}

	private void record(String key, String goalId, String outcome, long tick) {
		history.addFirst(new Record(key, goalId, outcome, tick));
		while (history.size() > HISTORY) {
			history.removeLast();
		}
	}

	/** Builds the stand-up for a goal now (DevBridge: {@code dev.routines.standup} forces one), or null without workers or lead. */
	public static @Nullable Standup build(GoalView g, Collection<TaskView> tasks) {
		List<Worker> ws = workersOf(g, tasks);
		if (ws.isEmpty() && g.leadId() == null) {
			return null;
		}
		return new Standup(g.key(), g.id(), g.leadId(), clean(g.line()), ws);
	}

	/** The workers of a goal's assigned open tasks (not its lead), one per agent (its oldest task), oldest first. */
	public static List<Worker> workersOf(GoalView g, Collection<TaskView> tasks) {
		List<TaskView> mine = new ArrayList<>();
		for (TaskView t : tasks) {
			if (g.id().equals(t.goalId()) && t.open() && t.assignee() != null && !t.assignee().equals(g.leadId())) {
				mine.add(t);
			}
		}
		mine.sort(Comparator.comparingLong(TaskView::createdAt).thenComparing(TaskView::id));
		Set<String> agents = new LinkedHashSet<>();
		List<Worker> out = new ArrayList<>();
		for (TaskView t : mine) {
			if (agents.add(t.assignee())) {
				out.add(new Worker(t.assignee(), t.id(), clean(t.title())));
			}
		}
		return out;
	}

	private static boolean hasAssigned(GoalView g, Collection<TaskView> tasks) {
		for (TaskView t : tasks) {
			if (g.id().equals(t.goalId()) && t.assignee() != null) {
				return true;
			}
		}
		return false;
	}

	/** First line, whitespace collapsed, at most 90 characters (a speech bubble, not an essay). */
	public static String clean(@Nullable String s) {
		if (s == null) {
			return "";
		}
		String line = s.strip();
		int nl = line.indexOf('\n');
		if (nl >= 0) {
			line = line.substring(0, nl);
		}
		line = line.replaceAll("^#+\\s*", "").replaceAll("\\s+", " ").strip();
		return line.length() > 90 ? line.substring(0, 89).stripTrailing() + "…" : line;
	}
}
