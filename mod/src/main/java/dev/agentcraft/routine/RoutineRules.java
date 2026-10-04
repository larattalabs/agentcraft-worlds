package dev.agentcraft.routine;

import dev.agentcraft.layout.AnchorNames;
import org.jspecify.annotations.Nullable;

/**
 * Who does which village routine (docs/VILLAGE.md V3), pure. Priorities, highest first:
 * <ol>
 *   <li>an agent that waits on the player (asking, or owning an open decision): no routine (it comes to you);</li>
 *   <li>an agent walking between buildings: no routine until it arrived;</li>
 *   <li>a stand-up it takes part in (the goal's lead and the workers of its first tasks): {@link Kind#STANDUP};</li>
 *   <li>a library visit after its own memory note, only between steps (not working, thinking or in an error):
 *       {@link Kind#LIBRARY};</li>
 *   <li>at night (world time 13000-23000), an idle agent (no task, not working) or one off shift: {@link Kind#REST};</li>
 *   <li>otherwise its normal station ({@link Kind#NONE}).</li>
 * </ol>
 * While the Foreman link is stale nothing starts or ends: the previous routine is kept.
 */
public final class RoutineRules {
	/** Night: world time (ticks into the day) in [{@value #NIGHT_START}, {@value #NIGHT_END}). */
	public static final int NIGHT_START = 13_000;
	public static final int NIGHT_END = 23_000;
	public static final int DAY = 24_000;

	public enum Kind {
		NONE("work"), REST("resting"), STANDUP("stand-up"), LIBRARY("library");

		/** What the agent is doing, for the DevBridge (and REST's nameplate line "resting"). */
		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/**
	 * What decides an agent's routine.
	 *
	 * @param waitsOnUser asking the player or owning an open decision (it walks to the player / the podium)
	 * @param onTrip walking (or about to walk) to another building
	 * @param hasTask the Foreman gave it a task
	 * @param busy working, thinking or in an error (mid-step)
	 * @param onShift active; an agent off shift counts as idle whatever its task
	 */
	public record Facts(boolean waitsOnUser, boolean onTrip, boolean hasTask, boolean busy, boolean onShift) {
		/** No task and not mid-step, or off shift: it may rest. */
		public boolean idle() {
			return !onShift || !hasTask && !busy;
		}

		/** Between steps: not mid-step (a task may be assigned); off shift always. */
		public boolean betweenSteps() {
			return !onShift || !busy;
		}
	}

	/**
	 * @param inStandup a running stand-up lists this agent (and stand-ups are on)
	 * @param libraryDue a library visit is running or waiting for this agent (and visits are on)
	 * @param restAllowed it is night and the night routine is on
	 */
	public static Kind decide(Facts f, boolean stale, Kind previous, boolean inStandup, boolean libraryDue, boolean restAllowed) {
		if (stale) {
			return previous;
		}
		if (f.waitsOnUser() || f.onTrip()) {
			return Kind.NONE;
		}
		if (inStandup) {
			return Kind.STANDUP;
		}
		if (libraryDue && f.betweenSteps()) {
			return Kind.LIBRARY;
		}
		if (restAllowed && f.idle()) {
			return Kind.REST;
		}
		return Kind.NONE;
	}

	/** Ticks into the day for a world clock reading (any long, also negative). */
	public static int timeOfDay(long clock) {
		return (int) Math.floorMod(clock, (long) DAY);
	}

	/** Night for the night routine: 13000 (inclusive) to 23000 (exclusive) ticks into the day. */
	public static boolean isNight(long clock) {
		int t = timeOfDay(clock);
		return t >= NIGHT_START && t < NIGHT_END;
	}

	/** Ticks from {@code clock} until the routine switches (night starts or ends). */
	public static int ticksToSwitch(long clock) {
		int t = timeOfDay(clock);
		if (t < NIGHT_START) {
			return NIGHT_START - t;
		}
		if (t < NIGHT_END) {
			return NIGHT_END - t;
		}
		return DAY - t + NIGHT_START;
	}

	/**
	 * The station an agent with routine {@code k} uses instead of its own: the lounge to rest (a bed, when it gets one, is
	 * walked to directly), the library, the stand-up's gathering station ({@code standupStation}: meeting or user); null =
	 * its own station (no routine, or a stand-up that is not running for it).
	 */
	public static @Nullable String stationKey(Kind k, @Nullable String standupStation) {
		return switch (k) {
			case REST -> AnchorNames.LOUNGE;
			case LIBRARY -> AnchorNames.LIBRARY;
			case STANDUP -> standupStation;
			case NONE -> null;
		};
	}

	private RoutineRules() {
	}
}
