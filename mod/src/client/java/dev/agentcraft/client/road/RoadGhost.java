package dev.agentcraft.client.road;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.agentcraft.building.RoadPlan;
import dev.larattalabs.labui.client.ui.UiStyle;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * The road preview's ghost ({@code LevelRenderEvents.COLLECT_SUBMITS}), in the placement ghost's colours: the new road
 * surface, half steps and bridge decks in tan, cells it clears (plants, snow, leaves) in orange, road cells it leaves out
 * (in the way, the player's blocks, water without a bridge) in red at their feet, and the lantern posts in brass. One
 * {@code submitCustomGeometry} call per frame with vanilla's translucent debug-box type; coordinates camera-relative.
 */
final class RoadGhost {
	static final int PATH = 0x90C8A060;
	static final int CLEARED = 0x70E0782A;
	static final int REFUSED = 0xB8E0302A;
	static final float GROW = 0.01f;

	static volatile int lastQuads;
	static volatile long lastNanos;
	static volatile long frames;

	private RoadGhost() {
	}

	static void submit(LevelRenderContext ctx) {
		RoadsFeature.Preview pv = RoadsFeature.preview();
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (pv == null || cam == null) {
			return;
		}
		RoadPlan.Plan p = pv.plan();
		double cx = cam.x;
		double cy = cam.y;
		double cz = cam.z;
		int brass = UiStyle.withAlpha(UiStyle.BRASS, 0xD0);
		ctx.submitNodeCollector().submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(), (pose, vc) -> {
			long t0 = System.nanoTime();
			int quads = 0;
			for (RoadPlan.Op op : p.ops()) {
				float x = (float) (op.x() - cx);
				float y = (float) (op.y() - cy);
				float z = (float) (op.z() - cz);
				switch (op.block()) {
					case AIR -> quads += cube(pose, vc, x - GROW, y - GROW, z - GROW, x + 1 + GROW, y + 1 + GROW, z + 1 + GROW, CLEARED);
					case PATH_SLAB, STONE_SLAB, DECK -> quads += cube(pose, vc, x - GROW, y - GROW, z - GROW, x + 1 + GROW, y + 0.5f + GROW, z + 1 + GROW, PATH);
					case FENCE -> quads += cube(pose, vc, x + 0.375f, y, z + 0.375f, x + 0.625f, y + 1.5f, z + 0.625f, brass);
					case LANTERN -> quads += cube(pose, vc, x + 0.3f, y, z + 0.3f, x + 0.7f, y + 0.6f, z + 0.7f, brass);
					default -> quads += cube(pose, vc, x - GROW, y - GROW, z - GROW, x + 1 + GROW, y + 1 + GROW, z + 1 + GROW, PATH);
				}
			}
			int[] r = p.refused();
			for (int i = 0; i + 2 < r.length; i += 3) {
				float x = (float) (r[i] - cx);
				float y = (float) (r[i + 1] - cy);
				float z = (float) (r[i + 2] - cz);
				quads += cube(pose, vc, x + 0.1f, y + 0.02f, z + 0.1f, x + 0.9f, y + 0.12f, z + 0.9f, REFUSED);
			}
			if (p.refusal() != null) {
				// nothing may be laid: the route itself in red
				long[] route = pv.route();
				for (long c : route) {
					float x = (float) (dev.agentcraft.walk.WalkCell.unpackX(c) - cx);
					float y = (float) (dev.agentcraft.walk.WalkCell.unpackY(c) - cy);
					float z = (float) (dev.agentcraft.walk.WalkCell.unpackZ(c) - cz);
					quads += cube(pose, vc, x + 0.1f, y + 0.02f, z + 0.1f, x + 0.9f, y + 0.12f, z + 0.9f, REFUSED);
				}
			}
			lastQuads = quads;
			lastNanos = System.nanoTime() - t0;
			frames++;
		});
	}

	private static final float[] SHADE = {0.62f, 1.0f, 0.86f, 0.86f, 0.74f, 0.74f};

	/** A box's six faces, shaded by direction; returns the quads drawn. */
	private static int cube(PoseStack.Pose p, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
		quad(p, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, shade(argb, SHADE[0]));
		quad(p, vc, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, shade(argb, SHADE[1]));
		quad(p, vc, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, shade(argb, SHADE[2]));
		quad(p, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, shade(argb, SHADE[3]));
		quad(p, vc, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, shade(argb, SHADE[4]));
		quad(p, vc, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, shade(argb, SHADE[5]));
		return 6;
	}

	private static void quad(PoseStack.Pose p, VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy,
		float cz, float dx, float dy, float dz, int argb) {
		vc.addVertex(p, ax, ay, az).setColor(argb);
		vc.addVertex(p, bx, by, bz).setColor(argb);
		vc.addVertex(p, cx, cy, cz).setColor(argb);
		vc.addVertex(p, dx, dy, dz).setColor(argb);
	}

	private static int shade(int argb, float f) {
		int a = argb >>> 24;
		int r = (int) (((argb >> 16) & 0xFF) * f);
		int g = (int) (((argb >> 8) & 0xFF) * f);
		int b = (int) ((argb & 0xFF) * f);
		return a << 24 | r << 16 | g << 8 | b;
	}
}
