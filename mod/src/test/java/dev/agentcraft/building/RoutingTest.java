package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingTest {
	static Anchors.Layout layout(String name, long rev, Anchors.Bounds bounds, String... anchors) {
		Map<String, Anchor> m = new LinkedHashMap<>();
		for (String a : anchors) {
			m.put(a, new Anchor(a, bounds.minX() + 1.5, bounds.minY(), bounds.minZ() + 1.5, 0, 0));
		}
		return new Anchors.Layout(name, rev, bounds, m);
	}

	static final Anchors.Bounds BOX1 = new Anchors.Bounds(0, 64, 0, 20, 72, 16);
	static final Anchors.Bounds BOX2 = new Anchors.Bounds(100, 64, 0, 120, 72, 16);
	static final Anchors.Bounds STUDIO = new Anchors.Bounds(-40, 60, -40, -10, 80, -10);

	static Routing.Site site(String id, boolean home, Anchors.Bounds box, List<String> repos, String... anchors) {
		Anchors.Bounds walk = new Anchors.Bounds(box.minX() + 1, box.minY() + 1, box.minZ() + 1, box.maxX() - 1, box.maxY() - 1, box.maxZ() - 1);
		return new Routing.Site(id, repos, home, layout("building:" + id, 1000 + id.hashCode(), walk, anchors), box);
	}

	final Routing.Site b1 = site("b1", true, BOX1, List.of("alpha"), "desk_kit", "lounge", "library");
	final Routing.Site b2 = site("b2", false, BOX2, List.of("beta", "gamma"), "desk_kit", "lounge");
	/** Anchors.current() in a world with buildings: the home building, re-revisioned by Anchors.showDerived. */
	final Anchors.Layout home = new Anchors.Layout(b1.layout().name(), 7, b1.layout().bounds(), b1.layout().anchors());
	final List<Routing.Site> sites = List.of(b1, b2);

	@Test
	void agentRepoPrefersAgentThenTaskThenLeadGoal() {
		assertEquals("a", Routing.agentRepo("a", "t", true, "g", true));
		assertEquals("t", Routing.agentRepo(null, "t", true, "g", true));
		assertEquals("t", Routing.agentRepo(" ", "t", false, null, true));
		assertEquals("g", Routing.agentRepo(null, null, true, "g", true));
		assertNull(Routing.agentRepo(null, null, false, "g", true), "a worker without repo/task goes home, not to the goal's repo");
		assertNull(Routing.agentRepo(null, "", true, "", true));
		assertNull(Routing.agentRepo("a", "t", true, "g", false), "off shift = home lounge");
	}

	@Test
	void layoutForRoutesToTheRepoBuildingElseCurrent() {
		assertSame(b2.layout(), Routing.layoutFor("beta", sites, home));
		assertSame(b2.layout(), Routing.layoutFor("gamma", sites, home));
		assertSame(home, Routing.layoutFor("nope", sites, home));
		assertSame(home, Routing.layoutFor(null, sites, home));
		// the home building through its repo is the same layout as Anchors.current(): one identity, one revision
		assertSame(home, Routing.layoutFor("alpha", sites, home));
	}

	@Test
	void hqWorldWithoutBuildingsIsAlwaysCurrent() {
		Anchors.Layout studio = layout("hq", 3, STUDIO, "lounge");
		assertSame(studio, Routing.layoutFor("alpha", List.of(), studio));
		assertEquals(List.of(studio), Routing.layouts(studio, List.of()));
		List<Routing.Region> regions = Routing.regions(studio, List.of());
		assertEquals(1, regions.size());
		assertEquals(STUDIO, regions.get(0).area());
		assertFalse(regions.get(0).building());
		assertTrue(Routing.layouts(Anchors.Layout.EMPTY, List.of()).isEmpty());
		assertTrue(Routing.regions(Anchors.Layout.EMPTY, List.of()).isEmpty());
	}

	@Test
	void regionsCoverEveryBuildingOnce() {
		List<Routing.Region> regions = Routing.regions(home, sites);
		assertEquals(2, regions.size());
		assertSame(home, regions.get(0).layout());
		assertEquals(BOX1, regions.get(0).area(), "the home building's region is its box, not only the walkable bounds");
		assertTrue(regions.get(0).building());
		assertSame(b2.layout(), regions.get(1).layout());
		assertEquals(List.of(home, b2.layout()), Routing.layouts(home, sites));
		assertSame(regions.get(1), Routing.regionAt(regions, 120, 70, 16, 0));
		assertSame(regions.get(1), Routing.regionAt(regions, 122, 70, 16, 3));
		assertNull(Routing.regionAt(regions, 122, 70, 16, 0));
		assertNull(Routing.regionAt(regions, 60, 70, 8, 3));
	}

	@Test
	void studioPlusVerifyBuildingInHqWorld() {
		// buildings placed in the HQ world never replace the studio: both are regions, the studio is not a building
		Anchors.Layout studio = layout("hq", 3, STUDIO, "lounge");
		List<Routing.Region> regions = Routing.regions(studio, sites);
		assertEquals(3, regions.size());
		assertFalse(regions.get(0).building());
		assertTrue(regions.get(1).building());
	}

	@Test
	void signatureFollowsRevisions() {
		long a = Routing.signature(Routing.regions(home, sites));
		Anchors.Layout home2 = new Anchors.Layout(home.name(), 8, home.bounds(), home.anchors());
		assertFalse(a == Routing.signature(Routing.regions(home2, sites)));
		assertEquals(a, Routing.signature(Routing.regions(home, List.of(b1, b2))));
	}

	@Test
	void canHostNeedsDeskStationOrLounge() {
		Anchors.Layout l = b1.layout();
		assertTrue(Routing.canHost(l, "desk", "kit"));
		assertTrue(Routing.canHost(l, "desk", "juniper"), "no desk: the lounge takes it");
		assertTrue(Routing.canHost(l, "library", "kit"));
		Anchors.Layout bare = layout("building:b9", 1, BOX1, "desk_kit", "terminal");
		assertTrue(Routing.canHost(bare, "desk", "kit"));
		assertTrue(Routing.canHost(bare, "terminal", "kit"));
		assertFalse(Routing.canHost(bare, "desk", "juniper"));
		assertFalse(Routing.canHost(bare, "library", "kit"));
		assertFalse(Routing.canHost(Anchors.Layout.EMPTY, "lounge", "kit"));
	}

	@Test
	void boardRepoFilter() {
		assertEquals("alpha", Routing.boardRepo("repo:alpha"));
		assertEquals("#2", Routing.boardRepo("repo:#2"), "an unrewritten wing placeholder matches no repo");
		assertNull(Routing.boardRepo(""));
		assertNull(Routing.boardRepo(null));
		assertNull(Routing.boardRepo("repo:"));
		assertNull(Routing.boardRepo("doing"));
		assertNull(Routing.boardRepo("ci:alpha"));
		assertTrue(Routing.boardShows(null, "alpha"));
		assertTrue(Routing.boardShows(null, null));
		assertTrue(Routing.boardShows("alpha", "alpha"));
		assertFalse(Routing.boardShows("alpha", "beta"));
		assertFalse(Routing.boardShows("alpha", null));
		assertFalse(Routing.boardShows("#2", "beta"));
	}

	@Test
	void ciPlaceholder() {
		assertTrue(Routing.isCiPlaceholder("ci:#1"));
		assertFalse(Routing.isCiPlaceholder("ci:alpha"));
		assertFalse(Routing.isCiPlaceholder("agent:kit"));
	}
}
