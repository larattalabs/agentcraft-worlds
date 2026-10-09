// An engine runs one agent turn: the Claude Agent SDK (claude/engine.ts) or the Codex app-server
// (codex/engine.ts). Everything around a turn - task graph, worktrees, scheduling, CI, reviews,
// merges, steering, restarts - is the team's (team.ts and team/) and the same for every engine, so
// a team can mix them (e.g. a Claude lead with Codex workers).
//
// Fork additions (all optional, so upstream's engines fit unchanged): the team picks the turn's
// model and effort (profiles, task sizes, repository roles), passes the policy context its
// permission gate uses (the Claude engine's PreToolUse guards check the same rules), and gets usage
// reports back (holds); an auth probe can report a passing failure (offline: retried); thrown
// errors are classified by the engine (auth, usage limit).
import type { ChildProcess } from 'node:child_process';
import type { PolicyContext } from '../policy.js';
import type { AgentTool, TurnHandle } from './tools.js';

export type EngineId = 'claude' | 'codex';
export const ENGINE_IDS: readonly EngineId[] = ['claude', 'codex'];
export type Role = 'lead' | 'worker';

/** A plan usage report from a running turn (Claude: the CLI's rate_limit_event). */
export interface RateLimitReport {
  status: 'allowed' | 'allowed_warning' | 'rejected';
  /** epoch ms */
  resetsAt?: number;
  type?: string;
  /** 0-1 */
  utilization?: number;
}

export interface TurnStats {
  /** the engine's session (Claude session id, Codex thread id), to resume the next turn */
  sessionId?: string;
  resultText?: string;
  subtype?: string;
  isError: boolean;
  costUsd?: number;
  numTurns?: number;
  /** total tokens of the turn (engines that report usage, not cost) */
  tokens?: number;
  authFailed?: string;
  errors: string[];
  /** a usage/rate limit refused this turn: the job waits for the reset instead of failing */
  limited?: boolean;
  /** the latest rate limit report seen in the turn */
  rateLimit?: RateLimitReport;
}

/** The user's (or the policy's) verdict on a tool call. */
export type PermissionAnswer = { allow: true } | { allow: false; message: string; interrupt?: boolean };

/**
 * Ask whether an agent may use a tool: the policy decides, or the user is asked (a permission
 * decision). `toolName`/`input` use the Claude tool vocabulary (Bash {command}, Edit {file_path}),
 * which the policy understands; other engines map their actions onto it.
 */
export type PermissionGate = (toolName: string, input: Record<string, unknown>, signal: AbortSignal, title?: string) => Promise<PermissionAnswer>;

/** A building design turn (Claude only): no avatar to ask through, its own MCP servers and caps. */
export interface AuxTurnOptions {
  /** the design kit's MCP servers, the only ones the turn gets */
  mcpServers: Record<string, unknown>;
  maxTurns: number;
  /** every SDK message, for the design job's own bookkeeping */
  onMessage?(msg: unknown): void;
}

export interface TurnSpec {
  agentId: string;
  role: Role;
  /** lead: a read-only view of the base (or the user's checkout); worker: its worktree */
  cwd: string;
  prompt: string;
  /** role, rules and worktree, added to the engine's own system prompt */
  instructions: string;
  /** the engine session to continue (from TurnStats.sessionId), if any */
  resume?: string;
  /** environment for the agent's process and every command it runs (git safety, identity, repo env) */
  env: Record<string, string | undefined>;
  /** directories the agent may write besides cwd (a worktree's git dir, for commits) */
  writableRoots?: string[];
  abort: AbortController;
  turn: TurnHandle;
  permission: PermissionGate;
  tools: AgentTool[];
  /** the agent process the engine spawned, so the team can end its whole tree on abort */
  onProcess(child: ChildProcess): void;
  /** the session id, as soon as the engine knows it (persisted for resume) */
  onSession(sessionId: string): void;
  /** the model the turn really runs (e.g. "claude-opus-5-5"), as soon as the engine knows it */
  onModel?(model: string): void;
  /** fork: the model / effort the team picked (profile, task size, repository role); else the engine's role default */
  model?: string;
  effort?: string;
  /** fork: the repository the turn works in (its agent files, its subagent setting) */
  repoId?: string;
  /** fork: what the policy knows about this agent right now (the permission gate's context; the Claude engine's guards use it) */
  policy?(): PolicyContext;
  /** fork: a usage report while the turn runs (holds, throttling) */
  onRateLimit?(r: RateLimitReport): void;
  /** fork: the engine's live session, to read the plan's usage windows from (Claude) */
  onUsageSource?(source: object): void;
  /** fork: a building design turn (Claude only) */
  aux?: AuxTurnOptions;
}

/** `transient`: the check could not reach the service (network, sleep): retried, not a bad login. */
export type AuthCheck = { ok: true; account: string } | { ok: false; message: string; transient?: boolean };

export interface Engine {
  readonly id: EngineId;
  /** display name, e.g. "Claude" */
  readonly label: string;
  model(role: Role): string;
  checkAuth(): Promise<AuthCheck>;
  runTurn(spec: TurnSpec): Promise<TurnStats>;
  /** what to tell the user when a turn failed authentication */
  authFailedMessage(detail: string): string;
  /** fork: what an error a turn threw means (bad login, usage limit); default: an auth word in it */
  classifyError?(message: string): { auth: boolean; limited?: { resetsAt?: number } };
  /** fork: set up once at the team's start (Claude: the skills plugin, subagent definitions) */
  prepare?(): void;
  /** fork: what this engine adds to the policy context (Claude: enabled skills, the plugin folder, subagents) */
  policyExtras?(repoId: string | undefined): Partial<PolicyContext>;
}
