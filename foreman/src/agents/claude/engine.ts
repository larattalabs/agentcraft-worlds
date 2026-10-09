// The Claude engine: one Claude Agent SDK `query()` per turn. We spawn the CLI ourselves (same as
// the SDK's local spawn) so its pid is known and a stopped turn's whole process tree can be ended.
//
// Everything Claude-specific the fork adds lives here: the permission mode (claude.permissions:
// AgentCraft policy or auto mode with guardrails), the PreToolUse guards that hold in both modes
// (the Foreman's private files, the MCP connector gate, the lead's read-only Bash), the user's Claude
// Code permission rules, no Claude attribution in commits or PRs, skills (a Foreman-owned plugin),
// subagents, the user's extra MCP servers and claude.ai connectors, auth modes (API key or the
// claude.ai login), the auth probe's retry classification, the plan's usage windows, and the
// building design turns (aux). The team (../team.ts) decides everything around a turn.
import { spawn } from 'node:child_process';
import path from 'node:path';
import { query, type AgentDefinition, type CanUseTool, type HookCallbackMatcher, type EffortLevel, type McpServerConfig, type Options, type PermissionResult } from '@anthropic-ai/claude-agent-sdk';
import type { ClaudeConfig } from '../../config.js';
import type { Foreman } from '../../foreman.js';
import { classifyToolUse, foremanPrivateVerdict, type PolicyContext } from '../../policy.js';
import { scrubEnv } from '../../util/env.js';
import { lineSplitter } from '../../util/lines.js';
import { truncate } from '../../util/text.js';
import type { AuthCheck, Engine, Role, TurnSpec, TurnStats } from '../engine.js';
import { agentEnv, foremanPrivateOf } from '../team/core.js';
import { detectApiAuth, NO_API_AUTH_MESSAGE, withAuthMode } from './auth.js';
import { buildSkillsPlugin } from './context.js';
import { isAuthText, probeFailure } from './failures.js';
import { connectorHook, foremanGuardHook, guardrailHook, leadReadOnlyHook } from './permissions.js';
import { limitFromText, StreamMapper } from './stream.js';
import { loadRepoAgents, loadSubagents } from './subagents.js';
import { MCP_SERVER, mcpServer } from './tools.js';

/** Claude Code settings that keep Claude's co-author trailer, PR footer and session link out (C8). */
export const NO_ATTRIBUTION = { attribution: { commit: '', pr: '', sessionUrl: false }, includeCoAuthoredBy: false } as const;

export class ClaudeEngine implements Engine {
  readonly id = 'claude' as const;
  readonly label = 'Claude';

  /** the Foreman's skills plugin (built at start from claude.context.skills) */
  skillsPlugin: { path: string; ids: string[] } | undefined;

  /** subagent definitions from claude.subagents.agents (loaded at start) */
  private subagentDefs: Record<string, AgentDefinition> = {};

  constructor(
    private fm: Foreman,
    private cfg: ClaudeConfig,
    private queryFn: typeof query = query,
  ) {}

  model(role: Role): string {
    return role === 'lead' ? this.cfg.leadModel : this.cfg.workerModel;
  }

  authFailedMessage(detail: string): string {
    return `Claude authentication failed (${detail}). Run \`claude\` and /login, then restart the Foreman.`;
  }

  classifyError(message: string): { auth: boolean; limited?: { resetsAt?: number } } {
    const l = limitFromText(message);
    return { auth: isAuthText(message), ...(l.limited ? { limited: l.resetsAt ? { resetsAt: l.resetsAt } : {} } : {}) };
  }

  /** The environment of the auth probe (the same auth mode as the agents). */
  private probeEnv(): Record<string, string | undefined> {
    return scrubEnv(withAuthMode(agentEnv(process.env), this.cfg.useClaudeLogin));
  }

  async checkAuth(): Promise<AuthCheck> {
    // API authentication by default; the claude.ai login only when explicitly opted into
    const api = detectApiAuth(process.env);
    if (!this.cfg.useClaudeLogin && !api.ok) return { ok: false, message: NO_API_AUTH_MESSAGE };
    async function* never(): AsyncGenerator<never> {
      await new Promise(() => undefined);
    }
    const q = this.queryFn({ prompt: never(), options: { settingSources: [], persistSession: false, permissionMode: 'default', env: this.probeEnv() } });
    try {
      const info = await Promise.race([q.accountInfo(), new Promise<never>((_, r) => setTimeout(() => r(new Error('timed out after 45s')), 45_000))]);
      const ok = !!(info.email || info.organization || (info.apiKeySource && info.apiKeySource !== 'none') || (info.tokenSource && info.tokenSource !== 'none') || (info.apiProvider && info.apiProvider !== 'firstParty'));
      if (!ok) throw new Error('not logged in');
      const account = this.cfg.useClaudeLogin
        ? [info.organization, info.subscriptionType].filter(Boolean).join(' · ') || info.apiProvider || 'ok'
        : [api.ok ? api.source : 'API', info.organization].filter(Boolean).join(' · ');
      return { ok: true, account };
    } catch (e) {
      const why = (e as Error).message ?? String(e);
      // the network, a sleeping machine, an API outage: not a bad login (the team retries with backoff)
      if (probeFailure(why) === 'retry') return { ok: false, transient: true, message: why };
      return {
        ok: false,
        message: this.cfg.useClaudeLogin
          ? `Claude login check failed: ${why}. Run \`claude\` and /login, then restart the Foreman. The sim backend still works.`
          : `Claude API check failed: ${why}. Check ANTHROPIC_API_KEY (or your cloud provider settings), then restart the Foreman. The sim backend still works.`,
      };
    } finally {
      try {
        q.close();
      } catch {
        /* ignore */
      }
    }
  }

  /** Build the skills plugin and load subagent definitions (once, at the team's start). */
  prepare(): void {
    const dir = path.join(this.fm.config.dataDir, 'agent-plugin');
    try {
      const { ids, problems } = buildSkillsPlugin(this.cfg.context.skills, dir);
      for (const p of problems) this.fm.log.warn(`claude.context: ${p}`);
      this.skillsPlugin = ids.length ? { path: dir, ids } : undefined;
    } catch (e) {
      this.fm.log.error(`claude.context: could not build the skills plugin: ${(e as Error).message}`);
      this.skillsPlugin = undefined;
    }
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

  /** Subagents are on for this repository: globally, or the repository's own (repoSettings.subagents "repo"). */
  subagentsOn(repoId: string | undefined): boolean {
    return this.cfg.subagents.enabled || (!!repoId && this.fm.repos.settingsFor(repoId).subagents === 'repo');
  }

  policyExtras(repoId: string | undefined): Partial<PolicyContext> {
    return {
      ...(this.skillsPlugin ? { readDirs: [this.skillsPlugin.path], skills: this.skillsPlugin.ids } : {}),
      ...(this.subagentsOn(repoId) ? { subagents: true } : {}),
    };
  }

  /** Auto mode's guardrails cover the user's checkouts and AgentCraft's own state. */
  private protectedRoots(): string[] {
    return this.cfg.permissions.protectCheckouts ? [...this.fm.repos.list().map((r) => r.path), this.fm.config.home] : [];
  }

  /**
   * Permission mode, tools, guards and the user's rules for one turn (claude.permissions,
   * claude.subagents). Policy mode keeps upstream's behaviour: every call through canUseTool.
   */
  permissionOptions(spec: Pick<TurnSpec, 'agentId' | 'role' | 'cwd' | 'repoId' | 'permission' | 'policy'>, mcpServers?: string[]): Partial<Options> {
    const { agentId, role, cwd, repoId } = spec;
    const p = this.cfg.permissions;
    const sub = this.subagentsOn(repoId);
    const settings = repoId ? this.fm.repos.settingsFor(repoId) : {};
    // the repository's own agent files (read from this agent's checkout), except those used as roles
    const repoAgents = settings.subagents === 'repo' ? loadRepoAgents(cwd, new Set(Object.values(settings.roles ?? {}).map((r) => r.replace(/\.md$/, '').split(/[\\/]/).pop()!))) : {};
    const defs = { ...this.subagentDefs, ...repoAgents };
    // the lead's Bash is read-only: the policy (and the lead hook below) asks before anything that is not a read
    const tools = role === 'lead' ? ['Read', 'Grep', 'Glob', 'Bash'] : ['Read', 'Grep', 'Glob', 'Edit', 'Write', 'Bash', 'TodoWrite'];
    if (p.webTools) tools.push('WebFetch', 'WebSearch');
    if (sub) tools.push('Agent', 'Task');
    if (this.skillsPlugin) tools.push('Skill');
    const disallowed = ['Bash(git push:*)', ...(sub ? [] : ['Task', 'Agent']), ...(p.webTools ? [] : ['WebSearch', 'WebFetch'])];
    const rules = p.allow.length || p.deny.length || p.ask.length ? { permissions: { allow: p.allow, deny: p.deny, ask: p.ask } } : {};
    const connectors = this.cfg.context.connectors;
    const policy = (): PolicyContext => spec.policy?.() ?? { role, cwd, foreman: foremanPrivateOf(this.fm) };
    // the Foreman's own files, token and port: denied before anything else, whatever the rules
    const hooks: HookCallbackMatcher[] = [
      {
        hooks: [
          foremanGuardHook(
            (tool, input) => foremanPrivateVerdict(tool, input, { role, cwd, foreman: foremanPrivateOf(this.fm) }),
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
    // the lead is read-only in both modes, whatever the allow rules or the auto mode classifier say; a
    // worker on a contributor's pull request asks before running its code the same way
    if (role === 'lead' || policy().untrustedCode) {
      hooks.push({
        hooks: [
          leadReadOnlyHook(
            (tool, input) => classifyToolUse(tool, input, policy()),
            (tool, decision, reason, subagent) => this.fm.agentLog(agentId, decision === 'deny' ? 'error' : 'tool', `${role === 'lead' ? 'lead read-only' : 'contributor code'} ${decision === 'deny' ? 'blocked' : 'asks you'}${subagent ? ' (subagent)' : ''}: ${tool} (${truncate(reason, 160)})`),
          ),
        ],
      });
    }
    if (p.mode === 'auto') {
      const guard = guardrailHook(
        (tool, input) => classifyToolUse(tool, input, policy()),
        () => this.protectedRoots(),
        (tool, decision, reason, subagent) =>
          this.fm.agentLog(agentId, decision === 'deny' ? 'error' : 'tool', `guardrail ${decision === 'deny' ? 'blocked' : 'asks you'}${subagent ? ' (subagent)' : ''}: ${tool} (${truncate(reason, 160)})`),
      );
      hooks.push({ hooks: [guard] });
    }
    const canUseTool: CanUseTool = async (toolName, input, opts): Promise<PermissionResult> => {
      const r = await spec.permission(toolName, input, opts.signal, opts.title);
      return r.allow ? { behavior: 'allow', updatedInput: input } : { behavior: 'deny', message: r.message, ...(r.interrupt ? { interrupt: true } : {}) };
    };
    return {
      permissionMode: p.mode === 'auto' ? 'auto' : 'default',
      canUseTool,
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

  /** The CLI is spawned by us (same as the SDK's local spawn) so its pid is known: an aborted turn's whole process tree can be ended. */
  private spawner(spec: TurnSpec): NonNullable<Options['spawnClaudeCodeProcess']> {
    const label = spec.agentId;
    return (o) => {
      const child = spawn(o.command, o.args, { cwd: o.cwd, env: scrubEnv(o.env as NodeJS.ProcessEnv), stdio: ['pipe', 'pipe', 'pipe'], signal: o.signal, windowsHide: true });
      child.stderr?.setEncoding('utf8');
      // whole lines only: a secret split across two chunks is still one line when it is redacted (an
      // endless line is dropped, never cut in pieces)
      const lines = lineSplitter((l) => {
        if (l.trim()) this.fm.log.debug(`[${label} stderr] ${this.fm.redact(l).trim().slice(0, 300)}`);
      });
      child.stderr?.on('data', (s: string) => lines.push(s));
      child.stderr?.on('end', () => lines.end());
      child.on('error', (e) => this.fm.log.debug(`[${label}] CLI process error: ${this.fm.redact(e.message)}`));
      spec.onProcess(child);
      return child;
    };
  }

  /** Options of an agent's turn, or of a building design turn (spec.aux). */
  private options(spec: TurnSpec): Options {
    const { role, cwd, abort, aux } = spec;
    const env = scrubEnv(withAuthMode(spec.env, this.cfg.useClaudeLogin));
    const common = {
      cwd,
      model: spec.model ?? this.model(role),
      ...((spec.effort ?? (role === 'lead' ? this.cfg.leadEffort : this.cfg.effort)) ? { effort: (spec.effort ?? (role === 'lead' ? this.cfg.leadEffort : this.cfg.effort)) as EffortLevel } : {}),
      settingSources: [] as [],
      systemPrompt: { type: 'preset' as const, preset: 'claude_code' as const, append: spec.instructions },
      abortController: abort,
      env,
      spawnClaudeCodeProcess: this.spawner(spec),
      ...(spec.resume ? { resume: spec.resume } : {}),
      ...(this.cfg.maxBudgetUsdPerTurn ? { maxBudgetUsd: this.cfg.maxBudgetUsdPerTurn } : {}),
    };
    if (aux) {
      // a design job: the workers' permission machinery without prompts, subagents, web tools,
      // skills or the user's MCP servers
      const { agents: _subagents, ...perm } = this.permissionOptions(spec, Object.keys(aux.mcpServers));
      const off = new Set(['Agent', 'Task', 'WebFetch', 'WebSearch', 'Skill']);
      return {
        ...common,
        maxTurns: aux.maxTurns,
        ...perm,
        // never auto mode: its classifier would decide what the policy asks about (network, ...);
        // here every such call reaches the gate, which refuses it
        permissionMode: 'default',
        tools: ((perm.tools as string[] | undefined) ?? []).filter((t) => !off.has(t)),
        disallowedTools: [...new Set([...(perm.disallowedTools ?? []), 'Agent', 'Task', 'WebFetch', 'WebSearch'])],
        strictMcpConfig: true,
        mcpServers: aux.mcpServers as Record<string, McpServerConfig>,
      };
    }
    return {
      ...common,
      maxTurns: role === 'lead' ? this.cfg.maxTurnsLead : this.cfg.maxTurnsWorker,
      ...this.permissionOptions(spec),
      // the user's extra servers first, so the team tools server can never be replaced
      mcpServers: { ...this.cfg.context.mcpServers, [MCP_SERVER]: mcpServer(spec.tools) },
      ...(this.skillsPlugin ? { plugins: [{ type: 'local' as const, path: this.skillsPlugin.path, skipMcpDiscovery: true }], skills: this.skillsPlugin.ids } : {}),
    };
  }

  async runTurn(spec: TurnSpec): Promise<TurnStats> {
    const { agentId, role, cwd, abort } = spec;
    const mapper = new StreamMapper(this.fm, agentId, cwd, role, spec.onRateLimit);
    const q = this.queryFn({ prompt: spec.prompt, options: this.options(spec) });
    spec.onUsageSource?.(q);
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
    let session: string | undefined;
    let model: string | undefined;
    try {
      for await (const msg of q) {
        if (abort.signal.aborted) break; // nothing from an aborted turn reaches the world
        mapper.handle(msg);
        if (spec.aux?.onMessage) {
          try {
            spec.aux.onMessage(msg);
          } catch (e) {
            this.fm.log.warn(`${agentId}: ${(e as Error).message}`);
          }
        }
        if (mapper.model && mapper.model !== model) {
          model = mapper.model;
          spec.onModel?.(model);
        }
        if (mapper.stats.sessionId && mapper.stats.sessionId !== session) {
          session = mapper.stats.sessionId;
          spec.onSession(session);
        }
      }
    } catch (e) {
      // what the turn did before it failed (its session above all) stays known to the team
      (e as { stats?: TurnStats }).stats = mapper.stats;
      throw e;
    } finally {
      abort.signal.removeEventListener('abort', closeQuery);
    }
    return mapper.stats;
  }
}
