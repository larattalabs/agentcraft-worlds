// Editable settings: config.get / config.set (docs/HUB.md "Team and Settings tabs").
//
// Every editable config.json key has a definition here: label and help for the hub, group, type,
// default, which flags / AGENTCRAFT_* variables override it, and whether a change applies to the
// running Foreman ("live": from the next turn or poll) or only after foreman.restart.
//
// config.set validates every change (all or nothing), then the whole candidate file the same way the
// Foreman loads it (config.ts configFrom), writes it atomically with a .bak of the previous file -
// unknown keys, other sections and key order kept - and copies the live settings into the running
// configuration in place (the backends hold references to its objects, so they see the change).
//
// No secret ever leaves through here: environment values are never read (only which variables are
// set), MCP servers are shown by name and command only, a repository's env by its variable names.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { CastMember } from './cast.js';
import { configEnv, configFrom, DEFAULT_LEADS, leadsList, RESERVED_KEYS, type Config, type RepoSettings } from './config.js';
import { DEFAULT_CONTEXT } from './agents/claude/context.js';
import { DEFAULT_PERMISSIONS } from './agents/claude/permissions.js';
import { readAgentFile } from './agents/claude/subagents.js';
import type { SettingDef } from './protocol.js';
import { samePath } from './repos.js';
import { defaultUserName } from './user.js';
import { writeFileAtomic } from './util/fsx.js';
import { jsonErrorMessage } from './util/jsonpos.js';

/** A config.get / config.set problem the user can fix (refused with this message). */
export class ConfigError extends Error {}

type Raw = Record<string, unknown>;
type SettingType = SettingDef['type'];

const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];
const SEVERITIES = ['critical', 'important', 'minor', 'testing', 'performance', 'teachable'];
const MODEL_RE = /^[A-Za-z0-9][\w.:@/[\]-]{0,99}$/;

/** What a definition needs to know besides the config: the cast, and for a repository its agent files. */
interface SpecCtx {
  cfg: Config;
  cast: CastMember[];
  repo?: { path: string; agents: RepoAgent[] };
}

type Checked = { value: unknown } | { error: string };

interface Spec {
  key: string;
  label: string;
  help: string;
  group: string;
  type: SettingType;
  options?: string[] | ((x: SpecCtx) => string[]);
  min?: number;
  max?: number;
  live: boolean;
  readOnly?: boolean;
  /** flags (as parseFlags names them, without --) and variables that win over config.json */
  flags?: string[];
  envs?: string[];
  /** other spellings of the key in config.json (read; written to when present) */
  alt?: string[];
  def: unknown | ((x: SpecCtx) => unknown);
  /** the configured value (global: from the parsed config; repository: from its parsed settings) */
  get: (cfg: Config, rs: RepoSettings, raw: unknown) => unknown;
  /** value for the file; undefined = remove the key (back to the default) */
  normalize?: (v: unknown, x: SpecCtx) => Checked;
}

// ---- type checks ---------------------------------------------------------------------------------

const optionsOf = (s: Spec, x: SpecCtx): string[] | undefined => (typeof s.options === 'function' ? s.options(x) : s.options);

function checkType(s: Spec, v: unknown, x: SpecCtx): Checked {
  const opts = optionsOf(s, x);
  switch (s.type) {
    case 'bool':
      return typeof v === 'boolean' ? { value: v } : { error: 'must be true or false' };
    case 'int':
      if (typeof v !== 'number' || !Number.isInteger(v)) return { error: 'must be a whole number' };
      if (s.min !== undefined && v < s.min) return { error: `must be at least ${s.min}` };
      if (s.max !== undefined && v > s.max) return { error: `must be at most ${s.max}` };
      return { value: v };
    case 'enum':
      return typeof v === 'string' && opts?.includes(v) ? { value: v } : { error: `must be one of ${opts?.join(', ')}` };
    case 'string':
      if (typeof v !== 'string') return { error: 'must be text' };
      if (v.length > 20_000) return { error: 'is too long' };
      return { value: v.trim() === '' ? undefined : v };
    case 'stringList': {
      if (!Array.isArray(v) || v.some((i) => typeof i !== 'string')) return { error: 'must be a list of text' };
      const items = [...new Set((v as string[]).map((i) => i.trim()).filter(Boolean))];
      const bad = opts ? items.filter((i) => !opts.includes(i)) : [];
      if (bad.length) return { error: `unknown ${bad.map((b) => `"${b}"`).join(', ')} (use ${opts!.join(', ')})` };
      return { value: items };
    }
    case 'model':
      if (typeof v !== 'string') return { error: 'must be a model name' };
      if (v.trim() === '' || v.trim() === 'default') return { value: undefined };
      return MODEL_RE.test(v.trim()) ? { value: v.trim() } : { error: `"${v}" is not a model name` };
    case 'effort':
      if (typeof v !== 'string' || !opts?.includes(v)) return { error: `must be one of ${opts?.join(', ')}` };
      return { value: v === 'default' ? undefined : v };
    case 'agentList': {
      if (!Array.isArray(v) || v.some((i) => typeof i !== 'string')) return { error: 'must be a list of agent ids' };
      const ids = [...new Set((v as string[]).map((i) => i.trim().toLowerCase()).filter(Boolean))];
      const bad = ids.filter((i) => !opts?.includes(i));
      return bad.length ? { error: `no such agent ${bad.map((b) => `"${b}"`).join(', ')} (agents: ${opts?.join(', ')})` } : { value: ids };
    }
    case 'map':
      return { error: 'is read-only' };
  }
}

// ---- definitions ---------------------------------------------------------------------------------

const castOf = (x: SpecCtx, role: 'lead' | 'worker') => x.cast.filter((c) => c.role === role).map((c) => c.id);

/** The Opus and Sonnet models the configuration uses, the aliases, and "default" (no Haiku). */
export function modelOptions(cfg: Config): string[] {
  const c = cfg.claude;
  const used = [c.leadModel, c.workerModel, c.designModel, ...Object.values(c.taskModels), ...Object.values(c.agents).map((a) => a.model)];
  const ids = used.filter((m): m is string => !!m && /opus|sonnet/i.test(m) && !/haiku/i.test(m));
  return ['default', ...new Set(['opus', 'sonnet', ...ids])];
}

const models = (x: SpecCtx) => modelOptions(x.cfg);
const g = (path: string) => (cfg: Config) => path.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Raw)[k] : undefined), cfg);

function globalSpecs(x: SpecCtx): Spec[] {
  const specs: Spec[] = [
    // team
    { key: 'claude.workers', group: 'team', type: 'agentList', label: 'Workers on the team', help: 'Which workers are on the team. Takes effect after a restart; to bring one in right away, spawn it from the hub.', options: (y) => castOf(y, 'worker'), live: false, flags: ['workers'], envs: ['AGENTCRAFT_WORKERS'], def: ['juniper', 'kit', 'wren'], get: g('claude.workers') },
    {
      key: 'claude.leads',
      group: 'team',
      type: 'agentList',
      label: 'Leads, in assignment order',
      help: 'Marlow leads home and repositories without a building; each other lead takes the next building you place, in this order. Marlow alone means one lead for everything. Takes effect after a restart.',
      options: (y) => castOf(y, 'lead'),
      live: false,
      flags: ['leads'],
      envs: ['AGENTCRAFT_LEADS'],
      def: DEFAULT_LEADS,
      get: g('claude.leads'),
      normalize: (v) => ({ value: leadsList(v) }),
    },
    { key: 'claude.leadReview', group: 'team', type: 'bool', label: 'Lead reviews finished work', help: 'The lead reviews each finished task (diff and tests) before the merge decision reaches you. Off: the decision comes straight after the tests.', live: true, flags: ['lead-review'], def: true, get: g('claude.leadReview') },
    // models
    { key: 'claude.leadModel', group: 'models', type: 'model', label: 'Lead model', help: 'The model the leads plan and review with (unless a lead has its own). From the next turn.', options: models, live: true, flags: ['lead-model', 'model'], envs: ['AGENTCRAFT_LEAD_MODEL'], def: 'opus', get: g('claude.leadModel') },
    { key: 'claude.leadEffort', group: 'models', type: 'effort', label: 'Lead effort', help: 'How hard the leads think. From the next turn.', options: EFFORTS, live: true, flags: ['lead-effort', 'effort'], def: 'medium', get: g('claude.leadEffort') },
    { key: 'claude.workerModel', group: 'models', type: 'model', label: 'Worker model', help: 'The model workers implement tasks with (unless a worker, its role in a repository or the task size says otherwise). From the next turn.', options: models, live: true, flags: ['worker-model', 'model'], envs: ['AGENTCRAFT_WORKER_MODEL'], def: 'sonnet', get: g('claude.workerModel') },
    { key: 'claude.effort', group: 'models', type: 'effort', label: 'Worker effort', help: 'How hard the workers think. From the next turn.', options: EFFORTS, live: true, flags: ['effort'], def: 'medium', get: g('claude.effort') },
    { key: 'claude.designModel', group: 'models', type: 'model', label: 'Building designer model', help: 'The model that designs new buildings. "default" uses the worker model.', options: models, live: true, flags: ['design-model'], envs: ['AGENTCRAFT_DESIGN_MODEL'], def: 'default', get: (cfg, _rs, raw) => (raw === undefined && cfg.claude.designModel === cfg.claude.workerModel ? 'default' : cfg.claude.designModel) },
    ...(['small', 'normal', 'large'] as const).map(
      (size): Spec => ({ key: `claude.taskModels.${size}`, group: 'models', type: 'model', label: `Model for ${size} tasks`, help: `When the lead marks a task ${size}, it runs on this model whatever the worker's own. "default": the worker's model.`, options: models, live: true, def: 'default', get: (cfg) => cfg.claude.taskModels[size] ?? 'default' }),
    ),
    { key: 'claude.maxConcurrent', group: 'models', type: 'int', min: 1, max: 10, label: 'Workers at once', help: 'How many workers may run a turn at the same time. From the next scheduling round.', live: true, flags: ['max-concurrent'], def: 3, get: g('claude.maxConcurrent') },
    { key: 'claude.throttleConcurrent', group: 'models', type: 'int', min: 1, max: 10, label: 'Workers at once on a usage warning', help: 'While your plan reports a usage warning, at most this many workers run at once until the window resets.', live: true, flags: ['throttle-concurrent'], def: 1, get: g('claude.throttleConcurrent') },
    { key: 'claude.maxConcurrentTurns', group: 'models', type: 'int', min: 0, max: 20, label: 'Agent turns at once', help: 'A cap on turns running at once, leads and workers together. 0: no cap besides "Workers at once".', live: true, flags: ['max-concurrent-turns'], def: 0, get: (cfg) => cfg.claude.maxConcurrentTurns ?? 0, normalize: (v) => ({ value: v === 0 ? undefined : v }) },
    // general
    { key: 'userName', group: 'general', type: 'string', label: 'Your name', help: 'How the agents address you, in prompts, the feed and the hub. Empty: your OS user name.', live: true, flags: ['user-name'], envs: ['AGENTCRAFT_USER_NAME'], alt: ['user-name'], def: defaultUserName(), get: (cfg) => cfg.userName, normalize: (v) => (typeof v === 'string' && v.trim().length > 40 ? { error: 'must be at most 40 characters' } : { value: typeof v === 'string' && v.trim() ? v.trim() : undefined }) },
    { key: 'notify', group: 'general', type: 'bool', label: 'Desktop notifications', help: 'A desktop notification when a decision waits for you.', live: true, flags: ['notify'], envs: ['AGENTCRAFT_NOTIFY'], def: x.cfg.backend === 'claude', get: (cfg) => cfg.notify },
    { key: 'toastSilent', group: 'general', type: 'bool', label: 'Silent notifications', help: 'Desktop notifications without sound.', live: true, flags: ['toast-silent'], envs: ['AGENTCRAFT_TOAST_SILENT'], alt: ['toast-silent'], def: false, get: (cfg) => cfg.toastSilent },
    { key: 'mergeStyle', group: 'general', type: 'enum', options: ['merge', 'squash'], label: 'Merge style', help: 'merge: a merge commit that keeps the agents\' commits; squash: one commit with the task\'s changes, authored by you.', live: true, flags: ['merge-style'], envs: ['AGENTCRAFT_MERGE_STYLE'], alt: ['merge-style'], def: 'merge', get: (cfg) => cfg.mergeStyle },
    { key: 'signMerges', group: 'general', type: 'bool', label: 'Sign approved merges', help: 'Sign your approved merge commits when your git config signs commits (commit.gpgsign). Agents never sign.', live: true, flags: ['sign-merges'], envs: ['AGENTCRAFT_SIGN_MERGES'], alt: ['sign-merges'], def: x.cfg.backend === 'claude', get: (cfg) => cfg.signMerges },
    // permissions
    { key: 'claude.permissions.mode', group: 'permissions', type: 'enum', options: ['policy', 'auto'], label: 'Permission mode', help: 'policy: what AgentCraft can verify is safe runs, everything else asks you in-world. auto: Claude Code\'s classifier decides what policy would ask about; git push, git internals and writes into your checkouts still ask. From the next turn.', live: true, def: DEFAULT_PERMISSIONS.mode, get: g('claude.permissions.mode') },
    { key: 'claude.permissions.allow', group: 'permissions', type: 'stringList', label: 'Allow rules', help: 'Claude Code permission rules that run without asking, e.g. Bash(codex exec:*) or WebFetch(domain:docs.example.com). They skip AgentCraft\'s checks for what they match. From the next turn.', live: true, def: [], get: g('claude.permissions.allow') },
    { key: 'claude.permissions.deny', group: 'permissions', type: 'stringList', label: 'Deny rules', help: 'Claude Code permission rules that are always refused. From the next turn.', live: true, def: [], get: g('claude.permissions.deny') },
    { key: 'claude.permissions.webTools', group: 'permissions', type: 'bool', label: 'Web tools', help: 'Let agents use WebFetch and WebSearch (each new host still asks in policy mode). From the next turn.', live: true, def: DEFAULT_PERMISSIONS.webTools, get: g('claude.permissions.webTools') },
    { key: 'claude.permissions.protectCheckouts', group: 'permissions', type: 'bool', label: 'Protect your checkouts (auto mode)', help: 'In auto mode, writes into your repositories\' checkouts and AgentCraft\'s own state still ask you.', live: true, def: DEFAULT_PERMISSIONS.protectCheckouts, get: g('claude.permissions.protectCheckouts') },
    // context
    { key: 'claude.context.userInstructions', group: 'context', type: 'bool', label: 'Your CLAUDE.md', help: 'Give the agents your ~/.claude/CLAUDE.md as instructions. From the next turn.', live: true, def: DEFAULT_CONTEXT.userInstructions, get: g('claude.context.userInstructions') },
    { key: 'claude.context.skills', group: 'context', type: 'stringList', label: 'Skills', help: 'Claude Code skills the agents may load (names under ~/.claude/skills, or paths). After a restart.', live: false, def: [], get: g('claude.context.skills') },
    { key: 'claude.context.sessionHistory.enabled', group: 'context', type: 'bool', label: 'Earlier sessions', help: 'Let agents search and read your earlier Claude sessions in these repositories. After a restart.', live: false, def: DEFAULT_CONTEXT.sessionHistory.enabled, get: g('claude.context.sessionHistory.enabled') },
    { key: 'claude.context.sessionHistory.days', group: 'context', type: 'int', min: 1, max: 365, label: 'Earlier sessions: days back', help: 'How far back earlier sessions are searchable. After a restart.', live: false, def: DEFAULT_CONTEXT.sessionHistory.days, get: g('claude.context.sessionHistory.days') },
    { key: 'claude.context.maxChars', group: 'context', type: 'int', min: 1000, max: 1_000_000, label: 'Instruction size limit', help: 'Characters of instruction files (CLAUDE.md, AGENTS.md, ...) given to each agent. From the next turn.', live: true, def: DEFAULT_CONTEXT.maxChars, get: g('claude.context.maxChars') },
    {
      key: 'claude.context.mcpAllow',
      group: 'context',
      type: 'stringList',
      label: 'MCP tools allowed without asking',
      help: 'Tool names (or prefixes ending in *) of your MCP servers that run without asking, e.g. mcp__github__get_issue. From the next call.',
      live: true,
      def: [],
      get: g('claude.context.mcpAllow'),
      normalize: (v) => {
        const bad = (v as string[]).filter((p) => !p.startsWith('mcp__') || p.startsWith('mcp__agentcraft__'));
        return bad.length ? { error: `${bad.join(', ')}: must start with mcp__ (and not mcp__agentcraft__)` } : { value: v };
      },
    },
    { key: 'claude.context.connectors', group: 'context', type: 'stringList', label: 'claude.ai connectors', help: 'claude.ai connectors (by name, e.g. monday.com) the agents may use; none when empty. From the next turn.', live: true, def: [], get: g('claude.context.connectors') },
    {
      key: 'claude.context.mcpServers',
      group: 'context',
      type: 'map',
      readOnly: true,
      label: 'MCP servers',
      help: 'The MCP servers in config.json (claude.context.mcpServers), by name and command. Edit config.json to change them.',
      live: false,
      def: {},
      get: (cfg) => Object.fromEntries(Object.entries(cfg.claude.context.mcpServers).map(([name, d]) => [name, serverCommand(d)])),
    },
    // subagents
    { key: 'claude.subagents.enabled', group: 'subagents', type: 'bool', label: 'Subagents', help: 'Let agents start Claude Code subagents (they work in the agent\'s worktree under the same rules). After a restart.', live: false, def: false, get: g('claude.subagents.enabled') },
    { key: 'claude.subagents.agents', group: 'subagents', type: 'stringList', label: 'Subagent definitions', help: 'Agent files the agents may use as subagents: names under ~/.claude/agents, or paths. After a restart.', live: false, def: [], get: g('claude.subagents.agents') },
    // prs
    { key: 'claude.prWatch', group: 'prs', type: 'enum', options: ['off', 'observe', 'on'], label: 'Pull request watching', help: 'For tasks landed as pull requests. observe: poll them and have the lead triage new comments, reviews and failing checks, posting nothing; on: also send fixes back to the worker and post replies after your approval; off: opening the PR finishes the task.', live: true, flags: ['pr-watch'], envs: ['AGENTCRAFT_PR_WATCH'], def: 'observe', get: g('claude.prWatch') },
    { key: 'claude.prPollSeconds', group: 'prs', type: 'int', min: 15, max: 3600, label: 'Poll pull requests every (seconds)', help: 'How often watched pull requests are checked.', live: true, flags: ['pr-poll-seconds'], def: 180, get: g('claude.prPollSeconds') },
    // usage
    { key: 'claude.maxBudgetUsdPerTurn', group: 'usage', type: 'int', min: 0, max: 1000, label: 'Budget per turn (USD)', help: 'Stop an agent turn that costs more than this many dollars. 0: no cap. From the next turn.', live: true, flags: ['max-budget'], def: 0, get: (cfg) => cfg.claude.maxBudgetUsdPerTurn ?? 0, normalize: (v) => ({ value: v === 0 ? undefined : v }) },
    { key: 'claude.useClaudeLogin', group: 'usage', type: 'bool', label: 'Use your claude.ai login', help: 'Run the agents on your local claude CLI login (your plan) instead of an API key. Personal use only. After a restart.', live: false, flags: ['use-claude-login'], envs: ['AGENTCRAFT_USE_CLAUDE_LOGIN'], def: false, get: g('claude.useClaudeLogin') },
  ];
  // per agent: role title, specialty prompt, model, effort
  for (const c of x.cast) {
    const who = `${c.name}${c.role === 'lead' ? ' (lead)' : ''}`;
    specs.push(
      { key: `claude.agents.${c.id}.title`, group: 'team', type: 'string', label: `${who}: title`, help: `The role on ${c.name}'s nameplate, e.g. Frontend. Empty: "${c.title}".`, live: true, def: c.title, get: (cfg) => cfg.claude.agents[c.id]?.title ?? c.title, normalize: (v) => (typeof v === 'string' && v.trim().length > 40 ? { error: 'must be at most 40 characters' } : { value: typeof v === 'string' && v.trim() ? v.trim() : undefined }) },
      { key: `claude.agents.${c.id}.prompt`, group: 'team', type: 'string', label: `${who}: specialty`, help: `What ${c.name} specialises in: goes into ${c.name}'s own prompt and the lead's team list. From the next turn.`, live: true, def: c.description, get: (cfg) => cfg.claude.agents[c.id]?.prompt ?? c.description },
      { key: `claude.agents.${c.id}.model`, group: 'team', type: 'model', label: `${who}: model`, help: `${c.name}'s own model. "default": the ${c.role} model. From the next turn.`, options: models, live: true, def: 'default', get: (cfg) => cfg.claude.agents[c.id]?.model ?? 'default' },
      { key: `claude.agents.${c.id}.effort`, group: 'team', type: 'effort', label: `${who}: effort`, help: `${c.name}'s own effort. "default": the ${c.role} effort. From the next turn.`, options: ['default', ...EFFORTS], live: true, def: 'default', get: (cfg) => cfg.claude.agents[c.id]?.effort ?? 'default' },
    );
  }
  return specs;
}

function repoSpecs(x: SpecCtx): Spec[] {
  const agentIds = (y: SpecCtx) => y.repo?.agents.map((a) => a.id) ?? [];
  const r = (k: keyof RepoSettings) => (_cfg: Config, rs: RepoSettings) => rs[k];
  const pattern = (re: RegExp, what: string) => (v: unknown): Checked => (v === undefined || re.test(v as string) ? { value: v } : { error: `must be ${what}` });
  const specs: Spec[] = [
    { key: 'land', group: 'landing', type: 'enum', options: ['merge', 'pr'], label: 'How approved work lands', help: 'merge: a local merge commit into the base branch; pr: the Foreman pushes the branch and opens a pull request.', live: true, def: 'merge', get: (_c, rs) => rs.land ?? 'merge' },
    { key: 'baseBranch', group: 'landing', type: 'string', label: 'Base branch', help: 'The branch agents start from and land into. Empty: whatever the checkout has checked out.', live: true, def: '', get: (_c, rs) => rs.baseBranch ?? '', normalize: pattern(/^[\w./-]+$/, 'a branch name') },
    { key: 'pr.remote', group: 'landing', type: 'string', label: 'Pull requests: remote', help: 'The remote to push to and open pull requests on. Empty: origin.', live: true, def: 'origin', get: (_c, rs) => rs.pr?.remote ?? '', normalize: pattern(/^[\w.-]+$/, 'a remote name') },
    { key: 'pr.branchPrefix', group: 'landing', type: 'string', label: 'Pull requests: branch prefix', help: 'Remote branch name prefix, e.g. feat/. Empty: the agent\'s branch name.', live: true, def: '', get: (_c, rs) => rs.pr?.branchPrefix ?? '', normalize: pattern(/^[\w./-]+$/, 'a branch name prefix') },
    { key: 'pr.draft', group: 'landing', type: 'bool', label: 'Pull requests: drafts', help: 'Open pull requests as drafts.', live: true, def: false, get: (_c, rs) => rs.pr?.draft ?? false },
    { key: 'pr.squash', group: 'landing', type: 'bool', label: 'Pull requests: one commit', help: 'Push one commit authored by you (the agents\' commits squashed, with Co-authored-by) instead of the agents\' commits.', live: true, def: false, get: (_c, rs) => rs.pr?.squash ?? false },
    { key: 'ci', group: 'worktrees', type: 'string', label: 'Test command', help: 'Run after each task. Empty: --ci, else detected (e.g. npm test).', live: true, def: '', get: (_c, rs) => rs.ci ?? '' },
    { key: 'setup', group: 'worktrees', type: 'string', label: 'Worktree setup command', help: 'Run once in each new worker worktree before the worker starts, e.g. npm ci.', live: true, def: '', get: (_c, rs) => rs.setup ?? '' },
    { key: 'copy', group: 'worktrees', type: 'stringList', label: 'Copy into new worktrees', help: 'Untracked files or folders copied from your checkout into each new worktree, e.g. .env.', live: true, def: [], get: (_c, rs) => rs.copy ?? [] },
    { key: 'setupTimeoutMs', group: 'worktrees', type: 'int', min: 1000, max: 3_600_000, label: 'Setup timeout (ms)', help: 'How long the setup command may run.', live: true, def: 600_000, get: (_c, rs) => rs.setupTimeoutMs ?? 600_000 },
    {
      key: 'protect',
      group: 'worktrees',
      type: 'stringList',
      label: 'Never committed',
      help: 'Paths relative to the repository (a trailing / is a folder) that agents never commit; editing them asks you.',
      live: true,
      def: [],
      get: (_c, rs) => rs.protect ?? [],
      normalize: (v) => {
        const bad = (v as string[]).filter((p) => p.startsWith('/') || /^[A-Za-z]:/.test(p) || p.split(/[\\/]/).includes('..'));
        return bad.length ? { error: `${bad.join(', ')}: must be relative to the repository, without ..` } : { value: v };
      },
    },
    { key: 'env', group: 'worktrees', type: 'map', readOnly: true, label: 'Environment', help: 'Variables set for agents, setup and tests in this repository (names only; values are never shown). Edit config.json to change them.', live: true, def: {}, get: (_c, rs) => Object.fromEntries(Object.keys(rs.env ?? {}).map((k) => [k, '(hidden)'])) },
    { key: 'subagents', group: 'agents', type: 'enum', options: ['off', 'repo'], label: 'Repository agents as subagents', help: 'repo: agents working here may use the repository\'s .claude/agents files as subagents.', live: true, def: 'off', get: (_c, rs) => rs.subagents ?? 'off', normalize: (v) => ({ value: v === 'off' ? undefined : v }) },
    { key: 'prReview.autoSeverities', group: 'review', type: 'stringList', options: SEVERITIES, label: 'Fold in automatically', help: 'Automated review findings of these severities are folded in without asking you.', live: true, def: ['critical', 'important'], get: (_c, rs) => rs.prReview?.autoSeverities ?? ['critical', 'important'] },
    { key: 'prReview.maxRounds', group: 'review', type: 'int', min: 0, max: 10, label: 'Automatic fold-in rounds', help: 'Fold-in rounds driven by automated reviews per pull request before you decide.', live: true, def: 2, get: (_c, rs) => rs.prReview?.maxRounds ?? 2 },
  ];
  for (const c of x.cast) {
    specs.push({
      key: `roles.${c.id}`,
      group: 'agents',
      type: 'string',
      options: agentIds,
      label: `${c.name}'s role here`,
      help: `One of the repository's .claude/agents files: ${c.name}'s role, prompt and model whenever ${c.name} works in this repository. Empty: none.`,
      live: true,
      def: '',
      get: (_c, rs) => rs.roles?.[c.id] ?? '',
      normalize: (v, y) => {
        if (v === undefined) return { value: undefined };
        const s = v as string;
        const hit = y.repo?.agents.find((a) => a.id === s || a.name === s || a.path === s);
        if (hit) return { value: hit.id };
        if (!/^[\w./-]+$/.test(s)) return { error: 'must be one of the repository\'s agent files' };
        return { error: `no agent file "${s}" in .claude/agents (files: ${agentIds(y).join(', ') || 'none'})` };
      },
    });
  }
  return specs;
}

/** An MCP server for display: its command (stdio) or type and host (remote); never args, env or headers. */
function serverCommand(d: unknown): string {
  const o = (d && typeof d === 'object' ? d : {}) as Raw;
  if (typeof o.command === 'string') return o.command;
  if (typeof o.url === 'string') {
    try {
      return `${typeof o.type === 'string' ? o.type : 'remote'} ${new URL(o.url).host}`;
    } catch {
      return String(o.type ?? 'remote');
    }
  }
  return String(o.type ?? '?');
}

// ---- repository agent files ----------------------------------------------------------------------

export interface RepoAgent {
  /** file name without .md: the value roles.<agent> stores */
  id: string;
  name: string;
  /** repo-relative, forward slashes */
  path: string;
  description?: string;
  model?: string;
}

/** A repository's `.claude/agents/*.md` (valid ones: front matter with a description, and a prompt). */
export function listRepoAgents(repoPath: string): RepoAgent[] {
  const dir = path.join(repoPath, '.claude', 'agents');
  let files: string[];
  try {
    files = fs.readdirSync(dir).filter((f) => f.endsWith('.md')).sort();
  } catch {
    return [];
  }
  const out: RepoAgent[] = [];
  for (const f of files) {
    const a = readAgentFile(path.join(dir, f));
    if (typeof a === 'string') continue;
    out.push({ id: f.slice(0, -3), name: a.name, path: `.claude/agents/${f}`, description: a.description, ...(a.model ? { model: a.model } : {}) });
  }
  return out;
}

// ---- the file --------------------------------------------------------------------------------------

/** config.json as it is on disk ({} when missing); refuses a file that is not a JSON object. */
export function readRawConfig(file: string): { raw: Raw; text: string | undefined } {
  let text: string | undefined;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return { raw: {}, text: undefined };
  }
  if (!text.trim()) return { raw: {}, text };
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new ConfigError(`${jsonErrorMessage(file, text)}; fix it by hand first`);
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new ConfigError(`${file} must hold a JSON object`);
  return { raw: raw as Raw, text };
}

const own = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k);

/** Path segments that would reach Object.prototype are refused outright. */
function checkSegments(keys: string[]): void {
  const bad = keys.find((k) => RESERVED_KEYS.has(k));
  if (bad !== undefined) throw new ConfigError(`"${bad}" is not allowed in a setting key`);
}

function getPath(o: unknown, keys: string[]): unknown {
  let cur = o;
  for (const k of keys) {
    if (!cur || typeof cur !== 'object' || Array.isArray(cur) || RESERVED_KEYS.has(k) || !own(cur, k)) return undefined;
    cur = (cur as Raw)[k];
  }
  return cur;
}

function hasPath(o: unknown, keys: string[]): boolean {
  const parent = getPath(o, keys.slice(0, -1));
  return !!parent && typeof parent === 'object' && !Array.isArray(parent) && Object.prototype.hasOwnProperty.call(parent, keys[keys.length - 1]!);
}

/** Set (value) or remove (undefined) a key, creating objects on the way (a boolean becomes {enabled}). */
function setPath(o: Raw, keys: string[], value: unknown): void {
  checkSegments(keys);
  const trail: Raw[] = [o];
  let cur = o;
  for (const k of keys.slice(0, -1)) {
    let next = own(cur, k) ? cur[k] : undefined;
    if (!next || typeof next !== 'object' || Array.isArray(next)) {
      if (value === undefined) return;
      // e.g. claude.context.sessionHistory: true -> { enabled: true }
      next = typeof next === 'boolean' ? { enabled: next } : {};
      cur[k] = next;
    }
    cur = next as Raw;
    trail.push(cur);
  }
  const leaf = keys[keys.length - 1]!;
  if (value !== undefined) {
    cur[leaf] = value;
    return;
  }
  delete cur[leaf];
  // drop objects the removal left empty (never the file itself)
  for (let i = trail.length - 1; i > 0; i--) {
    if (Object.keys(trail[i]!).length) break;
    delete trail[i - 1]![keys[i - 1]!];
  }
}

function indentOf(text: string | undefined): string | number {
  const m = text ? /\n([ \t]+)"/.exec(text) : null;
  return m ? m[1]! : 2;
}

/** Write config.json atomically; the previous file is kept as config.json.bak. */
export function writeRawConfig(file: string, raw: Raw, previousText: string | undefined): void {
  if (previousText !== undefined) writeFileAtomic(`${file}.bak`, previousText);
  writeFileAtomic(file, `${JSON.stringify(raw, null, indentOf(previousText))}\n`);
}

/** The key config.json uses for a repository: its spelling when it has one (e.g. ~/code/app), else the absolute path. */
export function repoFileKey(raw: Raw, repoPath: string): string {
  const rs = raw.repoSettings;
  if (rs && typeof rs === 'object') {
    for (const k of Object.keys(rs as Raw)) if (samePath(path.resolve(k.replace(/^~(?=$|[\\/])/, os.homedir())), repoPath)) return k;
  }
  return repoPath;
}

// ---- get -------------------------------------------------------------------------------------------

export interface ConfigTarget {
  /** the running configuration (its argv and overrides decide flag / env sources) */
  cfg: Config;
  cast: CastMember[];
  /** a repository's settings instead of the global ones */
  repo?: { id: string; path: string };
}

function ctxOf(t: ConfigTarget, cfg: Config): SpecCtx {
  return { cfg, cast: t.cast, ...(t.repo ? { repo: { path: t.repo.path, agents: listRepoAgents(t.repo.path) } } : {}) };
}

function specsFor(x: SpecCtx): Spec[] {
  return x.repo ? repoSpecs(x) : globalSpecs(x);
}

/** The flag or variable that overrides this key, as the user typed it (e.g. "--no-notify"). */
function overrideOf(s: Spec, cfg: Config): string | undefined {
  for (const f of s.flags ?? []) {
    if (!cfg.overrides.flags.includes(f)) continue;
    const arg = cfg.argv.find((a) => a === `--${f}` || a === `--no-${f}` || a.startsWith(`--${f}=`));
    return arg?.split('=')[0] ?? `--${f}`;
  }
  return (s.envs ?? []).find((e) => cfg.overrides.env.includes(e));
}

function fileKeys(s: Spec): string[][] {
  return [s.key.split('.'), ...(s.alt ?? []).map((a) => a.split('.'))];
}

function repoSettingsOf(cfg: Config, repoPath: string): RepoSettings {
  for (const [p, rs] of Object.entries(cfg.repoSettings)) if (samePath(p, repoPath)) return rs;
  return {};
}

function defsOf(t: ConfigTarget, cfg: Config, raw: Raw): SettingDef[] {
  const x = ctxOf(t, cfg);
  const section = t.repo ? getPath(raw, ['repoSettings', repoFileKey(raw, t.repo.path)]) : raw;
  const rs = t.repo ? repoSettingsOf(cfg, t.repo.path) : {};
  return specsFor(x).map((s) => {
    const by = t.repo ? undefined : overrideOf(s, cfg);
    const inFile = fileKeys(s).some((k) => hasPath(section, k));
    const rawValue = fileKeys(s).map((k) => getPath(section, k)).find((v) => v !== undefined);
    const opts = optionsOf(s, x);
    const def = typeof s.def === 'function' ? (s.def as (y: SpecCtx) => unknown)(x) : s.def;
    return {
      key: s.key,
      label: s.label,
      help: s.help,
      group: s.group,
      type: s.type,
      ...(opts ? { options: opts } : {}),
      ...(s.min !== undefined ? { min: s.min } : {}),
      ...(s.max !== undefined ? { max: s.max } : {}),
      value: s.get(cfg, rs, rawValue),
      default: def,
      source: by ? (by.startsWith('--') ? 'flag' : 'env') : inFile ? 'file' : 'default',
      live: s.live,
      ...(by ? { overriddenBy: by } : {}),
      ...(s.readOnly ? { readOnly: true } : {}),
    } satisfies SettingDef;
  });
}

/** config.get: the setting definitions with their configured values (from config.json as it is now). */
export function configGet(t: ConfigTarget): { file: string; settings: SettingDef[] } {
  const { raw } = readRawConfig(t.cfg.configFile);
  const now = configFrom(t.cfg.argv, envFor(t.cfg), raw);
  return { file: t.cfg.configFile, settings: defsOf(t, now, raw) };
}

/** The variables the running config was read with: a variable keeps overriding exactly as it did at start. */
function envFor(cfg: Config): NodeJS.ProcessEnv {
  return configEnv(cfg);
}

// ---- set -------------------------------------------------------------------------------------------

export interface ConfigSetResult {
  applied: string[];
  restartRequired: string[];
  overridden: Array<{ key: string; by: string }>;
  /** the configuration as config.json now says (live parts are copied into the running config) */
  next: Config;
}

/**
 * config.set: validate every change, write config.json, return what applies now. The caller copies
 * the live settings into the running config (applyLive) and announces the change.
 */
export function configSet(t: ConfigTarget, changes: Array<{ key: string; value: unknown }>): ConfigSetResult {
  const { raw, text } = readRawConfig(t.cfg.configFile);
  const x = ctxOf(t, t.cfg);
  const specs = new Map(specsFor(x).map((s) => [s.key, s]));
  const errors: string[] = [];
  const writes: Array<{ spec: Spec; keys: string[]; value: unknown }> = [];
  const seen = new Set<string>();
  const sectionPath = t.repo ? ['repoSettings', repoFileKey(raw, t.repo.path)] : [];
  const section = getPath(raw, sectionPath);
  for (const { key, value } of changes) {
    if (key.split('.').some((k) => RESERVED_KEYS.has(k))) {
      errors.push(`${key}: not an editable setting`);
      continue;
    }
    const s = specs.get(key);
    if (!s) {
      errors.push(`${key}: not an editable setting`);
      continue;
    }
    if (seen.has(key)) {
      errors.push(`${key}: changed twice`);
      continue;
    }
    seen.add(key);
    if (s.readOnly) {
      errors.push(`${key}: is read-only (edit config.json by hand)`);
      continue;
    }
    let checked: Checked = value === null ? { value: undefined } : checkType(s, value, x);
    if ('value' in checked && checked.value !== undefined && s.normalize) checked = s.normalize(checked.value, x);
    if ('error' in checked) {
      errors.push(`${key}: ${checked.error}`);
      continue;
    }
    // write to the spelling the file already uses
    const at = fileKeys(s).find((k) => hasPath(section, k)) ?? s.key.split('.');
    writes.push({ spec: s, keys: [...sectionPath, ...at], value: checked.value });
  }
  if (errors.length) throw new ConfigError(errors.join('; '));

  const candidate = structuredClone(raw);
  for (const w of writes) setPath(candidate, w.keys, w.value);
  let next: Config;
  try {
    next = configFrom(t.cfg.argv, envFor(t.cfg), candidate);
  } catch (e) {
    throw new ConfigError(`the new configuration is not valid: ${(e as Error).message}`);
  }
  writeRawConfig(t.cfg.configFile, candidate, text);

  const applied: string[] = [];
  const restartRequired: string[] = [];
  const overridden: Array<{ key: string; by: string }> = [];
  for (const w of writes) {
    const by = t.repo ? undefined : overrideOf(w.spec, t.cfg);
    if (by) overridden.push({ key: w.spec.key, by });
    else if (w.spec.live) applied.push(w.spec.key);
    else restartRequired.push(w.spec.key);
  }
  return { applied, restartRequired, overridden, next };
}

/** The restart-only settings whose configured value differs from what the running Foreman started with. */
export function pendingRestart(baseline: Map<string, string>, next: Config, cast: CastMember[]): string[] {
  const x: SpecCtx = { cfg: next, cast };
  return globalSpecs(x)
    .filter((s) => !s.live && !s.readOnly && baseline.has(s.key) && baseline.get(s.key) !== JSON.stringify(s.get(next, {}, undefined)))
    .map((s) => s.key);
}

/** The restart-only settings as the Foreman started (compared by pendingRestart). */
export function restartBaseline(cfg: Config, cast: CastMember[]): Map<string, string> {
  const x: SpecCtx = { cfg, cast };
  return new Map(globalSpecs(x).filter((s) => !s.live && !s.readOnly).map((s) => [s.key, JSON.stringify(s.get(cfg, {}, undefined))]));
}

/** Replace an object's contents in place (other holders of the reference see the change). */
function replaceInPlace<T extends object>(target: T, source: T): void {
  for (const k of Object.keys(target)) delete (target as Raw)[k];
  Object.assign(target, source);
}

/**
 * Copy every live setting of `next` into the running config, in place. Restart-only settings
 * (workers, leads, skills, session history, subagents, claude.ai login) are left as they started.
 */
export function applyLive(running: Config, next: Config): void {
  running.userName = next.userName;
  running.notify = next.notify;
  running.toastSilent = next.toastSilent;
  running.mergeStyle = next.mergeStyle;
  running.signMerges = next.signMerges;
  const c = running.claude;
  const n = next.claude;
  c.leadModel = n.leadModel;
  c.workerModel = n.workerModel;
  c.designModel = n.designModel;
  c.effort = n.effort;
  c.leadEffort = n.leadEffort;
  c.maxConcurrent = n.maxConcurrent;
  c.throttleConcurrent = n.throttleConcurrent;
  c.leadReview = n.leadReview;
  c.prWatch = n.prWatch;
  c.prPollSeconds = n.prPollSeconds;
  if (n.maxConcurrentTurns) c.maxConcurrentTurns = n.maxConcurrentTurns;
  else delete c.maxConcurrentTurns;
  if (n.maxBudgetUsdPerTurn) c.maxBudgetUsdPerTurn = n.maxBudgetUsdPerTurn;
  else delete c.maxBudgetUsdPerTurn;
  replaceInPlace(c.agents, n.agents);
  replaceInPlace(c.taskModels, n.taskModels);
  Object.assign(c.permissions, n.permissions);
  c.context.userInstructions = n.context.userInstructions;
  c.context.maxChars = n.context.maxChars;
  c.context.mcpAllow = n.context.mcpAllow;
  c.context.connectors = n.context.connectors;
  replaceInPlace(running.repoSettings, next.repoSettings);
}
