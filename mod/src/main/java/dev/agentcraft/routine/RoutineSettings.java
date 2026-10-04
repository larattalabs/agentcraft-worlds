package dev.agentcraft.routine;

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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The village routines per world, client side ({@code <gameDir>/agentcraft/routines.json}; hub > Buildings toggles
 * them): "Night routine", "Stand-ups", "Library visits" (docs/VILLAGE.md V3). Default on: a world or a toggle without
 * an entry is on. Pure; the client thread owns it.
 *
 * <pre>
 * { "version": 1, "worlds": { "New World": { "night": false, "standups": true, "library": true } } }
 * </pre>
 */
public final class RoutineSettings {
	public static final String FILE = "routines.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** One toggle; {@link #key} is its JSON field and DevBridge name. */
	public enum Toggle {
		NIGHT("night", "Night routine"),
		STANDUPS("standups", "Stand-ups"),
		LIBRARY("library", "Library visits");

		public final String key;
		public final String label;

		Toggle(String key, String label) {
			this.key = key;
			this.label = label;
		}

		/** The toggle for a JSON key or name ("night", "stand-ups", "library"), or null. */
		public static @Nullable Toggle parse(@Nullable String s) {
			if (s == null) {
				return null;
			}
			String k = s.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
			for (Toggle t : values()) {
				if (t.key.equals(k) || t.name().toLowerCase(Locale.ROOT).equals(k) || t.label.toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "").equals(k)) {
					return t;
				}
			}
			return switch (k) {
				case "rest", "nightroutine" -> NIGHT;
				case "standup" -> STANDUPS;
				case "libraryvisits", "visits" -> LIBRARY;
				default -> null;
			};
		}
	}

	private final Map<String, EnumMap<Toggle, Boolean>> worlds = new LinkedHashMap<>();
	private boolean dirty;

	public boolean enabled(String world, Toggle t) {
		EnumMap<Toggle, Boolean> w = worlds.get(world);
		Boolean b = w == null ? null : w.get(t);
		return b == null || b;
	}

	/** Sets one toggle for {@code world}; true when it changed. */
	public boolean set(String world, Toggle t, boolean on) {
		EnumMap<Toggle, Boolean> w = worlds.computeIfAbsent(world, k -> new EnumMap<>(Toggle.class));
		Boolean prev = w.put(t, on);
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
			e.getValue().forEach((t, on) -> w.addProperty(t.key, on));
			ws.add(e.getKey(), w);
		}
		o.add("worlds", ws);
		return o;
	}

	/** Parses {@link #toJson} output; anything malformed is skipped (that toggle stays on). */
	public static RoutineSettings fromJson(@Nullable JsonElement el) {
		RoutineSettings s = new RoutineSettings();
		if (el == null || !el.isJsonObject() || !(el.getAsJsonObject().get("worlds") instanceof JsonObject ws)) {
			return s;
		}
		for (var e : ws.entrySet()) {
			if (!(e.getValue() instanceof JsonObject w)) {
				continue;
			}
			for (Toggle t : Toggle.values()) {
				JsonElement v = w.get(t.key);
				if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
					s.worlds.computeIfAbsent(e.getKey(), k -> new EnumMap<>(Toggle.class)).put(t, v.getAsBoolean());
				}
			}
		}
		return s;
	}

	/** Reads {@code file}; a missing or unreadable file gives the defaults. */
	public static RoutineSettings load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new RoutineSettings();
		}
		try {
			return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) {
			return new RoutineSettings();
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
