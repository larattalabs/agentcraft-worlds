// Holds (C9 foreman.status.hold): a network failure at start is retried instead of failing auth for
// good; exact auth phrases only; the usage reserve pauses new turns; limits lift on the wall clock.
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it } from 'vitest';
import { classifyFailure, isAuthText, probeFailure } from '../src/agents/claude/failures.js';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { RESERVE_STALE_MS, reserveHold } from '../src/agents/claude/usage.js';
import { loadConfig } from '../src/config.js';
import type { PlanUsage } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<unknown> }> } };

let h: Harness | undefined;
let home = '';
let repoPath = '';
afterEach(async () => {
  await h?.fm.close();
  rmrf(home);
  if (repoPath) rmrf(path.dirname(repoPath));
  h = undefined;
  repoPath = '';
});

describe('failure classification', () => {
  it('auth needs a structured error or an exact phrase', () => {
    expect(isAuthText('Invalid API key · Please run /login')).toBe(true);
    expect(isAuthText('API Error: 401 {"type":"error","error":{"type":"authentication_error"}}')).toBe(true);
    expect(isAuthText('OAuth token has expired')).toBe(true);
    // the old /auth|login|credential|401/ hits
    expect(isAuthText('connect ECONNREFUSED 127.0.0.1:14010')).toBe(false);
    expect(isAuthText('fetch failed (oauth refresh)')).toBe(false);
    expect(isAuthText('Error reading credentials cache: EACCES')).toBe(false);
    expect(isAuthText('the author wrote a login page')).toBe(false);
    expect(probeFailure('not logged in')).toBe('auth');
    expect(probeFailure('timed out after 45s')).toBe('retry');
    expect(probeFailure('getaddrinfo ENOTFOUND api.anthropic.com')).toBe('retry');
  });

  it('sorts turn failures into auth / limit / transient / context / fatal', () => {
    const s = (o: Partial<Parameters<typeof classifyFailure>[0] & object>) => ({ isError: true, errors: [], ...o });
    expect(classifyFailure(s({ errors: ['authentication_failed'] }))).toBe('auth');
    expect(classifyFailure(s({ limited: true }))).toBe('limit');
    expect(classifyFailure(s({ errors: ['overloaded'] }))).toBe('transient');
    expect(classifyFailure(s({ errors: ['API Error: 529 {"type":"overloaded_error"}'] }))).toBe('transient');
    expect(classifyFailure(s({ errors: ['socket hang up'] }))).toBe('transient');
    expect(classifyFailure(s({ subtype: 'error_max_turns' }))).toBe('transient');
    expect(classifyFailure(undefined, { timedOut: true })).toBe('transient');
    expect(classifyFailure(s({ errors: ['Prompt is too long'] }))).toBe('context');
    expect(classifyFailure(s({ subtype: 'error_max_budget_usd' }))).toBe('fatal'); // never pay for it again
    expect(classifyFailure(s({ errors: ['billing_error'] }))).toBe('fatal');
    expect(classifyFailure(s({ errors: ['tool exploded'] }))).toBe('fatal');
  });
});

describe('the startup auth probe', () => {
  it('retries a network failure with backoff (hold offline), sticky only for a bad login', async () => {
    home = tempDir();
    h = makeForeman(home, ['--backend', 'claude', '--use-claude-login']);
    let calls = 0;
    const q = () => ({
      close() {},
      accountInfo: async () => {
        calls++;
        if (calls === 1) throw new Error('fetch failed: getaddrinfo ENOTFOUND api.anthropic.com');
        return { email: 'x@example.com', organization: 'Acme' };
      },
    });
    const b = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: q as never, authRetryMs: 50 });
    expect(await b.checkAuth()).toBe(false);
    expect(h.fm.status.auth).toBe('checking'); // not "failed": goals still queue
    expect(h.fm.status.hold).toMatchObject({ reason: 'offline', message: expect.stringMatching(/could not be reached .*trying again/) });
    await until(() => h!.fm.status.auth === 'ok', 5000);
    expect(calls).toBe(2);
    expect(h.fm.status.hold).toBeUndefined();
    await b.stop();
  });

  it('a bad login is sticky and reported as hold auth', async () => {
    home = tempDir();
    h = makeForeman(home, ['--backend', 'claude', '--use-claude-login']);
    const q = () => ({ close() {}, accountInfo: async () => ({}) }); // nothing: not logged in
    const b = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: q as never, authRetryMs: 20 });
    expect(await b.checkAuth()).toBe(false);
    expect(h.fm.status.auth).toBe('failed');
    expect(h.fm.status.hold).toMatchObject({ reason: 'auth' });
    await new Promise((r) => setTimeout(r, 100));
    expect(h.fm.status.auth).toBe('failed'); // no retry
    await b.stop();
  });
});

describe('usage reserve', () => {
  const usage = (pct: number, resetsAt?: number, updatedAt = Date.now()): PlanUsage => ({ windows: [{ id: 'five_hour', label: '5h', pct, ...(resetsAt ? { resetsAt } : {}) }], updatedAt });

  it('holds while a window is at or above its reserve, until it resets', () => {
    const now = Date.now();
    const r = { fiveHourPct: 85, sevenDayPct: 80 };
    expect(reserveHold(usage(84, now + 1000), r, now)).toBeUndefined();
    expect(reserveHold(usage(85, now + 1000), r, now)).toMatchObject({ limit: 85, until: now + 1000 });
    expect(reserveHold(usage(95, now - 1), r, now)).toBeUndefined(); // the window reset: stale data
    expect(reserveHold(usage(95, undefined, now - RESERVE_STALE_MS - 1), r, now)).toBeUndefined(); // no reset time: bounded
    expect(reserveHold(usage(99, now + 1000), { fiveHourPct: 0, sevenDayPct: 80 }, now)).toBeUndefined(); // 0 = off
    expect(reserveHold({ windows: [{ id: 'seven_day', label: '7d', pct: 81, resetsAt: now + 5000 }], updatedAt: now }, r, now)).toMatchObject({ limit: 80 });
  });

  it('config claude.usageReserve defaults to 85 / 80 and clamps', () => {
    const dir = tempDir();
    try {
      expect(loadConfig(['--home', dir], {}).claude.usageReserve).toEqual({ fiveHourPct: 85, sevenDayPct: 80 });
    } finally {
      rmrf(dir);
    }
  });

  it('pauses new turns above the reserve (hold usage) and starts them when the window resets', async () => {
    home = tempDir();
    repoPath = await demoRepo();
    h = makeForeman(home, ['--backend', 'claude', '--repo', repoPath, '--workers', 'kit', '--no-lead-review']);
    const fm = h.fm;
    const prompts: string[] = [];
    const queryFn = ({ prompt, options }: { prompt: string; options?: Options }) => {
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        prompts.push(String(prompt));
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'm', cwd: '', tools: [] });
        if (String(prompt).startsWith('New goal')) await (options!.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools.create_task!.handler({ title: 'x', assignee: 'kit' }, {});
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await fm.start(new ClaudeBackend(fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true, wakeIntervalMs: 50 }));
    const resetsAt = Date.now() + 600;
    fm.setStatus({ usage: usage(90, resetsAt) });
    await until(() => fm.status.hold?.reason === 'usage', 3000);
    expect(fm.status.hold!.message).toMatch(/5h at 90% \(reserve 85%\)/);
    expect(fm.store.data.feed.some((f) => /reserve 85%/.test(f.text))).toBe(true);
    await fm.submitGoal('do it');
    await new Promise((r) => setTimeout(r, 200));
    expect(prompts).toEqual([]); // nothing starts while held
    // the window resets on the wall clock: the wake interval notices, the plan turn starts
    await until(() => prompts.length >= 1, 5000);
    expect(fm.status.hold).toBeUndefined();
  });

  it('a usage limit whose timer did not fire is lifted by the 60 s wall-clock check', async () => {
    home = tempDir();
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit']);
    const b = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: (() => ({})) as never, skipAuthCheck: true, wakeIntervalMs: 30 });
    await h.fm.start(b);
    // as if the machine slept through the reset: a limit in the past, no timer armed for it
    (h.fm.store.data.backend.claude as { limit?: { until: number } }).limit = { until: Date.now() - 1 };
    await until(() => !(h!.fm.store.data.backend.claude as { limit?: unknown }).limit, 2000);
  });
});
