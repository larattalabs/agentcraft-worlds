// Pull requests for repositories that land work as PRs (repoSettings.land "pr"): which host a remote
// is on, and opening the PR with that host's own CLI (Azure DevOps: `az repos`, GitHub: `gh`), using
// the user's existing login for it. Only ever called after the user approved the task.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run } from './util/proc.js';

export type PrHost =
  | { kind: 'ado'; org: string; project: string; repo: string }
  | { kind: 'github'; owner: string; repo: string };

/** Recognise an Azure DevOps or GitHub remote URL (https or ssh). */
export function parseRemote(url: string): PrHost | undefined {
  const u = url.trim().replace(/\.git$/, '');
  let m = /^https:\/\/(?:[^@/]+@)?dev\.azure\.com\/([^/]+)\/([^/]+)\/_git\/([^/]+)$/i.exec(u);
  if (m) return { kind: 'ado', org: decodeURIComponent(m[1]!), project: decodeURIComponent(m[2]!), repo: decodeURIComponent(m[3]!) };
  m = /^https:\/\/(?:[^@/]+@)?([^./]+)\.visualstudio\.com\/(?:DefaultCollection\/)?([^/]+)\/_git\/([^/]+)$/i.exec(u);
  if (m) return { kind: 'ado', org: m[1]!, project: decodeURIComponent(m[2]!), repo: decodeURIComponent(m[3]!) };
  m = /^(?:ssh:\/\/)?git@ssh\.dev\.azure\.com[:/]v3\/([^/]+)\/([^/]+)\/([^/]+)$/i.exec(u);
  if (m) return { kind: 'ado', org: m[1]!, project: decodeURIComponent(m[2]!), repo: decodeURIComponent(m[3]!) };
  m = /^(?:https:\/\/(?:[^@/]+@)?github\.com\/|(?:ssh:\/\/)?git@github\.com[:/])([^/]+)\/([^/]+)$/i.exec(u);
  if (m) return { kind: 'github', owner: m[1]!, repo: m[2]! };
  return undefined;
}

export interface PrRequest {
  source: string;
  target: string;
  title: string;
  description: string;
  draft: boolean;
}

/** Web URL of an Azure DevOps PR. */
export function adoPrUrl(h: Extract<PrHost, { kind: 'ado' }>, id: number): string {
  const e = encodeURIComponent;
  return `https://dev.azure.com/${e(h.org)}/${e(h.project)}/_git/${e(h.repo)}/pullrequest/${id}`;
}

/** Open the PR; returns its URL. Throws with the CLI's own message when it fails. */
export async function openPullRequest(h: PrHost, pr: PrRequest, cwd: string, runFn: typeof run = run): Promise<string> {
  if (h.kind === 'ado') {
    const args = [
      'repos', 'pr', 'create',
      '--org', `https://dev.azure.com/${h.org}`, '--project', h.project, '--repository', h.repo,
      '--source-branch', pr.source, '--target-branch', pr.target,
      '--title', pr.title, '--description', pr.description,
      '--draft', pr.draft ? 'true' : 'false',
      '--output', 'json', '--only-show-errors',
    ];
    const res = await runFn('az', args, { cwd, timeoutMs: 120_000 });
    if (res.code !== 0) throw new Error(`az repos pr create failed: ${(res.stderr || res.stdout).trim().split('\n').slice(-3).join(' | ')}`);
    const id = (JSON.parse(res.stdout) as { pullRequestId?: number }).pullRequestId;
    if (!id) throw new Error('az repos pr create returned no pullRequestId');
    return adoPrUrl(h, id);
  }
  const args = ['pr', 'create', '--repo', `${h.owner}/${h.repo}`, '--head', pr.source, '--base', pr.target, '--title', pr.title, '--body', pr.description, ...(pr.draft ? ['--draft'] : [])];
  const res = await runFn('gh', args, { cwd, timeoutMs: 120_000 });
  if (res.code !== 0) throw new Error(`gh pr create failed: ${(res.stderr || res.stdout).trim().split('\n').slice(-3).join(' | ')}`);
  const url = res.stdout.trim().split('\n').filter((l) => /^https?:\/\//.test(l)).pop();
  if (!url) throw new Error('gh pr create printed no URL');
  return url;
}

// ---- watching an open pull request (prwatch.ts) -----------------------------------------------
//
// Reading: `az repos pr show` + the threads REST resource through `az devops invoke` + `az repos pr
// policy list` (Azure DevOps); `gh pr view --json` + `gh api .../pulls/<n>/comments` (GitHub).
// Writing (only after the user approved it, see prwatch.ts): a reply in a thread and a thread's
// status. Every call goes through an injectable runner (tests never reach a host).

export type RunFn = typeof run;

export type PrRef =
  | { host: 'ado'; org: string; project: string; repo: string; id: number }
  | { host: 'github'; owner: string; repo: string; id: number };

/** The PR a web URL points at (as openPullRequest returns them). */
export function parsePrUrl(url: string): PrRef | undefined {
  const u = url.trim();
  let m = /^https:\/\/(?:[^@/]+@)?dev\.azure\.com\/([^/]+)\/([^/]+)\/_git\/([^/]+)\/pullrequest\/(\d+)\/?(?:[?#].*)?$/i.exec(u);
  if (m) return { host: 'ado', org: decodeURIComponent(m[1]!), project: decodeURIComponent(m[2]!), repo: decodeURIComponent(m[3]!), id: Number(m[4]) };
  m = /^https:\/\/(?:[^@/]+@)?([^./]+)\.visualstudio\.com\/(?:DefaultCollection\/)?([^/]+)\/_git\/([^/]+)\/pullrequest\/(\d+)\/?(?:[?#].*)?$/i.exec(u);
  if (m) return { host: 'ado', org: m[1]!, project: decodeURIComponent(m[2]!), repo: decodeURIComponent(m[3]!), id: Number(m[4]) };
  m = /^https:\/\/github\.com\/([^/]+)\/([^/]+)\/pull\/(\d+)\/?(?:[?#].*)?$/i.exec(u);
  if (m) return { host: 'github', owner: m[1]!, repo: m[2]!, id: Number(m[3]) };
  return undefined;
}

export interface HostComment {
  id: number;
  author: string;
  /** ADO uniqueName / GitHub login */
  authorId?: string;
  text: string;
  /** a system comment (ADO commentType system: ref updates, status changes) */
  system: boolean;
  at?: number;
}

export interface HostThread {
  /** ADO: the thread id; GitHub: rc<root review comment id> | ic<issue comment id> | rv<review id> */
  id: string;
  /** still open (ADO active/pending; GitHub: always, resolution is not read) */
  active: boolean;
  /** the host's own status word (ADO: active, fixed, wontFix, closed, byDesign, pending, unknown) */
  status: string;
  file?: string;
  line?: number;
  comments: HostComment[];
  /** GitHub: what a reply posts to (the review-comment thread, or a new PR comment) */
  gh?: 'review-comment' | 'issue';
  /** GitHub review threads: the root comment id replies go to */
  replyTo?: number;
}

export interface HostPr {
  status: 'open' | 'changes' | 'approved' | 'merged' | 'abandoned';
  draft: boolean;
  checks: 'pending' | 'passing' | 'failing' | 'none';
  /** names of the failing checks */
  failing: string[];
  /** the source branch's tip on the host (someone may push to the PR branch) */
  headSha?: string;
  /** ADO: the repository id (GUID) for the REST resources */
  repoId?: string;
  /** ADO mergeStatus (succeeded, conflicts, ...) / GitHub mergeStateStatus */
  mergeStatus?: string;
  reviewers: Array<{ name: string; vote: number | string }>;
  threads: HostThread[];
}

export class PrHostError extends Error {}

function tail(res: { stdout: string; stderr: string }): string {
  return (res.stderr || res.stdout).trim().split('\n').slice(-3).join(' | ');
}

async function runJson<T>(runFn: RunFn, cmd: string, args: string[], cwd?: string): Promise<T> {
  const res = await runFn(cmd, args, { ...(cwd ? { cwd } : {}), timeoutMs: 120_000 });
  if (res.code !== 0) throw new PrHostError(`${cmd} ${args.slice(0, 3).join(' ')} failed: ${tail(res)}`);
  try {
    return JSON.parse(res.stdout) as T;
  } catch {
    throw new PrHostError(`${cmd} ${args.slice(0, 3).join(' ')} printed no JSON`);
  }
}

const adoOrg = (r: { org: string }) => `https://dev.azure.com/${r.org}`;
const ADO_STATUS = ['unknown', 'active', 'fixed', 'wontFix', 'closed', 'byDesign', 'pending'];

/** The argv that reads an ADO PR's threads. */
export function adoThreadsArgs(r: Extract<PrRef, { host: 'ado' }>, repoId: string): string[] {
  return ['devops', 'invoke', '--org', adoOrg(r), '--area', 'git', '--resource', 'pullRequestThreads', '--route-parameters', `project=${r.project}`, `repositoryId=${repoId}`, `pullRequestId=${r.id}`, '--http-method', 'GET', '--api-version', '7.1', '-o', 'json', '--only-show-errors'];
}

interface AdoPr {
  status?: string;
  isDraft?: boolean;
  mergeStatus?: string;
  reviewers?: Array<{ displayName?: string; uniqueName?: string; vote?: number }>;
  lastMergeSourceCommit?: { commitId?: string };
  repository?: { id?: string };
}
interface AdoThread {
  id: number;
  status?: string | number;
  isDeleted?: boolean;
  threadContext?: { filePath?: string; rightFileStart?: { line?: number }; leftFileStart?: { line?: number } } | null;
  comments?: Array<{ id: number; content?: string; commentType?: string | number; isDeleted?: boolean; publishedDate?: string; author?: { displayName?: string; uniqueName?: string } }>;
}
interface AdoPolicy {
  status?: string;
  configuration?: { isEnabled?: boolean; type?: { displayName?: string }; settings?: { displayName?: string } };
}

/** Build / status policies only ("Minimum number of reviewers" and the like are not checks). */
function adoChecks(evals: AdoPolicy[]): { checks: HostPr['checks']; failing: string[] } {
  const builds = evals.filter((e) => e.configuration?.isEnabled !== false && /build|status/i.test(e.configuration?.type?.displayName ?? '') && e.status !== 'notApplicable');
  if (!builds.length) return { checks: 'none', failing: [] };
  const name = (e: AdoPolicy) => e.configuration?.settings?.displayName || e.configuration?.type?.displayName || 'check';
  const failing = builds.filter((e) => e.status === 'rejected' || e.status === 'broken').map(name);
  if (failing.length) return { checks: 'failing', failing };
  return { checks: builds.every((e) => e.status === 'approved') ? 'passing' : 'pending', failing: [] };
}

function adoThread(t: AdoThread): HostThread | undefined {
  if (t.isDeleted) return undefined;
  const status = typeof t.status === 'number' ? (ADO_STATUS[t.status] ?? 'unknown') : (t.status ?? 'unknown');
  const comments: HostComment[] = (t.comments ?? [])
    .filter((c) => !c.isDeleted)
    .map((c) => ({
      id: c.id,
      author: c.author?.displayName ?? '?',
      ...(c.author?.uniqueName ? { authorId: c.author.uniqueName } : {}),
      text: c.content ?? '',
      system: c.commentType === 'system' || c.commentType === 3,
      ...at(c.publishedDate),
    }));
  const ctx = t.threadContext ?? undefined;
  const line = ctx?.rightFileStart?.line ?? ctx?.leftFileStart?.line;
  return { id: String(t.id), status, active: status === 'active' || status === 'pending', ...(ctx?.filePath ? { file: ctx.filePath.replace(/^\//, '') } : {}), ...(line ? { line } : {}), comments };
}

function adoState(pr: AdoPr): HostPr['status'] {
  if (pr.status === 'completed') return 'merged';
  if (pr.status === 'abandoned') return 'abandoned';
  const votes = (pr.reviewers ?? []).map((r) => r.vote ?? 0);
  if (votes.some((v) => v < 0)) return 'changes';
  if (votes.some((v) => v >= 5)) return 'approved';
  return 'open';
}

interface GhPr {
  state?: string;
  isDraft?: boolean;
  reviewDecision?: string;
  mergedAt?: string | null;
  headRefOid?: string;
  mergeStateStatus?: string;
  reviews?: Array<{ id?: string; author?: { login?: string }; body?: string; state?: string; submittedAt?: string }>;
  comments?: Array<{ id?: string; url?: string; author?: { login?: string }; body?: string; createdAt?: string }>;
  statusCheckRollup?: Array<{ __typename?: string; name?: string; context?: string; status?: string; conclusion?: string; state?: string }>;
}
interface GhReviewComment {
  id: number;
  in_reply_to_id?: number;
  path?: string;
  line?: number | null;
  original_line?: number | null;
  body?: string;
  user?: { login?: string };
  created_at?: string;
}

function ghChecks(rollup: GhPr['statusCheckRollup']): { checks: HostPr['checks']; failing: string[] } {
  const list = rollup ?? [];
  if (!list.length) return { checks: 'none', failing: [] };
  const name = (c: (typeof list)[number]) => c.name ?? c.context ?? 'check';
  const failing = list.filter((c) => /^(FAILURE|TIMED_OUT|CANCELLED|ACTION_REQUIRED|STARTUP_FAILURE|ERROR)$/.test(c.conclusion ?? c.state ?? '')).map(name);
  if (failing.length) return { checks: 'failing', failing };
  if (list.some((c) => (c.status && c.status !== 'COMPLETED') || /^(PENDING|EXPECTED)$/.test(c.state ?? ''))) return { checks: 'pending', failing: [] };
  return { checks: 'passing', failing: [] };
}

function at(s?: string | null): { at?: number } {
  return s && !Number.isNaN(Date.parse(s)) ? { at: Date.parse(s) } : {};
}

/** Read a PR's state, checks and comment threads from its host. Throws PrHostError when the CLI fails. */
export async function readPr(ref: PrRef, runFn: RunFn = run, cwd?: string): Promise<HostPr> {
  if (ref.host === 'ado') {
    const pr = await runJson<AdoPr>(runFn, 'az', ['repos', 'pr', 'show', '--id', String(ref.id), '--org', adoOrg(ref), '-o', 'json', '--only-show-errors'], cwd);
    const repoId = pr.repository?.id ?? ref.repo;
    const threads = await runJson<{ value?: AdoThread[] }>(runFn, 'az', adoThreadsArgs(ref, repoId), cwd);
    // build policies are optional (and the call may not be allowed): checks "none" then
    let checks: { checks: HostPr['checks']; failing: string[] } = { checks: 'none', failing: [] };
    try {
      checks = adoChecks(await runJson<AdoPolicy[]>(runFn, 'az', ['repos', 'pr', 'policy', 'list', '--id', String(ref.id), '--org', adoOrg(ref), '-o', 'json', '--only-show-errors'], cwd));
    } catch {
      /* no policy information */
    }
    return {
      status: adoState(pr),
      draft: !!pr.isDraft,
      ...checks,
      ...(pr.lastMergeSourceCommit?.commitId ? { headSha: pr.lastMergeSourceCommit.commitId } : {}),
      ...(pr.repository?.id ? { repoId: pr.repository.id } : {}),
      ...(pr.mergeStatus ? { mergeStatus: pr.mergeStatus } : {}),
      reviewers: (pr.reviewers ?? []).map((r) => ({ name: r.displayName ?? r.uniqueName ?? '?', vote: r.vote ?? 0 })),
      threads: (threads.value ?? []).map(adoThread).filter((t): t is HostThread => !!t),
    };
  }
  const url = `https://github.com/${ref.owner}/${ref.repo}/pull/${ref.id}`;
  const pr = await runJson<GhPr>(runFn, 'gh', ['pr', 'view', url, '--json', 'state,isDraft,reviewDecision,reviews,comments,statusCheckRollup,mergedAt,headRefOid,mergeStateStatus'], cwd);
  const rcs = await runJson<GhReviewComment[]>(runFn, 'gh', ['api', '--paginate', `repos/${ref.owner}/${ref.repo}/pulls/${ref.id}/comments`], cwd);
  const threads: HostThread[] = [];
  const roots = new Map<number, HostThread>();
  for (const c of [...rcs].sort((a, b) => a.id - b.id)) {
    const root = c.in_reply_to_id ?? c.id;
    let t = roots.get(root);
    if (!t) {
      const line = c.line ?? c.original_line ?? undefined;
      t = { id: `rc${root}`, active: true, status: 'active', ...(c.path ? { file: c.path } : {}), ...(line ? { line } : {}), comments: [], gh: 'review-comment', replyTo: root };
      roots.set(root, t);
      threads.push(t);
    }
    t.comments.push({ id: c.id, author: c.user?.login ?? '?', ...(c.user?.login ? { authorId: c.user.login } : {}), text: c.body ?? '', system: false, ...at(c.created_at) });
  }
  for (const c of pr.comments ?? []) {
    const num = Number(/#issuecomment-(\d+)/.exec(c.url ?? '')?.[1] ?? NaN);
    if (!Number.isFinite(num)) continue;
    threads.push({ id: `ic${num}`, active: true, status: 'active', comments: [{ id: num, author: c.author?.login ?? '?', ...(c.author?.login ? { authorId: c.author.login } : {}), text: c.body ?? '', system: false, ...at(c.createdAt) }], gh: 'issue' });
  }
  let n = 0;
  for (const r of pr.reviews ?? []) {
    n++;
    if (!r.body?.trim()) continue;
    threads.push({ id: `rv${r.id ?? n}`, active: true, status: r.state ?? 'COMMENTED', comments: [{ id: n, author: r.author?.login ?? '?', ...(r.author?.login ? { authorId: r.author.login } : {}), text: r.body, system: false, ...at(r.submittedAt) }], gh: 'issue' });
  }
  const status: HostPr['status'] = pr.state === 'MERGED' || pr.mergedAt ? 'merged' : pr.state === 'CLOSED' ? 'abandoned' : pr.reviewDecision === 'CHANGES_REQUESTED' ? 'changes' : pr.reviewDecision === 'APPROVED' ? 'approved' : 'open';
  return {
    status,
    draft: !!pr.isDraft,
    ...ghChecks(pr.statusCheckRollup),
    ...(pr.headRefOid ? { headSha: pr.headRefOid } : {}),
    ...(pr.mergeStateStatus ? { mergeStatus: pr.mergeStateStatus } : {}),
    reviewers: (pr.reviews ?? []).filter((r) => r.state && r.state !== 'COMMENTED').map((r) => ({ name: r.author?.login ?? '?', vote: r.state! })),
    threads,
  };
}

/** Write `body` to a temp JSON file for `az devops invoke --in-file`, run `fn`, remove the file. */
async function withJsonFile<T>(body: unknown, fn: (file: string) => Promise<T>): Promise<T> {
  const file = path.join(os.tmpdir(), `agentcraft-pr-${process.pid}-${Math.random().toString(36).slice(2)}.json`);
  fs.writeFileSync(file, JSON.stringify(body));
  try {
    return await fn(file);
  } finally {
    fs.rmSync(file, { force: true });
  }
}

/**
 * Post a reply in a thread; returns the new comment's id (so the next poll knows it is ours).
 * GitHub: a review-comment thread gets a threaded reply, anything else a new PR comment.
 */
export async function replyToThread(ref: PrRef, thread: Pick<HostThread, 'id' | 'gh' | 'replyTo'>, text: string, repoId: string | undefined, runFn: RunFn = run, cwd?: string): Promise<number | undefined> {
  if (ref.host === 'ado') {
    const res = await withJsonFile({ content: text, parentCommentId: 1, commentType: 1 }, (file) =>
      runJson<{ id?: number }>(runFn, 'az', ['devops', 'invoke', '--org', adoOrg(ref), '--area', 'git', '--resource', 'pullRequestThreadComments', '--route-parameters', `project=${ref.project}`, `repositoryId=${repoId ?? ref.repo}`, `pullRequestId=${ref.id}`, `threadId=${thread.id}`, '--http-method', 'POST', '--in-file', file, '--api-version', '7.1', '-o', 'json', '--only-show-errors'], cwd),
    );
    return res.id;
  }
  const endpoint = thread.gh === 'review-comment' && thread.replyTo ? `repos/${ref.owner}/${ref.repo}/pulls/${ref.id}/comments/${thread.replyTo}/replies` : `repos/${ref.owner}/${ref.repo}/issues/${ref.id}/comments`;
  const res = await runJson<{ id?: number }>(runFn, 'gh', ['api', '--method', 'POST', endpoint, '-f', `body=${text}`], cwd);
  return res.id;
}

/**
 * Set a thread's status (ADO: fixed / closed / wontFix). Returns false where this is not done
 * (GitHub: resolving a review thread needs the GraphQL API).
 */
export async function setThreadStatus(ref: PrRef, threadId: string, status: 'fixed' | 'closed' | 'wontFix', repoId: string | undefined, runFn: RunFn = run, cwd?: string): Promise<boolean> {
  if (ref.host !== 'ado') return false;
  await withJsonFile({ status }, (file) =>
    runJson<unknown>(runFn, 'az', ['devops', 'invoke', '--org', adoOrg(ref), '--area', 'git', '--resource', 'pullRequestThreads', '--route-parameters', `project=${ref.project}`, `repositoryId=${repoId ?? ref.repo}`, `pullRequestId=${ref.id}`, `threadId=${threadId}`, '--http-method', 'PATCH', '--in-file', file, '--api-version', '7.1', '-o', 'json', '--only-show-errors'], cwd),
  );
  return true;
}
