package dev.agentcraft.building;

import dev.agentcraft.layout.Anchors;
import java.util.List;
import org.jspecify.annotations.Nullable;

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

	/** The share of the cells where a site's snapshot and its building differ that must show the snapshot for the restore to count as saved. */
	public static final double RESTORED = 0.9;

	/**
	 * Whether a taken-down site shows its saved terrain again: {@code match} of the {@code total} cells where the snapshot
	 * and the building differ hold the snapshot's block. Null when no such cell could be compared (the building's own
	 * blocks are unknown).
	 */
	public static @Nullable Boolean restored(int match, int total) {
		return total <= 0 ? null : match >= RESTORED * total;
	}

	/** What a pending site (removed / moved away) overlaps among the current records (same dimension, its own id included). */
	public enum Overlap {
		/** Nothing. */
		NONE,
		/**
		 * A current record that stands covers the whole site: that record's own snapshot holds the same terrain. Its own
		 * id included: a building moved away and back (Undo move) stands on its old site legitimately.
		 */
		COVERED,
		/** Anything else that touches it. */
		PARTIAL
	}

	/** A site for {@link #overlap}: a building's id, dimension, restore box and whether it stands (null: unknown). */
	public record Site(String id, String dimension, Anchors.Bounds box, @Nullable Boolean stands) {
	}

	/** What {@link #overlap} found, and the record it is about (null for NONE). */
	public record Found(Overlap overlap, @Nullable String by) {
	}

	/**
	 * How a pending site relates to the current records: COVERED when one that stands contains it whole (its own id
	 * included), else PARTIAL when any intersects it, else NONE. Pure.
	 */
	public static Found overlap(Site pending, List<Site> current) {
		Found found = new Found(Overlap.NONE, null);
		for (Site c : current) {
			if (!c.dimension().equals(pending.dimension()) || !Building.intersects(c.box(), pending.box())) {
				continue;
			}
			if (Boolean.TRUE.equals(c.stands()) && contains(c.box(), pending.box())) {
				return new Found(Overlap.COVERED, c.id());
			}
			if (found.overlap() == Overlap.NONE) {
				found = new Found(Overlap.PARTIAL, c.id());
			}
		}
		return found;
	}

	static boolean contains(Anchors.Bounds outer, Anchors.Bounds inner) {
		return outer.minX() <= inner.minX() && outer.minY() <= inner.minY() && outer.minZ() <= inner.minZ() && outer.maxX() >= inner.maxX()
			&& outer.maxY() >= inner.maxY() && outer.maxZ() >= inner.maxZ();
	}

	/** What the world-start check does with a pending site. */
	public enum Action {
		/** Its building record comes back (a removal or move that never reached the disk). */
		RECOVER,
		/** The site is settled: the snapshot is deleted and the entry dropped. */
		RELEASE,
		/** Something is wrong that the player should see (two copies, a copy under another building): reported, snapshot kept. */
		REPORT_KEEP,
		/** Undecided (cannot be checked, or not clearly restored): kept silently for the next start. */
		KEEP
	}

	/**
	 * The world-start decision for a site taken down before the game stopped (docs/BUILDINGS.md "Crash safety"). A
	 * snapshot is only deleted on positive evidence: the site shows it again, or a standing building covers it. Pure.
	 *
	 * @param moved a moved-away site (else removed)
	 * @param stands whether the taken-down building still stands there (null: cannot be checked)
	 * @param restored whether the site shows its snapshot again ({@link #restored}; null: cannot be checked)
	 * @param hasCurrent whether a record with the same id exists now
	 * @param currentStands for a move: whether that record stands at its new site (null: cannot be checked)
	 */
	public static Action decide(boolean moved, @Nullable Boolean stands, @Nullable Boolean restored, Overlap overlap, boolean hasCurrent,
		@Nullable Boolean currentStands) {
		if (Boolean.TRUE.equals(restored) || overlap == Overlap.COVERED) {
			return Action.RELEASE;
		}
		if (!Boolean.TRUE.equals(stands)) {
			return Action.KEEP;
		}
		if (overlap == Overlap.PARTIAL) {
			return Action.REPORT_KEEP; // never re-add a building over another one
		}
		if (!moved) {
			return hasCurrent ? Action.REPORT_KEEP : Action.RECOVER;
		}
		return hasCurrent && Boolean.FALSE.equals(currentStands) ? Action.RECOVER : Action.REPORT_KEEP;
	}

	/** The hub's report for a building whose template does not stand. */
	public static String mismatch(String id, int match, int total) {
		int pct = total == 0 ? 0 : (int) Math.round(100.0 * match / total);
		return id + " does not match its blueprint (" + pct + " % of its blocks in place): the world may not have saved it before the game "
			+ "stopped, or it was taken apart. Remove puts back the terrain saved before it was placed; Forget only drops the record";
	}
}
