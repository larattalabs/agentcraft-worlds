package dev.agentcraft.building;

import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pure routing rules for a world with several buildings (docs/BUILDINGS.md "Client (routing)"): which
 * repo an agent works in, which layout that puts it in, which regions of the world belong to a layout,
 * and which tasks a task wall shows. No world or client state: callers pass the published
 * {@link Buildings#sites()} and {@link Anchors#current()}, so this is unit-tested on its own.
 *
 * <p>Layouts are identified by name. The home building is reachable both through {@link Anchors#current()}
 * (Anchors' own revision) and through its repo ({@code building:<id>}, revision {@code placedAt});
 * {@link #layoutFor} folds the second into the first so every consumer sees one layout per building.
 */
public final class Routing {
	/** Task wall binding prefix: {@code repo:<repoId>} shows only that repo's tasks. */
	public static final String REPO_BINDING = "repo:";
	/** Status lamp binding prefix for a repo's CI: {@code ci:<repoId>}; {@code ci:#n} = the n-th repo (HQ studio only). */
	public static final String CI_BINDING = "ci:";
	/** Layout name prefix of a building ({@link Building#layout()}). */
	public static final String BUILDING_LAYOUT = "building:";

	/**
	 * A building as published for readers on any thread: immutable, with its layout built once.
	 *
	 * @param box the world box the building occupies (lamps and screens set into its walls are inside)
	 */
	public record Site(String buildingId, List<String> repos, boolean home, Anchors.Layout layout, Anchors.Bounds box) {
		public Site {
			repos = List.copyOf(repos);
		}

		static Site of(Building b) {
			return new Site(b.id(), b.repos(), b.home(), b.layout(), b.box());
		}
	}

	/**
	 * A part of the world driven by one layout: the HQ studio (its walkable bounds) or a building (its
	 * box). {@code building} is false for the studio / a hand-published layout, where the studio-only
	 * conventions ({@code ci:#n} = the n-th repo) apply.
	 */
	public record Region(Anchors.Layout layout, Anchors.Bounds area, boolean building) {
		/** Whether (x, y, z) lies in the area grown by {@code margin} blocks on every side. */
		public boolean contains(int x, int y, int z, int margin) {
			return x >= area.minX() - margin && x <= area.maxX() + margin && y >= area.minY() - margin && y <= area.maxY() + margin
				&& z >= area.minZ() - margin && z <= area.maxZ() + margin;
		}

		/** Whether the horizontal footprint (any height) grown by {@code margin} contains (x, z). */
		public boolean containsColumn(int x, int z, int margin) {
			return x >= area.minX() - margin && x <= area.maxX() + margin && z >= area.minZ() - margin && z <= area.maxZ() + margin;
		}
	}

	private Routing() {
	}

	// ------------------------------------------------------------------ agents

	/**
	 * The repo whose building an agent works in: its own {@code repoId}, else its task's repo, else for
	 * the lead the current goal's repo. Off-shift agents ({@code active=false}) have none: they idle in
	 * the home building's lounge. Blank ids count as absent.
	 */
	public static @Nullable String agentRepo(@Nullable String agentRepo, @Nullable String taskRepo, boolean lead, @Nullable String goalRepo,
		boolean active) {
		if (!active) {
			return null;
		}
		if (!blank(agentRepo)) {
			return agentRepo;
		}
		if (!blank(taskRepo)) {
			return taskRepo;
		}
		return lead && !blank(goalRepo) ? goalRepo : null;
	}

	/**
	 * The layout for {@code repo}: the building hosting it, else {@code current} (home building, HQ studio,
	 * or empty). A building that is {@code current} under another revision returns {@code current} itself.
	 */
	public static Anchors.Layout layoutFor(@Nullable String repo, List<Site> sites, Anchors.Layout current) {
		if (blank(repo)) {
			return current;
		}
		for (Site s : sites) {
			if (s.repos().contains(repo)) {
				return s.layout().name().equals(current.name()) ? current : s.layout();
			}
		}
		return current;
	}

	/**
	 * The layout of building {@code buildingId} (a lead's assignment), else {@code current} (home). Like
	 * {@link #layoutFor}, the home building is returned as {@code current}.
	 */
	public static Anchors.Layout layoutForBuilding(@Nullable String buildingId, List<Site> sites, Anchors.Layout current) {
		if (blank(buildingId)) {
			return current;
		}
		for (Site s : sites) {
			if (s.buildingId().equals(buildingId)) {
				return s.layout().name().equals(current.name()) ? current : s.layout();
			}
		}
		return current;
	}

	/** The building whose box (grown by {@code margin}) contains the block, or null (the HQ studio / open world). */
	public static @Nullable Site siteAt(List<Site> sites, int x, int y, int z, int margin) {
		for (Site s : sites) {
			Anchors.Bounds b = s.box();
			if (x >= b.minX() - margin && x <= b.maxX() + margin && y >= b.minY() - margin && y <= b.maxY() + margin && z >= b.minZ() - margin
				&& z <= b.maxZ() + margin) {
				return s;
			}
		}
		return null;
	}

	/**
	 * Whether {@code layout} has a place for an agent at {@code stationKey} ("desk", a shared station or
	 * "lounge"): its desk, a slot of the station, or (the assigner's fallback) a lounge slot. An agent
	 * routed to a building that cannot host it goes home instead of disappearing.
	 */
	public static boolean canHost(Anchors.Layout layout, String stationKey, String agentId) {
		if (layout.isEmpty()) {
			return false;
		}
		if (stationKey.equals("desk") && layout.get(AnchorNames.desk(agentId)) != null) {
			return true;
		}
		return layout.get(AnchorNames.slot(stationKey, 1)) != null || layout.get(AnchorNames.slot(AnchorNames.LOUNGE, 1)) != null;
	}

	// ------------------------------------------------------------------ regions

	/** Every distinct layout in the world: {@code current} first (when not empty), then the other buildings. */
	public static List<Anchors.Layout> layouts(Anchors.Layout current, List<Site> sites) {
		List<Anchors.Layout> out = new ArrayList<>();
		if (!current.isEmpty()) {
			out.add(current);
		}
		for (Site s : sites) {
			if (!s.layout().name().equals(current.name())) {
				out.add(s.layout());
			}
		}
		return List.copyOf(out);
	}

	/**
	 * The world regions that belong to a layout: {@code current} (its bounds, or its building's box when it
	 * is the home building) first, then every other building's box. Layouts without bounds have no region.
	 */
	public static List<Region> regions(Anchors.Layout current, List<Site> sites) {
		List<Region> out = new ArrayList<>();
		if (!current.isEmpty() && current.bounds() != null) {
			Site home = null;
			for (Site s : sites) {
				if (s.layout().name().equals(current.name())) {
					home = s;
				}
			}
			out.add(new Region(current, home != null ? home.box() : current.bounds(), home != null));
		}
		for (Site s : sites) {
			if (!s.layout().name().equals(current.name())) {
				out.add(new Region(s.layout(), s.box(), true));
			}
		}
		return List.copyOf(out);
	}

	/** The first region whose area grown by {@code margin} contains the block, or null. */
	public static @Nullable Region regionAt(List<Region> regions, int x, int y, int z, int margin) {
		for (Region r : regions) {
			if (r.contains(x, y, z, margin)) {
				return r;
			}
		}
		return null;
	}

	/** Changes whenever a region's layout (name or revision) or the set of regions changes. */
	public static long signature(List<Region> regions) {
		long h = regions.size();
		for (Region r : regions) {
			h = h * 31 + r.layout().name().hashCode();
			h = h * 31 + Long.hashCode(r.layout().revision());
			h = h * 31 + r.area().hashCode();
		}
		return h;
	}

	// ------------------------------------------------------------------ task walls

	/**
	 * The repo a task wall is filtered to: {@code repo:<repoId>} -> repoId; anything else (empty, a
	 * column filter, ...) -> null = all tasks. An unrewritten wing placeholder ({@code repo:#2}) stays a
	 * filter that matches no repo (docs/BUILDINGS.md), so that wall is empty rather than showing all.
	 */
	public static @Nullable String boardRepo(@Nullable String binding) {
		if (binding == null || !binding.startsWith(REPO_BINDING)) {
			return null;
		}
		String r = binding.substring(REPO_BINDING.length()).strip();
		return r.isEmpty() ? null : r;
	}

	/** Whether a wall filtered to {@code filterRepo} (null = all) shows a task of {@code taskRepo}. */
	public static boolean boardShows(@Nullable String filterRepo, @Nullable String taskRepo) {
		return filterRepo == null || filterRepo.equals(taskRepo);
	}

	/** {@code ci:#n} (a wing placeholder; the n-th repo only in the HQ studio). */
	public static boolean isCiPlaceholder(String binding) {
		return binding.startsWith(CI_BINDING + "#");
	}

	private static boolean blank(@Nullable String s) {
		return s == null || s.isBlank();
	}
}
