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
 * @param snapshotBox the box the snapshot covers when it is larger than {@code box} (the foundation fill below the
 *                    template, docs/BUILDINGS.md "Terrain fit"); null = {@code box}. See {@link #restoreBox()}.
 * @param revision the layout revision: {@code placedAt} at placement, bumped when the repos change or the building
 *                 moves, so every layout consumer notices new anchors
 * @param movedFrom where the building stood before its last move (the hub's "Undo move"), null when it never moved
 */
public record Building(String id, String blueprint, List<String> repos, boolean home, String rotation, Anchors.Bounds box,
	Anchors.Bounds bounds, Map<String, Anchor> anchors, long placedAt, @Nullable String dimension, Anchors.@Nullable Bounds snapshotBox,
	long revision, @Nullable Site movedFrom) {
	/** What a record without a dimension is assumed to be in. */
	public static final String OVERWORLD = "minecraft:overworld";

	/** A building's former site: its box's minimum corner, rotation and dimension. */
	public record Site(int x, int y, int z, String rotation, String dimension) {
	}

	public Building {
		repos = List.copyOf(repos);
		anchors = Collections.unmodifiableMap(new LinkedHashMap<>(anchors));
		if (snapshotBox != null && snapshotBox.equals(box)) {
			snapshotBox = null;
		}
	}

	/** A record as placed before terrain fit, repo edits and moves existed (revision = placedAt). */
	public Building(String id, String blueprint, List<String> repos, boolean home, String rotation, Anchors.Bounds box, Anchors.Bounds bounds,
		Map<String, Anchor> anchors, long placedAt, @Nullable String dimension) {
		this(id, blueprint, repos, home, rotation, box, bounds, anchors, placedAt, dimension, null, placedAt, null);
	}

	public Building withHome(boolean h) {
		return new Building(id, blueprint, repos, h, rotation, box, bounds, anchors, placedAt, dimension, snapshotBox, revision, movedFrom);
	}

	/** The same building for other repos (anchors re-derived by the caller), as a new layout revision. */
	public Building withRepos(List<String> newRepos, Map<String, Anchor> newAnchors, long newRevision) {
		return new Building(id, blueprint, newRepos, home, rotation, box, bounds, newAnchors, placedAt, dimension, snapshotBox, newRevision, movedFrom);
	}

	/** The box {@link Buildings#remove} restores: the snapshot's box (the template box plus any foundation fill below it). */
	public Anchors.Bounds restoreBox() {
		return snapshotBox != null ? snapshotBox : box;
	}

	/** This building's site (for a later "Undo move"). */
	public Site site() {
		return new Site(box.minX(), box.minY(), box.minZ(), rotation, dimensionOrDefault());
	}

	/** The recorded dimension, or {@link #OVERWORLD} for an old record without one. */
	public String dimensionOrDefault() {
		return dimension != null ? dimension : OVERWORLD;
	}

	/**
	 * This building as an anchor layout (name {@code building:<id>}, revision = {@link #revision}: a replaced,
	 * re-repoed or moved building at the same id reads as a new layout).
	 */
	public Anchors.Layout layout() {
		return new Anchors.Layout("building:" + id, revision, bounds, anchors);
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
		if (snapshotBox != null) {
			o.add("snapshotBox", boundsJson(snapshotBox));
		}
		if (revision != placedAt) {
			o.addProperty("revision", revision);
		}
		if (movedFrom != null) {
			JsonObject m = new JsonObject();
			m.addProperty("x", movedFrom.x());
			m.addProperty("y", movedFrom.y());
			m.addProperty("z", movedFrom.z());
			m.addProperty("rotation", movedFrom.rotation());
			m.addProperty("dimension", movedFrom.dimension());
			o.add("movedFrom", m);
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
		long placedAt = o.has("placedAt") ? o.get("placedAt").getAsLong() : 0L;
		Site moved = null;
		if (o.has("movedFrom") && o.get("movedFrom").isJsonObject()) {
			JsonObject m = o.getAsJsonObject("movedFrom");
			moved = new Site(m.get("x").getAsInt(), m.get("y").getAsInt(), m.get("z").getAsInt(),
				m.has("rotation") ? m.get("rotation").getAsString() : "none", m.has("dimension") ? m.get("dimension").getAsString() : OVERWORLD);
		}
		return new Building(o.get("id").getAsString(), o.get("blueprint").getAsString(), repos, o.has("home") && o.get("home").getAsBoolean(),
			o.has("rotation") ? o.get("rotation").getAsString() : "none", box, bounds, anchors, placedAt,
			o.has("dimension") ? o.get("dimension").getAsString() : null,
			o.has("snapshotBox") ? boundsFromJson(o.getAsJsonObject("snapshotBox")) : null,
			o.has("revision") ? o.get("revision").getAsLong() : placedAt, moved);
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

	/**
	 * A site whose building was taken down (removed, or moved away) since the world last saved: its snapshot is
	 * kept until a save has reached the disk, because the restored blocks only live in memory until then
	 * (docs/BUILDINGS.md "Crash safety").
	 *
	 * @param building the record as it was before the removal (its box, blueprint and dimension)
	 * @param snapshot the snapshot's file name in the snapshot folder
	 * @param at when it was taken down (ms)
	 * @param why {@code removed} or {@code moved}
	 */
	public record Pending(Building building, String snapshot, long at, String why) {
		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.add("building", building.toJson());
			o.addProperty("snapshot", snapshot);
			o.addProperty("at", at);
			o.addProperty("why", why);
			return o;
		}

		public static Pending fromJson(JsonObject o) {
			return new Pending(Building.fromJson(o.getAsJsonObject("building")), o.get("snapshot").getAsString(),
				o.has("at") ? o.get("at").getAsLong() : 0L, o.has("why") ? o.get("why").getAsString() : "removed");
		}
	}

	/** The buildings file: {@code {"next": 4, "buildings": [...]}}. {@code next} never goes back, so ids are never reused. */
	public static JsonObject fileJson(List<Building> buildings, int next) {
		return fileJson(buildings, next, List.of());
	}

	/** The buildings file with the sites taken down since the last save ({@code "pending": [...]}, left out when empty). */
	public static JsonObject fileJson(List<Building> buildings, int next, List<Pending> pending) {
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("next", next);
		JsonArray arr = new JsonArray();
		buildings.forEach(b -> arr.add(b.toJson()));
		root.add("buildings", arr);
		if (!pending.isEmpty()) {
			JsonArray p = new JsonArray();
			pending.forEach(x -> p.add(x.toJson()));
			root.add("pending", p);
		}
		return root;
	}

	/** Parsed buildings file. */
	public record FileData(List<Building> buildings, int next, List<Pending> pending) {
		public FileData {
			buildings = List.copyOf(buildings);
			pending = List.copyOf(pending);
		}
	}

	public static FileData fileFromJson(JsonObject root) {
		List<Building> list = new ArrayList<>();
		int maxId = 0;
		for (JsonElement e : root.has("buildings") ? root.getAsJsonArray("buildings") : new JsonArray()) {
			Building b = fromJson(e.getAsJsonObject());
			list.add(b);
			maxId = Math.max(maxId, idNumber(b.id()));
		}
		List<Pending> pending = new ArrayList<>();
		for (JsonElement e : root.has("pending") ? root.getAsJsonArray("pending") : new JsonArray()) {
			Pending p = Pending.fromJson(e.getAsJsonObject());
			pending.add(p);
			maxId = Math.max(maxId, idNumber(p.building().id()));
		}
		int next = root.has("next") ? root.get("next").getAsInt() : 1;
		return new FileData(list, Math.max(next, maxId + 1), pending);
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
