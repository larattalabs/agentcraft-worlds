package dev.agentcraft.client.village;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.block.entity.VillageBoardBlockEntity;
import dev.larattalabs.labui.client.monitor.DisplayDraw;
import dev.agentcraft.client.taskwall.TaskBoardRenderer;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.WorldUi;
import dev.agentcraft.client.world.StationRenderState;
import dev.agentcraft.client.world.StationRenderer;
import dev.larattalabs.labui.ui.Guard;
import dev.larattalabs.labui.ui.TextDepth;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import org.jspecify.annotations.Nullable;

/**
 * Village board BER (docs/VILLAGE.md V2): the whole village on the connected village_board panel, drawn once from its
 * origin block on the same plane as the task board's linen. A dark walnut slate with a cream header; one paper card per
 * building (name, lead portrait and name, repos, its active goal with a progress bar along the card's bottom, open PRs
 * and PRs merged this week); the newest milestones in a column on the right (a brass dot = its trophy hangs on a wall);
 * a clay banner while the agents are held; a footer saying what a right-click does. Pages turn every 10 s when the
 * buildings do not fit. The prepared drawing lives in {@link BoardView} and is rebuilt only when something changed.
 */
public class VillageBoardRenderer extends StationRenderer<VillageBoardBlockEntity, VillageBoardRenderer.State> {
	public static class State extends StationRenderState {
		@Nullable BoardView view;
		int light;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 96;
	}

	@Override
	protected void extractStation(VillageBoardBlockEntity be, State s, float partialTicks) {
		s.view = null;
		if (!s.panelOrigin) {
			return;
		}
		Guard.run("agentcraft_worlds.village.board.extract", () -> {
			BoardView v = VillageBoardFeature.view(be.getBlockPos(), s.panelWidth, s.panelHeight);
			s.view = v;
			s.light = light(be, s.facing, s.panelWidth, s.panelHeight);
		});
	}

	/** Brightest light just in front of the panel (centre row), with a floor so the board reads at night. */
	private static int light(VillageBoardBlockEntity be, Direction facing, int w, int h) {
		if (be.getLevel() == null) {
			return WorldUi.uiLight();
		}
		Direction right = facing.getCounterClockWise();
		int block = 0;
		int sky = 0;
		BlockPos base = be.getBlockPos().relative(facing).above(h / 2);
		for (int i = 0; i < w; i += Math.max(1, w / 3)) {
			int l = LightCoordsUtil.getLightCoords(be.getLevel(), base.relative(right, i));
			block = Math.max(block, LightCoordsUtil.block(l));
			sky = Math.max(sky, LightCoordsUtil.sky(l));
		}
		return LightCoordsUtil.pack(Math.max(block, VillageBoardFeature.lightFloor), sky);
	}

	@Override
	public void submit(State s, PoseStack ps, SubmitNodeCollector c, CameraRenderState camera) {
		BoardView v = s.view;
		if (!s.panelOrigin || v == null || v.ppb == 0) {
			return;
		}
		Guard.run("agentcraft_worlds.village.board.submit", () -> draw(s, v, ps, c));
	}

	private static void draw(State s, BoardView v, PoseStack ps, SubmitNodeCollector c) {
		int light = s.light;
		float z = BoardView.Z;
		ps.pushPose();
		toFace(ps, s.facing, TaskBoardRenderer.LINEN_DEPTH, v.ppb);
		ps.translate(0, -(s.panelHeight - 1) * v.ppb, 0);
		// text a hair in front of its card/slate at any distance (TextDepth): the layer steps (cards 2, text 3, the
		// progress bars 3.6) stretch with the camera distance, within 0.04 of the linen (the task board's frame)
		ps.scale(1f, 1f, TextDepth.faceDepthScale(WorldUi.eyeDistance(ps), -z, -3.6f * z, TaskBoardRenderer.MAX_STACK_DEPTH));
		c.order(0).submitCustomGeometry(ps, DisplayDraw.fill(), (pose, vc) -> v.rects.emit(pose, vc, 255, light));
		if (!v.cards.isEmpty()) {
			TextureAtlasSprite sprite = WorldUi.sprite(Kit.card("todo"));
			c.order(0).submitCustomGeometry(ps, WorldUi.guiAtlasSolid(), (pose, vc) -> {
				for (BoardView.Card k : v.cards) {
					DisplayDraw.nineSlice(pose, vc, sprite, k.x(), k.y(), k.w(), k.h(), 2 * z, 0xFFFFFFFF, light, 1);
				}
			});
			if (v.overCards.size() > 0) {
				c.order(0).submitCustomGeometry(ps, DisplayDraw.fill(), (pose, vc) -> v.overCards.emit(pose, vc, 255, light));
			}
		}
		ps.pushPose();
		ps.translate(0, 0, 3 * z);
		for (BoardView.Text t : v.texts) {
			WorldUi.submitText(ps, c, t.s(), t.x(), t.y(), t.color(), light);
		}
		for (BoardView.Text t : v.cardTexts) {
			WorldUi.submitText(ps, c, t.s(), t.x(), t.y(), t.color(), light);
		}
		for (BoardView.Pic p : v.pics) {
			if (p.dot()) {
				WorldUi.submitSprite(ps, c, WorldUi.Layer.SOLID, p.tex(), p.x(), p.y(), p.size(), p.size(), 0f, 0xFFFFFFFF, light);
			} else {
				DisplayDraw.submitTexture(ps, c, p.tex(), p.x(), p.y(), p.size(), p.size(), 0f, 0xFFFFFFFF, light);
			}
		}
		ps.popPose();
		ps.popPose();
	}
}
