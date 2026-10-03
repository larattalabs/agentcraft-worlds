package dev.agentcraft.building;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Pure rules for "a lead per building" (docs/PRWATCH.md "A lead per building", docs/BUILDINGS.md
 * "Client (routing)"): the Foreman's building key, which building a lead works in, which podium shows a
 * decision, which lead a repo has, and what to tell the Foreman when the world's buildings change. No
 * world, client or Foreman state: callers pass the assignments ({@code leads.update}) and the buildings,
 * so this is unit-tested on its own.
 *
 * <p>A building key is {@code "<worldId>/<buildingId>"}, worldId = the save folder name, so two worlds
 * on one Foreman never collide. Marlow leads home, repos without a building and anything not tied to a
 * repo; he is never assigned a building.
 */
public final class LeadRouting {
	/** The home lead (never assigned a building). */
	public static final String MARLOW = "marlow";

	/** One lead assignment as the Foreman publishes it; {@code building} absent for Marlow. */
	public record Lead(String leadId, @Nullable String building, List<String> repos) {
		public Lead {
			repos = repos == null ? List.of() : List.copyOf(repos);
		}
	}

	/** A parsed building key. */
	public record Key(String worldId, String buildingId) {
	}

	/** What to send after the world's buildings changed: {@code lead.assign} per assign id, {@code lead.release} per release id. */
	public record Diff(List<String> assign, List<String> release) {
		public boolean isEmpty() {
			return assign.isEmpty() && release.isEmpty();
		}
	}

	private LeadRouting() {
	}

	// ------------------------------------------------------------------ keys

	/** {@code "<worldId>/<buildingId>"}. */
	public static String key(String worldId, String buildingId) {
		return worldId + "/" + buildingId;
	}

	/** Parses a building key (split at the last {@code /}; building ids never contain one), or null when malformed. */
	public static @Nullable Key parse(@Nullable String key) {
		if (key == null) {
			return null;
		}
		int i = key.lastIndexOf('/');
		if (i <= 0 || i == key.length() - 1) {
			return null;
		}
		return new Key(key.substring(0, i), key.substring(i + 1));
	}

	/**
	 * The world id of a save: its folder name. {@code worldRoot} is what {@code server.getWorldPath(ROOT)}
	 * returns ({@code .../saves/<name>/.}); "." segments are dropped. Null when the path has no name.
	 */
	public static @Nullable String worldIdOf(Path worldRoot) {
		Path p = worldRoot.toAbsolutePath().normalize();
		Path name = p.getFileName();
		return name == null || name.toString().isBlank() ? null : name.toString();
	}

	// ------------------------------------------------------------------ sync

	/**
	 * Building id -> repos before and after a change of the same world: buildings that are new or whose
	 * repos changed are (re)assigned, buildings that are gone are released. Placement order is kept.
	 */
	public static Diff diff(Map<String, List<String>> before, Map<String, List<String>> after) {
		List<String> assign = new ArrayList<>();
		List<String> release = new ArrayList<>();
		for (var e : after.entrySet()) {
			if (!Objects.equals(before.get(e.getKey()), e.getValue())) {
				assign.add(e.getKey());
			}
		}
		for (String id : before.keySet()) {
			if (!after.containsKey(id)) {
				release.add(id);
			}
		}
		return new Diff(List.copyOf(assign), List.copyOf(release));
	}

	// ------------------------------------------------------------------ routing

	/**
	 * The building (id) a lead works in: its assignment's building when that is in {@code worldId} and
	 * still exists; null = home (Marlow, an unassigned lead, a lead of another world's building, or one
	 * whose building is gone).
	 */
	public static @Nullable String leadBuilding(String agentId, List<Lead> leads, @Nullable String worldId, Collection<String> buildingIds) {
		if (MARLOW.equals(agentId) || worldId == null) {
			return null;
		}
		for (Lead l : leads) {
			if (l.leadId().equals(agentId)) {
				Key k = parse(l.building());
				if (k != null && k.worldId().equals(worldId) && buildingIds.contains(k.buildingId())) {
					return k.buildingId();
				}
			}
		}
		return null;
	}

	/** Lead id -> its building id, for every lead assigned to an existing building of {@code worldId} (Marlow never). */
	public static Map<String, String> assignedHere(List<Lead> leads, @Nullable String worldId, Collection<String> buildingIds) {
		Map<String, String> out = new HashMap<>();
		for (Lead l : leads) {
			String b = leadBuilding(l.leadId(), leads, worldId, buildingIds);
			if (b != null) {
				out.put(l.leadId(), b);
			}
		}
		return out;
	}

	/** The lead of a building of {@code worldId}, or null when it has none (then Marlow leads it from home). */
	public static @Nullable String leadOfBuilding(String buildingId, List<Lead> leads, @Nullable String worldId) {
		if (worldId == null) {
			return null;
		}
		String key = key(worldId, buildingId);
		for (Lead l : leads) {
			if (!MARLOW.equals(l.leadId()) && key.equals(l.building())) {
				return l.leadId();
			}
		}
		return null;
	}

	/** The lead whose building has {@code repoId}, else Marlow (the Foreman's {@code leadForRepo}). */
	public static String leadForRepo(@Nullable String repoId, List<Lead> leads) {
		if (repoId != null) {
			for (Lead l : leads) {
				if (!MARLOW.equals(l.leadId()) && l.building() != null && l.repos().contains(repoId)) {
					return l.leadId();
				}
			}
		}
		return MARLOW;
	}

	/** Whether a lead with this role should be on screen: Marlow always, any other lead only while assigned here. */
	public static boolean present(String agentId, @Nullable String building) {
		return MARLOW.equals(agentId) || building != null;
	}

	// ------------------------------------------------------------------ podiums

	/**
	 * Agent id -> the building whose podium shows that agent's decisions: an assigned lead's building when
	 * it has a podium ({@code podiumBuildings}); everyone else (Marlow, workers, unknown ids, a lead whose
	 * building has no podium) is absent = the home podium.
	 */
	public static Map<String, String> podiumOwners(Map<String, String> assignedHere, Collection<String> podiumBuildings) {
		Map<String, String> out = new HashMap<>();
		for (var e : assignedHere.entrySet()) {
			if (podiumBuildings.contains(e.getValue())) {
				out.put(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	/**
	 * Whether the podium of {@code podiumBuilding} (null = not in any building: the HQ studio) shows a
	 * decision filed by {@code agentId}. {@code homeBuilding} is the home building's id (null when there
	 * are no buildings). The home podium (and the studio's) shows Marlow's decisions and every one whose
	 * agent is not an assigned lead with a podium of its own; a building's podium shows its lead's.
	 */
	public static boolean podiumShows(@Nullable String podiumBuilding, @Nullable String homeBuilding, @Nullable String agentId,
		Map<String, String> podiumOwners) {
		String owner = agentId == null ? null : podiumOwners.get(agentId);
		boolean home = podiumBuilding == null || podiumBuilding.equals(homeBuilding);
		if (owner == null) {
			return home;
		}
		return owner.equals(podiumBuilding);
	}
}
