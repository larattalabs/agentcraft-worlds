// Foreman core: owns all state, composes the subsystems, applies user intents and exposes the
// primitives backends (sim / claude) use to drive agents. Transport-agnostic: the WS server feeds
// it ClientMessages and subscribes to outbound protocol messages.
import fs from 'node:fs';
import path from 'node:path';
import { MessageBus } from './bus.js';
import { LEAD_ID, loadCast, type CastMember } from './cast.js';
import type { Config } from './config.js';
import { FOREMAN_VERSION } from './config.js';
import { consoleLogger, type Ctx, type Logger } from './context.js';
import { DecisionError, DecisionQueue, type CreateDecisionInput } from './decisions.js';
import { DesignBook, describeRequest, isFinalDesign, outDirProblem, type Installed } from './designs.js';
import { HOME_LEAD, LeadBook } from './leads.js';
import { Memory, MemoryError } from './memory.js';
import { Notifier } from './notifier.js';
import type {
  Agent,
  AgentState,
  ClientMessage,
  Decision,
  Design,
  Digest,
  DesignRequest,
  ForemanStatus,
  Goal,
  GoalStatus,
  LogEntry,
  LogKind,
  MemoryEntry,
  Outbound,
  Station,
  Task,
} from './protocol.js';
import { parsePrUrl } from './prs.js';
import { RepoError, RepoManager } from './repos.js';
import { Store } from './store.js';
import { TaskError, TaskGraph } from './taskgraph.js';
import { setUserName, userName } from './user.js';
import { truncate } from './util/text.js';
import { unifiedDiff } from './util/udiff.js';
import { buildDigest } from './digest.js';
import { applyLive, ConfigError, configGet, configSet, listRepoAgents, pendingRestart, restartBaseline } from './settings.js';

export interface Backend {
  readonly name: 'sim' | 'claude';
  /** Called once after the core is ready (and after restart: resume work). */
  start(): Promise<void>;
  stop(): Promise<void>;
  submitGoal(goal: Goal): Promise<void>;
  onUserMessage(to: string, text: string): void;
  /** After a decision was answered and its side effects (merge etc.) applied. */
  onDecisionSettled(d: Decision): void;
  /**
   * An approved merge conflicts with the base branch (another task merged first). Return true when
   * the backend sent the task back to its worker to merge the base and resolve it; false (or no
   * method) leaves the merge decision open with the reason, for the user to handle.
   */
  onMergeConflict?(task: Task, info: { base: string; branch: string; files: string[]; reason: string }): boolean;
  onTaskAction(task: Task, action: 'reassign' | 'cancel' | 'retry' | 'prioritize', arg?: string): void;
  onAgentAction(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn', arg?: string): Promise<void> | void;
  /**
   * A building design was requested (status queued), or is picked up again after a restart. The
   * backend runs it (one at a time) and reports through Foreman.designStep / designDone /
   * designFailed. Without this method the backend cannot design (design.request is refused).
   */
  onDesignRequest?(design: Design): void;
  /** The user cancelled a design (already marked cancelled): stop its turn, or drop it from the queue. */
  onDesignCancel?(designId: string): void;
  /**
   * Pull requests are watched (docs/PRWATCH.md): a task landed as a PR stays in status `pr` until the
   * PR is merged. Without this (or false) landing a PR finishes the task.
   */
  watchesPrs?(): boolean;
  /**
   * A task's PR work ended: its first push opened the PR (`opened`), review fixes were pushed to it
   * (`landed`), or a fold-in ended without a push (`empty`: nothing new, `rejected` by the user).
   */
  onPrPush?(task: Task, outcome: 'opened' | 'landed' | 'empty' | 'rejected', sha?: string): void;
  /** pr.refresh: poll the PR of this task (or of every task in `pr`) now */
  onPrRefresh?(taskId?: string): void;
  /** a building lead was assigned (it is on duty and visible now) */
  onLeadAssigned?(leadId: string): void;
  /**
   * A building lead was released (lead.release / lead.sync, or dropped from claude.leads): its turns
   * end, and `goals` (its open goals) are marlow's now.
   */
  onLeadReleased?(leadId: string, goals: Goal[]): void;
  /**
   * goal.message: the user's message (unread in bus.goalInbox(leadId, goal.id)) to `leadId` about
   * `goal`: answer it in a turn of the lead's session for the goal, replies tagged with the goal.
   */
  onGoalMessage?(goal: Goal, leadId: string): void;
  /** goal.cancel (the goal and its open tasks are cancelled already): stop the lead's work on it */
  onGoalCancel?(goal: Goal): void;
  /**
   * config.set copied live settings into the config the backend holds (in place): pick up what is
   * not read afresh anyway (PR watching, the status line) and schedule again (concurrency).
   */
  onConfigChanged?(keys: string[]): void;
}

export interface GoalOptions {
  /** the repositories the goal is for (repoId first) */
  repos?: string[];
  /** the user's branch it continues */
  branch?: string;
  /** standing instructions */
  instructions?: string[];
}

/** "on <branch>: ..." at the start of a goal's text */
export const GOAL_BRANCH_RE = /^\s*on\s+([\w./-]+)\s*:\s*/i;

/** Trimmed, non-empty instruction lines. */
export function cleanInstructions(list: string[] | undefined): string[] {
  return (list ?? []).map((x) => x.replace(/\s+/g, ' ').trim()).filter(Boolean);
}

/** A goal for the wire (arrays copied). */
export function goalCopy(g: Goal): Goal {
  return { ...g, ...(g.repos ? { repos: [...g.repos] } : {}), ...(g.instructions ? { instructions: [...g.instructions] } : {}), ...(g.prs ? { prs: g.prs.map((p) => ({ ...p })) } : {}) };
}

export type Reply = (msg: Outbound) => void;

export class ClientError extends Error {}

export interface ForemanOptions {
  config: Config;
  logger?: Logger;
  notifier?: Notifier;
  now?: () => number;
}

const LOG_TEXT_MAX = 2000;

export class Foreman {
  readonly config: Config;
  readonly store: Store;
  readonly ctx: Ctx;
  readonly tasks: TaskGraph;
  readonly bus: MessageBus;
  readonly memory: Memory;
  readonly decisions: DecisionQueue;
  readonly repos: RepoManager;
  readonly designs: DesignBook;
  /** who leads which building (leads.ts) */
  readonly leads: LeadBook;
  readonly notifier: Notifier;
  readonly log: Logger;
  readonly cast: CastMember[];
  backend: Backend | undefined;
  status: ForemanStatus;
  /** where clients reach this Foreman (set by main once the server listens): the agent policy keeps agents away from both */
  endpoint: { port: number; tokenFile?: string } | undefined;
  /** foreman.restart: set by main (re-spawns the process); without it restarting is refused */
  restarter: (() => void) | undefined;
  /** the restart-only settings as this Foreman started (settings.ts pendingRestart) */
  private restartBase: Map<string, string>;

  private listeners = new Set<(m: Outbound) => void>();
  private logBuffers = new Map<string, LogEntry[]>();
  private logTimer: NodeJS.Timeout | undefined;
  private goalTimers = new Set<string>();
  private closed = false;

  constructor(opts: ForemanOptions) {
    this.config = opts.config;
    this.log = opts.logger ?? consoleLogger('foreman', { debug: opts.config.debug, quiet: opts.config.quiet });
    this.store = new Store(opts.config.dataDir);
    const now = opts.now ?? Date.now;
    this.ctx = { store: this.store, emit: (m) => this.emit(m), now, log: this.log };
    this.tasks = new TaskGraph(this.ctx);
    this.bus = new MessageBus(this.ctx);
    this.memory = new Memory(this.ctx, path.join(opts.config.dataDir, 'memory'));
    this.decisions = new DecisionQueue(this.ctx);
    this.designs = new DesignBook(this.ctx);
    this.repos = new RepoManager(this.ctx, path.join(opts.config.dataDir, 'worktrees'), { mergeStyle: opts.config.mergeStyle, signMerges: opts.config.signMerges, settings: opts.config.repoSettings });
    this.notifier =
      opts.notifier ??
      new Notifier({ enabled: opts.config.notify, silent: opts.config.toastSilent, log: this.log, now });
    this.leads = new LeadBook(this.ctx, opts.config.claude.leads);
    const { cast, source } = loadCast(opts.config.projectRoot, opts.config.claude.leads);
    this.cast = cast;
    this.log.debug(`cast from ${source}`);
    this.restartBase = restartBaseline(opts.config, cast);
    setUserName(opts.config.userName);
    this.status = { version: FOREMAN_VERSION, backend: opts.config.backend, auth: opts.config.backend === 'sim' ? 'ok' : 'unknown', userName: userName() };
    if (opts.config.backend === 'sim') this.status.message = 'Simulated team (sim backend)';
    this.initRoster();
    this.decisions.onCreated((d) => this.onDecisionCreated(d));
    // leads no longer in claude.leads: their buildings go back to marlow
    for (const id of this.leads.stale()) this.releaseLead(id, 'no longer in claude.leads');
  }

  // ---- event plumbing ---------------------------------------------------------------------

  subscribe(l: (m: Outbound) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  private emit(m: Outbound): void {
    if (m.type === 'task.upsert' && m.task.goalId) this.scheduleGoalUpdate(m.task.goalId);
    // a building lead exists for the mod only while it is assigned
    if (m.type === 'agent.upsert' && !this.visible(m.agent)) return;
    for (const l of this.listeners) {
      try {
        l(m);
      } catch (e) {
        this.log.error(`listener failed: ${(e as Error).message}`);
      }
    }
  }

  // ---- roster -----------------------------------------------------------------------------

  private initRoster(): void {
    const agents = this.store.data.agents;
    for (const c of this.cast) {
      let a = agents.find((x) => x.id === c.id);
      if (!a) {
        a = {
          id: c.id,
          name: c.name,
          role: c.role,
          title: this.config.claude.agents[c.id]?.title ?? c.title,
          color: c.color,
          skin: c.id,
          state: 'idle',
          activity: c.id === LEAD_ID ? 'ready for a goal' : 'off shift',
          station: 'lounge',
          paused: false,
          active: c.id === LEAD_ID || (c.role === 'lead' && this.leads.onDuty(c.id)),
        };
        if (c.accent) a.accent = c.accent;
        agents.push(a);
      } else {
        // cast may have been updated by the art track
        a.name = c.name;
        a.title = this.config.claude.agents[c.id]?.title ?? c.title;
        a.color = c.color;
        if (c.accent) a.accent = c.accent;
        a.role = c.role;
      }
    }
    this.store.markDirty();
  }

  /** The roster as the mod sees it: building leads only while they are assigned. */
  agents(): Agent[] {
    return this.store.data.agents.filter((a) => this.visible(a));
  }

  private visible(a: Agent): boolean {
    return a.role !== 'lead' || this.leads.onDuty(a.id);
  }

  /** Any agent, including building leads that are off duty (not assigned). */
  agent(id: string): Agent | undefined {
    return this.store.data.agents.find((a) => a.id === id);
  }

  requireAgent(id: string): Agent {
    const a = this.agent(id);
    if (!a) throw new ClientError(`no agent "${id}"`);
    return a;
  }

  /** Resolve "@Kit", "kit", "Kit" -> "kit". */
  resolveAgentId(nameOrId: string): string | undefined {
    const n = nameOrId.replace(/^@/, '').trim().toLowerCase();
    return this.agents().find((a) => a.id === n || a.name.toLowerCase() === n)?.id;
  }

  nameOf(id: string): string {
    if (id === 'user') return `${userName()}`;
    return this.agent(id)?.name ?? id;
  }

  /** Patch an agent and broadcast if anything changed. */
  setAgent(
    id: string,
    patch: Partial<Pick<Agent, 'state' | 'station' | 'activity' | 'paused' | 'active' | 'title'>> & {
      taskId?: string | null;
      repoId?: string | null;
      worktree?: string | null;
    },
  ): Agent {
    const a = this.requireAgent(id);
    let changed = false;
    const set = <K extends 'state' | 'station' | 'activity' | 'paused' | 'active' | 'title'>(k: K, v: Agent[K] | undefined) => {
      if (v !== undefined && a[k] !== v) {
        a[k] = v;
        changed = true;
      }
    };
    set('state', patch.state);
    set('station', patch.station);
    if (patch.activity !== undefined) set('activity', truncate(patch.activity.replace(/\s+/g, ' ').trim(), 48));
    set('paused', patch.paused);
    set('active', patch.active);
    set('title', patch.title);
    for (const k of ['taskId', 'repoId', 'worktree'] as const) {
      const v = patch[k];
      if (v === undefined) continue;
      if (v === null) {
        if (a[k] !== undefined) {
          delete a[k];
          changed = true;
        }
      } else if (a[k] !== v) {
        a[k] = v;
        changed = true;
      }
    }
    if (changed) {
      this.store.markDirty();
      this.emit({ type: 'agent.upsert', agent: { ...a } });
    }
    return a;
  }

  /** Convenience: state + station + activity in one call. */
  act(id: string, state: AgentState, station: Station, activity: string): void {
    this.setAgent(id, { state, station, activity });
  }

  // ---- logs ---------------------------------------------------------------------------------

  /** Append a log line to an agent monitor. Batched (~30ms) into agent.log frames. */
  agentLog(agentId: string, kind: LogKind, text: string): void {
    if (this.closed) return;
    const entry: LogEntry = { ts: this.ctx.now(), kind, text: truncate(text.replace(/\r\n/g, '\n'), LOG_TEXT_MAX) };
    this.store.appendLog(agentId, [entry]);
    const buf = this.logBuffers.get(agentId) ?? [];
    buf.push(entry);
    this.logBuffers.set(agentId, buf);
    if (!this.logTimer) {
      this.logTimer = setTimeout(() => this.flushLogs(), 30);
      this.logTimer.unref?.();
    }
  }

  flushLogs(): void {
    if (this.logTimer) clearTimeout(this.logTimer);
    this.logTimer = undefined;
    for (const [agentId, entries] of this.logBuffers) {
      if (entries.length) this.emit({ type: 'agent.log', agentId, entries });
    }
    this.logBuffers.clear();
  }

  // ---- goals --------------------------------------------------------------------------------

  goals(): Goal[] {
    return this.store.data.goals;
  }

  currentGoal(): Goal | undefined {
    const g = this.store.data.goals;
    return g[g.length - 1];
  }

  goal(id: string): Goal | undefined {
    return this.store.data.goals.find((g) => g.id === id);
  }

  createGoal(text: string, repoId?: string, opts: GoalOptions = {}): Goal {
    const now = this.ctx.now();
    const goal: Goal = { id: this.store.nextId('g'), text: text.trim(), progress: 0, status: 'planning', createdAt: now, updatedAt: now };
    if (repoId) goal.repoId = repoId;
    const repos = [...new Set([...(repoId ? [repoId] : []), ...(opts.repos ?? [])])];
    if (repos.length) goal.repos = repos;
    const instructions = cleanInstructions(opts.instructions);
    if (instructions.length) goal.instructions = instructions;
    if (opts.branch) goal.branch = opts.branch;
    // the lead of the building that has the repository (absent = marlow)
    const lead = this.leads.leadForRepo(repoId);
    if (lead !== HOME_LEAD) goal.leadId = lead;
    this.store.data.goals.push(goal);
    this.store.markDirty();
    this.emit({ type: 'goal.upsert', goal: goalCopy(goal) });
    this.bus.feed('goal', `New goal: ${goal.text}`, { agentId: 'user', goalId: goal.id });
    return goal;
  }

  /** Broadcast a goal after a direct change of its fields. */
  touchGoal(g: Goal): void {
    g.updatedAt = this.ctx.now();
    this.store.markDirty();
    this.emit({ type: 'goal.upsert', goal: goalCopy(g) });
  }

  /** The goal's plan note id (Goal.planId) was written: record it. */
  recordPlan(goalId: string, memoryId: string): void {
    const g = this.goal(goalId);
    if (!g || g.planId === memoryId) return;
    g.planId = memoryId;
    this.touchGoal(g);
  }

  /** The user's branch a goal continues could not be used: the goal is a normal one. */
  clearGoalBranch(goalId: string): void {
    const g = this.goal(goalId);
    if (!g?.branch) return;
    delete g.branch;
    this.touchGoal(g);
  }

  /** Standing instructions of a goal as a prompt section ('' without any). */
  instructionsSection(goalId: string | undefined, heading = '# Standing instructions'): string {
    const list = goalId ? this.goal(goalId)?.instructions : undefined;
    if (!list?.length) return '';
    return `${heading}\n${userName()}'s standing instructions for goal ${goalId} (they apply to all of its work and can change between turns; this list is the current one):\n${list.map((i) => `- ${i}`).join('\n')}`;
  }

  /** The lead that takes a goal's messages now: its lead, or marlow when that lead is off duty. */
  goalLead(goal: Goal): string {
    const lead = this.leadOf(goal);
    return this.leads.onDuty(lead) ? lead : HOME_LEAD;
  }

  requireGoal(id: string): Goal {
    const g = this.goal(id);
    if (!g) throw new ClientError(`no goal "${id}"`);
    return g;
  }

  /**
   * goal.message: the user's message to the goal's lead about it. Runs in the lead's session for the
   * goal (backend.onGoalMessage), also for done/cancelled goals.
   */
  goalMessage(goalId: string, text: string, feedText?: string): { goalId: string; leadId: string } {
    const g = this.requireGoal(goalId);
    const body = text.trim();
    if (!body) throw new ClientError('the message is empty');
    if (!this.backend) throw new ClientError('no backend running');
    const lead = this.goalLead(g);
    this.bus.send('user', lead, body, { goalId: g.id, goalMessage: true, ...(feedText ? { feedText } : {}) });
    try {
      this.backend.onGoalMessage?.(g, lead);
    } catch (e) {
      this.log.error(`backend.onGoalMessage: ${(e as Error).message}`);
    }
    return { goalId: g.id, leadId: lead };
  }

  /** goal.instructions: replace the standing instructions; a change goes to the lead as a goal message. */
  setGoalInstructions(goalId: string, instructions: string[]): { goalId: string; changed: boolean } {
    const g = this.requireGoal(goalId);
    const next = cleanInstructions(instructions);
    const prev = g.instructions ?? [];
    if (JSON.stringify(prev) === JSON.stringify(next)) return { goalId: g.id, changed: false };
    if (next.length) g.instructions = next;
    else delete g.instructions;
    this.touchGoal(g);
    const list = next.length ? next.map((i) => `- ${i}`).join('\n') : '(none any more)';
    if (this.backend) this.goalMessage(g.id, `The user changed the standing instructions for this goal:\n${list}\n\nThey apply to all of its work from now on: new tasks get them, and workers see them at their next turn. Adjust the open tasks if they need it.`, `Changed the standing instructions:\n${list}`);
    return { goalId: g.id, changed: true };
  }

  /**
   * goal.plan: write the goal's plan note as the user (created when missing), then send the lead a
   * goal message with the diff.
   */
  editGoalPlan(goalId: string, body: string): { goalId: string; planId: string; changed: boolean } {
    const g = this.requireGoal(goalId);
    const prev = g.planId ? this.memory.get(g.planId) : undefined;
    const slug = prev ? prev.id.slice(prev.id.indexOf('/') + 1) : `plan-${g.id}`;
    const scope = prev?.scope ?? 'shared';
    const oldBody = prev?.body ?? '';
    if (prev && oldBody === body) return { goalId: g.id, planId: prev.id, changed: false };
    let e: MemoryEntry;
    try {
      e = this.memory.write({ scope, slug, title: prev?.title ?? `Plan: ${truncate(g.text.replace(/\s+/g, ' '), 60)}`, body, author: 'user' });
    } catch (err) {
      if (err instanceof MemoryError) throw new ClientError(err.message);
      throw err;
    }
    this.recordPlan(g.id, e.id);
    this.bus.feed('memory', `${userName()} ${prev ? 'edited' : 'wrote'} the plan for ${g.id}: ${e.title}`, { agentId: 'user', goalId: g.id });
    const diff = unifiedDiff(oldBody, body, { from: `${e.id} (before)`, to: `${e.id} (${userName()})` });
    if (this.backend) this.goalMessage(g.id, `The user ${prev ? 'edited' : 'wrote'} the plan for this goal (shared memory ${e.id}). The change:\n\`\`\`diff\n${diff}\n\`\`\`\nAdjust the tasks to the plan where they no longer match (create, update or cancel tasks), and tell ${userName()} briefly what you changed.`, `${prev ? 'Edited' : 'Wrote'} the plan:\n\`\`\`diff\n${diff}\n\`\`\``);
    return { goalId: g.id, planId: e.id, changed: true };
  }

  /**
   * goal.cancel: every open task is cancelled (running workers stop, worktrees kept), open
   * decisions about the goal are withdrawn, the goal is cancelled. All synchronous, so the goal is
   * already cancelled when the task updates are looked at.
   */
  cancelGoal(goalId: string): string[] {
    const g = this.requireGoal(goalId);
    if (g.status === 'done') throw new ClientError(`goal ${g.id} is already done`);
    if (g.status === 'cancelled') return [];
    const open = this.tasks.forGoal(g.id).filter((t) => t.status !== 'done' && t.status !== 'cancelled');
    // the goal first: the task updates below must not re-open or close it on their own
    this.setGoal(g.id, { status: 'cancelled' });
    for (const t of open) this.taskAction(t.id, 'cancel');
    for (const d of this.decisions.open().filter((x) => x.goalId === g.id)) this.decisions.cancel(d.id, 'goal cancelled');
    this.bus.feed('goal', `${userName()} cancelled goal ${g.id} "${truncate(g.text, 60)}"${open.length ? ` (${open.length} open task${open.length === 1 ? '' : 's'} cancelled: ${open.map((t) => t.id).join(', ')})` : ''}`, { agentId: 'user', goalId: g.id });
    try {
      this.backend?.onGoalCancel?.(g);
    } catch (e) {
      this.log.error(`backend.onGoalCancel: ${(e as Error).message}`);
    }
    return open.map((t) => t.id);
  }

  /** goal.digest: what happened since `since` (pure, over the stored feed, tasks and decisions). */
  digest(since: number, goalId?: string): Digest {
    if (goalId) this.requireGoal(goalId);
    const d = this.store.data;
    return buildDigest({ goals: d.goals, tasks: d.tasks, decisions: d.decisions, feed: d.feed }, { since, until: this.ctx.now(), ...(goalId ? { goalId } : {}), nameOf: (id) => (id === 'user' ? userName() : this.nameOf(id)) });
  }

  /** repo.remove: refused while it has open tasks or a goal still being planned. */
  removeRepo(repoId: string): void {
    const r = this.repos.get(repoId);
    if (!r) throw new ClientError(`no repo "${repoId}"`);
    const open = this.tasks.list().filter((t) => t.repoId === repoId && t.status !== 'done' && t.status !== 'cancelled');
    if (open.length) throw new ClientError(`${r.name} has open tasks (${open.map((t) => `${t.id} ${t.status}`).join(', ')}): finish or cancel them first`);
    const planning = this.goals().filter((g) => g.repoId === repoId && g.status === 'planning');
    if (planning.length) throw new ClientError(`${r.name} has goals still being planned (${planning.map((g) => g.id).join(', ')}): wait for the plan or cancel them first`);
    this.repos.remove(repoId);
    this.bus.feed('system', `Repo removed: ${r.name} (worktrees and branches are left on disk)`, { agentId: 'user' });
  }

  setGoal(id: string, patch: { status?: GoalStatus; progress?: number; text?: string }): Goal {
    const g = this.goal(id);
    if (!g) throw new ClientError(`no goal ${id}`);
    let changed = false;
    if (patch.status && g.status !== patch.status) {
      g.status = patch.status;
      changed = true;
    }
    if (patch.progress !== undefined && Math.abs(g.progress - patch.progress) > 1e-6) {
      g.progress = Math.max(0, Math.min(1, patch.progress));
      changed = true;
    }
    if (patch.text && patch.text !== g.text) {
      g.text = patch.text;
      changed = true;
    }
    if (changed) {
      g.updatedAt = this.ctx.now();
      this.store.markDirty();
      this.emit({ type: 'goal.upsert', goal: goalCopy(g) });
    }
    return g;
  }

  // ---- leads ----------------------------------------------------------------------------------

  /** The lead running a goal: Goal.leadId, absent = marlow. */
  leadOf(goal: Goal | undefined): string {
    return goal?.leadId ?? HOME_LEAD;
  }

  /** The lead responsible for a task: its goal's lead, else the lead of its repository's building. */
  leadOfTask(task: Task | undefined): string {
    const g = task?.goalId ? this.goal(task.goalId) : undefined;
    return g ? this.leadOf(g) : this.leads.leadForRepo(task?.repoId);
  }

  /** A lead (role lead), on duty or not. */
  isLead(id: string): boolean {
    return id === HOME_LEAD || this.agent(id)?.role === 'lead';
  }

  /** The newest goal a lead runs (its "current" goal: follow-ups and answers resume its session). */
  currentGoalOf(leadId: string): Goal | undefined {
    const g = this.store.data.goals;
    for (let i = g.length - 1; i >= 0; i--) if (this.leadOf(g[i]) === leadId) return g[i];
    return undefined;
  }

  private emitLeads(): void {
    this.emit({ type: 'leads.update', leads: this.leads.list() });
  }

  /** lead.assign: see LeadBook.assign. */
  assignLead(building: string, repos: string[], opts: { quiet?: boolean } = {}): { leadId: string; overflow?: true } {
    const r = this.leads.assign(building, repos);
    const where = building.slice(building.indexOf('/') + 1);
    if (r.overflow) {
      this.bus.feed('system', `No free lead for building ${where}: ${this.nameOf(HOME_LEAD)} leads it`, { agentId: HOME_LEAD });
      return { leadId: HOME_LEAD, overflow: true };
    }
    for (const m of r.moved) this.bus.feed('system', `${m.repo} moved from ${this.nameOf(m.from)}'s building to ${this.nameOf(r.leadId)}'s`, { agentId: r.leadId });
    if (r.created) {
      const a = this.agent(r.leadId);
      if (a) {
        a.active = true;
        a.paused = false;
        a.state = 'idle';
        a.station = 'meeting';
        a.activity = 'ready for a goal';
        delete a.taskId;
        delete a.worktree;
        delete a.repoId;
        this.store.markDirty();
        this.emit({ type: 'agent.upsert', agent: { ...a } });
      }
      this.bus.feed('system', `${this.nameOf(r.leadId)} leads building ${where}${repos.length ? ` (${repos.join(', ')})` : ''}`, { agentId: r.leadId });
      try {
        this.backend?.onLeadAssigned?.(r.leadId);
      } catch (e) {
        this.log.error(`backend.onLeadAssigned: ${(e as Error).message}`);
      }
    }
    if (r.changed && !opts.quiet) this.emitLeads();
    return { leadId: r.leadId };
  }

  /** lead.release by building key (unknown building: nothing happens). */
  releaseBuilding(building: string, opts: { quiet?: boolean } = {}): string | undefined {
    const id = this.leads.leadOfBuilding(building);
    if (id) this.releaseLead(id, 'its building was removed', opts);
    return id;
  }

  /**
   * Free a building lead: one last agent.upsert (off shift, lounge) so the mod walks it home, its
   * open goals move to marlow (who gets the plan when it next works on them), the backend ends its
   * turns.
   */
  releaseLead(leadId: string, why: string, opts: { quiet?: boolean } = {}): void {
    const rec = this.leads.record(leadId);
    if (!rec) return;
    if (this.agent(leadId)) this.setAgent(leadId, { active: false, paused: false, state: 'idle', station: 'lounge', activity: 'off shift', taskId: null, worktree: null, repoId: null });
    this.leads.remove(leadId);
    const moved: Goal[] = [];
    for (const g of this.store.data.goals) {
      if (g.leadId !== leadId || (g.status !== 'planning' && g.status !== 'active')) continue;
      delete g.leadId;
      g.updatedAt = this.ctx.now();
      this.store.markDirty();
      this.emit({ type: 'goal.upsert', goal: goalCopy(g) });
      this.bus.feed('goal', `${this.nameOf(HOME_LEAD)} takes over ${g.id} "${truncate(g.text, 60)}" from ${this.nameOf(leadId)}`, { agentId: HOME_LEAD, goalId: g.id });
      moved.push(g);
    }
    this.bus.feed('system', `${this.nameOf(leadId)} no longer leads building ${rec.building.slice(rec.building.indexOf('/') + 1)} (${why})`, { agentId: leadId });
    try {
      this.backend?.onLeadReleased?.(leadId, moved);
    } catch (e) {
      this.log.error(`backend.onLeadReleased: ${(e as Error).message}`);
    }
    // goal messages it had not read yet go to whoever takes the goal's messages now
    const unread = this.bus.goalInbox(leadId);
    for (const m of unread) {
      const g = m.goalId ? this.goal(m.goalId) : undefined;
      if (g) m.to = this.goalLead(g);
    }
    if (unread.length) this.store.markDirty();
    for (const goalId of new Set(unread.map((m) => m.goalId!))) {
      const g = this.goal(goalId);
      if (!g) continue;
      try {
        this.backend?.onGoalMessage?.(g, this.goalLead(g));
      } catch (e) {
        this.log.error(`backend.onGoalMessage: ${(e as Error).message}`);
      }
    }
    if (!opts.quiet) this.emitLeads();
  }

  /** lead.sync: release the world's buildings that are gone, then assign every listed one. */
  syncLeads(world: string, buildings: Array<{ building: string; repos: string[] }>): Record<string, string> {
    const keep = new Set(buildings.map((b) => b.building));
    const before = JSON.stringify(this.leads.list());
    for (const b of this.leads.buildingsOf(world)) if (!keep.has(b.building)) this.releaseLead(b.leadId, 'its building is gone', { quiet: true });
    const out: Record<string, string> = {};
    for (const b of buildings) out[b.building] = this.assignLead(b.building, b.repos, { quiet: true }).leadId;
    if (JSON.stringify(this.leads.list()) !== before) this.emitLeads();
    return out;
  }

  /** Goal.repos (repoId, then each task's repo by first appearance) and Goal.prs from its tasks. */
  private updateGoalDerived(g: Goal, tasks: Task[]): void {
    const repos = [...new Set([...(g.repoId ? [g.repoId] : []), ...(g.repos ?? []), ...tasks.map((t) => t.repoId).filter((x): x is string => !!x)])];
    const prs = tasks.filter((t) => t.pr).map((t) => ({ taskId: t.id, url: t.pr!.url, id: t.pr!.id, status: t.pr!.status }));
    let changed = false;
    if (repos.length && JSON.stringify(repos) !== JSON.stringify(g.repos ?? [])) {
      g.repos = repos;
      changed = true;
    }
    if (JSON.stringify(prs) !== JSON.stringify(g.prs ?? [])) {
      if (prs.length) g.prs = prs;
      else delete g.prs;
      changed = true;
    }
    if (changed) this.touchGoal(g);
  }

  private scheduleGoalUpdate(goalId: string): void {
    if (this.goalTimers.has(goalId)) return;
    this.goalTimers.add(goalId);
    queueMicrotask(() => {
      this.goalTimers.delete(goalId);
      const g = this.goal(goalId);
      if (!g) return;
      const all = this.tasks.forGoal(goalId);
      this.updateGoalDerived(g, all);
      const live = all.filter((t) => t.status !== 'cancelled');
      // every task was cancelled or rejected: the goal is over (it would sit at 0% forever);
      // if the lead adds a new task to it later, it is active again
      if (g.status === 'active' && all.length && !live.length) {
        this.setGoal(goalId, { status: 'cancelled', progress: 0 });
        this.bus.feed('goal', `Goal closed: every task was cancelled or rejected (${truncate(g.text, 80)})`, { goalId });
        return;
      }
      // a cancelled goal is active again once it has open work (the lead added or retried a task);
      // tasks done before a goal.cancel do not re-open it
      if (g.status === 'cancelled' && live.some((t) => t.status !== 'done')) this.setGoal(goalId, { status: 'active' });
      // progress stays 0 while the lead is still planning (avoids a jittering ring)
      if (g.status === 'cancelled' || g.status === 'failed' || g.status === 'planning') return;
      const progress = this.tasks.progress(goalId);
      const complete = this.tasks.goalComplete(goalId);
      const wasDone = g.status === 'done';
      this.setGoal(goalId, { progress: complete ? 1 : progress, ...(complete ? { status: 'done' as const } : g.status === 'done' ? { status: 'active' as const } : {}) });
      if (complete && !wasDone) {
        this.bus.feed('goal', `Goal complete: ${g.text}`, { goalId });
        this.notify('info', `Goal complete: ${truncate(g.text, 80)}`);
      }
    });
  }

  // ---- decisions ----------------------------------------------------------------------------

  createDecision(input: CreateDecisionInput): Decision {
    return this.decisions.create(input);
  }

  private onDecisionCreated(d: Decision): void {
    const who = this.nameOf(d.agentId);
    const label = d.kind === 'merge' ? 'merge review' : d.kind === 'permission' ? 'permission' : 'question';
    this.bus.feed('decision', `${who} needs you (${label}): ${d.question}`, { agentId: d.agentId, to: 'user', goalId: d.goalId });
    this.notify('need_user', `${who}: ${truncate(d.question, 120)}`, d.id);
    this.notifier.needUser(`${who}: ${d.question}`);
  }

  notify(level: 'info' | 'warn' | 'need_user', text: string, decisionId?: string): void {
    const m: Outbound = {
      type: 'notify',
      level,
      text,
      ts: this.ctx.now(),
      ...(decisionId ? { decisionId } : {}),
    };
    this.emit(m);
  }

  /** Answer a decision, run kind-specific side effects, then wake the waiting agent. */
  async answerDecision(id: string, option?: string | number, text?: string): Promise<Decision> {
    let d: Decision;
    try {
      d = this.decisions.answer(id, option, text);
    } catch (e) {
      if (e instanceof DecisionError) throw new ClientError(e.message);
      throw e;
    }
    const answerText = [d.answer?.option, d.answer?.text].filter(Boolean).join(' — ');
    this.bus.feed('decision', `${userName()} answered ${this.nameOf(d.agentId)}: ${answerText}`, { agentId: 'user', to: d.agentId, goalId: d.goalId });
    if (d.kind === 'merge') await this.applyMergeAnswer(d);
    if (d.status === 'answered' || d.status === 'cancelled') {
      this.decisions.settle(d.id);
      try {
        this.backend?.onDecisionSettled(d);
      } catch (e) {
        this.log.error(`backend.onDecisionSettled: ${(e as Error).message}`);
      }
    }
    return d;
  }

  private async applyMergeAnswer(d: Decision): Promise<void> {
    const task = d.taskId ? this.tasks.get(d.taskId) : undefined;
    const option = d.answer?.option;
    if (option === 'Merge') {
      try {
        const res = await this.repos.land(
          d,
          task
            ? {
                commitMessage: task.pr ? `${task.id}: address review feedback on PR #${task.pr.id}${task.summary ? `\n\n${task.summary}` : ''}` : `${task.id}: ${task.title}${task.summary ? `\n\n${task.summary}` : ''}`,
                title: task.title,
                description: [task.summary, d.context?.split('\n')[0], `Built and reviewed in AgentCraft (${task.id}), approved by ${userName()}.`].filter(Boolean).join('\n\n'),
              }
            : {},
        );
        if (task && res.kind === 'merge') this.tasks.setStatus(task.id, 'done', { viaMerge: true, force: task.status !== 'review' });
        if (res.kind === 'merge') {
          this.bus.feed('merge', `Merged ${res.branch} into ${res.base} (${res.sha}, ${res.files} file${res.files === 1 ? '' : 's'})${res.pushed ? `; ${res.pushed}` : ''}`, { agentId: d.agentId, goalId: d.goalId });
          this.notify('info', `Merged ${res.branch} into ${res.base}${res.pushed ? ` (${res.pushed})` : ''}`);
        } else {
          // a PR that is watched keeps the task open (status pr) until it is merged
          const ref = res.url ? parsePrUrl(res.url) : undefined;
          const watched = !!ref && !!this.backend?.watchesPrs?.();
          const followUp = !!task?.pr && res.updated;
          const what = res.url ? (followUp ? `Pushed the review fixes to the pull request ${res.url} (${res.sha.slice(0, 7)})` : `${res.updated ? 'Updated the pull request' : 'Opened a pull request'} ${res.url}`) : `Pushed ${res.remoteBranch} (open the pull request yourself)`;
          if (task) {
            if (!followUp) this.tasks.update(task.id, { summary: `${what}${task.summary ? `\n${task.summary}` : ''}` });
            if (watched && ref) {
              const now = this.ctx.now();
              const prev = task.pr;
              this.tasks.update(task.id, {
                pr: prev ? { ...prev, updatedAt: now } : { url: res.url!, id: ref.id, host: ref.host, branch: res.remoteBranch, target: res.base, status: 'open', checks: 'pending', threads: { open: 0, new: 0 }, updatedAt: now },
              });
              this.tasks.setStatus(task.id, 'pr', { force: true });
            } else this.tasks.setStatus(task.id, 'done', { viaMerge: true, force: task.status !== 'review' });
          }
          this.bus.feed('merge', `${task?.id ?? res.branch}: ${what} (${res.remoteBranch} → ${res.base})${watched && !followUp ? '; watching it until it is merged' : ''}`, { agentId: d.agentId, goalId: d.goalId });
          this.notify('info', `${task?.id ?? res.branch}: ${what}`);
          if (task && watched) this.prPush(task.id, followUp ? 'landed' : 'opened', res.sha);
        }
      } catch (e) {
        if (e instanceof RepoError && e.code === 'empty' && task?.pr && d.repoId && d.worktree) {
          // review fixes that changed nothing: the PR stays as it is
          await this.repos.abandon(d.repoId, d.worktree, `agentcraft: ${task.id} review fixes (no changes)`).catch((err) => this.log.warn(`abandon: ${(err as Error).message}`));
          this.tasks.setStatus(task.id, 'pr', { force: true });
          this.bus.feed('merge', `Nothing new to push for ${task.id}: PR #${task.pr.id} stays as it is`, { agentId: d.agentId, goalId: d.goalId });
          this.prPush(task.id, 'empty');
          return;
        }
        if (e instanceof RepoError && e.code === 'empty' && task && d.repoId && d.worktree) {
          // nothing to merge (a report or investigation): the task is simply done
          await this.repos.abandon(d.repoId, d.worktree, `agentcraft: ${task.id} (no changes)`).catch((err) => this.log.warn(`abandon: ${(err as Error).message}`));
          this.tasks.setStatus(task.id, 'done', { force: true });
          this.bus.feed('merge', `Nothing to merge for ${task.id} (no file changes): closed as done`, { agentId: d.agentId, goalId: d.goalId });
          this.notify('info', `${task.id} had no changes: closed as done`);
          return;
        }
        const reason = e instanceof RepoError ? e.message : `merge failed: ${(e as Error).message}`;
        if (e instanceof RepoError && e.code === 'conflict' && task?.assignee && d.repoId && d.worktree && this.backend?.onMergeConflict) {
          // parallel tasks touched the same lines: the branch's worker merges the base and resolves
          // it, then the task comes back through review with a fresh merge decision
          const wt = this.repos.findWorktree(d.repoId, d.worktree);
          const info = { base: wt?.base ?? 'main', branch: wt?.branch ?? d.worktree, files: e.files, reason };
          let handled = false;
          try {
            handled = this.backend.onMergeConflict(task, info);
          } catch (err) {
            this.log.error(`backend.onMergeConflict: ${(err as Error).message}`);
          }
          if (handled) {
            this.log.info(`merge for ${d.id} conflicts with ${info.base} (${e.files.join(', ')}): sent back to ${task.assignee}`);
            this.bus.feed('merge', `${task.id} conflicts with ${info.base} in ${e.files.join(', ') || 'some files'}: ${this.nameOf(task.assignee)} merges ${info.base} and resolves it`, { agentId: task.assignee, goalId: d.goalId });
            this.notify('info', `${task.id} conflicts with ${info.base}: sent back to ${this.nameOf(task.assignee)} to resolve`);
            return;
          }
        }
        this.log.warn(`merge for ${d.id} refused: ${reason}`);
        const base = (d.context ?? '').replace(/\n*Merge refused: [\s\S]*$/, '');
        this.decisions.reopen(d.id, `${base}${base ? '\n\n' : ''}Merge refused: ${reason}`);
        this.bus.feed('error', `Merge refused: ${reason}`, { agentId: d.agentId, goalId: d.goalId });
        this.notify('warn', `Merge refused: ${truncate(reason, 160)}`, d.id);
        // broadcast the repo as it is now (e.g. dirty=true), so the mod can show why
        if (d.repoId && this.repos.get(d.repoId)) await this.repos.refresh(d.repoId).catch((err) => this.log.warn(`refresh ${d.repoId}: ${(err as Error).message}`));
      }
    } else if (option === 'Request changes') {
      if (task && task.status !== 'doing') {
        this.tasks.setStatus(task.id, 'doing', { force: true });
        if (d.answer?.text) this.tasks.update(task.id, { summary: `Changes requested: ${d.answer.text}` });
      }
    } else if (option === 'Reject') {
      if (d.repoId && d.worktree) await this.repos.abandon(d.repoId, d.worktree).catch((e) => this.log.warn(`abandon: ${(e as Error).message}`));
      if (task?.pr && task.pr.status !== 'merged' && task.pr.status !== 'abandoned') {
        // rejected review fixes: the pull request itself stays open and watched
        this.tasks.setStatus(task.id, 'pr', { force: true });
        this.bus.feed('merge', `Rejected the review fixes for ${task.id} (branch kept); PR #${task.pr.id} stays open`, { agentId: d.agentId, goalId: d.goalId });
        this.prPush(task.id, 'rejected');
        return;
      }
      if (task) {
        this.tasks.setStatus(task.id, 'cancelled', { force: true });
        for (const dep of this.tasks.dependents(task.id)) {
          if (dep.status === 'todo') this.tasks.setStatus(dep.id, 'blocked', { reason: `depends on rejected ${task.id}`, force: true });
        }
      }
      this.bus.feed('merge', `Rejected ${d.worktree ?? 'branch'} (branch kept for recovery)`, { agentId: d.agentId, goalId: d.goalId });
    }
  }

  private prPush(taskId: string, outcome: 'opened' | 'landed' | 'empty' | 'rejected', sha?: string): void {
    const t = this.tasks.get(taskId);
    if (!t) return;
    try {
      this.backend?.onPrPush?.(t, outcome, sha);
    } catch (e) {
      this.log.error(`backend.onPrPush: ${(e as Error).message}`);
    }
  }

  // ---- foreman status -------------------------------------------------------------------------

  setStatus(patch: Partial<ForemanStatus>): void {
    const next = { ...this.status, ...patch };
    for (const k of Object.keys(next) as Array<keyof ForemanStatus>) if (next[k] === undefined) delete next[k];
    if (JSON.stringify(next) === JSON.stringify(this.status)) return;
    this.status = next;
    this.emit({ type: 'foreman.status', status: { ...this.status } });
  }

  // ---- snapshot -----------------------------------------------------------------------------

  snapshot(): Outbound {
    this.flushLogs();
    const decisions = this.store.data.decisions;
    const open = decisions.filter((d) => d.status === 'open');
    const recent = decisions.filter((d) => d.status !== 'open').slice(-20);
    const goal = this.currentGoal();
    return {
      type: 'snapshot',
      foreman: { ...this.status },
      agents: this.agents().map((a) => ({ ...a })),
      tasks: this.tasks.list().map((t) => ({ ...t, deps: [...t.deps] })),
      decisions: [...recent, ...open].sort((a, b) => a.createdAt - b.createdAt),
      repos: this.repos.list().map((r) => this.repos.view(r)),
      memory: this.memory.list(),
      ...(goal ? { goal: goalCopy(goal) } : {}),
      goals: this.goals().map(goalCopy),
      feed: this.store.data.feed.slice(-200),
      logs: this.agents().map((a) => ({ agentId: a.id, entries: this.store.logTail(a.id).slice(-60) })),
      designs: this.designs.recent(),
      leads: this.leads.list(),
    };
  }

  // ---- inbound ------------------------------------------------------------------------------

  /** Apply a client intent. `reply` sends to the originating client only. */
  async handle(msg: ClientMessage, reply: Reply): Promise<void> {
    const ack = (ok: boolean, extra: { error?: string; result?: Record<string, unknown> } = {}) => {
      if (msg.id) reply({ type: 'ack', re: msg.id, ok, ...extra });
    };
    try {
      const result = await this.dispatch(msg, reply);
      ack(true, result ? { result } : {});
    } catch (e) {
      const known = e instanceof ClientError || e instanceof ConfigError || e instanceof TaskError || e instanceof RepoError || e instanceof DecisionError || e instanceof MemoryError;
      // a JSON.parse message can quote the text it failed on (a file with secrets): never forwarded
      const message = known ? (e as Error).message : e instanceof SyntaxError ? 'internal error: invalid JSON' : `internal error: ${(e as Error).message}`;
      if (!known) this.log.error(`${msg.type}: ${(e as Error).stack ?? e}`);
      reply({ type: 'error', message, ...(msg.id ? { re: msg.id } : {}) });
      ack(false, { error: message });
    }
  }

  private async dispatch(msg: ClientMessage, reply: Reply): Promise<Record<string, unknown> | undefined> {
    switch (msg.type) {
      case 'hello':
        reply(this.snapshot());
        return undefined;
      case 'goal.submit':
        return { goalId: (await this.submitGoal(msg.text, msg.repoId, { ...(msg.repos ? { repos: msg.repos } : {}), ...(msg.branch ? { branch: msg.branch } : {}), ...(msg.instructions ? { instructions: msg.instructions } : {}) })).id };
      case 'goal.message':
        return this.goalMessage(msg.goalId, msg.text);
      case 'goal.instructions':
        return this.setGoalInstructions(msg.goalId, msg.instructions);
      case 'goal.plan':
        return this.editGoalPlan(msg.goalId, msg.body);
      case 'goal.cancel':
        return { goalId: msg.goalId, cancelled: this.cancelGoal(msg.goalId) };
      case 'goal.digest':
        return this.digest(msg.since, msg.goalId) as unknown as Record<string, unknown>;
      case 'repo.remove':
        this.removeRepo(msg.repoId);
        return { repoId: msg.repoId };
      case 'user.message': {
        const { to, text } = this.routeUserMessage(msg.to, msg.text);
        this.bus.send('user', to, text);
        this.backend?.onUserMessage(to, text);
        return { to };
      }
      case 'decision.answer': {
        const d = await this.answerDecision(msg.decisionId, msg.option, msg.text);
        return { decisionId: d.id, status: d.status };
      }
      case 'task.action':
        this.taskAction(msg.taskId, msg.action, msg.arg);
        return { taskId: msg.taskId };
      case 'agent.action':
        await this.agentAction(msg.agentId, msg.action, msg.arg);
        return { agentId: msg.agentId };
      case 'diff.request': {
        try {
          const d = await this.repos.diff(msg.repoId, msg.worktree);
          reply({ type: 'diff', requestId: msg.requestId, repoId: d.repoId, worktree: d.worktree, base: d.base, branch: d.branch, files: d.files, stats: d.stats, truncated: d.truncated });
        } catch (e) {
          reply({ type: 'diff', requestId: msg.requestId, repoId: msg.repoId, worktree: msg.worktree, files: [], stats: { files: 0, additions: 0, deletions: 0 }, truncated: false, error: (e as Error).message });
        }
        return undefined;
      }
      case 'repo.add': {
        const r = await this.repos.add(msg.path);
        this.bus.feed('system', `Repo connected: ${r.name} (${r.branch})`);
        return { repoId: r.id };
      }
      case 'design.request': {
        const d = this.requestDesign(msg.request);
        return { designId: d.id, id: d.id };
      }
      case 'design.cancel':
        this.cancelDesign(msg.designId);
        return { designId: msg.designId };
      case 'lead.assign':
        return this.assignLead(msg.building, msg.repos);
      case 'lead.release':
        this.releaseBuilding(msg.building);
        return {};
      case 'lead.sync':
        return { leads: this.syncLeads(msg.world, msg.buildings) };
      case 'config.get':
        return configGet({ cfg: this.config, cast: this.cast, ...(msg.repoId ? { repo: this.repoTarget(msg.repoId) } : {}) }) as unknown as Record<string, unknown>;
      case 'config.set':
        return this.setConfig(msg.repoId, msg.changes);
      case 'foreman.restart': {
        const restart = this.restarter;
        if (!restart) throw new ClientError('this Foreman cannot restart itself (not started by main)');
        // after the ack is on its way
        setTimeout(restart, 150).unref?.();
        return {};
      }
      case 'repo.agents':
        return { agents: listRepoAgents(this.repoTarget(msg.repoId).path) };
      case 'pr.refresh': {
        if (!this.backend?.onPrRefresh || !this.backend.watchesPrs?.()) throw new ClientError('pull requests are not watched (claude backend with claude.prWatch "observe" or "on")');
        if (msg.taskId && this.tasks.get(msg.taskId)?.status !== 'pr') throw new ClientError(`task ${msg.taskId} has no open pull request`);
        this.backend.onPrRefresh(msg.taskId);
        return msg.taskId ? { taskId: msg.taskId } : {};
      }
    }
  }

  // ---- settings -----------------------------------------------------------------------------

  private repoTarget(repoId: string): { id: string; path: string } {
    const r = this.repos.get(repoId);
    if (!r) throw new ClientError(`no repo "${repoId}"`);
    return { id: r.id, path: r.path };
  }

  /** config.set: write config.json, apply the live settings, announce the change. */
  setConfig(repoId: string | undefined, changes: Array<{ key: string; value: unknown }>): Record<string, unknown> {
    const repo = repoId ? this.repoTarget(repoId) : undefined;
    const res = configSet({ cfg: this.config, cast: this.cast, ...(repo ? { repo } : {}) }, changes);
    const before = { titles: Object.fromEntries(this.cast.map((c) => [c.id, this.config.claude.agents[c.id]?.title])) };
    applyLive(this.config, res.next);
    this.afterConfigApplied(before.titles);
    const pending = pendingRestart(this.restartBase, res.next, this.cast);
    this.setStatus({ restartRequired: pending.length ? pending : undefined });
    const keys = changes.map((c) => (repo ? `repo:${repo.id}:${c.key}` : c.key));
    const what = [...res.applied, ...res.restartRequired, ...res.overridden.map((o) => o.key)];
    this.log.info(`config.set${repo ? ` (${repo.id})` : ''}: ${what.join(', ')}${res.restartRequired.length ? ` (after a restart: ${res.restartRequired.join(', ')})` : ''}`);
    this.emit({ type: 'config.changed', keys, restartRequired: pending });
    try {
      this.backend?.onConfigChanged?.(keys);
    } catch (e) {
      this.log.error(`backend.onConfigChanged: ${(e as Error).message}`);
    }
    return { applied: res.applied, restartRequired: res.restartRequired, overridden: res.overridden };
  }

  /** What the live settings change outside the config object itself. */
  private afterConfigApplied(oldTitles: Record<string, string | undefined>): void {
    const c = this.config;
    setUserName(c.userName);
    this.notifier.setEnabled(c.notify);
    this.notifier.setSilent(c.toastSilent);
    this.repos.setMergeOptions(c.mergeStyle, c.signMerges);
    this.setStatus({ userName: userName() });
    for (const m of this.cast) {
      const title = c.claude.agents[m.id]?.title;
      if (title !== oldTitles[m.id] && this.agent(m.id)) this.setAgent(m.id, { title: title ?? m.title });
    }
    // the repositories' settings views, and their base branch (applied on refresh)
    for (const r of this.repos.list()) {
      this.repos.announce(r.id);
      if (fs.existsSync(r.path)) void this.repos.refresh(r.id).catch((e) => this.log.warn(`refresh ${r.id}: ${(e as Error).message}`));
    }
  }

  // ---- building designs ---------------------------------------------------------------------

  requestDesign(request: DesignRequest): Design {
    if (!this.backend) throw new ClientError('no backend running');
    if (!this.backend.onDesignRequest) throw new ClientError(`the ${this.backend.name} backend cannot design buildings`);
    if (this.status.auth === 'failed') throw new ClientError(`Claude is not available: ${this.status.message ?? 'auth failed'}`);
    const bad = outDirProblem(request.outDir);
    if (bad) throw new ClientError(`outDir ${bad}`);
    const d = this.designs.create(request);
    const queued = this.designs.active().filter((x) => x.id !== d.id).length;
    this.bus.feed('system', `Design ${d.id} requested: ${describeRequest(request)}${queued ? ` (${queued} ahead in the queue)` : ''}`, { agentId: 'user' });
    this.backend.onDesignRequest(d);
    return d;
  }

  cancelDesign(id: string): Design {
    const d = this.designs.get(id);
    if (!d) throw new ClientError(`no design "${id}"`);
    if (isFinalDesign(d)) throw new ClientError(`design ${id} is already ${d.status}`);
    this.designs.update(id, { status: 'cancelled', step: `cancelled by ${userName()}` });
    this.bus.feed('system', `Design ${id} cancelled by ${userName()}`, { agentId: 'user' });
    try {
      this.backend?.onDesignCancel?.(id);
    } catch (e) {
      this.log.error(`backend.onDesignCancel: ${(e as Error).message}`);
    }
    return d;
  }

  /** A design job's progress (status + one line). Ignored once the design is final. */
  designStep(id: string, status: 'queued' | 'designing' | 'checking' | 'rendering', step: string): void {
    const before = this.designs.get(id);
    const first = before?.status === 'queued' && status === 'designing' && !before.step.startsWith('usage limit');
    this.designs.update(id, { status, step });
    if (first && before) this.bus.feed('system', `Design ${id} started: ${describeRequest(before.request)}`);
  }

  designDone(id: string, installed: Installed, size: { x: number; y: number; z: number }, note = ''): void {
    const d = this.designs.get(id);
    if (!d || isFinalDesign(d)) return;
    this.designs.update(id, {
      status: 'done',
      step: `done: ${installed.blueprintId} (${size.x}x${size.y}x${size.z}), ${installed.previews.length} preview${installed.previews.length === 1 ? '' : 's'}${note ? `; ${note}` : ''}`,
      blueprintId: installed.blueprintId,
      size,
      previews: installed.previews,
    });
    this.bus.feed('system', `Design ${id} is ready: blueprint ${installed.blueprintId} (${size.x}x${size.y}x${size.z}). Open the hub to review and place it.`);
    this.notify('info', `New building design ready: ${installed.blueprintId}`);
  }

  designFailed(id: string, error: string): void {
    const d = this.designs.get(id);
    if (!d || isFinalDesign(d)) return;
    this.designs.update(id, { status: 'failed', step: `failed: ${error.split('\n')[0]}`, error });
    this.bus.feed('error', `Design ${id} failed: ${truncate(error.split('\n')[0] ?? error, 160)}`);
    this.notify('warn', `Building design ${id} failed: ${truncate(error.split('\n')[0] ?? error, 120)}`);
  }

  async submitGoal(text: string, repoId?: string, opts: GoalOptions = {}): Promise<Goal> {
    for (const id of opts.repos ?? []) if (!this.repos.get(id)) throw new ClientError(`no repo "${id}"`);
    const rid = repoId ?? opts.repos?.[0];
    const repo = rid ? this.repos.get(rid) : this.repos.defaultRepo();
    if (rid && !repo) throw new ClientError(`no repo "${rid}"`);
    if (!repo) throw new ClientError('no repo connected yet — add one with /repo add <path>');
    if (!this.backend) throw new ClientError('no backend running');
    // "on <branch>: ..." continues one of the user's branches; an explicit branch wins
    const branch = opts.branch ?? GOAL_BRANCH_RE.exec(text)?.[1];
    const goal = this.createGoal(text, repo.id, { ...opts, ...(branch ? { branch } : {}) });
    await this.backend.submitGoal(goal);
    return goal;
  }

  routeUserMessage(to: string, text: string): { to: string; text: string } {
    let target = to;
    let body = text.trim();
    if (to === 'all') {
      const m = /^@([\w-]+)[\s,:]+([\s\S]+)$/.exec(body);
      if (m) {
        const id = this.resolveAgentId(m[1]!);
        if (!id) throw new ClientError(`no agent named "${m[1]}"`);
        target = id;
        body = m[2]!.trim();
      }
    } else {
      const id = this.resolveAgentId(to);
      if (!id) throw new ClientError(`no agent named "${to}"`);
      target = id;
    }
    return { to: target, text: body };
  }

  taskAction(taskId: string, action: 'reassign' | 'cancel' | 'retry' | 'prioritize', arg?: string): void {
    const t = this.tasks.require(taskId);
    switch (action) {
      case 'cancel':
        this.tasks.setStatus(t.id, 'cancelled', { force: true });
        for (const d of this.decisions.open().filter((d) => d.taskId === t.id)) this.decisions.cancel(d.id, 'task cancelled');
        this.bus.feed('task', `Task ${t.id} cancelled by ${userName()}: ${t.title}`, { agentId: 'user', taskId: t.id });
        break;
      case 'retry':
        this.tasks.update(t.id, { ci: 'unknown', blockedReason: null });
        this.tasks.setStatus(t.id, 'todo', { force: true });
        this.bus.feed('task', `Task ${t.id} queued again: ${t.title}`, { agentId: 'user', taskId: t.id });
        break;
      case 'prioritize': {
        const top = Math.max(0, ...this.tasks.list().map((x) => x.priority));
        const p = arg !== undefined && arg !== '' && Number.isFinite(Number(arg)) ? Math.trunc(Number(arg)) : top + 1;
        this.tasks.update(t.id, { priority: p });
        this.bus.feed('task', `Task ${t.id} priority -> ${p}`, { agentId: 'user', taskId: t.id });
        break;
      }
      case 'reassign': {
        if (!arg) throw new ClientError('reassign needs an agent id');
        const id = this.resolveAgentId(arg);
        if (!id) throw new ClientError(`no agent named "${arg}"`);
        if (this.agent(id)?.role === 'lead') throw new ClientError('tasks are assigned to workers, not the lead');
        this.tasks.update(t.id, { assignee: id });
        if (t.status === 'doing') this.tasks.setStatus(t.id, 'todo', { force: true });
        this.bus.feed('task', `Task ${t.id} reassigned to ${this.nameOf(id)}`, { agentId: 'user', taskId: t.id });
        break;
      }
    }
    this.backend?.onTaskAction(this.tasks.require(taskId), action, arg);
  }

  async agentAction(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn', arg?: string): Promise<void> {
    const id = this.resolveAgentId(agentId);
    if (!id) throw new ClientError(`no agent named "${agentId}"`);
    if (action === 'pause') this.setAgent(id, { paused: true });
    if (action === 'resume') this.setAgent(id, { paused: false });
    if (action === 'spawn' && arg && !this.tasks.get(arg)) throw new ClientError(`no task "${arg}"`);
    if (action === 'spawn' && arg && this.agent(id)?.role === 'lead') throw new ClientError('tasks are assigned to workers, not the lead');
    if (action === 'spawn') this.setAgent(id, { active: true, paused: false });
    this.bus.feed('system', `${this.nameOf(id)}: ${action}${arg ? ` ${arg}` : ''}`, { agentId: 'user' });
    await this.backend?.onAgentAction(id, action, arg);
    // spawn @wren t3: on shift, and t3 is hers
    if (action === 'spawn' && arg) this.taskAction(arg, 'reassign', id);
  }

  // ---- lifecycle ----------------------------------------------------------------------------

  async start(backend: Backend): Promise<void> {
    this.backend = backend;
    for (const p of this.config.repos) {
      try {
        const r = await this.repos.add(p);
        this.log.info(`repo ${r.id}: ${r.path} (${r.branch})`);
      } catch (e) {
        this.log.error(`could not add repo ${p}: ${(e as Error).message}`);
      }
    }
    for (const r of this.repos.list()) {
      if (!fs.existsSync(r.path)) {
        this.log.warn(`repo ${r.id} path is gone: ${r.path}`);
        continue;
      }
      await this.repos.refresh(r.id).catch((e) => this.log.warn(`refresh ${r.id}: ${(e as Error).message}`));
    }
    this.repos.startPolling(this.config.repoPollMs);
    await backend.start();
    // designs that were queued or running when the Foreman stopped
    for (const d of this.designs.active()) {
      if (!backend.onDesignRequest) {
        this.designFailed(d.id, `the ${backend.name} backend cannot design buildings`);
        continue;
      }
      this.designs.update(d.id, { status: 'queued', step: 'picked up again after a restart' });
      backend.onDesignRequest(d);
    }
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.repos.stopPolling();
    try {
      await this.backend?.stop();
    } catch (e) {
      this.log.error(`backend stop: ${(e as Error).message}`);
    }
    this.flushLogs();
    this.notifier.dispose();
    this.store.close();
  }
}
