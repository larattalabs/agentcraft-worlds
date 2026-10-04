package dev.agentcraft.building;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Trophy} as the four lines of a vanilla sign (docs/BUILDINGS.md "Trophies"). Every line fits the sign's
 * {@link #WIDTH} by the vanilla default font's advances ({@link #advance}): text is word-wrapped and the last line it may
 * use is trimmed with {@link #ELLIPSIS}. Formatting codes ({@code §}) and control characters are dropped, whitespace is
 * collapsed. Pure.
 *
 * <pre>
 * PR     "Merged PR #612" / title (up to 2 lines)     / "2026-10-04"
 * MERGE  "Merged t12"     / title (up to 2 lines)     / "2026-10-04"
 * GOAL   "Goal done"      / goal text, first line     / "3 tasks" / "2026-10-04"
 *        "Goal done"      / goal text over 2 lines    / "2026-10-04"
 * </pre>
 *
 * A goal's task count shares the date's line only when both fit (never with an ISO date: 107 px of 90); else it takes the
 * second title line, and a goal text that needs both lines leaves it out.
 */
public final class TrophyText {
	/** Lines on a sign side ({@code SignText.LINES}). */
	public static final int LINES = 4;
	/** A sign line's width in font pixels ({@code SignBlockEntity#getMaxTextLineWidth} of a standing or wall sign). */
	public static final int WIDTH = 90;
	public static final String ELLIPSIS = "…";
	/** Lines the title (or goal text) may use. */
	static final int TITLE_LINES = 2;

	private TrophyText() {
	}

	/** The sign's four lines, each at most {@link #WIDTH} wide; empty strings for unused lines. */
	public static List<String> lines(Trophy t) {
		String head;
		String date = t.date() == null ? "" : t.date().toString();
		String foot = date;
		String tasks = "";
		String body = unmark(clean(t.title()));
		switch (t.kind()) {
			case PR -> {
				String n = clean(Trophy.stripHash(t.prId()));
				head = n.isEmpty() ? "Merged PR" : "Merged PR #" + n;
			}
			case MERGE -> {
				String id = clean(t.taskId());
				head = id.isEmpty() ? "Merged" : "Merged " + id;
			}
			default -> {
				head = "Goal done";
				body = unmark(clean(firstLine(t.title())));
				Integer n = t.taskCount();
				if (n != null && n >= 0) {
					tasks = n + (n == 1 ? " task" : " tasks");
				}
			}
		}
		List<String> out = new ArrayList<>(LINES);
		out.add(ellipsize(head, WIDTH));
		List<String> title = wrap(body, TITLE_LINES, WIDTH);
		if (!tasks.isEmpty()) {
			// "3 tasks · 2026-10-04" is 107 px: it only shares the last line without a date; else the count takes the
			// free title line, and a goal text that needs both lines leaves it out
			String both = date.isEmpty() ? tasks : tasks + " · " + date;
			if (width(both) <= WIDTH) {
				foot = both;
			} else if (title.size() < TITLE_LINES) {
				title = new ArrayList<>(title);
				while (title.size() < TITLE_LINES - 1) {
					title.add("");
				}
				title.add(ellipsize(tasks, WIDTH));
			}
		}
		for (int i = 0; i < TITLE_LINES; i++) {
			out.add(i < title.size() ? title.get(i) : "");
		}
		out.add(ellipsize(foot, WIDTH));
		return List.copyOf(out);
	}

	// ------------------------------------------------------------------ text

	/** The first non-blank line of {@code s} ("" for none). */
	static String firstLine(@Nullable String s) {
		if (s == null) {
			return "";
		}
		for (String line : s.split("\\R")) {
			if (!line.isBlank()) {
				return line;
			}
		}
		return "";
	}

	/**
	 * Markdown code and bold marks out of a title (task and goal titles are written in Markdown; the village board shows
	 * them without the marks too): "`notes list --tag`" reads "notes list --tag" on the sign.
	 */
	static String unmark(String s) {
		return s.replace("`", "").replace("**", "").strip();
	}

	/** Drops formatting codes and control characters, collapses whitespace (line breaks included), strips. */
	static String clean(@Nullable String s) {
		if (s == null) {
			return "";
		}
		StringBuilder b = new StringBuilder(s.length());
		boolean space = false;
		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '§') {
				if (i < s.length()) {
					i += Character.charCount(s.codePointAt(i)); // the code after it
				}
				continue;
			}
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				space = b.length() > 0;
				continue;
			}
			if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) {
				continue;
			}
			if (space) {
				b.append(' ');
				space = false;
			}
			b.appendCodePoint(cp);
		}
		return b.toString();
	}

	/**
	 * {@code text} (already {@link #clean}ed) word-wrapped into at most {@code maxLines} lines of {@code width}; a word
	 * longer than a line is broken; what does not fit ends the last line with {@link #ELLIPSIS}.
	 */
	static List<String> wrap(String text, int maxLines, int width) {
		List<String> out = new ArrayList<>();
		String rest = text.strip();
		while (!rest.isEmpty() && out.size() < maxLines) {
			if (out.size() == maxLines - 1) {
				out.add(ellipsize(rest, width));
				break;
			}
			int cut = breakAt(rest, width);
			out.add(rest.substring(0, cut).strip());
			rest = rest.substring(cut).strip();
		}
		return out;
	}

	/** Where a line of {@code s} ends: all of it when it fits, else after the last word that fits, else mid-word. */
	private static int breakAt(String s, int width) {
		if (width(s) <= width) {
			return s.length();
		}
		int fit = fit(s, width);
		if (fit < s.length() && s.charAt(fit) == ' ') {
			return fit;
		}
		int space = s.lastIndexOf(' ', fit);
		if (space > 0) {
			return space;
		}
		return Math.max(fit, Character.charCount(s.codePointAt(0))); // always make progress
	}

	/** {@code s} when it fits {@code width}, else its longest prefix that fits with {@link #ELLIPSIS} after it. */
	static String ellipsize(String s, int width) {
		if (width(s) <= width) {
			return s;
		}
		int room = width - width(ELLIPSIS);
		String head = s.substring(0, fit(s, room)).stripTrailing();
		return head + ELLIPSIS;
	}

	/** The length (chars) of the longest prefix of {@code s} no wider than {@code width}, whole code points only. */
	private static int fit(String s, int width) {
		int w = 0;
		int i = 0;
		while (i < s.length()) {
			int cp = s.codePointAt(i);
			int a = advance(cp);
			if (w + a > width) {
				break;
			}
			w += a;
			i += Character.charCount(cp);
		}
		return i;
	}

	/** The width of {@code s} in font pixels (sum of {@link #advance}). */
	public static int width(String s) {
		int w = 0;
		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			w += advance(cp);
			i += Character.charCount(cp);
		}
		return w;
	}

	/**
	 * A character's advance in the vanilla default font (glyph width + 1, {@code assets/minecraft/font/include/default.json}
	 * bitmaps): 6 for most of ASCII, narrower for the thin glyphs. Characters outside the bitmaps' Latin range fall back to
	 * Unifont; they get a wider, conservative advance so a line never overflows.
	 */
	public static int advance(int cp) {
		return switch (cp) {
			case '!', ',', '.', ':', ';', '|', 'i', '\'' -> 2;
			case 'l', '`' -> 3;
			case ' ', 'I', '[', ']', 't', '·' -> 4;
			case '"', '(', ')', '*', '<', '>', 'f', 'k', '{', '}' -> 5;
			case '@', '~' -> 7;
			case '…' -> 8;
			default -> cp < 0x0250 ? 6 : cp < 0x2E80 ? 7 : 9;
		};
	}
}
