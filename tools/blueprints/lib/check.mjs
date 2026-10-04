// Blueprint checker: validates a structure template + sidecar against docs/BUILDINGS.md.
//   checkFiles(nbtPath, jsonPath) / checkBlueprint(bp) -> { ok, errors: string[], warnings: string[] }
import fs from 'node:fs';
import { parse, plain } from './nbt.mjs';
import { BLOCKS, collisionOf, normalize, emissionOf, opticsOf, voxelsOf, faceMask } from './blocks.mjs';

export const CAST = ['juniper', 'kit', 'wren', 'rowan', 'tove'];
const STATIONS = ['meeting', 'lounge', 'library', 'terminal', 'testbench', 'mergestation', 'user'];
const STANDING_RE = /^(library|terminal|testbench|mergestation|meeting|lounge|user)(_\d+)?$/;
const SEAT_RE = /^(desk_.+|seat_.+|meeting(_\d+)?|lounge(_\d+)?)$/;
const HORIZ = [[1, 0], [-1, 0], [0, 1], [0, -1]];
const YAW_OF_FACING = { south: 0, west: 90, north: 180, east: -90 };

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

/**
 * Shell integrity (C5): flood-fills the outside of the building (the padded template box above ground; rows below
 * groundY are ground) through everything a mob could pass, once with AgentCraft blocks in place and once with
 * every agentcraft:* cell turned to air (a world opened without the mod). Walk cells reached only in the second pass
 * mean a functional block is part of the outer shell. Unwritten cells above ground count as open (placement
 * clears the box).
 * @returns {{ errors: string[], warnings: string[], outside: Set<string> }} `outside` = cells reached with the mod
 */
export function shellCheck(grid, size, walk, groundY) {
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
  const flood = (acAir) => {
    const parent = new Map();
    const start = fmt(-1, sy, -1);
    parent.set(start, null);
    const q = [[-1, sy, -1]];
    for (let i = 0; i < q.length; i++) {
      const [x, y, z] = q[i];
      for (const [, dx, dy, dz] of DIRS6) {
        const nx = x + dx; const ny = y + dy; const nz = z + dz;
        if (nx < -1 || nx > sx || nz < -1 || nz > sz || ny > sy || ny < Math.min(groundY, 0)) continue;
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
  const reachedWalk = (m) => [...m.keys()].filter((k) => { const [x, y, z] = k.split(',').map(Number); return inWalk(x, y, z); });
  const modWalk = reachedWalk(withMod);
  if (modWalk.length) {
    const entries = new Set();
    for (const k of modWalk) { const p = withMod.get(k); const [px, py, pz] = p.split(',').map(Number); if (!inWalk(px, py, pz)) entries.add(`${p} -> ${k}`); }
    warnings.push(`shell: ${modWalk.length} walk cell(s) reachable from outside (open door, gap or courtyard), entries: ${[...entries].slice(0, 4).join('; ')}`);
  }
  const modSet = new Set(modWalk);
  const leaks = new Map();
  for (const k of reachedWalk(noMod)) {
    if (modSet.has(k)) continue;
    let hole = null; // the AgentCraft cell on the path that is nearest the outside = the opening in the shell
    for (let p = noMod.get(k); p; p = noMod.get(p)) {
      const c = grid.get(p);
      if (c && isAC(c.name)) hole = [p, c.name];
    }
    if (hole) leaks.set(hole[0], hole[1]);
  }
  for (const [k, name] of [...leaks].slice(0, 8)) {
    errors.push(`shell: without the mod the ${name} at ${k} leaves a hole into the building (put a solid vanilla block behind it on the outside, or move it off the shell)`);
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
    const conductive = a && !isAC(a.name) && collisionOf(a) === 'full' && opticsOf(a) === 'opaque';
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
  if (!['single', 'group'].includes(sidecar.kind)) err(`sidecar: kind '${sidecar.kind}' must be single|group`);
  if (!Number.isInteger(sidecar.wings) || sidecar.wings < 1) err('sidecar: wings must be an int >= 1');
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
  for (const n of requiredAnchors(sidecar.wings)) if (!anchors[n]) err(`missing required anchor '${n}'`);
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
    } else if (name === 'decision_podium') {
      const c = cellAt(cx, cy, cz);
      if (!c || c.name !== 'agentcraft:decision_podium') err(`anchor ${full}: not on an agentcraft:decision_podium block (found ${c?.name ?? 'nothing'})`);
    } else if (name === 'goal_atrium') {
      if (!inWalk(a)) err(`anchor ${full} is outside walk`);
    }
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
  if (!errors.some((e) => e.startsWith('sidecar: walk'))) {
    const shell = shellCheck(grid, size, w, sidecar.groundY);
    errors.push(...shell.errors);
    warnings.push(...shell.warnings);
    errors.push(...lightCheck(grid, size, w, sidecar.groundY).errors);
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
