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
  assert.deepEqual(side.size, { x: 29, y: 15, z: 32 });
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

test('kit: origin offsets blocks, anchors and walk; roofGable stairs rise towards the ridge', () => {
  const bp = new Blueprint({ id: 'o', size: [9, 8, 9], origin: [1, 0, 1], walk: [0, 0, 0, 1, 1, 1] });
  bp.set(0, 0, 0, B.plaster);
  assert.equal(bp.get(0, 0, 0).state.name, 'agentcraft:plaster_panel');
  assert.equal(bp.cells.has('1,0,1'), true);
  bp.anchor('a', 0.5, 1, 0.5);
  assert.deepEqual([bp.anchors.a.x, bp.anchors.a.z], [1.5, 1.5]);
  assert.equal(bp.walk.minX, 1);
  assert.throws(() => bp.set(-2, 0, 0, B.plaster));
  bp.roofGable(0, 0, 6, 6, 1, { ridge: 'x', pitch: 1, gable: null });
  assert.equal(bp.get(3, 1, 0).state.props.facing, 'south'); // north eave: tall side towards the ridge
  assert.equal(bp.get(3, 1, 6).state.props.facing, 'north');
  assert.equal(bp.nameAt(3, 4, 3), 'minecraft:dark_oak_planks'); // ridge cap
  const half = new Blueprint({ id: 'h', size: [8, 10, 12] });
  half.roofGable(0, 0, 7, 10, 0, { ridge: 'x', pitch: 0.5, gable: B.plaster });
  assert.equal(half.get(2, 0, 0).state.props.facing, 'south');
  assert.equal(half.get(2, 0, 1).state.props.type, 'top');
  assert.equal(half.get(2, 3, 5).state.name, 'minecraft:dark_oak_slab');
  assert.equal(half.get(0, 0, 0).state.name, 'minecraft:dark_oak_stairs');
});

test('kit: roofHip builds a stair ring and a plateau; awning places slabs and posts', () => {
  const bp = new Blueprint({ id: 'p', size: [12, 8, 12] });
  bp.roofHip(0, 0, 11, 11, 0, { rise: 2, skylights: [[5, 5, 6, 6]] });
  assert.equal(bp.nameAt(5, 2, 5), 'minecraft:glass');
  assert.equal(bp.nameAt(3, 2, 3), 'agentcraft:terracotta_tile');
  assert.equal(bp.get(5, 0, 0).state.props.facing, 'south');
  assert.equal(bp.get(0, 0, 0).state.props.shape, 'outer_left');
  assert.throws(() => bp.roofHip(0, 0, 3, 3, 0, { rise: 2 }));
  bp.awning(1, 1, 2, 2, 5, { posts: [[1, 1]], feetY: 1 });
  assert.equal(bp.get(2, 5, 2).state.props.type, 'bottom');
  assert.equal(bp.nameAt(1, 3, 1), 'agentcraft:walnut_panel');
});

test('designs: studio and campus2..4 build and pass the checker', async () => {
  const { default: studio } = await import('../blueprints/designs/studio.mjs');
  const { buildCampus } = await import('../blueprints/lib/campus.mjs');
  assert.ok(checkBlueprint(studio()).ok);
  for (const n of [2, 3, 4]) {
    const bp = buildCampus(n);
    const r = checkBlueprint(bp);
    assert.deepEqual(r.errors, []);
    assert.equal(bp.kind, 'group');
    for (let k = 1; k <= n; k++) assert.ok(bp.anchors[`task_wall@${k}`]);
  }
  assert.throws(() => buildCampus(1));
});
