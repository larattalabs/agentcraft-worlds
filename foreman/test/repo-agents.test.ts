// Repository agent files: as worker roles (repoSettings.roles) and as subagents (repoSettings.subagents "repo").
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { loadRepoAgents, readAgentFile } from '../src/agents/claude/subagents.js';
import { loadConfig } from '../src/config.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const write = (p: string, s: string) => {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, s);
};

const ARCHITECT = '---\nname: dg-architect\ndescription: >-\n  Physics, protocol and design work.\nmodel: arch-model\neffort: high\n---\nYou are the architect. Plan before you build.\n';
const BUILDER = '---\nname: dg-builder\ndescription: Precisely briefed tasks.\nmodel: build-model\n---\nYou are the builder. Follow the brief.\n';
const REVIEWER = '---\nname: reviewer\ndescription: Reviews a diff.\ntools: Read, Grep\n---\nReview it.\n';

describe('agent files', () => {
  it('reads front matter (model, effort, tools) and lists a repo\'s agents minus roles', () => {
    const dir = tempDir();
    try {
      write(path.join(dir, '.claude', 'agents', 'dg-architect.md'), ARCHITECT);
      write(path.join(dir, '.claude', 'agents', 'reviewer.md'), REVIEWER);
      write(path.join(dir, '.claude', 'agents', 'notes.txt'), 'x');
      expect(readAgentFile(path.join(dir, '.claude', 'agents', 'dg-architect.md'))).toEqual({
        name: 'dg-architect',
        description: 'Physics, protocol and design work.',
        prompt: 'You are the architect. Plan before you build.',
        model: 'arch-model',
        effort: 'high',
      });
      expect(Object.keys(loadRepoAgents(dir))).toEqual(['dg-architect', 'reviewer']);
      expect(Object.keys(loadRepoAgents(dir, new Set(['dg-architect'])))).toEqual(['reviewer']);
      expect(loadRepoAgents(path.join(dir, 'nope'))).toEqual({});
    } finally {
      rmrf(dir);
    }
  });

  it('reads repoSettings roles and subagents', () => {
    const home = tempDir();
    try {
      write(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { '/x/app': { roles: { Kit: 'dg-architect', juniper: 'dg-builder', 'bad id': 'x', wren: 3 }, subagents: 'repo' }, '/x/other': { subagents: true } } }));
      const c = loadConfig(['--home', home], {});
      expect(c.repoSettings['/x/app']).toEqual({ roles: { kit: 'dg-architect', juniper: 'dg-builder' }, subagents: 'repo' });
      expect(c.repoSettings['/x/other']).toEqual({});
    } finally {
      rmrf(home);
    }
  });
});

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<unknown> }> } };

describe('repo agents in a running team', () => {
  let h: Harness;
  let home: string;
  let repoPath: string;
  const turns: Array<{ kind: 'lead' | 'work'; prompt: string; options: Options; agentVerdict?: string }> = [];

  beforeAll(async () => {
    home = tempDir();
    repoPath = await demoRepo();
    write(path.join(repoPath, '.claude', 'agents', 'dg-architect.md'), ARCHITECT);
    write(path.join(repoPath, '.claude', 'agents', 'dg-builder.md'), BUILDER);
    write(path.join(repoPath, '.claude', 'agents', 'reviewer.md'), REVIEWER);
    execFileSync('git', ['-C', repoPath, 'add', '.claude']);
    execFileSync('git', ['-C', repoPath, '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'agents']);
    write(
      path.join(home, 'config.json'),
      JSON.stringify({ claude: { agents: { kit: { title: 'Backend', model: 'kit-model' } } }, repoSettings: { [repoPath]: { roles: { kit: 'dg-architect', juniper: 'dg-builder' }, subagents: 'repo' } } }),
    );
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit,juniper', '--repo', repoPath, '--no-lead-review']);
    const queryFn = ({ prompt, options }: { prompt: string; options?: Options }) => {
      const o = options!;
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: String(o.model), cwd: '', tools: [] });
        const p = String(prompt);
        if (p.startsWith('New goal')) {
          turns.push({ kind: 'lead', prompt: p, options: o });
          const tools = (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
          await tools.create_task!.handler({ title: 'Tune the flight model', assignee: 'kit' }, {});
          await tools.create_task!.handler({ title: 'Rename a helper', assignee: 'juniper' }, {});
        } else if (/Your task: t\d+/.test(p)) {
          const v = await o.canUseTool!('Agent', { subagent_type: 'reviewer', prompt: 'review' }, { signal: new AbortController().signal, toolUseID: 'x', requestId: 'r' } as never);
          turns.push({ kind: 'work', prompt: p, options: o, agentVerdict: v?.behavior ?? 'none' });
        }
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
  });

  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(path.dirname(repoPath));
  });

  it('gives workers their repo roles, models and the repo\'s other agents as subagents', async () => {
    await h.fm.submitGoal('flight model');
    const task = (title: string) => turns.find((t) => t.kind === 'work' && new RegExp(`Your task: t\\d+ "${title}"`).test(t.prompt));
    await until(() => !!task('Tune the flight model') && !!task('Rename a helper'));

    const lead = turns.find((t) => t.kind === 'lead')!;
    const leadAppend = (lead.options.systemPrompt as { append: string }).append;
    expect(leadAppend).toContain('- Kit (id "kit"): dg-architect: Physics, protocol and design work.');
    expect(leadAppend).toContain('- Juniper (id "juniper"): dg-builder: Precisely briefed tasks.');

    const kit = task('Tune the flight model')!;
    const kitAppend = (kit.options.systemPrompt as { append: string }).append;
    expect(kitAppend).toContain('## Your role in this repository: dg-architect');
    expect(kitAppend).toContain('You are the architect. Plan before you build.');
    expect(kit.options.model).toBe('arch-model'); // the role's model beats Kit's profile model
    expect(kit.options.effort).toBe('high');
    expect(task('Rename a helper')!.options.model).toBe('build-model');

    // subagents: on for this repo only, the role files are not offered as subagents
    expect(kit.options.tools).toContain('Agent');
    expect(Object.keys(kit.options.agents ?? {})).toEqual(['reviewer']);
    expect(kit.agentVerdict).toBe('allow');
  });
});
