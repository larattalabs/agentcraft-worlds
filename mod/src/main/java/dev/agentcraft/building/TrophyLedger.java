package dev.agentcraft.building;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What trophies a world has (docs/BUILDINGS.md "Trophies"), server side in {@code <world>/agentcraft-trophies.json}:
 * the keys ever awarded (an award is idempotent: a known key is never hung twice, even after its sign was replaced or
 * its building removed) and, per building, what hangs in each slot. Pure; {@link Trophies} owns it on the server
 * thread.
 *
 * <pre>
 * { "version": 1,
 *   "awarded": ["pr:agentcraft:612", "goal:g1:1759500000000"],
 *   "buildings": { "b3": { "trophy@1": { "key": "pr:agentcraft:612", "lines": ["Merged PR #612", "...", "", "2026-10-04"], "at": 1759 } } } }
 * </pre>
 */
public final class TrophyLedger {
	public static final String FILE = "agentcraft-trophies.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** What hangs in a slot: the trophy's key, its sign lines, when it was hung (ms). */
	public record Entry(String key, List<String> lines, long at) {
		public Entry {
			lines = List.copyOf(lines);
		}
	}

	private final Set<String> awarded = new LinkedHashSet<>();
	/** building id -> slot name (raw anchor name) -> entry. */
	private final Map<String, Map<String, Entry>> buildings = new LinkedHashMap<>();

	public boolean awarded(String key) {
		return awarded.contains(key);
	}

	/** Records {@code key} as awarded; true when it was new. */
	public boolean markAwarded(String key) {
		return awarded.add(key);
	}

	public Set<String> awardedKeys() {
		return Collections.unmodifiableSet(awarded);
	}

	/** A building's slots (slot name -> entry), empty when it has none. */
	public Map<String, Entry> slots(String buildingId) {
		Map<String, Entry> m = buildings.get(buildingId);
		return m == null ? Map.of() : Collections.unmodifiableMap(m);
	}

	public Set<String> buildingIds() {
		return Collections.unmodifiableSet(buildings.keySet());
	}

	/** Puts {@code e} in a building's slot (replacing what hung there; its key stays awarded). */
	public void put(String buildingId, String slot, Entry e) {
		buildings.computeIfAbsent(buildingId, k -> new LinkedHashMap<>()).put(slot, e);
	}

	/** Empties one slot. */
	public void clear(String buildingId, String slot) {
		Map<String, Entry> m = buildings.get(buildingId);
		if (m != null) {
			m.remove(slot);
			if (m.isEmpty()) {
				buildings.remove(buildingId);
			}
		}
	}

	/** Forgets a building's slots (removed or forgotten); its keys stay awarded. True when it had any. */
	public boolean dropBuilding(String buildingId) {
		return buildings.remove(buildingId) != null;
	}

	// ------------------------------------------------------------------ JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		JsonArray keys = new JsonArray();
		awarded.forEach(keys::add);
		o.add("awarded", keys);
		JsonObject bs = new JsonObject();
		buildings.forEach((id, slots) -> {
			JsonObject so = new JsonObject();
			slots.forEach((name, e) -> {
				JsonObject eo = new JsonObject();
				eo.addProperty("key", e.key());
				JsonArray ls = new JsonArray();
				e.lines().forEach(ls::add);
				eo.add("lines", ls);
				eo.addProperty("at", e.at());
				so.add(name, eo);
			});
			bs.add(id, so);
		});
		o.add("buildings", bs);
		return o;
	}

	/** Parses {@link #toJson} output; malformed parts are skipped (never throws). */
	public static TrophyLedger fromJson(@Nullable JsonElement el) {
		TrophyLedger l = new TrophyLedger();
		if (el == null || !el.isJsonObject()) {
			return l;
		}
		JsonObject o = el.getAsJsonObject();
		if (o.get("awarded") instanceof JsonArray keys) {
			for (JsonElement k : keys) {
				String s = string(k);
				if (s != null && !s.isEmpty()) {
					l.awarded.add(s);
				}
			}
		}
		if (o.get("buildings") instanceof JsonObject bs) {
			for (var b : bs.entrySet()) {
				if (!(b.getValue() instanceof JsonObject so)) {
					continue;
				}
				for (var s : so.entrySet()) {
					Entry e = entry(s.getValue());
					if (e != null) {
						l.put(b.getKey(), s.getKey(), e);
						l.awarded.add(e.key()); // what hangs was awarded, even if the key list lost it
					}
				}
			}
		}
		return l;
	}

	private static @Nullable Entry entry(JsonElement el) {
		if (!(el instanceof JsonObject eo)) {
			return null;
		}
		String key = string(eo.get("key"));
		if (key == null || key.isEmpty()) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		if (eo.get("lines") instanceof JsonArray ls) {
			for (JsonElement x : ls) {
				String s = string(x);
				lines.add(s == null ? "" : s);
			}
		}
		while (lines.size() < TrophyText.LINES) {
			lines.add("");
		}
		long at = 0L;
		if (eo.get("at") != null && eo.get("at").isJsonPrimitive() && eo.get("at").getAsJsonPrimitive().isNumber()) {
			at = eo.get("at").getAsLong();
		}
		return new Entry(key, lines.subList(0, TrophyText.LINES), at);
	}

	private static @Nullable String string(@Nullable JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	/**
	 * Reads {@code file}: a missing file is an empty ledger, malformed parts are skipped; throws when the file cannot be
	 * read or is not JSON at all (the caller then leaves it alone rather than overwrite what it could not read).
	 */
	public static TrophyLedger read(Path file) throws IOException {
		if (!Files.exists(file)) {
			return new TrophyLedger();
		}
		try {
			return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		} catch (RuntimeException e) {
			throw new IOException(file.getFileName() + " is not valid JSON: " + e.getMessage(), e);
		}
	}

	/** Writes {@code file} atomically (temp file + move). */
	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(toJson()), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
