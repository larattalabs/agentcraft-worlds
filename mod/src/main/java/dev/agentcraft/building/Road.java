package dev.agentcraft.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A laid road (docs/VILLAGE.md V1, docs/BUILDINGS.md "Roads") as {@code <world>/agentcraft-roads.json} keeps it. The
 * blocks it replaced are in its snapshot ({@code <world>/agentcraft-roads/<id>.before.nbt}), one entry per changed cell.
 *
 * @param id {@code r<n>}, never reused
 * @param a the building it starts at, {@code b} the one it leads to (the pair is unordered for lookups)
 * @param dimension where it lies
 * @param length route cells it covers (outside the buildings)
 * @param cells the road's cells: x, feet y, z triples (where a walker stands; agents prefer them)
 * @param lanternCells the lanterns (x, y, z triples)
 * @param changes every cell the road changed (x, y, z triples; another road never changes them again)
 * @param notes what was skipped, for the hub
 */
public record Road(String id, String a, String b, String dimension, int width, boolean lanterns, boolean bridge, long created, int length,
	int[] cells, int[] lanternCells, int[] changes, List<String> notes) {

	public boolean joins(String building) {
		return a.equals(building) || b.equals(building);
	}

	/** Whether it joins buildings {@code x} and {@code y} (either way round). */
	public boolean between(String x, String y) {
		return a.equals(x) && b.equals(y) || a.equals(y) && b.equals(x);
	}

	public int cellCount() {
		return cells.length / 3;
	}

	public int lanternCount() {
		return lanternCells.length / 3;
	}

	public int changeCount() {
		return changes.length / 3;
	}

	/**
	 * Which of a removed road's changes another road still needs (removing an older road under a newer one that shares
	 * its walkway left the newer road with holes: a road never changes another road's cells, so the newer one had left
	 * them to the older). {@code changes} are the removed road's cells to give back (x, y, z triples); a cell goes to the
	 * road with a walker cell nearest it: in its column or a neighbouring one, from two below the feet (the path block
	 * under a slab) to three above (cleared headroom over a slab). Lanterns beside a road that stays go with it. Ties:
	 * the newest road, then the id. Returns the change index -> the id of the road that keeps it; the rest go back.
	 */
	public static java.util.Map<Integer, String> handover(int[] changes, List<Road> others) {
		java.util.Map<Long, List<int[]>> columns = new java.util.HashMap<>(); // column -> [road index, feet y]
		for (int r = 0; r < others.size(); r++) {
			int[] c = others.get(r).cells();
			for (int i = 0; i + 2 < c.length; i += 3) {
				columns.computeIfAbsent(column(c[i], c[i + 2]), k -> new ArrayList<>()).add(new int[] {r, c[i], c[i + 1], c[i + 2]});
			}
		}
		java.util.Map<Integer, String> out = new java.util.LinkedHashMap<>();
		if (columns.isEmpty()) {
			return out;
		}
		for (int i = 0; i + 2 < changes.length; i += 3) {
			int x = changes[i];
			int y = changes[i + 1];
			int z = changes[i + 2];
			Road best = null;
			int bestD = Integer.MAX_VALUE;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					List<int[]> at = columns.get(column(x + dx, z + dz));
					if (at == null) {
						continue;
					}
					for (int[] w : at) {
						int feet = w[2];
						if (y < feet - 2 || y > feet + 3) {
							continue;
						}
						Road q = others.get(w[0]);
						int d = dx * dx + dz * dz;
						if (best == null || d < bestD || d == bestD && (q.created() > best.created()
							|| q.created() == best.created() && q.id().compareTo(best.id()) < 0)) {
							best = q;
							bestD = d;
						}
					}
				}
			}
			if (best != null) {
				out.put(i / 3, best.id());
			}
		}
		return out;
	}

	private static long column(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	/** This road with {@code more} changes (x, y, z triples) it now owns, handed over by a removed road. */
	public Road withChanges(int[] more) {
		int[] c = java.util.Arrays.copyOf(changes, changes.length + more.length);
		System.arraycopy(more, 0, c, changes.length, more.length);
		return new Road(id, a, b, dimension, width, lanterns, bridge, created, length, cells, lanternCells, c, notes);
	}

	/** The other end. */
	public String other(String building) {
		return a.equals(building) ? b : a;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("a", a);
		o.addProperty("b", b);
		o.addProperty("dimension", dimension);
		o.addProperty("width", width);
		o.addProperty("lanterns", lanterns);
		o.addProperty("bridge", bridge);
		o.addProperty("created", created);
		o.addProperty("length", length);
		o.add("cells", ints(cells));
		o.add("lanternCells", ints(lanternCells));
		o.add("changes", ints(changes));
		JsonArray n = new JsonArray();
		notes.forEach(n::add);
		o.add("notes", n);
		return o;
	}

	public static Road fromJson(JsonObject o) {
		List<String> notes = new ArrayList<>();
		if (o.has("notes") && o.get("notes").isJsonArray()) {
			o.getAsJsonArray("notes").forEach(e -> notes.add(e.getAsString()));
		}
		int width = o.has("width") ? o.get("width").getAsInt() : RoadPlan.DEFAULT_WIDTH;
		return new Road(o.get("id").getAsString(), o.get("a").getAsString(), o.get("b").getAsString(),
			o.has("dimension") ? o.get("dimension").getAsString() : Building.OVERWORLD, Math.max(RoadPlan.MIN_WIDTH, Math.min(RoadPlan.MAX_WIDTH, width)),
			!o.has("lanterns") || o.get("lanterns").getAsBoolean(), o.has("bridge") && o.get("bridge").getAsBoolean(),
			o.has("created") ? o.get("created").getAsLong() : 0L, o.has("length") ? o.get("length").getAsInt() : 0, triples(o.get("cells")),
			triples(o.get("lanternCells")), triples(o.get("changes")), List.copyOf(notes));
	}

	static JsonArray ints(int[] a) {
		JsonArray j = new JsonArray();
		for (int v : a) {
			j.add(v);
		}
		return j;
	}

	/** A flat int array whose length is a multiple of 3 (a malformed tail is dropped). */
	static int[] triples(@Nullable JsonElement e) {
		if (e == null || !e.isJsonArray()) {
			return new int[0];
		}
		JsonArray a = e.getAsJsonArray();
		int n = a.size() / 3 * 3;
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			out[i] = a.get(i).getAsInt();
		}
		return out;
	}

	/** A removal the next world start settles (crash safety, as for buildings). */
	public record Pending(Road road, String snapshot, long at) {
		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.add("road", road.toJson());
			o.addProperty("snapshot", snapshot);
			o.addProperty("at", at);
			return o;
		}

		public static Pending fromJson(JsonObject o) {
			return new Pending(Road.fromJson(o.getAsJsonObject("road")), o.get("snapshot").getAsString(), o.has("at") ? o.get("at").getAsLong() : 0L);
		}
	}

	/** The file: {@code {version, next, roads[], pending[]}}. */
	public record FileData(List<Road> roads, int next, List<Pending> pending) {
		public static final FileData EMPTY = new FileData(List.of(), 1, List.of());

		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("version", 1);
			o.addProperty("next", next);
			JsonArray rs = new JsonArray();
			roads.forEach(r -> rs.add(r.toJson()));
			o.add("roads", rs);
			JsonArray ps = new JsonArray();
			pending.forEach(p -> ps.add(p.toJson()));
			o.add("pending", ps);
			return o;
		}

		/** Reads the file; a malformed road or pending entry is skipped, a missing {@code next} follows the highest id. */
		public static FileData fromJson(JsonObject o) {
			List<Road> roads = new ArrayList<>();
			int next = o.has("next") ? o.get("next").getAsInt() : 1;
			if (o.has("roads") && o.get("roads").isJsonArray()) {
				for (JsonElement e : o.getAsJsonArray("roads")) {
					try {
						Road r = Road.fromJson(e.getAsJsonObject());
						roads.add(r);
						next = Math.max(next, number(r.id()) + 1);
					} catch (RuntimeException ex) {
						// skipped: one broken entry never loses the others
					}
				}
			}
			List<Pending> pending = new ArrayList<>();
			if (o.has("pending") && o.get("pending").isJsonArray()) {
				for (JsonElement e : o.getAsJsonArray("pending")) {
					try {
						Pending p = Pending.fromJson(e.getAsJsonObject());
						pending.add(p);
						next = Math.max(next, number(p.road().id()) + 1);
					} catch (RuntimeException ex) {
						// skipped
					}
				}
			}
			return new FileData(List.copyOf(roads), next, List.copyOf(pending));
		}
	}

	/** {@code r12} -> 12; anything else 0. */
	public static int number(@Nullable String id) {
		if (id == null || id.length() < 2 || id.charAt(0) != 'r') {
			return 0;
		}
		try {
			return Integer.parseInt(id.substring(1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** What the next world start does with a removed road's site (decided on the cells, never on a count of saves). */
	public enum Settle {
		/** The cells hold their old blocks again: the snapshot can go. */
		RELEASE,
		/** The removal never reached the disk (the road stands): the record comes back. */
		RECORD_BACK,
		/** Cannot tell (nothing readable): keep it for the next start. */
		KEEP
	}

	/**
	 * A removed road's site at world start: {@code atAfter} cells hold what the road put there, {@code atBefore} hold
	 * what was there before it ({@code total} cells in the snapshot; cells the road did not change in kind count for
	 * neither). The road counts as standing when most cells that tell show the road.
	 */
	public static Settle settle(int atAfter, int atBefore, int total) {
		if (total <= 0 || atAfter + atBefore == 0) {
			return total <= 0 ? Settle.RELEASE : Settle.KEEP;
		}
		return atAfter > atBefore ? Settle.RECORD_BACK : Settle.RELEASE;
	}
}
