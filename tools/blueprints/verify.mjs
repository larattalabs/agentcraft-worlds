#!/usr/bin/env node
// Visual + anchor verification loop for a blueprint (see docs/BUILDINGS.md "Verify loop").
//
//   node tools/blueprints/verify.mjs <id | path/to/id.nbt | namespace:vanilla/template/id>
//        [--port 7879] [--at x,y,z] [--rotation none|clockwise_90|180|counterclockwise_90]
//        [--keep] [--size x,y,z] [--no-clear] [--quick]
//
// Needs the dev client running (node tools/unix.mjs launch --backend sim --dev). It never launches
// the game itself. Output: artifacts/shots/blueprints/<id>/*.png + sheet.png, and an anchor table.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { DevClient, DEFAULT_PORT } from '../lib/devclient.mjs';
import { contactSheet } from '../lib/contactsheet.mjs';
import { readNbt } from './nbt.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ROTATIONS = ['none', 'clockwise_90', '180', 'counterclockwise_90'];

// ---- args -------------------------------------------------------------------------------------
const argv = process.argv.slice(2);
const flags = {};
const pos = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (!a.startsWith('--')) { pos.push(a); continue; }
  const k = a.slice(2);
  if (['port', 'at', 'rotation', 'size', 'world'].includes(k)) flags[k] = argv[++i];
  else flags[k] = true;
}
if (!pos[0] || flags.help) {
  console.error(`usage: node tools/blueprints/verify.mjs <id|path/to/id.nbt|ns:vanilla/template> [--port N] [--at x,y,z] [--rotation ${ROTATIONS.join('|')}] [--keep] [--size x,y,z] [--no-clear] [--quick]
  --at          template origin cell (pre-rotation min corner; /place rotates about it) in the world; default x=80, y=65-groundY (floor row replaces the y=64 grass), z=0
  --size        template size when it cannot be read (vanilla templates are looked up in the Minecraft jar)
  --quick       skip the extra orbit-ring views (4 exterior + anchors + top only)`);
  process.exit(pos[0] ? 0 : 2);
}
const rotation = flags.rotation ?? 'none';
if (!ROTATIONS.includes(rotation)) die(`bad --rotation '${rotation}' (${ROTATIONS.join(', ')})`);
function die(msg) { console.error(`verify: ${msg}`); process.exit(1); }
const log = (...a) => console.log(...a);

// ---- resolve the blueprint --------------------------------------------------------------------
const BUNDLED_STRUCT = path.join(root, 'mod/src/main/resources/data/agentcraft_worlds/structure');
const BUNDLED_SIDECAR = path.join(root, 'mod/src/main/resources/data/agentcraft_worlds/blueprints');

function resolveBlueprint(arg) {
  const looksLikePath = arg.endsWith('.nbt') || (arg.includes('/') && !arg.includes(':') && fs.existsSync(path.resolve(arg)));
  let nbtPath = null;
  let id;
  let templateId;
  if (looksLikePath) {
    nbtPath = path.resolve(arg);
    if (!fs.existsSync(nbtPath)) die(`no such file: ${nbtPath}`);
    id = path.basename(nbtPath, '.nbt');
  } else if (arg.includes(':')) {
    templateId = arg; // vanilla / other-namespace template that already exists in the game
    id = arg.replace(/[:/]+/g, '_');
  } else {
    id = arg;
    for (const dir of [BUNDLED_STRUCT, path.join(root, 'artifacts', 'blueprint-test'), path.join(root, 'artifacts', 'blueprints')]) {
      if (fs.existsSync(path.join(dir, `${id}.nbt`))) { nbtPath = path.join(dir, `${id}.nbt`); break; }
    }
    if (!nbtPath) templateId = `minecraft:${arg}`;
  }
  let sidecar = null;
  if (nbtPath) {
    for (const p of [path.join(path.dirname(nbtPath), `${id}.blueprint.json`), path.join(BUNDLED_SIDECAR, `${id}.blueprint.json`)]) {
      if (fs.existsSync(p)) { sidecar = { path: p, ...JSON.parse(fs.readFileSync(p, 'utf8')) }; break; }
    }
  }
  return { id, nbtPath, templateId, sidecar };
}

/** Size of a vanilla template: read it from the Minecraft client jar (the Gradle/Loom caches). */
function vanillaSize(templateId) {
  const [ns, p] = templateId.includes(':') ? templateId.split(':') : ['minecraft', templateId];
  const entry = `data/${ns}/structure/${p}.nbt`;
  let jars = [];
  const roots = [path.join(root, 'mod/.gradle'), path.join(os.homedir(), '.gradle/caches/fabric-loom'), path.join(root, 'mod/.gradle/loom-cache')];
  for (const r of roots) {
    if (!fs.existsSync(r)) continue;
    try {
      const out = execFileSync('find', [r, '-name', '*.jar', '-size', '+5M', '-path', '*minecraft*'], { encoding: 'utf8', timeout: 20000 });
      jars.push(...out.split('\n').filter(Boolean));
    } catch {}
  }
  const ver = (fs.readFileSync(path.join(root, 'mod/gradle.properties'), 'utf8').match(/^minecraft_version=(.+)$/m) ?? [])[1]?.trim();
  jars = jars.filter((j) => !ver || j.includes(`/${ver}/`) || j.includes(`-${ver}`)).concat(jars.filter((j) => ver && !j.includes(ver)));
  for (const jar of jars) {
    try {
      const buf = execFileSync('unzip', ['-p', jar, entry], { maxBuffer: 64 << 20, stdio: ['ignore', 'pipe', 'ignore'] });
      if (buf.length) return readNbt(buf).size;
    } catch {}
  }
  return null;
}

// ---- geometry ---------------------------------------------------------------------------------
/**
 * `/place template` rotates about the template's origin cell (not its centre), so a rotated template
 * extends to negative offsets. A block cell (x,z) goes to: clockwise_90 (-z,x), 180 (-x,-z),
 * counterclockwise_90 (z,-x); a point inside the cell turns about the cell centre.
 * Returns the world-offset point (relative to the placement origin) for a template-relative point.
 */
function rotatePoint(px, pz, rot) {
  const cx = Math.floor(px), cz = Math.floor(pz), fx = px - cx, fz = pz - cz;
  switch (rot) {
    case 'clockwise_90': return [-cz + (1 - fz), cx + fx];
    case '180': return [-cx + (1 - fx), -cz + (1 - fz)];
    case 'counterclockwise_90': return [cz + fz, -cx + (1 - fx)];
    default: return [px, pz];
  }
}
/** min/max cell offsets (relative to the origin) covered by a rotated sx x sz footprint */
function rotatedExtent(sx, sz, rot) {
  const a = rotatePoint(0.5, 0.5, rot).map(Math.floor), b = rotatePoint(sx - 0.5, sz - 0.5, rot).map(Math.floor);
  return { x0: Math.min(a[0], b[0]), x1: Math.max(a[0], b[0]), z0: Math.min(a[1], b[1]), z1: Math.max(a[1], b[1]) };
}

// ---- main -------------------------------------------------------------------------------------
const t0 = Date.now();
const bp = resolveBlueprint(pos[0]);
const port = flags.port ? Number(flags.port) : DEFAULT_PORT;
const worldDir = path.resolve(flags.world ?? path.join(root, 'mod/run/saves/AgentCraft HQ'));
// the game writes the PNGs to its shots dir (AGENTCRAFT_SHOTS_DIR, default <repo>/artifacts/shots); the sheet and report go next to them
const outDir = path.join(path.resolve(process.env.AGENTCRAFT_SHOTS_DIR ?? path.join(root, 'artifacts', 'shots')), 'blueprints', bp.id);

// size
let size = null;
if (flags.size) size = flags.size.split(',').map(Number);
else if (bp.sidecar?.size) size = [bp.sidecar.size.x, bp.sidecar.size.y, bp.sidecar.size.z];
else if (bp.nbtPath) size = readNbt(fs.readFileSync(bp.nbtPath)).size;
else size = vanillaSize(bp.templateId);
if (!size) die(`cannot determine the size of ${bp.templateId}; pass --size x,y,z`);
const [sx, sy, sz] = size;
const groundY = bp.sidecar?.groundY ?? 0;
const ext = rotatedExtent(sx, sz, rotation);
const rx = ext.x1 - ext.x0 + 1, rz = ext.z1 - ext.z0 + 1;
const at = flags.at ? flags.at.split(',').map(Number) : [80, 65 - groundY, 0];
if (at.length !== 3 || at.some(Number.isNaN)) die('--at needs x,y,z');
const [ox, oy, oz] = at;
const bounds = { minX: ox + ext.x0, minY: oy, minZ: oz + ext.z0, maxX: ox + ext.x1, maxY: oy + sy - 1, maxZ: oz + ext.z1 };
log(`blueprint ${bp.id}: ${bp.nbtPath ? 'nbt ' + path.relative(root, bp.nbtPath) : 'existing template ' + bp.templateId}, sidecar ${bp.sidecar ? path.relative(root, bp.sidecar.path) : 'none'}`);
log(`size ${sx}x${sy}x${sz}, rotation ${rotation}, origin ${ox},${oy},${oz}, bounds ${bounds.minX},${bounds.minY},${bounds.minZ} .. ${bounds.maxX},${bounds.maxY},${bounds.maxZ}`);

let dev;
try {
  dev = await DevClient.connect({ port, timeoutMs: 8000 });
} catch (e) {
  die(`the dev client is not running or refused the connection (${e.message}).\n  Start it first:  node tools/unix.mjs launch --backend sim --dev   (this script never launches it)`);
}
log(`connected to DevBridge :${port}${dev.hello?.version ? ' (' + dev.hello.version + ')' : ''}`);

const call = (type, payload, opts) => dev.call(type, payload, opts);
async function cmd(c, { expectSuccess = false } = {}) {
  const r = await call('dev.command', { cmd: c });
  if (expectSuccess && r.success === false) throw new Error(`command failed: ${c} -> ${(r.messages ?? []).join(' | ')}`);
  return r;
}

let copiedTemplate = null;
const cleanup = async () => {
  if (flags.keep) return;
  if (!flags['no-clear']) await clearBox(bounds, 4).catch((e) => log(`cleanup: ${e.message}`));
  if (copiedTemplate) fs.rmSync(copiedTemplate, { force: true });
};

/** /fill air over a padded box in slabs (<= 32768 blocks per command), then lay a grass floor. */
async function clearBox(b, pad) {
  const x0 = b.minX - pad, x1 = b.maxX + pad, z0 = b.minZ - pad, z1 = b.maxZ + pad;
  const y0 = Math.max(-64, Math.min(b.minY, 64)), y1 = b.maxY + pad;
  const area = (x1 - x0 + 1) * (z1 - z0 + 1);
  const layers = Math.max(1, Math.floor(30000 / area));
  for (let y = y0; y <= y1; y += layers) {
    await cmd(`/fill ${x0} ${y} ${z0} ${x1} ${Math.min(y + layers - 1, y1)} ${z1} minecraft:air`);
  }
  // restore the superflat surface: stone/dirt below, grass top at y=64
  await cmd(`/fill ${x0} 64 ${z0} ${x1} 64 ${z1} minecraft:grass_block`);
  await cmd(`/fill ${x0} 62 ${z0} ${x1} 63 ${z1} minecraft:dirt`);
}

async function viewTo(x, y, z, yaw, pitch, fov = 70) {
  await call('dev.camera', { x, y, z, yaw, pitch, fov, mode: 'spectator' });
}

try {
  await call('dev.time', { ticks: 6000 });
  await call('dev.weather', { weather: 'clear' });
  await dev.waitInWorld({ timeoutMs: 120_000, onWait: (ms) => log(`waiting for a world (${Math.round(ms / 1000)}s)`) });

  // 1. template into the world
  let templateId = bp.templateId;
  if (bp.nbtPath) {
    // the structure manager caches templates by id for the whole session (hits AND misses): a unique id per run so an
    // edited .nbt is never served stale; removed again at the end.
    const unique = `${bp.id.toLowerCase().replace(/[^a-z0-9_]/g, "_")}_v${Date.now().toString(36)}`;
    const dest = path.join(worldDir, 'generated', 'agentcraft_worlds', 'structure', `${unique}.nbt`);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(bp.nbtPath, dest);
    copiedTemplate = dest;
    templateId = `agentcraft_worlds:${unique}`;
    log(`copied ${path.relative(root, bp.nbtPath)} -> ${path.relative(root, dest)}`);
  }

  // load the site: fly there, wait for chunks, clear + floor, place
  const cx = (bounds.minX + bounds.maxX) / 2, cy = (bounds.minY + bounds.maxY) / 2, cz = (bounds.minZ + bounds.maxZ) / 2;
  await viewTo(cx, 90, cz - 40, 0, 40);
  await call('dev.waitChunks', { timeoutMs: 60_000 }).catch(() => {});
  await clearBox(bounds, 6);
  const place = await cmd(`/place template ${templateId} ${ox} ${oy} ${oz} ${rotation}`);
  const placeMsg = (place.messages ?? []).join(' | ');
  if (place.success === false) {
    throw new Error(`/place template ${templateId} failed: ${placeMsg || '(no message)'}\n  (copied files are only found by id from <world>/generated/<ns>/structure/ or datapack data/<ns>/structure/; a structure in the mod's resources needs a rebuild + game restart)`);
  }
  log(`placed: ${placeMsg}`);

  // 2. shots
  await call('dev.time', { ticks: 6000 });
  await call('dev.weather', { weather: 'clear' });
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  const tiles = [];
  const fovV = 70;
  const diag = Math.sqrt(rx * rx + sy * sy + rz * rz);
  const R = (diag / 2) / Math.sin((fovV / 2) * Math.PI / 180) * 0.95 + 2; // 3D distance that frames the bounding sphere
  const elev = 45 * Math.PI / 180;

  /** eye position + point to look at -> explicit yaw/pitch (MC: yaw 0 = +Z, 90 = -X, pitch > 0 looks down) */
  const aim = (eye, target, fov) => {
    const dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
    return { ...eye, yaw: Math.atan2(-dx, dz) * 180 / Math.PI, pitch: -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI, fov };
  };

  async function shoot(name, title, camera, note) {
    const t = Date.now();
    try {
      await call('dev.camera', { ...camera, mode: 'spectator' });
      await call('dev.waitChunks', { timeoutMs: 30_000 }).catch(() => {});
      const r = await call('dev.screenshot', { name: `bp_${bp.id}_${name}`, hideHud: true, frames: 3, waitChunks: true });
      const dest = path.join(outDir, `${name}.png`);
      fs.copyFileSync(r.path, dest);
      const warn = [];
      if (r.stats?.meanLuma < 6) warn.push('nearly black');
      if (r.stats?.stdLuma < 2) warn.push('flat image');
      if (r.chunksTimedOut) warn.push('chunks unfinished');
      tiles.push({ name, title, path: dest, status: 'ok', note: warn.join('; ') || note });
      log(`  ${name}.png  ${Date.now() - t} ms  luma ${r.stats?.meanLuma ?? '?'}${warn.length ? '  ! ' + warn.join('; ') : ''}`);
    } catch (e) {
      tiles.push({ name, title, status: 'failed', note: e.message });
      log(`  ${name}: FAILED ${e.message}`);
    }
  }

  const corners = [['sw', -1, 1], ['se', 1, 1], ['ne', 1, -1], ['nw', -1, -1]];
  for (const [nm, dx, dz] of corners) {
    const h = R * Math.cos(elev) / Math.SQRT2;
    await shoot(`ext_${nm}`, `exterior ${nm.toUpperCase()}`, aim({ x: cx + dx * h, y: cy + R * Math.sin(elev), z: cz + dz * h }, { x: cx, y: cy, z: cz }, fovV));
  }
  const camAnchors = bp.sidecar ? Object.entries(bp.sidecar.anchors ?? {}).filter(([k]) => k.startsWith('cam_')) : [];
  if (rotation !== 'none' && camAnchors.length) log(`  (cam_* anchors are only shot for rotation none; skipping ${camAnchors.length})`);
  else for (const [k, a] of camAnchors) {
    const cam = { x: ox + a.x, y: oy + a.y, z: oz + a.z, fov: a.fov ?? 70 };
    if (a.lookAt) cam.lookAt = { x: ox + a.lookAt.x, y: oy + a.lookAt.y, z: oz + a.lookAt.z };
    else { cam.yaw = a.yaw ?? 0; cam.pitch = a.pitch ?? 0; }
    await shoot(k, k, cam, 'anchor');
  }
  await shoot('top', 'top-down', { x: cx, y: bounds.maxY + Math.max(rx, rz) * 0.75 / Math.tan((fovV / 2) * Math.PI / 180) * 0.9 + 4, z: cz, yaw: 0, pitch: 90, fov: fovV });
  if (!flags.quick) {
    // low-ish ground view of the entrance side (front faces `front` rotated), for a human-scale look
    await shoot('front', 'front at eye level', aim({ x: cx, y: bounds.minY + groundY + 1.7, z: bounds.maxZ + Math.max(rx, rz) * 0.9 + 6 }, { x: cx, y: cy, z: cz }, 75));
  }

  // 3. anchor checks
  const rows = [];
  const STAND = /^(desk_|seat_|meeting|lounge|library|terminal|testbench|mergestation|user|entrance|spawn)/; // task_wall, decision_podium, goal_atrium, trophy* are block anchors
  if (bp.sidecar?.anchors) {
    const isOpen = async (x, y, z) => {
      // open = block tag #minecraft:replaceable (air, grass, snow layer, ...); everything else counts as solid
      const r = await cmd(`/execute if block ${x} ${y} ${z} #minecraft:replaceable`);
      return r.success !== false && !/Test failed/i.test((r.messages ?? []).join(' '));
    };
    const isSeat = async (x, y, z) => {
      // seat anchors (desks, meeting chairs, sofas) put the feet inside a stairs block, as in the studio
      const r = await cmd(`/execute if block ${x} ${y} ${z} #minecraft:stairs`);
      return r.success !== false && !/Test failed/i.test((r.messages ?? []).join(' '));
    };
    for (const [name, a] of Object.entries(bp.sidecar.anchors)) {
      if (name.startsWith('cam_')) continue;
      const base = name.replace(/@\d+$/, '');
      if (!STAND.test(base)) continue;
      // monitor_*/task boards are block anchors (centre of a surface), not feet spots: skip
      const [dx, dz] = rotatePoint(a.x, a.z, rotation);
      const wx = Math.floor(ox + dx), wy = Math.floor(oy + a.y + 1e-6), wz = Math.floor(oz + dz);
      const below = !(await isOpen(wx, wy - 1, wz));
      const feet = await isOpen(wx, wy, wz);
      const head = await isOpen(wx, wy + 1, wz);
      const inWalk = bp.sidecar.walk ? (a.x >= bp.sidecar.walk.minX && a.x <= bp.sidecar.walk.maxX + 1 && a.z >= bp.sidecar.walk.minZ && a.z <= bp.sidecar.walk.maxZ + 1) : null;
      const problems = [];
      if (!below) problems.push('no solid floor below');
      const sits = !feet && (await isSeat(wx, wy, wz)); // a seat anchor sits inside the chair block
      if (!feet && !sits) problems.push('feet cell blocked');
      if (!head) problems.push('head cell blocked');
      if (inWalk === false) problems.push('outside walk');
      rows.push({ name, cell: `${wx},${wy},${wz}`, below, feet: feet || sits, head, inWalk, ok: problems.length === 0, problems: sits ? [...problems, 'seat'].filter((p) => p !== 'seat' || problems.length === 0) : problems });
    }
  }
  if (rows.length) {
    const w = Math.max(...rows.map((r) => r.name.length));
    log(`\nanchor checks (${rows.filter((r) => r.ok).length}/${rows.length} ok):`);
    log(`  ${'anchor'.padEnd(w)}  world cell     floor feet head walk  result`);
    for (const r of rows) log(`  ${r.name.padEnd(w)}  ${r.cell.padEnd(13)}  ${(r.below ? 'ok' : 'NO').padEnd(5)} ${(r.feet ? 'ok' : 'NO').padEnd(4)} ${(r.head ? 'ok' : 'NO').padEnd(4)} ${(r.inWalk === null ? '-' : r.inWalk ? 'ok' : 'NO').padEnd(4)}  ${r.ok ? 'OK' : r.problems.join(', ')}`);
  } else if (bp.sidecar) log('\nanchor checks: no standing anchors in the sidecar');
  else log('\nanchor checks: no sidecar, skipped');

  // 4. contact sheet + report
  const sheet = contactSheet({
    out: path.join(outDir, 'sheet.png'),
    title: `${bp.id}  ${sx}x${sy}x${sz}  rot ${rotation}`,
    subtitle: `origin ${ox},${oy},${oz}  ${rows.length ? rows.filter((r) => r.ok).length + '/' + rows.length + ' anchors ok' : 'no anchor checks'}`,
    tiles,
    columns: 3,
    thumbWidth: 640,
  });
  fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify({ id: bp.id, template: templateId, size, rotation, origin: at, bounds, shots: tiles.map(({ name, status, note }) => ({ name, status, note })), anchors: rows }, null, 2) + '\n');
  log(`\nPNGs in ${path.relative(root, outDir)}/:`);
  for (const t of tiles) log(`  ${t.status === 'ok' ? '' : '[' + t.status + '] '}${t.path ? path.relative(root, t.path) : t.name}`);
  log(`contact sheet: ${path.relative(root, sheet.path)}`);
  const bad = tiles.filter((t) => t.status !== 'ok').length;
  process.exitCode = bad || rows.some((r) => !r.ok) ? 1 : 0;
} catch (e) {
  console.error(`verify: ${e.message}`);
  process.exitCode = 2;
} finally {
  await cleanup().catch((e) => log(`cleanup: ${e.message}`));
  if (flags.keep) log(`--keep: test site left in place (${bounds.minX},${bounds.minY},${bounds.minZ} .. ${bounds.maxX},${bounds.maxY},${bounds.maxZ})`);
  await call('dev.release', {}).catch(() => {});
  dev.close();
  log(`done in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
}
