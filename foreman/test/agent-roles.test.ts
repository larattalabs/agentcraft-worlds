// Roles (claude.agents, claude.taskModels): titles, specialties in prompts, per-agent and per-task
// models, private notes surfaced to workers.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { leadSystemPrompt, workerSystemPrompt, workPrompt } from '../src/agents/prompts.js';
import { loadConfig } from '../src/config.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;

const CONFIG = {
  claude: {
    agents: {
      marlow: { model: 'opus-x', effort: 'high' },
      kit: { title: 'Backend', prompt: 'APIs, databases and the server side.', model: 'sonnet-x', effort: 'low' },
      juniper: { title: 'Frontend' },
    },
    taskModels: { small: 'haiku-x', large: 'opus-x' },
  },
};

describe('config', () => {
  it('reads agent profiles and task models', () => {
    const home = tempDir();
    try {
      fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { ...CONFIG.claude, agents: { ...CONFIG.claude.agents, 'bad id!': { title: 'x' } }, taskModels: { small: 'haiku-x', huge: 'y' } } }));
      const c = loadConfig(['--home', home], {}).claude;
      expect(c.agents.kit).toEqual({ title: 'Backend', prompt: 'APIs, databases and the server side.', model: 'sonnet-x', effort: 'low' });
      expect(Object.keys(c.agents).sort()).toEqual(['juniper', 'kit', 'marlow']);
      expect(c.taskModels).toEqual({ small: 'haiku-x' });
      fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { agents: { kit: { effort: 'turbo' } } } }));
      expect(() => loadConfig(['--home', home], {})).toThrow(/unknown effort/);
    } finally {
      rmrf(home);
    }
  });
});

describe('roles in a running team', () => {
  let h: Harness;
  let home: string;
  let repoPath: string;
  let backend: ClaudeBackend;
  const workTurns: Array<{ prompt: string; options: Options }> = [];

  beforeAll(async () => {
    home = tempDir();
    repoPath = await demoRepo();
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify(CONFIG));
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit,juniper', '--repo', repoPath, '--no-lead-review']);
    const queryFn = ({ prompt, options }: { prompt: string; options?: Options }) => {
      const opts = options!;
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: String(opts.model), cwd: '', tools: [] });
        const tools = (opts.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
        if (String(prompt).startsWith('New goal')) {
          await tools.create_task!.handler({ title: 'Rename a constant', description: 'mechanical', assignee: 'kit', size: 'small' }, {});
          await tools.create_task!.handler({ title: 'Restyle the header', description: 'css', assignee: 'juniper' }, {});
        } else if (/Your task/.test(String(prompt))) {
          workTurns.push({ prompt: String(prompt), options: opts });
        }
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true });
    await h.fm.start(backend);
  });

  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(path.dirname(repoPath));
  });

  it('shows configured titles on the agents', () => {
    expect(h.fm.agent('kit')!.title).toBe('Backend');
    expect(h.fm.agent('juniper')!.title).toBe('Frontend');
    expect(h.fm.agent('wren')!.title).toBe(h.fm.cast.find((c) => c.id === 'wren')!.title);
  });

  it("puts specialties in the lead's team list and the workers' prompts", () => {
    const lead = leadSystemPrompt(h.fm, ['kit', 'juniper']);
    expect(lead).toContain('- Kit (id "kit"): Backend: APIs, databases and the server side.');
    expect(lead).toMatch(/- Juniper \(id "juniper"\): Frontend: /); // cast description fills in
    expect(lead).toContain('"small" (haiku-x) and "large" (opus-x)');
    const wt = { id: 'w', agentId: 'kit', taskId: 't', branch: 'b', base: 'main', path: '/tmp/w', status: 'active' as const, ahead: 0, files: 0, additions: 0, deletions: 0 };
    expect(workerSystemPrompt(h.fm, 'kit', wt)).toContain('Your specialty: Backend. APIs, databases and the server side.');
  });

  it('picks the model by task size, then agent profile, then role', async () => {
    await h.fm.submitGoal('two changes');
    await until(() => workTurns.length >= 2);
    const kit = workTurns.find((t) => /Your task: t\d+ "Rename a constant"/.test(t.prompt))!;
    const juniper = workTurns.find((t) => /Your task: t\d+ "Restyle the header"/.test(t.prompt))!;
    expect(kit.options.model).toBe('haiku-x'); // small task beats Kit's sonnet-x
    expect(kit.options.effort).toBe('low'); // Kit's effort
    expect(juniper.options.model).toBe(h.cfg.claude.workerModel); // no model in Juniper's profile
    expect(backend.modelFor('marlow', 'lead')).toEqual({ model: 'opus-x', effort: 'high' });
  });

  it("lists a worker's private notes in its task prompt", () => {
    h.fm.memory.write({ scope: 'kit', title: 'demo-app: CLI lives in src/cli.ts', body: 'x', author: 'kit' });
    const t = h.fm.tasks.create({ title: 'Another', createdBy: 'marlow', assignee: 'kit' });
    const wt = { id: 'w', agentId: 'kit', taskId: t.id, branch: 'b', base: 'main', path: '/tmp/w', status: 'active' as const, ahead: 0, files: 0, additions: 0, deletions: 0 };
    expect(workPrompt(h.fm, t, undefined, wt, '')).toContain('- kit/demo-app-cli-lives-in-src-cli-ts: demo-app: CLI lives in src/cli.ts');
    const tj = h.fm.tasks.create({ title: 'Juniper task', createdBy: 'marlow', assignee: 'juniper' });
    expect(workPrompt(h.fm, tj, undefined, { ...wt, agentId: 'juniper' }, '')).not.toContain('Your notes');
  });
});
