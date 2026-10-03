package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;

class BlueprintTransformTest {
	// a non-square template: 3 wide (x), 2 high, 2 deep (z), so an sx/sz swap shows up
	static final int SX = 3;
	static final int SZ = 2;
	static final int OX = 100;
	static final int OY = 64;
	static final int OZ = -40;

	/** A feet spot at the centre of template block (0, 1, 1), facing south. */
	static final Anchor SPOT = new Anchor("desk_kit", 0.5, 1.0, 1.5, 0f, 0f);
	/** A surface anchor on the template's south face (z = 2.0), above block x = 1, front facing south. */
	static final Anchor FACE = new Anchor("task_wall@1", 1.5, 1.5, 2.0, 0f, 0f);

	@Test
	void spotForAllFourRotations() {
		// expected: world position and the block it stands in, for NONE, CW_90, 180, CCW_90
		double[][] expect = {
			{OX + 0.5, OZ + 1.5, 0f},
			{OX + 0.5, OZ + 0.5, 90f},
			{OX + 2.5, OZ + 0.5, 180f},
			{OX + 1.5, OZ + 2.5, -90f},
		};
		int[][] blocks = {{0, 1}, {0, 0}, {2, 0}, {1, 2}};
		for (int t = 0; t < 4; t++) {
			Anchor w = BlueprintTransform.toWorld(SPOT, SX, SZ, t, OX, OY, OZ);
			assertEquals(expect[t][0], w.x(), 1e-9, "x, turns " + t);
			assertEquals(OY + 1.0, w.y(), 1e-9, "y, turns " + t);
			assertEquals(expect[t][1], w.z(), 1e-9, "z, turns " + t);
			assertEquals((float) expect[t][2], w.yaw(), 1e-4, "yaw, turns " + t);
			// the spot is the centre of the block the template block went to
			int[] b = BlueprintTransform.rotateBlock(0, 1, SX, SZ, t);
			assertArrayEquals(blocks[t], b, "block, turns " + t);
			assertEquals(OX + b[0] + 0.5, w.x(), 1e-9);
			assertEquals(OZ + b[1] + 0.5, w.z(), 1e-9);
		}
	}

	@Test
	void surfaceAnchorStaysOnTheFaceItWasOn() {
		// the template's south face (z = SZ) goes to: south, west (x = 0), north (z = 0), east (x = SZ) side
		Anchor none = BlueprintTransform.toWorld(FACE, SX, SZ, 0, OX, OY, OZ);
		assertEquals(OZ + 2.0, none.z(), 1e-9);
		assertEquals(OX + 1.5, none.x(), 1e-9);
		Anchor cw = BlueprintTransform.toWorld(FACE, SX, SZ, 1, OX, OY, OZ);
		assertEquals(OX + 0.0, cw.x(), 1e-9);
		assertEquals(OZ + 1.5, cw.z(), 1e-9);
		assertEquals(90f, cw.yaw(), 1e-4); // faces west
		Anchor half = BlueprintTransform.toWorld(FACE, SX, SZ, 2, OX, OY, OZ);
		assertEquals(OZ + 0.0, half.z(), 1e-9);
		assertEquals(OX + 1.5, half.x(), 1e-9);
		assertEquals(180f, half.yaw(), 1e-4); // faces north
		Anchor ccw = BlueprintTransform.toWorld(FACE, SX, SZ, 3, OX, OY, OZ);
		assertEquals(OX + 2.0, ccw.x(), 1e-9); // rotated footprint is SZ wide along x
		assertEquals(OZ + 1.5, ccw.z(), 1e-9);
		assertEquals(-90f, ccw.yaw(), 1e-4); // faces east
	}

	/** Our block mapping agrees with Minecraft's own StructureTemplate.transform, normalised to the rotated box's minimum corner. */
	@Test
	void blockMappingMatchesVanillaTransform() {
		for (int t = 0; t < 4; t++) {
			Rotation rot = Rotation.values()[t];
			int minX = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			for (int x = 0; x < SX; x++) {
				for (int z = 0; z < SZ; z++) {
					BlockPos p = StructureTemplate.transform(new BlockPos(x, 0, z), Mirror.NONE, rot, BlockPos.ZERO);
					minX = Math.min(minX, p.getX());
					minZ = Math.min(minZ, p.getZ());
				}
			}
			for (int x = 0; x < SX; x++) {
				for (int z = 0; z < SZ; z++) {
					BlockPos p = StructureTemplate.transform(new BlockPos(x, 0, z), Mirror.NONE, rot, BlockPos.ZERO);
					int[] ours = BlueprintTransform.rotateBlock(x, z, SX, SZ, t);
					assertArrayEquals(new int[] {p.getX() - minX, p.getZ() - minZ}, ours, rot + " block " + x + "," + z);
					// and a block-centre point lands in that block's centre
					double[] c = BlueprintTransform.rotatePoint(x + 0.5, z + 0.5, SX, SZ, t);
					assertEquals(ours[0] + 0.5, c[0], 1e-9);
					assertEquals(ours[1] + 0.5, c[1], 1e-9);
				}
			}
			assertEquals(rot.ordinal(), t);
			assertEquals(BlueprintTransform.rotationName(t), rot.name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	@Test
	void yawRotatesLikeDirections() {
		// Minecraft's Rotation turns north -> east for one clockwise step; yaw 180 (north) -> -90 (east)
		assertEquals(-90f, BlueprintTransform.rotateYaw(180f, 1), 1e-4);
		assertEquals(180f, BlueprintTransform.rotateYaw(180f, 0), 1e-4);
		assertEquals(0f, BlueprintTransform.rotateYaw(180f, 2), 1e-4);
		assertEquals(90f, BlueprintTransform.rotateYaw(180f, 3), 1e-4);
		assertEquals("east", BlueprintTransform.rotateDirection("north", 1));
		for (int t = 0; t < 4; t++) {
			assertEquals(Rotation.values()[t].rotate(net.minecraft.core.Direction.SOUTH).getName(), BlueprintTransform.rotateDirection("south", t));
		}
	}

	@Test
	void walkBoxToWorld() {
		Anchors.Bounds walk = new Anchors.Bounds(0, 1, 0, 2, 1, 0); // the template's north row
		assertEquals(new Anchors.Bounds(OX, OY + 1, OZ, OX + 2, OY + 1, OZ), BlueprintTransform.boxToWorld(walk, SX, SZ, 0, OX, OY, OZ));
		// clockwise: the north row becomes the east column (x = SZ-1)
		assertEquals(new Anchors.Bounds(OX + 1, OY + 1, OZ, OX + 1, OY + 1, OZ + 2), BlueprintTransform.boxToWorld(walk, SX, SZ, 1, OX, OY, OZ));
		assertEquals(new Anchors.Bounds(OX, OY + 1, OZ + 1, OX + 2, OY + 1, OZ + 1), BlueprintTransform.boxToWorld(walk, SX, SZ, 2, OX, OY, OZ));
		assertEquals(new Anchors.Bounds(OX, OY + 1, OZ, OX, OY + 1, OZ + 2), BlueprintTransform.boxToWorld(walk, SX, SZ, 3, OX, OY, OZ));
	}

	@Test
	void turnsToFaceAndParsing() {
		assertEquals(0, BlueprintTransform.turnsToFace("south", "south"));
		assertEquals(1, BlueprintTransform.turnsToFace("south", "west"));
		assertEquals(2, BlueprintTransform.turnsToFace("south", "north"));
		assertEquals(3, BlueprintTransform.turnsToFace("south", "east"));
		assertEquals(1, BlueprintTransform.turnsToFace("north", "east"));
		assertEquals(1, BlueprintTransform.parseTurns("clockwise_90"));
		assertEquals(1, BlueprintTransform.parseTurns("CW"));
		assertEquals(2, BlueprintTransform.parseTurns("180"));
		assertEquals(3, BlueprintTransform.parseTurns("-90"));
		assertEquals(0, BlueprintTransform.parseTurns("none"));
		assertEquals(-1, BlueprintTransform.parseTurns("force"));
	}

	@Test
	void originInFrontOfPlayer() {
		// player at (0, 65, 0); template rotated size 5 x 3, groundY 1
		assertArrayEquals(new int[] {-2, 64, 2}, BlueprintTransform.originInFront(0, 65, 0, "south", 5, 3, 1, 2));
		assertArrayEquals(new int[] {-2, 64, -4}, BlueprintTransform.originInFront(0, 65, 0, "north", 5, 3, 1, 2)); // max z = -2
		assertArrayEquals(new int[] {2, 64, -1}, BlueprintTransform.originInFront(0, 65, 0, "east", 5, 3, 1, 2));
		assertArrayEquals(new int[] {-6, 64, -1}, BlueprintTransform.originInFront(0, 65, 0, "west", 5, 3, 1, 2)); // max x = -2
	}

	@Test
	void bindingRewrite() {
		List<String> repos = List.of("pocket-api", "web.app");
		assertEquals("repo:pocket-api", BlueprintTransform.rewriteBinding("repo:#1", repos));
		assertEquals("ci:web.app", BlueprintTransform.rewriteBinding("ci:#2", repos));
		assertNull(BlueprintTransform.rewriteBinding("repo:#3", repos)); // wing without a repo: left as is
		assertNull(BlueprintTransform.rewriteBinding("repo:#0", repos));
		assertNull(BlueprintTransform.rewriteBinding("kit", repos));
		assertNull(BlueprintTransform.rewriteBinding("agent:kit", repos));
		assertNull(BlueprintTransform.rewriteBinding("repo:demo", repos));
		assertNull(BlueprintTransform.rewriteBinding("", repos));
	}

	@Test
	void wingRenamingSingle() {
		Map<String, Anchor> in = anchors("task_wall@1", "task_wall@2", "desk_kit", "bad@x");
		Map<String, Anchor> out = BlueprintTransform.renameWings(in, false, List.of("solo"));
		assertEquals(List.of("desk_kit", "task_wall"), List.copyOf(out.keySet()));
		assertEquals("task_wall", out.get("task_wall").name());
		assertEquals(1.0, out.get("task_wall").x(), 1e-9); // came from @1
	}

	@Test
	void wingRenamingGroup() {
		Map<String, Anchor> in = anchors("task_wall@1", "task_wall@2", "task_wall@3", "desk_kit");
		Map<String, Anchor> out = BlueprintTransform.renameWings(in, true, List.of("a", "b"));
		assertTrue(out.containsKey("task_wall:a"));
		assertTrue(out.containsKey("task_wall:b"));
		assertFalse(out.containsKey("task_wall:#3"));
		assertEquals(4, out.size(), out.keySet().toString()); // desk_kit, task_wall:a, task_wall (alias of wing 1), task_wall:b
		assertEquals(out.get("task_wall:a").x(), out.get("task_wall").x(), 1e-9);
		assertEquals(2.0, out.get("task_wall:b").x(), 1e-9);
	}

	@Test
	void parseRepos() {
		assertEquals(List.of("a", "b/c", "d.e"), BlueprintTransform.parseRepos(" a, b/c ,,d.e,a"));
	}

	/** Anchors named as given, with x = the wing number (1 for unsuffixed) to tell them apart. */
	private static Map<String, Anchor> anchors(String... names) {
		Map<String, Anchor> m = new LinkedHashMap<>();
		for (String n : names) {
			int w = Math.max(1, BlueprintTransform.wingOf(n));
			m.put(n, new Anchor(n, w, 0, 0, 0f, 0f));
		}
		return m;
	}
}
