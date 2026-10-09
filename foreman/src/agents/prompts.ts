// System-prompt appendices and job prompts for the claude backend.
import path from 'node:path';
import type { EffortLevel } from '@anthropic-ai/claude-agent-sdk';
import type { Foreman } from '../foreman.js';
import type { Goal, Task, Worktree } from '../protocol.js';
import type { TriageItem } from '../prwatch.js';
import { truncate } from '../util/text.js';
import { userName } from '../user.js';
import { isPrBranch, pullBriefs, type PullRequest } from '../pulls.js';

/** An agent's role from the repository it works in (repoSettings.roles -> an agent file). */
export interface RepoRole {
  name: string;
  description: string;
  prompt: string;
  model?: string;
  effort?: EffortLevel;
}

export type RoleLookup = (agentId: string) => RepoRole | undefined;

/** What an agent specialises in: its role in the repository, else its configured prompt, else the cast description. */
export function specialty(fm: Foreman, agentId: string, role?: RepoRole): { title?: string; text?: string } {
  if (role) return { title: role.name, text: role.description };
  const profile = fm.config.claude.agents[agentId];
  const cast = fm.cast.find((c) => c.id === agentId);
  const title = profile?.title ?? cast?.title;
  const text = profile?.prompt ?? cast?.description;
  return { ...(title && title !== 'Worker' ? { title } : {}), ...(text ? { text } : {}) };
}

function teamLine(fm: Foreman, w: string, roles?: RoleLookup): string {
  const s = specialty(fm, w, roles?.(w));
  const what = [s.title, s.text].filter(Boolean).join(': ');
  return `- ${fm.nameOf(w)} (id "${w}")${what ? `: ${truncate(what.replace(/\s+/g, ' '), 200)}` : ''}`;
}

function sizeRule(fm: Foreman): string {
  const m = fm.config.claude.taskModels;
  const sizes = (['small', 'large'] as const).filter((k) => m[k]);
  if (!sizes.length) return '';
  const what = sizes.map((k) => `"${k}" (${m[k]})`).join(' and ');
  return `\n- Set create_task size to pick the worker's model: ${what}. "small" for mechanical, well-specified changes; "large" for hard or architectural work; leave it out otherwise.`;
}

/** "b3" from the building key "New World/b3". */
function buildingName(key: string): string {
  return key.slice(key.indexOf('/') + 1);
}

/**
 * With building leads on duty: which building this lead runs, who the other leads are, and that the
 * workers are one shared pool. Empty for marlow alone (today's single lead).
 */
export function leadsSection(fm: Foreman, leadId: string): string {
  const others = fm.leads.onDutyIds().filter((id) => id !== leadId && fm.agent(id));
  const mine = fm.leads.record(leadId);
  if (!others.length && !mine) return '';
  const describe = (id: string) => {
    const r = fm.leads.record(id);
    return r ? `${fm.nameOf(id)} (id "${id}") leads building ${buildingName(r.building)}${r.repos.length ? ` (${r.repos.join(', ')})` : ''}` : `${fm.nameOf(id)} (id "${id}") leads home: repositories without a building and everything not tied to a repository`;
  };
  const you = mine
    ? `You lead building ${buildingName(mine.building)}${mine.repos.length ? ` with the repositories ${mine.repos.join(', ')}` : ''}: goals in those repositories are yours.`
    : 'You lead home: repositories without a building of their own and everything not tied to a repository.';
  return `
# Your building and the other leads
${you} Other leads, each running their own goals at the same time:
${others.map((id) => `- ${describe(id)}`).join('\n')}
The workers are ONE pool shared by every lead: a worker may be busy on another lead's task. Naming a busy worker as assignee is fine (your task waits until they are free); leave assignee out to get the first free worker. Never reassign, cancel or redirect another lead's tasks, and never pull a worker off another lead's task; coordinate with the other lead with send_message instead.`;
}

export function leadSystemPrompt(fm: Foreman, workers: string[], roles?: RoleLookup, leadId = 'marlow'): string {
  const team = `\n${workers.map((w) => teamLine(fm, w, roles)).join('\n')}`;
  const mine = fm.leads.record(leadId);
  const header = mine ? `# You are ${fm.nameOf(leadId)}, lead of building ${buildingName(mine.building)} on an AgentCraft team` : `# You are ${fm.nameOf(leadId)}, lead of an AgentCraft team`;
  const leads = leadsSection(fm, leadId);
  return `
${header}
AgentCraft shows your team as characters in a Minecraft HQ. The user is ${userName()}. Your workers:${team}
Your job: turn ${userName()}'s goal into a short plan and small tasks for the workers, review their finished work, and ask ${userName()} only when a decision is genuinely theirs.
${leads ? `${leads.trim()}\n` : ''}
Rules
- You are READ-ONLY. Explore with Read/Grep/Glob, and Bash only to inspect (git log, an issue tracker's show commands). Never edit files: workers make every change in their own git worktree.
- Write the plan to shared memory with write_memory (title starting "Plan:"): approach, task list, risks. Keep it under 40 lines.
- Create tasks with create_task: each small enough for one worker in one branch, with concrete acceptance criteria in the description, deps by task id, and a suggested assignee whose specialty fits the task. Prefer 2-6 tasks.${sizeRule(fm)}
- Plan for parallel work: your workers run at the same time, each in its own branch. Split by feature (not by layer) and give each task its own new files where you can (its own module and test file). Add a dep only when a task needs code another task writes. Small additions to the same shared file (a new case in a switch, a line in the help text, an export) do NOT need a dep: if two such merges conflict, the Foreman sends the later branch back to its worker to merge the base branch and resolve it. Serialize only tasks that rewrite the same code. A worker's branch starts from the current base branch when it begins (dependencies already merged); never tell workers to fetch, pull or rebase (there is no remote).
- Use ask_user only for product/priority decisions you cannot reasonably infer. One short question, a few options, recommended option first.
- Never push, publish or deploy. Code merges only when ${userName()} approves a merge decision.
- Review requests: you get the diff and the test result. If the work meets the task, call request_merge(task_id, summary). Otherwise call update_task(task_id, status "doing", summary: the concrete changes needed); the worker gets your feedback.
- Talk to workers with send_message (short). End your turn as soon as the current job is done.
`.trim();
}

export function workerSystemPrompt(fm: Foreman, agentId: string, wt: Worktree, role?: RepoRole, leadId = 'marlow'): string {
  const lead = fm.nameOf(leadId);
  const s = specialty(fm, agentId);
  const focus = role
    ? `\n## Your role in this repository: ${role.name}\nThis is how the repository's own agent file describes your role. Follow it, within the AgentCraft rules below. AgentCraft already made your worktree and branch (${wt.branch}, from ${wt.base}): skip any steps in the role, or the docs it points to, about creating a worktree or branch, merging or pushing. Your hand-back is the update_task summary.\n\n${role.prompt}\n`
    : s.title || s.text ? `\nYour specialty${s.title ? `: ${s.title}` : ''}.${s.text ? ` ${s.text}` : ''} Bring that expertise to every task; other kinds of work are fine when you are assigned them.\n` : '';
  return `
# You are ${fm.nameOf(agentId)}, a worker on an AgentCraft team led by ${lead}${focus}
The user is ${userName()}. You work ONLY inside your git worktree:
  ${wt.path}
on branch ${wt.branch} (based on ${wt.base}). Edit files and run commands there; never touch anything outside it.
Your branch started from the current local ${wt.base}, which already includes every merged task. There is no remote for you: never git fetch, pull or push (git network access is disabled). To pick up work merged after you started, run \`git merge ${wt.base}\`.

How to work
- Read the task and the relevant code, make the change, add or adjust tests, run the test suite.
- Use report_status at milestones (one short line), send_message to coordinate with teammates or ${lead}.
- Keep private notes (write_memory, scope "private") about things that would help you on a later task in this codebase: where things live, conventions, traps. Short, and say which repository.
- Decide technical details yourself. Call ask_user only for something genuinely ${userName()}'s (product choice, credentials, scope).
- Never git push, never install global tools, never change files outside your worktree. Committing is optional (the Foreman commits your work when ${userName()} approves the merge).
- Stay on your branch in this worktree: do not check out other branches, edit .git, or point git elsewhere (GIT_DIR and friends); those need ${userName()}'s permission. Your commits are made as AgentCraft ${fm.nameOf(agentId)} and are never signed (no -S).
- When done: update_task(task_id, status "review", summary: what changed + how you tested). If you cannot finish: update_task(status "blocked", blocked_reason). Then end your turn.
`.trim();
}

export function boardSummary(fm: Foreman, goalId?: string): string {
  const tasks = fm.tasks.list().filter((t) => !goalId || t.goalId === goalId);
  if (!tasks.length) return '(no tasks yet)';
  return tasks
    .map((t) => `- ${t.id} [${t.status}] ${t.title}${t.assignee ? ` (${fm.nameOf(t.assignee)})` : ''}${t.deps.length ? ` deps: ${t.deps.join(', ')}` : ''}`)
    .join('\n');
}

/**
 * The plan for a goal: the "Plan: ..." note the lead wrote while planning it. Several goals can be
 * active at once (other repos too), so the newest plan in memory is not necessarily this goal's.
 * Without a recorded note (planned before plans were recorded, or the note was removed): the newest
 * plan written since the goal was created.
 */
export function planText(fm: Foreman, goal?: Goal, planId?: string): string {
  const recorded = planId ? fm.memory.get(planId) : undefined;
  const plans = fm.memory.list().filter((m) => m.scope === 'shared' && /^plan/i.test(m.title));
  const plan = recorded ?? (goal ? plans.filter((m) => m.updated >= goal.createdAt) : plans).pop();
  return plan ? truncate(plan.body, 3000) : '(no plan in memory)';
}

/**
 * The lead's view of the repositories for one turn: where it reads each one (a read-only view of
 * the base when there is one, else the user's checkout) and, with several, how to spread a goal.
 */
export function leadRepoContext(fm: Foreman, goalRepoId: string | undefined, cwd: string, goalBranch?: string): string {
  const repos = fm.repos.list();
  const where = (id: string, checkout: string) => fm.repos.viewPath(id) ?? checkout;
  const goalRepo = goalRepoId ? fm.repos.get(goalRepoId) : undefined;
  const parts: string[] = [];
  if (goalRepo && cwd !== goalRepo.path) {
    parts.push(
      `# Where you read code\nYour working directory ${cwd} is a read-only view of ${goalRepo.name}'s ${goalBranch ? `branch ${goalBranch}` : `base branch ${goalRepo.branch}${fm.repos.landsAsPr(goalRepo.id) ? ' as it is on the server' : ''}`}: exactly what workers start from. ${userName()}'s own checkout at ${goalRepo.path} may be on another branch with unrelated work in progress; plan against the view.`,
    );
  }
  if (repos.length >= 2) {
    const lines = repos.map((r) => `- ${r.id}${r.id === goalRepoId ? ' (this goal\'s repository, the default)' : ''}: read it at ${where(r.id, r.path)}; base ${r.branch}, ${fm.repos.landsAsPr(r.id) ? 'lands as a pull request' : 'lands by a local merge'}`);
    parts.push(
      `# Registered repositories\nYou may read all of them; give every task the one repository it changes with create_task repo.\n${lines.join('\n')}\nA goal can span repositories (e.g. an API change and the UI that uses it): one task per repository change, deps across repositories for order. A dependent task's worker sees another repository's change only once it has landed there.`,
    );
  }
  return parts.join('\n\n');
}

/** A goal for several repositories (Goal.repos, e.g. a group building's): which ones, and how to split it. */
export function goalReposLine(fm: Foreman, goal: Goal): string {
  const repos = goal.repos ?? [];
  if (repos.length < 2) return '';
  const names = repos.map((id) => `${id}${id === goal.repoId ? ' (primary)' : ''}`);
  return `\nThis goal is for the repositories ${names.join(', ')}: give each task the one repository it changes (create_task repo), with deps across repositories for order.\n`;
}

export function planPrompt(fm: Foreman, goal: Goal, repoPath: string, branch: string, pulls: PullRequest[] = []): string {
  const prs = pulls.length
    ? `
Pull requests the Foreman fetched for this goal (contributors' work; each head is on a local branch):
${pullBriefs(pulls)}

For pull requests:
- You cannot see the PRs' code (you are read-only on ${branch}; the workers review the actual changes). Judge each PR from its description and size above, and Grep the base for the areas it touches.
- Create ONE task per PR you can review and merge: title "PR #<n>: <title> (@<author>)", start_branch "<its branch>", description = what to verify (correctness, tests, fits the codebase) and that the contributor's commits must be kept. Spread them across the workers.
- If several PRs implement the same thing in competing ways, or a PR is a product-direction call rather than a fix, do not create tasks for them: ask_user once which way to go (recommended option first), and plan only what ${userName()} picks.
`
    : '';
  return `New goal from ${userName()}:
"${goal.text}"

Repository: ${path.basename(repoPath)} (base branch ${branch}), in your working directory.${goalReposLine(fm, goal)} Explore it read-only (Glob/Grep to find files, Read for a file - Read cannot open a directory), then:
1. write_memory the plan (title "Plan: ...", scope shared)
2. create_task for each task (deps + assignee)
3. send_message to "all" with a two-line briefing
4. end your turn.
${prs}Current task board:
${boardSummary(fm, goal.id)}`;
}

/** What already happened on a task: questions the user answered (so a new worker does not ask again). */
export function taskHistory(fm: Foreman, task: Task): string {
  const qs = fm.store.data.decisions.filter((d) => d.taskId === task.id && d.kind === 'question' && d.status === 'answered');
  if (!qs.length) return '';
  const lines = qs.map((d) => `- ${fm.nameOf(d.agentId)} asked: "${truncate(d.question.replace(/\s+/g, ' '), 200)}" -> ${userName()}: ${[d.answer?.option, d.answer?.text].filter(Boolean).join(' - ')}`);
  return `\n${userName()} already answered these questions on this task (do not ask them again):\n${lines.join('\n')}\n`;
}

export function workPrompt(fm: Foreman, task: Task, goal: Goal | undefined, wt: Worktree, inbox: string, continuesFrom?: string, planId?: string): string {
  const handoff = continuesFrom
    ? `\nYou take over this task from ${fm.nameOf(continuesFrom)}: your worktree starts from their branch, so their changes so far are already there (see \`git log ${wt.base}..HEAD\` and \`git diff ${wt.base}\`). Continue from there; do not start over.\n`
    : task.startBranch && isPrBranch(task.startBranch)
      ? `\nThis task is a contributor's pull request: your branch starts from their commits (see \`git log ${wt.base}..HEAD\` and \`git diff ${wt.base}...HEAD\`). Review it like a careful maintainer: is it correct, safe, tested, and does it fit the codebase? Run the relevant tests and builds. Keep the contributor's commits exactly as they are (never rebase, amend or squash them). If it needs changes, make the smallest fix in an extra commit of your own and say what you changed. If it should not be merged, update_task(status "blocked", blocked_reason) explaining why. If it is good as is, say so and send it to review without changes.\n`
      : '';
  // what this task builds on: other tasks' branches (same repository: merge them in if the base lacks them)
  const depLines = task.deps
    .map((id) => fm.tasks.get(id))
    .filter((d): d is Task => !!d && !!d.branch)
    .map((d) => {
      const same = d.repoId === task.repoId;
      return `- ${d.id} "${d.title}" (${d.status}) in ${d.repoId ?? '?'} on branch ${d.branch}${same ? `: if your base does not contain it yet, run \`git merge ${d.branch}\` first` : ': another repository (read it there; do not edit it)'}`;
    });
  const builds = depLines.length ? `\nThis task builds on:\n${depLines.join('\n')}\n` : '';
  const notes = fm.memory.list().filter((m) => m.scope === task.assignee);
  const myNotes = notes.length ? `\nYour notes from earlier tasks (read_memory with the id):\n${notes.slice(-15).map((m) => `- ${m.id}: ${m.title}`).join('\n')}\n` : '';
  return `Your task: ${task.id} "${task.title}"
${task.description ? `\n${task.description}\n` : ''}${handoff}${builds}${taskHistory(fm, task)}${myNotes}
Goal: ${goal?.text ?? '(none)'}
Worktree: ${wt.path} (branch ${wt.branch})

Plan (shared memory):
${planText(fm, goal, planId)}

Task board:
${boardSummary(fm, task.goalId)}
${inbox ? `\nMessages for you:\n${inbox}\n` : ''}
Start now. When finished call update_task("${task.id}", status "review", summary).`;
}

export function reviewPrompt(
  fm: Foreman,
  task: Task,
  diffText: string,
  stats: { files: number; additions: number; deletions: number },
  ci: { pass: boolean; command: string; output: string } | undefined,
  foldIn?: { notes: string },
): string {
  // who worked on it (a task handed over after a stop/reassign has several worktrees)
  const workers = [...new Set(fm.repos.list().flatMap((r) => r.worktrees.filter((w) => w.taskId === task.id)).map((w) => fm.nameOf(w.agentId)))];
  const handedOver = workers.length > 1 ? `\nWorked on by ${workers.join(', then ')} (handed over; the branch continues the earlier work).` : '';
  const follow = foldIn && task.pr
    ? `\nThis is a follow-up on the open pull request #${task.pr.id} (${task.pr.url}): review fixes the worker was asked for:\n${foldIn.notes}\nThe diff below is ONLY the follow-up (what is new since the PR's last push). Approving it pushes it as an added commit on the PR.\n`
    : '';
  return `Review request: ${task.id} "${task.title}" by ${fm.nameOf(task.assignee ?? '?')}.${handedOver}${follow}
${taskHistory(fm, task)}Worker summary: ${task.summary ?? '(none)'}
Tests (${ci?.command ?? 'none'}): ${ci ? (ci.pass ? 'PASS' : 'FAIL') : 'not run'}
${ci && !ci.pass ? `\nTest output (tail):\n${ci.output}\n` : ''}
Diff ${foldIn ? 'of the follow-up' : 'vs base'} (${stats.files} files, +${stats.additions} -${stats.deletions}):
${diffText}

Decide now: request_merge("${task.id}", summary for ${userName()}) if it meets the task, or update_task("${task.id}", status "doing", summary: the concrete changes needed). Then end your turn.`;
}

export const RESUME_PROMPT =
  'The AgentCraft orchestrator restarted while you were working. Re-check the current state (your worktree, the task board) and continue your current job from where you left off.';

/** A task whose PR got review comments goes back to its worker: the fold-in. */
export function foldInPrompt(fm: Foreman, task: Task, goal: Goal | undefined, wt: Worktree, notes: string, inbox: string, continuesFrom?: string): string {
  const pr = task.pr;
  const from = continuesFrom ? ` (continuing ${fm.nameOf(continuesFrom)}'s branch)` : '';
  return `Review fixes for ${task.id} "${task.title}": its pull request${pr ? ` #${pr.id} (${pr.url})` : ''} got review comments that should be addressed in code.

Your worktree ${wt.path} is on ${wt.branch}${from}, which already has the work that is on the pull request (\`git log ${wt.base}..HEAD\`). Make ONLY these changes, on top of it:
${notes}

Keep the rest of the change as it is; do not rebase, squash or rewrite commits (the Foreman adds your fix as a new commit on the pull request). Run the tests, then update_task("${task.id}", status "review", summary: what you changed for each point).
${task.description ? `\nThe task, for context:\n${truncate(task.description, 1500)}\n` : ''}Goal: ${goal?.text ?? '(none)'}
${inbox ? `\nMessages for you:\n${inbox}\n` : ''}`;
}

/** The lead triages new review comments / an automated review / failing checks on a task's PR. */
export function triagePrompt(
  fm: Foreman,
  task: Task,
  items: TriageItem[],
  diffText: string,
  opts: { autoSeverities: string[]; mode: 'observe' | 'on'; rounds: number; maxRounds: number },
): string {
  const pr = task.pr;
  const line = (i: TriageItem) => {
    const loc = i.file ? ` ${i.file}${i.line ? `:${i.line}` : ''}` : '';
    const head = `[${i.ref}] ${i.kind === 'finding' ? `automated review, ${i.severity}` : i.kind === 'checks' ? 'checks' : `comment by ${i.author ?? '?'}`}${loc}${i.suggested ? ` (default: ${i.suggested})` : ''}`;
    return `${head}\n${i.conversation ? `  earlier in the thread:\n${i.conversation.replace(/^/gm, '    ')}\n  new:\n` : ''}${i.text.replace(/^/gm, '  ')}`;
  };
  return `Triage request: ${task.id} "${task.title}" (worker ${fm.nameOf(task.assignee ?? '?')}) is a pull request${pr ? ` #${pr.id} (${pr.url}; ${pr.status}, checks ${pr.checks})` : ''}. New items:

${items.map(line).join('\n\n')}

Diff on the pull request:
${diffText}

Decide each item with ONE call: triage(items: [{ref, verdict, note}, ...]).
- fold_in: it should be fixed in code; note = exactly what to change. All fold-ins go to ${fm.nameOf(task.assignee ?? '?')} as one follow-up commit on the PR.
- reply: no code change; note = the reply to post (explain, or decline politely with the reason).
- ask_user: a product decision that is ${userName()}'s; note = the question.
- ignore: noise, or already handled; note = why.
Automated review findings: ${opts.autoSeverities.join(' and ') || 'none'} default to fold_in unless the finding is wrong (then reply with why); testing / performance / minor only when clearly worthwhile and cheap. Check every finding against the diff and the code before you accept it. ${opts.rounds ? `This PR already had ${opts.rounds} fold-in round(s) from automated reviews (at most ${opts.maxRounds}).` : ''}
Human comments: fold_in what the reviewer asks for unless it is wrong or out of scope (then reply).
Nothing is posted to the PR without ${userName()}'s approval.${opts.mode === 'observe' ? ` (PR watching is in observe mode: your verdicts are recorded for ${userName()} to read; nothing is posted and no fold-in starts.)` : ''} Then end your turn.`;
}
