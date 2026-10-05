# Mod: open the diff of the selected merge, not the oldest one

Branch: `upstream/diff-opens-selected-merge` (one commit on `upstream/main`)

## Problem

`DiffLink` has a precise opener hook (`setOpener`), but nothing calls it. So `DiffLink.open` always
falls back to the DevBridge `"diff"` screen factory, `new DiffScreen(defaultTarget())`, and
`defaultTarget()` picks the **oldest** open merge decision. That affects:

- `D` (Review diff) on a merge decision in the decision screen;
- `/diff [worktree|@agent]` in the console.

With more than one merge waiting, reviewing the second merge opens the first. That screen's Merge
button and Ctrl+Enter answer the decision it shows, so the user can approve a different merge from
the one they selected.

## Fix

- `DiffFeature.init` installs the opener. With a decision, it opens `DiffScreen` on that decision's
  repo and worktree. With only a worktree, it opens `forWorktree(repo, worktree)`, which attaches that
  worktree's own merge decision when there is one.
- `DiffLink.open` / `hasDiffScreen` no longer fall back to the DevBridge screen. Without an opener,
  callers show the diff summary, as they already do in builds with no diff screen.
- The `"diff"` DevBridge screen stays registered for QA (`dev.screen {open:"diff"}`).

`DiffScreen` has no parent screen today, so the opener ignores `parent`, and Esc closes to the game
as before. Two things are left out of this PR:

- returning to the decision screen on Esc;
- making Ctrl+Enter ask for the same confirmation as the Merge button (it merges at once by design
  today).

Both would be small follow-ups if wanted.

## How tested

- `cd mod && ./gradlew build` passes.
- Downstream, the same opener wiring has been in use for a while. It is not run on this branch in the
  game.
- Suggested check (it shows the bug on `main` and the fix here):
  1. Start the sim backend with two waiting merges.
  2. Open the decision screen (`J`), select the second merge and press `D`.
  3. The diff header shows that worktree and "wants to merge" for that decision.
