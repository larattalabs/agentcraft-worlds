// Sim backend: fake building design jobs, so the hub's Design flow is testable without Claude.
// A job walks through designing / checking / rendering for a few (speed-scaled) seconds, then
// installs a copy of a bundled blueprint (workshop for one repo, campus<N> for a group) under a new
// id gen_sim_<n> with the request's name, through the same never-overwrite install as real jobs.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { Foreman } from '../../foreman.js';
import type { Design, DesignRequest } from '../../protocol.js';
import { BUILT_JSON_DIR, BUILT_NBT_DIR, installBlueprint, KIT_DIR, renderPreviews, sidecarProblem, type Sidecar } from '../../designs.js';

const STEPS: Array<{ status: 'designing' | 'checking' | 'rendering'; step: string }> = [
  { status: 'designing', step: 'reading the brief' },
  { status: 'designing', step: 'sketching the floor plan' },
  { status: 'designing', step: 'placing desks, task wall and podium' },
  { status: 'checking', step: 'running the checker' },
  { status: 'rendering', step: 'rendering previews' },
];

class Cancelled extends Error {}

/** The bundled blueprint a sim job copies for a request. */
export function simSource(req: DesignRequest): string {
  return req.kind === 'group' ? `campus${Math.min(4, Math.max(2, req.wings))}` : 'workshop';
}

export class SimDesigner {
  private queue: string[] = [];
  private current: string | undefined;
  private cancelled = new Set<string>();
  private stopped = false;
  private wake: (() => void) | undefined;
  private runP: Promise<void> | undefined;

  constructor(
    private fm: Foreman,
    private speed: number,
    /** ms per step at speed 1 */
    private stepMs = 1500,
  ) {}

  request(d: Design): void {
    if (this.queue.includes(d.id) || this.current === d.id) return;
    this.cancelled.delete(d.id);
    this.queue.push(d.id);
    this.kick();
  }

  cancel(id: string): void {
    this.queue = this.queue.filter((x) => x !== id);
    if (this.current === id) {
      this.cancelled.add(id);
      this.wake?.();
    }
  }

  async stop(): Promise<void> {
    this.stopped = true;
    this.wake?.();
    await this.runP?.catch(() => undefined);
  }

  /** resolves when nothing is queued or running (tests) */
  async idle(): Promise<void> {
    while (this.runP) await this.runP.catch(() => undefined);
  }

  private kick(): void {
    if (this.runP || this.stopped) return;
    this.runP = (async () => {
      try {
        while (!this.stopped && this.queue.length) {
          const id = this.queue.shift()!;
          this.current = id;
          try {
            await this.runJob(id);
          } catch (e) {
            if (!(e instanceof Cancelled)) this.fm.designFailed(id, (e as Error).message);
          } finally {
            this.current = undefined;
            this.cancelled.delete(id);
          }
        }
      } finally {
        this.runP = undefined;
      }
    })();
  }

  private sleep(ms: number, id: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const done = () => {
        clearTimeout(t);
        this.wake = undefined;
        if (this.stopped || this.cancelled.has(id)) reject(new Cancelled());
        else resolve();
      };
      const t = setTimeout(done, ms / this.speed);
      t.unref?.();
      this.wake = done;
    });
  }

  private async runJob(id: string): Promise<void> {
    const d = this.fm.designs.get(id);
    if (!d || d.status === 'cancelled' || d.status === 'done' || d.status === 'failed') return;
    for (const s of STEPS) {
      this.fm.designStep(id, s.status, `${s.step} (simulated)`);
      await this.sleep(this.stepMs, id);
    }
    const req = d.request;
    const src = simSource(req);
    const root = this.fm.config.projectRoot;
    const nbt = path.join(root, BUILT_NBT_DIR, `${src}.nbt`);
    const json = path.join(root, BUILT_JSON_DIR, `${src}.blueprint.json`);
    const sidecar = JSON.parse(fs.readFileSync(json, 'utf8')) as Sidecar;
    const problem = sidecarProblem(sidecar, req);
    if (problem) throw new Error(`the sim copies the bundled ${src}, which does not fit this request: ${problem}`);
    // previews: bundled ones next to the template, else the offline renderer when the kit has one
    const bundled = fs.existsSync(path.dirname(nbt))
      ? fs
          .readdirSync(path.dirname(nbt))
          .filter((f) => f.startsWith(`${src}.preview-`) && f.endsWith('.png'))
          .map((f) => path.join(path.dirname(nbt), f))
      : [];
    let previews = bundled;
    let tmp: string | undefined;
    let note = '';
    if (!previews.length && fs.existsSync(path.join(root, KIT_DIR, 'render.mjs'))) {
      // the renderer lives in the kit: render in a throwaway repo-shaped dir
      tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ac-simdesign-'));
      fs.cpSync(path.join(root, KIT_DIR), path.join(tmp, KIT_DIR), { recursive: true });
      const r = await renderPreviews(tmp, nbt);
      previews = r.files;
      if (r.error) note = `previews: ${r.error}`;
    }
    const current = this.fm.designs.get(id);
    if (!current || current.status === 'cancelled' || this.cancelled.has(id)) throw new Cancelled();
    try {
      const n = this.fm.store.nextId('gen_sim_');
      const installed = installBlueprint({
        nbt,
        sidecar,
        previews,
        outDir: req.outDir,
        baseId: n,
        meta: {
          name: req.name ?? `Sim ${req.style}`,
          description: `Simulated design (a copy of the bundled ${src})${req.notes ? `: ${req.notes}` : ''}`,
        },
      });
      const size = sidecar.size!;
      this.fm.designDone(id, installed, { x: size.x, y: size.y, z: size.z }, note);
    } finally {
      if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
    }
  }
}
