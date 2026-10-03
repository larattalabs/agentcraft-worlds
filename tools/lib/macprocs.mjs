// Pure helpers for tools/mac.mjs: ps-table parsing, descendant walks, run-file selection.

/** Parse `ps -ax -o pid,ppid,pgid,command` output into [{pid, ppid, pgid, command}]. */
export function parsePs(text) {
  const rows = [];
  for (const line of String(text).split('\n')) {
    const m = /^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.*)$/.exec(line);
    if (m) rows.push({ pid: +m[1], ppid: +m[2], pgid: +m[3], command: m[4].trim() });
  }
  return rows;
}

/** All transitive children of `pid` (excluding pid itself). */
export function descendants(table, pid) {
  const kids = new Map();
  for (const r of table) {
    if (!kids.has(r.ppid)) kids.set(r.ppid, []);
    kids.get(r.ppid).push(r);
  }
  const out = [];
  const seen = new Set([pid]);
  const queue = [pid];
  while (queue.length) {
    for (const child of kids.get(queue.shift()) ?? []) {
      if (seen.has(child.pid)) continue;
      seen.add(child.pid);
      out.push(child);
      queue.push(child.pid);
    }
  }
  return out;
}

/** Is this command line really a Foreman for `profile`? (guards against pid reuse) */
export function isForemanCommand(command, profile) {
  if (!command || !command.includes('src/main.ts')) return false;
  const escaped = profile.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`--profile[ =]${escaped}(\\s|$)`).test(command);
}

/**
 * What to signal for a set of verified root pids: every pid in their trees, plus each root's
 * process group when the root leads its own group (it was spawned detached) and that group is
 * not our own. Returns { pids: number[], groups: number[] }.
 */
export function planKill(table, rootPids, selfPgid) {
  const byPid = new Map(table.map((r) => [r.pid, r]));
  const pids = new Set();
  const groups = new Set();
  for (const root of rootPids) {
    const row = byPid.get(root);
    if (!row) continue;
    pids.add(root);
    for (const d of descendants(table, root)) pids.add(d.pid);
    if (row.pgid === root && root !== selfPgid && root > 1) groups.add(root);
  }
  return { pids: [...pids], groups: [...groups] };
}

/**
 * Choose which profile `stop` acts on from launcher run files.
 * files: [{ name: 'mac-game-sim.json', mtimeMs }]. An explicit profile wins; otherwise the
 * profile of the newest run file. `others` = other profiles that also have run files.
 */
export function selectProfile(files, explicit) {
  const parsed = files
    .map((f) => ({ mtimeMs: f.mtimeMs, m: /^mac-(game|foreman)-([\w-]+)\.json$/.exec(f.name) }))
    .filter((f) => f.m)
    .sort((a, b) => b.mtimeMs - a.mtimeMs);
  const profiles = [...new Set(parsed.map((f) => f.m[2]))];
  if (explicit) return { profile: explicit, others: profiles.filter((p) => p !== explicit) };
  return { profile: profiles[0] ?? null, others: profiles.slice(1) };
}

/** Compare a recorded Foreman launch with the current checkout. Returns a list of problems. */
export function staleReasons(recorded, current) {
  const reasons = [];
  if (!recorded?.root && !recorded?.commit) return ['it was started by an older launcher that did not record its checkout/commit'];
  if (recorded.root && recorded.root !== current.root) reasons.push(`started from a different checkout (${recorded.root})`);
  if (recorded.commit && current.commit && recorded.commit !== current.commit) {
    reasons.push(`started at commit ${recorded.commit.slice(0, 9)}, checkout is now ${current.commit.slice(0, 9)}`);
  }
  return reasons;
}
