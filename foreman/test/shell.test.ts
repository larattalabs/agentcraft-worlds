// The shared shell lexer (src/shell.ts): one module for the permission policy and the
// Foreman-private guard. The policy's own tests (policy.test.ts, security-review.test.ts,
// foreman-private.test.ts) cover the behaviour; these pin the module boundary.
import { describe, expect, it } from 'vitest';
import * as policy from '../src/policy.js';
import { commandWords, extractHeredocs, lex, shellItems, splitSegments, splitSegmentsWithOps } from '../src/shell.js';

describe('shell lexer module', () => {
  it('is the one policy.ts exports (no second copy)', () => {
    expect(policy.lex).toBe(lex);
    expect(policy.splitSegments).toBe(splitSegments);
    expect(policy.extractHeredocs).toBe(extractHeredocs);
  });

  it('splits simple commands with pipe input, and lexes words and redirections', () => {
    expect(splitSegmentsWithOps('a | b && c; (d)')).toEqual([
      { text: 'a', pipeIn: false },
      { text: 'b', pipeIn: true },
      { text: 'c', pipeIn: false },
      { text: 'd', pipeIn: false },
    ]);
    expect(lex(`cat "a b" 2>&1 > 'out file' < in`)).toEqual({ words: ['cat', 'a b'], redirects: [{ op: '>', target: 'out file' }, { op: '<', target: 'in' }] });
  });

  it('keeps subshell boundaries for the guard and undoes shell quoting', () => {
    expect(shellItems(`cd x && (cd ~/.agent"craft" ; cat $(echo y))`, true)).toEqual([
      { kind: 'cmd', words: ['cd', 'x'], redirects: [] },
      { kind: 'open' },
      { kind: 'cmd', words: ['cd', '~/.agentcraft'], redirects: [] },
      { kind: 'cmd', words: ['cat'], redirects: [] },
      { kind: 'open' },
      { kind: 'cmd', words: ['echo', 'y'], redirects: [] },
      { kind: 'close' },
      { kind: 'close' },
    ]);
    expect(commandWords(`grep -r "x" ~/notes --include=*.md`)).toEqual(['grep', 'x', '~/notes', '*.md']);
  });
});
