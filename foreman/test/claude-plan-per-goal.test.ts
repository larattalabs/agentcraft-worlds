// Two goals planned before either one's task starts: each worker must get its own goal's plan,
// not the newest "Plan: ..." note in memory.
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { planText } from '../src/agents/prompts.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };

async function callTool(options: Options, name: string, args: Record<string, unknown>): Promise<string> {
  const server = options.mcpServers!.agentcraft as unknown as ToolServer;
  const res = await server.instance._registeredTools[name]!.handler(args, {});
  return res.content.map((c) => c.text).join('\n');
}

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const result = (session: string) => msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: session, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

const workPrompts = new Map<string, string>();

function fakeQuery() {
  return ({ prompt, options }: { prompt: string; options?: Options }) => {
    const opts = options!;
    const p = String(prompt);
    async function* run(): AsyncGenerator<SDKMessage> {
      const s = sid();
      yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake-model', cwd: '', tools: [] });
      const goal = /New goal from .*:\n"(.+)"/.exec(p)?.[1];
      if (goal) {
        await callTool(opts, 'write_memory', { title: `Plan: ${goal}`, body: `PLAN-BODY-${goal}`, scope: 'shared' });
        await callTool(opts, 'create_task', { title: `Task for ${goal}`, assignee: 'kit' });
      }
      const task = /Your task: (t\d+)/.exec(p)?.[1];
      if (task) workPrompts.set(task, p);
      yield result(s);
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

let h: Harness;
let home: string;
let repoPath: string;

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
  const backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery() as never, skipAuthCheck: true });
  await h.fm.start(backend);
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('plan per goal', () => {
  it("gives each worker its own goal's plan when several goals are planned", async () => {
    const fm = h.fm;
    await fm.agentAction('kit', 'pause'); // hold the work until both goals are planned
    const a = await fm.submitGoal('alpha');
    await until(() => fm.goal(a.id)!.status === 'active');
    const b = await fm.submitGoal('beta');
    await until(() => fm.goal(b.id)!.status === 'active');
    expect(workPrompts.size).toBe(0);

    await fm.agentAction('kit', 'resume');
    const taskOf = (g: string) => fm.tasks.forGoal(g)[0]!.id;
    await until(() => workPrompts.has(taskOf(a.id)));
    const alpha = workPrompts.get(taskOf(a.id))!;
    expect(alpha).toContain('PLAN-BODY-alpha');
    expect(alpha).not.toContain('PLAN-BODY-beta');
  });

  it('files tasks under the goal being planned, not the newest goal', async () => {
    const fm = h.fm;
    await fm.agentAction('kit', 'pause');
    const c = await fm.submitGoal('gamma');
    const d = await fm.submitGoal('delta'); // submitted while gamma may still be planning
    await until(() => fm.goal(c.id)!.status === 'active' && fm.goal(d.id)!.status === 'active');
    expect(fm.tasks.forGoal(c.id).map((t) => t.title)).toEqual(['Task for gamma']);
    expect(fm.tasks.forGoal(d.id).map((t) => t.title)).toEqual(['Task for delta']);
    await fm.agentAction('kit', 'resume');
  });

  it('falls back to the newest plan written since the goal was created', () => {
    const fm = h.fm;
    const goal = { id: 'gx', text: 'x', progress: 0, status: 'active' as const, createdAt: Date.now() + 60_000, updatedAt: 0 };
    expect(planText(fm, goal)).toBe('(no plan in memory)');
    expect(planText(fm)).toContain('PLAN-BODY-delta');
  });
});
