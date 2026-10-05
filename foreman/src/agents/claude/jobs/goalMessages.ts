// Claude backend, goal messages (Goals tab): goal.message runs as a turn of the lead's session for that
// goal, built from the goal's unread messages when it starts; its reply goes to the goal's thread.
// A restart, stop or release before the lead answered (also while the turn is paused, held for the
// usage limit or waiting for its automatic retry) offers the messages again.
import type { Goal } from '../../../protocol.js';
import { truncate } from '../../../util/text.js';
import { type TurnStats } from '../stream.js';
import { userName } from '../../../user.js';
import { FollowupJobs } from './followup.js';
import { type Job, type Inflight } from '../core.js';

export abstract class GoalMessageJobs extends FollowupJobs {
  /** goal.message: a turn of the lead's session for the goal (queued behind its other work). */
  onGoalMessage(goal: Goal, leadId: string): void {
    if (this.isStopped(leadId)) {
      // stays unread: queued again when the lead is resumed
      this.fm.bus.send(leadId, 'user', `(${this.fm.nameOf(leadId)} is off shift - bring them back with Spawn on their agent card (hub Team tab), or /resume @${leadId}; your message about ${goal.id} is queued.)`, { goalId: goal.id });
      return;
    }
    this.enqueueGoalMessage(leadId, goal.id);
  }

  /** One goal-message job per (lead, goal) waiting at a time: it takes every unread message when it starts. */
  protected enqueueGoalMessage(leadId: string, goalId: string): void {
    if (this.hasQueued(leadId, (j) => !!j.pendingGoalMessage && j.goalId === goalId)) return;
    this.enqueue({ kind: 'followup', agentId: leadId, goalId, sessionKey: `${leadId}:${goalId}`, prompt: '', pendingGoalMessage: true });
  }

  /** Unread goal messages to a lead (after a restart, a resume, an assignment): queue their turns. */
  protected queueGoalMessages(leadId: string): void {
    if (this.stopping || this.isStopped(leadId) || !this.fm.isLead(leadId)) return;
    for (const goalId of new Set(this.fm.bus.goalInbox(leadId).map((m) => m.goalId!))) this.enqueueGoalMessage(leadId, goalId);
  }

  /** Build a goal-message job's prompt from the goal's unread messages (marked read). False: none left. */
  protected override fillGoalMessage(job: Job): boolean {
    const goal = job.goalId ? this.fm.goal(job.goalId) : undefined;
    // (messages a held goal-message turn of this lead answers when it runs again: not asked twice)
    const held = new Set([...(this.queues.get(job.agentId) ?? []), this.pausedJobs.get(job.agentId), this.delayed.get(job.agentId)?.job].flatMap((j) => (j?.goalReply ? (j.messageIds ?? []) : [])));
    const msgs = goal ? this.fm.bus.goalInbox(job.agentId, goal.id).filter((m) => !held.has(m.id)) : [];
    if (!goal || !msgs.length) return false;
    this.fm.bus.markRead(job.agentId, msgs.map((m) => m.id));
    const state =
      goal.status === 'done'
        ? ` This goal is done: answer questions about it; if ${userName()} asks for more work on it, create tasks for it (that re-opens it).`
        : goal.status === 'cancelled' || goal.status === 'failed'
          ? ` This goal is ${goal.status}: answer questions about it; create tasks for it only if ${userName()} clearly asks for the work (that re-opens it).`
          : goal.status === 'planning'
            ? ' You are still planning this goal: take this into the plan.'
            : '';
    job.prompt = `Message from ${userName()} about goal ${goal.id} "${truncate(goal.text.replace(/\s+/g, ' '), 200)}":\n${msgs.map((m) => m.text).join('\n\n')}\n\n${state ? `${state.trim()}\n` : ''}Answer with send_message(to "user") (short; it is shown in the goal's thread) and act on it if needed: create, update or cancel this goal's tasks.`;
    job.pendingGoalMessage = false;
    job.goalReply = true;
    job.startedAt = Date.now();
    job.messageIds = msgs.map((m) => m.id);
    // persisted before the turn's first await: a restart from here on asks the lead again (recover)
    this.st.inflight[job.agentId] = { ...this.inflightOf(job), startedAt: job.startedAt };
    this.fm.store.markDirty();
    return true;
  }

  /** What a restart needs to know about a job that is running (ClaudeState.inflight, without its start time). */
  protected override inflightOf(job: Job): Omit<Inflight, 'startedAt'> {
    return {
      kind: job.kind,
      sessionKey: job.sessionKey,
      ...(job.taskId ? { taskId: job.taskId } : {}),
      ...(job.goalId ? { goalId: job.goalId } : {}),
      ...(job.goalReply ? { goalReply: true as const, ...(job.messageIds?.length ? { messageIds: job.messageIds } : {}) } : {}),
    };
  }

  /** A goal-message turn ended: when the lead sent the user nothing, its final text is the reply. */
  protected override replyToGoal(lead: string, job: Job, stats: TurnStats | undefined): void {
    const since = job.startedAt ?? 0;
    const replied = this.fm.store.data.messages.some((m) => m.from === lead && m.to === 'user' && m.ts >= since);
    const text = stats?.resultText?.trim();
    if (!replied && text) this.fm.bus.send(lead, 'user', truncate(text, 1500), { goalId: job.goalId });
  }

  /**
   * A goal-message turn the restart interrupted before the lead answered (it sent the user nothing
   * since the turn started): its messages are unread again, so the goal's next goal-message turn
   * (queueGoalMessages, after recover) asks the lead again with the full text. True when re-queued
   * that way; false when the lead already answered (the turn resumes as usual).
   */
  protected override requeueGoalMessage(agentId: string, inf: Inflight, handOver = false): boolean {
    const replied = this.fm.store.data.messages.some((m) => m.from === agentId && m.to === 'user' && m.ts >= inf.startedAt && (!inf.goalId || m.goalId === inf.goalId));
    if (replied) return false;
    if (inf.messageIds?.length) this.fm.bus.markUnread(agentId, inf.messageIds);
    // this lead no longer leads the goal (released while the Foreman was down): its lead now gets them
    const goal = inf.goalId ? this.fm.goal(inf.goalId) : undefined;
    const now = goal ? this.fm.goalLead(goal) : undefined;
    if (handOver && now && now !== agentId && inf.messageIds?.length) {
      for (const m of this.fm.store.data.messages) if (inf.messageIds.includes(m.id) && m.to === agentId) m.to = now;
      this.fm.store.markDirty();
    }
    this.fm.log.info(`${agentId}'s answer about ${inf.goalId ?? 'a goal'} was interrupted; the message${inf.messageIds?.length === 1 ? '' : 's'} will be asked again`);
    return true;
  }

  /**
   * A goal-message turn that ended unfinished and runs again later (paused, held for the usage limit,
   * waiting for its automatic retry) lives in memory only, and its inflight record is gone. When the
   * lead has not answered yet, its messages are unread again meanwhile, so a restart, stop or release
   * before it runs still asks them (queueGoalMessages / the handover); the job itself is unchanged and
   * marks them read again when it starts (runJob). Other goal-message turns leave them out meanwhile
   * (fillGoalMessage).
   */
  protected override offerHeldGoalMessages(job: Job): void {
    if (!job.goalReply || !job.messageIds?.length) return;
    if (this.requeueGoalMessage(job.agentId, { ...this.inflightOf(job), startedAt: job.startedAt ?? 0 })) this.fm.store.markDirty();
  }

  /**
   * The lead is stopped or released: the goal messages its running, queued, paused or delayed
   * goal-message turn had taken (and not answered) are unread again, so they are not lost with the turn.
   */
  protected releaseGoalMessages(agentId: string): void {
    const recs: Inflight[] = [...(this.queues.get(agentId) ?? []), this.pausedJobs.get(agentId), this.delayed.get(agentId)?.job].filter((j): j is Job => !!j?.goalReply).map((j) => ({ ...this.inflightOf(j), startedAt: j.startedAt ?? 0 }));
    const inf = this.st.inflight[agentId];
    if (inf?.goalReply) recs.push(inf);
    for (const rec of recs) this.requeueGoalMessage(agentId, rec);
  }

  /** goal.cancel: the lead stops planning / reviewing / triaging that goal (its goal messages still run). */
  onGoalCancel(goal: Goal): void {
    const mine = (j: Job) => j.goalId === goal.id && (j.kind === 'plan' || j.kind === 'review' || j.kind === 'triage');
    for (const [id, r] of this.running) if (this.fm.isLead(id) && mine(r.job)) this.abortTurn(r, 'cancel');
    for (const [id, q] of this.queues) if (this.fm.isLead(id)) this.queues.set(id, q.filter((j) => !mine(j)));
    for (const [id, j] of this.pausedJobs) if (mine(j)) this.pausedJobs.delete(id);
    this.dropDelayed((id, j) => this.fm.isLead(id) && mine(j));
    for (const [id, inf] of Object.entries(this.st.inflight)) if (inf.goalId === goal.id && (inf.kind === 'plan' || inf.kind === 'review' || inf.kind === 'triage') && !this.running.has(id)) delete this.st.inflight[id];
    this.fm.store.markDirty();
    this.tick();
  }
}
