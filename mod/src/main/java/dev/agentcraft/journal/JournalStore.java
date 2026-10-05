package dev.agentcraft.journal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.jspecify.annotations.Nullable;

/**
 * The world journal on disk (docs/BUILDINGS.md "World journal"): {@code <world>/agentcraft-journal/journal.json} (the
 * index: every entry's metadata and box, the id and layer counters, the legacy snapshot names it imported) and one NBT
 * file per entry, {@code <id>.<gen>.nbt}. Crash safety: an entry file is never rewritten in place: a change writes the
 * next generation (temp file, atomic move, read back), then the index is replaced atomically (the commit point), then
 * the superseded files are deleted. At open, generations the index does not name for a known entry are leftovers and
 * deleted; files of entries the index does not know (a change that never committed) are kept and logged. No world, no
 * registries: testable with a temp folder.
 */
public final class JournalStore {
	public static final String DIR = "agentcraft-journal";
	public static final String INDEX = "journal.json";
	public static final int VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Pattern FILE = Pattern.compile("(j\\d+)\\.(\\d+)\\.nbt");

	/** An entry as the index knows it (no cells). {@code box}: {minX, minY, minZ, maxX, maxY, maxZ}, null without cells. */
	public record Meta(String id, String kind, String owner, String dimension, Policy policy, long createdAt, Status status, int gen, int cells,
		int @Nullable [] box, @Nullable String group, long undoneAt) {
		public boolean active() {
			return status == Status.ACTIVE;
		}

		public boolean intersects(String dim, int[] b) {
			return box != null && dimension.equals(dim) && box[0] <= b[3] && b[0] <= box[3] && box[1] <= b[4] && b[1] <= box[4] && box[2] <= b[5]
				&& b[2] <= box[5];
		}
	}

	/** The index: entries by id (creation order), the counters, legacy snapshot path -> entry id. Immutable. */
	public record Index(Map<String, Meta> entries, long nextId, long nextLayer, Map<String, String> legacy) {
		public static final Index EMPTY = new Index(Map.of(), 1, 1, Map.of());
	}

	private final Path dir;
	private volatile Index index;
	private long allocatedId;
	private long allocatedLayer;
	private final List<String> unreferenced = new ArrayList<>();

	private JournalStore(Path dir, Index index) {
		this.dir = dir;
		this.index = index;
		this.allocatedId = index.nextId();
		this.allocatedLayer = index.nextLayer();
	}

	/** The journal folder of a world. */
	public static Path dirOf(Path worldDir) {
		return worldDir.resolve(DIR);
	}

	/** Whether the world has a journal index (the migration's "done" marker). */
	public static boolean exists(Path worldDir) {
		return Files.exists(dirOf(worldDir).resolve(INDEX));
	}

	/**
	 * Opens the journal of {@code worldDir} (an empty one when there is no index yet; nothing is written until the first
	 * commit) and tidies its folder. Throws when the index exists but cannot be read: the caller then leaves everything
	 * alone and refuses world changes.
	 */
	public static JournalStore open(Path worldDir) throws IOException {
		Path dir = dirOf(worldDir);
		Path f = dir.resolve(INDEX);
		Index idx = Index.EMPTY;
		if (Files.exists(f)) {
			try {
				idx = indexFromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
			} catch (RuntimeException e) {
				throw new IOException(INDEX + " is not a journal index: " + e.getMessage(), e);
			}
		}
		JournalStore s = new JournalStore(dir, idx);
		s.tidy();
		return s;
	}

	public Path dir() {
		return dir;
	}

	public Index index() {
		return index;
	}

	/** Entry files no index entry names (a change that never committed), kept for a look by hand. */
	public List<String> unreferenced() {
		return List.copyOf(unreferenced);
	}

	private void tidy() throws IOException {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (var list = Files.list(dir)) {
			for (Path p : (Iterable<Path>) list::iterator) {
				String name = p.getFileName().toString();
				if (name.endsWith(".tmp")) {
					Files.deleteIfExists(p);
					continue;
				}
				Matcher m = FILE.matcher(name);
				if (!m.matches()) {
					continue;
				}
				Meta meta = index.entries().get(m.group(1));
				if (meta == null) {
					unreferenced.add(name);
				} else if (Integer.parseInt(m.group(2)) != meta.gen()) {
					Files.deleteIfExists(p); // superseded, or written by a commit that never reached the index
				}
			}
		}
	}

	/** A new entry id (not used by any entry, committed or not). */
	public String newId() {
		while (true) {
			String id = "j" + allocatedId++;
			if (!index.entries().containsKey(id) && !Files.exists(dir.resolve(id + ".0.nbt"))) {
				return id;
			}
		}
	}

	/** The next layer (later changes are higher). */
	public long newLayer() {
		return allocatedLayer++;
	}

	/** Raises the counters to at least these (migration). */
	void reserve(long id, long layer) {
		allocatedId = Math.max(allocatedId, id);
		allocatedLayer = Math.max(allocatedLayer, layer);
	}

	public @Nullable Meta meta(String id) {
		return index.entries().get(id);
	}

	/** The metas matching {@code p}, in creation order. */
	public List<Meta> find(Predicate<Meta> p) {
		List<Meta> out = new ArrayList<>();
		for (Meta m : index.entries().values()) {
			if (p.test(m)) {
				out.add(m);
			}
		}
		return out;
	}

	/** Reads an entry's cells. */
	public Entry load(String id) throws IOException {
		Meta m = index.entries().get(id);
		if (m == null) {
			throw new IOException("no journal entry " + id);
		}
		return read(dir.resolve(id + "." + m.gen() + ".nbt"));
	}

	public List<Entry> loadAll(Collection<Meta> metas) throws IOException {
		List<Entry> out = new ArrayList<>();
		for (Meta m : metas) {
			out.add(load(m.id()));
		}
		return out;
	}

	static Entry read(Path f) throws IOException {
		CompoundTag t = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
		try {
			return JournalNbt.decode(t);
		} catch (RuntimeException e) {
			throw new IOException(f.getFileName() + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Writes a change about to be made as generation 0 (before it touches the world; not in the index): a crash during
	 * the change leaves it on disk, and a disk that cannot take it refuses the change before anything happened.
	 */
	public void writeDraft(Entry e) throws IOException {
		write(dir.resolve(e.id() + ".0.nbt"), e);
	}

	/** Deletes a draft that was never committed (the change was rolled back). */
	public void discardDraft(String id) {
		try {
			Files.deleteIfExists(dir.resolve(id + ".0.nbt"));
		} catch (IOException ignored) {
			// a stray draft names no committed entry; the next open lists it
		}
	}

	/**
	 * Commits: every entry in {@code upserts} gets a new generation file, entries in {@code releases} leave, then the
	 * index is replaced (the commit point), then superseded files go. {@code legacy}: legacy snapshot names to add
	 * (migration). On failure before the index is replaced nothing changed (the new files are leftovers the next open
	 * deletes).
	 */
	public synchronized void commit(Map<String, Entry> upserts, Collection<String> releases, Map<String, String> legacy) throws IOException {
		Index old = index;
		Map<String, Meta> metas = new LinkedHashMap<>(old.entries());
		List<Path> superseded = new ArrayList<>();
		for (Entry e : upserts.values()) {
			Meta was = metas.get(e.id());
			int gen = was == null ? 1 : was.gen() + 1;
			write(dir.resolve(e.id() + "." + gen + ".nbt"), e);
			if (was != null) {
				superseded.add(dir.resolve(e.id() + "." + was.gen() + ".nbt"));
			} else {
				superseded.add(dir.resolve(e.id() + ".0.nbt")); // its draft
			}
			metas.put(e.id(), metaOf(e, gen));
		}
		for (String id : releases) {
			Meta was = metas.remove(id);
			if (was != null) {
				superseded.add(dir.resolve(id + "." + was.gen() + ".nbt"));
			}
		}
		Map<String, String> leg = new LinkedHashMap<>(old.legacy());
		leg.putAll(legacy);
		leg.values().removeIf(id -> !metas.containsKey(id));
		Index next = new Index(Collections.unmodifiableMap(metas), Math.max(old.nextId(), allocatedId), Math.max(old.nextLayer(), allocatedLayer),
			Collections.unmodifiableMap(leg));
		writeIndex(next);
		index = next;
		for (Path p : superseded) {
			try {
				Files.deleteIfExists(p);
			} catch (IOException e) {
				dev.agentcraft.AgentCraft.LOGGER.warn("World journal: could not delete {} (the next start does)", p.getFileName(), e);
			}
		}
	}

	public void commit(Map<String, Entry> upserts, Collection<String> releases) throws IOException {
		commit(upserts, releases, Map.of());
	}

	static Meta metaOf(Entry e, int gen) {
		return new Meta(e.id(), e.kind(), e.owner(), e.dimension(), e.policy(), e.createdAt(), e.status(), gen, e.cells().size(), e.box(),
			e.undo() == null ? null : e.undo().group(), e.undo() == null ? 0L : e.undo().at());
	}

	private void write(Path f, Entry e) throws IOException {
		Files.createDirectories(dir);
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		NbtIo.writeCompressed(JournalNbt.encode(e), tmp);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		Entry back = read(f);
		if (back.cells().size() != e.cells().size()) {
			throw new IOException("read back " + back.cells().size() + " of " + e.cells().size() + " cells of " + e.id());
		}
	}

	private void writeIndex(Index idx) throws IOException {
		Files.createDirectories(dir);
		Path f = dir.resolve(INDEX);
		Path tmp = dir.resolve(INDEX + ".tmp");
		Files.writeString(tmp, GSON.toJson(indexToJson(idx)), StandardCharsets.UTF_8);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	// ------------------------------------------------------------------ index JSON

	static JsonObject indexToJson(Index idx) {
		JsonObject o = new JsonObject();
		o.addProperty("version", VERSION);
		o.addProperty("nextId", idx.nextId());
		o.addProperty("nextLayer", idx.nextLayer());
		JsonArray es = new JsonArray();
		for (Meta m : idx.entries().values()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", m.id());
			j.addProperty("kind", m.kind());
			j.addProperty("owner", m.owner());
			j.addProperty("dimension", m.dimension());
			j.addProperty("policy", m.policy().name());
			j.addProperty("createdAt", m.createdAt());
			j.addProperty("status", m.status().name());
			j.addProperty("gen", m.gen());
			j.addProperty("cells", m.cells());
			if (m.box() != null) {
				JsonArray b = new JsonArray();
				for (int v : m.box()) {
					b.add(v);
				}
				j.add("box", b);
			}
			if (m.group() != null) {
				j.addProperty("group", m.group());
				j.addProperty("undoneAt", m.undoneAt());
			}
			es.add(j);
		}
		o.add("entries", es);
		JsonObject leg = new JsonObject();
		idx.legacy().forEach(leg::addProperty);
		o.add("legacy", leg);
		return o;
	}

	static Index indexFromJson(JsonObject o) {
		if (o.get("version") == null || o.get("version").getAsInt() != VERSION) {
			throw new IllegalArgumentException("unknown journal version " + o.get("version"));
		}
		Map<String, Meta> metas = new LinkedHashMap<>();
		for (JsonElement el : o.getAsJsonArray("entries")) {
			JsonObject j = el.getAsJsonObject();
			int[] box = null;
			if (j.get("box") instanceof JsonArray b && b.size() == 6) {
				box = new int[6];
				for (int i = 0; i < 6; i++) {
					box[i] = b.get(i).getAsInt();
				}
			}
			Meta m = new Meta(j.get("id").getAsString(), j.get("kind").getAsString(), j.get("owner").getAsString(), j.get("dimension").getAsString(),
				Policy.valueOf(j.get("policy").getAsString()), j.get("createdAt").getAsLong(), Status.valueOf(j.get("status").getAsString()),
				j.get("gen").getAsInt(), j.get("cells").getAsInt(), box, j.has("group") ? j.get("group").getAsString() : null,
				j.has("undoneAt") ? j.get("undoneAt").getAsLong() : 0L);
			metas.put(m.id(), m);
		}
		Map<String, String> legacy = new LinkedHashMap<>();
		if (o.get("legacy") instanceof JsonObject leg) {
			leg.entrySet().forEach(e -> legacy.put(e.getKey(), e.getValue().getAsString()));
		}
		return new Index(Collections.unmodifiableMap(metas), o.get("nextId").getAsLong(), o.get("nextLayer").getAsLong(),
			Collections.unmodifiableMap(legacy));
	}
}
