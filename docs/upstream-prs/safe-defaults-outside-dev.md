# Mod: safe defaults outside dev runs; guard world-changing commands

Branch: `upstream/safe-defaults-outside-dev` (one commit on `upstream/main`)

## Problem

The README says a regular mod release for normal launchers is planned. Today, though, the client's
defaults assume `gradlew runClient`. A built jar dropped into a normal launcher (the vanilla
launcher, Prism, ...) would on every start:

- create or load the "AgentCraft HQ" world on startup instead of showing the title screen
  (`AGENTCRAFT_AUTOWORLD` defaults to 1);
- set master and music volume to 0 (`AGENTCRAFT_MUTE` defaults to 1);
- open its window without focus (`AGENTCRAFT_FOCUS` defaults to 0);
- start the DevBridge, a localhost WebSocket that can run any command with full permissions and
  switch game modes (`AGENTCRAFT_DEV` defaults to 1);
- force `pauseOnLostFocus = false`, so a survival game keeps running while the player is alt-tabbed.

Two world-changing paths also have no guard:

- `/agentcraft hq` rewrites a large area of terrain and moves the world spawn in whatever world it
  runs in.
- `HqWorld.isHq` (switches players to creative, rewrites game rules) matches on the level name only,
  so it could match a Hardcore world with that name.

## Fix

`ClientEnv`:

- `DEV_RUN = FabricLoader.getInstance().isDevelopmentEnvironment()`.
- `AGENTCRAFT_DEV`, `_MUTE` and `_AUTOWORLD` default to `DEV_RUN`, and `AGENTCRAFT_FOCUS` to
  `!DEV_RUN`. `runClient` behaves exactly as before; a packaged jar keeps all four off (focus on).
- Each switch can still be set explicitly, as before.
- The forced `pauseOnLostFocus = false` now applies to dev runs only.

`/agentcraft hq` and Hardcore worlds:

- `/agentcraft hq` refuses outside the AgentCraft HQ world, with a message naming the opt-in
  (`-Dagentcraft.hq.anyworld=1` / `AGENTCRAFT_HQ_ANYWORLD=1`). It always refuses in a Hardcore world.
- `HqWorld.isHq` is never true for a Hardcore world.
- The DevBridge refuses every request except `dev.help` while a Hardcore world is loaded.

`mod/DEV.md`: the env-switch table shows the dev-run and jar defaults, and the HQ section describes
the new refusals.

The launchers (`tools/launch.ps1`, `tools/mac.mjs`) run `runClient`, so dev, QA and recording flows
are unchanged. They build in the auto-created "AgentCraft HQ" world, where `/agentcraft hq` still
works.

## How tested

- `cd mod && ./gradlew build` passes.
- Downstream, the same change has been in use. There, `runClient` dev and QA runs work as before, and
  the packaged jar starts in a regular launcher instance at the title screen.
- Not run on this branch in the game. Suggested check with the built jar in a normal launcher:
  - no HQ world is created;
  - the volume is untouched;
  - `pauseOnLostFocus` follows the player's options;
  - nothing listens on 7879;
  - `/agentcraft hq` is refused in a normal world;
  - under `runClient` everything is as before.
