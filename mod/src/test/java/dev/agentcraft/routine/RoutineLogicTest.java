package dev.agentcraft.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.agentcraft.routine.BedPicker.Bed;
import dev.agentcraft.routine.BedPicker.Want;
import dev.agentcraft.routine.LibraryVisits.Phase;
import dev.agentcraft.routine.RoutineRules.Facts;
import dev.agentcraft.routine.RoutineRules.Kind;
import dev.agentcraft.routine.RoutineSettings.Toggle;
import dev.agentcraft.routine.StandupTracker.Cue;
import dev.agentcraft.routine.StandupTracker.GoalView;
import dev.agentcraft.routine.StandupTracker.Standup;
import dev.agentcraft.routine.StandupTracker.TaskView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutineLogicTest {
	// ------------------------------------------------------------------ night

	@Test
	void nightWindowEdgesAndBigClocks() {
		assertFalse(RoutineRules.isNight(12_999));
		assertTrue(RoutineRules.isNight(13_000));
		assertTrue(RoutineRules.isNight(22_999));
		assertFalse(RoutineRules.isNight(23_000));
		assertFalse(RoutineRules.isNight(0));
		assertTrue(RoutineRules.isNight(24_000L * 1_000_000 + 18_000)); // day one million, midnight
		assertFalse(RoutineRules.isNight(24_000L * 1_000_000 + 6_000));
		assertTrue(RoutineRules.isNight(-6_000)); // floorMod: 18000
		assertEquals(18_000, RoutineRules.timeOfDay(-6_000));
		assertEquals(1_000, RoutineRules.ticksToSwitch(12_000));
		assertEquals(5_000, RoutineRules.ticksToSwitch(18_000));
		assertEquals(14_000, RoutineRules.ticksToSwitch(23_000)); // to the next nightfall
	}

	// ------------------------------------------------------------------ priorities

	static Facts idle() {
		return new Facts(false, false, false, false, true);
	}

	@Test
	void priorities() {
		Facts working = new Facts(false, false, true, true, true);
		Facts between = new Facts(false, false, true, false, true);
		Facts asking = new Facts(true, false, false, false, true);
		Facts walking = new Facts(false, true, false, false, true);
		Facts offShift = new Facts(false, false, true, true, false);
		// the player and trips win over everything
		assertEquals(Kind.NONE, RoutineRules.decide(asking, false, Kind.REST, true, true, true));
		assertEquals(Kind.NONE, RoutineRules.decide(walking, false, Kind.NONE, true, true, true));
		// stand-up beats work and library; working agents join it
		assertEquals(Kind.STANDUP, RoutineRules.decide(working, false, Kind.NONE, true, true, true));
		// library only between steps
		assertEquals(Kind.LIBRARY, RoutineRules.decide(between, false, Kind.NONE, false, true, true));
		assertEquals(Kind.LIBRARY, RoutineRules.decide(idle(), false, Kind.NONE, false, true, true));
		assertEquals(Kind.NONE, RoutineRules.decide(working, false, Kind.NONE, false, true, true));
		// rest: idle agents at night (and off shift ones), never working ones, never with a task between steps
		assertEquals(Kind.REST, RoutineRules.decide(idle(), false, Kind.NONE, false, false, true));
		assertEquals(Kind.REST, RoutineRules.decide(offShift, false, Kind.NONE, false, false, true));
		assertEquals(Kind.NONE, RoutineRules.decide(working, false, Kind.NONE, false, false, true));
		assertEquals(Kind.NONE, RoutineRules.decide(between, false, Kind.NONE, false, false, true));
		assertEquals(Kind.NONE, RoutineRules.decide(idle(), false, Kind.REST, false, false, false)); // morning
		// stale link: nothing starts or ends
		assertEquals(Kind.REST, RoutineRules.decide(working, true, Kind.REST, false, false, false));
		assertEquals(Kind.NONE, RoutineRules.decide(idle(), true, Kind.NONE, false, false, true));
	}

	// ------------------------------------------------------------------ settings

	@Test
	void settingsDefaultOnPerWorldAndRoundTrip(@TempDir Path dir) throws Exception {
		RoutineSettings s = new RoutineSettings();
		for (Toggle t : Toggle.values()) {
			assertTrue(s.enabled("w", t));
		}
		assertTrue(s.set("w", Toggle.NIGHT, false));
		assertFalse(s.set("w", Toggle.NIGHT, false));
		assertFalse(s.enabled("w", Toggle.NIGHT));
		assertTrue(s.enabled("w", Toggle.STANDUPS));
		assertTrue(s.enabled("other", Toggle.NIGHT));
		Path f = dir.resolve("agentcraft").resolve(RoutineSettings.FILE);
		s.save(f);
		assertFalse(s.dirty());
		RoutineSettings back = RoutineSettings.load(f);
		assertFalse(back.enabled("w", Toggle.NIGHT));
		assertTrue(back.enabled("w", Toggle.LIBRARY));
		// malformed parts are skipped, not fatal
		RoutineSettings bad = RoutineSettings.fromJson(JsonParser.parseString(
			"{\"worlds\":{\"a\":{\"night\":\"no\",\"library\":false},\"b\":7}}"));
		assertTrue(bad.enabled("a", Toggle.NIGHT));
		assertFalse(bad.enabled("a", Toggle.LIBRARY));
		assertTrue(bad.enabled("b", Toggle.STANDUPS));
		Files.writeString(f, "not json");
		assertTrue(RoutineSettings.load(f).enabled("w", Toggle.NIGHT));
		assertEquals(Toggle.STANDUPS, Toggle.parse("Stand-ups"));
		assertEquals(Toggle.NIGHT, Toggle.parse("night"));
		assertEquals(Toggle.LIBRARY, Toggle.parse("library_visits"));
		assertNull(Toggle.parse("roads"));
	}

	// ------------------------------------------------------------------ beds

	@Test
	void bedsStickyNearestOccupiedOverflow() {
		List<Bed> beds = List.of(new Bed("bed", 0.5, 1, 0.5, false), new Bed("bed_2", 10.5, 1, 0.5, false), new Bed("bed_3", 20.5, 1, 0.5, true));
		Map<String, String> got = BedPicker.assign(List.of(new Want("a", new double[] {11, 1, 2}), new Want("b", new double[] {1, 1, 2}),
			new Want("c", new double[] {20, 1, 1})), beds, Map.of());
		assertEquals("bed_2", got.get("a"));
		assertEquals("bed", got.get("b"));
		assertNull(got.get("c")); // bed_3 is the player's tonight; no free bed left: lounge
		// sticky: b keeps bed_2 even though bed is nearer; a newcomer takes what is left
		got = BedPicker.assign(List.of(new Want("a", new double[] {0, 1, 0}), new Want("b", new double[] {0, 1, 0})), beds, Map.of("b", "bed_2"));
		assertEquals("bed_2", got.get("b"));
		assertEquals("bed", got.get("a"));
		// a previous bed the player now sleeps in is given up
		got = BedPicker.assign(List.of(new Want("c", null)), beds, Map.of("c", "bed_3"));
		assertEquals("bed", got.get("c"));
		assertTrue(BedPicker.isBedAnchor("bed"));
		assertTrue(BedPicker.isBedAnchor("bed_12"));
		assertTrue(BedPicker.isBedAnchor("bed_2:pocket-api"));
		assertFalse(BedPicker.isBedAnchor("bedside"));
		assertFalse(BedPicker.isBedAnchor("lounge"));
	}

	// ------------------------------------------------------------------ stand-ups

	static GoalView goal(String id, boolean active) {
		return new GoalView(id, 100, active, "lead1", "Ship the login page\nwith tests");
	}

	static TaskView task(String id, String goal, String who, long at) {
		return new TaskView(id, goal, who, "Task " + id, true, at);
	}

	@Test
	void standupWaitsForTheFirstTasksThenOncePerGoal() {
		StandupTracker t = new StandupTracker();
		List<TaskView> tasks = new ArrayList<>();
		List<GoalView> goals = List.of(goal("g1", true));
		assertTrue(t.due(goals, tasks, 0).isEmpty()); // active but nothing assigned yet
		tasks.add(task("t1", "g1", "kit", 1));
		assertTrue(t.due(goals, tasks, 10).isEmpty()); // debounce: the next assignments arrive one by one
		tasks.add(task("t2", "g1", "wren", 2));
		tasks.add(task("t3", "g1", "kit", 3)); // kit's second task: kit speaks once (its first)
		tasks.add(task("t4", "g1", "lead1", 4)); // the lead is not a worker
		tasks.add(new TaskView("t5", "g1", "rowan", "done one", false, 0)); // closed tasks do not count
		assertTrue(t.due(goals, tasks, 10 + StandupTracker.DEBOUNCE_TICKS - 1).isEmpty());
		List<Standup> due = t.due(goals, tasks, 10 + StandupTracker.DEBOUNCE_TICKS);
		assertEquals(1, due.size());
		Standup s = due.getFirst();
		assertEquals(List.of("lead1", "kit", "wren"), s.participants());
		assertEquals("Ship the login page", s.line);
		assertEquals("Task t1", s.workers.getFirst().taskTitle());
		assertTrue(t.due(goals, tasks, 10_000).isEmpty()); // once per goal
		// a new goal with the same id but another createdAt is another goal
		assertEquals(0, t.due(List.of(new GoalView("g1", 200, true, "lead1", "x")), tasks, 20_000).size());
		assertEquals(1, t.due(List.of(new GoalView("g1", 200, true, "lead1", "x")), tasks, 20_000 + StandupTracker.DEBOUNCE_TICKS).size());
	}

	@Test
	void seedSkipsGoalsAlreadyUnderWayAndInactiveGoalsWait() {
		StandupTracker t = new StandupTracker();
		List<TaskView> tasks = List.of(task("t1", "g1", "kit", 1), task("t2", "g2", "wren", 1));
		t.seed(List.of(goal("g1", true), goal("g2", false), goal("g3", false)), tasks);
		assertTrue(t.seen("g1:100"));
		assertTrue(t.seen("g2:100")); // planning with assignments already: its moment passed
		assertFalse(t.seen("g3:100"));
		assertTrue(t.due(List.of(goal("g1", true)), tasks, 0).isEmpty());
		assertTrue(t.due(List.of(goal("g1", true)), tasks, 1_000).isEmpty());
		// planning goals never get one; when g3 turns active with an assignment it does
		List<TaskView> t3 = List.of(task("t9", "g3", "tove", 5));
		assertTrue(t.due(List.of(goal("g3", false)), t3, 2_000).isEmpty());
		assertTrue(t.due(List.of(goal("g3", false)), t3, 5_000).isEmpty());
		assertTrue(t.due(List.of(goal("g3", true)), t3, 6_000).isEmpty());
		assertEquals(1, t.due(List.of(goal("g3", true)), t3, 6_000 + StandupTracker.DEBOUNCE_TICKS).size());
	}

	@Test
	void standupTimelineGathersSpeaksAndEndsWithin20To30Seconds() {
		StandupTracker t = new StandupTracker();
		Standup s = StandupTracker.build(goal("g1", true), List.of(task("t1", "g1", "kit", 1), task("t2", "g1", "wren", 2)));
		assertNotNull(s);
		t.start(s, 1_000);
		assertEquals(s, t.of("kit", 1_000));
		assertNull(t.of("tove", 1_000));
		assertTrue(s.tick(1_010, false).isEmpty()); // still walking there
		assertEquals(1_000 + StandupTracker.MAX_TICKS, s.end());
		assertTrue(s.tick(1_050, true).isEmpty()); // everyone arrived: gathered at 1050
		assertEquals(1_050, s.gatheredAt());
		List<Cue> said = new ArrayList<>();
		for (long now = 1_051; now < 2_000; now++) {
			said.addAll(s.tick(now, true));
		}
		assertEquals(List.of("lead1", "kit", "wren"), said.stream().map(Cue::agentId).toList());
		assertEquals("all", said.getFirst().to());
		assertEquals("lead1", said.get(1).to());
		long end = s.end();
		assertTrue(end - 1_000 >= StandupTracker.MIN_TICKS && end - 1_000 <= StandupTracker.MAX_TICKS, "ends 20-30 s after the start: " + (end - 1_000));
		assertTrue(t.running(end - 1).contains(s));
		assertFalse(t.running(end).contains(s));
		assertEquals("ended", t.history().getFirst().outcome());
		// never gathered (someone could not get there): it still ends at the cap, lines from GATHER_TICKS on
		Standup late = StandupTracker.build(goal("g2", true), List.of(task("t1", "g2", "kit", 1)));
		t.start(late, 0);
		assertTrue(late.tick(StandupTracker.GATHER_TICKS - 1, false).isEmpty());
		late.tick(StandupTracker.GATHER_TICKS, false);
		assertEquals(StandupTracker.GATHER_TICKS, late.gatheredAt());
		// five workers still fit in 30 s
		List<TaskView> five = new ArrayList<>();
		for (String w : List.of("juniper", "kit", "wren", "rowan", "tove")) {
			five.add(task("t-" + w, "g5", w, five.size()));
		}
		Standup big = StandupTracker.build(goal("g5", true), five);
		t.start(big, 0);
		big.tick(StandupTracker.GATHER_TICKS, false);
		assertTrue(big.cues().getLast().at() + StandupTracker.GATHER_TICKS < StandupTracker.MAX_TICKS, "the last worker speaks before the end");
		assertTrue(big.end() <= StandupTracker.MAX_TICKS && big.end() >= StandupTracker.MIN_TICKS);
		t.skip(StandupTracker.build(goal("g6", true), five), "player far (90 blocks)", 5);
		assertTrue(t.seen("g6:100"));
		assertEquals("skipped: player far (90 blocks)", t.history().getFirst().outcome());
		assertEquals("Plan title", StandupTracker.clean("## Plan   title\nbody"));
		assertEquals(90, StandupTracker.clean("x".repeat(200)).length());
	}

	// ------------------------------------------------------------------ library

	@Test
	void libraryVisitWaitsForABreakReadsAndCoolsDown() {
		LibraryVisits v = new LibraryVisits();
		Set<String> known = Set.of("kit", "wren");
		assertTrue(v.note("kit", 0));
		assertTrue(v.due("kit"));
		assertEquals(Phase.WAITING, v.of("kit").phase());
		assertFalse(v.of("kit").holdsBook());
		v.tick(100, known::contains);
		v.start("kit", 100);
		assertEquals(Phase.WALKING, v.of("kit").phase());
		assertTrue(v.of("kit").holdsBook());
		assertFalse(v.note("kit", 120)); // visiting: nothing new
		v.arrived("kit", 160);
		assertEquals(Phase.READING, v.of("kit").phase());
		v.tick(160 + LibraryVisits.READ_TICKS - 1, known::contains);
		assertTrue(v.due("kit"));
		v.tick(160 + LibraryVisits.READ_TICKS, known::contains);
		assertFalse(v.due("kit"));
		assertEquals("done", v.history().getFirst().outcome());
		// cooldown: a note right after a visit adds nothing, later it does
		assertFalse(v.note("kit", 300));
		assertTrue(v.note("kit", 260 + LibraryVisits.COOLDOWN_TICKS + 1));
		// a note that never finds a break expires
		assertTrue(v.note("wren", 0));
		v.tick(LibraryVisits.WAIT_TICKS + 1, known::contains);
		assertFalse(v.due("wren"));
		// a visit that cannot get there ends at the cap
		v.force("wren", 10_000);
		v.start("wren", 10_000);
		v.tick(10_000 + LibraryVisits.MAX_TICKS, known::contains);
		assertFalse(v.due("wren"));
		assertEquals("timeout", v.history().getFirst().outcome());
		// cancelled by work; agents that left are dropped
		v.force("wren", 20_000);
		v.start("wren", 20_000);
		v.cancel("wren", "working", 20_010);
		assertFalse(v.due("wren"));
		v.force("gone", 0);
		v.tick(1, known::contains);
		assertFalse(v.due("gone"));
	}

	@Test
	void decideWithTrackersEndToEnd() {
		// one night: kit idle rests, wren works, the stand-up pulls wren and the lead, a note sends kit to the library
		StandupTracker st = new StandupTracker();
		LibraryVisits lib = new LibraryVisits();
		Map<String, Facts> facts = new HashMap<>();
		facts.put("kit", idle());
		facts.put("wren", new Facts(false, false, true, true, true));
		facts.put("lead1", idle());
		boolean night = RoutineRules.isNight(18_000);
		Map<String, Kind> k = new HashMap<>();
		for (var e : facts.entrySet()) {
			k.put(e.getKey(), RoutineRules.decide(e.getValue(), false, Kind.NONE, st.of(e.getKey(), 0) != null, lib.due(e.getKey()), night));
		}
		assertEquals(Map.of("kit", Kind.REST, "wren", Kind.NONE, "lead1", Kind.REST), k);
		Standup s = StandupTracker.build(goal("g1", true), List.of(task("t1", "g1", "wren", 1)));
		st.start(s, 0);
		lib.note("kit", 0);
		for (var e : facts.entrySet()) {
			k.put(e.getKey(), RoutineRules.decide(e.getValue(), false, Kind.NONE, st.of(e.getKey(), 1) != null, lib.due(e.getKey()), night));
		}
		assertEquals(Map.of("kit", Kind.LIBRARY, "wren", Kind.STANDUP, "lead1", Kind.STANDUP), k);
	}
}
