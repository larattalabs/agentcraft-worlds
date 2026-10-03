// Pull requests for repositories that land work as PRs (repoSettings.land "pr"): which host a remote
// is on, and opening the PR with that host's own CLI (Azure DevOps: `az repos`, GitHub: `gh`), using
// the user's existing login for it. Only ever called after the user approved the task.
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
