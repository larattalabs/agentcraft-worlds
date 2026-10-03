// A small line-based unified diff (LCS), for short texts such as plan notes (goal.plan sends the
// lead what the user changed). Not a general diff tool: inputs beyond MAX_CELLS of LCS table are
// shown as one hunk replacing everything.

const MAX_CELLS = 4_000_000;

type Op = { kind: ' ' | '-' | '+'; text: string; a: number; b: number };

function splitLines(s: string): string[] {
  if (s === '') return [];
  return s.replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n');
}

function ops(a: string[], b: string[]): Op[] {
  const n = a.length;
  const m = b.length;
  if (n * m > MAX_CELLS) {
    return [...a.map((text, i) => ({ kind: '-' as const, text, a: i, b: 0 })), ...b.map((text, j) => ({ kind: '+' as const, text, a: n, b: j }))];
  }
  // lcs[i][j] = LCS length of a[i..] and b[j..]
  const lcs: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) lcs[i]![j] = a[i] === b[j] ? lcs[i + 1]![j + 1]! + 1 : Math.max(lcs[i + 1]![j]!, lcs[i]![j + 1]!);
  }
  const out: Op[] = [];
  let i = 0;
  let j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && a[i] === b[j]) {
      out.push({ kind: ' ', text: a[i]!, a: i, b: j });
      i++;
      j++;
    } else if (i < n && (j >= m || lcs[i + 1]![j]! >= lcs[i]![j + 1]!)) {
      // deletions before additions, as diff -u shows them
      out.push({ kind: '-', text: a[i]!, a: i, b: j });
      i++;
    } else {
      out.push({ kind: '+', text: b[j]!, a: i, b: j });
      j++;
    }
  }
  return out;
}

/**
 * Unified diff of `before` -> `after` with `context` lines around each change (default 3).
 * Returns '' when the texts have the same lines.
 */
export function unifiedDiff(before: string, after: string, opts: { from?: string; to?: string; context?: number } = {}): string {
  const a = splitLines(before);
  const b = splitLines(after);
  const all = ops(a, b);
  const ctx = opts.context ?? 3;
  const changed = all.map((o, k) => (o.kind !== ' ' ? k : -1)).filter((k) => k >= 0);
  if (!changed.length) return '';
  // group changes whose context overlaps into hunks: [start, end) over `all`
  const ranges: Array<[number, number]> = [];
  for (const k of changed) {
    const s = Math.max(0, k - ctx);
    const e = Math.min(all.length, k + ctx + 1);
    const last = ranges[ranges.length - 1];
    if (last && s <= last[1]) last[1] = Math.max(last[1], e);
    else ranges.push([s, e]);
  }
  const lines = [`--- ${opts.from ?? 'before'}`, `+++ ${opts.to ?? 'after'}`];
  for (const [s, e] of ranges) {
    const part = all.slice(s, e);
    const oldLines = part.filter((o) => o.kind !== '+').length;
    const newLines = part.filter((o) => o.kind !== '-').length;
    // the line numbers where the hunk starts (1-based; 0 for an empty side, as diff -u does)
    const first = part[0]!;
    const oldStart = oldLines ? first.a + 1 : first.a;
    const newStart = newLines ? first.b + 1 : first.b;
    lines.push(`@@ -${oldStart},${oldLines} +${newStart},${newLines} @@`);
    for (const o of part) lines.push(`${o.kind}${o.text}`);
  }
  return lines.join('\n');
}
