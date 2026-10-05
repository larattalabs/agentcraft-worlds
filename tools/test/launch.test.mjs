// tools/hardcore-setup.mjs, tools/foreman-daemon.mjs and their libs: Prism instance.cfg editing,
// log rotation, daemon decisions, and a full setup run against a fake Prism instance.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { rotateLog } from '../lib/logrotate.mjs';
import {
  iniEscape, iniUnescape, readGeneral, setGeneral, splitCommand, quoteArg, preLaunchCommand, isOurPreLaunch,
  wrapPostExit, unwrapPostExit, isWrappedPostExit, mergeJvmArgs,
} from '../lib/prismcfg.mjs';
import { decideStart, devCheckoutConflict, expandHome, lockIsStale, LOCK_STALE_MS } from '../lib/daemonplan.mjs';
import { parseArgs as parseDaemonArgs } from '../foreman-daemon.mjs';
import {
  setup, parseArgs as parseSetupArgs, chooseSource, resolveRemoteRef, realDeps, DAEMON_FILES, LAUNCHER_FILES, parseStatusLine, gameProcesses, cwdProcesses,
} from '../hardcore-setup.mjs';
import { spawnSync } from 'node:child_process';

const tmp = (name) => fs.mkdtempSync(path.join(os.tmpdir(), `ac-${name}-`));

// The way Prism (QSettings) writes these keys; JvmArgs with a comma is quoted as a whole.
const BACKUP = '/Users/me/bin/backup-world.sh';
const CFG = [
  '[General]',
  'AutoCloseConsole=false',
  'Env={}',
  'JvmArgs="-XX:CompileCommand=exclude,a.b.C::d -Xss2m"',
  'OverrideCommands=true',
  'OverrideJavaArgs=true',
  `PostExitCommand=\\"${BACKUP}\\" \\"$INST_MC_DIR\\" \\"/Users/me/MinecraftBackups/Hardcore-World\\"`,
  'PreLaunchCommand=',
  'WrapperCommand=',
  'name=MC Hardcore 26.3',
  '',
  '[UI]',
  'mods_Page\\Columns="AAAA/wAAAAAAAAABAAAAAAAAAAEBAAAAAAAAAAAAAAANwB8AAAAHAAAADAAAAGQ="',
  '',
].join('\n');

// ---- prismcfg ------------------------------------------------------------------------------

test('iniUnescape reads the QSettings forms Prism writes', () => {
  const g = readGeneral(CFG);
  assert.equal(g.JvmArgs, '-XX:CompileCommand=exclude,a.b.C::d -Xss2m');
  assert.equal(g.PostExitCommand, `"${BACKUP}" "$INST_MC_DIR" "/Users/me/MinecraftBackups/Hardcore-World"`);
  assert.equal(g.PreLaunchCommand, '');
  assert.equal(g.name, 'MC Hardcore 26.3');
  assert.throws(() => iniUnescape('a,b'), /list/);
});

test('iniEscape matches QSettings (quotes for , ; = and edge spaces) and round-trips', () => {
  assert.equal(iniEscape('"/a b/c" "$X"'), '\\"/a b/c\\" \\"$X\\"');
  assert.equal(iniEscape('-Dagentcraft.port=7880'), '"-Dagentcraft.port=7880"');
  assert.equal(iniEscape('a,b'), '"a,b"');
  assert.equal(iniEscape(' x'), '" x"');
  assert.equal(iniEscape('back\\slash'), 'back\\\\slash');
  for (const s of ['', 'plain', 'a "q" b', 'x=y,z;w', ' lead', 'tab\there', 'c:\\path\\"q"', 'line\nbreak']) {
    assert.equal(iniUnescape(iniEscape(s)), s, JSON.stringify(s));
  }
});

test('setGeneral keeps every other byte and is idempotent', () => {
  const out = setGeneral(CFG, { PreLaunchCommand: '"/x/tools/foreman-daemon.sh" start', JvmArgs: readGeneral(CFG).JvmArgs });
  const before = CFG.split('\n');
  const after = out.split('\n');
  assert.equal(after.length, before.length);
  const changed = after.map((l, i) => (l === before[i] ? null : i)).filter((i) => i !== null);
  assert.deepEqual(changed, [before.indexOf('PreLaunchCommand=')]);
  assert.equal(setGeneral(out, { PreLaunchCommand: '"/x/tools/foreman-daemon.sh" start' }), out);
  // a missing key goes at the end of [General], before the blank line and [UI]
  const added = setGeneral(CFG, { NewKey: 'v' });
  assert.ok(added.includes('name=MC Hardcore 26.3\nNewKey=v\n\n[UI]'));
  // CRLF files stay CRLF
  assert.ok(setGeneral(CFG.replace(/\n/g, '\r\n'), { PreLaunchCommand: 'x' }).includes('PreLaunchCommand=x\r\n'));
});

test('splitCommand follows QProcess::splitCommand', () => {
  assert.deepEqual(splitCommand('"/a b/c.sh" start --home "/h"'), ['/a b/c.sh', 'start', '--home', '/h']);
  assert.deepEqual(splitCommand('a """q""" b'), ['a', '"q"', 'b']);
  assert.deepEqual(splitCommand('  x   y '), ['x', 'y']);
  assert.equal(quoteArg('plain'), 'plain');
  assert.deepEqual(splitCommand(quoteArg('has "q" and space')), ['has "q" and space']);
});

test('PreLaunchCommand splits into the daemon argv Prism will run', () => {
  const cmd = preLaunchCommand({ daemon: '/Users/me/Developer/agentcraft-stable/tools/foreman-daemon.sh', profile: 'hardcore', port: 7880, home: '/Users/me/.agentcraft' });
  assert.deepEqual(splitCommand(cmd), ['/Users/me/Developer/agentcraft-stable/tools/foreman-daemon.sh', 'start', '--profile', 'hardcore', '--port', '7880', '--home', '/Users/me/.agentcraft']);
  assert.ok(isOurPreLaunch(cmd));
  assert.ok(!isOurPreLaunch('"/usr/bin/other" start'));
  assert.ok(!isOurPreLaunch(''));
  assert.equal(iniUnescape(iniEscape(cmd)), cmd);
});

test('wrapPostExit runs the backup first, is idempotent, and unwraps exactly', () => {
  const orig = readGeneral(CFG).PostExitCommand;
  const o = { daemon: '/s/tools/foreman-daemon.sh', profile: 'hardcore', home: '/h' };
  const w = wrapPostExit(orig, o);
  const argv = splitCommand(w);
  assert.deepEqual(argv.slice(0, 7), ['/s/tools/foreman-daemon.sh', 'after-exit', '--profile', 'hardcore', '--home', '/h', '--']);
  assert.deepEqual(argv.slice(7), splitCommand(orig));
  assert.ok(isWrappedPostExit(w));
  assert.equal(wrapPostExit(w, o), w);
  assert.equal(unwrapPostExit(w), orig);
  assert.equal(unwrapPostExit(orig), orig);
  const empty = wrapPostExit('', o);
  assert.ok(empty.endsWith(' --'));
  assert.equal(unwrapPostExit(empty), '');
});

test('mergeJvmArgs replaces only the managed -Dagentcraft.* properties', () => {
  const base = '-XX:CompileCommand=exclude,a.b.C::d -Xss2m';
  const once = mergeJvmArgs(base, { 'agentcraft.port': 7880, 'agentcraft.profile': 'hardcore' });
  assert.equal(once, `${base} -Dagentcraft.port=7880 -Dagentcraft.profile=hardcore`);
  assert.equal(mergeJvmArgs(once, { 'agentcraft.port': 7880, 'agentcraft.profile': 'hardcore' }), once);
  const dev = mergeJvmArgs(once, { 'agentcraft.port': 7880, 'agentcraft.profile': 'hardcore', 'agentcraft.dev': 1, 'agentcraft.dev.port': 7881 });
  assert.match(dev, /-Dagentcraft\.dev=1 -Dagentcraft\.dev\.port=7881$/);
  assert.equal(mergeJvmArgs(dev, { 'agentcraft.port': 7880, 'agentcraft.profile': 'hardcore' }), once);
  assert.equal(mergeJvmArgs('-Dagentcraft.portal=x', { 'agentcraft.port': 1 }), '-Dagentcraft.portal=x -Dagentcraft.port=1');
  assert.throws(() => mergeJvmArgs('', { 'other.prop': 1 }), /managed/);
});

// ---- logrotate ------------------------------------------------------------------------------

test('rotateLog copy-truncates past the limit, keeps N, and an open append fd keeps working', () => {
  const dir = tmp('rot');
  const log = path.join(dir, 'x.log');
  fs.writeFileSync(log, 'small');
  assert.equal(rotateLog(log, { maxBytes: 100, keep: 2 }), false);
  const fd = fs.openSync(log, 'a');
  fs.writeSync(fd, 'A'.repeat(200));
  assert.equal(rotateLog(log, { maxBytes: 100, keep: 2 }), true);
  assert.equal(fs.statSync(log).size, 0);
  assert.equal(fs.readFileSync(`${log}.1`, 'utf8'), 'small' + 'A'.repeat(200));
  fs.writeSync(fd, 'after');
  fs.closeSync(fd);
  assert.equal(fs.readFileSync(log, 'utf8'), 'after');
  fs.writeFileSync(log, 'B'.repeat(150));
  rotateLog(log, { maxBytes: 100, keep: 2 });
  fs.writeFileSync(log, 'C'.repeat(150));
  rotateLog(log, { maxBytes: 100, keep: 2 });
  assert.deepEqual(fs.readdirSync(dir).sort(), ['x.log', 'x.log.1', 'x.log.2']);
  assert.equal(fs.readFileSync(`${log}.1`, 'utf8')[0], 'C');
  assert.equal(fs.readFileSync(`${log}.2`, 'utf8')[0], 'B');
  assert.equal(rotateLog(path.join(dir, 'missing.log')), false);
});

// ---- daemon decisions -----------------------------------------------------------------------

test('decideStart: same code -> noop, stale -> restart, free -> start, foreign port -> blocked', () => {
  assert.equal(decideStart({ running: true, stale: [], portBusy: false }), 'noop');
  assert.equal(decideStart({ running: true, stale: ['older commit'], portBusy: false }), 'restart');
  assert.equal(decideStart({ running: true, stale: [], forceRestart: true }), 'restart');
  assert.equal(decideStart({ running: false, portBusy: false }), 'start');
  assert.equal(decideStart({ running: false, portBusy: true }), 'blocked');
  assert.equal(decideStart({ running: false, portBusy: true, forceRestart: true }), 'blocked');
});

test('devCheckoutConflict catches the dev repo, its worktrees, and a shared git dir', () => {
  const repos = ['/Users/me/Developer/agentcraft', '/Users/me/Developer/work/api'];
  assert.equal(devCheckoutConflict({ root: '/Users/me/Developer/agentcraft', repos }), repos[0]);
  assert.equal(devCheckoutConflict({ root: '/Users/me/Developer/agentcraft/.claude/worktrees/x', repos }), repos[0]);
  assert.equal(devCheckoutConflict({ root: '/elsewhere/wt', commonDir: '/Users/me/Developer/agentcraft/.git', repos }), repos[0]);
  assert.equal(devCheckoutConflict({ root: '/Users/me/Developer/agentcraft-stable', commonDir: '/Users/me/Developer/agentcraft-stable/.git', repos }), null);
  assert.equal(devCheckoutConflict({ root: '/x', repos: undefined }), null);
  assert.equal(expandHome('~/a', '/H'), '/H/a');
  assert.equal(expandHome('/abs', '/H'), '/abs');
});

test('lockIsStale after the timeout only', () => {
  assert.equal(lockIsStale(1000, 1000 + LOCK_STALE_MS - 1), false);
  assert.equal(lockIsStale(1000, 1000 + LOCK_STALE_MS + 1), true);
  assert.equal(lockIsStale(NaN), true);
});

test('daemon args: defaults keep off the dev ports', () => {
  const o = parseDaemonArgs(['start']);
  assert.equal(o.profile, 'hardcore');
  assert.equal(o.port, 7880);
  assert.equal(parseDaemonArgs(['start', '--port', '9000', '--profile', 'p1', '--home', '/h']).home, '/h');
  assert.throws(() => parseDaemonArgs(['start', '--profile', 'bad name']), /profile/);
  assert.throws(() => parseDaemonArgs(['start', '--bogus']), /unknown/);
});

// ---- hardcore-setup against a fake instance -------------------------------------------------

function fakeWorld() {
  const dir = tmp('setup');
  const instance = path.join(dir, 'instances', 'Hardcore-World');
  const mods = path.join(instance, '.minecraft', 'mods');
  fs.mkdirSync(mods, { recursive: true });
  fs.mkdirSync(path.join(instance, '.minecraft', 'saves', 'World'), { recursive: true });
  fs.writeFileSync(path.join(instance, 'instance.cfg'), CFG);
  fs.writeFileSync(path.join(instance, 'mmc-pack.json'), JSON.stringify({ components: [{ uid: 'net.minecraft', version: '26.3' }] }));
  fs.writeFileSync(path.join(mods, 'fabric-api-0.161.0+26.3.jar'), 'api');
  fs.writeFileSync(path.join(mods, 'agentcraft-0.0.9.jar'), 'old');
  fs.writeFileSync(path.join(mods, 'sodium.jar'), 'sodium');
  const stable = path.join(dir, 'agentcraft-stable');
  fs.mkdirSync(path.join(stable, 'tools'), { recursive: true });
  fs.mkdirSync(path.join(stable, 'mod'), { recursive: true });
  fs.writeFileSync(path.join(stable, 'mod', 'gradle.properties'), 'minecraft_version=26.3\n');
  for (const f of [...DAEMON_FILES, ...LAUNCHER_FILES]) {
    fs.mkdirSync(path.dirname(path.join(stable, f)), { recursive: true });
    fs.writeFileSync(path.join(stable, f), '# stub\n');
  }
  const jar = path.join(dir, 'agentcraft-0.1.0.jar');
  fs.writeFileSync(jar, 'new jar');
  const backupScript = path.join(dir, 'backup-world.sh');
  fs.writeFileSync(backupScript, '#!/bin/bash\n');
  const home = path.join(dir, 'home');
  fs.mkdirSync(home);
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ repos: [path.join(dir, 'dev-agentcraft')] }));
  return { dir, instance, mods, stable, jar, backupScript, home, backupDir: path.join(dir, 'backups') };
}

function treeHash(dir) {
  const h = crypto.createHash('sha256');
  const walk = (d) => {
    for (const name of fs.readdirSync(d).sort()) {
      const p = path.join(d, name);
      h.update(p);
      if (fs.statSync(p).isDirectory()) walk(p);
      else h.update(fs.readFileSync(p));
    }
  };
  walk(dir);
  return h.digest('hex');
}

function deps({ prism = false, game = [], foreman = { running: false } } = {}) {
  const calls = [];
  const lines = [];
  return {
    calls, lines,
    query: () => null,
    exec: (cmd, args, opts) => calls.push([cmd, ...args, opts?.cwd ?? '']),
    prismRunning: () => prism,
    gameRunning: () => game,
    foremanStatus: () => foreman,
    foremanStop: (stable, o) => calls.push(['foreman-stop', stable, o.profile]),
    now: () => new Date(2026, 9, 3, 12, 0, 0),
    log: (l) => lines.push(l),
  };
}

function setupArgs(w, extra = []) {
  return parseSetupArgs(['--instance', w.instance, '--stable', w.stable, '--home', w.home, '--jar', w.jar,
    '--backup-script', w.backupScript, '--backup-dir', w.backupDir, '--skip-checkout', '--skip-deps', ...extra]);
}

test('setup dry run changes nothing and lists every change', async () => {
  const w = fakeWorld();
  const before = treeHash(w.dir);
  const d = deps({ prism: true });
  const r = await setup(setupArgs(w), d);
  assert.equal(treeHash(w.dir), before);
  assert.deepEqual(d.calls, []);
  const text = d.lines.join('\n');
  assert.match(text, /would remove mods\/agentcraft-0\.0\.9\.jar/);
  assert.match(text, /would add mods\/agentcraft-0\.1\.0\.jar/);
  assert.match(text, /would set PreLaunchCommand/);
  assert.match(text, /would set JvmArgs/);
  assert.match(text, /Prism Launcher is running/);
  assert.ok(r.changes.length >= 5);
});

test('setup --apply installs the jar, backs up, edits instance.cfg; a second run changes nothing', async () => {
  const w = fakeWorld();
  const d = deps();
  const r = await setup(setupArgs(w, ['--apply']), d);
  // world backup ran through the script
  assert.deepEqual(d.calls[0].slice(0, 4), ['/bin/bash', w.backupScript, path.join(w.instance, '.minecraft'), w.backupDir]);
  // mods: old jar out (kept in the backup), new jar in, others untouched
  assert.deepEqual(fs.readdirSync(w.mods).sort(), ['agentcraft-0.1.0.jar', 'fabric-api-0.161.0+26.3.jar', 'sodium.jar']);
  assert.equal(fs.readFileSync(path.join(r.backup, 'mods', 'agentcraft-0.0.9.jar'), 'utf8'), 'old');
  assert.equal(fs.readFileSync(path.join(r.backup, 'instance.cfg'), 'utf8'), CFG);
  // instance.cfg
  const cfg = fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8');
  const g = readGeneral(cfg);
  assert.deepEqual(splitCommand(g.PreLaunchCommand).slice(0, 2), [path.join(w.stable, 'tools', 'foreman-daemon.sh'), 'start']);
  assert.ok(splitCommand(g.PreLaunchCommand).includes('7880'));
  assert.equal(g.JvmArgs, `-XX:CompileCommand=exclude,a.b.C::d -Xss2m -Dagentcraft.port=7880 -Dagentcraft.profile=hardcore -Dagentcraft.home=${w.home}`);
  assert.equal(g.PostExitCommand, readGeneral(CFG).PostExitCommand);
  assert.ok(cfg.includes('[UI]\nmods_Page\\Columns="AAAA/wAAAAAAAAABAAAAAAAAAAEBAAAAAAAAAAAAAAANwB8AAAAHAAAADAAAAGQ="'));
  // second run: same instance.cfg, same mods
  const again = await setup(setupArgs(w, ['--apply']), deps());
  assert.equal(again.cfg, cfg);
  assert.equal(fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8'), cfg);
  assert.deepEqual(fs.readdirSync(w.mods).sort(), ['agentcraft-0.1.0.jar', 'fabric-api-0.161.0+26.3.jar', 'sodium.jar']);
});

test('setup --stop-on-exit wraps the backup command; running again without it unwraps', async () => {
  const w = fakeWorld();
  await setup(setupArgs(w, ['--apply', '--stop-on-exit', '--devbridge']), deps());
  let g = readGeneral(fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8'));
  const argv = splitCommand(g.PostExitCommand);
  assert.equal(argv[1], 'after-exit');
  assert.deepEqual(argv.slice(argv.indexOf('--') + 1), splitCommand(readGeneral(CFG).PostExitCommand));
  assert.match(g.JvmArgs, /-Dagentcraft\.dev=1 -Dagentcraft\.dev\.port=7881/);
  await setup(setupArgs(w, ['--apply', '--stop-on-exit', '--devbridge']), deps());
  assert.equal(readGeneral(fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8')).PostExitCommand, g.PostExitCommand);
  await setup(setupArgs(w, ['--apply']), deps());
  g = readGeneral(fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8'));
  assert.equal(g.PostExitCommand, readGeneral(CFG).PostExitCommand);
  assert.doesNotMatch(g.JvmArgs, /agentcraft\.dev/);
});

test('setup --no-prelaunch: no PreLaunchCommand (ours removed), the mod starts the Foreman from the stable checkout', async () => {
  const w = fakeWorld();
  // first the daemon path with --stop-on-exit (an instance set up before the mod could launch)
  await setup(setupArgs(w, ['--apply', '--stop-on-exit']), deps());
  let g = readGeneral(fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8'));
  assert.equal(splitCommand(g.PreLaunchCommand)[1], 'start');
  assert.equal(splitCommand(g.PostExitCommand)[1], 'after-exit');
  // dry run says what changes
  const dry = deps();
  await setup(setupArgs(w, ['--no-prelaunch']), dry);
  assert.match(dry.lines.join('\n'), /would set PreLaunchCommand: ".+" -> ""/);
  assert.match(dry.lines.join('\n'), /the mod starts the Foreman from .+ keeps running after the game exits/);
  // apply: hook gone, backup command unwrapped, the launcher's settings in JvmArgs
  const d = deps();
  await setup(setupArgs(w, ['--apply', '--no-prelaunch']), d);
  g = readGeneral(fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8'));
  assert.equal(g.PreLaunchCommand, '');
  assert.equal(g.PostExitCommand, readGeneral(CFG).PostExitCommand);
  assert.match(g.JvmArgs, new RegExp(`-Dagentcraft\\.port=7880 -Dagentcraft\\.profile=hardcore -Dagentcraft\\.home=\\S+ -Dagentcraft\\.foreman\\.dir=${w.stable.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`));
  assert.doesNotMatch(g.JvmArgs, /stop\.on\.exit/);
  assert.match(d.lines.join('\n'), /foreman-launcher-hardcore\.log/);
  // --stop-on-exit becomes the launcher's property, not a PostExit wrapper
  await setup(setupArgs(w, ['--apply', '--no-prelaunch', '--stop-on-exit']), deps());
  g = readGeneral(fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8'));
  assert.equal(g.PostExitCommand, readGeneral(CFG).PostExitCommand);
  assert.match(g.JvmArgs, /-Dagentcraft\.launcher\.stop\.on\.exit=1$/);
  // and back to the daemon path
  await setup(setupArgs(w, ['--apply']), deps());
  g = readGeneral(fs.readFileSync(path.join(w.instance, 'instance.cfg'), 'utf8'));
  assert.equal(splitCommand(g.PreLaunchCommand)[1], 'start');
  assert.doesNotMatch(g.JvmArgs, /stop\.on\.exit|foreman\.dir/);
});

test('setup: a stable path with spaces works on the daemon path, and is refused with --no-prelaunch', () => {
  const w = fakeWorld();
  const spaced = ['--instance', w.instance, '--stable', path.join(w.dir, 'agentcraft stable'), '--home', w.home, '--skip-checkout'];
  assert.doesNotThrow(() => parseSetupArgs(spaced));
  assert.throws(() => parseSetupArgs([...spaced, '--no-prelaunch']), /--stable cannot contain spaces with --no-prelaunch/);
});

test('setup --no-prelaunch keeps a foreign PreLaunchCommand and needs the mod launcher in the checkout', async () => {
  const w = fakeWorld();
  const cfgFile = path.join(w.instance, 'instance.cfg');
  fs.writeFileSync(cfgFile, CFG.replace('PreLaunchCommand=', 'PreLaunchCommand=/usr/local/bin/sync-world'));
  await assert.rejects(setup(setupArgs(w), deps()), /already has a PreLaunchCommand that is not ours/);
  const d = deps();
  await setup(setupArgs(w, ['--apply', '--no-prelaunch']), d);
  assert.equal(readGeneral(fs.readFileSync(cfgFile, 'utf8')).PreLaunchCommand, '/usr/local/bin/sync-world');
  assert.match(d.lines.join('\n'), /not ours; it is kept/);
  fs.rmSync(path.join(w.stable, LAUNCHER_FILES[0]));
  await assert.rejects(setup(setupArgs(w, ['--no-prelaunch']), deps()), /could not start the Foreman \(--no-prelaunch needs the mod's Foreman launcher\)/);
  fs.writeFileSync(cfgFile, CFG);
  await setup(setupArgs(w), deps()); // the daemon path does not need it
});

test('setup options come from config.json "hardcore" when no flag is given; flags win', () => {
  const w = fakeWorld();
  assert.throws(() => parseSetupArgs(['--home', w.home]), /no Prism instance: pass --instance PATH, or set "hardcore"/);
  const cfg = JSON.parse(fs.readFileSync(path.join(w.home, 'config.json'), 'utf8'));
  cfg.hardcore = { instance: w.instance, stable: w.stable, backupScript: w.backupScript, backupDir: w.backupDir, profile: 'hc2', port: 7990 };
  fs.writeFileSync(path.join(w.home, 'config.json'), JSON.stringify(cfg));
  const o = parseSetupArgs(['--home', w.home, '--skip-checkout']);
  assert.equal(o.instance, w.instance);
  assert.equal(o.stable, w.stable);
  assert.equal(o.backupScript, w.backupScript);
  assert.equal(o.backupDir, w.backupDir);
  assert.equal(o.profile, 'hc2');
  assert.equal(o.port, 7990);
  const f = parseSetupArgs(['--home', w.home, '--skip-checkout', '--profile', 'hc3', '--port', '7991', '--instance', path.join(w.dir, 'other')]);
  assert.equal(f.profile, 'hc3');
  assert.equal(f.port, 7991);
  assert.equal(f.instance, path.join(w.dir, 'other'));
  // validated like the flags
  assert.throws(() => parseSetupArgs(['--home', w.home], { config: { instance: w.instance, port: 7878 } }), /dev runs/);
  assert.throws(() => parseSetupArgs(['--home', w.home], { config: { instance: w.instance, profile: 'bad name' } }), /profile/);
  fs.writeFileSync(path.join(w.home, 'config.json'), JSON.stringify({ hardcore: { instance: w.instance, bogus: 1 } }));
  assert.throws(() => parseSetupArgs(['--home', w.home]), /hardcore\.bogus: unknown key/);
  fs.writeFileSync(path.join(w.home, 'config.json'), JSON.stringify({ hardcore: { instance: '' } }));
  assert.throws(() => parseSetupArgs(['--home', w.home]), /hardcore\.instance: expected a non-empty string/);
  // --help needs no instance
  assert.deepEqual(parseSetupArgs(['--help']), { help: true });
  // without --stable: agentcraft-stable next to the repository
  assert.equal(path.basename(parseSetupArgs(['--home', w.home, '--instance', w.instance, '--skip-checkout'], { config: {} }).stable), 'agentcraft-stable');
});

test('setup without a backup script notes that saves are not backed up', async () => {
  const w = fakeWorld();
  const d = deps();
  await setup(parseSetupArgs(['--instance', w.instance, '--stable', w.stable, '--home', w.home, '--jar', w.jar,
    '--backup-dir', w.backupDir, '--skip-checkout', '--skip-deps']), d);
  assert.ok(d.lines.some((l) => /no world backup script configured/.test(l)));
  assert.ok(!d.lines.some((l) => /back up the world saves/.test(l)));
});

test('setup refuses: Prism running on --apply, a foreign PreLaunchCommand, dev ports, a stable dir inside a dev repo', async () => {
  const w = fakeWorld();
  const before = treeHash(w.dir);
  await assert.rejects(setup(setupArgs(w, ['--apply']), deps({ prism: true })), /Prism Launcher is running/);
  assert.equal(treeHash(w.dir), before);
  assert.throws(() => setupArgs(w, ['--port', '7878']), /dev runs/);
  assert.throws(() => setupArgs(w, ['--devbridge', '--dev-port', '7879']), /collides/);
  const cfgFile = path.join(w.instance, 'instance.cfg');
  fs.writeFileSync(cfgFile, setGeneral(CFG, { PreLaunchCommand: '"/usr/local/bin/something" --x' }));
  await assert.rejects(setup(setupArgs(w, ['--apply']), deps()), /not ours/);
  fs.writeFileSync(cfgFile, CFG);
  const inside = path.join(w.dir, 'dev-agentcraft', 'stable');
  fs.mkdirSync(path.join(inside, 'mod'), { recursive: true });
  await assert.rejects(setup(parseSetupArgs(['--instance', w.instance, '--stable', inside, '--home', w.home, '--jar', w.jar, '--skip-checkout', '--skip-deps']), deps()), /repository the Foreman works on/);
});

test('setup keeps the pre-AgentCraft state once, as the rollback target', async () => {
  const w = fakeWorld();
  const first = await setup(setupArgs(w, ['--apply']), deps());
  const d2 = deps();
  await setup(setupArgs(w, ['--apply']), d2);
  assert.equal(fs.readFileSync(path.join(first.original, 'instance.cfg'), 'utf8'), CFG);
  assert.deepEqual(fs.readdirSync(path.join(first.original, 'mods')).sort(), ['agentcraft-0.0.9.jar', 'fabric-api-0.161.0+26.3.jar', 'sodium.jar']);
  assert.ok(d2.lines.some((l) => l.startsWith('Rollback') && l.includes('agentcraft-setup-original')));
  assert.ok(!d2.lines.some((l) => l.includes('the pre-AgentCraft state')));
});

test('chooseSource: explicit, else the stable checkout\'s origin when run from it, else this repo', () => {
  assert.equal(chooseSource({ explicit: '/src', thisRoot: '/a', stable: '/b', fallback: '/dev' }), '/src');
  assert.equal(chooseSource({ explicit: null, thisRoot: '/a', stable: '/b', stableOrigin: '/x', fallback: '/dev' }), '/dev');
  assert.equal(chooseSource({ explicit: null, thisRoot: '/s', stable: '/s', stableOrigin: '/dev', fallback: '/s' }), '/dev');
  assert.throws(() => chooseSource({ explicit: null, thisRoot: '/s', stable: '/s', stableOrigin: null, fallback: '/s' }), /origin/);
});

test('resolveRemoteRef: branch, peeled tag, full ref, SHA', () => {
  const a = 'a'.repeat(40); const b = 'b'.repeat(40); const c = 'c'.repeat(40);
  const ls = `${a}\tHEAD\n${a}\trefs/heads/main\n${b}\trefs/tags/v1\n${c}\trefs/tags/v1^{}\n`;
  assert.equal(resolveRemoteRef(ls, 'main'), a);
  assert.equal(resolveRemoteRef(ls, 'v1'), c);
  assert.equal(resolveRemoteRef(ls, 'refs/heads/main'), a);
  assert.equal(resolveRemoteRef(ls, 'D'.repeat(40)), 'd'.repeat(40));
  assert.equal(resolveRemoteRef(ls, 'nope'), null);
});

test('setup clones, then updates the stable checkout to the source\'s new commit (real git, temp dirs)', async () => {
  const w = fakeWorld();
  const src = path.join(w.dir, 'src');
  const git = (cwd, ...args) => {
    const r = spawnSync('git', args, { cwd, encoding: 'utf8', env: { ...process.env, GIT_AUTHOR_NAME: 't', GIT_AUTHOR_EMAIL: 't@t', GIT_COMMITTER_NAME: 't', GIT_COMMITTER_EMAIL: 't@t' } });
    assert.equal(r.status, 0, r.stderr);
    return r.stdout.trim();
  };
  fs.mkdirSync(path.join(src, 'mod'), { recursive: true });
  fs.mkdirSync(path.join(src, 'tools'), { recursive: true });
  fs.writeFileSync(path.join(src, 'mod', 'gradle.properties'), 'minecraft_version=26.3\n');
  for (const f of DAEMON_FILES) fs.writeFileSync(path.join(src, f), '# stub\n');
  git(src, 'init', '-q', '-b', 'main');
  git(src, 'add', '.');
  git(src, 'commit', '-q', '-m', 'one');
  const stable = path.join(w.dir, 'stable');
  const order = [];
  const d = {
    ...realDeps,
    exec: (cmd, args, opts = {}) => { order.push(args[0]); realDeps.exec(cmd, args, { ...opts, stdio: 'ignore' }); },
    prismRunning: () => false, gameRunning: () => [], log: () => {}, now: () => new Date(2026, 9, 3),
    foremanStatus: () => ({ running: true, pid: 42 }),
    foremanStop: () => order.push('foreman-stop'),
  };
  const args = (extra = []) => parseSetupArgs(['--instance', w.instance, '--stable', stable, '--source', src, '--home', w.home, '--jar', w.jar,
    '--backup-script', path.join(w.dir, 'no-such-backup.sh'), '--backup-dir', w.backupDir, '--skip-deps', ...extra]);
  await setup(args(['--apply']), d);
  assert.equal(git(stable, 'rev-parse', 'HEAD'), git(src, 'rev-parse', 'HEAD'));
  fs.writeFileSync(path.join(src, 'two.txt'), '2');
  git(src, 'add', '.');
  git(src, 'commit', '-q', '-m', 'two');
  const lines = [];
  await setup(args(), { ...d, log: (l) => lines.push(l) });
  assert.ok(lines.some((l) => /would fetch .* check out main/.test(l)));
  assert.notEqual(git(stable, 'rev-parse', 'HEAD'), git(src, 'rev-parse', 'HEAD'));
  order.length = 0;
  await setup(args(['--apply']), d);
  assert.equal(git(stable, 'rev-parse', 'HEAD'), git(src, 'rev-parse', 'HEAD'));
  // the running stable Foreman is stopped after the fetch and before the checkout changes its code
  assert.deepEqual(order, ['fetch', 'foreman-stop', 'checkout']);

  // a ref without the daemon: refused before anything changes (instance, mods, stable HEAD, Foreman)
  const good = git(stable, 'rev-parse', 'HEAD');
  git(src, 'rm', '-q', ...DAEMON_FILES);
  git(src, 'commit', '-q', '-m', 'old tools');
  const instanceBefore = treeHash(w.instance);
  order.length = 0;
  const dry = [];
  await assert.rejects(setup(args(), { ...d, log: (l) => dry.push(l) }), /has no tools\/foreman-daemon\.sh/);
  await assert.rejects(setup(args(['--apply']), d), /has no tools\/foreman-daemon\.sh/);
  assert.deepEqual(order, []);
  assert.equal(git(stable, 'rev-parse', 'HEAD'), good);
  assert.equal(treeHash(w.instance), instanceBefore);
  // the same with a source the commit is not in yet (a URL): checked in the stable after the fetch
  const remote = { ...d, query: (cmd, a, o = {}) => (o.cwd === src && a[0] === 'cat-file' ? null : realDeps.query(cmd, a, o)) };
  const lines2 = [];
  await assert.rejects(setup(args(['--apply']), { ...remote, log: (l) => lines2.push(l) }), /after the fetch; nothing else was changed/);
  assert.ok(lines2.some((l) => /checked after the fetch/.test(l)));
  assert.deepEqual(order, ['fetch']);
  assert.equal(git(stable, 'rev-parse', 'HEAD'), good);
  assert.equal(treeHash(w.instance), instanceBefore);
  // and a fresh clone of it: refused before the instance is touched
  await assert.rejects(setup(parseSetupArgs(['--instance', w.instance, '--stable', path.join(w.dir, 'stable2'), '--source', src, '--home', w.home, '--jar', w.jar, '--skip-deps', '--apply']), d), /has no tools\/foreman-daemon/);
  assert.equal(treeHash(w.instance), instanceBefore);
});

test('setup stops a running stable Foreman before npm ci, and lists it in the dry run', async () => {
  const w = fakeWorld();
  const args = (extra) => parseSetupArgs(['--instance', w.instance, '--stable', w.stable, '--home', w.home, '--jar', w.jar,
    '--backup-script', w.backupScript, '--backup-dir', w.backupDir, '--skip-checkout', ...extra]);
  const dry = deps({ foreman: { running: true, pid: 4242 } });
  await setup(args([]), dry);
  assert.ok(dry.lines.some((l) => /would stop the hardcore Foreman \(pid 4242\)/.test(l)));
  assert.deepEqual(dry.calls, []);
  const d = deps({ foreman: { running: true, pid: 4242 } });
  await setup(args(['--apply']), d);
  const kinds = d.calls.map((c) => (c[0] === 'foreman-stop' ? 'stop' : c[0] === 'npm' ? 'npm' : c[0]));
  assert.ok(kinds.indexOf('stop') >= 0 && kinds.indexOf('stop') < kinds.indexOf('npm'), kinds.join(','));
  assert.equal(kinds.filter((k) => k === 'stop').length, 1);
  // unknown status: stopped anyway; not running: left alone
  const unknown = deps({ foreman: null });
  await setup(args(['--apply']), unknown);
  assert.ok(unknown.calls.some((c) => c[0] === 'foreman-stop'));
  const idle = deps();
  await setup(args(['--apply']), idle);
  assert.ok(!idle.calls.some((c) => c[0] === 'foreman-stop'));
  // --skip-deps with --skip-checkout changes nothing the Foreman runs: no stop
  const nodeps = deps({ foreman: { running: true, pid: 1 } });
  await setup(args(['--apply', '--skip-deps']), nodeps);
  assert.ok(!nodeps.calls.some((c) => c[0] === 'foreman-stop'));
});

test('setup refuses a stable checkout without the daemon scripts (--skip-checkout)', async () => {
  const w = fakeWorld();
  fs.rmSync(path.join(w.stable, 'tools', 'foreman-daemon.sh'));
  const before = treeHash(w.dir);
  await assert.rejects(setup(setupArgs(w), deps()), /has no tools\/foreman-daemon\.sh/);
  await assert.rejects(setup(setupArgs(w, ['--apply']), deps()), /has no tools\/foreman-daemon\.sh/);
  assert.equal(treeHash(w.dir), before);
});

test('setup refuses --apply while the game runs from the instance (Prism closed or not)', async () => {
  const w = fakeWorld();
  const before = treeHash(w.dir);
  await assert.rejects(setup(setupArgs(w, ['--apply']), deps({ game: [777] })), /game is running .*777/);
  assert.equal(treeHash(w.dir), before);
  const d = deps({ game: [777] });
  await setup(setupArgs(w), d);
  assert.ok(d.lines.some((l) => /game is running/.test(l)));
});

test('gameProcesses / cwdProcesses find the instance\'s java, not other processes or this script', () => {
  const inst = '/Users/me/Library/Application Support/PrismLauncher/instances/Hardcore-World';
  const ps = [
    `  101 /Users/me/Library/Application Support/PrismLauncher/java/java-runtime-delta/bin/java -Xmx4G -Djava.library.path=${inst}/natives -cp x org.prismlauncher.EntryPoint`,
    `  102 /opt/homebrew/bin/node tools/hardcore-setup.mjs --instance ${inst}`,
    '  103 /usr/bin/java -jar other.jar',
    `  104 /opt/java/bin/java -Djava.library.path=${inst}-copy/natives`,
    `  105 java -Dx=${inst}/natives`,
  ].join('\n');
  assert.deepEqual(gameProcesses(ps, inst, [105]), [101]);
  assert.deepEqual(gameProcesses(ps, inst), [101, 105]);
  const lsof = `p201\nfcwd\nn${inst}/.minecraft\np202\nfcwd\nn/Users/me\np203\nfcwd\nn${inst}-copy/.minecraft\n`;
  assert.deepEqual(cwdProcesses(lsof, inst), [201]);
  assert.deepEqual(cwdProcesses('', inst), []);
});

test('parseStatusLine takes the last JSON status line and ignores shell noise', () => {
  assert.deepEqual(parseStatusLine('noise\n{"running":false}\n{"running":true,"pid":9}\n'), { running: true, pid: 9 });
  assert.equal(parseStatusLine('Welcome!\n'), null);
  assert.equal(parseStatusLine('{"nope":1}'), null);
  assert.equal(parseStatusLine('{broken'), null);
  assert.equal(parseStatusLine(undefined), null);
});

test('setup carries Prism\'s global commands and JVM args over when the instance did not override them', async () => {
  const w = fakeWorld();
  const cfgFile = path.join(w.instance, 'instance.cfg');
  const own = setGeneral(CFG, { OverrideCommands: 'false', OverrideJavaArgs: 'false', PostExitCommand: '/stale/leftover.sh', JvmArgs: '-Xstale' });
  fs.writeFileSync(cfgFile, own);
  // no global settings file: refused
  await assert.rejects(setup(setupArgs(w, ['--apply']), deps()), /--prism-cfg/);
  assert.equal(fs.readFileSync(cfgFile, 'utf8'), own);
  const globalCfg = path.join(w.dir, 'prismlauncher.cfg');
  const globalPost = `"${BACKUP}" "$INST_MC_DIR" "/Users/me/MinecraftBackups/global"`;
  fs.writeFileSync(globalCfg, setGeneral('[General]\nJvmArgs=\nPostExitCommand=\nPreLaunchCommand=\nWrapperCommand=\n', {
    PostExitCommand: globalPost, WrapperCommand: '/usr/bin/caffeinate -i', JvmArgs: '-XX:+UseZGC',
  }));
  // found next to instances/ by default
  await setup(setupArgs(w, ['--apply']), deps());
  let g = readGeneral(fs.readFileSync(cfgFile, 'utf8'));
  assert.equal(g.PostExitCommand, globalPost);
  assert.equal(g.WrapperCommand, '/usr/bin/caffeinate -i');
  assert.match(g.JvmArgs, /^-XX:\+UseZGC -Dagentcraft\.port=7880/);
  assert.doesNotMatch(g.JvmArgs, /stale/);
  assert.equal(g.OverrideCommands, 'true');
  const after = fs.readFileSync(cfgFile, 'utf8');
  assert.equal((await setup(setupArgs(w, ['--apply']), deps())).cfg, after);
  // --stop-on-exit wraps the global backup
  await setup(setupArgs(w, ['--apply', '--stop-on-exit']), deps());
  g = readGeneral(fs.readFileSync(cfgFile, 'utf8'));
  assert.deepEqual(splitCommand(g.PostExitCommand).slice(-3), splitCommand(globalPost));
  // a foreign global PreLaunchCommand is refused like an instance one
  fs.writeFileSync(cfgFile, own);
  fs.writeFileSync(path.join(w.dir, 'elsewhere.cfg'), '[General]\nPreLaunchCommand=/usr/local/bin/other\n');
  await assert.rejects(setup(setupArgs(w, ['--apply', '--prism-cfg', path.join(w.dir, 'elsewhere.cfg')]), deps()), /global settings .* not ours/);
});

// ---- foreman-daemon.sh: the Prism contract -------------------------------------------------
// Prism waits for PreLaunchCommand (and reads its output) and aborts the launch on a non-zero exit,
// so `start` must return 0 at once without holding the caller's pipes; `after-exit` must pass the
// backup's exit code through. Runs the real script next to a stub foreman-daemon.mjs, with an empty
// ZDOTDIR so the user's ~/.zshrc is not involved.

function daemonSandbox(t) {
  const dir = tmp('daemon-sh');
  const tools = path.join(dir, 'tools');
  fs.mkdirSync(tools);
  fs.copyFileSync(path.join(import.meta.dirname, '..', 'foreman-daemon.sh'), path.join(tools, 'foreman-daemon.sh'));
  fs.chmodSync(path.join(tools, 'foreman-daemon.sh'), 0o755);
  fs.writeFileSync(path.join(tools, 'foreman-daemon.mjs'), [
    "import fs from 'node:fs';",
    'const action = process.argv[2];',
    "fs.appendFileSync(process.env.STUB_MARKS, `${action}\\n`);",
    "if (action === 'start') { fs.writeFileSync(process.env.STUB_PID, String(process.pid)); setTimeout(() => process.exit(1), 20000); }",
    '',
  ].join('\n'));
  const zdot = path.join(dir, 'zdot');
  fs.mkdirSync(zdot);
  const env = {
    ...process.env, ZDOTDIR: zdot, PATH: `${path.dirname(process.execPath)}:${process.env.PATH}`,
    STUB_MARKS: path.join(dir, 'marks'), STUB_PID: path.join(dir, 'pid'),
  };
  t.after(() => {
    try { process.kill(Number(fs.readFileSync(env.STUB_PID, 'utf8')), 'SIGKILL'); } catch { /* not started or gone */ }
  });
  const marks = () => { try { return fs.readFileSync(env.STUB_MARKS, 'utf8').trim().split('\n'); } catch { return []; } };
  return { dir, script: path.join(tools, 'foreman-daemon.sh'), env, marks };
}

test('foreman-daemon.sh start returns 0 at once and does not hold the caller\'s pipes', async (t) => {
  const s = daemonSandbox(t);
  const t0 = Date.now();
  // `| cat` only ends when every holder of the pipe's write end has closed it
  const r = spawnSync('/bin/bash', ['-c', '"$0" start --profile t | cat; exit "${PIPESTATUS[0]}"', s.script], { env: s.env, encoding: 'utf8', timeout: 15_000 });
  const took = Date.now() - t0;
  assert.equal(r.status, 0, r.stderr);
  assert.ok(took < 2000, `start took ${took} ms`);
  // and it really started the (stub) daemon in the background, which outlives the command
  const deadline = Date.now() + 15_000;
  while (!fs.existsSync(s.env.STUB_PID) && Date.now() < deadline) await new Promise((res) => setTimeout(res, 100));
  assert.ok(fs.existsSync(s.env.STUB_PID), 'the background start never ran');
  assert.deepEqual(s.marks(), ['start']);
  assert.match(fs.readFileSync(path.join(s.dir, 'artifacts', 'logs', 'foreman-daemon-t.log'), 'utf8'), /start --profile t \(background\)/);
});

test('foreman-daemon.sh after-exit runs the backup first and passes its exit code through', (t) => {
  const s = daemonSandbox(t);
  const fail = spawnSync(s.script, ['after-exit', '--profile', 't', '--', '/usr/bin/false'], { env: s.env, encoding: 'utf8', timeout: 30_000 });
  assert.equal(fail.status, 1, fail.stderr);
  assert.deepEqual(s.marks(), ['stop']);
  const ok = spawnSync(s.script, ['after-exit', '--profile', 't', '--', '/bin/sh', '-c', 'echo backup >> "$STUB_MARKS"'], { env: s.env, encoding: 'utf8', timeout: 30_000 });
  assert.equal(ok.status, 0, ok.stderr);
  assert.deepEqual(s.marks(), ['stop', 'backup', 'stop']);
});
