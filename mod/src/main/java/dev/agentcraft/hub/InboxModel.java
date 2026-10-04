package dev.agentcraft.hub;

import dev.agentcraft.building.LeadRouting;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The hub Inbox (docs/WAVE2.md W1): one list of everything that needs the player or happened for them, grouped
 * <b>Needs you</b> (open decisions, blocked tasks, unread agent replies, the Foreman's hold, PRs needing attention)
 * and <b>Updates</b> (replies already read, decisions closed lately), newest first, with filters all / needs you /
 * per building / per agent / per podium, and the counts the HUD line and the hub badge show (W5).
 *
 * <p>Pure (no game or protocol classes): the client turns the Foreman model into {@link Item}s, this class groups,
 * filters and counts them. Unit-tested in {@code InboxModelTest}.
 */
public final class InboxModel {
	/** Closed decisions kept in Updates. */
	public static final int MAX_CLOSED_DECISIONS = 30;
	/** Read replies kept in Updates. */
	public static final int MAX_READ_REPLIES = 60;

	private InboxModel() {
	}

	public enum Kind {
		DECISION, REPLY, BLOCKED, HOLD, PR,
		/** The agent view's own row (the card summary and the full log); only under an agent filter, never counted. */
		AGENT;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static @Nullable Kind parse(@Nullable String s) {
			for (Kind k : values()) {
				if (k.id().equalsIgnoreCase(s)) {
					return k;
				}
			}
			return null;
		}
	}

	public enum Group {
		NEEDS_YOU, UPDATES;

		public String id() {
			return this == NEEDS_YOU ? "needs_you" : "updates";
		}

		public String label() {
			return this == NEEDS_YOU ? "Needs you" : "Updates";
		}
	}

	/**
	 * One inbox entry.
	 *
	 * @param key       stable id (read marks): {@code d:<id>}, {@code r:<agent>:<ts>:<hash>}, {@code t:<taskId>}, {@code pr:<taskId>},
	 *                  {@code hold}, {@code agent:<id>}
	 * @param ts        when it happened (a reply's time, a decision's creation, a task's last update, ...)
	 * @param open      still waiting on the player (an open decision not being answered, a blocked task, a PR needing
	 *                  attention, a hold); replies are never "open", their read state decides their group
	 * @param refId     the decision / task id it is about (null for replies, holds)
	 * @param podium    decisions: the building whose podium shows it ({@link LeadRouting#podiumFor}), null = home
	 * @param subKind   decisions: question | permission | merge; tasks: the PR status; holds: the reason
	 * @param until     holds: when it is expected to lift (null = unknown / not a hold)
	 */
	public record Item(String key, Kind kind, long ts, @Nullable String agentId, @Nullable String goalId, @Nullable String buildingId,
		String title, String detail, @Nullable String refId, boolean open, @Nullable String podium, @Nullable String subKind, @Nullable Long until) {
		public Item {
			Objects.requireNonNull(key);
			Objects.requireNonNull(kind);
			title = title == null ? "" : title;
			detail = detail == null ? "" : detail;
		}
	}

	/** An item with its group and read state. */
	public record Row(Item item, Group group, boolean unread) {
	}

	/** Read state of items (hub-seen.json, see {@link HubSeen#inboxRead}). */
	@FunctionalInterface
	public interface ReadState {
		boolean read(Item item);
	}

	/** Contract C9: the Foreman holds new agent turns. {@code reason} usage | auth | offline. */
	public record Hold(String reason, @Nullable Long until, String message) {
		public Hold {
			reason = reason == null ? "unknown" : reason;
			message = message == null ? "" : message;
		}
	}

	// ------------------------------------------------------------------ filters

	public enum FilterType {
		ALL, NEEDS_YOU, BUILDING, AGENT, PODIUM
	}

	/**
	 * What the list shows. {@code arg}: the building id (BUILDING, PODIUM; PODIUM {@code null} = the home podium) or
	 * the agent id (AGENT).
	 */
	public record Filter(FilterType type, @Nullable String arg) {
		public static final Filter ALL = new Filter(FilterType.ALL, null);
		public static final Filter NEEDS_YOU = new Filter(FilterType.NEEDS_YOU, null);

		public static Filter building(String id) {
			return new Filter(FilterType.BUILDING, id);
		}

		public static Filter agent(String id) {
			return new Filter(FilterType.AGENT, id);
		}

		/** A podium's decisions; {@code building} null = the home podium (or one outside every building). */
		public static Filter podium(@Nullable String building) {
			return new Filter(FilterType.PODIUM, building);
		}

		/** "all", "needs_you", "building:b3", "agent:kit", "podium:b3", "podium:home". */
		public String id() {
			return switch (type) {
				case ALL -> "all";
				case NEEDS_YOU -> "needs_you";
				case BUILDING -> "building:" + arg;
				case AGENT -> "agent:" + arg;
				case PODIUM -> "podium:" + (arg == null ? "home" : arg);
			};
		}

		/** Parses {@link #id()} (also "needs", "needsyou", "needs you"); null when it is none of them. */
		public static @Nullable Filter parse(@Nullable String s) {
			if (s == null || s.isBlank()) {
				return null;
			}
			String t = s.strip();
			String l = t.toLowerCase(Locale.ROOT);
			if (l.equals("all")) {
				return ALL;
			}
			if (l.equals("needs_you") || l.equals("needs") || l.equals("needsyou") || l.equals("needs you")) {
				return NEEDS_YOU;
			}
			int c = t.indexOf(':');
			if (c <= 0 || c == t.length() - 1) {
				return null;
			}
			String head = l.substring(0, c);
			String arg = t.substring(c + 1).strip();
			if (arg.isEmpty()) {
				return null;
			}
			return switch (head) {
				case "building" -> building(arg);
				case "agent" -> agent(arg.startsWith("@") ? arg.substring(1) : arg);
				case "podium" -> podium(arg.equalsIgnoreCase("home") ? null : arg);
				default -> null;
			};
		}
	}

	/**
	 * Whether {@code row} shows under {@code f}. {@code homeBuilding}: this world's home building (podium filters: what
	 * "home" means); {@code podiumsKnown}: the Foreman reports lead assignments (an older one: every podium shows all).
	 */
	public static boolean matches(Row row, Filter f, @Nullable String homeBuilding, boolean podiumsKnown) {
		Item it = row.item();
		if (it.kind() == Kind.AGENT) {
			return f.type() == FilterType.AGENT && Objects.equals(f.arg(), it.agentId());
		}
		return switch (f.type()) {
			case ALL -> true;
			case NEEDS_YOU -> row.group() == Group.NEEDS_YOU;
			case BUILDING -> it.kind() == Kind.HOLD || Objects.equals(f.arg(), it.buildingId());
			case AGENT -> Objects.equals(f.arg(), it.agentId());
			case PODIUM -> it.kind() == Kind.DECISION && it.open()
				&& (!podiumsKnown || LeadRouting.podiumShowsTarget(f.arg(), homeBuilding, it.podium()));
		};
	}

	// ------------------------------------------------------------------ build

	/**
	 * Groups and sorts {@code items}: Needs you first (the hold pinned on top, then newest first), then Updates newest
	 * first. Open items need you; a reply needs you while unread. Closed decisions and read replies are capped
	 * ({@link #MAX_CLOSED_DECISIONS}, {@link #MAX_READ_REPLIES}).
	 */
	public static List<Row> build(List<Item> items, ReadState read) {
		List<Row> needs = new ArrayList<>();
		List<Row> updates = new ArrayList<>();
		for (Item it : items) {
			boolean unread = it.kind() != Kind.AGENT && !read.read(it);
			boolean needsYou = switch (it.kind()) {
				case REPLY -> unread;
				case AGENT -> false;
				default -> it.open();
			};
			if (it.kind() == Kind.AGENT) {
				needs.add(new Row(it, Group.NEEDS_YOU, false)); // pinned on top of the agent view
			} else if (needsYou) {
				needs.add(new Row(it, Group.NEEDS_YOU, unread));
			} else if (it.kind() == Kind.DECISION || it.kind() == Kind.REPLY) {
				updates.add(new Row(it, Group.UPDATES, unread));
			}
		}
		Comparator<Row> newest = Comparator.comparingLong((Row r) -> r.item().ts()).reversed().thenComparing(r -> r.item().key());
		Comparator<Row> pinned = Comparator.comparingInt((Row r) -> r.item().kind() == Kind.AGENT ? 0 : r.item().kind() == Kind.HOLD ? 1 : 2);
		needs.sort(pinned.thenComparing(newest));
		updates.sort(newest);
		List<Row> out = new ArrayList<>(needs);
		int decisions = 0;
		int replies = 0;
		for (Row r : updates) {
			if (r.item().kind() == Kind.DECISION && ++decisions > MAX_CLOSED_DECISIONS) {
				continue;
			}
			if (r.item().kind() == Kind.REPLY && ++replies > MAX_READ_REPLIES) {
				continue;
			}
			out.add(r);
		}
		return out;
	}

	/** The rows {@code f} shows, in order. */
	public static List<Row> filter(List<Row> rows, Filter f, @Nullable String homeBuilding, boolean podiumsKnown) {
		List<Row> out = new ArrayList<>();
		for (Row r : rows) {
			if (matches(r, f, homeBuilding, podiumsKnown)) {
				out.add(r);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ counts

	/** The Needs you counts (W5 HUD line, hub badge). */
	public record Counts(int decisions, int blocked, int replies, int prs, @Nullable Hold hold) {
		public static final Counts NONE = new Counts(0, 0, 0, 0, null);

		/** The size of the Needs you group: every count, +1 while a hold is on. */
		public int needsYou() {
			return decisions + blocked + replies + prs + (hold != null ? 1 : 0);
		}

		/** The HUD line's parts, each only when non-zero: "2 decisions", "1 blocked", "3 replies", "1 PR", the hold. */
		public List<String> parts(ZoneId zone) {
			List<String> out = new ArrayList<>();
			if (decisions > 0) {
				out.add(decisions + (decisions == 1 ? " decision" : " decisions"));
			}
			if (blocked > 0) {
				out.add(blocked + " blocked");
			}
			if (replies > 0) {
				out.add(replies + (replies == 1 ? " reply" : " replies"));
			}
			if (prs > 0) {
				out.add(prs + (prs == 1 ? " PR" : " PRs"));
			}
			if (hold != null) {
				out.add(holdText(hold, zone));
			}
			return out;
		}

		/** "2 decisions · 1 blocked · usage paused until 14:20", or "" when nothing needs the player. */
		public String line(ZoneId zone) {
			return String.join(" · ", parts(zone));
		}
	}

	/** Counts the Needs you rows of {@code rows} (as built by {@link #build}). */
	public static Counts counts(List<Row> rows) {
		int d = 0;
		int b = 0;
		int r = 0;
		int p = 0;
		Hold hold = null;
		for (Row row : rows) {
			if (row.group() != Group.NEEDS_YOU) {
				continue;
			}
			switch (row.item().kind()) {
				case DECISION -> d++;
				case BLOCKED -> b++;
				case REPLY -> r++;
				case PR -> p++;
				case HOLD -> hold = new Hold(row.item().subKind(), row.item().until(), row.item().detail());
				default -> {
				}
			}
		}
		return new Counts(d, b, r, p, hold);
	}

	// ------------------------------------------------------------------ rules

	/** An agent's reply to the player: a feed {@code message} from an agent (not the player) addressed to the player. */
	public static boolean isReply(String feedKind, @Nullable String agentId, @Nullable String to) {
		return "message".equals(feedKind) && agentId != null && !isUser(agentId) && isUser(to);
	}

	/** The player's id on the wire ("user", also "you"/"player" from older tools). */
	public static boolean isUser(@Nullable String id) {
		return id != null && (id.equals("user") || id.equals("you") || id.equals("player"));
	}

	/**
	 * A task in status {@code pr} whose pull request needs the player: changes requested, checks failing, or new review
	 * threads. {@code prStatus} open|changes|approved|merged|abandoned, {@code checks} pending|passing|failing|none.
	 */
	public static boolean prNeedsAttention(String taskStatus, @Nullable String prStatus, @Nullable String checks, @Nullable Integer newThreads) {
		if (!"pr".equals(taskStatus) || prStatus == null || prStatus.equals("merged") || prStatus.equals("abandoned")) {
			return false;
		}
		return prStatus.equals("changes") || "failing".equals(checks) || newThreads != null && newThreads > 0;
	}

	/** Why a PR needs attention, for its row: "changes requested · checks failing · 2 new threads". */
	public static String prReason(@Nullable String prStatus, @Nullable String checks, @Nullable Integer newThreads) {
		List<String> parts = new ArrayList<>();
		if ("changes".equals(prStatus)) {
			parts.add("changes requested");
		}
		if ("failing".equals(checks)) {
			parts.add("checks failing");
		}
		if (newThreads != null && newThreads > 0) {
			parts.add(newThreads + (newThreads == 1 ? " new thread" : " new threads"));
		}
		return String.join(" · ", parts);
	}

	/** The key of a reply: agent, time and a hash of the text (feed items have no id). */
	public static String replyKey(String agentId, long ts, String text) {
		return "r:" + agentId + ":" + ts + ":" + Integer.toHexString(text.hashCode());
	}

	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

	/** One line for a hold: "usage paused until 14:20", "login failed: agents paused", "Claude offline: retry at 14:20". */
	public static String holdText(Hold h, ZoneId zone) {
		String until = h.until() == null ? null : HM.format(Instant.ofEpochMilli(h.until()).atZone(zone));
		return switch (h.reason()) {
			case "usage" -> until != null ? "usage paused until " + until : "usage paused";
			case "auth" -> "login failed: agents paused";
			case "offline" -> until != null ? "Claude offline: retry at " + until : "Claude offline: retrying";
			default -> h.message().isBlank() ? "agents paused" : h.message();
		};
	}

	/** What a hold means and when it lifts (the Inbox detail), one paragraph per entry. */
	public static List<String> holdExplain(Hold h, ZoneId zone) {
		List<String> out = new ArrayList<>();
		String until = h.until() == null ? null : HM.format(Instant.ofEpochMilli(h.until()).atZone(zone));
		switch (h.reason()) {
			case "usage" -> {
				out.add("Your Claude plan's usage limit was reached (or usage is above the reserve you set in Settings > Usage), so "
					+ "the Foreman starts no new agent turns. Turns already running finish.");
				out.add(until != null ? "It lifts by itself when the window resets at " + until + "; queued work then starts on its own."
					: "It lifts by itself when the usage window resets; queued work then starts on its own.");
			}
			case "auth" -> {
				out.add("The Foreman could not log in to Claude (the claude.ai login or the API key failed), so no agent can work.");
				out.add("It lifts after you log in again (run `claude` once in a terminal) and restart the Foreman (Settings > Restart "
					+ "Foreman, or restart it yourself).");
			}
			case "offline" -> {
				out.add("Claude could not be reached (network, outage or an overloaded API). The Foreman retries with a growing pause; "
					+ "nothing is lost.");
				out.add(until != null ? "The next retry is at " + until + "." : "It lifts on the next successful retry.");
			}
			default -> out.add("The Foreman is holding new agent turns.");
		}
		if (!h.message().isBlank()) {
			out.add("Foreman: " + h.message());
		}
		return out;
	}
}
