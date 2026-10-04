# Buildings: blueprints, placement, one building per repo (Phase 2 contract)

The contract the Phase 2 workstreams build against. Change it here first, then in code.

## Blueprints

A blueprint is two files with the same id (`[a-z0-9_]+`):

| file | what |
|---|---|
| `<id>.nbt` | a vanilla structure template (gzipped NBT, `DataVersion`, `size`, `palette` with entries `{id, properties}` (26.3 renamed `Name`/`Properties`; the old keys place nothing, silently), `blocks`, `entities: []`), exactly what a structure block saves; no size limit beyond vanilla's template format |
| `<id>.blueprint.json` | the sidecar below |

Where they live:
- bundled: `mod/src/main/resources/data/agentcraft/structure/<id>.nbt` (so vanilla `/place template agentcraft:<id>` works too) and `mod/src/main/resources/data/agentcraft/blueprints/<id>.blueprint.json`
- the user's own: `<gameDir>/agentcraft/blueprints/<id>.nbt` + `<id>.blueprint.json` (loaded at start and on `/agentcraft blueprints reload`; same id overrides a bundled one)
- sources: `tools/blueprints/` generates the bundled ones (parametric designs as code)

### Sidecar `<id>.blueprint.json`

```json
{
  "id": "workshop",
  "name": "Workshop",
  "description": "A one-repo office: six desks, a task wall, a podium, a small lounge.",
  "kind": "single",
  "wings": 1,
  "size": { "x": 21, "y": 9, "z": 17 },
  "groundY": 1,
  "front": "south",
  "materials": "agentcraft",
  "foundationBlock": "minecraft:stone_bricks",
  "walk": { "minX": 1, "minY": 1, "minZ": 1, "maxX": 19, "maxY": 7, "maxZ": 15 },
  "anchors": {
    "desk_kit": { "x": 4.5, "y": 1.0, "z": 3.5, "yaw": 180.0, "pitch": 0.0 },
    "task_wall@1": { "x": 10.5, "y": 3.0, "z": 15.0, "yaw": 0.0, "pitch": 0.0 }
  }
}
```

- Coordinates are relative to the template's own origin (its minimum corner, `0,0,0`), unrotated.
  `groundY` is the template row that sits on the terrain surface (rows below it are foundation).
  `front` is the direction the entrance faces in the unrotated template.
- `groundY` is the feet row: the row a player stands in at ground level. Row `groundY - 1` is the
  floor and replaces the terrain's top block; rows below it are foundation.
- Seat anchors (`desk_<id>`, `seat_<id>`, `lounge*`, `meeting*`) have their feet cell inside the
  seat block (stairs), as in the studio: a seat block under the feet, two free cells above, and a
  free standable neighbour cell to step from.
- Block-entity NBT is only ever `{"binding": "..."}`; every cell of a multi-block monitor or task
  board carries the same binding; `memory_catalog` has no block entity; `console_terminal` and
  `decision_podium` are written unbound.
- The whole `walk` region is written (interior air included) so placing a building clears it.
- Front doors are written closed (in Hardcore an open door lets mobs in at night); agents inside a
  building do not need to path through it, and moving between buildings teleports for now.
- `foundationBlock` (contract C4): the vanilla block placement fills below the floor with ("Terrain fit"
  below). Optional, default `minecraft:stone_bricks`; a bare `cobblestone` means `minecraft:cobblestone`; an
  unknown id falls back to the default (logged, and a sidecar warning).
- `kind`: `single` (one repo) or `group` (up to `wings` repos; wing `n` is the n-th repo chosen).
- `walk`: the walkable region (becomes the building's layout bounds after placement).
- `anchors`: the names and meanings of `dev.agentcraft.layout.AnchorNames` (spots = feet position,
  block anchors = centre of the surface, `cam_*` = eye position). Required in every blueprint:
  - `desk_<id>`, `seat_<id>` (if a chair) and `monitor_<id>` for every cast worker: `juniper`,
    `kit`, `wren`, `rowan`, `tove`
  - `meeting`, `lounge` (+ `lounge_2..`), `library`, `terminal`, `testbench`, `mergestation`, `user`
    (fewer slots are fine; agents overflow beside the first slot)
  - `task_wall@<n>` for every wing n (single: `task_wall@1`), `decision_podium`, `goal_atrium`,
    `entrance`, `spawn`
  - `cam_overview` (+ any `cam_*` for QA shots)
  Per-wing anchors use the suffix `@<n>`; placement rewrites them (see below).
- Station blocks in the template carry their binding in block-entity NBT (`{"binding": "..."}`).
  Wing placeholders: `repo:#<n>` (a task wall showing wing n's repo) and `ci:#<n>` (a CI lamp)
  are rewritten to `repo:<repoId>` / `ci:<repoId>` at placement. Monitors bound to an agent id
  (`kit`) as today.
- Materials: AgentCraft blocks first (`agentcraft:plaster_panel`, `plaster_frame`, `walnut_panel`,
  `walnut_trim`, `terracotta_tile`, `oak_parquet`, `glow_panel`, `glow_strip`), vanilla where needed
  (glass, doors, stairs, lights). Multi-block monitors / task boards: same block, same `facing`,
  adjacent; store `up/down/left/right` = false (connections are recomputed after placement).

## Buildings in a world

A placed blueprint is a building:

```json
{ "id": "b3", "blueprint": "workshop", "repos": ["pocket-api"], "home": false,
  "origin": [120, 64, -40], "rotation": "clockwise_90", "bounds": {...world...},
  "anchors": { "desk_kit": {...world, rotated...}, "task_wall": {...} }, "placedAt": 1759500000000,
  "dimension": "minecraft:overworld" }
```

- `dimension`: where it was placed. Records written before the field existed have none: they read as
  the overworld, and the hub's Remove/Teleport fall back to checking that the box in the player's
  dimension holds AgentCraft stations. `Buildings.remove` (and so `/agentcraft remove`) refuses in
  another dimension than the recorded one.

- Persisted in `<world>/agentcraft-buildings.json`; one building per repo (a repo in two buildings
  is refused). The first building placed is `home` (idle agents go there) until changed.
- Anchors are stored in world space, rotated. Per-wing anchors drop the suffix and gain the repo:
  `task_wall@2` -> `task_wall:<repoId>`; for a single building `task_wall@1` -> `task_wall`.
  A group building also keeps wing 1's anchor under the plain name (`task_wall`), so consumers that
  only know `task_wall` work in every building. Anchors of wings without a repo are dropped; their
  `repo:#n` / `ci:#n` bindings stay unrewritten (they match no repo).
- `origin` is the rotated box's minimum corner (vanilla rotates about the template's origin cell, so
  placement shifts by the rotated box's minimum). The file also stores `box` (the world box the
  template and its snapshot cover); ids `b<n>` are never reused (`next` in the file).
- Before placing, the blocks and block entities in the target box are saved to
  `<world>/agentcraft-buildings/<id>.before.nbt`; removing the building puts them back exactly.
- Placement refuses to overwrite block entities the mod did not place (chests, spawners, ...)
  unless forced, and is only ever done on an explicit command / wizard confirm.
  It always refuses a box that overlaps another building in the same dimension (removing the older one
  would break the newer one; the foundation counts), a repo that already has a building and more repos
  than wings.
- More fields (fix wave 1): `snapshotBox` (the box the snapshot covers when the foundation reaches below
  `box`; absent = `box`), `revision` (the layout revision; absent = `placedAt`; bumped by a repo change or a
  move so agents pick up the new anchors), `movedFrom {x, y, z, rotation, dimension}` (the site before the
  last move: the hub's Undo move), and in the file `pending: [{building, snapshot, at, why}]` (see "Crash
  safety").

### Occupancy (who is in the way)

`Buildings.place` (and `move`) refuse, naming them, when the box (foundation included) holds: a player whose
box grown by one block touches it; tamed, owned or leashed animals; villagers, armor stands, item frames,
minecarts and any other entity that may matter (named mobs, mobs that picked up loot); dropped items ("pick
them up first"). Hostile mobs that would despawn anyway (no name, not persistent) and stray projectiles/XP are
removed with a note ("removes 2 × zombie in the box"): the player cannot shoo a creeper out of a box at
night, and that is the safer UX; anything the player might care about refuses instead. The ghost uses the
same rules (`building.Occupancy`), so the HUD says what the server will say. A door cut in half by the box's
top or bottom face refuses too (raise or lower the building); a tall plant cut that way loses its outside
half.

### Fluids

The placement ray stops at fluids (aiming at a lake lands on its surface, not its bed) and the ground search
stops at the first fluid. Water and lava in the box grown by one block sideways and one below (and in the
foundation fill) are counted: **lava refuses** the placement (server and ghost), water is a warning (the
foundation fills the water below the floor; water next to the walls stays). The ghost draws water blue and
lava amber.

### Terrain fit (contract C4)

- **Foundation**: below every floor-row cell of the footprint (template row `groundY - 1`), the cells that are
  air, fluid or replaceable after the template is placed are filled downwards with `foundationBlock` until
  solid ground, at most 12 blocks; block entities stop it. A template's own foundation rows stop it at once.
- **Cleared**: natural terrain (dirt, stone, sand, gravel, snow, ores, plants...) at or above the ground row
  inside the box that the template does not write is cleared to air, so a slope no longer buries walls (the
  box corners beside a porch included).
- The snapshot box extends down to the lowest foundation cell (`snapshotBox`), so Remove restores all of it.
- The wizard puts the ground row on the **median surface** of the footprint's columns (motion-blocking,
  fluids count, leaves do not; columns more than 12 blocks from the looked-at spot are ignored), not on the
  looked-at spot; PgUp/PgDn still raise and lower it.
- The ghost draws the foundation grey and the cleared cells pale; the HUD counts both. Pure logic:
  `building.TerrainFit` (`TerrainFitTest`).

### Safe remove

Remove refuses, listing them ("Move these out of b3 first: chest at 1,64,2 (12 items), white bed at ...,
3 dropped item stacks. Or confirm again with force: they are lost"), when the box (foundation included)
holds what the building did not bring: block entities at positions where the template has none (a chest,
furnace, bed or barrel the player placed), template containers or lecterns the player filled, dropped items,
pets, villagers, item frames, paintings and armor stands. Remove, Move and Undo move also refuse while a player stands in or
next to the site getting its old terrain back (every path, `/agentcraft remove` included: it would bury
them). Forcing is a further explicit confirm: the hub's
button turns into "Remove anyway", `/agentcraft remove <id> force`. Nothing is deleted silently. Drops are
only cleared when the placement/removal itself made them: the items and XP around the box are recorded
before, and only new ones are removed (right after and again three ticks later), never the player's own drops
lying there.

### Crash safety

Removing a building (or moving it away) restores its site at once, but the restored chunks only reach the disk
with the next world save. The snapshot is therefore kept, and the site recorded under `pending` in
`agentcraft-buildings.json`, until a flushed save (stop, `save-all flush`) or the second save since (autosaves
queue chunk writes without waiting for them); then the snapshot is deleted. At world start every building is
checked against the world (by block, not state: lamps, podiums, monitors and doors change states; at least
80 % of the template's blocks must be in place):
(a building whose blueprint or dimension is not loaded cannot be checked and is skipped)
- a building whose template does not stand is reported in the hub (Buildings tab "Check", `/agentcraft
  buildings`): Remove restores the terrain saved before it was placed, Forget only drops the record; nothing
  is deleted automatically;
- a move that did not reach the disk (the new site is empty, the old one stands): the old record comes back
  with its snapshot (the unused one is kept as `<id>.unused-<ms>.nbt`);
- a removal that did not reach the disk (the building stands again): its record comes back.

### Change a building's repos

`Buildings.setRepos(server, id, repos)` (hub "Edit repos…", `/agentcraft repos <id> <repo>[,<repo>...]`):
wing n becomes `repos[n-1]`. Station bindings `repo:<old wing n repo>` / `ci:<old wing n repo>` and unfilled
`repo:#n` / `ci:#n` are rebound to the new wing n repo, a wing that loses its repo goes back to `#n`
(`BlueprintTransform.rebindBinding`); the per-wing anchors are derived again from the blueprint. Refuses
more repos than wings, a repo that has another building and a blueprint that is not loaded. The lead sync
(`LeadsFeature`'s listener) then sends `lead.assign` with the new repos.

### Move a building

The hub's "Move…" puts up the ghost of the building's blueprint (its repos, "Moving b3" in the HUD); Enter
runs `Buildings.move(level, id, origin, rotation, force)`: every check of `place` at the new site (it may not
overlap the building's current site), the old site's safe-remove check (Shift+Enter forces after a refusal),
then the template at the new site, then the old site restored from its snapshot (kept until the next save, as
for a removal). The id, repos, lead and home flag stay; the layout revision changes. `movedFrom` records the
old site; "Undo move" (`Buildings.undoMove`) moves it back there (one step).

## Server API (mod, `dev.agentcraft.building`)

- `Blueprints`: registry (bundled + user folder), `get(id)`, `all()`, `reload()`.
- `Buildings`: `all()`, `get(id)`, `forRepo(repoId)`, `home()`, `layoutFor(repoId)` (that repo's
  building layout, else `Anchors.current()`), `place(level, blueprint, origin, rotation, repos,
  force) -> Building`, `remove(level, id)`, `forget(server, id)`, `setHome(server, id)`, persistence,
  change listeners. Errors are `Buildings.BuildingException` with a player-facing message.
- Commands (gamemaster): `/agentcraft blueprints [reload]`, `/agentcraft buildings`, `/agentcraft build` (the wizard),
  `/agentcraft place <blueprint> <repo>[,<repo>...] [rotation] [force]` (in front of the player,
  ground at the player's feet), `/agentcraft remove <buildingId> [forget]`, `/agentcraft home <buildingId>`.
- `Anchors.current()` keeps working: in the AgentCraft HQ world it is the studio as today; in any
  world with buildings it is the home building's layout (shown, not saved as
  `agentcraft-anchors.json`). Per-building lookups go through `Buildings`. Buildings placed in the HQ
  world (e.g. by the verify loop) never replace the studio there.

## Client (routing)

- An agent's building: the one for its task's repo (`agent.repoId`, else its task's repoId); anyone
  else, or a repo without a building: home.
- Leads (docs/PRWATCH.md "A lead per building"): each building gets a lead from the Foreman. The mod
  sends `lead.assign {building: "<worldId>/<buildingId>", repos}` when a building is placed (any path),
  `lead.release` when it is removed or forgotten, and `lead.sync` for the whole world on connect and on
  world load (worldId = the save folder name). A lead's building = its assignment's (only a building of
  this world that exists); Marlow and unassigned leads: home. The lead works at its building's
  `meeting` / `decision_podium` (whatever station the Foreman gives it; no desk -> `meeting`), ignoring
  its goal's repo. A newly assigned lead walks in from its building's `entrance` (else `spawn`); a
  released one walks to the home `entrance` and leaves. With a Foreman that does not publish leads the
  lead follows its goal's repo, as before.
- Podiums (`LeadRouting.podiumFor`): a decision shows on the podium of its lead's building (an assigned
  lead with a podium); a worker's decision on the podium of the building of its repo (the decision's
  `repoId`, else its task's, else its agent's task's); Marlow's decision for a repo of an overflow building he
  leads (no lead of its own) on that building's podium; everything else on the home podium (and the HQ
  studio's). The podium's bubble, its `open` state, its signal bulbs and `decisions` lamps follow the same
  rule, and a right-click opens the decision queue filtered the same way ("b3's podium · A: all": the chip or
  `A` while not typing shows all). The hub's Buildings tab shows each building's lead; a `repo:` task wall's
  title shows its repo's lead.
- Per-building displays (`building.Displays`): a building's monitors light for an agent only while that
  agent is routed to the building (dim otherwise); its merge stations (their cards, click, `merge` lamps and
  bulbs) show the merges of its repos (the home building also repos without a building, and merges without a
  repo); its `goal` / `goal:atrium` lamps show the newest goal of its repos or its lead (home: also goals
  without a repo or whose repos have no building). The HQ studio keeps showing everything.
- Station targets, desks, seats, monitors and the pathfinder use that building's layout; moving
  between buildings teleports (for now).
- Dimensions: every building is driven in its own dimension (`ServerTasks.run(dimension, ...)`; the HQ studio
  is in the overworld): lamps, podiums, merge stations, monitors and signal bulbs. Agents only route and
  spawn to buildings in the player's dimension; an agent whose building and home are both elsewhere is not
  shown (rather than appearing at home's coordinates in the wrong level). Lookups by position
  (`Routing.siteAt/regionAt` with a dimension) never confuse two buildings at the same coordinates in two
  dimensions.
- Task walls bound `repo:<repoId>` show only that repo's tasks, titled with the repo's name; unbound show
  all (as today).
- Off-shift agents idle in the home building. A building without the agent's desk, its station or a
  lounge sends it home. Lamps, podiums, monitors and particles work in every building (its box), not
  only the home one; `ci:#n` means the n-th repo only in the HQ studio.

## Wizard (client)

The hub (`H`, docs/HUB.md) also starts it: "Place new" opens step 1, a blueprint's "Place" opens step
1 with that blueprint fixed (Next checks the repo count and goes straight to placement).

`/agentcraft build` (a server subcommand: the client installs `BuildingCommands.wizardOpener`, so the
server tree stays the only `agentcraft` root; a dedicated server answers with the `place` usage) or
the key `B` (Options > Controls > AgentCraft, rebindable) opens it. Singleplayer only. The key is not
gated on the gamemaster level the commands need: the world is the player's own and placing is
explicit and reversible.

1. Repos: one (single building) or several (group building, wing n = the n-th repo picked) from the
   Foreman's repos; repos that already have a building are shown disabled with its id. With the
   Foreman offline (or Tab) the ids are typed, comma separated.
2. Blueprint: single blueprints for one repo, group blueprints with `wings >= n` for n repos; name,
   kind, size, description and a top-down preview (each column's highest block in its map colour,
   entrance at the bottom). Confirm closes the screen and starts placement. **Too few wings**: when no
   blueprint takes n repos (5 repos with a 4-wing campus), the step says so ("No blueprint has 5 wings (the
   most is 4)") and offers **Design new…** (the generator form preset to a group of n wings, at most 8) or
   **Split** (a building for the first half now, rounded up and capped at the most wings any blueprint
   has; a toast names the repos for the second one).
3. Placement (no screen): a translucent ghost follows the look. The entrance faces the player, the
   near edge sits on the targeted block (ray up to 64 blocks; a wall hit drops to the ground below)
   and the ground row on its surface; looking at nothing places it like `/agentcraft place` (feet,
   2 blocks ahead). Drawn: the template's exposed faces in each block's map colour (~35 %), cells at
   or above the ground row that would replace a solid block in orange (advisory: placing replaces
   them; floor/foundation rows replacing terrain are not flagged), block entities the mod did not
   place in strong red, the outline of the drawn footprint (red when `place` would refuse; the whole
   box `place` reserves is added faintly when the refusal is an overlap, block entities or the player
   standing in it)
   and a brass bar on the front-most entrance face. The outline follows the drawn columns, not the
   template box: a template need not write every cell of its box (the studio's porch is 7 of its 37
   columns wide; the box corners beside it stay terrain). The HUD shows the blueprint, repos, rotation, the verdict (`place`'s refusals, computed on
   the client: repo already built, too many repos, build height, overlap, block entities) and counts.
   Keys (consumed before vanilla): `R` rotate (Shift+R back), arrows nudge (relative to the view),
   PgUp/PgDn raise/lower, `L` lock (the ghost stays when looking away; L's advancements screen is
   not opened while placing), Enter place, Esc/Backspace cancel. A refusal over block entities arms
   Shift+Enter (force) as a second, deliberate confirm.
4. Confirm runs `Buildings.place(level, bp, origin, rotation, repos, force=false)` on the integrated
   server in the player's dimension; the building id (or the refusal) comes back as a toast and HUD
   line. A refusal keeps placement mode.

## Verify loop (tools)

`tools/blueprints/verify.mjs <id>`: with the dev client running (`node tools/mac.mjs launch
--backend sim --dev`), places the template in the dev HQ world away from the studio, takes an
exterior orbit (4 views) + interior views (each `cam_*` anchor), writes PNGs and a contact sheet
under `artifacts/shots/blueprints/<id>/` (see `tools/blueprints/VERIFY.md`), and checks the sidecar (required anchors, anchors inside
`walk`, standing anchors on a floor with head room).
