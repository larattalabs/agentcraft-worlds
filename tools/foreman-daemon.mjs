#!/usr/bin/env node
// Keeps one Foreman running for a profile, from THIS checkout, for a game launched outside the dev
// tools (Prism). Normally run through tools/foreman-daemon.sh, which gives it a login shell's PATH.
//
//   start    already running from this checkout at this commit -> nothing; running older code or from
//            another checkout -> restart; not running -> start it detached. Then waits for the port
//            and logs the outcome.
//   stop     stops it (tools/unix.mjs stop --foreman: verified pids, SIGTERM, then SIGKILL)
//   restart  stop + start
//   status   prints a JSON line
//
// It writes the same launcher run file as tools/unix.mjs (artifacts/run/mac-foreman-<profile>.json),
// so `node tools/unix.mjs stop --foreman --profile <profile>` stops it too.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { staleReasons } from './lib/macprocs.mjs';
import { readJson, saveJson, processStamp, owned, portOpen, gitHead, findForeman } from './lib/foremanproc.mjs';
import { rotateLog } from './lib/logrotate.mjs';
import { decideStart, devCheckoutConflict, expandHome, lockIsStale } from './lib/daemonplan.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const runDir = path.join(root, 'artifacts', 'run');
const logDir = path.join(root, 'artifacts', 'logs');
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export const DEFAULTS = { profile: 'hardcore', port: 7880, backend: 'claude' };

function usage(code = 0) {
  console.log(`AgentCraft Foreman daemon (run it through tools/foreman-daemon.sh)
  foreman-daemon.sh start   [--profile NAME] [--port N] [--home PATH] [--backend claude|sim]
                            [--restart] [--allow-dev-checkout] [--wait]
  foreman-daemon.sh stop    [--profile NAME] [--home PATH]
  foreman-daemon.sh restart [same options as start]
  foreman-daemon.sh status  [--profile NAME] [--home PATH]
  foreman-daemon.sh after-exit [--profile NAME] [--home PATH] -- COMMAND...   (Prism PostExitCommand)

Defaults: profile ${DEFAULTS.profile}, port ${DEFAULTS.port}, home ~/.agentcraft, backend ${DEFAULTS.backend}.
"start" from foreman-daemon.sh returns at once (it runs in the background) unless --wait is given.
Log: artifacts/logs/foreman-daemon-<profile>.log in this checkout (rotated at 5 MB, 3 kept).`);
  process.exit(code);
}

export function parseArgs(argv) {
  const out = { action: argv[0], profile: DEFAULTS.profile, port: DEFAULTS.port, backend: DEFAULTS.backend, home: null };
  for (let i = 1; i < argv.length; i++) {
    const a = argv[i];
    const val = () => { if (argv[i + 1] === undefined) throw new Error(`${a} needs a value`); return argv[++i]; };
    if (a === '--profile') out.profile = val();
    else if (a === '--port') out.port = Number(val());
    else if (a === '--home') out.home = val();
    else if (a === '--backend') out.backend = val();
    else if (a === '--restart') out.restart = true;
    else if (a === '--allow-dev-checkout') out['allow-dev-checkout'] = true;
    else if (a === '--wait') out.wait = true;
    else if (a === '--help' || a === '-h') out.action = 'help';
    else throw new Error(`unknown argument: ${a}`);
  }
  out.home = path.resolve(expandHome(out.home ?? process.env.AGENTCRAFT_HOME ?? path.join(os.homedir(), '.agentcraft')));
  if (!/^[\w-]+$/.test(out.profile)) throw new Error('profile must contain only letters, digits, _ or -');
  if (!Number.isInteger(out.port) || out.port < 1 || out.port > 65535) throw new Error(`invalid port: ${out.port}`);
  if (!['claude', 'sim'].includes(out.backend)) throw new Error('backend must be claude or sim');
  return out;
}

const logFile = (profile) => path.join(logDir, `foreman-daemon-${profile}.log`);
const runFile = (profile) => path.join(runDir, `mac-foreman-${profile}.json`);
const say = (msg) => console.log(`[daemon ${new Date().toISOString()}] ${msg}`);

function realpath(p) { try { return fs.realpathSync(p); } catch { return path.resolve(p); } }

/** The repo this checkout must not be: one the Foreman's config.json lists (see daemonplan.mjs). */
function devConflict(home) {
  const config = readJson(path.join(home, 'config.json'));
  const repos = (Array.isArray(config?.repos) ? config.repos : []).map((r) => realpath(expandHome(String(r))));
  const common = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: root, encoding: 'utf8' });
  const commonDir = common.status === 0 ? realpath(common.stdout.trim()) : null;
  return devCheckoutConflict({ root: realpath(root), commonDir, repos });
}

function depsReady(dir) {
  return fs.existsSync(path.join(dir, 'node_modules', '.package-lock.json'));
}

function installDeps(dir) {
  if (depsReady(dir)) return;
  say(`installing npm dependencies in ${dir}`);
  const r = spawnSync('npm', ['ci', '--no-audit', '--no-fund'], { cwd: dir, stdio: 'inherit' });
  if (r.status !== 0) throw new Error(`npm ci failed in ${dir}`);
}

/** Take the per-profile start lock; null when another start holds it. */
function lock(profile) {
  const dir = path.join(runDir, `foreman-daemon-${profile}.lock`);
  fs.mkdirSync(runDir, { recursive: true });
  try {
    fs.mkdirSync(dir);
  } catch {
    let mtime = NaN;
    try { mtime = fs.statSync(dir).mtimeMs; } catch { /* gone meanwhile */ }
    if (!lockIsStale(mtime)) return null;
    fs.rmSync(dir, { recursive: true, force: true });
    try { fs.mkdirSync(dir); } catch { return null; }
  }
  return () => fs.rmSync(dir, { recursive: true, force: true });
}

function stopVia(opt) {
  const r = spawnSync(process.execPath, [path.join(root, 'tools', 'unix.mjs'), 'stop', '--foreman', '--profile', opt.profile, '--home', opt.home], { cwd: root, stdio: 'inherit' });
  return r.status ?? 1;
}

async function start(opt) {
  const log = logFile(opt.profile);
  fs.mkdirSync(logDir, { recursive: true });
  rotateLog(log);
  const conflict = devConflict(opt.home);
  if (conflict && !opt['allow-dev-checkout']) {
    throw new Error(`refusing to run the Foreman from ${root}: it is (inside) ${conflict}, a repository the Foreman itself works on. Use the stable checkout (tools/hardcore-setup.mjs), or pass --allow-dev-checkout.`);
  }
  const release = lock(opt.profile);
  if (!release) { say(`another start for profile ${opt.profile} is in progress; nothing to do`); return; }
  try {
    const file = runFile(opt.profile);
    const { fm, running } = await findForeman({ runFile: file, home: opt.home, profile: opt.profile });
    const stale = running ? staleReasons(fm, { root, commit: gitHead(root) }) : [];
    const portBusy = running ? false : await portOpen(opt.port);
    const action = decideStart({ running, stale, portBusy, forceRestart: opt.restart });
    if (action === 'noop') {
      say(`Foreman already running for profile ${opt.profile} (pid ${fm.pid}, :${fm.port}); nothing to do`);
      if (fm.port !== opt.port) say(`note: it listens on :${fm.port}, not :${opt.port}; the game must use -Dagentcraft.port=${fm.port}`);
      return;
    }
    if (action === 'blocked') {
      throw new Error(`port ${opt.port} is in use by something that is not this checkout's "${opt.profile}" Foreman (a dev Foreman?). Leaving it alone; choose another --port.`);
    }
    if (action === 'restart') {
      say(`restarting the Foreman (pid ${fm.pid}): ${stale.length ? stale.join('; ') : '--restart'}`);
      if (stopVia(opt) !== 0) throw new Error('could not stop the running Foreman');
      if (await portOpen(opt.port)) throw new Error(`port ${opt.port} is still in use after the stop`);
    }
    const foremanDir = path.join(root, 'foreman');
    installDeps(foremanDir);
    const args = ['--import', 'tsx', 'src/main.ts', '--backend', opt.backend, '--profile', opt.profile, '--home', opt.home, '--port', String(opt.port)];
    const out = fs.openSync(log, 'a');
    const child = spawn(process.execPath, args, { cwd: foremanDir, env: process.env, detached: true, stdio: ['ignore', out, out] });
    child.on('error', () => {});
    fs.closeSync(out);
    child.unref();
    if (!child.pid) throw new Error('could not start the Foreman');
    const info = { pid: child.pid, stamp: processStamp(child.pid), log, startedAt: new Date().toISOString(), backend: opt.backend, port: opt.port, home: opt.home, root, cwd: foremanDir, commit: gitHead(root), script: 'src/main.ts', by: 'foreman-daemon' };
    saveJson(file, info);
    say(`started the Foreman: pid ${child.pid}, profile ${opt.profile}, :${opt.port}, home ${opt.home}, commit ${String(info.commit).slice(0, 9)}`);
    const deadline = Date.now() + 120_000;
    while (Date.now() < deadline) {
      if (await portOpen(opt.port)) { say(`Foreman listening on :${opt.port}`); return; }
      if (!owned(info)) throw new Error('the Foreman exited during startup; see the lines above');
      await sleep(500);
    }
    throw new Error(`the Foreman did not open :${opt.port} within 120 s`);
  } finally {
    release();
  }
}

async function status(opt) {
  const { fm, running } = await findForeman({ runFile: runFile(opt.profile), home: opt.home, profile: opt.profile });
  const stale = running ? staleReasons(fm, { root, commit: gitHead(root) }) : [];
  console.log(JSON.stringify({ profile: opt.profile, running, pid: running ? fm.pid : null, port: running ? fm.port : null, root: fm?.root ?? null, commit: fm?.commit ?? null, stale, log: logFile(opt.profile) }));
}

async function main() {
  const argv = process.argv.slice(2);
  if (!argv.length || argv[0] === '--help' || argv[0] === '-h') usage(0);
  const opt = parseArgs(argv);
  if (opt.action === 'help') usage(0);
  if (opt.action === 'start') return start(opt);
  if (opt.action === 'restart') return start({ ...opt, restart: true });
  if (opt.action === 'stop') { const code = stopVia(opt); if (code) process.exitCode = code; return; }
  if (opt.action === 'status') return status(opt);
  usage(2);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => { say(`error: ${e.message}`); process.exitCode = 1; });
}
