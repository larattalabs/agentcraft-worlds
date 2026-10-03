package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Diff;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Static entry point to the Foreman for every client feature.
 *
 * <pre>
 * Foreman.state().agents()                      // read the model (client thread)
 * Foreman.addListener(new ForemanListener() {...}) // change callbacks (client thread)
 * Foreman.submitGoal("Add OAuth", null).thenAccept(ack -> ...)  // intents; futures complete on the client thread
 * Foreman.requestDiff(repoId, worktree).thenAccept(diff -> ...)
 * </pre>
 *
 * Intents fail fast (exceptionally) while the link is not synced; an {@link Ack} with
 * {@code ok=false} carries the Foreman's error text.
 */
public final class Foreman {
	private static ForemanState state;
	private static ForemanLink link;

	private Foreman() {
	}

	static void install(ForemanState s, ForemanLink l) {
		state = s;
		link = l;
	}

	public static ForemanState state() {
		return state;
	}

	public static ForemanLink link() {
		return link;
	}

	public static void addListener(ForemanListener l) {
		state.addListener(l);
	}

	public static boolean connected() {
		return link != null && link.status().synced();
	}

	/** Send any client message (type + payload); see docs/protocol.md "Mod -> Foreman". */
	public static CompletableFuture<Ack> send(String type, JsonObject payload) {
		JsonObject m = payload.deepCopy();
		m.addProperty("type", type);
		return link.send(m);
	}

	/** New goal for the lead (console: plain text). {@code repoId} null = the Foreman's default repo. */
	public static CompletableFuture<Ack> submitGoal(String text, @Nullable String repoId) {
		return link.send(ForemanJson.msg("goal.submit").put("text", text).put("repoId", repoId).json());
	}

	/** Message an agent ({@code to} = agent id) or everyone ({@code "all"}; a leading "@name" routes it). */
	public static CompletableFuture<Ack> message(String to, String text) {
		return link.send(ForemanJson.msg("user.message").put("to", to).put("text", text).json());
	}

	/** Answer a decision with an option label (preferred) and/or free text. */
	public static CompletableFuture<Ack> answer(String decisionId, @Nullable String option, @Nullable String text) {
		return link.send(ForemanJson.msg("decision.answer").put("decisionId", decisionId).put("option", option).put("text", text).json());
	}

	/** {@code action}: reassign | cancel | retry | prioritize; {@code arg}: agent id / priority. */
	public static CompletableFuture<Ack> taskAction(String taskId, String action, @Nullable String arg) {
		return link.send(ForemanJson.msg("task.action").put("taskId", taskId).put("action", action).put("arg", arg).json());
	}

	/** {@code action}: pause | resume | stop | spawn; {@code arg}: spawn task id. */
	public static CompletableFuture<Ack> agentAction(String agentId, String action, @Nullable String arg) {
		return link.send(ForemanJson.msg("agent.action").put("agentId", agentId).put("action", action).put("arg", arg).json());
	}

	public static CompletableFuture<Ack> addRepo(String path) {
		return link.send(ForemanJson.msg("repo.add").put("path", path).json());
	}

	// ------------------------------------------------------------------ Repos and Goals tabs (docs/HUB.md)

	/**
	 * New goal with the hub form's options: {@code repos} (the first is the goal's repo; a goal for a group
	 * building sends all its repos), {@code branch} ("continue a branch"), {@code instructions} (standing
	 * instructions). Null / empty options are omitted. An older Foreman accepts it and drops the options.
	 */
	public static CompletableFuture<Ack> submitGoal(String text, List<String> repos, @Nullable String branch, List<String> instructions) {
		return link.send(ForemanJson.msg("goal.submit").put("text", text).put("repoId", repos.isEmpty() ? null : repos.get(0))
			.put("repos", repos.size() > 1 ? List.copyOf(repos) : null).put("branch", branch == null || branch.isBlank() ? null : branch.strip())
			.put("instructions", instructions.isEmpty() ? null : List.copyOf(instructions)).json());
	}

	/** A message to the goal's lead about that goal ({@code goal.message}). */
	public static CompletableFuture<Ack> goalMessage(String goalId, String text) {
		return link.send(ForemanJson.msg("goal.message").put("goalId", goalId).put("text", text).json());
	}

	/** Replaces the goal's standing instructions ({@code goal.instructions}). */
	public static CompletableFuture<Ack> goalInstructions(String goalId, List<String> instructions) {
		return link.send(ForemanJson.msg("goal.instructions").put("goalId", goalId).put("instructions", List.copyOf(instructions)).json());
	}

	/** Writes the goal's plan note as the user ({@code goal.plan}). */
	public static CompletableFuture<Ack> goalPlan(String goalId, String body) {
		return link.send(ForemanJson.msg("goal.plan").put("goalId", goalId).put("body", body).json());
	}

	/** Cancels the goal's open tasks ({@code goal.cancel}). */
	public static CompletableFuture<Ack> goalCancel(String goalId) {
		return link.send(ForemanJson.msg("goal.cancel").put("goalId", goalId).json());
	}

	/** What happened since {@code since} (one goal, or every goal when {@code goalId} is null); parse the ack with {@link #digestOf}. */
	public static CompletableFuture<Ack> goalDigest(@Nullable String goalId, long since) {
		return link.send(ForemanJson.msg("goal.digest").put("goalId", goalId).put("since", since).json());
	}

	/** Unregisters a repo ({@code repo.remove}; refused while it has open tasks). */
	public static CompletableFuture<Ack> removeRepo(String repoId) {
		return link.send(ForemanJson.msg("repo.remove").put("repoId", repoId).json());
	}

	/** Polls the pull requests of tasks in status pr now ({@code pr.refresh}; {@code taskId} null = all). */
	public static CompletableFuture<Ack> refreshPrs(@Nullable String taskId) {
		return link.send(ForemanJson.msg("pr.refresh").put("taskId", taskId).json());
	}

	/** The digest in a {@code goal.digest} ack's result, or null when it has none. */
	public static Protocol.@Nullable Digest digestOf(Ack ack) {
		if (!ack.ok() || ack.result() == null) {
			return null;
		}
		try {
			return ForemanJson.read(ack.result(), Protocol.Digest.class);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Whether a refusal means "this Foreman does not know the message type" (an older Foreman acks an unknown
	 * type {@code ok:false} with its schema error on {@code type}, e.g. "type: Invalid discriminator value").
	 */
	public static boolean unsupported(@Nullable Ack ack) {
		return ack != null && !ack.ok() && unsupported(ack.error());
	}

	public static boolean unsupported(@Nullable String error) {
		if (error == null) {
			return false;
		}
		String e = error.toLowerCase(java.util.Locale.ROOT);
		return e.startsWith("type:") || e.contains("discriminator") || e.contains("unknown message type") || e.contains("unknown type");
	}

	/** The line to show for a refusal: "needs a newer Foreman" for an unknown type, else the Foreman's error. */
	public static String refusal(String what, @Nullable Ack ack) {
		if (unsupported(ack)) {
			return what + " needs a newer Foreman";
		}
		return what + " refused: " + (ack == null || ack.error() == null ? "no reason given" : ack.error());
	}

	/** Structured diff of a worktree (or an agent id: its current worktree) vs its base. */
	public static CompletableFuture<Diff> requestDiff(String repoId, String worktree) {
		return link.requestDiff(repoId, worktree);
	}
}
