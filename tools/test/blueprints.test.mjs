// node --test tools/test   (or: npm test --prefix tools)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { nbt, encodeGzip, parse, plain } from '../blueprints/lib/nbt.mjs';
import { Blueprint, B, DATA_VERSION } from '../blueprints/lib/kit.mjs';
import { writeBlueprint } from '../blueprints/lib/write.mjs';
import { checkBlueprint, checkFiles, checkStructure, requiredAnchors } from '../blueprints/lib/check.mjs';
import { normalize } from '../blueprints/lib/blocks.mjs';
import workshop from '../blueprints/designs/workshop.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'blueprints-'));

test('nbt: scalar/list/compound round trip through gzip', () => {
  const root = nbt.compound({
    a: nbt.int(-5), b: nbt.str('héllo'), c: nbt.long(2n ** 40n), d: nbt.byte(-1), e: nbt.double(1.5),
    l: nbt.list('int', [nbt.int(1), nbt.int(2)]), empty: nbt.list('compound', []),
    n: nbt.compound({ x: nbt.str('y') }),
  });
  const back = plain(parse(encodeGzip(root)));
  assert.deepEqual(back, { a: -5, b: 'héllo', c: 2n ** 40n, d: -1, e: 1.5, l: [1, 2], empty: [], n: { x: 'y' } });
});

test('written structure file: valid gzipped template with id/properties palette', () => {
  const dir = tmp();
  const bp = workshop();
  const { nbtPath, jsonPath } = writeBlueprint(bp, dir);
  const bytes = fs.readFileSync(nbtPath);
  assert.equal(bytes[0], 0x1f);
  assert.equal(bytes[1], 0x8b); // gzip magic
  const root = plain(parse(zlib.gunzipSync(bytes)));
  assert.equal(root.DataVersion, 5023);
  assert.equal(DATA_VERSION, 5023);
  assert.deepEqual(root.size, [bp.size.x, bp.size.y, bp.size.z]);
  assert.deepEqual(root.entities, []);
  assert.equal(root.blocks.length, bp.cells.size);
  // 26.3 palette entries are { id, properties }, NOT { Name, Properties } (the latter places nothing)
  for (const p of root.palette) {
    assert.equal(typeof p.id, 'string');
    assert.ok(!('Name' in p) && !('Properties' in p), 'palette must not use Name/Properties');
    for (const v of Object.values(p.properties ?? {})) assert.equal(typeof v, 'string');
  }
  const monitor = root.palette.findIndex((p) => p.id === 'agentcraft:monitor' && p.properties.facing === 'east');
  assert.ok(monitor >= 0);
  assert.deepEqual(Object.keys(root.palette[monitor].properties).sort(), ['down', 'facing', 'left', 'lit', 'right', 'up']);
  // every block: pos in size, state valid; interior air is written; monitors carry bindings
  let air = 0;
  const bindings = new Set();
  for (const b of root.blocks) {
    assert.ok(b.pos.every((n, i) => n >= 0 && n < root.size[i]));
    const p = root.palette[b.state];
    assert.ok(p);
    if (p.id === 'minecraft:air') air++;
    if (p.id === 'agentcraft:monitor') bindings.add(b.nbt.binding);
  }
  assert.ok(air > 2000);
  assert.deepEqual([...bindings].sort(), ['juniper', 'kit', 'rowan', 'tove', 'wren']);
  const side = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  assert.equal(side.id, 'workshop');
  assert.deepEqual(side.size, { x: 27, y: 10, z: 21 });
  assert.equal(side.anchors.desk_kit.yaw, 90); // west-wall desk: the agent looks west at its monitor
});

test('workshop: required anchors and checker passes (files and in-memory)', () => {
  const dir = tmp();
  const bp = workshop();
  const { nbtPath, jsonPath } = writeBlueprint(bp, dir);
  const res = checkFiles(nbtPath, jsonPath);
  assert.deepEqual(res.errors, []);
  assert.ok(res.ok);
  assert.ok(checkBlueprint(bp).ok);
  for (const n of requiredAnchors(1)) assert.ok(bp.anchors[n], `anchor ${n}`);
  assert.ok(bp.anchors['task_wall@1'] && bp.anchors.cam_interior && bp.anchors.cam_task_wall);
});

test('checker fails: missing anchor', () => {
  const bp = workshop();
  delete bp.anchors.desk_kit;
  const res = checkBlueprint(bp);
  assert.ok(!res.ok);
  assert.ok(res.errors.some((e) => e.includes("missing required anchor 'desk_kit'")), res.errors.join('\n'));
});

test('checker fails: standing anchor inside a wall / outside walk', () => {
  const bp = workshop();
  bp.anchor('spawn', 0.5, 1, 5.5, 0); // x=0 is the west wall
  const res = checkBlueprint(bp);
  assert.ok(!res.ok);
  assert.ok(res.errors.some((e) => e.startsWith('anchor spawn') && e.includes('outside walk')), res.errors.join('\n'));
  assert.ok(res.errors.some((e) => e.startsWith('anchor spawn') && e.includes('feet cell')), res.errors.join('\n'));
});

test('checker fails: anchor on a block (no head room), no floor, bad monitor/task anchors', () => {
  const bp = workshop();
  bp.set(13, 2, 19, B.plaster); // head cell of the entrance
  bp.air(5, 0, 5); // hole in the floor under nothing in particular
  bp.anchor('terminal', 5.5, 1, 5.5, 0);
  bp.anchor('monitor_kit', 10.5, 3, 10.5, 0); // not on a monitor
  bp.anchor('task_wall@1', 10.5, 3, 10.5, 0);
  const res = checkBlueprint(bp).errors.join('\n');
  assert.match(res, /anchor entrance: head cell/);
  assert.match(res, /anchor terminal: no solid block below/);
  assert.match(res, /anchor monitor_kit: not on an agentcraft:monitor/);
  assert.match(res, /anchor task_wall@1: not on an agentcraft:task_board/);
});

test('checker fails: uncleared walk cells, size mismatch, invalid palette properties', () => {
  const bp = workshop();
  bp.cells.delete('5,3,5');
  assert.match(checkBlueprint(bp).errors.join('\n'), /cell\(s\) inside walk are not written/);

  const small = workshop();
  small.size = { x: 30, y: 10, z: 21 };
  assert.match(checkBlueprint(small).errors.join('\n'), /does not match block extents/);

  const bad = {
    DataVersion: 5023, size: [1, 1, 1], entities: [],
    palette: [{ id: 'agentcraft:monitor', properties: { facing: 'sideways', lit: 'true' } }, { id: 'minecraft:dark_oak_stairs', properties: { facing: 'north' } }],
    blocks: [{ pos: [0, 0, 0], state: 0 }],
  };
  const res = checkStructure({ id: 'x', name: 'x', kind: 'single', wings: 1, size: { x: 1, y: 1, z: 1 }, groundY: 0, front: 'south', walk: { minX: 0, minY: 0, minZ: 0, maxX: 0, maxY: 0, maxZ: 0 }, anchors: {} }, bad);
  assert.ok(!res.ok);
  assert.match(res.errors.join('\n'), /invalid value 'sideways'/);
  bad.palette.shift();
  bad.blocks[0].state = 0;
  assert.match(checkStructure({ id: 'x', name: 'x', kind: 'single', wings: 1, size: { x: 1, y: 1, z: 1 }, groundY: 0, front: 'south', walk: { minX: 0, minY: 0, minZ: 0, maxX: 0, maxY: 0, maxZ: 0 }, anchors: {} }, bad).errors.join('\n'), /property 'half' not written explicitly/);
});

test('kit: block states are complete; desk writes its anchors; unknown blocks/props throw', () => {
  assert.deepEqual(normalize('minecraft:glass_pane').props, { north: 'false', east: 'false', south: 'false', west: 'false', waterlogged: 'false' });
  assert.throws(() => normalize('agentcraft:monitor', { sparkle: 'true' }), /unknown property/);
  assert.throws(() => normalize('minecraft:nope'), /unknown block/);
  const bp = new Blueprint({ id: 't', size: [8, 6, 8], groundY: 1 });
  bp.desk(4, 1, 'south', 'kit');
  assert.deepEqual(bp.anchors.desk_kit, { x: 3.5, y: 1, z: 2.5, yaw: 180, pitch: 0 }); // chair at the left third, facing the monitor
  assert.deepEqual(bp.anchors.seat_kit, bp.anchors.desk_kit);
  assert.equal(bp.anchors.monitor_kit.z, 1.252);
  assert.equal(bp.get(4, 2, 1).nbt.binding, 'kit');
  bp.camera('x', [0, 2, 0], [0, 2, 5]);
  assert.equal(bp.anchors.cam_x.yaw, 0); // looking south
  bp.camera('y', [0, 2, 0], [-5, 2, 0]);
  assert.equal(bp.anchors.cam_y.yaw, 90); // looking west
});
