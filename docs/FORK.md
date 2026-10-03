# Fork notes

Fork of [blendi-remade/agentcraft](https://github.com/blendi-remade/agentcraft), started 2026-10-03.
`origin` = this fork, `upstream` = blendi-remade. Upstream is days old and moving fast: merge
`upstream/main` often, and keep changes small and isolated so those merges stay cheap.

## Divergence rules

- Generic fixes (bugs, per-repo config, rate limits) go on their own branch and are offered upstream
  as PRs; once merged upstream, our copy disappears on the next sync.
- Personal behaviour goes behind config (`~/.agentcraft/config.json`), new modules, or an extra MCP
  server rather than edits spread through `agents/claude/index.ts` / `policy.ts`.
- Never weaken the safety model: the permission policy (`policy.ts`), git safety (`gitsafety.ts`),
  no-push, and merge-only-on-approval stay intact. Do not enable `settingSources` wholesale: settings
  `permissions.allow` rules are evaluated before `canUseTool` and would bypass the policy.

## Decisions

| Date | Decision |
|---|---|
| 2026-10-03 | Public fork, so generic fixes can go upstream as PRs. |
| 2026-10-03 | Hardcore world moves to MC 26.3 via a **new cloned Prism instance**; the 26.2 instance stays untouched as a fallback until the clone has soaked. |
| 2026-10-03 | `main` = upstream + every topic branch merged; topic branches stay for upstream PRs. New work: topic branch off `upstream/main`, then merge into `main`. |
| 2026-10-03 | Build order below. Phase 0 (verification) deferred by choice; Phase 1 starts now. Phase 0 must pass before anything enters the Hardcore world. |
| 2026-10-03 | Agent context: repo `CLAUDE.md`/`AGENTS.md` on by default; the user's global `~/.claude/CLAUDE.md` opt-in only (interactive-workflow rules would fight the role prompts); extra files by path. Read by the Foreman and appended, never via `settingSources`. |

## Branches

`main` is the fork's integration branch: upstream plus every topic branch below, merged. Each topic
branch is cut from `upstream/main` so it can go upstream as its own PR.

| Branch | What | Upstream PR? |
|---|---|---|
| `mod/safe-defaults` | roadmap 7–8 (+ this file) | yes, without `docs/FORK.md` |
| `foreman/node-test-spec-output` | parse Node 24's `node --test` spec output (upstream test fails on Node 24 without it) | yes |
| `foreman/plan-per-goal` | roadmap 1 | yes |
| `foreman/repo-settings` | roadmap 2 | yes |
| `foreman/rate-limits` | roadmap 3 | yes |

## Build sequence

Items refer to the roadmap below. Each phase ends at a gate; don't start the next phase until it passes.

**Phase 0: verify what exists** *(deferred 2026-10-03; required before any Hardcore use)*
1. Mod jar in a throwaway 26.3 instance + new Hardcore world: no HQ world created, volume untouched,
   pause-on-lost-focus intact, nothing on :7879, `/agentcraft hq` refused.
2. Soak the new 26.3 Hardcore instance without AgentCraft (first launch, one-way world upgrade, Sodium alpha + DH).
3. Real Foreman run from `main` (`--use-claude-login`, 2 workers, one non-critical repo with
   `repoSettings`): worktree setup, CI, usage-limit handling.

**Phase 1: Foreman features**
4. Roadmap 4: agent context (instructions, skills, MCP).
5. Roadmap 5: roles (per-agent role prompt, model, effort; cheap-task routing; per-agent memory notes).
6. Usage banner: `getUsage()` 5-hour/7-day windows in `foreman.status` (finishes roadmap 3).
- *Gate:* about a week of real work on Phase 1.

**Phase 2: mod, for the Hardcore office** (independent of Phase 1; can swap order)
7. Roadmap 9 + DevBridge token. 8. Roadmap 10. 9. Roadmap 11.
10. Build the office in a copy of the Hardcore world first; soak; then the real one (fresh backup each time).

**Phase 3: as needed after real use**
11. Roadmap 12 (display upgrades). 12. Roadmap 6 (coordination extras, as a separate MCP server).

**Throughout:** open upstream PRs early, merge `upstream/main` weekly, topic branch per item.

## Roadmap (ordered)

### Foreman (orchestration)

1. ✅ **Plan per goal** (`foreman/plan-per-goal`) — `prompts.ts` `planText()` picks the newest shared "Plan:" memory across all
   goals/repos; scope it to the job's goal. *(upstream PR)*
2. ✅ **Per-repo config** (`foreman/repo-settings`; config.json `repoSettings` with `ci`, `setup`, `copy`) — CI command, setup command run after worktree creation (e.g. `pnpm install`,
   copy `.env`), detect pnpm/monorepo test commands. Files: `config.ts`, `repos.ts`
   (`detectTestCommand`, `createWorktree`, `runTests`), `agents/claude/index.ts`. *(upstream PR)*
3. ✅ **Rate-limit aware scheduling** (`foreman/rate-limits`; `getUsage()` windows in the status banner still to do) — on `rate_limit_event` `rejected`: global pause until `resetsAt`,
   requeue instead of marking tasks blocked; throttle `maxConcurrent` on `allowed_warning`; surface
   `getUsage()` windows in `foreman.status`. Files: `agents/claude/stream.ts`, `index.ts`
   (`schedule`/`pump`/`afterTurn`). *(upstream PR)*
4. **Curated Claude Code context** — config-driven: inject repo `CLAUDE.md`/`AGENTS.md` plus an
   optional personal instructions file into `systemPrompt.append`; selected skills via SDK
   `plugins`/`skills` (remove `Skill` from `policy.ts` `DENIED_TOOLS` when configured); allowlisted
   extra MCP servers (policy rule so they don't ask every call). Files: `index.ts` `runJob`,
   `config.ts`, `policy.ts`.
5. **Roles** — per-agent role prompt, model and effort from config/cast; cast descriptions reach the
   prompts; lead may set `model` on `create_task` for cheap tasks. Files: `cast.ts`, `config.ts`,
   `prompts.ts`, `tools.ts`, `index.ts`.
6. **Coordination extras, only as needed after real use** — task specs/artifacts, ADRs, merge
   notifications to active workers, repo map. Prefer a separate MCP server.

### Mod (Minecraft)

7. **Safe defaults for normal play** — *branch `mod/safe-defaults` (compiles; not yet run in game).*
   `ClientEnv` defaults follow `FabricLoader.isDevelopmentEnvironment()`: unchanged under
   `runClient`, and in a packaged jar AutoWorld/DevBridge/mute are off and focus is on. The forced
   `pauseOnLostFocus = false` is dev-only. Still to do: a DevBridge token.
8. **Guard world-changing commands** — *same branch.* `/agentcraft hq` is refused outside the HQ world
   (opt in with `-Dagentcraft.hq.anyworld=1`) and always in Hardcore. `HqWorld.isHq` is never true for
   a Hardcore world. The DevBridge refuses every request except `dev.help` while a Hardcore world is
   loaded.
9. **Any-world layout** — load anchors in any world (`Anchors.java`), player commands to set
   anchors/bounds and bind station blocks (`StationBlockEntity.setBinding` exists), saved via
   `Anchors.publish`.
10. **Survival-obtainable blocks** — recipes + loot tables under `data/agentcraft/` (none exist).
11. **Iris compatibility** for the custom pipelines (`DisplayDraw`, `WorldUi`).
12. **More useful displays** — usage/model/context badges, branch/dependency view, repo ↔ building.

### Hardcore 26.3 instance

- Clone the 26.2 instance into a new 26.3 instance, fresh world backup first.
- All 29 mods have 26.3 builds (checked 2026-10-03). Exceptions to the stable-only rule:
  **Sodium is alpha-only on 26.3** (`mc26.3-0.9.3-alpha.1`), Visuality is beta.
- Soak the clone (no AgentCraft) before trusting it; only then add AgentCraft (after items 7–8).
