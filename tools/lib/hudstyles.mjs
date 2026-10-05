// HUD overlay styles QA (mod: hub Settings > General > HUD, dev.hud.set / dev.hud.state): the scene that crowds the
// screen (two boss bars, a beneficial and a harmful effect), the style/position/size matrix, and the pure checks of a
// dev.hud.state reply. Used by smoke.mjs (step hud_styles_426x240) and hud-shots.mjs (the hud-styles screenshots).

export const STYLES = ['pill', 'pill_plus', 'panel'];
export const POSITIONS = ['top_right', 'top_left', 'bottom_left', 'bottom_right', 'right_middle'];
export const SIZES = ['s', 'm', 'l'];

/** Commands that put two boss bars (one with a long title) and both effect rows on screen; undone by SCENE_UNDO. */
export const SCENE = [
  'bossbar add agentcraft_worlds:qa_boss_a "Ender Dragon"',
  'bossbar set agentcraft_worlds:qa_boss_a players @a',
  'bossbar set agentcraft_worlds:qa_boss_a value 70',
  'bossbar add agentcraft_worlds:qa_boss_b "Raid - Wave 3 of 7: Pillagers and Vindicators"',
  'bossbar set agentcraft_worlds:qa_boss_b players @a',
  'bossbar set agentcraft_worlds:qa_boss_b color red',
  'effect give @s minecraft:speed 600 0',
  'effect give @s minecraft:weakness 600 0',
];
export const SCENE_UNDO = ['bossbar remove agentcraft_worlds:qa_boss_a', 'bossbar remove agentcraft_worlds:qa_boss_b', 'effect clear @s'];

/** Axis-aligned overlap of two {x, y, w, h} rectangles (touching edges do not overlap); null/empty never overlaps. */
export function rectsOverlap(a, b) {
  if (!a || !b || a.w <= 0 || a.h <= 0 || b.w <= 0 || b.h <= 0) return false;
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

/**
 * Problems of one dev.hud.state reply for a style that must show (auto-hide off): hidden, no rectangle, any overlap
 * (boss bars, effect icons, hotbar, chat, the connection pill, minimap room, off screen), a fallback position, and the
 * toast column running into the boss bars, effect icons, the pill or the hotbar. [] = fine.
 */
export function hudProblems(label, h, { allowFallback = false } = {}) {
  const out = [];
  if (!h || typeof h !== 'object') return [`${label}: no state`];
  if (h.hidden) out.push(`${label}: hidden (${h.hidden})`);
  if (!h.rect) {
    if (!h.hidden) out.push(`${label}: no rectangle`);
    return out;
  }
  if (h.rect.w <= 0 || h.rect.h <= 0) out.push(`${label}: empty rectangle`);
  const o = h.overlaps ?? {};
  for (const k of ['bossbar', 'effects', 'hotbar', 'chat', 'pill', 'minimap', 'offscreen']) {
    if (o[k]) out.push(`${label}: overlaps ${k} (rect ${JSON.stringify(h.rect)})`);
  }
  if (!allowFallback && h.overlay?.fallback) out.push(`${label}: no room at ${h.position}, fell back to ${h.overlay.placedAt}`);
  const col = h.overlay?.toastColumn;
  const env = h.overlay?.env;
  if (col && env && col.bottom > col.top) {
    const probe = { x: col.x, y: col.top, w: h.toasts?.w ?? 196, h: col.bottom - col.top };
    for (const [k, r] of [['bossbar', env.bossRect], ['effects', env.effectsRect], ['pill', env.pillRect], ['hotbar', env.hotbarRect]]) {
      if (rectsOverlap(probe, r)) out.push(`${label}: toast column ${JSON.stringify(probe)} overlaps ${k} ${JSON.stringify(r)}`);
    }
    if (rectsOverlap(probe, h.rect)) out.push(`${label}: toast column overlaps the overlay`);
  }
  return out;
}

/** What the scene must have put on screen for the checks to mean anything. */
export function sceneProblems(h) {
  const env = h?.overlay?.env;
  if (!env) return ['no overlay.env in dev.hud.state'];
  const out = [];
  if ((env.bossBarsDrawn ?? 0) < 2) out.push(`boss bars drawn: ${env.bossBarsDrawn} (want 2)`);
  if ((env.beneficialEffects ?? 0) < 1 || (env.harmfulEffects ?? 0) < 1) out.push(`effects: ${env.beneficialEffects} beneficial, ${env.harmfulEffects} harmful (want both rows)`);
  return out;
}

/** The dev.hud.set payload that restores the settings found in dev.hud.state overlay.settings. */
export function restorePayload(settings) {
  if (!settings) return {};
  const keys = ['style', 'position', 'size', 'peek', 'autoHide', 'hideInCombat', 'toasts', 'topLeftOffset'];
  return Object.fromEntries(keys.filter((k) => settings[k] !== undefined).map((k) => [k, settings[k]]));
}
