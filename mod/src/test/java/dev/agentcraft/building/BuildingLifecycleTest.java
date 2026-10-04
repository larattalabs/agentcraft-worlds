package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.layout.Anchors;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fix wave 1, stream world: the record fields behind terrain fit, repo edits, moves and crash safety; the
 * occupancy wording; the pure reconcile rule; rebinding a building's stations to new repos.
 */
class BuildingLifecycleTest {
	static final Anchors.Bounds BOX = new Anchors.Bounds(0, 64, 0, 9, 72, 9);

	@Test
	void newFieldsRoundTripAndOldRecordsReadAsBefore() {
		Building b = new Building("b2", "workshop", List.of("r"), true, "none", BOX, BOX, Map.of(), 100L, "minecraft:overworld",
			new Anchors.Bounds(0, 58, 0, 9, 72, 9), 250L, new Building.Site(5, 60, 5, "clockwise_90", "minecraft:the_nether"));
		JsonObject j = b.toJson();
		assertEquals(b, Building.fromJson(JsonParser.parseString(j.toString()).getAsJsonObject()));
		assertEquals(58, b.restoreBox().minY());
		assertEquals(250L, b.layout().revision());
		// an old record: no snapshotBox (= box), revision = placedAt, never moved
		Building old = new Building("b3", "w", List.of("r"), false, "none", BOX, BOX, Map.of(), 7L, null);
		assertEquals(BOX, old.restoreBox());
		assertEquals(7L, old.layout().revision());
		assertNull(old.movedFrom());
		JsonObject oj = old.toJson();
		assertFalse(oj.has("snapshotBox") || oj.has("revision") || oj.has("movedFrom"));
		// a snapshot box equal to the box is not stored
		assertNull(new Building("b4", "w", List.of("r"), false, "none", BOX, BOX, Map.of(), 1L, null, BOX, 1L, null).snapshotBox());
	}

	@Test
	void reposChangeIsANewLayoutRevision() {
		Building b = new Building("b2", "w", List.of("a"), true, "none", BOX, BOX, Map.of(), 100L, null);
		Building c = b.withRepos(List.of("x"), Map.of(), 200L);
		assertEquals(List.of("x"), c.repos());
		assertNotEquals(b.layout().revision(), c.layout().revision());
		assertTrue(c.home());
	}

	@Test
	void pendingRemovalsSurviveTheFile() {
		Building b = new Building("b5", "w", List.of("r"), false, "none", BOX, BOX, Map.of(), 1L, null);
		JsonObject file = Building.fileJson(List.of(), 3, List.of(new Building.Pending(b, "b5.before.nbt", 9L, "removed")));
		Building.FileData back = Building.fileFromJson(JsonParser.parseString(file.toString()).getAsJsonObject());
		assertEquals(1, back.pending().size());
		assertEquals("b5.before.nbt", back.pending().get(0).snapshot());
		assertEquals(b, back.pending().get(0).building());
		// a pending id still counts for "ids are never reused"
		assertEquals(6, back.next());
		// no pending: the key is left out
		assertFalse(Building.fileJson(List.of(b), 6).has("pending"));
	}

	@Test
	void sidecarFoundationBlock() {
		Blueprint plain = Blueprint.fromJson(JsonParser.parseString(BuildingJsonTest.SIDECAR).getAsJsonObject());
		assertEquals(Blueprint.DEFAULT_FOUNDATION, plain.foundationBlock());
		JsonObject o = JsonParser.parseString(BuildingJsonTest.SIDECAR).getAsJsonObject();
		o.addProperty("foundationBlock", "cobblestone");
		Blueprint cob = Blueprint.fromJson(o);
		assertEquals("minecraft:cobblestone", cob.foundationBlock());
		assertEquals(cob, Blueprint.fromJson(cob.toJson()));
		assertTrue(Blueprint.isBlockId("minecraft:stone_bricks"));
		assertFalse(Blueprint.isBlockId("Stone Bricks"));
	}

	@Test
	void reconcileCountsBlocksNotStates() {
		assertTrue(Reconcile.stands(80, 100));
		assertFalse(Reconcile.stands(79, 100));
		assertFalse(Reconcile.stands(0, 0));
		assertTrue(Reconcile.mismatch("b3", 10, 100).startsWith("b3 does not match its blueprint (10 %"));
	}

	@Test
	void rebindingFollowsTheWings() {
		List<String> before = List.of("a", "b");
		assertEquals("repo:x", BlueprintTransform.rebindBinding("repo:a", before, List.of("x", "b")));
		assertNull(BlueprintTransform.rebindBinding("repo:b", before, List.of("x", "b"))); // unchanged
		assertEquals("ci:#2", BlueprintTransform.rebindBinding("ci:b", before, List.of("x"))); // wing 2 lost its repo
		assertEquals("repo:c", BlueprintTransform.rebindBinding("repo:#3", before, List.of("a", "b", "c"))); // an unfilled wing gets one
		assertNull(BlueprintTransform.rebindBinding("repo:other", before, List.of("x"))); // not a wing of this building
		assertNull(BlueprintTransform.rebindBinding("agent:kit", before, List.of("x")));
		assertNull(BlueprintTransform.rebindBinding("decisions", before, List.of("x")));
	}

	@Test
	void occupancyWording() {
		List<Occupancy.Found> found = List.of(new Occupancy.Found(Occupancy.Kind.PLAYER, "Sam", true),
			new Occupancy.Found(Occupancy.Kind.OWNED, "Rex (wolf)", true), new Occupancy.Found(Occupancy.Kind.HOSTILE, "zombie", false),
			new Occupancy.Found(Occupancy.Kind.HOSTILE, "zombie", false), new Occupancy.Found(Occupancy.Kind.HOSTILE, "Bob (zombie)", true),
			new Occupancy.Found(Occupancy.Kind.ITEM, "Diamond ×3", true), new Occupancy.Found(Occupancy.Kind.PROJECTILE, "arrow", false),
			new Occupancy.Found(Occupancy.Kind.LIVING, "villager", true));
		List<String> r = Occupancy.refusals(found);
		assertEquals(Buildings.PLAYER_IN_BOX, r.get(0));
		assertEquals("pets in the box: Rex (wolf) (lead them out)", r.get(1));
		assertEquals("in the box: Bob (zombie), villager (move them out)", r.get(2));
		assertEquals("dropped items in the box: Diamond ×3 (pick them up first)", r.get(3));
		assertEquals("removes 2 × zombie in the box", Occupancy.removalNote(found));
		// only removable ones: nothing refuses
		assertTrue(Occupancy.refusals(List.of(found.get(2), found.get(6))).isEmpty());
		assertNull(Occupancy.removalNote(List.of(found.get(6))));
	}

	@Test
	void tooFewWingsSplitsInTwo() {
		assertEquals(3, BlueprintTransform.splitAt(5, 4, true)); // 5 repos with a 4-wing campus: 3 now, 2 after
		assertEquals(2, BlueprintTransform.splitAt(9, 2, true));
		assertEquals(1, BlueprintTransform.splitAt(3, 0, true)); // only single blueprints
		assertEquals(0, BlueprintTransform.splitAt(3, 0, false));
		assertEquals(0, BlueprintTransform.splitAt(1, 4, true));
	}

	@Test
	void sitesAndRegionsKeepTheirDimension() {
		Anchors.Layout home = new Anchors.Layout("building:b1", 1L, BOX, Map.of("spawn", new dev.agentcraft.layout.Anchor("spawn", 1, 65, 1, 0f, 0f)));
		Anchors.Layout nether = new Anchors.Layout("building:b2", 1L, BOX, Map.of());
		Routing.Site a = new Routing.Site("b1", List.of("a"), true, home, BOX, "minecraft:overworld");
		Routing.Site b = new Routing.Site("b2", List.of("b"), false, nether, BOX, "minecraft:the_nether");
		List<Routing.Site> sites = List.of(a, b);
		// the same coordinates in two dimensions: each lookup finds its own
		assertEquals("b1", Routing.siteAt(sites, "minecraft:overworld", 1, 65, 1, 0).buildingId());
		assertEquals("b2", Routing.siteAt(sites, "minecraft:the_nether", 1, 65, 1, 0).buildingId());
		assertNull(Routing.siteAt(sites, "minecraft:the_end", 1, 65, 1, 0));
		assertEquals(List.of(b), Routing.sitesIn(sites, "minecraft:the_nether"));
		List<Routing.Region> regions = Routing.regions(home, sites);
		assertEquals("minecraft:overworld", regions.get(0).dimension());
		assertEquals("minecraft:the_nether", regions.get(1).dimension());
		assertEquals(1, Routing.regionsIn(regions, "minecraft:the_nether").size());
		assertNull(Routing.regionAt(regions, "minecraft:the_end", 1, 65, 1, 0));
		// a move to another dimension changes the signature (the driver re-applies)
		Routing.Site moved = new Routing.Site("b2", List.of("b"), false, nether, BOX, "minecraft:the_end");
		assertNotEquals(Routing.signature(regions), Routing.signature(Routing.regions(home, List.of(a, moved))));
	}

	@Test
	void podiumsFollowTheDecisionsRepo() {
		Map<String, String> owners = Map.of("ada", "b2"); // ada leads b2, which has a podium
		// a lead's own decisions: its building
		assertEquals("b2", LeadRouting.podiumFor("ada", true, "b3", false, owners));
		// a worker: its task's repo's building, led or not
		assertEquals("b3", LeadRouting.podiumFor("kit", false, "b3", true, owners));
		assertEquals("b3", LeadRouting.podiumFor("kit", false, "b3", false, owners));
		assertNull(LeadRouting.podiumFor("kit", false, null, false, owners)); // no repo building: home
		// marlow: an overflow building he leads shows his decisions for its repos; a led building does not
		assertEquals("b3", LeadRouting.podiumFor(LeadRouting.MARLOW, true, "b3", false, owners));
		assertNull(LeadRouting.podiumFor(LeadRouting.MARLOW, true, "b3", true, owners));
		// an unassigned lead: home
		assertNull(LeadRouting.podiumFor("tove", true, "b3", false, owners));
		// which podium shows a target
		assertTrue(LeadRouting.podiumShowsTarget("b1", "b1", null)); // home podium shows home
		assertTrue(LeadRouting.podiumShowsTarget(null, "b1", "b1")); // the studio counts as home
		assertTrue(LeadRouting.podiumShowsTarget("b1", "b1", "b1"));
		assertFalse(LeadRouting.podiumShowsTarget("b1", "b1", "b3"));
		assertTrue(LeadRouting.podiumShowsTarget("b3", "b1", "b3"));
		assertFalse(LeadRouting.podiumShowsTarget("b3", "b1", null));
	}

	@Test
	void displaysShowTheirBuildingsWork() {
		assertTrue(Displays.monitorLit(true, "building:b2", "building:b2"));
		assertFalse(Displays.monitorLit(true, "building:b1", "building:b2")); // kit works in b1: b2's monitor stays dim
		assertFalse(Displays.monitorLit(true, null, "building:b2"));
		assertFalse(Displays.monitorLit(false, "building:b2", "building:b2"));
		List<String> b2 = List.of("api", "web");
		assertTrue(Displays.mergeShows(b2, false, "api", true));
		assertFalse(Displays.mergeShows(b2, false, "docs", true));
		assertFalse(Displays.mergeShows(b2, false, null, false)); // no repo: home only
		assertTrue(Displays.mergeShows(List.of("x"), true, null, false));
		assertTrue(Displays.mergeShows(List.of("x"), true, "loose", false)); // a repo without a building: home
		assertFalse(Displays.mergeShows(List.of("x"), true, "api", true));
		assertTrue(Displays.mergeShows(null, false, "anything", true)); // the studio shows all
		java.util.function.Predicate<String> built = r -> r.equals("api") || r.equals("x");
		assertTrue(Displays.goalBelongs("marlow", List.of("api"), "ada", b2, false, built));
		assertTrue(Displays.goalBelongs("ada", List.of(), "ada", b2, false, built)); // led by the building's lead
		assertFalse(Displays.goalBelongs("marlow", List.of("x"), "ada", b2, false, built));
		assertTrue(Displays.goalBelongs("marlow", List.of(), null, List.of("x"), true, built)); // no repo: home
		assertTrue(Displays.goalBelongs("marlow", List.of("loose"), null, List.of("x"), true, built));
		assertFalse(Displays.goalBelongs("marlow", List.of("api"), null, List.of("x"), true, built));
	}
}
