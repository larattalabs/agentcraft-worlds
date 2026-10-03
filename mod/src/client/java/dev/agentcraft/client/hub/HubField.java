package dev.agentcraft.client.hub;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.TextFieldView;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.console.TextModel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * A text field of the hub's Repos/Goals tabs: the console's {@link TextModel} + {@link TextFieldView} (the
 * design form's fields), remembering where it was drawn last frame for clicks. {@code multiLine}: Enter
 * inserts a newline (Ctrl+Enter is the tab's "send"); single-line fields leave Enter to the tab.
 */
final class HubField {
	final String id;
	final TextModel model;
	final boolean multiLine;
	private final TextFieldView view = new TextFieldView();
	private @Nullable String placeholder;
	private int maxLines;
	private int x;
	private int y;
	private int w;
	private boolean drawn;

	HubField(String id, int maxLength, boolean multiLine, String placeholder) {
		this.id = id;
		this.model = new TextModel(maxLength);
		this.multiLine = multiLine;
		this.placeholder = placeholder;
		this.maxLines = 1;
	}

	void placeholder(String p) {
		placeholder = p;
	}

	private TextFieldView.Style style() {
		return new TextFieldView.Style(null, 0, placeholder, null, null, 0, Math.max(1, maxLines));
	}

	/** Height it takes at width {@code w} with at most {@code lines} visual lines. */
	int height(Font font, int w, int lines) {
		maxLines = lines;
		return view.height(font, model, w, style());
	}

	/** Draws it (remembered for clicks this frame); returns its height. */
	int draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, int lines, boolean focused) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.maxLines = lines;
		drawn = true;
		return view.draw(g, font, model, x, y, w, focused, style());
	}

	/** Called at the start of a frame: a field not drawn this frame cannot be clicked. */
	void beginFrame() {
		drawn = false;
	}

	boolean drawn() {
		return drawn;
	}

	/** Click: moves the caret there and returns true when it hit the field. */
	boolean click(Font font, double mx, double my) {
		if (!drawn) {
			return false;
		}
		int i = view.hit(font, model, x, y, w, style(), mx, my);
		if (i < 0) {
			return false;
		}
		model.moveTo(i, false);
		return true;
	}

	/** Editing keys (not Enter/Tab/Esc, which the tab handles). Up/Down move by visual line in a multi-line field. */
	boolean key(Font font, KeyEvent e) {
		int k = e.key();
		if (multiLine && (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN)) {
			model.vertical(font, TextFieldView.wrapWidth(font, model, w, style()), k == InputConstants.KEY_UP ? -1 : 1, e.hasShiftDown());
			return true;
		}
		return TextKeys.handle(e, model);
	}

	String value() {
		return model.value();
	}

	void set(String s) {
		model.set(s);
	}
}
