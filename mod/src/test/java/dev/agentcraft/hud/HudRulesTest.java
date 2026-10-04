package dev.agentcraft.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.hud.HudRules.GoalCand;
import dev.agentcraft.hud.HudRules.Pick;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HudRulesTest {
	static final long MIN = 60_000L;

	@Test
	void awaySinceIsTheLaterOfHubAndLastToast() {
		assertEquals(0, HudRules.awaySince(0, 500), "hub never opened: unknown");
		assertEquals(500, HudRules.awaySince(100, 500));
		assertEquals(900, HudRules.awaySince(900, 500));
	}

	@Test
	void awayDue() {
		long now = 100 * MIN;
		assertTrue(HudRules.awayDue(now, now - MIN, true, 0, false, true), "join: at once, however short the stretch");
		assertFalse(HudRules.awayDue(now, now - MIN, true, 0, true, true), "never while the hub is open");
		assertFalse(HudRules.awayDue(now, now - MIN, true, 0, false, false), "never offline");
		assertFalse(HudRules.awayDue(now, 0, true, 0, false, true), "unknown stretch");
		assertFalse(HudRules.awayDue(now, now - 9 * MIN, false, 0, false, true), "under 10 minutes");
		assertTrue(HudRules.awayDue(now, now - 10 * MIN, false, 0, false, true));
		assertFalse(HudRules.awayDue(now, now - 30 * MIN, false, now - MIN, false, true), "rechecks every 2 minutes at most");
		assertTrue(HudRules.awayDue(now, now - 30 * MIN, false, now - 2 * MIN, false, true));
	}

	@Test
	void awayText() {
		assertNull(HudRules.awayText(0, 3), "nothing moved: no toast");
		assertEquals("Since you were away: 1 goal moved", HudRules.awayText(1, 0));
		assertEquals("Since you were away: 2 goals moved, 1 needs you", HudRules.awayText(2, 1));
		assertEquals("Since you were away: 2 goals moved, 3 need you", HudRules.awayText(2, 3));
	}

	@Test
	void urgentGoalIsPinned() {
		List<GoalCand> gs = List.of(new GoalCand("g1", true, 0, 500, 1), new GoalCand("g2", true, 2, 100, 2), new GoalCand("g3", false, 9, 900, 3));
		Pick p = HudRules.pickGoal(gs, 0, null);
		assertEquals("g2", p.id());
		assertTrue(p.pinned());
		assertEquals(2, p.open());
		assertEquals(1, p.more());
		assertEquals(p, HudRules.pickGoal(gs, 123 * HudRules.CYCLE_MS, null), "pinned: no cycling");
	}

	@Test
	void calmGoalsTakeTurnsNewestFirst() {
		List<GoalCand> gs = List.of(new GoalCand("g1", true, 0, 100, 1), new GoalCand("g2", true, 0, 300, 2), new GoalCand("g3", true, 0, 200, 3));
		assertEquals(List.of("g2", "g3", "g1"), HudRules.order(gs).stream().map(GoalCand::id).toList());
		assertEquals("g2", HudRules.pickGoal(gs, 0, null).id());
		assertEquals("g3", HudRules.pickGoal(gs, HudRules.CYCLE_MS, null).id());
		assertEquals("g1", HudRules.pickGoal(gs, 2 * HudRules.CYCLE_MS + 5, null).id());
		assertEquals("g2", HudRules.pickGoal(gs, 3 * HudRules.CYCLE_MS, null).id());
		assertEquals(2, HudRules.pickGoal(gs, 0, null).more());
	}

	@Test
	void noOpenGoalFallsBack() {
		List<GoalCand> gs = List.of(new GoalCand("g1", false, 0, 100, 1));
		assertNull(HudRules.pickGoal(gs, 0, null));
		Pick p = HudRules.pickGoal(gs, 0, "g1");
		assertEquals("g1", p.id());
		assertEquals(0, p.more());
		Pick one = HudRules.pickGoal(List.of(new GoalCand("g4", true, 0, 1, 1)), 99 * HudRules.CYCLE_MS, null);
		assertEquals("g4", one.id());
		assertEquals(0, one.more());
	}

	@Test
	void welcomeDue() {
		assertTrue(HudRules.welcomeDue(true, false, false, 0, false, false, false));
		assertFalse(HudRules.welcomeDue(false, false, false, 0, false, false, false), "multiplayer: buildings unknown");
		assertFalse(HudRules.welcomeDue(true, true, false, 0, false, false, false), "dev HQ");
		assertFalse(HudRules.welcomeDue(true, false, true, 0, false, false, false), "buildings file unreadable");
		assertFalse(HudRules.welcomeDue(true, false, false, 1, false, false, false), "has a building");
		assertFalse(HudRules.welcomeDue(true, false, false, 0, true, false, false), "dismissed");
		assertFalse(HudRules.welcomeDue(true, false, false, 0, false, true, false), "shown this session");
		assertFalse(HudRules.welcomeDue(true, false, false, 0, false, false, true), "a screen is open");
	}

	@Test
	void prefsRoundTripAndTolerateJunk(@TempDir Path dir) throws Exception {
		HudPrefs p = new HudPrefs();
		assertFalse(p.known("W"));
		assertNull(p.lastTab("W"));
		p.setLastTab("W", "goals");
		p.markHubSeen("W", 500);
		p.markHubSeen("W", 400);
		p.setLastAwayToastAt("W", 600);
		p.markRepliesSeen("W", 700);
		p.setWelcomeDismissed("W", true);
		assertTrue(p.dirty());
		Path f = dir.resolve("agentcraft").resolve(HudPrefs.FILE);
		p.save(f);
		assertFalse(p.dirty());
		HudPrefs q = HudPrefs.load(f);
		assertTrue(q.known("W"));
		assertEquals("goals", q.lastTab("W"));
		assertEquals(500, q.hubSeenAt("W"), "never moves back");
		assertEquals(600, q.lastAwayToastAt("W"));
		assertEquals(700, q.repliesSeen("W"));
		assertTrue(q.welcomeDismissed("W"));
		assertFalse(q.welcomeDismissed("Other"));
		q.setHubSeenAtForTest("W", 100);
		assertEquals(100, q.hubSeenAt("W"));

		Files.writeString(f, "{\"worlds\":{\"W\":{\"lastTab\":3,\"hubSeenAt\":\"x\",\"welcomeDismissed\":\"yes\"},\"X\":7}}");
		HudPrefs r = HudPrefs.load(f);
		assertNull(r.lastTab("W"));
		assertEquals(0, r.hubSeenAt("W"));
		assertFalse(r.welcomeDismissed("W"));
		Files.writeString(f, "not json");
		assertEquals(0, HudPrefs.load(f).hubSeenAt("W"));
		assertEquals(0, HudPrefs.load(dir.resolve("missing.json")).hubSeenAt("W"));
	}
}
