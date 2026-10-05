// Shell lexing shared by the permission policy (policy.ts: classifyBash) and the Foreman-private
// guard (policy.ts: foremanPrivateCommand). Pure string functions: no paths, no file system.
//
//   extractHeredocs        here-document bodies out, a placeholder glued to each `<<DELIM`
//   extractSubst           `$(...)`, backticks, `<(...)`/`>(...)` bodies out, a marker in their place
//   splitSegmentsWithOps   simple commands on ; && || | & ( ) and newlines, with pipe input
//   lex                    one simple command's words (quotes removed) and redirections
//   shellItems             simple commands with ( ) / $( ) subshell boundaries kept (the guard's
//                          virtual `cd` follows and undoes them)
//   commandWords           every word that could be a path (a coarse split, for the guard)

/** Marks substituted text inside a word: \x01S<n>\x01 = substitution n, \x01D\x01 = unknowable. */
export const MARK = '\u0001';
export const MARK_RE = /\u0001S(\d+)\u0001/g;
export const DYN = `${MARK}D${MARK}`;
/** heredoc placeholder left behind the `<<DELIM` word: \x02H<n>\x02 */
export const HD_RE = /\u0002H(\d+)\u0002/;

/**
 * Pull here-document bodies out of a command (`cat > f <<'EOF' ... EOF`), so their lines are not
 * mistaken for commands. Each `<<DELIM` word gets a placeholder (\x02H<n>\x02) glued to it, so the
 * body can be found again wherever the operator ends up (even inside a `$(...)`).
 */
export function extractHeredocs(cmd: string, bodies: Array<{ body: string; quoted: boolean }> = []): { text: string; bodies: Array<{ body: string; quoted: boolean }> } {
  const lines = cmd.split('\n');
  const out: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    const pending: Array<{ strip: boolean; delim: string; idx: number }> = [];
    const marked = line.replace(/(?<!<)<<(?!<)(-?)(\s*)(['"]?)([A-Za-z_][\w.-]*)\3(?![\w.-])(?!\u0002)/g, (m, dash: string, _sp: string, q: string, delim: string) => {
      const idx = bodies.length + pending.length;
      pending.push({ strip: dash === '-', delim, idx });
      bodies.push({ body: '', quoted: !!q });
      return `${m}\u0002H${idx}\u0002`;
    });
    out.push(marked);
    for (const p of pending) {
      const body: string[] = [];
      while (i + 1 < lines.length) {
        const next = lines[++i]!.replace(/\r$/, '');
        if ((p.strip ? next.replace(/^\t+/, '') : next) === p.delim) break;
        body.push(next);
      }
      bodies[p.idx]!.body = body.join('\n');
    }
  }
  return { text: out.join('\n'), bodies };
}

export interface Subst {
  body: string;
  kind: '$(' | '`' | '<(' | '>(';
  unbalanced?: boolean;
}

/** Index of the `)` matching the `(` at `open` (quote-aware), or -1. */
export function matchClose(s: string, open: number): number {
  let depth = 0;
  let quote: string | null = null;
  for (let i = open; i < s.length; i++) {
    const c = s[i]!;
    if (quote) {
      if (quote === '"' && c === '\\') i++;
      else if (c === quote) quote = null;
      continue;
    }
    if (c === '\\') {
      i++;
      continue;
    }
    if (c === "'" || c === '"') {
      quote = c;
      continue;
    }
    if (c === '`') {
      const j = s.indexOf('`', i + 1);
      if (j < 0) return -1;
      i = j;
      continue;
    }
    if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return i;
  }
  return -1;
}

export const markOf = (k: number) => `${MARK}S${k}${MARK}`;

/**
 * Replace every command substitution in `text` with a marker and collect the bodies:
 * `$(...)`, backticks (also inside double quotes), `<(...)` / `>(...)` (unquoted), and the
 * substitutions nested in `$((arithmetic))`. `quotes=false` for unquoted heredoc bodies, where
 * quote characters are literal.
 */
export function extractSubst(text: string, out: Subst[], quotes = true): string {
  let res = '';
  let q: '"' | "'" | null = null;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (q === "'") {
      res += c;
      if (c === "'") q = null;
      continue;
    }
    if (c === '\\' && i + 1 < text.length) {
      res += c + text[i + 1]!;
      i++;
      continue;
    }
    if (quotes && c === "'" && q === null) {
      q = "'";
      res += c;
      continue;
    }
    if (quotes && c === '"') {
      q = q === '"' ? null : '"';
      res += c;
      continue;
    }
    if (c === '$' && text[i + 1] === '(') {
      const end = matchClose(text, i + 1);
      if (text[i + 2] === '(' && end > 0 && text[end - 1] === ')') {
        // $(( arithmetic )): a number, but it may contain substitutions of its own
        const before = out.length;
        extractSubst(text.slice(i + 3, end - 1), out, true);
        res += '0';
        for (let k = before; k < out.length; k++) res += markOf(k);
        i = end;
        continue;
      }
      if (end < 0) {
        out.push({ body: text.slice(i + 2), kind: '$(', unbalanced: true });
        res += markOf(out.length - 1);
        return res;
      }
      out.push({ body: text.slice(i + 2, end), kind: '$(' });
      res += markOf(out.length - 1);
      i = end;
      continue;
    }
    if (c === '`') {
      let j = i + 1;
      let body = '';
      while (j < text.length && text[j] !== '`') {
        if (text[j] === '\\' && j + 1 < text.length) {
          body += /[`\\$]/.test(text[j + 1]!) ? text[j + 1]! : text[j]! + text[j + 1]!;
          j += 2;
          continue;
        }
        body += text[j]!;
        j++;
      }
      out.push({ body, kind: '`', ...(j >= text.length ? { unbalanced: true } : {}) });
      res += markOf(out.length - 1);
      i = j;
      continue;
    }
    if (q === null && quotes && (c === '<' || c === '>') && text[i + 1] === '(' && text[i - 1] !== c) {
      const end = matchClose(text, i + 1);
      out.push({ body: end < 0 ? text.slice(i + 2) : text.slice(i + 2, end), kind: c === '<' ? '<(' : '>(', ...(end < 0 ? { unbalanced: true } : {}) });
      res += markOf(out.length - 1);
      if (end < 0) return res;
      i = end;
      continue;
    }
    res += c;
  }
  return res;
}

export interface Segment {
  text: string;
  /** stdin comes from the previous segment through a pipe */
  pipeIn: boolean;
}

export function splitSegmentsWithOps(cmd: string): Segment[] {
  const out: Segment[] = [];
  let cur = '';
  let quote: string | null = null;
  let pipeIn = false;
  const flush = (nextPipe: boolean) => {
    if (cur.trim()) out.push({ text: cur.trim(), pipeIn });
    cur = '';
    pipeIn = nextPipe;
  };
  for (let i = 0; i < cmd.length; i++) {
    const c = cmd[i]!;
    if (quote) {
      cur += c;
      if (c === '\\' && quote === '"' && i + 1 < cmd.length) cur += cmd[++i]!;
      else if (c === quote) quote = null;
      continue;
    }
    if (c === '\\' && i + 1 < cmd.length) {
      if (cmd[i + 1] === '\n') {
        i++; // line continuation
        continue;
      }
      cur += c + cmd[++i]!;
      continue;
    }
    if (c === '"' || c === "'") {
      quote = c;
      cur += c;
      continue;
    }
    // redirections are not separators: 2>&1, >&2, &>file
    if (c === '&' && (cmd[i - 1] === '>' || cmd[i + 1] === '>')) {
      cur += c;
      continue;
    }
    if (c === '|') {
      if (cmd[i + 1] === '|') {
        i++;
        flush(false);
      } else {
        if (cmd[i + 1] === '&') i++; // |& pipes stderr too
        flush(true);
      }
      continue;
    }
    if (c === ';' || c === '\n' || c === '&' || c === '(' || c === ')') {
      if (c === '&' && cmd[i + 1] === '&') i++;
      flush(false);
      continue;
    }
    cur += c;
  }
  flush(false);
  return out;
}

/** Split a command line into simple segments on ; && || | & ( ) and newlines (quote-aware). */
export function splitSegments(cmd: string): string[] {
  return splitSegmentsWithOps(cmd).map((s) => s.text);
}

export interface Lexed {
  words: string[];
  /** file redirections: `>`/`>>` write the target, `<` reads it */
  redirects: Array<{ op: '>' | '<'; target: string }>;
  /** here-documents (`<<EOF`) this command reads: indices into extractHeredocs().bodies */
  heredocRefs?: number[];
  /** here-strings (`<<< word`) this command reads */
  herestrings?: string[];
}

export function closeQuote(s: string, i: number, q: string): number {
  for (let j = i + 1; j < s.length; j++) {
    if (q === '"' && s[j] === '\\') {
      j++;
      continue;
    }
    if (s[j] === q) return j;
  }
  return s.length;
}

/** Words of one simple command, with quotes removed and file redirections pulled out. */
export function lex(seg: string): Lexed {
  const words: string[] = [];
  const redirects: Lexed['redirects'] = [];
  const heredocRefs: number[] = [];
  const herestrings: string[] = [];
  let cur = '';
  let started = false;
  let quotedInWord = false;
  let pending: '>' | '<' | 'heredoc' | 'herestring' | null = null;
  const push = () => {
    if (!started) return;
    if (pending === 'heredoc') {
      const m = HD_RE.exec(cur);
      heredocRefs.push(m ? Number(m[1]) : -1);
    } else if (pending === 'herestring') herestrings.push(cur);
    else if (pending) redirects.push({ op: pending, target: cur });
    else words.push(cur);
    pending = null;
    cur = '';
    started = false;
    quotedInWord = false;
  };
  for (let i = 0; i < seg.length; i++) {
    const c = seg[i]!;
    if (c === '"' || c === "'") {
      const end = closeQuote(seg, i, c);
      const content = seg.slice(i + 1, end);
      cur += c === '"' ? content.replace(/\\(["\\$`])/g, '$1') : content;
      started = true;
      quotedInWord = true;
      i = end;
      continue;
    }
    if (c === '\\' && i + 1 < seg.length && /[\s"'\\;&|<>()]/.test(seg[i + 1]!)) {
      cur += seg[++i]!;
      started = true;
      continue;
    }
    if (/\s/.test(c)) {
      push();
      continue;
    }
    if ((c === '>' || c === '<') && !quotedInWord && (!started || /^(\d+|&)$/.test(cur))) {
      let j = i + 1;
      if (c === '<' && seg[j] === '<') {
        if (seg[j + 1] === '<') {
          cur = '';
          started = false;
          pending = 'herestring';
          i = j + 1;
          continue;
        }
        while (seg[j] === '<' || seg[j] === '-') j++;
        cur = '';
        started = false;
        pending = 'heredoc';
        i = j - 1;
        continue;
      }
      if (c === '>' && (seg[j] === '>' || seg[j] === '|')) j++;
      if (seg[j] === '&') {
        // >&2, 2>&1, >&- duplicate a descriptor; >&file (bash) writes the file
        let k = j + 1;
        while (k < seg.length && !/\s/.test(seg[k]!)) k++;
        const word = seg.slice(j + 1, k);
        cur = '';
        started = false;
        if (/^(\d+|-)?$/.test(word)) {
          i = k - 1;
          continue;
        }
        pending = c === '>' ? '>' : '<';
        i = j;
        continue;
      }
      cur = '';
      started = false;
      pending = c === '>' ? '>' : '<';
      i = j - 1;
      continue;
    }
    if ((c === '>' || c === '<') && started && !quotedInWord) {
      // `a>b`: the word ends, the redirection starts
      push();
      i--;
      continue;
    }
    cur += c;
    started = true;
  }
  push();
  return { words, redirects, ...(heredocRefs.length ? { heredocRefs } : {}), ...(herestrings.length ? { herestrings } : {}) };
}

export function unescapeWord(w: string): string {
  return w.replace(/\\(.)/g, '$1');
}

/** Words of a command that could be paths (split on whitespace, quotes and shell punctuation). */
export function commandWords(command: string): string[] {
  return command.split(/[\s;|&<>()'"`=,]+/).filter((w) => w && w !== '-' && !/^-+[A-Za-z]/.test(w));
}

/** A shell command split into simple commands, with ( ) / $( ) subshell boundaries kept. */
export type ShellItem = { kind: 'open' } | { kind: 'close' } | { kind: 'cmd'; words: string[]; redirects: string[] };

export function shellItems(command: string, posix: boolean): ShellItem[] {
  const out: ShellItem[] = [];
  let text: string;
  try {
    text = extractHeredocs(command).text;
  } catch {
    text = command;
  }
  const clean = (w: string) => (posix ? unescapeWord(w) : w);
  const flush = (seg: string) => {
    if (!seg.trim()) return;
    try {
      const l = lex(seg);
      const words = l.words.map(clean).filter((w) => w.length);
      const redirects = l.redirects.map((r) => clean(r.target)).filter(Boolean);
      if (words.length || redirects.length) out.push({ kind: 'cmd', words, redirects });
    } catch {
      /* unparsable piece: the raw text checks still run */
    }
  };
  let cur = '';
  let q: string | undefined;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (q) {
      cur += c;
      if (c === '\\' && q === '"' && posix && i + 1 < text.length) cur += text[++i];
      else if (c === q) q = undefined;
      continue;
    }
    if (c === '\\' && posix && i + 1 < text.length) {
      cur += c + text[++i];
      continue;
    }
    if (c === "'" || c === '"') {
      q = c;
      cur += c;
      continue;
    }
    if (c === '(' || c === ')') {
      // `$(` opens a substitution: a subshell too
      if (c === '(' && cur.endsWith('$')) cur = cur.slice(0, -1);
      flush(cur);
      cur = '';
      out.push({ kind: c === '(' ? 'open' : 'close' });
      continue;
    }
    if (c === ';' || c === '\n' || c === '&' || c === '|') {
      flush(cur);
      cur = '';
      continue;
    }
    cur += c;
  }
  flush(cur);
  return out;
}
