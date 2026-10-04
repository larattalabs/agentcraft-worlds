// What kind of failure ended a turn (or the startup auth probe), so the backend reacts in proportion:
//
//   auth       the login / key is not valid (structured `authentication_failed` and friends, or an
//              exact phrase such as "Invalid API key" or a 401): sticky until the Foreman restarts
//   limit      a usage limit (handled by the usage-limit hold, see index.ts holdForLimit)
//   transient  the network, the machine sleeping, an overloaded or failing API (529 / 5xx), the
//              turn's step limit (max_turns) or its time limit: worth one automatic resume
//   context    the session's prompt is too long: worth one retry in a fresh session
//   fatal      everything else (and the per-turn budget, billing, a bad request): the task blocks
//
// The old checks matched /auth|login|credential|401/ anywhere in an error text, so a network error
// that mentioned "OAuth" or a stack trace with "401" in a port number marked the backend failed
// until a restart.
import type { TurnStats } from './stream.js';

export type FailureKind = 'auth' | 'limit' | 'transient' | 'context' | 'fatal';

/** Structured assistant-message errors (SDKAssistantMessageError) that mean the credentials are bad. */
export const AUTH_ERRORS = new Set(['authentication_failed', 'oauth_org_not_allowed', 'cloud_credential_error', 'verification_required']);
/** Structured errors worth a retry later. */
const TRANSIENT_ERRORS = new Set(['overloaded', 'server_error', 'max_output_tokens']);
/** Structured errors and result subtypes that a retry would only repeat (or pay for again). */
const FATAL_ERRORS = new Set(['billing_error', 'account_on_hold', 'model_not_found', 'error_max_budget_usd', 'error_max_structured_output_retries']);

/** Exact authentication phrases (never a bare "auth" / "login" / "credential"). */
export const AUTH_TEXT_RE =
  /\b401\b|\bauthentication[_ ]failed\b|\binvalid (?:x-)?api[ -]key\b|\bnot logged in\b|\bplease run \/login\b|\boauth token (?:has )?(?:expired|been revoked|is invalid)\b|\boauth_org_not_allowed\b|\bcloud_credential_error\b/i;

const NETWORK_RE =
  /\b(?:ECONNRESET|ECONNREFUSED|ECONNABORTED|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|ENETUNREACH|ENETDOWN|EHOSTUNREACH|EPIPE)\b|socket hang up|fetch failed|network (?:error|is unreachable)|connection (?:error|reset|refused|closed)|request timed out|timed out after|\b529\b|overloaded|\b50[0234]\b|internal server error|bad gateway|service unavailable|api_error/i;

const CONTEXT_RE = /prompt is too long|context (?:window|length) (?:exceeded|limit)|input length and `?max_tokens`? exceed/i;

/** An error text that says the credentials are bad. */
export function isAuthText(text: string): boolean {
  return AUTH_TEXT_RE.test(text);
}

/** An error text from the network / an overloaded or failing API. */
export function isNetworkText(text: string): boolean {
  return NETWORK_RE.test(text);
}

/**
 * Classify a failed turn. `timedOut`: the Foreman's own turn timer ended it (the machine slept, or
 * the agent hung).
 */
export function classifyFailure(stats: TurnStats | undefined, opts: { timedOut?: boolean } = {}): FailureKind {
  if (!stats) return opts.timedOut ? 'transient' : 'fatal';
  if (stats.authFailed) return 'auth';
  if (stats.limited) return 'limit';
  const all = [stats.subtype ?? '', ...stats.errors];
  if (all.some((e) => AUTH_ERRORS.has(e))) return 'auth';
  if (all.some((e) => FATAL_ERRORS.has(e))) return 'fatal';
  const text = [...stats.errors, stats.resultText ?? ''].join(' ');
  if (CONTEXT_RE.test(text)) return 'context';
  if (opts.timedOut || stats.subtype === 'error_max_turns' || stats.subtype === 'timeout') return 'transient';
  if (all.some((e) => TRANSIENT_ERRORS.has(e))) return 'transient';
  if (isNetworkText(text)) return 'transient';
  return 'fatal';
}

/** Startup auth probe: a thrown error -> sticky auth failure, or a network-type problem to retry. */
export function probeFailure(message: string): 'auth' | 'retry' {
  return message === 'not logged in' || isAuthText(message) ? 'auth' : 'retry';
}
