# Mod: keep agents walking when another mod skips their entity tick

Branch: `upstream/agent-tick-catch-up` (one commit on `upstream/main`)

## Problem

Agents are client-only entities. They move only in `ClientAgentEntity.tick()`, which advances
`AgentMotion`, the walk animation, `AgentLife` and the `AgentHooks` tickers. Some performance mods
skip ticking client entities the player can't see. The best known is Entity Culling (tr7zw), whose
`tickCulling` option is on by default. With it installed, an agent that walks out of view stops
mid-route and stays frozen until the player looks at it again. Tickers that hang off the entity tick
stop too.

## Fix

- `AgentManager.tick` (END_CLIENT_TICK runs after the level's entity ticks) now gives every agent a
  catch-up advance first. It uses the same per-entity filters as `ClientLevel.tickEntities`: not while
  paused, removed, a passenger or tick-frozen.
- A new pure class `TickGate` runs the advance at most once per client tick. Both paths claim the
  same tick number from `AgentManager.clock()`, so an agent advances once per tick whether its entity
  tick ran or not, never twice.
- The catch-up calls `setOldPosAndRot()` first, as `commonTick()` does, so render interpolation stays
  right.
- The only thing not caught up is `tickCount` (the clock behind `AgentRenderer`'s `timeSeconds`), and
  only while the agent is out of view.
- Entity Culling is not a dependency and is not touched at runtime. No configuration is needed. Before
  this change, the workaround was adding `agentcraft:agent` to Entity Culling's
  `tickCullingWhitelist`.
- New DevBridge command `dev.agents.freezeEntityTick {on?}`. It skips every agent's entity tick to
  emulate tick culling without the other mod, and reports `{on, entityAdvances, catchUpAdvances,
  moving}`.
- Docs: `mod/DEV.md` has a new "Compatibility: entity tick culling (Entity Culling)" note and a table
  row for the new command. `mod/FEATURES.md` gets one paragraph.

## How tested

- `cd mod && ./gradlew build` passes.
- Upstream has no mod unit-test setup, so `TickGate` was checked with a standalone harness (not part
  of this PR). The harness covers three cases:
  - a tick is claimed once;
  - with the entity tick running, the catch-up never advances (10 of 10 by the entity tick);
  - with two of three entity ticks skipped, every tick still advances exactly once (6 by the entity
    tick, 4 by the catch-up).
- Downstream, with a modpack that includes Entity Culling: before this change, a scripted in-game
  check failed because an agent walking out of view stopped mid-route. With the same change in place,
  that check passed.
- Not run on this branch in the game. To check it in the dev client:
  1. Run `dev.agents.freezeEntityTick {on:true}`.
  2. Give an agent work so it walks to a desk.
  3. Confirm that it arrives, that `entityAdvances` stays flat, and that `catchUpAdvances` grows by
     about 20 per agent per second.

## Notes

`upstream/client-crash-guards` also touches `AgentsFeature.java` (it wraps the same tick
registration). The two PRs change different lines and merge cleanly in either order.
