// Blueprint checker: validates a structure template + sidecar against docs/BUILDINGS.md.
//   checkFiles(nbtPath, jsonPath) / checkBlueprint(bp) -> { ok, errors: string[], warnings: string[] }
import fs from 'node:fs';
import { parse, plain } from './nbt.mjs';
import { BLOCKS, collisionOf, normalize, emissionOf, opticsOf, voxelsOf, faceMask, isBed } from './blocks.mjs';
import { dirOfYaw } from './kit.mjs';

export const CAST = ['juniper', 'kit', 'wren', 'rowan', 'tove'];
const STATIONS = ['meeting', 'lounge', 'library', 'terminal', 'testbench', 'mergestation', 'user'];
const STANDING_RE = /^(library|terminal|testbench|mergestation|meeting|lounge|user)(_\d+)?$/;
const TROPHY_RE = /^trophy(_\d+)?$/;
const BED_RE = /^bed(_\d+)?$/;
const SEAT_RE = /^(desk_.+|seat_.+|meeting(_\d+)?|lounge(_\d+)?)$/;
const HORIZ = [[1, 0], [-1, 0], [0, 1], [0, -1]];
const YAW_OF_FACING = { south: 0, west: 90, north: 180, east: -90 };

/** Required anchors of a fixture (docs/VILLAGE.md V2): the display's centre and where a teleport lands. */
export const FIXTURE_ANCHORS = ['board', 'spawn'];

/** Required anchor names for a blueprint with `wings` wings. */
export function requiredAnchors(wings = 1) {
  const names = [];
  for (const id of CAST) names.push(`desk_${id}`, `monitor_${id}`);
  names.push(...STATIONS, 'decision_podium', 'goal_atrium', 'entrance', 'spawn', 'cam_overview');
  for (let n = 1; n <= wings; n++) names.push(`task_wall@${n}`);
  return names;
}

const yawDiff = (a, b) => Math.abs((((a - b) % 360) + 540) % 360 - 180);

/** AgentCraft blocks a bundled/designed building may use: the functional station blocks (contract C5). */
export const FUNCTIONAL_BLOCKS = new Set([
  'agentcraft:monitor', 'agentcraft:task_board', 'agentcraft:decision_podium', 'agentcraft:console_terminal',
  'agentcraft:status_lamp', 'agentcraft:merge_station', 'agentcraft:memory_archive', 'agentcraft:memory_catalog',
  'agentcraft:village_board',
]);
const VANILLA_FOR = {
  'agentcraft:plaster_panel': 'minecraft:smooth_quartz (B.plaster)', 'agentcraft:plaster_frame': 'minecraft:calcite (B.plasterFrame)',
  'agentcraft:walnut_panel': 'minecraft:stripped_dark_oak_log (B.walnut)', 'agentcraft:walnut_trim': 'minecraft:dark_oak_planks (B.walnutTrim)',
  'agentcraft:terracotta_tile': 'minecraft:terracotta (B.tile)', 'agentcraft:oak_parquet': 'minecraft:oak_planks (B.parquet)',
  'agentcraft:glow_panel': 'minecraft:ochre_froglight (B.glowPanel)', 'agentcraft:glow_strip': 'a froglight band, end rods or lanterns',
};
const isAC = (name) => name.startsWith('agentcraft:');
const DIRS6 = [['east', 1, 0, 0], ['west', -1, 0, 0], ['up', 0, 1, 0], ['down', 0, -1, 0], ['south', 0, 0, 1], ['north', 0, 0, -1]];
const OPP = { east: 'west', west: 'east', up: 'down', down: 'up', south: 'north', north: 'south' };
const H_VEC = { north: [0, -1], south: [0, 1], west: [-1, 0], east: [1, 0] };
const fmt = (x, y, z) => `${x},${y},${z}`;
/** Full opaque cubes vanilla does not let carry redstone power (Blocks: isRedstoneConductor(never)), from the table. */
const NON_CONDUCTORS = new Set(['minecraft:glowstone', 'minecraft:sea_lantern']);

/**
 * Shell integrity (C5): flood-fills the outside of the building (the padded template box above ground; rows below
 * groundY are ground) through everything a mob could pass, once with AgentCraft blocks in place and once with
 * every agentcraft:* cell turned to air (a world opened without the mod). Walk cells reached only in the second pass
 * mean a functional block is part of the outer shell. Unwritten cells above ground count as open (placement
 * clears the box). Errors: walk reachable through the walls (a flood capped at walk.maxY: a doorway without
 * a closed door, a gap) and any cell that only becomes reachable without the mod (walk, attic or cavity).
 * Warning: walk cells reached only from above (open to the sky: a courtyard).
 * `fixture` (docs/VILLAGE.md V2): an outdoor object has no inside, so only the no-mod leaks count.
 * @returns {{ errors: string[], warnings: string[], outside: Set<string> }} `outside` = cells reached with the mod
 */
export function shellCheck(grid, size, walk, groundY, { fixture = false } = {}) {
  const errors = [];
  const warnings = [];
  const [sx, sy, sz] = size;
  const inWalk = (x, y, z) => x >= walk.minX && x <= walk.maxX && y >= walk.minY && y <= walk.maxY && z >= walk.minZ && z <= walk.maxZ;
  const open = (x, y, z, acAir) => {
    const c = grid.get(fmt(x, y, z));
    if (!c) return y >= groundY;
    if (isAC(c.name)) return acAir;
    const k = collisionOf(c);
    return k === 'none' || k === 'low' || k === 'partial';
  };
  // capY: a flood that never rises above capY (seeded at the ground beside the box) can only get in through the
  // walls; the uncapped one (seeded above the box) also comes down through open roofs and courtyards
  const flood = (acAir, capY = sy) => {
    const parent = new Map();
    const sy0 = capY === sy ? sy : groundY;
    parent.set(fmt(-1, sy0, -1), null);
    const q = [[-1, sy0, -1]];
    for (let i = 0; i < q.length; i++) {
      const [x, y, z] = q[i];
      for (const [, dx, dy, dz] of DIRS6) {
        const nx = x + dx; const ny = y + dy; const nz = z + dz;
        if (nx < -1 || nx > sx || nz < -1 || nz > sz || ny > capY || ny < Math.min(groundY, 0)) continue;
        const k = fmt(nx, ny, nz);
        if (parent.has(k) || !open(nx, ny, nz, acAir)) continue;
        parent.set(k, fmt(x, y, z));
        q.push([nx, ny, nz]);
      }
    }
    return parent;
  };
  const withMod = flood(false);
  const noMod = flood(true);
  const walls = flood(false, walk.maxY);
  const reachedWalk = (m) => [...m.keys()].filter((k) => { const [x, y, z] = k.split(',').map(Number); return inWalk(x, y, z); });
  const entriesOf = (m, cells) => {
    const entries = new Set();
    for (const k of cells) { const p = m.get(k); const [px, py, pz] = p.split(',').map(Number); if (!inWalk(px, py, pz)) entries.add(`${p} -> ${k}`); }
    return [...entries].slice(0, 4).join('; ');
  };
  const wallWalk = fixture ? [] : reachedWalk(walls);
  if (wallWalk.length) {
    errors.push(`shell: ${wallWalk.length} walk cell(s) reachable from outside through the walls (a doorway without a closed door, a gap), entries: ${entriesOf(walls, wallWalk)}`);
  }
  const wallSet = new Set(wallWalk);
  const skyWalk = reachedWalk(withMod).filter((k) => !wallSet.has(k));
  if (skyWalk.length && !fixture) warnings.push(`shell: ${skyWalk.length} walk cell(s) open to the sky (a courtyard or an open roof), entries: ${entriesOf(withMod, skyWalk)}`);
  // without the mod: every cell (walk, attic, cavity) that only becomes reachable when the AgentCraft blocks are gone
  const leaks = new Map();
  for (const k of noMod.keys()) {
    if (withMod.has(k)) continue;
    const c0 = grid.get(k);
    if (c0 && isAC(c0.name)) continue;
    let hole = null; // the AgentCraft cell on the path that is nearest the outside = the opening in the shell
    for (let p = noMod.get(k); p; p = noMod.get(p)) {
      const c = grid.get(p);
      if (c && isAC(c.name)) hole = [p, c.name];
    }
    if (hole) leaks.set(hole[0], hole[1]);
  }
  for (const [k, name] of [...leaks].slice(0, 8)) {
    errors.push(`shell: without the mod the ${name} at ${k} leaves a hole in the outer shell (put a solid vanilla block behind it on the outside, or move it off the shell)`);
  }
  if (leaks.size > 8) errors.push(`shell: ${leaks.size - 8} more AgentCraft block(s) in the outer shell`);
  return { errors, warnings, outside: new Set(withMod.keys()) };
}

/**
 * Vanilla block light (C5): propagates light from vanilla emitters only (froglights, sea lanterns, lanterns,
 * lit candles, ...; never AgentCraft blocks and never the invisible `minecraft:light`) with vanilla rules: the
 * level drops by 1 per step, opaque cubes stop it, glass/panes/air pass it, slabs and stairs block it through their
 * full faces. Runs twice: AgentCraft blocks as opaque non-emitters (with the mod) and as air (without it).
 * Every walk cell an entity can stand in (air, carpet, buttons...) must reach level >= 1.
 * @returns {{ errors: string[], dark: string[], levels: (mode:string)=>Int8Array }}
 */
export function lightCheck(grid, size, walk, groundY) {
  const [sx, sy, sz] = size;
  const idx = (x, y, z) => x + sx * (y + sy * z);
  const n = sx * sy * sz;
  const errors = [];
  const darkAll = new Set();
  const results = {};
  for (const mode of ['mod', 'nomod']) {
    const optic = new Uint8Array(n); // 0 clear, 1 opaque, 2 shape
    const vox = new Uint8Array(n);
    const level = new Int8Array(n);
    const buckets = Array.from({ length: 16 }, () => []);
    for (let z = 0; z < sz; z++) for (let y = 0; y < sy; y++) for (let x = 0; x < sx; x++) {
      const i = idx(x, y, z);
      const c = grid.get(fmt(x, y, z));
      if (!c) { optic[i] = y < groundY ? 1 : 0; continue; }
      if (isAC(c.name)) { optic[i] = mode === 'mod' ? 1 : 0; continue; }
      const o = opticsOf(c);
      optic[i] = o === 'opaque' ? 1 : o === 'shape' ? 2 : 0;
      if (o === 'shape') vox[i] = voxelsOf(c);
      const e = emissionOf(c);
      if (e > 0) { level[i] = e; buckets[e].push(i); }
    }
    for (let L = 15; L >= 2; L--) {
      for (const i of buckets[L]) {
        if (level[i] !== L) continue;
        const x = i % sx; const y = Math.floor(i / sx) % sy; const z = Math.floor(i / (sx * sy));
        for (const [d, dx, dy, dz] of DIRS6) {
          const nx = x + dx; const ny = y + dy; const nz = z + dz;
          if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) continue;
          const j = idx(nx, ny, nz);
          if (optic[j] === 1) continue;
          if ((optic[i] === 2 || optic[j] === 2) && ((optic[i] === 2 ? faceMask(vox[i], d) : 0) | (optic[j] === 2 ? faceMask(vox[j], OPP[d]) : 0)) === 15) continue;
          if (L - 1 > level[j]) { level[j] = L - 1; buckets[L - 1].push(j); }
        }
      }
    }
    results[mode] = level;
    for (let y = walk.minY; y <= walk.maxY; y++) for (let z = walk.minZ; z <= walk.maxZ; z++) for (let x = walk.minX; x <= walk.maxX; x++) {
      const c = grid.get(fmt(x, y, z));
      if (!c) continue;
      const roomy = isAC(c.name) ? mode === 'nomod' : ['none', 'low'].includes(collisionOf(c));
      if (roomy && level[idx(x, y, z)] < 1) darkAll.add(fmt(x, y, z));
    }
  }
  if (darkAll.size) {
    const list = [...darkAll].sort((a, b) => a.split(',')[1] - b.split(',')[1]);
    errors.push(`light: ${darkAll.size} walk cell(s) get no block light from vanilla sources (mobs spawn there at night), e.g. ${list.slice(0, 6).join('; ')}`);
  }
  return { errors, dark: [...darkAll], levels: (mode) => results[mode] };
}

/**
 * Doors (C5): every door is written closed; doors on the outside (a side cell reached by the outside flood) are
 * iron; every iron door has a stone button on both sides whose attached block is a full, conductive (opaque)
 * vanilla cube next to one of the door's halves.
 */
export function doorCheck(grid, outside) {
  const errors = [];
  const buttons = [];
  for (const [k, c] of grid) {
    if (c.name !== 'minecraft:stone_button') continue;
    const [x, y, z] = k.split(',').map(Number);
    const f = c.props.face;
    const [ax, ay, az] = f === 'floor' ? [x, y - 1, z] : f === 'ceiling' ? [x, y + 1, z] : [x - H_VEC[c.props.facing][0], y, z - H_VEC[c.props.facing][1]];
    const a = grid.get(fmt(ax, ay, az));
    const conductive = a && !isAC(a.name) && collisionOf(a) === 'full' && opticsOf(a) === 'opaque' && !NON_CONDUCTORS.has(a.name);
    buttons.push({ x, y, z, ax, ay, az, conductive });
  }
  for (const [k, c] of grid) {
    if (!c.name.endsWith('_door') || c.props.half !== 'lower') continue;
    const [x, y, z] = k.split(',').map(Number);
    if (c.props.open !== 'false') errors.push(`door at ${k} is written open (write doors closed)`);
    const [fx, fz] = H_VEC[c.props.facing];
    const sides = [fmt(x + fx, y, z + fz), fmt(x - fx, y, z - fz), fmt(x + fx, y + 1, z + fz), fmt(x - fx, y + 1, z - fz)];
    const exterior = sides.some((q) => outside.has(q));
    if (exterior && c.name !== 'minecraft:iron_door') errors.push(`door at ${k}: an outside door must be minecraft:iron_door (zombies break ${c.name} on Hard)`);
    if (c.name !== 'minecraft:iron_door') continue;
    const side = new Set();
    for (const b of buttons) {
      if (!b.conductive) continue;
      const touches = [y, y + 1].some((dy) => Math.abs(b.ax - x) + Math.abs(b.ay - dy) + Math.abs(b.az - z) === 1);
      if (!touches) continue;
      const along = (b.x - x) * fx + (b.z - z) * fz;
      if (along !== 0) side.add(Math.sign(along));
    }
    if (!side.has(1) || !side.has(-1)) {
      errors.push(`iron door at ${k}: needs a stone button on ${!side.has(1) && !side.has(-1) ? 'both sides' : !side.has(1) ? 'its front (outside)' : 'its back (inside)'} attached to a full vanilla block next to the door`);
    }
  }
  return { errors };
}

/**
 * @param {object} sidecar parsed <id>.blueprint.json
 * @param {object} structure plain (untagged) parsed structure NBT root
 */
export function checkStructure(sidecar, structure) {
  const errors = [];
  const warnings = [];
  const err = (m) => errors.push(m);

  // ---- structure format
  if (!Number.isInteger(structure.DataVersion)) err('structure: DataVersion missing / not an int');
  const size = structure.size;
  if (!Array.isArray(size) || size.length !== 3 || !size.every(Number.isInteger)) err('structure: size must be a list of 3 ints');
  if (!Array.isArray(structure.palette)) err('structure: palette missing');
  if (!Array.isArray(structure.blocks)) err('structure: blocks missing');
  if (!Array.isArray(structure.entities)) err('structure: entities missing (must be an empty list)');
  else if (structure.entities.length) err('structure: entities must be empty');
  if (errors.length) return { ok: false, errors, warnings };

  // ---- palette
  const palette = structure.palette.map((p, i) => {
    const props = p.properties ?? {};
    const name = p.id;
    if (typeof name !== 'string' || !name.includes(':')) { err(`palette[${i}]: bad id '${name}'`); return { name: String(name), props }; }
    if (!BLOCKS[name]) { err(`palette[${i}]: unknown block '${name}'`); return { name, props }; }
    if (isAC(name) && !FUNCTIONAL_BLOCKS.has(name)) err(`palette[${i}]: decorative AgentCraft block '${name}': buildings use vanilla blocks except the station blocks (use ${VANILLA_FOR[name] ?? 'a vanilla block'})`);
    for (const v of Object.values(props)) if (typeof v !== 'string') err(`palette[${i}] ${name}: property values must be strings`);
    try {
      const full = normalize(name, props);
      for (const k of Object.keys(full.props)) if (!(k in props)) err(`palette[${i}] ${name}: property '${k}' not written explicitly`);
    } catch (e) {
      err(`palette[${i}]: ${e.message}`);
    }
    return { name, props };
  });

  // ---- blocks
  const grid = new Map();
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (const [i, b] of structure.blocks.entries()) {
    if (!Array.isArray(b.pos) || b.pos.length !== 3 || !b.pos.every(Number.isInteger)) { err(`blocks[${i}]: bad pos`); continue; }
    const [x, y, z] = b.pos;
    const st = palette[b.state];
    if (!st) { err(`blocks[${i}] at ${x},${y},${z}: state ${b.state} not in palette`); continue; }
    const k = `${x},${y},${z}`;
    if (grid.has(k)) err(`duplicate block at ${k}`);
    grid.set(k, { ...st, nbt: b.nbt });
    for (let a = 0; a < 3; a++) { min[a] = Math.min(min[a], b.pos[a]); max[a] = Math.max(max[a], b.pos[a]); }
    if (x < 0 || y < 0 || z < 0 || x >= size[0] || y >= size[1] || z >= size[2]) err(`block at ${k} outside size ${size.join('x')}`);
  }
  if (grid.size) {
    const extent = max.map((m, a) => m - min[a] + 1);
    if (min.some((m) => m !== 0) || extent.some((e, a) => e !== size[a])) {
      err(`size ${size.join('x')} does not match block extents ${extent.join('x')} (min ${min.join(',')})`);
    }
  } else err('no blocks');

  // ---- sidecar basics
  for (const f of ['id', 'name', 'kind', 'wings', 'size', 'groundY', 'front', 'walk', 'anchors']) if (sidecar[f] === undefined) err(`sidecar: '${f}' missing`);
  if (errors.length && !sidecar.anchors) return { ok: false, errors, warnings };
  if (!/^[a-z0-9_]+$/.test(sidecar.id ?? '')) err(`sidecar: id '${sidecar.id}' must match [a-z0-9_]+`);
  const fixture = sidecar.kind === 'fixture';
  if (!['single', 'group', 'fixture'].includes(sidecar.kind)) err(`sidecar: kind '${sidecar.kind}' must be single|group|fixture`);
  if (fixture) {
    if (sidecar.wings !== 0) err('sidecar: a fixture takes no repos: wings 0');
  } else if (!Number.isInteger(sidecar.wings) || sidecar.wings < 1) err('sidecar: wings must be an int >= 1');
  if (sidecar.kind === 'single' && sidecar.wings !== 1) err('sidecar: a single blueprint has wings 1');
  if (!(sidecar.front in YAW_OF_FACING)) err(`sidecar: front '${sidecar.front}' invalid`);
  if (sidecar.size && (sidecar.size.x !== size[0] || sidecar.size.y !== size[1] || sidecar.size.z !== size[2])) {
    err(`sidecar size ${sidecar.size.x}x${sidecar.size.y}x${sidecar.size.z} != structure size ${size.join('x')}`);
  }
  const w = sidecar.walk ?? {};
  for (const f of ['minX', 'minY', 'minZ', 'maxX', 'maxY', 'maxZ']) if (!Number.isInteger(w[f])) err(`sidecar: walk.${f} must be an int`);
  if (w.minX > w.maxX || w.minY > w.maxY || w.minZ > w.maxZ) err('sidecar: walk min > max');
  if (w.minX < 0 || w.minY < 0 || w.minZ < 0 || w.maxX >= size[0] || w.maxY >= size[1] || w.maxZ >= size[2]) err('sidecar: walk outside the template');
  if (!(sidecar.groundY >= 0 && sidecar.groundY < size[1])) err('sidecar: groundY outside the template');

  // ---- required anchors
  const anchors = sidecar.anchors ?? {};
  for (const n of fixture ? FIXTURE_ANCHORS : requiredAnchors(sidecar.wings)) if (!anchors[n]) err(`missing required anchor '${n}'`);
  for (const [n, a] of Object.entries(anchors)) {
    for (const f of ['x', 'y', 'z', 'yaw', 'pitch']) if (typeof a[f] !== 'number' || !Number.isFinite(a[f])) err(`anchor ${n}: '${f}' must be a finite number`);
  }
  for (const n of Object.keys(anchors).filter((q) => q.includes('@'))) {
    const k = Number(n.slice(n.lastIndexOf('@') + 1));
    if (!Number.isInteger(k) || k < 1 || k > sidecar.wings) err(`anchor ${n}: wing out of range 1..${sidecar.wings}`);
  }
  if (sidecar.foundationBlock === undefined) warnings.push("sidecar: no 'foundationBlock' (the mod fills with minecraft:stone_bricks)");
  else {
    const fb = sidecar.foundationBlock;
    if (typeof fb !== 'string' || !fb.startsWith('minecraft:')) err(`sidecar: foundationBlock '${fb}' must be a vanilla block id (minecraft:...)`);
    else if (!BLOCKS[fb] || collisionOf({ name: fb, props: {} }) !== 'full' || opticsOf({ name: fb, props: {} }) !== 'opaque') err(`sidecar: foundationBlock '${fb}' must be a full, opaque block from lib/blocks.mjs`);
  }

  if (sidecar.approach !== undefined && sidecar.approach !== false) {
    const a = sidecar.approach;
    if (typeof a !== 'object' || a === null) err("sidecar: 'approach' must be an object or false");
    else {
      if (a.length !== undefined && !(Number.isInteger(a.length) && a.length >= 0 && a.length <= 16)) err(`sidecar: approach.length ${a.length} must be an int 0..16`);
      if (a.width !== undefined && !(Number.isInteger(a.width) && a.width >= 1 && a.width <= 7)) err(`sidecar: approach.width ${a.width} must be an int 1..7`);
      for (const [k, want] of [['block', 'full'], ['slab', 'slab']]) {
        const id = a[k];
        if (id === undefined) continue;
        if (typeof id !== 'string' || !id.startsWith('minecraft:')) err(`sidecar: approach.${k} '${id}' must be a vanilla block id (minecraft:...)`);
        else if (BLOCKS[id] && collisionOf({ name: id, props: {} }) !== want) err(`sidecar: approach.${k} '${id}' must be a ${want === 'full' ? 'full block' : 'slab'}`);
      }
    }
  }

  // ---- geometry helpers
  const cellAt = (x, y, z) => grid.get(`${x},${y},${z}`) ?? null;
  const cls = (c) => (c ? collisionOf(c) : 'unwritten');
  const inWalk = (a) => {
    const x = Math.floor(a.x); const y = Math.floor(a.y + 1e-6); const z = Math.floor(a.z);
    return x >= w.minX && x <= w.maxX && y >= w.minY && y <= w.maxY && z >= w.minZ && z <= w.maxZ;
  };
  const standable = (x, y, z) => {
    const below = cls(cellAt(x, y - 1, z));
    const below2 = cellAt(x, y - 1, z);
    const solidBelow = below === 'full' || (below === 'slab' && below2.props.type !== 'bottom');
    const feet = cls(cellAt(x, y, z));
    const head = cls(cellAt(x, y + 1, z));
    return solidBelow && (feet === 'none' || feet === 'low') && head === 'none';
  };

  const trophyCells = new Map();
  const bedCells = new Map();
  const trophyWings = new Set();
  for (const [full, a] of Object.entries(anchors)) {
    const name = full.replace(/@\d+$/, ''); // per-wing anchors (testbench@2) follow their base name's rules
    const cx = Math.floor(a.x);
    const cy = Math.floor(a.y + 1e-6);
    const cz = Math.floor(a.z);
    const standing = STANDING_RE.test(name) || /^(desk_|seat_)/.test(name) || name === 'entrance' || name === 'spawn';

    if (standing) {
      if (!inWalk(a)) err(`anchor ${full} (${a.x},${a.y},${a.z}) is outside walk`);
      const feetCell = cellAt(cx, cy, cz);
      const feet = cls(feetCell);
      const seat = SEAT_RE.test(name) && (feet === 'stairs' || feet === 'slab');
      if (!Number.isInteger(a.y)) warnings.push(`anchor ${full}: y ${a.y} is not on a block boundary`);
      const below = cellAt(cx, cy - 1, cz);
      const bc = cls(below);
      if (!(bc === 'full' || (bc === 'slab' && below.props.type !== 'bottom'))) err(`anchor ${full}: no solid block below (${below ? below.name : 'nothing written'} at ${cx},${cy - 1},${cz})`);
      if (seat) {
        for (const dy of [1, 2]) {
          const c = cls(cellAt(cx, cy + dy, cz));
          if (c !== 'none') err(`anchor ${full}: seat has no free headroom at +${dy} (${cellAt(cx, cy + dy, cz)?.name ?? 'unwritten'})`);
        }
        if (!HORIZ.some(([dx, dz]) => standable(cx + dx, cy, cz + dz))) err(`anchor ${full}: no free standable cell next to the seat (agents cannot step in)`);
      } else {
        if (!(feet === 'none' || feet === 'low')) err(`anchor ${full}: feet cell is ${feetCell ? feetCell.name : 'not cleared (unwritten)'} (needs air / non-collision)`);
        const head = cls(cellAt(cx, cy + 1, cz));
        if (head !== 'none') err(`anchor ${full}: head cell is ${cellAt(cx, cy + 1, cz)?.name ?? 'not cleared (unwritten)'} (needs air / non-collision)`);
      }
    } else if (name.startsWith('monitor_')) {
      const c = cellAt(cx, Math.floor(a.y - 0.01), cz);
      if (!c || c.name !== 'agentcraft:monitor') err(`anchor ${full}: not on an agentcraft:monitor block (found ${c?.name ?? 'nothing'} at ${cx},${Math.floor(a.y - 0.01)},${cz})`);
      else {
        if (YAW_OF_FACING[c.props.facing] !== undefined && yawDiff(a.yaw, YAW_OF_FACING[c.props.facing]) > 1) err(`anchor ${full}: yaw ${a.yaw} does not match monitor facing ${c.props.facing}`);
        const id = name.slice('monitor_'.length);
        if (c.nbt?.binding !== id) err(`anchor ${full}: monitor binding is '${c.nbt?.binding ?? ''}', expected '${id}'`);
      }
    } else if (name === 'task_wall' && full !== name) {
      const c = cellAt(cx, cy, cz);
      if (!c || c.name !== 'agentcraft:task_board') err(`anchor ${full}: not on an agentcraft:task_board block (found ${c?.name ?? 'nothing'})`);
      else {
        if (yawDiff(a.yaw, YAW_OF_FACING[c.props.facing]) > 1) err(`anchor ${full}: yaw ${a.yaw} does not match board facing ${c.props.facing}`);
        const n = full.slice('task_wall@'.length);
        if (c.nbt?.binding !== `repo:#${n}`) err(`anchor ${full}: board binding is '${c.nbt?.binding ?? ''}', expected 'repo:#${n}'`);
      }
    } else if (TROPHY_RE.test(name)) {
      // trophy slot: the centre of a wall-sign cell; yaw = the sign's front, the support is the cell behind it
      const yawOk = [0, 90, 180, -90, -180, 270].some((q) => yawDiff(a.yaw, q) < 1e-6);
      if (!yawOk) err(`anchor ${full}: yaw ${a.yaw} must be a multiple of 90 (the direction the sign faces)`);
      if (!inWalk(a)) err(`anchor ${full} (${a.x},${a.y},${a.z}) is outside walk`);
      if (Math.abs(a.x - cx - 0.5) > 1e-6 || Math.abs(a.y - cy - 0.5) > 1e-6 || Math.abs(a.z - cz - 0.5) > 1e-6) warnings.push(`anchor ${full}: not at a cell centre (x+.5, y+.5, z+.5)`);
      const cell = cellAt(cx, cy, cz);
      if (!cell || !/^minecraft:(cave_|void_)?air$/.test(cell.name)) err(`anchor ${full}: sign cell ${cx},${cy},${cz} is ${cell ? cell.name : 'not written'} (needs explicit air)`);
      const [bx, bz] = yawOk ? H_VEC[dirOfYaw(a.yaw)].map((v) => -v) : [0, 0];
      const back = cellAt(cx + bx, cy, cz + bz);
      if (!back || collisionOf(back) !== 'full' || opticsOf(back) !== 'opaque') err(`anchor ${full}: no full opaque block behind the sign (${back ? back.name : 'nothing written'} at ${cx + bx},${cy},${cz + bz})`);
      const ck = fmt(cx, cy, cz);
      if (trophyCells.has(ck)) err(`anchor ${full}: same sign cell as ${trophyCells.get(ck)}`);
      else trophyCells.set(ck, full);
      const wingOf = full.includes('@') ? Number(full.slice(full.lastIndexOf('@') + 1)) : 0;
      trophyWings.add(wingOf);
    } else if (BED_RE.test(name)) {
      // night-routine bed (docs/BUILDINGS.md "Beds"): the head half's cell (feet row), yaw = its facing; optional anchors
      if (!inWalk(a)) err(`anchor ${full} (${a.x},${a.y},${a.z}) is outside walk`);
      if (!Number.isInteger(a.y)) warnings.push(`anchor ${full}: y ${a.y} is not on a block boundary`);
      const head = cellAt(cx, cy, cz);
      if (!head || !isBed(head.name) || head.props.part !== 'head') err(`anchor ${full}: not on the head half of a bed (found ${head ? `${head.name}${head.props.part ? ` part=${head.props.part}` : ''}` : 'nothing'} at ${cx},${cy},${cz})`);
      else {
        const facingName = head.props.facing;
        if (yawDiff(a.yaw, YAW_OF_FACING[facingName]) > 1) err(`anchor ${full}: yaw ${a.yaw} does not match the bed's facing ${facingName}`);
        if (head.props.occupied !== 'false') err(`anchor ${full}: bed written occupied (write beds with occupied=false)`);
        const [fx, fz] = H_VEC[facingName];
        const footAt = [cx - fx, cy, cz - fz];
        const foot = cellAt(...footAt);
        if (!foot || foot.name !== head.name || foot.props.part !== 'foot' || foot.props.facing !== facingName) err(`anchor ${full}: no matching foot half behind the head (${foot ? `${foot.name} part=${foot.props.part}` : 'nothing'} at ${fmt(...footAt)})`);
        for (const [bx, by, bz] of [[cx, cy, cz], footAt]) {
          const below = cellAt(bx, by - 1, bz);
          if (!(cls(below) === 'full' || (cls(below) === 'slab' && below.props.type !== 'bottom'))) err(`anchor ${full}: no solid floor under the bed at ${fmt(bx, by - 1, bz)}`);
          const above = cellAt(bx, by + 1, bz);
          if (cls(above) !== 'none') err(`anchor ${full}: no free air above the bed at ${fmt(bx, by + 1, bz)} (${above?.name ?? 'unwritten'})`);
        }
        const sides = [[cx, cz], [footAt[0], footAt[2]]].flatMap(([bx, bz]) => HORIZ.map(([dx, dz]) => [bx + dx, bz + dz]));
        if (!sides.some(([sx, sz]) => standable(sx, cy, sz))) err(`anchor ${full}: no free standable cell beside the bed (agents cannot get in)`);
        if (bedCells.has(fmt(cx, cy, cz))) err(`anchor ${full}: same bed as ${bedCells.get(fmt(cx, cy, cz))}`);
        bedCells.set(fmt(cx, cy, cz), full);
        bedCells.set(fmt(...footAt), full);
      }
    } else if (name === 'board') {
      // a fixture's display: the anchor is the centre of an agentcraft:village_board surface, yaw = the board's facing
      const c = cellAt(cx, cy, cz);
      if (!c || c.name !== 'agentcraft:village_board') err(`anchor ${full}: not on an agentcraft:village_board block (found ${c?.name ?? 'nothing'} at ${cx},${cy},${cz})`);
      else if (yawDiff(a.yaw, YAW_OF_FACING[c.props.facing]) > 1) err(`anchor ${full}: yaw ${a.yaw} does not match board facing ${c.props.facing}`);
    } else if (name === 'decision_podium') {
      const c = cellAt(cx, cy, cz);
      if (!c || c.name !== 'agentcraft:decision_podium') err(`anchor ${full}: not on an agentcraft:decision_podium block (found ${c?.name ?? 'nothing'})`);
    } else if (name === 'goal_atrium') {
      if (!inWalk(a)) err(`anchor ${full} is outside walk`);
    }
  }

  // trophy slots: not where an agent stands (feet or head cell), and every wing should have some (a warning)
  for (const [full, a] of Object.entries(anchors)) {
    if (!STANDING_RE.test(full.replace(/@\d+$/, '')) && !/^(desk_|seat_)/.test(full) && full !== 'entrance' && full !== 'spawn') continue;
    const k0 = fmt(Math.floor(a.x), Math.floor(a.y + 1e-6), Math.floor(a.z));
    const k1 = fmt(Math.floor(a.x), Math.floor(a.y + 1e-6) + 1, Math.floor(a.z));
    for (const k of [k0, k1]) if (trophyCells.has(k)) err(`anchor ${trophyCells.get(k)}: sign cell ${k} is where anchor ${full} stands`);
  }
  if (fixture && trophyCells.size) err('a fixture has no trophy slots (trophies hang in buildings)');
  for (let n = 1; n <= (fixture ? 0 : sidecar.wings || 1); n++) {
    if (!trophyWings.has(n) && !(sidecar.wings === 1 && trophyWings.has(0))) warnings.push(`wing ${n} has no trophy slots (trophy@${n}): the mod hangs no trophies there`);
  }

  // ---- everything the agents can walk through must be cleared by the template
  if (Number.isInteger(w.minX) && Number.isInteger(w.maxX)) {
    let unwritten = 0;
    let first = null;
    for (let y = w.minY; y <= w.maxY; y++) for (let z = w.minZ; z <= w.maxZ; z++) for (let x = w.minX; x <= w.maxX; x++) {
      if (!cellAt(x, y, z)) { unwritten++; first ??= `${x},${y},${z}`; }
    }
    if (unwritten) err(`${unwritten} cell(s) inside walk are not written (interior air must be explicit), first at ${first}`);
  }

  // ---- C5: shell integrity without the mod, vanilla light, doors
  if (!errors.some((e) => e.startsWith('sidecar: walk')) && fixture) {
    // a fixture stands outdoors: no walls, no lit interior. It must keep its shape without the mod (no AgentCraft cell
    // in the outline: a full vanilla block behind every one) and carry no repo or CI binding.
    const shell = shellCheck(grid, size, w, sidecar.groundY, { fixture: true });
    errors.push(...shell.errors);
    warnings.push(...shell.warnings);
    for (const [k, c] of grid) {
      if (!isAC(c.name)) continue;
      const f = c.props.facing;
      if (H_VEC[f]) {
        const [x, y, z] = k.split(',').map(Number);
        const back = cellAt(x - H_VEC[f][0], y, z - H_VEC[f][1]);
        if (!back || isAC(back.name) || collisionOf(back) !== 'full' || opticsOf(back) !== 'opaque') {
          err(`fixture: the ${c.name} at ${k} has no full vanilla block behind it (${back ? back.name : 'nothing written'}): without the mod it leaves a hole`);
        }
      }
      if (/^(repo|ci):/.test(c.nbt?.binding ?? '')) err(`fixture: the ${c.name} at ${k} is bound to '${c.nbt.binding}' (a fixture takes no repos)`);
    }
    if (![...grid.values()].some((c) => !isAC(c.name) && emissionOf(c) > 0)) warnings.push('fixture: no vanilla light source: it is dark at night');
    errors.push(...doorCheck(grid, shell.outside).errors);
  } else if (!errors.some((e) => e.startsWith('sidecar: walk'))) {
    const shell = shellCheck(grid, size, w, sidecar.groundY);
    errors.push(...shell.errors);
    warnings.push(...shell.warnings);
    const light = lightCheck(grid, size, w, sidecar.groundY);
    errors.push(...light.errors);
    // enclosed spaces outside walk (attics, wall cavities) where a mob could spawn in the dark: a warning
    const lv = light.levels('nomod');
    const coll = (x, y, z) => { const c = cellAt(x, y, z); return c ? (isAC(c.name) ? 'none' : collisionOf(c)) : y < sidecar.groundY ? 'full' : 'none'; };
    let darkSpawn = 0;
    let firstDark = null;
    for (let z = 0; z < size[2]; z++) for (let y = 1; y < size[1] - 1; y++) for (let x = 0; x < size[0]; x++) {
      const inside = x >= w.minX && x <= w.maxX && y >= w.minY && y <= w.maxY && z >= w.minZ && z <= w.maxZ;
      if (inside || shell.outside.has(`${x},${y},${z}`) || lv[x + size[0] * (y + size[1] * z)] >= 1) continue;
      if (coll(x, y, z) !== 'none' || coll(x, y + 1, z) !== 'none' || !['full', 'slab', 'stairs'].includes(coll(x, y - 1, z))) continue;
      darkSpawn++;
      firstDark ??= `${x},${y},${z}`;
    }
    if (darkSpawn) warnings.push(`light: ${darkSpawn} dark cell(s) in enclosed space outside walk (an attic?) where mobs could spawn, first at ${firstDark}`);
    errors.push(...doorCheck(grid, shell.outside).errors);
  }

  // ---- station blocks need a binding where one is expected
  for (const [k, c] of grid) {
    if (c.name === 'agentcraft:monitor' && !c.nbt?.binding) err(`monitor at ${k} has no binding`);
    if (c.name === 'agentcraft:task_board' && !/^repo:/.test(c.nbt?.binding ?? '')) err(`task_board at ${k} has no repo: binding`);
  }

  return { ok: errors.length === 0, errors, warnings };
}

/** Check a Blueprint object (serialises it exactly as write.mjs would). */
export function checkBlueprint(bp) {
  const structure = plain(bp.toStructure());
  const sidecar = JSON.parse(JSON.stringify(bp.sidecar()));
  return checkStructure(sidecar, structure);
}

/** Check written files. */
export function checkFiles(nbtPath, jsonPath) {
  let structure;
  let sidecar;
  try { structure = plain(parse(fs.readFileSync(nbtPath))); } catch (e) { return { ok: false, errors: [`${nbtPath}: cannot parse NBT: ${e.message}`], warnings: [] }; }
  try { sidecar = JSON.parse(fs.readFileSync(jsonPath, 'utf8')); } catch (e) { return { ok: false, errors: [`${jsonPath}: cannot parse JSON: ${e.message}`], warnings: [] }; }
  return checkStructure(sidecar, structure);
}
