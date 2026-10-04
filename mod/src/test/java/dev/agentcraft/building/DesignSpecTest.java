package dev.agentcraft.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesignSpecTest {
	static final String OUT = "/Users/me/Library/Application Support/minecraft/agentcraft/blueprints";

	static DesignSpec.Draft draft(String kind, int wings, int x, int y, int z) {
		return new DesignSpec.Draft(kind, wings, "modern", "agentcraft", List.of("porch", "garden"), x, y, z, null, null, null, OUT);
	}

	@Test
	void choicesMatchTheProtocol() {
		// foreman/src/protocol.ts DesignStyle / DesignFeature
		assertEquals(List.of("modern", "cabin", "townhouse", "workshop", "campus", "custom"), DesignSpec.ids(DesignSpec.STYLES));
		assertEquals(List.of("porch", "skylights", "courtyard", "big_windows", "garden"), DesignSpec.ids(DesignSpec.FEATURES));
		assertEquals(List.of("agentcraft", "vanilla"), DesignSpec.ids(DesignSpec.MATERIALS));
	}

	@Test
	void validDraftsPass() {
		assertTrue(DesignSpec.validate(draft("single", 1, 36, 16, 36)).isEmpty());
		assertTrue(DesignSpec.validate(draft("group", 2, 63, 18, 36)).isEmpty());
		assertTrue(DesignSpec.validate(draft("group", 8, 128, 48, 128)).isEmpty());
		assertTrue(DesignSpec.validate(draft("single", 1, 9, 6, 9)).isEmpty());
	}

	@Test
	void limitsAreTheForemans() {
		Map<String, String> e = DesignSpec.validate(draft("single", 2, 8, 49, 129));
		assertEquals("a single building has exactly 1 wing", e.get("wings"));
		assertTrue(e.get("maxSize").contains("x 8"));
		assertTrue(e.get("maxSize").contains("height 49"));
		assertTrue(e.get("maxSize").contains("z 129"));
		assertNotNull(DesignSpec.validate(draft("group", 1, 40, 16, 40)).get("wings"));
		assertNotNull(DesignSpec.validate(draft("group", 9, 40, 16, 40)).get("wings"));
		assertNotNull(DesignSpec.validate(draft("tower", 1, 40, 16, 40)).get("kind"));
		DesignSpec.Draft bad = new DesignSpec.Draft("single", 1, "gothic", "stone", List.of("porch", "porch"), 20, 10, 20, "Not An Id",
			"x".repeat(41), "n".repeat(2001), "relative/agentcraft/blueprints");
		Map<String, String> b = DesignSpec.validate(bad);
		assertEquals(List.of("style", "materials", "features", "remix", "name", "notes", "outDir"), List.copyOf(b.keySet()));
		assertTrue(b.get("features").contains("duplicate"));
		// blank optional fields are fine (omitted on the wire)
		assertTrue(DesignSpec.validate(new DesignSpec.Draft("single", 1, "custom", "vanilla", List.of(), 20, 10, 20, " ", "", null, OUT))
			.isEmpty());
		assertNotNull(DesignSpec.validate(new DesignSpec.Draft("single", 1, "modern", "vanilla", List.of("pool"), 20, 10, 20, null, null, null,
			OUT)).get("features"));
	}

	@Test
	void outDirRule() {
		assertNull(DesignSpec.outDirProblem(OUT));
		assertNull(DesignSpec.outDirProblem("C:\\Games\\mc\\agentcraft\\blueprints"));
		assertNotNull(DesignSpec.outDirProblem("agentcraft/blueprints"));
		assertNotNull(DesignSpec.outDirProblem("/tmp/agentcraft/other"));
		assertNotNull(DesignSpec.outDirProblem("/tmp/../agentcraft/blueprints"));
		assertNotNull(DesignSpec.outDirProblem("/agentcraft/blueprints"));
		assertNotNull(DesignSpec.outDirProblem(""));
	}

	@Test
	void presets() {
		assertArrayEquals(new int[] {24, 14, 24}, DesignSpec.preset("S", "single", 1));
		assertArrayEquals(new int[] {36, 16, 36}, DesignSpec.preset("m", "single", 1));
		assertArrayEquals(new int[] {56, 18, 40}, DesignSpec.preset("L", "single", 1));
		// the sim copies the workshop (29x15x32) for one repo: S is too small (the failure path), M fits
		int[] s = DesignSpec.preset("S", "single", 1);
		assertFalse(s[0] >= 29 && s[1] >= 15 && s[2] >= 32);
		int[] m = DesignSpec.preset("M", "single", 1);
		assertTrue(m[0] >= 29 && m[1] >= 15 && m[2] >= 32);
		// group M fits the bundled campus2..5 (63/77/91/105 x 18 x 34), which the sim copies
		int[][] campus = {{63, 18, 34}, {77, 18, 34}, {91, 18, 34}, {105, 18, 34}};
		for (int n = 2; n <= 5; n++) {
			int[] g = DesignSpec.preset("M", "group", n);
			int[] c = campus[n - 2];
			assertTrue(g[0] >= c[0] && g[1] >= c[1] && g[2] >= c[2], "M for " + n + " wings");
		}
		for (String size : DesignSpec.SIZES) {
			for (int n = 2; n <= 8; n++) {
				int[] g = DesignSpec.preset(size, "group", n);
				assertTrue(DesignSpec.validate(draft("group", n, g[0], g[1], g[2])).isEmpty(), size + " " + n);
			}
			int[] one = DesignSpec.preset(size, "single", 1);
			assertTrue(DesignSpec.validate(draft("single", 1, one[0], one[1], one[2])).isEmpty(), size);
		}
	}

	@Test
	void plotFromCorners() {
		DesignSpec.Plot p = DesignSpec.Plot.of(110, 65, -20, 80, 64, 10, 16, "south", "minecraft:overworld");
		assertEquals(80, p.minX());
		assertEquals(64, p.y()); // the lower corner
		assertEquals(-20, p.minZ());
		assertEquals(31, p.dx());
		assertEquals(31, p.dz());
		assertEquals(110, p.maxX());
		assertEquals(10, p.maxZ());
	}

	@Test
	void plotMaxSizeIsInTheTemplatesFrame() {
		// 40 wide (x) by 30 deep (z), entrance south: template x = 40, z = 30
		DesignSpec.Plot south = new DesignSpec.Plot(0, 64, 0, 40, 30, 16, "south", "d");
		assertArrayEquals(new int[] {40, 16, 30}, south.maxSize());
		// the same rectangle fronting east: the entrance side runs along z
		DesignSpec.Plot east = new DesignSpec.Plot(0, 64, 0, 40, 30, 20, "east", "d");
		assertArrayEquals(new int[] {30, 20, 40}, east.maxSize());
		// clamped to the request limits
		DesignSpec.Plot tiny = new DesignSpec.Plot(0, 64, 0, 5, 200, 99, "north", "d");
		assertArrayEquals(new int[] {9, 48, 128}, tiny.maxSize());
		assertTrue(tiny.tooSmall());
		assertTrue(tiny.tooLarge());
		assertFalse(south.tooSmall() || south.tooLarge());
	}

	@Test
	void placementOnAPlot() {
		// workshop: 29 x 15 x 32, front south, ground row 1
		DesignSpec.Plot south = new DesignSpec.Plot(100, 64, 200, 31, 36, 16, "south", "d");
		int[] p = south.placement("south", 29, 32, 1);
		assertArrayEquals(new int[] {101, 63, 202, 0}, p); // centred, no turn, ground row on the surface
		assertTrue(south.fits("south", 29, 32));
		// fronting west: a south-facing template turns once clockwise (south -> west); its rotated size is 32 x 29
		DesignSpec.Plot west = new DesignSpec.Plot(100, 64, 200, 36, 31, 16, "west", "d");
		int[] w = west.placement("south", 29, 32, 1);
		assertEquals(1, w[3]);
		assertEquals("west", BlueprintTransform.rotateDirection("south", w[3]));
		assertEquals(100 + (36 - 32) / 2, w[0]);
		assertEquals(200 + (31 - 29) / 2, w[2]);
		assertTrue(west.fits("south", 29, 32));
		// the plot's max size, used as the design's limit, always admits a design that respects it
		int[] m = west.maxSize();
		assertTrue(west.fits("south", m[0], m[2]));
		assertTrue(south.fits("south", south.maxSize()[0], south.maxSize()[2]));
		// too big for the plot: still centred (overhangs), and fits() says so
		DesignSpec.Plot small = new DesignSpec.Plot(0, 70, 0, 20, 20, 16, "north", "d");
		int[] o = small.placement("south", 29, 32, 1);
		assertEquals(2, o[3]);
		assertEquals(Math.floorDiv(20 - 29, 2), o[0]);
		assertFalse(small.fits("south", 29, 32));
	}
}
