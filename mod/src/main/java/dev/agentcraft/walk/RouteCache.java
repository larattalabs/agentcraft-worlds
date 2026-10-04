package dev.agentcraft.walk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Planned outdoor routes per building pair ({@code "<from layout>><to layout>"}; routes are not reversible:
 * an agent may drop 3 blocks but only climb 1). An entry goes when a block changes within
 * {@link #NEAR} blocks of one of its cells ({@link #invalidateNear}), when the buildings change
 * ({@link #clear}) and, checked by the caller before reuse, when its cells are no longer standable. Least
 * recently used entries beyond {@link #MAX} are dropped. Pure; client thread only.
 */
public final class RouteCache {
	public static final int MAX = 32;
	/** A block change this close (Chebyshev, blocks) to a route cell drops the route. */
	public static final int NEAR = 2;

	/** One cached route: the smoothed points, the raw cells and their box (for invalidation). */
	public record Route(String key, List<OutdoorPlanner.Point> points, long[] cells, double length, int nodes, long micros, int minX, int minY,
		int minZ, int maxX, int maxY, int maxZ) {

		public static Route of(String key, OutdoorPlanner p) {
			List<OutdoorPlanner.Point> pts = p.path();
			long[] cells = p.cells();
			if (pts == null || cells == null) {
				throw new IllegalStateException("no route: " + p.status());
			}
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (long c : cells) {
				minX = Math.min(minX, WalkCell.unpackX(c));
				minY = Math.min(minY, WalkCell.unpackY(c));
				minZ = Math.min(minZ, WalkCell.unpackZ(c));
				maxX = Math.max(maxX, WalkCell.unpackX(c));
				maxY = Math.max(maxY, WalkCell.unpackY(c));
				maxZ = Math.max(maxZ, WalkCell.unpackZ(c));
			}
			return new Route(key, pts, cells, p.length(), p.expanded(), p.micros(), minX, minY, minZ, maxX, maxY, maxZ);
		}

		/** Is block (x,y,z) within {@link #NEAR} of a cell (or the 2 cells above it, the agent's body)? */
		public boolean near(int x, int y, int z) {
			if (x < minX - NEAR || x > maxX + NEAR || z < minZ - NEAR || z > maxZ + NEAR || y < minY - NEAR - 1 || y > maxY + NEAR + 2) {
				return false;
			}
			for (long c : cells) {
				int cx = WalkCell.unpackX(c);
				int cy = WalkCell.unpackY(c);
				int cz = WalkCell.unpackZ(c);
				if (Math.abs(cx - x) <= NEAR && Math.abs(cz - z) <= NEAR && y >= cy - NEAR - 1 && y <= cy + NEAR + 1) {
					return true;
				}
			}
			return false;
		}
	}

	private final LinkedHashMap<String, Route> routes = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Route> eldest) {
			return size() > MAX;
		}
	};
	private long hits;
	private long misses;
	private long invalidations;

	public static String key(String fromLayout, String toLayout) {
		return fromLayout + ">" + toLayout;
	}

	/** The cached route for {@code key} (counts a hit or a miss), or null. */
	public @Nullable Route get(String key) {
		Route r = routes.get(key);
		if (r == null) {
			misses++;
		} else {
			hits++;
		}
		return r;
	}

	public void put(Route r) {
		routes.put(r.key(), r);
	}

	/** Drops one route (e.g. it failed its reuse check); true when it was there. */
	public boolean remove(String key) {
		boolean had = routes.remove(key) != null;
		if (had) {
			invalidations++;
		}
		return had;
	}

	/** A block changed at (x,y,z): drops the routes passing near it. Returns how many. */
	public int invalidateNear(int x, int y, int z) {
		int n = 0;
		for (Iterator<Route> it = routes.values().iterator(); it.hasNext();) {
			if (it.next().near(x, y, z)) {
				it.remove();
				n++;
			}
		}
		invalidations += n;
		return n;
	}

	/** Buildings changed (or another level): everything goes. */
	public void clear() {
		invalidations += routes.size();
		routes.clear();
	}

	public int size() {
		return routes.size();
	}

	public long hits() {
		return hits;
	}

	public long misses() {
		return misses;
	}

	public long invalidations() {
		return invalidations;
	}

	/** The cached routes, most recently used last. */
	public List<Route> routes() {
		return new ArrayList<>(routes.values());
	}
}
