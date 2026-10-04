// Checks for the village routines QA (tools/routines-qa.mjs) over dev.routines.state replies
// (docs/VILLAGE.md V3, mod/DEV.md "Village routines"). Pure: tested in tools/test/routinesqa.test.mjs.

/** The routine label of the night routine (RoutineRules.Kind.REST.label) and its nameplate line. */
export const RESTING = 'resting';

const FACING = { south: [0, 1], west: [-1, 0], north: [0, -1], east: [1, 0] };

function cell(str) {
  const [x, y, z] = String(str ?? '').trim().split(/\s+/).map(Number);
  return [x, y, z].every(Number.isFinite) ? [Math.floor(x), Math.floor(y), Math.floor(z)] : null;
}

/** Every bed of the state, by "layout|name": {layout, name, head:[x,y,z], foot:[x,y,z], occupied, approach, agent}. */
export function bedsOf(state) {
  const out = new Map();
  for (const [layout, list] of Object.entries(state?.beds ?? {})) {
    for (const b of list ?? []) {
      const head = cell(b.head);
      const f = FACING[b.facing] ?? [0, 0];
      const foot = head ? [head[0] - f[0], head[1], head[2] - f[1]] : null;
      out.set(`${layout}|${b.name}`, { ...b, layout, head, foot });
    }
  }
  return out;
}

/**
 * Night: at least one agent rests in a bed (routine "resting", lying "bed..", plate "resting", standing in its bed's
 * head cell), every lying agent rests and lies in a bed of its own layout, and no two agents share a bed.
 * Returns {ok, reasons[], lying[]} (lying: the ids in bed).
 */
export function checkNight(state) {
  const reasons = [];
  if (!state?.night) reasons.push(`not night yet (timeOfDay ${state?.timeOfDay})`);
  if (state?.settings && state.settings.night === false) reasons.push('the night routine is off for this world');
  const beds = bedsOf(state);
  if (beds.size === 0) reasons.push('no beds found in any layout near the agents (place a building with beds, Overworld)');
  const agents = state?.agents ?? [];
  const lying = [];
  const taken = new Map();
  for (const a of agents) {
    if (!a.lying) continue;
    lying.push(a.id);
    if (a.routine !== RESTING) reasons.push(`${a.id} lies in ${a.lying} but its routine is ${a.routine}`);
    if (!/^bed(_\d+)?/.test(a.lying)) reasons.push(`${a.id} lies in '${a.lying}', not a bed anchor`);
    if (a.plate !== RESTING) reasons.push(`${a.id} lies in bed with plate '${a.plate}' (want '${RESTING}')`);
    const bed = beds.get(`${a.layout}|${a.lying}`);
    if (!bed) {
      reasons.push(`${a.id} lies in ${a.lying} which is not a bed of its layout ${a.layout}`);
    } else {
      const p = cell(a.pos);
      if (!p || p[0] !== bed.head[0] || p[2] !== bed.head[2]) reasons.push(`${a.id} lies at ${a.pos}, not on ${a.lying}'s head ${bed.head.join(' ')}`);
    }
    const key = `${a.layout}|${a.lying}`;
    if (taken.has(key)) reasons.push(`${a.id} and ${taken.get(key)} share ${key}`);
    taken.set(key, a.id);
  }
  if (lying.length === 0) {
    const resting = agents.filter((a) => a.routine === RESTING).map((a) => `${a.id}->${a.target}${a.walking ? ' (walking)' : ''}`);
    reasons.push(`nobody lies in a bed yet; resting: ${resting.join(', ') || 'none'}`);
  }
  return { ok: reasons.length === 0, reasons, lying };
}

/**
 * Morning: nobody rests or lies any more, and the agents that lay in a bed at night (wereLying) stand outside both
 * halves of every bed. Returns {ok, reasons[]}.
 */
export function checkMorning(state, wereLying = []) {
  const reasons = [];
  if (state?.night) reasons.push(`still night (timeOfDay ${state?.timeOfDay})`);
  const beds = [...bedsOf(state).values()];
  for (const a of state?.agents ?? []) {
    if (a.lying) reasons.push(`${a.id} still lies in ${a.lying}`);
    if (a.routine === RESTING) reasons.push(`${a.id} still rests`);
    if (a.plate === RESTING) reasons.push(`${a.id} still shows '${RESTING}'`);
    if (!wereLying.includes(a.id)) continue;
    const p = cell(a.pos);
    for (const b of beds) {
      for (const c of [b.head, b.foot]) {
        if (p && c && p[0] === c[0] && p[2] === c[2] && Math.abs(p[1] - c[1]) <= 1) reasons.push(`${a.id} got up inside ${b.layout}|${b.name} (${a.pos})`);
      }
    }
  }
  return { ok: reasons.length === 0, reasons };
}
