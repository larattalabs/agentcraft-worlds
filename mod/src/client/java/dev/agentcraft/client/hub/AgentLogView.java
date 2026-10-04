package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.hub.LogJoin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * An agent's full log for the Inbox's agent view (docs/WAVE2.md W3): pages of the Foreman's stored log
 * ({@code agent.logs.request}, oldest first, older pages prepended on demand) joined with the live tail the
 * snapshot and {@code agent.log} keep in {@link ForemanState#logs}. An older Foreman (no such message) leaves the
 * live tail alone, with a note. A gap between the fetched pages and the live tail (more entries arrived than the tail
 * keeps) is filled by paging back from the tail ({@link LogJoin}); the fetched pages stop at
 * {@link LogJoin#MAX_FETCHED} entries. Client thread.
 */
final class AgentLogView {
	static final int PAGE = 200;
	/** after a failed first page, the next try waits this long */
	static final long RETRY_MS = 5000;
	/** gap pages collected before giving up on keeping the older pages (they are replaced by the contiguous newer ones) */
	static final int GAP_MAX = LogJoin.MAX_FETCHED;

	final String agentId;
	/** Fetched entries, oldest first (at most {@link LogJoin#MAX_FETCHED} plus a page). */
	private final List<LogEntry> fetched = new ArrayList<>();
	private boolean more;
	private boolean loading;
	private boolean filling;
	private boolean started;
	private long retryAt;
	private @Nullable String error;
	private boolean unsupported;
	private boolean full;
	private int pages;
	private int gapFills;
	/** the live tail's first ts a gap fill already ran for (no second try until the tail moves) */
	private long gapTriedFor = Long.MIN_VALUE;
	/** Entries added at the front by the last older page (the view keeps its place by this many). */
	private int prepended;
	/** bumped on every change to the fetched entries (the view's wrapped-line cache keys on it) */
	private int version;

	AgentLogView(String agentId) {
		this.agentId = agentId;
	}

	/**
	 * Asks for the newest page once (no-op when started). A failed first page (not connected, timeout, refusal) is asked
	 * for again after {@link #RETRY_MS}; an older Foreman without the message is not.
	 */
	CompletableFuture<Void> start() {
		if (started || unsupported || System.currentTimeMillis() < retryAt) {
			return CompletableFuture.completedFuture(null);
		}
		started = true;
		return request(null);
	}

	/**
	 * Once a frame while the view shows: the first page (with retries), and a gap fill when the live tail has moved
	 * past the fetched pages (more entries arrived than the tail keeps). Never runs next to another request.
	 */
	void maintain() {
		start();
		if (loading || filling || fetched.isEmpty() || !Foreman.connected()) {
			return;
		}
		List<LogEntry> live = live();
		if (LogJoin.gap(fetched, live, LogEntry::ts) && live.get(0).ts() != gapTriedFor) {
			gapTriedFor = live.get(0).ts();
			fillGap(LogJoin.gapBefore(live, LogEntry::ts), new ArrayList<>());
		}
	}

	/** Pages back from {@code before} until the collected pages reach the fetched end, then appends them. */
	private void fillGap(long before, List<LogEntry> collected) {
		filling = true;
		Foreman.agentLogs(agentId, before, PAGE).handle((ack, err) -> {
			Protocol.LogPage page = err == null && ack.ok() ? Foreman.logPageOf(ack) : null;
			if (page == null) {
				filling = false; // tried for this tail head; the next one tries again
				return null;
			}
			List<LogEntry> got = new ArrayList<>(page.entries());
			got.addAll(collected);
			if (LogJoin.fill(fetched, got, LogEntry::ts) >= 0) {
				if (LogJoin.trimOldest(fetched) > 0) {
					more = true; // the oldest went (the cap); scrolling up loads them again
					full = false;
				}
				filling = false;
				gapFills++;
				version++;
				return null;
			}
			if (!page.more() || page.entries().isEmpty()) {
				// nothing older is stored (rotated away): keep what is fetched and add the newer entries
				long last = fetched.get(fetched.size() - 1).ts();
				got.stream().filter(e -> e.ts() > last).forEach(fetched::add);
				if (LogJoin.trimOldest(fetched) > 0) {
					more = true;
					full = false;
				}
				filling = false;
				gapFills++;
				version++;
				return null;
			}
			if (got.size() >= GAP_MAX) {
				// too far behind: the contiguous newer pages replace the older ones (scrolling up loads them again)
				fetched.clear();
				fetched.addAll(got);
				more = true;
				full = false;
				prepended = 0;
				filling = false;
				gapFills++;
				version++;
				return null;
			}
			fillGap(page.entries().get(0).ts(), got);
			return null;
		});
	}

	/** Asks for the page before the oldest entry shown (no-op while loading, when there is nothing older, or at the cap). */
	CompletableFuture<Void> loadOlder() {
		if (loading || filling || !more || fetched.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		if (fetched.size() >= LogJoin.MAX_FETCHED) {
			// first drop what the live tail shows anyway, then stop at the cap
			int keep = LogJoin.keepBeforeLive(fetched, live(), LogEntry::ts);
			if (keep < fetched.size() && keep > 0) {
				fetched.subList(keep, fetched.size()).clear();
				version++;
			}
			if (fetched.size() >= LogJoin.MAX_FETCHED) {
				full = true;
				error = "the view keeps " + LogJoin.MAX_FETCHED + " lines; older ones are in the Foreman's logs/" + agentId + ".jsonl";
				return CompletableFuture.completedFuture(null);
			}
		}
		return request(fetched.get(0).ts());
	}

	private List<LogEntry> live() {
		ForemanState s = Foreman.state();
		return s == null ? List.of() : s.logs(agentId);
	}

	private CompletableFuture<Void> request(@Nullable Long before) {
		if (!Foreman.connected()) {
			error = "the Foreman is not connected: the recent lines only";
			if (before == null) {
				started = false; // try again once it is
			}
			return CompletableFuture.completedFuture(null);
		}
		loading = true;
		error = null;
		return Foreman.agentLogs(agentId, before, PAGE).handle((ack, err) -> {
			loading = false;
			if (err != null) {
				Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				error = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
				retryFirst(before);
				return null;
			}
			if (!ack.ok()) {
				unsupported = Foreman.unsupported(ack);
				error = unsupported ? "older lines need a newer Foreman (agent.logs.request)" : Foreman.refusal("The log", ack);
				more = false;
				retryFirst(before);
				return null;
			}
			Protocol.LogPage page = Foreman.logPageOf(ack);
			if (page == null) {
				error = "the Foreman sent no log page";
				retryFirst(before);
				return null;
			}
			pages++;
			version++;
			if (before == null) {
				fetched.clear();
				fetched.addAll(page.entries());
				prepended = 0;
			} else {
				fetched.addAll(0, page.entries());
				prepended += page.entries().size();
			}
			more = page.more();
			return null;
		});
	}

	/** A failed first page: a later frame asks again (after {@link #RETRY_MS}; never for an older Foreman). */
	private void retryFirst(@Nullable Long before) {
		if (before == null && !unsupported) {
			started = false;
			retryAt = System.currentTimeMillis() + RETRY_MS;
		}
	}

	/** Everything to show, oldest first: the fetched pages, then the live tail's newer entries. No side effects. */
	List<LogEntry> entries() {
		return LogJoin.join(fetched, live(), LogEntry::ts);
	}

	/** Changes whenever {@link #entries} may have: the fetched pages' version and the live tail's ends. */
	String cacheKey() {
		List<LogEntry> live = live();
		return version + "/" + live.size() + "/" + (live.isEmpty() ? 0 : live.get(0).ts()) + "/" + (live.isEmpty() ? 0 : live.get(live.size() - 1).ts())
			+ "/" + (live.isEmpty() ? 0 : live.get(live.size() - 1).text().length());
	}

	boolean gap() {
		return LogJoin.gap(fetched, live(), LogEntry::ts);
	}

	/** Entries the last older page put in front (consumed: the view shifts its offset by them once). */
	int takePrepended() {
		int n = prepended;
		prepended = 0;
		return n;
	}

	boolean more() {
		return more;
	}

	boolean loading() {
		return loading;
	}

	@Nullable String error() {
		return error;
	}

	boolean unsupported() {
		return unsupported;
	}

	JsonObject state(int shownFrom, int shownRows) {
		JsonObject o = new JsonObject();
		o.addProperty("agentId", agentId);
		List<LogEntry> all = entries();
		o.addProperty("entries", all.size());
		o.addProperty("fetched", fetched.size());
		o.addProperty("pages", pages);
		o.addProperty("more", more);
		o.addProperty("loading", loading);
		o.addProperty("error", error);
		o.addProperty("unsupported", unsupported);
		o.addProperty("filling", filling);
		o.addProperty("gap", gap());
		o.addProperty("gapFills", gapFills);
		o.addProperty("full", full);
		o.addProperty("retryInMs", started ? 0 : Math.max(0, retryAt - System.currentTimeMillis()));
		o.addProperty("oldestTs", all.isEmpty() ? null : all.get(0).ts());
		o.addProperty("newestTs", all.isEmpty() ? null : all.get(all.size() - 1).ts());
		o.addProperty("scrollRow", shownFrom);
		o.addProperty("viewRows", shownRows);
		JsonArray tail = new JsonArray();
		for (int i = Math.max(0, all.size() - 5); i < all.size(); i++) {
			JsonObject e = new JsonObject();
			e.addProperty("ts", all.get(i).ts());
			e.addProperty("kind", all.get(i).kind().wire());
			e.addProperty("text", all.get(i).text().length() > 120 ? all.get(i).text().substring(0, 120) + "…" : all.get(i).text());
			tail.add(e);
		}
		o.add("last", tail);
		return o;
	}
}
