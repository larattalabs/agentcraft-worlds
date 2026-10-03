// The client token: which WebSocket clients may change things.
//
// Any local process can open ws://127.0.0.1:<port>, including a Bash command an agent runs. So the
// Foreman writes a random token to <dataDir>/client.token (mode 0600, a new one on every start) and
// lists that path in its run file (RunInfo.tokenFile). A client that sends it in `hello` may do
// everything; any other connection is read-only (snapshot and events, plus hello / diff.request /
// goal.digest). The mod and the tools read the run file to find it; the agent policy denies agents
// the file (policy.ts, foremanPrivateVerdict). `--no-client-token` (dev only) turns this off.
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export const CLIENT_TOKEN_FILE = 'client.token';

/** Client messages a connection without the token may send. */
export const READ_ONLY_TYPES: ReadonlySet<string> = new Set(['hello', 'diff.request', 'goal.digest']);

export const READ_ONLY_ERROR = 'read-only connection: no client token';

export function clientTokenPath(dataDir: string): string {
  return path.join(dataDir, CLIENT_TOKEN_FILE);
}

let tmpCounter = 0;

/** A fresh token, written owner-only (0600) to <dataDir>/client.token (temp file + rename). */
export function createClientToken(dataDir: string): { token: string; file: string } {
  const token = randomBytes(32).toString('hex');
  const file = clientTokenPath(dataDir);
  fs.mkdirSync(dataDir, { recursive: true });
  const tmp = `${file}.${process.pid}.${++tmpCounter}.tmp`;
  const fd = fs.openSync(tmp, 'w', 0o600);
  try {
    fs.writeSync(fd, `${token}\n`);
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  try {
    fs.chmodSync(tmp, 0o600);
  } catch {
    /* not supported (Windows): the file is in the user's profile anyway */
  }
  fs.renameSync(tmp, file);
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
