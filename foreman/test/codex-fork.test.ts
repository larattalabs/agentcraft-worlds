// The fork's protections on Codex turns (docs/FORK.md "Upstream sync 2026-10"), with the scripted fake
// `codex app-server` (fixtures/fake-codex.mjs): the user's Claude Code deny rules apply to Codex
// commands, a command Codex runs without asking that touches the Foreman's own files interrupts the
// turn, and Codex agents never get the Claude credentials.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { CodexEngine } from '../src/agents/codex/engine.js';
import { TeamBackend } from '../src/agents/team.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const FAKE = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/, '$1')), 'fixtures', 'fake-codex.mjs');

type LogLine = { pid: number; anthropicKey?: boolean; method?: string; cmd?: string; silentCmd?: string; decision?: string; cmdIn?: string; moveTo?: string; grantOnly?: string; stdinAfter?: string; decisions?: string[] };
const readLog = (file: string): LogLine[] => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l) as LogLine) : []);

let h: Harness;
let home: string;
let repoPath: string;
let logFile: string;
const savedKey = process.env.ANTHROPIC_API_KEY;

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  const scenario = {
    turns: [
      {
        role: 'lead',
        match: '^New goal',
        steps: [
          { tool: 'create_task', args: { title: 'Peek', description: 'look around', assignee: 'kit' } },
          { tool: 'create_task', args: { title: 'Publish', description: 'publish it', assignee: 'kit', deps: ['t1'] } },
          { tool: 'create_task', args: { title: 'Ship', description: 'ship it', assignee: 'kit', deps: ['t2'] } },
          { tool: 'create_task', args: { title: 'Ship quietly', description: 'ship it again', assignee: 'kit', deps: ['t3'] } },
          { say: 'Planned.' },
        ],
      },
      { role: 'worker', match: 'Your task: t3', steps: [{ cmd: 'npm publish --tag next' }, { wait: 4000 }, { say: 'shipped' }] },
      { role: 'worker', match: 'Your task: t4', steps: [{ silentCmd: 'sh -c "npm publish --tag next"' }, { wait: 4000 }, { say: 'shipped' }] },
      { role: 'worker', match: 'Your task: t1', steps: [{ silentCmd: `cat ${path.join(home, 'config.json')}`, output: '{"secret": true}' }, { wait: 4000 }, { say: 'read it' }] },
      {
        role: 'worker',
        match: 'Your task: t2',
        steps: [
          { moveTo: path.join(home, 'config.json') },
          { grantOnly: home },
          { stdinAfter: 'git status' },
          // last: its start already interrupts the turn (the Foreman-private guard)
          { cmdIn: { cmd: 'cat config.json', cwd: home } },
          { say: 'tried' },
        ],
      },
    ],
  };
  const scenarioFile = path.join(home, 'scenario.json');
  logFile = path.join(home, 'codex-log.jsonl');
  fs.writeFileSync(scenarioFile, JSON.stringify(scenario));
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { permissions: { deny: ['Bash(npm publish:*)'] } } }));
  process.env.FAKE_CODEX_SCENARIO = scenarioFile;
  process.env.FAKE_CODEX_LOG = logFile;
  process.env.ANTHROPIC_API_KEY = 'sk-ant-test-not-for-codex';
  h = makeForeman(home, ['--backend', 'codex', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
  const codex = new CodexEngine(h.fm, h.cfg.codex, { bin: process.execPath, args: [FAKE] });
  await h.fm.start(new TeamBackend(h.fm, h.cfg.claude, { name: 'codex', engines: { lead: codex, worker: codex }, transientRetryMs: 600_000 }));
});

afterAll(async () => {
  await h.fm.close();
  delete process.env.FAKE_CODEX_SCENARIO;
  delete process.env.FAKE_CODEX_LOG;
  if (savedKey === undefined) delete process.env.ANTHROPIC_API_KEY;
  else process.env.ANTHROPIC_API_KEY = savedKey;
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('the fork protections on Codex turns', () => {
  it('interrupts a turn whose unasked command reads the Foreman home; never passes the Claude key', async () => {
    await h.fm.submitGoal('Look around, then publish');
    const logs = () => {
      h.fm.flushLogs();
      return JSON.stringify(h.events.filter((e) => e.type === 'agent.log'));
    };
    await until(() => logs().includes('blocked (ran without asking)'), 30_000);
    await until(() => readLog(logFile).some((l) => l.method === 'turn/interrupt'), 10_000);
    expect(h.fm.decisions.open().some((d) => d.kind === 'permission')).toBe(false);
    // the agents' app-server processes (every one that started a turn) had no Claude key
    const log = readLog(logFile);
    const turnPids = new Set(log.filter((l) => l.method === 'turn/start').map((l) => l.pid));
    expect(turnPids.size).toBeGreaterThan(0);
    for (const pid of turnPids) expect(log.find((l) => l.pid === pid && l.anthropicKey !== undefined)?.anthropicKey).toBe(false);
  });

  it('applies the user\'s deny rules to Codex commands, and checks every approval where it applies', async () => {
    // t1 is blocked or retrying after the interrupted turn: let t2 run anyway
    await until(() => !!h.fm.tasks.get('t1'), 10_000);
    h.fm.tasks.update('t2', { deps: [] });
    h.fm.backend?.onTaskAction?.(h.fm.tasks.get('t2')!, 'prioritize');
    await until(() => readLog(logFile).some((l) => l.cmdIn), 50_000);
    const log = readLog(logFile);
    // judged where it runs: `cat config.json` in the Foreman home is the Foreman's config
    expect(log.find((l) => l.cmdIn)?.decision).toBe('decline');
    // a rename's destination is checked, not only its source
    expect(log.find((l) => l.moveTo)?.decision).toBe('decline');
    // a patch nobody described is declined, whatever root it asks for
    expect(log.find((l) => l.grantOnly)?.decision).toBe('decline');
    // input only while the approved command runs
    expect(log.find((l) => l.stdinAfter)?.decisions).toEqual(['accept', 'accept', 'decline']);
    expect(h.fm.decisions.open().some((d) => d.kind === 'permission')).toBe(false);
    // t3: a command that asks and that a deny rule matches is declined (and interrupts the turn as it starts)
    h.fm.tasks.update('t3', { deps: [] });
    h.fm.backend?.onTaskAction?.(h.fm.tasks.get('t3')!, 'prioritize');
    await until(() => readLog(logFile).some((l) => l.cmd === 'npm publish --tag next'), 60_000);
    expect(readLog(logFile).find((l) => l.cmd === 'npm publish --tag next')!.decision).toBe('decline');
    expect(h.fm.decisions.open().some((d) => d.kind === 'permission')).toBe(false);
    h.fm.flushLogs();
    expect(JSON.stringify(h.events.filter((e) => e.type === 'agent.log'))).toContain('denied by your permission rule Bash(npm publish:*)');
  }, 150_000);

  it('interrupts a command Codex ran without asking when a deny rule matches it (also inside sh -c)', async () => {
    // t1-t3 are done with (each retry would wait 10 minutes): off the board, so kit takes t4
    for (const id of ['t1', 't2', 't3']) {
      h.fm.tasks.setStatus(id, 'cancelled', { force: true });
      h.fm.backend?.onTaskAction?.(h.fm.tasks.get(id)!, 'cancel');
    }
    h.fm.tasks.update('t4', { deps: [] });
    h.fm.backend?.onTaskAction?.(h.fm.tasks.get('t4')!, 'prioritize');
    const texts = () => {
      h.fm.flushLogs();
      return JSON.stringify(h.events.filter((e) => e.type === 'agent.log'));
    };
    await until(() => texts().includes('blocked (ran without asking)') && /sh -c [^,]*npm publish --tag next[^,]*denied by your permission rule Bash\(npm publish:\*\)/.test(texts()), 60_000);
    await until(() => readLog(logFile).some((l) => l.silentCmd?.startsWith('sh -c')), 10_000);
  }, 120_000);
});
