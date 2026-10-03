# The AgentCraft Hub and generated buildings (contract)

One in-game screen for everything a player sets or does in AgentCraft, instead of chat commands,
config files and launch flags. Chat commands stay as thin aliases for scripts and the dev tools.
In a Hardcore world (cheats off) the hub is the way to do anything: it acts through the integrated
server (`ServerTasks`), so it needs no operator permission; commands do.

## Hub screen (mod, client)

**Status (branch `mod/hub`):** implemented: the screen, `H`, `/hub [tab]`, the **Buildings** tab
(buildings with make home / teleport / remove, blueprint browser with plan + rendered previews, Place;
Design new is a disabled placeholder with a hook, `HubFeature.designNew`) and the **Status** tab. Repos,
Goals, Team and Settings show a "coming next" panel. Generated buildings and the offline renderer below
are not part of this step (previews are shown when the renderer's PNGs exist). Code:
`mod/src/client/java/dev/agentcraft/client/hub/`, notes in mod/DEV.md "Hub".

- Opened with a key (default `H`, rebindable, AgentCraft category; vanilla binds H only as F3+H) and
  from the console (`/hub [tab]`). *(done)*
- Tabs, in this order (later waves fill the ones marked *later*):
  1. **Buildings** *(done)*: the world's buildings (blueprint, repos, home marker, box, rotation),
     actions: place new (opens the existing wizard), make home, remove (two-step confirm), teleport to
     (the entrance anchor, same dimension, a free spot with a floor); a blueprint browser (bundled +
     user, with the top-down plan and, when present, the rendered previews `<id>.preview-{iso,top,front,
     cutaway}.png` from the user folder, else the mod's `data/<ns>/blueprints/`), Place (repo step with
     the blueprint fixed, then placement mode) and **Design new** (below; a disabled placeholder until
     the form exists).
     Buildings do not record their dimension: remove and teleport refuse unless the box holds
     AgentCraft stations in the player's dimension; remove also refuses while the player stands in it.
  2. **Repos** *(later)*: registered repos and their `repoSettings`.
  3. **Goals** *(later)*: submit a goal (repo, "continue a branch", earlier session), open goals.
  4. **Team** *(later)*: agents, roles, models, effort, shift.
  5. **Settings** *(later)*: permissions, context, connectors, session history, usage.
  6. **Status** *(done)*: Foreman connection, backend, auth/account, usage windows (percent, reset
     time), spend, mod and Foreman versions, DevBridge state.
- Style: the existing UI kit (`ui/Kit`, `ui/Panels`, `gui/ui-style.json`, `palette.json`), like
  `DecisionScreen`. Shootable through `DevBridge.registerScreen("hub", ...)` with a tab argument.
  *(done: screens `hub`, `hub_<tab>`, `hub_blueprints`; `dev.hub.open {tab}`, `dev.hub.state`,
  `dev.hub.action`, see mod/DEV.md "Hub")*

## Generated buildings

### The form (Buildings tab -> Design new)

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
- `design.cancel` `{ id }`

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
- Permissions: the normal policy with `cwd` = the scratch dir; network off; no subagents needed.
- When done, the Foreman (not the agent) re-runs the checker and renderer, then copies
  `<blueprintId>.nbt`, `<blueprintId>.blueprint.json` and the preview PNGs
  (`<blueprintId>.preview-*.png`) into `outDir`, and reports `done` with the paths. Ids are
  `gen_<slug>` and never overwrite an existing file (suffix `_2`, ...).
- The sim backend fakes a job (copies the bundled workshop under a new id) so the UI is testable.
- One design at a time; queued requests wait. Usage limits apply like any turn.

### Review and place (mod)

- On `design.upsert` done: the mod reloads blueprints (`Blueprints.reload` on the server thread),
  the Buildings tab shows the new blueprint with its previews, and "Place" opens the wizard's
  placement mode with it (ghost = the real review; nothing is placed without confirm).

## Offline renderer (tools)

`node tools/blueprints/render.mjs <path.nbt|id> [--out dir]` writes `<id>.preview-iso.png`
(isometric, from the front-left, entrance side visible), `<id>.preview-top.png` (top-down) and
`<id>.preview-front.png` (front elevation), from block colours (a colour table per block id; textures
optional later). Dependency-free PNG output (zlib + CRC), fast (< 2 s for 100k blocks). Used by the
design agent, the Foreman, and tests.
