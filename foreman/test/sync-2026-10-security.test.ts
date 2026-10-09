// Fixes from the upstream sync's security review (docs/FORK.md "Upstream sync 2026-10"): the lead's
// shell runs nothing it was not shown, a contributor's pull request runs code only after asking and
// lands with its commits intact, Codex commands are judged where they run, the user's deny rules
// survive shell quoting, and intake only talks to GitHub.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { inDir } from '../src/agents/codex/engine.js';
import { shellRuleMatches } from '../src/agents/team/rules.js';
import { loadConfig } from '../src/config.js';
import { classifyToolUse, type PolicyContext } from '../src/policy.js';
import { MERGE_OPTIONS } from '../src/protocol.js';
import { githubOrigin } from '../src/pulls.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const cwd = tempDir();
const lead: PolicyContext = { role: 'lead', cwd, readDirs: [], leadReadCommands: ['bd show'] };
const contributor: PolicyContext = { role: 'worker', cwd, readDirs: [], untrustedCode: true };
const bash = (command: string, ctx: PolicyContext) => classifyToolUse('Bash', { command }, ctx).action;

describe('the lead runs nothing it was not shown', () => {
  it.each([
    ['./cat package.json', 'ask'],
    ['bin/cat package.json', 'ask'],
    ['PATH=./bin:$PATH cat package.json', 'ask'],
    ['env PATH=. cat a', 'ask'],
    ['rg --pre ./evil x package.json', 'ask'],
    ['sed -f s.sed package.json', 'ask'],
    ['sed -nf s.sed package.json', 'ask'],
    ['awk -f p.awk package.json', 'ask'],
    ['bd show x --output=f', 'ask'],
    ['bd show x --json', 'allow'],
    ['cat package.json', 'allow'],
    ['git log --oneline -5', 'allow'],
    ['rg foo src', 'allow'],
  ])('%s -> %s', (command, expected) => {
    expect(bash(command, lead)).toBe(expected);
  });

  it('refuses writers and runners as declared read commands', () => {
    for (const bad of ['sort', 'fd', 'rg', 'less', 'make']) {
      expect(() => loadConfig(['--lead-read-commands', `${bad} x`], { AGENTCRAFT_HOME: tempDir() })).toThrow(/not allowed/);
    }
  });
});

describe("a contributor's pull request", () => {
  it('runs code only after asking; reads and edits as usual', () => {
    for (const c of ['npm test', 'node x.js', 'npx vitest', 'make', 'pytest', 'git commit -am fix']) expect(bash(c, contributor), c).toBe('ask');
    for (const c of ['cat package.json', 'git log --oneline', 'git diff main...HEAD']) expect(bash(c, contributor), c).toBe('allow');
    expect(classifyToolUse('Edit', { file_path: path.join(cwd, 'src', 'a.ts') }, contributor).action).toBe('allow');
    const key = classifyToolUse('Bash', { command: 'npm test' }, contributor);
    expect(key.action === 'ask' && key.ruleKeys[0]!.startsWith('untrusted:')).toBe(true);
    expect(bash('npm test', { ...contributor, alwaysAllow: key.action === 'ask' ? key.ruleKeys : [] })).toBe('allow');
  });
});

describe('Codex commands', () => {
  it('are judged in the directory they run in', () => {
    expect(inDir('git status', cwd, cwd)).toBe('git status');
    expect(inDir('cat config.json', '/home/x/.agentcraft', cwd)).toBe("cd '/home/x/.agentcraft' && cat config.json");
    expect(inDir('ls', undefined, cwd)).toBe('ls');
  });

  it("the user's deny rules see through quotes and wrappers", () => {
    for (const c of ['to"fu" apply', "'tofu' apply", 'tofu a"pply"', 'env X=1 tofu apply', 'command tofu apply', 'timeout 5 tofu apply', 'ls; tofu apply', 'echo $(tofu apply)']) {
      expect(shellRuleMatches('Bash(tofu apply:*)', 'Bash', { command: c }), c).toBe(true);
    }
    for (const c of ['tofu plan', 'echo tofu apply']) expect(shellRuleMatches('Bash(tofu apply:*)', 'Bash', { command: c }), c).toBe(false);
  });
});

describe('PR intake', () => {
  it('talks to GitHub only (the host, not the path)', async () => {
    const origin = (url: string) => githubOrigin('/x', async () => ({ code: 0, stdout: `${url}\n`, stderr: '', timedOut: false }) as never);
    expect(await origin('https://github.com/o/r.git')).toBeTruthy();
    expect(await origin('git@github.com:o/r.git')).toBeTruthy();
    expect(await origin('https://evil.example/github.com/o/r.git')).toBeUndefined();
    expect(await origin('https://github.com.evil.example/o/r.git')).toBeUndefined();
  });
});

describe("landing a contributor's pull request", () => {
  const g = (dir: string, ...args: string[]) => execFileSync('git', ['-C', dir, '-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { encoding: 'utf8' }).trim();
  let h: Harness;
  let home: string;
  let repo: string;
  beforeAll(async () => {
    home = tempDir();
    repo = await demoRepo();
    // the contributor's branch, as fetched at intake
    g(repo, 'branch', 'agentcraft/pr-7');
    const tmp = path.join(tempDir(), 'pr');
    g(repo, 'worktree', 'add', '-q', tmp, 'agentcraft/pr-7');
    fs.writeFileSync(path.join(tmp, 'CONTRIB.md'), 'from @someone\n');
    g(tmp, 'add', '-A');
    g(tmp, 'commit', '-q', '-m', 'contributor change');
    g(repo, 'worktree', 'remove', '--force', tmp);
    h = makeForeman(home, ['--backend', 'sim']);
    await h.fm.repos.add(repo);
  });
  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(path.dirname(repo));
  });

  it('refuses when the contributor commits were rewritten', async () => {
    const repoId = h.fm.repos.list()[0]!.id;
    const sha = g(repo, 'rev-parse', 'agentcraft/pr-7');
    const task = h.fm.tasks.create({ title: 'PR #7', description: 'review', deps: [], repoId, createdBy: 'marlow', startBranch: 'agentcraft/pr-7' });
    const wt = await h.fm.repos.createWorktree(repoId, 'kit', task, { startPoint: sha });
    h.fm.repos.pinContributor(repoId, wt.id, sha);
    // the worker amends the contributor's commit
    g(wt.path, 'commit', '-q', '--amend', '-m', 'rewritten');
    const d = h.fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Land it?', options: [...MERGE_OPTIONS], taskId: task.id, repoId, worktree: wt.id });
    await expect(h.fm.repos.land(h.fm.decisions.answer(d.id, 'Merge'))).rejects.toThrow(/contributor's commits/);
  });
});
