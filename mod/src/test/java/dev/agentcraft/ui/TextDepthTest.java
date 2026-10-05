package dev.agentcraft.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextDepthTest {
	static final double EPS = 1e-6;

	@Test
	void aBillboardLiftsAlongPlusZTowardsTheCamera() {
		// a plate 10 blocks in front of the camera (camera looks down -z), billboard local z faces the camera (+z), 1 px = 0.025 blocks
		float lift = TextDepth.liftZ(0, 0, -10, 0, 0, 0.025);
		assertEquals(0.0003 * 10 / 0.025, lift, 1e-4); // 0.12 px
		assertTrue(lift > 0);
		// the same lift in blocks wherever the camera looks from (a rotated view: same length, same sign)
		double c = Math.cos(0.7);
		double s = Math.sin(0.7);
		float rotated = TextDepth.liftZ(-10 * s, 0, -10 * c, 0.025 * s, 0, 0.025 * c);
		assertEquals(lift, rotated, 1e-4);
	}

	@Test
	void aFaceWhoseViewerStandsAtMinusZLiftsAlongMinusZ() {
		// a north-facing screen 6 blocks south of the camera: local z points south (away from the viewer)
		float lift = TextDepth.liftZ(0, 0, 6, 0, 0, 1);
		assertEquals(-0.0018, lift, EPS);
		// off-axis: the distance is the whole camera-relative offset
		assertEquals(-0.0003 * Math.sqrt(0.09 + 0.04 + 36), TextDepth.liftZ(0.3, -0.2, 6, 0, 0, 1), 1e-7);
	}

	@Test
	void aScaledPoseLiftsTheSameDistanceInBlocks() {
		double dist = 20;
		for (double scale : new double[] {0.01, 0.025, 0.74 * 0.025, 1, 3}) {
			float local = TextDepth.liftZ(0, 0, -dist, 0, 0, scale);
			assertEquals(TextDepth.FRACTION * dist, local * scale, 1e-7);
		}
		// degenerate poses do not move anything
		assertEquals(0f, TextDepth.liftZ(0, 0, 0, 0, 0, 1));
		assertEquals(0f, TextDepth.liftZ(0, 0, -5, 0, 0, 0));
	}

	@Test
	void theNameplateFormulaIsTheSame() {
		// Nameplate.textLift: FRACTION x distance / (PX x scale), plate space +z faces the camera
		double dist = 7.5;
		double px = 0.025 * 1.3;
		assertEquals(TextDepth.FRACTION * dist / px, TextDepth.liftZ(3, 1, -Math.sqrt(dist * dist - 10), 0, 0, px), 1e-4);
	}

	@Test
	void faceDisplaysStretchTheirLayersUpToTheFrame() {
		float step = 0.0012f;
		// close up the layers stay as designed
		assertEquals(1f, TextDepth.faceDepthScale(1, 0.5f * step, 7 * step, 0.04f));
		// at 6 blocks the half-step gap needs 0.0018: x3
		assertEquals(3f, TextDepth.faceDepthScale(6, 0.5f * step, 7 * step, 0.04f), 1e-5);
		// far away the stack stops at the frame: 0.04 / 0.0084
		float far = TextDepth.faceDepthScale(64, 0.5f * step, 7 * step, 0.04f);
		assertEquals(0.04f / (7 * step), far, 1e-4);
		assertTrue(far * 7 * step <= 0.04f + 1e-6);
		// a stack already deeper than the room is never squeezed
		assertEquals(1f, TextDepth.faceDepthScale(64, step, 0.05f, 0.04f));
		assertEquals(1f, TextDepth.faceDepthScale(10, 0f, 0.01f, 0.04f));
	}
}
