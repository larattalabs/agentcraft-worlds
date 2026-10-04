// A lead per building: assignment rules (lead.assign / lead.release / lead.sync), roster
// visibility, goals routed to their building's lead, and config / store compatibility.
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { loadCast } from '../src/cast.js';
import { leadsList, loadConfig } from '../src/config.js';
import { parseClientMessage, type Outbound } from '../src/protocol.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const homes: string[] = [];
const open: Harness[] = [];

function fresh(args: string[] = [], home = tempDir()): Harness {
  homes.push(home);
  const h = makeForeman(home, args);
  open.push(h);
  return h;
}

afterEach(async () => {
  for (const h of open.splice(0)) await h.fm.close();
  for (const d of homes.splice(0)) rmrf(d);
});

const ack = async (h: Harness, msg: Record<string, unknown>) => {
  const out: Outbound[] = [];
  await h.fm.handle({ v: 1, id: 'c1', ...msg } as never, (m) => out.push(m));
  const a = out.find((m) => m.type === 'ack');
  if (!a || a.type !== 'ack') throw new Error('no ack');
  return a;
};

const leadsOf = (h: Harness) => h.fm.leads.list().map((l) => `${l.leadId}${l.building ? `@${l.building}` : ''}:${l.repos.join('+')}`);

describe('config and cast', () => {
  it('normalizes claude.leads: marlow first; [] or one entry = marlow alone', () => {
    expect(leadsList(undefined)).toEqual(['marlow', 'ines', 'bram', 'cass']);
    expect(leadsList([])).toEqual(['marlow']);
    expect(leadsList(['marlow'])).toEqual(['marlow']);
    expect(leadsList(['ines'])).toEqual(['marlow']);
    expect(leadsList('bram, Ines,bram')).toEqual(['marlow', 'bram', 'ines']);
    expect(leadsList(['ines', 'marlow', 'cass'])).toEqual(['marlow', 'ines', 'cass']);
    expect(() => leadsList(['bad id'])).toThrow(/bad lead id/);
    const home = tempDir();
    homes.push(home);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { leads: ['marlow', 'cass'], maxConcurrentTurns: 4 } }));
    const c = loadConfig(['--home', home], {}).claude;
    expect(c.leads).toEqual(['marlow', 'cass']);
    expect(c.maxConcurrentTurns).toBe(4);
    expect(loadConfig(['--home', home, '--leads', 'marlow'], {}).claude.leads).toEqual(['marlow']);
    expect(loadConfig(['--home', tempDir()], {}).claude.maxConcurrentTurns).toBeUndefined();
  });

  it('has Ines, Bram and Cass as leads, and names a configured lead without a cast entry after its id', () => {
    const { cast } = loadCast(undefined, ['marlow', 'ines', 'zora']);
    expect(cast.filter((c) => c.role === 'lead').map((c) => `${c.id}:${c.name}`)).toEqual(['marlow:Marlow', 'ines:Ines', 'bram:Bram', 'cass:Cass', 'zora:Zora']);
    // the project cast.json may not have the new leads yet: they still exist
    const real = loadCast(path.resolve(__dirname, '..', '..'), ['marlow', 'ines', 'bram', 'cass']).cast;
    for (const id of ['ines', 'bram', 'cass']) expect(real.find((c) => c.id === id)?.role).toBe('lead');
  });
});

describe('lead.assign / lead.release / lead.sync', () => {
  it('gives a new building the first free lead in order, and is idempotent per building', async () => {
    const h = fresh();
    expect(leadsOf(h)).toEqual(['marlow:']);
    const a = await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    expect(a.ok).toBe(true);
    expect(a.result).toEqual({ leadId: 'ines' });
    expect((await ack(h, { type: 'lead.assign', building: 'w1/b2', repos: ['api'] })).result).toEqual({ leadId: 'bram' });
    // same building again: same lead, repos updated
    expect((await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app', 'docs'] })).result).toEqual({ leadId: 'ines' });
    expect(leadsOf(h)).toEqual(['marlow:', 'ines@w1/b1:app+docs', 'bram@w1/b2:api']);
    expect(h.fm.leads.leadForRepo('docs')).toBe('ines');
    expect(h.fm.leads.leadForRepo('other')).toBe('marlow');
    expect(h.fm.leads.leadForRepo(undefined)).toBe('marlow');
    const updates = h.events.filter((m) => m.type === 'leads.update');
    expect(updates.length).toBe(3);
    // the same assignment once more changes nothing
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app', 'docs'] });
    expect(h.events.filter((m) => m.type === 'leads.update').length).toBe(3);
  });

  it('overflows to marlow when no lead is free (nothing stored, no repo moves)', async () => {
    const h = fresh(['--leads', 'marlow,ines']);
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    const a = await ack(h, { type: 'lead.assign', building: 'w1/b2', repos: ['app', 'api'] });
    expect(a.result).toEqual({ leadId: 'marlow', overflow: true });
    expect(leadsOf(h)).toEqual(['marlow:', 'ines@w1/b1:app']);
    expect(h.fm.leads.leadForRepo('app')).toBe('ines');
  });

  it('with one lead configured every building overflows to marlow (today\'s behaviour)', async () => {
    const h = fresh(['--leads', 'marlow']);
    expect((await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] })).result).toEqual({ leadId: 'marlow', overflow: true });
    expect(leadsOf(h)).toEqual(['marlow:']);
    expect(h.fm.agents().filter((a) => a.role === 'lead').map((a) => a.id)).toEqual(['marlow']);
  });

  it('moves a repository listed by another building', async () => {
    const h = fresh();
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app', 'api'] });
    await ack(h, { type: 'lead.assign', building: 'w1/b2', repos: ['api'] });
    expect(leadsOf(h)).toEqual(['marlow:', 'ines@w1/b1:app', 'bram@w1/b2:api']);
    expect(h.fm.leads.leadForRepo('api')).toBe('bram');
  });

  it('shows a building lead only while it is assigned', async () => {
    const h = fresh();
    const snapAgents = () => {
      const s = h.fm.snapshot();
      return s.type === 'snapshot' ? s.agents.filter((a) => a.role === 'lead').map((a) => a.id) : [];
    };
    expect(snapAgents()).toEqual(['marlow']);
    expect(h.fm.resolveAgentId('@ines')).toBeUndefined();
    h.fm.setAgent('ines', { activity: 'hidden' }); // no upsert for a lead that is not assigned
    expect(h.events.some((m) => m.type === 'agent.upsert' && m.agent.id === 'ines')).toBe(false);
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    expect(snapAgents()).toEqual(['marlow', 'ines']);
    expect(h.fm.resolveAgentId('@Ines')).toBe('ines');
    const up = h.events.flatMap((m) => (m.type === 'agent.upsert' && m.agent.id === 'ines' ? [m.agent] : []));
    expect(up.at(-1)?.active).toBe(true);
    const s = h.fm.snapshot();
    expect(s.type === 'snapshot' && s.leads).toEqual([{ leadId: 'marlow', repos: [] }, { leadId: 'ines', building: 'w1/b1', repos: ['app'], world: 'w1', lastSync: expect.any(Number) }]);
    await ack(h, { type: 'lead.release', building: 'w1/b1' });
    // one last upsert: off shift, so the mod walks it home
    const last = h.events.filter((m) => m.type === 'agent.upsert' && m.agent.id === 'ines').at(-1);
    expect(last?.type === 'agent.upsert' && [last.agent.active, last.agent.station]).toEqual([false, 'lounge']);
    expect(snapAgents()).toEqual(['marlow']);
  });

  it('sets a goal\'s lead from its repository at submit; when the repository moves, its open goals follow (C3)', async () => {
    const h = fresh();
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app', 'api'] });
    const g1 = h.fm.createGoal('in the building', 'app');
    const g2 = h.fm.createGoal('elsewhere', 'other');
    const g3 = h.fm.createGoal('no repo');
    const done = h.fm.createGoal('finished', 'app');
    h.fm.setGoal(done.id, { status: 'done' });
    expect([g1.leadId, g2.leadId, g3.leadId]).toEqual(['ines', undefined, undefined]);
    expect([h.fm.leadOf(g1), h.fm.leadOf(g2), h.fm.leadOf(g3)]).toEqual(['ines', 'marlow', 'marlow']);
    await ack(h, { type: 'lead.assign', building: 'w1/b2', repos: ['app'] });
    expect(h.fm.goal(g1.id)!.leadId).toBe('bram');
    expect(h.fm.goal(done.id)!.leadId).toBe('ines'); // history keeps who ran it
    expect(h.fm.store.data.feed.some((f) => /Bram takes over g1 "in the building" from Ines \(its repository is in Bram's building\)/.test(f.text))).toBe(true);
    expect(h.fm.leadOfTask(h.fm.tasks.create({ title: 'x', createdBy: 'user', goalId: g1.id }))).toBe('bram');
    expect(h.fm.leadOfTask(h.fm.tasks.create({ title: 'adhoc', createdBy: 'user', repoId: 'app' }))).toBe('bram');
    expect(h.fm.currentGoalOf('bram')?.id).toBe(g1.id);
    expect(h.fm.currentGoalOf('marlow')?.id).toBe(g3.id);
    expect(leadsOf(h)).toEqual(['marlow:', 'ines@w1/b1:api', 'bram@w1/b2:app']); // ines keeps api
  });

  it('adopts marlow\'s open goals when their repository gets a building (re-placed building, C3)', async () => {
    const h = fresh();
    const g = h.fm.createGoal('before the building', 'app');
    h.fm.bus.send('user', 'marlow', 'how is it going?', { goalId: g.id, goalMessage: true });
    expect(h.fm.leadOf(g)).toBe('marlow');
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    expect(h.fm.goal(g.id)!.leadId).toBe('ines');
    expect(h.fm.bus.goalInbox('ines', g.id).map((m) => m.text)).toEqual(['how is it going?']); // unread message follows
    expect(h.fm.bus.goalInbox('marlow', g.id)).toEqual([]);
    // idempotent: the same assign again moves nothing
    const feed = h.fm.store.data.feed.length;
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    expect(h.fm.store.data.feed.length).toBe(feed);
  });

  it('a building left without repositories frees its lead (moved away, or assigned [])', async () => {
    const h = fresh();
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    await ack(h, { type: 'lead.assign', building: 'w1/b2', repos: ['web'] });
    expect(leadsOf(h)).toEqual(['marlow:', 'ines@w1/b1:app', 'bram@w1/b2:web']);
    // b2 takes app: b1 has nothing left -> ines is free again
    await ack(h, { type: 'lead.assign', building: 'w1/b2', repos: ['web', 'app'] });
    expect(leadsOf(h)).toEqual(['marlow:', 'bram@w1/b2:web+app']);
    expect(h.fm.store.data.feed.some((f) => /Ines no longer leads building b1 \(its building has no repositories left\)/.test(f.text))).toBe(true);
    // the building's repos were all taken away by the user
    expect((await ack(h, { type: 'lead.assign', building: 'w1/b2', repos: [] })).result).toEqual({ leadId: 'marlow' });
    expect(leadsOf(h)).toEqual(['marlow:']);
    expect((await ack(h, { type: 'lead.assign', building: 'w1/b3', repos: ['x'] })).result).toEqual({ leadId: 'ines' });
  });

  it('lead.releaseWorld frees another world\'s leads; worlds not synced for leadWorldTtlDays expire (C2)', async () => {
    const home = tempDir();
    const h = fresh([], home);
    await ack(h, { type: 'lead.sync', world: 'Dev HQ', buildings: [{ building: 'Dev HQ/b1', repos: ['app'] }] });
    await ack(h, { type: 'lead.sync', world: 'Hardcore', buildings: [{ building: 'Hardcore/b1', repos: ['web'] }] });
    const g = h.fm.createGoal('in the dev world', 'app');
    expect(h.fm.leadOf(g)).toBe('ines');
    const listed = h.fm.leads.list();
    expect(listed.find((l) => l.leadId === 'ines')).toMatchObject({ world: 'Dev HQ', lastSync: expect.any(Number) });
    const a = await ack(h, { type: 'lead.releaseWorld', world: 'Dev HQ' });
    expect(a.result).toEqual({ released: ['ines'] });
    expect(leadsOf(h)).toEqual(['marlow:', 'bram@Hardcore/b1:web']);
    expect(h.fm.goal(g.id)!.leadId).toBeUndefined(); // marlow's again
    expect((await ack(h, { type: 'lead.releaseWorld', world: 'Nowhere' })).result).toEqual({ released: [] });
    // expiry: Hardcore last synced 20 days ago
    h.fm.store.data.leadWorlds!['Hardcore'] = Date.now() - 20 * 86_400_000;
    expect(h.fm.expireLeadWorlds()).toEqual(['bram']);
    expect(leadsOf(h)).toEqual(['marlow:']);
  });

  it('a world with leads but no lastSync yet (older state) starts its clock instead of expiring', async () => {
    const h = fresh();
    await ack(h, { type: 'lead.assign', building: 'old/b1', repos: ['app'] });
    delete h.fm.store.data.leadWorlds!['old'];
    h.fm.store.data.leads['ines']!.assignedAt = Date.now() - 100 * 86_400_000;
    expect(h.fm.expireLeadWorlds()).toEqual([]);
    expect(h.fm.store.data.leadWorlds!['old']).toBeGreaterThan(Date.now() - 5000);
    expect(leadsOf(h)).toEqual(['marlow:', 'ines@old/b1:app']);
  });

  it('release frees the lead and moves its open goals to marlow', async () => {
    const h = fresh();
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    const open = h.fm.createGoal('open one', 'app');
    const done = h.fm.createGoal('done one', 'app');
    h.fm.setGoal(done.id, { status: 'done' });
    const a = await ack(h, { type: 'lead.release', building: 'w1/b1' });
    expect(a.result).toEqual({});
    expect(h.fm.goal(open.id)!.leadId).toBeUndefined();
    expect(h.fm.goal(done.id)!.leadId).toBe('ines'); // history keeps who ran it
    expect(h.events.some((m) => m.type === 'goal.upsert' && m.goal.id === open.id && m.goal.leadId === undefined)).toBe(true);
    expect(h.fm.store.data.feed.some((f) => /Marlow takes over g\d+ "open one" from Ines/.test(f.text))).toBe(true);
    expect(leadsOf(h)).toEqual(['marlow:']);
    // the freed lead is the first free one again
    expect((await ack(h, { type: 'lead.assign', building: 'w1/b9', repos: ['z'] })).result).toEqual({ leadId: 'ines' });
    // unknown building: nothing to do
    expect((await ack(h, { type: 'lead.release', building: 'w1/nope' })).ok).toBe(true);
  });

  it('sync releases the world\'s missing buildings first, then assigns (freed leads are reused)', async () => {
    const h = fresh(['--leads', 'marlow,ines,bram']);
    await ack(h, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    await ack(h, { type: 'lead.assign', building: 'w1/b2', repos: ['api'] });
    await ack(h, { type: 'lead.assign', building: 'w2/b1', repos: ['other'] }); // overflow: both leads busy
    const before = h.events.filter((m) => m.type === 'leads.update').length;
    const a = await ack(h, { type: 'lead.sync', world: 'w1', buildings: [{ building: 'w1/b2', repos: ['api'] }, { building: 'w1/b3', repos: ['web'] }] });
    expect(a.result).toEqual({ leads: { 'w1/b2': 'bram', 'w1/b3': 'ines' } });
    expect(leadsOf(h)).toEqual(['marlow:', 'ines@w1/b3:web', 'bram@w1/b2:api']);
    expect(h.events.filter((m) => m.type === 'leads.update').length).toBe(before + 1);
    // another world's buildings are untouched by w1's sync
    await ack(h, { type: 'lead.release', building: 'w1/b3' });
    await ack(h, { type: 'lead.assign', building: 'w2/b1', repos: ['other'] });
    await ack(h, { type: 'lead.sync', world: 'w1', buildings: [{ building: 'w1/b2', repos: ['api'] }] });
    expect(leadsOf(h)).toEqual(['marlow:', 'ines@w2/b1:other', 'bram@w1/b2:api']);
  });

  it('refuses malformed building keys and worlds', () => {
    expect(parseClientMessage({ v: 1, type: 'lead.assign', building: 'nobuilding', repos: [] }).ok).toBe(false);
    expect(parseClientMessage({ v: 1, type: 'lead.release', building: 'w1/' }).ok).toBe(false);
    expect(parseClientMessage({ v: 1, type: 'lead.sync', world: 'a/b', buildings: [] }).ok).toBe(false);
    expect(parseClientMessage({ v: 1, type: 'lead.sync', world: 'New World', buildings: [{ building: 'New World/b1', repos: ['x'] }] }).ok).toBe(true);
  });
});

describe('persistence', () => {
  it('keeps assignments across restarts and releases leads dropped from claude.leads', async () => {
    const home = tempDir();
    const h1 = fresh([], home);
    await ack(h1, { type: 'lead.assign', building: 'w1/b1', repos: ['app'] });
    await ack(h1, { type: 'lead.assign', building: 'w1/b2', repos: ['api'] });
    const g = h1.fm.createGoal('api work', 'api');
    expect(g.leadId).toBe('bram');
    await h1.fm.close();
    open.splice(open.indexOf(h1), 1);

    const h2 = fresh([], home);
    expect(leadsOf(h2)).toEqual(['marlow:', 'ines@w1/b1:app', 'bram@w1/b2:api']);
    await h2.fm.close();
    open.splice(open.indexOf(h2), 1);

    // bram is no longer configured: his building goes back to marlow, and so does his goal
    const h3 = fresh(['--leads', 'marlow,ines'], home);
    expect(leadsOf(h3)).toEqual(['marlow:', 'ines@w1/b1:app']);
    expect(h3.fm.goal(g.id)!.leadId).toBeUndefined();
    expect(h3.fm.agents().map((a) => a.id)).not.toContain('bram');
  });

  it('loads a state file from before leads existed', () => {
    const home = tempDir();
    const dir = path.join(home, 'claude');
    fs.mkdirSync(dir, { recursive: true });
    const old = { version: 1, createdAt: 1, agents: [], tasks: [], decisions: [], repos: [], goals: [{ id: 'g1', text: 'old', progress: 0, status: 'active', repoId: 'app', createdAt: 1, updatedAt: 1 }], feed: [], messages: [], counters: { g: 1 }, sessions: { 'marlow:g1': { sessionId: 's-old', turns: 3, costUsd: 0, updatedAt: 1 } }, worktreeMeta: {}, permissionRules: {}, backend: {} };
    fs.writeFileSync(path.join(dir, 'state.json'), JSON.stringify(old));
    const h = fresh(['--backend', 'claude'], home);
    expect(h.fm.store.data.leads).toEqual({});
    expect(h.fm.leadOf(h.fm.goal('g1'))).toBe('marlow');
    expect(h.fm.store.data.sessions['marlow:g1']?.sessionId).toBe('s-old');
    expect(h.fm.agent('ines')?.active).toBe(false);
    expect(h.fm.agent('marlow')?.active).toBe(true);
  });
});
