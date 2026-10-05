# The AgentCraft Hub and generated buildings (contract)

One in-game screen for everything a player sets or does in AgentCraft, instead of chat commands,
config files and launch flags. Chat commands stay as thin aliases for scripts and the dev tools.
In a Hardcore world (cheats off) the hub is the way to do anything: it acts through the integrated
server (`ServerTasks`), so it needs no operator permission; commands do.

## Hub screen (mod, client)

**Status:** implemented: the screen, `H`, `/hub [tab]`, the **Buildings** tab (buildings with make
home / teleport / remove, blueprint browser with plan + rendered previews, Place, Place on the plot,
Design new; the Designs list) and the **Status** tab (branch `mod/hub`); the design form, plot marking
and the design flow (branch `mod/design-form`, see "Generated buildings"); the **Repos** and **Goals**
tabs (branch `mod/goals-tabs`, see "Repos and Goals tabs" below); the **Team** and **Settings** tabs and the Repos
tab's "Edit settings" (mod side in branch `mod/settings`, see "Team and Settings tabs" below). Code: `mod/src/client/java/dev/agentcraft/client/hub/` and
`.../client/design/`, notes in mod/DEV.md "Hub" and "Generated buildings". Later waves added the
**Inbox** tab (first tab, fix wave 2) and, in the Buildings tab, the **Fixtures** and **Roads** lists and
the village toggles (walking, trophies, routines; docs/VILLAGE.md). The Buildings tab's switcher has five
lists, in this order: **Buildings, Fixtures, Blueprints, Designs, Roads** (`HubScreen.Sub`).

- Opened with a key (default `H`, rebindable, AgentCraft category; vanilla binds H only as F3+H) and
  from the console (`/hub [tab]`). *(done)*
- Tabs, in this order (later waves fill the ones marked *later*):
  0. **Inbox** *(wave 2, branch `wave2/inbox`, see "Inbox" below)*: the first tab: decisions, agent replies,
     blocked tasks, the Foreman's hold and PRs that need you, in one list with a detail per item.
  1. **Buildings** *(done)*: the world's buildings (blueprint, repos, home marker, box, rotation),
     actions: place new (opens the existing wizard), make home, remove (two-step confirm), teleport to
     (the entrance anchor, same dimension, a free spot with a floor); a blueprint browser (bundled +
     user, with the top-down plan and, when present, the rendered previews `<id>.preview-{iso,top,front,
     cutaway}.png` from the user folder, else the mod's `data/<ns>/blueprints/`), Place (repo step with
     the blueprint fixed, then placement mode) and **Design new** (below). *(done)*
     The fourth list, **Designs**, shows the building designs (below). *(done)*
     **Fixtures** (village stream `board`, docs/VILLAGE.md V2): the second list, "Fixtures N": placed village boards with where
     they stand, Teleport (C7), Remove (asks twice, restores the terrain), Move, Undo move; the top-right button is
     **Place village board…** ("Place board…" when the row is narrow), which puts up the board's ghost at once (no repo step).
     Fixtures take no repos, have no lead and are never home. *(done, branch `village/board`)*
     Buildings record their dimension (`Building.dimension`): remove and teleport refuse, naming both
     dimensions, when the player is elsewhere; records from before the field fall back to requiring
     AgentCraft stations in the box in the player's dimension. Remove also refuses while the player
     stands in the box. *(done)*
     Fix wave 1 (stream world, docs/BUILDINGS.md): **Edit repos…** (the wizard's repo step in edit mode:
     this building's repos preselected, at most its wings; confirming calls `Buildings.setRepos` and the lead
     sync sends `lead.assign`), **Move…** (placement mode for this building; Enter moves it, keeping id, repos,
     lead and home), **Undo move** (after a move), **Remove anyway** (after a "move these first" refusal: a
     further confirm that loses what was listed), and a **Check** line when the world-start check found the
     building does not match its blueprint (or recovered a move/removal lost in a crash).
     **Teleport is gated (contract C7, fix wave 1):** the button shows only when the player may use
     commands (cheats on in singleplayer: create the world with them or "Open to LAN > Allow commands";
     an op in multiplayer) or is in creative/spectator; otherwise it is hidden and the note says to walk
     there (with the box's corner). `HubActions.teleport` refuses the same on the server.
     Placement messages say "Undo: hub (H) > Buildings > <id> > Remove" (not `/agentcraft remove`).
     Fix wave 2 (stream walking, docs/WAVE2.md W8): a toggle under the buildings list column,
     **"Agents walk between buildings: On/Off"** ("Walking: On/Off" with a short muted note when the column
     is narrow; the list gives up one row for it; not shown while the world has no buildings), per world and
     client side (`<gameDir>/agentcraft/walking.json`, default on). Off,
     agents teleport between buildings with a puff as before. Button id `walk_toggle`
     (`dev.hub.action {action:"press", button:"walk_toggle"}`, or `dev.walk.toggle`); its fit is in
     `dev.walk.state` `ui{needed, available, overflow, compact}`.
     Trophies (docs/BUILDINGS.md "Trophies"): a toggle under the walking one, **"Trophies for merges and
     finished goals: On/Off"** ("Trophies: On/Off" when it does not fit, with a short note "signs on the trophy
     wall" / "no signs are hung"), per world, client side (`<gameDir>/agentcraft/trophies.json`, default on).
     Button id `trophy_toggle` (or `dev.trophies.toggle {on}`).
     Village routines (docs/VILLAGE.md V3, stream routines): under the trophies toggle, one row of three
     toggles, filled = on: **Night** ("Night routine": idle agents sleep in the building's beds at night),
     **Stand-ups** (a goal's lead and its first workers gather when the tasks are assigned), **Library**
     ("Library visits": an agent walks to the library after writing a memory note). Per world, client side
     (`<gameDir>/agentcraft/routines.json`, default on); the list gives up one more row. Labels get tighter
     ("tight") and then shorter ("Night Stand Lib", "short") when the column is narrow. Button ids
     `routine_toggle:night|standups|library` (`dev.hub.action {action:"press", button:"routine_toggle:night"}`, or
     `dev.routines.toggle`); the row's fit is in `dev.routines.state` `ui{needed, available, mode, overflow}`.
     Village V1 (stream roads, docs/VILLAGE.md, docs/BUILDINGS.md "Roads"): the fifth list, **Roads**
     (`sub:"roads"`): building pairs of the player's dimension (entrances <= 256 blocks apart) with their road route
     (length / planning / no route and why) and road (id pill), roads whose building was removed or moved first ("!
     b3 was removed: remove it?"). A pair without a road: **Width 1-3**, **Lanterns: On/Off**, **Bridges: On/Off**, **Lay
     road…** (closes the hub and shows the ghost; Enter lays, Esc cancels) and **Plan again**; a road: its facts and notes
     and **Remove road…** (two-step, like Remove); an orphan: **Remove road…** and **Keep it**. "Place new…" is hidden on
     this list. Button ids `road_*`, rows `pair:<a>|<b>` / `road:<id>` (mod/DEV.md "Roads"). When the switch and
     the right button do not fit (GUI scale 4, ~426 px wide) the counts go first ("Buildings", "Roads": each list says
     them again), then the button reads "Place…" / "Design…" / "Place board…", and at ~426 px Fixtures falls back
to "Place…" too (`dev.roads.state ui.strip.compact`).
  2. **Repos** *(done, branch `mod/goals-tabs`)*: registered repos and their `repoSettings`.
  3. **Goals** *(done in the mod, branch `mod/goals-tabs`; Foreman side in `foreman/goals-tabs`)*: submit a goal (repo, "continue a branch", earlier session) and, per goal:
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
  4. **Team** *(done in the mod, branch `mod/settings`)*: agents, roles, models, effort, the team.
  5. **Settings** *(done in the mod, branch `mod/settings`)*: groups General (your name, notifications, merge style,
     commit identity, cleanup), Permissions, Context (incl. connectors and session history), Subagents, PRs, Usage.
  6. **Status** *(done)*: Foreman connection, backend, auth/account, usage windows (percent, reset
     time), spend, mod and Foreman versions, DevBridge state. Wave 2 (docs/WAVE2.md W7): a second view,
     **Keys & help** (every AgentCraft key with its live binding, every in-world interaction, "Show the
     welcome card").
- Wave 2 (docs/WAVE2.md W5/W6, "As implemented: hud"): tabs carry badges (Inbox needs-you, Goals unread, Repos
  failing CI, Team blocked agents); `H` reopens the world's last tab, or after an away toast the Inbox (else Goals).
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
bundled campus2..5 fit), L (44+18N)×22×48, x capped at 128.

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
- **Materials**: AgentCraft look (default: the AgentCraft style built from vanilla blocks) or any
  vanilla look. Both keep AgentCraft blocks only for the stations (docs/BUILDINGS.md "Materials").
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

#### As implemented (Foreman, branch foreman/goals-tabs)
- Every message above, both backends. Acks: `goal.submit {goalId}`, `goal.message {goalId,
  leadId}`, `goal.instructions {goalId, changed}`, `goal.plan {goalId, planId, changed}`,
  `goal.cancel {goalId, cancelled: taskIds}`, `goal.digest` -> the digest itself, `repo.remove
  {repoId}`. `Digest`, `GoalDigest`, `DigestLine`, `GoalPr`, `RepoSettingsView` are in
  docs/protocol.md.
- The user's goal message is a feed item `kind: "message"`, `agentId: "user"`, `to: <lead>`, with
  `goalId` (plain `user.message` stays `kind: "user"`). The lead's reply is what it sends to the
  user in that turn, else its final text. A done/cancelled goal whose building lead was released
  is answered by marlow. A stopped lead: the feed says so; the message runs at `/resume`. Unread
  goal messages of a lead that is released go to marlow. Goal-tagged `message` feed items keep up
  to 2000 characters (other feed items: 400).
- `goal.instructions` / `goal.plan` messages to the lead come from the user, text "The user changed
  the standing instructions for this goal: ..." / "The user edited the plan ... ```diff ...```".
  Unchanged input: nothing is sent (`changed: false`). `goal.plan` on a goal without a plan
  creates `shared/plan-<goalId>` titled "Plan: <goal text>".
- `goal.cancel` refuses a done goal, is a no-op for a cancelled one, also cancels open decisions
  with that `goalId` and stops the lead's plan/review/triage turn for it. Tasks done before the
  cancel no longer re-open the goal (a cancelled goal re-opens only for open, non-done work).
- `goal.digest`: lines from tasks (added = created in the window; done = done with its last change
  in the window, unless a merged/pr_merged line covers it; blocked = blocked now, changed in the
  window), decisions (`goalId`; waiting = opened in the window and open; answered = answered in
  the window) and feed items with `goalId` (merge lines -> merged / pr_opened / pr_comments /
  pr_merged, "Goal complete" -> goal_done, agent -> user messages). Window: `since < ts <= now`.
  Without `goalId`: only goals with lines. Feed-based lines older than the feed's last 300 items are
  gone.
- `repo.remove` is also refused while a goal of that repo is still `planning`. There is no removal
  broadcast (no message type for it): the requesting client drops it on the ack, others at their
  next snapshot. A repo from `--repo`/`config.repos` comes back at the next Foreman start.
- `Repo.settings` is always present: `land`, `protect`, `roles` and `prReview` (configured values,
  else defaults) always; the others only when configured.
- `Goal.branch` is cleared again when the claude backend cannot use the branch (feed error line);
  the sim records it only. `Goal.planId` for goals planned before this: from the claude backend's
  recorded plans at start.

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

#### As implemented (Foreman, branch foreman/goals-tabs)
- `Goal.repos` is set at submit (`repoId`, then `goal.submit repos`) and grows as tasks reach other
  repositories (order of first appearance); `repos[0]` becomes `repoId` when `repoId` is absent,
  every listed repo must exist. The claude planning prompt says "This goal is for the repositories
  a (primary), b: give each task the one repository it changes" for goals with 2+ repos.

### As implemented (mod, branch `mod/goals-tabs`)
Details and DevBridge in mod/DEV.md "Hub" -> "Repos and Goals tabs". Notes where the mod fills gaps:
- Home: `LeadRouting.leadBuildings` leaves the home building out of `lead.assign`/`lead.sync`; a home change
  sends one `lead.sync`; routing, podiums and `leadForRepo` ignore an assignment to the home building.
- An older Foreman: the new types are acked `ok:false` (schema error on `type`); the mod shows "… needs a newer
  Foreman" inline (thread send, plan, instructions, cancel, digest, repo.remove). Against the merged Foreman: a
  `repo.remove` ok drops the repo locally (no removal broadcast); `changed:false` acks say "unchanged". `goal.submit`'s new fields
  are silently dropped by an old schema: the form says so when the goal comes back without them. Absent fields
  read "not reported". Decisions without `goalId` are put in a goal's thread through their task's `goalId`.
- The thread shows what is in the mod's feed tail (200 items, replaced by every snapshot); older history needs
  a Foreman query (not in this contract).
- Inline decision answers keep the decision screen's guards (350 ms arm, Reject twice, and since wave 2 Merge twice too (AnswerPanel), Request changes needs
  the message box's text); "Open…" opens the decision screen over the hub.
- "Since you were away": asked on opening the hub or the Goals tab when the tab was last looked at >= 10
  minutes ago, once per away stretch; the goal's own digest is asked on opening a goal that had activity since
  its last view. A goal never opened counts as seen as of the Goals tab's previous visit (the tab is marked when it leaves the screen). `hub-seen.json` is keyed by the save
  folder name ("multiplayer" outside singleplayer).
- The new goal form's "For" offers each repo and each group building (2+ repos; it sends all of them as
  `repos[]`, wing order). Branch suggestions: the repo's worktree branches and its goals' branches.
- Compact layout (content area under 470 × 200 GUI px, i.e. GUI scale 4 at 1080p): list or detail with a back
  button; `dev.hub.state` reports `layout.overflow`.

## Team and Settings tabs, config get/set (contract, 2026-10-03)

### Client token (prerequisite)
Today any local process can drive the Foreman's WebSocket, including an agent's Bash (answer its own
decisions, and with config.set loosen its own permissions). From now on:
- The Foreman writes a random token to `<dataDir>/client.token` (mode 0600, new per start) and lists
  its path in the run file.
- `hello` takes `token?`. Without a valid token a connection is **read-only**: snapshot and events, and
  only `hello`/`diff.request`/`goal.digest`/`agent.logs.request`. Every other client message is refused (`ok:false`,
  "read-only connection: no client token").
- The mod (it reads the run file's token path), `tools/foremancli.mjs` and the dev tools send it.
- The agent policy denies agents: reading `client.token` or anything under the Foreman home's profile
  dirs (`~/.agentcraft/<profile>/`, the config file) by any tool, and Bash commands that mention them
  or open a connection to the Foreman's port (best effort; documented as such). Denials say why.
- `--no-client-token` (dev only) restores the old behaviour.

### Protocol
- `config.get { repoId? }` -> ack result `{ file, settings: SettingDef[] }` (global settings, or that
  repo's `repoSettings` when `repoId` is given).
  `SettingDef = { key, label, help, group, type: "bool"|"int"|"enum"|"string"|"stringList"|"model"|
  "effort"|"agentList"|"map"|"secretMap"|"mcpServers", options?: string[], min?, max?, value, default,
  source: "file"|"flag"|"env"|"default", live: boolean, overriddenBy?: string }`. `key` is the config.json path
  (`claude.prWatch`, `claude.agents.kit.model`, for a repo `land`, `pr.draft`, `roles.kit`).
  `model` options are the Opus and Sonnet models plus "default" (no Haiku). No secret values: env
  values, tokens, MCP server env values and headers, credential-looking arguments and URL credentials
  or queries are never returned (wave 3 contracts S1/S2, docs/WAVE3.md).
- `config.set { repoId?, changes: [{ key, value }] }` -> validates every change first (all or
  nothing), writes the config file atomically (unknown keys and other sections untouched, a
  `config.json.bak` of the previous file), applies `live` keys at once, and acks `{ applied: [key],
  restartRequired: [key], overridden: [{key, by}] }` (a key also set by a flag/env is written but stays
  overridden until that flag goes). Broadcast `config.changed { keys, restartRequired }`.
- `foreman.restart {}` -> ack, then the Foreman restarts itself with the same arguments (running turns
  are interrupted and resumed by `resumeOnStart`); the mod reconnects. `foreman.status` gains
  `restartRequired?: string[]`.
- `repo.agents { repoId }` -> ack `{ agents: [{ name, path, description?, model? }] }`: the repo's
  `.claude/agents` files (for the roles picker).

### Editable settings (v1)
- Team: `claude.workers` (from the cast), `claude.leads` (order), `claude.agents.<id>.{title,prompt,
  model,effort}`, `claude.leadModel`, `claude.leadEffort`, `claude.workerModel`, `claude.effort`,
  `claude.taskModels.{small,normal,large}`, `claude.maxConcurrent`, `claude.throttleConcurrent`,
  `claude.maxConcurrentTurns`, `claude.designModel`, `claude.leadReview`.
- Settings: `userName`, `notify`, `toastSilent`, `mergeStyle`, `signMerges`, `commitIdentity`; permissions
  (`claude.permissions.mode`, `allow`, `deny`, `webTools`, `protectCheckouts`); context
  (`claude.context.userInstructions`, `skills`, `sessionHistory.enabled/days`, `maxChars`, `mcpAllow`,
  `connectors`, `mcpServers` - restart); `claude.subagents` (list); PRs (`claude.prWatch`,
  `claude.prPollSeconds`); usage (`claude.maxBudgetUsdPerTurn`, `claude.usageReserve.fiveHourPct` /
  `sevenDayPct`, `claude.leadSession.maxDays` / `maxTurns`, `claude.useClaudeLogin` - restart); team
  (`claude.leadWorldTtlDays`); general (`cleanupAfterDays`; `notify` writes `notify.desktop` when
  config.json holds `notify: {desktop, discord}`, which stays file-only).
- Repo settings (Repos tab "Edit settings", now enabled): `land`, `commitIdentity`, `baseBranch`, `ci`, `setup`, `copy`,
  `setupTimeoutMs`, `ciTimeoutMs`, `protect`, `roles.<agent>` (picker from `repo.agents`), `subagents`, `pr.*`,
  `prReview.*`, `env` (a secret map, wave 3).
- Not editable in the hub: `repos` (Repos tab add/remove), host/port/home/profile, sim settings.

#### As implemented (Foreman, branch foreman/settings)
- **Token**: the run file (`<home>/<profile>/foreman.json`, and `<home>/foreman.json` for the
  primary Foreman) gains `tokenFile`, the absolute path of `<dataDir>/client.token`. The file holds
  64 hex characters and a newline (trim it); mode 0600; a new one on every start; removed on a clean
  exit. Send it as `hello.token`. A refused message gets `error` and (with an id) `ack {ok:false,
  error:"read-only connection: no client token"}`. A connection whose first message is not `hello`
  is read-only. Sending `hello` again with the token makes a connection trusted.
  `--no-client-token` (dev): every connection is trusted. Tools: `--home` / `AGENTCRAFT_HOME` to find
  the run file, `AGENTCRAFT_CLIENT_TOKEN` overrides (never passed on to agents, CI or setup).
- **Agent policy** (src/policy.ts `foremanPrivateVerdict`, checked first in `classifyToolUse` and by
  a PreToolUse hook on every turn, so allow rules, "Always allow" and subagents cannot skip it):
  file tools on anything under the Foreman home except `<profile>/{worktrees,memory,agent-plugin,
  designs}`, on any `client.token` (also through links), Grep over a folder containing the home;
  Bash/PowerShell mentioning such a path (`~`, `$HOME`, relative paths resolved), `client.token`,
  `foremancli`, `$AGENTCRAFT_HOME`/`_CLIENT_TOKEN`/`_PROFILE`, or the Foreman's port next to a
  loopback host or `ws://` (quoting and escapes undone first); recursive search/list/copy/archive
  commands rooted at the home or above it; instruction files and `@imports` that are or reach these
  files are skipped with a note. Best effort (documented in foreman/README.md "Permissions").
- **SettingDef**: as specified, plus `readOnly?: true` (`config.set` refuses the key; no setting uses it
  since wave 3 made `claude.context.mcpServers` and a repository's `env` editable). `value` is the *configured* value (config.json + flags +
  environment now), which for a restart-only key may differ from what is running until the restart.
  Global groups: `team` (workers, leads, leadReview, `claude.agents.<id>.*` for every cast member,
  leads included), `models` (lead/worker/design models and effort, task-size models, concurrency),
  `general`, `permissions`, `context`, `subagents`, `prs`, `usage`. Repository groups: `landing`
  (`land`, `commitIdentity`, `baseBranch`, `pr.*`), `worktrees` (`ci`, `setup`, `copy`, `setupTimeoutMs`, `ciTimeoutMs`, `protect`,
  `env`), `agents` (`roles.<id>` for every cast member, `subagents` as enum `off`/`repo`), `review`
  (`prReview.*`). `claude.subagents` is two keys: `claude.subagents.enabled`, `claude.subagents.agents`.
  `claude.permissions.ask` is not exposed. `claude.maxBudgetUsdPerTurn` is an `int` (whole dollars,
  0 = no cap).
- **Values**: model `options` = `default`, `opus`, `sonnet` and every Opus/Sonnet id the config uses
  (never Haiku). Clearing a key: `null` for any key; also `"default"` for a model or per-agent
  effort, `""` for a string, `0` for `maxConcurrentTurns` / `maxBudgetUsdPerTurn`, `"off"` for a
  repository's `subagents`. Per-agent model/effort show `"default"` when unset; `claude.designModel`
  shows `"default"` while it follows the worker model. Secret settings (wave 3, S1/S2): a repository's
  `env` is a `secretMap` (below); `claude.context.mcpServers` is `mcpServers` (below).
- **secretMap** (S1; a repository's `env`, and each MCP server's `env`): `value` is the list of variable
  names (`["NAME", ...]`), never values. `config.set` takes a partial update `{NAME: "value" | null}`:
  listed variables are set or (null) removed, the others stay; the last one removed removes the key;
  `value: null` removes them all. Names are `[A-Za-z_][A-Za-z0-9_]*`; a repository refuses `GIT_*` and
  `AGENTCRAFT_CLIENT_TOKEN` (an MCP server the token). A placeholder value (`"(set)"`, `"(hidden)"`,
  `"(staged)"`, `"[redacted]"`) or one with control characters other than tab / newline / CR is refused.
  Errors name places (`env key #2 is not a valid variable name`), never names or values.
- **mcpServers** (S2; `claude.context.mcpServers`, restart-required): `value` is `[{name, type:
  "stdio"|"http"|"sse", command?, argCount?, url?, urlHasPath?, headerKeys?, envKeys: string[]}]`:
  `command` is the executable only (the first word of the stored command), `argCount` how many arguments
  are stored, `url` scheme://host[:port] (`urlHasPath`: the stored URL has a path, query or more; no `url`:
  the stored one does not parse), `headerKeys` the names of config.json's headers. Argument values, the
  rest of a command line, URL paths and header values are never returned: they are write-only.
  `config.set` takes `[{name, type, command?, args?, url?, env?: {NAME: "value" | null}} | {name,
  remove: true}]`: each entry adds or changes that one server (the others stay; fields the hub does not
  edit, such as headers, are kept). `command`, `args` and `url` left out keep the stored value exactly;
  sent, they replace it exactly (`args`: the complete new list, `[]` clears it). There are no
  placeholders: an argument that is one (`"(hidden)"`, `--x=(hidden)`, ...) is refused, and a `command` /
  `url` equal to what config.get shows of a longer stored one is refused (it would cut the stored value
  down to its view). The view's read-only fields (`envKeys`, `headerKeys`, `argCount`, `urlHasPath`) may
  be sent back and are ignored (type-checked). Checks: name `[\w-]{1,64}` (not `agentcraft`), type; a new
  server, or one changing between stdio and http, needs a one-line `command` (stdio) or a `url`
  (http/sse); stdio has no `url`, http/sse no `command`/`args`/`env`; a `url` is http(s) without spaces,
  control characters, credentials or a fragment (a query is fine) and is stored normalized; each name
  once; removing a server that is not there is refused. Errors name places (`server #2: ...`), never
  names or values. A change of any value (an env value too) puts the key into
  `foreman.status.restartRequired`.
- **Redaction**: every secret value the Foreman knows (repository and MCP env values, MCP arguments, URL
  paths and queries, header values, the rest of a command line, the client token; at least 6
  characters) is replaced by `[redacted]` - also URL-encoded, JSON-escaped or base64 - in agent logs,
  the feed, `agent.say`, `notify`, desktop / Discord notifications, ack and error texts, setup / test output
  and console logs (foreman/src/redact.ts); stored feed and log text is cut again when the snapshot,
  `agent.logs.request` or `goal.digest` replays it (it may predate a secret).
- **config.set ack**: a key a flag or variable overrides is listed only under `overridden`
  (`by`: the flag as given, e.g. `"--no-notify"`, or the variable name), not under `applied` or
  `restartRequired`. `config.changed.keys` are the keys as sent, repository keys as
  `repo:<repoId>:<key>`; `config.changed.restartRequired` is the **full** pending list (same as
  `foreman.status.restartRequired`, which is omitted when empty). A restart-only key set back to the
  value the Foreman started with leaves the list.
- **Live vs restart** (foreman/README.md has the table): restart-only are `claude.workers`,
  `claude.leads`, `claude.context.skills`, `claude.context.sessionHistory.*`, `claude.subagents.*`,
  `claude.useClaudeLogin`; everything else editable applies from the next turn / tick / poll
  (agent titles and `userName` at once, PR watching switches over at once, `baseBranch` at the
  repository's next refresh).
- **repo.agents**: `{agents: [{id, name, path, description?, model?}]}`, only files with a
  description and a prompt; `id` (the file name without `.md`) is what `roles.<agent>` stores and
  what the `roles.*` SettingDefs offer as `options`. `config.set` also accepts the front-matter
  `name` or the `path` and writes the `id`.
- **foreman.restart**: acked with `{}`; about 150 ms later the server closes and a new process
  starts (same node, flags, script, arguments, environment and cwd, minus `--reset`, `--goal`,
  `--autostart`), on the same port with a **new token**: re-read the run file before reconnecting.
  The run file names the new pid (and the same `tokenFile` path) at once; the new process writes its
  token there before it listens. Verified on macOS (`tools/mac.mjs stop` and `launch` follow it);
  Windows `tools\stop.ps1` still looks only at the pid launch.ps1 recorded.
  Refused (`ok:false`) when the Foreman was not started by `main` (tests).

### Team tab (mod)
- Roster: leads (order, building, model/effort) and workers (on/off the team, title, specialty prompt,
  model, effort, per-repo roles), each with live state (station, task, goal, lead) and portrait.
- Models: lead/worker/design models and effort, task-size models, concurrency limits.
- Edits are staged in the tab and applied with one "Apply" (config.set); changes needing a restart show
  a banner with "Restart Foreman".
- **Agent card** (fix wave 1): an agent's detail has a **Card** button (also `C`, or a double click on
  the roster row) that opens its agent card; Esc in the card returns to the Team tab. The card is also
  reachable from a task screen (click the assignee or press `A`) and the console roster (right-click a
  face); in the world it needs an empty-hand sneak + right-click on the agent.
- **Leads held by other worlds** (contract C2): the Models view lists every other world holding lead
  assignments (`LeadAssignment.world`, else the world part of the building key; `lastSync` shown as
  "synced 3 d ago"), since those make this world's buildings overflow to Marlow. **Release** sends
  `lead.releaseWorld {world}`; the ack's `released` leads are named in the note. A world that is loaded
  again simply syncs its leads back. QA: `dev.team.release {world}`, Team `state.otherWorlds`.

### Settings tab (mod)
- Groups: General, Permissions, Context, Subagents, PRs, Usage (with the Status tab's usage windows
  read-only), each a form generated from `SettingDef`s (bool toggle, enum chips, int stepper, string
  field, string list editor, model/effort pickers). Source badges (file/flag/env/default), "overridden
  by --flag" notes, Apply / Revert, restart banner.
- A destructive or widening change (permission mode to a looser one, removing a deny rule, adding an
  allow rule, `useClaudeLogin`) asks a second confirm naming the change.
- DevBridge: `dev.hub.open {tab: team|settings, group?}`, state for both tabs, actions for every
  control; screens `hub_team`, `hub_settings_<group>`.

### As implemented (mod, branch `mod/settings`)
Details and DevBridge in mod/DEV.md "Hub" -> "Team and Settings tabs" and "Foreman link" (client token). Notes where
the mod fills gaps in this contract (the Foreman side was built in parallel; align or tell the mod):
- **Client token**: the mod reads it on every connect from the run file whose `port` is the one it connects to
  (`<home>/<AGENTCRAFT_PROFILE>/foreman.json`, then `<home>/foreman.json`, then any `<home>/*/foreman.json`), field
  `tokenFile` (as `foreman/settings` writes it; also accepted: `clientTokenFile`, `clientTokenPath`, `tokenPath`,
  `clientToken`; a relative path is relative to the run file), else `client.token` in the run file's profile dir. Sent as `hello.token` only when
  found. `AGENTCRAFT_CLIENT_TOKEN` overrides (dev).
- **Read-only**: any refusal containing "read-only connection" marks the link read-only until it drops; every hub
  tab's footer and every refusal note then say "Foreman did not accept the client token (read-only connection)";
  Team/Settings show it as a banner in place of the form (config.get is refused).
- `foreman.status.restartRequired` is read from the `ForemanStatus` object (snapshot `foreman` and `foreman.status`
  `status`) as the full list (absent = none); `config.changed.restartRequired` replaces it too; a config.set ack's
  `restartRequired` is added until the next of those.
- **config.set** errors: per field from `result.errors` (`[{key, error|message}]` or `{key: message}`), else from the
  error text split on `;`/newlines into `<key>: <problem>`; the rest is shown as the note.
- **Unsetting**: a setting whose default is "" or "default" (a repo role, a per-agent model/effort) is unset by
  staging that default (so "not set" when nothing is set is no change); any other "not set" sends `value: null`
  (the Foreman removes the key). A repo role's choices are the setting's `options` (agent file ids), else the
  `id`s of `repo.agents`.
- **Groups**: global `team` and `models` go to the Team tab (every key of those groups shows there, known or not),
  the others to the Settings tab's chips; a repo's `landing`, `worktrees`, `agents`, `review` are the sections of its
  form. `readOnly: true` (and every `map`) is shown, never edited.
- **Scopes**: the Team tab's per-repo roles are `config.set {repoId}` per repo; Apply sends one config.set per scope
  with changes (global first, stopping at the first refusal): not atomic across scopes.
- Keys the mod synthesises when `config.get` does not list them (so they stay editable): a repo's `roles.<agent>`
  for every roster agent, and `claude.agents.<id>.{title,prompt,model,effort}` for every roster agent.
- `model` / `effort` choices come from each setting's `options`; effort without options falls back to low, medium,
  high, max. `agentList` candidates: `options`, else the cast (leads for `claude.leads`).
- MCP servers: the ack's `mcpServers` (`[{name, command}]` or `{name: command | {command}}`), else a `map` setting
  whose key ends in `mcpServers`. Maps are always read-only in the hub. Wave 3 (S1/S2, pure `SecretSettings`,
  `SecretSettingsTest`): a `secretMap` setting (a repo's `env` in its Edit settings form) and the `mcpServers` setting
  (Settings > Context, restart-required) are edited; an older Foreman's plain map is still listed read-only.
  - **Secret map**: one line per variable (the view is the list of names; an older Foreman's `{NAME: "(set)"}`
    still reads), `NAME (set)` with Replace / Remove, or the staged change ("new value",
    "new value (replaces)", "removed") with Undo; then a name field, a value field and Set (Enter in the value field).
    Values are write-only: typed, staged on Set (the field clears), never shown again; the staged value is the
    partial update `{NAME: "value" | null}`. Checks before staging: a variable name, a repo refuses `GIT_*`, nobody
    takes `AGENTCRAFT_CLIENT_TOKEN`, no placeholder value, no control characters (tab / newline excepted).
  - **MCP servers**: one entry per server (name, type, `npx + 3 arguments` or `https://host/…`, `env:` its
    variable names, `headers:` their names; Edit, Remove or Undo; "new" / "changed" / "removed", "(replaced)" when
    the staged change replaces the arguments or URL), then "Add server…". The form: name (a new server), type chips
    stdio / http / sse, the command (pre-filled with the executable the Foreman shows; sent only when edited) and
    the environment as a secret map (stdio), or the URL (http, sse). Arguments and the URL are write-only and never
    pre-filled: an existing server shows "3 arguments (kept, never shown)" / "https://host/… (kept)" with
    **Replace arguments…** / **Replace URL…**, which open an empty field for the complete new value (one argument
    per line; empty clears them) and **Keep the stored …** to go back; a new server, or one changing between stdio
    and http, gets the fields at once. Opening a staged change again shows its arguments / URL as "(new, staged)"
    and keeps them (and its whole command line) unless replaced (pure `SecretSettings.toSend`). Add / Done stages the server's entry with only what changed (`{name, type,
    command?, args?, url?, env?}`, an edit that changes nothing stages nothing), Cancel drops it. Checks like the
    Foreman's: name `[\w-]{1,64}`, not `agentcraft`, free for a new server; a one-line command; no placeholder
    argument (`(hidden)`, `(set)`, `(staged)`, `[redacted]`, also after `=`); an http(s) URL without spaces,
    credentials or fragment.
  - After Apply the current value is the names-only view (no value is kept in the mod); `dev.hub.state` and
    `settings_set` replies show staged secret values, arguments and URLs as `"(staged)"` (a command line as its
    executable + `"(staged)"`).
- Widening (second confirm): permission mode away from `policy` (strict -> loose: policy, auto; unknown modes count
  as loosest), a deny rule removed, an allow rule added, any change of `claude.useClaudeLogin`.
- Restart: a `foreman.restart` whose ack is lost to the closing socket counts as restarting; "reconnecting" lasts
  until the link is synced with a newer snapshot.- DevBridge names: the task's "set {key, value}", "apply", "revert", "confirm", "restart" are `dev.hub.action`
  aliases of `settings_set`, `settings_apply`, `settings_revert`, `settings_confirm`, `foreman_restart`; "select
  agent" is `team_select {agentId}` (`select` is the Buildings tab's).

## Inbox (wave 2, docs/WAVE2.md W1-W4; branch `wave2/inbox`)

The first hub tab: everything that needs the player or happened for them, in one list. Code:
`client/hub/{Inbox,InboxTab,AgentLogView,InboxDev}`, `client/decisions/AnswerPanel`, the pure
`dev.agentcraft.hub.InboxModel` (unit-tested in `InboxModelTest`) and the read state in `HubSeen`.

- **Items**, grouped **Needs you** (the hold pinned on top, then newest first) and **Updates** (newest first):
  - decisions (all kinds) with the agent, the goal and the building whose podium shows them; open ones need
    you, answered / withdrawn ones are updates (the last 30);
  - agent replies to the player (feed `message` items from an agent `to: "user"`, goal replies included): unread
    ones need you, read ones are updates (the last 60). Read = viewed in the Inbox for a moment, the goal opened in
    its thread, the agent's card opened, or "Mark all read";
  - blocked tasks (reason, assignee, goal);
  - the hold (`foreman.status.hold`, C9): usage / auth / offline, what it means and when it lifts;
  - PRs needing attention: tasks in status `pr` whose PR has changes requested, failing checks or new threads.
- **Filters** (chips): All · Needs you n · Building ▾ (cycles the world's buildings) · Agent ▾ (cycles the agents:
  the agent view). A podium's right-click opens the Inbox on **Podium: b3 ×** (the decisions that podium shows,
  wave 1's `podiumFor` / `LeadRouting.podiumShowsTarget`) with **All decisions** next to it for the full queue.
  The filter is kept between visits. "Mark all read" on the right.
- **Detail** per kind (the body scrolls; the answer / reply area stays at the bottom):
  - decision: the question and its context, the **AnswerPanel** (options, the text box when the decision takes
    text, Review diff for merges; Merge and Reject ask twice, Request changes opens the feedback box), Open
    thread (its goal) and Decision screen (the full decision screen, Esc back to the hub);
  - reply: the whole message, a reply box (Ctrl+Enter or Send: `goal.message` to the goal's lead when the reply
    is about a goal, so it lands in that goal's thread, else `user.message` to the agent), Open thread, Open card;
  - blocked task: the reason in red, assignee, repo, branch; **Retry** (`task.action retry`), Open task (the task
    screen, Esc back), Open card;
  - hold: the line ("usage paused until 14:20"), what it means and when it lifts (`InboxModel.holdExplain`), Usage
    settings / Status tab;
  - PR: why it needs you, state, checks, threads, branch, link; Goal's tasks (the Goals tab's Tasks view of its
    goal), Refresh PRs, Open task.
- **Agent view** (Agent filter, a monitor's right-click, `/inbox @agent`): a pinned first row with the card's
  summary (portrait, title · role, state, activity, task, building) and the agent's **full log**: pages of the
  Foreman's stored log (`agent.logs.request`, 200 per page, both rotation files) joined with the live tail;
  scrolling up at the top loads the page before (the view keeps its place), "Load older" does the same; a message
  box (`user.message`) and Open card. An older Foreman shows the live tail with "older lines need a newer Foreman".
  A failed first page is asked for again after 5 s. When more entries arrive than the live tail keeps (200) while
  the view is open, it pages back from the tail until it meets the fetched pages, so the log has no hole
  (`LogJoin`); the fetched pages stop at 2,000 entries (older ones: the Foreman's `logs/<agent>.jsonl`). The paging
  state (pages, `more`, the entries put in front, the cap) is the pure `LogPager` (`LogPagerTest` covers the `more`
  path: older pages asked with `before` = the oldest ts until a page says `more: false`, then no further request). The
  wrapped lines are cached and rebuilt only when the entries or the width change.
- **Deep links** (W4): podium right-click -> Inbox on that podium's decisions; monitor right-click (empty hand or a
  non-block item; with a block in hand the click places it, so monitor walls still build) -> the Inbox view of the
  agent that panel shows (the feed monitor: the Inbox); console `/inbox [@agent]`; `J` still opens
  the decision screen (the fast path), which hosts the same AnswerPanel.
- **Layout**: compact under 470 × 200 GUI px (GUI scale 4 at 1080p, 4K with auto scale ~426 × 240): the list or
  the detail with "‹ Inbox", shorter chip and button labels; in the compact detail the item's action buttons
  (Thread / Full view, Card, Retry, ...) sit in the top bar next to "‹ Inbox" when they fit there, and the answer
  and reply boxes are one line (they scroll to the caret). Every detail is laid out by `DetailLayout`: the body
  keeps at least three lines (or all of a shorter text) above the pinned answer / reply area; when that does not
  fit, the whole detail flows and scrolls as one column (wheel, `inbox_detail_scroll`), focusing a box scrolls it
  into view, and buttons scrolled under the top bar are not clickable. Nothing is drawn over the pill, title and
  agent line. `dev.inbox.state` `layout` = `{guiWidth, guiHeight, guiScale, compact, width, needed, available,
  overflow, detail{flow, offset, max, bodyH, pinnedY, contentH, actionsInTopBar, fieldLines}, logRebuilds,
  tabStrip{needed, available, overflow}}` (the tab strip's
  seven labels are measured too: the hud stream adds badges to them).

