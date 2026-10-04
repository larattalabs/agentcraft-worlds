package dev.agentcraft.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Joins an agent log's fetched pages (agent.logs.request, oldest first) with the live tail the snapshot and
 * {@code agent.log} keep (oldest first, capped), for the Inbox's agent view (pure; docs/WAVE2.md W3). Generic over the
 * entry type ({@code ts} by {@code tsOf}) so it is testable without the client's protocol classes.
 */
public final class LogJoin {
	/** The fetched entries an agent view keeps (older pages stop there). */
	public static final int MAX_FETCHED = 2000;

	private LogJoin() {
	}

	/**
	 * Everything to show, oldest first: the fetched entries, then the live entries newer than the last fetched one
	 * (those sharing its ts are added when the fetched page's end does not already hold them).
	 */
	public static <T> List<T> join(List<T> fetched, List<T> live, ToLongFunction<T> tsOf) {
		if (fetched.isEmpty()) {
			return List.copyOf(live);
		}
		List<T> out = new ArrayList<>(fetched.size() + live.size());
		out.addAll(fetched);
		long last = tsOf.applyAsLong(fetched.get(fetched.size() - 1));
		List<T> lastGroup = null;
		for (T e : live) {
			long ts = tsOf.applyAsLong(e);
			if (ts > last) {
				out.add(e);
			} else if (ts == last) {
				if (lastGroup == null) {
					lastGroup = new ArrayList<>();
					for (int i = fetched.size() - 1; i >= 0 && tsOf.applyAsLong(fetched.get(i)) == last; i--) {
						lastGroup.add(fetched.get(i));
					}
				}
				if (!lastGroup.contains(e)) {
					out.add(e);
				}
			}
		}
		return out;
	}

	/**
	 * True when the live tail starts after the last fetched entry: entries between them may be missing (more arrived
	 * than the tail keeps since the newest page was fetched). The view asks for the page before the tail's first entry
	 * ({@link #gapBefore}) to fill it.
	 */
	public static <T> boolean gap(List<T> fetched, List<T> live, ToLongFunction<T> tsOf) {
		return !fetched.isEmpty() && !live.isEmpty() && tsOf.applyAsLong(live.get(0)) > tsOf.applyAsLong(fetched.get(fetched.size() - 1));
	}

	/** The {@code before} for the page that fills a {@link #gap}: it takes the tail's first entry and everything older. */
	public static <T> long gapBefore(List<T> live, ToLongFunction<T> tsOf) {
		return tsOf.applyAsLong(live.get(0)) + 1;
	}

	/**
	 * True when {@code page} (oldest first; the gap pages collected so far) reaches back to the last fetched entry, so
	 * {@link #fill} closes the gap without leaving a hole; an empty page reaches (there is nothing in between).
	 */
	public static <T> boolean reaches(List<T> fetched, List<T> page, ToLongFunction<T> tsOf) {
		return fetched.isEmpty() || page.isEmpty() || tsOf.applyAsLong(page.get(0)) <= tsOf.applyAsLong(fetched.get(fetched.size() - 1));
	}

	/**
	 * Adds the gap pages (oldest first, {@link #reaches reaching} back to the last fetched entry) after the fetched
	 * entries: the ones newer than it (and those sharing its ts that the fetched end lacks). Returns how many were added,
	 * or -1 (nothing changed) when the pages do not reach back: asking for the page before them comes first.
	 */
	public static <T> int fill(List<T> fetched, List<T> page, ToLongFunction<T> tsOf) {
		if (!reaches(fetched, page, tsOf)) {
			return -1;
		}
		if (fetched.isEmpty()) {
			fetched.addAll(page);
			return page.size();
		}
		long last = tsOf.applyAsLong(fetched.get(fetched.size() - 1));
		List<T> lastGroup = new ArrayList<>();
		for (int i = fetched.size() - 1; i >= 0 && tsOf.applyAsLong(fetched.get(i)) == last; i--) {
			lastGroup.add(fetched.get(i));
		}
		int n = 0;
		for (T e : page) {
			long ts = tsOf.applyAsLong(e);
			if (ts > last || ts == last && !lastGroup.contains(e)) {
				fetched.add(e);
				n++;
			}
		}
		return n;
	}

	/**
	 * Gap fills (a busy agent while the view is open) append at the newest end: past {@link #MAX_FETCHED} the oldest
	 * entries go (the viewer is at the bottom then; scrolling up loads them again). Returns how many were dropped.
	 */
	public static <T> int trimOldest(List<T> fetched) {
		int drop = fetched.size() - MAX_FETCHED;
		if (drop <= 0) {
			return 0;
		}
		fetched.subList(0, drop).clear();
		return drop;
	}

	/**
	 * How many fetched entries to keep: the ones the live tail also holds (newer than its first entry) are dropped when
	 * there is no gap, since {@link #join} shows them from the tail. Returns the new size.
	 */
	public static <T> int keepBeforeLive(List<T> fetched, List<T> live, ToLongFunction<T> tsOf) {
		if (fetched.isEmpty() || live.isEmpty() || gap(fetched, live, tsOf)) {
			return fetched.size();
		}
		long first = tsOf.applyAsLong(live.get(0));
		int n = fetched.size();
		while (n > 0 && tsOf.applyAsLong(fetched.get(n - 1)) > first) {
			n--;
		}
		return n;
	}
}
