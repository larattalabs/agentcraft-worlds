# Foreman: don't pass API keys to agents under --use-claude-login

Branch: `upstream/claude-login-drops-api-key` (one commit on `upstream/main`)

## Problem

`--use-claude-login` is meant to run the agents on the user's own `claude` CLI login. But
`withAuthMode` returns the environment unchanged in that mode. If `ANTHROPIC_API_KEY` (or
`ANTHROPIC_AUTH_TOKEN`) is still set in the shell that starts the Foreman, which is common for people
who also use the API, every agent CLI gets the key and authenticates with it. The result:

- usage is billed to the API key instead of the login the user chose;
- the start-up check (`checkAuth`) runs with the same environment, so it checks the key rather than
  the login.

API mode already handles the mirror case: it drops `CLAUDE_CODE_OAUTH_TOKEN`, so a login token never
overrides the key.

## Fix

`withAuthMode(env, useClaudeLogin)` now removes the variables of the mode the user did not pick:

- API mode (default): drops `CLAUDE_CODE_OAUTH_TOKEN`, as before;
- `--use-claude-login`: drops `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` (new export `API_KEY_VARS`).

Names match in any letter case. Values are never logged. Provider switches
(`CLAUDE_CODE_USE_BEDROCK`, ...), `ANTHROPIC_BASE_URL` and the rest of the environment pass through
unchanged. Every agent query and the auth check already build their environment through
`ClaudeBackend.env()` → `withAuthMode`, so `auth.ts` is the only source file that changes.

`claude-auth.test.ts` asserted that the login mode leaves the environment untouched. The test now
expects the key to be removed. A second test covers mixed-case names and checks that unrelated
variables are kept.

## How tested

- `cd foreman && npm ci && npm run check` passes on Node 22 (22 files, 483 tests).
- On Node 24 the only failure is `RepoManager > runs the repo test command`. It also fails on
  `upstream/main`, and the `upstream/node-test-spec-output` PR fixes it.
