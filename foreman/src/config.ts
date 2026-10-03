// Configuration: defaults < <AGENTCRAFT_HOME>/config.json < environment < CLI flags.
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readJson } from './util/fsx.js';
import type { BackendName } from './protocol.js';
import { defaultUserName } from './user.js';
import type { EffortLevel, McpServerConfig } from '@anthropic-ai/claude-agent-sdk';
import { DEFAULT_CONTEXT, type AgentContextConfig } from './agents/claude/context.js';

export const FOREMAN_VERSION = '0.1.0';

/** Repo root of the AgentCraft project (foreman/src/config.ts -> ../..). */
export const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

export interface ClaudeConfig {
  leadModel: string;
  workerModel: string;
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
    files: strings(o.files),
    maxChars: typeof o.maxChars === 'number' && o.maxChars > 0 ? o.maxChars : DEFAULT_CONTEXT.maxChars,
    skills: strings(o.skills),
    mcpServers: servers,
    mcpAllow: strings(o.mcpAllow).filter((p) => p.startsWith('mcp__') && !p.startsWith('mcp__agentcraft__')),
  };
}

function mergeStyle(v: unknown): 'merge' | 'squash' {
  if (v === undefined || v === 'merge') return 'merge';
  if (v === 'squash') return 'squash';
  throw new Error(`unknown merge style "${String(v)}" (use merge or squash)`);
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
  'lead-model', 'worker-model', 'effort', 'lead-effort', 'max-turns', 'max-turns-lead', 'max-turns-worker',
  'max-concurrent', 'throttle-concurrent', 'ci', 'max-budget', 'resume', 'lead-review', 'speed', 'seed', 'showcase', 'auto-answer',
  'ambient',
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

export function loadConfig(argv: string[], env: NodeJS.ProcessEnv = process.env): Config {
  const { flags, positional } = parseFlags(argv);
  checkArgs(flags, positional);
  const home = path.resolve(str(flags.home) ?? env.AGENTCRAFT_HOME ?? path.join(os.homedir(), '.agentcraft'));
  const file = readJson<Record<string, unknown>>(path.join(home, 'config.json')) ?? {};
  const fileClaude = (file.claude ?? {}) as Record<string, unknown>;
  const fileSim = (file.sim ?? {}) as Record<string, unknown>;
  const pick = (k: string, envKey?: string): unknown => flags[k] ?? (envKey ? env[envKey] : undefined) ?? file[k];

  const backendRaw = String(pick('backend', 'AGENTCRAFT_BACKEND') ?? 'claude');
  if (backendRaw !== 'sim' && backendRaw !== 'claude') throw new Error(`unknown backend "${backendRaw}" (use sim or claude)`);
  const backend = backendRaw as BackendName;
  const profile = str(pick('profile', 'AGENTCRAFT_PROFILE')) ?? backend;
  if (!/^[a-zA-Z0-9_-]+$/.test(profile)) throw new Error(`bad profile name "${profile}"`);

  const repoFlag = flags.repo;
  const repos: string[] = [];
  if (typeof repoFlag === 'string') repos.push(...repoFlag.split(',').map((s) => s.trim()).filter(Boolean));
  else if (Array.isArray(file.repos)) repos.push(...(file.repos as string[]));

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
      repoSettings[path.resolve(k.replace(/^~(?=$|[\\/])/, os.homedir()))] = s;
    }
  }

  const model = str(flags.model);
  const cfg: Config = {
    backend,
    userName: (str(pick('user-name', 'AGENTCRAFT_USER_NAME')) ?? str(file.userName))?.trim().slice(0, 40) || defaultUserName(),
    home,
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
    toastSilent: bool(pick('toast-silent', 'AGENTCRAFT_TOAST_SILENT'), false),
    debug: bool(pick('debug', 'AGENTCRAFT_DEBUG'), false),
    quiet: bool(flags.quiet, false),
    projectRoot: PROJECT_ROOT,
    allowBrowserOrigins: bool(pick('allow-browser-origins'), false),
    repoPollMs: Math.max(500, num(pick('repo-poll-ms'), 10_000)),
    mergeStyle: mergeStyle(pick('merge-style', 'AGENTCRAFT_MERGE_STYLE')),
    // the sim answers merges unattended (screenshot QA, --auto-answer): never sign there
    signMerges: bool(pick('sign-merges', 'AGENTCRAFT_SIGN_MERGES'), backend === 'claude'),
    claude: {
      leadModel: str(flags['lead-model']) ?? model ?? str(env.AGENTCRAFT_LEAD_MODEL) ?? str(fileClaude.leadModel) ?? 'opus',
      workerModel: str(flags['worker-model']) ?? model ?? str(env.AGENTCRAFT_WORKER_MODEL) ?? str(fileClaude.workerModel) ?? 'sonnet',
      effort: effort(flags.effort ?? fileClaude.effort, 'medium'),
      leadEffort: effort(flags['lead-effort'] ?? flags.effort ?? fileClaude.leadEffort, 'medium'),
      maxTurnsLead: num(flags['max-turns-lead'] ?? flags['max-turns'] ?? fileClaude.maxTurnsLead, 40),
      maxTurnsWorker: num(flags['max-turns-worker'] ?? flags['max-turns'] ?? fileClaude.maxTurnsWorker, 80),
      maxConcurrent: Math.max(1, num(flags['max-concurrent'] ?? fileClaude.maxConcurrent, 3)),
      throttleConcurrent: Math.max(1, num(flags['throttle-concurrent'] ?? fileClaude.throttleConcurrent, 1)),
      workers,
      ciCommand: str(flags.ci) ?? str(fileClaude.ciCommand),
      maxBudgetUsdPerTurn: flags['max-budget'] !== undefined ? num(flags['max-budget'], 0) || undefined : (fileClaude.maxBudgetUsdPerTurn as number | undefined),
      resumeOnStart: bool(flags.resume ?? fileClaude.resumeOnStart, true),
      leadReview: bool(flags['lead-review'] ?? fileClaude.leadReview, true),
      useClaudeLogin: bool(flags['use-claude-login'] ?? env.AGENTCRAFT_USE_CLAUDE_LOGIN ?? fileClaude.useClaudeLogin, false),
      context: contextConfig(fileClaude.context),
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
  --effort low|medium|high|xhigh|max   (default medium)
  --max-turns <n>          turn cap per session run (default lead 40 / worker 80)
  --workers <n|ids>        team size or comma list (default juniper,kit,wren)
  --max-concurrent <n>     workers running at once (default 3)
  --throttle-concurrent <n>  workers at once while your plan reports a usage warning (default 1);
                           at the limit itself every agent waits until it resets, then resumes
  --max-budget <usd>       per-turn USD cap
  --ci "<cmd>"             test command run after each task (default: detected, e.g. npm test;
                           per repo: config.json repoSettings.<path>.ci, with setup and copy)
  --no-lead-review         skip the lead's review turn before merge decisions
  --no-resume              do not resume interrupted sessions on start
`;
