// goal.digest: "since you were away". A pure function over what the Foreman already stores (goals,
// tasks, decisions, the feed): no model call. Per goal, at most DIGEST_MAX_LINES lines, newest last,
// routine progress (task starts, CI runs, memory notes, agents talking to each other) left out.
//
// Sources, per line kind:
//   task_added         tasks of the goal created in the window
//   task_done          tasks done (updatedAt in the window), unless a merged / pr_merged line covers them
//   task_blocked       tasks blocked now whose last change is in the window
//   decision_waiting   decisions about the goal opened in the window and still open
//   decision_answered  decisions about the goal answered in the window
//   merged, pr_opened, pr_merged, pr_comments, goal_done   feed items tagged with the goal (kind merge / goal)
//   message            feed messages tagged with the goal from an agent to the user
// The feed keeps only the last few hundred items, so after a long absence the older feed-based
// lines are gone; the task and decision lines do not depend on it.
import type { Decision, DigestLine, Digest, FeedItem, Goal, GoalDigest, Task } from './protocol.js';
import { truncate } from './util/text.js';

export const DIGEST_MAX_LINES = 30;

export interface DigestData {
  goals: Goal[];
  tasks: Task[];
  decisions: Decision[];
  feed: FeedItem[];
}

export interface DigestOptions {
  since: number;
  until: number;
  /** one goal (always listed, even without lines); omitted: every goal with lines in the window */
  goalId?: string;
  nameOf?: (id: string) => string;
}

/** The task id a feed text is about ("t3", "PR #12 (t3)", ".../t3-tag-parser"). */
function taskIdIn(text: string): string | undefined {
  return /(?:^|[^\w])(t\d+)(?![\w])/.exec(text)?.[1] ?? /\/(t\d+)-/.exec(text)?.[1];
}

/** A feed item about a goal as a digest line, or undefined when it is routine. */
export function feedLine(f: FeedItem): Omit<DigestLine, 'ts'> | undefined {
  const by = f.agentId ? { agentId: f.agentId } : {};
  if (f.kind === 'message') {
    if (f.to !== 'user' || !f.agentId || f.agentId === 'user') return undefined;
    return { kind: 'message', text: truncate(f.text.replace(/\s+/g, ' '), 200), ...by };
  }
  if (f.kind === 'goal') {
    if (/^Goal complete/i.test(f.text)) return { kind: 'goal_done', text: f.text, ...by };
    return undefined;
  }
  if (f.kind !== 'merge') return undefined;
  const taskId = taskIdIn(f.text);
  const t = taskId ? { taskId } : {};
  let kind: DigestLine['kind'] | undefined;
  if (/^Merged /.test(f.text)) kind = 'merged';
  else if (/^PR #\d+ merged/.test(f.text)) kind = 'pr_merged';
  else if (/Opened a pull request|Updated the pull request|Pushed the review fixes|open the pull request yourself/.test(f.text)) kind = 'pr_opened';
  else if (/^PR #\d+ \(t\d+\): /.test(f.text)) kind = 'pr_comments';
  return kind ? { kind, text: f.text, ...t, ...by } : undefined;
}

function inWindow(ts: number | undefined, o: DigestOptions): ts is number {
  return typeof ts === 'number' && ts > o.since && ts <= o.until;
}

/** The digest lines of one goal, oldest first, at most DIGEST_MAX_LINES (the newest ones). */
export function goalLines(data: DigestData, goal: Goal, o: DigestOptions): DigestLine[] {
  const name = o.nameOf ?? ((id: string) => id);
  const lines: DigestLine[] = [];
  for (const f of data.feed) {
    if (f.goalId !== goal.id || !inWindow(f.ts, o)) continue;
    const l = feedLine(f);
    if (l) lines.push({ ts: f.ts, ...l });
  }
  const landed = new Set(lines.filter((l) => (l.kind === 'merged' || l.kind === 'pr_merged') && l.taskId).map((l) => l.taskId));
  for (const t of data.tasks) {
    if (t.goalId !== goal.id) continue;
    const who = t.assignee ? { agentId: t.assignee } : {};
    if (inWindow(t.createdAt, o)) lines.push({ ts: t.createdAt, kind: 'task_added', text: `${t.id} added: ${t.title}`, taskId: t.id, ...(t.createdBy !== 'user' ? { agentId: t.createdBy } : {}) });
    if (t.status === 'done' && inWindow(t.updatedAt, o) && !landed.has(t.id)) lines.push({ ts: t.updatedAt, kind: 'task_done', text: `${t.id} done: ${t.title}`, taskId: t.id, ...who });
    if (t.status === 'blocked' && inWindow(t.updatedAt, o)) lines.push({ ts: t.updatedAt, kind: 'task_blocked', text: `${t.id} blocked: ${t.blockedReason ?? t.title}`, taskId: t.id, ...who });
  }
  for (const d of data.decisions) {
    if (d.goalId !== goal.id) continue;
    const t = d.taskId ? { taskId: d.taskId } : {};
    if (d.status === 'open' && inWindow(d.createdAt, o)) lines.push({ ts: d.createdAt, kind: 'decision_waiting', text: `${name(d.agentId)} asks: ${truncate(d.question, 160)}`, agentId: d.agentId, ...t });
    if (d.status === 'answered' && inWindow(d.answer?.ts, o)) {
      const ans = [d.answer?.option, d.answer?.text].filter(Boolean).join(' - ');
      lines.push({ ts: d.answer!.ts, kind: 'decision_answered', text: `${truncate(d.question, 120)} -> ${truncate(ans, 80)}`, agentId: d.agentId, ...t });
    }
  }
  // stable: same ts keeps the order the sources were read in
  return lines
    .map((l, i) => ({ l, i }))
    .sort((a, b) => a.l.ts - b.l.ts || a.i - b.i)
    .map((x) => x.l)
    .slice(-DIGEST_MAX_LINES);
}

/** The digest: one entry per goal (the asked goal, or every goal with something to report). */
export function buildDigest(data: DigestData, o: DigestOptions): Digest {
  const goals: GoalDigest[] = [];
  for (const g of data.goals) {
    if (o.goalId && g.id !== o.goalId) continue;
    const lines = goalLines(data, g, o);
    if (!lines.length && !o.goalId) continue;
    goals.push({ goalId: g.id, text: g.text, status: g.status, progress: g.progress, lines });
  }
  return { since: o.since, until: o.until, goals };
}
