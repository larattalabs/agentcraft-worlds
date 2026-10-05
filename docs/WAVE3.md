# Wave 3: leftovers, refactors, sim parity, smoke test (streams and contracts)

Wave A runs four streams in parallel; wave B (codegen + smoke test) starts after A is merged.

## Wave A streams
- **foreman**: split `agents/claude/index.ts` into job modules (plan, work, review, followup/triage, goal
  messages, design, sessions/usage) behind the same backend class and behaviour; one shared shell lexer
  for `policy.ts` and the Foreman-private guard; tests for unanswered goal messages re-queued after a full
  Foreman restart and old plans linked to goals (`planId` migration); the Foreman side of S1/S2 below.
- **sim**: the sim backend gains parity for PRs (contract S3): repos with `land: "pr"` get simulated PRs
  (open -> automated review thread in the "Claude Code Review" format -> triage turn -> optional fold-in ->
  merged), so PR watching, triage decisions, the Inbox PR items and the village board's PR counts can be
  exercised in game with no network; plus sim designs/leads/goal messages kept working.
- **world** (mod building/roads/trophies/fixtures): one layered world-change journal (contract J1)
  replacing the per-feature snapshot/restore code; natural drops (leaf litter, saplings, sticks, apples,
  seeds from cleared plants) never block Remove; a server verdict before the ghost says Ready (S4); site
  warnings (exposed water/lava/cave openings or a drop/gully in front of the entrance or under the
  approach); look placement beyond 64 blocks (the ghost stays at the last valid aim instead of the feet,
  with a "too far" note).
- **ui** (mod client): the text-depth fix (text a hair in front of its own background, as for nameplates
  and bubbles) applied to monitors, status-lamp labels, task boards, the village board and any other
  in-world text; toast priority (needs-you toasts first and never hidden behind info toasts; one
  "N more" line when stacked); blocked/waiting agents skip stand-ups (and any agent whose task needs the
  user); leader lines never cross another agent's plate; the agent log paging (`more`) covered by a test;
  the Buildings tab extracted from HubScreen into its own class like the other tabs; the mod side of S1/S2.

## Contracts
- **S1 Secret maps.** A new SettingDef type `secretMap`: `value` is `{ KEY: "(set)" }` (keys only, never
  values); `config.set` takes `{ KEY: string | null }` as a partial update (null removes a key). Used for
  `repoSettings.<repo>.env` (no longer read-only) and each MCP server's `env`. Values are never logged,
  echoed in acks or broadcast.
- **S2 MCP servers.** `claude.context.mcpServers` becomes editable: SettingDef type `mcpServers`, value =
  `[{ name, type: "stdio"|"http"|"sse", command?, args?: string[], url?: string (no credentials, no query),
  envKeys: string[] }]`; `config.set` takes the same entries (env through the S1 partial form, under
  `env`) plus `{ name, remove: true }`. Restart-required. The hub shows a list with add/edit/remove and the
  env as a secret map; `mcpAllow` stays where it is.
- **S3 Sim PRs.** Sim repos marked `land: "pr"` (sim config or a sim-only demo repo setting) produce
  `Task.pr` like the real backend, a fake host (no `az`/`gh` calls), review threads with the parser's
  real format (use the test fixtures' format), the same triage decision shapes, and merge after approval
  on a timer. `pr.refresh` works. Nothing touches the network.
- **S4 Server verdict.** Before placement mode shows "Ready" (and on Enter), the client asks the
  integrated server for a dry-run verdict of the exact site (same checks as `place`, no world change);
  the ghost/HUD shows the server's reasons; the client-side checks stay as the fast preview.
- **J1 World journal.** Every AgentCraft world change (buildings, approaches/foundations, fixtures,
  roads, trophies, anything later) is an entry `{ id, kind, owner, cells: [{pos, before, after}],
  createdAt }` in one per-world journal (`<world>/agentcraft-journal/`, crash-safe like today's pending
  snapshots, with a migration that imports existing `.before.nbt` snapshots and roads/trophy records
  losslessly). Undoing an entry restores a cell only if it still holds that entry's `after`; when a newer
  entry owns the cell, ownership passes down (the newer entry's `before` becomes the older entry's
  `before`), so overlapping changes undo in any order without holes or resurrected blocks. Existing APIs
  (`Buildings.place/remove/move`, `Roads.lay/remove`, `Trophies.award`) keep their behaviour on top.
