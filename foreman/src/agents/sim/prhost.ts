// SimPrHost: a fake Azure DevOps for the sim backend's pull requests (docs/PRWATCH.md, contract S3).
//
// It answers exactly the `az` command lines prs.ts runs (opening a PR, reading it, its threads and
// its build policy, posting a reply, setting a thread's status) from state kept in state.json
// (backend.simprs), so the real PR watcher, review parser, triage and decisions run unchanged on top
// of it. Nothing here starts a process that reaches a network: the runner never spawns `az` or `gh`.
//
// Each PR follows a speed-scaled timeline:
//   opened (checks pending) -> checks passing + an automated "Claude Code Review" thread (WARN, one
//   important finding) + a changelog-draft thread + a reviewer's question on the changed file
//   -> [review fixes pushed: checks pending again -> passing + a new review (PASS, minor only)]
//   -> the reviewer approves (vote 10) once the PR has been quiet for a while and AgentCraft has
//   nothing in flight on it (triage, fold-in, an open decision) -> completed (merged) a bit later.
// System threads (votes, pushes, completion) come from the TFS identity, as on a real host.
import fs from 'node:fs';
import type { Foreman } from '../../foreman.js';
import { adoPrUrl, type PrHost, type RunFn } from '../../prs.js';
import { REVIEW_MARKER, CHANGELOG_MARKER } from '../../prreview.js';
import { git } from '../../util/git.js';
import { userName } from '../../user.js';

export const SIM_ORG = 'contoso';
export const SIM_PROJECT = 'Notes';
/** the automated reviewer's identity (ADO's standard build identity) */
export const SIM_BOT = { displayName: `Project Collection Build Service (${SIM_ORG})`, uniqueName: `Build\\${SIM_ORG}` };
export const SIM_REVIEWER = { displayName: 'Dana Reviewer', uniqueName: `dana@${SIM_ORG}.example` };
const TFS = { displayName: 'Microsoft.VisualStudio.Services.TFS', uniqueName: 'tfs' };

/** sim-milliseconds (divided by the sim speed) */
export const SIM_PR_TIMING = { checks: 15_000, quietBeforeApproval: 25_000, mergeAfterApproval: 15_000 };

interface SimComment {
  id: number;
  content: string;
  commentType: 'text' | 'system';
  author: { displayName: string; uniqueName: string };
  publishedDate: string;
}
interface SimThread {
  id: number;
  status: string;
  threadContext: { filePath: string; rightFileStart: { line: number }; rightFileEnd: { line: number } } | null;
  comments: SimComment[];
}
export interface SimPr {
  id: number;
  url: string;
  repo: string;
  repoPath: string;
  source: string;
  target: string;
  title: string;
  status: 'active' | 'completed' | 'abandoned';
  vote: number;
  policy: 'queued' | 'running' | 'approved' | 'rejected';
  head?: string;
  /** files the PR changes (for the review's file references) */
  files: string[];
  threads: SimThread[];
  /** automated review runs posted */
  reviews: number;
  /** checks (and a review run) finish at this time; undefined: nothing running */
  checksAt?: number;
  /** quiet since (approval waits for quietBeforeApproval after the last activity) */
  quietSince: number;
  completeAt?: number;
}
interface HostState {
  next: number;
  prs: Record<string, SimPr>;
}

export interface SimPrHostHooks {
  /** something on the PR changed (a webhook, so to say): the watcher should poll it now */
  changed(pr: SimPr): void;
  /** AgentCraft has work in flight on this PR (triage, fold-in, an open decision): no approval yet */
  busy(pr: SimPr): boolean;
}

type RunResult = Awaited<ReturnType<RunFn>>;
const ok = (o: unknown): RunResult => ({ code: 0, stdout: JSON.stringify(o), stderr: '', timedOut: false });
const fail = (msg: string): RunResult => ({ code: 1, stdout: '', stderr: `ERROR: ${msg}`, timedOut: false });

export class SimPrHost {
  /** every command the host was asked to run (tests read it) */
  readonly calls: Array<{ cmd: string; args: string[] }> = [];
  private timer: NodeJS.Timeout | undefined;

  constructor(
    private fm: Foreman,
    private speed: () => number,
    private hooks: SimPrHostHooks,
  ) {}

  private get data(): HostState {
    const b = this.fm.store.data.backend;
    const d = (b.simprs ??= { next: 600, prs: {} }) as HostState;
    d.prs ??= {};
    d.next ??= 600;
    return d;
  }

  /** The host a sim repo's PRs go to (every repo under the sim: no real host is ever reached). */
  hostFor(repoId: string): PrHost {
    return { kind: 'ado', org: SIM_ORG, project: SIM_PROJECT, repo: this.fm.repos.get(repoId)?.name ?? repoId };
  }

  prs(): SimPr[] {
    return Object.values(this.data.prs);
  }

  pr(url: string): SimPr | undefined {
    return this.prs().find((p) => p.url === url);
  }

  start(): void {
    this.stop();
    this.timer = setInterval(() => void this.tick(), Math.max(20, Math.round(500 / Math.max(0.05, this.speed()))));
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  private ms(simMs: number): number {
    return simMs / Math.max(0.05, this.speed());
  }

  private now(): number {
    return Date.now();
  }

  private comment(id: number, author: SimComment['author'], content: string, system = false): SimComment {
    return { id, content, commentType: system ? 'system' : 'text', author, publishedDate: new Date(this.now()).toISOString() };
  }

  private addThread(pr: SimPr, author: SimComment['author'], content: string, opts: { system?: boolean; file?: string; line?: number } = {}): SimThread {
    const id = pr.threads.reduce((m, t) => Math.max(m, t.id), 0) + 1;
    const t: SimThread = {
      id,
      status: opts.system ? 'unknown' : 'active',
      threadContext: opts.file ? { filePath: `/${opts.file}`, rightFileStart: { line: opts.line ?? 1 }, rightFileEnd: { line: opts.line ?? 1 } } : null,
      comments: [this.comment(1, author, content, opts.system)],
    };
    pr.threads.push(t);
    return t;
  }

  // ---- the az command lines (prs.ts) ---------------------------------------------------------

  /** The RunFn the PR watcher and the repo manager use under the sim. */
  readonly run: RunFn = async (cmd, args, opts) => {
    this.calls.push({ cmd, args: [...args] });
    if (cmd !== 'az') return fail(`the sim PR host only answers az, not ${cmd}`);
    try {
      return await this.az(args, opts?.cwd);
    } catch (e) {
      return fail((e as Error).message);
    }
  };

  private arg(args: string[], name: string): string | undefined {
    const i = args.indexOf(name);
    return i >= 0 ? args[i + 1] : undefined;
  }

  private route(args: string[], key: string): string | undefined {
    return args.find((a) => a.startsWith(`${key}=`))?.slice(key.length + 1);
  }

  private find(id: number): SimPr {
    const pr = this.data.prs[String(id)];
    if (!pr) throw new Error(`TF401180: The requested pull request was not found (${id})`);
    return pr;
  }

  private async az(args: string[], cwd?: string): Promise<RunResult> {
    if (args[0] === 'repos' && args[1] === 'pr' && args[2] === 'create') return ok(await this.create(args, cwd));
    if (args[0] === 'repos' && args[1] === 'pr' && args[2] === 'show') return ok(this.show(this.find(Number(this.arg(args, '--id')))));
    if (args[0] === 'repos' && args[1] === 'pr' && args[2] === 'policy') return ok(this.policies(this.find(Number(this.arg(args, '--id')))));
    if (args[0] === 'devops' && args[1] === 'invoke') {
      const pr = this.find(Number(this.route(args, 'pullRequestId')));
      const resource = this.arg(args, '--resource');
      const method = this.arg(args, '--http-method');
      const file = this.arg(args, '--in-file');
      const body = file ? (JSON.parse(fs.readFileSync(file, 'utf8')) as Record<string, unknown>) : {};
      const threadId = Number(this.route(args, 'threadId'));
      if (resource === 'pullRequestThreads' && method === 'GET') return ok({ count: pr.threads.length, value: pr.threads });
      const t = pr.threads.find((x) => x.id === threadId);
      if (!t) throw new Error(`thread ${threadId} not found on PR ${pr.id}`);
      if (resource === 'pullRequestThreadComments' && method === 'POST') {
        const id = t.comments.reduce((m, c) => Math.max(m, c.id), 0) + 1;
        // what AgentCraft posts appears under the user's own name (their az login)
        t.comments.push(this.comment(id, { displayName: userName(), uniqueName: `you@${SIM_ORG}.example` }, String(body.content ?? '')));
        pr.quietSince = this.now();
        this.fm.store.markDirty();
        return ok({ id, content: body.content, parentCommentId: body.parentCommentId ?? 0 });
      }
      if (resource === 'pullRequestThreads' && method === 'PATCH') {
        t.status = String(body.status ?? t.status);
        pr.quietSince = this.now();
        this.fm.store.markDirty();
        return ok({ id: t.id, status: t.status });
      }
    }
    throw new Error(`the sim PR host does not know "az ${args.slice(0, 3).join(' ')}"`);
  }

  private async create(args: string[], cwd?: string): Promise<{ pullRequestId: number; url: string }> {
    const d = this.data;
    const id = ++d.next;
    const repo = this.arg(args, '--repository') ?? '?';
    const source = this.arg(args, '--source-branch') ?? '?';
    const target = this.arg(args, '--target-branch') ?? 'main';
    const url = adoPrUrl({ kind: 'ado', org: SIM_ORG, project: SIM_PROJECT, repo }, id);
    const now = this.now();
    d.prs[String(id)] = {
      id,
      url,
      repo,
      repoPath: cwd ?? '',
      source,
      target,
      title: this.arg(args, '--title') ?? `PR ${id}`,
      status: 'active',
      vote: 0,
      // the checks start with the push notification (pushed()), which also says what changed
      policy: 'queued',
      files: [],
      threads: [],
      reviews: 0,
      quietSince: now,
    };
    this.fm.store.markDirty();
    return { pullRequestId: id, url };
  }

  private show(pr: SimPr): Record<string, unknown> {
    return {
      pullRequestId: pr.id,
      title: pr.title,
      status: pr.status,
      isDraft: false,
      mergeStatus: 'succeeded',
      sourceRefName: `refs/heads/${pr.source}`,
      targetRefName: `refs/heads/${pr.target}`,
      reviewers: [{ displayName: SIM_REVIEWER.displayName, uniqueName: SIM_REVIEWER.uniqueName, vote: pr.vote }],
      ...(pr.head ? { lastMergeSourceCommit: { commitId: pr.head } } : {}),
      repository: { id: `sim-${pr.repo}`, name: pr.repo },
    };
  }

  private policies(pr: SimPr): unknown[] {
    return [
      { status: pr.policy, configuration: { isEnabled: true, type: { displayName: 'Build' }, settings: { displayName: `${pr.repo} CI` } } },
      { status: pr.vote >= 5 ? 'approved' : 'queued', configuration: { isEnabled: true, type: { displayName: 'Minimum number of reviewers' } } },
    ];
  }

  // ---- what AgentCraft tells the host (a push) -------------------------------------------------

  /** AgentCraft pushed to the PR's branch (opened it, or review fixes): checks run again. */
  async pushed(url: string, sha: string | undefined, opened: boolean): Promise<void> {
    const pr = this.pr(url);
    if (!pr || pr.status !== 'active') return;
    if (sha) pr.head = sha;
    pr.files = await this.changedFiles(pr);
    const now = this.now();
    if (!opened) {
      this.addThread(pr, TFS, `${userName()} updated the pull request (pushed ${sha ? sha.slice(0, 7) : 'a commit'})`, { system: true });
      pr.vote = 0;
      delete pr.completeAt;
    }
    pr.policy = 'running';
    pr.checksAt = now + this.ms(SIM_PR_TIMING.checks);
    pr.quietSince = now;
    this.fm.store.markDirty();
    this.hooks.changed(pr);
  }

  private async changedFiles(pr: SimPr): Promise<string[]> {
    if (!pr.repoPath || !pr.head) return pr.files;
    // local git only: the pushed commit against the target branch as the repo knows it
    const res = await git(pr.repoPath, ['diff', '--name-only', `refs/remotes/origin/${pr.target}`, pr.head], { allowFail: true });
    const files = res.code === 0 ? res.stdout.split('\n').map((s) => s.trim()).filter(Boolean) : [];
    return files.length ? files : pr.files;
  }

  // ---- the timeline ----------------------------------------------------------------------------

  /** Advance every open PR (run by the timer; tests may call it). */
  async tick(now = this.now()): Promise<void> {
    for (const pr of this.prs()) {
      if (pr.status !== 'active') continue;
      let changed = false;
      if (pr.checksAt !== undefined && now >= pr.checksAt) {
        delete pr.checksAt;
        pr.policy = 'approved';
        this.postReview(pr);
        pr.quietSince = now;
        changed = true;
      }
      const busy = pr.checksAt !== undefined || this.hooks.busy(pr);
      if (!changed && !busy && pr.vote < 5 && pr.reviews > 0 && now - pr.quietSince >= this.ms(SIM_PR_TIMING.quietBeforeApproval)) {
        pr.vote = 10;
        this.addThread(pr, TFS, `${SIM_REVIEWER.displayName} voted 10`, { system: true });
        pr.completeAt = now + this.ms(SIM_PR_TIMING.mergeAfterApproval);
        changed = true;
      }
      if (!changed && pr.vote >= 5 && pr.completeAt !== undefined && now >= pr.completeAt && !busy) {
        pr.status = 'completed';
        delete pr.completeAt;
        this.addThread(pr, TFS, `${SIM_REVIEWER.displayName} completed the pull request`, { system: true });
        changed = true;
      }
      if (changed) {
        this.fm.store.markDirty();
        this.hooks.changed(pr);
      }
    }
  }

  private postReview(pr: SimPr): void {
    pr.reviews++;
    const file = pr.files.find((f) => f.endsWith('.md')) ?? pr.files[0];
    this.addThread(pr, SIM_BOT, simReviewBody(pr.reviews, { title: pr.title, file, files: pr.files.length, at: new Date(this.now()) }));
    if (pr.reviews === 1) {
      this.addThread(pr, SIM_BOT, `${CHANGELOG_MARKER}\n**Changelog draft**\n- ${pr.title}`);
      this.addThread(pr, SIM_REVIEWER, `Could this also point to the API overview in the README, so people find it from there?`, file ? { file, line: 1 } : {});
    }
  }
}

/**
 * An automated review in the "Claude Code Review" renderer's format (the same as
 * test/fixtures/claude-review): run 1 is a WARN with one important finding, later runs PASS with a
 * minor note only.
 */
export function simReviewBody(run: number, o: { title: string; file?: string; files: number; at: Date }): string {
  const f = o.file ? `\`${o.file}\`` : 'the change';
  const at = o.at.toISOString().replace(/\.\d{3}Z$/, 'Z');
  const first = run === 1;
  const lines = [
    REVIEW_MARKER,
    `Review completed at ${at} UTC`,
    '',
    '<details open>',
    '<summary><b>📝 Code Review</b> (click to expand)</summary>',
    '',
    `**${o.title}**`,
    '',
    '### 📊 Summary',
    `- ${o.files || 1} file${o.files === 1 ? '' : 's'} changed${first ? '' : ' (review fixes added)'}`,
    '',
    `*Files examined: ${o.files || 1}*`,
    '',
    '### 🔍 Critical Findings',
    '*No critical issues found.*',
    '',
    '### ⚠️ Important Suggestions',
    ...(first
      ? [
          `- **${o.file ? `\`${o.file}:5\`` : f}** — The note says what to check but not how: name the command that runs the tests.`,
          '',
          '  *Impact:* Someone picking this up cannot verify the goal without reading the code.',
          '',
          '  Add the exact command (`npm test`) next to the check.',
        ]
      : ['*No important suggestions.*']),
    '',
    '### 💡 Minor Improvements',
    `- **${f}** — The heading repeats the goal word for word; a shorter title reads better in the docs index.`,
    '',
    ...(first
      ? [
          '### 📚 Teachable Moments',
          '- **Checklists** — A check that names its command can be run by anyone.',
          '',
          '  *Best practice:* Write the exact command next to each check.',
          '',
        ]
      : []),
    '### ✅ Testing Recommendations',
    '- **Docs check:** Run `npm test` after editing docs to make sure nothing else changed.',
    '',
    '### 🎯 Verdict',
    first ? '**WARN**' : '**PASS**',
    '',
    first ? '*Small docs change; one important clarity fix.*' : '*The note is clear now; only a minor suggestion left.*',
    '',
    '---',
    '*Review completed using Claude AI with structured output validation.*',
    '',
    '</details>',
  ];
  return lines.join('\n');
}
