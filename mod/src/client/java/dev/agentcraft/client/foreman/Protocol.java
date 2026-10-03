package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Java mirror of the Foreman protocol v1 (docs/protocol.md, generated from foreman/src/protocol.ts).
 * Field names are exact. Optional fields are nullable (boxed when numeric/boolean); required lists
 * are never null (normalised to empty). Unknown JSON fields are ignored and unknown enum values map
 * to {@code UNKNOWN}, so a newer Foreman never breaks the mod.
 */
public final class Protocol {
	public static final int VERSION = 1;

	private Protocol() {
	}

	/** Marker for protocol enums: wire value = lower-case constant name; unknown values map to UNKNOWN. */
	public interface Wire {
		default String wire() {
			return ((Enum<?>) this).name().toLowerCase(Locale.ROOT);
		}
	}

	// ------------------------------------------------------------------ enums

	public enum AgentState implements Wire {
		IDLE, THINKING, READING, EDITING, RUNNING, TESTING, WAITING_USER, BLOCKED, DONE, ERROR, UNKNOWN;

		/** Status-dot / lamp family: idle, thinking, working, waiting, error, done. */
		public String family() {
			return switch (this) {
				case THINKING -> "thinking";
				case READING, EDITING, RUNNING, TESTING -> "working";
				case WAITING_USER -> "waiting";
				case BLOCKED, ERROR -> "error";
				case DONE -> "done";
				default -> "idle";
			};
		}
	}

	public enum Station implements Wire {
		DESK, LIBRARY, TERMINAL, TESTBENCH, MERGESTATION, MEETING, LOUNGE, USER, UNKNOWN
	}

	public enum AgentRole implements Wire {
		LEAD, WORKER, UNKNOWN
	}

	public enum TaskStatus implements Wire {
		TODO, DOING, REVIEW, PR, DONE, BLOCKED, CANCELLED, UNKNOWN
	}

	public enum CiStatus implements Wire {
		UNKNOWN, RUNNING, PASS, FAIL
	}

	public enum LogKind implements Wire {
		TEXT, TOOL, RESULT, ERROR, DIFF, UNKNOWN
	}

	public enum DecisionKind implements Wire {
		QUESTION, PERMISSION, MERGE, UNKNOWN
	}

	public enum DecisionStatus implements Wire {
		OPEN, ANSWERED, CANCELLED, UNKNOWN
	}

	public enum GoalStatus implements Wire {
		PLANNING, ACTIVE, DONE, FAILED, CANCELLED, UNKNOWN
	}

	public enum FeedKind implements Wire {
		GOAL, PLAN, TASK, MESSAGE, DECISION, MERGE, CI, MEMORY, SYSTEM, ERROR, USER, UNKNOWN
	}

	public enum NotifyLevel implements Wire {
		INFO, WARN, NEED_USER, UNKNOWN
	}

	public enum WorktreeStatus implements Wire {
		ACTIVE, MERGED, ABANDONED, UNKNOWN
	}

	public enum BackendName implements Wire {
		SIM, CLAUDE, UNKNOWN
	}

	public enum AuthStatus implements Wire {
		OK, FAILED, UNKNOWN, CHECKING
	}

	public enum DiffFileStatus implements Wire {
		ADDED, MODIFIED, DELETED, RENAMED, UNKNOWN
	}

	public enum DiffLineKind implements Wire {
		ADD, DEL, CTX, UNKNOWN
	}

	/** queued -> designing -> checking -> rendering -> done; or failed / cancelled. done, failed, cancelled are final. */
	public enum DesignStatus implements Wire {
		QUEUED, DESIGNING, CHECKING, RENDERING, DONE, FAILED, CANCELLED, UNKNOWN;

		public boolean isFinal() {
			return this == DONE || this == FAILED || this == CANCELLED;
		}

		/** Still queued or being worked on (cancellable). */
		public boolean isRunning() {
			return this == QUEUED || this == DESIGNING || this == CHECKING || this == RENDERING;
		}
	}

	/** Exact option labels (protocol.md). */
	public static final String MERGE = "Merge";
	public static final String REQUEST_CHANGES = "Request changes";
	public static final String REJECT = "Reject";
	public static final String ALLOW_ONCE = "Allow once";
	public static final String ALWAYS_ALLOW = "Always allow for this agent";
	public static final String DENY = "Deny";

	// ------------------------------------------------------------------ entities

	public record Agent(String id, String name, AgentRole role, @Nullable String title, String color, @Nullable String accent, String skin,
		AgentState state, String activity, Station station, @Nullable String taskId, @Nullable String repoId, @Nullable String worktree,
		@Nullable Boolean paused, @Nullable Boolean active) {
		public Agent {
			name = displayName(id, name);
			role = role == null ? AgentRole.UNKNOWN : role;
			color = color == null ? "#9C9488" : color;
			skin = skin == null ? id : skin;
			state = state == null ? AgentState.UNKNOWN : state;
			activity = activity == null ? "" : activity;
			station = station == null ? Station.UNKNOWN : station;
		}

		/**
		 * The name to show: the Foreman's, unless it is missing or just the id (a new lead without a cast
		 * entry yet): then the cast's name, else the id capitalised ("ines" -> "Ines").
		 */
		static @Nullable String displayName(@Nullable String id, @Nullable String name) {
			if (id == null || id.isEmpty() || name != null && !name.isBlank() && !name.equals(id)) {
				return name == null ? id : name;
			}
			dev.agentcraft.Cast.Member m = dev.agentcraft.Cast.get(id);
			return m != null ? m.name() : Character.toUpperCase(id.charAt(0)) + id.substring(1);
		}

		/** false = off shift (render idle in the lounge). Missing = true. */
		public boolean isActive() {
			return active == null || active;
		}

		public boolean isPaused() {
			return paused != null && paused;
		}
	}

	public record LogEntry(long ts, LogKind kind, String text) {
		public LogEntry {
			kind = kind == null ? LogKind.UNKNOWN : kind;
			text = text == null ? "" : text;
		}
	}

	/**
	 * The pull request a task landed as (repoSettings land "pr"). {@code status} open|changes|approved|merged|abandoned,
	 * {@code checks} pending|passing|failing|none, kept as strings (display only).
	 */
	public record TaskPr(String url, int id, @Nullable String host, @Nullable String branch, @Nullable String target, String status,
		@Nullable String checks, @Nullable PrThreads threads, long updatedAt) {
		public TaskPr {
			url = url == null ? "" : url;
			status = status == null ? "open" : status;
		}

		/** Still on the host (open, changes, approved). */
		public boolean isOpen() {
			return !status.equals("merged") && !status.equals("abandoned");
		}
	}

	public record PrThreads(int open, @com.google.gson.annotations.SerializedName("new") @Nullable Integer newCount) {
	}

	public record Task(String id, String title, @Nullable String description, TaskStatus status, @Nullable String assignee, List<String> deps,
		@Nullable String repoId, @Nullable String goalId, int priority, @Nullable String branch, @Nullable String worktree, CiStatus ci,
		@Nullable String blockedReason, @Nullable String summary, @Nullable String createdBy, long createdAt, long updatedAt, @Nullable TaskPr pr) {
		public Task {
			title = title == null ? id : title;
			status = status == null ? TaskStatus.UNKNOWN : status;
			deps = deps == null ? List.of() : List.copyOf(deps);
			ci = ci == null ? CiStatus.UNKNOWN : ci;
		}
	}

	public record DecisionAnswer(@Nullable String option, @Nullable String text, long ts) {
	}

	public record Decision(String id, String agentId, DecisionKind kind, String question, List<String> options, @Nullable String context,
		DecisionStatus status, @Nullable DecisionAnswer answer, @Nullable String taskId, @Nullable String repoId, @Nullable String worktree,
		@Nullable String tool, long createdAt, @Nullable String goalId) {
		public Decision {
			kind = kind == null ? DecisionKind.UNKNOWN : kind;
			question = question == null ? "" : question;
			options = options == null ? List.of() : List.copyOf(options);
			status = status == null ? DecisionStatus.UNKNOWN : status;
		}

		public boolean isOpen() {
			return status == DecisionStatus.OPEN;
		}
	}

	public record Worktree(String id, String agentId, @Nullable String taskId, String branch, String base, String path, WorktreeStatus status,
		int ahead, int files, int additions, int deletions) {
		public Worktree {
			status = status == null ? WorktreeStatus.UNKNOWN : status;
		}
	}

	/** {@code pr} options of a repo's settings (read-only view). */
	public record RepoPrSettings(@Nullable String remote, @Nullable String branchPrefix, @Nullable Boolean draft, @Nullable Boolean squash) {
	}

	/** PR review defaults of a repo's settings. */
	public record RepoPrReview(List<String> autoSeverities, @Nullable Integer maxRounds) {
		public RepoPrReview {
			autoSeverities = autoSeverities == null ? List.of() : List.copyOf(autoSeverities);
		}
	}

	/**
	 * Read-only view of a repo's {@code repoSettings} (docs/HUB.md "Repos and Goals tabs"): no secrets, env as
	 * its keys only. {@code land} merge|pr. Absent on a Foreman from before the Repos tab.
	 */
	public record RepoSettingsView(String land, @Nullable String baseBranch, @Nullable String ci, @Nullable String setup, @Nullable RepoPrSettings pr,
		List<String> protect, java.util.Map<String, String> roles, @Nullable String subagents, @Nullable RepoPrReview prReview, List<String> envKeys) {
		public RepoSettingsView {
			land = land == null ? "merge" : land;
			protect = protect == null ? List.of() : List.copyOf(protect);
			roles = roles == null ? java.util.Map.of() : java.util.Map.copyOf(roles);
			envKeys = envKeys == null ? List.of() : List.copyOf(envKeys);
		}
	}

	public record Repo(String id, String name, String path, String branch, @Nullable String head, boolean dirty, List<Worktree> worktrees,
		CiStatus ci, @Nullable RepoSettingsView settings) {
		public Repo {
			name = name == null ? id : name;
			worktrees = worktrees == null ? List.of() : List.copyOf(worktrees);
			ci = ci == null ? CiStatus.UNKNOWN : ci;
		}
	}

	public record MemoryEntry(String id, String scope, String title, String body, long updated, @Nullable String author) {
		public MemoryEntry {
			scope = scope == null ? "shared" : scope;
			title = title == null ? id : title;
			body = body == null ? "" : body;
		}
	}

	/** One PR of a goal's tasks ({@code Goal.prs}). */
	public record GoalPr(@Nullable String taskId, @Nullable String url, int id, @Nullable String status) {
	}

	/**
	 * {@code leadId}: the lead that plans and reviews the goal (absent = marlow). Since the Repos/Goals tabs:
	 * {@code instructions} (standing instructions), {@code planId} (memory id of its plan note), {@code branch}
	 * ("on &lt;branch&gt;:"), {@code prs} (its tasks' PRs) and {@code repos} (every repo it touches, repoId first);
	 * all null on an older Foreman.
	 */
	public record Goal(String id, String text, double progress, GoalStatus status, @Nullable String repoId, long createdAt, long updatedAt,
		@Nullable String leadId, @Nullable List<String> instructions, @Nullable String planId, @Nullable String branch, @Nullable List<GoalPr> prs,
		@Nullable List<String> repos) {
		public Goal {
			text = text == null ? "" : text;
			status = status == null ? GoalStatus.UNKNOWN : status;
			instructions = instructions == null ? null : List.copyOf(instructions);
			prs = prs == null ? null : List.copyOf(prs);
			repos = repos == null ? null : List.copyOf(repos);
		}

		/** Every repo the goal touches: {@code repos} when the Foreman sends it, else just {@code repoId}. */
		public List<String> allRepos() {
			if (repos != null && !repos.isEmpty()) {
				return repos;
			}
			return repoId == null ? List.of() : List.of(repoId);
		}

		/** Whether the goal is still being planned or worked on. */
		public boolean isOpen() {
			return status == GoalStatus.PLANNING || status == GoalStatus.ACTIVE;
		}

		/** The goal's lead: {@code leadId}, else marlow. */
		public String lead() {
			return leadId == null || leadId.isBlank() ? "marlow" : leadId;
		}
	}

	/**
	 * Which lead leads which building (docs/PRWATCH.md "A lead per building"). {@code building} is the mod's
	 * key {@code "<worldId>/<buildingId>"}; marlow is listed without one.
	 */
	public record LeadAssignment(String leadId, @Nullable String building, List<String> repos) {
		public LeadAssignment {
			repos = repos == null ? List.of() : List.copyOf(repos);
		}
	}

	/** {@code goalId}: set when the item is about a goal (Foreman with the Goals tab). */
	public record FeedItem(long ts, FeedKind kind, String text, @Nullable String agentId, @Nullable String to, @Nullable String goalId) {
		public FeedItem {
			kind = kind == null ? FeedKind.UNKNOWN : kind;
			text = text == null ? "" : text;
		}
	}

	/** One plan usage window (claude.ai login), e.g. 5h at 42%. */
	public record UsageWindow(String id, String label, double pct, @Nullable Long resetsAt) {
		public UsageWindow {
			id = id == null ? "" : id;
			label = label == null ? id : label;
		}
	}

	public record PlanUsage(List<UsageWindow> windows, long updatedAt) {
		public PlanUsage {
			windows = windows == null ? List.of() : List.copyOf(windows);
		}
	}

	public record ForemanStatus(String version, BackendName backend, AuthStatus auth, @Nullable String message, @Nullable String account,
		@Nullable Double speed, @Nullable Boolean showcase, @Nullable Double costUsd, @Nullable String userName, @Nullable PlanUsage usage) {
		public ForemanStatus {
			version = version == null ? "?" : version;
			backend = backend == null ? BackendName.UNKNOWN : backend;
			auth = auth == null ? AuthStatus.UNKNOWN : auth;
		}
	}

	public record AgentLogs(String agentId, List<LogEntry> entries) {
		public AgentLogs {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}
	}

	public record DiffLine(DiffLineKind kind, String text, @Nullable Integer oldNo, @Nullable Integer newNo) {
		public DiffLine {
			kind = kind == null ? DiffLineKind.UNKNOWN : kind;
			text = text == null ? "" : text;
		}
	}

	public record DiffHunk(String header, int oldStart, int oldLines, int newStart, int newLines, List<DiffLine> lines) {
		public DiffHunk {
			header = header == null ? "" : header;
			lines = lines == null ? List.of() : List.copyOf(lines);
		}
	}

	public record DiffFile(String path, @Nullable String oldPath, DiffFileStatus status, boolean binary, int additions, int deletions,
		List<DiffHunk> hunks) {
		public DiffFile {
			status = status == null ? DiffFileStatus.UNKNOWN : status;
			hunks = hunks == null ? List.of() : List.copyOf(hunks);
		}
	}

	public record DiffStats(int files, int additions, int deletions) {
	}

	/** A template size or size limit in blocks. */
	public record Size3(int x, int y, int z) {
	}

	/**
	 * A building design request (hub: Buildings -> Design new). {@code kind} single|group, {@code style}
	 * modern|cabin|townhouse|workshop|campus|custom, {@code materials} agentcraft|vanilla, {@code features}
	 * porch|skylights|courtyard|big_windows|garden; limits in {@code dev.agentcraft.building.DesignLimits}.
	 * Kept as strings (not enums) so a request always echoes back exactly as sent.
	 */
	public record DesignRequest(String kind, int wings, String style, String materials, List<String> features, Size3 maxSize,
		@Nullable String remix, @Nullable String name, @Nullable String notes, String outDir) {
		public DesignRequest {
			kind = kind == null ? "single" : kind;
			style = style == null ? "" : style;
			materials = materials == null ? "agentcraft" : materials;
			features = features == null ? List.of() : List.copyOf(features);
			maxSize = maxSize == null ? new Size3(0, 0, 0) : maxSize;
			outDir = outDir == null ? "" : outDir;
		}
	}

	/** A building design job (Foreman): status, one line of progress, and when done the new blueprint. */
	public record Design(String id, DesignRequest request, DesignStatus status, String step, @Nullable String blueprintId, @Nullable Size3 size,
		List<String> previews, @Nullable String error, long createdAt, long updatedAt) {
		public Design {
			status = status == null ? DesignStatus.UNKNOWN : status;
			step = step == null ? "" : step;
			previews = previews == null ? List.of() : List.copyOf(previews);
		}
	}

	/**
	 * One line of a {@code goal.digest} ack. {@code kind} task_done|task_blocked|task_added|decision_waiting|
	 * decision_answered|merged|pr_opened|pr_merged|pr_comments|message|goal_done (kept as a string).
	 */
	public record DigestLine(long ts, String kind, String text, @Nullable String taskId, @Nullable String agentId) {
		public DigestLine {
			kind = kind == null ? "message" : kind;
			text = text == null ? "" : text;
		}
	}

	/** A goal's part of a digest. */
	public record GoalDigest(String goalId, @Nullable String text, @Nullable GoalStatus status, double progress, List<DigestLine> lines) {
		public GoalDigest {
			lines = lines == null ? List.of() : List.copyOf(lines);
		}
	}

	/** {@code goal.digest} ack result: what happened between {@code since} and {@code until}. */
	public record Digest(long since, long until, List<GoalDigest> goals) {
		public Digest {
			goals = goals == null ? List.of() : List.copyOf(goals);
		}
	}

	// ------------------------------------------------------------------ Foreman -> mod messages

	public record Snapshot(ForemanStatus foreman, List<Agent> agents, List<Task> tasks, List<Decision> decisions, List<Repo> repos,
		List<MemoryEntry> memory, @Nullable Goal goal, List<Goal> goals, List<FeedItem> feed, List<AgentLogs> logs, List<Design> designs,
		@Nullable List<LeadAssignment> leads) {
		/** {@code leads} stays null when the Foreman does not send it (a Foreman from before leads per building). */
		public Snapshot {
			designs = designs == null ? List.of() : List.copyOf(designs);
			agents = agents == null ? List.of() : List.copyOf(agents);
			tasks = tasks == null ? List.of() : List.copyOf(tasks);
			decisions = decisions == null ? List.of() : List.copyOf(decisions);
			repos = repos == null ? List.of() : List.copyOf(repos);
			memory = memory == null ? List.of() : List.copyOf(memory);
			goals = goals == null ? List.of() : List.copyOf(goals);
			feed = feed == null ? List.of() : List.copyOf(feed);
			logs = logs == null ? List.of() : List.copyOf(logs);
		}
	}

	public record AgentUpsert(Agent agent) {
	}

	public record AgentLog(String agentId, List<LogEntry> entries) {
		public AgentLog {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}
	}

	public record AgentSay(String agentId, String text, @Nullable String to, long ts) {
		public AgentSay {
			text = text == null ? "" : text;
		}
	}

	public record TaskUpsert(Task task) {
	}

	public record DecisionUpsert(Decision decision) {
	}

	public record RepoUpsert(Repo repo) {
	}

	public record MemoryUpsert(MemoryEntry entry) {
	}

	public record GoalUpsert(Goal goal) {
	}

	public record FeedAdd(FeedItem item) {
	}

	public record DesignUpsert(Design design) {
	}

	/** {@code leads.update}: the full list of lead assignments. */
	public record LeadsUpdate(List<LeadAssignment> leads) {
		public LeadsUpdate {
			leads = leads == null ? List.of() : List.copyOf(leads);
		}
	}

	public record Diff(String requestId, String repoId, String worktree, @Nullable String base, @Nullable String branch, List<DiffFile> files,
		DiffStats stats, boolean truncated, @Nullable String error) {
		public Diff {
			files = files == null ? List.of() : List.copyOf(files);
			stats = stats == null ? new DiffStats(0, 0, 0) : stats;
		}
	}

	public record Notify(NotifyLevel level, String text, @Nullable String decisionId, long ts) {
		public Notify {
			level = level == null ? NotifyLevel.UNKNOWN : level;
			text = text == null ? "" : text;
		}
	}

	public record ForemanStatusMsg(ForemanStatus status) {
	}

	/** Reply to a client message with an id. {@code result} e.g. {goalId} for goal.submit. */
	public record Ack(String re, boolean ok, @Nullable String error, @Nullable JsonObject result) {
	}

	public record ErrorMsg(String message, @Nullable String re) {
	}
}
