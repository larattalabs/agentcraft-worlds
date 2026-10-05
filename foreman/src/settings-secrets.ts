// Settings that hold secrets (docs/WAVE3.md contracts S1 and S2), for settings.ts.
//
// Secrets are write-only by design, not masked by heuristics: config.get never returns a value that
// could be one, so there is nothing to restore and nothing that can leak through a view.
//
// S1 secret maps (type "secretMap": a repository's `env`, each MCP server's `env`): config.get shows
// the variable names only, as a list `["NAME", ...]`; config.set takes a partial update `{ NAME:
// "value" | null }` (null removes a variable) merged into what config.json holds now. A value that
// is a placeholder ("(set)", "(hidden)", "(staged)", "[redacted]") or holds control characters is
// refused, so echoing a view can never overwrite a secret.
//
// S2 MCP servers (type "mcpServers": claude.context.mcpServers): config.get shows `[{ name, type,
// command?, argCount?, url?, urlHasPath?, envKeys, headerKeys? }]`: the executable only (the first
// word of the command), how many arguments there are, the URL as scheme://host[:port]. config.set
// takes `[{ name, type, command?, args?, url?, env? } | { name, remove: true }]`, each an upsert of
// that one server: a field left out keeps the stored value exactly, a field sent replaces it exactly
// (args: the complete new list). The view's read-only fields (envKeys, headerKeys, argCount,
// urlHasPath) may be sent back and are ignored. A `url` or `command` equal to what config.get showed
// for a longer stored one is refused (it would cut the stored value down to its view).
//
// Errors name places (server #2, env key #1), never what the caller sent.
import { createHash } from 'node:crypto';
import { RESERVED_KEYS } from './config.js';
import { REDACTED } from './redact.js';
import { isSecretEnvVar } from './util/env.js';

type Raw = Record<string, unknown>;
type Checked = { value: unknown } | { error: string };

/** Values config.get or the hub show in place of a secret: never stored as one. */
export const PLACEHOLDERS = ['(set)', '(hidden)', '(staged)', '(not shown)', REDACTED];

const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
const MAX_VALUE = 20_000;
const MAX_KEYS = 200;
/** Control characters a value may not hold (tab, newline and carriage return are fine: a PEM key has lines). */
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/;
// eslint-disable-next-line no-control-regex
const ANY_CONTROL = /[\u0000-\u001f\u007f]/;

const isObject = (v: unknown): v is Raw => !!v && typeof v === 'object' && !Array.isArray(v);
const own = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k);

/** A placeholder sent back as a value (what a view showed, not a secret). */
export function isPlaceholder(v: string): boolean {
  const t = v.trim().toLowerCase();
  return PLACEHOLDERS.some((p) => t === p.toLowerCase());
}

/** Why a secret value is refused (no value is ever named), or undefined. */
function valueProblem(v: unknown): string | undefined {
  if (typeof v !== 'string') return 'must be text or null';
  if (v.length > MAX_VALUE) return 'is too long';
  if (CONTROL.test(v)) return 'must not contain control characters';
  if (isPlaceholder(v)) return 'is a placeholder, not a value (leave the variable out to keep it)';
  return undefined;
}

// ---- S1 secret maps ----------------------------------------------------------------------------

/** config.get's view: the variable names only. */
export function secretMapView(m: unknown): string[] {
  return Object.keys(isObject(m) ? m : {}).filter((k) => !RESERVED_KEYS.has(k));
}

/** A partial update `{ NAME: string | null }`. `refuse` says why a variable may not be set here. */
export function checkSecretPatch(v: unknown, refuse?: (name: string) => string | undefined): Checked {
  if (!isObject(v)) return { error: 'must be an object of variable names to text (or null to remove one)' };
  const keys = Object.keys(v);
  if (keys.length > MAX_KEYS) return { error: `at most ${MAX_KEYS} variables` };
  const problems: string[] = [];
  for (const [i, k] of keys.entries()) {
    const at = `env key #${i + 1}`;
    if (!ENV_NAME.test(k) || RESERVED_KEYS.has(k)) {
      problems.push(`${at} is not a valid variable name`);
      continue;
    }
    const why = refuse?.(k);
    if (why) {
      problems.push(`${at}: ${why}`);
      continue;
    }
    const val = v[k];
    if (val === null) continue;
    const bad = valueProblem(val);
    if (bad) problems.push(`${at}: the value ${bad}`);
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
  /** the executable only (the first word of the stored command) */
  command?: string;
  /** stdio: how many arguments are stored (never their values) */
  argCount?: number;
  /** http / sse: scheme://host[:port] only (absent: the stored URL does not parse) */
  url?: string;
  /** http / sse: the stored URL has more than scheme://host[:port] (a path, query or fragment) */
  urlHasPath?: boolean;
  envKeys: string[];
  /** the names of config.json's headers (values never) */
  headerKeys?: string[];
}

function serverType(d: Raw): McpType {
  if (d.type === 'http' || d.type === 'sse') return d.type;
  if (typeof d.command === 'string') return 'stdio';
  return typeof d.url === 'string' ? 'http' : 'stdio';
}

/** The executable of a command line (what config.get shows). */
const executable = (command: string) => command.trim().split(/\s+/)[0] ?? '';

/** scheme://host[:port] of a URL, and whether it has more; undefined: not a URL. */
function urlView(u: string): { url: string; hasPath: boolean } | undefined {
  try {
    const p = new URL(u);
    return { url: p.origin === 'null' ? `${p.protocol}//${p.host}` : p.origin, hasPath: (p.pathname !== '' && p.pathname !== '/') || !!p.search || !!p.hash || !!p.username || !!p.password };
  } catch {
    return undefined;
  }
}

/** config.get's view of claude.context.mcpServers (as the Foreman parsed it). */
export function mcpServersView(servers: Record<string, unknown>): McpServerView[] {
  return Object.entries(servers).map(([name, def]) => {
    const d = isObject(def) ? def : {};
    const type = serverType(d);
    const view: McpServerView = { name, type, envKeys: Object.keys(isObject(d.env) ? d.env : {}).filter((k) => !RESERVED_KEYS.has(k)) };
    if (type === 'stdio') {
      if (typeof d.command === 'string') view.command = executable(d.command);
      view.argCount = Array.isArray(d.args) ? d.args.length : 0;
    } else if (typeof d.url === 'string') {
      const u = urlView(d.url);
      if (u) view.url = u.url;
      view.urlHasPath = u ? u.hasPath : true;
    }
    const headers = isObject(d.headers) ? Object.keys(d.headers).filter((k) => !RESERVED_KEYS.has(k)) : [];
    if (headers.length) view.headerKeys = headers;
    // read-only fields first, envKeys last (stable order for the hub)
    const { envKeys, ...rest } = view;
    return { ...rest, envKeys };
  });
}

/** A change of the hash of every server (values included): a restart is due even when the view looks the same. */
export function mcpServersFingerprint(servers: Record<string, unknown>): string {
  return createHash('sha256').update(JSON.stringify(servers)).digest('hex');
}

const NAME_RE = /^[\w-]{1,64}$/;
const FIELDS = ['name', 'remove', 'type', 'command', 'args', 'url', 'env', 'envKeys', 'headerKeys', 'argCount', 'urlHasPath'];

interface Entry {
  /** 1-based place in the list (errors name it, never the name the caller sent) */
  pos: number;
  name: string;
  remove?: true;
  type?: McpType;
  command?: string;
  args?: string[];
  url?: string;
  env?: Record<string, string | null>;
}

const isStringList = (v: unknown) => Array.isArray(v) && v.every((x) => typeof x === 'string');

/** An http(s) URL as stored (normalized), or why it is refused. */
function checkUrl(v: unknown): { url: string } | { error: string } {
  if (typeof v !== 'string' || !v || v.length > 2000) return { error: 'url must be an http(s) URL' };
  if (ANY_CONTROL.test(v) || /\s/.test(v)) return { error: 'url must not contain spaces or control characters' };
  let p: URL;
  try {
    p = new URL(v);
  } catch {
    return { error: 'url must be an http(s) URL' };
  }
  if (p.protocol !== 'https:' && p.protocol !== 'http:') return { error: 'url must be an http(s) URL' };
  if (p.username || p.password) return { error: 'url must not contain credentials (put them into the server\'s headers in config.json)' };
  if (p.hash) return { error: 'url must not contain a fragment' };
  return { url: p.href };
}

/** Check the entries of a config.set value (shape only; merged with the file in mergeMcpServers). */
export function checkMcpEntries(v: unknown): Checked {
  if (!Array.isArray(v)) return { error: 'must be a list of servers ({name, type, command, args, url, env} or {name, remove: true})' };
  if (v.length > 50) return { error: 'at most 50 servers at once' };
  const problems: string[] = [];
  const seen = new Set<string>();
  const entries: Entry[] = [];
  for (const [i, e] of v.entries()) {
    const at = `server #${i + 1}`;
    if (!isObject(e)) {
      problems.push(`${at}: must be an object`);
      continue;
    }
    const name = typeof e.name === 'string' ? e.name.trim() : '';
    if (!NAME_RE.test(name) || RESERVED_KEYS.has(name)) {
      problems.push(`${at}: name must be letters, digits, _ or - (at most 64)`);
      continue;
    }
    if (name.toLowerCase() === 'agentcraft') {
      problems.push(`${at}: agentcraft is the team tools server's name`);
      continue;
    }
    if (seen.has(name)) {
      problems.push(`${at}: the same server is listed twice`);
      continue;
    }
    seen.add(name);
    const unknown = Object.keys(e).filter((k) => !FIELDS.includes(k)).length;
    if (unknown) {
      problems.push(`${at}: ${unknown} unknown field${unknown > 1 ? 's' : ''} (fields: ${FIELDS.join(', ')})`);
      continue;
    }
    // the view's read-only fields may come back; they are ignored, but must have the view's types
    if ((e.envKeys !== undefined && !isStringList(e.envKeys)) || (e.headerKeys !== undefined && !isStringList(e.headerKeys))) {
      problems.push(`${at}: envKeys and headerKeys are lists of names (read-only, ignored)`);
      continue;
    }
    if ((e.argCount !== undefined && !(typeof e.argCount === 'number' && Number.isInteger(e.argCount) && e.argCount >= 0)) || (e.urlHasPath !== undefined && typeof e.urlHasPath !== 'boolean')) {
      problems.push(`${at}: argCount is a number and urlHasPath true or false (read-only, ignored)`);
      continue;
    }
    if (e.remove !== undefined) {
      if (e.remove !== true) problems.push(`${at}: remove must be true`);
      else if (Object.keys(e).some((k) => k !== 'name' && k !== 'remove')) problems.push(`${at}: a removal takes only name and remove`);
      else entries.push({ pos: i + 1, name, remove: true });
      continue;
    }
    if (e.type !== 'stdio' && e.type !== 'http' && e.type !== 'sse') {
      problems.push(`${at}: type must be stdio, http or sse`);
      continue;
    }
    const entry: Entry = { pos: i + 1, name, type: e.type };
    if (e.type === 'stdio') {
      if (e.url !== undefined) {
        problems.push(`${at}: a stdio server has no url`);
        continue;
      }
      if (e.command !== undefined) {
        if (typeof e.command !== 'string' || !e.command.trim() || e.command.length > 1000 || ANY_CONTROL.test(e.command)) {
          problems.push(`${at}: command must be one line of text`);
          continue;
        }
        if (isPlaceholder(e.command)) {
          problems.push(`${at}: command is a placeholder, not a value`);
          continue;
        }
        entry.command = e.command.trim();
      }
      if (e.args !== undefined) {
        if (!Array.isArray(e.args) || e.args.length > 100 || e.args.some((a) => typeof a !== 'string' || a.length > 4000 || CONTROL.test(a))) {
          problems.push(`${at}: args must be a list of text (at most 100, no control characters)`);
          continue;
        }
        const ph = (e.args as string[]).findIndex((a) => isPlaceholder(a) || PLACEHOLDERS.some((p) => a.endsWith(`=${p}`)));
        if (ph >= 0) {
          problems.push(`${at}: argument #${ph + 1} is a placeholder, not a value (leave args out to keep the stored ones)`);
          continue;
        }
        entry.args = e.args as string[];
      }
    } else {
      if (e.command !== undefined || e.args !== undefined) {
        problems.push(`${at}: an ${e.type} server has a url, not a command`);
        continue;
      }
      if (e.url !== undefined) {
        const u = checkUrl(e.url);
        if ('error' in u) {
          problems.push(`${at}: ${u.error}`);
          continue;
        }
        entry.url = u.url;
      }
    }
    if (e.env !== undefined) {
      if (e.type !== 'stdio') {
        problems.push(`${at}: env is for stdio servers`);
        continue;
      }
      const env = checkSecretPatch(e.env, mcpEnvRefusal);
      if ('error' in env) {
        problems.push(`${at} env: ${env.error}`);
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
    const at = `server #${e.pos}`;
    const before = own(out, e.name) && isObject(out[e.name]) ? (out[e.name] as Raw) : undefined;
    if (e.remove) {
      if (!own(out, e.name)) problems.push(`${at}: no such MCP server`);
      else delete out[e.name];
      continue;
    }
    const wasType = before ? serverType(before) : undefined;
    // fields the hub does not edit (headers, timeouts, ...) stay as they are
    const next: Raw = { ...(before ?? {}) };
    if (e.type === 'stdio') {
      const sameType = wasType === 'stdio';
      const storedCommand = sameType && typeof before?.command === 'string' ? before.command : undefined;
      if (e.command !== undefined) {
        // the view of a longer stored command sent back would cut it down: refused, never "restored"
        if (storedCommand !== undefined && e.command === executable(storedCommand) && e.command !== storedCommand.trim()) {
          problems.push(`${at}: command is what config.get shows of the stored one; leave it out to keep it, or send the whole new command`);
          continue;
        }
        next.command = e.command;
      } else if (storedCommand !== undefined) next.command = storedCommand;
      else {
        problems.push(`${at}: a stdio server needs a command`);
        continue;
      }
      if (e.args !== undefined) {
        if (e.args.length) next.args = e.args;
        else delete next.args;
      } else if (!sameType) delete next.args;
      delete next.url;
      if (before?.type === 'http' || before?.type === 'sse') delete next.type;
      const env = mergeSecretMap(sameType ? before?.env : undefined, e.env ?? {});
      if (env) next.env = env;
      else delete next.env;
    } else {
      const sameType = wasType === 'http' || wasType === 'sse';
      const storedUrl = sameType && typeof before?.url === 'string' ? before.url : undefined;
      if (e.url !== undefined) {
        const shown = storedUrl !== undefined ? urlView(storedUrl) : undefined;
        if (shown?.hasPath && (e.url === shown.url || e.url === `${shown.url}/`)) {
          problems.push(`${at}: url is what config.get shows of the stored one; leave it out to keep it, or send the whole new URL`);
          continue;
        }
        next.url = e.url;
      } else if (storedUrl !== undefined) next.url = storedUrl;
      else {
        problems.push(`${at}: an ${e.type} server needs a url`);
        continue;
      }
      next.type = e.type;
      delete next.command;
      delete next.args;
      delete next.env;
    }
    out[e.name] = next;
  }
  if (problems.length) return { error: problems.join('; ') };
  return { value: Object.keys(out).length ? out : undefined };
}
