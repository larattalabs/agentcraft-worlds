// Sim backend: deterministic scripted team working on a real sandbox repo.
import type { SimConfig } from '../../config.js';
import { ClientError, type Backend, type Foreman } from '../../foreman.js';
import type { Decision, Design, Goal, Task } from '../../protocol.js';
import { SimDesigner } from './designer.js';
import { truncate } from '../../util/text.js';
import { SimDirector, Stopped, type SimState } from './director.js';
import { BEATS, DEFAULT_SIM_GOAL, SIDE_BEATS } from './scenario.js';
import { userName } from '../../user.js';
import { HOME_LEAD } from '../../leads.js';

const CANNED_REPLIES = [
  'Got it - noted.',
  'On it. I will fold that into what I am doing.',
  'Thanks! Adding it to my notes.',
  'Understood. I will flag anything that conflicts with the plan.',
];

export class SimBackend implements Backend {
  readonly name = 'sim' as const;
  private director: SimDirector | undefined;
  private running: Promise<void> | undefined;
  /** side flows: goals of other buildings' leads (SIDE_BEATS), by goal id */
  private sides = new Map<string, { director: SimDirector; running: Promise<void> }>();
  private replyCount = 0;
  /** resolves when the scenario reaches the showcase checkpoint or ends (tests) */
  private settledWaiters: Array<() => void> = [];
  /** fake building design jobs (hub: Design new) */
  readonly designer: SimDesigner;

  constructor(
    private fm: Foreman,
    private cfg: SimConfig,
  ) {
    this.designer = new SimDesigner(fm, cfg.speed);
  }

  get state(): SimState {
    const b = this.fm.store.data.backend;
    let st = b.sim as SimState | undefined;
    if (!st) {
      st = { beat: 0, vars: {} };
      b.sim = st;
      this.fm.store.markDirty();
    }
    return st;
  }

  get beatCount(): number {
    return BEATS.length;
  }

  async start(): Promise<void> {
    const st = this.state;
    // the same banner as before the restart: "- goal done" once the scenario has finished
    const done = st.goalId && st.finished ? ' - goal done' : '';
    this.fm.setStatus({ backend: 'sim', auth: 'ok', message: `Simulated team (speed x${this.cfg.speed})${done}`, speed: this.cfg.speed, ...(this.cfg.showcase ? { showcase: false } : {}) });
    if (st.goalId && !st.finished) {
      if (this.cfg.showcase && st.checkpoint === this.cfg.showcaseAt) {
        this.fm.setStatus({ showcase: true, message: 'Showcase (static)' });
        this.fm.log.info('sim: holding showcase state');
        return;
      }
      this.fm.log.info(`sim: resuming scenario at beat ${st.beat + 1}/${BEATS.length} (${BEATS[st.beat]?.name ?? 'end'})`);
      this.fm.bus.feed('system', `Foreman restarted - the team picks up where it left off (${BEATS[st.beat]?.name ?? 'end'}).`);
      this.launch();
    }
    // side flows of other leads' goals that were running
    for (const goalId of Object.keys(st.side ?? {})) if (!st.side![goalId]!.finished) this.launchSide(goalId);
  }

  /** Called by main when --autostart/--showcase and no scenario has run yet. */
  async autostart(text?: string): Promise<Goal | undefined> {
    if (this.state.goalId) return undefined;
    return this.fm.submitGoal(text ?? DEFAULT_SIM_GOAL);
  }

  async submitGoal(goal: Goal): Promise<void> {
    const st = this.state;
    // a goal for another building's lead while the script is busy (or done): that lead runs a side
    // flow on its building's repository, borrowing free workers
    const lead = this.fm.leadOf(goal);
    const scriptLead = st.goalId ? this.fm.leadOf(this.fm.goal(st.goalId)) : undefined;
    if (st.goalId && lead !== HOME_LEAD && (st.finished || lead !== scriptLead) && !this.sideBusy(lead)) {
      st.side ??= {};
      st.side[goal.id] = { goalId: goal.id, beat: 0, vars: {} };
      this.fm.store.markDirty();
      this.launchSide(goal.id);
      return;
    }
    if (st.goalId && !st.finished) {
      this.fm.setGoal(goal.id, { status: 'cancelled' });
      throw new ClientError('the sim team is already working on a goal (restart with --reset to replay)');
    }
    if (st.finished) {
      this.fm.setGoal(goal.id, { status: 'cancelled' });
      this.fm.bus.send('marlow', 'user', 'The simulated team only knows one script and it is done. Restart the Foreman with --reset to replay it, or use --backend claude for real work.');
      return;
    }
    st.goalId = goal.id;
    st.beat = 0;
    st.vars = {};
    this.fm.store.markDirty();
    this.fm.setStatus({ message: `Simulated team (speed x${this.cfg.speed}) - working on ${goal.id}` });
    this.launch();
  }

  private launch(): void {
    if (this.running) return;
    const st = this.state;
    const d = new SimDirector(this.fm, this.cfg, st, () => this.fm.store.markDirty());
    this.director = d;
    d.instant = this.cfg.showcase && st.checkpoint !== this.cfg.showcaseAt;
    if (d.instant) this.fm.setStatus({ message: `Showcase: fast-forwarding to the ${this.cfg.showcaseAt === 'showcase-late' ? 'late ' : ''}showcase state...` });
    this.running = (async () => {
      try {
        while (st.beat < BEATS.length) {
          const beat = BEATS[st.beat]!;
          this.fm.log.debug(`sim beat ${st.beat + 1}/${BEATS.length}: ${beat.name}`);
          await beat.run(d);
          st.beat++;
          this.fm.store.markDirty();
          if (beat.checkpoint) {
            st.checkpoint = beat.checkpoint;
            if (this.cfg.showcase && beat.checkpoint === this.cfg.showcaseAt) {
              this.fm.flushLogs();
              this.fm.setStatus({ showcase: true, message: 'Showcase (static)' });
              this.fm.log.info('sim: showcase state reached; holding');
              this.store();
              return;
            }
          }
        }
        st.finished = true;
        this.store();
        this.fm.setStatus({ message: `Simulated team (speed x${this.cfg.speed}) - goal done` });
        this.fm.log.info('sim: scenario finished');
      } catch (e) {
        if (e instanceof Stopped) return;
        this.fm.log.error(`sim beat "${BEATS[st.beat]?.name}" failed: ${(e as Error).stack ?? e}`);
        this.fm.bus.feed('error', `Sim scenario error in "${BEATS[st.beat]?.name}": ${(e as Error).message}`);
        this.fm.notify('warn', `Sim error: ${truncate((e as Error).message, 120)}`);
      } finally {
        this.running = undefined;
        if (!this.sides.size) for (const w of this.settledWaiters.splice(0)) w();
      }
    })();
  }

  /** A lead already runs a side flow (one goal at a time per lead in the sim). */
  private sideBusy(lead: string): boolean {
    return Object.values(this.state.side ?? {}).some((x) => !x.finished && x.goalId && this.fm.leadOf(this.fm.goal(x.goalId)) === lead);
  }

  private launchSide(goalId: string): void {
    if (this.sides.has(goalId)) return;
    const st = this.state.side![goalId]!;
    const d = new SimDirector(this.fm, { ...this.cfg, showcase: false }, st, () => this.fm.store.markDirty());
    const running = (async () => {
      try {
        while (st.beat < SIDE_BEATS.length) {
          await SIDE_BEATS[st.beat]!.run(d);
          st.beat++;
          this.fm.store.markDirty();
        }
        st.finished = true;
        this.store();
      } catch (e) {
        if (e instanceof Stopped) return;
        this.fm.log.error(`sim side flow ${goalId} "${SIDE_BEATS[st.beat]?.name}" failed: ${(e as Error).stack ?? e}`);
        this.fm.bus.feed('error', `Sim side flow for ${goalId} failed in "${SIDE_BEATS[st.beat]?.name}": ${(e as Error).message}`);
      } finally {
        this.sides.delete(goalId);
        if (!this.running && !this.sides.size) for (const w of this.settledWaiters.splice(0)) w();
      }
    })();
    this.sides.set(goalId, { director: d, running });
  }

  private store(): void {
    this.fm.store.markDirty();
    this.fm.store.flush();
  }

  /** For tests/tools: resolves when the scenario finishes, errors, or holds at the showcase. */
  idle(): Promise<void> {
    if (!this.running && !this.sides.size) return Promise.resolve();
    return new Promise((r) => this.settledWaiters.push(r));
  }

  async stop(): Promise<void> {
    this.director?.stop();
    for (const s of this.sides.values()) s.director.stop();
    await this.designer.stop();
    await this.running?.catch(() => undefined);
    await Promise.allSettled([...this.sides.values()].map((s) => s.running));
  }

  onDesignRequest(d: Design): void {
    this.designer.request(d);
  }

  onDesignCancel(id: string): void {
    this.designer.cancel(id);
  }

  onUserMessage(to: string, text: string): void {
    const lead = this.fm.leadOf(this.fm.currentGoal());
    const agents = to === 'all' ? [this.fm.leads.onDuty(lead) ? lead : HOME_LEAD] : [to];
    for (const id of agents) {
      const a = this.fm.agent(id);
      if (!a) continue;
      this.fm.agentLog(id, 'text', `Message from ${userName()}: ${text}`);
      const reply = CANNED_REPLIES[this.replyCount++ % CANNED_REPLIES.length]!;
      setTimeout(() => this.fm.bus.send(id, 'user', reply), 1200 / this.cfg.speed).unref?.();
    }
  }

  /** goal.message: the goal's lead answers in the goal's thread (canned, after a short pause). */
  onGoalMessage(goal: Goal, leadId: string): void {
    const msgs = this.fm.bus.goalInbox(leadId, goal.id);
    if (!msgs.length) return;
    this.fm.bus.markRead(leadId, msgs.map((m) => m.id));
    const text = msgs[msgs.length - 1]!.text;
    for (const m of msgs) this.fm.agentLog(leadId, 'text', `Message from ${userName()} about ${goal.id}: ${truncate(m.text, 400)}`);
    const reply = /standing instructions/i.test(text)
      ? `Got the new standing instructions for ${goal.id} - the team follows them from the next step.`
      : /the plan for this goal/i.test(text)
        ? `Read your plan changes for ${goal.id}. The tasks still fit; I will keep an eye on it.`
        : goal.status === 'done'
          ? `${goal.id} is done - ${CANNED_REPLIES[this.replyCount++ % CANNED_REPLIES.length]!}`
          : `About ${goal.id}: ${CANNED_REPLIES[this.replyCount++ % CANNED_REPLIES.length]!}`;
    setTimeout(() => {
      if (this.fm.agent(leadId)) this.fm.bus.send(leadId, 'user', reply, { goalId: goal.id });
    }, 1200 / this.cfg.speed).unref?.();
  }

  /** goal.cancel: the goal's scripted flow stops (the main script counts as finished). */
  onGoalCancel(goal: Goal): void {
    const st = this.state;
    const side = this.sides.get(goal.id);
    if (side) side.director.stop();
    if (st.side?.[goal.id]) st.side[goal.id]!.finished = true;
    if (st.goalId === goal.id && !st.finished) {
      this.director?.stop();
      st.finished = true;
      this.fm.setStatus({ message: `Simulated team (speed x${this.cfg.speed}) - goal cancelled` });
    }
    this.store();
  }

  onDecisionSettled(_d: Decision): void {
    // the scenario awaits decisions itself (DecisionQueue.wait)
  }

  onTaskAction(task: Task, action: string): void {
    this.fm.agentLog(this.fm.leadOfTask(task), 'text', `${userName()}: ${action} ${task.id} (${task.title}). The sim script keeps its own course.`);
  }

  onAgentAction(agentId: string, action: string): void {
    if (action === 'pause') this.fm.agentLog(agentId, 'text', `Paused by ${userName()}.`);
    if (action === 'resume' || action === 'spawn') {
      const a = this.fm.agent(agentId);
      if (a && !a.active) this.fm.setAgent(agentId, { active: true, activity: 'back on shift' });
      this.fm.agentLog(agentId, 'text', 'Resumed.');
    }
    if (action === 'stop') {
      // off shift: the scripted team waits for this agent's next step until /resume
      this.fm.setAgent(agentId, { active: false, state: 'idle', station: 'lounge', activity: 'stopped - off shift' });
      this.fm.agentLog(agentId, 'text', `Stopped by ${userName()} (off shift). The script waits for /resume.`);
    }
  }
}
