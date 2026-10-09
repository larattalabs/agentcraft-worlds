package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.journal.Journal;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Value;
import dev.agentcraft.journal.Support;
import dev.agentcraft.journal.WorldJournal;
import dev.agentcraft.layout.Anchors;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.VineBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Vines and hanging plants (docs/BUILDINGS.md "Vines and hanging plants"): inside the box they are written after the rest
 * of it, quietly ({@link WorldJournal#splitHanging}); outside it, the ones within reach and their vertical runs are a CELL
 * entry of the site ({@link PlantGuard}, kind {@code plants}): before = the plant, after = what the placement left, undone
 * with the site after the box, in any order with other sites, never over a block the player changed.
 */
class PlantGuardTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	static final Anchors.Bounds BOX = new Anchors.Bounds(0, 64, 0, 9, 72, 9);

	static final Value VINE = Value.of("minecraft:vine", "east", "false", "north", "true", "south", "false", "up", "false", "west", "false");
	static final Value MUSHROOM = Value.of("minecraft:brown_mushroom");
	static final Value LOG = Value.of("minecraft:jungle_log", "axis", "y");
	static final Value PLANKS = Value.of("minecraft:oak_planks");
	static final Value COBBLE = Value.of("minecraft:cobblestone");
	static final Value AIR = Journal.AIR;

	static boolean hanging(Value v) {
		return v.name().equals("minecraft:vine") || v.name().endsWith("_mushroom") || v.name().equals("minecraft:cocoa");
	}

	// ------------------------------------------------------------------ which blocks

	@Test
	void lateBlocksAreTheOnesHangingOrOnASideOrTwoHighOrLightBound() {
		for (var b : List.of(Blocks.VINE, Blocks.COCOA, Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN, Blocks.HANGING_ROOTS, Blocks.CAVE_VINES,
			Blocks.CAVE_VINES_PLANT, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.POINTED_DRIPSTONE, Blocks.SPORE_BLOSSOM,
			Blocks.PALE_HANGING_MOSS, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.TALL_GRASS, Blocks.LARGE_FERN, Blocks.SUNFLOWER,
			Blocks.SMALL_DRIPLEAF)) {
			assertTrue(Support.late(b.defaultBlockState()), b.toString());
			assertTrue(Support.needs(b.defaultBlockState()), b.toString());
		}
		assertTrue(Support.late(Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true)));
		assertTrue(Support.late(Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2)));
		// standing on the block below: the restore's order writes their support first; recorded around a site all the same
		for (var b : List.of(Blocks.SHORT_GRASS, Blocks.POPPY, Blocks.OAK_SAPLING, Blocks.SNOW, Blocks.MOSS_CARPET, Blocks.TWISTING_VINES, Blocks.KELP,
			Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO, Blocks.LEAF_LITTER, Blocks.SEAGRASS, Blocks.BIG_DRIPLEAF, Blocks.AMETHYST_CLUSTER)) {
			assertFalse(Support.late(b.defaultBlockState()), b.toString());
			assertTrue(Support.needs(b.defaultBlockState()), b.toString());
		}
		// full blocks, air, leaves, block entities: neither
		for (var b : List.of(Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES, Blocks.STONE, Blocks.CHEST, Blocks.OAK_SIGN,
			Blocks.WATER)) {
			assertFalse(Support.late(b.defaultBlockState()), b.toString());
			assertFalse(Support.needs(b.defaultBlockState()), b.toString());
		}
	}

	// ------------------------------------------------------------------ inside the box

	@Test
	void theBoxWritesHangingBlocksAsAirThenLowestFirstAfterTheRest() {
		long top = Journal.pos(2, 70, 2);
		long mid = Journal.pos(2, 69, 2);
		long low = Journal.pos(2, 68, 2);
		long log = Journal.pos(2, 69, 1);
		long mush = Journal.pos(5, 64, 5);
		Map<Long, Value> values = new LinkedHashMap<>();
		values.put(top, VINE);
		values.put(log, LOG);
		values.put(low, VINE);
		values.put(mush, MUSHROOM);
		values.put(mid, VINE);
		Value chest = new Value(Value.of("minecraft:vine").state(), new net.minecraft.nbt.CompoundTag()); // a value with block entity data
		long be = Journal.pos(7, 66, 7);
		values.put(be, chest);
		WorldJournal.Hung h = WorldJournal.splitHanging(values, PlantGuardTest::hanging);
		assertEquals(values.keySet(), h.box().keySet(), "every position is written in the box pass");
		assertEquals(AIR, h.box().get(top), "a placeholder: the building's block must not stay there");
		assertEquals(AIR, h.box().get(mush));
		assertEquals(LOG, h.box().get(log));
		assertEquals(chest, h.box().get(be), "block entity data is the template's to load");
		assertEquals(List.of(mush, low, mid, top), List.copyOf(h.late().keySet()), "the late pass, lowest first");
		assertEquals(VINE, h.late().get(top));
	}

	// ------------------------------------------------------------------ outside the box: which cells

	@Test
	void selectTakesHangingPlantsWithinReachAndTheirRuns() {
		Set<Long> plants = new HashSet<>();
		long beside = Journal.pos(-1, 70, 4); // on the face of a log inside the box
		long twoOut = Journal.pos(4, 66, -2);
		long threeOut = Journal.pos(13, 66, 4); // too far: nothing it hangs on is touched
		long inside = Journal.pos(3, 66, 3);
		long under = Journal.pos(4, 63, 4); // below the box
		long taken = Journal.pos(10, 68, 9); // another entry's cell
		plants.addAll(List.of(beside, twoOut, threeOut, inside, under, taken));
		for (int y = 69; y >= 50; y--) {
			plants.add(Journal.pos(-1, y, 4)); // the vine curtain below `beside`, far below the reach
		}
		for (int y = 71; y <= 80; y++) {
			plants.add(Journal.pos(-1, y, 4)); // and above it, past the box's top
		}
		plants.add(Journal.pos(-1, 82, 4)); // not connected (81 is air)
		List<Long> sel = PlantGuard.select(BOX, -64, 320, (x, y, z) -> plants.contains(Journal.pos(x, y, z)), p -> p == taken);
		Set<Long> got = new HashSet<>(sel);
		assertTrue(got.contains(beside) && got.contains(twoOut) && got.contains(under));
		assertFalse(got.contains(threeOut), "past the reach");
		assertFalse(got.contains(inside), "the box's own cells are the box's");
		assertFalse(got.contains(taken), "skipped");
		assertTrue(got.contains(Journal.pos(-1, 50, 4)), "the whole curtain below");
		assertTrue(got.contains(Journal.pos(-1, 80, 4)), "and above");
		assertFalse(got.contains(Journal.pos(-1, 82, 4)), "a run stops at the first gap");
		assertEquals(sel.size(), got.size(), "no position twice");
		assertEquals(3 + 20 + 10, sel.size());
	}

	@Test
	void aRunStopsAtTheLevelsFloor() {
		Set<Long> plants = new HashSet<>();
		for (int y = 62; y >= 55; y--) {
			plants.add(Journal.pos(-1, y, 4));
		}
		List<Long> sel = PlantGuard.select(BOX, 58, 320, (x, y, z) -> plants.contains(Journal.pos(x, y, z)), p -> false);
		assertEquals(Set.of(Journal.pos(-1, 62, 4), Journal.pos(-1, 61, 4), Journal.pos(-1, 60, 4), Journal.pos(-1, 59, 4), Journal.pos(-1, 58, 4)),
			new HashSet<>(sel));
	}

	// ------------------------------------------------------------------ outside the box: the journal entry

	static final long IN = Journal.pos(0, 70, 4); // a log in the box's west column
	static final long OUT = Journal.pos(-1, 70, 4); // a vine on its west face, outside the box
	static final long OUT_LOW = Journal.pos(-1, 69, 4); // the vine hanging from it
	static final long SHROOM = Journal.pos(10, 64, 4); // a mushroom beside the box; the placement left it

	/** Site a placed over IN: the vines popped (after = air), the mushroom stayed (after = the mushroom). */
	static LeafGuardTest.Sim placed() {
		LeafGuardTest.Sim s = new LeafGuardTest.Sim();
		s.world.put(IN, LOG);
		s.world.put(OUT, VINE);
		s.world.put(OUT_LOW, VINE);
		s.world.put(SHROOM, MUSHROOM);
		s.change("a-box", "building", Policy.BOX, Map.of(IN, PLANKS));
		Map<Long, Value> plants = new LinkedHashMap<>();
		plants.put(OUT, AIR);
		plants.put(OUT_LOW, AIR);
		plants.put(SHROOM, MUSHROOM);
		s.change("a-plants", PlantGuard.KIND, Policy.CELL, plants);
		return s;
	}

	@Test
	void removeWritesTheOutsidePlantsBackAfterTheBoxQuietly() {
		LeafGuardTest.Sim s = placed();
		Journal.UndoPlan p = s.remove("a");
		assertEquals(LOG, s.world.get(IN));
		assertEquals(VINE, s.world.get(OUT));
		assertEquals(VINE, s.world.get(OUT_LOW));
		assertEquals(MUSHROOM, s.world.get(SHROOM), "written again: a restore that popped it is undone too");
		assertEquals("a-box", s.entries.get("a-plants").undo().group(), "undone in the site's group");
		WorldJournal.Phases ph = WorldJournal.phases(p, CutPlantsTest::leaf);
		assertTrue(ph.guards().isEmpty(), "not before the box: their support is in it");
		assertEquals(Map.of(IN, LOG), ph.boxes().get("a-box"));
		assertEquals(List.of(OUT_LOW, OUT, SHROOM).stream().sorted().toList(), ph.cells().stream().map(Journal.Write::pos).sorted().toList());
		assertTrue(WorldJournal.quietKind(PlantGuard.KIND), "written without shape updates");
	}

	@Test
	void aCellThePlayerChangedIsLeftAlone() {
		LeafGuardTest.Sim s = placed();
		s.world.put(OUT, COBBLE); // built there while the site stood
		s.world.remove(SHROOM); // picked the mushroom
		s.remove("a");
		assertEquals(COBBLE, s.world.get(OUT));
		assertEquals(AIR, s.world.getOrDefault(SHROOM, AIR));
		assertEquals(VINE, s.world.get(OUT_LOW));
	}

	@Test
	void aLaterSiteOverAnOutsidePlantUndoesInEitherOrder() {
		for (boolean aFirst : new boolean[] {true, false}) {
			LeafGuardTest.Sim s = placed();
			s.change("b-box", "building", Policy.BOX, Map.of(OUT, PLANKS, SHROOM, PLANKS));
			if (aFirst) {
				s.remove("a");
				assertEquals(PLANKS, s.world.get(OUT), "b still stands there");
				assertEquals(VINE, s.entries.get("b-box").cell(OUT).before(), "handed down: b gives back the vine");
				s.remove("b");
			} else {
				s.remove("b");
				assertEquals(AIR, s.world.get(OUT), "b gives back what it found");
				s.remove("a");
			}
			assertEquals(VINE, s.world.get(OUT), "a first: " + aFirst);
			assertEquals(MUSHROOM, s.world.get(SHROOM), "a first: " + aFirst);
			assertEquals(LOG, s.world.get(IN));
			assertTrue(s.entries.values().stream().noneMatch(Journal.Entry::active));
		}
	}

	@Test
	void aRemovalThatNeverReachedTheDiskReactivatesThePlantsWithTheSite() {
		LeafGuardTest.Sim s = placed();
		s.change("b-box", "building", Policy.BOX, Map.of(OUT, PLANKS));
		s.remove("a");
		s.entries.putAll(Journal.reactivate(s.entries.values(), "a-box"));
		assertTrue(s.entries.get("a-plants").active());
		assertEquals(AIR, s.entries.get("b-box").cell(OUT).before(), "the hand-down reversed: b found the placement's air");
	}

	@Test
	void theSitesGroupIncludesThePlants() {
		assertTrue(Arrays.asList(Buildings.SITE_KINDS).containsAll(List.of("trophy", LeafGuard.KIND, LeafGuard.RING_KIND, PlantGuard.KIND)),
			"undone (Remove, Move), released (Forget) and settled (crash safety) with the site");
	}
}
