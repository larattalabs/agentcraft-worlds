// Regressions for the security review of the client token / settings work (foreman/settings).
// One describe per finding; each failed before its fix.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, afterEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_CONTEXT, instructionsBlock } from '../src/agents/claude/context.js';
import { createClientToken } from '../src/clienttoken.js';
import { loadConfig } from '../src/config.js';
import { withGitSafety } from '../src/gitsafety.js';
import { foremanPrivateCommand, foremanPrivatePath, type PolicyContext } from '../src/policy.js';
import type { ClientMessage, Outbound } from '../src/protocol.js';
import { restartCommand } from '../src/restart.js';
import { writeFileAtomic } from '../src/util/fsx.js';
import { jsonErrorPosition } from '../src/util/jsonpos.js';
import { run } from '../src/util/proc.js';
import { demoRepo, makeForeman, rmrf, tempDir } from './helpers.js';

const dirs: string[] = [];
afterAll(() => {
  for (const d of dirs) rmrf(d);
});

/** A user home holding a Foreman home with a profile, its token, config and a worker worktree. */
function layout() {
  const userHome = tempDir('ac-sec-');
  dirs.push(userHome);
  const home = path.join(userHome, '.agentcraft');
  const profile = path.join(home, 'claude');
  const wt = path.join(profile, 'worktrees', 'demo', 'kit-t2');
  fs.mkdirSync(wt, { recursive: true });
  const tokenFile = path.join(profile, 'client.token');
  fs.writeFileSync(tokenFile, 'TOKEN-SECRET-1234\n');
  fs.writeFileSync(path.join(home, 'config.json'), '{"claude":{"note":"CONFIG-SECRET"}}');
  const foreman = { home, port: 7878, tokenFile };
  return { userHome, home, profile, wt, tokenFile, foreman };
}

describe('finding 1: instruction imports never inline the Foreman\'s files', () => {
  it('skips @imports of the token, the config and the profile, with a note', () => {
    const l = layout();
    fs.writeFileSync(path.join(l.wt, 'CLAUDE.md'), `# Repo rules\n\nSee @../../../client.token and @${path.join(l.home, 'config.json')} and @~/.agentcraft/claude/client.token\n\nKeep tests green.\n`);
    const deny = (abs: string) => foremanPrivatePath(abs, l.foreman);
    const block = instructionsBlock(DEFAULT_CONTEXT, l.wt, 'Alex', l.userHome, undefined, deny);
    expect(block).toContain('Keep tests green.');
    expect(block).not.toContain('TOKEN-SECRET');
    expect(block).not.toContain('CONFIG-SECRET');
    expect(block).toContain("(import @../../../client.token skipped: AgentCraft's own files are off limits)");
  });

  it('skips an instruction source that links to them, and imports reached through a link', () => {
    const l = layout();
    try {
      fs.symlinkSync(l.tokenFile, path.join(l.wt, 'CLAUDE.md'));
      fs.symlinkSync(path.join(l.home, 'config.json'), path.join(l.wt, 'notes.md'));
    } catch {
      return; // no symlinks (Windows without the privilege)
    }
    fs.writeFileSync(path.join(l.wt, 'AGENTS.md'), 'Read @notes.md first.\n');
    const block = instructionsBlock(DEFAULT_CONTEXT, l.wt, 'Alex', l.userHome, undefined, (abs) => foremanPrivatePath(abs, l.foreman));
    expect(block).not.toContain('TOKEN-SECRET');
    expect(block).not.toContain('CONFIG-SECRET');
    expect(block).toContain('skipped');
  });
});

describe('finding 3: AGENTCRAFT_CLIENT_TOKEN never reaches a spawned process', () => {
  const saved = process.env.AGENTCRAFT_CLIENT_TOKEN;
  afterEach(() => {
    if (saved === undefined) delete process.env.AGENTCRAFT_CLIENT_TOKEN;
    else process.env.AGENTCRAFT_CLIENT_TOKEN = saved;
  });

  it('is dropped after the repo env is merged (git safety env for agents, CI and setup)', () => {
    const env = withGitSafety({ PATH: '/bin', AGENTCRAFT_CLIENT_TOKEN: 'a' }, { AGENTCRAFT_CLIENT_TOKEN: 'b', agentcraft_client_token: 'c' });
    expect(Object.keys(env).filter((k) => /client_token/i.test(k))).toEqual([]);
  });

  it('is not passed to processes run() starts, whatever env they are given', async () => {
    const r = await run(process.execPath, ['-e', 'console.log(process.env.AGENTCRAFT_CLIENT_TOKEN ?? "none")'], { env: { ...process.env, AGENTCRAFT_CLIENT_TOKEN: 'tok' } });
    expect(r.stdout.trim()).toBe('none');
  });

  it('is not passed to a restarted Foreman', () => {
    const cmd = restartCommand({ execPath: 'node', execArgv: [], script: 'main.ts', argv: [], cwd: '/', env: { PATH: '/bin', AGENTCRAFT_CLIENT_TOKEN: 'tok' } });
    expect(cmd.env).toEqual({ PATH: '/bin' });
  });

  it('cannot be set or copied in through repoSettings env', async () => {
    const repoPath = await demoRepo();
    dirs.push(path.dirname(repoPath));
    const home = tempDir();
    dirs.push(home);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repoPath]: { env: { AGENTCRAFT_CLIENT_TOKEN: 'set', COPY: '$AGENTCRAFT_CLIENT_TOKEN', COPY2: 'x${AGENTCRAFT_CLIENT_TOKEN}y', OK: 'fine' } } } }));
    const h = makeForeman(home);
    try {
      const repo = await h.fm.repos.add(repoPath);
      expect(h.fm.repos.envFor(repo.id, { AGENTCRAFT_CLIENT_TOKEN: 'tok' })).toEqual({ COPY: '', COPY2: 'xy', OK: 'fine' });
    } finally {
      await h.fm.close();
    }
  });
});

describe('finding 4: Bash guard sees through quoting and recursive searches above the home', () => {
  const l = layout();
  const ctx: PolicyContext = { role: 'worker', cwd: l.wt, home: l.userHome, foreman: l.foreman };
  it('denies them', () => {
    for (const command of [
      'rg --hidden --no-ignore . ~',
      "cat ~/.agent'craft'/config.json",
      'cat ~/.agent"craft"/claude/state.json',
      'cat ~/.agent\\craft/config.json',
      "cat client'.'token",
      'cd / && grep -r TOKEN .',
      'cd ~ && rg TOKEN',
      'grep -rn TOKEN ~',
      `grep -R TOKEN ${path.dirname(l.userHome)}`,
      'ls -R ~',
      'tar czf /tmp/x.tgz ~',
      'cp -r ~ /tmp/copy',
      'du -sh /',
      'rsync -a ~/ /tmp/b',
      'find ~ -type f',
      'sudo rg TOKEN /',
    ]) {
      expect(foremanPrivateCommand(command, ctx), command).toBeDefined();
    }
  });

  it('lets ordinary commands in the worktree through', () => {
    for (const command of ['rg TODO', 'rg TODO src', 'grep -rn TODO src', 'ls -la', 'find . -name "*.ts"', 'tar czf out.tgz src', 'cp -r src lib', 'du -sh .', `cd ${l.wt} && npm test`]) {
      expect(foremanPrivateCommand(command, ctx), command).toBeUndefined();
    }
  });
});

describe('finding 5: no prototype pollution through agent ids or setting keys', () => {
  it('refuses reserved ids at load', () => {
    const home = tempDir();
    dirs.push(home);
    expect(() => loadConfig(['--home', home, '--leads', 'marlow,__proto__'], {})).toThrow(/bad lead id "__proto__"/);
    expect(() => loadConfig(['--home', home, '--leads', 'marlow,constructor'], {})).toThrow(/bad lead id/);
    expect(() => loadConfig(['--home', home, '--workers', 'kit,__proto__'], {})).toThrow(/bad worker id "__proto__"/);
    fs.writeFileSync(path.join(home, 'config.json'), '{"claude":{"agents":{"__proto__":{"model":"polluted"}},"leads":["marlow","prototype"]}}');
    expect(() => loadConfig(['--home', home], {})).toThrow(/bad lead id "prototype"/);
    fs.writeFileSync(path.join(home, 'config.json'), '{"claude":{"agents":{"__proto__":{"model":"polluted"}}}}');
    const cfg = loadConfig(['--home', home], {});
    expect(Object.keys(cfg.claude.agents)).toEqual([]);
    expect(({} as Record<string, unknown>).model).toBeUndefined();
  });

  it('refuses reserved key segments in config.set', async () => {
    const home = tempDir();
    dirs.push(home);
    const h = makeForeman(home);
    try {
      for (const key of ['claude.agents.__proto__.model', '__proto__.polluted', 'claude.constructor.prototype.x']) {
        const out: Outbound[] = [];
        await h.fm.handle({ v: 1, id: 'p', type: 'config.set', changes: [{ key, value: 'opus' }] } as ClientMessage, (m) => out.push(m));
        expect(out.find((m) => m.type === 'ack')).toMatchObject({ ok: false });
      }
      expect(({} as Record<string, unknown>).model).toBeUndefined();
      expect(({} as Record<string, unknown>).polluted).toBeUndefined();
      expect(fs.existsSync(path.join(home, 'config.json'))).toBe(false);
    } finally {
      await h.fm.close();
    }
  });
});

describe('finding 6: temp files are unguessable and created exclusively', () => {
  afterEach(() => vi.restoreAllMocks());

  it('writeFileAtomic opens a random temp name with O_EXCL and never writes through a planted link', () => {
    const dir = tempDir();
    dirs.push(dir);
    const spy = vi.spyOn(fs, 'openSync');
    writeFileAtomic(path.join(dir, 'state.json'), '{}');
    const [tmp, flags] = spy.mock.calls[0]!;
    expect(flags).toBe('wx');
    expect(String(tmp)).not.toContain(`.${process.pid}.`);
    expect(String(tmp)).toMatch(/state\.json\.[0-9a-f]{24}\.tmp$/);
    expect(fs.readdirSync(dir)).toEqual(['state.json']);
  });

  it('the token file is created 0600 from the start', () => {
    const dir = tempDir();
    dirs.push(dir);
    const spy = vi.spyOn(fs, 'openSync');
    const { file } = createClientToken(dir);
    const call = spy.mock.calls.find((c) => String(c[0]).startsWith(`${file}.`))!;
    expect(call[1]).toBe('wx');
    expect(call[2]).toBe(0o600);
  });
});

describe('finding 7: JSON errors never quote the file', () => {
  it('config.get / config.set say where config.json breaks, without its text', async () => {
    const home = tempDir();
    dirs.push(home);
    const h = makeForeman(home);
    // broken while the Foreman runs (a hand edit)
    fs.writeFileSync(path.join(home, 'config.json'), '{\n  "claude": {"apiKey": sk-secret-123}\n}\n');
    try {
      for (const type of ['config.get', 'config.set']) {
        const out: Outbound[] = [];
        await h.fm.handle({ v: 1, id: 'j', type, ...(type === 'config.set' ? { changes: [{ key: 'claude.prWatch', value: 'on' }] } : {}) } as ClientMessage, (m) => out.push(m));
        const text = JSON.stringify(out);
        expect(text).not.toContain('sk-secret');
        expect(out.find((m) => m.type === 'ack')).toMatchObject({ ok: false, error: expect.stringMatching(/config\.json is not valid JSON at line 2, column 24; fix it by hand first$/) });
      }
    } finally {
      await h.fm.close();
    }
  });

  it('finds the error position like JSON.parse does', () => {
    expect(jsonErrorPosition('{"a": 1}')).toBeUndefined();
    expect(jsonErrorPosition('[1, 2, {"x": [true, false, null, -1.5e3, "\\u00e9\\n"]}]')).toBeUndefined();
    expect(jsonErrorPosition('{"a": 1,}')).toEqual({ line: 1, column: 9 });
    expect(jsonErrorPosition('{"a"\n: tru}')).toEqual({ line: 2, column: 6 });
    expect(jsonErrorPosition('{"a": 1} x')).toEqual({ line: 1, column: 10 });
    expect(jsonErrorPosition('')).toEqual({ line: 1, column: 1 });
  });
});
