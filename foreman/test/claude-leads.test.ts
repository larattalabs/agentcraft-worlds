// A lead per building, claude backend (fake SDK): goals go to their building's lead, leads plan in
// parallel (one queue each, per-lead order kept), messages / reviews / triage / decisions route to
// the goal's lead, lead tools respect other leads' tasks, a released lead's goal is taken over by
// marlow with the plan, and a usage warning makes leads take turns one at a time.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }>; isError?: boolean }> }> } };
const tools = (o: Options) => (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await tools(o)[name]!.handler(args, {})).content.map((c) => c.text).join('\n');

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const result = (s: string) => msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

interface Turn {
  who: string;
  prompt: string;
  system: string;
  start: number;
  end?: number;
  aborted?: boolean;
}

const turns: Turn[] = [];
/** goal text -> a gate the lead's planning turn waits on */
const holds = new Map<string, Promise<void>>();
/** tests react to tool results here */
const toolLog: Array<{ who: string; tool: string; out: string }> = [];
/** what a lead does in its planning turn, by goal text */
const planScript = new Map<string, (o: Options) => Promise<void>>();

function fakeQuery() {
  return ({ prompt, options }: { prompt: string; options?: Options }) => {
    const opts = options!;
    const p = String(prompt);
    const system = (opts.systemPrompt as { append?: string }).append ?? '';
    const who = /# You are (\w+),/.exec(system)?.[1]?.toLowerCase() ?? '?';
    const turn: Turn = { who, prompt: p, system, start: Date.now() };
    turns.push(turn);
    const aborted = new Promise<void>((resolve) => opts.abortController!.signal.addEventListener('abort', () => resolve(), { once: true }));
    async function* run(): AsyncGenerator<SDKMessage> {
      const s = sid();
      yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake-model', cwd: '', tools: [] });
      const goal = /New goal from .*:\n"(.+)"/.exec(p)?.[1];
      // reviews and triage can be held too: "Review request:t5", "Triage request:t6"
      const job = /^(Review request|Triage request): (t\d+)/.exec(p);
      const jobHold = job ? holds.get(`${job[1]}:${job[2]}`) : undefined;
      if (jobHold) {
        await Promise.race([jobHold, aborted]);
        if (opts.abortController!.signal.aborted) {
          turn.aborted = true;
          return;
        }
      }
      if (goal) {
        const hold = holds.get(goal);
        if (hold) await Promise.race([hold, aborted]);
        if (opts.abortController!.signal.aborted) {
          turn.aborted = true;
          return;
        }
        const script = planScript.get(goal);
        if (script) await script(opts);
        else {
          await callTool(opts, 'write_memory', { title: `Plan: ${goal}`, body: `PLAN-BODY-${goal}`, scope: 'shared' });
          toolLog.push({ who, tool: 'create_task', out: await callTool(opts, 'create_task', { title: `Task for ${goal}`, description: 'x', assignee: 'kit' }) });
        }
      }
      await new Promise((r) => setTimeout(r, 30));
      turn.end = Date.now();
      yield result(s);
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

let h: Harness;
let backend: ClaudeBackend;
let home: string;
let repoA: string;
let repoB: string;
let repoC: string;

beforeAll(async () => {
  home = tempDir();
  repoA = await demoRepo();
  repoB = await demoRepo();
  repoC = await demoRepo();
  h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit,juniper', '--repo', [repoA, repoB, repoC].join(','), '--no-lead-review']);
  backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery() as never, skipAuthCheck: true });
  await h.fm.start(backend);
  // pause the workers: these tests are about the leads
  await h.fm.agentAction('kit', 'pause');
  await h.fm.agentAction('juniper', 'pause');
});

afterAll(async () => {
  await h.fm.close();
  for (const d of [home, repoA, repoB, repoC]) rmrf(d.endsWith('demo-app') ? path.dirname(d) : d);
});

const [A, B, C] = ['demo-app', 'demo-app-2', 'demo-app-3'];

describe('claude backend with a lead per building', () => {
  it('plans goals of two buildings in parallel, each by its own lead and session', async () => {
    const fm = h.fm;
    expect(fm.repos.list().map((r) => r.id)).toEqual([A, B, C]);
    expect(fm.assignLead('w/b1', [A]).leadId).toBe('ines');
    expect(fm.assignLead('w/b2', [B]).leadId).toBe('bram');
    // both planning turns wait until both have started: only possible if they run at the same time
    let release!: () => void;
    const both = new Promise<void>((r) => (release = r));
    holds.set('alpha', both);
    holds.set('beta', both);
    const ga = await fm.submitGoal('alpha', A);
    const gb = await fm.submitGoal('beta', B);
    expect([ga.leadId, gb.leadId]).toEqual(['ines', 'bram']);
    await until(() => turns.filter((t) => t.prompt.includes('"alpha"') || t.prompt.includes('"beta"')).length === 2);
    release();
    await until(() => fm.goal(ga.id)!.status === 'active' && fm.goal(gb.id)!.status === 'active');
    expect(fm.store.data.sessions[`ines:${ga.id}`]?.sessionId).toBeTruthy();
    expect(fm.store.data.sessions[`bram:${gb.id}`]?.sessionId).toBeTruthy();
    expect(fm.store.data.sessions[`marlow:${ga.id}`]).toBeUndefined();
    // the prompts name the lead, its building and the other leads; workers are shared
    const ines = turns.find((t) => t.who === 'ines')!;
    expect(ines.system).toContain('# You are Ines, lead of building b1 on an AgentCraft team');
    expect(ines.system).toContain(`You lead building b1 with the repositories ${A}`);
    expect(ines.system).toContain('Bram (id "bram") leads building b2');
    expect(ines.system).toContain('Marlow (id "marlow") leads home');
    expect(ines.system).toContain('ONE pool shared by every lead');
    expect(fm.store.data.feed.some((f) => f.agentId === 'ines' && /^Ines planned the goal into 1 task/.test(f.text))).toBe(true);
    // tasks keep their goal; the task's lead is the goal's lead
    expect(fm.leadOfTask(fm.tasks.forGoal(ga.id)[0])).toBe('ines');
  });

  it("keeps each lead's own order: its next job waits for its running turn; a message reaches it mid-turn", async () => {
    const fm = h.fm;
    let release!: () => void;
    holds.set('gamma', new Promise<void>((r) => (release = r)));
    const g1 = await fm.submitGoal('gamma', A);
    const g2 = await fm.submitGoal('gamma two', A);
    await until(() => turns.some((t) => t.who === 'ines' && t.prompt.includes('"gamma"')));
    await fm.handle({ v: 1, type: 'user.message', to: 'all', text: '@ines also add a test' }, () => undefined);
    await new Promise((r) => setTimeout(r, 150));
    // ines is busy: her second plan waits (marlow and bram are free, but it is not theirs)
    expect(turns.some((t) => t.prompt.includes('"gamma two"'))).toBe(false);
    release();
    await until(() => fm.goal(g1.id)!.status === 'active' && fm.goal(g2.id)!.status === 'active');
    const first = turns.find((t) => t.prompt.includes('"gamma"'))!;
    const second = turns.find((t) => t.prompt.includes('"gamma two"'))!;
    expect(second.who).toBe('ines');
    expect(second.start).toBeGreaterThanOrEqual(first.end!);
    // the message went to ines (with a tool result of her running turn), not to marlow
    const m = fm.store.data.messages.find((x) => x.text === 'also add a test')!;
    expect(m.to).toBe('ines');
    expect(m.readBy).toContain('ines');
  });

  it("routes plain messages to the current goal's lead and @marlow to marlow", async () => {
    const fm = h.fm;
    const before = turns.length;
    await fm.handle({ v: 1, type: 'user.message', to: 'all', text: 'how is it going?' }, () => undefined);
    await until(() => turns.slice(before).some((t) => t.prompt.includes('how is it going?')));
    expect(turns.slice(before).find((t) => t.prompt.includes('how is it going?'))!.who).toBe('ines'); // the newest goal is ines's
    await fm.handle({ v: 1, type: 'user.message', to: 'all', text: '@marlow anything for you?' }, () => undefined);
    await until(() => fm.store.data.messages.some((m) => m.from === 'marlow' && m.to === 'user' && /No goal yet/.test(m.text)));
  });

  it("opens merge decisions and triage turns for the task's lead", async () => {
    const fm = h.fm;
    const g = fm.goals().find((x) => x.text === 'beta')!;
    const t = fm.tasks.forGoal(g.id)[0]!;
    // a task in review: the merge decision is bram's (podium of his building)
    fm.tasks.update(t.id, { repoId: B });
    const wt = await fm.repos.createWorktree(B, 'kit', t);
    fm.tasks.update(t.id, { worktree: wt.id, branch: wt.branch, assignee: 'kit' });
    fs.appendFileSync(path.join(wt.path, 'README.md'), '\nmore\n');
    fm.tasks.setStatus(t.id, 'review', { force: true });
    (backend as unknown as { openMergeDecision(t: unknown, s: string): void }).openMergeDecision(fm.tasks.require(t.id), 'done');
    const d = fm.decisions.open().find((x) => x.taskId === t.id)!;
    expect(d.agentId).toBe('bram');
    fm.decisions.cancel(d.id, 'test');
    // PR triage goes to the goal's lead, in the goal's session
    fm.tasks.update(t.id, { pr: { url: 'https://github.com/o/r/pull/7', id: 7, host: 'github', branch: 'b', target: 'main', status: 'open', checks: 'passing', threads: { open: 1, new: 1 }, updatedAt: Date.now() } });
    fm.tasks.setStatus(t.id, 'pr', { force: true });
    await (backend as unknown as { enqueueTriage(id: string, items: unknown[]): Promise<void> }).enqueueTriage(t.id, [{ ref: `${t.id}/thread-1`, kind: 'thread', threadId: '1', author: 'sam', text: 'rename this' }]);
    await until(() => turns.some((x) => x.prompt.startsWith('Triage request')));
    expect(turns.find((x) => x.prompt.startsWith('Triage request'))!.who).toBe('bram');
  });

  it("does not let a lead change another lead's task, and says when a named worker is busy elsewhere", async () => {
    const fm = h.fm;
    const ga = fm.goals().find((x) => x.text === 'alpha')!;
    const ta = fm.tasks.forGoal(ga.id)[0]!;
    // kit is busy on bram's task
    const gb = fm.goals().find((x) => x.text === 'beta')!;
    const tb = fm.tasks.forGoal(gb.id)[0]!;
    fm.tasks.setStatus(tb.id, 'doing', { force: true });
    fm.tasks.update(tb.id, { assignee: 'kit' });
    planScript.set('delta', async (o) => {
      toolLog.push({ who: 'ines', tool: 'update_task', out: await callTool(o, 'update_task', { task_id: tb.id, assignee: 'juniper' }) });
      toolLog.push({ who: 'ines', tool: 'create_task', out: await callTool(o, 'create_task', { title: 'Task for delta', description: 'x', assignee: 'kit' }) });
      toolLog.push({ who: 'ines', tool: 'update_task-own', out: await callTool(o, 'update_task', { task_id: ta.id, summary: 'mine' }) });
    });
    const g = await fm.submitGoal('delta', A);
    await until(() => fm.goal(g.id)!.status === 'active');
    expect(toolLog.find((x) => x.tool === 'update_task')!.out).toMatch(new RegExp(`${tb.id} belongs to Bram's goal`));
    expect(fm.tasks.get(tb.id)!.assignee).toBe('kit');
    expect(toolLog.filter((x) => x.tool === 'create_task').at(-1)!.out).toMatch(new RegExp(`Kit is busy on ${tb.id} for Bram; t\\d+ waits until they are free`));
    expect(toolLog.find((x) => x.tool === 'update_task-own')!.out).toMatch(/^Updated/);
    fm.tasks.setStatus(tb.id, 'todo', { force: true });
  });

  it('a released lead stops; marlow takes its planning goal over with the plan', async () => {
    const fm = h.fm;
    expect(fm.assignLead('w/b3', [C]).leadId).toBe('cass');
    planScript.set('epsilon', async (o) => {
      await callTool(o, 'write_memory', { title: 'Plan: epsilon', body: 'PLAN-BODY-epsilon', scope: 'shared' });
    });
    holds.set('epsilon', new Promise<void>(() => undefined)); // cass never finishes on her own
    const g = await fm.submitGoal('epsilon', C);
    expect(g.leadId).toBe('cass');
    await until(() => turns.some((t) => t.who === 'cass' && t.prompt.includes('"epsilon"')));
    // cass got as far as the plan note before the building went away
    const note = fm.memory.write({ scope: 'shared', title: 'Plan: epsilon', body: 'PLAN-BODY-epsilon', author: 'cass' });
    (fm.store.data.backend.claude as { plans: Record<string, string> }).plans[g.id] = note.id;
    holds.delete('epsilon');
    planScript.delete('epsilon');
    fm.releaseBuilding('w/b3');
    expect(fm.goal(g.id)!.leadId).toBeUndefined();
    await until(() => turns.some((t) => t.who === 'cass' && t.aborted));
    await until(() => turns.some((t) => t.who === 'marlow' && t.prompt.includes('"epsilon"')));
    const take = turns.find((t) => t.who === 'marlow' && t.prompt.includes('"epsilon"'))!;
    expect(take.prompt).toContain('You take over this goal from Cass');
    expect(take.prompt).toContain('PLAN-BODY-epsilon');
    await until(() => fm.goal(g.id)!.status === 'active');
    expect(fm.store.data.sessions[`marlow:${g.id}`]?.sessionId).toBeTruthy();
    expect(fm.agents().some((a) => a.id === 'cass')).toBe(false);
  });

  it('a moved planning goal is planned by marlow even while marlow waits on a question about something else', async () => {
    const fm = h.fm;
    expect(fm.assignLead('w/b4', [C]).leadId).toBe('cass');
    holds.set('theta', new Promise<void>(() => undefined));
    const g = await fm.submitGoal('theta', C);
    await until(() => turns.some((t) => t.who === 'cass' && t.prompt.includes('"theta"')));
    const q = fm.createDecision({ agentId: 'marlow', kind: 'question', question: 'Unrelated?', options: ['Yes', 'No'] });
    holds.delete('theta');
    fm.releaseBuilding('w/b4');
    await until(() => turns.some((t) => t.who === 'marlow' && t.prompt.includes('"theta"')));
    expect(turns.find((t) => t.who === 'marlow' && t.prompt.includes('"theta"'))!.prompt).toContain('You take over this goal from Cass');
    await until(() => fm.goal(g.id)!.status === 'active');
    fm.decisions.cancel(q.id, 'test');
  });

  it("hands a released lead's review and pending PR triage to marlow", async () => {
    const fm = h.fm;
    const cfg = h.cfg.claude as { leadReview: boolean };
    cfg.leadReview = true;
    expect(fm.assignLead('w/b5', [C]).leadId).toBe('cass');
    planScript.set('iota', async (o) => {
      await callTool(o, 'create_task', { title: 'iota review', description: 'x', assignee: 'kit' });
      await callTool(o, 'create_task', { title: 'iota pr', description: 'x', assignee: 'kit' });
    });
    const g = await fm.submitGoal('iota', C);
    await until(() => fm.goal(g.id)!.status === 'active');
    const [tr, tp] = fm.tasks.forGoal(g.id);
    // a finished task: CI, then cass's review (held mid-turn)
    holds.set(`Review request:${tr!.id}`, new Promise<void>(() => undefined));
    const wt = await fm.repos.createWorktree(C, 'kit', tr!);
    fm.tasks.update(tr!.id, { worktree: wt.id, branch: wt.branch, assignee: 'kit' });
    fs.appendFileSync(path.join(wt.path, 'README.md'), '\niota\n');
    fm.tasks.setStatus(tr!.id, 'review', { force: true });
    await (backend as unknown as { afterWorkerDone(id: string): Promise<void> }).afterWorkerDone(tr!.id);
    await until(() => turns.some((t) => t.who === 'cass' && t.prompt.startsWith(`Review request: ${tr!.id}`)));
    // a PR with new comments: cass's triage waits behind her review
    fm.tasks.update(tp!.id, { pr: { url: 'https://github.com/o/r/pull/9', id: 9, host: 'github', branch: 'b', target: 'main', status: 'open', checks: 'passing', threads: { open: 1, new: 1 }, updatedAt: Date.now() } });
    fm.tasks.setStatus(tp!.id, 'pr', { force: true });
    const prs = (backend as unknown as { prs: { state(id: string): { triage?: unknown } } }).prs;
    const items = [{ ref: `${tp!.id}/thread-1`, kind: 'thread', threadId: '1', author: 'sam', text: 'rename this' }];
    prs.state(tp!.id).triage = { items, at: Date.now(), attempts: 1 };
    await (backend as unknown as { enqueueTriage(id: string, items: unknown[]): Promise<void> }).enqueueTriage(tp!.id, items);
    await new Promise((r) => setTimeout(r, 100));
    expect(turns.some((t) => t.prompt.startsWith(`Triage request: ${tp!.id}`))).toBe(false);

    fm.releaseBuilding('w/b5');
    holds.delete(`Review request:${tr!.id}`); // cass's turn already waits on it; marlow's must not
    await until(() => turns.some((t) => t.who === 'cass' && t.aborted && t.prompt.startsWith('Review request')));
    await until(() => turns.some((t) => t.who === 'marlow' && t.prompt.includes(`Review request: ${tr!.id}`)));
    await until(() => turns.some((t) => t.who === 'marlow' && t.prompt.includes(`Triage request: ${tp!.id}`)));
    expect(turns.some((t) => t.who === 'cass' && t.prompt.startsWith(`Triage request: ${tp!.id}`))).toBe(false);
    const first = turns.find((t) => t.who === 'marlow' && (t.prompt.includes(`Review request: ${tr!.id}`) || t.prompt.includes(`Triage request: ${tp!.id}`)))!;
    expect(first.prompt).toContain('You take over this goal from Cass');
    cfg.leadReview = false;
  });

  it('a usage warning makes leads take turns one at a time', async () => {
    const fm = h.fm;
    const st = fm.store.data.backend.claude as { throttle?: { until: number } };
    st.throttle = { until: Date.now() + 60_000 };
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    holds.set('zeta', gate);
    holds.set('eta', gate);
    const gz = await fm.submitGoal('zeta', A);
    const ge = await fm.submitGoal('eta', B);
    await until(() => turns.some((t) => t.prompt.includes('"zeta"')));
    await new Promise((r) => setTimeout(r, 200));
    expect(turns.some((t) => t.prompt.includes('"eta"'))).toBe(false);
    release();
    await until(() => fm.goal(gz.id)!.status === 'active' && fm.goal(ge.id)!.status === 'active');
    const z = turns.find((t) => t.prompt.includes('"zeta"'))!;
    const e = turns.find((t) => t.prompt.includes('"eta"'))!;
    expect(e.start).toBeGreaterThanOrEqual(z.end!);
    delete st.throttle;
  });

  it('a goal marlow is planning moves to the lead of a building placed for its repository, without a second plan (C3)', async () => {
    const fm = h.fm;
    for (const l of fm.leads.list()) if (l.building) fm.releaseLead(l.leadId, 'test reset');
    const repoD = await demoRepo();
    extra.push(path.dirname(repoD));
    const r = await fm.repos.add(repoD);
    let release!: () => void;
    holds.set('kappa', new Promise<void>((res) => (release = res)));
    const g = await fm.submitGoal('kappa', r.id);
    expect(fm.leadOf(g)).toBe('marlow');
    await until(() => turns.some((t) => t.who === 'marlow' && t.prompt.includes('"kappa"')));
    const lead = fm.assignLead('w/b9', [r.id]).leadId;
    expect(lead).not.toBe('marlow');
    expect(fm.goal(g.id)!.leadId).toBe(lead);
    expect(fm.store.data.feed.some((f) => f.goalId === g.id && /takes over .* from Marlow \(its repository is in/.test(f.text))).toBe(true);
    release();
    await until(() => fm.goal(g.id)!.status === 'active'); // marlow's running plan settles it
    await new Promise((res) => setTimeout(res, 150));
    expect(turns.filter((t) => t.prompt.includes('"kappa"')).map((t) => t.who)).toEqual(['marlow']);
  });
});

const extra: string[] = [];
afterAll(() => {
  for (const d of extra) rmrf(d);
});
