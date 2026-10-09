// The team: what a turn runs with. Its directory (the worker's worktree, the lead's read-only view),
// role, model and effort, the policy context and the permission gate (the AgentCraft policy, then the
// user in game), and the repository's worktree setup. The engine-specific part of a turn (the Claude
// engine's permission mode, hooks, skills and subagents) is the engine's (claude/engine.ts).
import path from 'node:path';
import type { EffortLevel } from '@anthropic-ai/claude-agent-sdk';
import { classifyToolUse, describeRuleKey, describeToolCall, exactKey, type PolicyContext, type Verdict } from '../../policy.js';
import type { Task } from '../../protocol.js';
import { PERMISSION_OPTIONS } from '../../protocol.js';
import { truncate } from '../../util/text.js';
import { workspaceInstructionDirs } from '../claude/context.js';
import { SessionHistory } from '../../history.js';
import { agentFilePath, readAgentFile } from '../claude/subagents.js';
import { type RepoRole } from '../prompts.js';
import { type TurnHandle } from '../tools.js';
import type { Engine, PermissionGate, Role } from '../engine.js';
import { userName } from '../../user.js';
import { HoldsLayer } from './holds.js';
import { type Job, foremanPrivateOf } from './core.js';
import { matchingShellRule } from './rules.js';
import { ClaudeEngine } from '../claude/engine.js';

export abstract class TurnSetupLayer extends HoldsLayer {
  /** Prepare every engine (Claude: the skills plugin, subagents) and report what context the agents get. */
  protected prepareContext(): void {
    for (const e of this.allEngines()) e.prepare?.();
    const c = this.cfg.context;
    const skills = this.allEngines().find((e): e is ClaudeEngine => e instanceof ClaudeEngine)?.skillsPlugin;
    const what = [
      c.repoInstructions ? 'repo CLAUDE.md/AGENTS.md' : '',
      c.userInstructions ? '~/.claude/CLAUDE.md' : '',
      c.files.length ? `${c.files.length} instruction file(s)` : '',
      skills ? `skills ${skills.ids.map((i) => i.split(':')[1]).join(', ')}` : '',
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

  /** What the AgentCraft policy needs to judge this agent's tool calls (`engine`: the one running the turn). */
  protected policyContext(agentId: string, role: 'lead' | 'worker', cwd: string, repoId?: string, engine: Engine = this.engineFor(agentId)): PolicyContext {
    const repo = repoId ? this.fm.repos.get(repoId) : undefined;
    // a workspace holding several repos: its instructions point at docs in the workspace folder
    const workspace = repo && this.cfg.context.workspaceInstructions ? workspaceInstructionDirs(repo.path) : [];
    const protectedPaths = repoId ? this.fm.repos.protectedPaths(repoId) : [];
    const extras = engine.policyExtras?.(repoId) ?? {};
    return {
      role,
      cwd,
      // every registered repository is readable (goals can span them); writing stays in the worktree
      readDirs: [this.fm.memory.dir, ...(extras.readDirs ?? []), ...workspace, ...this.fm.repos.list().map((r) => r.path)],
      ...(protectedPaths.length ? { protectedPaths } : {}),
      alwaysAllow: this.fm.store.data.permissionRules[agentId] ?? [],
      mcpServer: 'agentcraft',
      ...(extras.skills ? { skills: extras.skills } : {}),
      mcpAllow: this.cfg.context.mcpAllow,
      ...(extras.subagents ? { subagents: true } : {}),
      foreman: foremanPrivateOf(this.fm),
      // the user's declared read commands: the lead only (policy.ts), never a worker's
      ...(role === 'lead' && this.cfg.leadReadCommands.length ? { leadReadCommands: this.cfg.leadReadCommands } : {}),
      ...(role === 'worker' && this.runsContributorCode(agentId) ? { untrustedCode: true } : {}),
    };
  }

  /** The worker's task works on a contributor's pull request (PR intake): code the user did not write. */
  protected runsContributorCode(agentId: string): boolean {
    const t = this.fm.tasks.get(this.fm.agent(agentId)?.taskId ?? '');
    const w = t?.repoId && t.worktree ? this.fm.repos.findWorktree(t.repoId, t.worktree) : undefined;
    return !!w && this.fm.repos.keepsContributorCommits(w);
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

  /**
   * The user's Claude Code deny / ask rules (claude.permissions) for an engine that does not apply
   * them itself (Codex): a matching deny rule refuses the call, a matching ask rule asks even when the
   * policy would allow it (team/rules.ts). Claude agents: the CLI applies them.
   */
  protected userRuleVerdict(engine: Engine, toolName: string, input: Record<string, unknown>, verdict: Verdict, alwaysAllow: string[]): Verdict {
    if (engine.id === 'claude' || verdict.action === 'deny') return verdict;
    const p = this.cfg.permissions;
    const deny = matchingShellRule(p.deny, toolName, input);
    if (deny) return { action: 'deny', reason: `denied by your permission rule ${deny}` };
    const ask = matchingShellRule(p.ask, toolName, input);
    if (ask && verdict.action === 'allow') {
      const key = `rule-ask:${exactKey(typeof input.command === 'string' ? input.command : '')}`;
      if (alwaysAllow.includes(key)) return verdict;
      return { action: 'ask', reason: `your permission rule ${ask} asks before this`, ruleKey: key, ruleKeys: [key] };
    }
    return verdict;
  }

  /**
   * The permission gate of one turn: the AgentCraft policy decides, and whatever it cannot allow by
   * itself becomes an in-world permission decision. The same for every engine (Codex maps its
   * approval requests onto the Claude tool vocabulary); `title` is the engine's own description.
   */
  protected permissionGate(agentId: string, role: Role, cwd: string, turn: TurnHandle, repoId?: string, engine: Engine = this.engineFor(agentId)): PermissionGate {
    return async (toolName, input, signal, title) => {
      // a stopped/paused/cancelled turn runs nothing more, even if its CLI has not exited yet
      if (turn.signal.aborted) return { allow: false, message: `Your turn was stopped by ${userName()}.`, interrupt: true };
      const ctx = this.policyContext(agentId, role, cwd, repoId, engine);
      const verdict = this.userRuleVerdict(engine, toolName, input, classifyToolUse(toolName, input, ctx), ctx.alwaysAllow ?? []);
      if (verdict.action === 'allow') return { allow: true };
      if (verdict.action === 'deny') {
        this.fm.agentLog(agentId, 'error', `blocked: ${describeToolCall(toolName, input)} (${verdict.reason})`);
        return { allow: false, message: verdict.reason };
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
        context: `${verdict.reason}\ncwd: ${cwd}\n"${PERMISSION_OPTIONS[1]}" covers: ${[...new Set(verdict.ruleKeys.map(describeRuleKey))].join('; ')}${title ? `\n${title}` : ''}`,
        ...(t ? { taskId: t } : turn.goalId ? { goalId: turn.goalId } : {}),
      });
      this.fm.setAgent(agentId, { state: 'waiting_user', station: 'user', activity: 'asking permission' });
      this.fm.agentLog(agentId, 'tool', `permission? ${describeToolCall(toolName, input)}`);
      const onAbort = () => this.fm.decisions.cancel(d.id, 'turn stopped');
      if (signal.aborted) onAbort();
      else signal.addEventListener('abort', onAbort, { once: true });
      const res = await this.fm.decisions.wait(d.id);
      signal.removeEventListener('abort', onAbort);
      if (signal.aborted) return { allow: false, message: 'The turn was stopped.' };
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
        return { allow: true };
      }
      this.fm.agentLog(agentId, 'error', `${res.status === 'cancelled' ? 'Permission request withdrawn' : `${userName()} denied`}: ${describeToolCall(toolName, input)}`);
      return { allow: false, message: `${userName()} denied this${res.answer?.text ? `: ${res.answer.text}` : ''}. Find another way or ask_user.` };
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
      if (res.skipped === 'contributor') {
        this.fm.agentLog(agentId, 'tool', 'contributor pull request: the repository\'s setup command and copied files are not applied');
        return `Note: this worktree holds a contributor's pull request, so the repository's setup command${s.setup ? ` (\`${s.setup}\`)` : ''} was not run and no local files (like .env) were copied in. Install what you need yourself; installs and other risky commands are shown to ${userName()} first.`;
      }
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
}
