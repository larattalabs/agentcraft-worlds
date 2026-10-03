// Multi-repo workspaces: workspace instructions above a repo, repoSettings.baseBranch / protect / env.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_CONTEXT, instructionsBlock, workspaceDirs } from '../src/agents/claude/context.js';
import { guardrail } from '../src/agents/claude/permissions.js';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { loadConfig } from '../src/config.js';
import { classifyToolUse } from '../src/policy.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const write = (p: string, s: string) => {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, s);
};
const g = (cwd: string, ...args: string[]) => execFileSync('git', ['-C', cwd, '-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { encoding: 'utf8' }).trim();

describe('workspace instructions', () => {
  it('reads CLAUDE.md / AGENTS.md above the checkout, up to home', () => {
    const home = fs.realpathSync(tempDir());
    try {
      const ws = path.join(home, 'Developer', 'Work');
      write(path.join(ws, 'CLAUDE.md'), 'WORKSPACE RULES: integration branch is dev');
      fs.symlinkSync('CLAUDE.md', path.join(ws, 'AGENTS.md')); // same file: included once
      write(path.join(home, 'CLAUDE.md'), 'HOME FILE (not a workspace)');
      const repo = path.join(ws, 'api');
      write(path.join(repo, 'CLAUDE.md'), 'REPO RULES');
      expect(workspaceDirs(repo, home)).toEqual([path.join(home, 'Developer'), ws]);
      const wt = path.join(home, '.agentcraft', 'wt'); // a worker's worktree is elsewhere
      fs.mkdirSync(wt, { recursive: true });
      const block = instructionsBlock(DEFAULT_CONTEXT, wt, 'Alex', home, repo);
      expect(block).toContain(`Workspace instructions (paths in it are relative to ${ws})`);
      expect(block.match(/WORKSPACE RULES/g)).toHaveLength(1);
      expect(block).not.toContain('HOME FILE');
      expect(instructionsBlock({ ...DEFAULT_CONTEXT, workspaceInstructions: false }, wt, 'Alex', home, repo)).not.toContain('WORKSPACE RULES');
      expect(workspaceDirs('/opt/elsewhere/repo', home)).toEqual([]);
    } finally {
      rmrf(home);
    }
  });
});

describe('repoSettings: baseBranch, protect, env', () => {
  it('parses and sanitises the settings', () => {
    const home = tempDir();
    try {
      write(
        path.join(home, 'config.json'),
        JSON.stringify({ repoSettings: { '/x/api': { baseBranch: 'dev', protect: ['Api/appsettings.json', 'local/', '../escape', '/abs'], env: { PATH: '~/n16/bin:$PATH', GIT_DIR: '/x', 'bad-key': 'y' } } } }),
      );
      expect(loadConfig(['--home', home], {}).repoSettings['/x/api']).toEqual({ baseBranch: 'dev', protect: ['Api/appsettings.json', 'local/'], env: { PATH: '~/n16/bin:$PATH' } });
    } finally {
      rmrf(home);
    }
  });

  let h: Harness;
  let home: string;
  let repoPath: string;
  beforeAll(async () => {
    home = tempDir();
    repoPath = await demoRepo();
    write(path.join(repoPath, 'config', 'local.json'), '{"db":"prod"}');
    g(repoPath, 'add', '.');
    g(repoPath, 'commit', '-qm', 'config');
    g(repoPath, 'branch', 'dev');
    g(repoPath, 'checkout', '-qb', 'feature/mine'); // the user's checkout sits on a feature branch
    write(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repoPath]: { baseBranch: 'dev', protect: ['config/local.json', 'secrets/'], env: { DEMO_VAR: '${HOME}/x', OTHER: 'plain' } } } }));
    h = makeForeman(home, ['--backend', 'sim']);
    await h.fm.repos.add(repoPath);
  });
  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(path.dirname(repoPath));
  });

  it('uses the configured base branch, not what the checkout has checked out', async () => {
    const r = h.fm.repos.get('demo-app')!;
    expect(r.branch).toBe('dev');
    const t = h.fm.tasks.create({ title: 'Base check', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit' });
    const wt = await h.fm.repos.createWorktree('demo-app', 'kit', t);
    expect(wt.base).toBe('dev');
    expect(g(repoPath, 'rev-parse', '--abbrev-ref', 'HEAD')).toBe('feature/mine');
  });

  it('creates the local base branch from origin when only the remote one exists', async () => {
    const repo2 = await demoRepo();
    try {
      g(repo2, 'update-ref', 'refs/remotes/origin/develop', 'HEAD');
      const s = h.cfg.repoSettings;
      s[path.resolve(repo2)] = { baseBranch: 'develop' };
      const r = await h.fm.repos.add(repo2);
      expect(r.branch).toBe('develop');
      expect(g(repo2, 'rev-parse', 'refs/heads/develop')).toBe(g(repo2, 'rev-parse', 'HEAD'));
    } finally {
      rmrf(path.dirname(repo2));
    }
  });

  it('keeps protected paths out of commits and finds committed ones', async () => {
    const t = h.fm.tasks.create({ title: 'Protect check', createdBy: 'marlow', repoId: 'demo-app', assignee: 'wren' });
    const wt = await h.fm.repos.createWorktree('demo-app', 'wren', t);
    fs.writeFileSync(path.join(wt.path, 'config', 'local.json'), '{"db":"local"}'); // local-only edit
    write(path.join(wt.path, 'secrets', 'key.txt'), 'k');
    fs.writeFileSync(path.join(wt.path, 'README.md'), 'changed\n');
    expect(await h.fm.repos.commitAll('demo-app', wt.id, 'work')).toBe(true);
    const committed = g(wt.path, 'show', '--name-only', '--format=', 'HEAD').split('\n');
    expect(committed).toEqual(['README.md']);
    expect(fs.readFileSync(path.join(wt.path, 'config', 'local.json'), 'utf8')).toBe('{"db":"local"}'); // still there, uncommitted
    expect(await h.fm.repos.protectedChanges('demo-app', wt.id)).toEqual([]);
    // the worker commits a protected file itself
    g(wt.path, 'add', 'config/local.json');
    g(wt.path, 'commit', '-qm', 'oops');
    expect(await h.fm.repos.protectedChanges('demo-app', wt.id)).toEqual(['config/local.json']);
    expect(h.fm.repos.isProtected('demo-app', 'secrets/a/b')).toBe(true);
    expect(h.fm.repos.isProtected('demo-app', 'config/other.json')).toBe(false);
  });

  it('expands the repo env', () => {
    expect(h.fm.repos.envFor('demo-app', { HOME: '/home/a' })).toEqual({ DEMO_VAR: '/home/a/x', OTHER: 'plain' });
    expect(h.fm.repos.envFor(undefined)).toEqual({});
  });
});

describe('protected paths in the policy', () => {
  it('asks before editing a protected file, in both modes', () => {
    const wt = fs.realpathSync(tempDir());
    try {
      const ctx = { role: 'worker' as const, cwd: wt, mcpServer: 'agentcraft', protectedPaths: ['config/local.json', 'secrets/'] };
      const v = classifyToolUse('Edit', { file_path: path.join(wt, 'config', 'local.json') }, ctx);
      expect(v.action).toBe('ask');
      expect(guardrail(v, [])?.decision).toBe('ask');
      expect(classifyToolUse('Write', { file_path: path.join(wt, 'secrets', 'x') }, ctx).action).toBe('ask');
      expect(classifyToolUse('Edit', { file_path: path.join(wt, 'config', 'other.json') }, ctx).action).toBe('allow');
    } finally {
      rmrf(wt);
    }
  });
});

// ---- in a running team ----------------------------------------------------------------------------

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<unknown> }> } };

describe('a workspace repo in the claude backend', () => {
  let h: Harness;
  let home: string;
  let wsRoot: string;
  let repoPath: string;
  const prompts: Array<{ prompt: string; options: Options }> = [];

  beforeAll(async () => {
    home = tempDir();
    const demo = await demoRepo();
    // a workspace folder (with its own CLAUDE.md) around the repo, under the user's home
    wsRoot = fs.mkdtempSync(path.join(process.env.HOME!, '.ac-ws-test-'));
    repoPath = path.join(wsRoot, 'api');
    fs.renameSync(demo, repoPath);
    rmrf(path.dirname(demo));
    write(path.join(wsRoot, 'CLAUDE.md'), 'WORKSPACE: branch new work from dev.');
    write(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repoPath]: { protect: ['README.md'], env: { DEMO_REPO_ENV: 'yes' } } } }));
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
    const queryFn = ({ prompt, options }: { prompt: string; options?: Options }) => {
      const o = options!;
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'm', cwd: '', tools: [] });
        const p = String(prompt);
        prompts.push({ prompt: p, options: o });
        const tools = (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
        if (p.startsWith('New goal')) await tools.create_task!.handler({ title: 'Touch the readme', assignee: 'kit' }, {});
        else if (/Your task: (t\d+)/.test(p)) {
          // the worker commits a protected file itself, then hands in
          fs.writeFileSync(path.join(o.cwd!, 'README.md'), 'edited\n');
          g(o.cwd!, 'commit', '-qam', 'readme');
          await tools.update_task!.handler({ task_id: /Your task: (t\d+)/.exec(p)![1], status: 'review', summary: 'done' }, {});
        }
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
  });

  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(wsRoot);
  });

  it('gives agents the workspace instructions and repo env, and sends committed protected files back', async () => {
    await h.fm.submitGoal('readme');
    await until(() => prompts.some((p) => /must never be committed/.test(p.prompt)));
    const work = prompts.find((p) => /Your task: t\d+/.test(p.prompt))!;
    expect((work.options.systemPrompt as { append: string }).append).toContain('WORKSPACE: branch new work from dev.');
    expect(work.options.env?.DEMO_REPO_ENV).toBe('yes');
    const back = prompts.find((p) => /must never be committed/.test(p.prompt))!;
    expect(back.prompt).toContain('README.md');
    expect(h.fm.decisions.open().some((d) => d.kind === 'merge')).toBe(false);
  });
});
