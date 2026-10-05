#!/usr/bin/env node
// End-to-end smoke test: launches (or attaches to) the dev client against the sim backend in a fresh throwaway world
// on natural terrain, then drives the main flows through the DevBridge with assertions and screenshots. macOS
// (tools/mac.mjs); with --attach any running dev client on --dev-port.
//
//   node tools/smoke.mjs                 # launch (sim, scratch Foreman home, world "Smoke" recreated), run, stop
//   npm run smoke --prefix tools         # the same
//   node tools/smoke.mjs --attach        # use the running client (it must be a fresh sim world: no buildings)
//   node tools/smoke.mjs --keep          # leave the launched client running afterwards (stop: mac.mjs stop --profile smoke)
//
// Options: --port N (Foreman, 7978) --dev-port N (DevBridge, 7979) --world NAME (Smoke) --seed N (2026) --speed N (3)
// --home DIR (Foreman home; default a new temp dir) --minutes N (overall deadline, 12).
//
// Output: artifacts/shots/smoke/ (report.json, smoke.log, the screenshots, contact-sheet.png). Exit 0 when every
// step passed, 1 when a step failed or was skipped, 2 when the run could not start.
//
// Steps (each logs its checks; a step whose prerequisite failed is skipped): preflight, place two buildings (natural
// terrain site search with the server verdict) and the village board, a road laid and removed (exact block restore),
// an agent walking between the buildings, night in beds and the morning, move a building and undo (both sites
// restored), goals for both repos and a stand-up, the Inbox with a simulated PR (automated review -> triage
// decision), answering a decision, a merge through the diff screen's confirm (a real merge commit), a goal done ->
// trophy, removing a building that holds trophies (exact restore), the Settings MCP editor with a fake secret (stored,
// never shown or logged), layout at 426x240 GUI px with toast priority, the HUD overlay styles at 426x240 (every
// style, position and size with two boss bars and both effect rows: shown, no overlaps; a peek; Off; F1), placement
// HUD shots (ready, server refusal, too far).

import { spawnSync, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DevClient } from './lib/devclient.mjs';
import { contactSheet } from './lib/contactsheet.mjs';
import { checkMorning, checkNight, bedsOf } from './lib/routinesqa.mjs';
import { POSITIONS, SCENE, SCENE_UNDO, SIZES, STYLES, hudProblems, restorePayload, sceneProblems } from './lib/hudstyles.mjs';
import {
  SmokeRunner, boxCentre, boxText, boxesOverlap, countStates, diffDumps, fakeSecret, findSecret, growBox, layoutProblem,
  parseBox, pollUntil, redact, scanFiles, selector, summaryLines,
} from './lib/smoke.mjs';

const tools = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(tools, '..');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- options ------------------------------------------------------------------------------------------------

function options(argv) {
  const o = { port: 7978, 'dev-port': 7979, world: 'Smoke', seed: '2026', speed: '3', minutes: '12' };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--help' || a === '-h') o.help = true;
    else if (['--attach', '--keep'].includes(a)) o[a.slice(2)] = true;
    else if (['--port', '--dev-port', '--world', '--seed', '--speed', '--home', '--minutes'].includes(a)) {
      if (argv[i + 1] === undefined) throw new Error(`${a} needs a value`);
      o[a.slice(2)] = argv[++i];
    } else throw new Error(`unknown option ${a}`);
  }
  o.port = Number(o.port);
  o['dev-port'] = Number(o['dev-port']);
  if (!/^[\w -]+$/.test(o.world)) throw new Error('invalid --world');
  return o;
}

const opt = (() => {
  try { return options(process.argv.slice(2)); } catch (e) { console.error(`smoke: ${e.message}`); process.exit(2); }
})();
if (opt.help) {
  console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 26).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'));
  process.exit(0);
}

// ---- output, logging ------------------------------------------------------------------------------------------

const outDir = path.join(root, 'artifacts', 'shots', 'smoke');
fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });
const logFile = path.join(outDir, 'smoke.log');
const secrets = [];
const t0 = Date.now();
function log(line) {
  const text = redact(`[${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s] ${line}`, secrets);
  process.stderr.write(`${text}\n`);
  fs.appendFileSync(logFile, `${text}\n`);
}

// ---- launch / attach --------------------------------------------------------------------------------------------

const PROFILE = 'smoke';
let launched = false;
let home = opt.home ? path.resolve(opt.home) : null;

function gradleHome() {
  if (process.env.GRADLE_USER_HOME) return process.env.GRADLE_USER_HOME;
  try {
    // worktrees share the main checkout's Gradle home (downloads Minecraft once)
    const common = execFileSync('git', ['-C', root, 'rev-parse', '--path-format=absolute', '--git-common-dir'], { encoding: 'utf8' }).trim();
    const main = path.join(path.dirname(common), '.gradle-home');
    if (fs.existsSync(main)) return main;
  } catch {}
  return path.join(root, '.gradle-home');
}

/** A fresh world: the save and this world's entries in the per-world client files (welcome card, Inbox marks, toggles). */
function freshWorld(name) {
  const runDir = path.join(root, 'mod', 'run');
  const save = path.join(runDir, 'saves', name);
  if (path.dirname(save) !== path.join(runDir, 'saves')) throw new Error('bad world name');
  fs.rmSync(save, { recursive: true, force: true });
  const dir = path.join(runDir, 'agentcraft');
  if (!fs.existsSync(dir)) return;
  for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
    const file = path.join(dir, f);
    try {
      const j = JSON.parse(fs.readFileSync(file, 'utf8'));
      let changed = false;
      if (j?.worlds && typeof j.worlds === 'object' && name in j.worlds) { delete j.worlds[name]; changed = true; }
      if (j && typeof j === 'object' && !Array.isArray(j) && name in j) { delete j[name]; changed = true; }
      if (changed) fs.writeFileSync(file, JSON.stringify(j, null, 2));
    } catch {}
  }
}

function macStop() {
  const r = spawnSync(process.execPath, [path.join(tools, 'mac.mjs'), 'stop', '--profile', PROFILE], { cwd: root, encoding: 'utf8', timeout: 120_000 });
  log(`stop: ${(r.stdout + r.stderr).trim().split('\n').slice(-3).join(' | ')}`);
}

async function launch() {
  const runFile = path.join(root, 'artifacts', 'run', `mac-game-${PROFILE}.json`);
  if (fs.existsSync(runFile)) {
    // a smoke client of this checkout is still recorded: stop it, so the world is created fresh
    log('a smoke client from an earlier run is recorded; stopping it first');
    macStop();
  }
  home ??= fs.mkdtempSync(path.join(os.tmpdir(), 'agentcraft-smoke-'));
  freshWorld(opt.world);
  const env = {
    ...process.env,
    GRADLE_USER_HOME: gradleHome(),
    AGENTCRAFT_DEV_TEST: '1', // test-only DevBridge commands
    AGENTCRAFT_WELCOME: '0', // the welcome card would take the first screen
    AGENTCRAFT_PLAYER: 'Sam', // a neutral player name in the shots
  };
  const args = [path.join(tools, 'mac.mjs'), 'launch', '--backend', 'sim', '--dev', '--world', opt.world, '--preset', 'normal', '--seed', String(opt.seed),
    '--home', home, '--profile', PROFILE, '--port', String(opt.port), '--dev-port', String(opt['dev-port']), '--reset',
    '--foreman-arg', '--user-name', '--foreman-arg', 'Sam', '--foreman-arg', '--sim-pr', '--foreman-arg', '--pr-watch', '--foreman-arg', 'on',
    '--foreman-arg', '--speed', '--foreman-arg', String(opt.speed)];
  log(`launch: world "${opt.world}" (normal, seed ${opt.seed}), Foreman :${opt.port} home ${home}, DevBridge :${opt['dev-port']}`);
  launched = true;
  const r = spawnSync(process.execPath, args, { cwd: root, env, encoding: 'utf8', timeout: 900_000 });
  fs.writeFileSync(path.join(outDir, 'launch.log'), `${r.stdout}\n${r.stderr}`);
  if (r.status !== 0) throw new Error(`mac.mjs launch failed (exit ${r.status}): ${(r.stderr || r.stdout).trim().split('\n').slice(-4).join(' | ')}`);
  log(`launch: ${r.stdout.trim().split('\n').slice(-2).join(' | ')}`);
}

let stopping = false;
async function cleanup() {
  if (stopping) return;
  stopping = true;
  if (launched && !opt.keep) macStop();
  else if (launched) log(`--keep: the client keeps running (stop: node tools/mac.mjs stop --profile ${PROFILE})`);
}
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => {
    log(`${sig}: stopping`);
    await cleanup();
    process.exit(130);
  });
}

// ---- the run ------------------------------------------------------------------------------------------------------

const runner = new SmokeRunner({ log, deadlineMs: Number(opt.minutes) * 60_000 });
const shots = []; // {name, path, step, stats}
const W = {}; // what the steps found: repos, buildings, boxes, dumps, decisions...
let dev;
let shotNo = 0;

async function call(type, payload = {}, opts) {
  return dev.call(type, payload, opts);
}

/** A screenshot under artifacts/shots/smoke/, recorded in the step and the contact sheet; checks it is not black. */
async function shot(ctx, name, { hud = false, chunkRadius = 6, waitChunks = true, frames = 3 } = {}) {
  const file = `smoke/${String(++shotNo).padStart(2, '0')}_${name}`;
  const s = await call('dev.screenshot', { name: file, hideHud: !hud, chunkRadius, waitChunks, frames, chunkTimeoutMs: 20_000 });
  const rec = { name: path.basename(file), path: s.path, step: ctx.stepName, stats: s.stats, chunksTimedOut: s.chunksTimedOut };
  shots.push(rec);
  ctx.shot(rec);
  ctx.check(`shot ${rec.name} is not blank`, (s.stats?.stdLuma ?? 0) > 2, s.stats);
  return rec;
}

async function camera(x, y, z, lookAt, mode = 'spectator') {
  return call('dev.camera', { x, y, z, lookAt, mode, closePause: true });
}

/** Puts the player (spectator camera) at the vantage point between the buildings, so routines and walks treat it as near. */
async function vantage() {
  const v = W.vantage;
  if (v) await camera(v.x, v.y, v.z, v.lookAt, 'creative');
}

async function closeScreens() {
  await call('dev.screen', { open: null }).catch(() => {});
  // only when placing: a cancel shows "Placement cancelled" for 8 s, and toasts keep clear of that panel
  const b = await call('dev.build.state').catch(() => null);
  if (b?.active) await call('dev.build.cancel').catch(() => {});
}

/** Removes mobs and dropped items from a box (they block placement: "in the box: chicken"). */
async function clearEntities(box) {
  const sel = selector(box);
  await call('dev.command', { cmd: `kill @e[type=!minecraft:player,${sel}]` });
  await call('dev.command', { cmd: `kill @e[type=minecraft:item,${sel}]` });
}

/** Waits for dev.build.state's server verdict (async: null at first), clearing the box of mobs while it is refused. */
async function verdict(ctx, { tries = 30 } = {}) {
  let s = await call('dev.build.state');
  for (let i = 0; i < tries; i++) {
    if (s.serverVerdict && (s.ready || s.serverVerdict.ok === false)) return s;
    if (s.conflicts?.refusals?.some((r) => /in the box|dropped items|Move these out/.test(r))) {
      const box = parseBox(s.conflicts.snapshotBox ?? s.box);
      if (box) await clearEntities(growBox(box, 2, 4));
      if (s.moving) {
        const old = W.buildings?.[s.moving]?.snapshotBox;
        if (old) await clearEntities(growBox(old, 12, 8));
      }
    }
    await sleep(200);
    s = await call('dev.build.state');
  }
  return s;
}

/** The site of a candidate: placement started there, its verdict and the reasons it is not good enough. */
async function trySite(ctx, blueprint, repos, [x, z, turns = 0], { strict = true, gap = 6, clearFront = 0 } = {}) {
  await call('dev.build.start', { blueprint, repos, origin: [x, 0, z], ground: true, turns });
  let s = await call('dev.build.state');
  const box = parseBox(s.conflicts?.snapshotBox ?? s.box);
  if (box) await clearEntities(growBox(box, 2, 4));
  s = await verdict(ctx);
  const c = s.conflicts ?? {};
  const why = [];
  if (!s.ready) why.push(`not ready: ${(s.serverVerdict?.refusals ?? c.refusals ?? []).join('; ') || 'no verdict'}`);
  if (strict && c.site?.warnings?.length) why.push(`site warnings: ${c.site.warnings.join('; ')}`);
  if (strict && c.approach?.short) why.push(c.approach.short);
  if (strict && (c.water ?? 0) > 0) why.push(`${c.water} water cells`);
  // a board must be readable: nothing solid (a tree, a hill) within `clearFront` blocks in front of its face
  if (clearFront && box) {
    const d = { south: [0, 1], north: [0, -1], east: [1, 0], west: [-1, 0] }[s.front] ?? [0, 1];
    const front = d[1] > 0 ? { x0: box.x0, x1: box.x1, z0: box.z1 + 1, z1: box.z1 + clearFront } : d[1] < 0 ? { x0: box.x0, x1: box.x1, z0: box.z0 - clearFront, z1: box.z0 - 1 }
      : d[0] > 0 ? { x0: box.x1 + 1, x1: box.x1 + clearFront, z0: box.z0, z1: box.z1 } : { x0: box.x0 - clearFront, x1: box.x0 - 1, z0: box.z0, z1: box.z1 };
    // at the height of the board's face (the ground may rise on a slope; trees are what hide a board)
    const dump = await call('dev.roads.blocks', { ...front, y0: box.y0 + 2, y1: box.y1 });
    const trees = countStates(dump, /leaves|_log\b|_log\}|_wood/);
    if (trees) why.push(`${trees} leaf/log blocks in front of it`);
    s.treesInFront = trees;
  }
  // keep `gap` blocks from what stands already (a building's approach and foundation are in its snapshot box)
  for (const b of Object.values(W.buildings ?? {})) {
    if (box && boxesOverlap(growBox(box, gap, 0), b.snapshotBox)) why.push(`within ${gap} blocks of ${b.id}`);
  }
  return { state: s, box, why };
}

/** Starts placement at the first good candidate, shoots the HUD (optional), confirms; returns the building record. */
async function place(ctx, label, blueprint, repos, candidates, { hudShot, gap, clearFront } = {}) {
  let chosen = null;
  const tried = [];
  // a site with trees in front only (natural forest) is kept as the fallback with the fewest leaves/logs
  let fallback = null;
  for (const cand of candidates) {
    const r = await trySite(ctx, blueprint, repos, cand, { gap, clearFront });
    tried.push({ at: cand, why: r.why });
    if (!r.why.length) { chosen = { ...r, at: cand }; break; }
    if (r.why.length === 1 && r.state.treesInFront && (!fallback || r.state.treesInFront < fallback.state.treesInFront)) fallback = { ...r, at: cand };
  }
  if (!chosen && fallback) {
    ctx.note(`${label}: every site has trees in front; taking ${fallback.at.join(',')} (${fallback.state.treesInFront} leaf/log blocks)`);
    await call('dev.build.start', { blueprint, repos, origin: [fallback.at[0], 0, fallback.at[1]], ground: true, turns: fallback.at[2] ?? 0 });
    chosen = { ...fallback, state: await verdict(ctx) };
  }
  ctx.data(`${label}Sites`, tried);
  ctx.require(`${label}: a candidate site with no refusal or warning`, !!chosen, tried.map((t) => `${t.at.join(',')}: ${t.why.join('; ')}`).join(' | '));
  const s = chosen.state;
  ctx.check(`${label}: the server verdict is in and ok`, s.serverVerdict?.ok === true, s.serverVerdict);
  ctx.check(`${label}: the HUD says ready`, s.ready === true);
  const snapshotBox = parseBox(s.conflicts.snapshotBox);
  const before = await call('dev.roads.blocks', snapshotBox);
  if (hudShot) {
    const c = boxCentre(chosen.box);
    await camera(c.x - 18, chosen.box.y1 + 14, chosen.box.z1 + 26, { x: c.x, y: chosen.box.y0 + 4, z: c.z }, 'creative');
    // the ghost follows nothing (locked by origin); re-check the verdict after the camera moved
    await verdict(ctx);
    await shot(ctx, hudShot, { hud: true });
  }
  const res = await call('dev.build.confirm', {});
  ctx.require(`${label}: placed`, res.placed === true && !!res.buildingId, res.message ?? res.lastResult);
  const id = res.buildingId;
  const hub = await call('dev.hub.state');
  const b = [...(hub.buildings ?? []), ...(hub.fixtures ?? [])].find((x) => x.id === id);
  ctx.require(`${label}: ${id} is listed by the hub`, !!b);
  const rec = { id, blueprint, repos, box: parseBox(b.box), snapshotBox: parseBox(b.snapshotBox ?? s.conflicts.snapshotBox), preDump: before, origin: s.origin };
  ctx.check(`${label}: the snapshot box is the one the ghost showed`, boxText(rec.snapshotBox) === boxText(snapshotBox), `${boxText(rec.snapshotBox)} vs ${boxText(snapshotBox)}`);
  W.buildings[id] = rec;
  ctx.note(`${label}: ${id} ${blueprint} at ${boxText(rec.box)} (${res.message})`);
  return rec;
}

/** Notes the differences a restore leaves that are not its own (leaf distances, fluids flowing, live station states). */
function noteSettled(ctx, d) {
  const parts = [];
  if (d.leafDistance) parts.push(`${d.leafDistance} leaves with a recomputed distance`);
  if (d.flow) parts.push(`${d.flow} cells of flowing water/lava settled`);
  if (d.live) parts.push(`${d.live} station blocks with another live state`);
  if (parts.length) ctx.note(`not counted: ${parts.join(', ')} (e.g. ${JSON.stringify(d.settled.slice(0, 2))})`);
}

const inbox = () => call('dev.inbox.state');
const decisionsOpen = async () => (await call('dev.decisions')).queue ?? [];

/** Waits for an open decision matching `pred`; returns it or null. */
async function waitDecision(pred, timeoutMs, signal) {
  return pollUntil(async () => (await decisionsOpen()).find(pred) ?? null, { timeoutMs, everyMs: 500, signal });
}

const gitMerges = (repo) => {
  try { return Number(execFileSync('git', ['-C', repo, 'rev-list', '--count', '--merges', 'main'], { encoding: 'utf8' }).trim()); } catch { return -1; }
};

async function main() {
  // ---- preflight -------------------------------------------------------------------------------------------------
  await runner.step('preflight', { timeoutMs: 900_000 }, async (ctx) => {
    if (!opt.attach) await launch();
    dev = await DevClient.connect({ port: opt['dev-port'], timeoutMs: 60_000 });
    const st = await dev.waitInWorld({ timeoutMs: 300_000 });
    ctx.data('world', st.world?.name);
    ctx.require('in a world', st.ready === true);
    if (!opt.attach) ctx.check(`the world is "${opt.world}"`, st.world?.name === opt.world, st.world?.name);
    ctx.require('in the Overworld', st.world?.dimension === 'minecraft:overworld', st.world?.dimension);
    const fm = await pollUntil(async () => { const f = (await call('dev.foreman')); return f.link === 'synced' ? f : null; }, { timeoutMs: 60_000 });
    ctx.require('the Foreman link is synced', !!fm);
    const f = await call('dev.state');
    ctx.require('the backend is sim', f.foreman?.backend === 'sim', f.foreman?.backend);
    ctx.check('the connection is not read-only', f.foreman?.readOnly === false);
    const repos = (await call('dev.hub.open', { tab: 'repos' })).reposTab?.repos ?? [];
    W.repos = repos.map((r) => ({ id: r.id, path: r.path }));
    ctx.data('repos', W.repos.map((r) => r.id));
    W.prRepo = repos.find((r) => r.id.startsWith('pocket-api'));
    W.demoRepo = repos.find((r) => r.id.startsWith('sim-demo'));
    ctx.require('the sim demo repo and the PR demo repo are registered', !!(W.prRepo && W.demoRepo), W.repos);
    const hub = await call('dev.hub.state');
    ctx.require('a fresh world: no buildings yet', (hub.buildings ?? []).length === 0 && (hub.fixtures ?? []).length === 0, (hub.buildings ?? []).map((b) => b.id));
    const j = await call('dev.journal.state');
    ctx.check('the world journal is empty', (j.entries ?? []).length === 0, (j.entries ?? []).length);
    await closeScreens();
    // a still world: no day cycle or weather, no random ticks (grass under a building turning to dirt, leaf decay)
    // so that block dumps taken minutes apart compare exactly
    for (const cmd of ['gamerule advance_time false', 'gamerule advance_weather false', 'gamerule random_tick_speed 0', 'gamerule spawn_mobs false', 'time set 6000', 'weather clear']) {
      const r = await call('dev.command', { cmd });
      ctx.check(`/${cmd}`, r.success !== false, r.messages);
    }
    ctx.check('trophies on', (await call('dev.trophies.toggle', { on: true })).enabled === true);
    ctx.check('walking between buildings on', (await call('dev.walk.toggle', { on: true })).enabled === true);
    for (const t of ['night', 'standups', 'library']) ctx.check(`routine ${t} on`, (await call('dev.routines.toggle', { toggle: t, on: true })).enabled === true);
    W.buildings = {};
  });

  // ---- buildings --------------------------------------------------------------------------------------------------
  // Candidate sites for seed 2026 (natural terrain around the spawn hill), tried in order; each must place with no
  // refusal and no site warning. Origins are the rotated box's minimum corner (x, z); y comes from the ground.
  await runner.step('place_buildings', { needs: ['preflight'], timeoutMs: 120_000 }, async (ctx) => {
    await camera(-26, 140, 30, { x: -40, y: 118, z: -20 }, 'creative');
    W.A = await place(ctx, 'home building', 'workshop', [W.demoRepo.id], [[-40, -40], [-90, 10], [-80, -5], [110, 10]], { hudShot: 'placement_ready_hud' });
    W.B = await place(ctx, 'second building', 'workshop', [W.prRepo.id], [[-80, -5], [-5, -40], [-90, 10], [110, 10], [60, 10]]);
    const hub = await call('dev.hub.state');
    const a = hub.buildings.find((b) => b.id === W.A.id);
    ctx.check(`${W.A.id} is the home building`, a?.home === true);
    const ac = boxCentre(W.A.box);
    const bc = boxCentre(W.B.box);
    // between the two buildings, a little up: within 64 blocks of both boxes (stand-ups) and in render distance
    const mid = { x: (ac.x + bc.x) / 2, z: (ac.z + bc.z) / 2 };
    W.vantage = { x: mid.x, y: Math.max(W.A.box.y1, W.B.box.y1) + 10, z: mid.z + 8, lookAt: { x: mid.x, y: Math.min(W.A.box.y0, W.B.box.y0), z: mid.z - 12 } };
    ctx.check('the vantage point is within 64 blocks of both buildings (stand-ups)', [W.A.box, W.B.box].every((b) => Math.hypot(Math.max(b.x0 - W.vantage.x, 0, W.vantage.x - b.x1), Math.max(b.z0 - W.vantage.z, 0, W.vantage.z - b.z1)) < 48), W.vantage);
    await vantage();
    await shot(ctx, 'buildings_placed');
  });

  await runner.step('village_board', { needs: ['place_buildings'], timeoutMs: 90_000 }, async (ctx) => {
    // in front of the home building's entrance, off its approach
    const a = W.A.snapshotBox;
    const cands = [];
    for (const [dx, dz] of [[4, 3], [8, 6], [0, 8], [12, 3], [-10, 4], [4, 14], [16, 8], [-16, 10], [8, 18]]) cands.push([dx < 0 ? a.x0 + dx : a.x1 + dx, a.z1 + dz]);
    W.board = await place(ctx, 'village board', 'village_board', [], cands, { gap: 2, clearFront: 4 });
    const bs = await pollUntil(async () => { const s = await call('dev.board.state'); return s.rows?.length >= 2 ? s : null; }, { timeoutMs: 10_000 });
    ctx.require('the board lists both buildings', !!bs, bs?.rows);
    ctx.check('the board rows are the two buildings', [W.A.id, W.B.id].every((id) => bs.rows.some((r) => r.building === id)), bs.rows.map((r) => r.building));
    ctx.check('the board is a fixture', (bs.fixtures ?? []).some((f) => f.id === W.board.id));
    // the board must have been seen (drawn) before dev.board.aim knows it: look at the fixture's box first
    const bc = boxCentre(W.board.box);
    for (const [dx, dz] of [[0, 9], [0, -9], [9, 0], [-9, 0]]) {
      await camera(bc.x + dx, bc.y + 1, bc.z + dz, bc);
      await sleep(300);
      if ((await call('dev.board.state')).boards?.length) break;
    }
    const aim = await call('dev.board.aim', { distance: 5 });
    await camera(aim.eye.x, aim.eye.y + 0.6, aim.eye.z, aim.point);
    await sleep(500);
    const drawn = await pollUntil(async () => { const s = await call('dev.board.state'); return (s.boards ?? []).length ? s : null; }, { timeoutMs: 5_000 });
    ctx.check('the board is drawn', !!drawn, drawn?.boards?.[0]?.texts);
    await shot(ctx, 'village_board');
    // up close: the text a hair in front of its own background (wave 3 text depth)
    const near = await call('dev.board.aim', { distance: 2 });
    await camera(near.eye.x + 0.9, near.eye.y + 0.4, near.eye.z, near.point);
    await shot(ctx, 'text_depth_village_board', { chunkRadius: 3 });
    await vantage();
  });

  // ---- road --------------------------------------------------------------------------------------------------------
  await runner.step('road_lay_remove', { needs: ['place_buildings'], timeoutMs: 120_000 }, async (ctx) => {
    await vantage();
    const plan = await call('dev.roads.plan', { a: W.A.id, b: W.B.id, fresh: true }, { timeoutMs: 60_000 });
    ctx.require('a road route between the buildings', plan.status === 'found', plan);
    const pv = await call('dev.roads.preview', { a: W.A.id, b: W.B.id });
    ctx.require('the preview has no refusal', !pv.refusal, pv.refusal);
    const box = growBox(parseBox(pv.box), 2, 4);
    ctx.data('box', boxText(box));
    const before = await call('dev.roads.blocks', box);
    const lay = await call('dev.roads.lay', {});
    ctx.require('laid', lay.ok === true && !!lay.roadId, lay.message);
    W.road = lay.roadId;
    const laid = await call('dev.roads.blocks', box);
    const d1 = diffDumps(before, laid, { max: 3 });
    ctx.check('the road changed blocks', d1.differ > 0, d1.differ);
    ctx.check('the road has path blocks', countStates(laid, /dirt_path|gravel/) > countStates(before, /dirt_path|gravel/));
    const rs = await call('dev.roads.state');
    ctx.check(`dev.roads.state lists ${W.road}`, (rs.roads ?? []).some((r) => r.id === W.road));
    const c = boxCentre(box);
    await camera(c.x + 6, box.y1 + 14, c.z + 14, { x: c.x, y: box.y0 + 4, z: c.z });
    await shot(ctx, 'road_laid');
    const rm = await call('dev.roads.remove', { road: W.road });
    ctx.require('removed', rm.ok === true, rm.message);
    await sleep(500);
    const after = await call('dev.roads.blocks', box);
    const d = diffDumps(before, after);
    ctx.data('restore', d);
    ctx.check('exact restore: every cell as before the road', d.differ === 0, d.diffs);
    noteSettled(ctx, d);
    await vantage();
  });

  // ---- walking ----------------------------------------------------------------------------------------------------
  await runner.step('agent_walks', { needs: ['place_buildings'], timeoutMs: 120_000 }, async (ctx) => {
    await vantage();
    const agents = (await call('dev.agents')).agents ?? [];
    const walker = ['kit', 'wren', 'rowan', 'tove', 'juniper'].find((id) => agents.some((a) => a.id === id));
    ctx.require('an agent to send', !!walker, agents.map((a) => a.id));
    const send = await call('dev.walk.send', { agent: walker, to: W.B.id });
    ctx.require(`${W.B.id} can host ${walker}`, send.canHost === true, send);
    let seenWalking = null;
    const done = await pollUntil(async () => {
      const ws = await call('dev.walk.state');
      const trip = (ws.trips ?? []).find((t) => t.agent === walker);
      if (trip?.phase === 'walking' && !seenWalking) seenWalking = trip;
      const rec = (ws.recent ?? []).find((r) => r.agent === walker && String(r.to).includes(W.B.id));
      return rec ?? null;
    }, { timeoutMs: 30_000, everyMs: 200, signal: ctx.signal });
    ctx.check(`${walker} set off on foot`, !!seenWalking || done?.outcome === 'walk', seenWalking ?? done);
    ctx.require(`${walker}'s trip to ${W.B.id} is a walk, not a teleport`, done?.outcome === 'walk', done);
    ctx.data('trip', done);
    // and it really moves along the route
    const start = (await call('dev.agents')).agents.find((a) => a.id === walker);
    const moved = await pollUntil(async () => {
      const a = (await call('dev.agents')).agents.find((x) => x.id === walker);
      return a && Math.hypot(a.x - start.x, a.z - start.z) > 3 ? a : null;
    }, { timeoutMs: 20_000, everyMs: 250, signal: ctx.signal });
    ctx.check(`${walker} moves on foot (more than 3 blocks)`, !!moved, moved ? undefined : { from: [start.x, start.z] });
    // shoot the walker on its way
    const pos = (await call('dev.agents')).agents.find((a) => a.id === walker);
    const ws = await call('dev.walk.state');
    if ((ws.trips ?? []).some((t) => t.agent === walker && t.phase === 'walking') && pos) {
      await camera(pos.x + 5, pos.y + 4, pos.z + 5, { x: pos.x, y: pos.y + 1, z: pos.z });
      await shot(ctx, 'agent_walking', { chunkRadius: 4 });
      await vantage();
    } else ctx.note('the walk ended before the shot');
    await call('dev.walk.send', { agent: walker, to: null });
    await call('dev.agents', { settle: true });
  });

  // ---- night and morning ----------------------------------------------------------------------------------------------
  await runner.step('night_and_morning', { needs: ['place_buildings'], timeoutMs: 150_000 }, async (ctx) => {
    await vantage();
    await call('dev.routines.time', { at: 'night' });
    const night = await pollUntil(async () => { const s = await call('dev.routines.state'); const r = checkNight(s); return r.ok ? { s, r } : null; }, { timeoutMs: 60_000, everyMs: 1000, signal: ctx.signal });
    const last = night ?? { r: checkNight(await call('dev.routines.state')) };
    ctx.require('night: agents rest in beds', !!night, last.r.reasons);
    ctx.data('lying', night.r.lying);
    const bed = [...bedsOf(night.s).values()].find((b) => b.agent);
    if (bed) {
      const [x, y, z] = bed.head;
      // from the cell beside the bed the agents step in from (inside the room), a little up
      const ap = String(bed.approach ?? '').split(/\s+/).map(Number);
      const [ex, ez] = ap.length === 3 && ap.every(Number.isFinite) ? [ap[0], ap[2]] : [x + 2.5, z + 2.5];
      await camera(ex + (ex - x - 0.5) * 0.8, y + 2.4, ez + (ez - z - 0.5) * 0.8, { x: x + 0.5, y: y + 0.5, z: z + 0.5 });
      await shot(ctx, 'night_in_bed', { chunkRadius: 3 });
    }
    await vantage();
    await call('dev.routines.time', { at: 'morning' });
    const morning = await pollUntil(async () => { const s = await call('dev.routines.state'); return checkMorning(s, night.r.lying).ok ? s : null; }, { timeoutMs: 60_000, everyMs: 1000, signal: ctx.signal });
    ctx.check('morning: everyone is up', !!morning, morning ? undefined : checkMorning(await call('dev.routines.state'), night.r.lying).reasons);
    await call('dev.routines.time', { ticks: 6000 });
  });

  // ---- move and undo -------------------------------------------------------------------------------------------------
  await runner.step('move_and_undo', { needs: ['place_buildings'], timeoutMs: 150_000 }, async (ctx) => {
    const B = W.B;
    const oldBefore = await call('dev.roads.blocks', B.snapshotBox);
    // candidate sites near the old one; the ground height of each comes from a placement preview (the move ghost
    // keeps its height when nudged)
    const sx = B.box.x1 - B.box.x0 + 1;
    const sz = B.box.z1 - B.box.z0 + 1;
    const sites = [];
    for (const [tx, tz] of [[B.box.x0, B.box.z1 + 16], [B.box.x0 - sx - 14, B.box.z0], [B.box.x0, B.box.z0 - sz - 14], [B.box.x0 - sx - 14, B.box.z1 + 16], [B.box.x1 + 30, B.box.z1 + 16]]) {
      await call('dev.build.start', { blueprint: B.blueprint, repos: B.repos, origin: [tx, 0, tz], ground: true });
      const p = await call('dev.build.state');
      sites.push(p.origin);
    }
    await call('dev.build.cancel');
    await clearEntities(growBox(B.snapshotBox, 12, 8));
    // a camera facing north from the south: nudges are then forward = -z, right = +x (measured below anyway)
    await camera(B.box.x0 - 10, B.box.y1 + 30, B.box.z1 + 60, { x: B.box.x0 - 10, y: B.box.y0, z: B.box.z1 }, 'creative');
    await call('dev.hub.open', { tab: 'buildings', buildingId: B.id });
    await call('dev.hub.action', { action: 'move', buildingId: B.id });
    await call('dev.build.lock', { on: true });
    let s = await call('dev.build.state');
    ctx.require(`moving ${B.id}`, s.moving === B.id, s.moving);
    const o0 = s.origin;
    s = await call('dev.build.nudge', { forward: 1 });
    const f = [s.origin[0] - o0[0], s.origin[2] - o0[2]];
    s = await call('dev.build.nudge', { forward: -1, right: 1 });
    const rv = [s.origin[0] - o0[0], s.origin[2] - o0[2]];
    s = await call('dev.build.nudge', { right: -1 });
    const det = f[0] * rv[1] - f[1] * rv[0];
    ctx.require('nudges move the ghost', det !== 0, { f, rv });
    // the first candidate the server accepts
    let target = null;
    const tried = [];
    for (const [tx, ty, tz] of sites) {
      const cur = (await call('dev.build.state')).origin;
      const dx = tx - cur[0];
      const dz = tz - cur[2];
      const a = (dx * rv[1] - dz * rv[0]) / det;
      const b = (f[0] * dz - f[1] * dx) / det;
      await call('dev.build.nudge', { forward: Math.round(a), right: Math.round(b), up: ty - cur[1] });
      s = await verdict(ctx);
      const why = [];
      if (!s.ready) why.push((s.serverVerdict?.refusals ?? s.conflicts?.refusals ?? ['no verdict']).join('; '));
      if (s.conflicts?.approach?.short) why.push(s.conflicts.approach.short);
      if (W.A && boxesOverlap(growBox(parseBox(s.conflicts.snapshotBox), 4, 0), growBox(W.A.snapshotBox, 4, 0))) why.push(`next to ${W.A.id}`);
      if (W.board && boxesOverlap(growBox(parseBox(s.conflicts.snapshotBox), 4, 0), W.board.snapshotBox)) why.push('next to the board');
      tried.push({ at: [tx, ty, tz], origin: s.origin, why });
      if (!why.length) { target = s; break; }
    }
    ctx.data('moveSites', tried);
    ctx.require('a site to move to', !!target, tried);
    await shot(ctx, 'move_ghost_hud', { hud: true, chunkRadius: 6 });
    const newBox = parseBox(target.conflicts.snapshotBox);
    const newBefore = await call('dev.roads.blocks', newBox);
    const res = await call('dev.build.confirm', {});
    ctx.require('moved', res.placed === true, res.message ?? res.lastResult);
    ctx.note(res.message);
    const hub = await call('dev.hub.state');
    const moved = hub.buildings.find((b) => b.id === B.id);
    ctx.check(`${B.id} stands at the new site`, boxText(parseBox(moved.snapshotBox)) === boxText(newBox), moved.snapshotBox);
    const oldAfterMove = await call('dev.roads.blocks', B.snapshotBox);
    const dm = diffDumps(B.preDump, oldAfterMove);
    noteSettled(ctx, dm);
    ctx.check('the old site is as before the building was placed', dm.differ === 0, dm.diffs);
    await sleep(500);
    const u = await call('dev.hub.action', { action: 'undo_move', buildingId: B.id });
    const ur = u.lastAction?.action ? u.lastAction : u;
    ctx.require('undo move', ur.ok !== false, ur.message);
    ctx.note(ur.message);
    await sleep(1000);
    const newAfter = await call('dev.roads.blocks', newBox);
    const oldAfter = await call('dev.roads.blocks', B.snapshotBox);
    const d1 = diffDumps(newBefore, newAfter);
    const d2 = diffDumps(oldBefore, oldAfter);
    ctx.data('newSite', d1);
    ctx.data('oldSite', d2);
    ctx.check('the site it moved to is as it was', d1.differ === 0, d1.diffs);
    ctx.check('the building is back exactly as it stood', d2.differ === 0, d2.diffs);
    noteSettled(ctx, d1);
    noteSettled(ctx, d2);
    const back = (await call('dev.hub.state')).buildings.find((b) => b.id === B.id);
    ctx.check(`${B.id} is back at its site`, boxText(parseBox(back.box)) === boxText(B.box), back.box);
    await closeScreens();
    await vantage();
  });

  // ---- goals, stand-up ------------------------------------------------------------------------------------------------
  await runner.step('goals', { needs: ['place_buildings'], timeoutMs: 30_000 }, async (ctx) => {
    await vantage();
    const g1 = await call('dev.goals.submit', { text: 'Add #tags to notes', repoId: W.demoRepo.id });
    ctx.require('goal for the home repo submitted', g1.ok !== false && !!g1.result?.goalId, g1.message);
    const g2 = await call('dev.goals.submit', { text: 'Document the notes export endpoint', repoId: W.prRepo.id });
    ctx.require('goal for the PR repo submitted', g2.ok !== false && !!g2.result?.goalId, g2.message);
    W.goalMain = g1.result.goalId;
    W.goalPr = g2.result.goalId;
    ctx.data('goals', { main: W.goalMain, pr: W.goalPr });
  });

  await runner.step('standup', { needs: ['goals'], timeoutMs: 120_000 }, async (ctx) => {
    await vantage();
    // the stand-up of the home goal: its lead and the workers of its first assignments gather at the meeting table
    let shotDone = false;
    const started = await pollUntil(async () => {
      const s = await call('dev.routines.state');
      const run = (s.standups?.running ?? []).find((r) => r.goal === W.goalMain || r.goalId === W.goalMain);
      if (run && !shotDone) {
        shotDone = true;
        const anchors = (await call('dev.anchors')).anchors ?? {};
        const m = anchors.meeting;
        if (m) {
          await camera(m.x + 4, m.y + 3.2, m.z + 4, { x: m.x, y: m.y + 1.2, z: m.z });
          await sleep(4000); // the first lines are spoken a few seconds in
          await shot(ctx, 'standup', { chunkRadius: 3 });
          await vantage();
        }
      }
      return (s.standups?.history ?? []).find((h) => h.goal === W.goalMain && h.outcome === 'started') ?? null;
    }, { timeoutMs: 60_000, everyMs: 300, signal: ctx.signal });
    if (!started) {
      const s = await call('dev.routines.state');
      ctx.note(`no stand-up by itself: ${JSON.stringify(s.standups?.history ?? []).slice(0, 400)}`);
    }
    ctx.check('the home goal held a stand-up', !!started);
    const ended = await pollUntil(async () => (await call('dev.routines.state')).standups?.history?.find((h) => h.goal === W.goalMain && h.outcome === 'ended'), { timeoutMs: 45_000, everyMs: 1000, signal: ctx.signal });
    ctx.check('the stand-up ended', !!ended);
  });

  // ---- Inbox, simulated PR, triage ------------------------------------------------------------------------------------
  await runner.step('inbox_pr_and_triage', { needs: ['goals'], timeoutMs: 180_000 }, async (ctx) => {
    await vantage();
    const prOpen = await waitDecision((d) => d.kind === 'merge' && /pull request/i.test(d.question), 60_000, ctx.signal);
    ctx.require('the PR goal asks to open a pull request', !!prOpen);
    const items = (await inbox()).items ?? [];
    ctx.check('the Inbox lists it under Needs you', items.some((i) => i.ref === prOpen.id && i.group === 'needs_you' && i.open));
    await call('dev.hub.open', { tab: 'inbox', item: prOpen.id });
    await sleep(400);
    await shot(ctx, 'inbox_pr_decision', { hud: false, waitChunks: false });
    // Merge in the Inbox asks twice
    await call('dev.hub.action', { action: 'inbox_answer', item: prOpen.id, option: 'Merge' });
    await call('dev.hub.action', { action: 'inbox_answer', item: prOpen.id, option: 'Merge' });
    const sent = await pollUntil(async () => !(await decisionsOpen()).some((d) => d.id === prOpen.id), { timeoutMs: 10_000 });
    ctx.require('answered from the Inbox: the pull request is opened', !!sent);
    await closeScreens();
    // the fake host's checks pass, its automated review comes in, the lead triages it: catch the PR item meanwhile
    let prItem = null;
    const triage = await pollUntil(async () => {
      const ib = await inbox();
      prItem ??= (ib.items ?? []).find((i) => i.kind === 'pr') ?? null;
      return (await decisionsOpen()).find((d) => /replies|resolve/i.test(d.question) && /PR #\d+/.test(d.question)) ?? null;
    }, { timeoutMs: 60_000, everyMs: 100, signal: ctx.signal });
    ctx.require('a triage decision for the PR (replies to post)', !!triage);
    W.prNumber = Number(triage.question.match(/PR #(\d+)/)[1]);
    ctx.data('pr', W.prNumber);
    if (prItem) ctx.check('the Inbox showed the PR while it had new review threads', /thread|changes|failing/.test(`${prItem.detail} ${prItem.title}`), prItem);
    else ctx.note('the PR item (new review threads) was not caught between the review and the triage (it lasts about a second at this speed)');
    const thread = (await call('dev.hub.open', { tab: 'goals', goalId: W.goalPr, view: 'thread' })).goalsTab?.goal?.thread ?? [];
    const review = thread.find((t) => /automated review/i.test(t.text ?? ''));
    ctx.check('the goal thread has the automated review (Claude Code Review format) line', !!review, review?.text);
    ctx.check('the goal thread has the triage', thread.some((t) => /triaged PR #/i.test(t.text ?? '')));
    await shot(ctx, 'goal_thread_pr_review', { waitChunks: false });
    await call('dev.hub.open', { tab: 'inbox', item: triage.id });
    await sleep(300);
    const panel = (await inbox()).panel;
    ctx.check('the triage decision shows Post / Skip', JSON.stringify(panel?.buttons ?? []).includes('Post'), panel?.buttons);
    await shot(ctx, 'inbox_triage_decision', { waitChunks: false });
    await call('dev.hub.action', { action: 'inbox_answer', item: triage.id, option: 'Post' });
    ctx.require('triage answered: Post', !!await pollUntil(async () => !(await decisionsOpen()).some((d) => d.id === triage.id), { timeoutMs: 10_000 }));
    // the fold-in: the worker's review fixes come back as a decision to push them to the PR
    const push = await waitDecision((d) => d.kind === 'merge' && /review fixes/i.test(d.question), 60_000, ctx.signal);
    ctx.require('the review fixes ask to be pushed', !!push);
    await call('dev.hub.open', { tab: 'inbox', item: push.id });
    await call('dev.hub.action', { action: 'inbox_answer', item: push.id, option: 'Merge' });
    await call('dev.hub.action', { action: 'inbox_answer', item: push.id, option: 'Merge' });
    ctx.require('fixes pushed', !!await pollUntil(async () => !(await decisionsOpen()).some((d) => d.id === push.id), { timeoutMs: 10_000 }));
    await closeScreens();
  });

  // ---- answer a decision ---------------------------------------------------------------------------------------------
  await runner.step('answer_decision', { needs: ['goals'], timeoutMs: 90_000 }, async (ctx) => {
    const perm = await waitDecision((d) => d.kind === 'permission' || d.kind === 'question' && d.goalId !== W.goalPr && !/PR #/.test(d.question), 60_000, ctx.signal);
    ctx.require('the home goal asks something', !!perm);
    await call('dev.hub.open', { tab: 'inbox', item: perm.id });
    await sleep(300);
    const panel = (await inbox()).panel;
    ctx.check('the answer panel is on that decision', panel?.decisionId === perm.id, panel?.decisionId);
    await shot(ctx, 'inbox_answer_panel', { waitChunks: false });
    const option = perm.options.find((o) => /^Allow once$/i.test(o)) ?? perm.options[0];
    await call('dev.hub.action', { action: 'inbox_answer', item: perm.id, option });
    const gone = await pollUntil(async () => !(await decisionsOpen()).some((d) => d.id === perm.id), { timeoutMs: 10_000 });
    ctx.require(`answered "${option}"`, !!gone);
    const item = ((await inbox()).items ?? []).find((i) => i.ref === perm.id);
    ctx.check('the Inbox moves it to Updates as answered', item?.group === 'updates' && /answered/.test(item?.detail ?? ''), item);
    await closeScreens();
  });

  // ---- merge through the diff screen -------------------------------------------------------------------------------------
  await runner.step('merge_via_diff_screen', { needs: ['answer_decision'], timeoutMs: 120_000 }, async (ctx) => {
    const merge = await waitDecision((d) => d.kind === 'merge' && /^Merge /.test(d.question) && d.goalId !== W.goalPr, 90_000, ctx.signal);
    ctx.require('a merge decision of the home goal', !!merge);
    const before = gitMerges(W.demoRepo.path);
    await call('dev.diff', { decisionId: merge.id, open: true });
    const ready = await pollUntil(async () => { const s = await call('dev.diff.state'); return s.load === 'ready' ? s : null; }, { timeoutMs: 15_000 });
    ctx.require('the diff loaded', !!ready);
    ctx.check('the diff has files', (ready.files ?? []).length > 0, ready.files?.map((f) => f.path));
    const armed = await call('dev.diff', { mode: 'confirm_merge' });
    ctx.check('Merge arms the confirm', armed.mode === 'confirm_merge', armed.mode);
    await shot(ctx, 'diff_confirm_merge', { waitChunks: false });
    await sleep(400); // a confirm by key needs a fresh press at least 300 ms after arming
    await call('dev.key', { key: 'key.keyboard.enter' });
    const gone = await pollUntil(async () => !(await decisionsOpen()).some((d) => d.id === merge.id), { timeoutMs: 15_000 });
    ctx.require('Enter on Confirm merge answered it', !!gone);
    const merged = await pollUntil(async () => (gitMerges(W.demoRepo.path) > before ? true : null), { timeoutMs: 30_000, everyMs: 500 });
    ctx.check('a merge commit landed on main of the demo repo', !!merged, { before, after: gitMerges(W.demoRepo.path) });
    await closeScreens();
  });

  // ---- goal done -> trophy ------------------------------------------------------------------------------------------------
  await runner.step('trophy_on_goal_done', { needs: ['inbox_pr_and_triage'], timeoutMs: 150_000 }, async (ctx) => {
    await vantage();
    const done = await pollUntil(async () => {
      const t = await call('dev.trophies.list');
      return (t.awarded ?? []).find((k) => k.startsWith(`goal:${W.goalPr}:`)) ? t : null;
    }, { timeoutMs: 120_000, everyMs: 1000, signal: ctx.signal });
    ctx.require('the PR goal finished (PR approved and completed) and its trophy is awarded', !!done);
    const b = done.buildings.find((x) => x.id === W.B.id);
    const slots = (b?.slots ?? []).filter((s) => s.key);
    const goalSign = slots.find((s) => s.key.startsWith(`goal:${W.goalPr}:`));
    ctx.require(`a "Goal done" sign hangs in ${W.B.id}`, !!goalSign && goalSign.lines?.[0] === 'Goal done', slots.map((s) => s.lines));
    ctx.check(`the merged PR has its trophy too`, slots.some((s) => s.key.startsWith('pr:')), slots.map((s) => s.key));
    const cell = await call('dev.roads.blocks', { x0: goalSign.x, y0: goalSign.y, z0: goalSign.z, x1: goalSign.x, y1: goalSign.y, z1: goalSign.z });
    ctx.check('the sign is in the world', /wall_sign/.test(cell.palette[cell.cells[0]]), cell.palette[cell.cells[0]]);
    W.trophyCells = slots.map((s) => [s.x, s.y, s.z]);
    // the board copies the trophy ledger every 5 s
    if (!W.board) ctx.note('no village board: its milestones are not checked');
    else {
    const bs = await pollUntil(async () => { const b = await call('dev.board.state'); return (b.milestones ?? []).some((m) => m.key === goalSign.key && m.trophy) ? b : null; }, { timeoutMs: 15_000 });
    ctx.check('the village board lists the milestone with its trophy', !!bs);
    }
    // the sign faces into the room: look at it from 2.5 blocks in front (the side it faces)
    const facing = (cell.palette[cell.cells[0]].match(/facing=(\w+)/) ?? [])[1];
    const off = { north: [0, -1], south: [0, 1], west: [-1, 0], east: [1, 0] }[facing] ?? [0, 1];
    const c = { x: goalSign.x + 0.5, y: goalSign.y + 0.5, z: goalSign.z + 0.5 };
    await camera(c.x + off[0] * 2.5 + off[1] * 0.8, c.y + 0.4, c.z + off[1] * 2.5 + off[0] * 0.8, c);
    await shot(ctx, 'trophy_goal_done', { chunkRadius: 3 });
    await vantage();
  });

  // ---- remove a building that holds trophies ----------------------------------------------------------------------------------
  await runner.step('remove_building_with_trophies', { needs: ['trophy_on_goal_done'], timeoutMs: 120_000 }, async (ctx) => {
    const B = W.B;
    await vantage();
    await call('dev.hub.open', { tab: 'buildings', buildingId: B.id });
    const first = await call('dev.hub.action', { action: 'remove', buildingId: B.id });
    ctx.check('the first Remove arms it', !!first.armed || first.armedRemove === B.id, first.message);
    let res = await call('dev.hub.action', { action: 'remove', buildingId: B.id });
    let rr = res.lastAction?.action ? res.lastAction : res;
    if (rr.ok === false && /Move these|force/i.test(rr.message ?? '')) {
      ctx.note(`refused: ${rr.message}; clearing the box and trying again`);
      await clearEntities(growBox(B.snapshotBox, 2, 4));
      await call('dev.hub.action', { action: 'remove', buildingId: B.id });
      res = await call('dev.hub.action', { action: 'remove', buildingId: B.id });
      rr = res.lastAction?.action ? res.lastAction : res;
    }
    ctx.require('removed (the trophies do not block it)', rr.ok === true, rr.message);
    ctx.note(rr.message);
    await closeScreens();
    await sleep(1000);
    const after = await call('dev.roads.blocks', B.snapshotBox);
    const d = diffDumps(B.preDump, after);
    ctx.data('restore', d);
    ctx.check('exact restore: the site is as before the building (signs gone too)', d.differ === 0, d.diffs);
    noteSettled(ctx, d);
    for (const [x, y, z] of W.trophyCells ?? []) {
      const cell = await call('dev.roads.blocks', { x0: x, y0: y, z0: z, x1: x, y1: y, z1: z });
      ctx.check(`no sign left at ${x} ${y} ${z}`, !/sign/.test(cell.palette[cell.cells[0]]), cell.palette[cell.cells[0]]);
    }
    const t = await call('dev.trophies.list');
    ctx.check(`the trophy ledger no longer lists ${B.id}`, !(t.buildings ?? []).some((b) => b.id === B.id));
    const hub = await call('dev.hub.state');
    ctx.check(`${B.id} is gone from the hub`, !(hub.buildings ?? []).some((b) => b.id === B.id));
    const c = boxCentre(B.box);
    await camera(c.x + 10, B.box.y1 + 12, B.box.z1 + 22, { x: c.x, y: B.box.y0, z: c.z });
    await shot(ctx, 'site_after_remove');
    await vantage();
  });

  // ---- Settings: MCP server editor with a fake secret --------------------------------------------------------------------------
  await runner.step('settings_mcp_secret', { needs: ['preflight'], timeoutMs: 120_000 }, async (ctx) => {
    const secret = fakeSecret();
    secrets.push(secret);
    const name = 'smoke-files';
    const KEY = 'claude.context.mcpServers';
    const ENV = 'SMOKE_API_TOKEN';
    await call('dev.hub.open', { tab: 'settings', group: 'context' });
    await sleep(300);
    const scrollTo = async () => { await call('dev.hub.action', { action: 'settings_scroll', key: KEY }); await sleep(150); };
    // the editor grows below the fold: a field or chip that was not drawn last frame is scrolled into view (down from the
    // MCP row) and tried again
    const visible = async (what, fn) => {
      await scrollTo();
      for (let i = 0; ; i++) {
        const r = await dev.request('dev.hub.action', fn());
        if (r.ok) return r;
        if (i >= 10 || !/drawn last frame/.test(r.error ?? '')) throw new Error(`${what}: ${r.error}`);
        await call('dev.hub.action', { action: 'settings_scroll', by: 40 });
        await sleep(120);
      }
    };
    const field = (id, text) => visible(id, () => ({ action: 'settings_field', field: id, text }));
    const press = (button) => visible(button, () => ({ action: 'press', button }));
    await press(`settings:${KEY}:add`);
    await sleep(150);
    await field('mcp:name', name);
    await field('mcp:command', 'npx');
    await field('mcp:args', '-y\n@modelcontextprotocol/server-filesystem\n/tmp');
    await field('mcp:env:name', ENV);
    await field('mcp:env:value', secret);
    await press('mcp:env:set');
    await sleep(150);
    await shot(ctx, 'settings_mcp_editor_staged', { waitChunks: false });
    await press('mcp:done');
    await sleep(150);
    let st = (await call('dev.hub.state')).settingsTab;
    const row = st.form.rows.find((r) => r.key === KEY);
    ctx.check('the server is staged', row?.staged === true, row?.staged);
    ctx.check('the staged secret shows as "(staged)" in the state', JSON.stringify(row?.value ?? '').includes('(staged)'), row?.value);
    const ap = await call('dev.hub.action', { action: 'settings_apply' });
    let note = ap.note ?? ap.result?.message ?? '';
    if (/confirm needed|Confirm and apply/i.test(`${ap.note ?? ''} ${ap.settingsTab?.form?.note ?? ''}`)) {
      const c = await call('dev.hub.action', { action: 'settings_confirm' });
      note = c.note ?? note;
    }
    const applied = await pollUntil(async () => {
      const s = (await call('dev.hub.state')).settingsTab;
      const r = s.form.rows.find((x) => x.key === KEY);
      return r && !r.staged && JSON.stringify(r.current ?? r.value).includes(name) ? { s, r } : null;
    }, { timeoutMs: 15_000 });
    ctx.require('applied: the Foreman lists the server', !!applied, note);
    const server = (applied.r.current ?? applied.r.value).find?.((x) => x.name === name);
    ctx.check(`the Foreman shows the variable name only (envKeys ${ENV})`, JSON.stringify(server?.envKeys ?? []) === JSON.stringify([ENV]), server);
    ctx.check('the change waits for a restart', (applied.s.config?.restartRequired ?? []).includes(KEY), applied.s.config?.restartRequired);
    // reload from the Foreman: still there, still names only
    await call('dev.hub.action', { action: 'settings_reload' });
    await sleep(800);
    st = (await call('dev.hub.state')).settingsTab;
    const again = (st.form.rows.find((r) => r.key === KEY)?.current ?? []).find?.((x) => x.name === name);
    ctx.check('after a reload the server is still listed with its variable name', JSON.stringify(again?.envKeys ?? []) === JSON.stringify([ENV]), again);
    await scrollTo();
    await shot(ctx, 'settings_mcp_applied', { waitChunks: false });
    // positive: the value reached its store (the Foreman's config.json)
    if (home) {
      const cfg = JSON.parse(fs.readFileSync(path.join(home, 'config.json'), 'utf8'));
      // config.json keeps the servers as Claude's map by name ({name: {command, args, env}}); a list is read too
      const servers = cfg?.claude?.context?.mcpServers ?? {};
      const stored = Array.isArray(servers) ? servers.find((x) => x.name === name) : servers[name];
      ctx.check('the secret is stored in the Foreman config (config.json)', stored?.env?.[ENV] === secret, stored ? Object.keys(stored.env ?? {}) : 'no server');
    } else ctx.note('--attach without --home: the store is not checked');
    // negative: never in the client's state, the HUD, the Inbox or a log
    const views = { hub: await call('dev.hub.state'), state: await call('dev.state'), inbox: await call('dev.inbox.state'), hud: await call('dev.hud.state'), journal: await call('dev.journal.state') };
    for (const [k, v] of Object.entries(views)) ctx.check(`the secret is not in dev.${k === 'state' ? 'state' : `${k}.state`}`, findSecret(v, secret).length === 0, findSecret(v, secret));
    const logs = [path.join(root, 'artifacts', 'logs'), path.join(root, 'mod', 'run', 'logs')];
    if (home) logs.push(home);
    const scan = scanFiles(logs, secret, { skip: home ? [path.join(home, 'config.json'), path.join(home, 'config.json.bak')] : [] });
    ctx.check(`the secret is in no log or state file (${scan.scanned} files scanned)`, scan.hits.length === 0, scan.hits);
    W.secretCheck = { secret, scanned: scan.scanned };
    // clean up: remove the server again
    await scrollTo();
    await press(`settings:${KEY}:remove:${name}`).catch((e) => ctx.note(`remove chip: ${e.message}`));
    await call('dev.hub.action', { action: 'settings_apply', confirm: true }).catch(() => {});
    await closeScreens();
  });

  // ---- layout at 426x240, toast priority ------------------------------------------------------------------------------------------
  await runner.step('layout_426x240', { needs: ['preflight'], timeoutMs: 150_000 }, async (ctx) => {
    await vantage();
    await call('dev.window', { width: 1278, height: 720 });
    const sc = await call('dev.review.guiScale', { scale: 0 });
    ctx.require('426x240 GUI px', sc.guiWidth === 426 && sc.guiHeight === 240, sc);
    try {
      const problems = [];
      const tabs = [['inbox', {}], ['buildings', {}], ['buildings', { sub: 'roads' }], ['buildings', { sub: 'fixtures' }], ['repos', {}], ['goals', {}], ['team', {}], ['settings', { group: 'general' }], ['settings', { group: 'context' }], ['status', {}]];
      for (const [tab, extra] of tabs) {
        await call('dev.hub.open', { tab, ...extra });
        await sleep(250);
        const h = await call('dev.hub.state');
        const label = `${tab}${extra.sub ? `/${extra.sub}` : ''}`;
        const layouts = { tabs: h.tabs, inbox: h.inboxTab?.layout, repos: h.reposTab?.layout, goals: h.goalsTab?.layout, team: h.teamTab?.layout, settings: h.settingsTab?.layout, status: h.statusTab?.layout };
        for (const [k, l] of Object.entries(layouts)) {
          const p = layoutProblem(`${label}: ${k}`, l);
          if (p) problems.push(p);
        }
        if (extra.sub === 'roads') {
          const r = await call('dev.roads.state');
          if (r.ui?.overflow) problems.push(`${label}: roads pane overflow`);
          if (r.ui?.strip?.overflow) problems.push(`${label}: list strip overflow`);
        }
        const shotLabel = extra.sub ? `${label.replace('/', '_')}` : extra.group ? `${tab}_${extra.group}` : label;
        await shot(ctx, `small_${shotLabel}`, { waitChunks: false, frames: 2 });
      }
      await closeScreens();
      const walk = await call('dev.walk.state');
      if (walk.ui?.overflow) problems.push('walk toggle row overflow');
      // toast priority: info toasts first, then one that needs the player; it must show first, the rest as "+N more"
      for (let i = 1; i <= 3; i++) await call('dev.toast', { text: `Info ${i}: a step finished`, level: 'info' });
      await call('dev.toast', { text: 'Marlow needs you: merge t3?', level: 'need_user' });
      await call('dev.toast', { text: 'Info 4: another step', level: 'info' });
      await sleep(300);
      const hud = await call('dev.hud.state');
      const shown = hud.toasts?.shown ?? [];
      ctx.check('the needs-you toast shows first', shown[0]?.level === 'need_user', shown);
      ctx.check('stacked toasts collapse into one "+N more" line', /\+\d+ more/.test(hud.toasts?.moreLine ?? ''), hud.toasts);
      const al = layoutProblem('HUD alert line', hud.alert?.layout);
      if (al) problems.push(al);
      await shot(ctx, 'small_toasts_hud', { hud: true, waitChunks: false, frames: 2 });
      ctx.check('no layout overflows at 426x240', problems.length === 0, problems);
    } finally {
      await call('dev.window', { width: 1920, height: 1080 });
      await call('dev.review.guiScale', { scale: 3 });
    }
  });

  // ---- HUD overlay styles at 426x240: every style and position clear of boss bars, effects, hotbar, chat --------------
  await runner.step('hud_styles_426x240', { needs: ['preflight'], timeoutMs: 180_000 }, async (ctx) => {
    await closeScreens();
    await vantage();
    const before = (await call('dev.hud.state')).overlay?.settings;
    ctx.require('dev.hud.state reports the overlay settings', !!before, before);
    await call('dev.window', { width: 1278, height: 720 });
    const sc = await call('dev.review.guiScale', { scale: 0 });
    ctx.require('426x240 GUI px', sc.guiWidth === 426 && sc.guiHeight === 240, sc);
    try {
      for (const cmd of SCENE) {
        const r = await call('dev.command', { cmd });
        ctx.check(`/${cmd}`, r.success !== false, r.messages);
      }
      // auto-hide off so every combination must show (no empty rectangle passes for free), no peeks, no chat lines
      await call('dev.hud.set', { autoHide: false, peek: false, hideInCombat: false, clearPeek: true, clearChat: true });
      await sleep(400);
      const scene = await call('dev.hud.state');
      ctx.require('the scene shows two boss bars and both effect rows', sceneProblems(scene).length === 0, sceneProblems(scene));
      const problems = [];
      let checked = 0;
      for (const style of STYLES) {
        for (const position of POSITIONS) {
          for (const size of SIZES) {
            await call('dev.hud.set', { style, position, size });
            await sleep(120);
            const h = await call('dev.hud.state');
            problems.push(...hudProblems(`${style}/${position}/${size}`, h));
            checked++;
            if (size === 'm') await shot(ctx, `hud_${style}_${position}`, { hud: true, waitChunks: false, frames: 2 });
          }
        }
      }
      ctx.data('combinations', checked);
      ctx.check(`all ${checked} style/position/size combinations show, clear of everything`, problems.length === 0, problems);
      // a peek widens the pill for a few seconds
      await call('dev.hud.set', { style: 'pill', position: 'top_right', size: 'm' });
      await sleep(150);
      const narrow = (await call('dev.hud.state')).rect;
      const peek = await call('dev.hud.peek', { text: 'Done: Parse the tag list', kind: 'task_done' });
      await sleep(150);
      const wide = await call('dev.hud.state');
      ctx.check('a peek shows', wide.peek?.active === true && wide.peek?.text === 'Done: Parse the tag list', peek.peek);
      ctx.check('the peek widens the pill', (wide.rect?.w ?? 0) > (narrow?.w ?? 0), { narrow, wide: wide.rect });
      ctx.check('the peeking pill stays clear', hudProblems('pill peek', wide).length === 0, hudProblems('pill peek', wide));
      await shot(ctx, 'hud_pill_peek', { hud: true, waitChunks: false, frames: 2 });
      // Off draws nothing; F1 hides everything
      const off = await call('dev.hud.set', { style: 'off', clearPeek: true });
      ctx.check('Off: hidden, no rectangle', off.hidden === 'off' && !off.rect, { hidden: off.hidden, rect: off.rect });
      await call('dev.hud.set', { style: 'pill' });
      await call('dev.hud', { hidden: true });
      await sleep(150);
      const f1 = await call('dev.hud.state');
      ctx.check('F1 hides the overlay', f1.hidden === 'f1' && !f1.rect, { hidden: f1.hidden });
      await call('dev.hud', { hidden: false });
    } finally {
      await call('dev.hud', { hidden: false }).catch(() => {});
      await call('dev.hud.set', { ...restorePayload(before), clearPeek: true }).catch(() => {});
      for (const cmd of SCENE_UNDO) await call('dev.command', { cmd }).catch(() => {});
      await call('dev.window', { width: 1920, height: 1080 });
      await call('dev.review.guiScale', { scale: 3 });
    }
  });

  // ---- placement HUD: too far, text depth at stations ---------------------------------------------------------------------------
  await runner.step('placement_too_far', { needs: ['place_buildings'], timeoutMs: 60_000 }, async (ctx) => {
    await closeScreens();
    const a = W.A.box;
    // look at the ground in reach, then at the sky: the ghost stays where it was, with the "too far" note
    await camera(a.x1 + 12, a.y0 + 8, a.z1 + 14, { x: a.x1 + 12, y: a.y0, z: a.z1 + 24 }, 'creative');
    await call('dev.build.start', { blueprint: 'village_board', repos: [] });
    await sleep(400);
    const s1 = await call('dev.build.state');
    ctx.check('aiming in reach: not too far', s1.tooFar === false, s1.tooFar);
    await camera(a.x1 + 12, a.y0 + 8, a.z1 + 14, { x: a.x1 + 40, y: a.y0 + 120, z: a.z1 + 200 }, 'creative');
    await sleep(400);
    const s2 = await call('dev.build.state');
    ctx.check('aiming at the sky: too far', s2.tooFar === true, s2.tooFar);
    ctx.check('the ghost stays at the last spot in reach', JSON.stringify(s2.origin) === JSON.stringify(s1.origin), { before: s1.origin, after: s2.origin });
    await shot(ctx, 'placement_too_far_hud', { hud: true, chunkRadius: 4 });
    await call('dev.build.cancel');
  });

  await runner.step('text_depth_closeups', { needs: ['place_buildings'], timeoutMs: 90_000 }, async (ctx) => {
    await closeScreens();
    const an = (await call('dev.anchors')).anchors ?? {};
    const yawVec = (yaw) => [-Math.sin((yaw * Math.PI) / 180), Math.cos((yaw * Math.PI) / 180)];
    const close = async (anchor, dist, up, name) => {
      const p = an[anchor];
      if (!p) { ctx.note(`no ${anchor} anchor`); return; }
      const [fx, fz] = yawVec(p.yaw);
      // the anchor faces out of its block: stand in front of it, a little to the side, looking at its face
      await camera(p.x + fx * dist + fz * 0.6, p.y + up + 0.3, p.z + fz * dist - fx * 0.6, { x: p.x, y: p.y + up, z: p.z });
      await sleep(600);
      await shot(ctx, name, { chunkRadius: 3 });
    };
    await close('monitor_kit', 1.6, 0.4, 'text_depth_monitor');
    await close('task_wall', 2.2, 0.2, 'text_depth_task_board');
    const disp = await call('dev.displays');
    ctx.check('monitors were laid out', (disp.monitors ?? []).length > 0, (disp.monitors ?? []).length);
    const tw = await call('dev.taskwall');
    ctx.check('the task board was laid out', (tw.boards ?? []).length > 0);
    // the goal lamp's paper card (progress ring and text) over the atrium
    await close('goal_atrium', 5.5, 2.6, 'text_depth_goal_lamp');
    await vantage();
  });
}

let exitCode = 0;
try {
  await main();
} catch (e) {
  log(`run error: ${e.stack ?? e}`);
  exitCode = 2;
} finally {
  if (dev) {
    await call('dev.window', { width: 1920, height: 1080 }).catch(() => {});
    await call('dev.release', { mode: 'creative' }).catch(() => {});
  }
  const report = runner.report({
    world: opt.world, seed: opt.seed, speed: opt.speed, attach: !!opt.attach, devPort: opt['dev-port'], foremanPort: opt.port,
    buildings: Object.fromEntries(Object.entries(W.buildings ?? {}).map(([id, b]) => [id, { blueprint: b.blueprint, repos: b.repos, box: boxText(b.box), snapshotBox: boxText(b.snapshotBox) }])),
    goals: { main: W.goalMain, pr: W.goalPr }, pr: W.prNumber,
    shots: shots.map((s) => ({ name: s.name, path: path.relative(root, s.path), step: s.step })),
  });
  const sheet = path.join(outDir, 'contact-sheet.png');
  try {
    const status = (s) => runner.status(s.step) === 'ok' ? 'ok' : runner.status(s.step) === 'skipped' ? 'skipped' : 'failed';
    const tiles = shots.map((s) => ({ name: s.name, path: s.path, status: status(s), note: s.step }));
    for (const st of report.steps.filter((x) => x.status !== 'ok')) tiles.push({ name: st.name, status: st.status === 'skipped' ? 'skipped' : 'failed', title: st.name, note: st.error ?? '' });
    contactSheet({ out: sheet, title: `AgentCraft smoke ${new Date(t0).toISOString().slice(0, 16)} ${report.ok ? 'PASSED' : 'FAILED'}`, subtitle: `${report.counts.ok} passed, ${report.counts.failed} failed, ${report.counts.skipped} skipped in ${Math.round(report.durationMs / 1000)} s`, tiles, columns: 4, thumbWidth: 480 });
    report.contactSheet = path.relative(root, sheet);
  } catch (e) {
    log(`contact sheet: ${e.message}`);
  }
  const text = redact(JSON.stringify(report, null, 2), secrets);
  fs.writeFileSync(path.join(outDir, 'report.json'), text);
  if (secrets.some((s) => text.includes(s))) log('BUG: the report still holds the secret');
  for (const line of summaryLines(report)) log(line);
  log(`report: ${path.relative(root, path.join(outDir, 'report.json'))}; contact sheet: ${report.contactSheet ?? 'none'}`);
  dev?.close();
  await cleanup();
  if (!exitCode && !report.ok) exitCode = 1;
  process.exit(exitCode);
}
