package dev.agentcraft.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/**
 * The layering rules of the world journal (contract J1): place A, place B over it, undo in either order, with cells the
 * player changed in between; hand-downs reversed on reactivation; transfers, releases and absorbs.
 */
class JournalTest {
	static final Value T = Value.of("minecraft:grass_block");
	static final Value ROAD = Value.of("minecraft:dirt_path");
	static final Value BOARD = Value.of("minecraft:spruce_planks");
	static final Value SIGN = Value.of("minecraft:dark_oak_wall_sign", "facing", "north");
	static final Value PLAYER = Value.of("minecraft:cobblestone");
	static final long P = Journal.pos(10, 64, -3);
	static final long Q = Journal.pos(11, 64, -3);

	/** A tiny world + journal: changes are made the way the server makes them (before read from the world). */
	static final class Sim {
		final Map<Long, Value> world = new HashMap<>();
		final Map<String, Entry> entries = new LinkedHashMap<>();
		long layer = 1;
		final List<Value> log = new ArrayList<>();

		Value at(long p) {
			return world.getOrDefault(p, T);
		}

		/** Makes a change: {@code to} at each position. */
		Entry change(String id, Policy policy, Map<Long, Value> to) {
			List<Cell> cells = new ArrayList<>();
			long l = layer++;
			for (var t : to.entrySet()) {
				cells.add(new Cell(t.getKey(), l, at(t.getKey()), t.getValue()));
				world.put(t.getKey(), t.getValue());
			}
			Entry e = new Entry(id, policy == Policy.BOX ? "building" : "road", id, "minecraft:overworld", policy, l, Status.ACTIVE, cells, null, null);
			entries.put(id, e);
			return e;
		}

		Journal.UndoPlan undo(String... ids) {
			Journal.UndoPlan p = Journal.planUndo(entries.values(), List.of(ids), ids[0], 0L, (pos, after) -> at(pos).equals(after), Journal.Match.EQUAL);
			for (Journal.Write w : p.writes()) {
				world.put(w.pos(), w.value());
				log.add(w.value());
			}
			entries.putAll(p.updated());
			return p;
		}

		void release(String id) {
			entries.remove(id);
		}

		void reactivate(String group) {
			entries.putAll(Journal.reactivate(entries.values(), group));
		}

		/** What the stacks say the world shows: the top active cell's after, else the original ground. */
		Value expected(long p) {
			var s = Journal.stack(entries.values(), p);
			return s.isEmpty() ? T : s.get(s.size() - 1).getValue().after();
		}
	}

	// ------------------------------------------------------------------ two overlapping changes

	@Test
	void fixtureOverARoadUndoesInEitherOrder() {
		for (boolean roadFirst : new boolean[] {true, false}) {
			Sim s = new Sim();
			s.change("road", Policy.CELL, Map.of(P, ROAD, Q, ROAD));
			s.change("fixture", Policy.BOX, Map.of(P, BOARD));
			if (roadFirst) {
				Journal.UndoPlan r = s.undo("road");
				assertEquals(new Journal.Stats(1, 0, 1), r.stats().get("road"), "Q restored, P handed down to the fixture");
				assertEquals(BOARD, s.at(P), "the fixture stands: nothing written under it");
				assertEquals(T, s.at(Q));
				s.undo("fixture");
			} else {
				s.undo("fixture");
				assertEquals(ROAD, s.at(P), "the road comes back under the removed fixture");
				s.undo("road");
			}
			assertEquals(T, s.at(P), "no road resurrected (" + (roadFirst ? "road" : "fixture") + " first)");
			assertEquals(T, s.at(Q));
		}
	}

	@Test
	void aCellThePlayerChangedBeforeTheNewerChangeStaysThePlayers() {
		for (boolean olderFirst : new boolean[] {true, false}) {
			Sim s = new Sim();
			s.change("road", Policy.CELL, Map.of(P, ROAD));
			s.world.put(P, PLAYER); // the player paved over the road
			s.change("fixture", Policy.BOX, Map.of(P, BOARD));
			if (olderFirst) {
				s.undo("road");
				s.undo("fixture");
			} else {
				s.undo("fixture");
				s.undo("road");
			}
			assertEquals(PLAYER, s.at(P), "the player's block, in either order");
		}
	}

	@Test
	void aCellThePlayerChangedAfterTheNewerChangeIsLeftByACellEntry() {
		for (boolean olderFirst : new boolean[] {true, false}) {
			Sim s = new Sim();
			s.change("r1", Policy.CELL, Map.of(P, ROAD));
			s.change("r2", Policy.CELL, Map.of(P, BOARD));
			s.world.put(P, PLAYER);
			if (olderFirst) {
				s.undo("r1");
				s.undo("r2");
			} else {
				s.undo("r2");
				s.undo("r1");
			}
			assertEquals(PLAYER, s.at(P));
		}
	}

	@Test
	void aBoxEntryRevertsThePlayersChangesInsideItsBox() {
		// docs/BUILDINGS.md "Safe remove": Remove puts back exactly what was in the box
		for (boolean olderFirst : new boolean[] {true, false}) {
			Sim s = new Sim();
			s.change("a", Policy.BOX, Map.of(P, ROAD));
			s.change("b", Policy.BOX, Map.of(P, BOARD));
			s.world.put(P, PLAYER);
			if (olderFirst) {
				s.undo("a");
				s.undo("b");
			} else {
				s.undo("b");
				s.undo("a");
			}
			assertEquals(T, s.at(P));
		}
	}

	@Test
	void everyOrderOfThreeMixedLayersEndsOnTheGroundWithNoHoleInBetween() {
		String[][] orders = {{"road", "fixture", "trophy"}, {"road", "trophy", "fixture"}, {"fixture", "road", "trophy"},
			{"fixture", "trophy", "road"}, {"trophy", "road", "fixture"}, {"trophy", "fixture", "road"}};
		for (String[] order : orders) {
			Sim s = new Sim();
			s.change("road", Policy.CELL, Map.of(P, ROAD, Q, ROAD));
			s.change("fixture", Policy.BOX, Map.of(P, BOARD, Q, BOARD));
			s.change("trophy", Policy.CELL, Map.of(P, SIGN));
			for (String id : order) {
				s.undo(id);
				for (long p : new long[] {P, Q}) {
					assertEquals(s.expected(p), s.at(p), "after undoing " + id + " in " + String.join(",", order) + ": the world shows the top of the stack");
				}
			}
			assertEquals(T, s.at(P), String.join(",", order));
			assertEquals(T, s.at(Q), String.join(",", order));
		}
	}

	@Test
	void undoingAGroupTogetherWritesEachCellOnce() {
		Sim s = new Sim();
		s.change("building", Policy.BOX, Map.of(P, Journal.AIR, Q, BOARD));
		s.change("trophy", Policy.CELL, Map.of(P, SIGN));
		Journal.UndoPlan p = s.undo("building", "trophy");
		assertEquals(2, p.writes().size());
		assertEquals(T, s.at(P));
		assertEquals("building", p.writes().stream().filter(w -> w.pos() == P).findFirst().orElseThrow().by(), "the box restore writes the slot");
		assertEquals(Status.UNDONE, s.entries.get("trophy").status());
		assertEquals("building", s.entries.get("trophy").undo().group());
		assertEquals(T, s.entries.get("trophy").undo().written().get(P), "the trophy's cell records what the world shows now");
	}

	// ------------------------------------------------------------------ crash safety: reactivation

	static Value chest(int items) {
		CompoundTag nbt = new CompoundTag();
		nbt.putString("id", "minecraft:chest");
		ListTag list = new ListTag();
		CompoundTag stack = new CompoundTag();
		stack.putString("id", "minecraft:diamond");
		stack.putInt("count", items);
		list.add(stack);
		nbt.put("Items", list);
		return Value.of("minecraft:chest", "facing", "north").withNbt(nbt);
	}

	@Test
	void reactivationReversesHandDownsSoAChestNeverComesBackTwice() {
		Sim s = new Sim();
		s.world.put(P, chest(12));
		s.change("a", Policy.BOX, Map.of(P, Journal.AIR)); // a building over the player's chest (forced)
		s.change("b", Policy.CELL, Map.of(P, ROAD));
		s.undo("a"); // a under b: b's before becomes the chest, nothing written
		assertEquals(chest(12), s.entries.get("b").cell(P).before());
		s.reactivate("a"); // the removal never reached the disk
		assertEquals(Journal.AIR, s.entries.get("b").cell(P).before(), "the hand-down is reversed");
		s.undo("b");
		assertEquals(Journal.AIR, s.at(P), "b gives back a's block, not the chest a still covers");
		s.undo("a");
		assertEquals(chest(12), s.at(P));
		assertEquals(1, s.log.stream().filter(v -> v.equals(chest(12))).count(), "the chest is restored exactly once");
	}

	@Test
	void reactivationLeavesACellChangedAgainSince() {
		Sim s = new Sim();
		s.change("a", Policy.CELL, Map.of(P, ROAD));
		s.change("b", Policy.BOX, Map.of(P, BOARD));
		s.change("c", Policy.CELL, Map.of(Q, SIGN));
		s.undo("a"); // b.before: ROAD -> T
		// another hand-down changes b's before again (an entry under b at P undone later is impossible here, so edit it)
		Entry b = s.entries.get("b");
		s.entries.put("b", b.withCells(List.of(b.cell(P).withBefore(PLAYER))));
		s.reactivate("a");
		assertEquals(PLAYER, s.entries.get("b").cell(P).before(), "not reverted over a newer change");
		assertEquals(Status.ACTIVE, s.entries.get("a").status());
	}

	// ------------------------------------------------------------------ transfer, release, absorb

	@Test
	void transferredCellsKeepTheirLayerAndUndoWithTheirNewOwner() {
		Sim s = new Sim();
		s.change("r1", Policy.CELL, Map.of(P, ROAD, Q, ROAD));
		s.change("r2", Policy.CELL, Map.of(Journal.pos(12, 64, -3), ROAD));
		long l1 = s.entries.get("r1").cell(Q).layer();
		s.entries.putAll(Journal.transfer(s.entries.get("r1"), s.entries.get("r2"), Set.of(Q)));
		assertEquals(l1, s.entries.get("r2").cell(Q).layer());
		assertNull(s.entries.get("r1").cell(Q));
		s.change("fixture", Policy.BOX, Map.of(Q, BOARD)); // over the transferred cell
		s.undo("r1");
		assertEquals(T, s.at(P));
		assertEquals(BOARD, s.at(Q));
		s.undo("r2"); // owns Q under the fixture: handed down
		assertEquals(BOARD, s.at(Q));
		s.undo("fixture");
		assertEquals(T, s.at(Q), "the road the cell was transferred to is gone: the ground comes back");
	}

	@Test
	void transferLeavesPositionsTheReceiverHas() {
		Sim s = new Sim();
		s.change("r1", Policy.CELL, Map.of(P, ROAD));
		s.change("r2", Policy.CELL, Map.of(P, BOARD));
		var out = Journal.transfer(s.entries.get("r1"), s.entries.get("r2"), Set.of(P));
		assertNotNull(out.get("r1").cell(P));
		assertEquals(1, out.get("r2").cells().size());
	}

	@Test
	void forgettingTheNewerEntryLeavesItsBlocksForGood() {
		Sim s = new Sim();
		s.change("road", Policy.CELL, Map.of(P, ROAD));
		s.change("fixture", Policy.BOX, Map.of(P, BOARD));
		s.release("fixture"); // Forget: the record goes, the blocks stay
		Journal.UndoPlan p = s.undo("road");
		assertEquals(new Journal.Stats(0, 1, 0), p.stats().get("road"));
		assertEquals(BOARD, s.at(P), "removing the road leaves the forgotten fixture's blocks");
	}

	@Test
	void forgettingTheOlderEntryKeepsItsBlocksUnderTheNewerOne() {
		Sim s = new Sim();
		s.change("road", Policy.CELL, Map.of(P, ROAD));
		s.change("fixture", Policy.BOX, Map.of(P, BOARD));
		s.release("road");
		s.undo("fixture");
		assertEquals(ROAD, s.at(P), "the forgotten road's block is the ground now");
	}

	@Test
	void anOlderTrophyCoveredByANewerOneIsAbsorbed() {
		Sim s = new Sim();
		s.change("t1", Policy.CELL, Map.of(P, SIGN));
		Value sign2 = SIGN.withNbt(new CompoundTag());
		s.change("t2", Policy.CELL, Map.of(P, sign2));
		var out = Journal.absorb(s.entries.values(), "t1");
		assertNotNull(out);
		s.entries.putAll(out);
		s.release("t1");
		assertEquals(T, s.entries.get("t2").cell(P).before());
		s.undo("t2");
		assertEquals(T, s.at(P));
	}

	@Test
	void anEntryStillOnTopIsNotAbsorbed() {
		Sim s = new Sim();
		s.change("t1", Policy.CELL, Map.of(P, SIGN, Q, SIGN));
		s.change("t2", Policy.CELL, Map.of(P, BOARD));
		assertNull(Journal.absorb(s.entries.values(), "t1"), "its cell at Q still shows");
	}

	@Test
	void unknownAfterNeverMatchesForACellEntry() {
		Sim s = new Sim();
		s.entries.put("old", new Entry("old", "road", "old", "minecraft:overworld", Policy.CELL, 0L, Status.ACTIVE,
			List.of(new Cell(P, 0, T, null)), null, null));
		s.world.put(P, ROAD);
		Journal.UndoPlan p = s.undo("old");
		assertTrue(p.writes().isEmpty());
		assertEquals(ROAD, s.at(P));
	}

	@Test
	void theStackIsOrderedByLayer() {
		Sim s = new Sim();
		s.change("a", Policy.CELL, Map.of(P, ROAD));
		s.change("b", Policy.BOX, Map.of(P, BOARD));
		var st = Journal.stack(s.entries.values(), P);
		assertEquals(List.of("a", "b"), st.stream().map(e -> e.getKey().id()).toList());
		s.undo("a");
		assertEquals(List.of("b"), Journal.stack(s.entries.values(), P).stream().map(e -> e.getKey().id()).toList());
	}
}
