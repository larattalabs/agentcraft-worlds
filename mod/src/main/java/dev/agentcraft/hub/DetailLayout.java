package dev.agentcraft.hub;

/**
 * How an Inbox detail splits its height under the header (pure; docs/HUB.md "Inbox", layout): a text body on top and a
 * pinned area at the bottom (the answer panel, the reply box, the action buttons).
 *
 * <p>Fixed: the pinned area sits at the bottom and the body gets the rest (it scrolls inside itself). When that would
 * leave the body less than {@link #MIN_LINES} lines (or its whole text, when shorter), the detail <em>flows</em>
 * instead: the body at its natural height, the pinned area under it, and the whole column scrolls by {@code offset}
 * pixels. Either way nothing is drawn above the body's top (the pill, the title and the agent line stay readable), at
 * any available height: 4K with auto GUI scale leaves an Inbox detail about 98 px.
 *
 * @param flow      the whole column scrolls (else: fixed)
 * @param bodyH     the body's height
 * @param pinnedY   the pinned area's top, relative to the body's top (before the offset)
 * @param contentH  the column's height (fixed: the available height)
 * @param offset    the scroll offset in pixels, clamped to [0, maxOffset]
 * @param maxOffset 0 when it fits
 */
public record DetailLayout(boolean flow, int bodyH, int pinnedY, int contentH, int offset, int maxOffset) {
	public static final int LINE_H = 10;
	/** the body well's padding (4 above the first line, 2 under the last) */
	public static final int PAD = 6;
	/** between the body and the pinned area */
	public static final int GAP = 4;
	/** the body never shows fewer lines than this (unless its text is shorter) */
	public static final int MIN_LINES = 3;
	/** the column a flowing detail keeps free for its scrollbar */
	public static final int BAR = 8;

	/** A body well showing {@code lines} lines (at least one). */
	public static int bodyHeight(int lines) {
		return Math.max(1, lines) * LINE_H + PAD;
	}

	/** The least body height: {@link #MIN_LINES} lines, or all of a shorter text. */
	public static int minBody(int lines) {
		return bodyHeight(Math.min(MIN_LINES, Math.max(1, lines)));
	}

	/** What the detail needs to show without scrolling the column: the least body, the gap and the pinned area. */
	public static int needed(int pinned, int bodyLines) {
		return minBody(bodyLines) + GAP + Math.max(0, pinned);
	}

	/**
	 * @param available from the body's top to the bottom of the detail
	 * @param pinned    the pinned area's height
	 * @param bodyLines the body's wrapped lines
	 * @param offset    the wanted scroll offset (pixels; used when it flows)
	 */
	public static DetailLayout of(int available, int pinned, int bodyLines, int offset) {
		int avail = Math.max(0, available);
		int pin = Math.max(0, pinned);
		if (avail - pin - GAP >= minBody(bodyLines)) {
			int bodyH = avail - pin - GAP;
			return new DetailLayout(false, bodyH, avail - pin, avail, 0, 0);
		}
		int bodyH = Math.max(minBody(bodyLines), bodyHeight(bodyLines));
		int content = bodyH + GAP + pin;
		int max = Math.max(0, content - avail);
		return new DetailLayout(true, bodyH, bodyH + GAP, content, Math.max(0, Math.min(offset, max)), max);
	}

	/** The offset that shows the pinned area's bottom (a focused text box in it must be visible). */
	public int offsetShowingPinned(int available) {
		return flow ? Math.max(0, Math.min(maxOffset, contentH - Math.max(0, available))) : 0;
	}
}
