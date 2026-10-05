// Foreman core: owns all state, composes the subsystems, applies user intents and exposes the
// primitives backends (sim / claude) use to drive agents. Transport-agnostic: the WS server feeds
// it ClientMessages and subscribes to outbound protocol messages.
import fs from 'node:fs';
import path from 'node:path';
import { MessageBus } from './bus.js';
import { LEAD_ID, loadCast, type CastMember } from './cast.js';
import type { Config } from './config.js';
import { FOREMAN_VERSION } from './config.js';
import { consoleLogger, redactingLogger, type Ctx, type Logger } from './context.js';
import { configSecrets, INHERITED_SECRET_VARS, Redactor } from './redact.js';
import { DecisionError, DecisionQueue, type CreateDecisionInput } from './decisions.js';
import { DesignBook, describeRequest, isFinalDesign, outDirProblem, type Installed } from './designs.js';
import { HOME_LEAD, LeadBook, worldOf } from './leads.js';
import { Memory, MemoryError } from './memory.js';
import { DiscordNotifier, Notifier, type ExternalKind } from './notifier.js';
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
  FeedItem,
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
   * A lead was assigned a building (or its repositories changed) and open goals in those
   * repositories moved to it from other leads (C3): `from` maps goal id -> the previous lead.
   */
  onGoalsAdopted?(leadId: string, goals: Goal[], from: Record<string, string>): void;
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

/**
 * A pull request's description from a task summary (C8): the summary as written, minus lines that
 * advertise the tooling (AgentCraft / Claude attribution, co-author trailers, generated-with lines).
 */
export function prDescription(summary: string | undefined): string {
  const lines = (summary ?? '').replace(/\r\n/g, '\n').split('\n');
  const ad = /^\s*(co-authored-by:|generated with|🤖)|\b(agentcraft|claude code)\b/i;
  return lines.filter((l) => !ad.test(l)).join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

/** What an outside notification is about (notify.discord ping / silent lists). */
export type ExternalNotifyKind = ExternalKind;

export type Reply = (msg: Outbound) => void;

export class ClientError extends Error {}

export interface ForemanOptions {
  config: Config;
  logger?: Logger;
  notifier?: Notifier;
  /** notify.discord sender (tests inject one with a fake spawn) */
  discord?: DiscordNotifier;
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
  /** every secret value this Foreman knows (redact.ts): cut out of logs, feed, acks, errors and notifications */
  readonly redactor = new Redactor();

  private listeners = new Set<(m: Outbound) => void>();
  private logBuffers = new Map<string, LogEntry[]>();
  private logTimer: NodeJS.Timeout | undefined;
  private goalTimers = new Set<string>();
  private closed = false;
  private dailyTimer: NodeJS.Timeout | undefined;
  /** notify.discord (C10); read live from the config */
  readonly discord: DiscordNotifier;
  /** task id -> last status seen (a task newly blocked is announced once) */
  private taskStatusSeen = new Map<string, string>();

  constructor(opts: ForemanOptions) {
    this.config = opts.config;
    this.redactor.add(configSecrets(opts.config));
    // the Claude credentials the Foreman inherited (an API key, an OAuth token): never printed
    this.redactor.add(INHERITED_SECRET_VARS.map((k) => process.env[k]));
    this.log = redactingLogger(opts.logger ?? consoleLogger('foreman', { debug: opts.config.debug, quiet: opts.config.quiet }), (s) => this.redact(s));
    this.store = new Store(opts.config.dataDir, { log: this.log });
    const now = opts.now ?? Date.now;
    this.ctx = { store: this.store, emit: (m) => this.emit(m), now, log: this.log, redact: (s) => this.redact(s) };
    this.tasks = new TaskGraph(this.ctx);
    this.bus = new MessageBus(this.ctx);
    this.memory = new Memory(this.ctx, path.join(opts.config.dataDir, 'memory'));
    this.decisions = new DecisionQueue(this.ctx);
    this.designs = new DesignBook(this.ctx);
    this.repos = new RepoManager(this.ctx, path.join(opts.config.dataDir, 'worktrees'), { mergeStyle: opts.config.mergeStyle, signMerges: opts.config.signMerges, commitIdentity: opts.config.commitIdentity, settings: opts.config.repoSettings });
    this.notifier =
      opts.notifier ??
      new Notifier({ enabled: opts.config.notify, silent: opts.config.toastSilent, log: this.log, now });
    this.leads = new LeadBook(this.ctx, opts.config.claude.leads);
    // (config.json is shared by every profile: a sim / dev run never pings the phone)
    this.discord = opts.discord ?? new DiscordNotifier(() => (this.config.backend === 'claude' ? this.config.notifyDiscord : undefined), { log: this.log });
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

  /** `text` without any secret this Foreman knows (redact.ts). */
  redact(text: string): string {
    return this.redactor.redact(text);
  }

  /** Copies of feed items / log entries with their text redacted (stored text may predate a secret). */
  private redactFeed(items: FeedItem[]): FeedItem[] {
    return items.map((f) => ({ ...f, text: this.redact(f.text) }));
  }

  private redactLog(entries: LogEntry[]): LogEntry[] {
    return entries.map((e) => ({ ...e, text: this.redact(e.text) }));
  }

  /** Copies with the text fields built from errors, tool output or agents' words redacted (ids, paths and branches untouched). */
  private redactTask(t: Task): Task {
    const o = { ...t };
    if (o.blockedReason !== undefined) o.blockedReason = this.redact(o.blockedReason);
    if (o.summary !== undefined) o.summary = this.redact(o.summary);
    return o;
  }

  private redactDecision(d: Decision): Decision {
    const o = { ...d, question: this.redact(d.question) };
    if (o.context !== undefined) o.context = this.redact(o.context);
    if (o.answer?.text !== undefined) o.answer = { ...o.answer, text: this.redact(o.answer.text) };
    return o;
  }

  private redactMemory(e: MemoryEntry): MemoryEntry {
    return { ...e, title: this.redact(e.title), body: this.redact(e.body) };
  }

  private redactDesign(d: Design): Design {
    const o = { ...d, step: this.redact(d.step) };
    if (o.error !== undefined) o.error = this.redact(o.error);
    return o;
  }

  /** More secret values to redact (the client token, set up after the Foreman). */
  addSecrets(values: Iterable<string | undefined>): void {
    this.redactor.add(values);
  }

  subscribe(l: (m: Outbound) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  private emit(m: Outbound): void {
    if (m.type === 'task.upsert' && m.task.goalId) this.scheduleGoalUpdate(m.task.goalId);
    if (m.type === 'task.upsert') {
      const before = this.taskStatusSeen.get(m.task.id);
      this.taskStatusSeen.set(m.task.id, m.task.status);
      if (m.task.status === 'blocked' && before !== undefined && before !== 'blocked') this.notifyExternal('blocked', `${m.task.id} "${truncate(m.task.title, 80)}" is blocked${m.task.blockedReason ? `: ${truncate(m.task.blockedReason, 200)}` : ''}`);
    }
    // a building lead exists for the mod only while it is assigned
    if (m.type === 'agent.upsert' && !this.visible(m.agent)) return;
    // text fields go out without a known secret (copies; stored text may predate a secret learnt later)
    if (m.type === 'agent.say') m = { ...m, text: this.redact(m.text) };
    else if (m.type === 'task.upsert') m = { ...m, task: this.redactTask(m.task) };
    else if (m.type === 'decision.upsert') m = { ...m, decision: this.redactDecision(m.decision) };
    else if (m.type === 'memory.upsert') m = { ...m, entry: this.redactMemory(m.entry) };
    else if (m.type === 'design.upsert') m = { ...m, design: this.redactDesign(m.design) };
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
    if (patch.activity !== undefined) set('activity', truncate(this.redact(patch.activity).replace(/\s+/g, ' ').trim(), 48));
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
    // redacted before it is cut, persisted and broadcast
    const entry: LogEntry = { ts: this.ctx.now(), kind, text: truncate(this.redact(text.replace(/\r\n/g, '\n')), LOG_TEXT_MAX) };
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
    return buildDigest({ goals: d.goals, tasks: d.tasks.map((t) => this.redactTask(t)), decisions: d.decisions.map((x) => this.redactDecision(x)), feed: this.redactFeed(d.feed) }, { since, until: this.ctx.now(), ...(goalId ? { goalId } : {}), nameOf: (id) => (id === 'user' ? userName() : this.nameOf(id)) });
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
    this.leads.touchWorld(worldOf(building));
    const where = building.slice(building.indexOf('/') + 1);
    if (!new Set(repos).size) {
      // a building with no repository needs no lead: free its slot if it had one
      const held = this.leads.leadOfBuilding(building);
      if (held) this.releaseLead(held, 'its building has no repositories any more', opts);
      return { leadId: HOME_LEAD };
    }
    const r = this.leads.assign(building, repos);
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
    // C3: open goals in these repositories are this lead's (idempotent: nothing moves twice)
    this.adoptGoals(r.leadId, this.leads.record(r.leadId)?.repos ?? []);
    // a building whose last repository moved here has nothing left to lead: free that lead
    for (const from of new Set(r.moved.map((m) => m.from))) {
      if (from !== r.leadId && this.leads.record(from) && !this.leads.record(from)!.repos.length) this.releaseLead(from, 'its building has no repositories left', { quiet: true });
    }
    if ((r.changed || r.moved.length) && !opts.quiet) this.emitLeads();
    return { leadId: r.leadId };
  }

  /**
   * C3: open goals (planning / active) whose repository (repoId, else repos[0]) is in `repos` move to
   * `leadId` (a feed line each), with their unread goal messages; the backend hands the work over.
   */
  private adoptGoals(leadId: string, repos: string[]): Goal[] {
    if (!repos.length) return [];
    const moved: Goal[] = [];
    const from: Record<string, string> = {};
    for (const g of this.store.data.goals) {
      if (g.status !== 'planning' && g.status !== 'active') continue;
      const primary = g.repoId ?? g.repos?.[0];
      if (!primary || !repos.includes(primary)) continue;
      const before = this.leadOf(g);
      if (before === leadId) continue;
      if (leadId === HOME_LEAD) delete g.leadId;
      else g.leadId = leadId;
      g.updatedAt = this.ctx.now();
      this.store.markDirty();
      this.emit({ type: 'goal.upsert', goal: goalCopy(g) });
      this.bus.feed('goal', `${this.nameOf(leadId)} takes over ${g.id} "${truncate(g.text, 60)}" from ${this.nameOf(before)} (its repository is in ${this.nameOf(leadId)}'s building)`, { agentId: leadId, goalId: g.id });
      from[g.id] = before;
      moved.push(g);
      // goal messages the previous lead had not read yet go to the new one
      const unread = this.bus.goalInbox(before, g.id);
      for (const m of unread) m.to = leadId;
    }
    if (!moved.length) return moved;
    try {
      this.backend?.onGoalsAdopted?.(leadId, moved, from);
    } catch (e) {
      this.log.error(`backend.onGoalsAdopted: ${(e as Error).message}`);
    }
    for (const g of moved) {
      if (!this.bus.goalInbox(leadId, g.id).length) continue;
      try {
        this.backend?.onGoalMessage?.(g, this.goalLead(g));
      } catch (e) {
        this.log.error(`backend.onGoalMessage: ${(e as Error).message}`);
      }
    }
    return moved;
  }

  /** lead.releaseWorld (C2): free every lead held by that world's buildings. */
  releaseWorld(world: string, why = 'released from the Team tab'): string[] {
    const released: string[] = [];
    for (const b of this.leads.buildingsOf(world)) {
      this.releaseLead(b.leadId, why, { quiet: true });
      released.push(b.leadId);
    }
    const w = this.store.data.leadWorlds;
    if (w && world in w) {
      delete w[world];
      this.store.markDirty();
    }
    if (released.length) this.emitLeads();
    return released;
  }

  /** C2: release the leads of worlds that have not synced for claude.leadWorldTtlDays (start, daily). */
  expireLeadWorlds(): string[] {
    const days = this.config.claude.leadWorldTtlDays;
    const out: string[] = [];
    for (const w of this.leads.expiredWorlds(days * 86_400_000)) {
      this.log.info(`releasing the leads of world "${w}" (not opened for ${days}+ days)`);
      out.push(...this.releaseWorld(w, `world "${w}" not opened for ${days}+ days`));
    }
    return out;
  }

  /** lead.release by building key (unknown building: nothing happens). */
  releaseBuilding(building: string, opts: { quiet?: boolean } = {}): string | undefined {
    if (building.includes('/')) this.leads.touchWorld(worldOf(building));
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
    this.leads.touchWorld(world);
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
        this.notifyExternal('goal_done', `Goal complete: ${truncate(g.text, 200)}`);
      }
    });
  }

  // ---- decisions ----------------------------------------------------------------------------

  createDecision(input: CreateDecisionInput): Decision {
    return this.decisions.create(input);
  }

  /** The note a merge decision carries while its worktree has uncommitted protected edits. */
  private static readonly PROTECTED_NOTE = /\n*Uncommitted edits to protected files[^\n]*(\n[^\n]*)?$/;

  /** A merge decision whose worktree has uncommitted edits to protected files says so (B8). */
  private async noteProtectedEdits(d: Decision): Promise<string[]> {
    if (d.kind !== 'merge' || !d.repoId || !d.worktree || !this.repos.get(d.repoId)) return [];
    const files = await this.repos.protectedUncommitted(d.repoId, d.worktree).catch(() => [] as string[]);
    const cur = this.decisions.get(d.id);
    if (!cur || cur.status !== 'open') return files;
    const base = (cur.context ?? '').replace(Foreman.PROTECTED_NOTE, '');
    const note = files.length ? `Uncommitted edits to protected files: ${files.join(', ')}.\nThe tests ran with them, but they never land: landing is refused until they are dropped (a copy is kept for you).` : '';
    this.decisions.setContext(d.id, `${base}${note ? `${base ? '\n\n' : ''}${note}` : ''}`);
    return files;
  }

  /** Ask (once per merge) whether to drop the protected edits; the Foreman owns the answer. */
  private askDropProtected(d: Decision, files: string[]): void {
    const drops = (this.store.data.protectedDrops ??= {});
    const open = Object.entries(drops).find(([id, x]) => x.mergeDecisionId === d.id && this.decisions.get(id)?.status === 'open');
    if (open) return;
    const q = this.decisions.create({
      agentId: d.agentId,
      kind: 'question',
      question: `${d.taskId ?? d.worktree}: drop the uncommitted edits to ${files.join(', ')}? They are protected files (never committed), so they cannot land.`,
      options: ['Drop them', 'Leave them'],
      textAllowed: false,
      context: `"Drop them" saves the edits under ${this.repos.protectedEditsDir} (a patch to apply by hand in your checkout), removes them from the worktree and runs the tests again; then approve the merge again. "Leave them" keeps them; the merge stays refused while they are there.`,
      ...(d.taskId ? { taskId: d.taskId } : {}),
      ...(d.goalId ? { goalId: d.goalId } : {}),
    });
    drops[q.id] = { repoId: d.repoId!, worktree: d.worktree!, mergeDecisionId: d.id, ...(d.taskId ? { taskId: d.taskId } : {}) };
    this.store.markDirty();
  }

  /** A decision the Foreman itself asked (not an agent's question; backends leave it alone). */
  ownsDecision(id: string): boolean {
    return !!this.store.data.protectedDrops?.[id];
  }

  /** The user answered a protected-edits question (Foreman-owned, not an agent's). */
  private async applyProtectedDrop(d: Decision): Promise<void> {
    const drops = this.store.data.protectedDrops ?? {};
    const target = drops[d.id];
    delete drops[d.id];
    this.store.markDirty();
    if (!target || d.status !== 'answered') return;
    const merge = this.decisions.get(target.mergeDecisionId);
    if (d.answer?.option !== 'Drop them') {
      this.bus.feed('merge', `Kept the protected edits in ${target.worktree}; landing stays refused while they are there`, { agentId: 'user', goalId: d.goalId });
      return;
    }
    const res = await this.repos.dropProtectedEdits(target.repoId, target.worktree, target.taskId ?? target.worktree);
    if (!res.dropped.length) {
      this.bus.feed('merge', `No protected edits left in ${target.worktree}`, { agentId: 'user', goalId: d.goalId });
    } else {
      this.bus.feed('merge', `Dropped the edits to ${res.dropped.join(', ')} from ${target.worktree}; saved for you at ${res.saved}`, { agentId: 'user', goalId: d.goalId });
      this.notify('info', `Protected edits saved to ${res.saved}`);
    }
    // the tests ran with those edits: run them again without
    const task = target.taskId ? this.tasks.get(target.taskId) : undefined;
    const wt = this.repos.findWorktree(target.repoId, target.worktree);
    let tests = '';
    if (wt && wt.status === 'active') {
      try {
        if (task) this.tasks.update(task.id, { ci: 'running' });
        const ci = await this.repos.runTests(target.repoId, wt.id, this.repos.testCommand(target.repoId, wt.path, this.config.claude.ciCommand));
        if (task) this.tasks.update(task.id, { ci: ci.pass ? 'pass' : 'fail' });
        this.repos.setCi(target.repoId, ci.pass ? 'pass' : 'fail');
        tests = `tests without them: ${ci.pass ? 'pass' : 'FAIL'} (${ci.command})`;
        this.bus.feed('ci', `${task?.id ?? wt.id}: ${tests}`, { ...(task?.assignee ? { agentId: task.assignee } : {}), ...(task ? { taskId: task.id } : {}) });
      } catch (e) {
        this.log.warn(`tests after dropping protected edits: ${(e as Error).message}`);
      }
    }
    if (merge && merge.status === 'open') {
      const base = (merge.context ?? '').replace(/\n*Merge refused: [\s\S]*$/, '').replace(Foreman.PROTECTED_NOTE, '');
      this.decisions.setContext(merge.id, `${base}\n\nProtected edits dropped (saved at ${res.saved ?? 'n/a'})${tests ? `; ${tests}` : ''}. Approve again to land.`);
    }
  }

  private onDecisionCreated(d: Decision): void {
    if (d.kind === 'merge') void this.noteProtectedEdits(d).catch((e) => this.log.warn(`protected edits for ${d.id}: ${(e as Error).message}`));
    const who = this.nameOf(d.agentId);
    const label = d.kind === 'merge' ? 'merge review' : d.kind === 'permission' ? 'permission' : 'question';
    this.bus.feed('decision', `${who} needs you (${label}): ${d.question}`, { agentId: d.agentId, to: 'user', goalId: d.goalId });
    this.notify('need_user', `${who}: ${truncate(d.question, 120)}`, d.id);
    this.notifyExternal('need_user', `${who}: ${truncate(d.question, 200)}`);
    this.notifier.needUser(this.redact(`${who}: ${d.question}`));
  }

  /**
   * Outside-the-game notifications (C10, notify.discord): `kind` picks the channel level. Never
   * blocks and never throws; off unless configured.
   */
  notifyExternal(kind: ExternalNotifyKind, text: string): void {
    try {
      this.discord.send(kind, this.redact(text));
    } catch (e) {
      this.log.warn(`notify.discord: ${(e as Error).message}`);
    }
  }

  notify(level: 'info' | 'warn' | 'need_user', text: string, decisionId?: string): void {
    const m: Outbound = {
      type: 'notify',
      level,
      text: this.redact(text),
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
    if (this.store.data.protectedDrops?.[d.id]) {
      // the Foreman's own question: never handed to the backend (it is no agent's ask_user)
      try {
        await this.applyProtectedDrop(d);
      } finally {
        this.decisions.settle(d.id);
      }
      return d;
    }
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
        // B8: uncommitted edits to protected files ran in CI but would not land: refuse until resolved
        const pending = d.repoId && d.worktree ? await this.repos.protectedUncommitted(d.repoId, d.worktree) : [];
        if (pending.length) {
          this.askDropProtected(d, pending);
          throw new RepoError(`uncommitted edits to protected files (${pending.join(', ')}) are in the worktree: the tests ran with them, but they would not land. Drop them (the question next to this one; a copy is kept) or take them out yourself, then approve again.`, 'refused');
        }
        const res = await this.repos.land(
          d,
          task
            ? {
                commitMessage: task.pr ? `${task.id}: address review feedback on PR #${task.pr.id}${task.summary ? `\n\n${task.summary}` : ''}` : `${task.id}: ${task.title}${task.summary ? `\n\n${task.summary}` : ''}`,
                title: task.title,
                // the worker's summary only: no tool attribution, no review transcript (C8)
                description: prDescription(task.summary),
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
    // a status / hold message can quote an auth probe's error
    const next = { ...this.status, ...patch, ...(patch.message !== undefined ? { message: this.redact(patch.message) } : {}), ...(patch.account !== undefined ? { account: this.redact(patch.account) } : {}) };
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
      tasks: this.tasks.list().map((t) => this.redactTask({ ...t, deps: [...t.deps] })),
      decisions: [...recent, ...open].sort((a, b) => a.createdAt - b.createdAt).map((d) => this.redactDecision(d)),
      repos: this.repos.list().map((r) => this.repos.view(r)),
      memory: this.memory.list().map((e) => this.redactMemory(e)),
      ...(goal ? { goal: goalCopy(goal) } : {}),
      goals: this.goals().map(goalCopy),
      // stored text is cut again on the way out: it may predate a secret the Foreman learnt later
      feed: this.redactFeed(this.store.data.feed.slice(-200)),
      logs: this.agents().map((a) => ({ agentId: a.id, entries: this.redactLog(this.store.logTail(a.id).slice(-60)) })),
      designs: this.designs.recent().map((d) => this.redactDesign(d)),
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
      // and whatever a message still carries of a known secret (a spawn error quoting a value) is cut
      const message = this.redact(known ? (e as Error).message : e instanceof SyntaxError ? 'internal error: invalid JSON' : `internal error: ${(e as Error).message}`);
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
      case 'agent.logs.request': {
        const id = this.resolveAgentId(msg.agentId);
        if (!id) throw new ClientError(`no agent named "${msg.agentId}"`);
        const page = this.store.readLog(id, msg.before, msg.limit ?? 200);
        return { agentId: id, ...page, entries: this.redactLog(page.entries) };
      }
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
      case 'lead.releaseWorld':
        return { released: this.releaseWorld(msg.world) };
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
        if (!this.backend?.onPrRefresh || !this.backend.watchesPrs?.()) throw new ClientError('pull requests are not watched (claude.prWatch "observe" or "on" watches them, with the claude or sim backend)');
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
    // the new values are secrets from now on (restart-only ones too: the file holds them)
    this.addSecrets(configSecrets(res.next));
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
    this.repos.setCommitIdentity(c.commitIdentity);
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
    // leads held by worlds nobody opened for a long time (a dev world, a test or dead save), and
    // worktrees / branches of tasks finished long ago
    this.daily();
    this.dailyTimer = setInterval(() => this.daily(), 86_400_000);
    this.dailyTimer.unref?.();
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

  /** Once a day (and at start): housekeeping. */
  private daily(): void {
    try {
      this.expireLeadWorlds();
    } catch (e) {
      this.log.error(`daily: ${(e as Error).message}`);
    }
    void this.cleanup().catch((e) => this.log.warn(`cleanup: ${(e as Error).message}`));
  }

  /**
   * cleanupAfterDays: remove the worktrees and local agentcraft/* branches of tasks finished long
   * ago (RepoManager.sweepFinished). The very first sweep is a dry run: it logs and announces what it
   * would remove; sweeps from 12 h later on act.
   */
  async cleanup(now = this.ctx.now()): Promise<{ dryRun: boolean; worktrees: string[]; branches: string[]; kept: string[] } | undefined> {
    const days = this.config.cleanupAfterDays;
    if (!days || this.closed) return undefined;
    const armed = this.store.data.cleanupArmedAt;
    const dryRun = armed === undefined || now - armed < 12 * 3_600_000;
    const res = await this.repos.sweepFinished({
      olderThanMs: days * 86_400_000,
      dryRun,
      task: (id) => this.tasks.get(id),
      busy: (repoId, wt) => this.decisions.open().some((d) => d.repoId === repoId && d.worktree === wt) || this.agents().some((a) => a.worktree === wt && a.repoId === repoId),
    });
    if (armed === undefined) {
      this.store.data.cleanupArmedAt = now;
      this.store.markDirty();
    }
    const what = `${res.worktrees.length} worktree${res.worktrees.length === 1 ? '' : 's'} and ${res.branches.length} branch${res.branches.length === 1 ? '' : 'es'} of tasks finished more than ${days} days ago`;
    if (res.worktrees.length || res.branches.length) {
      for (const x of res.worktrees) this.log.info(`cleanup${dryRun ? ' (dry run)' : ''}: worktree ${x}`);
      for (const x of res.branches) this.log.info(`cleanup${dryRun ? ' (dry run)' : ''}: branch ${x}`);
      this.bus.feed('system', dryRun ? `Cleanup (first run, nothing removed): would remove ${what}; from tomorrow this runs daily (cleanupAfterDays, 0 = off)` : `Cleanup: removed ${what}`);
    }
    for (const x of res.kept) this.log.info(`cleanup: kept ${x}`);
    return { dryRun, ...res };
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    if (this.dailyTimer) clearInterval(this.dailyTimer);
    this.repos.stopPolling();
    try {
      await this.backend?.stop();
    } catch (e) {
      this.log.error(`backend stop: ${(e as Error).message}`);
    }
    this.flushLogs();
    this.notifier.dispose();
    this.discord.dispose();
    this.store.close();
  }
}
