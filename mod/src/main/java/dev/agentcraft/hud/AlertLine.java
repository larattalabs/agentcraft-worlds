package dev.agentcraft.hud;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;

/**
 * The HUD alert line (docs/WAVE2.md W5): what needs the player, one compact row under the goal bar,
 * e.g. "2 decisions · 1 blocked · 3 replies · usage paused until 14:20". Each part only when non-zero;
 * the hold ({@code foreman.status.hold}, contract C9) with its {@code until}. Pure, unit-tested
 * ({@code AlertLineTest}); the client draws the parts with their status dots.
 *
 * <p>Three widths: {@link Level#FULL} ("2 decisions"), {@link Level#SHORT} ("2 dec", "1 blk", "3 msg",
 * "paused → 14:20") and {@link Level#DOTS} (just the numbers next to their coloured dots). {@link #fit}
 * picks the widest that fits.
 */
public record AlertLine(int decisions, int blocked, int replies, int prs, @Nullable String holdReason, @Nullable Long holdUntil,
	@Nullable String holdMessage) {

	public static final String SEP = " · ";
	public static final AlertLine NONE = new AlertLine(0, 0, 0, 0, null, null, null);

	public AlertLine {
		decisions = Math.max(0, decisions);
		blocked = Math.max(0, blocked);
		replies = Math.max(0, replies);
		prs = Math.max(0, prs);
		holdReason = holdReason == null || holdReason.isBlank() ? null : holdReason.strip().toLowerCase(Locale.ROOT);
	}

	/** No PRs needing attention. */
	public AlertLine(int decisions, int blocked, int replies, @Nullable String holdReason, @Nullable Long holdUntil, @Nullable String holdMessage) {
		this(decisions, blocked, replies, 0, holdReason, holdUntil, holdMessage);
	}

	public enum Level {
		FULL, SHORT, DOTS
	}

	/**
	 * One part of the line. {@code kind}: decisions, blocked, replies, prs, hold; {@code family}: the status-dot
	 * family the client draws in front of it (waiting, error, thinking, working, idle).
	 */
	public record Part(String kind, String family, String full, String brief, String dots) {
		public String at(Level l) {
			return switch (l) {
				case FULL -> full;
				case SHORT -> brief;
				case DOTS -> dots;
			};
		}
	}

	/** Anything to show (some count non-zero or a hold). */
	public boolean visible() {
		return decisions > 0 || blocked > 0 || replies > 0 || prs > 0 || holdReason != null;
	}

	/**
	 * The Inbox's "Needs you" group size (the inbox tab badge, the away toast): decisions + blocked + replies + PRs,
	 * plus one while a hold is on (the Inbox lists the hold as an item; {@code InboxModel.Counts.needsYou}).
	 */
	public int needsYou() {
		return decisions + blocked + replies + prs + (holdReason != null ? 1 : 0);
	}

	public List<Part> parts(ZoneId zone, long now) {
		List<Part> out = new ArrayList<>();
		if (decisions > 0) {
			out.add(new Part("decisions", "waiting", plural(decisions, "decision", "decisions"), decisions + " dec", Integer.toString(decisions)));
		}
		if (blocked > 0) {
			out.add(new Part("blocked", "error", blocked + " blocked", blocked + " blk", Integer.toString(blocked)));
		}
		if (replies > 0) {
			out.add(new Part("replies", "thinking", plural(replies, "reply", "replies"), replies + " msg", Integer.toString(replies)));
		}
		if (prs > 0) {
			out.add(new Part("prs", "working", prs == 1 ? "1 PR" : prs + " PRs", prs + " PR", Integer.toString(prs)));
		}
		if (holdReason != null) {
			out.add(new Part("hold", "idle", holdText(holdReason, holdUntil, zone, now, false), holdText(holdReason, holdUntil, zone, now, true),
				holdDots(holdReason, holdUntil, zone, now)));
		}
		return out;
	}

	/** The line as text at {@code level} ("" when nothing needs the player). */
	public String text(Level level, ZoneId zone, long now) {
		StringBuilder b = new StringBuilder();
		for (Part p : parts(zone, now)) {
			if (b.length() > 0) {
				b.append(level == Level.DOTS ? "  " : SEP);
			}
			b.append(p.at(level));
		}
		return b.toString();
	}

	/**
	 * The widest level whose row fits {@code maxW}: each part costs {@code dotW} (its dot and gap) plus its
	 * text, parts are separated by {@code sepW}, and {@code tailW} (the key hint) is added once. Null when even
	 * the dots do not fit.
	 */
	public @Nullable Level fit(ToIntFunction<String> width, int maxW, int dotW, int sepW, int tailW, ZoneId zone, long now) {
		List<Part> ps = parts(zone, now);
		for (Level l : Level.values()) {
			if (rowWidth(ps, l, width, dotW, sepW) + tailW <= maxW) {
				return l;
			}
		}
		return null;
	}

	public static int rowWidth(List<Part> ps, Level l, ToIntFunction<String> width, int dotW, int sepW) {
		int w = 0;
		for (int i = 0; i < ps.size(); i++) {
			if (i > 0) {
				w += sepW;
			}
			w += dotW + width.applyAsInt(ps.get(i).at(l));
		}
		return w;
	}

	/**
	 * "usage paused until 14:20", "usage paused until Mon 14:20" (not today), "usage paused" (no until);
	 * "sign-in needed"; "Claude offline, retry 14:20". {@code brief}: "paused → 14:20", "sign-in", "offline".
	 */
	public static String holdText(String reason, @Nullable Long until, ZoneId zone, long now, boolean brief) {
		String at = until == null || until <= 0 ? null : clock(until, zone, now);
		return switch (reason) {
			case "usage" -> brief ? at == null ? "paused" : "paused → " + at : at == null ? "usage paused" : "usage paused until " + at;
			case "auth" -> brief ? "sign-in" : "Claude sign-in needed";
			case "offline" -> brief ? "offline" : at == null ? "Claude offline" : "Claude offline, retry " + at;
			default -> brief ? "paused" : at == null ? "agents paused" : "agents paused until " + at;
		};
	}

	private static String holdDots(String reason, @Nullable Long until, ZoneId zone, long now) {
		return switch (reason) {
			case "usage" -> until == null || until <= 0 ? "paused" : clock(until, zone, now);
			case "auth" -> "sign-in";
			case "offline" -> "offline";
			default -> "paused";
		};
	}

	/** "14:20" today, else "Mon 14:20". */
	public static String clock(long ts, ZoneId zone, long now) {
		ZonedDateTime t = Instant.ofEpochMilli(ts).atZone(zone);
		LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
		DateTimeFormatter f = t.toLocalDate().equals(today) ? DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
			: DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT);
		return t.format(f);
	}

	static String plural(int n, String one, String many) {
		return n + " " + (n == 1 ? one : many);
	}
}
