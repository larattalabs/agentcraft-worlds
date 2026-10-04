// Workshop: a one-repo AgentCraft office, 27 x 21 footprint, 10 tall (rows 0..9).
//
//   x ->  0                         13                        26
//   z=0   +--win-----+P+==== TASK WALL ====+P+-----win---CI---+   north wall
//   z=1   |LIB LIB LIB cat fern TERM|  9..17  |  bench  CONSOLE bench|
//   z=3   |                       [podium]                         |
//   z=4   |                       (user)                           |
//   z=7   |jun desk                                      rowan desk|   west desks: monitors on x=1 facing east
//   z=9   |                    ( goal pedestal )                   |
//   z=12  |kit desk      MEETING table x6..9       tove desk       |   east desks: monitors on x=25 facing west
//   z=16  |wren desk                  LOUNGE x17..22      MERGE   |
//   z=20  +--win--------------[ door ]--------------win------------+   south wall (front)
//
// Rows: y0 floor (parquet, terracotta runner), y1..6 interior (air), y7 ceiling with glow panels,
// y8.. a shallow gable roof (dark oak shingles, ridge east-west) with a 1-cell overhang, hollow attic and
// triangular gable windows. Outside: oak corner posts under the eaves, window sills, a porch awning over the
// door and a stone-brick path. Feet row = groundY = 1. Coordinates in the code are relative to the walls
// (0..W-1 x 0..D-1); the template origin is shifted by (1,0,1) for the overhang / porch margin.
import { Blueprint, B, lookYaw } from '../lib/kit.mjs';

export const id = 'workshop';

const W = 27;
const D = 21;
const H = 15;
const OX = 1;
const OZ = 1;

export default function build() {
  const bp = new Blueprint({
    id,
    name: 'Workshop',
    description: 'A one-repo office: five desks, a task wall, a decision podium, a meeting table, a small lounge, a rest corner, library, terminal, test bench and merge station.',
    kind: 'single',
    wings: 1,
    size: [W + 2 * OX, H, OZ + D + 10],
    origin: [OX, 0, OZ],
    groundY: 1,
    front: 'south',
    approach: { length: 6, width: 3, block: 'minecraft:stone_bricks', slab: 'minecraft:stone_brick_slab' }, // continues the porch path down/up to the terrain
    walk: [1, 1, 1, W - 2, 6, D - 2],
    foundationBlock: 'minecraft:cobblestone', // a rustic footing under the workshop on uneven ground
  });
  const X = W - 1;
  const Z = D - 1;

  // ---------------------------------------------------------------- shell
  bp.shell([0, 0, 0, X, 7, Z], { wall: B.plaster, floor: B.parquet, ceiling: B.plaster });
  for (let y = 1; y <= 6; y++) {
    for (let x = 0; x <= X; x++) for (const z of [0, Z]) bp.set(x, y, z, y === 1 ? B.walnutTrim : y === 6 ? B.plasterFrame : B.plaster);
    for (let z = 0; z <= Z; z++) for (const x of [0, X]) bp.set(x, y, z, y === 1 ? B.walnutTrim : y === 6 ? B.plasterFrame : B.plaster);
  }
  for (const [x, z] of [[0, 0], [X, 0], [0, Z], [X, Z]]) bp.fill([x, 1, z, x, 7, z], B.walnut);
  // pilasters
  for (const x of [8, 18]) bp.fill([x, 1, 0, x, 6, 0], B.walnut);
  for (const z of [6, 10, 14]) for (const x of [0, X]) bp.fill([x, 1, z, x, 6, z], B.walnut);
  for (const x of [12, 14]) bp.fill([x, 1, Z, x, 6, Z], B.walnut);

  // ---------------------------------------------------------------- windows
  for (const [a, b] of [[2, 7], [19, 24]]) bp.window(a, 4, 0, b, 5, 0);
  for (const [a, b] of [[1, 5], [7, 9], [11, 13], [15, 19]]) bp.window(0, 4, a, 0, 5, b);
  for (const [a, b] of [[1, 5], [7, 9], [11, 13], [18, 19]]) bp.window(X, 4, a, X, 5, b);
  for (const [a, b] of [[2, 11], [15, 24]]) bp.window(a, 2, Z, b, 5, Z);
  // front door (closed double-height entry with a transom), walnut surround
  bp.door(13, 1, Z, 'south');
  bp.set(13, 3, Z, B.pane);
  bp.fill([13, 4, Z, 13, 5, Z], B.plasterFrame);
  bp.set(12, 1, Z, B.walnut);
  bp.set(14, 1, Z, B.walnut);

  // ---------------------------------------------------------------- floor + ceiling
  bp.floor(12, 4, 14, Z, 0, B.tile); // runner from the door to the podium
  for (let dz = -2; dz <= 2; dz++) for (let dx = -2; dx <= 2; dx++) if (dx * dx + dz * dz <= 5) bp.set(13 + dx, 0, 9 + dz, B.tile); // goal atrium pad
  bp.ceilingLights(7, [5, 13, 21], [3, 8, 13, 18]);

  // ---------------------------------------------------------------- roof + exterior
  bp.roofGable(-1, -1, X + 1, Z + 1, 8, { ridge: 'x', pitch: 0.5, gable: B.plaster, gableInset: 1, stairs: 'minecraft:brick_stairs', slab: 'minecraft:brick_slab', full: 'minecraft:bricks' });
  // soffit under the overhang
  for (let x = -1; x <= X + 1; x++) for (const z of [-1, Z + 1]) bp.slab(x, 7, z, 'top', 'minecraft:dark_oak_slab');
  for (let z = 0; z <= Z; z++) for (const x of [-1, X + 1]) bp.slab(x, 7, z, 'top', 'minecraft:dark_oak_slab');
  // triangular gable windows
  for (const x of [0, X]) {
    bp.window(x, 9, 8, x, 10, 12);
    bp.window(x, 11, 9, x, 11, 11);
  }
  // corner posts carrying the eaves
  for (const [x, z] of [[-1, -1], [X + 1, -1], [-1, Z + 1], [X + 1, Z + 1]]) bp.post(x, z, 1, 6, 'minecraft:stripped_dark_oak_log');
  // window sills (dark oak slabs just below each window, on the outside)
  const sill = (x, y, z) => bp.slab(x, y, z, 'top', 'minecraft:dark_oak_slab');
  for (const [a, b] of [[2, 7], [19, 24]]) for (let x = a; x <= b; x++) sill(x, 3, -1);
  for (const [a, b] of [[1, 5], [7, 9], [11, 13], [15, 19]]) for (let z = a; z <= b; z++) sill(-1, 3, z);
  for (const [a, b] of [[1, 5], [7, 9], [11, 13], [18, 19]]) for (let z = a; z <= b; z++) sill(X + 1, 3, z);
  for (const [a, b] of [[2, 11], [15, 24]]) for (let x = a; x <= b; x++) sill(x, 1, Z + 1);
  // porch: awning of slabs on posts, tile deck, hanging lanterns, then a stone-brick path with planters
  bp.floor(10, Z + 1, 16, Z + 4, 0, B.tile);
  bp.awning(10, Z + 1, 16, Z + 4, 4, { posts: [[10, Z + 4], [16, Z + 4]], post: B.walnut });
  bp.fill([10, 4, Z + 4, 16, 4, Z + 4], B.walnutTrim);
  bp.lantern(12, 3, Z + 3, true);
  bp.lantern(14, 3, Z + 3, true);
  bp.floor(12, Z + 5, 14, Z + 10, 0, 'minecraft:stone_bricks');
  bp.floor(11, Z + 5, 11, Z + 10, 0, 'minecraft:polished_andesite');
  bp.floor(15, Z + 5, 15, Z + 10, 0, 'minecraft:polished_andesite');
  bp.floor(10, Z + 5, 10, Z + 10, 0, 'minecraft:stone_brick_slab');
  bp.floor(16, Z + 5, 16, Z + 10, 0, 'minecraft:stone_brick_slab');
  for (const x of [10, 16]) for (const z of [Z + 6, Z + 9]) bp.plant(x, 1, z);

  // ---------------------------------------------------------------- task wall, podium, user, goal
  bp.taskWall(9, 1, 17, 1, 'south', 4, 1);
  bp.podium(13, 3, 'south');
  bp.spot('user', 13, 4, 180);
  bp.spot('user', 11, 5, lookYaw(11.5, 5.5, 13.5, 4.5));
  bp.spot('user', 15, 5, lookYaw(15.5, 5.5, 13.5, 4.5));
  for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) if (dx || dz) bp.slab(13 + dx, 1, 9 + dz, 'bottom', 'minecraft:waxed_cut_copper_slab');
  bp.statusLamp(13, 1, 9, 'goal', 'idle');
  bp.anchor('goal_atrium', 13.5, 1, 9.5, 0);

  // ---------------------------------------------------------------- desks
  const west = [['juniper', 8], ['kit', 12], ['wren', 16]];
  for (const [agent, z] of west) bp.desk(1, z, 'east', agent);
  bp.desk(X - 1, 8, 'west', 'rowan');
  bp.desk(X - 1, 12, 'west', 'tove');
  const cabinet = (x, z, facing) => bp.set(x, 1, z, 'minecraft:chiseled_bookshelf', { facing, slot_0_occupied: 'true', slot_2_occupied: 'true', slot_3_occupied: 'true', slot_5_occupied: 'true' });
  cabinet(1, 10, 'east');
  bp.lantern(1, 2, 10);
  cabinet(1, 14, 'east');
  bp.plant(1, 2, 14);
  bp.plant(1, 1, 18);
  bp.plant(1, 1, 19);
  cabinet(X - 1, 10, 'west');
  bp.lantern(X - 1, 2, 10);
  bp.plant(X - 1, 1, 6);
  bp.plant(X - 1, 1, 14);

  // ---------------------------------------------------------------- library + terminal (north-west)
  bp.library(2, 1, 5, 1, 'south');
  bp.plant(6, 1, 1);
  bp.console(7, 1, 'south', 'terminal');

  // ---------------------------------------------------------------- test bench (north-east)
  for (const x of [20, 21, 23, 24]) bp.slab(x, 1, 1, 'top');
  bp.console(22, 1, 'south', 'testbench', { slots: 3 });
  bp.lantern(21, 2, 1);
  bp.candle(23, 2, 1, 3);
  bp.plant(X - 1, 1, 1);
  bp.wallLamp(22, 3, 0, 'south', 'ci:#1');

  // ---------------------------------------------------------------- merge station (east)
  bp.fill([X, 1, 15, X, 6, 17], B.walnut);
  bp.mergeStation(X - 1, 15, X - 1, 17, 'west');

  // ---------------------------------------------------------------- meeting (west-centre)
  bp.meeting(6, 12, 9, 12, [[6, 11, 0], [8, 11, 0], [7, 13, 180], [9, 13, 180]]);
  bp.lantern(7, 2, 12);
  bp.candle(9, 2, 12, 2);

  // ---------------------------------------------------------------- lounge (south-east)
  bp.rug(17, 14, 22, 19, 1);
  bp.coffeeTable(19, 16, 20, 16);
  bp.candle(19, 2, 16, 3);
  bp.plant(20, 2, 16);
  bp.loungeSeat(18, 16, -90);
  bp.loungeSeat(21, 16, 90);
  bp.loungeSeat(19, 14, 0);
  bp.loungeSeat(20, 14, 0);
  bp.loungeSeat(19, 18, 180);
  bp.loungeSeat(20, 18, 180);
  bp.plant(22, 1, 19);

  // ---------------------------------------------------------------- rest corner (south-west): three beds against the
  // front wall, heads south, a barrel nightstand with a candle between each pair (docs/VILLAGE.md V3 night routine)
  for (const x of [4, 6, 8]) bp.bed(x, Z - 1, 'south', { color: 'red' });
  for (const x of [5, 7]) {
    bp.set(x, 1, Z - 1, 'minecraft:barrel', { facing: 'up', open: 'false' });
    bp.candle(x, 2, Z - 1, 1);
  }

  // ---------------------------------------------------------------- entrance + cameras
  bp.spot('entrance', 13, 19, 180);
  bp.spot('spawn', 12, 18, 180);
  bp.camera('overview', [-10, 13, 32], [13.5, 3, 10]);
  bp.camera('interior', [24.5, 4.8, 19.0], [2, 2.5, 10]);
  bp.camera('task_wall', [13.5, 3.4, 17.5], [13.5, 3.5, 1.3]);
  // ---------------------------------------------------------------- trophy wall (east wall, north end, facing west)
  bp.trophyWall(W - 1, 2, W - 1, 4, 'west', { wing: 1 });

  return bp;
}
