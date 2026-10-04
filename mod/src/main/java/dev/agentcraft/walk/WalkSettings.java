package dev.agentcraft.walk;

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
 * "Agents walk between buildings", per world, client side ({@code <gameDir>/agentcraft/walking.json}; the
 * hub's Buildings tab toggles it). Default on: a world without an entry walks. Pure; the client thread owns it.
 *
 * <pre>
 * { "version": 1, "worlds": { "New World": { "walk": false } } }
 * </pre>
 */
public final class WalkSettings {
	public static final String FILE = "walking.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Map<String, Boolean> walk = new LinkedHashMap<>();
	private boolean dirty;

	public boolean enabled(String world) {
		return walk.getOrDefault(world, true);
	}

	/** Sets the toggle for {@code world}; true when it changed. */
	public boolean set(String world, boolean on) {
		Boolean prev = walk.put(world, on);
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
		for (var e : walk.entrySet()) {
			JsonObject w = new JsonObject();
			w.addProperty("walk", e.getValue());
			ws.add(e.getKey(), w);
		}
		o.add("worlds", ws);
		return o;
	}

	/** Parses {@link #toJson} output; anything malformed is skipped (walking stays on there). */
	public static WalkSettings fromJson(@Nullable JsonElement el) {
		WalkSettings s = new WalkSettings();
		if (el == null || !el.isJsonObject() || !(el.getAsJsonObject().get("worlds") instanceof JsonObject ws)) {
			return s;
		}
		for (var e : ws.entrySet()) {
			if (e.getValue() instanceof JsonObject w && w.get("walk") != null && w.get("walk").isJsonPrimitive()
				&& w.get("walk").getAsJsonPrimitive().isBoolean()) {
				s.walk.put(e.getKey(), w.get("walk").getAsBoolean());
			}
		}
		return s;
	}

	/** Reads {@code file}; a missing or unreadable file gives the defaults. */
	public static WalkSettings load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new WalkSettings();
		}
		try {
			return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) {
			return new WalkSettings();
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
