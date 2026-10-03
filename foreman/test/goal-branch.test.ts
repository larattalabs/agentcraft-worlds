// Goals that continue one of the user's branches ("on <branch>: ..."), e.g. one started in Claude Desktop.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const g = (cwd: string, ...args: string[]) => execFileSync('git', ['-C', cwd, '-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { encoding: 'utf8' }).trim();
const write = (p: string, s: string) => {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, s);
};
const bareRev = (bare: string, ref: string) => execFileSync('git', ['--git-dir', bare, 'rev-parse', ref], { encoding: 'utf8' }).trim();

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };

describe('a goal on the user\'s branch', () => {
  let h: Harness;
  let home: string;
  let repoPath: string;
  let bare: string;
  let desktop: string;
  const turns: Array<{ prompt: string; options: Options; created?: string }> = [];
  const saved = { count: process.env.GIT_CONFIG_COUNT, key: process.env.GIT_CONFIG_KEY_0, value: process.env.GIT_CONFIG_VALUE_0 };

  beforeAll(async () => {
    process.env.GIT_CONFIG_COUNT = '1';
    process.env.GIT_CONFIG_KEY_0 = 'protocol.file.allow';
    process.env.GIT_CONFIG_VALUE_0 = 'always';
    home = tempDir();
    repoPath = await demoRepo();
    bare = path.join(tempDir(), 'server.git');
    execFileSync('git', ['init', '-q', '--bare', bare]);
    g(repoPath, 'remote', 'add', 'origin', bare);
    g(repoPath, 'push', '-q', 'origin', 'HEAD:refs/heads/dev');
    // a branch the user started elsewhere and pushed (no local copy here)
    const elsewhere = path.join(tempDir(), 'elsewhere');
    execFileSync('git', ['clone', '-q', '-b', 'dev', bare, elsewhere]);
    g(elsewhere, 'checkout', '-qb', 'feat/started');
    write(path.join(elsewhere, 'STARTED.md'), 'begun in another session\n');
    g(elsewhere, 'add', '.');
    g(elsewhere, 'commit', '-qm', 'start');
    g(elsewhere, 'push', '-q', 'origin', 'feat/started');
    rmrf(path.dirname(elsewhere));
    // a local-only branch checked out in a "Claude Desktop" worktree with uncommitted edits
    g(repoPath, 'branch', 'feat/desktop');
    desktop = path.join(tempDir(), 'desktop-wt');
    g(repoPath, 'worktree', 'add', '-q', desktop, 'feat/desktop');
    write(path.join(desktop, 'README.md'), 'editing in Desktop\n');
    write(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repoPath]: { baseBranch: 'dev', land: 'pr' } } }));
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
    const queryFn = ({ prompt, options }: { prompt: string; options?: Options }) => {
      const o = options!;
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'm', cwd: '', tools: [] });
        const p = String(prompt);
        const tools = (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
        if (p.startsWith('New goal')) {
          const title = /"on ([\w/]+):/.exec(p)?.[1] ?? 'plain';
          const created = (await tools.create_task!.handler({ title: `Finish ${title}`, assignee: 'kit' }, {})).content[0]!.text;
          turns.push({ prompt: p, options: o, created });
        } else if (/Your task: (t\d+)/.test(p)) {
          turns.push({ prompt: p, options: o });
          const id = /Your task: (t\d+)/.exec(p)![1]!;
          write(path.join(o.cwd!, `${id}.md`), 'agent work\n');
          await tools.update_task!.handler({ task_id: id, status: 'review', summary: 'done' }, {});
        }
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
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
    rmrf(path.dirname(desktop));
  });

  it('builds on a pushed branch, adds approved work to it and pushes it (no PR)', async () => {
    const fm = h.fm;
    const goal = await fm.submitGoal('on feat/started: finish what I started');
    await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === fm.tasks.forGoal(goal.id)[0]?.id));
    const lead = turns.find((t) => t.created && t.prompt.includes('feat/started'))!;
    expect(lead.prompt).toContain('(base branch feat/started)');
    expect(fs.existsSync(path.join(lead.options.cwd!, 'STARTED.md'))).toBe(true); // the lead's view is the user's branch
    expect((lead.options.systemPrompt as { append: string }).append).toContain("continues Alex's branch feat/started");
    const work = turns.find((t) => /Your task/.test(t.prompt) && fs.existsSync(path.join(t.options.cwd!, 'STARTED.md')))!;
    expect(work).toBeDefined(); // the worker started from it too
    const d = fm.decisions.open().find((x) => x.kind === 'merge')!;
    expect(d.question).toMatch(/to your branch feat\/started\?$/);
    await fm.answerDecision(d.id, 'Merge');
    const t = fm.tasks.forGoal(goal.id)[0]!;
    expect(fm.tasks.get(t.id)!.status).toBe('done');
    expect(g(repoPath, 'show', `feat/started:${t.id}.md`)).toBe('agent work');
    expect(bareRev(bare, 'feat/started')).toBe(g(repoPath, 'rev-parse', 'feat/started')); // pushed, fast-forward
    expect(fm.store.data.feed.some((f) => /pushed feat\/started to origin/.test(f.text))).toBe(true);
    expect(() => bareRev(bare, `refs/heads/feat/${t.id}`)).toThrow(); // no PR branch
    expect(bareRev(bare, 'dev')).not.toBe(bareRev(bare, 'feat/started'));
  });

  it("never lands on a branch whose Desktop checkout has uncommitted edits", async () => {
    const fm = h.fm;
    const goal = await fm.submitGoal('on feat/desktop: tidy up');
    await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === fm.tasks.forGoal(goal.id)[0]?.id));
    const d = fm.decisions.open().find((x) => x.taskId === fm.tasks.forGoal(goal.id)[0]!.id)!;
    await fm.answerDecision(d.id, 'Merge');
    await until(() => fm.decisions.get(d.id)!.status === 'open');
    expect(fm.decisions.get(d.id)!.context).toMatch(/desktop-wt \(feat\/desktop\) has uncommitted changes/);
    expect(fs.readFileSync(path.join(desktop, 'README.md'), 'utf8')).toBe('editing in Desktop\n');
    // the user commits in Desktop; approving again lands it in their checkout
    g(desktop, 'commit', '-qam', 'desktop edit');
    await fm.answerDecision(d.id, 'Merge');
    const t = fm.tasks.forGoal(goal.id)[0]!;
    expect(fs.existsSync(path.join(desktop, `${t.id}.md`))).toBe(true);
  });

  it('says why when the branch does not exist, and plans a normal goal', async () => {
    const fm = h.fm;
    const goal = await fm.submitGoal('on feat/nope: something');
    await until(() => fm.tasks.forGoal(goal.id).length > 0);
    expect(fm.store.data.feed.some((f) => /Not building on "feat\/nope": .*no branch feat\/nope/.test(f.text))).toBe(true);
    const wt = await until(() => !!fm.tasks.forGoal(goal.id)[0]!.worktree).then(() => fm.repos.findWorktree('demo-app', fm.tasks.forGoal(goal.id)[0]!.worktree!)!);
    expect(wt.base).toBe('origin/dev');
  });
});
