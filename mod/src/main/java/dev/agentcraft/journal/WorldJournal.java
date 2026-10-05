package dev.agentcraft.journal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Value;
import dev.agentcraft.layout.Anchors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The running world's journal (contract J1, docs/BUILDINGS.md "World journal"), server side: opened (and the older
 * records imported, {@link JournalMigration}) when a world starts, before the buildings, roads and trophies load; closed
 * when it stops. The features ({@code Buildings}, {@code Roads}, {@code Trophies}) record every world change here and
 * undo through it; this class does the world work the rules ({@link Journal}) leave out: capturing blocks, and
 * writing an undo's blocks back (a box through vanilla's {@code StructureTemplate}, exactly as the buildings'
 * snapshots always were; single cells with {@code setBlock}). When the journal cannot be read, world changes are refused
 * ({@link #unavailable}) and nothing on disk is touched. Server thread, except the read-only views.
 */
public final class WorldJournal {
	private static volatile @Nullable JournalStore store;
	private static volatile @Nullable String unavailable;
	private static volatile @Nullable Path world;
	private static volatile JournalMigration.@Nullable Plan imported;
	/** Test hook (DevBridge {@code dev.buildings.failNextRename}): the next commit throws before it writes. */
	private static volatile boolean failNext;

	private WorldJournal() {
	}

	/** Register before {@code Buildings.init()}: the journal opens (and imports) before the features load their records. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WorldJournal::open);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			store = null;
			unavailable = null;
			world = null;
			imported = null;
			failNext = false;
		});
	}

	static void open(MinecraftServer server) {
		Path w = server.getWorldPath(LevelResource.ROOT);
		world = w;
		imported = null;
		try {
			boolean had = JournalStore.exists(w);
			JournalStore s = JournalStore.open(w);
			if (!had && JournalMigration.needed(w)) {
				JournalMigration.Plan p = JournalMigration.run(w, s, (dim, x, y, z) -> {
					ServerLevel level = level(server, dim);
					return level == null ? null : valueAt(level, new BlockPos(x, y, z));
				});
				imported = p;
				AgentCraft.LOGGER.info("World journal: imported {} entr{} from the older snapshots ({} legacy file names){}", p.entries().size(),
					p.entries().size() == 1 ? "y" : "ies", p.legacy().size(), p.notes().isEmpty() ? "" : "; " + String.join("; ", p.notes()));
			} else if (had && JournalMigration.needed(w)) {
				JournalMigration.moveLegacy(w, s.dir()); // a crash between the import's commit and this move
			}
			if (!s.unreferenced().isEmpty()) {
				AgentCraft.LOGGER.warn("World journal: entry files no index entry names (a change that never committed; kept): {}", s.unreferenced());
			}
			store = s;
			unavailable = null;
		} catch (IOException | RuntimeException e) {
			store = null;
			unavailable = "The world journal (" + JournalStore.DIR + ") could not be read or imported (" + e.getMessage()
				+ "): AgentCraft changes no blocks until it is fixed (see the log)";
			AgentCraft.LOGGER.error("World journal: could not open {}", JournalStore.dirOf(w), e);
		}
	}

	/** Why world changes are refused (the journal could not be read), or null. Any thread. */
	public static @Nullable String unavailable() {
		return world == null ? "no world" : unavailable;
	}

	/** The open store; throws IOException with {@link #unavailable} when there is none. */
	public static JournalStore store() throws IOException {
		JournalStore s = store;
		if (s == null) {
			String why = unavailable();
			throw new IOException(why == null ? "no world journal" : why);
		}
		return s;
	}

	/** What the last world start imported, or null. */
	public static JournalMigration.@Nullable Plan imported() {
		return imported;
	}

	/** Arms {@link #failNext} (DevBridge only). */
	public static void failNextCommit() {
		failNext = true;
	}

	// ------------------------------------------------------------------ reads

	/** The metas of {@code kind} entries owned by {@code owner} with status {@code status} (null: any), creation order. Any thread. */
	public static List<JournalStore.Meta> find(String kind, String owner, @Nullable Status status) {
		JournalStore s = store;
		if (s == null) {
			return List.of();
		}
		return s.find(m -> m.kind().equals(kind) && m.owner().equals(owner) && (status == null || m.status() == status));
	}

	/** Whether any entry (any kind or status) is owned by {@code owner}, or a legacy snapshot of it was imported: its id must not be reused. */
	public static boolean ownerUsed(String owner) {
		JournalStore s = store;
		if (s == null) {
			return false;
		}
		if (!s.find(m -> m.owner().equals(owner)).isEmpty()) {
			return true;
		}
		for (String k : s.index().legacy().keySet()) {
			String name = k.substring(k.lastIndexOf('/') + 1);
			if (name.startsWith(owner + ".")) {
				return true;
			}
		}
		Path legacy = s.dir().resolve(JournalMigration.LEGACY_DIR);
		for (String d : new String[] {JournalMigration.BUILDINGS_DIR, JournalMigration.ROADS_DIR}) {
			if (Files.exists(legacy.resolve(d).resolve(owner + ".before.nbt"))) {
				return true;
			}
		}
		return false;
	}

	/** The entry a pending record's {@code snapshot} names: an entry id, or a legacy file name in {@code legacyDir}. Null when none. */
	public static @Nullable String resolve(String legacyDir, String snapshot) {
		JournalStore s = store;
		if (s == null) {
			return null;
		}
		if (s.meta(snapshot) != null) {
			return snapshot;
		}
		String id = s.index().legacy().get(legacyDir + "/" + snapshot);
		return id != null && s.meta(id) != null ? id : null;
	}

	public static Entry load(String id) throws IOException {
		return store().load(id);
	}

	/** The active entries in {@code dimension} whose boxes touch {@code box} {minX..maxZ}. */
	public static List<Entry> activeTouching(String dimension, int[] box) throws IOException {
		JournalStore s = store();
		return s.loadAll(s.find(m -> m.active() && m.intersects(dimension, box)));
	}

	// ------------------------------------------------------------------ making changes

	/** A new entry id and the layer of a change made now. */
	public static String newId() throws IOException {
		return store().newId();
	}

	public static long newLayer() throws IOException {
		return store().newLayer();
	}

	/** {@link JournalStore#writeDraft}. */
	public static void draft(Entry e) throws IOException {
		store().writeDraft(e);
	}

	public static void discardDraft(String id) {
		JournalStore s = store;
		if (s != null) {
			s.discardDraft(id);
		}
	}

	/** Commits entries (new, changed, undone) and releases others in one index write. */
	public static void commit(Map<String, Entry> upserts, Collection<String> releases) throws IOException {
		JournalStore s = store();
		if (failNext) {
			failNext = false;
			throw new IOException("injected journal failure (dev.buildings.failNextRename)");
		}
		s.commit(upserts, releases);
	}

	/**
	 * Captures {@code box} as a structure template (blocks, air included, and block entities; no entities), the format the
	 * buildings' snapshots always had, and checks the capture is complete (one block per cell).
	 */
	public static CompoundTag capture(ServerLevel level, Anchors.Bounds box) throws IOException {
		BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
		Vec3i size = new Vec3i(box.maxX() - box.minX() + 1, box.maxY() - box.minY() + 1, box.maxZ() - box.minZ() + 1);
		StructureTemplate t = new StructureTemplate();
		t.setAuthor("agentcraft");
		t.fillFromWorld(level, min, size, false, List.of());
		CompoundTag tag = t.save(new CompoundTag());
		long volume = (long) size.getX() * size.getY() * size.getZ();
		int captured = tag.getListOrEmpty("blocks").size();
		if (captured != volume) {
			throw new IOException("captured " + captured + " of " + volume + " blocks");
		}
		return tag;
	}

	/** Puts a captured template back over {@code box} (a placement that failed half way): the old snapshot restore, unchanged. */
	public static void restoreTemplate(ServerLevel level, Anchors.Bounds box, CompoundTag tpl, int flags) {
		StructureTemplate t = new StructureTemplate();
		t.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tpl);
		BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
		t.placeInWorld(level, min, min, settings(), level.getRandom(), flags);
	}

	private static StructurePlaceSettings settings() {
		return new StructurePlaceSettings().setRotation(Rotation.NONE).setMirror(Mirror.NONE).setIgnoreEntities(true)
			.setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
	}

	/** The block at {@code p} as the journal keeps it (state and block entity data). */
	public static Value valueAt(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		CompoundTag nbt = null;
		BlockEntity be = level.getBlockEntity(p);
		if (be != null) {
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			be.saveWithId(out);
			nbt = out.buildResult();
		}
		return new Value(NbtUtils.writeBlockState(s), nbt);
	}

	private static final Map<CompoundTag, BlockState> STATES = new java.util.concurrent.ConcurrentHashMap<>();

	/** A journal value's block state (cached). */
	public static BlockState state(ServerLevel level, Value v) {
		BlockState s = STATES.get(v.state());
		if (s == null) {
			s = NbtUtils.readBlockState(level.registryAccess().lookupOrThrow(Registries.BLOCK), v.state());
			if (STATES.size() < 4096) {
				STATES.put(v.state().copy(), s);
			}
		}
		return s;
	}

	// ------------------------------------------------------------------ undo

	/**
	 * Plans undoing {@code ids} together as {@code group} ({@link Journal#planUndo}) against {@code level}: loads every
	 * active entry their boxes touch. {@code same}: how a CELL entry recognises its own block ({@code (now, placed)}).
	 */
	public static Journal.UndoPlan planUndo(ServerLevel level, Collection<String> ids, String group, BiPredicate<BlockState, BlockState> same)
		throws IOException {
		JournalStore s = store();
		Map<String, Entry> loaded = new LinkedHashMap<>();
		for (String id : ids) {
			loaded.put(id, s.load(id));
		}
		Set<String> dims = new LinkedHashSet<>();
		for (Entry e : List.copyOf(loaded.values())) {
			dims.add(e.dimension());
			int[] b = e.box();
			if (b == null) {
				continue;
			}
			for (Entry o : activeTouching(e.dimension(), b)) {
				loaded.putIfAbsent(o.id(), o);
			}
		}
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		Journal.World w = (pos, after) -> {
			BlockState now = level.getBlockState(m.set(Journal.x(pos), Journal.y(pos), Journal.z(pos)));
			BlockState want = state(level, after);
			return same.test(now, want) && now.hasBlockEntity() == want.hasBlockEntity();
		};
		Journal.Match match = (v, after) -> {
			BlockState a = state(level, v);
			BlockState b = state(level, after);
			return same.test(a, b) && a.hasBlockEntity() == b.hasBlockEntity();
		};
		return Journal.planUndo(loaded.values(), ids, group, System.currentTimeMillis(), w, match);
	}

	/**
	 * Writes an undo's blocks: a BOX writer's through a structure template over its entry's box ({@code boxFlags}, the
	 * buildings' restore exactly), the CELL writers' cell by cell, lowest first ({@code cellFlags}), their block entity data
	 * loaded after. Server thread.
	 */
	public static void apply(ServerLevel level, Journal.UndoPlan plan, int boxFlags, int cellFlags) {
		Map<String, Map<Long, Value>> boxes = new LinkedHashMap<>();
		List<Journal.Write> cells = new ArrayList<>();
		for (Journal.Write w : plan.writes()) {
			if (w.policy() == Policy.BOX) {
				boxes.computeIfAbsent(w.by(), k -> new LinkedHashMap<>()).put(w.pos(), w.value());
			} else {
				cells.add(w);
			}
		}
		for (var b : boxes.entrySet()) {
			Entry e = plan.updated().get(b.getKey());
			int[] box = e == null ? null : e.box();
			if (box == null) {
				continue;
			}
			CompoundTag tpl = JournalNbt.toTemplate(b.getValue(), box[0], box[1], box[2], box[3] - box[0] + 1, box[4] - box[1] + 1, box[5] - box[2] + 1, 0);
			restoreTemplate(level, new Anchors.Bounds(box[0], box[1], box[2], box[3], box[4], box[5]), tpl, boxFlags);
		}
		cells.sort(Comparator.comparingInt(w -> Journal.y(w.pos())));
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Journal.Write w : cells) {
			m.set(Journal.x(w.pos()), Journal.y(w.pos()), Journal.z(w.pos()));
			level.setBlock(m, state(level, w.value()), cellFlags);
			if (w.value().nbt() != null) {
				BlockEntity be = level.getBlockEntity(m);
				if (be != null) {
					be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), w.value().nbt()));
					be.setChanged();
				}
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	/** A BOX entry over {@code box} from a captured template ({@code after} null: filled in later). */
	public static Entry boxEntry(String id, String kind, String owner, String dimension, long layer, Anchors.Bounds box, CompoundTag before,
		@Nullable CompoundTag after, @Nullable JsonObject meta) {
		List<Cell> cells = JournalNbt.fromTemplate(before, after, box.minX(), box.minY(), box.minZ(), layer);
		return new Entry(id, kind, owner, dimension, Policy.BOX, System.currentTimeMillis(), Status.ACTIVE, cells, null, meta);
	}

	/** {@code entry} with every cell's after from a template captured over {@code box} after the change. */
	public static Entry withAfter(Entry entry, Anchors.Bounds box, CompoundTag after) {
		Map<Long, Value> a = JournalNbt.values(after, box.minX(), box.minY(), box.minZ());
		List<Cell> cs = new ArrayList<>(entry.cells().size());
		for (Cell c : entry.cells()) {
			cs.add(new Cell(c.pos(), c.layer(), c.before(), a.get(c.pos())));
		}
		return entry.withCells(cs);
	}

	/** The befores of an entry as a template over its box, loaded (the buildings' crash check compares a site with it). */
	public static @Nullable StructureTemplate beforeTemplate(ServerLevel level, Entry e) {
		int[] box = e.box();
		if (box == null) {
			return null;
		}
		StructureTemplate t = new StructureTemplate();
		t.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), JournalNbt.beforeTemplate(e, box));
		return t;
	}

	static @Nullable ServerLevel level(MinecraftServer server, String dimension) {
		Identifier key = Identifier.tryParse(dimension);
		return key == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, key));
	}

	// ------------------------------------------------------------------ DevBridge

	/** The journal as JSON (dev.journal.state): status, counters, every entry's metadata, the legacy names. Any thread. */
	public static JsonObject json() {
		JsonObject o = new JsonObject();
		JournalStore s = store;
		o.addProperty("open", s != null);
		o.addProperty("unavailable", unavailable());
		if (s == null) {
			return o;
		}
		JournalStore.Index idx = s.index();
		o.addProperty("nextId", idx.nextId());
		o.addProperty("nextLayer", idx.nextLayer());
		JsonArray es = new JsonArray();
		for (JournalStore.Meta m : idx.entries().values()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", m.id());
			j.addProperty("kind", m.kind());
			j.addProperty("owner", m.owner());
			j.addProperty("dimension", m.dimension());
			j.addProperty("policy", m.policy().name());
			j.addProperty("status", m.status().name());
			j.addProperty("cells", m.cells());
			j.addProperty("gen", m.gen());
			if (m.box() != null) {
				j.addProperty("box", m.box()[0] + "," + m.box()[1] + "," + m.box()[2] + " .. " + m.box()[3] + "," + m.box()[4] + "," + m.box()[5]);
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
		JsonArray un = new JsonArray();
		s.unreferenced().forEach(un::add);
		o.add("unreferenced", un);
		JournalMigration.Plan p = imported;
		if (p != null) {
			JsonArray n = new JsonArray();
			p.notes().forEach(n::add);
			o.add("importNotes", n);
			o.addProperty("imported", p.entries().size());
		}
		return o;
	}

	/** The stack at a cell (dev.journal.at): every active entry with a cell there, bottom first, with before / after. Server thread. */
	public static JsonObject at(String dimension, int x, int y, int z) throws IOException {
		long pos = Journal.pos(x, y, z);
		List<Entry> touching = activeTouching(dimension, new int[] {x, y, z, x, y, z});
		JsonObject o = new JsonObject();
		o.addProperty("pos", x + "," + y + "," + z);
		JsonArray st = new JsonArray();
		for (var e : Journal.stack(touching, pos)) {
			JsonObject j = new JsonObject();
			j.addProperty("id", e.getKey().id());
			j.addProperty("kind", e.getKey().kind());
			j.addProperty("owner", e.getKey().owner());
			j.addProperty("policy", e.getKey().policy().name());
			j.addProperty("layer", e.getValue().layer());
			j.addProperty("before", e.getValue().before().toString());
			j.addProperty("after", e.getValue().after() == null ? null : e.getValue().after().toString());
			st.add(j);
		}
		o.add("stack", st);
		return o;
	}

	/** Pure helper for features: the positions of {@code cells}. */
	public static Map<Long, Cell> byPos(Entry e) {
		Map<Long, Cell> m = new HashMap<>();
		for (Cell c : e.cells()) {
			m.put(c.pos(), c);
		}
		return m;
	}
}
