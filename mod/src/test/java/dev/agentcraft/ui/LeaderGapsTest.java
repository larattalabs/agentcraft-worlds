package dev.agentcraft.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LeaderGapsTest {
	static final int MAX = 4;

	/** No drawn piece of the line may touch any rectangle the line passes (the property the renderer relies on). */
	static void assertClear(float x, float top, float bottom, float[] rects, int count, float[] gaps, int g) {
		float[] seg = LeaderGaps.segments(top, bottom, gaps, g);
		for (int s = 0; s < seg.length; s += 2) {
			for (int r = 0; r < count; r++) {
				int o = 4 * r;
				boolean inX = x >= rects[o] - 1f && x <= rects[o + 2] + 1f;
				boolean inY = seg[s] < rects[o + 3] && seg[s + 1] > rects[o + 1];
				assertTrue(!(inX && inY), "segment " + seg[s] + ".." + seg[s + 1] + " crosses rect " + r);
			}
		}
		for (int i = 1; i < g; i++) {
			assertTrue(gaps[2 * i] > gaps[2 * i - 1], "gaps sorted and apart");
		}
	}

	@Test
	void cutsAroundPlatesOnTheLineOnly() {
		float[] rects = {
			-20, 10, 20, 30, // on the line
			40, 0, 80, 100, // beside it
			-5, 200, 5, 220 // below the line's end
		};
		float[] out = new float[2 * MAX];
		int g = LeaderGaps.compute(0, 0, 100, rects, 3, 1f, 1.5f, MAX, out);
		assertEquals(1, g);
		assertEquals(8.5f, out[0]);
		assertEquals(31.5f, out[1]);
		assertArrayEquals(new float[] {0, 8.5f, 31.5f, 100}, LeaderGaps.segments(0, 100, out, g));
	}

	@Test
	void overlappingPlatesMergeAndALineEndingInsideAPlateStops() {
		float[] rects = {-10, 20, 10, 40, -10, 35, 10, 60, -10, 90, 10, 140};
		float[] out = new float[2 * MAX];
		int g = LeaderGaps.compute(0, 0, 100, rects, 3, 1f, 1f, MAX, out);
		assertEquals(2, g);
		assertEquals(19f, out[0]);
		assertEquals(61f, out[1]);
		assertEquals(89f, out[2]);
		assertEquals(101f, out[3]); // to the line's end: nothing is drawn under the last plate
		assertClear(0, 0, 100, rects, 3, out, g);
	}

	@Test
	void moreCrossingsThanSlotsNeverCrossAPlate() {
		// six plates stacked on the line, apart from each other: four slots
		float[] rects = new float[24];
		for (int i = 0; i < 6; i++) {
			rects[4 * i] = -8;
			rects[4 * i + 1] = 10 + i * 30;
			rects[4 * i + 2] = 8;
			rects[4 * i + 3] = 25 + i * 30;
		}
		float[] out = new float[2 * MAX];
		int g = LeaderGaps.compute(0, 0, 200, rects, 6, 1f, 1f, MAX, out);
		assertEquals(MAX, g);
		assertClear(0, 0, 200, rects, 6, out, g);
		assertEquals(176f, out[7]); // the last gap reaches over the last plate
	}

	@Test
	void randomCrowdsNeverCrossAPlate() {
		Random rnd = new Random(42);
		for (int trial = 0; trial < 2000; trial++) {
			int n = rnd.nextInt(10);
			float[] rects = new float[4 * n];
			for (int i = 0; i < n; i++) {
				float x0 = rnd.nextFloat() * 60 - 40;
				float y0 = rnd.nextFloat() * 300 - 50;
				rects[4 * i] = x0;
				rects[4 * i + 1] = y0;
				rects[4 * i + 2] = x0 + 5 + rnd.nextFloat() * 50;
				rects[4 * i + 3] = y0 + 5 + rnd.nextFloat() * 40;
			}
			float top = rnd.nextFloat() * 100;
			float bottom = top + 10 + rnd.nextFloat() * 200;
			float[] out = new float[2 * MAX];
			int g = LeaderGaps.compute(0, top, bottom, rects, n, 1f, 1.5f, MAX, out);
			assertTrue(g <= MAX);
			assertClear(0, top, bottom, rects, n, out, g);
		}
	}

	@Test
	void nothingToCutForAShortOrEmptyLine() {
		float[] out = new float[2 * MAX];
		assertEquals(0, LeaderGaps.compute(0, 50, 50, new float[] {-5, 0, 5, 100}, 1, 1f, 1f, MAX, out));
		assertEquals(0, LeaderGaps.compute(0, 0, 50, new float[0], 0, 1f, 1f, MAX, out));
	}
}
