// Lead session rotation (claude.leadSession): a lead's per-goal session that is old or long is
// replaced by a fresh one seeded with the plan note, the task board and the goal thread's latest
// messages; the spend of the retired session is kept.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it } from 'vitest';
import { ClaudeBackend, rotationDue } from '../src/agents/claude/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools[name]!.handler(args, {})).content.map((c) => c.text).join('\n');
let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const DAY = 86_400_000;

describe('rotationDue', () => {
  it('rotates by age or by turns; 0 = no limit; legacy records start their clock first', () => {
    const now = Date.now();
    const lim = { maxDays: 7, maxTurns: 40 };
    expect(rotationDue({ sessionId: 's', turns: 0, costUsd: 0, updatedAt: now, startedAt: now - 8 * DAY, sessionTurns: 3 }, lim, now)).toBe('8 days old');
    expect(rotationDue({ sessionId: 's', turns: 0, costUsd: 0, updatedAt: now, startedAt: now - DAY, sessionTurns: 40 }, lim, now)).toBe('40 turns');
    expect(rotationDue({ sessionId: 's', turns: 0, costUsd: 0, updatedAt: now, startedAt: now - DAY, sessionTurns: 39 }, lim, now)).toBeUndefined();
    expect(rotationDue({ sessionId: 's', turns: 0, costUsd: 0, updatedAt: now, startedAt: now - 30 * DAY, sessionTurns: 400 }, { maxDays: 0, maxTurns: 0 }, now)).toBeUndefined();
    expect(rotationDue({ sessionId: 's', turns: 900, costUsd: 0, updatedAt: now - 30 * DAY }, lim, now)).toBeUndefined(); // no startedAt
    expect(rotationDue(undefined, lim, now)).toBeUndefined();
  });
});

describe('a lead session that grew long', () => {
  let h: Harness | undefined;
  let home = '';
  let repoPath = '';
  afterEach(async () => {
    await h?.fm.close();
    rmrf(home);
    if (repoPath) rmrf(path.dirname(repoPath));
  });

  it('is replaced by a seeded fresh one after claude.leadSession.maxTurns, and its spend is kept', async () => {
    home = tempDir();
    repoPath = await demoRepo();
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { leadSession: { maxDays: 7, maxTurns: 2 } } }));
    h = makeForeman(home, ['--backend', 'claude', '--repo', repoPath, '--workers', 'kit', '--no-lead-review']);
    const fm = h.fm;
    const lead: Array<{ prompt: string; resume?: string; session: string }> = [];
    const cost = new Map<string, number>();
    const queryFn = ({ prompt, options }: { prompt: string; options: Options }) => {
      const isLead = !(options.tools as string[]).includes('Edit');
      const s = options.resume ?? sid();
      if (isLead) lead.push({ prompt: String(prompt), session: s, ...(options.resume ? { resume: options.resume } : {}) });
      async function* run(): AsyncGenerator<SDKMessage> {
        yield m({ type: 'system', subtype: 'init', session_id: s, model: 'fake' });
        if (isLead && String(prompt).startsWith('New goal')) {
          await callTool(options, 'write_memory', { title: 'Plan: add a flag', body: 'Step 1: the flag. Step 2: docs.', scope: 'shared' }).catch(() => '');
          await callTool(options, 'create_task', { title: 'Add a flag', assignee: 'kit' });
        }
        if (!isLead) {
          fs.appendFileSync(path.join(options.cwd!, 'README.md'), '\nflag\n');
          await callTool(options, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
        }
        if (isLead && /second question/.test(String(prompt))) await callTool(options, 'send_message', { to: 'user', text: 'Answer two' });
        // cumulative per session, like total_cost_usd: +1.00 per lead turn
        const total = (cost.get(s) ?? 0) + (isLead ? 1 : 0);
        cost.set(s, total);
        yield m({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 2, total_cost_usd: total, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await fm.start(new ClaudeBackend(fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
    const g = await fm.submitGoal('add a flag');
    await until(() => fm.goal(g.id)?.status === 'active' && lead.length === 1);
    fm.goalMessage(g.id, 'first question: is the flag documented?');
    await until(() => lead.length === 2);
    expect(lead[1]!.resume).toBe(lead[0]!.session); // still the same session (1 turn so far)
    await until(() => fm.store.data.sessions[`marlow:${g.id}`]?.sessionTurns === 2);
    fm.goalMessage(g.id, 'second question: and the changelog?');
    await until(() => lead.length === 3);
    const third = lead[2]!;
    expect(third.resume).toBeUndefined(); // rotated: a fresh session
    expect(third.prompt).toMatch(/This is a fresh session for goal g1 .*the previous one was 2 turns/);
    expect(third.prompt).toMatch(/# Task board for the goal\n- t1 /);
    expect(third.prompt).toMatch(/# Latest messages\n[\s\S]*first question: is the flag documented\?/);
    expect(third.prompt).toMatch(/second question: and the changelog\?/); // the job's own prompt follows
    await until(() => fm.store.data.sessions[`marlow:${g.id}`]?.sessionId === third.session && fm.store.data.sessions[`marlow:${g.id}`]?.sessionTurns === 1);
    const rec = fm.store.data.sessions[`marlow:${g.id}`]!;
    // 2.00 from the retired session + 1.00 from the new one; the status total agrees
    expect(rec.costUsd).toBeCloseTo(3);
    expect(fm.status.costUsd).toBeCloseTo(3);
  });
  it('a job that continues the session (a retry, a usage-limit resume) never rotates it', async () => {
    home = tempDir();
    repoPath = await demoRepo();
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { leadSession: { maxDays: 7, maxTurns: 2 } } }));
    h = makeForeman(home, ['--backend', 'claude', '--repo', repoPath, '--workers', 'kit', '--no-lead-review']);
    const fm = h.fm;
    const lead: Array<{ prompt: string; resume?: string; session: string }> = [];
    const queryFn = ({ prompt, options }: { prompt: string; options: Options }) => {
      const isLead = !(options.tools as string[]).includes('Edit');
      const s = options.resume ?? sid();
      if (isLead) lead.push({ prompt: String(prompt), session: s, ...(options.resume ? { resume: options.resume } : {}) });
      const nth = lead.length;
      async function* run(): AsyncGenerator<SDKMessage> {
        yield m({ type: 'system', subtype: 'init', session_id: s, model: 'fake' });
        if (isLead && String(prompt).startsWith('New goal')) await callTool(options, 'create_task', { title: 'Add a flag', assignee: 'kit' });
        if (!isLead) {
          fs.appendFileSync(path.join(options.cwd!, 'README.md'), '\nflag\n');
          await callTool(options, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
        }
        if (isLead && nth === 2) {
          // the goal-message turn dies on the network: one automatic retry resumes it
          yield m({ type: 'result', subtype: 'error_during_execution', is_error: true, num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [], errors: ['fetch failed'] });
          return;
        }
        yield m({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await fm.start(new ClaudeBackend(fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true, transientRetryMs: 50 }));
    const g = await fm.submitGoal('add a flag');
    await until(() => fm.goal(g.id)?.status === 'active' && lead.length === 1);
    fm.goalMessage(g.id, 'a question');
    await until(() => lead.length === 3, 10_000);
    expect(fm.store.data.sessions[`marlow:${g.id}`]!.sessionTurns).toBeGreaterThanOrEqual(2);
    expect(lead[2]!.resume).toBe(lead[0]!.session); // the retry continues the same session
    expect(lead[2]!.prompt).toMatch(/^Your last turn ended early/);
  });
});
