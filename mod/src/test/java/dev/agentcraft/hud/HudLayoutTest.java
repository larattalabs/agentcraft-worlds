package dev.agentcraft.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.hud.HudLayout.Column;
import dev.agentcraft.hud.HudLayout.Env;
import dev.agentcraft.hud.HudLayout.Overlaps;
import dev.agentcraft.hud.HudLayout.Placement;
import dev.agentcraft.hud.HudLayout.Rect;
import dev.agentcraft.hud.HudSettings.Position;
import dev.agentcraft.hud.HudSettings.Size;
import org.junit.jupiter.api.Test;

class HudLayoutTest {
	/** 1278x720 at GUI scale 2, 3 (= "auto" for that window: 426x240) and 4; 1080p at 2-4. */
	static final int[][] GUIS = {{639, 360, 2}, {426, 240, 3}, {320, 180, 4}, {960, 540, 2}, {640, 360, 3}, {480, 270, 4}};
	/** Overlay sizes in overlay px: a pill, a peeking pill, Pill+, the panel. */
	static final int[][] CONTENT = {{96, 16}, {190, 16}, {180, 58}, {300, 70}};

	/** A busy screen: two boss bars, both effect rows, survival bars, the default chat, the connection pill. */
	static Env busy(int w, int h, int scale) {
		Env e = Env.of(w, h, scale).effects(2, 1).boss(2, 120).survival(true);
		Rect fx = HudLayout.effects(e);
		return e.withPill(new Rect(w - HudLayout.MARGIN - 92, fx.bottom() + HudLayout.GAP, 92, 18));
	}

	@Test
	void sizeStepsStayOnWholePixels() {
		assertEquals(2, HudLayout.effectivePx(Size.S, 2), "S never below 2 framebuffer px: at scale 2 it is M");
		assertEquals(2, HudLayout.effectivePx(Size.M, 2));
		assertEquals(3, HudLayout.effectivePx(Size.L, 2));
		assertEquals(2, HudLayout.effectivePx(Size.S, 3));
		assertEquals(3, HudLayout.effectivePx(Size.M, 3));
		assertEquals(4, HudLayout.effectivePx(Size.L, 3));
		assertEquals(3, HudLayout.effectivePx(Size.S, 4));
		assertEquals(5, HudLayout.effectivePx(Size.L, 4));
		assertEquals(1, HudLayout.effectivePx(Size.S, 1));
		assertEquals(1f, HudLayout.scale(Size.M, 3));
		assertEquals(2f / 3f, HudLayout.scale(Size.S, 3), 1e-6);
		assertEquals(67, HudLayout.scaled(100, 2f / 3f), "rounded out");
		assertEquals(100, HudLayout.scaled(100, 1f));
	}

	@Test
	void bossBarsStopAtAThirdOfTheScreen() {
		assertEquals(0, HudLayout.bossBarsDrawn(0, 240));
		assertEquals(1, HudLayout.bossBarsDrawn(1, 240));
		assertEquals(4, HudLayout.bossBarsDrawn(9, 240));
		assertEquals(3, HudLayout.bossBarsDrawn(9, 180));
		Env e = Env.of(426, 240, 3).boss(2, 50);
		assertEquals(new Rect(213 - 91, 0, 182, 36), HudLayout.bossBars(e));
		assertEquals(260, HudLayout.bossBars(Env.of(426, 240, 3).boss(1, 260)).w(), "a wide title widens the column");
	}

	@Test
	void effectRows() {
		Env e = Env.of(426, 240, 3);
		assertTrue(HudLayout.effects(e).empty());
		assertEquals(new Rect(426 - 50, 1, 50, 24), HudLayout.effects(e.effects(2, 0)));
		assertEquals(new Rect(426 - 75, 1, 75, 50), HudLayout.effects(e.effects(1, 3)));
		assertEquals(16, HudLayout.effects(e.effects(1, 0).withDemo(true)).y());
	}

	@Test
	void topRightSitsUnderTheEffectsAndThePill() {
		Env plain = Env.of(426, 240, 3);
		Placement p = HudLayout.place(plain, Position.TOP_RIGHT, 96, 16);
		assertEquals(new Rect(426 - 4 - 96, 4, 96, 16), p.rect());
		assertFalse(p.fallback());
		assertEquals(25 + HudLayout.GAP, HudLayout.place(plain.effects(1, 0), Position.TOP_RIGHT, 96, 16).rect().y(), "one row of icons");
		assertEquals(51 + HudLayout.GAP, HudLayout.place(plain.effects(1, 1), Position.TOP_RIGHT, 96, 16).rect().y(), "two rows");
		Env e = busy(426, 240, 3);
		Rect r = HudLayout.place(e, Position.TOP_RIGHT, 96, 16).rect();
		assertEquals(e.pill().bottom() + HudLayout.GAP, r.y(), "under the connection pill");
		assertFalse(HudLayout.overlaps(e, r).any());
	}

	@Test
	void aWidePanelGoesBelowTheBossBars() {
		// no chat lines showing (the chat area only counts while it shows something)
		Env e = Env.of(426, 240, 3).boss(3, 100).chat(0, 0);
		Rect boss = HudLayout.bossBars(e);
		Rect r = HudLayout.place(e, Position.TOP_RIGHT, 300, 70).rect();
		assertTrue(r.y() >= boss.bottom() + HudLayout.GAP, r + " vs " + boss);
		// a narrow pill beside the boss bars stays at the top
		assertEquals(4, HudLayout.place(e, Position.TOP_RIGHT, 96, 16).rect().y());
	}

	@Test
	void topLeftKeepsTheMinimapRoom() {
		Env e = Env.of(426, 240, 3).withMinimap(72);
		Rect r = HudLayout.place(e, Position.TOP_LEFT, 96, 16).rect();
		assertEquals(new Rect(4, 76, 96, 16), r);
		assertFalse(HudLayout.overlaps(e, r).minimap());
		assertEquals(4, HudLayout.place(Env.of(426, 240, 3), Position.TOP_LEFT, 96, 16).rect().y(), "no offset");
	}

	@Test
	void bottomLeftSitsAboveTheChat() {
		Env e = Env.of(426, 240, 3);
		Rect chat = HudLayout.chat(e);
		assertEquals(110, chat.y());
		Rect r = HudLayout.place(e, Position.BOTTOM_LEFT, 96, 16).rect();
		assertEquals(chat.y() - HudLayout.GAP - 16, r.y());
		assertEquals(4, r.x());
	}

	@Test
	void bottomRightClearsTheHotbarAndTheChat() {
		Env e = busy(426, 240, 3);
		Rect r = HudLayout.place(e, Position.BOTTOM_RIGHT, 96, 16).rect();
		Overlaps o = HudLayout.overlaps(e, r);
		assertFalse(o.any(), r + " " + o);
		// at 1080p scale 2 there is room beside the hotbar, so it stays in the corner
		Env wide = busy(960, 540, 2);
		Rect w = HudLayout.place(wide, Position.BOTTOM_RIGHT, 96, 16).rect();
		assertEquals(540 - 4 - 16, w.y());
	}

	@Test
	void rightMiddleIsCentred() {
		Env e = Env.of(426, 240, 3).chat(0, 0);
		Rect r = HudLayout.place(e, Position.RIGHT_MIDDLE, 96, 16).rect();
		assertEquals((240 - 16) / 2, r.y());
		assertEquals(426 - 4 - 96, r.x());
		// a full chat (320 px wide) reaches under the middle of a 426 px screen: it moves up above it
		Rect up = HudLayout.place(Env.of(426, 240, 3), Position.RIGHT_MIDDLE, 96, 16).rect();
		assertEquals(110 - HudLayout.GAP - 16, up.y());
		// a tall overlay on a short screen with both effect rows slides down past them
		Env s = Env.of(320, 180, 4).effects(1, 1);
		Rect t = HudLayout.place(s, Position.RIGHT_MIDDLE, 180, 110).rect();
		assertFalse(HudLayout.overlaps(s, t).any(), t.toString());
	}

	@Test
	void noRoomFallsBackToTopRightThenHides() {
		// a tall chat leaves bottom left no room at 320x180 (the hotbar below it, the chat above)
		Env e = Env.of(320, 180, 4).chat(200, 130);
		Placement p = HudLayout.place(e, Position.BOTTOM_LEFT, 96, 16);
		assertTrue(p.fallback());
		assertEquals(Position.TOP_RIGHT, p.used());
		assertTrue(p.placed());
		assertFalse(HudLayout.overlaps(e, p.rect()).any());
		Placement none = HudLayout.place(Env.of(320, 180, 4), Position.TOP_RIGHT, 400, 16);
		assertFalse(none.placed(), "wider than the screen");
		assertTrue(HudLayout.place(Env.of(320, 180, 4), Position.TOP_RIGHT, 0, 16).rect().empty());
	}

	@Test
	void everyPositionOnEveryScreenIsClearOrHidden() {
		for (int[] gui : GUIS) {
			for (Size size : Size.values()) {
				float k = HudLayout.scale(size, gui[2]);
				for (int[] c : CONTENT) {
					int w = HudLayout.scaled(c[0], k);
					int h = HudLayout.scaled(c[1], k);
					for (int minimap : new int[] {0, 72}) {
						Env[] envs = {Env.of(gui[0], gui[1], gui[2]).withMinimap(minimap), busy(gui[0], gui[1], gui[2]).chat(0, 0).withMinimap(minimap),
							busy(gui[0], gui[1], gui[2]).withMinimap(minimap), busy(gui[0], gui[1], gui[2]).boss(5, 200).withMinimap(minimap)};
						for (int ei = 0; ei < envs.length; ei++) {
							Env e = envs[ei];
							for (Position pos : Position.values()) {
								Placement p = HudLayout.place(e, pos, w, h);
								String what = gui[0] + "x" + gui[1] + "@" + gui[2] + " " + size + " " + w + "x" + h + " " + pos + " minimap " + minimap + " env " + ei
									+ " -> " + p;
								if (!p.placed()) {
									// a one-line pill always finds room (here or top right); a full chat (ten lines, 328 px wide) leaves none on
									// a 320 px screen (it spans the width between the effects and the hotbar), four boss bars none at 426x240
									assertFalse(c[1] == 16 && w <= 120 && (ei < 2 || ei == 2 && gui[0] >= 426), "the pill must find room: " + what);
									continue;
								}
								Overlaps o = HudLayout.overlaps(e, p.rect());
								assertFalse(o.any(), what + " " + o);
								assertEquals(w, p.rect().w());
								assertEquals(h, p.rect().h());
							}
						}
					}
				}
			}
		}
	}

	@Test
	void theDefaultPillFitsAt426x240AtEveryScaleStep() {
		for (Size size : Size.values()) {
			float k = HudLayout.scale(size, 3);
			Env e = busy(426, 240, 3);
			for (Position pos : Position.values()) {
				Placement p = HudLayout.place(e, pos, HudLayout.scaled(150, k), HudLayout.scaled(16, k));
				assertTrue(p.placed() && !p.fallback(), size + " " + pos + " " + p);
			}
		}
	}

	@Test
	void overlapsAreReported() {
		Env e = busy(426, 240, 3);
		Overlaps top = HudLayout.overlaps(e, new Rect(150, 10, 100, 16));
		assertTrue(top.bossbar());
		assertFalse(top.hotbar());
		assertTrue(HudLayout.overlaps(e, new Rect(200, 230, 20, 5)).hotbar());
		assertTrue(HudLayout.overlaps(e, new Rect(10, 150, 20, 5)).chat());
		assertTrue(HudLayout.overlaps(e, new Rect(410, 5, 10, 5)).effects());
		assertTrue(HudLayout.overlaps(e, new Rect(420, 230, 20, 5)).offscreen());
		assertFalse(HudLayout.overlaps(e, Rect.NONE).any());
	}

	@Test
	void toastsStackUnderOrAboveTheOverlay() {
		Env e = busy(426, 240, 3);
		Rect pill = HudLayout.place(e, Position.TOP_RIGHT, 96, 16).rect();
		Column c = HudLayout.toasts(e, Position.TOP_RIGHT, pill, 196);
		assertFalse(c.up());
		assertEquals(426 - 4 - 196, c.x());
		assertTrue(c.top() >= pill.bottom() + HudLayout.GAP);
		Rect probe = new Rect(c.x(), c.top(), 196, Math.max(1, c.height()));
		Overlaps o = HudLayout.overlaps(e, probe);
		assertFalse(o.bossbar() || o.effects() || o.hotbar() || o.pill(), probe + " " + o);

		Rect low = HudLayout.place(e, Position.BOTTOM_LEFT, 96, 16).rect();
		Column b = HudLayout.toasts(e, Position.BOTTOM_LEFT, low, 196);
		assertTrue(b.up());
		assertEquals(4, b.x());
		assertEquals(low.y() - HudLayout.GAP, b.bottom());

		Column hidden = HudLayout.toasts(e, Position.TOP_RIGHT, Rect.NONE, 196);
		assertTrue(hidden.top() >= e.pill().bottom(), "the overlay hidden: toasts take its place");
	}
}
