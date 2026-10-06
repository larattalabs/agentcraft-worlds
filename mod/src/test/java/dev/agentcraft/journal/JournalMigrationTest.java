package dev.agentcraft.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.building.Building;
import dev.agentcraft.building.Road;
import dev.agentcraft.building.TrophyLedger;
import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Value;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one-time import of the older records into the world journal, against files in the exact formats the older code
 * wrote: structure-template building snapshots ({@code size}, {@code palette}, {@code blocks} with {@code pos/state/nbt},
 * {@code entities}, {@code DataVersion}), per-cell road snapshots ({@code version}, {@code cells} of {@code x, y, z,
 * before, after}) and the buildings / roads / trophies JSON.
 */
class JournalMigrationTest {
	static final Anchors.Bounds BOX3 = new Anchors.Bounds(10, 64, 20, 11, 65, 21); // 2 x 2 x 2
	static final Anchors.Bounds BOX5 = new Anchors.Bounds(40, 64, 20, 41, 64, 20); // 2 x 1 x 1
	static final Anchors.Bounds BOX3_OLD = new Anchors.Bounds(-30, 70, 5, -29, 70, 5); // where b3 stood before its move

	@TempDir
	Path world;

	static CompoundTag state(String name, String... props) {
		return Value.of(name, props).state();
	}

	static ListTag ints(int... v) {
		ListTag l = new ListTag();
		for (int i : v) {
			l.add(IntTag.valueOf(i));
		}
		return l;
	}

	static CompoundTag chestNbt() {
		CompoundTag nbt = new CompoundTag();
		nbt.putString("id", "minecraft:chest");
		ListTag items = new ListTag();
		CompoundTag diamond = new CompoundTag();
		diamond.putString("id", "minecraft:diamond");
		diamond.putInt("count", 12);
		diamond.putByte("Slot", (byte) 0);
		items.add(diamond);
		nbt.put("Items", items);
		return nbt;
	}

	/** A building snapshot as {@code StructureTemplate.save} wrote it: full blocks, then block entities, then the rest. */
	static CompoundTag buildingSnapshot(Anchors.Bounds box, boolean chest) {
		CompoundTag t = new CompoundTag();
		ListTag palette = new ListTag();
		palette.add(state("minecraft:dirt"));
		palette.add(state("minecraft:air"));
		palette.add(state("minecraft:chest", "facing", "north", "type", "single", "waterlogged", "false"));
		palette.add(state("minecraft:short_grass"));
		ListTag blocks = new ListTag();
		int sx = box.maxX() - box.minX() + 1;
		int sy = box.maxY() - box.minY() + 1;
		int sz = box.maxZ() - box.minZ() + 1;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					CompoundTag b = new CompoundTag();
					b.put("pos", ints(x, y, z));
					boolean isChest = chest && x == 1 && y == sy - 1 && z == 0;
					b.putInt("state", y == 0 ? 0 : isChest ? 2 : x == 0 && z == 1 ? 3 : 1);
					if (isChest) {
						b.put("nbt", chestNbt());
					}
					blocks.add(b);
				}
			}
		}
		t.put("size", ints(sx, sy, sz));
		t.put("entities", new ListTag());
		t.put("blocks", blocks);
		t.put("palette", palette);
		t.putInt("DataVersion", 4790);
		return t;
	}

	/** A road snapshot as {@code Roads.writeSnapshot} wrote it. */
	static CompoundTag roadSnapshot(int[][] cells) {
		CompoundTag root = new CompoundTag();
		root.putInt("version", 1);
		ListTag list = new ListTag();
		for (int[] c : cells) {
			CompoundTag t = new CompoundTag();
			t.putInt("x", c[0]);
			t.putInt("y", c[1]);
			t.putInt("z", c[2]);
			t.put("before", state("minecraft:grass_block", "snowy", "false"));
			t.put("after", c[1] == 64 ? state("minecraft:dirt_path") : state("minecraft:mud_brick_slab", "type", "bottom", "waterlogged", "false"));
			list.add(t);
		}
		root.put("cells", list);
		return root;
	}

	static Road road(String id, String a, String b, long created) {
		return new Road(id, a, b, "minecraft:overworld", 2, true, false, created, 3, new int[] {12, 65, 30}, new int[0], new int[] {12, 64, 30, 13, 64, 30},
			List.of());
	}

	static Building building(String id, Anchors.Bounds box, long placedAt, Building.Pin pin) {
		return new Building(id, "workshop", List.of("pocket-notes"), true, "none", box, box, Map.of(), placedAt, "minecraft:overworld", null, placedAt, null,
			pin);
	}

	/** The world folder of an older world: b3 (moved here from BOX3_OLD), b5 removed, fixture b6, road r2, road r4 removed, road r7 whose removal never renamed its snapshot, a trophy in b3. */
	void oldWorld() throws IOException {
		Path bdir = Files.createDirectories(world.resolve(JournalMigration.BUILDINGS_DIR));
		Path rdir = Files.createDirectories(world.resolve(JournalMigration.ROADS_DIR));
		Building.Pin pin = new Building.Pin("abc", 1, false, Map.of("trophy@1", new Anchor("trophy@1", 10.5, 65.5, 21.5, 180f, 0f)), List.of(1, 1, 0));
		Building b3 = building("b3", BOX3, 2000L, pin);
		Building b3old = building("b3", BOX3_OLD, 1000L, pin);
		Building b5 = building("b5", BOX5, 1500L, null);
		Building b6 = new Building("b6", "village_board", List.of(), false, "none", BOX5, BOX5, Map.of(), 3000L, "minecraft:overworld");
		Files.writeString(world.resolve(JournalMigration.BUILDINGS_FILE), Building.fileJson(List.of(b3, b6), 7,
			List.of(new Building.Pending(b3old, "b3.moved-1700.nbt", 1700L, "moved"), new Building.Pending(b5, "b5.before.nbt", 1800L, "removed"))).toString(),
			StandardCharsets.UTF_8);
		NbtIo.writeCompressed(buildingSnapshot(BOX3, true), bdir.resolve("b3.before.nbt"));
		NbtIo.writeCompressed(buildingSnapshot(BOX3_OLD, false), bdir.resolve("b3.moved-1700.nbt"));
		NbtIo.writeCompressed(buildingSnapshot(BOX5, false), bdir.resolve("b5.before.nbt"));
		NbtIo.writeCompressed(buildingSnapshot(BOX5, false), bdir.resolve("b6.before.nbt"));
		NbtIo.writeCompressed(buildingSnapshot(BOX5, false), bdir.resolve("b2.unused-99.nbt")); // a leftover no record names
		Road r2 = road("r2", "b3", "b6", 2500L);
		Road r4 = road("r4", "b3", "b5", 1200L);
		Road r7 = road("r7", "b6", "b5", 2600L);
		Files.writeString(world.resolve(JournalMigration.ROADS_FILE), new Road.FileData(List.of(r2), 8,
			List.of(new Road.Pending(r4, "r4.removed-1900.nbt", 1900L), new Road.Pending(r7, "r7.removed-2700.nbt", 2700L))).toJson().toString(),
			StandardCharsets.UTF_8);
		NbtIo.writeCompressed(roadSnapshot(new int[][] {{12, 64, 30}, {13, 64, 30}, {13, 65, 30}}), rdir.resolve("r2.before.nbt"));
		NbtIo.writeCompressed(roadSnapshot(new int[][] {{20, 64, 30}}), rdir.resolve("r4.removed-1900.nbt"));
		NbtIo.writeCompressed(roadSnapshot(new int[][] {{30, 64, 30}, {31, 64, 30}}), rdir.resolve("r7.before.nbt")); // never renamed
		TrophyLedger l = new TrophyLedger();
		l.markAwarded("pr:pocket-notes:12");
		l.put("b3", "trophy@1", new TrophyLedger.Entry("pr:pocket-notes:12", List.of("Merged PR #12", "", "", "2026-10-01"), 2200L));
		l.save(world.resolve(TrophyLedger.FILE));
	}

	/** The sign as {@code WorldJournal.valueAt} reads it: {@code NbtUtils.writeBlockState}'s 26.x keys, built by hand (not by {@link Value#of}). */
	static final Value SIGN = new Value(worldState("minecraft:dark_oak_wall_sign", "facing", "north", "waterlogged", "false"), new CompoundTag());

	static CompoundTag worldState(String id, String... props) {
		CompoundTag t = new CompoundTag();
		t.putString("id", id);
		if (props.length > 0) {
			CompoundTag p = new CompoundTag();
			for (int i = 0; i + 1 < props.length; i += 2) {
				p.putString(props[i], props[i + 1]);
			}
			t.put("properties", p);
		}
		return t;
	}

	/** A block state in the older form ({@code Name}/{@code Properties}), as files written before 26.x hold them. */
	static CompoundTag older(CompoundTag state) {
		CompoundTag t = state.copy();
		t.put("Name", t.get("id"));
		t.remove("id");
		if (t.get("properties") != null) {
			t.put("Properties", t.get("properties"));
			t.remove("properties");
		}
		return t;
	}

	/** Rewrites the old world's snapshot files with their block states in the older form (palettes; roads' before/after). */
	void olderKeys() throws IOException {
		for (Path f : List.of(world.resolve(JournalMigration.BUILDINGS_DIR).resolve("b3.before.nbt"),
			world.resolve(JournalMigration.BUILDINGS_DIR).resolve("b5.before.nbt"))) {
			CompoundTag t = NbtIo.readCompressed(f, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
			ListTag pal = new ListTag();
			for (int i = 0; i < t.getListOrEmpty("palette").size(); i++) {
				pal.add(older(t.getListOrEmpty("palette").getCompoundOrEmpty(i)));
			}
			t.put("palette", pal);
			NbtIo.writeCompressed(t, f);
		}
		Path r = world.resolve(JournalMigration.ROADS_DIR).resolve("r2.before.nbt");
		CompoundTag t = NbtIo.readCompressed(r, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
		for (int i = 0; i < t.getListOrEmpty("cells").size(); i++) {
			CompoundTag c = t.getListOrEmpty("cells").getCompoundOrEmpty(i);
			c.put("before", older(c.getCompoundOrEmpty("before")));
			c.put("after", older(c.getCompoundOrEmpty("after")));
		}
		NbtIo.writeCompressed(t, r);
	}

	/** The world: the trophy sign hangs at b3's slot (10, 65, 21). */
	static final JournalMigration.SlotReader READER = (dim, x, y, z) -> x == 10 && y == 65 && z == 21 ? SIGN : Journal.AIR;

	Entry only(JournalStore s, String kind, String owner, Status status) throws IOException {
		List<JournalStore.Meta> ms = s.find(m -> m.kind().equals(kind) && m.owner().equals(owner) && m.status() == status);
		assertEquals(1, ms.size(), kind + " " + owner + " " + status + ": " + ms);
		return s.load(ms.get(0).id());
	}

	@Test
	void importsEveryRecordLosslesslyAndOnlyOnce() throws IOException {
		oldWorld();
		assertTrue(JournalMigration.needed(world));
		JournalStore s = JournalStore.open(world);
		JournalMigration.Plan p = JournalMigration.run(world, s, READER);
		assertEquals(8, p.entries().size(), "b3, b3 old site, b5, b6, r2, r4, r7, the trophy: " + p.entries().keySet());

		// b3: active BOX entry, its snapshot's blocks exactly (the chest with its items included)
		Entry b3 = only(s, "building", "b3", Status.ACTIVE);
		assertEquals(Policy.BOX, b3.policy());
		assertEquals(8, b3.cells().size());
		CompoundTag original = buildingSnapshot(BOX3, true);
		Map<Long, Value> want = JournalNbt.values(original, BOX3.minX(), BOX3.minY(), BOX3.minZ());
		for (Cell c : b3.cells()) {
			assertEquals(want.get(c.pos()), c.before());
			assertNull(c.after(), "an imported snapshot has no after");
		}
		Value chest = b3.cell(Journal.pos(11, 65, 20)).before();
		assertEquals("minecraft:chest", chest.name());
		assertEquals(chestNbt(), chest.nbt());
		// the template the box restore pastes is the snapshot's blocks, in its order
		CompoundTag again = JournalNbt.beforeTemplate(b3, b3.box());
		assertEquals(JournalNbt.values(original, 0, 0, 0), JournalNbt.values(again, 0, 0, 0));
		assertEquals(original.getListOrEmpty("size"), again.getListOrEmpty("size"));
		assertEquals("b3", b3.meta().get("id").getAsString(), "the record is the meta");

		// pending sites: undone entries whose undo wrote the box
		Entry moved = only(s, "building", "b3", Status.UNDONE);
		assertEquals(BOX3_OLD.minX(), moved.box()[0]);
		assertEquals(moved.cells().size(), moved.undo().written().size());
		assertEquals(1700L, moved.undo().at());
		Entry b5 = only(s, "building", "b5", Status.UNDONE);
		assertEquals(2, b5.cells().size());
		only(s, "building", "b6", Status.ACTIVE);

		// roads
		Entry r2 = only(s, "road", "r2", Status.ACTIVE);
		assertEquals(Policy.CELL, r2.policy());
		assertEquals(3, r2.cells().size());
		Cell slab = r2.cell(Journal.pos(13, 65, 30));
		assertEquals(Value.of("minecraft:grass_block", "snowy", "false"), slab.before());
		assertEquals(Value.of("minecraft:mud_brick_slab", "type", "bottom", "waterlogged", "false"), slab.after());
		Entry r4 = only(s, "road", "r4", Status.UNDONE);
		assertEquals(1, r4.undo().written().size());
		Entry r7 = only(s, "road", "r7", Status.UNDONE);
		assertEquals(2, r7.cells().size());

		// the trophy hangs over b3, in a higher layer
		Entry trophy = only(s, "trophy", "b3", Status.ACTIVE);
		Cell sign = trophy.cells().get(0);
		assertEquals(SIGN, sign.after());
		assertEquals(Journal.AIR, sign.before());
		assertTrue(sign.layer() > b3.cell(sign.pos()).layer());
		assertEquals("trophy@1", trophy.meta().get("slot").getAsString());

		// every pending record's file name resolves
		Map<String, String> legacy = s.index().legacy();
		assertEquals(moved.id(), legacy.get("agentcraft-buildings/b3.moved-1700.nbt"));
		assertEquals(b5.id(), legacy.get("agentcraft-buildings/b5.before.nbt"));
		assertEquals(b3.id(), legacy.get("agentcraft-buildings/b3.before.nbt"));
		assertEquals(r4.id(), legacy.get("agentcraft-roads/r4.removed-1900.nbt"));
		assertEquals(r7.id(), legacy.get("agentcraft-roads/r7.removed-2700.nbt"), "the pending's own name resolves to the never-renamed file's entry");
		assertNull(legacy.get("agentcraft-buildings/b2.unused-99.nbt"));

		// layers follow time: the older road under the buildings placed after it
		assertTrue(r2.cells().get(0).layer() > b3.cells().get(0).layer(), "r2 (2500) after b3 (2000)");

		// the old folders moved to the journal's legacy folder, every file kept
		assertFalse(Files.exists(world.resolve(JournalMigration.BUILDINGS_DIR)));
		assertFalse(Files.exists(world.resolve(JournalMigration.ROADS_DIR)));
		Path legacyDir = s.dir().resolve(JournalMigration.LEGACY_DIR);
		assertTrue(Files.exists(legacyDir.resolve("agentcraft-buildings/b2.unused-99.nbt")));
		assertTrue(Files.exists(legacyDir.resolve("agentcraft-roads/r7.before.nbt")));

		// a second start imports nothing again
		assertTrue(JournalStore.exists(world));
		assertFalse(JournalMigration.needed(world));
		JournalStore reopened = JournalStore.open(world);
		assertEquals(s.index().entries().keySet(), reopened.index().entries().keySet());
		assertEquals(b3.cells(), reopened.load(b3.id()).cells());
	}

	@Test
	void snapshotsWithTheOlderKeysImportAsTheSameBlocks() throws IOException {
		oldWorld();
		olderKeys();
		JournalStore s = JournalStore.open(world);
		JournalMigration.run(world, s, READER);
		Entry b3 = only(s, "building", "b3", Status.ACTIVE);
		Map<Long, Value> want = JournalNbt.values(buildingSnapshot(BOX3, true), BOX3.minX(), BOX3.minY(), BOX3.minZ());
		for (Cell c : b3.cells()) {
			assertEquals(want.get(c.pos()), c.before(), "{Name, Properties} read as {id, properties}");
			assertNull(c.before().state().get("Name"));
		}
		assertEquals("minecraft:chest", b3.cell(Journal.pos(11, 65, 20)).before().name());
		assertEquals(chestNbt(), b3.cell(Journal.pos(11, 65, 20)).before().nbt(), "block entity data is left as it is");
		Cell slab = only(s, "road", "r2", Status.ACTIVE).cell(Journal.pos(13, 65, 30));
		assertEquals(new Value(worldState("minecraft:grass_block", "snowy", "false"), null), slab.before());
		assertEquals(new Value(worldState("minecraft:mud_brick_slab", "type", "bottom", "waterlogged", "false"), null), slab.after());
		// the restore template of the imported box is in 26.x's form (NbtUtils.readBlockState reads only id/properties)
		ListTag pal = JournalNbt.beforeTemplate(b3, b3.box()).getListOrEmpty("palette");
		for (int i = 0; i < pal.size(); i++) {
			assertTrue(pal.getCompoundOrEmpty(i).contains("id"), pal.toString());
		}
		// written back in 26.x's form, read again the same
		assertEquals(b3.cells(), JournalStore.open(world).load(b3.id()).cells());
	}

	@Test
	void aTrophySignReadFromTheWorldIsImported() throws IOException {
		// the world's sign has 26.x keys; the import used to compare Value.name() (which read Name) and never found it
		oldWorld();
		JournalStore s = JournalStore.open(world);
		JournalMigration.Plan p = JournalMigration.plan(world, s, READER);
		assertEquals(1, p.entries().values().stream().filter(e -> e.kind().equals("trophy")).count());
		Entry trophy = p.entries().values().stream().filter(e -> e.kind().equals("trophy")).findFirst().orElseThrow();
		assertEquals("minecraft:dark_oak_wall_sign", trophy.cells().get(0).after().name());
		assertEquals(new Value(worldState("minecraft:air"), null), trophy.cells().get(0).before(), "Journal.AIR is the world's air");
	}

	@Test
	void aCrashAfterTheCommitOnlyFinishesTheFolderMove() throws IOException {
		oldWorld();
		JournalStore s = JournalStore.open(world);
		JournalMigration.Plan p = JournalMigration.plan(world, s, READER);
		s.commit(p.entries(), List.of(), p.legacy()); // the commit reached the disk, the folders did not move
		assertTrue(JournalStore.exists(world));
		assertTrue(JournalMigration.needed(world));
		JournalMigration.moveLegacy(world, s.dir()); // what the next start does instead of importing again
		assertFalse(JournalMigration.needed(world));
		assertEquals(p.entries().size(), JournalStore.open(world).index().entries().size());
	}

	@Test
	void anUnreadableRecordFileAbortsTheImportAndWritesNothing() throws IOException {
		oldWorld();
		Files.writeString(world.resolve(JournalMigration.BUILDINGS_FILE), "{ not json", StandardCharsets.UTF_8);
		JournalStore s = JournalStore.open(world);
		assertThrows(IOException.class, () -> JournalMigration.run(world, s, READER));
		assertFalse(JournalStore.exists(world));
		assertTrue(Files.exists(world.resolve(JournalMigration.BUILDINGS_DIR).resolve("b3.before.nbt")), "nothing moved");
	}

	@Test
	void withoutTheWorldNoTrophyEntryIsMade() throws IOException {
		oldWorld();
		JournalStore s = JournalStore.open(world);
		JournalMigration.Plan p = JournalMigration.plan(world, s, JournalMigration.SlotReader.NONE);
		assertEquals(7, p.entries().size());
		assertTrue(p.entries().values().stream().noneMatch(e -> e.kind().equals("trophy")));
	}

	@Test
	void anImportedBuildingUndoesWithItsChestOnce() throws IOException {
		oldWorld();
		JournalStore s = JournalStore.open(world);
		JournalMigration.run(world, s, READER);
		Entry b3 = only(s, "building", "b3", Status.ACTIVE);
		Entry trophy = only(s, "trophy", "b3", Status.ACTIVE);
		Journal.UndoPlan plan = Journal.planUndo(List.of(b3, trophy), List.of(b3.id(), trophy.id()), b3.id(), 1L, (p, a) -> true, Journal.Match.EQUAL);
		assertEquals(8, plan.writes().size(), "the whole box, each cell once");
		assertEquals(1, plan.writes().stream().filter(w -> w.value().name().equals("minecraft:chest")).count());
		assertTrue(plan.writes().stream().allMatch(w -> w.policy() == Policy.BOX));
		assertNotNull(plan.updated().get(trophy.id()).undo());
	}
}
