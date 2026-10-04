package dev.agentcraft.hud;

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
 * Per-world HUD memory (docs/WAVE2.md W5-W7), {@code <gameDir>/agentcraft/hub-hud.json}, a sibling of
 * {@code hub-seen.json} (which holds the read marks): the hub's last tab ({@code H} reopens it), when the hub
 * was last on screen (the away toast), when the away toast was last shown, when free-floating agent
 * replies were last seen (the console), and whether the welcome card was dismissed. Pure, unit-tested
 * ({@code HudPrefsTest}); the client keeps one instance and saves it. Not thread-safe (client thread).
 *
 * <pre>
 * { "version": 1, "worlds": { "New World": { "lastTab": "goals", "hubSeenAt": 1759500000000,
 *   "lastAwayToastAt": 1759500000000, "repliesSeen": 1759500000000, "welcomeDismissed": true } } }
 * </pre>
 */
public final class HudPrefs {
	public static final String FILE = "hub-hud.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final class World {
		@Nullable String lastTab;
		long hubSeenAt;
		long lastAwayToastAt;
		long repliesSeen;
		boolean welcomeDismissed;
	}

	private final Map<String, World> worlds = new LinkedHashMap<>();
	private boolean dirty;

	private World w(String world) {
		return worlds.computeIfAbsent(world, k -> new World());
	}

	/** Whether {@code world} has an entry yet (its first join with this file). */
	public boolean known(String world) {
		return worlds.containsKey(world);
	}

	public @Nullable String lastTab(String world) {
		World x = worlds.get(world);
		return x == null ? null : x.lastTab;
	}

	public void setLastTab(String world, @Nullable String tab) {
		World x = w(world);
		if (!java.util.Objects.equals(x.lastTab, tab)) {
			x.lastTab = tab;
			dirty = true;
		}
	}

	/** When the hub was last on screen in {@code world}, 0 = never. */
	public long hubSeenAt(String world) {
		World x = worlds.get(world);
		return x == null ? 0 : x.hubSeenAt;
	}

	/** Never moves back (except {@link #setHubSeenAtForTest}). */
	public void markHubSeen(String world, long ts) {
		World x = w(world);
		if (ts > x.hubSeenAt) {
			x.hubSeenAt = ts;
			dirty = true;
		}
	}

	/** Dev ({@code dev.away}): pretend the hub was last open at {@code ts}. */
	public void setHubSeenAtForTest(String world, long ts) {
		w(world).hubSeenAt = Math.max(0, ts);
		dirty = true;
	}

	/** When the away toast was last shown, 0 = never (the next one covers what happened since then). */
	public long lastAwayToastAt(String world) {
		World x = worlds.get(world);
		return x == null ? 0 : x.lastAwayToastAt;
	}

	public void setLastAwayToastAt(String world, long since) {
		World x = w(world);
		if (x.lastAwayToastAt != since) {
			x.lastAwayToastAt = since;
			dirty = true;
		}
	}

	/** When the player last saw the replies that belong to no goal (console open), 0 = never. */
	public long repliesSeen(String world) {
		World x = worlds.get(world);
		return x == null ? 0 : x.repliesSeen;
	}

	public void markRepliesSeen(String world, long ts) {
		World x = w(world);
		if (ts > x.repliesSeen) {
			x.repliesSeen = ts;
			dirty = true;
		}
	}

	public boolean welcomeDismissed(String world) {
		World x = worlds.get(world);
		return x != null && x.welcomeDismissed;
	}

	public void setWelcomeDismissed(String world, boolean dismissed) {
		World x = w(world);
		if (x.welcomeDismissed != dismissed) {
			x.welcomeDismissed = dismissed;
			dirty = true;
		}
	}

	public boolean dirty() {
		return dirty;
	}

	// ------------------------------------------------------------------ JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		JsonObject ws = new JsonObject();
		for (var e : worlds.entrySet()) {
			World x = e.getValue();
			JsonObject j = new JsonObject();
			if (x.lastTab != null) {
				j.addProperty("lastTab", x.lastTab);
			}
			j.addProperty("hubSeenAt", x.hubSeenAt);
			j.addProperty("lastAwayToastAt", x.lastAwayToastAt);
			j.addProperty("repliesSeen", x.repliesSeen);
			j.addProperty("welcomeDismissed", x.welcomeDismissed);
			ws.add(e.getKey(), j);
		}
		o.add("worlds", ws);
		return o;
	}

	/** Parses {@link #toJson} output; anything malformed is skipped (a broken file must never break the HUD). */
	public static HudPrefs fromJson(@Nullable JsonElement el) {
		HudPrefs p = new HudPrefs();
		if (el == null || !el.isJsonObject()) {
			return p;
		}
		JsonElement ws = el.getAsJsonObject().get("worlds");
		if (ws == null || !ws.isJsonObject()) {
			return p;
		}
		for (var e : ws.getAsJsonObject().entrySet()) {
			if (!e.getValue().isJsonObject()) {
				continue;
			}
			JsonObject j = e.getValue().getAsJsonObject();
			World x = new World();
			JsonElement t = j.get("lastTab");
			x.lastTab = t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isString() ? t.getAsString() : null;
			x.hubSeenAt = longOf(j.get("hubSeenAt"));
			x.lastAwayToastAt = longOf(j.get("lastAwayToastAt"));
			x.repliesSeen = longOf(j.get("repliesSeen"));
			JsonElement d = j.get("welcomeDismissed");
			x.welcomeDismissed = d != null && d.isJsonPrimitive() && d.getAsJsonPrimitive().isBoolean() && d.getAsBoolean();
			p.worlds.put(e.getKey(), x);
		}
		return p;
	}

	private static long longOf(@Nullable JsonElement e) {
		try {
			return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? Math.max(0, e.getAsLong()) : 0;
		} catch (NumberFormatException x) {
			return 0;
		}
	}

	/** Reads {@code file}; a missing or unreadable file gives an empty record. */
	public static HudPrefs load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new HudPrefs();
		}
		try {
			return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) {
			return new HudPrefs();
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
