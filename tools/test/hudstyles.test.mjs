// node --test tools/test   (or: npm test --prefix tools)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extrasProblems, hudProblems, rectsOverlap, restorePayload, sceneProblems, STYLES, POSITIONS, SIZES } from '../lib/hudstyles.mjs';

const env = {
  bossRect: { x: 122, y: 0, w: 182, h: 36 }, effectsRect: { x: 376, y: 1, w: 50, h: 50 }, pillRect: { x: 330, y: 54, w: 92, h: 18 },
  hotbarRect: { x: 93, y: 178, w: 240, h: 62 }, bossBarsDrawn: 2, beneficialEffects: 1, harmfulEffects: 1,
};
const good = {
  style: 'pill', position: 'top_right', rect: { x: 326, y: 75, w: 96, h: 17 }, hidden: null,
  overlaps: { bossbar: false, effects: false, hotbar: false, chat: false, pill: false, minimap: false, offscreen: false, any: false },
  overlay: { fallback: false, placedAt: 'top_right', env, toastColumn: { x: 226, top: 95, bottom: 175, up: false } }, toasts: { w: 196 },
};

test('rectsOverlap', () => {
  assert.equal(rectsOverlap({ x: 0, y: 0, w: 10, h: 10 }, { x: 5, y: 5, w: 10, h: 10 }), true);
  assert.equal(rectsOverlap({ x: 0, y: 0, w: 10, h: 10 }, { x: 10, y: 0, w: 10, h: 10 }), false, 'touching');
  assert.equal(rectsOverlap(null, { x: 0, y: 0, w: 1, h: 1 }), false);
  assert.equal(rectsOverlap({ x: 0, y: 0, w: 0, h: 10 }, { x: 0, y: 0, w: 10, h: 10 }), false);
});

test('a clear overlay has no problems', () => {
  assert.deepEqual(hudProblems('pill', good), []);
});

test('hidden, overlapping, fallen back or a toast column over the boss bars are problems', () => {
  assert.match(hudProblems('x', { ...good, hidden: 'no_room', rect: null }).join(), /hidden \(no_room\)/);
  assert.match(hudProblems('x', { ...good, rect: null }).join(), /no rectangle/);
  assert.match(hudProblems('x', { ...good, overlaps: { ...good.overlaps, bossbar: true } }).join(), /overlaps bossbar/);
  assert.match(hudProblems('x', { ...good, overlay: { ...good.overlay, fallback: true } }).join(), /fell back/);
  assert.deepEqual(hudProblems('x', { ...good, overlay: { ...good.overlay, fallback: true } }, { allowFallback: true }), []);
  const col = { ...good.overlay, toastColumn: { x: 226, top: 20, bottom: 175, up: false } };
  assert.match(hudProblems('x', { ...good, overlay: col }).join(), /toast column .* overlaps bossbar/);
  assert.deepEqual(hudProblems('x', null), ['x: no state']);
});

test('the scene must show two boss bars and both effect rows', () => {
  assert.deepEqual(sceneProblems(good), []);
  assert.equal(sceneProblems({ overlay: { env: { ...env, bossBarsDrawn: 1 } } }).length, 1);
  assert.equal(sceneProblems({ overlay: { env: { ...env, harmfulEffects: 0 } } }).length, 1);
  assert.equal(sceneProblems({}).length, 1);
});

test('overlapping the sidebar, subtitles, a vanilla toast or the auth banner is a problem, and so is a toast column over them', () => {
  for (const k of ['sidebar', 'subtitles', 'toasts', 'banner']) {
    assert.match(hudProblems('x', { ...good, overlaps: { ...good.overlaps, [k]: true } }).join(), new RegExp(`overlaps ${k}`));
  }
  const side = { ...good.overlay, env: { ...env, sidebarRect: { x: 371, y: 92, w: 54, h: 37 } } };
  assert.match(hudProblems('x', { ...good, overlay: side }).join(), /toast column .* overlaps sidebar/);
  const above = { ...side, toastColumn: { x: 226, top: 95, bottom: 89, up: false } };
  assert.deepEqual(hudProblems('x', { ...good, overlay: above }), [], 'a column that stops above the sidebar');
});

test('the extras pass must show the sidebar, subtitles, a vanilla toast and a clear auth banner', () => {
  const full = {
    ...env, sidebarRect: { x: 371, y: 92, w: 54, h: 37 }, subtitleRowsRect: { x: 330, y: 180, w: 95, h: 30 },
    vanillaToastsRect: { x: 266, y: 0, w: 160, h: 32 }, bannerRect: { x: 98, y: 39, w: 230, h: 40 },
  };
  assert.deepEqual(extrasProblems({ overlay: { env: full } }), []);
  assert.deepEqual(extrasProblems({ overlay: { env: { ...full, bannerRect: null } } }), ['no auth banner on screen']);
  assert.deepEqual(extrasProblems({ overlay: { env: { ...full, bannerRect: null } } }, { banner: false }), []);
  assert.equal(extrasProblems({ overlay: { env: { ...full, sidebarRect: null, subtitleRowsRect: null } } }).length, 2);
  assert.match(extrasProblems({ overlay: { env: { ...full, bannerRect: { x: 98, y: 10, w: 230, h: 40 } } } }).join(), /overlaps bossbar/);
  assert.match(extrasProblems({ overlay: { env: { ...full, bannerRect: { x: 200, y: 39, w: 230, h: 40 } } } }).join(), /overlaps pill/);
  assert.deepEqual(extrasProblems({}), ['no overlay.env in dev.hud.state']);
});

test('restorePayload keeps only the settings dev.hud.set takes', () => {
  assert.deepEqual(restorePayload({ version: 1, style: 'pill', position: 'top_right', size: 'm', peek: true, autoHide: true, hideInCombat: false, toasts: 'needs_you', topLeftOffset: 72 }),
    { style: 'pill', position: 'top_right', size: 'm', peek: true, autoHide: true, hideInCombat: false, toasts: 'needs_you', topLeftOffset: 72 });
  assert.deepEqual(restorePayload(null), {});
  assert.equal(STYLES.length * POSITIONS.length * SIZES.length, 45);
});
