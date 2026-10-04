// cleanupAfterDays: worktrees and local agentcraft/* branches of tasks finished long ago go away
// (the first sweep is a dry run); a cancelled task's unmerged work and anything still in use stay.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { MERGE_OPTIONS } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const g = (cwd: string, ...args: string[]) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
const DAY = 86_400_000;

let h: Harness | undefined;
let home = '';
let repo = '';
afterEach(async () => {
  await h?.fm.close();
  rmrf(home);
  if (repo) rmrf(path.dirname(repo));
});

describe('cleanup of finished worktrees and branches', () => {
  it('dry run first, then removes; keeps a cancelled task\'s unmerged branch and recent / in-use work', async () => {
    home = tempDir();
    repo = await demoRepo();
    h = makeForeman(home, ['--backend', 'sim']);
    const fm = h.fm;
    await fm.repos.add(repo);
    const mk = async (title: string, agent: string) => {
      const t = fm.tasks.create({ title, createdBy: 'marlow', repoId: 'demo-app', assignee: agent });
      const wt = await fm.repos.createWorktree('demo-app', agent, t);
      fm.tasks.update(t.id, { branch: wt.branch, worktree: wt.id });
      return { t, wt };
    };
    // 1. merged long ago
    const merged = await mk('Merged one', 'kit');
    fs.writeFileSync(path.join(merged.wt.path, 'A.md'), 'a\n');
    fm.tasks.setStatus(merged.t.id, 'doing');
    fm.tasks.setStatus(merged.t.id, 'review');
    const d = fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Merge?', options: [...MERGE_OPTIONS], taskId: merged.t.id, repoId: 'demo-app', worktree: merged.wt.id });
    await fm.answerDecision(d.id, 'Merge');
    expect(fm.tasks.get(merged.t.id)!.status).toBe('done');
    // 2. cancelled long ago with committed, unmerged work (kept), 3. cancelled with nothing (removed)
    const rejected = await mk('Rejected one', 'wren');
    fs.writeFileSync(path.join(rejected.wt.path, 'B.md'), 'b\n');
    await fm.repos.abandon('demo-app', rejected.wt.id, 'wip');
    fm.tasks.setStatus(rejected.t.id, 'cancelled', { force: true });
    const empty = await mk('Empty one', 'juniper');
    fm.tasks.setStatus(empty.t.id, 'cancelled', { force: true });
    // 4. done recently (stays)
    const recent = await mk('Recent one', 'rowan');
    fm.tasks.setStatus(recent.t.id, 'done', { force: true });
    // age the old ones
    for (const t of [merged.t, rejected.t, empty.t]) fm.tasks.get(t.id)!.updatedAt = Date.now() - 30 * DAY;

    const first = await fm.cleanup();
    expect(first!.dryRun).toBe(true);
    expect(first!.worktrees.sort()).toEqual([`demo-app/${empty.wt.id}`, `demo-app/${merged.wt.id}`, `demo-app/${rejected.wt.id}`].sort());
    expect(first!.branches.sort()).toEqual([`demo-app:${empty.wt.branch}`, `demo-app:${merged.wt.branch}`].sort());
    expect(first!.kept).toEqual([`demo-app:${rejected.wt.branch} (a cancelled task's unmerged work)`]);
    expect(fm.repos.get('demo-app')!.worktrees).toHaveLength(4); // nothing removed yet
    expect(fs.existsSync(empty.wt.path)).toBe(true);
    expect(fm.store.data.feed.some((f) => /Cleanup \(first run, nothing removed\): would remove 3 worktrees and 2 branches/.test(f.text))).toBe(true);

    const second = await fm.cleanup(Date.now() + DAY);
    expect(second!.dryRun).toBe(false);
    const left = fm.repos.get('demo-app')!.worktrees.map((w) => w.id);
    expect(left).toEqual([recent.wt.id]);
    expect(fs.existsSync(empty.wt.path)).toBe(false);
    const heads = g(repo, 'branch', '--list', 'agentcraft/*', '--format=%(refname:short)').split('\n');
    expect(heads).toContain(rejected.wt.branch); // its work stays
    expect(heads).toContain(recent.wt.branch);
    expect(heads).not.toContain(merged.wt.branch);
    expect(heads).not.toContain(empty.wt.branch);
    expect(g(repo, 'show', 'HEAD:A.md')).toBe('a'); // the merged work is on the base
  });

  it('cleanupAfterDays 0 turns it off; default 14', async () => {
    home = tempDir();
    expect(loadConfig(['--home', home], {}).cleanupAfterDays).toBe(14);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ cleanupAfterDays: 0 }));
    h = makeForeman(home, ['--backend', 'sim']);
    expect(await h.fm.cleanup()).toBeUndefined();
  });
});
