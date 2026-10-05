// The village board (docs/VILLAGE.md V2): a fixture, not a building. A 5 x 3 agentcraft_worlds:village_board display under a
// dark-oak hood, between two stripped dark-oak posts with lanterns on top, on a stone-brick plinth; a paved strip in
// front where the player stands to read it. Everything but the display is vanilla, and the display has a dark-oak
// wall behind every cell, so the frame stays whole in a world opened without the mod.
//
// Template (x 0..6, y 0..6, z 0..3), front = south (the display faces +z):
//   y0      floor row: stone bricks (replaces the terrain's top block)
//   z0      back wall: dark oak planks behind the display, posts at x0 / x6 (z0..1), up to y5
//   z1      plinth y1 (stone bricks), display y2..4, a dark-oak slab hood y5
//   z2..3   the reading strip (walk): air, paved below
//   y6      lanterns on the posts (light the strip and the board at night)
import { Blueprint, B } from '../lib/kit.mjs';

export default function villageBoard() {
  const bp = new Blueprint({
    id: 'village_board',
    name: 'Village board',
    description: 'A fixture for the village square: a 5 x 3 board that shows every building (its lead, active goal and progress, open PRs and '
      + 'PRs merged this week), the newest milestones (goals done, PRs merged, trophies hung) and anything that holds the agents. '
      + 'Readable from about 8 blocks; right-click it for the hub (or the Inbox when something needs you). Takes no repos.',
    kind: 'fixture',
    size: [7, 7, 4],
    groundY: 1,
    front: 'south',
    materials: 'vanilla',
    foundationBlock: 'minecraft:stone_bricks',
    approach: false,
    walk: [0, 1, 2, 6, 2, 3],
  });
  const plank = 'minecraft:dark_oak_planks';
  const post = B.walnut; // stripped dark oak log
  // floor row and the reading strip
  bp.fill([0, 0, 0, 6, 0, 3], 'minecraft:stone_bricks');
  bp.carve([0, 1, 2, 6, 3, 3]);
  // posts and back wall
  for (const x of [0, 6]) for (const z of [0, 1]) bp.fill([x, 1, z, x, 5, z], post);
  bp.fill([1, 1, 0, 5, 5, 0], plank);
  // plinth, display (backed by the planks), hood
  bp.fill([1, 1, 1, 5, 1, 1], 'minecraft:stone_bricks');
  bp.villageBoard(1, 1, 5, 1, 'south', 3, { y: 2, backing: plank });
  bp.fill([1, 5, 1, 5, 5, 1], 'minecraft:dark_oak_slab', { type: 'bottom' });
  // lanterns on the posts (z1: the front pair)
  bp.lantern(0, 6, 1);
  bp.lantern(6, 6, 1);
  // where a teleport lands and agents could stand to read it; a close camera for QA shots
  bp.anchor('spawn', 3.5, 1, 3.5, 180);
  bp.camera('overview', [3.5, 2.6, 3.9], [3.5, 3.5, 1.2]);
  return bp;
}
