package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoalLogicTest {
	record G(String id, long createdAt) {
	}

	/** One "pixel" per character. */
	static final ToIntFunction<String> CHARS = String::length;

	@Test
	void newestFirstWithNumericTieBreak() {
		List<G> gs = List.of(new G("g1", 100), new G("g9", 300), new G("g12", 300), new G("g2", 200));
		List<String> ids = GoalLogic.newestFirst(gs, G::createdAt, G::id).stream().map(G::id).toList();
		assertEquals(List.of("g12", "g9", "g2", "g1"), ids);
		assertEquals(12, GoalLogic.idNumber("g12"));
		assertEquals(-1, GoalLogic.idNumber("goal"));
	}

	@Test
	void unreadAndBuildingFilter() {
		assertTrue(GoalLogic.unread(200, 100));
		assertFalse(GoalLogic.unread(100, 100));
		assertFalse(GoalLogic.unread(0, 0), "no activity is never unread");
		assertTrue(GoalLogic.inBuilding(List.of("a", "b"), Set.of("b", "c")));
		assertFalse(GoalLogic.inBuilding(List.of("a"), Set.of("b", "c")));
		assertTrue(GoalLogic.inBuilding(List.of("a"), Set.of()), "no filter");
	}

	@Test
	void digestSectionsAndSummary() {
		List<GoalLogic.Line> lines = List.of(
			new GoalLogic.Line(5, "task_done", "t2 done"),
			new GoalLogic.Line(1, "task_added", "t3 added"),
			new GoalLogic.Line(7, "decision_waiting", "merge t2?"),
			new GoalLogic.Line(3, "task_done", "t1 done"),
			new GoalLogic.Line(4, "weird_new_kind", "?"),
			new GoalLogic.Line(6, "pr_opened", "PR #4"));
		List<GoalLogic.Section> s = GoalLogic.sections(lines);
		assertEquals(List.of("Waiting for you", "Done", "Pull requests", "New tasks", "Messages"), s.stream().map(GoalLogic.Section::title).toList());
		assertEquals(List.of("t1 done", "t2 done"), s.get(1).lines().stream().map(GoalLogic.Line::text).toList(), "time order inside a section");
		assertEquals("waiting", s.get(0).family());
		assertEquals("2 done · 1 waiting for you · 1 PR update · 1 new task · 1 message", GoalLogic.summary(lines));
		assertEquals("nothing new", GoalLogic.summary(List.of()));
		assertTrue(GoalLogic.sections(List.of()).isEmpty());
	}

	@Test
	void instructionLines() {
		assertEquals(List.of("keep the API stable", "no new deps", "tests first"),
			GoalLogic.instructionLines("  - keep the API stable\n\n* no new deps\r\n• tests first  \n   "));
	}

	@Test
	void wrapKeepsShortLinesAndBlankLines() {
		assertEquals(List.of("# Plan", "", "short"), GoalLogic.wrap("# Plan\n\nshort\n\n", 20, CHARS));
	}

	@Test
	void wrapMovesWordsAndHangsListItems() {
		List<String> out = GoalLogic.wrap("- one two three four five", 12, CHARS);
		assertEquals(List.of("- one two", "  three four", "  five"), out);
		out = GoalLogic.wrap("12. alpha beta gamma", 12, CHARS);
		assertEquals(List.of("12. alpha", "    beta", "    gamma"), out);
		for (String l : GoalLogic.wrap("plain words that go on and on and on", 10, CHARS)) {
			assertTrue(l.length() <= 10, l);
		}
	}

	@Test
	void wrapBreaksLongWords() {
		List<String> out = GoalLogic.wrap("see src/very/long/path/to/a/file.ts now", 10, CHARS);
		for (String l : out) {
			assertTrue(l.length() <= 10, l);
		}
		assertEquals("seesrc/very/long/path/to/a/file.tsnow", String.join("", out).replace(" ", ""), "nothing lost");
	}

	@Test
	void hangingIndent() {
		assertEquals(2, GoalLogic.hangingIndent("- x"));
		assertEquals(4, GoalLogic.hangingIndent("  > x"));
		assertEquals(3, GoalLogic.hangingIndent("1. x"));
		assertEquals(0, GoalLogic.hangingIndent("plain"));
	}

	@Test
	void seenPerWorldAndGoal(@TempDir Path dir) throws Exception {
		HubSeen s = new HubSeen();
		assertEquals(0, s.goalSeen("w", "g1"));
		assertFalse(s.away("w", 1_000_000), "never looked: no digest");
		s.markTab("w", 1000);
		assertEquals(1000, s.goalSeen("w", "g1"), "a goal never opened counts as seen with the list");
		s.markGoal("w", "g1", 5000);
		s.markGoal("w", "g1", 4000);
		assertEquals(5000, s.goalSeen("w", "g1"), "never moves back");
		assertTrue(s.opened("w", "g1"));
		assertFalse(s.opened("other", "g1"));
		assertEquals(0, s.goalSeen("other", "g1"), "per world");
		assertFalse(s.away("w", 1000 + HubSeen.AWAY_MS - 1));
		assertTrue(s.away("w", 1000 + HubSeen.AWAY_MS));
		assertTrue(s.dirty());
		Path f = dir.resolve("agentcraft").resolve(HubSeen.FILE);
		s.save(f);
		assertFalse(s.dirty());
		HubSeen back = HubSeen.load(f);
		assertEquals(5000, back.goalSeen("w", "g1"));
		assertEquals(1000, back.tabSeen("w"));
		assertFalse(back.dirty());
		// a broken file loads as empty
		Files.writeString(f, "{not json");
		assertEquals(0, HubSeen.load(f).tabSeen("w"));
		assertEquals(0, HubSeen.load(dir.resolve("missing.json")).tabSeen("w"));
	}

	@Test
	void aGoalThatMovedBetweenVisitsIsUnreadDuringTheNextVisit() {
		HubSeen s = new HubSeen();
		// visit 1: 1000..2000
		long base1 = s.beginVisit("w");
		assertEquals(0, base1);
		s.endVisit("w", 2000);
		// g1 (never opened) has activity at 3000; visit 2 starts at 5000
		long base2 = s.beginVisit("w");
		assertEquals(2000, base2, "the fallback is the mark from before this visit");
		assertTrue(GoalLogic.unread(3000, s.goalSeen("w", "g1", base2)), "unread although the tab is on screen");
		assertFalse(GoalLogic.unread(1500, s.goalSeen("w", "g1", base2)));
		// opening it marks it; a goal's own mark wins over the fallback
		s.markGoal("w", "g1", 5100);
		assertFalse(GoalLogic.unread(3000, s.goalSeen("w", "g1", base2)));
		s.endVisit("w", 6000);
		assertEquals(6000, s.tabSeen("w"));
		assertFalse(s.away("w", 6000 + HubSeen.AWAY_MS - 1));
	}

	@Test
	void seenDropsTheLeastRecentlySeenGoals() {
		HubSeen s = new HubSeen();
		for (int i = 0; i < HubSeen.MAX_GOALS + 3; i++) {
			s.markGoal("w", "g" + i, 10 + i);
		}
		assertFalse(s.opened("w", "g0"));
		assertTrue(s.opened("w", "g" + (HubSeen.MAX_GOALS + 2)));
	}
}
