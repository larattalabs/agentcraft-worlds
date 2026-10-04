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
import { setup, parseArgs as parseSetupArgs } from '../hardcore-setup.mjs';

const tmp = (name) => fs.mkdtempSync(path.join(os.tmpdir(), `ac-${name}-`));

// The way Prism (QSettings) writes these keys; JvmArgs with a comma is quoted as a whole.
const BACKUP = '/Users/me/Developer/bin/backup-world.sh';
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

function deps({ prism = false } = {}) {
  const calls = [];
  const lines = [];
  return {
    calls, lines,
    query: () => null,
    exec: (cmd, args, opts) => calls.push([cmd, ...args, opts?.cwd ?? '']),
    prismRunning: () => prism,
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
