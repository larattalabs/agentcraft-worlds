// The user's earlier Claude sessions (claude.context.sessionHistory): summaries, scope, search, reading.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { encodeDir, SessionHistory, summarizeSession } from '../src/history.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const user = (text: string, ts: string, extra: Record<string, unknown> = {}) => ({ type: 'user', timestamp: ts, message: { role: 'user', content: text }, ...extra });
const bash = (command: string, ts: string) => ({ type: 'assistant', timestamp: ts, message: { content: [{ type: 'tool_use', id: 'x', name: 'Bash', input: { command } }] } });
const say = (text: string, ts: string) => ({ type: 'assistant', timestamp: ts, message: { content: [{ type: 'text', text }] } });
const edit = (file: string, ts: string) => ({ type: 'assistant', timestamp: ts, message: { content: [{ type: 'tool_use', id: 'y', name: 'Edit', input: { file_path: file } }] } });

function writeSession(root: string, cwd: string, id: string, records: object[]): string {
  const dir = path.join(root, encodeDir(cwd));
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${id}.jsonl`);
  fs.writeFileSync(file, records.map((r) => JSON.stringify({ cwd, sessionId: id, gitBranch: 'HEAD', ...r })).join('\n') + '\n');
  return file;
}

describe('session history', () => {
  let root: string;
  let ws: string;
  let home: string;
  beforeAll(() => {
    root = tempDir();
    ws = fs.realpathSync(tempDir());
    home = fs.realpathSync(tempDir());
    writeSession(root, ws, 'aaaa1111', [
      { type: 'custom-title', customTitle: 'Rolling text' },
      user('Animate the last synced counter, make it roll over', '2026-09-28T17:41:00Z'),
      user('<command-name>/compact</command-name>', '2026-09-28T17:42:00Z'),
      user('caveat', '2026-09-28T17:42:00Z', { isMeta: true }),
      { type: 'user', timestamp: '2026-09-28T17:43:00Z', message: { content: [{ type: 'tool_result', content: 'big output' }] } },
      bash('cd app && git checkout -b feature/tag-filter && git status 2>&1', '2026-09-28T17:44:00Z'),
      bash('git push -u origin feature/tag-filter 2>&1 | tail -2', '2026-09-28T17:50:00Z'),
      edit(path.join(ws, 'app', 'src', 'sync.ts'), '2026-09-28T17:51:00Z'),
      say(`Investigated the counter. ${'Details of the investigation. '.repeat(60)}`, '2026-09-28T18:00:00Z'),
      say('Opened PR 101; one open question: should under a minute read "just now"?', '2026-09-28T19:20:00Z'),
      { type: 'pr-link', prUrl: 'https://dev.azure.com/o/p/_git/r/pullrequest/101', timestamp: '2026-09-28T19:20:00Z' },
      user('This session is being continued from a previous conversation. Summary: rolling counter done', '2026-09-28T19:21:00Z'),
      user('watch that pr for review comments', '2026-09-28T19:22:00Z'),
    ]);
    // a Claude Desktop scratchpad worktree of the same workspace
    writeSession(root, path.join('/private/tmp/claude-501', encodeDir(ws), 'f00d', 'scratchpad', 'pwa'), 'bbbb2222', [{ type: 'ai-title', aiTitle: 'Badge links' }, user('add badge links', '2026-09-27T10:00:00Z'), bash('git worktree add ../pwa -b feat/badge-links origin/develop', '2026-09-27T10:01:00Z')]);
    // out of scope: another project, and AgentCraft's own agent sessions
    writeSession(root, path.join(home, 'Other'), 'cccc3333', [user('rolling text elsewhere', '2026-09-29T10:00:00Z')]);
    writeSession(root, path.join(home, '.agentcraft', 'claude', 'worktrees', 'x'), 'dddd4444', [user('rolling text by an agent', '2026-09-29T11:00:00Z')]);
    // old: outside the look-back
    const old = writeSession(root, ws, 'eeee5555', [user('ancient rolling text', '2025-01-01T00:00:00Z')]);
    fs.utimesSync(old, new Date('2025-01-01'), new Date('2025-01-01'));
  });
  afterAll(() => {
    rmrf(root);
    rmrf(ws);
    rmrf(home);
  });

  const history = (indexFile?: string) => new SessionHistory({ root, within: () => [ws], exclude: [path.join(home, '.agentcraft')], days: 30, ...(indexFile ? { indexFile } : {}) });

  it('summarises a transcript', async () => {
    const s = await summarizeSession(path.join(root, encodeDir(ws), 'aaaa1111.jsonl'));
    expect(s.title).toBe('Rolling text');
    expect(s.prompts).toBe(2); // not the slash command, meta, tool results or the compaction summary
    expect(s.firstPrompt).toBe('Animate the last synced counter, make it roll over');
    expect(s.lastPrompt).toBe('watch that pr for review comments');
    expect(s.branches).toEqual(['feature/tag-filter']); // not "2" from 2>&1
    expect(s.prs).toEqual(['https://dev.azure.com/o/p/_git/r/pullrequest/101']);
    expect(s.files).toEqual([path.join(ws, 'app', 'src', 'sync.ts')]);
  });

  it('finds sessions in scope only, by branch and words', async () => {
    const h = history();
    expect((await h.find()).map((s) => s.id)).toEqual(['aaaa1111', 'bbbb2222']); // workspace + its Desktop scratchpad; newest first
    expect((await h.find({ branch: 'feat/badge-links' }))[0]!.id).toBe('bbbb2222');
    expect((await h.find({ query: 'rolling' })).map((s) => s.id)).toEqual(['aaaa1111']);
    expect(await h.find({ query: 'nothing-like-this' })).toEqual([]);
    expect(await h.find({ dir: path.join(home, 'Other') })).toEqual([]); // `dir` narrows, never widens the scope
    expect((await h.find({ dir: ws })).map((s) => s.id)).toEqual(['aaaa1111', 'bbbb2222']);
    expect((await h.find({ days: 1000 })).map((s) => s.id)).toContain('eeee5555');
  });

  it('reads a condensed transcript, keeping the start and the end', async () => {
    const h = history();
    const full = (await h.read('aaaa'))!;
    expect(full).toContain('# Rolling text');
    expect(full).toContain('Branches it worked with: feature/tag-filter');
    expect(full).toContain('$ cd app && git checkout -b feature/tag-filter');
    expect(full).toContain('edited ');
    expect(full).toContain('[Summary of the earlier part of this session]');
    expect(full).not.toContain('big output');
    const short = (await h.read('aaaa1111', 1000))!;
    expect(short).toContain('characters of the middle left out');
    expect(short).toContain('watch that pr for review comments');
    expect(await h.read('cccc3333')).toBeUndefined(); // out of scope
  });

  it('caches summaries on disk', async () => {
    const idx = path.join(tempDir(), 'history-index.json');
    await history(idx).find();
    const cached = JSON.parse(fs.readFileSync(idx, 'utf8')) as Record<string, { summary: { id: string } }>;
    expect(Object.values(cached).map((c) => c.summary.id)).toContain('aaaa1111');
    rmrf(path.dirname(idx));
  });
});

// ---- an "on <branch>:" goal with session history on ----------------------------------------------

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };

describe('session history in the claude backend', () => {
  let h: Harness;
  let home: string;
  let repoPath: string;
  let claudeDir: string;
  const saved = process.env.CLAUDE_CONFIG_DIR;
  const seen: { plan?: string; append?: string; found?: string; read?: string } = {};

  beforeAll(async () => {
    home = tempDir();
    repoPath = fs.realpathSync(await demoRepo());
    execFileSync('git', ['-C', repoPath, 'branch', 'feat/started']);
    claudeDir = tempDir();
    process.env.CLAUDE_CONFIG_DIR = claudeDir;
    writeSession(path.join(claudeDir, 'projects'), repoPath, 'feed0001', [
      { type: 'custom-title', customTitle: 'Started the importer' },
      user('build the importer', new Date(Date.now() - 3_600_000).toISOString()),
      bash('git switch feat/started', new Date(Date.now() - 3_000_000).toISOString()),
      say('Parser done; the CSV export is what is left.', new Date(Date.now() - 2_000_000).toISOString()),
    ]);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { context: { sessionHistory: true } } }));
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
    const queryFn = ({ prompt, options }: { prompt: string; options?: Options }) => {
      const o = options!;
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'm', cwd: '', tools: [] });
        const p = String(prompt);
        const tools = (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
        if (p.startsWith('New goal')) {
          seen.plan = p;
          seen.append = (o.systemPrompt as { append: string }).append;
          seen.found = (await tools.find_sessions!.handler({ query: 'importer' }, {})).content[0]!.text;
          seen.read = (await tools.read_session!.handler({ id: 'feed0001' }, {})).content[0]!.text;
        }
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
  });

  afterAll(async () => {
    await h.fm.close();
    if (saved === undefined) delete process.env.CLAUDE_CONFIG_DIR;
    else process.env.CLAUDE_CONFIG_DIR = saved;
    rmrf(home);
    rmrf(claudeDir);
    rmrf(path.dirname(repoPath));
  });

  it('hands the lead the sessions that worked on the branch, and the tools to read them', async () => {
    await h.fm.submitGoal('on feat/started: finish the importer');
    await until(() => !!seen.read);
    expect(seen.plan).toContain('Earlier Claude sessions that worked on feat/started');
    expect(seen.plan).toContain('feed0001');
    expect(seen.append).toContain('# Earlier sessions');
    expect(seen.found).toContain('"Started the importer"');
    expect(seen.read).toContain('Parser done; the CSV export is what is left.');
    expect(h.fm.store.data.feed.some((f) => /Found 1 earlier session on feat\/started/.test(f.text))).toBe(true);
  });
});
