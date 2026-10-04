# Fix wave 1 (from docs/AUDIT-2026-10-03.md): streams and shared contracts

User decisions (2026-10-03): Iris removed from the instance (done); hub Teleport only when cheats are on
or the player is in creative/spectator; buildings switch to **vanilla materials** (AgentCraft blocks only
where they are functional), so a world opened without the mod keeps its buildings; **no AgentCraft or
Claude co-author trailers/footers** anywhere (commits, squash commits, PR text). Config hardening
applied to ~/.agentcraft/config.json by the coordinator (connectors [], deny az/dotnet run/ef/watch/
func/sqlcmd, leads ["marlow"], maxConcurrentTurns 2, maxBudgetUsdPerTurn 10, prWatch observe,
explicit baseBranch per repo, only local-config files in protect).

Each stream works on its own branch `fix/<stream>` in its own worktree and owns its files; small edits
outside its area are fine when a contract below needs them. Wave 2 (after this merges): one Inbox/Team
surface (decisions, replies, blocked tasks, agent cards/logs), persistent HUD alert line, away-digest
visibility, hub badges, onboarding/help, 4K/auto-scale layout pass, walking routes between buildings.

## Streams
- **ui** (mod client screens/interaction): pause in singleplayer, agent NPC click, lecterns, diff
  opener + merge confirm, PR-decision free text (mod side), crash guards on client tick handlers, hub
  Teleport gating, `B` default unbound, undo texts, agent card reachable from Team tab / TaskScreen /
  console roster, card Review for any decision + answers through DecisionsFeature.answer, parents for
  Diff/Console/review screens, one Enter rule, console plain text no longer silently creates a goal,
  console terminal defaults to its building's repo, Team tab "release other worlds' leads".
- **world** (mod building/placement/routing/driver): server occupancy check, safe Remove, clearDrops
  only placement drops, fluids, crash ordering of snapshots, dimension-aware buildings (driver, agents,
  podiums), worker decisions on their building's podium + podium click filtered, per-building monitors/
  merge station/goal lamp, change a building's repos, too-few-wings message, terrain fit (foundation
  fill + Y suggestion), move building.
- **blueprints** (tools/blueprints + bundled resources): vanilla materials, iron doors with buttons,
  lighting by vanilla emitters, functional blocks never part of the outer shell, foundationBlock in
  sidecars, a 5-wing campus, regenerate everything + checker/renderer updates, design-agent kit.
- **foreman**: connector gate fail-closed, protected uncommitted edits, auth retry + regexes,
  transient-failure auto-resume, plan retry, lead session rotation, usage wake interval, usage reserve,
  strip ANTHROPIC_API_KEY under claude login, no trailers/attribution, lead world expiry +
  lead.releaseWorld, goal adoption on assign, closed decisions, PR follow-up markDirty, leadView fetch
  cache, toasts naming GUI paths, Discord notifications, CI timeout setting, worktree/branch sweep.
- **launch** (tools + one mod banner): stable launch from Prism, install script for the Hardcore
  instance, port/profile separation from dev, launcher log rotation, ConnectionBanner text.

## Contracts
- C1 `Decision.textAllowed?: boolean` (absent = true). The Foreman sets false where only the options
  make sense (PR "Post replies", "Fold in / Leave it", other closed choices) and refuses a
  `decision.answer` without a valid option for them. The mod hides/disables free text when false.
- C2 Lead worlds: the Foreman records `lastSync` per world; at start and daily it drops assignments of
  worlds not synced for `claude.leadWorldTtlDays` (default 14). New client message
  `lead.releaseWorld { world }` -> ack `{ released: [leadId] }`. `leads.update`/`LeadAssignment` gains
  `world?: string` and `lastSync?: Ts`. The mod's Team tab lists other worlds holding leads with a
  "Release" button.
- C3 Goal adoption: when a lead is assigned a building, open goals whose `repoId` (or `repos[0]`) is in
  that building move to it (Foreman only; feed line).
- C4 Foundations / terrain: sidecar gains `foundationBlock` (vanilla block id, default
  `minecraft:stone_bricks`). On placement, below every floor-row cell of the footprint the server fills
  air/fluid/replaceable cells downward with `foundationBlock` until solid ground, at most 12 blocks;
  above ground it clears natural terrain inside the box. The snapshot box extends down to cover the
  fill so Remove restores it. Placement suggests origin Y from the footprint's median surface height
  (the player can still raise/lower).
- C5 Materials: bundled blueprints use vanilla blocks for structure, floors, roofs, trim and light.
  AgentCraft blocks only where functional: monitor, task board, decision podium, console terminal,
  status lamps (CI/agent/decision lamps), merge station, memory archive/catalog, test bench. Every
  wall-mounted functional block has a solid vanilla block behind it on the outside, so a world without
  the mod has no holes in the shell. Every interior air cell has block light >= 1 from vanilla
  emitters alone. Doors: iron doors with a stone button on both sides (zombies can't break them).
- C6 Screens: every AgentCraft screen returns `isPauseScreen() == true` in singleplayer unless the
  DevBridge/dev run says otherwise (dev runs keep the current behaviour for QA).
- C7 Teleport (hub): allowed when the integrated server allows commands for the player (cheats on) or
  the player is creative/spectator; otherwise the button is hidden with a note.
- C8 Attribution: no `Co-authored-by` trailers, no "Built and reviewed in AgentCraft" footer, squash
  commits authored by the user only, agents' own commits without Claude attribution (SDK settings,
  e.g. `includeCoAuthoredBy: false` / `attribution`), PR descriptions without AgentCraft mentions.
- C9 `foreman.status.hold?: { reason: "usage"|"auth"|"offline", until?: Ts, message }` while the
  backend holds new turns (wave 2 shows it persistently; wave 1 just provides it).
- C10 Discord: config `notify.discord: { script, ping: string[], silent: string[] }` (off by default;
  levels from `notify` kinds); the notifier runs the script (`script <level> <title> <body>` or the
  script's real interface - read the configured script) without blocking.
