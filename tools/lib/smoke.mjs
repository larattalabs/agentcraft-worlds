// Pure helpers for the end-to-end smoke test (tools/smoke.mjs): boxes, block-dump diffs, the step runner and its
// report, secret redaction and leak scans. No game or network here: tested in tools/test/smoke.test.mjs.

import fs from 'node:fs';
import path from 'node:path';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- boxes and block dumps ----------------------------------------------------------------------

/** "x,y,z .. x,y,z" (as dev.build.state / dev.hub.state print boxes) -> {x0,y0,z0,x1,y1,z1}, or null. */
export function parseBox(text) {
  const m = String(text ?? '').match(/^\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\s*\.\.\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\s*$/);
  if (!m) return null;
  const [x0, y0, z0, x1, y1, z1] = m.slice(1).map(Number);
  return { x0: Math.min(x0, x1), y0: Math.min(y0, y1), z0: Math.min(z0, z1), x1: Math.max(x0, x1), y1: Math.max(y0, y1), z1: Math.max(z0, z1) };
}

/** The box grown by `pad` blocks sideways and `padY` (default pad) up and down. */
export function growBox(b, pad, padY = pad) {
  return { x0: b.x0 - pad, y0: b.y0 - padY, z0: b.z0 - pad, x1: b.x1 + pad, y1: b.y1 + padY, z1: b.z1 + pad };
}

export const boxCells = (b) => (b.x1 - b.x0 + 1) * (b.y1 - b.y0 + 1) * (b.z1 - b.z0 + 1);
export const boxText = (b) => `${b.x0},${b.y0},${b.z0} .. ${b.x1},${b.y1},${b.z1}`;
export const boxCentre = (b) => ({ x: (b.x0 + b.x1 + 1) / 2, y: (b.y0 + b.y1 + 1) / 2, z: (b.z0 + b.z1 + 1) / 2 });
export const boxesOverlap = (a, b) => a.x0 <= b.x1 && b.x0 <= a.x1 && a.y0 <= b.y1 && b.y0 <= a.y1 && a.z0 <= b.z1 && b.z0 <= a.z1;

/** A target-selector clause for that box (`x=..,y=..,z=..,dx=..,dy=..,dz=..`), e.g. for `kill @e[type=!player,<sel>]`. */
export function selector(b) {
  return `x=${b.x0},y=${b.y0},z=${b.z0},dx=${b.x1 - b.x0},dy=${b.y1 - b.y0},dz=${b.z1 - b.z0}`;
}

/** The world cell of index i in a dev.roads.blocks dump (x fastest, then z, then y). */
export function cellAt(box, i) {
  const nx = box.x1 - box.x0 + 1;
  const nz = box.z1 - box.z0 + 1;
  return [box.x0 + (i % nx), box.y0 + Math.floor(i / (nx * nz)), box.z0 + (Math.floor(i / nx) % nz)];
}

const LEAF = /^Block\{minecraft:[a-z_]*leaves\}/;

/**
 * Whether two block states differ only in a leaf block's `distance` (vanilla recomputes it from the logs around a
 * leaf whenever a neighbour changes; world generation leaves stale values that the first neighbour update fixes), so a
 * restored cell can come back with a corrected distance although the restore wrote the exact old state.
 */
export function leafDistanceOnly(a, b) {
  if (!LEAF.test(a) || !LEAF.test(b)) return false;
  return a.replace(/distance=\d+/, '') === b.replace(/distance=\d+/, '');
}

const FLOWING = /^Block\{minecraft:(water|lava)\}\[level=([1-9]|1[0-5])\]$/;
const AIR_STATE = /^Block\{minecraft:(cave_|void_)?air\}$/;

/**
 * Whether two states differ only by flowing fluid (a cell going between air and flowing water/lava, or between flow
 * levels): world generation leaves fluids unsettled in caves and on slopes, and the block updates of a restore let
 * them flow. A source block (level=0) never counts here.
 */
export function flowOnly(a, b) {
  const fa = FLOWING.test(a);
  const fb = FLOWING.test(b);
  return (fa && fb) || (fa && AIR_STATE.test(b)) || (fb && AIR_STATE.test(a));
}

/**
 * Whether two states are the same AgentCraft station block whose live properties differ (a monitor's `lit`, a lamp's
 * `status`): the game sets those from the Foreman's state, they are not part of what a restore puts back.
 */
export function liveStateOnly(a, b) {
  const ida = a.match(/^Block\{(agentcraft:[a-z_]+)\}/)?.[1];
  return !!ida && ida === b.match(/^Block\{(agentcraft:[a-z_]+)\}/)?.[1];
}

/**
 * Compares two dev.roads.blocks dumps of the same box by block state (palettes are per dump, so indices never are
 * compared). Returns {cells, differ, leafDistance, flow, live, diffs: [{at:[x,y,z], before, after}] (the first `max`),
 * settled: the first `max` of the differences counted apart}. `differ` counts every real difference; leaf-distance-only,
 * flowing-fluid-only and station-live-state-only differences are counted apart (leafDistanceOnly, flowOnly,
 * liveStateOnly).
 */
export function diffDumps(a, b, { max = 12 } = {}) {
  if (!a?.box || !b?.box) throw new Error('diffDumps: missing dump');
  const ab = typeof a.box === 'string' ? parseBox(a.box) : a.box;
  const bb = typeof b.box === 'string' ? parseBox(b.box) : b.box;
  if (boxText(ab) !== boxText(bb)) throw new Error(`diffDumps: different boxes ${boxText(ab)} vs ${boxText(bb)}`);
  if (a.cells.length !== b.cells.length) throw new Error(`diffDumps: ${a.cells.length} vs ${b.cells.length} cells`);
  let differ = 0;
  let leafDistance = 0;
  let flow = 0;
  let live = 0;
  const diffs = [];
  const settled = [];
  for (let i = 0; i < a.cells.length; i++) {
    const sa = a.palette[a.cells[i]];
    const sb = b.palette[b.cells[i]];
    if (sa === sb) continue;
    const kind = leafDistanceOnly(sa, sb) ? 'leafDistance' : flowOnly(sa, sb) ? 'flow' : liveStateOnly(sa, sb) ? 'live' : null;
    if (kind) {
      if (kind === 'leafDistance') leafDistance++;
      else if (kind === 'flow') flow++;
      else live++;
      if (settled.length < max) settled.push({ at: cellAt(ab, i), kind, before: sa, after: sb });
      continue;
    }
    differ++;
    if (diffs.length < max) diffs.push({ at: cellAt(ab, i), before: sa, after: sb });
  }
  return { cells: a.cells.length, differ, leafDistance, flow, live, diffs, settled };
}

/** Counts the cells of a dump whose state matches `re` (e.g. /agentcraft:/). */
export function countStates(dump, re) {
  let n = 0;
  for (const c of dump.cells) if (re.test(dump.palette[c])) n++;
  return n;
}

// ---- secrets ----------------------------------------------------------------------------------------

/** A random secret-shaped value for the MCP editor round-trip (never a real credential). */
export function fakeSecret(rand = Math.random) {
  let s = '';
  const abc = 'abcdefghijklmnopqrstuvwxyz0123456789';
  for (let i = 0; i < 24; i++) s += abc[Math.floor(rand() * abc.length)];
  return `smoke-fake-${s}`;
}

/** Replaces every occurrence of each secret in `text` with "<redacted>". */
export function redact(text, secrets) {
  let out = String(text);
  for (const s of secrets ?? []) if (s) out = out.split(s).join('<redacted>');
  return out;
}

/** Where `secret` occurs in a JSON-able value: ["path.to.field", ...] (strings only; keys too). */
export function findSecret(value, secret, at = '$') {
  const hits = [];
  if (!secret) return hits;
  if (typeof value === 'string') {
    if (value.includes(secret)) hits.push(at);
  } else if (Array.isArray(value)) {
    value.forEach((v, i) => hits.push(...findSecret(v, secret, `${at}[${i}]`)));
  } else if (value && typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) {
      if (k.includes(secret)) hits.push(`${at}.<key>`);
      hits.push(...findSecret(v, secret, `${at}.${k}`));
    }
  }
  return hits;
}

/**
 * Text files under `dirs` (recursive, files up to maxBytes, skipping `skip` paths) that contain `secret`.
 * Returns {scanned, hits: [path]}.
 */
export function scanFiles(paths, secret, { skip = [], maxBytes = 64 * 1024 * 1024 } = {}) {
  const skipSet = new Set(skip.map((p) => path.resolve(p)));
  const hits = [];
  let scanned = 0;
  const visit = (p) => {
    const abs = path.resolve(p);
    if (skipSet.has(abs)) return;
    let st;
    try { st = fs.statSync(abs); } catch { return; }
    if (st.isDirectory()) {
      for (const name of fs.readdirSync(abs)) {
        if (name === '.git' || name === 'node_modules') continue;
        visit(path.join(abs, name));
      }
      return;
    }
    if (!st.isFile() || st.size > maxBytes) return;
    scanned++;
    if (fs.readFileSync(abs).includes(secret)) hits.push(abs);
  };
  for (const p of paths) visit(p);
  return { scanned, hits };
}

// ---- runner -------------------------------------------------------------------------------------------

export class StepFailed extends Error {}

/**
 * Runs named steps in order, each with its own timeout, records checks, notes and screenshots per step, and skips
 * steps whose prerequisites failed. `log` gets one line per event (already redacted by the caller's log function).
 */
export class SmokeRunner {
  constructor({ log = () => {}, deadlineMs = Infinity, now = () => Date.now() } = {}) {
    this.log = log;
    this.now = now;
    this.started = now();
    this.deadline = this.started + deadlineMs;
    this.steps = [];
    this.byName = new Map();
  }

  /** ok | failed | skipped | undefined (not run yet) */
  status(name) {
    return this.byName.get(name)?.status;
  }

  /**
   * Runs `fn(ctx)` as step `name` unless one of `needs` did not pass (then skipped). ctx: check(label, ok, detail?)
   * records a check and carries on; require(label, ok, detail?) records it and stops the step when false; note(text);
   * data(key, value) (into the report); shot(entry) (a screenshot record {name, path, ...}); signal (aborted on timeout).
   */
  async step(name, { needs = [], timeoutMs = 60_000 } = {}, fn) {
    const rec = { name, status: 'running', ms: 0, checks: [], notes: [], shots: [], data: {} };
    this.steps.push(rec);
    this.byName.set(name, rec);
    const missing = needs.filter((n) => this.status(n) !== 'ok');
    if (missing.length) {
      rec.status = 'skipped';
      rec.error = `needs ${missing.map((n) => `${n} (${this.status(n) ?? 'not run'})`).join(', ')}`;
      this.log(`SKIP ${name}: ${rec.error}`);
      return rec;
    }
    const left = this.deadline - this.now();
    if (left <= 0) {
      rec.status = 'skipped';
      rec.error = 'the run deadline passed';
      this.log(`SKIP ${name}: ${rec.error}`);
      return rec;
    }
    const t0 = this.now();
    this.log(`---- ${name}`);
    const ac = new AbortController();
    const ctx = {
      stepName: name,
      signal: ac.signal,
      check: (label, ok, detail) => {
        rec.checks.push({ label, ok: !!ok, ...(detail === undefined ? {} : { detail }) });
        this.log(`  ${ok ? 'ok  ' : 'FAIL'} ${label}${detail === undefined ? '' : `: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`}`);
        return !!ok;
      },
      require: (label, ok, detail) => {
        if (!ctx.check(label, ok, detail)) throw new StepFailed(`${label}${detail === undefined ? '' : `: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`}`);
        return true;
      },
      note: (text) => {
        rec.notes.push(text);
        this.log(`  note ${text}`);
      },
      data: (k, v) => {
        rec.data[k] = v;
      },
      shot: (s) => {
        rec.shots.push(s);
      },
    };
    let timer;
    try {
      const budget = Math.min(timeoutMs, left);
      await Promise.race([
        Promise.resolve().then(() => fn(ctx)),
        new Promise((_, reject) => {
          timer = setTimeout(() => {
            ac.abort();
            reject(new StepFailed(`timed out after ${Math.round(budget / 1000)} s`));
          }, budget);
        }),
      ]);
      rec.status = rec.checks.every((c) => c.ok) ? 'ok' : 'failed';
      if (rec.status === 'failed') rec.error = rec.checks.filter((c) => !c.ok).map((c) => c.label).join('; ');
    } catch (e) {
      rec.status = 'failed';
      rec.error = e instanceof StepFailed ? e.message : `${e?.message ?? e}`;
      if (!(e instanceof StepFailed) && e?.stack) rec.stack = String(e.stack).split('\n').slice(0, 6).join('\n');
    } finally {
      clearTimeout(timer);
      ac.abort();
      rec.ms = this.now() - t0;
    }
    this.log(`${rec.status === 'ok' ? 'PASS' : 'FAIL'} ${name} (${(rec.ms / 1000).toFixed(1)} s)${rec.error ? `: ${rec.error}` : ''}`);
    return rec;
  }

  /** The report: {ok, durationMs, counts{ok, failed, skipped}, steps}. */
  report(extra = {}) {
    const counts = { ok: 0, failed: 0, skipped: 0 };
    for (const s of this.steps) counts[s.status] = (counts[s.status] ?? 0) + 1;
    return { ok: counts.failed === 0 && counts.skipped === 0 && this.steps.length > 0, durationMs: this.now() - this.started, counts, ...extra, steps: this.steps };
  }
}

/** One line per step for the console: "PASS  12.3s  place_buildings". */
export function summaryLines(report) {
  return report.steps.map((s) => `${s.status === 'ok' ? 'PASS' : s.status === 'skipped' ? 'SKIP' : 'FAIL'}  ${(s.ms / 1000).toFixed(1).padStart(5)}s  ${s.name}${s.error ? `  (${s.error})` : ''}`);
}

/** Polls `fn` (async, returns a truthy value when done) every `everyMs` until `timeoutMs`; returns the value or null. */
export async function pollUntil(fn, { timeoutMs = 30_000, everyMs = 500, signal } = {}) {
  const until = Date.now() + timeoutMs;
  for (;;) {
    if (signal?.aborted) return null;
    const v = await fn();
    if (v) return v;
    if (Date.now() >= until) return null;
    await sleep(everyMs);
  }
}

/**
 * The overflow problems of a hub/HUD layout object ({overflow, ...}): a pane that scrolls on purpose (Inbox detail
 * `detail.flow`, a scrolling Status page) is fine. Returns null when it fits, else a short reason.
 */
export function layoutProblem(name, layout) {
  if (!layout || typeof layout !== 'object') return null;
  if (!layout.overflow) return null;
  if (layout.detail?.flow) return null;
  if (typeof layout.maxScroll === 'number' && layout.maxScroll > 0) return null;
  return `${name}: overflow (needed ${layout.needed ?? '?'} > available ${layout.available ?? '?'})`;
}
