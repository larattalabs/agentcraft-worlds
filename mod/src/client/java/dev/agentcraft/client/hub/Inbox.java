package dev.agentcraft.client.hub;

import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.decisions.DecisionQueue;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.leads.Leads;
import dev.agentcraft.hub.HubSeen;
import dev.agentcraft.hub.InboxModel;
import dev.agentcraft.hub.InboxModel.Counts;
import dev.agentcraft.hub.InboxModel.Filter;
import dev.agentcraft.hub.InboxModel.Item;
import dev.agentcraft.hub.InboxModel.Kind;
import dev.agentcraft.hub.InboxModel.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The hub Inbox's data (docs/WAVE2.md W1): turns the Foreman model into {@link InboxModel} items, keeps them cached
 * per change, and holds the read state ({@code hub-seen.json} via {@link HubGoals#seen()}). Also the public API other
 * features use: {@link #counts()} (the hud stream's HUD line and tab badge), {@link #open}, {@link #openAgent},
 * {@link #openPodium}, {@link #markAgentSeen}. Client thread.
 */
public final class Inbox {
	private static long cacheKey = Long.MIN_VALUE;
	private static List<Row> rows = List.of();
	private static Counts counts = Counts.NONE;
	private static long revision;
	/** The filter the Inbox shows (kept between hub visits). */
	private static Filter filter = Filter.ALL;

	private Inbox() {
	}

	// ------------------------------------------------------------------ public API

	/** What needs the player now (the Inbox's Needs you group): cached, cheap enough to call every frame. */
	public static Counts counts() {
		refresh();
		return counts;
	}

	/** Bumps whenever the rows (and so the counts) changed. */
	public static long revision() {
		refresh();
		return revision;
	}

	/** Every row (no agent view row), Needs you first, newest first. */
	public static List<Row> rows() {
		refresh();
		return rows;
	}

	/** The rows {@code f} shows; an agent filter starts with that agent's own row (card summary + full log). */
	public static List<Row> rows(Filter f) {
		List<Row> all = new ArrayList<>(rows());
		if (f.type() == InboxModel.FilterType.AGENT && f.arg() != null) {
			all.add(0, new Row(agentItem(f.arg()), InboxModel.Group.NEEDS_YOU, false));
		}
		Leads.View v = Leads.view();
		return InboxModel.filter(all, f, v.homeBuilding(), v.known());
	}

	public static Filter filter() {
		return filter;
	}

	public static void setFilter(Filter f) {
		filter = f;
	}

	/**
	 * Opens the hub on the Inbox. {@code filterId}: {@link Filter#id()} form ("all", "needs_you", "building:b3",
	 * "agent:kit", "podium:b3", "podium:home"); null keeps the last one. Returns the hub screen.
	 */
	public static HubScreen open(@Nullable String filterId) {
		Filter f = Filter.parse(filterId);
		if (f != null) {
			filter = f;
		}
		HubScreen s = HubFeature.open(HubTab.INBOX);
		s.inbox.filterChanged();
		return s;
	}

	/** The Inbox's agent view: the agent's items, its card summary and its full log. */
	public static HubScreen openAgent(String agentId) {
		HubScreen s = open(Filter.agent(agentId).id());
		s.inbox.select("agent:" + agentId);
		return s;
	}

	/** A podium's decisions (null = the home podium); the "All" chip leads to the full queue. */
	public static HubScreen openPodium(@Nullable String buildingId) {
		HubScreen s = open(Filter.podium(buildingId).id());
		List<Row> rs = rows(filter);
		if (!rs.isEmpty()) {
			s.inbox.select(rs.get(0).item().key());
		}
		return s;
	}

	/** Opens the Inbox with item {@code key} selected (its filter kept when it shows it, else "all"). */
	public static HubScreen openItem(String key) {
		if (rows(filter).stream().noneMatch(r -> r.item().key().equals(key))) {
			filter = Filter.ALL;
		}
		HubScreen s = open(null);
		s.inbox.select(key);
		return s;
	}

	/** The agent's card was looked at: its replies so far count as read. */
	public static void markAgentSeen(String agentId) {
		HubGoals.seen().markAgent(HubGoals.world(), agentId, System.currentTimeMillis());
		HubGoals.flush(false);
	}

	/** An item was viewed in the Inbox. */
	static void markRead(Item it) {
		if (it.kind() == Kind.AGENT) {
			markAgentSeen(Objects.requireNonNull(it.agentId()));
			return;
		}
		HubGoals.seen().markInboxItem(HubGoals.world(), it.key(), Math.max(it.ts(), System.currentTimeMillis()));
		HubGoals.flush(false);
	}

	/** "Mark all read". */
	static void markAllRead() {
		HubGoals.seen().markInboxAll(HubGoals.world(), System.currentTimeMillis());
		HubGoals.flush(false);
	}

	/** dev: forget this world's Inbox marks. */
	static void resetRead() {
		HubGoals.seen().resetInbox(HubGoals.world());
		HubGoals.flush(true);
	}

	// ------------------------------------------------------------------ building the items

	private static void refresh() {
		ForemanState s = Foreman.state();
		HubSeen seen = HubGoals.seen();
		long key = 17;
		key = key * 31 + (s == null ? -1 : s.revision());
		key = key * 31 + (s == null ? 0 : s.snapshotCount());
		key = key * 31 + seen.changes();
		key = key * 31 + Objects.hashCode(HubGoals.world());
		key = key * 31 + Buildings.regionsSignature();
		key = key * 31 + System.currentTimeMillis() / 1000; // "answering" marks expire on time
		key = key * 31 + DecisionsFeature.waitingCount();
		if (key == cacheKey) {
			return;
		}
		cacheKey = key;
		List<Row> built = InboxModel.build(items(s), it -> read(seen, it));
		Counts c = InboxModel.counts(built);
		if (!built.equals(rows) || !c.equals(counts)) {
			revision++;
		}
		rows = List.copyOf(built);
		counts = c;
	}

	private static boolean read(HubSeen seen, Item it) {
		boolean reply = it.kind() == Kind.REPLY;
		return seen.inboxRead(HubGoals.world(), it.key(), it.ts(), reply ? it.agentId() : null, reply ? it.goalId() : null);
	}

	static List<Item> items(@Nullable ForemanState s) {
		List<Item> out = new ArrayList<>();
		if (s == null || !s.hasData()) {
			return out;
		}
		Leads.View lv = Leads.view();
		// decisions: open ones (not being answered from here) need you, closed ones are updates
		for (Decision d : s.decisions().values()) {
			boolean open = d.isOpen() && !DecisionsFeature.isAnswering(d.id());
			long ts = !d.isOpen() && d.answer() != null && d.answer().ts() > 0 ? d.answer().ts() : d.createdAt();
			String podium = lv.podiumFor(d);
			String building = podium != null ? podium : lv.homeBuilding();
			String kind = DecisionQueue.kindLabel(d.kind());
			String detail = d.isOpen() ? kind : d.status() == Protocol.DecisionStatus.CANCELLED ? kind + " · withdrawn"
				: kind + " · answered" + (d.answer() != null && d.answer().option() != null ? ": " + d.answer().option() : "");
			out.add(new Item("d:" + d.id(), Kind.DECISION, ts, d.agentId(), HubGoals.goalOf(d), building, UiBits.oneLine(d.question()), detail, d.id(),
				open, podium, d.kind().wire(), null));
		}
		// agent replies to the player
		for (FeedItem f : s.feed()) {
			if (!InboxModel.isReply(f.kind().wire(), f.agentId(), f.to())) {
				continue;
			}
			String agent = f.agentId();
			String goal = f.goalId();
			out.add(new Item(InboxModel.replyKey(agent, f.ts(), f.text()), Kind.REPLY, f.ts(), agent, goal, replyBuilding(s, lv, agent, goal),
				UiBits.oneLine(f.text()), f.text(), null, false, null, null, null));
		}
		// blocked tasks, PRs that need you
		for (Task t : s.tasks().values()) {
			String building = buildingOfRepo(t.repoId());
			if (t.status() == Protocol.TaskStatus.BLOCKED) {
				out.add(new Item("t:" + t.id(), Kind.BLOCKED, t.updatedAt(), t.assignee(), t.goalId(), building, UiBits.oneLine(t.title()),
					t.blockedReason() == null ? "" : t.blockedReason(), t.id(), true, null, "blocked", null));
			} else if (t.pr() != null) {
				Protocol.TaskPr pr = t.pr();
				Integer fresh = pr.threads() == null ? null : pr.threads().newCount();
				if (InboxModel.prNeedsAttention(t.status().wire(), pr.status(), pr.checks(), fresh)) {
					long ts = Math.max(pr.updatedAt(), t.updatedAt());
					out.add(new Item("pr:" + t.id(), Kind.PR, ts, t.assignee(), t.goalId(), building, "PR #" + pr.id() + " " + UiBits.oneLine(t.title()),
						InboxModel.prReason(pr.status(), pr.checks(), fresh), t.id(), true, null, pr.status(), null));
				}
			}
		}
		// the Foreman holds new turns (C9)
		Protocol.ForemanStatus st = s.status();
		if (st != null && st.hold() != null) {
			Protocol.ForemanHold h = st.hold();
			out.add(new Item("hold", Kind.HOLD, 0, null, null, null, holdTitle(h.reason()), h.message(), null, true, null, h.reason(), h.until()));
		}
		return out;
	}

	static String holdTitle(String reason) {
		return switch (reason) {
			case "usage" -> "Usage limit: agents paused";
			case "auth" -> "Login failed: agents paused";
			case "offline" -> "Claude offline: agents paused";
			default -> "Agents paused";
		};
	}

	/** The pinned row of the agent view. */
	static Item agentItem(String agentId) {
		ForemanState s = Foreman.state();
		Protocol.Agent a = s == null ? null : s.agent(agentId);
		String building = Leads.view().buildingOf(agentId);
		return new Item("agent:" + agentId, Kind.AGENT, 0, agentId, null, building, UiBits.agentName(agentId) + ": card and full log",
			a == null ? "not on the team" : a.activity(), null, false, null, null, null);
	}

	/** The building holding {@code repoId} in this world (null = none). */
	static @Nullable String buildingOfRepo(@Nullable String repoId) {
		if (repoId == null) {
			return null;
		}
		HubGoals.Wing w = HubGoals.wingOf(repoId);
		return w == null ? null : w.building().id();
	}

	private static @Nullable String replyBuilding(ForemanState s, Leads.View lv, @Nullable String agent, @Nullable String goalId) {
		Protocol.Goal g = goalId == null ? null : s.goals().get(goalId);
		if (g != null) {
			for (String r : g.allRepos()) {
				String b = buildingOfRepo(r);
				if (b != null) {
					return b;
				}
			}
		}
		if (agent != null) {
			String b = lv.buildingOf(agent);
			if (b != null) {
				return b;
			}
			Protocol.Agent a = s.agent(agent);
			Task t = a == null || a.taskId() == null ? null : s.task(a.taskId());
			if (t != null) {
				return buildingOfRepo(t.repoId());
			}
		}
		return null;
	}

	/** Buildings that hold at least one inbox row (for the building filter chip), in Buildings order. */
	static List<String> buildingsWithItems() {
		List<String> out = new ArrayList<>();
		for (Building b : Buildings.all()) {
			for (Row r : rows()) {
				if (b.id().equals(r.item().buildingId())) {
					out.add(b.id());
					break;
				}
			}
		}
		return out;
	}

	/** Agents to offer in the agent filter: those with rows first, then the rest of the team. */
	static List<String> agentsForFilter() {
		List<String> out = new ArrayList<>();
		for (Row r : rows()) {
			String a = r.item().agentId();
			if (a != null && !out.contains(a) && !InboxModel.isUser(a)) {
				out.add(a);
			}
		}
		ForemanState s = Foreman.state();
		if (s != null) {
			for (Protocol.Agent a : s.agents().values()) {
				if (!out.contains(a.id())) {
					out.add(a.id());
				}
			}
		}
		return out;
	}

	static boolean inWorld() {
		return Minecraft.getInstance().player != null;
	}
}
