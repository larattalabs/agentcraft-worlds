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
