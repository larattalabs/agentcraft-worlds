package dev.agentcraft.building;

import com.google.gson.JsonObject;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A blueprint's sidecar ({@code <id>.blueprint.json}, docs/BUILDINGS.md "Blueprints"). Pure data: no
 * Minecraft types, so it can be parsed and tested without a game. The structure template that goes
 * with it is held by {@link Blueprints}.
 *
 * <p>All coordinates are template-local (origin = the template's minimum corner), unrotated.
 *
 * @param kind {@code single}, {@code group} or {@code fixture} (docs/VILLAGE.md V2: a small placeable object such as the
 *             village board; no repos, no lead, not a building for routing, leads or trophies)
 * @param wings how many repos the blueprint takes (single: 1, fixture: 0)
 * @param front the direction the entrance faces in the unrotated template ({@code north/east/south/west})
 * @param walk the walkable region (template-local block coordinates, inclusive); null when missing
 * @param foundationBlock the vanilla block id the placement fills below the floor with (docs/BUILDINGS.md "Terrain fit",
 *                        contract C4); {@link #DEFAULT_FOUNDATION} when the sidecar names none
 * @param approach the entrance approach placement builds in front of the door (docs/BUILDINGS.md "Entrance approach");
 *                 {@link Approach.Spec#DEFAULT} when the sidecar names none
 */
public record Blueprint(String id, String name, String description, String kind, int wings, int sizeX, int sizeY, int sizeZ,
	int groundY, String front, String materials, Anchors.@Nullable Bounds walk, Map<String, Anchor> anchors, String foundationBlock,
	Approach.Spec approach) {

	public static final Pattern ID = Pattern.compile("[a-z0-9_]+");
	public static final String SINGLE = "single";
	public static final String GROUP = "group";
	/** A placeable fixture (the village board): no repos, placed, moved and removed like a building (docs/VILLAGE.md V2). */
	public static final String FIXTURE = "fixture";
	/** Cast workers every blueprint needs a desk and a monitor for (docs/BUILDINGS.md). */
	public static final List<String> CAST_WORKERS = List.of("juniper", "kit", "wren", "rowan", "tove");
	/** What fills below a building's floor when the sidecar names no {@code foundationBlock}. */
	public static final String DEFAULT_FOUNDATION = "minecraft:stone_bricks";
	private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");

	/** Whether {@code id} looks like a namespaced block id ({@code minecraft:stone_bricks}). */
	public static boolean isBlockId(@Nullable String id) {
		return id != null && BLOCK_ID.matcher(id).matches();
	}

	public boolean isGroup() {
		return GROUP.equals(kind);
	}

	/** A fixture blueprint (no repos, never a building for routing, leads, trophies or "one building per repo"). */
	public boolean isFixture() {
		return FIXTURE.equals(kind);
	}

	/** Parses a sidecar. Throws {@link IllegalArgumentException} with a readable message when it is unusable. */
	public static Blueprint fromJson(JsonObject o) {
		String id = str(o, "id", null);
		if (id == null || !ID.matcher(id).matches()) {
			throw new IllegalArgumentException("missing or invalid \"id\" (expected [a-z0-9_]+): " + id);
		}
		String kind = str(o, "kind", SINGLE).toLowerCase(Locale.ROOT);
		if (!kind.equals(SINGLE) && !kind.equals(GROUP) && !kind.equals(FIXTURE)) {
			throw new IllegalArgumentException("\"kind\" must be single, group or fixture, not " + kind);
		}
		boolean fixture = kind.equals(FIXTURE);
		int wings = o.has("wings") ? o.get("wings").getAsInt() : fixture ? 0 : 1;
		if (fixture ? wings != 0 : wings < 1 || (kind.equals(SINGLE) && wings != 1)) {
			throw new IllegalArgumentException("bad \"wings\" " + wings + " for kind " + kind + (fixture ? " (a fixture has no wings: 0)" : ""));
		}
		if (!o.has("size") || !o.get("size").isJsonObject()) {
			throw new IllegalArgumentException("missing \"size\"");
		}
		JsonObject size = o.getAsJsonObject("size");
		int sx = size.get("x").getAsInt();
		int sy = size.get("y").getAsInt();
		int sz = size.get("z").getAsInt();
		if (sx < 1 || sy < 1 || sz < 1) {
			throw new IllegalArgumentException("bad \"size\" " + sx + "x" + sy + "x" + sz);
		}
		int groundY = o.has("groundY") ? o.get("groundY").getAsInt() : 0;
		String front = str(o, "front", "south").toLowerCase(Locale.ROOT);
		if (BlueprintTransform.directionIndex(front) < 0) {
			throw new IllegalArgumentException("\"front\" must be north/east/south/west, not " + front);
		}
		Anchors.Bounds walk = null;
		if (o.has("walk") && o.get("walk").isJsonObject()) {
			JsonObject w = o.getAsJsonObject("walk");
			walk = new Anchors.Bounds(w.get("minX").getAsInt(), w.get("minY").getAsInt(), w.get("minZ").getAsInt(),
				w.get("maxX").getAsInt(), w.get("maxY").getAsInt(), w.get("maxZ").getAsInt());
		}
		Map<String, Anchor> anchors = new LinkedHashMap<>();
		if (o.has("anchors") && o.get("anchors").isJsonObject()) {
			for (var e : o.getAsJsonObject("anchors").entrySet()) {
				if (fixture && e.getKey().indexOf('@') >= 0) {
					throw new IllegalArgumentException("a fixture has no wings: anchor " + e.getKey() + " has a wing suffix");
				}
				JsonObject a = e.getValue().getAsJsonObject();
				anchors.put(e.getKey(), new Anchor(e.getKey(), a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble(),
					a.has("yaw") ? a.get("yaw").getAsFloat() : 0f, a.has("pitch") ? a.get("pitch").getAsFloat() : 0f));
			}
		}
		String foundation = str(o, "foundationBlock", DEFAULT_FOUNDATION).strip().toLowerCase(Locale.ROOT);
		if (!foundation.isEmpty() && foundation.indexOf(':') < 0) {
			foundation = "minecraft:" + foundation;
		}
		return new Blueprint(id, str(o, "name", id), str(o, "description", ""), kind, wings, sx, sy, sz, groundY, front,
			str(o, "materials", ""), walk, Collections.unmodifiableMap(anchors), foundation.isEmpty() ? DEFAULT_FOUNDATION : foundation,
			Approach.Spec.fromJson(o.get("approach")));
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("name", name);
		o.addProperty("description", description);
		o.addProperty("kind", kind);
		o.addProperty("wings", wings);
		JsonObject size = new JsonObject();
		size.addProperty("x", sizeX);
		size.addProperty("y", sizeY);
		size.addProperty("z", sizeZ);
		o.add("size", size);
		o.addProperty("groundY", groundY);
		o.addProperty("front", front);
		if (!materials.isEmpty()) {
			o.addProperty("materials", materials);
		}
		if (walk != null) {
			o.add("walk", Building.boundsJson(walk));
		}
		o.addProperty("foundationBlock", foundationBlock);
		o.add("approach", approach.toJson());
		JsonObject a = new JsonObject();
		anchors.forEach((n, v) -> a.add(n, Anchors.anchorJson(v)));
		o.add("anchors", a);
		return o;
	}

	/**
	 * Non-fatal problems worth a log line: required anchors missing, anchors outside the template,
	 * a walk box outside the template. Empty when the sidecar looks complete.
	 */
	public List<String> warnings() {
		List<String> w = new ArrayList<>();
		if (isFixture()) {
			// a fixture is not a workplace: no desks, stations or task walls (docs/VILLAGE.md V2)
			for (Anchor a : anchors.values()) {
				if (a.x() < 0 || a.y() < 0 || a.z() < 0 || a.x() > sizeX || a.y() > sizeY + 2 || a.z() > sizeZ) {
					w.add("anchor " + a.name() + " lies outside the template");
				}
			}
			if (!isBlockId(foundationBlock)) {
				w.add("foundationBlock '" + foundationBlock + "' is not a block id (" + DEFAULT_FOUNDATION + " is used)");
			}
			return w;
		}
		for (String req : List.of("meeting", "lounge", "library", "terminal", "testbench", "mergestation", "user", "decision_podium",
			"goal_atrium", "entrance", "spawn", "cam_overview")) {
			if (!anchors.containsKey(req)) {
				w.add("missing anchor " + req);
			}
		}
		for (String worker : CAST_WORKERS) {
			for (String prefix : List.of("desk_", "monitor_")) {
				if (!anchors.containsKey(prefix + worker)) {
					w.add("missing anchor " + prefix + worker);
				}
			}
		}
		for (int n = 1; n <= wings; n++) {
			if (!anchors.containsKey("task_wall@" + n)) {
				w.add("missing anchor task_wall@" + n);
			}
		}
		for (Anchor a : anchors.values()) {
			if (a.x() < 0 || a.y() < 0 || a.z() < 0 || a.x() > sizeX || a.y() > sizeY + 2 || a.z() > sizeZ) {
				w.add("anchor " + a.name() + " lies outside the template");
			}
			int at = a.name().lastIndexOf('@');
			if (at >= 0 && BlueprintTransform.wingOf(a.name()) < 1) {
				w.add("anchor " + a.name() + " has a bad wing suffix");
			}
		}
		if (walk != null && (walk.minX() < 0 || walk.minY() < 0 || walk.minZ() < 0 || walk.maxX() >= sizeX || walk.maxY() >= sizeY
			|| walk.maxZ() >= sizeZ)) {
			w.add("walk box exceeds the template");
		}
		if (walk == null) {
			w.add("no walk box (the whole template is used)");
		}
		if (approach.enabled() && (!isBlockId(approach.block()) || !isBlockId(approach.slab()))) {
			w.add("approach block/slab is not a block id (" + Approach.DEFAULT_BLOCK + " / " + Approach.DEFAULT_SLAB + " are used)");
		}
		if (!isBlockId(foundationBlock)) {
			w.add("foundationBlock '" + foundationBlock + "' is not a block id (" + DEFAULT_FOUNDATION + " is used)");
		}
		return w;
	}

	private static String str(JsonObject o, String key, @Nullable String def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
	}
}
