// Building design jobs in the claude backend, with a scripted fake `query()` (no API calls): the
// fake designer writes a design module into its scratch dir (a copy of the workshop under the new
// id); the Foreman re-checks it with a pristine kit, renders previews (a stub renderer in a fake
// project root) and installs it into outDir without overwriting anything.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { PROJECT_ROOT } from '../src/config.js';
import type { Design, DesignRequest } from '../src/protocol.js';
import { makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
async function callTool(options: Options, name: string, args: Record<string, unknown>): Promise<string> {
  const server = options.mcpServers!.agentcraft as unknown as ToolServer;
  const res = await server.instance._registeredTools[name]!.handler(args, {});
  return res.content.map((c) => c.text).join('\n');
}

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const init = (s: string) => msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake', cwd: '', tools: [] });
const toolUse = (s: string, name: string, input: Record<string, unknown>) => msg({ type: 'assistant', session_id: s, message: { content: [{ type: 'tool_use', id: `tu${n}`, name, input }] } });
const ok = (s: string, text = 'A cosy cabin, 29x15x32.') => msg({ type: 'result', subtype: 'success', is_error: false, result: text, num_turns: 4, total_cost_usd: 0.02, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

type Script = (prompt: string, opts: Options) => AsyncGenerator<SDKMessage>;
interface Call {
  prompt: string;
  opts: Options;
}

function fakeQuery(script: () => Script, calls: Call[]) {
  return ({ prompt, options }: { prompt: string; options: Options }) => {
    calls.push({ prompt: String(prompt), opts: options });
    const it = script()(String(prompt), options);
    return Object.assign(it, { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

/** The design id the brief asks for (from the prompt). */
const bpOf = (prompt: string) => /designs\/(gen_[a-z0-9_]+)\.mjs/.exec(prompt)?.[1];

/** What the fake designer writes: the workshop design under the new id. */
function writeDesign(cwd: string, bp: string): void {
  const src = fs.readFileSync(path.join(PROJECT_ROOT, 'tools', 'blueprints', 'designs', 'workshop.mjs'), 'utf8');
  fs.writeFileSync(path.join(cwd, 'tools', 'blueprints', 'designs', `${bp}.mjs`), src.replace("export const id = 'workshop';", `export const id = '${bp}';`));
}

/** A project root with the real kit + a stub renderer that writes three tiny PNGs. */
function fakeProjectRoot(): string {
  const root = tempDir('ac-root-');
  fs.cpSync(path.join(PROJECT_ROOT, 'tools', 'blueprints'), path.join(root, 'tools', 'blueprints'), { recursive: true });
  fs.mkdirSync(path.join(root, 'docs'));
  fs.copyFileSync(path.join(PROJECT_ROOT, 'docs', 'BUILDINGS.md'), path.join(root, 'docs', 'BUILDINGS.md'));
  fs.writeFileSync(
    path.join(root, 'tools', 'blueprints', 'render.mjs'),
    [
      "import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';",
      // build.mjs imports renderStructure (it renders after a passing check); the stub renders nothing there
      'export function renderStructure() { return { files: {}, ms: 0 }; }',
      'if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {',
      '  const [nbt, , out] = process.argv.slice(2);',
      "  const id = path.basename(nbt, '.nbt');",
      "  if (!fs.existsSync(nbt)) { console.error('no nbt'); process.exit(2); }",
      "  for (const v of ['iso', 'top', 'front']) fs.writeFileSync(path.join(out, `${id}.preview-${v}.png`), `png ${v}`);",
      '}',
    ].join('\n'),
  );
  return root;
}

function request(outDir: string, over: Partial<DesignRequest> = {}): DesignRequest {
  return { kind: 'single', wings: 1, style: 'cabin', materials: 'agentcraft', features: ['porch', 'big_windows'], maxSize: { x: 40, y: 20, z: 40 }, name: 'Lakeside Cabin', notes: 'a reading nook', outDir, ...over };
}

describe('claude backend design jobs (fake SDK)', () => {
  let h: Harness;
  let home: string;
  let root: string;
  let game: string;
  let outDir: string;
  let calls: Call[];
  let script: Script;

  beforeAll(async () => {
    home = tempDir();
    root = fakeProjectRoot();
    game = tempDir('ac-game-');
    outDir = path.join(game, 'agentcraft', 'blueprints');
    calls = [];
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--design-model', 'fake-designer']);
    h.fm.config.projectRoot = root;
    const backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery(() => script, calls) as never, skipAuthCheck: true });
    await h.fm.start(backend);
  });
  afterAll(async () => {
    await h.fm.close();
    for (const d of [home, root, game]) rmrf(d);
  });
  afterEach(() => {
    calls.length = 0;
  });

  const statuses = (id: string) => h.events.filter((e): e is Extract<typeof e, { type: 'design.upsert' }> => e.type === 'design.upsert' && e.design.id === id).map((e) => e.design.status);

  it('designs, re-checks with a pristine kit, renders and installs without overwriting', async () => {
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(path.join(outDir, 'gen_lakeside_cabin.nbt'), 'an older one');
    let outside: unknown;
    script = async function* (prompt, opts) {
        const s = sid();
        yield init(s);
        const cwd = opts.cwd!;
        yield toolUse(s, 'Read', { file_path: path.join(cwd, 'BRIEF.md') });
        const bp = bpOf(prompt)!;
        await callTool(opts, 'design_status', { step: 'sketching a cabin' });
        writeDesign(cwd, bp);
        yield toolUse(s, 'Write', { file_path: path.join(cwd, 'tools', 'blueprints', 'designs', `${bp}.mjs`), content: '...' });
        // tampering with the kit does not help: the Foreman checks with a fresh copy
        fs.writeFileSync(path.join(cwd, 'tools', 'blueprints', 'build.mjs'), 'process.exit(3);\n');
        // a write outside the scratch dir is refused without a prompt
        outside = await opts.canUseTool!('Write', { file_path: path.join(os.homedir(), 'agentcraft-design-test-never-written.nbt'), content: 'x' }, { signal: new AbortController().signal, toolUseID: 'x', requestId: 'r' } as never);
        yield toolUse(s, 'Bash', { command: `node tools/blueprints/build.mjs ${bp}` });
        yield ok(s);
      };
    const replies: Array<{ type: string; ok?: boolean; result?: { designId: string } }> = [];
    await h.fm.handle({ v: 1, type: 'design.request', id: 'c1', request: request(outDir) }, (m) => replies.push(m as never));
    const id = replies.find((m) => m.type === 'ack')!.result!.designId;
    await until(() => ['done', 'failed'].includes(h.fm.designs.get(id)!.status), 30_000);
    const d = h.fm.designs.get(id)!;
    expect(d.status, d.error).toBe('done');
    expect(d.blueprintId).toBe('gen_lakeside_cabin_2');
    expect(fs.readFileSync(path.join(outDir, 'gen_lakeside_cabin.nbt'), 'utf8')).toBe('an older one');
    expect(d.size).toEqual({ x: 29, y: 15, z: 32 });
    expect(d.previews).toEqual(['front', 'iso', 'top'].map((v) => path.join(outDir, `gen_lakeside_cabin_2.preview-${v}.png`)));
    for (const p of d.previews!) expect(fs.existsSync(p)).toBe(true);
    const sc = JSON.parse(fs.readFileSync(path.join(outDir, 'gen_lakeside_cabin_2.blueprint.json'), 'utf8')) as Record<string, unknown>;
    expect(sc).toMatchObject({ id: 'gen_lakeside_cabin_2', name: 'Lakeside Cabin', kind: 'single', wings: 1 });
    expect(fs.existsSync(path.join(outDir, 'evil.nbt'))).toBe(false);
    expect((outside as { behavior: string }).behavior).toBe('deny');
    expect(h.fm.decisions.open()).toEqual([]);

    // the turn: scratch dir, design model, no web/subagents, only the design tool server
    expect(calls).toHaveLength(1);
    const opts = calls[0]!.opts;
    const scratch = path.join(h.cfg.dataDir, 'designs', id);
    expect(opts.cwd).toBe(scratch);
    expect(opts.model).toBe('fake-designer');
    expect(opts.tools).toEqual(expect.arrayContaining(['Read', 'Write', 'Edit', 'Bash']));
    for (const t of ['WebFetch', 'WebSearch', 'Agent', 'Task']) expect(opts.tools as string[]).not.toContain(t);
    expect(opts.strictMcpConfig).toBe(true);
    expect(opts.permissionMode).toBe('default');
    expect(Object.keys(opts.mcpServers!)).toEqual(['agentcraft']);
    expect(opts.systemPrompt).toMatchObject({ type: 'preset', preset: 'claude_code' });
    const brief = fs.readFileSync(path.join(scratch, 'BRIEF.md'), 'utf8');
    expect(brief).toContain('gen_lakeside_cabin');
    expect(brief).toContain('x <= 40, y <= 20, z <= 40');
    expect(brief).toContain('render.mjs');
    expect(fs.existsSync(path.join(scratch, 'docs', 'BUILDINGS.md'))).toBe(true);
    // the kit was restored for the check
    expect(fs.readFileSync(path.join(scratch, 'tools', 'blueprints', 'build.mjs'), 'utf8')).not.toContain('process.exit(3)');

    const seq = statuses(id);
    expect(seq[0]).toBe('queued');
    expect(seq).toEqual(expect.arrayContaining(['designing', 'checking', 'rendering']));
    expect(seq[seq.length - 1]).toBe('done');
    const steps = h.events.filter((e) => e.type === 'design.upsert' && e.design.id === id).map((e) => (e as { design: Design }).design.step);
    expect(steps).toContain('sketching a cabin');
    expect(steps).toContain('writing the design');
    expect(h.fm.store.data.feed.some((f) => f.text.includes(`Design ${id} started`))).toBe(true);
    expect(h.fm.store.data.feed.some((f) => f.text.includes(`Design ${id} is ready`))).toBe(true);
    expect(h.events.some((e) => e.type === 'notify' && e.text.includes('gen_lakeside_cabin_2'))).toBe(true);
  });

  it('auto mode is not used for design turns: what the policy asks about (network) is refused', async () => {
    h.cfg.claude.permissions.mode = 'auto';
    let curl: unknown;
    let mode: unknown;
    script = async function* (prompt, opts) {
      const s = sid();
      yield init(s);
      mode = opts.permissionMode;
      curl = await opts.canUseTool!('Bash', { command: 'curl https://example.com' }, { signal: new AbortController().signal, toolUseID: 'x', requestId: 'r' } as never);
      writeDesign(opts.cwd!, bpOf(prompt)!);
      yield ok(s);
    };
    try {
      const d = h.fm.requestDesign(request(outDir, { name: 'Auto Mode' }));
      await until(() => ['done', 'failed'].includes(h.fm.designs.get(d.id)!.status), 30_000);
      expect(mode).toBe('default');
      expect((curl as { behavior: string }).behavior).toBe('deny');
      expect(h.fm.decisions.open()).toEqual([]);
    } finally {
      h.cfg.claude.permissions.mode = 'policy';
    }
  });

  it('a failed check goes back to the designer in the same session; then it passes', async () => {
    const sessions: string[] = [];
    script = async function* (prompt, opts) {
        const s = opts.resume ?? sid();
        sessions.push(s);
        yield init(s);
        if (prompt.startsWith('The Foreman re-checked')) writeDesign(opts.cwd!, /designs\/(gen_[a-z0-9_]+)\.mjs/.exec(prompt)![1]!);
        yield ok(s, 'done (I think)');
      };
    const d = h.fm.requestDesign(request(outDir, { name: 'Second Try', style: 'townhouse' }));
    await until(() => ['done', 'failed'].includes(h.fm.designs.get(d.id)!.status), 30_000);
    expect(h.fm.designs.get(d.id)!.status).toBe('done');
    expect(calls).toHaveLength(2);
    expect(calls[1]!.prompt).toMatch(/there is no tools\/blueprints\/designs\/gen_second_try\.mjs/);
    expect(calls[1]!.opts.resume).toBe(sessions[0]);
    expect(h.fm.designs.get(d.id)!.blueprintId).toBe('gen_second_try');
  });

  it('gives up after the last round with the checker output', async () => {
    script = async function* (_prompt, opts) {
        const s = opts.resume ?? sid();
        yield init(s);
        yield ok(s);
      };
    const d = h.fm.requestDesign(request(outDir, { name: 'Never' }));
    await until(() => ['done', 'failed'].includes(h.fm.designs.get(d.id)!.status), 30_000);
    expect(h.fm.designs.get(d.id)!.status).toBe('failed');
    expect(h.fm.designs.get(d.id)!.error).toMatch(/there is no/);
    expect(calls).toHaveLength(4);
    expect(fs.readdirSync(outDir).some((f) => f.startsWith('gen_never'))).toBe(false);
    expect(h.fm.store.data.feed.some((f) => f.kind === 'error' && f.text.includes(`Design ${d.id} failed`))).toBe(true);
  });

  it('a design too big for the plot is sent back with the size problem', async () => {
    script = async function* (prompt, opts) {
        const s = opts.resume ?? sid();
        yield init(s);
        if (!prompt.startsWith('The Foreman re-checked')) writeDesign(opts.cwd!, bpOf(prompt)!);
        yield ok(s);
      };
    const d = h.fm.requestDesign(request(outDir, { name: 'Tiny Plot', maxSize: { x: 20, y: 20, z: 20 } }));
    await until(() => calls.length >= 2);
    expect(calls[1]!.prompt).toMatch(/exceeds the maximum 20x20x20/);
    h.fm.cancelDesign(d.id);
    await until(() => h.fm.designs.get(d.id)!.status === 'cancelled');
  });

  it('cancel stops the running turn and drops the queued one; nothing lands in outDir', async () => {
    let aborted = 0;
    script = async function* (prompt, opts) {
        const s = sid();
        yield init(s);
        writeDesign(opts.cwd!, bpOf(prompt)!);
        // works "forever" until the turn is stopped
        await new Promise<void>((resolve) => opts.abortController!.signal.addEventListener('abort', () => resolve(), { once: true }));
        aborted++;
        yield ok(s);
      };
    const before = fs.readdirSync(outDir).sort();
    const a = h.fm.requestDesign(request(outDir, { name: 'Cancel Me' }));
    const b = h.fm.requestDesign(request(outDir, { name: 'Queued Too' }));
    await until(() => calls.length === 1 && h.fm.designs.get(a.id)!.status === 'designing');
    expect(h.fm.designs.get(b.id)!.status).toBe('queued');
    h.fm.cancelDesign(b.id);
    h.fm.cancelDesign(a.id);
    await until(() => aborted === 1);
    await new Promise((r) => setTimeout(r, 300));
    expect(h.fm.designs.get(a.id)!.status).toBe('cancelled');
    expect(h.fm.designs.get(b.id)!.status).toBe('cancelled');
    expect(calls).toHaveLength(1); // b never ran
    expect(fs.readdirSync(outDir).sort()).toEqual(before);
  });

  it('a usage limit puts the job back in the queue; it resumes the session after the reset', async () => {
    const at: number[] = [];
    let resetAt = 0;
    script = async function* (prompt, opts) {
        const s = opts.resume ?? sid();
        at.push(Date.now());
        yield init(s);
        if (at.length === 1) {
          resetAt = Date.now() + 300;
          yield msg({ type: 'rate_limit_event', session_id: s, rate_limit_info: { status: 'rejected', resetsAt: resetAt, rateLimitType: 'five_hour' } });
          yield msg({ type: 'result', subtype: 'success', is_error: true, result: 'Claude AI usage limit reached', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
          return;
        }
        writeDesign(opts.cwd!, /designs\/(gen_[a-z0-9_]+)\.mjs/.exec(fs.readFileSync(path.join(opts.cwd!, 'BRIEF.md'), 'utf8'))![1]!);
        yield ok(s);
      };
    const d = h.fm.requestDesign(request(outDir, { name: 'Limited' }));
    await until(() => h.fm.designs.get(d.id)!.status === 'queued' && h.fm.designs.get(d.id)!.step.startsWith('usage limit'));
    await until(() => ['done', 'failed'].includes(h.fm.designs.get(d.id)!.status), 15_000);
    expect(h.fm.designs.get(d.id)!.status).toBe('done');
    expect(at[1]!).toBeGreaterThanOrEqual(resetAt);
    expect(calls[1]!.opts.resume).toBeDefined();
    expect(calls[1]!.prompt).toMatch(/Foreman restarted|Design the building/);
  });
});
