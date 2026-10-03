// Watching a task's pull request to completion (docs/PRWATCH.md), end to end in the claude backend
// with a fake SDK and a fake PR host: landing as a PR leaves the task in `pr`; new threads and an
// automated review go to the lead's triage turn; fold-ins go back to the worker and land as an added
// commit; replies/resolutions are posted only after the user's approval (exact az commands
// asserted); merged -> done -> goal done. Observe mode posts nothing and starts nothing.
// Real git against a local bare "server". No network: every az/gh call goes to the fake runner.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import type { RunFn } from '../src/prs.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
async function callTool(options: Options, name: string, args: Record<string, unknown>): Promise<string> {
  const server = options.mcpServers!.agentcraft as unknown as ToolServer;
  const res = await server.instance._registeredTools[name]!.handler(args, {});
  return res.content.map((c) => c.text).join('\n');
}

let n = 0;
const sid = (k: string) => `00000000-0000-4000-8000-${k.padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(String(++n)), ...o }) as unknown as SDKMessage;
const init = (session: string) => msg({ type: 'system', subtype: 'init', session_id: session, model: 'fake-model', cwd: '', tools: [] });
const result = (session: string, text = 'done') => msg({ type: 'result', subtype: 'success', is_error: false, result: text, num_turns: 1, total_cost_usd: 0.001, session_id: session, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

const fixture = (name: string) => fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures', 'claude-review', name), 'utf8');
const g = (cwd: string, ...args: string[]) => execFileSync('git', ['-C', cwd, '-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { encoding: 'utf8' }).trim();
const BOT = { displayName: 'Project Collection Build Service (contoso)', uniqueName: 'Build\\contoso' };
const DANA = { displayName: 'Dana Reviewer', uniqueName: 'dana@contoso.com' };
const PR_URL = 'https://dev.azure.com/contoso/Notes/_git/demo-app/pullrequest/612';
const ORG = 'https://dev.azure.com/contoso';

interface AdoThread {
  id: number;
  status?: string;
  threadContext?: { filePath: string; rightFileStart: { line: number } } | null;
  comments: Array<{ id: number; content: string; commentType: string; author: { displayName: string; uniqueName?: string }; publishedDate?: string }>;
}
type Call = { cmd: string; args: string[]; body?: Record<string, unknown> };

/** A fake Azure DevOps behind `az`: PR state, threads (replies are added to them), policies. */
function fakeHost() {
  const host = {
    pr: { status: 'active', isDraft: false, reviewers: [] as Array<{ displayName: string; vote: number }>, repository: { id: 'guid-1' } } as Record<string, unknown>,
    threads: [] as AdoThread[],
    policies: [] as unknown[],
    calls: [] as Call[],
    fail: false,
    nextComment: 100,
  };
  const ok = (o: unknown) => ({ code: 0, stdout: JSON.stringify(o), stderr: '', timedOut: false });
  const run = (async (cmd: string, args: string[]) => {
    const i = args.indexOf('--in-file');
    const body = i >= 0 ? (JSON.parse(fs.readFileSync(args[i + 1]!, 'utf8')) as Record<string, unknown>) : undefined;
    host.calls.push({ cmd, args, ...(body ? { body } : {}) });
    if (host.fail) return { code: 1, stdout: '', stderr: 'ERROR: Failed to establish a new connection', timedOut: false };
    if (cmd !== 'az') throw new Error(`unexpected ${cmd}`);
    if (args[0] === 'repos' && args[2] === 'show') return ok(host.pr);
    if (args[0] === 'repos' && args[2] === 'policy') return ok(host.policies);
    if (args[0] === 'repos' && args[2] === 'create') throw new Error('the test seeds the PR; nothing may open one');
    const threadId = Number(args.find((a) => a.startsWith('threadId='))?.slice(9));
    if (args.includes('GET')) return ok({ count: host.threads.length, value: host.threads });
    if (args.includes('POST')) {
      const id = ++host.nextComment;
      host.threads.find((t) => t.id === threadId)!.comments.push({ id, content: String(body!.content), commentType: 'text', author: { displayName: 'Alex Example', uniqueName: 'alex@contoso.com' } });
      return ok({ id, content: body!.content });
    }
    if (args.includes('PATCH')) {
      host.threads.find((t) => t.id === threadId)!.status = String(body!.status);
      return ok({ id: threadId, status: body!.status });
    }
    throw new Error(`unexpected az ${args.join(' ')}`);
  }) as RunFn;
  return { host, run };
}

/** argv with the temp --in-file path replaced, for exact comparisons. */
const argv = (c: Call) => [c.cmd, ...c.args.map((a, i) => (c.args[i - 1] === '--in-file' ? '<file>' : a))];

interface World {
  h: Harness;
  backend: ClaudeBackend;
  host: ReturnType<typeof fakeHost>['host'];
  home: string;
  repo: string;
  bare: string;
  prompts: Array<{ agent: string; prompt: string }>;
  triage: (opts: Options, prompt: string) => Promise<void>;
}

function world(mode: 'on' | 'observe', triage: World['triage']): World {
  const w = { prompts: [], triage } as unknown as World;
  const saved = { count: process.env.GIT_CONFIG_COUNT, key: process.env.GIT_CONFIG_KEY_0, value: process.env.GIT_CONFIG_VALUE_0 };
  beforeAll(async () => {
    process.env.GIT_CONFIG_COUNT = '1';
    process.env.GIT_CONFIG_KEY_0 = 'protocol.file.allow';
    process.env.GIT_CONFIG_VALUE_0 = 'always';
    w.home = tempDir();
    w.repo = await demoRepo();
    w.bare = path.join(tempDir(), 'server.git');
    execFileSync('git', ['init', '-q', '--bare', w.bare]);
    g(w.repo, 'remote', 'add', 'origin', w.bare);
    g(w.repo, 'push', '-q', 'origin', 'HEAD:refs/heads/dev');
    fs.writeFileSync(path.join(w.home, 'config.json'), JSON.stringify({ repoSettings: { [w.repo]: { baseBranch: 'dev', land: 'pr', pr: { branchPrefix: 'feat/', squash: true } } } }));
    w.h = makeForeman(w.home, ['--backend', 'claude', '--workers', 'kit', '--repo', w.repo, '--pr-watch', mode, '--pr-poll-seconds', '600']);
    const fake = fakeHost();
    w.host = fake.host;
    w.backend = new ClaudeBackend(w.h.fm, w.h.cfg.claude, { queryFn: fakeQuery(w) as never, skipAuthCheck: true, prRunFn: fake.run });
    await w.h.fm.start(w.backend);
  });
  afterAll(async () => {
    await w.h.fm.close();
    for (const [k, v] of [['GIT_CONFIG_COUNT', saved.count], ['GIT_CONFIG_KEY_0', saved.key], ['GIT_CONFIG_VALUE_0', saved.value]] as const) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
    rmrf(w.home);
    rmrf(path.dirname(w.repo));
    rmrf(path.dirname(w.bare));
  });
  return w;
}

function fakeQuery(w: World) {
  return ({ prompt, options }: { prompt: string | AsyncIterable<unknown>; options?: Options }) => {
    const opts = options!;
    const p = String(prompt);
    const cwd = opts.cwd!;
    const agent = cwd.includes(`${path.sep}kit-`) ? 'kit' : 'marlow';
    w.prompts.push({ agent, prompt: p });
    async function* run(): AsyncGenerator<SDKMessage> {
      const s = sid(agent === 'kit' ? '2001' : '1001');
      yield init(s);
      if (p.startsWith('New goal')) {
        await callTool(opts, 'create_task', { title: 'Tag filter', description: 'filter by tag', assignee: 'kit' });
      } else if (/^Your task: (t\d+)/.test(p)) {
        fs.writeFileSync(path.join(cwd, 'FILTER.md'), 'tag filter\n');
        await callTool(opts, 'update_task', { task_id: /^Your task: (t\d+)/.exec(p)![1], status: 'review', summary: 'Adds the filter.' });
      } else if (p.startsWith('Review fixes for t1')) {
        fs.writeFileSync(path.join(cwd, 'FILTER.md'), 'tag filter\nwith auth\n');
        await callTool(opts, 'update_task', { task_id: 't1', status: 'review', summary: 'Added the auth check; renamed.' });
      } else if (/^Review request: (t\d+)/.test(p)) {
        await callTool(opts, 'request_merge', { task_id: /^Review request: (t\d+)/.exec(p)![1], summary: 'ok' });
      } else if (p.startsWith('Triage request')) {
        await w.triage(opts, p);
      }
      yield result(s, 'ok');
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

/** Plan -> work -> review -> the user approves the PR. The PR "opens" (seeded: the remote is a local path). */
async function landAsPr(w: World) {
  const fm = w.h.fm;
  await fm.submitGoal('Add a tag filter');
  await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 60_000);
  const d = fm.decisions.open().find((x) => x.kind === 'merge' && x.taskId === 't1')!;
  expect(d.question).toMatch(/^Open a pull request for t1/);
  fm.store.data.worktreeMeta[`demo-app/${d.worktree}`]!.prUrl = PR_URL;
  await fm.answerDecision(d.id, 'Merge');
}

describe('PR watching (on)', () => {
  let triageItems = '';
  const w = world('on', async (opts, prompt) => {
    triageItems = prompt;
    const refs = [...prompt.matchAll(/^\[(t1\/[^\]]+)\]/gm)].map((m) => m[1]!);
    const bad = await callTool(opts, 'triage', { items: [{ ref: 't9/thread-1', verdict: 'ignore', note: 'x' }] });
    expect(bad).toMatch(/No pending triage item matches t9\/thread-1/);
    const verdicts = refs.map((ref) =>
      ref === 't1/review-11.1'
        ? { ref, verdict: 'fold_in', note: 'Add [Authorize(Roles = "Admin")] to Export' }
        : ref === 't1/review-11.2'
          ? { ref, verdict: 'reply', note: 'CsvHelper escapes fields already; see CsvWriter.cs:17.' }
          : ref === 't1/thread-12'
            ? { ref, verdict: 'fold_in', note: 'Rename the filter param to tags' }
            : ref === 't1/thread-13'
              ? { ref, verdict: 'reply', note: 'Good question: it is kept for the mobile app.' }
              : { ref, verdict: 'ignore', note: 'not worth it' },
    );
    await callTool(opts, 'triage', { items: verdicts });
  });

  it('keeps the task in pr, triages review + comments, folds in, posts after approval, finishes on merge', async () => {
    const fm = w.h.fm;
    const host = w.host;
    await landAsPr(w);
    const t = () => fm.tasks.get('t1')!;
    expect(t().status).toBe('pr');
    expect(t().pr).toMatchObject({ url: PR_URL, id: 612, host: 'ado', status: 'open', target: 'dev' });
    expect(fm.goal('g1')!.status).toBe('active'); // not done while the PR is open
    expect(fm.store.data.feed.some((f) => /watching it until it is merged/.test(f.text))).toBe(true);

    // a quiet poll: nothing to triage
    await w.backend.prs.poll('t1');
    expect(argv(host.calls[0]!)).toEqual(['az', 'repos', 'pr', 'show', '--id', '612', '--org', ORG, '-o', 'json', '--only-show-errors']);
    expect(argv(host.calls[1]!)).toEqual(['az', 'devops', 'invoke', '--org', ORG, '--area', 'git', '--resource', 'pullRequestThreads', '--route-parameters', 'project=Notes', 'repositoryId=guid-1', 'pullRequestId=612', '--http-method', 'GET', '--api-version', '7.1', '-o', 'json', '--only-show-errors']);
    expect(argv(host.calls[2]!)).toEqual(['az', 'repos', 'pr', 'policy', 'list', '--id', '612', '--org', ORG, '-o', 'json', '--only-show-errors']);
    expect(w.prompts.some((x) => x.prompt.startsWith('Triage request'))).toBe(false);

    // the automated review, a reviewer's comments, and the threads to ignore
    host.threads.push(
      { id: 10, comments: [{ id: 1, content: 'Alex updated the pull request status', commentType: 'system', author: { displayName: 'Microsoft.VisualStudio.Services.TFS' } }] },
      { id: 11, status: 'active', threadContext: null, comments: [{ id: 1, content: fixture('fail.md'), commentType: 'text', author: BOT, publishedDate: '2026-10-03T15:04:05Z' }] },
      { id: 12, status: 'active', threadContext: { filePath: '/FILTER.md', rightFileStart: { line: 1 } }, comments: [{ id: 1, content: 'Call the param `tags`, please.', commentType: 'text', author: DANA }] },
      { id: 13, status: 'active', threadContext: null, comments: [{ id: 1, content: 'Why keep the old endpoint?', commentType: 'text', author: DANA }] },
      { id: 14, status: 'active', threadContext: null, comments: [{ id: 1, content: '<!-- changelog-draft -->\n## Changelog draft', commentType: 'text', author: BOT }] },
    );
    host.calls.length = 0;
    await w.backend.prs.poll('t1');
    expect(t().pr).toMatchObject({ threads: { open: 3, new: 3 } });
    await until(() => !!fm.decisions.open().find((d) => /on PR #612\?$/.test(d.question)), 30_000);
    // the lead saw the items, the conversation and the diff
    expect(triageItems).toContain('[t1/review-11.1] automated review, critical Pocket.Api/Controllers/ExportController.cs:42 (default: fold_in)');
    expect(triageItems).toContain('[t1/thread-12] comment by Dana Reviewer FILTER.md:1');
    expect(triageItems).toContain('FILTER.md');
    expect(triageItems).not.toContain('changelog');
    expect(triageItems).not.toMatch(/\[t1\/review-11\.\d+\][^\n]*teachable/);

    // ONE decision for the replies/resolutions; the fold-in already went back to Kit
    const post = fm.decisions.open().find((d) => /on PR #612\?$/.test(d.question))!;
    expect(post.question).toBe('Post 4 replies and resolve 2 threads on PR #612?');
    expect(post.context).toContain('reply in thread 13 (Dana Reviewer): "Good question: it is kept for the mobile app."');
    expect(post.context).toContain('after the fix lands: reply "Addressed in <commit>: Rename the filter param to tags" and resolve thread 12 as fixed');
    expect(post.options).toEqual(['Post', 'Skip']);
    expect(host.calls.filter((c) => c.args.includes('POST') || c.args.includes('PATCH'))).toEqual([]); // nothing before the approval
    await until(() => w.prompts.some((x) => x.agent === 'kit' && x.prompt.startsWith('Review fixes for t1')), 30_000);
    const fold = w.prompts.find((x) => x.agent === 'kit' && x.prompt.startsWith('Review fixes for t1'))!.prompt;
    expect(fold).toContain('#612');
    expect(fold).toContain('Add [Authorize(Roles = "Admin")] to Export');
    expect(fold).toContain('Rename the filter param to tags');

    // the user approves the posts while the fold-in runs: replies now, the fixed threads after the push
    await fm.answerDecision(post.id, 'Post');
    await until(() => host.calls.filter((c) => c.args.includes('POST')).length === 2);
    const posted = host.calls.filter((c) => c.args.includes('POST') || c.args.includes('PATCH'));
    expect(posted.map((c) => [c.args.find((a) => a.startsWith('threadId=')), c.args[c.args.indexOf('--http-method') + 1], c.body])).toEqual([
      ['threadId=13', 'POST', { content: 'Good question: it is kept for the mobile app.', parentCommentId: 1, commentType: 1 }],
      ['threadId=11', 'POST', { content: expect.stringMatching(/^Notes on the automated review:\n- Pocket\.Api\/Services\/CsvWriter\.cs:17: Values are not escaped, .*…\n {2}CsvHelper escapes fields already; see CsvWriter\.cs:17\.$/), parentCommentId: 1, commentType: 1 }],
    ]);
    expect(argv(posted[0]!)).toEqual(['az', 'devops', 'invoke', '--org', ORG, '--area', 'git', '--resource', 'pullRequestThreadComments', '--route-parameters', 'project=Notes', 'repositoryId=guid-1', 'pullRequestId=612', 'threadId=13', '--http-method', 'POST', '--in-file', '<file>', '--api-version', '7.1', '-o', 'json', '--only-show-errors']);

    // the fold-in comes back through CI + lead review as a follow-up, then the user's approval
    await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 60_000);
    const review = w.prompts.filter((x) => x.agent === 'marlow' && x.prompt.startsWith('Review request: t1')).pop()!.prompt;
    expect(review).toContain('This is a follow-up on the open pull request #612');
    expect(review).toContain('Diff of the follow-up (1 files');
    const m2 = fm.decisions.open().find((d) => d.kind === 'merge' && d.taskId === 't1')!;
    expect(m2.question).toBe('Push the review fixes for t1 "Tag filter" to PR #612?');
    const meta = fm.store.data.worktreeMeta[`demo-app/${m2.worktree}`]!;
    const before = meta.prPushedSha!;
    host.calls.length = 0;
    await fm.answerDecision(m2.id, 'Merge');
    expect(t().status).toBe('pr');
    const tip = execFileSync('git', ['--git-dir', w.bare, 'rev-parse', `refs/heads/${meta.prBranch}`], { encoding: 'utf8' }).trim();
    expect(execFileSync('git', ['--git-dir', w.bare, 'rev-parse', `${tip}^`], { encoding: 'utf8' }).trim()).toBe(before); // an added commit
    // now the fixed threads are answered and resolved, with the pushed commit
    await until(() => host.calls.filter((c) => c.args.includes('PATCH')).length === 2);
    const after = host.calls.filter((c) => c.args.includes('POST') || c.args.includes('PATCH'));
    expect(after.map((c) => [c.args.find((a) => a.startsWith('threadId=')), c.args[c.args.indexOf('--http-method') + 1], c.body])).toEqual([
      ['threadId=12', 'POST', { content: `Addressed in ${tip.slice(0, 7)}: Rename the filter param to tags`, parentCommentId: 1, commentType: 1 }],
      ['threadId=12', 'PATCH', { status: 'fixed' }],
      ['threadId=11', 'POST', { content: `Addressed in ${tip.slice(0, 7)}:\n- Pocket.Api/Controllers/ExportController.cs:42: Add [Authorize(Roles = "Admin")] to Export`, parentCommentId: 1, commentType: 1 }],
      ['threadId=11', 'PATCH', { status: 'fixed' }],
    ]);
    expect(argv(after[1]!)).toEqual(['az', 'devops', 'invoke', '--org', ORG, '--area', 'git', '--resource', 'pullRequestThreads', '--route-parameters', 'project=Notes', 'repositoryId=guid-1', 'pullRequestId=612', 'threadId=12', '--http-method', 'PATCH', '--in-file', '<file>', '--api-version', '7.1', '-o', 'json', '--only-show-errors']);

    // our own replies come back under the user's name: not new. A PASS review after the round: no triage.
    const triages = w.prompts.filter((x) => x.prompt.startsWith('Triage request')).length;
    host.threads.push({ id: 15, status: 'active', threadContext: null, comments: [{ id: 1, content: fixture('pass.md'), commentType: 'text', author: BOT, publishedDate: '2026-10-03T16:30:00Z' }] });
    await w.backend.prs.poll('t1');
    expect(fm.store.data.feed.some((f) => /PR #612 \(t1\): automated review PASS/.test(f.text))).toBe(true);
    expect(t().pr!.threads.new).toBe(0);

    // loop guard: after the maximum rounds a FAIL review becomes the user's decision
    w.backend.prs.state('t1').reviewRounds = 2;
    host.threads.push({ id: 16, status: 'active', threadContext: null, comments: [{ id: 1, content: fixture('fail.md'), commentType: 'text', author: BOT, publishedDate: '2026-10-03T17:00:00Z' }] });
    await w.backend.prs.poll('t1');
    const guard = fm.decisions.open().find((d) => d.question.startsWith('Review round 3 on PR #612'))!;
    expect(guard.question).toBe('Review round 3 on PR #612 (t1) suggests 1 critical, 2 important, 2 testing, 1 performance; fold in?');
    await fm.answerDecision(guard.id, 'Leave it');
    expect(w.prompts.filter((x) => x.prompt.startsWith('Triage request')).length).toBe(triages);
    expect(fm.decisions.open().filter((d) => d.agentId === 'marlow' && d.kind === 'question')).toEqual([]);

    // merged on the host: the task is done, and so is the goal
    host.pr.status = 'completed';
    await w.backend.prs.poll('t1');
    expect(t().status).toBe('done');
    expect(t().pr!.status).toBe('merged');
    await until(() => fm.goal('g1')!.status === 'done');
    expect(fm.store.data.feed.some((f) => f.text.startsWith('PR #612 merged: t1'))).toBe(true);
  });
});

describe('PR watching (observe)', () => {
  const w = world('observe', async (opts, prompt) => {
    const refs = [...prompt.matchAll(/^\[(t1\/[^\]]+)\]/gm)].map((m) => m[1]!);
    expect(prompt).toContain('observe mode');
    await callTool(opts, 'triage', { items: refs.map((ref) => ({ ref, verdict: ref.includes('thread') ? 'reply' : 'fold_in', note: `note for ${ref}` })) });
  });

  it('polls and triages, but posts nothing, asks nothing and starts no fold-in', async () => {
    const fm = w.h.fm;
    const host = w.host;
    await landAsPr(w);
    expect(fm.tasks.get('t1')!.status).toBe('pr');

    // a poll that fails backs off (and never throws)
    host.fail = true;
    await w.backend.prs.poll('t1');
    const s = w.backend.prs.state('t1');
    expect(s.failures).toBe(1);
    expect(s.nextPollAt).toBeGreaterThan(Date.now() + 10 * 60_000);
    expect(fm.store.data.feed.some((f) => /Could not read PR #612 \(t1\): az repos pr show failed: ERROR: Failed to establish/.test(f.text))).toBe(true);
    const calls = host.calls.length;
    await w.backend.prs.pollDue();
    expect(host.calls.length).toBe(calls); // not due yet
    host.fail = false;

    host.threads.push(
      { id: 11, status: 'active', threadContext: null, comments: [{ id: 1, content: fixture('fail.md'), commentType: 'text', author: BOT }] },
      { id: 12, status: 'active', threadContext: null, comments: [{ id: 1, content: 'Looks off', commentType: 'text', author: DANA }] },
    );
    host.policies = [{ status: 'rejected', configuration: { type: { displayName: 'Build' }, settings: { displayName: 'CI' } } }];
    await w.backend.prs.poll('t1');
    expect(fm.tasks.get('t1')!.pr).toMatchObject({ checks: 'failing' });
    await until(() => fm.store.data.feed.some((f) => /Marlow triaged PR #612 \(t1\): .*observe mode: nothing posted, no fold-in/.test(f.text)), 30_000);
    expect(w.prompts.some((x) => x.prompt.includes('[t1/checks] checks'))).toBe(true);
    const note = fm.memory.get('shared/pr-triage-t1-612-observe')!;
    expect(note.body).toContain('Fold-in that would go to Kit');
    expect(note.body).toContain('note for t1/review-11.1');
    expect(note.body).toContain('would list:');
    expect(host.calls.filter((c) => c.args.includes('POST') || c.args.includes('PATCH'))).toEqual([]);
    expect(fm.decisions.open()).toEqual([]);
    expect(fm.tasks.get('t1')!.status).toBe('pr');
    expect(w.prompts.some((x) => x.prompt.startsWith('Review fixes'))).toBe(false);

    // someone pushes to the PR branch on the host (seen twice in a row)
    host.pr.lastMergeSourceCommit = { commitId: 'deadbeefcafe' };
    await w.backend.prs.poll('t1');
    await w.backend.prs.poll('t1');
    expect(w.backend.prs.state('t1').foreignHead).toBe('deadbeefcafe');
    expect(fm.store.data.feed.some((f) => /has commits AgentCraft did not push \(deadbee\)/.test(f.text))).toBe(true);

    // abandoned on the host: the task is cancelled
    host.pr.status = 'abandoned';
    await w.backend.prs.poll('t1');
    expect(fm.tasks.get('t1')!.status).toBe('cancelled');
    expect(fm.tasks.get('t1')!.summary).toMatch(/^PR #612 was abandoned on Azure DevOps/);
  });
});
