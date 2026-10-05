package dev.agentcraft.journal;

import net.minecraft.world.level.block.LeavesBlock;
import org.jspecify.annotations.Nullable;

/**
 * Drops the leaf ticks a restore schedules (docs/BUILDINGS.md "Leaf ring"; ported from Architect's {@code TickDeferral}).
 * Writing a box back with shape updates makes every leaf next to a changed cell schedule a tick, and that tick recomputes
 * its {@code distance}: world generation leaves many distances stale (trees generated over each other), so the canopy
 * around the box would relax to new distances right after Remove, which is not the terrain as it was. A leaf's scheduled
 * tick only recomputes its {@code distance} (decay is a random tick), so dropping it changes nothing else; every other
 * block and fluid tick is scheduled as usual, with its own delay ({@code mixin.LevelTicksMixin}). Server thread; nested
 * calls are fine.
 */
public final class LeafTicks {
	private static @Nullable Thread owner;
	private static int depth;
	private static long dropped;

	private LeafTicks() {
	}

	/** Runs {@code r} with the leaf ticks it schedules on this thread dropped. */
	public static void quietly(Runnable r) {
		Thread t = Thread.currentThread();
		if (owner != null && owner != t) {
			r.run(); // another thread is restoring (never: server thread only); its ticks are not ours to drop
			return;
		}
		owner = t;
		depth++;
		try {
			r.run();
		} finally {
			if (--depth == 0) {
				owner = null;
			}
		}
	}

	/** {@code LevelTicksMixin}: whether a tick of {@code type} scheduled now is dropped. */
	public static boolean drops(Object type) {
		if (depth == 0 || Thread.currentThread() != owner || !(type instanceof LeavesBlock)) {
			return false;
		}
		dropped++;
		return true;
	}

	/** How many leaf ticks restores dropped since the game started (DevBridge). */
	public static long dropped() {
		return dropped;
	}
}
