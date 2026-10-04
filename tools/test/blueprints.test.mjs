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
import { checkBlueprint, checkFiles, checkStructure, requiredAnchors, lightCheck, FUNCTIONAL_BLOCKS } from '../blueprints/lib/check.mjs';
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
  assert.equal(bp.get(0, 0, 0).state.name, 'minecraft:smooth_quartz');
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
  assert.equal(bp.nameAt(3, 2, 3), 'minecraft:terracotta');
  assert.equal(bp.get(5, 0, 0).state.props.facing, 'south');
  assert.equal(bp.get(0, 0, 0).state.props.shape, 'outer_left');
  assert.throws(() => bp.roofHip(0, 0, 3, 3, 0, { rise: 2 }));
  bp.awning(1, 1, 2, 2, 5, { posts: [[1, 1]], feetY: 1 });
  assert.equal(bp.get(2, 5, 2).state.props.type, 'bottom');
  assert.equal(bp.nameAt(1, 3, 1), 'minecraft:stripped_dark_oak_log');
});

test('designs: every bundled design (studio, workshop, campus2..5) passes the checker with a sealed shell', async () => {
  const { default: studio } = await import('../blueprints/designs/studio.mjs');
  const { buildCampus } = await import('../blueprints/lib/campus.mjs');
  for (const bp of [studio(), workshop()]) {
    const r = checkBlueprint(bp);
    assert.deepEqual(r.errors, [], bp.id);
    assert.deepEqual(r.warnings.filter((w) => w.startsWith('shell')), [], bp.id);
  }
  for (const n of [2, 3, 4, 5]) {
    const bp = buildCampus(n);
    const r = checkBlueprint(bp);
    assert.deepEqual(r.errors, [], `campus${n}`);
    assert.deepEqual(r.warnings.filter((w) => w.startsWith('shell')), [], `campus${n}`);
    assert.equal(bp.kind, 'group');
    for (let k = 1; k <= n; k++) {
      assert.ok(bp.anchors[`task_wall@${k}`]);
      // per-wing test bench spots (placement renames them testbench:<repoId>) next to the shared slots
      assert.ok(bp.anchors[`testbench@${k}`] && bp.anchors[`testbench_2@${k}`], `testbench@${k}`);
    }
    assert.ok(bp.anchors.testbench && bp.anchors.testbench_2);
  }
  assert.deepEqual(buildCampus(5).anchors['testbench@5'], buildCampus(5).anchors.testbench_9);
  assert.throws(() => buildCampus(1));
  assert.throws(() => buildCampus(6));
});

test('C5 materials: bundled designs use AgentCraft blocks only where functional; the checker refuses decorative ones', async () => {
  const { default: studio } = await import('../blueprints/designs/studio.mjs');
  const { buildCampus } = await import('../blueprints/lib/campus.mjs');
  for (const bp of [studio(), workshop(), buildCampus(5)]) {
    const ac = new Set([...bp.cells.values()].map((c) => c.state.name).filter((n) => n.startsWith('agentcraft:')));
    for (const n of ac) assert.ok(FUNCTIONAL_BLOCKS.has(n), `${bp.id} uses ${n}`);
    assert.equal(bp.materials, 'agentcraft'); // = the AgentCraft look in vanilla blocks
  }
  const bp = workshop();
  bp.set(5, 3, 0, 'agentcraft:plaster_panel');
  assert.match(checkBlueprint(bp).errors.join('\n'), /decorative AgentCraft block 'agentcraft:plaster_panel'.*smooth_quartz/);
});

test('C5 shell: a functional block in the outer wall without a vanilla block behind it is a hole without the mod', () => {
  // workshop east wall (x=26): the pilaster at z=14 has nothing outside it at y=3 (no window sill)
  const bp = workshop();
  bp.statusLamp(26, 3, 14, 'ci:#1');
  const errs = checkBlueprint(bp).errors.join('\n');
  assert.match(errs, /shell: without the mod the agentcraft:status_lamp at 27,3,15 leaves a hole/);
  const fixed = workshop();
  fixed.wallLamp(26, 3, 14, 'west', 'ci:#1');
  assert.equal(fixed.nameAt(27, 3, 14), B.walnutTrim); // the backing plate outside
  assert.deepEqual(checkBlueprint(fixed).errors, []);
  // the same lamp on an inner partition gets no plate (the cell behind is carved air, not outside)
  const inner = workshop();
  inner.wallLamp(1, 3, 3, 'west', 'ci:#1');
  assert.equal(inner.nameAt(0, 3, 3), B.plaster);
  // the merge lamp (in the wall behind the merge station) is backed on the outside
  assert.equal(workshop().nameAt(27, 3, 16), B.walnutTrim);
});

test('C5 shell: a doorway without a door is an error; a lamp in a gable wall opening the attic too', () => {
  const doorless = workshop();
  doorless.air(13, 1, 20);
  doorless.air(13, 2, 20);
  assert.match(checkBlueprint(doorless).errors.join('\n'), /walk cell\(s\) reachable from outside through the walls.*14,1,21/);
  const gable = workshop();
  gable.statusLamp(0, 8, 3, 'ci:#1'); // west gable wall, attic behind it, open eave outside
  const errs = checkBlueprint(gable).errors.join('\n');
  assert.match(errs, /without the mod the agentcraft:status_lamp at 1,8,4 leaves a hole in the outer shell/);
  // a courtyard (walk open to the sky only) stays a warning
  const yard = workshop();
  for (let y = 7; y <= 14; y++) for (const [x, z] of [[5, 16], [5, 17]]) if (yard.inBounds(x, y, z)) yard.air(x, y, z);
  const r = checkBlueprint(yard);
  assert.ok(!r.errors.some((e) => e.startsWith('shell')), r.errors.join('\n'));
  assert.ok(r.warnings.some((w) => w.includes('open to the sky')), r.warnings.join('\n'));
});

test('C5 light: the studio corner (34,1,26) was dark; the corner lantern fixes it (regression)', async () => {
  const { default: studio } = await import('../blueprints/designs/studio.mjs');
  const bp = studio();
  assert.deepEqual(checkBlueprint(bp).errors, []);
  bp.air(33, 2, 25); // the corner lantern
  bp.air(33, 1, 25); // its barrel
  assert.match(checkBlueprint(bp).errors.join('\n'), /light: \d+ walk cell\(s\) get no block light.*34,1,26/);
});

test('C5 light: vanilla propagation (decrement, opaque, glass, slab faces, no AgentCraft or invisible light)', () => {
  const grid = new Map();
  const put = (x, y, z, name, props = {}) => grid.set(`${x},${y},${z}`, normalize(name, props));
  const size = [16, 4, 1];
  for (let x = 0; x < 16; x++) for (let y = 0; y < 4; y++) put(x, y, 0, 'minecraft:air');
  put(0, 1, 0, 'minecraft:lantern');
  const walk = { minX: 0, minY: 1, minZ: 0, maxX: 15, maxY: 1, maxZ: 0 };
  let r = lightCheck(grid, size, walk, 0);
  const lv = (x, y, mode = 'nomod') => r.levels(mode)[x + 16 * y];
  assert.equal(lv(0, 1), 15);
  assert.equal(lv(14, 1), 1);
  assert.equal(lv(15, 1), 0); // 15 steps away: dark
  assert.match(r.errors.join(), /1 walk cell/);
  put(5, 1, 0, 'minecraft:glass'); // glass passes light
  put(5, 2, 0, 'minecraft:glass');
  put(5, 3, 0, 'minecraft:glass');
  put(5, 0, 0, 'minecraft:glass');
  r = lightCheck(grid, size, walk, 0);
  assert.equal(lv(6, 1), 9);
  for (let y = 0; y < 4; y++) put(5, y, 0, 'minecraft:smooth_quartz'); // an opaque wall stops it
  r = lightCheck(grid, size, walk, 0);
  assert.equal(lv(6, 1), 0);
  // a bottom slab's full bottom face: light from above does not reach the cell below it
  const g2 = new Map();
  const p2 = (x, y, z, name, props = {}) => g2.set(`${x},${y},${z}`, normalize(name, props));
  for (let y = 0; y < 4; y++) p2(0, y, 0, 'minecraft:smooth_quartz');
  p2(0, 3, 0, 'minecraft:sea_lantern');
  p2(0, 2, 0, 'minecraft:oak_slab', { type: 'bottom' });
  p2(0, 1, 0, 'minecraft:air');
  r = lightCheck(g2, [1, 4, 1], { minX: 0, minY: 1, minZ: 0, maxX: 0, maxY: 1, maxZ: 0 }, 0);
  assert.equal(r.levels('nomod')[2], 14); // inside the slab cell
  assert.equal(r.levels('nomod')[1], 0); // below it
  p2(0, 2, 0, 'minecraft:oak_slab', { type: 'top' }); // a top slab's bottom face is open... but its top face is full
  r = lightCheck(g2, [1, 4, 1], { minX: 0, minY: 1, minZ: 0, maxX: 0, maxY: 1, maxZ: 0 }, 0);
  assert.equal(r.levels('nomod')[2], 0);
  // AgentCraft blocks and the invisible light block never count
  const g3 = new Map();
  g3.set('0,1,0', { name: 'agentcraft:status_lamp', props: { status: 'idle' } });
  g3.set('1,1,0', normalize('minecraft:light', { level: '15' }));
  g3.set('2,1,0', normalize('minecraft:air'));
  r = lightCheck(g3, [3, 2, 1], { minX: 0, minY: 1, minZ: 0, maxX: 2, maxY: 1, maxZ: 0 }, 1);
  assert.equal(r.dark.length, 3); // the lamp cell (air without the mod), the light block cell and the air cell
});

test('C5 light: a dark attic (enclosed, outside walk) is a warning', () => {
  const bp = workshop();
  for (const [k, c] of bp.cells) if (c.state.name === B.glowPanel && k.split(',')[1] === '7') bp.cells.set(k, { state: normalize(B.plaster), nbt: null });
  const r = checkBlueprint(bp);
  assert.match(r.errors.join('\n'), /light: \d+ walk cell/);
  assert.match(r.warnings.join('\n'), /dark cell\(s\) in enclosed space outside walk/);
  assert.ok(!checkBlueprint(workshop()).warnings.some((x) => x.includes('enclosed space')));
});

test('C5 doors: outside doors are iron, written closed, with stone buttons on both sides', () => {
  const ok = workshop();
  assert.equal(ok.nameAt(13, 1, 20), 'minecraft:iron_door');
  assert.equal(ok.get(13, 1, 20).state.props.open, 'false');
  assert.equal(ok.get(14, 2, 21).state.props.facing, 'south'); // outside button on the east jamb
  assert.equal(ok.get(14, 2, 19).state.props.facing, 'north'); // inside button
  const wood = workshop();
  wood.door(13, 1, 20, 'south', { block: 'minecraft:dark_oak_door' });
  assert.match(checkBlueprint(wood).errors.join('\n'), /outside door must be minecraft:iron_door/);
  const noOut = workshop();
  noOut.air(14, 2, 21);
  assert.match(checkBlueprint(noOut).errors.join('\n'), /iron door at 14,1,21: needs a stone button on its front/);
  const glassJamb = workshop();
  glassJamb.set(14, 2, 20, B.pane); // a button on glass powers nothing
  assert.match(checkBlueprint(glassJamb).errors.join('\n'), /iron door at 14,1,21: needs a stone button on both sides/);
  const glow = workshop();
  glow.set(14, 2, 20, 'minecraft:glowstone'); // full and opaque, but carries no redstone power
  assert.match(checkBlueprint(glow).errors.join('\n'), /iron door at 14,1,21: needs a stone button on both sides/);
  const open = workshop();
  open.door(13, 1, 20, 'south', { open: true });
  assert.match(checkBlueprint(open).errors.join('\n'), /door at 14,1,21 is written open/);
});

test('C4 foundationBlock: sidecar default, per design, validated', async () => {
  const { default: studio } = await import('../blueprints/designs/studio.mjs');
  assert.equal(studio().sidecar().foundationBlock, 'minecraft:stone_bricks');
  assert.equal(workshop().sidecar().foundationBlock, 'minecraft:cobblestone');
  assert.equal(new Blueprint({ id: 'f', size: [1, 1, 1] }).foundationBlock, 'minecraft:stone_bricks');
  const bp = workshop();
  bp.foundationBlock = 'agentcraft:plaster_panel';
  assert.match(checkBlueprint(bp).errors.join('\n'), /foundationBlock 'agentcraft:plaster_panel' must be a vanilla block id/);
  bp.foundationBlock = 'minecraft:glass';
  assert.match(checkBlueprint(bp).errors.join('\n'), /must be a full, opaque block/);
});

test('bundled blueprints are up to date with their designs (run node tools/blueprints/build.mjs --all)', async () => {
  const { listDesigns, NBT_DIR, JSON_DIR } = await import('../blueprints/build.mjs');
  for (const name of listDesigns()) {
    const { default: make } = await import(`../blueprints/designs/${name}.mjs`);
    const bp = make();
    const bundled = JSON.parse(fs.readFileSync(path.join(JSON_DIR, `${name}.blueprint.json`), 'utf8'));
    assert.deepEqual(bundled, JSON.parse(JSON.stringify(bp.sidecar())), `${name}.blueprint.json is stale`);
    const root = plain(parse(fs.readFileSync(path.join(NBT_DIR, `${name}.nbt`))));
    assert.equal(root.blocks.length, bp.cells.size, `${name}.nbt is stale`);
    assert.ok(checkFiles(path.join(NBT_DIR, `${name}.nbt`), path.join(JSON_DIR, `${name}.blueprint.json`)).ok, name);
  }
});

// ---- trophy slots (docs/BUILDINGS.md "Trophy slots")
const trophyNames = (anchors, wing) => Object.keys(anchors).filter((k) => new RegExp(`^trophy(_\\d+)?@${wing}$`).test(k));

test('trophies: every bundled sidecar has 6 trophy slots per wing, in fill order, facing into the room', async () => {
  const { listDesigns, JSON_DIR } = await import('../blueprints/build.mjs');
  for (const name of listDesigns()) {
    const side = JSON.parse(fs.readFileSync(path.join(JSON_DIR, `${name}.blueprint.json`), 'utf8'));
    for (let w = 1; w <= side.wings; w++) {
      const names = trophyNames(side.anchors, w);
      assert.deepEqual(names.sort(), ['trophy', 'trophy_2', 'trophy_3', 'trophy_4', 'trophy_5', 'trophy_6'].map((n) => `${n}@${w}`).sort(), `${name} wing ${w}`);
      const a = side.anchors[`trophy@${w}`];
      assert.equal(a.x % 1, 0.5); assert.equal(a.y % 1, 0.5); assert.equal(a.z % 1, 0.5);
      assert.ok([0, 90, 180, -90].includes(a.yaw), `${name} yaw ${a.yaw}`);
    }
  }
  const { buildCampus } = await import('../blueprints/lib/campus.mjs');
});

test('trophies: trophyWall writes air cells, a backing, a frame and light; top row first, left to right', () => {
  const bp = new Blueprint({ id: 't', size: [9, 8, 9], groundY: 1, walk: [1, 1, 1, 7, 6, 7] });
  bp.trophyWall(0, 3, 0, 5, 'east', { slots: 6, wing: 1 });
  for (const [x, y, z] of [[1, 2, 3], [1, 3, 5]]) assert.equal(bp.nameAt(x, y, z), 'minecraft:air');
  for (const [x, y, z] of [[0, 2, 3], [0, 3, 5]]) assert.equal(bp.nameAt(x, y, z), B.plaster);
  assert.equal(bp.nameAt(0, 4, 4), B.glowPanel);
  assert.equal(bp.nameAt(0, 3, 2), B.walnut);
  // facing east the viewer looks west: left = south (+z); top row (y 3) first
  assert.deepEqual(bp.anchors['trophy@1'], { x: 1.5, y: 3.5, z: 5.5, yaw: -90, pitch: 0 });
  assert.deepEqual(bp.anchors['trophy_3@1'], { x: 1.5, y: 3.5, z: 3.5, yaw: -90, pitch: 0 });
  assert.deepEqual(bp.anchors['trophy_4@1'], { x: 1.5, y: 2.5, z: 5.5, yaw: -90, pitch: 0 });
  assert.equal(bp.anchors['trophy_6@1'].z, 3.5);
  assert.throws(() => bp.trophyWall(0, 3, 0, 5, 'east', { slots: 7, rows: 2 }), /do not fit/);
  const plain1 = new Blueprint({ id: 't2', size: [9, 8, 9], groundY: 1, walk: [1, 1, 1, 7, 6, 7] }).trophyWall(0, 3, 0, 5, 'east');
  assert.ok(plain1.anchors.trophy && plain1.anchors.trophy_6 && !plain1.anchors['trophy@1']);
});

test('trophies checker: rejects a slot with no support, outside walk, a non-air cell, a bad wing, a shared cell', () => {
  const run = (mutate) => {
    const bp = workshop();
    mutate(bp);
    return checkBlueprint(bp);
  };
  const base = workshop().anchors['trophy@1'];
  assert.ok(base, 'workshop has trophy@1');
  assert.equal(base.yaw, 90); // east wall, facing west: the support is the +x cell
  const at = (bp, dx) => [Math.floor(base.x) - bp.ox + dx, Math.floor(base.y) - bp.oy, Math.floor(base.z) - bp.oz];
  assert.deepEqual(checkBlueprint(workshop()).errors, []);
  // no support: the wall behind the sign becomes air
  let r = run((bp) => bp.air(...at(bp, 1)));
  assert.ok(r.errors.some((e) => e.startsWith('anchor trophy@1') && e.includes('no full opaque block behind')), r.errors.join('\n'));
  // a non-full backing (a pane)
  r = run((bp) => bp.set(...at(bp, 1), B.pane));
  assert.ok(r.errors.some((e) => e.includes('no full opaque block behind')), r.errors.join('\n'));
  // non-air cell
  r = run((bp) => bp.set(...at(bp, 0), B.plaster));
  assert.ok(r.errors.some((e) => e.startsWith('anchor trophy@1') && e.includes('needs explicit air')), r.errors.join('\n'));
  // outside walk (inside the wall)
  r = run((bp) => bp.anchor('trophy_9@1', base.x - bp.ox + 1, base.y - bp.oy, base.z - bp.oz, base.yaw));
  assert.ok(r.errors.some((e) => e.startsWith('anchor trophy_9@1') && e.includes('outside walk')), r.errors.join('\n'));
  // bad wing
  r = run((bp) => { bp.anchors['trophy_9@3'] = { ...base }; });
  assert.ok(r.errors.some((e) => e.includes('trophy_9@3') && e.includes('wing out of range')), r.errors.join('\n'));
  // two slots in one cell
  r = run((bp) => { bp.anchors['trophy_9@1'] = { ...base }; });
  assert.ok(r.errors.some((e) => e.includes('same sign cell')), r.errors.join('\n'));
  // a standing anchor in the sign cell
  r = run((bp) => bp.spot('user', at(bp, 0)[0], at(bp, 0)[2], 0));
  assert.ok(r.errors.some((e) => e.includes('trophy') && e.includes('stands')), r.errors.join('\n'));
  // bad yaw
  r = run((bp) => { bp.anchors['trophy@1'] = { ...base, yaw: 45 }; });
  assert.ok(r.errors.some((e) => e.includes('multiple of 90')), r.errors.join('\n'));
});

test('trophies checker: a wing without trophy slots is a warning, not an error', () => {
  const bp = workshop();
  for (const k of Object.keys(bp.anchors)) if (k.startsWith('trophy')) delete bp.anchors[k];
  const r = checkBlueprint(bp);
  assert.deepEqual(r.errors, []);
  assert.ok(r.warnings.some((w) => w.includes('no trophy slots')), r.warnings.join('\n'));
});

// ---- fixtures (docs/VILLAGE.md V2: the village board)
test('fixture: the bundled village board passes the checker, takes no repos and keeps its shape without the mod', async () => {
  const { default: villageBoard } = await import('../blueprints/designs/village_board.mjs');
  const bp = villageBoard();
  const r = checkBlueprint(bp);
  assert.deepEqual(r.errors, []);
  assert.deepEqual(r.warnings, []);
  assert.equal(bp.kind, 'fixture');
  assert.equal(bp.wings, 0);
  assert.equal(bp.approach.length, 0);
  const side = bp.sidecar();
  assert.deepEqual(Object.keys(side.anchors).sort(), ['board', 'cam_overview', 'spawn']);
  // the anchor is the display's centre, on the board plane, facing south
  assert.deepEqual(side.anchors.board, { x: 3.5, y: 3.5, z: 1.127, yaw: 0, pitch: 0 });
  const ac = [...bp.cells.values()].filter((c) => c.state.name.startsWith('agentcraft:'));
  assert.equal(ac.length, 15); // 5 x 3 display
  assert.ok(ac.every((c) => c.state.name === B.villageBoard && !c.nbt));
  assert.ok(FUNCTIONAL_BLOCKS.has(B.villageBoard));
});

test('fixture: the checker wants the board anchor on the display, a backing behind it and no repo bindings or trophies', async () => {
  const { default: villageBoard } = await import('../blueprints/designs/village_board.mjs');
  const noBack = villageBoard();
  noBack.set(3, 3, 0, 'minecraft:air');
  assert.match(checkBlueprint(noBack).errors.join('\n'), /fixture: the agentcraft:village_board at 3,3,1 has no full vanilla block behind it/);
  const bound = villageBoard();
  bound.bind(2, 2, 1, 'repo:#1');
  assert.match(checkBlueprint(bound).errors.join('\n'), /bound to 'repo:#1' \(a fixture takes no repos\)/);
  const moved = villageBoard();
  moved.anchor('board', 3.5, 1.5, 3.5, 0);
  assert.match(checkBlueprint(moved).errors.join('\n'), /anchor board: not on an agentcraft:village_board block/);
  const wings = villageBoard();
  wings.wings = 1;
  assert.match(checkBlueprint(wings).errors.join('\n'), /a fixture takes no repos: wings 0/);
  const trophy = villageBoard();
  trophy.anchor('trophy', 3.5, 2.5, 2.5, 180);
  assert.match(checkBlueprint(trophy).errors.join('\n'), /a fixture has no trophy slots/);
  const missing = villageBoard();
  delete missing.anchors.spawn;
  assert.match(checkBlueprint(missing).errors.join('\n'), /missing required anchor 'spawn'/);
  // a building may not pass itself off as a fixture's shape: the building rules still apply to kind single
  const asBuilding = villageBoard();
  asBuilding.kind = 'single';
  asBuilding.wings = 1;
  assert.match(checkBlueprint(asBuilding).errors.join('\n'), /missing required anchor 'desk_juniper'/);
});
