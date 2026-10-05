# tools/

## macOS

Requires Node 22+, git, and Java 25. Install Java with `brew install openjdk@25`;
`mac.mjs` uses Homebrew's JDK directly, so no system Java changes are needed.

```sh
node tools/mac.mjs launch --backend sim             # free simulated team
node tools/mac.mjs stop --profile sim
node tools/mac.mjs launch --repo /path/to/repo --use-claude-login
node tools/mac.mjs stop                            # save/quit game, stop Foreman
node tools/mac.mjs stop --dry-run                  # print what would be killed, change nothing
node tools/mac.mjs launch --restart-foreman        # replace a Foreman running old code
node tools/mac.mjs launch --backend sim --dev --world "Docs World" --preset normal   # natural terrain
```

**Which world the dev client opens.** By default AutoWorld loads (or creates) the flat "AgentCraft HQ"
studio. `--world NAME` opens that save instead (created if missing), `--preset flat|normal` picks the
terrain of a new world (`normal` = natural terrain: creative, peaceful, cheats on, no structures) and
`--seed N` its seed (default for `normal`: 2026, a birch meadow on a hill with forest, lakes and a cherry
grove nearby). Preset and seed only matter when the world is created; delete `mod/run/saves/NAME` to
regenerate (the per-world AgentCraft files under `mod/run/agentcraft/`, such as the welcome card's
dismissal and the Inbox read marks, are keyed by the save name and survive that: `dev.onboarding {reset:true}`
brings the card back). Only the name "AgentCraft HQ" gets the HQ game rules and studio, so any other world behaves
like a player's own world (the welcome card shows while it has no buildings). The flags set
`AGENTCRAFT_AUTOWORLD_NAME`, `AGENTCRAFT_AUTOWORLD_PRESET` and `AGENTCRAFT_AUTOWORLD_SEED` for the game
(mod/DEV.md "Environment switches"). A running game keeps its world: stop it first (`stop --game`).

The launcher installs npm dependencies on first use, runs the Fabric development client,
and waits for the studio world. It reuses a running Foreman or game from the same profile.
Use `--dev` for mute/no focus/no notifications; `--no-game` or `--no-foreman` to run just
one component (`--no-foreman` also turns the mod's Foreman launcher off, so no Foreman runs at all);
`--mod-foreman` to start no Foreman here and let the mod start one itself (its Foreman launcher, docs/HUB.md
"Foreman launcher": the way a jar in Prism gets its Foreman); `--no-wait` to return immediately while Minecraft builds. Repeat
`--foreman-arg VALUE` to pass extra Foreman options. Logs and process records live in
`artifacts/logs/mac-*.log` and `artifacts/run/mac-*.json`. `stop` only signals processes
recorded by this launcher. macOS uses Notification Center for agent decisions.
The screenshot QA command, `node tools/qa.mjs`, also uses this launcher on macOS.
Logs are checked when a process is started. A log over 5 MB is copy-truncated and three
copies are kept (`.1` .. `.3`). A Foreman still writing to the file is unaffected. A process that
runs for weeks can grow its log past 5 MB until the next start.

## Playing in a Hardcore world (Prism Launcher, macOS)

Dev runs (`mac.mjs`) use the dev checkout, profile `claude`/`sim` and ports 7878/7879. The
everyday game is different: a jar in a Prism instance, and **the mod starts the Foreman itself** when the
game starts (its Foreman launcher, docs/HUB.md "Foreman launcher"), so Prism needs no hook and no
terminal is involved. That Foreman runs from a **stable checkout** (default: `agentcraft-stable` next to your clone,
e.g. `~/code/agentcraft-stable` for `~/code/agentcraft`), not from your dev checkout. The dev
checkout has many worktrees, the Foreman works on it as a repository, and agent merges land in its `main`. The stable Foreman uses its own profile and port
(`hardcore`, 7880), so it never collides with a dev run.

**Setup (once, and again to update):** quit Prism and the game first, because setup rewrites
`instance.cfg` and swaps jars in `mods/`.

Tell it which Prism instance to use, either with `--instance` on every run or once in the
optional `hardcore` section of the Foreman config (`~/.agentcraft/config.json`, or `<--home>/config.json`).
Flags win over the config; every key is optional except that an instance must come from one of
the two:

```json
{
  "hardcore": {
    "instance": "~/Library/Application Support/PrismLauncher/instances/My Hardcore World",
    "stable": "~/code/agentcraft-stable",
    "backupScript": "~/bin/backup-world.sh",
    "backupDir": "~/MinecraftBackups/My Hardcore World",
    "profile": "hardcore",
    "port": 7880
  }
}
```

`backupScript` is your own world backup script, called as `SCRIPT <game dir> <backup dir>`;
without one, setup notes that the world saves are not backed up (instance.cfg and `mods/` still are).

```sh
node tools/hardcore-setup.mjs --no-prelaunch                    # dry run: prints every change, changes nothing
node tools/hardcore-setup.mjs --no-prelaunch --apply            # do it
node tools/hardcore-setup.mjs --no-prelaunch --apply --ref v0.2 # pin the stable checkout to a tag/branch/commit
```

`--no-prelaunch` is the recommended setup: the mod starts the Foreman. Without it the script sets up the
older **daemon path** (Prism's PreLaunchCommand runs `tools/foreman-daemon.sh start`), which still works
and can live side by side with the mod's launcher (whichever starts first wins; see "How the Foreman
starts"). Running setup with `--no-prelaunch` on an instance set up the old way removes our
PreLaunchCommand and unwraps the PostExitCommand.

What `--apply` changes (defaults; see `--help`):

| what | change |
| --- | --- |
| `<stable>` (default `../agentcraft-stable`) | cloned from this repository (or fetched), `--ref` (default `main`) checked out detached; refuses local changes, and refuses a ref without the launch tools: with `--no-prelaunch` the mod's Foreman launcher (`mod/.../client/launcher/Launcher.java`) and `tools/foreman-daemon.mjs`, else `tools/foreman-daemon.sh`/`.mjs` (the PreLaunchCommand points there) |
| the `hardcore` Foreman | stopped first when it runs from the stable checkout and the checkout or its `node_modules` are about to change; it starts again with the next game launch |
| its `foreman/`, `tools/` | `npm ci` |
| its `mod/` | `gradlew build`, giving `mod/build/libs/agentcraft-<version>.jar` |
| `~/MinecraftBackups/<instance>/` | world saves via the backup script, when one is set; `agentcraft-setup-<stamp>/` with `instance.cfg` and the whole `mods/` folder on every run; on the first run also `agentcraft-setup-original/`, the pre-AgentCraft state, which is never overwritten |
| `<instance>/.minecraft/mods/` | older `agentcraft*.jar` removed (they are in the backup) and the new jar copied in |
| `<instance>/instance.cfg` | `JvmArgs` gains `-Dagentcraft.port=7880 -Dagentcraft.profile=hardcore -Dagentcraft.foreman.dir=<stable>` (other args kept; the stable path cannot contain spaces); with `--no-prelaunch` no `PreLaunchCommand` (ours is removed, someone else's kept), else `PreLaunchCommand="<stable>/tools/foreman-daemon.sh" start --profile hardcore --port 7880 --home "<home>"`; `OverrideCommands`/`OverrideJavaArgs=true`; `PostExitCommand` (the world backup) kept. When the instance used Prism's global commands or JVM args (override off), their `PostExitCommand`, `WrapperCommand` and `JvmArgs` are copied in from `prismlauncher.cfg` (`--prism-cfg` if it is elsewhere) |

Running it again changes only what is out of date. It refuses when Prism or the game is running
(a java process whose command line or working directory is in the instance), when the
instance (or Prism's global settings, without the override) already has a PreLaunchCommand that
is not ours (the daemon path only), when the ref has no launch tools, when the Minecraft versions differ, and
when `--stable` sits inside a repository listed in `~/.agentcraft/config.json`.

Options: `--stop-on-exit` stops the Foreman when the game exits. With `--no-prelaunch` it becomes
`-Dagentcraft.launcher.stop.on.exit=1` (the mod stops the Foreman it started as the game closes; the
PostExitCommand backup runs after the game as always). On the daemon path the backup still runs first,
because the PostExitCommand becomes `foreman-daemon.sh after-exit ... -- <backup command>`.
Without the option the Foreman keeps running after you quit, so agents keep working and PR polling
continues; running setup again without the option removes it. `--devbridge [--dev-port 7881]`
turns the DevBridge on in this instance, for scripted checks in a copy of the instance. In a
Hardcore world the DevBridge answers only `dev.help`. `--instance`, `--stable`, `--profile`,
`--port`, `--home`, `--backup-script` and `--backup-dir` override the defaults and the config.

**How the Foreman starts (the mod):** when the game has started, the mod's Foreman launcher (on its
own thread, never the render thread) reads the `launcher` section of `<home>/config.json`, picks the
checkout (`-Dagentcraft.foreman.dir`, which setup writes; else `launcher.foremanDir`, `hardcore.stable`,
or the checkout the jar was built from), and probes the port with `hello` and the client token. A
current Foreman there (same checkout, same commit) is reused, whoever started it. One the game started
earlier that runs older code (another commit or checkout) is restarted. One it did not start (Prism's
daemon, `mac.mjs`, a terminal) is never stopped, even when older: the Status tab says "running (older
version)" and who started it. When nothing answers, it finds node 22+ (`launcher.nodePath`, PATH,
Homebrew, mise/volta/nvm/asdf/fnm, then your login shell), runs `npm ci` in `foreman/` once when
`node_modules` is missing (log: `<stable>/artifacts/logs/foreman-launcher-npm.log`), and starts the
daemon's command line (`node --import tsx src/main.ts --backend claude --profile hardcore --home <home>
--port 7880`) detached through node, so the game's exit or a signal to the game never reaches it. The
Foreman gets your login shell's environment (`$SHELL -lic`, an interactive login shell like the daemon's
`/bin/zsh -lic`, so `~/.zshrc` counts; `$SHELL -lc` as the fallback): its PATH (node's folder first, then mise, `dotnet@8`, Homebrew's `az`/`gh`/`cargo`,
`~/.local/bin`) and variables such as `DOTNET_ROOT`. Nothing secret goes on the command line: the
Foreman reads its own config (`useClaudeLogin` etc.). It records what it started in
`<home>/<profile>/launcher.json` (pid and start time, so a reused pid is never mistaken for it) and in
the checkout's `artifacts/run/mac-foreman-<profile>.json`, the run file `foreman-daemon.sh` and
`mac.mjs stop --foreman` read, so those tools see and can stop it too. The Foreman keeps running after
you quit (PR polling continues) unless `launcher.stopOnExit` (or `--stop-on-exit`) is set, and then
only if the game started it.

```json
{
  "launcher": {
    "enabled": true,
    "foremanDir": "~/code/agentcraft-stable",
    "nodePath": "/opt/homebrew/bin/node",
    "stopOnExit": false
  }
}
```

All keys are optional (defaults shown, `foremanDir`/`nodePath` unset). Environment variables or `-D`
properties override them: `AGENTCRAFT_LAUNCHER=0|1` (`-Dagentcraft.launcher`),
`AGENTCRAFT_FOREMAN_DIR` (`-Dagentcraft.foreman.dir`), `AGENTCRAFT_NODE` (`-Dagentcraft.node`),
`AGENTCRAFT_LAUNCHER_STOP_ON_EXIT` (`-Dagentcraft.launcher.stop.on.exit`). The Foreman's backend is
`AGENTCRAFT_BACKEND` (default `claude`), its profile `-Dagentcraft.profile` (default: the backend's name),
its port `-Dagentcraft.port` (default 7878). A launcher-started (non-dev) game refuses to run the Foreman
from a checkout listed in config.json `repos`, as the daemon does.

**How the Foreman starts (the daemon path, optional):** Prism runs the PreLaunchCommand and waits for it, and a non-zero exit
aborts the launch. Prism 11.0.3 does no shell parsing: it splits the command on double quotes
(`launcher/launch/steps/PreLaunchCommand.cpp`). `foreman-daemon.sh start` therefore returns 0
immediately and does the work in the background. The background work goes through
`/bin/zsh -lic`, so the Foreman gets your terminal's PATH even though Prism inherits launchd's
bare one. That PATH covers mise's node, `dotnet@8`/`DOTNET_ROOT`, Homebrew's
`az`/`gh`/`cargo` and `~/.local/bin`. If the stable checkout's Foreman is already running at the
same commit, nothing happens. If it runs older code or comes from another checkout, it is
restarted. If something else holds the port, such as a dev Foreman, it is left alone and the
error goes to the log. The mod's launcher stays on next to it: the daemon usually wins the race (the
mod then reuses its Foreman); when the mod wins, the daemon finds the mod's run file and does nothing.

**Day to day:** the hub's **Status** tab (`H`) has a Launcher section: its state (off, Node.js not
found and how to install it, installing, starting, running: started by the game or reused, pid,
version, commit, log; crashed with the log's last lines; running (older version); could not start),
with **Start**, **Restart** (only for a Foreman the game started) and **Open log**. A crash or a
failed start also shows a toast. From any terminal (the scripts find their own checkout):

```sh
<stable>/tools/foreman-daemon.sh status    # JSON: running, pid, port, commit, stale
<stable>/tools/foreman-daemon.sh stop
<stable>/tools/foreman-daemon.sh restart   # e.g. after `claude` /login
<stable>/tools/foreman-daemon.sh start --wait   # start in the foreground, see errors
node <stable>/tools/mac.mjs stop --foreman --profile hardcore   # same as stop
```

Logs: `<stable>/artifacts/logs/foreman-launcher-hardcore.log` holds the output of a Foreman the
mod started; `foreman-daemon-hardcore.log` that of the daemon path. Both are checked at every start and rotated past 5 MB, with 3 kept. In game, the top-right pill
follows the launcher until the link is up ("Starting the Foreman…", "The Foreman stopped: see the
hub's Status tab"). After a reboot, nothing starts the Foreman until the next game launch, Start in
the Status tab, or `foreman-daemon.sh start`.

**Updating:** run `node tools/hardcore-setup.mjs --apply` again, optionally with `--ref`. It
fetches `--ref` from the source and checks it out in the stable checkout. Run from the dev
checkout, the source is the dev checkout. Run from the stable checkout, the source is its
`origin`, which is the dev checkout it was cloned from. `--ref` is resolved with
`git ls-remote`, so a dry run shows the commit that would be installed. The default `main` is
the local `main`, which includes agent merges that have not been pushed; pass a tag or a commit
to pin one. A running `hardcore` Foreman is stopped before the checkout and `npm ci` (the dry run
lists it), because it runs from that checkout's code and `node_modules`; it starts again with the
next game launch, or right away with `foreman-daemon.sh start`. Merge the launch tools
(the mod's Foreman launcher, `tools/foreman-daemon.sh`) into the ref you install: setup refuses a ref without them.

**Rollback:** setup prints the exact commands. In short, quit Prism and copy
`~/MinecraftBackups/<instance>/agentcraft-setup-original/instance.cfg` over
`<instance>/instance.cfg`. Then remove `mods/agentcraft*.jar` and run `foreman-daemon.sh stop`.
The original has no AgentCraft jar, so there is nothing to copy back. Use an
`agentcraft-setup-<stamp>/` folder instead to return to an earlier AgentCraft version. World saves are restored from your backup script's archives
as usual.

## Windows

Windows PowerShell 5.1+ and Node 22. `launch.ps1` installs the npm dependencies it needs on the
first run (`npm ci` in `foreman/` and `tools/`); the Gradle wrapper downloads Gradle, Minecraft
and Fabric by itself. Java 25 must be installed (Temurin 25: https://adoptium.net).

## Daily use

```powershell
tools\launch.ps1                              # claude backend, state in ~/.agentcraft, Foreman :7878, DevBridge :7879
tools\launch.ps1 -Repo C:\code\life-tracker   # also register a repo with the Foreman
tools\launch.ps1 -Backend sim                 # scripted demo team (no API calls), demo repo in sandbox/
tools\launch.ps1 -Showcase                    # static showcase state (sim); -Showcase late for the later one
tools\stop.ps1                                # stop what launch.ps1 started (game + Foreman)
tools\stop.ps1 -Game                          # just the game: agents keep working, relaunch any time
```

From `cmd.exe` or Explorer: `tools\launch.cmd` / `tools\stop.cmd` (same arguments).

`launch.ps1`:
1. Reuses a running Foreman for `<home>/<profile>` (its `foreman.json` pid alive + port answering),
   otherwise starts one in the background (hidden console; log `artifacts\logs\foreman-<profile>.log`).
2. Builds if needed and starts Minecraft (`gradlew runClient`, `GRADLE_USER_HOME` =
   `<repo>\.gradle-home`), passing `AGENTCRAFT_PORT`, `AGENTCRAFT_DEV_PORT`, `AGENTCRAFT_HOME`,
   `AGENTCRAFT_PROFILE`, `AGENTCRAFT_MUTE=0`, `AGENTCRAFT_FOCUS=1` to the game. Log: `artifacts\logs\game.log`.
3. Waits until the HQ world is ready and prints what runs where and how to stop it.

If the game of this checkout is already running it is reused (one client per checkout: they share
`mod/run`). Quitting the game window leaves the Foreman running (agents keep working); the next
`launch.ps1` reuses it.

| parameter | default | |
| --- | --- | --- |
| `-Backend sim\|claude` | `claude` (`AGENTCRAFT_BACKEND`) | `-Showcase` implies `sim` |
| `-Repo <path>[,<path>]` | | registered at start, or sent as `repo.add` to a running Foreman |
| `-Profile <name>` | backend name; `showcase` / `showcase-late` | state lives in `<home>/<profile>` |
| `-Showcase [busy\|late]` | | hold a static scripted state (QA screenshots); always a fresh (`--reset`) profile |
| `-Home <dir>` | `~/.agentcraft` (`AGENTCRAFT_HOME`); with `-Dev`: `<main checkout>\.agentcraft-home` | **QA/tests must pass the project home** |
| `-Port N` / `-DevPort N` | 7878 / 7879 (`AGENTCRAFT_PORT` / `AGENTCRAFT_DEV_PORT`) | 3000/5173/8080 are refused |
| `-Dev` | | unattended runs: muted, never steals focus, no toasts, Gradle daemon exits after 30 idle min |
| `-Reset` | | wipe the profile before starting (new Foreman only) |
| `-Speed x`, `-Autostart`, `-Goal "..."` | | sim speed / start the scripted goal / submit a goal at start |
| `-ForemanArgs @('--workers','kit,wren')` | | extra Foreman flags (`npm run start -- --help`) |
| `-Notify` / `-NoNotify` | Foreman default (on for claude) | Windows toasts |
| `-NoGame`, `-NoForeman`, `-NoWait` | | only the Foreman / only the game / don't wait for the world |
| `-GradleHome <dir>` | `GRADLE_USER_HOME` or `<main checkout>\.gradle-home` | |
| `-TimeoutSec N` | 600 | how long to wait for the world |
| `-SummaryJson <file>` | | machine-readable result (what was started or reused, pids, ports, logs) |
| `-DryRun` | | print the Foreman command, the game command and the game env; start nothing |

`stop.ps1` stops only what `launch.ps1` started, using the run files in `artifacts\run\`
(pid + process start time, so a reused pid is never touched): the game via the DevBridge
`dev.quit` (world saved), the Foreman via Ctrl+Break into its hidden console (the Foreman saves
its state and releases `foreman.json`, like Ctrl+C in a terminal), then, after `-TimeoutSec`
(30), a force-kill of only those process trees. A Foreman that `launch.ps1` reused but did not
start is left alone. `-Game` / `-Foreman` / `-Profile` / `-Home` / `-Port` narrow it down;
`-FromSummary <launch summary>` stops exactly what one launch started; `-StopDaemon` also stops
this checkout's Gradle daemon (never another checkout's).

## Dev / QA tools

```powershell
node tools/devcli.mjs state --port 7889                 # DevBridge CLI (mod/DEV.md has the full command list)
node tools/foremancli.mjs status --port 27878           # Foreman: backend/auth, agents, tasks, open decisions
node tools/foremancli.mjs diff --decision d3 --port 27878
node tools/foremancli.mjs send user.message to=kit "text=hi there" --port 27878   # any client message, prints the ack
node tools/shoot.mjs tools/scenes/qa.json --only qa01_exterior_hero --port 7889 --foreman 27878 --prefix wip/
node tools/qa.mjs --port 27878 --dev-port 7889 --home <checkout>/.agentcraft-home
node tools/record.mjs tools/shots/desk_story.json --port 7889 --hold 3000   # play a camera shot for OBS (shots/README.md)
npm test --prefix tools
```

### Smoke test (`npm run smoke`, macOS)

`node tools/smoke.mjs` (or `npm run smoke --prefix tools`) is the end-to-end check of the main flows in a real game:
it launches the dev client with `mac.mjs` against the sim backend (profile `smoke`, Foreman :7978, DevBridge :7979,
a new temp Foreman home, `--sim-pr --pr-watch on`, user "Sam") in a fresh natural-terrain world (`mod/run/saves/Smoke`
and that world's entries in `mod/run/agentcraft/*.json` are deleted first), drives it through the DevBridge with
assertions and screenshots, then stops it. About 2-3 minutes once Minecraft is built. Steps, each independent and
logged (a step whose prerequisite failed is skipped):

| step | checks |
| --- | --- |
| `preflight` | world, sim backend, the two demo repos, no buildings; a still world (no day cycle, weather or random ticks, so block dumps compare exactly) |
| `place_buildings`, `village_board` | sites from a candidate list for seed 2026 with no refusal, no site warning and the server verdict ok; the board lists both buildings |
| `road_lay_remove` | `dev.roads.blocks` before laying = after removing, cell by cell |
| `agent_walks` | `dev.walk.send`: the trip is a walk and the agent moves |
| `night_and_morning` | agents lie in beds at night and are up in the morning (`lib/routinesqa.mjs`) |
| `move_and_undo` | both sites restored exactly |
| `goals`, `standup` | a goal per repo; the home goal's stand-up starts and ends by itself |
| `inbox_pr_and_triage` | the PR goal's "open a pull request" answered in the Inbox, the PR item with new review threads, the automated review and triage in the goal thread, the triage decision (Post) and the fold-in push |
| `answer_decision`, `merge_via_diff_screen` | the home goal's question answered in the Inbox; a merge armed in the diff screen and confirmed with Enter, a merge commit on the demo repo's `main` |
| `trophy_on_goal_done`, `remove_building_with_trophies` | the "Goal done" sign, the board's milestone; Remove is not blocked by the signs and restores the site exactly |
| `settings_mcp_secret` | an MCP server with a fake secret added in Settings > Context: stored in the Foreman's config.json, shown as names only, never in any DevBridge state, log or state file |
| `layout_426x240` | every hub tab at 426x240 GUI px without overflow; a needs-you toast shows before info toasts, the rest on one "+N more" line |
| `placement_too_far`, `text_depth_closeups` | the "too far" note with the ghost kept; close-ups of a monitor, the task board and lamps |

Output in `artifacts/shots/smoke/`: `report.json` (every step's checks, notes, data and shots), `smoke.log`, the PNGs
and `contact-sheet.png`. Exit 0 = every step passed, 1 = a step failed or was skipped, 2 = the run could not start.
Options: `--attach` (use the running client on `--dev-port`; it must be a fresh sim world), `--keep` (leave the launched
client running), `--port`, `--dev-port`, `--world`, `--seed`, `--speed`, `--home`, `--minutes` (overall deadline, 12).
Block comparisons count three kinds of change apart (noted, not failures): leaf `distance` recomputed, flowing water or
lava settling (never a source block), and the live properties of AgentCraft station blocks (a monitor's `lit`).
Pure parts (dump diff, runner, secret scan) are `lib/smoke.mjs`, tested in `test/smoke.test.mjs`.

The Foreman serves only reads to clients without its client token. `foremancli.mjs`,
`lib/foremanclient.mjs`, `shoot.mjs` and `qa.mjs` find the token through the Foreman's run file
under its home: add `--home <dir>` (or set `AGENTCRAFT_HOME`) when the Foreman runs with a home
other than `~/.agentcraft`, e.g. `node tools/foremancli.mjs send config.get --port 27878 --home
<checkout>/.agentcraft-home`. `AGENTCRAFT_CLIENT_TOKEN` overrides
(foreman/README.md "Client token").

Screenshot QA (scene format, anchor contract, judging): [docs/QA.md](../docs/QA.md).

| file | |
| --- | --- |
| `launch.ps1`, `stop.ps1`, `launch.cmd`, `stop.cmd` | launcher |
| `lib/procs.ps1` | shared PowerShell helpers (run files, process identity, Ctrl+Break, ports) |
| `lib/bgrun.mjs` | background runner: owns the log files and the hidden console of a background process |
| `devcli.mjs`, `lib/devclient.mjs` | DevBridge client (sends the token from `AGENTCRAFT_DEV_TOKEN` or `mod/run/agentcraft/devbridge.token`; `AGENTCRAFT_GAME_DIR` overrides the game dir) |
| `foremancli.mjs`, `lib/foremanclient.mjs` | Foreman WS client (hello, acks, diff, live state mirror) |
| `shoot.mjs`, `lib/scene.mjs` | scene runner (anchors, screens, Foreman messages, waits) |
| `record.mjs`, `shots/*.json` | real-time shot player for screen recording (`dev.play`: camera paths, timed Foreman injections, typing); format in `shots/README.md` |
| `qa.mjs`, `lib/contactsheet.mjs`, `scenes/qa.json` | QA suite, contact sheet (pngjs) |
| `scenes/phase1.json`, `scenes/qa-selftest.json` | Phase 1 proof scene, runner self-test |
| `mac.mjs`, `lib/macprocs.mjs`, `lib/foremanproc.mjs`, `lib/logrotate.mjs` | macOS launcher: ps parsing and run files, finding a running Foreman, log rotation |
| `hardcore-setup.mjs`, `lib/prismcfg.mjs` | set up a Prism instance to play with AgentCraft from a stable checkout ("Playing in a Hardcore world") |
| `foreman-daemon.sh`, `foreman-daemon.mjs`, `lib/daemonplan.mjs` | keep one Foreman running for a game launched outside a terminal (Prism's PreLaunchCommand; optional since the mod starts the Foreman itself) |
| `blueprints/` | blueprints as code: the parametric kit, `build.mjs`, the checker (`verify.mjs`) and the offline renderer (`render.mjs`); see `blueprints/VERIFY.md` |
| `agents-live.mjs`, `agents-typing.mjs` | live checks of the agents feature (observe agent life; the agent card's message line by keyboard) |
| `smoke.mjs`, `lib/smoke.mjs` | end-to-end smoke test of the main flows against the sim ("Smoke test" above) |
| `routines-qa.mjs`, `lib/routinesqa.mjs` | village routines check against a running dev world: night rest in beds, morning return (mod/DEV.md "Village routines") |
