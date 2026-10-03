// Automated PR reviews posted as comment threads. Today: a "Claude Code Review" pipeline
// (a review pipeline): one PR-level thread per review run, posted
// by the build service identity, its body rendered by render-review.sh:
//
//   **Claude Code Review**
//   Review completed at <ts> UTC
//
//   <details open>
//   <summary><b>📝 Code Review</b> (click to expand)</summary>
//
//   **<purpose>**
//
//   ### 📊 Summary
//   - ...
//   ### 🔍 Critical Findings
//   - **`src/a.ts:12-20`** ⚠️ — <description>
//
//   ```
//   <code snippet, unindented>
//   ```
//
//     **Fix:** <fix>
//   ### ⚠️ Important Suggestions        (empty: "*No important suggestions.*")
//   ### 💡 Minor Improvements
//   ### 📚 Teachable Moments            (optional)
//   ### 🔄 Refactoring Opportunities    (optional; counted as minor)
//   ### ✅ Testing Recommendations
//   ### 📈 Performance Notes            (optional)
//   ### 🎯 Verdict
//   **PASS|WARN|FAIL**
//
//   *<explanation>*
//   </details>
//
// A context-aware run has two <details> blocks, "📝 Initial Review" (collapsed) and "✅ Final
// Context-Aware Review" (open): only the final one counts. Headings are matched by keyword, so emoji
// variants do not matter.

export type FindingSeverity = 'critical' | 'important' | 'minor' | 'testing' | 'performance' | 'teachable';

export interface ReviewFinding {
  severity: FindingSeverity;
  file?: string;
  line?: number;
  /** the finding as one plain line (description, fix/impact), markdown removed */
  text: string;
  /** the reviewer's own confidence badge (⚠️ medium, ❓ low); absent = high */
  confidence?: 'medium' | 'low';
}

export interface ParsedReview {
  verdict?: 'PASS' | 'WARN' | 'FAIL';
  explanation?: string;
  findings: ReviewFinding[];
}

export const REVIEW_MARKER = '**Claude Code Review**';
export const CHANGELOG_MARKER = '<!-- changelog-draft -->';
/** Azure DevOps' standard build identity ("Project Collection Build Service (<org>)") */
export const BUILD_SERVICE = /^Project Collection Build Service\b/i;

/** Is this comment body an automated Claude Code Review? */
export function isAutomatedReview(text: string): boolean {
  return text.trimStart().startsWith(REVIEW_MARKER);
}

/** Order of severities, most severe first. */
export const SEVERITIES: FindingSeverity[] = ['critical', 'important', 'testing', 'performance', 'minor', 'teachable'];

function sectionOf(heading: string): FindingSeverity | 'verdict' | undefined {
  const h = heading.toLowerCase();
  if (/critical/.test(h)) return 'critical';
  if (/important/.test(h)) return 'important';
  if (/minor|refactoring/.test(h)) return 'minor';
  if (/teachable/.test(h)) return 'teachable';
  if (/testing/.test(h)) return 'testing';
  if (/performance/.test(h)) return 'performance';
  if (/verdict/.test(h)) return 'verdict';
  return undefined; // summary, full-stack validation, corrections: not findings
}

/** The review's own content: the final context-aware block when there is one. */
function reviewBlock(body: string): string {
  const blocks = body.split(/<details\b[^>]*>/i).slice(1);
  if (!blocks.length) return body;
  const final = blocks.find((b) => /<summary>[\s\S]*?final[\s\S]*?<\/summary>/i.test(b));
  const chosen = final ?? blocks.find((b) => !/<summary>[\s\S]*?initial review[\s\S]*?<\/summary>/i.test(b)) ?? blocks[blocks.length - 1]!;
  return chosen.split(/<\/details>/i)[0]!;
}

const unescape = (s: string) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

function plain(s: string): string {
  return unescape(
    s
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/(^|\s)\*([^*\n]+)\*/g, '$1$2')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/\s+/g, ' ')
      .trim(),
  );
}

/** One bullet (its first line plus continuation lines) -> a finding. */
function finding(severity: FindingSeverity, lines: string[]): ReviewFinding {
  const first = lines[0]!.replace(/^-\s+/, '');
  const f: ReviewFinding = { severity, text: '' };
  // - **`src/a.ts:12-20`** ⚠️ — description   |   - **`src/a.ts`** — description
  const m = /^\*\*`([^`]+)`\*\*\s*(⚠️|⚠|❓)?\s*(?:—|-{1,2}|:)?\s*/.exec(first);
  let rest = first;
  if (m) {
    const loc = /^(.+?)(?::(\d+)(?:-\d+)?)?$/.exec(m[1]!.trim())!;
    f.file = loc[1]!;
    if (loc[2]) f.line = Number(loc[2]);
    if (m[2]) f.confidence = m[2] === '❓' ? 'low' : 'medium';
    rest = first.slice(m[0].length);
  } else {
    // a plain file:line somewhere in the text
    const loc = /(?:^|[\s(`])((?:[\w.-]+\/)*[\w.-]+\.[a-z0-9]{1,6}):(\d+)/i.exec(first);
    if (loc) {
      f.file = loc[1]!;
      f.line = Number(loc[2]);
    }
  }
  f.text = plain([rest, ...lines.slice(1)].join('\n'));
  if (f.text.length > 700) f.text = `${f.text.slice(0, 697)}...`;
  return f;
}

/** Parse a Claude Code Review comment. Returns undefined when the text is not one. */
export function parseAutomatedReview(text: string): ParsedReview | undefined {
  if (!isAutomatedReview(text)) return undefined;
  const out: ParsedReview = { findings: [] };
  const lines = reviewBlock(text.replace(/\r\n/g, '\n')).split('\n');
  let section: FindingSeverity | 'verdict' | undefined;
  let bullet: string[] | undefined;
  let fence = false;
  const flush = () => {
    if (bullet && section && section !== 'verdict') out.findings.push(finding(section, bullet));
    bullet = undefined;
  };
  for (const raw of lines) {
    const line = raw.replace(/\s+$/, '');
    if (/^\s*```/.test(line)) {
      fence = !fence;
      bullet?.push(line);
      continue;
    }
    if (fence) {
      bullet?.push(line);
      continue;
    }
    const h = /^#{2,4}\s+(.*)$/.exec(line);
    if (h || /^---\s*$/.test(line) || /^<\/?(details|summary)/i.test(line)) {
      flush();
      if (h) section = sectionOf(h[1]!);
      else if (/^---/.test(line)) section = undefined;
      continue;
    }
    if (section === 'verdict') {
      const v = /\*\*(PASS|WARN|FAIL)\*\*/i.exec(line);
      if (v && !out.verdict) out.verdict = v[1]!.toUpperCase() as ParsedReview['verdict'];
      else if (out.verdict && !out.explanation && /^\s*\*[^*].*\*\s*$/.test(line)) out.explanation = plain(line);
      continue;
    }
    if (/^-\s+/.test(line)) {
      flush();
      bullet = [line];
      continue;
    }
    if (bullet) bullet.push(line);
  }
  flush();
  return out;
}

/** "2 critical, 1 important, 3 minor" (most severe first; empty string when there are none). */
export function findingCounts(findings: ReviewFinding[]): string {
  return SEVERITIES.map((s) => [s, findings.filter((f) => f.severity === s).length] as const)
    .filter(([, n]) => n > 0)
    .map(([s, n]) => `${n} ${s}`)
    .join(', ');
}
