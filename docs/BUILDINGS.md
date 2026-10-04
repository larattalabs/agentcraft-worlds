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
  "approach": { "length": 6, "width": 3, "block": "minecraft:stone_bricks", "slab": "minecraft:stone_brick_slab" },
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
  building do not need to path through it, and moving between buildings teleports for now. Every
  outside door is a `minecraft:iron_door` (zombies cannot break it) with a `minecraft:stone_button` on
  both sides (inside and outside), each on a full, conductive vanilla block next to the door (the
  button powers that block, the block powers the door; a button on glass does nothing). The kit's
  `door()` writes exactly that.
- `foundationBlock` (contract C4): the vanilla block the mod fills below the floor row down to solid
  ground on placement (default `minecraft:stone_bricks` when absent). Bundled: `stone_bricks` (studio,
  campus*), `cobblestone` (workshop). Must be a full, opaque `minecraft:` block.
  An unknown id falls back to the default (logged, and a sidecar warning).
- `approach` (see "Entrance approach"): the path placement builds from the door to the terrain: `length` rows
  out from the box (0..16, default 6; `false` = no approach), `width` cells across (1..7, default 3, centred on the
  `entrance` anchor), `block` the path block (default `minecraft:dirt_path`), `slab` the half-step slab (default
  `minecraft:stone_brick_slab`). Absent = the defaults. The bundled blueprints continue their stone porch path
  (`stone_bricks` / `stone_brick_slab`).
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
  - optional: `trophy@<n>`, `trophy_2@<n>` .. `trophy_<k>@<n>` (see "Trophy slots"; a wing without any is a
    checker warning, so user and generated blueprints without a trophy wall stay valid)
  Per-wing anchors use the suffix `@<n>`; placement rewrites them (see below).
- Station blocks in the template carry their binding in block-entity NBT (`{"binding": "..."}`).
  Wing placeholders: `repo:#<n>` (a task wall showing wing n's repo) and `ci:#<n>` (a CI lamp)
  are rewritten to `repo:<repoId>` / `ci:<repoId>` at placement. Monitors bound to an agent id
  (`kit`) as today.
- Materials (contract C5): structure, floors, walls, roofs, trim and light are **vanilla blocks**, so a
  world opened without the mod keeps its buildings and only the station blocks go missing.
  AgentCraft blocks only where they are functional: `monitor`, `task_board`, `decision_podium`,
  `console_terminal`, `status_lamp`, `merge_station`, `memory_archive`, `memory_catalog`. The
  decorative AgentCraft blocks (`plaster_panel`, `plaster_frame`, `walnut_panel`, `walnut_trim`,
  `terracotta_tile`, `oak_parquet`, `glow_panel`, `glow_strip`) stay registered in the mod for old
  worlds but are not used by blueprints (the checker refuses them). `materials: "agentcraft"` means
  the AgentCraft look built from vanilla blocks (the kit's palette: plaster = `smooth_quartz`, plaster
  frame = `calcite`, walnut = `stripped_dark_oak_log`, walnut trim = `dark_oak_planks`, tile =
  `terracotta`, parquet = `oak_planks`, glow panel = `ochre_froglight`; lanterns, candles and
  froglight bands for the rest); `"vanilla"` means any vanilla look. Multi-block monitors / task
  boards: same block, same `facing`, adjacent; store `up/down/left/right` = false (connections are
  recomputed after placement).
- Shell: every functional block in or on an outer wall has a solid vanilla block behind it on the
  outside (the kit's `wallLamp()` adds a dark-oak plate behind a wall lamp), so with every
  `agentcraft:*` cell turned to air the outer shell has no openings.
- Light: every walk cell an entity can stand in gets block light >= 1 from vanilla emitters alone
  (froglights, sea lanterns, lanterns, lit candles, ...; not AgentCraft blocks, not monitors, not the
  invisible `minecraft:light`), so nothing spawns inside at night. Roofs, porches and the attic are
  exterior and not checked.
- Per-wing station spots (group blueprints): `testbench@<n>` / `testbench_2@<n>` next to the shared
  `testbench`, `testbench_2..` slots (the bundled campuses write both). Placement renames them
  `testbench:<repoId>` like any `@<n>` anchor; routing still uses the shared slots (an agent picking
  its wing's bench needs the client change noted under "Client (routing)").

### Trophy slots

Trophies (a plaque per finished goal or merged PR) are vanilla **wall signs** the mod hangs inside the building.
A blueprint offers a *trophy wall* by writing slot anchors, per wing (`trophy@<w>`, `trophy_2@<w>` ..
`trophy_<k>@<w>`; `k` = fill order, the mod fills the lowest free `k` first and replaces the oldest trophy when all
are taken; a single building uses `@1`):
- the anchor is the **centre of the sign cell** (`x+.5, y+.5, z+.5`), `pitch` 0, `yaw` = the direction the sign's front
  faces, a multiple of 90, **into the room** (kit convention: 0 south, 90 west, 180 north, -90 east);
- the sign cell is explicit `minecraft:air` in the template, inside `walk` (readable from where agents walk) and not
  a cell an agent stands in (no standing anchor's feet or head cell); one slot per cell;
- the support is the cell **behind** the sign (opposite the yaw): a full, opaque vanilla block. The mod hangs a
  waxed wall sign with no neighbour updates only on an air cell with such a support.
- placement renames them like any `@<n>` anchor (`trophy`, `trophy_2`.., `trophy:<repoId>`, `trophy_2:<repoId>`);
- the slots are inside the building box, so `before.nbt` covers them: Remove and Move restore the site exactly.

The kit writes one with `trophyWall(x0, z0, x1, z1, facing, { slots = 6, rows, y, wing })`: the segment is the *wall*
cells (3 by default), the sign cells are the 3 x 2 room cells in front of it (rows feet+1 and feet+2), a plaster
backing behind each, a walnut frame, a `glowPanel` row above for light; order = reading order from the room (top row
first, left to right). All bundled designs have 6 slots per wing: studio (meeting room, west wall), workshop (east wall,
north end), campuses (per wing, on the wing's far wall: the outer wall beside the arch rows z13..15, or south of the
arch gap z18..20 on a partition; both sides of a partition share the wall).

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
  last move: the hub's Undo move), `pin` (see "Blueprint versions"), and in the file `pending: [{building,
  snapshot, at, why}]` (see "Crash safety").

### Occupancy (who is in the way)

`Buildings.place` (and `move`) refuse, naming them, when the box (foundation included) holds: a player whose
box grown by one block touches it; tamed, owned or leashed animals; villagers, armor stands, item frames,
minecarts and any other entity that may matter (named mobs, mobs that picked up loot); dropped items, a thrown
trident and an arrow that can be picked up ("pick them up first": an enchanted trident is never discarded).
Hostile mobs that would despawn anyway (no name, not persistent), arrows nobody can pick up (a skeleton's, a
creative or Infinity shot) and XP are removed with a note ("removes 2 × zombie in the box"): the player cannot shoo a creeper out of a box at
night, and that is the safer UX; anything the player might care about refuses instead. The ghost uses the
same rules (`building.Occupancy`), so the HUD says what the server will say; the one gap is an arrow's
`pickup`, which the client is not told: the ghost counts an arrow as the player's when it knows a player shot
it, so the server may still refuse one the ghost did not flag. A door cut in half by the box's
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
- **Cleared**: natural terrain (dirt, grass, podzol, moss, mud, stone, sand, gravel, snow, ores, plants...) and
  trees (logs, leaves) at or above the ground row inside the box that the template does not write are cleared to
  air, so a slope no longer buries walls (the box corners beside a porch included) and a canopy does not fill
  the porch. (Until 2026-10-04 grass blocks were missed: 26.x moved them from `#dirt` to `#grass_blocks`, so on
  hills the porch kept turf at head height and agents could not leave the door; `natural()` now uses
  `#substrate_overworld`.)
- The snapshot box extends down to the lowest foundation cell and out over the entrance approach
  (`snapshotBox`), so Remove restores all of it.
- The wizard puts the ground row on the **median surface** of the footprint's columns (motion-blocking,
  fluids count, leaves do not; columns more than 12 blocks from the looked-at spot are ignored), not on the
  looked-at spot; PgUp/PgDn still raise and lower it.
- The ghost draws the foundation grey and the cleared cells pale; the HUD counts both. Pure logic:
  `building.TerrainFit` (`TerrainFitTest`).

### Entrance approach

Levelling the footprint is not enough on a hill: the door opened onto a bank or a drop, and agents walking
between buildings found no way out (QA: `no_path` after 499 nodes). So placement also builds an **approach**
(`building.Approach`, pure, `ApproachTest`), shared by the server and the ghost:
- **Where**: a strip `width` cells wide (3), centred on the `entrance` anchor's column, from the box's front face
  outwards (the bundled templates' stone path reaches that face), `length` rows (6), plus up to 8 more rows
  while the path has not met the ground.
- **Profile**: row 0 is the template's own row at the door's feet height. Each row aims for the median ground
  height of its columns (one above the highest solid block that is not a log or leaves, searched from 12 above
  the previous row down to 13 below; a water surface counts as ground; nothing found = a deep drop, keep going
  down) and moves towards it by **at most one block per row**.
- **Per row and column**: the path `block` one below the feet; a bottom `slab` in the feet cell of a row that is
  lower than the row before it (going down from the door) or at the foot of a climb from a level stretch, so steps
  read as half steps and no walked step is ever more than a block (`Approach.floor`); `foundationBlock` filled below
  the path down to solid ground (at most 12, like the foundation); **headroom** of 3 cells above the path cleared
  to air (terrain, plants, logs, leaves, a player's blocks; water stays and is a warning); natural terrain above
  that (a bank) cleared up to 8 above the path so the cut is open to the sky, not a tunnel.
- **Checks, same rules as the box**: the snapshot box grows to cover the approach, so block entities on the strip
  refuse (force overwrites them; they come back on remove), other buildings' boxes overlap, players and pets in
  the way refuse, doors cut by its edge refuse; lava on or beside the strip refuses; water is a note.
- **Too steep**: a slope steeper than one block per block may still leave the path short of the ground after 14
  rows; the ghost and the place note say "the entrance path ends N blocks below/above the ground (too steep here:
  turn or move the building)".
- **Ghost**: the path and slabs in tan, its fill and cut with the foundation (grey) and cleared terrain (pale);
  the HUD line "Entrance path 6 blocks (tan), up 5 · 25 cut"; `dev.build.state.conflicts.approach`.
- **Where the player stands**: `/agentcraft place` puts the approach's end 2 blocks ahead of the player (the
  building beyond it) and the wizard puts it on the looked-at block, while the entrance faces the player (turned
  away with R, the near edge is there as before).
- **Side effects of one snapshot box**: the box is the union of the template's box, the foundation and the
  approach, so it spans the building's whole width across the approach's rows. A neighbour placed in front
  overlaps sooner; Remove puts back that whole strip as it was at placement (a player's later changes there
  are reverted, like inside the box), and removal blockers (containers, beds, dropped items) and the "player
  standing in the site" refusal cover it too.

### Safe remove

Remove refuses, listing them ("Move these out of b3 first: chest at 1,64,2 (12 items), white bed at ...,
3 dropped item stacks. Or confirm again with force: they are lost"), when the box (foundation included)
holds what the building did not bring: block entities at positions where the template has none (a chest,
furnace, bed or barrel the player placed; the template's own positions come from the building's pin, see
"Blueprint versions"), template containers or lecterns the player filled, dropped items (a trident or a
pickable arrow named with its position), pets, villagers, item frames, paintings and armor stands. Remove, Move and Undo move also refuse while a player stands in or
next to the site getting its old terrain back (every path, `/agentcraft remove` included: it would bury
them). Forcing is a further explicit confirm: the hub's
button turns into "Remove anyway", `/agentcraft remove <id> force`. Nothing is deleted silently. Drops are
only cleared when the placement/removal itself made them: the items and XP around the box are recorded
before, and only new ones are removed (right after and again three ticks later), never the player's own drops
lying there. Trophy signs the mod hung at the building's own trophy slots never count (see "Trophies").

### Crash safety

Removing a building (or moving it away) restores its site at once, but the restored chunks only reach the disk
with some later save, and a save does not promise it: an autosave or a pause save (singleplayer saves every time
the game pauses: the Esc menu, any AgentCraft screen) skips chunks saved in the last few seconds and does not
wait for the writes. So the snapshot is kept, and the site recorded under `pending` in
`agentcraft-buildings.json`, until the **next world start**, which settles each pending site on evidence
(`Reconcile.decide`, unit-tested), never on a count of saves:
- **released** (snapshot deleted): the site shows its snapshot again (at least 90 % of the cells where the
  snapshot and the building differ hold the snapshot's block; the building's own template when its pin
  matches, else its pinned block-entity positions), or a standing building covers the whole site (that
  building's own snapshot holds the same terrain; the same building included, after Undo move or a move back);
- **record back**: a removal that did not reach the disk (the building stands again) gets its record back; a
  move that did not reach the disk (the old site stands, the new one does not) gets the old record and its
  snapshot back (the unused one is kept as `<id>.unused-<ms>.nbt`);
- **reported, snapshot kept**: a move saved at both sites (two copies of the building), or a taken-down
  building standing partly under another building: it is never re-added over another one;
- **kept silently** for the next start: anything that cannot be told (blueprint changed or missing, dimension
  not loaded, a site neither standing nor restored).
Placing on a just-removed site in the same session is allowed (the rules above sort it out at the next start).

At world start every building is also checked against the world (by block, not state: lamps, podiums,
monitors and doors change states; at least 80 % of the template's blocks must be in place). A building whose
own template does not stand is reported in the hub (Buildings tab "Check", `/agentcraft buildings`): Remove
restores the terrain saved before it was placed, Forget only drops the record; nothing is deleted
automatically. A building whose blueprint changed since it was placed, or whose blueprint or dimension is not
loaded, cannot be checked: it gets a note, never a "does not match" (see "Blueprint versions").
`dev.buildings.pending` shows the pending sites, the snapshot files, the pins and the reports.

### Blueprint versions

A blueprint can be regenerated under the same id (a new bundled version, a redesign). A building record
therefore pins what it was placed from (`pin` in the record): the template's fingerprint
(`TemplateGrid.fingerprint`: SHA-256 of its cells, order-free), the wing count and kind, every sidecar anchor in
world space with its raw `name@n`, and the template's own block-entity positions. Everything about an
existing building uses its pin, never the current blueprint:
- **Edit repos** derives the anchors from the pinned ones (`BlueprintTransform.renameWings`) and checks the
  pinned wing count, so agents keep routing to the desks that stand there;
- **Remove / Move** tell the building's own chests, barrels and lecterns from the player's by the pinned
  positions;
- **the world-start check** compares the world with the template only while the fingerprint matches; else
  it notes "the blueprint changed since it was placed" and does not check.
Records placed before pins existed get one at world start when the current blueprint stands there and gives
the same anchors; otherwise they get a note, keep their anchor positions on Edit repos (names are only
renamed, `BlueprintTransform.rebindAnchors`, and they take at most as many repos as they have), and Remove
lists every block entity but the stations (with a note saying why). Move re-places a building from the
current blueprint (and pins that), so it is the way to bring an old building up to date; it refuses when the
current blueprint has fewer wings than the building has repos.

### Change a building's repos

`Buildings.setRepos(server, id, repos)` (hub "Edit repos…", `/agentcraft repos <id> <repo>[,<repo>...]`):
wing n becomes `repos[n-1]`. Station bindings `repo:<old wing n repo>` / `ci:<old wing n repo>` and unfilled
`repo:#n` / `ci:#n` are rebound to the new wing n repo, a wing that loses its repo goes back to `#n`
(`BlueprintTransform.rebindBinding`); the per-wing anchors are derived again from the building's pin (the
blueprint as it was at placement, "Blueprint versions"), so the blueprint need not be loaded; the repo screen
caps the pick at the same count. Refuses more
repos than wings and a repo that has another building. The lead sync
(`LeadsFeature`'s listener) then sends `lead.assign` with the new repos.

### Move a building

The hub's "Move…" puts up the ghost of the building's blueprint (its repos, "Moving b3" in the HUD); Enter
runs `Buildings.move(level, id, origin, rotation, force)`: every check of `place` at the new site (it may not
overlap the building's current site), the old site's safe-remove check (Shift+Enter forces after a refusal),
then the template at the new site, then the snapshots renamed (the old one to `<id>.moved-<ms>.nbt`, the new
one to `<id>.before.nbt`), then the old site restored from the renamed snapshot (kept until the next world start,
as for a removal). A failure at any step undoes the steps before it (files renamed back, the new site restored
from its snapshot) and records nothing, so the record never points at a site whose snapshot is another site's
terrain (`dev.buildings.failNextRename` injects a rename failure). The id, repos, lead and home flag stay; the layout revision changes. `movedFrom` records the
old site; "Undo move" (`Buildings.undoMove`) moves it back there (one step). The building's trophies are hung again
at the new site (see "Trophies").

### Trophies

A repo's building gets a plaque when one of its PRs merges, a task merges locally, or one of its goals turns done
(the client decides when; server side `dev.agentcraft.building.Trophies` hangs it):
- **The plaque** is a vanilla **waxed dark-oak wall sign** (front text, black, not glowing; nobody can edit it, and
  it stays a plain sign when the mod is removed). Lines (`TrophyText`, pure):

  | kind | line 1 | lines 2-3 | line 4 |
  |---|---|---|---|
  | PR | `Merged PR #612` | title, word-wrapped | `2026-10-04` |
  | merge (done without a PR) | `Merged t12` | title, word-wrapped | `2026-10-04` |
  | goal | `Goal done` | the goal text's first line, word-wrapped; when it takes one line, line 3 is `3 tasks` | `2026-10-04` |

  Every line fits the sign's 90 px by the vanilla default font's advances (6 px for most characters, 2-5 for the
  thin ones, conservative widths for anything Unifont draws); a word longer than a line is broken; what does not fit
  ends the last line with `…`. `§` codes and control characters are dropped, whitespace collapsed. The task count
  never shares the date's line: `3 tasks · 2026-10-04` is 107 px.
- **Where**: only at the building's trophy slots (see "Trophy slots"), read from its **pin** (raw `trophy*@<w>`,
  world space: the template that stands there, not the blueprint's current version), the repo's wing only (a single
  building: wing 1), never outside the building's box. A building placed before pins, or a blueprint without slots,
  gets none; so does a repo without a building (the dev HQ studio has no slots).
- **Which slot**: the lowest free slot in fill order, else the slot with the oldest trophy (replaced: its sign is
  rewritten). A slot is only used when its cell is air (or holds our own sign, per the ledger) and the block behind
  it has a sturdy face towards the sign; a blocked slot is skipped for the next one.
- **How**: `setBlock` with `UPDATE_CLIENTS` only (no neighbour or shape updates, nothing pops), then the sign's
  block entity text. No AgentCraft block, no items, works with cheats off.
- **Ledger** `<world>/agentcraft-trophies.json` (`TrophyLedger`, written atomically): `awarded` keys and per building
  per slot `{key, lines, at}`. Keys: `goal:<goalId>:<createdAt>`, `pr:<repo>:<prId>`, `merge:<taskId>:<createdAt>`
  (`Trophy.goalKey/prKey/mergeKey`). A known key is never hung again (also after its sign was replaced or its building
  removed). A key is only recorded when a sign was hung: a repo without a building, slots or room catches up later.
  Malformed parts are skipped; a file that is not JSON at all is left alone and nothing is awarded that session.
- **Remove / Move**: the slots are inside the box, so the snapshot (`before.nbt`) covers them and Remove puts back
  exactly what was there (the restore flags suppress drops: no sign item comes out; the drop cleaner catches any).
  The removal check ignores sign block entities at the building's pinned trophy cells (a chest put there still
  counts), and the world-start check skips those cells. Move (and Undo move) hangs every ledger trophy again at the
  same slot of the new site; a slot the new site lacks or blocks drops out of the ledger. `forget` drops the
  building's slots from the ledger at once; a removed building's slots are dropped at the next world start (a
  removal the disk never saw comes back with its record, and its signs must still read as ours). Keys stay awarded.
- A player can still break a trophy sign (it drops one dark-oak sign, as any sign does); the slot then holds air
  and is reused like any other.

Client side (`client.trophy.TrophyFeature`; pure `trophy.TrophyEvents` and `TrophySettings`; no protocol change):
- **What earns one**: a goal's `status` turning `done` (`goal:<goalId>:<createdAt>`; repo = `Goal.repoId`; the text's first
  line; the count of its non-cancelled tasks; date = `updatedAt`), a task's `pr.status` turning `merged` (`pr:<repo>:<prId>`;
  repo = the task's, else its goal's), a task without a PR turning `done` (`merge:<taskId>:<createdAt>`).
- **When**: live on each goal/task update (the transition only), and a **catch-up** of everything done or merged that has no
  key yet, oldest first, on every Foreman snapshot, when a building is placed or its repo list changes, and when the toggle
  is switched on. A repo without a building, or a building without slots, awards nothing and is retried by the next
  catch-up. A catch-up awards only each repo's newest N (N = its wing's slot count), oldest first, so a long history does not rewrite the wall hundreds of times; older ones are never hung. The ledger's `at` is strictly increasing per building (`TrophySlots.nextAt`), so awards within one millisecond still replace in award order.
- **Toggle**: hub > Buildings, under "Agents walk between buildings": "Trophies for merges and finished goals: On/Off", per
  world, `<gameDir>/agentcraft/trophies.json` (default on). Off hangs nothing (signs already hung stay).
- Singleplayer only (the integrated server hangs them); DevBridge `dev.trophies.award`, `dev.trophies.list`,
  `dev.trophies.toggle` (mod/DEV.md).

API (server thread): `Trophies.award(level | server, Trophy, key) -> Result{outcome PLACED | KNOWN | NO_BUILDING |
NO_SLOTS | NO_ROOM | UNAVAILABLE, building, slot, replaced, message}`, `Trophies.known(key)`, `Trophies.slotsFor(repo)`,
`Trophies.list(server)` (JSON for the DevBridge).

## Roads (docs/VILLAGE.md V1)

Roads join two buildings along the route agents walk. Pure logic: `building.RoadPlan` (`RoadPlanTest`), records
`building.Road` (`RoadJsonTest`); server `building.Roads`, `building.RoadTerrain` (block -> kind, shared by the ghost and
the server); client `client.road.RoadsFeature` (+ `RoadGhost`, `RoadHud`), the hub view `client.hub.RoadsView`.

- **Route**: the outdoor planner (`OutdoorPlanner`, docs/WAVE2.md W8) from entrance to entrance with
  `Limits.ROAD`: like the agents' routes, but **no step drops more than one block** (agents may drop 3), so the road
  is walkable both ways and climbs or falls at most one block per cell. The route's cells inside any building's
  restore box (template, foundation, entrance approach) are trimmed: the road runs between the approach ends.
- **Walkway**: `width` cells across (1-3, default 2; 2 = the route and the cell to its right). Each route cell stamps
  the cells across its step; a diagonal step goes through its corner cell (standable: the planner never cuts corners),
  so the walkway stays side-by-side connected around corners and on diagonals. A centre cell keeps the route's height;
  a side cell takes its own ground within one block of it and is left out when it is more than a block off a
  neighbouring road cell.
- **Half steps**: a road cell with a neighbour one block higher and none lower gets a bottom slab in its feet cell (its
  ground is left as it is: a dirt path under a block turns to dirt), so a climb from a level stretch starts with a half
  step; no step on the road is ever more than one block.
- **Blocks** (`RoadPlan.surfaceFor`; decision): `minecraft:dirt_path` on grass, dirt, coarse/rooted dirt, podzol,
  mycelium and moss; `minecraft:gravel` on sand, red sand, gravel and natural stone (base stone, sandstone, terracotta,
  clay, snow block) where the block below holds it up, else `minecraft:packed_mud` (it never falls); `packed_mud`
  on mud. Half steps: `mud_brick_slab` on dirt and mud roads, `cobblestone_slab` on gravel. Only those natural kinds are
  ever paved (`RoadTerrain`): a player's floor on the route is crossed as it is, a field (farmland) is the player's.
  **Ores** count as built (never gravelled: if the player mined the gravel the ore would be gone for good): a centre
  cell on an exposed ore keeps it, a side cell on one is left out (`RoadPlanTest.exposedOresAreKept`).
- **Clearing**: plants, flowers, saplings, grass, snow layers, lily pads and leaves in the walkway's two cells of
  headroom (three over a half step). A tall plant (double plants, sugar cane, bamboo) goes with its whole stack, so
  nothing floats and pops. Never: block entities, logs, cacti, a player's blocks (anything not natural, torches and
  rails included), fluids, anything waterlogged. A side cell where one is in the way is left out; a centre cell keeps
  everything as it is (`Role.KEPT`).
- **Water**: a cell wading 1 deep gets a bridge deck (`minecraft:oak_slab`, bottom) in the **air cell above the
  water** only with **Bridges: On** (per road, default off); the water itself is never touched (a slab in the water
  cell would waterlog, and a waterlogged cell over flowing water is a new source). Off, those cells are skipped with a
  note ("6 cells of shallow water skipped (bridge off)"). Deeper water is never on a route.
- **Lanterns** (default on): an `oak_fence` post with a `lantern` on top just beside the walkway, the first 6 blocks
  along the road and then every 12, on natural ground within a block of the road's height; when there is no room the
  other side, then the next route cells (up to 3 further) are tried, else it is noted.
- **Shared cells**: a cell another road already changed is left to that road (the column is skipped, noted "already
  part of another road"); removing the first road removes it.
- **Laying** (`Roads.lay(level, a, b, route, options, previewHash)`, server thread): the client sends the route and,
  from a preview, the fingerprint of the ghost the player confirmed (`RoadPlan.hash`: each change's x, y, z and block,
  in order). The server
  checks again: both buildings in the player's dimension, no road between them yet, the route a chain of neighbouring
  cells (each step at most one block up or down, at most 1024 cells), starting and ending within 4.5 blocks of the two
  entrances, every cell outside the buildings still standable on the server's level (`walk.LevelWalk`, the agents'
  rules), every chunk loaded (nothing is loaded or generated). Then it plans the road itself on its own level
  (`RoadPlan.plan` with `RoadTerrain`), refuses when its plan is not the confirmed ghost ("The ground changed since the
  preview: preview the road again"; `dev.roads.lay` with `a`/`b` lays without a preview and skips this), drops changes
  that change nothing, and refuses when a player (box grown by 0.3 sideways), a pet, a villager, an armor stand or a
  named mob is in a cell that could trap them: one that gains a collision shape or whose top rises by more than 1/8
  (`RoadPlan.canTrap`; a ground swap such as grass to a dirt path or stone to gravel never counts) ("Step off the road
  first: you at 12, 65, -3"). Order: the snapshot (written atomically, read back), the record, then the blocks; when the
  record cannot be written the snapshot is deleted and nothing is laid (no block stands without a record).
- **Blocks are set** with `UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS`: no neighbour or shape updates (nothing next
  to the road pops or reconnects), no drops, no `onPlace` (gravel never ticks), no block-entity side effects. New item
  and XP entities within a block of a changed cell are cleared anyway, right after and 3 ticks later (`Roads.CellDrops`;
  only new ones near the cells: a road's box can span the village, and the player's own drops there are never touched). Lanterns light the road
  (light updates still run). Like a building's blocks, the road's blocks come from the mod (no items are taken or given).
- **Records**: `<world>/agentcraft-roads.json` `{version: 1, next, roads: [{id: "r<n>", a, b, dimension, width,
  lanterns, bridge, created, length (route cells), cells: [x, feetY, z, ...], lanternCells, changes: [x, y, z, ...],
  notes}], pending: [{road, snapshot, at}]}`; ids are never reused. A malformed entry is skipped; a file that is not JSON
  at all is left alone and nothing is laid that session.
- **Snapshot**: `<world>/agentcraft-roads/<id>.before.nbt`, one entry per changed cell `{x, y, z, before, after}` (block
  states), never a box: a box restore would revert everything else in a long diagonal road's bounding box.
- **Remove road** (`Roads.remove(level, id)`): every cell that **still holds what the road put there** (the same state;
  for fences and lanterns the same block, as a neighbour update reshapes a fence's connections or water fills it; for
  slabs the same block and slab type, waterlogged or not) gets its old block back, ground first, then what stood on it; cells the player changed since and cells a building now covers are left as
  they are ("Removed road r2 (b1 to b3): 140 cells back as they were; 3 cells you changed since left alone"). Refuses while
  a player or a pet stands where an old block comes back (a bush at head height suffocates). Crash safety as for
  buildings: the removal is recorded under `pending` first (refused, nothing done, when the record cannot be written),
  then the snapshot is renamed `<id>.removed-<ms>.nbt` (the record is put back when that fails), then the blocks
  are restored; the next world start settles
  it on the cells (`Road.settle`): most telling cells hold the old blocks -> the snapshot goes; most hold the road (the
  removal never reached the disk) -> the record comes back; nothing readable -> kept. `forget` drops a record and leaves
  the blocks (for a road whose snapshot is gone).
- **Buildings removed or moved** (any path: hub, command, Undo move): their roads now lead nowhere or to the old site.
  The client notices (a `Buildings` listener comparing restore boxes), shows a toast ("b3 was removed: its road r2 leads
  nowhere now. Remove it in the hub: Buildings > Roads") and lists those roads first in Roads with **Remove road…** and
  **Keep it**. Nothing is removed silently. The building's armed Remove note says its roads stay.
- **Agents prefer roads**: the planner's steps onto a road's feet cells (`Roads.feetCells(dimension)`) cost
  `OutdoorPlanner.ROAD_FACTOR` (0.6) of a step elsewhere, and when a road cell lies inside the search box its heuristic
  is scaled by the same factor (otherwise the search would rush past it). A road elsewhere in the dimension leaves the
  heuristic as it is, so it costs other searches nothing. Measured (`routesPreferLaidRoads`,
  `unrelatedRoadsDoNotSlowTheCorridor`): a 100-block trip with a road 4 blocks off the straight line keeps to the road
  for 90+ cells (241 expansions); a road 40 blocks off is not worth the detour; the 256-block corridor and the mountain
  route take the same expansions with an unrelated road as without (21 919 and 3 390; budget 120 000).
  Route caches are dropped whenever a road is laid or removed (`Roads.signature`).
- **Ghost** (`RoadGhost`, the placement ghost's colours): new surface, half steps and decks tan; cleared cells orange;
  road cells left out red at their feet (the whole route red when the plan is refused); fence posts and lanterns brass.
  The HUD panel (`RoadHud`) says the pair, the options, the verdict, the counts and the first note; Enter lays, Esc or
  Backspace cancel (through the wizard's keyboard hook, only with no screen open; the player can walk around).
- QA: mod/DEV.md "Roads".

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
- Station targets, desks, seats, monitors and the pathfinder use that building's layout. Moving between
  buildings walks (fix wave 2, docs/WAVE2.md W8, mod/DEV.md "Walking between buildings"): inside to the
  building's `entrance`, outdoors to the other building's `entrance` along a route planned over the
  client's loaded terrain, inside to the spot. It teleports with a puff at both ends instead when walking
  is off for the world (hub > Buildings), a building is in another dimension or has no `entrance`, the
  entrances are more than 256 blocks apart, chunks on the way are not loaded, the player is beyond render
  distance, or the route is missing, blocked or takes far too long. A released lead going home still
  teleports to the home lounge first.
- Dimensions: every building is driven in its own dimension (`ServerTasks.run(dimension, ...)`; the HQ studio
  is in the overworld): lamps, podiums, merge stations, monitors and signal bulbs. Agents only route and
  spawn to buildings in the player's dimension; an agent whose building and home are both elsewhere is not
  shown (rather than appearing at home's coordinates in the wrong level). Lookups by position
  (`Routing.siteAt/regionAt` with a dimension) never confuse two buildings at the same coordinates in two
  dimensions.
- Per-wing stations: not yet. A placed group building has `testbench:<repoId>` anchors (from
  `testbench@<n>`), but agents look up the shared `testbench`/`testbench_N` slots, so a repo's tester
  may use another wing's bench. To route by wing, the client's station lookup should try
  `<station>:<agent's repoId>` (and its `_N` slots) first, then the shared name.
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
the "Building wizard" key opens it (Options > Controls > AgentCraft; **unbound by default**, `B`
clashed with Xaero's new-waypoint key: bind one if you want it). Singleplayer only. The key is not
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

## Checker (tools)

`tools/blueprints/lib/check.mjs` (run by `build.mjs` and by the Foreman on every design) enforces this
contract offline: template format, palette states, sidecar fields, required anchors and their cells,
explicit interior air, bindings, and C5:
- decorative AgentCraft blocks are refused (with the vanilla block to use);
- shell: the outside (padded template box above the ground row; unwritten cells there count as open)
  is flood-filled through everything a mob could pass (air, carpet, buttons, lanterns and other small
  blocks; not cubes, glass, panes, closed doors, slabs, stairs), once with the AgentCraft blocks and
  once with them as air; any cell (walk, attic, cavity) reached only without them is an error naming
  the AgentCraft block in the shell. A pass that cannot rise above `walk.maxY` finds openings in the
  walls: walk cells it reaches are an error (a doorway without a closed door, a gap); walk cells
  reached only from above are a warning (open to the sky: a courtyard);
- light: vanilla block light from vanilla emitters only, -1 per step, opaque cubes stop it, glass and
  panes pass it, slabs and stairs block it through their full faces (2x2x2 voxel faces, vanilla's
  shape occlusion); run with AgentCraft blocks as opaque non-emitters and again as air; every walk
  cell with no collision (air, carpet, buttons) needs level >= 1;
  dark spots in enclosed space outside walk (an attic) where a mob could spawn are a warning;
- doors: written closed; a door next to an outside cell is iron; every iron door has a stone button
  on each side on a full, opaque, redstone-conductive block (not glowstone or a sea lantern) touching one of its
  halves;
- trophy slots (`trophy*`, see "Trophy slots"): wing in range, yaw a multiple of 90, the cell inside `walk` and explicit
  air, a full opaque block behind it, one slot per cell, not an agent's feet/head cell; a wing without any slot is a
  warning. They are block anchors for `verify.mjs` (no floor/headroom check);
- `@<n>` anchors in range 1..wings; `foundationBlock` a full, opaque `minecraft:` block; `approach` an object or
  `false`, `length` 0..16, `width` 1..7, `block` a `minecraft:` full block, `slab` a `minecraft:` slab (when the kit
  knows them).

## Verify loop (tools)

`tools/blueprints/verify.mjs <id>`: with the dev client running (`node tools/mac.mjs launch
--backend sim --dev`), places the template in the dev HQ world away from the studio, takes an
exterior orbit (4 views) + interior views (each `cam_*` anchor), writes PNGs and a contact sheet
under `artifacts/shots/blueprints/<id>/` (see `tools/blueprints/VERIFY.md`), and checks the sidecar (required anchors, anchors inside
`walk`, standing anchors on a floor with head room).
