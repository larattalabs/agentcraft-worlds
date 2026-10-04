// Watching pull requests to completion (docs/PRWATCH.md).
//
// Tasks landed as a pull request sit in status `pr`. Every claude.prPollSeconds the watcher reads
// each one's PR from its host (prs.ts: az / gh) and
//  - keeps Task.pr up to date (status, checks, open / new threads);
//  - merged -> the task is done; abandoned -> cancelled;
//  - new comment threads (a human's, or the newest automated "Claude Code Review"), and checks that
//    turn failing, become triage items for the lead (a `triage` turn in the goal's session, tool
//    `triage`). The lead's verdicts: fold_in (one follow-up per PR for the task's worker, landing
//    as an added commit), reply (a drafted reply), ask_user (a question for the user), ignore.
//  - replies and thread resolutions are posted only after the user approves ONE decision per PR;
//    threads fixed by a fold-in are resolved after its push lands.
//
// Modes (claude.prWatch): "off" = landing a PR finishes the task (nothing is watched); "observe"
// (default) = poll and triage, but post nothing and start no fold-in (the verdicts go to the feed and
// a shared memory note); "on" = everything above.
//
// What is remembered per PR (state.json backend.prwatch): the comments seen per thread, the newest
// automated review seen, the comments AgentCraft posted itself (they come back under the user's
// name), the last check state, the automated-review fold-in rounds, poll failures/backoff.
import type { Foreman } from './foreman.js';
import type { Decision, Task, TaskPr } from './protocol.js';
import { BUILD_SERVICE, CHANGELOG_MARKER, findingCounts, isAutomatedReview, parseAutomatedReview, SEVERITIES, type FindingSeverity, type ParsedReview, type ReviewFinding } from './prreview.js';
import { parsePrUrl, readPr, replyToThread, setThreadStatus, type HostComment, type HostPr, type HostThread, type PrRef, type RunFn } from './prs.js';
import { run } from './util/proc.js';
import { truncate } from './util/text.js';
import { userName } from './user.js';

export type PrWatchMode = 'off' | 'observe' | 'on';
export type Verdict = 'fold_in' | 'reply' | 'ask_user' | 'ignore';

export const DEFAULT_AUTO_SEVERITIES: FindingSeverity[] = ['critical', 'important'];
export const DEFAULT_MAX_ROUNDS = 2;
const MAX_BACKOFF_MS = 60 * 60_000;

export interface TriageItem {
  /** unique across PRs: "t3/thread-12", "t3/review-11.2", "t3/checks" */
  ref: string;
  kind: 'thread' | 'finding' | 'checks';
  threadId?: string;
  severity?: FindingSeverity;
  file?: string;
  line?: number;
  author?: string;
  /** the new comment(s) / the finding / the failing checks */
  text: string;
  /** earlier comments of the thread, for context */
  conversation?: string;
  /** the default verdict (repoSettings.prReview.autoSeverities); the lead may differ */
  suggested?: Verdict;
}

export interface TriageVerdict {
  ref: string;
  verdict: Verdict;
  note: string;
}

/** A write to the host, listed in the user's decision first. */
interface PostOp {
  threadId: string;
  /** reply text; "{sha}" is replaced by the fold-in's pushed commit */
  reply?: string;
  status?: 'fixed' | 'closed';
  /** GitHub: where a reply goes */
  gh?: HostThread['gh'];
  replyTo?: number;
  /** for the decision text */
  label: string;
}

interface PrDecision {
  taskId: string;
  kind: 'post' | 'ask' | 'guard';
  /** post: written now; afterLanding: once the fold-in `gen` (carrying those fixes) landed */
  now?: PostOp[];
  afterLanding?: PostOp[];
  gen?: number;
  /** ask / guard: what a fold-in would address */
  notes?: string;
  review?: boolean;
}

export interface PrWatchState {
  /** thread id -> human comments seen (comments AgentCraft posted are not counted) */
  seen: Record<string, number>;
  /** "<threadId>:<commentId>" of comments AgentCraft posted */
  ours: string[];
  /** id of the newest automated review thread already handled */
  review?: string;
  /** fold-in rounds started from automated reviews (loop guard) */
  reviewRounds: number;
  checks?: TaskPr['checks'];
  /** the lead's verdicts are pending for these items */
  triage?: { items: TriageItem[]; at: number; attempts: number; stale?: boolean };
  /** a fold-in is running (started by the watcher): its generation and notes */
  foldIn?: { gen: number; notes: string; review: boolean; at: number };
  /** fold-ins started so far; queued notes go into generation gen + 1 */
  gen: number;
  /** how each fold-in generation ended */
  outcomes: Record<string, { outcome: 'landed' | 'empty' | 'rejected'; sha?: string }>;
  /** fold-in notes that wait (another fold-in runs, or the PR branch has foreign commits) */
  queued: string[];
  /** approved writes that wait for the push of fold-in `gen` */
  pendingResolve: Array<{ gen: number; ops: PostOp[] }>;
  /** ADO repository GUID */
  repoId?: string;
  /** the PR branch's tip on the host when it differs from what AgentCraft pushed */
  foreignHead?: string;
  foreignCandidate?: string;
  failures: number;
  nextPollAt?: number;
  lastError?: string;
}

interface Persisted {
  prs: Record<string, PrWatchState>;
  decisions: Record<string, PrDecision>;
}

export interface PrWatchHooks {
  /** run a triage turn for the lead (the goal's session) */
  triage(task: Task, items: TriageItem[]): void;
  /** send the task back to its worker as a fold-in; false if that is not possible now */
  foldIn(task: Task, notes: string): boolean;
  /** the PR was merged and the task is done */
  merged?(task: Task): void;
  /** a triage turn for this task is queued or running (after a restart: resumed by the backend) */
  triaging?(taskId: string): boolean;
}

export interface PrWatchOptions {
  mode: PrWatchMode;
  pollSeconds: number;
  runFn?: RunFn;
  hooks: PrWatchHooks;
}

/** The threads of a PR sorted for triage. */
export interface Classified {
  /** unresolved threads (system, changelog and other bot threads not counted) */
  open: number;
  /** active threads with human comments not seen yet */
  human: Array<{ thread: HostThread; fresh: HostComment[]; earlier: HostComment[] }>;
  /** the newest automated review */
  review?: { thread: HostThread; parsed: ParsedReview };
  /** system / changelog / informational bot threads */
  ignored: number;
}

const TFS = /^Microsoft\.VisualStudio\.Services\.TFS$/i;

/** Sort a PR's threads: what is new from humans, the newest automated review, what to ignore. */
export function classify(host: HostPr, seen: Record<string, number>, ours: Set<string>): Classified {
  const out: Classified = { open: 0, human: [], ignored: 0 };
  let newest: { thread: HostThread; at: number; n: number } | undefined;
  for (const t of host.threads) {
    const first = t.comments[0];
    if (!first || t.comments.every((c) => c.system) || TFS.test(first.author)) {
      out.ignored++;
      continue;
    }
    if (isAutomatedReview(first.text)) {
      if (t.active) out.open++;
      const atMs = first.at ?? 0;
      const n = Number(t.id.replace(/\D/g, '')) || 0;
      if (!newest || atMs > newest.at || (atMs === newest.at && n > newest.n)) newest = { thread: t, at: atMs, n };
      continue;
    }
    if (first.text.includes(CHANGELOG_MARKER) || BUILD_SERVICE.test(first.author)) {
      out.ignored++;
      continue;
    }
    if (t.active) out.open++;
    const human = t.comments.filter((c) => !c.system && !ours.has(`${t.id}:${c.id}`));
    const known = seen[t.id] ?? 0;
    if (t.active && human.length > known) out.human.push({ thread: t, fresh: human.slice(known), earlier: human.slice(0, known) });
  }
  if (newest) {
    const parsed = parseAutomatedReview(newest.thread.comments[0]!.text);
    if (parsed) out.review = { thread: newest.thread, parsed };
  }
  return out;
}

const where = (f: { file?: string; line?: number }) => (f.file ? `${f.file}${f.line ? `:${f.line}` : ''}` : '');
const quote = (cs: HostComment[]) => cs.map((c) => `${c.author}: ${truncate(c.text.replace(/\s+/g, ' ').trim(), 800)}`).join('\n');

export class PrWatcher {
  private timer: NodeJS.Timeout | undefined;
  private busy = new Set<string>();
  private readonly runFn: RunFn;

  constructor(
    private fm: Foreman,
    private opts: PrWatchOptions,
  ) {
    this.runFn = opts.runFn ?? run;
  }

  get mode(): PrWatchMode {
    return this.opts.mode;
  }

  /** Is anything watched (landing a PR leaves the task in `pr`)? */
  get active(): boolean {
    return this.opts.mode !== 'off';
  }

  private get data(): Persisted {
    const b = this.fm.store.data.backend;
    const p = (b.prwatch ??= { prs: {}, decisions: {} }) as Persisted;
    p.prs ??= {};
    p.decisions ??= {};
    return p;
  }

  state(taskId: string): PrWatchState {
    const s = (this.data.prs[taskId] ??= { seen: {}, ours: [], reviewRounds: 0, gen: 0, outcomes: {}, queued: [], pendingResolve: [], failures: 0 });
    s.seen ??= {};
    s.gen ??= 0;
    s.outcomes ??= {};
    s.ours ??= [];
    s.queued ??= [];
    s.pendingResolve ??= [];
    s.reviewRounds ??= 0;
    s.failures ??= 0;
    return s;
  }

  /** Does this decision belong to the watcher (its answer is handled here, not by an agent)? */
  owns(decisionId: string): boolean {
    return !!this.data.decisions[decisionId];
  }

  start(): void {
    if (!this.active) {
      // nothing watches them any more: as before PR watching, a landed PR finishes its task
      for (const t of this.fm.tasks.list().filter((x) => x.status === 'pr')) {
        this.fm.tasks.setStatus(t.id, 'done', { viaMerge: true, force: true });
        this.fm.bus.feed('merge', `${t.id}: PR #${t.pr?.id ?? '?'} is no longer watched (claude.prWatch "off"): marked done`, { taskId: t.id });
      }
      return;
    }
    // triage turns that were waiting when the Foreman stopped: offered again on the next poll
    for (const [taskId, s] of Object.entries(this.data.prs)) if (s.triage && !this.opts.hooks.triaging?.(taskId)) s.triage.stale = true;
    this.stop();
    this.timer = setInterval(() => void this.pollDue(), Math.max(5, this.opts.pollSeconds) * 1000);
    this.timer.unref?.();
    void this.pollDue();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  /** config.set changed claude.prWatch / prPollSeconds: switch over now (as start() would after a restart). */
  configure(mode: PrWatchMode, pollSeconds: number): void {
    if (mode === this.opts.mode && pollSeconds === this.opts.pollSeconds) return;
    this.opts.mode = mode;
    this.opts.pollSeconds = pollSeconds;
    this.stop();
    this.start();
  }

  /** Tasks whose PR is watched now. */
  watched(): Task[] {
    return this.fm.tasks.list().filter((t) => t.status === 'pr' && t.pr && parsePrUrl(t.pr.url));
  }

  /** Poll every watched PR that is due (errors back off per PR; nothing here ever throws). */
  async pollDue(now = Date.now()): Promise<void> {
    for (const t of this.watched()) {
      const s = this.state(t.id);
      if (s.nextPollAt && s.nextPollAt > now) continue;
      await this.poll(t.id);
    }
  }

  /** pr.refresh: poll now (one task, or every watched one). */
  refresh(taskId?: string): void {
    const ts = taskId ? this.watched().filter((t) => t.id === taskId) : this.watched();
    for (const t of ts) void this.poll(t.id);
  }

  /** Read one PR and act on what changed. Never throws. */
  async poll(taskId: string): Promise<void> {
    if (this.busy.has(taskId)) return;
    this.busy.add(taskId);
    try {
      await this.doPoll(taskId);
    } catch (e) {
      this.fm.log.error(`PR watch ${taskId}: ${(e as Error).stack ?? e}`);
    } finally {
      this.busy.delete(taskId);
    }
  }

  private async doPoll(taskId: string): Promise<void> {
    const t = this.fm.tasks.get(taskId);
    if (!t?.pr || t.status !== 'pr') return;
    const ref = parsePrUrl(t.pr.url);
    if (!ref) return;
    const s = this.state(t.id);
    const repo = t.repoId ? this.fm.repos.get(t.repoId) : undefined;
    let host: HostPr;
    try {
      host = await readPr(ref, this.runFn, repo?.path);
    } catch (e) {
      s.failures++;
      const wait = Math.min(MAX_BACKOFF_MS, this.opts.pollSeconds * 1000 * 2 ** Math.min(s.failures, 6));
      s.nextPollAt = Date.now() + wait;
      const msg = (e as Error).message;
      if (s.failures === 1 || msg !== s.lastError) {
        this.fm.bus.feed('error', `Could not read PR #${ref.id} (${t.id}): ${truncate(msg, 200)}. Retrying in ${Math.round(wait / 60_000) || 1} min.`, { taskId: t.id });
      }
      this.fm.log.warn(`PR #${ref.id} (${t.id}): ${msg}`);
      s.lastError = msg;
      this.fm.store.markDirty();
      return;
    }
    if (s.failures) this.fm.log.info(`PR #${ref.id} (${t.id}) readable again`);
    s.failures = 0;
    delete s.lastError;
    s.nextPollAt = Date.now() + this.opts.pollSeconds * 1000;
    if (host.repoId) s.repoId = host.repoId;
    // a task may have changed while the host answered (cancelled, fold-in started)
    const cur = this.fm.tasks.get(taskId);
    if (!cur || cur.status !== 'pr' || !cur.pr) return;

    if (host.status === 'merged') return this.merged(cur, ref);
    if (host.status === 'abandoned') return this.abandoned(cur, ref);

    // someone pushed to the PR branch (e.g. applied a suggestion in the web UI): a fold-in would
    // not be able to push on top of it
    const pushed = this.pushedSha(cur);
    const foreign = host.headSha && pushed && !host.headSha.startsWith(pushed) && !pushed.startsWith(host.headSha) ? host.headSha : undefined;
    // (twice in a row: right after a push the host may still report the previous tip)
    if (!foreign) {
      delete s.foreignHead;
      delete s.foreignCandidate;
    } else if (foreign === s.foreignCandidate && foreign !== s.foreignHead) {
      s.foreignHead = foreign;
      this.fm.bus.feed('merge', `PR #${ref.id} (${t.id}) has commits AgentCraft did not push (${foreign.slice(0, 7)}): review fixes cannot be added on top until you bring them into ${t.branch ?? 'the branch'}`, { taskId: t.id });
    } else s.foreignCandidate = foreign;

    // fold-in notes that could not start (foreign commits, task busy): try again now
    if (s.queued.length && !s.foldIn && !s.foreignHead) {
      const next = s.queued.splice(0);
      this.startFoldIn(cur, next.join('\n'), false);
      return;
    }

    const c = classify(host, s.seen, new Set(s.ours));
    const reviewNew = !!c.review && c.review.thread.id !== s.review;
    const checksTurnedFailing = host.checks === 'failing' && s.checks !== 'failing';
    const pending = !!s.triage && !s.triage.stale;
    const setPr = (fresh: number) => this.fm.tasks.update(cur.id, { pr: { ...cur.pr!, status: host.status, checks: host.checks, threads: { open: c.open, new: fresh }, updatedAt: this.fm.ctx.now() } });
    if (pending) {
      // the lead has not answered the previous triage yet: what is new now waits for the next poll
      setPr(Math.max(c.human.length + (reviewNew ? 1 : 0), new Set(s.triage!.items.map((i) => i.threadId ?? i.ref)).size));
      return;
    }

    const items: TriageItem[] = [];
    const settings = this.reviewSettings(cur);
    const lines: string[] = [];
    for (const h of c.human) {
      items.push({
        ref: `${t.id}/thread-${h.thread.id}`,
        kind: 'thread',
        threadId: h.thread.id,
        ...(h.thread.file ? { file: h.thread.file } : {}),
        ...(h.thread.line ? { line: h.thread.line } : {}),
        author: h.fresh[h.fresh.length - 1]!.author,
        text: quote(h.fresh),
        ...(h.earlier.length ? { conversation: quote(h.earlier) } : {}),
      });
    }
    if (c.human.length) lines.push(`${c.human.length} thread${c.human.length === 1 ? '' : 's'} with new comments`);
    // the newest automated review, once (older review threads are superseded)
    if (reviewNew) {
      const r = c.review!;
      const findings = r.parsed.findings.filter((f) => f.severity !== 'teachable');
      const serious = findings.filter((f) => f.severity === 'critical' || f.severity === 'important');
      const auto = findings.filter((f) => settings.autoSeverities.includes(f.severity));
      lines.push(`automated review ${r.parsed.verdict ?? '?'}${findings.length ? ` (${findingCounts(findings)})` : ''}`);
      if (!findings.length || (r.parsed.verdict === 'PASS' && !serious.length)) {
        // a PASS with nothing above minor ends the automated rounds
      } else if (s.reviewRounds >= settings.maxRounds || (s.reviewRounds > 0 && !auto.length)) {
        // loop guard: the user decides whether another round is worth it
        this.guard(cur, ref, r.thread, r.parsed, findings, s.reviewRounds >= settings.maxRounds ? `after ${s.reviewRounds} fold-in round${s.reviewRounds === 1 ? '' : 's'} from automated reviews` : 'this round would only address minor items');
      } else {
        findings.forEach((f, i) =>
          items.push({
            ref: `${t.id}/review-${r.thread.id}.${i + 1}`,
            kind: 'finding',
            threadId: r.thread.id,
            severity: f.severity,
            ...(f.file ? { file: f.file } : {}),
            ...(f.line ? { line: f.line } : {}),
            author: r.thread.comments[0]!.author,
            text: `${f.text}${f.confidence ? ` (reviewer confidence: ${f.confidence})` : ''}`,
            suggested: settings.autoSeverities.includes(f.severity) ? 'fold_in' : 'ignore',
          }),
        );
      }
    }
    if (checksTurnedFailing) {
      items.push({ ref: `${t.id}/checks`, kind: 'checks', text: `Failing checks: ${host.failing.join(', ') || '(unnamed)'}`, suggested: 'fold_in' });
      lines.push(`checks failing (${host.failing.join(', ') || '?'})`);
    }
    s.checks = host.checks;
    setPr(c.human.length + (items.some((i) => i.kind === 'finding') ? 1 : 0));
    this.markSeen(s, c, reviewNew);
    this.fm.store.markDirty();

    if (s.triage?.stale) {
      // a triage that never got its verdicts (a restart, a turn that ended without them): once more
      if (s.triage.attempts < 2) {
        const all = [...s.triage.items, ...items.filter((i) => !s.triage!.items.some((x) => x.ref === i.ref))];
        s.triage = { items: all, at: Date.now(), attempts: s.triage.attempts + 1 };
        this.opts.hooks.triage(cur, all);
        return;
      }
      this.fm.bus.feed('error', `PR #${ref.id} (${t.id}): ${this.fm.nameOf(this.leadOf(t))} did not triage ${s.triage.items.length} item(s) after ${s.triage.attempts} tries; look at the PR yourself`, { taskId: t.id });
      delete s.triage;
    }
    if (!items.length) {
      if (lines.length) this.fm.bus.feed('merge', `PR #${ref.id} (${t.id}): ${lines.join(', ')}`, { taskId: t.id });
      return;
    }
    s.triage = { items, at: Date.now(), attempts: 1 };
    this.fm.bus.feed('merge', `PR #${ref.id} (${t.id}): ${lines.join(', ')}. ${this.fm.nameOf(this.leadOf(t))} triages ${items.length} item${items.length === 1 ? '' : 's'}${this.opts.mode === 'observe' ? ' (observe mode)' : ''}`, { agentId: this.leadOf(t), taskId: t.id });
    this.opts.hooks.triage(cur, items);
  }

  private markSeen(s: PrWatchState, c: Classified, reviewNew: boolean): void {
    for (const h of c.human) s.seen[h.thread.id] = h.earlier.length + h.fresh.length;
    if (reviewNew && c.review) s.review = c.review.thread.id;
  }

  private reviewSettings(t: Task): { autoSeverities: FindingSeverity[]; maxRounds: number } {
    const p = t.repoId ? this.fm.repos.settingsFor(t.repoId).prReview : undefined;
    return { autoSeverities: p?.autoSeverities ?? DEFAULT_AUTO_SEVERITIES, maxRounds: p?.maxRounds ?? DEFAULT_MAX_ROUNDS };
  }

  private pushedSha(t: Task): string | undefined {
    if (!t.repoId) return undefined;
    const r = this.fm.repos.get(t.repoId);
    if (!r) return undefined;
    for (const w of [...r.worktrees].reverse()) {
      if (w.taskId !== t.id) continue;
      const m = this.fm.store.data.worktreeMeta[`${r.id}/${w.id}`];
      if (m?.prUrl === t.pr?.url && m?.prPushedSha) return m.prPushedSha;
    }
    return undefined;
  }

  private merged(t: Task, ref: PrRef): void {
    this.fm.tasks.update(t.id, { pr: { ...t.pr!, status: 'merged', threads: { ...t.pr!.threads, new: 0 }, updatedAt: this.fm.ctx.now() } });
    this.fm.tasks.setStatus(t.id, 'done', { viaMerge: true, force: true });
    this.cancelDecisions(t.id, `PR #${ref.id} was merged`);
    this.fm.bus.feed('merge', `PR #${ref.id} merged: ${t.id} "${truncate(t.title, 60)}" is done`, { agentId: this.leadOf(t), taskId: t.id });
    this.fm.notify('info', `PR #${ref.id} merged: ${t.id} is done`);
    try {
      this.opts.hooks.merged?.(this.fm.tasks.require(t.id));
    } catch (e) {
      this.fm.log.error(`merged hook: ${(e as Error).message}`);
    }
  }

  private abandoned(t: Task, ref: PrRef): void {
    this.fm.tasks.update(t.id, { pr: { ...t.pr!, status: 'abandoned', threads: { ...t.pr!.threads, new: 0 }, updatedAt: this.fm.ctx.now() } });
    this.fm.tasks.setStatus(t.id, 'cancelled', { force: true, summary: `PR #${ref.id} was abandoned on ${ref.host === 'ado' ? 'Azure DevOps' : 'GitHub'}.${t.summary ? `\n${t.summary}` : ''}` });
    for (const dep of this.fm.tasks.dependents(t.id)) {
      if (dep.status === 'todo') this.fm.tasks.setStatus(dep.id, 'blocked', { reason: `depends on ${t.id}, whose PR #${ref.id} was abandoned`, force: true });
    }
    this.cancelDecisions(t.id, `PR #${ref.id} was abandoned`);
    this.fm.bus.feed('merge', `PR #${ref.id} was abandoned: ${t.id} "${truncate(t.title, 60)}" is cancelled`, { agentId: this.leadOf(t), taskId: t.id });
    this.fm.notify('warn', `PR #${ref.id} was abandoned: ${t.id} cancelled`);
  }

  private cancelDecisions(taskId: string, why: string): void {
    for (const [id, pd] of Object.entries(this.data.decisions)) {
      if (pd.taskId !== taskId) continue;
      if (this.fm.decisions.get(id)?.status === 'open') this.fm.decisions.cancel(id, why);
      delete this.data.decisions[id];
    }
    const s = this.state(taskId);
    delete s.triage;
    s.queued = [];
    s.pendingResolve = [];
  }

  // ---- loop guard ---------------------------------------------------------------------------

  /** Another automated review round would start: the user decides instead of the lead. */
  private guard(t: Task, ref: PrRef, thread: HostThread, parsed: ParsedReview, findings: ReviewFinding[], why: string): void {
    const s = this.state(t.id);
    const notes = findings.map((f) => `- [${f.severity}] ${where(f) ? `${where(f)}: ` : ''}${f.text}`).join('\n');
    const question = `Review round ${s.reviewRounds + 1} on PR #${ref.id} (${t.id}) suggests ${findingCounts(findings)}; fold in?`;
    if (this.opts.mode !== 'on') {
      this.observeNote(t, ref, `${question}\n(${why}; observe mode: nothing asked, nothing started)\n\n${notes}`);
      return;
    }
    const d = this.fm.createDecision({ agentId: this.leadOf(t), kind: 'question', question, options: ['Fold in', 'Leave it'], textAllowed: false, context: `Automated review ${parsed.verdict ?? ''} (thread ${thread.id}); ${why}.\n${truncate(notes, 1500)}`, taskId: t.id });
    this.data.decisions[d.id] = { taskId: t.id, kind: 'guard', notes: `The automated review (round ${s.reviewRounds + 1}) found:\n${notes}`, review: true };
    this.fm.store.markDirty();
  }

  // ---- triage verdicts ----------------------------------------------------------------------

  /** The lead who triages a task's PR: the lead of its goal (its session), else of its repository's building. */
  private leadOf(t: Task | undefined): string {
    return this.fm.leadOfTask(t);
  }

  /** Items waiting for the lead's verdicts, for the triage prompt. */
  pendingItems(taskId: string): TriageItem[] {
    return this.state(taskId).triage?.items ?? [];
  }

  /** A triage turn ended: if no verdicts came, the items are offered again on the next poll. */
  triageTurnEnded(taskId: string): void {
    const s = this.state(taskId);
    if (!s.triage) return;
    s.triage.stale = true;
    this.fm.store.markDirty();
    const lead = this.leadOf(this.fm.tasks.get(taskId));
    this.fm.bus.feed('error', `${this.fm.nameOf(lead)}'s triage turn for ${taskId} ended without verdicts; it is offered again on the next poll`, { agentId: lead, taskId });
  }

  /**
   * The lead's verdicts (tool `triage`). Returns the text for the tool result. Refs must be among the
   * pending items; items without a verdict are left alone (threads) or ignored (findings, checks).
   */
  applyTriage(verdicts: TriageVerdict[]): { ok: boolean; text: string } {
    const byTask = new Map<string, TriageVerdict[]>();
    const unknown: string[] = [];
    for (const v of verdicts) {
      const taskId = v.ref.split('/')[0]!;
      if (!this.state(taskId).triage?.items.some((i) => i.ref === v.ref)) {
        unknown.push(v.ref);
        continue;
      }
      byTask.set(taskId, [...(byTask.get(taskId) ?? []), v]);
    }
    if (!byTask.size) {
      const open = Object.entries(this.data.prs).flatMap(([, s]) => s.triage?.items.map((i) => i.ref) ?? []);
      return { ok: false, text: `No pending triage item matches ${unknown.join(', ') || '(none given)'}. Pending: ${open.join(', ') || 'none'}.` };
    }
    const out: string[] = [];
    for (const [taskId, vs] of byTask) out.push(this.applyFor(taskId, vs));
    if (unknown.length) out.push(`Ignored unknown refs: ${unknown.join(', ')}.`);
    return { ok: true, text: out.join('\n') };
  }

  private applyFor(taskId: string, vs: TriageVerdict[]): string {
    const t = this.fm.tasks.require(taskId);
    const s = this.state(taskId);
    const items = s.triage!.items;
    delete s.triage;
    this.fm.store.markDirty();
    const ref = parsePrUrl(t.pr!.url)!;
    const v = new Map(vs.map((x) => [x.ref, x]));
    const verdictOf = (i: TriageItem): TriageVerdict | undefined => v.get(i.ref) ?? (i.kind === 'thread' ? undefined : { ref: i.ref, verdict: 'ignore', note: '' });

    const foldIns: string[] = [];
    const now: PostOp[] = [];
    const after: PostOp[] = [];
    const asks: Array<{ item: TriageItem; note: string }> = [];
    let reviewFold = false;
    const reviewReplies = new Map<string, { lines: string[]; fixed: string[] }>();
    const count = { fold_in: 0, reply: 0, ask_user: 0, ignore: 0 };
    for (const i of items) {
      const x = verdictOf(i);
      if (!x) continue;
      count[x.verdict]++;
      const loc = where(i) ? `${where(i)}: ` : '';
      if (x.verdict === 'fold_in') {
        foldIns.push(`- ${i.kind === 'finding' ? `[review, ${i.severity}] ` : i.kind === 'checks' ? '[checks] ' : `[${i.author ?? 'comment'}] `}${loc}${x.note || truncate(i.text, 400)}`);
        if (i.kind === 'finding') reviewFold = true;
      }
      if (x.verdict === 'ask_user') asks.push({ item: i, note: x.note });
      if (i.kind === 'thread' && i.threadId) {
        const th = { threadId: i.threadId, ...this.ghTarget(i) };
        if (x.verdict === 'reply' && x.note.trim()) now.push({ ...th, reply: x.note.trim(), label: `reply in thread ${i.threadId} (${i.author ?? '?'}${loc ? `, ${where(i)}` : ''}): "${truncate(x.note.trim(), 300)}"` });
        if (x.verdict === 'ignore') now.push({ ...th, status: 'closed', label: `resolve thread ${i.threadId} (${i.author ?? '?'}: "${truncate(i.text, 120)}") without a reply` });
        if (x.verdict === 'fold_in') after.push({ ...th, reply: `Addressed in {sha}: ${x.note.trim() || 'see the new commit'}`, status: 'fixed', label: `after the fix lands: reply "Addressed in <commit>: ${truncate(x.note.trim(), 200)}" and resolve thread ${i.threadId} as fixed` });
      }
      if (i.kind === 'finding' && i.threadId) {
        const r = reviewReplies.get(i.threadId) ?? { lines: [], fixed: [] };
        if (x.verdict === 'reply' && x.note.trim()) r.lines.push(`- ${loc}${truncate(i.text, 120)}\n  ${x.note.trim()}`);
        if (x.verdict === 'fold_in') r.fixed.push(`- ${loc}${truncate(x.note || i.text, 160)}`);
        reviewReplies.set(i.threadId, r);
      }
    }
    for (const [threadId, r] of reviewReplies) {
      if (r.lines.length) now.push({ threadId, reply: `Notes on the automated review:\n${r.lines.join('\n')}`, label: `reply to the automated review (thread ${threadId}) on ${r.lines.length} finding${r.lines.length === 1 ? '' : 's'}` });
      if (r.fixed.length) after.push({ threadId, reply: `Addressed in {sha}:\n${r.fixed.join('\n')}`, status: 'fixed', label: `after the fix lands: reply listing ${r.fixed.length} addressed finding${r.fixed.length === 1 ? '' : 's'} and resolve the review thread ${threadId} as fixed` });
      else now.push({ threadId, status: 'closed', label: `resolve the automated review thread ${threadId}` });
    }

    const summary = `${this.fm.nameOf(this.leadOf(t))} triaged PR #${ref.id} (${t.id}): ${(['fold_in', 'reply', 'ask_user', 'ignore'] as const).filter((k) => count[k]).map((k) => `${count[k]} ${k.replace('_', ' ')}`).join(', ') || 'nothing'}`;
    if (this.opts.mode !== 'on') {
      const body = [
        `Mode observe: nothing was posted and no fold-in started.`,
        foldIns.length ? `Fold-in that would go to ${this.fm.nameOf(t.assignee ?? '?')}:\n${foldIns.join('\n')}` : '',
        now.length || after.length ? `The decision "${this.postQuestion(ref, now, after)}" would list:\n${[...now, ...after].map((o) => `- ${o.label}`).join('\n')}` : '',
        asks.length ? `Questions for ${userName()}:\n${asks.map((a) => `- ${a.note || a.item.text}`).join('\n')}` : '',
        `Items:\n${items.map((i) => `- ${i.ref} [${i.kind}${i.severity ? ` ${i.severity}` : ''}] ${where(i)} ${truncate(i.text, 300)} -> ${verdictOf(i)?.verdict ?? '(no verdict)'}${verdictOf(i)?.note ? `: ${verdictOf(i)!.note}` : ''}`).join('\n')}`,
      ].filter(Boolean);
      this.observeNote(t, ref, body.join('\n\n'));
      this.fm.bus.feed('merge', `${summary} (observe mode: nothing posted, no fold-in)`, { agentId: this.leadOf(t), taskId: t.id });
      return `${summary}. Observe mode: recorded only (memory note), nothing posted and no fold-in started.`;
    }

    const did: string[] = [];
    let gen: number | undefined;
    if (foldIns.length) {
      const r = this.startFoldIn(t, foldIns.join('\n'), reviewFold);
      gen = r.gen;
      did.push(r.started ? `fold-in sent to ${this.fm.nameOf(this.fm.tasks.require(t.id).assignee ?? '?')}` : 'fold-in queued');
    }
    if (now.length || after.length) {
      const d = this.fm.createDecision({
        agentId: this.leadOf(t),
        kind: 'question',
        question: this.postQuestion(ref, now, after),
        options: ['Post', 'Skip'],
        textAllowed: false,
        context: truncate([...now, ...after].map((o) => `- ${o.label}`).join('\n'), 3500),
        taskId: t.id,
      });
      this.data.decisions[d.id] = { taskId: t.id, kind: 'post', now, afterLanding: after, ...(gen !== undefined ? { gen } : {}) };
      did.push(`decision ${d.id} for ${userName()} (replies/resolutions)`);
    }
    for (const a of asks) {
      const d = this.fm.createDecision({ agentId: this.leadOf(t), kind: 'question', question: `PR #${ref.id} (${t.id}): ${truncate(a.note || a.item.text, 200)}`, options: ['Fold in', 'Leave it'], textAllowed: false, context: `${a.item.author ?? ''}${where(a.item) ? ` on ${where(a.item)}` : ''}: ${truncate(a.item.text, 1200)}`, taskId: t.id });
      this.data.decisions[d.id] = { taskId: t.id, kind: 'ask', notes: `- [${a.item.author ?? 'comment'}] ${where(a.item) ? `${where(a.item)}: ` : ''}${a.item.text}${a.note ? `\n  ${this.fm.nameOf(this.leadOf(t))}: ${a.note}` : ''}`, review: a.item.kind === 'finding' };
      did.push(`question ${d.id}`);
    }
    this.fm.store.markDirty();
    this.fm.bus.feed('merge', `${summary}${did.length ? ` -> ${did.join('; ')}` : ''}`, { agentId: this.leadOf(t), taskId: t.id });
    return `${summary}.${did.length ? ` ${did.join('; ')}.` : ''}`;
  }

  private ghTarget(i: TriageItem): Pick<PostOp, 'gh' | 'replyTo'> {
    if (!i.threadId) return {};
    if (i.threadId.startsWith('rc')) return { gh: 'review-comment', replyTo: Number(i.threadId.slice(2)) };
    if (/^(ic|rv)/.test(i.threadId)) return { gh: 'issue' };
    return {};
  }

  private postQuestion(ref: PrRef, now: PostOp[], after: PostOp[]): string {
    const all = [...now, ...after];
    const n = all.filter((o) => o.reply).length;
    const m = all.filter((o) => o.status).length;
    const parts = [n ? `post ${n} repl${n === 1 ? 'y' : 'ies'}` : '', m ? `resolve ${m} thread${m === 1 ? '' : 's'}` : ''].filter(Boolean).join(' and ');
    return `${parts.charAt(0).toUpperCase()}${parts.slice(1)} on PR #${ref.id}?`;
  }

  private observeNote(t: Task, ref: PrRef, body: string): void {
    try {
      this.fm.memory.write({ scope: 'shared', title: `PR triage ${t.id} #${ref.id} (observe)`, body: `${t.pr?.url ?? ''}\n\n${body}`, author: this.leadOf(t), mode: 'append' });
    } catch (e) {
      this.fm.log.warn(`observe note: ${(e as Error).message}`);
    }
  }

  // ---- fold-ins -----------------------------------------------------------------------------

  /**
   * Start a fold-in, or queue its notes. `gen` = the fold-in generation that carries these notes
   * (queued notes go into the next one to start), so approved "fixed" replies wait for exactly it.
   */
  private startFoldIn(t: Task, notes: string, review: boolean): { started: boolean; gen: number } {
    const s = this.state(t.id);
    const queue = () => {
      s.queued.push(notes);
      this.fm.store.markDirty();
      return { started: false, gen: s.gen + 1 };
    };
    if (s.foldIn || t.status !== 'pr') return queue();
    if (s.foreignHead) {
      this.fm.bus.feed('error', `Not starting review fixes for ${t.id}: PR #${t.pr?.id} has commits AgentCraft did not push (${s.foreignHead.slice(0, 7)}). Bring them into ${t.branch ?? 'the task branch'} (the fixes start once AgentCraft's push is the PR's tip again) or address the comments yourself.`, { agentId: this.leadOf(t), taskId: t.id });
      this.fm.notify('warn', `${t.id}: review fixes not started (someone else pushed to PR #${t.pr?.id})`);
      return queue();
    }
    if (!this.opts.hooks.foldIn(t, notes)) return queue();
    s.gen++;
    s.foldIn = { gen: s.gen, notes, review, at: Date.now() };
    if (review) s.reviewRounds++;
    this.fm.store.markDirty();
    return { started: true, gen: s.gen };
  }

  /** The fold-in's push landed on the PR (sha), or it ended without landing (rejected, empty). */
  async foldInEnded(taskId: string, outcome: 'landed' | 'empty' | 'rejected', sha?: string): Promise<void> {
    const s = this.state(taskId);
    const gen = s.foldIn?.gen;
    delete s.foldIn;
    if (gen === undefined) return;
    s.outcomes[gen] = { outcome, ...(sha ? { sha } : {}) };
    const mine = s.pendingResolve.filter((p) => p.gen === gen).flatMap((p) => p.ops);
    s.pendingResolve = s.pendingResolve.filter((p) => p.gen !== gen);
    this.fm.store.markDirty();
    const t = this.fm.tasks.get(taskId);
    if (!t?.pr) return;
    if (mine.length) await this.afterFoldIn(t, gen, mine);
    const next = s.queued.splice(0);
    if (next.length && t.status === 'pr') this.startFoldIn(t, next.join('\n'), false);
  }

  /** Approved "fixed" replies/resolutions of fold-in `gen`: post them if it landed, else drop them. */
  private async afterFoldIn(t: Task, gen: number, ops: PostOp[]): Promise<void> {
    const o = this.state(t.id).outcomes[gen];
    if (o?.outcome === 'landed') await this.execute(t, ops, o.sha);
    else this.fm.bus.feed('merge', `${t.id}: the review fixes did not land (${o?.outcome ?? 'not started'}); ${ops.length} thread update${ops.length === 1 ? '' : 's'} not posted`, { agentId: this.leadOf(t), taskId: t.id });
  }

  // ---- the user's answers ------------------------------------------------------------------

  /** A watcher decision was answered or cancelled. Returns true if it was one. */
  async onDecision(d: Decision): Promise<boolean> {
    const pd = this.data.decisions[d.id];
    if (!pd) return false;
    delete this.data.decisions[d.id];
    this.fm.store.markDirty();
    const t = this.fm.tasks.get(pd.taskId);
    if (d.status !== 'answered' || !t?.pr) return true;
    const opt = d.answer?.option;
    if (pd.kind === 'post') {
      if (opt !== 'Post') {
        this.fm.bus.feed('merge', `Nothing posted on PR #${t.pr.id} (${userName()} skipped it)`, { agentId: 'user', taskId: t.id });
        return true;
      }
      if (pd.now?.length) await this.execute(t, pd.now);
      if (pd.afterLanding?.length) {
        // the replies that say "addressed" wait for the fold-in carrying those fixes
        const s = this.state(t.id);
        if (pd.gen !== undefined && s.outcomes[pd.gen]) await this.afterFoldIn(t, pd.gen, pd.afterLanding);
        else if (pd.gen !== undefined) s.pendingResolve.push({ gen: pd.gen, ops: pd.afterLanding });
        else this.fm.bus.feed('merge', `${t.id}: no fold-in carries the fixes; ${pd.afterLanding.length} thread update${pd.afterLanding.length === 1 ? '' : 's'} not posted`, { agentId: this.leadOf(t), taskId: t.id });
        this.fm.store.markDirty();
      }
      return true;
    }
    if (opt === 'Fold in') {
      const extra = d.answer?.text ? `\n${userName()} adds: ${d.answer.text}` : '';
      const r = this.startFoldIn(t, `${pd.notes ?? ''}${extra}`, !!pd.review);
      this.fm.bus.feed('merge', `${userName()}: fold in on PR #${t.pr.id}${r.started ? '' : ' (queued)'}`, { agentId: 'user', taskId: t.id });
    }
    return true;
  }

  /** Post replies / set statuses (one by one; a failure is reported and the rest still run). */
  private async execute(t: Task, ops: PostOp[], sha?: string): Promise<void> {
    const ref = parsePrUrl(t.pr!.url)!;
    const s = this.state(t.id);
    const repo = t.repoId ? this.fm.repos.get(t.repoId) : undefined;
    let replies = 0;
    let resolved = 0;
    const failed: string[] = [];
    for (const o of ops) {
      try {
        if (o.reply) {
          const text = o.reply.replace(/\{sha\}/g, sha ? sha.slice(0, 7) : 'the latest commit');
          const id = await replyToThread(ref, { id: o.threadId, ...(o.gh ? { gh: o.gh } : {}), ...(o.replyTo ? { replyTo: o.replyTo } : {}) }, text, s.repoId, this.runFn, repo?.path);
          if (id !== undefined) s.ours.push(ref.host === 'github' && o.gh !== 'review-comment' ? `ic${id}:${id}` : `${o.threadId}:${id}`);
          replies++;
        }
        if (o.status && (await setThreadStatus(ref, o.threadId, o.status, s.repoId, this.runFn, repo?.path))) resolved++;
      } catch (e) {
        failed.push(`${o.threadId}: ${(e as Error).message}`);
      }
      this.fm.store.markDirty();
    }
    this.fm.bus.feed('merge', `PR #${ref.id}: posted ${replies} repl${replies === 1 ? 'y' : 'ies'}, resolved ${resolved} thread${resolved === 1 ? '' : 's'}${failed.length ? `; ${failed.length} failed` : ''}`, { agentId: this.leadOf(t), taskId: t.id });
    if (failed.length) {
      this.fm.log.warn(`PR #${ref.id}: ${failed.join(' | ')}`);
      this.fm.notify('warn', `PR #${ref.id}: ${failed.length} update(s) could not be posted (${truncate(failed[0]!, 120)})`);
    }
  }
}

/** Severity names for docs and validation. */
export const FINDING_SEVERITIES = SEVERITIES;
