package dev.agentcraft.ui;

/**
 * How far in-world text sits in front of its own background (pure, unit-tested in {@code TextDepthTest}). Text and its
 * plate/paper/card are separate quads; on one plane the depth test drops whole glyphs (the font's polygon offset does
 * not separate opaque text: it is drawn in the solid pass), so the text gets a real lift towards the camera: a fixed
 * fraction ({@link #FRACTION}) of the camera distance, the nameplate fix ("Marlow" read "M r o"), applied to every
 * world UI text.
 * <ul>
 *   <li>Billboards and free-standing cards ({@link #liftZ}): the lift along the pose's local z, towards the camera,
 *       computed from the pose itself (its translation = the camera-relative origin, its z column = local z).</li>
 *   <li>Face displays (monitors, task boards, the village board, the console) already stack their layers in small
 *       steps; {@link #faceDepthScale} stretches the whole stack so its smallest text-over-background gap is at least
 *       the same fraction of the distance, capped so the front layer stays behind the block's bezel or frame.</li>
 * </ul>
 */
public final class TextDepth {
	/** Text lift as a fraction of the camera distance (the nameplates' {@code TEXT_LIFT}; under PlateLayout's 0.0015 rank nudge). */
	public static final float FRACTION = 0.0003f;

	private TextDepth() {
	}

	/** {@link #FRACTION} of a camera distance, in blocks. */
	public static double liftBlocks(double distance) {
		return distance <= 0 ? 0 : FRACTION * distance;
	}

	/**
	 * The local z translation that moves what is drawn next {@link #FRACTION} x the camera distance towards the camera.
	 * {@code (tx, ty, tz)}: the pose's translation (where its origin is, relative to the camera, in blocks; any rotation
	 * of the view does not change the result); {@code (zx, zy, zz)}: the pose's local z axis in the same space (its length
	 * is blocks per local unit). The sign follows whichever way local z faces the camera (billboards: +z, face displays
	 * with the viewer at -z: -z). 0 for a degenerate pose.
	 */
	public static float liftZ(double tx, double ty, double tz, double zx, double zy, double zz) {
		double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
		double len = Math.sqrt(zx * zx + zy * zy + zz * zz);
		if (dist <= 1e-6 || len <= 1e-9) {
			return 0f;
		}
		// towards the camera is -t: +z faces it when z . (-t) >= 0
		double towards = -(tx * zx + ty * zy + tz * zz);
		double sign = towards >= 0 ? 1 : -1;
		return (float) (sign * liftBlocks(dist) / len);
	}

	/**
	 * The factor a face display's layer depths are stretched by at {@code distance}: enough that its smallest gap
	 * between text and the background right under it ({@code minGap}, blocks) is at least {@link #FRACTION} of the
	 * distance, at least 1 (the layers as designed), and at most what keeps the whole stack ({@code stackDepth}, blocks
	 * from the drawing plane to the frontmost layer) within {@code maxDepth} (the room in front of the surface before
	 * the block's bezel or frame).
	 */
	public static float faceDepthScale(double distance, float minGap, float stackDepth, float maxDepth) {
		if (minGap <= 0 || stackDepth <= 0) {
			return 1f;
		}
		double want = liftBlocks(distance) / minGap;
		double cap = Math.max(1.0, maxDepth / stackDepth);
		return (float) Math.max(1.0, Math.min(cap, want));
	}
}
