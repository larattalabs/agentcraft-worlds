// The client token: which WebSocket clients may change things.
//
// Any local process can open ws://127.0.0.1:<port>, including a Bash command an agent runs. So the
// Foreman writes a random token to <dataDir>/client.token (mode 0600, a new one on every start) and
// lists that path in its run file (RunInfo.tokenFile). A client that sends it in `hello` may do
// everything; any other connection is read-only (snapshot and events, plus hello / diff.request /
// goal.digest / agent.logs.request). The mod and the tools read the run file to find it; the agent policy denies agents
// the file (policy.ts, foremanPrivateVerdict). `--no-client-token` (dev only) turns this off.
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { writeFileAtomic } from './util/fsx.js';

export const CLIENT_TOKEN_FILE = 'client.token';

/** Client messages a connection without the token may send. */
export const READ_ONLY_TYPES: ReadonlySet<string> = new Set(['hello', 'diff.request', 'goal.digest', 'agent.logs.request']);

export const READ_ONLY_ERROR = 'read-only connection: no client token';

export function clientTokenPath(dataDir: string): string {
  return path.join(dataDir, CLIENT_TOKEN_FILE);
}

/** A fresh token, written owner-only (0600) to <dataDir>/client.token (temp file + rename). */
export function createClientToken(dataDir: string): { token: string; file: string } {
  const token = randomBytes(32).toString('hex');
  const file = clientTokenPath(dataDir);
  // a random temp name created exclusively with 0600, then renamed over the target (never follows a link)
  writeFileAtomic(file, `${token}\n`, { mode: 0o600 });
  return { token, file };
}

/** Remove the token file, unless it holds another token by now (a restarted Foreman's). */
export function removeClientToken(file: string, token: string): void {
  try {
    if (fs.readFileSync(file, 'utf8').trim() === token) fs.rmSync(file, { force: true });
  } catch {
    /* already gone */
  }
}

const digest = (s: string) => createHash('sha256').update(s, 'utf8').digest();

/** Constant-time token comparison (both sides hashed first, so lengths never leak either). */
export function tokenMatches(expected: string, given: unknown): boolean {
  if (typeof given !== 'string' || !given) return false;
  return timingSafeEqual(digest(expected), digest(given.trim()));
}
