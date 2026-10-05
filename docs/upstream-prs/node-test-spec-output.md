# Foreman: parse the node --test spec reporter's output

Branch: `upstream/node-test-spec-output` (one commit on `upstream/main`)

## Problem

On newer Node versions (Node 24 here) `node --test` uses the spec reporter by default instead of TAP.
The summary lines then look like `ℹ tests 2` / `ℹ pass 1`, and failures look like `✖ name (0.1ms)`.
`parseTestOutput` in `foreman/src/repos.ts` only reads `# tests N`, so it found no summary. It also
kept the duration in failure names and counted the `✖ failing tests:` header as a failure.

That breaks the Foreman's own suite on Node 24. On a clean `upstream/main`, `npm run check` in
`foreman/` fails one test:

```
FAIL  test/repos.test.ts > RepoManager > runs the repo test command and reports failures
TypeError: .toMatch() expects to receive a string, but got undefined
  expect(res.summary).toMatch(/pass \d+/);
```

Workers whose repos use `node --test` also lose the pass/fail summary in their task results.

## Fix

- The summary regex accepts both `# ` (TAP) and `ℹ ` (spec) prefixes.
- Trailing `(1.2ms)` / `(3s)` durations are stripped from failure names.
- The `failing tests:` header line is skipped.

TAP parsing is unchanged. The change adds two unit tests for `parseTestOutput`, one with TAP output
and one with spec output.

## How tested

- `cd foreman && npm ci && npm run check` (tsc, vitest, protocol-doc check):
  - Node 24.16: 22 files, 484 tests pass. On `upstream/main` the same run gives 481 passed and 1 failed.
  - Node 22: also passes.
