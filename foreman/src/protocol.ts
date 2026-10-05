// AgentCraft wire protocol, version 1.
//
// This file is the single source of truth. docs/protocol.md is GENERATED from it
// (`npm run gen:protocol-doc`), and so is the mod's Java mirror
// (mod/src/client/java/dev/agentcraft/client/foreman/Protocol.java, `npm run gen:java-protocol`). Every message is one JSON object per
// WebSocket text frame:  { "v": 1, "type": "<type>", "id"?: "<client correlation id>", ...payload }
//
// Conventions
//  - timestamps (`ts`, `createdAt`, `updated`, ...) are integer epoch milliseconds
//  - colors are "#RRGGBB"
//  - unknown fields must be ignored by receivers (forward compatibility); zod strips them here
//  - optional fields are omitted (never null)
import { z } from 'zod';

export const PROTOCOL_VERSION = 1 as const;

// ---------------------------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------------------------

export const AgentState = z
  .enum(['idle', 'thinking', 'reading', 'editing', 'running', 'testing', 'waiting_user', 'blocked', 'done', 'error'])
  .describe('What the agent is doing right now; drives nameplate dot color, particles and animation.');
export type AgentState = z.infer<typeof AgentState>;

export const Station = z
  .enum(['desk', 'library', 'terminal', 'testbench', 'mergestation', 'meeting', 'lounge', 'user'])
  .describe('Where in the HQ the agent should walk to. `user` = next to the player / Decision Podium.');
export type Station = z.infer<typeof Station>;

export const AgentRole = z.enum(['lead', 'worker']);
export type AgentRole = z.infer<typeof AgentRole>;

export const TaskStatus = z
  .enum(['todo', 'doing', 'review', 'pr', 'done', 'blocked', 'cancelled'])
  .describe('Task Wall column. `pr` = landed as a pull request that is still open (the Foreman watches it; see Task.pr): shown like review, labelled "PR open"; it becomes `done` when the PR is merged, `cancelled` when it is abandoned. `cancelled` tasks are kept for history but should not be shown on the wall.');
export type TaskStatus = z.infer<typeof TaskStatus>;

export const CiStatus = z.enum(['unknown', 'running', 'pass', 'fail']);
export type CiStatus = z.infer<typeof CiStatus>;

export const LogKind = z.enum(['text', 'tool', 'result', 'error', 'diff']);
export type LogKind = z.infer<typeof LogKind>;

export const DecisionKind = z.enum(['question', 'permission', 'merge']);
export type DecisionKind = z.infer<typeof DecisionKind>;

export const DecisionStatus = z
  .enum(['open', 'answered', 'cancelled'])
  .describe('`cancelled` = became moot (agent stopped, task cancelled); render like answered.');
export type DecisionStatus = z.infer<typeof DecisionStatus>;

export const GoalStatus = z.enum(['planning', 'active', 'done', 'failed', 'cancelled']);
export type GoalStatus = z.infer<typeof GoalStatus>;

export const FeedKind = z.enum(['goal', 'plan', 'task', 'message', 'decision', 'merge', 'ci', 'memory', 'system', 'error', 'user']);
export type FeedKind = z.infer<typeof FeedKind>;

export const NotifyLevel = z.enum(['info', 'warn', 'need_user']);
export type NotifyLevel = z.infer<typeof NotifyLevel>;

export const WorktreeStatus = z.enum(['active', 'merged', 'abandoned']);
export type WorktreeStatus = z.infer<typeof WorktreeStatus>;

export const BackendName = z.enum(['sim', 'claude']);
export type BackendName = z.infer<typeof BackendName>;

export const AuthStatus = z
  .enum(['ok', 'failed', 'unknown', 'checking'])
  .describe('`failed` must be shown loudly (in-world banner): the claude backend cannot run.');
export type AuthStatus = z.infer<typeof AuthStatus>;

export const DesignStatus = z
  .enum(['queued', 'designing', 'checking', 'rendering', 'done', 'failed', 'cancelled'])
  .describe('queued -> designing (the design agent works) -> checking (the Foreman re-runs the checker) -> rendering (previews) -> done; or failed / cancelled. done, failed and cancelled are final.');
export type DesignStatus = z.infer<typeof DesignStatus>;

export const DesignStyle = z
  .enum(['modern', 'cabin', 'townhouse', 'workshop', 'campus', 'custom'])
  .describe('style preset of a generated building (docs/HUB.md); `custom` = described only by the notes');
export type DesignStyle = z.infer<typeof DesignStyle>;

export const DesignFeature = z.enum(['porch', 'skylights', 'courtyard', 'big_windows', 'garden']);
export type DesignFeature = z.infer<typeof DesignFeature>;

const Id = z.string().min(1);
/** Epoch milliseconds. Exported so generators can tell timestamps (Java `long`) from other integers. */
export const Ts = z.number().int().nonnegative().describe('epoch milliseconds');
const HexColor = z.string().regex(/^#[0-9A-Fa-f]{6}$/).describe('"#RRGGBB"');

// ---------------------------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------------------------

export const Agent = z.object({
  id: Id.describe('stable lowercase id, e.g. "kit"'),
  name: z.string().describe('display name, e.g. "Kit"'),
  role: AgentRole,
  title: z.string().optional().describe('short descriptive role from cast.json, e.g. "Backend tinkerer"'),
  color: HexColor.describe('agent color (scarf/badge/nameplate)'),
  accent: HexColor.optional(),
  skin: z.string().describe('skin id -> assets/agentcraft_worlds/textures/entity/agent/<skin>.png'),
  state: AgentState,
  activity: z.string().describe('one short line for the nameplate, e.g. "editing src/cli.ts" (<= 48 chars)'),
  station: Station,
  taskId: Id.optional(),
  repoId: Id.optional(),
  worktree: Id.optional().describe('id of the worktree the agent is working in (see Repo.worktrees)'),
  paused: z.boolean(),
  active: z.boolean().describe('false = off shift (not on the current team, or stopped by the user); render idle in the lounge'),
});
export type Agent = z.infer<typeof Agent>;

export const LogEntry = z.object({
  ts: Ts,
  kind: LogKind,
  text: z.string().describe('may contain newlines; `diff` entries use unified +/- line prefixes'),
});
export type LogEntry = z.infer<typeof LogEntry>;

export const PrStatus = z
  .enum(['open', 'changes', 'approved', 'merged', 'abandoned'])
  .describe('open: waiting for reviews; changes: a reviewer asked for changes (vote -5/-10, GitHub CHANGES_REQUESTED); approved: approved and nobody objects; merged / abandoned: closed on the host');
export type PrStatus = z.infer<typeof PrStatus>;

export const PrChecks = z
  .enum(['pending', 'passing', 'failing', 'none'])
  .describe('build / status checks on the PR (Azure DevOps build policies, GitHub status checks); none = the PR has no checks');
export type PrChecks = z.infer<typeof PrChecks>;

export const TaskPr = z.object({
  url: z.string().describe('the PR\'s web URL'),
  id: z.number().int().describe('PR number on its host'),
  host: z.enum(['ado', 'github']),
  branch: z.string().describe('source branch on the remote'),
  target: z.string().describe('target branch on the remote'),
  status: PrStatus,
  checks: PrChecks,
  threads: z.object({
    open: z.number().int().nonnegative().describe('unresolved comment threads (system threads not counted)'),
    new: z.number().int().nonnegative().describe('threads with comments the lead has not triaged yet'),
  }),
  updatedAt: Ts.describe('last successful poll of the host'),
});
export type TaskPr = z.infer<typeof TaskPr>;

export const Task = z.object({
  id: Id.describe('e.g. "t3"'),
  title: z.string(),
  description: z.string().optional(),
  status: TaskStatus,
  assignee: Id.optional().describe('agent id'),
  deps: z.array(Id).describe('task ids that must be done before this one can start'),
  repoId: Id.optional(),
  goalId: Id.optional(),
  priority: z.number().int().describe('higher = sooner; default 0'),
  branch: z.string().optional().describe('git branch, e.g. "agentcraft/kit/t2-tag-parser"'),
  worktree: Id.optional(),
  ci: CiStatus,
  blockedReason: z.string().optional(),
  summary: z.string().optional().describe('worker/lead summary of the result'),
  pr: TaskPr.optional().describe('the pull request this task landed as (repoSettings land "pr"), while it is watched and after it closed'),
  createdBy: Id.describe('agent id or "user"'),
  createdAt: Ts,
  updatedAt: Ts,
});
export type Task = z.infer<typeof Task>;

export const DecisionAnswer = z.object({
  option: z.string().optional().describe('the chosen option label (one of Decision.options)'),
  text: z.string().optional().describe('free-text answer / feedback'),
  ts: Ts,
});
export type DecisionAnswer = z.infer<typeof DecisionAnswer>;

export const Decision = z.object({
  id: Id.describe('e.g. "d4"'),
  agentId: Id.describe('agent waiting on this decision'),
  kind: DecisionKind,
  question: z.string(),
  options: z.array(z.string()).describe('button labels; first is the default/recommended choice'),
  context: z
    .string()
    .optional()
    .describe('extra detail, multi-line plain text. permission: why it asks, the cwd, and a line `"Always allow for this agent" covers: ...` (the scope of that choice). merge: summary + diff stat; after a refused merge the decision is open again and this ends with `Merge refused: <reason>`'),
  status: DecisionStatus,
  answer: DecisionAnswer.optional(),
  taskId: Id.optional(),
  repoId: Id.optional().describe('merge decisions: repo to request the diff from'),
  worktree: Id.optional().describe('merge decisions: worktree to request the diff for'),
  tool: z.string().optional().describe('permission decisions: tool name, e.g. "Bash"'),
  goalId: Id.optional().describe('the goal this decision is about (its task\'s goal, or the goal of the lead turn that asked); absent on older decisions and ones not tied to a goal'),
  textAllowed: z.boolean().optional().describe('false: only the options make sense (e.g. PR "Post"/"Skip", "Fold in"/"Leave it"): hide or disable free text; a `decision.answer` without a valid option is refused. Absent = true'),
  createdAt: Ts,
});
export type Decision = z.infer<typeof Decision>;

export const Worktree = z.object({
  id: Id.describe('e.g. "kit-t2" — use as `worktree` in diff.request'),
  agentId: Id,
  taskId: Id.optional(),
  branch: z.string(),
  base: z.string().describe('branch it was created from / will merge into'),
  path: z.string(),
  status: WorktreeStatus,
  ahead: z.number().int().nonnegative().describe('commits on branch not on base'),
  files: z.number().int().nonnegative().describe('files changed vs base (incl. uncommitted)'),
  additions: z.number().int().nonnegative(),
  deletions: z.number().int().nonnegative(),
});
export type Worktree = z.infer<typeof Worktree>;

const BRANCH_RE = /^[\w./-]+$/;

export const RepoSettingsView = z.object({
  land: z.enum(['merge', 'pr']).describe('how approved work lands (repoSettings.land, default "merge")'),
  baseBranch: z.string().optional().describe('configured base branch (repoSettings.baseBranch)'),
  ci: z.string().optional().describe('configured test command (repoSettings.ci); absent = --ci or detected'),
  setup: z.string().optional().describe('worktree setup command (repoSettings.setup)'),
  pr: z
    .object({ remote: z.string().optional(), branchPrefix: z.string().optional(), draft: z.boolean().optional(), squash: z.boolean().optional() })
    .optional()
    .describe('pull request options (repoSettings.pr)'),
  protect: z.array(z.string()).describe('paths never committed (repoSettings.protect)'),
  roles: z.record(z.string(), z.string()).describe('agent id -> the repository agent file that is its role here'),
  subagents: z.string().optional().describe('"repo": the repository\'s .claude/agents files are usable as subagents'),
  prReview: z.object({ autoSeverities: z.array(z.string()), maxRounds: z.number().int() }).optional().describe('PR review triage (configured values, else the defaults)'),
  envKeys: z.array(z.string()).optional().describe('names of the repoSettings.env variables (values are never sent)'),
});
export type RepoSettingsView = z.infer<typeof RepoSettingsView>;

export const Repo = z.object({
  id: Id.describe('e.g. "demo-app"'),
  name: z.string(),
  path: z.string().describe('absolute path of the user checkout'),
  branch: z.string().describe('base branch agents branch from and merge into'),
  head: z.string().optional().describe('short sha of base branch'),
  dirty: z.boolean().describe('user checkout has uncommitted tracked changes (merges are refused while dirty)'),
  worktrees: z.array(Worktree),
  ci: CiStatus.describe('latest CI/test result across this repo'),
  settings: RepoSettingsView.optional().describe('read-only view of the repository\'s config.json repoSettings (no secrets: env shows its keys only)'),
});
export type Repo = z.infer<typeof Repo>;

export const MemoryEntry = z.object({
  id: Id.describe('"shared/<slug>" or "<agentId>/<slug>"'),
  scope: z.string().describe('"shared" or an agent id'),
  title: z.string(),
  body: z.string().describe('markdown'),
  updated: Ts,
  author: Id.optional().describe('agent id or "user" that last wrote it'),
});
export type MemoryEntry = z.infer<typeof MemoryEntry>;

export const GoalPr = z.object({
  taskId: Id,
  url: z.string(),
  id: z.number().int().describe('PR number on its host'),
  status: PrStatus,
});
export type GoalPr = z.infer<typeof GoalPr>;

export const Goal = z.object({
  id: Id.describe('e.g. "g1"'),
  text: z.string(),
  progress: z.number().min(0).max(1),
  status: GoalStatus.describe('planning (lead is planning) -> active -> done (every non-cancelled task merged/done); cancelled: every task was cancelled or rejected (back to active if the lead adds a task); failed: planning failed'),
  repoId: Id.optional(),
  leadId: Id.optional().describe('the lead running this goal (set at submit from the goal\'s repository: the lead of the building that has it). Absent = "marlow". Changes only while the goal is open: when its lead is released (lead.release / lead.sync / lead.releaseWorld) marlow takes it over; when a building is assigned its repository (lead.assign / lead.sync) that building\'s lead adopts it'),
  repos: z.array(Id).optional().describe('every repository the goal touches: repoId first, then each task\'s repository in order of first appearance (kept up to date)'),
  instructions: z.array(z.string()).optional().describe('standing instructions (goal.instructions): in the lead\'s prompts, appended to new task descriptions, and a section of every worker prompt for its tasks'),
  planId: Id.optional().describe('memory entry id of the goal\'s plan note, once it exists'),
  branch: z.string().optional().describe('the user\'s branch the goal continues ("on <branch>:" prefix or goal.submit branch)'),
  prs: z.array(GoalPr).optional().describe('its tasks\' pull requests (tasks with Task.pr), in task order'),
  createdAt: Ts,
  updatedAt: Ts,
});
export type Goal = z.infer<typeof Goal>;

/** A building's key: `"<worldId>/<buildingId>"` (worldId = the save folder name). */
const BuildingKey = z.string().regex(/^[^/]+\/.+$/).describe('"<worldId>/<buildingId>" (worldId = the save folder name, so two worlds on one Foreman do not collide)');

export const LeadAssignment = z.object({
  leadId: Id.describe('a lead agent id, e.g. "ines"'),
  building: BuildingKey.optional().describe('the building this lead leads; absent for "marlow" (home, repositories without a building, everything not tied to a repository)'),
  repos: z.array(Id).describe('repository ids of the building (a repository is in at most one building); empty for marlow'),
  world: z.string().optional().describe('the world (save folder name) the building is in; absent for marlow'),
  lastSync: Ts.optional().describe('when that world last talked to the Foreman (lead.sync / lead.assign / lead.release). Assignments of worlds not seen for `claude.leadWorldTtlDays` (default 14) are dropped'),
});
export type LeadAssignment = z.infer<typeof LeadAssignment>;

export const FeedItem = z.object({
  ts: Ts,
  kind: FeedKind,
  text: z.string(),
  agentId: Id.optional().describe('who it is about / from'),
  to: z.string().optional().describe('message recipient: agent id, "user" or "all"'),
  goalId: Id.optional().describe('the goal the item is about (its tasks, its lead\'s turns for it, its PRs, goal messages); absent on older items and ones not tied to a goal'),
});
export type FeedItem = z.infer<typeof FeedItem>;

export const DigestLineKind = z.enum(['task_done', 'task_blocked', 'task_added', 'decision_waiting', 'decision_answered', 'merged', 'pr_opened', 'pr_merged', 'pr_comments', 'message', 'goal_done']);
export type DigestLineKind = z.infer<typeof DigestLineKind>;

export const DigestLine = z.object({
  ts: Ts,
  kind: DigestLineKind,
  text: z.string(),
  taskId: Id.optional(),
  agentId: Id.optional(),
});
export type DigestLine = z.infer<typeof DigestLine>;

export const GoalDigest = z.object({
  goalId: Id,
  text: z.string().describe('the goal\'s text'),
  status: GoalStatus,
  progress: z.number().min(0).max(1),
  lines: z.array(DigestLine).describe('at most 30, oldest first (newest last); routine progress left out'),
});
export type GoalDigest = z.infer<typeof GoalDigest>;

export const Digest = z.object({
  since: Ts,
  until: Ts,
  goals: z.array(GoalDigest).describe('with goalId: that goal; without: every goal with something in the window'),
});
export type Digest = z.infer<typeof Digest>;

export const UsageWindow = z.object({
  id: z.string().describe('"five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet", "overage"'),
  label: z.string().describe('short label, e.g. "5h", "7d"'),
  pct: z.number().min(0).max(100).describe('percent of the window used'),
  resetsAt: Ts.optional().describe('when the window resets'),
});
export type UsageWindow = z.infer<typeof UsageWindow>;

export const PlanUsage = z.object({
  windows: z.array(UsageWindow).describe('shortest window first'),
  updatedAt: Ts,
});
export type PlanUsage = z.infer<typeof PlanUsage>;

export const ForemanHold = z.object({
  reason: z.enum(['usage', 'auth', 'offline']).describe('usage: a usage limit was hit, or usage is above claude.usageReserve; auth: the login / API key failed (until a restart); offline: Claude could not be reached (retried with backoff)'),
  until: Ts.optional().describe('when the hold is expected to end (usage: the window resets; offline: the next retry)'),
  message: z.string().describe('one line for a persistent HUD line'),
});
export type ForemanHold = z.infer<typeof ForemanHold>;

export const ForemanStatus = z.object({
  version: z.string(),
  backend: BackendName,
  auth: AuthStatus,
  message: z.string().optional().describe('human-readable backend/auth status for the banner'),
  account: z.string().optional().describe('e.g. organization / plan when auth ok'),
  speed: z.number().optional().describe('sim: speed multiplier'),
  showcase: z.boolean().optional().describe('sim: holding a static showcase state (`--showcase` or `--showcase late`)'),
  costUsd: z.number().optional().describe('claude: estimated spend of this profile (sum over all sessions, survives restarts)'),
  userName: z.string().optional().describe('the person the team works for, as the agents address them (UI: "<name> answered")'),
  usage: PlanUsage.optional().describe('claude.ai login: how much of the plan\'s usage windows is used (from the agents\' sessions)'),
  restartRequired: z.array(z.string()).optional().describe('config keys changed (config.set) that take effect only after a restart (`foreman.restart`); omitted when none'),
  hold: ForemanHold.optional().describe('claude: the backend is holding new agent turns (usage limit or reserve, auth failure, offline); absent when nothing holds them. Running turns finish; queued work starts when the hold ends'),
});
export type ForemanStatus = z.infer<typeof ForemanStatus>;

/**
 * Where generated blueprints go: the absolute `<gameDir>/agentcraft/blueprints` folder the mod reads
 * user blueprints from (Windows or POSIX), without `.`/`..` segments. Anything else is refused, so a
 * client cannot make the Foreman write elsewhere.
 */
export function blueprintOutDirError(p: string): string | undefined {
  const absolute = /^\//.test(p) || /^[A-Za-z]:[\\/]/.test(p) || /^\\\\[^\\/]+[\\/][^\\/]+/.test(p);
  if (!absolute) return 'must be an absolute path';
  const segs = p.split(/[\\/]+/).filter(Boolean);
  if (segs.some((x) => x === '.' || x === '..')) return 'must not contain . or .. segments';
  if (segs.length < 3 || segs[segs.length - 2] !== 'agentcraft' || segs[segs.length - 1] !== 'blueprints') return 'must be the <gameDir>/agentcraft/blueprints folder';
  return undefined;
}

const SizeBox = (x: [number, number], y: [number, number], z_: [number, number]) =>
  z.object({ x: z.number().int().min(x[0]).max(x[1]), y: z.number().int().min(y[0]).max(y[1]), z: z.number().int().min(z_[0]).max(z_[1]) });

export const DesignRequest = z
  .object({
    kind: z.enum(['single', 'group']).describe('single: one repo (wings must be 1); group: N repos, one wing each (wings >= 2)'),
    wings: z.number().int().min(1).max(8),
    style: DesignStyle,
    materials: z.enum(['agentcraft', 'vanilla']).describe('agentcraft: the AgentCraft look built from vanilla blocks; vanilla: any vanilla look (both: AgentCraft blocks only for the station blocks)'),
    features: z.array(DesignFeature).max(5),
    maxSize: SizeBox([9, 128], [6, 48], [9, 128]).describe('the largest template allowed (x/z 9..128, y 6..48), e.g. from a marked plot'),
    remix: z.string().regex(/^[a-z0-9_]+$/).optional().describe('start from this blueprint id (bundled or user)'),
    name: z.string().min(1).max(40).optional().describe('display name; also names the blueprint id (gen_<slug>). Default: from the style'),
    notes: z.string().max(2000).optional().describe('free text for the designer'),
    outDir: z.string().min(1).describe('absolute `<gameDir>/agentcraft/blueprints` (the mod\'s user blueprint folder); any other path is refused'),
  })
  .superRefine((r, ctx) => {
    const e = blueprintOutDirError(r.outDir);
    if (e) ctx.addIssue({ code: 'custom', path: ['outDir'], message: e });
    if (r.kind === 'single' && r.wings !== 1) ctx.addIssue({ code: 'custom', path: ['wings'], message: 'a single building has exactly 1 wing' });
    if (r.kind === 'group' && r.wings < 2) ctx.addIssue({ code: 'custom', path: ['wings'], message: 'a group building has at least 2 wings' });
    if (new Set(r.features).size !== r.features.length) ctx.addIssue({ code: 'custom', path: ['features'], message: 'duplicate feature' });
  });
export type DesignRequest = z.infer<typeof DesignRequest>;

export const Design = z.object({
  id: Id.describe('e.g. "d7" (allocated from the same counter as decision ids, so never equal to one)'),
  request: DesignRequest,
  status: DesignStatus,
  step: z.string().describe('one line of progress, e.g. "running the checker (round 2)"'),
  blueprintId: z.string().optional().describe('done: the new blueprint id in outDir (gen_<slug>, gen_<slug>_2, ...)'),
  size: z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() }).optional().describe('done: template size'),
  previews: z.array(z.string()).optional().describe('done: absolute paths of the preview PNGs in outDir (empty when no renderer is available)'),
  error: z.string().optional().describe('failed: what went wrong (tail of the checker output)'),
  createdAt: Ts,
  updatedAt: Ts,
});
export type Design = z.infer<typeof Design>;

export const SettingType = z
  .enum(['bool', 'int', 'enum', 'string', 'stringList', 'model', 'effort', 'agentList', 'map', 'secretMap', 'mcpServers'])
  .describe(
    'How a setting is edited: bool toggle, int stepper (min/max), enum chips (options), string field, string list editor, model / effort picker (options), agent list (options = agent ids; ordered for claude.leads), map (read-only name -> text), ' +
      'secretMap (a repository\'s `env`: value `["NAME", ...]`, the variable names only, never values; config.set takes a partial update `{ NAME: "value" | null }`, null removes that variable; a placeholder value ("(set)", "(hidden)", "(staged)", "[redacted]") or one with control characters other than tab / newline is refused), ' +
      'mcpServers (`claude.context.mcpServers`: value `[{ name, type: "stdio"|"http"|"sse", command?, argCount?, url?, urlHasPath?, headerKeys?, envKeys }]`: `command` is the executable only, `argCount` how many arguments there are, `url` scheme://host[:port] only (`urlHasPath`: the stored URL has a path or query); argument values and the full URL are never sent. ' +
      'config.set takes `[{ name, type, command?, args?, url?, env?: { NAME: "value" | null } } | { name, remove: true }]`, each an upsert or removal of that one server, the others unchanged: `command`, `args` and `url` left out keep the stored value exactly, sent they replace it exactly (`args`: the complete new list); a `command` / `url` equal to what config.get shows of a longer stored one is refused; the view\'s read-only fields may be sent back and are ignored; errors name places (server #2), never values)',
  );
export const SettingSource = z.enum(['file', 'flag', 'env', 'default']).describe('where the value comes from: config.json, a command-line flag, an AGENTCRAFT_* environment variable, or the built-in default');

export const SettingDef = z.object({
  key: z.string().describe('the config.json path, e.g. "claude.prWatch", "claude.agents.kit.model"; for a repository relative to its repoSettings entry, e.g. "land", "pr.draft", "roles.kit"'),
  label: z.string(),
  help: z.string().describe('one or two sentences for the user'),
  group: z.string().describe('global: team, models, general, permissions, context, subagents, prs, usage; repository: landing, worktrees, agents, review'),
  type: SettingType,
  options: z.array(z.string()).optional().describe('enum / model / effort / agentList / stringList choices (model: the Opus and Sonnet models in use and "default"; "default" clears the setting)'),
  min: z.number().optional(),
  max: z.number().optional(),
  value: z.unknown().describe('the configured value (what config.json, a flag or the environment says now; may differ from what the running Foreman uses until a restart, see restartRequired)'),
  default: z.unknown().describe('the value when nothing is configured'),
  source: SettingSource,
  live: z.boolean().describe('true: a change applies from the next turn / poll without a restart; false: after foreman.restart'),
  overriddenBy: z.string().optional().describe('the flag or variable that wins over config.json, e.g. "--lead-model" or "AGENTCRAFT_PR_WATCH": a change is written but has no effect while it is given'),
  readOnly: z.boolean().optional().describe('shown, never changed through config.set'),
});
export type SettingDef = z.infer<typeof SettingDef>;

export const AgentLogs = z.object({ agentId: Id, entries: z.array(LogEntry) });
export type AgentLogs = z.infer<typeof AgentLogs>;

export const DiffLine = z.object({
  kind: z.enum(['add', 'del', 'ctx']),
  text: z.string().describe('line content without the +/-/space prefix'),
  oldNo: z.number().int().optional().describe('line number in base (del, ctx)'),
  newNo: z.number().int().optional().describe('line number in branch (add, ctx)'),
});
export type DiffLine = z.infer<typeof DiffLine>;

export const DiffHunk = z.object({
  header: z.string().describe('e.g. "@@ -12,6 +12,9 @@ export function run"'),
  oldStart: z.number().int(),
  oldLines: z.number().int(),
  newStart: z.number().int(),
  newLines: z.number().int(),
  lines: z.array(DiffLine),
});
export type DiffHunk = z.infer<typeof DiffHunk>;

export const DiffFile = z.object({
  path: z.string(),
  oldPath: z.string().optional().describe('renames only'),
  status: z.enum(['added', 'modified', 'deleted', 'renamed']),
  binary: z.boolean(),
  additions: z.number().int().nonnegative(),
  deletions: z.number().int().nonnegative(),
  hunks: z.array(DiffHunk),
});
export type DiffFile = z.infer<typeof DiffFile>;

// ---------------------------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------------------------

const envelope = <T extends string>(type: T) => ({
  v: z.literal(PROTOCOL_VERSION),
  type: z.literal(type),
  id: z.string().optional().describe('client correlation id; the Foreman answers with `ack` {re: id}'),
});

// Foreman -> Mod ------------------------------------------------------------------------------

export const SnapshotMsg = z.object({
  ...envelope('snapshot'),
  foreman: ForemanStatus,
  agents: z.array(Agent),
  tasks: z.array(Task),
  decisions: z.array(Decision).describe('open decisions plus the most recent answered ones'),
  repos: z.array(Repo),
  memory: z.array(MemoryEntry),
  goal: Goal.optional().describe('current (latest) goal'),
  goals: z.array(Goal).describe('all goals, oldest first'),
  feed: z.array(FeedItem).describe('most recent feed items, oldest first (<= 200)'),
  logs: z.array(AgentLogs).describe('recent log tail per agent (<= 60 entries each)'),
  designs: z.array(Design).describe('the most recent building designs (<= 20), oldest first; queued/running ones always included'),
  leads: z.array(LeadAssignment).describe('who leads what: marlow first (no building), then every assigned building lead (same as `leads.update`)'),
});
export const AgentUpsertMsg = z.object({ ...envelope('agent.upsert'), agent: Agent });
export const AgentLogMsg = z.object({ ...envelope('agent.log'), agentId: Id, entries: z.array(LogEntry) });
export const AgentSayMsg = z.object({
  ...envelope('agent.say'),
  agentId: Id,
  text: z.string(),
  to: z.string().optional().describe('agent id, "user" or "all"; omitted = said to the room'),
  ts: Ts,
});
export const TaskUpsertMsg = z.object({ ...envelope('task.upsert'), task: Task });
export const DecisionUpsertMsg = z.object({ ...envelope('decision.upsert'), decision: Decision });
export const RepoUpsertMsg = z.object({ ...envelope('repo.upsert'), repo: Repo });
export const MemoryUpsertMsg = z.object({ ...envelope('memory.upsert'), entry: MemoryEntry });
export const GoalUpsertMsg = z.object({ ...envelope('goal.upsert'), goal: Goal });
export const FeedAddMsg = z.object({ ...envelope('feed.add'), item: FeedItem });
export const DiffMsg = z.object({
  ...envelope('diff'),
  requestId: z.string(),
  repoId: Id,
  worktree: Id,
  base: z.string().optional(),
  branch: z.string().optional(),
  files: z.array(DiffFile),
  stats: z.object({ files: z.number().int(), additions: z.number().int(), deletions: z.number().int() }),
  truncated: z.boolean().describe('true if very large files/hunks were cut'),
  error: z.string().optional(),
});
export const NotifyMsg = z.object({
  ...envelope('notify'),
  level: NotifyLevel,
  text: z.string(),
  decisionId: Id.optional(),
  ts: Ts,
});
export const DesignUpsertMsg = z.object({ ...envelope('design.upsert'), design: Design });
export const ForemanStatusMsg = z.object({ ...envelope('foreman.status'), status: ForemanStatus });
export const LeadsUpdateMsg = z.object({ ...envelope('leads.update'), leads: z.array(LeadAssignment).describe('the full list (replace): marlow first, then each assigned building lead') });
export const ConfigChangedMsg = z.object({
  ...envelope('config.changed'),
  keys: z.array(z.string()).describe('the keys config.set changed (repository keys as "repo:<repoId>:<key>")'),
  restartRequired: z.array(z.string()).describe('every changed key still waiting for a restart (the full list, same as foreman.status.restartRequired)'),
});
export const AckMsg = z.object({
  ...envelope('ack'),
  re: z.string().describe('the `id` of the client message being acknowledged'),
  ok: z.boolean(),
  error: z.string().optional(),
  result: z.record(z.string(), z.unknown()).optional().describe('e.g. {goalId} for goal.submit, {repoId} for repo.add'),
});
export const ErrorMsg = z.object({
  ...envelope('error'),
  message: z.string(),
  re: z.string().optional(),
});

export const ServerMessage = z.discriminatedUnion('type', [
  SnapshotMsg,
  AgentUpsertMsg,
  AgentLogMsg,
  AgentSayMsg,
  TaskUpsertMsg,
  DecisionUpsertMsg,
  RepoUpsertMsg,
  MemoryUpsertMsg,
  GoalUpsertMsg,
  FeedAddMsg,
  DiffMsg,
  NotifyMsg,
  DesignUpsertMsg,
  ForemanStatusMsg,
  LeadsUpdateMsg,
  ConfigChangedMsg,
  AckMsg,
  ErrorMsg,
]);
export type ServerMessage = z.infer<typeof ServerMessage>;

// Mod -> Foreman ------------------------------------------------------------------------------

export const HelloMsg = z.object({
  ...envelope('hello'),
  modVersion: z.string(),
  protocol: z.literal(PROTOCOL_VERSION),
  client: z.string().optional().describe('"mod" | "cli" | ... (informational)'),
  token: z.string().optional().describe('the client token: the contents of the file the run file names in `tokenFile` (`<dataDir>/client.token`, new on every Foreman start). Without a valid token the connection is read-only: snapshot and events, and only `hello`, `diff.request`, `goal.digest` and `agent.logs.request`; every other message is refused (`ack.ok` false, "read-only connection: no client token")'),
});
export const GoalSubmitMsg = z.object({
  ...envelope('goal.submit'),
  text: z.string().min(1),
  repoId: Id.optional().describe('defaults to repos[0], else the only/most recently added repo'),
  repos: z.array(Id).optional().describe('the repositories the goal is for (e.g. a group building\'s); the first becomes repoId when repoId is absent. The goal\'s lead is the lead of repoId'),
  branch: z.string().regex(BRANCH_RE).optional().describe('continue this branch of the user\'s (same as an "on <branch>:" prefix; wins over it)'),
  instructions: z.array(z.string()).optional().describe('standing instructions from the start (see goal.instructions)'),
});
export const GoalMessageMsg = z.object({ ...envelope('goal.message'), goalId: Id, text: z.string().min(1) });
export const GoalInstructionsMsg = z.object({ ...envelope('goal.instructions'), goalId: Id, instructions: z.array(z.string()).describe('the full list (replace); blank lines are dropped') });
export const GoalPlanMsg = z.object({ ...envelope('goal.plan'), goalId: Id, body: z.string().describe('the plan note\'s new markdown body') });
export const GoalCancelMsg = z.object({ ...envelope('goal.cancel'), goalId: Id });
export const GoalDigestMsg = z.object({
  ...envelope('goal.digest'),
  goalId: Id.optional().describe('one goal; omitted = every goal with activity in the window'),
  since: Ts,
});
export const RepoRemoveMsg = z.object({ ...envelope('repo.remove'), repoId: Id });
export const UserMessageMsg = z.object({
  ...envelope('user.message'),
  to: z.string().min(1).describe('agent id or "all". With "all", a leading "@name" in text routes to that agent.'),
  text: z.string().min(1),
});
export const DecisionAnswerMsg = z.object({
  ...envelope('decision.answer'),
  decisionId: Id,
  option: z
    .union([z.string(), z.number().int().nonnegative()])
    .optional()
    .describe('option label (preferred) or 0-based index into Decision.options'),
  text: z.string().optional().describe('free text (questions) or feedback (merge "Request changes")'),
});
export const TaskActionMsg = z.object({
  ...envelope('task.action'),
  taskId: Id,
  action: z.enum(['reassign', 'cancel', 'retry', 'prioritize']),
  arg: z.string().optional().describe('reassign: agent id; prioritize: integer priority (default: bump to top)'),
});
export const AgentActionMsg = z.object({
  ...envelope('agent.action'),
  agentId: Id,
  action: z
    .enum(['pause', 'resume', 'stop', 'spawn'])
    .describe(
      'pause: abort the current turn, keep the task (open questions are withdrawn); resume continues it. stop: off shift (active=false) until resume/spawn: turn aborted, open questions/permission prompts withdrawn, its doing tasks go back to the board and the next worker continues from the same branch. spawn: bring an off-shift agent onto the team.',
    ),
  arg: z.string().optional().describe('spawn: optional task id to assign to the agent'),
});
export const DiffRequestMsg = z.object({
  ...envelope('diff.request'),
  requestId: z.string().min(1),
  repoId: Id,
  worktree: Id.describe('worktree id (e.g. "kit-t2"); an agent id resolves to that agent\'s current worktree'),
});
export const RepoAddMsg = z.object({ ...envelope('repo.add'), path: z.string().min(1) });
export const DesignRequestMsg = z.object({ ...envelope('design.request'), request: DesignRequest });
export const DesignCancelMsg = z.object({ ...envelope('design.cancel'), designId: Id });
export const LeadAssignMsg = z.object({
  ...envelope('lead.assign'),
  building: BuildingKey,
  repos: z.array(Id).describe('repository ids the building holds'),
});
export const LeadReleaseMsg = z.object({ ...envelope('lead.release'), building: BuildingKey });
export const LeadReleaseWorldMsg = z.object({ ...envelope('lead.releaseWorld'), world: z.string().min(1).regex(/^[^/]+$/).describe('the world id (save folder name) whose leads to release') });
export const LeadSyncMsg = z.object({
  ...envelope('lead.sync'),
  world: z.string().min(1).regex(/^[^/]+$/).describe('the world id (save folder name)'),
  buildings: z.array(z.object({ building: BuildingKey, repos: z.array(Id) })).describe('every building of that world that holds repositories'),
});
export const PrRefreshMsg = z.object({
  ...envelope('pr.refresh'),
  taskId: Id.optional().describe('the task whose PR to poll now; omitted = every task in status `pr`'),
});

export const ConfigGetMsg = z.object({ ...envelope('config.get'), repoId: Id.optional().describe('that repository\'s repoSettings instead of the global settings') });
export const ConfigSetMsg = z.object({
  ...envelope('config.set'),
  repoId: Id.optional().describe('change that repository\'s repoSettings'),
  changes: z
    .array(z.object({ key: z.string().min(1), value: z.unknown().describe('the new value; null (or "default" for a model / per-agent effort) removes the key from config.json, back to the default; for a secretMap or mcpServers setting a partial update (see SettingType)') }))
    .min(1),
});
export const ForemanRestartMsg = z.object({ ...envelope('foreman.restart') });
export const RepoAgentsMsg = z.object({ ...envelope('repo.agents'), repoId: Id });
export const AgentLogsRequestMsg = z.object({
  ...envelope('agent.logs.request'),
  agentId: Id,
  before: Ts.optional().describe('only entries older than this (the `ts` of the oldest entry the client has); omitted = the newest'),
  limit: z.number().int().min(1).max(500).optional().describe('at most this many entries (default 200); entries sharing the oldest one\'s `ts` are never split, so a page can be slightly longer'),
});

export const ClientMessage = z.discriminatedUnion('type', [
  HelloMsg,
  GoalSubmitMsg,
  UserMessageMsg,
  DecisionAnswerMsg,
  TaskActionMsg,
  AgentActionMsg,
  DiffRequestMsg,
  RepoAddMsg,
  DesignRequestMsg,
  DesignCancelMsg,
  PrRefreshMsg,
  LeadAssignMsg,
  LeadReleaseMsg,
  LeadSyncMsg,
  LeadReleaseWorldMsg,
  GoalMessageMsg,
  GoalInstructionsMsg,
  GoalPlanMsg,
  GoalCancelMsg,
  GoalDigestMsg,
  RepoRemoveMsg,
  ConfigGetMsg,
  ConfigSetMsg,
  ForemanRestartMsg,
  RepoAgentsMsg,
  AgentLogsRequestMsg,
]);
export type ClientMessage = z.infer<typeof ClientMessage>;

// Helper types ----------------------------------------------------------------------------------

/** A server message without the envelope's `v` (added by the sender). */
export type Outbound = ServerMessage extends infer M ? (M extends { v: 1 } ? Omit<M, 'v'> : never) : never;
export type ServerMessageOf<T extends ServerMessage['type']> = Extract<ServerMessage, { type: T }>;
export type ClientMessageOf<T extends ClientMessage['type']> = Extract<ClientMessage, { type: T }>;

export function parseClientMessage(raw: unknown): { ok: true; msg: ClientMessage } | { ok: false; error: string } {
  let data = raw;
  if (typeof raw === 'string') {
    try {
      data = JSON.parse(raw);
    } catch {
      return { ok: false, error: 'invalid JSON' };
    }
  }
  const r = ClientMessage.safeParse(data);
  if (r.success) return { ok: true, msg: r.data };
  return { ok: false, error: formatZodError(r.error) };
}

export function parseServerMessage(raw: unknown): { ok: true; msg: ServerMessage } | { ok: false; error: string } {
  let data = raw;
  if (typeof raw === 'string') {
    try {
      data = JSON.parse(raw);
    } catch {
      return { ok: false, error: 'invalid JSON' };
    }
  }
  const r = ServerMessage.safeParse(data);
  if (r.success) return { ok: true, msg: r.data };
  return { ok: false, error: formatZodError(r.error) };
}

export function formatZodError(err: z.ZodError): string {
  return err.issues
    .slice(0, 5)
    .map((i) => `${i.path.join('.') || '(root)'}: ${i.message}`)
    .join('; ');
}

/** Registry used by the doc generator and tests. Order = documentation order. */
export const SERVER_MESSAGES = {
  snapshot: { schema: SnapshotMsg, doc: 'Full state. Sent in reply to every `hello`; the mod rebuilds its view from it.' },
  'agent.upsert': { schema: AgentUpsertMsg, doc: 'An agent was created or changed (state, station, activity, task...). Replace by `agent.id`.' },
  'agent.log': { schema: AgentLogMsg, doc: 'New log lines for an agent monitor (append; keep a bounded tail).' },
  'agent.say': { schema: AgentSayMsg, doc: 'Speech bubble above the agent; also mirrored to the feed.' },
  'task.upsert': { schema: TaskUpsertMsg, doc: 'Task created or changed. Replace by `task.id`.' },
  'decision.upsert': { schema: DecisionUpsertMsg, doc: 'Decision opened, answered or cancelled. Replace by `decision.id`.' },
  'repo.upsert': { schema: RepoUpsertMsg, doc: 'Repo added or changed (worktrees, CI, head, dirty). Replace by `repo.id`.' },
  'memory.upsert': { schema: MemoryUpsertMsg, doc: 'Memory entry written. Replace by `entry.id`.' },
  'goal.upsert': { schema: GoalUpsertMsg, doc: 'Goal created or progress/status changed. Replace by `goal.id`; latest goal is current.' },
  'feed.add': { schema: FeedAddMsg, doc: 'Append to the activity feed.' },
  diff: { schema: DiffMsg, doc: 'Reply to `diff.request` (sent only to the requesting client). Structured unified diff of worktree vs base, including uncommitted changes.' },
  notify: { schema: NotifyMsg, doc: 'Toast/banner for the player. `need_user` = a decision is waiting (play a bell).' },
  'design.upsert': { schema: DesignUpsertMsg, doc: 'A building design was requested or progressed (status, step) or finished. Replace by `design.id`. On `done` the blueprint files are already in `request.outDir`: reload blueprints.' },
  'foreman.status': { schema: ForemanStatusMsg, doc: 'Backend/auth status changed (banner).' },
  'leads.update': { schema: LeadsUpdateMsg, doc: 'Lead assignments changed (lead.assign / lead.release / lead.sync, or the Foreman dropped a lead that is no longer configured). Full list; also in `snapshot.leads`. A lead agent (role `lead`) other than marlow is in `snapshot.agents` and gets `agent.upsert` only while it is assigned; a released lead gets one last `agent.upsert` (active=false, lounge) and should walk home and despawn.' },
  'config.changed': { schema: ConfigChangedMsg, doc: 'config.set changed config.json (any client). Clients showing settings fetch them again (`config.get`).' },
  ack: { schema: AckMsg, doc: 'Reply to any client message that carried an `id`.' },
  error: { schema: ErrorMsg, doc: 'A client message was invalid or failed (also sent as ack.ok=false when it had an id).' },
} as const;

export const CLIENT_MESSAGES = {
  hello: { schema: HelloMsg, doc: 'First message after connecting. The Foreman replies with `snapshot`, then streams upserts.' },
  'goal.submit': { schema: GoalSubmitMsg, doc: 'New goal for the lead (console: plain text). Acked with `{goalId}`.' },
  'goal.message': { schema: GoalMessageMsg, doc: 'A message to the goal\'s lead about that goal. It runs as a turn of the lead\'s session for the goal (queued behind its other work; also for done/cancelled goals). The user\'s message and the lead\'s replies arrive as `feed.add` items with kind `message` and `goalId`. Acked with `{goalId, leadId}`.' },
  'goal.instructions': { schema: GoalInstructionsMsg, doc: 'Replace the goal\'s standing instructions (`Goal.instructions`). A change is sent to the lead as a goal message. Acked with `{goalId, changed}`.' },
  'goal.plan': { schema: GoalPlanMsg, doc: 'Write the goal\'s plan note as the user (creates it when missing; `memory.upsert`, `Goal.planId`), then send the lead a goal message with a unified diff of the change. Acked with `{goalId, planId, changed}`.' },
  'goal.cancel': { schema: GoalCancelMsg, doc: 'Cancel every open task of the goal (running workers stop, worktrees and branches kept, open decisions withdrawn) and set it `cancelled`. Refused for a done goal. Acked with `{goalId, cancelled: [taskIds]}`.' },
  'goal.digest': { schema: GoalDigestMsg, doc: 'What happened since `since` ("since you were away"). Acked with a `Digest` as `result` (`{since, until, goals}`); built from the feed, tasks and decisions, no model call.' },
  'repo.remove': { schema: RepoRemoveMsg, doc: 'Unregister a repository. Refused while it has open tasks (not done/cancelled) or a goal that is still planning. Worktrees, branches and lead assignments are left alone; there is no removal broadcast: other clients see it in their next snapshot. Acked with `{repoId}`.' },
  'user.message': { schema: UserMessageMsg, doc: 'Message an agent (console: `@name text`) or everyone.' },
  'decision.answer': { schema: DecisionAnswerMsg, doc: 'Answer an open decision. Merge decisions: option "Merge" merges, "Request changes" sends `text` back to the worker, "Reject" abandons the branch.' },
  'task.action': { schema: TaskActionMsg, doc: 'Steer a task from the Task Wall.' },
  'agent.action': { schema: AgentActionMsg, doc: 'Pause/resume/stop an agent, or spawn (activate) an off-shift worker.' },
  'diff.request': { schema: DiffRequestMsg, doc: 'Ask for the structured diff of a worktree. Answered with `diff` (same requestId).' },
  'repo.add': { schema: RepoAddMsg, doc: 'Register a local git repo (console: `/repo add <path>`).' },
  'design.request': { schema: DesignRequestMsg, doc: 'Design a new building blueprint (hub: Buildings -> Design new). Acked with `{designId}`; progress arrives as `design.upsert`. One design runs at a time; later ones queue.' },
  'design.cancel': { schema: DesignCancelMsg, doc: 'Cancel a queued or running design (the design agent\'s turn is stopped; nothing is written to outDir).' },
  'pr.refresh': { schema: PrRefreshMsg, doc: 'Poll the pull request(s) of tasks in status `pr` now instead of at the next interval (claude backend with PR watching on). Changes arrive as `task.upsert`.' },
  'lead.assign': { schema: LeadAssignMsg, doc: 'A building holding repositories was placed (or its repositories changed). Acked with `{leadId}`. Idempotent: the same `building` keeps its lead and gets its repos updated. A new building takes the first free lead in `claude.leads` order; when none is free the ack says `{leadId: "marlow", overflow: true}` and nothing is stored. A repository listed here leaves any other building that had it; a building left with no repository (here with `repos: []`, or because its last one moved) frees its lead. Open goals (planning / active) whose repository (`repoId`, else `repos[0]`) is in the building move to its lead (feed line per goal); new goals in these repositories go to that lead too.' },
  'lead.release': { schema: LeadReleaseMsg, doc: 'The building was removed. Acked with `{}` (also for a building that has no lead). Its lead goes off shift; its open goals move to marlow (feed line; marlow gets the plan note when it takes over).' },
  'config.get': { schema: ConfigGetMsg, doc: 'The editable settings (hub Team / Settings tabs, Repos "Edit settings"). Acked with `{file, settings: SettingDef[]}`: the global settings, or with `repoId` that repository\'s repoSettings. Never contains secret values: environment values, tokens, MCP server env values or headers, credential-looking arguments, URL credentials or queries.' },
  'config.set': { schema: ConfigSetMsg, doc: 'Change settings. Every change is validated first (all or nothing: one bad change refuses the lot, `ack.error` lists the problems), then config.json is written atomically (previous file kept as `config.json.bak`; unknown keys, other sections and key order kept), `live` keys apply at once (from the next turn / poll), and the ack is `{applied: [key], restartRequired: [key], overridden: [{key, by}]}` (a key a flag or variable also sets is written but stays overridden). Then `config.changed` is broadcast and `foreman.status.restartRequired` updated. Repository changes go to `repoSettings[<the repo\'s path as config.json spells it, else its absolute path>]`. Secret values sent here (`secretMap`, `mcpServers` env, args and URL) are never echoed in the ack, an error, `config.changed` or a log line: errors name setting keys and places (`change #2`, `server #1`, `env key #3`, `item #2`), never what was sent, and every secret value the Foreman knows (repository and MCP env values, MCP arguments, URL paths and queries, header values, the client token) is cut (`[redacted]`, also URL-encoded, JSON-escaped or base64) from agent logs, the feed, acks, errors, notifications and console logs.' },
  'foreman.restart': { schema: ForemanRestartMsg, doc: 'Restart the Foreman with the same arguments, environment and working directory (except `--reset`, `--goal` and `--autostart`). Acked with `{}` first; then the server closes (clients see the connection drop and reconnect), running turns are interrupted and resumed on start (`resumeOnStart`), and a new Foreman process (new pid, new client token: read the run file again) takes over the same port.' },
  'agent.logs.request': { schema: AgentLogsRequestMsg, doc: 'Older entries of an agent\'s log (the full history the Foreman stored, across one rotation: `logs/<agent>.jsonl` and `<agent>.1.jsonl`), for a scrollable log view; read-only (allowed without the client token). Acked with `{agentId, entries: LogEntry[], more}`: `entries` oldest first, all older than `before`; `more` = older entries exist (ask again with `before` = the first entry\'s `ts`). An unknown agent is refused.' },
  'repo.agents': { schema: RepoAgentsMsg, doc: 'The repository\'s Claude Code agent files (`.claude/agents/*.md` in its checkout), for the roles picker. Acked with `{agents: [{id, name, path, description?, model?}]}`: `id` is the file name without `.md` (the value to store in `roles.<agent>`), `name` the front matter name (else the id), `path` repo-relative.' },
  'lead.sync': { schema: LeadSyncMsg, doc: 'Sent by the mod on connect for its world: every `"<world>/..."` building not in the list is released first, then each listed building is assigned (as `lead.assign`). Acked with `{leads}` (building -> lead id). Also records the world\'s `lastSync`.' },
  'lead.releaseWorld': { schema: LeadReleaseWorldMsg, doc: 'Release every lead held by buildings of another world (hub Team tab "Release" next to a world in `leads.update` that is not the current one). Acked with `{released: [leadId]}`; their open goals move to marlow as with `lead.release`. Worlds that have not synced for `claude.leadWorldTtlDays` (default 14; 0 = never) are released automatically at start and daily.' },
} as const;

export const ENTITY_SCHEMAS = {
  Agent,
  LogEntry,
  Task,
  TaskPr,
  Decision,
  DecisionAnswer,
  Repo,
  Worktree,
  MemoryEntry,
  Goal,
  GoalPr,
  RepoSettingsView,
  Digest,
  GoalDigest,
  DigestLine,
  LeadAssignment,
  FeedItem,
  ForemanStatus,
  AgentLogs,
  DiffFile,
  DiffHunk,
  DiffLine,
  DesignRequest,
  Design,
  SettingDef,
} as const;

/** Named enums. Order = documentation order (docs/protocol.md, the Java mirror). */
export const ENUM_SCHEMAS = {
  AgentState,
  Station,
  AgentRole,
  TaskStatus,
  CiStatus,
  LogKind,
  DecisionKind,
  DecisionStatus,
  GoalStatus,
  FeedKind,
  NotifyLevel,
  WorktreeStatus,
  BackendName,
  AuthStatus,
  DesignStatus,
  DesignStyle,
  DesignFeature,
  PrStatus,
  PrChecks,
  DigestLineKind,
  SettingType,
  SettingSource,
} as const;

/** Merge decision option labels (exact strings). */
export const MERGE_OPTIONS = ['Merge', 'Request changes', 'Reject'] as const;
/** Permission decision option labels (exact strings). */
export const PERMISSION_OPTIONS = ['Allow once', 'Always allow for this agent', 'Deny'] as const;
