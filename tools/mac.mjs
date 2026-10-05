#!/usr/bin/env node
// macOS launcher for the Foreman and the Fabric development client.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isForemanCommand, planKill, selectProfile, staleReasons } from './lib/macprocs.mjs';
import { readJson, saveJson, processStamp, owned, portOpen, psTable, gitHead as gitHeadAt, findForeman } from './lib/foremanproc.mjs';
import { rotateLog } from './lib/logrotate.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tools = path.join(root, 'tools');
const runDir = path.join(root, 'artifacts', 'run');
const logDir = path.join(root, 'artifacts', 'logs');
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const runFile = (kind, profile) => path.join(runDir, `mac-${kind}-${profile}.json`);

function usage(code = 0) {
  console.log(`AgentCraft macOS launcher
  node tools/mac.mjs launch [--backend sim|claude] [--repo PATH] [--use-claude-login]
                            [--home PATH] [--profile NAME] [--port N] [--dev-port N]
                            [--dev] [--showcase busy|late] [--reset]
                            [--world NAME] [--preset flat|normal] [--seed N]
                            [--no-game] [--no-foreman | --mod-foreman] [--no-wait] [--restart-foreman]
                            [--summary-json PATH]
                            [--foreman-arg VALUE] (repeatable)
  node tools/mac.mjs stop [--game] [--foreman] [--profile NAME] [--stop-daemon] [--dry-run]

stop: --game and --foreman can be combined; with neither, both are stopped. Without
--profile it acts on the profile of the newest launcher run file. --dry-run prints what
would be signalled and changes nothing. launch --restart-foreman replaces a running Foreman
(launch warns when the running one came from another checkout or an older commit).
Default: Claude backend, ~/.agentcraft, ports 7878/7879. --dev mutes the game,
keeps it from taking focus, and disables desktop notifications.
--no-foreman: no Foreman at all (the mod's Foreman launcher is turned off, AGENTCRAFT_LAUNCHER=0).
--mod-foreman: this launcher starts no Foreman; the mod starts one itself (its Foreman launcher,
docs/HUB.md), with --backend, --profile, --port and --home, or reuses one already running.
--world/--preset/--seed pick the world the dev client opens or creates (default the flat
"AgentCraft HQ"; any other name is a plain creative world without the HQ rules or studio;
preset and seed only apply when the world is created). Example:
  node tools/mac.mjs launch --backend sim --dev --world "Docs World" --preset normal`);
  process.exit(code);
}

function options(argv) {
  const out = { action: argv.shift(), repo: [], foremanArgs: [] };
  const values = new Set(['backend', 'repo', 'home', 'profile', 'port', 'dev-port', 'showcase', 'summary-json', 'foreman-arg', 'world', 'preset', 'seed']);
  const switches = new Set(['use-claude-login', 'dev', 'reset', 'no-game', 'no-foreman', 'mod-foreman', 'no-wait', 'game', 'foreman', 'stop-daemon', 'restart-foreman', 'dry-run']);
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i].replace(/^--/, '');
    if (!argv[i].startsWith('--')) throw new Error(`unexpected argument: ${argv[i]}`);
    if (values.has(key)) {
      if (!argv[i + 1]) throw new Error(`--${key} needs a value`);
      const value = argv[++i];
      if (key === 'repo') out.repo.push(path.resolve(value));
      else if (key === 'foreman-arg') out.foremanArgs.push(value);
      else out[key] = value;
    } else if (switches.has(key)) out[key] = true;
    else throw new Error(`unknown option: --${key}`);
  }
  if (!['launch', 'stop'].includes(out.action)) usage(out.action ? 2 : 0);
  out.backend ??= process.env.AGENTCRAFT_BACKEND || 'claude';
  if (out.showcase) {
    if (!['busy', 'late'].includes(out.showcase)) throw new Error('showcase must be busy or late');
    out.backend = 'sim';
    out.profile ??= out.showcase === 'late' ? 'showcase-late' : 'showcase';
  }
  if (!['sim', 'claude'].includes(out.backend)) throw new Error('backend must be sim or claude');
  if (out.preset && !['flat', 'normal'].includes(out.preset)) throw new Error('preset must be flat or normal');
  if (out.seed !== undefined && !/^-?\d{1,19}$/.test(out.seed)) throw new Error('seed must be a whole number');
  if (out.world !== undefined && (!out.world.trim() || /[\/\\:*?"<>|]/.test(out.world) || out.world.startsWith('.'))) throw new Error('invalid --world name');
  out.profileExplicit = out.profile;
  out.profile ??= out.backend;
  if (!/^[\w-]+$/.test(out.profile)) throw new Error('profile must contain only letters, digits, _ or -');
  out.home = path.resolve(out.home ?? process.env.AGENTCRAFT_HOME ?? path.join(os.homedir(), '.agentcraft'));
  out.port = Number(out.port ?? process.env.AGENTCRAFT_PORT ?? 7878);
  out['dev-port'] = Number(out['dev-port'] ?? process.env.AGENTCRAFT_DEV_PORT ?? 7879);
  for (const port of [out.port, out['dev-port']]) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`invalid port: ${port}`);
  }
  if (out.port === out['dev-port']) throw new Error('Foreman and DevBridge ports must differ');
  if (out['no-foreman'] && out['mod-foreman']) throw new Error('--no-foreman and --mod-foreman exclude each other');
  if (out['mod-foreman'] && out['no-game']) throw new Error('--mod-foreman needs the game (the mod starts the Foreman)');
  return out;
}

async function waitPort(port, timeoutMs, info, name) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await portOpen(port)) return;
    if (info && !owned(info)) throw new Error(`${name} exited; see ${info.log}`);
    await sleep(500);
  }
  throw new Error(`${name} did not start in time; see ${info?.log ?? 'its logs'}`);
}

function javaHome() {
  const candidates = [process.env.JAVA_HOME, '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home', '/usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'];
  for (const candidate of candidates) {
    if (!candidate) continue;
    const java = path.join(candidate, 'bin', 'java');
    if (!fs.existsSync(java)) continue;
    const result = spawnSync(java, ['-version'], { encoding: 'utf8' });
    if (/version "25[.\"]/.test(result.stderr + result.stdout)) return candidate;
  }
  throw new Error('Java 25 is required. Install it with: brew install openjdk@25');
}

function installDeps(dir) {
  const lock = path.join(dir, 'package-lock.json');
  const stamp = path.join(dir, 'node_modules', '.package-lock.json');
  if (fs.existsSync(stamp) && fs.statSync(stamp).mtimeMs >= fs.statSync(lock).mtimeMs - 2000) return;
  console.log(`Installing npm dependencies in ${path.relative(root, dir)}/ ...`);
  const result = spawnSync('npm', ['ci', '--no-audit', '--no-fund'], { cwd: dir, stdio: 'inherit' });
  if (result.status !== 0) throw new Error(`npm ci failed in ${dir}`);
}

function start(command, args, cwd, log, env = {}) {
  rotateLog(log);
  const output = fs.openSync(log, 'a');
  const child = spawn(command, args, {
    cwd, env: { ...process.env, ...env }, detached: true,
    stdio: ['ignore', output, output],
  });
  child.on('error', () => {});
  fs.closeSync(output);
  child.unref();
  if (!child.pid) throw new Error(`could not start ${command}`);
  return { pid: child.pid, stamp: processStamp(child.pid), log, startedAt: new Date().toISOString() };
}

const gitHead = () => gitHeadAt(root);

function runCli(script, args, timeout = 30000) {
  const result = spawnSync(process.execPath, [path.join(tools, script), ...args], { cwd: root, encoding: 'utf8', timeout });
  if (result.status !== 0) throw new Error(`${script}: ${result.stdout || result.stderr}`.trim());
  return result.stdout.trim();
}

function prepareAudio(dev) {
  const optionsFile = path.join(root, 'mod', 'run', 'options.txt');
  const savedFile = path.join(runDir, 'mac-audio.json');
  const template = path.join(root, 'mod', 'run-template', 'options.txt');
  const level = (text, name) => new RegExp(`^soundCategory_${name}:([^\\n]+)$`, 'm').exec(text)?.[1];
  const replace = (text, name, value) => text.replace(new RegExp(`^soundCategory_${name}:[^\\n]+$`, 'm'), `soundCategory_${name}:${value}`);
  if (!fs.existsSync(optionsFile) && dev) {
    saveJson(savedFile, { master: '1.0', music: level(fs.readFileSync(template, 'utf8'), 'music') });
    return;
  }
  if (!fs.existsSync(optionsFile) && !dev) {
    fs.mkdirSync(path.dirname(optionsFile), { recursive: true });
    fs.writeFileSync(optionsFile, replace(fs.readFileSync(template, 'utf8'), 'master', '1.0'));
  }
  if (!fs.existsSync(optionsFile)) return;
  let text = fs.readFileSync(optionsFile, 'utf8');
  if (dev) {
    if (!fs.existsSync(savedFile)) saveJson(savedFile, { master: level(text, 'master'), music: level(text, 'music') });
  } else {
    const saved = readJson(savedFile);
    if (saved) {
      if (level(text, 'master') === '0.0' && saved.master) text = replace(text, 'master', saved.master);
      if (level(text, 'music') === '0.0' && saved.music) text = replace(text, 'music', saved.music);
      fs.writeFileSync(optionsFile, text);
      fs.rmSync(savedFile, { force: true });
    }
  }
}

async function launch(opt, summary) {
  if (process.platform !== 'darwin') throw new Error('tools/mac.mjs is for macOS');
  if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('Node 22+ is required');
  fs.mkdirSync(runDir, { recursive: true });
  fs.mkdirSync(logDir, { recursive: true });
  if (!opt['no-game']) javaHome();
  for (const repo of opt.repo) {
    if (!fs.existsSync(path.join(repo, '.git'))) throw new Error(`not a Git repository root: ${repo}`);
  }
  installDeps(tools);
  const fmFile = runFile('foreman', opt.profile);
  let fm = readJson(fmFile);
  let fmPort = opt.port;
  if (opt['mod-foreman']) {
    console.log(await portOpen(fmPort) ? `A Foreman is listening on :${fmPort}; the mod will reuse it (or restart it if it started it and it is stale).`
      : `No Foreman on :${fmPort}; the mod's Foreman launcher starts one (${opt.backend}, profile ${opt.profile}).`);
  } else if (!opt['no-foreman']) {
    // also finds a Foreman restarted from the hub (foreman.restart), whose pid the launcher never saw
    const found = await findForeman({ runFile: fmFile, home: opt.home, profile: opt.profile });
    fm = found.fm;
    const running = found.running;
    const stale = running ? staleReasons(fm, { root, commit: gitHead() }) : [];
    if (running && stale.length) {
      const text = `Foreman ${fm.pid} on :${fm.port} is running OLD CODE: ${stale.join('; ')}.`;
      if (!opt['restart-foreman']) {
        console.warn(`\n!!! ${text}\n!!! Reusing it anyway. Re-run with --restart-foreman to replace it.\n`);
      } else {
        console.warn(`${text} Restarting it.`);
        await stopForeman(opt.profile, opt, false);
        fm = null;
      }
    } else if (running && opt['restart-foreman']) {
      console.log('--restart-foreman: restarting the running Foreman.');
      await stopForeman(opt.profile, opt, false);
      fm = null;
    }
    if (fm && running) {
      fmPort = fm.port;
      summary.foreman.port = fmPort;
      console.log(`Reusing Foreman ${fm.pid} on :${fmPort}`);
      if (fm.backend !== opt.backend) console.warn(`Foreman is already using backend ${fm.backend}`);
      for (const repo of opt.repo) console.log(runCli('foremancli.mjs', ['repo-add', repo, '--port', String(fmPort), '--home', fm.home ?? opt.home]));
    } else {
      if (await portOpen(fmPort)) throw new Error(`port ${fmPort} is already in use`);
      installDeps(path.join(root, 'foreman'));
      const args = ['--import', 'tsx', 'src/main.ts', '--backend', opt.backend,
        '--profile', opt.profile, '--home', opt.home, '--port', String(fmPort)];
      for (const repo of opt.repo) args.push('--repo', repo);
      if (opt['use-claude-login']) args.push('--use-claude-login');
      if (opt.dev) args.push('--no-notify');
      if (opt.showcase) args.push('--showcase', opt.showcase);
      if (opt.reset || opt.showcase) args.push('--reset');
      args.push(...opt.foremanArgs);
      fm = { ...start(process.execPath, args, path.join(root, 'foreman'), path.join(logDir, `mac-foreman-${opt.profile}.log`)), backend: opt.backend, port: fmPort, home: opt.home, root, cwd: path.join(root, 'foreman'), commit: gitHead(), script: 'src/main.ts' };
      saveJson(fmFile, fm);
      summary.foreman.started = true;
      await waitPort(fmPort, 120000, fm, 'Foreman');
      console.log(`Foreman running on :${fmPort} (PID ${fm.pid})`);
    }
  } else if (!await portOpen(fmPort)) {
    console.warn(`No Foreman is listening on :${fmPort}; the game will retry connecting.`);
  }
  if (opt['no-game']) return;

  const gameFile = runFile('game', opt.profile);
  for (const name of fs.readdirSync(runDir).filter((name) => /^mac-game-[\w-]+\.json$/.test(name))) {
    const otherFile = path.join(runDir, name);
    if (otherFile !== gameFile && owned(readJson(otherFile))) {
      throw new Error(`another Minecraft client from this checkout is running (${name}); stop it before switching profiles`);
    }
  }
  let game = readJson(gameFile);
  if (owned(game)) {
    if (!await portOpen(game.devPort)) await waitPort(game.devPort, 600000, game, 'Minecraft');
    console.log(`Minecraft is already running (PID ${game.pid}, DevBridge :${game.devPort})`);
    if (opt.world || opt.preset || opt.seed) console.warn('--world/--preset/--seed are ignored: the running game keeps its world. Stop it first (stop --game).');
    summary.game.devPort = game.devPort;
    if (game.foremanPort !== fmPort) console.warn(`It was launched for Foreman :${game.foremanPort}; stop the game before switching ports.`);
    return;
  }
  if (await portOpen(opt['dev-port'])) throw new Error(`DevBridge port ${opt['dev-port']} is already in use`);
  prepareAudio(opt.dev);
  const gradleHome = process.env.GRADLE_USER_HOME || path.join(root, '.gradle-home');
  const env = {
    JAVA_HOME: javaHome(), GRADLE_USER_HOME: gradleHome,
    AGENTCRAFT_PORT: String(fmPort), AGENTCRAFT_DEV_PORT: String(opt['dev-port']),
    AGENTCRAFT_HOME: opt.home, AGENTCRAFT_PROFILE: opt.profile,
    AGENTCRAFT_MUTE: opt.dev ? '1' : '0', AGENTCRAFT_FOCUS: opt.dev ? '0' : '1',
    // the mod's Foreman launcher reuses the Foreman started above, or starts one itself (--mod-foreman)
    AGENTCRAFT_BACKEND: opt.backend,
  };
  if (opt['no-foreman']) env.AGENTCRAFT_LAUNCHER = '0';
  if (opt['mod-foreman']) env.AGENTCRAFT_LAUNCHER = '1';
  if (opt.world) env.AGENTCRAFT_AUTOWORLD_NAME = opt.world;
  if (opt.preset) env.AGENTCRAFT_AUTOWORLD_PRESET = opt.preset;
  if (opt.seed) env.AGENTCRAFT_AUTOWORLD_SEED = opt.seed;
  game = { ...start('/bin/sh', [path.join(root, 'mod', 'gradlew'), 'runClient', '--console=plain'], path.join(root, 'mod'), path.join(logDir, 'mac-game.log'), env), devPort: opt['dev-port'], foremanPort: fmPort };
  saveJson(gameFile, game);
  summary.game.started = true;
  console.log(`Starting Minecraft (Gradle PID ${game.pid}); log: ${game.log}`);
  if (opt['no-wait']) return;
  await waitPort(game.devPort, 600000, game, 'Minecraft');
  console.log('Waiting for the studio world...');
  const state = JSON.parse(runCli('devcli.mjs', ['wait', '--port', String(game.devPort), '--timeout', '300'], 310000));
  console.log(`Studio ready: Minecraft ${state.minecraft}, world ${state.world?.name ?? 'unknown'}, Foreman ${state.foreman?.link ?? 'unknown'}`);
  console.log(`Ready. Stop with: node tools/mac.mjs stop --profile ${opt.profile}`);
}

const alive = (pid) => { try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; } };
const signal = (target, sig) => { try { process.kill(target, sig); } catch {} };

/** Stop the Foreman for `profile`: find it by launcher + own run files, verify, SIGTERM the group, then SIGKILL. */
async function stopForeman(profile, opt, announce = true) {
  const dry = opt['dry-run'];
  const say = (m) => console.log(dry ? `[dry-run] ${m}` : m);
  const launcherFile = runFile('foreman', profile);
  const launcher = readJson(launcherFile);
  const ownFiles = [path.join(opt.home, profile, 'foreman.json')];
  if (launcher?.home) ownFiles.push(path.join(path.resolve(launcher.home), profile, 'foreman.json'));
  const ownInfos = [...new Set(ownFiles)].map((file) => ({ file, info: readJson(file) }));
  let table = psTable();
  const command = (pid) => table.find((r) => r.pid === pid)?.command;
  const candidates = new Map();
  if (launcher?.pid && isForemanCommand(command(launcher.pid), profile)) candidates.set(launcher.pid, 'launcher run file');
  for (const { file, info } of ownInfos) {
    if (info?.pid && isForemanCommand(command(info.pid), profile) && !candidates.has(info.pid)) candidates.set(info.pid, path.relative(root, file) || file);
  }
  const staleFiles = [launcherFile, ...ownInfos.map((o) => o.file)]
    .filter((file) => fs.existsSync(file) && !candidates.has(readJson(file)?.pid));
  if (!candidates.size) {
    if (announce || launcher) console.log(`foreman[${profile}]: no running Foreman found${staleFiles.length ? ' (stale run files)' : ''}`);
  } else {
    const selfPgid = table.find((r) => r.pid === process.pid)?.pgid ?? 0;
    const plan = planKill(table, [...candidates.keys()], selfPgid);
    say(`foreman[${profile}]: found via ${[...candidates].map(([pid, why]) => `${why} (pid ${pid})`).join(', ')}`);
    for (const pid of plan.pids) say(`  ${dry ? 'would signal' : 'signalling'} ${pid} ${(command(pid) ?? '').slice(0, 90)}`);
    for (const group of plan.groups) say(`  ${dry ? 'would signal' : 'signalling'} process group ${group}`);
    if (!dry) {
      for (const group of plan.groups) signal(-group, 'SIGTERM');
      for (const pid of plan.pids) signal(pid, 'SIGTERM');
      for (let i = 0; i < 20 && plan.pids.some(alive); i++) await sleep(250);
      const left = plan.pids.filter(alive);
      if (left.length) {
        console.warn(`foreman[${profile}]: still running after 5 s, sending SIGKILL to ${left.join(', ')}`);
        for (const group of plan.groups) signal(-group, 'SIGKILL');
        for (const pid of left) signal(pid, 'SIGKILL');
        await sleep(500);
      }
      const survivors = plan.pids.filter(alive);
      if (survivors.length) throw new Error(`could not stop Foreman process(es): ${survivors.join(', ')}`);
    }
  }
  if (dry) {
    if (staleFiles.length) say(`  would remove stale run files: ${staleFiles.map((f) => path.relative(root, f)).join(', ')}`);
    return;
  }
  fs.rmSync(launcherFile, { force: true });
  for (const { file, info } of ownInfos) if (info && !alive(info.pid)) fs.rmSync(file, { force: true });
  if (candidates.size || staleFiles.length) console.log(`foreman[${profile}]: stopped`);
}

async function stopGame(profile, opt) {
  const dry = opt['dry-run'];
  const file = runFile('game', profile);
  const info = readJson(file);
  if (!owned(info)) {
    console.log(`game[${profile}]: no launcher-owned process running`);
    if (info && !dry) fs.rmSync(file, { force: true });
    return;
  }
  if (dry) { console.log(`[dry-run] game[${profile}]: would quit via DevBridge :${info.devPort}, then SIGTERM/SIGKILL process group ${info.pid}`); return; }
  if (await portOpen(info.devPort)) {
    try { console.log(runCli('devcli.mjs', ['quit', '--port', String(info.devPort), '--timeout', '20'])); }
    catch (error) { console.warn(error.message); }
  }
  for (let i = 0; i < 40 && owned(info); i++) await sleep(250);
  if (owned(info)) {
    // launch made this PID a separate process group; only touch the recorded group.
    signal(-info.pid, 'SIGTERM');
    await sleep(1000);
    if (owned(info)) signal(-info.pid, 'SIGKILL');
  }
  fs.rmSync(file, { force: true });
  console.log(`game[${profile}]: stopped`);
}

async function stop(opt) {
  const files = fs.existsSync(runDir)
    ? fs.readdirSync(runDir).map((name) => ({ name, mtimeMs: fs.statSync(path.join(runDir, name)).mtimeMs }))
    : [];
  const { profile, others } = selectProfile(files, opt.profileExplicit);
  const kinds = [];
  if (opt.game || !opt.foreman) kinds.push('game');
  if (opt.foreman || !opt.game) kinds.push('foreman');
  if (!profile) {
    console.log(`No launcher run files in ${path.relative(root, runDir)}/; nothing to stop (use --profile NAME to look for a Foreman anyway).`);
  } else {
    if (!opt.profileExplicit) console.log(`Using profile "${profile}" (newest launcher run file)${others.length ? `; other profiles with run files: ${others.join(', ')} (use --profile)` : ''}`);
    if (kinds.includes('game')) await stopGame(profile, opt);
    if (kinds.includes('foreman')) await stopForeman(profile, opt);
  }
  if (opt['stop-daemon'] && kinds.includes('game') && !opt['dry-run']) {
    const localGradleHome = path.join(root, '.gradle-home');
    if (process.env.GRADLE_USER_HOME && path.resolve(process.env.GRADLE_USER_HOME) !== localGradleHome) {
      throw new Error('--stop-daemon requires this checkout\'s .gradle-home to avoid stopping other projects');
    }
    const env = { ...process.env, JAVA_HOME: javaHome(), GRADLE_USER_HOME: localGradleHome };
    const result = spawnSync('/bin/sh', [path.join(root, 'mod', 'gradlew'), '--stop'], { cwd: path.join(root, 'mod'), env, stdio: 'inherit' });
    if (result.status !== 0) throw new Error('could not stop the Gradle daemon');
  }
}

try {
  const opt = options(process.argv.slice(2));
  if (opt.action === 'launch') {
    const summary = { foreman: { started: false, port: opt.port }, game: { started: false, devPort: opt['dev-port'] } };
    try { await launch(opt, summary); }
    finally { if (opt['summary-json']) saveJson(path.resolve(opt['summary-json']), summary); }
  } else await stop(opt);
} catch (error) {
  console.error(`AgentCraft: ${error.message}`);
  process.exitCode = 1;
}
