package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Digest;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.hub.GoalLogic;
import dev.agentcraft.hub.HubSeen;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The model behind the hub's Repos and Goals tabs (docs/HUB.md "Repos and Goals tabs"): what a goal's
 * thread, tasks and plan are, which goals are unread ({@code hub-seen.json} via {@link HubSeen}), the
 * "since you were away" digests, messages being sent (shown at once as "sending…"), and the sends
 * themselves, each completing with a {@link Note} for the screen. Client thread.
 *
 * <p>An older Foreman: the new fields are absent (no instructions, plan id, PR summary, goalId on feed
 * items) and the new message types are acked {@code ok:false}: every send then reports "… needs a newer
 * Foreman" ({@link Foreman#unsupported}). A goal's thread falls back to decisions whose task belongs to it.
 * The thread only holds what is in the model's feed tail ({@link ForemanState#FEED_TAIL} items, replaced by
 * every snapshot).
 */
public final class HubGoals {
	/** The outcome of a send, for the screen and DevBridge. {@code unsupported}: the Foreman does not know the message. */
	public record Note(boolean ok, String message, boolean unsupported, @Nullable JsonObject result) {
		static Note ok(String message, @Nullable JsonObject result) {
			return new Note(true, message, false, result);
		}

		static Note failed(String message) {
			return new Note(false, message, false, null);
		}
	}

	/** A goal message on its way (or refused): shown in the thread at once. */
	public record Pending(String goalId, String text, long ts, boolean failed, @Nullable String error) {
	}

	/** One entry of a goal's thread, in time order: a feed item, a decision or a pending message. */
	public record Entry(long ts, @Nullable FeedItem feed, @Nullable Decision decision, @Nullable Pending pending) {
	}

	/** A digest request and its answer. {@code goalId} null = every goal (the away panel). */
	public static final class DigestState {
		public final @Nullable String goalId;
		public final long since;
		public final long askedAt;
		public @Nullable Digest digest;
		public @Nullable String error;
		public boolean unsupported;
		public boolean dismissed;

		DigestState(@Nullable String goalId, long since) {
			this.goalId = goalId;
			this.since = since;
			this.askedAt = System.currentTimeMillis();
		}

		public boolean loading() {
			return digest == null && error == null;
		}
	}

	private static @Nullable HubSeen seen;
	private static long lastSave;
	private static final Map<String, List<Pending>> PENDING = new HashMap<>();
	private static @Nullable DigestState away;
	private static final Map<String, DigestState> GOAL_DIGESTS = new HashMap<>();
	/** The goal open in the hub (counts as seen while open), null = none. */
	private static @Nullable String viewing;
	private static boolean goalsTabShown;

	private HubGoals() {
	}

	// ------------------------------------------------------------------ seen

	/** The key of this world in hub-seen.json: the save folder name, else "multiplayer". */
	public static String world() {
		String w = Buildings.worldId();
		return w == null ? "multiplayer" : w;
	}

	public static Path seenFile() {
		return FabricLoader.getInstance().getGameDir().resolve("agentcraft").resolve(HubSeen.FILE);
	}

	public static HubSeen seen() {
		if (seen == null) {
			seen = HubSeen.load(seenFile());
		}
		return seen;
	}

	/** Saves hub-seen.json when it changed (throttled to every 2 s unless {@code now}). */
	public static void flush(boolean now) {
		HubSeen s = seen;
		if (s == null || !s.dirty() || !now && System.currentTimeMillis() - lastSave < 2000) {
			return;
		}
		lastSave = System.currentTimeMillis();
		try {
			s.save(seenFile());
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", seenFile(), e);
		}
	}

	/** dev.goals.seen: forget this world's marks (tab = the Goals tab's last look, 0 = never) and the digests. */
	static void resetSeen(long tab) {
		seen().resetWorld(world(), tab);
		away = null;
		GOAL_DIGESTS.clear();
		flush(true);
	}

	/** The Goals tab is (or stops being) on screen: it counts as seen up to now. */
	static void goalsTabShown(boolean shown) {
		if (shown || goalsTabShown) {
			seen().markTab(world(), System.currentTimeMillis());
		}
		goalsTabShown = shown;
		flush(false);
	}

	/** Opens (or leaves, null) a goal in the hub: the one left and the one opened count as seen now. */
	static void view(@Nullable String goalId) {
		long now = System.currentTimeMillis();
		if (viewing != null) {
			seen().markGoal(world(), viewing, now);
		}
		viewing = goalId;
		if (goalId != null) {
			seen().markGoal(world(), goalId, now);
		}
		flush(false);
	}

	static @Nullable String viewing() {
		return viewing;
	}

	/** The newest activity of a goal: its update, its feed items, decisions and tasks. */
	public static long activity(Goal g) {
		ForemanState s = Foreman.state();
		long t = g.updatedAt();
		if (s == null) {
			return t;
		}
		for (FeedItem f : s.feed()) {
			if (g.id().equals(f.goalId())) {
				t = Math.max(t, f.ts());
			}
		}
		for (Decision d : s.decisions().values()) {
			if (g.id().equals(goalOf(d))) {
				t = Math.max(t, d.createdAt());
			}
		}
		for (Task k : s.tasks().values()) {
			if (g.id().equals(k.goalId())) {
				t = Math.max(t, k.updatedAt());
			}
		}
		return t;
	}

	/** Whether a goal has activity the player has not looked at (never the goal open now). */
	public static boolean unread(Goal g) {
		if (g.id().equals(viewing)) {
			return false;
		}
		return GoalLogic.unread(activity(g), seen().goalSeen(world(), g.id()));
	}

	// ------------------------------------------------------------------ queries

	/** Goals newest first, optionally only those touching {@code buildingId}'s repos (null = all). */
	public static List<Goal> goals(@Nullable String buildingId) {
		ForemanState s = Foreman.state();
		if (s == null) {
			return List.of();
		}
		List<String> filter = List.of();
		if (buildingId != null) {
			Building b = Buildings.get(buildingId);
			filter = b == null ? List.of() : b.repos();
		}
		List<Goal> out = new ArrayList<>();
		for (Goal g : GoalLogic.newestFirst(s.goals().values(), Goal::createdAt, Goal::id)) {
			if (buildingId == null || GoalLogic.inBuilding(g.allRepos(), filter)) {
				out.add(g);
			}
		}
		return out;
	}

	public static @Nullable Goal goal(@Nullable String id) {
		ForemanState s = Foreman.state();
		return s == null || id == null ? null : s.goals().get(id);
	}

	/** The goal a decision is about: its {@code goalId}, else its task's. */
	public static @Nullable String goalOf(Decision d) {
		if (d.goalId() != null) {
			return d.goalId();
		}
		ForemanState s = Foreman.state();
		Task t = s == null || d.taskId() == null ? null : s.task(d.taskId());
		return t == null ? null : t.goalId();
	}

	/** The goal's tasks, oldest first. */
	public static List<Task> tasks(String goalId) {
		ForemanState s = Foreman.state();
		List<Task> out = new ArrayList<>();
		if (s != null) {
			for (Task t : s.tasks().values()) {
				if (goalId.equals(t.goalId())) {
					out.add(t);
				}
			}
		}
		out.sort(java.util.Comparator.comparingLong(Task::createdAt));
		return out;
	}

	/** The goal's thread in time order: its feed items, its decisions (at their creation) and pending messages. */
	public static List<Entry> thread(String goalId) {
		ForemanState s = Foreman.state();
		List<Entry> out = new ArrayList<>();
		if (s != null) {
			for (FeedItem f : s.feed()) {
				if (goalId.equals(f.goalId())) {
					out.add(new Entry(f.ts(), f, null, null));
				}
			}
			for (Decision d : s.decisions().values()) {
				if (goalId.equals(goalOf(d))) {
					out.add(new Entry(d.createdAt(), null, d, null));
				}
			}
		}
		for (Pending p : PENDING.getOrDefault(goalId, List.of())) {
			out.add(new Entry(p.ts(), null, null, p));
		}
		out.sort(java.util.Comparator.comparingLong(Entry::ts));
		return out;
	}

	public static List<Pending> pending(String goalId) {
		return List.copyOf(PENDING.getOrDefault(goalId, List.of()));
	}

	/** The goal's plan note (its memory entry {@code planId}), or null. */
	public static Protocol.@Nullable MemoryEntry plan(Goal g) {
		ForemanState s = Foreman.state();
		return s == null || g.planId() == null ? null : s.memory().get(g.planId());
	}

	/** Branches to offer for "continue a branch": the repo's worktree branches and its goals' branches (not the base). */
	public static List<String> branches(@Nullable String repoId) {
		ForemanState s = Foreman.state();
		Set<String> out = new LinkedHashSet<>();
		if (s == null || repoId == null) {
			return List.of();
		}
		Repo r = s.repo(repoId);
		if (r != null) {
			for (Protocol.Worktree w : r.worktrees()) {
				if (w.branch() != null && !w.branch().isBlank() && !w.branch().equals(r.branch())) {
					out.add(w.branch());
				}
			}
		}
		for (Goal g : s.goals().values()) {
			if (g.branch() != null && g.allRepos().contains(repoId)) {
				out.add(g.branch());
			}
		}
		return List.copyOf(out);
	}

	/** The building (in this world) that holds a repo, and the repo's wing number (1-based), or null. */
	public record Wing(Building building, int wing) {
	}

	public static @Nullable Wing wingOf(String repoId) {
		for (Building b : Buildings.all()) {
			int i = b.repos().indexOf(repoId);
			if (i >= 0) {
				return new Wing(b, i + 1);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ digests

	/**
	 * Hub (or Goals tab) opened: after {@link HubSeen#AWAY_MS} away, ask for the "since you were away" digest of
	 * every goal (once per away stretch). Returns whether a request went out.
	 */
	static boolean checkAway() {
		long now = System.currentTimeMillis();
		long since = seen().tabSeen(world());
		if (!seen().away(world(), now) || !Foreman.connected()) {
			return false;
		}
		if (away != null && away.since == since) {
			return false;
		}
		requestAway(since);
		return true;
	}

	/** Asks {@code goal.digest {since}} for the away panel. */
	public static CompletableFuture<DigestState> requestAway(long since) {
		DigestState st = new DigestState(null, since);
		away = st;
		return fill(st, Foreman.goalDigest(null, since));
	}

	/** Asks {@code goal.digest {goalId, since}} for a goal's detail. */
	public static CompletableFuture<DigestState> requestGoal(String goalId, long since) {
		DigestState st = new DigestState(goalId, since);
		GOAL_DIGESTS.put(goalId, st);
		return fill(st, Foreman.goalDigest(goalId, since));
	}

	private static CompletableFuture<DigestState> fill(DigestState st, CompletableFuture<Ack> f) {
		return f.handle((ack, err) -> {
			if (err != null) {
				st.error = describe(err);
			} else if (!ack.ok()) {
				st.unsupported = Foreman.unsupported(ack);
				st.error = Foreman.refusal("The digest", ack);
			} else {
				Digest d = Foreman.digestOf(ack);
				if (d == null) {
					st.error = "The Foreman sent no digest";
				} else {
					st.digest = d;
				}
			}
			return st;
		});
	}

	public static @Nullable DigestState away() {
		return away;
	}

	public static void dismissAway() {
		if (away != null) {
			away.dismissed = true;
		}
	}

	public static @Nullable DigestState goalDigest(String goalId) {
		return GOAL_DIGESTS.get(goalId);
	}

	/** The goal is being opened: when it had activity since its last view, ask for its own digest. */
	static void openGoal(Goal g) {
		long since = seen().goalSeen(world(), g.id());
		if (since > 0 && activity(g) > since && Foreman.connected()) {
			DigestState prev = GOAL_DIGESTS.get(g.id());
			if (prev == null || prev.since != since) {
				requestGoal(g.id(), since);
			}
		}
	}

	/** Digest lines of one goal as {@link GoalLogic.Line}s. */
	public static List<GoalLogic.Line> lines(Protocol.GoalDigest gd) {
		List<GoalLogic.Line> out = new ArrayList<>();
		for (Protocol.DigestLine l : gd.lines()) {
			out.add(new GoalLogic.Line(l.ts(), l.kind(), l.text()));
		}
		return out;
	}

	// ------------------------------------------------------------------ sends

	private static String describe(Throwable err) {
		Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
		return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
	}

	private static CompletableFuture<Note> note(CompletableFuture<Ack> f, String what, String okMessage) {
		return f.handle((ack, err) -> {
			if (err != null) {
				return Note.failed(what + " not sent: " + describe(err));
			}
			if (!ack.ok()) {
				return new Note(false, Foreman.refusal(what, ack), Foreman.unsupported(ack), null);
			}
			return Note.ok(okMessage, ack.result());
		});
	}

	private static @Nullable CompletableFuture<Note> offline(String what) {
		return Foreman.connected() ? null : CompletableFuture.completedFuture(Note.failed(what + ": the Foreman is not connected"));
	}

	/** {@code goal.message}: shown at once as "sending…"; on success it leaves (the Foreman's feed item takes its place). */
	public static CompletableFuture<Note> message(String goalId, String text) {
		String t = text.strip();
		if (t.isEmpty()) {
			return CompletableFuture.completedFuture(Note.failed("Type a message first"));
		}
		CompletableFuture<Note> off = offline("Message");
		if (off != null) {
			return off;
		}
		Pending p = new Pending(goalId, t, System.currentTimeMillis(), false, null);
		PENDING.computeIfAbsent(goalId, k -> new ArrayList<>()).add(p);
		return note(Foreman.goalMessage(goalId, t), "Message", "Sent to the lead").thenApply(n -> {
			if (n.ok() && n.result() != null && n.result().has("leadId")) {
				n = Note.ok("Sent to " + dev.agentcraft.client.hud.UiBits.agentName(n.result().get("leadId").getAsString()), n.result());
			}
			List<Pending> list = PENDING.getOrDefault(goalId, new ArrayList<>());
			int i = list.indexOf(p);
			if (n.ok()) {
				if (i >= 0) {
					list.remove(i);
				}
				// your own message is not news
				if (goalId.equals(viewing)) {
					seen().markGoal(world(), goalId, System.currentTimeMillis());
				}
			} else if (i >= 0) {
				list.set(i, new Pending(goalId, t, p.ts(), true, n.message()));
			}
			return n;
		});
	}

	/** Drops refused messages of a goal (the text went back to the input). */
	public static void clearFailed(String goalId) {
		List<Pending> list = PENDING.get(goalId);
		if (list != null) {
			list.removeIf(Pending::failed);
		}
	}

	public static CompletableFuture<Note> instructions(String goalId, List<String> instructions) {
		CompletableFuture<Note> off = offline("Instructions");
		return off != null ? off : note(Foreman.goalInstructions(goalId, instructions), "Instructions", "Instructions saved; the lead was told")
			.thenApply(n -> unchanged(n, "Instructions unchanged: the lead was not told"));
	}

	public static CompletableFuture<Note> plan(String goalId, String body) {
		CompletableFuture<Note> off = offline("Plan");
		return off != null ? off : note(Foreman.goalPlan(goalId, body), "The plan", "Plan saved; the lead got the change")
			.thenApply(n -> unchanged(n, "Plan unchanged: the lead was not told"));
	}

	public static CompletableFuture<Note> cancel(String goalId) {
		CompletableFuture<Note> off = offline("Cancel");
		return off != null ? off : note(Foreman.goalCancel(goalId), "Cancel", "Cancelled " + goalId + ": its open tasks stop (worktrees kept)")
			.thenApply(n -> {
				if (n.ok() && n.result() != null && n.result().has("cancelled") && n.result().get("cancelled").isJsonArray()) {
					int k = n.result().getAsJsonArray("cancelled").size();
					return Note.ok("Cancelled " + goalId + ": " + (k == 0 ? "no open tasks" : k == 1 ? "1 task stopped" : k + " tasks stopped")
						+ " (worktrees kept)", n.result());
				}
				return n;
			});
	}

	/** {@code goal.submit} with the form's options; the result carries {@code goalId}. */
	public static CompletableFuture<Note> submit(String text, List<String> repos, @Nullable String branch, List<String> instructions) {
		if (text.isBlank()) {
			return CompletableFuture.completedFuture(Note.failed("Say what the goal is"));
		}
		CompletableFuture<Note> off = offline("Goal");
		return off != null ? off : note(Foreman.submitGoal(text.strip(), repos, branch, instructions), "Goal", "Goal submitted");
	}

	/** {@code changed: false} in an ok ack: nothing was sent to the lead. */
	private static Note unchanged(Note n, String message) {
		if (n.ok() && n.result() != null && n.result().has("changed") && !n.result().get("changed").getAsBoolean()) {
			return Note.ok(message, n.result());
		}
		return n;
	}

	public static CompletableFuture<Note> addRepo(String path) {
		if (path.isBlank()) {
			return CompletableFuture.completedFuture(Note.failed("Type the repo's path"));
		}
		CompletableFuture<Note> off = offline("Add repo");
		return off != null ? off : note(Foreman.addRepo(path.strip()), "Add repo", "Added " + path.strip());
	}

	public static CompletableFuture<Note> removeRepo(String repoId) {
		CompletableFuture<Note> off = offline("Remove");
		return off != null ? off : note(Foreman.removeRepo(repoId), "Remove", "Removed " + repoId + " (worktrees and branches stay on disk)")
			.thenApply(n -> {
				if (n.ok() && Foreman.state() != null) {
					Foreman.state().forgetRepo(repoId); // no removal broadcast: drop it here (others at their next snapshot)
				}
				return n;
			});
	}

	public static CompletableFuture<Note> refreshPrs() {
		CompletableFuture<Note> off = offline("Refresh PRs");
		return off != null ? off : note(Foreman.refreshPrs(null), "Refresh PRs", "Asked the Foreman to poll the PRs now");
	}

	// ------------------------------------------------------------------ JSON (DevBridge)

	public static JsonObject noteJson(Note n) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", n.ok());
		o.addProperty("message", n.message());
		o.addProperty("unsupported", n.unsupported());
		o.add("result", n.result());
		return o;
	}

	public static @Nullable JsonObject digestJson(@Nullable DigestState st) {
		if (st == null) {
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("goalId", st.goalId);
		o.addProperty("since", st.since);
		o.addProperty("askedAt", st.askedAt);
		o.addProperty("loading", st.loading());
		o.addProperty("error", st.error);
		o.addProperty("unsupported", st.unsupported);
		o.addProperty("dismissed", st.dismissed);
		if (st.digest != null) {
			o.addProperty("until", st.digest.until());
			JsonArray gs = new JsonArray();
			for (Protocol.GoalDigest gd : st.digest.goals()) {
				JsonObject j = new JsonObject();
				j.addProperty("goalId", gd.goalId());
				j.addProperty("summary", GoalLogic.summary(lines(gd)));
				j.addProperty("lines", gd.lines().size());
				JsonArray secs = new JsonArray();
				for (GoalLogic.Section s : GoalLogic.sections(lines(gd))) {
					secs.add(s.title() + " " + s.lines().size());
				}
				j.add("sections", secs);
				gs.add(j);
			}
			o.add("goals", gs);
		}
		return o;
	}

	public static JsonObject goalJson(Goal g) {
		JsonObject j = new JsonObject();
		j.addProperty("id", g.id());
		j.addProperty("text", g.text());
		j.addProperty("status", g.status().wire());
		j.addProperty("progress", g.progress());
		JsonArray rs = new JsonArray();
		g.allRepos().forEach(rs::add);
		j.add("repos", rs);
		j.addProperty("lead", g.lead());
		j.addProperty("branch", g.branch());
		j.addProperty("planId", g.planId());
		j.addProperty("instructions", g.instructions() == null ? null : g.instructions().size());
		j.addProperty("prs", g.prs() == null ? null : g.prs().size());
		j.addProperty("tasks", tasks(g.id()).size());
		j.addProperty("thread", thread(g.id()).size());
		j.addProperty("activity", activity(g));
		j.addProperty("seen", seen().goalSeen(world(), g.id()));
		j.addProperty("unread", unread(g));
		return j;
	}
}
