package dev.agentcraft.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The in-game overlay's client-side settings, {@code <gameDir>/agentcraft/hud.json} (hub Settings > General > HUD; no
 * Foreman config): the style, where it sits, its size, peek-on-change, auto-hide when idle, hide in combat, which
 * Foreman notifies become toasts and the top-left offset (room for a minimap). Pure and tolerant like
 * {@link HudPrefs}: an unknown or malformed value keeps its default, a broken file never breaks the HUD.
 *
 * <pre>
 * { "version": 1, "style": "pill", "position": "top_right", "size": "m", "peek": true, "autoHide": true,
 *   "hideInCombat": false, "toasts": "needs_you", "topLeftOffset": 72 }
 * </pre>
 */
public final class HudSettings {
	public static final String FILE = "hud.json";
	/** The top-left offset's range and step (GUI px). */
	public static final int OFFSET_MAX = 200;
	public static final int OFFSET_STEP = 8;
	public static final int OFFSET_DEFAULT = 72;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** What the overlay looks like. */
	public enum Style {
		/** Nothing (toasts and J still work). */
		OFF("Off"),
		/** One compact line: mini progress bar, %, counts; clay when something needs the player. */
		PILL("Pill"),
		/** The pill's look, a bit taller: goal title, goal dots, working agents, usage / hold, next decision. */
		PILL_PLUS("Pill+"),
		/** The full goal bar with the decisions badge and the alert line (the wave 2 HUD, out of the top centre). */
		PANEL("Panel");

		private final String label;

		Style(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public Style next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/** Where the overlay sits. */
	public enum Position {
		TOP_RIGHT("Top right"), TOP_LEFT("Top left"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right"), RIGHT_MIDDLE("Right middle");

		private final String label;

		Position(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public boolean left() {
			return this == TOP_LEFT || this == BOTTOM_LEFT;
		}

		public boolean bottom() {
			return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
		}
	}

	/** One step smaller / larger than the GUI scale ({@link HudLayout#scale}). */
	public enum Size {
		S, M, L;

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public String label() {
			return name();
		}
	}

	/** Which Foreman notifies become toasts. */
	public enum Toasts {
		NEEDS_YOU("Needs you"), ALL("All");

		private final String label;

		Toasts(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private Style style = Style.PILL;
	private Position position = Position.TOP_RIGHT;
	private Size size = Size.M;
	private boolean peek = true;
	private boolean autoHide = true;
	private boolean hideInCombat;
	private Toasts toasts = Toasts.NEEDS_YOU;
	private int topLeftOffset = OFFSET_DEFAULT;
	private boolean dirty;

	public Style style() {
		return style;
	}

	public Position position() {
		return position;
	}

	public Size size() {
		return size;
	}

	public boolean peek() {
		return peek;
	}

	public boolean autoHide() {
		return autoHide;
	}

	public boolean hideInCombat() {
		return hideInCombat;
	}

	public Toasts toasts() {
		return toasts;
	}

	public int topLeftOffset() {
		return topLeftOffset;
	}

	public boolean dirty() {
		return dirty;
	}

	public void setStyle(Style s) {
		dirty |= s != style;
		style = s;
	}

	/** The cycle key: Off -> Pill -> Pill+ -> Panel -> Off. */
	public Style cycleStyle() {
		setStyle(style.next());
		return style;
	}

	public void setPosition(Position p) {
		dirty |= p != position;
		position = p;
	}

	public void setSize(Size s) {
		dirty |= s != size;
		size = s;
	}

	public void setPeek(boolean on) {
		dirty |= on != peek;
		peek = on;
	}

	public void setAutoHide(boolean on) {
		dirty |= on != autoHide;
		autoHide = on;
	}

	public void setHideInCombat(boolean on) {
		dirty |= on != hideInCombat;
		hideInCombat = on;
	}

	public void setToasts(Toasts t) {
		dirty |= t != toasts;
		toasts = t;
	}

	/** Clamped to 0..{@link #OFFSET_MAX}. */
	public void setTopLeftOffset(int px) {
		int v = Math.max(0, Math.min(OFFSET_MAX, px));
		dirty |= v != topLeftOffset;
		topLeftOffset = v;
	}

	/** Copies every value of {@code o} (the smoke test restores what it found). */
	public void copyFrom(HudSettings o) {
		setStyle(o.style);
		setPosition(o.position);
		setSize(o.size);
		setPeek(o.peek);
		setAutoHide(o.autoHide);
		setHideInCombat(o.hideInCombat);
		setToasts(o.toasts);
		setTopLeftOffset(o.topLeftOffset);
	}

	// ------------------------------------------------------------------ parsing single values (also DevBridge)

	/** "pill_plus", "pill+", "Pill+" -> PILL_PLUS; null when unknown. */
	public static @Nullable Style parseStyle(@Nullable String s) {
		String n = norm(s);
		if (n == null) {
			return null;
		}
		if (n.equals("pill+") || n.equals("pillplus")) {
			return Style.PILL_PLUS;
		}
		return byName(Style.values(), n);
	}

	public static @Nullable Position parsePosition(@Nullable String s) {
		return byName(Position.values(), norm(s));
	}

	public static @Nullable Size parseSize(@Nullable String s) {
		String n = norm(s);
		if (n == null) {
			return null;
		}
		return switch (n) {
			case "small" -> Size.S;
			case "medium" -> Size.M;
			case "large" -> Size.L;
			default -> byName(Size.values(), n);
		};
	}

	public static @Nullable Toasts parseToasts(@Nullable String s) {
		String n = norm(s);
		if (n == null) {
			return null;
		}
		if (n.equals("need_you") || n.equals("need_user")) {
			return Toasts.NEEDS_YOU;
		}
		return byName(Toasts.values(), n);
	}

	private static @Nullable String norm(@Nullable String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		return s.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
	}

	private static <E extends Enum<E>> @Nullable E byName(E[] values, @Nullable String n) {
		if (n == null) {
			return null;
		}
		for (E e : values) {
			if (e.name().toLowerCase(Locale.ROOT).equals(n)) {
				return e;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		o.addProperty("style", style.wire());
		o.addProperty("position", position.wire());
		o.addProperty("size", size.wire());
		o.addProperty("peek", peek);
		o.addProperty("autoHide", autoHide);
		o.addProperty("hideInCombat", hideInCombat);
		o.addProperty("toasts", toasts.wire());
		o.addProperty("topLeftOffset", topLeftOffset);
		return o;
	}

	/** Parses {@link #toJson} output; anything missing or malformed keeps its default. */
	public static HudSettings fromJson(@Nullable JsonElement el) {
		HudSettings h = new HudSettings();
		if (el == null || !el.isJsonObject()) {
			return h;
		}
		JsonObject o = el.getAsJsonObject();
		Style st = parseStyle(str(o, "style"));
		if (st != null) {
			h.style = st;
		}
		Position p = parsePosition(str(o, "position"));
		if (p != null) {
			h.position = p;
		}
		Size sz = parseSize(str(o, "size"));
		if (sz != null) {
			h.size = sz;
		}
		Toasts t = parseToasts(str(o, "toasts"));
		if (t != null) {
			h.toasts = t;
		}
		h.peek = bool(o, "peek", h.peek);
		h.autoHide = bool(o, "autoHide", h.autoHide);
		h.hideInCombat = bool(o, "hideInCombat", h.hideInCombat);
		JsonElement off = o.get("topLeftOffset");
		if (off != null && off.isJsonPrimitive() && off.getAsJsonPrimitive().isNumber()) {
			try {
				h.topLeftOffset = Math.max(0, Math.min(OFFSET_MAX, off.getAsInt()));
			} catch (NumberFormatException ignored) {
				// keep the default
			}
		}
		return h;
	}

	private static @Nullable String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static boolean bool(JsonObject o, String k, boolean def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
	}

	/** Reads {@code file}; a missing or unreadable file gives the defaults. */
	public static HudSettings load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new HudSettings();
		}
		try {
			return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) {
			return new HudSettings();
		}
	}

	/** Writes {@code file} atomically (temp file + move). */
	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(toJson()), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		dirty = false;
	}
}
