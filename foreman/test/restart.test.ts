// foreman.restart: the command the Foreman re-spawns itself with (no process is started here).
import { describe, expect, it } from 'vitest';
import type { ClientMessage, Outbound } from '../src/protocol.js';
import { restartArgs, restartCommand } from '../src/restart.js';
import { makeForeman, rmrf, tempDir } from './helpers.js';

describe('restart command', () => {
  it('is the same node, node flags, script, arguments, cwd and environment', () => {
    const env = { PATH: '/usr/bin', AGENTCRAFT_PORT: '7878' };
    const cmd = restartCommand({
      execPath: '/usr/local/bin/node',
      execArgv: ['--import', 'tsx'],
      script: '/x/agentcraft/foreman/src/main.ts',
      argv: ['--backend', 'claude', '--profile', 'claude', '--home', '/h', '--port', '7878', '--repo', '/r'],
      cwd: '/x/agentcraft/foreman',
      env,
    });
    expect(cmd).toEqual({
      command: '/usr/local/bin/node',
      args: ['--import', 'tsx', '/x/agentcraft/foreman/src/main.ts', '--backend', 'claude', '--profile', 'claude', '--home', '/h', '--port', '7878', '--repo', '/r'],
      cwd: '/x/agentcraft/foreman',
      env,
    });
    expect(cmd.env).not.toBe(env); // a copy
  });

  it('drops the one-shot arguments: --reset, --goal (with its value) and --autostart', () => {
    expect(restartArgs(['--backend', 'sim', '--reset', '--profile', 'showcase', '--showcase', 'late'])).toEqual(['--backend', 'sim', '--profile', 'showcase', '--showcase', 'late']);
    expect(restartArgs(['--goal', 'Add a --version flag', '--autostart', '--speed', '2'])).toEqual(['--speed', '2']);
    expect(restartArgs(['--goal=do it', '--no-reset', '--debug'])).toEqual(['--debug']);
    expect(restartArgs(['--reset', '--repo', '/r'])).toEqual(['--repo', '/r']);
    expect(restartArgs(['--goal', '--debug'])).toEqual(['--debug']);
  });
});

describe('foreman.restart', () => {
  it('acks, then calls the restarter; refused when nothing can restart', async () => {
    const home = tempDir();
    const h = makeForeman(home);
    try {
      const out: Outbound[] = [];
      await h.fm.handle({ v: 1, id: 'r1', type: 'foreman.restart' } as ClientMessage, (m) => out.push(m));
      expect(out.find((m) => m.type === 'ack')).toMatchObject({ ok: false, error: expect.stringMatching(/cannot restart/) });
      let restarted = 0;
      h.fm.restarter = () => restarted++;
      await h.fm.handle({ v: 1, id: 'r2', type: 'foreman.restart' } as ClientMessage, (m) => out.push(m));
      expect(out.find((m) => m.type === 'ack' && m.re === 'r2')).toMatchObject({ ok: true, result: {} });
      expect(restarted).toBe(0); // only after the ack went out
      await new Promise((r) => setTimeout(r, 300));
      expect(restarted).toBe(1);
    } finally {
      await h.fm.close();
      rmrf(home);
    }
  });
});
