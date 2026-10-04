// Pure decisions for tools/foreman-daemon.mjs (unit-tested in test/daemon.test.mjs).
import os from 'node:os';
import path from 'node:path';

/**
 * What `start` does.
 *   running + not stale        -> 'noop'
 *   running + stale            -> 'restart' (other checkout or older commit)
 *   not running, port free     -> 'start'
 *   not running, port answers  -> 'blocked' (something else holds the port: never touch it)
 */
export function decideStart({ running, stale = [], portBusy, forceRestart = false }) {
  if (running) return stale.length || forceRestart ? 'restart' : 'noop';
  return portBusy ? 'blocked' : 'start';
}

export function expandHome(p, home = os.homedir()) {
  if (typeof p !== 'string' || !p) return p;
  if (p === '~') return home;
  if (p.startsWith('~/')) return path.join(home, p.slice(2));
  return p;
}

const inside = (child, parent) => {
  const rel = path.relative(parent, child);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
};

/**
 * Is `root` (a checkout, already realpath'd) one the Foreman itself works on? True when it is, or
 * sits inside, a repository listed in config.json `repos`, or when its Git common dir (worktrees
 * share it) belongs to one. Agents merge into such a checkout and create worktrees under it, so the
 * daily Foreman must not run from there. `repos` are realpath'd by the caller (or plain paths).
 * Returns the matching repo or null.
 */
export function devCheckoutConflict({ root, commonDir, repos }) {
  for (const repo of repos ?? []) {
    if (!repo) continue;
    if (inside(root, repo)) return repo;
    if (commonDir && inside(commonDir, repo)) return repo;
  }
  return null;
}

/** Lock files older than this are from a start that died; a new start may take the lock. */
export const LOCK_STALE_MS = 3 * 60 * 1000;

export function lockIsStale(mtimeMs, now = Date.now()) {
  return !Number.isFinite(mtimeMs) || now - mtimeMs > LOCK_STALE_MS;
}
