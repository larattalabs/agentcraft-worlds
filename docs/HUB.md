# The AgentCraft Hub and generated buildings (contract)

One in-game screen for everything a player sets or does in AgentCraft, instead of chat commands,
config files and launch flags. Chat commands stay as thin aliases for scripts and the dev tools.
In a Hardcore world (cheats off) the hub is the way to do anything: it acts through the integrated
server (`ServerTasks`), so it needs no operator permission; commands do.

## Hub screen (mod, client)

**Status:** implemented: the screen, `H`, `/hub [tab]`, the **Buildings** tab (buildings with make
home / teleport / remove, blueprint browser with plan + rendered previews, Place, Place on the plot,
Design new; the Designs list) and the **Status** tab (branch `mod/hub`); the design form, plot marking
and the design flow (branch `mod/design-form`, see "Generated buildings"). Repos, Goals, Team and
Settings show a "coming next" panel. Code: `mod/src/client/java/dev/agentcraft/client/hub/` and
`.../client/design/`, notes in mod/DEV.md "Hub" and "Generated buildings".

- Opened with a key (default `H`, rebindable, AgentCraft category; vanilla binds H only as F3+H) and
  from the console (`/hub [tab]`). *(done)*
- Tabs, in this order (later waves fill the ones marked *later*):
  1. **Buildings** *(done)*: the world's buildings (blueprint, repos, home marker, box, rotation),
     actions: place new (opens the existing wizard), make home, remove (two-step confirm), teleport to
     (the entrance anchor, same dimension, a free spot with a floor); a blueprint browser (bundled +
     user, with the top-down plan and, when present, the rendered previews `<id>.preview-{iso,top,front,
     cutaway}.png` from the user folder, else the mod's `data/<ns>/blueprints/`), Place (repo step with
     the blueprint fixed, then placement mode) and **Design new** (below). *(done)*
     A third list, **Designs**, shows the building designs (below). *(done)*
     Buildings record their dimension (`Building.dimension`): remove and teleport refuse, naming both
     dimensions, when the player is elsewhere; records from before the field fall back to requiring
     AgentCraft stations in the box in the player's dimension. Remove also refuses while the player
     stands in the box. *(done)*
  2. **Repos** *(later)*: registered repos and their `repoSettings`.
  3. **Goals** *(later)*: submit a goal (repo, "continue a branch", earlier session) and, per goal:
     - **Thread with the lead**: a conversation about this goal (messages to Marlow tagged with the
       goal id; the lead's replies and its plan/task changes for that goal), instead of free-floating
       `@marlow` messages.
     - **Plan view / edit**: the goal's plan (its `Plan:` memory note) shown and editable in place;
       an edit is sent to the lead as "the user changed the plan" so it can adjust tasks.
     - **Standing instructions**: notes that every task of the goal inherits (in the lead's planning
       prompt, each task description and every worker prompt for the goal), e.g. "keep the API
       backwards compatible".
     - **Digest ("since you were away")**: a short summary of what happened on the goal since the
       player last looked (tasks done/blocked, decisions answered or waiting, merges/PRs, key
       messages), built by the Foreman from the feed and task history (no model call needed for v1).
     Needs Foreman support: goal-scoped messages, goal instructions (stored with the goal, included in
     prompts), plan read/write, and a digest query (`goal.digest {goalId, since}`).
  4. **Team** *(later)*: agents, roles, models, effort, shift.
  5. **Settings** *(later)*: permissions, context, connectors, session history, usage.
  6. **Status** *(done)*: Foreman connection, backend, auth/account, usage windows (percent, reset
     time), spend, mod and Foreman versions, DevBridge state.
- Style: the existing UI kit (`ui/Kit`, `ui/Panels`, `gui/ui-style.json`, `palette.json`), like
  `DecisionScreen`. Shootable through `DevBridge.registerScreen("hub", ...)` with a tab argument.
  *(done: screens `hub`, `hub_<tab>`, `hub_blueprints`; `dev.hub.open {tab}`, `dev.hub.state`,
  `dev.hub.action`, see mod/DEV.md "Hub")*

## Generated buildings

### The form (Buildings tab -> Design new) *(done, branch `mod/design-form`)*

As implemented: `DesignScreen`, opened by "Design new…" (blueprint browser, Designs list) and the
console's `/hub design`. Two columns: For (one repo / a group of 2..8 wings), Style (one line about the
selected one), Materials, Features (checkboxes, the protocol ids), Size (S / M / L, or "Fit a plot…");
Remix (‹ none / blueprint ids ›), Name (optional, 40 chars; the blueprint id it will get is shown),
Notes (multi-line). Every value is checked with the Foreman's own limits (`DesignSpec.validate`: wings,
style and feature ids, maxSize x/z 9..128, y 6..48, remix id, name 40, notes 2000, the outDir rule) and a
problem shows in red next to its field. "Design it" (or Ctrl+Enter) sends `design.request` with
`outDir` = the absolute `<gameDir>/agentcraft/blueprints` (created first) and opens the Designs list.
The form keeps its content across plot marking and reopening (per session).

Size presets (`DesignSpec.preset`, x × y × z in the template's frame, x along the entrance side):
single S 24×14×24, M 36×16×36, L 56×18×40; a group of N wings S (24+12N)×16×30, M (35+14N)×18×36 (the
bundled campus2..4 fit), L (44+18N)×22×48, x capped at 128.

**Fit a plot…** closes the form and enters plot marking (`PlotMarker`, built on placement mode's look
ray, key capture, HUD slot and renderer): look at a corner, Enter; look at the other, Enter. A
translucent rectangle follows the look with its size (x by z), the entrance side (the side facing the
player, brass) and the height limit (PgUp/PgDn, Shift: by 4; default 16) drawn as faint posts. Backspace
goes back a corner, Esc returns to the form unchanged. The second Enter returns to the form with
maxSize = the plot (width along the entrance side × height × depth, clamped to the limits; the HUD warns
when clamped) and remembers the plot (min corner, ground = the lower corner's surface, front, dimension)
for the design it is sent with.

- **For**: one repo (single) or N repos (group, N wings).
- **Style preset**: `modern` (glass, plaster, flat/hip roof), `cabin` (logs, stone, gable roof),
  `townhouse` (brick, gable), `workshop` (the current workshop's look), `campus` (hall + wings).
- **Materials**: AgentCraft-first (default) or vanilla allowed.
- **Features** (checkboxes): porch, skylights, courtyard, big windows, garden.
- **Size**: S / M / L, or **fit a plot**: the player marks a rectangle on the ground (placement-mode
  style: look at a corner, confirm, look at the other corner, confirm) -> max footprint x/z; max
  height (default 16).
- **Remix** (optional): start from an existing blueprint id.
- **Notes**: free text.

### Protocol (mod <-> Foreman), added to `foreman/src/protocol.ts`

Client -> Foreman:
- `design.request` `{ request: DesignRequest }` -> ack `{ id }`
  - `DesignRequest = { kind: "single"|"group", wings: int>=1, style: string, materials:
    "agentcraft"|"vanilla", features: string[], maxSize: {x,y,z}, remix?: string, notes?: string,
    outDir: string }` — `outDir` is the absolute `<gameDir>/agentcraft/blueprints` the mod reads
    user blueprints from.
- `design.cancel` `{ designId }` (the envelope's `id` is the correlation id)

As implemented (see `docs/protocol.md`): the ack's `result` is `{ designId, id }` (both the design
id); `DesignRequest` also takes an optional `name` (display name; the blueprint id is
`gen_<slug of name>`, else of the style); `style` is one of `modern`, `cabin`, `townhouse`,
`workshop`, `campus`, `custom`; `features` are ids `porch`, `skylights`, `courtyard`,
`big_windows`, `garden`; `single` has exactly 1 wing and `group` 2..8; `maxSize` x/z 9..128, y 6..48;
`outDir` must be an absolute path ending in `agentcraft/blueprints` (anything else is refused).
Design ids come from the decisions' counter, so a design id never equals a decision id.

Foreman -> client:
- `design.upsert` `{ design: Design }` (also in `snapshot.designs[]`)
  - `Design = { id: "d<n>", request, status: "queued"|"designing"|"checking"|"rendering"|"done"|
    "failed"|"cancelled", step: string (one line of progress), blueprintId?: string, size?: {x,y,z},
    previews?: string[] (absolute PNG paths), error?: string, createdAt, updatedAt }`

### The design job (Foreman)

- A design agent (Claude Agent SDK, `claude_code` preset, model `claude.designModel`, default the
  worker model) works in `<profile>/designs/<id>/`, a scratch copy of `tools/blueprints` (kit, block
  table, checker, existing designs as examples) — never in a registered repo.
- It writes `designs/<blueprintId>.mjs` as a parametric design with the kit (semantic, not raw
  coordinates), runs `build.mjs` (checker) and the offline renderer, looks at the PNGs, and iterates
  until the checker passes, the size is within `maxSize`, and the renders look right (max N rounds).
- Permissions: the normal policy with `cwd` = the scratch dir; network off; no subagents. Nothing
  can prompt (no avatar to ask through): what the policy would ask about is refused with a reason.
- The scratch dir mirrors the repo (`tools/blueprints`, `docs/BUILDINGS.md`, `BRIEF.md`), so
  `node tools/blueprints/build.mjs <id>` works unchanged from its root. The Foreman restores a
  pristine kit before its own check, so only `designs/<id>.mjs` counts. Max 4 agent turns (a failed
  Foreman check goes back to the same session).
- When done, the Foreman (not the agent) re-runs the checker and renderer, then copies
  `<blueprintId>.nbt`, `<blueprintId>.blueprint.json` and the preview PNGs
  (`<blueprintId>.preview-*.png`) into `outDir`, and reports `done` with the paths. Ids are
  `gen_<slug>` and never overwrite an existing file (suffix `_2`, ...).
- The sim backend fakes a job (copies the bundled workshop under a new id) so the UI is testable.
- One design at a time; queued requests wait. Usage limits apply like any turn.

### Review and place (mod) *(done, branch `mod/design-form`)*

- On `design.upsert` done: the mod reloads blueprints (`Blueprints.reload` on the server thread),
  the Buildings tab shows the new blueprint with its previews, and "Place" opens the wizard's
  placement mode with it (ghost = the real review; nothing is placed without confirm).

As implemented: the **Designs** list (Buildings tab, newest first) shows each design's status pill and
step line; the detail has the request, the result blueprint and size, the error of a failed one, and
Cancel (queued/running: `design.cancel`). A toast on done and on failed (these replace the Foreman's own
notify for the same event). On done (seen as a transition, also across a reconnect's snapshot):
`Blueprints.reload` on the integrated server, then the new blueprint is selected in the browser (iso
preview) if the hub is open, else when it next opens. Then "Place on the plot" when a plot was marked
for it (the repo step, then placement mode locked on the plot: rotated so its entrance faces the side
the plot was marked from, centred on it, ground row on the plot's ground; rotate/nudge/unlock still
work; refused in another dimension) and "Place…" (the normal repo step + placement).

## Offline renderer (tools)

`node tools/blueprints/render.mjs <path.nbt|id> [--out dir]` writes `<id>.preview-iso.png`
(isometric, from the front-left, entrance side visible), `<id>.preview-top.png` (top-down) and
`<id>.preview-front.png` (front elevation), from block colours (a colour table per block id; textures
optional later). Dependency-free PNG output (zlib + CRC), fast (< 2 s for 100k blocks). Used by the
design agent, the Foreman, and tests.

## Repos and Goals tabs (contract, 2026-10-03)

Decision: the home building is always Marlow's. The mod never asks a lead for the home building
(`lead.assign`/`lead.sync` leave it out); making another building home re-syncs, so the old home gets a
lead and the new home's lead is released. The home podium shows Marlow's decisions.

### Protocol additions (Foreman)
Shapes:
- `Goal` gains `instructions?: string[]` (standing instructions), `planId?: string` (the memory entry id
  of the goal's plan note, when it exists), `branch?: string` (the "on <branch>:" branch), `prs?:
  {taskId, url, id, status}[]` (summary of its tasks' PRs).
- `FeedItem` and `Decision` gain `goalId?` (set whenever the item is about a goal: its tasks, its lead's
  turns for it, its PRs).
- `Repo` gains `settings?: RepoSettingsView` = `{ land: "merge"|"pr", baseBranch?, ci?: string,
  setup?: string, pr?: {remote?, branchPrefix?, draft?, squash?}, protect: string[], roles: {[agentId]:
  string}, subagents?: string, prReview?: {autoSeverities, maxRounds} }` (read-only view of
  `repoSettings`; no secrets/env values: `env` shows its keys only as `envKeys: string[]`).
Client -> Foreman (all ack; errors as `ok:false` with a message):
- `goal.message { goalId, text }`: a message to the goal's lead about that goal (runs in the goal's lead
  session; the user's message and the lead's replies are feed items with `goalId`, kind `message`).
  `user.message` keeps working for free-floating messages.
- `goal.instructions { goalId, instructions: string[] }`: replaces the goal's standing instructions.
  They go into the lead's prompts for the goal, every task description created afterwards (appended
  as "Standing instructions"), and every worker prompt for the goal's tasks (as a section, not baked
  into the description, so edits apply to running work at the next turn). A change is sent to the lead
  as a goal message ("The user changed the standing instructions: ...").
- `goal.plan { goalId, body }`: writes the goal's plan note (creates it if missing, sets `planId`) as
  the user, then sends the lead a goal message with a unified diff of the change so it can adjust
  tasks.
- `goal.cancel { goalId }`: cancels the goal's open tasks (running workers stop, worktrees kept),
  status `cancelled`.
- `goal.digest { goalId?, since: Ts }` -> ack result `{ since, until, goals: [{ goalId, text, status,
  progress, lines: DigestLine[] }] }`, `DigestLine = { ts, kind: "task_done"|"task_blocked"|
  "task_added"|"decision_waiting"|"decision_answered"|"merged"|"pr_opened"|"pr_merged"|"pr_comments"|
  "message"|"goal_done", text, taskId?, agentId? }`: built from the feed, tasks and decisions (no
  model call); at most 30 lines per goal, newest last, routine progress lines dropped.
- `repo.remove { repoId }`: unregisters a repo (refused while it has open tasks); worktrees and
  branches are left on disk.
- `goal.submit` gains `branch?: string` (equivalent to the "on <branch>:" prefix) and `instructions?:
  string[]`.

### Repos tab (mod)
- List: each repo with name, branch@head, dirty flag, CI lamp, worktrees, its building (or "no
  building") and lead, open PRs. Detail: path, the settings view (land mode, base branch, CI/setup
  commands, PR options, protected files, roles, review defaults), its goals.
- Actions: Add repo (path field -> `repo.add`), Remove (two-step -> `repo.remove`), Place a building
  (wizard with this repo preselected), Refresh PRs (`pr.refresh`), New goal (Goals tab form with the
  repo set). Editing settings waits for the Settings tab (config get/set).

### Goals tab (mod)
- List: goals newest first with status, progress, repo, lead (portrait), PR summary, and an unread dot
  when it has activity since the player last opened it.
- New goal form: text (multi-line), repo, "continue a branch" (free text; offers branches the Foreman
  knows for that repo when available), standing instructions (lines).
- Goal detail, sub-views:
  - **Thread**: the goal's feed items and decisions in order (decisions answerable inline with their
    options), an input that sends `goal.message`.
  - **Plan**: the plan note rendered as text; Edit -> multi-line editor -> Save sends `goal.plan`.
  - **Instructions**: list with add / edit / remove -> `goal.instructions`.
  - **Tasks**: the goal's tasks with status, assignee, PR link/state; open the existing task screen.
  - Cancel goal (two-step).
- "Since you were away": opening the hub (or the Goals tab) after >= 10 minutes away asks
  `goal.digest {since: lastSeen}` and shows a dismissible digest panel at the top of the Goals tab;
  each goal's detail shows its own digest since its last view. `lastSeen` per world and goal lives in
  `<gameDir>/agentcraft/hub-seen.json`.
- DevBridge: `dev.hub.open {tab: repos|goals, ...}`, `dev.hub.state` (tab state), actions for every
  button, `dev.goals.*` helpers; screens `hub_repos`, `hub_goals`, `hub_goal_<view>`.

### Multi-repo buildings and cross-repo goals (amendment)
- A group building's repos share one lead (the lead's `repos` is the building's repo list).
- `Goal` gains `repos?: string[]`: every repo the goal touches (its `repoId` first, then each task's repo
  in order of first appearance), maintained by the Foreman.
- `goal.submit` gains `repos?: string[]` (the first one becomes `repoId`; a goal "for a building" sends
  the building's repos). The goal's lead is the lead of `repoId`. The lead's planning prompt names the
  goal's repos ("this goal is for <building/repos>; give each task the one repo it changes").
- A goal whose tasks reach a repo of another building stays with its own lead; those tasks show on that
  building's task wall (wing per repo), PR triage stays with the goal's lead.
- Mod: the new goal form's target is a repo or a building (group buildings list their repos); the
  Goals list shows all of a goal's repos and can be filtered by building; the Repos tab shows each
  repo's building with its wing number ("Notes campus · wing 2") and the building's shared lead.
