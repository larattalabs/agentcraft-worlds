// The shared cast. Final colors/descriptions are owned by the art track in assets-src/cast.json
// ({id,name,role,color,accent,description}[]); we read it if present and fall back to placeholders.
import fs from 'node:fs';
import path from 'node:path';
import type { AgentRole } from './protocol.js';

export interface CastMember {
  id: string;
  name: string;
  role: AgentRole;
  title: string;
  color: string;
  accent?: string;
  description: string;
}

/** the home lead: home, repositories without a building, anything not tied to a repository */
export const LEAD_ID = 'marlow';
/** the building leads of the default cast, in assignment order (claude.leads) */
export const BUILDING_LEAD_IDS = ['ines', 'bram', 'cass'] as const;
export const WORKER_IDS = ['juniper', 'kit', 'wren', 'rowan', 'tove'] as const;

const PLACEHOLDER: CastMember[] = [
  { id: 'marlow', name: 'Marlow', role: 'lead', title: 'Lead', color: '#D97757', accent: '#3B2A20', description: 'Plans the work, splits it into tasks, reviews and asks you when it matters.' },
  { id: 'ines', name: 'Ines', role: 'lead', title: 'Lead', color: '#7E6BA8', accent: '#F4EFE6', description: 'Leads a building: plans its goals, reviews its work, asks you when it matters.' },
  { id: 'bram', name: 'Bram', role: 'lead', title: 'Lead', color: '#5E8C6A', accent: '#F4EFE6', description: 'Leads a building: plans its goals, reviews its work, asks you when it matters.' },
  { id: 'cass', name: 'Cass', role: 'lead', title: 'Lead', color: '#C0727E', accent: '#2A1F1E', description: 'Leads a building: plans its goals, reviews its work, asks you when it matters.' },
  { id: 'juniper', name: 'Juniper', role: 'worker', title: 'Worker', color: '#8FA98B', accent: '#F4EFE6', description: 'Careful generalist; likes CLIs and UX details.' },
  { id: 'kit', name: 'Kit', role: 'worker', title: 'Worker', color: '#2FA3A0', accent: '#1F1E1D', description: 'Fast backend tinkerer; writes the tests first.' },
  { id: 'wren', name: 'Wren', role: 'worker', title: 'Worker', color: '#C9A227', accent: '#3B2A20', description: 'Front-of-house polish: output, colors, docs.' },
  { id: 'rowan', name: 'Rowan', role: 'worker', title: 'Worker', color: '#B4553A', accent: '#E9E1D3', description: 'Documentation and release hygiene.' },
  { id: 'tove', name: 'Tove', role: 'worker', title: 'Worker', color: '#6F8FB5', accent: '#F4EFE6', description: 'Performance and tooling.' },
];

const HEX = /^#[0-9A-Fa-f]{6}$/;
/** colours for configured lead ids that have no cast entry */
const GENERATED_COLORS = ['#8A6FB0', '#4E8F8B', '#B07A3C', '#6A7FB8', '#A35C7A'];

/** A configured lead id (claude.leads) with no cast entry anywhere: its name is the capitalised id. */
export function generatedLead(id: string, index = 0): CastMember {
  return {
    id,
    name: id.charAt(0).toUpperCase() + id.slice(1),
    role: 'lead',
    title: 'Lead',
    color: GENERATED_COLORS[index % GENERATED_COLORS.length]!,
    description: 'Leads a building: plans its goals, reviews its work, asks you when it matters.',
  };
}

/**
 * The cast, plus a generated member for every lead id in `leadIds` that neither the placeholder
 * list nor cast.json knows (cast.json may lack the newer leads while the art track catches up).
 */
export function loadCast(projectRoot: string | undefined, leadIds: readonly string[] = []): { cast: CastMember[]; source: string } {
  const r = loadCastFile(projectRoot);
  const extra = leadIds.filter((id) => !r.cast.some((c) => c.id === id));
  if (!extra.length) return r;
  return { ...r, cast: [...r.cast, ...extra.map((id, i) => r.extra.get(id) ?? generatedLead(id, i))] };
}

function loadCastFile(projectRoot: string | undefined): { cast: CastMember[]; source: string; extra: Map<string, CastMember> } {
  const extra = new Map<string, CastMember>();
  const file = projectRoot ? path.join(projectRoot, 'assets-src', 'cast.json') : undefined;
  if (!file || !fs.existsSync(file)) return { cast: PLACEHOLDER, source: 'placeholder', extra };
  try {
    const raw = JSON.parse(fs.readFileSync(file, 'utf8')) as unknown;
    // accepted shapes: [...], {agents:[...]}, {cast:[...]}
    const obj = raw as { agents?: unknown; cast?: unknown };
    const arr = Array.isArray(raw) ? raw : Array.isArray(obj.agents) ? obj.agents : Array.isArray(obj.cast) ? obj.cast : [];
    const byId = new Map<string, Record<string, unknown>>();
    for (const r of arr as Array<Record<string, unknown>>) if (typeof r?.id === 'string') byId.set(r.id, r);
    const cast = PLACEHOLDER.map((p) => {
      const r = byId.get(p.id);
      if (!r) return p;
      const title =
        typeof r.title === 'string' && r.title ? r.title : typeof r.role === 'string' && !['lead', 'worker'].includes(r.role) ? r.role : p.title;
      return {
        ...p,
        name: typeof r.name === 'string' && r.name ? r.name : p.name,
        title,
        color: typeof r.color === 'string' && HEX.test(r.color) ? r.color : p.color,
        accent: typeof r.accent === 'string' && HEX.test(r.accent) ? r.accent : p.accent,
        description: typeof r.description === 'string' ? r.description : p.description,
      } satisfies CastMember;
    });
    // leads cast.json has that the placeholder list does not (used only when configured)
    let i = 0;
    for (const [id, r] of byId) {
      if (r.role !== 'lead' || PLACEHOLDER.some((p) => p.id === id)) continue;
      const g = generatedLead(id, i++);
      extra.set(id, {
        ...g,
        name: typeof r.name === 'string' && r.name ? r.name : g.name,
        title: typeof r.title === 'string' && r.title ? r.title : g.title,
        color: typeof r.color === 'string' && HEX.test(r.color) ? r.color : g.color,
        ...(typeof r.accent === 'string' && HEX.test(r.accent) ? { accent: r.accent } : {}),
        description: typeof r.description === 'string' ? r.description : g.description,
      });
    }
    return { cast, source: file, extra };
  } catch {
    return { cast: PLACEHOLDER, source: 'placeholder (cast.json unreadable)', extra };
  }
}
