// The team, plan jobs: the lead explores the repo (read-only), writes the plan and creates tasks. A
// goal that mentions pull requests on a GitHub repository gets them fetched first (PR intake,
// pulls.ts).
import { ClientError } from '../../../foreman.js';
import type { Goal } from '../../../protocol.js';
import { sessionLine } from '../../../history.js';
import { prRefs, pullBriefs, type PullRequest } from '../../../pulls.js';
import { planPrompt } from '../../prompts.js';
import { RecoveryLayer } from '../recovery.js';

export abstract class PlanJobs extends RecoveryLayer {
  async submitGoal(goal: Goal): Promise<void> {
    if (this.authFailed) {
      this.fm.setGoal(goal.id, { status: 'failed' });
      throw new ClientError(`${this.engineFor(this.fm.leadOf(goal)).label} is not available: ${this.fm.status.message ?? 'auth failed'}`);
    }
    const repo = this.fm.repos.require(goal.repoId!);
    const lead = this.fm.leadOf(goal);
    if (this.isStopped(lead)) {
      this.setStopped(lead, false);
      this.fm.bus.feed('system', `${this.fm.nameOf(lead)} is back on shift for the new goal`, { agentId: lead });
    }
    for (const w of [lead, ...this.team]) if (!this.isStopped(w)) this.fm.setAgent(w, { active: true });
    this.fm.setAgent(lead, { state: 'thinking', station: 'meeting', activity: 'reading the goal', repoId: repo.id });
    // Goal.branch ("on <branch>: ..." or goal.submit branch) continues one of the user's branches
    // (e.g. one started in Claude Desktop)
    const want = goal.branch;
    if (want) {
      try {
        const b = await this.fm.repos.useBranch(repo.id, want);
        this.st.goalBranch[goal.id] = { repoId: repo.id, ...b };
        this.fm.store.markDirty();
        this.fm.bus.feed('goal', `This goal continues your branch ${b.branch} in ${repo.name}: tasks start from it and approved work is added to it${b.onRemote ? ' (and pushed)' : ''}`, { agentId: lead, goalId: goal.id });
      } catch (e) {
        this.fm.clearGoalBranch(goal.id);
        this.fm.bus.feed('error', `Not building on "${want}": ${(e as Error).message}. Planning it as a normal goal on ${repo.branch}.`, { agentId: lead, goalId: goal.id });
      }
    }
    const gb = this.st.goalBranch[goal.id];
    let earlier = '';
    if (gb && this.history) {
      // the sessions that worked on that branch: the lead reads them before planning
      const found = await this.history.find({ branch: gb.branch, limit: 3 }).catch((e) => {
        this.fm.log.warn(`session history: ${(e as Error).message}`);
        return [];
      });
      if (found.length) {
        earlier = `\n\nEarlier Claude sessions that worked on ${gb.branch} (best match first):\n${found.map(sessionLine).join('\n')}\nRead the most relevant with read_session before planning (where it stopped, what was decided, what is left), and put what matters and the session id into the task descriptions.`;
        this.fm.bus.feed('goal', `Found ${found.length} earlier session${found.length === 1 ? '' : 's'} on ${gb.branch} for ${this.fm.nameOf(lead)} to read`, { agentId: lead, goalId: goal.id });
      }
    }
    const pulls = await this.intakePulls(goal, lead, repo.path);
    this.enqueue({ kind: 'plan', agentId: lead, goalId: goal.id, sessionKey: `${lead}:${goal.id}`, fresh: true, prompt: `${planPrompt(this.fm, goal, repo.path, gb?.branch ?? repo.branch, pulls)}${earlier}` });
  }

  /**
   * A goal that mentions pull requests ("#12") on a GitHub repository: fetch them before the lead
   * plans (see pulls.ts), announce them in the feed and keep a brief in shared memory. Tasks the lead
   * makes of them (create_task start_branch) keep the contributor's commits when they land, also as
   * a pull request of ours (land "pr", which PR watching then follows like any other); their
   * worktrees get no setup command, copied files or credential-like repository env (repos.ts).
   */
  protected async intakePulls(goal: Goal, lead: string, repoPath: string): Promise<PullRequest[]> {
    const refs = prRefs(goal.text);
    if (!refs.length || !(await this.pullFetcher.origin(repoPath).catch(() => undefined))) return [];
    const n = refs.length;
    this.fm.setAgent(lead, { state: 'reading', station: 'library', activity: `fetching ${n} pull request${n === 1 ? '' : 's'}` });
    this.fm.bus.feed('system', `Fetching ${n} pull request${n === 1 ? '' : 's'} from GitHub`, { agentId: lead, goalId: goal.id });
    const { pulls, errors } = await this.pullFetcher.fetch(repoPath, refs).catch((e: Error) => ({ pulls: [] as PullRequest[], errors: [`fetch failed: ${this.fm.redact(e.message)}`] }));
    for (const p of pulls) this.fm.bus.feed('task', `PR #${p.number} by @${p.author}: ${p.title}`, { agentId: lead, goalId: goal.id });
    for (const e of errors) this.fm.bus.feed('error', `PR ${e}`, { agentId: lead, goalId: goal.id });
    if (pulls.length) this.fm.memory.write({ scope: 'shared', title: `Pull requests for ${goal.id}`, body: pullBriefs(pulls), author: lead, mode: 'replace' });
    this.fm.setAgent(lead, { state: 'thinking', station: 'meeting', activity: 'reading the goal' });
    return pulls;
  }

  protected override promoteGoal(goal: Goal, why: string): void {
    if (goal.status !== 'planning') return;
    const n = this.fm.tasks.forGoal(goal.id).length;
    this.fm.setGoal(goal.id, { status: 'active' });
    const lead = this.fm.leadOf(goal);
    this.fm.bus.feed('plan', `${this.fm.nameOf(lead)} planned the goal into ${n} task${n === 1 ? '' : 's'}${why === 'recovered' ? ' (picked up after a restart)' : ''}`, { agentId: lead, goalId: goal.id });
    this.tick();
  }
}
