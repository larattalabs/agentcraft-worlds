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
import { lineSplitter } from '../src/util/lines.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const cwd = tempDir();
const lead: PolicyContext = { role: 'lead', cwd, readDirs: [], leadReadCommands: ['bd show'] };
const contributor: PolicyContext = { role: 'worker', cwd, readDirs: [], untrustedCode: true };
const bash = (command: string, ctx: PolicyContext) => classifyToolUse('Bash', { command }, ctx).action;

describe("the lead's shell: a best-effort read-only nudge", () => {
  it.each([
    // obvious writes ask
    ['rm -rf src', 'ask'],
    ['git commit -am x', 'ask'],
    ['echo x > notes.txt', 'ask'],
    ["sed -i 's/a/b/' package.json", 'ask'],
    ['npm install left-pad', 'ask'],
    ['bd show x --output=f', 'ask'],
    ['bd show x -oout.json', 'ask'],
    // reads run, including text that only looks like code
    ["rg 'PATH=.' src", 'allow'],
    ["echo 'literal $(./cat package.json)'", 'allow'],
    ['bd show x --json', 'allow'],
    ['cat package.json', 'allow'],
    ['git log --oneline -5', 'allow'],
    ['rg foo src', 'allow'],
    ['timeout 5 cat package.json', 'allow'],
    // never: push, and the Foreman's own files
    ['git push origin main', 'deny'],
  ])('%s -> %s', (command, expected) => {
    expect(bash(command, lead)).toBe(expected);
  });

  it("the Foreman's own files stay off limits for the lead", () => {
    const home = tempDir();
    const ctx: PolicyContext = { ...lead, foreman: { home } };
    expect(bash(`cat ${path.join(home, 'config.json')}`, ctx)).toBe('deny');
  });

  it('refuses writers and runners as declared read commands', () => {
    for (const bad of ['sort', 'fd', 'rg', 'less', 'make', 'uniq', 'yq']) {
      expect(() => loadConfig(['--lead-read-commands', `${bad} x`], { AGENTCRAFT_HOME: tempDir() })).toThrow(/not allowed/);
    }
  });
});

describe("a contributor's pull request", () => {
  it('runs code only after asking; reads and edits as usual', () => {
    for (const c of ['npm test', 'node x.js', 'npx vitest', 'make', 'pytest', 'git commit -am fix', 'npm install', 'sh p.sh']) {
      // asked as code from the pull request (forced in auto mode), keyed to the exact command
      const v = classifyToolUse('Bash', { command: c }, contributor);
      expect(v.action === 'ask' && v.reason.includes("contributor's pull request") && v.ruleKeys.every((k) => k.startsWith('untrusted:')), c).toBe(true);
    }
    // "reads" that would still run code nobody was shown
    for (const c of [
      './cat package.json',
      'PATH=./bin:$PATH cat package.json',
      'rg --pre ./evil x package.json',
      'sed -f s.sed package.json',
      'awk -fp.awk package.json',
      "sh -c './cat package.json'",
      'git ls-files | xargs cat',
      'echo "$(./cat package.json)"',
      'export PATH=./bin:$PATH; cat package.json',
      'printf -v PATH "./bin:%s" "$PATH"; cat package.json',
    ]) {
      expect(bash(c, contributor), c).toBe('ask');
    }
    for (const c of ["rg 'PATH=.' src", "echo 'literal $(x)'"]) expect(bash(c, contributor), c).toBe('allow');
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
    for (const c of ['to"fu" apply', "'tofu' apply", 'tofu a"pply"', 'env X=1 tofu apply', 'command tofu apply', 'timeout 5 tofu apply', 'ls; tofu apply', 'echo $(tofu apply)', 'env -u LANG tofu apply', 'timeout -s TERM 5 tofu apply', 'echo "$(tofu apply)"', 'echo `tofu apply`']) {
      expect(shellRuleMatches('Bash(tofu apply:*)', 'Bash', { command: c }), c).toBe(true);
    }
    for (const c of ['tofu plan', 'echo tofu apply']) expect(shellRuleMatches('Bash(tofu apply:*)', 'Bash', { command: c }), c).toBe(false);
    const deploy = (c: string) => shellRuleMatches('Bash(npm run deploy:*)', 'Bash', { command: c });
    expect(deploy('env -S "npm run deploy"')).toBe(true);
    expect(deploy(`echo "$(printf ')'; npm run deploy)"`)).toBe(true);
    expect(deploy(`echo "it's $(npm run deploy)"`)).toBe(true);
    expect(deploy(`echo 'not $(npm run deploy)'`)).toBe(false);
    expect(deploy(`${'$('.repeat(64)}npm run deploy${')'.repeat(64)}`)).toBe(true);
    // shell command strings
    expect(deploy("sh -c 'npm run deploy'")).toBe(true);
    expect(deploy('bash -lc "npm run deploy --prod"')).toBe(true);
    expect(deploy("env sh -c 'npm run deploy'")).toBe(true);
    expect(deploy("env -u LANG /bin/sh -c 'cd x && npm run deploy'")).toBe(true);
    expect(deploy(`echo "$(sh -c 'npm run deploy')"`)).toBe(true);
    expect(deploy("sh -c 'npm run build'")).toBe(false);
  });

  it('stderr lines are whole; an endless one is dropped, never cut', () => {
    const got: string[] = [];
    const s = lineSplitter((l) => got.push(l), 10);
    s.push('ab');
    s.push('c\nsecret-');
    s.push('value-way-too-long');
    s.push('-still\nnext\n');
    s.push('tail');
    s.end();
    expect(got).toEqual(['abc', 'next', 'tail']);
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

  it('refuses a contributor worktree that has no recorded starting commit', async () => {
    const repoId = h.fm.repos.list()[0]!.id;
    const task = h.fm.tasks.create({ title: 'PR #7 again', description: 'review', deps: [], repoId, createdBy: 'marlow', startBranch: 'agentcraft/pr-7' });
    const wt = await h.fm.repos.createWorktree(repoId, 'juniper', task, { startPoint: 'agentcraft/pr-7' });
    fs.appendFileSync(path.join(wt.path, 'CONTRIB.md'), 'fix\n');
    const d = h.fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Land it?', options: [...MERGE_OPTIONS], taskId: task.id, repoId, worktree: wt.id });
    await expect(h.fm.repos.land(h.fm.decisions.answer(d.id, 'Merge'))).rejects.toThrow(/no recorded starting commit/);
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
