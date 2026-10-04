package dev.agentcraft.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression (audit "Crash safety"): a failing client tick handler is logged once and the game keeps running. */
class GuardTest {
	private final List<String> logged = new ArrayList<>();

	@BeforeEach
	void reset() {
		Guard.resetForTest((kind, t) -> logged.add(kind + ": " + t.getMessage()));
	}

	@Test
	void failuresAreSwallowedLoggedOncePerKindAndCounted() {
		for (int i = 0; i < 3; i++) {
			Guard.run("agents.tick", () -> {
				throw new IllegalStateException("boom");
			});
		}
		Guard.run("hq.tick", () -> {
			throw new NoSuchMethodError("other mod");
		});
		int[] ran = {0};
		Guard.run("agents.tick", () -> ran[0]++);
		assertEquals(1, ran[0], "the handler runs again next tick");
		assertEquals(List.of("agents.tick: boom", "hq.tick: other mod"), logged);
		assertEquals(3, Guard.counts().get("agents.tick"));
		assertEquals(1, Guard.counts().get("hq.tick"));
	}

	@Test
	void callReturnsTheFallback() {
		assertEquals(7, Guard.call("x", () -> 7, -1));
		assertEquals(-1, Guard.call("x", () -> {
			throw new RuntimeException("no");
		}, -1));
	}

	@Test
	void outOfMemoryIsNotSwallowed() {
		assertThrows(OutOfMemoryError.class, () -> Guard.run("x", () -> {
			throw new OutOfMemoryError("oom");
		}));
	}

	@Test
	void injectedFaultsAreCaughtOnce() {
		Guard.inject("wizard.tick");
		int[] ran = {0};
		Guard.run("wizard.tick", () -> ran[0]++);
		Guard.run("wizard.tick", () -> ran[0]++);
		assertEquals(1, ran[0], "the injected run throws instead of running, the next one runs");
		assertEquals(1, Guard.counts().get("wizard.tick"));
	}
}
