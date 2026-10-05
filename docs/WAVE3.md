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
- **S1 Secret maps.** A new SettingDef type `secretMap`: `value` is the list of variable names
  (`["KEY", ...]`, never values); `config.set` takes `{ KEY: string | null }` as a partial update (null
  removes a key). A value that is a placeholder (`"(set)"`, `"(hidden)"`, `"(staged)"`, `"[redacted]"`) or
  holds control characters (tab / newline / CR excepted) is refused, so echoing a view can never overwrite
  a secret. Used for `repoSettings.<repo>.env` (no longer read-only) and each MCP server's `env`.
- **S2 MCP servers.** `claude.context.mcpServers` becomes editable: SettingDef type `mcpServers`, value =
  `[{ name, type: "stdio"|"http"|"sse", command?, argCount?, url?, urlHasPath?, headerKeys?, envKeys }]`:
  `command` is the executable only, `argCount` how many arguments are stored, `url` scheme://host[:port]
  (`urlHasPath`: the stored URL has more). Argument values, the rest of a command line and the full URL are
  never returned: they are write-only. `config.set` takes `{ name, type, command?, args?, url?, env? }` (env
  through the S1 partial form) or `{ name, remove: true }`: a field left out keeps the stored value exactly,
  a field sent replaces it exactly (`args`: the complete new list); there are no placeholders and no
  restoration. A `command` / `url` equal to its own view of a longer stored value is refused; a new server
  (or one changing between stdio and http) needs its command / URL; the view's read-only fields may come
  back and are ignored (type-checked); URLs refuse spaces, control characters, credentials and fragments
  and are stored normalized. Restart-required. The hub shows a list with add/edit/remove, "Replace
  arguments…" / "Replace URL…" and the env as a secret map; `mcpAllow` stays where it is.
- **S1/S2 errors and redaction** (after the wave-3 security review). Errors name setting keys and places
  (`change #2`, `server #1`, `env key #3`, `item #2`), never what the caller sent. A central redactor
  (`foreman/src/redact.ts`) holds every secret value the Foreman knows - repository and MCP env values,
  MCP arguments, URL paths / queries, header values, the rest of a command line, the client token - and
  cuts them (`[redacted]`; also URL-encoded, JSON-escaped and base64; values of at least 6 characters)
  from agent log entries, feed items, `agent.say`, notifications (desktop, Discord, `notify`), ack /
  error texts, setup / test output and console logs, before they are truncated, persisted or broadcast;
  stored feed and log text is cut again when the snapshot, `agent.logs.request` or `goal.digest` replays it.
  Structured text is stored and sent without them too: a task's `blockedReason` and `summary`, a
  decision's question, context and answer text, agent messages (bus), memory notes and a design's step /
  error are redacted when written and again in `*.upsert`, the snapshot and the digest. Values with a NUL in
  config.json are dropped at load (a spawn error would quote them).
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
