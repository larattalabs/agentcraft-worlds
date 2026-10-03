import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePs, descendants, isForemanCommand, planKill, selectProfile, staleReasons } from '../lib/macprocs.mjs';

const PS = `  PID  PPID  PGID COMMAND
    1     0     1 /sbin/launchd
  100     1   100 node --import tsx src/main.ts --backend sim --profile sim --home /h --port 7878
  101   100   100 /x/node_modules/@esbuild/darwin-arm64/bin/esbuild --service=0.25 --ping
  102   101   100 esbuild-child
  200     1   200 node --import tsx src/main.ts --backend sim --profile simulator
  300     1   300 /bin/sh gradlew runClient
`;
const table = parsePs(PS);

test('parsePs skips the header and keeps whole commands', () => {
  assert.equal(table.length, 6);
  assert.deepEqual(table[1], { pid: 100, ppid: 1, pgid: 100, command: 'node --import tsx src/main.ts --backend sim --profile sim --home /h --port 7878' });
});

test('descendants walks transitively and excludes the root', () => {
  assert.deepEqual(descendants(table, 100).map((r) => r.pid), [101, 102]);
  assert.deepEqual(descendants(table, 300), []);
});

test('isForemanCommand needs src/main.ts and the exact profile', () => {
  assert.ok(isForemanCommand(table[1].command, 'sim'));
  assert.ok(!isForemanCommand(table[4].command, 'sim'));
  assert.ok(isForemanCommand(table[4].command, 'simulator'));
  assert.ok(!isForemanCommand('node other.js --profile sim', 'sim'));
});

test('planKill takes the tree and the root group, never our own group', () => {
  assert.deepEqual(planKill(table, [100], 999), { pids: [100, 101, 102], groups: [100] });
  assert.deepEqual(planKill(table, [100], 100), { pids: [100, 101, 102], groups: [] });
  assert.deepEqual(planKill(table, [4242], 999), { pids: [], groups: [] });
  assert.equal(planKill(table, [100, 100], 999).pids.length, 3);
});

test('selectProfile prefers explicit, else the newest run file', () => {
  const files = [
    { name: 'mac-game-sim.json', mtimeMs: 50 }, { name: 'mac-foreman-sim.json', mtimeMs: 40 },
    { name: 'mac-foreman-claude.json', mtimeMs: 10 }, { name: 'mac-audio.json', mtimeMs: 99 },
  ];
  assert.deepEqual(selectProfile(files, undefined), { profile: 'sim', others: ['claude'] });
  assert.deepEqual(selectProfile(files, 'claude'), { profile: 'claude', others: ['sim'] });
  assert.deepEqual(selectProfile([], undefined), { profile: null, others: [] });
});

test('staleReasons flags other checkout, older commit, and unrecorded launches', () => {
  const cur = { root: '/a', commit: 'bbbbbbbbbbbb' };
  assert.deepEqual(staleReasons({ root: '/a', commit: 'bbbbbbbbbbbb' }, cur), []);
  assert.equal(staleReasons({ root: '/z', commit: 'bbbbbbbbbbbb' }, cur).length, 1);
  assert.equal(staleReasons({ root: '/a', commit: 'aaaaaaaaaaaa' }, cur).length, 1);
  assert.equal(staleReasons({ pid: 1 }, cur).length, 1);
});
