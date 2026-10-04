// Which PR threads are automated reviews: repoSettings.prReview.bots (default DEFAULT_REVIEW_BOTS),
// classify() with them, and the config validation.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { DEFAULT_REVIEW_BOTS, parseReviewBots } from '../src/prreview.js';
import type { HostPr, HostThread } from '../src/prs.js';
import { classify } from '../src/prwatch.js';

const REVIEW = '**Claude Code Review**\nReview completed\n\n### 🎯 Verdict\n**PASS**\n';
const thread = (id: number, author: string, text: string): HostThread => ({ id: String(id), active: true, status: 'active', comments: [{ id: 1, author, text, system: false, at: id }] });
const pr = (threads: HostThread[]): HostPr => ({ status: 'open', draft: false, checks: 'none', failing: [], threads }) as HostPr;

const threads = [
  thread(1, 'Project Collection Build Service (contoso)', REVIEW),
  thread(2, 'Project Collection Build Service (contoso)', 'Build 42 succeeded'),
  thread(3, 'Changelog Bot', '<!-- changelog-draft -->\n## Changelog'),
  thread(4, 'Dana Reviewer', 'why this?'),
  thread(5, 'review-bot[bot]', '## AI Review\n### 🎯 Verdict\n**FAIL**\n'),
];

describe('automated reviewers (prReview.bots)', () => {
  it('the defaults: a "Claude Code Review" from anyone is a review; build-service and changelog threads are ignored', () => {
    const c = classify(pr(threads), {}, new Set());
    expect(c.review?.thread.id).toBe('1');
    expect(c.review?.parsed.verdict).toBe('PASS');
    expect(c.human.map((h) => h.thread.id)).toEqual(['4', '5']);
    expect(c.ignored).toBe(2);
    expect(classify(pr(threads), {}, new Set(), DEFAULT_REVIEW_BOTS)).toEqual(c);
  });

  it('a configured list replaces the defaults; [] recognises nothing', () => {
    const bots = parseReviewBots([{ name: 'ai', author: '\\[bot\\]$', marker: '## AI Review' }], 'bots');
    const c = classify(pr(threads), {}, new Set(), bots);
    expect(c.review?.thread.id).toBe('5');
    expect(c.review?.parsed.verdict).toBe('FAIL');
    // without the defaults the build service and the changelog are ordinary threads
    expect(c.human.map((h) => h.thread.id)).toEqual(['1', '2', '3', '4']);
    // the author must match
    expect(classify(pr([thread(6, 'Dana Reviewer', '## AI Review\n')]), {}, new Set(), bots).review).toBeUndefined();
    const none = classify(pr(threads), {}, new Set(), []);
    expect(none.review).toBeUndefined();
    expect(none.human).toHaveLength(5);
  });

  it('validates the configured list with the offending path', () => {
    expect(() => parseReviewBots({}, 'b')).toThrow(/^b: expected a list/);
    expect(() => parseReviewBots([{ author: 'x' }], 'b')).toThrow(/^b\[0\]\.marker: expected a non-empty string/);
    expect(() => parseReviewBots([{ marker: 'm' }, { marker: 'm', author: '([' }], 'b')).toThrow(/^b\[1\]\.author: not a valid regular expression/);
    expect(() => parseReviewBots([{ marker: 'm', ignoreMarkers: ['ok', ''] }], 'b')).toThrow(/^b\[0\]\.ignoreMarkers/);
    expect(() => parseReviewBots([{ marker: 'm', extra: 1 }], 'b')).toThrow(/^b\[0\]\.extra: unknown key/);
    expect(parseReviewBots([{ name: 'n', author: 'a', marker: 'm', ignoreMarkers: ['i'] }], 'b')).toEqual([{ name: 'n', author: 'a', marker: 'm', ignoreMarkers: ['i'] }]);
  });

  it('config.json: repoSettings.<repo>.prReview.bots is read and validated', () => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'ac-bots-'));
    try {
      const write = (bots: unknown) => fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ repoSettings: { '/x/api': { prReview: { maxRounds: 1, bots } } } }));
      write([{ marker: '## AI Review', author: 'bot' }]);
      expect(loadConfig(['--home', home], {}).repoSettings['/x/api']!.prReview).toEqual({ maxRounds: 1, bots: [{ marker: '## AI Review', author: 'bot' }] });
      write([]);
      expect(loadConfig(['--home', home], {}).repoSettings['/x/api']!.prReview!.bots).toEqual([]);
      write([{ marker: 'm', author: '(' }]);
      expect(() => loadConfig(['--home', home], {})).toThrow('repoSettings[/x/api].prReview.bots[0].author: not a valid regular expression');
    } finally {
      fs.rmSync(home, { recursive: true, force: true });
    }
  });
});
