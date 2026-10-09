// Claude backend, plan jobs: the lead explores the repo (read-only), writes the plan and creates tasks.
import { ClientError } from '../../../foreman.js';
import type { Goal } from '../../../protocol.js';
import { sessionLine } from '../../../history.js';
import { planPrompt } from '../../prompts.js';
import { RecoveryLayer } from '../recovery.js';

export abstract class PlanJobs extends RecoveryLayer {
  async submitGoal(goal: Goal): Promise<void> {
    if (this.authFailed) {
      this.fm.setGoal(goal.id, { status: 'failed' });
      throw new ClientError(`Claude is not available: ${this.fm.status.message ?? 'auth failed'}`);
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
    this.enqueue({ kind: 'plan', agentId: lead, goalId: goal.id, sessionKey: `${lead}:${goal.id}`, fresh: true, prompt: `${planPrompt(this.fm, goal, repo.path, gb?.branch ?? repo.branch)}${earlier}` });
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
