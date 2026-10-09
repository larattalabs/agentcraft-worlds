// Durable acks (store.ts, "the durability rule"): whatever the Foreman acks or announces is on disk
// first. Each test takes state.json AT the ack / announcement (synchronously, from inside the reply
// or the subscriber), then "hard-kills" the Foreman: its flush is disabled so nothing more reaches
// the disk, it is closed, and the file is put back exactly as it was at that moment. A second
// Foreman then boots from that file, as it would after power loss or kill -9 right after the ack.
// It must not resume a turn that already finished, nor resurrect a cancelled goal's or task's work,
// nor run a stopped or paused agent, nor forget an answered decision.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { RESUME_PROMPT } from '../src/agents/prompts.js';
import type { ClientMessage, Outbound } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
const tools = (o: Options) => (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await tools(o)[name]!.handler(args, {})).content.map((c) => c.text).join('\n');
const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: '00000000-0000-4000-8000-000000000000', ...o }) as unknown as SDKMessage;
const init = (s: string) => m({ type: 'system', subtype: 'init', session_id: s, model: 'fake' });
const result = (s: string) => m({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0.001, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
const LEAD_S = '11111111-1111-4111-8111-111111111111';
const WORK_S = '22222222-2222-4222-8222-222222222222';

/**
 * hang: the worker's turn runs until aborted; ask: the worker asks the user first; finish: every turn
 * completes; slow: the worker's turn takes 400 ms (long enough for its inflight entry to be saved)
 */
type Mode = 'hang' | 'ask' | 'finish' | 'slow';
interface Call {
  prompt: string;
  lead: boolean;
}

let current: Harness;

function fake(mode: Mode, calls: Call[]) {
  return ({ prompt, options }: { prompt: string; options: Options }) => {
    const p = String(prompt);
    const lead = 'create_task' in tools(options);
    calls.push({ prompt: p, lead });
    const aborted = new Promise<never>((_, reject) => options.abortController!.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }));
    aborted.catch(() => {}); // awaited only by the turns that wait for their abort
    async function* run(): AsyncGenerator<SDKMessage> {
      if (lead) {
        yield init(LEAD_S);
        if (p.startsWith('Review request')) await callTool(options, 'request_merge', { task_id: 't1', summary: 'ok' });
        else if (!current.fm.tasks.get('t1')) await callTool(options, 'create_task', { title: 'Add a version flag', description: 'x', assignee: 'kit' });
        yield result(LEAD_S);
        return;
      }
      yield init(WORK_S);
      if (mode === 'hang') await aborted;
      if (mode === 'slow') await new Promise((r) => setTimeout(r, 400));
      if (mode === 'ask') await Promise.race([callTool(options, 'ask_user', { question: 'Short or long output?', options: ['Short', 'Long'] }), aborted]);
      fs.appendFileSync(path.join(options.cwd!, 'README.md'), '\nversion flag\n');
      await callTool(options, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
      yield result(WORK_S);
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

const cleanup: string[] = [];
afterAll(() => cleanup.forEach(rmrf));

async function boot(home: string, repo: string, mode: Mode, calls: Call[]): Promise<Harness> {
  const h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repo]);
  current = h;
  const b = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fake(mode, calls) as never, skipAuthCheck: true });
  await h.fm.start(b);
  return h;
}

async function fresh(): Promise<{ home: string; repo: string }> {
  const home = tempDir();
  const repo = await demoRepo();
  cleanup.push(home, path.dirname(repo));
  return { home, repo };
}

/** Send a client intent; returns state.json as it was on disk when the ack went out. */
async function ackSnapshot(h: Harness, msg: ClientMessage): Promise<Buffer> {
  let snap: Buffer | undefined;
  let ack: Outbound | undefined;
  await h.fm.handle(msg, (out) => {
    if (out.type !== 'ack') return;
    snap = fs.readFileSync(h.fm.store.file);
    ack = out;
  });
  expect(ack).toMatchObject({ ok: true });
  return snap!;
}

/** Power loss right after `snap` was taken: nothing later reaches the disk; the next Foreman boots from `snap`. */
async function hardKill(h: Harness, snap: Buffer): Promise<void> {
  h.fm.store.flush = () => {};
  await h.fm.close();
  fs.writeFileSync(h.fm.store.file, snap);
}

const workerCalls = (calls: Call[]) => calls.filter((c) => !c.lead);
const settle = () => new Promise((r) => setTimeout(r, 1500));
const backendState = (h: Harness) => h.fm.store.data.backend.claude as { inflight: Record<string, unknown>; stopped: string[] };

/** Boot, submit a goal and wait until kit's turn on t1 is running (it has its session). */
async function kitWorking(mode: Mode): Promise<{ h: Harness; home: string; repo: string; goalId: string }> {
  const { home, repo } = await fresh();
  const h = await boot(home, repo, mode, []);
  const goal = await h.fm.submitGoal('version flag');
  await until(() => h.fm.store.data.sessions['kit:t1']?.sessionId === WORK_S && !!backendState(h).inflight.kit);
  return { h, home, repo, goalId: goal.id };
}

describe('durable acks: a hard kill right after the ack or announcement loses nothing', () => {
  it('a finished worker turn is not resumed (its cleared inflight was durable when announced)', async () => {
    const { home, repo } = await fresh();
    const h = await boot(home, repo, 'slow', []);
    let seen = false;
    let snap: Buffer | undefined;
    h.fm.subscribe((ev) => {
      if (snap) return;
      const inflight = backendState(h)?.inflight ?? {};
      if (inflight.kit && (inflight.kit as { taskId?: string }).taskId === 't1') seen = true;
      // the first announcement after kit's turn ended (it goes idle): take the disk as it is now
      else if (seen && !inflight.kit) snap = fs.readFileSync(h.fm.store.file);
    });
    await h.fm.submitGoal('version flag');
    await until(() => !!snap);
    await hardKill(h, snap!);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, 'finish', calls2);
    await settle();
    expect(workerCalls(calls2).filter((c) => c.prompt === RESUME_PROMPT)).toEqual([]);
    await h2.fm.close();
  });

  it('goal.cancel: the cancelled goal\'s running task is not resumed', async () => {
    const { h, home, repo, goalId } = await kitWorking('hang');
    const snap = await ackSnapshot(h, { v: 1, type: 'goal.cancel', id: 'c1', goalId });
    await hardKill(h, snap);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, 'finish', calls2);
    await settle();
    expect(h2.fm.goal(goalId)!.status).toBe('cancelled');
    expect(h2.fm.tasks.get('t1')!.status).toBe('cancelled');
    expect(workerCalls(calls2)).toEqual([]);
    expect(backendState(h2).inflight.kit).toBeUndefined();
    await h2.fm.close();
  });

  it('task.action cancel: the cancelled task is not resumed', async () => {
    const { h, home, repo } = await kitWorking('hang');
    const snap = await ackSnapshot(h, { v: 1, type: 'task.action', id: 'c1', taskId: 't1', action: 'cancel' });
    await hardKill(h, snap);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, 'finish', calls2);
    await settle();
    expect(h2.fm.tasks.get('t1')!.status).toBe('cancelled');
    expect(workerCalls(calls2)).toEqual([]);
    expect(backendState(h2).inflight.kit).toBeUndefined();
    await h2.fm.close();
  });

  it('agent.action stop: the stopped worker stays stopped and is not resumed', async () => {
    const { h, home, repo } = await kitWorking('hang');
    const snap = await ackSnapshot(h, { v: 1, type: 'agent.action', id: 'c1', agentId: 'kit', action: 'stop' });
    await hardKill(h, snap);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, 'finish', calls2);
    await settle();
    expect(backendState(h2).stopped).toContain('kit');
    expect(workerCalls(calls2)).toEqual([]);
    expect(backendState(h2).inflight.kit).toBeUndefined();
    await h2.fm.close();
  });

  it('agent.action pause: the paused worker stays paused and runs nothing', async () => {
    const { h, home, repo } = await kitWorking('hang');
    const snap = await ackSnapshot(h, { v: 1, type: 'agent.action', id: 'c1', agentId: 'kit', action: 'pause' });
    await hardKill(h, snap);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, 'finish', calls2);
    await settle();
    expect(h2.fm.agent('kit')!.paused).toBe(true);
    // (its interrupted turn stays inflight: the resume continues it)
    expect(workerCalls(calls2)).toEqual([]);
    await h2.fm.close();
  });

  it('decision.answer (a question): the answer is not lost, the question is not asked again', async () => {
    const { home, repo } = await fresh();
    const h = await boot(home, repo, 'ask', []);
    await h.fm.submitGoal('version flag');
    await until(() => h.fm.decisions.open().some((d) => d.kind === 'question'));
    const q = h.fm.decisions.open().find((d) => d.kind === 'question')!;
    const snap = await ackSnapshot(h, { v: 1, type: 'decision.answer', id: 'c1', decisionId: q.id, option: 'Long' });
    await hardKill(h, snap);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, 'finish', calls2);
    expect(h2.fm.decisions.get(q.id)!.status).not.toBe('open');
    expect(h2.fm.decisions.get(q.id)!.answer?.option).toBe('Long');
    await h2.fm.close();
  });

  it('decision.answer (merge approved): the task stays done and the merge is not asked again', async () => {
    const { home, repo } = await fresh();
    const h = await boot(home, repo, 'finish', []);
    await h.fm.submitGoal('version flag');
    await until(() => h.fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 60_000);
    const md = h.fm.decisions.open().find((d) => d.kind === 'merge')!;
    const snap = await ackSnapshot(h, { v: 1, type: 'decision.answer', id: 'c1', decisionId: md.id, option: 'Merge' });
    expect(h.fm.tasks.get('t1')!.status).toBe('done');
    await hardKill(h, snap);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, 'finish', calls2);
    await settle();
    expect(h2.fm.decisions.get(md.id)!.status).not.toBe('open');
    expect(h2.fm.tasks.get('t1')!.status).toBe('done');
    expect(workerCalls(calls2)).toEqual([]);
    expect(backendState(h2).inflight.kit).toBeUndefined();
    await h2.fm.close();
  });
});
