#!/usr/bin/env node
// node tools/blueprints/render.mjs <path.nbt | bundled id> [--out dir] [--sidecar path] [--no-cutaway] [--cut-y N] [--width N] [--transparent]
// Renders a vanilla structure template to PNG previews without launching the game:
//   <id>.preview-iso.png      isometric, from the front-left (entrance side visible)
//   <id>.preview-cutaway.png  the same with the roof removed (rows above the walk volume) so the furniture shows
//   <id>.preview-top.png      top-down
//   <id>.preview-front.png    front elevation
// The sidecar (<id>.blueprint.json) supplies `front` and `walk`; without one the front is south.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse, plain } from './lib/nbt.mjs';
import { encodePng } from './lib/png.mjs';
import { buildScene, renderIso, renderTop, renderFront } from './lib/render.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
const NBT_DIR = path.join(REPO, 'mod/src/main/resources/data/agentcraft_worlds/structure');
const JSON_DIR = path.join(REPO, 'mod/src/main/resources/data/agentcraft_worlds/blueprints');
const DEFAULT_OUT = path.join(REPO, 'artifacts/blueprint-preview');

/** Resolve `<path.nbt | id>` (+ optional sidecar override) to files. */
export function resolveInput(input, sidecarOverride) {
  let nbtPath = input;
  if (!input.endsWith('.nbt') && !fs.existsSync(input)) nbtPath = path.join(NBT_DIR, `${input}.nbt`);
  if (!fs.existsSync(nbtPath)) throw new Error(`no such structure: ${input} (looked at ${nbtPath})`);
  const id = path.basename(nbtPath, '.nbt');
  let sidecarPath = sidecarOverride ?? null;
  if (!sidecarPath) {
    for (const p of [path.join(path.dirname(nbtPath), `${id}.blueprint.json`), path.join(JSON_DIR, `${id}.blueprint.json`)]) {
      if (fs.existsSync(p)) { sidecarPath = p; break; }
    }
  }
  return { id, nbtPath, sidecarPath };
}

/** First row removed in the cutaway (rows >= this are dropped). */
export function cutawayRow(sidecar, structure) {
  const H = structure.size[1];
  // the ceiling slab sits one row above the highest standing cell (walk.maxY); drop it and everything above
  if (sidecar?.walk && Number.isFinite(sidecar.walk.maxY)) return Math.min(H, sidecar.walk.maxY + 1);
  return Math.max(2, Math.ceil(H / 2));
}

/**
 * Render every view of one structure.
 * @returns {{id, files: {iso, cutaway?, top, front}, ms: number, blocks: number, unknown: string[]}}
 */
export function renderStructure(input, { out = DEFAULT_OUT, sidecar: sidecarOverride, cutaway = true, cutY, width, bg } = {}) {
  const t0 = performance.now();
  const { id, nbtPath, sidecarPath } = resolveInput(input, sidecarOverride);
  const structure = plain(parse(fs.readFileSync(nbtPath)));
  const sidecar = sidecarPath ? JSON.parse(fs.readFileSync(sidecarPath, 'utf8')) : null;
  const unknown = new Set();
  const scene = buildScene(structure, { front: sidecar?.front ?? 'south', unknown });
  fs.mkdirSync(out, { recursive: true });
  const files = {};
  const write = (key, img) => {
    const p = path.join(out, `${id}.preview-${key}.png`);
    fs.writeFileSync(p, encodePng(img.width, img.height, img.data));
    files[key] = p;
  };
  const common = { width, bg };
  write('iso', renderIso(scene, common));
  if (cutaway) write('cutaway', renderIso(scene, { ...common, maxY: (cutY ?? cutawayRow(sidecar, structure)) - 1, hide: nearWallHider(scene, sidecar) }));
  write('top', renderTop(scene, { bg }));
  write('front', renderFront(scene, { bg }));
  return { id, files, ms: performance.now() - t0, blocks: structure.blocks.length, unknown: [...unknown].sort() };
}

/**
 * Cutaway helper: lowers the two walls nearest the camera (south = front, west = left) to sill height so the
 * interior is visible. Needs the sidecar's walk box (the interior); without one nothing is hidden.
 */
export function nearWallHider(scene, sidecar) {
  const w = sidecar?.walk;
  if (!w) return undefined;
  const [ax, az] = scene.rot(w.minX, w.minZ), [bx, bz] = scene.rot(w.maxX, w.maxZ);
  const x0 = Math.min(ax, bx), x1 = Math.max(ax, bx), z0 = Math.min(az, bz), z1 = Math.max(az, bz);
  const sill = w.minY + 2; // rows below this stay
  return (x, y, z) => y >= sill && ((z > z1 && z <= z1 + 2 && x >= x0 - 2 && x <= x1 + 2) || (x < x0 && x >= x0 - 2 && z >= z0 - 2 && z <= z1 + 2));
}

function parseArgs(argv) {
  const o = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--out') o.out = path.resolve(argv[++i]);
    else if (a === '--sidecar') o.sidecar = path.resolve(argv[++i]);
    else if (a === '--cutaway') o.cutaway = true;
    else if (a === '--no-cutaway') o.cutaway = false;
    else if (a === '--cut-y') o.cutY = Number(argv[++i]);
    else if (a === '--width') o.width = Number(argv[++i]);
    else if (a === '--transparent') o.bg = 'transparent';
    else if (a.startsWith('--')) throw new Error(`unknown option ${a}`);
    else o._.push(a);
  }
  return o;
}

function main() {
  let o;
  try { o = parseArgs(process.argv.slice(2)); } catch (e) { console.error(e.message); process.exit(2); }
  if (!o._.length) {
    console.error('usage: node tools/blueprints/render.mjs <path.nbt | id> [--out dir] [--sidecar path] [--no-cutaway] [--cut-y N] [--width N] [--transparent]');
    process.exit(2);
  }
  for (const input of o._) {
    try {
      const r = renderStructure(input, o);
      console.log(`${r.id}: ${r.blocks} blocks, ${r.ms.toFixed(0)} ms`);
      for (const f of Object.values(r.files)) console.log(`  ${path.relative(process.cwd(), f)}`);
      if (r.unknown.length) console.log(`  colour guessed from the name for: ${r.unknown.join(', ')}`);
    } catch (e) {
      console.error(`${input}: ${e.message}`);
      process.exit(1);
    }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
