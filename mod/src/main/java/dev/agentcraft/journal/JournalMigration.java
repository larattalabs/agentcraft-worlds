package dev.agentcraft.journal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Road;
import dev.agentcraft.building.TrophyLedger;
import dev.agentcraft.building.TrophySlots;
import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Undo;
import dev.agentcraft.journal.Journal.Value;
import dev.agentcraft.layout.Anchors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The one-time import of a world's older per-feature records into the world journal (contract J1), lossless:
 * <ul>
 * <li>{@code agentcraft-buildings/<id>.before.nbt} of every recorded building or fixture: an active BOX entry over its
 * restore box, the snapshot's blocks (block entities included) as the cells' {@code before} ({@code after} unknown: a box
 * restore never needs it); the record is the entry's meta;</li>
 * <li>each {@code pending} site of {@code agentcraft-buildings.json} ({@code <id>.before.nbt} of a removal,
 * {@code <id>.moved-<ms>.nbt} of a move): an undone entry whose undo wrote the whole box, settled by the next world start
 * as before;</li>
 * <li>{@code agentcraft-roads/<id>.before.nbt} of every road: an active CELL entry with each cell's before and after; each
 * pending removal ({@code <id>.removed-<ms>.nbt}, or the never renamed {@code <id>.before.nbt}): an undone entry;</li>
 * <li>the trophy signs the ledger says hang in a recorded building's slots and that still hang there: active CELL entries
 * over the building's (the ledger itself stays the awards record).</li>
 * </ul>
 * Layers follow time (a road laid before a building placed over it is lower). Every imported file name maps to its entry
 * ({@link JournalStore.Index#legacy}), so a pending record that still names a file resolves. The index commit is the
 * only "done" marker: the old folders move to {@code agentcraft-journal/legacy/} only after it, and an index that exists
 * means no import runs again. Unreadable record files abort the import (nothing is written; world changes stay refused
 * until fixed).
 */
public final class JournalMigration {
	public static final String BUILDINGS_FILE = "agentcraft-buildings.json";
	public static final String BUILDINGS_DIR = "agentcraft-buildings";
	public static final String ROADS_FILE = "agentcraft-roads.json";
	public static final String ROADS_DIR = "agentcraft-roads";
	public static final String LEGACY_DIR = "legacy";
	static final String SIGN = "minecraft:dark_oak_wall_sign";

	private JournalMigration() {
	}

	/** Reads the block at a trophy slot (the world), or null when it cannot be read: no trophy entry then. */
	@FunctionalInterface
	public interface SlotReader {
		@Nullable
		Value at(String dimension, int x, int y, int z);

		SlotReader NONE = (d, x, y, z) -> null;
	}

	/** What an import found: the entries, the legacy names -> entry ids, what it says about it. */
	public record Plan(Map<String, Entry> entries, Map<String, String> legacy, List<String> notes) {
		public boolean empty() {
			return entries.isEmpty();
		}
	}

	/** Whether {@code worldDir} has anything to import. */
	public static boolean needed(Path worldDir) {
		return Files.isDirectory(worldDir.resolve(BUILDINGS_DIR)) || Files.isDirectory(worldDir.resolve(ROADS_DIR));
	}

	/**
	 * Imports {@code worldDir}'s old records into {@code store} (which has no index yet) and moves the old folders into
	 * the journal's legacy folder. Returns the plan. Throws when a record file cannot be read (nothing written).
	 */
	public static Plan run(Path worldDir, JournalStore store, SlotReader slots) throws IOException {
		Plan p = plan(worldDir, store, slots);
		store.commit(p.entries(), List.of(), p.legacy());
		moveLegacy(worldDir, store.dir());
		return p;
	}

	/** After the index commit: the old folders into {@code agentcraft-journal/legacy/} (also finishes a move a crash interrupted). */
	public static void moveLegacy(Path worldDir, Path journalDir) throws IOException {
		for (String d : new String[] {BUILDINGS_DIR, ROADS_DIR}) {
			Path from = worldDir.resolve(d);
			if (!Files.isDirectory(from)) {
				continue;
			}
			Path to = journalDir.resolve(LEGACY_DIR).resolve(d);
			Files.createDirectories(to);
			try (var list = Files.list(from)) {
				for (Path f : (Iterable<Path>) list::iterator) {
					Path target = to.resolve(f.getFileName());
					if (Files.exists(target)) {
						target = to.resolve(f.getFileName() + ".dup-" + System.currentTimeMillis());
					}
					Files.move(f, target, StandardCopyOption.ATOMIC_MOVE);
				}
			}
			Files.deleteIfExists(from);
		}
	}

	/** The import plan for {@code worldDir}, allocating ids and layers from {@code store}. Writes nothing. */
	public static Plan plan(Path worldDir, JournalStore store, SlotReader slots) throws IOException {
		List<String> notes = new ArrayList<>();
		Building.FileData buildings = readBuildings(worldDir);
		Road.FileData roads = readRoads(worldDir);
		TrophyLedger ledger;
		try {
			ledger = TrophyLedger.read(worldDir.resolve(TrophyLedger.FILE));
		} catch (IOException e) {
			ledger = new TrophyLedger(); // an unreadable ledger is the trophies' business: no trophy entries, the file stays
			notes.add("trophy ledger unreadable: no trophy entries (" + e.getMessage() + ")");
		}
		// what to import, oldest first (layers follow time)
		List<Item> items = new ArrayList<>();
		Path bdir = worldDir.resolve(BUILDINGS_DIR);
		Map<String, Building> recorded = new LinkedHashMap<>();
		for (Building b : buildings.buildings()) {
			recorded.put(b.id(), b);
			String name = b.id() + ".before.nbt";
			if (Files.exists(bdir.resolve(name))) {
				items.add(new Item(Item.Kind.BUILDING, BUILDINGS_DIR + "/" + name, bdir.resolve(name), b.id(), b.dimensionOrDefault(),
					Math.max(b.placedAt(), b.revision()), b.restoreBox(), b.toJson(), null));
			} else {
				notes.add(b.id() + ": no snapshot " + name + " (Remove stays refused; Forget drops the record)");
			}
		}
		for (Building.Pending p : buildings.pending()) {
			Path f = bdir.resolve(p.snapshot());
			if (!Files.exists(f)) {
				notes.add("pending " + p.why() + " site of " + p.building().id() + ": " + p.snapshot() + " is missing");
				continue;
			}
			items.add(new Item(Item.Kind.BUILDING_PENDING, BUILDINGS_DIR + "/" + p.snapshot(), f, p.building().id(), p.building().dimensionOrDefault(),
				p.at(), p.building().restoreBox(), p.building().toJson(), p.at()));
		}
		Path rdir = worldDir.resolve(ROADS_DIR);
		Map<String, Road> roadsById = new HashMap<>();
		for (Road r : roads.roads()) {
			roadsById.put(r.id(), r);
			String name = r.id() + ".before.nbt";
			if (Files.exists(rdir.resolve(name))) {
				items.add(new Item(Item.Kind.ROAD, ROADS_DIR + "/" + name, rdir.resolve(name), r.id(), r.dimension(), r.created(), null, r.toJson(), null));
			} else {
				notes.add(r.id() + ": no snapshot " + name);
			}
		}
		for (Road.Pending p : roads.pending()) {
			Path f = rdir.resolve(p.snapshot());
			String name = p.snapshot();
			if (!Files.exists(f) && !roadsById.containsKey(p.road().id()) && Files.exists(rdir.resolve(p.road().id() + ".before.nbt"))) {
				name = p.road().id() + ".before.nbt"; // the removal was recorded but its snapshot never renamed
				f = rdir.resolve(name);
			}
			if (!Files.exists(f)) {
				notes.add("pending removal of " + p.road().id() + ": " + p.snapshot() + " is missing");
				continue;
			}
			Item it = new Item(Item.Kind.ROAD_PENDING, ROADS_DIR + "/" + name, f, p.road().id(), p.road().dimension(), p.at(), null, p.road().toJson(),
				p.at());
			it.alias = ROADS_DIR + "/" + p.snapshot();
			items.add(it);
		}
		// trophy signs that hang in a recorded building's slots now
		for (String bid : ledger.buildingIds()) {
			Building b = recorded.get(bid);
			if (b == null || b.pin() == null) {
				continue;
			}
			Map<String, TrophySlots.Slot> byName = new HashMap<>();
			for (TrophySlots.Slot s : TrophySlots.all(b.pin().wingAnchors())) {
				byName.put(s.name(), s);
			}
			long base = Math.max(b.placedAt(), b.revision());
			for (var e : ledger.slots(bid).entrySet()) {
				TrophySlots.Slot s = byName.get(e.getKey());
				if (s == null) {
					continue;
				}
				Value now = slots.at(b.dimensionOrDefault(), s.x(), s.y(), s.z());
				if (now == null || !SIGN.equals(now.name())) {
					continue;
				}
				JsonObject meta = new JsonObject();
				meta.addProperty("building", bid);
				meta.addProperty("slot", e.getKey());
				meta.addProperty("key", e.getValue().key());
				Item it = new Item(Item.Kind.TROPHY, null, null, bid, b.dimensionOrDefault(), Math.max(base + 1, e.getValue().at()), null, meta, null);
				it.trophy = new Cell(Journal.pos(s.x(), s.y(), s.z()), 0, Journal.AIR, now);
				items.add(it);
			}
		}
		items.sort(Comparator.comparingLong((Item i) -> i.time).thenComparingInt(i -> i.kind.order));
		Map<String, Entry> entries = new LinkedHashMap<>();
		Map<String, String> legacy = new LinkedHashMap<>();
		Map<String, String> byFile = new HashMap<>();
		for (Item it : items) {
			if (it.legacy != null && byFile.containsKey(it.legacy)) {
				legacy.put(it.legacy, byFile.get(it.legacy)); // one file, two records (inconsistent): one entry, the active one first
				continue;
			}
			String id = store.newId();
			long layer = store.newLayer();
			Entry e = entry(it, id, layer);
			entries.put(id, e);
			if (it.legacy != null) {
				legacy.put(it.legacy, id);
				byFile.put(it.legacy, id);
			}
			if (it.alias != null) {
				legacy.put(it.alias, id);
			}
		}
		return new Plan(entries, legacy, notes);
	}

	/** One thing to import. */
	private static final class Item {
		enum Kind {
			ROAD(0), ROAD_PENDING(0), BUILDING(1), BUILDING_PENDING(1), TROPHY(2);

			final int order;

			Kind(int order) {
				this.order = order;
			}
		}

		final Kind kind;
		final @Nullable String legacy;
		final @Nullable Path file;
		final String owner;
		final String dimension;
		final long time;
		final Anchors.@Nullable Bounds box;
		final JsonObject meta;
		final @Nullable Long undoneAt;
		@Nullable String alias;
		@Nullable Cell trophy;

		Item(Kind kind, @Nullable String legacy, @Nullable Path file, String owner, String dimension, long time, Anchors.@Nullable Bounds box, JsonObject meta,
			@Nullable Long undoneAt) {
			this.kind = kind;
			this.legacy = legacy;
			this.file = file;
			this.owner = owner;
			this.dimension = dimension;
			this.time = time;
			this.box = box;
			this.meta = meta;
			this.undoneAt = undoneAt;
		}
	}

	private static Entry entry(Item it, String id, long layer) throws IOException {
		switch (it.kind) {
			case TROPHY -> {
				Cell c = new Cell(it.trophy.pos(), layer, it.trophy.before(), it.trophy.after());
				return new Entry(id, "trophy", it.owner, it.dimension, Policy.CELL, it.time, Status.ACTIVE, List.of(c), null, it.meta);
			}
			case BUILDING, BUILDING_PENDING -> {
				CompoundTag tpl = NbtIo.readCompressed(it.file, NbtAccounter.unlimitedHeap());
				dev.agentcraft.compat.LegacyIds.remap(tpl);
				if (!JournalNbt.isTemplate(tpl)) {
					throw new IOException(it.legacy + " is not a building snapshot");
				}
				List<Cell> cells = JournalNbt.fromTemplate(tpl, null, it.box.minX(), it.box.minY(), it.box.minZ(), layer);
				Entry e = new Entry(id, "building", it.owner, it.dimension, Policy.BOX, it.time, Status.ACTIVE, cells, null, it.meta);
				return it.kind == Item.Kind.BUILDING ? e : e.undone(new Undo(id, it.undoneAt, allBefore(cells), List.of()));
			}
			default -> {
				CompoundTag road = NbtIo.readCompressed(it.file, NbtAccounter.unlimitedHeap());
				dev.agentcraft.compat.LegacyIds.remap(road);
				List<Cell> cells = roadCells(road, layer);
				Entry e = new Entry(id, "road", it.owner, it.dimension, Policy.CELL, it.time, Status.ACTIVE, cells, null, it.meta);
				return it.kind == Item.Kind.ROAD ? e : e.undone(new Undo(id, it.undoneAt, allBefore(cells), List.of()));
			}
		}
	}

	/** A removal's file holds the cells it put back: the undo wrote each one's before. */
	private static Map<Long, Value> allBefore(List<Cell> cells) {
		Map<Long, Value> w = new LinkedHashMap<>();
		for (Cell c : cells) {
			w.put(c.pos(), c.before());
		}
		return w;
	}

	/** A road snapshot {@code {version: 1, cells: [{x, y, z, before, after}]}} as cells. Pure. */
	static List<Cell> roadCells(CompoundTag root, long layer) {
		List<Cell> out = new ArrayList<>();
		for (Tag t : root.getListOrEmpty("cells")) {
			if (!(t instanceof CompoundTag c)) {
				continue;
			}
			out.add(new Cell(Journal.pos(c.getIntOr("x", 0), c.getIntOr("y", 0), c.getIntOr("z", 0)), layer, new Value(c.getCompoundOrEmpty("before"), null),
				new Value(c.getCompoundOrEmpty("after"), null)));
		}
		return out;
	}

	private static Building.FileData readBuildings(Path worldDir) throws IOException {
		Path f = worldDir.resolve(BUILDINGS_FILE);
		if (!Files.exists(f)) {
			return new Building.FileData(List.of(), 1, List.of());
		}
		try {
			return Building.fileFromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (RuntimeException e) {
			throw new IOException(BUILDINGS_FILE + " could not be read (" + e.getMessage() + "): the journal import waits until it is fixed", e);
		}
	}

	private static Road.FileData readRoads(Path worldDir) throws IOException {
		Path f = worldDir.resolve(ROADS_FILE);
		if (!Files.exists(f)) {
			return Road.FileData.EMPTY;
		}
		try {
			return Road.FileData.fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (RuntimeException e) {
			throw new IOException(ROADS_FILE + " could not be read (" + e.getMessage() + "): the journal import waits until it is fixed", e);
		}
	}

	/** For tests: the template a building entry's befores make again (equal to the imported snapshot's blocks). */
	static ListTag blocks(CompoundTag tpl) {
		return tpl.getListOrEmpty("blocks");
	}
}
