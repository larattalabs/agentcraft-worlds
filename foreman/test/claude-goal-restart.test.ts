// Goal messages and plan notes across a full Foreman restart (a new Foreman on the same store, fake
// SDK). Nothing the user asked about a goal may be lost:
//  - a goal message still unread at shutdown is answered after the restart
//  - a goal-message turn the restart interrupted before the lead answered (with or without a session
//    for it) asks the lead again, with the message, and the answer lands in the goal's thread
//  - a goal-message turn interrupted after the lead answered resumes; nothing is asked or posted twice
//  - a goal-message turn held for the usage limit, waiting for its automatic retry or paused (in memory
//    only) is asked again after a restart, and handed on when its lead is released; without a restart
//    it continues its session and nothing is asked twice
//  - plans recorded before goals carried Goal.planId are linked at start (not over an existing planId,
//    not to a missing note)
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { RESUME_PROMPT } from '../src/agents/claude/prompts.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
const tools = (o: Options) => (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await tools(o)[name]!.handler(args, {})).content.map((c) => c.text).join('\n');

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const result = (s: string, text: string) => msg({ type: 'result', subtype: 'success', is_error: false, result: text, num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

interface Call {
  prompt: string;
  resume?: string;
}

/**
 * The lead: plans "New goal" prompts (a plan note and one task) unless the goal text says HOLD-PLAN
 * (then it hangs until aborted); answers "Message from" prompts with its final text "ANSWER: ...",
 * except (first run only) HANG (hangs after it has a session), EARLY (hangs before it has one) and
 * REPLY-THEN-HANG (answers with send_message, then hangs) and ASK (asks the user, then waits). A resumed
 * turn after an answer ("Earlier you asked") ends with "ANSWER after the question".
 */
function fake(calls: Call[], firstRun: boolean) {
  return ({ prompt, options }: { prompt: string; options: Options }) => {
    const p = String(prompt);
    calls.push({ prompt: p, ...(options.resume ? { resume: options.resume } : {}) });
    const s = options.resume ?? sid();
    const aborted = new Promise<void>((resolve) => options.abortController!.signal.addEventListener('abort', () => resolve(), { once: true }));
    async function* run(): AsyncGenerator<SDKMessage> {
      if (firstRun && p.startsWith('Message from') && p.includes('EARLY')) await aborted;
      if (options.abortController!.signal.aborted) return;
      yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake', cwd: '', tools: [] });
      const goal = /New goal from .*:\n"(.+)"/.exec(p)?.[1];
      let text = 'ok';
      if (goal || p === RESUME_PROMPT) {
        if (firstRun && goal?.includes('HOLD-PLAN')) await aborted;
        if (options.abortController!.signal.aborted) return;
        if (goal) await callTool(options, 'write_memory', { title: `Plan: ${goal}`, body: `PLAN ${goal}`, scope: 'shared' });
        if (goal || /no tasks/i.test(await callTool(options, 'list_tasks', {}))) await callTool(options, 'create_task', { title: `Task for ${goal ?? 'resumed plan'}`, description: 'x', assignee: 'kit' });
        text = p === RESUME_PROMPT ? 'RESUMED' : 'planned';
      } else if (p.startsWith('Earlier you asked')) {
        text = 'ANSWER after the question';
      } else if (/Message from \w+ about goal/.test(p)) {
        // (a lead taking the goal over gets a takeover note first)
        const asked = /Message from \w+ about goal [^\n]*:\n([\s\S]*?)\n\n/.exec(p)?.[1] ?? '';
        if (firstRun && asked.includes('REPLY-THEN-HANG')) {
          await callTool(options, 'send_message', { to: 'user', text: `Replied before the restart to: ${asked}` });
          await aborted;
          return;
        }
        if (firstRun && asked.includes('HANG')) await aborted;
        // (once per message) a usage limit refuses the turn; an overloaded API ends it early
        if ((asked.includes('LIMIT') || asked.includes('OVERLOAD')) && !failedOnce.has(asked)) {
          failedOnce.add(asked);
          if (asked.includes('LIMIT')) {
            yield msg({ type: 'rate_limit_event', session_id: s, rate_limit_info: { status: 'rejected', resetsAt: limitResetAt, rateLimitType: 'five_hour' } });
            yield msg({ type: 'result', subtype: 'success', is_error: true, result: 'Claude AI usage limit reached', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
          } else {
            yield msg({ type: 'result', subtype: 'error_during_execution', is_error: true, num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [], errors: ['API Error: 529 {"type":"error","error":{"type":"overloaded_error"}}'] });
          }
          return;
        }
        if (asked.includes('STOPME') && !stopped.has(asked)) {
          stopped.add(asked);
          await aborted;
          return;
        }
        if (firstRun && asked.includes('ASK')) {
          await Promise.race([callTool(options, 'ask_user', { question: 'Which parser?', options: ['regex', 'tokenizer'] }), aborted]);
          return;
        }
        if (options.abortController!.signal.aborted) return;
        text = `ANSWER: ${asked}`;
      }
      yield result(s, text);
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

/** STOPME messages already hung on once (the lead was stopped mid-answer) */
const stopped = new Set<string>();
/** LIMIT / OVERLOAD messages whose first turn already failed */
const failedOnce = new Set<string>();
/** when the usage limit of a LIMIT turn resets */
let limitResetAt = 0;

const cleanup: string[] = [];
afterAll(() => cleanup.forEach(rmrf));

async function boot(home: string, repo: string, calls: Call[], firstRun: boolean, transientRetryMs?: number): Promise<Harness> {
  const h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repo, '--no-lead-review']);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fake(calls, firstRun) as never, skipAuthCheck: true, ...(transientRetryMs !== undefined ? { transientRetryMs } : {}) }));
  return h;
}

/** A Foreman with one planned goal (kit off shift, so no worker turns run). */
async function planned(text = 'alpha', transientRetryMs?: number): Promise<{ h: Harness; home: string; repo: string; goalId: string; calls: Call[] }> {
  const home = tempDir();
  const repo = await demoRepo();
  cleanup.push(home, path.dirname(repo));
  const calls: Call[] = [];
  const h = await boot(home, repo, calls, true, transientRetryMs);
  await h.fm.agentAction('kit', 'stop');
  const g = await h.fm.submitGoal(text);
  if (!text.includes('HOLD-PLAN')) await until(() => h.fm.goal(g.id)!.status === 'active');
  return { h, home, repo, goalId: g.id, calls };
}

const repliesTo = (h: Harness, goalId: string) => h.fm.store.data.messages.filter((m) => m.from === 'marlow' && m.to === 'user' && m.goalId === goalId).map((m) => m.text);

describe('goal messages across a full Foreman restart', () => {
  it('a goal message still unread at shutdown is answered after the restart', async () => {
    const { h, home, repo, goalId, calls } = await planned('beta HOLD-PLAN');
    await until(() => calls.some((c) => c.prompt.includes('beta HOLD-PLAN')) && !!h.fm.store.data.sessions[`marlow:${goalId}`]?.sessionId);
    // the lead is busy planning: the message waits behind the plan
    h.fm.goalMessage(goalId, 'Which flag name?');
    await h.fm.close();
    expect(calls.some((c) => c.prompt.includes('Which flag name?'))).toBe(false);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, calls2, false);
    await until(() => repliesTo(h2, goalId).includes('ANSWER: Which flag name?'));
    expect(h2.fm.goal(goalId)!.status).toBe('active');
    const asked = calls2.filter((c) => c.prompt.includes('Which flag name?'));
    expect(asked).toHaveLength(1);
    expect(asked[0]!.resume).toBe(h2.fm.store.data.sessions[`marlow:${goalId}`]!.sessionId);
    expect(h2.fm.bus.goalInbox('marlow')).toEqual([]);
    await h2.fm.close();
  });

  it('a goal-message turn interrupted before the lead answered asks again (with a session for it, or before it had one)', async () => {
    for (const word of ['HANG', 'EARLY']) {
      const { h, home, repo, goalId, calls } = await planned(`gamma ${word}`);
      h.fm.goalMessage(goalId, `${word}: is it case-insensitive?`);
      await until(() => calls.some((c) => c.prompt.includes(`${word}: is it case-insensitive?`)));
      if (word === 'HANG') await until(() => (h.fm.store.data.backend.claude as { inflight: Record<string, { goalReply?: boolean }> }).inflight.marlow?.goalReply === true);
      // read by the interrupted turn
      expect(h.fm.bus.goalInbox('marlow')).toEqual([]);
      await h.fm.close();

      const calls2: Call[] = [];
      const h2 = await boot(home, repo, calls2, false);
      await until(() => repliesTo(h2, goalId).length > 0);
      expect(repliesTo(h2, goalId), word).toEqual([`ANSWER: ${word}: is it case-insensitive?`]);
      // asked again with the message itself, in the goal's lead session; no bare "continue" turn
      expect(calls2.filter((c) => c.prompt.includes(`${word}: is it case-insensitive?`)).map((c) => c.resume)).toEqual([h2.fm.store.data.sessions[`marlow:${goalId}`]!.sessionId]);
      expect(calls2.some((c) => c.prompt === RESUME_PROMPT)).toBe(false);
      expect(h2.fm.store.data.feed.find((f) => f.text === `ANSWER: ${word}: is it case-insensitive?`)).toMatchObject({ agentId: 'marlow', to: 'user', goalId });
      await h2.fm.close();
    }
  });

  it('a goal-message turn interrupted after the lead answered resumes; nothing is asked or posted twice', async () => {
    const { h, home, repo, goalId, calls } = await planned('delta');
    h.fm.goalMessage(goalId, 'REPLY-THEN-HANG status?');
    await until(() => repliesTo(h, goalId).length === 1);
    await h.fm.close();
    expect(calls.filter((c) => c.prompt.includes('REPLY-THEN-HANG'))).toHaveLength(1);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, calls2, false);
    await until(() => calls2.some((c) => c.prompt === RESUME_PROMPT));
    await until(() => !h2.fm.agent('marlow')!.activity.startsWith('Resuming') && (h2.fm.store.data.backend.claude as { inflight: Record<string, unknown> }).inflight.marlow === undefined);
    expect(calls2.find((c) => c.prompt === RESUME_PROMPT)!.resume).toBe(h2.fm.store.data.sessions[`marlow:${goalId}`]!.sessionId);
    expect(calls2.some((c) => c.prompt.includes('REPLY-THEN-HANG'))).toBe(false);
    // the lead had answered: its resumed turn's final text is not posted as a second answer
    expect(repliesTo(h2, goalId)).toEqual(['Replied before the restart to: REPLY-THEN-HANG status?']);
    await h2.fm.close();
  });
});

describe('a goal-message turn held for a usage limit or an automatic retry', () => {
  const inflight = (h: Harness) => (h.fm.store.data.backend.claude as { inflight: Record<string, unknown> }).inflight;

  it('is asked again after a restart during the usage-limit hold', async () => {
    const { h, home, repo, goalId, calls } = await planned('lambda');
    limitResetAt = Date.now() + 1500;
    h.fm.goalMessage(goalId, 'LIMIT: restart while held?');
    await until(() => h.fm.agent('marlow')!.activity.startsWith('usage limit'));
    // held in memory only, its inflight record gone: the message is unread again
    expect(inflight(h).marlow).toBeUndefined();
    expect(h.fm.bus.goalInbox('marlow').map((m) => m.text)).toEqual(['LIMIT: restart while held?']);
    await h.fm.close();

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, calls2, false);
    await until(() => repliesTo(h2, goalId).length > 0, 10_000);
    expect(repliesTo(h2, goalId)).toEqual(['ANSWER: LIMIT: restart while held?']);
    expect(calls.filter((c) => c.prompt.includes('LIMIT: restart while held?'))).toHaveLength(1);
    expect(calls2.filter((c) => c.prompt.includes('LIMIT: restart while held?'))).toHaveLength(1);
    expect(h2.fm.bus.goalInbox('marlow')).toEqual([]);
    await h2.fm.close();
  });

  it('is asked again after a restart during the automatic-retry delay', async () => {
    const { h, home, repo, goalId, calls } = await planned('mu', 60_000);
    h.fm.goalMessage(goalId, 'OVERLOAD: restart while waiting?');
    await until(() => /retrying/.test(h.fm.agent('marlow')!.activity));
    expect(inflight(h).marlow).toBeUndefined();
    expect(h.fm.bus.goalInbox('marlow').map((m) => m.text)).toEqual(['OVERLOAD: restart while waiting?']);
    await h.fm.close();
    expect(calls.filter((c) => c.prompt.includes('OVERLOAD: restart while waiting?'))).toHaveLength(1);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, calls2, false);
    await until(() => repliesTo(h2, goalId).length > 0);
    expect(repliesTo(h2, goalId)).toEqual(['ANSWER: OVERLOAD: restart while waiting?']);
    expect(calls2.filter((c) => c.prompt.includes('OVERLOAD: restart while waiting?'))).toHaveLength(1);
    await h2.fm.close();
  });

  it('is asked again after a restart while the lead is paused mid-answer', async () => {
    const { h, home, repo, goalId, calls } = await planned('omicron');
    h.fm.goalMessage(goalId, 'HANG: paused mid-answer?');
    await until(() => calls.some((c) => c.prompt.includes('HANG: paused mid-answer?')));
    await until(() => (h.fm.store.data.backend.claude as { inflight: Record<string, { goalReply?: boolean }> }).inflight.marlow?.goalReply === true);
    await h.fm.agentAction('marlow', 'pause');
    await until(() => inflight(h).marlow === undefined);
    expect(h.fm.bus.goalInbox('marlow').map((m) => m.text)).toEqual(['HANG: paused mid-answer?']);
    await h.fm.close();
    expect(calls.filter((c) => c.prompt.includes('HANG: paused mid-answer?'))).toHaveLength(1);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, calls2, false);
    if (h2.fm.agent('marlow')!.paused) await h2.fm.agentAction('marlow', 'resume');
    await until(() => repliesTo(h2, goalId).length > 0);
    expect(repliesTo(h2, goalId)).toEqual(['ANSWER: HANG: paused mid-answer?']);
    await h2.fm.close();
  });

  it('without a restart: the held turn continues its session and answers once; the message is not asked twice', async () => {
    const { h, goalId, calls } = await planned('nu', 50);
    const session = () => h.fm.store.data.sessions[`marlow:${goalId}`]!.sessionId;
    limitResetAt = Date.now() + 800;
    h.fm.goalMessage(goalId, 'LIMIT: no restart?');
    await until(() => calls.some((c) => c.prompt.startsWith('A usage limit stopped your last turn')), 10_000);
    await until(() => repliesTo(h, goalId).length === 1);
    h.fm.goalMessage(goalId, 'OVERLOAD: no restart?');
    await until(() => calls.some((c) => c.prompt.startsWith('Your last turn ended early')), 10_000);
    await until(() => repliesTo(h, goalId).length === 2 && inflight(h).marlow === undefined);
    expect(calls.filter((c) => c.prompt.includes('LIMIT: no restart?'))).toHaveLength(1);
    expect(calls.filter((c) => c.prompt.includes('OVERLOAD: no restart?'))).toHaveLength(1);
    expect(calls.filter((c) => /^(A usage limit stopped|Your last turn ended early)/.test(c.prompt)).map((c) => c.resume)).toEqual([session(), session()]);
    // taken by the held turns again: nothing left to ask
    expect(h.fm.bus.goalInbox('marlow')).toEqual([]);
    expect(calls.filter((c) => c.prompt.startsWith('Message from'))).toHaveLength(2);
    await h.fm.close();
  });

  it("released while held for the usage limit: the goal's new lead answers", async () => {
    const home = tempDir();
    const repo = await demoRepo();
    cleanup.push(home, path.dirname(repo));
    const calls: Call[] = [];
    const h = await boot(home, repo, calls, true);
    await h.fm.agentAction('kit', 'stop');
    expect(h.fm.assignLead('w/b1', [h.fm.repos.list()[0]!.id]).leadId).toBe('ines');
    const g = await h.fm.submitGoal('xi');
    await until(() => h.fm.goal(g.id)!.status === 'active');
    limitResetAt = Date.now() + 800;
    h.fm.goalMessage(g.id, 'LIMIT: who answers?');
    await until(() => h.fm.agent('ines')!.activity.startsWith('usage limit'));
    h.fm.releaseBuilding('w/b1');
    await until(() => repliesTo(h, g.id).length > 0, 10_000);
    expect(repliesTo(h, g.id)).toEqual(['ANSWER: LIMIT: who answers?']);
    expect(h.fm.store.data.messages.find((m) => m.text === 'LIMIT: who answers?')!.to).toBe('marlow');
    await h.fm.close();
  });
});

describe('a goal-message turn waiting on a question across a restart', () => {
  it('resumes with the answer as a goal-message turn (the message is not asked again)', async () => {
    const { h, home, repo, goalId, calls } = await planned('theta');
    h.fm.goalMessage(goalId, 'ASK: which parser should it use?');
    await until(() => h.fm.decisions.open().some((d) => d.kind === 'question' && d.agentId === 'marlow'));
    await h.fm.close();
    expect(calls.filter((c) => c.prompt.includes('ASK: which parser'))).toHaveLength(1);

    const calls2: Call[] = [];
    const h2 = await boot(home, repo, calls2, false);
    const q = h2.fm.decisions.open().find((d) => d.kind === 'question' && d.agentId === 'marlow')!;
    expect(q.goalId).toBe(goalId);
    expect(h2.fm.agent('marlow')!.state).toBe('waiting_user');
    await h2.fm.answerDecision(q.id, 'tokenizer');
    await until(() => repliesTo(h2, goalId).length > 0);
    expect(repliesTo(h2, goalId)).toEqual(['ANSWER after the question']);
    expect(calls2.some((c) => c.prompt.includes('ASK: which parser'))).toBe(false);
    expect(calls2.find((c) => c.prompt.startsWith('Earlier you asked'))!.resume).toBe(h2.fm.store.data.sessions[`marlow:${goalId}`]!.sessionId);
    await h2.fm.close();
  });
});

describe('a goal-message turn stopped mid-answer (no restart)', () => {
  it('leaves the message unread for the resume, which answers it', async () => {
    const { h, goalId, calls } = await planned('iota');
    h.fm.goalMessage(goalId, 'STOPME: one more thing?');
    await until(() => calls.some((c) => c.prompt.includes('STOPME: one more thing?')));
    expect(h.fm.bus.goalInbox('marlow')).toEqual([]);
    await h.fm.agentAction('marlow', 'stop');
    expect(h.fm.bus.goalInbox('marlow').map((m) => m.text)).toEqual(['STOPME: one more thing?']);
    await h.fm.agentAction('marlow', 'resume');
    await until(() => repliesTo(h, goalId).length > 0);
    expect(repliesTo(h, goalId)).toEqual(['ANSWER: STOPME: one more thing?']);
    expect(calls.filter((c) => c.prompt.includes('STOPME: one more thing?'))).toHaveLength(2);
    await h.fm.close();
  });
});

describe('a building lead released mid-answer (no restart)', () => {
  it("hands the goal message to the goal's new lead, who answers it", async () => {
    const home = tempDir();
    const repo = await demoRepo();
    cleanup.push(home, path.dirname(repo));
    const calls: Call[] = [];
    const h = await boot(home, repo, calls, true);
    await h.fm.agentAction('kit', 'stop');
    expect(h.fm.assignLead('w/b1', [h.fm.repos.list()[0]!.id]).leadId).toBe('ines');
    const g = await h.fm.submitGoal('kappa');
    expect(g.leadId).toBe('ines');
    await until(() => h.fm.goal(g.id)!.status === 'active');
    h.fm.goalMessage(g.id, 'STOPME: who takes this?');
    await until(() => calls.some((c) => c.prompt.includes('STOPME: who takes this?')));
    h.fm.releaseBuilding('w/b1');
    await until(() => h.fm.store.data.messages.some((m) => m.from === 'marlow' && m.to === 'user' && m.goalId === g.id));
    expect(h.fm.store.data.messages.filter((m) => m.from === 'marlow' && m.to === 'user' && m.goalId === g.id).map((m) => m.text)).toEqual(['ANSWER: STOPME: who takes this?']);
    expect(h.fm.store.data.messages.find((m) => m.text === 'STOPME: who takes this?')!.to).toBe('marlow');
    await h.fm.close();
  });
});

describe('plan notes recorded before goals carried planId', () => {
  it('are linked to their goals at start, never over an existing planId or to a missing note', async () => {
    const { h, home, repo, goalId } = await planned('epsilon');
    const g2 = await h.fm.submitGoal('zeta');
    await until(() => h.fm.goal(g2.id)!.status === 'active');
    const g3 = await h.fm.submitGoal('eta');
    await until(() => h.fm.goal(g3.id)!.status === 'active');
    const planOf = (id: string) => h.fm.goal(id)!.planId!;
    const [p1, p2, p3] = [planOf(goalId), planOf(g2.id), planOf(g3.id)];
    expect(new Set([p1, p2, p3]).size).toBe(3);
    // the state as an older Foreman left it: plans only in the backend's record
    const st = h.fm.store.data.backend.claude as { plans: Record<string, string> };
    for (const g of h.fm.store.data.goals) delete g.planId;
    st.plans = { [goalId]: p1, [g2.id]: 'shared/plan-that-was-deleted', [g3.id]: p3 };
    // g3 already links a note of its own (e.g. written with goal.plan): kept
    h.fm.store.data.goals.find((g) => g.id === g3.id)!.planId = p2;
    h.fm.store.markDirty();
    await h.fm.close();

    const h2 = await boot(home, repo, [], false);
    expect(h2.fm.goal(goalId)!.planId).toBe(p1);
    expect(h2.fm.goal(g2.id)!.planId).toBeUndefined();
    expect(h2.fm.goal(g3.id)!.planId).toBe(p2);
    // and the link reaches clients
    expect(h2.events.some((e) => e.type === 'goal.upsert' && e.goal.id === goalId && e.goal.planId === p1)).toBe(true);
    await h2.fm.close();
  });
});
