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
 * { "version": 1, "worlds": { "New World": { "tab": 1759500000000, "goals": { "g3": 1759500100000 } } } }
 * </pre>
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

	private static final class World {
		long tab;
		final Map<String, Long> goals = new LinkedHashMap<>();
	}

	private final Map<String, World> worlds = new LinkedHashMap<>();
	private boolean dirty;

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
		}
	}

	/** Marks a goal seen at {@code ts} (never moves back). */
	public void markGoal(String world, String goalId, long ts) {
		World w = worlds.computeIfAbsent(world, k -> new World());
		Long prev = w.goals.get(goalId);
		if (prev == null || ts > prev) {
			w.goals.remove(goalId);
			w.goals.put(goalId, ts); // re-insert: iteration order = least recently seen first
			dirty = true;
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

	/** Changed since the last {@link #save}/{@link #load}. */
	public boolean dirty() {
		return dirty;
	}

	// ------------------------------------------------------------------ JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		JsonObject ws = new JsonObject();
		for (var e : worlds.entrySet()) {
			JsonObject w = new JsonObject();
			w.addProperty("tab", e.getValue().tab);
			JsonObject gs = new JsonObject();
			e.getValue().goals.forEach(gs::addProperty);
			w.add("goals", gs);
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
			s.worlds.put(e.getKey(), world);
		}
		return s;
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
