# Buildings: blueprints, placement, one building per repo (Phase 2 contract)

The contract the Phase 2 workstreams build against. Change it here first, then in code.

## Blueprints

A blueprint is two files with the same id (`[a-z0-9_]+`):

| file | what |
|---|---|
| `<id>.nbt` | a vanilla structure template (gzipped NBT, `DataVersion`, `size`, `palette` with entries `{id, properties}` (26.3 renamed `Name`/`Properties`; the old keys place nothing, silently), `blocks`, `entities: []`), exactly what a structure block saves; no size limit beyond vanilla's template format |
| `<id>.blueprint.json` | the sidecar below |

Where they live:
- bundled: `mod/src/main/resources/data/agentcraft_worlds/structure/<id>.nbt` (so vanilla `/place template agentcraft_worlds:<id>` works too) and `mod/src/main/resources/data/agentcraft_worlds/blueprints/<id>.blueprint.json`
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
- `kind`: `single` (one repo), `group` (up to `wings` repos; wing `n` is the n-th repo chosen) or `fixture` (`wings: 0`, no
  repos: a placeable object such as the village board, see "Fixtures" below; the required anchors are `board` and `spawn`).
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
  - optional: `bed`, `bed_2` .. (see "Beds": the night routine; without beds agents rest in the lounge)
  Per-wing anchors use the suffix `@<n>`; placement rewrites them (see below).
- Station blocks in the template carry their binding in block-entity NBT (`{"binding": "..."}`).
  Wing placeholders: `repo:#<n>` (a task wall showing wing n's repo) and `ci:#<n>` (a CI lamp)
  are rewritten to `repo:<repoId>` / `ci:<repoId>` at placement. Monitors bound to an agent id
  (`kit`) as today.
- Materials (contract C5): structure, floors, walls, roofs, trim and light are **vanilla blocks**, so a
  world opened without the mod keeps its buildings and only the station blocks go missing.
  AgentCraft blocks only where they are functional: `monitor`, `task_board`, `decision_podium`,
  `console_terminal`, `status_lamp`, `merge_station`, `memory_archive`, `memory_catalog`, `village_board`. The
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
  `agentcraft_worlds:*` cell turned to air the outer shell has no openings.
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
- the slots are inside the building box, so the site's journal entry covers them: Remove and Move restore the site exactly.

The kit writes one with `trophyWall(x0, z0, x1, z1, facing, { slots = 6, rows, y, wing })`: the segment is the *wall*
cells (3 by default), the sign cells are the 3 x 2 room cells in front of it (rows feet+1 and feet+2), a plaster
backing behind each, a walnut frame, a `glowPanel` row above for light; order = reading order from the room (top row
first, left to right). All bundled designs have 6 slots per wing: studio (meeting room, west wall), workshop (east wall,
north end), campuses (per wing, on the wing's far wall: the outer wall beside the arch rows z13..15, or south of the
arch gap z18..20 on a partition; both sides of a partition share the wall).

### Beds

The night routine (docs/VILLAGE.md V3) lets idle agents sleep in the building's **vanilla beds**. A blueprint offers
them with plain anchors `bed`, `bed_2` .. (no `@<n>`: a campus names its beds across all wings, and agents take the free
bed nearest their wing's task wall or their desk; `@<n>` anchors of a wing without a repo would be dropped):
- the anchor is the **head half's cell** at the feet row (`x+.5, feetY, z+.5`), `yaw` = the bed's `facing` (from the
  foot to the head, the pillow end; kit convention 0 south, 90 west, 180 north, -90 east);
- the cell holds `minecraft:<colour>_bed` `part=head`, `occupied=false`; the foot half is the cell behind it (opposite
  `facing`) with the same block and facing; a full floor under both; air above both; a free standable cell beside the
  bed (where an agent steps in and gets up); one anchor per bed; inside `walk`;
- the kit writes one with `bed(x, z, facing, { color })` (both halves + the next anchor); bundled: workshop three red
  beds in the south-west corner, studio three light-grey beds between the meeting room and the runner, each campus
  wing two beds in its colour with a barrel-and-lantern nightstand, all against the front wall;
- beds are block entities: the template's own beds are in the building's pin (`blockEntities`), so Remove and Move
  treat them as the building's (a bed the player adds is still "move this first"); the snapshot restores them like
  any block (no drops: the restore flags suppress them, both halves are in the box);
- agents never change the world: lying is a render pose of the client-only agent; a bed the player sleeps in
  (`occupied=true`) is skipped. In the Overworld the player can sleep in them too (a normal bed: spawn point, night
  skip);
- **beds only where they are safe**: Place and Move read the level's bed rule (`BedRule`, vanilla's per-position
  environment attribute) at each template bed's head. Where nobody can sleep or a bed is destroyed on use or on
  leaving (the Nether and the End: right-clicking a bed there is a power-5 explosion with fire, the end of a Hardcore
  world), both halves are written as air (no drops), the bed's anchor is dropped from the building (agents rest in the
  lounge there), the pin forgets the cells (a bed the player brings later is theirs: Remove lists it) and its
  `wingAnchors` (a repo change does not bring the anchor back), and the placement note says "N beds left out (beds
  explode in <dimension>; ...)". Logic in `BedSafety` (`BedSafetyTest`); a building moved back to the Overworld gets
  its beds again (Move re-places the template);
- Regenerated bundled blueprints change the template fingerprint: buildings placed before have a pin from the old
  version (the world-start check notes "the blueprint changed since it was placed" and does not check); Move
  re-places them from the current blueprint (with beds).

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
- Before placing, the blocks and block entities in the target box are saved as an entry of the world journal (see
  "World journal"; until wave 3 `<world>/agentcraft-buildings/<id>.before.nbt`); removing the building puts them back
  exactly.
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

### Fixtures

A fixture (docs/VILLAGE.md V2: the village board) is placed from a `kind: "fixture"` blueprint and recorded like a building but
with **no repos** (`Building.isFixture()`): same file, same ids, same snapshot, ghost, terrain fit, Remove, Move, Undo move,
crash safety and world-start check. It is **not a building** for anything else: never home, no lead, no routing site, no
trophies, no "one building per repo". `Buildings.all()` lists every site (buildings and fixtures): it is the one for overlap and
collision (placement, the ghost and roads refuse a box overlapping a fixture as they do a building). `Buildings.buildings()`
lists buildings only (routing, leads, trophies, the hub's building list), `Buildings.fixtures()` the fixtures. `place` refuses repos for a fixture; Make home and Edit repos refuse a fixture. The record says
`"kind": "fixture"` for readers (it reads back from the empty `repos`). The hub lists fixtures under Buildings > Fixtures.

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
  (`snapshotBox`), so Remove restores all of it, and one row further down (since 2026-10-05): the ground under the
  floor and the foundation changes while the site stands (grass under a solid block turns to dirt) and Remove puts
  that back too. At the level's floor the extra row is left out. Buildings placed before keep their recorded box.
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
lying there. Trophy signs the mod hung at the building's own trophy slots never count (see "Trophies"), and neither do
**natural drops** (wave 3, `building.NaturalDrops`, `NaturalDropsTest`): saplings, sticks, apples, seeds, leaf litter,
petals, berries and flowers that decaying leaves and cleared plants dropped (the placement cleared a tree's logs; the leaves
left beside the box decay over the next minutes and drop into it), unless a player threw the item or it has a custom name.
They are nobody's items: they never block Remove or a Move's old site and are not listed or counted. Placement occupancy
is unchanged (dropped items in a box about to be built still refuse: "pick them up first").

### Held leaves

Placing a site clears the logs inside its box; leaves outside it that hung on those logs then decayed, and Remove
(which restores the box) could not bring them back. Since 2026-10-05 (`building.LeafGuard`, ported from Architect,
`LeafGuardTest`) placement first makes those leaves **persistent** and records them as a journal entry of their own:
kind `leaves`, policy CELL, owned by the building, each cell's `before` the natural leaf with its `distance` and its
`after` the same leaf persistent.
- **Which**: non-persistent leaves within 6 of the snapshot box with `distance` below 7 whose Manhattan distance to the
  box is at most their `distance` (only those can hang on a log inside it); not cells inside a standing site's box (they
  are that site's), not in unloaded chunks. Read before the box changes.
- **Quiet**: holding and releasing a leaf never notifies neighbours (`UPDATE_KNOWN_SHAPE`; `WorldJournal.apply` writes
  `leaves` cells that way): world generation leaves many leaf distances stale, and a recompute would change cells nobody
  recorded.
- **Remove / Move** undo the hold with the site's entry and its trophies (one group, so crash safety settles it with
  the site): the box first (the logs come back), then each held leaf that is still a persistent leaf of the same block
  gets its natural state and recorded `distance` back. Vanilla recomputes a persistent leaf's distance too, so the undo
  compares only block and `persistent` (`LeafGuard.stillHeld`); a leaf the player broke or replaced is left.
- **Overlaps**: a later site whose box takes in a held leaf records the persistent leaf as its `before`; the journal's
  hand-down gives it the natural leaf when the holder goes first, and when the later site goes first the holder still
  holds it. After a Remove or Move, the sites standing near the restored box **hold again** (a new `leaves` entry each)
  what now hangs on them: restored leaves whose logs a standing site cleared, leaves the removed site held.
- **Forget** releases the hold with the site's entry: the building stays, so its held leaves stay persistent.

### Cut plants

A two-block plant (tall grass, large fern, sunflower, lilac, rose bush, peony, pitcher plant...) can stand across the
snapshot box's top or bottom face: one half inside, the other just outside. Placing the site replaced the inside half and
the outside one dropped with it (or the placement took it, so it would not float), and Remove's box restore put the inside
half back next to air, where it dropped again: the plant was gone for good. Since 2026-10-06 (`fix/journal-exactness`,
ported from Architect c9af228, `CutPlantsTest`) Remove brings it back:
- **Recorded**: placement reads the outside halves of the plants the box cuts before anything changes; each one the
  placement changed becomes a **guard cell** of the site's held-leaves entry (kind `leaves`, CELL): `before` the plant's
  half, `after` what the placement left (air). So it is undone with the site's group, released by Forget, settled by crash
  safety and layered like the held leaves (a later site over it hands it down). The log names them ("kept as guard cells").
- **Written first**: `WorldJournal.apply` writes guard cells before the box, quietly (`UPDATE_KNOWN_SHAPE`), lowest first,
  then the box, then the other cells (`WorldJournal.phases`). The box then writes the inside half next to its other half
  and the plant stays whole. A half the player changed since is left (the CELL rule). A placement rolled back before its
  commit (a failure, a Move whose journal commit failed) puts them back before the box too.
- Doors cut by the box are still refused at placement (a player's door), not guarded.
- Rare in practice: the box reaches one row below the lowest written cell, so only a plant on terrain at exactly the box's
  top row, or in a dip of a column the foundation does not fill, is cut (80 trial placements over meadow, sunflower plains
  and flower forest found none; the QA planted them on purpose).

### Leaf ring

World generation leaves many leaf `distance`s larger than their nearest log gives (trees generated over each other: dark
oak, jungle edges, overlapping canopies). Any shape update next to such a leaf lets the canopy relax to the true distances,
so placing a site (and the decay and holds around it) and then removing it changed leaf distances up to about 7 blocks
outside the box, held leaves or not, and the box restore's own shape updates scheduled leaf ticks that changed more
afterwards. Since 2026-10-05 (`building.LeafGuard.ring`, ported from Architect, `LeafGuardTest`) Remove is exact there too:
- **Recorded**: placement reads, before the box changes, every leaf within 8 of the snapshot box but outside it (persistent
  or not) and records it as a journal entry of its own: kind `leafring`, policy CELL, owned by the building, each cell's
  `before` and `after` the leaf as it was (nothing is written). Left out: cells of any other active journal entry (a
  standing site's box, held leaves, another site's ring, a road: they are theirs, and a ring cell over them would turn
  their undo's write into a hand-down that writes nothing), the leaves this placement holds (their hold gives their natural
  state back), cells in unloaded chunks. It commits with the site's entry, so it has the journal's save/reload and crash
  safety (a crash before the commit leaves nothing: the ring changed no block).
- **Remove / Move** undo the ring with the site's entry, trophies and holds (one group): each ring cell that still holds
  the same leaf (same block, same `persistent` and `waterlogged`; `LeafGuard.sameLeaf`, the `distance` may differ) gets
  its recorded state back, quietly (`UPDATE_KNOWN_SHAPE`, as held leaves). A leaf the player broke, replaced, placed by hand
  or waterlogged is left (the CELL rule).
- **Restores drop their leaf ticks** (`journal.LeafTicks`, `mixin.LevelTicksMixin`): `WorldJournal.apply` (every undo:
  Remove, Move, roads, trophies) and `restoreTemplate` (a rolled-back placement) cancel the leaf ticks scheduled while
  they write, so the restored leaves keep the distances the undo wrote instead of relaxing again. A leaf's scheduled tick
  only recomputes its `distance` (decay is a random tick); every other block and fluid tick is scheduled as usual.
  Placement keeps its leaf ticks (vanilla behaviour while the site stands; the ring puts it back on Remove).
- **Any order**: a later site whose box takes in a ring leaf records it (maybe relaxed) as its `before`; when the ring's
  site goes first the hand-down gives the later box the recorded leaf, when the later site goes first its box writes what
  it found and the ring then writes the recorded leaf. A later hold over a ring leaf (a re-hold, its own or a neighbour's)
  works the same way. Every order is in `LeafGuardTest`; a reactivated removal (crash safety) reverses the hand-downs.
- **Ring again**: after a Remove or Move, the sites standing near the restored box (within 16 of it) record a new
  `leafring` entry each over the leaves around them that now belong to no entry (the removed site's box, ring and holds
  had them), as they are right after the restore. A moved building's new site is placed before its old site is restored,
  and its placement's leaf ticks run after that, so without this the leaves between the two sites would be in no ring.
  These entries, and the re-hold's (`leaves`), name the undo they followed (meta `after`: the site's entry id). When the
  next world start finds that the undo never reached the disk and takes it back (record back, or a move taken back), it
  releases them in the same commit (`Buildings.followers`): their cells may lie in the box that stands again, and a cell
  over the box's would turn its next Remove's write into a hand-down that writes nothing (`LeafGuardTest`). A released
  re-ring changed no block; a released re-hold's leaves stay persistent.
- **Forget** releases the ring with the site's entry (the building stays; nothing is written).
- **Cost**: one pass over box + 8 (and loading the active entries near it) per placement; `dev.buildings.timing` reports
  the time in `Buildings.place`, the ring's part and cell count, and the server tick interval around the placement.
- Not covered: leaves next to a road or trophy that are undone on their own (only a building's Remove and Move ring
  again), leaves past 8 of the box (a relaxation that travels further through one canopy), and leaves whose distance
  vanilla changes while no site holds them.

QA: `dev.region.capture {name, x0..z1}` then `dev.region.diff {name}` (block states plus block entity data, at most 4M
cells) or `dev.region.hash`; capture box + 7 before placing, place, soak, remove, wait, diff: 0 cells.

### Crash safety

Removing a building (or moving it away) restores its site at once, but the restored chunks only reach the disk
with some later save, and a save does not promise it: an autosave or a pause save (singleplayer saves every time
the game pauses: the Esc menu, any AgentCraft screen) skips chunks saved in the last few seconds and does not
wait for the writes. So the site's journal entry is kept undone (with its trophies', held leaves' and leaf ring's entries, one group), and the site
recorded under `pending` in `agentcraft-buildings.json` (`snapshot` names the entry, `j<n>`; an imported pending site
keeps its old file name, which the journal's `legacy` map resolves), until the **next world start**, which settles each
pending site on evidence (`Reconcile.decide`, unit-tested), never on a count of saves:
- **released** (journal entries released): the site shows its saved terrain again (at least 90 % of the cells where the
  terrain and the building differ hold the terrain's block; the building's own template when its pin
  matches, else its pinned block-entity positions), or a standing building covers the whole site (that
  building's own entry holds the same terrain; the same building included, after Undo move or a move back);
- **record back**: a removal that did not reach the disk (the building stands again) gets its record back and its
  entries reactivated (their hand-downs reversed, see "World journal"); a move that did not reach the disk (the old site
  stands, the new one does not) gets the old record and its entries back (the new site's entries are kept undone as
  unused, naming no record);
- **reported, entry kept**: a move saved at both sites (two copies of the building), or a taken-down
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
`dev.buildings.pending` shows the pending sites (with the journal entry each resolves to), the sites' journal entries,
the pins and the reports. Before the evidence rules, the world start repairs the crash windows between the journal and
`agentcraft-buildings.json` (`Buildings.repair`, pure, `JournalRepairTest`; the journal is always written first): a record
whose site entry was undone with no pending site naming it becomes a pending removal; a record whose site is not the
journal's active one, which stands elsewhere, follows the journal (the entry's meta) and the old site becomes a pending
move; an active site entry with no record and no pending site (a placement whose record was not saved) gets its record back
from the entry's meta unless another building stands there; a pending site whose entry is active again with no record of
its building (the previous world start took the removal back in the journal and stopped before it saved the file) gets
its record back and the pending site is dropped, unless another building overlaps it (reported). Each says so in the
hub's Check line. Roads repair the same windows (`Roads.repair`). `CrashKillPointsTest` walks every kill point of place,
remove, move and of the world start's own release, record-back and move-back, and checks that an active site entry always
has its record and an undone one is always named by a pending site.

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
then the template at the new site (drafted in the world journal first), then **one journal commit** holding the new
site's entry and the old site's undo (its entry and its trophies'), then the old site restored (the entries kept undone
until the next world start, as for a removal), then the record. A failure before the commit takes the new site down
again and records nothing; a failure restoring the old site after it takes the commit back (the old entries active again,
the new one released) and the new site down, so the record never points at a site the journal does not hold
(`dev.buildings.failNextRename` makes the next move's journal commit fail). The id, repos, lead and home flag stay; the layout revision changes. `movedFrom` records the
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
- **Remove / Move**: the slots are inside the box, so the site's journal entry covers them and Remove puts back
  exactly what was there (the restore flags suppress drops: no sign item comes out; the drop cleaner catches any). Each
  hung sign is also a journal entry of its own (kind `trophy`, owned by the building, CELL policy: the cell before and
  after); Remove and Move undo them in one group with the site, Forget releases them, and a sign rewritten over our own
  older trophy folds that older entry into the new one (`Journal.absorb`), so a wall's stack never grows with the awards.
  A journal that cannot be saved is logged, not fatal (the box covers the slot); while the journal cannot be read at all,
  `award` answers UNAVAILABLE.
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

### Site warnings

What the ground in front of a building's entrance and under its entrance approach is like, which placing does not fix
and walkers meet when they step out of the door (`building.SiteWarnings`, pure, `SiteWarningsTest`). **Warnings never
refuse.** One function feeds the ghost (cells drawn magenta), the HUD (notes), the server's place note and the server
verdict's notes; `dev.build.state.conflicts.site {water, lava, drops, maxDrop, openings, gullies, caves, warnings[]}`.
- **In front of the entrance**: the strip the approach covers grown by one column on each side, from the box's front
  face out to 4 rows past the approach's end (4 rows when the blueprint has no approach). Per column the ground is
  searched as the approach does (12 above to 13 below the path's height there; trees are not ground): **water** or
  **lava** on the surface ("3 water blocks in front of the entrance"; the approach strip's own cells are the approach's
  to report, its lava still refuses), a **drop** of 3 or more blocks below the path ("a drop of up to 5 blocks in front of
  the entrance (12 columns)"), and no ground within reach: a **cave opening** or a deep gully. The strip's own columns
  are not drops (the approach's fill holds the path up).
- **Under the approach**: a path column whose foundation fill reached its 12-block limit without meeting the ground (a
  **gully** under the path), and air, water or lava within 3 blocks under the ground the path (or its fill) stands on (a
  **cave** under a thin roof).

### World journal (contract J1)

Every AgentCraft world change is an entry of one per-world journal, `<world>/agentcraft-journal/` (package
`dev.agentcraft.journal`, wave 3): buildings and fixtures (their whole site: template box, foundation and entrance
approach), roads, trophy signs, the leaves a site holds ("Held leaves") and the leaves around it ("Leaf ring"), anything later. An entry is `{id: "j<n>", kind, owner, dimension, policy, createdAt,
status, cells: [{pos, layer, before, after}], meta}`: `owner` is the record it belongs to (`b3`, `r2`; a trophy's is its
building), `before`/`after` a block state with its block entity data, `meta` the owner's record when it was made (crash
repair rebuilds a lost record from it).

**Layers.** The cells of every active entry at one position form a stack ordered by their **layer** (a later change is
higher; a cell keeps its layer when it moves to another entry). The top cell's `after` is what the world shows. Undoing
entries (`Journal.planUndo`, pure, `JournalTest`) takes them out of the stacks top-down per position:
- a cell **on top** writes the world: a **BOX** entry (a building's or fixture's site) always writes its `before`, so
  Remove still puts back exactly what was in the box, the player's later changes inside it included ("Safe remove"); a
  **CELL** entry (roads, trophies) writes it only where the world still holds its `after` (the contract's rule; cells the
  player changed since are left as they are). The box is written through vanilla's `StructureTemplate` exactly as the old
  snapshot restore (same flags, shape updates, block entity loading); cells with `setBlock`, lowest first, with the
  feature's flags. *Decision*: the contract's "restore only if the cell still holds `after`" is kept for CELL entries; for
  a building's box it would have changed Safe remove (a broken wall left a hole instead of the terrain), so BOX entries
  keep the documented behaviour;
- a cell **under a newer one** changes nothing in the world: **ownership passes down**, the newer cell's `before` becomes
  what this entry's undo would have made of it (its `before`; for a CELL entry whose `after` the newer cell did not find,
  the newer cell's `before` as it is). So overlapping changes undo in any order without holes or resurrected blocks: a
  village board placed over a road, then the road removed, then the board removed, gives the ground back (before, the
  board's snapshot held road blocks and brought the road back); every order of road, board and trophy is in `JournalTest`.
Undone entries keep their cells, what the undo wrote and every hand-down until the next world start settles them ("Crash
safety"): **released**, or **reactivated** (the undo never reached the disk), which reverses the hand-downs newest first
where the receiving cell still holds what was handed (else a chest an undone entry handed down would come back twice).
**Forget** releases entries (the blocks stay for good: a change under them later leaves them, one over them restores
them). **Transfer** moves cells between entries keeping their layers (road handover); **absorb** folds a trophy covered
by a newer one into it.

**On disk** (`JournalStore`): `journal.json` (the index: every entry's metadata and box, the id and layer counters, the
imported legacy file names) and `<id>.<gen>.nbt` per entry (gzip NBT: a state palette, positions, layers, before/after
indexes, block entity data, the undo). Block states are kept in 26.x's form `{id, properties}`, what
`NbtUtils.writeBlockState` and structure templates write and the only keys `NbtUtils.readBlockState` reads; a state in the
older form `{Name, Properties}` (an older journal, a snapshot or road file from before 26.x) is converted when it is read
(`Journal.Value.canonical`), so it compares equal to the world's value and restores as its block, not as air. Until
2026-10-06 the journal's own helpers (`Value.of`, `name()`, `Journal.AIR`) used the older keys: `name()` read every world
value as `minecraft:air`, so the import never recognised a hanging trophy sign. Undo bookkeeping is linear in the cells
(`JournalScaleTest`: 600k cells plan in about 0.4 s and reactivate in 0.1 s; `Map.copyOf` on packed positions and a cell
scan per hand-down took 20-40 s there before). A change is drafted as generation 0 before it touches the world (a crash leaves
it on disk; a full disk refuses the change first), committed as the next generation (written, read back), then the index
is replaced atomically (the commit point), then superseded files are deleted. At open, generations the index does not
name are leftovers (deleted) and files of entries it does not know (a change that never committed) are kept and listed
(`dev.journal.state.unreferenced`). Order for every change: the journal, then the feature's record file, then the
blocks (placing: draft, blocks, journal, record); the world start repairs what a crash between the journal and the record
file left ("Crash safety"). While the journal cannot be read or imported, place, move, remove, forget, lay and award
refuse with the reason (the ghost's verdict too) and nothing on disk is touched.

**Import** (`JournalMigration`, once, `JournalMigrationTest` against files in the old formats): when a world has no
`journal.json` but has `agentcraft-buildings/` or `agentcraft-roads/`, every recorded building's `<id>.before.nbt`
becomes an active BOX entry (its blocks and block entities exactly; `after` unknown, a box restore never needs it), each
pending site's file (`<id>.before.nbt` of a removal, `<id>.moved-<ms>.nbt` of a move) an undone one, each road's
`<id>.before.nbt` an active CELL entry with each cell's before and after, each pending removal (`<id>.removed-<ms>.nbt`,
or the never renamed `<id>.before.nbt`) an undone one, and each trophy sign the ledger says hangs in a recorded building
and still hangs there an active trophy entry (the ledger stays the awards record). Layers follow time (a road laid
before a board placed over it is lower). Every imported file name maps to its entry (`legacy`), so the pending records,
which keep their old names, still resolve. The index commit is the only "done" marker: the old folders move into
`agentcraft-journal/legacy/` only after it (every file kept, also leftovers such as `<id>.unused-<ms>.nbt`), a crash in
between only finishes the move at the next start, and an index that exists means no import runs again. An unreadable
`agentcraft-buildings.json` or `agentcraft-roads.json` aborts the import (nothing written; changes refused until fixed).
Ids are never reused: `place` skips any `b<n>` the journal (any entry, any status) or an imported file knows.

DevBridge: `dev.journal.state` (open, unavailable, counters, every entry's metadata, legacy names, unreferenced files,
the import's notes), `dev.journal.at {x, y, z, dimension?}` (the stack at one cell, bottom first, with layer, before and
after).

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
  other side, then the next route cells (up to 3 further) are tried, else it is noted. None along a stretch another
  road paved (it has its own; two posts stood side by side there), and the spacing starts again where the road leaves it.
- **Shared cells**: a cell another road already changed is left to that road (the column is skipped, noted "already
  part of another road"). Removing the first road hands the cells the other road still runs on to it (`Road.handover`:
  a changed cell in or beside a column of the other road's walker cells, from 2 below its feet to 3 above; the nearest,
  then the newest road takes it): they stay, go into that road's journal entry (keeping their layer, `Journal.transfer`)
  and changes, and its own removal restores them ("…; 294 cells kept for road r13 (it runs there too)"). Before, they
  went back and left the other road with holes.
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
  first: you at 12, 65, -3"). Order: the journal entry (written atomically, read back; the road record is its meta),
  the record, then the blocks; when the record cannot be written the entry is released and nothing is laid (no block
  stands without a record).
- **Blocks are set** with `UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS`: no neighbour or shape updates (nothing next
  to the road pops or reconnects), no drops, no `onPlace` (gravel never ticks), no block-entity side effects. New item
  and XP entities within a block of a changed cell are cleared anyway, right after and 3 ticks later (`Roads.CellDrops`;
  only new ones near the cells: a road's box can span the village, and the player's own drops there are never touched). Lanterns light the road
  (light updates still run). Like a building's blocks, the road's blocks come from the mod (no items are taken or given).
- **Records**: `<world>/agentcraft-roads.json` `{version: 1, next, roads: [{id: "r<n>", a, b, dimension, width,
  lanterns, bridge, created, length (route cells), cells: [x, feetY, z, ...], lanternCells, changes: [x, y, z, ...],
  notes}], pending: [{road, snapshot, at}]}`; ids are never reused. A malformed entry is skipped; a file that is not JSON
  at all is left alone and nothing is laid that session.
- **Snapshot**: the road's world journal entry (kind `road`, CELL policy; until wave 3
  `<world>/agentcraft-roads/<id>.before.nbt`), one cell per changed block `{pos, before, after}` (block states), never a
  box: a box restore would revert everything else in a long diagonal road's bounding box.
- **Remove road** (`Roads.remove(level, id)`): every cell that **still holds what the road put there** (the same state;
  for fences and lanterns the same block, as a neighbour update reshapes a fence's connections or water fills it; for
  slabs the same block and slab type, waterlogged or not) gets its old block back, ground first, then what stood on it; cells the player changed since are left as
  they are, and cells a building or fixture placed over the road now covers are left in the world and **handed down** to
  that site's entry (its removal later restores the ground, not the road: before wave 3 a board placed over a road
  brought the road blocks back when it was removed after the road) ("Removed road r2 (b1 to b3): 140 cells back as they
  were; 3 cells you changed since left alone"). Refuses while
  a player or a pet stands where an old block comes back (a bush at head height suffocates). Crash safety as for
  buildings: the journal commit (the entry undone with what it wrote, cells handed over moved to the receiving roads'
  entries), then the removal recorded under `pending` naming the entry (refused, the journal put back, when the record
  cannot be written), then the blocks; the next world start settles
  it on the cells the undo wrote (`Road.settle`), counting only cells no standing road changed (a road laid over the same ground
  later shows road blocks there although the removal reached the disk: counting them brought removed roads back): most
  telling cells hold the old blocks, or none tell -> the entry is released; most hold the road (the removal never reached
  the disk) -> the record and the entry come back, at most one per building pair; nothing readable -> kept. `forget` releases
  the entry and drops the record, leaving the blocks (also for a road whose snapshot is gone). Crash windows between the
  journal and `agentcraft-roads.json` are repaired first (`Roads.repair`, as for buildings). Imported pending removals
  (`<id>.removed-<ms>.nbt`, or the never renamed `<id>.before.nbt`) resolve through the journal's `legacy` map.
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
  route take the same expansions with an unrelated road as without (21 919 and 3 390; budget 120 000); with a
  30-block road inside the corridor's box (the village case) it takes 25 643.
  Route caches are dropped whenever a road is laid or removed (`Roads.signature`).
- **Ghost** (`RoadGhost`, the placement ghost's colours): new surface, half steps and decks tan; cleared cells orange;
  road cells left out red at their feet (the whole route red when the plan is refused); fence posts and lanterns brass.
  The HUD panel (`RoadHud`) says the pair, the options, the verdict, the counts and the first note; Enter lays, Esc or
  Backspace cancel (through the wizard's keyboard hook, only with no screen open; the player can walk around).
- QA: mod/DEV.md "Roads".

## Server API (mod, `dev.agentcraft.building`)

- `Blueprints`: registry (bundled + user folder), `get(id)`, `all()`, `reload()`.
- `Buildings`: `all()` (every site, fixtures included: overlap checks), `buildings()` (no fixtures: routing, leads, trophies), `fixtures()`, `get(id)` (either), `forRepo(repoId)`, `home()`, `layoutFor(repoId)` (that repo's
  building layout, else `Anchors.current()`), `place(level, blueprint, origin, rotation, repos,
  force) -> Building`, `verdict(level, blueprint, origin, rotation, repos, force, movingId) -> Verdict{refusals, notes}`
  (the dry run of `place`/`move`, contract S4), `remove(level, id)`, `forget(server, id)`, `setHome(server, id)`, persistence,
  change listeners. Errors are `Buildings.BuildingException` with a player-facing message.
- `dev.agentcraft.journal`: `Journal` (the pure layering rules), `JournalStore` (files), `JournalNbt`, `JournalMigration`,
  `WorldJournal` (the running world's journal: capture, planUndo, apply, commit). See "World journal".
- Commands (gamemaster): `/agentcraft blueprints [reload]`, `/agentcraft buildings`, `/agentcraft build` (the wizard),
  `/agentcraft place <blueprint> <repo>[,<repo>...] [rotation] [force]` (in front of the player,
  ground at the player's feet), `/agentcraft place village_board` (a fixture: no repos),
  `/agentcraft remove <buildingId> [forget]`, `/agentcraft home <buildingId>`.
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
   **Server verdict** (contract S4, wave 3): the client's checks are the fast preview; when they pass, the client asks the
   integrated server for `Buildings.verdict` of the exact site (blueprint, origin, rotation, repos, the building moved,
   force): the same checks `place` (or `move`, old site included) runs, in the same words, every reason instead of the
   first, as a dry run (no world change; it never loads or generates a chunk: an unloaded site is a refusal of its own).
   Asked once when the site changes and then every second (one request in flight; a reply for a site the ghost left is
   dropped). The HUD says "Checking the site with the server…" until it arrives, "Ready" only when the server agrees, else
   "Server would refuse: <reasons>" (and the ghost's outline turns red). Enter runs the verdict and `place` in one server
   task, so a refusal lists every reason; that verdict reads the site as `place` does (it may load the site's chunks, as
   placing does), only the polling never loads one. `dev.build.state.serverVerdict {ok, refusals, notes}` and `ready`.
   **Too far** (wave 3): looking further than the 64-block reach (or at the sky) keeps the ghost at the last spot that
   was in reach in this placement session, with an orange "Too far: aim within 64 blocks" HUD line
   (`dev.build.state.tooFar`), instead of snapping it to the player's feet; before any spot was in reach the feet
   fallback is as before. A locked ghost or an explicit origin is not affected.
   **Site warnings** (wave 3, see "Site warnings"): drawn magenta and listed as notes.
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
- beds (`bed`, `bed_2`.., see "Beds"): on a bed's head half, the matching foot half behind it, yaw = its facing, written
  `occupied=false`, a solid floor and air above both halves, a free standable cell beside it, one anchor per bed,
  inside `walk`; optional (no beds is fine);
- trophy slots (`trophy*`, see "Trophy slots"): wing in range, yaw a multiple of 90, the cell inside `walk` and explicit
  air, a full opaque block behind it, one slot per cell, not an agent's feet/head cell; a wing without any slot is a
  warning. They are block anchors for `verify.mjs` (no floor/headroom check);
- fixtures (`kind: "fixture"`, `wings: 0`): `board` (on an `agentcraft_worlds:village_board`, yaw = its facing) and `spawn` required
  instead of the building anchors; no walk/light/doorway rules (outdoors), the no-mod shell leak check stays, a full opaque
  vanilla block behind every AgentCraft cell, no `repo:`/`ci:` bindings, no trophy slots, a warning without a vanilla light;
- `@<n>` anchors in range 1..wings; `foundationBlock` a full, opaque `minecraft:` block; `approach` an object or
  `false`, `length` 0..16, `width` 1..7, `block` a `minecraft:` full block, `slab` a `minecraft:` slab (when the kit
  knows them).

## Verify loop (tools)

`tools/blueprints/verify.mjs <id>`: with the dev client running (`node tools/unix.mjs launch
--backend sim --dev`), places the template in the dev HQ world away from the studio, takes an
exterior orbit (4 views) + interior views (each `cam_*` anchor), writes PNGs and a contact sheet
under `artifacts/shots/blueprints/<id>/` (see `tools/blueprints/VERIFY.md`), and checks the sidecar (required anchors, anchors inside
`walk`, standing anchors on a floor with head room).
