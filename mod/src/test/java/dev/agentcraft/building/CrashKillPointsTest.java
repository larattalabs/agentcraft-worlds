package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Every kill point of place / remove / move and of the world start's own settling (docs/BUILDINGS.md "Crash safety"),
 * against the journal as the real operations leave it ({@link Journal#planUndo}, {@link Journal#reactivate}) and the
 * buildings file as it was last saved. The world start's repair ({@link Buildings#repair}) and evidence rule
 * ({@link Reconcile#decide}) must end in a consistent state at each:
 * <ul>
 * <li>a site the journal holds ACTIVE stands with a record (Architect's K4: a placement the journal holds is placed, never
 * rolled back or orphaned);</li>
 * <li>a site entry the journal holds UNDONE is named by a pending site, so the next start settles it (Architect's K6: an
 * undo committed while the record was not pending yet must not stay UNDONE for ever).</li>
 * </ul>
 * Order of each operation (Buildings): place = draft, blocks, journal commit, buildings file; remove = journal commit
 * (undone), blocks, file (pending); move = new site's blocks, one journal commit (new site active, old undone), old site's
 * blocks, file; the world start = repair, then per pending site one journal commit (release / reactivation), then the file.
 */
class CrashKillPointsTest {
	static final Anchors.Bounds A = new Anchors.Bounds(0, 64, 0, 3, 66, 3);
	static final Anchors.Bounds B = new Anchors.Bounds(40, 64, 0, 43, 66, 3);
	static final Value GRASS = Value.of("minecraft:grass_block", "snowy", "false");
	static final Value AIR = Journal.AIR;
	static final Value PLANKS = Value.of("minecraft:spruce_planks");
	static final Value SIGN = Value.of("minecraft:dark_oak_wall_sign", "facing", "north", "waterlogged", "false");

	/** The journal on disk (entries by id) and the buildings file as last saved. */
	static final class Disk {
		final Map<String, Entry> journal = new LinkedHashMap<>();
		Map<String, Building> records = new LinkedHashMap<>();
		List<Building.Pending> pending = new ArrayList<>();
		long layer = 1;
		int next = 1;

		Disk copy() {
			Disk d = new Disk();
			d.journal.putAll(journal);
			d.records = new LinkedHashMap<>(records);
			d.pending = new ArrayList<>(pending);
			d.layer = layer;
			d.next = next;
			return d;
		}

		/** A site's BOX entry over {@code box} (ground at its lowest row, air above; after: planks), its record as meta. */
		Entry site(Building b, Anchors.Bounds box) {
			List<Cell> cells = new ArrayList<>();
			long l = layer++;
			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					for (int x = box.minX(); x <= box.maxX(); x++) {
						cells.add(new Cell(Journal.pos(x, y, z), l, y == box.minY() ? GRASS : AIR, PLANKS));
					}
				}
			}
			return new Entry("j" + next++, "building", b.id(), "minecraft:overworld", Policy.BOX, l, Status.ACTIVE, cells, null, b.toJson());
		}

		/** A trophy sign hung in {@code b}'s box, over its site's cell. */
		Entry trophy(Building b, Anchors.Bounds box) {
			long l = layer++;
			Cell c = new Cell(Journal.pos(box.minX() + 1, box.minY() + 1, box.minZ()), l, PLANKS, SIGN);
			return new Entry("j" + next++, "trophy", b.id(), "minecraft:overworld", Policy.CELL, l, Status.ACTIVE, List.of(c), null, null);
		}

		/** A held-leaves entry of {@code b} just outside its box. */
		Entry leaves(Building b, Anchors.Bounds box) {
			long l = layer++;
			Value natural = Value.of("minecraft:oak_leaves", "distance", "3", "persistent", "false", "waterlogged", "false");
			Value held = Value.of("minecraft:oak_leaves", "distance", "3", "persistent", "true", "waterlogged", "false");
			Cell c = new Cell(Journal.pos(box.maxX() + 1, box.maxY(), box.minZ()), l, natural, held);
			return new Entry("j" + next++, "leaves", b.id(), "minecraft:overworld", Policy.CELL, l, Status.ACTIVE, List.of(c), null, null);
		}

		/** The ids of {@code b}'s active entries of its site group (site, trophies, leaves). */
		List<String> group(String owner) {
			return journal.values().stream().filter(e -> e.active() && e.owner().equals(owner)).map(Entry::id).toList();
		}

		String siteEntry(Building b) {
			return journal.values().stream().filter(e -> e.active() && e.kind().equals("building") && e.owner().equals(b.id())
				&& Buildings.sameBox(e.box(), b.restoreBox())).map(Entry::id).findFirst().orElseThrow();
		}

		/** The undo of {@code b}'s site group (one plan, as Buildings.planSite), committed to the journal. */
		String undoSite(Building b) {
			String site = siteEntry(b);
			List<String> ids = new ArrayList<>(group(b.id()));
			ids.remove(site);
			ids.add(0, site);
			Journal.UndoPlan p = Journal.planUndo(journal.values(), ids, site, layer++, (pos, v) -> true, Journal.Match.EQUAL);
			journal.putAll(p.updated());
			return site;
		}

		List<Buildings.SiteEntry> siteEntries() {
			List<Buildings.SiteEntry> out = new ArrayList<>();
			for (Entry e : journal.values()) {
				if (e.kind().equals("building")) {
					out.add(new Buildings.SiteEntry(e.id(), e.owner(), e.status(), e.box(), e.undo() == null ? 0L : e.undo().at()));
				}
			}
			return out;
		}
	}

	static Building b(String id, Anchors.Bounds box) {
		return new Building(id, "workshop", List.of("pocket-notes"), true, "none", box, box, Map.of(), 1L, "minecraft:overworld");
	}

	/** What the world start does, as Buildings.reconcile: repair, then each pending site settled on {@code world}'s evidence. */
	static Disk start(Disk d, Map<String, Boolean> standsAt) {
		Disk out = d.copy();
		Buildings.Repaired r = Buildings.repair(out.records, out.pending, out.siteEntries(), s -> out.journal.containsKey(s) ? s : null,
			id -> out.journal.get(id).meta() == null ? null : Building.fromJson(out.journal.get(id).meta()));
		out.records = new LinkedHashMap<>(r.records());
		out.pending = new ArrayList<>(r.pending());
		for (Building.Pending p : List.copyOf(out.pending)) {
			Entry entry = out.journal.get(p.snapshot());
			if (entry == null || entry.active()) {
				out.pending.remove(p); // missing / in use by a standing site: dropped
				continue;
			}
			Building gone = p.building();
			boolean stands = standsAt.getOrDefault(key(gone), false);
			Building current = out.records.get(gone.id());
			Boolean currentStands = current == null ? null : standsAt.getOrDefault(key(current), false);
			Reconcile.Action a = Reconcile.decide("moved".equals(p.why()), stands, !stands, Reconcile.Overlap.NONE, current != null, currentStands);
			String g = entry.undo().group();
			List<String> group = out.journal.values().stream().filter(e -> e.status() == Status.UNDONE && g.equals(e.undo().group())).map(Entry::id)
				.toList();
			switch (a) {
				case RELEASE -> group.forEach(out.journal::remove);
				case RECOVER -> {
					out.journal.putAll(Journal.reactivate(out.journal.values(), g));
					if ("moved".equals(p.why())) {
						for (Entry e : List.copyOf(out.journal.values())) {
							if (e.active() && e.owner().equals(gone.id()) && !group.contains(e.id())) {
								out.journal.put(e.id(), e.undone(new Journal.Undo("unused-" + e.id(), 1L, Map.of(), List.of())));
							}
						}
					}
					out.records.put(gone.id(), gone);
				}
				default -> {
					continue;
				}
			}
			out.pending.remove(p);
		}
		return out;
	}

	static String key(Building b) {
		return b.restoreBox().toString();
	}

	/** The invariants a world start must leave (K4, K6). */
	static void consistent(Disk d, String when) {
		Map<String, Integer> sites = new HashMap<>();
		for (Entry e : d.journal.values()) {
			if (!e.kind().equals("building")) {
				continue;
			}
			if (e.active()) {
				Building r = d.records.get(e.owner());
				assertNotNull(r, when + ": " + e.id() + " is active but " + e.owner() + " has no record (orphaned)");
				assertTrue(Buildings.sameBox(e.box(), r.restoreBox()), when + ": " + e.id() + " is active at another box than the record");
				sites.merge(e.owner(), 1, Integer::sum);
			} else if (!e.undo().group().startsWith("unused-")) {
				assertTrue(d.pending.stream().anyMatch(p -> p.snapshot().equals(e.id())), when + ": " + e.id() + " is undone with no pending site naming it");
			}
		}
		for (Building r : d.records.values()) {
			assertEquals(1, sites.getOrDefault(r.id(), 0), when + ": " + r.id() + " needs exactly one active site entry");
		}
	}

	/** b1 at A with a trophy and held leaves, committed and saved. */
	static Disk placed() {
		Disk d = new Disk();
		Building b1 = b("b1", A);
		for (Entry e : List.of(d.site(b1, A), d.trophy(b1, A), d.leaves(b1, A))) {
			d.journal.put(e.id(), e);
		}
		d.records.put("b1", b1);
		return d;
	}

	@Test
	void place() {
		// killed after the journal commit, before the buildings file: the journal holds the site, so the record comes back
		Disk d = new Disk();
		Building b1 = b("b1", A);
		Entry s = d.site(b1, A);
		d.journal.put(s.id(), s);
		for (boolean saved : new boolean[] {false, true}) { // the world reached the disk or not: the record comes back either way
			Disk after = start(d, Map.of(key(b1), saved));
			assertTrue(after.records.containsKey("b1"));
			consistent(after, "place killed before the file (world saved " + saved + ")");
		}
		// killed before the commit: nothing in the journal names it (its draft is an unreferenced file), no record
		Disk none = start(new Disk(), Map.of());
		assertTrue(none.records.isEmpty());
	}

	@Test
	void remove() {
		Disk d = placed();
		Building b1 = d.records.get("b1");
		String site = d.undoSite(b1); // the journal commit
		// killed before the file: pending by repair, then settled on the world (removal saved: released; not: back)
		Disk restored = start(d, Map.of(key(b1), false));
		assertFalse(restored.records.containsKey("b1"));
		assertTrue(restored.journal.isEmpty(), "the whole group is released: " + restored.journal.keySet());
		consistent(restored, "remove killed before the file, removal saved");
		Disk back = start(d, Map.of(key(b1), true));
		assertTrue(back.records.containsKey("b1"));
		assertTrue(back.journal.values().stream().allMatch(Entry::active), "the whole group is active again");
		consistent(back, "remove killed before the file, removal not saved");
		// the file saved too: the pending site is the file's own
		Disk saved = d.copy();
		saved.records.remove("b1");
		saved.pending.add(new Building.Pending(b1, site, 5L, "removed"));
		consistent(start(saved, Map.of(key(b1), false)), "remove saved, removal saved");
		consistent(start(saved, Map.of(key(b1), true)), "remove saved, removal not saved");
	}

	@Test
	void theWorldStartsOwnRecoveryKilledBeforeTheFile() {
		// a removal not saved: the world start reactivates the group (journal commit) and is killed before the file
		Disk d = placed();
		Building b1 = d.records.get("b1");
		String site = d.undoSite(b1);
		d.records.remove("b1");
		d.pending.add(new Building.Pending(b1, site, 5L, "removed"));
		Disk killed = d.copy();
		killed.journal.putAll(Journal.reactivate(killed.journal.values(), site)); // the reactivation's commit reached the disk only
		Disk after = start(killed, Map.of(key(b1), true));
		assertTrue(after.records.containsKey("b1"), "the journal says it stands: the record comes back, not an orphaned entry");
		assertTrue(after.pending.isEmpty());
		consistent(after, "recovery killed before the file");
		// and a release killed before the file: the pending names an entry the journal no longer has
		Disk released = d.copy();
		List.copyOf(released.journal.keySet()).forEach(released.journal::remove);
		Disk r2 = start(released, Map.of(key(b1), false));
		assertFalse(r2.records.containsKey("b1"));
		assertTrue(r2.pending.isEmpty());
		consistent(r2, "release killed before the file");
	}

	@Test
	void move() {
		Disk d = placed();
		Building old = d.records.get("b1");
		Building moved = b("b1", B);
		// one commit: the new site active (meta: the moved record), the old site's group undone
		Entry ns = d.site(moved, B);
		String oldSite = d.undoSite(old);
		d.journal.put(ns.id(), ns);
		// killed before the file: the record follows the journal, the old site is pending as moved
		Disk saved = start(d, Map.of(key(old), false, key(moved), true));
		assertEquals(B, saved.records.get("b1").box());
		consistent(saved, "move killed before the file, move saved");
		Disk notSaved = start(d, Map.of(key(old), true, key(moved), false));
		assertEquals(A, notSaved.records.get("b1").box(), "the move never reached the disk: back at its old site");
		consistent(notSaved, "move killed before the file, move not saved");
		// the world start's own move-back killed before the file: the old group active, the new site's entries unused
		Disk killed = d.copy();
		killed.records.put("b1", moved);
		killed.pending.add(new Building.Pending(old, oldSite, 5L, "moved"));
		killed.journal.putAll(Journal.reactivate(killed.journal.values(), oldSite));
		killed.journal.put(ns.id(), ns.undone(new Journal.Undo("unused-" + ns.id(), 1L, Map.of(), List.of())));
		Disk after = start(killed, Map.of(key(old), true, key(moved), false));
		assertEquals(A, after.records.get("b1").box());
		consistent(after, "move-back killed before the file");
	}
}
