// Read-only probe of a pull request the way the PR watcher sees it (prwatch.ts), to check the host
// adapter and the review parser against a real PR before any goal lands one:
//
//   npm run pr-probe -- https://dev.azure.com/contoso/Notes/_git/pocket-notes/pullrequest/101
//   npm run pr-probe -- <url> --json     (the raw normalized threads too)
//
// Runs only reads with your own az / gh login: az repos pr show, az devops invoke (GET threads),
// az repos pr policy list; gh pr view, gh api (GET). Never posts, never changes the PR.
import { classify } from '../src/prwatch.js';
import { findingCounts } from '../src/prreview.js';
import { parsePrUrl, readPr } from '../src/prs.js';

const args = process.argv.slice(2);
const url = args.find((a) => !a.startsWith('--'));
const ref = url ? parsePrUrl(url) : undefined;
if (!ref) {
  console.error('usage: npm run pr-probe -- <Azure DevOps or GitHub pull request URL> [--json]');
  process.exit(2);
}
const host = await readPr(ref);
const c = classify(host, {}, new Set());
const out = {
  pr: { host: ref.host, id: ref.id, status: host.status, draft: host.draft, checks: host.checks, failing: host.failing, headSha: host.headSha, mergeStatus: host.mergeStatus, repoId: host.repoId, reviewers: host.reviewers },
  threads: { total: host.threads.length, open: c.open, ignored: c.ignored, human: c.human.length },
  human: c.human.map((h) => ({ thread: h.thread.id, status: h.thread.status, at: h.thread.file ? `${h.thread.file}${h.thread.line ? `:${h.thread.line}` : ''}` : undefined, comments: h.fresh.map((x) => `${x.author}: ${x.text.slice(0, 160)}`) })),
  review: c.review
    ? {
        thread: c.review.thread.id,
        status: c.review.thread.status,
        verdict: c.review.parsed.verdict,
        explanation: c.review.parsed.explanation,
        counts: findingCounts(c.review.parsed.findings),
        findings: c.review.parsed.findings.map((f) => ({ severity: f.severity, at: f.file ? `${f.file}${f.line ? `:${f.line}` : ''}` : undefined, confidence: f.confidence, text: f.text.slice(0, 200) })),
      }
    : undefined,
  ...(args.includes('--json') ? { raw: host.threads } : {}),
};
console.log(JSON.stringify(out, null, 2));
