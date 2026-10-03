# Watching pull requests to completion, and a lead per building (contract)

Decisions (2026-10-03): PR watching first, then a lead per building. Workers stay one shared pool.
Replies/resolutions written back to the PR host need the user's approval. A goal in a PR repo is done
when all its PRs are merged.

## PR watching (Foreman)

Applies to tasks landed as pull requests (`repoSettings.land: "pr"`; the Foreman knows the PR from
`worktreeMeta.prUrl` / `prBranch` / `prPushedSha`).

### State

- `Task.pr` (protocol, optional): `{ url, id, host: "ado"|"github", branch, target, status:
  "open"|"changes"|"approved"|"merged"|"abandoned", checks: "pending"|"passing"|"failing"|"none",
  threads: { open: int, new: int }, updatedAt }`.
- A task with an open PR is not `done`: it stays in a new status `pr` ("PR open, being watched")
  between `review` and `done`. `merged` -> `done`; `abandoned` -> `cancelled` (with the reason).
  Goal completion is unchanged in code (every non-cancelled task `done`), so goals now complete when
  their PRs merge.

### Polling

- Every `claude.prPollSeconds` (default 180) for tasks in `pr`, and on demand (`pr.refresh`).
- Azure DevOps: `az repos pr show --id <id> --org ... --project ...` (status, mergeStatus, reviewers'
  votes, isDraft) + threads through `az devops invoke --area git --resource pullRequestThreads
  --route-parameters project=<p> repositoryId=<repo> pullRequestId=<id> --http-method GET`
  (thread id, status active/fixed/closed/wontFix, comments with author displayName/uniqueName, file
  path + line context) + policy evaluations / build status where available.
- GitHub: `gh pr view <url> --json state,isDraft,reviewDecision,reviews,comments,statusCheckRollup,
  mergedAt` + `gh api` for review threads.
- Network/CLI errors are logged and retried with backoff; never block other work.
- What the Foreman remembers per PR: the thread ids it has seen and triaged (so only new threads or
  new comments in existing threads trigger triage), the last check state.

### Automated reviews ("Claude Code Review")

Observed on an Azure DevOps PR with a Claude review pipeline (2026-10-03):
- The reviewer posts as **Project Collection Build Service (contoso)**, one PR-level thread (no file
  anchor) per review run, a new thread after every push; body starts `**Claude Code Review**` and has
  fixed sections: `### 🔍 Critical Findings`, `### ⚠️ Important Suggestions`, `### 💡 Minor
  Improvements`, `### 📚 Teachable Moments`, `### ✅ Testing Recommendations`, `### 📈 Performance
  Notes`, `### 🎯 Verdict` (e.g. **PASS**). Findings are bullets naming `file` / `file:line`.
  "*No critical issues found.*" style lines mean an empty section.
- The same identity posts a `<!-- changelog-draft -->` thread (informational, ignore).
- System threads (author `Microsoft.VisualStudio.Services.TFS`, commentType `system`: ref updated,
  status changes) are ignored.

Handling:
- Parse each new review into findings `{severity: critical|important|minor|testing|performance|
  teachable, file?, line?, text}` + the verdict; only the newest review per PR counts (older review
  threads are superseded once a newer one exists).
- Triage defaults (configurable per repo, `repoSettings.prReview`): critical and important ->
  `fold_in` unless the lead judges a finding wrong (then `reply` with why); testing/performance/minor
  -> the lead decides (fold in only clearly worthwhile, cheap items); teachable -> ignore.
- Loop guard: at most `prReview.maxRounds` (default 2) fold-in rounds driven by automated reviews per
  PR; after that, or when a round would only address minor items, the lead stops and the decision
  goes to the user ("review round 3 suggests …; fold in?"). A PASS verdict with nothing above minor
  ends automated rounds.
- Resolving the review thread after the fold-in lands is part of the reply/resolve decision.

### Triage

When there are new comment threads (from the automated reviewer or a human), or checks turn failing:
- The repo's lead (Marlow until leads per building exist) gets a `triage` turn in its goal session
  with: the task, its PR, the full diff, and the new threads (author, file:line, text, the thread's
  conversation). Tool `triage_thread(threadId, verdict, note)` with verdicts:
  - `fold_in`: the comment should be addressed in code -> `note` = what to change;
  - `reply`: no code change; `note` = the drafted reply (explain / decline politely);
  - `ask_user`: a product decision for the user (becomes a question decision);
  - `ignore`: noise (e.g. an informational bot summary) -> resolve without reply? only with approval.
- Fold-ins are batched per PR into ONE follow-up for the task's worker: a new worktree from the
  task's branch (the branch AgentCraft pushed: local `agentcraft/<agent>/<task>` plus the pushed
  commits), the notes as the work prompt, CI, lead review, then the usual approval; landing pushes an
  ADDED commit on top of the PR branch ("Address review: ...", not re-squashed), so reviewers see an
  incremental diff. Failing checks become a fold-in the same way.
- Replies / resolutions: one decision per PR, "Post N replies and resolve M threads on PR #612?",
  listing each thread's verdict and drafted reply; on approval the Foreman posts them
  (`az devops invoke ... --http-method POST` thread comments / PATCH thread status; `gh api`). Fixed
  threads are resolved after the fold-in's push lands (status `fixed`).

### Never

The Foreman never merges or completes a PR, never approves its own PR, never changes reviewers or
policies. Agents never push or call the PR host; only the Foreman does, after an approval.

### As implemented (branch foreman/pr-watch)

Code: `foreman/src/prwatch.ts` (watcher, triage, decisions), `prs.ts` (host adapters),
`prreview.ts` (review parser), the `triage` job and fold-ins in `agents/claude/index.ts`, follow-up
landing in `repos.ts` (`doOpenPr`). Tests: `test/pr-review-parse.test.ts`, `pr-followup.test.ts`,
`pr-watch.test.ts` (fake host runner; nothing reaches a host).

- **Modes** `claude.prWatch` / `--pr-watch`: `observe` (default) polls, updates `Task.pr`, moves
  merged/abandoned tasks, runs the lead's triage turns, but posts nothing, opens no decision and
  starts no fold-in; the verdicts go to the feed and to the shared memory note
  `PR triage <task> #<id> (observe)`. `on` does everything below. `off` = the old behaviour (a PR
  finishes its task). Status `pr` is only used when a backend watches PRs (claude, not `off`) and the
  host gave a PR URL; otherwise landing still means `done`.
- **Tool name**: one call `triage(items: [{ref, verdict, note}])` instead of `triage_thread` per thread.
  Refs carry the task id (one goal session can triage several PRs): `t3/thread-<threadId>`,
  `t3/review-<threadId>.<n>` (finding n of the automated review), `t3/checks`. Findings without a
  verdict count as ignore; human threads without one are left alone.
- **Commands** (all through an injectable runner):
  - ADO read: `az repos pr show --id N --org https://dev.azure.com/<org> -o json --only-show-errors`
    (no `--project`: the command does not take one); threads `az devops invoke --org ... --area git
    --resource pullRequestThreads --route-parameters project=<p> repositoryId=<repo GUID from pr show>
    pullRequestId=N --http-method GET --api-version 7.1 -o json --only-show-errors`; checks `az repos
    pr policy list --id N --org ... -o json --only-show-errors` (only Build/Status policy types count;
    a failing call means checks `none`).
  - ADO write (after approval): reply `az devops invoke ... --resource pullRequestThreadComments
    --route-parameters ... threadId=T --http-method POST --in-file <tmp.json>`
    (`{"content", "parentCommentId": 1, "commentType": 1}`); status `... --resource pullRequestThreads
    ... threadId=T --http-method PATCH --in-file <tmp.json>` (`{"status": "fixed" | "closed"}`).
  - GitHub read: `gh pr view <url> --json state,isDraft,reviewDecision,reviews,comments,
    statusCheckRollup,mergedAt,headRefOid,mergeStateStatus` + `gh api --paginate
    repos/o/r/pulls/N/comments`. Write: `gh api --method POST repos/o/r/pulls/N/comments/<id>/replies
    -f body=...` (review threads) or `.../issues/N/comments` (others). Resolving GitHub review threads
    is not done (needs GraphQL).
- **Review parsing** follows the review pipeline's renderer format: a
  context-aware review's final block is the one used; refactoring opportunities count as minor;
  teachable moments never reach the lead. Bot identity: `Project Collection Build Service ...`;
  the review is recognised by its `**Claude Code Review**` header, the changelog thread by
  `<!-- changelog-draft -->`; other build-service threads and system threads are ignored.
- **New**: per thread the count of human comments seen (comments AgentCraft posted are remembered
  by id and never count); the newest automated review by publish time, once. Items found while a
  triage is still waiting stay for the poll after it. A triage turn that ends without verdicts is
  offered once more, then reported in the feed.
- **Loop guard**: a PASS with nothing above minor (critical/important) never starts a round; after
  `maxRounds` review-driven fold-ins, or when a later round has nothing at `autoSeverities`, the
  newest review becomes the user's question "Review round N on PR #612 suggests ...; fold in?".
- **Fold-ins**: the task goes `pr -> todo`; its worker (or the next free one, continuing the branch)
  gets the notes; CI, then the lead reviews only the follow-up diff; the merge decision reads "Push the
  review fixes for t3 to PR #612?". Landing pushes on top of the PR (fast-forward of the branch, or,
  for squash PRs, a commit by the user whose parent is the pushed commit: 3-way merge with the last
  landed branch tip as base, so newer base commits never leak in), then the task is back in `pr`.
  Rejected or empty fold-ins also go back to `pr`. A second fold-in for the same PR waits for the
  first. If the PR branch has commits AgentCraft did not push (seen twice), fold-ins are not started.
- **Decisions** (agent Marlow, kind question, owned by the watcher so they never resume a session):
  "Post N replies and resolve M threads on PR #612?" [Post, Skip]; ask_user items [Fold in, Leave it];
  the loop guard [Fold in, Leave it]. Replies to automated-review findings are combined into one
  comment on the review thread; the review thread is resolved `fixed` after a fold-in landed, else
  `closed`.
- **Not done yet**: polling a task's PR while its fold-in runs (a merge during a fold-in is noticed
  once the task is back in `pr`); lead per building (below).
- **Probe**: `npm run pr-probe -- <PR url>` prints what the watcher would see (reads only).

## A lead per building (after PR watching)

- Each building (one repo or a repo group) has a lead; Marlow leads repos without a building and
  anything not tied to a repo. The mapping (repos -> lead id) lives in the Foreman's state; placing a
  building through the hub sends `lead.assign { repos, buildingId }` (the Foreman picks the next free
  lead character), removing it sends `lead.release`.
- `LEAD = 'marlow'` becomes a lookup: the lead of a goal = the lead of the goal's repo. Leads run in
  parallel (each its own queue, sessions per goal); usage throttling counts leads too.
- Workers: one shared pool; any lead assigns from it.
- Cast: lead characters beyond Marlow (names, colours) in `assets-src/cast.json` / the mod's cast and
  skins; the mod routes each lead to its building's podium/meeting area; each podium shows its lead's
  decisions.
- PR triage turns go to the PR's repo's lead.
