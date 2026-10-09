// The agent team: a lead per building and shared workers doing real work, each turn run by an
// engine (../engine.ts): the Claude Agent SDK or the Codex app-server, chosen per agent (a team can
// mix them). This folder is upstream's team.ts split into layers (fork wave 3); upstream edits to
// team.ts are ported here by hand (docs/FORK.md "Upstream sync 2026-10").
//
// Each agent processes a queue of jobs, one engine turn per job:
//   plan    lead explores the repo (read-only), writes the plan, creates tasks
//   work    worker implements a task in its own git worktree
//   review  lead reviews a finished task (diff + CI) -> request_merge or changes
//   followup resume an agent's session with new input (user message, answer, feedback)
//   triage  lead triages new comments / an automated review / failing checks on a task's pull
//           request (prwatch.ts): verdicts with the triage tool; fold-ins send the task back to its
//           worker (status pr -> todo -> doing, continuing its branch) and land as an added commit
// Session ids are persisted per (agent, task|goal). A job interrupted by a Foreman restart is
// resumed with the same session AND the same kind, so what happens after the turn (a planning
// goal becomes active, a finished task goes to CI + review) is never lost. start() reconciles
// every non-terminal state (planning goals, doing tasks, review tasks) with what is running.
//
// Steering: pause = abort the turn, keep the task, resume later; stop = off shift: abort the
// turn, withdraw the agent's open questions, hand its tasks back to the board, never scheduled
// again until resume/spawn (persisted across restarts).
//
// Hand-off: a task that changes hands (stop, reassign) is held off the board until the old turn
// is really over - its CLI process has exited and every process it started is gone (they keep
// the worktree busy on Windows and could still write to it) - and the old worktree's work is
// committed on its branch. The next worker's worktree then starts from that branch.
//
// The backend is one class assembled in layers, one file each (base first):
//   core.ts            state (every field lives here), engines per agent, queues, the scheduler tick
//   holds.ts           auth probes, usage limits / warnings / reserve: foreman.status.hold
//   turnSetup.ts       a turn's cwd, role, model, policy context, permission gate, worktree setup
//   sessions.ts        running a turn (the engine, its process, sessions, rotation, abort / reap, hand-off)
//   outcomes.ts        after a turn: settle the goal / task, the one automatic retry
//   recovery.ts        after a restart: re-queue what was in flight, reconcile states
//   jobs/plan.ts       plan jobs (goals)
//   jobs/work.ts       work jobs: scheduling, worktrees, feedback, task steering
//   jobs/review.ts     review jobs: CI, lead review, merge decisions
//   jobs/followup.ts   follow-ups: user messages, answers, PR triage and fold-ins
//   jobs/goalMessages.ts  goal messages (Goals tab)
//   jobs/designTurns.ts  building design turns (design.ts DesignJobs runs them)
//   ../team.ts         TeamBackend: lifecycle, steering, leads (claude/index.ts: ClaudeBackend)
// A method a base layer calls but a later layer implements is declared abstract at the end of
// BackendCore. Later layers add methods only: with ES2022 class fields, a field declared in a
// derived class would be re-initialised after the base constructor ran.
import { type ChildProcess } from 'node:child_process';
import path from 'node:path';
import { DesignJobs, type AuxTurn, type AuxTurnSpec } from '../claude/design.js';
import type { ClaudeConfig } from '../../config.js';
import { FOREMAN_VERSION } from '../../config.js';
import { type Foreman } from '../../foreman.js';
import { withGitSafety } from '../../gitsafety.js';
import { agentGitIdentity } from '../../util/git.js';
import type { BackendName, Decision, Goal, Task } from '../../protocol.js';
import type { ForemanPrivate } from '../../policy.js';
import { type ProcEntry } from '../../util/proc.js';
import { SessionHistory } from '../../history.js';
import { boardSummary, planText } from '../prompts.js';
import { PrWatcher, type TriageItem } from '../../prwatch.js';
import type { RunFn } from '../../prs.js';
import { API_KEY_VARS, CLAUDE_LOGIN_VARS } from '../claude/auth.js';
import type { SessionRecord } from '../../store.js';
import type { Engine, EngineId, Role, TurnStats } from '../engine.js';
import { modelLabel } from '../models.js';
import type { PullRequest } from '../../pulls.js';
import { fetchPulls, githubOrigin } from '../../pulls.js';
import { type ToolHooks } from '../tools.js';
import { scrubEnv } from '../../util/env.js';

export type JobKind = 'plan' | 'work' | 'review' | 'followup' | 'triage';
export type AbortReason = 'pause' | 'stop' | 'shutdown' | 'cancel' | 'timeout';

export interface Job {
  kind: JobKind;
  agentId: string;
  prompt: string;
  sessionKey: string;
  taskId?: string;
  goalId?: string;
  /** start a new session even if one exists for the key */
  fresh?: boolean;
  /** nudges already sent for this task (worker ended without update_task) */
  nudges?: number;
  /** continuing an interrupted job (restart, pause): log the prompt */
  resumed?: boolean;
  /** goal.message: the prompt is built from the goal's unread messages when the turn starts */
  pendingGoalMessage?: boolean;
  /** a goal message turn: the lead's reply goes to the goal's thread (see afterTurn) */
  goalReply?: boolean;
  /** when the goal message turn started (epoch ms) */
  startedAt?: number;
  /** a goal message turn: the user's messages it answers (marked read when it starts) */
  messageIds?: string[];
}

export interface Inflight {
  kind: JobKind;
  sessionKey: string;
  taskId?: string;
  goalId?: string;
  startedAt: number;
  /** a goal-message turn (its reply goes to the goal's thread) and the user's messages it answers */
  goalReply?: true;
  messageIds?: string[];
}

export interface ClaudeState {
  inflight: Record<string, Inflight>;
  ciFixes: Record<string, number>;
  /** agents the user stopped (off shift until resume/spawn) */
  stopped: string[];
  /** goal id -> memory id of the plan the lead wrote for it */
  plans: Record<string, string>;
  /** a usage limit was hit: no turn starts until `until` (epoch ms) */
  limit?: { until: number; type?: string };
  /** the plan warned about usage: fewer workers at once until `until` */
  throttle?: { until: number; type?: string };
  /** task id -> size the lead gave it (picks the worker's model) */
  taskSize: Record<string, 'small' | 'normal' | 'large'>;
  /** goal id -> the user's branch it continues (`on <branch>: ...`), in the goal's repository */
  goalBranch: Record<string, { repoId: string; branch: string; onRemote: boolean }>;
  /** task id -> a branch the lead put this task on (create_task base) */
  taskBase: Record<string, string>;
  /** task id -> review fixes for its open PR the worker is making (PR fold-in) */
  foldIns: Record<string, { notes: string; from?: string; at: number }>;
  /** goal id -> the lead that last had a turn for it (another lead next: it gets a takeover note) */
  goalLead: Record<string, string>;
}

export interface Running {
  abort: AbortController;
  job: Job;
  reason?: AbortReason;
  /** the agent's CLI process (the engine spawns it and reports it, so we know its pid) */
  child?: ChildProcess;
  /** settles when runJob is completely done with this turn */
  done?: Promise<void>;
  /** when we spawned the CLI (epoch ms) */
  spawnedAt?: number;
  /** snapshot of the CLI's process tree taken when the turn was aborted (undefined: unreadable) */
  tree?: Promise<ProcEntry[] | undefined>;
  /** the (single) clean-up of this turn's processes, once started */
  reaping?: Promise<void>;
}

export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** "14:05" today, "Tue 14:05" on another day. */
export function clock(ms: number): string {
  const d = new Date(ms);
  const hm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return d.toDateString() === new Date().toDateString() ? hm : `${d.toLocaleDateString([], { weekday: 'short' })} ${hm}`;
}

export function alive(child: ChildProcess | undefined): child is ChildProcess {
  return !!child && child.exitCode === null && child.signalCode === null;
}

export const TURN_TIMEOUT_MS = 45 * 60_000;
/** wait after a usage limit that did not say when it resets (doubles per hit, up to LIMIT_BACKOFF_MAX_MS) */
export const LIMIT_BACKOFF_MS = 5 * 60_000;
export const LIMIT_BACKOFF_MAX_MS = 60 * 60_000;
/** throttle length when a usage warning has no reset time */
export const THROTTLE_DEFAULT_MS = 30 * 60_000;
/** how often the plan's usage windows are re-read from a live session */
export const USAGE_REFRESH_MS = 5 * 60_000;
/** first retry of the startup auth probe after a network-type failure (doubles, up to the max) */
export const AUTH_RETRY_MS = 30_000;
export const AUTH_RETRY_MAX_MS = 10 * 60_000;
/** wall-clock check of usage windows and holds (timers drift or stall while the machine sleeps) */
export const WAKE_INTERVAL_MS = 60_000;

/**
 * Environment for an agent's CLI process (and every command it runs): git refuses all
 * transports (no push, ever) and never signs; git does not walk up out of the agent's cwd; the
 * agent's commits carry its own placeholder identity ("AgentCraft Kit <kit@agentcraft.local>"; a turn's
 * env then applies the repo's commitIdentity, see RepoManager.commitIdentityEnv); and each Bash call starts in the agent's own cwd, so a `cd` in one command
 * cannot carry the next one out of the worktree.
 */
export function agentEnv(base: NodeJS.ProcessEnv = process.env, who: { agentId?: string; cwd?: string } = {}): Record<string, string | undefined> {
  return withGitSafety(
    base,
    {
      CLAUDE_AGENT_SDK_CLIENT_APP: `agentcraft-foreman/${FOREMAN_VERSION}`,
      CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR: '1',
      ...(who.agentId ? (agentGitIdentity(who.agentId) as Record<string, string>) : {}),
    },
    who.cwd ? { ceiling: path.dirname(path.resolve(who.cwd)) } : {},
  );
}

/** The Foreman's own home, port and client token: off limits for agents (policy.ts). */
export function foremanPrivateOf(fm: Foreman): ForemanPrivate {
  const e = fm.endpoint;
  return { home: fm.config.home, port: e?.port ?? fm.config.port, ...(e?.tokenFile ? { tokenFile: e.tokenFile } : {}) };
}

/** An environment without the Claude credentials (for an engine that is not Claude: Codex agents never get them). */
export function withoutClaudeAuth(env: Record<string, string | undefined>): Record<string, string | undefined> {
  const out = { ...env };
  for (const k of Object.keys(out)) if ([...API_KEY_VARS, ...CLAUDE_LOGIN_VARS].includes(k.toUpperCase())) delete out[k];
  return out;
}

/**
 * Lead session rotation (claude.leadSession): why the stored session should be replaced by a fresh
 * one, or undefined. A record from before rotation existed (no startedAt) is not rotated: its clock
 * starts at its next use.
 */
export function rotationDue(rec: SessionRecord | undefined, limits: { maxDays: number; maxTurns: number }, now = Date.now()): string | undefined {
  if (!rec?.sessionId || rec.startedAt === undefined) return undefined;
  const days = (now - rec.startedAt) / 86_400_000;
  if (limits.maxDays > 0 && days >= limits.maxDays) return `${Math.floor(days)} days old`;
  if (limits.maxTurns > 0 && (rec.sessionTurns ?? 0) >= limits.maxTurns) return `${rec.sessionTurns} turns`;
  return undefined;
}

/** A resumed goal-message turn stays one: its final text is still the goal thread's reply (see replyToGoal). */
export function goalReplyOf(inf: Inflight): Pick<Job, 'goalReply' | 'startedAt' | 'messageIds'> {
  return inf.goalReply ? { goalReply: true, startedAt: inf.startedAt, ...(inf.messageIds ? { messageIds: inf.messageIds } : {}) } : {};
}

/** Which engine runs which agent: the leads', the workers', and per-agent exceptions. */
export interface TeamEngines {
  lead: Engine;
  worker: Engine;
  byAgent?: Record<string, Engine>;
  /** building design jobs (Claude only); none: design requests are refused */
  design?: Engine;
}

export interface PullFetcher {
  origin(repoPath: string): Promise<string | undefined>;
  fetch(repoPath: string, numbers: number[]): Promise<{ pulls: PullRequest[]; errors: string[] }>;
}

export const defaultPullFetcher: PullFetcher = { origin: (p) => githubOrigin(p), fetch: (p, n) => fetchPulls(p, n) };

export interface TeamOptions {
  /** the backend name reported to clients ("claude" or "codex": the workers' engine) */
  name: BackendName;
  engines: TeamEngines;
  /** skip the startup auth probe (tests) */
  skipAuthCheck?: boolean;
  /** injectable for tests: pull request intake (default: git + gh, see pulls.ts) */
  pullFetcher?: PullFetcher;
  /** runs `az` / `gh` for pull requests (opening and watching them); tests inject a fake */
  prRunFn?: RunFn;
  /** first auth-probe retry delay after a network failure (tests) */
  authRetryMs?: number;
  /** the wall-clock hold/usage check interval (tests) */
  wakeIntervalMs?: number;
  /** delay before the automatic retry of a turn that failed for a passing reason (default 2-5 min) */
  transientRetryMs?: number;
}

export abstract class BackendCore {
  readonly name: BackendName;

  protected queues = new Map<string, Job[]>();

  protected running = new Map<string, Running>();

  protected pausedJobs = new Map<string, Job>();

  protected tickTimer: NodeJS.Timeout | undefined;

  /** sticky: the login / key is not valid (until a restart) */
  protected authFailed = false;

  protected authMessage = '';

  /** the startup auth probe could not reach Claude: retried with backoff, work waits meanwhile */
  protected offline: { delayMs: number; retryAt: number; message: string } | undefined;

  protected authRetryTimer: NodeJS.Timeout | undefined;

  protected wakeTimer: NodeJS.Timeout | undefined;

  /** the usage reserve is holding new turns (announced once per episode) */
  protected reserveActive = false;

  /** agent id -> a failed turn's job waiting for its one automatic retry (see autoRetry) */
  protected delayed = new Map<string, { job: Job; timer: NodeJS.Timeout; at: number }>();

  /** automatic retries used, per (worker task | lead plan | lead review); cleared when one succeeds */
  protected retried = new Map<string, number>();

  protected stopping = false;

  protected waitingUser = new Set<string>();

  protected hooks: ToolHooks;

  protected lastCost = new Map<string, number>();

  protected turnPromises = new Set<Promise<void>>();

  /** tasks whose CI + review hand-off is in progress */
  protected reviewing = new Set<string>();

  /** the most recent turn per agent (kept after it ends, for quiesce) */
  protected lastTurn = new Map<string, Running>();

  /** tasks changing hands: off the board until the old turn is over and its work committed */
  protected handoffs = new Map<string, Promise<void>>();

  /** scheduler retry after an error (backoff) */
  protected retryTimer: NodeJS.Timeout | undefined;

  protected retryDelayMs = 2000;

  /** fires when a usage limit / throttle window ends */
  protected limitTimer: NodeJS.Timeout | undefined;

  protected limitBackoffMs = LIMIT_BACKOFF_MS;

  /** the user's earlier Claude sessions (claude.context.sessionHistory) */
  protected history: SessionHistory | undefined;

  /** role files that could not be read (warned once each) */
  protected roleProblems = new Set<string>();

  /** building design jobs (design.request), one at a time */
  readonly designs: DesignJobs;

  /** pull requests of tasks landed as PRs (status pr) */
  readonly prs: PrWatcher;

  /** when the plan's usage windows were last read (refreshUsage) */
  protected lastUsageRead = 0;

  /** the model each agent's last turn really ran (shown on its nameplate) */
  protected reportedModels = new Map<string, { engine: EngineId; label: string }>();

  protected readonly pullFetcher: PullFetcher;

  constructor(
    protected readonly fm: Foreman,
    protected readonly cfg: ClaudeConfig,
    protected readonly opts: TeamOptions,
  ) {
    this.name = opts.name;
    this.pullFetcher = opts.pullFetcher ?? defaultPullFetcher;
    this.designs = new DesignJobs({
      fm: this.fm,
      cfg: this.cfg,
      canStart: () => !this.stopping && !this.held(),
      holdForLimit: (stats) => {
        if (!this.limited()) this.setLimit(stats.rateLimit?.resetsAt, stats.rateLimit?.type);
        return this.st.limit?.until;
      },
      runTurn: (spec) => this.runAuxTurn(spec),
      tick: () => this.tick(),
    });
    this.hooks = {
      onReview: () => {
        /* handled after the worker's turn ends (CI then review) */
      },
      onChangesRequested: (taskId, feedback) => this.sendBackToWorker(taskId, `${this.fm.nameOf(this.fm.leadOfTask(this.fm.tasks.get(taskId)))} reviewed your work on ${taskId} and asks for changes:\n${feedback}\n\nMake the changes, re-run the tests, then update_task("${taskId}", status "review", summary).`),
      onTasksChanged: () => this.tick(),
      onMergeRequested: (taskId) => this.fm.log.info(`merge decision opened for ${taskId}`),
      onWaiting: (agentId, waiting) => {
        if (waiting) this.waitingUser.add(agentId);
        else this.waitingUser.delete(agentId);
      },
      onPlanWritten: (goalId, memoryId) => {
        this.st.plans[goalId] = memoryId;
        this.fm.store.markDirty();
        this.fm.recordPlan(goalId, memoryId);
      },
      onTaskSize: (taskId, size) => {
        this.st.taskSize[taskId] = size;
        this.fm.store.markDirty();
      },
      onTaskBase: async (taskId, repoId, branch) => {
        const b = await this.fm.repos.useBranch(repoId, branch);
        this.st.taskBase[taskId] = b.branch;
        this.fm.store.markDirty();
      },
      onTriage: (items) => this.prs.applyTriage(items),
    };
    if (opts.prRunFn) this.fm.repos.prRunFn = opts.prRunFn;
    this.prs = new PrWatcher(this.fm, {
      mode: this.cfg.prWatch ?? 'observe',
      pollSeconds: this.cfg.prPollSeconds ?? 180,
      ...(opts.prRunFn ? { runFn: opts.prRunFn } : {}),
      hooks: {
        triage: (task, items) => void this.enqueueTriage(task.id, items),
        foldIn: (task, notes) => this.startFoldIn(task.id, notes),
        merged: (task) => this.onPrMerged(task),
        triaging: (taskId) => this.triaging(taskId),
      },
    });
  }

  protected get st(): ClaudeState {
    const b = this.fm.store.data.backend;
    let s = b.claude as ClaudeState | undefined;
    if (!s) {
      s = { inflight: {}, ciFixes: {}, stopped: [], plans: {}, taskSize: {}, goalBranch: {}, taskBase: {}, foldIns: {}, goalLead: {} };
      b.claude = s;
    }
    s.inflight ??= {};
    s.ciFixes ??= {};
    s.stopped ??= [];
    s.plans ??= {};
    s.taskSize ??= {};
    s.goalBranch ??= {};
    s.taskBase ??= {};
    s.foldIns ??= {};
    s.goalLead ??= {};
    return s;
  }

  /** The engine that runs an agent's turns (every lead: the lead engine). */
  engineFor(agentId: string): Engine {
    const e = this.opts.engines;
    return e.byAgent?.[agentId] ?? (this.fm.isLead(agentId) ? e.lead : e.worker);
  }

  /** Every engine on the team: each is checked at start (building designs: when they run). */
  protected enginesInUse(): Engine[] {
    return [...new Set([...this.leadsOnDuty(), ...this.team].map((id) => this.engineFor(id)))];
  }

  /** Every engine the team was given (prepared at start). */
  protected allEngines(): Engine[] {
    const e = this.opts.engines;
    return [...new Set([e.lead, e.worker, ...Object.values(e.byAgent ?? {}), ...(e.design ? [e.design] : [])])];
  }

  /** e.g. "Claude (lead opus, workers sonnet)" or "Claude lead opus · Codex workers gpt-5" */
  protected teamLabel(): string {
    const lead = this.opts.engines.lead;
    const workers = [...new Set(this.team.map((w) => this.engineFor(w)))];
    // aliases as configured ("opus"), full model ids as display names ("claude-opus-5-5" -> "Opus 5.5")
    const m = (e: Engine, role: Role) => (/^[a-z]+$/.test(e.model(role)) ? e.model(role) : (modelLabel(e.model(role)) ?? e.model(role)));
    if (!workers.length || (workers.length === 1 && workers[0] === lead)) return `${lead.label} (lead ${m(lead, 'lead')}, workers ${m(lead, 'worker')})`;
    if (workers.length === 1) return `${lead.label} lead ${m(lead, 'lead')} · ${workers[0]!.label} workers ${m(workers[0]!, 'worker')}`;
    return `${lead.label} lead ${m(lead, 'lead')} · ${this.team.map((w) => `${this.fm.nameOf(w)} ${this.engineFor(w).label}`).join(', ')}`;
  }

  /**
   * Every agent's nameplate shows its engine and model: the configured one (its profile's model for a
   * Claude agent) until a turn reports the real one. Off-shift agents too: what they would run.
   */
  protected showEngines(): void {
    for (const a of this.fm.agents()) {
      const engine = this.engineFor(a.id);
      const role: Role = this.fm.isLead(a.id) ? 'lead' : 'worker';
      const reported = this.reportedModels.get(a.id);
      const configured = engine.id === 'claude' ? (this.cfg.agents[a.id]?.model ?? engine.model(role)) : engine.model(role);
      const model = (reported?.engine === engine.id ? reported.label : undefined) ?? modelLabel(configured) ?? engine.label;
      this.fm.setAgent(a.id, { engine: engine.id, model });
    }
  }

  get team(): string[] {
    return this.cfg.workers.filter((w) => this.fm.agent(w) && !this.fm.isLead(w));
  }

  /** Leads on duty (marlow, and every assigned building lead), each with its own job queue. */
  protected leadsOnDuty(): string[] {
    return this.fm.leads.onDutyIds().filter((id) => this.fm.agent(id));
  }

  protected leadTurnsRunning(): number {
    return [...this.running.keys()].filter((id) => this.fm.isLead(id)).length;
  }

  /** The plan says usage is high: fewer workers, and leads take turns one at a time. */
  protected throttled(now = Date.now()): boolean {
    return !!this.st.throttle && now < this.st.throttle.until;
  }

  /** claude.maxConcurrentTurns: every agent turn counts, leads and workers. */
  protected turnCapReached(): boolean {
    return !!this.cfg.maxConcurrentTurns && this.running.size >= this.cfg.maxConcurrentTurns;
  }

  protected isStopped(agentId: string): boolean {
    return this.st.stopped.includes(agentId);
  }

  protected setStopped(agentId: string, stopped: boolean): void {
    const s = this.st;
    s.stopped = s.stopped.filter((x) => x !== agentId);
    if (stopped) s.stopped.push(agentId);
    this.fm.store.markDirty();
  }

  /** The goal's plan note: Goal.planId, else the one recorded before goals carried it. */
  protected planIdOf(goalId: string): string | undefined {
    return this.fm.goal(goalId)?.planId ?? this.st.plans[goalId];
  }

  /**
   * A lead's turn for a goal another lead worked on before (its lead was released): what it takes
   * over. Recorded once per hand-over.
   */
  protected takeoverNote(leadId: string, goalId: string | undefined): string {
    if (!goalId) return '';
    const before = this.st.goalLead[goalId];
    if (before === leadId) return '';
    this.st.goalLead[goalId] = leadId;
    this.fm.store.markDirty();
    if (!before) return '';
    const goal = this.fm.goal(goalId);
    return `You take over this goal from ${this.fm.nameOf(before)}, who no longer leads its building. ${this.fm.nameOf(before)}'s plan (shared memory):\n${planText(this.fm, goal, this.planIdOf(goalId))}\n\nTask board for the goal:\n${boardSummary(this.fm, goalId)}\n\nContinue from here: the tasks, reviews and pull requests of this goal are yours now.`;
  }

  /** An agent's own open question (the PR watcher's decisions to the user are not the agent's). */
  protected openQuestion(agentId: string): Decision | undefined {
    return this.fm.decisions.open().find((d) => d.kind === 'question' && d.agentId === agentId && !this.prs.owns(d.id) && !this.fm.ownsDecision(d.id));
  }

  /** A job matching `pred` is queued, running or paused (waiting for /resume) for this agent. */
  protected hasQueued(agentId: string, pred: (j: Job) => boolean): boolean {
    const running = this.running.get(agentId);
    const paused = this.pausedJobs.get(agentId);
    const later = this.delayed.get(agentId)?.job;
    return (this.queues.get(agentId) ?? []).some(pred) || (running ? pred(running.job) : false) || (paused ? pred(paused) : false) || (later ? pred(later) : false);
  }

  protected enqueue(job: Job): void {
    const q = this.queues.get(job.agentId) ?? [];
    q.push(job);
    this.queues.set(job.agentId, q);
    this.pump(job.agentId);
  }

  protected pump(agentId: string): void {
    if (this.stopping || this.held()) return;
    if (this.running.has(agentId)) return;
    const a = this.fm.agent(agentId);
    if (!a || a.paused || !a.active || this.isStopped(agentId)) return;
    const q = this.queues.get(agentId);
    if (!q?.length) return;
    const lead = this.fm.isLead(agentId);
    if (!lead && this.workersRunning() >= this.maxWorkers()) return;
    // leads run in parallel, one queue each; under a usage warning they take turns one at a time
    if (lead && this.throttled() && this.leadTurnsRunning() >= 1) return;
    if (this.turnCapReached()) return;
    let job = q.shift()!;
    // goal.message: the goal's unread messages make the prompt (none left: nothing to do)
    while (job.pendingGoalMessage && !this.fillGoalMessage(job)) {
      const next = q.shift();
      if (!next) return;
      job = next;
    }
    const p = this.runJob(job).finally(() => {
      this.turnPromises.delete(p);
    });
    this.turnPromises.add(p);
    // runJob registers its Running entry synchronously, before its first await
    const entry = this.running.get(agentId);
    if (entry) {
      entry.done = p;
      this.lastTurn.set(agentId, entry);
    }
  }

  tick(): void {
    if (this.tickTimer || this.stopping) return;
    this.tickTimer = setTimeout(() => {
      this.tickTimer = undefined;
      this.schedule().catch((e) => {
        this.fm.log.error(`scheduler: ${(e as Error).stack ?? e}`);
        this.retryLater();
      });
    }, 50);
    this.tickTimer.unref?.();
  }

  /** Scheduler failed (git error, busy directory...): try again later instead of stalling. */
  protected retryLater(): void {
    if (this.retryTimer || this.stopping) return;
    const delay = this.retryDelayMs;
    this.retryDelayMs = Math.min(60_000, this.retryDelayMs * 2);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      this.tick();
    }, delay);
    this.retryTimer.unref?.();
  }

  protected workersRunning(): number {
    return [...this.running.keys()].filter((id) => !this.fm.isLead(id)).length;
  }

  protected isFree(w: string): boolean {
    const a = this.fm.agent(w);
    if (!a || !a.active || a.paused || this.isStopped(w) || !this.team.includes(w)) return false;
    if (this.running.has(w) || this.delayed.has(w) || (this.queues.get(w)?.length ?? 0) > 0) return false;
    return !this.fm.tasks.list().some((t) => t.assignee === w && t.status === 'doing');
  }

  /** An agent's base environment (git safety, identity); each engine applies its own auth on top. */
  protected env(who: { agentId?: string; cwd?: string } = {}): Record<string, string | undefined> {
    return scrubEnv(agentEnv(process.env, who));
  }

  // ---- implemented by later layers (one file each, see the list above) ----

  protected abstract held(now?: number): boolean;
  protected abstract limited(now?: number): boolean;
  protected abstract maxWorkers(now?: number): number;
  protected abstract setLimit(resetsAt: number | undefined, type: string | undefined): void;
  protected abstract runJob(job: Job): Promise<void>;
  protected abstract afterTurn(job: Job, stats: TurnStats | undefined): Promise<void>;
  protected abstract promoteGoal(goal: Goal, why: string): void;
  protected abstract schedule(): Promise<void>;
  protected abstract sendBackToWorker(taskId: string, prompt: string): void;
  protected abstract sweepReviews(): void;
  protected abstract afterWorkerDone(taskId: string): Promise<void>;
  protected abstract openMergeDecision(t: Task, summary: string): void;
  protected abstract deliverPending(agentId: string): void;
  protected abstract triaging(taskId: string): boolean;
  protected abstract enqueueTriage(taskId: string, items: TriageItem[]): Promise<void>;
  protected abstract startFoldIn(taskId: string, notes: string): boolean;
  protected abstract onPrMerged(t: Task): void;
  protected abstract fillGoalMessage(job: Job): boolean;
  protected abstract inflightOf(job: Job): Omit<Inflight, 'startedAt'>;
  protected abstract replyToGoal(lead: string, job: Job, stats: TurnStats | undefined): void;
  protected abstract requeueGoalMessage(agentId: string, inf: Inflight, handOver?: boolean): boolean;
  protected abstract offerHeldGoalMessages(job: Job): void;
  abstract runAuxTurn(spec: AuxTurnSpec): AuxTurn;
}
