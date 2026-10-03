// Sim backend with a lead per building: the scripted goal is run by its building's lead, and a goal
// for another building's lead runs as a side flow (that lead plans, a free worker does it, that
// lead reviews and asks for the merge), so the mod can be tested against the sim.
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { SimBackend } from '../src/agents/sim/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const cleanup: string[] = [];
const open: Harness[] = [];
afterAll(async () => {
  for (const h of open) await h.fm.close();
  cleanup.forEach(rmrf);
});

async function boot(repos: string[]): Promise<{ h: Harness; sim: SimBackend }> {
  const home = tempDir();
  cleanup.push(home, ...repos.map((r) => path.dirname(r)));
  const h = makeForeman(home, ['--backend', 'sim', '--repo', repos.join(','), '--speed', '1000', '--no-ambient', '--auto-answer']);
  open.push(h);
  const sim = new SimBackend(h.fm, h.cfg.sim);
  await h.fm.start(sim);
  return { h, sim };
}

describe('sim backend with building leads', () => {
  it("runs the scripted goal with its building's lead", async () => {
    const { h, sim } = await boot([await demoRepo()]);
    const repo = h.fm.repos.list()[0]!.id;
    expect(h.fm.assignLead('World/b1', [repo]).leadId).toBe('ines');
    const g = await h.fm.submitGoal('Add #tags', repo);
    expect(g.leadId).toBe('ines');
    await sim.idle();
    const feed = h.fm.store.data.feed;
    expect(feed.some((f) => f.kind === 'error')).toBe(false);
    expect(feed.some((f) => f.agentId === 'ines' && /^Ines planned/.test(f.text))).toBe(true);
    const leadDecisions = h.fm.store.data.decisions.filter((d) => d.kind === 'merge');
    expect(leadDecisions.length).toBeGreaterThan(0);
    expect(new Set(leadDecisions.map((d) => d.agentId))).toEqual(new Set(['ines']));
    expect(h.fm.store.data.decisions.some((d) => d.agentId === 'marlow')).toBe(false);
    expect(h.fm.agent('ines')!.state).toBe('done');
  });

  it("runs another building's goal as a side flow by that building's lead", async () => {
    const { h, sim } = await boot([await demoRepo(), await demoRepo()]);
    const [a, b] = h.fm.repos.list().map((r) => r.id);
    expect(h.fm.assignLead('World/b2', [b!]).leadId).toBe('ines');
    const main = await h.fm.submitGoal('Add #tags', a);
    expect(main.leadId).toBeUndefined(); // marlow runs the script
    await until(() => h.fm.goal(main.id)!.status === 'active');
    const side = await h.fm.submitGoal('Document the API', b);
    expect(side.leadId).toBe('ines');
    // a second goal for the same lead while it is busy is refused, as before
    await expect(h.fm.submitGoal('Another one', b)).rejects.toThrow(/already working on a goal/);
    await sim.idle();
    expect(h.fm.store.data.feed.filter((f) => f.kind === 'error')).toEqual([]);
    expect(h.fm.goal(side.id)!.status).toBe('done');
    const sideTask = h.fm.tasks.forGoal(side.id)[0]!;
    expect(sideTask.status).toBe('done');
    expect(sideTask.createdBy).toBe('ines');
    const merges = h.fm.store.data.decisions.filter((d) => d.kind === 'merge' && d.taskId === sideTask.id);
    expect(merges.map((d) => d.agentId)).toEqual(['ines']);
    expect(h.fm.store.data.feed.some((f) => f.agentId === 'ines' && /^Ines planned Document the API into 1 task/.test(f.text))).toBe(true);
    // the scripted goal stayed marlow's
    expect(h.fm.store.data.decisions.filter((d) => d.kind === 'merge' && d.taskId !== sideTask.id).every((d) => d.agentId === 'marlow')).toBe(true);
  });
});
