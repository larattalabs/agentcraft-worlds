# AgentCraft protocol v1

> GENERATED from `foreman/src/protocol.ts` and `foreman/src/protocol-examples.ts` by `npm run gen:protocol-doc` (in `foreman/`). Do not edit by hand. The Java mod mirrors these shapes.

## Transport

- The Foreman listens on `ws://127.0.0.1:${AGENTCRAFT_PORT:-7878}`. Clients (the mod, CLI tools) connect, send `hello`, and receive a full `snapshot` followed by incremental messages. Multiple clients may be connected; every client receives every broadcast.
- One JSON object per WebSocket **text** frame. Envelope: `{ "v": 1, "type": "<type>", "id"?: "<correlation id>", ...payload }`.
- Client messages that carry an `id` are answered with `ack { re: id, ok, error?, result? }`. Invalid messages get `error` (and a failed `ack` if they had an id).
- Timestamps are integer epoch milliseconds. Colors are `"#RRGGBB"`. Optional fields are omitted, never `null`. Receivers must ignore unknown fields.
- Upserts replace the whole entity by id. `agent.log` and `feed.add` append.
- Connections that carry any `Origin` header (browsers; also `Origin: null` from sandboxed iframes, `data:` and `file:` pages) or a Host header other than `127.0.0.1` / `localhost` / `[::1]` are rejected with HTTP 401, so a web page cannot drive your agents. Clients (the mod, CLI tools) must not send an Origin header. Heartbeat: the Foreman pings every 15 s.
- The mod should reconnect with backoff and re-send `hello`; the snapshot rebuilds the whole view.

## Enums

- <a id="agentstate"></a>**AgentState**: `idle`, `thinking`, `reading`, `editing`, `running`, `testing`, `waiting_user`, `blocked`, `done`, `error` - What the agent is doing right now; drives nameplate dot color, particles and animation.
- <a id="station"></a>**Station**: `desk`, `library`, `terminal`, `testbench`, `mergestation`, `meeting`, `lounge`, `user` - Where in the HQ the agent should walk to. `user` = next to the player / Decision Podium.
- <a id="agentrole"></a>**AgentRole**: `lead`, `worker`
- <a id="taskstatus"></a>**TaskStatus**: `todo`, `doing`, `review`, `pr`, `done`, `blocked`, `cancelled` - Task Wall column. `pr` = landed as a pull request that is still open (the Foreman watches it; see Task.pr): shown like review, labelled "PR open"; it becomes `done` when the PR is merged, `cancelled` when it is abandoned. `cancelled` tasks are kept for history but should not be shown on the wall.
- <a id="cistatus"></a>**CiStatus**: `unknown`, `running`, `pass`, `fail`
- <a id="logkind"></a>**LogKind**: `text`, `tool`, `result`, `error`, `diff`
- <a id="decisionkind"></a>**DecisionKind**: `question`, `permission`, `merge`
- <a id="decisionstatus"></a>**DecisionStatus**: `open`, `answered`, `cancelled` - `cancelled` = became moot (agent stopped, task cancelled); render like answered.
- <a id="goalstatus"></a>**GoalStatus**: `planning`, `active`, `done`, `failed`, `cancelled`
- <a id="feedkind"></a>**FeedKind**: `goal`, `plan`, `task`, `message`, `decision`, `merge`, `ci`, `memory`, `system`, `error`, `user`
- <a id="notifylevel"></a>**NotifyLevel**: `info`, `warn`, `need_user`
- <a id="worktreestatus"></a>**WorktreeStatus**: `active`, `merged`, `abandoned`
- <a id="backendname"></a>**BackendName**: `sim`, `claude`
- <a id="authstatus"></a>**AuthStatus**: `ok`, `failed`, `unknown`, `checking` - `failed` must be shown loudly (in-world banner): the claude backend cannot run.
- <a id="designstatus"></a>**DesignStatus**: `queued`, `designing`, `checking`, `rendering`, `done`, `failed`, `cancelled` - queued -> designing (the design agent works) -> checking (the Foreman re-runs the checker) -> rendering (previews) -> done; or failed / cancelled. done, failed and cancelled are final.
- <a id="designstyle"></a>**DesignStyle**: `modern`, `cabin`, `townhouse`, `workshop`, `campus`, `custom` - style preset of a generated building (docs/HUB.md); `custom` = described only by the notes
- <a id="designfeature"></a>**DesignFeature**: `porch`, `skylights`, `courtyard`, `big_windows`, `garden`
- <a id="prstatus"></a>**PrStatus**: `open`, `changes`, `approved`, `merged`, `abandoned` - open: waiting for reviews; changes: a reviewer asked for changes (vote -5/-10, GitHub CHANGES_REQUESTED); approved: approved and nobody objects; merged / abandoned: closed on the host
- <a id="prchecks"></a>**PrChecks**: `pending`, `passing`, `failing`, `none` - build / status checks on the PR (Azure DevOps build policies, GitHub status checks); none = the PR has no checks
- <a id="digestlinekind"></a>**DigestLineKind**: `task_done`, `task_blocked`, `task_added`, `decision_waiting`, `decision_answered`, `merged`, `pr_opened`, `pr_merged`, `pr_comments`, `message`, `goal_done`
- <a id="settingtype"></a>**SettingType**: `bool`, `int`, `enum`, `string`, `stringList`, `model`, `effort`, `agentList`, `map` - How a setting is edited: bool toggle, int stepper (min/max), enum chips (options), string field, string list editor, model / effort picker (options), agent list (options = agent ids; ordered for claude.leads), map (read-only: MCP servers by name and command, repo env by name)
- <a id="settingsource"></a>**SettingSource**: `file`, `flag`, `env`, `default` - where the value comes from: config.json, a command-line flag, an AGENTCRAFT_* environment variable, or the built-in default

Exact option labels: merge decisions use `Merge`, `Request changes`, `Reject`; permission decisions use `Allow once`, `Always allow for this agent`, `Deny`. Question decisions use agent-supplied options (may be empty: free text).

## Entities

### <a id="agent"></a>Agent

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | stable lowercase id, e.g. "kit" |
| `name` | string | yes | display name, e.g. "Kit" |
| `role` | [AgentRole](#agentrole) | yes |  |
| `title` | string | no | short descriptive role from cast.json, e.g. "Backend tinkerer" |
| `color` | string (#RRGGBB) | yes | agent color (scarf/badge/nameplate) |
| `accent` | string (#RRGGBB) | no | "#RRGGBB" |
| `skin` | string | yes | skin id -> assets/agentcraft/textures/entity/agent/<skin>.png |
| `state` | [AgentState](#agentstate) | yes | What the agent is doing right now; drives nameplate dot color, particles and animation. |
| `activity` | string | yes | one short line for the nameplate, e.g. "editing src/cli.ts" (<= 48 chars) |
| `station` | [Station](#station) | yes | Where in the HQ the agent should walk to. `user` = next to the player / Decision Podium. |
| `taskId` | string | no |  |
| `repoId` | string | no |  |
| `worktree` | string | no | id of the worktree the agent is working in (see Repo.worktrees) |
| `paused` | boolean | yes |  |
| `active` | boolean | yes | false = off shift (not on the current team, or stopped by the user); render idle in the lounge |

### <a id="logentry"></a>LogEntry

| field | type | required | notes |
| --- | --- | --- | --- |
| `ts` | integer | yes | epoch milliseconds |
| `kind` | [LogKind](#logkind) | yes |  |
| `text` | string | yes | may contain newlines; `diff` entries use unified +/- line prefixes |

### <a id="task"></a>Task

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "t3" |
| `title` | string | yes |  |
| `description` | string | no |  |
| `status` | [TaskStatus](#taskstatus) | yes | Task Wall column. `pr` = landed as a pull request that is still open (the Foreman watches it; see Task.pr): shown like review, labelled "PR open"; it becomes `done` when the PR is merged, `cancelled` when it is abandoned. `cancelled` tasks are kept for history but should not be shown on the wall. |
| `assignee` | string | no | agent id |
| `deps` | string[] | yes | task ids that must be done before this one can start |
| `repoId` | string | no |  |
| `goalId` | string | no |  |
| `priority` | integer | yes | higher = sooner; default 0 |
| `branch` | string | no | git branch, e.g. "agentcraft/kit/t2-tag-parser" |
| `worktree` | string | no |  |
| `ci` | [CiStatus](#cistatus) | yes |  |
| `blockedReason` | string | no |  |
| `summary` | string | no | worker/lead summary of the result |
| `pr` | [TaskPr](#taskpr) | no | the pull request this task landed as (repoSettings land "pr"), while it is watched and after it closed |
| `createdBy` | string | yes | agent id or "user" |
| `createdAt` | integer | yes | epoch milliseconds |
| `updatedAt` | integer | yes | epoch milliseconds |

### <a id="taskpr"></a>TaskPr

| field | type | required | notes |
| --- | --- | --- | --- |
| `url` | string | yes | the PR's web URL |
| `id` | integer | yes | PR number on its host |
| `host` | `ado` \| `github` | yes |  |
| `branch` | string | yes | source branch on the remote |
| `target` | string | yes | target branch on the remote |
| `status` | [PrStatus](#prstatus) | yes | open: waiting for reviews; changes: a reviewer asked for changes (vote -5/-10, GitHub CHANGES_REQUESTED); approved: approved and nobody objects; merged / abandoned: closed on the host |
| `checks` | [PrChecks](#prchecks) | yes | build / status checks on the PR (Azure DevOps build policies, GitHub status checks); none = the PR has no checks |
| `threads` | { open: integer, new: integer } | yes |  |
| `updatedAt` | integer | yes | last successful poll of the host |

### <a id="decision"></a>Decision

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "d4" |
| `agentId` | string | yes | agent waiting on this decision |
| `kind` | [DecisionKind](#decisionkind) | yes |  |
| `question` | string | yes |  |
| `options` | string[] | yes | button labels; first is the default/recommended choice |
| `context` | string | no | extra detail, multi-line plain text. permission: why it asks, the cwd, and a line `"Always allow for this agent" covers: ...` (the scope of that choice). merge: summary + diff stat; after a refused merge the decision is open again and this ends with `Merge refused: <reason>` |
| `status` | [DecisionStatus](#decisionstatus) | yes | `cancelled` = became moot (agent stopped, task cancelled); render like answered. |
| `answer` | [DecisionAnswer](#decisionanswer) | no |  |
| `taskId` | string | no |  |
| `repoId` | string | no | merge decisions: repo to request the diff from |
| `worktree` | string | no | merge decisions: worktree to request the diff for |
| `tool` | string | no | permission decisions: tool name, e.g. "Bash" |
| `goalId` | string | no | the goal this decision is about (its task's goal, or the goal of the lead turn that asked); absent on older decisions and ones not tied to a goal |
| `textAllowed` | boolean | no | false: only the options make sense (e.g. PR "Post"/"Skip", "Fold in"/"Leave it"): hide or disable free text; a `decision.answer` without a valid option is refused. Absent = true |
| `createdAt` | integer | yes | epoch milliseconds |

### <a id="decisionanswer"></a>DecisionAnswer

| field | type | required | notes |
| --- | --- | --- | --- |
| `option` | string | no | the chosen option label (one of Decision.options) |
| `text` | string | no | free-text answer / feedback |
| `ts` | integer | yes | epoch milliseconds |

### <a id="repo"></a>Repo

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "demo-app" |
| `name` | string | yes |  |
| `path` | string | yes | absolute path of the user checkout |
| `branch` | string | yes | base branch agents branch from and merge into |
| `head` | string | no | short sha of base branch |
| `dirty` | boolean | yes | user checkout has uncommitted tracked changes (merges are refused while dirty) |
| `worktrees` | [Worktree](#worktree)[] | yes |  |
| `ci` | `unknown` \| `running` \| `pass` \| `fail` | yes | latest CI/test result across this repo |
| `settings` | [RepoSettingsView](#reposettingsview) | no | read-only view of the repository's config.json repoSettings (no secrets: env shows its keys only) |

### <a id="worktree"></a>Worktree

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "kit-t2" — use as `worktree` in diff.request |
| `agentId` | string | yes |  |
| `taskId` | string | no |  |
| `branch` | string | yes |  |
| `base` | string | yes | branch it was created from / will merge into |
| `path` | string | yes |  |
| `status` | [WorktreeStatus](#worktreestatus) | yes |  |
| `ahead` | integer | yes | commits on branch not on base |
| `files` | integer | yes | files changed vs base (incl. uncommitted) |
| `additions` | integer | yes |  |
| `deletions` | integer | yes |  |

### <a id="memoryentry"></a>MemoryEntry

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | "shared/<slug>" or "<agentId>/<slug>" |
| `scope` | string | yes | "shared" or an agent id |
| `title` | string | yes |  |
| `body` | string | yes | markdown |
| `updated` | integer | yes | epoch milliseconds |
| `author` | string | no | agent id or "user" that last wrote it |

### <a id="goal"></a>Goal

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "g1" |
| `text` | string | yes |  |
| `progress` | number | yes |  |
| `status` | `planning` \| `active` \| `done` \| `failed` \| `cancelled` | yes | planning (lead is planning) -> active -> done (every non-cancelled task merged/done); cancelled: every task was cancelled or rejected (back to active if the lead adds a task); failed: planning failed |
| `repoId` | string | no |  |
| `leadId` | string | no | the lead running this goal (set at submit from the goal's repository: the lead of the building that has it). Absent = "marlow". Changes only while the goal is open: when its lead is released (lead.release / lead.sync / lead.releaseWorld) marlow takes it over; when a building is assigned its repository (lead.assign / lead.sync) that building's lead adopts it |
| `repos` | string[] | no | every repository the goal touches: repoId first, then each task's repository in order of first appearance (kept up to date) |
| `instructions` | string[] | no | standing instructions (goal.instructions): in the lead's prompts, appended to new task descriptions, and a section of every worker prompt for its tasks |
| `planId` | string | no | memory entry id of the goal's plan note, once it exists |
| `branch` | string | no | the user's branch the goal continues ("on <branch>:" prefix or goal.submit branch) |
| `prs` | [GoalPr](#goalpr)[] | no | its tasks' pull requests (tasks with Task.pr), in task order |
| `createdAt` | integer | yes | epoch milliseconds |
| `updatedAt` | integer | yes | epoch milliseconds |

### <a id="goalpr"></a>GoalPr

| field | type | required | notes |
| --- | --- | --- | --- |
| `taskId` | string | yes |  |
| `url` | string | yes |  |
| `id` | integer | yes | PR number on its host |
| `status` | [PrStatus](#prstatus) | yes | open: waiting for reviews; changes: a reviewer asked for changes (vote -5/-10, GitHub CHANGES_REQUESTED); approved: approved and nobody objects; merged / abandoned: closed on the host |

### <a id="reposettingsview"></a>RepoSettingsView

| field | type | required | notes |
| --- | --- | --- | --- |
| `land` | `merge` \| `pr` | yes | how approved work lands (repoSettings.land, default "merge") |
| `baseBranch` | string | no | configured base branch (repoSettings.baseBranch) |
| `ci` | string | no | configured test command (repoSettings.ci); absent = --ci or detected |
| `setup` | string | no | worktree setup command (repoSettings.setup) |
| `pr` | { remote?: string, branchPrefix?: string, draft?: boolean, squash?: boolean } | no | pull request options (repoSettings.pr) |
| `protect` | string[] | yes | paths never committed (repoSettings.protect) |
| `roles` | map<string, string> | yes | agent id -> the repository agent file that is its role here |
| `subagents` | string | no | "repo": the repository's .claude/agents files are usable as subagents |
| `prReview` | { autoSeverities: string[], maxRounds: integer } | no | PR review triage (configured values, else the defaults) |
| `envKeys` | string[] | no | names of the repoSettings.env variables (values are never sent) |

### <a id="digest"></a>Digest

| field | type | required | notes |
| --- | --- | --- | --- |
| `since` | integer | yes | epoch milliseconds |
| `until` | integer | yes | epoch milliseconds |
| `goals` | [GoalDigest](#goaldigest)[] | yes | with goalId: that goal; without: every goal with something in the window |

### <a id="goaldigest"></a>GoalDigest

| field | type | required | notes |
| --- | --- | --- | --- |
| `goalId` | string | yes |  |
| `text` | string | yes | the goal's text |
| `status` | [GoalStatus](#goalstatus) | yes |  |
| `progress` | number | yes |  |
| `lines` | [DigestLine](#digestline)[] | yes | at most 30, oldest first (newest last); routine progress left out |

### <a id="digestline"></a>DigestLine

| field | type | required | notes |
| --- | --- | --- | --- |
| `ts` | integer | yes | epoch milliseconds |
| `kind` | [DigestLineKind](#digestlinekind) | yes |  |
| `text` | string | yes |  |
| `taskId` | string | no |  |
| `agentId` | string | no |  |

### <a id="leadassignment"></a>LeadAssignment

| field | type | required | notes |
| --- | --- | --- | --- |
| `leadId` | string | yes | a lead agent id, e.g. "ines" |
| `building` | string (#RRGGBB) | no | the building this lead leads; absent for "marlow" (home, repositories without a building, everything not tied to a repository) |
| `repos` | string[] | yes | repository ids of the building (a repository is in at most one building); empty for marlow |
| `world` | string | no | the world (save folder name) the building is in; absent for marlow |
| `lastSync` | integer | no | when that world last talked to the Foreman (lead.sync / lead.assign / lead.release). Assignments of worlds not seen for `claude.leadWorldTtlDays` (default 14) are dropped |

### <a id="feeditem"></a>FeedItem

| field | type | required | notes |
| --- | --- | --- | --- |
| `ts` | integer | yes | epoch milliseconds |
| `kind` | [FeedKind](#feedkind) | yes |  |
| `text` | string | yes |  |
| `agentId` | string | no | who it is about / from |
| `to` | string | no | message recipient: agent id, "user" or "all" |
| `goalId` | string | no | the goal the item is about (its tasks, its lead's turns for it, its PRs, goal messages); absent on older items and ones not tied to a goal |

### <a id="foremanstatus"></a>ForemanStatus

| field | type | required | notes |
| --- | --- | --- | --- |
| `version` | string | yes |  |
| `backend` | [BackendName](#backendname) | yes |  |
| `auth` | [AuthStatus](#authstatus) | yes | `failed` must be shown loudly (in-world banner): the claude backend cannot run. |
| `message` | string | no | human-readable backend/auth status for the banner |
| `account` | string | no | e.g. organization / plan when auth ok |
| `speed` | number | no | sim: speed multiplier |
| `showcase` | boolean | no | sim: holding a static showcase state (`--showcase` or `--showcase late`) |
| `costUsd` | number | no | claude: estimated spend of this profile (sum over all sessions, survives restarts) |
| `userName` | string | no | the person the team works for, as the agents address them (UI: "<name> answered") |
| `usage` | { windows: { id: string, label: string, pct: number, resetsAt?: integer }[], updatedAt: integer } | no | claude.ai login: how much of the plan's usage windows is used (from the agents' sessions) |
| `restartRequired` | string[] | no | config keys changed (config.set) that take effect only after a restart (`foreman.restart`); omitted when none |
| `hold` | { reason: `usage` \| `auth` \| `offline`, until?: integer, message: string } | no | claude: the backend is holding new agent turns (usage limit or reserve, auth failure, offline); absent when nothing holds them. Running turns finish; queued work starts when the hold ends |

### <a id="agentlogs"></a>AgentLogs

| field | type | required | notes |
| --- | --- | --- | --- |
| `agentId` | string | yes |  |
| `entries` | [LogEntry](#logentry)[] | yes |  |

### <a id="difffile"></a>DiffFile

| field | type | required | notes |
| --- | --- | --- | --- |
| `path` | string | yes |  |
| `oldPath` | string | no | renames only |
| `status` | `added` \| `modified` \| `deleted` \| `renamed` | yes |  |
| `binary` | boolean | yes |  |
| `additions` | integer | yes |  |
| `deletions` | integer | yes |  |
| `hunks` | [DiffHunk](#diffhunk)[] | yes |  |

### <a id="diffhunk"></a>DiffHunk

| field | type | required | notes |
| --- | --- | --- | --- |
| `header` | string | yes | e.g. "@@ -12,6 +12,9 @@ export function run" |
| `oldStart` | integer | yes |  |
| `oldLines` | integer | yes |  |
| `newStart` | integer | yes |  |
| `newLines` | integer | yes |  |
| `lines` | [DiffLine](#diffline)[] | yes |  |

### <a id="diffline"></a>DiffLine

| field | type | required | notes |
| --- | --- | --- | --- |
| `kind` | `add` \| `del` \| `ctx` | yes |  |
| `text` | string | yes | line content without the +/-/space prefix |
| `oldNo` | integer | no | line number in base (del, ctx) |
| `newNo` | integer | no | line number in branch (add, ctx) |

### <a id="designrequest"></a>DesignRequest

| field | type | required | notes |
| --- | --- | --- | --- |
| `kind` | `single` \| `group` | yes | single: one repo (wings must be 1); group: N repos, one wing each (wings >= 2) |
| `wings` | integer | yes |  |
| `style` | [DesignStyle](#designstyle) | yes | style preset of a generated building (docs/HUB.md); `custom` = described only by the notes |
| `materials` | `agentcraft` \| `vanilla` | yes | agentcraft: the AgentCraft look built from vanilla blocks; vanilla: any vanilla look (both: AgentCraft blocks only for the station blocks) |
| `features` | [DesignFeature](#designfeature)[] | yes |  |
| `maxSize` | { x: integer, y: integer, z: integer } | yes | the largest template allowed (x/z 9..128, y 6..48), e.g. from a marked plot |
| `remix` | string (#RRGGBB) | no | start from this blueprint id (bundled or user) |
| `name` | string | no | display name; also names the blueprint id (gen_<slug>). Default: from the style |
| `notes` | string | no | free text for the designer |
| `outDir` | string | yes | absolute `<gameDir>/agentcraft/blueprints` (the mod's user blueprint folder); any other path is refused |

### <a id="design"></a>Design

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "d7" (allocated from the same counter as decision ids, so never equal to one) |
| `request` | [DesignRequest](#designrequest) | yes |  |
| `status` | [DesignStatus](#designstatus) | yes | queued -> designing (the design agent works) -> checking (the Foreman re-runs the checker) -> rendering (previews) -> done; or failed / cancelled. done, failed and cancelled are final. |
| `step` | string | yes | one line of progress, e.g. "running the checker (round 2)" |
| `blueprintId` | string | no | done: the new blueprint id in outDir (gen_<slug>, gen_<slug>_2, ...) |
| `size` | { x: integer, y: integer, z: integer } | no | done: template size |
| `previews` | string[] | no | done: absolute paths of the preview PNGs in outDir (empty when no renderer is available) |
| `error` | string | no | failed: what went wrong (tail of the checker output) |
| `createdAt` | integer | yes | epoch milliseconds |
| `updatedAt` | integer | yes | epoch milliseconds |

### <a id="settingdef"></a>SettingDef

| field | type | required | notes |
| --- | --- | --- | --- |
| `key` | string | yes | the config.json path, e.g. "claude.prWatch", "claude.agents.kit.model"; for a repository relative to its repoSettings entry, e.g. "land", "pr.draft", "roles.kit" |
| `label` | string | yes |  |
| `help` | string | yes | one or two sentences for the user |
| `group` | string | yes | global: team, models, general, permissions, context, subagents, prs, usage; repository: landing, worktrees, agents, review |
| `type` | [SettingType](#settingtype) | yes | How a setting is edited: bool toggle, int stepper (min/max), enum chips (options), string field, string list editor, model / effort picker (options), agent list (options = agent ids; ordered for claude.leads), map (read-only: MCP servers by name and command, repo env by name) |
| `options` | string[] | no | enum / model / effort / agentList / stringList choices (model: the Opus and Sonnet models in use and "default"; "default" clears the setting) |
| `min` | number | no |  |
| `max` | number | no |  |
| `value` | any | yes | the configured value (what config.json, a flag or the environment says now; may differ from what the running Foreman uses until a restart, see restartRequired) |
| `default` | any | yes | the value when nothing is configured |
| `source` | [SettingSource](#settingsource) | yes | where the value comes from: config.json, a command-line flag, an AGENTCRAFT_* environment variable, or the built-in default |
| `live` | boolean | yes | true: a change applies from the next turn / poll without a restart; false: after foreman.restart |
| `overriddenBy` | string | no | the flag or variable that wins over config.json, e.g. "--lead-model" or "AGENTCRAFT_PR_WATCH": a change is written but has no effect while it is given |
| `readOnly` | boolean | no | shown, never changed through config.set (MCP servers, repo env) |

## Foreman -> Mod

### `snapshot`

Full state. Sent in reply to every `hello`; the mod rebuilds its view from it.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `foreman` | [ForemanStatus](#foremanstatus) | yes |  |
| `agents` | [Agent](#agent)[] | yes |  |
| `tasks` | [Task](#task)[] | yes |  |
| `decisions` | [Decision](#decision)[] | yes | open decisions plus the most recent answered ones |
| `repos` | [Repo](#repo)[] | yes |  |
| `memory` | [MemoryEntry](#memoryentry)[] | yes |  |
| `goal` | [Goal](#goal) | no | current (latest) goal |
| `goals` | [Goal](#goal)[] | yes | all goals, oldest first |
| `feed` | [FeedItem](#feeditem)[] | yes | most recent feed items, oldest first (<= 200) |
| `logs` | [AgentLogs](#agentlogs)[] | yes | recent log tail per agent (<= 60 entries each) |
| `designs` | [Design](#design)[] | yes | the most recent building designs (<= 20), oldest first; queued/running ones always included |
| `leads` | [LeadAssignment](#leadassignment)[] | yes | who leads what: marlow first (no building), then every assigned building lead (same as `leads.update`) |

```json
{
  "v": 1,
  "type": "snapshot",
  "foreman": {
    "version": "0.1.0",
    "backend": "claude",
    "auth": "ok",
    "account": "fal · Claude Enterprise",
    "message": "Claude (lead opus, workers sonnet)",
    "costUsd": 0.42
  },
  "agents": [
    {
      "id": "kit",
      "name": "Kit",
      "role": "worker",
      "title": "Builder & tester",
      "color": "#2E78C6",
      "accent": "#F4EFE6",
      "skin": "kit",
      "state": "editing",
      "activity": "editing src/tags.ts",
      "station": "desk",
      "taskId": "t2",
      "repoId": "demo-app",
      "worktree": "kit-t2",
      "paused": false,
      "active": true
    }
  ],
  "tasks": [
    {
      "id": "t2",
      "title": "Tag parser module (src/tags.ts)",
      "description": "parseTags/hasTag/normalizeTag with unit tests.",
      "status": "doing",
      "assignee": "kit",
      "deps": [
        "t1"
      ],
      "repoId": "demo-app",
      "goalId": "g1",
      "priority": 2,
      "branch": "agentcraft/kit/t2-tag-parser-module",
      "worktree": "kit-t2",
      "ci": "fail",
      "createdBy": "marlow",
      "createdAt": 1790850000000,
      "updatedAt": 1790850060000
    }
  ],
  "decisions": [
    {
      "id": "d2",
      "agentId": "marlow",
      "kind": "merge",
      "question": "Merge t2 \"Tag parser module (src/tags.ts)\" (agentcraft/kit/t2-tag-parser-module) into main?",
      "options": [
        "Merge",
        "Request changes",
        "Reject"
      ],
      "context": "2 files, +45 -0 | tests: pass",
      "status": "open",
      "taskId": "t2",
      "repoId": "demo-app",
      "worktree": "kit-t2",
      "goalId": "g1",
      "createdAt": 1790850120000
    }
  ],
  "repos": [
    {
      "id": "demo-app",
      "name": "demo-app",
      "path": "C:\\Projects\\agentcraft\\sandbox\\demo-app",
      "branch": "main",
      "head": "a6cbf49",
      "dirty": false,
      "ci": "pass",
      "settings": {
        "land": "pr",
        "baseBranch": "dev",
        "ci": "npm test",
        "setup": "npm ci",
        "pr": {
          "remote": "origin",
          "branchPrefix": "feat/",
          "draft": true
        },
        "protect": [
          ".env",
          "secrets/"
        ],
        "roles": {
          "kit": "backend-dev"
        },
        "prReview": {
          "autoSeverities": [
            "critical",
            "important"
          ],
          "maxRounds": 2
        },
        "envKeys": [
          "NODE_OPTIONS"
        ]
      },
      "worktrees": [
        {
          "id": "kit-t2",
          "agentId": "kit",
          "taskId": "t2",
          "branch": "agentcraft/kit/t2-tag-parser-module",
          "base": "main",
          "path": "C:\\Users\\you\\.agentcraft\\claude\\worktrees\\demo-app\\kit-t2",
          "status": "active",
          "ahead": 1,
          "files": 2,
          "additions": 45,
          "deletions": 0
        }
      ]
    }
  ],
  "memory": [
    {
      "id": "shared/plan",
      "scope": "shared",
      "title": "Plan: #tags for pocket-notes",
      "body": "# Plan\n\n- t2 Tag parser module - Kit\n- t3 `list --tag` - Juniper",
      "updated": 1790850030000,
      "author": "marlow"
    }
  ],
  "goal": {
    "id": "g1",
    "text": "Add #tags to pocket-notes",
    "progress": 0.39,
    "status": "active",
    "repoId": "demo-app",
    "createdAt": 1790850000000,
    "updatedAt": 1790850120000
  },
  "goals": [
    {
      "id": "g1",
      "text": "Add #tags to pocket-notes",
      "progress": 0.39,
      "status": "active",
      "repoId": "demo-app",
      "createdAt": 1790850000000,
      "updatedAt": 1790850120000
    }
  ],
  "feed": [
    {
      "ts": 1790850005000,
      "kind": "plan",
      "text": "Marlow planned the goal into 9 tasks",
      "agentId": "marlow"
    }
  ],
  "logs": [
    {
      "agentId": "kit",
      "entries": [
        {
          "ts": 1790850090000,
          "kind": "tool",
          "text": "Edit src/tags.ts"
        }
      ]
    }
  ],
  "designs": [
    {
      "id": "d7",
      "request": {
        "kind": "single",
        "wings": 1,
        "style": "cabin",
        "materials": "agentcraft",
        "features": [
          "porch",
          "big_windows"
        ],
        "maxSize": {
          "x": 24,
          "y": 16,
          "z": 20
        },
        "name": "Lakeside Cabin",
        "notes": "cosy, a reading nook by the fire",
        "outDir": "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints"
      },
      "status": "done",
      "step": "done: checker OK, 3 previews",
      "blueprintId": "gen_lakeside_cabin",
      "size": {
        "x": 23,
        "y": 14,
        "z": 19
      },
      "previews": [
        "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints\\gen_lakeside_cabin.preview-iso.png",
        "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints\\gen_lakeside_cabin.preview-top.png",
        "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints\\gen_lakeside_cabin.preview-front.png"
      ],
      "createdAt": 1790850000000,
      "updatedAt": 1790850480000
    }
  ],
  "leads": [
    {
      "leadId": "marlow",
      "repos": []
    },
    {
      "leadId": "ines",
      "building": "New World/b3",
      "repos": [
        "demo-app"
      ]
    }
  ]
}
```

### `agent.upsert`

An agent was created or changed (state, station, activity, task...). Replace by `agent.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agent` | [Agent](#agent) | yes |  |

```json
{
  "v": 1,
  "type": "agent.upsert",
  "agent": {
    "id": "kit",
    "name": "Kit",
    "role": "worker",
    "title": "Builder & tester",
    "color": "#2E78C6",
    "accent": "#F4EFE6",
    "skin": "kit",
    "state": "editing",
    "activity": "editing src/tags.ts",
    "station": "desk",
    "taskId": "t2",
    "repoId": "demo-app",
    "worktree": "kit-t2",
    "paused": false,
    "active": true
  }
}
```

### `agent.log`

New log lines for an agent monitor (append; keep a bounded tail).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agentId` | string | yes |  |
| `entries` | [LogEntry](#logentry)[] | yes |  |

```json
{
  "v": 1,
  "type": "agent.log",
  "agentId": "kit",
  "entries": [
    {
      "ts": 1790850091000,
      "kind": "tool",
      "text": "$ npm test"
    },
    {
      "ts": 1790850092000,
      "kind": "error",
      "text": "tests FAILED (0.4s) - tests 15, pass 12, fail 3\nFAIL parseTags keeps hyphenated tags"
    },
    {
      "ts": 1790850093000,
      "kind": "diff",
      "text": "src/tags.ts\n- const TAG_RE = /#(\\w+)/g;\n+ const TAG_RE = /(?:^|\\s)#(\\w[\\w-]*)/g;"
    }
  ]
}
```

### `agent.say`

Speech bubble above the agent; also mirrored to the feed.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agentId` | string | yes |  |
| `text` | string | yes |  |
| `to` | string | no | agent id, "user" or "all"; omitted = said to the room |
| `ts` | integer | yes | epoch milliseconds |

```json
{
  "v": 1,
  "type": "agent.say",
  "agentId": "kit",
  "to": "juniper",
  "text": "parseTags() is in src/tags.ts - you are unblocked once it merges.",
  "ts": 1790850095000
}
```

### `task.upsert`

Task created or changed. Replace by `task.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `task` | [Task](#task) | yes |  |

```json
{
  "v": 1,
  "type": "task.upsert",
  "task": {
    "id": "t4",
    "title": "Tag filter in the list view",
    "description": "parseTags/hasTag/normalizeTag with unit tests.",
    "status": "pr",
    "assignee": "wren",
    "deps": [
      "t1"
    ],
    "repoId": "demo-app",
    "goalId": "g1",
    "priority": 2,
    "branch": "agentcraft/wren/t4-tag-filter",
    "worktree": "wren-t4",
    "ci": "pass",
    "createdBy": "marlow",
    "createdAt": 1790850000000,
    "updatedAt": 1790850060000,
    "pr": {
      "url": "https://dev.azure.com/acme/Notes/_git/pocket-notes/pullrequest/612",
      "id": 612,
      "host": "ado",
      "branch": "feat/t4-tag-filter",
      "target": "dev",
      "status": "open",
      "checks": "passing",
      "threads": {
        "open": 2,
        "new": 1
      },
      "updatedAt": 1790850400000
    }
  }
}
```

### `decision.upsert`

Decision opened, answered or cancelled. Replace by `decision.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `decision` | [Decision](#decision) | yes |  |

```json
{
  "v": 1,
  "type": "decision.upsert",
  "decision": {
    "id": "d2",
    "agentId": "marlow",
    "kind": "merge",
    "question": "Merge t2 \"Tag parser module (src/tags.ts)\" (agentcraft/kit/t2-tag-parser-module) into main?",
    "options": [
      "Merge",
      "Request changes",
      "Reject"
    ],
    "context": "2 files, +45 -0 | tests: pass",
    "status": "open",
    "taskId": "t2",
    "repoId": "demo-app",
    "worktree": "kit-t2",
    "goalId": "g1",
    "createdAt": 1790850120000
  }
}
```

### `repo.upsert`

Repo added or changed (worktrees, CI, head, dirty). Replace by `repo.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `repo` | [Repo](#repo) | yes |  |

```json
{
  "v": 1,
  "type": "repo.upsert",
  "repo": {
    "id": "demo-app",
    "name": "demo-app",
    "path": "C:\\Projects\\agentcraft\\sandbox\\demo-app",
    "branch": "main",
    "head": "a6cbf49",
    "dirty": false,
    "ci": "pass",
    "settings": {
      "land": "pr",
      "baseBranch": "dev",
      "ci": "npm test",
      "setup": "npm ci",
      "pr": {
        "remote": "origin",
        "branchPrefix": "feat/",
        "draft": true
      },
      "protect": [
        ".env",
        "secrets/"
      ],
      "roles": {
        "kit": "backend-dev"
      },
      "prReview": {
        "autoSeverities": [
          "critical",
          "important"
        ],
        "maxRounds": 2
      },
      "envKeys": [
        "NODE_OPTIONS"
      ]
    },
    "worktrees": [
      {
        "id": "kit-t2",
        "agentId": "kit",
        "taskId": "t2",
        "branch": "agentcraft/kit/t2-tag-parser-module",
        "base": "main",
        "path": "C:\\Users\\you\\.agentcraft\\claude\\worktrees\\demo-app\\kit-t2",
        "status": "active",
        "ahead": 1,
        "files": 2,
        "additions": 45,
        "deletions": 0
      }
    ]
  }
}
```

### `memory.upsert`

Memory entry written. Replace by `entry.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `entry` | [MemoryEntry](#memoryentry) | yes |  |

```json
{
  "v": 1,
  "type": "memory.upsert",
  "entry": {
    "id": "shared/plan",
    "scope": "shared",
    "title": "Plan: #tags for pocket-notes",
    "body": "# Plan\n\n- t2 Tag parser module - Kit\n- t3 `list --tag` - Juniper",
    "updated": 1790850030000,
    "author": "marlow"
  }
}
```

### `goal.upsert`

Goal created or progress/status changed. Replace by `goal.id`; latest goal is current.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `goal` | [Goal](#goal) | yes |  |

```json
{
  "v": 1,
  "type": "goal.upsert",
  "goal": {
    "id": "g1",
    "text": "Add #tags to pocket-notes",
    "progress": 0.56,
    "status": "active",
    "repoId": "demo-app",
    "leadId": "ines",
    "repos": [
      "demo-app",
      "notes-api"
    ],
    "instructions": [
      "No new dependencies",
      "Keep the CLI output under 80 columns"
    ],
    "planId": "shared/plan-tags-for-pocket-notes",
    "branch": "feature/tags",
    "prs": [
      {
        "taskId": "t4",
        "url": "https://dev.azure.com/acme/Notes/_git/pocket-notes/pullrequest/612",
        "id": 612,
        "status": "open"
      }
    ],
    "createdAt": 1790850000000,
    "updatedAt": 1790850200000
  }
}
```

### `feed.add`

Append to the activity feed.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `item` | [FeedItem](#feeditem) | yes |  |

```json
{
  "v": 1,
  "type": "feed.add",
  "item": {
    "ts": 1790850210000,
    "kind": "merge",
    "text": "Merged agentcraft/kit/t2-tag-parser-module into main (7cf1999, 2 files)",
    "agentId": "marlow",
    "goalId": "g1"
  }
}
```

### `diff`

Reply to `diff.request` (sent only to the requesting client). Structured unified diff of worktree vs base, including uncommitted changes.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `requestId` | string | yes |  |
| `repoId` | string | yes |  |
| `worktree` | string | yes |  |
| `base` | string | no |  |
| `branch` | string | no |  |
| `files` | [DiffFile](#difffile)[] | yes |  |
| `stats` | { files: integer, additions: integer, deletions: integer } | yes |  |
| `truncated` | boolean | yes | true if very large files/hunks were cut |
| `error` | string | no |  |

```json
{
  "v": 1,
  "type": "diff",
  "requestId": "r7",
  "repoId": "demo-app",
  "worktree": "kit-t2",
  "base": "main",
  "branch": "agentcraft/kit/t2-tag-parser-module",
  "files": [
    {
      "path": "src/tags.ts",
      "status": "added",
      "binary": false,
      "additions": 2,
      "deletions": 0,
      "hunks": [
        {
          "header": "@@ -0,0 +1,2 @@",
          "oldStart": 0,
          "oldLines": 0,
          "newStart": 1,
          "newLines": 2,
          "lines": [
            {
              "kind": "add",
              "text": "// Tag parsing for notes",
              "newNo": 1
            },
            {
              "kind": "add",
              "text": "export const TAG_RE = /(?:^|\\s)#(\\w[\\w-]*)/g;",
              "newNo": 2
            }
          ]
        }
      ]
    },
    {
      "path": "src/notes.ts",
      "status": "modified",
      "binary": false,
      "additions": 1,
      "deletions": 1,
      "hunks": [
        {
          "header": "@@ -9,3 +9,3 @@ export interface Note {",
          "oldStart": 9,
          "oldLines": 3,
          "newStart": 9,
          "newLines": 3,
          "lines": [
            {
              "kind": "ctx",
              "text": "export interface ListOptions {",
              "oldNo": 9,
              "newNo": 9
            },
            {
              "kind": "del",
              "text": "  all?: boolean;",
              "oldNo": 10
            },
            {
              "kind": "add",
              "text": "  all?: boolean; tag?: string;",
              "newNo": 10
            },
            {
              "kind": "ctx",
              "text": "}",
              "oldNo": 11,
              "newNo": 11
            }
          ]
        }
      ]
    }
  ],
  "stats": {
    "files": 2,
    "additions": 3,
    "deletions": 1
  },
  "truncated": false
}
```

### `notify`

Toast/banner for the player. `need_user` = a decision is waiting (play a bell).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `level` | [NotifyLevel](#notifylevel) | yes |  |
| `text` | string | yes |  |
| `decisionId` | string | no |  |
| `ts` | integer | yes | epoch milliseconds |

```json
{
  "v": 1,
  "type": "notify",
  "level": "need_user",
  "text": "Marlow: Merge t2 \"Tag parser module\" into main?",
  "decisionId": "d2",
  "ts": 1790850120000
}
```

### `design.upsert`

A building design was requested or progressed (status, step) or finished. Replace by `design.id`. On `done` the blueprint files are already in `request.outDir`: reload blueprints.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `design` | [Design](#design) | yes |  |

```json
{
  "v": 1,
  "type": "design.upsert",
  "design": {
    "id": "d7",
    "request": {
      "kind": "single",
      "wings": 1,
      "style": "cabin",
      "materials": "agentcraft",
      "features": [
        "porch",
        "big_windows"
      ],
      "maxSize": {
        "x": 24,
        "y": 16,
        "z": 20
      },
      "name": "Lakeside Cabin",
      "notes": "cosy, a reading nook by the fire",
      "outDir": "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints"
    },
    "status": "done",
    "step": "done: checker OK, 3 previews",
    "blueprintId": "gen_lakeside_cabin",
    "size": {
      "x": 23,
      "y": 14,
      "z": 19
    },
    "previews": [
      "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints\\gen_lakeside_cabin.preview-iso.png",
      "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints\\gen_lakeside_cabin.preview-top.png",
      "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints\\gen_lakeside_cabin.preview-front.png"
    ],
    "createdAt": 1790850000000,
    "updatedAt": 1790850480000
  }
}
```

### `foreman.status`

Backend/auth status changed (banner).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `status` | [ForemanStatus](#foremanstatus) | yes |  |

```json
{
  "v": 1,
  "type": "foreman.status",
  "status": {
    "version": "0.1.0",
    "backend": "claude",
    "auth": "failed",
    "message": "Claude login check failed: not logged in. Run `claude` and /login, then restart the Foreman."
  }
}
```

### `leads.update`

Lead assignments changed (lead.assign / lead.release / lead.sync, or the Foreman dropped a lead that is no longer configured). Full list; also in `snapshot.leads`. A lead agent (role `lead`) other than marlow is in `snapshot.agents` and gets `agent.upsert` only while it is assigned; a released lead gets one last `agent.upsert` (active=false, lounge) and should walk home and despawn.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `leads` | [LeadAssignment](#leadassignment)[] | yes | the full list (replace): marlow first, then each assigned building lead |

```json
{
  "v": 1,
  "type": "leads.update",
  "leads": [
    {
      "leadId": "marlow",
      "repos": []
    },
    {
      "leadId": "ines",
      "building": "New World/b3",
      "repos": [
        "demo-app"
      ],
      "world": "New World",
      "lastSync": 1790850060000
    },
    {
      "leadId": "bram",
      "building": "Dev HQ/b7",
      "repos": [
        "api",
        "web"
      ],
      "world": "Dev HQ",
      "lastSync": 1790418000000
    }
  ]
}
```

### `config.changed`

config.set changed config.json (any client). Clients showing settings fetch them again (`config.get`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `keys` | string[] | yes | the keys config.set changed (repository keys as "repo:<repoId>:<key>") |
| `restartRequired` | string[] | yes | every changed key still waiting for a restart (the full list, same as foreman.status.restartRequired) |

```json
{
  "v": 1,
  "type": "config.changed",
  "keys": [
    "claude.workerModel",
    "claude.leads"
  ],
  "restartRequired": [
    "claude.leads"
  ]
}
```

### `ack`

Reply to any client message that carried an `id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `re` | string | yes | the `id` of the client message being acknowledged |
| `ok` | boolean | yes |  |
| `error` | string | no |  |
| `result` | map<string, any> | no | e.g. {goalId} for goal.submit, {repoId} for repo.add |

```json
{
  "v": 1,
  "type": "ack",
  "re": "c12",
  "ok": true,
  "result": {
    "goalId": "g2"
  }
}
```

### `error`

A client message was invalid or failed (also sent as ack.ok=false when it had an id).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `message` | string | yes |  |
| `re` | string | no |  |

```json
{
  "v": 1,
  "type": "error",
  "message": "no agent named \"kitt\"",
  "re": "c13"
}
```

## Mod -> Foreman

### `hello`

First message after connecting. The Foreman replies with `snapshot`, then streams upserts.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `modVersion` | string | yes |  |
| `protocol` | 1 | yes |  |
| `client` | string | no | "mod" \| "cli" \| ... (informational) |
| `token` | string | no | the client token: the contents of the file the run file names in `tokenFile` (`<dataDir>/client.token`, new on every Foreman start). Without a valid token the connection is read-only: snapshot and events, and only `hello`, `diff.request`, `goal.digest` and `agent.logs.request`; every other message is refused (`ack.ok` false, "read-only connection: no client token") |

```json
{
  "v": 1,
  "type": "hello",
  "modVersion": "0.1.0",
  "protocol": 1,
  "client": "mod",
  "token": "EXAMPLE-not-a-real-token-0123456789abcdef"
}
```

### `goal.submit`

New goal for the lead (console: plain text). Acked with `{goalId}`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `text` | string | yes |  |
| `repoId` | string | no | defaults to repos[0], else the only/most recently added repo |
| `repos` | string[] | no | the repositories the goal is for (e.g. a group building's); the first becomes repoId when repoId is absent. The goal's lead is the lead of repoId |
| `branch` | string (#RRGGBB) | no | continue this branch of the user's (same as an "on <branch>:" prefix; wins over it) |
| `instructions` | string[] | no | standing instructions from the start (see goal.instructions) |

```json
{
  "v": 1,
  "type": "goal.submit",
  "id": "c12",
  "text": "Add a --version flag to the CLI",
  "repoId": "demo-app",
  "repos": [
    "demo-app",
    "notes-api"
  ],
  "branch": "feature/version-flag",
  "instructions": [
    "No new dependencies"
  ]
}
```

### `goal.message`

A message to the goal's lead about that goal. It runs as a turn of the lead's session for the goal (queued behind its other work; also for done/cancelled goals). The user's message and the lead's replies arrive as `feed.add` items with kind `message` and `goalId`. Acked with `{goalId, leadId}`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `goalId` | string | yes |  |
| `text` | string | yes |  |

```json
{
  "v": 1,
  "type": "goal.message",
  "id": "c25",
  "goalId": "g1",
  "text": "Is the tag parser case-insensitive?"
}
```

### `goal.instructions`

Replace the goal's standing instructions (`Goal.instructions`). A change is sent to the lead as a goal message. Acked with `{goalId, changed}`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `goalId` | string | yes |  |
| `instructions` | string[] | yes | the full list (replace); blank lines are dropped |

```json
{
  "v": 1,
  "type": "goal.instructions",
  "id": "c26",
  "goalId": "g1",
  "instructions": [
    "No new dependencies",
    "Keep the CLI output under 80 columns"
  ]
}
```

### `goal.plan`

Write the goal's plan note as the user (creates it when missing; `memory.upsert`, `Goal.planId`), then send the lead a goal message with a unified diff of the change. Acked with `{goalId, planId, changed}`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `goalId` | string | yes |  |
| `body` | string | yes | the plan note's new markdown body |

```json
{
  "v": 1,
  "type": "goal.plan",
  "id": "c27",
  "goalId": "g1",
  "body": "# Plan: #tags\n\n- t2 Tag parser module - Kit\n- t3 `list --tag` - Juniper\n- t4 docs - Tove"
}
```

### `goal.cancel`

Cancel every open task of the goal (running workers stop, worktrees and branches kept, open decisions withdrawn) and set it `cancelled`. Refused for a done goal. Acked with `{goalId, cancelled: [taskIds]}`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `goalId` | string | yes |  |

```json
{
  "v": 1,
  "type": "goal.cancel",
  "id": "c28",
  "goalId": "g1"
}
```

### `goal.digest`

What happened since `since` ("since you were away"). Acked with a `Digest` as `result` (`{since, until, goals}`); built from the feed, tasks and decisions, no model call.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `goalId` | string | no | one goal; omitted = every goal with activity in the window |
| `since` | integer | yes | epoch milliseconds |

```json
{
  "v": 1,
  "type": "goal.digest",
  "id": "c29",
  "since": 1790850000000
}
```

### `repo.remove`

Unregister a repository. Refused while it has open tasks (not done/cancelled) or a goal that is still planning. Worktrees, branches and lead assignments are left alone; there is no removal broadcast: other clients see it in their next snapshot. Acked with `{repoId}`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `repoId` | string | yes |  |

```json
{
  "v": 1,
  "type": "repo.remove",
  "id": "c30",
  "repoId": "demo-app"
}
```

### `user.message`

Message an agent (console: `@name text`) or everyone.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `to` | string | yes | agent id or "all". With "all", a leading "@name" in text routes to that agent. |
| `text` | string | yes |  |

```json
{
  "v": 1,
  "type": "user.message",
  "id": "c13",
  "to": "all",
  "text": "@kit please also cover #tags with emoji"
}
```

### `decision.answer`

Answer an open decision. Merge decisions: option "Merge" merges, "Request changes" sends `text` back to the worker, "Reject" abandons the branch.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `decisionId` | string | yes |  |
| `option` | string \| integer | no | option label (preferred) or 0-based index into Decision.options |
| `text` | string | no | free text (questions) or feedback (merge "Request changes") |

```json
{
  "v": 1,
  "type": "decision.answer",
  "id": "c14",
  "decisionId": "d2",
  "option": "Request changes",
  "text": "Export TAG_RE so format.ts can reuse it."
}
```

### `task.action`

Steer a task from the Task Wall.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `taskId` | string | yes |  |
| `action` | `reassign` \| `cancel` \| `retry` \| `prioritize` | yes |  |
| `arg` | string | no | reassign: agent id; prioritize: integer priority (default: bump to top) |

```json
{
  "v": 1,
  "type": "task.action",
  "id": "c15",
  "taskId": "t5",
  "action": "reassign",
  "arg": "wren"
}
```

### `agent.action`

Pause/resume/stop an agent, or spawn (activate) an off-shift worker.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agentId` | string | yes |  |
| `action` | `pause` \| `resume` \| `stop` \| `spawn` | yes | pause: abort the current turn, keep the task (open questions are withdrawn); resume continues it. stop: off shift (active=false) until resume/spawn: turn aborted, open questions/permission prompts withdrawn, its doing tasks go back to the board and the next worker continues from the same branch. spawn: bring an off-shift agent onto the team. |
| `arg` | string | no | spawn: optional task id to assign to the agent |

```json
{
  "v": 1,
  "type": "agent.action",
  "id": "c16",
  "agentId": "juniper",
  "action": "pause"
}
```

### `diff.request`

Ask for the structured diff of a worktree. Answered with `diff` (same requestId).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `requestId` | string | yes |  |
| `repoId` | string | yes |  |
| `worktree` | string | yes | worktree id (e.g. "kit-t2"); an agent id resolves to that agent's current worktree |

```json
{
  "v": 1,
  "type": "diff.request",
  "id": "c17",
  "requestId": "r7",
  "repoId": "demo-app",
  "worktree": "kit-t2"
}
```

### `repo.add`

Register a local git repo (console: `/repo add <path>`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `path` | string | yes |  |

```json
{
  "v": 1,
  "type": "repo.add",
  "id": "c18",
  "path": "C:\\Projects\\agentcraft\\sandbox\\demo-app"
}
```

### `design.request`

Design a new building blueprint (hub: Buildings -> Design new). Acked with `{designId}`; progress arrives as `design.upsert`. One design runs at a time; later ones queue.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `request` | [DesignRequest](#designrequest) | yes |  |

```json
{
  "v": 1,
  "type": "design.request",
  "id": "c19",
  "request": {
    "kind": "single",
    "wings": 1,
    "style": "cabin",
    "materials": "agentcraft",
    "features": [
      "porch",
      "big_windows"
    ],
    "maxSize": {
      "x": 24,
      "y": 16,
      "z": 20
    },
    "name": "Lakeside Cabin",
    "notes": "cosy, a reading nook by the fire",
    "outDir": "C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints"
  }
}
```

### `design.cancel`

Cancel a queued or running design (the design agent's turn is stopped; nothing is written to outDir).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `designId` | string | yes |  |

```json
{
  "v": 1,
  "type": "design.cancel",
  "id": "c20",
  "designId": "d7"
}
```

### `pr.refresh`

Poll the pull request(s) of tasks in status `pr` now instead of at the next interval (claude backend with PR watching on). Changes arrive as `task.upsert`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `taskId` | string | no | the task whose PR to poll now; omitted = every task in status `pr` |

```json
{
  "v": 1,
  "type": "pr.refresh",
  "id": "c21",
  "taskId": "t4"
}
```

### `lead.assign`

A building holding repositories was placed (or its repositories changed). Acked with `{leadId}`. Idempotent: the same `building` keeps its lead and gets its repos updated. A new building takes the first free lead in `claude.leads` order; when none is free the ack says `{leadId: "marlow", overflow: true}` and nothing is stored. A repository listed here leaves any other building that had it; a building left with no repository (here with `repos: []`, or because its last one moved) frees its lead. Open goals (planning / active) whose repository (`repoId`, else `repos[0]`) is in the building move to its lead (feed line per goal); new goals in these repositories go to that lead too.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `building` | string (#RRGGBB) | yes | "<worldId>/<buildingId>" (worldId = the save folder name, so two worlds on one Foreman do not collide) |
| `repos` | string[] | yes | repository ids the building holds |

```json
{
  "v": 1,
  "type": "lead.assign",
  "id": "c22",
  "building": "New World/b7",
  "repos": [
    "api",
    "web"
  ]
}
```

### `lead.release`

The building was removed. Acked with `{}` (also for a building that has no lead). Its lead goes off shift; its open goals move to marlow (feed line; marlow gets the plan note when it takes over).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `building` | string (#RRGGBB) | yes | "<worldId>/<buildingId>" (worldId = the save folder name, so two worlds on one Foreman do not collide) |

```json
{
  "v": 1,
  "type": "lead.release",
  "id": "c23",
  "building": "New World/b7"
}
```

### `config.get`

The editable settings (hub Team / Settings tabs, Repos "Edit settings"). Acked with `{file, settings: SettingDef[]}`: the global settings, or with `repoId` that repository's repoSettings. Never contains secret values (environment values, tokens, MCP server env or arguments).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `repoId` | string | no | that repository's repoSettings instead of the global settings |

```json
{
  "v": 1,
  "type": "config.get",
  "id": "c31"
}
```

### `config.set`

Change settings. Every change is validated first (all or nothing: one bad change refuses the lot, `ack.error` lists the problems), then config.json is written atomically (previous file kept as `config.json.bak`; unknown keys, other sections and key order kept), `live` keys apply at once (from the next turn / poll), and the ack is `{applied: [key], restartRequired: [key], overridden: [{key, by}]}` (a key a flag or variable also sets is written but stays overridden). Then `config.changed` is broadcast and `foreman.status.restartRequired` updated. Repository changes go to `repoSettings[<the repo's path as config.json spells it, else its absolute path>]`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `repoId` | string | no | change that repository's repoSettings |
| `changes` | { key: string, value: any }[] | yes |  |

```json
{
  "v": 1,
  "type": "config.set",
  "id": "c32",
  "changes": [
    {
      "key": "claude.workerModel",
      "value": "sonnet"
    },
    {
      "key": "claude.agents.kit.effort",
      "value": "high"
    },
    {
      "key": "claude.leads",
      "value": [
        "marlow",
        "ines"
      ]
    }
  ]
}
```

### `foreman.restart`

Restart the Foreman with the same arguments, environment and working directory (except `--reset`, `--goal` and `--autostart`). Acked with `{}` first; then the server closes (clients see the connection drop and reconnect), running turns are interrupted and resumed on start (`resumeOnStart`), and a new Foreman process (new pid, new client token: read the run file again) takes over the same port.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |

```json
{
  "v": 1,
  "type": "foreman.restart",
  "id": "c33"
}
```

### `agent.logs.request`

Older entries of an agent's log (the full history the Foreman stored, across one rotation: `logs/<agent>.jsonl` and `<agent>.1.jsonl`), for a scrollable log view; read-only (allowed without the client token). Acked with `{agentId, entries: LogEntry[], more}`: `entries` oldest first, all older than `before`; `more` = older entries exist (ask again with `before` = the first entry's `ts`). An unknown agent is refused.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agentId` | string | yes |  |
| `before` | integer | no | only entries older than this (the `ts` of the oldest entry the client has); omitted = the newest |
| `limit` | integer | no | at most this many entries (default 200); entries sharing the oldest one's `ts` are never split, so a page can be slightly longer |

```json
{
  "v": 1,
  "type": "agent.logs.request",
  "id": "c36",
  "agentId": "kit",
  "before": 1790850000000,
  "limit": 200
}
```

### `repo.agents`

The repository's Claude Code agent files (`.claude/agents/*.md` in its checkout), for the roles picker. Acked with `{agents: [{id, name, path, description?, model?}]}`: `id` is the file name without `.md` (the value to store in `roles.<agent>`), `name` the front matter name (else the id), `path` repo-relative.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `repoId` | string | yes |  |

```json
{
  "v": 1,
  "type": "repo.agents",
  "id": "c34",
  "repoId": "demo-app"
}
```

### `lead.sync`

Sent by the mod on connect for its world: every `"<world>/..."` building not in the list is released first, then each listed building is assigned (as `lead.assign`). Acked with `{leads}` (building -> lead id). Also records the world's `lastSync`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `world` | string (#RRGGBB) | yes | the world id (save folder name) |
| `buildings` | { building: string (#RRGGBB), repos: string[] }[] | yes | every building of that world that holds repositories |

```json
{
  "v": 1,
  "type": "lead.sync",
  "id": "c24",
  "world": "New World",
  "buildings": [
    {
      "building": "New World/b3",
      "repos": [
        "demo-app"
      ]
    },
    {
      "building": "New World/b7",
      "repos": [
        "api",
        "web"
      ]
    }
  ]
}
```

### `lead.releaseWorld`

Release every lead held by buildings of another world (hub Team tab "Release" next to a world in `leads.update` that is not the current one). Acked with `{released: [leadId]}`; their open goals move to marlow as with `lead.release`. Worlds that have not synced for `claude.leadWorldTtlDays` (default 14; 0 = never) are released automatically at start and daily.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `world` | string (#RRGGBB) | yes | the world id (save folder name) whose leads to release |

```json
{
  "v": 1,
  "type": "lead.releaseWorld",
  "id": "c35",
  "world": "Dev HQ"
}
```

## Console mapping (mod)

| console input | message |
| --- | --- |
| plain text | `goal.submit {text}` |
| `@name text` | `user.message {to:"all", text:"@name text"}` (the Foreman routes it) or `{to:"name", text}` |
| `/answer [dN] <n\|label> [text]` | `decision.answer {decisionId, option, text?}` |
| `/repo add <path>` | `repo.add {path}` |
| `/pause @name`, `/resume @name`, `/stop @name`, `/spawn @name [taskId]` | `agent.action` (spawn: `arg` = task id) |
| `/task <id> cancel\|retry\|prioritize [n]\|reassign <agent>` | `task.action` |

