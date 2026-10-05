// node --test tools/test   (or: npm test --prefix tools)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  SmokeRunner, boxText, cellAt, countStates, diffDumps, fakeSecret, findSecret, flowOnly, growBox, layoutProblem, leafDistanceOnly, liveStateOnly,
  parseBox, pollUntil, redact, scanFiles, selector, summaryLines,
} from '../lib/smoke.mjs';

const box = { x0: 0, y0: 10, z0: 0, x1: 1, y1: 11, z1: 2 }; // 2 x 2 x 3 = 12 cells
const dump = (states) => {
  const palette = [...new Set(states)];
  return { box: boxText(box), palette, cells: states.map((s) => palette.indexOf(s)), blockEntities: 0 };
};
const AIR = 'Block{minecraft:air}';
const STONE = 'Block{minecraft:stone}';
const leaf = (d) => `Block{minecraft:birch_leaves}[distance=${d},persistent=false,waterlogged=false]`;

test('parseBox reads the hub and build state form, any corner order', () => {
  assert.deepEqual(parseBox('-40,111,-40 .. -12,132,-3'), { x0: -40, y0: 111, z0: -40, x1: -12, y1: 132, z1: -3 });
  assert.deepEqual(parseBox('5,6,7 .. 1,2,3'), { x0: 1, y0: 2, z0: 3, x1: 5, y1: 6, z1: 7 });
  assert.equal(parseBox('nonsense'), null);
  assert.equal(parseBox(null), null);
});

test('growBox, selector and boxText', () => {
  assert.equal(boxText(growBox(box, 2, 4)), '-2,6,-2 .. 3,15,4');
  assert.equal(selector(box), 'x=0,y=10,z=0,dx=1,dy=1,dz=2');
});

test('cellAt follows the dump order: x fastest, then z, then y', () => {
  assert.deepEqual(cellAt(box, 0), [0, 10, 0]);
  assert.deepEqual(cellAt(box, 1), [1, 10, 0]);
  assert.deepEqual(cellAt(box, 2), [0, 10, 1]);
  assert.deepEqual(cellAt(box, 6), [0, 11, 0]);
  assert.deepEqual(cellAt(box, 11), [1, 11, 2]);
});

test('diffDumps compares states, not palette indices', () => {
  const a = dump([AIR, STONE, ...Array(10).fill(AIR)]);
  const b = dump([STONE, AIR, ...Array(10).fill(AIR)].reverse().reverse()); // palette order differs: stone first
  b.cells[0] = b.palette.indexOf(AIR);
  b.cells[1] = b.palette.indexOf(STONE);
  assert.notDeepEqual(a.palette, b.palette);
  assert.equal(diffDumps(a, b).differ, 0);
});

test('diffDumps reports the differing cells with their position and both states', () => {
  const a = dump(Array(12).fill(AIR));
  const b = dump([...Array(11).fill(AIR), STONE]);
  const d = diffDumps(a, b);
  assert.equal(d.differ, 1);
  assert.deepEqual(d.diffs, [{ at: [1, 11, 2], before: AIR, after: STONE }]);
});

test('a leaf whose distance was recomputed is counted apart, anything else about a leaf is a difference', () => {
  assert.equal(leafDistanceOnly(leaf(2), leaf(1)), true);
  assert.equal(leafDistanceOnly(leaf(2), leaf(2).replace('persistent=false', 'persistent=true')), false);
  assert.equal(leafDistanceOnly(leaf(2), STONE), false);
  assert.equal(leafDistanceOnly('Block{minecraft:water}[level=0]', 'Block{minecraft:water}[level=1]'), false);
  const d = diffDumps(dump([leaf(2), ...Array(11).fill(AIR)]), dump([leaf(1), ...Array(11).fill(AIR)]));
  assert.equal(d.differ, 0);
  assert.equal(d.leafDistance, 1);
});

test('flowing fluid settling and station live states are counted apart, source blocks and other blocks are not', () => {
  const w = (l) => `Block{minecraft:water}[level=${l}]`;
  assert.equal(flowOnly(AIR, w(2)), true);
  assert.equal(flowOnly(w(6), AIR), true);
  assert.equal(flowOnly(w(1), w(7)), true);
  assert.equal(flowOnly(AIR, w(0)), false, 'a source block appearing is a real difference');
  assert.equal(flowOnly(STONE, w(2)), false);
  const lamp = (st) => `Block{agentcraft:monitor}[facing=east,lit=${st}]`;
  assert.equal(liveStateOnly(lamp('false'), lamp('true')), true);
  assert.equal(liveStateOnly(lamp('false'), 'Block{agentcraft:status_lamp}[status=done]'), false);
  assert.equal(liveStateOnly(STONE, 'Block{minecraft:dirt}'), false);
  const d = diffDumps(dump([AIR, lamp('false'), STONE, ...Array(9).fill(AIR)]), dump([w(3), lamp('true'), 'Block{minecraft:dirt}', ...Array(9).fill(AIR)]));
  assert.equal(d.flow, 1);
  assert.equal(d.live, 1);
  assert.equal(d.differ, 1);
  assert.deepEqual(d.settled.map((x) => x.kind), ['flow', 'live']);
});

test('diffDumps refuses dumps of different boxes', () => {
  const a = dump(Array(12).fill(AIR));
  assert.throws(() => diffDumps(a, { ...a, box: '0,0,0 .. 1,1,2' }), /different boxes/);
});

test('countStates', () => {
  assert.equal(countStates(dump([STONE, STONE, ...Array(10).fill(AIR)]), /stone/), 2);
});

test('secrets: random, redacted, found in nested values and keys', () => {
  const s = fakeSecret();
  assert.match(s, /^smoke-fake-[a-z0-9]{24}$/);
  assert.notEqual(s, fakeSecret());
  assert.equal(redact(`token=${s}; again ${s}`, [s]), 'token=<redacted>; again <redacted>');
  assert.deepEqual(findSecret({ a: [{ b: `x${s}x` }], [s]: 1, c: 3 }, s), ['$.a[0].b', '$.<key>']);
  assert.deepEqual(findSecret({ a: '(set)' }, s), []);
});

test('scanFiles finds the secret in files under a directory and skips the given paths', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'smoke-test-'));
  try {
    const s = fakeSecret();
    fs.mkdirSync(path.join(dir, 'logs'));
    fs.writeFileSync(path.join(dir, 'logs', 'a.log'), `ok\n${s}\n`);
    fs.writeFileSync(path.join(dir, 'logs', 'b.log'), 'nothing');
    fs.writeFileSync(path.join(dir, 'config.json'), JSON.stringify({ env: { K: s } }));
    const r = scanFiles([dir], s, { skip: [path.join(dir, 'config.json')] });
    assert.equal(r.scanned, 2);
    assert.deepEqual(r.hits, [path.join(dir, 'logs', 'a.log')]);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('layoutProblem: overflow is a problem unless the pane scrolls on purpose', () => {
  assert.equal(layoutProblem('x', { overflow: false }), null);
  assert.equal(layoutProblem('x', null), null);
  assert.match(layoutProblem('inbox', { overflow: true, needed: 300, available: 200 }), /inbox: overflow \(needed 300 > available 200\)/);
  assert.equal(layoutProblem('inbox', { overflow: true, detail: { flow: true } }), null);
  assert.equal(layoutProblem('status', { overflow: true, maxScroll: 40 }), null);
});

test('the runner records checks, fails a step on a failed check, skips its dependents and reports', async () => {
  const lines = [];
  const r = new SmokeRunner({ log: (l) => lines.push(l) });
  await r.step('a', {}, (ctx) => { ctx.check('one', true); ctx.note('hello'); ctx.data('k', 1); });
  await r.step('b', { needs: ['a'] }, (ctx) => { ctx.check('two', false, { why: 'no' }); ctx.check('three', true); });
  await r.step('c', { needs: ['b'] }, () => { throw new Error('never runs'); });
  await r.step('d', {}, (ctx) => { ctx.require('must', false, 'detail'); ctx.check('not reached', true); });
  await r.step('e', {}, () => { throw new Error('boom'); });
  const rep = r.report({ world: 'w' });
  assert.deepEqual(rep.steps.map((s) => s.status), ['ok', 'failed', 'skipped', 'failed', 'failed']);
  assert.equal(rep.steps[1].error, 'two');
  assert.match(rep.steps[2].error, /needs b \(failed\)/);
  assert.equal(rep.steps[3].error, 'must: detail');
  assert.equal(rep.steps[3].checks.length, 1);
  assert.equal(rep.steps[4].error, 'boom');
  assert.deepEqual(rep.steps[0].data, { k: 1 });
  assert.deepEqual(rep.counts, { ok: 1, failed: 3, skipped: 1 });
  assert.equal(rep.ok, false);
  assert.equal(rep.world, 'w');
  assert.ok(lines.some((l) => /FAIL two: \{"why":"no"\}/.test(l)));
  assert.match(summaryLines(rep)[2], /^SKIP/);
});

test('a step times out and aborts its signal', async () => {
  const r = new SmokeRunner();
  let aborted = false;
  const rec = await r.step('slow', { timeoutMs: 50 }, async (ctx) => {
    ctx.signal.addEventListener('abort', () => { aborted = true; });
    await new Promise((res) => setTimeout(res, 500));
  });
  assert.equal(rec.status, 'failed');
  assert.match(rec.error, /timed out/);
  assert.equal(aborted, true);
});

test('steps after the deadline are skipped', async () => {
  let t = 0;
  const r = new SmokeRunner({ deadlineMs: 100, now: () => t });
  await r.step('first', {}, () => { t = 200; });
  const rec = await r.step('late', {}, () => {});
  assert.equal(rec.status, 'skipped');
  assert.match(rec.error, /deadline/);
});

test('pollUntil returns the first truthy value, or null after the timeout', async () => {
  let n = 0;
  assert.equal(await pollUntil(async () => (++n >= 3 ? 'yes' : null), { everyMs: 1, timeoutMs: 1000 }), 'yes');
  assert.equal(await pollUntil(async () => null, { everyMs: 5, timeoutMs: 20 }), null);
});
