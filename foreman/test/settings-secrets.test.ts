// Secret settings through config.get / config.set (docs/WAVE3.md contracts S1 and S2):
//  - S1 secret maps: a repository's env shows names only (a list) and takes partial updates (null
//    removes); a placeholder or a control character is refused, never stored
//  - S2 MCP servers: the view never holds an argument or a URL path; args and the URL are write-only
//    (left out: kept exactly; sent: replaced exactly)
//  - errors name places (server #2, env key #1, change #3), never what the caller sent
//  - no secret value ever appears in config.get, an ack, an error, config.changed, a log line or
//    any file the Foreman writes besides config.json (and its .bak)
// The regressions of the wave-3 security review are marked "finding N".
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
const setMcp = (h: H, value: unknown) => call(h, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value }] });

const def = (ack: Ack, key: string) => (ack.result as { settings: SettingDef[] }).settings.find((d) => d.key === key)!;
const servers = async (h: H) => def(await call(h, { type: 'config.get' }), 'claude.context.mcpServers').value as Array<Record<string, unknown>>;
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
  it('shows names only (a list), applies partial updates (null removes), live, and refuses git / token variables by place', async () => {
    const repoPath = await demoRepo();
    dirs.push(path.dirname(repoPath));
    const h = setup({ repoSettings: { [repoPath]: { env: { API_KEY: 'old-secret-1', KEEP: 'keep-secret-2' } } } });
    const repo = await h.fm.repos.add(repoPath);

    let got = await call(h, { type: 'config.get', repoId: repo.id });
    expect(def(got, 'env')).toMatchObject({ type: 'secretMap', value: ['API_KEY', 'KEEP'], default: {}, source: 'file' });

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
    expect(def(got, 'env').value).toEqual(['NODE_HOME']);

    // removing the last variable removes the key (and the sections it leaves empty)
    await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: { NODE_HOME: null } }] });
    expect(readFile(h.file).repoSettings).toBeUndefined();

    // refused (all or nothing), naming places, never names or values
    const bad = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: { GOOD: 'bad-secret-5', GIT_DIR: 'bad-secret-6', AGENTCRAFT_CLIENT_TOKEN: 'bad-secret-7', '1X': 'bad-secret-8', ['__proto__']: 'bad-secret-10', NUM: 5 } }] });
    expect(bad.ok).toBe(false);
    for (const part of ['env key #2: git variables cannot be set', 'env key #3: the client token never reaches agents', 'env key #4 is not a valid variable name', 'env key #5 is not a valid variable name', 'env key #6: the value must be text or null']) expect(bad.error).toContain(part);
    for (const sent of ['GIT_DIR', '1X', '__proto__', 'NUM', 'AGENTCRAFT_CLIENT_TOKEN']) expect(bad.error).not.toContain(sent);
    expect(readFile(h.file).repoSettings).toBeUndefined();
    const notObject = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: ['bad-secret-9'] }] });
    expect(notObject.error).toContain('env: must be an object');

    await expectNoSecrets(h, ['old-secret-1', 'keep-secret-2', 'new-secret-3', 'path-secret-4', 'bad-secret-5', 'bad-secret-6', 'bad-secret-7', 'bad-secret-8', 'bad-secret-9', 'bad-secret-10']);
  });

  it('finding 5: echoing the view back never replaces a credential with a placeholder', async () => {
    const repoPath = await demoRepo();
    dirs.push(path.dirname(repoPath));
    const h = setup({ repoSettings: { [repoPath]: { env: { API_KEY: 'real-credential-1' } } } });
    const repo = await h.fm.repos.add(repoPath);
    const before = fs.readFileSync(h.file, 'utf8');
    // the old view's shape, the new view itself, and the other placeholders
    for (const value of [{ API_KEY: '(set)' }, ['API_KEY'], { API_KEY: true }, { API_KEY: '(hidden)' }, { API_KEY: ' (Set) ' }, { API_KEY: '(staged)' }, { API_KEY: '[redacted]' }]) {
      const ack = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value }] });
      expect(ack.ok, JSON.stringify(value)).toBe(false);
    }
    expect(fs.readFileSync(h.file, 'utf8')).toBe(before);
    expect(h.fm.repos.envFor(repo.id)).toMatchObject({ API_KEY: 'real-credential-1' });
    await expectNoSecrets(h, ['real-credential-1']);
  });

  it('finding 3: a value with NUL or control characters is refused; one in config.json never reaches a spawn', async () => {
    const repoPath = await demoRepo();
    dirs.push(path.dirname(repoPath));
    const h = setup({ repoSettings: { [repoPath]: { env: { NULLY: 'nul-secret-1\u0000x', FINE: 'line one\nline two' } } } });
    const repo = await h.fm.repos.add(repoPath);
    // loaded without the NUL value (node's spawn would quote it in its exception)
    expect(h.fm.repos.envFor(repo.id).NULLY).toBeUndefined();
    expect(h.fm.repos.envFor(repo.id).FINE).toBe('line one\nline two');
    for (const v of ['nul-secret-2\u0000', 'bell-secret-3\u0007', 'esc-secret-4\u001b[0m']) {
      const ack = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: { X: v } }] });
      expect(ack.ok).toBe(false);
      expect(ack.error).toContain('env key #1: the value must not contain control characters');
    }
    await expectNoSecrets(h, ['nul-secret-1', 'nul-secret-2', 'bell-secret-3', 'esc-secret-4']);
  });

  it('finding 6: a secret-bearing malformed key, an unknown setting key and an unknown MCP field are never echoed', async () => {
    const repoPath = await demoRepo();
    dirs.push(path.dirname(repoPath));
    const h = setup({});
    const repo = await h.fm.repos.add(repoPath);
    const env = await call(h, { type: 'config.set', repoId: repo.id, changes: [{ key: 'env', value: { 'API_KEY=hunter2-key-1': null } }] });
    expect(env.ok).toBe(false);
    expect(env.error).toBe('env: env key #1 is not a valid variable name');
    const key = await call(h, { type: 'config.set', changes: [{ key: 'claude.token=hunter2-key-2', value: 1 }, { key: 'repoSettings.__proto__.hunter2-key-3', value: 1 }] });
    expect(key.error).toBe('change #1: not an editable setting; change #2: not an editable setting');
    const field = await setMcp(h, [{ name: 'x', type: 'stdio', command: 'node', 'hunter2-key-4': 'hunter2-value-5' }, { name: 'hunter2-name-6', type: 'telnet' }]);
    expect(field.ok).toBe(false);
    expect(field.error).toContain('server #1: 1 unknown field');
    expect(field.error).toContain('server #2: type must be stdio, http or sse');
    await expectNoSecrets(h, ['hunter2']);
  });
});

describe('S2 MCP servers', () => {
  const start = {
    claude: {
      context: {
        mcpServers: {
          gh: { command: 'npx', args: ['-y', 'gh-mcp', '--token', 'arg-secret-1', '--api-key=arg-secret-2', 'ghp_abcdefghijklmnopqrstuvwxyz0123456789'], env: { GH_TOKEN: 'env-secret-3' } },
          web: { type: 'http', url: 'https://mcp.example.com/mcp/url-secret-4?key=url-secret-5', headers: { Authorization: 'Bearer hdr-secret-6' } },
        },
      },
    },
  };
  const all = ['arg-secret-1', 'arg-secret-2', 'ghp_abcdefghijklmnopqrstuvwxyz0123456789', 'env-secret-3', 'url-secret-4', 'url-secret-5', 'hdr-secret-6'];

  it('shows the executable, the number of arguments and scheme://host only; an entry sent back as shown keeps everything', async () => {
    const h = setup(start);
    const shown = await servers(h);
    expect(shown).toEqual([
      { name: 'gh', type: 'stdio', command: 'npx', argCount: 6, envKeys: ['GH_TOKEN'] },
      { name: 'web', type: 'http', url: 'https://mcp.example.com', urlHasPath: true, headerKeys: ['Authorization'], envKeys: [] },
    ]);
    // the hub sends gh back as shown plus one more variable: args, command and env kept exactly
    const ack = await setMcp(h, [{ ...shown[0]!, env: { LOG_LEVEL: 'debug' } }]);
    expect(ack.ok, ack.error).toBe(true);
    expect(ack.result).toEqual({ applied: [], restartRequired: ['claude.context.mcpServers'], overridden: [] });
    expect(readFile(h.file).claude.context.mcpServers).toEqual({
      gh: { ...start.claude.context.mcpServers.gh, env: { GH_TOKEN: 'env-secret-3', LOG_LEVEL: 'debug' } },
      web: start.claude.context.mcpServers.web,
    });
    // web sent back without its url: kept exactly (headers too)
    const web = await setMcp(h, [{ name: 'web', type: 'http' }]);
    expect(web.ok, web.error).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.web).toEqual(start.claude.context.mcpServers.web);
    // restart-required: the running Foreman keeps the servers it started with
    expect(h.fm.config.claude.context.mcpServers.gh).toMatchObject({ args: start.claude.context.mcpServers.gh.args });
    expect(h.fm.status.restartRequired).toEqual(['claude.context.mcpServers']);
    await expectNoSecrets(h, all);
  });

  it('finding 1: no argument value is ever shown, whatever its shape', async () => {
    const shapes = [
      ['--passphrase', 'hunter2-pass-1'],
      ['-p', 'hunter2-pass-2'],
      ['-phunter2-pass-3'],
      ['PIN=123456'],
      ['dGhpcyBpcy9hIHNlY3JldA'], // unpadded base64 with '/'
      ['--password', '--hunter2-pass-4'],
      ['--header', 'X-Api: hunter2-pass-5', '--header', 'X-Other: hunter2-pass-6'],
      ['plainword7'],
    ];
    const h = setup({ claude: { context: { mcpServers: Object.fromEntries(shapes.map((args, i) => [`s${i}`, { command: 'npx', args }])) } } });
    const shown = await servers(h);
    expect(shown.map((s) => s.argCount)).toEqual(shapes.map((a) => a.length));
    expect(shown.every((s) => s.args === undefined)).toBe(true);
    const text = JSON.stringify(shown);
    for (const a of shapes.flat()) expect(text).not.toContain(a);
    await expectNoSecrets(h, ['hunter2', '123456', 'dGhpcyBpcy9hIHNlY3JldA', 'plainword7']);
  });

  it('finding 2: a credential in the URL path or query is never shown', async () => {
    const h = setup({ claude: { context: { mcpServers: { gh: { type: 'http', url: 'https://mcp.example:8443/mcp/ghp_abcdefghijklmnopqrstuvwxyz0123456789' }, plain: { type: 'sse', url: 'https://plain.example/' }, broken: { type: 'http', url: 'not a url ghp_brokenbrokenbroken' } } } } });
    expect(await servers(h)).toEqual([
      { name: 'gh', type: 'http', url: 'https://mcp.example:8443', urlHasPath: true, envKeys: [] },
      { name: 'plain', type: 'sse', url: 'https://plain.example', urlHasPath: false, envKeys: [] },
      { name: 'broken', type: 'http', urlHasPath: true, envKeys: [] },
    ]);
    // the shown origin sent back for a URL with a path: refused, not "restored", not written
    const before = fs.readFileSync(h.file, 'utf8');
    for (const url of ['https://mcp.example:8443', 'https://mcp.example:8443/']) {
      const echo = await setMcp(h, [{ name: 'gh', type: 'http', url }]);
      expect(echo.ok).toBe(false);
      expect(echo.error).toContain('server #1: url is what config.get shows of the stored one');
    }
    expect(fs.readFileSync(h.file, 'utf8')).toBe(before);
    // a complete new URL replaces it exactly (normalized)
    const replaced = await setMcp(h, [{ name: 'gh', type: 'http', url: 'HTTPS://MCP.Example:8443/v2/ghp_newnewnewnewnewnew?x=1' }]);
    expect(replaced.ok, replaced.error).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.gh.url).toBe('https://mcp.example:8443/v2/ghp_newnewnewnewnewnew?x=1');
    // the origin of a URL without a path is no view of anything: accepted
    expect((await setMcp(h, [{ name: 'plain', type: 'sse', url: 'https://plain.example' }])).ok).toBe(true);
    await expectNoSecrets(h, ['ghp_abcdefghijklmnopqrstuvwxyz0123456789', 'ghp_newnewnewnewnewnew', 'ghp_brokenbrokenbroken']);
  });

  it('finding 4: args are replaced exactly (no placeholder, no index restoration), so deleting the first of two pairs keeps the right secret', async () => {
    const both = ['mcp-remote', '--header', 'X-One: first-secret-1', '--header', 'X-Two: second-secret-2'];
    const h = setup({ claude: { context: { mcpServers: { remote: { command: 'npx', args: both } } } } });
    const before = fs.readFileSync(h.file, 'utf8');
    // what the old hub sent (the first pair deleted, the masked second pair "as shown"): refused, nothing written
    const old = await setMcp(h, [{ name: 'remote', type: 'stdio', command: 'npx', args: ['mcp-remote', '--header', '(hidden)'] }]);
    expect(old.ok).toBe(false);
    expect(old.error).toContain('server #1: argument #3 is a placeholder');
    const eq = await setMcp(h, [{ name: 'remote', type: 'stdio', args: ['--key=(hidden)'] }]);
    expect(eq.ok).toBe(false);
    expect(fs.readFileSync(h.file, 'utf8')).toBe(before);
    // the complete new list: stored exactly as sent
    const ok = await setMcp(h, [{ name: 'remote', type: 'stdio', args: ['mcp-remote', '--header', 'X-Two: second-secret-2'] }]);
    expect(ok.ok, ok.error).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.remote).toEqual({ command: 'npx', args: ['mcp-remote', '--header', 'X-Two: second-secret-2'] });
    // an empty list clears them
    expect((await setMcp(h, [{ name: 'remote', type: 'stdio', args: [] }])).ok).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.remote).toEqual({ command: 'npx' });
    await expectNoSecrets(h, ['first-secret-1', 'second-secret-2']);
  });

  it('a command line is shown as its executable; that view sent back is refused, left out it is kept', async () => {
    const h = setup({ claude: { context: { mcpServers: { cli: { command: 'node /srv/mcp.js --token cmd-secret-1', args: ['--x'] } } } } });
    expect(await servers(h)).toEqual([{ name: 'cli', type: 'stdio', command: 'node', argCount: 1, envKeys: [] }]);
    const echo = await setMcp(h, [{ name: 'cli', type: 'stdio', command: 'node', argCount: 1, envKeys: [] }]);
    expect(echo.ok).toBe(false);
    expect(echo.error).toContain('server #1: command is what config.get shows of the stored one');
    const kept = await setMcp(h, [{ name: 'cli', type: 'stdio', env: { A: 'env-secret-2' } }]);
    expect(kept.ok, kept.error).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.cli).toEqual({ command: 'node /srv/mcp.js --token cmd-secret-1', args: ['--x'], env: { A: 'env-secret-2' } });
    await expectNoSecrets(h, ['cmd-secret-1', '/srv/mcp.js', 'env-secret-2']);
  });

  it('adds, edits, retypes and removes servers; an env value change alone still needs a restart', async () => {
    const h = setup(start);
    const add = await setMcp(h, [
      { name: 'notes', type: 'stdio', command: 'node', args: ['notes-mcp.js'], env: { NOTES_TOKEN: 'new-secret-7' } },
      { name: 'docs', type: 'sse', url: 'https://docs.example.com/sse' },
      { name: 'web', remove: true },
    ]);
    expect(add.ok, add.error).toBe(true);
    let file = readFile(h.file).claude.context.mcpServers;
    expect(Object.keys(file)).toEqual(['gh', 'notes', 'docs']);
    expect(file.notes).toEqual({ command: 'node', args: ['notes-mcp.js'], env: { NOTES_TOKEN: 'new-secret-7' } });
    expect(file.docs).toEqual({ type: 'sse', url: 'https://docs.example.com/sse' });

    // a new server needs its command / url; a retyped one too (and loses the other kind's fields)
    const missing = await setMcp(h, [{ name: 'fresh', type: 'stdio' }, { name: 'gh', type: 'http' }]);
    expect(missing.error).toBe('claude.context.mcpServers: server #1: a stdio server needs a command; server #2: an http server needs a url');
    const retyped = await setMcp(h, [{ name: 'gh', type: 'http', url: 'https://gh.example/mcp' }, { name: 'docs', type: 'stdio', command: 'docs-mcp' }]);
    expect(retyped.ok, retyped.error).toBe(true);
    file = readFile(h.file).claude.context.mcpServers;
    expect(file.gh).toEqual({ type: 'http', url: 'https://gh.example/mcp' });
    expect(file.docs).toEqual({ command: 'docs-mcp' });

    // back to how it started except one env value: the view is the same, the restart flag is not
    fs.writeFileSync(h.file, JSON.stringify(start));
    const sameView = await setMcp(h, [{ name: 'gh', type: 'stdio', env: { GH_TOKEN: 'rotated-secret-8' } }]);
    expect(sameView.ok, sameView.error).toBe(true);
    expect(readFile(h.file).claude.context.mcpServers.gh).toEqual({ ...start.claude.context.mcpServers.gh, env: { GH_TOKEN: 'rotated-secret-8' } });
    expect(h.fm.status.restartRequired).toEqual(['claude.context.mcpServers']);
    expect(h.out.filter((m) => m.type === 'config.changed').at(-1)).toEqual({ type: 'config.changed', keys: ['claude.context.mcpServers'], restartRequired: ['claude.context.mcpServers'] });

    // removing every server removes the key; null does too
    await setMcp(h, [{ name: 'gh', remove: true }, { name: 'web', remove: true }]);
    expect(readFile(h.file).claude).toBeUndefined();
    await expectNoSecrets(h, [...all, 'new-secret-7', 'rotated-secret-8']);
  });

  it('validates names, types, commands and URLs by place, without echoing names or values (finding 7 included)', async () => {
    const h = setup(start);
    const before = fs.readFileSync(h.file, 'utf8');
    const bad = await setMcp(h, [
      { name: 'agentcraft', type: 'stdio', command: 'x' },
      { name: '__proto__', type: 'stdio', command: 'x' },
      { name: 'bad name', type: 'stdio', command: 'x' },
      { name: 'notype', command: 'x' },
      { name: 'nocmd', type: 'stdio', command: 'two\nlines' },
      { name: 'creds', type: 'http', url: 'https://me:bad-secret-2@mcp.example.com/' },
      { name: 'ftp', type: 'sse', url: 'ftp://mcp.example.com/' },
      { name: 'mixed', type: 'http', url: 'https://mcp.example.com/', command: 'x' },
      { name: 'httpenv', type: 'http', url: 'https://mcp.example.com/', env: { A: 'bad-secret-3' } },
      { name: 'envname', type: 'stdio', command: 'x', env: { 'A-B': 'bad-secret-4', AGENTCRAFT_CLIENT_TOKEN: 'bad-secret-5' } },
      { name: 'twice', type: 'stdio', command: 'x' },
      { name: 'twice', type: 'stdio', command: 'y' },
      { name: 'extra', type: 'stdio', command: 'x', headers: { A: 'bad-secret-6' } },
      { name: 'keys', type: 'stdio', command: 'x', envKeys: { A: 'bad-secret-7' } },
      { name: 'newline', type: 'http', url: 'https://mcp.example.com/a\nb-bad-secret-8' },
      { name: 'nul', type: 'http', url: 'https://mcp.example.com/a\u0000bad-secret-9' },
      { name: 'space', type: 'http', url: 'https://mcp.example.com/a bad-secret-10' },
      { name: 'frag', type: 'http', url: 'https://mcp.example.com/#bad-secret-11' },
      { name: 'count', type: 'stdio', command: 'x', argCount: 'bad-secret-12' },
      { name: 'argnul', type: 'stdio', command: 'x', args: ['bad-secret-13\u0000'] },
    ]);
    expect(bad.ok).toBe(false);
    for (const part of [
      "server #1: agentcraft is the team tools server's name",
      'server #2: name must be',
      'server #3: name must be',
      'server #4: type must be stdio, http or sse',
      'server #5: command must be one line of text',
      'server #6: url must not contain credentials',
      'server #7: url must be an http(s) URL',
      'server #8: an http server has a url, not a command',
      'server #9: env is for stdio servers',
      'server #10 env: env key #1 is not a valid variable name, env key #2: the client token never reaches agents',
      'server #12: the same server is listed twice',
      'server #13: 1 unknown field',
      'server #14: envKeys and headerKeys are lists of names',
      'server #15: url must not contain spaces or control characters',
      'server #16: url must not contain spaces or control characters',
      'server #17: url must not contain spaces or control characters',
      'server #18: url must not contain a fragment',
      'server #19: argCount is a number',
      'server #20: args must be a list of text',
    ]) {
      expect(bad.error).toContain(part);
    }
    for (const name of ['nocmd', 'creds', 'notype', 'envname', 'A-B', 'mixed', 'newline']) expect(bad.error).not.toContain(name);
    // removal of a server that is not there: refused on its own too
    const ghost = await setMcp(h, [{ name: 'ghost-secret-1', remove: true }]);
    expect(ghost.error).toBe('claude.context.mcpServers: server #1: no such MCP server');
    expect(fs.readFileSync(h.file, 'utf8')).toBe(before);
    expect(h.fm.status.restartRequired).toBeUndefined();
    await expectNoSecrets(h, [...all, 'ghost-secret-1', ...Array.from({ length: 12 }, (_, i) => `bad-secret-${i + 2}`)]);
  });
});
