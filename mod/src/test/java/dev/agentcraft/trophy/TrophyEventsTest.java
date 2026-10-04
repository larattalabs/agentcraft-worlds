package dev.agentcraft.trophy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.agentcraft.building.Trophy;
import dev.agentcraft.trophy.TrophyEvents.Award;
import dev.agentcraft.trophy.TrophyEvents.GoalIn;
import dev.agentcraft.trophy.TrophyEvents.TaskIn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrophyEventsTest {
	static final ZoneOffset Z = ZoneOffset.UTC;
	static final long T0 = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli();

	static GoalIn goal(String id, boolean done, String repo, long updated) {
		return new GoalIn(id, "Add an export command\nmore", done, repo, 1000, updated, 3);
	}

	static TaskIn task(String id, boolean done, String repo, String goal, String pr, boolean merged, long at) {
		return new TaskIn(id, "Rolling text", done, repo, goal, pr, merged, at, 500, at);
	}

	@Test
	void goalTurningDoneAwardsOnce() {
		GoalIn open = goal("g1", false, "app", T0 - 5);
		GoalIn done = goal("g1", true, "app", T0);
		Award a = TrophyEvents.goal(open, done, Z).orElseThrow();
		assertEquals("goal:g1:1000", a.key());
		assertEquals(Trophy.Kind.GOAL, a.trophy().kind());
		assertEquals("app", a.trophy().repo());
		assertEquals(3, a.trophy().taskCount());
		assertEquals(LocalDate.of(2026, 10, 4), a.trophy().date());
		assertTrue(TrophyEvents.goal(null, done, Z).isPresent(), "a new goal that is already done");
		assertFalse(TrophyEvents.goal(done, goal("g1", true, "app", T0 + 9), Z).isPresent(), "still done");
		assertFalse(TrophyEvents.goal(null, open, Z).isPresent());
		assertFalse(TrophyEvents.goal(open, goal("g1", true, null, T0), Z).isPresent(), "no repo, nowhere to hang");
	}

	@Test
	void prMergedAwardsThePr() {
		TaskIn open = task("t1", false, "app", "g1", "612", false, T0 - 9);
		TaskIn merged = task("t1", true, "app", "g1", "612", true, T0);
		Award a = TrophyEvents.task(open, merged, Map.of(), Z).orElseThrow();
		assertEquals("pr:app:612", a.key());
		assertEquals(Trophy.Kind.PR, a.trophy().kind());
		assertFalse(TrophyEvents.task(merged, merged, Map.of(), Z).isPresent());
		assertFalse(TrophyEvents.task(open, task("t1", true, "app", "g1", "612", false, T0), Map.of(), Z).isPresent(), "done but PR not merged");
	}

	@Test
	void taskWithoutPrIsAMergeWhenDone() {
		TaskIn doing = task("t7", false, null, "g1", null, false, T0 - 9);
		TaskIn done = task("t7", true, null, "g1", null, false, T0);
		assertFalse(TrophyEvents.task(doing, done, Map.of(), Z).isPresent(), "no repo anywhere");
		Award a = TrophyEvents.task(doing, done, Map.of("g1", "lib"), Z).orElseThrow();
		assertEquals("merge:t7:500", a.key());
		assertEquals("lib", a.trophy().repo());
		assertFalse(TrophyEvents.task(done, done, Map.of("g1", "lib"), Z).isPresent());
		assertFalse(TrophyEvents.task(null, doing, Map.of("g1", "lib"), Z).isPresent());
	}

	@Test
	void taskRepoWinsOverGoalRepo() {
		Award a = TrophyEvents.task(null, task("t1", true, "own", "g1", "5", true, T0), Map.of("g1", "lib"), Z).orElseThrow();
		assertEquals("own", a.trophy().repo());
	}

	@Test
	void catchUpIsOldestFirst() {
		List<Award> as = TrophyEvents.catchUp(
			List.of(goal("g1", true, "app", T0 + 20), goal("g2", false, "app", T0)),
			List.of(task("t1", true, "app", "g1", "1", true, T0 + 10), task("t2", true, "app", "g1", null, false, T0 + 5),
				task("t3", false, "app", "g1", "3", false, T0), task("t4", true, "app", "g1", "4", false, T0)),
			Map.of(), Z);
		assertEquals(List.of("merge:t2:500", "pr:app:1", "goal:g1:1000"), as.stream().map(Award::key).toList());
	}

	@Test
	void tenEventsOnSixSlotsKeepTheNewestSix() {
		List<TaskIn> tasks = new java.util.ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			tasks.add(task("t" + i, true, "app", "g1", null, false, T0 + i));
		}
		tasks.add(task("o1", true, "other", "g1", null, false, T0));
		List<Award> all = TrophyEvents.catchUp(List.of(), tasks, Map.of(), Z);
		List<Award> kept = TrophyEvents.newestPerRepo(all, repo -> repo.equals("app") ? 6 : 0);
		assertEquals(List.of("t5", "t6", "t7", "t8", "t9", "t10"), kept.stream().map(a -> a.trophy().taskId()).toList());
		assertEquals(0, TrophyEvents.newestPerRepo(all, repo -> 0).size());
	}

	@Test
	void settingsDefaultOnAndRoundTrip(@TempDir Path dir) throws Exception {
		TrophySettings s = new TrophySettings();
		assertTrue(s.enabled("New World"));
		assertTrue(s.set("New World", false));
		assertFalse(s.set("New World", false));
		assertFalse(s.enabled("New World"));
		assertTrue(s.enabled("Other"));
		Path f = dir.resolve("agentcraft").resolve(TrophySettings.FILE);
		s.save(f);
		assertFalse(s.dirty());
		TrophySettings back = TrophySettings.load(f);
		assertFalse(back.enabled("New World"));
		assertTrue(back.enabled("Other"));
		Files.writeString(f, "not json");
		assertTrue(TrophySettings.load(f).enabled("New World"));
		TrophySettings odd = TrophySettings.fromJson(JsonParser.parseString("{\"worlds\":{\"a\":{\"trophies\":\"no\"},\"b\":3,\"c\":{\"trophies\":false}}}"));
		assertTrue(odd.enabled("a"));
		assertTrue(odd.enabled("b"));
		assertFalse(odd.enabled("c"));
		assertTrue(TrophySettings.load(dir.resolve("missing.json")).enabled("x"));
	}
}
