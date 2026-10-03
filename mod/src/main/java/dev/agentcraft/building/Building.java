package dev.agentcraft.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A placed blueprint (docs/BUILDINGS.md "Buildings in a world"). Pure data, persisted by
 * {@link Buildings} in {@code <world>/agentcraft-buildings.json}.
 *
 * @param rotation lower-case Minecraft {@code Rotation} name ({@code none}, {@code clockwise_90}, ...)
 * @param box the world block box the template occupies (inclusive); its minimum corner is the origin.
 *            The snapshot taken before placement covers exactly this box.
 * @param bounds the walkable region in world space (the layout bounds agents path inside)
 * @param anchors world-space anchors, rotated, wing names resolved
 * @param dimension the dimension it was placed in ({@code minecraft:overworld}, ...); null for records written before the
 *                  field existed (read as the overworld, see {@link #dimensionOrDefault}; the hub then falls back to
 *                  looking for its stations)
 */
public record Building(String id, String blueprint, List<String> repos, boolean home, String rotation, Anchors.Bounds box,
	Anchors.Bounds bounds, Map<String, Anchor> anchors, long placedAt, @Nullable String dimension) {
	/** What a record without a dimension is assumed to be in. */
	public static final String OVERWORLD = "minecraft:overworld";

	public Building {
		repos = List.copyOf(repos);
		anchors = Collections.unmodifiableMap(new LinkedHashMap<>(anchors));
	}

	public Building withHome(boolean h) {
		return new Building(id, blueprint, repos, h, rotation, box, bounds, anchors, placedAt, dimension);
	}

	/** The recorded dimension, or {@link #OVERWORLD} for an old record without one. */
	public String dimensionOrDefault() {
		return dimension != null ? dimension : OVERWORLD;
	}

	/**
	 * This building as an anchor layout (name {@code building:<id>}, revision = {@link #placedAt}, so a
	 * replaced building at the same id reads as a new layout).
	 */
	public Anchors.Layout layout() {
		return new Anchors.Layout("building:" + id, placedAt, bounds, anchors);
	}

	public boolean hasRepo(String repoId) {
		return repos.contains(repoId);
	}

	// ------------------------------------------------------------------ JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("blueprint", blueprint);
		JsonArray r = new JsonArray();
		repos.forEach(r::add);
		o.add("repos", r);
		o.addProperty("home", home);
		JsonArray origin = new JsonArray();
		origin.add(box.minX());
		origin.add(box.minY());
		origin.add(box.minZ());
		o.add("origin", origin);
		o.addProperty("rotation", rotation);
		o.add("box", boundsJson(box));
		o.add("bounds", boundsJson(bounds));
		JsonObject a = new JsonObject();
		anchors.forEach((n, v) -> a.add(n, Anchors.anchorJson(v)));
		o.add("anchors", a);
		o.addProperty("placedAt", placedAt);
		if (dimension != null) {
			o.addProperty("dimension", dimension);
		}
		return o;
	}

	public static Building fromJson(JsonObject o) {
		List<String> repos = new ArrayList<>();
		for (JsonElement e : o.getAsJsonArray("repos")) {
			repos.add(e.getAsString());
		}
		Map<String, Anchor> anchors = new LinkedHashMap<>();
		if (o.has("anchors")) {
			for (var e : o.getAsJsonObject("anchors").entrySet()) {
				JsonObject a = e.getValue().getAsJsonObject();
				anchors.put(e.getKey(), new Anchor(e.getKey(), a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble(),
					a.has("yaw") ? a.get("yaw").getAsFloat() : 0f, a.has("pitch") ? a.get("pitch").getAsFloat() : 0f));
			}
		}
		Anchors.Bounds box = boundsFromJson(o.getAsJsonObject("box"));
		Anchors.Bounds bounds = o.has("bounds") ? boundsFromJson(o.getAsJsonObject("bounds")) : box;
		return new Building(o.get("id").getAsString(), o.get("blueprint").getAsString(), repos, o.has("home") && o.get("home").getAsBoolean(),
			o.has("rotation") ? o.get("rotation").getAsString() : "none", box, bounds, anchors,
			o.has("placedAt") ? o.get("placedAt").getAsLong() : 0L, o.has("dimension") ? o.get("dimension").getAsString() : null);
	}

	public static JsonObject boundsJson(Anchors.Bounds b) {
		JsonObject o = new JsonObject();
		o.addProperty("minX", b.minX());
		o.addProperty("minY", b.minY());
		o.addProperty("minZ", b.minZ());
		o.addProperty("maxX", b.maxX());
		o.addProperty("maxY", b.maxY());
		o.addProperty("maxZ", b.maxZ());
		return o;
	}

	public static Anchors.Bounds boundsFromJson(JsonObject b) {
		return new Anchors.Bounds(b.get("minX").getAsInt(), b.get("minY").getAsInt(), b.get("minZ").getAsInt(), b.get("maxX").getAsInt(),
			b.get("maxY").getAsInt(), b.get("maxZ").getAsInt());
	}

	/** True when two inclusive boxes share at least one block. */
	public static boolean intersects(Anchors.Bounds a, Anchors.Bounds b) {
		return a.minX() <= b.maxX() && a.maxX() >= b.minX() && a.minY() <= b.maxY() && a.maxY() >= b.minY() && a.minZ() <= b.maxZ()
			&& a.maxZ() >= b.minZ();
	}

	/** The buildings file: {@code {"next": 4, "buildings": [...]}}. {@code next} never goes back, so ids are never reused. */
	public static JsonObject fileJson(List<Building> buildings, int next) {
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("next", next);
		JsonArray arr = new JsonArray();
		buildings.forEach(b -> arr.add(b.toJson()));
		root.add("buildings", arr);
		return root;
	}

	/** Parsed buildings file. */
	public record FileData(List<Building> buildings, int next) {
	}

	public static FileData fileFromJson(JsonObject root) {
		List<Building> list = new ArrayList<>();
		int maxId = 0;
		for (JsonElement e : root.has("buildings") ? root.getAsJsonArray("buildings") : new JsonArray()) {
			Building b = fromJson(e.getAsJsonObject());
			list.add(b);
			maxId = Math.max(maxId, idNumber(b.id()));
		}
		int next = root.has("next") ? root.get("next").getAsInt() : 1;
		return new FileData(list, Math.max(next, maxId + 1));
	}

	/** {@code b12 -> 12}; 0 for other ids. */
	public static int idNumber(@Nullable String id) {
		if (id == null || id.length() < 2 || id.charAt(0) != 'b') {
			return 0;
		}
		try {
			return Integer.parseInt(id.substring(1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
