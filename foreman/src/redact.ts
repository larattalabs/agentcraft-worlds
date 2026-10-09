// The central redactor (docs/WAVE3.md S1/S2): best-effort defense for display and log channels (the
// settings view itself never holds a secret). Every secret value the Foreman knows - repository env
// values, MCP server env values, header values, URL userinfo / query / fragment, MCP arguments that
// follow a credential flag or are credential-like NAME=value, the client token, the inherited Claude
// credentials - is cut out of the text that leaves the Foreman or is kept by it: agent log entries,
// feed items, structured text (blocked reasons, messages, notes), ack / error texts, notifications
// and console logs. Not covered: secrets shorter than MIN_SECRET, other case / encoding variants,
// and text the user or the lead writes (task titles and descriptions, decision options, goal text,
// diffs).
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
      if (typeof v !== 'string') continue;
      // a multi-line secret (a PEM key) is also cut line by line (each line on its own length)
      const lines = /[\r\n]/.test(v) ? v.split(/\r?\n|\r/).map((l) => l.trim()) : [];
      for (const x of [v, ...lines]) {
        if (x.length < MIN_SECRET || this.values.has(x)) continue;
        this.values.add(x);
        changed = true;
      }
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
   * `text` without any known secret. Text cut off with "…" (util/text.ts truncate, headLines; the cut
   * may sit inside a longer text, e.g. `denied: Bash (curl -H sk-…)`) also loses a secret's start left
   * before each "…".
   */
  redact(text: string): string {
    if (!this.re || !text) return text;
    const out = text.replace(this.re, REDACTED);
    if (!out.includes('…')) return out;
    const parts = out.split('…');
    for (let i = 0; i < parts.length - 1; i++) {
      const body = parts[i]!;
      let cut = 0;
      for (const v of this.variants) {
        for (let n = Math.min(v.length - 1, body.length); n > cut && n >= MIN_PARTIAL; n--) {
          if (body.endsWith(v.slice(0, n))) {
            cut = n;
            break;
          }
        }
      }
      if (cut) parts[i] = `${body.slice(0, body.length - cut)}${REDACTED}`;
    }
    return parts.join('…');
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

/** The parts of a URL that are credentials: userinfo, the query (and each value), the fragment. */
function urlSecrets(u: string): string[] {
  let p: URL;
  try {
    p = new URL(u);
  } catch {
    return [];
  }
  const out: string[] = [];
  if (p.username) out.push(p.username, safeDecode(p.username));
  if (p.password) out.push(p.password, safeDecode(p.password));
  if (p.search) {
    out.push(p.search, p.search.slice(1));
    for (const [, val] of p.searchParams) out.push(val);
  }
  if (p.hash) out.push(p.hash, p.hash.slice(1));
  return out;
}

function safeDecode(s: string): string {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

/** A header or credential value and the parts of it that are a credential on their own ("Bearer x" -> x; "X-Key: x" -> x). */
function valueParts(v: string): string[] {
  const out = [v];
  const colon = /^[\w-]+:\s*([\s\S]+)$/.exec(v);
  const value = colon ? colon[1]! : v;
  if (colon) out.push(value);
  const words = value.trim().split(/\s+/);
  if (words.length > 1) out.push(words.slice(1).join(' '), ...words.slice(1));
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(v)) out.push(...urlSecrets(v));
  return out;
}

/** A flag whose value is a credential: --token, --key, --password / --pass / --passphrase, --secret, -p, -H / --header, --auth*, *key / *token / *secret / *pass. */
export const CREDENTIAL_FLAG = /^(-p|-H|--?(token|key|password|pass|passphrase|passwd|secret|headers?|auth[\w-]*|[\w-]*(key|keys|token|tokens|secret|secrets|pass|passwd|password|passphrase)))$/i;
/** A NAME=value whose name looks like a credential. */
const CREDENTIAL_NAME = /token|secret|passw(or)?d|passwd|passphrase|pwd|pass$|^pass|api[-_]?key|apikey|[-_]key$|^key$|auth|credential|bearer|private|cookie|session/i;

/** Does a variable name look like it holds a credential (GITHUB_TOKEN, API_KEY, DB_PASSWORD...)? */
export function credentialLikeName(name: string): boolean {
  return CREDENTIAL_NAME.test(name);
}

/** The credentials among MCP arguments: values after a credential flag, --flag=value of one, NAME=value with a credential-like name. */
export function argSecrets(args: string[]): string[] {
  const out: string[] = [];
  for (const [i, a] of args.entries()) {
    const prev = args[i - 1];
    if (prev !== undefined && CREDENTIAL_FLAG.test(prev)) out.push(...valueParts(a));
    const eq = /^(--?[\w-]+|[A-Za-z_][\w.-]*)=([\s\S]+)$/.exec(a);
    if (eq && (CREDENTIAL_FLAG.test(eq[1]!) || CREDENTIAL_NAME.test(eq[1]!.replace(/^-+/, '')))) out.push(...valueParts(eq[2]!));
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(a)) out.push(...urlSecrets(a));
  }
  return out;
}

/**
 * The secret values a configuration holds: repository env values, MCP env values, header values, URL
 * userinfo / query / fragment, and MCP arguments that are credentials (argSecrets). Plain arguments
 * (paths, branch names, package names) are not secrets: the view hides them, the redactor leaves them.
 */
export function configSecrets(cfg: Pick<Config, 'repoSettings' | 'claude'>): string[] {
  const out: string[] = [];
  for (const rs of Object.values(cfg.repoSettings ?? {})) {
    for (const v of Object.values(rs.env ?? {})) if (typeof v === 'string') out.push(v);
  }
  const servers = (cfg.claude?.context?.mcpServers ?? {}) as Record<string, unknown>;
  for (const def of Object.values(servers)) {
    if (!isObject(def)) continue;
    if (isObject(def.env)) for (const v of Object.values(def.env)) if (typeof v === 'string') out.push(v);
    if (isObject(def.headers)) for (const v of Object.values(def.headers)) if (typeof v === 'string') out.push(...valueParts(v));
    const words = typeof def.command === 'string' ? def.command.trim().split(/\s+/).slice(1) : [];
    out.push(...argSecrets([...words, ...(Array.isArray(def.args) ? def.args.filter((a): a is string => typeof a === 'string') : [])]));
    if (typeof def.url === 'string') out.push(...urlSecrets(def.url));
  }
  return out;
}

/** Credentials the Foreman inherited from its environment (never printed). */
export const INHERITED_SECRET_VARS = ['ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN', 'CLAUDE_CODE_OAUTH_TOKEN'];
