import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { claimRunFiles, homeRunFile, liveOwner, profileRunFile, releaseRunFiles } from '../src/runfile.js';
import { Store, tsPrefix } from '../src/store.js';
import { makeForeman, rmrf, tempDir } from './helpers.js';

const dirs: string[] = [];
afterEach(() => {
  for (const d of dirs.splice(0)) rmrf(d);
});

describe('persistence', () => {
  it('restores tasks, decisions, goals, memory, logs, messages and counters after a restart', async () => {
    const home = tempDir();
    dirs.push(home);
    const a = makeForeman(home, ['--backend', 'sim']);
    const goal = a.fm.createGoal('survive restarts');
    const t1 = a.fm.tasks.create({ title: 'first', createdBy: 'marlow', goalId: goal.id, assignee: 'kit' });
    a.fm.tasks.create({ title: 'second', deps: [t1.id], createdBy: 'marlow', goalId: goal.id });
    const d = a.fm.createDecision({ agentId: 'marlow', kind: 'question', question: 'Tabs or spaces?', options: ['Spaces', 'Tabs'] });
    a.fm.memory.write({ scope: 'shared', title: 'Plan: persistence', body: '# Plan\n\n- keep everything', author: 'marlow' });
    a.fm.memory.write({ scope: 'kit', title: 'Kit notes', body: 'private', author: 'kit' });
    a.fm.agentLog('kit', 'tool', 'Read src/cli.ts');
    a.fm.agentLog('kit', 'result', '81 lines');
    // in-memory tail == what is on disk (no duplicate of the first entry)
    expect(a.fm.store.logTail('kit').map((e) => e.text)).toEqual(['Read src/cli.ts', '81 lines']);
    a.fm.bus.send('kit', 'juniper', 'hello juniper');
    a.fm.setAgent('kit', { state: 'editing', station: 'desk', activity: 'editing src/cli.ts' });
    await a.fm.close();

    const raw = JSON.parse(fs.readFileSync(path.join(a.cfg.dataDir, 'state.json'), 'utf8'));
    expect(raw.version).toBe(1);

    const b = makeForeman(home, ['--backend', 'sim']);
    expect(b.fm.tasks.list().map((t) => t.title)).toEqual(['first', 'second']);
    expect(b.fm.tasks.get('t2')!.deps).toEqual(['t1']);
    expect(b.fm.decisions.get(d.id)!.status).toBe('open');
    expect(b.fm.currentGoal()!.text).toBe('survive restarts');
    expect(b.fm.memory.get('shared/plan-persistence')!.body).toContain('keep everything');
    expect(b.fm.memory.get('kit/kit-notes')!.scope).toBe('kit');
    expect(b.fm.store.logTail('kit').map((e) => e.text)).toEqual(['Read src/cli.ts', '81 lines']);
    expect(b.fm.bus.inbox('juniper').map((m) => m.text)).toEqual(['hello juniper']);
    expect(b.fm.agent('kit')!.state).toBe('editing');
    // counters continue
    expect(b.fm.tasks.create({ title: 'third', createdBy: 'marlow' }).id).toBe('t3');
    // the snapshot carries all of it
    const snap = b.fm.snapshot();
    expect(snap.type).toBe('snapshot');
    if (snap.type === 'snapshot') {
      expect(snap.tasks).toHaveLength(3);
      expect(snap.decisions.map((x) => x.id)).toContain(d.id);
      expect(snap.logs.find((l) => l.agentId === 'kit')!.entries).toHaveLength(2);
    }
    // an answer after the restart still works
    await b.fm.answerDecision(d.id, 'Spaces');
    expect(b.fm.decisions.get(d.id)!.answer!.option).toBe('Spaces');
    await b.fm.close();
  });

  it('writes state atomically (no temp files left) and survives a corrupt state file', () => {
    const dir = tempDir();
    dirs.push(dir);
    const s = new Store(dir, { debounceMs: 1 });
    s.data.counters.t = 5;
    s.flush();
    const leftovers = fs.readdirSync(dir).filter((f) => f.endsWith('.tmp'));
    expect(leftovers).toEqual([]);
    fs.writeFileSync(path.join(dir, 'state.json'), '{ this is not json');
    const s2 = new Store(dir);
    expect(s2.data.counters.t).toBeUndefined();
    expect(fs.readdirSync(dir).some((f) => f.startsWith('state.json.corrupt-'))).toBe(true);
  });

  it('memory bodies round-trip byte for byte across restarts', async () => {
    const home = tempDir();
    dirs.push(home);
    const a = makeForeman(home, ['--backend', 'sim']);
    const bodies = ['plain', 'ends with a newline\n', 'two newlines\n\n', '# Plan\n\n- a\n- b', ''];
    bodies.forEach((body, i) => a.fm.memory.write({ scope: 'shared', title: `note ${i}`, body, author: 'kit' }));
    const before = a.fm.memory.list();
    await a.fm.close();
    for (let round = 0; round < 2; round++) {
      const b = makeForeman(home, ['--backend', 'sim']);
      expect(b.fm.memory.list()).toEqual(before);
      await b.fm.close();
    }
  });

  it('rotates agent logs and rebuilds the tail from the end of the files only', () => {
    const dir = tempDir();
    dirs.push(dir);
    const s = new Store(dir, { debounceMs: 1, logMaxBytes: 20_000 });
    for (let i = 0; i < 600; i++) s.appendLog('kit', [{ ts: i, kind: 'text', text: `line ${i} ${'x'.repeat(60)}` }]);
    const logs = path.join(dir, 'logs');
    expect(fs.statSync(path.join(logs, 'kit.jsonl')).size).toBeLessThanOrEqual(20_000);
    expect(fs.existsSync(path.join(logs, 'kit.1.jsonl'))).toBe(true);
    expect(fs.readdirSync(logs).sort()).toEqual(['kit.1.jsonl', 'kit.jsonl']); // one old file kept
    // a fresh store (restart) reads the last 200 entries, across the rotation boundary if needed
    const s2 = new Store(dir);
    const tail = s2.logTail('kit');
    expect(tail).toHaveLength(200);
    expect(tail[tail.length - 1]!.ts).toBe(599);
    expect(tail[0]!.ts).toBe(400);
  });

  it('pages through the full agent log across the rotation (agent.logs.request)', () => {
    const dir = tempDir();
    dirs.push(dir);
    const s = new Store(dir, { debounceMs: 1, logMaxBytes: 20_000 });
    // two entries share every timestamp: a page never splits such a pair
    for (let i = 0; i < 300; i++) s.appendLog('kit', [{ ts: 1000 + i, kind: 'text', text: `a ${i} ${'x'.repeat(40)}` }, { ts: 1000 + i, kind: 'tool', text: `b ${i}` }]);
    expect(fs.existsSync(path.join(dir, 'logs', 'kit.1.jsonl'))).toBe(true);
    const first = s.readLog('kit', undefined, 7);
    expect(first.more).toBe(true);
    expect(first.entries.map((e) => e.ts)).toEqual([1296, 1296, 1297, 1297, 1298, 1298, 1299, 1299]); // 7 + the rest of 1296's pair
    expect(first.entries.at(-1)!.text).toBe('b 299'); // oldest first, file order kept
    // walk back to the start of what is stored; nothing repeats, nothing is lost
    const seen = [...first.entries];
    let before = first.entries[0]!.ts;
    for (let guard = 0; guard < 1000; guard++) {
      const page = s.readLog('kit', before, 50);
      expect(page.entries.every((e) => e.ts < before)).toBe(true);
      seen.unshift(...page.entries);
      if (!page.more) break;
      before = page.entries[0]!.ts;
    }
    const ts = seen.map((e) => e.ts);
    expect(ts).toEqual([...ts].sort((a, b) => a - b));
    expect(new Set(seen.map((e) => e.text)).size).toBe(seen.length);
    // everything both files hold, i.e. more than the in-memory tail
    const stored = ['kit.1.jsonl', 'kit.jsonl'].map((f) => fs.readFileSync(path.join(dir, 'logs', f), 'utf8').split('\n').filter(Boolean).length).reduce((a, b) => a + b);
    expect(seen).toHaveLength(stored);
    expect(seen.length).toBeGreaterThan(200);
    expect(s.readLog('nobody', undefined, 10)).toEqual({ entries: [], more: false });
  });

  it('reads a stored line\'s ts from its prefix, and pages lines written in another key order too', () => {
    expect(tsPrefix(Buffer.from('{"ts":1234,"kind":"text","text":"x"}'))).toBe(1234);
    expect(tsPrefix(Buffer.from('{"ts":7}'))).toBe(7);
    expect(tsPrefix(Buffer.from('{"kind":"text","ts":5,"text":"x"}'))).toBeUndefined(); // other order: parsed instead
    expect(tsPrefix(Buffer.from('{"ts":1.5,"kind":"text"}'))).toBeUndefined();
    expect(tsPrefix(Buffer.from('{"ts":'))).toBeUndefined();
    const dir = tempDir();
    dirs.push(dir);
    const s = new Store(dir, { debounceMs: 1 });
    s.appendLog('kit', [{ ts: 1, kind: 'text', text: 'one' }]);
    // a line whose keys are not ts-first (hand-edited or an older writer) still pages by its ts
    fs.appendFileSync(path.join(dir, 'logs', 'kit.jsonl'), '{"kind":"text","text":"two","ts":2}\n{"torn":\n');
    s.appendLog('kit', [{ ts: 3, kind: 'text', text: 'three' }]);
    expect(s.readLog('kit', undefined, 10).entries.map((e) => e.text)).toEqual(['one', 'two', 'three']);
    expect(s.readLog('kit', 3, 10).entries.map((e) => e.text)).toEqual(['one', 'two']);
    expect(s.readLog('kit', 2, 10)).toEqual({ entries: [{ ts: 1, kind: 'text', text: 'one' }], more: false });
  });

  it('run files: one per profile; a live owner keeps <home>/foreman.json; release hands it over', async () => {
    const home = tempDir();
    dirs.push(home);
    const net = await import('node:net');
    const server = net.createServer().listen(0, '127.0.0.1');
    await new Promise((r) => server.once('listening', r));
    const port = (server.address() as { port: number }).port;
    // another live Foreman (pid of this test process' parent is alive; its port answers)
    const other = { pid: process.ppid, port, host: '127.0.0.1', backend: 'claude', profile: 'claude', version: '0', startedAt: '2026-01-01T00:00:00.000Z' };
    fs.mkdirSync(path.join(home, 'claude'), { recursive: true });
    fs.writeFileSync(profileRunFile(path.join(home, 'claude')), JSON.stringify(other));
    fs.writeFileSync(homeRunFile(home), JSON.stringify(other));
    expect(await liveOwner(profileRunFile(path.join(home, 'claude')))).toMatchObject({ pid: process.ppid });
    // we start on profile "sim": our own profile file, but the primary stays the other Foreman
    const mine = { ...other, pid: process.pid, profile: 'sim', backend: 'sim', startedAt: new Date().toISOString() };
    await claimRunFiles(home, path.join(home, 'sim'), mine);
    expect(JSON.parse(fs.readFileSync(profileRunFile(path.join(home, 'sim')), 'utf8')).pid).toBe(process.pid);
    expect(JSON.parse(fs.readFileSync(homeRunFile(home), 'utf8')).pid).toBe(process.ppid);
    await releaseRunFiles(home, path.join(home, 'sim'));
    expect(fs.existsSync(profileRunFile(path.join(home, 'sim')))).toBe(false);
    expect(JSON.parse(fs.readFileSync(homeRunFile(home), 'utf8')).pid).toBe(process.ppid);
    // a dead owner (port closed) does not count: the next Foreman takes over and hands back on exit
    server.close();
    await new Promise((r) => setTimeout(r, 100));
    expect(await liveOwner(homeRunFile(home))).toBeUndefined();
    await claimRunFiles(home, path.join(home, 'sim'), mine);
    expect(JSON.parse(fs.readFileSync(homeRunFile(home), 'utf8')).pid).toBe(process.pid);
    await releaseRunFiles(home, path.join(home, 'sim'));
    expect(fs.existsSync(homeRunFile(home))).toBe(false);
  });

  it('picks up hand-edited memory files', async () => {
    const home = tempDir();
    dirs.push(home);
    const a = makeForeman(home, ['--backend', 'sim']);
    fs.writeFileSync(path.join(a.fm.memory.dir, 'shared', 'notes-from-alex.md'), '# Notes from Alex\n\nPrefer small PRs.\n');
    a.fm.memory.reload();
    const e = a.fm.memory.get('shared/notes-from-alex')!;
    expect(e.title).toBe('Notes from Alex');
    expect(e.body).toContain('small PRs');
    await a.fm.close();
  });
});
