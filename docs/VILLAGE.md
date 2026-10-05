# Village life: roads, village board, routines (contract)

Three streams build these in parallel; each owns its area. Everything is singleplayer, works with cheats
off (through the integrated server, like the hub), is Hardcore safe, and is undoable.

## V1 Roads between buildings (stream `roads`)
- Source: the outdoor walking planner's routes (wave 2 W8, `OutdoorRoutes`) between buildings' approach
  ends (the entrance approach of fix/approach). A road follows the route agents actually walk.
- Hub > Buildings: **Roads…** lists building pairs with their route (length, status) and existing roads;
  **Lay road** shows a ghost of the road cells (same renderer style as placement: tan path, orange where it
  replaces something, red where it refuses) and asks to confirm; **Remove road** restores the cells exactly.
- Blocks: `minecraft:dirt_path` on grass/dirt/podzol/mycelium/coarse dirt; elsewhere the biome's natural
  path look (gravel on sand/stone, or `minecraft:packed_mud`/`stone_brick_slab` - pick and document);
  width 2 by default (1-3 configurable per road); clears plants, snow layers, flowers and leaves in the
  walkway up to 2 blocks of headroom; never touches block entities, other buildings, player-placed blocks
  (only natural blocks: use the tags terrain fit uses) or fluids (a route crossing water: shallow (1 deep)
  gets a plank/slab bridge only if the player opts in, else the segment is skipped with a note); never
  creates drops or holes (replaced blocks go into the road's snapshot; nothing falls).
- Optional lanterns on fence posts every ~12 blocks (default on; vanilla) so the paths are lit at night.
- Persistence: `<world>/agentcraft-roads.json` (id, buildings, cells, width, created) + a snapshot per road (since wave 3
  an entry of the world journal, docs/BUILDINGS.md "World journal"; before, `<world>/agentcraft-roads/<id>.before.nbt`,
  imported once); Remove road restores it; removing or moving a building offers to remove its roads (never silently).
- Agents prefer laid roads (lower cost in the planner).
- DevBridge: `dev.roads.state`, `dev.roads.preview {a, b}`, `dev.roads.lay {a, b, width?}`, `dev.roads.remove {road}`, `dev.roads.plan {a, b}` (the route only), `dev.roads.blocks {x0, y0, z0, x1, y1, z1}` (block ids in a box, for restore checks).

## V2 Village board (stream `board`)
- A placeable **fixture**: a small blueprint kind `fixture` (no repos, no lead, not a building for routing,
  leads or trophies; does not count against "one building per repo"), placed and removed with the same
  wizard/ghost/snapshot machinery as buildings (hub > Buildings > **Place village board…**, near spawn or
  anywhere), vanilla frame (dark oak/stone) with one functional AgentCraft block: `agentcraft:village_board`
  (a wall display, multi-block like the task board: same facing, adjacent, connections recomputed).
- Shows: every building (name, repos, lead portrait/name, active goal + progress, CI/PR state: open PRs,
  merged this week), the newest milestones (goals done, PRs merged, trophies hung; newest first), holds
  (usage/auth). Readable from ~8 blocks; pages if there are many buildings.
- Right-click: opens the hub (Buildings, or the Inbox when something needs the player).
- DevBridge: `dev.board.state`; screens for QA.

## V3 Routines (stream `routines`)
- Night (world time 13000-23000): idle agents (no task, not answering) go to rest: a free `bed*` anchor in
  their building if present (agent lies in the bed pose), else the lounge seats; working agents keep
  working. Morning: they return to their desks/lounge. Nameplate shows "resting".
- Blueprints: add 2-4 vanilla beds (`bed`, `bed_2`.. anchors; per wing for campuses) to every bundled
  design where they fit (a small rest corner), keeping all checker rules (shell, light, standing cells).
- Stand-up: when a goal becomes active with its first tasks assigned, the goal's lead and those workers
  gather at the building's `meeting` (or podium) for ~20-30 s with speech bubbles (the lead: the goal's
  title / plan's first line; workers: their task title), then go to work. Skipped when the player is far
  away (> 64 blocks) or the building is not loaded.
- Library: when an agent writes a memory note (`memory.upsert` by that agent), it walks to the building's
  `library` spot holding a book (held-item render), stays ~5 s, then returns (only when idle or between
  steps; never interrupts a running task's position more than that).
- Settings: per-world toggles in hub > Buildings: "Night routine", "Stand-ups", "Library visits" (default on).
- DevBridge: `dev.routines.state`, `dev.routines.time {ticks}` (or reuse /time in dev worlds), `dev.routines.standup {goalId}`.

## As implemented: routines (branch `village/routines`)
- Pure scheduling in `mod/src/main/java/dev/agentcraft/routine` (`RoutineRules` priorities + night window, `BedPicker`,
  `StandupTracker`, `LibraryVisits`, `RoutineSettings`; `RoutineLogicTest`); client `client/agents/Routines` hooked into
  `AgentManager` (the station key per agent; beds; lying; bubbles; the book). Details: mod/DEV.md "Village routines".
- Night: 13000 <= time of day < 23000 on the overworld clock. Idle = no task and not working/thinking/in an error, or off
  shift; agents waiting on the player or walking between buildings never rest. Beds: plain `bed`, `bed_2`.. anchors (no
  `@<n>`: the free bed nearest the agent's wing task wall / desk; a bed the player sleeps in is skipped); no free bed: the
  lounge. Lying = vanilla's sleeping pose on the render state of the client-only agent (nothing in the world changes);
  the nameplate says "resting". Morning: up beside the bed, back to the desk / lounge.
- Blueprints: kit `bed()`, checker rule for `bed*` anchors (optional), workshop and studio three beds, each campus wing two
  (docs/BUILDINGS.md "Beds"). The bundled fingerprints changed: existing buildings get "blueprint changed" notes until moved.
- Stand-up: once per goal (`goalId:createdAt`), 3 s after the first assignment of an active goal, in the lead's building
  (`meeting` slots, else `user`); only participants routed to that building gather; lead line = the plan note's first
  line, else the goal text; workers say their first open task's title; 20-30 s. Skipped (recorded) when off, the player
  is > 64 blocks from the building box, its chunks are not loaded, nobody is routed there, or it has no spot; goals
  already under way at connect never get one. A goal without a lead (the sim's) is opened by its building's lead, else Marlow for the home building.
- Library: `memory.upsert` with `author` = an agent; between steps only; a book in the main hand while walking, READ pose
  for 5 s at the `library` slot; cancelled by work or a stand-up; 30 s cap; notes wait at most 2 min; 1 min cooldown.
- Toggles: hub > Buildings, a row under the trophies toggle (`Night`, `Stand-ups`, `Library`), `routines.json` per world,
  default on. DevBridge: `dev.routines.state`, `dev.routines.toggle`, `dev.routines.time`, `dev.routines.standup`,
  `dev.routines.library`.
## As implemented: board stream (branch `village/board`)

**Fixtures** (`kind: "fixture"`, `wings: 0`; docs/BUILDINGS.md "Fixtures"):
- A fixture is a `Building` record with **no repos** (`Building.isFixture()`), in the same `agentcraft-buildings.json`, the same
  `b<n>` id sequence and the same world journal entries (kind `building`), so place, ghost, terrain fit, snapshot, Remove (asks twice, restores exactly),
  Move, Undo move, crash safety and the world-start check work unchanged.
- `Buildings.all()` is every placed site, buildings **and** fixtures: the safe default for overlap and collision (the ghost,
  placement and roads use it: a road laid through a fixture's restore box would be overwritten when the fixture is removed, and
  removing the road would restore the old cells over the board). The other way round, a board (or a building) placed **over**
  an existing road is safe since wave 3: the world journal layers the board over the road's cells, so the two undo in either
  order (removing the road hands its cells under the board down to the board's entry, and removing the board then gives
  the ground back, not the road; docs/BUILDINGS.md "World journal"). `Buildings.buildings()` leaves fixtures out: routing sites,
  leads and `lead.sync`, trophies, the Inbox, Goals, the HUD welcome rule and the hub's building list use it.
  `Buildings.fixtures()` lists the fixtures; `Buildings.get(id)` returns either.
- A fixture is never home (place, rehome, load and reconcile skip it), refuses Make home and Edit repos, and takes no repos
  (`place` refuses any; `move` skips the repo checks). One building per repo is untouched.
- Placing: hub > Buildings > **Fixtures** > **Place village board…** (compact "Place board…"), a fixture blueprint's **Place**
  in the Blueprints list, `/agentcraft place village_board [-] [rotation] [force]` (or just `/agentcraft place village_board`), or `dev.build.start {blueprint:
  "village_board"}` (no repos). The ghost and the HUD say "Placing Village board" (no "for"); the ghost's overlap check covers
  buildings and fixtures (`GhostModel.refusals(fixture, ...)`). The repo and blueprint wizard steps never offer fixtures.

**The block** `agentcraft:village_board` (`VillageBoardBlock extends PanelBlock`, depth 3, own block entity
`VillageBoardBlockEntity`, binding empty): connects like the task board (same block, same facing, adjacent; recomputed after
placement by `Buildings.connectPanels`), never to a task board. Its blockstate and item reuse the task board's models (the
renderer draws its own slate over the linen); no loot table, so breaking it drops nothing (no free items in survival).

**The blueprint** `tools/blueprints/designs/village_board.mjs` (7 x 7 x 4, front south, approach off): a 5 x 3 display on a
stone plinth between stripped dark-oak posts, a dark-oak plank wall behind every display cell, a dark-oak slab hood, lanterns on
the posts, a paved reading strip in front (walk). Anchors: `board` (centre of the display surface, kit `villageBoard()`),
`spawn` (on the strip, facing the board; Teleport lands there), `cam_overview`. Checker rules for fixtures: `board` + `spawn`
required, `board` on a village_board with matching yaw; no walk/light/"reachable through the walls" rules (it stands outdoors);
the no-mod shell leak check stays, plus a full opaque vanilla block behind every AgentCraft cell; no `repo:`/`ci:` bindings; no
trophy slots; a warning without any vanilla light source.

**Content** (`dev.agentcraft.village.VillageBoard`, pure, `VillageBoardTest`):
- One row per building, home first, then placement order: `b3 Workshop` (+ "· home"), the lead's portrait and name (the hub's
  `LeadsFeature.leadLabel`), the repos by their Foreman names, the **active goal** = the newest open (planning/active) goal that
  belongs to the building by `Displays.goalBelongs` (its repos or lead; home takes the rest) with its progress (bar along the
  card's bottom, `55%`, `+N` other open goals), **open PRs** (red when an open PR's checks fail) and **PRs merged this week**
  (the local Monday 00:00; a PR's repo is the task's, else its goal's).
- **Milestones**, newest first, at most 12 (as many as fit are drawn): `TrophyEvents.catchUp` of the Foreman state (goal done,
  PR merged, task merged locally: the trophy keys), each marked with a brass dot when its trophy hangs on a wall; hung trophies
  whose key the Foreman no longer has (an older database) show from their sign lines.
- **Hold** (C9): a clay banner "Agents paused: usage paused until 14:20" (`InboxModel.holdText`).
- Footer: "Needs you: 2 decisions · 1 blocked · right-click for the Inbox" while the Inbox's Needs you is above 0, else
  "Right-click: hub > Buildings". Header: "Village board", the building count and the page.
- **Paging**: rows that do not fit turn pages every 10 s (`VillageBoard.page`). **Density**: `round(156 / height)` px per block,
  40..64 (52 on the bundled 5 x 3: 260 x 156 px, text 8 px = 0.154 blocks, about 1.1° at 8 blocks; 3 building rows per page (2 while the hold banner shows) and 4
  milestones on the bundled board). Light floor 13 like the task wall.
- Data: the Foreman state, buildings and leads on the client thread; the trophy ledger is copied on the server thread every 5 s
  while a board was drawn in the last 30 s (`Trophies.hung()`), never read across threads. The content is rebuilt only when the
  Foreman revision, the buildings, the ledger copy, the Inbox revision, the link or the 30 s clock changed; the per-board drawing
  (`BoardView`) only when the content, page, size or density changed. Extract and submit run under `Guard`.

**Right-click** (`StationInteractions`, guarded): the Inbox on "Needs you" when something needs the player, else the hub on
Buildings. Sneak-right-click places blocks as usual.

**DevBridge**: `dev.board.state` (content, each board drawn in the last 30 s: origin, size, ppb, `textHeightBlocks`,
`rowsPerPage`, `twoColumns`, `milestoneSlots`, page/pages, rows and milestones shown, every text on it; the placed fixtures),
`dev.board.set {page?, ppb?, lightFloor?}`, `dev.board.aim {board?, distance?: 8}` (an eye 8 blocks in front of the centre for
`dev.camera`), `dev.board.use` (the right-click's action, returns where it went); hub: `dev.hub.open {sub: "fixtures"}`,
`dev.hub.action {action: "place_board"}` (then `dev.build.*`), remove/move/undo_move/teleport act on a fixture id; screen
`hub_fixtures`.

## As implemented: wave 3 stream world (branch `w3/world`)

- **World journal** (contract J1, docs/BUILDINGS.md "World journal"): buildings, fixtures, roads and trophy signs record every
  world change in `<world>/agentcraft-journal/` and undo through it; existing worlds are imported once (snapshots, pending
  removals and moves, road snapshots and removals, the trophy signs that hang); the old folders move to
  `agentcraft-journal/legacy/`. A board placed over a road, a road shared by two roads, a trophy over a building: every order
  of removal gives the ground back without holes or resurrected blocks. `dev.journal.state`, `dev.journal.at`.
- **Natural drops** never block Remove (docs/BUILDINGS.md "Safe remove").
- **Server verdict** (S4), **site warnings** and **too far** in the wizard (docs/BUILDINGS.md "Wizard (client)", "Site
  warnings").
