// The team, building design turns (claude/design.ts DesignJobs runs them): turns outside the roster
// on the design engine (Claude) with the workers' permission machinery, but nobody to ask, so
// whatever would ask is refused.
import { type AuxTurn, type AuxTurnSpec } from '../../claude/design.js';
import { classifyToolUse, describeToolCall } from '../../../policy.js';
import type { Design } from '../../../protocol.js';
import { truncate } from '../../../util/text.js';
import type { Engine, PermissionGate, TurnStats } from '../../engine.js';
import { type TurnHandle } from '../../tools.js';
import { GoalMessageJobs } from './goalMessages.js';
import { type AbortReason, type Running, LIMIT_BACKOFF_MS } from '../core.js';

export abstract class DesignTurns extends GoalMessageJobs {
  /** The engine of building design jobs (Claude), if the team has one. */
  protected designEngine(): Engine | undefined {
    return this.opts.engines.design;
  }

  /**
   * A design job's tool calls: the same policy as a worker in `cwd`, but nobody can answer a
   * permission prompt (there is no avatar to ask through), so whatever the policy would ask
   * about is refused with a reason the agent can work with.
   */
  protected auxGate(logId: string, cwd: string, turn: TurnHandle, engine: Engine): PermissionGate {
    return async (toolName, input) => {
      if (turn.signal.aborted) return { allow: false, message: 'The job was stopped.', interrupt: true };
      const v = classifyToolUse(toolName, input, this.policyContext(logId, 'worker', cwd, undefined, engine));
      if (v.action === 'allow') return { allow: true };
      this.fm.agentLog(logId, 'error', `blocked: ${describeToolCall(toolName, input)} (${v.reason})`);
      return {
        allow: false,
        message: v.action === 'deny' ? v.reason : `Not allowed in a design job (${v.reason}). Nobody can approve permission prompts here: work only inside ${cwd} with the kit and node, without network access or installs.`,
      };
    };
  }

  /**
   * One engine turn for something that is not a roster agent (a building design job): the workers'
   * permission machinery (policy / auto mode guardrails) without prompts, subagents, web tools,
   * skills or the user's MCP servers; the same env, process tracking, usage limit reports, session
   * records and abort/reap as agent turns.
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
    const engine = this.designEngine();
    const turn: TurnHandle = { signal: entry.abort.signal, reason: () => entry.reason };
    this.fm.agentLog(logId, 'text', `${spec.resume ? 'Resuming' : 'Starting'} ${spec.sessionKey} (${spec.model})`);
    let stats: TurnStats;
    const timer = setTimeout(() => this.abortTurn(entry, 'timeout'), spec.timeoutMs);
    timer.unref?.();
    try {
      if (!engine) throw new Error('building designs need the Claude engine');
      stats = await engine.runTurn({
        agentId: logId,
        role: 'worker',
        cwd,
        prompt: spec.prompt,
        instructions: spec.systemAppend,
        ...(spec.resume ? { resume: spec.resume } : {}),
        env: this.env({ agentId: logId, cwd }),
        abort: entry.abort,
        turn,
        permission: this.auxGate(logId, cwd, turn, engine),
        tools: [],
        model: spec.model,
        effort: spec.effort,
        policy: () => this.policyContext(logId, 'worker', cwd, undefined, engine),
        onRateLimit: (r) => this.onRateLimit(r),
        onUsageSource: (q) => this.refreshUsage(q),
        onProcess: (child) => {
          entry.child = child;
          entry.spawnedAt = Date.now();
        },
        onSession: (id) => {
          if (this.fm.store.data.sessions[spec.sessionKey]?.sessionId !== id) this.recordSession(spec.sessionKey, id, spec.model, undefined, engine.id);
        },
        aux: { mcpServers: spec.mcpServers, maxTurns: spec.maxTurns, ...(spec.onMessage ? { onMessage: (m) => spec.onMessage!(m as never) } : {}) },
      });
      if (stats.sessionId) this.recordSession(spec.sessionKey, stats.sessionId, spec.model, stats, engine.id);
      if (stats.authFailed) this.markAuthFailed(engine.authFailedMessage(stats.authFailed));
    } catch (e) {
      const partial = (e as { stats?: TurnStats }).stats;
      stats = { isError: false, errors: [], ...(partial ?? {}) };
      if (!entry.abort.signal.aborted) {
        const msg = this.fm.redact((e as Error).message ?? String(e));
        this.fm.log.error(`${logId} ${spec.sessionKey} failed: ${msg}`);
        this.fm.agentLog(logId, 'error', `session error: ${truncate(msg, 400)}`);
        const c = engine?.classifyError?.(msg) ?? { auth: false };
        if (c.auth && engine) {
          stats.authFailed = truncate(msg, 160);
          this.markAuthFailed(engine.authFailedMessage(truncate(msg, 160)));
        }
        stats.isError = true;
        stats.errors = [...stats.errors, msg];
        if (c.limited) {
          stats.limited = true;
          if (c.limited.resetsAt) stats.rateLimit = { status: 'rejected', resetsAt: c.limited.resetsAt };
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
    if (!this.designEngine()) {
      this.fm.designFailed(d.id, 'Building designs need the Claude engine: this team runs no Claude agents.');
      return;
    }
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
