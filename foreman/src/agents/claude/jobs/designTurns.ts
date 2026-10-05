// Claude backend, building design turns (design.ts DesignJobs): turns outside the roster with the
// workers' permission machinery, but nobody to ask, so whatever would ask is refused.
import { type CanUseTool, type Options, type PermissionResult } from '@anthropic-ai/claude-agent-sdk';
import { type AuxTurn, type AuxTurnSpec } from '../design.js';
import { classifyToolUse, describeToolCall } from '../../../policy.js';
import type { Design } from '../../../protocol.js';
import { truncate } from '../../../util/text.js';
import { isAuthText } from '../failures.js';
import { limitFromText, StreamMapper, type TurnStats } from '../stream.js';
import { type TurnHandle } from '../tools.js';
import { GoalMessageJobs } from './goalMessages.js';
import { type AbortReason, type Running, LIMIT_BACKOFF_MS } from '../core.js';

export abstract class DesignTurns extends GoalMessageJobs {
  /**
   * A design job's tool calls: the same policy as a worker in `cwd`, but nobody can answer a
   * permission prompt (there is no avatar to ask through), so whatever the policy would ask
   * about is refused with a reason the agent can work with.
   */
  protected auxCanUseTool(logId: string, cwd: string, turn: TurnHandle): CanUseTool {
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
  override runAuxTurn(spec: AuxTurnSpec): AuxTurn {
    const abort = new AbortController();
    const entry: Running = { abort, job: { kind: 'followup', agentId: spec.logId, prompt: spec.prompt, sessionKey: spec.sessionKey } };
    const done = this.doAuxTurn(spec, entry);
    const tracked: Promise<void> = done.then(() => undefined).finally(() => this.turnPromises.delete(tracked));
    this.turnPromises.add(tracked);
    return { abort: (reason) => this.abortTurn(entry, reason), done };
  }

  protected async doAuxTurn(spec: AuxTurnSpec, entry: Running): Promise<{ stats: TurnStats; reason?: AbortReason }> {
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
}
