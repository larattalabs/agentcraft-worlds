// Durable state. Everything the Foreman knows lives under `dir` (a profile directory inside
// AGENTCRAFT_HOME) and survives restarts:
//
//   <dir>/state.json            agents, tasks, decisions, repos, goals, feed, messages, sessions...
//   <dir>/logs/<agentId>.jsonl  append-only agent logs (the snapshot carries a tail); rotated at
//                               8 MB into <agentId>.1.jsonl (one old file kept)
//   <dir>/foreman.json          {pid, port, ...} of the Foreman running this profile (see runfile.ts)
//   <dir>/memory/**.md          markdown memory (see memory.ts)
//   <dir>/worktrees/<repo>/<wt> git worktrees for workers (see repos.ts)
//   <dir>/designs/<designId>/   scratch dirs of building design jobs (see designs.ts)
//
// state.json is written atomically (temp + fsync + rename), debounced, and flushed on exit.
import fs from 'node:fs';
import path from 'node:path';
import type { Agent, Decision, Design, FeedItem, Goal, LogEntry, Repo, Task } from './protocol.js';
import { ensureDir, readJson, writeJsonAtomic } from './util/fsx.js';

export interface BusMessage {
  id: string;
  ts: number;
  from: string; // agent id or "user"
  to: string; // agent id, "user" or "all"
  text: string;
  /** agent ids that have consumed this message */
  readBy: string[];
  /** the goal it is about */
  goalId?: string;
  /** a goal.message from the user to the goal's lead (runs as its own turn; not in the inbox) */
  goalMessage?: boolean;
}

export interface SessionRecord {
  sessionId?: string;
  model?: string;
  turns: number;
  /** spend of every session this key had (earlier sessions + the current one) */
  costUsd: number;
  updatedAt: number;
  /** when the current session id started (absent on records from before rotation: clock starts at next use) */
  startedAt?: number;
  /** agent turns (jobs) run in the current session */
  sessionTurns?: number;
  /** cumulative cost the current session reported (its total_cost_usd) */
  sessionCostUsd?: number;
  /** spend of the earlier sessions of this key (lead session rotation) */
  baseCostUsd?: number;
  /** why the last turn ended (for resume decisions) */
  lastResult?: string;
}

export interface WorktreeMeta {
  /** base sha recorded at merge time so merged diffs stay viewable */
  mergedBaseSha?: string;
  mergedSha?: string;
  createdAt: number;
  /** the directory of a finished worktree could not be removed yet (busy); retried later */
  pendingRemoval?: boolean;
  /** repoSettings copy/setup already ran in this worktree */
  prepared?: boolean;
  /** land "pr": the remote branch, the last sha AgentCraft pushed to it, the PR's URL */
  prBranch?: string;
  prPushedSha?: string;
  prUrl?: string;
  /** a PR fold-in by another worker: the earlier worktree's branch tip (the 3-way merge base of the added commit) */
  prevTip?: string;
}

/** A building lead's assignment (lead.assign), keyed by lead id in StateData.leads. */
export interface LeadRecord {
  /** the mod's building key "<worldId>/<buildingId>" */
  building: string;
  repos: string[];
  assignedAt: number;
}

export interface StateData {
  version: 1;
  createdAt: number;
  agents: Agent[];
  tasks: Task[];
  decisions: Decision[];
  repos: Repo[];
  goals: Goal[];
  /** building design jobs (design.request), oldest first */
  designs: Design[];
  feed: FeedItem[];
  messages: BusMessage[];
  counters: Record<string, number>;
  sessions: Record<string, SessionRecord>;
  worktreeMeta: Record<string, WorktreeMeta>; // key: `${repoId}/${worktreeId}`
  permissionRules: Record<string, string[]>; // agentId -> rule keys always allowed
  /** lead id -> its building (every lead but marlow that leads one); see leads.ts */
  leads: Record<string, LeadRecord>;
  /** world id -> when it last talked to the Foreman about leads (lead.sync / assign / release) */
  leadWorlds?: Record<string, number>;
  /** when the first (dry-run) cleanup sweep ran: later sweeps remove things (Foreman.cleanup) */
  cleanupArmedAt?: number;
  /** the Foreman's own "drop the protected edits?" decisions (never an agent's question): decision id -> target */
  protectedDrops?: Record<string, { repoId: string; worktree: string; mergeDecisionId: string; taskId?: string }>;
  /** opaque backend-owned state (e.g. sim progress) */
  backend: Record<string, unknown>;
}

export const FEED_LIMIT = 300;
export const MESSAGE_LIMIT = 500;
export const LOG_TAIL = 200;
/** an agent log rotates at this size; one previous file (<agent>.1.jsonl) is kept */
export const LOG_MAX_BYTES = 8 * 1024 * 1024;
/** how much of the end of a log file is read to rebuild its tail */
const TAIL_READ_BYTES = 512 * 1024;

/** Complete lines from the last `maxBytes` of a file (the first, partial line is dropped). */
function readTailLines(file: string, maxBytes: number): string[] {
  let fd: number | undefined;
  try {
    const size = fs.statSync(file).size;
    const len = Math.min(size, maxBytes);
    if (!len) return [];
    fd = fs.openSync(file, 'r');
    const buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, size - len);
    let text = buf.toString('utf8');
    if (len < size) text = text.slice(text.indexOf('\n') + 1);
    return text.split('\n').filter(Boolean);
  } catch {
    return [];
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
}

function emptyState(now: number): StateData {
  return {
    version: 1,
    createdAt: now,
    agents: [],
    tasks: [],
    decisions: [],
    repos: [],
    goals: [],
    designs: [],
    feed: [],
    messages: [],
    counters: {},
    sessions: {},
    worktreeMeta: {},
    permissionRules: {},
    leads: {},
    backend: {},
  };
}

export class Store {
  readonly dir: string;
  readonly file: string;
  data: StateData;
  private timer: NodeJS.Timeout | undefined;
  private dirty = false;
  private logTails = new Map<string, LogEntry[]>();
  private logSizes = new Map<string, number>();
  private readonly debounceMs: number;
  private readonly logMaxBytes: number;

  constructor(dir: string, opts: { debounceMs?: number; now?: number; logMaxBytes?: number } = {}) {
    this.dir = path.resolve(dir);
    this.file = path.join(this.dir, 'state.json');
    this.debounceMs = opts.debounceMs ?? 100;
    this.logMaxBytes = opts.logMaxBytes ?? LOG_MAX_BYTES;
    ensureDir(this.dir);
    ensureDir(path.join(this.dir, 'logs'));
    const loaded = this.loadFile();
    this.data = loaded ?? emptyState(opts.now ?? Date.now());
    if (!loaded) this.flush();
  }

  private loadFile(): StateData | undefined {
    try {
      const d = readJson<StateData>(this.file);
      if (!d) return undefined;
      if (d.version !== 1) throw new Error(`unsupported state version ${String((d as { version?: unknown }).version)}`);
      // tolerate older files missing newer keys
      return { ...emptyState(d.createdAt ?? Date.now()), ...d };
    } catch (e) {
      // Keep the corrupt file for inspection and start fresh rather than crash-looping.
      const bad = `${this.file}.corrupt-${Date.now()}`;
      try {
        fs.renameSync(this.file, bad);
      } catch {
        /* ignore */
      }
      console.error(`[store] could not read ${this.file} (${(e as Error).message}); moved to ${bad}`);
      return undefined;
    }
  }

  /** Allocate the next id for a counter, e.g. nextId('t') -> "t1", "t2"... */
  nextId(prefix: string): string {
    const n = (this.data.counters[prefix] ?? 0) + 1;
    this.data.counters[prefix] = n;
    this.markDirty();
    return `${prefix}${n}`;
  }

  markDirty(): void {
    this.dirty = true;
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      this.flush();
    }, this.debounceMs);
    this.timer.unref?.();
  }

  flush(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    writeJsonAtomic(this.file, this.data);
    this.dirty = false;
  }

  get isDirty(): boolean {
    return this.dirty;
  }

  pushFeed(item: FeedItem): void {
    this.data.feed.push(item);
    if (this.data.feed.length > FEED_LIMIT) this.data.feed.splice(0, this.data.feed.length - FEED_LIMIT);
    this.markDirty();
  }

  pushMessage(m: BusMessage): void {
    this.data.messages.push(m);
    if (this.data.messages.length > MESSAGE_LIMIT) this.data.messages.splice(0, this.data.messages.length - MESSAGE_LIMIT);
    this.markDirty();
  }

  // ---- logs -------------------------------------------------------------------------------

  private logFile(agentId: string): string {
    return path.join(this.dir, 'logs', `${agentId.replace(/[^a-zA-Z0-9_-]/g, '_')}.jsonl`);
  }

  private rotatedLogFile(agentId: string): string {
    return this.logFile(agentId).replace(/\.jsonl$/, '.1.jsonl');
  }

  appendLog(agentId: string, entries: LogEntry[]): void {
    if (!entries.length) return;
    // load the cached tail BEFORE appending, or the lazy load would read these entries twice
    const tail = this.logTail(agentId);
    const f = this.logFile(agentId);
    const data = entries.map((e) => JSON.stringify(e)).join('\n') + '\n';
    const bytes = Buffer.byteLength(data);
    let size = this.logSizes.get(agentId) ?? (fs.existsSync(f) ? fs.statSync(f).size : 0);
    if (size > 0 && size + bytes > this.logMaxBytes) {
      // rotate: keep one previous file, so the logs never grow without bound
      try {
        fs.rmSync(this.rotatedLogFile(agentId), { force: true });
        fs.renameSync(f, this.rotatedLogFile(agentId));
        size = 0;
      } catch {
        /* held open elsewhere: try again on a later append */
      }
    }
    fs.appendFileSync(f, data);
    this.logSizes.set(agentId, size + bytes);
    tail.push(...entries);
    if (tail.length > LOG_TAIL) tail.splice(0, tail.length - LOG_TAIL);
  }

  /** Recent log entries for an agent (cached; loaded lazily from the END of the log files). */
  logTail(agentId: string): LogEntry[] {
    let tail = this.logTails.get(agentId);
    if (tail) return tail;
    tail = [];
    let lines = readTailLines(this.logFile(agentId), TAIL_READ_BYTES);
    // just rotated: the rest of the tail is at the end of the previous file
    if (lines.length < LOG_TAIL) lines = [...readTailLines(this.rotatedLogFile(agentId), TAIL_READ_BYTES), ...lines];
    for (const l of lines.slice(-LOG_TAIL)) {
      try {
        tail.push(JSON.parse(l) as LogEntry);
      } catch {
        /* skip torn line */
      }
    }
    this.logTails.set(agentId, tail);
    return tail;
  }

  /**
   * A page of an agent's stored log, newest last (agent.logs.request): up to `limit` entries older than
   * `before` (all when absent), read backwards from the end of the current file and then the rotated one
   * (`<agent>.1.jsonl`), so paging walks across a rotation. Entries sharing the timestamp of the page's
   * oldest entry are kept together (a page may exceed `limit` by that group, never splits it, so paging by
   * `before = page[0].ts` loses nothing). `more`: older entries exist.
   */
  readLog(agentId: string, before: number | undefined, limit: number): { entries: LogEntry[]; more: boolean } {
    const out: LogEntry[] = []; // newest first while collecting
    let more = false;
    let cut: number | undefined; // ts of the last entry taken once the limit was reached
    outer: for (const file of [this.logFile(agentId), this.rotatedLogFile(agentId)]) {
      for (const line of readLinesBackward(file)) {
        // cheap skip: the ts from the line's prefix (no decode, no JSON.parse) for the lines newer than `before`
        const quick = tsPrefix(line);
        if (quick !== undefined && before !== undefined && quick >= before) continue;
        let e: LogEntry;
        try {
          e = JSON.parse(line.toString('utf8')) as LogEntry;
        } catch {
          continue; // torn line
        }
        if (typeof e?.ts !== 'number') continue;
        if (before !== undefined && e.ts >= before) continue;
        if (cut !== undefined) {
          if (e.ts === cut && out.length < limit * 2) {
            out.push(e);
            continue;
          }
          more = true;
          break outer;
        }
        out.push(e);
        if (out.length >= limit) cut = e.ts;
      }
    }
    return { entries: out.reverse(), more };
  }

  close(): void {
    this.flush();
  }
}

const TS_PREFIX = Buffer.from('{"ts":');

/**
 * The `ts` of a stored log line from its first bytes (`{"ts":<digits>`, the order JSON.stringify writes a LogEntry
 * in), without decoding or parsing the line; undefined when the line does not start that way (the caller parses it).
 */
export function tsPrefix(line: Buffer): number | undefined {
  if (line.length <= TS_PREFIX.length || line.compare(TS_PREFIX, 0, TS_PREFIX.length, 0, TS_PREFIX.length) !== 0) return undefined;
  let i = TS_PREFIX.length;
  let n = 0;
  const start = i;
  while (i < line.length && i - start < 16) {
    const c = line[i]!;
    if (c < 0x30 || c > 0x39) break;
    n = n * 10 + (c - 0x30);
    i++;
  }
  // digits, then the next key or the end of the object (a float or exponent is left to JSON.parse)
  if (i === start || i >= line.length || (line[i] !== 0x2c && line[i] !== 0x7d)) return undefined;
  return n;
}

/** The complete lines of a file (raw bytes), last first, read in chunks from the end (a missing file yields nothing). */
function* readLinesBackward(file: string, chunk = 64 * 1024): Generator<Buffer> {
  let fd: number | undefined;
  try {
    fd = fs.openSync(file, 'r');
  } catch {
    return;
  }
  try {
    let pos = fs.fstatSync(fd).size;
    let rest = Buffer.alloc(0); // bytes after the last newline seen so far (the start of a line)
    while (pos > 0) {
      const len = Math.min(chunk, pos);
      pos -= len;
      const buf = Buffer.alloc(len);
      fs.readSync(fd, buf, 0, len, pos);
      const data = rest.length ? Buffer.concat([buf, rest]) : buf;
      let end = data.length;
      // native newline search (no per-byte loop in JS)
      for (let i = data.lastIndexOf(0x0a, end - 1); i >= 0; i = i > 0 ? data.lastIndexOf(0x0a, i - 1) : -1) {
        const line = data.subarray(i + 1, end);
        if (!isBlank(line)) yield line;
        end = i;
      }
      rest = Buffer.from(data.subarray(0, end)); // a copy: `data` is not kept
    }
    if (!isBlank(rest)) yield rest;
  } finally {
    fs.closeSync(fd);
  }
}

function isBlank(b: Buffer): boolean {
  for (let i = 0; i < b.length; i++) {
    const c = b[i];
    if (c !== 0x20 && c !== 0x0d && c !== 0x09) return false; // stops at the first byte of a real line
  }
  return true;
}
