# AgentCraft mod: developer notes

Fabric mod `agentcraft` (package `dev.agentcraft`). Common entrypoint `dev.agentcraft.AgentCraft`,
client entrypoint `dev.agentcraft.client.AgentCraftClient`. Loom's split source sets are used:
`src/main` (both sides) and `src/client` (client only).

## Versions, and why

| Thing | Version |
|---|---|
| Minecraft | **26.3** (stable, released 2026-09-15) |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.161.0+26.3 |
| Loom | 1.18.2 (`net.fabricmc.fabric-loom`, the non-remapping plugin), pinned to a release instead of the template's `1.18-SNAPSHOT` |
| Gradle | 9.7.1 (wrapper from the official template) |
| Java | 25 (runtime and `--release 25`) |
| DevBridge WebSocket | org.java-websocket:Java-WebSocket 1.6.0 (nested with jar-in-jar; slf4j comes from Minecraft) |

The spec said 1.21.x. **26.3 was chosen instead** for these reasons:
- It is the newest stable release, with an actively maintained Fabric API, and it is the default branch
  of the official `FabricMC/fabric-example-mod` template.
- From 26.1 on, Minecraft is **unobfuscated**. There is no mapping layer and no remapping (so builds
  are faster), and the decompiled sources use Mojang's real names. For agents this is the biggest win:
  every API can be checked by grepping the real sources (see "Finding Minecraft APIs").
- 26.x requires Java 25, which is the JDK installed here. 1.21.11 would have targeted Java 21.

The cost is that many APIs changed after 1.21. Do **not** code from 1.21-era memory. Check the
26.3 sources. Changes that matter to this project:
- **Windowing and input use SDL3, not GLFW** (`com.mojang.blaze3d.platform.Window`, `SDLHints`).
  Key codes are SDL scancodes (`InputConstants.KEY_ESCAPE == 41`).
- Rendering is split into extract and render passes (`GameRenderer.extract/render`, `*RenderState`).
  Screens are drawn through `GuiGraphicsExtractor`. Backends live under `com.mojang.renderpearl`.
  OpenGL is the default; Vulkan is optional (options key `preferredGraphicsBackend`).
- Screens: `mc.gui.setScreen(..)` and `mc.gui.screen()` (no longer on `Minecraft`). The HUD is `mc.gui.hud`
  (`isHidden()` and `toggle()` replace `options.hideGui`).
- Game rules are snake_case registry entries (`GameRules.ADVANCE_TIME`, `KEEP_INVENTORY`, ...).
- Day time comes from world clocks: `level.getDefaultClockTime()`. `/time set` still works.
- `Identifier` replaces `ResourceLocation`. Permissions use `PermissionSet` and `LevelBasedPermissionSet.OWNER`.
- **Resource pack format is 97.1, data pack format is 121** (from the jar's `version.json`). The art
  track should target these.

## Build and run

Always isolate Gradle:

```bash
cd mod
GRADLE_USER_HOME=C:/Projects/agentcraft/.gradle-home ./gradlew build        # jar -> mod/build/libs/agentcraft-0.1.0.jar
GRADLE_USER_HOME=C:/Projects/agentcraft/.gradle-home ./gradlew runClient    # dev client
GRADLE_USER_HOME=C:/Projects/agentcraft/.gradle-home ./gradlew --stop       # stop OUR daemons only
```
(PowerShell: `$env:GRADLE_USER_HOME='C:\Projects\agentcraft\.gradle-home'; .\gradlew.bat runClient`.)

`runClient` starts with **no clicks**:
1. `prepareRunDir` copies `run-template/options.txt` to `mod/run/options.txt`, but only if that file
   does not exist yet. The template sets: master and music volume 0, `pauseOnLostFocus:false`,
   `guiScale:3` (1080p), render distance 16, chunk fade-in off (so screenshots are never half-faded),
   no tutorial, accessibility onboarding or narrator, no Realms notifications, and the
   inactivity FPS limit set to "minimized" only. Delete `mod/run/options.txt` to reset it.
2. Program args: `--username <you> --width 1920 --height 1080`, where `<you>` is `AGENTCRAFT_PLAYER`
   or else your OS user name (letters, digits and `_`, at most 16 characters).
3. **AutoWorld** (client) runs the first time the title screen appears. It loads the world folder
   `AgentCraft HQ` if it exists, and otherwise creates it: creative, peaceful, commands allowed, no
   structures, no bonus chest, superflat plains meadow (bedrock / 124 stone / 3 dirt / grass), so
   **the grass top is y=64 and you stand at y=65**. World spawn is 0 65 0.
4. **HqWorld** (server side, HQ world only) re-applies the game rules on every start: no time or
   weather cycle, keep inventory, no fire spread, no mob spawning of any kind, no mob griefing, no vine
   spread, no snow build-up, respawn radius 0, no locator bar, quiet advancement and command output,
   and max_block_modifications 1,000,000 so large /fill commands work for the HQ builder. On first
   creation it also sets time 12000 (golden hour), clear weather and the world spawn. It writes a
   marker file `agentcraft-world.json` in the world folder. Players who join in spectator or survival
   are put back into creative.

Delete `mod/run/saves/AgentCraft HQ` to start over with a fresh world. For docs or QA on natural terrain
(welcome card, walking routes): `node tools/mac.mjs launch --backend sim --dev --world "Docs World" --preset
normal [--seed N]` (env switches `AGENTCRAFT_AUTOWORLD_NAME/_PRESET/_SEED` below; tools/README.md).

### Environment switches (env var, or `-Dagentcraft.xxx=` system property)

| Var | Default | Effect |
|---|---|---|
| `AGENTCRAFT_DEV_PORT` | 7879 | DevBridge port (always bound to 127.0.0.1) |
| `AGENTCRAFT_DEV_TOKEN` | random | DevBridge shared secret. Default: 32 random bytes (hex) per start, written owner-only (0600) to `<gameDir>/agentcraft/devbridge.token` (dev run: `mod/run/agentcraft/devbridge.token`) |
| `AGENTCRAFT_DEV` | 1 | `0` disables the DevBridge |
| `AGENTCRAFT_MUTE` | 1 | Forces master and music volume to 0 at startup. **Set `0` for real use** (for example in launch.ps1) to keep your own volume |
| `AGENTCRAFT_FOCUS` | 0 | `0`: the window is shown **without activating it**, so it never steals focus. `1`: normal "come to front" |
| `AGENTCRAFT_AUTOWORLD` | 1 | `0`: stay on the title screen |
| `AGENTCRAFT_AUTOWORLD_NAME` | `AgentCraft HQ` | The world (save folder and level name) AutoWorld loads, or creates if missing. Only `AgentCraft HQ` gets the HQ rules/studio (`HqWorld.isHq` is by name), so another name is a plain creative world (welcome card, no studio). `tools/mac.mjs --world NAME` |
| `AGENTCRAFT_AUTOWORLD_PRESET` | `flat` | Terrain of a **new** world: `flat` = the superflat meadow below, `normal` = natural terrain (creative, peaceful, cheats on, no structures). `--preset` |
| `AGENTCRAFT_AUTOWORLD_SEED` | flat: `"agentcraft-hq".hashCode()`, normal: `2026` | Seed of a new world (a number, or text hashed like the vanilla box). 2026 spawns in a birch meadow on a hill (y~118) with forest, lakes and a cherry grove within ~150 blocks. `--seed N`. Parsed by the pure `dev.agentcraft.world.AutoWorldSpec` (`AutoWorldSpecTest`) |
| `AGENTCRAFT_PAUSE` | (dev run or DevBridge: 0, else 1) | Whether AgentCraft screens pause a singleplayer game (contract C6). Everyday play pauses like vanilla menus; dev runs and clients with the DevBridge on keep the world running for QA. `dev.ui.pause {on}` changes it at runtime |
| `AGENTCRAFT_SHOTS_DIR` | `<repo>/artifacts/shots` | Where `dev.screenshot` writes |
| `AGENTCRAFT_DEV_ALLOW_ORIGIN` | 0 | `1` lets browser pages (which send an Origin header) connect. They are refused by default |
| `AGENTCRAFT_DEV_TEST` | 0 | `1` registers test-only commands (`dev.test.stall`, which blocks the render thread to simulate a hung game; `dev.test.foremanMessage`). Never set it for real use |
| `AGENTCRAFT_PORT` | 7878 | Foreman WebSocket port the mod connects to (always 127.0.0.1) |
| `AGENTCRAFT_FOREMAN` | 1 | `0` disables the Foreman link (the HUD says so) |
| `AGENTCRAFT_WELCOME` | 1 | `0`: the welcome card never opens by itself on joining a world without buildings (scripted QA worlds); `dev.onboarding {show}` still opens it |

The defaults (muted, no focus) suit unattended agent runs. `tools/launch.ps1` should set
`AGENTCRAFT_MUTE=0 AGENTCRAFT_FOCUS=1` for real use (when you launch the game yourself; it does
without `-Dev`). The name the agents call you comes from the Foreman (`--user-name`, see
foreman/README.md) and reaches the mod in `foreman.status`.

### Focus behaviour (what was verified)
`RenderSystemMixin` sets the SDL hints `SDL_WINDOW_ACTIVATE_WHEN_SHOWN=0`,
`SDL_WINDOW_ACTIVATE_WHEN_RAISED=0` and `SDL_FORCE_RAISEWINDOW=0` before SDL starts. SDL3 then shows
the window with `SWP_NOACTIVATE`. Verified on Windows 11: after launch, `GetForegroundWindow()` was
still the previously active app. `dev.state` reports `window.osForeground:false`, read through a
read-only user32 call using Java FFM.

Caveat: **SDL's own flag (`window.focused`) still says `true`.** The window can still appear on top
of other windows; it just isn't activated. Window *placement* is untouched.

## DevBridge (dev control WebSocket)

A WebSocket server **inside the client**, listening on `ws://127.0.0.1:${AGENTCRAFT_DEV_PORT:-7879}`. It
starts at `CLIENT_STARTED`, before the world loads, so `dev.ping` works during loading. It stops at
`CLIENT_STOPPING` and uses daemon threads. If the port is busy it logs an error and the game keeps running.

**Authentication.** Every connection must present a shared secret during the WebSocket handshake, either
`Authorization: Bearer <token>` or a `token` query parameter (`ws://127.0.0.1:7879/?token=<token>`);
otherwise the handshake is refused (checked in constant time; browser `Origin` headers are still refused).
On start the bridge uses `AGENTCRAFT_DEV_TOKEN` (or `-Dagentcraft.dev.token`) if set, else generates 32 random
bytes (hex), and writes it owner-only to `<gameDir>/agentcraft/devbridge.token`. The log names the file, never
the token. The dev tools (`tools/lib/devclient.mjs`) take `AGENTCRAFT_DEV_TOKEN` or read `mod/run/agentcraft/devbridge.token`
(override the game dir with `AGENTCRAFT_GAME_DIR`). For other clients: `curl`-style one-offs can use
`TOKEN=$(cat mod/run/agentcraft/devbridge.token)`.

**Protocol.** Each frame carries one JSON object (text frames; binary frames holding UTF-8 JSON are
treated the same, other binary frames get an `ok:false` reply).
- Request: `{"id":"7","type":"dev.camera", ...fields}`. `id`, `type` and `timeoutMs` are reserved
  names, so never use them as payload field names.
  - `id` (optional) is a string or a number and is echoed back **exactly as sent** (`7` stays a number).
    Any other id (object, array, bool) gets an `ok:false` reply that still carries the id.
  - `type` must be a JSON string (`["dev.state"]` or `5` are refused).
  - `timeoutMs` (optional, integer 1..3600000) overrides the command's server-side timeout.
- Reply: `{"id":"7","type":"dev.camera","ok":true, ...result}` or `{"id":"7","type":"dev.camera","ok":false,"error":"..."}`.
  Replies always include null-valued fields (for example `dev.state` `screen: null`, `foreman: null`).
- **Fields are strictly typed** and errors name the field: numbers must be JSON numbers and finite (the
  strings `"NaN"`/`"12"`, bare `NaN`/`Infinity` and overflowing `1e400` are refused), integers must be whole,
  booleans must be `true`/`false` (the string `"false"` is refused), and every number has a range. Example:
  `field 'ticks' must be an integer (got string "noon")`, `field 'lookAt.x' must be a finite number (got string "NaN")`.
- A timeout while the render thread is stuck carries `stalled:true` and says so in `error`.
- On connect the server sends `{"type":"dev.hello","protocol":1,"minecraft":"26.3","inWorld":bool}`.
- Requests may be in flight at the same time; match replies by `id`. All game work is done on the
  render thread (`mc.execute` or the end-of-frame scheduler) or on the integrated server thread
  (`server.submit`), and the reply is sent asynchronously. Every handler error becomes an
  `ok:false` reply; nothing is thrown into the game. Unexpected exceptions are reported as
  `internal error: <exception>` and logged with a stack.
- Connections that send an `Origin` header (browsers) are refused with HTTP 404.

| Command | Fields | Result / notes |
|---|---|---|
| `dev.ping` | (none) | `{pong, frame, msSinceLastFrame, stalled, quitting}`. Answered on the socket thread, so it answers while the game loads **and while it is hung**. `stalled:true` = the render thread has not finished a frame for 5 s (QA: relaunch) |
| `dev.help` | (none) | All commands with help text, plus registered screens |
| `dev.state` | (none) | `inWorld`, **`ready`** (in a world with no loading screen or overlay: safe to shoot), `paused` (a pausing screen is open, so the integrated server is stopped), `screen{class,title}` or null, `fps`, `frame`, `window{width,height,framebufferWidth/Height,renderWidth/Height,guiScale,focused,osForeground,iconified}`, `hudHidden`, `fov` (what the last frame was rendered with), `fovOption` (the player's setting), `fovPin` (dev camera pin, null = none), `cameraType`, `audio{master,music}`, `player{name,x,y,z,eyeY,yaw,pitch,flying,gameMode}`, `camera{x,y,z,yaw,pitch,fov}`, `world{name,dimension,time,raining,thundering}`, `chunks{renderedAll,lightQueue,loadedAll,renderDistance}` (player/camera/world/chunks are null outside a world), `foreman{link, connected, url, attempt, lastError, phaseForMs, everSynced, snapshots, messages, lastMessageAgoMs, stale, backend, auth, message, version, counts{agents, activeAgents, tasks, openTasks, decisions, openDecisions, repos, memory, goals, feed}, goal?, oldestOpenDecision}`, `agents{count, moving, pathFailures, plates, plateOverlaps, plateLayoutUs}` (plates = nameplates laid out last frame, plateOverlaps = pairs of drawn plates overlapping on screen last frame, 0 when settled; plateLayoutUs = mean cost of the declutter pass), `ui{screensPause, screen, screenPauses, gamePaused, parent, guards{kind: failures}}` (C6 pausing, the open screen's parent = where Esc returns, crash-guard counts) |
| `dev.camera` | `anchor?` (fills x/y/z/yaw/pitch from the published layout, see `dev.anchors`; `cam_*` anchors are eye positions, other anchors feet positions; explicit fields win), `x,y,z` + (`yaw,pitch` **or** `lookAt:{x,y,z}`, lookAt wins), `fov?` (30-110, may be fractional; **default: the player's FOV option**), `mode?` = `spectator` (default) / `creative` (flying) / `keep`, `feet?` (default false: x,y,z is the **eye** position), `hideHud?`, `closePause?` (default true: closes a vanilla pause menu first) | Validates first: all numbers finite; `pitch` in [-90, 90]; `yaw` any finite value (wrapped to [-180, 180)); `y` in [-20000000, 19999999] (vanilla `/tp`'s limit); x/z inside the **world border** (±29999984); lookAt not equal to the eye. Then forces first person, stops spectating other entities, teleports on the server thread, waits until the client has the exact position, pins position and rotation with no interpolation, and **only replies ok once a rendered frame used exactly the requested eye position (±0.01), rotation (±0.05°) and FOV**; otherwise `ok:false` with wanted vs got (`mode:keep` skips that check, since walking players fall). Returns the actual `camera{x,y,z,yaw,pitch,fov}`. Yaw: 0 = +Z (south), 90 = -X (west), -90 = +X (east). Pitch: positive looks down. **FOV pin:** each call renders with exactly its `fov` (or the option), ignoring vanilla's dynamic FOV (flying widens it by 1.1x, so before this pin a "70" shot really rendered at 77). Nothing carries over between calls and `options.txt` is never touched |
| `dev.release` | `mode?` = `creative` (default) / `keep` | Hands the view back to the player: clears the FOV pin (vanilla FOV again), shows the HUD, spectator -> creative (flying, so you don't fall) |
| `dev.screenshot` | `name` (letters, digits, `_ - . /`; `.png` added), `hideHud?` (default true), `frames?` (3, 1-600), `waitChunks?` (true), `chunkRadius?` (whole render distance, 0-64), `chunkTimeoutMs?` (30000, 0-600000) | Hides the HUD, waits until all chunks within the render distance are loaded and meshed and the light queue is empty for 5 consecutive frames, waits N more frames, then copies the **main render target** (the framebuffer, so it doesn't depend on window focus or overlap). Writes the PNG and restores the HUD. Returns `{path, width, height, ms, chunksTimedOut, paused, stats{meanLuma,stdLuma,darkFraction}}`. Use the stats to catch black frames. Open screens are included in the capture. `width`/`height` are **not supported** (the shot is the window framebuffer size, 1920x1080). The default request timeout grows with `chunkTimeoutMs` and `frames` |
| `dev.time` | `ticks` (integer 0..2147483647) | `/time set`. 6000 noon, 12000 golden hour (the HQ default), 18000 night, 23300 sunrise |
| `dev.weather` | `weather: clear/rain/thunder` or `clear: bool` | Rain fades in over several seconds (vanilla behaviour) |
| `dev.command` | `cmd` (non-empty string, leading `/` optional) | Runs as the player with owner permissions on the integrated server. Returns `{cmd, messages[], success, result}` (`result` null when the command did not parse). Feedback is captured, not shown in chat. An idempotent `/fill` reports `success:false` with "No blocks were filled" |
| `dev.screen` | `open: name` or `null` (absent = null) | Built in: `title`, `pause`, `chat`, `inventory`, `options`, plus any registered screens. `null` closes the screen (on the title screen it stays on title). Returns the resulting `screen` class or null |
| `dev.key` | `key` (`escape`, `key.keyboard.f3`, ...) + `modifiers?` (0-65535), or `mapping` (`key.chat`) | Sent to the open screen's `keyPressed`, otherwise as a key-mapping click |
| `dev.type` | `text` (up to 100000 chars) | `charTyped` into the open screen's focused widget |
| `dev.hud` | `hidden: bool` | Like F1 |
| `dev.waitChunks` | `timeoutMs?` (30000; also the request timeout), `radius?` (0-64) | Waits for chunks to load and build (same condition as screenshots) |
| `dev.wait` | `frames?` (0-36000), `ms?` (0-600000) | Waits for rendered frames and/or wall time; the default request timeout grows with both |
| `dev.quit` | `forceAfterMs?` (15000, 1000-600000) | Replies `{quitting, alreadyQuitting, renderThreadStalled, forceAfterMs}`, then calls `mc.stop()` 250 ms later: the world saves and the JVM exits (about 1-2 s). **Watchdog:** if the render thread is hung and has not run the stop after `forceAfterMs`, it logs the render thread's stack, stops the integrated server (which saves the world), then halts the JVM with **exit code 3** (`runClient` then ends with BUILD FAILED). If a normal shutdown is still running after 90 s it does the same with exit code 4 |
| `dev.test.stall` | `ms` (1-600000) | **Test only** (`AGENTCRAFT_DEV_TEST=1`): blocks the render thread to simulate a hang |
| `dev.foreman` | `reconnect?` (false) | The Foreman link + model summary (same as `dev.state.foreman`); `reconnect:true` drops the connection and connects again now |
| `dev.foreman.send` | `message:{type, ...}` | Sends a client message (docs/protocol.md "Mod -> Foreman") through the mod's own link and replies `{ack:{re, ok, error?, result?}}`. Example: `{message:{type:"goal.submit", text:"Add #tags"}}`, `{message:{type:"decision.answer", decisionId:"d3", option:"Merge"}}` |
| `dev.foreman.inject` | exactly one of `message:{type,...}`, `patch:{agent\|task: id, set:{wire fields}}`, `say:{agent, text, to?}` | Applies to the mod's Foreman model **as if the Foreman had sent it** (always available; bypasses the hold queue). `patch` copies the current agent/task, replaces the given wire fields (`{"station":"desk","state":"editing"}`, `{"status":"doing","assignee":"marlow"}`, `null` clears) and applies it as an `agent.upsert`/`task.upsert`; `say` is an `agent.say` stamped now. Replies `{applied, message}`. Video choreography; the next snapshot (reconnect) undoes it |
| `dev.foreman.hold` | `on` (bool), `release?` = `reconnect` (default) / `replay` / `drop` | `on:true`: live Foreman messages are queued instead of applied, so a shot shows only what it injects. `on:false` releases them: `reconnect` drops the queue and reconnects (fresh snapshot), `replay` applies the queue, `drop` discards it. `dev.state.foreman.held/heldQueued` show it |
| `dev.play` | `duration` (s), `camera`, `timeline?`, `name?`, `showHud?` (false), `holdEndMs?` (300), `foreman?:{hold?, release?}`, `log?` (true) | **Real-time shot playback** for a screen recorder (OBS). See "Shot playback" below. Replies when the shot is done: `{frames, resolution, perf{fps, frameMsMedian/P99/Max, framesOver20ms, pathStepMsMin/Max}, cameraVsPath{maxPosError, maxRotError, maxFovError}, events[{t, at, event, ok, error?}], warnings, frameLog}` |
| `dev.play.pose` | `camera`, `t?` (0), `duration?` | Where a camera path is at time t: `{pose{x,y,z,yaw,pitch,fov}, start, end}` (eye position). `record.mjs` puts the camera there with `dev.camera` before playing; also handy to preview a path with stills |
| `dev.play.status` / `dev.play.stop` | | Progress of the playing shot / stop it (its `dev.play` replies `ok:false`) |
| `dev.window` | `width`, `height` | Resizes the game window (windowed mode, `Window.setWindowed`), e.g. 2560x1440 to fill the monitor for a capture; replies once a frame was rendered at the new size `{width, height, framebufferWidth/Height, renderWidth/Height}` |
| `dev.agents` | `settle?` (false) | Every agent NPC: `id, entityId, x,y,z, yaw, station, anchor, target{x,y,z,yaw}, walking, path[[x,y,z]...], state, activity, stale, model, skin`, and `plate{mode full\|compact, lift, target, rank, nudge, scale, depth, weight, focused, capped, rect[x0,y0,x1,y1] in screen px}` when its nameplate was laid out last frame; top level also has `plates, plateOverlaps`; `dev.state.agents` also has `plateOverlapPairs` ("rowan/wren": which plates overlapped last frame) and `exclaims`. `settle:true` snaps walking agents to their targets and the nameplates to their final layout on the next frame (no one mid-walk, no plate mid-slide in a shot) |
| `dev.anchors` | `prefix?` | The published layout: `{layout, revision, bounds, anchors:{name:{x,y,z,yaw,pitch}}, count}` |
| `dev.agents.look` | `agent?` | Agent life per agent: `{id, family, awaitingUser, awaitingDecision, needsYou, paused, posture, seated, sit, seat{x,z,top,drop,deskTop}?, bodyYaw, headYaw, headPitch, bubble, particles}`; top level `exclaims` (agents showing the "!"), `card{agent, input}` while an agent card is open (`input` = its message line, null when closed), `textInputActive` (SDL text input on: typed characters are delivered) |
| `dev.agents.card` | `agent`, `press?` = `message`/`pause`/`stop`/`review`, `answer?` = `opt:<option>`/`send`/`diff`/`cancel`, `text?` | Opens the agent card for that agent (like an empty-hand sneak + right-click), or presses a button on its open card (Stop needs two presses: `stopArmed`); `answer` drives the card's AnswerPanel for the decision it waits on (Merge / Reject need two calls; no arm delay through the DevBridge); returns `stopArmed`, `status`, `review` (the decision), `panel` (AnswerPanel state), `screen` (after `review`: the decision's screen) |
| `dev.player.sneak` | `on` (bool) | Holds (or releases) the sneak key mapping, like a held Shift; returns `sneaking`, `mainHandEmpty` (agents are targetable, `dev.agents.look` `pickable`, only both) |
| `dev.ui.pause` | `on?` (bool; omit = the environment's default) | Forces AgentCraft screens to pause (or not) in singleplayer for this session; returns the `ui` state |
| `dev.guard.inject` | `kind` (`agents.tick`, `agents.plates`, `hq.tick`, `wizard.tick`, `wizard.ghost`, `hub.tick`, `console.tick`, `decisions.tick`, `hud.toasts`, ...) | The next run of that guarded client handler throws: it must be logged once, counted in `dev.state` `ui.guards`, and the game keeps running |
| `dev.library.lectern` | `x`, `y`, `z` | Whether a right-click on the lectern there opens the library (`opensLibrary`, the `building` holding it) or is left to vanilla |
| `dev.team.card` | `agent` | The hub Team tab's Card button: the agent card with the hub as its parent (Esc returns to the hub) |
| `dev.team.release` | `world` | The Team tab's Release for a world holding leads (`lead.releaseWorld {world}`; the hub must be open); returns the note |
| `dev.agents.fx` | `agent`, `fx` = `confetti`/`puff`/`sparkle`/`say`, `text?`, `to?` | Plays an agent effect now (QA preview; `say` shows a local speech bubble, nothing is sent) |
| `dev.agents.keys` | `keys` (comma-separated: key names `space return escape back tab left right`, or text typed letter by letter, a-z 0-9 space) | **Test only** (`AGENTCRAFT_DEV_TEST=1`): presses keys as SDL reports a keyboard (SDL events queued for the game window, one key every 3 frames, through Minecraft's SDL event loop; printable keys produce text events only while SDL text input is on). Returns `{pressed, textEvents, screen, input?, textInputActive}`. `tools/agents-typing.mjs` uses it to check the agent card's message line |
| `dev.walk.state` | | Walking between buildings (W8): `enabled, world, walking, planning, trips[{agent, from, to, phase planning\|walking, length, ticks, limit, target, pos, remainingPoints}], jobs, cache{size, hits, misses, invalidations, blockChanges, routes[{key, length, points, cells, nodes, micros}]}, planner{plans, found, tickNodes, tickBudgetUs, lastTickUs, maxTickUs, last{key, status, nodes, micros, ticks, length, points, unloadedHits}}, reasons{walk\|disabled\|other_dimension\|no_entrance\|too_far\|unloaded\|player_far\|no_path\|no_door_path\|budget\|blocked\|stuck\|rerouted\|settled: count}, recent[{agent, from, to, outcome walk\|teleport, reason, why, length?}], ui{drawn, needed, available, overflow, compact}` (also `dev.state.walk`) |
| `dev.walk.plan` | `from`, `to` (building id or `home`), `fresh?` (false), `show?` (true) | Plans entrance to entrance with the agents' planner and cache (replies when the incremental job finishes): `{decision, key, status found\|no_path\|unloaded\|budget\|too_far\|no_start\|no_goal, cached, nodes, micros, ticks, length, cells, from, to, points[[x,y,z]]}` or `reason`; `show` draws the route with end-rod particles for 20 s (screenshots) |
| `dev.walk.send` | `agent`, `to` (building id, `home`, or null) | QA: routes that agent to that building regardless of its work (sticky until `to:null` or a level change), so it changes building by the normal rules (walks or teleports); returns `{agent, to, canHost, note?, sends}` (`canHost` false: the building has no desk, station or lounge for it, it stays home) |
| `dev.walk.toggle` | `on?` (bool; omit = flip) | "Agents walk between buildings" for this world (`walking.json`); returns `{enabled, world}` |
| `dev.test.foremanMessage` | `message:{type, ...}` | **Test only** (`AGENTCRAFT_DEV_TEST=1`): applies a Foreman message to the state model as if received (e.g. `foreman.status` with `auth:"failed"` to see the auth banner) |
| `dev.displays` | `look?` = `paper` / `dark` / `split`, `reset?` | Monitor look (default dark; split alternates per monitor for comparisons), every laid-out monitor screen `{pos, agent, mode, style, size, ppb, rows, ageMs}`, and `stats` = display CPU cost per frame since the last reset (`monitor`/`board`: `usPerFrame`, `callsPerFrame`, `rebuilds`) |
| `dev.taskwall` | `open?` (task id), `press?` (button id), `aim?` (task id), `board?` ("x y z" origin for `aim`), `lightFloor?` (0-15), `ppb?` (0-256, 0 = auto), `relayout?` | Task Wall boards and their cards (column counts, widths and cards per row, hidden ids, card positions, size full/brief/compact, title lines, state dot, glowing, `layoutUs` of the last re-plan). `lightFloor`/`ppb` override the block-light floor and the pixel density for A/B shots, `relayout` forces a re-plan. `open` opens that task's screen, `press` presses a button in the open task screen (`prev next retry prioritize reassign cancel to:<agent>`), `aim` returns the world point of a card and an eye 2.5 blocks in front (then `dev.camera` + `dev.key {mapping:"key.use"}` clicks it the real way; use `mode:"creative"`, spectators cannot click) |

Registered screens (`dev.screen {open}`): `creative_agentcraft` (creative inventory on the AgentCraft tab), `agent` (agent card: last clicked agent, else whoever needs you), `task` (task detail: the last task opened, else the first doing one), `hub`, `hub_<tab>`, `hub_blueprints`, `hub_designs`, `hub_goal_{thread,plan,instructions,tasks}`, `hub_settings_<group>`, `hub_repo_settings` (see "Hub"). Phase 3 features add theirs (see mod/FEATURES.md).

### Extending it from other mod code (client side)

```java
DevBridge.register("dev.foreman", 10_000, "{} -> foreman link status", (req, mc) -> {
    Fields f = Fields.of(req);                       // strict typed fields, errors name the field
    int limit = f.optInt("limit", 20, 1, 500);
    return DevBridge.onClient(mc, () -> { JsonObject o = new JsonObject(); /* ... */ return o; });
});
DevBridge.register("dev.slow", req -> Fields.of(req).optLong("ms", 0, 0, 60_000) + 10_000, "...", handler); // request-dependent timeout
DevBridge.registerScreen("console", mc -> new ConsoleScreen());   // dev.screen {open:"console"}
DevBridge.addStateContributor((mc, state) -> state.add("foreman", foremanStatusJson()));
FrameScheduler.afterFrames(2).thenRun(...);                        // end-of-frame callbacks (render thread)
FrameScheduler.when(() -> condition, minFrames, stableFrames, timeoutMs, "what");
FrameScheduler.msSinceLastFrame(); FrameScheduler.stalled();      // render-thread liveness, any thread
DevBridge.invoke("dev.screen", req);                               // run a registered command from mod code (dev.play timelines)
```
Register in `onInitializeClient`. Built-ins are registered when the bridge starts, so a later
`register` with the same name replaces a built-in. Throw `DevBridge.DevException` for user errors
(sent back verbatim); any other exception becomes `internal error: ...` and is logged.
`Fields` (`num`, `num(min,max)`, `optNum`, `integer`, `optInt`, `optLong`, `bool`, `optBool`, `str`,
`nonBlank`, `optStr`, `obj`, `optObj`) refuses NaN/Infinity, wrong JSON types and out-of-range values.

### Shot playback (`dev.play`, `tools/record.mjs`)

Plays a choreographed camera shot **in real time** so a screen recorder (OBS, 60 fps window
capture) films it. Code: `client.dev.play` (`ShotPlayer`, `CameraPath`, `Timeline`, `PlayCommands`);
hooks in `MinecraftMixin`; shot files and their format: `tools/shots/README.md`.

Per loop iteration, on the render thread:
1. `Minecraft.runTick` HEAD (before the client ticks): the first call starts the clock; every
   timeline event with `t <= elapsed` runs, so its effect is in this frame.
2. `Minecraft.renderFrame` HEAD (after the ticks): the path is sampled and the player is snapped
   there with `snapTo` (position **and previous position**, rotation **and previous rotation**), the
   camera's own smoothed eye height (`CameraAccessor`, lerped with the partial tick) is subtracted,
   and the FOV is pinned (`DevCamera`). `Camera.alignWithEntity` then lands exactly on the path:
   no tick interpolation, no jitter (measured: rendered camera vs path max error 0 blocks / 8e-6 deg).
3. `renderFrame` TAIL: frame timing and the rendered camera are logged; after the end pose was held
   for `holdEndMs` the shot ends, the HUD comes back and a Foreman hold is released.

**Which time a frame shows.** Frame *starts* jitter by about +-2 ms against vsync (ticks, packets,
GC); frame *ends* (right after present) are as regular as the display. Sampling the path at the
frame start gave uneven camera steps (14.8-18.6 ms of path time per 16.7 ms frame). The path is
now sampled at the predicted present time: previous frame end + the smoothed frame interval,
clamped to within one interval of the wall clock (a hitch still advances the camera by the real
time that passed). Path steps now follow the frame intervals (16.59-16.78 ms in a clean take).
t = 0 is the predicted present time of the shot's first frame.

**Camera paths** (`camera` field, also `dev.play.pose`):
- `keys`: keyframes `{t, x,y,z, yaw,pitch | lookAt:{x,y,z}, fov?, ease?}` or `{t, anchor}` (eye
  position; `cam_*` anchors are eyes, other anchors feet + 1.62). A time-based (non-uniform)
  Catmull-Rom spline (cubic Hermite, tangents `(v[i+1]-v[i-1])/(t[i+1]-t[i-1])`); `easeEnds`
  (default true) gives zero velocity at the first and last key. Yaw takes the shortest arc between
  keys. When every key has a `lookAt` (or the path has one) the look *target* is splined instead
  of yaw/pitch, so a subject stays centred. `ease` on a key (`inOut|in|out|linear`) remaps time
  inside the segment that starts there (e.g. a hold).
- `orbit`: `{center:{x,z}, radius, y, from, to, lookAt?, fov?, ease?:"inOut"}`: an exact arc at
  constant height; angles in degrees, 0 = south of the centre, growing like yaw (90 = west).

**Timeline** (`timeline` field): `{t, inject:{type,...}}`, `{t, patch:{agent|task, set}}`,
`{t, say:{agent, text, to?}}` (same as `dev.foreman.inject`), or `{t, cmd:"dev.xxx", ...fields}`
for any DevBridge command (`dev.screen`, `dev.key`, `dev.agents {settle}`, `dev.screenshot`...;
`dev.command` takes the server command in `command`). `{t, cmd:"dev.type", text, msPerChar?:75,
jitter?:0.3, seed?}` types one character at a time with a natural, seeded rhythm (longer pauses
after spaces/punctuation). An event that fails at once stops the shot (`ok:false`); slower
failures are reported in `events`. With a timeline, live Foreman messages are held during the shot
(`foreman.hold`, default on) and released by reconnecting (fresh snapshot, so the injected changes
are undone after the shot).

**Not deterministic** (real time): the client ticks (agent walks, sitting, bubbles), the integrated
server and the GPU run on their own clocks, so two takes differ by up to a tick (50 ms) in when an
agent arrives; server commands (`dev.command`) apply whenever the server thread gets to them;
particles are random. The camera itself is exact every frame. Shots therefore start agents walking
early enough (desk_story: the walk starts at 0.6 s, the camera arrives at 3.6 s) and never cut
on an agent's exact arrival.

Measured (RTX 4090, vsync on, window 2560x1440 via `dev.window`): all three example shots at 60 fps,
frame time median 16.68 ms, p99 <= 16.8 ms, 0 frames over 20 ms in the final takes (one earlier
orbit take had one 33.5 ms hitch followed by two 8.3 ms frames: the camera followed real time).

## Phase 2: the mod as a live view of the Foreman

Feature map and APIs for Phase 3: **mod/FEATURES.md**. This section records how it works and why.

```
common (src/main)                          client (src/client)
  AgentCraft          registries + init      AgentCraftClient -> ClientFeatures (one init() per feature)
  block/              16 blocks, BEs, items  foreman/   link (java.net.http WS), Protocol records, ForemanState, Foreman facade
  entity/             agent entity type      agents/    AgentManager, AgentMotion, GridPathfinder, AgentRenderer, Nameplate, hooks
  layout/             Anchors + names        hud/       ConnectionBanner        ui/  UiStyle, Kit, Panels, WorldUi, TextUtil
  hq/                 /agentcraft hq builder world/     StationRenderer base, ServerTasks, StationInteractions, dev helpers
  command/            /agentcraft root       monitor/ taskwall/ decisions/ console/ diff/ library/ permissions/ hq/  (Phase 3)
```

### Foreman link
`client.foreman.ForemanLink`: `java.net.http` WebSocket to `ws://127.0.0.1:${AGENTCRAFT_PORT:-7878}`
(no Origin header; the Foreman refuses any). On open it sends `hello`; the `snapshot` reply makes the
link `synced`. Frames are reassembled and parsed on the link's own daemon threads and applied to
`ForemanState` on the client thread with `Minecraft.execute`, in order, so the render and server
threads never block on the network. Requests (`Foreman.send` / `submitGoal` / `answer` / ...)
get an id, are chained (java.net.http allows one outstanding send) and complete with the matching
`ack` (20 s timeout; failed immediately when not synced or when the connection drops). `diff`
replies are matched by `requestId`. Reconnect forever with backoff 0.25, 0.5, 1, 2, 3, 5 s; a watchdog
pings every 15 s and drops a connection that is silent for 45 s or that sends no snapshot within 15 s.
A killed Foreman is noticed at once (connection reset) and its restart is picked up within the
backoff (about 3 s in the Phase 2 test). The model keeps the last known state while disconnected
(`isStale()`): agents stay in place with a dimmed "Foreman offline" plate and the HUD says
"Reconnecting to the Foreman".

Before the first connection the HUD pill reads "Foreman not running" and gives a hint from
`hub.ConnectionHints` (pure, `ConnectionHintsTest`). In a dev run the hint is `tools/mac.mjs launch`.
In a launcher jar it reads "it starts with the game; or run tools/foreman-daemon.sh". The auth
banner falls back on the same split when the Foreman sends no message of its own. A jar in Prism
gets its port and profile as `-Dagentcraft.port` / `-Dagentcraft.profile` JVM args, which
`tools/hardcore-setup.mjs` writes (tools/README.md "Playing in a Hardcore world").

**Client token** (docs/HUB.md "Client token"). `hello` carries `token` when the mod finds one, so a newer Foreman
gives the mod a full (not read-only) connection; an older Foreman never sees the field. `ClientToken.resolve`
(pure, `ClientTokenTest`) runs on **every connect** (a restarted Foreman has a new token): the run file whose
`port` is the port the mod connects to, looked for in `<home>/<AGENTCRAFT_PROFILE>/foreman.json`, then
`<home>/foreman.json`, then every `<home>/<dir>/foreman.json` (home = `AGENTCRAFT_HOME`, else `~/.agentcraft`;
`tools/mac.mjs` passes both); in it the first of the fields `clientTokenFile`, `tokenFile` (the Foreman's),
`clientTokenPath`, `tokenPath`, `clientToken` that names a readable file (relative = to the run file's folder), else `client.token`
in `<home>/<runfile.profile>/` or next to the run file. No run file / none of these = an older Foreman (no
token sent). `AGENTCRAFT_CLIENT_TOKEN` overrides all of it (dev). The token is never logged; `dev.state.foreman`
has `clientToken{sent, runFile, tokenFile, note}`, `readOnly`, `restartRequired[]`.
A refusal whose error contains "read-only connection" marks the link read-only (`ForemanState.readOnly()`, cleared
when the connection drops) and `Foreman.refusal` words it "Foreman did not accept the client token (read-only
connection)"; the hub's footer then says so on every tab instead of key hints.

### Agents: client-side entities (architecture A), decided by measurement
Two options were prototyped in the Phase 2 test room on the same six anchor-to-anchor routes
(a temporary `dev.proto.ab` command, removed after the measurement; raw data in
`artifacts/logs/proto-ab-final.json`):

- **A**: client-only entities in the client level, moved by `AgentMotion` along a `GridPathfinder`
  route (A* over the client's blocks, string-pulled). No physics, no server AI.
- **B**: a server-side `PathfinderMob` walking with vanilla `GroundPathNavigation` (`moveTo`, re-path
  every 10 ticks like vanilla move-to goals), synced to the client by vanilla entity tracking.

| route | A arrive (ticks) | A final error | A speed CV | B result | B final error | B speed CV | B stalled ticks |
|---|---|---|---|---|---|---|---|
| desk_marlow -> library | 42 | 0 | 0.038 | stopped short | 1.183 | 0.302 | 369 |
| desk_kit -> testbench | 108 | 0 | 0.061 | stopped short | 1.227 | 0.212 | 369 |
| lounge -> mergestation | 122 | 0 | 0.021 | stopped short | 1.566 | 0.192 | 369 |
| user -> desk_tove | 102 | 0 | 0 | timed out | 1.177 | 0.308 | 385 |
| library_2 -> terminal | 147 | 0 | 0.014 | stopped short | 1.199 | 0.168 | 369 |
| testbench_2 -> lounge_2 | 75 | 0 | 0.016 | stopped short | 1.531 | 0.226 | 369 |

(Final error = horizontal distance to the anchor in blocks. Speed CV = std/mean of the per-tick
speed while moving. A also ends on the anchor's exact yaw; its route planning took 0.1-2.3 ms.)

B never reached a spot. Vanilla navigation considers itself done about a block early (its goal is a
block, not a point), its first `moveTo` right after spawning fails (the mob is not on the ground
yet), and it stop-starts, with 3-15x the speed jitter of A. Exact desk and podium spots with the
right facing would need a custom move control on top anyway. B also needs the Foreman state on the
server (client-to-server sync), puts Foreman agents into the world save (stale NPCs after a crash),
lets players push them, and the vanilla `AvatarRenderer` (both skin layers, slim arms) requires an
`Avatar`, which a `PathfinderMob` is not. A is exact and deterministic (same state, same picture,
which screenshot QA needs), cheap, needs no networking, and keeps "the game is only a view".
**Chosen: A.**

How A works: `AgentManager` (END_CLIENT_TICK) keeps one `ClientAgentEntity` (extends `Avatar`,
negative entity id, `noSave`/`noSummon` type; a server-side instance discards itself) per Foreman
agent in `mc.level`. Targets come from `StationAssigner` (desk -> `desk_<id>`, shared stations ->
sticky slots, off shift -> lounge). New agents appear on their spot. Station changes walk at
2.9 blocks/s (the vanilla walk cycle is driven by the real distance moved), turning at most
24 deg/tick and turning in place before walking backwards. No route (or more than 96 blocks)
teleports. Rendering: `EntityRenderDispatcherMixin` routes agents to `AgentRenderer` (an
`AvatarRenderer`, slim or wide by skin), because vanilla sends every `AvatarRenderState` to the
player renderer at submit time. Clicks on agents are consumed client-side (never sent to the server,
which does not know them).

### Nameplates: declutter and occlusion (fix round)
The verifier found plates unreadable whenever agents shared a station (the lounge at every session
start): a full plate is up to 126 GUI px (3.15 blocks) wide, lounge slots are 1.4 blocks apart, all
plate backgrounds were translucent and drawn after all (opaque) text, so side-by-side plates
interleaved their text and a nearer plate let farther text ghost through. Fixed at both levels:

- **Occlusion** (`client.ui.WorldUi`): plates are opaque on `Layer.SOLID` (see gotchas) and get a
  depth nudge by rank, so whenever two plates do overlap, one hides the other completely. Checked
  with Improved Transparency (OIT) on as well (`artifacts/shots/fix2_oit_*.png`).
- **Declutter** (`client.agents.PlateLayout`, once per frame at END_EXTRACTION): projects every
  visible plate to a screen rect, collapses low-value plates (idle/done/off shift/offline, weight
  <= 2) to a name-only pill when the full plate would overlap another, then places plates in priority
  order (crosshair/focused, waiting, error, working/thinking, then the low-value tier; nearest first
  within a tier, sticky) at the lowest lift that clears the plates placed before. Lifts slide (fast
  up, calm down, feed-forward for drifting targets), jump on camera cuts and when a slide would carry
  a plate across another one, and a lifted plate draws a hairline in the agent's colour down to its
  head. The plate under the crosshair always shows in full (hover a compact plate to read it).
  Plates behind walls take no space (line-of-sight test per tick; a camera inside a wall block is
  handled), plates nearer than 1.3 blocks are hidden, a crowd taller than 8 plates hides its
  low-value plates.
- **Mid-distance legibility**: plates keep their world size up to 12 blocks, then grow with
  distance up to 1.6x (constant screen size), so desk plates read from `cam_room`.

Measured (fix round, `artifacts/logs/phase2_fix_live.json`): 0 overlapping plates in every settled
view (lounge front/back/side/focused/stale, showcase, room, reconnect); live sim at speed 4 (up to
six agents walking at once): some plate pair overlapped in 13 of 319 samples (4 %, 200 ms apart),
no run longer than ~0.25 s (mid re-arrangement), always drawn cleanly in rank order. Layout pass:
1-16 microseconds per frame. Fps with vsync off: ~1440 with six agents and plates in view vs ~1850
looking away (the verifier measured 1400-1500 before this change).

### Anchors and the test room
`layout.Anchors` holds the published layout (an immutable snapshot, readable from any thread) and
saves it as `agentcraft-anchors.json` in the world folder; it is loaded again whenever the HQ world
starts. `/agentcraft hq [builder]` runs an `hq.HqBuilder`, publishes its anchors and moves the world
spawn to `spawn`. The Phase 2 builder `test` (`hq.TestRoomBuilder`) is a temporary 25x25 walled
room: a desk island with six monitors (north), library shelves and the task wall (west), terminals
and merge stations (east), a meeting table and the goal atrium (centre), the podium with user spots,
a per-agent status lamp test bench and the lounge (south, everyone facing north so `cam_agents` sees
their faces), plus a sample row of all 16 blocks between vanilla reference blocks north of the room
(`cam_blockrow`). `/agentcraft anchors` and `dev.anchors` list the anchors; `dev.camera {anchor}`
uses them.

### Buildings (blueprints placed per repo)
The contract is `docs/BUILDINGS.md`; the server side lives in `dev.agentcraft.building`.
- `Blueprints`: bundled sidecars `data/<ns>/blueprints/<id>.blueprint.json` + templates
  `data/<ns>/structure/<id>.nbt` (via the server's resource + structure template managers), then
  `<gameDir>/agentcraft/blueprints/<id>.blueprint.json` + `<id>.nbt` (read with NbtIo, data-fixed,
  `StructureTemplate.load`); same id in the user folder wins. Loaded on server start, data pack reload
  and `/agentcraft blueprints reload`. A sidecar whose `size` differs from its template, or that
  doesn't parse, is skipped with a log line (missing required anchors are only warnings).
- Bundled blueprints (studio, workshop, campus2..5; generated by `tools/blueprints`, `node
  tools/blueprints/build.mjs --all`) are vanilla blocks except the station blocks (contract C5): a
  world opened without the mod keeps them, minus monitors/boards/podium/lamps/terminals/merge
  station/archive. Front doors are iron with a stone button inside and out. The decorative
  AgentCraft blocks (plaster, walnut, tile, parquet, glow panel/strip) stay registered for old worlds.
  Sidecars carry `foundationBlock` (C4; the world stream's placement fill reads it). Group sidecars
  also carry per-wing `testbench@n` spots (renamed `testbench:<repoId>`; routing does not use them yet).
- `Buildings`: `<world>/agentcraft-buildings.json` (`{version, next, buildings:[...]}`; ids `b<n>`
  are never reused), loaded for any world. `place` refuses: a repo that already has a building, more
  repos than wings, a box leaving the build height, a box overlapping another building (never
  forceable, removal would break the newer one), and block entities that are not AgentCraft stations
  (unless `force`). It then saves the box (air included, verified to hold one entry per cell) to
  `<world>/agentcraft-buildings/<id>.before.nbt`, places with mirror NONE, entities ignored, no
  waterlogging, flags `UPDATE_CLIENTS | SUPPRESS_DROPS | SKIP_BLOCK_ENTITY_SIDEEFFECTS` (a forced
  chest is replaced without spilling, so restore doesn't duplicate items), reconnects panels,
  rewrites `<prefix>:#n` bindings to the n-th repo, clears drops. `remove` places the snapshot back
  at the same corner and deletes it; `remove <id> forget` only drops the record.
- Placement math is `BlueprintTransform` (pure; unit tests in `src/test/java`, `gradlew test`, also
  run by `build`). Vanilla rotates about the template's origin cell (clockwise_90 puts the footprint
  at x-(sizeZ-1)..x), so `place` shifts the position by the rotated box's minimum: the `origin` is
  always the rotated box's minimum corner. Anchors use the continuous form of the same map:
  CW `(x,z) -> (sizeZ - z, x)`, 180 `(sizeX - x, sizeZ - z)`, CCW `(z, sizeX - x)`, yaw + 90 per
  clockwise turn.
- Wing anchors: single building `name@1 -> name`; group `name@n -> name:<repo>` plus plain `name`
  for wing 1 (so `task_wall` exists in every building). Unused wings' anchors are dropped and their
  `repo:#n` bindings stay as they are (they match no repo).
- Layouts: in the HQ world `Anchors.current()` stays the studio. In any other world it is the home
  building's layout (`Anchors.showDerived`, never written to `agentcraft-anchors.json`), EMPTY with
  no buildings. `Buildings.layoutFor(repoId)` = that repo's building layout (name `building:<id>`,
  revision `placedAt`), else `Anchors.current()`.
- Routing (client, `building.Routing` pure + unit-tested): `Buildings.sites()` / `regions()` / `layouts()`
  are immutable views rebuilt once per change (volatile, safe from the client thread, same instance until
  the next change). An agent's repo = `agent.repoId`, else its task's `repoId`, else (lead) the current
  goal's `repoId`; off-shift agents have none (home lounge). Its layout = that repo's building, else
  `Anchors.current()`; the home building is always the `Anchors.current()` instance (one identity and
  revision whichever way it is reached). A building that has neither the agent's desk, its station nor a
  lounge sends it home (`Routing.canHost`) instead of dropping it. `AgentManager` groups agents by layout
  name: one `StationAssigner`, pathfinder and seat cache (`layout|anchor`) per layout; a layout's revision
  change snaps only its agents; a changed building walks the agent there outdoors, or teleports it with a
  vanilla poof at both ends (see "Walking between buildings").
  The player's building alone pulls waiting agents to the player. `AgentView.layout` is what
  `AgentLife` (monitor gaze) uses; `dev.agents` lists `layout` and `repo` per agent.
- Regions: `HqWorldDriver` (lamps, podium, merge station, monitors, signal bulbs), `HqClientFeature`
  (particles), `StatusLampRenderer` (face towards the region's centre) and `MonitorFeature`
  (`monitor_<id>` anchors of every layout) cover every region: the studio's bounds and each building's
  box (+3). `ci:<repoId>` lamps work everywhere; `ci:#n` (the n-th Foreman repo) only in the studio, an
  unrewritten placeholder in a building shows idle. `dev.state` -> `hq.regions` lists them.
- Task walls: binding `repo:<repoId>` (from `repo:#n` at placement) filters the wall (and the task screen
  opened from it) to that repo's tasks under a title strip with the Foreman repo's name; an empty or other
  binding shows all tasks. `repo:#n` left unrewritten matches nothing (empty wall). `dev.taskwall` boards
  show `repo`, `title`, `tasks`.
- Commands (gamemaster, allowed in Hardcore too: explicit and reversible): `/agentcraft blueprints
  [reload]`, `/agentcraft buildings`, `/agentcraft place <blueprint> <repo>[,<repo>...] [rotation]
  [force]` (everything after the blueprint is one greedy argument, so repo ids need no quotes;
  rotation `none|clockwise_90|clockwise_180|counterclockwise_90` or `cw|ccw|90|180|270`, default:
  entrance facing the player; ground row at the feet, near edge 2 blocks ahead, centred),
  `/agentcraft remove <id> [forget]`, `/agentcraft home <id>`.
- Wizard (client, `dev.agentcraft.client.building`, contract in docs/BUILDINGS.md "Wizard"):
  `BuildingWizardFeature` (key unbound by default, `/agentcraft build` via `BuildingCommands.wizardOpener`),
  `RepoPickScreen` -> `BlueprintPickScreen` -> `BuildPlacement` (state, raycast, conflict scan on the
  client level, confirm through `getSingleplayerServer().execute` in the player's dimension),
  `GhostRenderer` (`LevelRenderEvents.COLLECT_SUBMITS`, one `submitCustomGeometry` with
  `RenderTypes.debugFilledBox()`: POSITION_COLOR quads, blended, no depth write, no culling; set only
  `addVertex` + `setColor`; cubes inflated 0.005 against z-fighting; camera-relative on a fresh
  PoseStack), `PlacementHud`, `KeyboardHandlerMixin` (placement keys before vanilla, so Esc does not
  pause). The pure parts are `building.GhostModel` (rotated cells, checked against vanilla
  `StructureTemplate.transform`; exposed faces; conflict classes; `place`'s refusals; the outline),
  tested in `GhostModelTest`. The outline (`GhostModel.outline()`) traces the drawn columns' perimeter
  (bottom edges, per-column top edges, verticals at corners and height steps, merged: a full box is its
  12 edges), not the template box: templates need not write every cell (the studio, 37x14x36, leaves
  7010 cells unwritten, both front corners beside its 7-wide porch), and outlining the box made the
  outline stand out past the building there. `frontEdges(front)` is the brass entrance bar (front-most
  face only). The reserved box is drawn faintly only for an overlap / player-inside / block-entity refusal. Template cells come from `StructureTemplate.save` (palettes are private), cached
  per `Blueprints.Entry`. Render cost: the workshop (27x10x21, 2583 visible cells) draws 3750 faces
  (15k vertices) per frame; conflict cells draw only the outline of each blob; `dev.build.state`
  reports `render.faces`, `conflictFaces`, `lastFrameMicros`, `maxFrameMicros`. Placement is refused
  on the client while the player stands in the box.
  DevBridge: `dev.build.open {step: repos|blueprints, repos?, blueprint?}`, `dev.build.start
  {blueprint, repos, origin?: [x,y,z] (rotated box minimum, locks there), turns?}`, `dev.build.state`,
  `dev.build.rotate {turns?}`, `dev.build.nudge {forward?, right?, up?}`, `dev.build.lock {on?}`,
  `dev.build.confirm {force?}` (replies with the server's result), `dev.build.cancel`; screens
  `build_repos`, `build_blueprints` for `dev.screen`.
- Fix wave 1, stream world (docs/BUILDINGS.md "Occupancy", "Fluids", "Terrain fit", "Safe remove", "Crash
  safety", "Change a building's repos", "Move a building"): `building.Occupancy`, `TerrainFit`, `TemplateGrid`
  (the template's written cells and block entities, shared by server and ghost; `TemplateCells` colours it),
  `Reconcile`, `Displays`; tests `TerrainFitTest`, `BuildingLifecycleTest`, `PinAndReconcileTest`.
  `dev.buildings.pending` (pending sites with `snapshotExists`, the snapshot files, each building's pin
  fingerprint, the world-start reports) and `dev.buildings.failNextRename` (the next move's snapshot rename
  fails: the move must roll back). `dev.build.state.conflicts` adds
  `water, lava, foundation, cleared, snapshotMinY, notes[]` and `moving`; refusals include occupants, lava
  and doors cut by the box edge. `dev.build.pick {action: split|design_new}` on the blueprint step when no
  blueprint has enough wings (`screen.tooFewWings`, `maxWings`). `dev.hub.action`: `edit_repos {buildingId,
  repos}` (without repos: opens the repo screen), `move {buildingId}` (then `dev.build.lock/nudge/confirm`),
  `undo_move {buildingId}`, `remove` a third time after a "Move these" refusal forces it
  (`dev.hub.state.forceRemoveArmed`); `dev.hub.state.buildings[]` adds `snapshotBox, revision, movedFrom,
  check, checkProblem`. `dev.decision {podium: [x,y,z], showAll?}` opens the queue as that podium's
  right-click; `dev.decisions` reports each decision's `podium` and the screen's `scope`/`scoped`. `dev.state.hq`
  adds `goalLampByBuilding`, `mergeActiveIn`, `mergeHome`, `routed` and each region's `dimension`. Commands:
  `/agentcraft remove <id> force`, `/agentcraft repos <id> <repo>[,...]`; `/agentcraft buildings` prints
  the check lines.
- Wizard screens draw buttons with `UiBits.button` (the clay primary is tinted for contrast).
  `Panels.button`'s primary label used `palette.ui.highlight`, which is the clay itself: the label was
  invisible ("Place…" in the blueprint step, TaskScreen's primary buttons); it is `panel_hi` now. The
  blueprint step's detail column is up to 200 px wide; facts and description wrap in it and the
  description scrolls (wheel). The top-down plan is `building.BlueprintPreview` (shared with the hub).
  `RepoPickScreen` has a fixed-blueprint mode (hub "Place"): Next checks the repo count
  (`RepoPickScreen.fits`) and starts placement; `BuildingWizardFeature.openFor(id)` / `placeNow(id,
  repos)` are the entry points.
- Trying it without a bundled blueprint: save a structure with a structure block (it lands in
  `<world>/generated/<ns>/structure/<name>.nbt`), copy it to `run/agentcraft/blueprints/<id>.nbt`,
  write `<id>.blueprint.json` next to it (size = the structure block's size), then
  `/agentcraft blueprints reload`.

### Walking between buildings (fix wave 2, W8)

An agent whose building changes walks there (docs/WAVE2.md W8). Code: pure planner and rules in
`dev.agentcraft.walk` (`WalkCell`, `OutdoorPlanner`, `RouteCache`, `WalkRules`, `WalkSettings`, unit-tested
on synthetic terrain in `src/test/java/dev/agentcraft/walk`), client side `agents/OutdoorRoutes` (planner
queue, cache, setting, stats, dev commands), `agents/LevelTerrain` (block states -> cell codes),
`AgentManager` trips and `mixin/ClientLevelBlockMixin` (block changes).

- **Decision** (`WalkRules.decide`, in this order): walking on for the world (hub > Buildings toggle,
  `<gameDir>/agentcraft/walking.json` keyed by the save folder name, default on); both buildings found in
  the player's dimension; both have an `entrance` anchor; entrances <= 256 blocks apart (horizontal); the
  player within render distance (`options.getEffectiveRenderDistance() * 16`) of the straight line between
  them; every chunk within 1 chunk of that line loaded on the client. Otherwise: teleport with a puff at both
  ends (as before), the reason counted in `dev.walk.state`.
- **Route**: A* from entrance to entrance over `WalkCell` codes. Standable: a floor below (collision top
  14..16 sixteenths: full blocks, dirt path, soul sand) with open, door or 1-deep water feet, or a block up
  to a bottom slab in the feet cell; the head cell open (water there = too deep). Never a floor: leaves,
  fences/walls (top > 16), trapdoors, water. Hazards (lava, fire, magma, powder snow, campfire, cactus,
  berry bush, wither rose) are avoided as floor, feet and head. Moves: 8 directions (diagonals on one level,
  no corner cutting), step up <= 1 (with headroom), drop <= 3 (the column above the landing open), so
  cliffs over 3 are never taken; wading costs extra, doors a little. **Doors, fence gates and trapdoors are
  passed through visually** (decision: client-only agents open nothing in the world; routing around closed
  doors would make every iron-door building unreachable). Search box: the endpoints +-40 blocks (y +-26),
  60 000 expansions max; then string pulling (agent width 0.6, at most 24 cells a segment) with an extra
  waypoint at the edge of every step/drop (the agent climbs or drops at the edge, not through the corner).
- **Budget**: `OutdoorRoutes.tick` (from `AgentManager.tick`, client thread) steps the queued jobs with
  2 500 expansions and 2 ms per tick, whichever runs out first; one job per building pair, shared by every
  agent making that trip. Measured (unit test `corridor256Performance`, hills + a 2-deep river with fords +
  tree clumps, a 253-block corridor): ~22 000 expansions, ~18-20 ms in total over ~15 ticks, worst tick
  2.0 ms, a 288-block route of ~97 points. No snapshotting or threads: the level is read on the client thread.
- **Trip** (`AgentManager.Trip`): planning (the agent stays where it is) -> walking: one route handed to
  `AgentMotion` = inside A to its entrance (`GridPathfinder`, standing up first) + outdoor points + inside B
  from its entrance to the spot (seat approach and the last step onto the seat). Normal retargeting is
  skipped while a trip runs (a station change in B updates the trip's target; retargeting resumes on
  arrival). Ends: arrival; `player_far` (the player > render distance from the agent: it is placed at its
  spot); `blocked` (the next outdoor waypoint is no longer standable, checked every 10 ticks: the route is
  dropped and the agent teleports; the next trip re-plans); `stuck` (3x the walking time + 15 s);
  `rerouted` (sent to a third building mid-walk: teleport). Deadlines count unpaused ticks only (every
  AgentCraft screen pauses singleplayer). `dev.agents {settle:true}` finishes trips (`settled`).
- **Cache** (`RouteCache`, 32 routes, LRU): key `<from layout>><to layout>` (not reversible: drop 3 / climb 1).
  Dropped when a block changes within 2 blocks of a route cell (`ClientLevel.sendBlockUpdated` HEAD inject,
  `require = 0`), on any building change (`Buildings.regionsSignature`) and on a level change; checked
  again (every cell still standable) before reuse, which also covers chunk reloads.
- **Not done**: a released lead going home still teleports to the home lounge (`startDeparture`); no
  re-plan from mid-route when blocked (teleport instead).
- QA: `dev.walk.toggle {on:true}`, `dev.walk.plan {from:"b1", to:"b2"}` (particles for 20 s, then
  `dev.screenshot`), `dev.walk.send {agent, to:"b2"}` then `dev.walk.state` (trip planning -> walking) and
  `dev.agents` (its `path`), `dev.walk.send {agent, to:null}` to send it back; `dev.hub.open {tab:"buildings"}`
  + `dev.hub.action {action:"press", button:"walk_toggle"}` for the toggle (its fit: `dev.walk.state.ui`).
- Block changes that mean the same to a walker (a door opened, a lamp lit) do not drop routes (the mixin
  compares the two states' cell codes).

### A lead per building (`dev.agentcraft.client.leads`)
The contract is docs/PRWATCH.md "A lead per building"; routing rules in docs/BUILDINGS.md "Client (routing)".
- Building key `"<worldId>/<buildingId>"`, worldId = the save folder name (`Buildings.worldId()`, from
  `getWorldPath(ROOT)`; set before the listeners hear about a loaded world, null before they hear it stopped).
  Pure rules are `building.LeadRouting` (keys, the assign/release diff, a lead's building, the podium filter,
  `leadForRepo`) + `Routing.layoutForBuilding` / `siteAt`, unit-tested in `LeadRoutingTest`.
- **The home building is always Marlow's** (docs/HUB.md "Repos and Goals tabs"): `LeadRouting.leadBuildings` leaves it
  out of everything `LeadsFeature` sends, and a home change (hub "Make home", `/agentcraft home`) sends one `lead.sync`
  (the old home gets a lead, the new home's lead is released). `leadBuilding`/`assignedHere`/`leadOfBuilding`/`leadForRepo`
  take the home building and ignore a (stale) assignment to it, so the home podium shows Marlow's decisions even before
  the Foreman processed the sync. `dev.leads.state` has `syncedHome`.
- `LeadsFeature` is the only sender, from one `Buildings` change listener (every placement path ends in
  `Buildings.place`, every removal in `forget`): world loaded -> `lead.sync {world, buildings:[{building,
  repos}]}`; new building -> `lead.assign {building, repos}`; building gone -> `lead.sync` (releases it, then gives the freed lead to an overflowed building);
  link (re)connected -> `lead.sync` (the reconciler for anything done offline); world stopped -> nothing.
  Nothing is sent without a singleplayer world, nor for a world whose `agentcraft-buildings.json` could not
  be read (it loads as "no buildings"; a sync would release every lead of the world; `dev.leads.state`
  `blocked`). A refused ack (an older Foreman acks unknown types `ok:false`) is logged once per type.
- Cast: `Cast.deskIds()` (workers + Marlow) owns the studio's / test room's desks, monitors and test-bench
  lamps, so lead entries (`ines`, `bram`, `cass`, role `lead`) added to cast.json get none. An agent
  whose Foreman name is missing or just its id shows the cast name, else the id capitalised
  (`Protocol.Agent.displayName`).
- `ForemanState.leads()` from `snapshot.leads` / `leads.update`; `leadsKnown()` is false until the Foreman
  sends either (an older Foreman never does). `Leads.view()` resolves the assignments against this world's
  buildings once per Foreman revision / buildings change (client thread): `assignedHere` (lead -> building
  of this world that still exists), `podiumOwners` (lead -> building whose podium has its decisions),
  `leadOf(building)`, `leadForRepo(repo)`.
- With leads known, `AgentManager` routes role-`lead` agents by assignment only (Marlow and unassigned:
  home; the Foreman sets the lead's `repoId` to its goal's repo, which is ignored). A lead (not Marlow)
  without an assignment to an existing building here is not shown: an entity already shown walks to the
  home `entrance` (from another building it first moves into the home lounge with a puff) and despawns
  with a puff (or after 30 s); reassigned while walking out, it goes back to work. A lead spawned after
  the level was first populated starts at its layout's `entrance` (else `spawn`) and walks in. A lead
  whose station is `desk` (leads have no desk) goes to `meeting`. A lead without its own skin texture
  wears Marlow's (`AgentSkins.get(id, skin, lead)`); names fall back to the cast name, else the id
  capitalised; portraits to the tinted initial.
  Without leads known (older Foreman) routing is as before (the lead follows its goal's repo).
- Podiums: `DecisionPodiumRenderer` (bubble, count, `open` sync) and `HqWorldDriver` (block `open`,
  signal bulbs, `decisions` lamps, per region: `Wanted.podiumOpen(area)`) use the same filter: a building's
  podium shows its lead's decisions; the home podium (and one outside any building, the studio's) Marlow's
  and every decision whose agent is not an assigned lead with a podium of its own. Particles follow the
  block's `open`. Older Foreman: every podium shows everything, as before.
- Hub Buildings tab: a `Lead` row (portrait + name, `Marlow (home)`, `no lead: Foreman offline`) and the
  lead in the list; `dev.hub.state` buildings carry `lead`, `leadLabel`, `leadKey`. A `repo:` task wall's
  title is `<repo> · lead <name>` (`dev.taskwall` `title`).
- DevBridge: `dev.leads.state` (worldId, known, raw `assignments`, `buildings` with `key`/`lead`/`leadLabel`/
  `hasPodium`, `podiumOwners`, `leads` with `assignedBuilding`/`routedLayout`/`target`/`walking`/`departing`/
  `pos`, and `sent`: the last 20 `lead.*` messages with `ok`/`error`/`result`), `dev.leads.sync` (send
  `lead.sync` now), `dev.state` -> `leads` (summary), `dev.state` -> `hq.homePodiumOpen` / `podiumOpenIn`.
- Testing without a Foreman that knows leads (sim backend, `node tools/mac.mjs launch --backend sim --dev`):
  hold the live stream (`dev.foreman.hold {on:true}`, else the next snapshot clears what you inject), then
  `dev.foreman.inject` an `agent.upsert` with `{id:"ines", name:"Ines", role:"lead", state:"idle",
  station:"meeting", ...}`, a `leads.update {leads:[{leadId:"marlow", repos:[]}, {leadId:"ines",
  building:"<worldId>/b2", repos:["..."]}]}` (worldId from `dev.leads.state`) and a `decision.upsert` with
  `agentId:"ines"`; then `dev.leads.state`, `dev.agents`, `dev.hub.state`, `dev.state` (hq). Releasing:
  inject a `leads.update` without ines and watch `departing`. `dev.foreman.hold {on:false}` reconnects.

### Hub (`H`, `/hub [tab]`)
The contract is docs/HUB.md "Hub screen"; code in `dev.agentcraft.client.hub`.
- `HubScreen` (not pausing): tabs from `HubTab` (Inbox, Buildings, Repos, Goals, Team, Settings, Status). Repos, Goals,
  Team and Settings are `HubPane`s
  (`ReposTab`, `GoalsTab`) with their own state: the hub hands them keys, typed characters, clicks and the
  wheel first; while one of their text fields has focus every key goes to it (typing "h" never closes the hub;
  `isInputCaptured`, SDL text input on), Esc unfocuses, Tab moves between the view's fields, Ctrl+Enter sends. Tab / Shift+Tab cycle tabs, Left /
  Right switch buildings/blueprints, Up / Down select, Esc or the hub key close. Selection and the armed
  remove are kept by **id** (a removed building never hands its armed confirm to its neighbour); an
  armed remove expires after 6 s.
- Actions: `HubActions` through `ServerTasks.callAsPlayer((level, player) -> ...)` (integrated server,
  the player's own level and ServerPlayer, completes on the client thread), calling `Buildings` directly,
  so no operator permission is needed (Hardcore). `Building.dimension` (recorded by `Buildings.place`;
  null in older records) must equal the player's dimension for remove and teleport, and
  `Buildings.remove` itself refuses another dimension (it pastes the snapshot into the level it is
  given). Old records without a dimension fall back to `Buildings.stationCount(level, box) > 0`
  (AgentCraft station block entities in the box in the player's dimension; loads those chunks). Remove refuses with the player in the box. Teleport goes to
  the `entrance` anchor (else `spawn`, else the box centre), tries y+0, +1, -1, +2..+4 for a spot with no
  collision, no lava and a floor, dismounts, `teleportTo` in the same level, resets fall distance, and
  closes the hub. Every result is a toast and `HubActions.last()`.
- Blueprint browser: the plan (`BlueprintPreview`) and rendered previews (`PreviewImages`):
  `<id>.preview-{iso,top,front,cutaway}.png` from `Blueprints.userDir()` first, then (bundled
  blueprints) the class-path resource `/data/<ns>/blueprints/`. Discovery is cached 2 s per blueprint;
  bytes are read on `Util.ioPool()`, decoded and registered as `DynamicTexture`s
  (`agentcraft:hub_preview/<n>`) on the client thread, only for the view shown; max 2048 px a side,
  LRU-evicted beyond 8 textures / 48 MB, all released when the hub closes; keyed by path + mtime + size
  so a regenerated PNG shows. Missing previews just leave the Plan chip. "Design new…" calls
  `HubFeature.designNew` (a `Consumer<Screen>` called with the hub as parent), set by `DesignFeature`.
- Designs (third list, `Sub.DESIGNS`): `ForemanState.designs()` newest first, status pill + step,
  detail with the request, result and error, Cancel / Show blueprint / Place on the plot / Place….
- Status: `Foreman.link().status()` (phase, url, attempt, last error, since), `ForemanState.status()`
  (backend, auth, account, user, message, `usage.windows` with progress bars and reset times, spend,
  version), the mod version and `DevBridge.status()`.
  Since wave 2 the Status tab is a `HubPane` (`StatusPane`) with chips **Overview** (the above) and **Keys & help**
  (←→ switch; see "HUD check-in (wave 2)").
- `H` opens the world's last hub tab (`hub-hud.json`), or after an away toast the Inbox (else Goals). The DevBridge
  screens and `dev.hub.open` without a tab still open Buildings. Tabs carry badges (`TabBadges`).
- DevBridge: `dev.hub.open {tab?, sub?: buildings|blueprints|designs, buildingId?, blueprint?, designId?,
  view?: plan|iso|top|front|cutaway}` (cancels a placement, opens, selects); `dev.hub.state` (`open, tab,
  sub, selectedBuilding, selectedBlueprint, selectedDesign, designNote, armedRemove, busy, view,
  lastAction, buildings[] (with dimension), blueprints[] (with preview counts, hasPlot), designs[], previews{tried[], found[{kind, where, path, state, width, height, error}]}` for
  the selected blueprint, `previewTextures`, `buttons[{id, label, state}]` drawn last frame,
  `description{rows, lines}`); `dev.hub.action {action, ...}` with `tab {tab}`, `select {buildingId |
  blueprint}`, `view {view}`, `home|teleport {buildingId}`, `remove {buildingId, confirm?}` (without
  confirm the first call arms and a second confirms; replies with `result`), `place_new`, `place
  {blueprint, repos?}` (with repos: straight to placement mode, else the fixed-blueprint repo step),
  `place_plot {blueprint, repos?}` (Place on the plot; with repos straight to placement locked on the
  plot), `design_new`, `cancel_design {designId}`. Server and Foreman actions reply after they answered.
  Screens for `dev.screen`: `hub`, `hub_<tab>`, `hub_blueprints`, `hub_designs`.

#### Repos and Goals tabs
The contract is docs/HUB.md "Repos and Goals tabs" (+ its multi-repo amendment); code in `client.hub`
(`ReposTab`, `GoalsTab`, `HubGoals` = model + sends, `HubField` = a `TextModel`/`TextFieldView` field,
`PaneList` = a list with its own scroll, `HubDev` = DevBridge) and the pure `dev.agentcraft.hub.GoalLogic` /
`HubSeen` (unit-tested in `GoalLogicTest`).
- Protocol mirror: `Goal.instructions/planId/branch/prs/repos` (`allRepos()` falls back to `repoId`),
  `FeedItem.goalId`, `Decision.goalId`, `Repo.settings` (`RepoSettingsView`, env as `envKeys`), `Task.pr`
  (`TaskPr`), `Digest/GoalDigest/DigestLine` (the `goal.digest` ack result, `Foreman.digestOf`). Sends in
  `Foreman`: `submitGoal(text, repos, branch, instructions)`, `goalMessage`, `goalInstructions`, `goalPlan`,
  `goalCancel`, `goalDigest`, `removeRepo`, `refreshPrs`. **An older Foreman** acks unknown types `ok:false`
  with its schema error on `type` (`"type: Invalid discriminator value…"`): `Foreman.unsupported(ack)` spots
  that and every note says "… needs a newer Foreman"; absent fields show "not reported" lines; `goal.submit`'s
  extra fields are dropped silently by its schema, so the form says so when the goal comes back without them.
- Repos tab: list (id, branch@head, `*` dirty, CI dot, building + wing, lead, worktrees, open PRs = tasks of the
  repo with an open `pr`), detail in a scrolled area (path, branch, CI, worktrees, building "<blueprint> · wing
  n (bN)", lead "(shared by N repos)" / "(home)", settings view, its goals: click opens one in Goals), buttons
  New goal… / Place a building… (`BuildingWizardFeature.openWithRepos`, only without a building) / two-step
  Remove…; top: Add repo… (path field, Enter adds) and Refresh PRs.
- Goals tab: building filter chip (cycles buildings with repos: `GoalLogic.inBuilding` on `allRepos()`), New
  goal…, the away panel, the list (`GoalLogic.newestFirst`; unread dot = `GoalLogic.unread(activity,
  seen)`, activity = newest of the goal's `updatedAt`, its feed items, decisions, tasks; the open goal never
  counts as unread and your own sends mark it seen) and the detail: status, progress, lead, repos, branch,
  PRs; view chips Thread / Plan / Instructions n / Tasks n (←→ cycles), two-step Cancel goal… (`goal.cancel`).
  - Thread: `HubGoals.thread` = feed items with that `goalId` + decisions whose `goalId` (else their task's
    goal) is it + pending messages, in time order, in a scissored, wheel-scrolled area that follows the
    newest. Only what is in the model's feed tail (200 items, replaced by each snapshot). Open decisions get
    their options as buttons through `DecisionsFeature.answer` (shared with `DecisionScreen`: mark answering,
    send, record / unmark) with the decision screen's guards (AnswerPanel, wave 2): a decision that just showed up ignores clicks
    for 350 ms, Reject and Merge ask twice, Request changes takes the message box's text as the feedback, a question's
    option sends the box's text along; "Answer with text" and "Open…" (the decision screen, back to the hub).
    The message box (multi-line, Enter = newline) sends `goal.message` with Ctrl+Enter or Send: a "You ·
    sending…" line shows at once and leaves on the ack (the Foreman's own feed item replaces it); a refusal
    turns it into "not sent: …" and puts the text back. Its own digest ("Since you last looked") on top
    when it had activity since its last view (`goal.digest {goalId, since}` on opening it).
  - Plan: the memory entry `planId`, wrapped by `GoalLogic.wrap` (words moved whole, long words broken,
    list items hang-indented, `#` lines in clay), scrolled; Edit plan / Write a plan -> a multi-line editor,
    Save plan (Ctrl+Enter) -> `goal.plan`, Cancel.
  - Instructions: the list with Edit / × per line; the field adds (Enter) or saves the one being edited;
    every change sends the whole list (`goal.instructions`).
  - Tasks: the goal's tasks (status, assignee, repo, PR #n state + checks, blocked reason); a click opens the
    task screen (`TaskScreen.withParent(hub)`: Esc returns to the hub); Refresh PRs.
  - New goal form (replaces the list): the goal text (multi-line), standing instructions (one per line,
    `GoalLogic.instructionLines`), For (a chip per repo and per group building: a building sends all its repos,
    wing order, the first is `repoId`), Continue a branch (field + chips from `HubGoals.branches`: the repo's
    worktree branches and goals' branches). Submit goal / Ctrl+Enter; on the ack the new goal opens.
- Seen and digests: `<gameDir>/agentcraft/hub-seen.json` (`HubSeen`: per world = `Buildings.worldId()`, else
  "multiplayer": the Goals tab's last look + each goal's; a goal never opened counts as seen as of the tab's previous visit
  (marked when the tab leaves the screen, so a goal that moved since then has a dot during this visit);
  saved atomically, throttled, and on closing the hub). Opening the hub (or the Goals tab) when the tab was
  last looked at >= 10 minutes ago asks `goal.digest {since}`; the panel (sections by `GoalLogic.sections`,
  one line per goal with `GoalLogic.summary`, click opens the goal) shows at the top of the Goals tab until
  Dismiss.
- Layout: compact when the content area is under 470 × 200 GUI px (GUI scale 4 at 1080p: 448 × ~180): the list
  or the detail with a "‹ Goals" / "‹ Repos" back button (Esc), shorter labels and fields. `dev.hub.state`
  `goalsTab.layout` / `reposTab.layout` = `{guiWidth, guiHeight, compact, needed, available, overflow}`.
- DevBridge:
  - `dev.hub.open {tab: repos|goals, goalId?, view?: thread|plan|instructions|tasks, repoId?, form?: bool}`.
  - `dev.hub.state` adds `reposTab` (selected, adding, path, focus, armedRemove, note, layout, `repos[]` with
    building/wing/lead/leadLabel/openPrs/settings) and `goalsTab` (mode list|detail|list+detail|form, selected,
    view, filter, focus, fields{message, plan, instruction, goal_text, goal_branch, goal_instructions}, form
    {target, repos}, note, armedCancel, layout, away digest, `goals[]` with unread/activity/seen, `goal` = the
    open one with its `thread[]`, plan body, instructions, tasks, digest, and `chips[]` drawn last frame).
  - `dev.hub.action`: `press {button}` (any button id in `buttons` or chip id in `goalsTab.chips`),
    `focus {field}`, `goal_open {goalId, view?}`, `goal_view {view, goalId?}`, `goal_back`, `goal_new
    {repoId?|buildingId?}`, `goal_form {text?, repoId?, buildingId?, branch?, instructions?}`, `goal_submit
    {same}`, `goal_send {goalId?, text?}`, `goal_answer {decisionId, option?, text?}`, `plan_edit`, `plan_save
    {body?}`, `plan_cancel`, `instr_add {text}`, `instr_edit {index, text}`, `instr_remove {index}`,
    `goal_cancel {confirm?}`, `goal_filter {buildingId?}`, `digest_dismiss`, `digest_refresh {since?}`,
    `repo_select {repoId}`, `repo_add {path}`, `repo_remove {repoId?, confirm?}`, `repo_place {repoId?}`,
    `repo_new_goal {repoId?}`, `refresh_prs`. Foreman actions reply after the ack with `result{ok, message,
    unsupported, result}`.
  - `dev.goals.submit {text, repoId?, repos?, branch?, instructions?}`, `dev.goals.message {goalId, text}`,
    `dev.goals.plan {goalId, body}`, `dev.goals.instructions {goalId, instructions}`, `dev.goals.digest {since?,
    goalId?}` (no screen needed; reply after the ack), `dev.goals.seen {reset?, tabAgoMs?}` (hub-seen.json for
    this world; `tabAgoMs` fakes "away" for the digest panel).
  - Screens: `hub_repos`, `hub_goals`, `hub_goal_thread|plan|instructions|tasks` (the newest goal in that view).
- Acks (Foreman as merged, docs/HUB.md "As implemented (Foreman)"): `goal.message {goalId, leadId}` -> "Sent to
  <lead>"; `goal.instructions` / `goal.plan` `{changed}` -> "unchanged: the lead was not told" when false;
  `goal.cancel {cancelled: [taskIds]}` -> "N tasks stopped" (a done goal is refused); `repo.remove {repoId}` ->
  the mod drops the repo itself (`ForemanState.forgetRepo`: there is no removal broadcast); `goal.digest` ->
  the digest. A user's goal message comes back as a feed item `kind:"message"`, `agentId:"user"`, `to:<lead>`,
  shown as "You → <lead>".
- Testing with the sim backend (`node tools/mac.mjs launch --backend sim --dev`; the sim Foreman knows every
  new type: goal-tagged script, plan id, replies to goal messages, cancel): `dev.goals.submit {text:"Add #tags",
  repoId:"demo", instructions:["keep the API stable"]}` -> `dev.hub.open {tab:"goals", goalId:"<id>",
  view:"thread"}` and shoot; `dev.hub.action {action:"goal_send", text:"Use a set"}` (the "sending…" line, then
  the lead's reply), `{action:"plan_save", body:"# Plan\n- one"}`, `{action:"instr_add", text:"no new deps"}`,
  `{action:"goal_view", view:"tasks"}`, `{action:"goal_cancel", confirm:true}`; open decisions of the goal show
  in the thread (`goal_answer {decisionId, option}`); `dev.goals.seen {tabAgoMs: 900000}` then reopen the hub
  for the away panel (or `dev.goals.digest {since}`). The repos tab: `dev.hub.open {tab:"repos", repoId:"demo"}`,
  `{action:"repo_add", path:"/abs/path"}`, `{action:"repo_remove", repoId, confirm:true}`. For an older
  Foreman's paths, inject (`dev.foreman.hold {on:true}` + `dev.foreman.inject`) goals without the new fields.
  Shoot each at GUI scale 2, 3 and 4 (`dev.review.guiScale {scale}`) and check `layout.overflow`.

#### Team and Settings tabs
The contract is docs/HUB.md "Team and Settings tabs, config get/set"; code in `client.hub` (`TeamTab`, `SettingsTab`,
`SettingsForm` = the one form renderer, `ConfigScope` = one config.get/config.set scope, `HubConfig` = the scopes +
restart, `SettingsDev` = DevBridge) and the pure `dev.agentcraft.hub.SettingDef` / `SettingsLogic` / `Staged`
(unit-tested in `SettingsLogicTest`).
- Protocol mirror: `Foreman.configGet(repoId?)`, `configSet(repoId?, changes)`, `restart()`, `repoAgents(repoId)`;
  `ForemanStatus.restartRequired`, `config.changed` (`ForemanState.configRevision()`, `restartRequired()`,
  `ForemanListener.onConfigChanged`), `Protocol.RepoAgentFile`.
- `ConfigScope` (global, or one repo's `repoSettings`; kept for the session, so staged edits survive closing the hub):
  loads on first show, after `config.changed` and after every new snapshot (reconnect, restart), **rebasing** staged
  edits (an edit is dropped only when it now equals the current value). Edits: `set(key, json)` (the mod validates
  at once: type, int min/max, a choice among `options`, list entries, leads start with marlow) and `setText(key,
  text)` (text that does not parse stays as a parse problem, the staged value unchanged). Keys the Foreman does not
  list are synthesised where the mod knows them: a repo's `roles.<agent>` (current value from `Repo.settings.roles`)
  and `claude.agents.<id>.{title,prompt,model,effort}` (model/effort options from the lead/worker defaults).
- Apply (`HubConfig.applyAll`): refuses while a field has a problem; if any staged change widens what agents may do
  (`SettingsLogic.widening`: permission mode away from `policy` (an unknown mode counts as the loosest), a deny rule
  removed, an allow rule added, any change of `claude.useClaudeLogin`) a confirm bar names each one and nothing is
  sent until "Confirm and apply"; then one `config.set` per dirty scope, global first, stopping at the first refusal
  (**not atomic across scopes**: the Team tab's per-repo roles are each repo's own config.set). An ok ack: values
  taken as current, `restartRequired` added to `ForemanState.restartRequired()`, `overridden` shown as notes, reload.
  A refusal: per-field errors from `result.errors` ([{key, error}] or {key: msg}) else from the error text
  ("key: problem; …", longest key first), shown under each field; the rest goes to the note line.
- "Not set": `SettingsLogic.unsetValue` = the default when it is "" or "default" (a repo role, per-agent
  model/effort), else `null` (the Foreman removes the key); `null` always passes the mod's check.
- Restart banner when `restartRequired` is not empty: "N settings wait for a Foreman restart: keys…" + **Restart
  Foreman** (`foreman.restart`). Then "Restarting the Foreman… reconnecting (attempt n) · s" until the link is synced
  with a newer snapshot than at the restart; an ack lost to the closing socket ("connection lost") counts as
  restarting, not failed. Every scope reloads after it.
- Read-only: `config.get` refused as read-only -> the banner "Foreman did not accept the client token…" in place of
  the form; Apply/Restart disabled. An older Foreman (unknown `config.get`) -> "Editing settings needs a newer
  Foreman"; `repo.agents` unknown -> the role is a text field ("the roles picker needs a newer Foreman").
- `SettingsForm` rows: bool = checkbox; enum/model/effort = chips from `options` (effort without options: low,
  medium, high, max; model/effort with no default get a "not set" chip); int = − [typed value] + stepper with the
  range; string = field (multi-line for `*.prompt` / `*Instructions`); stringList = multi-line field, one per line;
  agentList = toggle chips (leads numbered in order, marlow fixed); a repo's `roles.<agent>` = chips "not set" + the
  repo's agent files (`repo.agents`, loaded once per scope and snapshot); map and unknown types read-only. Each row:
  label, badges (changed, restart when not `live`, the source file/flag/env/default), help (one line in compact),
  "Overridden by --flag…" and its problem in red. The form scrolls (wheel); Tab cycles its fields, Enter ends a
  single-line field, Ctrl+Enter applies, Esc unfocuses.
- Settings tab: group chips General / Permissions / Context / Subagents / PRs / Usage (• = staged edits in it) and the
  config file; a group = the global settings whose `SettingsLogic.groupOf` is it (Team keys excluded; `group` from the
  Foreman, else by key prefix). Context lists the MCP servers read-only (`mcpServers` of the ack, else a `map`
  setting named `*mcpServers`); Usage starts with the Status tab's usage windows (`HubScreen.drawUsage`, shared).
- Team tab: roster (`PaneList`): "Models and limits", the leads (staged `claude.leads` order, then the cast's other
  leads "not in use"; building via `Leads.view().buildingOf`, model · effort) and the workers (cast + Foreman +
  configured; on/off = staged `claude.workers`), each with its face, state dot and a second line. Detail: framed
  portrait, live state (state · activity, station, task, goal, lead / building), In use + ↑↓ (leads; marlow always
  first) or On the team (workers), then the form: Profile (`claude.agents.<id>.title/prompt/model/effort`) and Role
  per repo (one row per repo, each repo's scope). Models: lead/worker/design model + effort, task-size models,
  concurrency, then any other Team key (`claude.leadReview`, …). Apply covers the global scope and every repo scope.
- Repos tab: **Edit settings…** opens the repo's form in place of the tab (Done / Esc back), in the Foreman's groups:
  Landing (`land`, `baseBranch`, `pr.*`), Worktrees (`ci`, `setup`, `copy`, `setupTimeoutMs`, `protect`, `env`
  read-only), Agents (`subagents`, `roles.<agent>` for every roster agent: chips of the agent file ids), Review
  (`prReview.*`).
- Layout: compact under 470 × 200 GUI px like Repos/Goals (Team: list or detail with "‹ Team"); banners collapse to
  one line each; only the form scrolls. `dev.hub.state` `teamTab.layout` / `settingsTab.layout` = `{guiWidth,
  guiHeight, guiScale, compact, needed, available, overflow}` where `needed` is the fixed parts (chips, banners,
  Apply row) plus room for two rows: overflow means the form has no room at all, not that it scrolls.
- DevBridge:
  - `dev.hub.open {tab:"team", agentId?: <id>|models}`, `{tab:"settings", group?}`, `{tab:"repos", repoId, edit:true}`.
  - `dev.hub.state` adds `teamTab` (selected, detailOpen, roster[{id, role, inUse, order, title, model, building,
    live[], portrait}], scopes[] (each: phase, error, unsupported, readOnly, file, staged{}, errors{}, overridden{},
    widening[], confirm[], agents[] for repos), form{focus, note, scroll, rows[{scope, key, type, value, current,
    staged, source, live, overriddenBy, problem, editable, y, visible}], chips[]}, config{restartRequired[],
    restarting, restartingForMs, readOnly, …}, layout) and `settingsTab` (group, groups, scope, form, config, layout);
    `reposTab.editing` (+ scope, form, config while editing).
  - `dev.hub.action` (`set`, `apply`, `revert`, `confirm`, `restart` are aliases of `settings_set`, `settings_apply`,
    `settings_revert`, `settings_confirm`, `foreman_restart`): `settings_set {key, value (JSON, null = not set), repoId?}`, `settings_text {key, text,
    repoId?}` (as typed), `settings_focus {key}`, `settings_apply {confirm?}` (the shown tab's scopes; a widening
    change without confirm replies `ok:false, "confirm needed: …"`), `settings_confirm`, `settings_confirm_back`,
    `settings_revert`, `settings_group {group}`, `settings_reload {repoId?}`, `foreman_restart`, `team_select
    {agentId?}`, `team_back`, `team_on {agentId, on}`, `team_lead {agentId, inUse?, move?: -1|1}`, `repo_settings
    {repoId?}`, `repo_settings_done`; `press {button}` also presses the shown form's chips (`<form>:<key>[:<choice>]`,
    `group:<g>`, `team:on_team:<id>`, `team:lead_up:<id>`, …; see `*.form.chips`) and buttons `settings_apply`,
    `settings_revert`, `settings_confirm`, `foreman_restart`, `settings_retry`, `repo_edit_settings`. Foreman actions
    reply after the ack with `result{ok, message, unsupported, result}`.
  - Fakes (no Foreman side needed): `settings_fake {result:{file, settings:[SettingDef…], mcpServers?}, repoId?}`
    loads a config.get result into a scope; `settings_fake_agents {repoId, agents:[name | {name, path, description,
    model}]}` fakes repo.agents. Apply still sends a real config.set.
  - Screens: `hub_team`, `hub_settings`, `hub_settings_<group>`, `hub_repo_settings` (the first repo).
- Testing with the sim backend (`node tools/mac.mjs launch --backend sim --dev`, once the Foreman side is merged):
  `dev.hub.open {tab:"settings", group:"permissions"}` -> `settings_set {key:"claude.permissions.allow",
  value:["Bash(npm test)"]}` -> `settings_apply` (confirm bar: shoot it) -> `settings_confirm`; `settings_set
  {key:"claude.useClaudeLogin", value:true}` + apply/confirm -> the restart banner -> `foreman_restart` (watch
  `settingsTab.config.restarting` go false). Team: `dev.hub.open {tab:"team", agentId:"kit"}`, `settings_set
  {key:"claude.agents.kit.model", value:"default"}`, `team_on {agentId:"kit", on:false}`, `settings_set
  {key:"roles.kit", repoId:"demo", value:"<agent file>"}`, `settings_apply`. Repo: `dev.hub.open {tab:"repos",
  repoId:"demo", edit:true}`. Against an older Foreman the forms show "needs a newer Foreman"; drive them with
  `settings_fake`. Read-only: start the Foreman with token checking and the mod with `AGENTCRAFT_CLIENT_TOKEN=wrong`.
  Shoot each at GUI scale 2, 3 and 4 and check `layout.overflow`.

### HUD check-in (wave 2)
The contract is docs/WAVE2.md W5-W7 ("As implemented: hud" there). Code in `client.hud` and `client.hub`; pure rules
in `dev.agentcraft.hud` (`AlertLine`, `HudPrefs`, `HudRules`, tests `AlertLineTest`, `HudRulesTest`).
- **Alert line** (`GoalBar.drawAlerts`, counts from `Alerts.line()` over an `AlertCounts` source: the Inbox's
  (`HubFeature.init`: `Alerts.setSource("inbox", …, Inbox::revision)`; `ForemanAlertCounts` before it is set):
  decisions, blocked, replies, PRs, hold; under the decisions badge, full / short / dots width by what fits, hub keycap
  at the end. `needsYou` = the Inbox's Needs you (the hold counts one), the inbox tab badge and the away toast. The whole goal bar is skipped while the HUD is hidden (F1, `dev.hud
  {hidden}`).
- **Goal bar with several open goals**: urgent pinned, else 8 s turns, "+N more" (`HudRules.pickGoal`).
- **`HudWatch`** (client tick, guarded as `hud.watch`): hub on screen (also under screens opened from it) ->
  `hubSeenAt` + `lastTab`; console on screen -> `repliesSeen`; the away check (join + every 2 min after 10 min away,
  `goal.digest` via `HubGoals.requestAway`, toast "Since you were away: …" with the hub key); the welcome card.
- **Files**: `<gameDir>/agentcraft/hub-hud.json` (per world: `lastTab`, `hubSeenAt`, `lastAwayToastAt`, `repliesSeen`,
  `welcomeDismissed`), next to `hub-seen.json` (same world key: save folder name, "multiplayer" otherwise).
- **DevBridge**:
  - `dev.hud.state` gains `hudHidden`, `hubKey`, `alert{source, visible, decisions, blocked, replies, prs, needsYou, hold,
    holdUntil, holdMessage, text, parts[{kind, family, full, short, dots}], drawn, level: full|short|dots|null,
    layout{needed, available, overflow, x, y, w, h, guiWidth, guiHeight, guiScale}}`, `goalBar{goalId, index, open,
    more, pinned}`, `away{world, hubSeenAt, lastAwayToastAt, awaySince, awayForMs, joinPending, lastCheckAt, checking,
    awayPending, lastAwaySince, lastAwayGoals, lastAwayText, hubTarget, lastTab, repliesSeen, welcomeDismissed,
    welcomeShownThisJoin}`, `lastToast`, `lastToastHint`.
  - `dev.away {minutes}`: sets `hubSeenAt` to that long ago, clears `lastAwayToastAt` and runs the check now; replies
    `{digest, toastShown, text, hint, away}` once the digest is in (needs the Foreman).
  - `dev.onboarding {reset?, show?, press?: open_hub|got_it}`: reset forgets the welcome dismissal for this world; show
    opens the card; press presses one of its buttons (a frame after show); replies
    with the away/onboarding state, `welcomeLayout{needed, available, overflow}` and `help{keys[], interactions[],
    foreman}`.
  - `dev.hub.open {tab: "status", view: "overview"|"help"}`; `dev.hub.state` gains `statusTab{view, layout{needed,
    available, overflow, scroll, maxScroll}, help}` and `tabs{badges{<tab id>: n}, needed, available, compact,
    overflow}`; button `help_welcome` (`dev.hub.action {action:"press", button:"help_welcome"}`) shows the card.
  - Screens: `welcome`, `hub_status_help`.
  - Faking the inputs: a hold with `dev.foreman.inject {message:{type:"foreman.status", status:{version:"dev",
    backend:"claude", auth:"ok", hold:{reason:"usage", until:<ms>, message:"5h limit"}}}}`; a reply with
    `{message:{type:"feed.add", item:{ts:<now ms>, kind:"message", text:"Done, see the PR", agentId:"marlow",
    to:"user"}}}`; a blocked task with `{patch:{task:"t1", set:{status:"blocked"}}}`; more goals with
    `{message:{type:"goal.upsert", goal:{id:"g90", text:"…", progress:0.3, status:"active", createdAt:<ms>,
    updatedAt:<ms>}}}`.

### Generated buildings (design form, plot marking, design progress)
The contract is docs/HUB.md "Generated buildings"; code in `dev.agentcraft.client.design` plus
`building.PlotMarker` / `PlotHud` / `GhostRenderer.submitPlot` and the pure `building.DesignSpec` (choices,
limits, presets, plot geometry; unit-tested in `DesignSpecTest`).
- Protocol mirror: `Protocol.DesignRequest/Design/Size3/DesignStatus/DesignUpsert`, `Snapshot.designs`;
  `ForemanState.designs()` (snapshot replaces, `design.upsert` fires `ForemanListener.onDesign`).
  Requests are built by hand as JSON (optional fields omitted, never null; `wings` 1 for single).
- `DesignFeature`: the form's model (`DesignForm`, kept across plot marking), `submit()` (validates,
  creates `Blueprints.userDir()`, sends `design.request`, reads `designId` from the ack's result,
  remembers the plot by design id), `cancel(id)`, the done flow (transition to done/failed from
  upserts, or a snapshot showing a design it saw running: toast; done: `ServerTasks.callOnServer(
  Blueprints::reload)`, select in the open hub or `takePendingSelect()` on the next `HubFeature.open`;
  the plot moves to the blueprint id). The Foreman's own "design ready / failed" notifies are dropped
  (`Toasts.addFilter`) in favour of these toasts.
- Plot marking: `PlotMarker` (client thread) uses `BuildPlacement.spot` (the placement look ray: the
  looked-at block's open neighbour dropped to the ground; nothing in reach = the feet), keys through
  `BuildingWizardFeature.onKey` (Enter, PgUp/PgDn, Backspace, Esc), `PlotHud`, and
  `GhostRenderer.submitPlot`. Starting placement cancels it and vice versa; leaving the level (or the
  dimension) cancels it. `DesignSpec.Plot.maxSize()` is in the template's frame (x along the entrance
  side: dx/dz swap for an east/west front); `placement(front, sx, sz, groundY)` gives the locked spot
  `{ox, oy, oz, turns}` (`turnsToFace`, centred with `rotatedSizeX/Z`, `oy = ground - groundY`).
- DevBridge:
  - `dev.design.open {fields?, reset?, parent?: hub|none}` opens the form (parent = the hub) after
    setting fields; `fields` = `{kind?, wings?, style?, materials?, features?: [..] | "a,b", size?:
    S|M|L|plot, maxSize?: {x,y,z} | [x,y,z] (an explicit limit, e.g. invalid to see the inline error),
    remix?, name?, notes?}` (values are kept as given, so bad ones show as errors).
  - `dev.design.submit {fields?}` presses "Design it": replies after the ack with `sent{designId,
    error}` + the state; on success the hub's Designs list is open with it selected.
  - `dev.design.state`: `form{open, focus, busy, kind, wings, style, materials, features, size,
    maxSize, plot, remix, name, notes, outDir, errors{field: message}, sendError, request (exactly as
    sent), buttons[{id, label, state}], layout{guiWidth, guiHeight, leftNeeded, leftAvailable, compact,
    overflow}}`, `foremanConnected`, `lastSent`, `lastReload`,
    `pendingSelect`, `designs[{id, status, step, blueprintId, loaded, size, previews, error, kind,
    wings, style, maxSize, name, hasPlot}]`, `plotsByBlueprint`, `plotsByDesign`, `plotMode`.
  - `dev.design.cancel {designId}` (replies after the ack: `ok`, `ackError`); `dev.design.place
    {blueprint, repos?}` = Place on the plot (with repos: placement mode locked on the plot, replies
    with the placement state).
  - `dev.plot.start {height?}` (= Fit a plot…), `dev.plot.corner {x, y, z, front?, confirm?: true,
    height?}` (y = the surface, feet level; `confirm:false` pins the corner as the looked-at one, for a
    shot of the rectangle; the second confirmed corner returns to the form with maxSize filled),
    `dev.plot.state` (`active, phase: first_corner|second_corner, first, hover, pinned, height, front,
    plot{min, max, x, z, height, front, dimension, maxSize, clamped}, last`), `dev.plot.cancel` (Esc).
  - Screens: `design_form`, `hub_designs`.
- End-to-end with the sim backend (`AGENTCRAFT_BACKEND=sim`, which fakes a design by copying the bundled
  workshop, 29×15×32, or campus2..4 for a group, and fails it when that exceeds `maxSize`):
  `dev.design.open {reset:true}` -> `dev.design.submit {fields:{size:"M", name:"Test hall"}}` -> poll
  `dev.design.state` until the design is `done` (a few seconds) -> `lastReload` says loaded and
  `dev.hub.state` has the blueprint selected -> `dev.hub.action {action:"place", repos:["demo"]}`.
  Failure path: `size:"S"` (24×14×24 < the workshop). Plot path: `dev.plot.start`, `dev.plot.corner`
  twice (at least 29 wide × 32 deep in the template frame, height >= 15), `dev.design.submit
  {fields:{size:"plot"}}`, wait for done, `dev.design.place {blueprint, repos:["demo"]}`, `dev.build.state`
  (origin/turns on the plot), `dev.build.confirm`.

### Blocks
All 16 blocks of the assets-src block contract are registered (`block.ModBlocks`) with block items
and the "AgentCraft Studio" creative tab (`block.ModItems`, translation key `itemGroup.agentcraft`).
Facing blocks face the placer (vanilla lectern rule), luminance follows the contract, thin or
shaped blocks are non-occluding with real shapes, and connectable panels compute up/down/left/right
from same-facing neighbours. Block entities (with a saved and synced binding string) exist on
monitor, task_board, decision_podium, merge_station, status_lamp, console_terminal and
memory_archive. Their BERs are empty Phase 3 hooks, except the monitor's placeholder (the agent's
name on the screen).

### Phase 2 gotchas
- **Render layers need no registration in 26.x**: every baked quad picks solid, cutout or
  translucent from its sprite's transparency (`ChunkSectionLayer.byTransparency`). There is no
  BlockRenderLayerMap any more.
- Resource paths must be lower case. An art build briefly shipped `monitor_corner_bl_L.json`, which the
  game refuses ("Non [a-z0-9/._-] character"), so monitors and task boards rendered purple/black. Fixed
  in assets-src (`_outer`); re-run `sync.py`, which removes files it put there before.
- Removing a registered entry (block, entity type) from an existing world triggers Fabric's "Missing
  content detected!" screen, which blocked unattended runs. `AutoWorld` now answers it for the HQ
  world only (Fabric makes a backup, then the world loads) and logs a warning.
- The entity dispatcher picks renderers per type, but at submit time it sends every
  `AvatarRenderState` to the player renderer: custom avatars need the mixin, or they lose their own
  nameplate and layers.
- World-space UI: a nine-slice plate and the sprites on top of it z-fight at the same depth, so
  overlays/text use the polygon-offset variant (`WorldUi.Layer.OVERLAY`, `WorldUi.submitText`).
- **Fully opaque world text is drawn in the solid pass**, before every translucent quad
  (`SubmitNodeCollection.canRenderAsSolid`: text alpha 255, no background). Submit order does not
  change that. A translucent plate (`RenderTypes.text(GUI atlas)`, the kit nameplate has alpha 220)
  is therefore drawn over every opaque text that's already in the depth buffer: a nearer plate lets
  the farther plate's text ghost through. With "Improved Transparency" all translucent quads go
  through OIT and ordering is lost entirely. Fix used for billboards: `WorldUi.Layer.SOLID`, a custom
  pipeline (world text shader, no blending, colour-only writes, cutout below 0.1 alpha) that draws
  plates opaque in the solid pass, so depth alone decides what is in front.
- Two billboards at the same camera distance are coplanar, and the polygon offset of text then lets
  plate A's text win over plate B even where B should cover it (text from two plates interleaves).
  `WorldUi.billboard(..., scale, nudge, ox, oy, oz)` pulls a billboard a fraction of its distance
  towards the camera and shrinks it by the same factor (a homothety about the eye: identical on
  screen, nearer in depth). `PlateLayout` gives every plate a rank nudge of 0.15 % per rank.
- `LevelExtractionEvents.END_EXTRACTION` (Fabric) runs after every entity render state was extracted
  and before anything is submitted: the place for a pass that needs all of them at once (the
  nameplate declutter). `CameraRenderState` already has `projectionMatrix` and `viewRotationMatrix`
  then (camera space looks down -Z).
- JSpecify `@Nullable` on a qualified nested type goes after the dot: `Anchors.@Nullable Bounds`.
- Git Bash rewrites a leading slash in an argument into a Windows path (the command `/agentcraft hq`
  arrived as `C:/Program Files/Git/agentcraft hq`). Use `devcli cmd "agentcraft hq"`; the slash is optional.
- A Foreman profile can only run once at a time. Parallel specialists must use their own `--profile`
  (and port).

#### Layout checks at the user's setup (wave 2, stream layout)
4K fullscreen with GUI scale auto (= 9) is **426x240 GUI px**. The dev client reproduces it exactly with `dev.window
{width:1278, height:720}` + `dev.review.guiScale {scale:0}` (auto picks 3 there: 1278/3 x 720/3); scales 2/3/4 at the
default 1920x1080 window give 960x540, 640x360 and 480x270. No panel layout depends on the scale value itself (it only snaps
scrolling to physical pixels in Diff/Library, caps nameplate size, and sizes TaskScreen's title at (s+1)/s, which is
smaller at 9 than at 3), so 3 at 1278x720 lays out like 9 at 3840x2160 (the shots are the worst case). Put the window back with `dev.window
{width:1920, height:1080}` + `dev.review.guiScale {scale:3}`. Overflow reports: `dev.hub.state` (`tabs`, `statusTab`,
`inboxTab.layout`, `reposTab/goalsTab/teamTab/settingsTab.layout`), `dev.hud.state alert.layout`, `dev.walk.state.ui`,
`dev.agents.card layout`, `dev.onboarding welcomeLayout`, `dev.design.state form.layout` (`scrolls` = the left column
scrolls). An `overflow` on a pane that scrolls (Inbox detail with `detail.flow`, Status > Keys & help) is expected.
Small-screen rules from that pass: the decision question shrinks to 2-5 lines (tooltip with the whole text), the
blueprint step's preview shrinks before its buttons leave the screen, the design form's left column scrolls, the
placement/plot panels move below the crosshair with shorter key rows, toasts never stack over the hotbar or those panels,
the agent card drops to one log row, the Buildings tab drops minor facts so its buttons stay inside the hub.

#### Inbox (wave 2, stream inbox)
The contract is docs/WAVE2.md W1-W4 (and its "As implemented: inbox stream"), the screen docs/HUB.md "Inbox"; code in
`client.hub` (`Inbox` = items, cache, read state and the public API, `InboxTab` = the pane, `AgentLogView` = the paged
agent log, `InboxDev` = DevBridge), `client.decisions.AnswerPanel` and the pure `dev.agentcraft.hub.InboxModel` +
`HubSeen` inbox marks (unit-tested in `InboxModelTest`).
- `Inbox.counts()` (the hud stream's HUD line and badges): `InboxModel.Counts(decisions, blocked, replies, prs, hold)`,
  `needsYou()`, `line(zone)`. Cached per Foreman revision, read-mark changes, buildings, world and second (answering
  marks expire on time); `decisions` equals `DecisionsFeature.waitingCount()`.
- Read state: `hub-seen.json` version 2, per world `inbox: {all, items{key: ts}, agents{id: ts}}`; an item is read
  when shown in the detail for 1.2 s; replies also by their agent's card (`Inbox.markAgentSeen`, the card's init and
  close) and their goal's own mark (the goal thread). `dev.goals.seen {reset}` keeps the Inbox marks;
  `inbox_reset_read` forgets them.
- **AnswerPanel** (W2): `DecisionScreen` (Options.SCREEN: numbered, Review diff, no merge confirm: 1-9 + the 350 ms
  arm guard it as before), the Inbox (Options.EMBEDDED: Merge and Reject ask twice), the goal thread (EMBEDDED with
  the message box as its text: button ids `answer:<id>:<opt|text|open>` unchanged) and the agent card (Merge/Reject
  twice, 2-line box; R still opens the full review screen; the old "press a row twice" option rows and the 1-4 keys are
  gone; when the card would not fit the screen with the panel and two log rows (a merge at ~240 GUI px) it shows the
  single Review button instead: `dev.agents.card` `layout{panelInline, neededWithPanel, needed, available, overflow}`). Held
  keys and OS repeats stay the host's (DecisionScreen passes `repeat`); hosts own SDL text input (`Host.textFocus`).
- Deep links: `DecisionsFeature.openPodium` (podium right-click) -> `Inbox.openPodium(building)`; the old scoped
  decision screen is `openPodiumScreen` (`dev.decision {podium, screen:true}`); `MonitorFeature.agentAt(level, pos)` +
  a filtered `StationInteractions` handler on the monitor (`MonitorFeature.opensInbox`: empty main hand or a
  non-block item; a block in hand and sneak keep vanilla use, so panels still place) -> `Inbox.openAgent`; console `/inbox
  [@agent]` (`ConsoleCommands.OpenInbox`), `/hub inbox`.
- DevBridge:
  - `dev.hub.open {tab:"inbox", filter?: all|needs_you|building:<id>|agent:<id>|podium:<id|home>, item?: key | decision
    id | task id, agentId?: id (the agent view)}`.
  - `dev.inbox.state` (no screen needed): `counts{decisions, blocked, replies, prs, hold, needsYou, line}`, `revision`,
    `filter`, `items[{key, kind, group, unread, ts, agentId, goalId, buildingId, title, detail, ref, open, podium,
    subKind, until}]`, and with the Inbox open `selected`, `mode` (list|detail|list+detail), `detailOpen`, `focus`,
    `note`, `reply`, `groups{needs_you, updates}`, `item`, `panel` (AnswerPanel: decisionId, armed, highlight,
    textFocused, requestChanges, text, confirmReject, confirmMerge, sending, note, buttons[]), `log` (agent view:
    entries, fetched, pages, more, loading, filling, gap, gapFills, full, retryInMs, error, unsupported, oldestTs,
    newestTs, scrollRow, viewRows, last[]), `chips[]`, `layout` (`needed`, `available`, `overflow`, `detail{flow,
    offset, max, bodyH, pinnedY, contentH, actionsInTopBar, fieldLines}`, `logRebuilds`, `tabStrip`). `overflow` with
    `detail.flow` = it does not fit but scrolls (fine); `overflow` without it is a bug. `dev.hub.state` has the same under `inboxTab`.
  - `dev.hub.action`: `inbox_filter {filter}`, `inbox_select {item}`, `inbox_back`, `inbox_mark_all_read`,
    `inbox_reset_read`, `inbox_answer {item?, option, text?}` (the panel's guards: Merge / Reject need a second call;
    Request changes with text sends), `inbox_answer_text {item?, text}`, `inbox_answer_press {item?, button:
    opt:<option>|diff|send|cancel}` (a button drawn last frame), `inbox_reply {item?, text?}` (reply / agent view; replies
    after the ack), `inbox_retry {item?}`, `inbox_open_task`, `inbox_open_card`, `inbox_open_thread`, `inbox_open_tasks`,
    `inbox_open_diff`, `inbox_open_decision`, `inbox_refresh_prs`, `inbox_log_older {item?}` (replies once the page is in),
    `inbox_log_scroll {rows}` (negative = up; up at the top loads older), `inbox_detail_scroll {rows}` (a flowing
    detail's column, in text lines; negative = up), `inbox_focus {field: reply|answer}`; `press
    {button}` also presses the Inbox's chips (`filter:all`, `filter:needs_you`, `filter:building`, `filter:agent`,
    `filter:podium`) and hub buttons (`inbox_mark_all_read`, `inbox_back`, `inbox_retry`, `inbox_reply_send`, ...);
    in a flowing detail (`layout.detail.flow`) a hub button scrolled out of the column is not registered, so `press`
    on it fails: use the action (`inbox_reply`, `inbox_retry`, ...) or `inbox_detail_scroll` first.
  - `dev.agent.log {agentId, older?}`: opens the agent view and replies once the first (or the older) page is in.
  - `dev.monitor.open {x, y, z}`: a monitor's right-click (the agent it shows, else the Inbox).
  - `dev.decision {podium:[x,y,z]}` now opens the Inbox on that podium (as the right-click); `screen:true` = the
    podium-scoped decision screen as before; `showAll` = the "All decisions" chip.
  - Screens: `hub_inbox`, `hub_inbox_<decision|reply|blocked|hold|pr|agent>` (the newest item of that kind; agent =
    the first agent's view).
- Making each kind with the sim (`node tools/mac.mjs launch --backend sim --dev`, then `dev.foreman.hold {on:true}`
  so nothing moves under the shot):
  - hold: `dev.foreman.inject {message:{type:"foreman.status", status:{version:"x", backend:"claude", auth:"ok",
    hold:{reason:"usage", until:<now+3600000>, message:"5h window at 100%"}}}}`;
  - blocked: `dev.foreman.inject {patch:{task:"t3", set:{status:"blocked", blockedReason:"npm test fails: 2 tests"}}}`;
  - reply: `dev.foreman.inject {message:{type:"feed.add", item:{ts:<now>, kind:"message", text:"Done: the parser is in.",
    agentId:"kit", to:"user", goalId:"g1"}}}`;
  - PR: `dev.foreman.inject {patch:{task:"t4", set:{status:"pr", pr:{url:"https://x/pr/4", id:4, status:"changes",
    checks:"failing", threads:{open:2, new:1}, updatedAt:<now>}}}}`;
  - decision: the sim script asks some; or `dev.foreman.inject {message:{type:"decision.upsert", decision:{id:"d90",
    agentId:"marlow", kind:"question", question:"Use a set or a list?", options:["Set","List"], status:"open",
    createdAt:<now>}}}`.

## Interaction rules (fix wave 1, stream ui)

docs/FIXWAVE.md, docs/AUDIT-2026-10-03.md. The pure rules live in `dev.agentcraft.ui.UiRules` (unit-tested,
`UiRulesTest`); the crash guard in `dev.agentcraft.ui.Guard` (`GuardTest`).

- **Pause (C6)**: every AgentCraft screen's `isPauseScreen()` is `ScreenPause.pauses()`: true for a jar in a
  normal launcher (singleplayer pauses like a vanilla menu), false in dev runs and with the DevBridge on
  (`AGENTCRAFT_PAUSE`, `dev.ui.pause`). While paused the integrated server still runs queued tasks and its
  connections (`IntegratedServer.tickPaused` -> `tickConnection`), so hub actions get their reply and a
  teleport moves the player at once, but block changes (Remove, podium state) are broadcast by the chunk tick
  and show only after the screen closes.
- **Agent NPCs**: targetable only while the player sneaks with an empty main hand
  (`ClientAgentEntity#isPickable`); that sneak + right-click opens the card, anything else is the item's
  use, and swings/mining go through to the block behind. Attacks on agents stay cancelled. While the agent
  is targetable both hands answer FAIL (`UiRules.agentUse`): vanilla goes on to the off hand after a FAIL
  on an entity, and the off-hand item (food, a shield) must not be used behind the card.
- **Lecterns**: `StationInteractions.onUse(block, filter, handler)`; the library takes a lectern only
  inside a recorded building box (or the dev HQ), without a book and with no book in hand.
- **Diff**: `DiffFeature` installs `DiffLink`'s opener (exact decision/worktree, parent); there is no
  fallback to the DevBridge `diff` screen (it reviews the oldest merge). `DiffScreen` answers only its own
  open merge on the shown repo/worktree, through `DecisionsFeature.answer`; Ctrl+Enter arms the same
  confirm as the Merge button. A confirm by key (Enter on Confirm merge, X/Enter on Confirm reject) needs
  a fresh press at least 300 ms after arming (`UiRules.keyConfirmReady`); OS key repeats and keys held
  since the screen opened are ignored for Enter/X (`UiRules.KeyRepeat`, as the decision screen does), so
  a held or double-tapped Ctrl+Enter never merges. Through the DevBridge, wait 300 ms between the two
  `dev.key` presses.
- **Parents**: `DiffScreen`, `ConsoleScreen`, `AgentCardScreen`, `DecisionScreen` and `TaskScreen` take a
  parent (`withParent`, `HasParent`): Esc and a finished answer return there.
- **Agent card**: Review/Answer/Decide for any open decision of the agent (factories registered for every
  decision kind: merges -> `DiffScreen`, others -> `DecisionScreen`), "filed for you" lines open theirs,
  answers go through `DecisionsFeature.answer`, Message opens the console returning to the card, Stop
  asks for a second press. `AgentsFeature.openCard(id, parent)` opens it from the Team tab, a task's
  assignee and the console roster.
- **Free text (C1)**: `Decision.textAllowed` (absent = true; always true without options) hides the text
  box in the decision screen and the goal thread's "Answer with text"; `/answer` refuses text for them.
- **Crash guards**: client tick, level-render (nameplates, ghost/plot, card outline) and HUD handlers run
  through `Guard.run(kind, ...)`: a failure is logged once per kind, counted, and the game keeps running.
- **Teleport (C7)**: `HubActions.teleportAllowed` (commands allowed, or creative/spectator), checked by the
  hub button and on the server.
- **Keys**: the building wizard key is unbound by default (B clashed with Xaero); the hub's Place new
  stays. An existing `options.txt` keeps whatever it saved.
- **Enter**: single-line inputs send on Enter (Ctrl+Enter too; Shift+Enter = new line); multi-line inputs
  make Enter a new line and send on Ctrl+Enter (`TextKeys.enter`).
- **Console**: plain text asks "Create a goal …? Enter again" (the second Enter creates it; `/goal`
  skips; with several repos the repo chooser is the confirm); a held Enter (OS key repeat) never counts
  as that second Enter. A console opened at a terminal sends goals to its building's repo.

## Tools (repo `tools/`, Node 22, local `ws` dependency: run `npm install` in tools/ once)

```bash
node tools/devcli.mjs wait                         # wait for the bridge + a ready world (300 s)
node tools/devcli.mjs state
node tools/devcli.mjs camera -4 72 -8 -37.4 13.1 70        # x y z yaw pitch [fov]
node tools/devcli.mjs camera 20 66.5 20 80 --look 9,67,9   # aim at a point
node tools/devcli.mjs shot my_shot [--hud] [--frames 5] [--no-wait-chunks]
node tools/devcli.mjs cmd "/fill 0 65 0 4 69 4 minecraft:oak_planks"
node tools/devcli.mjs time 12000 | weather clear | hud off | screen pause | key escape | type "hi"
node tools/devcli.mjs raw '{"type":"dev.waitChunks","timeoutMs":60000}'
node tools/devcli.mjs quit                         # saves; waits for the bridge to close
node tools/shoot.mjs tools/scenes/phase1.json [--only a,b] [--manifest out.json] [--verbose]
node tools/record.mjs tools/shots/hq_orbit.json [more.json] [--hold 3000] [--stills 1,3] [--window 2560x1440]   # play shots for OBS (tools/shots/README.md)
```
`devcli` prints the JSON reply and exits 1 on `ok:false`. `--port` overrides the port. `--timeout`
sets the connect timeout in seconds. `devcli release` hands the view back after dev camera use.
`tools/lib/devclient.mjs` exports `DevClient` (`connect`, `request`, `call`, `waitInWorld`,
`waitClosed`, `health`, `assertNotHung`) for other scripts. `health()` uses dev.ping and works on a
hung game; `waitInWorld` (so `devcli wait` and `shoot.mjs`) fails fast with "game is hung" once the
render thread has been stuck for 30 s, and `shoot.mjs` stops at the first failed shot if the game
looks hung (`hung` in its JSON summary) instead of timing out on every remaining shot. Relaunch by
`devcli quit` (force-exits a hung game after 15 s) and `gradlew runClient`.

Scene format for shoot.mjs (see `tools/scenes/phase1.json`): `{defaults, setup:[cmds], shots:[{name,
camera:{x,y,z,yaw,pitch | lookAt, fov}, time?, weather?, commands?, screen?, keepScreen?, hideHud?,
frames?, delayMs?, waitChunks?}]}`, or a bare array of shots. Shots are written to `artifacts/shots/<name>.png`.
A shot without `fov` renders at the player's FOV option (70), exactly; scenes no longer inherit the
previous shot's FOV.

## Finding Minecraft APIs

```bash
GRADLE_USER_HOME=C:/Projects/agentcraft/.gradle-home ./gradlew mcSources   # genSources + unpack into mod/build/mcsrc
grep -rn "class LevelRenderer" mod/build/mcsrc/net/minecraft
```
`gradlew clean` deletes `mod/build/mcsrc` with the rest of `build/`; run `mcSources` again after a
clean (about a minute).
Fabric API module sources are in the official maven, for example
`https://maven.fabricmc.net/net/fabricmc/fabric-api/<module>/<version>/<module>-<version>-sources.jar`.
The module versions are listed under `.gradle-home/caches/modules-2/files-2.1/net.fabricmc.fabric-api/`.
`fabric-client-gametest-api-v1` is a good reference for world creation, screenshots and chunk waiting in 26.3.

## Timings (this machine, RTX 4090)

| Step | Time |
|---|---|
| Cold `gradlew build`: fresh copy of `mod/` (no `build/`, `.gradle/`), **empty `.gradle-home`** (the real one moved aside, then restored), `--no-daemon`; downloads Gradle 9.7.1, Loom, MC and Fabric API, about 580 MB (`artifacts/logs/fix-coldbuild.log`) | 61 s |
| `genSources` (first time) | 72 s |
| Incremental `gradlew build` (warm daemon) | 2-4 s |
| `runClient` to ready world, first ever run (Gradle configure + world creation) | 48 s |
| `runClient` to ready world, warm daemon, existing world | 14-19 s |
| `runClient` to ready world, warm daemon, fresh run dir and new world | 17 s |
| `dev.quit` to JVM exit | 1-2 s |
| `dev.quit` on a hung render thread (`forceAfterMs` 5000) to JVM exit, world saved | 5.9 s |
| Screenshot (chunks already loaded) | about 0.4-0.8 s |
| Screenshot after a 6000-block teleport (waits for 16-chunk radius) | about 5 s |
| Soak: 90 camera moves and shots (18 of them far teleports) in 3 min | 0 failures, 59-60 fps (vsync) |
| Soak after the fix round: 42 verified camera moves + shots (6 far teleports, mixed modes/FOVs) | 68 s, 0 failures, 59-60 fps |

## Known issues

- **One JVM crash in about 8 sessions** (Temurin 25.0.1): `EXCEPTION_ACCESS_VIOLATION` in the *C2 JIT compiler
  thread* while compiling `LevelExtractor::extract`, about 34 s after launch (`artifacts/logs/hs_err_pid104408.log`).
  No third-party DLLs were loaded and no mod code was on the stack. Repeating the exact same sequence
  and a 3-minute soak did not reproduce it. If it recurs, tools should treat "bridge gone" as "relaunch"
  (the world autosaves). Possible mitigations, both untested: a newer JDK 25 update, or
  `-XX:CompileCommand=exclude,net/minecraft/client/renderer/extract/LevelExtractor.extract`, which costs
  per-frame speed. A shot that is in progress when the JVM dies leaves a 0-byte PNG.
- **An unexplained pause menu** appeared once during an unattended run (`focused` flipped to false at the
  same moment, with `pauseOnLostFocus=false`). In 26.3 only a real Escape key event or focus-pause opens
  it. A pause menu stops the integrated server, so chunks stop loading. Mitigations now in place:
  `dev.state.paused`; `dev.camera` closes a vanilla pause menu (`closedPauseScreen:true`; disable with
  `closePause:false`); chunk waits fail with a clear "game is paused" error; and every pause-screen
  open is logged with a short stack ("Pause screen opening ...") so the source can be found next time.
- `width`/`height` for `dev.screenshot` are not implemented. Shots are always the window framebuffer size (1920x1080).
- Rain fades in over a few seconds after `dev.weather rain`.
- A forced `dev.quit` (exit code 3/4) skips the client's own shutdown (options, resource cleanup); the
  world itself is saved by the integrated server first. The exit-code-4 path (a normal shutdown that
  hangs) has not been observed or tested; the exit-code-3 path was tested with `dev.test.stall`.
- `dev.key` sends keycode 0 alongside the scancode, so shortcuts that depend on the keycode (such as
  Ctrl+C in text fields) may not work through it.

## Gotchas hit

- `type`, `id` and `timeoutMs` are reserved request fields. `dev.weather` first used `type` for the
  weather kind, which the message type silently overwrote. It is now `weather`.
- **A NaN camera hangs vanilla forever.** `Frustum.offsetToFullyIncludeCameraCube` loops until the
  camera cube is inside the frustum; with a NaN view vector that never happens, so the render thread
  spins and the game is dead (dev.quit could not even run). Gson's `getAsFloat()` happily parses the
  string `"NaN"`, and `pitch < -90 || pitch > 90` is false for NaN. All request fields now go through
  `Fields`, which refuses non-finite numbers. Huge-but-finite values are dangerous in the same loop
  (doubles lose the precision to move the camera), so y is limited like vanilla `/tp` and x/z to the
  world border.
- Gson's lenient getters are traps for a protocol: `getAsString()` on `["dev.state"]` returns
  `"dev.state"`, `getAsBoolean()` on `"yes"` returns false, `getAsLong()` on `1.5` truncates. Use `Fields`.
- Vanilla multiplies the FOV option by a dynamic modifier (1.1 while flying, which spectators always
  are). Shots requested at `fov:70` used to render at 77; `tools/scenes/phase1.json` was updated to 77/88
  to keep its framing, and the dev camera now pins the exact FOV (`CameraMixin` on `Camera.calculateFov`).
- Right after joining, `inWorld` is true while `LevelLoadingScreen` is still showing. Wait for `ready`.
- Waiting only for chunks near the camera left a square horizon after long teleports. Screenshots now
  wait for the whole render distance (circular, radius rd-1, matching the server's chunk tracking).
- `hasRenderedAllSections()` is true right after a teleport, before new sections are queued. The
  waiter therefore requires the condition to hold for 5 consecutive frames, after at least 6 frames.
- Chunk fade-in (new in 26.x, default 0.75 s) would make freshly loaded chunks look translucent in
  shots. It is set to 0 in the options template.
- Static `start()` and `stop()` names clash with `WebSocketServer`'s own methods, hence `startBridge` and `stopBridge`.
- Log noise that is harmless in dev: `Could not authorize you against Realms server` (offline dev
  account) and `Requested post effect does not exist: minecraft:end_of_frame`. The second is printed
  by vanilla 26.3 in dev, not by this mod.
- Don't set `setReuseAddr(true)` on the WebSocket server. On Windows that would allow two games to
  bind the same port.
- `mod/run/` is gitignored, and the server run dir is `mod/run/server`, so it is ignored too.
- **Git Bash rewrites arguments that start with a slash.** Run from Git Bash (MSYS),
  `node tools/devcli.mjs type "/status"` types `C:/Program Files/Git/status`, and the console sends
  that as a new goal. Set `MSYS_NO_PATHCONV=1` for console commands, or drive the DevBridge from
  PowerShell. This happened in the claude e2e run. The lead asked what the goal meant instead of
  starting work on it.
