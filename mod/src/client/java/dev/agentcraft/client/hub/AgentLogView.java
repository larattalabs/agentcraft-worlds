package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * An agent's full log for the Inbox's agent view (docs/WAVE2.md W3): pages of the Foreman's stored log
 * ({@code agent.logs.request}, oldest first, older pages prepended on demand) joined with the live tail the
 * snapshot and {@code agent.log} keep in {@link ForemanState#logs}. An older Foreman (no such message) leaves the
 * live tail alone, with a note. Client thread.
 */
final class AgentLogView {
	static final int PAGE = 200;

	final String agentId;
	/** Fetched entries, oldest first. */
	private final List<LogEntry> fetched = new ArrayList<>();
	private boolean more;
	private boolean loading;
	private boolean started;
	private @Nullable String error;
	private boolean unsupported;
	private int pages;
	/** Entries added at the front by the last older page (the view keeps its place by this many). */
	private int prepended;

	AgentLogView(String agentId) {
		this.agentId = agentId;
	}

	/** Asks for the newest page once (no-op when started). */
	CompletableFuture<Void> start() {
		if (started) {
			return CompletableFuture.completedFuture(null);
		}
		started = true;
		return request(null);
	}

	/** Asks for the page before the oldest entry shown (no-op while loading or when there is nothing older). */
	CompletableFuture<Void> loadOlder() {
		if (loading || !more || fetched.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		return request(fetched.get(0).ts());
	}

	private CompletableFuture<Void> request(@Nullable Long before) {
		if (!Foreman.connected()) {
			error = "the Foreman is not connected: the recent lines only";
			started = false; // try again once it is
			return CompletableFuture.completedFuture(null);
		}
		loading = true;
		error = null;
		return Foreman.agentLogs(agentId, before, PAGE).handle((ack, err) -> {
			loading = false;
			if (err != null) {
				Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				error = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
				return null;
			}
			if (!ack.ok()) {
				unsupported = Foreman.unsupported(ack);
				error = unsupported ? "older lines need a newer Foreman (agent.logs.request)" : Foreman.refusal("The log", ack);
				more = false;
				return null;
			}
			Protocol.LogPage page = Foreman.logPageOf(ack);
			if (page == null) {
				error = "the Foreman sent no log page";
				return null;
			}
			pages++;
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

	/** Everything to show, oldest first: the fetched pages, then the live tail's newer entries. */
	List<LogEntry> entries() {
		ForemanState s = Foreman.state();
		List<LogEntry> live = s == null ? List.of() : s.logs(agentId);
		if (fetched.isEmpty()) {
			return List.copyOf(live);
		}
		List<LogEntry> out = new ArrayList<>(fetched);
		LogEntry last = fetched.get(fetched.size() - 1);
		for (LogEntry e : live) {
			if (e.ts() > last.ts() || e.ts() == last.ts() && !fetched.subList(Math.max(0, fetched.size() - 50), fetched.size()).contains(e)) {
				out.add(e);
			}
		}
		return out;
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
