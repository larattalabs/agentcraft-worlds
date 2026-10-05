# Mod: crash guards for client tick, render and HUD handlers

Branch: `upstream/client-crash-guards` (one commit on `upstream/main`)

## Problem

An exception thrown out of a Fabric `END_CLIENT_TICK`, level-render/extraction or HUD element callback
is not caught by the game. It ends in a crash report. In singleplayer that takes down the integrated
server with the client, so the session ends mid-play, and the world is only as saved as its last
autosave.

AgentCraft's handlers depend on Foreman state that arrives asynchronously: snapshots, layouts,
decisions. One unexpected null or a state race in any of them crashes the game. The agent entity
already guards its own life and tickers (`ClientAgentEntity.tick`). The feature-level handlers do
not.

## Fix

A small client utility, `client/ui/Guard`:

- `Guard.run(kind, runnable)` and `Guard.call(kind, supplier, fallback)` catch any `Throwable` except
  `VirtualMachineError`.
- The first failure per kind is logged with its stack trace. After that, a one-line count is logged
  at 10, 100, 1000, ... failures, so a failure that repeats every tick doesn't flood the log.
- The game keeps running, and the handler runs again on the next tick or frame.

`GuardedHud.of(kind, element)` wraps a `HudElement` the same way.

Wrapped:

- END_CLIENT_TICK in `AgentsFeature` (agent manager), `HqClientFeature` (HQ driver and ambience),
  `DecisionsFeature` and `ConsoleFeature` (key handling), and the `MonitorFeature` / `TaskWallFeature`
  cache sweeps;
- `LevelExtractionEvents.END_EXTRACTION`: the nameplate layout;
- `LevelRenderEvents.BEFORE_BLOCK_OUTLINE`: the task-card outline (fallback `true`, which keeps the
  vanilla outline);
- the three HUD elements: connection banner, goal bar, toasts.

No behaviour changes when nothing throws.

## How tested

- `cd mod && ./gradlew build` passes.
- Downstream, the same guards wrap the same handlers, and a DevBridge fault-injection hook exercises
  them. That hook is not part of this PR, to keep it small; it can be added if wanted.
- Not run on this branch in the game. To check it: throw from any wrapped handler (for example the
  console tick) in the dev client. The log should show one error with the stack trace, and the game
  should keep running.

## Notes

`upstream/agent-tick-catch-up` also touches `AgentsFeature.java`. The two PRs change different
lines and merge cleanly in either order.
