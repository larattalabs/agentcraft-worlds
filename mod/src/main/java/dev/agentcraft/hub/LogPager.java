package dev.agentcraft.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * The paging state of an agent's full log in the Inbox (docs/WAVE2.md W3), pure: the fetched entries (oldest first),
 * whether the Foreman stores older ones ({@code more} of its last page), the entries the last older page put in front,
 * the {@link LogJoin#MAX_FETCHED} cap. The client's {@code AgentLogView} asks the Foreman ({@code agent.logs.request})
 * and hands the pages here; unit-tested in {@code LogPagerTest} (the {@code more} path).
 */
public final class LogPager<E> {
	private final ToLongFunction<E> tsOf;
	private final List<E> fetched = new ArrayList<>();
	private boolean more;
	private boolean full;
	private int pages;
	private int prepended;
	private int version;

	public LogPager(ToLongFunction<E> tsOf) {
		this.tsOf = tsOf;
	}

	/** One {@code agent.logs.request} page: its entries (oldest first) and whether older ones are stored. */
	public record Page<E>(List<E> entries, boolean more) {
		public Page {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}
	}

	/** The fetched entries, oldest first (the gap fill edits them in place; call {@link #changed} after). */
	public List<E> fetched() {
		return fetched;
	}

	public boolean more() {
		return more;
	}

	public void more(boolean m) {
		more = m;
	}

	/** The view keeps {@link LogJoin#MAX_FETCHED} lines and an older page was asked for at the cap. */
	public boolean full() {
		return full;
	}

	public void full(boolean f) {
		full = f;
	}

	public int pages() {
		return pages;
	}

	public int version() {
		return version;
	}

	/** The fetched entries changed (bumps {@link #version}). */
	public void changed() {
		version++;
	}

	/** The newest page ({@code before} null): replaces what was fetched. */
	public void firstPage(Page<E> page) {
		pages++;
		version++;
		fetched.clear();
		fetched.addAll(page.entries());
		prepended = 0;
		more = page.more();
	}

	/** An older page: goes in front; the view keeps its place by {@link #takePrepended}. */
	public void olderPage(Page<E> page) {
		pages++;
		version++;
		fetched.addAll(0, page.entries());
		prepended += page.entries().size();
		more = page.more();
	}

	/** The gap fill replaced the fetched entries with newer contiguous ones (older ones load again on scrolling up). */
	public void replaced() {
		prepended = 0;
	}

	/**
	 * The {@code before} of the next older page, or null when none is asked: busy (a request runs), nothing older is
	 * stored ({@code more} false), nothing fetched yet, or the cap. At the cap the entries the live tail shows anyway are
	 * dropped first; when that frees nothing, {@link #full} turns on.
	 */
	public @Nullable Long olderBefore(boolean busy, List<E> live) {
		if (busy || !more || fetched.isEmpty()) {
			return null;
		}
		if (fetched.size() >= LogJoin.MAX_FETCHED) {
			int keep = LogJoin.keepBeforeLive(fetched, live, tsOf);
			if (keep < fetched.size() && keep > 0) {
				fetched.subList(keep, fetched.size()).clear();
				version++;
			}
			if (fetched.size() >= LogJoin.MAX_FETCHED) {
				full = true;
				return null;
			}
		}
		return tsOf.applyAsLong(fetched.get(0));
	}

	/** Entries the last older pages put in front (consumed: the view shifts its offset by them once). */
	public int takePrepended() {
		int n = prepended;
		prepended = 0;
		return n;
	}
}
