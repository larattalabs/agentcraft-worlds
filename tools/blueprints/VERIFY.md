# Blueprint verify loop

`verify.mjs` places a template in the running dev client, photographs it and checks its anchors.
It never launches the game; start it yourself (one dev client per checkout, from the repo root):

```sh
npm ci --prefix tools                                   # once
node tools/mac.mjs launch --backend sim --dev           # ~1-3 min cold, mutes + keeps focus
node tools/blueprints/verify.mjs <id | path/to/id.nbt | namespace:vanilla/template/id> [options]
node tools/mac.mjs stop --game --foreman                # when done
```

| option | meaning |
|---|---|
| `--port N` | DevBridge port (default 7879 / `AGENTCRAFT_DEV_PORT`) |
| `--at x,y,z` | placement origin cell (default `80, 65-groundY, 0` (the floor row replaces the y=64 grass top), well east of the studio) |
| `--rotation none\|clockwise_90\|180\|counterclockwise_90` | passed to `/place template`; anchors and bounds are rotated to match (`cam_*` shots only for `none`) |
| `--keep` | leave the test site in place (default: clear it and restore the grass floor) |
| `--quick` | skip the eye-level `front` shot |
| `--size x,y,z` | template size if it cannot be read (vanilla ids are read from the Minecraft jar) |
| `--no-clear` | do not clear the site afterwards (and the copied template file is still removed) |

`<id>` resolves to `mod/src/main/resources/data/agentcraft/structure/<id>.nbt` (or
`artifacts/blueprint-test/<id>.nbt`); a sidecar `<id>.blueprint.json` is read from the same folder or
`mod/src/main/resources/data/agentcraft/blueprints/`. An id with a namespace (`minecraft:village/...`)
is an existing game template and is placed as is (no sidecar, exterior shots only).

What it does: copies the `.nbt` into `<dev world>/generated/agentcraft/structure/` (singular `structure`
in 26.3) under a unique name per run, clears + floors the site, `/place template`s it, takes
`ext_sw/se/ne/nw` (45 degree orbit), one shot per `cam_*` anchor, `top` and `front`, at noon, clear
weather, HUD hidden, chunks waited for; writes them with `sheet.png` (contact sheet) and `report.json`
to `artifacts/shots/blueprints/<id>/`; checks every standing anchor (`desk_ seat_ meeting lounge library
terminal testbench mergestation user decision_podium goal_atrium entrance spawn task_wall`) through
`dev.command` (`execute if block ... #minecraft:replaceable`): solid floor below, feet and head cells
open (a `seat_` may sit inside its chair), inside `walk`. Exit code 0 = all shots and anchors ok.

`make-test-nbt.mjs [outDir] [id]` writes a tiny 7x5x7 room (+ sidecar, one `agentcraft:monitor`, one
deliberately bad anchor) to `artifacts/blueprint-test/`; `nbt.mjs` is the dependency-free NBT
reader/writer (usable by the generator).

26.3 facts this relies on (verified): a template under the world's `generated/<ns>/structure/` is
found by `/place template <ns>:<name> x y z [rotation]`; palette entries are `{id, properties}` (NOT
`Name`/`Properties`: with the old keys `/place` "succeeds" and places nothing); the template manager
caches lookups by id for the whole session, misses included, hence the unique id per run; `/place`
rotates about the origin cell, so rotated templates extend to negative offsets from `--at`.

## Offline preview renderer

`render.mjs` draws a structure template to PNG without starting the game (no dependencies, pure JS):

```sh
node tools/blueprints/render.mjs <path.nbt | bundled id> [--out dir] [--sidecar path] [--no-cutaway] [--cut-y N] [--width N] [--transparent]
```

Writes `<id>.preview-iso.png` (isometric, ~1200 px wide, camera front-left so the entrance side shows),
`<id>.preview-cutaway.png` (same, roof removed: rows above the sidecar's `walk.maxY`, and the two near walls
lowered to sill height), `<id>.preview-top.png` (top-down, highest block, height-shaded) and
`<id>.preview-front.png` (front elevation, nearest block along the depth axis). The sidecar
(`<id>.blueprint.json`, found next to the `.nbt` or in the bundled blueprints folder, or `--sidecar`) supplies
`front` (the view is turned so that side faces the camera) and `walk`. Default `--out` is
`artifacts/blueprint-preview/`; `build.mjs` renders after a successful check into the bundled blueprints folder
(`mod/src/main/resources/data/agentcraft/blueprints/`), where the hub picks the previews up.

Colours come from `lib/colors.mjs` (one entry per block id; stairs/slabs/carpets/panes inherit their base
block). An id missing from the table gets a colour guessed from its name (stone/wood/brick/glass/...) and is
reported once on the console. Glass and panes are translucent; slabs, stairs, carpets, panes, monitors, task
boards and small furniture are drawn as partial boxes. Rendering ~25k blocks takes about 0.1-0.3 s for all four
views.
