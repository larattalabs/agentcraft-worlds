package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The settings scopes the hub edits (global + one per repo, kept for the session so staged edits survive
 * closing the hub) and the Foreman restart ({@code foreman.restart}): after it is sent the hub shows
 * "restarting… reconnecting" until the link is synced with a newer snapshot than the one at the restart,
 * then every scope reloads (their {@link ConfigScope#tick} sees the new snapshot). A restart ack lost to the
 * closing socket counts as restarting, not as a failure. Client thread.
 */
final class HubConfig {
	private static final ConfigScope GLOBAL = new ConfigScope(null);
	private static final Map<String, ConfigScope> REPOS = new LinkedHashMap<>();
	private static boolean restarting;
	private static int restartSnapshots;
	private static long restartAt;
	private static @Nullable String restartNote;
	private static boolean restartNoteError;

	private HubConfig() {
	}

	static ConfigScope global() {
		return GLOBAL;
	}

	static ConfigScope repo(String repoId) {
		return REPOS.computeIfAbsent(repoId, ConfigScope::new);
	}

	/** Repo scopes created so far (Team tab roles, Repos tab editor). */
	static List<ConfigScope> repoScopes() {
		return List.copyOf(REPOS.values());
	}

	/** Config keys written but waiting for a restart (the Foreman's list plus this session's acks). */
	static List<String> restartRequired() {
		ForemanState s = Foreman.state();
		return s == null ? List.of() : s.restartRequired();
	}

	/** True between sending foreman.restart and the first snapshot of the restarted Foreman. */
	static boolean restarting() {
		if (restarting) {
			ForemanState s = Foreman.state();
			if (s != null && Foreman.connected() && s.snapshotCount() > restartSnapshots) {
				restarting = false;
				restartNote = "The Foreman restarted (" + Math.max(1, (System.currentTimeMillis() - restartAt) / 1000) + " s)";
				restartNoteError = false;
			}
		}
		return restarting;
	}

	static long restartingForMs() {
		return restarting ? System.currentTimeMillis() - restartAt : 0;
	}

	static @Nullable String restartNote() {
		return restartNote;
	}

	static boolean restartNoteError() {
		return restartNoteError;
	}

	/** {@code foreman.restart}: running turns are interrupted and resumed by the restarted Foreman; the mod reconnects. */
	static CompletableFuture<HubGoals.Note> restart() {
		if (restarting()) {
			return CompletableFuture.completedFuture(HubGoals.Note.failed("Already restarting"));
		}
		if (!Foreman.connected()) {
			return CompletableFuture.completedFuture(HubGoals.Note.failed("Restart: the Foreman is not connected"));
		}
		restarting = true;
		restartAt = System.currentTimeMillis();
		restartSnapshots = Foreman.state().snapshotCount();
		restartNote = "Restarting the Foreman…";
		restartNoteError = false;
		return Foreman.restart().handle((ack, err) -> {
			HubGoals.Note n;
			if (err != null) {
				String why = ConfigScope.describe(err);
				if (why.contains("connection lost") || why.contains("not connected")) {
					// the socket closed before the ack: that is the restart happening
					n = HubGoals.Note.ok("Restarting the Foreman (connection closed)", null);
				} else {
					restarting = false;
					n = HubGoals.Note.failed("Restart not sent: " + why);
				}
			} else if (!ack.ok()) {
				restarting = false;
				n = new HubGoals.Note(false, Foreman.unsupported(ack) ? "Restarting from the hub needs a newer Foreman" : Foreman.refusal("Restart",
					ack), Foreman.unsupported(ack), null);
			} else {
				n = HubGoals.Note.ok("Restarting the Foreman…", ack.result());
			}
			restartNote = n.message();
			restartNoteError = !n.ok();
			return n;
		});
	}

	/** Apply every scope with staged edits, the global one first (one config.set each; not atomic across scopes). */
	static CompletableFuture<HubGoals.Note> applyAll(List<ConfigScope> scopes, boolean confirmed) {
		List<ConfigScope> dirty = new ArrayList<>();
		for (ConfigScope s : scopes) {
			if (s.dirty()) {
				dirty.add(s);
			}
		}
		if (dirty.isEmpty()) {
			return CompletableFuture.completedFuture(HubGoals.Note.failed("Nothing to apply"));
		}
		if (!confirmed) {
			List<String> wide = widenings(dirty);
			if (!wide.isEmpty()) {
				// one confirm naming every widening change of every scope; nothing is sent yet
				for (ConfigScope s : dirty) {
					s.askConfirm();
				}
				return CompletableFuture.completedFuture(new HubGoals.Note(false, "confirm needed: " + String.join("; ", wide), false, null));
			}
		}
		CompletableFuture<List<HubGoals.Note>> chain = CompletableFuture.completedFuture(new ArrayList<>());
		for (ConfigScope s : dirty) {
			chain = chain.thenCompose(list -> {
				// stop at the first scope that refused or wants a confirm
				if (!list.isEmpty() && !list.getLast().ok()) {
					return CompletableFuture.completedFuture(list);
				}
				return s.apply(true).thenApply(n -> {
					list.add(n);
					return list;
				});
			});
		}
		return chain.thenApply(list -> {
			HubGoals.Note last = list.getLast();
			if (list.size() == 1) {
				return last;
			}
			List<String> msgs = new ArrayList<>();
			for (int i = 0; i < list.size(); i++) {
				msgs.add(dirty.get(i).id() + ": " + list.get(i).message());
			}
			return new HubGoals.Note(last.ok(), String.join(" | ", msgs), last.unsupported(), last.result());
		});
	}

	/** The widening changes of these scopes, each with its scope when not global. */
	static List<String> widenings(List<ConfigScope> scopes) {
		List<String> out = new ArrayList<>();
		for (ConfigScope s : scopes) {
			for (String w : s.widenings()) {
				out.add(s.repoId == null ? w : s.repoId + ": " + w);
			}
		}
		return out;
	}

	static JsonObject state() {
		JsonObject o = new JsonObject();
		JsonArray rr = new JsonArray();
		restartRequired().forEach(rr::add);
		o.add("restartRequired", rr);
		o.addProperty("restarting", restarting());
		o.addProperty("restartingForMs", restartingForMs());
		o.addProperty("restartNote", restartNote);
		ForemanState s = Foreman.state();
		o.addProperty("readOnly", s != null && s.readOnly());
		o.addProperty("readOnlyError", s == null ? null : s.readOnlyError());
		o.addProperty("connected", Foreman.connected());
		return o;
	}
}
