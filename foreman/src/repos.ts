// RepoManager: registered local git repos, per-worker worktrees, structured diffs, guarded merges.
//
// Safety contract
//  - never pushes (there is no code path that runs `git push`)
//  - worktrees live under <profile>/worktrees, on branches agentcraft/<agent>/<task-slug>
//  - the user's checkout is only ever modified by merge(), which requires an answered `merge`
//    decision whose option is "Merge" and refuses if the merge would conflict or if the checkout
//    that has the base branch checked out has uncommitted tracked changes
//  - the merge commit is built off-tree (merge-tree + commit-tree) and then applied with
//    `merge --ff-only`, so a refused/failed apply leaves the user's working tree untouched
//  - the merge commit is the user's: their git identity, signed if their git config signs
//    (unless signMerges is off); mergeStyle "squash" makes it a single-parent commit
//  - removing a finished worktree's directory never fails an operation (busy dirs are retried
//    later) and never deletes anything outside the worktree root
import { isSecretEnvVar } from './util/env.js';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { RepoSettings } from './config.js';
import type { Ctx } from './context.js';
import { parseUnifiedDiff, type ParsedDiff } from './diff.js';
import type { CiStatus, Decision, Repo, RepoSettingsView, Worktree } from './protocol.js';
import { DEFAULT_AUTO_SEVERITIES, DEFAULT_MAX_ROUNDS } from './prwatch.js';
import { withGitSafety } from './gitsafety.js';
import { ensureDir, isInsideOrEqual } from './util/fsx.js';
import { agentGitIdentity, git, gitConfigGet, gitOut, gitRemote, identityEnv, listWorktrees } from './util/git.js';
import { openPullRequest, parseRemote, type PrHost } from './prs.js';
import { run, runShell } from './util/proc.js';
import { slugify, tailLines } from './util/text.js';

export class RepoError extends Error {
  constructor(
    message: string,
    readonly code: 'not_found' | 'not_git' | 'no_commits' | 'refused' | 'conflict' | 'dirty' | 'empty' | 'failed' = 'failed',
    /** code 'conflict': the files that would conflict */
    readonly files: string[] = [],
  ) {
    super(message);
    this.name = 'RepoError';
  }
}

export interface DiffResult extends ParsedDiff {
  repoId: string;
  worktree: string;
  base: string;
  branch: string;
}

export interface MergeResult {
  sha: string;
  base: string;
  branch: string;
  files: number;
}

/** An approved task landed as a pull request (repoSettings.land "pr"). */
export interface PrResult {
  /** the PR's web URL (undefined: the remote is not Azure DevOps or GitHub; the branch was pushed) */
  url?: string;
  /** the branch name on the remote */
  remoteBranch: string;
  /** target branch on the remote */
  base: string;
  branch: string;
  /** a PR for this task existed already: the branch was updated */
  updated: boolean;
  /** the commit now at the tip of the remote branch */
  sha: string;
}

export type LandResult = ({ kind: 'merge'; pushed?: string } & MergeResult) | ({ kind: 'pr' } & PrResult);

export interface TestResult {
  pass: boolean;
  code: number;
  command: string;
  output: string; // tail
  durationMs: number;
  /** names of failing tests (TAP "not ok" lines / common runner formats), from the full output */
  failures: string[];
  /** e.g. "tests 15, pass 12, fail 3" when the runner prints a summary */
  summary?: string;
}

/**
 * Pull failing test names and a summary line out of common test-runner output: TAP, and the spec
 * reporter `node --test` uses by default on newer Node versions ("ℹ tests 15", "✖ name (1.2ms)").
 */
export function parseTestOutput(text: string): { failures: string[]; summary?: string } {
  const failures: string[] = [];
  for (const m of text.matchAll(/^not ok \d+ - (.+)$/gm)) failures.push(m[1]!.replace(/\\#/g, '#').replace(/\s+#\s*(TODO|SKIP).*$/i, '').trim());
  if (!failures.length) {
    for (const m of text.matchAll(/^\s*(?:✖|×|FAIL)\s+(.+)$/gm)) {
      const name = m[1]!.replace(/\s+\(\d+(?:\.\d+)?m?s\)$/, '').trim();
      if (!/^failing tests:?$/i.test(name)) failures.push(name);
    }
  }
  const nums: string[] = [];
  for (const k of ['tests', 'pass', 'fail']) {
    const m = new RegExp(`^(?:# |ℹ )${k} (\\d+)$`, 'm').exec(text);
    if (m) nums.push(`${k} ${m[1]}`);
  }
  return { failures: [...new Set(failures)].slice(0, 20), ...(nums.length ? { summary: nums.join(', ') } : {}) };
}

export const BRANCH_PREFIX = 'agentcraft/';

const STOP_WORDS = new Set(['a', 'an', 'the', 'and', 'or', 'for', 'of', 'to', 'in', 'on', 'with', 'by', 'at', 'from']);

/** "Tag parser module (src/tags.ts)" -> "tag-parser-module": drop parentheticals, cut at a word boundary. */
export function branchSlug(title: string, max = 24): string {
  const base = slugify(title.replace(/\([^)]*\)/g, ' ').replace(/`/g, ''), 64);
  let out = base;
  if (base.length > max) {
    const cut = base.slice(0, max + 1);
    const i = cut.lastIndexOf('-');
    out = (i >= 8 ? cut.slice(0, i) : base.slice(0, max)).replace(/-+$/, '');
  }
  // drop dangling stop words ("readme-help-text-for" -> "readme-help-text")
  const parts = out.split('-');
  while (parts.length > 1 && STOP_WORDS.has(parts[parts.length - 1]!)) parts.pop();
  return parts.join('-');
}

const agentIdentity = agentGitIdentity;
const TAMPERED = 'AgentCraft will not run git in worktree';

/** Real path (8.3 names, links, case) when it exists, else the resolved path. */
function realPath(p: string): string {
  try {
    return fs.realpathSync.native(p);
  } catch {
    return path.resolve(p);
  }
}

export function samePath(a: string, b: string): boolean {
  if (!a || !b) return false;
  const n = (p: string) => {
    const r = realPath(p).replace(/[\\/]+$/, '');
    return process.platform === 'win32' ? r.toLowerCase().replace(/\//g, '\\') : r;
  };
  return n(a) === n(b);
}

export interface RepoOptions {
  /** merge: a merge commit that keeps the agents' commits; squash: one commit with the changes */
  mergeStyle?: 'merge' | 'squash';
  /** sign the approved merge commit if the repo's git config says commit.gpgsign=true */
  signMerges?: boolean;
  /** per-repo settings keyed by absolute repo path (config.json repoSettings) */
  settings?: Record<string, RepoSettings>;
  /** runs `az` / `gh` to open pull requests (tests inject a fake) */
  prRunFn?: typeof run;
}

export interface PrepareResult {
  /** files/directories copied from the main checkout */
  copied: string[];
  /** the setup command, when one ran */
  setup?: { command: string; ok: boolean; output: string; durationMs: number };
}

/** the user's git identity as their own git sees it in that repo (falls back to AgentCraft). */
async function userIdentity(repoPath: string): Promise<{ env: NodeJS.ProcessEnv; who: string }> {
  const name = await gitConfigGet(repoPath, 'user.name');
  const email = await gitConfigGet(repoPath, 'user.email');
  if (name && email) return { env: identityEnv(name, email), who: `${name} <${email}>` };
  return { env: agentIdentity('user'), who: 'AgentCraft <user@agentcraft.local>' };
}

export class RepoManager {
  readonly worktreeRoot: string;
  private refreshTimers = new Map<string, NodeJS.Timeout>();
  /** per-repo queue: merges / worktree add+remove never run concurrently on one repo */
  private locks = new Map<string, Promise<unknown>>();

  constructor(
    private ctx: Ctx,
    worktreeRoot: string,
    private opts: RepoOptions = {},
  ) {
    this.worktreeRoot = ensureDir(worktreeRoot);
  }

  /** config.set: mergeStyle / signMerges for the next approved merge */
  setMergeOptions(mergeStyle: 'merge' | 'squash', signMerges: boolean): void {
    this.opts.mergeStyle = mergeStyle;
    this.opts.signMerges = signMerges;
  }

  /** Broadcast a repository again (e.g. its settings view changed). */
  announce(repoId: string): void {
    const r = this.get(repoId);
    if (r) this.emitRepo(r);
  }

  /** the command runner for the PR host CLIs (tests replace it) */
  get prRunFn(): typeof run {
    return this.opts.prRunFn ?? run;
  }

  set prRunFn(fn: typeof run) {
    this.opts.prRunFn = fn;
  }

  private serial<T>(repoId: string, fn: () => Promise<T>): Promise<T> {
    const prev = this.locks.get(repoId) ?? Promise.resolve();
    const next = prev.then(fn, fn);
    const settled = next.catch(() => undefined);
    this.locks.set(repoId, settled);
    void settled.then(() => {
      if (this.locks.get(repoId) === settled) this.locks.delete(repoId);
    });
    return next;
  }

  private get repos(): Repo[] {
    return this.ctx.store.data.repos;
  }

  list(): Repo[] {
    return this.repos;
  }

  get(id: string): Repo | undefined {
    return this.repos.find((r) => r.id === id);
  }

  require(id: string): Repo {
    const r = this.get(id);
    if (!r) throw new RepoError(`no repo ${id}`, 'not_found');
    return r;
  }

  /** Default repo for goals without an explicit repoId: the most recently added. */
  defaultRepo(): Repo | undefined {
    return this.repos[this.repos.length - 1];
  }

  findWorktree(repoId: string, worktreeOrAgent: string): Worktree | undefined {
    const r = this.get(repoId);
    if (!r) return undefined;
    return (
      r.worktrees.find((w) => w.id === worktreeOrAgent) ??
      r.worktrees.find((w) => w.branch === worktreeOrAgent) ??
      [...r.worktrees].reverse().find((w) => w.agentId === worktreeOrAgent && w.status === 'active') ??
      [...r.worktrees].reverse().find((w) => w.agentId === worktreeOrAgent)
    );
  }

  requireWorktree(repoId: string, wt: string): Worktree {
    const w = this.findWorktree(repoId, wt);
    if (!w) throw new RepoError(`no worktree ${wt} in ${repoId}`, 'not_found');
    return w;
  }

  /** Register a local git repo (idempotent by path). */
  async add(p: string): Promise<Repo> {
    const abs = path.resolve(p.replace(/^~(?=$|[\\/])/, os.homedir()));
    if (!fs.existsSync(abs)) throw new RepoError(`path does not exist: ${abs}`, 'not_found');
    const top = await git(abs, ['rev-parse', '--show-toplevel'], { allowFail: true });
    if (top.code !== 0) throw new RepoError(`not a git repository: ${abs}`, 'not_git');
    const root = path.resolve(top.stdout.trim());
    // a folder inside some other repository is not that repository: registering the enclosing
    // repo silently would make it the merge target (e.g. a new folder under a project)
    const real = (p: string) => {
      try {
        return path.resolve(fs.realpathSync.native(p)).toLowerCase();
      } catch {
        return path.resolve(p).toLowerCase();
      }
    };
    if (real(abs) !== real(root)) {
      throw new RepoError(`${abs} is not a repository root: it is inside the git repository ${root}. Add the repository itself (/repo add ${root}) or run \`git init\` in ${abs} first.`, 'not_git');
    }
    const existing = this.repos.find((r) => path.resolve(r.path).toLowerCase() === root.toLowerCase());
    if (existing) {
      await this.applyBase(existing);
      await this.refresh(existing.id);
      return existing;
    }
    const head = await git(root, ['rev-parse', '--verify', 'HEAD'], { allowFail: true });
    if (head.code !== 0) throw new RepoError(`repository has no commits yet: ${root}`, 'no_commits');
    const branch = (await git(root, ['symbolic-ref', '--quiet', '--short', 'HEAD'], { allowFail: true })).stdout.trim();
    if (!branch) throw new RepoError(`repository is in detached HEAD state; check out a branch first: ${root}`, 'refused');
    let id = slugify(path.basename(root), 24);
    for (let i = 2; this.get(id); i++) id = `${slugify(path.basename(root), 20)}-${i}`;
    const repo: Repo = { id, name: path.basename(root), path: root, branch, dirty: false, worktrees: [], ci: 'unknown' };
    this.repos.push(repo);
    await this.applyBase(repo);
    await this.refresh(id);
    return repo;
  }

  private emitRepo(r: Repo): void {
    this.ctx.store.markDirty();
    this.ctx.emit({ type: 'repo.upsert', repo: this.view(r) });
  }

  /** A repo for the wire: a copy with its settings view (computed, never stored). */
  view(r: Repo): Repo {
    return { ...r, worktrees: r.worktrees.map((w) => ({ ...w })), settings: this.settingsView(r.id) };
  }

  /** Read-only view of the repo's repoSettings: nothing secret (env as its keys only). */
  settingsView(repoId: string): RepoSettingsView {
    const s = this.settingsFor(repoId);
    const v: RepoSettingsView = {
      land: s.land ?? 'merge',
      protect: [...(s.protect ?? [])],
      roles: { ...(s.roles ?? {}) },
      prReview: { autoSeverities: [...(s.prReview?.autoSeverities ?? DEFAULT_AUTO_SEVERITIES)], maxRounds: s.prReview?.maxRounds ?? DEFAULT_MAX_ROUNDS },
    };
    if (s.baseBranch) v.baseBranch = s.baseBranch;
    if (s.ci) v.ci = s.ci;
    if (s.setup) v.setup = s.setup;
    if (s.subagents) v.subagents = s.subagents;
    if (s.pr) {
      const pr: NonNullable<RepoSettingsView['pr']> = {};
      if (s.pr.remote) pr.remote = s.pr.remote;
      if (s.pr.branchPrefix) pr.branchPrefix = s.pr.branchPrefix;
      if (s.pr.draft !== undefined) pr.draft = s.pr.draft;
      if (s.pr.squash !== undefined) pr.squash = s.pr.squash;
      v.pr = pr;
    }
    if (s.env && Object.keys(s.env).length) v.envKeys = Object.keys(s.env);
    return v;
  }

  /** Unregister a repo (repo.remove): its worktrees and branches stay on disk. */
  remove(repoId: string): Repo {
    const r = this.require(repoId);
    this.ctx.store.data.repos = this.repos.filter((x) => x.id !== repoId);
    this.views.delete(repoId);
    const t = this.refreshTimers.get(repoId);
    if (t) clearTimeout(t);
    this.refreshTimers.delete(repoId);
    this.ctx.store.markDirty();
    return r;
  }

  setCi(repoId: string, ci: CiStatus): void {
    const r = this.require(repoId);
    if (r.ci === ci) return;
    r.ci = ci;
    this.emitRepo(r);
  }

  /** Update head/dirty and worktree stats, then broadcast. */
  async refresh(repoId: string): Promise<Repo> {
    const r = this.require(repoId);
    await this.applyBase(r);
    const h = await git(r.path, ['rev-parse', '--short', `refs/heads/${r.branch}`], { allowFail: true });
    if (h.code === 0) r.head = h.stdout.trim();
    r.dirty = await this.isDirty(r.path);
    for (const w of r.worktrees) {
      if (w.status !== 'active') continue;
      try {
        await this.updateWorktreeStats(r, w);
      } catch (e) {
        this.ctx.log.warn(`refresh ${w.id}: ${(e as Error).message}`);
      }
    }
    this.emitRepo(r);
    return r;
  }

  /**
   * Cheap check of the main checkout (head + dirty) that broadcasts only when something changed,
   * so `repo.dirty` follows the user's own edits even when no agent touches the repo.
   */
  async pollStatus(repoId: string): Promise<boolean> {
    const r = this.get(repoId);
    if (!r || !fs.existsSync(r.path)) return false;
    const h = await git(r.path, ['rev-parse', '--short', `refs/heads/${r.branch}`], { allowFail: true });
    const head = h.code === 0 ? h.stdout.trim() : r.head;
    const dirty = await this.isDirty(r.path);
    if (head === r.head && dirty === r.dirty) return false;
    if (head) r.head = head;
    r.dirty = dirty;
    this.emitRepo(r);
    return true;
  }

  private pollTimer: NodeJS.Timeout | undefined;

  startPolling(intervalMs = 10_000): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      for (const r of this.repos) this.pollStatus(r.id).catch((e) => this.ctx.log.debug(`poll ${r.id}: ${(e as Error).message}`));
      this.sweepPendingRemovals().catch((e) => this.ctx.log.debug(`sweep: ${(e as Error).message}`));
    }, intervalMs);
    this.pollTimer.unref?.();
  }

  stopPolling(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.pollTimer = undefined;
    for (const t of this.refreshTimers.values()) clearTimeout(t);
    this.refreshTimers.clear();
  }

  /** Coalesce frequent refresh requests (e.g. after every agent edit). */
  scheduleRefresh(repoId: string, delayMs = 400): void {
    if (this.refreshTimers.has(repoId)) return;
    const t = setTimeout(() => {
      this.refreshTimers.delete(repoId);
      this.refresh(repoId).catch((e) => this.ctx.log.warn(`refresh ${repoId}: ${(e as Error).message}`));
    }, delayMs);
    t.unref?.();
    this.refreshTimers.set(repoId, t);
  }

  /** Tracked changes (staged or unstaged) in a checkout. Untracked files do not count. */
  async isDirty(checkout: string): Promise<boolean> {
    const s = await git(checkout, ['status', '--porcelain', '--untracked-files=no'], { allowFail: true });
    return s.code !== 0 || s.stdout.trim().length > 0;
  }

  branchName(agentId: string, taskId: string, title: string): string {
    return `${BRANCH_PREFIX}${agentId}/${taskId}-${branchSlug(title)}`;
  }

  /**
   * Create (or reuse) the worktree for agent+task. New branches start from the repo's base branch.
   * Returns the existing active worktree if one already exists for that task.
   */
  /**
   * `base`: a branch of the user's to build on and land into instead of the repo's base (a goal
   * that continues the user's branch, see useBranch); it must exist locally.
   */
  createWorktree(repoId: string, agentId: string, task: { id: string; title: string }, opts: { startPoint?: string; base?: string } = {}): Promise<Worktree> {
    return this.serial(repoId, () => this.doCreateWorktree(repoId, agentId, task, opts.startPoint, opts.base));
  }

  /**
   * `startPoint`: continue from another branch (a task handed over from a stopped/reassigned
   * worker) instead of the base branch. An existing branch of this agent is only moved forward
   * to it (never rewound); if the histories diverged a fresh branch name is used instead.
   */
  private async doCreateWorktree(repoId: string, agentId: string, task: { id: string; title: string }, startPoint?: string, userBase?: string): Promise<Worktree> {
    const r = this.require(repoId);
    const id = `${agentId}-${task.id}`;
    const existing = r.worktrees.find((w) => w.id === id);
    if (existing && existing.status === 'active' && fs.existsSync(existing.path)) return existing;
    let branch = existing?.branch ?? this.branchName(agentId, task.id, task.title);
    let wtPath = path.join(this.worktreeRoot, r.id, id);
    ensureDir(path.dirname(wtPath));
    await git(r.path, ['worktree', 'prune'], { allowFail: true });
    const known = async (p: string) => (await listWorktrees(r.path)).some((e) => path.resolve(e.path).toLowerCase() === path.resolve(p).toLowerCase());
    if (fs.existsSync(wtPath) && !(await known(wtPath))) {
      // stale directory (crash, or still busy when it was abandoned): remove it, or if something
      // still holds it, use a fresh directory next to it
      try {
        fs.rmSync(wtPath, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
      } catch (e) {
        let n = 2;
        while (fs.existsSync(`${wtPath}-${n}`) && n < 50) n++;
        this.ctx.log.warn(`${wtPath} is busy (${(e as NodeJS.ErrnoException).code ?? (e as Error).message}); using ${wtPath}-${n}`);
        wtPath = `${wtPath}-${n}`;
      }
    }
    // PR repos branch from the server's base branch (origin/<base>), fetched just now
    const pr = this.landsAsPr(r.id) && !userBase;
    const base = userBase ?? (pr ? `${this.remoteOf(r.id)}/${r.branch}` : r.branch);
    if (pr && !fs.existsSync(wtPath)) await this.fetchBase(r);
    if (!fs.existsSync(wtPath)) {
      const exists = async (b: string) => (await git(r.path, ['rev-parse', '--verify', '--quiet', `refs/heads/${b}`], { allowFail: true })).code === 0;
      let branchExists = await exists(branch);
      if (startPoint && branchExists && branch !== startPoint) {
        const ancestor = (await git(r.path, ['merge-base', '--is-ancestor', `refs/heads/${branch}`, startPoint], { allowFail: true })).code === 0;
        if (ancestor) {
          await git(r.path, ['branch', '-f', branch, startPoint]); // fast-forward only: nothing is lost
        } else {
          let n = 2;
          while (await exists(`${branch}-${n}`)) if (++n > 50) throw new RepoError(`no free branch name for ${branch}`);
          branch = `${branch}-${n}`;
          branchExists = false;
        }
      }
      // agent worktrees hold the repository's bytes as committed (no CRLF conversion), so agents,
      // their edits and the Foreman's diffs all see the same content
      const lf = ['-c', 'core.autocrlf=false'];
      // a fresh directory needs its copy/setup again (prepareWorktree)
      delete this.ctx.store.data.worktreeMeta[`${r.id}/${id}`]?.prepared;
      if (branchExists) await git(r.path, [...lf, 'worktree', 'add', wtPath, branch]);
      else await git(r.path, [...lf, 'worktree', 'add', '--no-track', '-b', branch, wtPath, startPoint ?? base]);
    }
    const w: Worktree = {
      id,
      agentId,
      taskId: task.id,
      branch,
      base: existing?.base ?? base,
      path: wtPath,
      status: 'active',
      ahead: 0,
      files: 0,
      additions: 0,
      deletions: 0,
    };
    if (existing) Object.assign(existing, w);
    else r.worktrees.push(w);
    this.ctx.store.data.worktreeMeta[`${r.id}/${id}`] ??= { createdAt: this.ctx.now() };
    await this.updateWorktreeStats(r, existing ?? w);
    this.emitRepo(r);
    return existing ?? w;
  }

  private async updateWorktreeStats(r: Repo, w: Worktree): Promise<void> {
    if (!fs.existsSync(w.path)) return;
    const ahead = await git(w.path, ['rev-list', '--count', `${w.base}..HEAD`], { allowFail: true });
    w.ahead = ahead.code === 0 ? Number(ahead.stdout.trim()) || 0 : 0;
    const d = await this.rawWorkingDiff(r, w, ['--numstat']);
    let files = 0;
    let add = 0;
    let del = 0;
    for (const line of d.split('\n')) {
      const m = /^(\d+|-)\t(\d+|-)\t/.exec(line);
      if (!m) continue;
      files++;
      add += m[1] === '-' ? 0 : Number(m[1]);
      del += m[2] === '-' ? 0 : Number(m[2]);
    }
    w.files = files;
    w.additions = add;
    w.deletions = del;
  }

  /**
   * Diff of the worktree's working tree (including uncommitted + untracked files, respecting
   * .gitignore) against merge-base(base, HEAD). Uses a throwaway index so the agent's own index
   * is untouched.
   */
  private async rawWorkingDiff(_r: Repo, w: Worktree, extra: string[], from?: string): Promise<string> {
    const mb = from ?? (await gitOut(w.path, ['merge-base', w.base, 'HEAD']));
    const tmpIndex = path.join(os.tmpdir(), `agentcraft-index-${process.pid}-${Math.random().toString(36).slice(2)}`);
    const env = { GIT_INDEX_FILE: tmpIndex };
    try {
      await git(w.path, ['read-tree', 'HEAD'], { env });
      await git(w.path, ['add', '-A'], { env });
      const res = await git(w.path, ['diff', '--cached', '-M', '--no-ext-diff', '--unified=3', ...extra, mb], { env });
      return res.stdout;
    } finally {
      fs.rmSync(tmpIndex, { force: true });
      fs.rmSync(`${tmpIndex}.lock`, { force: true });
    }
  }

  /** `from`: diff against this commit instead of the fork point (a PR fold-in: only the follow-up). */
  async diff(repoId: string, worktreeId: string, opts: { from?: string } = {}): Promise<DiffResult> {
    const r = this.require(repoId);
    const w = this.requireWorktree(repoId, worktreeId);
    let text: string;
    if (w.status === 'active' && fs.existsSync(w.path)) {
      const v = await this.verifyWorktreeGit(r, w);
      if (!v.ok) throw new RepoError(`cannot show the diff of ${w.id}: ${v.reason}`, 'refused');
      text = await this.rawWorkingDiff(r, w, [], opts.from);
    } else {
      const meta = this.ctx.store.data.worktreeMeta[`${r.id}/${w.id}`];
      const from = opts.from ?? meta?.mergedBaseSha ?? (await gitOut(r.path, ['merge-base', w.base, w.branch]));
      text = (await git(r.path, ['diff', '-M', '--no-ext-diff', '--unified=3', from, w.branch])).stdout;
    }
    const parsed = parseUnifiedDiff(text);
    return { ...parsed, repoId: r.id, worktree: w.id, base: w.base, branch: w.branch };
  }

  /**
   * Is the worktree's git still this repository's worktree at w.path? An agent can rewrite the
   * `.git` link file (to the user's checkout, another worktree or another repo) or delete it (git
   * then walks up to an enclosing repository). Checked before the Foreman writes with git in a
   * worktree (commits) or shows its diff for review. `head` is the symbolic ref HEAD points at.
   */
  async verifyWorktreeGit(r: Repo, w: Worktree): Promise<{ ok: true; head: string } | { ok: false; reason: string }> {
    if (!fs.existsSync(w.path)) return { ok: false, reason: `${w.path} does not exist` };
    const res = await git(w.path, ['rev-parse', '--path-format=absolute', '--git-dir', '--git-common-dir', '--show-toplevel'], { allowFail: true });
    if (res.code !== 0) return { ok: false, reason: `git finds no repository at ${w.path} (its .git link is missing or broken)` };
    const [gitDir = '', commonDir = '', top = ''] = res.stdout.trim().split(/\r?\n/);
    const repoCommon = (await git(r.path, ['rev-parse', '--path-format=absolute', '--git-common-dir'], { allowFail: true })).stdout.trim();
    if (!samePath(top, w.path)) return { ok: false, reason: `git in ${w.path} works on ${top} instead (its .git link was removed or changed)` };
    if (!repoCommon || !samePath(commonDir, repoCommon)) return { ok: false, reason: `${w.path} now belongs to another repository (${commonDir})` };
    if (samePath(gitDir, commonDir) || !isInsideOrEqual(realPath(gitDir), realPath(path.join(repoCommon, 'worktrees')))) {
      return { ok: false, reason: `${w.path}/.git points at ${gitDir}, not at the worktree's own entry` };
    }
    let back = '';
    try {
      back = fs.readFileSync(path.join(gitDir, 'gitdir'), 'utf8').trim();
    } catch {
      /* checked below */
    }
    if (!back || !samePath(path.dirname(back), w.path)) return { ok: false, reason: `${w.path}/.git points at the worktree entry of ${back ? path.dirname(back) : 'another directory'}` };
    const head = (await git(w.path, ['symbolic-ref', '-q', 'HEAD'], { allowFail: true })).stdout.trim();
    return { ok: true, head };
  }

  /**
   * Commit everything in the worktree (agent identity) on the agent's own branch. Returns true if
   * a commit was made. Only ever moves refs/heads/agentcraft/...: if the agent left HEAD on
   * another branch or a detached commit, the working tree is snapshotted onto its own branch
   * without touching HEAD's branch. Refuses if the worktree's .git link was tampered with.
   */
  async commitAll(repoId: string, worktreeId: string, message: string): Promise<boolean> {
    const r = this.require(repoId);
    const w = this.requireWorktree(repoId, worktreeId);
    if (w.status !== 'active') return false;
    if (!w.branch.startsWith(BRANCH_PREFIX)) throw new RepoError(`refusing to commit on ${w.branch}: not an AgentCraft branch`, 'refused');
    const v = await this.verifyWorktreeGit(r, w);
    if (!v.ok) throw new RepoError(`${TAMPERED} ${w.id}: ${v.reason}`, 'refused');
    const ref = `refs/heads/${w.branch}`;
    const identity = agentIdentity(w.agentId);
    const keep = this.protectedPaths(repoId);
    // protected paths stay out of every commit AgentCraft makes (they may be modified in the worktree)
    const unstage = async (env?: NodeJS.ProcessEnv) => {
      if (keep.length) await git(w.path, ['reset', '-q', '--', ...keep], { allowFail: true, ...(env ? { env } : {}) });
    };
    if (v.head === ref) {
      await git(w.path, ['add', '-A']);
      await unstage();
      const st = await git(w.path, ['diff', '--cached', '--quiet'], { allowFail: true });
      if (st.code === 0) return false;
      await git(w.path, ['commit', '-q', '--no-verify', '-m', message], { env: identity });
      return true;
    }
    // HEAD is elsewhere: commit a snapshot of the working tree onto the agent's branch
    this.ctx.log.warn(`worktree ${w.id} is on ${v.head || 'a detached HEAD'}, not ${w.branch}: committing a snapshot onto ${w.branch}`);
    const tmpIndex = path.join(os.tmpdir(), `agentcraft-index-${process.pid}-${Math.random().toString(36).slice(2)}`);
    const env = { GIT_INDEX_FILE: tmpIndex };
    try {
      await git(w.path, ['read-tree', ref], { env });
      await git(w.path, ['add', '-A'], { env });
      await unstage(env);
      const tree = await gitOut(w.path, ['write-tree'], { env });
      const tip = await gitOut(w.path, ['rev-parse', ref]);
      if (tree === (await gitOut(w.path, ['rev-parse', `${tip}^{tree}`]))) return false;
      const sha = await gitOut(w.path, ['commit-tree', tree, '-p', tip, '-m', `${message}\n\n(snapshot of the working tree; HEAD was on ${v.head || 'a detached commit'})`], { env: identity });
      await git(w.path, ['update-ref', ref, sha, tip]);
      return true;
    } finally {
      fs.rmSync(tmpIndex, { force: true });
      fs.rmSync(`${tmpIndex}.lock`, { force: true });
    }
  }

  /** Where (if anywhere) a branch is checked out. */
  private async checkoutOf(r: Repo, branch: string): Promise<string | undefined> {
    const list = await listWorktrees(r.path);
    return list.find((e) => e.branch === branch)?.path;
  }

  /** Check a merge without performing it. */
  async canMerge(repoId: string, worktreeId: string): Promise<{ ok: true } | { ok: false; reason: string; code: RepoError['code']; files?: string[] }> {
    const r = this.require(repoId);
    const w = this.requireWorktree(repoId, worktreeId);
    if (w.status !== 'active') return { ok: false, reason: `worktree ${w.id} is ${w.status}`, code: 'refused' };
    const target = this.landsAsPr(r.id) && !this.isUserBase(r.id, w.base) ? undefined : await this.checkoutOf(r, w.base);
    if (target && (await this.isDirty(target))) {
      return { ok: false, reason: `the checkout at ${target} (${w.base}) has uncommitted changes — commit or stash them, then approve again`, code: 'dirty' };
    }
    const mt = await git(r.path, ['merge-tree', '--write-tree', '--name-only', '--no-messages', w.base, w.branch], { allowFail: true });
    if (mt.code === 1) {
      const files = mt.stdout.trim().split('\n').slice(1).filter(Boolean);
      return { ok: false, reason: `merge would conflict in: ${files.join(', ') || '(unknown files)'}`, code: 'conflict', files };
    }
    if (mt.code !== 0) return { ok: false, reason: `merge-tree failed: ${mt.stderr.trim()}`, code: 'failed' };
    return { ok: true };
  }

  /**
   * Merge a worker branch into the repo's base branch. ONLY with an answered merge decision whose
   * option is "Merge" and that targets this worktree.
   */
  /** `commitMessage` is used if the agent left uncommitted work (e.g. "t1: Add --version flag"). */
  /**
   * After work landed on a user's branch: push it to the remote if the branch is there (fast-forward
   * only, never forced). Returns what happened, for the feed; undefined when it is local-only.
   */
  private async pushUserBranch(repoId: string, branch: string): Promise<string | undefined> {
    const r = this.require(repoId);
    const remote = this.remoteOf(r.id);
    const tracked = (await git(r.path, ['rev-parse', '--verify', '--quiet', `refs/remotes/${remote}/${branch}`], { allowFail: true })).code === 0;
    if (!tracked) return undefined;
    const res = await gitRemote(r.path, ['push', '--no-verify', remote, `refs/heads/${branch}:refs/heads/${branch}`], { allowFail: true });
    if (res.code === 0) return `pushed ${branch} to ${remote}`;
    this.ctx.log.warn(`push ${branch}: ${(res.stderr || res.stdout).trim()}`);
    return `not pushed: ${remote}/${branch} has commits that are not on your ${branch} (pull or merge, then push yourself)`;
  }

  /** Land an approved task the repo's way: a local merge, or a pull request (repoSettings.land). */
  async land(decision: Decision, opts: { commitMessage?: string; title?: string; description?: string } = {}): Promise<LandResult> {
    const w = decision.repoId && decision.worktree ? this.findWorktree(decision.repoId, decision.worktree) : undefined;
    if (decision.repoId && w && this.isUserBase(decision.repoId, w.base)) {
      // the user's own branch: add the work to it (squashed if the repo's PRs are squashed), then
      // fast-forward it on the remote when it is there, so a PR already open from it updates
      const squash = !!this.settingsFor(decision.repoId).pr?.squash;
      const res = await this.merge(decision, { ...opts, ...(squash ? { style: 'squash' as const } : {}) });
      return { kind: 'merge', ...res, pushed: await this.pushUserBranch(decision.repoId, w.base) };
    }
    if (decision.repoId && this.landsAsPr(decision.repoId)) return { kind: 'pr', ...(await this.openPr(decision, opts)) };
    return { kind: 'merge', ...(await this.merge(decision, opts)) };
  }

  /**
   * Push an approved task's branch and open a pull request into the base branch. ONLY with an
   * answered merge decision whose option is "Merge". Never force-pushes over someone else's work: the
   * push expects the remote branch to be absent, or to be exactly what AgentCraft pushed last time.
   */
  openPr(decision: Decision, opts: { commitMessage?: string; title?: string; description?: string } = {}): Promise<PrResult> {
    return this.serial(decision.repoId ?? '?', () => this.doOpenPr(decision, opts));
  }

  private async doOpenPr(decision: Decision, opts: { commitMessage?: string; title?: string; description?: string }): Promise<PrResult> {
    if (decision.kind !== 'merge') throw new RepoError('a pull request requires a merge decision', 'refused');
    if (decision.status !== 'answered' || decision.answer?.option !== 'Merge') throw new RepoError(`decision ${decision.id} does not approve landing`, 'refused');
    if (!decision.repoId || !decision.worktree) throw new RepoError(`decision ${decision.id} names no repo/worktree`, 'refused');
    const r = this.require(decision.repoId);
    const w = this.requireWorktree(r.id, decision.worktree);
    if (w.status !== 'active') throw new RepoError(`worktree ${w.id} is ${w.status}`, 'refused');
    const s = this.settingsFor(r.id);
    const remote = this.remoteOf(r.id);
    const target = w.base.startsWith(`${remote}/`) ? w.base.slice(remote.length + 1) : w.base;
    const baseRef = `refs/remotes/${remote}/${target}`;

    // the PR AgentCraft opened for this task: this worktree's, or (a fold-in by another worker) the
    // task's earlier worktree's
    const key = `${r.id}/${w.id}`;
    const meta = (this.ctx.store.data.worktreeMeta[key] ??= { createdAt: this.ctx.now() });
    if (!meta.prUrl && w.taskId) {
      const prev = r.worktrees
        .filter((x) => x.taskId === w.taskId && x.id !== w.id)
        .map((x) => this.ctx.store.data.worktreeMeta[`${r.id}/${x.id}`])
        .reverse()
        .find((m) => m?.prUrl && m.prPushedSha);
      if (prev) {
        meta.prUrl = prev.prUrl;
        meta.prBranch = prev.prBranch;
        meta.prPushedSha = prev.prPushedSha;
        if (prev.mergedSha) meta.prevTip = prev.mergedSha;
      }
    }
    const pushed = meta.prUrl ? meta.prPushedSha : undefined;
    const prevTip = meta.prevTip ?? meta.mergedSha;

    // 1. the agent's work committed on its branch; the base as it is on the server now
    await this.commitAll(r.id, w.id, opts.commitMessage ?? `agentcraft: ${w.taskId ?? w.id}`);
    await this.fetchBase({ ...r, branch: target });
    const branchRef = `refs/heads/${w.branch}`;
    let src = branchRef;
    if (!pushed) {
      const ahead = Number(await gitOut(r.path, ['rev-list', '--count', `${baseRef}..${branchRef}`]));
      if (!ahead) throw new RepoError(`${w.branch} has no changes to land`, 'empty');
      const mt = await git(r.path, ['merge-tree', '--write-tree', '--name-only', '--no-messages', baseRef, branchRef], { allowFail: true });
      if (mt.code === 1) {
        const files = mt.stdout.trim().split('\n').slice(1).filter(Boolean);
        throw new RepoError(`the branch conflicts with ${remote}/${target} in: ${files.join(', ') || '(unknown files)'}`, 'conflict', files);
      }
      if (mt.code !== 0) throw new RepoError(`merge-tree failed: ${mt.stderr.trim()}`, 'failed');

      // 2. what to push: the agents' commits, or one commit authored by the user on top of the base
      if (s.pr?.squash) {
        const tree = mt.stdout.trim().split('\n')[0]!.trim();
        const baseSha = await gitOut(r.path, ['rev-parse', baseRef]);
        const authors = [...new Set((await gitOut(r.path, ['log', '--format=%an <%ae>', `${baseRef}..${branchRef}`])).split('\n').filter(Boolean))];
        const msg = `${(opts.title ?? opts.commitMessage ?? w.taskId ?? w.id).trim()}${opts.description ? `\n\n${opts.description.trim()}` : ''}${authors.length ? `\n\n${authors.map((a) => `Co-authored-by: ${a}`).join('\n')}` : ''}`;
        const { env } = await userIdentity(r.path);
        src = await gitOut(r.path, ['commit-tree', tree, '-p', baseSha, '-m', msg], { env });
      }
    } else {
      // 2'. a follow-up on an open PR (review fixes): an ADDED commit on top of what is on the PR,
      //     never a rewrite. The branch continues from what was pushed: push it as a fast-forward.
      //     The PR's commit is a squash (or the history diverged): one new commit whose parent is
      //     the pushed commit and whose tree is the pushed tree plus what the branch did since it
      //     last landed (a 3-way merge with the last landed branch tip as the merge base), so newer
      //     base commits do not leak into it.
      const has = (await git(r.path, ['cat-file', '-e', `${pushed}^{commit}`], { allowFail: true })).code === 0;
      if (!has) throw new RepoError(`the commit AgentCraft last pushed to the PR (${pushed.slice(0, 7)}) is not in this repository any more`, 'refused');
      const ff = (await git(r.path, ['merge-base', '--is-ancestor', pushed, branchRef], { allowFail: true })).code === 0;
      if (ff) {
        if (!Number(await gitOut(r.path, ['rev-list', '--count', `${pushed}..${branchRef}`]))) throw new RepoError(`${w.branch} has nothing new for the pull request`, 'empty');
      } else {
        const known = !!prevTip && (await git(r.path, ['merge-base', '--is-ancestor', prevTip, branchRef], { allowFail: true })).code === 0;
        // without the last landed tip: the branch merged onto the PR commit's own parent (its base)
        const parents = (await gitOut(r.path, ['rev-list', '--parents', '-n', '1', pushed])).split(/\s+/).slice(1);
        const onto = parents.length === 1 ? parents[0]! : pushed;
        const mt = known
          ? await git(r.path, ['merge-tree', '--write-tree', '--name-only', '--no-messages', `--merge-base=${prevTip}`, pushed, branchRef], { allowFail: true })
          : await git(r.path, ['merge-tree', '--write-tree', '--name-only', '--no-messages', onto, branchRef], { allowFail: true });
        if (mt.code === 1) {
          const files = mt.stdout.trim().split('\n').slice(1).filter(Boolean);
          throw new RepoError(`the review fixes conflict with the pull request's base in: ${files.join(', ') || '(unknown files)'}`, 'conflict', files);
        }
        if (mt.code !== 0) throw new RepoError(`merge-tree failed: ${mt.stderr.trim()}`, 'failed');
        const tree = mt.stdout.trim().split('\n')[0]!.trim();
        if (tree === (await gitOut(r.path, ['rev-parse', `${pushed}^{tree}`]))) throw new RepoError(`${w.branch} has nothing new for the pull request`, 'empty');
        const range = known ? `${prevTip}..${branchRef}` : `${onto}..${branchRef}`;
        const authors = [...new Set((await gitOut(r.path, ['log', '--format=%an <%ae>', range])).split('\n').filter(Boolean))];
        const msg = `${(opts.commitMessage ?? 'Address review feedback').trim()}${authors.length ? `\n\n${authors.map((a) => `Co-authored-by: ${a}`).join('\n')}` : ''}`;
        const { env } = await userIdentity(r.path);
        src = await gitOut(r.path, ['commit-tree', tree, '-p', pushed, '-m', msg], { env });
      }
    }

    // 3. push, never over work AgentCraft did not push itself
    const tail = w.branch.startsWith(BRANCH_PREFIX) ? w.branch.split('/').slice(2).join('/') : w.branch;
    const remoteBranch = meta.prBranch ?? (s.pr?.branchPrefix ? `${s.pr.branchPrefix}${tail}` : w.branch);
    const lease = `--force-with-lease=refs/heads/${remoteBranch}:${meta.prPushedSha ?? ''}`;
    const push = await gitRemote(r.path, ['push', '--no-verify', lease, remote, `${src}:refs/heads/${remoteBranch}`], { allowFail: true });
    if (push.code !== 0) throw new RepoError(`push to ${remote}/${remoteBranch} failed: ${(push.stderr || push.stdout).trim().split('\n').slice(-2).join(' ')}`, 'refused');
    meta.prBranch = remoteBranch;
    meta.prPushedSha = await gitOut(r.path, ['rev-parse', src]);

    // 4. the pull request (once; later approvals update the same branch)
    const updated = !!meta.prUrl;
    if (!meta.prUrl) {
      const host: PrHost | undefined = parseRemote(await gitOut(r.path, ['remote', 'get-url', remote]));
      if (host) {
        meta.prUrl = await openPullRequest(host, { source: remoteBranch, target, title: opts.title ?? `${w.taskId ?? w.id}`, description: opts.description ?? '', draft: !!s.pr?.draft }, r.path, this.prRunFn);
      } else this.ctx.log.warn(`${r.name}: ${remote} is not an Azure DevOps or GitHub remote; pushed ${remoteBranch}, open the PR yourself`);
    }

    // 5. bookkeeping: keep the local branch (the diff stays viewable); remove the worktree directory
    meta.mergedBaseSha = await gitOut(r.path, ['merge-base', baseRef, branchRef]);
    meta.mergedSha = await gitOut(r.path, ['rev-parse', branchRef]);
    delete meta.prevTip;
    // the task's other worktrees (earlier rounds by other workers) follow the PR's new state
    for (const x of r.worktrees) {
      const m = x.id !== w.id && x.taskId === w.taskId ? this.ctx.store.data.worktreeMeta[`${r.id}/${x.id}`] : undefined;
      if (!m?.prUrl || m.prUrl !== meta.prUrl) continue;
      m.prPushedSha = meta.prPushedSha;
      m.prevTip = meta.mergedSha;
    }
    w.status = 'merged';
    this.ctx.store.markDirty();
    await this.removeWorktreeDir(r, w);
    await this.refresh(r.id);
    return { ...(meta.prUrl ? { url: meta.prUrl } : {}), remoteBranch, base: target, branch: w.branch, updated, sha: meta.prPushedSha };
  }

  merge(decision: Decision, opts: { commitMessage?: string; style?: 'merge' | 'squash' } = {}): Promise<MergeResult> {
    return this.serial(decision.repoId ?? '?', () => this.doMerge(decision, opts.commitMessage, opts.style));
  }

  private async doMerge(decision: Decision, commitMessage?: string, style?: 'merge' | 'squash'): Promise<MergeResult> {
    if (decision.kind !== 'merge') throw new RepoError('merge requires a merge decision', 'refused');
    if (decision.status !== 'answered' || decision.answer?.option !== 'Merge') {
      throw new RepoError(`decision ${decision.id} does not approve a merge`, 'refused');
    }
    if (!decision.repoId || !decision.worktree) throw new RepoError(`decision ${decision.id} names no repo/worktree`, 'refused');
    const r = this.require(decision.repoId);
    const w = this.requireWorktree(r.id, decision.worktree);
    if (w.id !== decision.worktree) throw new RepoError(`decision ${decision.id} targets ${decision.worktree}, not ${w.id}`, 'refused');
    if (w.status !== 'active') throw new RepoError(`worktree ${w.id} is ${w.status}`, 'refused');

    // 1. make sure the agent's work is committed on its branch
    await this.commitAll(r.id, w.id, commitMessage ?? `agentcraft: ${w.taskId ?? w.id}`);
    const ahead = Number(await gitOut(r.path, ['rev-list', '--count', `${w.base}..${w.branch}`]));
    if (!ahead) throw new RepoError(`${w.branch} has no changes to merge`, 'empty');

    // 2. safety checks: conflicts + dirty target checkout
    const check = await this.canMerge(r.id, w.id);
    if (!check.ok) throw new RepoError(check.reason, check.code, check.files);

    // 3. build the merge commit off-tree, as the user (they approved it): their git identity, and
    //    signed if his git config signs commits (commit-tree ignores commit.gpgsign by itself)
    const baseSha = await gitOut(r.path, ['rev-parse', `refs/heads/${w.base}`]);
    const branchSha = await gitOut(r.path, ['rev-parse', `refs/heads/${w.branch}`]);
    const tree = (await gitOut(r.path, ['merge-tree', '--write-tree', '--no-messages', w.base, w.branch])).split('\n')[0]!.trim();
    const approved = `Approved in AgentCraft (decision ${decision.id}${w.taskId ? `, task ${w.taskId}` : ''}).`;
    const squash = (style ?? this.opts.mergeStyle) === 'squash';
    let msg: string;
    if (squash) {
      const authors = [...new Set((await gitOut(r.path, ['log', '--format=%an <%ae>', `${baseSha}..${branchSha}`])).split('\n').filter(Boolean))];
      msg = `${(commitMessage ?? `agentcraft: ${w.taskId ?? w.id}`).trim()}\n\nSquashed from ${w.branch}. ${approved}${authors.length ? `\n\n${authors.map((a) => `Co-authored-by: ${a}`).join('\n')}` : ''}`;
    } else msg = `Merge ${w.branch} into ${w.base}\n\n${approved}`;
    const sign = !!this.opts.signMerges && (await gitConfigGet(r.path, 'commit.gpgsign', 'bool')) === 'true';
    const { env } = await userIdentity(r.path);
    const parents = squash ? ['-p', baseSha] : ['-p', baseSha, '-p', branchSha];
    const ct = await git(r.path, ['commit-tree', ...(sign ? ['-S'] : []), tree, ...parents, '-m', msg], { env, allowFail: true, timeoutMs: 120_000 });
    if (ct.code !== 0) {
      const why = (ct.stderr || ct.stdout).trim().split('\n').slice(-2).join(' ');
      throw new RepoError(sign ? `signing the merge commit failed (your git config has commit.gpgsign=true): ${why}` : `could not create the merge commit: ${why}`, 'failed');
    }
    const mergeSha = ct.stdout.trim();

    // 4. apply: fast-forward the checkout that has base checked out, or move the ref if none does
    const target = await this.checkoutOf(r, w.base);
    if (target) {
      const ff = await git(target, ['merge', '--ff-only', '-q', mergeSha], { allowFail: true });
      if (ff.code !== 0) {
        throw new RepoError(`could not update ${target}: ${(ff.stderr || ff.stdout).trim().split('\n').slice(-2).join(' ')}`, 'refused');
      }
    } else {
      await git(r.path, ['update-ref', `refs/heads/${w.base}`, mergeSha, baseSha]);
    }

    // 5. bookkeeping: keep the branch (no data loss); remove the worktree directory
    const files = Number((await gitOut(r.path, ['diff', '--name-only', baseSha, mergeSha])).split('\n').filter(Boolean).length);
    // fork point of the branch, so the merged diff shows exactly the branch's own changes
    const forkPoint = await gitOut(r.path, ['merge-base', baseSha, branchSha]);
    this.ctx.store.data.worktreeMeta[`${r.id}/${w.id}`] = {
      ...(this.ctx.store.data.worktreeMeta[`${r.id}/${w.id}`] ?? { createdAt: this.ctx.now() }),
      mergedBaseSha: forkPoint,
      mergedSha: mergeSha,
    };
    w.status = 'merged';
    await this.removeWorktreeDir(r, w);
    await this.refresh(r.id);
    return { sha: mergeSha.slice(0, 7), base: w.base, branch: w.branch, files };
  }

  /**
   * Abandon a worktree (user rejected, or the task moved to another worker): uncommitted work is
   * committed on the branch, the directory removed, the branch kept.
   */
  abandon(repoId: string, worktreeId: string, message?: string): Promise<void> {
    return this.serial(repoId, () => this.doAbandon(repoId, worktreeId, message));
  }

  private async doAbandon(repoId: string, worktreeId: string, message?: string): Promise<void> {
    const r = this.require(repoId);
    const w = this.requireWorktree(repoId, worktreeId);
    if (w.status !== 'active') return;
    let tampered = false;
    await this.commitAll(r.id, w.id, message ?? `agentcraft: ${w.taskId ?? w.id} (abandoned)`).catch((e: Error) => {
      tampered = e instanceof RepoError && e.message.startsWith(TAMPERED);
      this.ctx.log.warn(`abandon ${w.id}: could not commit its work: ${e.message}`);
      return false;
    });
    w.status = 'abandoned';
    // a worktree whose .git was tampered with is left in place for the user to look at
    if (tampered) this.ctx.log.error(`worktree ${w.id}: left in place at ${w.path} (its git link was changed; nothing was committed)`);
    else await this.removeWorktreeDir(r, w);
    await this.refresh(r.id);
  }

  /**
   * Remove a finished worktree's directory. Never throws: on Windows a directory is "busy" while
   * any process still has it as its cwd (a stopped agent's CLI takes a moment to exit), so this
   * retries for a while and otherwise leaves it for the background sweep. The branch (the work)
   * is never touched. Only ever deletes inside our own worktree root.
   */
  private async removeWorktreeDir(r: Repo, w: Worktree, attempts = 6): Promise<boolean> {
    if (!isInsideOrEqual(w.path, this.worktreeRoot) || path.resolve(w.path) === path.resolve(this.worktreeRoot)) {
      this.ctx.log.error(`refusing to remove ${w.path}: not inside ${this.worktreeRoot}`);
      return false;
    }
    const key = `${r.id}/${w.id}`;
    let lastError = '';
    for (let i = 0; i < attempts; i++) {
      if (i > 0) await new Promise((res) => setTimeout(res, 250 * 2 ** Math.min(i - 1, 3)));
      if (fs.existsSync(w.path)) {
        const res = await git(r.path, ['worktree', 'remove', '--force', w.path], { allowFail: true });
        if (res.code !== 0 && fs.existsSync(w.path)) {
          try {
            fs.rmSync(w.path, { recursive: true, force: true, maxRetries: 2, retryDelay: 100 });
          } catch (e) {
            lastError = (e as NodeJS.ErrnoException).code ?? (e as Error).message;
          }
        }
      }
      if (!fs.existsSync(w.path)) {
        await git(r.path, ['worktree', 'prune'], { allowFail: true });
        const meta = this.ctx.store.data.worktreeMeta[key];
        if (meta?.pendingRemoval) {
          delete meta.pendingRemoval;
          this.ctx.store.markDirty();
        }
        return true;
      }
    }
    const meta = (this.ctx.store.data.worktreeMeta[key] ??= { createdAt: this.ctx.now() });
    meta.pendingRemoval = true;
    this.ctx.store.markDirty();
    this.ctx.log.warn(`worktree dir ${w.path} is still in use (${lastError || 'busy'}); will remove it later (the branch ${w.branch} is kept)`);
    return false;
  }

  /** Retry removing directories of finished worktrees that were busy before (poll timer / start). */
  async sweepPendingRemovals(): Promise<number> {
    let n = 0;
    for (const r of this.repos) {
      for (const w of r.worktrees) {
        if (w.status === 'active' || !this.ctx.store.data.worktreeMeta[`${r.id}/${w.id}`]?.pendingRemoval) continue;
        if (await this.serial(r.id, () => this.removeWorktreeDir(r, w, 1))) n++;
      }
    }
    return n;
  }

  /** Commits on `branch` that `base` does not have (0 if the branch is gone). */
  async commitsAhead(repoId: string, branch: string, base: string): Promise<number> {
    const r = this.require(repoId);
    const res = await git(r.path, ['rev-list', '--count', `${base}..refs/heads/${branch}`], { allowFail: true });
    return res.code === 0 ? Number(res.stdout.trim()) || 0 : 0;
  }

  /**
   * repoSettings.baseBranch: make it the repo's base (what agents start from and land into),
   * creating the local branch from origin/<base> when only the remote one exists (no network).
   */
  private async applyBase(r: Repo): Promise<void> {
    const base = this.settingsFor(r.id).baseBranch;
    if (!base || r.branch === base) return;
    const has = async (ref: string) => (await git(r.path, ['rev-parse', '--verify', '--quiet', ref], { allowFail: true })).code === 0;
    if (!(await has(`refs/heads/${base}`))) {
      if (!(await has(`refs/remotes/origin/${base}`))) {
        this.ctx.log.warn(`repoSettings baseBranch: ${r.name} has no branch ${base} (nor origin/${base}); staying on ${r.branch}`);
        return;
      }
      await git(r.path, ['branch', base, `refs/remotes/origin/${base}`]);
      this.ctx.log.info(`${r.name}: created ${base} from origin/${base}`);
    }
    this.ctx.log.info(`${r.name}: base branch ${base} (repoSettings; the checkout stays on ${r.branch})`);
    r.branch = base;
  }

  /** Does this repo land approved work as pull requests (repoSettings.land "pr")? */
  landsAsPr(repoId: string): boolean {
    return this.settingsFor(repoId).land === 'pr';
  }

  private remoteOf(repoId: string): string {
    return this.settingsFor(repoId).pr?.remote ?? 'origin';
  }

  /**
   * PR repos: fetch the base branch so new work starts from what is on the server, not from a
   * local branch nobody updates. A failed fetch (offline) is logged; the last fetched state is used.
   */
  private async fetchBase(r: Repo): Promise<void> {
    const remote = this.remoteOf(r.id);
    const res = await gitRemote(r.path, ['fetch', '--no-tags', remote, `+refs/heads/${r.branch}:refs/remotes/${remote}/${r.branch}`], { allowFail: true });
    if (res.code !== 0) this.ctx.log.warn(`${r.name}: could not fetch ${remote}/${r.branch} (${(res.stderr || res.stdout).trim().split('\n').pop()}); using the last fetched state`);
  }

  /** repo id -> the lead's read-only view of the base (see leadView) */
  private views = new Map<string, string>();

  /**
   * A read-only view of the repository's base for the lead: a detached worktree at the base
   * (PR repos: the freshly fetched origin/<base>) under <worktrees>/<repo>/_lead, refreshed on every
   * call. The lead plans and reviews against what workers start from, not against the user's
   * checkout, which may be on another branch with work in progress. Nothing is ever written there.
   */
  leadView(repoId: string, branch?: string): Promise<string> {
    return this.serial(repoId, async () => {
      const r = this.require(repoId);
      const pr = this.landsAsPr(r.id) && !branch;
      if (pr) await this.fetchBase(r);
      const sha = await gitOut(r.path, ['rev-parse', '--verify', branch ? `refs/heads/${branch}` : pr ? `refs/remotes/${this.remoteOf(r.id)}/${r.branch}` : `refs/heads/${r.branch}`]);
      const dir = path.join(this.worktreeRoot, r.id, '_lead');
      const lf = ['-c', 'core.autocrlf=false'];
      const known = fs.existsSync(dir) && (await listWorktrees(r.path)).some((e) => samePath(e.path, dir));
      if (known) {
        await git(dir, [...lf, 'checkout', '-q', '--force', '--detach', sha]);
      } else {
        fs.rmSync(dir, { recursive: true, force: true });
        await git(r.path, ['worktree', 'prune'], { allowFail: true });
        ensureDir(path.dirname(dir));
        await git(r.path, [...lf, 'worktree', 'add', '-q', '--detach', dir, sha]);
      }
      this.views.set(r.id, dir);
      return dir;
    });
  }

  /** The lead's view of a repository, once leadView made it. */
  viewPath(repoId: string): string | undefined {
    return this.views.get(repoId);
  }

  /**
   * A goal that continues one of the user's branches (e.g. one started in Claude Desktop): check the
   * branch exists, locally or on the remote (fetched; then a local branch is made from it, no other
   * change), and return where it lives. Throws a RepoError saying why it cannot be used.
   */
  useBranch(repoId: string, name: string): Promise<{ branch: string; onRemote: boolean }> {
    return this.serial(repoId, async () => {
      const r = this.require(repoId);
      if (!/^[\w./-]+$/.test(name) || name.startsWith(BRANCH_PREFIX)) throw new RepoError(`"${name}" is not a branch AgentCraft can build on`, 'refused');
      const remote = this.remoteOf(r.id);
      const has = async (ref: string) => (await git(r.path, ['rev-parse', '--verify', '--quiet', ref], { allowFail: true })).code === 0;
      const hasRemote = (await git(r.path, ['remote', 'get-url', remote], { allowFail: true })).code === 0;
      if (hasRemote) await gitRemote(r.path, ['fetch', '--no-tags', remote, `+refs/heads/${name}:refs/remotes/${remote}/${name}`], { allowFail: true });
      const onRemote = hasRemote && (await has(`refs/remotes/${remote}/${name}`));
      if (!(await has(`refs/heads/${name}`))) {
        if (!onRemote) throw new RepoError(`${r.name} has no branch ${name} (not locally, not on ${remote})`, 'not_found');
        await git(r.path, ['branch', name, `refs/remotes/${remote}/${name}`]);
        this.ctx.log.info(`${r.name}: created ${name} from ${remote}/${name}`);
      }
      return { branch: name, onRemote };
    });
  }

  /** Is this worktree based on a user's branch (useBranch) rather than the repo's base? */
  isUserBase(repoId: string, base: string): boolean {
    const r = this.require(repoId);
    return base !== r.branch && base !== `${this.remoteOf(r.id)}/${r.branch}`;
  }

  /** repoSettings.protect for a repo. */
  protectedPaths(repoId: string): string[] {
    return this.settingsFor(repoId).protect ?? [];
  }

  /** Is `rel` (a repo-relative path) one of the repo's protected paths? */
  isProtected(repoId: string, rel: string): boolean {
    const p = rel.replace(/\\/g, '/').replace(/^\.\//, '');
    return this.protectedPaths(repoId).some((x) => (x.endsWith('/') ? p.startsWith(x) : p === x));
  }

  /**
   * Protected files the branch has COMMITTED changes to since its base. Uncommitted edits are fine:
   * AgentCraft's own commits leave protected paths out (commitAll).
   */
  async protectedChanges(repoId: string, worktreeId: string): Promise<string[]> {
    if (!this.protectedPaths(repoId).length) return [];
    const r = this.require(repoId);
    const w = this.requireWorktree(repoId, worktreeId);
    const mb = await git(r.path, ['merge-base', w.base, `refs/heads/${w.branch}`], { allowFail: true });
    if (mb.code !== 0) return [];
    const names = await gitOut(r.path, ['diff', '--name-only', '--no-renames', mb.stdout.trim(), `refs/heads/${w.branch}`]);
    return names.split('\n').filter(Boolean).filter((f) => this.isProtected(repoId, f));
  }

  /** repoSettings.env expanded against `base` (~, $VAR, ${VAR}). */
  envFor(repoId: string | undefined, base: NodeJS.ProcessEnv = process.env): Record<string, string> {
    const env = repoId ? this.settingsFor(repoId).env : undefined;
    if (!env) return {};
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(env)) {
      // the client token can neither be set nor copied in ($AGENTCRAFT_CLIENT_TOKEN expands to '')
      if (isSecretEnvVar(k)) continue;
      out[k] = v.replace(/^~(?=$|[\\/])/, os.homedir()).replace(/\$\{(\w+)\}|\$(\w+)/g, (_m, a: string | undefined, b: string | undefined) => {
        const name = (a ?? b)!;
        return isSecretEnvVar(name) ? '' : (base[name] ?? '');
      });
    }
    return out;
  }

  /** The repo's settings from config.json (matched by path), or {}. */
  settingsFor(repoId: string): RepoSettings {
    const r = this.get(repoId);
    if (!r || !this.opts.settings) return {};
    for (const [p, s] of Object.entries(this.opts.settings)) if (samePath(p, r.path)) return s;
    return {};
  }

  /** The test command for a repo: its configured `ci`, else `fallback` (--ci), else detected in `dir`. */
  testCommand(repoId: string, dir: string, fallback?: string): string | undefined {
    return this.settingsFor(repoId).ci ?? fallback ?? this.detectTestCommand(dir);
  }

  /**
   * Get a new worker worktree ready, once: copy the configured untracked files (e.g. .env) from the
   * main checkout, then run the repo's setup command (e.g. installing dependencies) in it. Recorded
   * in the worktree's meta, so a resumed or handed-over turn does not run it again. A failed setup
   * is reported, not thrown: the worker can still look at it and work around it.
   */
  async prepareWorktree(repoId: string, worktreeId: string): Promise<PrepareResult> {
    const r = this.require(repoId);
    const w = this.requireWorktree(repoId, worktreeId);
    const key = `${r.id}/${w.id}`;
    const meta = (this.ctx.store.data.worktreeMeta[key] ??= { createdAt: this.ctx.now() });
    const out: PrepareResult = { copied: [] };
    if (meta.prepared) return out;
    const s = this.settingsFor(repoId);
    for (const rel of s.copy ?? []) {
      const from = path.resolve(r.path, rel);
      const to = path.resolve(w.path, rel);
      // only paths inside the checkout, onto paths inside the worktree, never over existing files
      if (path.isAbsolute(rel) || !isInsideOrEqual(from, r.path) || from === path.resolve(r.path) || !isInsideOrEqual(to, w.path)) {
        this.ctx.log.warn(`repoSettings copy: ignoring ${rel} (must be a relative path inside the repository)`);
        continue;
      }
      if (!fs.existsSync(from) || fs.existsSync(to)) continue;
      ensureDir(path.dirname(to));
      fs.cpSync(from, to, { recursive: true, errorOnExist: false, force: false });
      out.copied.push(rel);
    }
    if (s.setup) {
      const t0 = Date.now();
      const timeoutMs = s.setupTimeoutMs ?? 600_000;
      // git transports stay disabled (as for agents and CI); package managers may use the network
      const res = await runShell(s.setup, { cwd: w.path, timeoutMs, env: withGitSafety(process.env, { CI: '1', FORCE_COLOR: '0', NO_COLOR: '1', ...this.envFor(repoId) }, { ceiling: path.dirname(path.resolve(w.path)) }) });
      const full = `${res.stdout}\n${res.stderr}${res.timedOut ? `\n(timed out after ${Math.round(timeoutMs / 1000)}s; process tree killed)` : ''}`;
      out.setup = { command: s.setup, ok: res.code === 0 && !res.timedOut, output: tailLines(full, 30, 2500), durationMs: Date.now() - t0 };
    }
    meta.prepared = true;
    this.ctx.store.markDirty();
    return out;
  }

  /** Run the repo's test command in a worktree (or the main checkout). */
  async runTests(repoId: string, worktreeId?: string, command?: string, timeoutMs = 300_000): Promise<TestResult> {
    const r = this.require(repoId);
    const cwd = worktreeId ? this.requireWorktree(repoId, worktreeId).path : r.path;
    const cmd = command ?? this.testCommand(repoId, cwd);
    if (!cmd) return { pass: true, code: 0, command: '(none)', output: 'no test command found', durationMs: 0, failures: [] };
    const t0 = Date.now();
    // the worktree's test scripts are agent-editable code: run them with git transports disabled
    // (a `git push` inside a test script fails) and kill the whole process tree on timeout
    const res = await runShell(cmd, { cwd, timeoutMs, env: withGitSafety(process.env, { CI: '1', FORCE_COLOR: '0', NO_COLOR: '1', ...this.envFor(repoId) }, { ceiling: path.dirname(path.resolve(cwd)) }) });
    const full = `${res.stdout}\n${res.stderr}${res.timedOut ? `\n(timed out after ${Math.round(timeoutMs / 1000)}s; process tree killed)` : ''}`;
    const output = tailLines(full, 40, 3000);
    return { pass: res.code === 0 && !res.timedOut, code: res.code, command: cmd, output, durationMs: Date.now() - t0, ...parseTestOutput(full) };
  }

  detectTestCommand(dir: string): string | undefined {
    const pkg = path.join(dir, 'package.json');
    if (fs.existsSync(pkg)) {
      try {
        const j = JSON.parse(fs.readFileSync(pkg, 'utf8')) as { scripts?: Record<string, string> };
        if (j.scripts?.test && !/no test specified/.test(j.scripts.test)) {
          // the package manager the repository uses (its lockfile), so workspaces resolve the same way
          if (fs.existsSync(path.join(dir, 'pnpm-lock.yaml'))) return 'pnpm -s test';
          if (fs.existsSync(path.join(dir, 'yarn.lock'))) return 'yarn test';
          if (fs.existsSync(path.join(dir, 'bun.lock')) || fs.existsSync(path.join(dir, 'bun.lockb'))) return 'bun run test';
          return 'npm test --silent';
        }
      } catch {
        /* ignore */
      }
    }
    if (fs.existsSync(path.join(dir, 'Cargo.toml'))) return 'cargo test';
    if (fs.existsSync(path.join(dir, 'go.mod'))) return 'go test ./...';
    if (fs.existsSync(path.join(dir, 'pyproject.toml')) || fs.existsSync(path.join(dir, 'pytest.ini'))) return 'python -m pytest -q';
    return undefined;
  }
}
