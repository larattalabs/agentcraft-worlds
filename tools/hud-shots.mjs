#!/usr/bin/env node
// HUD overlay style screenshots: attaches to a running dev client (in a world, Foreman connected), crowds the screen
// (two boss bars, a beneficial and a harmful effect: lib/hudstyles.mjs SCENE) and shoots every style at the 4K-auto
// equivalent (window 1278x720 at GUI scale auto = 426x240 GUI px) and at GUI scale 3 (1920x1080), each position of the
// default Pill, the sizes, and the hub's Settings > General > HUD section with its preview. Checks every shot's state
// (shown, no overlaps) and restores the settings, the scene and the window afterwards.
//
//   node tools/hud-shots.mjs [--dev-port 7979] [--out hud-styles]
//
// Output: artifacts/shots/<out>/ (PNGs and report.json). Exit 1 when a check failed.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DevClient } from './lib/devclient.mjs';
import { POSITIONS, SCENE, SCENE_UNDO, hudProblems, restorePayload, sceneProblems } from './lib/hudstyles.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const opt = { 'dev-port': undefined, out: 'hud-styles' };
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--dev-port' || args[i] === '--out') opt[args[i].slice(2)] = args[++i];
  else if (args[i] === '--help' || args[i] === '-h') {
    console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 10).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'));
    process.exit(0);
  } else {
    console.error(`unknown option ${args[i]}`);
    process.exit(2);
  }
}
if (!/^[\w-]+$/.test(opt.out)) {
  console.error('invalid --out');
  process.exit(2);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const dev = await DevClient.connect({ port: opt['dev-port'] ? Number(opt['dev-port']) : undefined, timeoutMs: 30_000 });
const call = (t, p = {}) => dev.call(t, p);
const report = { shots: [], problems: [] };

async function shot(name, label) {
  const h = await call('dev.hud.state');
  const probs = label ? hudProblems(label, h, { allowFallback: h.style !== 'pill' }) : [];
  report.problems.push(...probs);
  const s = await call('dev.screenshot', { name: `${opt.out}/${name}`, hideHud: false, waitChunks: false, frames: 3 });
  report.shots.push({ name, path: path.relative(root, s.path), rect: h.rect, hidden: h.hidden, style: h.style, position: h.position, size: h.size, problems: probs });
  console.log(`${name}: ${h.style}/${h.position}/${h.size} rect ${JSON.stringify(h.rect)}${probs.length ? ` PROBLEMS ${probs.join('; ')}` : ''}`);
}

const before = (await call('dev.hud.state')).overlay?.settings;
try {
  await call('dev.screen', { open: null }).catch(() => {});
  for (const cmd of SCENE) await call('dev.command', { cmd });
  await call('dev.hud.set', { autoHide: false, peek: false, clearPeek: true, clearChat: true });
  for (const [scale, win, gui] of [['auto', { width: 1278, height: 720 }, 0], ['s3', { width: 1920, height: 1080 }, 3]]) {
    await call('dev.window', win);
    await call('dev.review.guiScale', { scale: gui });
    await call('dev.hud.set', { clearChat: true });
    await sleep(500);
    const scene = sceneProblems(await call('dev.hud.state'));
    if (scene.length) report.problems.push(...scene.map((p) => `${scale}: ${p}`));
    for (const style of ['pill', 'pill_plus', 'panel']) {
      await call('dev.hud.set', { style, position: 'top_right', size: 'm' });
      await sleep(200);
      await shot(`${scale}_${style}_top_right`, `${scale} ${style}`);
    }
    await call('dev.hud.set', { style: 'off' });
    await sleep(200);
    await shot(`${scale}_off`, null);
    for (const position of POSITIONS.filter((p) => p !== 'top_right')) {
      for (const style of ['pill', 'pill_plus']) {
        await call('dev.hud.set', { style, position, size: 'm' });
        await sleep(200);
        await shot(`${scale}_${style}_${position}`, `${scale} ${style} ${position}`);
      }
    }
    for (const size of ['s', 'l']) {
      await call('dev.hud.set', { style: 'pill_plus', position: 'top_right', size });
      await sleep(200);
      await shot(`${scale}_pill_plus_size_${size}`, `${scale} pill_plus ${size}`);
    }
    await call('dev.hud.set', { style: 'pill', position: 'top_right', size: 'm' });
    await call('dev.hud.peek', { text: 'PR merged: Parse the tag list', kind: 'pr_merged' });
    await sleep(200);
    await shot(`${scale}_pill_peek`, `${scale} pill peek`);
    await call('dev.hud.set', { clearPeek: true });
    await call('dev.hub.open', { tab: 'settings', group: 'general' });
    await sleep(400);
    await shot(`${scale}_settings_general_hud`, null);
    await call('dev.screen', { open: null }).catch(() => {});
  }
} finally {
  await call('dev.screen', { open: null }).catch(() => {});
  await call('dev.hud.set', { ...restorePayload(before), clearPeek: true }).catch(() => {});
  for (const cmd of SCENE_UNDO) await call('dev.command', { cmd }).catch(() => {});
  await call('dev.window', { width: 1920, height: 1080 }).catch(() => {});
  await call('dev.review.guiScale', { scale: 3 }).catch(() => {});
  const dir = path.join(root, 'artifacts', 'shots', opt.out);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'report.json'), JSON.stringify(report, null, 2));
  console.log(`${report.shots.length} shots, ${report.problems.length} problems -> ${path.relative(root, dir)}`);
  dev.close();
}
process.exit(report.problems.length ? 1 : 0);
