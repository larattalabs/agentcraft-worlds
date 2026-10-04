package dev.agentcraft.walk;

import java.util.Locale;
import java.util.function.LongPredicate;

/**
 * Walk or teleport (docs/WAVE2.md W8): an agent that changes building walks an outdoor route when walking is
 * on for this world, both buildings are in the player's dimension and have an entrance, their entrances are
 * at most {@link #MAX_DISTANCE} blocks apart, every chunk along the corridor is loaded on the client and the
 * player is within render distance of the corridor; otherwise it teleports with a puff as before. Pure.
 */
public final class WalkRules {
	/** Entrances farther apart than this (horizontal blocks): teleport. */
	public static final int MAX_DISTANCE = 256;
	/** Chunks either side of the straight line that must be loaded too. */
	public static final int CORRIDOR_MARGIN = 1;

	/** Why an agent walks or teleports (the dev state lists the last ones). */
	public enum Reason {
		WALK("walks"),
		DISABLED("walking is off for this world"),
		OTHER_DIMENSION("a building is not in the player's dimension"),
		NO_ENTRANCE("a building has no entrance anchor"),
		TOO_FAR("entrances more than 256 blocks apart"),
		UNLOADED("chunks on the way are not loaded"),
		PLAYER_FAR("the player is beyond render distance"),
		NO_PATH("no walkable outdoor route"),
		NO_DOOR_PATH("no way to or from a building's entrance inside"),
		BUDGET("route search budget used up"),
		BLOCKED("the route got blocked"),
		STUCK("the walk took far too long"),
		REROUTED("sent to another building mid-walk"),
		SETTLED("settled (dev)");

		public final String text;

		Reason(String text) {
			this.text = text;
		}

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		/** The teleport reason for a planner outcome. */
		public static Reason of(OutdoorPlanner.Status s) {
			return switch (s) {
				case FOUND, RUNNING -> WALK;
				case UNLOADED -> UNLOADED;
				case BUDGET -> BUDGET;
				case TOO_FAR -> TOO_FAR;
				case NO_START, NO_GOAL -> NO_DOOR_PATH;
				case NO_PATH -> NO_PATH;
			};
		}
	}

	/** What {@link #decide} needs to know (horizontal coordinates; {@code renderBlocks} = render distance in blocks). */
	public record Inputs(boolean enabled, boolean sameDimension, boolean entrances, double ax, double az, double bx, double bz, double playerX,
		double playerZ, double renderBlocks) {
	}

	private WalkRules() {
	}

	/** Walk or why not; {@code loaded} answers whether chunk (cx, cz) ({@link #chunk}) is loaded. */
	public static Reason decide(Inputs in, LongPredicate loaded) {
		if (!in.enabled()) {
			return Reason.DISABLED;
		}
		if (!in.sameDimension()) {
			return Reason.OTHER_DIMENSION;
		}
		if (!in.entrances()) {
			return Reason.NO_ENTRANCE;
		}
		if (Math.hypot(in.bx() - in.ax(), in.bz() - in.az()) > MAX_DISTANCE) {
			return Reason.TOO_FAR;
		}
		if (segmentDistance(in.playerX(), in.playerZ(), in.ax(), in.az(), in.bx(), in.bz()) > in.renderBlocks()) {
			return Reason.PLAYER_FAR;
		}
		for (long c : corridorChunks(in.ax(), in.az(), in.bx(), in.bz(), CORRIDOR_MARGIN)) {
			if (!loaded.test(c)) {
				return Reason.UNLOADED;
			}
		}
		return Reason.WALK;
	}

	public static long chunk(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	public static int chunkX(long c) {
		return (int) (c >> 32);
	}

	public static int chunkZ(long c) {
		return (int) c;
	}

	/** The chunks within {@code margin} chunks of the straight line a->b (each once, in order along the line). */
	public static long[] corridorChunks(double ax, double az, double bx, double bz, int margin) {
		java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
		double len = Math.hypot(bx - ax, bz - az);
		int samples = Math.max(1, (int) Math.ceil(len / 4));
		for (int s = 0; s <= samples; s++) {
			double t = (double) s / samples;
			int cx = Math.floorDiv((int) Math.floor(ax + (bx - ax) * t), 16);
			int cz = Math.floorDiv((int) Math.floor(az + (bz - az) * t), 16);
			for (int dx = -margin; dx <= margin; dx++) {
				for (int dz = -margin; dz <= margin; dz++) {
					out.add(chunk(cx + dx, cz + dz));
				}
			}
		}
		long[] r = new long[out.size()];
		int i = 0;
		for (long c : out) {
			r[i++] = c;
		}
		return r;
	}

	/** Horizontal distance from point p to the segment a-b. */
	public static double segmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
		double dx = bx - ax;
		double dz = bz - az;
		double len2 = dx * dx + dz * dz;
		double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len2));
		return Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
	}

	/**
	 * Ticks a walk of {@code length} blocks may take before it counts as stuck (and the agent teleports): three
	 * times the time at walking speed, plus 15 s for getting up and turning.
	 */
	public static int stuckTicks(double length, double blocksPerTick) {
		return (int) Math.ceil(length / Math.max(0.01, blocksPerTick) * 3) + 300;
	}
}
