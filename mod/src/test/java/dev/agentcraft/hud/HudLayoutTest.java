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
		// no chat lines showing: still above the chat's area, so a new message does not make it jump
		assertEquals(r, HudLayout.place(e.chat(0, 0), Position.BOTTOM_LEFT, 96, 16).rect());
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
		Env e = Env.of(320, 180, 4).chat(200, 175);
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

	// ------------------------------------------------------------------ vanilla extras: sidebar, subtitles, toasts, the auth banner

	/** {@link #busy} plus a three-row scoreboard sidebar, subtitles on with two rows showing, a vanilla toast and the auth banner. */
	static Env crowded(int w, int h, int scale) {
		Env e = busy(w, h, scale).withSidebar(HudLayout.sidebar(w, h, 60, 3)).withSubtitles(true, HudLayout.subtitleRows(w, h, 90, 2))
			.withToasts(HudLayout.vanillaToast(w, 160, 32, 0));
		return e.withBanner(HudLayout.banner(e, 360, bw -> bw >= 250 ? 30 : 40));
	}

	@Test
	void sidebarMatchesVanillasGeometry() {
		assertEquals(new Rect(371, 92, 54, 37), HudLayout.sidebar(426, 240, 50, 3), "3 rows: bottom at 120 + 9, title row 10 px");
		assertEquals(new Rect(381, 110, 44, 10), HudLayout.sidebar(426, 240, 40, 0), "no entries: the title row still shows");
		Rect full = HudLayout.sidebar(426, 240, 40, 40);
		assertEquals(165, full.bottom(), "at most 15 rows");
		assertEquals(165 - 135 - 10, full.y());
		assertTrue(HudLayout.sidebar(426, 240, -1, 3).empty(), "no sidebar objective");
	}

	@Test
	void subtitlesMatchVanillasGeometryAndKeepABand() {
		assertEquals(new Rect(343, 190, 82, 20), HudLayout.subtitleRows(426, 240, 81, 2), "two rows, 10 px apart, 30 px above the bottom");
		assertTrue(HudLayout.subtitleRows(426, 240, 81, 0).empty());
		Env off = Env.of(426, 240, 3).withSubtitles(false, HudLayout.subtitleRows(426, 240, 81, 2));
		assertTrue(HudLayout.subtitles(off).empty(), "subtitles off: nothing kept");
		Env on = Env.of(426, 240, 3).withSubtitles(true, Rect.NONE);
		assertEquals(new Rect(426 - HudLayout.SUBTITLE_RESERVE_W, 180, HudLayout.SUBTITLE_RESERVE_W, 30), HudLayout.subtitles(on),
			"on with none showing: the band of three rows");
		Env many = Env.of(426, 240, 3).withSubtitles(true, HudLayout.subtitleRows(426, 240, 100, 5));
		assertEquals(new Rect(306, 160, 120, 50), HudLayout.subtitles(many), "more rows than the band: what shows counts");
	}

	@Test
	void vanillaToastsSitInTheTopRightBySlot() {
		assertEquals(new Rect(266, 0, 160, 32), HudLayout.vanillaToast(426, 160, 32, 0));
		assertEquals(new Rect(266, 32, 160, 32), HudLayout.vanillaToast(426, 160, 32, 1));
		assertEquals(new Rect(226, 0, 200, 64), HudLayout.vanillaToast(426, 160, 32, 0).union(HudLayout.vanillaToast(426, 200, 32, 1)));
		assertTrue(HudLayout.vanillaToast(426, 0, 32, 0).empty());
		assertEquals(new Rect(1, 2, 3, 4), Rect.NONE.union(new Rect(1, 2, 3, 4)));
	}

	@Test
	void theOverlayKeepsClearOfTheVanillaExtras() {
		Env plain = Env.of(426, 240, 3).chat(0, 0);
		Env side = plain.withSidebar(HudLayout.sidebar(426, 240, 50, 3));
		assertEquals(new Rect(326, 112, 96, 16), HudLayout.place(plain, Position.RIGHT_MIDDLE, 96, 16).rect());
		assertEquals(new Rect(326, 132, 96, 16), HudLayout.place(side, Position.RIGHT_MIDDLE, 96, 16).rect(), "right middle: under the sidebar");
		Env toast = plain.withToasts(HudLayout.vanillaToast(426, 160, 32, 0));
		assertEquals(35, HudLayout.place(toast, Position.TOP_RIGHT, 96, 16).rect().y(), "top right: under an advancement toast");
		Env subs = plain.withSubtitles(true, Rect.NONE);
		assertEquals(173, HudLayout.place(plain, Position.BOTTOM_RIGHT, 96, 16).rect().y(), "above the hotbar");
		assertEquals(161, HudLayout.place(subs, Position.BOTTOM_RIGHT, 96, 16).rect().y(), "bottom right: above the subtitle band");
		Env loud = plain.withSubtitles(true, HudLayout.subtitleRows(426, 240, 100, 5));
		assertEquals(141, HudLayout.place(loud, Position.BOTTOM_RIGHT, 96, 16).rect().y(), "and above more rows when they show");
		Env banner = plain.withBanner(new Rect(98, 20, 230, 30));
		assertEquals(53, HudLayout.place(banner, Position.TOP_RIGHT, 96, 16).rect().y(), "under the auth banner where they meet");
		assertTrue(HudLayout.overlaps(side, new Rect(380, 100, 10, 10)).sidebar());
		assertTrue(HudLayout.overlaps(subs, new Rect(400, 200, 10, 5)).subtitles());
		assertTrue(HudLayout.overlaps(toast, new Rect(300, 10, 10, 5)).toasts());
		assertTrue(HudLayout.overlaps(banner, new Rect(200, 30, 10, 5)).banner());
		assertTrue(HudLayout.overlaps(banner, new Rect(200, 30, 10, 5)).any());
	}

	@Test
	void crowdedScreensAreClearOrHidden() {
		for (int[] gui : GUIS) {
			for (Size size : Size.values()) {
				float k = HudLayout.scale(size, gui[2]);
				for (int[] c : CONTENT) {
					int w = HudLayout.scaled(c[0], k);
					int h = HudLayout.scaled(c[1], k);
					Env e = crowded(gui[0], gui[1], gui[2]).chat(0, 0);
					for (Position pos : Position.values()) {
						Placement p = HudLayout.place(e, pos, w, h);
						if (p.placed()) {
							Overlaps o = HudLayout.overlaps(e, p.rect());
							assertFalse(o.any(), gui[0] + "x" + gui[1] + " " + size + " " + pos + " " + p + " " + o);
						}
					}
				}
			}
		}
		// the default pill still finds room everywhere at 426x240 with all of it on screen (here or top right)
		Env e = crowded(426, 240, 3).chat(0, 0);
		for (Position pos : Position.values()) {
			assertTrue(HudLayout.place(e, pos, 96, 16).placed(), pos.toString());
		}
	}

	@Test
	void toastColumnStopsAboveTheSidebar() {
		Env e = Env.of(426, 240, 3).chat(0, 0).withSidebar(HudLayout.sidebar(426, 240, 50, 3));
		Rect pill = HudLayout.place(e, Position.TOP_RIGHT, 96, 16).rect();
		Column c = HudLayout.toasts(e, Position.TOP_RIGHT, pill, 196);
		assertEquals(92 - HudLayout.GAP, c.bottom());
	}

	@Test
	void toastColumnAboveABottomOverlayStopsAboveTheBannerItMeets() {
		Env e = Env.of(426, 240, 3).chat(0, 0).withBanner(new Rect(118, 39, 190, 57));
		Rect low = new Rect(4, 90, 91, 17);
		Column c = HudLayout.toasts(e, Position.BOTTOM_LEFT, low, 196);
		assertTrue(c.up());
		assertEquals(39 - HudLayout.GAP, c.bottom(), "the banner straddles the overlay's top: the column ends above it");
		assertFalse(new Rect(c.x(), c.top(), 196, Math.max(1, c.height())).intersects(e.banner()));
	}

	@Test
	void theAuthBannerNarrowsBetweenTheCornersBeforeItDropsBelowThem() {
		Env e = Env.of(426, 240, 3).boss(1, 70).effects(1, 0).withPill(new Rect(331, 28, 95, 18));
		Rect b = HudLayout.banner(e, 360, w -> w >= 200 ? 30 : 40);
		assertEquals(new Rect(98, 20, 230, 30), b, "under the boss bar, narrowed to clear the pill");
		assertFalse(HudLayout.overlaps(e, b).any());
		Env open = Env.of(426, 240, 3);
		assertEquals(new Rect(33, 4, 360, 30), HudLayout.banner(open, 360, w -> 30), "nothing around: top centre at full width");
		Env wide = Env.of(960, 540, 2).boss(2, 100).effects(2, 1).withPill(new Rect(864, 54, 92, 18));
		Rect wb = HudLayout.banner(wide, 360, w -> 30);
		assertEquals(360, wb.w(), "a wide screen keeps it wide");
		assertEquals(HudLayout.bossBars(wide).bottom() + HudLayout.GAP, wb.y(), "stacked under the boss bars");
		assertTrue(HudLayout.banner(Env.of(426, 240, 3), 0, w -> 30).empty());
		Rect titled = HudLayout.banner(e, 360, 240, w -> 30);
		assertTrue(titled.w() >= 240, "never narrower than its title: " + titled);
		assertTrue(titled.y() >= 46 + HudLayout.GAP, "so it goes under the pill instead: " + titled);
		assertFalse(HudLayout.overlaps(e, titled).any());
	}
}
