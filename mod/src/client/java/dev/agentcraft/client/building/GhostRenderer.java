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
 * <li>terrain fit ({@code TerrainFit}): the foundation the server adds below the floor in stone grey, natural
 * terrain it clears above the ground row as a pale wash; fluids in and next to the footprint: water blue, lava
 * bright amber (lava refuses);</li>
 * <li>the entrance approach ({@code Approach}): its path and half-step slabs in tan, its fill and cut with the
 * foundation and cleared terrain;</li>
 * <li>the outline of the ghost's footprint ({@link GhostModel#outline}) as thin bars: sage when placement
 * would go ahead, red when it would be refused (plus the whole reserved box, faintly, when the refusal is
 * an overlap or the player standing in it), and a brass bar along the front-most entrance face at ground
 * level.</li>
 * </ul>
 * Cubes are inflated slightly so their faces never z-fight with the world faces they coincide with.
 * Coordinates are camera-relative (world minus camera in double, then float), on an identity pose.
 */
final class GhostRenderer {
	static final int GHOST_ALPHA = 0x5A; // ~35 %
	static final int OBSTRUCTED = 0x70E0782A;
	static final int BLOCKED = 0xB8E0302A;
	static final int FOUNDATION = 0x78989490;
	static final int CLEARED = 0x30F4EBD8;
	static final int WATER = 0x803C78E6;
	static final int LAVA = 0xC8FFB000;
	static final int PATH = 0x90C8A060;
	/** Site warnings ({@code SiteWarnings}): water, lava, drops, cave openings in front of the door, gullies and caves under the path. */
	static final int HAZARD = 0x90D040C8;
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

	/**
	 * The plot being marked ({@link PlotMarker}): before the first corner, the looked-at cell; then a
	 * translucent rectangle on the ground from the first corner to the looked-at one, its outline (brass
	 * along the entrance side) and faint posts and a top outline at the height limit.
	 */
	static void submitPlot(LevelRenderContext ctx) {
		PlotMarker.View v = PlotMarker.view();
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (v == null || cam == null) {
			return;
		}
		dev.agentcraft.building.DesignSpec.Plot p = v.plot(PlotMarker.dimension());
		boolean single = v.first() == null;
		float x0 = (float) (p.minX() - cam.x);
		float z0 = (float) (p.minZ() - cam.z);
		float x1 = x0 + p.dx();
		float z1 = z0 + p.dz();
		float y = (float) (p.y() - cam.y);
		float top = y + p.height();
		boolean odd = p.tooSmall() || p.tooLarge();
		// cream and clay read on grass, sand and stone alike (sage vanished on grass)
		int fill = UiStyle.withAlpha(odd ? 0xFFE0782A : UiStyle.CREAM, single ? 0x70 : 0x50);
		int edge = UiStyle.withAlpha(odd ? 0xFFE0782A : UiStyle.CLAY, 0xF0);
		int faint = UiStyle.withAlpha(UiStyle.CREAM, 0x90);
		int brass = UiStyle.withAlpha(UiStyle.BRASS, 0xFF);
		// lines keep a few pixels of width from far away: thickness grows with the distance to the plot
		double nx = Math.max(p.minX(), Math.min(p.minX() + p.dx(), cam.x));
		double nz = Math.max(p.minZ(), Math.min(p.minZ() + p.dz(), cam.z));
		float dist = (float) Math.sqrt((nx - cam.x) * (nx - cam.x) + (p.y() - cam.y) * (p.y() - cam.y) + (nz - cam.z) * (nz - cam.z));
		float t = Math.max(EDGE * 2, dist * 0.004f);
		String front = p.front();
		ctx.submitNodeCollector().submitCustomGeometry(new PoseStack(), RenderTypes.debugFilledBox(), (pose, vc) -> {
			// the ground rectangle: a thin slab just above the surface
			cube(pose, vc, x0, y + 0.02f, z0, x1, y + 0.06f, z1, fill, 0x3, false);
			// outline at ground level
			cube(pose, vc, x0 - t, y, z0 - t, x1 + t, y + 2 * t, z0 + t, edge, 0x3F, false);
			cube(pose, vc, x0 - t, y, z1 - t, x1 + t, y + 2 * t, z1 + t, edge, 0x3F, false);
			cube(pose, vc, x0 - t, y, z0 - t, x0 + t, y + 2 * t, z1 + t, edge, 0x3F, false);
			cube(pose, vc, x1 - t, y, z0 - t, x1 + t, y + 2 * t, z1 + t, edge, 0x3F, false);
			if (single) {
				return;
			}
			// the entrance side
			float b = t * 2.2f;
			switch (front) {
				case "north" -> cube(pose, vc, x0, y, z0 - b, x1, y + 2 * b, z0 + b, brass, 0x3F, false);
				case "south" -> cube(pose, vc, x0, y, z1 - b, x1, y + 2 * b, z1 + b, brass, 0x3F, false);
				case "west" -> cube(pose, vc, x0 - b, y, z0, x0 + b, y + 2 * b, z1, brass, 0x3F, false);
				default -> cube(pose, vc, x1 - b, y, z0, x1 + b, y + 2 * b, z1, brass, 0x3F, false);
			}
			// the height limit: corner posts and the top outline, faintly
			float ft = t * 0.6f;
			for (float px : new float[] {x0, x1}) {
				for (float pz : new float[] {z0, z1}) {
					cube(pose, vc, px - ft, y, pz - ft, px + ft, top, pz + ft, faint, 0x3F, false);
				}
			}
			cube(pose, vc, x0 - ft, top - ft, z0 - ft, x1 + ft, top + ft, z0 + ft, faint, 0x3F, false);
			cube(pose, vc, x0 - ft, top - ft, z1 - ft, x1 + ft, top + ft, z1 + ft, faint, 0x3F, false);
			cube(pose, vc, x0 - ft, top - ft, z0 - ft, x0 + ft, top + ft, z1 + ft, faint, 0x3F, false);
			cube(pose, vc, x1 - ft, top - ft, z0 - ft, x1 + ft, top + ft, z1 + ft, faint, 0x3F, false);
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
		quads += cells(pose, vc, v.fill(), FOUNDATION, 0.008f, cx, cy, cz);
		quads += cells(pose, vc, v.clear(), CLEARED, 0.006f, cx, cy, cz);
		quads += cells(pose, vc, v.path(), PATH, 0.009f, cx, cy, cz);
		quads += cells(pose, vc, v.water(), WATER, 0.01f, cx, cy, cz);
		quads += cells(pose, vc, v.lava(), LAVA, 0.02f, cx, cy, cz);
		quads += cells(pose, vc, v.hazards(), HAZARD, 0.015f, cx, cy, cz);
		quads += cells(pose, vc, v.obstructed(), OBSTRUCTED, 0.012f, cx, cy, cz);
		quads += cells(pose, vc, v.blocked(), BLOCKED, 0.03f, cx, cy, cz);

		// the outline of what is drawn (GhostModel.outline: the footprint's perimeter, not the template's box,
		// whose corners can be cells the template never writes, e.g. beside the studio's porch)
		boolean refused = !v.refusals().isEmpty();
		int edge = refused ? UiStyle.withAlpha(0xFFD0402A, 0xE8) : UiStyle.withAlpha(UiStyle.SAGE, 0xE0);
		float t = EDGE;
		for (GhostModel.Edge e : m.outline()) {
			quads += bar(pose, vc, bx, by, bz, e, t, edge);
		}
		// the whole box place() reserves, faintly, when a refusal is about the box itself (an overlap, the
		// player standing in it, or block entities, which place() refuses anywhere in the box), so a corner the
		// footprint leaves out still explains the refusal
		if (refused && (v.blockedCount() > 0 || v.playerInside() || v.refusals().stream().anyMatch(r -> r.startsWith("overlaps") || r.startsWith("pets")
			|| r.startsWith("in the box") || r.startsWith("dropped items")))) {
			int faint = UiStyle.withAlpha(0xFFD0402A, 0x60);
			// the snapshot box: the template's box with the foundation below and the entrance approach in front
			dev.agentcraft.layout.Anchors.Bounds sb = v.snapBox();
			float w = sb.maxX() - sb.minX() + 1;
			float h = sb.maxY() - sb.minY() + 1;
			float d = sb.maxZ() - sb.minZ() + 1;
			float qx = (float) (sb.minX() - cx);
			float qy = (float) (sb.minY() - cy);
			float qz = (float) (sb.minZ() - cz);
			float ft = t * 0.5f;
			for (int a = 0; a <= 1; a++) {
				for (int b = 0; b <= 1; b++) {
					float ya = qy + a * h;
					quads += cube(pose, vc, qx - ft, ya - ft, qz + b * d - ft, qx + w + ft, ya + ft, qz + b * d + ft, faint, 0x3F, false);
					quads += cube(pose, vc, qx + b * w - ft, ya - ft, qz - ft, qx + b * w + ft, ya + ft, qz + d + ft, faint, 0x3F, false);
					quads += cube(pose, vc, qx + a * w - ft, qy - ft, qz + b * d - ft, qx + a * w + ft, qy + h + ft, qz + b * d + ft, faint, 0x3F, false);
				}
			}
		}
		// the entrance side: a brass bar at the ground row (feet level) along the footprint's front-most face
		int brass = UiStyle.withAlpha(UiStyle.BRASS, 0xF0);
		for (GhostModel.Edge e : m.frontEdges(v.front())) {
			quads += bar(pose, vc, bx, by, bz, e, t * 2.2f, brass);
		}
		return quads;
	}

	/** A straight model edge (rotated-local corner coordinates) as a thin bar of half-thickness {@code t}. */
	private static int bar(PoseStack.Pose pose, VertexConsumer vc, float bx, float by, float bz, GhostModel.Edge e, float t, int argb) {
		float x0 = bx + Math.min(e.x0(), e.x1());
		float x1 = bx + Math.max(e.x0(), e.x1());
		float y0 = by + Math.min(e.y0(), e.y1());
		float y1 = by + Math.max(e.y0(), e.y1());
		float z0 = bz + Math.min(e.z0(), e.z1());
		float z1 = bz + Math.max(e.z0(), e.z1());
		return cube(pose, vc, x0 - t, y0 - t, z0 - t, x1 + t, y1 + t, z1 + t, argb, 0x3F, false);
	}

	/** The exposed faces of world cells given as (x, y, z, face mask) quadruples. */
	private static int cells(PoseStack.Pose pose, VertexConsumer vc, int[] cells, int argb, float grow, double cx, double cy, double cz) {
		int n = 0;
		for (int i = 0; i + 3 < cells.length; i += 4) {
			float x = (float) (cells[i] - cx);
			float y = (float) (cells[i + 1] - cy);
			float z = (float) (cells[i + 2] - cz);
			n += cube(pose, vc, x - grow, y - grow, z - grow, x + 1 + grow, y + 1 + grow, z + 1 + grow, argb, cells[i + 3], true);
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
