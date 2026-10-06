package dev.agentcraft.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.agentcraft.journal.Journal.Cell;
import dev.agentcraft.journal.Journal.Entry;
import dev.agentcraft.journal.Journal.Policy;
import dev.agentcraft.journal.Journal.Status;
import dev.agentcraft.journal.Journal.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Undo planning on large entries stays linear (Architect saw {@code Map.copyOf} on packed block positions take minutes
 * for a 589k-cell undo): sites of 50k, 200k and 600k cells at world coordinates, alone, under a second site that covers
 * half of them (every covered cell a hand-down) and undone together with it, then the undone entry encoded, decoded and
 * reactivated. Timings go to {@code build/journal-scale.txt}.
 */
class JournalScaleTest {
	static final Value GRASS = Value.of("minecraft:grass_block", "snowy", "false");
	static final Value STONE = Value.of("minecraft:stone_bricks");
	static final Value PLANKS = Value.of("minecraft:spruce_planks");

	/** A BOX entry over {@code sx * sy * sz} cells from {@code (x0, y0, z0)}. */
	static Entry box(String id, int x0, int y0, int z0, int sx, int sy, int sz, long layer, Value before, Value after) {
		List<Cell> cells = new ArrayList<>(sx * sy * sz);
		for (int y = y0; y < y0 + sy; y++) {
			for (int z = z0; z < z0 + sz; z++) {
				for (int x = x0; x < x0 + sx; x++) {
					cells.add(new Cell(Journal.pos(x, y, z), layer, before, after));
				}
			}
		}
		return new Entry(id, "building", id, "minecraft:overworld", Policy.BOX, layer, Status.ACTIVE, cells, null, null);
	}

	static void log(String line) {
		try {
			Path f = Path.of("build", "journal-scale.txt");
			Files.createDirectories(f.getParent());
			Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static long ms(long t0) {
		return (System.nanoTime() - t0) / 1_000_000;
	}

	void run(int x0, int y0, int z0, int sx, int sy, int sz) {
		int cells = sx * sy * sz;
		Entry a = box("a", x0, y0, z0, sx, sy, sz, 1, GRASS, STONE);
		long t0 = System.nanoTime();
		Journal.UndoPlan alone = Journal.planUndo(List.of(a), List.of("a"), "a", 1L, (p, v) -> true, Journal.Match.EQUAL);
		long plan1 = ms(t0);
		assertEquals(cells, alone.writes().size());
		// b covers the upper half of a: every cell there is a hand-down when a goes first
		Entry b = box("b", x0, y0 + sy / 2, z0, sx, sy - sy / 2, sz, 2, STONE, PLANKS);
		t0 = System.nanoTime();
		Journal.UndoPlan under = Journal.planUndo(List.of(a, b), List.of("a"), "a", 1L, (p, v) -> true, Journal.Match.EQUAL);
		long plan2 = ms(t0);
		int covered = b.cells().size();
		assertEquals(covered, under.stats().get("a").covered());
		assertEquals(covered, under.updated().get("a").undo().handed().size());
		// both together, as one group (a site and what lies over it)
		t0 = System.nanoTime();
		Journal.UndoPlan group = Journal.planUndo(List.of(a, b), List.of("a", "b"), "g", 1L, (p, v) -> true, Journal.Match.EQUAL);
		long plan3 = ms(t0);
		assertEquals(cells, group.writes().size());
		t0 = System.nanoTime();
		var tag = JournalNbt.encode(under.updated().get("a"));
		long enc = ms(t0);
		t0 = System.nanoTime();
		Entry back = JournalNbt.decode(tag);
		long dec = ms(t0);
		assertEquals(cells - covered, back.undo().written().size());
		t0 = System.nanoTime();
		Map<String, Entry> re = Journal.reactivate(List.of(back, under.updated().get("b")), "a");
		long react = ms(t0);
		assertEquals(Status.ACTIVE, re.get("a").status());
		assertEquals(STONE, re.get("b").cells().get(0).before(), "the hand-downs are reversed");
		log(String.format("%,d cells (%dx%dx%d at %d,%d,%d), %,d covered: planUndo alone %d ms, under b %d ms, a+b group %d ms, encode %d ms, "
			+ "decode %d ms, reactivate %d ms", cells, sx, sy, sz, x0, y0, z0, covered, plan1, plan2, plan3, enc, dec, react));
	}

	// x = 9000 / -20000: positions whose packed longs Map.copyOf's open addressing clusters badly; x = -1234: it does not

	@Test
	void fiftyThousandCells() {
		assertTimeoutPreemptively(Duration.ofSeconds(60), () -> run(-1234, 58, 870, 60, 14, 60));
		assertTimeoutPreemptively(Duration.ofSeconds(60), () -> run(9000, 62, -7000, 60, 14, 60));
	}

	@Test
	void twoHundredThousandCells() {
		assertTimeoutPreemptively(Duration.ofSeconds(60), () -> run(-1234, 58, 870, 120, 14, 120));
		assertTimeoutPreemptively(Duration.ofSeconds(60), () -> run(-20000, 62, 870, 120, 14, 120));
	}

	@Test
	void sixHundredThousandCells() {
		// Architect's case was 589k cells: quadratic paths take tens of seconds to minutes here, linear ones about a second
		assertTimeoutPreemptively(Duration.ofSeconds(60), () -> run(-20000, 62, 870, 200, 15, 200));
	}
}
