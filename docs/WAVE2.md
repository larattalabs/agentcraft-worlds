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
