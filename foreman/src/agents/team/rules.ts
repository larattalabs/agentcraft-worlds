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
// so `X=1 env tofu apply` and `ls; tofu apply` match; `sh -c` / `bash -lc` strings are read as commands. `allow` rules are not applied to Codex agents
// (they would skip AgentCraft's policy); rules for other tools (Edit(...), Read(...), WebFetch(...))
// do not apply to them either. Best effort, like every text check: a script that runs the command,
// or a command Codex runs without asking, is not seen.
import { shellItems } from '../../shell.js';

const SHELL_TOOLS = new Set(['Bash', 'PowerShell']);
const SHELLS = new Set(['sh', 'bash', 'zsh', 'dash', 'ksh', 'fish']);
const WRAPPERS = new Set(['env', 'command', 'builtin', 'exec', 'nice', 'nohup', 'time', 'timeout', 'stdbuf', 'xargs', 'sudo', 'doas']);

/** Wrapper options that take the next word as their value (`env -u NAME`, `timeout -s TERM`). */
const OPTION_WITH_VALUE = /^(-[uCSsknIoe]|--(unset|chdir|split-string|signal|kill-after|adjustment|input|output|error|max-args|max-procs|replace|delimiter|arg-file))$/;

/**
 * `$( ... )` and backtick bodies anywhere in the text, also inside double quotes (not inside single
 * quotes, where they are literal). Quotes inside a body do not count as its parentheses. An unclosed
 * body runs to the end of the text.
 */
function substitutions(command: string): string[] {
  const out: string[] = [];
  let dq = false;
  for (let i = 0; i < command.length; i++) {
    const c = command[i]!;
    if (c === '"') {
      dq = !dq;
      continue;
    }
    if (c === "'" && !dq) {
      // a single-quoted run outside any substitution: literal text
      const end = command.indexOf("'", i + 1);
      if (end < 0) break;
      i = end;
      continue;
    }
    if (c === '\\') {
      i++;
      continue;
    }
    if (c === '$' && command[i + 1] === '(') {
      let depth = 0;
      let q: string | undefined;
      let j = i + 1;
      for (; j < command.length; j++) {
        const d = command[j]!;
        if (q) {
          if (d === q) q = undefined;
          else if (d === '\\' && q === '"') j++;
          continue;
        }
        if (d === "'" || d === '"') q = d;
        else if (d === '\\') j++;
        else if (d === '(') depth++;
        else if (d === ')' && --depth === 0) break;
      }
      out.push(command.slice(i + 2, j));
      i = j;
    } else if (c === '`') {
      const end = command.indexOf('`', i + 1);
      out.push(command.slice(i + 1, end < 0 ? undefined : end));
      if (end < 0) break;
      i = end;
    }
  }
  return out;
}

/** Each simple command of a shell line as plain words, without assignments and wrappers in front. */
function commandTexts(command: string, depth = 0): string[] {
  const out: string[] = [];
  for (const it of shellItems(command, process.platform !== 'win32')) {
    if (it.kind !== 'cmd') continue;
    const w = it.words;
    let i = 0;
    while (i < w.length) {
      if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(w[i]!)) i++;
      else if (WRAPPERS.has(w[i]!.toLowerCase())) {
        i++;
        while (i < w.length && (w[i]!.startsWith('-') || /^\d+(\.\d+)?[smhd]?$/.test(w[i]!))) {
          // env -S "cmd args": the value is a command line of its own
          if (/^(-S|--split-string)$/.test(w[i]!) && w[i + 1] !== undefined && depth < 4) out.push(...commandTexts(w[i + 1]!, depth + 1));
          const attached = /^(?:-S|--split-string=)(.+)$/.exec(w[i]!);
          if (attached && depth < 4) out.push(...commandTexts(attached[1]!, depth + 1));
          if (OPTION_WITH_VALUE.test(w[i]!)) i++;
          i++;
        }
      } else break;
    }
    if (i < w.length) out.push(w.slice(i).join(' '));
    // `sh -c 'cmd'`, `bash -lc "cmd"`, `/bin/sh -c ...`: the command string is a command line of its own
    if (i < w.length && SHELLS.has(w[i]!.split(/[\\/]/).pop()!.toLowerCase().replace(/\.exe$/, '')) && depth < 4) {
      for (let k = i + 1; k < w.length; k++) {
        if (/^-[A-Za-z]*c[A-Za-z]*$/.test(w[k]!) && w[k + 1] !== undefined) {
          out.push(...commandTexts(w[k + 1]!, depth + 1));
          break;
        }
        if (!w[k]!.startsWith('-')) break;
      }
    }
  }
  if (depth < 4) for (const body of substitutions(command)) out.push(...commandTexts(body, depth + 1));
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
  try {
    return commandTexts(command).some((text) => patternMatches(m[2]!, text));
  } catch {
    // a command too odd to parse: matched when the rule's words appear in it at all (fail closed)
    const words = m[2]!.replace(/:\*$/, '').replace(/\*/g, ' ').trim().split(/\s+/).filter(Boolean);
    return words.every((x) => command.includes(x));
  }
}

/** The first rule of `rules` that matches, if any. */
export function matchingShellRule(rules: string[], toolName: string, input: Record<string, unknown>): string | undefined {
  return rules.find((r) => shellRuleMatches(r, toolName, input));
}
