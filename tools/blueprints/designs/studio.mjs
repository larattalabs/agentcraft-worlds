// Studio: a premium one-repo AgentCraft office. Interior 33 x 25, double-height hall (rows 1..8), one floor.
// Footprint 35 x 27 walls (x 0..34, z 0..26); the template adds a 1-cell eave margin and a porch/path to the south.
//
//   x ->  0         10        17        24            34
//   z=0   +=== north window wall (rows 2..8, walnut pilasters) =============+
//   z=1   |  walkway / sill bench / planters                                |
//   z=2   |  [desk]    [desk]    [desk]    [desk]    [desk]   5 desks, monitors face south, sitters face the glass
//   z=3   |   jun        kit       wren      rowan     tove                |
//   z=5   | LIB                                              TEST  bench   |  library: west wall z6..10, test bench: east wall z5..9
//   z=11  | TERM          (user)<podium  <==== TASK WALL ====  z9..17       |  task wall on the east wall, podium + user in front
//   z=12  |         [ goal atrium pad x17 z12 ]                              |
//   z=15  |+--------+ MEETING (glass room, 3-wide door gap z19..21)          |
//   z=18  ||  table |             LOUNGE rug x21..31 z18..24    MERGE z20..22|
//   z=25  |+--------+                                                       |
//   z=26  +=== south wall, door at x=17 (porch + stone path) =================+
//
// Rows: y0 floor (parquet, tile runner), y1..8 hall air, y5 exterior belt, y6..8 clerestory band, y9 ceiling
// with glow panels + skylights (glass at y9 and on the roof plateau, plaster shaft between), y10..13 hip roof
// (brick stairs rising 3 rows, plateau of terracotta tile at y13). Meeting room has its own flat ceiling at y5.
import { Blueprint, B, lookYaw } from '../lib/kit.mjs';

export const id = 'studio';

const W = 35;
const D = 27;
const X = W - 1;
const Z = D - 1;
const H = 14;
const OX = 1;
const OZ = 1;
const CEIL = 9;

export default function build() {
  const bp = new Blueprint({
    id,
    name: 'Studio',
    description: 'A premium one-repo office: double-height hall with skylights, a window wall behind five desks, task wall, decision podium, glass meeting room, library, lounge, terminal, test bench and merge station.',
    kind: 'single',
    wings: 1,
    size: [W + 2 * OX, H, OZ + D + 8],
    origin: [OX, 0, OZ],
    groundY: 1,
    front: 'south',
    approach: { length: 6, width: 3, block: 'minecraft:stone_bricks', slab: 'minecraft:stone_brick_slab' }, // continues the porch path down/up to the terrain
    walk: [1, 1, 1, X - 1, 8, Z - 1],
  });

  // ---------------------------------------------------------------- shell
  bp.shell([0, 0, 0, X, CEIL, Z], { wall: B.plaster, floor: B.parquet, ceiling: B.plaster });
  // skirting + belt + pilasters
  for (let x = 0; x <= X; x++) for (const z of [0, Z]) bp.set(x, 1, z, B.walnutTrim);
  for (let z = 0; z <= Z; z++) for (const x of [0, X]) bp.set(x, 1, z, B.walnutTrim);
  for (let y = 5; y <= 5; y++) {
    for (let x = 1; x < X; x++) bp.set(x, y, Z, B.plasterFrame);
    for (let z = 0; z <= Z; z++) for (const x of [0, X]) bp.set(x, y, z, B.plasterFrame);
  }
  for (let y = 8; y <= 8; y++) {
    for (let x = 0; x <= X; x++) for (const z of [0, Z]) bp.set(x, y, z, B.plasterFrame);
    for (let z = 0; z <= Z; z++) for (const x of [0, X]) bp.set(x, y, z, B.plasterFrame);
  }
  const pilasterX = [0, 8, 17, 26, X]; // (17 on the south wall is the door)
  const pilasterZ = [0, 6, 12, 18, 24, Z];

  // ---------------------------------------------------------------- windows (before pilasters)
  bp.window(1, 2, 0, X - 1, 7, 0); // north window wall behind the desks
  // south: meeting-room low windows, flanks, lounge
  bp.window(1, 2, Z, 9, 4, Z);
  bp.window(11, 2, Z, 15, 7, Z);
  bp.window(19, 2, Z, X - 1, 4, Z);
  bp.window(19, 6, Z, X - 1, 7, Z);
  bp.window(11, 6, Z, 15, 7, Z);
  // west + east clerestory bands and low windows
  bp.window(0, 6, 1, 0, 7, Z - 1);
  bp.window(X, 6, 1, X, 7, Z - 1);
  for (const [a, b] of [[1, 4], [13, 14], [16, 24]]) bp.window(0, 2, a, 0, 4, b);
  bp.window(X, 2, 1, X, 4, 4);
  bp.window(X, 2, 24, X, 4, 25);
  // front door (iron, stone buttons inside and out on the east jamb) + transom, walnut surround
  bp.door(17, 1, Z, 'south');
  bp.set(17, 3, Z, B.pane);
  bp.fill([17, 4, Z, 17, 5, Z], B.plasterFrame);
  bp.set(16, 1, Z, B.walnut);
  bp.set(18, 1, Z, B.walnut);
  bp.fill([16, 2, Z, 16, 6, Z], B.walnut);
  bp.fill([18, 2, Z, 18, 6, Z], B.walnut);
  bp.fill([16, 6, Z, 18, 7, Z], B.plaster);
  // pilasters + corners
  for (const x of pilasterX) for (const z of [0, Z]) if (!(z === Z && x === 17)) bp.fill([x, 1, z, x, CEIL - 1, z], B.walnut);
  for (const z of pilasterZ) for (const x of [0, X]) bp.fill([x, 1, z, x, CEIL - 1, z], B.walnut);
  for (const [x, z] of [[0, 0], [X, 0], [0, Z], [X, Z]]) bp.fill([x, 1, z, x, CEIL, z], B.walnut);

  // ---------------------------------------------------------------- floor + ceiling
  bp.floor(16, 12, 18, Z - 1, 0, B.tile); // runner from the door to the atrium
  bp.floorRing(14, 9, 20, 15, 0, B.tile);
  for (let dz = -3; dz <= 3; dz++) for (let dx = -3; dx <= 3; dx++) if (dx * dx + dz * dz <= 10) bp.set(17 + dx, 0, 12 + dz, B.tile); // goal atrium pad
  bp.fill([1, 0, 1, X - 1, 0, 1], B.tile);
  bp.ceilingLights(CEIL, [5, 11, 17, 23, 29], [3, 9, 15, 22]);
  bp.ceilingLights(CEIL, [8, 14, 20, 26], [6, 12, 19]);
  // skylights over the atrium and a strip over the hall; light shafts through the attic
  const skylight = (x0, z0, x1, z1) => {
    bp.fill([x0, CEIL, z0, x1, CEIL, z1], B.glass);
    bp.fill([x0, 13, z0, x1, 13, z1], B.glass);
    for (let y = 10; y <= 12; y++) {
      for (let x = x0 - 1; x <= x1 + 1; x++) for (const z of [z0 - 1, z1 + 1]) bp.set(x, y, z, B.plaster);
      for (let z = z0; z <= z1; z++) for (const x of [x0 - 1, x1 + 1]) bp.set(x, y, z, B.plaster);
    }
  };
  skylight(13, 10, 21, 14);
  skylight(4, 6, 12, 7);
  skylight(22, 6, 30, 7);
  skylight(24, 18, 30, 20);

  // ---------------------------------------------------------------- roof + exterior
  bp.roofHip(-1, -1, X + 1, Z + 1, 10, {
    rise: 3, stairs: 'minecraft:brick_stairs', top: B.tile, under: B.plaster,
  });
  // re-assert skylight glass on the plateau (roofHip wrote the tile deck first)
  for (const [x0, z0, x1, z1] of [[13, 10, 21, 14], [4, 6, 12, 7], [22, 6, 30, 7], [24, 18, 30, 20]]) bp.fill([x0, 13, z0, x1, 13, z1], B.glass);
  // soffit under the eave, corner posts, walnut fascia
  for (let x = -1; x <= X + 1; x++) for (const z of [-1, Z + 1]) bp.slab(x, 9, z, 'top', 'minecraft:dark_oak_slab');
  for (let z = 0; z <= Z; z++) for (const x of [-1, X + 1]) bp.slab(x, 9, z, 'top', 'minecraft:dark_oak_slab');
  for (const [x, z] of [[-1, -1], [X + 1, -1], [-1, Z + 1], [X + 1, Z + 1]]) bp.post(x, z, 1, 8, 'minecraft:stripped_dark_oak_log');
  // plinth ring (stone brick) around the building, sills under the lower windows
  for (let x = -1; x <= X + 1; x++) for (const z of [-1, Z + 1]) if (!(z === Z + 1 && x >= 14 && x <= 20)) bp.set(x, 0, z, 'minecraft:stone_bricks');
  for (let z = 0; z <= Z; z++) for (const x of [-1, X + 1]) bp.set(x, 0, z, 'minecraft:stone_bricks');
  const sill = (x, y, z) => bp.slab(x, y, z, 'top', 'minecraft:dark_oak_slab');
  for (let x = 1; x <= 9; x++) sill(x, 1, Z + 1);
  for (let x = 11; x <= 12; x++) sill(x, 1, Z + 1);
  for (let x = 21; x <= X - 1; x++) sill(x, 1, Z + 1);
  for (let x = 1; x <= X - 1; x++) sill(x, 1, -1);
  // porch: tile deck, slab awning on walnut posts, hanging lanterns, stone path with planters
  bp.floor(14, Z + 1, 20, Z + 4, 0, B.tile);
  bp.awning(14, Z + 1, 20, Z + 4, 5, { posts: [[14, Z + 4], [20, Z + 4], [14, Z + 1], [20, Z + 1]], post: B.walnut });
  bp.fill([14, 5, Z + 4, 20, 5, Z + 4], B.walnutTrim);
  bp.lantern(16, 4, Z + 3, true);
  bp.lantern(18, 4, Z + 3, true);
  bp.lantern(16, 4, Z + 2, true);
  bp.lantern(18, 4, Z + 2, true);
  bp.floor(16, Z + 5, 18, Z + 8, 0, 'minecraft:stone_bricks');
  bp.floor(15, Z + 5, 15, Z + 8, 0, 'minecraft:polished_andesite');
  bp.floor(19, Z + 5, 19, Z + 8, 0, 'minecraft:polished_andesite');
  bp.floor(14, Z + 5, 14, Z + 8, 0, 'minecraft:stone_brick_slab');
  bp.floor(20, Z + 5, 20, Z + 8, 0, 'minecraft:stone_brick_slab');
  for (const x of [14, 20]) for (const z of [Z + 6, Z + 8]) bp.plant(x, 1, z);

  // ---------------------------------------------------------------- desks (row facing the north window wall)
  const cast = [['juniper', 5], ['kit', 11], ['wren', 17], ['rowan', 23], ['tove', 29]];
  for (const [agent, x] of cast) bp.desk(x, 2, 'south', agent);
  for (const x of [2, 14, 20, 32]) bp.plant(x, 1, 1);
  for (const x of [8, 26]) {
    bp.set(x, 1, 1, 'minecraft:barrel', { facing: 'up', open: 'false' });
    bp.lantern(x, 2, 1);
  }

  // ---------------------------------------------------------------- library + terminal (west wall)
  bp.library(1, 6, 1, 10, 'east');
  bp.plant(1, 1, 5);
  bp.plant(1, 1, 11);
  bp.console(1, 12, 'east', 'terminal');
  bp.rug(3, 6, 4, 10, 1, { fill: 'minecraft:brown_carpet', border: 'minecraft:brown_carpet' }); // reading carpet strip (library spots stand at x=2, off the carpet)
  bp.lantern(1, 4, 6);

  // ---------------------------------------------------------------- task wall, podium, user (east wall)
  bp.taskWall(X - 1, 10, X - 1, 16, 'west', 4, 1);
  bp.podium(X - 3, 13, 'west');
  bp.spot('user', X - 4, 13, -90);
  bp.spot('user', X - 4, 11, lookYaw(X - 3.5, 11.5, X - 2.5, 13.5));
  bp.spot('user', X - 4, 15, lookYaw(X - 3.5, 15.5, X - 2.5, 13.5));
  bp.floorRing(X - 5, 10, X - 2, 16, 0, B.tile);

  // ---------------------------------------------------------------- goal atrium
  for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) if (dx || dz) bp.slab(17 + dx, 1, 12 + dz, 'bottom', 'minecraft:waxed_cut_copper_slab');
  bp.statusLamp(17, 1, 12, 'goal', 'idle');
  bp.anchor('goal_atrium', 17.5, 1, 12.5, 0);
  // pendant lanterns under the atrium skylight (it replaces the ceiling lights there)
  for (const [x, z] of [[15, 11], [19, 11], [15, 13], [19, 13]]) bp.lantern(x, CEIL - 1, z, true);

  // ---------------------------------------------------------------- test bench (east wall, north)
  for (const z of [5, 6, 8, 9]) bp.slab(X - 1, 1, z, 'top');
  bp.console(X - 1, 7, 'west', 'testbench', { slots: 3 });
  bp.lantern(X - 1, 2, 5);
  bp.candle(X - 1, 2, 9, 3);
  bp.plant(X - 1, 1, 4);
  bp.wallLamp(X, 3, 7, 'west', 'ci:#1');

  // ---------------------------------------------------------------- merge station (east wall, south)
  bp.fill([X, 1, 20, X, 6, 22], B.walnut);
  bp.mergeStation(X - 1, 20, X - 1, 22, 'west');

  // ---------------------------------------------------------------- meeting room (glass, door gap on its east side)
  const R = { x0: 1, x1: 10, z0: 15, z1: 25 };
  bp.fill([R.x0, 5, R.z0, R.x1, 5, R.z1], B.plaster); // room ceiling
  for (let x = R.x0; x <= R.x1; x++) { bp.fill([x, 1, R.z0, x, 1, R.z0], B.walnutTrim); bp.fill([x, 2, R.z0, x, 4, R.z0], B.pane); }
  for (let z = R.z0; z <= R.z1; z++) {
    if (z >= 19 && z <= 21) continue; // door gap (no door)
    bp.set(R.x1, 1, z, B.walnutTrim);
    bp.fill([R.x1, 2, z, R.x1, 4, z], B.pane);
  }
  for (const [x, z] of [[R.x1, R.z0], [R.x1, 18], [R.x1, 22], [R.x1, R.z1]]) bp.fill([x, 1, z, x, 4, z], B.walnut);
  for (const x of [5]) bp.fill([x, 1, R.z0, x, 4, R.z0], B.walnut);
  bp.fill([R.x1, 5, 19, R.x1, 5, 21], B.walnutTrim); // lintel over the gap
  bp.meeting(4, 19, 7, 20, [[4, 18, 0], [6, 18, 0], [5, 21, 180], [7, 21, 180], [3, 19, -90], [8, 20, 90]]);
  bp.ceilingLights(5, [3, 6, 8], [17, 20, 23]);
  bp.lantern(4, 2, 19);
  bp.candle(7, 2, 20, 2);
  bp.plant(2, 1, 16);
  bp.plant(2, 1, 24);

  // ---------------------------------------------------------------- lounge (south-east)
  bp.rug(21, 18, 31, 24, 1, {});
  bp.coffeeTable(25, 21, 26, 21);
  bp.candle(25, 2, 21, 3);
  bp.plant(26, 2, 21);
  bp.loungeSeat(23, 21, -90);
  bp.loungeSeat(28, 21, 90);
  bp.loungeSeat(25, 19, 0);
  bp.loungeSeat(26, 19, 0);
  bp.loungeSeat(25, 23, 180);
  bp.loungeSeat(26, 23, 180);
  bp.plant(21, 1, 24);
  bp.plant(31, 1, 24);
  bp.plant(21, 1, 18);
  // corner lantern on a barrel: the south-east corner is the farthest cell from the ceiling lights (C5 light check)
  bp.set(X - 1, 1, Z - 1, 'minecraft:barrel', { facing: 'up', open: 'false' });
  bp.lantern(X - 1, 2, Z - 1);

  // ---------------------------------------------------------------- entrance + cameras
  bp.spot('entrance', 17, Z - 1, 180);
  bp.spot('spawn', 16, Z - 2, 180);
  bp.camera('overview', [-12, 16, Z + 22], [17.5, 4, 12]);
  bp.camera('desks', [17.5, 4.5, 21], [17.5, 3, 2]);
  bp.camera('task_wall', [12, 4.5, 6.5], [X - 1.2, 3.5, 13.5]);
  bp.camera('meeting', [9.2, 3.8, 24.5], [4.5, 1.8, 19.5]);
  bp.camera('lounge', [19, 4.5, 24.5], [26, 1.5, 21]);
  return bp;
}
