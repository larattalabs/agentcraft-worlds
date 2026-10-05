package dev.agentcraft.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.HandDown;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Undo;
import dev.agentcraft.journal.Journal.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The journal on disk: entry files round trip, generations, the index as the commit point, tidying at open. */
class JournalStoreTest {
	@TempDir
	Path world;

	static Entry entry(String id, long layer) {
		CompoundTag chest = new CompoundTag();
		chest.putString("id", "minecraft:chest");
		Value before = Value.of("minecraft:chest", "facing", "east").withNbt(chest);
		return new Entry(id, "building", "b1", "minecraft:overworld", Policy.BOX, 123L, Status.ACTIVE,
			List.of(new Cell(Journal.pos(1, 64, 2), layer, before, Journal.AIR), new Cell(Journal.pos(1, 65, 2), layer, Journal.AIR, null)), null, null);
	}

	@Test
	void entriesRoundTripWithUndoAndHandDowns() {
		JsonObject meta = new JsonObject();
		meta.addProperty("id", "b1");
		Entry e = entry("j1", 4).withMeta(meta);
		Value sign = Value.of("minecraft:oak_sign").withNbt(new CompoundTag());
		Entry u = e.undone(new Undo("j1", 99L, Map.of(Journal.pos(1, 64, 2), e.cells().get(0).before()),
			List.of(new HandDown(0, "j7", Journal.pos(1, 65, 2), Journal.AIR, sign))));
		Entry back = JournalNbt.decode(JournalNbt.encode(u));
		assertEquals(u, back);
		assertEquals(Status.UNDONE, back.status());
		assertEquals(sign, back.undo().handed().get(0).now());
		assertEquals("b1", back.meta().get("id").getAsString());
	}

	@Test
	void commitsWriteANewGenerationAndTheIndexLast() throws IOException {
		JournalStore s = JournalStore.open(world);
		assertFalse(JournalStore.exists(world));
		String id = s.newId();
		Entry e = entry(id, s.newLayer());
		s.writeDraft(e);
		assertTrue(Files.exists(s.dir().resolve(id + ".0.nbt")));
		s.commit(Map.of(id, e), List.of());
		assertTrue(JournalStore.exists(world));
		assertFalse(Files.exists(s.dir().resolve(id + ".0.nbt")), "the draft goes with the commit");
		assertTrue(Files.exists(s.dir().resolve(id + ".1.nbt")));
		Entry changed = e.withCells(List.of(e.cells().get(0).withBefore(Journal.AIR)));
		s.commit(Map.of(id, changed), List.of());
		assertFalse(Files.exists(s.dir().resolve(id + ".1.nbt")));
		assertEquals(changed, s.load(id));
		JournalStore r = JournalStore.open(world);
		assertEquals(changed, r.load(id));
		assertEquals(2, r.meta(id).gen());
		assertEquals(1, r.meta(id).cells());
		String next = r.newId();
		assertNotEquals(id, next, "ids are never reused");
		r.commit(Map.of(), List.of(id));
		assertTrue(JournalStore.open(world).index().entries().isEmpty());
		assertFalse(Files.exists(s.dir().resolve(id + ".2.nbt")));
	}

	@Test
	void openTidiesLeftoversAndKeepsUncommittedChanges() throws IOException {
		JournalStore s = JournalStore.open(world);
		String id = s.newId();
		s.commit(Map.of(id, entry(id, s.newLayer())), List.of());
		// a commit that wrote generation 2 and crashed before the index; a change that never committed; a temp file
		Files.copy(s.dir().resolve(id + ".1.nbt"), s.dir().resolve(id + ".2.nbt"));
		String draft = s.newId();
		s.writeDraft(entry(draft, s.newLayer()));
		Files.writeString(s.dir().resolve("journal.json.tmp"), "{", StandardCharsets.UTF_8);
		JournalStore r = JournalStore.open(world);
		assertFalse(Files.exists(s.dir().resolve(id + ".2.nbt")));
		assertTrue(Files.exists(s.dir().resolve(id + ".1.nbt")));
		assertFalse(Files.exists(s.dir().resolve("journal.json.tmp")));
		assertEquals(List.of(draft + ".0.nbt"), r.unreferenced());
		assertTrue(Files.exists(s.dir().resolve(draft + ".0.nbt")), "kept for a look by hand");
		assertNotEquals(draft, r.newId(), "a draft's id is not handed out again");
	}

	@Test
	void anUnreadableIndexIsAnError() throws IOException {
		Files.createDirectories(JournalStore.dirOf(world));
		Files.writeString(JournalStore.dirOf(world).resolve(JournalStore.INDEX), "{ nope", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> JournalStore.open(world));
	}

	@Test
	void metasFindByBox() throws IOException {
		JournalStore s = JournalStore.open(world);
		String id = s.newId();
		s.commit(Map.of(id, entry(id, s.newLayer())), List.of());
		JournalStore.Meta m = s.meta(id);
		assertTrue(m.intersects("minecraft:overworld", new int[] {0, 60, 0, 1, 64, 2}));
		assertFalse(m.intersects("minecraft:the_nether", new int[] {0, 60, 0, 1, 64, 2}));
		assertFalse(m.intersects("minecraft:overworld", new int[] {2, 60, 0, 5, 70, 5}));
	}
}
