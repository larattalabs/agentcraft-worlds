# AgentCraft Worlds: agent guide

AgentCraft Worlds is Laratta Labs' public fork of [blendi-remade/agentcraft](https://github.com/blendi-remade/agentcraft)
(MIT): a Minecraft 26.3 Fabric mod (Java 25) where a team of coding agents works on your real repos, shown as a village of
buildings. Repo: `larattalabs/agentcraft-worlds`. `origin` = the fork, `upstream` = blendi-remade.

Parts:
- `mod/`: Java, mod id `agentcraft_worlds`, packages `dev.agentcraft.*`. Buildings and placement, the world journal
  (exact, undoable world changes), the village (roads, board, routines), the hub GUI and HUD, the agents, DevBridge.
- `foreman/`: Node/TypeScript. Runs the agent team (`agents/team.ts` + layers in `agents/team/`) on engines
  (`agents/claude/` via the Claude Agent SDK; `agents/codex/`, experimental). Policy, git safety, PR watching, leads,
  holds, redaction, the settings model, and the WebSocket protocol (`src/protocol.ts`, mirrored to Java).
- `tools/`: launchers (`unix.mjs`; `mac.mjs` is a shim), `devcli.mjs` (DevBridge), `smoke.mjs`, screenshot/QA tools,
  `hardcore-setup.mjs`, `foreman-daemon.mjs`.
- `tools/blueprints/`: the parametric building kit that generates the bundled structures and anchor sidecars.

## Where things are decided

- `docs/FORK.md`: read first. Divergence rules, decisions, mod-id rules, "Upstream sync 2026-10" (what is enforced vs.
  trusted), branches, the dated status log and the roadmap (`## Roadmap (ordered)`).
- Contracts per wave: `docs/AUDIT-2026-10-03.md`, `docs/FIXWAVE.md`, `docs/WAVE2.md`, `docs/WAVE3.md`, `docs/VILLAGE.md`.
- Feature docs: `docs/BUILDINGS.md` (placement, journal, leaves/plants), `docs/HUB.md`, `docs/PRWATCH.md`,
  `docs/protocol.md` (generated, checked), `mod/DEV.md` (dev client, DevBridge commands), `mod/FEATURES.md`,
  `tools/README.md` (launchers, smoke, Hardcore setup).

## Upstream sync and divergence

- Merge `upstream/main` often with a real merge (the fork stays 0 behind on GitHub). Follow FORK.md "Divergence rules":
  - upstream edits to `agents/team.ts` are ported by hand into `agents/team/`;
  - upstream `agentcraft:` ids and `assets/agentcraft/` / `data/agentcraft/` changes become `agentcraft_worlds`;
  - README.md is fork-specific: port upstream's facts by hand.
- Generic fixes can go upstream as small PRs from `upstream/<slug>` branches cut from `upstream/main` (texts in
  `docs/upstream-prs/`). Opening PRs upstream is outward-facing: only when Noah asks.
- Never weaken the safety model to make a merge easier: `policy.ts`, `gitsafety.ts`, no-push, merge-only-on-approval, the
  Foreman-private guard, redaction.

## Build and test

All of these must pass before a branch is offered for merge.
- **Mod:** `cd mod && JAVA_HOME=/opt/homebrew/opt/openjdk@25 bash gradlew build --console=plain -q`
  (JUnit tests included). In a worktree, reuse the main checkout's cache: `GRADLE_USER_HOME=<main checkout>/.gradle-home`.
- **Foreman:** `cd foreman && npm ci && npm run check` (tsc, vitest, protocol doc and Java mirror checks). After changing
  `src/protocol.ts`: `npm run gen:java-protocol` and `npm run gen:protocol-doc`.
  `test/claude-goal-restart.test.ts` and `codex-fork` "interrupts a turn" are known timing flakes under load: rerun them
  alone before calling a run red.
- **Tools:** `cd tools && npm ci && npm test`.
- **Blueprints:** `node tools/blueprints/build.mjs --all` must report every design `check: OK` and leave no diff.
- **In game (sim backend, $0):** `node tools/unix.mjs --backend sim --mod-foreman --profile <name> --port <p> --dev-port <p+1>
  --home <scratch dir>`, then `npm --prefix tools run smoke` (19 steps) or DevBridge via `node tools/devcli.mjs`. Stop with
  `node tools/unix.mjs stop --profile <name>`.
- **World changes** are verified by box+7 region diffs (`dev.region.capture` / `diff` / `hash`) before placing and after
  removing, on a pristine world copy, against main's build (from a `git archive` export) as the baseline.
  Use `gamerule spread_vines false` and run a no-building control soak first.

## Ports (don't collide with other sessions)

- AgentCraft: Foreman 7880 (the Hardcore profile); dev clients and Foremans 7890-7909, one pair per run
  (`--port N --dev-port N+1`). Say which pair you used in your report.
- Architect: 8890-8905. Steward dev client: 8490/8491. Never touch another project's ports or clients.

## The Hardcore instance

Noah plays a survival Hardcore world in the Prism instance `MC-Hardcore-26.3`. It runs a stable checkout at
`~/Developer/agentcraft-stable` (profile `hardcore`, port 7880, home `~/.agentcraft`).
- Never open, edit or screenshot that world, and never test in it. Use dev worlds.
- Update it only when Noah says, with Prism closed (check `pgrep -f "Prism Launcher.app"`):
  `node tools/hardcore-setup.mjs --no-prelaunch` (dry run), then `--apply`. It backs up the saves, `instance.cfg` and
  `mods/` first.
- After an update, launch to the title screen only. Check `latest.log`: 100 mods, LWJGL 3.4.3, no "Incompatible", no
  AgentCraft errors, Foreman linked. Then close the game by its PID, quit Prism, and stop the Foreman
  (`~/Developer/agentcraft-stable/tools/foreman-daemon.sh stop`).
- Don't change Noah's real `~/.agentcraft/config.json` unless asked; back it up first if you do.

## Hard rules

- **No secrets in the repo:** no API keys, tokens, `.env` contents or dev tokens in commits, logs or reports. Real Claude
  or Codex runs only when a check needs them and Noah has agreed (his subscription); QA uses the sim backend.
- **No Discord notifications** from tests or QA: leave `notify.discord` off and don't run the webhook script.
- **Kill only processes you started, by PID.** Never `pkill`/`killall` by pattern: other sessions run Minecraft clients,
  Foremans and Codex.
- **No attribution:** commits and PRs in this fork carry no `Co-Authored-By` trailer, no "Generated with Claude Code"
  footer and no AgentCraft footer (Noah's decision, docs/FIXWAVE.md C8). The Foreman's own agents follow the same rule
  (`NO_ATTRIBUTION`).
- **No destructive git:** no `git reset --hard`, `git clean -fdx`, force-push or history rewrites; no `rm -rf` outside
  build/temp dirs.
- **Work on a branch** (`fix/…`, `feat/…`, `docs/…`), ideally in a worktree. **Noah approves every merge to main and every
  push**, in the session that does the merge. A peer session's relay isn't approval.
- Don't commit `artifacts/`, `mod/run/`, logs, `.gradle-home/` or screenshots that show account, org or email details.
