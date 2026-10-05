package dev.agentcraft.ui;

import java.util.Arrays;

/**
 * Where a nameplate's leader line must be left out so it never runs across another plate (or a reserved billboard such
 * as the podium's bubble) and its text: pure, unit-tested in {@code LeaderGapsTest}. Screen space, y down. The line is
 * vertical at {@code lineX} from {@code lineTop} to {@code lineBottom}; every obstacle rectangle it passes (within
 * {@code xMargin} of its sides) cuts a gap from its top to its bottom, widened by {@code pad} at both ends. Gaps are
 * sorted and merged; when there are more than {@code max}, the last ones merge into one (a longer gap, never a line
 * across a plate).
 */
public final class LeaderGaps {
	private LeaderGaps() {
	}

	/**
	 * Fills {@code out} with gap pairs (top, bottom), sorted top to bottom and not overlapping; returns how many.
	 *
	 * @param rects obstacle rectangles as x0, y0, x1, y1 quadruples ({@code count} of them)
	 * @param out room for at least {@code 2 * max} values
	 */
	public static int compute(float lineX, float lineTop, float lineBottom, float[] rects, int count, float xMargin, float pad, int max, float[] out) {
		if (max <= 0 || lineBottom <= lineTop) {
			return 0;
		}
		float[] tmp = new float[2 * Math.max(1, count)];
		int n = 0;
		for (int r = 0; r < count; r++) {
			int o = 4 * r;
			float x0 = rects[o];
			float y0 = rects[o + 1];
			float x1 = rects[o + 2];
			float y1 = rects[o + 3];
			if (lineX < x0 - xMargin || lineX > x1 + xMargin || y1 <= lineTop || y0 >= lineBottom) {
				continue;
			}
			tmp[2 * n] = Math.max(y0, lineTop) - pad;
			tmp[2 * n + 1] = Math.min(y1, lineBottom) + pad;
			n++;
		}
		if (n == 0) {
			return 0;
		}
		// sort the pairs by their top (insertion sort: a handful of plates)
		for (int i = 1; i < n; i++) {
			float a = tmp[2 * i];
			float b = tmp[2 * i + 1];
			int j = i - 1;
			while (j >= 0 && tmp[2 * j] > a) {
				tmp[2 * (j + 1)] = tmp[2 * j];
				tmp[2 * (j + 1) + 1] = tmp[2 * j + 1];
				j--;
			}
			tmp[2 * (j + 1)] = a;
			tmp[2 * (j + 1) + 1] = b;
		}
		// merge overlapping or touching gaps
		int m = 0;
		for (int i = 0; i < n; i++) {
			float a = tmp[2 * i];
			float b = tmp[2 * i + 1];
			if (m > 0 && a <= out[2 * (m - 1) + 1]) {
				out[2 * (m - 1) + 1] = Math.max(out[2 * (m - 1) + 1], b);
			} else if (m < max) {
				out[2 * m] = a;
				out[2 * m + 1] = b;
				m++;
			} else {
				// no slot left: the last gap reaches down over this one too
				out[2 * (m - 1) + 1] = Math.max(out[2 * (m - 1) + 1], b);
			}
		}
		return m;
	}

	/** The drawn pieces of the line between the gaps (top, bottom pairs), for tests and QA. */
	public static float[] segments(float lineTop, float lineBottom, float[] gaps, int count) {
		float[] seg = new float[2 * (count + 1)];
		int s = 0;
		float top = lineTop;
		for (int i = 0; i < count; i++) {
			float g0 = gaps[2 * i];
			float g1 = gaps[2 * i + 1];
			if (g0 > top) {
				seg[2 * s] = top;
				seg[2 * s + 1] = Math.min(g0, lineBottom);
				s++;
			}
			top = Math.max(top, g1);
		}
		if (top < lineBottom) {
			seg[2 * s] = top;
			seg[2 * s + 1] = lineBottom;
			s++;
		}
		return Arrays.copyOf(seg, 2 * s);
	}
}
