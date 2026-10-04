package dev.agentcraft.building;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A building's trophy slots (docs/BUILDINGS.md "Trophy slots"): read from its pin's raw anchors ({@code trophy@<w>},
 * {@code trophy_<k>@<w>}, world space), so they match the template that stands there. Pure.
 */
public final class TrophySlots {
	public static final String BASE = "trophy";

	private TrophySlots() {
	}

	/**
	 * One sign cell.
	 *
	 * @param name the raw anchor name ({@code trophy_2@1}): the ledger's slot key
	 * @param k fill order (1 for {@code trophy@w})
	 * @param wing the wing it belongs to
	 * @param x the sign cell (likewise y, z)
	 * @param facing the way the sign's front faces: 0 south (+Z), 1 west (-X), 2 north (-Z), 3 east (+X)
	 */
	public record Slot(String name, int k, int wing, int x, int y, int z, int facing) {
		/** X offset from the sign cell to its support (the cell behind the sign, opposite {@link #facing}). */
		public int backX() {
			return facing == 1 ? 1 : facing == 3 ? -1 : 0;
		}

		/** Z offset from the sign cell to its support. */
		public int backZ() {
			return facing == 0 ? -1 : facing == 2 ? 1 : 0;
		}

		public boolean inside(Anchors.Bounds box) {
			return box.contains(x, y, z);
		}
	}

	/** The fill order of a trophy anchor name ({@code trophy@1} -> 1, {@code trophy_3@2} -> 3), or 0 when it is not one. */
	public static int orderOf(String rawName) {
		int at = rawName.lastIndexOf('@');
		String base = at < 0 ? rawName : rawName.substring(0, at);
		if (base.equals(BASE)) {
			return 1;
		}
		if (!base.startsWith(BASE + "_")) {
			return 0;
		}
		try {
			int k = Integer.parseInt(base.substring(BASE.length() + 1));
			return k >= 1 ? k : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Yaw (degrees, Minecraft) to {@link Slot#facing}: the nearest of south, west, north, east. */
	public static int facingOf(float yaw) {
		return Math.floorMod(Math.round(yaw / 90f), 4);
	}

	/** Every trophy slot among {@code rawAnchors} (all wings), wing then fill order. */
	public static List<Slot> all(Map<String, Anchor> rawAnchors) {
		List<Slot> out = new ArrayList<>();
		for (Anchor a : rawAnchors.values()) {
			int k = orderOf(a.name());
			int wing = BlueprintTransform.wingOf(a.name());
			if (k == 0 || wing < 1) {
				continue;
			}
			out.add(new Slot(a.name(), k, wing, (int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()), facingOf(a.yaw())));
		}
		out.sort(Comparator.comparingInt(Slot::wing).thenComparingInt(Slot::k).thenComparing(Slot::name));
		return out;
	}

	/** Wing {@code wing}'s slots in fill order, only those inside {@code box} (never hang a sign outside the building). */
	public static List<Slot> forWing(Map<String, Anchor> rawAnchors, int wing, Anchors.Bounds box) {
		List<Slot> out = new ArrayList<>();
		for (Slot s : all(rawAnchors)) {
			if (s.wing() == wing && s.inside(box)) {
				out.add(s);
			}
		}
		return out;
	}

	/**
	 * The wing a repo's trophies go to: its position in a group building's repos (1-based), 1 in a single building,
	 * 0 when the building does not host it.
	 */
	public static int wingFor(List<String> repos, boolean group, String repo) {
		int i = repos.indexOf(repo);
		if (i < 0) {
			return 0;
		}
		return group ? i + 1 : 1;
	}

	/**
	 * The slots in the order to try them for a new trophy: free slots in fill order first, then the taken ones from the
	 * oldest ({@link TrophyLedger.Entry#at}) to the newest. {@code taken}: the building's ledger entries by slot name.
	 */
	public static List<Slot> order(List<Slot> slots, Map<String, TrophyLedger.Entry> taken) {
		List<Slot> free = new ArrayList<>();
		List<Slot> used = new ArrayList<>();
		for (Slot s : slots) {
			(taken.containsKey(s.name()) ? used : free).add(s);
		}
		used.sort(Comparator.comparingLong((Slot s) -> taken.get(s.name()).at()).thenComparingInt(Slot::k));
		free.addAll(used);
		return free;
	}

	/** The slot a new trophy goes to ({@link #order}'s first), or null when there are none. */
	public static @Nullable Slot choose(List<Slot> slots, Map<String, TrophyLedger.Entry> taken) {
		List<Slot> o = order(slots, taken);
		return o.isEmpty() ? null : o.get(0);
	}

	/** The sign cells of every trophy slot (all wings) as packed {@link #cell} keys. */
	public static Set<Long> cells(Map<String, Anchor> rawAnchors) {
		Set<Long> out = new HashSet<>();
		for (Slot s : all(rawAnchors)) {
			out.add(cell(s.x(), s.y(), s.z()));
		}
		return out;
	}

	/** A block position as a set key. */
	public static long cell(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
	}

	/**
	 * Whether a block entity in a building's box is a trophy the mod hung and so does not hold up a removal
	 * ({@link Buildings#removalBlockers}): a sign standing on one of the building's pinned trophy cells. Anything else
	 * there (a chest the player put in the slot) still counts.
	 */
	public static boolean exempt(Set<Long> trophyCells, int x, int y, int z, boolean sign) {
		return sign && trophyCells.contains(cell(x, y, z));
	}
}
