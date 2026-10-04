package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Beds left out where they explode (docs/BUILDINGS.md "Beds"): the rule, the head cell, anchors and pin stripping. */
class BedSafetyTest {
	@Test
	void onlyBedsYouCanSafelySleepInStay() {
		// vanilla rules: overworld CAN_SLEEP_WHEN_DARK (when_dark, no destroy), nether/end DESTROY_ON_USE (never, destroy_on_use)
		assertFalse(BedSafety.unsafe(false, false, false), "overworld");
		assertTrue(BedSafety.unsafe(true, true, false), "nether / end: explodes when used");
		assertTrue(BedSafety.unsafe(false, false, true), "DESTROY_ON_LEAVE");
		assertTrue(BedSafety.unsafe(true, false, false), "a datapack rule where nobody can sleep");
		assertTrue(BedSafety.unsafe(false, true, false), "a datapack rule that explodes even when sleeping is allowed");
	}

	@Test
	void theFootHalfPointsAtItsHeadAlongFacing() {
		// facing = from the foot to the head (vanilla: head = foot.relative(FACING))
		assertEquals(new BlockPos(3, 64, 5), BedSafety.head(3, 64, 5, true, 0, 1));
		assertEquals(new BlockPos(3, 64, 6), BedSafety.head(3, 64, 5, false, 0, 1), "south");
		assertEquals(new BlockPos(2, 64, 5), BedSafety.head(3, 64, 5, false, -1, 0), "west");
		assertEquals(new BlockPos(3, 64, 4), BedSafety.head(3, 64, 5, false, 0, -1), "north");
		assertEquals(new BlockPos(4, 64, 5), BedSafety.head(3, 64, 5, false, 1, 0), "east");
	}

	private static Map<String, Anchor> anchors() {
		Map<String, Anchor> m = new LinkedHashMap<>();
		m.put("lounge", new Anchor("lounge", 1.5, 64, 1.5, 0, 0));
		m.put("bed", new Anchor("bed", 3.5, 64, 6.5, 0, 0)); // head at 3,64,6
		m.put("bed_2", new Anchor("bed_2", 5.5, 64, 6.5, 0, 0)); // head at 5,64,6 (kept: not removed)
		m.put("bedrock_wall", new Anchor("bedrock_wall", 3.5, 64, 6.5, 0, 0)); // not a bed anchor, same cell
		return m;
	}

	@Test
	void onlyTheRemovedBedsAnchorsAreDropped() {
		Map<String, Anchor> out = BedSafety.withoutBeds(anchors(), Set.of(BlockPos.asLong(3, 64, 6)));
		assertEquals(List.of("lounge", "bed_2", "bedrock_wall"), List.copyOf(out.keySet()));
		Map<String, Anchor> all = anchors();
		assertSame(all, BedSafety.withoutBeds(all, Set.of()), "nothing removed: unchanged");
		assertEquals(4, BedSafety.withoutBeds(anchors(), Set.of(BlockPos.asLong(9, 9, 9))).size(), "a head no anchor stands on");
	}

	@Test
	void theStrippedPinForgetsTheBedCellsAndAnchors() {
		Anchors.Bounds box = new Anchors.Bounds(0, 63, 0, 10, 70, 10);
		// block entities as offsets from the box min (0,63,0): head 3,64,6 -> 3,1,6; foot 3,64,5 -> 3,1,5; a station 1,1,1
		Building.Pin pin = new Building.Pin("fp", 1, false, anchors(), List.of(3, 1, 6, 3, 1, 5, 1, 1, 1));
		Set<Long> cells = Set.of(BlockPos.asLong(3, 64, 6), BlockPos.asLong(3, 64, 5));
		Building.Pin out = BedSafety.strip(pin, cells, Set.of(BlockPos.asLong(3, 64, 6)), box);
		assertEquals(List.of(1, 1, 1), out.blockEntities(), "the beds' cells are no longer the building's own");
		assertFalse(out.wingAnchors().containsKey("bed"), "a repo change rebuilds anchors from the pin: the bed must not come back");
		assertTrue(out.wingAnchors().containsKey("bed_2"));
		assertEquals("fp", out.template(), "same template version: the building still checks against its blueprint");
		assertSame(pin, BedSafety.strip(pin, Set.of(), Set.of(), box), "overworld: the pin as is");
	}

	@Test
	void thePlacementSaysWhyTheBedsAreMissing() {
		assertNull(BedSafety.note(0, "minecraft:overworld"));
		assertEquals("3 beds left out (beds explode in minecraft:the_nether; agents rest in the lounge)", BedSafety.note(3, "minecraft:the_nether"));
		assertTrue(BedSafety.note(1, "minecraft:the_end").startsWith("1 bed left out"));
	}
}
