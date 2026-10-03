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
  finishes its task; started with `off`, tasks still in `pr` are marked done). Status `pr` is only used when a backend watches PRs (claude, not `off`) and the
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
    statusCheckRollup,mergedAt,headRefOid,mergeStateStatus` + `gh api --paginate --slurp
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
  first. If the PR branch has commits AgentCraft did not push (seen twice; ADO: the PR's
  lastMergeSourceCommit), fold-ins are queued and start on the first poll after that clears.
  Approved "Addressed in <commit>" replies and `fixed` resolutions belong to the fold-in that
  carries those fixes (a generation number): posted when exactly that one lands, dropped (with a
  feed line) when it is rejected or comes back empty.
- **Decisions** (agent: the task's lead, Marlow unless its goal belongs to a building lead; kind question, owned by the watcher so they never resume a session):
  "Post N replies and resolve M threads on PR #612?" [Post, Skip]; ask_user items [Fold in, Leave it];
  the loop guard [Fold in, Leave it]. Replies to automated-review findings are combined into one
  comment on the review thread; the review thread is resolved `fixed` after a fold-in landed, else
  `closed`.
- **Not done yet**: polling a task's PR while its fold-in runs (a merge during a fold-in is noticed
  once the task is back in `pr`).
- **Probe**: `npm run pr-probe -- <PR url>` prints what the watcher would see (reads only).

## A lead per building (contract, 2026-10-03)

Each building (one repo or a repo group) gets its own lead; Marlow leads home, repos without a building
and anything not tied to a repo. Workers stay one shared pool that every lead assigns from.

### Cast
- Lead characters in `assets-src/cast.json` (role `lead`): `marlow` plus three new ones, ids **`ines`**,
  **`bram`**, **`cass`** (names Ines, Bram, Cass; titles, looks and colours per the cast rules, skins and
  portraits generated by `assets-src/gen`). Order = assignment order. The Foreman's cast (`foreman/src/cast.ts`)
  and the mod's (`assets/agentcraft/cast.json`) both come from it.
- `claude.leads` (config): the lead ids in use, default `["marlow","ines","bram","cass"]`; `[]`/one entry
  = today's single lead. All leads use `agents.lead` model/effort.

### State (Foreman)
- `store.data.leads: { [leadId]: { building: string, repos: string[], assignedAt } }` for every lead but
  Marlow that leads a building. `building` is the mod's key `"<worldId>/<buildingId>"` (worldId = the save
  folder name, so two worlds on one Foreman don't collide).
- `leadForRepo(repoId)`: the lead whose building has that repo, else `marlow`.
- `Goal.leadId` (protocol, optional; absent = marlow): set at submit from the goal's repo; fixed for
  the goal's life except when its lead is released (then `marlow` takes the goal over, with a feed line
  and the plan memory note carried in the takeover prompt).
- Lead sessions are keyed `<leadId>:<goalId>` (was `marlow:<goalId>`; existing keys migrate on load).
- Every lead has its own job queue (plan / review / followup / triage); leads run in parallel. The usage
  throttle and `claude.maxConcurrentTurns` (if set) count lead turns too.
- Decisions, messages and feed lines carry the acting lead's id as `agentId` (no protocol change).
  `@<leadId>` messages route to that lead; plain messages about a goal go to the goal's lead.
- The sim backend mirrors this: each assigned lead plans and reviews its building's goals.

### Protocol
Client -> Foreman:
- `lead.assign { building, repos: string[] }` -> ack `{ leadId }`. Idempotent: the same `building` keeps
  its lead and gets its repos updated. A new building takes the first free lead in `claude.leads`
  order; none free -> `marlow` (ack says so; nothing stored). A repo in another building's lead moves.
- `lead.release { building }` -> ack `{}`: frees that lead; its open goals move to marlow.
- `lead.sync { world, buildings: [{ building, repos }] }`: sent by the mod on connect for its world;
  assigns missing ones and releases any `"<world>/..."` building not in the list.
Foreman -> client:
- `leads.update { leads: LeadAssignment[] }` (full list; also `snapshot.leads`), `LeadAssignment =
  { leadId, building?, repos: string[] }` (marlow listed with no building).
- Agents with role `lead` appear in `snapshot.agents` only while assigned (marlow always).

### Mod
- Placing a building sends `lead.assign`, removing it `lead.release`, connecting `lead.sync`.
- Routing: a lead's building = its assignment's building (marlow: home). The lead stands at / walks to
  its building's `decision_podium` / `meeting`; a released lead walks home and despawns like an
  off-shift agent.
- Each podium shows the decisions of its building's lead (home podium: marlow's + unassigned).
- Hub Buildings tab: each building shows its lead (portrait, name). The task wall title shows the lead.
- PR triage turns go to the PR's repo's lead.

### As implemented (Foreman + protocol, branch `foreman/leads`)
- **Protocol**: as above. `lead.assign` acks `{leadId}`, or `{leadId: "marlow", overflow: true}` when no
  lead is free. `lead.release` acks `{}` (also for an unknown building). `lead.sync` acks `{leads: {building:
  leadId}}`; it releases the world's missing buildings first, then assigns, so a freed lead is reused at once,
  and sends one `leads.update` for the whole sync. Building keys must look like `"<world>/<building>"` and the
  world in `lead.sync` must not contain `/` (schema-checked). `LeadAssignment` lists marlow first with `repos: []`
  (not "repos without a building"), then the assigned leads in `claude.leads` order. `Goal.leadId` is omitted for
  marlow, so old goals keep their shape.
- **Cast**: `foreman/src/cast.ts` has Ines, Bram and Cass (role lead) in its placeholder list, overridden by
  `assets-src/cast.json` when it has them; a configured lead id with no entry anywhere gets its capitalised id as
  its name and a generated colour. Unassigned building leads are kept in `state.json` but are hidden from
  `snapshot.agents` and `agent.upsert`, and `@ines` does not resolve while Ines leads nothing. A released lead gets
  one last `agent.upsert` (active false, lounge) before it is hidden.
- **Config**: `claude.leads` (also `--leads`, `AGENTCRAFT_LEADS`) is normalized to start with marlow; `[]` or one
  entry means marlow alone, so `["marlow"]` reproduces the single lead. `claude.maxConcurrentTurns` (also
  `--max-concurrent-turns`) is new, optional and unset by default. Leads in the store that are no longer in
  `claude.leads` are released when the Foreman starts, and their goals move to marlow.
- **Sessions / migration**: a goal without `leadId` is marlow's, so the existing `marlow:<goalId>` keys are already
  `<leadId>:<goalId>`. Nothing is rewritten, and old stores resume as before. At start, in-flight turns of leads
  that are off duty (or whose goal moved) are dropped, and reconciliation re-plans the goals under marlow.
- **Parallel leads, throttle**: one queue per lead id (as the backend already queued per agent), with per-lead
  order kept. Workers are capped by `maxConcurrent` as before, and leads by nothing extra. While the plan reports a
  usage warning, workers are capped by `throttleConcurrent` (as before) and leads take turns **one at a time**, so
  one lead alone behaves exactly as before. `claude.maxConcurrentTurns`, if set, caps lead and worker turns
  together. At the usage limit itself nobody starts a turn (unchanged).
- **Routing**: plan, review, follow-up and triage jobs, merge decisions, and the backend's and the PR watcher's feed
  lines all go to the goal's lead. A task without a goal goes to its repository's building's lead. **Deviation:**
  PR triage goes to the *goal's* lead, because its `<lead>:<goal>` session has the plan, not to the lead of the
  PR's repository. The two differ only for a cross-repository task whose repository is in another building. A
  plain console message (`to: "all"` without `@`) goes to the lead of the newest goal (marlow if that lead is
  released). A lead's follow-up resumes the session of its own newest goal (not the newest goal overall). A worker
  with no task forwards to that same lead. A worker's `send_message` to `"lead"` reaches its task's lead.
- **Release**: the lead's running turn is aborted with no after-turn step, and its queue, paused job and in-flight
  record are dropped. Its open questions and permission prompts are withdrawn; its merge decisions stay open for
  you. Its open (`planning` / `active`) goals lose `leadId` (`goal.upsert` plus a feed line). Then reconciliation
  runs: planning goals are planned again by marlow, tasks in review without a decision get CI and review again
  under marlow, and pending PR triage of the moved goals is offered to marlow at once. Marlow's first turn on each
  moved goal starts with a **takeover note**: the previous lead's plan note (`planText`) and the goal's task
  board. The backend tracks which lead last had a turn per goal, so this also works after a restart.
- **Lead tools**: a lead may `update_task` only its own goals' tasks; another lead's task is refused with a
  pointer to `send_message`. Reassigning a task that is `doing` goes through the proper hand-off
  (`task.action reassign`) instead of just changing the assignee. `create_task` naming a worker busy on another
  lead's task creates it (the scheduler waits for that worker, never preempts) and the result says so. Leads cannot
  be assignees.
- **Prompts**: a building lead's header is `# You are Ines, lead of building b3 on an AgentCraft team`. Every lead,
  when others are on duty, gets a section naming its building and repositories, the other leads and their
  buildings, and the shared worker pool. With marlow alone the prompt is byte-identical to before. The worker
  prompt names its task's lead.
- **Sim**: the scripted goal is run by its goal's lead. A goal for another building's lead (while the script runs,
  or after it finished) runs as a 3-beat side flow on that building's repository: the lead plans one task, the
  first free worker writes `docs/goals/<goal>.md` in a real worktree and runs the tests, then the lead reviews and
  opens the merge decision. One side flow runs per lead at a time; a second goal for a busy lead is refused, as
  before. A side flow can borrow a worker the script needs later, and the script's own animation then overrides it
  (cosmetic). A released lead does not block the script: `gate()` lets off-duty leads pass.
- **Not done here**: the cast.json entries, skins and portraits (art track), and everything under "Mod".
