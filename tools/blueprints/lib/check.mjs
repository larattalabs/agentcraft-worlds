// Blueprint checker: validates a structure template + sidecar against docs/BUILDINGS.md.
//   checkFiles(nbtPath, jsonPath) / checkBlueprint(bp) -> { ok, errors: string[], warnings: string[] }
import fs from 'node:fs';
import { parse, plain } from './nbt.mjs';
import { BLOCKS, collisionOf, normalize } from './blocks.mjs';

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
  const wingAnchors = Object.keys(anchors).filter((n) => n.startsWith('task_wall@'));
  for (const n of wingAnchors) {
    const k = Number(n.slice('task_wall@'.length));
    if (!Number.isInteger(k) || k < 1 || k > sidecar.wings) err(`anchor ${n}: wing out of range 1..${sidecar.wings}`);
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

  for (const [name, a] of Object.entries(anchors)) {
    const cx = Math.floor(a.x);
    const cy = Math.floor(a.y + 1e-6);
    const cz = Math.floor(a.z);
    const standing = STANDING_RE.test(name) || /^(desk_|seat_)/.test(name) || name === 'entrance' || name === 'spawn';

    if (standing) {
      if (!inWalk(a)) err(`anchor ${name} (${a.x},${a.y},${a.z}) is outside walk`);
      const feetCell = cellAt(cx, cy, cz);
      const feet = cls(feetCell);
      const seat = SEAT_RE.test(name) && (feet === 'stairs' || feet === 'slab');
      if (!Number.isInteger(a.y)) warnings.push(`anchor ${name}: y ${a.y} is not on a block boundary`);
      const below = cellAt(cx, cy - 1, cz);
      const bc = cls(below);
      if (!(bc === 'full' || (bc === 'slab' && below.props.type !== 'bottom'))) err(`anchor ${name}: no solid block below (${below ? below.name : 'nothing written'} at ${cx},${cy - 1},${cz})`);
      if (seat) {
        for (const dy of [1, 2]) {
          const c = cls(cellAt(cx, cy + dy, cz));
          if (c !== 'none') err(`anchor ${name}: seat has no free headroom at +${dy} (${cellAt(cx, cy + dy, cz)?.name ?? 'unwritten'})`);
        }
        if (!HORIZ.some(([dx, dz]) => standable(cx + dx, cy, cz + dz))) err(`anchor ${name}: no free standable cell next to the seat (agents cannot step in)`);
      } else {
        if (!(feet === 'none' || feet === 'low')) err(`anchor ${name}: feet cell is ${feetCell ? feetCell.name : 'not cleared (unwritten)'} (needs air / non-collision)`);
        const head = cls(cellAt(cx, cy + 1, cz));
        if (head !== 'none') err(`anchor ${name}: head cell is ${cellAt(cx, cy + 1, cz)?.name ?? 'not cleared (unwritten)'} (needs air / non-collision)`);
      }
    } else if (name.startsWith('monitor_')) {
      const c = cellAt(cx, Math.floor(a.y - 0.01), cz);
      if (!c || c.name !== 'agentcraft:monitor') err(`anchor ${name}: not on an agentcraft:monitor block (found ${c?.name ?? 'nothing'} at ${cx},${Math.floor(a.y - 0.01)},${cz})`);
      else {
        if (YAW_OF_FACING[c.props.facing] !== undefined && yawDiff(a.yaw, YAW_OF_FACING[c.props.facing]) > 1) err(`anchor ${name}: yaw ${a.yaw} does not match monitor facing ${c.props.facing}`);
        const id = name.slice('monitor_'.length);
        if (c.nbt?.binding !== id) err(`anchor ${name}: monitor binding is '${c.nbt?.binding ?? ''}', expected '${id}'`);
      }
    } else if (name.startsWith('task_wall@')) {
      const c = cellAt(cx, cy, cz);
      if (!c || c.name !== 'agentcraft:task_board') err(`anchor ${name}: not on an agentcraft:task_board block (found ${c?.name ?? 'nothing'})`);
      else {
        if (yawDiff(a.yaw, YAW_OF_FACING[c.props.facing]) > 1) err(`anchor ${name}: yaw ${a.yaw} does not match board facing ${c.props.facing}`);
        const n = name.slice('task_wall@'.length);
        if (c.nbt?.binding !== `repo:#${n}`) err(`anchor ${name}: board binding is '${c.nbt?.binding ?? ''}', expected 'repo:#${n}'`);
      }
    } else if (name === 'decision_podium') {
      const c = cellAt(cx, cy, cz);
      if (!c || c.name !== 'agentcraft:decision_podium') err(`anchor ${name}: not on an agentcraft:decision_podium block (found ${c?.name ?? 'nothing'})`);
    } else if (name === 'goal_atrium') {
      if (!inWalk(a)) err(`anchor ${name} is outside walk`);
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
