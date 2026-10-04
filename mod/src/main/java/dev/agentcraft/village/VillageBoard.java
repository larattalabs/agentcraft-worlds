package dev.agentcraft.village;

import dev.agentcraft.building.Displays;
import dev.agentcraft.trophy.TrophyEvents;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the village board shows (docs/VILLAGE.md V2), pure so it can be tested without a game: one row per building
 * (name, repos, lead, its active goal with progress, open PRs and PRs merged this week), the newest milestones (goals
 * done, PRs merged, tasks merged locally, trophies hung; newest first) and the hold line; plus the paging and layout
 * arithmetic the renderer uses. The client maps its Foreman records, buildings, leads and the trophy ledger to the
 * input records below.
 *
 * <p>Rules shared with the rest of the mod, so the board never disagrees with the world:
 * <ul>
 * <li>a goal belongs to a building by {@link Displays#goalBelongs} (its repos, its lead; home takes the rest), the
 * active goal is the newest open one (planning or active) by {@code updatedAt};</li>
 * <li>milestones are {@link TrophyEvents#catchUp}'s awards (the same keys as the trophies), so a milestone whose
 * trophy hangs on a wall is marked, and a hung trophy the Foreman no longer knows (an older database) still shows;</li>
 * <li>"this week" starts on the local Monday at 00:00 ({@link #weekStart}).</li>
 * </ul>
 */
public final class VillageBoard {
	/** How long a page of buildings stays up before the next one (ms). */
	public static final int PAGE_MS = 10_000;
	/** At most this many milestones are kept (the layout shows what fits). */
	public static final int MAX_MILESTONES = 12;

	private VillageBoard() {
	}

	// ------------------------------------------------------------------ input

	/**
	 * A building (never a fixture) as the board sees it.
	 *
	 * @param repos the repo ids (what goals and PRs match); {@code repoNames} what the row shows (the Foreman's names)
	 * @param leadId the lead assigned to it, null when Marlow leads it (home, overflow) or leads are unknown
	 * @param lead the lead's display name ("Marlow (home)", "no lead: Foreman offline")
	 */
	public record Site(String id, String name, List<String> repos, List<String> repoNames, @Nullable String leadId, String lead, boolean home) {
		public Site {
			repos = List.copyOf(repos);
			repoNames = List.copyOf(repoNames);
		}

		/** A site whose repos show by their ids. */
		public Site(String id, String name, List<String> repos, @Nullable String leadId, String lead, boolean home) {
			this(id, name, repos, repos, leadId, lead, home);
		}
	}

	/** @param progress 0..1 */
	public record Goal(String id, String text, boolean open, double progress, List<String> repos, @Nullable String leadId, long updatedAt) {
		public Goal {
			repos = List.copyOf(repos);
		}
	}

	/**
	 * A task's PR.
	 *
	 * @param at when it last changed (for merged: the merge)
	 * @param failing its checks are failing
	 */
	public record Pr(String repo, String prId, boolean open, boolean merged, long at, boolean failing) {
	}

	/** A trophy sign hanging in a building (the ledger's entry). */
	public record Hung(String buildingId, String key, List<String> lines, long at) {
		public Hung {
			lines = List.copyOf(lines);
		}
	}

	/**
	 * Everything the board is built from.
	 *
	 * @param awards {@link TrophyEvents#catchUp} of the Foreman state (oldest first is fine: sorted here)
	 * @param hold the hold line ("usage paused until 14:20"), null when nothing holds the agents
	 * @param needsYou the Inbox's Needs you count (the right-click goes to the Inbox when it is above 0)
	 * @param needsLine the HUD line for it ("2 decisions · 1 blocked"), "" when nothing
	 */
	public record Input(List<Site> sites, List<Goal> goals, List<Pr> prs, List<TrophyEvents.Award> awards, List<Hung> hung, @Nullable String hold,
		int needsYou, String needsLine, long now, ZoneId zone) {
		public Input {
			sites = List.copyOf(sites);
			goals = List.copyOf(goals);
			prs = List.copyOf(prs);
			awards = List.copyOf(awards);
			hung = List.copyOf(hung);
			needsLine = needsLine == null ? "" : needsLine;
		}
	}

	// ------------------------------------------------------------------ output

	/**
	 * A building's row.
	 *
	 * @param goal the active goal's first line, null when it has none
	 * @param progress the active goal's progress 0..1, -1 when there is none
	 * @param moreGoals other open goals of the building
	 * @param prsOpen its repos' open PRs; {@code prsMerged} merged this week; {@code failing} an open PR's checks fail
	 */
	public record Row(String buildingId, String name, String repos, @Nullable String leadId, String lead, boolean home, @Nullable String goal,
		double progress, int moreGoals, int prsOpen, int prsMerged, boolean failing) {
	}

	/**
	 * A milestone line.
	 *
	 * @param kind {@code goal}, {@code pr}, {@code merge} or {@code trophy} (a hung sign the Foreman does not know)
	 * @param label "Goal done", "PR #612 merged", "Merged t12", "Trophy"
	 * @param where the building's name, else the repo
	 * @param trophy a trophy for it hangs in a building
	 */
	public record Milestone(String key, String kind, String label, String text, @Nullable String buildingId, String where, long at, boolean trophy) {
	}

	/** The board's content: rows in building order (home first), milestones newest first. */
	public record Content(List<Row> rows, List<Milestone> milestones, @Nullable String hold, int needsYou, String needsLine, long weekStart,
		int prsOpen, int prsMerged) {
		public Content {
			rows = List.copyOf(rows);
			milestones = List.copyOf(milestones);
		}

		/** An empty board (no world, no buildings). */
		public static final Content EMPTY = new Content(List.of(), List.of(), null, 0, "", 0, 0, 0);
	}

	/** The least time between two rebuilds caused only by a Foreman revision change (it moves on every log line). */
	public static final long REVISION_THROTTLE_MS = 1000;

	/**
	 * Whether the board content must be rebuilt now. A change of anything but the Foreman revision (buildings, world, hung
	 * trophies, the Inbox, the link, the 30 s bucket) or a first build rebuilds at once; a revision change alone waits until
	 * {@link #REVISION_THROTTLE_MS} have passed since the last build (it is still pending then, so the last change is never lost).
	 */
	public static boolean rebuildDue(boolean otherChanged, boolean revisionChanged, boolean never, long nowMs, long builtAtMs) {
		if (never || otherChanged) {
			return true;
		}
		return revisionChanged && (nowMs - builtAtMs >= REVISION_THROTTLE_MS || nowMs < builtAtMs);
	}

	/** Builds the content. */
	public static Content build(Input in) {
		long week = weekStart(in.now(), in.zone());
		Set<String> built = new HashSet<>();
		Map<String, Site> byRepo = new HashMap<>();
		for (Site s : in.sites()) {
			built.addAll(s.repos());
			s.repos().forEach(r -> byRepo.putIfAbsent(r, s));
		}
		List<Site> order = new ArrayList<>(in.sites());
		order.sort(Comparator.comparing((Site s) -> !s.home())); // home first, then placement order (stable)
		List<Row> rows = new ArrayList<>();
		int open = 0;
		int merged = 0;
		for (Site s : order) {
			Goal active = null;
			int more = 0;
			for (Goal g : in.goals()) {
				if (!g.open() || !Displays.goalBelongs(g.leadId(), g.repos(), s.leadId(), s.repos(), s.home(), built::contains)) {
					continue;
				}
				if (active == null || g.updatedAt() > active.updatedAt()) {
					if (active != null) {
						more++;
					}
					active = g;
				} else {
					more++;
				}
			}
			int po = 0;
			int pm = 0;
			boolean failing = false;
			for (Pr p : in.prs()) {
				if (!s.repos().contains(p.repo())) {
					continue;
				}
				if (p.open()) {
					po++;
					failing |= p.failing();
				} else if (p.merged() && p.at() >= week) {
					pm++;
				}
			}
			open += po;
			merged += pm;
			rows.add(new Row(s.id(), s.name(), String.join(", ", s.repoNames()), s.leadId(), s.lead(), s.home(), active == null ? null : firstLine(active.text()),
				active == null ? -1 : clamp01(active.progress()), more, po, pm, failing));
		}
		return new Content(rows, milestones(in, byRepo), in.hold(), Math.max(0, in.needsYou()), in.needsLine(), week, open, merged);
	}

	/** The newest milestones: the awards (goal done, PR merged, merged locally) and hung trophies no award explains. */
	static List<Milestone> milestones(Input in, Map<String, Site> byRepo) {
		Map<String, Hung> hungByKey = new HashMap<>();
		for (Hung h : in.hung()) {
			hungByKey.merge(h.key(), h, (a, b) -> a.at() >= b.at() ? a : b);
		}
		Map<String, String> names = new HashMap<>();
		in.sites().forEach(s -> names.put(s.id(), s.name()));
		List<Milestone> out = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (TrophyEvents.Award a : in.awards()) {
			if (!keys.add(a.key())) {
				continue;
			}
			dev.agentcraft.building.Trophy t = a.trophy();
			Site s = byRepo.get(t.repo());
			String label = switch (t.kind()) {
				case GOAL -> "Goal done";
				case PR -> "PR #" + stripHash(t.prId()) + " merged";
				case MERGE -> "Merged " + (t.taskId() == null ? "" : t.taskId());
			};
			String kind = t.kind().name().toLowerCase(Locale.ROOT);
			out.add(new Milestone(a.key(), kind, label.strip(), firstLine(t.title() == null ? "" : t.title()), s == null ? null : s.id(),
				s == null ? t.repo() : s.name(), a.at(), hungByKey.containsKey(a.key())));
		}
		for (Hung h : hungByKey.values()) {
			if (keys.contains(h.key())) {
				continue;
			}
			// sign lines: line 1 says what (Merged PR #612 / Goal done), lines 2-3 the title, line 4 the date
			List<String> ls = h.lines();
			String label = ls.isEmpty() ? "Trophy" : ls.get(0);
			StringBuilder text = new StringBuilder();
			for (int i = 1; i < Math.min(3, ls.size()); i++) {
				if (!ls.get(i).isBlank()) {
					text.append(text.isEmpty() ? "" : " ").append(ls.get(i).strip());
				}
			}
			out.add(new Milestone(h.key(), "trophy", label, text.toString(), h.buildingId(), names.getOrDefault(h.buildingId(), h.buildingId()), h.at(),
				true));
		}
		out.sort(Comparator.comparingLong(Milestone::at).reversed().thenComparing(Milestone::key));
		return out.size() > MAX_MILESTONES ? List.copyOf(out.subList(0, MAX_MILESTONES)) : out;
	}

	/** The start of "this week": the local Monday at 00:00 of the week holding {@code now}, in ms. */
	public static long weekStart(long now, ZoneId zone) {
		return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone)
			.toInstant().toEpochMilli();
	}

	static String firstLine(String s) {
		String t = s == null ? "" : s.strip();
		int nl = t.indexOf('\n');
		return (nl >= 0 ? t.substring(0, nl) : t).replace("`", "").replace("**", "").replace('\t', ' ').strip();
	}

	private static String stripHash(@Nullable String id) {
		String s = id == null ? "" : id.strip();
		return s.startsWith("#") ? s.substring(1) : s;
	}

	private static double clamp01(double p) {
		return Double.isNaN(p) ? 0 : Math.max(0, Math.min(1, p));
	}

	// ------------------------------------------------------------------ paging

	/** Which rows a page shows: {@code index} of {@code count} pages, rows {@code from} (inclusive) .. {@code to} (exclusive). */
	public record Page(int index, int count, int from, int to) {
	}

	/**
	 * The page shown at {@code now}: pages turn every {@link #PAGE_MS}; {@code forced} (>= 0, DevBridge) holds one page.
	 * Zero rows or a zero capacity is one empty page.
	 */
	public static Page page(int rows, int perPage, long now, int forced) {
		if (rows <= 0 || perPage <= 0) {
			return new Page(0, 1, 0, 0);
		}
		int count = (rows + perPage - 1) / perPage;
		int index = forced >= 0 ? Math.min(forced, count - 1) : (int) Math.floorMod(now / PAGE_MS, (long) count);
		int from = index * perPage;
		return new Page(index, count, from, Math.min(rows, from + perPage));
	}

	// ------------------------------------------------------------------ layout

	/** Panel pixel density: about 156 px of height (52 px per block on a 3-high board, the bundled one), 40..64. */
	public static int density(int w, int h) {
		return Math.max(40, Math.min(64, Math.round(156f / Math.max(1, h))));
	}

	/** Header strip, building row and milestone heights (face pixels; the font is 8 px high). */
	public static final int TRIM_TEXELS = 2;
	public static final int HEADER_H = 14;
	/**
	 * A building card: three text lines from {@link #CARD_LINE_Y}, {@link #CARD_LINE_STEP} apart (the font draws 9 px with
	 * descenders), then the 2 px progress bar along the bottom edge; ROW_H keeps the third line clear of the bar and the
	 * card's border (it was 30: the goal line's bottom ran under the bar).
	 */
	public static final int ROW_H = 33;
	public static final int CARD_LINE_Y = 3;
	public static final int CARD_LINE_STEP = 9;
	/** Face pixels at a card's bottom the progress bar and the card's border take. */
	public static final int CARD_BAR_H = 3;
	public static final int ROW_GAP = 3;
	public static final int MILESTONE_H = 20;
	public static final int MILESTONE_GAP = 3;
	public static final int HOLD_H = 13;
	public static final int FOOTER_H = 11;
	/** Below this panel width (px) the milestones get no column of their own: they show under the rows when room is left. */
	public static final int TWO_COLUMNS_MIN_W = 200;

	/**
	 * Where things go on a panel of {@code pw} x {@code ph} face pixels: the inner box, the building column
	 * ({@code bx0..bx1}) and its row capacity, the milestone column ({@code mx0..mx1}, empty when there is no room) and
	 * how many milestones fit, the hold banner and the footer. Pure arithmetic: the renderer ellipsizes text to these.
	 */
	public record Layout(int pw, int ph, int trim, int ix0, int iy0, int ix1, int iy1, int bodyY, int bodyBottom, int bx0, int bx1, int perPage,
		int mx0, int mx1, int milestones, int holdY, int footerY) {
		public boolean twoColumns() {
			return mx1 > mx0;
		}
	}

	public static Layout layout(int pw, int ph, int ppb, boolean hold) {
		int trim = Math.max(1, Math.round(ppb * TRIM_TEXELS / 16f));
		int ix0 = trim + 3;
		int iy0 = trim + 2;
		int ix1 = pw - trim - 3;
		int iy1 = ph - trim - 2;
		int bodyY = iy0 + HEADER_H + 2;
		int footerY = iy1 - FOOTER_H;
		int holdY = hold ? footerY - HOLD_H - 1 : footerY;
		int bodyBottom = holdY - 2;
		int bodyH = Math.max(0, bodyBottom - bodyY);
		boolean two = ix1 - ix0 >= TWO_COLUMNS_MIN_W;
		int bx0 = ix0;
		int bx1 = two ? ix0 + Math.round((ix1 - ix0) * 0.6f) - 4 : ix1;
		int mx0 = two ? bx1 + 8 : 0;
		int mx1 = two ? ix1 : 0;
		int perPage = Math.max(0, (bodyH + ROW_GAP) / (ROW_H + ROW_GAP));
		int ms = two ? Math.max(0, (bodyH - 11 + MILESTONE_GAP) / (MILESTONE_H + MILESTONE_GAP)) : 0;
		return new Layout(pw, ph, trim, ix0, iy0, ix1, iy1, bodyY, bodyBottom, bx0, bx1, perPage, mx0, mx1, ms, holdY, footerY);
	}

	/**
	 * The short form for a milestone's line on the board, where the column is ~90 px wide and the label comes first:
	 * "now", "5m", "13h", "2d" (the long form left "Goal done" no room: it drew as "…").
	 */
	public static String agoShort(long at, long now) {
		long s = Math.max(0, (now - at) / 1000);
		if (s < 60) {
			return "now";
		}
		if (s < 3600) {
			return (s / 60) + "m";
		}
		if (s < 86_400) {
			return (s / 3600) + "h";
		}
		return (s / 86_400) + "d";
	}

	/** "3 days ago", "5 min ago", "just now" for a milestone (coarse: the board is read from across a square). */
	public static String ago(long at, long now) {
		long s = Math.max(0, (now - at) / 1000);
		if (s < 60) {
			return "just now";
		}
		if (s < 3600) {
			return (s / 60) + " min ago";
		}
		if (s < 86_400) {
			long h = s / 3600;
			return h + (h == 1 ? " hour ago" : " hours ago");
		}
		long d = s / 86_400;
		return d == 1 ? "yesterday" : d + " days ago";
	}
}
