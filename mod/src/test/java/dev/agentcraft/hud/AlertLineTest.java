package dev.agentcraft.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertLineTest {
	static final ZoneId UTC = ZoneId.of("UTC");
	static final long NOW = ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, UTC).toInstant().toEpochMilli();
	static final long AT_1420 = ZonedDateTime.of(2026, 10, 3, 14, 20, 0, 0, UTC).toInstant().toEpochMilli();
	static final long TOMORROW = ZonedDateTime.of(2026, 10, 4, 9, 5, 0, 0, UTC).toInstant().toEpochMilli();

	@Test
	void fullTextOnlyNonZeroParts() {
		AlertLine a = new AlertLine(2, 1, 3, "usage", AT_1420, "limit");
		assertEquals("2 decisions · 1 blocked · 3 replies · usage paused until 14:20", a.text(AlertLine.Level.FULL, UTC, NOW));
		assertEquals("1 decision", new AlertLine(1, 0, 0, null, null, null).text(AlertLine.Level.FULL, UTC, NOW));
		assertEquals("1 reply", new AlertLine(0, 0, 1, null, null, null).text(AlertLine.Level.FULL, UTC, NOW));
		assertEquals("", AlertLine.NONE.text(AlertLine.Level.FULL, UTC, NOW));
	}

	@Test
	void visibleAndNeedsYou() {
		assertFalse(AlertLine.NONE.visible());
		assertTrue(new AlertLine(0, 0, 0, "auth", null, null).visible(), "a hold alone shows");
		assertEquals(1, new AlertLine(0, 0, 0, "auth", null, null).needsYou(), "a hold is one Inbox item, like InboxModel.Counts");
		assertEquals(6, new AlertLine(2, 1, 3, null, null, null).needsYou());
		assertFalse(new AlertLine(-1, 0, 0, " ", null, null).visible(), "negative counts and blank reasons are nothing");
	}

	/** Regression (wave 2 merge): PRs needing attention are in the Inbox's Needs you, so the line and badge count them. */
	@Test
	void prsAreCountedAndShown() {
		AlertLine a = new AlertLine(1, 0, 0, 2, "usage", AT_1420, null);
		assertTrue(new AlertLine(0, 0, 0, 1, null, null, null).visible());
		assertEquals(4, a.needsYou(), "1 decision + 2 PRs + the hold = the Inbox's Needs you");
		assertEquals("1 decision · 2 PRs · usage paused until 14:20", a.text(AlertLine.Level.FULL, UTC, NOW));
		assertEquals("1 PR", new AlertLine(0, 0, 0, 1, null, null, null).text(AlertLine.Level.FULL, UTC, NOW));
		assertEquals("1 dec · 2 PR · paused → 14:20", a.text(AlertLine.Level.SHORT, UTC, NOW));
	}

	@Test
	void holdTexts() {
		assertEquals("usage paused", AlertLine.holdText("usage", null, UTC, NOW, false));
		assertEquals("usage paused until Sun 09:05", AlertLine.holdText("usage", TOMORROW, UTC, NOW, false));
		assertEquals("paused → 14:20", AlertLine.holdText("usage", AT_1420, UTC, NOW, true));
		assertEquals("Claude sign-in needed", AlertLine.holdText("auth", null, UTC, NOW, false));
		assertEquals("Claude offline, retry 14:20", AlertLine.holdText("offline", AT_1420, UTC, NOW, false));
		assertEquals("agents paused", AlertLine.holdText("something-new", null, UTC, NOW, false));
		assertEquals("usage", new AlertLine(0, 0, 0, " USAGE ", null, null).holdReason());
	}

	@Test
	void partsCarryTheirDotFamilies() {
		List<AlertLine.Part> ps = new AlertLine(2, 1, 3, "usage", AT_1420, null).parts(UTC, NOW);
		assertEquals(List.of("decisions", "blocked", "replies", "hold"), ps.stream().map(AlertLine.Part::kind).toList());
		assertEquals(List.of("waiting", "error", "thinking", "idle"), ps.stream().map(AlertLine.Part::family).toList());
		assertEquals("2  1  3  14:20", new AlertLine(2, 1, 3, "usage", AT_1420, null).text(AlertLine.Level.DOTS, UTC, NOW));
		assertEquals("2 dec · 1 blk · 3 msg · paused → 14:20", new AlertLine(2, 1, 3, "usage", AT_1420, null).text(AlertLine.Level.SHORT, UTC, NOW));
	}

	@Test
	void fitPicksTheWidestLevelThatFits() {
		AlertLine a = new AlertLine(2, 1, 3, "usage", AT_1420, null);
		// one px per character, 10 px per dot, 3 per separator, 12 for the key hint
		int full = AlertLine.rowWidth(a.parts(UTC, NOW), AlertLine.Level.FULL, String::length, 10, 3);
		int dots = AlertLine.rowWidth(a.parts(UTC, NOW), AlertLine.Level.DOTS, String::length, 10, 3);
		assertEquals(AlertLine.Level.FULL, a.fit(String::length, full + 12, 10, 3, 12, UTC, NOW));
		assertEquals(AlertLine.Level.SHORT, a.fit(String::length, full + 11, 10, 3, 12, UTC, NOW));
		assertEquals(AlertLine.Level.DOTS, a.fit(String::length, dots + 12, 10, 3, 12, UTC, NOW));
		assertNull(a.fit(String::length, dots + 11, 10, 3, 12, UTC, NOW));
	}
}
