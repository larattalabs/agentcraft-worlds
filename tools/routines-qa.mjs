#!/usr/bin/env node
// Village routines QA (docs/VILLAGE.md V3): night rest in beds and the morning return, asserted through the
// DevBridge against a running game. Needs a dev world with a placed building that has beds (any bundled
// blueprint, in the Overworld) and some idle agents routed to it (a Foreman, sim backend is fine), e.g.
//   node tools/mac.mjs launch --backend sim --dev --world "Village QA" --preset normal
//   node tools/devcli.mjs cmd "/agentcraft place workshop <repo>"     (or the hub's Buildings wizard)
//   node tools/routines-qa.mjs [--port 7879] [--timeout 90] [--no-restore] [--shot]
//
// Steps: night routine on for this world; dev.routines.time night; poll dev.routines.state until an agent lies in
// a bed (routine "resting", lying "bed..", plate "resting", on its bed's head); dev.routines.time morning; poll until
// nobody rests or lies and the sleepers stood up beside their beds. Then the old time of day is set back (unless
// --no-restore). --shot takes a screenshot of the first bed at night (artifacts/shots/routines_qa_night.png).
// Prints a JSON summary; exit 0 when both checks pass, 1 when one fails or times out, 2 on a bridge error.

import { DevClient } from './lib/devclient.mjs';
import { checkMorning, checkNight, bedsOf } from './lib/routinesqa.mjs';

const argv = process.argv.slice(2);
const opt = {};
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (!a.startsWith('--')) continue;
  const k = a.slice(2);
  if (['port', 'timeout'].includes(k)) opt[k] = argv[++i];
  else opt[k] = true;
}
const port = Number(opt.port ?? process.env.AGENTCRAFT_DEV_PORT ?? 7879);
const timeoutMs = Number(opt.timeout ?? 90) * 1000;
const log = (m) => process.stderr.write(`[routines-qa] ${m}\n`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function poll(dev, label, check) {
  const until = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < until) {
    const state = await dev.call('dev.routines.state');
    last = { state, result: check(state) };
    if (last.result.ok) return last;
    await sleep(1000);
  }
  log(`${label}: timed out after ${timeoutMs / 1000}s: ${last?.result.reasons.join('; ')}`);
  return last;
}

const summary = { night: null, morning: null };
let dev;
try {
  dev = await DevClient.connect({ port });
  await dev.waitInWorld({ timeoutMs: 120_000 });
  const before = await dev.call('dev.routines.state');
  summary.startTimeOfDay = before.timeOfDay;
  await dev.call('dev.routines.toggle', { toggle: 'night', on: true });

  log('night');
  await dev.call('dev.routines.time', { at: 'night' });
  const night = await poll(dev, 'night', checkNight);
  summary.night = { ok: night.result.ok, reasons: night.result.reasons, lying: night.result.lying, timeOfDay: night.state.timeOfDay };
  if (opt.shot && night.result.ok) {
    const bed = [...bedsOf(night.state).values()].find((b) => b.agent);
    if (bed) {
      const [x, y, z] = bed.head;
      await dev.call('dev.camera', { x: x + 2.5, y: y + 2.2, z: z + 2.5, lookAt: { x: x + 0.5, y: y + 0.5, z: z + 0.5 } });
      const s = await dev.call('dev.screenshot', { name: 'routines_qa_night' });
      summary.night.shot = s.path;
      await dev.call('dev.release').catch(() => {});
    }
  }

  log('morning');
  await dev.call('dev.routines.time', { at: 'morning' });
  const morning = await poll(dev, 'morning', (s) => checkMorning(s, night.result.lying));
  summary.morning = { ok: morning.result.ok, reasons: morning.result.reasons, timeOfDay: morning.state.timeOfDay };

  if (!opt['no-restore'] && Number.isFinite(summary.startTimeOfDay)) {
    await dev.call('dev.routines.time', { ticks: summary.startTimeOfDay });
  }
} catch (e) {
  summary.error = e.message;
  console.log(JSON.stringify(summary, null, 2));
  process.exit(2);
} finally {
  dev?.close?.();
}
summary.ok = !!(summary.night?.ok && summary.morning?.ok);
console.log(JSON.stringify(summary, null, 2));
process.exit(summary.ok ? 0 : 1);
