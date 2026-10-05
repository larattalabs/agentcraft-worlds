package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.journal.Journal;
import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Value;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Held leaves (docs/BUILDINGS.md "Held leaves"): which leaves may hang on a box, and the hold as a CELL journal entry
 * undone with its site in any order, with vanilla changing a held leaf's distance meanwhile.
 */
class LeafGuardTest {
	static final Anchors.Bounds BOX = new Anchors.Bounds(0, 64, 0, 9, 72, 9);

	static Value leaf(int distance, boolean persistent) {
		return Value.of("minecraft:oak_leaves", "distance", Integer.toString(distance), "persistent", Boolean.toString(persistent), "waterlogged",
			"false");
	}

	static final Value GROUND = Value.of("minecraft:grass_block", "snowy", "false");
	static final Value PLANKS = Value.of("minecraft:oak_planks");
	static final Value AIR = Journal.AIR;
	static final long L = Journal.pos(11, 68, 4); // two east of the box
	static final long G = Journal.pos(4, 63, 4); // under the box

	static final long R = Journal.pos(14, 70, 4); // five east of the box: a ring leaf, too far to hang on the box
	static final long R2 = Journal.pos(-3, 66, 2); // three west of the box: another ring leaf

	/**
	 * {@link LeafGuard#or} on journal values: a persistent leaf is the same block and still persistent
	 * ({@link LeafGuard#stillHeld}), a natural one the same block, still natural, same waterlogged ({@link LeafGuard#sameLeaf});
	 * a leaf's distance may move. Anything else must be equal.
	 */
	static boolean same(Value now, Value placed) {
		if (persistent(placed)) {
			return now.name().equals(placed.name()) && persistent(now);
		}
		if (placed.name().endsWith("_leaves")) {
			return now.name().equals(placed.name()) && !persistent(now) && prop(now, "waterlogged").equals(prop(placed, "waterlogged"));
		}
		return now.equals(placed);
	}

	static String prop(Value v, String key) {
		return v.state().getCompoundOrEmpty("Properties").getStringOr(key, "");
	}

	static boolean persistent(Value v) {
		return v.name().endsWith("_leaves") && "true".equals(prop(v, "persistent"));
	}

	static Value wet(Value leaf) {
		return Value.of(leaf.name(), "distance", prop(leaf, "distance"), "persistent", prop(leaf, "persistent"), "waterlogged", "true");
	}

	/** A tiny world and journal, changes made as the server makes them (befores read from the world). */
	static final class Sim {
		final Map<Long, Value> world = new HashMap<>();
		final Map<String, Entry> entries = new LinkedHashMap<>();
		long layer = 1;

		Sim() {
			world.put(L, leaf(3, false));
			world.put(G, GROUND);
			world.put(R, leaf(5, false)); // world generation left it at 5; its nearest log gives 3
			world.put(R2, leaf(6, false));
		}

		/** Records site {@code s}'s leaf ring over {@code cells} (before = after = the leaf as it is; nothing changes). */
		void ring(String s, long... cells) {
			Map<Long, Value> r = new LinkedHashMap<>();
			for (long p : cells) {
				r.put(p, world.get(p));
			}
			change(s + "-ring", LeafGuard.RING_KIND, Policy.CELL, r);
		}

		/** A later hold of {@code s} (a re-hold after a neighbour's Remove): the leaf as it is, made persistent. */
		void hold(String s, long p) {
			Value v = world.get(p);
			change(s + "-hold" + layer, LeafGuard.KIND, Policy.CELL, Map.of(p, Value.of(v.name(), "distance", prop(v, "distance"), "persistent", "true",
				"waterlogged", "false")));
		}

		Entry change(String id, String kind, Policy policy, Map<Long, Value> to) {
			List<Cell> cells = new ArrayList<>();
			long l = layer++;
			for (var t : to.entrySet()) {
				cells.add(new Cell(t.getKey(), l, world.getOrDefault(t.getKey(), AIR), t.getValue()));
				world.put(t.getKey(), t.getValue());
			}
			Entry e = new Entry(id, kind, id.substring(0, 1), "minecraft:overworld", policy, l, Status.ACTIVE, cells, null, null);
			entries.put(id, e);
			return e;
		}

		/** Places site {@code s}: its box over {@code box} and the hold of {@code held}. */
		void place(String s, Map<Long, Value> box, long... held) {
			Map<Long, Value> h = new LinkedHashMap<>();
			for (long p : held) {
				Value v = world.get(p);
				h.put(p, Value.of(v.name(), "distance", v.state().getCompoundOrEmpty("Properties").getStringOr("distance", "1"), "persistent", "true",
					"waterlogged", "false"));
			}
			change(s + "-box", "building", Policy.BOX, box);
			if (!h.isEmpty()) {
				change(s + "-leaves", LeafGuard.KIND, Policy.CELL, h);
			}
		}

		Journal.UndoPlan remove(String s) {
			List<String> ids = new ArrayList<>();
			for (Entry e : entries.values()) {
				if (e.active() && e.id().startsWith(s + "-")) {
					ids.add(e.id());
				}
			}
			Journal.UndoPlan p = Journal.planUndo(entries.values(), ids, s + "-box", 0L, (pos, after) -> same(world.getOrDefault(pos, AIR), after),
				LeafGuardTest::same);
			for (Journal.Write w : p.writes()) {
				world.put(w.pos(), w.value());
			}
			entries.putAll(p.updated());
			return p;
		}
	}

	@Test
	void whichLeavesMayHangOnTheBox() {
		assertEquals(0, LeafGuard.distanceTo(BOX, 4, 70, 4));
		assertEquals(2, LeafGuard.distanceTo(BOX, 11, 68, 4));
		assertEquals(4, LeafGuard.distanceTo(BOX, 11, 74, 8));
		assertEquals(4, LeafGuard.distanceTo(BOX, -1, 62, -1));
		assertTrue(LeafGuard.mayDependOnBox(2, 2));
		assertTrue(LeafGuard.mayDependOnBox(6, 1));
		assertFalse(LeafGuard.mayDependOnBox(1, 2), "a leaf one from its log, two from the box, hangs on a log outside");
		assertFalse(LeafGuard.mayDependOnBox(7, 1), "distance 7 decays anyway");
		assertFalse(LeafGuard.mayDependOnBox(3, 0), "inside the box: the box's own cell");
		assertTrue(LeafGuard.near(BOX, new Anchors.Bounds(21, 64, 0, 30, 70, 9), 12));
		assertFalse(LeafGuard.near(BOX, new Anchors.Bounds(22, 64, 0, 30, 70, 9), 12));
	}

	@Test
	void removeGivesAHeldLeafItsNaturalStateBackThoughVanillaMovedItsDistance() {
		Sim s = new Sim();
		s.place("a", Map.of(G, PLANKS), L);
		assertEquals(leaf(3, true), s.world.get(L));
		s.world.put(L, leaf(5, true)); // the logs inside the box are gone: vanilla recomputes a persistent leaf's distance too
		s.remove("a");
		assertEquals(leaf(3, false), s.world.get(L), "natural again, with the distance it had");
		assertEquals(GROUND, s.world.get(G));
	}

	@Test
	void aHeldLeafThePlayerBrokeOrReplacedIsLeftAlone() {
		Value birch = Value.of("minecraft:birch_leaves", "distance", "1", "persistent", "true", "waterlogged", "false");
		// broken, replaced by other leaves, built over: none is the hold's any more
		for (Value player : new Value[] {AIR, birch, PLANKS}) {
			Sim s = new Sim();
			s.place("a", Map.of(G, PLANKS), L);
			s.world.put(L, player);
			Journal.UndoPlan p = s.remove("a");
			assertEquals(player, s.world.get(L), player.toString());
			assertEquals(1, p.stats().get("a-leaves").changed());
		}
	}

	@Test
	void aSiteBuiltOverAHeldLeafUndoesInEitherOrderWithNoLeafLeftPersistent() {
		for (boolean holderFirst : new boolean[] {true, false}) {
			Sim s = new Sim();
			s.place("a", Map.of(G, PLANKS), L); // a holds L
			s.world.put(L, leaf(4, true)); // distance moved while held
			s.place("b", Map.of(L, PLANKS)); // b's box takes L in: its before is the held leaf
			if (holderFirst) {
				s.remove("a");
				assertEquals(PLANKS, s.world.get(L), "b stands: nothing written under it");
				s.remove("b");
			} else {
				s.remove("b");
				assertTrue(persistent(s.world.get(L)), "a still stands and still holds L");
				s.remove("a");
			}
			assertEquals(leaf(3, false), s.world.get(L), (holderFirst ? "holder" : "builder") + " first: natural, never left persistent");
			assertEquals(GROUND, s.world.get(G));
		}
	}

	@Test
	void anUndoThatNeverReachedTheDiskReactivatesTheHoldWithTheSite() {
		Sim s = new Sim();
		s.place("a", Map.of(G, PLANKS), L);
		s.place("b", Map.of(L, PLANKS));
		s.remove("a"); // hands L's natural state down to b
		assertEquals(leaf(3, false), s.entries.get("b-box").cell(L).before());
		assertEquals("a-box", s.entries.get("a-leaves").undo().group(), "the hold is undone in the site's group");
		s.entries.putAll(Journal.reactivate(s.entries.values(), "a-box"));
		assertTrue(s.entries.get("a-leaves").active());
		assertEquals(leaf(3, true), s.entries.get("b-box").cell(L).before(), "the hand-down reversed: b's before is the held leaf again");
	}

	// ------------------------------------------------------------------ leaf ring

	@Test
	void removeGivesTheRingItsRecordedDistancesBack() {
		Sim s = new Sim();
		s.place("a", Map.of(G, PLANKS), L);
		s.ring("a", R, R2);
		s.world.put(R, leaf(3, false)); // the canopy relaxed after a shape update next to it
		s.world.put(R2, leaf(4, false));
		Journal.UndoPlan p = s.remove("a");
		assertEquals(leaf(5, false), s.world.get(R), "the stale worldgen distance, as it was");
		assertEquals(leaf(6, false), s.world.get(R2));
		assertEquals(leaf(3, false), s.world.get(L));
		assertEquals(GROUND, s.world.get(G));
		assertEquals("a-box", s.entries.get("a-ring").undo().group(), "the ring is undone in the site's group");
		assertEquals(2, p.stats().get("a-ring").restored());
	}

	@Test
	void aRingLeafThePlayerChangedIsLeftAlone() {
		Value birch = Value.of("minecraft:birch_leaves", "distance", "1", "persistent", "true", "waterlogged", "false");
		// broken, replaced by other leaves, built over, the same leaf placed by hand (persistent), waterlogged
		for (Value player : new Value[] {AIR, birch, PLANKS, leaf(1, true), wet(leaf(3, false))}) {
			Sim s = new Sim();
			s.place("a", Map.of(G, PLANKS));
			s.ring("a", R);
			s.world.put(R, player);
			Journal.UndoPlan p = s.remove("a");
			assertEquals(player, s.world.get(R), player.toString());
			assertEquals(0, p.stats().get("a-ring").restored(), player.toString());
		}
	}

	@Test
	void aSiteBuiltOverARingLeafUndoesInEitherOrderToTheRecordedLeaf() {
		for (boolean ringFirst : new boolean[] {true, false}) {
			Sim s = new Sim();
			s.place("a", Map.of(G, PLANKS));
			s.ring("a", R);
			s.world.put(R, leaf(4, false)); // relaxed while a stands
			s.place("b", Map.of(R, PLANKS)); // b's box takes R in: its before is the relaxed leaf
			if (ringFirst) {
				s.remove("a");
				assertEquals(PLANKS, s.world.get(R), "b stands: nothing written under it");
				assertEquals(leaf(5, false), s.entries.get("b-box").cell(R).before(), "handed down: b's restore gives the recorded leaf");
				s.remove("b");
			} else {
				s.remove("b");
				assertEquals(leaf(4, false), s.world.get(R), "b gives back what it found");
				s.remove("a");
			}
			assertEquals(leaf(5, false), s.world.get(R), (ringFirst ? "ring" : "box") + " first");
			assertEquals(GROUND, s.world.get(G));
		}
	}

	@Test
	void aLaterHoldOverARingLeafUndoesInEitherOrderToTheRecordedLeaf() {
		// a's own re-hold (same group), and a neighbour b's hold removed before or after a
		for (String holder : new String[] {"a", "b-first", "b-last"}) {
			Sim s = new Sim();
			s.place("a", Map.of(G, PLANKS));
			s.ring("a", R);
			s.world.put(R, leaf(4, false));
			String h = holder.substring(0, 1);
			if (!h.equals("a")) {
				s.place(h, Map.of(Journal.pos(30, 64, 30), PLANKS));
			}
			s.hold(h, R);
			s.world.put(R, leaf(3, true)); // vanilla moved the held leaf's distance again
			if (holder.equals("b-first")) {
				s.remove("b");
				assertEquals(leaf(4, false), s.world.get(R), "b's hold gives back what it found");
			}
			s.remove("a");
			if (holder.equals("b-last")) {
				assertTrue(persistent(s.world.get(R)), "b still stands and holds R");
				s.remove("b");
			}
			assertEquals(leaf(5, false), s.world.get(R), holder);
		}
	}

	@Test
	void aRemovalThatNeverReachedTheDiskReactivatesTheRingWithTheSite() {
		Sim s = new Sim();
		s.place("a", Map.of(G, PLANKS));
		s.ring("a", R);
		s.world.put(R, leaf(4, false));
		s.place("b", Map.of(R, PLANKS));
		s.remove("a"); // hands R's recorded leaf down to b
		assertEquals(leaf(5, false), s.entries.get("b-box").cell(R).before());
		s.entries.putAll(Journal.reactivate(s.entries.values(), "a-box"));
		assertTrue(s.entries.get("a-ring").active());
		assertEquals(leaf(4, false), s.entries.get("b-box").cell(R).before(), "the hand-down reversed");
	}
}
