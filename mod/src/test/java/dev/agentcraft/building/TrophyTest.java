package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trophies' pure parts: sign text, slot choice, the ledger, the removal-blocker exemption. */
class TrophyTest {
	static final LocalDate DAY = LocalDate.of(2026, 10, 4);

	static void assertFits(List<String> lines) {
		assertEquals(TrophyText.LINES, lines.size(), lines.toString());
		for (String l : lines) {
			assertTrue(TrophyText.width(l) <= TrophyText.WIDTH, "too wide (" + TrophyText.width(l) + "): " + l);
		}
	}

	// ------------------------------------------------------------------ text

	@Test
	void prTrophy() {
		List<String> l = TrophyText.lines(Trophy.pr("agentcraft", "#612", "Rolling text", DAY));
		assertEquals(List.of("Merged PR #612", "Rolling text", "", "2026-10-04"), l);
		assertEquals(l, TrophyText.lines(Trophy.pr("agentcraft", "612", "Rolling text", DAY)));
	}

	@Test
	void mergeTrophy() {
		assertEquals(List.of("Merged t12", "Fix the lamp", "", "2026-10-04"), TrophyText.lines(Trophy.merge("r", "t12", "Fix the lamp", DAY)));
		// Markdown marks in a task title do not end up on the sign (the village board drops them too)
		assertEquals(List.of("Merged t3", "notes list --tag +", "notes tags", "2026-10-04"),
			TrophyText.lines(Trophy.merge("r", "t3", "`notes list --tag` + **`notes tags`**", DAY)));
	}

	@Test
	void goalTrophyUsesTheFirstLineAndCountsTasks() {
		// the goal text needs both title lines: the count does not fit beside the date (107 px) and is left out
		assertTrue(TrophyText.width("3 tasks · 2026-10-04") > TrophyText.WIDTH);
		assertEquals(List.of("Goal done", "Add an export", "command", "2026-10-04"),
			TrophyText.lines(Trophy.goal("r", "\n  Add an export command\nwith details nobody reads", 3, DAY)));
		// one line of text: the count takes the second
		assertEquals(List.of("Goal done", "Export command", "3 tasks", "2026-10-04"), TrophyText.lines(Trophy.goal("r", "Export command", 3, DAY)));
		assertEquals("1 task", TrophyText.lines(Trophy.goal("r", "x", 1, DAY)).get(2));
		assertEquals(List.of("Goal done", "x", "", "2026-10-04"), TrophyText.lines(Trophy.goal("r", "x", null, DAY)));
		// without a date the count shares the last line
		assertEquals(List.of("Goal done", "Add an export", "command", "3 tasks"), TrophyText.lines(Trophy.goal("r", "Add an export command", 3, null)));
		assertEquals(List.of("Goal done", "", "12 tasks", "2026-10-04"), TrophyText.lines(Trophy.goal("r", "", 12, DAY)));
	}

	@Test
	void titleWrapsAtWordsAndTrimsTheSecondLine() {
		List<String> l = TrophyText.lines(Trophy.pr("r", "1", "Rolling text for the hub with a very long tail that cannot fit on a sign at all", DAY));
		assertFits(l);
		assertFalse(l.get(1).endsWith(" "));
		assertTrue("Rolling text for the hub with a very long tail".startsWith(l.get(1)), l.get(1));
		assertTrue(l.get(2).endsWith(TrophyText.ELLIPSIS), l.get(2));
		// the first line broke at a space: the next word would not have fit
		String next = "Rolling text for the hub with a very long tail".substring(l.get(1).length()).strip().split(" ")[0];
		assertTrue(TrophyText.width(l.get(1) + " " + next) > TrophyText.WIDTH);
	}

	@Test
	void aWordLongerThanALineIsBroken() {
		List<String> l = TrophyText.lines(Trophy.pr("r", "1", "Supercalifragilisticexpialidocious-and-then-some-more-letters", DAY));
		assertFits(l);
		assertFalse(l.get(1).isEmpty());
		assertTrue(l.get(2).endsWith(TrophyText.ELLIPSIS));
		assertEquals("Supercalifragilisticexpialidocious-and-then-some-more-letters".substring(0, l.get(1).length()), l.get(1));
	}

	@Test
	void widthsFollowTheDefaultFont() {
		assertEquals(6 * 4, TrophyText.width("WWWW"));
		assertEquals(2 + 3 + 4, TrophyText.width("il "));
		assertEquals(18, TrophyText.width("iiiiiiiii"));
		// 15 'W's are exactly 90 px: fit; 16 do not
		assertEquals("W".repeat(15), TrophyText.ellipsize("W".repeat(15), 90));
		String cut = TrophyText.ellipsize("W".repeat(16), 90);
		assertTrue(cut.endsWith(TrophyText.ELLIPSIS) && TrophyText.width(cut) <= 90, cut);
	}

	@Test
	void oddInputIsSafe() {
		assertFits(TrophyText.lines(Trophy.pr("r", null, null, DAY)));
		assertEquals(List.of("Merged PR", "", "", "2026-10-04"), TrophyText.lines(Trophy.pr("r", "", "   ", DAY)));
		assertEquals(List.of("Merged", "", "", ""), TrophyText.lines(new Trophy(Trophy.Kind.MERGE, "r", null, null, null, null, null)));
		assertEquals("Goal done", TrophyText.lines(Trophy.goal("r", "\n\n", -1, DAY)).get(0));
		// formatting codes and control characters never reach the sign
		assertEquals("Red text", TrophyText.lines(Trophy.pr("r", "1", "§cRed\u0000 \ttext§r", DAY)).get(1));
		assertEquals("Merged t", TrophyText.lines(Trophy.merge("r", "t§", "x", DAY)).get(0));
		// a huge task id is trimmed on its own line
		assertFits(TrophyText.lines(Trophy.merge("r", "t" + "9".repeat(200), "x", DAY)));
		// wide scripts and emoji
		assertFits(TrophyText.lines(Trophy.goal("r", "漢字のゴールがとても長い場合でも看板に収まること 🎉🎉🎉🎉🎉🎉🎉🎉🎉", 12345, DAY)));
	}

	@Test
	void randomTextAlwaysFits() {
		Random r = new Random(42);
		String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 !\"'()*,.:;<>@[]`{|}~-_ilIt§\n\té…·漢🎉";
		for (int n = 0; n < 2000; n++) {
			StringBuilder b = new StringBuilder();
			int len = r.nextInt(120);
			for (int i = 0; i < len; i++) {
				b.appendCodePoint(alphabet.codePointAt(alphabet.offsetByCodePoints(0, r.nextInt(alphabet.codePointCount(0, alphabet.length())))));
			}
			String s = b.toString();
			assertFits(TrophyText.lines(Trophy.pr("r", s, s, DAY)));
			assertFits(TrophyText.lines(Trophy.goal("r", s, r.nextInt(1000), DAY)));
		}
	}

	@Test
	void keys() {
		assertEquals("pr:agentcraft:612", Trophy.prKey("agentcraft", "#612"));
		assertEquals("pr:agentcraft:612", Trophy.prKey("agentcraft", "612"));
		assertEquals("goal:g1:17", Trophy.goalKey("g1", 17));
		assertEquals("merge:t12:5", Trophy.mergeKey("t12", 5));
	}

	// ------------------------------------------------------------------ slots

	static Anchor a(String name, double x, double y, double z, float yaw) {
		return new Anchor(name, x, y, z, yaw, 0f);
	}

	/** A group building's pin anchors: wing 1 has 3 slots (listed out of order), wing 2 has 2, plus other anchors. */
	static Map<String, Anchor> pinAnchors() {
		Map<String, Anchor> m = new LinkedHashMap<>();
		for (Anchor x : List.of(a("task_wall@1", 3.5, 66, 3.5, 0), a("trophy_3@1", 12.5, 66.5, 2.5, 0), a("trophy@1", 10.5, 66.5, 2.5, 0),
			a("trophy_2@1", 11.5, 66.5, 2.5, 0), a("trophy@2", 5.5, 66.5, 14.5, 180), a("trophy_2@2", 6.5, 66.5, 14.5, 180),
			a("trophy_x@1", 1.5, 66.5, 1.5, 0), a("trophyroom", 1.5, 66.5, 1.5, 0), a("trophy@9", 30.5, 66.5, 1.5, 0))) {
			m.put(x.name(), x);
		}
		return m;
	}

	static final Anchors.Bounds BOX = new Anchors.Bounds(0, 64, 0, 20, 72, 16);

	@Test
	void slotsParseNamesWingsAndFillOrder() {
		assertEquals(1, TrophySlots.orderOf("trophy@1"));
		assertEquals(2, TrophySlots.orderOf("trophy_2@3"));
		assertEquals(1, TrophySlots.orderOf("trophy"));
		assertEquals(0, TrophySlots.orderOf("trophy_x@1"));
		assertEquals(0, TrophySlots.orderOf("trophy_0@1"));
		assertEquals(0, TrophySlots.orderOf("trophyroom@1"));
		assertEquals(0, TrophySlots.orderOf("task_wall@1"));

		List<TrophySlots.Slot> w1 = TrophySlots.forWing(pinAnchors(), 1, BOX);
		assertEquals(List.of("trophy@1", "trophy_2@1", "trophy_3@1"), w1.stream().map(TrophySlots.Slot::name).toList());
		assertEquals(10, w1.get(0).x());
		assertEquals(66, w1.get(0).y());
		assertEquals(2, w1.get(0).z());
		List<TrophySlots.Slot> w2 = TrophySlots.forWing(pinAnchors(), 2, BOX);
		assertEquals(List.of("trophy@2", "trophy_2@2"), w2.stream().map(TrophySlots.Slot::name).toList());
		// a slot outside the box is never used
		assertEquals(List.of(), TrophySlots.forWing(pinAnchors(), 9, BOX));
		assertEquals(1, TrophySlots.forWing(pinAnchors(), 9, new Anchors.Bounds(0, 64, 0, 40, 72, 16)).size());
	}

	@Test
	void wingOfARepo() {
		assertEquals(1, TrophySlots.wingFor(List.of("a"), false, "a"));
		assertEquals(2, TrophySlots.wingFor(List.of("a", "b"), true, "b"));
		assertEquals(0, TrophySlots.wingFor(List.of("a", "b"), true, "c"));
	}

	@Test
	void facingAndSupport() {
		// yaw 0 faces south (+Z): the support is north of the sign
		TrophySlots.Slot s = new TrophySlots.Slot("trophy@1", 1, 1, 0, 0, 0, TrophySlots.facingOf(0f));
		assertEquals(0, s.facing());
		assertEquals(0, s.backX());
		assertEquals(-1, s.backZ());
		assertEquals(1, TrophySlots.facingOf(90f)); // west: support east
		assertEquals(1, new TrophySlots.Slot("t", 1, 1, 0, 0, 0, 1).backX());
		assertEquals(2, TrophySlots.facingOf(180f)); // north: support south
		assertEquals(1, new TrophySlots.Slot("t", 1, 1, 0, 0, 0, 2).backZ());
		assertEquals(3, TrophySlots.facingOf(-90f)); // east: support west
		assertEquals(3, TrophySlots.facingOf(270f));
		assertEquals(-1, new TrophySlots.Slot("t", 1, 1, 0, 0, 0, 3).backX());
	}

	@Test
	void rotatedSlotsKeepTheirSupportBehindThem() {
		// a template 9 x 7: sign cell (2, 1, 3) facing south, its support (2, 1, 2); every rotation keeps them together
		int sx = 9;
		int sz = 7;
		Anchor local = a("trophy@1", 2.5, 1.5, 3.5, 0);
		for (int turns = 0; turns < 4; turns++) {
			Anchor w = BlueprintTransform.toWorld(local, sx, sz, turns, 100, 64, 200);
			TrophySlots.Slot s = TrophySlots.all(Map.of(w.name(), w)).get(0);
			int[] sign = BlueprintTransform.rotateBlock(2, 3, sx, sz, turns);
			int[] support = BlueprintTransform.rotateBlock(2, 2, sx, sz, turns);
			assertEquals(100 + sign[0], s.x(), "turns " + turns);
			assertEquals(200 + sign[1], s.z(), "turns " + turns);
			assertEquals(65, s.y());
			assertEquals(100 + support[0], s.x() + s.backX(), "turns " + turns);
			assertEquals(200 + support[1], s.z() + s.backZ(), "turns " + turns);
		}
	}

	@Test
	void bundledBlueprintsGiveEveryWingSixSlotsInsideTheBoxAtEveryRotation() throws IOException {
		Path dir = Path.of("src/main/resources/data/agentcraft_worlds/blueprints");
		List<Path> files;
		try (var s = Files.list(dir)) {
			files = s.filter(p -> p.getFileName().toString().endsWith(".blueprint.json")).sorted().toList();
		}
		assertEquals(7, files.size()); // 6 buildings + the village board fixture (no wings: no slots, checked below)
		for (Path f : files) {
			Blueprint bp = Blueprint.fromJson(JsonParser.parseString(Files.readString(f)).getAsJsonObject());
			if (bp.isFixture()) {
				assertEquals(0, bp.wings(), bp.id());
				assertTrue(TrophySlots.cells(bp.anchors()).isEmpty(), bp.id() + ": a fixture has no trophy slots");
			}
			for (int turns = 0; turns < 4; turns++) {
				boolean odd = turns % 2 == 1;
				Anchors.Bounds box = new Anchors.Bounds(1000, 64, -500, 1000 + (odd ? bp.sizeZ() : bp.sizeX()) - 1, 64 + bp.sizeY() - 1,
					-500 + (odd ? bp.sizeX() : bp.sizeZ()) - 1);
				Map<String, Anchor> raw = BlueprintTransform.rawWorldAnchors(bp, turns, box.minX(), box.minY(), box.minZ());
				for (int w = 1; w <= bp.wings(); w++) {
					List<TrophySlots.Slot> slots = TrophySlots.forWing(raw, w, box);
					assertEquals(6, slots.size(), bp.id() + " wing " + w + " turns " + turns);
					for (TrophySlots.Slot s : slots) {
						assertTrue(box.contains(s.x() + s.backX(), s.y(), s.z() + s.backZ()), bp.id() + " " + s + ": support outside the box");
					}
				}
				assertEquals(6 * bp.wings(), TrophySlots.cells(raw).size(), bp.id() + ": one cell per slot");
			}
		}
	}

	static TrophyLedger.Entry e(String key, long at) {
		return new TrophyLedger.Entry(key, List.of(key, "", "", ""), at);
	}

	@Test
	void choiceFillsFreeSlotsFirstThenReplacesTheOldest() {
		List<TrophySlots.Slot> w1 = TrophySlots.forWing(pinAnchors(), 1, BOX);
		Map<String, TrophyLedger.Entry> taken = new LinkedHashMap<>();
		assertEquals("trophy@1", TrophySlots.choose(w1, taken).name());
		taken.put("trophy@1", e("a", 10));
		assertEquals("trophy_2@1", TrophySlots.choose(w1, taken).name());
		taken.put("trophy_3@1", e("c", 5)); // a gap: the lowest free slot still comes first
		assertEquals("trophy_2@1", TrophySlots.choose(w1, taken).name());
		taken.put("trophy_2@1", e("b", 20));
		// all full: oldest first, then the others by age (a blocked slot is skipped to the next)
		assertEquals(List.of("trophy_3@1", "trophy@1", "trophy_2@1"), TrophySlots.order(w1, taken).stream().map(TrophySlots.Slot::name).toList());
		taken.put("trophy_3@1", e("d", 30));
		assertEquals("trophy@1", TrophySlots.choose(w1, taken).name());
		assertNull(TrophySlots.choose(List.of(), taken));
	}

	@Test
	void sameMillisecondAwardsReplaceInAwardOrder() {
		List<TrophySlots.Slot> w1 = TrophySlots.forWing(pinAnchors(), 1, BOX);
		int n = w1.size();
		Map<String, TrophyLedger.Entry> taken = new LinkedHashMap<>();
		for (int i = 1; i <= 3 * n + 1; i++) { // all "at" the same millisecond
			TrophySlots.Slot s = TrophySlots.choose(w1, taken);
			taken.put(s.name(), e("k" + i, TrophySlots.nextAt(1000, taken)));
		}
		List<String> keys = taken.values().stream().map(TrophyLedger.Entry::key).sorted().toList();
		List<String> newest = new java.util.ArrayList<>();
		for (int i = 2 * n + 2; i <= 3 * n + 1; i++) {
			newest.add("k" + i);
		}
		assertEquals(newest.stream().sorted().toList(), keys, "the wall holds exactly the newest awards");
		assertEquals(1000, TrophySlots.nextAt(1000, Map.of()));
		assertEquals(5001, TrophySlots.nextAt(1000, Map.of("a", e("a", 5000))));
	}

	@Test
	void wingsDoNotShareSlots() {
		Map<String, TrophyLedger.Entry> taken = new LinkedHashMap<>();
		taken.put("trophy@1", e("a", 1));
		taken.put("trophy_2@1", e("b", 2));
		taken.put("trophy_3@1", e("c", 3));
		// wing 1 full does not spill into wing 2 and wing 2's entries never count for wing 1
		assertEquals("trophy@2", TrophySlots.choose(TrophySlots.forWing(pinAnchors(), 2, BOX), taken).name());
		taken.put("trophy@2", e("x", 0));
		assertEquals("trophy@1", TrophySlots.choose(TrophySlots.forWing(pinAnchors(), 1, BOX), taken).name());
	}

	// ------------------------------------------------------------------ removal blocker exemption

	@Test
	void onlySignsOnTrophyCellsAreExempt() {
		Set<Long> cells = TrophySlots.cells(pinAnchors());
		assertTrue(TrophySlots.exempt(cells, 10, 66, 2, true));
		assertTrue(TrophySlots.exempt(cells, 6, 66, 14, true));
		assertFalse(TrophySlots.exempt(cells, 10, 66, 2, false), "a chest the player put in a slot still blocks");
		assertFalse(TrophySlots.exempt(cells, 10, 67, 2, true), "a sign elsewhere still blocks");
		assertFalse(TrophySlots.exempt(cells, 3, 66, 3, true), "not a trophy anchor");
		assertFalse(TrophySlots.exempt(Set.of(), 10, 66, 2, true), "a building without slots exempts nothing");
		// negative coordinates pack apart
		assertFalse(TrophySlots.cell(-1, 64, 0) == TrophySlots.cell(1, 64, 0));
		assertFalse(TrophySlots.cell(0, -5, -1) == TrophySlots.cell(0, -5, 1));
	}

	// ------------------------------------------------------------------ ledger

	@Test
	void ledgerRoundTripAndIdempotentKeys(@TempDir Path dir) throws IOException {
		TrophyLedger l = new TrophyLedger();
		assertTrue(l.markAwarded("pr:r:1"));
		assertFalse(l.markAwarded("pr:r:1"));
		l.put("b1", "trophy@1", new TrophyLedger.Entry("pr:r:1", List.of("Merged PR #1", "x", "", "2026-10-04"), 100));
		l.put("b2", "trophy_2@2", new TrophyLedger.Entry("goal:g:5", List.of("Goal done", "y", "", "1 task · 2026-10-04"), 200));
		l.markAwarded("goal:g:5");
		Path f = dir.resolve(TrophyLedger.FILE);
		l.save(f);
		TrophyLedger back = TrophyLedger.read(f);
		assertEquals(l.toJson(), back.toJson());
		assertTrue(back.awarded("pr:r:1"));
		assertEquals(200, back.slots("b2").get("trophy_2@2").at());
		assertEquals("1 task · 2026-10-04", back.slots("b2").get("trophy_2@2").lines().get(3));
		// replacing a slot keeps the old key awarded; dropping a building keeps its keys
		back.put("b1", "trophy@1", e("pr:r:2", 300));
		back.markAwarded("pr:r:2");
		assertTrue(back.awarded("pr:r:1"));
		assertTrue(back.dropBuilding("b1"));
		assertFalse(back.dropBuilding("b1"));
		assertEquals(Map.of(), back.slots("b1"));
		assertTrue(back.awarded("pr:r:1") && back.awarded("pr:r:2"));
		back.clear("b2", "trophy_2@2");
		assertEquals(Set.of(), back.buildingIds());
		assertFalse(Files.exists(dir.resolve(TrophyLedger.FILE + ".tmp")));
	}

	@Test
	void ledgerToleratesMalformedInput(@TempDir Path dir) throws IOException {
		assertEquals(Set.of(), TrophyLedger.fromJson(null).awardedKeys());
		assertEquals(Set.of(), TrophyLedger.fromJson(JsonParser.parseString("[1,2]")).awardedKeys());
		TrophyLedger l = TrophyLedger.fromJson(JsonParser.parseString("""
			{"awarded": ["a", 3, null, "", {"x":1}, "b"],
			 "buildings": {
			   "b1": {"trophy@1": {"key": "c", "lines": ["one", 2], "at": "soon"},
			          "trophy_2@1": {"lines": ["no key"]},
			          "trophy_3@1": "nope",
			          "trophy_4@1": {"key": "d", "lines": ["1","2","3","4","5"], "at": 7}},
			   "b2": 5 }}"""));
		assertEquals(Set.of("a", "b", "c", "d"), l.awardedKeys(), "a hanging entry's key counts as awarded");
		assertEquals(Set.of("trophy@1", "trophy_4@1"), l.slots("b1").keySet());
		assertEquals(List.of("one", "", "", ""), l.slots("b1").get("trophy@1").lines());
		assertEquals(0, l.slots("b1").get("trophy@1").at());
		assertEquals(List.of("1", "2", "3", "4"), l.slots("b1").get("trophy_4@1").lines());
		// missing file = empty; not JSON = an error (the caller leaves the file alone)
		assertEquals(Set.of(), TrophyLedger.read(dir.resolve("none.json")).awardedKeys());
		Path bad = dir.resolve("bad.json");
		Files.writeString(bad, "{\"awarded\": [");
		assertThrows(IOException.class, () -> TrophyLedger.read(bad));
	}
}
