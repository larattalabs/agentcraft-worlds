package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;

class LogJoinTest {
	record E(long ts, String text) {
	}

	static final ToLongFunction<E> TS = E::ts;

	static List<E> range(long from, long to) {
		List<E> out = new ArrayList<>();
		for (long t = from; t <= to; t++) {
			out.add(new E(t, "e" + t));
		}
		return out;
	}

	@Test
	void joinsWithoutDuplicates() {
		List<E> fetched = range(1, 10);
		List<E> live = range(5, 14);
		List<E> all = LogJoin.join(fetched, live, TS);
		assertEquals(range(1, 14), all);
		assertFalse(LogJoin.gap(fetched, live, TS));
		assertEquals(range(3, 4), LogJoin.join(List.of(), range(3, 4), TS));
	}

	@Test
	void equalTimestampsAtTheSeamAreKeptOnce() {
		List<E> fetched = new ArrayList<>(range(1, 3));
		fetched.add(new E(3, "e3b"));
		List<E> live = List.of(new E(3, "e3"), new E(3, "e3b"), new E(3, "e3c"), new E(4, "e4"));
		List<E> all = LogJoin.join(fetched, live, TS);
		assertEquals(List.of("e1", "e2", "e3", "e3b", "e3c", "e4"), all.stream().map(E::text).toList());
	}

	@Test
	void aTailThatMovedPastTheFetchedPagesIsAGapAndAFillClosesIt() {
		List<E> fetched = new ArrayList<>(range(1, 200));
		List<E> live = range(450, 649); // 250 arrived beyond the 200 the tail keeps
		assertTrue(LogJoin.gap(fetched, live, TS));
		assertEquals(451, LogJoin.gapBefore(live, TS));
		// the Foreman's page before 451 (its newest 200): 251..450 does not reach back to 200, so it is not added (no hole)
		assertFalse(LogJoin.reaches(fetched, range(251, 450), TS));
		assertEquals(-1, LogJoin.fill(fetched, range(251, 450), TS));
		assertEquals(range(1, 200), fetched);
		// with the page before it (51..250) collected in front, it reaches and closes the gap
		List<E> pages = new ArrayList<>(range(51, 250));
		pages.addAll(range(251, 450));
		assertEquals(250, LogJoin.fill(fetched, pages, TS));
		assertEquals(range(1, 450), fetched);
		assertFalse(LogJoin.gap(fetched, live, TS), "the pages took the tail's first entry, so the seam overlaps");
		assertEquals(range(1, 649), LogJoin.join(fetched, live, TS));
		// nothing in between (an empty page) reaches too
		assertEquals(0, LogJoin.fill(new ArrayList<>(range(1, 5)), List.of(), TS));
	}

	@Test
	void dropsTheFetchedEntriesTheTailHolds() {
		List<E> fetched = range(1, 300);
		List<E> live = range(201, 400);
		assertEquals(201, LogJoin.keepBeforeLive(fetched, live, TS));
		List<E> kept = new ArrayList<>(fetched.subList(0, 201));
		assertEquals(range(1, 400), LogJoin.join(kept, live, TS));
		// a gap: nothing is dropped (the tail does not cover what is missing)
		assertEquals(300, LogJoin.keepBeforeLive(fetched, range(350, 400), TS));
	}

	@Test
	void gapFillsStayUnderTheCapByDroppingTheOldest() {
		List<E> fetched = new ArrayList<>(range(1, LogJoin.MAX_FETCHED - 50));
		assertEquals(0, LogJoin.trimOldest(fetched));
		LogJoin.fill(fetched, range(LogJoin.MAX_FETCHED - 50, LogJoin.MAX_FETCHED + 150), TS);
		assertEquals(150, LogJoin.trimOldest(fetched));
		assertEquals(LogJoin.MAX_FETCHED, fetched.size());
		assertEquals(151, fetched.get(0).ts());
		assertEquals(LogJoin.MAX_FETCHED + 150, fetched.get(fetched.size() - 1).ts());
	}
}
