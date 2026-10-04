package dev.agentcraft.walk;

/**
 * Block codes ({@link WalkCell}) of a level, read by {@link OutdoorPlanner}. The client implementation reads
 * the client level on the client thread (the planner runs there, a budget per tick); tests use grids.
 */
@FunctionalInterface
public interface Terrain {
	/** The {@link WalkCell} code of block (x,y,z). */
	int at(int x, int y, int z);
}
