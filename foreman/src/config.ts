// Configuration: defaults < <AGENTCRAFT_HOME>/config.json < environment < CLI flags.
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readJson } from './util/fsx.js';
import type { BackendName } from './protocol.js';
import { defaultUserName } from './user.js';
import type { EffortLevel, McpServerConfig } from '@anthropic-ai/claude-agent-sdk';
import { DEFAULT_CONTEXT, type AgentContextConfig } from './agents/claude/context.js';
import { DEFAULT_PERMISSIONS, type PermissionsConfig } from './agents/claude/permissions.js';
import { DEFAULT_SUBAGENTS, type SubagentsConfig } from './agents/claude/subagents.js';

export const FOREMAN_VERSION = '0.1.0';

/** Repo root of the AgentCraft project (foreman/src/config.ts -> ../..). */
export const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

/** One agent's profile (config.json claude.agents.<id>). */
export interface AgentProfile {
  /** role title shown on the agent's nameplate, e.g. "Frontend" */
  title?: string;
  /** what this agent specialises in; goes into its own prompt and the lead's team list */
  prompt?: string;
  model?: string;
  effort?: EffortLevel;
}

export type TaskSize = 'small' | 'normal' | 'large';

export interface ClaudeConfig {
  leadModel: string;
  workerModel: string;
  /** model of the building design agent (design.request); default: the worker model */
  designModel: string;
  effort: EffortLevel;
  leadEffort: EffortLevel;
  maxTurnsLead: number;
  maxTurnsWorker: number;
  /** max workers running a turn at the same time */
  maxConcurrent: number;
  /** max workers while the plan reports a usage warning (claude.ai login), until that window resets */
  throttleConcurrent: number;
  /** worker ids in the team (subset of the cast) */
  workers: string[];
  /**
   * lead ids in use, always starting with "marlow" (home, repositories without a building, anything
   * not tied to a repository); the others lead one building each, assigned in this order
   * (lead.assign). Default ["marlow","ines","bram","cass"]; [] or one entry = marlow alone.
   */
  leads: string[];
  /** cap on agent turns running at once, leads and workers together (unset: no cap besides maxConcurrent) */
  maxConcurrentTurns?: number;
  /** test command for CI after a worker finishes (default: detect, e.g. `npm test`) */
  ciCommand?: string;
  /** per-turn budget cap passed to the SDK */
  maxBudgetUsdPerTurn?: number;
  /** resume interrupted sessions on Foreman start */
  resumeOnStart: boolean;
  /** lead reviews each finished task before the merge decision reaches the user */
  leadReview: boolean;
  /**
   * Use the local `claude` CLI's claude.ai login instead of an API key / cloud provider. Personal use
   * only: Anthropic does not allow third-party tools to offer claude.ai login (see agents/claude/auth.ts).
   */
  useClaudeLogin: boolean;
  /** Claude Code context for the agents: instruction files, skills, MCP servers (config.json claude.context) */
  context: AgentContextConfig;
  /** per-agent profiles (role title, specialty prompt, model, effort) */
  agents: Record<string, AgentProfile>;
  /** model per task size the lead sets on create_task (wins over the agent's model) */
  taskModels: Partial<Record<TaskSize, string>>;
  /** permission mode, guardrails and Claude Code permission rules (config.json claude.permissions) */
  permissions: PermissionsConfig;
  /** Claude Code subagents for the agents (config.json claude.subagents) */
  subagents: SubagentsConfig;
  /**
   * pull requests of tasks landed as PRs (repoSettings land "pr"): "off" = landing finishes the task;
   * "observe" (default) = watch and triage, post nothing, start no fold-in; "on" = everything
   */
  prWatch: 'off' | 'observe' | 'on';
  /** how often watched PRs are polled (seconds, default 180) */
  prPollSeconds: number;
}

/**
 * Per-repo settings, from config.json `repoSettings` keyed by the repository's path:
 *
 *   "repoSettings": { "~/code/app": { "ci": "pnpm -r test", "setup": "pnpm install --frozen-lockfile", "copy": [".env"] } }
 *
 * They live in the user's config, not in the repository, so an agent cannot change what the
 * Foreman runs by editing a file in its worktree.
 */
export interface RepoSettings {
  /** test command run after each task (overrides --ci and detection) */
  ci?: string;
  /** run once in each new worker worktree before the worker starts (e.g. install dependencies) */
  setup?: string;
  /** untracked files or directories copied from the main checkout into each new worktree (e.g. .env) */
  copy?: string[];
  /** setup timeout in ms (default 10 minutes) */
  setupTimeoutMs?: number;
  /**
   * agent id -> one of the repository's agent files (a name under .claude/agents, or a path): the
   * agent's role, prompt and model whenever it works in this repository
   */
  roles?: Record<string, string>;
  /** "repo": agents working here may use the repository's .claude/agents files as subagents */
  subagents?: 'repo';
  /** branch agents start from and land into (default: whatever the checkout has checked out) */
  baseBranch?: string;
  /** paths (relative to the repo; a trailing / means a folder) that are never committed */
  protect?: string[];
  /** environment for agents, setup and CI in this repo ($VAR / ${VAR} and ~ expanded; GIT_* ignored) */
  env?: Record<string, string>;
  /**
   * how approved work lands: "merge" (default) = a local merge commit into the base branch;
   * "pr" = the Foreman pushes the branch and opens a pull request into the base branch
   */
  land?: 'merge' | 'pr';
  /** pull request options (land "pr") */
  pr?: PrSettings;
  /** how review comments on the PRs are triaged (PR watching) */
  prReview?: PrReviewSettings;
}

export interface PrReviewSettings {
  /** automated-review severities folded in by default (default ["critical", "important"]) */
  autoSeverities?: Array<'critical' | 'important' | 'minor' | 'testing' | 'performance' | 'teachable'>;
  /** fold-in rounds driven by automated reviews per PR before the user decides (default 2) */
  maxRounds?: number;
}

export interface PrSettings {
  /** remote to fetch the base from and push to (default "origin") */
  remote?: string;
  /** remote branch name prefix, e.g. "feat/" -> feat/t3-tune-the-flight-model (default: the agent's branch name) */
  branchPrefix?: string;
  /** open PRs as drafts */
  draft?: boolean;
  /** push one commit authored by the user (agents' commits squashed, with Co-authored-by) instead of the agents' commits */
  squash?: boolean;
}

export type ShowcaseCheckpoint = 'showcase' | 'showcase-late';

export interface SimConfig {
  speed: number;
  seed: number;
  showcase: boolean;
  /** which static state --showcase holds: the busy mid-run state (default) or `--showcase late` */
  showcaseAt: ShowcaseCheckpoint;
  /** sim answers its own decisions (first option) after a short delay — for unattended runs/tests */
  autoAnswer: boolean;
  /** extra idle log lines while waiting on the user */
  ambient: boolean;
}

export interface Config {
  backend: BackendName;
  /** the person the team works for (prompts, feed, UI); default: the OS user name */
  userName: string;
  home: string;
  /** <home>/config.json */
  configFile: string;
  /** the command-line arguments this configuration came from (foreman.restart starts with them again) */
  argv: string[];
  /** the flags given and the AGENTCRAFT_* variables set (names only): they win over config.json */
  overrides: { flags: string[]; env: string[] };
  profile: string;
  /** profile directory: <home>/<profile> */
  dataDir: string;
  host: string;
  port: number;
  repos: string[];
  /** absolute repo path -> settings */
  repoSettings: Record<string, RepoSettings>;
  goal?: string;
  autostart: boolean;
  reset: boolean;
  notify: boolean;
  toastSilent: boolean;
  debug: boolean;
  quiet: boolean;
  projectRoot: string;
  /** reject WebSocket upgrades that carry a browser Origin (CSRF-style protection) */
  allowBrowserOrigins: boolean;
  /** require the client token (clienttoken.ts) for anything but read-only use; --no-client-token (dev) turns it off */
  clientToken: boolean;
  /** how often the main checkouts are polled for head/dirty changes (ms) */
  repoPollMs: number;
  /** approved merges: a merge commit (keeps the agents' commits) or one squashed commit */
  mergeStyle: 'merge' | 'squash';
  /** sign approved merge commits when the repo's own git config says commit.gpgsign=true */
  signMerges: boolean;
  claude: ClaudeConfig;
  sim: SimConfig;
}

type Flags = Record<string, string | boolean>;

export function parseFlags(argv: string[]): { flags: Flags; positional: string[] } {
  const flags: Flags = {};
  const positional: string[] = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    if (a === '--') {
      positional.push(...argv.slice(i + 1));
      break;
    }
    if (!a.startsWith('--')) {
      positional.push(a);
      continue;
    }
    const eq = a.indexOf('=');
    if (eq > 0) {
      flags[a.slice(2, eq)] = a.slice(eq + 1);
      continue;
    }
    const key = a.slice(2);
    if (key.startsWith('no-')) {
      flags[key.slice(3)] = false;
      continue;
    }
    const next = argv[i + 1];
    if (next !== undefined && !next.startsWith('--')) {
      flags[key] = next;
      i++;
    } else flags[key] = true;
  }
  return { flags, positional };
}

function num(v: unknown, d: number): number {
  if (v === undefined || v === '' || v === true) return d;
  const n = Number(v);
  return Number.isFinite(n) ? n : d;
}

function bool(v: unknown, d: boolean): boolean {
  if (v === undefined) return d;
  if (typeof v === 'boolean') return v;
  return !/^(0|false|no|off)$/i.test(String(v));
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.length ? v : undefined;
}

function strings(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.trim().length > 0) : [];
}

/** config.json claude.context; unknown keys are ignored, wrong types fall back to the defaults. */
function contextConfig(v: unknown): AgentContextConfig {
  const o = (v && typeof v === 'object' ? v : {}) as Record<string, unknown>;
  const servers: Record<string, McpServerConfig> = {};
  if (o.mcpServers && typeof o.mcpServers === 'object') {
    for (const [name, def] of Object.entries(o.mcpServers as Record<string, unknown>)) {
      // "agentcraft" is the team tools server; nothing may replace it
      if (name !== 'agentcraft' && /^[\w-]+$/.test(name) && def && typeof def === 'object') servers[name] = def as McpServerConfig;
    }
  }
  return {
    repoInstructions: typeof o.repoInstructions === 'boolean' ? o.repoInstructions : DEFAULT_CONTEXT.repoInstructions,
    userInstructions: typeof o.userInstructions === 'boolean' ? o.userInstructions : DEFAULT_CONTEXT.userInstructions,
    workspaceInstructions: typeof o.workspaceInstructions === 'boolean' ? o.workspaceInstructions : DEFAULT_CONTEXT.workspaceInstructions,
    files: strings(o.files),
    maxChars: typeof o.maxChars === 'number' && o.maxChars > 0 ? o.maxChars : DEFAULT_CONTEXT.maxChars,
    skills: strings(o.skills),
    mcpServers: servers,
    mcpAllow: strings(o.mcpAllow).filter((p) => p.startsWith('mcp__') && !p.startsWith('mcp__agentcraft__')),
    connectors: strings(o.connectors),
    sessionHistory: ((h: unknown) => {
      if (typeof h === 'boolean') return { enabled: h, days: DEFAULT_CONTEXT.sessionHistory.days };
      const x = (h && typeof h === 'object' ? h : {}) as Record<string, unknown>;
      return {
        enabled: typeof x.enabled === 'boolean' ? x.enabled : DEFAULT_CONTEXT.sessionHistory.enabled,
        days: typeof x.days === 'number' && x.days > 0 ? x.days : DEFAULT_CONTEXT.sessionHistory.days,
      };
    })(o.sessionHistory),
  };
}

function agentProfiles(v: unknown): Record<string, AgentProfile> {
  const out: Record<string, AgentProfile> = {};
  if (!v || typeof v !== 'object') return out;
  for (const [id, raw] of Object.entries(v as Record<string, unknown>)) {
    if (!/^[a-z0-9_-]+$/i.test(id) || !raw || typeof raw !== 'object') continue;
    const o = raw as Record<string, unknown>;
    const p: AgentProfile = {};
    if (str(o.title)) p.title = (o.title as string).trim().slice(0, 40);
    if (str(o.prompt)) p.prompt = o.prompt as string;
    if (str(o.model)) p.model = o.model as string;
    if (o.effort !== undefined) p.effort = effort(o.effort, 'medium');
    out[id.toLowerCase()] = p;
  }
  return out;
}

function taskModels(v: unknown): Partial<Record<TaskSize, string>> {
  const out: Partial<Record<TaskSize, string>> = {};
  if (!v || typeof v !== 'object') return out;
  for (const k of ['small', 'normal', 'large'] as const) {
    const m = (v as Record<string, unknown>)[k];
    if (str(m)) out[k] = m as string;
  }
  return out;
}

function permissionsConfig(v: unknown): PermissionsConfig {
  const o = (v && typeof v === 'object' ? v : {}) as Record<string, unknown>;
  const mode = o.mode ?? DEFAULT_PERMISSIONS.mode;
  if (mode !== 'policy' && mode !== 'auto') throw new Error(`unknown permissions mode "${String(mode)}" (use policy or auto)`);
  return {
    mode,
    allow: strings(o.allow),
    deny: strings(o.deny),
    ask: strings(o.ask),
    webTools: typeof o.webTools === 'boolean' ? o.webTools : DEFAULT_PERMISSIONS.webTools,
    protectCheckouts: typeof o.protectCheckouts === 'boolean' ? o.protectCheckouts : DEFAULT_PERMISSIONS.protectCheckouts,
  };
}

function subagentsConfig(v: unknown): SubagentsConfig {
  const o = (v && typeof v === 'object' ? v : {}) as Record<string, unknown>;
  return { enabled: typeof o.enabled === 'boolean' ? o.enabled : DEFAULT_SUBAGENTS.enabled, agents: strings(o.agents) };
}

export const DEFAULT_LEADS = ['marlow', 'ines', 'bram', 'cass'];

/**
 * claude.leads / --leads / AGENTCRAFT_LEADS: an id list (array or comma list). Normalized to start
 * with "marlow"; [] or a single entry means marlow alone (today's single lead).
 */
export function leadsList(v: unknown): string[] {
  if (v === undefined) return [...DEFAULT_LEADS];
  const raw = Array.isArray(v) ? v : typeof v === 'string' ? v.split(',') : [];
  const ids = [...new Set(raw.filter((x): x is string => typeof x === 'string').map((x) => x.trim().toLowerCase()).filter(Boolean))];
  for (const id of ids) if (!/^[a-z0-9_-]+$/.test(id)) throw new Error(`bad lead id "${id}" (lowercase letters, digits, - and _)`);
  if (ids.length <= 1) return ['marlow'];
  return ['marlow', ...ids.filter((x) => x !== 'marlow')];
}

function mergeStyle(v: unknown): 'merge' | 'squash' {
  if (v === undefined || v === 'merge') return 'merge';
  if (v === 'squash') return 'squash';
  throw new Error(`unknown merge style "${String(v)}" (use merge or squash)`);
}

function prWatchMode(v: unknown): 'off' | 'observe' | 'on' {
  if (v === undefined || v === true || v === '') return 'observe';
  if (v === false) return 'off';
  if (v === 'off' || v === 'observe' || v === 'on') return v;
  throw new Error(`unknown PR watch mode "${String(v)}" (use off, observe or on)`);
}

const EFFORTS: EffortLevel[] = ['low', 'medium', 'high', 'xhigh', 'max'];
function effort(v: unknown, d: EffortLevel): EffortLevel {
  if (v === undefined) return d;
  if (typeof v === 'string' && (EFFORTS as string[]).includes(v)) return v as EffortLevel;
  throw new Error(`unknown effort "${String(v)}" (use ${EFFORTS.join(', ')})`);
}

/** Every flag loadConfig reads (the `no-` prefix is stripped by parseFlags). */
export const KNOWN_FLAGS = new Set([
  'home', 'backend', 'profile', 'user-name', 'use-claude-login', 'repo', 'workers', 'model', 'port', 'goal', 'autostart', 'reset', 'notify',
  'toast-silent', 'debug', 'quiet', 'allow-browser-origins', 'repo-poll-ms', 'merge-style', 'sign-merges',
  'lead-model', 'worker-model', 'design-model', 'effort', 'lead-effort', 'max-turns', 'max-turns-lead', 'max-turns-worker',
  'max-concurrent', 'throttle-concurrent', 'ci', 'max-budget', 'resume', 'lead-review', 'speed', 'seed', 'showcase', 'auto-answer',
  'ambient', 'pr-watch', 'pr-poll-seconds', 'leads', 'max-concurrent-turns', 'client-token',
]);

/**
 * Unknown flags and stray positionals are errors, not silently ignored: a mistyped or mangled flag
 * (e.g. PowerShell passing `--workers,kit,--model,sonnet` as ONE argument) would otherwise start a
 * real claude team on the expensive defaults (opus lead, medium effort, three workers).
 */
function checkArgs(flags: Flags, positional: string[]): void {
  const unknown = Object.keys(flags).filter((k) => !KNOWN_FLAGS.has(k));
  if (unknown.length) {
    throw new Error(`unknown option${unknown.length > 1 ? 's' : ''} ${unknown.map((k) => `"--${k}"`).join(', ')} (see --help)`);
  }
  if (positional.length) throw new Error(`unexpected argument "${positional[0]}" (options start with --; see --help)`);
}

/** Every environment variable loadConfig reads (only their presence is recorded: Config.overrides). */
export const CONFIG_ENV_VARS = [
  'AGENTCRAFT_HOME', 'AGENTCRAFT_BACKEND', 'AGENTCRAFT_PROFILE', 'AGENTCRAFT_WORKERS', 'AGENTCRAFT_USER_NAME', 'AGENTCRAFT_PORT',
  'AGENTCRAFT_NOTIFY', 'AGENTCRAFT_TOAST_SILENT', 'AGENTCRAFT_DEBUG', 'AGENTCRAFT_MERGE_STYLE', 'AGENTCRAFT_SIGN_MERGES',
  'AGENTCRAFT_LEAD_MODEL', 'AGENTCRAFT_WORKER_MODEL', 'AGENTCRAFT_DESIGN_MODEL', 'AGENTCRAFT_LEADS', 'AGENTCRAFT_USE_CLAUDE_LOGIN',
  'AGENTCRAFT_PR_WATCH', 'AGENTCRAFT_SIM_SPEED',
];

/** <home>/config.json, home from --home / AGENTCRAFT_HOME / ~/.agentcraft */
export function configFilePath(argv: string[], env: NodeJS.ProcessEnv = process.env): string {
  const { flags } = parseFlags(argv);
  return path.join(path.resolve(str(flags.home) ?? env.AGENTCRAFT_HOME ?? path.join(os.homedir(), '.agentcraft')), 'config.json');
}

export function loadConfig(argv: string[], env: NodeJS.ProcessEnv = process.env): Config {
  return configFrom(argv, env);
}

/**
 * The configuration from defaults < `file` (default: <home>/config.json as it is on disk) <
 * environment < flags. config.set validates a candidate file with it before writing.
 */
export function configFrom(argv: string[], env: NodeJS.ProcessEnv, fileOverride?: Record<string, unknown>): Config {
  const { flags, positional } = parseFlags(argv);
  checkArgs(flags, positional);
  const home = path.resolve(str(flags.home) ?? env.AGENTCRAFT_HOME ?? path.join(os.homedir(), '.agentcraft'));
  const configFile = path.join(home, 'config.json');
  const file = fileOverride ?? readJson<Record<string, unknown>>(configFile) ?? {};
  const fileClaude = (file.claude ?? {}) as Record<string, unknown>;
  const fileSim = (file.sim ?? {}) as Record<string, unknown>;
  // file keys: camelCase (what the Settings tab writes), or the flag's spelling
  const pick = (k: string, envKey?: string, fileKey?: string): unknown => flags[k] ?? (envKey ? env[envKey] : undefined) ?? (fileKey ? file[fileKey] : undefined) ?? file[k];

  const backendRaw = String(pick('backend', 'AGENTCRAFT_BACKEND') ?? 'claude');
  if (backendRaw !== 'sim' && backendRaw !== 'claude') throw new Error(`unknown backend "${backendRaw}" (use sim or claude)`);
  const backend = backendRaw as BackendName;
  const profile = str(pick('profile', 'AGENTCRAFT_PROFILE')) ?? backend;
  if (!/^[a-zA-Z0-9_-]+$/.test(profile)) throw new Error(`bad profile name "${profile}"`);

  const repoFlag = flags.repo;
  const repos: string[] = [];
  if (typeof repoFlag === 'string') repos.push(...repoFlag.split(',').map((s) => s.trim()).filter(Boolean));
  // config.json repos are the user's real repositories: the scripted sim backend never registers
  // them (it works on its sandbox demo repo unless a --repo is given explicitly)
  else if (Array.isArray(file.repos) && backend !== 'sim') repos.push(...(file.repos as string[]));

  const workersRaw = str(flags.workers) ?? env.AGENTCRAFT_WORKERS ?? (fileClaude.workers as string[] | string | undefined);
  const workers = Array.isArray(workersRaw)
    ? workersRaw
    : typeof workersRaw === 'string'
      ? /^\d+$/.test(workersRaw)
        ? ['juniper', 'kit', 'wren', 'rowan', 'tove'].slice(0, Math.max(1, Math.min(5, Number(workersRaw))))
        : workersRaw.split(',').map((s) => s.trim()).filter(Boolean)
      : ['juniper', 'kit', 'wren'];

  const repoSettings: Record<string, RepoSettings> = {};
  if (file.repoSettings && typeof file.repoSettings === 'object') {
    for (const [k, v] of Object.entries(file.repoSettings as Record<string, unknown>)) {
      if (!v || typeof v !== 'object') continue;
      const o = v as Record<string, unknown>;
      const s: RepoSettings = {};
      if (str(o.ci)) s.ci = o.ci as string;
      if (str(o.setup)) s.setup = o.setup as string;
      if (Array.isArray(o.copy)) s.copy = o.copy.filter((x): x is string => typeof x === 'string' && x.length > 0);
      if (typeof o.setupTimeoutMs === 'number' && o.setupTimeoutMs > 0) s.setupTimeoutMs = o.setupTimeoutMs;
      if (o.roles && typeof o.roles === 'object') {
        const roles: Record<string, string> = {};
        for (const [id, spec] of Object.entries(o.roles as Record<string, unknown>)) if (/^[a-z0-9_-]+$/i.test(id) && str(spec)) roles[id.toLowerCase()] = spec as string;
        if (Object.keys(roles).length) s.roles = roles;
      }
      if (o.subagents === 'repo') s.subagents = 'repo';
      if (str(o.baseBranch) && /^[\w./-]+$/.test(o.baseBranch as string)) s.baseBranch = o.baseBranch as string;
      if (Array.isArray(o.protect)) s.protect = o.protect.filter((x): x is string => typeof x === 'string' && x.length > 0 && !x.startsWith('/') && !x.split(/[\\/]/).includes('..'));
      if (o.land === 'pr' || o.land === 'merge') s.land = o.land;
      if (o.pr && typeof o.pr === 'object') {
        const q = o.pr as Record<string, unknown>;
        const pr: PrSettings = {};
        if (str(q.remote) && /^[\w.-]+$/.test(q.remote as string)) pr.remote = q.remote as string;
        if (str(q.branchPrefix) && /^[\w./-]+$/.test(q.branchPrefix as string)) pr.branchPrefix = q.branchPrefix as string;
        if (typeof q.draft === 'boolean') pr.draft = q.draft;
        if (typeof q.squash === 'boolean') pr.squash = q.squash;
        s.pr = pr;
      }
      if (o.prReview && typeof o.prReview === 'object') {
        const q = o.prReview as Record<string, unknown>;
        const pv: PrReviewSettings = {};
        const sev = ['critical', 'important', 'minor', 'testing', 'performance', 'teachable'];
        if (Array.isArray(q.autoSeverities)) pv.autoSeverities = q.autoSeverities.filter((x): x is NonNullable<PrReviewSettings['autoSeverities']>[number] => typeof x === 'string' && sev.includes(x));
        if (typeof q.maxRounds === 'number' && q.maxRounds >= 0) pv.maxRounds = Math.floor(q.maxRounds);
        s.prReview = pv;
      }
      if (o.env && typeof o.env === 'object') {
        const env: Record<string, string> = {};
        for (const [k, v] of Object.entries(o.env as Record<string, unknown>)) if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(k) && !/^GIT_/i.test(k) && typeof v === 'string') env[k] = v;
        if (Object.keys(env).length) s.env = env;
      }
      repoSettings[path.resolve(k.replace(/^~(?=$|[\\/])/, os.homedir()))] = s;
    }
  }

  const model = str(flags.model);
  const cfg: Config = {
    backend,
    userName: (str(pick('user-name', 'AGENTCRAFT_USER_NAME', 'userName')))?.trim().slice(0, 40) || defaultUserName(),
    home,
    configFile,
    argv: [...argv],
    overrides: { flags: Object.keys(flags), env: CONFIG_ENV_VARS.filter((k) => env[k] !== undefined && env[k] !== '') },
    profile,
    dataDir: path.join(home, profile),
    host: '127.0.0.1',
    port: num(pick('port', 'AGENTCRAFT_PORT'), 7878),
    repos,
    repoSettings,
    goal: str(flags.goal),
    autostart: bool(flags.autostart, false) || !!str(flags.goal),
    reset: bool(flags.reset, false),
    notify: bool(pick('notify', 'AGENTCRAFT_NOTIFY'), backend === 'claude'),
    toastSilent: bool(pick('toast-silent', 'AGENTCRAFT_TOAST_SILENT', 'toastSilent'), false),
    debug: bool(pick('debug', 'AGENTCRAFT_DEBUG'), false),
    quiet: bool(flags.quiet, false),
    projectRoot: PROJECT_ROOT,
    allowBrowserOrigins: bool(pick('allow-browser-origins'), false),
    clientToken: bool(flags['client-token'], true),
    repoPollMs: Math.max(500, num(pick('repo-poll-ms'), 10_000)),
    mergeStyle: mergeStyle(pick('merge-style', 'AGENTCRAFT_MERGE_STYLE', 'mergeStyle')),
    // the sim answers merges unattended (screenshot QA, --auto-answer): never sign there
    signMerges: bool(pick('sign-merges', 'AGENTCRAFT_SIGN_MERGES', 'signMerges'), backend === 'claude'),
    claude: {
      leadModel: str(flags['lead-model']) ?? model ?? str(env.AGENTCRAFT_LEAD_MODEL) ?? str(fileClaude.leadModel) ?? 'opus',
      workerModel: str(flags['worker-model']) ?? model ?? str(env.AGENTCRAFT_WORKER_MODEL) ?? str(fileClaude.workerModel) ?? 'sonnet',
      designModel: '',
      effort: effort(flags.effort ?? fileClaude.effort, 'medium'),
      leadEffort: effort(flags['lead-effort'] ?? flags.effort ?? fileClaude.leadEffort, 'medium'),
      maxTurnsLead: num(flags['max-turns-lead'] ?? flags['max-turns'] ?? fileClaude.maxTurnsLead, 40),
      maxTurnsWorker: num(flags['max-turns-worker'] ?? flags['max-turns'] ?? fileClaude.maxTurnsWorker, 80),
      maxConcurrent: Math.max(1, num(flags['max-concurrent'] ?? fileClaude.maxConcurrent, 3)),
      throttleConcurrent: Math.max(1, num(flags['throttle-concurrent'] ?? fileClaude.throttleConcurrent, 1)),
      workers,
      leads: leadsList(str(flags.leads) ?? str(env.AGENTCRAFT_LEADS) ?? fileClaude.leads),
      ...((t) => (t > 0 ? { maxConcurrentTurns: Math.floor(t) } : {}))(num(flags['max-concurrent-turns'] ?? fileClaude.maxConcurrentTurns, 0)),
      ciCommand: str(flags.ci) ?? str(fileClaude.ciCommand),
      maxBudgetUsdPerTurn: flags['max-budget'] !== undefined ? num(flags['max-budget'], 0) || undefined : (fileClaude.maxBudgetUsdPerTurn as number | undefined),
      resumeOnStart: bool(flags.resume ?? fileClaude.resumeOnStart, true),
      leadReview: bool(flags['lead-review'] ?? fileClaude.leadReview, true),
      useClaudeLogin: bool(flags['use-claude-login'] ?? env.AGENTCRAFT_USE_CLAUDE_LOGIN ?? fileClaude.useClaudeLogin, false),
      context: contextConfig(fileClaude.context),
      agents: agentProfiles(fileClaude.agents),
      taskModels: taskModels(fileClaude.taskModels),
      permissions: permissionsConfig(fileClaude.permissions),
      subagents: subagentsConfig(fileClaude.subagents),
      prWatch: prWatchMode(flags['pr-watch'] ?? env.AGENTCRAFT_PR_WATCH ?? fileClaude.prWatch),
      prPollSeconds: Math.max(15, num(flags['pr-poll-seconds'] ?? fileClaude.prPollSeconds, 180)),
    },
    sim: {
      speed: Math.max(0.05, num(flags.speed ?? env.AGENTCRAFT_SIM_SPEED ?? fileSim.speed, 1)),
      seed: num(flags.seed ?? fileSim.seed, 7),
      showcase: bool(flags.showcase, false),
      showcaseAt: flags.showcase === 'late' ? 'showcase-late' : 'showcase',
      autoAnswer: bool(flags['auto-answer'] ?? fileSim.autoAnswer, false),
      ambient: bool(flags.ambient ?? fileSim.ambient, true),
    },
  };
  cfg.claude.designModel = str(flags['design-model']) ?? str(env.AGENTCRAFT_DESIGN_MODEL) ?? str(fileClaude.designModel) ?? cfg.claude.workerModel;
  if (cfg.sim.showcase) cfg.autostart = true;
  return cfg;
}

export const HELP = `AgentCraft Foreman ${FOREMAN_VERSION}

usage: npm run start -- [options]

  --backend sim|claude     agent backend (default: claude)
  --repo <path>[,<path>]   register local git repo(s) at start (sim: defaults to a fresh sandbox/sim-demo)
  --goal "<text>"          submit a goal right away
  --port <n>               WebSocket port (default 7878, env AGENTCRAFT_PORT)
  --home <dir>             state root (default ~/.agentcraft, env AGENTCRAFT_HOME)
  --user-name <name>       your name, as the agents address you (default: your OS user name,
                           env AGENTCRAFT_USER_NAME, config.json "userName")
  --profile <name>         state profile under home (default: backend name)
  --reset                  wipe this profile's state first (sim: also recreates the demo repo)
  --notify / --no-notify   desktop notification when a decision waits (default: on for claude, off for sim)
  --toast-silent           toasts without sound
  --repo-poll-ms <n>       how often repo checkouts are checked for head/dirty changes (default 10000)
  --merge-style merge|squash  approved merges: merge commit keeping the agents' commits (default),
                           or one squashed commit authored by you
  --no-sign-merges         never sign approved merge commits (default: signed when your git
                           config has commit.gpgsign=true; claude backend only)
  --debug                  verbose logging
  --no-client-token        (dev only) every local WebSocket client may change things, as before the
                           client token; by default only clients that send <profile>/client.token do

 sim backend
  --speed <x>              speed multiplier (default 1)
  --seed <n>               scenario seed (default 7)
  --autostart              start the scripted scenario immediately (otherwise: on first goal.submit)
  --showcase               run to the showcase checkpoint instantly and hold that static state
  --showcase late          hold the later state instead (blocked, error, done and running agents)
  --auto-answer            answer the scenario's own decisions (unattended runs)
  --no-ambient             no idle chatter while waiting on you

 claude backend
  auth: ANTHROPIC_API_KEY, or a cloud provider (CLAUDE_CODE_USE_BEDROCK / _VERTEX / _FOUNDRY)
  --use-claude-login       use your local \`claude\` CLI login instead (personal use only; env
                           AGENTCRAFT_USE_CLAUDE_LOGIN=1, config.json claude.useClaudeLogin)
  --model <m>              model for lead and workers (default lead: opus, workers: sonnet)
  --lead-model <m> / --worker-model <m>
  --design-model <m>       model of the building design agent (default: the worker model)
  --effort low|medium|high|xhigh|max   (default medium)
  --max-turns <n>          turn cap per session run (default lead 40 / worker 80)
  --workers <n|ids>        team size or comma list (default juniper,kit,wren)
  --max-concurrent <n>     workers running at once (default 3)
  --max-concurrent-turns <n>  agent turns at once, leads and workers together (default: no cap)
  --leads <ids>            leads in use, in assignment order (default marlow,ines,bram,cass): marlow
                           leads home and repos without a building, each other lead one building
                           the mod assigns it; "marlow" alone = one lead for everything
  --throttle-concurrent <n>  workers at once while your plan reports a usage warning (default 1);
                           at the limit itself every agent waits until it resets, then resumes
  --max-budget <usd>       per-turn USD cap
  --ci "<cmd>"             test command run after each task (default: detected, e.g. npm test;
                           per repo: config.json repoSettings.<path>.ci, with setup and copy)
  --no-lead-review         skip the lead's review turn before merge decisions
  --no-resume              do not resume interrupted sessions on start
  --pr-watch off|observe|on  pull requests of tasks landed as PRs: observe (default) polls them and
                           has Marlow triage new comments / reviews / failing checks, but posts
                           nothing and starts no follow-up; on also sends fixes back to the worker
                           and posts replies after your approval; off = a PR finishes its task
  --pr-poll-seconds <n>    how often watched PRs are polled (default 180)
`;
