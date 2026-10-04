# Fix wave 2: check-in surfaces and village life (streams and contracts)

Follows docs/FIXWAVE.md (wave 1, merged). Goal: a Hardcore player checks in on the team from one place,
never misses what needs them, finds their way without docs, and the village feels alive.

## Streams
- **inbox** (mod hub + decisions/agents/monitor, small Foreman addition): a new hub tab **Inbox** (first
  tab) and one shared answer component.
- **hud** (mod hud + hub chrome + onboarding): persistent alert line, away digest on return, hub tab
  badges, multi-goal GoalBar, onboarding/help, discoverability hints.
- **walking** (mod agents/routing/pathfinder): agents walk between buildings outdoors.
- After these merge: **layout** (one agent with the dev client): every screen at the user's real setup,
  4K fullscreen with auto GUI scale (~426x240 GUI px) and at scale 2-4.

## Contracts
- W1 Inbox items (mod-side model, `dev.agentcraft.hub.InboxModel`, pure + unit-tested), newest first,
  grouped Needs you / Updates:
  - decisions (all kinds; merges open the diff), with the agent, goal, building;
  - agent replies to the user (feed `message` items with `to: "user"` or goalId threads' lead replies),
    unread until seen in the Inbox, the goal thread or the agent card;
  - blocked / failed tasks (with Retry / Open task);
  - holds (`foreman.status.hold`, contract C9: usage/auth/offline with `until`);
  - PRs needing attention (task status `pr` with new threads/checks failing: Task.pr).
  Read state persists per world in `<gameDir>/agentcraft/hub-seen.json` (extend its schema, keep old
  files readable). Filters: all / needs you / per building / per agent.
- W2 One answer component (`client/decisions/AnswerPanel`): options with the existing guards (350 ms
  arm, Reject twice, merge confirm), free text only when `textAllowed`, request-changes text; used by
  DecisionScreen, the Inbox, the goal thread and the agent card. `J` keeps opening DecisionScreen (fast
  path) which now hosts the AnswerPanel.
- W3 Agent log history: client message `agent.logs.request { agentId, before?: Ts, limit?: int <= 500 }`
  -> ack `{ entries: LogEntry[], more: boolean }` from the Foreman's stored agent logs (read-only, allowed
  only with the client token like every other non-read-only message? No: it is read-only, add it to the
  read-only allow list with `hello`/`diff.request`/`goal.digest`). The Inbox's agent view and a monitor
  right-click show a scrollable full log (paged).
- W4 In-world deep links: podium right-click -> Inbox filtered to that podium's decisions (the filter
  from wave 1's `podiumShows`); monitor right-click -> that agent's log/card view; task board card ->
  TaskScreen as today; console `/inbox`.
- W5 Alerts (hud): a persistent compact HUD line under the goal bar while anything needs the player:
  "2 decisions · 1 blocked · 3 replies · usage paused until 14:20" (each part only when non-zero; key hint
  for the Inbox). Same counts as Inbox Needs you. Hub tab badges use the same counts.
- W6 Away: on world join or after >= 10 minutes without the hub open, a toast "Since you were away: …
  (H)"; `H` then opens the Inbox (which shows the digest at the top). Otherwise `H` reopens the last tab.
- W7 Onboarding: first join in a world with AgentCraft and no buildings -> a short welcome card (what
  AgentCraft is, H hub, J decisions, ` console, how to place the first building) dismissible forever per
  world; a "Keys & help" section in the Status tab listing every key and in-world interaction.
- W8 Walking: when an agent changes building and both buildings are in the player's dimension, within
  ~256 blocks of each other and the route's chunks are loaded on the client, the agent walks an outdoor
  route between entrances (A* on the client's loaded terrain: standable surface, step up 1, drop <= 3,
  no water deeper than 1, no lava, no fire, avoid cliffs; doors/gates handled; capped search budget,
  computed off the render path); otherwise it teleports as today. Routes are cached per building pair
  and invalidated by block changes near them. Walking agents keep their nameplate/state; at night they
  still walk (they cannot be hurt). A per-world setting (Settings > General or hub Buildings) can turn
  walking off.

## As implemented: inbox stream (branch `wave2/inbox`)

API for the **hud** stream (client thread; all pure parts unit-tested in `InboxModelTest`):
- `dev.agentcraft.client.hub.Inbox.counts()` -> `dev.agentcraft.hub.InboxModel.Counts` record
  `(int decisions, int blocked, int replies, int prs, @Nullable InboxModel.Hold hold)`:
  - `decisions`: open decisions not being answered from this client; `blocked`: blocked tasks;
    `replies`: **unread** agent replies; `prs`: PRs needing attention; `hold`: `foreman.status.hold` (C9) or null.
  - `needsYou()` = the sum (+1 while a hold is on) = the Inbox's "Needs you" group size = the hub tab badge.
  - `parts(ZoneId)` -> the W5 line parts, each only when non-zero, in order: "2 decisions", "1 blocked",
    "3 replies", "1 PR", "usage paused until 14:20" (`InboxModel.holdText`); `line(ZoneId)` joins them with " · ".
- `Inbox.open(@Nullable String filter)` opens the hub on the Inbox (`filter`: `all` | `needs_you` |
  `building:<id>` | `agent:<id>` | `podium:<buildingId|home>`; null keeps the last); returns the `HubScreen`.
  `Inbox.openAgent(agentId)`, `Inbox.openPodium(@Nullable buildingId)`, `Inbox.openItem(key)`.
- `Inbox.markAgentSeen(agentId)`: the agent card marks that agent's replies read (called by AgentCardScreen).
- `Inbox.revision()`: bumps whenever counts may have changed (cheap change check for a HUD line).
- `HubTab.INBOX` is the first tab (order: Inbox, Buildings, Repos, Goals, Team, Settings, Status). `H` and
  `HubFeature.open(null)` still open Buildings (the hud stream owns W6, "H opens the Inbox when away").
- Read state: `hub-seen.json` worlds gain `"inbox": {"all": ts, "items": {key: ts}, "agents": {id: ts}}`
  (version 2; version 1 files load unchanged). A reply is read when its ts <= the newest of: Mark all read, its
  own mark (viewed in the Inbox), its agent's mark (agent card), its goal's own mark (opened in the goal thread).
- W2 `client/decisions/AnswerPanel` (Host + Options): `Options.SCREEN` (DecisionScreen: numbered, Review diff, **no merge
  confirm**: 1-9 behind the 350 ms arm as before, so `J` behaves exactly as in wave 1), `Options.EMBEDDED` (Inbox, goal
  thread: Merge and Reject ask twice), the card uses `(confirmMerge, diff, no numbers, 2 lines)`. Held-key / OS-repeat
  detection stays in each host. The goal thread keeps its hub button ids (`answer:<id>:<opt|text|open>`).
- W3 Foreman: `agent.logs.request {agentId, before?, limit? (1..500, default 200)}` -> ack `{agentId, entries, more}`,
  read backwards through `logs/<agent>.jsonl` then `<agent>.1.jsonl`; entries sharing the oldest entry's `ts` are never
  split across pages (page by `before = entries[0].ts`); unknown agent refused; allowed without the client token.
- W4: podium right-click -> `Inbox.openPodium(building)` (`dev.decision {podium}` follows the right-click; `screen:true`
  keeps the old scoped DecisionScreen); monitor right-click -> `Inbox.openAgent` (feed monitor: the Inbox); `/inbox
  [@agent]`. Task board cards still open the TaskScreen.
## As implemented: hud (branch `wave2/hud`)
Code: `mod/src/client/java/dev/agentcraft/client/hud/` (`Alerts`, `AlertCounts`, `ForemanAlertCounts`, `GoalBar`,
`Toasts`, `HudWatch`, `HudMemory`, `HelpContent`, `WelcomeScreen`), `client/hub/` (`TabBadges`, `StatusPane`, small
edits in `HubScreen`, `HubFeature`, `HubGoals`), pure + unit-tested `mod/src/main/java/dev/agentcraft/hud/`
(`AlertLine`, `HudPrefs`, `HudRules`; `AlertLineTest`, `HudRulesTest`). DevBridge in mod/DEV.md "HUD check-in (wave 2)".
- **Counts (W5) behind `AlertCounts`** (`decisions()`, `blocked()`, `replies()`, `hold()`): today
  `ForemanAlertCounts` computes them from `ForemanState`: decisions = `DecisionsFeature.waitingCount()` (what the
  badge shows), blocked = tasks with status `blocked`, replies = feed `message` items from an agent to the user newer
  than their read mark (a goal-tagged one: the goal's `hub-seen.json` mark, set by opening the goal in the hub; the rest:
  `hub-hud.json repliesSeen`, set while the console is open, which shows every reply and so also covers goal
  replies), hold = `foreman.status.hold`. The feed is the Foreman's, not the world's: the first time a world is seen
  (no `hub-hud.json` entry) `repliesSeen` starts at now, so older replies never flood a new world or the first join
  after the upgrade. **Merge step**: when
  `InboxModel` lands, call `Alerts.setSource("inbox", () -> <its Needs-you counts as an AlertCounts>)` once (e.g. in
  the inbox feature's init); the alert line, the away toast and every tab badge follow. The Inbox's read state then
  replaces the console/goal marks above.
- **Protocol mirror**: `Protocol.Hold(reason, until?, message?)` and `ForemanStatus.hold` (last component). The inbox
  stream needs the same: keep one.
- **Alert line**: drawn by `GoalBar` under the decisions badge (the badge stays the `J` fast path, so decisions show in
  both), one tooltip-style row: each non-zero part with its status dot (decisions clay/waiting, blocked error,
  replies thinking, hold idle), " · " between, then the hub key's keycap + "open". Three widths picked per frame
  (`AlertLine.fit`): full ("2 decisions · 1 blocked · 3 replies · usage paused until 14:20"), short ("2 dec · 1 blk ·
  3 msg · paused → 14:20"), dots ("2 1 3 14:20" next to their dots, keycap only). Hold texts: usage "usage paused
  until 14:20" ("Mon 14:20" when not today, "usage paused" without `until`), auth "Claude sign-in needed", offline
  "Claude offline, retry 14:20". Hidden when nothing needs the player, with F1 (`mc.gui.hud.isHidden()`, the whole
  goal bar), and dimmed while the Foreman is stale. It uses the goal bar's pill-avoiding placement, so toasts stack
  under it when they would meet.
- **Tab badges**: `TabBadges` by tab id: `inbox` = needs-you (shows by itself once `HubTab` has an `inbox` id), `goals`
  = goals with unread activity, `repos` = CI `fail`, `team` = agents `blocked`/`error`. A status dot and the count after
  the label; when the strip does not fit, dots only (`compact`); still too wide, labels are cut (`overflow`).
- **Away (W6)**: `hub-hud.json` (sibling of `hub-seen.json`, same world key) keeps `hubSeenAt` (any hub tab on screen,
  also under a task/decision screen opened from it), `lastAwayToastAt`, `lastTab`, `repliesSeen`,
  `welcomeDismissed`. The stretch starts at max(hubSeenAt, lastAwayToastAt) (unknown when the hub was never opened in
  that world: no toast; before `hubSeenAt` existed, the Goals tab's `hub-seen.json` mark stands in). Checked once on joining (as soon as the Foreman is connected) and every 2 minutes once the
  stretch is >= 10 minutes, never while the hub is open: `goal.digest {since}` via `HubGoals.requestAway` (so the
  Goals tab's away panel shows the same digest; `HubGoals.checkAway` no longer replaces a fresh one). A toast only
  when a goal moved: "Since you were away: 2 goals moved, 1 needs you" (needs-you = the alert counts), with the hub
  key hint "catch up"; nothing moved: no toast and no "nothing happened" panel. After the toast `H` opens the Inbox
  (`HubTab.parse("inbox")`, so it switches at merge by itself) else Goals; otherwise `H` opens the world's last tab.
  The Inbox should show `HubGoals.away()` at its top.
- **Toast hints**: "need you" toasts hint `J answer` when about a decision, else `H open hub`; `Toasts.push(n, key, verb)`
  for an explicit hint.
- **Multi-goal goal bar**: open goals (planning/active) ranked by urgency (open decisions + blocked tasks of the
  goal), then most recent update (`HudRules.pickGoal`). An urgent goal is pinned; otherwise they take turns every 8 s.
  The title row shows "+N more" (or "+N" when tight). No open goal: the latest goal as before. Task counts are per goal
  now (tasks without a goal id count only while there is a single goal).
- **Welcome (W7)**: `WelcomeScreen` 2.5 s after joining a singleplayer world (not the dev HQ, buildings file readable)
  with no building, unless dismissed in that world. Two lines on what AgentCraft is, H / J / console keys (live
  bindings), "Place your first building: H > Buildings > Place new", the Foreman status. "Open the hub" (Buildings tab),
  "Got it" and Esc dismiss it for good; Status > Keys & help > "Show the welcome card" shows it again.
  `AGENTCRAFT_WELCOME=0` stops it opening by itself (scripted QA worlds). It scrolls when the window is short.
- **Keys & help**: the Status tab has chips Overview / Keys & help (←→). Help lists every key mapping of the
  AgentCraft category (live labels, "not bound"), the in-world interactions (podium, task board, monitor = look only,
  console terminal, library, merge station, agent card = empty-hand sneak + right-click), the rebinding path, and
  "Show the welcome card". Scrolls (wheel, ↑↓) and reports `{needed, available, overflow}`.
- Decisions for the coordinator: decisions appear in both the badge and the line; the away toast uses a keycap hint
  instead of a literal "(H)"; `hub-hud.json` instead of extending `hub-seen.json` (no schema clash with the inbox
  stream); monitors have no right-click today (W4 adds one: update `HelpContent.INTERACTIONS` then); the hold's
  `message` is carried (`dev.hud.state alert.holdMessage`) but not drawn (the line stays one compact row); the
  Status Overview has no scroll (18 px went to the chips): `statusTab.layout` reports whether it fits, the layout
  stream decides.
