// The team, follow-up jobs: an agent's session resumed with new input (a user message, an
// answer after a restart), and the PR side: the lead triages a pull request's new comments, reviews
// and checks (prwatch.ts) and fold-ins send the task back to its worker.
import type { Decision, Task } from '../../../protocol.js';
import { renderDiffText } from '../../../diff.js';
import { truncate } from '../../../util/text.js';
import { triagePrompt } from '../../prompts.js';
import { DEFAULT_AUTO_SEVERITIES, DEFAULT_MAX_ROUNDS, type TriageItem } from '../../../prwatch.js';
import { userName } from '../../../user.js';
import { HOME_LEAD } from '../../../leads.js';
import { ReviewJobs } from './review.js';
import { goalReplyOf } from '../core.js';

export abstract class FollowupJobs extends ReviewJobs {
  /** `note`: extra instructions for the agent only (not shown in the feed). */
  onUserMessage(to: string, text: string, note?: string): void {
    // plain messages go to the lead of the current goal; a lead that leads no building any more: marlow
    let id = to === 'all' ? this.fm.leadOf(this.fm.currentGoal()) : to;
    if (this.fm.isLead(id) && !this.fm.leads.onDuty(id)) id = HOME_LEAD;
    const a = this.fm.agent(id);
    if (!a) return;
    if (this.isStopped(id)) {
      // stays unread; delivered when the user resumes the agent (deliverPending)
      this.fm.bus.send(id, 'user', `(${this.fm.nameOf(id)} is off shift - bring them back with Spawn on their agent card (hub Team tab), or /resume @${id}; your message is queued.)`);
      return;
    }
    // in a turn: delivered with its next agentcraft tool result, or right after the turn ends
    // (deliverPending). Paused mid-turn: delivered with the resumed job's prompt.
    if (this.running.has(id) || this.pausedJobs.has(id) || this.delayed.has(id)) return;
    // every unread message from the user to this agent goes into one follow-up
    const mine = this.fm.bus.inbox(id).filter((m) => m.from === 'user' && (m.to === id || (to === 'all' && m.to === 'all')));
    const body = mine.length ? mine.map((m) => m.text).join('\n\n') : text;
    const consume = () => this.fm.bus.markRead(id, mine.map((m) => m.id));
    const prompt = `Message from ${userName()}: ${body}\n\n${note ? `${note}\n\n` : ''}Respond briefly with send_message(to "user") and act on it if needed (lead: create or update tasks; worker: adjust your work).`;
    if (this.fm.isLead(id)) {
      const goal = this.fm.currentGoalOf(id);
      if (!goal) {
        consume();
        this.fm.bus.send(id, 'user', 'No goal yet - type one in the console and I will plan it.');
        return;
      }
      consume();
      this.enqueue({ kind: 'followup', agentId: id, goalId: goal.id, sessionKey: `${id}:${goal.id}`, prompt });
      return;
    }
    const t = this.fm.tasks.list().filter((x) => x.assignee === id && (x.status === 'doing' || x.status === 'review')).pop();
    consume();
    if (!t) {
      let lead = this.fm.leadOf(this.fm.currentGoal());
      if (!this.fm.leads.onDuty(lead)) lead = HOME_LEAD;
      this.fm.bus.send(id, 'user', `I am not on a task right now - ${this.fm.nameOf(lead)} will pick that up.`);
      this.fm.bus.send('user', lead, `(for ${this.fm.nameOf(id)}) ${body}`);
      const name = this.fm.nameOf(id);
      this.onUserMessage(
        lead,
        `(originally for ${name}) ${body}`,
        `${name} is not on a task, and workers only read messages while they work on one. If this needs ${name} to do something, create a task for it with create_task (assignee "${id}"); a send_message alone will not reach ${name}.`,
      );
      return;
    }
    this.enqueue({ kind: 'followup', agentId: id, taskId: t.id, ...(t.goalId ? { goalId: t.goalId } : {}), sessionKey: `${id}:${t.id}`, prompt });
  }

  /**
   * Messages from the user to this agent that nobody has read yet (they arrived after its last
   * agentcraft tool call, or while it was off shift): start a follow-up turn for them.
   */
  protected override deliverPending(agentId: string): void {
    // (a retry waiting for its time takes the unread messages along with its prompt)
    if (this.stopping || this.isStopped(agentId) || this.running.has(agentId) || this.pausedJobs.has(agentId) || this.delayed.has(agentId) || (this.queues.get(agentId)?.length ?? 0) > 0) return;
    const a = this.fm.agent(agentId);
    if (!a?.active || a.paused) return;
    const fromUser = this.fm.bus.inbox(agentId).filter((m) => m.from === 'user' && m.to === agentId);
    if (!fromUser.length) return;
    this.fm.log.info(`delivering ${fromUser.length} message(s) from ${userName()} to ${agentId} that arrived after its last turn`);
    this.onUserMessage(agentId, fromUser[fromUser.length - 1]!.text);
  }

  onDecisionSettled(d: Decision): void {
    if (this.prs.owns(d.id)) {
      // the PR watcher's own decisions (post replies, fold in?): never an agent's question
      void this.prs.onDecision(d).then(() => this.tick()).catch((e) => this.fm.log.error(`PR decision ${d.id}: ${(e as Error).stack ?? e}`));
      return;
    }
    if (d.kind === 'question') {
      // in-process ask_user waiters resolve by themselves; after a restart nobody waits -> resume
      if (!this.waitingUser.has(d.agentId) && !this.running.has(d.agentId) && !this.isStopped(d.agentId)) {
        const ans = [d.answer?.option, d.answer?.text].filter(Boolean).join(' — ') || '(cancelled)';
        const inf = this.st.inflight[d.agentId];
        const t = d.taskId ? this.fm.tasks.get(d.taskId) : undefined;
        const lead = this.fm.isLead(d.agentId);
        const goalId = inf?.goalId ?? t?.goalId ?? (lead ? this.fm.currentGoalOf(d.agentId)?.id : undefined);
        const sessionKey = inf?.sessionKey ?? (lead ? `${d.agentId}:${goalId ?? 'adhoc'}` : t ? `${d.agentId}:${t.id}` : undefined);
        if (sessionKey) {
          // keep the interrupted job's kind, so its after-turn step (e.g. plan -> active) still runs
          this.enqueue({
            kind: inf?.kind ?? 'followup',
            agentId: d.agentId,
            sessionKey,
            resumed: true,
            ...(t ? { taskId: t.id } : inf?.taskId ? { taskId: inf.taskId } : {}),
            ...(goalId ? { goalId } : {}),
            ...(inf ? goalReplyOf(inf) : {}),
            prompt: `Earlier you asked ${userName()}: "${d.question}". ${userName()} answered: ${ans}. (Your ask_user call was interrupted by an orchestrator restart.) Continue.`,
          });
        }
      }
      return;
    }
    if (d.kind === 'merge' && d.taskId) {
      const t = this.fm.tasks.get(d.taskId);
      if (!t) return;
      if (d.answer?.option === 'Merge' && (t.status === 'done' || t.status === 'pr')) {
        const wtl = t.repoId && t.worktree ? this.fm.repos.findWorktree(t.repoId, t.worktree) : undefined;
        const landed = wtl && t.repoId && this.fm.repos.isUserBase(t.repoId, wtl.base) ? `added to ${wtl.base}` : t.status === 'pr' ? `PR #${t.pr?.id ?? '?'} open` : t.repoId && this.fm.repos.landsAsPr(t.repoId) ? 'PR opened' : 'merged';
        if (t.assignee && this.fm.agent(t.assignee)?.taskId === t.id) this.fm.setAgent(t.assignee, { state: 'idle', station: 'lounge', activity: `${t.id} ${landed}`, taskId: null, worktree: null });
        const lead = this.fm.leadOfTask(t);
        if (!this.isStopped(lead)) this.fm.setAgent(lead, { state: 'idle', station: 'meeting', activity: 'watching the task wall' });
        const g = t.goalId ? this.fm.goal(t.goalId) : undefined;
        if (g && this.fm.tasks.goalComplete(g.id)) {
          this.fm.bus.send(lead, 'user', `Everything for "${truncate(g.text, 80)}" is merged. Nice working with you.`, { goalId: g.id });
          for (const w of this.team) if (!this.isStopped(w)) this.fm.setAgent(w, { state: 'done', station: 'lounge', activity: 'goal done' });
          if (!this.isStopped(lead)) this.fm.setAgent(lead, { state: 'done', station: 'meeting', activity: 'goal done' });
        }
        this.tick();
      } else if (d.answer?.option === 'Request changes') {
        this.sendBackToWorker(t.id, `${userName()} reviewed ${t.id} and requested changes:\n${d.answer.text ?? '(no details given - ask_user if unclear)'}\n\nMake the changes, re-run the tests, then update_task("${t.id}", status "review", summary).`);
      } else if (d.answer?.option === 'Reject') {
        if (t.assignee && this.fm.agent(t.assignee)?.taskId === t.id) this.fm.setAgent(t.assignee, { state: 'idle', station: 'lounge', activity: `${t.id} rejected`, taskId: null, worktree: null });
        this.tick();
      }
    }
  }

  watchesPrs(): boolean {
    return this.prs.active;
  }

  onPrRefresh(taskId?: string): void {
    this.prs.refresh(taskId);
  }

  onPrPush(task: Task, outcome: 'opened' | 'landed' | 'empty' | 'rejected', sha?: string): void {
    if (outcome === 'opened') return;
    delete this.st.foldIns[task.id];
    this.fm.store.markDirty();
    if (task.assignee && this.fm.agent(task.assignee)?.taskId === task.id) this.fm.setAgent(task.assignee, { state: 'idle', station: 'lounge', activity: `${task.id} PR open`, taskId: null, worktree: null });
    void this.prs.foldInEnded(task.id, outcome, sha).finally(() => this.tick());
  }

  /** A triage turn for this task's PR is queued, running or paused (for the task's lead). */
  protected override triaging(taskId: string): boolean {
    const lead = this.fm.leadOfTask(this.fm.tasks.get(taskId));
    return this.hasQueued(lead, (j) => j.kind === 'triage' && j.taskId === taskId) || (this.st.inflight[lead]?.kind === 'triage' && this.st.inflight[lead]?.taskId === taskId);
  }

  /** The lead triages a PR's new items in the goal's session. */
  protected override async enqueueTriage(taskId: string, items: TriageItem[]): Promise<void> {
    const t = this.fm.tasks.get(taskId);
    if (!t?.pr) return;
    let diffText = '(diff not available)';
    if (t.repoId && t.worktree) {
      try {
        const d = await this.fm.repos.diff(t.repoId, t.worktree);
        diffText = truncate(renderDiffText(d.files), 40_000);
      } catch (e) {
        this.fm.log.warn(`triage diff for ${t.id}: ${(e as Error).message}`);
      }
    }
    const p = t.repoId ? this.fm.repos.settingsFor(t.repoId).prReview : undefined;
    const prompt = triagePrompt(this.fm, t, items, diffText, {
      autoSeverities: p?.autoSeverities ?? DEFAULT_AUTO_SEVERITIES,
      mode: this.prs.mode === 'on' ? 'on' : 'observe',
      rounds: this.prs.state(t.id).reviewRounds,
      maxRounds: p?.maxRounds ?? DEFAULT_MAX_ROUNDS,
    });
    const lead = this.fm.leadOfTask(t);
    if (!this.isStopped(lead)) this.fm.setAgent(lead, { state: 'reading', station: 'mergestation', activity: `triaging PR #${t.pr.id}` });
    this.enqueue({ kind: 'triage', agentId: lead, taskId: t.id, ...(t.goalId ? { goalId: t.goalId } : {}), sessionKey: `${lead}:${t.goalId ?? 'adhoc'}`, prompt });
  }

  /** Review fixes for a task's open PR: back on the board for its worker, continuing its branch. */
  protected override startFoldIn(taskId: string, notes: string): boolean {
    const t = this.fm.tasks.get(taskId);
    if (!t || t.status !== 'pr' || !t.repoId || !t.worktree) return false;
    const from = this.fm.store.data.worktreeMeta[`${t.repoId}/${t.worktree}`]?.mergedSha;
    this.st.foldIns[t.id] = { notes, at: Date.now(), ...(from ? { from } : {}) };
    // a fresh CI retry and protect budget for the follow-up
    delete this.st.ciFixes[t.id];
    delete this.st.ciFixes[`protect:${t.id}`];
    this.fm.store.markDirty();
    this.fm.tasks.setStatus(t.id, 'todo', { force: true });
    this.fm.bus.feed('task', `${t.id} goes back to ${this.fm.nameOf(t.assignee ?? 'the next free worker')} for review fixes on PR #${t.pr?.id}`, { agentId: this.fm.leadOfTask(t), taskId: t.id });
    this.tick();
    return true;
  }

  protected override onPrMerged(t: Task): void {
    if (t.assignee && this.fm.agent(t.assignee)?.taskId === t.id) this.fm.setAgent(t.assignee, { state: 'idle', station: 'lounge', activity: `${t.id} merged`, taskId: null, worktree: null });
    const g = t.goalId ? this.fm.goal(t.goalId) : undefined;
    if (g && this.fm.tasks.goalComplete(g.id)) {
      const lead = this.fm.leadOf(g);
      this.fm.bus.send(lead, 'user', `Every pull request for "${truncate(g.text, 80)}" is merged. Nice working with you.`, { goalId: g.id });
      for (const w of this.team) if (!this.isStopped(w) && !this.running.has(w)) this.fm.setAgent(w, { state: 'done', station: 'lounge', activity: 'goal done' });
      if (!this.isStopped(lead) && !this.running.has(lead)) this.fm.setAgent(lead, { state: 'done', station: 'meeting', activity: 'goal done' });
    }
    this.tick();
  }
}
