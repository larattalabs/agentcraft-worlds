// Map Agent SDK stream messages -> agent.log entries + agent state/station.
import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import type { Foreman } from '../../foreman.js';
import { firstLine, headLines, tailLines, truncate } from '../../util/text.js';
import { relPath, toolActivity } from '../activity.js';
import { AUTH_ERRORS, isAuthText, isNetworkText } from './failures.js';

export interface TurnStats {
  sessionId?: string;
  resultText?: string;
  subtype?: string;
  isError: boolean;
  costUsd?: number;
  numTurns?: number;
  authFailed?: string;
  errors: string[];
  /** a usage/rate limit refused this turn (rate_limit_event "rejected" or an API rate_limit error) */
  limited?: boolean;
  /** the latest rate limit report seen in the turn */
  rateLimit?: RateLimitReport;
}

/** A plan usage report from the CLI (claude.ai subscription logins). */
export interface RateLimitReport {
  status: 'allowed' | 'allowed_warning' | 'rejected';
  /** epoch ms */
  resetsAt?: number;
  type?: string;
  /** 0-1 */
  utilization?: number;
}

const LIMIT_RE = /usage limit|rate[ _-]?limit/i;

/**
 * A turn's error text that reports the plan's usage limit ("Claude AI usage limit reached|1759500000",
 * "rate_limit_error"): whether it is one, and when it resets if the text says.
 */
export function limitFromText(text: string): { limited: boolean; resetsAt?: number } {
  if (!LIMIT_RE.test(text)) return { limited: false };
  const at = resetsAtMs(Number(/\|(\d{9,13})\b/.exec(text)?.[1]));
  return at ? { limited: true, resetsAt: at } : { limited: true };
}

/** resetsAt arrives in epoch seconds; accept ms too. */
export function resetsAtMs(v: unknown): number | undefined {
  if (typeof v !== 'number' || !Number.isFinite(v) || v <= 0) return undefined;
  return v < 1e12 ? v * 1000 : v;
}

interface Block {
  type: string;
  text?: string;
  thinking?: string;
  id?: string;
  name?: string;
  input?: unknown;
  tool_use_id?: string;
  content?: unknown;
  is_error?: boolean;
}


function resultText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((c) => (c && typeof c === 'object' && 'text' in c ? String((c as { text: unknown }).text) : ''))
      .filter(Boolean)
      .join('\n');
  }
  return '';
}

/** Short human summary of a tool result for the monitor. */
function summarizeResult(tool: string, text: string): string {
  const name = tool.startsWith('mcp__') ? tool.split('__').pop()! : tool;
  const lines = text.replace(/\r\n/g, '\n').split('\n').filter((l) => l.trim());
  switch (name) {
    case 'Read':
      return `${lines.length} lines`;
    case 'Grep':
    case 'Glob':
    case 'LS':
      return lines.length ? `${lines.length} results\n${headLines(lines.join('\n'), 4, 400)}` : 'no matches';
    case 'Edit':
    case 'MultiEdit':
    case 'Write':
      return firstLine(text, 160) || 'ok';
    case 'Bash':
    case 'PowerShell':
      return tailLines(text, 8, 900) || '(no output)';
    default:
      return headLines(text, 4, 500) || 'ok';
  }
}

/** -/+ lines from an Edit/Write tool input, for a `diff` log entry. */
function diffFromInput(tool: string, input: Record<string, unknown>, cwd: string): string | undefined {
  const file = typeof input.file_path === 'string' ? relPath(input.file_path, cwd) : '?';
  const clip = (s: string, n: number) => s.replace(/\r\n/g, '\n').split('\n').slice(0, n);
  if (tool === 'Edit' && typeof input.old_string === 'string' && typeof input.new_string === 'string') {
    const out = [file, ...clip(input.old_string, 6).map((l) => `- ${l}`), ...clip(input.new_string, 8).map((l) => `+ ${l}`)];
    return out.join('\n');
  }
  if (tool === 'MultiEdit' && Array.isArray(input.edits)) {
    const out = [file];
    for (const e of (input.edits as Array<{ old_string?: string; new_string?: string }>).slice(0, 3)) {
      out.push(...clip(e.old_string ?? '', 3).map((l) => `- ${l}`), ...clip(e.new_string ?? '', 4).map((l) => `+ ${l}`));
    }
    return out.join('\n');
  }
  if (tool === 'Write' && typeof input.content === 'string') {
    const all = input.content.split('\n');
    return [`${file} (${all.length} lines)`, ...clip(input.content, 10).map((l) => `+ ${l}`)].join('\n');
  }
  return undefined;
}

/** The one argument worth showing for a subagent's tool call. */
function subagentArg(input: Record<string, unknown>): string {
  for (const k of ['command', 'file_path', 'pattern', 'path', 'url', 'query', 'description']) if (typeof input[k] === 'string') return input[k] as string;
  return '';
}

export class StreamMapper {
  private toolNames = new Map<string, string>();
  readonly stats: TurnStats = { isError: false, errors: [] };

  constructor(
    private fm: Foreman,
    private agentId: string,
    private cwd: string,
    private role: 'lead' | 'worker',
    /** live usage reports, so the scheduler can react while the turn is still running */
    private onRateLimit?: (r: RateLimitReport) => void,
  ) {}

  handle(msg: SDKMessage): void {
    const fm = this.fm;
    const id = this.agentId;
    switch (msg.type) {
      case 'system': {
        const m = msg as { subtype?: string; session_id?: string; model?: string; tool_name?: string; agent_id?: string; decision_reason_type?: string; decision_reason?: string; message?: string };
        if (m.subtype === 'init' && m.session_id) {
          this.stats.sessionId = m.session_id;
          fm.log.debug(`${id}: session ${m.session_id} (${m.model ?? '?'})`);
        } else if (m.subtype === 'permission_denied') {
          // auto mode's classifier (or a rule) refused a call without asking anyone
          const why = [m.decision_reason_type, m.decision_reason ?? m.message].filter(Boolean).join(': ');
          fm.agentLog(id, 'error', `denied${m.agent_id ? ' (subagent)' : ''}: ${m.tool_name ?? 'tool'}${why ? ` (${truncate(why, 160)})` : ''}`);
        }
        break;
      }
      case 'assistant': {
        if (msg.parent_tool_use_id) {
          // a subagent's work: its tool calls, one line each, so the monitor shows it is busy
          for (const b of (msg.message?.content ?? []) as Block[]) {
            if (b.type === 'tool_use' && b.name) fm.agentLog(id, 'tool', `↳ subagent ${b.name}${b.input && typeof b.input === 'object' ? ` ${truncate(subagentArg(b.input as Record<string, unknown>), 120)}` : ''}`);
          }
          break;
        }
        if (msg.error) {
          const err = String(msg.error);
          this.stats.errors.push(err);
          if (AUTH_ERRORS.has(err)) this.stats.authFailed = err;
          if (err === 'rate_limit') this.stats.limited = true;
          fm.agentLog(id, 'error', `API error: ${err}`);
        }
        this.stats.sessionId ??= msg.session_id;
        const blocks = (msg.message?.content ?? []) as Block[];
        for (const b of blocks) {
          if (b.type === 'text' && b.text?.trim()) {
            fm.agentLog(id, 'text', truncate(b.text.trim(), 1200));
            const a = fm.agent(id);
            if (a && a.state !== 'waiting_user') fm.setAgent(id, { state: 'thinking', activity: firstLine(b.text, 48) });
          } else if (b.type === 'thinking') {
            const a = fm.agent(id);
            if (a && a.state !== 'waiting_user') fm.setAgent(id, { state: 'thinking', activity: 'thinking' });
            if (b.thinking?.trim()) fm.agentLog(id, 'text', `~ ${truncate(b.thinking.trim().replace(/\s+/g, ' '), 300)}`);
          } else if (b.type === 'tool_use' && b.name && b.id) {
            this.toolNames.set(b.id, b.name);
            const input = (b.input ?? {}) as Record<string, unknown>;
            const act = toolActivity(b.name, input, this.cwd);
            fm.agentLog(id, 'tool', act.label);
            const diff = diffFromInput(b.name, input, this.cwd);
            if (diff) fm.agentLog(id, 'diff', diff);
            // (a design job's turn has no avatar: only roster agents get a state)
            if (fm.agent(id)) fm.setAgent(id, { state: act.state, station: act.station, activity: act.activity });
            if ((act.state === 'editing' || act.state === 'running' || act.state === 'testing') && this.role === 'worker') {
              const repoId = fm.agent(id)?.repoId;
              if (repoId) fm.repos.scheduleRefresh(repoId, 1500);
            }
          }
        }
        break;
      }
      case 'user': {
        if (msg.parent_tool_use_id) break;
        const content = (msg.message as { content?: unknown }).content;
        if (!Array.isArray(content)) break;
        for (const b of content as Block[]) {
          if (b.type !== 'tool_result' || !b.tool_use_id) continue;
          const tool = this.toolNames.get(b.tool_use_id) ?? '?';
          // a tool's output (an MCP server's error above all) can quote a secret: cut before it is clipped
          const text = fm.redact(resultText(b.content));
          if (tool.startsWith('mcp__') && !b.is_error) {
            fm.agentLog(id, 'result', headLines(text, 3, 300) || 'ok');
          } else {
            fm.agentLog(id, b.is_error ? 'error' : 'result', b.is_error ? truncate(text || 'tool error', 600) : summarizeResult(tool, text));
          }
        }
        break;
      }
      case 'result': {
        this.stats.subtype = msg.subtype;
        this.stats.isError = msg.is_error || msg.subtype !== 'success';
        this.stats.costUsd = msg.total_cost_usd;
        this.stats.numTurns = msg.num_turns;
        this.stats.sessionId ??= msg.session_id;
        if (msg.subtype === 'success') {
          this.stats.resultText = msg.result;
          if (msg.is_error && isAuthText(msg.result)) this.stats.authFailed = firstLine(msg.result, 200);
        } else {
          this.stats.errors.push(...(msg.errors ?? []).map((x) => fm.redact(x)));
          const joined = (msg.errors ?? []).join(' ');
          if (isAuthText(joined)) this.stats.authFailed = firstLine(joined, 200);
        }
        if (this.stats.isError) {
          const l = limitFromText([this.stats.resultText ?? (msg.subtype === 'success' ? msg.result : ''), ...this.stats.errors].join(' '));
          if (l.limited) {
            this.stats.limited = true;
            if (l.resetsAt && !this.stats.rateLimit?.resetsAt) this.stats.rateLimit = { status: 'rejected', resetsAt: l.resetsAt };
          }
        }
        const cost = typeof msg.total_cost_usd === 'number' ? ` · $${msg.total_cost_usd.toFixed(3)}` : '';
        fm.agentLog(id, this.stats.isError ? 'error' : 'result', `turn ${msg.subtype === 'success' && !msg.is_error ? 'complete' : `ended: ${msg.subtype}`} (${msg.num_turns} steps${cost})`);
        break;
      }
      default: {
        const t = (msg as { type: string; subtype?: string }).type;
        if (t === 'rate_limit_event') {
          const info = (msg as { rate_limit_info?: { status?: string; utilization?: number; rateLimitType?: string; resetsAt?: number } }).rate_limit_info;
          if (info?.status === 'allowed' || info?.status === 'allowed_warning' || info?.status === 'rejected') {
            const r: RateLimitReport = { status: info.status };
            const at = resetsAtMs(info.resetsAt);
            if (at) r.resetsAt = at;
            if (info.rateLimitType) r.type = info.rateLimitType;
            if (typeof info.utilization === 'number') r.utilization = info.utilization;
            this.stats.rateLimit = r;
            if (r.status === 'rejected') this.stats.limited = true;
            this.onRateLimit?.(r);
          }
          if (info?.status === 'rejected') {
            fm.agentLog(id, 'error', `rate limited (${info.rateLimitType ?? 'limit'}); waiting...`);
            if (fm.agent(id)) fm.setAgent(id, { state: 'blocked', activity: 'rate limited - waiting' });
          } else if (info?.status === 'allowed_warning') {
            fm.agentLog(id, 'text', `usage warning: ${info.rateLimitType ?? 'limit'} ${info.utilization !== undefined ? `${Math.round(info.utilization * 100)}%` : ''}`.trim());
          }
        }
        if (t === 'auth_status') {
          const m = msg as { error?: string };
          // (a token refresh that failed on the network is not a bad login)
          if (m.error && (isAuthText(m.error) || !isNetworkText(m.error))) this.stats.authFailed = m.error;
        }
      }
    }
  }
}
