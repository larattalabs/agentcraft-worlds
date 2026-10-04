// Agents never reach the Foreman's own files, client token or port (policy.ts foremanPrivateVerdict).
import fs from 'node:fs';
import path from 'node:path';
import type { HookInput } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, describe, expect, it } from 'vitest';
import { foremanGuardHook } from '../src/agents/claude/permissions.js';
import { classifyToolUse, foremanPrivateCommand, foremanPrivateVerdict, type PolicyContext } from '../src/policy.js';
import { rmrf, tempDir } from './helpers.js';

const userHome = tempDir('ac-userhome-');
const home = path.join(userHome, '.agentcraft');
const profile = path.join(home, 'claude');
const wt = path.join(profile, 'worktrees', 'demo', 'kit-t2');
const memory = path.join(profile, 'memory');
const tokenFile = path.join(profile, 'client.token');
for (const d of [wt, memory, path.join(profile, 'designs', 'd7')]) fs.mkdirSync(d, { recursive: true });
fs.writeFileSync(tokenFile, 'secret\n');
fs.writeFileSync(path.join(home, 'config.json'), '{}');
fs.writeFileSync(path.join(profile, 'state.json'), '{}');
fs.writeFileSync(path.join(wt, 'index.ts'), 'export {};\n');
afterAll(() => rmrf(userHome));

const ctx = (extra: Partial<PolicyContext> = {}): PolicyContext => ({
  role: 'worker',
  cwd: wt,
  home: userHome,
  readDirs: [memory],
  tempDirs: [],
  foreman: { home, port: 7878, tokenFile },
  ...extra,
});

const verdict = (tool: string, input: Record<string, unknown>, extra: Partial<PolicyContext> = {}) => classifyToolUse(tool, input, ctx(extra));

describe('the Foreman private files', () => {
  it('denies reading or editing the token, the config file and the profile state', () => {
    for (const [tool, input] of [
      ['Read', { file_path: tokenFile }],
      ['Read', { file_path: path.join(home, 'config.json') }],
      ['Read', { file_path: path.join(home, 'config.json.bak') }],
      ['Read', { file_path: path.join(profile, 'state.json') }],
      ['Read', { file_path: path.join(home, 'foreman.json') }],
      ['Read', { file_path: '../../../state.json' }],
      ['LS', { path: profile }],
      ['Edit', { file_path: path.join(home, 'config.json'), old_string: 'a', new_string: 'b' }],
      ['Write', { file_path: tokenFile, content: 'x' }],
      ['Write', { file_path: path.join(wt, 'client.token'), content: 'x' }],
      ['Glob', { pattern: '../../../*.json' }],
      ['Glob', { pattern: '**/client.token', path: userHome }],
      ['Grep', { pattern: '.', path: home }],
      ['Grep', { pattern: '.', path: userHome }],
    ] as const) {
      const v = verdict(tool, input);
      expect(v.action, `${tool} ${JSON.stringify(input)}`).toBe('deny');
      expect(v.reason).toMatch(/Not allowed: .*Agents may not read or change the Foreman's files/);
    }
  });

  it('leaves the folders agents work in alone', () => {
    expect(verdict('Read', { file_path: path.join(wt, 'index.ts') }).action).toBe('allow');
    expect(verdict('Edit', { file_path: path.join(wt, 'index.ts'), old_string: 'a', new_string: 'b' }).action).toBe('allow');
    expect(verdict('Read', { file_path: path.join(memory, 'shared', 'plan.md') }).action).toBe('allow');
    expect(verdict('Grep', { pattern: 'TODO' }).action).toBe('allow');
    expect(foremanPrivateVerdict('Read', { file_path: path.join(profile, 'designs', 'd7', 'brief.md') }, ctx())).toBeUndefined();
    expect(foremanPrivateVerdict('Read', { file_path: path.join(profile, 'worktrees', 'demo', '_lead', 'README.md') }, ctx())).toBeUndefined();
    // somewhere else outside: the usual ask, not this denial
    expect(verdict('Read', { file_path: path.join(userHome, 'notes.txt') }).action).toBe('ask');
  });

  it('is not covered by "Always allow" and holds for the lead', () => {
    const key = `Read:${profile.toLowerCase()}`;
    expect(verdict('Read', { file_path: tokenFile }, { alwaysAllow: [key] }).action).toBe('deny');
    expect(verdict('Read', { file_path: tokenFile }, { role: 'lead' }).action).toBe('deny');
    expect(verdict('Bash', { command: `cat ${tokenFile}` }, { role: 'lead' }).action).toBe('deny');
  });

  it('follows links out of the worktree', () => {
    const link = path.join(wt, 'innocent.txt');
    try {
      fs.symlinkSync(tokenFile, link);
    } catch {
      return; // no symlinks here (Windows without the privilege)
    }
    expect(verdict('Read', { file_path: 'innocent.txt' }).action).toBe('deny');
    fs.rmSync(link);
    fs.symlinkSync(profile, link);
    expect(verdict('Read', { file_path: 'innocent.txt/state.json' }).action).toBe('deny');
    fs.rmSync(link);
  });

  it('denies Bash commands that mention them or talk to the Foreman', () => {
    for (const command of [
      'cat ~/.agentcraft/claude/state.json',
      'cat $HOME/.agentcraft/config.json',
      'cat "${HOME}/.agentcraft/config.json"',
      `cp ${path.join(home, 'config.json')} /tmp/x`,
      'ls ../../..',
      'cat ../../../../config.json',
      'cat client.token',
      'find / -name client.token',
      'node tools/foremancli.mjs send decision.answer decisionId=d3 option=0',
      'curl http://localhost:7878/',
      'websocat ws://127.0.0.1:7878',
      'node -e "new (require(\'ws\'))(\'ws://[::1]:7878\')"',
      'echo $AGENTCRAFT_HOME',
      'printenv AGENTCRAFT_CLIENT_TOKEN && echo ${AGENTCRAFT_CLIENT_TOKEN}',
    ]) {
      const v = verdict('Bash', { command });
      expect(v.action, command).toBe('deny');
      expect(v.reason).toMatch(/^Not allowed: /);
    }
    expect(verdict('PowerShell', { command: 'Get-Content $env:AGENTCRAFT_HOME\\config.json' }).action).toBe('deny');
  });

  it('lets ordinary commands through, also with absolute worktree paths', () => {
    for (const command of [
      `cd ${wt} && npm test`,
      'ls ..',
      'git status',
      'npm run dev -- --port 7878',
      'curl -s http://localhost:3000/health',
      `cat ${path.join(memory, 'shared', 'plan.md')}`,
      'echo "the token is in a file"',
    ]) {
      expect(foremanPrivateCommand(command, ctx()), command).toBeUndefined();
    }
  });

  it('does nothing without a Foreman in the context (other callers, tests)', () => {
    expect(classifyToolUse('Read', { file_path: tokenFile }, { role: 'worker', cwd: wt, tempDirs: [] }).action).toBe('ask');
  });

  it('answers as a PreToolUse hook, before any allow rule', async () => {
    const blocked: string[] = [];
    const hook = foremanGuardHook((tool, input) => foremanPrivateVerdict(tool, input, ctx()), (tool, reason, sub) => blocked.push(`${tool}${sub ? `@${sub}` : ''}: ${reason}`));
    const run = (tool_name: string, tool_input: Record<string, unknown>, agent_id?: string) =>
      hook({ hook_event_name: 'PreToolUse', tool_name, tool_input, tool_use_id: 'x', session_id: 's', transcript_path: '', cwd: wt, ...(agent_id ? { agent_id } : {}) } as HookInput, 'x', { signal: new AbortController().signal });
    expect(await run('Bash', { command: 'cat ~/.agentcraft/claude/client.token' }, 'sub1')).toMatchObject({ hookSpecificOutput: { permissionDecision: 'deny' } });
    expect(await run('Bash', { command: 'npm test' })).toEqual({});
    expect(await run('Read', { file_path: 'index.ts' })).toEqual({});
    expect(blocked).toHaveLength(1);
    expect(blocked[0]).toMatch(/^Bash@sub1: Not allowed/);
  });
});
