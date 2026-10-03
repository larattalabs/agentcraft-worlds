package dev.agentcraft.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Edits staged in a hub form until Apply (one {@code config.set}) or Revert: key -> new value. Setting a
 * key back to its current value drops the edit, so {@link #changes()} is always exactly the diff. When the
 * settings are fetched again (config.changed, after Apply, a reconnect) {@link #rebase} keeps the edits and
 * drops only those that now equal the current value.
 */
public final class Staged {
	private final Map<String, JsonElement> edits = new LinkedHashMap<>();

	/** Stages {@code value} for {@code key}; equal to {@code current} = the edit goes away. */
	public void set(String key, @Nullable JsonElement value, @Nullable JsonElement current) {
		JsonElement v = value == null ? JsonNull.INSTANCE : value.deepCopy();
		if (SettingsLogic.same(v, current)) {
			edits.remove(key);
		} else {
			edits.put(key, v);
		}
	}

	/** The staged value, else {@code current}. */
	public @Nullable JsonElement value(String key, @Nullable JsonElement current) {
		JsonElement v = edits.get(key);
		return v != null ? v : current;
	}

	public boolean has(String key) {
		return edits.containsKey(key);
	}

	public void remove(String key) {
		edits.remove(key);
	}

	public void clear() {
		edits.clear();
	}

	public boolean isEmpty() {
		return edits.isEmpty();
	}

	public int size() {
		return edits.size();
	}

	/** Read-only view, in the order the edits were made. */
	public Map<String, JsonElement> edits() {
		return Collections.unmodifiableMap(edits);
	}

	/** Drops the edits that now equal the current value (another client made the same change, or it applied). */
	public void rebase(Function<String, @Nullable JsonElement> current) {
		edits.entrySet().removeIf(e -> SettingsLogic.same(e.getValue(), current.apply(e.getKey())));
	}

	/** {@code [{key, value}]} for {@code config.set}. */
	public JsonArray changes() {
		JsonArray a = new JsonArray();
		for (var e : edits.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("key", e.getKey());
			o.add("value", e.getValue().deepCopy());
			a.add(o);
		}
		return a;
	}
}
