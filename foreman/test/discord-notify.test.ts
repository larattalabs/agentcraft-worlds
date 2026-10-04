// C10: Discord notifications through the user's own script (notify.discord), off by default,
// never blocking: need_user pings (--critical), blocked / usage / goal_done silent.
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { configSet } from '../src/settings.js';
import { DEFAULT_DISCORD, discordArgs, DiscordNotifier } from '../src/notifier.js';
import { makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const dirs: string[] = [];
let h: Harness | undefined;
afterEach(async () => {
  await h?.fm.close();
  h = undefined;
  for (const d of dirs.splice(0)) rmrf(d);
});

/** A fake discord-notify.sh: appends its arguments (one call per line, \x1f between args) to a file. */
function fakeScript(dir: string): { script: string; calls: () => string[][] } {
  const script = path.join(dir, 'fake-notify.sh');
  const out = path.join(dir, 'calls.txt');
  // one append per call (atomic), so concurrent runs do not interleave
  fs.writeFileSync(script, `#!/bin/sh\nsep=$(printf '\\037')\nline=""\nd=""\nfor a in "$@"; do line="$line$d$a"; d="$sep"; done\nprintf '%s\\n' "$line" >> "${out}"\n`, { mode: 0o755 });
  return { script, calls: () => (fs.existsSync(out) ? fs.readFileSync(out, 'utf8').split('\n').filter(Boolean).map((l) => l.split('\x1f')).sort((a, b) => a.join().localeCompare(b.join())) : []) };
}

describe('notify.discord config', () => {
  it('is off by default; an object or true turns it on; notify stays a desktop boolean', () => {
    const home = tempDir();
    dirs.push(home);
    expect(loadConfig(['--home', home], {}).notifyDiscord).toBeUndefined();
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ notify: { desktop: false, discord: { script: '~/bin/n.sh', silent: ['goal_done'] } } }));
    const c = loadConfig(['--home', home, '--backend', 'claude'], {});
    expect(c.notify).toBe(false);
    expect(c.notifyDiscord).toEqual({ script: '~/bin/n.sh', ping: ['need_user', 'auth'], silent: ['goal_done'] });
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ notify: { discord: true } }));
    expect(loadConfig(['--home', home, '--backend', 'claude'], {}).notifyDiscord).toEqual(DEFAULT_DISCORD);
    expect(loadConfig(['--home', home, '--backend', 'claude'], {}).notify).toBe(true);
  });

  it('the Settings toggle for desktop notifications does not wipe notify.discord', () => {
    const home = tempDir();
    dirs.push(home);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ notify: { discord: { script: '/x/n.sh' } } }));
    const cfg = loadConfig(['--home', home, '--backend', 'claude'], {});
    configSet({ cfg, cast: [] }, [{ key: 'notify', value: false }]);
    const raw = JSON.parse(fs.readFileSync(path.join(home, 'config.json'), 'utf8'));
    expect(raw.notify).toEqual({ discord: { script: '/x/n.sh' }, desktop: false });
  });
});

describe('DiscordNotifier', () => {
  it('maps kinds to --critical / silent / nothing', () => {
    expect(discordArgs('need_user', 'Marlow: Merge t3?', DEFAULT_DISCORD)).toEqual(['--critical', '🔴 AgentCraft: Marlow: Merge t3?']);
    expect(discordArgs('goal_done', 'Goal complete: x', DEFAULT_DISCORD)).toEqual(['🟢 AgentCraft: Goal complete: x']);
    expect(discordArgs('blocked', 't3 is blocked', { ...DEFAULT_DISCORD, silent: [] })).toBeUndefined();
  });

  it('runs the script without blocking; a missing script only warns', async () => {
    const dir = tempDir();
    dirs.push(dir);
    const { script, calls } = fakeScript(dir);
    const warns: string[] = [];
    const n = new DiscordNotifier(() => ({ ...DEFAULT_DISCORD, script }), { log: { info() {}, warn: (m) => warns.push(m), error() {}, debug() {} }, coalesceMs: 100 });
    const t0 = Date.now();
    n.send('usage', 'Usage limit reached');
    n.send('need_user', 'Marlow: question one');
    n.send('need_user', 'Marlow: question two'); // within the window: combined
    n.send('need_user', 'Kit: question three');
    expect(Date.now() - t0).toBeLessThan(100); // nothing waited for the script
    await until(() => calls().length >= 3, 5000);
    expect(calls()).toEqual([
      ['--critical', '🔴 AgentCraft: 2 things wait for you: Marlow: question two | Kit: question three'],
      ['--critical', '🔴 AgentCraft: Marlow: question one'],
      ['🟡 AgentCraft: Usage limit reached'],
    ]);
    const missing = new DiscordNotifier(() => ({ ...DEFAULT_DISCORD, script: path.join(dir, 'nope.sh') }), { log: { info() {}, warn: (m) => warns.push(m), error() {}, debug() {} } });
    expect(() => missing.send('goal_done', 'x')).not.toThrow();
    await until(() => warns.some((w) => /could not run/.test(w)), 5000);
    n.dispose();
  });

  it('the Foreman sends need_user, blocked and goal_done when configured', async () => {
    const home = tempDir();
    const dir = tempDir();
    dirs.push(home, dir);
    const { script, calls } = fakeScript(dir);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ notify: { discord: { script } } }));
    h = makeForeman(home, ['--backend', 'claude']); // (a sim run never notifies, see below)
    const fm = h.fm;
    fm.createDecision({ agentId: 'marlow', kind: 'question', question: 'Which flag?', options: ['a', 'b'] });
    const g = fm.createGoal('a goal');
    const t = fm.tasks.create({ title: 'Publish', createdBy: 'marlow', goalId: g.id, assignee: 'kit' });
    fm.tasks.setStatus(t.id, 'blocked', { reason: 'npm publish needs you', force: true });
    fm.setGoal(g.id, { status: 'active' });
    fm.tasks.setStatus(t.id, 'done', { force: true });
    await until(() => calls().length >= 3, 5000);
    expect(calls()).toEqual([
      ['--critical', '🔴 AgentCraft: Marlow: Which flag?'],
      [`🟠 AgentCraft: ${t.id} "Publish" is blocked: npm publish needs you`],
      ['🟢 AgentCraft: Goal complete: a goal'],
    ]);
  });

  it('a sim Foreman never sends (config.json is shared by every profile)', async () => {
    const home = tempDir();
    const dir = tempDir();
    dirs.push(home, dir);
    const { script, calls } = fakeScript(dir);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ notify: { discord: { script } } }));
    h = makeForeman(home, ['--backend', 'sim']);
    h.fm.createDecision({ agentId: 'marlow', kind: 'question', question: 'Which flag?', options: ['a', 'b'] });
    await new Promise((r) => setTimeout(r, 300));
    expect(calls()).toEqual([]);
  });
});
