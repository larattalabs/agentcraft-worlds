package dev.agentcraft.trophy;

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
 * "Trophies for merges and finished goals", per world, client side ({@code <gameDir>/agentcraft/trophies.json}; the
 * hub's Buildings tab toggles it). Default on: a world without an entry hangs trophies. Pure; the client thread owns it.
 *
 * <pre>
 * { "version": 1, "worlds": { "New World": { "trophies": false } } }
 * </pre>
 */
public final class TrophySettings {
	public static final String FILE = "trophies.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Map<String, Boolean> worlds = new LinkedHashMap<>();
	private boolean dirty;

	public boolean enabled(String world) {
		return worlds.getOrDefault(world, true);
	}

	/** Sets the toggle for {@code world}; true when it changed. */
	public boolean set(String world, boolean on) {
		Boolean prev = worlds.put(world, on);
		boolean changed = prev == null ? !on : prev != on;
		dirty |= changed || prev == null;
		return changed;
	}

	public boolean dirty() {
		return dirty;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		JsonObject ws = new JsonObject();
		for (var e : worlds.entrySet()) {
			JsonObject w = new JsonObject();
			w.addProperty("trophies", e.getValue());
			ws.add(e.getKey(), w);
		}
		o.add("worlds", ws);
		return o;
	}

	/** Parses {@link #toJson} output; anything malformed is skipped (trophies stay on there). */
	public static TrophySettings fromJson(@Nullable JsonElement el) {
		TrophySettings s = new TrophySettings();
		if (el == null || !el.isJsonObject() || !(el.getAsJsonObject().get("worlds") instanceof JsonObject ws)) {
			return s;
		}
		for (var e : ws.entrySet()) {
			if (e.getValue() instanceof JsonObject w && w.get("trophies") != null && w.get("trophies").isJsonPrimitive()
				&& w.get("trophies").getAsJsonPrimitive().isBoolean()) {
				s.worlds.put(e.getKey(), w.get("trophies").getAsBoolean());
			}
		}
		return s;
	}

	/** Reads {@code file}; a missing or unreadable file gives the defaults. */
	public static TrophySettings load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new TrophySettings();
		}
		try {
			return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) {
			return new TrophySettings();
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
