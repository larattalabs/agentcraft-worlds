package dev.agentcraft.client.hub;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * A hub tab with its own state and input (Repos, Goals). {@link HubScreen} draws the frame and the tab
 * strip, and hands the content area, keys, typed characters, clicks and the wheel to the active pane
 * first. While a pane's text field has focus it gets every key (so typing "h" never closes the hub).
 */
interface HubPane {
	void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my);

	/** A key while this pane is shown; true = consumed. Called before the hub's own keys. */
	boolean keyPressed(KeyEvent e);

	boolean charTyped(CharacterEvent e);

	/** A click that hit no button (fields, rows, chips); true = consumed. */
	boolean mouseClicked(double x, double y, boolean doubleClick);

	boolean mouseScrolled(double x, double y, int dir);

	/** The focused text field's id, or null. */
	@Nullable String focus();

	/** Drop text focus (tab switch, close). */
	void unfocus();

	/** The pane became the shown tab (true) or stopped being it (false). */
	void shown(boolean on);

	/** Footer key hints (key, verb, ...). */
	String[] hints();

	/** DevBridge: the pane's state for {@code dev.hub.state}. */
	JsonObject state();
}
