package dev.agentcraft.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.building.Trophy;
import dev.agentcraft.trophy.TrophyEvents;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class VillageBoardTest {
	static final ZoneId Z = ZoneId.of("UTC");
	/** Wednesday 2026-10-07 12:00 UTC. */
	static final long NOW = ZonedDateTime.of(2026, 10, 7, 12, 0, 0, 0, Z).toInstant().toEpochMilli();
	static final long DAY = 86_400_000L;

	static final VillageBoard.Site HOME = new VillageBoard.Site("b1", "Workshop", List.of("api"), "ines", "Ines", true);
	static final VillageBoard.Site CAMPUS = new VillageBoard.Site("b2", "Campus", List.of("web", "docs"), "theo", "Theo", false);

	static VillageBoard.Input input(List<VillageBoard.Goal> goals, List<VillageBoard.Pr> prs, List<TrophyEvents.Award> awards,
		List<VillageBoard.Hung> hung) {
		return new VillageBoard.Input(List.of(CAMPUS, HOME), goals, prs, awards, hung, null, 0, "", NOW, Z);
	}

	@Test
	void weekStartsOnLocalMonday() {
		assertEquals(ZonedDateTime.of(2026, 10, 5, 0, 0, 0, 0, Z).toInstant().toEpochMilli(), VillageBoard.weekStart(NOW, Z));
		long monday = ZonedDateTime.of(2026, 10, 5, 0, 0, 0, 0, Z).toInstant().toEpochMilli();
		assertEquals(monday, VillageBoard.weekStart(monday, Z));
		assertEquals(monday - 7 * DAY, VillageBoard.weekStart(monday - 1, Z)); // Sunday 23:59:59.999 is last week
	}

	@Test
	void rowsShowHomeFirstWithTheNewestOpenGoalAndPrCounts() {
		List<VillageBoard.Goal> goals = List.of(
			new VillageBoard.Goal("g1", "Old api goal", true, 0.2, List.of("api"), "ines", NOW - 3 * DAY),
			new VillageBoard.Goal("g2", "Ship the login page\nwith tests", true, 0.55, List.of("web"), "theo", NOW - DAY),
			new VillageBoard.Goal("g3", "Newer api goal", true, 1.7, List.of("api"), "ines", NOW - DAY),
			new VillageBoard.Goal("g4", "Done docs goal", false, 1, List.of("docs"), "theo", NOW));
		List<VillageBoard.Pr> prs = List.of(
			new VillageBoard.Pr("web", "12", true, false, NOW, false),
			new VillageBoard.Pr("docs", "13", true, false, NOW, true),
			new VillageBoard.Pr("web", "9", false, true, NOW - DAY, false), // merged this week (Tuesday)
			new VillageBoard.Pr("web", "8", false, true, NOW - 4 * DAY, false), // merged last week (Saturday)
			new VillageBoard.Pr("api", "3", false, false, NOW, false)); // abandoned
		VillageBoard.Content c = VillageBoard.build(input(goals, prs, List.of(), List.of()));
		assertEquals(2, c.rows().size());
		VillageBoard.Row home = c.rows().get(0);
		assertEquals("b1", home.buildingId());
		assertEquals("Newer api goal", home.goal());
		assertEquals(1.0, home.progress()); // clamped
		assertEquals(1, home.moreGoals());
		assertEquals(0, home.prsOpen());
		VillageBoard.Row campus = c.rows().get(1);
		assertEquals("web, docs", campus.repos());
		assertEquals("Ship the login page", campus.goal());
		assertEquals(0.55, campus.progress());
		assertEquals(0, campus.moreGoals());
		assertEquals(2, campus.prsOpen());
		assertEquals(1, campus.prsMerged());
		assertTrue(campus.failing());
		assertEquals(2, c.prsOpen());
		assertEquals(1, c.prsMerged());
	}

	@Test
	void homeTakesGoalsOfReposWithoutABuilding() {
		List<VillageBoard.Goal> goals = List.of(new VillageBoard.Goal("g9", "Elsewhere", true, 0, List.of("tools"), null, NOW));
		VillageBoard.Content c = VillageBoard.build(input(goals, List.of(), List.of(), List.of()));
		assertEquals("Elsewhere", c.rows().get(0).goal());
		assertNull(c.rows().get(1).goal());
		assertEquals(-1, c.rows().get(1).progress());
	}

	@Test
	void milestonesNewestFirstMarkHungTrophiesAndKeepOrphanSigns() {
		LocalDate d = LocalDate.of(2026, 10, 6);
		List<TrophyEvents.Award> awards = List.of(
			new TrophyEvents.Award(Trophy.goalKey("g4", 1), Trophy.goal("docs", "Write the guide\nmore", 3, d), NOW - 2 * DAY),
			new TrophyEvents.Award(Trophy.prKey("web", "9"), Trophy.pr("web", "9", "Fix login", d), NOW - DAY),
			new TrophyEvents.Award(Trophy.mergeKey("t5", 1), Trophy.merge("tools", "t5", "Local tweak", d), NOW - 3 * DAY));
		List<VillageBoard.Hung> hung = List.of(
			new VillageBoard.Hung("b2", Trophy.prKey("web", "9"), List.of("Merged PR #9", "Fix login", "", "2026-10-06"), NOW - DAY + 5),
			new VillageBoard.Hung("b1", "pr:api:1", List.of("Merged PR #1", "First API", "endpoint", "2026-09-01"), NOW - 30 * DAY));
		List<VillageBoard.Milestone> ms = VillageBoard.build(input(List.of(), List.of(), awards, hung)).milestones();
		assertEquals(List.of("pr:web:9", "goal:g4:1", "merge:t5:1", "pr:api:1"), ms.stream().map(VillageBoard.Milestone::key).toList());
		assertEquals("PR #9 merged", ms.get(0).label());
		assertEquals("Campus", ms.get(0).where());
		assertTrue(ms.get(0).trophy());
		assertEquals("Goal done", ms.get(1).label());
		assertEquals("Write the guide", ms.get(1).text());
		assertFalse(ms.get(1).trophy());
		assertEquals("tools", ms.get(2).where()); // no building for the repo: the repo's id
		assertNull(ms.get(2).buildingId());
		assertEquals("trophy", ms.get(3).kind());
		assertEquals("Merged PR #1", ms.get(3).label());
		assertEquals("First API endpoint", ms.get(3).text());
		assertEquals("Workshop", ms.get(3).where());
	}

	@Test
	void pagesTurnEveryTenSecondsAndCanBeHeld() {
		assertEquals(new VillageBoard.Page(0, 1, 0, 0), VillageBoard.page(0, 4, NOW, -1));
		assertEquals(new VillageBoard.Page(0, 1, 0, 3), VillageBoard.page(3, 4, NOW, -1));
		VillageBoard.Page p = VillageBoard.page(9, 4, 25_000, -1); // third period: page 2 of 3
		assertEquals(new VillageBoard.Page(2, 3, 8, 9), p);
		assertEquals(new VillageBoard.Page(0, 3, 0, 4), VillageBoard.page(9, 4, 30_000, -1));
		assertEquals(new VillageBoard.Page(1, 3, 4, 8), VillageBoard.page(9, 4, 0, 1));
		assertEquals(new VillageBoard.Page(2, 3, 8, 9), VillageBoard.page(9, 4, 0, 7)); // clamped to the last page
	}

	@Test
	void theBundledFiveByThreeBoardHasTwoColumnsAndRoom() {
		int ppb = VillageBoard.density(5, 3);
		assertEquals(52, ppb);
		VillageBoard.Layout l = VillageBoard.layout(5 * ppb, 3 * ppb, ppb, false);
		assertTrue(l.twoColumns());
		assertTrue(l.perPage() >= 3, "rows per page " + l.perPage());
		assertTrue(l.milestones() >= 4, "milestones " + l.milestones());
		VillageBoard.Layout held = VillageBoard.layout(5 * ppb, 3 * ppb, ppb, true);
		assertTrue(held.perPage() <= l.perPage());
		assertTrue(held.holdY() < held.footerY());
		// a 2x1 board still lays out (one column, at least the header), never negative
		VillageBoard.Layout tiny = VillageBoard.layout(2 * 64, 64, 64, true);
		assertFalse(tiny.twoColumns());
		assertTrue(tiny.perPage() >= 0);
		assertEquals(40, VillageBoard.density(8, 6));
		assertEquals(64, VillageBoard.density(2, 1));
	}

	@Test
	void agoIsCoarse() {
		assertEquals("just now", VillageBoard.ago(NOW - 5_000, NOW));
		assertEquals("5 min ago", VillageBoard.ago(NOW - 300_000, NOW));
		assertEquals("1 hour ago", VillageBoard.ago(NOW - 3_600_000, NOW));
		assertEquals("yesterday", VillageBoard.ago(NOW - DAY, NOW));
		assertEquals("3 days ago", VillageBoard.ago(NOW - 3 * DAY, NOW));
		assertEquals("just now", VillageBoard.ago(NOW + 10_000, NOW)); // clock skew: never negative
	}
}
