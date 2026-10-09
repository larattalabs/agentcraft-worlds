// Claude backend: running a turn. One SDK query() per job with the session resumed (or rotated, for a
// lead), the CLI spawned by us so a stopped turn's whole process tree can be ended, session records
// and costs, and the hand-off of a task whose turn was stopped.
import { spawn } from 'node:child_process';
import os from 'node:os';
import { type Options } from '@anthropic-ai/claude-agent-sdk';
import { foremanPrivatePath } from '../../policy.js';
import { formatInbox } from '../../bus.js';
import { descendantsOf, killSnapshot, killTree, orphansOf, processTable, type ProcEntry } from '../../util/proc.js';
import { truncate } from '../../util/text.js';
import { instructionsBlock } from './context.js';
import { boardSummary, leadRepoContext, leadSystemPrompt, planText, workerSystemPrompt } from '../prompts.js';
import { withAuthMode } from './auth.js';
import { isAuthText } from './failures.js';
import { limitFromText, StreamMapper, type TurnStats } from './stream.js';
import { buildMcpServer, MCP_SERVER, type TurnHandle } from './tools.js';
import { userName } from '../../user.js';
import { scrubEnv } from '../../util/env.js';
import { TurnSetupLayer } from './turnSetup.js';
import { type AbortReason, type Job, type Running, sleep, alive, TURN_TIMEOUT_MS, LIMIT_BACKOFF_MS, rotationDue } from './core.js';

export abstract class SessionsLayer extends TurnSetupLayer {
  /** Abort a turn: remember why, snapshot its process tree while the CLI is still alive, close it. */
  protected abortTurn(r: Running, reason: AbortReason): void {
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
  protected reap(r: Running, graceMs = 4000): Promise<void> {
    r.reaping ??= this.doReap(r, graceMs).catch((e) => this.fm.log.warn(`clean-up of ${r.job.agentId}'s turn: ${(e as Error).message}`));
    return r.reaping;
  }

  protected async doReap(r: Running, graceMs: number): Promise<void> {
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
  protected async quiesce(agentId: string, maxMs = 15_000): Promise<void> {
    const r = this.running.get(agentId) ?? this.lastTurn.get(agentId);
    if (!r) return;
    if (r.done) await Promise.race([r.done.catch(() => undefined), sleep(maxMs)]);
    await this.reap(r);
  }

  /**
   * A task leaves `fromAgent` (stop, reassign): hold it off the board until that agent's turn is
   * really over, then commit its work on its branch (the next worker starts from that branch).
   */
  protected handOff(taskId: string, fromAgent: string, why: string): void {
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

  protected override async runJob(job: Job): Promise<void> {
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
      // a lead's long-lived session for a goal: start over once it is old or long (seeded below).
      // Never for a job that continues the session's own work (restart, usage limit, pause, retry,
      // an answer after a restart): its prompt only means something inside that session.
      let rotated = '';
      if (resume && role === 'lead' && session && !job.resumed) {
        session.startedAt ??= Date.now();
        const why = rotationDue(session, this.cfg.leadSession);
        if (why) {
          resume = undefined;
          rotated = this.rotationSeed(job, why);
          this.fm.log.info(`${agentId}: fresh session for ${job.sessionKey} (the last one is ${why})`);
          this.fm.agentLog(agentId, 'text', `Starting a fresh session for ${job.goalId ?? 'this work'} (the last one is ${why}); seeded with the plan, the board and the latest messages`);
        }
      }
      this.st.inflight[agentId] = { ...this.inflightOf(job), startedAt: job.goalReply && job.startedAt ? job.startedAt : Date.now() };
      // a goal-message turn that was held (offerHeldGoalMessages): its messages are its own again
      if (job.goalReply && job.messageIds?.length) this.fm.bus.markRead(agentId, job.messageIds);
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
        env: scrubEnv(withAuthMode({ ...this.env({ agentId, cwd }), ...this.fm.repos.envFor(repoId), ...this.fm.repos.commitIdentityEnv(repoId, agentId) }, this.cfg.useClaudeLogin)),
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
        // an exception can quote a secret (a spawn error names the environment value it refused)
        const msg = this.fm.redact((e as Error).message ?? String(e));
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
      this.offerHeldGoalMessages(next);
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

  protected recordSession(key: string, sessionId: string, model: string, stats?: TurnStats): void {
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
  protected rotationSeed(job: Job, why: string): string {
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
  protected spawner(entry: Running, label: string): NonNullable<Options['spawnClaudeCodeProcess']> {
    return (o) => {
      const child = spawn(o.command, o.args, { cwd: o.cwd, env: scrubEnv(o.env as NodeJS.ProcessEnv), stdio: ['pipe', 'pipe', 'pipe'], signal: o.signal, windowsHide: true });
      child.stderr?.setEncoding('utf8');
      // whole lines only: a secret split across two chunks is still one line when it is redacted
      let pending = '';
      const line = (l: string) => {
        if (l.trim()) this.fm.log.debug(`[${label} stderr] ${this.fm.redact(l).trim().slice(0, 300)}`);
      };
      child.stderr?.on('data', (s: string) => {
        pending += s;
        const lines = pending.split('\n');
        pending = lines.pop() ?? '';
        // an endless line is flushed in large pieces (redacted whole up to there)
        if (pending.length > 64_000) {
          lines.push(pending);
          pending = '';
        }
        for (const l of lines) line(l);
      });
      child.stderr?.on('end', () => {
        line(pending);
        pending = '';
      });
      child.on('error', (e) => this.fm.log.debug(`[${label}] CLI process error: ${e.message}`));
      entry.child = child;
      entry.spawnedAt = Date.now();
      return child;
    };
  }
}
