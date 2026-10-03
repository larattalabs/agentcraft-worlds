package dev.agentcraft.client.building;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.agentcraft.building.GhostModel;
import dev.agentcraft.client.ui.UiStyle;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the placement ghost in the level ({@code LevelRenderEvents.COLLECT_SUBMITS}): one
 * {@code submitCustomGeometry} call per frame with vanilla's translucent debug-box type
 * (POSITION_COLOR quads, blended, depth-tested, no depth write, no culling):
 * <ul>
 * <li>every exposed face of a visible template cell, tinted with the block's map colour at ~35 %
 * alpha, shaded by face direction so the shape reads;</li>
 * <li>cells that would replace a solid world block at or above the ground row in orange, block
 * entities that block placement in strong red (whole cubes, drawn a little larger);</li>
 * <li>the box's edges as thin bars: sage when placement would go ahead, red when it would be refused,
 * and a brass bar along the entrance side at ground level.</li>
 * </ul>
 * Cubes are inflated slightly so their faces never z-fight with the world faces they coincide with.
 * Coordinates are camera-relative (world minus camera in double, then float), on an identity pose.
 */
final class GhostRenderer {
	static final int GHOST_ALPHA = 0x5A; // ~35 %
	static final int OBSTRUCTED = 0x70E0782A;
	static final int BLOCKED = 0xB8E0302A;
	static final float INFLATE = 0.005f;
	static final float EDGE = 0.045f;

	static volatile int lastQuads;
	static volatile long lastNanos;
	static volatile long maxNanos;
	static volatile long frames;

	private static final float[] FACE_SHADE = {0.62f, 1.0f, 0.86f, 0.86f, 0.74f, 0.74f};

	private GhostRenderer() {
	}

	static void submit(LevelRenderContext ctx) {
		BuildPlacement.View v = BuildPlacement.view();
		if (v == null) {
			return;
		}
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (cam == null) {
			return;
		}
		// the ghost's origin relative to the camera (double first: world coordinates can be large)
		float bx = (float) (v.ox() - cam.x);
		float by = (float) (v.oy() - cam.y);
		float bz = (float) (v.oz() - cam.z);
		double cx = cam.x;
		double cy = cam.y;
		double cz = cam.z;
		ctx.submitNodeCollector().submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(), (pose, vc) -> {
			long t0 = System.nanoTime();
			int quads = draw(pose, vc, v, bx, by, bz, cx, cy, cz);
			long dt = System.nanoTime() - t0;
			lastQuads = quads;
			lastNanos = dt;
			maxNanos = Math.max(maxNanos, dt);
			frames++;
		});
	}

	private static int draw(PoseStack.Pose pose, VertexConsumer vc, BuildPlacement.View v, float bx, float by, float bz, double cx, double cy,
		double cz) {
		GhostModel m = v.model();
		int quads = 0;
		for (int i = 0; i < m.count(); i++) {
			int faces = m.faces(i);
			if (faces == 0) {
				continue;
			}
			int base = (GHOST_ALPHA << 24) | (m.argb(i) & 0xFFFFFF);
			float x = bx + m.x(i);
			float y = by + m.y(i);
			float z = bz + m.z(i);
			quads += cube(pose, vc, x - INFLATE, y - INFLATE, z - INFLATE, x + 1 + INFLATE, y + 1 + INFLATE, z + 1 + INFLATE, base, faces, true);
		}
		quads += cells(pose, vc, v.obstructed(), OBSTRUCTED, 0.012f, cx, cy, cz);
		quads += cells(pose, vc, v.blocked(), BLOCKED, 0.03f, cx, cy, cz);

		// box edges
		int edge = v.refusals().isEmpty() ? UiStyle.withAlpha(UiStyle.SAGE, 0xE0) : UiStyle.withAlpha(0xFFD0402A, 0xE8);
		float w = m.sizeX;
		float h = m.sizeY;
		float d = m.sizeZ;
		float t = EDGE;
		for (int a = 0; a <= 1; a++) {
			for (int b = 0; b <= 1; b++) {
				float ya = by + a * h;
				float zb = bz + b * d;
				float xb = bx + b * w;
				float za = bz + a * d;
				// along x (y, z corners), along z (y, x corners), along y (x, z corners)
				quads += cube(pose, vc, bx - t, ya - t, zb - t, bx + w + t, ya + t, zb + t, edge, 0x3F, false);
				quads += cube(pose, vc, xb - t, ya - t, bz - t, xb + t, ya + t, bz + d + t, edge, 0x3F, false);
				quads += cube(pose, vc, bx + a * w - t, by - t, bz + b * d - t, bx + a * w + t, by + h + t, bz + b * d + t, edge, 0x3F, false);
			}
		}
		// the entrance side: a brass bar at the ground row (feet level) along the front face
		float gy = by + m.groundY;
		int brass = UiStyle.withAlpha(UiStyle.BRASS, 0xF0);
		float tt = t * 2.2f;
		quads += switch (v.front()) {
			case "north" -> cube(pose, vc, bx, gy - tt, bz - tt, bx + w, gy + tt, bz + tt, brass, 0x3F, false);
			case "south" -> cube(pose, vc, bx, gy - tt, bz + d - tt, bx + w, gy + tt, bz + d + tt, brass, 0x3F, false);
			case "west" -> cube(pose, vc, bx - tt, gy - tt, bz, bx + tt, gy + tt, bz + d, brass, 0x3F, false);
			default -> cube(pose, vc, bx + w - tt, gy - tt, bz, bx + w + tt, gy + tt, bz + d, brass, 0x3F, false);
		};
		return quads;
	}

	/** Whole cubes at world cells {@code xyz} (x, y, z triples). */
	private static int cells(PoseStack.Pose pose, VertexConsumer vc, int[] xyz, int argb, float grow, double cx, double cy, double cz) {
		int n = 0;
		for (int i = 0; i + 2 < xyz.length && i < BuildPlacement.MAX_DRAWN_CONFLICTS * 3; i += 3) {
			float x = (float) (xyz[i] - cx);
			float y = (float) (xyz[i + 1] - cy);
			float z = (float) (xyz[i + 2] - cz);
			n += cube(pose, vc, x - grow, y - grow, z - grow, x + 1 + grow, y + 1 + grow, z + 1 + grow, argb, 0x3F, true);
		}
		return n;
	}

	/** The faces in {@code mask} (bit order down up north south west east) of a box; returns the quads drawn. */
	private static int cube(PoseStack.Pose p, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1, int argb, int mask,
		boolean shade) {
		int n = 0;
		if ((mask & 1) != 0) { // down
			int c = shade ? shade(argb, FACE_SHADE[0]) : argb;
			quad(p, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, c);
			n++;
		}
		if ((mask & 2) != 0) { // up
			int c = shade ? shade(argb, FACE_SHADE[1]) : argb;
			quad(p, vc, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, c);
			n++;
		}
		if ((mask & 4) != 0) { // north
			int c = shade ? shade(argb, FACE_SHADE[2]) : argb;
			quad(p, vc, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, c);
			n++;
		}
		if ((mask & 8) != 0) { // south
			int c = shade ? shade(argb, FACE_SHADE[3]) : argb;
			quad(p, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, c);
			n++;
		}
		if ((mask & 16) != 0) { // west
			int c = shade ? shade(argb, FACE_SHADE[4]) : argb;
			quad(p, vc, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, c);
			n++;
		}
		if ((mask & 32) != 0) { // east
			int c = shade ? shade(argb, FACE_SHADE[5]) : argb;
			quad(p, vc, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, c);
			n++;
		}
		return n;
	}

	private static void quad(PoseStack.Pose p, VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy,
		float cz, float dx, float dy, float dz, int argb) {
		vc.addVertex(p, ax, ay, az).setColor(argb);
		vc.addVertex(p, bx, by, bz).setColor(argb);
		vc.addVertex(p, cx, cy, cz).setColor(argb);
		vc.addVertex(p, dx, dy, dz).setColor(argb);
	}

	private static int shade(int argb, float f) {
		return TemplateCells.shade(argb, f);
	}
}
