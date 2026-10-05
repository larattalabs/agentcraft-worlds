// config.get / config.set / repo.agents (docs/HUB.md "Team and Settings tabs").
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { ClientMessage, Outbound, SettingDef } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const dirs: string[] = [];
const harnesses: Harness[] = [];
afterEach(async () => {
  for (const h of harnesses.splice(0)) await h.fm.close();
  for (const d of dirs.splice(0)) rmrf(d);
});

function setup(file?: unknown, args: string[] = []): Harness & { file: string } {
  const home = tempDir();
  dirs.push(home);
  const file_ = path.join(home, 'config.json');
  if (file !== undefined) fs.writeFileSync(file_, typeof file === 'string' ? file : JSON.stringify(file, null, 2) + '\n');
  const h = makeForeman(home, ['--backend', 'claude', ...args]);
  harnesses.push(h);
  return { ...h, file: file_ };
}

type Ack = Extract<Outbound, { type: 'ack' }>;
let nextId = 1;
async function call(h: Harness, msg: Record<string, unknown>): Promise<Ack> {
  const out: Outbound[] = [];
  const id = `t${nextId++}`;
  await h.fm.handle({ v: 1, id, ...msg } as unknown as ClientMessage, (m) => out.push(m));
  return out.find((m): m is Ack => m.type === 'ack' && m.re === id)!;
}

const settingsOf = (ack: Ack) => (ack.result as { settings: SettingDef[] }).settings;
const byKey = (defs: SettingDef[], key: string) => defs.find((d) => d.key === key)!;
const readFile = (f: string) => JSON.parse(fs.readFileSync(f, 'utf8')) as Record<string, any>;

describe('config.get', () => {
  it('describes every global setting, with values, sources and no secrets', async () => {
    const h = setup({
      claude: {
        leadModel: 'claude-opus-4-1',
        prWatch: 'on',
        context: { mcpServers: { gh: { command: 'npx', args: ['--token', 'arg-secret'], env: { GH_TOKEN: 'env-secret' } }, web: { type: 'http', url: 'https://mcp.example.com/x?key=url-secret', headers: { Authorization: 'hdr-secret' } } } },
      },
      repoSettings: { '/nowhere': { env: { API_KEY: 'repo-secret' } } },
    });
    const ack = await call(h, { type: 'config.get' });
    expect(ack.ok).toBe(true);
    expect((ack.result as { file: string }).file).toBe(h.file);
    const defs = settingsOf(ack);
    const text = JSON.stringify(ack);
    for (const secret of ['arg-secret', 'env-secret', 'url-secret', 'hdr-secret', 'repo-secret']) expect(text).not.toContain(secret);

    expect(new Set(defs.map((d) => d.group))).toEqual(new Set(['team', 'models', 'general', 'permissions', 'context', 'subagents', 'prs', 'usage']));
    const lead = byKey(defs, 'claude.leadModel');
    expect(lead).toMatchObject({ type: 'model', value: 'claude-opus-4-1', default: 'opus', source: 'file', live: true });
    expect(lead.options).toEqual(['default', 'opus', 'sonnet', 'claude-opus-4-1']);
    expect(defs.filter((d) => d.type === 'model').every((d) => !d.options!.some((o) => /haiku/i.test(o)))).toBe(true);
    expect(byKey(defs, 'claude.workerModel')).toMatchObject({ value: 'sonnet', source: 'default' });
    expect(byKey(defs, 'claude.prWatch')).toMatchObject({ type: 'enum', options: ['off', 'observe', 'on'], value: 'on', source: 'file', live: true });
    // the test harness passes --no-notify and --user-name
    expect(byKey(defs, 'notify')).toMatchObject({ source: 'flag', overriddenBy: '--no-notify', value: false });
    expect(byKey(defs, 'userName')).toMatchObject({ source: 'flag', overriddenBy: '--user-name', value: 'Alex' });
    expect(byKey(defs, 'claude.leads')).toMatchObject({ type: 'agentList', live: false, value: ['marlow', 'ines', 'bram', 'cass'] });
    expect(byKey(defs, 'claude.leads').options).toEqual(['marlow', 'ines', 'bram', 'cass']);
    expect(byKey(defs, 'claude.workers').options).toEqual(['juniper', 'kit', 'wren', 'rowan', 'tove']);
    expect(byKey(defs, 'claude.agents.kit.effort')).toMatchObject({ type: 'effort', value: 'default', options: ['default', 'low', 'medium', 'high', 'xhigh', 'max'] });
    const ines = byKey(defs, 'claude.agents.ines.title');
    expect(ines.value).toBe(h.fm.cast.find((c) => c.id === 'ines')!.title);
    expect(ines).toMatchObject({ default: ines.value, source: 'default' });
    expect(byKey(defs, 'claude.maxConcurrent')).toMatchObject({ type: 'int', min: 1, max: 10, value: 3 });
    expect(byKey(defs, 'claude.useClaudeLogin')).toMatchObject({ live: false, group: 'usage' });
    // contract S2 (docs/WAVE3.md): editable, restart-required; no env values, credential arguments or URL queries
    const mcp = byKey(defs, 'claude.context.mcpServers');
    expect(mcp).toMatchObject({ type: 'mcpServers', live: false, source: 'file', default: [] });
    expect(mcp.readOnly).toBeUndefined();
    expect(mcp.value).toEqual([
      { name: 'gh', type: 'stdio', command: 'npx', args: ['--token', '(hidden)'], envKeys: ['GH_TOKEN'] },
      { name: 'web', type: 'http', url: 'https://mcp.example.com/x', envKeys: [] },
    ]);
    for (const d of defs) expect(d.help.length, d.key).toBeGreaterThan(10);
  });

  it('shows env overrides by variable name', async () => {
    const home = tempDir();
    dirs.push(home);
    const { loadConfig } = await import('../src/config.js');
    const { Foreman } = await import('../src/foreman.js');
    const { silentLogger } = await import('../src/context.js');
    const cfg = loadConfig(['--home', home, '--backend', 'claude', '--no-notify'], { AGENTCRAFT_PR_WATCH: 'off' });
    const fm = new Foreman({ config: cfg, logger: silentLogger });
    try {
      const out: Outbound[] = [];
      await fm.handle({ v: 1, id: 'x', type: 'config.get' }, (m) => out.push(m));
      const ack = out.find((m) => m.type === 'ack') as Ack;
      expect(byKey(settingsOf(ack), 'claude.prWatch')).toMatchObject({ source: 'env', overriddenBy: 'AGENTCRAFT_PR_WATCH', value: 'off' });
    } finally {
      await fm.close();
    }
  });
});

describe('config.set', () => {
  const original = '{\n    "repos": [],\n    "future": { "keep": [1, 2] },\n    "claude": {\n        "workerModel": "sonnet",\n        "unknownThing": true,\n        "leads": ["marlow", "ines", "bram", "cass"]\n    },\n    "zzz": 1\n}\n';

  it('validates every change first: one bad change refuses the lot', async () => {
    const h = setup(original);
    const ack = await call(h, {
      type: 'config.set',
      changes: [
        { key: 'claude.workerModel', value: 'opus' },
        { key: 'claude.maxConcurrent', value: 0 },
        { key: 'claude.effort', value: 'turbo' },
        { key: 'claude.workers', value: ['kit', 'nobody'] },
        { key: 'claude.leads', value: ['marlow', 'kit'] },
        { key: 'claude.context.mcpServers', value: {} },
        { key: 'claude.secret', value: 1 },
        { key: 'claude.prPollSeconds', value: '60' },
        { key: 'claude.agents.kit.model', value: 'not a model!' },
      ],
    });
    expect(ack.ok).toBe(false);
    for (const part of ['claude.maxConcurrent: must be at least 1', 'claude.effort: must be one of', 'no such agent "nobody"', 'claude.leads: no such agent "kit"', 'claude.context.mcpServers: must be a list of servers', 'claude.secret: not an editable setting', 'claude.prPollSeconds: must be a whole number', 'is not a model name']) {
      expect(ack.error).toContain(part);
    }
    expect(fs.readFileSync(h.file, 'utf8')).toBe(original);
    expect(fs.existsSync(`${h.file}.bak`)).toBe(false);
    expect(h.fm.config.claude.workerModel).toBe('sonnet');
  });

  it('writes atomically with a .bak, keeps unknown keys and order, applies live keys and reports restart ones', async () => {
    const h = setup(original);
    const ack = await call(h, {
      type: 'config.set',
      changes: [
        { key: 'claude.workerModel', value: 'opus' },
        { key: 'claude.agents.kit.effort', value: 'high' },
        { key: 'claude.agents.kit.title', value: 'Backend' },
        { key: 'claude.maxConcurrent', value: 5 },
        { key: 'claude.permissions.allow', value: ['Bash(codex exec:*)', ' ', 'Bash(codex exec:*)'] },
        { key: 'claude.leads', value: ['ines', 'marlow'] },
        { key: 'notify', value: true },
        { key: 'mergeStyle', value: 'squash' },
      ],
    });
    expect(ack.ok, ack.error).toBe(true);
    expect(ack.result).toEqual({
      applied: ['claude.workerModel', 'claude.agents.kit.effort', 'claude.agents.kit.title', 'claude.maxConcurrent', 'claude.permissions.allow', 'mergeStyle'],
      restartRequired: ['claude.leads'],
      overridden: [{ key: 'notify', by: '--no-notify' }],
    });
    expect(fs.readFileSync(`${h.file}.bak`, 'utf8')).toBe(original);
    const text = fs.readFileSync(h.file, 'utf8');
    expect(text).toContain('\n    "repos"'); // the file's own indentation
    const f = readFile(h.file);
    expect(Object.keys(f)).toEqual(['repos', 'future', 'claude', 'zzz', 'notify', 'mergeStyle']);
    expect(Object.keys(f.claude)).toEqual(['workerModel', 'unknownThing', 'leads', 'agents', 'maxConcurrent', 'permissions']);
    expect(f.future).toEqual({ keep: [1, 2] });
    expect(f.claude).toMatchObject({ workerModel: 'opus', unknownThing: true, leads: ['marlow', 'ines'], agents: { kit: { effort: 'high', title: 'Backend' } }, maxConcurrent: 5, permissions: { allow: ['Bash(codex exec:*)'] } });
    expect(f.notify).toBe(true);

    // live: the running config (the backend holds the same objects)
    const c = h.fm.config;
    expect(c.claude.workerModel).toBe('opus');
    expect(c.claude.designModel).toBe('opus'); // follows the worker model when unset
    expect(c.claude.agents.kit).toEqual({ effort: 'high', title: 'Backend' });
    expect(c.claude.maxConcurrent).toBe(5);
    expect(c.claude.permissions.allow).toEqual(['Bash(codex exec:*)']);
    expect(c.mergeStyle).toBe('squash');
    // restart-only and overridden: unchanged
    expect(c.claude.leads).toEqual(['marlow', 'ines', 'bram', 'cass']);
    expect(c.notify).toBe(false);
    expect(h.fm.agent('kit')!.title).toBe('Backend');
    expect(h.events.some((e) => e.type === 'agent.upsert' && e.agent.id === 'kit' && e.agent.title === 'Backend')).toBe(true);

    expect(h.fm.status.restartRequired).toEqual(['claude.leads']);
    const changed = h.events.filter((e) => e.type === 'config.changed');
    expect(changed).toEqual([{ type: 'config.changed', keys: ['claude.workerModel', 'claude.agents.kit.effort', 'claude.agents.kit.title', 'claude.maxConcurrent', 'claude.permissions.allow', 'claude.leads', 'notify', 'mergeStyle'], restartRequired: ['claude.leads'] }]);
    expect(h.events.some((e) => e.type === 'foreman.status' && e.status.restartRequired?.[0] === 'claude.leads')).toBe(true);

    // config.get shows the configured (not yet running) value
    expect(byKey(settingsOf(await call(h, { type: 'config.get' })), 'claude.leads').value).toEqual(['marlow', 'ines']);

    // setting it back: nothing waits for a restart any more
    const back = await call(h, { type: 'config.set', changes: [{ key: 'claude.leads', value: ['marlow', 'ines', 'bram', 'cass'] }] });
    expect(back.ok).toBe(true);
    expect(h.fm.status.restartRequired).toBeUndefined();
    expect(h.events.filter((e) => e.type === 'config.changed').at(-1)).toMatchObject({ restartRequired: [] });
  });

  it('live keys reach the Claude backend from its next turn (it holds the same config objects)', async () => {
    const { ClaudeBackend } = await import('../src/agents/claude/index.js');
    const h = setup({});
    const backend = new ClaudeBackend(h.fm, h.cfg.claude, { skipAuthCheck: true });
    expect(backend.modelFor('kit', 'worker')).toEqual({ model: 'sonnet', effort: 'medium' });
    const ack = await call(h, { type: 'config.set', changes: [{ key: 'claude.workerModel', value: 'opus' }, { key: 'claude.agents.kit.effort', value: 'max' }, { key: 'claude.agents.marlow.model', value: 'sonnet' }] });
    expect(ack.ok, ack.error).toBe(true);
    expect(backend.modelFor('kit', 'worker')).toEqual({ model: 'opus', effort: 'max' });
    expect(backend.modelFor('marlow', 'lead').model).toBe('sonnet');
  });

  it('null or "default" removes a key (and objects it leaves empty)', async () => {
    const h = setup({ claude: { agents: { kit: { model: 'opus' } }, maxConcurrentTurns: 4, maxBudgetUsdPerTurn: 3 } });
    const ack = await call(h, {
      type: 'config.set',
      changes: [
        { key: 'claude.agents.kit.model', value: 'default' },
        { key: 'claude.maxConcurrentTurns', value: 0 },
        { key: 'claude.maxBudgetUsdPerTurn', value: null },
      ],
    });
    expect(ack.ok, ack.error).toBe(true);
    expect(readFile(h.file)).toEqual({});
    expect(h.fm.config.claude.agents.kit).toBeUndefined();
    expect(h.fm.config.claude.maxConcurrentTurns).toBeUndefined();
    expect(h.fm.config.claude.maxBudgetUsdPerTurn).toBeUndefined();
  });

  it('writes to the spelling the file already uses, and turns a boolean section into an object', async () => {
    const h = setup({ 'merge-style': 'merge', claude: { context: { sessionHistory: true } } });
    const ack = await call(h, {
      type: 'config.set',
      changes: [
        { key: 'mergeStyle', value: 'squash' },
        { key: 'claude.context.sessionHistory.days', value: 30 },
      ],
    });
    expect(ack.ok, ack.error).toBe(true);
    expect(readFile(h.file)).toEqual({ 'merge-style': 'squash', claude: { context: { sessionHistory: { enabled: true, days: 30 } } } });
    expect(ack.result).toMatchObject({ applied: ['mergeStyle'], restartRequired: ['claude.context.sessionHistory.days'] });
  });

  it('creates config.json when there is none, and refuses to touch one that is not JSON', async () => {
    const h = setup();
    expect((await call(h, { type: 'config.set', changes: [{ key: 'claude.prWatch', value: 'off' }] })).ok).toBe(true);
    expect(readFile(h.file)).toEqual({ claude: { prWatch: 'off' } });
    expect(fs.existsSync(`${h.file}.bak`)).toBe(false);

    fs.writeFileSync(h.file, '{ "claude": ');
    const bad = await call(h, { type: 'config.set', changes: [{ key: 'claude.prWatch', value: 'on' }] });
    expect(bad.ok).toBe(false);
    expect(bad.error).toMatch(/is not valid JSON/);
    expect(fs.readFileSync(h.file, 'utf8')).toBe('{ "claude": ');
    expect((await call(h, { type: 'config.get' })).ok).toBe(false);
  });
});

describe('repository settings and repo.agents', () => {
  it('reads and writes repoSettings under the key the file uses, with agent files for roles', async () => {
    const repoPath = await demoRepo();
    dirs.push(path.dirname(repoPath));
    const agents = path.join(repoPath, '.claude', 'agents');
    fs.mkdirSync(agents, { recursive: true });
    fs.writeFileSync(path.join(agents, 'backend-dev.md'), '---\nname: Backend developer\ndescription: Builds the API\nmodel: sonnet\n---\nYou build APIs.');
    fs.writeFileSync(path.join(agents, 'broken.md'), 'no front matter');
    const spelled = `${repoPath}/`;
    const h = setup({ repoSettings: { [spelled]: { ci: 'npm test', env: { API_KEY: 'repo-secret' }, custom: 1 } } });
    const repo = await h.fm.repos.add(repoPath);

    const list = await call(h, { type: 'repo.agents', repoId: repo.id });
    expect(list.result).toEqual({ agents: [{ id: 'backend-dev', name: 'Backend developer', path: '.claude/agents/backend-dev.md', description: 'Builds the API', model: 'sonnet' }] });

    const got = await call(h, { type: 'config.get', repoId: repo.id });
    const defs = settingsOf(got);
    expect(JSON.stringify(got)).not.toContain('repo-secret');
    expect(new Set(defs.map((d) => d.group))).toEqual(new Set(['landing', 'worktrees', 'agents', 'review']));
    expect(byKey(defs, 'ci')).toMatchObject({ value: 'npm test', source: 'file', live: true });
    expect(byKey(defs, 'land')).toMatchObject({ value: 'merge', source: 'default', options: ['merge', 'pr'] });
    // contract S1 (docs/WAVE3.md): a secret map, names only
    expect(byKey(defs, 'env')).toMatchObject({ type: 'secretMap', live: true, value: { API_KEY: '(set)' } });
    expect(byKey(defs, 'env').readOnly).toBeUndefined();
    expect(byKey(defs, 'roles.kit')).toMatchObject({ value: '', options: ['backend-dev'] });

    const bad = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'roles.kit', value: 'nope' }, { key: 'protect', value: ['../x'] }, { key: 'env', value: {} }] });
    expect(bad.ok).toBe(false);
    expect(bad.error).toContain('no agent file "nope"');
    expect(bad.error).toContain('must be relative to the repository');

    const ok = await call(h, {
      type: 'config.set',
      repoId: repo.id,
      changes: [
        { key: 'roles.kit', value: 'Backend developer' },
        { key: 'land', value: 'pr' },
        { key: 'pr.draft', value: true },
        { key: 'ci', value: '' },
      ],
    });
    expect(ok.ok, ok.error).toBe(true);
    expect(ok.result).toEqual({ applied: ['roles.kit', 'land', 'pr.draft', 'ci'], restartRequired: [], overridden: [] });
    expect(readFile(h.file).repoSettings).toEqual({ [spelled]: { env: { API_KEY: 'repo-secret' }, custom: 1, roles: { kit: 'backend-dev' }, land: 'pr', pr: { draft: true } } });
    // live: the repository manager reads the same settings object
    expect(h.fm.repos.settingsFor(repo.id)).toMatchObject({ land: 'pr', roles: { kit: 'backend-dev' }, pr: { draft: true } });
    expect(h.fm.repos.settingsFor(repo.id).ci).toBeUndefined();
    expect(h.events.some((e) => e.type === 'repo.upsert' && e.repo.settings?.land === 'pr')).toBe(true);
    expect(h.events.filter((e) => e.type === 'config.changed').at(-1)).toMatchObject({ keys: [`repo:${repo.id}:roles.kit`, `repo:${repo.id}:land`, `repo:${repo.id}:pr.draft`, `repo:${repo.id}:ci`] });
  });

  it('uses the absolute path for a repository config.json does not mention yet', async () => {
    const repoPath = await demoRepo();
    dirs.push(path.dirname(repoPath));
    const h = setup({});
    const repo = await h.fm.repos.add(repoPath);
    expect((await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'setup', value: 'npm ci' }] })).ok).toBe(true);
    expect(readFile(h.file)).toEqual({ repoSettings: { [repo.path]: { setup: 'npm ci' } } });
    expect((await call(h, { type: 'repo.agents', repoId: 'nope' })).error).toBe('no repo "nope"');
  });
});
