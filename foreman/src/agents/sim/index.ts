// Sim backend: deterministic scripted team working on a real sandbox repo.
//
// Pull requests (contract S3, docs/PRWATCH.md): repos that land as PRs (repoSettings land "pr", e.g.
// the --sim-pr demo repo pocket-api) push to a local bare "server" and open their PR on a fake host
// (prhost.ts) that answers the `az` command lines; the real PrWatcher reads it, so Task.pr, triage
// items, verdicts, decisions and fold-ins have the same shapes as with the claude backend. The lead's
// triage turn and the worker's review fixes are scripted here. No `az` / `gh` ever runs.
import fs from 'node:fs';
import path from 'node:path';
import type { SimConfig } from '../../config.js';
import { ClientError, type Backend, type Foreman } from '../../foreman.js';
import type { Decision, Design, Goal, Task } from '../../protocol.js';
import { SimDesigner } from './designer.js';
import { truncate } from '../../util/text.js';
import { SimDirector, Stopped, type SimState } from './director.js';
import { BEATS, DEFAULT_SIM_GOAL, SIDE_BEATS } from './scenario.js';
import { userName } from '../../user.js';
import { HOME_LEAD } from '../../leads.js';
import { PrWatcher, type TriageItem, type TriageVerdict } from '../../prwatch.js';
import { SimPrHost, type SimPr } from './prhost.js';
import { SIM_PR_GOAL, simPrRepoDir, simPrRepoSettings } from './prdemo.js';

/** The sim lead's verdict for a triage item: the suggested one for findings, a reply for people. */
function simVerdict(i: TriageItem): TriageVerdict | undefined {
  if (i.kind === 'checks') return { ref: i.ref, verdict: 'fold_in', note: 'Make the failing checks pass again.' };
  if (i.kind === 'finding') {
    const v = i.suggested ?? 'ignore';
    if (v === 'fold_in') return { ref: i.ref, verdict: 'fold_in', note: truncate(i.text.replace(/\s*\(reviewer confidence: \w+\)$/, ''), 300) };
    return { ref: i.ref, verdict: 'ignore', note: '' };
  }
  return { ref: i.ref, verdict: 'reply', note: 'Good idea - I will link it from the README in a follow-up so this PR stays small.' };
}

/** The file review fixes go into: the first one named in the notes that exists in the worktree. */
function foldFile(notes: string, root: string): string | undefined {
  for (const m of notes.matchAll(/([A-Za-z0-9_.\/-]+\.[A-Za-z]{1,5})(?::\d+)?/g)) {
    const f = m[1]!.replace(/^\/+/, '');
    if (!f.includes('..') && fs.existsSync(path.join(root, f))) return f;
  }
  return undefined;
}

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
  /** the fake PR host every sim pull request goes to */
  readonly prHost: SimPrHost;
  /** the real PR watcher, reading the fake host */
  readonly prs: PrWatcher;
  /** review fixes running (fold-ins), by task id */
  private folds = new Map<string, { director: SimDirector; running: Promise<void> }>();
  /** triage turns running, by task id */
  private triaging = new Set<string>();
  private pollQueue = new Set<string>();
  private polling = false;
  private stopped = false;

  constructor(
    private fm: Foreman,
    private cfg: SimConfig,
  ) {
    this.designer = new SimDesigner(fm, cfg.speed);
    this.prHost = new SimPrHost(fm, () => this.cfg.speed, { changed: (pr) => this.prChanged(pr), busy: (pr) => this.prBusy(pr) });
    // under the sim no PR host CLI ever runs: opening, reading and writing PRs all go to the fake host
    fm.repos.prRunFn = this.prHost.run;
    fm.repos.prHostFor = (repoId) => this.prHost.hostFor(repoId);
    this.prs = new PrWatcher(fm, {
      mode: fm.config.claude.prWatch ?? 'observe',
      pollSeconds: fm.config.claude.prPollSeconds ?? 180,
      runFn: this.prHost.run,
      hooks: {
        triage: (task, items) => void this.triage(task.id, items),
        foldIn: (task, notes) => this.startFoldIn(task, notes),
        merged: (task) => this.prMerged(task),
        triaging: (taskId) => this.triaging.has(taskId),
      },
    });
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
    // pull requests: the watcher, the fake host's timeline, review fixes that were running
    this.prs.start();
    this.prHost.start();
    for (const taskId of Object.keys(st.folds ?? {})) this.launchFold(taskId);
  }

  /** Called by main when --autostart/--showcase and no scenario has run yet. */
  async autostart(text?: string): Promise<Goal | undefined> {
    if (this.state.goalId) return undefined;
    const g = await this.fm.submitGoal(text ?? DEFAULT_SIM_GOAL);
    // --sim-pr: the pull request demo repo gets its own goal (a side flow that lands as a PR)
    const pr = this.cfg.prDemo && !this.cfg.showcase ? this.prRepos().find((r) => r.id !== g.repoId) : undefined;
    if (pr) await this.fm.submitGoal(SIM_PR_GOAL, pr.id).catch((e) => this.fm.log.warn(`sim: PR demo goal: ${(e as Error).message}`));
    return g;
  }

  /** Registered repos that land approved work as pull requests. */
  private prRepos() {
    return this.fm.repos.list().filter((r) => this.fm.repos.landsAsPr(r.id));
  }

  async submitGoal(goal: Goal): Promise<void> {
    const st = this.state;
    // a goal for another building's lead while the script is busy (or done): that lead runs a side
    // flow on its building's repository, borrowing free workers
    const lead = this.fm.leadOf(goal);
    const scriptLead = st.goalId ? this.fm.leadOf(this.fm.goal(st.goalId)) : undefined;
    // a goal in a repo that lands as pull requests always runs as a side flow (any lead, marlow too):
    // the scripted 22-beat goal knows only the demo repo
    const prGoal = !!goal.repoId && this.fm.repos.landsAsPr(goal.repoId) && goal.repoId !== (st.goalId ? this.fm.goal(st.goalId)?.repoId : undefined);
    if (prGoal && this.sideBusy(lead)) {
      this.fm.setGoal(goal.id, { status: 'cancelled' });
      throw new ClientError(`${this.fm.nameOf(lead)} is already working on a goal in the sim (one at a time per lead)`);
    }
    if ((st.goalId || prGoal) && (lead !== HOME_LEAD || prGoal) && (st.finished || lead !== scriptLead || prGoal) && !this.sideBusy(lead)) {
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
        this.settle();
      }
    })();
  }

  /** Wake idle() waiters once nothing scripted runs any more. */
  private settle(): void {
    if (!this.running && !this.sides.size && !this.folds.size) for (const w of this.settledWaiters.splice(0)) w();
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
        this.settle();
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
    if (!this.running && !this.sides.size && !this.folds.size) return Promise.resolve();
    return new Promise((r) => this.settledWaiters.push(r));
  }

  async stop(): Promise<void> {
    this.stopped = true;
    this.prs.stop();
    this.prHost.stop();
    this.director?.stop();
    for (const s of this.sides.values()) s.director.stop();
    for (const f of this.folds.values()) f.director.stop();
    await this.designer.stop();
    await this.running?.catch(() => undefined);
    await Promise.allSettled([...this.sides.values(), ...this.folds.values()].map((s) => s.running));
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

  onDecisionSettled(d: Decision): void {
    // the scenario awaits its decisions itself (DecisionQueue.wait); the PR watcher's own decisions
    // (post replies, fold in?) are answered here, as in the claude backend
    if (this.prs.owns(d.id)) {
      void this.prs
        .onDecision(d)
        .then(() => d.taskId && this.pollTask(d.taskId))
        .catch((e) => this.fm.log.error(`PR decision ${d.id}: ${(e as Error).stack ?? e}`));
    }
  }

  // ---- pull requests (prwatch.ts on the fake host) ---------------------------------------------

  watchesPrs(): boolean {
    return this.prs.active;
  }

  onPrRefresh(taskId?: string): void {
    this.prs.refresh(taskId);
  }

  onPrPush(task: Task, outcome: 'opened' | 'landed' | 'empty' | 'rejected', sha?: string): void {
    if (task.pr && (outcome === 'opened' || outcome === 'landed')) void this.prHost.pushed(task.pr.url, sha, outcome === 'opened');
    if (outcome === 'opened') return this.pollTask(task.id);
    const st = this.state;
    if (st.folds?.[task.id]) {
      delete st.folds[task.id];
      this.fm.store.markDirty();
    }
    if (task.assignee && this.fm.agent(task.assignee)?.taskId === task.id) this.fm.setAgent(task.assignee, { state: 'idle', station: 'lounge', activity: `${task.id} PR open`, taskId: null, worktree: null });
    void this.prs.foldInEnded(task.id, outcome, sha).finally(() => this.pollTask(task.id));
  }

  onConfigChanged(): void {
    const c = this.fm.config;
    this.prs.configure(c.claude.prWatch, c.claude.prPollSeconds);
    // config.set re-reads repoSettings from config.json: the PR demo repo keeps landing as PRs
    if (this.cfg.prDemo) {
      const dir = simPrRepoDir(c);
      if (this.fm.repos.list().some((r) => path.resolve(r.path) === path.resolve(dir))) simPrRepoSettings(c, dir);
    }
  }

  /** The task whose PR this is (the newest one in case of a stale duplicate). */
  private taskOfPr(pr: SimPr): Task | undefined {
    return this.fm.tasks
      .list()
      .filter((t) => t.pr?.url === pr.url)
      .pop();
  }

  /** The fake host changed a PR: poll it now (like a webhook). */
  private prChanged(pr: SimPr): void {
    const t = this.taskOfPr(pr);
    if (t) this.pollTask(t.id);
  }

  /** AgentCraft has work in flight on this PR: the fake reviewer does not approve or complete it yet. */
  private prBusy(pr: SimPr): boolean {
    const t = this.taskOfPr(pr);
    if (!t) return false;
    if (t.status !== 'pr' || this.triaging.has(t.id) || this.folds.has(t.id)) return true;
    const s = this.prs.state(t.id);
    if (s.triage || s.foldIn || s.queued.length || s.pendingResolve.length) return true;
    return this.fm.decisions.list().some((d) => d.status === 'open' && d.taskId === t.id);
  }

  /** Poll PRs one at a time (the watcher drops a poll of a PR it is already reading). */
  private pollTask(taskId: string): void {
    if (this.stopped || !this.prs.active) return;
    this.pollQueue.add(taskId);
    if (this.polling) return;
    this.polling = true;
    void (async () => {
      try {
        while (this.pollQueue.size && !this.stopped) {
          const id = this.pollQueue.values().next().value as string;
          this.pollQueue.delete(id);
          await this.prs.poll(id);
          this.autoAnswerPrDecisions();
        }
      } finally {
        this.polling = false;
      }
    })();
  }

  /** --auto-answer: the watcher's decisions get their first option too (Post / Fold in). */
  private autoAnswerPrDecisions(): void {
    if (!this.cfg.autoAnswer) return;
    for (const d of this.fm.decisions.list()) {
      if (d.status !== 'open' || !this.prs.owns(d.id)) continue;
      setTimeout(() => {
        if (this.stopped || this.fm.decisions.get(d.id)?.status !== 'open') return;
        this.fm.answerDecision(d.id, 0).catch((e) => this.fm.log.warn(`sim auto-answer ${d.id}: ${(e as Error).message}`));
      }, 1500 / this.cfg.speed).unref?.();
    }
  }

  private async pause(ms: number): Promise<void> {
    await new Promise((r) => setTimeout(r, ms / this.cfg.speed).unref?.());
  }

  /** The lead's triage turn: scripted verdicts through the watcher's real `triage` tool path. */
  private async triage(taskId: string, items: TriageItem[]): Promise<void> {
    if (this.triaging.has(taskId)) return;
    this.triaging.add(taskId);
    let ok = false;
    try {
      const t = this.fm.tasks.get(taskId);
      if (!t?.pr) return;
      const lead = this.fm.leadOfTask(t);
      const here = this.fm.agent(lead);
      if (here?.active && !here.paused) this.fm.act(lead, 'reading', 'mergestation', `triaging PR #${t.pr.id}`);
      this.fm.agentLog(lead, 'text', `New on PR #${t.pr.id} (${t.id}): ${items.length} item${items.length === 1 ? '' : 's'} to triage`);
      for (const i of items) this.fm.agentLog(lead, 'text', `${i.ref} [${i.kind}${i.severity ? ` ${i.severity}` : ''}]${i.file ? ` ${i.file}${i.line ? `:${i.line}` : ''}` : ''} ${truncate(i.text, 200)}`);
      await this.pause(2500);
      if (this.stopped) return;
      const verdicts = items.map((i) => simVerdict(i)).filter((v): v is TriageVerdict => !!v);
      this.fm.agentLog(lead, 'tool', `triage ${verdicts.map((v) => `${v.ref}=${v.verdict}`).join(', ')}`);
      const res = this.prs.applyTriage(verdicts);
      ok = res.ok;
      this.fm.agentLog(lead, res.ok ? 'result' : 'error', res.text);
      const after = this.fm.agent(lead);
      if (after?.active && !after.paused && after.state === 'reading') this.fm.act(lead, 'idle', 'mergestation', `PR #${t.pr.id} triaged`);
    } catch (e) {
      this.fm.log.error(`sim triage ${taskId}: ${(e as Error).stack ?? e}`);
    } finally {
      this.triaging.delete(taskId);
    }
    if (!ok && !this.stopped) this.prs.triageTurnEnded(taskId);
    // items that came in while the lead was triaging wait for the next poll
    this.pollTask(taskId);
  }

  /** The watcher sends a task back for review fixes: its worker continues the PR's branch. */
  private startFoldIn(task: Task, notes: string): boolean {
    if (this.stopped || task.status !== 'pr' || !task.repoId || !task.worktree || this.folds.has(task.id)) return false;
    const st = this.state;
    st.folds ??= {};
    st.folds[task.id] = { notes, st: { ...(task.goalId ? { goalId: task.goalId } : {}), repoId: task.repoId, beat: 0, vars: { 'task:f': task.id } } };
    this.fm.store.markDirty();
    this.fm.tasks.setStatus(task.id, 'todo', { force: true });
    this.fm.bus.feed('task', `${task.id} goes back to ${this.fm.nameOf(task.assignee ?? 'the next free worker')} for review fixes on PR #${task.pr?.id}`, { agentId: this.fm.leadOfTask(task), taskId: task.id });
    this.launchFold(task.id);
    return true;
  }

  private launchFold(taskId: string): void {
    const f = this.state.folds?.[taskId];
    if (!f || this.folds.has(taskId)) return;
    const d = new SimDirector(this.fm, { ...this.cfg, showcase: false }, f.st, () => this.fm.store.markDirty());
    const running = (async () => {
      try {
        await this.foldWork(d, taskId, f.notes);
      } catch (e) {
        if (e instanceof Stopped) return;
        this.fm.log.error(`sim review fixes for ${taskId}: ${(e as Error).stack ?? e}`);
        this.fm.bus.feed('error', `Sim review fixes for ${taskId} failed: ${(e as Error).message}`, { taskId });
      } finally {
        this.folds.delete(taskId);
        this.settle();
      }
    })();
    this.folds.set(taskId, { director: d, running });
  }

  private async foldWork(d: SimDirector, taskId: string, notes: string): Promise<void> {
    const lead = () => this.fm.leadOfTask(this.fm.tasks.get(taskId));
    const free = (id: string) => {
      const a = this.fm.agent(id);
      return !!a && a.role === 'worker' && !a.paused && (!a.taskId || a.taskId === taskId) && !this.fm.tasks.list().some((t) => t.id !== taskId && t.assignee === id && t.status === 'doing');
    };
    let worker = typeof d.vars.worker === 'string' ? d.vars.worker : undefined;
    while (!worker) {
      const t = d.task('f');
      const pick = t.assignee && free(t.assignee) ? t.assignee : this.fm.agents().find((a) => free(a.id))?.id;
      if (pick) {
        worker = pick;
        d.vars.worker = pick;
        this.fm.store.markDirty();
      } else await d.sleep(1500);
    }
    const summary = truncate(notes.split('\n').map((l) => l.replace(/^-\s*(\[[^\]]*\]\s*)?/, '').trim()).filter(Boolean).join('; '), 160);
    let t = d.task('f');
    if (t.status === 'todo') {
      // the same worker continues its own branch; another one starts from it
      const prev = t.worktree ? this.fm.repos.findWorktree(d.repoId, t.worktree) : undefined;
      const startPoint = prev && prev.agentId !== worker ? prev.branch : undefined;
      await d.gate(worker);
      d.say(lead(), worker, `${this.fm.nameOf(worker)}, review fixes for ${t.id} on PR #${t.pr?.id}: ${summary}`);
      this.fm.setAgent(worker, { active: true });
      const wt = await this.fm.repos.createWorktree(d.repoId, worker, t, startPoint ? { startPoint } : {});
      this.fm.tasks.update(t.id, { assignee: worker, branch: wt.branch, worktree: wt.id });
      this.fm.tasks.setStatus(t.id, 'doing', { force: true });
      this.fm.setAgent(worker, { taskId: t.id, repoId: d.repoId, worktree: wt.id });
      d.act(worker, 'running', 'terminal', `review fixes for ${t.id}`);
      this.fm.bus.feed('task', `${this.fm.nameOf(worker)} started the review fixes for ${t.id}${t.pr ? ` (PR #${t.pr.id})` : ''}`, { agentId: worker, taskId: t.id });
      await d.sleep(700);
    }
    t = d.task('f');
    const wt = d.wt('f');
    const file = foldFile(notes, wt.path) ?? 'README.md';
    if (t.status === 'doing') {
      await d.think(worker, `Review fixes: ${summary}`, 'desk');
      await d.foldNote(worker, wt.path, file, summary, Math.max(1, this.prs.state(taskId).gen));
      await d.runTests(worker, { taskKey: 'f', worktree: wt });
      await d.commit(worker, 'f', `Address review: ${truncate(summary, 60)}`);
      d.setTask('f', 'review');
      this.fm.bus.feed('task', `${this.fm.nameOf(worker)} finished the review fixes for ${t.id} -> review`, { agentId: worker, taskId: t.id });
      d.act(worker, 'idle', 'lounge', `${t.id} fixes in review`);
    }
    if (d.task('f').status === 'review') {
      await d.requestMerge('f', 1, 'Only the review fixes; tests pass.', lead());
      await d.settleMerge('f', worker, file, lead());
    }
    const st = this.state;
    if (st.folds?.[taskId]) {
      delete st.folds[taskId];
      this.fm.store.markDirty();
    }
  }

  /** The PR was merged (the task is done): its worker is free; the side flow tells the user. */
  private prMerged(task: Task): void {
    if (task.assignee && this.fm.agent(task.assignee)?.taskId === task.id) this.fm.setAgent(task.assignee, { state: 'idle', station: 'lounge', activity: `${task.id} merged`, taskId: null, worktree: null });
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
