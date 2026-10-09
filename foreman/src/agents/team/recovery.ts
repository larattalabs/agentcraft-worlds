// The team: after a restart. Re-attach or re-queue what was in flight, then bring every
// non-terminal state (planning goals, doing tasks, review tasks) back in line with what is running.
import { planPrompt, RESUME_PROMPT } from '../prompts.js';
import { OutcomesLayer } from './outcomes.js';
import { goalReplyOf } from './core.js';

export abstract class RecoveryLayer extends OutcomesLayer {
  /**
   * After a restart: re-attach or re-queue everything that was in flight, then reconcile every
   * non-terminal state with what is actually running, so nothing waits forever.
   */
  protected recover(): void {
    const st = this.st;
    // permission prompts from a dead process are moot; the resumed agent retries the tool
    for (const d of this.fm.decisions.open().filter((x) => x.kind === 'permission')) this.fm.decisions.cancel(d.id, 'Foreman restarted');
    for (const [agentId, inf] of Object.entries(st.inflight)) {
      // a lead released while the Foreman was down (or dropped from claude.leads): its goals are
      // marlow's now, the reconciliation below hands them over
      const offDuty = this.fm.isLead(agentId) && (!this.fm.leads.onDuty(agentId) || (!!inf.goalId && this.fm.leadOf(this.fm.goal(inf.goalId)) !== agentId));
      if (this.isStopped(agentId) || !this.fm.agent(agentId) || offDuty) {
        // an interrupted answer to goal messages: offered again (to whoever leads the goal now)
        if (inf.goalReply) this.requeueGoalMessage(agentId, inf, true);
        delete st.inflight[agentId];
        continue;
      }
      // (a question the turn asked: the answer resumes it, as a goal-message turn still)
      const openQ = this.openQuestion(agentId);
      if (openQ) {
        this.fm.log.info(`recover: ${agentId} is waiting on ${openQ.id}; will resume after the answer`);
        this.fm.setAgent(agentId, { state: 'waiting_user', station: 'user', activity: 'waiting for your answer' });
        continue;
      }
      if (inf.goalReply && this.requeueGoalMessage(agentId, inf)) {
        delete st.inflight[agentId];
        continue;
      }
      const session = this.fm.store.data.sessions[inf.sessionKey];
      if (session?.sessionId) {
        this.fm.log.info(`recover: resuming ${agentId} (${inf.kind}${inf.taskId ? ` ${inf.taskId}` : ''})`);
        this.enqueue({ kind: inf.kind, agentId, sessionKey: inf.sessionKey, prompt: RESUME_PROMPT, resumed: true, ...(inf.taskId ? { taskId: inf.taskId } : {}), ...(inf.goalId ? { goalId: inf.goalId } : {}), ...goalReplyOf(inf) });
      } else {
        // the turn died before it had a session: the reconciliation below starts it again
        delete st.inflight[agentId];
      }
    }
    this.reconcile();
    this.fm.store.markDirty();
  }

  /** Bring planning goals, doing tasks and review tasks back in line with running/queued jobs. */
  protected reconcile(): void {
    const st = this.st;
    // goals still planning with nobody planning them
    for (const g of this.fm.goals().filter((x) => x.status === 'planning')) {
      const lead = this.fm.leadOf(g);
      // (another lead's plan for it still running or waiting, e.g. the goal moved to a new building's
      // lead mid-plan: that plan settles the goal, no second one)
      const otherPlanning = this.leadsOnDuty().some((l) => l !== lead && ((st.inflight[l]?.goalId === g.id && st.inflight[l]?.kind === 'plan') || this.hasQueued(l, (j) => j.goalId === g.id && j.kind === 'plan')));
      const leadOnIt = otherPlanning || st.inflight[lead]?.goalId === g.id || this.hasQueued(lead, (j) => j.goalId === g.id) || this.openQuestion(lead) !== undefined;
      if (leadOnIt || this.isStopped(lead)) continue;
      if (this.fm.tasks.forGoal(g.id).length) this.promoteGoal(g, 'recovered');
      else {
        const repo = g.repoId ? this.fm.repos.get(g.repoId) : undefined;
        if (!repo) continue;
        this.fm.log.info(`recover: re-planning ${g.id}`);
        this.enqueue({ kind: 'plan', agentId: lead, goalId: g.id, sessionKey: `${lead}:${g.id}`, fresh: !this.fm.store.data.sessions[`${lead}:${g.id}`]?.sessionId, prompt: planPrompt(this.fm, g, repo.path, this.st.goalBranch[g.id]?.branch ?? repo.branch) });
      }
    }
    // doing tasks whose worker is not working on them: back on the board (the session resumes)
    for (const t of this.fm.tasks.list()) {
      if (t.status !== 'doing' || !t.assignee || this.fm.isLead(t.assignee)) continue;
      const w = t.assignee;
      if (st.inflight[w]?.taskId === t.id || this.hasQueued(w, (j) => j.taskId === t.id)) continue;
      if (this.openQuestion(w)?.taskId === t.id) continue; // resumes with the answer
      this.fm.log.info(`recover: ${t.id} was doing without a running turn; re-queued`);
      this.fm.tasks.setStatus(t.id, 'todo', { force: true });
      if (this.isStopped(w)) this.fm.tasks.update(t.id, { assignee: null });
    }
    this.sweepReviews();
  }
}
