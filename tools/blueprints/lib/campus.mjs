// Campus generator: buildCampus(wings) -> Blueprint (kind group). One building per repo is a *wing*; the wings sit in a
// row on both sides of a central hall, all under one long gable roof.
//
//  wings = 4:  [wing3][wing1][ HALL ][wing2][wing4]      (odd wings on the west, even wings on the east)
//  wings = 3:  [wing3][wing1][ HALL ][wing2]
//  wings = 2:           [wing1][ HALL ][wing2]
//
//  Hall (33 x 25 walls, x = hx0 .. hx0+32), north is up, front door in the south wall:
//
//   z=0   +=== north window wall ====================================+
//   z=2   |   [desk]   [desk]   [desk]   [desk]   [desk]   five desks face the glass
//   z=5   |LIB                                                 MERGE |   library z5..9, merge station z5..7
//   z=10  | TERM          ( goal atrium pad )    [podium]<user       |
//   z=13..16  arch gaps in both side partitions (4 wide, no doors)  |
//   z=17  |+--------+                         LOUNGE rug            |
//   z=20  || MEETING  door gap east z19..21     (sofas, table)       |
//   z=24  +=== south wall, door at hx 16 (porch + stone path) ========+
//
//  Wing (14 wide incl. a shared partition wall, 25 deep): task wall (`task_wall@k`, `repo:#k`) on its far wall z4..10 with k glow
//  pips above it, a test bench (`testbench`) + CI lamp (`ci:#k`) on the north wall, an arch gap to the next room at z13..16, a
//  floor inlay in the wing's colour (blue, green, orange, purple, yellow) and a matching frieze on the facade, so wings read apart.
//
//  Rows: y0 floor, y1..8 interior (everything is one 8 high floor), y9 ceiling + glow panels, y10.. gable roof (brick, half pitch,
//  ridge east-west), hollow attic, triangular gable windows at the two ends.
import { Blueprint, B, lookYaw } from './kit.mjs';

const WING_W = 14;
const HALL_W = 33;
const D = 25;
const Z = D - 1;
const CEIL = 9;
const OX = 1;
const OZ = 1;
const COLORS = ['blue', 'green', 'orange', 'purple', 'yellow'];
const STONE = 'minecraft:stone_bricks';

export function campusLayout(wings) {
  const west = Math.ceil(wings / 2);
  const east = Math.floor(wings / 2);
  const hx0 = WING_W * west;
  const hx1 = hx0 + HALL_W - 1;
  const X = hx1 + WING_W * east;
  const wing = [];
  for (let k = 1; k <= wings; k++) {
    const isWest = k % 2 === 1;
    const j = Math.ceil(k / 2); // 1 = adjacent to the hall
    const [x0, x1] = isWest ? [hx0 - WING_W * j, hx0 - WING_W * (j - 1)] : [hx1 + WING_W * (j - 1), hx1 + WING_W * j];
    wing.push({ k, isWest, j, x0, x1, hasNext: j < (isWest ? west : east), color: COLORS[(k - 1) % COLORS.length] });
  }
  return { west, east, hx0, hx1, X, wing };
}

export function buildCampus(wings) {
  if (!Number.isInteger(wings) || wings < 2 || wings > 5) throw new Error('campus wings must be 2..5');
  const { hx0, hx1, X, wing } = campusLayout(wings);
  const H = 18;
  const bp = new Blueprint({
    id: `campus${wings}`,
    name: `Campus (${wings} wings)`,
    description: `A ${wings}-repo campus: a central hall with five desks, meeting room, lounge, library, podium and goal atrium, plus one wing per repo with its own task wall, CI lamp and test bench.`,
    kind: 'group',
    wings,
    size: [X + 1 + 2 * OX, H, OZ + D + 8],
    origin: [OX, 0, OZ],
    groundY: 1,
    front: 'south',
    approach: { length: 6, width: 3, block: 'minecraft:stone_bricks', slab: 'minecraft:stone_brick_slab' }, // continues the porch path down/up to the terrain
    walk: [1, 1, 1, X - 1, 8, Z - 1],
  });
  const h = (dx) => hx0 + dx; // hall-local x
  const lines = [...new Set([...wing.flatMap((w) => [w.x0, w.x1]), hx0, hx1])].sort((a, b) => a - b);
  const inner = lines.filter((x) => x > 0 && x < X);

  // ---------------------------------------------------------------- shell + partitions
  bp.shell([0, 0, 0, X, CEIL, Z], { wall: B.plaster, floor: B.parquet, ceiling: B.plaster });
  for (let x = 0; x <= X; x++) for (const z of [0, Z]) bp.set(x, 1, z, B.walnutTrim);
  for (let z = 0; z <= Z; z++) for (const x of [0, X]) bp.set(x, 1, z, B.walnutTrim);
  for (const p of inner) {
    bp.fill([p, 1, 1, p, 8, Z - 1], B.plaster);
    bp.fill([p, 1, 1, p, 1, Z - 1], B.walnutTrim);
    bp.fill([p, 1, 13, p, 4, 16], B.air); // arch gap, no door
    bp.fill([p, 5, 13, p, 5, 16], B.walnutTrim);
    bp.fill([p, 6, 13, p, 6, 16], B.pane);
    bp.fill([p, 1, 12, p, 5, 12], B.walnut);
    bp.fill([p, 1, 17, p, 5, 17], B.walnut);
  }

  // ---------------------------------------------------------------- windows (exterior walls)
  bp.window(h(1), 2, 0, hx1 - 1, 7, 0); // hall: north window wall behind the desks
  for (const w of wing) {
    bp.window(w.x0 + 1, 4, 0, w.x1 - 1, 7, 0); // wings: above the test bench
    bp.window(w.x0 + 1, 2, Z, w.x1 - 1, 7, Z);
  }
  bp.window(h(1), 2, Z, h(9), 4, Z); // meeting room, low
  bp.window(h(11), 2, Z, h(14), 7, Z);
  bp.window(h(18), 2, Z, hx1 - 1, 7, Z);
  bp.window(0, 6, 1, 0, 7, Z - 1);
  bp.window(X, 6, 1, X, 7, Z - 1);
  for (const [a, b] of [[1, 4], [20, 23]]) {
    bp.window(0, 2, a, 0, 4, b);
    bp.window(X, 2, a, X, 4, b);
  }
  // front door
  const dx = h(16);
  bp.door(dx, 1, Z, 'south');
  bp.set(dx, 3, Z, B.pane);
  bp.fill([dx, 4, Z, dx, 5, Z], B.plasterFrame);
  bp.fill([dx - 1, 1, Z, dx - 1, 6, Z], B.walnut);
  bp.fill([dx + 1, 1, Z, dx + 1, 6, Z], B.walnut);
  bp.fill([dx - 1, 6, Z, dx + 1, 7, Z], B.plaster);
  // frieze on the facade: plaster frame on the hall, the wing's colour on its wing
  for (let x = 0; x <= X; x++) for (const z of [0, Z]) bp.set(x, 8, z, B.plasterFrame);
  for (const w of wing) for (let x = w.x0 + 1; x <= w.x1 - 1; x++) for (const z of [0, Z]) bp.set(x, 8, z, `minecraft:${w.color}_terracotta`);
  for (let z = 0; z <= Z; z++) for (const x of [0, X]) bp.set(x, 5, z, B.plasterFrame);
  // pilasters: every partition line, the corners, the hall's own rhythm
  const pil = [0, X, ...lines, h(8), h(26)];
  for (const x of pil) for (const z of [0, Z]) if (!(z === Z && x === dx)) bp.fill([x, 1, z, x, 8, z], B.walnut);
  for (const z of [0, 6, 12, 18, Z]) for (const x of [0, X]) bp.fill([x, 1, z, x, 8, z], B.walnut);
  for (const [x, z] of [[0, 0], [X, 0], [0, Z], [X, Z]]) bp.fill([x, 1, z, x, CEIL, z], B.walnut);

  // ---------------------------------------------------------------- floor + ceiling
  bp.floor(dx - 1, 12, dx + 1, Z - 1, 0, B.tile); // runner door -> atrium
  for (let ddz = -3; ddz <= 3; ddz++) for (let ddx = -3; ddx <= 3; ddx++) if (ddx * ddx + ddz * ddz <= 10) bp.set(dx + ddx, 0, 10 + ddz, B.tile);
  bp.fill([h(1), 0, 1, hx1 - 1, 0, 1], B.tile);
  for (let x = 5; x < X; x += 6) for (const z of [3, 9, 15, 21]) bp.glowPanel(x, CEIL, z);
  bp.ceilingLights(CEIL, [h(8), h(14), h(20), h(26)], [6, 12, 19]);

  // ---------------------------------------------------------------- roof: one long gable
  bp.roofGable(-1, -1, X + 1, Z + 1, 10, { ridge: 'x', pitch: 0.5, gable: B.plaster, gableInset: 1, stairs: 'minecraft:brick_stairs', slab: 'minecraft:brick_slab', full: 'minecraft:bricks' });
  for (const x of [0, X]) {
    bp.window(x, 11, 9, x, 12, 15);
    bp.window(x, 13, 10, x, 13, 14);
    bp.window(x, 14, 11, x, 14, 13);
    bp.window(x, 15, 12, x, 15, 12);
  }
  for (let x = -1; x <= X + 1; x++) for (const z of [-1, Z + 1]) bp.slab(x, 9, z, 'top', 'minecraft:dark_oak_slab');
  for (let z = 0; z <= Z; z++) for (const x of [-1, X + 1]) bp.slab(x, 9, z, 'top', 'minecraft:dark_oak_slab');
  for (const [x, z] of [[-1, -1], [X + 1, -1], [-1, Z + 1], [X + 1, Z + 1]]) bp.post(x, z, 1, 8, 'minecraft:stripped_dark_oak_log');
  for (const x of lines) for (const z of [-1, Z + 1]) bp.post(x, z, 1, 8, 'minecraft:stripped_dark_oak_log');
  // plinth + sills
  for (let x = -1; x <= X + 1; x++) for (const z of [-1, Z + 1]) if (!(z === Z + 1 && Math.abs(x - dx) <= 3)) bp.set(x, 0, z, STONE);
  for (let z = 0; z <= Z; z++) for (const x of [-1, X + 1]) bp.set(x, 0, z, STONE);
  const sill = (x, y, z) => bp.slab(x, y, z, 'top', 'minecraft:dark_oak_slab');
  for (let x = 1; x < X; x++) {
    if (Math.abs(x - dx) > 3) sill(x, 1, Z + 1);
    if (!inner.includes(x) && (x < hx0 || x > hx1)) sill(x, 3, -1);
  }
  // porch + path
  bp.floor(dx - 3, Z + 1, dx + 3, Z + 4, 0, B.tile);
  bp.awning(dx - 3, Z + 1, dx + 3, Z + 4, 5, { posts: [[dx - 3, Z + 4], [dx + 3, Z + 4], [dx - 3, Z + 1], [dx + 3, Z + 1]] });
  bp.fill([dx - 3, 5, Z + 4, dx + 3, 5, Z + 4], B.walnutTrim);
  for (const [x, z] of [[dx - 1, Z + 3], [dx + 1, Z + 3], [dx - 1, Z + 2], [dx + 1, Z + 2]]) bp.lantern(x, 4, z, true);
  bp.floor(dx - 1, Z + 5, dx + 1, Z + 8, 0, STONE);
  bp.floor(dx - 2, Z + 5, dx - 2, Z + 8, 0, 'minecraft:polished_andesite');
  bp.floor(dx + 2, Z + 5, dx + 2, Z + 8, 0, 'minecraft:polished_andesite');
  bp.floor(dx - 3, Z + 5, dx - 3, Z + 8, 0, 'minecraft:stone_brick_slab');
  bp.floor(dx + 3, Z + 5, dx + 3, Z + 8, 0, 'minecraft:stone_brick_slab');
  for (const x of [dx - 3, dx + 3]) for (const z of [Z + 6, Z + 8]) bp.plant(x, 1, z);

  // ---------------------------------------------------------------- hall: desks (north window wall)
  const cast = [['juniper', 5], ['kit', 11], ['wren', 17], ['rowan', 23], ['tove', 29]];
  for (const [agent, x] of cast) bp.desk(h(x), 2, 'south', agent);
  for (const x of [2, 14, 20, 32 - 2]) bp.plant(h(x), 1, 1);
  for (const x of [8, 26]) {
    bp.set(h(x), 1, 1, 'minecraft:barrel', { facing: 'up', open: 'false' });
    bp.lantern(h(x), 2, 1);
  }

  // ---------------------------------------------------------------- hall: library + terminal (west partition)
  bp.library(h(1), 5, h(1), 9, 'east');
  bp.plant(h(1), 1, 4);
  bp.console(h(1), 11, 'east', 'terminal');
  bp.rug(h(3), 5, h(4), 9, 1, { fill: 'minecraft:brown_carpet', border: 'minecraft:brown_carpet' });
  bp.lantern(h(1), 4, 5);

  // ---------------------------------------------------------------- hall: goal atrium, podium
  for (let ddz = -1; ddz <= 1; ddz++) for (let ddx = -1; ddx <= 1; ddx++) if (ddx || ddz) bp.slab(dx + ddx, 1, 10 + ddz, 'bottom', 'minecraft:waxed_cut_copper_slab');
  bp.statusLamp(dx, 1, 10, 'goal', 'idle');
  bp.anchor('goal_atrium', dx + 0.5, 1, 10.5, 0);
  bp.podium(h(21), 10, 'east');
  bp.spot('user', h(22), 10, 90);
  bp.spot('user', h(22), 8, lookYaw(h(22) + 0.5, 8.5, h(21) + 0.5, 10.5));
  bp.spot('user', h(22), 12, lookYaw(h(22) + 0.5, 12.5, h(21) + 0.5, 10.5));
  bp.floorRing(h(21), 7, h(23), 13, 0, B.tile);

  // ---------------------------------------------------------------- hall: merge station (east partition)
  bp.mergeStation(h(31), 5, h(31), 7, 'west');

  // ---------------------------------------------------------------- hall: meeting room (glass, door gap east)
  const R = { x0: h(1), x1: h(10), z0: 17, z1: 23 };
  bp.fill([R.x0, 5, R.z0, R.x1, 5, R.z1], B.plaster);
  for (let x = R.x0; x <= R.x1; x++) { bp.set(x, 1, R.z0, B.walnutTrim); bp.fill([x, 2, R.z0, x, 4, R.z0], B.pane); }
  for (let z = R.z0; z <= R.z1; z++) {
    if (z >= 19 && z <= 21) continue;
    bp.set(R.x1, 1, z, B.walnutTrim);
    bp.fill([R.x1, 2, z, R.x1, 4, z], B.pane);
  }
  for (const [x, z] of [[R.x1, R.z0], [R.x1, 18], [R.x1, 22], [R.x1, R.z1], [h(5), R.z0]]) bp.fill([x, 1, z, x, 4, z], B.walnut);
  bp.fill([R.x1, 5, 19, R.x1, 5, 21], B.walnutTrim);
  bp.meeting(h(4), 20, h(7), 20, [[h(4), 19, 0], [h(6), 19, 0], [h(5), 21, 180], [h(7), 21, 180], [h(3), 20, -90], [h(8), 20, 90]]);
  bp.ceilingLights(5, [h(3), h(6), h(8)], [19, 22]);
  bp.lantern(h(5), 2, 20);
  bp.candle(h(7), 2, 20, 2);
  bp.plant(h(2), 1, 18);
  bp.plant(h(2), 1, 23);

  // ---------------------------------------------------------------- hall: lounge (south-east)
  bp.rug(h(20), 17, h(30), 23, 1, {});
  bp.coffeeTable(h(24), 20, h(25), 20);
  bp.candle(h(24), 2, 20, 3);
  bp.plant(h(25), 2, 20);
  bp.loungeSeat(h(22), 20, -90);
  bp.loungeSeat(h(27), 20, 90);
  bp.loungeSeat(h(24), 18, 0);
  bp.loungeSeat(h(25), 18, 0);
  bp.loungeSeat(h(24), 22, 180);
  bp.loungeSeat(h(25), 22, 180);
  bp.plant(h(20), 1, 23);
  bp.plant(h(30), 1, 23);
  bp.plant(h(30), 1, 17);
  // corner lantern on a barrel: the hall's south-east corner is the farthest cell from the ceiling lights
  bp.set(h(31), 1, Z - 1, 'minecraft:barrel', { facing: 'up', open: 'false' });
  bp.lantern(h(31), 2, Z - 1);

  // ---------------------------------------------------------------- entrance
  bp.spot('entrance', dx, Z - 1, 180);
  bp.spot('spawn', dx - 1, Z - 2, 180);

  // ---------------------------------------------------------------- wings
  for (const w of wing) {
    const cx = Math.floor((w.x0 + w.x1) / 2);
    const colour = `minecraft:${w.color}_terracotta`;
    const far = w.isWest ? w.x0 : w.x1; // outer partition / end wall
    const boardX = w.isWest ? far + 1 : far - 1;
    const facing = w.isWest ? 'east' : 'west';
    bp.taskWall(boardX, 4, boardX, 10, facing, 4, w.k, { light: false }); // dark header: the wing-number pips above it read clearly
    // k glow pips above the board = the wing's number
    for (let i = 0; i < w.k; i++) bp.glowPanel(boardX, 7, 7 - (w.k - 1) + 2 * i);
    // test bench + CI lamp on the north wall
    for (const o of [-2, -1, 1, 2]) bp.slab(cx + o, 1, 1, 'top');
    bp.console(cx, 1, 'south', 'testbench', { slots: 2, wing: w.k }); // shared slots + testbench@k / testbench_2@k
    bp.lantern(cx - 2, 2, 1);
    bp.candle(cx + 2, 2, 1, 3);
    bp.wallLamp(cx, 3, 0, 'south', `ci:#${w.k}`);
    // floor inlay in the wing colour, tile border
    bp.fill([cx - 4, 0, 8, cx + 4, 0, 20], colour);
    bp.floorRing(cx - 4, 8, cx + 4, 20, 0, B.tile);
    // a long worktable with lights, plants in the corners
    for (let x = cx - 2; x <= cx + 2; x++) bp.slab(x, 1, 14, 'top');
    bp.lantern(cx - 2, 2, 14);
    bp.lantern(cx + 2, 2, 14);
    bp.candle(cx, 2, 14, 2);
    const cabinet = (x, z, f) => bp.set(x, 1, z, 'minecraft:chiseled_bookshelf', { facing: f, slot_0_occupied: 'true', slot_2_occupied: 'true', slot_3_occupied: 'true', slot_5_occupied: 'true' });
    cabinet(w.x0 + 1, 1, 'south');
    cabinet(w.x1 - 1, 1, 'south');
    for (const [x, z] of [[w.x0 + 1, Z - 1], [w.x1 - 1, Z - 1], [w.x0 + 1, 12], [w.x1 - 1, 12]]) bp.plant(x, 1, z);
    // a coloured band under the clerestory so each wing is recognisable from inside too
    for (let x = w.x0 + 1; x <= w.x1 - 1; x++) bp.set(x, 5, Z, colour);
    // trophy wall: inner partitions south of the arch gap (both sides of the wall share its backing), outer walls beside the arch rows
    if (w.hasNext) bp.trophyWall(far, 18, far, 20, facing, { wing: w.k });
    else bp.trophyWall(far, 13, far, 15, facing, { wing: w.k });
    // cameras inside the wing, looking at its board
    const camX = w.isWest ? w.x1 - 2 : w.x0 + 2;
    bp.camera(`wing${w.k}`, [camX + 0.5, 4.2, 18.5], [boardX + 0.5, 3.4, 7.5]);
  }

  // ---------------------------------------------------------------- cameras
  bp.camera('overview', [-14, 18, Z + 24], [(X + 1) / 2, 4, 12]);
  bp.camera('desks', [dx + 0.5, 4.5, 22], [dx + 0.5, 3, 2]);
  bp.camera('hall', [h(2) + 0.5, 5, 22.5], [h(22), 1.5, 8]);
  return bp;
}
