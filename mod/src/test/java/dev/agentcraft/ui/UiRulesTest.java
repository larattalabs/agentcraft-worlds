package dev.agentcraft.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.ui.UiRules.EnterAction;
import dev.agentcraft.ui.UiRules.LeadWorld;
import dev.agentcraft.ui.UiRules.OtherWorld;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class UiRulesTest {
	/** Regression B1 / C6: a normal launcher pauses, dev runs and the DevBridge keep the world running. */
	@Test
	void screensPauseOutsideDevRuns() {
		assertTrue(UiRules.screensPause(false, false, null), "everyday play pauses");
		assertFalse(UiRules.screensPause(true, false, null), "gradlew runClient keeps running (QA)");
		assertFalse(UiRules.screensPause(false, true, null), "AGENTCRAFT_DEV=1 keeps running (QA)");
		assertTrue(UiRules.screensPause(true, true, true), "AGENTCRAFT_PAUSE=1 wins");
		assertFalse(UiRules.screensPause(false, false, false), "AGENTCRAFT_PAUSE=0 wins");
	}

	/** Regression B1: right-click with food/shield/bow/blocks never opens the card; only empty-hand sneak does. */
	@Test
	void agentCardOnlyOnEmptyHandSneak() {
		assertTrue(UiRules.agentUseOpensCard(true, true, true));
		assertFalse(UiRules.agentUseOpensCard(true, false, true), "not sneaking");
		assertFalse(UiRules.agentUseOpensCard(true, true, false), "holding an item");
		assertFalse(UiRules.agentUseOpensCard(false, true, true), "off hand");
		assertFalse(UiRules.agentTargetable(false, true), "swings and mining go through unless sneaking");
		assertFalse(UiRules.agentTargetable(true, false));
		assertTrue(UiRules.agentTargetable(true, true));
	}

	@Test
	void teleportNeedsCheatsOrCreative() {
		assertFalse(UiRules.teleportAllowed(false, false, false), "survival, cheats off");
		assertTrue(UiRules.teleportAllowed(true, false, false), "cheats on");
		assertTrue(UiRules.teleportAllowed(false, true, false), "creative");
		assertTrue(UiRules.teleportAllowed(false, false, true), "spectator");
	}

	/** Regression B4: lecterns outside buildings (or with a book) are vanilla lecterns. */
	@Test
	void lecternsOnlyInsideBuildings() {
		assertTrue(UiRules.lecternOpensLibrary(true, false, false, false));
		assertTrue(UiRules.lecternOpensLibrary(false, true, false, false), "the dev HQ's library");
		assertFalse(UiRules.lecternOpensLibrary(false, false, false, false), "a village lectern");
		assertFalse(UiRules.lecternOpensLibrary(true, false, true, false), "a book on it: read it");
		assertFalse(UiRules.lecternOpensLibrary(true, false, false, true), "holding a book: put it on");
	}

	record B(String id, Anchors.Bounds box, @Nullable String dim) {
	}

	@Test
	void containingRespectsBoxAndDimension() {
		B a = new B("a", new Anchors.Bounds(0, 60, 0, 10, 70, 10), null);
		B n = new B("n", new Anchors.Bounds(0, 60, 0, 10, 70, 10), "minecraft:the_nether");
		List<B> all = List.of(a, n);
		assertSame(a, UiRules.containing(all, B::box, B::dim, "minecraft:overworld", 5, 65, 5), "null dimension = overworld");
		assertSame(n, UiRules.containing(all, B::box, B::dim, "minecraft:the_nether", 10, 70, 10), "inclusive max corner");
		assertNull(UiRules.containing(all, B::box, B::dim, "minecraft:overworld", 11, 65, 5));
		assertNull(UiRules.containing(all, B::box, B::dim, "minecraft:the_end", 5, 65, 5));
	}

	/** One Enter rule: single-line Enter sends; multi-line Enter is a new line and Ctrl+Enter sends. */
	@Test
	void oneEnterRule() {
		assertEquals(EnterAction.SEND, UiRules.enter(false, false, false));
		assertEquals(EnterAction.SEND, UiRules.enter(false, true, false), "Ctrl+Enter sends everywhere");
		assertEquals(EnterAction.NEWLINE, UiRules.enter(false, false, true));
		assertEquals(EnterAction.NEWLINE, UiRules.enter(true, false, false));
		assertEquals(EnterAction.NEWLINE, UiRules.enter(true, false, true));
		assertEquals(EnterAction.SEND, UiRules.enter(true, true, false));
		assertArrayEquals(new String[] {"Ctrl+Enter", "send", "Enter", "new line"}, UiRules.enterHints(true));
		assertEquals("Enter", UiRules.enterHints(false)[0]);
	}

	@Test
	void secondPressWindow() {
		assertFalse(UiRules.secondPress(0, 1000, 3000), "never armed");
		assertTrue(UiRules.secondPress(1000, 2500, 3000));
		assertFalse(UiRules.secondPress(1000, 4001, 3000), "expired");
		assertFalse(UiRules.secondPress(5000, 4000, 3000), "clock went backwards");
	}

	/** Regression: plain console text no longer silently creates a goal. */
	@Test
	void plainConsoleTextNeedsASecondEnter() {
		assertFalse(UiRules.plainGoalConfirmed(null, "fix the build"), "first Enter only asks");
		assertTrue(UiRules.plainGoalConfirmed("fix the build", "fix the build "), "second Enter on the same text");
		assertFalse(UiRules.plainGoalConfirmed("fix the build", "fix the tests"), "edited: ask again");
		assertFalse(UiRules.plainGoalConfirmed("", " "));
	}

	@Test
	void terminalGoalsGoToTheBuildingsRepo() {
		assertEquals("api", UiRules.buildingRepo(List.of("gone", "api", "web"), Set.of("api", "web")));
		assertNull(UiRules.buildingRepo(null, Set.of("api")), "no building");
		assertNull(UiRules.buildingRepo(List.of("gone"), Set.of("api")), "no known repo");
	}

	/** C2: the Team tab lists the leads other worlds hold. */
	@Test
	void otherWorldsHoldingLeads() {
		List<OtherWorld> o = UiRules.otherWorlds(List.of(new LeadWorld("marlow", null, 0), new LeadWorld("ada", "HQ", 100),
			new LeadWorld("bo", "Hardcore", 50), new LeadWorld("cy", "HQ", 300), new LeadWorld("di", "", 0)), "Hardcore");
		assertEquals(1, o.size());
		assertEquals("HQ", o.get(0).world());
		assertEquals(List.of("ada", "cy"), o.get(0).leads());
		assertEquals(300, o.get(0).lastSync());
		assertEquals(2, UiRules.otherWorlds(List.of(new LeadWorld("ada", "HQ", 1), new LeadWorld("bo", "Hardcore", 2)), null).size(),
			"no world loaded: all are other");
	}
}
