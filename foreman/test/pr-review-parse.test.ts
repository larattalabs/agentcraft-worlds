// The automated "Claude Code Review" comments (a review pipeline) and the PR host adapters (az / gh),
// with a fake command runner: nothing here reaches a host.
//
// The fixtures in fixtures/claude-review follow the markdown a Claude review pipeline renders from
// structured review JSON, wrapped the way it posts them as a PR comment.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { findingCounts, isAutomatedReview, parseAutomatedReview } from '../src/prreview.js';
import { parsePrUrl, readPr, replyToThread, setThreadStatus, type RunFn } from '../src/prs.js';

const fixture = (name: string) => fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures', 'claude-review', name), 'utf8');

describe('Claude Code Review comments', () => {
  it('parses a PASS review with only minor items (rendered by the pipeline)', () => {
    const r = parseAutomatedReview(fixture('pass.md'))!;
    expect(r.verdict).toBe('PASS');
    expect(r.explanation).toBe('Small, well-scoped change with tests; only minor suggestions.');
    expect(findingCounts(r.findings)).toBe('1 testing, 2 minor, 1 teachable');
    expect(r.findings.find((f) => f.severity === 'minor')).toEqual({
      severity: 'minor',
      file: 'src/components/TagFilter.tsx',
      text: 'The onChange handler re-creates the tag array on every keystroke; useMemo would avoid re-renders of NoteList.',
    });
    expect(r.findings.filter((f) => f.severity === 'minor')[1]!.file).toBeUndefined();
    expect(r.findings.find((f) => f.severity === 'testing')!.text).toBe('Unit test: Cover an empty tag list and a tag with spaces in parseTagQuery.');
    // "*No critical issues found.*" and friends are empty sections, the summary is not a finding
    expect(r.findings.some((f) => f.severity === 'critical' || f.severity === 'important')).toBe(false);
  });

  it('parses a FAIL review: file:line, confidence badges, code snippets with "- " lines, fixes', () => {
    const r = parseAutomatedReview(fixture('fail.md'))!;
    expect(r.verdict).toBe('FAIL');
    expect(findingCounts(r.findings)).toBe('1 critical, 2 important, 2 testing, 1 performance');
    const crit = r.findings.find((f) => f.severity === 'critical')!;
    expect(crit.file).toBe('Pocket.Api/Controllers/ExportController.cs');
    expect(crit.line).toBe(42);
    expect(crit.confidence).toBeUndefined();
    expect(crit.text).toMatch(/^The export endpoint has no \[Authorize\] attribute, .* Fix: Add \[Authorize\(Roles = "Admin"\)\] to the action/);
    expect(crit.text).not.toContain('ToListAsync'); // the code snippet is not part of the line
    const [csv, button] = r.findings.filter((f) => f.severity === 'important');
    expect(csv).toMatchObject({ file: 'Pocket.Api/Services/CsvWriter.cs', line: 17, confidence: 'medium' });
    expect(csv!.text).toContain('breaks the CSV <Generic> rows. Impact: Corrupted exports');
    expect(button).toMatchObject({ file: 'src/pages/Notes.tsx', confidence: 'low' });
    expect(button!.line).toBeUndefined();
    expect(r.findings.find((f) => f.severity === 'performance')!.file).toBe('Pocket.Api/Controllers/ExportController.cs');
  });

  it('uses only the final block of a context-aware review', () => {
    const r = parseAutomatedReview(fixture('context-aware.md'))!;
    expect(r.verdict).toBe('PASS');
    expect(r.findings.some((f) => f.severity === 'critical')).toBe(false);
  });

  it('recognises reviews only by their header', () => {
    expect(isAutomatedReview('  **Claude Code Review**\nReview completed')).toBe(true);
    expect(parseAutomatedReview('<!-- changelog-draft -->\n## Changelog')).toBeUndefined();
    expect(parseAutomatedReview('LGTM')).toBeUndefined();
  });
});

describe('pull request URLs', () => {
  it('parses Azure DevOps and GitHub PR URLs', () => {
    expect(parsePrUrl('https://dev.azure.com/contoso/Notes/_git/pocket-notes/pullrequest/101')).toEqual({ host: 'ado', org: 'contoso', project: 'Notes', repo: 'pocket-notes', id: 101 });
    expect(parsePrUrl('https://dev.azure.com/o/My%20Project/_git/r/pullrequest/7')).toEqual({ host: 'ado', org: 'o', project: 'My Project', repo: 'r', id: 7 });
    expect(parsePrUrl('https://github.com/o/r/pull/9')).toEqual({ host: 'github', owner: 'o', repo: 'r', id: 9 });
    expect(parsePrUrl('https://example.com/pr/1')).toBeUndefined();
  });
});

type Call = { cmd: string; args: string[]; inFile?: unknown };
function fakeRunner(reply: (cmd: string, args: string[]) => unknown, calls: Call[]): RunFn {
  return (async (cmd: string, args: string[]) => {
    const i = args.indexOf('--in-file');
    const inFile = i >= 0 ? JSON.parse(fs.readFileSync(args[i + 1]!, 'utf8')) : undefined;
    calls.push({ cmd, args, ...(inFile !== undefined ? { inFile } : {}) });
    const out = reply(cmd, args);
    if (out instanceof Error) return { code: 1, stdout: '', stderr: out.message, timedOut: false };
    return { code: 0, stdout: JSON.stringify(out), stderr: '', timedOut: false };
  }) as RunFn;
}

const ADO = { host: 'ado' as const, org: 'contoso', project: 'Notes', repo: 'pocket-notes', id: 101 };
const ORG = 'https://dev.azure.com/contoso';

describe('Azure DevOps adapter', () => {
  it('reads status, votes, checks and threads with az', async () => {
    const calls: Call[] = [];
    const run = fakeRunner((_cmd, args) => {
      if (args[1] === 'pr' && args[2] === 'show') return { status: 'active', isDraft: false, mergeStatus: 'succeeded', reviewers: [{ displayName: 'Dana', vote: -5 }], lastMergeSourceCommit: { commitId: 'abc123' }, repository: { id: 'guid-1' } };
      if (args[0] === 'devops')
        return {
          count: 3,
          value: [
            { id: 11, status: 'active', threadContext: null, comments: [{ id: 1, content: fixture('fail.md'), commentType: 'text', author: { displayName: 'Project Collection Build Service (contoso)', uniqueName: 'Build\\x' }, publishedDate: '2026-10-03T15:04:05Z' }] },
            { id: 12, status: 2, threadContext: { filePath: '/src/a.ts', rightFileStart: { line: 4 } }, comments: [{ id: 1, content: 'why?', commentType: 1, author: { displayName: 'Dana', uniqueName: 'dana@contoso.com' } }, { id: 2, content: 'gone', isDeleted: true }] },
            { id: 13, comments: [{ id: 1, content: 'Sam updated the pull request', commentType: 'system', author: { displayName: 'Microsoft.VisualStudio.Services.TFS' } }] },
            { id: 14, isDeleted: true, comments: [] },
          ],
        };
      return [
        { status: 'rejected', configuration: { type: { displayName: 'Build' }, settings: { displayName: 'Claude Code Review' } } },
        { status: 'approved', configuration: { type: { displayName: 'Minimum number of reviewers' } } },
      ];
    }, calls);
    const pr = await readPr(ADO, run);
    expect(calls.map((c) => [c.cmd, ...c.args])).toEqual([
      ['az', 'repos', 'pr', 'show', '--id', '101', '--org', ORG, '-o', 'json', '--only-show-errors'],
      ['az', 'devops', 'invoke', '--org', ORG, '--area', 'git', '--resource', 'pullRequestThreads', '--route-parameters', 'project=Notes', 'repositoryId=guid-1', 'pullRequestId=101', '--http-method', 'GET', '--api-version', '7.1', '-o', 'json', '--only-show-errors'],
      ['az', 'repos', 'pr', 'policy', 'list', '--id', '101', '--org', ORG, '-o', 'json', '--only-show-errors'],
    ]);
    expect(pr).toMatchObject({ status: 'changes', checks: 'failing', failing: ['Claude Code Review'], headSha: 'abc123', repoId: 'guid-1', mergeStatus: 'succeeded' });
    expect(pr.threads.map((t) => [t.id, t.status, t.active, t.file, t.line, t.comments.length])).toEqual([
      ['11', 'active', true, undefined, undefined, 1],
      ['12', 'fixed', false, 'src/a.ts', 4, 1],
      ['13', 'unknown', false, undefined, undefined, 1],
    ]);
    expect(pr.threads[2]!.comments[0]!.system).toBe(true);
  });

  it('maps completed / abandoned / approvals, and survives a policy call that fails', async () => {
    const show = (o: object) => fakeRunner((_c, args) => (args[2] === 'show' ? o : args[0] === 'devops' ? { value: [] } : new Error('TF400813: not authorized')), []);
    expect((await readPr(ADO, show({ status: 'completed' }))).status).toBe('merged');
    expect((await readPr(ADO, show({ status: 'abandoned' }))).status).toBe('abandoned');
    const ok = await readPr(ADO, show({ status: 'active', reviewers: [{ vote: 10 }, { vote: 0 }] }));
    expect(ok).toMatchObject({ status: 'approved', checks: 'none' });
    await expect(readPr(ADO, fakeRunner(() => new Error('ERROR: Please run az login'), []))).rejects.toThrow(/az repos pr show failed: ERROR: Please run az login/);
  });

  it('posts a reply and sets a thread status through az devops invoke', async () => {
    const calls: Call[] = [];
    const run = fakeRunner((_c, args) => (args.includes('POST') ? { id: 3 } : { id: 12, status: 'fixed' }), calls);
    expect(await replyToThread(ADO, { id: '12' }, 'Fixed in 1a2b3c4.', 'guid-1', run)).toBe(3);
    expect(await setThreadStatus(ADO, '12', 'fixed', 'guid-1', run)).toBe(true);
    const strip = (c: Call) => [c.cmd, ...c.args.map((a, i) => (c.args[i - 1] === '--in-file' ? '<file>' : a))];
    expect(strip(calls[0]!)).toEqual(['az', 'devops', 'invoke', '--org', ORG, '--area', 'git', '--resource', 'pullRequestThreadComments', '--route-parameters', 'project=Notes', 'repositoryId=guid-1', 'pullRequestId=101', 'threadId=12', '--http-method', 'POST', '--in-file', '<file>', '--api-version', '7.1', '-o', 'json', '--only-show-errors']);
    expect(calls[0]!.inFile).toEqual({ content: 'Fixed in 1a2b3c4.', parentCommentId: 1, commentType: 1 });
    expect(strip(calls[1]!)).toEqual(['az', 'devops', 'invoke', '--org', ORG, '--area', 'git', '--resource', 'pullRequestThreads', '--route-parameters', 'project=Notes', 'repositoryId=guid-1', 'pullRequestId=101', 'threadId=12', '--http-method', 'PATCH', '--in-file', '<file>', '--api-version', '7.1', '-o', 'json', '--only-show-errors']);
    expect(calls[1]!.inFile).toEqual({ status: 'fixed' });
  });
});

describe('GitHub adapter', () => {
  const GH = { host: 'github' as const, owner: 'o', repo: 'r', id: 9 };

  it('reads state, checks, reviews and comment threads with gh', async () => {
    const calls: Call[] = [];
    const run = fakeRunner((_c, args) => {
      if (args[0] === 'pr')
        return {
          state: 'OPEN',
          isDraft: false,
          reviewDecision: 'CHANGES_REQUESTED',
          headRefOid: 'def456',
          reviews: [{ id: 'PRR_1', author: { login: 'dana' }, body: 'A few things', state: 'CHANGES_REQUESTED' }, { id: 'PRR_2', author: { login: 'eli' }, body: '', state: 'APPROVED' }],
          comments: [{ id: 'IC_x', url: 'https://github.com/o/r/pull/9#issuecomment-555', author: { login: 'dana' }, body: 'Also the docs' }],
          statusCheckRollup: [{ __typename: 'CheckRun', name: 'test', status: 'COMPLETED', conclusion: 'FAILURE' }, { __typename: 'StatusContext', context: 'lint', state: 'SUCCESS' }],
        };
      return [[{ id: 100, path: 'src/a.ts', line: 3, body: 'off by one', user: { login: 'dana' } }], [{ id: 101, in_reply_to_id: 100, path: 'src/a.ts', line: 3, body: 'agreed', user: { login: 'eli' } }]];
    }, calls);
    const pr = await readPr(GH, run);
    expect(calls.map((c) => [c.cmd, ...c.args])).toEqual([
      ['gh', 'pr', 'view', 'https://github.com/o/r/pull/9', '--json', 'state,isDraft,reviewDecision,reviews,comments,statusCheckRollup,mergedAt,headRefOid,mergeStateStatus'],
      ['gh', 'api', '--paginate', '--slurp', 'repos/o/r/pulls/9/comments'],
    ]);
    expect(pr).toMatchObject({ status: 'changes', checks: 'failing', failing: ['test'], headSha: 'def456' });
    expect(pr.threads.map((t) => [t.id, t.file, t.comments.length])).toEqual([
      ['rc100', 'src/a.ts', 2],
      ['ic555', undefined, 1],
      ['rvPRR_1', undefined, 1],
    ]);
  });

  it('replies in review threads and as PR comments; does not resolve', async () => {
    const calls: Call[] = [];
    const run = fakeRunner(() => ({ id: 777 }), calls);
    expect(await replyToThread(GH, { id: 'rc100', gh: 'review-comment', replyTo: 100 }, 'Done.', undefined, run)).toBe(777);
    await replyToThread(GH, { id: 'ic555', gh: 'issue' }, 'Added.', undefined, run);
    expect(calls.map((c) => [c.cmd, ...c.args])).toEqual([
      ['gh', 'api', '--method', 'POST', 'repos/o/r/pulls/9/comments/100/replies', '-f', 'body=Done.'],
      ['gh', 'api', '--method', 'POST', 'repos/o/r/issues/9/comments', '-f', 'body=Added.'],
    ]);
    expect(await setThreadStatus(GH, 'rc100', 'fixed', undefined, run)).toBe(false);
    expect(calls).toHaveLength(2);
  });
});
