package dev.agentcraft.routine;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which resting agent sleeps in which bed of a building, pure. An agent keeps the bed it has while that bed is still
 * free (not occupied by the player, not taken by an agent earlier in the order); the others take the free bed nearest
 * their normal spot (their desk or lounge seat: campus agents sleep in their own wing), ties by bed order. Agents left
 * without a bed rest on the lounge seats (absent from the result).
 */
public final class BedPicker {
	/** A bed: its anchor name, the head half's cell centre, and whether someone (the player) sleeps in it. */
	public record Bed(String name, double x, double y, double z, boolean occupied) {
	}

	/** Where an agent would be without the routine (its normal spot), for the nearest-bed pick; null = anywhere. */
	public record Want(String agentId, double @Nullable [] near) {
	}

	/**
	 * @param wants resting agents in priority order (Foreman order: deterministic)
	 * @param beds the building's beds in anchor order
	 * @param previous agent id -> the bed name it had (sticky)
	 * @return agent id -> bed name, for the agents that get one
	 */
	public static Map<String, String> assign(List<Want> wants, List<Bed> beds, Map<String, String> previous) {
		Map<String, String> out = new LinkedHashMap<>();
		Set<String> taken = new HashSet<>();
		for (Bed b : beds) {
			if (b.occupied()) {
				taken.add(b.name());
			}
		}
		// sticky first, in order
		for (Want w : wants) {
			String prev = previous.get(w.agentId());
			if (prev != null && !taken.contains(prev) && beds.stream().anyMatch(b -> b.name().equals(prev))) {
				out.put(w.agentId(), prev);
				taken.add(prev);
			}
		}
		for (Want w : wants) {
			if (out.containsKey(w.agentId())) {
				continue;
			}
			Bed best = null;
			double bestD = Double.MAX_VALUE;
			for (Bed b : beds) {
				if (taken.contains(b.name())) {
					continue;
				}
				double d = w.near() == null ? 0 : dist2(w.near(), b);
				if (d < bestD - 1e-9) {
					best = b;
					bestD = d;
				}
			}
			if (best == null) {
				continue; // no free bed: the lounge
			}
			out.put(w.agentId(), best.name());
			taken.add(best.name());
		}
		return out;
	}

	private static double dist2(double[] p, Bed b) {
		double dx = p[0] - b.x();
		double dy = p.length > 1 ? p[1] - b.y() : 0;
		double dz = p.length > 2 ? p[2] - b.z() : 0;
		return dx * dx + dy * dy + dz * dz;
	}

	/** True for a bed anchor name: {@code bed}, {@code bed_2}.., optionally with a placed wing suffix {@code :<repo>}. */
	public static boolean isBedAnchor(String name) {
		return name.matches("bed(_\\d+)?(:.+)?");
	}

	private BedPicker() {
	}
}
