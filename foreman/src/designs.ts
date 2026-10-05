// Building designs (docs/HUB.md "Generated buildings"): the design records every backend shares,
// and the file side of a design job that is the same for every backend:
//
//   - DesignBook: the designs in state.json, `design.upsert` on every change; a final design
//     (done / failed / cancelled) never changes again, so a cancel can never be overwritten by a
//     job that was still finishing
//   - the scratch dir of a job: <profile>/designs/<id>/, a mirror of the repo layout
//     (tools/blueprints = the kit, docs/BUILDINGS.md, BRIEF.md); `build.mjs` then writes into
//     <scratch>/mod/src/main/resources/data/agentcraft/{structure,blueprints}/ like in the repo
//   - checking a built design with a PRISTINE copy of the kit (the agent may have edited its copy),
//     in a child process with a minimal environment (it runs agent-written code)
//   - installing the result into the mod's user blueprint folder, never overwriting anything
import fs from 'node:fs';
import path from 'node:path';
import type { Ctx } from './context.js';
import { blueprintOutDirError, type Design, type DesignRequest, type DesignStatus } from './protocol.js';
import { truncate } from './util/text.js';
import { run } from './util/proc.js';

/** designs listed in the snapshot (queued/running ones always are) */
export const DESIGN_SNAPSHOT_LIMIT = 20;
/** finished designs kept in state.json */
const DESIGN_KEEP = 100;
const FINAL: ReadonlySet<DesignStatus> = new Set(['done', 'failed', 'cancelled']);

export const isFinalDesign = (d: Design): boolean => FINAL.has(d.status);

export type DesignPatch = Partial<Pick<Design, 'status' | 'step' | 'blueprintId' | 'size' | 'previews' | 'error'>>;

export class DesignBook {
  constructor(private ctx: Ctx) {}

  private get all(): Design[] {
    return (this.ctx.store.data.designs ??= []);
  }

  list(): Design[] {
    return this.all;
  }

  get(id: string): Design | undefined {
    return this.all.find((d) => d.id === id);
  }

  /** queued or running */
  active(): Design[] {
    return this.all.filter((d) => !isFinalDesign(d));
  }

  /** what the snapshot carries: the last DESIGN_SNAPSHOT_LIMIT plus every unfinished one, oldest first */
  recent(): Design[] {
    const tail = this.all.slice(-DESIGN_SNAPSHOT_LIMIT);
    const extra = this.active().filter((d) => !tail.includes(d));
    return [...extra, ...tail].sort((a, b) => a.createdAt - b.createdAt).map((d) => structuredClone(d));
  }

  create(request: DesignRequest): Design {
    const now = this.ctx.now();
    // the decisions' counter: design ids ("d<n>", docs/HUB.md) can never equal a decision id
    const d: Design = { id: this.ctx.store.nextId('d'), request: structuredClone(request), status: 'queued', step: 'waiting for the designer', createdAt: now, updatedAt: now };
    this.all.push(d);
    this.trim();
    this.ctx.store.markDirty();
    this.ctx.emit({ type: 'design.upsert', design: structuredClone(d) });
    return d;
  }

  /** Patch and broadcast. A final design is never changed (returns it unchanged). */
  update(id: string, patch: DesignPatch): Design | undefined {
    const d = this.get(id);
    if (!d || isFinalDesign(d)) return d;
    let changed = false;
    for (const [k, v] of Object.entries(patch) as Array<[keyof DesignPatch, unknown]>) {
      if (v === undefined) continue;
      // redacted before it is cut (an error is the tail of the checker's output)
      const red = (x: string) => (this.ctx.redact ? this.ctx.redact(x) : x);
      const val = k === 'step' || k === 'error' ? truncate(red(String(v)).replace(/\s+/g, ' ').trim(), k === 'step' ? 120 : 1500) : v;
      if (JSON.stringify(d[k]) !== JSON.stringify(val)) {
        (d as Record<string, unknown>)[k] = val;
        changed = true;
      }
    }
    if (changed) {
      d.updatedAt = this.ctx.now();
      this.ctx.store.markDirty();
      this.ctx.emit({ type: 'design.upsert', design: structuredClone(d) });
    }
    return d;
  }

  private trim(): void {
    const all = this.all;
    while (all.length > DESIGN_KEEP) {
      const i = all.findIndex((d) => isFinalDesign(d));
      if (i < 0) break;
      all.splice(i, 1);
    }
  }
}

// ---- naming -----------------------------------------------------------------------------------

/** "Lakeside Cabin" -> "lakeside_cabin" (blueprint ids are [a-z0-9_]+). */
export function slugify(s: string, max = 32): string {
  const slug = s
    .normalize('NFKD')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, max)
    .replace(/_+$/g, '');
  return slug;
}

/** The base blueprint id for a request: gen_<slug of the name, else of the style>. */
export function designBaseId(req: DesignRequest): string {
  const slug = (req.name && slugify(req.name)) || (req.style === 'custom' ? 'building' : slugify(req.style)) || 'building';
  return `gen_${slug}`;
}

const exists = (p: string) => fs.existsSync(p);

/** `base`, `base_2`, `base_3`, ...: the first id with no .nbt or sidecar in `outDir` and not in `taken`. */
export function freeBlueprintId(outDir: string, base: string, taken: ReadonlySet<string> = new Set()): string {
  for (let n = 1; ; n++) {
    const id = n === 1 ? base : `${base}_${n}`;
    if (taken.has(id)) continue;
    if (exists(path.join(outDir, `${id}.nbt`)) || exists(path.join(outDir, `${id}.blueprint.json`))) continue;
    return id;
  }
}

/**
 * Make sure `outDir` is the mod's user blueprint folder (validated like the protocol does, then
 * created, then the real path checked again: a link must not lead elsewhere). Returns the real path.
 */
export function prepareOutDir(outDir: string): string {
  const e = outDirProblem(outDir);
  if (e) throw new Error(`outDir ${e}`);
  const dir = path.resolve(outDir);
  fs.mkdirSync(dir, { recursive: true });
  const real = fs.realpathSync(dir);
  if (path.basename(real) !== 'blueprints' || path.basename(path.dirname(real)) !== 'agentcraft') throw new Error(`outDir resolves to ${real}, not an agentcraft/blueprints folder`);
  return dir;
}

/**
 * The protocol's outDir rule plus: absolute on THIS machine (a Windows path sent to a Foreman on
 * macOS would otherwise be a relative path under the Foreman's cwd).
 */
export function outDirProblem(outDir: string): string | undefined {
  return blueprintOutDirError(outDir) ?? (path.isAbsolute(outDir) ? undefined : `must be an absolute path on this machine (${process.platform})`);
}

// ---- sidecar checks ---------------------------------------------------------------------------

export interface Sidecar {
  id: string;
  name?: string;
  description?: string;
  kind?: string;
  wings?: number;
  size?: { x: number; y: number; z: number };
  [k: string]: unknown;
}

/** Does the built blueprint fit the request? (undefined = yes, else why not) */
export function sidecarProblem(sc: Sidecar, req: DesignRequest): string | undefined {
  const s = sc.size;
  if (!s || ![s.x, s.y, s.z].every((n) => Number.isInteger(n) && n > 0)) return 'the sidecar has no valid size';
  const m = req.maxSize;
  if (s.x > m.x || s.y > m.y || s.z > m.z) return `size ${s.x}x${s.y}x${s.z} exceeds the maximum ${m.x}x${m.y}x${m.z} (x*y*z); make it smaller`;
  if (sc.kind !== req.kind) return `kind is "${String(sc.kind)}" but the request is for a ${req.kind} building`;
  if (req.kind === 'single' && sc.wings !== 1) return `a single building must have wings: 1 (has ${String(sc.wings)})`;
  if (req.kind === 'group' && (typeof sc.wings !== 'number' || sc.wings < req.wings)) return `the request needs ${req.wings} wings (has ${String(sc.wings)})`;
  return undefined;
}

// ---- running the kit --------------------------------------------------------------------------

/** Where build.mjs writes, relative to a repo-shaped root (the project, or a scratch dir). */
export const BUILT_NBT_DIR = path.join('mod', 'src', 'main', 'resources', 'data', 'agentcraft', 'structure');
export const BUILT_JSON_DIR = path.join('mod', 'src', 'main', 'resources', 'data', 'agentcraft', 'blueprints');
export const KIT_DIR = path.join('tools', 'blueprints');
export const PREVIEW_DIR = 'previews';

/** An environment with nothing secret in it, for running agent-written code. */
export function minimalEnv(base: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
  const keep = ['PATH', 'Path', 'HOME', 'USERPROFILE', 'TMP', 'TEMP', 'TMPDIR', 'SystemRoot', 'SYSTEMROOT', 'windir', 'LANG', 'LC_ALL'];
  const env: NodeJS.ProcessEnv = {};
  for (const k of keep) if (base[k] !== undefined) env[k] = base[k];
  return env;
}

export interface NodeRun {
  ok: boolean;
  output: string;
  timedOut: boolean;
}

export async function runNode(script: string, args: string[], cwd: string, timeoutMs: number): Promise<NodeRun> {
  try {
    const r = await run(process.execPath, [script, ...args], { cwd, env: minimalEnv(), timeoutMs });
    return { ok: r.code === 0 && !r.timedOut, output: `${r.stdout}${r.stderr ? `\n${r.stderr}` : ''}`.trim(), timedOut: r.timedOut };
  } catch (e) {
    return { ok: false, output: (e as Error).message, timedOut: false };
  }
}

/** The last lines of a tool's output, for an error message. */
export function outputTail(text: string, lines = 12, max = 1200): string {
  return truncate(text.split('\n').filter((l) => l.trim()).slice(-lines).join('\n'), max);
}

/**
 * Copy the kit (tools/blueprints, every file) from `projectRoot` into `<scratch>/tools/blueprints`,
 * replacing whatever is there, except the design modules listed in `keep` (designs/<id>.mjs).
 */
export function refreshKit(projectRoot: string, scratch: string, keep: string[] = []): void {
  const src = path.join(projectRoot, KIT_DIR);
  const dst = path.join(scratch, KIT_DIR);
  const saved = new Map<string, Buffer>();
  for (const id of keep) {
    const f = path.join(dst, 'designs', `${id}.mjs`);
    if (exists(f)) saved.set(id, fs.readFileSync(f));
  }
  fs.rmSync(dst, { recursive: true, force: true });
  fs.cpSync(src, dst, { recursive: true, filter: (p) => !/(^|[\\/])(node_modules|\.git)([\\/]|$)/.test(path.relative(src, p)) });
  for (const [id, buf] of saved) fs.writeFileSync(path.join(dst, 'designs', `${id}.mjs`), buf);
}

export function rendererIn(root: string): string | undefined {
  const r = path.join(root, KIT_DIR, 'render.mjs');
  return exists(r) ? r : undefined;
}

export interface CheckResult {
  ok: boolean;
  /** what to tell the designer / the user when not ok */
  problem?: string;
  output: string;
  sidecar?: Sidecar;
  nbt?: string;
  json?: string;
}

/**
 * Build designs/<bp>.mjs with a pristine kit and check it against the contract and the request.
 * `kitRoot` is where a fresh kit comes from (the project).
 */
export async function checkDesign(kitRoot: string, scratch: string, bp: string, req: DesignRequest, timeoutMs = 60_000): Promise<CheckResult> {
  const design = path.join(scratch, KIT_DIR, 'designs', `${bp}.mjs`);
  if (!exists(design)) return { ok: false, problem: `there is no ${path.join(KIT_DIR, 'designs', `${bp}.mjs`)}`, output: '' };
  refreshKit(kitRoot, scratch, [bp]);
  const nbt = path.join(scratch, BUILT_NBT_DIR, `${bp}.nbt`);
  const json = path.join(scratch, BUILT_JSON_DIR, `${bp}.blueprint.json`);
  for (const f of [nbt, json]) fs.rmSync(f, { force: true });
  const r = await runNode(path.join(KIT_DIR, 'build.mjs'), [bp], scratch, timeoutMs);
  if (!r.ok) return { ok: false, problem: r.timedOut ? `build.mjs timed out after ${timeoutMs / 1000}s` : `build.mjs / the checker failed:\n${outputTail(r.output)}`, output: r.output };
  if (!exists(nbt) || !exists(json)) return { ok: false, problem: `build.mjs did not write ${bp}.nbt and ${bp}.blueprint.json`, output: r.output };
  let sc: Sidecar;
  try {
    sc = JSON.parse(fs.readFileSync(json, 'utf8')) as Sidecar;
  } catch (e) {
    return { ok: false, problem: `the sidecar is not valid JSON: ${(e as Error).message}`, output: r.output };
  }
  const p = sidecarProblem(sc, req);
  if (p) return { ok: false, problem: p, output: r.output, sidecar: sc };
  return { ok: true, output: r.output, sidecar: sc, nbt, json };
}

/**
 * Render previews of `nbt` into <scratch>/previews with the renderer of a pristine kit, if the kit
 * has one. Returns the PNGs (the renderer names them <id>.preview-<view>.png), or an error.
 */
export async function renderPreviews(scratch: string, nbt: string, timeoutMs = 60_000): Promise<{ files: string[]; error?: string; skipped?: boolean }> {
  const renderer = rendererIn(scratch);
  if (!renderer) return { files: [], skipped: true };
  const out = path.join(scratch, PREVIEW_DIR);
  fs.rmSync(out, { recursive: true, force: true });
  fs.mkdirSync(out, { recursive: true });
  const r = await runNode(path.relative(scratch, renderer), [nbt, '--out', out], scratch, timeoutMs);
  const files = fs
    .readdirSync(out)
    .filter((f) => /\.preview-[a-z0-9_-]+\.png$/i.test(f))
    .sort()
    .map((f) => path.join(out, f));
  if (!r.ok) return { files, error: r.timedOut ? 'the renderer timed out' : outputTail(r.output, 6, 400) };
  return { files };
}

// ---- installing -------------------------------------------------------------------------------

export interface InstallInput {
  nbt: string;
  sidecar: Sidecar;
  /** preview PNGs named <anything>.preview-<view>.png */
  previews: string[];
  outDir: string;
  baseId: string;
  /** ids other jobs are about to use */
  taken?: ReadonlySet<string>;
  /** overrides written into the sidecar (name, description) */
  meta?: { name?: string; description?: string };
}

export interface Installed {
  blueprintId: string;
  nbt: string;
  json: string;
  previews: string[];
}

/**
 * Copy a built blueprint into the mod's user blueprint folder under a fresh id (`baseId`,
 * `baseId_2`, ...). Never overwrites a file: every copy is exclusive, and a file that appeared
 * meanwhile moves on to the next id. The sidecar is written last (the mod lists a blueprint by it).
 */
export function installBlueprint(input: InstallInput): Installed {
  const outDir = prepareOutDir(input.outDir);
  // ids that collided with some file (e.g. a leftover preview PNG): never tried twice
  const tried = new Set(input.taken ?? []);
  for (let attempt = 0; attempt < 50; attempt++) {
    const id = freeBlueprintId(outDir, input.baseId, tried);
    tried.add(id);
    const created: string[] = [];
    try {
      const nbt = path.join(outDir, `${id}.nbt`);
      fs.copyFileSync(input.nbt, nbt, fs.constants.COPYFILE_EXCL);
      created.push(nbt);
      const previews: string[] = [];
      for (const p of input.previews) {
        const view = /\.preview-([a-z0-9_-]+)\.png$/i.exec(p)?.[1];
        if (!view) continue;
        const dst = path.join(outDir, `${id}.preview-${view.toLowerCase()}.png`);
        fs.copyFileSync(p, dst, fs.constants.COPYFILE_EXCL);
        created.push(dst);
        previews.push(dst);
      }
      const json = path.join(outDir, `${id}.blueprint.json`);
      const sidecar = { ...input.sidecar, id, ...(input.meta?.name ? { name: input.meta.name } : {}), ...(input.meta?.description ? { description: input.meta.description } : {}) };
      fs.writeFileSync(json, `${JSON.stringify(sidecar, null, 2)}\n`, { flag: 'wx' });
      created.push(json);
      return { blueprintId: id, nbt, json, previews };
    } catch (e) {
      for (const f of created) fs.rmSync(f, { force: true });
      if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e;
      // another writer took this id between the check and the copy: next one
    }
  }
  throw new Error(`could not find a free blueprint id for ${input.baseId} in ${outDir}`);
}

/** One line describing a request, for the feed. */
export function describeRequest(req: DesignRequest): string {
  const what = req.kind === 'group' ? `${req.style} campus, ${req.wings} wings` : `${req.style} building`;
  return `${req.name ? `"${req.name}" (${what})` : what}, max ${req.maxSize.x}x${req.maxSize.y}x${req.maxSize.z}`;
}
