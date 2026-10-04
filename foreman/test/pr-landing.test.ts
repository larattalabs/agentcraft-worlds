// Landing approved work as pull requests (repoSettings.land "pr") and goals spanning repositories.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { adoPrUrl, openPullRequest, parseRemote } from '../src/prs.js';
import { MERGE_OPTIONS } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const g = (cwd: string, ...args: string[]) => execFileSync('git', ['-C', cwd, '-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { encoding: 'utf8' }).trim();
const write = (p: string, s: string) => {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, s);
};

describe('pull request hosts', () => {
  it('recognises Azure DevOps and GitHub remotes', () => {
    const ado = { kind: 'ado', org: 'contoso', project: 'Notes', repo: 'pocket-api' };
    expect(parseRemote('https://dev.azure.com/contoso/Notes/_git/pocket-api')).toEqual(ado);
    expect(parseRemote('https://contoso@dev.azure.com/contoso/Notes/_git/pocket-api')).toEqual(ado);
    expect(parseRemote('git@ssh.dev.azure.com:v3/contoso/Notes/pocket-api')).toEqual(ado);
    expect(parseRemote('https://contoso.visualstudio.com/Notes/_git/pocket-api')).toEqual(ado);
    expect(parseRemote('https://dev.azure.com/o/My%20Project/_git/r')).toEqual({ kind: 'ado', org: 'o', project: 'My Project', repo: 'r' });
    expect(parseRemote('git@github.com:acme/website.git')).toEqual({ kind: 'github', owner: 'acme', repo: 'website' });
    expect(parseRemote('https://github.com/octocat/agentcraft')).toEqual({ kind: 'github', owner: 'octocat', repo: 'agentcraft' });
    expect(parseRemote('/srv/git/repo.git')).toBeUndefined();
    expect(adoPrUrl({ kind: 'ado', org: 'o', project: 'My Project', repo: 'r' }, 7)).toBe('https://dev.azure.com/o/My%20Project/_git/r/pullrequest/7');
  });

  it('opens the PR with az / gh and returns its URL', async () => {
    const calls: Array<{ cmd: string; args: string[] }> = [];
    const fake = (async (cmd: string, args: string[]) => {
      calls.push({ cmd, args });
      return cmd === 'az' ? { code: 0, stdout: '{"pullRequestId": 612}', stderr: '', timedOut: false } : { code: 0, stdout: 'Creating...\nhttps://github.com/o/r/pull/9\n', stderr: '', timedOut: false };
    }) as never;
    const req = { source: 'feat/t3-x', target: 'dev', title: 'Tune it', description: 'Why', draft: true };
    expect(await openPullRequest({ kind: 'ado', org: 'contoso', project: 'Notes', repo: 'pocket-api' }, req, '/x', fake)).toBe('https://dev.azure.com/contoso/Notes/_git/pocket-api/pullrequest/612');
    expect(calls[0]!.args).toEqual(expect.arrayContaining(['--source-branch', 'feat/t3-x', '--target-branch', 'dev', '--draft', 'true', '--repository', 'pocket-api']));
    expect(await openPullRequest({ kind: 'github', owner: 'o', repo: 'r' }, req, '/x', fake)).toBe('https://github.com/o/r/pull/9');
    expect(calls[1]!.args).toEqual(expect.arrayContaining(['--head', 'feat/t3-x', '--base', 'dev', '--draft']));
    const failing = (async () => ({ code: 1, stdout: '', stderr: 'TF401179: An active pull request already exists', timedOut: false })) as never;
    await expect(openPullRequest({ kind: 'ado', org: 'o', project: 'p', repo: 'r' }, req, '/x', failing)).rejects.toThrow(/TF401179/);
  });
});

describe('landing as a pull request', () => {
  let h: Harness;
  let home: string;
  let repoPath: string;
  let bare: string;
  let other: string;
  const saved = { count: process.env.GIT_CONFIG_COUNT, key: process.env.GIT_CONFIG_KEY_0, value: process.env.GIT_CONFIG_VALUE_0 };

  beforeAll(async () => {
    // the test "server" is a local bare repository: allow git's file transport for this test only
    process.env.GIT_CONFIG_COUNT = '1';
    process.env.GIT_CONFIG_KEY_0 = 'protocol.file.allow';
    process.env.GIT_CONFIG_VALUE_0 = 'always';
    home = tempDir();
    repoPath = await demoRepo();
    g(repoPath, 'config', 'user.name', 'Sam');
    g(repoPath, 'config', 'user.email', 'sam@example.com');
    bare = path.join(tempDir(), 'server.git');
    execFileSync('git', ['init', '-q', '--bare', bare]);
    g(repoPath, 'remote', 'add', 'origin', bare);
    g(repoPath, 'push', '-q', 'origin', 'HEAD:refs/heads/dev');
    g(repoPath, 'checkout', '-qb', 'feature/local-only'); // the user's checkout is elsewhere
    // someone else lands work on the server's dev after the user last fetched
    other = path.join(tempDir(), 'other');
    execFileSync('git', ['clone', '-q', '-b', 'dev', bare, other]);
    write(path.join(other, 'NOTES.md'), 'from the server\n');
    g(other, 'add', '.');
    g(other, 'commit', '-qm', 'server work');
    g(other, 'push', '-q', 'origin', 'dev');
    write(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repoPath]: { baseBranch: 'dev', land: 'pr', pr: { branchPrefix: 'feat/', squash: true } } } }));
    h = makeForeman(home, ['--backend', 'sim']);
    await h.fm.repos.add(repoPath);
  });

  afterAll(async () => {
    await h.fm.close();
    for (const [k, v] of [['GIT_CONFIG_COUNT', saved.count], ['GIT_CONFIG_KEY_0', saved.key], ['GIT_CONFIG_VALUE_0', saved.value]] as const) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
    rmrf(home);
    rmrf(path.dirname(repoPath));
    rmrf(path.dirname(bare));
    rmrf(path.dirname(other));
  });

  it('starts work from the freshly fetched server base, pushes one commit by the user, and keeps the local checkout alone', async () => {
    const fm = h.fm;
    const t = fm.tasks.create({ title: 'Add a changelog', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit' });
    const wt = await fm.repos.createWorktree('demo-app', 'kit', t);
    expect(wt.base).toBe('origin/dev');
    expect(fs.existsSync(path.join(wt.path, 'NOTES.md'))).toBe(true); // the server's newer dev
    write(path.join(wt.path, 'CHANGELOG.md'), '# Changes\n');
    g(wt.path, 'add', '.');
    g(wt.path, 'commit', '-qm', 'kit: changelog');
    write(path.join(wt.path, 'CHANGELOG.md'), '# Changes\n\n- more\n'); // uncommitted too
    fm.tasks.update(t.id, { branch: wt.branch, worktree: wt.id });
    fm.tasks.setStatus(t.id, 'doing');
    fm.tasks.setStatus(t.id, 'review', { summary: 'Adds a changelog.' });
    const d = fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Open a PR?', options: [...MERGE_OPTIONS], taskId: t.id, repoId: 'demo-app', worktree: wt.id });
    await fm.answerDecision(d.id, 'Merge');

    const remoteBranch = `feat/${wt.branch.split('/').slice(2).join('/')}`;
    const tip = execFileSync('git', ['--git-dir', bare, 'rev-parse', `refs/heads/${remoteBranch}`], { encoding: 'utf8' }).trim();
    const log = execFileSync('git', ['--git-dir', bare, 'log', '--format=%an|%s|%b', `dev..${tip}`], { encoding: 'utf8' }).trim();
    expect(log.split('\n')[0]).toMatch(/^Sam\|Add a changelog\|/); // one squashed commit, authored by the user
    expect(log).not.toMatch(/co-authored-by|agentcraft|claude/i); // C8: the user's commit, no attribution
    expect(log).toContain('Adds a changelog.');
    expect(execFileSync('git', ['--git-dir', bare, 'rev-parse', `${tip}^`], { encoding: 'utf8' }).trim()).toBe(execFileSync('git', ['--git-dir', bare, 'rev-parse', 'dev'], { encoding: 'utf8' }).trim());
    expect(execFileSync('git', ['--git-dir', bare, 'show', `${tip}:CHANGELOG.md`], { encoding: 'utf8' })).toBe('# Changes\n\n- more\n');
    expect(fm.tasks.get(t.id)!.status).toBe('done');
    expect(fm.tasks.get(t.id)!.summary).toContain(`Pushed ${remoteBranch}`); // not an ADO/GitHub remote: no PR to open
    expect(g(repoPath, 'rev-parse', '--abbrev-ref', 'HEAD')).toBe('feature/local-only');
    expect(fm.repos.findWorktree('demo-app', wt.id)!.status).toBe('merged');
  });

  it('refuses to push over a remote branch AgentCraft did not push', async () => {
    const fm = h.fm;
    const t = fm.tasks.create({ title: 'Clash', createdBy: 'marlow', repoId: 'demo-app', assignee: 'wren' });
    const wt = await fm.repos.createWorktree('demo-app', 'wren', t);
    write(path.join(wt.path, 'clash.txt'), 'x\n');
    // someone already has a branch with that name on the server
    g(other, 'push', '-q', 'origin', `dev:refs/heads/feat/${wt.branch.split('/').slice(2).join('/')}`);
    fm.tasks.update(t.id, { branch: wt.branch, worktree: wt.id });
    fm.tasks.setStatus(t.id, 'doing');
    fm.tasks.setStatus(t.id, 'review');
    const d = fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Open a PR?', options: [...MERGE_OPTIONS], taskId: t.id, repoId: 'demo-app', worktree: wt.id });
    await fm.answerDecision(d.id, 'Merge');
    await until(() => fm.decisions.get(d.id)!.status === 'open');
    expect(fm.decisions.get(d.id)!.context).toMatch(/push to origin\/feat\/.* failed/);
    expect(fm.tasks.get(t.id)!.status).toBe('review');
  });
});

// ---- a goal across two repositories, in the claude backend ---------------------------------------

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };

describe('a goal across repositories', () => {
  let h: Harness;
  let home: string;
  let api: string;
  let web: string;
  const turns: Array<{ prompt: string; options: Options; created?: string[] }> = [];

  beforeAll(async () => {
    home = tempDir();
    api = await demoRepo();
    const webDemo = await demoRepo();
    web = path.join(path.dirname(webDemo), 'web-app');
    fs.renameSync(webDemo, web);
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit,juniper', '--repo', `${api},${web}`, '--no-lead-review']);
    const queryFn = ({ prompt, options }: { prompt: string; options?: Options }) => {
      const o = options!;
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'm', cwd: '', tools: [] });
        const p = String(prompt);
        const tools = (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
        if (p.startsWith('New goal')) {
          const a = (await tools.create_task!.handler({ title: 'API endpoint', assignee: 'kit' }, {})).content[0]!.text;
          const id = /Created (t\d+)/.exec(a)![1]!;
          const b = (await tools.create_task!.handler({ title: 'UI for the endpoint', assignee: 'juniper', repo: 'web-app', deps: [id] }, {})).content[0]!.text;
          const bad = (await tools.create_task!.handler({ title: 'x', repo: 'nope' }, {})).content[0]!.text;
          turns.push({ prompt: p, options: o, created: [a, b, bad] });
        } else turns.push({ prompt: p, options: o });
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
  });

  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(path.dirname(api));
    rmrf(path.dirname(web));
  });

  it('lets the lead put tasks in other registered repositories and orders them across repos', async () => {
    const fm = h.fm;
    await fm.submitGoal('an endpoint and its UI', 'demo-app');
    await until(() => turns.some((t) => t.created));
    const lead = turns.find((t) => t.created)!;
    const append = (lead.options.systemPrompt as { append: string }).append;
    expect(append).toContain('# Registered repositories');
    expect(append).toMatch(/- web-app: read it at .*worktrees\/web-app\/_lead; base main, lands by a local merge/);
    expect(append).toContain('is a read-only view of demo-app');
    expect(lead.options.cwd).toBe(path.join(h.cfg.dataDir, 'worktrees', 'demo-app', '_lead'));
    expect(lead.created![2]).toMatch(/no repository nope \(registered: demo-app, web-app\)/);
    const [t1, t2] = fm.tasks.list();
    expect(t1!.repoId).toBe('demo-app');
    expect(t2!.repoId).toBe('web-app');
    expect(t2!.deps).toEqual([t1!.id]);
    // the lead may read both repositories
    const read = await lead.options.canUseTool!('Read', { file_path: path.join(web, 'README.md') }, { signal: new AbortController().signal, toolUseID: 'x', requestId: 'r' } as never);
    expect(read?.behavior).toBe('allow');
  });
});

describe("the lead's view of the base", () => {
  it('follows the base branch, not the checkout, and refreshes once its cache is stale', async () => {
    const home = tempDir();
    const repo = await demoRepo();
    try {
      write(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repo]: { baseBranch: 'main' } } }));
      const h = makeForeman(home, ['--backend', 'sim']);
      await h.fm.repos.add(repo);
      // the user is on a feature branch with uncommitted work in progress
      g(repo, 'checkout', '-qb', 'feature/wip');
      write(path.join(repo, 'WIP.md'), 'half done\n');
      g(repo, 'add', '.');
      g(repo, 'commit', '-qm', 'wip');
      write(path.join(repo, 'README.md'), 'uncommitted\n');
      const view = await h.fm.repos.leadView('demo-app');
      expect(view).toBe(path.join(h.cfg.dataDir, 'worktrees', 'demo-app', '_lead'));
      expect(fs.existsSync(path.join(view, 'WIP.md'))).toBe(false);
      expect(fs.readFileSync(path.join(view, 'README.md'), 'utf8')).not.toBe('uncommitted\n');
      // the base moves on: the next call shows it
      g(repo, 'checkout', '-q', 'main');
      g(repo, 'stash', '-q');
      write(path.join(repo, 'LANDED.md'), 'merged\n');
      g(repo, 'add', '.');
      g(repo, 'commit', '-qm', 'landed');
      // within the cache window the view is reused as it is (no fetch / checkout per lead turn)
      expect(await h.fm.repos.leadView('demo-app')).toBe(view);
      expect(fs.existsSync(path.join(view, 'LANDED.md'))).toBe(false);
      // stale: the next call shows the moved base
      h.fm.repos.leadViewTtlMs = 0;
      expect(await h.fm.repos.leadView('demo-app')).toBe(view);
      expect(fs.existsSync(path.join(view, 'LANDED.md'))).toBe(true);
      expect(h.fm.repos.viewPath('demo-app')).toBe(view);
      expect(h.fm.repos.get('demo-app')!.worktrees).toEqual([]); // not an agent worktree
      await h.fm.close();
    } finally {
      rmrf(home);
      rmrf(path.dirname(repo));
    }
  });
});
