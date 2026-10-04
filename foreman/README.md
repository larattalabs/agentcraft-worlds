# AgentCraft Foreman

The Foreman is the brain of AgentCraft: a Node 22 + TypeScript service that runs a team of Claude
agents (one lead, up to five workers) on a real git repo and streams everything to the Minecraft
mod over a WebSocket. The game is only a view. The Foreman owns all state, keeps working while
Minecraft is closed, and survives restarts.

```
                     ws://127.0.0.1:7878  (protocol v1, docs/protocol.md)
 Minecraft mod  <------------------------------------------------>  Foreman
 (or npm run tui)     hello -> snapshot, then upserts                  |
                      goal.submit / decision.answer / ...              |
                                                                       v
   Foreman (src/foreman.ts) -- owns state, applies user intents, exposes primitives to backends
     |- TaskGraph     src/taskgraph.ts   tasks, deps, statuses, assignment, goal progress
     |- MessageBus    src/bus.ts         agent<->agent, agent<->user, activity feed
     |- Memory        src/memory.ts      markdown notes, shared + per agent
     |- DecisionQueue src/decisions.ts   question | permission | merge; answer wakes the agent
     |- RepoManager   src/repos.ts       repos, per-task worktrees, structured diffs, guarded merges
     |- Notifier      src/notifier.ts    desktop notification + console bell when you are needed
     |- Store         src/store.ts       atomic JSON state + JSONL logs under AGENTCRAFT_HOME
     `- Backend       claude: src/agents/claude/  (Claude Agent SDK sessions)
                      sim:    src/agents/sim/     (deterministic scripted team, real git)
```

## Run it

```sh
cd foreman
npm install

# real agents: needs ANTHROPIC_API_KEY (or CLAUDE_CODE_USE_BEDROCK / _VERTEX / _FOUNDRY)
npm run start -- --backend claude --repo C:\path\to\your\repo
# personal use only: your local `claude` CLI login instead of an API key
npm run start -- --backend claude --repo C:\path\to\your\repo --use-claude-login

# simulated team on a fresh sandbox repo (no API calls) - for demos and screenshot QA
npm run start -- --backend sim --reset --speed 2

# static "showcase" states for screenshots (fast-forward through the real script, then hold)
npm run start -- --backend sim --profile showcase --reset --showcase            # busy mid-run
npm run start -- --backend sim --profile showcase-late --reset --showcase late  # blocked/error/done

# terminal client that connects exactly like the mod
npm run tui
```

In the TUI (and in the mod's console) type:

| input | does |
| --- | --- |
| `Add OAuth to life-tracker` | new goal for the lead |
| `@kit please also cover emoji tags` | message an agent (`@all` for everyone) |
| `/answer` / `/answer d3 2 more tests please` | answer the oldest / a specific decision (1-based option, optional text) |
| `/diff kit-t2` or `/diff d3` | show a worktree's diff (or a merge decision's) |
| `/repo add C:\path\to\repo` | connect a repo |
| `/pause @kit`, `/resume @kit`, `/stop @kit`, `/spawn @tove` | steer agents (see Steering) |
| `/task t3 cancel\|retry\|prioritize [n]\|reassign @wren` | steer tasks |
| `/status`, `/tasks`, `/agents`, `/decisions`, `/memory [id]`, `/feed` | views |

`npm run tui -- --script scripts/sim-demo.script --transcript out.txt` replays a whole session
unattended (`/wait d3`, `/wait goal done`, `/wait 2` are available in scripts);
`--commands <file>` tails a file for commands; `--auto-answer merge,permission` answers for you.

Stop the Foreman with Ctrl+C (or `q` + Enter). State is saved continuously; a hard kill loses at
most ~100 ms of state, and interrupted agent turns resume on the next start.

## Options

`npm run start -- --help` prints everything. The important ones:

| flag / env | default | |
| --- | --- | --- |
| `--backend sim\|claude` / `AGENTCRAFT_BACKEND` | `claude` | |
| `--port` / `AGENTCRAFT_PORT` | `7878` | WebSocket port (127.0.0.1 only) |
| `--home` / `AGENTCRAFT_HOME` | `~/.agentcraft` | state root |
| `--user-name` / `AGENTCRAFT_USER_NAME` / config `userName` | OS user name | how the agents address you; sent to the mod in `foreman.status` |
| `--profile` | backend name | state lives in `<home>/<profile>` |
| `--repo <path>[,<path>]` | | register repos at start (sim: a fresh `sandbox/sim-demo`) |
| `--goal "<text>"` | | submit a goal right away |
| `--reset` | | wipe this profile first |
| `--notify` / `--no-notify` / `AGENTCRAFT_NOTIFY` | on for claude, off for sim | Windows or macOS notifications |
| `--toast-silent` | | toast without sound |
| `--model`, `--lead-model`, `--worker-model` | lead `opus`, workers `sonnet` | any model id/alias the CLI accepts |
| `--design-model` / `AGENTCRAFT_DESIGN_MODEL` / `claude.designModel` | the worker model | the building design agent (below) |
| `--effort low..max` | `medium` | |
| `--workers 3` or `--workers kit,wren` | `juniper,kit,wren` | team (others stay "off shift") |
| `--max-concurrent` | `3` | workers running at once |
| `--throttle-concurrent` | `1` | workers at once while your plan (claude.ai login) reports a usage warning (leads then take turns one at a time); at the limit itself nobody starts a turn until it resets, and interrupted turns resume then |
| `--leads <ids>` / `AGENTCRAFT_LEADS` / `claude.leads` | `marlow,ines,bram,cass` | leads in use, in assignment order: marlow leads home, repos without a building and anything not tied to a repo; each other lead leads one building the mod assigns it (below). `marlow` alone = one lead for everything |
| `--max-concurrent-turns` / `claude.maxConcurrentTurns` | no cap | agent turns at once, leads and workers together |
| `--max-turns`, `--max-budget <usd>` | 40 lead / 80 worker, none | per turn caps |
| `--ci "<cmd>"` | detected (`npm`/`pnpm`/`yarn`/`bun test` by lockfile, `cargo test`, ...) | run after each task (a repo's `repoSettings` `ci` wins) |
| `--no-lead-review` | | merge decisions go to you without a lead review turn |
| `--pr-watch off\|observe\|on` | `observe` | pull requests of tasks landed as PRs (`land: "pr"`): observe = poll + Marlow's triage, nothing posted or started; on = fold-ins and approved replies too; off = a PR finishes its task (config `claude.prWatch`) |
| `--pr-poll-seconds` | `180` | how often watched PRs are polled (config `claude.prPollSeconds`) |
| `--repo-poll-ms` | `10000` | how often checkouts are checked for head/dirty changes |
| `--no-client-token` | | dev only: every local WebSocket client may change things (see Client token) |
| `--merge-style merge\|squash` / `AGENTCRAFT_MERGE_STYLE` | `merge` | approved merges: a merge commit that keeps the agents' commits, or one squashed commit (see Safety guarantees) |
| `--no-sign-merges` / `AGENTCRAFT_SIGN_MERGES=0` | signed if your git config signs (claude) | never sign approved merge commits; the sim never signs |
| sim: `--speed`, `--seed`, `--autostart`, `--showcase [late]`, `--auto-answer`, `--no-ambient` | | |

`<home>/config.json` can hold the same settings (`{"backend":"claude","claude":{"workers":["kit","wren"]}}`).

Per-repo settings go under `repoSettings`, keyed by the repository path. They live in your config, not
in the repo, so an agent cannot change what the Foreman runs by editing its worktree:

```json
{ "repoSettings": { "~/code/app": {
    "ci": "pnpm -r test",
    "setup": "pnpm install --frozen-lockfile",
    "copy": [".env", ".env.local"],
    "setupTimeoutMs": 600000 } } }
```

A repository's own Claude Code agent files (`.claude/agents/*.md`) can be used two ways:

```json
{ "repoSettings": { "~/code/game": {
    "roles": { "kit": "dg-architect", "juniper": "dg-builder" },
    "subagents": "repo" } } }
```

`roles` makes an agent file a worker's role in that repository: its prompt goes into the worker's
system prompt, its model and effort win over the agent's profile (a sized task still wins), and the
lead's team list shows the role's description. The file is read from the worker's worktree (the lead:
the checkout), so it follows the branch. `"subagents": "repo"` lets agents working there use the
repository's other agent files as subagents (on top of `claude.subagents`, even if that is off).

For a workspace of several repos with local-only setup, three more per-repo settings:

```json
{ "repoSettings": { "~/work/api": {
    "baseBranch": "dev",
    "protect": ["App/appsettings.json", "local-config/"],
    "env": { "PATH": "~/.local/share/mise/installs/node/16/bin:$PATH" } } } }
```

`baseBranch` is what agents start from and land into, whatever your checkout has checked out (the
local branch is created from `origin/<base>` if needed). `protect` paths are never committed:
AgentCraft's commits leave them out, editing them asks first, and a branch that commits one goes
back to the worker before review. `env` applies to the agents' shells, setup and CI (`GIT_*` is
ignored). `CLAUDE.md` / `AGENTS.md` in the folders above a repository (a workspace holding several
repos, up to your home folder) are read too, and that folder is readable for agents
(`claude.context.workspaceInstructions`, default on).

Landing as pull requests instead of local merges:

```json
{ "repoSettings": { "~/work/api": {
    "baseBranch": "dev", "land": "pr",
    "pr": { "branchPrefix": "feat/", "squash": true, "draft": false, "remote": "origin" } } } }
```

With `land: "pr"` workers start from the server's base (`origin/<base>`, fetched by the Foreman when
the worktree is made). When you approve ("Merge" on the decision), the Foreman fetches again, checks
for conflicts (a conflict goes back to the worker), pushes the branch (`branchPrefix` + the task slug)
and opens the pull request with the remote's own CLI and your login: `az repos pr create` for Azure
DevOps, `gh pr create` for GitHub (other remotes: pushed only). `squash` pushes one commit authored
by you alone (no co-author trailers). The PR title is the task's title and its description the
worker's summary: no AgentCraft or Claude attribution anywhere (no `Co-authored-by`, no footer; the
agents run with Claude Code's `attribution` turned off, so their own commits carry none either).
Without `squash` the agents' own commits are pushed as they are, authored `AgentCraft <Name>`: use
`squash` for repositories where that should not show. The push never overwrites a remote branch
AgentCraft did not push itself. Agents still never push: only the Foreman does, and only after your
approval.

#### Watching the pull requests (`claude.prWatch`, docs/PRWATCH.md)

With the claude backend a task landed as a PR is not done yet: it moves to status `pr` ("PR open" on
the task wall) and the Foreman polls the PR every `claude.prPollSeconds` (default 180, flag
`--pr-poll-seconds`; `pr.refresh` polls now). Merged: the task is `done` (so a goal finishes when its
PRs merge, and a task that depends on it starts only then). Abandoned: the task is `cancelled`.

New comment threads (a reviewer's, or the newest automated "Claude Code Review" comment, parsed into
findings by severity) and checks that turn failing go to Marlow in a `triage` turn, with the PR's
diff. Marlow answers with the `triage` tool, per item: `fold_in` (fix it in code), `reply` (a drafted
reply), `ask_user` (your decision), `ignore`. With `claude.prWatch: "on"`:

- all fold-ins of a PR go back to the task's worker as ONE follow-up, continuing the task's branch;
  CI and Marlow's review see only the follow-up; after your approval it is pushed as an ADDED commit
  on the PR ("<task>: address review feedback on PR #N"; squash repos: one commit by you on top of
  the PR's commit, never a re-squash or force-push);
- replies and thread resolutions wait for ONE decision per PR ("Post 3 replies and resolve 2 threads
  on PR #612?"); threads fixed by a fold-in are answered ("Addressed in <commit>") and resolved after
  its push lands. Azure DevOps: `az devops invoke` (thread comments POST, thread status PATCH);
  GitHub: `gh api` replies (resolving GitHub review threads is not done);
- loop guard: after `prReview.maxRounds` (default 2) fold-in rounds from automated reviews, or when
  a round would only address minor items, the next review asks you instead of Marlow. A PASS with
  nothing above minor ends the automated rounds.

`"observe"` (the default) does all the reading and the triage turns, but posts nothing, asks you
nothing and starts no fold-in: Marlow's verdicts go to the feed and to a shared memory note
"PR triage <task> #<n> (observe)". `"off"`: landing a PR finishes the task, nothing is polled.

```json
{ "claude": { "prWatch": "observe", "prPollSeconds": 180 },
  "repoSettings": { "~/work/api": { "land": "pr",
    "prReview": { "autoSeverities": ["critical", "important"], "maxRounds": 2 } } } }
```

Check what the watcher would see on an existing PR (read-only, your own az/gh login):
`npm run pr-probe -- https://dev.azure.com/<org>/<project>/_git/<repo>/pullrequest/<id>`.

The lead reads code in a read-only view of each repository's base (a detached worktree at
`<profile>/worktrees/<repo>/_lead`, refreshed before every lead turn; PR repos: the freshly fetched
`origin/<base>`), so it plans and reviews against what workers start from rather than your checkout,
which may be on another branch with work in progress.

A goal that starts with `on <branch>:` (e.g. "on feat/tag-filter: finish the animation") continues
one of your branches, e.g. one you started in Claude Desktop. The branch must exist locally or on the
remote (then a local branch is made from it). The lead plans against it, its tasks start from it, and
approving a task adds the work to it (squashed if the repo squashes PRs): in the checkout that has
it checked out, only when that checkout is clean; then, if the branch is on the remote, it is pushed
(fast-forward only) so a PR you already opened updates. No new PR is opened. The lead can also put a
single task on one of your branches (`create_task` `base`).

A goal can span registered repositories: the lead sees all of them (with base and landing mode),
gives each task its repository (`create_task` `repo`), and orders them with deps; every registered
repository is readable for agents.

`copy` brings untracked files from your checkout into each new worker worktree. `setup` runs once per
new worktree before the worker's first turn (git network access stays off; a failure is shown to the
worker rather than stopping it). `ci` replaces `--ci` and detection for that repo.

### Agent context (`claude.context`)

Agents run with Claude Code's own settings off (`settingSources: []`): settings-file allow rules and
repo hooks would run ahead of, or outside, the permission policy. What they do get is set here:

```json
{ "claude": { "context": {
    "repoInstructions": true,
    "userInstructions": false,
    "files": ["~/.codex/AGENTS.md"],
    "maxChars": 24000,
    "skills": ["roadmap-keeper", "~/somewhere/my-skill"],
    "mcpServers": { "xcode": { "command": "xcodebuildmcp", "args": ["mcp"] } },
    "mcpAllow": ["mcp__xcode__list_sims"] } } }
```

- **Instructions:** the repository's `CLAUDE.md`, `.claude/CLAUDE.md` and `AGENTS.md` (on by default;
  workers read them from their worktree, so committed versions), your `~/.claude/CLAUDE.md` (off by
  default), and any `files`. `@path` imports are expanded. They are appended after the role prompt,
  which wins where they conflict.
- **Skills:** names under `~/.claude/skills` or paths. They are copied into `<profile>/agent-plugin`
  with `allowed-tools` removed from `SKILL.md` (it would pre-approve tools), and only these can be
  loaded with the Skill tool. Everything a skill then does goes through the normal permission checks.
- **claude.ai connectors** (`connectors`, e.g. `["monday.com"]`): with a claude.ai login the CLI
  would also load the account's connectors (mail, calendars, accounting...). By default agents get
  none (`strictMcpConfig`); listed ones load, and tools of any other connector are refused.
- **Earlier sessions** (`sessionHistory: true`, or `{ "enabled": true, "days": 60 }`): agents can
  search and read your earlier Claude Code / Claude Desktop sessions (`find_sessions`, `read_session`)
  from `~/.claude/projects`, only those whose folder is a registered repo, its workspace folder, or a
  Desktop scratchpad of one; AgentCraft's own agent sessions are left out. A read is condensed
  (prompts, Claude's text, commands, edits; no tool output), mostly from the end. For an
  `on <branch>:` goal the lead is handed the sessions that worked on that branch before planning.
- **MCP servers:** given to every agent. Their tools ask for permission unless listed in `mcpAllow`
  (exact names or `mcp__server__*`). The `agentcraft` server name is reserved.

### Roles (`claude.agents`, `claude.taskModels`)

```json
{ "claude": {
    "agents": {
      "marlow": { "model": "opus", "effort": "high" },
      "kit": { "title": "Backend", "prompt": "APIs, databases, the server side.", "model": "sonnet" },
      "juniper": { "title": "Frontend", "prompt": "UI, styling, accessibility." } },
    "taskModels": { "small": "haiku", "large": "opus" } } }
```

`title` is shown on the agent's nameplate. `prompt` (or, without one, the cast description) goes into
the agent's own prompt and into the lead's team list, so the lead assigns by specialty. `model` and
`effort` override the role defaults. With `taskModels`, the lead can size a task (`small`/`large` on
create_task) and that model wins for the task. Workers keep private notes across tasks; each task
prompt lists their earlier ones.

### Permissions and subagents (`claude.permissions`, `claude.subagents`)

```json
{ "claude": {
    "permissions": {
      "mode": "auto",
      "allow": ["Bash(codex exec:*)"], "deny": [], "ask": [],
      "webTools": true,
      "protectCheckouts": true },
    "subagents": { "enabled": true, "agents": ["gate-verifier", "~/agents/reviewer.md"] } } }
```

- `mode: "policy"` (default): every tool call goes through AgentCraft's policy; what it cannot verify
  as safe asks you in-world.
- `mode: "auto"`: Claude Code's auto mode. A classifier decides what the policy would have asked
  about. A PreToolUse hook keeps the guardrails that apply whatever the classifier, your rules or a
  subagent do: everything the policy denies (git push, git safety settings, the lead editing),
  git internals (`.git`, `GIT_DIR`, `--git-dir`), and with `protectCheckouts` writes into your
  checkouts of registered repos or AgentCraft's own state; those still ask you. Classifier denials
  show on the agent's monitor.
- `allow` / `deny` / `ask`: Claude Code permission rules, in both modes, after the guardrails. An
  allow rule skips AgentCraft's policy for what it matches.
- `webTools`: WebFetch and WebSearch (asked per host in policy mode).
- `subagents`: agents may start Claude Code subagents (built-ins such as Explore, plus the agent files
  listed: names under `~/.claude/agents` or paths). Their tool calls go through the same policy and
  guardrails; they never get a worktree of their own. A `permissionMode` in an agent file is ignored.

While running, `<home>/<profile>/foreman.json` records `{pid, port, host, backend, profile, version, startedAt}`
so launch scripts can find it; `<home>/foreman.json` holds the same for the first live Foreman (when
it exits, another live profile takes its place). A second Foreman on a profile that is already
running is refused (two would both write its `state.json`).

## How the claude backend works

1. **Plan** (lead, read-only in your checkout): explores with Read/Grep/Glob, writes `Plan: ...` to
   shared memory, creates tasks with deps and assignees via `create_task`, may `ask_user`.
2. **Work** (worker, in its own worktree `<profile>/worktrees/<repo>/<agent>-<task>` on branch
   `agentcraft/<agent>/<task>-<slug>`): edits, runs tests, coordinates with `send_message`, finishes
   with `update_task(status "review")`. A worker that ends without it is nudged once.
3. **CI**: the repo's test command runs in the worktree; a failure goes back to the worker once.
4. **Review** (lead): gets the diff + CI result (and the task's history: who worked on it, what you
   already answered), then `request_merge` or asks for changes.
5. **Merge decision** (you): Merge / Request changes / Reject. Only an answered **Merge** merges.
   If the base moved on and the merge would conflict, the worker merges the base into its branch,
   resolves it, and the task comes back for review. That is what lets the lead plan tasks that
   touch the same files (a new CLI case, a help line) to run in parallel instead of in a chain.

A task that changed no files (a report, an investigation) has nothing to merge: `request_merge`
closes it as done (worktree abandoned, branch kept) instead of asking you to approve an empty
merge, and **Merge** on such a branch does the same. A goal whose tasks were all cancelled or
rejected becomes `cancelled` (it is active again if the lead adds a task to it).

Agent tools (in-process MCP server `agentcraft`): `send_message`, `ask_user` (blocks until you
answer), `write_memory`, `read_memory`, `update_task`, `report_status`, `list_tasks`, and for the
leads `create_task`, `request_merge`, `triage`. Unread messages ride along on every tool result and on the
prompt of the agent's next turn. A message from you that arrives after an agent's last tool call
(e.g. while it writes its final summary) starts a follow-up turn as soon as that turn ends; one
sent to an off-shift agent is delivered when you `/resume` it.

The CLI process of every agent turn is spawned by the Foreman (the SDK's
`spawnClaudeCodeProcess`), so the Foreman knows its pid: an aborted turn (stop, pause, cancel,
timeout, Foreman shutdown) is closed, and its CLI and every process it started are killed if they
are still there a few seconds later (a process tree snapshot taken at abort time, plus a second
look after the CLI exited, also finds orphans the CLI left behind; a pid is only killed if its
creation time still matches, and never if the CLI's pid was reused). The next turn of that agent
(e.g. after `/pause` + `/resume`) waits for this clean-up, so two CLIs never share a session.

SDK stream -> world: Read/Grep/Glob -> `reading@library`, Edit/Write -> `editing@desk`, test
commands -> `testing@testbench`, other Bash -> `running@terminal`, `ask_user` -> `waiting_user@user`.

Sessions are persisted per (agent, task) and per (lead, goal). On restart, interrupted turns resume
with their session id **and their job kind** (a resumed plan still activates the goal; a resumed
review still ends in a merge decision); a question that was open across the restart resumes the
session with your answer. Start-up then reconciles every open state: a goal still planning with
nobody planning it is planned again (or activated if it has tasks), a task left `doing` with no
turn behind it goes back on the board (its session resumes), a task in `review` without a merge
decision gets CI + review again. SDK sessions are isolated from your own Claude Code settings
(`settingSources: []`).

A failed turn (API error, max turns, timeout) shows the agent as `error` and blocks its task with
the reason; `/task t3 retry` puts it back on the board.

### A lead per building (docs/PRWATCH.md)

Each building in the world that holds repositories gets its own lead; Marlow leads home,
repositories without a building and anything not tied to a repository. The workers stay one shared
pool every lead assigns from.

- The mod sends `lead.assign {building, repos}` when a building is placed, `lead.release {building}`
  when it is removed and `lead.sync {world, buildings}` on connect. A new building takes the first
  free lead in `claude.leads` order (default Ines, Bram, Cass); with none free Marlow leads it (the
  ack says `overflow`, nothing is stored). The same building again only updates its repositories; a
  repository listed by another building moves there. Assignments live in `state.json` `leads`.
- A goal belongs to the lead of its repository's building when it is submitted (`Goal.leadId`;
  absent = Marlow) and keeps that lead, even if repositories move later. Only releasing the lead
  moves its open goals to Marlow, whose first turn on each gets the plan and the board (a takeover
  note). Leads not in `claude.leads` any more are released at start.
- Every lead has its own job queue (plan, review, follow-up, PR triage) and session per goal
  (`<lead>:<goal>`), and leads run in parallel. Merge decisions, questions, feed lines and PR triage
  carry the goal's lead (the mod shows them at that building's podium). A plain console message goes
  to the current goal's lead; `@bram ...` to Bram.
- A lead's prompt names its building, its repositories and the other leads. A lead changes only its
  own goals' tasks; naming a worker who is busy on another lead's task is fine (the task waits) and
  never takes the worker off it.
- Building leads are in the snapshot's agents (and get `agent.upsert`) only while they are assigned;
  a released lead gets one last upsert (off shift) so the mod walks it home.
- The sim backend does the same: the scripted goal is run by its building's lead, and a goal for
  another building's lead runs as a short side flow (that lead plans one task, a free worker does
  it, that lead reviews it).

### Goals and repos for the hub (Goals and Repos tabs, docs/HUB.md)

- Goals carry their thread: feed items and decisions about a goal have `goalId` (its tasks, its
  lead's turns for it, its PRs, goal messages). `Goal.repos` lists every repository it touches
  (`repoId` first, then each task's repository), `Goal.prs` its tasks' pull requests,
  `Goal.planId` the plan note (the lead's shared `Plan: ...` memory, or the one you write),
  `Goal.branch` the branch it continues.
- `goal.submit` takes `repos` (the first is `repoId` and picks the lead), `branch` (same as the
  `on <branch>:` prefix) and `instructions`.
- `goal.message {goalId, text}` is a turn of the goal's lead session (`<lead>:<goal>`), queued on
  that lead's queue, also for done or cancelled goals (a released lead's goals: Marlow). One queued
  turn per goal takes every unread message when it starts; unread goal messages are queued again
  after a restart, `/resume` or an assignment. What the lead sends you in that turn is the reply;
  if it sends nothing, its final text is. Goal messages never ride along with other turns.
- `goal.instructions` (standing instructions) are in the lead's prompt for the goal and in every
  worker prompt for its tasks (a section, read each turn), and are appended to task descriptions
  created afterwards. A change is sent to the lead as a goal message.
- `goal.plan {goalId, body}` writes the plan note as you (`plan-<goal>` when there was none) and
  sends the lead the unified diff as a goal message.
- `goal.cancel` cancels the goal's open tasks (workers stop, worktrees kept), withdraws its open
  decisions, stops the lead's planning/review/triage turns for it; goal messages still work. A
  cancelled goal re-opens only when it gets open work again.
- `goal.digest {goalId?, since}` ("since you were away") is computed from the feed, tasks and
  decisions (src/digest.ts): task added/done/blocked, decisions waiting/answered, merges, PRs
  opened/commented/merged, the lead's messages to you, goal done; at most 30 lines per goal.
- `repo.remove` unregisters a repository unless it has open tasks or a goal still being planned
  (worktrees, branches and lead assignments stay; a `--repo`/`config.repos` entry adds it again at
  the next start). Every `Repo` carries `settings`, a read-only view of its `repoSettings` (env
  values never leave the Foreman: only `envKeys`).
- The sim backend does all of this too: its script tags its feed and decisions with the goal,
  records its plan note, answers goal messages from the goal's lead, and stops on `goal.cancel`.

### Client token, settings and restart (Team and Settings tabs, docs/HUB.md)

**Client token.** Any local process can open the Foreman's WebSocket, an agent's Bash command
included. So every start writes a new random token to `<home>/<profile>/client.token` (mode 0600)
and names it in the run file (`foreman.json`, field `tokenFile`). A client that sends it in
`hello` (`token`) may do everything; any other connection is **read-only**: it gets the snapshot
and the events and may send `hello`, `diff.request` and `goal.digest`; everything else is refused
(`ack.ok: false`, "read-only connection: no client token"). Tokens are compared in constant time,
never logged, and the file is removed on a clean exit. The mod reads the run file;
`tools/foremancli.mjs`, the tools' `ForemanClient`, `npm run tui`, `qa.mjs` and `shoot.mjs` find it
the same way (`--home` when the Foreman runs with another home; `AGENTCRAFT_CLIENT_TOKEN`
overrides; it is removed from the final environment of every process the Foreman starts (agents,
CI, setup, gh/az, git, notifications, a restarted Foreman), and a repository's `env` can neither set
it nor copy it in with `$AGENTCRAFT_CLIENT_TOKEN`). `--no-client-token`
restores the old behaviour for development.

**Settings (`config.get` / `config.set`, src/settings.ts).** The hub's Team and Settings tabs (and
the Repos tab's "Edit settings") edit `config.json` through the Foreman. `config.get` returns a
`SettingDef` per editable key (label, help, group, type, options, the configured value, default,
source `file`/`flag`/`env`/`default`, `overriddenBy`, `live`). It never returns secrets:
environment values are never read (only which `AGENTCRAFT_*` variables are set), MCP servers are
listed by name and command only (no arguments, env or headers), a repository's `env` by variable
name. `config.set` validates every change first (types, enums, ranges, agent ids of the cast,
model names; all or nothing), then the whole new file the way the Foreman loads it, writes it
atomically with the previous file kept as `config.json.bak` (unknown keys, other sections, key
order and an existing key's spelling such as `merge-style` kept), applies the live keys and
broadcasts `config.changed`. `null` (or `"default"` for a model or per-agent effort, `0` for the
optional caps) removes a key. A key a flag or `AGENTCRAFT_*` variable also sets is written but
stays overridden while that flag is given (the ack lists it under `overridden`). Repository
settings are written under the key `config.json` already uses for that repository (e.g.
`~/code/app`), else its absolute path. `repo.agents` lists a repository's `.claude/agents/*.md`
for the roles picker.

| applies | settings |
| --- | --- |
| live (from the next turn, tick or poll) | `claude.leadModel`, `leadEffort`, `workerModel`, `effort`, `designModel`, `taskModels.*`, `agents.<id>.{title,prompt,model,effort}` (a title change updates the nameplate at once), `maxConcurrent`, `throttleConcurrent`, `maxConcurrentTurns`, `leadReview`, `maxBudgetUsdPerTurn`, `prWatch` / `prPollSeconds` (the watcher switches over at once), `permissions.{mode,allow,deny,webTools,protectCheckouts}`, `context.{userInstructions,maxChars,mcpAllow,connectors}`; `userName`, `notify`, `toastSilent`, `mergeStyle`, `signMerges`; every repository setting (`baseBranch` at the repository's next refresh) |
| after a restart | `claude.workers`, `claude.leads`, `claude.context.skills`, `claude.context.sessionHistory.{enabled,days}`, `claude.subagents.{enabled,agents}`, `claude.useClaudeLogin` |
| read-only | `claude.context.mcpServers`, a repository's `env` |

Changes that wait for a restart are listed in `foreman.status.restartRequired` until the Foreman
restarts (or they are set back).

**Restart (`foreman.restart`).** Acked first; then the server closes, the Foreman saves its state
(running turns are interrupted and resumed on start), and the same command (node, its flags, the
script, the arguments, environment and working directory; without the one-shot `--reset`, `--goal`
and `--autostart`) starts again detached, with a new pid and a new client token, on the same port.
The profile's run file names the new process right away, so `node tools/mac.mjs stop` finds it
(`launch` reuses it too). Its output goes where the old process's went.

### Steering

| | |
| --- | --- |
| `/pause @kit` | aborts Kit's turn, keeps the task; any open question of Kit's is withdrawn. `/resume @kit` continues the same session. |
| `/stop @kit` | Kit goes off shift (lounge, `active: false`): turn aborted, open questions and permission prompts withdrawn, `doing` tasks back on the board unassigned. Never scheduled again until `/resume @kit` or `/spawn @kit`; survives Foreman restarts. |
| `/spawn @wren [t3]` | brings an off-shift agent onto the team; with a task id, that task is assigned to it. |
| hand-off | when a task changes hands (stop, `/task t3 reassign @wren`) it is held off the board until the old turn is really over (CLI exited, its processes gone), then the old worktree's work is committed on its branch and the next worker's worktree starts **from that branch**, so nothing is lost. The next worker's prompt says it takes over (and from whom) and lists the questions you already answered on that task, so it does not ask again. This also works if the old directory is still busy: removing it is retried in the background and never blocks the hand-off. |
| stop is immediate | the aborted turn's CLI process is force-closed; anything a lingering session still tries (tool calls, permission requests, messages) is refused. |
| self-healing scheduler | if starting a task fails (a git error, a busy directory), the task stays on the board and the scheduler retries with backoff (2 s .. 60 s) instead of waiting for the next event. |

### Permissions (src/policy.ts)

| | |
| --- | --- |
| allowed | reads/edits inside the agent's worktree; safe dev commands (`npm test`, `node src/x.ts`, `git status/diff/add/commit/merge`, `ls`, `grep`, ...); `npx <tool>` for dev tools the worktree has installed (`node_modules/.bin`); scratch files in the OS temp dir |
| asks you (permission decision) | anything outside the worktree (absolute paths, `..`, `~`, `$HOME`, `%USERPROFILE%`, brace expansion like `{~,x}`, `cd` out of the worktree, redirections like `>C:/x`, paths from `$VARS` or `$(...)`, links that lead out, Glob patterns like `../../x/*`), network (`curl`, `npm install`/`view`/`outdated`, `npx` of a tool that is not installed, WebFetch, `git fetch`), dev servers (`vite`, `webpack serve`), git commands that change the repo shared with your checkout (`git config` writes, `git branch -f/-D/<new>`, `git tag`, `git stash`, `git update-ref`, `git checkout <branch>`, `git rebase <upstream> <other-branch>`, `--update-refs`, `--ignore-other-worktrees`, `git submodule update`, `filter-branch`), git pointed at another repository (`GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR`, ... in any form: prefix, `export`, `env`, `read`, `for`; `--git-dir`; git run after `cd`/`-C` out of the worktree), writing, moving or deleting a `.git` entry inside the worktree (its link to the repository), destructive commands (`rm -r`, `find -delete`, `git reset --hard`, `git clean`, `xargs rm`), env vars that make commands run code (`GIT_PAGER=...`, `NODE_OPTIONS=...`, `git -c core.pager=...`), inline code that writes/spawns/uses the network (`node -e`, `python -c`), process/system commands (`taskkill`, `reg`, `sudo`), GUIs and the browser (`git citool`, `git gui`, `git <sub> --help` on Windows), unknown binaries |
| always denied | `git push` however it is spelled, wrapped or hidden, when the policy can see it: `env`/`xargs`/`timeout`/`sudo`/`bash -c`/`cmd /c`/`eval`, inside `$(...)`, backticks or `<(...)`, `find -exec`, commands git runs for us (`rebase -x`, `bisect run`, `submodule foreach`, `filter-branch --tree-filter`, `difftool -x`), `echo "git push" \| bash`, here-docs/strings fed to a shell, `node -e "...execSync('git push')"`, a git subcommand from a variable or substitution (`git $x`), `git -c alias.p=push`, `git lfs push`, `git subtree push`; signing with your key (`git commit -S`, `git tag -s`, `-c commit.gpgsign=true`); changing or clearing the git safety variables (`env -i`, `GIT_CEILING_DIRECTORIES`); file edits by the lead; subagents; the Foreman's own files, token and port (below). Where the policy cannot see a push (a test script, a node script), git itself refuses it (below). |

The Bash classifier is a small shell parser (quotes, redirections, heredocs and here-strings,
brace expansion, a virtual `cd`, wrapper commands, nested shells, `eval`). Every command
substitution (`$(...)`, backticks, `<(...)`, also inside double quotes, unquoted heredocs and
`$((...))`) is classified as a command of its own, and its output counts as unknown wherever a
path matters (except `$(pwd)`, `$(git rev-parse --show-toplevel)` and lists of worktree paths like
`$(git ls-files)`). `find` is checked per start point and per `-exec` command; `xargs` may read
from a list of worktree paths (`find`, `git ls-files`, `grep -l`), anything else it runs asks.
Commands that git runs for us (`git rebase -x/--exec`, `git bisect run`, `git submodule foreach`,
`git filter-branch --*-filter`, `git difftool -x`, `-c alias.x='!cmd'`) are classified exactly like
the same command typed directly. Anything it cannot verify asks. The lead works in your own
checkout, so it may only run read-only commands without asking (a redirection like `git log > x`
or `git diff --output=x` is a write).

"Always allow for this agent" stores every rule key the call needed, and each key is scoped so it
never covers more than the prompt said (the prompt shows the scope: `"Always allow for this
agent" covers: ...`):

| key | covers |
| --- | --- |
| `Bash:rm -r`, `Bash:find -delete`, `Bash:npm install`, `Bash:git reset --hard`, `Bash:git rebase`, ... | that action inside the worktree; paths outside, and commands git would run for it (`rebase -x`), still ask with their own keys |
| `Bash:git checkout:<target>` | switching the worktree to exactly that branch/commit |
| `Bash:outside:<cmd>:r\|w\|x:<dir>` | `<cmd>` reading / writing / running files directly in `<dir>` |
| `Bash:outside:<cmd>:rtree\|wtree:<path>` | a recursive read / change of exactly `<path>` (`rm -r`, `grep -r`, `find`, `mv` of a directory) |
| `Bash:net:curl:<hosts>`, `Bash:net:gh:pr view` | that tool to those hosts / that subcommand |
| `Bash:exact:<hash>` | only that exact command: anything whose arguments cannot be checked (xargs writes, `$VARS`, substitutions, unknown programs, process/system commands, inline code, shell scripts on stdin) |
| `Read:<dir>`, `Grep:tree:<path>`, `Write:<dir>`, `Write:.git:<file>`, `WebFetch:<host>` | the non-Bash tools |

`git push` is never allowed, whatever is stored. Keys from older versions of the Foreman
(`Bash:subst`, `Bash:find -exec`, `Bash:xargs rm`, `lead:<prefix>`, `Bash:git fetch`,
`Bash:git submodule`, `Bash:git lfs`, `Bash:git checkout`, `Write:.git`, ...) no longer match anything.

**The Foreman's own files and port** (`foremanPrivateVerdict`): agents may not read or change the
Foreman's settings and state, nor talk to it directly. Denied with a reason saying so, checked before
everything else (no "Always allow" covers it) and again by a PreToolUse hook on every turn (so your
`claude.permissions.allow` rules and subagents cannot skip it, in both permission modes):

- Read / Grep / Glob / LS / Edit / Write / NotebookEdit of anything under the Foreman home
  (`config.json`, `config.json.bak`, `foreman.json`, every profile's `state.json`, logs,
  `client.token`, ...) except the folders agents work in (`<profile>/worktrees`, `memory`,
  `agent-plugin`, `designs`); of any `client.token`; through a link as well (the real path is
  checked); Grep over a folder that contains the home (e.g. `~`); Glob patterns that reach them.
- Bash / PowerShell commands that mention such a path (absolute, `~`, `$HOME`, `${HOME}` or
  relative to the agent's directory, e.g. `cat ../../../state.json`), `client.token`, `foremancli`,
  `$AGENTCRAFT_HOME` / `_CLIENT_TOKEN` / `_PROFILE`, or the Foreman's port together with a loopback
  host or a `ws://` URL (`curl localhost:7878`, `websocat ws://127.0.0.1:7878`). Shell quoting and
  escapes are undone first (`cat ~/.agent'craft'/config.json` is caught).
- Recursive searches, listings, copies and archives (`rg`, `grep -r`, `find`, `ls -R`, `tree`, `du`,
  `tar`, `cp -r`, `rsync`, `zip -r`, ...) rooted at the Foreman home or a folder above it (`/`, `~`,
  `/Users`, ...), also after a `cd` in the same command.
- Instruction files: a CLAUDE.md / AGENTS.md (or a configured instruction file) that is, or links
  to, one of these files is skipped, and an `@import` of one is replaced by a note
  (`(import @... skipped: AgentCraft's own files are off limits)`), so an agent cannot get the
  token into its next prompt by writing an import into its worktree.

**The Bash guard is best effort, by design.** It reads the command the way a shell would (quotes and
escapes undone, `cd` followed, a `( )` subshell's `cd` left inside it, redirections, the option
values of rg/grep/find that are patterns rather than paths), but a text check cannot see a path a
program assembles at run time, a glob that expands to the home, or a script the agent wrote and
runs (`npm test` runs the agent's own code, as do worktree setup and the Foreman's CI, all as your
user). The real boundary is the client token file plus the read-only socket: they stop a
*prompt* (a CLAUDE.md, a PR comment, a web page) from talking an agent into driving the Foreman
through its own tool calls. They do not stop code an agent writes and then runs from reading a file
your user can read; that is accepted, and the docs say so rather than promise more.

### Push, signing and other repositories are blocked at the git level too (src/gitsafety.ts)

The policy can only judge what it can see; a push could hide in a test script or a node script.
So every process an agent can influence (the agent's Claude CLI process, every command it runs,
and the Foreman's CI runs) gets:

- `GIT_ALLOW_PROTOCOL=agentcraft-none` plus env-scoped git config `protocol.allow=never` and an
  empty `url.<dead>.pushInsteadOf`: git refuses every transport, so push, send-pack, fetch and
  clone all fail, even with an explicit `pushurl`.
- `commit.gpgsign=false`, `tag.gpgsign=false` and a missing `gpg.program`/`gpg.ssh.program`:
  agents' commits are never signed with your key, and an explicit `-S` fails instead of signing.
- `GIT_CEILING_DIRECTORIES=<parent of the agent's cwd>`: git never walks up out of the worktree,
  so a worktree whose `.git` link was deleted stops working instead of reaching an enclosing
  repository. Inherited `GIT_DIR`/`GIT_WORK_TREE`/`GIT_INDEX_FILE`/... are removed.
- The agent's own git identity (`AgentCraft Kit <kit@agentcraft.local>`), never yours.

Env-scoped config outranks the repo's `.git/config` and your global config. Local git (status,
commit, branch, merge) is unaffected, and the Foreman's own git calls do not use this environment.
This is not a sandbox: code an agent runs could drop the variables on purpose (a script that
spawns git with an empty environment); the policy refuses every command it can see doing that
(`unset GIT_...`, `env -i`, `GIT_ALLOW_PROTOCOL=...`).

## Safety guarantees (src/repos.ts)

- Never pushes; there is no code path that runs `git push` (and agents' git cannot push, above).
- Your checked-out branch's working tree is touched only by an approved merge. The merge commit is
  built off-tree (`merge-tree` + `commit-tree`) and applied with `merge --ff-only`.
- The Foreman's own git writes in a worktree (committing leftover work at merge, abandon and
  hand-off) first check that the worktree's `.git` link still leads to this repository's own entry
  for that directory. If an agent rewrote or deleted it, nothing is committed, the diff is not
  shown, and an abandoned worktree is left in place for you to look at. They only ever move the
  agent's own `agentcraft/...` branch: if the agent checked out another branch, its work is
  committed as a snapshot onto its own branch and the other branch is not touched.
- The approved merge commit is yours: it uses your git identity (`user.name`/`user.email` as your
  git sees it in that repo) and is signed when your git config has `commit.gpgsign=true` (with your
  `gpg.format`/`user.signingkey`; `commit-tree` does not do that by itself). If signing fails, the
  merge is refused with the reason and the decision re-opens; nothing changes. `--no-sign-merges`
  turns signing off; the sim never signs. The agents' own commits on their branches (theirs
  and the Foreman's) use `AgentCraft <Name> <name@agentcraft.local>` and are never signed.
  With `--merge-style squash`, main gets one commit with the task's changes (your identity,
  signed as above, the task's title and summary as the message, no trailers) instead of a merge
  commit (`Merge <branch> into <base>`, no tool attribution) plus the agents'
  commits - useful for repos that require signed commits or verified emails. Branches are kept.
- `/repo add <path>` must name a repository root; a folder inside another repository is refused
  (instead of silently registering the enclosing repo as the merge target).
- Merges are refused (and the decision re-opens with the reason) if the checkout that has the base
  branch checked out has uncommitted tracked changes. A merge that would conflict is not made either:
  with the claude backend the task goes back to its worker (`git merge <base>` in its worktree,
  resolve, test, commit), then through CI and review to a fresh merge decision; other backends
  re-open the decision with the conflicting files.
- If you have another branch checked out, a merge only moves the base branch ref.
- Agent branches are kept after merge/reject; merged worktree diffs stay viewable.
- `repo.dirty` follows your checkout: it is re-broadcast after a refused merge and polled every
  10 s (`--repo-poll-ms`), so the mod can show why a merge was refused.
- CI runs with the git safety env and a timeout that kills the whole process tree.
- The WebSocket only takes local, non-browser clients: any `Origin` header (every browser sends
  one; sandboxed iframes, `data:` and `file:` pages send `Origin: null`) and any Host header other
  than `127.0.0.1`/`localhost`/`[::1]` (DNS rebinding) are refused with HTTP 401. The mod and the
  CLI tools send no Origin. `--allow-browser-origins` turns this off for development.

## State layout

```
<AGENTCRAFT_HOME>/
  config.json            your settings (hand-editable; the hub's Settings tab writes it too)
  config.json.bak        the previous config.json, kept by every config.set
  foreman.json           the primary Foreman's run file
<AGENTCRAFT_HOME>/<profile>/
  state.json             agents, tasks, decisions, repos, goals, feed, messages, sessions, leads (atomic writes)
  foreman.json           pid/port of the Foreman running this profile, and tokenFile
  client.token           the client token (0600, new on every start, removed on exit)
  logs/<agent>.jsonl     agent logs (snapshots carry the last 60 lines per agent); rotated at 8 MB
                         into <agent>.1.jsonl (one old file kept); start-up reads only their ends
  memory/shared/*.md     shared notes (hand-editable)
  memory/agents/<id>/*.md
  worktrees/<repo>/<agent>-<task>/
  designs/<designId>/    scratch dir of a building design job (kept: the source of the design)
```

## Protocol

`src/protocol.ts` (zod) is the source of truth; `docs/protocol.md` is generated from it with field
tables and a JSON example per message (`npm run gen:protocol-doc`; `npm run check:protocol-doc`
fails if it is stale). Highlights beyond the spec draft: `foreman.status` (backend/auth banner),
`ack`/`error` replies for messages with an `id`, `snapshot.logs`/`snapshot.goals`,
`Agent.active/paused/worktree/title`, task status `cancelled`, decision status `cancelled`.

## Building designs (`design.request`)

The hub's "Design new" form (docs/HUB.md) sends `design.request`; the Foreman acks with
`{designId}` and reports progress as `design.upsert` (also in `snapshot.designs`, the last 20 plus
any unfinished): `queued -> designing -> checking -> rendering -> done | failed | cancelled`, with
a one-line `step`. `design.cancel {designId}` stops it. The request is validated strictly: style and
features from fixed enums, `maxSize` x/z 9..128 and y 6..48, `single` = 1 wing, `group` = 2..8
wings, and `outDir` must be an absolute `<gameDir>/agentcraft/blueprints` (Windows or POSIX, no
`.`/`..`; checked again on the real path before writing), so a client cannot make the Foreman write
anywhere else.

A job (claude backend, `src/agents/claude/design.ts`), one at a time:

1. `<profile>/designs/<id>/` mirrors the repo: a fresh copy of `tools/blueprints` (kit, block table,
   checker, example designs, the renderer if present), `docs/BUILDINGS.md`, and `BRIEF.md` written
   from the request (style guide per preset, materials, features, size limits, kind/wings, the
   anchor contract summary, how to build/check/render). The blueprint id is `gen_<slug of the name,
   else the style>`, free in `outDir` at that moment. A remix copies the source module (a bundled
   design, or the module an earlier job wrote).
2. A design agent turn runs there: `claude_code` preset, `claude.designModel`, worker effort and
   max turns, cwd = the scratch dir, the AgentCraft policy as for a worker in that dir (always, also
   when `claude.permissions.mode` is `auto`: its classifier would decide what the policy asks
   about, e.g. network), except that nothing can prompt: whatever the policy would ask about is
   refused with a reason. No web tools, subagents, skills or the user's MCP servers; one tool, `design_status(step)`,
   for progress (tool calls are mapped to steps too: writing the design, running the checker,
   looking at the renders). It logs as `designer` (`logs/designer.jsonl`; not a roster agent).
3. The Foreman re-checks: it restores a pristine kit (only `designs/<id>.mjs` is the agent's), runs
   `node tools/blueprints/build.mjs <id>` in a child process with a minimal environment (no keys)
   and a timeout, and checks the sidecar against the request (size within `maxSize`, kind, wings).
   A failed check goes back to the same session with the problem; at most 4 turns in all, then
   `failed` with the checker output.
4. If the kit has `tools/blueprints/render.mjs`, it renders `<id>.preview-*.png` (a renderer error
   does not fail the design). Then it copies `<id>.nbt`, the previews and the sidecar (last) into
   `outDir` under a fresh id: `gen_<slug>`, `gen_<slug>_2`, ... (exclusive copies; nothing is
   ever overwritten), and reports `done` with `blueprintId`, `size` and `previews` (absolute paths).
   Feed lines on start / done / failure, a notification on done.

A usage limit puts the job back at the front of the queue (it resumes the same session when the
limit resets); a Foreman shutdown leaves it unfinished and the next start picks it up. The sim
backend fakes jobs: a few speed-scaled steps, then a copy of the bundled workshop (group: campus
with enough wings) as `gen_sim_<n>`, with the request's name; requests it does not fit fail.

## The sim backend

A deterministic 22-beat script (`src/agents/sim/scenario.ts`) on the pocket-notes demo repo: the
lead plans 9 tasks, five workers read, edit, test and message each other, CI fails and gets fixed,
a permission prompt, a product question, a blocked task (npm publish needs you) with a blocked
agent, an agent error that recovers (API overload), a cancelled stretch task, five merge decisions,
and a last question that decides how the goal ends (close the publish task -> goal done at 100%,
or keep it -> the goal stays open). Every agent state and station appears. Every edit, test run
and merge is real (real worktrees, real `npm test`, real merge commits), so the diffs you review
are genuine. Your answers change the outcome (Deny vs Allow, which `notes tags` behaviour gets
built, Request changes makes the worker revise). Progress is persisted per beat; a restarted
Foreman resumes the script. `/stop @agent` takes an agent off shift; the script waits for it until
`/resume`.

Static states for screenshot QA:

| | agents |
| --- | --- |
| `--showcase` | Marlow waiting_user@user, Juniper editing@desk, Kit testing@testbench, Wren idle@lounge, Rowan reading@library, Tove thinking@desk; open merge + question decisions |
| `--showcase late` | Marlow thinking@meeting, Juniper running@terminal, Kit done@lounge, Wren blocked@desk (t7), Rowan error@library, Tove editing@desk |

The repo id is the demo dir name: `sim-demo-showcase` / `sim-demo-showcase-late` for those profiles.

## Tests

```sh
npm test            # vitest: protocol, task graph, persistence, decisions, repos/merges, policy,
                    #         git push block, WS + full sim run, showcase states, claude
                    #         orchestration, restart recovery and steering, design jobs (fake SDK)
npx tsc --noEmit
npm run check       # all of the above + protocol doc freshness
```

## Troubleshooting

- **`port 7878 is already in use`**: another Foreman is running (`~/.agentcraft/foreman.json` and `~/.agentcraft/<profile>/foreman.json` have its pid) - or use `--port`.
- **`profile "claude" is in use by the Foreman pid N`**: that profile already has a running Foreman; stop it or use `--profile`.
- **`read-only connection: no client token`**: the client did not send this start's client token. Tools find it through the run file under the Foreman's home: pass `--home` (or set `AGENTCRAFT_HOME`) when the Foreman runs with another home. After a restart the token is new: reconnect.
- **`... is not a repository root`**: `/repo add` the repository's top folder (the message names it).
- **Banner says auth failed**: set `ANTHROPIC_API_KEY` (or a cloud provider switch) and restart the Foreman. With `--use-claude-login`: run `claude` and `/login`. The sim backend works without auth. Why the claude.ai login is opt-in: Anthropic does not allow third-party tools to offer it ([Agent SDK overview](https://code.claude.com/docs/en/agent-sdk/overview)); see `src/agents/claude/auth.ts`.
- **Merge refused: uncommitted changes**: commit or stash in your checkout, then choose Merge again (the decision re-opened).
- **Reset the demo repo**: `node sandbox/create-demo.mjs --force`.
