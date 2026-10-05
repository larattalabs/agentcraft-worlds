package dev.agentcraft.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.agentcraft.hud.HudSettings.Position;
import dev.agentcraft.hud.HudSettings.Size;
import dev.agentcraft.hud.HudSettings.Style;
import dev.agentcraft.hud.HudSettings.Toasts;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HudSettings (hud.json), HudPeek (peek timing) and HudVisibility (hide rules, combat). */
class HudStylesTest {
	// ------------------------------------------------------------------ settings

	@Test
	void defaults() {
		HudSettings h = new HudSettings();
		assertEquals(Style.PILL, h.style());
		assertEquals(Position.TOP_RIGHT, h.position());
		assertEquals(Size.M, h.size());
		assertTrue(h.peek());
		assertTrue(h.autoHide());
		assertFalse(h.hideInCombat());
		assertEquals(Toasts.NEEDS_YOU, h.toasts());
		assertEquals(HudSettings.OFFSET_DEFAULT, h.topLeftOffset());
		assertFalse(h.dirty());
	}

	@Test
	void roundTripAndTolerance(@TempDir Path dir) throws Exception {
		HudSettings h = new HudSettings();
		h.setStyle(Style.PILL_PLUS);
		h.setPosition(Position.BOTTOM_LEFT);
		h.setSize(Size.L);
		h.setPeek(false);
		h.setAutoHide(false);
		h.setHideInCombat(true);
		h.setToasts(Toasts.ALL);
		h.setTopLeftOffset(500);
		assertEquals(HudSettings.OFFSET_MAX, h.topLeftOffset(), "clamped");
		assertTrue(h.dirty());
		Path f = dir.resolve("agentcraft").resolve(HudSettings.FILE);
		h.save(f);
		assertFalse(h.dirty());
		HudSettings back = HudSettings.load(f);
		assertEquals(h.toJson(), back.toJson());
		assertEquals("pill_plus", back.toJson().get("style").getAsString());

		Files.writeString(f, "{\"style\": 7, \"position\": \"middle\", \"size\": \"large\", \"peek\": \"yes\", \"toasts\": \"all\", \"topLeftOffset\": -5}");
		HudSettings odd = HudSettings.load(f);
		assertEquals(Style.PILL, odd.style(), "a number is not a style");
		assertEquals(Position.TOP_RIGHT, odd.position(), "unknown position");
		assertEquals(Size.L, odd.size());
		assertTrue(odd.peek(), "a string is not a boolean");
		assertEquals(Toasts.ALL, odd.toasts());
		assertEquals(0, odd.topLeftOffset());
		Files.writeString(f, "not json {");
		assertEquals(Style.PILL, HudSettings.load(f).style());
		assertEquals(Style.PILL, HudSettings.load(dir.resolve("missing.json")).style());
		assertEquals(Style.PILL, HudSettings.fromJson(JsonParser.parseString("[1, 2]")).style());
	}

	@Test
	void parsingAndCycling() {
		assertEquals(Style.PILL_PLUS, HudSettings.parseStyle("Pill+"));
		assertEquals(Style.PILL_PLUS, HudSettings.parseStyle("pill-plus"));
		assertEquals(Style.OFF, HudSettings.parseStyle("OFF"));
		assertNull(HudSettings.parseStyle("big"));
		assertEquals(Position.RIGHT_MIDDLE, HudSettings.parsePosition("right middle"));
		assertEquals(Size.S, HudSettings.parseSize("small"));
		assertEquals(Toasts.NEEDS_YOU, HudSettings.parseToasts("need_user"));
		HudSettings h = new HudSettings();
		assertEquals(Style.PILL_PLUS, h.cycleStyle());
		assertEquals(Style.PANEL, h.cycleStyle());
		assertEquals(Style.OFF, h.cycleStyle());
		assertEquals(Style.PILL, h.cycleStyle());
		HudSettings copy = new HudSettings();
		h.setPosition(Position.TOP_LEFT);
		copy.copyFrom(h);
		assertEquals(Position.TOP_LEFT, copy.position());
	}

	// ------------------------------------------------------------------ peek

	@Test
	void peekShowsFiveSecondsThenTheNext() {
		HudPeek p = new HudPeek();
		assertNull(p.current(0));
		p.push(HudPeek.TASK_DONE, "Parse tags done", 1000);
		assertEquals("Parse tags done", p.current(1000).text());
		assertEquals(HudPeek.SHOW_MS, p.remaining(1000));
		assertEquals("Parse tags done", p.current(1000 + HudPeek.SHOW_MS - 1).text());
		assertNull(p.current(1000 + HudPeek.SHOW_MS));
		// two at once: the first gets the short slot while the second waits
		p.push(HudPeek.DECISION, "Merge t3?", 10_000);
		p.push(HudPeek.GOAL_DONE, "Export done", 10_000);
		assertEquals(HudPeek.DECISION, p.current(10_000).kind());
		assertEquals(1, p.waiting());
		assertEquals(HudPeek.DECISION, p.current(10_000 + HudPeek.MIN_MS - 1).kind());
		assertEquals(HudPeek.GOAL_DONE, p.current(10_000 + HudPeek.MIN_MS).kind());
		assertEquals(HudPeek.GOAL_DONE, p.current(10_000 + HudPeek.MIN_MS + HudPeek.SHOW_MS - 1).kind());
		assertNull(p.current(10_000 + HudPeek.MIN_MS + HudPeek.SHOW_MS));
	}

	@Test
	void peekQueueIsBoundedAndSkipsRepeats() {
		HudPeek p = new HudPeek();
		p.push(HudPeek.TASK_DONE, "a", 0);
		p.push(HudPeek.TASK_DONE, "a", 0);
		assertEquals(1, p.waiting(), "the same change twice");
		for (int i = 0; i < 10; i++) {
			p.push(HudPeek.TASK_DONE, "t" + i, 0);
		}
		assertEquals(HudPeek.MAX_QUEUE, p.waiting());
		assertEquals("t6", p.current(0).text(), "the oldest waiting ones were dropped");
		p.clear();
		assertNull(p.current(0));
	}

	@Test
	void whichChangesPeek() {
		assertEquals(HudPeek.TASK_DONE, HudPeek.taskChange("doing", "done", false));
		assertEquals(HudPeek.PR_MERGED, HudPeek.taskChange("pr", "done", false));
		assertEquals(HudPeek.PR_MERGED, HudPeek.taskChange("review", "done", true));
		assertNull(HudPeek.taskChange(null, "done", false), "first seen done: no peek");
		assertNull(HudPeek.taskChange("done", "done", false));
		assertNull(HudPeek.taskChange("todo", "doing", false));
		assertEquals(HudPeek.DECISION, HudPeek.decisionChange(null, true));
		assertEquals(HudPeek.DECISION, HudPeek.decisionChange(false, true));
		assertNull(HudPeek.decisionChange(true, true));
		assertNull(HudPeek.decisionChange(true, false));
		assertEquals(HudPeek.GOAL_DONE, HudPeek.goalChange("active", "done"));
		assertNull(HudPeek.goalChange(null, "done"));
		assertNull(HudPeek.goalChange("done", "done"));
		assertEquals("Merge the tag…", HudPeek.firstWords("Merge the tag parser now?", 15));
		assertEquals("Short", HudPeek.firstWords("  Short ", 15));
		assertEquals("", HudPeek.firstWords(null, 15));
	}

	// ------------------------------------------------------------------ visibility

	@Test
	void combat() {
		long now = 100_000;
		assertFalse(HudVisibility.inCombat(now, 0, false));
		assertTrue(HudVisibility.inCombat(now, now - 1000, false), "hurt a second ago");
		assertFalse(HudVisibility.inCombat(now, now - HudVisibility.COMBAT_MS, false), "five seconds ago is over");
		assertTrue(HudVisibility.inCombat(now, 0, true), "a hostile nearby");
	}

	@Test
	void hiddenReasons() {
		assertEquals(HudVisibility.NO_WORLD, HudVisibility.hiddenReason(false, true, Style.PILL, true, false, false, true, false));
		assertEquals(HudVisibility.F1, HudVisibility.hiddenReason(true, true, Style.OFF, true, false, false, true, false));
		assertEquals(HudVisibility.OFF, HudVisibility.hiddenReason(true, false, Style.OFF, true, false, false, true, false));
		assertEquals(HudVisibility.NO_DATA, HudVisibility.hiddenReason(true, false, Style.PILL, false, false, false, true, false));
		assertEquals(HudVisibility.COMBAT, HudVisibility.hiddenReason(true, false, Style.PANEL, true, true, true, true, false));
		assertNull(HudVisibility.hiddenReason(true, false, Style.PANEL, true, false, true, true, false), "hide in combat off");
		assertEquals(HudVisibility.IDLE, HudVisibility.hiddenReason(true, false, Style.PILL, true, false, false, true, true));
		assertNull(HudVisibility.hiddenReason(true, false, Style.PILL, true, false, false, false, true), "auto-hide off");
		assertTrue(HudVisibility.idle(false, false, false));
		assertFalse(HudVisibility.idle(true, false, false));
		assertFalse(HudVisibility.idle(false, true, false));
		assertFalse(HudVisibility.idle(false, false, true));
		assertTrue(HudVisibility.toastFor(Toasts.NEEDS_YOU, true));
		assertFalse(HudVisibility.toastFor(Toasts.NEEDS_YOU, false));
		assertTrue(HudVisibility.toastFor(Toasts.ALL, false));
	}
}
