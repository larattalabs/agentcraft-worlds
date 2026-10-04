package dev.agentcraft.routine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Library visits (docs/VILLAGE.md V3), pure: an agent that wrote a memory note walks to its building's library with a
 * book, reads there for {@value #READ_TICKS} ticks (~5 s) and goes back.
 * <ul>
 *   <li>{@link #note}: a memory note by the agent. It waits until the agent is between steps, at most
 *       {@value #WAIT_TICKS} ticks (2 min; then it is dropped). Notes during a visit, or within
 *       {@value #COOLDOWN_TICKS} ticks after one, add nothing (an agent writing ten notes walks once).</li>
 *   <li>{@link #start} when the routine picks it; {@link #arrived} at the shelves; it ends after reading, or
 *       {@value #MAX_TICKS} ticks (30 s) after the start whatever happened; {@link #cancel} when work (or the player)
 *       needs the agent: a visit never holds a working agent.</li>
 * </ul>
 * Ticks are the client's unpaused ticks.
 */
public final class LibraryVisits {
	public static final int WAIT_TICKS = 2_400;
	public static final int READ_TICKS = 100;
	public static final int MAX_TICKS = 600;
	public static final int COOLDOWN_TICKS = 1_200;
	private static final int HISTORY = 8;

	public enum Phase {
		WAITING, WALKING, READING
	}

	/** One agent's visit. {@code since}: queued (WAITING) or started (WALKING/READING); {@code readUntil} while reading. */
	public record Visit(String agentId, Phase phase, long since, long readUntil) {
		public boolean holdsBook() {
			return phase != Phase.WAITING;
		}
	}

	public record Record(String agentId, String outcome, long tick) {
	}

	private final Map<String, Visit> visits = new LinkedHashMap<>();
	private final Map<String, Long> lastEnd = new LinkedHashMap<>();
	private final Deque<Record> history = new ArrayDeque<>();

	/** A memory note by {@code agentId}; false when it adds nothing (visiting, cooling down). */
	public boolean note(String agentId, long now) {
		Visit v = visits.get(agentId);
		if (v != null && v.phase() != Phase.WAITING) {
			return false;
		}
		Long end = lastEnd.get(agentId);
		if (end != null && now - end < COOLDOWN_TICKS) {
			return false;
		}
		visits.put(agentId, new Visit(agentId, Phase.WAITING, now, 0));
		return true;
	}

	/** Queue a visit now regardless of the cooldown (DevBridge {@code dev.routines.library}). */
	public void force(String agentId, long now) {
		lastEnd.remove(agentId);
		visits.put(agentId, new Visit(agentId, Phase.WAITING, now, 0));
	}

	public @Nullable Visit of(String agentId) {
		return visits.get(agentId);
	}

	/** A visit is waiting for or running for this agent. */
	public boolean due(String agentId) {
		return visits.containsKey(agentId);
	}

	public void start(String agentId, long now) {
		Visit v = visits.get(agentId);
		if (v != null && v.phase() == Phase.WAITING) {
			visits.put(agentId, new Visit(agentId, Phase.WALKING, now, 0));
			record(agentId, "started", now);
		}
	}

	/** At the shelves: read for READ_TICKS. */
	public void arrived(String agentId, long now) {
		Visit v = visits.get(agentId);
		if (v != null && v.phase() == Phase.WALKING) {
			visits.put(agentId, new Visit(agentId, Phase.READING, v.since(), Math.min(now + READ_TICKS, v.since() + MAX_TICKS)));
		}
	}

	public void cancel(String agentId, String why, long now) {
		Visit v = visits.remove(agentId);
		if (v != null) {
			if (v.phase() != Phase.WAITING) {
				lastEnd.put(agentId, now);
			}
			record(agentId, "cancelled: " + why, now);
		}
	}

	/**
	 * Advances to {@code now}: drops notes that waited too long, ends visits whose reading is done or that hit the cap.
	 * {@code known}: agents that still exist (others are dropped).
	 */
	public void tick(long now, Predicate<String> known) {
		for (var it = visits.values().iterator(); it.hasNext();) {
			Visit v = it.next();
			String outcome = null;
			if (!known.test(v.agentId())) {
				outcome = "gone";
			} else if (v.phase() == Phase.WAITING && now - v.since() > WAIT_TICKS) {
				outcome = "expired (never between steps)";
			} else if (v.phase() == Phase.READING && now >= v.readUntil()) {
				outcome = "done";
			} else if (v.phase() != Phase.WAITING && now - v.since() >= MAX_TICKS) {
				outcome = "timeout";
			}
			if (outcome != null) {
				it.remove();
				if (v.phase() != Phase.WAITING) {
					lastEnd.put(v.agentId(), now);
				}
				record(v.agentId(), outcome, now);
			}
		}
	}

	public Map<String, Visit> visits() {
		return Map.copyOf(visits);
	}

	public List<Record> history() {
		return List.copyOf(history);
	}

	public void clear() {
		visits.clear();
		lastEnd.clear();
	}

	private void record(String agentId, String outcome, long tick) {
		history.addFirst(new Record(agentId, outcome, tick));
		while (history.size() > HISTORY) {
			history.removeLast();
		}
	}
}
