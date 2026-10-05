package dev.agentcraft.hud;

/**
 * How hub Settings > General > HUD lays out its controls and the preview's small screen (pure, {@code HudPreviewLayoutTest}).
 * With room for two columns the small screen sits to the right of the Style / Position / Size chips, at the top of the
 * section, sized so it is whole in the visible part of the scrolled form without scrolling (a 426x240 screen leaves about
 * 85 px under the section title); the real-size sample, the checkboxes and the rest follow under both columns. Too narrow
 * for that, everything stacks: the controls, then the small screen ({@link #MAX_SCREEN_W} wide) and the sample.
 *
 * @param columns     controls left, small screen right
 * @param controlsW   the controls' width (the whole width when stacked)
 * @param screenX     the small screen's left edge, from the section's left
 * @param screenW     the small screen's size (GUI px; it shows a 426x240 screen)
 * @param screenH     ditto
 */
public record HudPreviewLayout(boolean columns, int controlsW, int screenX, int screenW, int screenH) {
	/** The screen the preview shows (GUI px). */
	public static final int PW = 426;
	public static final int PH = 240;
	/** The small screen's width when there is room. */
	public static final int MAX_SCREEN_W = 176;
	/** Narrower than this it is not worth a column. */
	public static final int MIN_SCREEN_W = 96;
	/** The controls' column never narrower than this (Position's chips wrap to two rows at about 190). */
	public static final int MIN_CONTROLS_W = 200;
	/** Between the columns. */
	public static final int GUTTER = 8;

	/**
	 * The layout for a section {@code w} wide in a form whose visible height is {@code viewH}, the section's content
	 * starting {@code top} px below the form's top at scroll 0 (its title row).
	 */
	public static HudPreviewLayout of(int w, int viewH, int top) {
		return of(w, viewH, top, MIN_CONTROLS_W);
	}

	/**
	 * Ditto, with the controls' column at least {@code controlsW} wide (the caller's measure of what keeps its chip rows
	 * short, e.g. Position in two rows), never less than {@link #MIN_CONTROLS_W}.
	 */
	public static HudPreviewLayout of(int w, int viewH, int top, int controlsW) {
		int room = w - Math.max(MIN_CONTROLS_W, controlsW) - GUTTER;
		if (room < MIN_SCREEN_W) {
			int sw = Math.min(w, MAX_SCREEN_W);
			return new HudPreviewLayout(false, w, 0, sw, sw * PH / PW);
		}
		// whole in view: 1 px of slack under it
		int byHeight = viewH > 0 ? (viewH - top - 1) * PW / PH : MAX_SCREEN_W;
		int sw = Math.max(MIN_SCREEN_W, Math.min(MAX_SCREEN_W, Math.min(room, byHeight)));
		return new HudPreviewLayout(true, w - sw - GUTTER, w - sw, sw, sw * PH / PW);
	}
}
