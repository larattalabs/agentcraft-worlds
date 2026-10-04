package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DetailLayoutTest {
	@Test
	void fitsFixedWhenTheBodyKeepsThreeLines() {
		// 4K auto scale: ~98 px under the header; a one-row answer panel with the actions in the top bar
		DetailLayout l = DetailLayout.of(98, 50, 10, 0);
		assertFalse(l.flow());
		assertEquals(44, l.bodyH());
		assertEquals(48, l.pinnedY());
		assertEquals(98, l.pinnedY() + 50);
		assertEquals(0, l.maxOffset());
	}

	@Test
	void flowsInsteadOfDrawingOverTheHeader() {
		// the review's case: a free-text question with options, pinned ~88 px of ~98
		DetailLayout l = DetailLayout.of(98, 88, 6, 0);
		assertTrue(l.flow());
		assertEquals(DetailLayout.bodyHeight(6), l.bodyH());
		assertEquals(l.bodyH() + DetailLayout.GAP, l.pinnedY());
		assertEquals(l.bodyH() + DetailLayout.GAP + 88, l.contentH());
		assertEquals(l.contentH() - 98, l.maxOffset());
		assertEquals(0, l.offset());
		assertEquals(l.maxOffset(), l.offsetShowingPinned(98));
	}

	@Test
	void neverStartsAboveTheBodyTopAndKeepsTheLeastBody() {
		for (int avail = -10; avail <= 300; avail += 7) {
			for (int pinned = 0; pinned <= 400; pinned += 13) {
				for (int lines = 0; lines <= 40; lines += 3) {
					DetailLayout l = DetailLayout.of(avail, pinned, lines, 0);
					assertTrue(l.bodyH() >= DetailLayout.minBody(lines), "body " + avail + "/" + pinned + "/" + lines);
					assertTrue(l.pinnedY() >= l.bodyH(), "pinned under the body");
					assertTrue(l.offset() >= 0 && l.offset() <= l.maxOffset());
					if (!l.flow()) {
						assertEquals(Math.max(0, avail), l.pinnedY() + pinned, "fixed: pinned at the bottom");
					}
				}
			}
		}
	}

	@Test
	void minimumIsThreeLinesOrAShorterText() {
		assertEquals(36, DetailLayout.minBody(10));
		assertEquals(16, DetailLayout.minBody(1));
		assertEquals(16, DetailLayout.minBody(0));
		// a one-line body fits in 20 px over a big pinned area
		assertFalse(DetailLayout.of(98, 78, 1, 0).flow());
		assertTrue(DetailLayout.of(98, 78, 5, 0).flow());
		assertEquals(36 + 4 + 60, DetailLayout.needed(60, 8));
	}

	@Test
	void clampsTheOffset() {
		DetailLayout l = DetailLayout.of(60, 80, 12, 10_000);
		assertTrue(l.flow());
		assertEquals(l.maxOffset(), l.offset());
		assertEquals(0, DetailLayout.of(60, 80, 12, -5).offset());
		assertEquals(0, DetailLayout.of(200, 40, 3, 50).offset());
	}
}
