// Claude backend: real Claude Agent SDK sessions for the lead and workers (see core.ts for how the
// class is put together). This layer: start / stop, agent steering (pause, resume, stop, spawn), live
// config changes and leads (a lead per building).
import { type Backend } from '../../foreman.js';
import type { Goal } from '../../protocol.js';
import { planPrompt } from './prompts.js';
import { DesignTurns } from './jobs/designTurns.js';
import { type Job, sleep, WAKE_INTERVAL_MS } from './core.js';

export { agentEnv, NO_ATTRIBUTION, rotationDue, type ClaudeBackendOptions } from './core.js';

export class ClaudeBackend extends DesignTurns implements Backend {
  async start(): Promise<void> {
    for (const a of this.fm.agents()) {
      const onTeam = (this.fm.isLead(a.id) ? this.fm.leads.onDuty(a.id) : this.team.includes(a.id)) && !this.isStopped(a.id);
      this.fm.setAgent(a.id, { active: onTeam });
      if (!onTeam) this.fm.setAgent(a.id, { state: 'idle', station: 'lounge', activity: this.isStopped(a.id) ? 'stopped - off shift' : 'off shift' });
      else if (a.activity === 'off shift' || a.activity.startsWith('stopped')) this.fm.setAgent(a.id, { activity: 'ready' });
    }
    // the spend survives restarts: every session's cost is persisted, so the total is their sum
    const spent = Object.values(this.fm.store.data.sessions).reduce((sum, s) => sum + (s.costUsd || 0), 0);
    if (spent > 0) this.fm.setStatus({ costUsd: Math.round(spent * 1000) / 1000 });
    await this.checkAuth();
    this.prepareContext();
    this.preparePermissions();
    if (!this.cfg.resumeOnStart) {
      this.st.inflight = {};
    } else {
      this.recover();
    }
    this.armLimitTimer();
    // limits and reserve windows end on the wall clock: a timer alone can fire late after a sleep
    this.wakeTimer = setInterval(() => {
      this.liftExpired();
      this.refreshHold();
    }, this.opts.wakeIntervalMs ?? WAKE_INTERVAL_MS);
    this.wakeTimer.unref?.();
    this.refreshHold();
    this.prs.start();
    if (this.prs.active) this.fm.log.info(`PR watching: ${this.prs.mode} (every ${this.cfg.prPollSeconds}s)`);
    // plans recorded before goals carried their plan id
    for (const [goalId, memoryId] of Object.entries(this.st.plans)) if (!this.fm.goal(goalId)?.planId && this.fm.memory.get(memoryId)) this.fm.recordPlan(goalId, memoryId);
    // the user's messages that no agent read before the restart
    for (const id of [...this.leadsOnDuty(), ...this.team]) this.deliverPending(id);
    for (const id of this.leadsOnDuty()) this.queueGoalMessages(id);
    void this.fm.repos.sweepPendingRemovals().catch((e) => this.fm.log.debug(`sweep: ${(e as Error).message}`));
    this.tick();
  }

  async stop(): Promise<void> {
    this.stopping = true;
    this.prs.stop();
    if (this.tickTimer) clearTimeout(this.tickTimer);
    if (this.limitTimer) clearTimeout(this.limitTimer);
    if (this.retryTimer) clearTimeout(this.retryTimer);
    if (this.authRetryTimer) clearTimeout(this.authRetryTimer);
    if (this.wakeTimer) clearInterval(this.wakeTimer);
    for (const d of this.delayed.values()) clearTimeout(d.timer);
    this.delayed.clear();
    const turns = [...this.running.values()];
    for (const r of turns) this.abortTurn(r, 'shutdown');
    const designs = this.designs.stop();
    await Promise.race([Promise.allSettled([...this.turnPromises, designs]), sleep(4000)]);
    // nothing an agent started outlives the Foreman (sessions resume on the next start)
    await Promise.race([Promise.allSettled(turns.map((r) => this.reap(r, 1500))), sleep(3000)]);
    // inflight entries stay persisted so the next start resumes them
    this.fm.store.markDirty();
  }

  async onAgentAction(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn'): Promise<void> {
    const r = this.running.get(agentId);
    const name = this.fm.nameOf(agentId);
    if (action === 'pause') {
      if (r) this.abortTurn(r, 'pause');
      // a retry waiting for its time: held until the resume
      const later = this.delayed.get(agentId);
      if (later) {
        this.dropDelayed((id) => id === agentId);
        this.pausedJobs.set(agentId, later.job);
      }
      this.fm.setAgent(agentId, { state: 'idle', activity: 'paused' });
    } else if (action === 'resume' || action === 'spawn') {
      const wasStopped = this.isStopped(agentId);
      if (action === 'spawn' && !this.fm.isLead(agentId) && !this.cfg.workers.includes(agentId)) this.cfg.workers.push(agentId);
      if (wasStopped || action === 'spawn') {
        this.setStopped(agentId, false);
        this.fm.setAgent(agentId, { active: true, paused: false, state: 'idle', station: 'lounge', activity: 'ready' });
        if (wasStopped) this.fm.bus.feed('system', `${name} is back on shift`, { agentId });
      }
      const job = this.pausedJobs.get(agentId);
      this.pausedJobs.delete(agentId);
      if (job) this.enqueue(job);
      else this.pump(agentId);
      if (this.fm.isLead(agentId) && wasStopped) this.reconcile();
      // messages the user sent while the agent was off shift or paused
      this.deliverPending(agentId);
      this.queueGoalMessages(agentId);
    } else if (action === 'stop') {
      this.setStopped(agentId, true);
      if (r) this.abortTurn(r, 'stop');
      this.queues.delete(agentId);
      this.pausedJobs.delete(agentId);
      this.dropDelayed((id) => id === agentId);
      delete this.st.inflight[agentId];
      this.withdrawDecisions(agentId, `${name} was stopped`);
      if (!this.fm.isLead(agentId)) {
        for (const t of this.fm.tasks.list().filter((x) => x.assignee === agentId && x.status === 'doing')) {
          // back on the board, but held until the agent's turn is really over and its work is committed;
          // the next worker then continues from this branch
          this.fm.tasks.setStatus(t.id, 'todo', { force: true });
          this.fm.tasks.update(t.id, { assignee: null });
          this.fm.bus.feed('task', `${t.id} is back on the board (${name} was stopped)`, { agentId: 'user', taskId: t.id });
          this.handOff(t.id, agentId, `${name} was stopped`);
        }
      }
      this.fm.setAgent(agentId, { active: false, paused: false, state: 'idle', station: 'lounge', activity: 'stopped - off shift', taskId: null, worktree: null });
    }
    this.tick();
  }

  /** Withdraw an agent's open questions and permission prompts (not merge decisions: those are the user's). */
  protected withdrawDecisions(agentId: string, why: string): void {
    for (const d of this.fm.decisions.open().filter((x) => x.agentId === agentId && x.kind !== 'merge' && !this.prs.owns(x.id) && !this.fm.ownsDecision(x.id))) this.fm.decisions.cancel(d.id, why);
  }

  /**
   * config.set copied live settings into this.cfg (models, effort, concurrency, permissions, context
   * lists and repo settings are read at every turn / tick anyway): PR watching switches over now,
   * the status line shows the new models, and more turns may start.
   */
  onConfigChanged(): void {
    this.prs.configure(this.cfg.prWatch, this.cfg.prPollSeconds);
    const msg = this.fm.status.message;
    if (this.fm.status.auth === 'ok' && msg?.startsWith('Claude (lead ')) this.fm.setStatus({ message: this.baseStatusMessage() });
    this.tick();
  }

  onLeadAssigned(leadId: string): void {
    // a newly assigned lead starts on shift (a stop from an earlier assignment does not carry over)
    if (this.isStopped(leadId)) this.setStopped(leadId, false);
    this.deliverPending(leadId);
    this.queueGoalMessages(leadId);
    this.tick();
  }

  /**
   * Open goals moved to `leadId` (its building has their repository, C3). Work the previous lead had
   * QUEUED for them (reviews, triage) is dropped and picked up again for the new lead (sweepReviews,
   * PR triage); a turn already running finishes (its plan still settles the goal). The new lead's
   * first turn on each gets the takeover note.
   */
  onGoalsAdopted(leadId: string, goals: Goal[], from: Record<string, string>): void {
    const ids = new Set(goals.map((g) => g.id));
    const mine = (j: Job) => !!j.goalId && ids.has(j.goalId) && (j.kind === 'review' || j.kind === 'triage');
    for (const prev of new Set(Object.values(from))) {
      if (prev === leadId) continue;
      const q = this.queues.get(prev);
      if (q) this.queues.set(prev, q.filter((j) => !mine(j)));
      const p = this.pausedJobs.get(prev);
      if (p && mine(p)) this.pausedJobs.delete(prev);
      this.dropDelayed((id, j) => id === prev && mine(j));
    }
    if (this.stopping || this.authFailed) return;
    this.reconcile();
    for (const t of this.prs.watched()) {
      if (!t.goalId || !ids.has(t.goalId)) continue;
      const items = this.prs.pendingItems(t.id);
      if (items.length && !this.triaging(t.id)) void this.enqueueTriage(t.id, items);
    }
    this.tick();
  }

  /**
   * A building lead was released: its turn ends and nothing more of it runs. Its goals are marlow's
   * now (the Foreman moved them): planning goals are planned again, finished tasks reviewed and
   * pending PR triage offered to marlow; marlow's first turn on each gets the takeover note.
   */
  onLeadReleased(leadId: string, goals: Goal[] = []): void {
    const r = this.running.get(leadId);
    if (r) this.abortTurn(r, 'stop');
    this.queues.delete(leadId);
    this.pausedJobs.delete(leadId);
    this.dropDelayed((id) => id === leadId);
    this.waitingUser.delete(leadId);
    delete this.st.inflight[leadId];
    this.fm.store.markDirty();
    this.withdrawDecisions(leadId, `${this.fm.nameOf(leadId)} no longer leads a building`);
    if (this.stopping || this.authFailed) return;
    // goals still being planned: their new lead plans them now (reconcile alone would wait while
    // that lead has an open question about another goal)
    for (const g of goals) {
      const goal = this.fm.goal(g.id);
      if (!goal || goal.status !== 'planning' || this.fm.tasks.forGoal(goal.id).length) continue;
      const lead = this.fm.leadOf(goal);
      const repo = goal.repoId ? this.fm.repos.get(goal.repoId) : undefined;
      if (!repo || this.isStopped(lead) || this.st.inflight[lead]?.goalId === goal.id || this.hasQueued(lead, (j) => j.goalId === goal.id)) continue;
      this.enqueue({ kind: 'plan', agentId: lead, goalId: goal.id, sessionKey: `${lead}:${goal.id}`, fresh: !this.fm.store.data.sessions[`${lead}:${goal.id}`]?.sessionId, prompt: planPrompt(this.fm, goal, repo.path, this.st.goalBranch[goal.id]?.branch ?? repo.branch) });
    }
    this.reconcile();
    const moved = new Set(goals.map((g) => g.id));
    for (const t of this.prs.watched()) {
      if (!t.goalId || !moved.has(t.goalId)) continue;
      const items = this.prs.pendingItems(t.id);
      if (items.length && !this.triaging(t.id)) void this.enqueueTriage(t.id, items);
    }
    this.tick();
  }
}
