// The team, review jobs: a finished task runs CI in its worktree, then the lead reviews it
// (request_merge or changes) or the merge decision goes straight to the user.
import type { Task } from '../../../protocol.js';
import { MERGE_OPTIONS } from '../../../protocol.js';
import type { TestResult } from '../../../repos.js';
import { renderDiffText } from '../../../diff.js';
import { reviewPrompt } from '../../prompts.js';
import { WorkJobs } from './work.js';

export abstract class ReviewJobs extends WorkJobs {
  /** Tasks in review with no merge decision and no review job: CI + review (again). */
  protected override sweepReviews(): void {
    const st = this.st;
    for (const t of this.fm.tasks.list()) {
      if (t.status !== 'review' || !t.worktree) continue;
      if (this.fm.decisions.open().some((d) => d.taskId === t.id)) continue;
      if (Object.values(st.inflight).some((i) => i.taskId === t.id)) continue;
      if (this.reviewing.has(t.id) || this.hasQueued(this.fm.leadOfTask(t), (j) => j.taskId === t.id) || (t.assignee && this.hasQueued(t.assignee, (j) => j.taskId === t.id))) continue;
      void this.afterWorkerDone(t.id);
    }
  }

  /** A worker finished a task: CI in the worktree, then lead review (or a merge decision). */
  protected override async afterWorkerDone(taskId: string): Promise<void> {
    if (this.reviewing.has(taskId)) return; // CI already running for it
    this.reviewing.add(taskId);
    try {
      await this.ciThenReview(taskId);
    } finally {
      this.reviewing.delete(taskId);
    }
  }

  protected async ciThenReview(taskId: string): Promise<void> {
    const t = this.fm.tasks.get(taskId);
    if (!t || t.status !== 'review' || !t.repoId || !t.worktree) return;
    const worker = t.assignee;
    // protected files (repoSettings.protect) must never land: back to the worker, twice at most
    const leaked = await this.fm.repos.protectedChanges(t.repoId, t.worktree).catch(() => [] as string[]);
    if (leaked.length) {
      const n = (this.st.ciFixes[`protect:${t.id}`] ?? 0) + 1;
      this.st.ciFixes[`protect:${t.id}`] = n;
      if (n <= 2 && worker && !this.isStopped(worker)) {
        this.sendBackToWorker(t.id, `Your branch changes files that must never be committed in this repository: ${leaked.join(', ')}. Take those changes off the branch (e.g. \`git checkout ${t.worktree ? this.fm.repos.requireWorktree(t.repoId, t.worktree).base : 'BASE'} -- <file>\` and commit), keep your other work, then update_task("${t.id}", status "review", summary).`);
      } else {
        this.fm.tasks.setStatus(t.id, 'blocked', { reason: `changes protected files: ${leaked.join(', ')}`, force: true });
        this.fm.notify('warn', `${t.id} changes protected files (${leaked.join(', ')}); not offered for landing`);
      }
      return;
    }
    if (worker) this.fm.setAgent(worker, { state: 'idle', station: 'lounge', activity: `${t.id} in review` });
    let ci: TestResult | undefined;
    // a contributor's pull request (PR intake): its tests are its code; they ran only where the worker
    // asked and the user approved, never automatically outside the policy
    const contributor = this.fm.repos.keepsContributorCommits(this.fm.repos.requireWorktree(t.repoId, t.worktree));
    if (contributor) this.fm.bus.feed('ci', `${t.id}: tests not run automatically (a contributor's pull request; the worker's approved runs count)`, { ...(worker ? { agentId: worker } : {}), taskId: t.id });
    else try {
      this.fm.tasks.update(t.id, { ci: 'running' });
      this.fm.repos.setCi(t.repoId, 'running');
      const ciCommand = this.fm.repos.testCommand(t.repoId, this.fm.repos.requireWorktree(t.repoId, t.worktree).path, this.cfg.ciCommand);
      if (worker) this.fm.agentLog(worker, 'tool', `CI: ${ciCommand ?? '(no tests)'}`);
      ci = await this.fm.repos.runTests(t.repoId, t.worktree, ciCommand);
      this.fm.tasks.update(t.id, { ci: ci.pass ? 'pass' : 'fail' });
      this.fm.repos.setCi(t.repoId, ci.pass ? 'pass' : 'fail');
      if (worker) this.fm.agentLog(worker, ci.pass ? 'result' : 'error', `CI ${ci.pass ? 'passed' : 'FAILED'} (${(ci.durationMs / 1000).toFixed(1)}s)\n${ci.output.split('\n').slice(-6).join('\n')}`);
      this.fm.bus.feed('ci', `${t.id}: tests ${ci.pass ? 'pass' : 'fail'} (${ci.command})`, { ...(worker ? { agentId: worker } : {}), taskId: t.id });
    } catch (e) {
      this.fm.log.warn(`CI for ${t.id}: ${(e as Error).message}`);
    }
    await this.fm.repos.refresh(t.repoId);
    if (ci && !ci.pass && (this.st.ciFixes[t.id] ?? 0) < 1 && worker && !this.isStopped(worker)) {
      this.st.ciFixes[t.id] = (this.st.ciFixes[t.id] ?? 0) + 1;
      this.sendBackToWorker(t.id, `CI failed for ${t.id} (${ci.command}):\n${ci.output}\n\nFix the failures, re-run the tests, then update_task("${t.id}", status "review", summary).`);
      return;
    }
    const lead = this.fm.leadOfTask(t);
    if (this.cfg.leadReview && !this.isStopped(lead)) {
      const fold = this.st.foldIns[t.id];
      const diff = await this.fm.repos.diff(t.repoId, t.worktree, fold?.from ? { from: fold.from } : {}).catch(() => this.fm.repos.diff(t.repoId!, t.worktree!));
      this.fm.setAgent(lead, { state: 'reading', station: 'mergestation', activity: `reviewing ${t.id}` });
      this.enqueue({ kind: 'review', agentId: lead, taskId: t.id, ...(t.goalId ? { goalId: t.goalId } : {}), sessionKey: `${lead}:${t.goalId ?? 'adhoc'}`, prompt: reviewPrompt(this.fm, this.fm.tasks.require(t.id), renderDiffText(diff.files), diff.stats, ci, fold ? { notes: fold.notes } : undefined) });
    } else {
      this.openMergeDecision(t, t.summary ?? 'Work complete.');
    }
  }

  protected override openMergeDecision(t: Task, summary: string): void {
    const wt = this.fm.repos.requireWorktree(t.repoId!, t.worktree!);
    const userBase = this.fm.repos.isUserBase(t.repoId!, wt.base);
    const pr = this.fm.repos.landsAsPr(t.repoId!) && !userBase;
    const fold = !!t.pr && !!this.st.foldIns[t.id];
    const lead = this.fm.leadOfTask(t);
    this.fm.createDecision({
      agentId: lead,
      kind: 'merge',
      question: fold ? `Push the review fixes for ${t.id} "${t.title}" to PR #${t.pr!.id}?` : userBase ? `Add ${t.id} "${t.title}" (${wt.branch}) to your branch ${wt.base}?` : pr ? `Open a pull request for ${t.id} "${t.title}" (${wt.branch} into ${wt.base.replace(/^[^/]+\//, '')})?` : `Merge ${t.id} "${t.title}" (${wt.branch}) into ${wt.base}?`,
      options: [...MERGE_OPTIONS],
      context: `${summary}\n${wt.files} files, +${wt.additions} -${wt.deletions} | tests: ${t.ci}${fold ? `\n"${MERGE_OPTIONS[0]}" pushes the fixes as an added commit on the pull request (no force-push, no squash of what is there).` : pr ? `\n"${MERGE_OPTIONS[0]}" pushes the branch and opens the pull request (agents never push).` : ''}`,
      taskId: t.id,
      repoId: t.repoId!,
      worktree: wt.id,
    });
    if (!this.isStopped(lead)) this.fm.setAgent(lead, { state: 'idle', station: 'mergestation', activity: `awaiting your review of ${t.id}` });
  }
}
