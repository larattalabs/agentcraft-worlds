package dev.agentcraft.building;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.routine.BedPicker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Beds where they would hurt the player (docs/BUILDINGS.md "Beds"). A bundled blueprint brings vanilla beds; in a
 * dimension whose bed rule does not allow sleeping (the Nether and the End: the bed explodes with power 5 and fire when
 * used) a placement leaves them out: both halves become air, their {@code bed*} anchors are dropped (agents rest in the
 * lounge there) and the building's pin forgets those cells (a bed the player puts there later is the player's). Pure
 * logic; {@code Buildings.build} reads the rule from the level and writes the air. Any thread.
 */
public final class BedSafety {
	/**
	 * Whether a bed with this rule must not be placed: it explodes on use, breaks when the sleeper leaves, or nobody can
	 * ever sleep in it (vanilla {@code BedRule}: {@code can_sleep}, {@code destroy_on_use}, {@code destroy_on_leave}).
	 */
	public static boolean unsafe(boolean neverSleep, boolean destroyOnUse, boolean destroyOnLeave) {
		return neverSleep || destroyOnUse || destroyOnLeave;
	}

	/** The head half's cell of a bed half at {@code x,y,z}: itself for the head, else one step towards {@code facing} (dx, dz). */
	public static BlockPos head(int x, int y, int z, boolean isHead, int facingDx, int facingDz) {
		return isHead ? new BlockPos(x, y, z) : new BlockPos(x + facingDx, y, z + facingDz);
	}

	/** The cell an anchor stands on (its feet cell: a bed anchor is on the head half, feet row). */
	public static long cellOf(Anchor a) {
		return BlockPos.asLong((int) Math.floor(a.x()), (int) Math.floor(a.y() + 0.01), (int) Math.floor(a.z()));
	}

	/** {@code anchors} without the bed anchors whose head cell (a {@link BlockPos#asLong}) is in {@code removedHeads}. */
	public static Map<String, Anchor> withoutBeds(Map<String, Anchor> anchors, Set<Long> removedHeads) {
		if (removedHeads.isEmpty()) {
			return anchors;
		}
		Map<String, Anchor> out = new LinkedHashMap<>();
		anchors.forEach((n, a) -> {
			if (!(BedPicker.isBedAnchor(n) && removedHeads.contains(cellOf(a)))) {
				out.put(n, a);
			}
		});
		return out;
	}

	/** Block-entity offsets (x,y,z triples from {@code min}) without the cells in {@code removed} (world {@link BlockPos#asLong}). */
	public static List<Integer> withoutCells(List<Integer> offsets, Set<Long> removed, int minX, int minY, int minZ) {
		if (removed.isEmpty()) {
			return offsets;
		}
		List<Integer> out = new ArrayList<>(offsets.size());
		for (int i = 0; i + 2 < offsets.size(); i += 3) {
			if (!removed.contains(BlockPos.asLong(minX + offsets.get(i), minY + offsets.get(i + 1), minZ + offsets.get(i + 2)))) {
				out.add(offsets.get(i));
				out.add(offsets.get(i + 1));
				out.add(offsets.get(i + 2));
			}
		}
		return out;
	}

	/** A pin without the removed beds: their anchors (so a repo change does not bring them back) and their block-entity cells. */
	public static Building.Pin strip(Building.Pin pin, Set<Long> removedCells, Set<Long> removedHeads, Anchors.Bounds box) {
		if (removedCells.isEmpty()) {
			return pin;
		}
		return new Building.Pin(pin.template(), pin.wings(), pin.group(), withoutBeds(pin.wingAnchors(), removedHeads),
			withoutCells(pin.blockEntities(), removedCells, box.minX(), box.minY(), box.minZ()));
	}

	/** What the placement note says, or null when no bed was left out. */
	public static @Nullable String note(int beds, String dimension) {
		return beds == 0 ? null : beds + " bed" + (beds == 1 ? "" : "s") + " left out (beds explode in " + dimension + "; agents rest in the lounge)";
	}

	private BedSafety() {
	}
}
