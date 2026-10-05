// The user's earlier Claude sessions (Claude Code CLI and Claude Desktop's Code tab), so agents can
// pick up work that was started there (config.json claude.context.sessionHistory, off by default).
//
// Sessions are JSONL transcripts under ~/.claude/projects/<folder>/<session id>.jsonl (or
// $CLAUDE_CONFIG_DIR/projects). Each is summarised once per (mtime, size) and cached in memory and
// in <profile>/history-index.json: titles, the first and last prompts, PR links, the branches its git
// commands name, the files it edited. Reading one returns a condensed transcript (prompts, the
// assistant's text, commands, edits; tool output left out), weighted towards the end, where "where
// we left off" is.
//
// Scope: only sessions whose working directory is inside one of `within` (the registered repos and
// their workspace folders), or a Claude Desktop scratchpad worktree of one of them. AgentCraft's own
// agent sessions are never included.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { readJson, writeJsonAtomic } from './util/fsx.js';

export interface SessionSummary {
  id: string;
  file: string;
  cwd?: string;
  /** epoch ms of the first / last timestamped record */
  started?: number;
  ended?: number;
  /** the user's title, else Claude's, else the first prompt */
  title: string;
  firstPrompt?: string;
  lastPrompt?: string;
  prs: string[];
  /** branches the session's git commands named (checkout, switch, worktree add, push, merge, branch) */
  branches: string[];
  /** files it edited (most recent last, at most 30) */
  files: string[];
  prompts: number;
}

interface CacheEntry {
  mtimeMs: number;
  size: number;
  summary: SessionSummary;
}

const BRANCH_RES = [
  /\bgit\s+(?:checkout|switch)\s+(?:-[bBc]\s+)?([\w./-]+)/g,
  /\bgit\s+worktree\s+add\b[^\n;&|]*?\s-b\s+([\w./-]+)/g,
  /\bgit\s+push\s+(?:-u\s+|--set-upstream\s+)?[\w.-]+\s+(?:HEAD:)?([\w./-]+)/g,
  /\bgit\s+merge\s+(?:--no-ff\s+|--ff-only\s+)?([\w./-]+)/g,
  /\bgit\s+branch\s+(?:-[a-zA-Z]+\s+)?([\w./-]+)/g,
];
const NOT_BRANCHES = new Set(['HEAD', '.', '..', 'origin', '--', '-', 'main', 'master', 'FETCH_HEAD', 'ORIG_HEAD']);

/** A captured word that can be a branch (not a flag, a redirect like 2>&1, or a ref keyword). */
function isBranchName(b: string): boolean {
  return !NOT_BRANCHES.has(b) && !b.startsWith('-') && /[A-Za-z]/.test(b) && !b.endsWith('.');
}

function promptText(content: unknown): string | undefined {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    const texts = content.filter((b) => b && typeof b === 'object' && (b as { type?: string }).type === 'text').map((b) => (b as { text: string }).text);
    return texts.length ? texts.join('\n') : undefined;
  }
  return undefined;
}

/** A user prompt worth showing: not a tool result, not a slash-command wrapper or system note. */
function realPrompt(rec: Record<string, unknown>): string | undefined {
  if (rec.type !== 'user' || rec.isMeta) return undefined;
  const t = promptText((rec.message as { content?: unknown } | undefined)?.content)?.trim();
  if (!t || /^<(command-|local-command|system-reminder|task-notification)/.test(t)) return undefined;
  return t;
}

const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n).trimEnd()}…` : s);
const oneLine = (s: string) => s.replace(/\s+/g, ' ').trim();

/** Summarise one transcript (streamed: transcripts can be tens of MB). */
export async function summarizeSession(file: string): Promise<SessionSummary> {
  const s: SessionSummary = { id: path.basename(file, '.jsonl'), file, title: '', prs: [], branches: [], files: [], prompts: 0 };
  let customTitle: string | undefined;
  let aiTitle: string | undefined;
  const branches = new Set<string>();
  const files: string[] = [];
  const rl = readline.createInterface({ input: fs.createReadStream(file, 'utf8'), crlfDelay: Infinity });
  for await (const line of rl) {
    if (!line.trim()) continue;
    let rec: Record<string, unknown>;
    try {
      rec = JSON.parse(line) as Record<string, unknown>;
    } catch {
      continue;
    }
    if (typeof rec.timestamp === 'string') {
      const t = Date.parse(rec.timestamp);
      if (Number.isFinite(t)) {
        s.started ??= t;
        s.ended = t;
      }
    }
    if (!s.cwd && typeof rec.cwd === 'string') s.cwd = rec.cwd;
    if (typeof rec.gitBranch === 'string' && rec.gitBranch && isBranchName(rec.gitBranch)) branches.add(rec.gitBranch);
    switch (rec.type) {
      case 'custom-title':
        if (typeof rec.customTitle === 'string') customTitle = rec.customTitle;
        break;
      case 'ai-title':
        if (typeof rec.aiTitle === 'string') aiTitle = rec.aiTitle;
        break;
      case 'pr-link':
        if (typeof rec.prUrl === 'string' && !s.prs.includes(rec.prUrl)) s.prs.push(rec.prUrl);
        break;
      case 'user': {
        const p = realPrompt(rec);
        if (p && !p.startsWith('This session is being continued from a previous conversation')) {
          s.prompts++;
          s.firstPrompt ??= clip(oneLine(p), 300);
          s.lastPrompt = clip(oneLine(p), 300);
        }
        break;
      }
      case 'assistant': {
        const content = (rec.message as { content?: unknown } | undefined)?.content;
        if (!Array.isArray(content)) break;
        for (const b of content as Array<{ type?: string; name?: string; input?: Record<string, unknown> }>) {
          if (b.type !== 'tool_use' || !b.input) continue;
          if (b.name === 'Bash' && typeof b.input.command === 'string') {
            for (const re of BRANCH_RES) for (const m of b.input.command.matchAll(re)) if (isBranchName(m[1]!)) branches.add(m[1]!);
          } else if ((b.name === 'Edit' || b.name === 'Write' || b.name === 'MultiEdit') && typeof b.input.file_path === 'string') {
            const i = files.indexOf(b.input.file_path);
            if (i >= 0) files.splice(i, 1);
            files.push(b.input.file_path);
          }
        }
        break;
      }
    }
  }
  s.title = customTitle ?? aiTitle ?? s.firstPrompt ?? '(untitled)';
  s.branches = [...branches];
  s.files = files.slice(-30);
  return s;
}

/** Claude's encoding of a directory in project folder names and Desktop scratchpad paths. */
export function encodeDir(dir: string): string {
  return path.resolve(dir).replace(/[^A-Za-z0-9]/g, '-');
}

export interface FindOptions {
  query?: string;
  branch?: string;
  /** only sessions in this directory (a repo or workspace folder) or its Desktop scratchpads */
  dir?: string;
  days?: number;
  limit?: number;
}

export class SessionHistory {
  private cache = new Map<string, CacheEntry>();
  private dirty = false;

  constructor(
    private opts: {
      /** where the project folders are (default ~/.claude/projects or $CLAUDE_CONFIG_DIR/projects) */
      root?: string;
      /** directories whose sessions are in scope */
      within: () => string[];
      /** directories whose sessions are never in scope (AgentCraft's own) */
      exclude: string[];
      /** cache file */
      indexFile?: string;
      /** default look-back in days */
      days?: number;
      /** cuts known secrets out of the prompts and titles the cache keeps (redact.ts) */
      redact?: (text: string) => string;
    },
  ) {
    const saved = opts.indexFile ? readJson<Record<string, CacheEntry>>(opts.indexFile) : undefined;
    if (saved) for (const [k, v] of Object.entries(saved)) this.cache.set(k, v);
  }

  get root(): string {
    return this.opts.root ?? path.join(process.env.CLAUDE_CONFIG_DIR ?? path.join(os.homedir(), '.claude'), 'projects');
  }

  /** Is a session with this cwd in scope (and, with `only`, also of that one directory)? `only` never widens the scope. */
  inScope(cwd: string | undefined, only?: string): boolean {
    if (!cwd) return false;
    const c = path.resolve(cwd);
    const inside = (d: string) => c === path.resolve(d) || c.startsWith(path.resolve(d) + path.sep);
    if (this.opts.exclude.some(inside)) return false;
    // a Claude Desktop scratchpad worktree: /private/tmp/claude-<uid>/<encoded dir>/<session>/scratchpad/...
    const of = (d: string) => inside(d) || c.includes(`${path.sep}${encodeDir(d)}${path.sep}`);
    return this.opts.within().some(of) && (!only || of(only));
  }

  private async summary(file: string, st: fs.Stats): Promise<SessionSummary> {
    const hit = this.cache.get(file);
    if (hit && hit.mtimeMs === st.mtimeMs && hit.size === st.size) return hit.summary;
    const summary = await summarizeSession(file);
    this.cache.set(file, { mtimeMs: st.mtimeMs, size: st.size, summary });
    this.dirty = true;
    return summary;
  }

  /** Sessions in scope, best match first (newest first without a query or branch). */
  async find(o: FindOptions = {}): Promise<SessionSummary[]> {
    const since = Date.now() - (o.days ?? this.opts.days ?? 60) * 86_400_000;
    const out: SessionSummary[] = [];
    let folders: string[] = [];
    try {
      folders = fs.readdirSync(this.root);
    } catch {
      return [];
    }
    for (const folder of folders) {
      const dir = path.join(this.root, folder);
      let names: string[];
      try {
        names = fs.readdirSync(dir).filter((f) => f.endsWith('.jsonl'));
      } catch {
        continue;
      }
      for (const name of names) {
        const file = path.join(dir, name);
        let st: fs.Stats;
        try {
          st = fs.statSync(file);
        } catch {
          continue;
        }
        if (st.mtimeMs < since || !st.isFile()) continue;
        const s = await this.summary(file, st);
        if (this.inScope(s.cwd, o.dir) && s.prompts > 0) out.push(s);
      }
    }
    this.save();
    const words = (o.query ?? '').toLowerCase().split(/[^a-z0-9./-]+/).filter((w) => w.length >= 3);
    const branch = o.branch?.toLowerCase();
    const score = (s: SessionSummary) => {
      let n = 0;
      if (branch && s.branches.some((b) => b.toLowerCase() === branch)) n += 10;
      const hay = [s.title, s.firstPrompt, s.lastPrompt, ...s.branches, ...s.prs, ...s.files].join(' ').toLowerCase();
      for (const w of words) if (hay.includes(w)) n += 1;
      return n;
    };
    const ranked = out.map((s) => ({ s, n: score(s) })).filter((x) => (!branch && !words.length) || x.n > 0);
    ranked.sort((a, b) => b.n - a.n || (b.s.ended ?? 0) - (a.s.ended ?? 0));
    return ranked.slice(0, o.limit ?? 10).map((x) => x.s);
  }

  /** The summary of one session in scope (by id or id prefix). */
  async get(id: string): Promise<SessionSummary | undefined> {
    const all = await this.find({ days: 3650, limit: 100_000 });
    return all.find((s) => s.id === id) ?? all.find((s) => s.id.startsWith(id));
  }

  /** A condensed transcript: prompts, the assistant's text, commands and edits; tool output left out. */
  async read(id: string, maxChars = 20_000): Promise<string | undefined> {
    const s = await this.get(id);
    if (!s) return undefined;
    const entries: string[] = [];
    const rl = readline.createInterface({ input: fs.createReadStream(s.file, 'utf8'), crlfDelay: Infinity });
    for await (const line of rl) {
      let rec: Record<string, unknown>;
      try {
        rec = JSON.parse(line) as Record<string, unknown>;
      } catch {
        continue;
      }
      if (rec.type === 'user') {
        const p = realPrompt(rec);
        if (!p) continue;
        if (p.startsWith('This session is being continued from a previous conversation')) entries.push(`[Summary of the earlier part of this session]\n${clip(p, 4000)}`);
        else entries.push(`${'#'.repeat(2)} User${typeof rec.timestamp === 'string' ? ` (${rec.timestamp.slice(0, 16).replace('T', ' ')})` : ''}\n${clip(p, 2000)}`);
      } else if (rec.type === 'assistant') {
        const content = (rec.message as { content?: unknown } | undefined)?.content;
        if (!Array.isArray(content) || rec.parent_tool_use_id) continue;
        for (const b of content as Array<{ type?: string; text?: string; name?: string; input?: Record<string, unknown> }>) {
          if (b.type === 'text' && b.text?.trim()) entries.push(`Claude: ${clip(b.text.trim(), 1500)}`);
          else if (b.type === 'tool_use' && b.input) {
            if (b.name === 'Bash' && typeof b.input.command === 'string') entries.push(`  $ ${clip(oneLine(b.input.command), 300)}`);
            else if (typeof b.input.file_path === 'string' && /^(Edit|Write|MultiEdit)$/.test(b.name ?? '')) entries.push(`  edited ${b.input.file_path}`);
          }
        }
      }
    }
    const header = [
      `# ${s.title}`,
      `Session ${s.id}${s.started ? `, ${new Date(s.started).toISOString().slice(0, 16).replace('T', ' ')} to ${new Date(s.ended ?? s.started).toISOString().slice(0, 16).replace('T', ' ')} UTC` : ''}, in ${s.cwd ?? '?'}`,
      s.branches.length ? `Branches it worked with: ${s.branches.join(', ')}` : '',
      s.prs.length ? `Pull requests: ${s.prs.join(', ')}` : '',
    ]
      .filter(Boolean)
      .join('\n');
    // keep the start (what was asked) and as much of the end (where it stopped) as fits
    let body = entries.join('\n\n');
    const room = Math.max(1000, maxChars - header.length - 100);
    if (body.length > room) {
      const head = entries.join('\n\n').slice(0, Math.floor(room * 0.15));
      const tail = body.slice(body.length - Math.floor(room * 0.85));
      body = `${head}\n\n[... ${body.length - head.length - tail.length} characters of the middle left out ...]\n\n${tail}`;
    }
    return `${header}\n\n${body}`;
  }

  private save(): void {
    if (!this.dirty || !this.opts.indexFile) return;
    this.dirty = false;
    try {
      // prompts and titles can quote a secret: the index file keeps them redacted
      const r = this.opts.redact;
      const red = (v: string | undefined) => (r && v !== undefined ? r(v) : v);
      const out = Object.fromEntries(
        [...this.cache].map(([k, e]) => [k, { ...e, summary: { ...e.summary, title: red(e.summary.title)!, ...(e.summary.firstPrompt !== undefined ? { firstPrompt: red(e.summary.firstPrompt) } : {}), ...(e.summary.lastPrompt !== undefined ? { lastPrompt: red(e.summary.lastPrompt) } : {}) } }]),
      );
      writeJsonAtomic(this.opts.indexFile, out);
    } catch {
      /* a cache only */
    }
  }
}

/** One line per session, for tool results and prompts. */
export function sessionLine(s: SessionSummary): string {
  const when = s.ended ? new Date(s.ended).toISOString().slice(0, 10) : '?';
  return `- ${s.id} (${when}, ${s.prompts} prompts) "${clip(oneLine(s.title), 100)}"${s.branches.length ? ` branches: ${s.branches.slice(-6).join(', ')}` : ''}${s.prs.length ? ` PRs: ${s.prs.slice(-3).join(', ')}` : ''}${s.lastPrompt ? `\n    last prompt: "${clip(s.lastPrompt, 160)}"` : ''}`;
}
