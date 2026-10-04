package dev.agentcraft.building;

import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Pure rules for what a building's displays show (docs/BUILDINGS.md "Per-building displays"), so every building
 * shows its own work instead of the whole world's:
 * <ul>
 * <li>a monitor lights for its agent only while that agent is routed to the monitor's building;</li>
 * <li>a merge station shows the merges of its building's repos (the home building also those of repos without a
 * building, and merges without a repo); outside every building (the HQ studio) all of them;</li>
 * <li>a goal lamp shows the newest goal of its building's repos or lead (home: also goals of repos without a
 * building, and goals without a repo).</li>
 * </ul>
 */
public final class Displays {
	private Displays() {
	}

	/** Whether a monitor for an agent routed to layout {@code routed} (null = not routed, not shown) lights in a building whose layout is {@code here}. */
	public static boolean monitorLit(boolean active, @Nullable String routed, String here) {
		return active && here.equals(routed);
	}

	/**
	 * Whether a merge of {@code repo} shows at a station of a building with {@code stationRepos} (null: the station is
	 * not in a building, the studio, which shows all). {@code repoHasBuilding}: whether some building hosts the repo.
	 */
	public static boolean mergeShows(@Nullable List<String> stationRepos, boolean stationHome, @Nullable String repo, boolean repoHasBuilding) {
		if (stationRepos == null) {
			return true;
		}
		if (repo != null && stationRepos.contains(repo)) {
			return true;
		}
		return stationHome && (repo == null || !repoHasBuilding);
	}

	/**
	 * Whether a goal (its lead and repos) belongs to a building: one of its repos is the building's, or it is led by
	 * the building's own lead; the home building also takes goals without a repo or whose repos have no building.
	 *
	 * @param buildingLead the building's lead, null when Marlow leads it (home, overflow)
	 */
	public static boolean goalBelongs(@Nullable String goalLead, List<String> goalRepos, @Nullable String buildingLead, List<String> buildingRepos,
		boolean home, Predicate<String> repoHasBuilding) {
		for (String r : goalRepos) {
			if (buildingRepos.contains(r)) {
				return true;
			}
		}
		if (buildingLead != null && buildingLead.equals(goalLead)) {
			return true;
		}
		return home && goalRepos.stream().noneMatch(repoHasBuilding);
	}
}
