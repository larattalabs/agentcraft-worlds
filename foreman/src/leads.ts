// A lead per building (docs/PRWATCH.md "A lead per building").
//
// Each building of the mod's world that holds repositories gets its own lead; marlow leads home,
// repositories without a building and anything not tied to a repository. The workers stay one
// shared pool every lead assigns from.
//
// State: store.data.leads { [leadId]: { building, repos, assignedAt } } for every lead but marlow
// that leads a building. Assignment order is claude.leads (config.ts: always marlow first). This
// module is the bookkeeping only; the Foreman applies the consequences (roster visibility, goals
// moving to marlow, protocol messages) and the backends run the leads.
import { LEAD_ID } from './cast.js';
import type { Ctx } from './context.js';
import type { LeadAssignment } from './protocol.js';
import type { LeadRecord } from './store.js';

export const HOME_LEAD = LEAD_ID;

export interface AssignResult {
  leadId: string;
  /** no lead was free: marlow leads the building, nothing was stored */
  overflow?: true;
  /** a lead was newly assigned to this building */
  created: boolean;
  /** the stored assignment changed (new, or its repos) */
  changed: boolean;
  /** repositories taken from another building: [repo, from lead] */
  moved: Array<{ repo: string; from: string }>;
}

export class LeadBook {
  /** building leads in assignment order (claude.leads without marlow) */
  readonly order: string[];

  constructor(
    private ctx: Ctx,
    leads: readonly string[],
  ) {
    this.order = leads.filter((id) => id !== HOME_LEAD);
  }

  private get data(): Record<string, LeadRecord> {
    return (this.ctx.store.data.leads ??= {});
  }

  /** A lead id this Foreman uses (marlow, or one of claude.leads). */
  isLead(id: string): boolean {
    return id === HOME_LEAD || this.order.includes(id);
  }

  /** marlow, or a building lead that is assigned (the others are off shift and hidden). */
  onDuty(id: string): boolean {
    return id === HOME_LEAD || !!this.data[id];
  }

  record(leadId: string): LeadRecord | undefined {
    return this.data[leadId];
  }

  /** every lead on duty: marlow first, then the assigned building leads in claude.leads order */
  onDutyIds(): string[] {
    return [HOME_LEAD, ...this.order.filter((id) => this.data[id]), ...Object.keys(this.data).filter((id) => !this.order.includes(id))];
  }

  /** The lead whose building has this repository, else marlow. */
  leadForRepo(repoId: string | undefined): string {
    if (!repoId) return HOME_LEAD;
    for (const id of this.onDutyIds()) if (this.data[id]?.repos.includes(repoId)) return id;
    return HOME_LEAD;
  }

  leadOfBuilding(building: string): string | undefined {
    return Object.keys(this.data).find((id) => this.data[id]!.building === building);
  }

  /** What `leads.update` / `snapshot.leads` carry: marlow (no building) first. */
  list(): LeadAssignment[] {
    return this.onDutyIds().map((id) => {
      const r = this.data[id];
      return r ? { leadId: id, building: r.building, repos: [...r.repos] } : { leadId: id, repos: [] };
    });
  }

  /**
   * lead.assign. Idempotent per building (its repos are updated); a new building takes the first
   * free lead in order; none free -> marlow, nothing stored. A repository listed here leaves any
   * other building that had it.
   */
  assign(building: string, repos: string[]): AssignResult {
    const want = [...new Set(repos)];
    let leadId = this.leadOfBuilding(building);
    let created = false;
    if (!leadId) {
      leadId = this.order.find((id) => !this.data[id]);
      if (!leadId) return { leadId: HOME_LEAD, overflow: true, created: false, changed: false, moved: [] };
      this.data[leadId] = { building, repos: [], assignedAt: this.ctx.now() };
      created = true;
    }
    const rec = this.data[leadId]!;
    const moved: AssignResult['moved'] = [];
    for (const [other, r] of Object.entries(this.data)) {
      if (other === leadId) continue;
      const keep = r.repos.filter((x) => !want.includes(x));
      for (const x of r.repos) if (want.includes(x)) moved.push({ repo: x, from: other });
      r.repos = keep;
    }
    const changed = created || moved.length > 0 || rec.repos.length !== want.length || rec.repos.some((x, i) => x !== want[i]);
    rec.repos = want;
    if (changed) this.ctx.store.markDirty();
    return { leadId, created, changed, moved };
  }

  /** Forget a lead's assignment (the Foreman moves its goals); returns the record. */
  remove(leadId: string): LeadRecord | undefined {
    const r = this.data[leadId];
    if (!r) return undefined;
    delete this.data[leadId];
    this.ctx.store.markDirty();
    return r;
  }

  /** Stored assignments of leads that are no longer in claude.leads (config changed). */
  stale(): string[] {
    return Object.keys(this.data).filter((id) => !this.order.includes(id));
  }

  /** Buildings of a world: `"<world>/..."` keys and their leads. */
  buildingsOf(world: string): Array<{ leadId: string; building: string }> {
    return Object.entries(this.data)
      .filter(([, r]) => r.building.startsWith(`${world}/`))
      .map(([leadId, r]) => ({ leadId, building: r.building }));
  }
}
