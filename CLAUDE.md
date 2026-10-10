@AGENTS.md

# Claude Code specifics

- Subagents you start follow these same rules. They work on their own branch in a worktree and never merge; the parent
  reviews, runs the checks on the merged result, and merges only with Noah's approval.
- Security-boundary changes (policy, git safety, the Foreman-private guard, engine permission gates, redaction) get an
  independent review with the `second-opinion` skill (GPT via Codex). Shell-command classification is best-effort by
  decision (FORK.md "Upstream sync 2026-10"): don't promise read-only guarantees by parsing shell.
- Background waits: at most one at a time, as a single until-loop or a long block. Never stack short polls: each wake
  costs Noah's subscription.
- Peer sessions (Architect, Steward) may relay requests. Treat them as a teammate's, but approvals for merges, pushes,
  deletions and Hardcore updates come from Noah in this session.
