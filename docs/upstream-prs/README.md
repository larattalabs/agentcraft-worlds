# Candidate upstream PRs

These are small fixes from this fork that also apply to upstream
([blendi-remade/agentcraft](https://github.com/blendi-remade/agentcraft)). They are prepared as
**local branches only**: nothing has been pushed or opened.

Each branch:

- is cut from `upstream/main` at `0be815d`;
- has one commit, with no attribution trailers;
- is adapted to upstream's code (mod id `agentcraft`, upstream file layout).

The PR text (title and body) for each branch is in the file named after its slug. These texts are on
the fork only; they are not on the `upstream/*` branches.

## Branches

| Branch | Commit | Area | Checks on the branch |
|---|---|---|---|
| `upstream/node-test-spec-output` | `4201434` Foreman: parse the node --test spec reporter's output | Foreman | `npm run check` passes on Node 24.16 (484 tests) and Node 22. Fixes the one test that fails on upstream under Node 24 |
| `upstream/claude-login-drops-api-key` | `5edc83d` Foreman: don't pass API keys to agents under --use-claude-login | Foreman | `npm run check` passes on Node 22 (483 tests). On Node 24 the only failure is the pre-existing Node 24 test fixed by the branch above |
| `upstream/agent-tick-catch-up` | `02d8679` Mod: keep agents walking when another mod skips their entity tick | Mod | `gradlew build` passes; a standalone `TickGate` harness passes |
| `upstream/diff-opens-selected-merge` | `7e32e54` Mod: open the diff of the selected merge, not the oldest one | Mod | `gradlew build` passes |
| `upstream/client-crash-guards` | `dc2ad48` Mod: crash guards for client tick, render and HUD handlers | Mod | `gradlew build` passes |
| `upstream/safe-defaults-outside-dev` | `fa9e0f7` Mod: safe defaults outside dev runs; guard world-changing commands | Mod | `gradlew build` passes |

The checks that were run:

- Mod: `cd mod && JAVA_HOME=/opt/homebrew/opt/openjdk@25 bash gradlew build --console=plain -q`.
  Upstream has no mod unit tests, so the build is the only check on the branch.
- Foreman: `cd foreman && npm ci && npm run check`. That runs tsc, vitest and the protocol-doc check.
- No branch was run in the game. Each PR text says what was verified downstream, and gives a manual
  check for this branch.

Upstream itself, at `upstream/main`, fails one Foreman test on Node 24
(`RepoManager > runs the repo test command`); `upstream/node-test-spec-output` fixes it.

Overlaps:

- `agent-tick-catch-up` and `client-crash-guards` both edit `AgentsFeature.java`.
- `agent-tick-catch-up` and `safe-defaults-outside-dev` both edit `mod/DEV.md`.

`git merge-tree` shows every pair merging cleanly. Suggested order: `node-test-spec-output` first (it
makes upstream's suite green on Node 24); the rest in any order.

## Pushing and opening (when decided)

The fork is a GitHub fork of upstream, so PRs can come from `larattalabs:<branch>`. The fork already
has topic branches with similar names (for example `foreman/node-test-spec-output`), so push under an
`upstream-pr/` prefix:

```sh
cd <agentcraft checkout>
git fetch upstream    # if upstream moved, rebase first: git rebase upstream/main upstream/<slug>

for slug in node-test-spec-output claude-login-drops-api-key agent-tick-catch-up \
            diff-opens-selected-merge client-crash-guards safe-defaults-outside-dev; do
  git push origin "upstream/$slug:upstream-pr/$slug"
done
```

Then open each PR. The title is the first `# ` line of its text file, and the body is the rest:

```sh
open_pr() {  # $1 = slug
  f="docs/upstream-prs/$1.md"
  title="$(head -1 "$f" | sed 's/^# //')"
  tail -n +2 "$f" | sed '1{/^$/d;}' > /tmp/pr-body.md
  gh pr create --repo blendi-remade/agentcraft --base main \
    --head "larattalabs:upstream-pr/$1" --title "$title" --body-file /tmp/pr-body.md
}
open_pr node-test-spec-output
# ...one call per slug
```

Run these commands from the `docs/upstream-prs` branch, or after it is merged into `main`, so that
the text files exist. Before opening a PR:

- Read its body and remove the `Branch:` line if you prefer.
- If the PR text should say "Generated with Claude Code", add that line by hand. Nothing here adds it.
- Note that commit author and committer are your git identity, as usual for your PRs.

## Candidates considered and rejected

| Candidate | Why not |
|---|---|
| Connector gate fails closed | Depends on the fork-only `connectors` setting. Upstream has no connector allowlist; its policy already asks the user for every external `mcp__` tool call |
| Sodium / LWJGL notes (LWJGL 3.4.3 for 26.3 + Sodium; Sodium 0.9.3-alpha vs Reese's Sodium Options) | Launcher-instance configuration, not code. Upstream runs through `gradlew runClient` and has no modpack guidance to attach it to |
| Lecterns open the library only inside a building | A real upstream issue: every vanilla lectern opens the library in any world. But the fork's fix depends on fork-only buildings. An HQ-world-only reduction is possible, but that is a design call for upstream |
| Auth retry with backoff / exact auth phrases | Upstream `checkAuth` marks auth failed on any error, including a timeout, so this is a real bug. But the fork fix is bundled with the usage reserve and the hold status (fork protocol). It could be reduced later |
| Pause screens in singleplayer; agent card only on empty-hand sneak + right-click | UX policy aimed at survival play, not a bug fix. Worth an issue upstream rather than a PR |
| Ctrl+Enter asks before merging in the diff screen | Upstream documents the instant merge as intended. Mentioned as a follow-up in the diff-opener PR |
| Remove / placement safety, occupancy checks, foundations, world journal | Fork-only buildings and world-journal architecture |
| Protected uncommitted edits, lead world expiry, goal adoption, PR watch, rate-limit pause, no-attribution commits | Depend on fork-only Foreman features (`protect`, leads per building, `land: "pr"`, usage banner) or a fork decision. The topic branches listed in docs/FORK.md stay the route for the generic Foreman features |
| Secret redaction, editable MCP servers / repo env | Built on fork-only settings (`repoSettings.env`, `claude.context.mcpServers`) |
