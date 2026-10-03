// Goals tab on the claude backend (fake SDK): goal.message runs in the goal's lead session and the
// reply lands in the goal's thread; standing instructions reach the lead and worker prompts (fresh
// each turn); the plan note becomes Goal.planId; goal.plan / goal.instructions reach the lead as goal
// messages; goal.cancel stops the lead's planning turn; Goal.branch drives "on <branch>:".
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { planPrompt } from '../src/agents/claude/prompts.js';
import type { ClientMessage, Outbound } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }>; isError?: boolean }> }> } };
const tools = (o: Options) => (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await tools(o)[name]!.handler(args, {})).content.map((c) => c.text).join('\n');

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const result = (s: string, text: string) => msg({ type: 'result', subtype: 'success', is_error: false, result: text, num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

interface Turn {
  who: string;
  prompt: string;
  system: string;
  resume?: string;
  session: string;
  aborted?: boolean;
}
const turns: Turn[] = [];
const holds = new Map<string, Promise<void>>();
/** what a lead does when it gets a goal message, by a word in the message */
const replyScript = new Map<string, (o: Options) => Promise<string>>();

function fakeQuery() {
  return ({ prompt, options }: { prompt: string; options?: Options }) => {
    const opts = options!;
    const p = String(prompt);
    const system = (opts.systemPrompt as { append?: string }).append ?? '';
    const who = /# You are (\w+),/.exec(system)?.[1]?.toLowerCase() ?? '?';
    // a resumed session keeps its id (as the real CLI does)
    const s = opts.resume ?? sid();
    const turn: Turn = { who, prompt: p, system, session: s, ...(opts.resume ? { resume: opts.resume } : {}) };
    turns.push(turn);
    const aborted = new Promise<void>((resolve) => opts.abortController!.signal.addEventListener('abort', () => resolve(), { once: true }));
    async function* run(): AsyncGenerator<SDKMessage> {
      yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake-model', cwd: '', tools: [] });
      const goal = /New goal from .*:\n"(.+)"/.exec(p)?.[1];
      let text = 'ok';
      if (goal) {
        const hold = holds.get(goal);
        if (hold) await Promise.race([hold, aborted]);
        if (opts.abortController!.signal.aborted) {
          turn.aborted = true;
          return;
        }
        await callTool(opts, 'write_memory', { title: `Plan: ${goal}`, body: `PLAN-BODY-${goal}`, scope: 'shared' });
        await callTool(opts, 'create_task', { title: `Task for ${goal}`, description: 'do it', assignee: 'kit' });
      } else if (p.startsWith('Message from')) {
        const key = [...replyScript.keys()].find((k) => p.includes(k));
        text = key ? await replyScript.get(key)!(opts) : 'Final answer text.';
      }
      await new Promise((r) => setTimeout(r, 20));
      yield result(s, text);
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

let h: Harness;
let home: string;
let repo: string;

beforeAll(async () => {
  home = tempDir();
  repo = await demoRepo();
  execFileSync('git', ['-C', repo, 'branch', 'feat/mine']);
  h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repo, '--no-lead-review']);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery() as never, skipAuthCheck: true }));
  await h.fm.agentAction('kit', 'pause');
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repo));
});

let c = 0;
async function send(m: Record<string, unknown>): Promise<{ ok: boolean; error?: string; result?: Record<string, unknown> }> {
  const replies: Outbound[] = [];
  await h.fm.handle({ v: 1, id: `c${++c}`, ...m } as unknown as ClientMessage, (x) => replies.push(x));
  return replies.find((x) => x.type === 'ack') as { ok: boolean; error?: string; result?: Record<string, unknown> };
}

const repoId = () => h.fm.repos.list()[0]!.id;

describe('Goals tab, claude backend', () => {
  it('records the plan note as Goal.planId and puts standing instructions into the lead prompt', async () => {
    const r = await send({ type: 'goal.submit', text: 'alpha', repoId: repoId(), instructions: ['Use tabs, not spaces'] });
    const g = h.fm.goal(r.result!.goalId as string)!;
    await until(() => h.fm.goal(g.id)!.status === 'active');
    expect(h.fm.goal(g.id)!.planId).toBe('shared/plan-alpha');
    const plan = turns.find((t) => t.prompt.includes('"alpha"'))!;
    expect(plan.system).toContain(`# Standing instructions for goal ${g.id}`);
    expect(plan.system).toContain('- Use tabs, not spaces');
    // the task it created carries them
    expect(h.fm.tasks.forGoal(g.id)[0]!.description).toBe('do it\n\nStanding instructions:\n- Use tabs, not spaces');
    // feed lines of the planning turn are the goal's
    expect(h.fm.store.data.feed.filter((f) => /Marlow (created|wrote memory|planned)/.test(f.text)).every((f) => f.goalId === g.id)).toBe(true);
  });

  it("goal.message runs in the goal's lead session; replies are the goal's thread", async () => {
    const ga = h.fm.goals().find((x) => x.text === 'alpha')!;
    const gb = await h.fm.submitGoal('beta', repoId());
    await until(() => h.fm.goal(gb.id)!.status === 'active');
    const session = h.fm.store.data.sessions[`marlow:${ga.id}`]!.sessionId!;
    // a reply with send_message
    replyScript.set('colour', async (o) => {
      await callTool(o, 'send_message', { to: 'user', text: 'Blue, as planned.' });
      return 'done';
    });
    const r = await send({ type: 'goal.message', goalId: ga.id, text: 'Which colour?' });
    expect(r).toMatchObject({ ok: true, result: { goalId: ga.id, leadId: 'marlow' } });
    await until(() => h.fm.store.data.feed.some((f) => f.text === 'Blue, as planned.'));
    const turn = turns.find((t) => t.prompt.includes('Which colour?'))!;
    expect(turn.prompt).toContain(`Message from Alex about goal ${ga.id} "alpha"`);
    expect(turn.resume).toBe(session);
    expect(h.fm.store.data.feed.find((f) => f.text === 'Which colour?')).toMatchObject({ kind: 'message', agentId: 'user', to: 'marlow', goalId: ga.id });
    expect(h.fm.store.data.feed.find((f) => f.text === 'Blue, as planned.')).toMatchObject({ kind: 'message', agentId: 'marlow', to: 'user', goalId: ga.id });
    // no send_message: the turn's final text is the reply
    const before = h.fm.store.data.feed.length;
    await send({ type: 'goal.message', goalId: gb.id, text: 'Status?' });
    await until(() => h.fm.store.data.feed.slice(before).some((f) => f.text === 'Final answer text.'));
    expect(h.fm.store.data.feed.slice(before).find((f) => f.text === 'Final answer text.')).toMatchObject({ agentId: 'marlow', to: 'user', goalId: gb.id });
    expect(turns.find((t) => t.prompt.includes('Status?'))!.resume).toBe(h.fm.store.data.sessions[`marlow:${gb.id}`]!.sessionId);
    // the messages never ride along with a turn for another goal
    for (const t of turns.filter((x) => x.prompt.includes('Which colour?'))) expect(t.prompt).toContain(`about goal ${ga.id}`);
    for (const t of turns.filter((x) => x.prompt.includes('Status?'))) expect(t.prompt).toContain(`about goal ${gb.id}`);
    // a plain user message still goes to the current goal's lead as before
    await send({ type: 'user.message', to: 'all', text: 'anything else?' });
    await until(() => turns.some((t) => t.prompt.includes('anything else?')));
  });

  it('goal.message for a done goal, and for a stopped lead after /resume', async () => {
    const ga = h.fm.goals().find((x) => x.text === 'alpha')!;
    h.fm.setGoal(ga.id, { status: 'done' });
    await send({ type: 'goal.message', goalId: ga.id, text: 'What did we ship?' });
    await until(() => turns.some((t) => t.prompt.includes('What did we ship?')));
    expect(turns.find((t) => t.prompt.includes('What did we ship?'))!.prompt).toMatch(/This goal is done/);
    await until(() => !h.fm.store.data.backend.claude || !(h.fm.store.data.backend.claude as { inflight: Record<string, unknown> }).inflight.marlow);
    // stopped: the message waits, the user is told; resume delivers it
    await h.fm.agentAction('marlow', 'stop');
    await send({ type: 'goal.message', goalId: ga.id, text: 'Are you there?' });
    expect(h.fm.store.data.feed.some((f) => f.agentId === 'marlow' && f.goalId === ga.id && /off shift/.test(f.text))).toBe(true);
    await new Promise((r) => setTimeout(r, 100));
    expect(turns.some((t) => t.prompt.includes('Are you there?'))).toBe(false);
    await h.fm.agentAction('marlow', 'resume');
    await until(() => turns.some((t) => t.prompt.includes('Are you there?')));
  });

  it('goal.instructions and goal.plan reach the lead as goal messages; workers see the instructions fresh each turn', async () => {
    const gb = h.fm.goals().find((x) => x.text === 'beta')!;
    await send({ type: 'goal.instructions', goalId: gb.id, instructions: ['Write tests first'] });
    await until(() => turns.some((t) => t.prompt.includes('The user changed the standing instructions')));
    const t1 = turns.find((t) => t.prompt.includes('The user changed the standing instructions'))!;
    expect(t1.prompt).toContain(`about goal ${gb.id}`);
    expect(t1.prompt).toContain('- Write tests first');
    expect(t1.system).toContain('- Write tests first');
    await send({ type: 'goal.plan', goalId: gb.id, body: 'PLAN-BODY-beta\n- and docs' });
    await until(() => turns.some((t) => t.prompt.includes('edited the plan')));
    const t2 = turns.find((t) => t.prompt.includes('edited the plan'))!;
    expect(t2.prompt).toContain(' PLAN-BODY-beta\n+- and docs');
    expect(h.fm.memory.get('shared/plan-beta')).toMatchObject({ author: 'user' });
    // a worker turn on the goal's task has the section; a change shows at its next turn
    const task = h.fm.tasks.forGoal(gb.id)[0]!;
    const before = turns.length;
    await h.fm.agentAction('kit', 'resume');
    await until(() => turns.slice(before).some((t) => t.who === 'kit' && t.prompt.includes(task.id)));
    const w1 = turns.slice(before).find((t) => t.who === 'kit')!;
    expect(w1.system).toContain('# Standing instructions');
    expect(w1.system).toContain('- Write tests first');
    h.fm.setGoalInstructions(gb.id, ['Write tests first', 'No new files']);
    // the worker ends without update_task: the nudge is its next turn
    await until(() => turns.slice(before).filter((t) => t.who === 'kit').length >= 2);
    const w2 = turns.slice(before).filter((t) => t.who === 'kit')[1]!;
    expect(w2.system).toContain('- No new files');
    await h.fm.agentAction('kit', 'pause');
  });

  it('goal.cancel stops the lead planning that goal; it stays cancelled', async () => {
    let release!: () => void;
    holds.set('gamma', new Promise<void>((r) => (release = r)));
    const g = await h.fm.submitGoal('gamma', repoId());
    await until(() => turns.some((t) => t.prompt.includes('"gamma"')));
    const r = await send({ type: 'goal.cancel', goalId: g.id });
    expect(r).toMatchObject({ ok: true, result: { goalId: g.id, cancelled: [] } });
    await until(() => turns.find((t) => t.prompt.includes('"gamma"'))!.aborted === true);
    release();
    await new Promise((res) => setTimeout(res, 150));
    expect(h.fm.goal(g.id)!.status).toBe('cancelled');
    expect(h.fm.tasks.forGoal(g.id)).toEqual([]);
    expect(h.fm.agent('marlow')!.activity).toBe('goal cancelled');
  });

  it('Goal.branch: "on <branch>:" or goal.submit branch; cleared when the branch cannot be used', async () => {
    const ok = await h.fm.submitGoal('on feat/mine: finish it', repoId());
    expect(h.fm.goal(ok.id)!.branch).toBe('feat/mine');
    const r = await send({ type: 'goal.submit', text: 'keep going', repoId: repoId(), branch: 'feat/nope' });
    const bad = r.result!.goalId as string;
    expect(h.fm.goal(bad)!.branch).toBeUndefined();
    expect(h.fm.store.data.feed.find((f) => /Not building on "feat\/nope"/.test(f.text))!.goalId).toBe(bad);
    expect(h.fm.store.data.feed.find((f) => /continues your branch feat\/mine/.test(f.text))!.goalId).toBe(ok.id);
  });

  it("the planning prompt names a multi-repo goal's repositories", () => {
    const base = { id: 'g9', text: 'cross', progress: 0, status: 'planning' as const, repoId: 'api', createdAt: 1, updatedAt: 1 };
    expect(planPrompt(h.fm, { ...base, repos: ['api', 'web'] }, '/x/api', 'main')).toContain('This goal is for the repositories api (primary), web: give each task the one repository it changes (create_task repo)');
    expect(planPrompt(h.fm, { ...base, repos: ['api'] }, '/x/api', 'main')).not.toContain('This goal is for');
  });
});
