// Pure helpers for editing a Prism Launcher instance.cfg (tools/hardcore-setup.mjs).
//
// instance.cfg is a QSettings INI file. Values are escaped the way QSettings writes them
// (qsettings.cpp iniEscapedString): `\` and `"` get a backslash, control characters become \n, \t,
// \xHH, and a value containing `;`, `,` or `=` (or with a leading/trailing space) is wrapped in double
// quotes; an unquoted `,` would make the value a list. Only the keys we set are rewritten; every other
// line is kept byte for byte.
//
// Prism 11.0.3 runs PreLaunchCommand / PostExitCommand by substituting $INST_* variables into the
// string and splitting it with QProcess::splitCommand (double quotes only, `"""` = a literal quote,
// no backslash escapes, no single quotes, no shell). It waits for the command to exit; a non-zero
// exit code (or a crash) aborts the launch with "Pre-Launch command failed with code N"
// (launcher/launch/steps/PreLaunchCommand.cpp at tag 11.0.3).

/** QSettings INI value escaping (iniEscapedString). */
export function iniEscape(str) {
  let out = '';
  let needsQuotes = false;
  for (const ch of String(str)) {
    if (ch === ';' || ch === ',' || ch === '=') needsQuotes = true;
    switch (ch) {
      case '"': case '\\': out += '\\' + ch; break;
      case '\n': out += '\\n'; break;
      case '\r': out += '\\r'; break;
      case '\t': out += '\\t'; break;
      default: {
        const code = ch.codePointAt(0);
        out += code <= 0x1f ? `\\x${code.toString(16).toUpperCase()}` : ch;
      }
    }
  }
  if (needsQuotes || out.startsWith(' ') || out.endsWith(' ')) out = `"${out}"`;
  return out;
}

/**
 * QSettings INI value unescaping (iniUnescapedStringList) for a single string value. Throws when the
 * value is a list (an unquoted comma), which none of the keys we edit should be.
 */
export function iniUnescape(raw) {
  const s = String(raw);
  const parts = [];
  let cur = '';
  let inQuotes = false;
  let pendingSpace = '';
  let started = false;
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === '\\' && i + 1 < s.length) {
      const n = s[++i];
      const map = { a: '\x07', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t', v: '\v', '"': '"', "'": "'", '?': '?', '\\': '\\' };
      cur += pendingSpace; pendingSpace = ''; started = true;
      if (n in map) cur += map[n];
      else if (n === 'x') {
        let hex = '';
        while (i + 1 < s.length && /[0-9a-fA-F]/.test(s[i + 1])) hex += s[++i];
        cur += String.fromCodePoint(parseInt(hex || '0', 16));
      } else if (/[0-7]/.test(n)) {
        let oct = n;
        while (i + 1 < s.length && /[0-7]/.test(s[i + 1]) && oct.length < 3) oct += s[++i];
        cur += String.fromCodePoint(parseInt(oct, 8));
      } else cur += n;
    } else if (ch === '"') {
      cur += pendingSpace; pendingSpace = ''; started = true;
      inQuotes = !inQuotes;
    } else if (inQuotes) {
      cur += ch;
    } else if (ch === ',') {
      parts.push(cur); cur = ''; pendingSpace = ''; started = false;
    } else if (ch === ' ' || ch === '\t') {
      if (started) pendingSpace += ch;
    } else {
      cur += pendingSpace + ch; pendingSpace = ''; started = true;
    }
  }
  parts.push(cur);
  if (parts.length > 1) throw new Error(`value is a list, not a string: ${raw}`);
  return parts[0];
}

function lines(text) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  return { eol, rows: text.split(eol) };
}

/** Values of the [General] section, unescaped: { key: string } (list values are skipped). */
export function readGeneral(text) {
  const { rows } = lines(text);
  const out = {};
  let section = '';
  for (const row of rows) {
    const sec = /^\s*\[(.*)\]\s*$/.exec(row);
    if (sec) { section = sec[1]; continue; }
    if (section !== 'General') continue;
    const eq = row.indexOf('=');
    if (eq < 1) continue;
    try { out[row.slice(0, eq).trim()] = iniUnescape(row.slice(eq + 1)); } catch { /* list value */ }
  }
  return out;
}

/**
 * Set `updates` ({ key: string }) in [General]. Existing lines are replaced in place, new keys are
 * appended at the end of the section; every other line is untouched. Returns the new text.
 */
export function setGeneral(text, updates) {
  const { eol, rows } = lines(text);
  const pending = new Map(Object.entries(updates));
  let section = '';
  let generalEnd = -1;
  for (let i = 0; i < rows.length; i++) {
    const sec = /^\s*\[(.*)\]\s*$/.exec(rows[i]);
    if (sec) {
      if (section === 'General' && generalEnd < 0) generalEnd = i;
      section = sec[1];
      continue;
    }
    if (section !== 'General') continue;
    const eq = rows[i].indexOf('=');
    if (eq < 1) continue;
    const key = rows[i].slice(0, eq).trim();
    if (!pending.has(key)) continue;
    const value = iniEscape(pending.get(key));
    if (rows[i].slice(eq + 1) !== value) rows[i] = `${key}=${value}`;
    pending.delete(key);
  }
  if (pending.size) {
    if (!rows.some((r) => /^\s*\[General\]\s*$/.test(r))) {
      rows.unshift('[General]');
      generalEnd = 1;
    } else if (generalEnd < 0) {
      generalEnd = rows.length;
      while (generalEnd > 0 && rows[generalEnd - 1] === '') generalEnd--;
    } else {
      while (generalEnd > 0 && rows[generalEnd - 1] === '') generalEnd--;
    }
    rows.splice(generalEnd, 0, ...[...pending].map(([k, v]) => `${k}=${iniEscape(v)}`));
  }
  return rows.join(eol);
}

/** QProcess::splitCommand (Qt 6). */
export function splitCommand(command) {
  const args = [];
  let tmp = '';
  let quoteCount = 0;
  let inQuote = false;
  let has = false;
  for (const ch of String(command)) {
    if (ch === '"') {
      quoteCount++;
      if (quoteCount === 3) { quoteCount = 0; tmp += ch; }
      has = true;
      continue;
    }
    if (quoteCount) {
      if (quoteCount === 1) inQuote = !inQuote;
      quoteCount = 0;
    }
    if (!inQuote && /\s/.test(ch)) {
      if (tmp) { args.push(tmp); tmp = ''; }
      has = false;
    } else tmp += ch;
  }
  if (tmp || has) { if (tmp) args.push(tmp); }
  return args;
}

/** Quote one argument for QProcess::splitCommand (always quoted when it has a space or a quote). */
export function quoteArg(arg) {
  const s = String(arg);
  if (s && !/[\s"]/.test(s)) return s;
  return `"${s.replace(/"/g, '"""')}"`;
}

/** A path, always double-quoted (the style Prism's own settings use). */
export const quotePath = (p) => `"${String(p).replace(/"/g, '"""')}"`;

export const DAEMON_SCRIPT = 'foreman-daemon.sh';
const isDaemon = (arg) => typeof arg === 'string' && (arg === DAEMON_SCRIPT || arg.endsWith(`/${DAEMON_SCRIPT}`));

/** The PreLaunchCommand that starts the Foreman (returns at once; never fails the launch). */
export function preLaunchCommand({ daemon, profile, port, home }) {
  return [quotePath(daemon), 'start', '--profile', quoteArg(profile), '--port', String(port), '--home', quotePath(home)].join(' ');
}

/** Is `cmd` a PreLaunchCommand we wrote (any checkout)? */
export function isOurPreLaunch(cmd) {
  const args = splitCommand(cmd ?? '');
  return isDaemon(args[0]) && args[1] === 'start';
}

/** Is `cmd` a PostExitCommand wrapped by wrapPostExit? */
export function isWrappedPostExit(cmd) {
  const args = splitCommand(cmd ?? '');
  return isDaemon(args[0]) && args[1] === 'after-exit';
}

/** The original PostExitCommand inside a wrapped one (the text after the first " -- "), else cmd itself. */
export function unwrapPostExit(cmd) {
  const s = cmd ?? '';
  if (!isWrappedPostExit(s)) return s;
  const i = s.indexOf(' -- ');
  if (i >= 0) return s.slice(i + 4);
  return '';
}

/**
 * PostExitCommand that runs `original` (the backup) first, exactly as before, then stops the
 * Foreman. Wrapping an already wrapped command replaces the wrapper (idempotent).
 */
export function wrapPostExit(original, { daemon, profile, home }) {
  const inner = unwrapPostExit(original).trim();
  const prefix = [quotePath(daemon), 'after-exit', '--profile', quoteArg(profile), '--home', quotePath(home), '--'].join(' ');
  return inner ? `${prefix} ${inner}` : prefix;
}

/** JVM properties the setup manages (ClientEnv.raw maps AGENTCRAFT_X_Y to -Dagentcraft.x.y). */
export const MANAGED_PROPS = ['agentcraft.port', 'agentcraft.profile', 'agentcraft.home', 'agentcraft.dev', 'agentcraft.dev.port', 'agentcraft.foreman'];

/**
 * Replace every managed -Dagentcraft.* property in `existing` JvmArgs with `props` ({ name: value };
 * null/undefined = drop it). Other arguments keep their text and order.
 */
export function mergeJvmArgs(existing, props) {
  const names = MANAGED_PROPS.map((n) => n.replace(/\./g, '\\.')).join('|');
  const stripped = String(existing ?? '').replace(new RegExp(`(^|\\s)-D(?:${names})=\\S*`, 'g'), '').replace(/\s{2,}/g, ' ').trim();
  const add = Object.entries(props)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => {
      if (!MANAGED_PROPS.includes(k)) throw new Error(`not a managed property: ${k}`);
      if (/\s/.test(String(v))) throw new Error(`-D${k} cannot contain spaces: ${v}`);
      return `-D${k}=${v}`;
    });
  return [stripped, ...add].filter(Boolean).join(' ');
}
