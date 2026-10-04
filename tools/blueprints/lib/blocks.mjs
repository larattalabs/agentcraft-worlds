// Table of the blocks the blueprint kit knows: full property domains, defaults and a coarse collision
// class (used by the checker). Every palette entry is written with ALL of its properties explicit.
// AgentCraft blocks: mod/src/main/java/dev/agentcraft/block/ModBlocks.java + assets/agentcraft/blockstates.
// Vanilla ids/properties are verified against the 26.3 client jar's blockstate files (see README).
//
// collision: 'full' (solid cube), 'none' (walk-through / air), 'low' (carpet-thin, walkable),
//            'partial' (collides, not a cube), 'thin' (pane/fence), 'door' (collides only when closed),
//            'stairs' | 'slab' (collide, usable as seat / floor).

const BOOL = ['false', 'true'];
const H4 = ['north', 'east', 'south', 'west'];
const H6 = ['north', 'east', 'south', 'west', 'up', 'down'];
const STAIR_SHAPES = ['straight', 'inner_left', 'inner_right', 'outer_left', 'outer_right'];

const facing = (domain = H4) => ({ facing: domain });
const panelProps = { facing: H4, up: BOOL, down: BOOL, left: BOOL, right: BOOL };

/** name -> { props: {prop: [values]}, defaults: {prop: value}, collision } ; first value is the default unless overridden. */
const T = {};
function def(name, props = {}, collision = 'full', defaults = {}) {
  const d = {};
  for (const [k, vals] of Object.entries(props)) d[k] = defaults[k] ?? vals[0];
  for (const k of Object.keys(defaults)) if (!(k in props)) throw new Error(`${name}: default for unknown property ${k}`);
  T[name] = { props, defaults: d, collision };
}

// ---- AgentCraft ----
def('agentcraft:monitor', { ...panelProps, lit: BOOL }, 'partial', { facing: 'south', up: 'false', down: 'false', left: 'false', right: 'false', lit: 'false' });
def('agentcraft:task_board', panelProps, 'partial', { facing: 'south', up: 'false', down: 'false', left: 'false', right: 'false' });
def('agentcraft:decision_podium', { facing: H4, open: BOOL }, 'partial', { facing: 'south', open: 'false' });
def('agentcraft:memory_archive', facing(), 'full', { facing: 'south' });
def('agentcraft:memory_catalog', facing(), 'full', { facing: 'south' });
def('agentcraft:merge_station', { facing: H4, active: BOOL }, 'partial', { facing: 'south', active: 'false' });
def('agentcraft:status_lamp', { status: ['off', 'idle', 'thinking', 'working', 'waiting', 'error', 'done'] }, 'partial', { status: 'off' });
def('agentcraft:console_terminal', facing(), 'partial', { facing: 'south' });
def('agentcraft:glow_strip', { facing: H6, axis: ['x', 'z'] }, 'none', { facing: 'up', axis: 'x' });
for (const n of ['glow_panel', 'plaster_panel', 'plaster_frame', 'walnut_panel', 'walnut_trim', 'terracotta_tile', 'oak_parquet']) def(`agentcraft:${n}`);

// ---- vanilla ----
def('minecraft:air', {}, 'none');
for (const wood of ['dark_oak', 'birch', 'pale_oak']) {
  def(`minecraft:${wood}_stairs`, { facing: H4, half: ['bottom', 'top'], shape: STAIR_SHAPES, waterlogged: BOOL }, 'stairs', { facing: 'north', half: 'bottom', shape: 'straight', waterlogged: 'false' });
  def(`minecraft:${wood}_slab`, { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
  def(`minecraft:${wood}_planks`);
}
def('minecraft:brown_wool_stairs', { facing: H4, half: ['bottom', 'top'], shape: STAIR_SHAPES, waterlogged: BOOL }, 'stairs', { facing: 'north', half: 'bottom', shape: 'straight', waterlogged: 'false' });
def('minecraft:brown_wool_slab', { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
def('minecraft:waxed_cut_copper_slab', { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
def('minecraft:waxed_cut_copper', {});
def('minecraft:stripped_dark_oak_log', { axis: ['x', 'y', 'z'] }, 'full', { axis: 'y' });
def('minecraft:dark_oak_door', { facing: H4, half: ['lower', 'upper'], hinge: ['left', 'right'], open: BOOL, powered: BOOL }, 'door', { facing: 'south', half: 'lower', hinge: 'left', open: 'false', powered: 'false' });
for (const m of ['brick', 'stone_brick']) {
  def(`minecraft:${m}_stairs`, { facing: H4, half: ['bottom', 'top'], shape: STAIR_SHAPES, waterlogged: BOOL }, 'stairs', { facing: 'north', half: 'bottom', shape: 'straight', waterlogged: 'false' });
  def(`minecraft:${m}_slab`, { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
  def(`minecraft:${m}s`);
}
def('minecraft:smooth_stone_slab', { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
def('minecraft:polished_andesite');
for (const c of ['blue', 'green', 'orange', 'purple', 'cyan', 'red', 'yellow', 'white', 'light_blue']) {
  def(`minecraft:${c}_terracotta`);
  def(`minecraft:${c}_carpet`, {}, 'low');
}
for (const m of ['brick', 'stone_brick']) {
  def(`minecraft:${m}_stairs`, { facing: H4, half: ['bottom', 'top'], shape: STAIR_SHAPES, waterlogged: BOOL }, 'stairs', { facing: 'north', half: 'bottom', shape: 'straight', waterlogged: 'false' });
  def(`minecraft:${m}_slab`, { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
  def(`minecraft:${m}s`);
}
def('minecraft:smooth_stone_slab', { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
def('minecraft:polished_andesite');
for (const c of ['blue', 'green', 'orange', 'purple', 'cyan', 'red', 'yellow', 'white', 'light_blue']) {
  def(`minecraft:${c}_terracotta`);
  def(`minecraft:${c}_carpet`, {}, 'low');
}
// vanilla structure/finish blocks of the AgentCraft look (C5: docs/BUILDINGS.md "Materials")
for (const n of ['smooth_quartz', 'quartz_bricks', 'calcite', 'terracotta', 'oak_planks', 'spruce_planks', 'cobblestone', 'polished_diorite', 'white_concrete', 'light_gray_concrete', 'mud_bricks', 'tuff_bricks', 'polished_tuff', 'deepslate_tiles']) def(`minecraft:${n}`);
for (const wood of ['oak', 'spruce']) {
  def(`minecraft:${wood}_stairs`, { facing: H4, half: ['bottom', 'top'], shape: STAIR_SHAPES, waterlogged: BOOL }, 'stairs', { facing: 'north', half: 'bottom', shape: 'straight', waterlogged: 'false' });
  def(`minecraft:${wood}_slab`, { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
}
def('minecraft:smooth_quartz_stairs', { facing: H4, half: ['bottom', 'top'], shape: STAIR_SHAPES, waterlogged: BOOL }, 'stairs', { facing: 'north', half: 'bottom', shape: 'straight', waterlogged: 'false' });
def('minecraft:smooth_quartz_slab', { type: ['bottom', 'top', 'double'], waterlogged: BOOL }, 'slab', { type: 'bottom', waterlogged: 'false' });
for (const n of ['stripped_dark_oak_wood', 'stripped_oak_log', 'stripped_oak_wood', 'stripped_spruce_log', 'quartz_pillar']) def(`minecraft:${n}`, { axis: ['x', 'y', 'z'] }, 'full', { axis: 'y' });
// vanilla light sources (`light` = emitted block light; the checker's light pass uses it)
for (const n of ['ochre_froglight', 'pearlescent_froglight', 'verdant_froglight']) def(`minecraft:${n}`, { axis: ['x', 'y', 'z'] }, 'full', { axis: 'y' });
for (const n of ['sea_lantern', 'shroomlight', 'glowstone']) def(`minecraft:${n}`);
def('minecraft:soul_lantern', { hanging: BOOL, waterlogged: BOOL }, 'partial', { hanging: 'false', waterlogged: 'false' });
def('minecraft:end_rod', { facing: H6 }, 'partial', { facing: 'up' });
// iron front doors (zombies cannot break them) opened by stone buttons on both sides
def('minecraft:iron_door', { facing: H4, half: ['lower', 'upper'], hinge: ['left', 'right'], open: BOOL, powered: BOOL }, 'door', { facing: 'south', half: 'lower', hinge: 'left', open: 'false', powered: 'false' });
def('minecraft:stone_button', { face: ['floor', 'wall', 'ceiling'], facing: H4, powered: BOOL }, 'none', { face: 'wall', facing: 'north', powered: 'false' });
def('minecraft:glass');
def('minecraft:glass_pane', { north: BOOL, east: BOOL, south: BOOL, west: BOOL, waterlogged: BOOL }, 'thin', { north: 'false', east: 'false', south: 'false', west: 'false', waterlogged: 'false' });
def('minecraft:light', { level: Array.from({ length: 16 }, (_, i) => String(i)), waterlogged: BOOL }, 'none', { level: '15', waterlogged: 'false' });
def('minecraft:lantern', { hanging: BOOL, waterlogged: BOOL }, 'partial', { hanging: 'false', waterlogged: 'false' });
def('minecraft:candle', { candles: ['1', '2', '3', '4'], lit: BOOL, waterlogged: BOOL }, 'partial', { candles: '1', lit: 'false', waterlogged: 'false' });
for (const c of ['brown', 'white', 'light_gray']) def(`minecraft:${c}_carpet`, {}, 'low');
def('minecraft:bookshelf');
def('minecraft:chiseled_bookshelf', { facing: H4, slot_0_occupied: BOOL, slot_1_occupied: BOOL, slot_2_occupied: BOOL, slot_3_occupied: BOOL, slot_4_occupied: BOOL, slot_5_occupied: BOOL }, 'full', { facing: 'south' });
def('minecraft:lectern', { facing: H4, has_book: BOOL, powered: BOOL }, 'partial', { facing: 'south', has_book: 'false', powered: 'false' });
def('minecraft:potted_fern', {}, 'partial');
def('minecraft:potted_flowering_azalea_bush', {}, 'partial');
def('minecraft:barrel', { facing: H6, open: BOOL }, 'full', { facing: 'north', open: 'false' });
def('minecraft:moss_block');
// beds (the rest corners of docs/VILLAGE.md V3): two cells, `facing` points from the foot to the head (the pillow end)
export const BED_COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'];
for (const c of BED_COLORS) def(`minecraft:${c}_bed`, { facing: H4, occupied: BOOL, part: ['foot', 'head'] }, 'partial', { facing: 'north', occupied: 'false', part: 'foot' });

export const BLOCKS = T;

/** Block light a vanilla block emits (0 when absent). AgentCraft blocks are never counted (C5). */
const EMIT = {
  'minecraft:sea_lantern': 15, 'minecraft:ochre_froglight': 15, 'minecraft:pearlescent_froglight': 15, 'minecraft:verdant_froglight': 15,
  'minecraft:shroomlight': 15, 'minecraft:glowstone': 15, 'minecraft:lantern': 15, 'minecraft:soul_lantern': 10, 'minecraft:end_rod': 14,
};
/**
 * Light emitted by a written state. `minecraft:light` (the invisible light block) deliberately counts as 0: the
 * bundled designs light their rooms with visible sources only.
 */
export function emissionOf(state) {
  if (state.name === 'minecraft:candle') return state.props?.lit === 'true' ? 3 * Number(state.props?.candles ?? 1) : 0;
  return EMIT[state.name] ?? 0;
}

/** Full cubes that let light through (glass-like). */
const CLEAR_CUBES = new Set(['minecraft:glass']);
/**
 * How a block treats block light: 'opaque' (stops it), 'clear' (passes, -1 per step), or 'shape' (passes, but
 * its full faces block it: slabs, stairs). Mirrors vanilla: opacity 15 for solid-render cubes, else 1.
 */
export function opticsOf(state) {
  const i = T[state.name];
  if (!i) return 'opaque';
  if (i.collision === 'full') return CLEAR_CUBES.has(state.name) ? 'clear' : 'opaque';
  if (i.collision === 'slab') return state.props?.type === 'double' ? 'opaque' : 'shape';
  if (i.collision === 'stairs') return 'shape';
  return 'clear';
}

/**
 * The block as 2x2x2 voxels (bit index vx + 2*vy + 4*vz) for slab/stair face occlusion. Stairs: bottom layer full,
 * top layer = the quarters on the tall (`facing`) side; outer corners keep facing ∩ left (counter-clockwise),
 * inner corners add that quarter on the back half. half=top mirrors vertically.
 */
export function voxelsOf(state) {
  const i = T[state.name];
  if (!i) return 0xff;
  if (i.collision === 'slab') return state.props?.type === 'top' ? 0b11001100 : state.props?.type === 'double' ? 0xff : 0b00110011;
  if (i.collision !== 'stairs') return opticsOf(state) === 'opaque' ? 0xff : 0;
  const f = state.props?.facing ?? 'north';
  const ccw = { north: 'west', west: 'south', south: 'east', east: 'north' }[f];
  const cw = { north: 'east', east: 'south', south: 'west', west: 'north' }[f];
  const side = (d, vx, vz) => (d === 'north' ? vz === 0 : d === 'south' ? vz === 1 : d === 'west' ? vx === 0 : vx === 1);
  const shape = state.props?.shape ?? 'straight';
  const top = (vx, vz) => {
    if (shape === 'straight') return side(f, vx, vz);
    if (shape === 'outer_left') return side(f, vx, vz) && side(ccw, vx, vz);
    if (shape === 'outer_right') return side(f, vx, vz) && side(cw, vx, vz);
    if (shape === 'inner_left') return side(f, vx, vz) || side(ccw, vx, vz);
    return side(f, vx, vz) || side(cw, vx, vz); // inner_right
  };
  const upper = state.props?.half === 'top' ? 0 : 1;
  let bits = 0;
  for (let vz = 0; vz < 2; vz++) for (let vx = 0; vx < 2; vx++) {
    bits |= 1 << (vx + 2 * (1 - upper) + 4 * vz); // the full layer
    if (top(vx, vz)) bits |= 1 << (vx + 2 * upper + 4 * vz);
  }
  return bits;
}

const FACE_AXIS = { east: [0, 1], west: [0, 0], up: [1, 1], down: [1, 0], south: [2, 1], north: [2, 0] };
/** The 4-bit mask of a voxel set's face towards `dir` (projected on the two other axes, a fixed order per axis). */
export function faceMask(bits, dir) {
  const [axis, sideV] = FACE_AXIS[dir];
  let m = 0;
  let n = 0;
  for (let a = 0; a < 2; a++) for (let b = 0; b < 2; b++) {
    const v = [0, 0, 0];
    v[axis] = sideV;
    const others = [0, 1, 2].filter((q) => q !== axis);
    v[others[0]] = a;
    v[others[1]] = b;
    if (bits & (1 << (v[0] + 2 * v[1] + 4 * v[2]))) m |= 1 << n;
    n++;
  }
  return m;
}

/** Add `minecraft:` to bare names. */
export function qualify(name) {
  return name.includes(':') ? name : `minecraft:${name}`;
}

/** Look a block up (throws on unknown ids: add them to the table after checking the 26.3 jar). */
export function info(name) {
  const i = T[qualify(name)];
  if (!i) throw new Error(`unknown block '${name}' (add it to tools/blueprints/lib/blocks.mjs)`);
  return i;
}

/** Validate + complete a property set: every property explicit, values strings, nothing unknown. */
export function normalize(name, props = {}) {
  const q = qualify(name);
  const i = info(q);
  const out = {};
  for (const [k, v] of Object.entries(props)) {
    if (!(k in i.props)) throw new Error(`${q}: unknown property '${k}' (has: ${Object.keys(i.props).join(', ') || 'none'})`);
    const s = String(v);
    if (!i.props[k].includes(s)) throw new Error(`${q}: invalid value '${s}' for '${k}' (one of ${i.props[k].join('|')})`);
  }
  for (const k of Object.keys(i.props)) out[k] = props[k] === undefined ? i.defaults[k] : String(props[k]);
  return { name: q, props: out };
}

/** Collision class of a written palette state ({name, props}). */
export function collisionOf(state) {
  const i = T[state.name];
  if (!i) return 'full';
  if (i.collision === 'door') return state.props?.open === 'true' ? 'none' : 'full';
  return i.collision;
}

export const isCube = (state) => T[state.name]?.collision === 'full';

/** A vanilla bed half (`minecraft:<colour>_bed`). */
export const isBed = (name) => /^minecraft:[a-z_]+_bed$/.test(name ?? '') && !!T[name];
