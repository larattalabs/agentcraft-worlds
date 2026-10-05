// Claude backend: holds on new turns. The startup auth probe (auth failed: until a restart;
// unreachable: retried with backoff), usage limits and warnings reported by running turns, and the
// usage reserve, published as foreman.status.hold.
import { truncate } from '../../util/text.js';
import { detectApiAuth, NO_API_AUTH_MESSAGE } from './auth.js';
import { pruneUsage, readPlanUsage, reserveHold, usageLine, withWindow } from './usage.js';
import { probeFailure } from './failures.js';
import type { ForemanHold } from '../../protocol.js';
import { type RateLimitReport, type TurnStats } from './stream.js';
import { BackendCore } from './core.js';
import { type Job, clock, LIMIT_BACKOFF_MAX_MS, THROTTLE_DEFAULT_MS, USAGE_REFRESH_MS, AUTH_RETRY_MS, AUTH_RETRY_MAX_MS } from './core.js';

export abstract class HoldsLayer extends BackendCore {
  async checkAuth(): Promise<boolean> {
    if (this.opts.skipAuthCheck) {
      this.fm.setStatus({ auth: 'ok', message: `Claude (lead ${this.cfg.leadModel}, workers ${this.cfg.workerModel})` });
      return true;
    }
    // API authentication by default; the claude.ai login only when explicitly opted into
    const api = detectApiAuth(process.env);
    if (!this.cfg.useClaudeLogin && !api.ok) {
      this.markAuthFailed(NO_API_AUTH_MESSAGE);
      return false;
    }
    this.fm.setStatus({ auth: 'checking', message: this.cfg.useClaudeLogin ? 'Checking Claude login...' : 'Checking Claude API access...' });
    async function* never(): AsyncGenerator<never> {
      await new Promise(() => undefined);
    }
    const q = this.queryFn({ prompt: never(), options: { settingSources: [], persistSession: false, permissionMode: 'default', env: this.env() } });
    try {
      const info = await Promise.race([q.accountInfo(), new Promise<never>((_, r) => setTimeout(() => r(new Error('timed out after 45s')), 45_000))]);
      const ok = !!(info.email || info.organization || (info.apiKeySource && info.apiKeySource !== 'none') || (info.tokenSource && info.tokenSource !== 'none') || (info.apiProvider && info.apiProvider !== 'firstParty'));
      if (!ok) throw new Error('not logged in');
      const account = this.cfg.useClaudeLogin
        ? [info.organization, info.subscriptionType].filter(Boolean).join(' · ') || info.apiProvider || 'ok'
        : [api.ok ? api.source : 'API', info.organization].filter(Boolean).join(' · ');
      this.authFailed = false;
      this.fm.setStatus({ auth: 'ok', account, message: `Claude (lead ${this.cfg.leadModel}, workers ${this.cfg.workerModel})` });
      this.fm.log.info(`claude auth ok (${account})`);
      if (this.offline) {
        this.offline = undefined;
        this.fm.bus.feed('system', 'Claude is reachable again: the team picks up where it stopped');
      }
      this.refreshHold();
      return true;
    } catch (e) {
      const why = (e as Error).message ?? String(e);
      if (probeFailure(why) === 'retry') {
        // the network, a sleeping machine, an API outage: not a bad login. Retry with backoff.
        this.goOffline(why);
        return false;
      }
      this.markAuthFailed(
        this.cfg.useClaudeLogin
          ? `Claude login check failed: ${why}. Run \`claude\` and /login, then restart the Foreman. The sim backend still works.`
          : `Claude API check failed: ${why}. Check ANTHROPIC_API_KEY (or your cloud provider settings), then restart the Foreman. The sim backend still works.`,
      );
      return false;
    } finally {
      try {
        q.close();
      } catch {
        /* ignore */
      }
    }
  }

  protected markAuthFailed(message: string): void {
    this.authFailed = true;
    this.authMessage = message;
    this.offline = undefined;
    if (this.authRetryTimer) clearTimeout(this.authRetryTimer);
    this.authRetryTimer = undefined;
    this.fm.setStatus({ auth: 'failed', message });
    this.fm.log.error(message);
    this.fm.bus.feed('error', message);
    this.fm.notify('warn', message);
    this.fm.notifyExternal('auth', message);
    if (process.stdout.isTTY) process.stdout.write('\x07');
    this.refreshHold();
  }

  /** The auth probe could not reach Claude: hold new turns and probe again later (backoff). */
  protected goOffline(why: string): void {
    const delayMs = this.offline ? Math.min(AUTH_RETRY_MAX_MS, this.offline.delayMs * 2) : (this.opts.authRetryMs ?? AUTH_RETRY_MS);
    const first = !this.offline;
    const retryAt = Date.now() + delayMs;
    this.offline = { delayMs, retryAt, message: `Claude could not be reached (${truncate(why, 120)}); trying again at ${clock(retryAt)}. Work waits meanwhile.` };
    this.fm.setStatus({ auth: 'checking', message: this.offline.message });
    if (first) {
      this.fm.log.warn(this.offline.message);
      this.fm.bus.feed('error', this.offline.message);
    } else this.fm.log.info(`claude still unreachable (${truncate(why, 120)}); next try ${clock(retryAt)}`);
    this.refreshHold();
    if (this.authRetryTimer) clearTimeout(this.authRetryTimer);
    this.authRetryTimer = setTimeout(() => {
      this.authRetryTimer = undefined;
      if (this.stopping) return;
      void this.checkAuth().then((ok) => {
        if (ok) this.tick();
      });
    }, delayMs);
    this.authRetryTimer.unref?.();
  }

  /** New agent turns wait: auth failed, offline, a usage limit, or usage above the reserve. */
  protected override held(now = Date.now()): boolean {
    return this.authFailed || !!this.offline || this.limited(now) || !!this.reserved(now);
  }

  /** claude.usageReserve: the window above its reserve, while it lasts. */
  protected reserved(now = Date.now()): ReturnType<typeof reserveHold> {
    return reserveHold(this.fm.status.usage, this.cfg.usageReserve, now);
  }

  /** What holds new turns right now (C9 foreman.status.hold), most important first. */
  protected currentHold(now = Date.now()): ForemanHold | undefined {
    if (this.authFailed) return { reason: 'auth', message: this.authMessage || 'Claude authentication failed' };
    if (this.offline) return { reason: 'offline', until: this.offline.retryAt, message: this.offline.message };
    const l = this.st.limit;
    if (l && now < l.until) return { reason: 'usage', until: l.until, message: `Usage limit reached${l.type ? ` (${l.type.replace(/_/g, ' ')})` : ''}: agents wait until ${clock(l.until)}, then resume` };
    const r = this.reserved(now);
    if (r) return { reason: 'usage', until: r.until, message: `Usage ${r.window.label} at ${r.window.pct}% (reserve ${r.limit}%): no new agent turns until ${clock(r.until)}, so some is left for you` };
    return undefined;
  }

  /**
   * Publish the hold (foreman.status.hold) and react to it changing: the reserve is announced once
   * when it starts holding; when nothing holds any more, queued work starts.
   */
  protected refreshHold(now = Date.now()): void {
    const hold = this.currentHold(now);
    const before = this.fm.status.hold;
    const reserve = !!this.reserved(now) && !this.limited(now) && !this.authFailed && !this.offline;
    if (reserve && !this.reserveActive && hold) {
      this.fm.bus.feed('system', `${hold.message}.`);
      this.fm.notify('warn', hold.message);
      this.fm.notifyExternal('usage', hold.message);
    }
    this.reserveActive = reserve;
    if (JSON.stringify(hold) === JSON.stringify(before)) return;
    this.fm.setStatus({ hold });
    if (before && !hold && !this.stopping) {
      for (const id of this.queues.keys()) this.pump(id);
      this.tick();
    }
  }

  /** A usage limit is in force: no turn starts (queued jobs wait, then resume). */
  protected override limited(now = Date.now()): boolean {
    return !!this.st.limit && now < this.st.limit.until;
  }

  /** Workers allowed at once: fewer while the plan reports a usage warning. */
  protected override maxWorkers(now = Date.now()): number {
    const t = this.st.throttle;
    return t && now < t.until ? Math.min(this.cfg.maxConcurrent, this.cfg.throttleConcurrent) : this.cfg.maxConcurrent;
  }

  protected baseStatusMessage(): string {
    return `Claude (lead ${this.cfg.leadModel}, workers ${this.cfg.workerModel})`;
  }

  /** A live usage report from a running turn. */
  protected onRateLimit(r: RateLimitReport): void {
    if (r.type && typeof r.utilization === 'number') this.setUsage(withWindow(pruneUsage(this.fm.status.usage), r.type, r.utilization * 100, r.resetsAt));
    else if (r.type && r.status === 'rejected') this.setUsage(withWindow(pruneUsage(this.fm.status.usage), r.type, 100, r.resetsAt));
    if (r.status === 'rejected') this.setLimit(r.resetsAt, r.type);
    else if (r.status === 'allowed_warning') {
      const until = r.resetsAt ?? Date.now() + THROTTLE_DEFAULT_MS;
      const prev = this.st.throttle;
      if (prev && prev.until >= until) return;
      this.st.throttle = { until, ...(r.type ? { type: r.type } : {}) };
      this.fm.store.markDirty();
      if (this.maxWorkers() < this.cfg.maxConcurrent) {
        const pct = r.utilization !== undefined ? ` (${Math.round(r.utilization * 100)}%)` : '';
        this.fm.bus.feed('system', `Usage warning${r.type ? ` (${r.type.replace(/_/g, ' ')})` : ''}${pct}: ${this.maxWorkers()} worker(s) at a time until ${clock(until)}`);
      }
      this.armLimitTimer();
    }
  }

  protected setUsage(u: ReturnType<typeof pruneUsage>): void {
    if (!u) return;
    const before = usageLine(this.fm.status.usage);
    this.fm.setStatus({ usage: u });
    const now = usageLine(u);
    if (now !== before) this.fm.log.info(`plan usage: ${now}`);
    this.refreshHold();
  }

  /** At most every few minutes, while some agent has a live session: the CLI's /usage windows. */
  protected refreshUsage(q: object): void {
    if (!this.cfg.useClaudeLogin || Date.now() - this.lastUsageRead < USAGE_REFRESH_MS) return;
    this.lastUsageRead = Date.now();
    void readPlanUsage(q, pruneUsage(this.fm.status.usage)).then((u) => this.setUsage(u));
  }

  /** Stop starting turns until the limit resets (or a backoff when it did not say when). */
  protected override setLimit(resetsAt: number | undefined, type: string | undefined): void {
    const until = resetsAt ?? Date.now() + this.limitBackoffMs;
    if (!resetsAt) this.limitBackoffMs = Math.min(LIMIT_BACKOFF_MAX_MS, this.limitBackoffMs * 2);
    const prev = this.st.limit;
    if (prev && prev.until >= until) return;
    this.st.limit = { until, ...(type ? { type } : {}) };
    this.fm.store.markDirty();
    const what = `Usage limit reached${type ? ` (${type.replace(/_/g, ' ')})` : ''}`;
    this.fm.setStatus({ message: `${what}: agents wait until ${clock(until)}, then resume` });
    if (!prev || Date.now() >= prev.until) {
      this.fm.bus.feed('error', `${what}. Nobody starts a new turn until ${clock(until)}; interrupted work resumes then.`);
      this.fm.notify('warn', `${what}: AgentCraft resumes at ${clock(until)}`);
      this.fm.notifyExternal('usage', `${what}: agents resume at ${clock(until)}`);
    }
    this.armLimitTimer();
    this.refreshHold();
  }

  /** Wake up when the earliest limit/throttle window ends. */
  protected armLimitTimer(): void {
    if (this.limitTimer) clearTimeout(this.limitTimer);
    this.limitTimer = undefined;
    const now = Date.now();
    const ends = [this.st.limit?.until, this.st.throttle?.until].filter((t): t is number => typeof t === 'number' && t > now);
    if (!ends.length) {
      this.liftExpired();
      return;
    }
    // setTimeout caps at ~24.8 days; re-arm in steps
    const delay = Math.min(Math.min(...ends) - now + 1000, 2 ** 31 - 1);
    this.limitTimer = setTimeout(() => {
      this.limitTimer = undefined;
      this.liftExpired();
      this.armLimitTimer();
    }, delay);
    this.limitTimer.unref?.();
  }

  protected liftExpired(): void {
    const now = Date.now();
    let changed = false;
    if (this.st.limit && now >= this.st.limit.until) {
      delete this.st.limit;
      changed = true;
      this.fm.setStatus({ message: this.baseStatusMessage() });
      this.fm.bus.feed('system', 'Usage limit reset: the team picks up where it stopped');
      for (const id of this.queues.keys()) this.pump(id);
    }
    if (this.st.throttle && now >= this.st.throttle.until) {
      delete this.st.throttle;
      changed = true;
    }
    if (changed) {
      this.fm.store.markDirty();
      this.tick();
    }
    this.refreshHold(now);
  }

  /**
   * A turn ended because of the usage limit: keep its task where it is and run the same job again
   * (resuming its session) once the limit resets, instead of marking the task failed/blocked.
   */
  protected holdForLimit(job: Job, stats: TurnStats): void {
    if (!this.limited()) this.setLimit(stats.rateLimit?.resetsAt, stats.rateLimit?.type);
    const hasSession = !!this.fm.store.data.sessions[job.sessionKey]?.sessionId;
    const next: Job = hasSession
      ? { ...job, fresh: false, resumed: true, prompt: 'A usage limit stopped your last turn; it has reset now. Re-check where you were (your worktree, the task board) and continue your current job.' }
      : job;
    const until = this.st.limit?.until;
    this.fm.setAgent(job.agentId, { state: 'blocked', activity: `usage limit - resumes ${until ? clock(until) : 'later'}` });
    this.fm.agentLog(job.agentId, 'error', `usage limit: this ${job.kind} resumes when it resets${until ? ` (${clock(until)})` : ''}`);
    const q = this.queues.get(job.agentId) ?? [];
    q.unshift(next);
    this.queues.set(job.agentId, q);
  }
}
