// The user's Claude Code permission rules (claude.permissions deny / ask) for engines that do not
// apply them themselves (Codex). The Claude CLI applies all three lists to Claude agents; for a
// Codex agent the Foreman's permission gate applies `deny` and `ask` to shell commands:
//
//   "Bash"                    every command
//   "Bash(tofu apply:*)"      any simple command that starts with these words
//   "Bash(npm run deploy)"    a simple command that is exactly this
//   "Bash(az deployment *)"   `*` matches anything
//
// A command is split into its simple commands (`a && b | c`, `;`, newlines, subshells and `$( )`)
// by the shared shell lexer, quotes removed (`to"fu" apply` is `tofu apply`); leading VAR=value
// assignments and wrappers (env, command, nohup, time, timeout, nice, exec, xargs...) are skipped,
// so `X=1 env tofu apply` and `ls; tofu apply` match. `allow` rules are not applied to Codex agents
// (they would skip AgentCraft's policy); rules for other tools (Edit(...), Read(...), WebFetch(...))
// do not apply to them either. Best effort, like every text check: a script that runs the command,
// or a command Codex runs without asking, is not seen.
import { shellItems } from '../../shell.js';

const SHELL_TOOLS = new Set(['Bash', 'PowerShell']);
const WRAPPERS = new Set(['env', 'command', 'builtin', 'exec', 'nice', 'nohup', 'time', 'timeout', 'stdbuf', 'xargs', 'sudo', 'doas']);

/** Each simple command of a shell line as plain words, without assignments and wrappers in front. */
function commandTexts(command: string): string[] {
  const out: string[] = [];
  for (const it of shellItems(command, process.platform !== 'win32')) {
    if (it.kind !== 'cmd') continue;
    const w = it.words;
    let i = 0;
    while (i < w.length) {
      if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(w[i]!)) i++;
      else if (WRAPPERS.has(w[i]!.toLowerCase())) {
        i++;
        while (i < w.length && (w[i]!.startsWith('-') || /^\d+(\.\d+)?[smhd]?$/.test(w[i]!))) i++;
      } else break;
    }
    if (i < w.length) out.push(w.slice(i).join(' '));
  }
  return out;
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
  return commandTexts(command).some((text) => patternMatches(m[2]!, text));
}

/** The first rule of `rules` that matches, if any. */
export function matchingShellRule(rules: string[], toolName: string, input: Record<string, unknown>): string | undefined {
  return rules.find((r) => shellRuleMatches(r, toolName, input));
}
