// The central redactor (docs/WAVE3.md S1/S2): every secret value the Foreman knows - repository env
// values, MCP server env values, MCP arguments (all of them: they are write-only), MCP URL paths and
// queries, header values, the client token - is cut out of the text that leaves the Foreman or is
// kept by it: agent log entries, feed items, ack / error texts, notifications and console logs.
//
// A value is matched as it is and in the encodings it most often travels in: URL-encoded,
// JSON-escaped and base64 (standard and URL-safe, with and without padding). Values shorter than
// MIN_SECRET characters are not redacted (they would cut "true" or "1" out of every line).
//
// The set only grows while the Foreman runs: a value replaced or removed through config.set stays
// redacted (old log lines and stderr may still carry it).
import type { Config } from './config.js';

/** What a secret is replaced with. */
export const REDACTED = '[redacted]';
/** Shorter values are not redacted. */
export const MIN_SECRET = 6;
/** The shortest cut-off start of a secret removed from the end of truncated text ("…"). */
const MIN_PARTIAL = 3;

type Raw = Record<string, unknown>;
const isObject = (v: unknown): v is Raw => !!v && typeof v === 'object' && !Array.isArray(v);

/** The forms `v` is looked for in: as it is, URL-encoded, JSON-escaped, base64. */
export function secretVariants(v: string): string[] {
  const out = new Set<string>([v]);
  try {
    const u = encodeURIComponent(v);
    out.add(u);
    out.add(u.replace(/%20/g, '+'));
  } catch {
    /* a lone surrogate: not URL-encodable */
  }
  out.add(JSON.stringify(v).slice(1, -1));
  // JSON-escaped twice (a JSON document inside a JSON string, e.g. a tool result)
  out.add(JSON.stringify(JSON.stringify(v)).slice(3, -3));
  const b64 = Buffer.from(v, 'utf8').toString('base64');
  const url = b64.replace(/\+/g, '-').replace(/\//g, '_');
  for (const b of [b64, url]) {
    out.add(b);
    out.add(b.replace(/=+$/, ''));
  }
  return [...out].filter((x) => x.length >= MIN_SECRET);
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export class Redactor {
  private readonly values = new Set<string>();
  private variants: string[] = [];
  private re: RegExp | undefined;

  /** Add secret values (shorter than MIN_SECRET: ignored). */
  add(values: Iterable<string | undefined>): void {
    let changed = false;
    for (const v of values) {
      if (typeof v !== 'string' || v.length < MIN_SECRET || this.values.has(v)) continue;
      this.values.add(v);
      changed = true;
    }
    if (!changed) return;
    const all = new Set<string>();
    for (const v of this.values) for (const x of secretVariants(v)) all.add(x);
    // longest first: a secret that contains another is cut out whole
    this.variants = [...all].sort((a, b) => b.length - a.length);
    this.re = new RegExp(this.variants.map(escapeRe).join('|'), 'g');
  }

  get size(): number {
    return this.values.size;
  }

  /**
   * `text` without any known secret. Text cut off with "…" (util/text.ts truncate, headLines) also
   * loses a secret's start left at the cut.
   */
  redact(text: string): string {
    if (!this.re || !text) return text;
    let out = text.replace(this.re, REDACTED);
    const m = /…\s*$/.exec(out);
    if (m) {
      const body = out.slice(0, m.index);
      for (const v of this.variants) {
        let cut = 0;
        for (let n = Math.min(v.length - 1, body.length); n >= MIN_PARTIAL; n--) {
          if (body.endsWith(v.slice(0, n))) {
            cut = n;
            break;
          }
        }
        if (cut) {
          out = `${body.slice(0, body.length - cut)}${REDACTED}${out.slice(m.index)}`;
          break;
        }
      }
    }
    return out;
  }

  /** A copy of a JSON-like value with every string redacted (objects and arrays copied, never changed in place). */
  redactDeep<T>(v: T): T {
    if (!this.re) return v;
    if (typeof v === 'string') return this.redact(v) as T;
    if (Array.isArray(v)) return v.map((x) => this.redactDeep(x)) as T;
    if (isObject(v)) return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, this.redactDeep(x)])) as T;
    return v;
  }
}

/** The parts of a URL that may carry a credential: the whole URL, its path, its path segments, its query and query values. */
function urlSecrets(u: string): string[] {
  let p: URL;
  try {
    p = new URL(u);
  } catch {
    return [u];
  }
  // scheme://host[:port] is what config.get shows: nothing secret in it
  const out = (p.pathname && p.pathname !== '/') || p.search || p.hash || p.username ? [u, p.href] : [];
  if (p.pathname && p.pathname !== '/') {
    out.push(p.pathname, p.pathname + p.search);
    for (const seg of p.pathname.split('/')) {
      out.push(seg);
      try {
        out.push(decodeURIComponent(seg));
      } catch {
        /* not decodable */
      }
    }
  }
  if (p.search) {
    out.push(p.search, p.search.slice(1));
    for (const [, val] of p.searchParams) out.push(val);
  }
  if (p.username) out.push(p.username);
  if (p.password) out.push(p.password, decodeURIComponent(p.password));
  return out;
}

/** A value and the parts of it that are a credential on their own ("Bearer x" -> x; "--token=x" -> x). */
function withParts(v: string): string[] {
  const out = [v];
  const eq = /^[^=\s]+=([\s\S]+)$/.exec(v);
  if (eq) out.push(eq[1]!);
  const words = v.trim().split(/\s+/);
  if (words.length > 1) out.push(words.slice(1).join(' '), ...words.slice(1));
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(v)) out.push(...urlSecrets(v));
  return out;
}

/** Every secret value a configuration holds (repository env, MCP servers' env, args, URL parts, headers). */
export function configSecrets(cfg: Pick<Config, 'repoSettings' | 'claude'>): string[] {
  const out: string[] = [];
  for (const rs of Object.values(cfg.repoSettings ?? {})) {
    for (const v of Object.values(rs.env ?? {})) if (typeof v === 'string') out.push(v);
  }
  const servers = (cfg.claude?.context?.mcpServers ?? {}) as Record<string, unknown>;
  for (const def of Object.values(servers)) {
    if (!isObject(def)) continue;
    if (isObject(def.env)) for (const v of Object.values(def.env)) if (typeof v === 'string') out.push(v);
    if (isObject(def.headers)) for (const v of Object.values(def.headers)) if (typeof v === 'string') out.push(...withParts(v));
    if (Array.isArray(def.args)) for (const a of def.args) if (typeof a === 'string') out.push(...withParts(a));
    if (typeof def.url === 'string') out.push(...urlSecrets(def.url));
    // the view shows the executable only: the rest of a command line is write-only like the args
    if (typeof def.command === 'string') {
      const words = def.command.trim().split(/\s+/);
      if (words.length > 1) out.push(words.slice(1).join(' '), ...words.slice(1).flatMap(withParts));
    }
  }
  return out;
}
