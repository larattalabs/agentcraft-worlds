package dev.agentcraft.client.leads;

import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.LeadRouting;
import dev.agentcraft.building.Routing;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.layout.AnchorNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The client's view of "a lead per building" (docs/PRWATCH.md, docs/BUILDINGS.md "Client (routing)"): the
 * Foreman's lead assignments resolved against this world's buildings, built once per change of the
 * Foreman state or the buildings, so agents, podiums, lamps, the hub and task walls all read the same
 * answer. Client thread (it reads {@link ForemanState}).
 */
public final class Leads {
	/**
	 * @param worldId        this world's id (save folder name), null outside singleplayer
	 * @param known          whether the Foreman publishes assignments (false: an older Foreman, old routing)
	 * @param leads          the assignments as received
	 * @param assignedHere   lead id -> building id, leads assigned to an existing building of this world
	 * @param podiumOwners   lead id -> building id whose podium shows that lead's decisions (absent = home podium)
	 * @param homeBuilding   the home building's id (null without buildings)
	 * @param repoPodiums    repo id -> the building with a podium that hosts it (this world)
	 */
	public record View(@Nullable String worldId, boolean known, List<LeadRouting.Lead> leads, Map<String, String> assignedHere,
		Map<String, String> podiumOwners, @Nullable String homeBuilding, Map<String, String> repoPodiums) {

		/** The building a lead works in (null = home): only for leads, only while assignments are known. */
		public @Nullable String buildingOf(String agentId) {
			return assignedHere.get(agentId);
		}

		/** This world's building's lead, or null (Marlow leads it from home; the home building is always his). */
		public @Nullable String leadOf(String buildingId) {
			return LeadRouting.leadOfBuilding(buildingId, leads, worldId, homeBuilding);
		}

		/** The lead of a repo (Foreman-wide), Marlow when none or when it is in this world's home building. */
		public String leadForRepo(@Nullable String repoId) {
			return LeadRouting.leadForRepo(repoId, leads, worldId == null || homeBuilding == null ? null : LeadRouting.key(worldId, homeBuilding));
		}

		/** Whether the podium of {@code podiumBuilding} (null = outside any building) shows a decision of {@code agentId}. */
		public boolean podiumShows(@Nullable String podiumBuilding, @Nullable String agentId) {
			if (!known) {
				return true; // older Foreman: every podium shows everything, as before
			}
			return LeadRouting.podiumShows(podiumBuilding, homeBuilding, agentId, podiumOwners);
		}

		/**
		 * The building whose podium shows {@code d} ({@link LeadRouting#podiumFor}): its lead's, its repo's (a worker's
		 * task, Marlow's for an overflow building), null = home. Client thread.
		 */
		public @Nullable String podiumFor(Protocol.Decision d) {
			ForemanState st = Foreman.state();
			String repo = decisionRepo(st, d);
			String building = repo == null ? null : repoPodiums.get(repo);
			boolean led = building != null && leadOf(building) != null;
			boolean lead = LeadRouting.MARLOW.equals(d.agentId()) || st != null && st.agent(d.agentId()) != null
				&& st.agent(d.agentId()).role() == Protocol.AgentRole.LEAD;
			return LeadRouting.podiumFor(d.agentId(), lead, building, led, podiumOwners);
		}

		/** Whether the podium of {@code podiumBuilding} (null = outside any building) shows {@code d}. Client thread. */
		public boolean podiumShows(@Nullable String podiumBuilding, Protocol.Decision d) {
			if (!known) {
				return true; // older Foreman: every podium shows everything, as before
			}
			return LeadRouting.podiumShowsTarget(podiumBuilding, homeBuilding, podiumFor(d));
		}
	}

	private static final View EMPTY = new View(null, false, List.of(), Map.of(), Map.of(), null, Map.of());

	/** A decision's repo: its own, else its task's, else its agent's task's (null = none). */
	public static @Nullable String decisionRepo(@Nullable ForemanState st, Protocol.Decision d) {
		if (d.repoId() != null && !d.repoId().isBlank()) {
			return d.repoId();
		}
		if (st == null) {
			return null;
		}
		Protocol.Task t = d.taskId() == null ? null : st.task(d.taskId());
		if (t != null && t.repoId() != null) {
			return t.repoId();
		}
		Protocol.Agent a = st.agent(d.agentId());
		Protocol.Task at = a == null || a.taskId() == null ? null : st.task(a.taskId());
		return at == null ? null : at.repoId();
	}
	private static View view = EMPTY;
	private static long seenRevision = Long.MIN_VALUE;
	private static long seenRegions = Long.MIN_VALUE;
	private static @Nullable String seenWorld;
	private static @Nullable List<Routing.Site> seenSites;

	private Leads() {
	}

	/** The current view (rebuilt when the Foreman state, the buildings or the world changed). Client thread. */
	public static View view() {
		ForemanState st = Foreman.state();
		long rev = st == null ? -1 : st.revision();
		long regions = Buildings.regionsSignature();
		String world = Buildings.worldId();
		List<Routing.Site> sites = Buildings.sites();
		if (rev == seenRevision && regions == seenRegions && java.util.Objects.equals(world, seenWorld) && sites == seenSites) {
			return view;
		}
		seenRevision = rev;
		seenRegions = regions;
		seenWorld = world;
		seenSites = sites;
		view = build(st, world, sites);
		return view;
	}

	private static View build(@Nullable ForemanState st, @Nullable String world, List<Routing.Site> sites) {
		if (st == null) {
			return EMPTY;
		}
		List<LeadRouting.Lead> leads = new ArrayList<>();
		for (Protocol.LeadAssignment a : st.leads()) {
			leads.add(new LeadRouting.Lead(a.leadId(), a.building(), a.repos()));
		}
		Set<String> ids = new HashSet<>();
		Set<String> podiums = new HashSet<>();
		Map<String, String> repoPodiums = new java.util.HashMap<>();
		String home = null;
		for (Routing.Site s : sites) {
			ids.add(s.buildingId());
			if (s.layout().get(AnchorNames.DECISION_PODIUM) != null) {
				podiums.add(s.buildingId());
				s.repos().forEach(r -> repoPodiums.put(r, s.buildingId()));
			}
			if (s.home()) {
				home = s.buildingId();
			}
		}
		Map<String, String> here = LeadRouting.assignedHere(leads, world, ids, home);
		return new View(world, st.leadsKnown(), List.copyOf(leads), Map.copyOf(here), Map.copyOf(LeadRouting.podiumOwners(here, podiums)), home,
			Map.copyOf(repoPodiums));
	}

	/** The building id of the site containing the block (grown by {@code margin}), or null (studio / open world). Any dimension. Any thread. */
	public static @Nullable String buildingAt(int x, int y, int z, int margin) {
		Routing.Site s = Routing.siteAt(Buildings.sites(), x, y, z, margin);
		return s == null ? null : s.buildingId();
	}

	/** {@link #buildingAt(int, int, int, int)} among the buildings of {@code level}'s dimension. */
	public static @Nullable String buildingAt(net.minecraft.world.level.@Nullable Level level, int x, int y, int z, int margin) {
		Routing.Site s = Routing.siteAt(Buildings.sites(), level == null ? null : level.dimension().identifier().toString(), x, y, z, margin);
		return s == null ? null : s.buildingId();
	}

	/** Display name for a lead id: the Foreman's agent name, the cast name, else the id capitalised. */
	public static String name(String leadId) {
		Protocol.Agent a = Foreman.state() == null ? null : Foreman.state().agent(leadId);
		if (a != null) {
			return a.name();
		}
		dev.agentcraft.Cast.Member m = dev.agentcraft.Cast.get(leadId);
		return m != null ? m.name() : leadId.isEmpty() ? leadId : Character.toUpperCase(leadId.charAt(0)) + leadId.substring(1);
	}
}
