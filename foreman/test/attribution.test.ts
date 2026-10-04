// C8: nothing the Foreman lands carries AgentCraft or Claude attribution (trailers, footers, PR text).
import { afterEach, describe, expect, it } from 'vitest';
import { prDescription } from '../src/foreman.js';
import { MERGE_OPTIONS } from '../src/protocol.js';
import type { LandResult } from '../src/repos.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';
import path from 'node:path';

describe('prDescription', () => {
  it('keeps the summary and drops tool attribution lines', () => {
    expect(prDescription('Adds a changelog.\n\nTests: npm test')).toBe('Adds a changelog.\n\nTests: npm test');
    expect(prDescription('Fix the export.\n\nCo-authored-by: AgentCraft Kit <kit@agentcraft.local>\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\nBuilt and reviewed in AgentCraft (t3)')).toBe('Fix the export.');
    expect(prDescription(undefined)).toBe('');
  });
});

describe('landing a task as a pull request', () => {
  let h: Harness | undefined;
  let home = '';
  let repo = '';
  afterEach(async () => {
    await h?.fm.close();
    rmrf(home);
    if (repo) rmrf(path.dirname(repo));
  });

  it('passes the worker summary only: no "Built and reviewed in AgentCraft" footer, no review transcript', async () => {
    home = tempDir();
    repo = await demoRepo();
    h = makeForeman(home, ['--backend', 'sim']);
    const fm = h.fm;
    await fm.repos.add(repo);
    const t = fm.tasks.create({ title: 'Add a changelog', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit' });
    fm.tasks.setStatus(t.id, 'doing');
    fm.tasks.setStatus(t.id, 'review', { summary: 'Adds a changelog.' });
    let seen: { commitMessage?: string; title?: string; description?: string } | undefined;
    fm.repos.land = async (_d, opts = {}) => {
      seen = opts;
      return { kind: 'merge', sha: 'abc1234', base: 'main', branch: 'b', files: 1 } as LandResult;
    };
    const d = fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Open a PR?', options: [...MERGE_OPTIONS], context: "Marlow's review: looks good, approved by the lead", taskId: t.id, repoId: 'demo-app', worktree: 'kit-t1' });
    await fm.answerDecision(d.id, 'Merge');
    expect(seen?.title).toBe('Add a changelog');
    expect(seen?.description).toBe('Adds a changelog.');
    expect(JSON.stringify(seen)).not.toMatch(/agentcraft|claude|co-authored-by|review:/i);
  });
});
