package dev.agentcraft.building;

import com.google.gson.JsonObject;
import dev.agentcraft.journal.LeafTicks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * What the last placement cost the server thread (DevBridge {@code dev.buildings.timing}, QA): the time spent in
 * {@link Buildings#place} / {@link Buildings#move}, the part of it that read the leaf ring ({@link LeafGuard#ring}), and the
 * interval between the start of the server tick before it and the next one (a command runs between ticks, so this is the
 * stall a player sees). Server thread writes, any thread reads.
 */
public final class PlaceTiming {
	private static volatile long placeNanos;
	private static volatile long ringNanos;
	private static volatile int ringCells;
	private static volatile long stallNanos;
	private static volatile long maxIntervalNanos;
	private static volatile int placements;
	private static long tickStart;
	private static boolean placed;

	private PlaceTiming() {
	}

	static void init() {
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			long now = System.nanoTime();
			if (tickStart != 0) {
				long interval = now - tickStart;
				maxIntervalNanos = Math.max(maxIntervalNanos, interval);
				if (placed) {
					stallNanos = interval;
					placed = false;
				}
			}
			tickStart = now;
		});
	}

	static void placed(long nanos) {
		placeNanos = nanos;
		placements++;
		placed = true;
	}

	static void ring(long nanos, int cells) {
		ringNanos = nanos;
		ringCells = cells;
	}

	/** The numbers as JSON; {@code reset}: start the longest-interval count again. */
	public static JsonObject json(boolean reset) {
		JsonObject o = new JsonObject();
		o.addProperty("placements", placements);
		o.addProperty("placeMs", placeNanos / 1e6);
		o.addProperty("ringMs", ringNanos / 1e6);
		o.addProperty("ringCells", ringCells);
		o.addProperty("tickIntervalWithPlaceMs", stallNanos / 1e6);
		o.addProperty("maxTickIntervalMs", maxIntervalNanos / 1e6);
		o.addProperty("leafTicksDropped", LeafTicks.dropped());
		if (reset) {
			maxIntervalNanos = 0;
		}
		return o;
	}
}
