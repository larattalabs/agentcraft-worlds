package dev.agentcraft.hub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Pure rules of the hub's Goals tab (docs/HUB.md "Repos and Goals tabs"): list order, unread, the digest's
 * sections and summary, standing instructions as text, and wrapping plan text to a pixel width. No game
 * or Foreman classes: the client maps its records in, so this is unit-tested ({@code GoalLogicTest}).
 */
public final class GoalLogic {
	private GoalLogic() {
	}

	// ------------------------------------------------------------------ list

	/** Newest first by creation time; ties by the id's number ("g12" after "g9"), then the id. */
	public static <T> List<T> newestFirst(Collection<T> goals, ToLongFunction<T> createdAt, Function<T, String> id) {
		List<T> out = new ArrayList<>(goals);
		Comparator<T> c = Comparator.comparingLong(createdAt);
		c = c.thenComparingLong(t -> idNumber(id.apply(t))).thenComparing(id);
		out.sort(c.reversed());
		return out;
	}

	/** The number in an id like "g12" (-1 when there is none). */
	static long idNumber(String id) {
		int i = id.length();
		while (i > 0 && Character.isDigit(id.charAt(i - 1))) {
			i--;
		}
		if (i == id.length() || id.length() - i > 18) {
			return -1;
		}
		return Long.parseLong(id.substring(i));
	}

	/** Whether a goal with its newest activity at {@code activity} is unread for a player who last saw it at {@code seen}. */
	public static boolean unread(long activity, long seen) {
		return activity > 0 && activity > seen;
	}

	/** Whether a goal's repos meet a building's repos (the list filter "by building"); an empty filter matches all. */
	public static boolean inBuilding(Collection<String> goalRepos, Collection<String> buildingRepos) {
		if (buildingRepos.isEmpty()) {
			return true;
		}
		for (String r : goalRepos) {
			if (buildingRepos.contains(r)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ digest

	/** One digest line (the client maps {@code Protocol.DigestLine} in). */
	public record Line(long ts, String kind, String text) {
	}

	/** A digest section: a title, a status-dot family and its lines (oldest first). */
	public record Section(String title, String family, List<Line> lines) {
	}

	/** Section order: what needs the player first, then problems, results, PR traffic, new work, answers, chat. */
	private static final String[][] SECTIONS = {
		{"Waiting for you", "waiting", "decision_waiting"},
		{"Blocked", "error", "task_blocked"},
		{"Done", "done", "goal_done,task_done,merged,pr_merged"},
		{"Pull requests", "working", "pr_opened,pr_comments"},
		{"New tasks", "thinking", "task_added"},
		{"Answered", "idle", "decision_answered"},
		{"Messages", "idle", "message"},
	};

	/** Groups lines into sections (empty ones left out); lines of an unknown kind go under Messages. Each section keeps time order. */
	public static List<Section> sections(List<Line> lines) {
		Map<String, List<Line>> by = new LinkedHashMap<>();
		for (String[] s : SECTIONS) {
			by.put(s[0], new ArrayList<>());
		}
		List<Line> sorted = new ArrayList<>(lines);
		sorted.sort(Comparator.comparingLong(Line::ts));
		for (Line l : sorted) {
			by.get(sectionOf(l.kind())[0]).add(l);
		}
		List<Section> out = new ArrayList<>();
		for (String[] s : SECTIONS) {
			List<Line> ls = by.get(s[0]);
			if (!ls.isEmpty()) {
				out.add(new Section(s[0], s[1], List.copyOf(ls)));
			}
		}
		return out;
	}

	private static String[] sectionOf(String kind) {
		for (String[] s : SECTIONS) {
			for (String k : s[2].split(",")) {
				if (k.equals(kind)) {
					return s;
				}
			}
		}
		return SECTIONS[SECTIONS.length - 1];
	}

	/** "2 done · 1 blocked · 1 waiting for you" (counts per kind family), or "nothing new". */
	public static String summary(List<Line> lines) {
		int waiting = 0;
		int blocked = 0;
		int done = 0;
		int prs = 0;
		int added = 0;
		int messages = 0;
		for (Line l : lines) {
			switch (l.kind()) {
				case "decision_waiting" -> waiting++;
				case "task_blocked" -> blocked++;
				case "task_done", "merged", "pr_merged", "goal_done" -> done++;
				case "pr_opened", "pr_comments" -> prs++;
				case "task_added" -> added++;
				case "decision_answered" -> {
				}
				default -> messages++;
			}
		}
		List<String> parts = new ArrayList<>();
		if (done > 0) {
			parts.add(done + " done");
		}
		if (blocked > 0) {
			parts.add(blocked + " blocked");
		}
		if (waiting > 0) {
			parts.add(waiting + " waiting for you");
		}
		if (prs > 0) {
			parts.add(prs + (prs == 1 ? " PR update" : " PR updates"));
		}
		if (added > 0) {
			parts.add(added + (added == 1 ? " new task" : " new tasks"));
		}
		if (messages > 0) {
			parts.add(messages + (messages == 1 ? " message" : " messages"));
		}
		return parts.isEmpty() ? "nothing new" : String.join(" · ", parts);
	}

	// ------------------------------------------------------------------ instructions

	/** Standing instructions typed one per line: trimmed, a leading "- " / "* " / "• " dropped, blank lines skipped. */
	public static List<String> instructionLines(String text) {
		List<String> out = new ArrayList<>();
		for (String raw : text.split("\n", -1)) {
			String s = raw.strip();
			if (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("• ")) {
				s = s.substring(2).strip();
			}
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ wrapping

	/**
	 * Wraps text (markdown kept as typed) to {@code width} pixels: each source line on its own, words moved
	 * whole, a word wider than the line broken by characters, continuation lines indented like the line's
	 * text after its list marker ("- ", "1. ", "&gt; ") or leading spaces. Blank lines stay. Tabs become two spaces.
	 */
	public static List<String> wrap(String text, int width, ToIntFunction<String> widthOf) {
		List<String> out = new ArrayList<>();
		for (String raw : text.replace("\r", "").replace("\t", "  ").split("\n", -1)) {
			wrapLine(raw.stripTrailing(), Math.max(1, width), widthOf, out);
		}
		while (out.size() > 1 && out.get(out.size() - 1).isEmpty()) {
			out.remove(out.size() - 1);
		}
		return out;
	}

	private static void wrapLine(String line, int width, ToIntFunction<String> widthOf, List<String> out) {
		if (widthOf.applyAsInt(line) <= width) {
			out.add(line);
			return;
		}
		String indent = " ".repeat(Math.min(hangingIndent(line), 12));
		if (widthOf.applyAsInt(indent + "mm") > width) {
			indent = "";
		}
		StringBuilder cur = new StringBuilder();
		int i = 0;
		boolean first = true;
		// leading spaces of the first line are kept as they are
		while (i < line.length() && line.charAt(i) == ' ') {
			cur.append(' ');
			i++;
		}
		while (i < line.length()) {
			int j = i;
			while (j < line.length() && line.charAt(j) != ' ') {
				j++;
			}
			String word = line.substring(i, j);
			int k = j;
			while (k < line.length() && line.charAt(k) == ' ') {
				k++;
			}
			String sep = cur.isEmpty() || cur.toString().isBlank() ? "" : " ";
			String candidate = cur + sep + word;
			if (widthOf.applyAsInt(candidate) <= width) {
				cur.setLength(0);
				cur.append(candidate);
			} else if (!cur.toString().isBlank()) {
				out.add(cur.toString());
				first = false;
				cur.setLength(0);
				cur.append(indent);
				continue; // retry the word on the new line
			} else {
				// a word longer than the line: break it by characters
				String prefix = cur.toString();
				int c = 0;
				while (c < word.length()) {
					int n = 1;
					while (c + n < word.length() && widthOf.applyAsInt(prefix + word.substring(c, c + n + 1)) <= width) {
						n++;
					}
					String piece = word.substring(c, c + n);
					c += n;
					if (c < word.length()) {
						out.add(prefix + piece);
						first = false;
						prefix = indent;
					} else {
						cur.setLength(0);
						cur.append(prefix).append(piece);
					}
				}
			}
			i = k;
		}
		if (!cur.toString().isBlank() || first) {
			out.add(cur.toString());
		}
	}

	/** Columns a wrapped continuation is indented by: leading spaces plus a list or quote marker. */
	static int hangingIndent(String line) {
		int i = 0;
		while (i < line.length() && line.charAt(i) == ' ') {
			i++;
		}
		String rest = line.substring(i);
		if (rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ") || rest.startsWith("> ")) {
			return i + 2;
		}
		int d = 0;
		while (d < rest.length() && d < 3 && Character.isDigit(rest.charAt(d))) {
			d++;
		}
		if (d > 0 && rest.length() > d + 1 && (rest.charAt(d) == '.' || rest.charAt(d) == ')') && rest.charAt(d + 1) == ' ') {
			return i + d + 2;
		}
		return i;
	}
}
