// The team: running a turn. One engine turn per job (Claude: an SDK query(); Codex: an app-server
// thread turn) with the session resumed (or rotated, for a lead), the agent process reported by the
// engine so a stopped turn's whole process tree can be ended, session records and costs, and the
// hand-off of a task whose turn was stopped.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { foremanPrivatePath, foremanPrivateVerdict } from '../../policy.js';
import { matchingShellRule } from './rules.js';
import { formatInbox } from '../../bus.js';
import { descendantsOf, killSnapshot, killTree, orphansOf, processTable, type ProcEntry } from '../../util/proc.js';
import { truncate } from '../../util/text.js';
import { instructionsBlock } from '../claude/context.js';
import { boardSummary, leadRepoContext, leadSystemPrompt, planText, workerSystemPrompt } from '../prompts.js';
import { agentTools, type TurnHandle } from '../tools.js';
import { API_KEY_VARS, CLAUDE_LOGIN_VARS } from '../claude/auth.js';
import { credentialLikeName } from '../../redact.js';
import type { Engine, EngineId, Role, TurnStats } from '../engine.js';
import { modelLabel } from '../models.js';
import { userName } from '../../user.js';
import { scrubEnv } from '../../util/env.js';
import { TurnSetupLayer } from './turnSetup.js';
import { type AbortReason, type Job, type Running, sleep, alive, TURN_TIMEOUT_MS, LIMIT_BACKOFF_MS, rotationDue, withoutClaudeAuth, foremanPrivateOf } from './core.js';

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
      const engine = this.engineFor(agentId);
      const session = this.fm.store.data.sessions[job.sessionKey];
      // a session belongs to the engine that made it (records from before engines were Claude's)
      let resume = !job.fresh && session?.sessionId && (session.engine ?? 'claude') === engine.id ? session.sessionId : undefined;
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
      const priv = foremanPrivateOf(this.fm);
      const extra = instructionsBlock(this.cfg.context, cwd, userName(), os.homedir(), this.fm.repos.get(repoId)?.path, (abs) => foremanPrivatePath(abs, priv));
      if (extra) systemAppend = `${systemAppend}\n\n${extra}`;
      // Claude agents: profiles, task sizes and repository roles pick the model; other engines run
      // their own configured model (a Claude alias would mean nothing to them)
      const picked = engine.id === 'claude' ? this.modelFor(agentId, role, job.taskId, role === 'worker' ? roleOf(agentId) : undefined) : undefined;
      const model = picked?.model ?? engine.model(role);
      this.fm.agentLog(agentId, 'text', `${resume ? 'Resuming' : 'Starting'} ${job.kind}${job.taskId ? ` ${job.taskId}` : ''} (${engine.id === 'claude' ? model : `${engine.label} ${model}`})`);
      if (job.kind === 'followup' || job.resumed) this.fm.agentLog(agentId, 'text', truncate(job.prompt, 400));
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
      // the repository's env (e.g. a PATH for its Node version) on top; GIT_* never comes from it.
      // The Claude engine applies its auth mode to this final environment; other engines never get
      // the Claude credentials.
      const worktreeId = role === 'worker' && job.taskId ? this.fm.tasks.get(job.taskId)?.worktree : undefined;
      const env = scrubEnv({ ...this.env({ agentId, cwd }), ...this.fm.repos.envForWorktree(repoId, worktreeId), ...this.fm.repos.commitIdentityEnv(repoId, agentId) });
      try {
        stats = await engine.runTurn({
          agentId,
          role,
          cwd,
          prompt,
          instructions: systemAppend,
          ...(resume ? { resume } : {}),
          env: this.contributorEnv(agentId, role, engine.id === 'claude' ? env : withoutClaudeAuth(env), engine),
          writableRoots: await this.writableRoots(role, job),
          abort,
          turn,
          permission: this.permissionGate(agentId, role, cwd, turn, repoId, engine),
          tools: agentTools(this.fm, agentId, role, this.hooks, turn, this.history),
          ...(picked ? { model: picked.model, effort: picked.effort } : {}),
          repoId,
          policy: () => this.policyContext(agentId, role, cwd, repoId, engine),
          check: (tool, input) => {
            const priv = foremanPrivateVerdict(tool, input, this.policyContext(agentId, role, cwd, repoId, engine));
            if (priv?.action === 'deny') return priv.reason;
            const rule = matchingShellRule(this.cfg.permissions.deny, tool, input);
            return rule ? `denied by your permission rule ${rule}` : undefined;
          },
          onRateLimit: (r) => this.onRateLimit(r),
          onUsageSource: (q) => this.refreshUsage(q),
          // a stopped turn's whole process tree is ended before its worktree is handed on
          onProcess: (child) => {
            entry.child = child;
            entry.spawnedAt = Date.now();
          },
          onSession: (id) => {
            if (this.fm.store.data.sessions[job.sessionKey]?.sessionId !== id) this.recordSession(job.sessionKey, id, model, undefined, engine.id);
          },
          onModel: (m) => this.reportModel(agentId, engine.id, m),
        });
      } finally {
        clearTimeout(timer);
      }
      if (stats.sessionId) this.recordSession(job.sessionKey, stats.sessionId, model, stats, engine.id);
      if (stats.authFailed) this.markAuthFailed(engine.authFailedMessage(stats.authFailed));
    } catch (e) {
      const aborted = abort.signal.aborted;
      if (!aborted) {
        // an exception can quote a secret (a spawn error names the environment value it refused)
        const msg = this.fm.redact((e as Error).message ?? String(e));
        this.fm.log.error(`${agentId} ${job.kind} failed: ${msg}`);
        this.fm.agentLog(agentId, 'error', `session error: ${truncate(msg, 400)}`);
        const engine = this.engineFor(agentId);
        const c = engine.classifyError?.(msg) ?? { auth: /auth|login|credential|401/i.test(msg) };
        if (c.auth) this.markAuthFailed(engine.authFailedMessage(truncate(msg, 160)));
        stats = { isError: true, errors: [msg] };
        // a session the turn started before it failed: kept, so a retry resumes it
        const partial = (e as { stats?: TurnStats }).stats;
        if (partial?.sessionId) stats.sessionId = partial.sessionId;
        if (c.limited) {
          stats.limited = true;
          if (c.limited.resetsAt) stats.rateLimit = { status: 'rejected', resetsAt: c.limited.resetsAt };
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

  protected recordSession(key: string, sessionId: string, model: string, stats?: TurnStats, engine: EngineId = 'claude'): void {
    const s = (this.fm.store.data.sessions[key] ??= { turns: 0, costUsd: 0, updatedAt: Date.now() });
    s.engine = engine;
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

  /**
   * A worker on a contributor's pull request (PR intake) runs code the user did not write: its
   * environment keeps no credential-like variables (tokens, keys, secrets, passwords, the ssh agent)
   * except what its engine needs to sign in (Claude: the API key / login token, a cloud provider's
   * credentials when one is switched on; Codex: OPENAI_* / CODEX_*).
   */
  protected contributorEnv(agentId: string, role: Role, env: Record<string, string | undefined>, engine: Engine): Record<string, string | undefined> {
    if (role !== 'worker' || !this.runsContributorCode(agentId)) return env;
    const on = (k: string) => !!env[k] && env[k] !== '0' && env[k]!.toLowerCase() !== 'false';
    // the credentials of the cloud provider the Claude CLI is switched to, and only those
    const families = [
      ...(on('CLAUDE_CODE_USE_BEDROCK') || on('CLAUDE_CODE_USE_ANTHROPIC_AWS') ? ['AWS_'] : []),
      ...(on('CLAUDE_CODE_USE_VERTEX') ? ['GOOGLE_', 'GCLOUD_', 'CLOUDSDK_'] : []),
      ...(on('CLAUDE_CODE_USE_FOUNDRY') ? ['AZURE_', 'ANTHROPIC_FOUNDRY_'] : []),
    ];

    const keep = (k: string) =>
      engine.id === 'claude'
        ? [...API_KEY_VARS, ...CLAUDE_LOGIN_VARS].includes(k.toUpperCase()) || families.some((f) => k.toUpperCase().startsWith(f))
        : /^(OPENAI_|CODEX_)/i.test(k);
    return Object.fromEntries(Object.entries(env).filter(([k]) => keep(k) || !credentialLikeName(k)));
  }

  /** A turn reported the model it really runs: the agent's nameplate shows it. */
  protected reportModel(agentId: string, engine: EngineId, model: string): void {
    const label = modelLabel(model);
    if (!label) return;
    this.reportedModels.set(agentId, { engine, label });
    if (this.fm.agent(agentId)) this.fm.setAgent(agentId, { engine, model: label });
  }

  /**
   * Where a sandboxed worker (Codex) may write besides its worktree: exactly what committing and
   * merging on its own branch needs, and the temp dir (scratch files and test runs; the policy
   * allows it too). Never the shared git dir as a whole: its config and hooks would let a worker
   * run code in the user's own git, outside every sandbox, and its refs would let it move the
   * user's branches (upstream 0f04d91).
   *  - objects/                        new commits, trees, blobs (a trust grant: the sandbox cannot make it append-only, so a worker could also corrupt or delete objects; docs/FORK.md)
   *  - refs/heads/<branch dir>/        this agent's branches only (agentcraft/<agent>/...)
   *  - logs/refs/heads/<branch dir>/   their reflogs
   *  - the worktree's own git dir      its HEAD, index, ORIG_HEAD, MERGE_HEAD
   * Claude agents need none of this (their sandbox is the policy); computed for every engine that
   * sandboxes (Codex).
   */
  protected async writableRoots(role: Role, job: Job, engine: Engine = this.engineFor(job.agentId)): Promise<string[]> {
    if (engine.id === 'claude' || role !== 'worker' || !job.taskId) return [];
    const t = this.fm.tasks.require(job.taskId);
    const repo = this.fm.repos.require(t.repoId!);
    const worktree = this.fm.repos.requireWorktree(repo.id, t.worktree!);
    const verified = await this.fm.repos.verifyWorktreeGit(repo, worktree);
    if (!verified.ok) throw new Error(`Cannot grant worktree Git access: ${verified.reason}`);
    const roots = [path.join(verified.commonDir, 'objects'), verified.gitDir, os.tmpdir()];
    const branchDir = path.posix.dirname(worktree.branch);
    if (branchDir !== '.' && !branchDir.split('/').includes('..')) {
      for (const d of [path.join(verified.commonDir, 'refs', 'heads', ...branchDir.split('/')), path.join(verified.commonDir, 'logs', 'refs', 'heads', ...branchDir.split('/'))]) {
        fs.mkdirSync(d, { recursive: true });
        roots.push(d);
      }
    }
    return [...new Set(roots)];
  }
}
