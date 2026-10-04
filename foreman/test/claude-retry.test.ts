// Passing failures (network, sleep, 529 overloaded, max_turns, a too-long prompt): one automatic
// retry after a pause instead of a blocked task waiting for a manual retry; failed plans and lead
// reviews are tried once more too. Auth, usage limits and the per-turn budget never are.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools[name]!.handler(args, {})).content.map((c) => c.text).join('\n');
let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const init = (s: string) => m({ type: 'system', subtype: 'init', session_id: s, model: 'fake' });
const ok = (s: string) => m({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
const fail = (s: string, subtype: string, errors: string[]) => m({ type: 'result', subtype, is_error: true, num_turns: 3, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [], errors });

interface Call {
  lead: boolean;
  prompt: string;
  resume?: string;
}
type Script = (c: { call: Call; options: Options; s: string; nth: number }) => AsyncGenerator<SDKMessage>;

let h: Harness | undefined;
let home = '';
let repoPath = '';
afterEach(async () => {
  await h?.fm.close();
  rmrf(home);
  if (repoPath) rmrf(path.dirname(repoPath));
  h = undefined;
});

async function start(script: Script, args: string[] = []): Promise<{ h: Harness; calls: Call[] }> {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'claude', '--repo', repoPath, '--workers', 'kit', ...args]);
  const calls: Call[] = [];
  const queryFn = ({ prompt, options }: { prompt: string; options: Options }) => {
    const call: Call = { lead: !(options.tools as string[]).includes('Bash'), prompt: String(prompt), ...(options.resume ? { resume: options.resume } : {}) };
    calls.push(call);
    const s = options.resume ?? sid();
    return Object.assign(script({ call, options, s, nth: calls.filter((c) => c.lead === call.lead).length }), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true, transientRetryMs: 60 }));
  return { h, calls };
}

async function* planOne(options: Options, s: string): AsyncGenerator<SDKMessage> {
  yield init(s);
  await callTool(options, 'create_task', { title: 'Add a flag', assignee: 'kit' });
  yield ok(s);
}

describe('automatic retry of passing failures', () => {
  it('a worker turn that dies on the network resumes its session once and finishes', async () => {
    const { h, calls } = await start(async function* ({ call, options, s, nth }) {
      if (call.lead) return yield* planOne(options, s);
      yield init(s);
      if (nth === 1) {
        yield fail(s, 'error_during_execution', ['API Error: Connection error. (cause: socket hang up)']);
        return;
      }
      fs.appendFileSync(path.join(options.cwd!, 'README.md'), '\nflag\n');
      await callTool(options, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
      yield ok(s);
    }, ['--no-lead-review']);
    await h.fm.submitGoal('add a flag');
    await until(() => h.fm.store.data.feed.some((f) => /turn on t1 ended early .*trying again at/.test(f.text)));
    expect(h.fm.tasks.get('t1')!.status).toBe('doing'); // not blocked while it waits
    expect(h.fm.agent('kit')!.activity).toMatch(/retrying/);
    await until(() => h.fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'));
    const work = calls.filter((c) => !c.lead);
    expect(work).toHaveLength(2);
    expect(work[1]!.resume).toBe(work[0]!.resume ?? h.fm.store.data.sessions['kit:t1']!.sessionId); // same session
    expect(work[1]!.prompt).toMatch(/^Your last turn ended early/);
  });

  it('only once: a second passing failure blocks the task (Retry names the GUI path)', async () => {
    const { h, calls } = await start(async function* ({ call, options, s }) {
      if (call.lead) return yield* planOne(options, s);
      yield init(s);
      yield fail(s, 'error_max_turns', []);
    }, ['--no-lead-review']);
    await h.fm.submitGoal('add a flag');
    await until(() => h.fm.tasks.get('t1')?.status === 'blocked');
    expect(calls.filter((c) => !c.lead)).toHaveLength(2);
    expect(h.events.some((e) => e.type === 'notify' && /Retry on the task's card \(task wall, or hub Goals > Tasks\)/.test(e.text))).toBe(true);
  });

  it('never for the per-turn budget: blocked at once', async () => {
    const { h, calls } = await start(async function* ({ call, options, s }) {
      if (call.lead) return yield* planOne(options, s);
      yield init(s);
      yield fail(s, 'error_max_budget_usd', []);
    }, ['--no-lead-review']);
    await h.fm.submitGoal('add a flag');
    await until(() => h.fm.tasks.get('t1')?.status === 'blocked');
    await new Promise((r) => setTimeout(r, 200));
    expect(calls.filter((c) => !c.lead)).toHaveLength(1);
  });

  it('a too-long prompt retries in a fresh session with the original prompt', async () => {
    const { h, calls } = await start(async function* ({ call, options, s, nth }) {
      if (call.lead) return yield* planOne(options, s);
      yield init(s);
      if (nth === 1) {
        yield fail(s, 'error_during_execution', ['Prompt is too long']);
        return;
      }
      fs.appendFileSync(path.join(options.cwd!, 'README.md'), '\nflag\n');
      await callTool(options, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
      yield ok(s);
    }, ['--no-lead-review']);
    await h.fm.submitGoal('add a flag');
    await until(() => calls.filter((c) => !c.lead).length === 2);
    const second = calls.filter((c) => !c.lead)[1]!;
    expect(second.resume).toBeUndefined();
    expect(second.prompt).toMatch(/ran out of room[\s\S]*Your task: t1/);
  });

  it('a failed plan is tried once more before the goal fails', async () => {
    const { h, calls } = await start(async function* ({ call, options, s, nth }) {
      if (call.lead && nth === 1) {
        yield init(s);
        yield fail(s, 'error_during_execution', ['something odd happened']);
        return;
      }
      if (call.lead) return yield* planOne(options, s);
      yield init(s);
      yield ok(s);
    }, ['--no-lead-review']);
    const g = await h.fm.submitGoal('add a flag');
    await until(() => h.fm.goal(g.id)?.status === 'active');
    expect(calls.filter((c) => c.lead)).toHaveLength(2);
  });

  it('a failed lead review is tried once more before the merge goes to the user without a verdict', async () => {
    let reviews = 0;
    const { h } = await start(async function* ({ call, options, s }) {
      if (call.lead && call.prompt.startsWith('New goal')) return yield* planOne(options, s);
      yield init(s);
      if (call.lead) {
        reviews++;
        if (reviews === 1) {
          yield fail(s, 'error_during_execution', ['API Error: 529 {"type":"error","error":{"type":"overloaded_error"}}']);
          return;
        }
        await callTool(options, 'request_merge', { task_id: 't1', summary: 'looks right' });
        yield ok(s);
        return;
      }
      fs.appendFileSync(path.join(options.cwd!, 'README.md'), '\nflag\n');
      await callTool(options, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
      yield ok(s);
    });
    await h.fm.submitGoal('add a flag');
    await until(() => h.fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 30_000);
    expect(reviews).toBe(2);
    expect(h.fm.decisions.open().find((d) => d.kind === 'merge')!.context).toContain('looks right');
  });

  it('a cancelled task drops its waiting retry', async () => {
    const { h, calls } = await start(async function* ({ call, options, s }) {
      if (call.lead) return yield* planOne(options, s);
      yield init(s);
      yield fail(s, 'error_during_execution', ['fetch failed']);
    }, ['--no-lead-review']);
    await h.fm.submitGoal('add a flag');
    await until(() => h.fm.store.data.feed.some((f) => /trying again at/.test(f.text)));
    await h.fm.handle({ v: 1, type: 'task.action', taskId: 't1', action: 'cancel' }, () => undefined);
    await new Promise((r) => setTimeout(r, 300));
    expect(calls.filter((c) => !c.lead)).toHaveLength(1);
  });
});
