package dev.agentcraft.ui;

import dev.agentcraft.AgentCraft;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Crash guard for client tick and render handlers (docs/AUDIT-2026-10-03.md "Crash safety"). An exception
 * thrown out of an {@code END_CLIENT_TICK} or level-render callback takes down the client and, in
 * singleplayer, the integrated server with it (a Hardcore world would be left wherever it was). Each
 * handler runs through {@link #run}: a failure is logged once per kind (with its stack trace), counted,
 * and the game keeps running; the handler simply runs again next tick.
 *
 * <p>{@link VirtualMachineError}s (out of memory, stack overflow is not one of them) are rethrown: the
 * game cannot meaningfully continue after those. QA: {@link #inject} makes the next run of a kind throw
 * (DevBridge {@code dev.guard.inject}), {@link #counts} reports failures per kind ({@code dev.state}).
 */
public final class Guard {
	private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();
	private static final Set<String> INJECT = ConcurrentHashMap.newKeySet();
	private static volatile BiConsumer<String, Throwable> logger = (kind, t) -> AgentCraft.LOGGER.error(
		"AgentCraft: the {} handler failed; the game keeps running (logged once per kind, counted after that)", kind, t);

	private Guard() {
	}

	/** Runs {@code r}; any failure but a {@link VirtualMachineError} is logged (first time per kind) and swallowed. */
	public static void run(String kind, Runnable r) {
		try {
			if (!INJECT.isEmpty() && INJECT.remove(kind)) {
				throw new IllegalStateException("injected fault for '" + kind + "' (dev.guard.inject)");
			}
			r.run();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			report(kind, t);
		}
	}

	/** Like {@link #run}, returning {@code fallback} when the call fails. */
	public static <T> T call(String kind, Supplier<T> s, T fallback) {
		try {
			if (!INJECT.isEmpty() && INJECT.remove(kind)) {
				throw new IllegalStateException("injected fault for '" + kind + "' (dev.guard.inject)");
			}
			return s.get();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			report(kind, t);
			return fallback;
		}
	}

	/** Counts a failure; true (and logged) only the first time for {@code kind}. */
	static boolean report(String kind, Throwable t) {
		int n = COUNTS.merge(kind, 1, Integer::sum);
		if (n == 1) {
			try {
				logger.accept(kind, t);
			} catch (RuntimeException ignored) {
				// a broken logger must not undo the guard
			}
			return true;
		}
		return false;
	}

	/** Failures so far per kind (sorted by kind). */
	public static Map<String, Integer> counts() {
		return new TreeMap<>(COUNTS);
	}

	/** QA: the next run of {@code kind} throws (caught and reported like a real failure). */
	public static void inject(String kind) {
		INJECT.add(kind);
	}

	/** Tests: replace the logger, clear counts and pending injections. */
	static void resetForTest(BiConsumer<String, Throwable> log) {
		COUNTS.clear();
		INJECT.clear();
		logger = log;
	}
}
