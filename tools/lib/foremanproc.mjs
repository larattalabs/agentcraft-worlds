// Finding a running Foreman from a checkout's launcher run file (artifacts/run/mac-foreman-<profile>.json)
// and the Foreman's own run file (<home>/<profile>/foreman.json). Shared by tools/unix.mjs and
// tools/foreman-daemon.mjs so both agree on what "running" means.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { parsePs, isForemanCommand } from './macprocs.mjs';

export const readJson = (file) => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };
export const saveJson = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
};

/** `ps` start time of `pid` (guards against pid reuse), or null. */
export function processStamp(pid) {
  if (!Number.isInteger(pid) || pid < 1) return null;
  const result = spawnSync('ps', ['-p', String(pid), '-o', 'lstart='], { encoding: 'utf8' });
  return result.status === 0 ? result.stdout.trim() || null : null;
}

/** Is the process recorded in `info` ({pid, stamp}) still the same process? */
export function owned(info) { return Boolean(info?.pid && info?.stamp && processStamp(info.pid) === info.stamp); }

export function portOpen(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port });
    socket.setTimeout(500);
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('timeout', () => { socket.destroy(); resolve(false); });
    socket.once('error', () => resolve(false));
  });
}

export function psTable() {
  const result = spawnSync('ps', ['-ax', '-o', 'pid,ppid,pgid,command'], { encoding: 'utf8', maxBuffer: 64 << 20 });
  return result.status === 0 ? parsePs(result.stdout) : [];
}

export function gitHead(root) {
  const result = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' });
  return result.status === 0 ? result.stdout.trim() : null;
}

/**
 * The Foreman running `profile`, as recorded in the launcher run file `runFile`.
 * 1. the launcher's pid is still the process it started and its port answers; else
 * 2. the Foreman's own run file names a live `src/main.ts --profile <profile>` process whose port
 *    answers (it was restarted from the hub: foreman.restart, so the launcher's pid is gone). The
 *    launcher run file is then updated to name that process.
 * Returns { fm, running } where fm is the (possibly updated) launcher record or null.
 */
export async function findForeman({ runFile, home, profile }) {
  let fm = readJson(runFile);
  if (owned(fm) && await portOpen(fm.port)) return { fm, running: true };
  const ownHome = fm?.home ?? home;
  const own = readJson(path.join(ownHome, profile, 'foreman.json'));
  const command = own?.pid ? psTable().find((r) => r.pid === own.pid)?.command : undefined;
  if (own?.pid && isForemanCommand(command, profile) && await portOpen(own.port)) {
    fm = { ...(fm ?? {}), pid: own.pid, stamp: processStamp(own.pid), port: own.port, backend: own.backend ?? fm?.backend, home: ownHome };
    saveJson(runFile, fm);
    return { fm, running: true };
  }
  return { fm, running: false };
}
