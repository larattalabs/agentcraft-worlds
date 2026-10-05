package dev.agentcraft.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HudPreviewLayoutTest {
	@Test
	void at426x240TheSmallScreenSitsBesideTheChipsAndFitsInView() {
		// the hub's Settings form at 426x240: about 395 px wide, 105 px visible, the section title 18 px
		HudPreviewLayout l = HudPreviewLayout.of(395, 105, 18);
		assertTrue(l.columns());
		assertTrue(18 + l.screenH() <= 105, "whole in view without scrolling: " + l);
		assertTrue(l.controlsW() >= HudPreviewLayout.MIN_CONTROLS_W, l.toString());
		assertEquals(395, l.screenX() + l.screenW(), "right-aligned");
		assertEquals(l.controlsW() + HudPreviewLayout.GUTTER, l.screenX());
		assertEquals(l.screenW() * 240 / 426, l.screenH(), "a 426x240 screen");
	}

	@Test
	void roomyFormsKeepTheFullSizeScreen() {
		HudPreviewLayout l = HudPreviewLayout.of(900, 400, 18);
		assertTrue(l.columns());
		assertEquals(HudPreviewLayout.MAX_SCREEN_W, l.screenW());
		assertEquals(HudPreviewLayout.MAX_SCREEN_W, HudPreviewLayout.of(600, 0, 18).screenW(), "before the first draw (no height yet)");
	}

	@Test
	void narrowFormsStack() {
		HudPreviewLayout l = HudPreviewLayout.of(250, 300, 18);
		assertFalse(l.columns());
		assertEquals(250, l.controlsW());
		assertEquals(176, l.screenW());
		assertEquals(120, HudPreviewLayout.of(120, 300, 18).screenW(), "never wider than the form");
	}

	@Test
	void theControlsCanAskForMoreRoom() {
		// 426x240: the Position chips need about 227 px to wrap into two rows rather than three
		HudPreviewLayout l = HudPreviewLayout.of(387, 105, 18, 227);
		assertTrue(l.columns());
		assertTrue(l.controlsW() >= 227, l.toString());
		assertTrue(18 + l.screenH() <= 105, l.toString());
		assertFalse(HudPreviewLayout.of(387, 105, 18, 300).columns(), "no room left for the screen: stacked");
	}

	@Test
	void veryShortFormsKeepAUsableScreen() {
		assertEquals(HudPreviewLayout.MIN_SCREEN_W, HudPreviewLayout.of(395, 60, 18).screenW());
	}
}
