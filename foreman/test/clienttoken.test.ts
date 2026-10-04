import fs from 'node:fs';
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { clientTokenPath, createClientToken, READ_ONLY_ERROR, removeClientToken, tokenMatches } from '../src/clienttoken.js';
import { loadConfig } from '../src/config.js';
import { silentLogger } from '../src/context.js';
import { readClientToken } from '../src/runfile.js';
import { ForemanServer } from '../src/server.js';
import { makeForeman, rmrf, tempDir, until } from './helpers.js';

const cleanup: string[] = [];
afterAll(() => {
  for (const d of cleanup) rmrf(d);
});

interface Msg {
  type: string;
  re?: string;
  ok?: boolean;
  error?: string;
  [k: string]: unknown;
}

function connect(port: number): Promise<{ ws: WebSocket; msgs: Msg[]; send: (o: Record<string, unknown>) => void }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    const msgs: Msg[] = [];
    ws.on('message', (d) => msgs.push(JSON.parse(d.toString()) as Msg));
    ws.once('open', () => resolve({ ws, msgs, send: (o) => ws.send(JSON.stringify({ v: 1, ...o })) }));
    ws.once('error', reject);
  });
}

async function serve(token?: string) {
  const home = tempDir();
  cleanup.push(home);
  const h = makeForeman(home, ['--backend', 'sim']);
  const server = new ForemanServer(h.fm, { host: '127.0.0.1', port: 0, ...(token ? { token } : {}), log: silentLogger });
  const port = await server.start();
  return {
    h,
    port,
    stop: async () => {
      await server.stop();
      await h.fm.close();
    },
  };
}

const ackOf = (msgs: Msg[], id: string) => msgs.find((m) => m.type === 'ack' && m.re === id);

describe('client token file', () => {
  it('is random, owner-only, and removed only while it is still ours', () => {
    const dir = tempDir();
    cleanup.push(dir);
    const a = createClientToken(dir);
    expect(a.file).toBe(clientTokenPath(dir));
    expect(a.token).toMatch(/^[0-9a-f]{64}$/);
    expect(fs.readFileSync(a.file, 'utf8').trim()).toBe(a.token);
    if (process.platform !== 'win32') expect(fs.statSync(a.file).mode & 0o777).toBe(0o600);
    const b = createClientToken(dir); // a restart writes a new one
    expect(b.token).not.toBe(a.token);
    removeClientToken(a.file, a.token); // the old Foreman's clean-up leaves the new token alone
    expect(fs.existsSync(b.file)).toBe(true);
    removeClientToken(b.file, b.token);
    expect(fs.existsSync(b.file)).toBe(false);
    expect(fs.readdirSync(dir)).toEqual([]);
  });

  it('compares tokens in constant time and refuses non-strings', () => {
    expect(tokenMatches('abc', 'abc')).toBe(true);
    expect(tokenMatches('abc', 'abc\n')).toBe(true);
    expect(tokenMatches('abc', 'abd')).toBe(false);
    expect(tokenMatches('abc', 'ab')).toBe(false);
    expect(tokenMatches('abc', '')).toBe(false);
    expect(tokenMatches('abc', undefined)).toBe(false);
    expect(tokenMatches('abc', 42)).toBe(false);
  });

  it('is found through the run file whose port matches, or AGENTCRAFT_CLIENT_TOKEN', () => {
    const home = tempDir();
    cleanup.push(home);
    const dataDir = path.join(home, 'claude');
    const { token, file } = createClientToken(dataDir);
    fs.writeFileSync(path.join(dataDir, 'foreman.json'), JSON.stringify({ pid: 1, port: 7878, host: '127.0.0.1', tokenFile: file }));
    expect(readClientToken(home, 7878, {})).toBe(token);
    expect(readClientToken(home, undefined, {})).toBe(token);
    expect(readClientToken(home, 9999, {})).toBeUndefined();
    expect(readClientToken(home, 9999, { AGENTCRAFT_CLIENT_TOKEN: 'from-env' })).toBe('from-env');
  });

  it('--no-client-token turns it off', () => {
    const home = tempDir();
    cleanup.push(home);
    expect(loadConfig(['--home', home], {}).clientToken).toBe(true);
    expect(loadConfig(['--home', home, '--no-client-token'], {}).clientToken).toBe(false);
  });
});

describe('WebSocket server with a client token', () => {
  it('keeps connections without the token read-only', async () => {
    const s = await serve('s3cret');
    try {
      const c = await connect(s.port);
      c.send({ type: 'hello', id: 'h', modVersion: 't', protocol: 1, client: 'test' });
      await until(() => !!ackOf(c.msgs, 'h'));
      expect(c.msgs.some((m) => m.type === 'snapshot')).toBe(true);
      expect(ackOf(c.msgs, 'h')!.ok).toBe(true);
      // read-only requests still work
      c.send({ type: 'goal.digest', id: 'dg', since: 0 });
      c.send({ type: 'diff.request', id: 'df', requestId: 'r1', repoId: 'nope', worktree: 'x' });
      await until(() => !!ackOf(c.msgs, 'dg') && !!ackOf(c.msgs, 'df'));
      expect(ackOf(c.msgs, 'dg')!.ok).toBe(true);
      expect(c.msgs.some((m) => m.type === 'diff' && m.requestId === 'r1')).toBe(true);
      // anything that changes something is refused
      for (const [id, msg] of [
        ['um', { type: 'user.message', to: 'kit', text: 'hi' }],
        ['da', { type: 'decision.answer', decisionId: 'd1', option: 0 }],
        ['ra', { type: 'repo.add', path: '/tmp' }],
      ] as const) {
        c.send({ ...msg, id });
        await until(() => !!ackOf(c.msgs, id));
        expect(ackOf(c.msgs, id)).toMatchObject({ ok: false, error: READ_ONLY_ERROR });
      }
      expect(c.msgs.some((m) => m.type === 'error' && m.message === READ_ONLY_ERROR && m.re === 'um')).toBe(true);
      expect(s.h.fm.bus.inbox('kit')).toEqual([]);
      c.ws.close();

      // a wrong token is no token
      const w = await connect(s.port);
      w.send({ type: 'hello', modVersion: 't', protocol: 1, token: 'guess' });
      w.send({ type: 'user.message', id: 'u', to: 'kit', text: 'hi' });
      await until(() => !!ackOf(w.msgs, 'u'));
      expect(ackOf(w.msgs, 'u')!.ok).toBe(false);
      w.ws.close();

      // an implicit hello (first message is an intent) carries no token either
      const i = await connect(s.port);
      i.send({ type: 'user.message', id: 'u', to: 'kit', text: 'hi' });
      await until(() => !!ackOf(i.msgs, 'u'));
      expect(ackOf(i.msgs, 'u')).toMatchObject({ ok: false, error: READ_ONLY_ERROR });
      i.ws.close();
    } finally {
      await s.stop();
    }
  });

  it('lets a client with the token do everything, and sends it the broadcasts like any other', async () => {
    const s = await serve('s3cret');
    try {
      const c = await connect(s.port);
      c.send({ type: 'hello', modVersion: 't', protocol: 1, token: 's3cret' });
      c.send({ type: 'user.message', id: 'u', to: 'kit', text: 'hi' });
      await until(() => !!ackOf(c.msgs, 'u'));
      expect(ackOf(c.msgs, 'u')!.ok).toBe(true);
      c.ws.close();
    } finally {
      await s.stop();
    }
  });

  it('without a token (--no-client-token, tests) every client may do everything', async () => {
    const s = await serve();
    try {
      const c = await connect(s.port);
      c.send({ type: 'hello', modVersion: 't', protocol: 1 });
      c.send({ type: 'user.message', id: 'u', to: 'kit', text: 'hi' });
      await until(() => !!ackOf(c.msgs, 'u'));
      expect(ackOf(c.msgs, 'u')!.ok).toBe(true);
      c.ws.close();
    } finally {
      await s.stop();
    }
  });
});
