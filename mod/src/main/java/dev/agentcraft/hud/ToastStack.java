package dev.agentcraft.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Which in-game toasts show, in what order, and what waits (pure, unit-tested in {@code ToastStackTest}). "Need you"
 * toasts come first and are never pushed out or hidden by info toasts: the queue evicts its oldest info toast first,
 * the stack places need-you toasts before any info toast, and once a need-you toast does not fit no info toast is
 * drawn after it. What is not drawn is counted on one "+N more" line under the stack (when that line fits).
 */
public final class ToastStack {
	/** Toasts drawn at most at once; the rest wait (and count in "+N more"). */
	public static final int MAX_SHOWN = 3;
	/** Toasts kept at most (shown and waiting); beyond it {@link #evict} picks one to drop. */
	public static final int MAX_KEPT = 8;

	private ToastStack() {
	}

	/** One toast for the layout: a need-you toast or not, and its height (GUI px). */
	public record Item(boolean need, int height) {
	}

	/**
	 * The stack as drawn: indices into the input list with their y, then the "+N more" line ({@code moreY} -1 = not
	 * drawn, e.g. no room or nothing hidden). {@code hidden} counts what is not drawn, {@code hiddenNeed} the need-you
	 * toasts among them.
	 */
	public record Layout(List<Integer> shown, List<Integer> ys, int hidden, int hiddenNeed, int moreY) {
	}

	/** Display order of a newest-first list: need-you toasts first, each group newest first. Returns input indices. */
	public static List<Integer> order(List<Boolean> need) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < need.size(); i++) {
			if (need.get(i)) {
				out.add(i);
			}
		}
		for (int i = 0; i < need.size(); i++) {
			if (!need.get(i)) {
				out.add(i);
			}
		}
		return out;
	}

	/**
	 * The toast to drop from a newest-first list that holds more than {@code cap}: the oldest info toast, else (all need
	 * you) the oldest one. -1 when nothing has to go.
	 */
	public static int evict(List<Boolean> need, int cap) {
		if (need.size() <= cap) {
			return -1;
		}
		for (int i = need.size() - 1; i >= 0; i--) {
			if (!need.get(i)) {
				return i;
			}
		}
		return need.size() - 1;
	}

	/**
	 * Lays out {@code items} (already in display order, see {@link #order}) from {@code top} down, {@code gap} between
	 * toasts, never below {@code limit}. At most {@code maxShown} are drawn. A toast that would reach below the limit is
	 * left out and a shorter one after it may still fit, except that after a need-you toast that does not fit only
	 * need-you toasts are tried. While toasts remain, room for the "+N more" line ({@code moreH}) is kept under the
	 * stack when it can be; a need-you toast that fits only without that room still shows (the line is then left out).
	 */
	public static Layout layout(List<Item> items, int top, int limit, int gap, int maxShown, int moreH) {
		List<Integer> shown = new ArrayList<>();
		List<Integer> ys = new ArrayList<>();
		int y = top;
		boolean needOnly = false;
		boolean noMoreLine = false;
		for (int i = 0; i < items.size(); i++) {
			Item it = items.get(i);
			if (shown.size() >= maxShown) {
				break;
			}
			if (needOnly && !it.need()) {
				continue;
			}
			// the "+N more" line is needed when anything before was left out or anything comes after
			boolean alone = i == items.size() - 1 && shown.size() == i;
			boolean fitsWithMore = y + it.height() + (alone ? 0 : gap + moreH) <= limit;
			boolean fits = y + it.height() <= limit;
			if (fitsWithMore || fits && it.need()) {
				shown.add(i);
				ys.add(y);
				y += it.height() + gap;
				if (!fitsWithMore) {
					noMoreLine = true;
				}
			} else if (it.need()) {
				needOnly = true;
			}
		}
		int hidden = items.size() - shown.size();
		int hiddenNeed = 0;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).need() && !shown.contains(i)) {
				hiddenNeed++;
			}
		}
		int moreY = hidden > 0 && !noMoreLine && y + moreH <= limit ? y : -1;
		return new Layout(List.copyOf(shown), List.copyOf(ys), hidden, hiddenNeed, moreY);
	}

	/** The "+N more" line: "+2 more", "+3 more · 1 needs you". */
	public static String moreText(int hidden, int hiddenNeed) {
		String s = "+" + hidden + " more";
		if (hiddenNeed > 0) {
			s += " · " + hiddenNeed + (hiddenNeed == 1 ? " needs you" : " need you");
		}
		return s;
	}
}
