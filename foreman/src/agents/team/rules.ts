// The user's Claude Code permission rules (claude.permissions deny / ask) for engines that do not
// apply them themselves (Codex). The Claude CLI applies all three lists to Claude agents; for a
// Codex agent the Foreman's permission gate applies `deny` and `ask` to shell commands:
//
//   "Bash"                    every command
//   "Bash(tofu apply:*)"      any command segment that starts with these words
//   "Bash(npm run deploy)"    a segment that is exactly this
//   "Bash(az deployment *)"   `*` matches anything
//
// A command is split into its segments (`a && b | c`, `;`, newlines) with the shared shell lexer, and
// leading VAR=value assignments are ignored, so `X=1 tofu apply` and `ls; tofu apply` match. `allow`
// rules are not applied to Codex agents (they would skip AgentCraft's policy); rules for other tools
// (Edit(...), Read(...), WebFetch(...)) do not apply to them either. Best effort, like every text
// check: a script that runs the command is not seen.
import { splitSegments } from '../../shell.js';

const SHELL_TOOLS = new Set(['Bash', 'PowerShell']);

/** The command words of a segment, without leading VAR=value assignments. */
function segmentText(seg: string): string {
  return seg.trim().replace(/^(?:[A-Za-z_][A-Za-z0-9_]*=(?:'[^']*'|"[^"]*"|\S*)\s+)+/, '').replace(/\s+/g, ' ').trim();
}

function patternMatches(pattern: string, text: string): boolean {
  const p = pattern.trim().replace(/\s+/g, ' ');
  if (p.endsWith(':*')) {
    const prefix = p.slice(0, -2).trim();
    return text === prefix || text.startsWith(`${prefix} `);
  }
  if (p.includes('*')) {
    const re = new RegExp(`^${p.split('*').map((x) => x.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*')}$`, 's');
    return re.test(text);
  }
  return text === p;
}

/** Does a Claude Code permission rule match this shell call? Rules for other tools never do. */
export function shellRuleMatches(rule: string, toolName: string, input: Record<string, unknown>): boolean {
  if (!SHELL_TOOLS.has(toolName)) return false;
  const m = /^\s*(Bash|PowerShell)\s*(?:\((.*)\))?\s*$/s.exec(rule);
  if (!m) return false;
  if (m[2] === undefined) return true;
  const command = typeof input.command === 'string' ? input.command : '';
  return splitSegments(command).some((seg) => patternMatches(m[2]!, segmentText(seg)));
}

/** The first rule of `rules` that matches, if any. */
export function matchingShellRule(rules: string[], toolName: string, input: Record<string, unknown>): string | undefined {
  return rules.find((r) => shellRuleMatches(r, toolName, input));
}
