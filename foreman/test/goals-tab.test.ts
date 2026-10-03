// Repos and Goals tabs (docs/HUB.md), Foreman core + sim backend: goal-tagged feed and decisions,
// goal.message / goal.instructions / goal.plan / goal.cancel / goal.digest / repo.remove, goal.submit
// repos / branch / instructions, Goal.repos / prs / planId, and the repo settings view.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { SimBackend } from '../src/agents/sim/index.js';
import type { ClientMessage, Outbound } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const cleanup: string[] = [];
const open: Harness[] = [];
afterAll(async () => {
  for (const h of open) await h.fm.close();
  cleanup.forEach(rmrf);
});

async function boot(repos: string[], args: string[] = [], config?: (repos: string[]) => unknown): Promise<{ h: Harness; sim: SimBackend }> {
  const home = tempDir();
  cleanup.push(home, ...repos.map((r) => path.dirname(r)));
  if (config) fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify(config(repos)));
  const h = makeForeman(home, ['--backend', 'sim', '--repo', repos.join(','), '--speed', '1000', '--no-ambient', ...args]);
  open.push(h);
  const sim = new SimBackend(h.fm, h.cfg.sim);
  await h.fm.start(sim);
  return { h, sim };
}

let n = 0;
/** Send a client message through Foreman.handle; resolves with its ack. */
async function send(h: Harness, msg: Record<string, unknown>): Promise<{ ok: boolean; error?: string; result?: Record<string, unknown> }> {
  const id = `c${++n}`;
  const replies: Outbound[] = [];
  await h.fm.handle({ v: 1, id, ...msg } as unknown as ClientMessage, (m) => replies.push(m));
  const ack = replies.find((m) => m.type === 'ack') as { ok: boolean; error?: string; result?: Record<string, unknown> } | undefined;
  if (!ack) throw new Error('no ack');
  return ack;
}

describe('Goals tab on the sim backend', () => {
  it('tags the scripted run\'s feed and decisions with the goal, records the plan, and digests it', async () => {
    const { h, sim } = await boot([await demoRepo()], ['--auto-answer']);
    const repo = h.fm.repos.list()[0]!.id;
    const t0 = Date.now() - 1;
    const g = await h.fm.submitGoal('Add #tags', repo);
    await sim.idle();
    const feed = h.fm.store.data.feed;
    // everything about the goal's tasks carries the goal
    const taskLines = feed.filter((f) => f.kind === 'task' || f.kind === 'merge' || f.kind === 'ci' || f.kind === 'plan');
    expect(taskLines.length).toBeGreaterThan(10);
    expect(taskLines.filter((f) => f.goalId !== g.id)).toEqual([]);
    expect(feed.find((f) => f.kind === 'goal' && f.text.startsWith('New goal'))!.goalId).toBe(g.id);
    expect(feed.filter((f) => f.kind === 'message' && f.goalId === g.id).length).toBeGreaterThan(3);
    // decisions about the goal
    const decisions = h.fm.store.data.decisions;
    expect(decisions.length).toBeGreaterThan(3);
    expect(decisions.filter((d) => d.goalId !== g.id)).toEqual([]);
    // the lead's plan note is the goal's plan
    const goal = h.fm.goal(g.id)!;
    expect(goal.planId).toMatch(/^shared\/plan/);
    expect(h.fm.memory.get(goal.planId!)!.title).toMatch(/^Plan:/);
    expect(goal.repos).toEqual([repo]);
    expect(goal.status).toBe('done');
    // digest: no model, every kind of interesting line, at most 30
    const r = await send(h, { type: 'goal.digest', since: t0 });
    expect(r.ok).toBe(true);
    const dg = r.result as { since: number; until: number; goals: Array<{ goalId: string; status: string; lines: Array<{ kind: string; taskId?: string }> }> };
    expect(dg.since).toBe(t0);
    expect(dg.goals.map((x) => x.goalId)).toEqual([g.id]);
    const kinds = new Set(dg.goals[0]!.lines.map((l) => l.kind));
    for (const k of ['merged', 'decision_answered', 'goal_done']) expect(kinds.has(k)).toBe(true);
    expect(dg.goals[0]!.lines.length).toBeLessThanOrEqual(30);
    expect(dg.goals[0]!.status).toBe('done');
    // nothing since now
    expect((await send(h, { type: 'goal.digest', since: Date.now() + 1 })).result).toMatchObject({ goals: [] });
    expect((await send(h, { type: 'goal.digest', since: 0, goalId: 'g99' })).ok).toBe(false);
  });

  it('goal.message: the goal\'s lead answers in the goal\'s thread, also for a done goal', async () => {
    const { h, sim } = await boot([await demoRepo()], ['--auto-answer']);
    const repo = h.fm.repos.list()[0]!.id;
    expect(h.fm.assignLead('World/b1', [repo]).leadId).toBe('ines');
    const g = await h.fm.submitGoal('Add #tags', repo);
    await sim.idle();
    expect(h.fm.goal(g.id)!.status).toBe('done');
    const r = await send(h, { type: 'goal.message', goalId: g.id, text: 'Is the parser case-insensitive?' });
    expect(r).toMatchObject({ ok: true, result: { goalId: g.id, leadId: 'ines' } });
    const mine = h.fm.store.data.feed.find((f) => f.text === 'Is the parser case-insensitive?')!;
    expect(mine).toMatchObject({ kind: 'message', agentId: 'user', to: 'ines', goalId: g.id });
    await until(() => h.fm.store.data.feed.some((f) => f.kind === 'message' && f.agentId === 'ines' && f.to === 'user' && f.goalId === g.id && /done/.test(f.text)));
    // the goal message is not in the lead's ordinary inbox
    expect(h.fm.bus.inbox('ines').some((m) => m.text.includes('case-insensitive'))).toBe(false);
    expect((await send(h, { type: 'goal.message', goalId: 'g42', text: 'x' })).ok).toBe(false);
    // the lead's building is gone: marlow answers for the done goal
    h.fm.releaseBuilding('World/b1');
    expect((await send(h, { type: 'goal.message', goalId: g.id, text: 'Who is still here?' })).result).toMatchObject({ leadId: 'marlow' });
    await until(() => h.fm.store.data.feed.some((f) => f.agentId === 'marlow' && f.to === 'user' && f.goalId === g.id));
  });

  it('goal.submit with repos, branch and instructions; goal.instructions; goal.plan', async () => {
    const { h } = await boot([await demoRepo(), await demoRepo()]);
    const [a, b] = h.fm.repos.list().map((r) => r.id);
    expect(h.fm.assignLead('World/b2', [b!, a!]).leadId).toBe('ines');
    const r = await send(h, { type: 'goal.submit', text: 'on feature/x: Wire it up', repos: [b, a], branch: 'feature/y', instructions: ['  No new dependencies ', '', 'Small commits'] });
    expect(r.ok).toBe(true);
    const g = h.fm.goal(r.result!.goalId as string)!;
    // repos[0] is the goal's repository and picks its lead; an explicit branch wins over the prefix
    expect(g).toMatchObject({ repoId: b, repos: [b, a], leadId: 'ines', branch: 'feature/y', instructions: ['No new dependencies', 'Small commits'] });
    // (the sim runs one script per lead: a second goal is made directly)
    const g2 = h.fm.createGoal('other thing', a);
    expect((await send(h, { type: 'goal.submit', text: 'x', repos: ['nope'] })).ok).toBe(false);
    expect((await send(h, { type: 'goal.submit', text: 'x', branch: 'bad branch' })).ok).toBe(false);

    // tasks get the standing instructions; Goal.repos follows the tasks' repositories
    const t = h.fm.tasks.create({ title: 'Do it', description: 'the work', createdBy: 'ines', goalId: g.id, repoId: a! });
    expect(t.description).toBe('the work\n\nStanding instructions:\n- No new dependencies\n- Small commits');
    const plain = h.fm.tasks.create({ title: 'No goal', createdBy: 'user' });
    expect(plain.description).toBeUndefined();

    // goal.instructions: replace, sent to the lead as a goal message
    const before = h.fm.store.data.messages.length;
    let ack = await send(h, { type: 'goal.instructions', goalId: g.id, instructions: ['No new dependencies', 'Keep output under 80 columns'] });
    expect(ack.result).toEqual({ goalId: g.id, changed: true });
    expect(h.fm.goal(g.id)!.instructions).toEqual(['No new dependencies', 'Keep output under 80 columns']);
    const sent = h.fm.store.data.messages.slice(before).find((m) => m.from === 'user')!;
    expect(sent).toMatchObject({ to: 'ines', goalId: g.id, goalMessage: true });
    expect(sent.text).toMatch(/^The user changed the standing instructions for this goal:\n- No new dependencies\n- Keep output under 80 columns/);
    ack = await send(h, { type: 'goal.instructions', goalId: g.id, instructions: ['No new dependencies', 'Keep output under 80 columns'] });
    expect(ack.result).toEqual({ goalId: g.id, changed: false });
    await send(h, { type: 'goal.instructions', goalId: g.id, instructions: [] });
    expect(h.fm.goal(g.id)!.instructions).toBeUndefined();

    // goal.plan: creates the note as the user, then edits it; the lead gets the diff
    ack = await send(h, { type: 'goal.plan', goalId: g.id, body: '# Plan\n\n- t1 wire it\n- t2 test it' });
    expect(ack.result).toMatchObject({ goalId: g.id, planId: `shared/plan-${g.id}`, changed: true });
    expect(h.fm.goal(g.id)!.planId).toBe(`shared/plan-${g.id}`);
    expect(h.fm.memory.get(`shared/plan-${g.id}`)).toMatchObject({ author: 'user', title: 'Plan: on feature/x: Wire it up' });
    ack = await send(h, { type: 'goal.plan', goalId: g.id, body: '# Plan\n\n- t1 wire it\n- t2 test it well\n- t3 docs' });
    expect(ack.result).toMatchObject({ changed: true });
    const diffMsg = h.fm.store.data.messages.filter((m) => m.goalMessage && m.goalId === g.id).pop()!;
    expect(diffMsg.text).toContain('-- t2 test it\n+- t2 test it well\n+- t3 docs');
    expect(diffMsg.text).toContain('@@ -1,4 +1,5 @@');
    // the player's thread shows the change, not the prompt the lead gets
    const threadLine = h.fm.store.data.feed.filter((f) => f.goalId === g.id && f.agentId === 'user').pop()!;
    expect(threadLine.text).toMatch(/^Edited the plan:\n```diff\n/);
    expect(threadLine.text).not.toContain('Adjust the tasks');
    expect(h.fm.store.data.feed.some((f) => f.goalId === g.id && f.text.startsWith('Changed the standing instructions:\n- No new dependencies'))).toBe(true);
    expect(h.fm.memory.get(`shared/plan-${g.id}`)!.body).toContain('t3 docs');
    expect((await send(h, { type: 'goal.plan', goalId: g.id, body: '# Plan\n\n- t1 wire it\n- t2 test it well\n- t3 docs' })).result).toMatchObject({ changed: false });
    // an existing plan the lead wrote keeps its id and title
    h.fm.memory.write({ scope: 'shared', title: 'Plan: other', body: 'old', author: 'ines', slug: 'plan-other' });
    h.fm.recordPlan(g2.id, 'shared/plan-other');
    ack = await send(h, { type: 'goal.plan', goalId: g2.id, body: 'new' });
    expect(ack.result).toMatchObject({ planId: 'shared/plan-other' });
    expect(h.fm.memory.get('shared/plan-other')).toMatchObject({ title: 'Plan: other', body: 'new', author: 'user' });
  });

  it('goal.cancel: open tasks and decisions cancelled, the script stops, the goal stays cancelled', async () => {
    const { h, sim } = await boot([await demoRepo()]);
    const repo = h.fm.repos.list()[0]!.id;
    const g = await h.fm.submitGoal('Add #tags', repo);
    // the script waits on its first decision (no auto-answer)
    await until(() => h.fm.decisions.open().length > 0 && h.fm.tasks.forGoal(g.id).some((t) => t.status === 'doing'));
    const openTasks = h.fm.tasks.forGoal(g.id).filter((t) => t.status !== 'done' && t.status !== 'cancelled').map((t) => t.id);
    const r = await send(h, { type: 'goal.cancel', goalId: g.id });
    expect(r.ok).toBe(true);
    expect(r.result).toEqual({ goalId: g.id, cancelled: openTasks });
    expect(h.fm.tasks.forGoal(g.id).filter((t) => t.status !== 'done' && t.status !== 'cancelled')).toEqual([]);
    expect(h.fm.decisions.open()).toEqual([]);
    await sim.idle();
    await new Promise((res) => setTimeout(res, 50));
    expect(h.fm.goal(g.id)!.status).toBe('cancelled');
    expect(h.fm.store.data.feed.some((f) => /^Goal closed/.test(f.text))).toBe(false);
    expect(h.fm.store.data.feed.some((f) => f.goalId === g.id && /cancelled goal/.test(f.text))).toBe(true);
    // again: nothing to do; a done goal cannot be cancelled
    expect((await send(h, { type: 'goal.cancel', goalId: g.id })).result).toEqual({ goalId: g.id, cancelled: [] });
    h.fm.setGoal(g.id, { status: 'done' });
    expect((await send(h, { type: 'goal.cancel', goalId: g.id })).error).toMatch(/already done/);
  });

  it('repo.remove: refused with open tasks or a planning goal, then unregisters', async () => {
    const { h } = await boot([await demoRepo(), await demoRepo()]);
    const [a, b] = h.fm.repos.list().map((r) => r.id);
    const g = h.fm.createGoal('x', a);
    expect((await send(h, { type: 'repo.remove', repoId: a })).error).toMatch(/still being planned/);
    h.fm.setGoal(g.id, { status: 'active' });
    const t = h.fm.tasks.create({ title: 'open one', createdBy: 'user', goalId: g.id, repoId: a! });
    expect((await send(h, { type: 'repo.remove', repoId: a })).error).toMatch(/open tasks \(t\d+ todo\)/);
    h.fm.tasks.setStatus(t.id, 'cancelled', { force: true });
    expect(await send(h, { type: 'repo.remove', repoId: a })).toMatchObject({ ok: true, result: { repoId: a } });
    expect(h.fm.repos.list().map((r) => r.id)).toEqual([b]);
    expect((h.fm.snapshot() as { repos: Array<{ id: string }> }).repos.map((r) => r.id)).toEqual([b]);
    expect((await send(h, { type: 'repo.remove', repoId: a })).ok).toBe(false);
  });

  it('Repo.settings: a read-only view without env values', async () => {
    const { h } = await boot([await demoRepo(), await demoRepo()], [], (repos) => ({
      repoSettings: {
        [repos[0]!]: {
          land: 'pr',
          baseBranch: 'main',
          ci: 'npm test',
          setup: 'npm ci',
          pr: { remote: 'origin', branchPrefix: 'feat/', draft: true },
          protect: ['.env'],
          roles: { kit: 'backend' },
          prReview: { maxRounds: 4 },
          env: { API_TOKEN: 'super-secret-value', NODE_OPTIONS: '--max-old-space-size=4096' },
          copy: ['.env'],
        },
      },
    }));
    const snap = h.fm.snapshot() as { repos: Array<{ id: string; settings?: unknown }> };
    const [r1, r2] = snap.repos;
    expect(r1!.settings).toEqual({
      land: 'pr',
      baseBranch: 'main',
      ci: 'npm test',
      setup: 'npm ci',
      pr: { remote: 'origin', branchPrefix: 'feat/', draft: true },
      protect: ['.env'],
      roles: { kit: 'backend' },
      prReview: { autoSeverities: ['critical', 'important'], maxRounds: 4 },
      envKeys: ['API_TOKEN', 'NODE_OPTIONS'],
    });
    expect(JSON.stringify(snap)).not.toContain('super-secret-value');
    expect(r2!.settings).toEqual({ land: 'merge', protect: [], roles: {}, prReview: { autoSeverities: ['critical', 'important'], maxRounds: 2 } });
    // repo.upsert carries it too; state.json does not store it
    h.events.length = 0;
    h.fm.repos.setCi(r1!.id, 'pass');
    const up = h.events.find((e) => e.type === 'repo.upsert') as { repo: { settings?: unknown } } | undefined;
    expect(up!.repo.settings).toBeTruthy();
    expect(h.fm.store.data.repos.every((r) => r.settings === undefined)).toBe(true);
  });

  it('Goal.prs and Goal.repos follow the goal\'s tasks', async () => {
    const { h } = await boot([await demoRepo(), await demoRepo()]);
    const [a, b] = h.fm.repos.list().map((r) => r.id);
    const g = h.fm.createGoal('cross-repo', a);
    h.fm.setGoal(g.id, { status: 'active' });
    const t1 = h.fm.tasks.create({ title: 'b side', createdBy: 'marlow', goalId: g.id, repoId: b! });
    h.fm.tasks.create({ title: 'a side', createdBy: 'marlow', goalId: g.id, repoId: a! });
    h.fm.tasks.update(t1.id, { pr: { url: 'https://github.com/o/r/pull/7', id: 7, host: 'github', branch: 'x', target: 'main', status: 'open', checks: 'pending', threads: { open: 0, new: 0 }, updatedAt: 1 } });
    await until(() => !!h.fm.goal(g.id)!.prs);
    expect(h.fm.goal(g.id)).toMatchObject({ repos: [a, b], prs: [{ taskId: t1.id, url: 'https://github.com/o/r/pull/7', id: 7, status: 'open' }] });
    h.fm.tasks.update(t1.id, { pr: { ...h.fm.tasks.get(t1.id)!.pr!, status: 'merged' } });
    await until(() => h.fm.goal(g.id)!.prs?.[0]?.status === 'merged');
  });
});
