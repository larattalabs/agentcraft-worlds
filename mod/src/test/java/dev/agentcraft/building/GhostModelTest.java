package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.building.GhostModel.Conflict;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;

class GhostModelTest {
	static final int SOLID = 0x80FFFFFF;
	static final int AIR = 0;

	/** A full box of {@code sx x sy x sz} cells, all visible except those listed as air. */
	static GhostModel.Cells box(int sx, int sy, int sz, Set<List<Integer>> air) {
		int n = sx * sy * sz;
		int[] xyz = new int[n * 3];
		int[] argb = new int[n];
		int i = 0;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					xyz[i * 3] = x;
					xyz[i * 3 + 1] = y;
					xyz[i * 3 + 2] = z;
					argb[i] = air.contains(List.of(x, y, z)) ? AIR : SOLID;
					i++;
				}
			}
		}
		return new GhostModel.Cells(sx, sy, sz, 1, xyz, argb);
	}

	@Test
	void ghostCellsLandWhereVanillaPlacesThemForAllRotations() {
		// non-square on purpose (sx != sz) so a swapped axis shows up
		int sx = 4;
		int sy = 2;
		int sz = 3;
		GhostModel.Cells cells = box(sx, sy, sz, Set.of());
		for (Rotation rot : Rotation.values()) {
			// what Buildings.place does: vanilla rotates about the origin cell, the box min becomes the origin
			BlockPos a = StructureTemplate.transform(BlockPos.ZERO, Mirror.NONE, rot, BlockPos.ZERO);
			BlockPos b = StructureTemplate.transform(new BlockPos(sx - 1, sy - 1, sz - 1), Mirror.NONE, rot, BlockPos.ZERO);
			BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
			GhostModel g = GhostModel.of(cells, rot.ordinal());
			for (int i = 0; i < cells.count(); i++) {
				BlockPos p = new BlockPos(cells.xyz()[i * 3], cells.xyz()[i * 3 + 1], cells.xyz()[i * 3 + 2]);
				BlockPos v = StructureTemplate.transform(p, Mirror.NONE, rot, BlockPos.ZERO).subtract(min);
				assertArrayEquals(new int[] {v.getX(), v.getY(), v.getZ()}, new int[] {g.x(i), g.y(i), g.z(i)}, rot + " cell " + p);
			}
			assertEquals(BlueprintTransform.rotatedSizeX(sx, sz, rot.ordinal()), g.sizeX, rot + " size x");
			assertEquals(BlueprintTransform.rotatedSizeZ(sx, sz, rot.ordinal()), g.sizeZ, rot + " size z");
		}
	}

	@Test
	void rotatedCellsStayInsideTheRotatedBoxAndAreDistinct() {
		GhostModel.Cells cells = box(5, 3, 2, Set.of());
		for (int t = 0; t < 4; t++) {
			GhostModel g = GhostModel.of(cells, t);
			Set<List<Integer>> seen = new HashSet<>();
			for (int i = 0; i < g.count(); i++) {
				assertTrue(g.x(i) >= 0 && g.x(i) < g.sizeX && g.z(i) >= 0 && g.z(i) < g.sizeZ, "turn " + t + " cell " + i);
				assertTrue(seen.add(List.of(g.x(i), g.y(i), g.z(i))), "duplicate cell at turn " + t);
			}
		}
	}

	@Test
	void exposedFacesOnlyOnTheShell() {
		// one cell: 6 faces; two in a row: 10; a 2x2x2 cube: 24 (the shell), whatever the rotation
		assertEquals(6, GhostModel.of(box(1, 1, 1, Set.of()), 0).faceCount());
		assertEquals(10, GhostModel.of(box(2, 1, 1, Set.of()), 1).faceCount());
		for (int t = 0; t < 4; t++) {
			assertEquals(24, GhostModel.of(box(2, 2, 2, Set.of()), t).faceCount());
		}
		// a 3x3x3 cube: the centre cell is buried (no faces), 54 faces in all
		GhostModel cube = GhostModel.of(box(3, 3, 3, Set.of()), 0);
		assertEquals(54, cube.faceCount());
		for (int i = 0; i < cube.count(); i++) {
			if (cube.x(i) == 1 && cube.y(i) == 1 && cube.z(i) == 1) {
				assertEquals(0, cube.faces(i));
			}
		}
	}

	@Test
	void airCellsAreKeptButNotDrawnAndExposeTheirNeighbours() {
		// a 3x3x3 cube with an air centre (a room): the room's walls face inwards too
		GhostModel g = GhostModel.of(box(3, 3, 3, Set.of(List.of(1, 1, 1))), 2);
		assertEquals(27, g.count());
		assertEquals(26, g.visibleCount());
		assertEquals(54 + 6, g.faceCount());
		for (int i = 0; i < g.count(); i++) {
			if (!g.visible(i)) {
				assertEquals(0, g.faces(i));
			}
		}
		// the cell below the room (1,0,1) shows its top face
		for (int i = 0; i < g.count(); i++) {
			if (g.x(i) == 1 && g.y(i) == 0 && g.z(i) == 1) {
				assertTrue((g.faces(i) & (1 << GhostModel.UP)) != 0);
				assertTrue((g.faces(i) & (1 << GhostModel.DOWN)) != 0);
				assertEquals(0, g.faces(i) & (1 << GhostModel.NORTH));
			}
		}
	}

	@Test
	void faceBitsFollowTheRotation() {
		// an L: cells (0,0,0) and (1,0,0). Unrotated, the shared face is east of cell 0.
		GhostModel.Cells l = new GhostModel.Cells(2, 1, 1, 0, new int[] {0, 0, 0, 1, 0, 0}, new int[] {SOLID, SOLID});
		GhostModel g0 = GhostModel.of(l, 0);
		assertEquals(0, g0.faces(0) & (1 << GhostModel.EAST));
		// one clockwise turn: x runs along +z, so the shared face is south of cell 0
		GhostModel g1 = GhostModel.of(l, 1);
		assertEquals(0, g1.faces(0) & (1 << GhostModel.SOUTH));
		assertTrue((g1.faces(0) & (1 << GhostModel.EAST)) != 0);
	}

	@Test
	void classifyCells() {
		int ground = 2;
		assertEquals(Conflict.NONE, GhostModel.classify(5, ground, true, false, false));
		assertEquals(Conflict.NONE, GhostModel.classify(5, ground, false, true, false), "grass / flowers / water");
		assertEquals(Conflict.OBSTRUCTED, GhostModel.classify(ground, ground, false, false, false), "the ground row itself");
		assertEquals(Conflict.TERRAIN, GhostModel.classify(ground - 1, ground, false, false, false), "the floor row replaces terrain");
		assertEquals(Conflict.TERRAIN, GhostModel.classify(0, ground, false, false, false));
		assertEquals(Conflict.BLOCKED, GhostModel.classify(0, ground, false, false, true), "a chest in the foundation still blocks");
		assertEquals(Conflict.BLOCKED, GhostModel.classify(5, ground, true, true, true));
	}

	@Test
	void refusalsMirrorPlace() {
		assertEquals(List.of(), GhostModel.refusals(List.of("a"), 1, List.of(), 60, 70, -64, 319, List.of(), 0, false));
		assertEquals(List.of("no repo chosen"), GhostModel.refusals(List.of(), 1, List.of(), 60, 70, -64, 319, List.of(), 0, false));
		assertEquals(List.of("2 repos for 1 wing"), GhostModel.refusals(List.of("a", "b"), 1, List.of(), 60, 70, -64, 319, List.of(), 0, false));
		assertEquals(List.of("bad or repeated repo id 'a'"), GhostModel.refusals(List.of("a", "a"), 3, List.of(), 60, 70, -64, 319, List.of(), 0,
			false));
		assertEquals(List.of("repo a already has a building"), GhostModel.refusals(List.of("a"), 1, List.of("a"), 60, 70, -64, 319, List.of(), 0,
			false));
		assertEquals(1, GhostModel.refusals(List.of("a"), 1, List.of(), 310, 330, -64, 319, List.of(), 0, false).size());
		assertEquals(List.of("overlaps building b2"), GhostModel.refusals(List.of("a"), 1, List.of(), 60, 70, -64, 319, List.of("b2"), 0, false));
		assertEquals(1, GhostModel.refusals(List.of("a"), 1, List.of(), 60, 70, -64, 319, List.of(), 3, false).size());
		assertEquals(List.of(), GhostModel.refusals(List.of("a"), 1, List.of(), 60, 70, -64, 319, List.of(), 3, true), "force overwrites BEs");
	}

	@Test
	void relativeSteps() {
		// facing south (+z): forward is +z, right is west (-x)
		assertArrayEquals(new int[] {0, 1}, GhostModel.relativeToWorld("south", 1, 0));
		assertArrayEquals(new int[] {-1, 0}, GhostModel.relativeToWorld("south", 0, 1));
		assertArrayEquals(new int[] {0, -1}, GhostModel.relativeToWorld("north", 1, 0));
		assertArrayEquals(new int[] {1, 0}, GhostModel.relativeToWorld("north", 0, 1));
		assertArrayEquals(new int[] {1, 0}, GhostModel.relativeToWorld("east", 1, 0));
		assertArrayEquals(new int[] {0, 1}, GhostModel.relativeToWorld("east", 0, 1));
		assertArrayEquals(new int[] {-1, 0}, GhostModel.relativeToWorld("west", 1, 0));
		assertArrayEquals(new int[] {0, -1}, GhostModel.relativeToWorld("west", 0, 1));
	}
}
