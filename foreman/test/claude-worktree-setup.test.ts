// A worker's first turn runs the repo's worktree setup first; a failed setup reaches its prompt.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };

async function callTool(options: Options, name: string, args: Record<string, unknown>): Promise<string> {
  const server = options.mcpServers!.agentcraft as unknown as ToolServer;
  const res = await server.instance._registeredTools[name]!.handler(args, {});
  return res.content.map((c) => c.text).join('\n');
}

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;

const workPrompts: string[] = [];

function fakeQuery() {
  return ({ prompt, options }: { prompt: string; options?: Options }) => {
    const opts = options!;
    const p = String(prompt);
    async function* run(): AsyncGenerator<SDKMessage> {
      const s = sid();
      yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake-model', cwd: '', tools: [] });
      if (p.startsWith('New goal')) await callTool(opts, 'create_task', { title: 'Do it', assignee: 'kit' });
      if (/Your task: t\d+/.test(p)) workPrompts.push(p);
      yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

let h: Harness;
let home: string;
let repoPath: string;

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { [repoPath]: { setup: 'node -e "console.error(\'install exploded\'); process.exit(1)"' } } }));
  h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery() as never, skipAuthCheck: true }));
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('worktree setup in the claude backend', () => {
  it('runs setup before the first work turn and tells the worker when it failed', async () => {
    await h.fm.submitGoal('do it');
    await until(() => workPrompts.length > 0);
    expect(workPrompts[0]).toContain('the worktree setup command');
    expect(workPrompts[0]).toContain('install exploded');
    expect(h.fm.store.data.feed.some((f) => /worktree setup failed/.test(f.text))).toBe(true);
  });
});
