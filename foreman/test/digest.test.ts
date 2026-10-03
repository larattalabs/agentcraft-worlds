// goal.digest is a pure function over goals, tasks, decisions and the feed; and the small unified
// diff goal.plan sends the lead.
import { describe, expect, it } from 'vitest';
import { buildDigest, DIGEST_MAX_LINES, feedLine, goalLines, type DigestData } from '../src/digest.js';
import type { Decision, FeedItem, Goal, Task } from '../src/protocol.js';
import { unifiedDiff } from '../src/util/udiff.js';

const T0 = 1_000_000;
const goal = (id: string, extra: Partial<Goal> = {}): Goal => ({ id, text: `goal ${id}`, progress: 0.5, status: 'active', createdAt: T0 - 10_000, updatedAt: T0, ...extra });
const task = (id: string, goalId: string, extra: Partial<Task> = {}): Task => ({ id, title: `task ${id}`, status: 'todo', deps: [], priority: 0, ci: 'unknown', createdBy: 'marlow', createdAt: T0 - 5_000, updatedAt: T0 - 5_000, goalId, ...extra });
const feed = (ts: number, kind: FeedItem['kind'], text: string, extra: Partial<FeedItem> = {}): FeedItem => ({ ts, kind, text, ...extra });
const dec = (id: string, extra: Partial<Decision>): Decision => ({ id, agentId: 'marlow', kind: 'question', question: `question ${id}`, options: [], status: 'open', createdAt: T0, ...extra });

function data(over: Partial<DigestData> = {}): DigestData {
  return { goals: [goal('g1'), goal('g2')], tasks: [], decisions: [], feed: [], ...over };
}

describe('goal.digest', () => {
  it('builds lines from tasks, decisions and goal-tagged feed items, oldest first', () => {
    const d = data({
      tasks: [
        task('t1', 'g1', { createdAt: T0 + 1_000, updatedAt: T0 + 1_000 }),
        task('t2', 'g1', { status: 'blocked', blockedReason: 'needs a token', updatedAt: T0 + 3_000, assignee: 'kit' }),
        task('t3', 'g1', { status: 'done', updatedAt: T0 + 4_000, assignee: 'wren' }),
      ],
      decisions: [
        dec('d1', { goalId: 'g1', createdAt: T0 + 2_000, question: 'Which color?' }),
        dec('d2', { goalId: 'g1', status: 'answered', createdAt: T0 - 50_000, answer: { option: 'Merge', ts: T0 + 5_000 }, kind: 'merge', question: 'Merge t9?', taskId: 't9' }),
      ],
      feed: [
        feed(T0 + 6_000, 'message', 'All done for now', { agentId: 'marlow', to: 'user', goalId: 'g1' }),
        feed(T0 + 7_000, 'goal', 'Goal complete: goal g1', { goalId: 'g1' }),
      ],
    });
    const lines = goalLines(d, d.goals[0]!, { since: T0, until: T0 + 10_000, nameOf: (id) => id.toUpperCase() });
    expect(lines.map((l) => l.kind)).toEqual(['task_added', 'decision_waiting', 'task_blocked', 'task_done', 'decision_answered', 'message', 'goal_done']);
    expect(lines[1]).toMatchObject({ text: 'MARLOW asks: Which color?', agentId: 'marlow' });
    expect(lines[2]).toMatchObject({ text: 't2 blocked: needs a token', taskId: 't2', agentId: 'kit' });
    expect(lines[4]).toMatchObject({ text: 'Merge t9? -> Merge', taskId: 't9' });
    expect(lines.every((l, i) => i === 0 || l.ts >= lines[i - 1]!.ts)).toBe(true);
  });

  it('only takes items strictly after since and up to until', () => {
    const d = data({
      tasks: [task('t1', 'g1', { createdAt: T0 }), task('t2', 'g1', { createdAt: T0 + 1 }), task('t3', 'g1', { createdAt: T0 + 100 }), task('t4', 'g1', { createdAt: T0 + 101 })],
    });
    const lines = goalLines(d, d.goals[0]!, { since: T0, until: T0 + 100 });
    expect(lines.map((l) => l.taskId)).toEqual(['t2', 't3']);
  });

  it('filters by goal: other goals\' items and untagged feed items are not in it', () => {
    const d = data({
      tasks: [task('t1', 'g1', { createdAt: T0 + 1 }), task('t2', 'g2', { createdAt: T0 + 2 })],
      feed: [feed(T0 + 3, 'message', 'for g2', { agentId: 'ines', to: 'user', goalId: 'g2' }), feed(T0 + 4, 'message', 'untagged', { agentId: 'marlow', to: 'user' })],
      decisions: [dec('d1', { goalId: 'g2', createdAt: T0 + 5 })],
    });
    const one = buildDigest(d, { since: T0, until: T0 + 10, goalId: 'g1' });
    expect(one.goals.map((g) => g.goalId)).toEqual(['g1']);
    expect(one.goals[0]!.lines.map((l) => l.taskId)).toEqual(['t1']);
    const all = buildDigest(d, { since: T0, until: T0 + 10 });
    expect(all.goals.map((g) => g.goalId)).toEqual(['g1', 'g2']);
    expect(all.goals[1]!.lines.map((l) => l.kind)).toEqual(['task_added', 'message', 'decision_waiting']);
    expect(all).toMatchObject({ since: T0, until: T0 + 10 });
  });

  it('lists the asked goal even without lines, and leaves quiet goals out otherwise', () => {
    const d = data();
    expect(buildDigest(d, { since: T0, until: T0 + 10 }).goals).toEqual([]);
    expect(buildDigest(d, { since: T0, until: T0 + 10, goalId: 'g2' }).goals).toEqual([{ goalId: 'g2', text: 'goal g2', status: 'active', progress: 0.5, lines: [] }]);
  });

  it('caps each goal at 30 lines and keeps the newest', () => {
    const tasks = Array.from({ length: 45 }, (_, i) => task(`t${i + 1}`, 'g1', { createdAt: T0 + i + 1 }));
    const lines = buildDigest(data({ tasks }), { since: T0, until: T0 + 1000 }).goals[0]!.lines;
    expect(DIGEST_MAX_LINES).toBe(30);
    expect(lines).toHaveLength(30);
    expect(lines[0]!.taskId).toBe('t16');
    expect(lines[29]!.taskId).toBe('t45');
  });

  it('drops routine lines: task starts, CI, memory, system, agent-to-agent chatter and the user\'s own messages', () => {
    const g = 'g1';
    const d = data({
      feed: [
        feed(T0 + 1, 'task', 'Kit started t2: Tag parser', { agentId: 'kit', goalId: g }),
        feed(T0 + 2, 'ci', 't2: tests pass (npm test)', { agentId: 'kit', goalId: g }),
        feed(T0 + 3, 'memory', 'Marlow wrote memory: Plan: x', { agentId: 'marlow', goalId: g }),
        feed(T0 + 4, 'system', 'something', { goalId: g }),
        feed(T0 + 5, 'message', 'Kit, take t2', { agentId: 'marlow', to: 'kit', goalId: g }),
        feed(T0 + 6, 'message', 'what about colors?', { agentId: 'user', to: 'marlow', goalId: g }),
        feed(T0 + 7, 'plan', 'Marlow planned the goal into 3 tasks', { agentId: 'marlow', goalId: g }),
        feed(T0 + 8, 'goal', 'New goal: x', { agentId: 'user', goalId: g }),
        feed(T0 + 9, 'merge', 'Rejected kit-t2 (branch kept for recovery)', { agentId: 'marlow', goalId: g }),
      ],
    });
    expect(goalLines(d, d.goals[0]!, { since: T0, until: T0 + 100 })).toEqual([]);
  });

  it('classifies merge and PR feed lines and does not repeat a merged task as done', () => {
    const g = 'g1';
    const d = data({
      tasks: [task('t2', g, { status: 'done', updatedAt: T0 + 2 }), task('t4', g, { status: 'done', updatedAt: T0 + 6 }), task('t5', g, { status: 'done', updatedAt: T0 + 7 })],
      feed: [
        feed(T0 + 1, 'merge', 'Merged agentcraft/kit/t2-tag-parser into main (abc1234, 2 files)', { agentId: 'marlow', goalId: g }),
        feed(T0 + 3, 'merge', 't4: Opened a pull request https://x/pr/12 (feat/t4 → dev); watching it until it is merged', { agentId: 'marlow', goalId: g }),
        feed(T0 + 4, 'merge', 'PR #12 (t4): 2 new comments. Marlow triages 2 items', { agentId: 'marlow', goalId: g }),
        feed(T0 + 5, 'merge', 'PR #12 merged: t4 "Tag filter" is done', { agentId: 'marlow', goalId: g }),
      ],
    });
    const lines = goalLines(d, d.goals[0]!, { since: T0, until: T0 + 100 });
    expect(lines.map((l) => [l.kind, l.taskId])).toEqual([
      ['merged', 't2'],
      ['pr_opened', 't4'],
      ['pr_comments', 't4'],
      ['pr_merged', 't4'],
      ['task_done', 't5'],
    ]);
  });

  it('feedLine: messages count only from an agent to the user', () => {
    expect(feedLine(feed(1, 'message', 'hi', { agentId: 'marlow', to: 'user' }))).toEqual({ kind: 'message', text: 'hi', agentId: 'marlow' });
    expect(feedLine(feed(1, 'message', 'hi', { agentId: 'marlow', to: 'all' }))).toBeUndefined();
    expect(feedLine(feed(1, 'user', 'hi', { to: 'marlow' }))).toBeUndefined();
  });
});

describe('unifiedDiff', () => {
  it('is empty for equal texts', () => {
    expect(unifiedDiff('a\nb\n', 'a\nb')).toBe('');
  });

  it('shows a change with 3 lines of context and hunk headers', () => {
    const a = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10'].join('\n');
    const b = ['1', '2', '3', '4', '5', 'SIX', '7', '8', '9', '10'].join('\n');
    expect(unifiedDiff(a, b, { from: 'old', to: 'new' })).toBe(['--- old', '+++ new', '@@ -3,7 +3,7 @@', ' 3', ' 4', ' 5', '-6', '+SIX', ' 7', ' 8', ' 9'].join('\n'));
  });

  it('splits far-apart changes into hunks and merges close ones', () => {
    const a = Array.from({ length: 20 }, (_, i) => `l${i + 1}`);
    const b = [...a];
    b[1] = 'X2';
    b[17] = 'X18';
    const d = unifiedDiff(a.join('\n'), b.join('\n'), { context: 1 });
    expect(d.split('\n').filter((l) => l.startsWith('@@'))).toEqual(['@@ -1,3 +1,3 @@', '@@ -17,3 +17,3 @@']);
    const c = [...a];
    c[1] = 'X2';
    c[3] = 'X4';
    expect(unifiedDiff(a.join('\n'), c.join('\n'), { context: 1 }).split('\n').filter((l) => l.startsWith('@@'))).toEqual(['@@ -1,5 +1,5 @@']);
  });

  it('handles creating from nothing and added / removed lines', () => {
    expect(unifiedDiff('', 'a\nb')).toBe(['--- before', '+++ after', '@@ -0,0 +1,2 @@', '+a', '+b'].join('\n'));
    expect(unifiedDiff('a\nb\nc', 'a\nc\nd')).toBe(['--- before', '+++ after', '@@ -1,3 +1,3 @@', ' a', '-b', ' c', '+d'].join('\n'));
  });
});
