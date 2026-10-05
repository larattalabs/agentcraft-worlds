// Claude backend: what a turn runs with. Its directory (the worker's worktree, the lead's read-only
// view), role, model and effort, the AgentCraft policy (canUseTool), permission mode, tools, hooks,
// the skills plugin and subagents, and the repository's worktree setup.
import path from 'node:path';
import { type CanUseTool, type HookCallbackMatcher, type EffortLevel, type Options, type PermissionResult } from '@anthropic-ai/claude-agent-sdk';
import { classifyToolUse, describeRuleKey, describeToolCall, foremanPrivateVerdict, type PolicyContext } from '../../policy.js';
import type { Task } from '../../protocol.js';
import { PERMISSION_OPTIONS } from '../../protocol.js';
import { truncate } from '../../util/text.js';
import { buildSkillsPlugin, workspaceInstructionDirs } from './context.js';
import { SessionHistory } from '../../history.js';
import { connectorHook, foremanGuardHook, guardrailHook } from './permissions.js';
import { agentFilePath, loadRepoAgents, loadSubagents, readAgentFile } from './subagents.js';
import { type RepoRole } from './prompts.js';
import { MCP_SERVER, type TurnHandle } from './tools.js';
import { userName } from '../../user.js';
import { HoldsLayer } from './holds.js';
import { type Job, NO_ATTRIBUTION } from './core.js';

export abstract class TurnSetupLayer extends HoldsLayer {
  /** Build the skills plugin and report what context the agents get. */
  protected prepareContext(): void {
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
        redact: (t) => this.fm.redact(t),
      });
      what.push(`session history (${c.sessionHistory.days} days)`);
    }
    this.fm.log.info(`agent context: ${what.join('; ') || 'none'}`);
  }

  /** Load subagent definitions and report the permission setup. */
  protected preparePermissions(): void {
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
  protected policyContext(agentId: string, role: 'lead' | 'worker', cwd: string, repoId?: string): PolicyContext {
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
  protected foremanPrivate(): NonNullable<PolicyContext['foreman']> {
    const e = this.fm.endpoint;
    return { home: this.fm.config.home, port: e?.port ?? this.fm.config.port, ...(e?.tokenFile ? { tokenFile: e.tokenFile } : {}) };
  }

  /** Auto mode's guardrails cover the user's checkouts and AgentCraft's own state. */
  protected protectedRoots(): string[] {
    return this.cfg.permissions.protectCheckouts ? [...this.fm.repos.list().map((r) => r.path), this.fm.config.home] : [];
  }

  protected async cwdFor(job: Job): Promise<{ cwd: string; role: 'lead' | 'worker'; repoId: string }> {
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
  protected baseFor(t: Task): string | undefined {
    const own = this.st.taskBase[t.id];
    if (own) return own;
    const gb = t.goalId ? this.st.goalBranch[t.goalId] : undefined;
    return gb && gb.repoId === t.repoId ? gb.branch : undefined;
  }

  /** Subagents are on for this repository: globally, or the repository's own (repoSettings.subagents "repo"). */
  protected subagentsOn(repoId: string | undefined): boolean {
    return this.cfg.subagents.enabled || (!!repoId && this.fm.repos.settingsFor(repoId).subagents === 'repo');
  }

  protected canUseTool(agentId: string, role: 'lead' | 'worker', cwd: string, turn: TurnHandle, repoId?: string): CanUseTool {
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
  protected async prepareWorktree(agentId: string, taskId: string): Promise<string> {
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
  protected permissionOptions(agentId: string, role: 'lead' | 'worker', cwd: string, turn: TurnHandle, repoId?: string, mcpServers?: string[]): Partial<Options> {
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
}
