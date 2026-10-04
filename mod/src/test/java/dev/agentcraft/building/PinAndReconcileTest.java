package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.building.Reconcile.Action;
import dev.agentcraft.building.Reconcile.Overlap;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Review fixes of the world stream: a building pins the template it was placed from (blueprints regenerated under the
 * same id never change it), the world-start release of pending snapshots (never after N saves), arrows and tridents.
 */
class PinAndReconcileTest {
	static final Anchors.Bounds BOX = new Anchors.Bounds(0, 64, 0, 20, 72, 16);

	static Blueprint group() {
		JsonObject o = JsonParser.parseString(BuildingJsonTest.SIDECAR).getAsJsonObject();
		o.addProperty("id", "duo");
		o.addProperty("kind", "group");
		o.addProperty("wings", 2);
		JsonObject a = o.getAsJsonObject("anchors");
		a.add("task_wall@2", JsonParser.parseString("{\"x\": 4.5, \"y\": 3.0, \"z\": 15.0, \"yaw\": 0.0, \"pitch\": 0.0}"));
		a.add("desk@2", JsonParser.parseString("{\"x\": 6.5, \"y\": 1.0, \"z\": 9.5, \"yaw\": 90.0, \"pitch\": 0.0}"));
		return Blueprint.fromJson(o);
	}

	// ------------------------------------------------------------------ pin (high: blueprint regenerated under the same id)

	@Test
	void pinSurvivesTheFileAndPendingKeepsIt() {
		Blueprint bp = group();
		Building.Pin pin = new Building.Pin("0123456789abcdef", 2, true, BlueprintTransform.rawWorldAnchors(bp, 1, 100, 64, -40),
			List.of(1, 1, 1, 4, 2, 9));
		Building b = new Building("b1", "duo", List.of("a"), true, "clockwise_90", BOX, BOX, Map.of(), 5L, "minecraft:overworld", null, 5L, null, pin);
		Building back = Building.fromJson(JsonParser.parseString(b.toJson().toString()).getAsJsonObject());
		assertEquals(b, back);
		assertEquals(pin, back.pin());
		JsonObject file = Building.fileJson(List.of(b), 3, List.of(new Building.Pending(b, "b1.moved-9.nbt", 9L, "moved")));
		Building.FileData data = Building.fileFromJson(JsonParser.parseString(file.toString()).getAsJsonObject());
		assertEquals(pin, data.pending().get(0).building().pin());
		// withHome / withRepos keep the pin; an old record has none
		assertEquals(pin, b.withHome(false).withRepos(List.of("x"), Map.of(), 6L).pin());
		assertNull(new Building("b2", "w", List.of("r"), false, "none", BOX, BOX, Map.of(), 1L, null).pin());
		assertFalse(new Building("b2", "w", List.of("r"), false, "none", BOX, BOX, Map.of(), 1L, null).toJson().has("pin"));
	}

	@Test
	void fingerprintIsOrderFreeAndSeesEveryChange() {
		int[] xyz = {0, 0, 0, 1, 0, 0, 0, 1, 0};
		String[] st = {"Block{minecraft:stone}", "Block{agentcraft:monitor}[facing=north]", "Block{minecraft:air}"};
		boolean[] be = {false, true, false};
		String f = TemplateGrid.fingerprint(xyz, st, be);
		assertEquals(16, f.length());
		// same cells, another order: same fingerprint
		assertEquals(f, TemplateGrid.fingerprint(new int[] {0, 1, 0, 0, 0, 0, 1, 0, 0},
			new String[] {st[2], st[0], st[1]}, new boolean[] {false, false, true}));
		// vanilla materials instead of the mod's: another template
		assertNotEquals(f, TemplateGrid.fingerprint(xyz, new String[] {st[0], "Block{minecraft:barrel}[facing=north]", st[2]}, be));
		assertNotEquals(f, TemplateGrid.fingerprint(xyz, st, new boolean[] {false, false, false}));
		assertNotEquals(f, TemplateGrid.fingerprint(new int[] {0, 0, 0, 2, 0, 0, 0, 1, 0}, st, be));
	}

	@Test
	void pinnedWingAnchorsGiveWhatTheBlueprintGaveAtPlacement() {
		Blueprint bp = group();
		Map<String, Anchor> raw = BlueprintTransform.rawWorldAnchors(bp, 1, 100, 64, -40);
		assertTrue(raw.containsKey("task_wall@2"));
		for (List<String> repos : List.of(List.of("a"), List.of("a", "b"), List.of("x", "y"))) {
			assertEquals(BlueprintTransform.worldAnchors(bp, 1, 100, 64, -40, repos), BlueprintTransform.renameWings(raw, true, repos));
		}
	}

	@Test
	void withoutAPinRepoEditsOnlyRenameTheStoredAnchors() {
		Blueprint bp = group();
		Map<String, Anchor> stored = BlueprintTransform.worldAnchors(bp, 0, 0, 64, 0, List.of("a", "b"));
		Map<String, Anchor> renamed = BlueprintTransform.rebindAnchors(stored, List.of("a", "b"), List.of("x", "b"));
		assertEquals(stored.get("task_wall:a").x(), renamed.get("task_wall:x").x(), 1e-9);
		assertFalse(renamed.containsKey("task_wall:a"));
		assertEquals(stored.get("task_wall:b"), renamed.get("task_wall:b"));
		assertEquals(stored.get("task_wall"), renamed.get("task_wall")); // wing 1's plain alias keeps its position
		assertEquals(stored.get("desk_kit"), renamed.get("desk_kit"));
		// a wing that loses its repo loses its named anchors
		Map<String, Anchor> one = BlueprintTransform.rebindAnchors(stored, List.of("a", "b"), List.of("a"));
		assertFalse(one.containsKey("task_wall:b") || one.containsKey("desk:b"));
		assertTrue(one.containsKey("task_wall:a"));
	}

	// ------------------------------------------------------------------ world-start release (high: snapshot deleted after pause saves)

	@Test
	void aSnapshotIsOnlyReleasedOnPositiveEvidence() {
		// the restore reached the disk: the site shows its snapshot again
		assertEquals(Action.RELEASE, Reconcile.decide(false, false, true, Overlap.NONE, false, null));
		// neither the building nor its snapshot stands (taken apart before removal, crash): kept
		assertEquals(Action.KEEP, Reconcile.decide(false, false, false, Overlap.NONE, false, null));
		// cannot be checked (blueprint changed, dimension missing): kept
		assertEquals(Action.KEEP, Reconcile.decide(false, null, null, Overlap.NONE, false, null));
		assertEquals(Action.KEEP, Reconcile.decide(true, null, null, Overlap.NONE, true, null));
		// a removal that never reached the disk: the record comes back
		assertEquals(Action.RECOVER, Reconcile.decide(false, true, false, Overlap.NONE, false, null));
		assertEquals(Action.REPORT_KEEP, Reconcile.decide(false, true, false, Overlap.NONE, true, null));
		// restored needs a share of the differing cells
		assertEquals(Boolean.TRUE, Reconcile.restored(90, 100));
		assertEquals(Boolean.FALSE, Reconcile.restored(89, 100));
		assertNull(Reconcile.restored(0, 0));
	}

	@Test
	void aMoveSavedOnlyAtOneSiteIsReportedNotDeleted() {
		// both sites stand (an autosave wrote the new site's chunks, not the old one's): report, keep the snapshot
		assertEquals(Action.REPORT_KEEP, Reconcile.decide(true, true, false, Overlap.NONE, true, true));
		assertEquals(Action.REPORT_KEEP, Reconcile.decide(true, true, false, Overlap.NONE, true, null));
		// only the old site stands: the move is undone
		assertEquals(Action.RECOVER, Reconcile.decide(true, true, false, Overlap.NONE, true, false));
		// only the new site stands and the old site shows its terrain: done
		assertEquals(Action.RELEASE, Reconcile.decide(true, false, true, Overlap.NONE, true, true));
	}

	@Test
	void aTakenDownBuildingUnderAnotherIsNeverReAdded() {
		// remove b1, place b2 on the same spot, crash: b1 "stands" but b2 covers it
		assertEquals(Action.RELEASE, Reconcile.decide(false, true, false, Overlap.COVERED, false, null));
		// partly under b2: report on b2, keep b1's terrain, never re-add b1
		assertEquals(Action.REPORT_KEEP, Reconcile.decide(false, true, false, Overlap.PARTIAL, false, null));
		assertEquals(Action.KEEP, Reconcile.decide(false, false, false, Overlap.PARTIAL, false, null));
	}

	// ------------------------------------------------------------------ occupancy (medium: tridents and pickable arrows)

	@Test
	void pickableProjectilesRefuseLikeItems() {
		Occupancy.Found trident = new Occupancy.Found(Occupancy.Kind.ITEM, "trident", true);
		Occupancy.Found skeletonArrow = new Occupancy.Found(Occupancy.Kind.PROJECTILE, "arrow", false);
		assertFalse(trident.removable());
		assertTrue(skeletonArrow.removable());
		assertEquals(List.of("dropped items in the box: trident (pick them up first)"), Occupancy.refusals(List.of(trident, skeletonArrow)));
	}
}
