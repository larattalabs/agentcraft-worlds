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
- Persistence: `<world>/agentcraft-roads.json` (id, buildings, cells, width, created) + a snapshot per road
  (`<world>/agentcraft-roads/<id>.before.nbt`); Remove road restores it; removing or moving a building
  offers to remove its roads (never silently).
- Agents prefer laid roads (lower cost in the planner).
- DevBridge: `dev.roads.state`, `dev.roads.preview {a, b}`, `dev.roads.lay {a, b, width?}`, `dev.roads.remove {id}`.

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
  already under way at connect never get one.
- Library: `memory.upsert` with `author` = an agent; between steps only; a book in the main hand while walking, READ pose
  for 5 s at the `library` slot; cancelled by work or a stand-up; 30 s cap; notes wait at most 2 min; 1 min cooldown.
- Toggles: hub > Buildings, a row under the trophies toggle (`Night`, `Stand-ups`, `Library`), `routines.json` per world,
  default on. DevBridge: `dev.routines.state`, `dev.routines.toggle`, `dev.routines.time`, `dev.routines.standup`,
  `dev.routines.library`.
