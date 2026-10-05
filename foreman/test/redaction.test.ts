// The central redactor (redact.ts, docs/WAVE3.md S1/S2): every secret value the Foreman knows is cut
// out of agent logs, the feed, acks, errors, notifications and console logs, also URL-encoded,
// JSON-escaped and base64 - through the real paths: the claude backend's session (a thrown error,
// the CLI's stderr) and StreamMapper (an MCP tool's error result), and the WebSocket server.
// Finding 3 of the wave-3 security review: runtime diagnostics bypassed the settings masking.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it, vi } from 'vitest';
import WebSocket from 'ws';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import type { Logger } from '../src/context.js';
import { Foreman } from '../src/foreman.js';
import { Notifier } from '../src/notifier.js';
import { ServerMessage, type Outbound } from '../src/protocol.js';
import { configSecrets, MIN_SECRET, REDACTED, Redactor, secretVariants } from '../src/redact.js';
import { ForemanServer } from '../src/server.js';
import { demoRepo, rmrf, tempDir, testConfig, until } from './helpers.js';

const dirs: string[] = [];
const foremen: Foreman[] = [];
const closers: Array<() => Promise<void>> = [];
afterEach(async () => {
  for (const c of closers.splice(0)) await c();
  for (const f of foremen.splice(0)) await f.close();
  for (const d of dirs.splice(0)) rmrf(d);
});

/** The forms a leak could take: as it is, URL-encoded, JSON-escaped, base64. */
function encodings(s: string): string[] {
  const b64 = Buffer.from(s).toString('base64');
  return [s, encodeURIComponent(s), JSON.stringify(s).slice(1, -1), b64, b64.replace(/=+$/, ''), b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')];
}

function expectAbsent(text: string, secrets: string[]): void {
  for (const s of secrets) for (const e of encodings(s)) expect(text.includes(e), `${JSON.stringify(s)} as ${JSON.stringify(e)}`).toBe(false);
}

/** Every file under `dir` except config.json and its backup, as text. */
function otherFiles(dir: string): string {
  let text = '';
  for (const e of fs.readdirSync(dir, { withFileTypes: true, recursive: true })) {
    if (!e.isFile() || /^config\.json(\.bak)?$/.test(e.name)) continue;
    text += fs.readFileSync(path.join(e.parentPath, e.name), 'utf8');
  }
  return text;
}

function capture(): { logs: string[]; logger: Logger } {
  const logs: string[] = [];
  return { logs, logger: { info: (m) => logs.push(m), warn: (m) => logs.push(m), error: (m) => logs.push(m), debug: (m) => logs.push(m) } };
}

describe('Redactor', () => {
  it('cuts a value and its URL-encoded, JSON-escaped and base64 forms; ignores short values', () => {
    const r = new Redactor();
    const secret = 'p@ss w/rd+"q"\\x';
    r.add([secret, 'short', undefined]);
    expect(r.size).toBe(1);
    for (const e of encodings(secret)) expect(r.redact(`before ${e} after`)).toBe(`before ${REDACTED} after`);
    expect(r.redact('a short word')).toBe('a short word');
    expect(secretVariants('abcdefgh')).toContain(Buffer.from('abcdefgh').toString('base64'));
    expect(MIN_SECRET).toBe(6);
  });

  it('cuts the start of a secret left at a "…" cut, and copies rather than changes objects', () => {
    const r = new Redactor();
    r.add(['sk-live-0123456789']);
    expect(r.redact('token: sk-live-01…')).toBe(`token: ${REDACTED}…`);
    // a cut inside a longer text (truncate, then wrapped)
    expect(r.redact('denied: Bash (curl -H sk-live-01…) and "sk-l…" is blocked')).toBe(`denied: Bash (curl -H ${REDACTED}…) and "${REDACTED}…" is blocked`);
    expect(r.redact('just words…')).toBe('just words…');
    const o = { a: ['x sk-live-0123456789'], n: 1 };
    expect(r.redactDeep(o)).toEqual({ a: [`x ${REDACTED}`], n: 1 });
    expect(o.a[0]).toBe('x sk-live-0123456789');
  });

  it('collects repository env, MCP env, args, URL paths / queries, headers and the rest of a command line', () => {
    const got = configSecrets({
      repoSettings: { '/r': { env: { A: 'repo-env-1' } } },
      claude: { context: { mcpServers: { s: { command: 'node /srv/x.js', args: ['--token', 'arg-val-2', 'KEY=kv-val-3'], env: { B: 'mcp-env-4' } }, h: { type: 'http', url: 'https://h.example/p/path-tok-5?k=query-tok-6', headers: { Authorization: 'Bearer hdr-tok-7' } }, plain: { type: 'http', url: 'https://plain.example' } } } },
    } as never);
    for (const s of ['repo-env-1', 'arg-val-2', 'kv-val-3', 'mcp-env-4', 'path-tok-5', 'query-tok-6', 'hdr-tok-7', 'Bearer hdr-tok-7', '/srv/x.js']) expect(got).toContain(s);
    expect(got).not.toContain('https://plain.example');
  });
});

// ---- runtime diagnostics through the claude backend ------------------------------------------

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;

describe('runtime diagnostics (finding 3)', () => {
  it('a thrown error, the CLI stderr and an MCP tool error result never carry a known secret', async () => {
    const home = tempDir();
    const repoPath = await demoRepo();
    dirs.push(home, path.dirname(repoPath));
    const envSecret = 'sk-live-repo-env-secret-0001';
    const argSecret = 'ghp_mcpArgSecret0002abcdefghij';
    const urlSecret = 'url-path-secret-0003';
    fs.writeFileSync(
      path.join(home, 'config.json'),
      JSON.stringify({ repoSettings: { [repoPath]: { env: { API_KEY: envSecret } } }, claude: { context: { mcpServers: { gh: { command: 'npx', args: ['--token', argSecret] }, web: { type: 'http', url: `https://mcp.example/mcp/${urlSecret}` } } } } }),
    );
    const { logs, logger } = capture();
    const fm = new Foreman({ config: testConfig(home, ['--backend', 'claude', '--repo', repoPath, '--workers', 'kit', '--debug']), logger, notifier: new Notifier({ enabled: false, bell: false }) });
    foremen.push(fm);
    const out: Outbound[] = [];
    fm.subscribe((x) => out.push(x));
    let stderrDone = false;
    const queryFn = ({ options }: { prompt: string; options: Options }) => {
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield m({ type: 'system', subtype: 'init', session_id: s, model: 'fake' });
        // an MCP server's error result quoting its credentials (plain, URL-encoded, JSON-escaped, base64, at the cut)
        yield m({ type: 'assistant', session_id: s, message: { content: [{ type: 'tool_use', id: 'tu-mcp', name: 'mcp__gh__get_issue', input: {} }] } });
        const quoted = `401 for token=${argSecret} url=${encodeURIComponent(`https://mcp.example/mcp/${urlSecret}`)} body={"key":${JSON.stringify(envSecret)}} b64=${Buffer.from(envSecret).toString('base64')}`;
        yield m({ type: 'user', session_id: s, message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'tu-mcp', is_error: true, content: `${quoted} ${'x'.repeat(580)}${argSecret}` }] } });
        // the CLI process the session spawns writes the secret to stderr
        const child = options.spawnClaudeCodeProcess!({ command: process.execPath, args: ['-e', `process.stderr.write('auth failed: ' + ${JSON.stringify(envSecret)} + '\\n')`], cwd: options.cwd!, env: { ...process.env }, signal: new AbortController().signal } as never) as unknown as NodeJS.EventEmitter;
        await new Promise((r) => child.once('exit', r));
        await new Promise((r) => setTimeout(r, 50));
        stderrDone = true;
        // then the session dies with an exception quoting the environment value (like spawn's EINVAL)
        throw new Error(`spawn claude EINVAL: env API_KEY=${envSecret} not allowed (arg ${argSecret}, ${urlSecret})`);
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await fm.start(new ClaudeBackend(fm, testConfig(home, ['--backend', 'claude']).claude, { queryFn: queryFn as never, skipAuthCheck: true, transientRetryMs: 60_000 }));
    await fm.submitGoal('add a flag');
    await until(() => stderrDone && fm.store.logTail('marlow').some((e) => e.text.startsWith('session error')));
    fm.flushLogs();
    // the diagnostics are there, without the secrets
    const tail = fm.store.logTail('marlow').map((e) => e.text);
    expect(tail.some((t) => t.startsWith('401 for token=[redacted]'))).toBe(true);
    expect(tail.some((t) => t.includes('session error: spawn claude EINVAL: env API_KEY=[redacted]'))).toBe(true);
    expect(logs.some((l) => l.includes('stderr] auth failed: [redacted]'))).toBe(true);
    await fm.close();
    const everything = [JSON.stringify(out), logs.join('\n'), otherFiles(home)].join('\n');
    expectAbsent(everything, [envSecret, argSecret, urlSecret]);
  });
});

// ---- property-style: many secret shapes through the WebSocket server --------------------------

interface Client {
  raw: string[];
  send(o: Record<string, unknown>): void;
  acks: Map<string, { ok: boolean; error?: string; result?: unknown }>;
}

function connect(port: number): Promise<Client> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    const c: Client = { raw: [], acks: new Map(), send: (o) => ws.send(JSON.stringify({ v: 1, ...o })) };
    ws.on('message', (d) => {
      const text = d.toString();
      c.raw.push(text);
      const msg = JSON.parse(text);
      expect(ServerMessage.safeParse(msg).success, text.slice(0, 200)).toBe(true);
      if (msg.type === 'ack') c.acks.set(msg.re, msg);
    });
    ws.once('open', () => resolve(c));
    ws.once('error', reject);
    closers.push(async () => ws.close());
  });
}

let reqId = 0;
async function request(c: Client, o: Record<string, unknown>): Promise<{ ok: boolean; error?: string; result?: any }> {
  const id = `r${++reqId}`;
  c.send({ id, ...o });
  await until(() => c.acks.has(id), 5000, 5);
  return c.acks.get(id)!;
}

const SHAPES = [
  'hunter2-pass',
  'sk-ant-api03-AbC/dEf+GhI=',
  'ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345',
  'p@ss w0rd "quoted" \\ back',
  'ünïcödé-sëcret',
  'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln',
  'AKIAIOSFODNN7EXAMPLE',
  '123456',
  'line1\nline2-secret',
  'a&b=c?d#e%f',
  'dGhpcyBpcy9hIHNlY3JldA',
];

describe('every secret shape, everywhere the Foreman speaks (WebSocket path)', () => {
  it('stored, sent, logged, fed, notified and thrown: never out in any encoding', async () => {
    const home = tempDir();
    const repoPath = await demoRepo();
    dirs.push(home, path.dirname(repoPath));
    const stored = SHAPES.map((s) => `${s}-stored`);
    const fresh = SHAPES.map((s) => `${s}-fresh`);
    const refused = SHAPES.map((s) => `${s}-refused`);
    fs.writeFileSync(
      path.join(home, 'config.json'),
      JSON.stringify({
        repoSettings: { [repoPath]: { env: Object.fromEntries(stored.map((s, i) => [`K${i}`, s])) } },
        claude: {
          context: {
            mcpServers: {
              // a filesystem server whose argument is the repository path: structured fields stay intact
              fs: { command: 'npx', args: ['-y', 'fs-mcp', repoPath, ...stored] },
              web: { type: 'http', url: `https://mcp.example/mcp/${encodeURIComponent(stored[2]!)}?key=${encodeURIComponent(stored[0]!)}`, headers: { Authorization: `Bearer ${stored[5]}` } },
              envd: { command: 'node', env: Object.fromEntries(stored.map((s, i) => [`E${i}`, s])) },
            },
          },
        },
      }),
    );
    const { logs, logger } = capture();
    const fm = new Foreman({ config: testConfig(home, ['--backend', 'claude', '--repo', repoPath]), logger, notifier: new Notifier({ enabled: false, bell: false }) });
    foremen.push(fm);
    const token = 'client-token-0123456789abcdef';
    fm.addSecrets([token]);
    const server = new ForemanServer(fm, { host: '127.0.0.1', port: 0, token, validateOutbound: true, log: fm.log });
    const port = await server.start();
    closers.push(() => server.stop());
    await fm.repos.add(repoPath);
    const c = await connect(port);
    c.send({ type: 'hello', modVersion: 'test', protocol: 1, client: 'test', token });
    await until(() => c.raw.some((r) => r.includes('"type":"snapshot"')));
    const snapshot = JSON.parse(c.raw.find((r) => r.includes('"type":"snapshot"'))!);
    expect(snapshot.repos[0].path).toBe(fm.repos.list()[0]!.path); // a path that is also an MCP argument is not cut
    const repoId = snapshot.repos[0].id as string;

    // config.get, global and per repository
    expect((await request(c, { type: 'config.get' })).ok).toBe(true);
    expect((await request(c, { type: 'config.get', repoId })).ok).toBe(true);
    // refused changes carrying never-stored secrets as keys, values, names and URLs
    for (const s of refused) {
      const bad = await request(c, {
        type: 'config.set',
        changes: [
          { key: `claude.${s}`, value: s },
          { key: 'claude.context.mcpServers', value: [{ name: 'x', type: 'stdio', args: [s, '(hidden)'], [s]: s }, { name: s, type: 'http', url: `https://h.example/${s}` }] },
          { key: 'claude.agents.kit.model', value: s },
          { key: 'claude.workers', value: [s] },
        ],
      });
      expect(bad.ok).toBe(false);
      const env = await request(c, { type: 'config.set', repoId, changes: [{ key: 'env', value: { [`${s}=`]: s, OK: `${s}\u0000` } }] });
      expect(env.ok).toBe(false);
    }
    // accepted changes storing fresh secrets (acks and config.changed must not carry them)
    const okRepo = await request(c, { type: 'config.set', repoId, changes: [{ key: 'env', value: Object.fromEntries(fresh.map((s, i) => [`F${i}`, s])) }] });
    expect(okRepo.ok, okRepo.error).toBe(true);
    const okMcp = await request(c, { type: 'config.set', changes: [{ key: 'claude.context.mcpServers', value: [{ name: 'more', type: 'stdio', command: 'npx', args: fresh, env: { X: fresh[0] } }] }] });
    expect(okMcp.ok, okMcp.error).toBe(true);

    // diagnostics carrying every known secret, in every encoding, reach logs, feed, notifications and errors
    const known = [...stored, ...fresh, token];
    for (const s of known) {
      for (const e of encodings(s)) {
        fm.agentLog('marlow', 'error', `tool failed: ${e}`);
        fm.bus.feed('error', `merge failed: ${e}`);
        fm.notify('warn', `heads up: ${e}`);
        fm.log.error(`console: ${e}`);
      }
      fm.agentLog('marlow', 'error', `${'y'.repeat(1990)}${s}`); // cut by the log limit
    }
    const spy = vi.spyOn(fm, 'digest').mockImplementation(() => {
      throw new Error(`internal: ${known.join(' ')}`);
    });
    const internal = await request(c, { type: 'goal.digest', since: 0 });
    expect(internal.ok).toBe(false);
    expect(internal.error).toContain('[redacted]');
    spy.mockRestore();
    fm.flushLogs();
    await new Promise((r) => setTimeout(r, 100));
    expect(c.raw.some((r) => r.includes('"type":"agent.log"') && r.includes('tool failed: [redacted]'))).toBe(true);

    await server.stop();
    await fm.close();
    const everything = [c.raw.join('\n'), logs.join('\n'), otherFiles(home)].join('\n');
    expectAbsent(everything, [...known, ...refused]);
  });
});

describe('text stored before a secret was known (snapshot, agent.logs.request, goal.digest)', () => {
  it('is cut on the way out', async () => {
    const home = tempDir();
    dirs.push(home);
    const secret = 'late-known-secret-0042';
    // a run that did not know the value yet: its log line and feed item are stored as they were
    const first = new Foreman({ config: testConfig(home, ['--backend', 'claude']), logger: capture().logger, notifier: new Notifier({ enabled: false, bell: false }) });
    first.agentLog('marlow', 'error', `old stderr: ${secret}`);
    first.bus.feed('error', `old failure: ${secret}`);
    await first.close();
    expect(otherFiles(home)).toContain(secret);
    // now config.json holds it (a repository's env)
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { '/nowhere/repo': { env: { LATE: secret } } } }));
    const fm = new Foreman({ config: testConfig(home, ['--backend', 'claude']), logger: capture().logger, notifier: new Notifier({ enabled: false, bell: false }) });
    foremen.push(fm);
    const server = new ForemanServer(fm, { host: '127.0.0.1', port: 0, validateOutbound: true, log: fm.log });
    const port = await server.start();
    closers.push(() => server.stop());
    const c = await connect(port);
    c.send({ type: 'hello', modVersion: 'test', protocol: 1, client: 'test' });
    await until(() => c.raw.some((r) => r.includes('"type":"snapshot"')));
    const logs = await request(c, { type: 'agent.logs.request', agentId: 'marlow' });
    expect(logs.ok).toBe(true);
    expect(JSON.stringify(logs.result)).toContain('old stderr: [redacted]');
    expect((await request(c, { type: 'goal.digest', since: 0 })).ok).toBe(true);
    const snapshot = c.raw.find((r) => r.includes('"type":"snapshot"'))!;
    expect(snapshot).toContain('old failure: [redacted]');
    expectAbsent(c.raw.join('\n'), [secret]);
  });
});
