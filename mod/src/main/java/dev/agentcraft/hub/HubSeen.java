package dev.agentcraft.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * When the player last looked at the Goals tab and at each goal, per world (docs/HUB.md "Repos and Goals
 * tabs": {@code <gameDir>/agentcraft/hub-seen.json}). Drives the unread dots and the "since you were away"
 * digest. Pure (no game classes), so it is unit-tested; the client keeps one instance and saves it.
 *
 * <pre>
 * { "version": 2, "worlds": { "New World": { "tab": 1759500000000, "goals": { "g3": 1759500100000 },
 *     "inbox": { "all": 1759500200000, "items": { "d:d4": 1759500150000 }, "agents": { "kit": 1759500180000 } } } } }
 * </pre>
 *
 * The {@code inbox} object (wave 2, docs/WAVE2.md W1) is the Inbox's read state: "Mark all read", each item viewed
 * in the Inbox, each agent's card; version 1 files (without it) load unchanged.
 *
 * Not thread-safe: the client thread owns it.
 */
public final class HubSeen {
	public static final String FILE = "hub-seen.json";
	/** Away this long (since the Goals tab was last looked at), opening it asks for a digest. */
	public static final long AWAY_MS = 10 * 60_000L;
	/** A world keeps at most this many goals (the oldest-seen are dropped). */
	public static final int MAX_GOALS = 500;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Inbox item marks kept per world (the oldest-seen are dropped). */
	public static final int MAX_INBOX_ITEMS = 1000;

	private static final class World {
		long tab;
		final Map<String, Long> goals = new LinkedHashMap<>();
		final Inbox inbox = new Inbox();
	}

	/** The Inbox's read state of a world. */
	private static final class Inbox {
		long all;
		final Map<String, Long> items = new LinkedHashMap<>();
		final Map<String, Long> agents = new LinkedHashMap<>();

		boolean empty() {
			return all == 0 && items.isEmpty() && agents.isEmpty();
		}
	}

	private final Map<String, World> worlds = new LinkedHashMap<>();
	private boolean dirty;
	/** Bumps on every change (callers cache what they derive from the marks). */
	private long changes;

	/** When the Goals tab was last looked at in {@code world}, 0 = never. */
	public long tabSeen(String world) {
		World w = worlds.get(world);
		return w == null ? 0 : w.tab;
	}

	/**
	 * When goal {@code goalId} was last looked at: its own mark, else the Goals tab's (a goal never opened
	 * counts as seen when the list was), 0 = never.
	 */
	public long goalSeen(String world, String goalId) {
		World w = worlds.get(world);
		if (w == null) {
			return 0;
		}
		Long t = w.goals.get(goalId);
		return t != null ? t : w.tab;
	}

	/**
	 * {@link #goalSeen(String, String)} during a visit of the Goals tab: a goal never opened falls back to
	 * {@code tabFallback} (the tab's mark from before this visit began, see {@link #beginVisit}), so a goal
	 * that moved since the last visit shows as unread although the tab is on screen now.
	 */
	public long goalSeen(String world, String goalId, long tabFallback) {
		World w = worlds.get(world);
		Long t = w == null ? null : w.goals.get(goalId);
		return t != null ? t : tabFallback;
	}

	/**
	 * The Goals tab comes on screen: returns its mark from before (the fallback for {@link #goalSeen(String,
	 * String, long)} during this visit). Nothing is marked: {@link #endVisit} marks it when the tab goes.
	 */
	public long beginVisit(String world) {
		return tabSeen(world);
	}

	/** The Goals tab leaves the screen at {@code now}: it counts as seen up to then. */
	public void endVisit(String world, long now) {
		markTab(world, now);
	}

	/** Whether the goal's own mark exists (it was opened at least once). */
	public boolean opened(String world, String goalId) {
		World w = worlds.get(world);
		return w != null && w.goals.containsKey(goalId);
	}

	/** Marks the Goals tab seen at {@code ts} (never moves back). */
	public void markTab(String world, long ts) {
		World w = worlds.computeIfAbsent(world, k -> new World());
		if (ts > w.tab) {
			w.tab = ts;
			dirty = true;
			changes++;
		}
	}

	/** Forgets a world's marks; {@code tab} > 0 sets the Goals tab's last look (may move back: tests and dev only). */
	public void resetWorld(String world, long tab) {
		World w = new World();
		w.tab = Math.max(0, tab);
		World old = worlds.put(world, w);
		if (old != null) {
			// the Inbox's marks are not the Goals tab's: resetInbox forgets them
			w.inbox.all = old.inbox.all;
			w.inbox.items.putAll(old.inbox.items);
			w.inbox.agents.putAll(old.inbox.agents);
		}
		dirty = true;
		changes++;
	}

	/** Marks a goal seen at {@code ts} (never moves back). */
	public void markGoal(String world, String goalId, long ts) {
		World w = worlds.computeIfAbsent(world, k -> new World());
		Long prev = w.goals.get(goalId);
		if (prev == null || ts > prev) {
			w.goals.remove(goalId);
			w.goals.put(goalId, ts); // re-insert: iteration order = least recently seen first
			dirty = true;
			changes++;
			while (w.goals.size() > MAX_GOALS) {
				w.goals.remove(w.goals.keySet().iterator().next());
			}
		}
	}

	/**
	 * Whether a digest should be asked for on opening the Goals tab at {@code now}: it was looked at before
	 * and at least {@link #AWAY_MS} ago. Never on the very first look (there is nothing to compare with).
	 */
	public boolean away(String world, long now) {
		long t = tabSeen(world);
		return t > 0 && now - t >= AWAY_MS;
	}

	// ------------------------------------------------------------------ inbox (wave 2)

	/** A counter that moves on every change of any mark (cache key). */
	public long changes() {
		return changes;
	}

	/** When "Mark all read" was last pressed in {@code world}'s Inbox, 0 = never. */
	public long inboxAll(String world) {
		World w = worlds.get(world);
		return w == null ? 0 : w.inbox.all;
	}

	/** "Mark all read" at {@code ts} (never moves back). */
	public void markInboxAll(String world, long ts) {
		World w = worlds.computeIfAbsent(world, k -> new World());
		if (ts > w.inbox.all) {
			w.inbox.all = ts;
			dirty = true;
			changes++;
		}
	}

	/** An Inbox item was viewed at {@code ts} (never moves back; at most {@link #MAX_INBOX_ITEMS} kept). */
	public void markInboxItem(String world, String key, long ts) {
		World w = worlds.computeIfAbsent(world, k -> new World());
		Long prev = w.inbox.items.get(key);
		if (prev == null || ts > prev) {
			w.inbox.items.remove(key);
			w.inbox.items.put(key, ts);
			dirty = true;
			changes++;
			while (w.inbox.items.size() > MAX_INBOX_ITEMS) {
				w.inbox.items.remove(w.inbox.items.keySet().iterator().next());
			}
		}
	}

	/** An agent's card (or its Inbox view) was looked at {@code ts}: its replies up to then are read. */
	public void markAgent(String world, String agentId, long ts) {
		World w = worlds.computeIfAbsent(world, k -> new World());
		Long prev = w.inbox.agents.get(agentId);
		if (prev == null || ts > prev) {
			w.inbox.agents.put(agentId, ts);
			dirty = true;
			changes++;
		}
	}

	/** Forgets a world's Inbox marks (dev and tests): everything is unread again. */
	public void resetInbox(String world) {
		World w = worlds.get(world);
		if (w != null && !w.inbox.empty()) {
			w.inbox.all = 0;
			w.inbox.items.clear();
			w.inbox.agents.clear();
			dirty = true;
			changes++;
		}
	}

	/** The item's own mark, 0 = never viewed. */
	public long inboxItem(String world, String key) {
		World w = worlds.get(world);
		Long t = w == null ? null : w.inbox.items.get(key);
		return t == null ? 0 : t;
	}

	/**
	 * Whether an Inbox item that happened at {@code ts} is read: not after the newest of "Mark all read", its own mark,
	 * its agent's mark ({@code agentId}, the agent card) and its goal's own mark ({@code goalId}, opened in the goal
	 * thread; the Goals tab's list mark does not count).
	 */
	public boolean inboxRead(String world, String key, long ts, @Nullable String agentId, @Nullable String goalId) {
		World w = worlds.get(world);
		if (w == null) {
			return false;
		}
		long mark = Math.max(w.inbox.all, w.inbox.items.getOrDefault(key, 0L));
		if (agentId != null) {
			mark = Math.max(mark, w.inbox.agents.getOrDefault(agentId, 0L));
		}
		if (goalId != null) {
			mark = Math.max(mark, w.goals.getOrDefault(goalId, 0L));
		}
		return ts <= mark;
	}

	/** Changed since the last {@link #save}/{@link #load}. */
	public boolean dirty() {
		return dirty;
	}

	// ------------------------------------------------------------------ JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 2);
		JsonObject ws = new JsonObject();
		for (var e : worlds.entrySet()) {
			JsonObject w = new JsonObject();
			w.addProperty("tab", e.getValue().tab);
			JsonObject gs = new JsonObject();
			e.getValue().goals.forEach(gs::addProperty);
			w.add("goals", gs);
			Inbox in = e.getValue().inbox;
			if (!in.empty()) {
				JsonObject ib = new JsonObject();
				ib.addProperty("all", in.all);
				JsonObject items = new JsonObject();
				in.items.forEach(items::addProperty);
				ib.add("items", items);
				JsonObject agents = new JsonObject();
				in.agents.forEach(agents::addProperty);
				ib.add("agents", agents);
				w.add("inbox", ib);
			}
			ws.add(e.getKey(), w);
		}
		o.add("worlds", ws);
		return o;
	}

	/** Parses {@link #toJson} output; anything malformed is skipped (a broken file must never break the hub). */
	public static HubSeen fromJson(@Nullable JsonElement el) {
		HubSeen s = new HubSeen();
		if (el == null || !el.isJsonObject()) {
			return s;
		}
		JsonElement ws = el.getAsJsonObject().get("worlds");
		if (ws == null || !ws.isJsonObject()) {
			return s;
		}
		for (var e : ws.getAsJsonObject().entrySet()) {
			if (!e.getValue().isJsonObject()) {
				continue;
			}
			JsonObject w = e.getValue().getAsJsonObject();
			World world = new World();
			world.tab = longOf(w.get("tab"));
			JsonElement gs = w.get("goals");
			if (gs != null && gs.isJsonObject()) {
				for (var g : gs.getAsJsonObject().entrySet()) {
					long t = longOf(g.getValue());
					if (t > 0) {
						world.goals.put(g.getKey(), t);
					}
				}
			}
			JsonElement ib = w.get("inbox");
			if (ib != null && ib.isJsonObject()) {
				JsonObject in = ib.getAsJsonObject();
				world.inbox.all = longOf(in.get("all"));
				readMarks(in.get("items"), world.inbox.items);
				readMarks(in.get("agents"), world.inbox.agents);
			}
			s.worlds.put(e.getKey(), world);
		}
		return s;
	}

	private static void readMarks(@Nullable JsonElement el, Map<String, Long> into) {
		if (el == null || !el.isJsonObject()) {
			return;
		}
		for (var m : el.getAsJsonObject().entrySet()) {
			long t = longOf(m.getValue());
			if (t > 0) {
				into.put(m.getKey(), t);
			}
		}
	}

	private static long longOf(@Nullable JsonElement e) {
		try {
			return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? Math.max(0, e.getAsLong()) : 0;
		} catch (NumberFormatException x) {
			return 0;
		}
	}

	/** Reads {@code file}; a missing or unreadable file gives an empty record. */
	public static HubSeen load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new HubSeen();
		}
		try {
			return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) {
			return new HubSeen();
		}
	}

	/** Writes {@code file} atomically (temp file + move). */
	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(toJson()), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		dirty = false;
	}
}
