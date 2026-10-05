// Sim pull requests (contract S3, docs/PRWATCH.md): a goal in a sim repo that lands as PRs runs as a
// side flow whose approved task opens a PR on the sim's fake Azure DevOps host. The real PR watcher
// reads it: Task.pr goes open/pending -> passing -> approved -> merged, an automated "Claude Code
// Review" thread (the review pipeline's format) and a reviewer's thread go to the lead's triage turn,
// observe mode records the verdicts only, "on" mode sends a fold-in back to the worker (an added
// commit on the PR branch), posts replies after approval, and the PR merges on the host's timer ->
// task done -> goal done. Real git against a local bare "server"; no az / gh process ever starts.
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { afterAll, describe, expect, it, vi } from 'vitest';

const spawned = vi.hoisted(() => [] as string[][]);
vi.mock('../src/util/proc.js', async (importOriginal) => {
  const m = await importOriginal<typeof import('../src/util/proc.js')>();
  return {
    ...m,
    run: (async (cmd: string, args: string[], opts?: Parameters<typeof m.run>[2]) => {
      // a PR host CLI would reach the network: under the sim it must never be started
      if (/^(az|gh)(\.cmd|\.exe)?$/i.test(path.basename(cmd))) {
        spawned.push([cmd, ...args]);
        throw new Error(`the sim started ${cmd}`);
      }
      return m.run(cmd, args, opts);
    }) as typeof m.run,
  };
});

import { SimBackend } from '../src/agents/sim/index.js';
import { createSimPrRepo, simPrRepoSettings, simPrServerDir } from '../src/agents/sim/prdemo.js';
import { simReviewBody } from '../src/agents/sim/prhost.js';
import { parseAutomatedReview } from '../src/prreview.js';
import type { ClientMessage, Outbound, Task, TaskPr } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const PROJECT_ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..', '..');
type CreateDemo = (o: { dir: string; force?: boolean; quiet?: boolean }) => unknown;
async function prRepo(): Promise<string> {
  const mod = (await import(pathToFileURL(path.join(PROJECT_ROOT, 'sandbox', 'create-demo.mjs')).href)) as { createDemo: CreateDemo };
  const dir = path.join(tempDir('ac-simpr-'), 'pocket-api');
  createSimPrRepo(dir, mod.createDemo);
  return dir;
}

const bareGit = (bare: string, ...args: string[]) => execFileSync('git', ['--git-dir', bare, ...args], { encoding: 'utf8' }).trim();

const cleanup: string[] = [];
const open: Harness[] = [];
afterAll(async () => {
  for (const h of open) await h.fm.close();
  cleanup.forEach(rmrf);
});

async function boot(mode: 'observe' | 'on'): Promise<{ h: Harness; sim: SimBackend; api: string; apiId: string; prs: TaskPr[] }> {
  const home = tempDir();
  const main = await demoRepo();
  const api = await prRepo();
  cleanup.push(home, path.dirname(main), path.dirname(api));
  const h = makeForeman(home, ['--backend', 'sim', '--repo', `${main},${api}`, '--speed', '1000', '--no-ambient', '--auto-answer', '--pr-watch', mode]);
  open.push(h);
  simPrRepoSettings(h.cfg, api);
  const prs: TaskPr[] = [];
  h.fm.subscribe((m: Outbound) => {
    if (m.type === 'task.upsert' && m.task.pr) prs.push(m.task.pr);
  });
  const sim = new SimBackend(h.fm, h.cfg.sim);
  await h.fm.start(sim);
  const apiId = h.fm.repos.list().find((r) => r.path === api || path.basename(r.path) === 'pocket-api')!.id;
  return { h, sim, api, apiId, prs };
}

async function send(h: Harness, msg: Record<string, unknown>): Promise<Extract<Outbound, { type: 'ack' }>> {
  const replies: Outbound[] = [];
  await h.fm.handle({ v: 1, id: 'x', ...msg } as unknown as ClientMessage, (m) => replies.push(m));
  return replies.find((m) => m.type === 'ack') as Extract<Outbound, { type: 'ack' }>;
}

describe('sim review format', () => {
  it("is the review pipeline's format: the real parser reads its findings and verdict", () => {
    const at = new Date('2026-10-04T10:00:00Z');
    const first = parseAutomatedReview(simReviewBody(1, { title: 'Notes for g1', file: 'docs/goals/g1.md', files: 1, at }));
    expect(first?.verdict).toBe('WARN');
    expect(first?.findings.map((f) => f.severity)).toEqual(['important', 'minor', 'teachable', 'testing']);
    expect(first?.findings[0]).toMatchObject({ severity: 'important', file: 'docs/goals/g1.md', line: 5 });
    const later = parseAutomatedReview(simReviewBody(2, { title: 'Notes for g1', file: 'docs/goals/g1.md', files: 1, at }));
    expect(later?.verdict).toBe('PASS');
    expect(later?.findings.map((f) => f.severity)).toEqual(['minor', 'testing']);
  });
});

describe('sim pull requests', () => {
  it('observe: the PR is opened, reviewed, triaged (recorded only), approved and merged; the goal is done', async () => {
    const { h, sim, api, apiId, prs } = await boot('observe');
    // the demo repo pushes to its local bare server: no network remote
    expect(execFileSync('git', ['-C', api, 'remote', 'get-url', 'origin'], { encoding: 'utf8' }).trim()).toBe(simPrServerDir(api));
    const g = await h.fm.submitGoal('Document the export endpoint', apiId);
    expect(g.leadId).toBeUndefined(); // marlow runs a PR repo's goal as a side flow
    let task: Task | undefined;
    await until(() => !!(task = h.fm.tasks.forGoal(g.id).find((t) => t.status === 'pr')));
    expect(task!.pr).toMatchObject({ url: 'https://dev.azure.com/contoso/Notes/_git/pocket-api/pullrequest/601', id: 601, host: 'ado', target: 'main', status: 'open' });
    expect(prs[0]).toMatchObject({ id: 601, status: 'open', checks: 'pending', threads: { open: 0, new: 0 } });
    expect(h.fm.store.data.decisions.find((d) => d.kind === 'merge' && d.taskId === task!.id)!.question).toMatch(/^Open a pull request for t\d+ ".*" \(agentcraft\/.+ into main\)\?$/);

    // pr.refresh polls it now
    expect((await send(h, { type: 'pr.refresh', taskId: task!.id })).ok).toBe(true);
    // a goal message about it is answered by its lead
    expect((await send(h, { type: 'goal.message', goalId: g.id, text: 'How is the PR doing?' })).ok).toBe(true);

    await sim.idle();
    const t = h.fm.tasks.require(task!.id);
    expect(t.status).toBe('done');
    expect(h.fm.goal(g.id)!.status).toBe('done');
    expect(t.pr).toMatchObject({ status: 'merged', checks: 'passing' });
    expect(prs.some((p) => p.checks === 'passing' && p.status === 'open' && p.threads.open >= 2)).toBe(true);
    expect(prs.some((p) => p.status === 'approved')).toBe(true);

    const feed = h.fm.store.data.feed;
    expect(feed.filter((f) => f.kind === 'error')).toEqual([]);
    expect(feed.some((f) => /^PR #601 \(t\d+\): 1 thread with new comments, automated review WARN \(1 important, 1 testing, 1 minor\)\. Marlow triages 4 items \(observe mode\)$/.test(f.text))).toBe(true);
    expect(feed.some((f) => /^Marlow triaged PR #601 \(t\d+\): 1 fold in, 1 reply, 2 ignore \(observe mode: nothing posted, no fold-in\)$/.test(f.text))).toBe(true);
    expect(feed.some((f) => /^PR #601 merged: t\d+ ".*" is done$/.test(f.text))).toBe(true);
    expect(h.fm.memory.list().some((m) => m.title === `PR triage ${t.id} #601 (observe)`)).toBe(true);
    // nothing was written to the host, no fold-in, no watcher decision
    expect(sim.prHost.calls.filter((c) => c.args.includes('POST') || c.args.includes('PATCH'))).toEqual([]);
    expect(h.fm.store.data.decisions.filter((d) => d.taskId === t.id).map((d) => d.kind)).toEqual(['merge']);
    expect(sim.prHost.calls.every((c) => c.cmd === 'az')).toBe(true);
    expect(sim.prHost.calls.some((c) => c.args.slice(0, 3).join(' ') === 'repos pr create')).toBe(true);
    expect(spawned).toEqual([]);
  });

  it("on: a building lead triages, the worker folds the review in as an added commit, replies are posted after approval, then it merges", async () => {
    const { h, sim, api, apiId } = await boot('on');
    expect(h.fm.assignLead('World/b2', [apiId]).leadId).toBe('ines');
    const g = await h.fm.submitGoal('Document the export endpoint', apiId);
    expect(g.leadId).toBe('ines');
    let task: Task | undefined;
    await until(() => !!(task = h.fm.tasks.forGoal(g.id).find((t) => t.status === 'pr')));
    const branch = task!.pr!.branch;
    const server = simPrServerDir(api);

    await sim.idle();
    const t = h.fm.tasks.require(task!.id);
    expect(t.status).toBe('done');
    expect(h.fm.goal(g.id)!.status).toBe('done');

    // the fold-in landed as ONE added commit on top of the PR's first push
    const tip = bareGit(server, 'rev-parse', `refs/heads/${branch}`);
    expect(bareGit(server, 'log', '--format=%s', `main..${tip}`).split('\n')).toEqual([expect.stringMatching(/^Address review: /), `Notes for ${g.id}`]);
    expect(bareGit(server, 'diff', '--name-only', `${tip}^`, tip)).toBe(`docs/goals/${g.id}.md`);
    expect(bareGit(server, 'show', `${tip}:docs/goals/${g.id}.md`)).toMatch(/Review fixes \(PR round 1\): .*name the command that runs the tests/);

    const decisions = h.fm.store.data.decisions.filter((d) => d.taskId === t.id);
    expect(decisions.map((d) => d.question)).toEqual([
      expect.stringMatching(/^Open a pull request for /),
      expect.stringMatching(/^Post 2 replies and resolve 1 thread on PR #601\?$/),
      expect.stringMatching(/^Push the review fixes for t\d+ ".*" to PR #601\?$/),
    ]);
    expect(new Set(decisions.map((d) => d.agentId))).toEqual(new Set(['ines']));

    // the host got the replies (the "addressed" one after the fix landed) and the review thread is fixed
    const host = sim.prHost.pr(t.pr!.url)!;
    const review = host.threads.find((x) => x.comments[0]!.content.startsWith('**Claude Code Review**'))!;
    expect(review.status).toBe('fixed');
    expect(review.comments.at(-1)!.content).toMatch(new RegExp(`^Addressed in ${tip.slice(0, 7)}:`));
    const human = host.threads.find((x) => x.comments[0]!.author.displayName === 'Dana Reviewer')!;
    expect(human.comments.at(-1)!.content).toMatch(/^Good idea/);
    expect(host.reviews).toBe(2); // a new (PASS) review after the push: nothing above minor, no second round
    expect(h.fm.store.data.backend.prwatch).toMatchObject({ prs: { [t.id]: { reviewRounds: 1 } } });

    const feed = h.fm.store.data.feed;
    expect(feed.filter((f) => f.kind === 'error')).toEqual([]);
    expect(feed.some((f) => f.agentId === 'ines' && /^Ines triaged PR #601 \(t\d+\): 1 fold in, 1 reply, 2 ignore -> fold-in sent to /.test(f.text))).toBe(true);
    expect(feed.some((f) => /^PR #601: posted 1 reply, resolved 0 threads$/.test(f.text))).toBe(true);
    expect(feed.some((f) => /^PR #601: posted 1 reply, resolved 1 thread$/.test(f.text))).toBe(true);
    expect(feed.some((f) => /automated review PASS/.test(f.text))).toBe(true);
    expect(spawned).toEqual([]);
  });

  it('--sim-pr autostart: the PR goal runs beside the script, and a restart mid-PR picks it up again', async () => {
    const home = tempDir();
    const main = await demoRepo();
    const api = await prRepo();
    cleanup.push(home, path.dirname(main), path.dirname(api));
    // as main.ts registers them: pocket-api first, so the scripted demo repo stays the default
    const args = ['--backend', 'sim', '--repo', `${api},${main}`, '--speed', '1000', '--no-ambient', '--auto-answer', '--sim-pr', '--pr-watch', 'on'];
    const h = makeForeman(home, args);
    simPrRepoSettings(h.cfg, api);
    expect(h.cfg.sim.prDemo).toBe(true);
    const sim = new SimBackend(h.fm, h.cfg.sim);
    await h.fm.start(sim);
    const g = await sim.autostart();
    const prGoal = h.fm.store.data.goals.find((x) => x.id !== g!.id)!;
    expect(g!.repoId).toBe(h.fm.repos.defaultRepo()!.id);
    expect(path.basename(h.fm.repos.require(g!.repoId!).path)).toBe('demo-app');
    expect(prGoal.repoId).toBe(h.fm.repos.list()[0]!.id);
    // stop once the PR is open and its first review is in
    await until(() => (sim.prHost.prs()[0]?.reviews ?? 0) >= 1);
    await h.fm.close();

    const h2 = makeForeman(home, args);
    open.push(h2);
    simPrRepoSettings(h2.cfg, api);
    const sim2 = new SimBackend(h2.fm, h2.cfg.sim);
    await h2.fm.start(sim2);
    await sim2.idle();
    await until(() => h2.fm.goal(prGoal.id)!.status === 'done');
    expect(h2.fm.store.data.feed.filter((f) => f.kind === 'error')).toEqual([]);
    const t = h2.fm.tasks.forGoal(prGoal.id)[0]!;
    expect(t.pr).toMatchObject({ id: 601, status: 'merged' });
    expect(sim2.prHost.pr(t.pr!.url)!.status).toBe('completed');
    expect(spawned).toEqual([]);
  });
});
