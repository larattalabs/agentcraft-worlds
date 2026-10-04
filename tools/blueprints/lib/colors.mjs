// Block colour table for the offline renderer. Keyed by full block id (base id for stairs/slabs/carpets/panes:
// `minecraft:brick_stairs` finds `minecraft:bricks`). Entries: hex string, or
//   { c: '#hex', a: alpha, shape, border: '#hex', emissive: true, lit: '#hex', box: [..] }
// Unknown ids get a colour derived from keywords in their name (stone/wood/brick/glass/...) and are logged once.

const GLASS = { c: '#a9d6ee', a: 0.38 };

export const COLOR_TABLE = {
  // ---- AgentCraft (mod/src/main/java/dev/agentcraft/block/ModBlocks.java)
  'agentcraft:plaster_panel': '#ece6d6',
  'agentcraft:plaster_frame': '#d8cfb8',
  'agentcraft:walnut_panel': '#5b3a25',
  'agentcraft:walnut_trim': '#432a1a',
  'agentcraft:terracotta_tile': '#b4613f',
  'agentcraft:oak_parquet': '#b98c57',
  'agentcraft:glow_panel': { c: '#fff1c4', emissive: true },
  'agentcraft:glow_strip': { c: '#ffe6a0', emissive: true, shape: 'strip' },
  'agentcraft:monitor': { c: '#1d2431', border: '#cfd6e0', lit: '#3f78b8', shape: 'panel' },
  'agentcraft:task_board': { c: '#252c36', border: '#e8e2d0', shape: 'panel' },
  'agentcraft:decision_podium': { c: '#7a4e2e', shape: 'small', box: [0.15, 0, 0.15, 0.85, 0.95, 0.85], border: '#d9b45a' },
  'agentcraft:memory_archive': '#5f4c80',
  'agentcraft:memory_catalog': '#7e6ba3',
  'agentcraft:merge_station': { c: '#3e8574', shape: 'small', box: [0.08, 0, 0.08, 0.92, 0.7, 0.92], border: '#bfe9dc' },
  'agentcraft:status_lamp': { c: '#62d37a', emissive: true, shape: 'small', box: [0.25, 0, 0.25, 0.75, 0.5, 0.75] },
  'agentcraft:console_terminal': { c: '#2a303c', shape: 'small', box: [0.1, 0, 0.1, 0.9, 0.85, 0.9], border: '#6fe08a' },

  // ---- vanilla blocks used by the bundled designs
  'minecraft:air': { shape: 'none', c: '#000000' },
  'minecraft:light': { shape: 'none', c: '#000000' },
  'minecraft:stone_bricks': '#7d7d7a',
  'minecraft:bricks': '#965f4e',
  'minecraft:polished_andesite': '#888a88',
  'minecraft:smooth_stone': '#9d9d9d',
  'minecraft:blue_terracotta': '#4a3b5b',
  'minecraft:green_terracotta': '#4c532a',
  'minecraft:orange_terracotta': '#a1532a',
  'minecraft:purple_terracotta': '#764556',
  'minecraft:cyan_terracotta': '#575b5b',
  'minecraft:red_terracotta': '#8e3d2f',
  'minecraft:yellow_terracotta': '#ba8523',
  'minecraft:white_terracotta': '#d1b2a1',
  'minecraft:light_blue_terracotta': '#716c89',
  'minecraft:stripped_dark_oak_log': '#4b3a25',
  'minecraft:dark_oak_planks': '#3f2b15',
  'minecraft:birch_planks': '#c5b077',
  'minecraft:pale_oak_planks': '#e4d9cb',
  'minecraft:oak_planks': '#a2824e',
  'minecraft:brown_wool': '#724728',
  'minecraft:waxed_cut_copper': '#c06f50',
  'minecraft:bookshelf': { c: '#8a6a3e', border: '#5a8dc0' },
  'minecraft:chiseled_bookshelf': { c: '#8a6a3e', border: '#a25a4a' },
  'minecraft:barrel': '#7d5a2e',
  'minecraft:moss_block': '#5a7a2c',
  'minecraft:glass': GLASS,
  'minecraft:glass_pane': { ...GLASS, shape: 'pane' },
  'minecraft:lantern': { c: '#ffc766', emissive: true, shape: 'lantern' },
  'minecraft:candle': { c: '#efe6cf', shape: 'candle' },
  'minecraft:lectern': { c: '#9a7344', shape: 'small', box: [0.2, 0, 0.2, 0.8, 0.9, 0.8] },
  'minecraft:potted_fern': { c: '#3f8f3a', shape: 'potted' },
  'minecraft:potted_flowering_azalea_bush': { c: '#6d9a3c', shape: 'potted' },
  'minecraft:dark_oak_door': { c: '#4a331b', shape: 'door' },
  'minecraft:iron_door': { c: '#c4c4c4', shape: 'door' },
  'minecraft:stone_button': { c: '#8a8a87', shape: 'button' },
  // the AgentCraft look in vanilla blocks (tools/blueprints/lib/kit.mjs B; docs/BUILDINGS.md "Materials")
  'minecraft:smooth_quartz': '#ece6df',
  'minecraft:quartz_bricks': '#e9e3da',
  'minecraft:quartz_pillar': '#ebe6e0',
  'minecraft:calcite': '#dcddd8',
  'minecraft:terracotta': '#985e43',
  'minecraft:spruce_planks': '#73553a',
  'minecraft:cobblestone': '#7f7f7f',
  'minecraft:polished_diorite': '#c0c1c2',
  'minecraft:white_concrete': '#cfd5d6',
  'minecraft:light_gray_concrete': '#7d7d73',
  'minecraft:mud_bricks': '#89694f',
  'minecraft:tuff_bricks': '#62665f',
  'minecraft:polished_tuff': '#626a63',
  'minecraft:deepslate_tiles': '#363637',
  'minecraft:stripped_dark_oak_wood': '#4b3a25',
  'minecraft:stripped_oak_log': '#b29157',
  'minecraft:stripped_oak_wood': '#b29157',
  'minecraft:stripped_spruce_log': '#73593a',
  // vanilla light sources
  'minecraft:ochre_froglight': { c: '#f5e7b0', emissive: true },
  'minecraft:pearlescent_froglight': { c: '#f3ecef', emissive: true },
  'minecraft:verdant_froglight': { c: '#e3f1dc', emissive: true },
  'minecraft:sea_lantern': { c: '#c9ddd6', emissive: true },
  'minecraft:shroomlight': { c: '#f19646', emissive: true },
  'minecraft:glowstone': { c: '#d6ad6a', emissive: true },
  'minecraft:soul_lantern': { c: '#7fd9e0', emissive: true, shape: 'lantern' },
  'minecraft:end_rod': { c: '#f2ece0', emissive: true, shape: 'rod' },

  // carpets
  'minecraft:brown_carpet': '#724728',
  'minecraft:white_carpet': '#e9ecec',
  'minecraft:light_gray_carpet': '#8e8e86',
  'minecraft:blue_carpet': '#35399d',
  'minecraft:green_carpet': '#546d1b',
  'minecraft:orange_carpet': '#f07613',
  'minecraft:purple_carpet': '#7a2aa0',
  'minecraft:cyan_carpet': '#157788',
  'minecraft:red_carpet': '#a02722',
  'minecraft:yellow_carpet': '#f8c527',
  'minecraft:light_blue_carpet': '#3aafd9',
};

const STATUS = { off: '#555b66', idle: '#6f7a88', thinking: '#e6c34a', working: '#4aa3e6', waiting: '#e69a3a', error: '#e45555', done: '#62d37a' };

const hexToRgb = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];

// keyword -> colour, first match wins (checked on the id with the namespace stripped)
const KEYWORDS = [
  [/glass|ice/, '#a9d6ee', { a: 0.38 }], [/leaves|moss|grass|azalea|fern|vine/, '#4f8a38'],
  [/brick/, '#965f4e'], [/terracotta|clay/, '#9a5b3f'], [/copper/, '#c06f50'],
  [/dark_oak|spruce|walnut/, '#4a331b'], [/birch|pale|oak|wood|plank|log|parquet/, '#a2824e'], [/bamboo/, '#c2b050'],
  [/sand/, '#dccf9b'], [/snow|quartz|white|plaster|calcite/, '#ece8e0'], [/deepslate|blackstone|obsidian|coal|black/, '#2c2c32'],
  [/stone|andesite|diorite|granite|cobble|tuff|slate|concrete|gray|grey/, '#858582'],
  [/wool|carpet/, '#b0b0b0'], [/lamp|light|glow|torch|lantern|sea_lantern|shroom/, '#ffe9a8', { emissive: true }],
  [/water/, '#3f76e4', { a: 0.5 }], [/lava/, '#e8741f', { emissive: true }], [/iron|metal/, '#c8c8c8'], [/gold/, '#f1c640'],
];

const loggedUnknown = new Set();

/** Strip a trailing shape suffix: returns [base, shape] */
function splitShape(name) {
  for (const [suf, shape] of [['_stairs', 'stairs'], ['_slab', 'slab'], ['_carpet', 'carpet'], ['_pane', 'pane'], ['_door', 'door'], ['_trapdoor', 'door'], ['_fence', 'pane'], ['_wall', 'cube']]) {
    if (name.endsWith(suf)) return [name.slice(0, -suf.length), shape];
  }
  return [name, null];
}

function fromEntry(e) {
  if (typeof e === 'string') return { color: hexToRgb(e), alpha: 1, shape: 'cube' };
  return {
    color: hexToRgb(e.c), alpha: e.a ?? 1, shape: e.shape ?? 'cube', border: e.border ? hexToRgb(e.border) : null,
    emissive: !!e.emissive, box: e.box, litColor: e.lit ? hexToRgb(e.lit) : null,
  };
}

/** @returns {{color:number[], alpha:number, shape:string, border:number[]|null, emissive:boolean, box?:number[]}} */
export function resolveMaterial(id, props = {}, unknown = null) {
  const name = id.replace(/^[a-z_]+:/, '');
  const ns = id.includes(':') ? id.split(':')[0] : 'minecraft';
  const direct = COLOR_TABLE[id];
  if (direct) {
    const m = fromEntry(direct);
    if (id === 'agentcraft:status_lamp') m.color = hexToRgb(STATUS[props.status] ?? STATUS.off);
    return m;
  }
  const [base, shape] = splitShape(name);
  if (shape) {
    for (const cand of [base, `${base}s`, `${base}_planks`, `${base}_block`, `${base}_wool`]) {
      const e = COLOR_TABLE[`${ns}:${cand}`];
      if (e) {
        const m = fromEntry(e);
        // panes keep glass transparency; everything else uses the base block's solid colour
        return { ...m, shape, alpha: shape === 'pane' ? Math.min(m.alpha, 0.38) : m.alpha };
      }
    }
  }
  // unknown: derive from the name
  if (unknown && !unknown.has(id)) unknown.add(id);
  if (!loggedUnknown.has(id) && !unknown) { loggedUnknown.add(id); console.error(`render: no colour for ${id}, guessing from its name`); }
  const target = shape ? base : name;
  for (const [re, hex, extra] of KEYWORDS) {
    if (re.test(target)) return { color: hexToRgb(hex), alpha: extra?.a ?? 1, shape: shape ?? 'cube', border: null, emissive: !!extra?.emissive };
  }
  // stable hash colour so different unknown blocks stay distinguishable
  let h = 0; for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) | 0;
  const c = [96 + (h & 0x3f), 96 + ((h >> 6) & 0x3f), 96 + ((h >> 12) & 0x3f)];
  return { color: c, alpha: 1, shape: shape ?? 'cube', border: null, emissive: false };
}
