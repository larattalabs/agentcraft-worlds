// Claude backend: after a turn. What the job's end means for its goal or task (plan -> active, a
// worker's task -> CI and review, blocked, nudged), and the one automatic retry of a turn that failed
// for a passing reason.
import { truncate } from '../../util/text.js';
import { classifyFailure } from './failures.js';
import { type TurnStats } from './stream.js';
import { SessionsLayer } from './sessions.js';
import { type Job, clock } from './core.js';

export abstract class OutcomesLayer extends SessionsLayer {
  protected failure(stats: TurnStats | undefined): string {
    return truncate(stats?.subtype && stats.subtype !== 'success' ? stats.subtype.replace(/^error_/, '').replace(/_/g, ' ') : (stats?.errors[0] ?? 'error'), 36);
  }

  /** Which automatic-retry budget a job uses (one retry per worker task, lead plan, lead review). */
  protected retryKey(job: Job): string {
    if (job.kind === 'plan') return `plan:${job.goalId}`;
    if (job.kind === 'review') return `review:${job.taskId}`;
    if (!this.fm.isLead(job.agentId) && job.taskId) return `work:${job.taskId}`;
    return `${job.kind}:${job.agentId}:${job.taskId ?? job.goalId ?? ''}`;
  }

  /**
   * A turn failed for a passing reason (network, sleep, an overloaded API, its step or time limit,
   * a too-long prompt), or a lead's plan / review failed at all: run the same job once more, after
   * a pause (2-5 min), resuming its session (a too-long prompt: a fresh session with the original
   * prompt). Never for auth, usage limits, the per-turn budget or billing. Returns false when no
   * retry is due (the caller blocks / fails as before). The task or goal stays where it is meanwhile,
   * so a restart picks it up the normal way.
   */
  protected autoRetry(job: Job, stats: TurnStats | undefined, what: string): boolean {
    if (this.stopping) return false;
    const kind = classifyFailure(stats, { timedOut: stats?.subtype === 'timeout' });
    const lead = this.fm.isLead(job.agentId);
    const retryable = kind === 'transient' || kind === 'context' || (kind === 'fatal' && lead && (job.kind === 'plan' || job.kind === 'review'));
    if (!retryable) return false;
    const key = this.retryKey(job);
    const used = this.retried.get(key) ?? 0;
    if (used >= 1) return false;
    this.retried.set(key, used + 1);
    const why = this.failure(stats);
    const hasSession = !!this.fm.store.data.sessions[job.sessionKey]?.sessionId;
    const fresh = kind === 'context' || !hasSession;
    const next: Job = fresh
      ? { ...job, fresh: true, resumed: true, prompt: `${kind === 'context' ? 'Your previous session for this job ran out of room (the prompt grew too long), so this is a fresh one. Re-check where things stand (the task board; workers: git status and git log in your worktree) before you continue.\n\n' : ''}${job.prompt}` }
      : { ...job, fresh: false, resumed: true, prompt: `Your last turn ended early (${why}). Re-check where you were (${lead ? 'the task board' : 'your worktree and the task board'}) and continue your current job.` };
    const delay = this.opts.transientRetryMs ?? 2 * 60_000 + Math.floor(Math.random() * 3 * 60_000);
    const at = Date.now() + delay;
    const name = this.fm.nameOf(job.agentId);
    this.fm.setAgent(job.agentId, { state: 'blocked', activity: `${what}: ${why} - retrying ${clock(at)}` });
    this.fm.agentLog(job.agentId, 'error', `${what} ended early (${why}); one automatic retry at ${clock(at)}`);
    this.fm.bus.feed('system', `${name}'s ${what} ended early (${why}); trying again at ${clock(at)}`, { agentId: job.agentId, ...(job.taskId ? { taskId: job.taskId } : {}), ...(job.goalId ? { goalId: job.goalId } : {}) });
    const prev = this.delayed.get(job.agentId);
    if (prev) clearTimeout(prev.timer);
    const timer = setTimeout(() => {
      this.delayed.delete(job.agentId);
      if (this.stopping || !this.stillDue(next)) {
        this.fm.log.info(`automatic retry of ${job.agentId}'s ${job.kind} dropped (no longer due)`);
        return;
      }
      if (this.fm.agent(job.agentId)?.paused) {
        this.pausedJobs.set(job.agentId, next);
        return;
      }
      this.enqueue(next);
    }, delay);
    timer.unref?.();
    this.delayed.set(job.agentId, { job: next, timer, at });
    return true;
  }

  /** A delayed retry still makes sense: same agent on the same task / goal, in the same state. */
  protected stillDue(job: Job): boolean {
    if (this.isStopped(job.agentId) || !this.fm.agent(job.agentId)) return false;
    if (job.kind === 'plan') {
      const g = job.goalId ? this.fm.goal(job.goalId) : undefined;
      return !!g && g.status === 'planning' && this.fm.leadOf(g) === job.agentId && !this.fm.tasks.forGoal(g.id).length;
    }
    if (job.kind === 'review') {
      const t = job.taskId ? this.fm.tasks.get(job.taskId) : undefined;
      return !!t && t.status === 'review' && this.fm.leadOfTask(t) === job.agentId && !this.fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === t.id);
    }
    if (job.taskId && !this.fm.isLead(job.agentId)) {
      const t = this.fm.tasks.get(job.taskId);
      return !!t && t.status === 'doing' && t.assignee === job.agentId;
    }
    return true;
  }

  /** Drop delayed retries matching `pred` (cancel, stop, reassign, goal.cancel). */
  protected dropDelayed(pred: (agentId: string, job: Job) => boolean): void {
    for (const [id, d] of [...this.delayed]) {
      if (!pred(id, d.job)) continue;
      clearTimeout(d.timer);
      this.delayed.delete(id);
    }
  }

  protected override async afterTurn(job: Job, stats: TurnStats | undefined): Promise<void> {
    const failed = !stats || stats.isError;
    if (!failed) this.retried.delete(this.retryKey(job));
    if (this.fm.isLead(job.agentId)) {
      const lead = job.agentId;
      // a lead's follow-up (a goal message, an answer, a user message) that failed for a passing
      // reason: one automatic resume, like a worker's turn (plans and reviews: below)
      if (failed && job.kind === 'followup' && this.autoRetry(job, stats, 'turn')) return;
      this.fm.setAgent(lead, failed ? { state: 'error', station: 'meeting', activity: `turn failed: ${this.failure(stats)}` } : { state: 'idle', station: 'meeting', activity: 'watching the task wall' });
      if (job.goalReply && job.goalId && !failed) this.replyToGoal(lead, job, stats);
      // any lead turn for a goal that is still planning (plan, or a plan resumed after a
      // restart / an answer) settles the goal: tasks -> active
      const goal = job.goalId ? this.fm.goal(job.goalId) : undefined;
      if (goal && goal.status === 'planning') {
        const n = this.fm.tasks.forGoal(goal.id).length;
        if (n > 0) this.promoteGoal(goal, 'planned');
        else if (failed && job.kind === 'plan' && this.autoRetry(job, stats, 'planning turn')) {
          // tried once more after a pause; the goal stays planning meanwhile
        } else if (failed) {
          this.fm.setGoal(goal.id, { status: 'failed' });
          this.fm.bus.feed('error', `${this.fm.nameOf(lead)}'s planning turn ended without tasks${stats?.errors.length ? `: ${stats.errors.join('; ')}` : ''}`, { agentId: lead, goalId: goal.id });
        } else if (job.kind === 'plan') {
          // nothing to do (e.g. the user said "ignore it"): close the goal instead of leaving it
          // "active" at 0% forever; a task the lead adds to it later makes it active again
          this.fm.setGoal(goal.id, { status: 'cancelled', progress: 0 });
          this.fm.bus.feed('goal', `${this.fm.nameOf(lead)} planned no tasks: goal closed (${truncate(goal.text, 80)})`, { agentId: lead, goalId: goal.id });
        }
      }
      if (job.kind === 'triage' && job.taskId && this.prs.pendingItems(job.taskId).length) this.prs.triageTurnEnded(job.taskId);
      if (job.kind === 'review' && job.taskId) {
        const t = this.fm.tasks.get(job.taskId);
        const hasDecision = this.fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === job.taskId);
        if (t && t.status === 'review' && !hasDecision) {
          // a failed review is tried once more before the merge goes to the user without a verdict
          if (failed && this.autoRetry(job, stats, `review of ${t.id}`)) return;
          // lead gave no verdict: still surface the merge to the user (never auto-merge)
          this.openMergeDecision(t, `${this.fm.nameOf(lead)}'s review: ${truncate(stats?.resultText ?? '(no verdict)', 300)}`);
        }
      }
      return;
    }
    // worker
    const t = job.taskId ? this.fm.tasks.get(job.taskId) : undefined;
    if (!t) {
      this.fm.setAgent(job.agentId, failed ? { state: 'error', station: 'desk', activity: `turn failed: ${this.failure(stats)}` } : { state: 'idle', station: 'lounge', activity: 'idle' });
      return;
    }
    if (t.status === 'review') {
      await this.afterWorkerDone(t.id);
      return;
    }
    if (t.status === 'doing') {
      // a passing failure (network, sleep, overload, step / time limit): one automatic resume first
      if (failed && this.autoRetry(job, stats, `turn on ${t.id}`)) return;
      const nudges = job.nudges ?? 0;
      if (!failed && nudges < 1) {
        this.enqueue({ ...job, kind: 'followup', fresh: false, nudges: nudges + 1, prompt: `You ended your turn but ${t.id} is still "doing". If the work is complete, call update_task("${t.id}", status "review", summary). If you are stuck, call update_task with status "blocked" and blocked_reason. Otherwise continue.` });
        return;
      }
      const wt = t.worktree && t.repoId ? this.fm.repos.findWorktree(t.repoId, t.worktree) : undefined;
      if (wt) await this.fm.repos.refresh(t.repoId!);
      if (!failed && wt && wt.files > 0) {
        this.fm.tasks.setStatus(t.id, 'review', { summary: truncate(stats?.resultText ?? 'work complete', 400) });
        await this.afterWorkerDone(t.id);
      } else {
        this.fm.tasks.setStatus(t.id, 'blocked', { reason: failed ? `session ended: ${stats?.subtype ?? stats?.errors.join('; ') ?? 'error'}` : 'worker stopped without changes', force: true });
        // a failed turn is an error (red); a worker that gave up is blocked
        this.fm.setAgent(job.agentId, failed ? { state: 'error', station: 'desk', activity: `${t.id}: ${this.failure(stats)}` } : { state: 'blocked', station: 'desk', activity: `${t.id} blocked` });
        this.fm.bus.send(job.agentId, this.fm.leadOfTask(t), `${t.id} is blocked: ${this.fm.tasks.get(t.id)?.blockedReason}`, { taskId: t.id });
        this.fm.notify('warn', `${this.fm.nameOf(job.agentId)}: ${t.id} ${failed ? 'failed' : 'is blocked'} (${this.fm.tasks.get(t.id)?.blockedReason ?? ''}) - when ready: Retry on the task's card (task wall, or hub Goals > Tasks), or /task ${t.id} retry`);
      }
      return;
    }
    if (t.status === 'blocked') {
      this.fm.setAgent(job.agentId, { state: 'blocked', station: 'desk', activity: `${t.id} blocked` });
      this.fm.notify('warn', `${this.fm.nameOf(job.agentId)} is blocked on ${t.id}: ${t.blockedReason ?? ''}`);
      return;
    }
    this.fm.setAgent(job.agentId, { state: 'idle', station: 'lounge', activity: 'idle' });
  }
}
