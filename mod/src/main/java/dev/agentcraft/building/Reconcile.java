package dev.agentcraft.building;

/**
 * Pure rules of the buildings check at world start ({@link Buildings#reconcile}): whether a building's template
 * still stands in the world, and what to say when it does not. Compared by block (not state) over the template's
 * non-air blocks; a building counts as standing while {@link #STANDING} of them match, so a player's own changes
 * (a window knocked out, a torch added) never flag it.
 */
public final class Reconcile {
	/** The share of a template's blocks that must match for the building to count as standing. */
	public static final double STANDING = 0.8;

	private Reconcile() {
	}

	/** Whether {@code match} of {@code total} template blocks in place means the building stands. An empty template never "stands". */
	public static boolean stands(int match, int total) {
		return total > 0 && match >= STANDING * total;
	}

	/** The hub's report for a building whose template does not stand. */
	public static String mismatch(String id, int match, int total) {
		int pct = total == 0 ? 0 : (int) Math.round(100.0 * match / total);
		return id + " does not match its blueprint (" + pct + " % of its blocks in place): the world may not have saved it before the game "
			+ "stopped, or it was taken apart. Remove puts back the terrain saved before it was placed; Forget only drops the record";
	}
}
