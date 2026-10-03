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

export const BLOCKS = T;

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
