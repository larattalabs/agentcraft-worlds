// B8: an approved edit to a protected file (repoSettings.protect) stayed uncommitted: the tests ran
// with it, the merge / PR landed without it, silently. Now the merge decision lists such edits,
// landing is refused while they are there, and a Foreman-owned question drops them (saved first).
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { MERGE_OPTIONS } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const g = (cwd: string, ...args: string[]) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();

let h: Harness | undefined;
let home = '';
let repo = '';
afterEach(async () => {
  await h?.fm.close();
  rmrf(home);
  if (repo) rmrf(path.dirname(repo));
});

describe('uncommitted edits to protected files', () => {
  it('are listed on the merge decision, refuse landing, and are dropped (saved) on request', async () => {
    home = tempDir();
    repo = await demoRepo();
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repo]: { protect: ['README.md', 'secrets/'], ci: 'node -e "process.exit(0)"' } } }));
    h = makeForeman(home, ['--backend', 'sim']);
    const fm = h.fm;
    await fm.repos.add(repo);
    const t = fm.tasks.create({ title: 'Add a changelog', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit' });
    const wt = await fm.repos.createWorktree('demo-app', 'kit', t);
    fm.tasks.update(t.id, { branch: wt.branch, worktree: wt.id });
    fm.tasks.setStatus(t.id, 'doing');
    // the real work, plus an approved edit to a protected file and a new protected file
    fs.writeFileSync(path.join(wt.path, 'CHANGELOG.md'), '# Changes\n');
    fs.appendFileSync(path.join(wt.path, 'README.md'), '\nlocal connection string\n');
    fs.mkdirSync(path.join(wt.path, 'secrets'));
    fs.writeFileSync(path.join(wt.path, 'secrets', 'dev.json'), '{"key":"x"}\n');
    expect((await fm.repos.protectedUncommitted('demo-app', wt.id)).sort()).toEqual(['README.md', 'secrets/dev.json']);
    fm.tasks.setStatus(t.id, 'review', { summary: 'Adds a changelog.' });

    const d = fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Merge?', options: [...MERGE_OPTIONS], context: 'Adds a changelog.', taskId: t.id, repoId: 'demo-app', worktree: wt.id });
    await until(() => /Uncommitted edits to protected files: README\.md, secrets\/dev\.json/.test(fm.decisions.get(d.id)!.context ?? ''));

    const headBefore = g(repo, 'rev-parse', 'HEAD');
    await fm.answerDecision(d.id, 'Merge');
    expect(fm.decisions.get(d.id)!.status).toBe('open'); // refused, open again
    expect(fm.decisions.get(d.id)!.context).toMatch(/Merge refused: uncommitted edits to protected files \(README\.md, secrets\/dev\.json\)/);
    expect(g(repo, 'rev-parse', 'HEAD')).toBe(headBefore);
    const q = fm.decisions.open().find((x) => x.kind === 'question' && x.taskId === t.id)!;
    expect(q.options).toEqual(['Drop them', 'Leave them']);
    expect(q.textAllowed).toBe(false);
    expect(fm.ownsDecision(q.id)).toBe(true);
    // a second refused approval does not ask twice
    await fm.answerDecision(d.id, 'Merge');
    expect(fm.decisions.open().filter((x) => x.kind === 'question' && x.taskId === t.id)).toHaveLength(1);

    await fm.answerDecision(q.id, 'Drop them');
    expect(fm.decisions.get(q.id)!.status).toBe('answered');
    expect(fs.readFileSync(path.join(wt.path, 'README.md'), 'utf8')).not.toContain('local connection string');
    expect(fs.existsSync(path.join(wt.path, 'secrets', 'dev.json'))).toBe(false);
    expect(fs.existsSync(path.join(wt.path, 'CHANGELOG.md'))).toBe(true); // the real work stays
    const saved = fs.readdirSync(fm.repos.protectedEditsDir).map((x) => path.join(fm.repos.protectedEditsDir, x));
    expect(saved).toHaveLength(1);
    expect(fs.readFileSync(path.join(saved[0]!, 'changes.patch'), 'utf8')).toContain('+local connection string');
    expect(fs.readFileSync(path.join(saved[0]!, 'files', 'secrets', 'dev.json'), 'utf8')).toContain('"key"');
    expect(fm.decisions.get(d.id)!.context).toMatch(/Protected edits dropped \(saved at .*\); tests without them: pass/);
    expect(fm.tasks.get(t.id)!.ci).toBe('pass');

    await fm.answerDecision(d.id, 'Merge');
    expect(fm.decisions.get(d.id)!.status).toBe('answered');
    expect(g(repo, 'show', 'HEAD:CHANGELOG.md')).toBe('# Changes');
    expect(g(repo, 'show', 'HEAD:README.md')).not.toContain('local connection string');
  });

  it('"Leave them" keeps them and landing stays refused', async () => {
    home = tempDir();
    repo = await demoRepo();
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repo]: { protect: ['README.md'] } } }));
    h = makeForeman(home, ['--backend', 'sim']);
    const fm = h.fm;
    await fm.repos.add(repo);
    const t = fm.tasks.create({ title: 'x', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit' });
    const wt = await fm.repos.createWorktree('demo-app', 'kit', t);
    fs.writeFileSync(path.join(wt.path, 'NEW.md'), 'x\n');
    fs.appendFileSync(path.join(wt.path, 'README.md'), '\nmine\n');
    const d = fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Merge?', options: [...MERGE_OPTIONS], taskId: t.id, repoId: 'demo-app', worktree: wt.id });
    await fm.answerDecision(d.id, 'Merge');
    const q = fm.decisions.open().find((x) => x.kind === 'question')!;
    await fm.answerDecision(q.id, 'Leave them');
    expect(fs.readFileSync(path.join(wt.path, 'README.md'), 'utf8')).toContain('mine');
    await fm.answerDecision(d.id, 'Merge');
    expect(fm.decisions.get(d.id)!.status).toBe('open');
  });
});
