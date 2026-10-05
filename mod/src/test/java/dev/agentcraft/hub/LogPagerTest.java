package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.hub.LogPager.Page;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The agent log's paging ({@code agent.logs.request} pages with {@code more}), as the Inbox's agent view drives it. */
class LogPagerTest {
	record E(long ts) {
	}

	/** A fake Foreman log of {@code n} entries (ts 1..n): pages of {@code limit} before {@code before}, oldest first, with more. */
	static Page<E> page(int n, Long before, int limit) {
		long end = before == null ? n + 1 : before;
		long start = Math.max(1, end - limit);
		List<E> es = new ArrayList<>();
		for (long t = start; t < end; t++) {
			es.add(new E(t));
		}
		return new Page<>(es, start > 1);
	}

	@Test
	void morePagesBackToTheOldestAndStops() {
		LogPager<E> p = new LogPager<>(E::ts);
		assertNull(p.olderBefore(false, List.of()), "nothing fetched yet");
		p.firstPage(page(450, null, 200)); // the ack said more:true
		assertTrue(p.more());
		assertEquals(251, p.fetched().get(0).ts());
		assertEquals(0, p.takePrepended());
		int v = p.version();
		assertNull(p.olderBefore(true, List.of()), "no second request while one runs");
		Long before = p.olderBefore(false, List.of());
		assertEquals(251L, before);
		p.olderPage(page(450, before, 200));
		assertEquals(51, p.fetched().get(0).ts());
		assertEquals(200, p.takePrepended());
		assertEquals(0, p.takePrepended(), "consumed once");
		assertTrue(p.version() > v);
		assertTrue(p.more());
		before = p.olderBefore(false, List.of());
		assertEquals(51L, before);
		p.olderPage(page(450, before, 200)); // the last page: more:false
		assertFalse(p.more());
		assertEquals(1, p.fetched().get(0).ts());
		assertEquals(450, p.fetched().size());
		assertNull(p.olderBefore(false, List.of()), "nothing older stored: no request");
		assertEquals(3, p.pages());
		assertFalse(p.full());
	}

	@Test
	void theCapDropsWhatTheLiveTailShowsThenStops() {
		LogPager<E> p = new LogPager<>(E::ts);
		List<E> first = new ArrayList<>();
		for (long t = 5001; t <= 5000 + LogJoin.MAX_FETCHED; t++) {
			first.add(new E(t));
		}
		p.firstPage(new Page<>(first, true));
		// the live tail holds the newest 100: those go first, then an older page may still be asked for
		List<E> live = first.subList(first.size() - 100, first.size());
		assertEquals(5001L, p.olderBefore(false, live));
		assertEquals(LogJoin.MAX_FETCHED - 99, p.fetched().size()); // the seam (the tail's first entry) stays
		assertFalse(p.full());
		// a full view the tail does not overlap: no request, full
		LogPager<E> q = new LogPager<>(E::ts);
		q.firstPage(new Page<>(first, true));
		assertNull(q.olderBefore(false, List.of(new E(99_999))));
		assertTrue(q.full());
	}

	@Test
	void aNewFirstPageStartsOver() {
		LogPager<E> p = new LogPager<>(E::ts);
		p.firstPage(page(300, null, 200));
		p.olderPage(page(300, 101L, 200));
		p.firstPage(page(10, null, 200));
		assertEquals(10, p.fetched().size());
		assertFalse(p.more());
		assertEquals(0, p.takePrepended());
	}
}
