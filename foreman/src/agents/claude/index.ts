// Claude backend: real Claude Agent SDK sessions for the lead and workers.
//
// Each agent processes a queue of jobs, one SDK `query()` turn per job:
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
import { spawn, type ChildProcess } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { query, type AgentDefinition, type CanUseTool, type HookCallbackMatcher, type EffortLevel, type Options, type PermissionResult } from '@anthropic-ai/claude-agent-sdk';
import { DesignJobs, type AuxTurn, type AuxTurnSpec } from './design.js';
import type { ClaudeConfig } from '../../config.js';
import { FOREMAN_VERSION } from '../../config.js';
import { ClientError, type Backend, type Foreman } from '../../foreman.js';
import { withGitSafety } from '../../gitsafety.js';
import { agentGitIdentity } from '../../util/git.js';
import { classifyToolUse, describeRuleKey, describeToolCall, foremanPrivatePath, foremanPrivateVerdict, type PolicyContext } from '../../policy.js';
import type { Decision, Design, Goal, Task } from '../../protocol.js';
import { MERGE_OPTIONS, PERMISSION_OPTIONS } from '../../protocol.js';
import type { TestResult } from '../../repos.js';
import { renderDiffText } from '../../diff.js';
import { formatInbox } from '../../bus.js';
import { descendantsOf, killSnapshot, killTree, orphansOf, processTable, type ProcEntry } from '../../util/proc.js';
import { truncate } from '../../util/text.js';
import { buildSkillsPlugin, instructionsBlock, workspaceInstructionDirs } from './context.js';
import { SessionHistory, sessionLine } from '../../history.js';
import { connectorHook, foremanGuardHook, guardrailHook } from './permissions.js';
import { agentFilePath, loadRepoAgents, loadSubagents, readAgentFile } from './subagents.js';
import { type RepoRole, boardSummary, foldInPrompt, leadRepoContext, leadSystemPrompt, planPrompt, planText, RESUME_PROMPT, reviewPrompt, triagePrompt, workerSystemPrompt, workPrompt } from './prompts.js';
import { DEFAULT_AUTO_SEVERITIES, DEFAULT_MAX_ROUNDS, PrWatcher, type TriageItem } from '../../prwatch.js';
import type { RunFn } from '../../prs.js';
import { detectApiAuth, NO_API_AUTH_MESSAGE, withAuthMode } from './auth.js';
import { pruneUsage, readPlanUsage, reserveHold, usageLine, withWindow } from './usage.js';
import { classifyFailure, isAuthText, probeFailure } from './failures.js';
import type { ForemanHold } from '../../protocol.js';
import type { SessionRecord } from '../../store.js';
import { limitFromText, StreamMapper, type RateLimitReport, type TurnStats } from './stream.js';
import { buildMcpServer, MCP_SERVER, type ToolHooks, type TurnHandle } from './tools.js';
import { userName } from '../../user.js';
import { scrubEnv } from '../../util/env.js';
import { HOME_LEAD } from '../../leads.js';

type JobKind = 'plan' | 'work' | 'review' | 'followup' | 'triage';
type AbortReason = 'pause' | 'stop' | 'shutdown' | 'cancel' | 'timeout';

interface Job {
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
}

interface Inflight {
  kind: JobKind;
  sessionKey: string;
  taskId?: string;
  goalId?: string;
  startedAt: number;
}

interface ClaudeState {
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

interface Running {
  abort: AbortController;
  job: Job;
  reason?: AbortReason;
  /** the live query, force-closed on abort */
  q?: { close(): void };
  /** the agent's CLI process (we spawn it, so we know its pid) */
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

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** "14:05" today, "Tue 14:05" on another day. */
function clock(ms: number): string {
  const d = new Date(ms);
  const hm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return d.toDateString() === new Date().toDateString() ? hm : `${d.toLocaleDateString([], { weekday: 'short' })} ${hm}`;
}

function alive(child: ChildProcess | undefined): child is ChildProcess {
  return !!child && child.exitCode === null && child.signalCode === null;
}

/** Claude Code settings that keep Claude's co-author trailer, PR footer and session link out (C8). */
export const NO_ATTRIBUTION = { attribution: { commit: '', pr: '', sessionUrl: false }, includeCoAuthoredBy: false } as const;

const TURN_TIMEOUT_MS = 45 * 60_000;
/** wait after a usage limit that did not say when it resets (doubles per hit, up to LIMIT_BACKOFF_MAX_MS) */
const LIMIT_BACKOFF_MS = 5 * 60_000;
const LIMIT_BACKOFF_MAX_MS = 60 * 60_000;
/** throttle length when a usage warning has no reset time */
const THROTTLE_DEFAULT_MS = 30 * 60_000;
/** how often the plan's usage windows are re-read from a live session */
const USAGE_REFRESH_MS = 5 * 60_000;
/** first retry of the startup auth probe after a network-type failure (doubles, up to the max) */
const AUTH_RETRY_MS = 30_000;
const AUTH_RETRY_MAX_MS = 10 * 60_000;
/** wall-clock check of usage windows and holds (timers drift or stall while the machine sleeps) */
const WAKE_INTERVAL_MS = 60_000;

/**
 * Environment for an agent's CLI process (and every command it runs): git refuses all
 * transports (no push, ever) and never signs; git does not walk up out of the agent's cwd; the
 * agent's commits carry its own placeholder identity ("AgentCraft Kit <kit@agentcraft.local>"),
 * never the user's; and each Bash call starts in the agent's own cwd, so a `cd` in one command
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

export interface ClaudeBackendOptions {
  /** injectable for tests */
  queryFn?: typeof query;
  /** skip the startup auth probe (tests) */
  skipAuthCheck?: boolean;
  /** runs `az` / `gh` for pull requests (opening and watching them); tests inject a fake */
  prRunFn?: RunFn;
  /** first auth-probe retry delay after a network failure (tests) */
  authRetryMs?: number;
  /** the wall-clock hold/usage check interval (tests) */
  wakeIntervalMs?: number;
  /** delay before the automatic retry of a turn that failed for a passing reason (default 2-5 min) */
  transientRetryMs?: number;
}

export class ClaudeBackend implements Backend {
  readonly name = 'claude' as const;
  private queues = new Map<string, Job[]>();
  private running = new Map<string, Running>();
  private pausedJobs = new Map<string, Job>();
  private tickTimer: NodeJS.Timeout | undefined;
  /** sticky: the login / key is not valid (until a restart) */
  private authFailed = false;
  private authMessage = '';
  /** the startup auth probe could not reach Claude: retried with backoff, work waits meanwhile */
  private offline: { delayMs: number; retryAt: number; message: string } | undefined;
  private authRetryTimer: NodeJS.Timeout | undefined;
  private wakeTimer: NodeJS.Timeout | undefined;
  /** the usage reserve is holding new turns (announced once per episode) */
  private reserveActive = false;
  /** agent id -> a failed turn's job waiting for its one automatic retry (see autoRetry) */
  private delayed = new Map<string, { job: Job; timer: NodeJS.Timeout; at: number }>();
  /** automatic retries used, per (worker task | lead plan | lead review); cleared when one succeeds */
  private retried = new Map<string, number>();
  private stopping = false;
  private waitingUser = new Set<string>();
  private readonly queryFn: typeof query;
  private hooks: ToolHooks;
  private lastCost = new Map<string, number>();
  private turnPromises = new Set<Promise<void>>();
  /** tasks whose CI + review hand-off is in progress */
  private reviewing = new Set<string>();
  /** the most recent turn per agent (kept after it ends, for quiesce) */
  private lastTurn = new Map<string, Running>();
  /** tasks changing hands: off the board until the old turn is over and its work committed */
  private handoffs = new Map<string, Promise<void>>();
  /** scheduler retry after an error (backoff) */
  private retryTimer: NodeJS.Timeout | undefined;
  private retryDelayMs = 2000;
  /** fires when a usage limit / throttle window ends */
  private limitTimer: NodeJS.Timeout | undefined;
  private limitBackoffMs = LIMIT_BACKOFF_MS;
  /** the Foreman's skills plugin (built at start from claude.context.skills) */
  private skillsPlugin: { path: string; ids: string[] } | undefined;
  /** the user's earlier Claude sessions (claude.context.sessionHistory) */
  private history: SessionHistory | undefined;
  /** subagent definitions from claude.subagents.agents (loaded at start) */
  private subagentDefs: Record<string, AgentDefinition> = {};
  /** role files that could not be read (warned once each) */
  private roleProblems = new Set<string>();
  /** building design jobs (design.request), one at a time */
  readonly designs: DesignJobs;
  /** pull requests of tasks landed as PRs (status pr) */
  readonly prs: PrWatcher;

  constructor(
    private fm: Foreman,
    private cfg: ClaudeConfig,
    private opts: ClaudeBackendOptions = {},
  ) {
    this.queryFn = opts.queryFn ?? query;
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

  private get st(): ClaudeState {
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

  get team(): string[] {
    return this.cfg.workers.filter((w) => this.fm.agent(w) && !this.fm.isLead(w));
  }

  /** Leads on duty (marlow, and every assigned building lead), each with its own job queue. */
  private leadsOnDuty(): string[] {
    return this.fm.leads.onDutyIds().filter((id) => this.fm.agent(id));
  }

  private leadTurnsRunning(): number {
    return [...this.running.keys()].filter((id) => this.fm.isLead(id)).length;
  }

  /** The plan says usage is high: fewer workers, and leads take turns one at a time. */
  private throttled(now = Date.now()): boolean {
    return !!this.st.throttle && now < this.st.throttle.until;
  }

  /** claude.maxConcurrentTurns: every agent turn counts, leads and workers. */
  private turnCapReached(): boolean {
    return !!this.cfg.maxConcurrentTurns && this.running.size >= this.cfg.maxConcurrentTurns;
  }

  /** A triage turn for this task's PR is queued, running or paused (for the task's lead). */
  private triaging(taskId: string): boolean {
    const lead = this.fm.leadOfTask(this.fm.tasks.get(taskId));
    return this.hasQueued(lead, (j) => j.kind === 'triage' && j.taskId === taskId) || (this.st.inflight[lead]?.kind === 'triage' && this.st.inflight[lead]?.taskId === taskId);
  }

  /**
   * A lead's turn for a goal another lead worked on before (its lead was released): what it takes
   * over. Recorded once per hand-over.
   */
  private takeoverNote(leadId: string, goalId: string | undefined): string {
    if (!goalId) return '';
    const before = this.st.goalLead[goalId];
    if (before === leadId) return '';
    this.st.goalLead[goalId] = leadId;
    this.fm.store.markDirty();
    if (!before) return '';
    const goal = this.fm.goal(goalId);
    return `You take over this goal from ${this.fm.nameOf(before)}, who no longer leads its building. ${this.fm.nameOf(before)}'s plan (shared memory):\n${planText(this.fm, goal, this.planIdOf(goalId))}\n\nTask board for the goal:\n${boardSummary(this.fm, goalId)}\n\nContinue from here: the tasks, reviews and pull requests of this goal are yours now.`;
  }

  /** The goal's plan note: Goal.planId, else the one recorded before goals carried it. */
  private planIdOf(goalId: string): string | undefined {
    return this.fm.goal(goalId)?.planId ?? this.st.plans[goalId];
  }

  private isStopped(agentId: string): boolean {
    return this.st.stopped.includes(agentId);
  }

  private setStopped(agentId: string, stopped: boolean): void {
    const s = this.st;
    s.stopped = s.stopped.filter((x) => x !== agentId);
    if (stopped) s.stopped.push(agentId);
    this.fm.store.markDirty();
  }

  // ---- lifecycle ----------------------------------------------------------------------------

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

  /** Build the skills plugin and report what context the agents get. */
  private prepareContext(): void {
    const c = this.cfg.context;
    const dir = path.join(this.fm.config.dataDir, 'agent-plugin');
    try {
      const { ids, problems } = buildSkillsPlugin(c.skills, dir);
      for (const p of problems) this.fm.log.warn(`claude.context: ${p}`);
      this.skillsPlugin = ids.length ? { path: dir, ids } : undefined;
    } catch (e) {
      this.fm.log.error(`claude.context: could not build the skills plugin: ${(e as Error).message}`);
      this.skillsPlugin = undefined;
    }
    const what = [
      c.repoInstructions ? 'repo CLAUDE.md/AGENTS.md' : '',
      c.userInstructions ? '~/.claude/CLAUDE.md' : '',
      c.files.length ? `${c.files.length} instruction file(s)` : '',
      this.skillsPlugin ? `skills ${this.skillsPlugin.ids.map((i) => i.split(':')[1]).join(', ')}` : '',
      Object.keys(c.mcpServers).length ? `MCP ${Object.keys(c.mcpServers).join(', ')}` : '',
    ].filter(Boolean);
    if (c.sessionHistory.enabled) {
      this.history = new SessionHistory({
        // the registered repositories and the workspace folders around them (and their Desktop scratchpads)
        within: () => this.fm.repos.list().flatMap((r) => [r.path, ...workspaceInstructionDirs(r.path)]),
        exclude: [this.fm.config.home],
        indexFile: path.join(this.fm.config.dataDir, 'history-index.json'),
        days: c.sessionHistory.days,
      });
      what.push(`session history (${c.sessionHistory.days} days)`);
    }
    this.fm.log.info(`agent context: ${what.join('; ') || 'none'}`);
  }

  /** Load subagent definitions and report the permission setup. */
  private preparePermissions(): void {
    const p = this.cfg.permissions;
    const s = this.cfg.subagents;
    if (s.enabled) {
      const { agents, problems } = loadSubagents(s.agents);
      for (const pr of problems) this.fm.log.warn(`claude.subagents: ${pr}`);
      this.subagentDefs = agents;
    }
    const rules = p.allow.length + p.deny.length + p.ask.length;
    this.fm.log.info(
      `permissions: ${p.mode === 'auto' ? `auto mode${p.protectCheckouts ? ' (checkouts protected)' : ''}` : 'AgentCraft policy'}` +
        `${rules ? `, ${rules} rule(s)` : ''}${p.webTools ? ', web tools' : ''}` +
        `${s.enabled ? `, subagents${Object.keys(this.subagentDefs).length ? ` (${Object.keys(this.subagentDefs).join(', ')})` : ''}` : ''}`,
    );
  }

  /** What the AgentCraft policy needs to judge this agent's tool calls. */
  private policyContext(agentId: string, role: 'lead' | 'worker', cwd: string, repoId?: string): PolicyContext {
    const repo = repoId ? this.fm.repos.get(repoId) : undefined;
    // a workspace holding several repos: its instructions point at docs in the workspace folder
    const workspace = repo && this.cfg.context.workspaceInstructions ? workspaceInstructionDirs(repo.path) : [];
    const protectedPaths = repoId ? this.fm.repos.protectedPaths(repoId) : [];
    return {
      role,
      cwd,
      // every registered repository is readable (goals can span them); writing stays in the worktree
      readDirs: [this.fm.memory.dir, ...(this.skillsPlugin ? [this.skillsPlugin.path] : []), ...workspace, ...this.fm.repos.list().map((r) => r.path)],
      ...(protectedPaths.length ? { protectedPaths } : {}),
      alwaysAllow: this.fm.store.data.permissionRules[agentId] ?? [],
      mcpServer: MCP_SERVER,
      ...(this.skillsPlugin ? { skills: this.skillsPlugin.ids } : {}),
      mcpAllow: this.cfg.context.mcpAllow,
      ...(this.subagentsOn(repoId) ? { subagents: true } : {}),
      foreman: this.foremanPrivate(),
    };
  }

  /** The Foreman's own home, port and client token: off limits for agents (policy.ts). */
  private foremanPrivate(): NonNullable<PolicyContext['foreman']> {
    const e = this.fm.endpoint;
    return { home: this.fm.config.home, port: e?.port ?? this.fm.config.port, ...(e?.tokenFile ? { tokenFile: e.tokenFile } : {}) };
  }

  /** Auto mode's guardrails cover the user's checkouts and AgentCraft's own state. */
  private protectedRoots(): string[] {
    return this.cfg.permissions.protectCheckouts ? [...this.fm.repos.list().map((r) => r.path), this.fm.config.home] : [];
  }

  async checkAuth(): Promise<boolean> {
    if (this.opts.skipAuthCheck) {
      this.fm.setStatus({ auth: 'ok', message: `Claude (lead ${this.cfg.leadModel}, workers ${this.cfg.workerModel})` });
      return true;
    }
    // API authentication by default; the claude.ai login only when explicitly opted into
    const api = detectApiAuth(process.env);
    if (!this.cfg.useClaudeLogin && !api.ok) {
      this.markAuthFailed(NO_API_AUTH_MESSAGE);
      return false;
    }
    this.fm.setStatus({ auth: 'checking', message: this.cfg.useClaudeLogin ? 'Checking Claude login...' : 'Checking Claude API access...' });
    async function* never(): AsyncGenerator<never> {
      await new Promise(() => undefined);
    }
    const q = this.queryFn({ prompt: never(), options: { settingSources: [], persistSession: false, permissionMode: 'default', env: this.env() } });
    try {
      const info = await Promise.race([q.accountInfo(), new Promise<never>((_, r) => setTimeout(() => r(new Error('timed out after 45s')), 45_000))]);
      const ok = !!(info.email || info.organization || (info.apiKeySource && info.apiKeySource !== 'none') || (info.tokenSource && info.tokenSource !== 'none') || (info.apiProvider && info.apiProvider !== 'firstParty'));
      if (!ok) throw new Error('not logged in');
      const account = this.cfg.useClaudeLogin
        ? [info.organization, info.subscriptionType].filter(Boolean).join(' · ') || info.apiProvider || 'ok'
        : [api.ok ? api.source : 'API', info.organization].filter(Boolean).join(' · ');
      this.authFailed = false;
      this.fm.setStatus({ auth: 'ok', account, message: `Claude (lead ${this.cfg.leadModel}, workers ${this.cfg.workerModel})` });
      this.fm.log.info(`claude auth ok (${account})`);
      if (this.offline) {
        this.offline = undefined;
        this.fm.bus.feed('system', 'Claude is reachable again: the team picks up where it stopped');
      }
      this.refreshHold();
      return true;
    } catch (e) {
      const why = (e as Error).message ?? String(e);
      if (probeFailure(why) === 'retry') {
        // the network, a sleeping machine, an API outage: not a bad login. Retry with backoff.
        this.goOffline(why);
        return false;
      }
      this.markAuthFailed(
        this.cfg.useClaudeLogin
          ? `Claude login check failed: ${why}. Run \`claude\` and /login, then restart the Foreman. The sim backend still works.`
          : `Claude API check failed: ${why}. Check ANTHROPIC_API_KEY (or your cloud provider settings), then restart the Foreman. The sim backend still works.`,
      );
      return false;
    } finally {
      try {
        q.close();
      } catch {
        /* ignore */
      }
    }
  }

  private markAuthFailed(message: string): void {
    this.authFailed = true;
    this.authMessage = message;
    this.offline = undefined;
    if (this.authRetryTimer) clearTimeout(this.authRetryTimer);
    this.authRetryTimer = undefined;
    this.fm.setStatus({ auth: 'failed', message });
    this.fm.log.error(message);
    this.fm.bus.feed('error', message);
    this.fm.notify('warn', message);
    this.fm.notifyExternal('auth', message);
    if (process.stdout.isTTY) process.stdout.write('\x07');
    this.refreshHold();
  }

  /** The auth probe could not reach Claude: hold new turns and probe again later (backoff). */
  private goOffline(why: string): void {
    const delayMs = this.offline ? Math.min(AUTH_RETRY_MAX_MS, this.offline.delayMs * 2) : (this.opts.authRetryMs ?? AUTH_RETRY_MS);
    const first = !this.offline;
    const retryAt = Date.now() + delayMs;
    this.offline = { delayMs, retryAt, message: `Claude could not be reached (${truncate(why, 120)}); trying again at ${clock(retryAt)}. Work waits meanwhile.` };
    this.fm.setStatus({ auth: 'checking', message: this.offline.message });
    if (first) {
      this.fm.log.warn(this.offline.message);
      this.fm.bus.feed('error', this.offline.message);
    } else this.fm.log.info(`claude still unreachable (${truncate(why, 120)}); next try ${clock(retryAt)}`);
    this.refreshHold();
    if (this.authRetryTimer) clearTimeout(this.authRetryTimer);
    this.authRetryTimer = setTimeout(() => {
      this.authRetryTimer = undefined;
      if (this.stopping) return;
      void this.checkAuth().then((ok) => {
        if (ok) this.tick();
      });
    }, delayMs);
    this.authRetryTimer.unref?.();
  }

  /** New agent turns wait: auth failed, offline, a usage limit, or usage above the reserve. */
  private held(now = Date.now()): boolean {
    return this.authFailed || !!this.offline || this.limited(now) || !!this.reserved(now);
  }

  /** claude.usageReserve: the window above its reserve, while it lasts. */
  private reserved(now = Date.now()): ReturnType<typeof reserveHold> {
    return reserveHold(this.fm.status.usage, this.cfg.usageReserve, now);
  }

  /** What holds new turns right now (C9 foreman.status.hold), most important first. */
  private currentHold(now = Date.now()): ForemanHold | undefined {
    if (this.authFailed) return { reason: 'auth', message: this.authMessage || 'Claude authentication failed' };
    if (this.offline) return { reason: 'offline', until: this.offline.retryAt, message: this.offline.message };
    const l = this.st.limit;
    if (l && now < l.until) return { reason: 'usage', until: l.until, message: `Usage limit reached${l.type ? ` (${l.type.replace(/_/g, ' ')})` : ''}: agents wait until ${clock(l.until)}, then resume` };
    const r = this.reserved(now);
    if (r) return { reason: 'usage', until: r.until, message: `Usage ${r.window.label} at ${r.window.pct}% (reserve ${r.limit}%): no new agent turns until ${clock(r.until)}, so some is left for you` };
    return undefined;
  }

  /**
   * Publish the hold (foreman.status.hold) and react to it changing: the reserve is announced once
   * when it starts holding; when nothing holds any more, queued work starts.
   */
  private refreshHold(now = Date.now()): void {
    const hold = this.currentHold(now);
    const before = this.fm.status.hold;
    const reserve = !!this.reserved(now) && !this.limited(now) && !this.authFailed && !this.offline;
    if (reserve && !this.reserveActive && hold) {
      this.fm.bus.feed('system', `${hold.message}.`);
      this.fm.notify('warn', hold.message);
      this.fm.notifyExternal('usage', hold.message);
    }
    this.reserveActive = reserve;
    if (JSON.stringify(hold) === JSON.stringify(before)) return;
    this.fm.setStatus({ hold });
    if (before && !hold && !this.stopping) {
      for (const id of this.queues.keys()) this.pump(id);
      this.tick();
    }
  }

  /** An agent's own open question (the PR watcher's decisions to the user are not the agent's). */
  private openQuestion(agentId: string): Decision | undefined {
    return this.fm.decisions.open().find((d) => d.kind === 'question' && d.agentId === agentId && !this.prs.owns(d.id));
  }

  /** A job matching `pred` is queued, running or paused (waiting for /resume) for this agent. */
  private hasQueued(agentId: string, pred: (j: Job) => boolean): boolean {
    const running = this.running.get(agentId);
    const paused = this.pausedJobs.get(agentId);
    const later = this.delayed.get(agentId)?.job;
    return (this.queues.get(agentId) ?? []).some(pred) || (running ? pred(running.job) : false) || (paused ? pred(paused) : false) || (later ? pred(later) : false);
  }

  /**
   * After a restart: re-attach or re-queue everything that was in flight, then reconcile every
   * non-terminal state with what is actually running, so nothing waits forever.
   */
  private recover(): void {
    const st = this.st;
    // permission prompts from a dead process are moot; the resumed agent retries the tool
    for (const d of this.fm.decisions.open().filter((x) => x.kind === 'permission')) this.fm.decisions.cancel(d.id, 'Foreman restarted');
    for (const [agentId, inf] of Object.entries(st.inflight)) {
      // a lead released while the Foreman was down (or dropped from claude.leads): its goals are
      // marlow's now, the reconciliation below hands them over
      const offDuty = this.fm.isLead(agentId) && (!this.fm.leads.onDuty(agentId) || (!!inf.goalId && this.fm.leadOf(this.fm.goal(inf.goalId)) !== agentId));
      if (this.isStopped(agentId) || !this.fm.agent(agentId) || offDuty) {
        delete st.inflight[agentId];
        continue;
      }
      const openQ = this.openQuestion(agentId);
      if (openQ) {
        this.fm.log.info(`recover: ${agentId} is waiting on ${openQ.id}; will resume after the answer`);
        this.fm.setAgent(agentId, { state: 'waiting_user', station: 'user', activity: 'waiting for your answer' });
        continue;
      }
      const session = this.fm.store.data.sessions[inf.sessionKey];
      if (session?.sessionId) {
        this.fm.log.info(`recover: resuming ${agentId} (${inf.kind}${inf.taskId ? ` ${inf.taskId}` : ''})`);
        this.enqueue({ kind: inf.kind, agentId, sessionKey: inf.sessionKey, prompt: RESUME_PROMPT, resumed: true, ...(inf.taskId ? { taskId: inf.taskId } : {}), ...(inf.goalId ? { goalId: inf.goalId } : {}) });
      } else {
        // the turn died before it had a session: the reconciliation below starts it again
        delete st.inflight[agentId];
      }
    }
    this.reconcile();
    this.fm.store.markDirty();
  }

  /** Bring planning goals, doing tasks and review tasks back in line with running/queued jobs. */
  private reconcile(): void {
    const st = this.st;
    // goals still planning with nobody planning them
    for (const g of this.fm.goals().filter((x) => x.status === 'planning')) {
      const lead = this.fm.leadOf(g);
      const leadOnIt = st.inflight[lead]?.goalId === g.id || this.hasQueued(lead, (j) => j.goalId === g.id) || this.openQuestion(lead) !== undefined;
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

  /** Tasks in review with no merge decision and no review job: CI + review (again). */
  private sweepReviews(): void {
    const st = this.st;
    for (const t of this.fm.tasks.list()) {
      if (t.status !== 'review' || !t.worktree) continue;
      if (this.fm.decisions.open().some((d) => d.taskId === t.id)) continue;
      if (Object.values(st.inflight).some((i) => i.taskId === t.id)) continue;
      if (this.reviewing.has(t.id) || this.hasQueued(this.fm.leadOfTask(t), (j) => j.taskId === t.id) || (t.assignee && this.hasQueued(t.assignee, (j) => j.taskId === t.id))) continue;
      void this.afterWorkerDone(t.id);
    }
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

  // ---- turn teardown ------------------------------------------------------------------------

  /** Abort a turn: remember why, snapshot its process tree while the CLI is still alive, close it. */
  private abortTurn(r: Running, reason: AbortReason): void {
    r.reason = reason;
    const pid = r.child?.pid;
    if (pid && alive(r.child) && !r.tree) r.tree = processTable().then((t) => (t ? descendantsOf(t, pid) : undefined)).catch(() => undefined);
    r.abort.abort();
  }

  /**
   * Make sure an aborted turn's CLI process and everything it started are gone. The SDK's close()
   * gives the CLI ~2 s to exit; after that its tree is killed. Processes that outlived the CLI
   * (orphans on Windows) come from the snapshot taken at abort time plus, read after the CLI
   * exited, every newer process whose parent chain leads to the CLI's pid (unless that pid was
   * reused). Only processes that are still the same ones (pid + creation time) are killed.
   */
  private reap(r: Running, graceMs = 4000): Promise<void> {
    r.reaping ??= this.doReap(r, graceMs).catch((e) => this.fm.log.warn(`clean-up of ${r.job.agentId}'s turn: ${(e as Error).message}`));
    return r.reaping;
  }

  private async doReap(r: Running, graceMs: number): Promise<void> {
    const child = r.child;
    if (!child?.pid) return;
    if (alive(child)) {
      await Promise.race([new Promise<void>((res) => child.once('exit', () => res())), sleep(graceMs)]);
      if (alive(child)) {
        killTree(child);
        await Promise.race([new Promise<void>((res) => child.once('exit', () => res())), sleep(2000)]);
      }
    }
    const snapshot = r.tree ? await r.tree : undefined;
    const table = await processTable();
    if (!table) {
      this.fm.log.warn(`could not read the process table to check for processes left by ${r.job.agentId}'s stopped turn`);
      return;
    }
    const targets = new Map<number, ProcEntry>();
    for (const e of [...(snapshot ?? []), ...orphansOf(table, child.pid, r.spawnedAt ?? 0)]) targets.set(e.pid, e);
    const killed = (await killSnapshot([...targets.values()], table)) ?? [];
    if (killed.length) this.fm.log.info(`killed ${killed.length} leftover process(es) of ${r.job.agentId}'s stopped turn (pids ${killed.join(', ')})`);
  }

  /** Wait until an agent's current/last turn is completely over (CLI exited, its processes gone). */
  private async quiesce(agentId: string, maxMs = 15_000): Promise<void> {
    const r = this.running.get(agentId) ?? this.lastTurn.get(agentId);
    if (!r) return;
    if (r.done) await Promise.race([r.done.catch(() => undefined), sleep(maxMs)]);
    await this.reap(r);
  }

  /**
   * A task leaves `fromAgent` (stop, reassign): hold it off the board until that agent's turn is
   * really over, then commit its work on its branch (the next worker starts from that branch).
   */
  private handOff(taskId: string, fromAgent: string, why: string): void {
    if (this.handoffs.has(taskId)) return;
    const p = (async () => {
      try {
        await this.quiesce(fromAgent);
        const t = this.fm.tasks.get(taskId);
        const wt = t?.worktree && t.repoId ? this.fm.repos.findWorktree(t.repoId, t.worktree) : undefined;
        if (t && wt && wt.agentId === fromAgent && wt.status === 'active') {
          await this.fm.repos.abandon(t.repoId!, wt.id, `agentcraft: ${t.id} work in progress (${why})`);
        }
      } catch (e) {
        this.fm.log.warn(`hand-off of ${taskId} from ${fromAgent}: ${(e as Error).message}`);
      } finally {
        this.handoffs.delete(taskId);
        this.tick();
      }
    })();
    this.handoffs.set(taskId, p);
  }

  // ---- usage limits -------------------------------------------------------------------------

  /** A usage limit is in force: no turn starts (queued jobs wait, then resume). */
  private limited(now = Date.now()): boolean {
    return !!this.st.limit && now < this.st.limit.until;
  }

  /** Workers allowed at once: fewer while the plan reports a usage warning. */
  private maxWorkers(now = Date.now()): number {
    const t = this.st.throttle;
    return t && now < t.until ? Math.min(this.cfg.maxConcurrent, this.cfg.throttleConcurrent) : this.cfg.maxConcurrent;
  }

  private baseStatusMessage(): string {
    return `Claude (lead ${this.cfg.leadModel}, workers ${this.cfg.workerModel})`;
  }

  /** A live usage report from a running turn. */
  private onRateLimit(r: RateLimitReport): void {
    if (r.type && typeof r.utilization === 'number') this.setUsage(withWindow(pruneUsage(this.fm.status.usage), r.type, r.utilization * 100, r.resetsAt));
    else if (r.type && r.status === 'rejected') this.setUsage(withWindow(pruneUsage(this.fm.status.usage), r.type, 100, r.resetsAt));
    if (r.status === 'rejected') this.setLimit(r.resetsAt, r.type);
    else if (r.status === 'allowed_warning') {
      const until = r.resetsAt ?? Date.now() + THROTTLE_DEFAULT_MS;
      const prev = this.st.throttle;
      if (prev && prev.until >= until) return;
      this.st.throttle = { until, ...(r.type ? { type: r.type } : {}) };
      this.fm.store.markDirty();
      if (this.maxWorkers() < this.cfg.maxConcurrent) {
        const pct = r.utilization !== undefined ? ` (${Math.round(r.utilization * 100)}%)` : '';
        this.fm.bus.feed('system', `Usage warning${r.type ? ` (${r.type.replace(/_/g, ' ')})` : ''}${pct}: ${this.maxWorkers()} worker(s) at a time until ${clock(until)}`);
      }
      this.armLimitTimer();
    }
  }

  private setUsage(u: ReturnType<typeof pruneUsage>): void {
    if (!u) return;
    const before = usageLine(this.fm.status.usage);
    this.fm.setStatus({ usage: u });
    const now = usageLine(u);
    if (now !== before) this.fm.log.info(`plan usage: ${now}`);
    this.refreshHold();
  }

  /** At most every few minutes, while some agent has a live session: the CLI's /usage windows. */
  private lastUsageRead = 0;
  private refreshUsage(q: object): void {
    if (!this.cfg.useClaudeLogin || Date.now() - this.lastUsageRead < USAGE_REFRESH_MS) return;
    this.lastUsageRead = Date.now();
    void readPlanUsage(q, pruneUsage(this.fm.status.usage)).then((u) => this.setUsage(u));
  }

  /** Stop starting turns until the limit resets (or a backoff when it did not say when). */
  private setLimit(resetsAt: number | undefined, type: string | undefined): void {
    const until = resetsAt ?? Date.now() + this.limitBackoffMs;
    if (!resetsAt) this.limitBackoffMs = Math.min(LIMIT_BACKOFF_MAX_MS, this.limitBackoffMs * 2);
    const prev = this.st.limit;
    if (prev && prev.until >= until) return;
    this.st.limit = { until, ...(type ? { type } : {}) };
    this.fm.store.markDirty();
    const what = `Usage limit reached${type ? ` (${type.replace(/_/g, ' ')})` : ''}`;
    this.fm.setStatus({ message: `${what}: agents wait until ${clock(until)}, then resume` });
    if (!prev || Date.now() >= prev.until) {
      this.fm.bus.feed('error', `${what}. Nobody starts a new turn until ${clock(until)}; interrupted work resumes then.`);
      this.fm.notify('warn', `${what}: AgentCraft resumes at ${clock(until)}`);
      this.fm.notifyExternal('usage', `${what}: agents resume at ${clock(until)}`);
    }
    this.armLimitTimer();
    this.refreshHold();
  }

  /** Wake up when the earliest limit/throttle window ends. */
  private armLimitTimer(): void {
    if (this.limitTimer) clearTimeout(this.limitTimer);
    this.limitTimer = undefined;
    const now = Date.now();
    const ends = [this.st.limit?.until, this.st.throttle?.until].filter((t): t is number => typeof t === 'number' && t > now);
    if (!ends.length) {
      this.liftExpired();
      return;
    }
    // setTimeout caps at ~24.8 days; re-arm in steps
    const delay = Math.min(Math.min(...ends) - now + 1000, 2 ** 31 - 1);
    this.limitTimer = setTimeout(() => {
      this.limitTimer = undefined;
      this.liftExpired();
      this.armLimitTimer();
    }, delay);
    this.limitTimer.unref?.();
  }

  private liftExpired(): void {
    const now = Date.now();
    let changed = false;
    if (this.st.limit && now >= this.st.limit.until) {
      delete this.st.limit;
      changed = true;
      this.fm.setStatus({ message: this.baseStatusMessage() });
      this.fm.bus.feed('system', 'Usage limit reset: the team picks up where it stopped');
      for (const id of this.queues.keys()) this.pump(id);
    }
    if (this.st.throttle && now >= this.st.throttle.until) {
      delete this.st.throttle;
      changed = true;
    }
    if (changed) {
      this.fm.store.markDirty();
      this.tick();
    }
    this.refreshHold(now);
  }

  /**
   * A turn ended because of the usage limit: keep its task where it is and run the same job again
   * (resuming its session) once the limit resets, instead of marking the task failed/blocked.
   */
  private holdForLimit(job: Job, stats: TurnStats): void {
    if (!this.limited()) this.setLimit(stats.rateLimit?.resetsAt, stats.rateLimit?.type);
    const hasSession = !!this.fm.store.data.sessions[job.sessionKey]?.sessionId;
    const next: Job = hasSession
      ? { ...job, fresh: false, resumed: true, prompt: 'A usage limit stopped your last turn; it has reset now. Re-check where you were (your worktree, the task board) and continue your current job.' }
      : job;
    const until = this.st.limit?.until;
    this.fm.setAgent(job.agentId, { state: 'blocked', activity: `usage limit - resumes ${until ? clock(until) : 'later'}` });
    this.fm.agentLog(job.agentId, 'error', `usage limit: this ${job.kind} resumes when it resets${until ? ` (${clock(until)})` : ''}`);
    const q = this.queues.get(job.agentId) ?? [];
    q.unshift(next);
    this.queues.set(job.agentId, q);
  }

  /** Scheduler failed (git error, busy directory...): try again later instead of stalling. */
  private retryLater(): void {
    if (this.retryTimer || this.stopping) return;
    const delay = this.retryDelayMs;
    this.retryDelayMs = Math.min(60_000, this.retryDelayMs * 2);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      this.tick();
    }, delay);
    this.retryTimer.unref?.();
  }

  // ---- goals & scheduling -------------------------------------------------------------------

  async submitGoal(goal: Goal): Promise<void> {
    if (this.authFailed) {
      this.fm.setGoal(goal.id, { status: 'failed' });
      throw new ClientError(`Claude is not available: ${this.fm.status.message ?? 'auth failed'}`);
    }
    const repo = this.fm.repos.require(goal.repoId!);
    const lead = this.fm.leadOf(goal);
    if (this.isStopped(lead)) {
      this.setStopped(lead, false);
      this.fm.bus.feed('system', `${this.fm.nameOf(lead)} is back on shift for the new goal`, { agentId: lead });
    }
    for (const w of [lead, ...this.team]) if (!this.isStopped(w)) this.fm.setAgent(w, { active: true });
    this.fm.setAgent(lead, { state: 'thinking', station: 'meeting', activity: 'reading the goal', repoId: repo.id });
    // Goal.branch ("on <branch>: ..." or goal.submit branch) continues one of the user's branches
    // (e.g. one started in Claude Desktop)
    const want = goal.branch;
    if (want) {
      try {
        const b = await this.fm.repos.useBranch(repo.id, want);
        this.st.goalBranch[goal.id] = { repoId: repo.id, ...b };
        this.fm.store.markDirty();
        this.fm.bus.feed('goal', `This goal continues your branch ${b.branch} in ${repo.name}: tasks start from it and approved work is added to it${b.onRemote ? ' (and pushed)' : ''}`, { agentId: lead, goalId: goal.id });
      } catch (e) {
        this.fm.clearGoalBranch(goal.id);
        this.fm.bus.feed('error', `Not building on "${want}": ${(e as Error).message}. Planning it as a normal goal on ${repo.branch}.`, { agentId: lead, goalId: goal.id });
      }
    }
    const gb = this.st.goalBranch[goal.id];
    let earlier = '';
    if (gb && this.history) {
      // the sessions that worked on that branch: the lead reads them before planning
      const found = await this.history.find({ branch: gb.branch, limit: 3 }).catch((e) => {
        this.fm.log.warn(`session history: ${(e as Error).message}`);
        return [];
      });
      if (found.length) {
        earlier = `\n\nEarlier Claude sessions that worked on ${gb.branch} (best match first):\n${found.map(sessionLine).join('\n')}\nRead the most relevant with read_session before planning (where it stopped, what was decided, what is left), and put what matters and the session id into the task descriptions.`;
        this.fm.bus.feed('goal', `Found ${found.length} earlier session${found.length === 1 ? '' : 's'} on ${gb.branch} for ${this.fm.nameOf(lead)} to read`, { agentId: lead, goalId: goal.id });
      }
    }
    this.enqueue({ kind: 'plan', agentId: lead, goalId: goal.id, sessionKey: `${lead}:${goal.id}`, fresh: true, prompt: `${planPrompt(this.fm, goal, repo.path, gb?.branch ?? repo.branch)}${earlier}` });
  }

  private promoteGoal(goal: Goal, why: string): void {
    if (goal.status !== 'planning') return;
    const n = this.fm.tasks.forGoal(goal.id).length;
    this.fm.setGoal(goal.id, { status: 'active' });
    const lead = this.fm.leadOf(goal);
    this.fm.bus.feed('plan', `${this.fm.nameOf(lead)} planned the goal into ${n} task${n === 1 ? '' : 's'}${why === 'recovered' ? ' (picked up after a restart)' : ''}`, { agentId: lead, goalId: goal.id });
    this.tick();
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

  private workersRunning(): number {
    return [...this.running.keys()].filter((id) => !this.fm.isLead(id)).length;
  }

  private isFree(w: string): boolean {
    const a = this.fm.agent(w);
    if (!a || !a.active || a.paused || this.isStopped(w) || !this.team.includes(w)) return false;
    if (this.running.has(w) || this.delayed.has(w) || (this.queues.get(w)?.length ?? 0) > 0) return false;
    return !this.fm.tasks.list().some((t) => t.assignee === w && t.status === 'doing');
  }

  private async schedule(): Promise<void> {
    if (this.stopping || this.held()) return;
    // the design queue first: the goal loop below returns early when the workers are at the cap
    this.designs.kick();
    for (const goal of this.fm.goals().filter((g) => g.status === 'active')) {
      for (const t of this.fm.tasks.ready(goal.id)) {
        if (this.workersRunning() >= this.maxWorkers() || this.turnCapReached()) return;
        if (this.handoffs.has(t.id)) continue; // the previous worker's turn is still winding down
        let w: string | undefined;
        if (t.assignee && this.team.includes(t.assignee) && !this.isStopped(t.assignee)) {
          if (!this.isFree(t.assignee)) continue; // wait for the intended worker
          w = t.assignee;
        } else {
          w = this.team.find((x) => this.isFree(x));
        }
        if (!w) continue;
        try {
          await this.startWork(w, t, goal);
          this.retryDelayMs = 2000;
        } catch (e) {
          // the task stays on the board (assigned to w); try again shortly
          this.fm.log.error(`could not start ${t.id} for ${w}: ${(e as Error).message}`);
          this.retryLater();
        }
      }
    }
    // pump queues that were held back by the concurrency cap
    for (const id of this.queues.keys()) this.pump(id);
  }

  private async startWork(agentId: string, t: Task, goal: Goal): Promise<void> {
    if (!t.repoId) t.repoId = goal.repoId;
    this.fm.tasks.update(t.id, { assignee: agentId });
    // a task another worker started (stopped / reassigned): continue from that worker's branch,
    // whether or not its worktree was already wound down (abandoned)
    let startPoint: string | undefined;
    let continuesFrom: string | undefined;
    const prev = t.worktree ? this.fm.repos.findWorktree(t.repoId!, t.worktree) : undefined;
    const fold = this.st.foldIns[t.id];
    if (fold && prev && prev.status === 'merged' && prev.agentId !== agentId) {
      // review fixes by another worker: continue the branch the pull request was pushed from
      startPoint = prev.branch;
      continuesFrom = prev.agentId;
      this.fm.bus.feed('task', `${this.fm.nameOf(agentId)} makes the review fixes for ${t.id} on ${this.fm.nameOf(prev.agentId)}'s branch`, { agentId, taskId: t.id });
    } else if (prev && prev.agentId !== agentId && prev.status !== 'merged') {
      if (prev.status === 'active') {
        // nobody prepared the hand-off (e.g. reconciled after a restart): finish it here
        if (this.lastTurn.get(prev.agentId)?.job.taskId === t.id) await this.quiesce(prev.agentId);
        await this.fm.repos.abandon(t.repoId!, prev.id, `agentcraft: ${t.id} work in progress (handed to ${this.fm.nameOf(agentId)})`).catch((e) => this.fm.log.warn(`abandon ${prev.id}: ${(e as Error).message}`));
      }
      if ((await this.fm.repos.commitsAhead(t.repoId!, prev.branch, prev.base)) > 0) {
        startPoint = prev.branch;
        continuesFrom = prev.agentId;
        this.fm.bus.feed('task', `${this.fm.nameOf(agentId)} continues ${t.id} from ${this.fm.nameOf(prev.agentId)}'s branch`, { agentId, taskId: t.id });
      }
    }
    const base = this.baseFor(t);
    const wt = await this.fm.repos.createWorktree(t.repoId!, agentId, t, { ...(startPoint ? { startPoint } : {}), ...(base ? { base } : {}) });
    this.fm.tasks.update(t.id, { branch: wt.branch, worktree: wt.id });
    this.fm.tasks.setStatus(t.id, 'doing');
    this.fm.setAgent(agentId, { taskId: t.id, repoId: t.repoId!, worktree: wt.id, state: 'thinking', station: 'desk', activity: `starting ${t.id}` });
    this.fm.bus.feed('task', fold ? `${this.fm.nameOf(agentId)} started the review fixes for ${t.id}${t.pr ? ` (PR #${t.pr.id})` : ''}` : `${this.fm.nameOf(agentId)} started ${t.id}: ${t.title}`, { agentId, taskId: t.id });
    const inbox = formatInbox(this.fm.bus.inbox(agentId, { markRead: true }), (id) => this.fm.nameOf(id));
    const prompt = fold ? foldInPrompt(this.fm, t, goal, wt, fold.notes, inbox, continuesFrom) : workPrompt(this.fm, t, goal, wt, inbox, continuesFrom, this.planIdOf(goal.id));
    this.enqueue({ kind: 'work', agentId, taskId: t.id, goalId: goal.id, sessionKey: `${agentId}:${t.id}`, fresh: !this.fm.store.data.sessions[`${agentId}:${t.id}`]?.sessionId, prompt });
  }

  // ---- job queue ----------------------------------------------------------------------------

  private enqueue(job: Job): void {
    const q = this.queues.get(job.agentId) ?? [];
    q.push(job);
    this.queues.set(job.agentId, q);
    this.pump(job.agentId);
  }

  private pump(agentId: string): void {
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

  private env(who: { agentId?: string; cwd?: string } = {}): Record<string, string | undefined> {
    return scrubEnv(withAuthMode(agentEnv(process.env, who), this.cfg.useClaudeLogin));
  }

  private async cwdFor(job: Job): Promise<{ cwd: string; role: 'lead' | 'worker'; repoId: string }> {
    if (this.fm.isLead(job.agentId)) {
      const goal = job.goalId ? this.fm.goal(job.goalId) : this.fm.currentGoalOf(job.agentId);
      const own = this.fm.leads.record(job.agentId)?.repos.map((id) => this.fm.repos.get(id)).find(Boolean);
      // (a goal whose repository was removed since: the lead reads its own / the default one)
      const repo = (goal?.repoId ? this.fm.repos.get(goal.repoId) : undefined) ?? own ?? this.fm.repos.defaultRepo();
      if (!repo) throw new Error('no repo for the lead');
      // read-only views of every repository's base: the lead plans against what workers start from
      const gb = goal ? this.st.goalBranch[goal.id] : undefined;
      const views = await Promise.all(
        this.fm.repos.list().map((r) =>
          this.fm.repos.leadView(r.id, gb && gb.repoId === r.id ? gb.branch : undefined).catch((e) => {
            this.fm.log.warn(`lead view of ${r.id}: ${(e as Error).message}; the lead reads the checkout`);
            return undefined;
          }),
        ),
      );
      const view = views[this.fm.repos.list().indexOf(repo)];
      return { cwd: view ?? repo.path, role: 'lead', repoId: repo.id };
    }
    const t = job.taskId ? this.fm.tasks.get(job.taskId) : undefined;
    if (!t?.worktree || !t.repoId) throw new Error(`job for ${job.agentId} has no worktree`);
    return { cwd: this.fm.repos.requireWorktree(t.repoId, t.worktree).path, role: 'worker', repoId: t.repoId };
  }

  /**
   * An agent's role in a repository (repoSettings.roles): the repository's agent file, read from
   * `dir` (the worker's worktree, or the checkout for the lead) so it follows the branch.
   */
  repoRole(agentId: string, repoId: string, dir: string): RepoRole | undefined {
    const spec = this.fm.repos.settingsFor(repoId).roles?.[agentId];
    if (!spec) return undefined;
    const a = readAgentFile(agentFilePath(spec, path.join(dir, '.claude', 'agents'), dir));
    if (typeof a === 'string') {
      const key = `${repoId}:${agentId}:${a}`;
      if (!this.roleProblems.has(key)) {
        this.roleProblems.add(key);
        this.fm.log.warn(`repoSettings roles: ${this.fm.nameOf(agentId)} in ${repoId}: ${a}`);
      }
      return undefined;
    }
    return { name: a.name, description: a.description, prompt: a.prompt, ...(a.model ? { model: a.model } : {}), ...(a.effort ? { effort: a.effort } : {}) };
  }

  /** The user's branch a task builds on: its own (create_task base), else its goal's in that repository. */
  private baseFor(t: Task): string | undefined {
    const own = this.st.taskBase[t.id];
    if (own) return own;
    const gb = t.goalId ? this.st.goalBranch[t.goalId] : undefined;
    return gb && gb.repoId === t.repoId ? gb.branch : undefined;
  }

  /** Subagents are on for this repository: globally, or the repository's own (repoSettings.subagents "repo"). */
  private subagentsOn(repoId: string | undefined): boolean {
    return this.cfg.subagents.enabled || (!!repoId && this.fm.repos.settingsFor(repoId).subagents === 'repo');
  }

  private canUseTool(agentId: string, role: 'lead' | 'worker', cwd: string, turn: TurnHandle, repoId?: string): CanUseTool {
    return async (toolName, input, opts): Promise<PermissionResult> => {
      // a stopped/paused/cancelled turn runs nothing more, even if its CLI has not exited yet
      if (turn.signal.aborted) return { behavior: 'deny', message: `Your turn was stopped by ${userName()}.`, interrupt: true };
      const verdict = classifyToolUse(toolName, input, this.policyContext(agentId, role, cwd, repoId));
      if (verdict.action === 'allow') return { behavior: 'allow', updatedInput: input };
      if (verdict.action === 'deny') {
        this.fm.agentLog(agentId, 'error', `blocked: ${describeToolCall(toolName, input)} (${verdict.reason})`);
        return { behavior: 'deny', message: verdict.reason };
      }
      const prev = this.fm.agent(agentId);
      const prevState = prev ? { state: prev.state, station: prev.station, activity: prev.activity } : undefined;
      const t = prev?.taskId;
      const d = this.fm.createDecision({
        agentId,
        kind: 'permission',
        tool: toolName,
        question: `${this.fm.nameOf(agentId)} wants to run ${truncate(describeToolCall(toolName, input), 160)}`,
        options: [...PERMISSION_OPTIONS],
        context: `${verdict.reason}\ncwd: ${cwd}\n"${PERMISSION_OPTIONS[1]}" covers: ${[...new Set(verdict.ruleKeys.map(describeRuleKey))].join('; ')}${opts.title ? `\n${opts.title}` : ''}`,
        ...(t ? { taskId: t } : turn.goalId ? { goalId: turn.goalId } : {}),
      });
      this.fm.setAgent(agentId, { state: 'waiting_user', station: 'user', activity: 'asking permission' });
      this.fm.agentLog(agentId, 'tool', `permission? ${describeToolCall(toolName, input)}`);
      const onAbort = () => this.fm.decisions.cancel(d.id, 'turn stopped');
      if (opts.signal.aborted) onAbort();
      else opts.signal.addEventListener('abort', onAbort, { once: true });
      const res = await this.fm.decisions.wait(d.id);
      opts.signal.removeEventListener('abort', onAbort);
      if (opts.signal.aborted) return { behavior: 'deny', message: 'The turn was stopped.' };
      if (prevState) this.fm.setAgent(agentId, prevState);
      const opt = res.answer?.option;
      if (res.status === 'answered' && (opt === PERMISSION_OPTIONS[0] || opt === PERMISSION_OPTIONS[1])) {
        if (opt === PERMISSION_OPTIONS[1]) {
          // every key the call needed: each is scoped (see policy.ts), so this grants exactly
          // what the prompt listed
          const rules = (this.fm.store.data.permissionRules[agentId] ??= []);
          for (const k of verdict.ruleKeys) if (!rules.includes(k)) rules.push(k);
          this.fm.store.markDirty();
        }
        this.fm.agentLog(agentId, 'result', `${userName()} allowed: ${describeToolCall(toolName, input)}`);
        return { behavior: 'allow', updatedInput: input };
      }
      this.fm.agentLog(agentId, 'error', `${res.status === 'cancelled' ? 'Permission request withdrawn' : `${userName()} denied`}: ${describeToolCall(toolName, input)}`);
      return { behavior: 'deny', message: `${userName()} denied this${res.answer?.text ? `: ${res.answer.text}` : ''}. Find another way or ask_user.` };
    };
  }

  /**
   * The repo's configured copy/setup for a new worker worktree (once per worktree). Returns a note
   * for the worker's prompt when setup failed, else ''.
   */
  private async prepareWorktree(agentId: string, taskId: string): Promise<string> {
    const t = this.fm.tasks.get(taskId);
    if (!t?.repoId || !t.worktree) return '';
    const s = this.fm.repos.settingsFor(t.repoId);
    if (!s.setup && !s.copy?.length) return '';
    const prev = this.fm.agent(agentId);
    if (s.setup) this.fm.setAgent(agentId, { state: 'running', station: 'desk', activity: 'setting up worktree' });
    try {
      const res = await this.fm.repos.prepareWorktree(t.repoId, t.worktree);
      if (res.copied.length) this.fm.agentLog(agentId, 'tool', `copied into worktree: ${res.copied.join(', ')}`);
      if (!res.setup) return '';
      const secs = (res.setup.durationMs / 1000).toFixed(1);
      if (res.setup.ok) {
        this.fm.agentLog(agentId, 'result', `worktree setup ok: ${res.setup.command} (${secs}s)`);
        return '';
      }
      this.fm.agentLog(agentId, 'error', `worktree setup FAILED: ${res.setup.command} (${secs}s)\n${res.setup.output.split('\n').slice(-6).join('\n')}`);
      this.fm.bus.feed('error', `${this.fm.nameOf(agentId)}: worktree setup failed for ${t.id} (${res.setup.command})`, { agentId, taskId: t.id });
      return `Note: the worktree setup command \`${res.setup.command}\` failed before you started:\n${res.setup.output}\nLook into it before relying on the dependencies it installs.`;
    } catch (e) {
      this.fm.log.warn(`worktree setup for ${t.id}: ${(e as Error).message}`);
      return '';
    } finally {
      if (prev && this.fm.agent(agentId)?.activity === 'setting up worktree') this.fm.setAgent(agentId, { state: prev.state, station: prev.station, activity: prev.activity });
    }
  }

  /**
   * Model and effort for a turn: the task's size (claude.taskModels) wins, then the agent's role in
   * the repository (repoSettings.roles), then its profile (claude.agents), then the role defaults.
   */
  modelFor(agentId: string, role: 'lead' | 'worker', taskId?: string, repoRole?: RepoRole): { model: string; effort: EffortLevel } {
    const profile = this.cfg.agents[agentId];
    let model = repoRole?.model ?? profile?.model ?? (role === 'lead' ? this.cfg.leadModel : this.cfg.workerModel);
    const effort = repoRole?.effort ?? profile?.effort ?? (role === 'lead' ? this.cfg.leadEffort : this.cfg.effort);
    const size = role === 'worker' && taskId ? this.st.taskSize[taskId] : undefined;
    if (size && this.cfg.taskModels[size]) model = this.cfg.taskModels[size]!;
    return { model, effort };
  }

  /**
   * Permission mode, tools, guardrail hook and the user's rules for one turn (claude.permissions,
   * claude.subagents). Policy mode keeps upstream's behaviour: every call through canUseTool.
   */
  private permissionOptions(agentId: string, role: 'lead' | 'worker', cwd: string, turn: TurnHandle, repoId?: string, mcpServers?: string[]): Partial<Options> {
    const p = this.cfg.permissions;
    const sub = this.subagentsOn(repoId);
    const settings = repoId ? this.fm.repos.settingsFor(repoId) : {};
    // the repository's own agent files (read from this agent's checkout), except those used as roles
    const repoAgents = settings.subagents === 'repo' ? loadRepoAgents(cwd, new Set(Object.values(settings.roles ?? {}).map((r) => r.replace(/\.md$/, '').split(/[\\/]/).pop()!))) : {};
    const defs = { ...this.subagentDefs, ...repoAgents };
    const tools = role === 'lead' ? ['Read', 'Grep', 'Glob'] : ['Read', 'Grep', 'Glob', 'Edit', 'Write', 'Bash', 'TodoWrite'];
    if (p.webTools) tools.push('WebFetch', 'WebSearch');
    if (sub) tools.push('Agent', 'Task');
    if (this.skillsPlugin) tools.push('Skill');
    const disallowed = ['Bash(git push:*)', ...(sub ? [] : ['Task', 'Agent']), ...(p.webTools ? [] : ['WebSearch', 'WebFetch'])];
    const rules = p.allow.length || p.deny.length || p.ask.length ? { permissions: { allow: p.allow, deny: p.deny, ask: p.ask } } : {};
    const connectors = this.cfg.context.connectors;
    // the Foreman's own files, token and port: denied before anything else, whatever the rules
    const hooks: HookCallbackMatcher[] = [
      {
        hooks: [
          foremanGuardHook(
            (tool, input) => foremanPrivateVerdict(tool, input, { role, cwd, foreman: this.foremanPrivate() }),
            (tool, reason, subagent) => this.fm.agentLog(agentId, 'error', `blocked${subagent ? ' (subagent)' : ''}: ${tool} (${truncate(reason, 160)})`),
          ),
        ],
      },
    ];
    // MCP tools: only the Foreman's own servers and the connectors listed, fail-closed (every turn)
    hooks.push({
      hooks: [
        connectorHook(
          () => ({ connectors: mcpServers ? [] : this.cfg.context.connectors, servers: mcpServers ?? [MCP_SERVER, ...Object.keys(this.cfg.context.mcpServers)] }),
          (tool, server) => this.fm.agentLog(agentId, 'error', `blocked ${tool}: MCP server "${server}" is not enabled for agents`),
        ),
      ],
    });
    if (p.mode === 'auto') {
      const guard = guardrailHook(
        (tool, input) => classifyToolUse(tool, input, this.policyContext(agentId, role, cwd, repoId)),
        () => this.protectedRoots(),
        (tool, decision, reason, subagent) =>
          this.fm.agentLog(agentId, decision === 'deny' ? 'error' : 'tool', `guardrail ${decision === 'deny' ? 'blocked' : 'asks you'}${subagent ? ' (subagent)' : ''}: ${tool} (${truncate(reason, 160)})`),
      );
      hooks.push({ hooks: [guard] });
    }
    return {
      permissionMode: p.mode === 'auto' ? 'auto' : 'default',
      canUseTool: this.canUseTool(agentId, role, cwd, turn, repoId),
      tools,
      disallowedTools: disallowed,
      // no Claude attribution in anything an agent commits or writes for a PR (C8); the object form
      // of `attribution` (older CLIs reject a boolean there), plus the deprecated switch
      settings: { ...rules, ...NO_ATTRIBUTION },
      ...(sub && Object.keys(defs).length ? { agents: defs } : {}),
      // claude.ai connectors: none unless listed (strict), listed ones only (hook)
      strictMcpConfig: connectors.length === 0,
      ...(hooks.length ? { hooks: { PreToolUse: hooks } } : {}),
    };
  }

  private async runJob(job: Job): Promise<void> {
    const agentId = job.agentId;
    const abort = new AbortController();
    const entry: Running = { abort, job };
    const turn: TurnHandle = { signal: abort.signal, reason: () => entry.reason, ...(job.goalId ? { goalId: job.goalId } : {}) };
    // (pump records this turn as lastTurn right after this synchronous part: this is the previous one)
    const previous = this.lastTurn.get(agentId);
    this.running.set(agentId, entry);
    let stats: TurnStats | undefined;
    let cwd = '';
    try {
      // a paused/stopped turn of this agent may still be winding down: never run two CLIs on one
      // agent (they could share a session)
      if (previous?.reaping) await Promise.race([previous.reaping, sleep(10_000)]);
      const where = await this.cwdFor(job);
      cwd = where.cwd;
      const role = where.role;
      const repoId = where.repoId;
      const roleDir = cwd;
      const roleOf = (id: string) => this.repoRole(id, repoId, roleDir);
      const session = this.fm.store.data.sessions[job.sessionKey];
      let resume = !job.fresh && session?.sessionId ? session.sessionId : undefined;
      // a lead's long-lived session for a goal: start over once it is old or long (seeded below)
      let rotated = '';
      if (resume && role === 'lead' && session) {
        session.startedAt ??= Date.now();
        const why = rotationDue(session, this.cfg.leadSession);
        if (why) {
          resume = undefined;
          rotated = this.rotationSeed(job, why);
          this.fm.log.info(`${agentId}: fresh session for ${job.sessionKey} (the last one is ${why})`);
          this.fm.agentLog(agentId, 'text', `Starting a fresh session for ${job.goalId ?? 'this work'} (the last one is ${why}); seeded with the plan, the board and the latest messages`);
        }
      }
      this.st.inflight[agentId] = { kind: job.kind, sessionKey: job.sessionKey, startedAt: Date.now(), ...(job.taskId ? { taskId: job.taskId } : {}), ...(job.goalId ? { goalId: job.goalId } : {}) };
      this.fm.store.markDirty();

      let systemAppend: string;
      if (role === 'lead') {
        const gb = job.goalId ? this.st.goalBranch[job.goalId] : undefined;
        const where = [
          leadRepoContext(this.fm, repoId, cwd, gb?.branch),
          gb ? `# This goal continues ${userName()}'s branch ${gb.branch}\nIt is ${userName()}'s own work in progress (maybe started in another Claude session). Tasks in ${gb.repoId} start from it and approved work is added to it${gb.onRemote ? ' and pushed' : ''}; no pull request is opened. Read what is already there before planning, and plan what is left.` : '',
        ]
          .filter(Boolean)
          .join('\n\n');
        systemAppend = `${leadSystemPrompt(this.fm, this.team, roleOf, agentId)}${where ? `\n\n${where}` : ''}`;
        // the goal's standing instructions, read fresh every turn
        const standing = this.fm.instructionsSection(job.goalId, `# Standing instructions for goal ${job.goalId}`);
        if (standing) systemAppend += `\n\n${standing}\nCarry them into the tasks you create (they are appended to new task descriptions automatically) and hold the workers' results to them in reviews.`;
      }
      else {
        const t = this.fm.tasks.require(job.taskId!);
        systemAppend = workerSystemPrompt(this.fm, agentId, this.fm.repos.requireWorktree(t.repoId!, t.worktree!), roleOf(agentId), this.fm.leadOfTask(t));
        // the goal's standing instructions, read fresh every turn (edits apply to running work)
        const standing = this.fm.instructionsSection(t.goalId);
        if (standing) systemAppend += `\n\n${standing}\nThey win over anything older in your task description.`;
      }
      if (this.history) {
        systemAppend +=
          role === 'lead'
            ? `\n\n# Earlier sessions\n${userName()}'s earlier Claude sessions in these repositories are searchable (find_sessions, read_session). When a goal refers to earlier work, find and read the relevant session before planning, then put what a worker needs, and the session id, into the task description.`
            : `\n\n# Earlier sessions\nIf your task names an earlier Claude session (an id), read it with read_session before you start; find_sessions searches others.`;
      }
      const priv = this.foremanPrivate();
      const extra = instructionsBlock(this.cfg.context, cwd, userName(), os.homedir(), this.fm.repos.get(repoId)?.path, (abs) => foremanPrivatePath(abs, priv));
      if (extra) systemAppend = `${systemAppend}\n\n${extra}`;
      const { model, effort } = this.modelFor(agentId, role, job.taskId, role === 'worker' ? roleOf(agentId) : undefined);
      const options: Options = {
        cwd,
        model,
        effort,
        maxTurns: role === 'lead' ? this.cfg.maxTurnsLead : this.cfg.maxTurnsWorker,
        settingSources: [],
        ...this.permissionOptions(agentId, role, cwd, turn, repoId),
        // the user's extra servers first, so the team tools server can never be replaced
        mcpServers: { ...this.cfg.context.mcpServers, [MCP_SERVER]: buildMcpServer(this.fm, agentId, role, this.hooks, turn, this.history) },
        ...(this.skillsPlugin ? { plugins: [{ type: 'local' as const, path: this.skillsPlugin.path, skipMcpDiscovery: true }], skills: this.skillsPlugin.ids } : {}),
        systemPrompt: { type: 'preset', preset: 'claude_code', append: systemAppend },
        abortController: abort,
        // the repository's env (e.g. a PATH for its Node version) on top; GIT_* never comes from it
        env: scrubEnv(withAuthMode({ ...this.env({ agentId, cwd }), ...this.fm.repos.envFor(repoId) }, this.cfg.useClaudeLogin)),
        // we spawn the CLI ourselves (same as the SDK's local spawn) so its pid is known: a stopped
        // turn's whole process tree can then be ended before its worktree is handed on
        spawnClaudeCodeProcess: this.spawner(entry, agentId),
        ...(resume ? { resume } : {}),
        ...(this.cfg.maxBudgetUsdPerTurn ? { maxBudgetUsd: this.cfg.maxBudgetUsdPerTurn } : {}),
      };
      this.fm.agentLog(agentId, 'text', `${resume ? 'Resuming' : 'Starting'} ${job.kind}${job.taskId ? ` ${job.taskId}` : ''} (${model})`);
      if (job.kind === 'followup' || job.resumed) this.fm.agentLog(agentId, 'text', truncate(job.prompt, 400));
      const mapper = new StreamMapper(this.fm, agentId, cwd, role, (r) => this.onRateLimit(r));
      const setupNote = role === 'worker' && job.taskId ? await this.prepareWorktree(agentId, job.taskId) : '';
      if (abort.signal.aborted) throw new Error('turn stopped during worktree setup');
      const timer = setTimeout(() => this.abortTurn(entry, 'timeout'), TURN_TIMEOUT_MS);
      timer.unref?.();
      // messages that arrived while the agent was not in a turn ride along with this prompt
      const unread = this.fm.bus.inbox(agentId, { markRead: true });
      // a goal another lead worked on before: what this lead takes over
      const takeover = role === 'lead' ? this.takeoverNote(agentId, job.goalId) : '';
      const withSetup = [setupNote, takeover, rotated, job.prompt].filter(Boolean).join('\n\n');
      const prompt = unread.length ? `${withSetup}\n\n[New messages]\n${formatInbox(unread, (id) => this.fm.nameOf(id))}` : withSetup;
      try {
        const q = this.queryFn({ prompt, options });
        this.refreshUsage(q);
        entry.q = q;
        // the abort signal alone lets a CLI finish what it is doing (seen in a real run: ~6 s of
        // further turns after /stop). close() force-ends the subprocess and its transports.
        const closeQuery = () => {
          try {
            q.close();
          } catch {
            /* already closed */
          }
        };
        if (abort.signal.aborted) closeQuery();
        else abort.signal.addEventListener('abort', closeQuery, { once: true });
        for await (const msg of q) {
          if (abort.signal.aborted) break; // nothing from an aborted turn reaches the world
          mapper.handle(msg);
          if (mapper.stats.sessionId && this.fm.store.data.sessions[job.sessionKey]?.sessionId !== mapper.stats.sessionId) {
            this.recordSession(job.sessionKey, mapper.stats.sessionId, model);
          }
        }
      } finally {
        clearTimeout(timer);
      }
      stats = mapper.stats;
      if (stats.sessionId) this.recordSession(job.sessionKey, stats.sessionId, model, stats);
      if (stats.authFailed) this.markAuthFailed(`Claude authentication failed (${stats.authFailed}). Run \`claude\` and /login, then restart the Foreman.`);
    } catch (e) {
      const aborted = abort.signal.aborted;
      if (!aborted) {
        const msg = (e as Error).message ?? String(e);
        this.fm.log.error(`${agentId} ${job.kind} failed: ${msg}`);
        this.fm.agentLog(agentId, 'error', `session error: ${truncate(msg, 400)}`);
        if (isAuthText(msg)) this.markAuthFailed(`Claude authentication failed: ${truncate(msg, 160)}`);
        stats = { isError: true, errors: [msg] };
        const l = limitFromText(msg);
        if (l.limited) {
          stats.limited = true;
          if (l.resetsAt) stats.rateLimit = { status: 'rejected', resetsAt: l.resetsAt };
        }
      }
    } finally {
      this.running.delete(agentId);
    }

    const reason = entry.reason;
    if (reason === 'shutdown') return; // inflight stays persisted -> resumed next start (stop() reaps)
    // an aborted turn's CLI and its processes must not linger (they would keep working); started
    // before anything is re-queued, so the next turn of this agent waits for it
    if (reason) void this.reap(entry);
    delete this.st.inflight[agentId];
    this.fm.store.markDirty();
    if (reason === 'pause') {
      const next: Job = { ...job, fresh: false, resumed: true, prompt: `${userName()} paused you and has now resumed you. Any question you had open was withdrawn; ask again if you still need it. Continue your current job.` };
      // resume may already have arrived while the aborted turn was unwinding
      if (this.fm.agent(agentId)?.paused) {
        this.pausedJobs.set(agentId, next);
        this.fm.setAgent(agentId, { state: 'idle', activity: 'paused' });
      } else this.enqueue(next);
    } else if (reason === 'stop') {
      if (this.isStopped(agentId)) this.fm.setAgent(agentId, { state: 'idle', station: 'lounge', activity: 'stopped - off shift', taskId: null, worktree: null });
    } else if (reason === 'cancel' && this.fm.isLead(agentId)) {
      // goal.cancel stopped the lead's turn for that goal
      this.fm.setAgent(agentId, { state: 'idle', station: 'meeting', activity: 'goal cancelled' });
    } else if (reason === 'cancel') {
      const a = this.fm.agent(agentId);
      if (a?.taskId === job.taskId) this.fm.setAgent(agentId, { state: 'idle', station: 'lounge', activity: 'task cancelled', taskId: null, worktree: null });
    } else if (stats?.isError && stats.limited) {
      this.holdForLimit(job, stats);
    } else if (reason === 'timeout') {
      this.fm.agentLog(agentId, 'error', `turn timed out after ${TURN_TIMEOUT_MS / 60_000} min`);
      await this.afterTurn(job, { isError: true, subtype: 'timeout', errors: ['turn timed out'] }).catch((e) => this.fm.log.error(`afterTurn ${agentId}: ${(e as Error).stack ?? e}`));
    } else {
      if (stats && !stats.isError) this.limitBackoffMs = LIMIT_BACKOFF_MS;
      await this.afterTurn(job, stats).catch((e) => this.fm.log.error(`afterTurn ${agentId}: ${(e as Error).stack ?? e}`));
    }
    this.pump(agentId);
    // a lead turn held back (usage warning, claude.maxConcurrentTurns) may start now
    for (const id of this.queues.keys()) if (id !== agentId && (this.fm.isLead(id) || this.cfg.maxConcurrentTurns)) this.pump(id);
    // the user's messages that came after the agent's last tool call: answer them now
    if (reason !== 'stop' && reason !== 'pause') this.deliverPending(agentId);
    this.tick();
  }

  /**
   * Messages from the user to this agent that nobody has read yet (they arrived after its last
   * agentcraft tool call, or while it was off shift): start a follow-up turn for them.
   */
  private deliverPending(agentId: string): void {
    if (this.stopping || this.isStopped(agentId) || this.running.has(agentId) || this.pausedJobs.has(agentId) || (this.queues.get(agentId)?.length ?? 0) > 0) return;
    const a = this.fm.agent(agentId);
    if (!a?.active || a.paused) return;
    const fromUser = this.fm.bus.inbox(agentId).filter((m) => m.from === 'user' && m.to === agentId);
    if (!fromUser.length) return;
    this.fm.log.info(`delivering ${fromUser.length} message(s) from ${userName()} to ${agentId} that arrived after its last turn`);
    this.onUserMessage(agentId, fromUser[fromUser.length - 1]!.text);
  }

  private recordSession(key: string, sessionId: string, model: string, stats?: TurnStats): void {
    const s = (this.fm.store.data.sessions[key] ??= { turns: 0, costUsd: 0, updatedAt: Date.now() });
    if (s.sessionId !== sessionId) {
      // a new session under this key (fresh plan, rotation): the earlier sessions' spend is kept
      if (s.sessionId) s.baseCostUsd = s.costUsd;
      s.sessionId = sessionId;
      s.startedAt = Date.now();
      s.sessionTurns = 0;
      s.sessionCostUsd = 0;
    }
    s.model = model;
    s.updatedAt = Date.now();
    if (stats) {
      s.turns += stats.numTurns ?? 0;
      s.sessionTurns = (s.sessionTurns ?? 0) + 1;
      if (typeof stats.costUsd === 'number') {
        // total_cost_usd is cumulative per session (resumes continue from the saved total); records
        // from before rotation have no sessionCostUsd: their costUsd is that session's total
        const sessionSoFar = s.sessionCostUsd ?? s.costUsd - (s.baseCostUsd ?? 0);
        const prev = this.lastCost.get(sessionId) ?? sessionSoFar;
        const delta = Math.max(0, stats.costUsd - prev);
        this.lastCost.set(sessionId, stats.costUsd);
        s.sessionCostUsd = Math.max(sessionSoFar, stats.costUsd);
        s.costUsd = (s.baseCostUsd ?? 0) + s.sessionCostUsd;
        this.fm.setStatus({ costUsd: Math.round(((this.fm.status.costUsd ?? 0) + delta) * 1000) / 1000 });
      }
      s.lastResult = stats.subtype;
    }
    this.fm.store.markDirty();
  }

  /**
   * What a lead's fresh session (rotation) starts from: the goal's plan note, its task board and the
   * last messages of its thread. The earlier session is not available to it any more.
   */
  private rotationSeed(job: Job, why: string): string {
    const goal = job.goalId ? this.fm.goal(job.goalId) : undefined;
    const thread = this.fm.store.data.messages
      .filter((m) => (goal ? m.goalId === goal.id : true) && (m.from === job.agentId || m.to === job.agentId))
      .slice(-6)
      .map((m) => `- ${m.from === 'user' ? userName() : this.fm.nameOf(m.from)} -> ${m.to === 'user' ? userName() : this.fm.nameOf(m.to)}: ${truncate(m.text.replace(/\s+/g, ' '), 400)}`);
    return [
      `(This is a fresh session${goal ? ` for goal ${goal.id} "${truncate(goal.text.replace(/\s+/g, ' '), 160)}"` : ''}: the previous one was ${why}, so it was retired. Below is what carries over; read anything else from the task board, shared memory and the repositories.)`,
      goal ? `# The plan (shared memory)\n${planText(this.fm, goal, this.planIdOf(goal.id))}` : '',
      `# Task board${goal ? ' for the goal' : ''}\n${boardSummary(this.fm, goal?.id)}`,
      thread.length ? `# Latest messages\n${thread.join('\n')}` : '',
    ]
      .filter(Boolean)
      .join('\n\n');
  }

  /** The CLI is spawned by us (same as the SDK's local spawn) so its pid is known: an aborted turn's whole process tree can be ended. */
  private spawner(entry: Running, label: string): NonNullable<Options['spawnClaudeCodeProcess']> {
    return (o) => {
      const child = spawn(o.command, o.args, { cwd: o.cwd, env: scrubEnv(o.env as NodeJS.ProcessEnv), stdio: ['pipe', 'pipe', 'pipe'], signal: o.signal, windowsHide: true });
      child.stderr?.setEncoding('utf8');
      child.stderr?.on('data', (s: string) => this.fm.log.debug(`[${label} stderr] ${s.trim().slice(0, 300)}`));
      child.on('error', (e) => this.fm.log.debug(`[${label}] CLI process error: ${e.message}`));
      entry.child = child;
      entry.spawnedAt = Date.now();
      return child;
    };
  }

  // ---- turns outside the roster (design jobs) -----------------------------------------------

  /**
   * A design job's tool calls: the same policy as a worker in `cwd`, but nobody can answer a
   * permission prompt (there is no avatar to ask through), so whatever the policy would ask
   * about is refused with a reason the agent can work with.
   */
  private auxCanUseTool(logId: string, cwd: string, turn: TurnHandle): CanUseTool {
    return async (toolName, input): Promise<PermissionResult> => {
      if (turn.signal.aborted) return { behavior: 'deny', message: 'The job was stopped.', interrupt: true };
      const v = classifyToolUse(toolName, input, this.policyContext(logId, 'worker', cwd));
      if (v.action === 'allow') return { behavior: 'allow', updatedInput: input };
      this.fm.agentLog(logId, 'error', `blocked: ${describeToolCall(toolName, input)} (${v.reason})`);
      return {
        behavior: 'deny',
        message: v.action === 'deny' ? v.reason : `Not allowed in a design job (${v.reason}). Nobody can approve permission prompts here: work only inside ${cwd} with the kit and node, without network access or installs.`,
      };
    };
  }

  /**
   * One SDK turn for something that is not a roster agent (a building design job): the workers'
   * permission machinery (policy / auto mode guardrails) without prompts, subagents, web tools,
   * skills or the user's MCP servers; the same env, CLI process tracking, usage limit reports,
   * session records and abort/reap as agent turns.
   */
  runAuxTurn(spec: AuxTurnSpec): AuxTurn {
    const abort = new AbortController();
    const entry: Running = { abort, job: { kind: 'followup', agentId: spec.logId, prompt: spec.prompt, sessionKey: spec.sessionKey } };
    const done = this.doAuxTurn(spec, entry);
    const tracked: Promise<void> = done.then(() => undefined).finally(() => this.turnPromises.delete(tracked));
    this.turnPromises.add(tracked);
    return { abort: (reason) => this.abortTurn(entry, reason), done };
  }

  private async doAuxTurn(spec: AuxTurnSpec, entry: Running): Promise<{ stats: TurnStats; reason?: AbortReason }> {
    const { logId, cwd } = spec;
    const turn: TurnHandle = { signal: entry.abort.signal, reason: () => entry.reason };
    const { agents: _subagents, ...perm } = this.permissionOptions(logId, 'worker', cwd, turn, undefined, Object.keys(spec.mcpServers));
    const off = new Set(['Agent', 'Task', 'WebFetch', 'WebSearch', 'Skill']);
    const options: Options = {
      cwd,
      model: spec.model,
      effort: spec.effort,
      maxTurns: spec.maxTurns,
      settingSources: [],
      ...perm,
      // never auto mode: its classifier would decide what the policy asks about (network, ...);
      // here every such call reaches auxCanUseTool, which refuses it
      permissionMode: 'default',
      canUseTool: this.auxCanUseTool(logId, cwd, turn),
      tools: ((perm.tools as string[] | undefined) ?? []).filter((t) => !off.has(t)),
      disallowedTools: [...new Set([...(perm.disallowedTools ?? []), 'Agent', 'Task', 'WebFetch', 'WebSearch'])],
      strictMcpConfig: true,
      mcpServers: spec.mcpServers,
      systemPrompt: { type: 'preset', preset: 'claude_code', append: spec.systemAppend },
      abortController: entry.abort,
      env: this.env({ agentId: logId, cwd }),
      spawnClaudeCodeProcess: this.spawner(entry, logId),
      ...(spec.resume ? { resume: spec.resume } : {}),
      ...(this.cfg.maxBudgetUsdPerTurn ? { maxBudgetUsd: this.cfg.maxBudgetUsdPerTurn } : {}),
    };
    const mapper = new StreamMapper(this.fm, logId, cwd, 'worker', (r) => this.onRateLimit(r));
    this.fm.agentLog(logId, 'text', `${spec.resume ? 'Resuming' : 'Starting'} ${spec.sessionKey} (${spec.model})`);
    let stats: TurnStats;
    const timer = setTimeout(() => this.abortTurn(entry, 'timeout'), spec.timeoutMs);
    timer.unref?.();
    try {
      const q = this.queryFn({ prompt: spec.prompt, options });
      this.refreshUsage(q);
      entry.q = q;
      const closeQuery = () => {
        try {
          q.close();
        } catch {
          /* already closed */
        }
      };
      if (entry.abort.signal.aborted) closeQuery();
      else entry.abort.signal.addEventListener('abort', closeQuery, { once: true });
      for await (const msg of q) {
        if (entry.abort.signal.aborted) break;
        mapper.handle(msg);
        try {
          spec.onMessage?.(msg);
        } catch (e) {
          this.fm.log.warn(`${logId}: ${(e as Error).message}`);
        }
        if (mapper.stats.sessionId && this.fm.store.data.sessions[spec.sessionKey]?.sessionId !== mapper.stats.sessionId) this.recordSession(spec.sessionKey, mapper.stats.sessionId, spec.model);
      }
      stats = mapper.stats;
      if (stats.sessionId) this.recordSession(spec.sessionKey, stats.sessionId, spec.model, stats);
      if (stats.authFailed) this.markAuthFailed(`Claude authentication failed (${stats.authFailed}). Run \`claude\` and /login, then restart the Foreman.`);
    } catch (e) {
      stats = { ...mapper.stats };
      if (!entry.abort.signal.aborted) {
        const msg = (e as Error).message ?? String(e);
        this.fm.log.error(`${logId} ${spec.sessionKey} failed: ${msg}`);
        this.fm.agentLog(logId, 'error', `session error: ${truncate(msg, 400)}`);
        if (isAuthText(msg)) {
          stats.authFailed = truncate(msg, 160);
          this.markAuthFailed(`Claude authentication failed: ${truncate(msg, 160)}`);
        }
        stats.isError = true;
        stats.errors = [...stats.errors, msg];
        const l = limitFromText(msg);
        if (l.limited) {
          stats.limited = true;
          if (l.resetsAt) stats.rateLimit = { status: 'rejected', resetsAt: l.resetsAt };
        }
      }
    } finally {
      clearTimeout(timer);
    }
    if (entry.reason) void this.reap(entry);
    else if (!stats.isError) this.limitBackoffMs = LIMIT_BACKOFF_MS;
    return { stats, ...(entry.reason ? { reason: entry.reason } : {}) };
  }

  onDesignRequest(d: Design): void {
    if (this.authFailed) {
      this.fm.designFailed(d.id, `Claude is not available: ${this.fm.status.message ?? 'auth failed'}`);
      return;
    }
    this.designs.enqueue(d.id);
    this.tick();
  }

  onDesignCancel(id: string): void {
    this.designs.cancel(id);
  }

  // ---- after a turn -------------------------------------------------------------------------

  private failure(stats: TurnStats | undefined): string {
    return truncate(stats?.subtype && stats.subtype !== 'success' ? stats.subtype.replace(/^error_/, '').replace(/_/g, ' ') : (stats?.errors[0] ?? 'error'), 36);
  }

  /** Which automatic-retry budget a job uses (one retry per worker task, lead plan, lead review). */
  private retryKey(job: Job): string {
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
  private autoRetry(job: Job, stats: TurnStats | undefined, what: string): boolean {
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
  private stillDue(job: Job): boolean {
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
  private dropDelayed(pred: (agentId: string, job: Job) => boolean): void {
    for (const [id, d] of [...this.delayed]) {
      if (!pred(id, d.job)) continue;
      clearTimeout(d.timer);
      this.delayed.delete(id);
    }
  }

  private async afterTurn(job: Job, stats: TurnStats | undefined): Promise<void> {
    const failed = !stats || stats.isError;
    if (!failed) this.retried.delete(this.retryKey(job));
    if (this.fm.isLead(job.agentId)) {
      const lead = job.agentId;
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

  /** A worker finished a task: CI in the worktree, then lead review (or a merge decision). */
  private async afterWorkerDone(taskId: string): Promise<void> {
    if (this.reviewing.has(taskId)) return; // CI already running for it
    this.reviewing.add(taskId);
    try {
      await this.ciThenReview(taskId);
    } finally {
      this.reviewing.delete(taskId);
    }
  }

  private async ciThenReview(taskId: string): Promise<void> {
    const t = this.fm.tasks.get(taskId);
    if (!t || t.status !== 'review' || !t.repoId || !t.worktree) return;
    const worker = t.assignee;
    // protected files (repoSettings.protect) must never land: back to the worker, twice at most
    const leaked = await this.fm.repos.protectedChanges(t.repoId, t.worktree).catch(() => [] as string[]);
    if (leaked.length) {
      const n = (this.st.ciFixes[`protect:${t.id}`] ?? 0) + 1;
      this.st.ciFixes[`protect:${t.id}`] = n;
      if (n <= 2 && worker && !this.isStopped(worker)) {
        this.sendBackToWorker(t.id, `Your branch changes files that must never be committed in this repository: ${leaked.join(', ')}. Take those changes off the branch (e.g. \`git checkout ${t.worktree ? this.fm.repos.requireWorktree(t.repoId, t.worktree).base : 'BASE'} -- <file>\` and commit), keep your other work, then update_task("${t.id}", status "review", summary).`);
      } else {
        this.fm.tasks.setStatus(t.id, 'blocked', { reason: `changes protected files: ${leaked.join(', ')}`, force: true });
        this.fm.notify('warn', `${t.id} changes protected files (${leaked.join(', ')}); not offered for landing`);
      }
      return;
    }
    if (worker) this.fm.setAgent(worker, { state: 'idle', station: 'lounge', activity: `${t.id} in review` });
    let ci: TestResult | undefined;
    try {
      this.fm.tasks.update(t.id, { ci: 'running' });
      this.fm.repos.setCi(t.repoId, 'running');
      const ciCommand = this.fm.repos.testCommand(t.repoId, this.fm.repos.requireWorktree(t.repoId, t.worktree).path, this.cfg.ciCommand);
      if (worker) this.fm.agentLog(worker, 'tool', `CI: ${ciCommand ?? '(no tests)'}`);
      ci = await this.fm.repos.runTests(t.repoId, t.worktree, ciCommand);
      this.fm.tasks.update(t.id, { ci: ci.pass ? 'pass' : 'fail' });
      this.fm.repos.setCi(t.repoId, ci.pass ? 'pass' : 'fail');
      if (worker) this.fm.agentLog(worker, ci.pass ? 'result' : 'error', `CI ${ci.pass ? 'passed' : 'FAILED'} (${(ci.durationMs / 1000).toFixed(1)}s)\n${ci.output.split('\n').slice(-6).join('\n')}`);
      this.fm.bus.feed('ci', `${t.id}: tests ${ci.pass ? 'pass' : 'fail'} (${ci.command})`, { ...(worker ? { agentId: worker } : {}), taskId: t.id });
    } catch (e) {
      this.fm.log.warn(`CI for ${t.id}: ${(e as Error).message}`);
    }
    await this.fm.repos.refresh(t.repoId);
    if (ci && !ci.pass && (this.st.ciFixes[t.id] ?? 0) < 1 && worker && !this.isStopped(worker)) {
      this.st.ciFixes[t.id] = (this.st.ciFixes[t.id] ?? 0) + 1;
      this.sendBackToWorker(t.id, `CI failed for ${t.id} (${ci.command}):\n${ci.output}\n\nFix the failures, re-run the tests, then update_task("${t.id}", status "review", summary).`);
      return;
    }
    const lead = this.fm.leadOfTask(t);
    if (this.cfg.leadReview && !this.isStopped(lead)) {
      const fold = this.st.foldIns[t.id];
      const diff = await this.fm.repos.diff(t.repoId, t.worktree, fold?.from ? { from: fold.from } : {}).catch(() => this.fm.repos.diff(t.repoId!, t.worktree!));
      this.fm.setAgent(lead, { state: 'reading', station: 'mergestation', activity: `reviewing ${t.id}` });
      this.enqueue({ kind: 'review', agentId: lead, taskId: t.id, ...(t.goalId ? { goalId: t.goalId } : {}), sessionKey: `${lead}:${t.goalId ?? 'adhoc'}`, prompt: reviewPrompt(this.fm, this.fm.tasks.require(t.id), renderDiffText(diff.files), diff.stats, ci, fold ? { notes: fold.notes } : undefined) });
    } else {
      this.openMergeDecision(t, t.summary ?? 'Work complete.');
    }
  }

  private openMergeDecision(t: Task, summary: string): void {
    const wt = this.fm.repos.requireWorktree(t.repoId!, t.worktree!);
    const userBase = this.fm.repos.isUserBase(t.repoId!, wt.base);
    const pr = this.fm.repos.landsAsPr(t.repoId!) && !userBase;
    const fold = !!t.pr && !!this.st.foldIns[t.id];
    const lead = this.fm.leadOfTask(t);
    this.fm.createDecision({
      agentId: lead,
      kind: 'merge',
      question: fold ? `Push the review fixes for ${t.id} "${t.title}" to PR #${t.pr!.id}?` : userBase ? `Add ${t.id} "${t.title}" (${wt.branch}) to your branch ${wt.base}?` : pr ? `Open a pull request for ${t.id} "${t.title}" (${wt.branch} into ${wt.base.replace(/^[^/]+\//, '')})?` : `Merge ${t.id} "${t.title}" (${wt.branch}) into ${wt.base}?`,
      options: [...MERGE_OPTIONS],
      context: `${summary}\n${wt.files} files, +${wt.additions} -${wt.deletions} | tests: ${t.ci}${fold ? `\n"${MERGE_OPTIONS[0]}" pushes the fixes as an added commit on the pull request (no force-push, no squash of what is there).` : pr ? `\n"${MERGE_OPTIONS[0]}" pushes the branch and opens the pull request (agents never push).` : ''}`,
      taskId: t.id,
      repoId: t.repoId!,
      worktree: wt.id,
    });
    if (!this.isStopped(lead)) this.fm.setAgent(lead, { state: 'idle', station: 'mergestation', activity: `awaiting your review of ${t.id}` });
  }

  /** Resume the worker's task session with feedback (lead/user changes, CI failure). */
  private sendBackToWorker(taskId: string, prompt: string): void {
    const t = this.fm.tasks.get(taskId);
    if (!t?.assignee) return;
    if (this.isStopped(t.assignee)) {
      // nobody to send it back to: put it on the board for the next free worker
      this.fm.tasks.setStatus(t.id, 'todo', { force: true, summary: truncate(prompt, 400) });
      this.fm.tasks.update(t.id, { assignee: null });
      this.tick();
      return;
    }
    if (t.status !== 'doing') this.fm.tasks.setStatus(t.id, 'doing', { force: true });
    this.fm.setAgent(t.assignee, { taskId: t.id, state: 'thinking', station: 'desk', activity: `revising ${t.id}`, ...(t.repoId ? { repoId: t.repoId } : {}), ...(t.worktree ? { worktree: t.worktree } : {}) });
    this.enqueue({ kind: 'followup', agentId: t.assignee, taskId: t.id, ...(t.goalId ? { goalId: t.goalId } : {}), sessionKey: `${t.assignee}:${t.id}`, prompt });
  }

  // ---- pull requests (prwatch.ts) -------------------------------------------------------------

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

  /** The lead triages a PR's new items in the goal's session. */
  private async enqueueTriage(taskId: string, items: TriageItem[]): Promise<void> {
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
  private startFoldIn(taskId: string, notes: string): boolean {
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

  private onPrMerged(t: Task): void {
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

  // ---- user intents -------------------------------------------------------------------------

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
    if (this.running.has(id) || this.pausedJobs.has(id)) return;
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

  // ---- goals (Goals tab) ----------------------------------------------------------------------

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
  private enqueueGoalMessage(leadId: string, goalId: string): void {
    if (this.hasQueued(leadId, (j) => !!j.pendingGoalMessage && j.goalId === goalId)) return;
    this.enqueue({ kind: 'followup', agentId: leadId, goalId, sessionKey: `${leadId}:${goalId}`, prompt: '', pendingGoalMessage: true });
  }

  /** Unread goal messages to a lead (after a restart, a resume, an assignment): queue their turns. */
  private queueGoalMessages(leadId: string): void {
    if (this.stopping || this.isStopped(leadId) || !this.fm.isLead(leadId)) return;
    for (const goalId of new Set(this.fm.bus.goalInbox(leadId).map((m) => m.goalId!))) this.enqueueGoalMessage(leadId, goalId);
  }

  /** Build a goal-message job's prompt from the goal's unread messages (marked read). False: none left. */
  private fillGoalMessage(job: Job): boolean {
    const goal = job.goalId ? this.fm.goal(job.goalId) : undefined;
    const msgs = goal ? this.fm.bus.goalInbox(job.agentId, goal.id) : [];
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
    return true;
  }

  /** A goal-message turn ended: when the lead sent the user nothing, its final text is the reply. */
  private replyToGoal(lead: string, job: Job, stats: TurnStats | undefined): void {
    const since = job.startedAt ?? 0;
    const replied = this.fm.store.data.messages.some((m) => m.from === lead && m.to === 'user' && m.ts >= since);
    const text = stats?.resultText?.trim();
    if (!replied && text) this.fm.bus.send(lead, 'user', truncate(text, 1500), { goalId: job.goalId });
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

  onMergeConflict(task: Task, info: { base: string; branch: string; files: string[]; reason: string }): boolean {
    if (!task.assignee || this.isStopped(task.assignee)) return false; // the user decides (decision stays open)
    const files = info.files.length ? info.files.join(', ') : '(see git status)';
    this.sendBackToWorker(
      task.id,
      `${userName()} approved merging ${task.id}, but ${info.branch} now conflicts with ${info.base} (other work was merged into ${info.base} after you started) in: ${files}.\n` +
        `In your worktree run \`git merge ${info.base}\`, resolve every conflict so that both sides' changes are kept, run the tests, and commit the merge (git commit --no-edit). ` +
        `Do not rebase, reset or check out other branches. Then update_task("${task.id}", status "review", summary).`,
    );
    return true;
  }

  onTaskAction(task: Task, action: 'reassign' | 'cancel' | 'retry' | 'prioritize'): void {
    if (action === 'cancel' || action === 'reassign') {
      for (const [id, r] of this.running) {
        if (r.job.taskId === task.id && !this.fm.isLead(id) && (action === 'cancel' || task.assignee !== id)) this.abortTurn(r, 'cancel');
      }
      for (const [id, q] of this.queues) this.queues.set(id, q.filter((j) => j.taskId !== task.id || (action === 'reassign' && task.assignee === id)));
      this.dropDelayed((id, j) => j.taskId === task.id && (action === 'cancel' || task.assignee !== id || this.fm.isLead(id)));
      // reassigned: the new worker continues from the old worker's branch once that turn is over
      if (action === 'reassign' && task.repoId && task.worktree) {
        const wt = this.fm.repos.findWorktree(task.repoId, task.worktree);
        if (wt && wt.status === 'active' && wt.agentId !== task.assignee) this.handOff(task.id, wt.agentId, `reassigned to ${this.fm.nameOf(task.assignee ?? 'user')}`);
      }
    }
    this.tick();
  }

  /** Withdraw an agent's open questions and permission prompts (not merge decisions: those are the user's). */
  private withdrawDecisions(agentId: string, why: string): void {
    for (const d of this.fm.decisions.open().filter((x) => x.agentId === agentId && x.kind !== 'merge' && !this.prs.owns(x.id))) this.fm.decisions.cancel(d.id, why);
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

  // ---- leads (a lead per building) ------------------------------------------------------------

  onLeadAssigned(leadId: string): void {
    // a newly assigned lead starts on shift (a stop from an earlier assignment does not carry over)
    if (this.isStopped(leadId)) this.setStopped(leadId, false);
    this.deliverPending(leadId);
    this.queueGoalMessages(leadId);
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
