// Building designs: protocol validation, the never-overwrite install, and the sim backend's fake
// job end to end (files in a temp outDir, the design.upsert sequence, cancel).
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { SimBackend } from '../src/agents/sim/index.js';
import { PROJECT_ROOT } from '../src/config.js';
import { BUILT_JSON_DIR, BUILT_NBT_DIR, designBaseId, freeBlueprintId, installBlueprint, sidecarProblem, slugify, type Sidecar } from '../src/designs.js';
import { ClientMessage, type Design, type DesignRequest } from '../src/protocol.js';
import { makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

export function request(outDir: string, over: Partial<DesignRequest> = {}): DesignRequest {
  return { kind: 'single', wings: 1, style: 'cabin', materials: 'agentcraft', features: ['porch'], maxSize: { x: 40, y: 20, z: 40 }, outDir, ...over };
}

const valid = (r: unknown) => ClientMessage.safeParse({ v: 1, type: 'design.request', id: 'c1', request: r }).success;

describe('design.request validation', () => {
  const ok = '/Users/alex/Library/Application Support/minecraft/agentcraft/blueprints';
  it('accepts a sane request (POSIX and Windows outDir)', () => {
    expect(valid(request(ok))).toBe(true);
    expect(valid(request('C:\\Users\\alex\\AppData\\Roaming\\.minecraft\\agentcraft\\blueprints'))).toBe(true);
    expect(valid(request(`${ok}/`))).toBe(true);
    expect(valid(request(ok, { kind: 'group', wings: 3, style: 'campus' }))).toBe(true);
  });
  it('refuses any outDir that is not an absolute <gameDir>/agentcraft/blueprints', () => {
    for (const bad of [
      'agentcraft/blueprints',
      './agentcraft/blueprints',
      '/tmp/evil',
      '/Users/alex/.ssh',
      '/Users/alex/agentcraft/blueprints/../../.ssh',
      '/Users/alex/agentcraft/blueprints/..',
      '/a/agentcraft/blueprints/x',
      '/agentcraft/blueprintsx',
      'C:\\Windows\\System32',
      'C:agentcraft\\blueprints',
      '',
    ]) {
      expect(valid(request(bad)), bad).toBe(false);
    }
  });
  it('refuses out-of-range sizes, wings that do not match the kind, unknown styles and features', () => {
    expect(valid(request(ok, { maxSize: { x: 8, y: 16, z: 20 } }))).toBe(false);
    expect(valid(request(ok, { maxSize: { x: 129, y: 16, z: 20 } }))).toBe(false);
    expect(valid(request(ok, { maxSize: { x: 20, y: 5, z: 20 } }))).toBe(false);
    expect(valid(request(ok, { maxSize: { x: 20, y: 49, z: 20 } }))).toBe(false);
    expect(valid(request(ok, { maxSize: { x: 20.5, y: 16, z: 20 } }))).toBe(false);
    expect(valid(request(ok, { wings: 2 }))).toBe(false);
    expect(valid(request(ok, { kind: 'group', wings: 1 }))).toBe(false);
    expect(valid(request(ok, { kind: 'group', wings: 9 }))).toBe(false);
    expect(valid({ ...request(ok), style: 'castle' })).toBe(false);
    expect(valid({ ...request(ok), features: ['moat'] })).toBe(false);
    expect(valid(request(ok, { features: ['porch', 'porch'] }))).toBe(false);
    expect(valid(request(ok, { remix: '../workshop' }))).toBe(false);
  });
});

describe('blueprint naming and install', () => {
  let dir: string;
  beforeAll(() => {
    dir = path.join(tempDir('ac-bp-'), 'agentcraft', 'blueprints');
    fs.mkdirSync(dir, { recursive: true });
  });
  afterAll(() => rmrf(path.dirname(path.dirname(dir))));

  it('slugs names into gen_<slug>', () => {
    expect(slugify('Lakeside Cabin!')).toBe('lakeside_cabin');
    expect(designBaseId(request(dir, { name: 'Café  Øst' }))).toBe('gen_cafe_st');
    expect(designBaseId(request(dir))).toBe('gen_cabin');
    expect(designBaseId(request(dir, { style: 'custom' }))).toBe('gen_building');
    expect(designBaseId(request(dir, { name: '!!!' }))).toBe('gen_cabin');
  });

  it('never overwrites: gen_x, gen_x_2, gen_x_3 ...', () => {
    fs.writeFileSync(path.join(dir, 'gen_cabin.nbt'), 'mine');
    fs.writeFileSync(path.join(dir, 'gen_cabin_2.blueprint.json'), '{}');
    expect(freeBlueprintId(dir, 'gen_cabin')).toBe('gen_cabin_3');
    expect(freeBlueprintId(dir, 'gen_cabin', new Set(['gen_cabin_3']))).toBe('gen_cabin_4');
    const nbt = path.join(PROJECT_ROOT, BUILT_NBT_DIR, 'workshop.nbt');
    const sidecar = JSON.parse(fs.readFileSync(path.join(PROJECT_ROOT, BUILT_JSON_DIR, 'workshop.blueprint.json'), 'utf8')) as Sidecar;
    const png = path.join(path.dirname(dir), 'x.preview-iso.png');
    fs.writeFileSync(png, 'png');
    const a = installBlueprint({ nbt, sidecar, previews: [png], outDir: dir, baseId: 'gen_cabin', meta: { name: 'Cabin' } });
    expect(a.blueprintId).toBe('gen_cabin_3');
    expect(fs.readFileSync(path.join(dir, 'gen_cabin.nbt'), 'utf8')).toBe('mine'); // untouched
    const sc = JSON.parse(fs.readFileSync(a.json, 'utf8')) as Sidecar;
    expect(sc).toMatchObject({ id: 'gen_cabin_3', name: 'Cabin', kind: 'single' });
    expect(a.previews).toEqual([path.join(fs.realpathSync(dir), 'gen_cabin_3.preview-iso.png')]);
    expect(installBlueprint({ nbt, sidecar, previews: [], outDir: dir, baseId: 'gen_cabin' }).blueprintId).toBe('gen_cabin_4');
  });

  it('refuses an outDir that is not the blueprints folder, even when called directly', () => {
    const nbt = path.join(PROJECT_ROOT, BUILT_NBT_DIR, 'workshop.nbt');
    expect(() => installBlueprint({ nbt, sidecar: { id: 'x' }, previews: [], outDir: path.dirname(dir), baseId: 'gen_x' })).toThrow(/outDir/);
  });

  it('checks the built sidecar against the request', () => {
    const sc: Sidecar = { id: 'gen_x', kind: 'single', wings: 1, size: { x: 29, y: 15, z: 32 } };
    expect(sidecarProblem(sc, request(dir))).toBeUndefined();
    expect(sidecarProblem(sc, request(dir, { maxSize: { x: 28, y: 20, z: 40 } }))).toMatch(/exceeds/);
    expect(sidecarProblem(sc, request(dir, { kind: 'group', wings: 2 }))).toMatch(/kind/);
    expect(sidecarProblem({ ...sc, kind: 'group', wings: 2 }, request(dir, { kind: 'group', wings: 3 }))).toMatch(/3 wings/);
  });
});

describe('sim backend design jobs', () => {
  let h: Harness;
  let home: string;
  let outDir: string;
  let sim: SimBackend;

  beforeAll(async () => {
    home = tempDir();
    outDir = path.join(tempDir('ac-game-'), 'agentcraft', 'blueprints');
    h = makeForeman(home, ['--backend', 'sim', '--speed', '50']);
    sim = new SimBackend(h.fm, h.cfg.sim);
    await h.fm.start(sim);
  });
  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(path.dirname(path.dirname(outDir)));
  });

  const upserts = (id: string) => h.events.filter((e): e is Extract<typeof e, { type: 'design.upsert' }> => e.type === 'design.upsert' && e.design.id === id).map((e) => e.design);

  it('fakes a job: steps, then a copy of the workshop under a new id in outDir', async () => {
    const replies: unknown[] = [];
    await h.fm.handle({ v: 1, type: 'design.request', id: 'c1', request: request(outDir, { name: 'Lakeside Cabin', notes: 'cosy' }) }, (m) => replies.push(m));
    const ack = replies.find((m) => (m as { type: string }).type === 'ack') as { ok: boolean; result: { designId: string } };
    expect(ack.ok).toBe(true);
    const id = ack.result.designId;
    await until(() => h.fm.designs.get(id)!.status === 'done');
    const d = h.fm.designs.get(id)!;
    expect(d.blueprintId).toBe('gen_sim_1');
    expect(d.size).toEqual({ x: 29, y: 15, z: 32 });
    expect(fs.existsSync(path.join(outDir, 'gen_sim_1.nbt'))).toBe(true);
    const sc = JSON.parse(fs.readFileSync(path.join(outDir, 'gen_sim_1.blueprint.json'), 'utf8')) as Sidecar;
    expect(sc).toMatchObject({ id: 'gen_sim_1', name: 'Lakeside Cabin', kind: 'single' });
    expect(sc.description).toContain('cosy');
    for (const p of d.previews!) expect(fs.existsSync(p)).toBe(true);
    const seq = upserts(id).map((x) => x.status);
    expect(seq[0]).toBe('queued');
    expect(seq).toContain('designing');
    expect(seq).toContain('checking');
    expect(seq).toContain('rendering');
    expect(seq[seq.length - 1]).toBe('done');
    // feed + notification + snapshot
    expect(h.fm.store.data.feed.some((f) => f.text.includes(`Design ${id} is ready`))).toBe(true);
    expect(h.events.some((e) => e.type === 'notify' && e.text.includes('gen_sim_1'))).toBe(true);
    const snap = h.fm.snapshot() as { designs: Design[] };
    expect(snap.designs.map((x) => x.id)).toContain(id);
  });

  it('a group request copies the campus with enough wings; a second job never overwrites', async () => {
    const d = h.fm.requestDesign(request(outDir, { kind: 'group', wings: 3, style: 'campus', maxSize: { x: 128, y: 48, z: 128 } }));
    await until(() => h.fm.designs.get(d.id)!.status === 'done');
    const sc = JSON.parse(fs.readFileSync(path.join(outDir, `${h.fm.designs.get(d.id)!.blueprintId}.blueprint.json`), 'utf8')) as Sidecar;
    expect(sc.kind).toBe('group');
    expect(sc.wings).toBeGreaterThanOrEqual(3);
    expect(h.fm.designs.get(d.id)!.blueprintId).toBe('gen_sim_2');
  });

  it('fails a request the bundled blueprint does not fit', async () => {
    const d = h.fm.requestDesign(request(outDir, { maxSize: { x: 20, y: 16, z: 20 } }));
    await until(() => h.fm.designs.get(d.id)!.status === 'failed');
    expect(h.fm.designs.get(d.id)!.error).toMatch(/exceeds/);
  });

  it('cancel: stops the job, nothing lands in outDir, the design stays cancelled', async () => {
    const before = fs.readdirSync(outDir).length;
    const d = h.fm.requestDesign(request(outDir));
    await until(() => h.fm.designs.get(d.id)!.status === 'designing');
    await h.fm.handle({ v: 1, type: 'design.cancel', id: 'c9', designId: d.id }, () => undefined);
    expect(h.fm.designs.get(d.id)!.status).toBe('cancelled');
    await sim.designer.idle();
    await new Promise((r) => setTimeout(r, 200));
    expect(h.fm.designs.get(d.id)!.status).toBe('cancelled');
    expect(fs.readdirSync(outDir).length).toBe(before);
    // cancelling again is an error
    const replies: Array<{ type: string; ok?: boolean }> = [];
    await h.fm.handle({ v: 1, type: 'design.cancel', id: 'c10', designId: d.id }, (m) => replies.push(m as never));
    expect(replies.find((m) => m.type === 'ack')!.ok).toBe(false);
  });
});
