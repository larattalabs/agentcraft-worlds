// Per-repo settings from config.json: test command, worktree setup command, copied files.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness;
let home: string;
let repoPath: string;

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  // untracked files the worktree should get (the demo repo ignores nothing, so keep them out of git)
  fs.writeFileSync(path.join(repoPath, '.env'), 'SECRET=1\n');
  fs.mkdirSync(path.join(repoPath, 'local-config'));
  fs.writeFileSync(path.join(repoPath, 'local-config', 'a.json'), '{}');
  fs.writeFileSync(
    path.join(home, 'config.json'),
    JSON.stringify({
      repoSettings: {
        [repoPath]: {
          ci: 'node -e "process.exit(0)"',
          setup: 'node -e "require(\'fs\').appendFileSync(\'setup-ran.txt\', \'x\')"',
          copy: ['.env', 'local-config', '../outside', '/etc/hosts', 'missing.txt'],
        },
      },
    }),
  );
  h = makeForeman(home, ['--backend', 'sim']);
  await h.fm.repos.add(repoPath);
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('repoSettings', () => {
  it('reads repoSettings from config.json, keyed by resolved path', () => {
    expect(h.cfg.repoSettings[path.resolve(repoPath)]?.ci).toBe('node -e "process.exit(0)"');
    expect(h.fm.repos.settingsFor('demo-app').copy).toContain('.env');
    expect(h.fm.repos.settingsFor('nope')).toEqual({});
  });

  it('uses the repo ci command over --ci and detection', () => {
    expect(h.fm.repos.testCommand('demo-app', repoPath, 'npm run other')).toBe('node -e "process.exit(0)"');
  });

  it('copies configured files and runs setup once per worktree', async () => {
    const t = h.fm.tasks.create({ title: 'Setup check', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit' });
    const wt = await h.fm.repos.createWorktree('demo-app', 'kit', t);
    const first = await h.fm.repos.prepareWorktree('demo-app', wt.id);
    expect(first.copied).toEqual(['.env', 'local-config']);
    expect(first.setup?.ok).toBe(true);
    expect(fs.readFileSync(path.join(wt.path, '.env'), 'utf8')).toBe('SECRET=1\n');
    expect(fs.existsSync(path.join(wt.path, 'local-config', 'a.json'))).toBe(true);
    expect(fs.existsSync(path.join(path.dirname(wt.path), 'outside'))).toBe(false);
    const again = await h.fm.repos.prepareWorktree('demo-app', wt.id);
    expect(again).toEqual({ copied: [] });
    expect(fs.readFileSync(path.join(wt.path, 'setup-ran.txt'), 'utf8')).toBe('x');
  });

  it('reports a failing setup instead of throwing', async () => {
    const settings = h.fm.repos.settingsFor('demo-app');
    const saved = settings.setup;
    settings.setup = 'node -e "console.error(\'boom\'); process.exit(3)"';
    try {
      const t = h.fm.tasks.create({ title: 'Setup fails', createdBy: 'marlow', repoId: 'demo-app', assignee: 'juniper' });
      const wt = await h.fm.repos.createWorktree('demo-app', 'juniper', t);
      const res = await h.fm.repos.prepareWorktree('demo-app', wt.id);
      expect(res.setup?.ok).toBe(false);
      expect(res.setup?.output).toContain('boom');
    } finally {
      settings.setup = saved;
    }
  });
});

describe('detectTestCommand', () => {
  it("uses the package manager of the repository's lockfile", () => {
    const dir = tempDir();
    try {
      fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ scripts: { test: 'vitest run' } }));
      expect(h.fm.repos.detectTestCommand(dir)).toBe('npm test --silent');
      fs.writeFileSync(path.join(dir, 'pnpm-lock.yaml'), '');
      expect(h.fm.repos.detectTestCommand(dir)).toBe('pnpm -s test');
      fs.rmSync(path.join(dir, 'pnpm-lock.yaml'));
      fs.writeFileSync(path.join(dir, 'yarn.lock'), '');
      expect(h.fm.repos.detectTestCommand(dir)).toBe('yarn test');
      fs.rmSync(path.join(dir, 'yarn.lock'));
      fs.writeFileSync(path.join(dir, 'bun.lock'), '');
      expect(h.fm.repos.detectTestCommand(dir)).toBe('bun run test');
    } finally {
      rmrf(dir);
    }
  });
});
