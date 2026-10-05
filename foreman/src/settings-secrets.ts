// Settings that hold secrets (docs/WAVE3.md contracts S1 and S2), for settings.ts.
//
// S1 secret maps (type "secretMap": a repository's `env`, each MCP server's `env`): config.get shows
// the variable names only, `{ NAME: "(set)" }`; config.set takes a partial update `{ NAME: "value" |
// null }` (null removes a variable) that is merged into what config.json holds now.
//
// S2 MCP servers (type "mcpServers": claude.context.mcpServers): config.get shows `[{ name, type,
// command?, args?, url?, envKeys }]`; config.set takes the same entries (env in the S1 partial form,
// under `env`) and `{ name, remove: true }`, each an upsert of that one server.
//
// Nothing secret leaves through here: env values are never shown; an argument in a credential
// position (after --token, --api-key=..., KEY=... with a secret-looking name, a token-shaped word)
// is shown as "(hidden)"; a URL is shown without credentials or query. When an entry comes back
// with the hidden or shortened form in the same place, the stored original is kept, so editing a
// server in the hub never loses what the hub could not show. Error messages name keys, never values.
import { createHash } from 'node:crypto';
import { RESERVED_KEYS } from './config.js';
import { isSecretEnvVar } from './util/env.js';

type Raw = Record<string, unknown>;
type Checked = { value: unknown } | { error: string };

/** The value config.get shows for every variable of a secret map. */
export const SECRET_SET = '(set)';
/** An argument config.get does not show. */
export const HIDDEN = '(hidden)';

const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
const MAX_VALUE = 20_000;
const MAX_KEYS = 200;

const isObject = (v: unknown): v is Raw => !!v && typeof v === 'object' && !Array.isArray(v);
const own = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k);

// ---- S1 secret maps ----------------------------------------------------------------------------

/** config.get's view: names only. */
export function secretMapView(m: unknown): Record<string, string> {
  return Object.fromEntries(Object.keys(isObject(m) ? m : {}).filter((k) => !RESERVED_KEYS.has(k)).map((k) => [k, SECRET_SET]));
}

/** A partial update `{ NAME: string | null }`. `refuse` names variables that may not be set here. */
export function checkSecretPatch(v: unknown, refuse?: (name: string) => string | undefined): Checked {
  if (!isObject(v)) return { error: 'must be an object of variable names to text (or null to remove one)' };
  const keys = Object.keys(v);
  if (keys.length > MAX_KEYS) return { error: `at most ${MAX_KEYS} variables` };
  const problems: string[] = [];
  for (const k of keys) {
    if (!ENV_NAME.test(k) || RESERVED_KEYS.has(k)) {
      problems.push(`"${k.slice(0, 64)}" is not a variable name`);
      continue;
    }
    const why = refuse?.(k);
    if (why) {
      problems.push(`${k}: ${why}`);
      continue;
    }
    const val = v[k];
    if (val === null) continue;
    if (typeof val !== 'string') problems.push(`${k}: must be text or null`);
    else if (val.length > MAX_VALUE) problems.push(`${k}: is too long`);
    else if (val.includes('\0')) problems.push(`${k}: must not contain a NUL character`);
  }
  return problems.length ? { error: problems.join(', ') } : { value: v };
}

/** Apply a checked partial update to the stored map; undefined when nothing is left. */
export function mergeSecretMap(current: unknown, patch: Record<string, string | null>): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  if (isObject(current)) for (const [k, val] of Object.entries(current)) if (!RESERVED_KEYS.has(k) && typeof val === 'string') out[k] = val;
  for (const [k, val] of Object.entries(patch)) {
    if (val === null) delete out[k];
    else out[k] = val;
  }
  return Object.keys(out).length ? out : undefined;
}

/** repoSettings env: the variables config.ts would drop (git's own, the client token) are refused outright. */
export function repoEnvRefusal(name: string): string | undefined {
  if (/^GIT_/i.test(name)) return 'git variables cannot be set for a repository (AgentCraft sets git up itself)';
  if (isSecretEnvVar(name)) return 'the client token never reaches agents';
  return undefined;
}

function mcpEnvRefusal(name: string): string | undefined {
  return isSecretEnvVar(name) ? 'the client token never reaches agents' : undefined;
}

// ---- S2 MCP servers ----------------------------------------------------------------------------

export type McpType = 'stdio' | 'http' | 'sse';

export interface McpServerView {
  name: string;
  type: McpType;
  command?: string;
  args?: string[];
  url?: string;
  envKeys: string[];
}

const SECRET_WORD = /token|secret|passw(or)?d|passwd|pwd|api[-_]?key|apikey|auth|credential|bearer|private[-_]?key|access[-_]?key|session[-_]?key|cookie/i;
/** Words shaped like a credential: known prefixes, or a long run of key characters. */
const TOKEN_SHAPE = /^(sk-|sk_|pk_|rk_|ghp_|gho_|ghu_|ghs_|ghr_|github_pat_|glpat-|xox[abposr]-|AKIA|AIza|ya29\.|eyJ)[\w.-]{6,}$|^[A-Za-z0-9+/_=-]{32,}$/;

function serverType(d: Raw): McpType {
  if (d.type === 'http' || d.type === 'sse') return d.type;
  if (typeof d.command === 'string') return 'stdio';
  return typeof d.url === 'string' ? 'http' : 'stdio';
}

/** A URL without credentials, query or fragment (undefined: not a URL). */
function shortUrl(u: string): string | undefined {
  try {
    const p = new URL(u);
    p.username = '';
    p.password = '';
    p.search = '';
    p.hash = '';
    return p.toString();
  } catch {
    return undefined;
  }
}

/** One argument as config.get shows it (`prev`: the argument before it). */
function maskArg(a: string, prev: string | undefined): string {
  if (prev && /^--?[\w-]+$/.test(prev) && SECRET_WORD.test(prev) && !a.startsWith('-')) return HIDDEN;
  const eq = /^(--?[\w-]+|[A-Za-z_][A-Za-z0-9_]*)=(.+)$/.exec(a);
  if (eq && SECRET_WORD.test(eq[1]!)) return `${eq[1]}=${HIDDEN}`;
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(a)) {
    const s = shortUrl(a);
    if (s !== undefined && s !== a && s !== `${a}/`) return s;
    return a;
  }
  if (TOKEN_SHAPE.test(a) && !a.includes('/')) return HIDDEN;
  return a;
}

function maskArgs(args: string[]): string[] {
  return args.map((a, i) => maskArg(a, args[i - 1]));
}

/** config.get's view of claude.context.mcpServers (as the Foreman parsed it). */
export function mcpServersView(servers: Record<string, unknown>): McpServerView[] {
  return Object.entries(servers).map(([name, def]) => {
    const d = isObject(def) ? def : {};
    const type = serverType(d);
    const args = Array.isArray(d.args) ? d.args.filter((a): a is string => typeof a === 'string') : undefined;
    const url = typeof d.url === 'string' ? (shortUrl(d.url) ?? '(not a URL)') : undefined;
    return {
      name,
      type,
      ...(typeof d.command === 'string' ? { command: d.command } : {}),
      ...(args ? { args: maskArgs(args) } : {}),
      ...(url !== undefined ? { url } : {}),
      envKeys: Object.keys(isObject(d.env) ? d.env : {}).filter((k) => !RESERVED_KEYS.has(k)),
    };
  });
}

/** A change of the hash of every server (values included): a restart is due even when the view looks the same. */
export function mcpServersFingerprint(servers: Record<string, unknown>): string {
  return createHash('sha256').update(JSON.stringify(servers)).digest('hex');
}

const NAME_RE = /^[\w-]{1,64}$/;

interface Entry {
  name: string;
  remove?: true;
  type?: McpType;
  command?: string;
  args?: string[];
  url?: string;
  env?: Record<string, string | null>;
}

/** Check the entries of a config.set value (shape only; merged with the file in mergeMcpServers). */
export function checkMcpEntries(v: unknown): Checked {
  if (!Array.isArray(v)) return { error: 'must be a list of servers ({name, type, command, args, url, env} or {name, remove: true})' };
  if (v.length > 50) return { error: 'at most 50 servers at once' };
  const problems: string[] = [];
  const seen = new Set<string>();
  const entries: Entry[] = [];
  for (const [i, e] of v.entries()) {
    if (!isObject(e)) {
      problems.push(`entry ${i + 1}: must be an object`);
      continue;
    }
    const name = typeof e.name === 'string' ? e.name.trim() : '';
    const label = NAME_RE.test(name) ? name : `entry ${i + 1}`;
    if (!NAME_RE.test(name) || RESERVED_KEYS.has(name)) {
      problems.push(`${label}: name must be letters, digits, _ or - (at most 64)`);
      continue;
    }
    if (name.toLowerCase() === 'agentcraft') {
      problems.push(`${name}: is the team tools server's name`);
      continue;
    }
    if (seen.has(name)) {
      problems.push(`${name}: listed twice`);
      continue;
    }
    seen.add(name);
    const unknown = Object.keys(e).filter((k) => !['name', 'remove', 'type', 'command', 'args', 'url', 'env', 'envKeys'].includes(k));
    if (unknown.length) {
      problems.push(`${name}: unknown field${unknown.length > 1 ? 's' : ''} ${unknown.map((k) => k.slice(0, 32)).join(', ')}`);
      continue;
    }
    if (e.remove !== undefined) {
      if (e.remove !== true) problems.push(`${name}: remove must be true`);
      else if (Object.keys(e).some((k) => k !== 'name' && k !== 'remove')) problems.push(`${name}: a removal takes only name and remove`);
      else entries.push({ name, remove: true });
      continue;
    }
    if (e.type !== 'stdio' && e.type !== 'http' && e.type !== 'sse') {
      problems.push(`${name}: type must be stdio, http or sse`);
      continue;
    }
    const entry: Entry = { name, type: e.type };
    if (e.type === 'stdio') {
      if (typeof e.command !== 'string' || !e.command.trim() || e.command.length > 1000 || /[\0\r\n]/.test(e.command)) {
        problems.push(`${name}: a stdio server needs a command (one line)`);
        continue;
      }
      if (e.url !== undefined) {
        problems.push(`${name}: a stdio server has no url`);
        continue;
      }
      entry.command = e.command.trim();
      if (e.args !== undefined) {
        if (!Array.isArray(e.args) || e.args.length > 100 || e.args.some((a) => typeof a !== 'string' || a.length > 4000 || a.includes('\0'))) {
          problems.push(`${name}: args must be a list of text (at most 100)`);
          continue;
        }
        entry.args = e.args as string[];
      }
    } else {
      if (e.command !== undefined || e.args !== undefined) {
        problems.push(`${name}: an ${e.type} server has a url, not a command`);
        continue;
      }
      const url = typeof e.url === 'string' ? e.url.trim() : '';
      let parsed: URL | undefined;
      try {
        parsed = new URL(url);
      } catch {
        /* below */
      }
      if (!parsed || (parsed.protocol !== 'https:' && parsed.protocol !== 'http:')) {
        problems.push(`${name}: url must be an http(s) URL`);
        continue;
      }
      if (parsed.username || parsed.password) {
        problems.push(`${name}: url must not contain credentials (put them into the server's env or config.json headers)`);
        continue;
      }
      if (parsed.search || parsed.hash) {
        problems.push(`${name}: url must not contain a query or fragment`);
        continue;
      }
      entry.url = url;
    }
    if (e.env !== undefined) {
      if (e.type !== 'stdio') {
        problems.push(`${name}: env is for stdio servers`);
        continue;
      }
      const env = checkSecretPatch(e.env, mcpEnvRefusal);
      if ('error' in env) {
        problems.push(`${name} env: ${env.error}`);
        continue;
      }
      entry.env = env.value as Record<string, string | null>;
    }
    entries.push(entry);
  }
  return problems.length ? { error: problems.join('; ') } : { value: entries };
}

/** The new claude.context.mcpServers for the file: checked entries applied to what it holds now. */
export function mergeMcpServers(current: unknown, entries: Entry[]): Checked {
  const out: Raw = {};
  if (isObject(current)) for (const [k, d] of Object.entries(current)) if (!RESERVED_KEYS.has(k)) out[k] = d;
  const problems: string[] = [];
  for (const e of entries) {
    const before = own(out, e.name) && isObject(out[e.name]) ? (out[e.name] as Raw) : undefined;
    if (e.remove) {
      if (!own(out, e.name)) problems.push(`${e.name}: no such MCP server`);
      else delete out[e.name];
      continue;
    }
    // fields the hub does not edit (headers, timeouts, ...) stay as they are
    const next: Raw = { ...(before ?? {}) };
    delete next.command;
    delete next.args;
    delete next.url;
    if (e.type === 'stdio') {
      if (before?.type === 'http' || before?.type === 'sse') delete next.type;
      if (before?.type === 'stdio') next.type = 'stdio';
      next.command = e.command;
      if (e.args) next.args = restoreArgs(e.args, Array.isArray(before?.args) ? (before.args as unknown[]).filter((a): a is string => typeof a === 'string') : []);
      const env = mergeSecretMap(before?.env, e.env ?? {});
      if (env) next.env = env;
      else delete next.env;
    } else {
      next.type = e.type;
      // the URL as shown (no credentials / query): the stored one stays
      const stored = typeof before?.url === 'string' ? before.url : undefined;
      next.url = stored !== undefined && shortUrl(stored) === e.url ? stored : e.url;
      delete next.env;
    }
    out[e.name] = next;
  }
  if (problems.length) return { error: problems.join('; ') };
  return { value: Object.keys(out).length ? out : undefined };
}

/** Arguments as sent, with every hidden one that matches the stored argument's view in the same place restored. */
function restoreArgs(sent: string[], stored: string[]): string[] {
  const view = maskArgs(stored);
  return sent.map((a, i) => (i < stored.length && a === view[i] && view[i] !== stored[i] ? stored[i]! : a));
}
