package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.layout.Anchors;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeadRoutingTest {
	static final String W = "New World";
	final List<LeadRouting.Lead> leads = List.of(
		new LeadRouting.Lead("marlow", null, List.of()),
		new LeadRouting.Lead("ines", W + "/b2", List.of("beta", "gamma")),
		new LeadRouting.Lead("bram", "Other World/b1", List.of("delta")),
		new LeadRouting.Lead("cass", W + "/b9", List.of("omega")));
	final Set<String> buildings = Set.of("b1", "b2");

	@Test
	void keysRoundTrip() {
		assertEquals("New World/b3", LeadRouting.key(W, "b3"));
		LeadRouting.Key k = LeadRouting.parse("New World/b3");
		assertEquals(W, k.worldId());
		assertEquals("b3", k.buildingId());
		assertEquals("a/b", LeadRouting.parse("a/b/b1").worldId(), "split at the last slash");
		assertNull(LeadRouting.parse(null));
		assertNull(LeadRouting.parse("b1"));
		assertNull(LeadRouting.parse("/b1"));
		assertNull(LeadRouting.parse("w/"));
	}

	@Test
	void worldIdIsTheSaveFolderName() {
		assertEquals(W, LeadRouting.worldIdOf(Path.of("/tmp/mc/saves/New World/.")));
		assertEquals("hq", LeadRouting.worldIdOf(Path.of("/tmp/mc/saves/hq")));
		assertEquals("x", LeadRouting.worldIdOf(Path.of("/tmp/mc/saves/x/./")));
	}

	@Test
	void diffAssignsNewAndChangedReleasesGone() {
		Map<String, List<String>> before = new LinkedHashMap<>();
		before.put("b1", List.of("alpha"));
		before.put("b2", List.of("beta"));
		Map<String, List<String>> after = new LinkedHashMap<>();
		after.put("b2", List.of("beta", "gamma"));
		after.put("b3", List.of("delta"));
		LeadRouting.Diff d = LeadRouting.diff(before, after);
		assertEquals(List.of("b2", "b3"), d.assign());
		assertEquals(List.of("b1"), d.release());
		assertTrue(LeadRouting.diff(after, new LinkedHashMap<>(after)).isEmpty());
	}

	@Test
	void leadBuildingOnlyForThisWorldsExistingBuildings() {
		assertEquals("b2", LeadRouting.leadBuilding("ines", leads, W, buildings));
		assertNull(LeadRouting.leadBuilding("marlow", leads, W, buildings), "marlow: home");
		assertNull(LeadRouting.leadBuilding("bram", leads, W, buildings), "another world's building: not here");
		assertNull(LeadRouting.leadBuilding("cass", leads, W, buildings), "a building that is gone: home");
		assertNull(LeadRouting.leadBuilding("juniper", leads, W, buildings), "unknown: home");
		assertNull(LeadRouting.leadBuilding("ines", leads, null, buildings), "no world (multiplayer): home");
		assertEquals(Map.of("ines", "b2"), LeadRouting.assignedHere(leads, W, buildings));
		assertTrue(LeadRouting.present("marlow", null));
		assertFalse(LeadRouting.present("cass", null));
		assertTrue(LeadRouting.present("ines", "b2"));
	}

	@Test
	void leadOfBuildingAndRepo() {
		assertEquals("ines", LeadRouting.leadOfBuilding("b2", leads, W));
		assertNull(LeadRouting.leadOfBuilding("b1", leads, W));
		assertNull(LeadRouting.leadOfBuilding("b1", leads, null));
		assertEquals("bram", LeadRouting.leadOfBuilding("b1", leads, "Other World"));
		assertEquals("ines", LeadRouting.leadForRepo("gamma", leads));
		assertEquals("bram", LeadRouting.leadForRepo("delta", leads), "repo -> lead is Foreman-wide");
		assertEquals("marlow", LeadRouting.leadForRepo("alpha", leads));
		assertEquals("marlow", LeadRouting.leadForRepo(null, leads));
	}

	@Test
	void podiumFilter() {
		Map<String, String> here = Map.of("ines", "b2", "cass", "b3");
		// b3 has no podium: cass's decisions fall back to the home podium
		Map<String, String> owners = LeadRouting.podiumOwners(here, Set.of("b1", "b2"));
		assertEquals(Map.of("ines", "b2"), owners);
		// home podium (b1): marlow, workers, unknown ids and podium-less leads
		assertTrue(LeadRouting.podiumShows("b1", "b1", "marlow", owners));
		assertTrue(LeadRouting.podiumShows("b1", "b1", "juniper", owners));
		assertTrue(LeadRouting.podiumShows("b1", "b1", "cass", owners));
		assertTrue(LeadRouting.podiumShows("b1", "b1", null, owners));
		assertFalse(LeadRouting.podiumShows("b1", "b1", "ines", owners));
		// ines's building shows only hers
		assertTrue(LeadRouting.podiumShows("b2", "b1", "ines", owners));
		assertFalse(LeadRouting.podiumShows("b2", "b1", "marlow", owners));
		assertFalse(LeadRouting.podiumShows("b2", "b1", "juniper", owners));
		// a podium outside any building (the HQ studio) is a home podium
		assertTrue(LeadRouting.podiumShows(null, null, "marlow", owners));
		assertFalse(LeadRouting.podiumShows(null, "b1", "ines", owners));
		// a (stale) lead of the home building is ignored: the home building is marlow's, its podium shows his
		List<LeadRouting.Lead> stale = List.of(new LeadRouting.Lead("bram", W + "/b1", List.of("alpha")));
		Map<String, String> hereWithHome = LeadRouting.assignedHere(stale, W, buildings, "b1");
		assertEquals(Map.of(), hereWithHome);
		Map<String, String> homeLed = LeadRouting.podiumOwners(hereWithHome, Set.of("b1"));
		assertTrue(LeadRouting.podiumShows("b1", "b1", "bram", homeLed), "bram is not a lead here: his decisions go home");
		assertTrue(LeadRouting.podiumShows("b1", "b1", "marlow", homeLed));
	}

	@Test
	void homeBuildingIsAlwaysMarlows() {
		Map<String, List<String>> all = new LinkedHashMap<>();
		all.put("b1", List.of("alpha"));
		all.put("b2", List.of("beta", "gamma"));
		all.put("b3", List.of("delta"));
		// never sent: lead.assign / lead.sync leave the home building out
		assertEquals(List.of("b2", "b3"), List.copyOf(LeadRouting.leadBuildings(all, "b1").keySet()));
		assertEquals(List.of("b1", "b2", "b3"), List.copyOf(LeadRouting.leadBuildings(all, null).keySet()));
		// a home change b1 -> b2: b1 now gets a lead, b2's is released
		LeadRouting.Diff d = LeadRouting.diff(LeadRouting.leadBuildings(all, "b1"), LeadRouting.leadBuildings(all, "b2"));
		assertEquals(List.of("b1"), d.assign());
		assertEquals(List.of("b2"), d.release());
		// routing ignores a stale assignment to the home building
		assertNull(LeadRouting.leadBuilding("ines", leads, W, buildings, "b2"));
		assertEquals("b2", LeadRouting.leadBuilding("ines", leads, W, buildings, "b1"));
		assertNull(LeadRouting.leadOfBuilding("b2", leads, W, "b2"));
		assertEquals("ines", LeadRouting.leadOfBuilding("b2", leads, W, "b1"));
		assertEquals("marlow", LeadRouting.leadForRepo("gamma", leads, W + "/b2"));
		assertEquals("ines", LeadRouting.leadForRepo("gamma", leads, W + "/b1"));
	}

	@Test
	void layoutForBuildingAndSiteAt() {
		RoutingTest rt = new RoutingTest();
		assertSame(rt.b2.layout(), Routing.layoutForBuilding("b2", rt.sites, rt.home));
		assertSame(rt.home, Routing.layoutForBuilding("b1", rt.sites, rt.home), "the home building is current");
		assertSame(rt.home, Routing.layoutForBuilding("b9", rt.sites, rt.home));
		assertSame(rt.home, Routing.layoutForBuilding(null, rt.sites, rt.home));
		assertEquals("b2", Routing.siteAt(rt.sites, 110, 66, 5, 0).buildingId());
		assertEquals("b1", Routing.siteAt(rt.sites, -2, 66, 5, 3).buildingId(), "the margin covers lamps set into the walls");
		assertNull(Routing.siteAt(rt.sites, 50, 66, 5, 3));
		Anchors.Bounds unused = RoutingTest.STUDIO;
		assertNull(Routing.siteAt(rt.sites, unused.minX(), unused.minY(), unused.minZ(), 0));
	}
}
