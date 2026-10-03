// Review fixes on an open pull request land as an ADDED commit on the PR branch (never a rewrite):
// a fast-forward of the agents' branch, or (squash PRs) one new commit whose parent is the commit on
// the PR. Real git against a local bare "server"; the PR host CLI is a fake that must not be called.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { MERGE_OPTIONS } from '../src/protocol.js';
import type { Foreman } from '../src/foreman.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const g = (cwd: string, ...args: string[]) => execFileSync('git', ['-C', cwd, '-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { encoding: 'utf8' }).trim();
const bareGit = (bare: string, ...args: string[]) => execFileSync('git', ['--git-dir', bare, ...args], { encoding: 'utf8' }).trim();
const write = (p: string, s: string) => {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, s);
};

async function approve(fm: Foreman, taskId: string, worktree: string, commitMessage?: string) {
  const d = fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Land it?', options: [...MERGE_OPTIONS], taskId, repoId: 'demo-app', worktree });
  const answered = fm.decisions.answer(d.id, 'Merge');
  return fm.repos.land(answered, commitMessage ? { commitMessage, title: 'Add a changelog' } : { title: 'Add a changelog', description: 'Adds it.' });
}

function setup(squash: boolean) {
  const ctx = {} as { h: Harness; home: string; repo: string; bare: string; other: string; hostCalls: string[][] };
  beforeAll(async () => {
    ctx.home = tempDir();
    ctx.repo = await demoRepo();
    g(ctx.repo, 'config', 'user.name', 'Sam');
    g(ctx.repo, 'config', 'user.email', 'sam@example.com');
    ctx.bare = path.join(tempDir(), 'server.git');
    execFileSync('git', ['init', '-q', '--bare', ctx.bare]);
    g(ctx.repo, 'remote', 'add', 'origin', ctx.bare);
    g(ctx.repo, 'push', '-q', 'origin', 'HEAD:refs/heads/dev');
    ctx.other = path.join(tempDir(), 'other');
    execFileSync('git', ['clone', '-q', '-b', 'dev', ctx.bare, ctx.other]);
    write(path.join(ctx.home, 'config.json'), JSON.stringify({ repoSettings: { [ctx.repo]: { baseBranch: 'dev', land: 'pr', pr: { branchPrefix: 'feat/', squash } } } }));
    ctx.h = makeForeman(ctx.home, ['--backend', 'sim']);
    ctx.hostCalls = [];
    ctx.h.fm.repos.prRunFn = (async (cmd: string, args: string[]) => {
      ctx.hostCalls.push([cmd, ...args]);
      return { code: 1, stdout: '', stderr: 'no host in tests', timedOut: false };
    }) as never;
    await ctx.h.fm.repos.add(ctx.repo);
  });
  afterAll(async () => {
    await ctx.h.fm.close();
    rmrf(ctx.home);
    rmrf(path.dirname(ctx.repo));
    rmrf(path.dirname(ctx.bare));
    rmrf(path.dirname(ctx.other));
  });
  return ctx;
}

const saved = { count: process.env.GIT_CONFIG_COUNT, key: process.env.GIT_CONFIG_KEY_0, value: process.env.GIT_CONFIG_VALUE_0 };
beforeAll(() => {
  // the test "server" is a local bare repository: allow git's file transport for these tests only
  process.env.GIT_CONFIG_COUNT = '1';
  process.env.GIT_CONFIG_KEY_0 = 'protocol.file.allow';
  process.env.GIT_CONFIG_VALUE_0 = 'always';
});
afterAll(() => {
  for (const [k, v] of [['GIT_CONFIG_COUNT', saved.count], ['GIT_CONFIG_KEY_0', saved.key], ['GIT_CONFIG_VALUE_0', saved.value]] as const) {
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
});

describe('review fixes on a squashed pull request', () => {
  const ctx = setup(true);

  it('adds a commit on top of the PR commit, without newer base commits, also from another worker', async () => {
    const { fm } = ctx.h;
    const t = fm.tasks.create({ title: 'Add a changelog', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit' });
    const wt = await fm.repos.createWorktree('demo-app', 'kit', t);
    write(path.join(wt.path, 'CHANGELOG.md'), '# Changes\n');
    fm.tasks.update(t.id, { branch: wt.branch, worktree: wt.id });
    const first = await approve(fm, t.id, wt.id);
    expect(first.kind).toBe('pr');
    const remoteBranch = `feat/${wt.branch.split('/').slice(2).join('/')}`;
    const squash = bareGit(ctx.bare, 'rev-parse', `refs/heads/${remoteBranch}`);
    // the PR was opened (the remote is a local path here, so pretend the host returned its URL)
    const meta = fm.store.data.worktreeMeta[`demo-app/${wt.id}`]!;
    meta.prUrl = 'https://dev.azure.com/o/p/_git/demo-app/pullrequest/5';

    // meanwhile someone lands other work on the server's dev
    write(path.join(ctx.other, 'SERVER.md'), 'newer base\n');
    g(ctx.other, 'add', '.');
    g(ctx.other, 'commit', '-qm', 'server work');
    g(ctx.other, 'push', '-q', 'origin', 'dev');

    // 1. kit makes the review fixes on the same branch
    const again = await fm.repos.createWorktree('demo-app', 'kit', t);
    expect(again.branch).toBe(wt.branch);
    expect(fs.readFileSync(path.join(again.path, 'CHANGELOG.md'), 'utf8')).toBe('# Changes\n');
    write(path.join(again.path, 'CHANGELOG.md'), '# Changes\n\n- fixed per review\n');
    const res = await approve(fm, t.id, again.id, 't1: address review feedback on PR #5');
    expect(res).toMatchObject({ kind: 'pr', updated: true, url: meta.prUrl, remoteBranch });
    const tip = bareGit(ctx.bare, 'rev-parse', `refs/heads/${remoteBranch}`);
    expect(bareGit(ctx.bare, 'rev-parse', `${tip}^`)).toBe(squash); // added on top, not re-squashed
    expect(bareGit(ctx.bare, 'diff', '--name-only', squash, tip)).toBe('CHANGELOG.md'); // only the fix
    expect(bareGit(ctx.bare, 'log', '-1', '--format=%an|%s', tip)).toBe('Sam|t1: address review feedback on PR #5');
    expect(bareGit(ctx.bare, 'log', '-1', '--format=%b', tip)).toContain('Co-authored-by: AgentCraft Kit');
    expect(bareGit(ctx.bare, 'ls-tree', '--name-only', tip).split('\n')).not.toContain('SERVER.md'); // the newer base stays out
    expect(meta.prPushedSha).toBe(tip);

    // 2. wren (another worker) continues kit's branch for a second round
    const wren = await fm.repos.createWorktree('demo-app', 'wren', t, { startPoint: wt.branch });
    expect(fs.readFileSync(path.join(wren.path, 'CHANGELOG.md'), 'utf8')).toContain('fixed per review');
    write(path.join(wren.path, 'docs.md'), 'more\n');
    fm.tasks.update(t.id, { worktree: wren.id, assignee: 'wren' });
    const res2 = await approve(fm, t.id, wren.id, 't1: address review feedback on PR #5');
    expect(res2).toMatchObject({ kind: 'pr', updated: true, url: meta.prUrl, remoteBranch });
    const tip2 = bareGit(ctx.bare, 'rev-parse', `refs/heads/${remoteBranch}`);
    expect(bareGit(ctx.bare, 'rev-parse', `${tip2}^`)).toBe(tip);
    expect(bareGit(ctx.bare, 'diff', '--name-only', tip, tip2)).toBe('docs.md');
    expect(bareGit(ctx.bare, 'log', '-1', '--format=%b', tip2)).toContain('Co-authored-by: AgentCraft Wren');
    expect(bareGit(ctx.bare, 'log', '-1', '--format=%b', tip2)).not.toContain('AgentCraft Kit');
    expect(fm.store.data.worktreeMeta[`demo-app/${wren.id}`]!.prUrl).toBe(meta.prUrl);

    // 3. kit again, continuing wren's branch: the PR state followed (lease, last landed tip)
    const kit3 = await fm.repos.createWorktree('demo-app', 'kit', t, { startPoint: wren.branch });
    expect(kit3.branch).toBe(wt.branch); // fast-forwarded to wren's work
    write(path.join(kit3.path, 'third.md'), '3\n');
    fm.tasks.update(t.id, { worktree: kit3.id, assignee: 'kit' });
    await approve(fm, t.id, kit3.id, 't1: address review feedback on PR #5');
    const tip3 = bareGit(ctx.bare, 'rev-parse', `refs/heads/${remoteBranch}`);
    expect(bareGit(ctx.bare, 'rev-parse', `${tip3}^`)).toBe(tip2);
    expect(bareGit(ctx.bare, 'diff', '--name-only', tip2, tip3)).toBe('third.md');

    // 4. a round with nothing new is refused as empty (the PR stays as it is)
    const empty = await fm.repos.createWorktree('demo-app', 'wren', { id: t.id, title: t.title }, { startPoint: wt.branch });
    await expect(approve(fm, t.id, empty.id)).rejects.toThrow(/nothing new for the pull request/);
    expect(bareGit(ctx.bare, 'rev-parse', `refs/heads/${remoteBranch}`)).toBe(tip3);
    expect(ctx.hostCalls).toEqual([]); // no second pull request
  });
});

describe('review fixes on a pull request of the agents\' commits', () => {
  const ctx = setup(false);

  it('pushes the branch as a fast-forward', async () => {
    const { fm } = ctx.h;
    const t = fm.tasks.create({ title: 'Add a changelog', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit' });
    const wt = await fm.repos.createWorktree('demo-app', 'kit', t);
    write(path.join(wt.path, 'CHANGELOG.md'), '# Changes\n');
    await approve(fm, t.id, wt.id);
    const remoteBranch = `feat/${wt.branch.split('/').slice(2).join('/')}`;
    const first = bareGit(ctx.bare, 'rev-parse', `refs/heads/${remoteBranch}`);
    fm.store.data.worktreeMeta[`demo-app/${wt.id}`]!.prUrl = 'https://github.com/o/r/pull/5';
    const again = await fm.repos.createWorktree('demo-app', 'kit', t);
    write(path.join(again.path, 'CHANGELOG.md'), '# Changes\n\n- review\n');
    await approve(fm, t.id, again.id, 't1: address review feedback');
    const tip = bareGit(ctx.bare, 'rev-parse', `refs/heads/${remoteBranch}`);
    expect(bareGit(ctx.bare, 'rev-parse', `${tip}^`)).toBe(first);
    expect(bareGit(ctx.bare, 'log', '-1', '--format=%an|%s', tip)).toBe('AgentCraft Kit|t1: address review feedback'); // the agent's own commit
    expect(ctx.hostCalls).toEqual([]);
  });
});
