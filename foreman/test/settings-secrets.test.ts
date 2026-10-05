// Secret settings through config.get / config.set (docs/WAVE3.md contracts S1 and S2):
//  - S1 secret maps: a repository's env shows names only and takes partial updates (null removes)
//  - S2 MCP servers: editable list (add / edit / remove), restart-required, env as a secret map
//  - no secret value ever appears in config.get, an ack, an error, config.changed, a log line or
//    any file the Foreman writes besides config.json (and its .bak)
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { Logger } from '../src/context.js';
import { Foreman } from '../src/foreman.js';
import { Notifier } from '../src/notifier.js';
import type { ClientMessage, Outbound, SettingDef } from '../src/protocol.js';
import { demoRepo, rmrf, tempDir, testConfig } from './helpers.js';

const dirs: string[] = [];
const foremen: Foreman[] = [];
afterEach(async () => {
  for (const f of foremen.splice(0)) await f.close();
  for (const d of dirs.splice(0)) rmrf(d);
});

interface H {
  fm: Foreman;
  home: string;
  file: string;
  /** everything sent: replies, acks and broadcasts */
  out: Outbound[];
  logs: string[];
}

function setup(file: unknown): H {
  const home = tempDir();
  dirs.push(home);
  const configFile = path.join(home, 'config.json');
  fs.writeFileSync(configFile, JSON.stringify(file, null, 2) + '\n');
  const logs: string[] = [];
  const logger: Logger = { info: (m) => logs.push(m), warn: (m) => logs.push(m), error: (m) => logs.push(m), debug: (m) => logs.push(m) };
  const fm = new Foreman({ config: testConfig(home, ['--backend', 'claude']), logger, notifier: new Notifier({ enabled: false, bell: false }) });
  foremen.push(fm);
  const out: Outbound[] = [];
  fm.subscribe((m) => out.push(m));
  return { fm, home, file: configFile, out, logs };
}

type Ack = Extract<Outbound, { type: 'ack' }>;
let nextId = 1;
async function call(h: H, msg: Record<string, unknown>): Promise<Ack> {
  const id = `s${nextId++}`;
  await h.fm.handle({ v: 1, id, ...msg } as unknown as ClientMessage, (m) => h.out.push(m));
  return h.out.find((m): m is Ack => m.type === 'ack' && m.re === id)!;
}

const def = (ack: Ack, key: string) => (ack.result as { settings: SettingDef[] }).settings.find((d) => d.key === key)!;
const readFile = (f: string) => JSON.parse(fs.readFileSync(f, 'utf8')) as Record<string, any>;

/** Every file under `dir` except config.json and its backup, as text. */
function otherFiles(dir: string): string {
  let text = '';
  for (const e of fs.readdirSync(dir, { withFileTypes: true, recursive: true })) {
    if (!e.isFile() || /^config\.json(\.bak)?$/.test(e.name)) continue;
    text += fs.readFileSync(path.join(e.parentPath, e.name), 'utf8');
  }
  return text;
}

/** No secret anywhere the Foreman sends, logs or keeps (config.json itself excepted). */
async function expectNoSecrets(h: H, secrets: string[]): Promise<void> {
  await h.fm.close();
  const everything = [JSON.stringify(h.out), h.logs.join('\n'), otherFiles(h.home)].join('\n');
  for (const s of secrets) expect(everything, s).not.toContain(s);
}

describe('S1 secret maps: a repository env', () => {
  it('shows names only, applies partial updates (null removes), live, and refuses git / token variables', async () => {
    const repoPath = await demoRepo();
    dirs.push(path.dirname(repoPath));
    const h = setup({ repoSettings: { [repoPath]: { env: { API_KEY: 'old-secret-1', KEEP: 'keep-secret-2' } } } });
    const repo = await h.fm.repos.add(repoPath);

    let got = await call(h, { type: 'config.get', repoId: repo.id });
    expect(def(got, 'env')).toMatchObject({ type: 'secretMap', value: { API_KEY: '(set)', KEEP: '(set)' }, default: {}, source: 'file' });

    const set = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: { API_KEY: 'new-secret-3', NODE_HOME: 'path-secret-4' } }] });
    expect(set.ok, set.error).toBe(true);
    expect(set.result).toEqual({ applied: ['env'], restartRequired: [], overridden: [] });
    expect(readFile(h.file).repoSettings[repoPath].env).toEqual({ API_KEY: 'new-secret-3', KEEP: 'keep-secret-2', NODE_HOME: 'path-secret-4' });
    // live: the next turn, setup and CI read it
    expect(h.fm.repos.envFor(repo.id)).toMatchObject({ API_KEY: 'new-secret-3', KEEP: 'keep-secret-2', NODE_HOME: 'path-secret-4' });
    expect(h.out.filter((m) => m.type === 'config.changed').at(-1)).toMatchObject({ keys: [`repo:${repo.id}:env`] });

    const removed = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: { API_KEY: null, KEEP: null } }] });
    expect(removed.ok, removed.error).toBe(true);
    expect(readFile(h.file).repoSettings[repoPath].env).toEqual({ NODE_HOME: 'path-secret-4' });
    got = await call(h, { type: 'config.get', repoId: repo.id });
    expect(def(got, 'env').value).toEqual({ NODE_HOME: '(set)' });

    // removing the last variable removes the key (and the sections it leaves empty)
    await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: { NODE_HOME: null } }] });
    expect(readFile(h.file).repoSettings).toBeUndefined();

    // refused (all or nothing), naming keys but never the values
    const bad = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: { GOOD: 'bad-secret-5', GIT_DIR: 'bad-secret-6', AGENTCRAFT_CLIENT_TOKEN: 'bad-secret-7', '1X': 'bad-secret-8', ['__proto__']: 'bad-secret-10', NUM: 5 } }] });
    expect(bad.ok).toBe(false);
    for (const part of ['GIT_DIR: git variables cannot be set', 'AGENTCRAFT_CLIENT_TOKEN: the client token never reaches agents', '"1X" is not a variable name', '"__proto__" is not a variable name', 'NUM: must be text or null']) expect(bad.error).toContain(part);
    expect(readFile(h.file).repoSettings).toBeUndefined();
    const notObject = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: ['bad-secret-9'] }] });
    expect(notObject.error).toContain('env: must be an object');

    await expectNoSecrets(h, ['old-secret-1', 'keep-secret-2', 'new-secret-3', 'path-secret-4', 'bad-secret-5', 'bad-secret-6', 'bad-secret-7', 'bad-secret-8', 'bad-secret-9', 'bad-secret-10']);
  });
});

describe('S2 MCP servers', () => {
  const start = {
    claude: {
      context: {
        mcpServers: {
          gh: { command: 'npx', args: ['-y', 'gh-mcp', '--token', 'arg-secret-1', '--api-key=arg-secret-2', 'ghp_abcdefghijklmnopqrstuvwxyz0123456789'], env: { GH_TOKEN: 'env-secret-3' } },
          web: { type: 'http', url: 'https://user:url-secret-4@mcp.example.com/mcp?key=url-secret-5', headers: { Authorization: 'hdr-secret-6' } },
        },
      },
    },
  };
  const all = ['arg-secret-1', 'arg-secret-2', 'ghp_abcdefghijklmnopqrstuvwxyz0123456789', 'env-secret-3', 'url-secret-4', 'url-secret-5', 'hdr-secret-6'];

  it('lists servers without secrets; an entry sent back as shown keeps the stored originals', async () => {
    const h = setup(start);
    const got = await call(h, { type: 'config.get' });
    const shown = def(got, 'claude.context.mcpServers').value as Array<Record<string, unknown>>;
    expect(shown).toEqual([
      { name: 'gh', type: 'stdio', command: 'npx', args: ['-y', 'gh-mcp', '--token', '(hidden)', '--api-key=(hidden)', '(hidden)'], envKeys: ['GH_TOKEN'] },
      { name: 'web', type: 'http', url: 'https://mcp.example.com/mcp', envKeys: [] },
    ]);
    // the hub edits gh (one more argument, one more variable) and saves web unchanged
    const gh = { ...shown[0]!, args: [...(shown[0]!.args as string[]), '--verbose'], env: { LOG_LEVEL: 'debug' } };
    const ack = await call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [gh, shown[1]] }] });
    expect(ack.ok, ack.error).toBe(true);
    expect(ack.result).toEqual({ applied: [], restartRequired: ['claude.context.mcpServers'], overridden: [] });
    expect(readFile(h.file).claude.context.mcpServers).toEqual({
      gh: { command: 'npx', args: ['-y', 'gh-mcp', '--token', 'arg-secret-1', '--api-key=arg-secret-2', 'ghp_abcdefghijklmnopqrstuvwxyz0123456789', '--verbose'], env: { GH_TOKEN: 'env-secret-3', LOG_LEVEL: 'debug' } },
      web: { type: 'http', url: 'https://user:url-secret-4@mcp.example.com/mcp?key=url-secret-5', headers: { Authorization: 'hdr-secret-6' } },
    });
    // restart-required: the running Foreman keeps the servers it started with
    expect(h.fm.config.claude.context.mcpServers.gh).toMatchObject({ args: ['-y', 'gh-mcp', '--token', 'arg-secret-1', '--api-key=arg-secret-2', 'ghp_abcdefghijklmnopqrstuvwxyz0123456789'] });
    expect(h.fm.status.restartRequired).toEqual(['claude.context.mcpServers']);
    await expectNoSecrets(h, all);
  });

  it('adds, edits and removes servers; an env value change alone still needs a restart', async () => {
    const h = setup(start);
    const add = await call(h, {
      type: 'config.set',
      changes: [
        {
          key: 'claude.context.mcpServers',
          value: [
            { name: 'notes', type: 'stdio', command: 'node', args: ['notes-mcp.js'], env: { NOTES_TOKEN: 'new-secret-7' } },
            { name: 'docs', type: 'sse', url: 'https://docs.example.com/sse' },
            { name: 'web', remove: true },
          ],
        },
      ],
    });
    expect(add.ok, add.error).toBe(true);
    const servers = readFile(h.file).claude.context.mcpServers;
    expect(Object.keys(servers)).toEqual(['gh', 'notes', 'docs']);
    expect(servers.notes).toEqual({ command: 'node', args: ['notes-mcp.js'], env: { NOTES_TOKEN: 'new-secret-7' } });
    expect(servers.docs).toEqual({ type: 'sse', url: 'https://docs.example.com/sse' });
    expect(servers.gh.env).toEqual({ GH_TOKEN: 'env-secret-3' });

    // back to how it started except one env value: the view is the same, the restart flag is not
    fs.writeFileSync(h.file, JSON.stringify(start));
    const sameView = await call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [{ name: 'gh', type: 'stdio', command: 'npx', args: ['-y', 'gh-mcp', '--token', '(hidden)', '--api-key=(hidden)', '(hidden)'], env: { GH_TOKEN: 'rotated-secret-8' } }] }] });
    expect(sameView.ok, sameView.error).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.gh.env).toEqual({ GH_TOKEN: 'rotated-secret-8' });
    expect(h.fm.status.restartRequired).toEqual(['claude.context.mcpServers']);
    expect(h.out.filter((m) => m.type === 'config.changed').at(-1)).toEqual({ type: 'config.changed', keys: ['claude.context.mcpServers'], restartRequired: ['claude.context.mcpServers'] });

    // removing every server removes the key; null does too
    await call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [{ name: 'gh', remove: true }, { name: 'web', remove: true }] }] });
    expect(readFile(h.file).claude).toBeUndefined();
    await expectNoSecrets(h, [...all, 'new-secret-7', 'rotated-secret-8']);
  });

  it('validates names, types, commands and URLs (no credentials, no query) without echoing values', async () => {
    const h = setup(start);
    const before = fs.readFileSync(h.file, 'utf8');
    const bad = await call(h, {
      type: 'config.set',
      changes: [
        {
          key: 'claude.context.mcpServers',
          value: [
            { name: 'agentcraft', type: 'stdio', command: 'x' },
            { name: '__proto__', type: 'stdio', command: 'x' },
            { name: 'bad name', type: 'stdio', command: 'x' },
            { name: 'notype', command: 'x' },
            { name: 'nocmd', type: 'stdio' },
            { name: 'query', type: 'http', url: 'https://mcp.example.com/?token=bad-secret-1' },
            { name: 'creds', type: 'http', url: 'https://me:bad-secret-2@mcp.example.com/' },
            { name: 'ftp', type: 'sse', url: 'ftp://mcp.example.com/' },
            { name: 'mixed', type: 'http', url: 'https://mcp.example.com/', command: 'x' },
            { name: 'httpenv', type: 'http', url: 'https://mcp.example.com/', env: { A: 'bad-secret-3' } },
            { name: 'envname', type: 'stdio', command: 'x', env: { 'A-B': 'bad-secret-4', AGENTCRAFT_CLIENT_TOKEN: 'bad-secret-5' } },
            { name: 'ghost', remove: true },
            { name: 'twice', type: 'stdio', command: 'x' },
            { name: 'twice', type: 'stdio', command: 'y' },
            { name: 'extra', type: 'stdio', command: 'x', headers: { A: 'bad-secret-6' } },
          ],
        },
      ],
    });
    expect(bad.ok).toBe(false);
    for (const part of [
      "agentcraft: is the team tools server's name",
      '__proto__: name must be',
      'entry 3: name must be',
      'notype: type must be stdio, http or sse',
      'nocmd: a stdio server needs a command',
      'query: url must not contain a query',
      'creds: url must not contain credentials',
      'ftp: url must be an http(s) URL',
      'mixed: an http server has a url, not a command',
      'httpenv: env is for stdio servers',
      'envname env: "A-B" is not a variable name',
      'AGENTCRAFT_CLIENT_TOKEN: the client token never reaches agents',
      'twice: listed twice',
      'extra: unknown field headers',
    ]) {
      expect(bad.error).toContain(part);
    }
    // removal of a server that is not there: refused on its own too
    const ghost = await call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [{ name: 'ghost', remove: true }] }] });
    expect(ghost.error).toContain('ghost: no such MCP server');
    // a hidden argument that moved (an argument inserted before it) is refused, never written as "(hidden)"
    const moved = await call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [{ name: 'gh', type: 'stdio', command: 'npx', args: ['--first', '-y', 'gh-mcp', '--token', '(hidden)', '--api-key=(hidden)', '(hidden)'] }] }] });
    expect(moved.ok).toBe(false);
    expect(moved.error).toContain('gh: arguments 5, 6, 7 are hidden and moved; enter them again');
    expect(fs.readFileSync(h.file, 'utf8')).toBe(before);
    expect(h.fm.status.restartRequired).toBeUndefined();
    await expectNoSecrets(h, [...all, 'bad-secret-1', 'bad-secret-2', 'bad-secret-3', 'bad-secret-4', 'bad-secret-5', 'bad-secret-6']);
  });
  it('hides header, Bearer, --key / --pat and name=URL arguments; shows only what clearly holds no secret', async () => {
    const remote = {
      command: 'npx',
      args: [
        'mcp-remote',
        'https://mcp.example.com/sse',
        '--header',
        'Authorization: Bearer hdr-arg-secret-11',
        '-H',
        'X-API-Key: hdr-arg-secret-12',
        '--key',
        'key-arg-secret-13',
        '--pat',
        'pat-arg-secret-14',
        'Authorization: Bearer hdr-arg-secret-15',
        '--header=X-Token: hdr-arg-secret-16',
        '--db=postgres://u:url-arg-secret-17@db.example.com/x',
        'token tok-arg-secret-18',
        '--config={"key":"json-arg-secret-19"}',
        '-u',
        'sam:pw-arg-secret-20',
        '@scope/server-notes@1.2.3',
        '/srv/pocket-notes',
        '--port',
        '8080',
      ],
    };
    const h = setup({ claude: { context: { mcpServers: { remote } } } });
    const got = await call(h, { type: 'config.get' });
    const shown = def(got, 'claude.context.mcpServers').value as Array<Record<string, unknown>>;
    expect(shown[0]!.args).toEqual([
      'mcp-remote',
      'https://mcp.example.com/sse',
      '--header',
      '(hidden)',
      '-H',
      '(hidden)',
      '--key',
      '(hidden)',
      '--pat',
      '(hidden)',
      '(hidden)',
      '--header=(hidden)',
      '--db=postgres://db.example.com/x',
      '(hidden)',
      '--config=(hidden)',
      '-u',
      '(hidden)',
      '@scope/server-notes@1.2.3',
      '/srv/pocket-notes',
      '--port',
      '8080',
    ]);
    // sent back as shown: every original kept
    const same = await call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [shown[0]] }] });
    expect(same.ok, same.error).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.remote).toEqual(remote);
    // a new server with the same kinds of arguments: the ack and the broadcast carry none of them
    const add = await call(h, {
      type: 'config.set',
      changes: [{ key: 'claude.context.mcpServers', value: [{ name: 'other', type: 'stdio', command: 'npx', args: ['mcp-remote', '--header', 'Authorization: Bearer new-arg-secret-21', '--key', 'new-arg-secret-22', '--pat', 'new-arg-secret-23', 'X-API-Key: new-arg-secret-24'] }] }],
    });
    expect(add.ok, add.error).toBe(true);
    const again = await call(h, { type: 'config.get' });
    expect((def(again, 'claude.context.mcpServers').value as Array<Record<string, unknown>>)[1]!.args).toEqual(['mcp-remote', '--header', '(hidden)', '--key', '(hidden)', '--pat', '(hidden)', '(hidden)']);
    await expectNoSecrets(h, [11, 12, 13, 14, 15, 16, 17, 18, 19, 20].map((n) => `arg-secret-${n}`).concat(['new-arg-secret-21', 'new-arg-secret-22', 'new-arg-secret-23', 'new-arg-secret-24']));
  });

  it('refuses a shortened URL argument that moved instead of writing it without its credentials', async () => {
    const pg = { command: 'npx', args: ['-y', 'postgres://user:pg-secret-1@db.example.com/x'] };
    const h = setup({ claude: { context: { mcpServers: { pg } } } });
    const before = fs.readFileSync(h.file, 'utf8');
    const got = await call(h, { type: 'config.get' });
    expect((def(got, 'claude.context.mcpServers').value as Array<Record<string, unknown>>)[0]!.args).toEqual(['-y', 'postgres://db.example.com/x']);
    const moved = await call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [{ name: 'pg', type: 'stdio', command: 'npx', args: ['postgres://db.example.com/x'] }] }] });
    expect(moved.ok).toBe(false);
    expect(moved.error).toContain('pg: argument 1 is hidden and moved; enter it again');
    expect(fs.readFileSync(h.file, 'utf8')).toBe(before);
    // in the same place: kept with its password
    const kept = await call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [{ name: 'pg', type: 'stdio', command: 'npx', args: ['-y', 'postgres://db.example.com/x', '--ro'] }] }] });
    expect(kept.ok, kept.error).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.pg.args).toEqual(['-y', 'postgres://user:pg-secret-1@db.example.com/x', '--ro']);
    await expectNoSecrets(h, ['pg-secret-1']);
  });
});
