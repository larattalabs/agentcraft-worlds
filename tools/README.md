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
one component; `--no-wait` to return immediately while Minecraft builds. Repeat
`--foreman-arg VALUE` to pass extra Foreman options. Logs and process records live in
`artifacts/logs/mac-*.log` and `artifacts/run/mac-*.json`. `stop` only signals processes
recorded by this launcher. macOS uses Notification Center for agent decisions.
The screenshot QA command, `node tools/qa.mjs`, also uses this launcher on macOS.
Logs are checked when a process is started. A log over 5 MB is copy-truncated and three
copies are kept (`.1` .. `.3`). A Foreman still writing to the file is unaffected. A process that
runs for weeks can grow its log past 5 MB until the next start.

## Playing in a Hardcore world (Prism Launcher, macOS)

Dev runs (`mac.mjs`) use the dev checkout, profile `claude`/`sim` and ports 7878/7879. The
everyday game is different: a jar in a Prism instance, with a Foreman that starts with the game.
That Foreman runs from a **stable checkout** (`~/Developer/agentcraft-stable`), not from
`~/Developer/agentcraft`. The dev checkout has many worktrees, the Foreman works on it as a
repository, and agent merges land in its `main`. The stable Foreman uses its own profile and port
(`hardcore`, 7880), so it never collides with a dev run.

**Setup (once, and again to update):** quit Prism and the game first, because setup rewrites
`instance.cfg` and swaps jars in `mods/`.

```sh
node tools/hardcore-setup.mjs                    # dry run: prints every change, changes nothing
node tools/hardcore-setup.mjs --apply            # do it
node tools/hardcore-setup.mjs --apply --ref v0.2 # pin the stable checkout to a tag/branch/commit
```

What `--apply` changes (defaults; see `--help`):

| what | change |
| --- | --- |
| `~/Developer/agentcraft-stable` | cloned from this repository (or fetched), `--ref` (default `main`) checked out detached; refuses local changes, and refuses a ref without `tools/foreman-daemon.sh`/`.mjs` (the PreLaunchCommand points there) |
| the `hardcore` Foreman | stopped first when it runs from the stable checkout and the checkout or its `node_modules` are about to change; it starts again with the next game launch |
| its `foreman/`, `tools/` | `npm ci` |
| its `mod/` | `gradlew build`, giving `mod/build/libs/agentcraft-<version>.jar` |
| `~/MinecraftBackups/<instance>/` | world saves via `~/bin/backup-world.sh`; `agentcraft-setup-<stamp>/` with `instance.cfg` and the whole `mods/` folder on every run; on the first run also `agentcraft-setup-original/`, the pre-AgentCraft state, which is never overwritten |
| `<instance>/.minecraft/mods/` | older `agentcraft*.jar` removed (they are in the backup) and the new jar copied in |
| `<instance>/instance.cfg` | `PreLaunchCommand="<stable>/tools/foreman-daemon.sh" start --profile hardcore --port 7880 --home "<home>"`; `JvmArgs` gains `-Dagentcraft.port=7880 -Dagentcraft.profile=hardcore` (other args kept); `OverrideCommands`/`OverrideJavaArgs=true`; `PostExitCommand` (the world backup) kept. When the instance used Prism's global commands or JVM args (override off), their `PostExitCommand`, `WrapperCommand` and `JvmArgs` are copied in from `prismlauncher.cfg` (`--prism-cfg` if it is elsewhere) |

Running it again changes only what is out of date. It refuses when Prism or the game is running
(a java process whose command line or working directory is in the instance), when the
instance (or Prism's global settings, without the override) already has a PreLaunchCommand that
is not ours, when the ref has no daemon script, when the Minecraft versions differ, and
when `--stable` sits inside a repository listed in `~/.agentcraft/config.json`.

Options: `--stop-on-exit` stops the Foreman when the game exits. The backup still runs first,
because the PostExitCommand becomes `foreman-daemon.sh after-exit ... -- <backup command>`.
Without the option the Foreman keeps running after you quit, so agents keep working and PR polling
continues; running setup again without the option removes it. `--devbridge [--dev-port 7881]`
turns the DevBridge on in this instance, for scripted checks in a copy of the instance. In a
Hardcore world the DevBridge answers only `dev.help`. `--instance`, `--stable`, `--profile`,
`--port`, `--home` and `--backup-dir` change the defaults.

**How the Foreman starts:** Prism runs the PreLaunchCommand and waits for it, and a non-zero exit
aborts the launch. Prism 11.0.3 does no shell parsing: it splits the command on double quotes
(`launcher/launch/steps/PreLaunchCommand.cpp`). `foreman-daemon.sh start` therefore returns 0
immediately and does the work in the background. The background work goes through
`/bin/zsh -lic`, so the Foreman gets your terminal's PATH even though Prism inherits launchd's
bare one. That PATH covers mise's node, `dotnet@8`/`DOTNET_ROOT`, Homebrew's
`az`/`gh`/`cargo` and `~/.local/bin`. If the stable checkout's Foreman is already running at the
same commit, nothing happens. If it runs older code or comes from another checkout, it is
restarted. If something else holds the port, such as a dev Foreman, it is left alone and the
error goes to the log.

**Day to day** (any terminal; the script finds its own checkout):

```sh
~/Developer/agentcraft-stable/tools/foreman-daemon.sh status    # JSON: running, pid, port, commit, stale
~/Developer/agentcraft-stable/tools/foreman-daemon.sh stop
~/Developer/agentcraft-stable/tools/foreman-daemon.sh restart   # e.g. after `claude` /login
~/Developer/agentcraft-stable/tools/foreman-daemon.sh start --wait   # start in the foreground, see errors
node ~/Developer/agentcraft-stable/tools/mac.mjs stop --foreman --profile hardcore   # same as stop
```

Logs: `~/Developer/agentcraft-stable/artifacts/logs/foreman-daemon-hardcore.log` holds the daemon
and the Foreman's output. It is checked at every game launch and rotated past 5 MB, with 3 kept. Prism's own console shows only that the
PreLaunchCommand ran. In game, the top-right pill says "Foreman not running: it starts with the
game; or run tools/foreman-daemon.sh" until the link is up. After a reboot, nothing starts the
Foreman until the next game launch or `foreman-daemon.sh start`.

**Updating:** run `node tools/hardcore-setup.mjs --apply` again, optionally with `--ref`. It
fetches `--ref` from the source and checks it out in the stable checkout. Run from the dev
checkout, the source is the dev checkout. Run from the stable checkout, the source is its
`origin`, which is the dev checkout it was cloned from. `--ref` is resolved with
`git ls-remote`, so a dry run shows the commit that would be installed. The default `main` is
the local `main`, which includes agent merges that have not been pushed; pass a tag or a commit
to pin one. A running `hardcore` Foreman is stopped before the checkout and `npm ci` (the dry run
lists it), because it runs from that checkout's code and `node_modules`; it starts again with the
next game launch, or right away with `foreman-daemon.sh start`. Merge the launch tools
(`tools/foreman-daemon.sh`) into the ref you install: setup refuses a ref without them.

**Rollback:** setup prints the exact commands. In short, quit Prism and copy
`~/MinecraftBackups/<instance>/agentcraft-setup-original/instance.cfg` over
`<instance>/instance.cfg`. Then remove `mods/agentcraft*.jar` and run `foreman-daemon.sh stop`.
The original has no AgentCraft jar, so there is nothing to copy back. Use an
`agentcraft-setup-<stamp>/` folder instead to return to an earlier AgentCraft version. World saves are restored from the `backup-world.sh` archives
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
node tools/qa.mjs --port 27878 --dev-port 7889 --home C:\Projects\agentcraft\.agentcraft-home
node tools/record.mjs tools/shots/desk_story.json --port 7889 --hold 3000   # play a camera shot for OBS (shots/README.md)
npm test --prefix tools
```

The Foreman serves only reads to clients without its client token. `foremancli.mjs`,
`lib/foremanclient.mjs`, `shoot.mjs` and `qa.mjs` find the token through the Foreman's run file
under its home: add `--home <dir>` (or set `AGENTCRAFT_HOME`) when the Foreman runs with a home
other than `~/.agentcraft`, e.g. `node tools/foremancli.mjs send config.get --port 27878 --home
C:\Projects\agentcraft\.agentcraft-home`. `AGENTCRAFT_CLIENT_TOKEN` overrides
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
