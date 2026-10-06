package dev.agentcraft.journal;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;

/**
 * The layering rules of the world journal (contract J1, docs/BUILDINGS.md "World journal"), pure: no world, no files.
 * Every AgentCraft world change is an {@link Entry} of {@link Cell}s {@code {pos, layer, before, after}}. The cells of
 * all active entries at one position form a <b>stack</b> ordered by their layer (the change made later is higher; a cell
 * keeps its layer when it moves to another entry, see {@link #transfer}); the top cell's {@code after} is what the world
 * should show there.
 *
 * <p><b>Undo</b> ({@link #planUndo}) takes entries out of the stacks, top-down per position:
 * <ul>
 * <li>a cell on top restores the world: a {@link Policy#BOX} entry (a building's or a fixture's site: Remove puts back
 * exactly what was there, the player's later changes inside the box included) always writes its {@code before}; a
 * {@link Policy#CELL} entry (roads, trophies) writes it only while the world still holds its {@code after} (cells the
 * player changed since are left alone);</li>
 * <li>a cell under a newer one changes nothing in the world: <b>ownership passes down</b>, the newer cell's {@code before}
 * becomes what this entry's undo would have made of it (its {@code before}, or for a CELL entry whose {@code after} the
 * newer cell did not find, the newer cell's {@code before} as it is). Undoing the newer entry later then restores
 * the right block: overlapping changes undo in any order with no holes and no resurrected blocks.</li>
 * </ul>
 * An undone entry keeps its cells, what the undo wrote and every hand-down it made, until the next world start settles
 * it: {@link #reactivate} reverses the hand-downs (the undo never reached the disk), a release forgets it.
 */
public final class Journal {
	private Journal() {
	}

	/** How an entry's undo treats the cells it is on top of. */
	public enum Policy {
		/** Always restore {@code before} (a building's or fixture's site: the box comes back exactly). */
		BOX,
		/** Restore {@code before} only where the world still holds {@code after} (roads, trophies). */
		CELL
	}

	public enum Status {
		ACTIVE,
		/** Undone, kept until the next world start settles it (crash safety). */
		UNDONE
	}

	/**
	 * A block as the journal keeps it: the block state as NBT and the block entity's data, if any. Treated as immutable.
	 *
	 * <p>The state is kept in 26.x's form, {@code {id, properties}}: what {@code NbtUtils.writeBlockState} and structure
	 * templates write, and the only keys {@code NbtUtils.readBlockState} reads (it returns air for a tag without
	 * {@code id}). A state in the older form {@code {Name, Properties}} (a snapshot or road file written before 26.x, an
	 * older journal) is converted when the value is made ({@link #canonical}), so values compare equal whichever form they
	 * were read from and always decode to their block.
	 */
	public record Value(CompoundTag state, @Nullable CompoundTag nbt) {
		/** The block id key of a state ({@code NbtUtils}, 26.x). */
		public static final String ID = "id";
		/** The properties key of a state ({@code NbtUtils}, 26.x). */
		public static final String PROPERTIES = "properties";
		/** The older keys ({@code Name}, {@code Properties}), read and converted, never written. */
		static final String OLD_ID = "Name";
		static final String OLD_PROPERTIES = "Properties";

		public Value {
			Objects.requireNonNull(state, "state");
			state = canonical(state);
		}

		/**
		 * {@code state} in the 26.x form: the same tag when it has no older key, else a copy with {@code Name} and
		 * {@code Properties} renamed (a key in the new form wins when a tag has both). Pure.
		 */
		public static CompoundTag canonical(CompoundTag state) {
			if (!state.contains(OLD_ID) && !state.contains(OLD_PROPERTIES)) {
				return state;
			}
			CompoundTag t = state.copy();
			net.minecraft.nbt.Tag name = t.get(OLD_ID);
			t.remove(OLD_ID);
			if (name != null && !t.contains(ID)) {
				t.put(ID, name);
			}
			net.minecraft.nbt.Tag props = t.get(OLD_PROPERTIES);
			t.remove(OLD_PROPERTIES);
			if (props != null && !t.contains(PROPERTIES)) {
				t.put(PROPERTIES, props);
			}
			return t;
		}

		/** A plain block by id (tests, air). */
		public static Value of(String name) {
			CompoundTag t = new CompoundTag();
			t.putString(ID, name);
			return new Value(t, null);
		}

		/** A block by id with block state properties ({@code "type", "bottom"} pairs). */
		public static Value of(String name, String... props) {
			CompoundTag t = new CompoundTag();
			t.putString(ID, name);
			if (props.length > 0) {
				CompoundTag p = new CompoundTag();
				for (int i = 0; i + 1 < props.length; i += 2) {
					p.putString(props[i], props[i + 1]);
				}
				t.put(PROPERTIES, p);
			}
			return new Value(t, null);
		}

		public Value withNbt(@Nullable CompoundTag data) {
			return new Value(state, data);
		}

		/** The block id ({@code minecraft:air} when the state names none). */
		public String name() {
			return state.getStringOr(ID, "minecraft:air");
		}

		/** A block state property as written ({@code ""} when the state has none by that name). */
		public String property(String key) {
			return state.getCompoundOrEmpty(PROPERTIES).getStringOr(key, "");
		}

		@Override
		public String toString() {
			return name() + (state.get(PROPERTIES) == null ? "" : state.get(PROPERTIES).toString()) + (nbt == null ? "" : " +nbt");
		}
	}

	public static final Value AIR = Value.of("minecraft:air");

	/** One changed cell: its position ({@code BlockPos.asLong}), its layer, the block before, the block after (null: unknown, an imported snapshot). */
	public record Cell(long pos, long layer, Value before, @Nullable Value after) {
		public Cell withBefore(Value b) {
			return new Cell(pos, layer, b, after);
		}
	}

	/** A hand-down an undo made: {@code to}'s cell at {@code pos} had {@code was} as its before and got {@code now}. {@code order}: the plan's order. */
	public record HandDown(int order, String to, long pos, Value was, Value now) {
	}

	/**
	 * What an undo did to an entry: its group (the entries undone together: a building and its trophies), when, the value
	 * the undo wrote at each position (positions where it wrote nothing are absent), the hand-downs it made.
	 */
	public record Undo(String group, long at, Map<Long, Value> written, List<HandDown> handed) {
		public Undo {
			// not Map.copyOf: its open addressing clusters on packed block positions (seconds to minutes for a large undo)
			written = java.util.Collections.unmodifiableMap(new HashMap<>(written));
			handed = List.copyOf(handed);
		}
	}

	/**
	 * A world change. {@code kind}: building, road, trophy, ...; {@code owner}: the record it belongs to (a building's or
	 * road's id); {@code meta}: the owner's record when the entry was made (crash repair rebuilds a lost record from it).
	 */
	public record Entry(String id, String kind, String owner, String dimension, Policy policy, long createdAt, Status status, List<Cell> cells,
		@Nullable Undo undo, @Nullable JsonObject meta) {
		public Entry {
			cells = List.copyOf(cells);
		}

		public boolean active() {
			return status == Status.ACTIVE;
		}

		public Entry withCells(List<Cell> c) {
			return new Entry(id, kind, owner, dimension, policy, createdAt, status, c, undo, meta);
		}

		public Entry undone(Undo u) {
			return new Entry(id, kind, owner, dimension, policy, createdAt, Status.UNDONE, cells, u, meta);
		}

		public Entry reactivated() {
			return new Entry(id, kind, owner, dimension, policy, createdAt, Status.ACTIVE, cells, null, meta);
		}

		public Entry withMeta(@Nullable JsonObject m) {
			return new Entry(id, kind, owner, dimension, policy, createdAt, status, cells, undo, m);
		}

		/** The box of its cells {minX, minY, minZ, maxX, maxY, maxZ}, or null without cells. */
		public int @Nullable [] box() {
			return Journal.box(cells);
		}

		public @Nullable Cell cell(long pos) {
			for (Cell c : cells) {
				if (c.pos() == pos) {
					return c;
				}
			}
			return null;
		}
	}

	/** The world, for an undo: whether the block at {@code pos} still is {@code after} (as the entry's kind compares). */
	@FunctionalInterface
	public interface World {
		boolean holds(long pos, Value after);
	}

	/** Whether a recorded value {@code v} (a newer cell's before) is {@code after}, compared as {@link World} would. */
	@FunctionalInterface
	public interface Match {
		boolean same(Value v, Value after);

		Match EQUAL = Value::equals;
	}

	/** One block an undo puts into the world: {@code by} the entry whose cell wrote it last, with that entry's policy. */
	public record Write(long pos, Value value, String by, Policy policy) {
	}

	/** Per undone entry: cells restored (on top, written), changed (on top, the player changed them: left), covered (under a newer cell: handed down). */
	public record Stats(int restored, int changed, int covered) {
	}

	/**
	 * What an undo does: the blocks it writes (positions in the order they were met), the entries it changes (the undone
	 * ones, now {@link Status#UNDONE}, and those that got a hand-down), per undone entry its {@link Stats}.
	 */
	public record UndoPlan(List<Write> writes, Map<String, Entry> updated, Map<String, Stats> stats) {
	}

	/**
	 * Plans undoing {@code ids} (active entries, all in {@code loaded}) together, as group {@code group}. {@code loaded}
	 * must hold every active entry with a cell at any position of theirs (the stacks are built from it). Pure.
	 */
	public static UndoPlan planUndo(Collection<Entry> loaded, Collection<String> ids, String group, long at, World world, Match match) {
		Map<String, Entry> byId = new LinkedHashMap<>();
		for (Entry e : loaded) {
			byId.put(e.id(), e);
		}
		Set<String> undo = new HashSet<>(ids);
		for (String id : ids) {
			Entry e = byId.get(id);
			if (e == null || !e.active()) {
				throw new IllegalArgumentException("not an active entry: " + id);
			}
		}
		// stacks at the positions the undone entries touch
		Set<Long> positions = new java.util.LinkedHashSet<>();
		for (String id : ids) {
			for (Cell c : byId.get(id).cells()) {
				positions.add(c.pos());
			}
		}
		Map<Long, List<String[]>> stacks = stacks(byId.values(), positions); // pos -> [entryId] bottom..top (by layer)
		Map<String, Map<Long, Cell>> cellsOf = new HashMap<>();
		for (Entry e : byId.values()) {
			Map<Long, Cell> m = new HashMap<>();
			for (Cell c : e.cells()) {
				m.put(c.pos(), c);
			}
			cellsOf.put(e.id(), m);
		}
		Map<String, Map<Long, Value>> newBefore = new HashMap<>(); // hand-down targets: entry -> pos -> before
		Map<String, List<HandDown>> handed = new HashMap<>();
		Map<String, Map<Long, Value>> written = new HashMap<>();
		Map<String, int[]> stats = new HashMap<>();
		for (String id : ids) {
			stats.put(id, new int[3]);
			handed.put(id, new ArrayList<>());
			written.put(id, new HashMap<>());
		}
		List<Write> writes = new ArrayList<>();
		int order = 0;
		for (long pos : positions) {
			List<String[]> stack = new ArrayList<>(stacks.get(pos));
			Value world0 = null; // null = the world as it is; else what this plan wrote there
			String writer = null;
			boolean wrote = false;
			for (int k = stack.size() - 1; k >= 0; k--) {
				String eid = stack.get(k)[0];
				if (!undo.contains(eid)) {
					continue;
				}
				Entry e = byId.get(eid);
				Cell cell = cellsOf.get(eid).get(pos);
				// the nearest cell above that stays
				String above = null;
				for (int j = k + 1; j < stack.size(); j++) {
					if (!undo.contains(stack.get(j)[0])) {
						above = stack.get(j)[0];
						break;
					}
				}
				if (above == null) {
					// on top (nothing that stays is above it): restore the world
					boolean ours;
					if (e.policy() == Policy.BOX) {
						ours = true;
					} else if (cell.after() == null) {
						ours = false;
					} else {
						ours = world0 == null ? world.holds(pos, cell.after()) : match.same(world0, cell.after());
					}
					if (ours) {
						world0 = cell.before();
						writer = eid;
						wrote = true;
						stats.get(eid)[0]++;
					} else {
						stats.get(eid)[1]++;
					}
				} else {
					// under a cell that stays: ownership passes down
					Map<Long, Value> nb = newBefore.computeIfAbsent(above, x -> new HashMap<>());
					Value v = nb.containsKey(pos) ? nb.get(pos) : cellsOf.get(above).get(pos).before();
					Value r;
					if (e.policy() == Policy.BOX) {
						r = cell.before();
					} else {
						r = cell.after() != null && match.same(v, cell.after()) ? cell.before() : v;
					}
					if (!r.equals(v)) {
						nb.put(pos, r);
						handed.get(eid).add(new HandDown(order++, above, pos, v, r));
					}
					stats.get(eid)[2]++;
				}
				stack.remove(k);
			}
			if (wrote) {
				writes.add(new Write(pos, world0, writer, byId.get(writer).policy()));
				for (String[] s : stacks.get(pos)) {
					if (undo.contains(s[0])) {
						written.get(s[0]).put(pos, world0);
					}
				}
			}
		}
		Map<String, Entry> updated = new LinkedHashMap<>();
		for (var t : newBefore.entrySet()) {
			Entry e = byId.get(t.getKey());
			List<Cell> cs = new ArrayList<>(e.cells().size());
			for (Cell c : e.cells()) {
				Value b = t.getValue().get(c.pos());
				cs.add(b == null ? c : c.withBefore(b));
			}
			updated.put(e.id(), e.withCells(cs));
		}
		Map<String, Stats> st = new LinkedHashMap<>();
		for (String id : ids) {
			updated.put(id, byId.get(id).undone(new Undo(group, at, written.get(id), handed.get(id))));
			int[] s = stats.get(id);
			st.put(id, new Stats(s[0], s[1], s[2]));
		}
		return new UndoPlan(List.copyOf(writes), updated, st);
	}

	/**
	 * Takes the undone entries of {@code group} back into the stacks (the undo never reached the disk: the world still
	 * shows them): their hand-downs are reversed, newest first, where the receiving cell still holds what was handed
	 * (a cell changed again since is left as it is). {@code loaded}: the group's entries and every entry they handed to.
	 * Returns the changed entries. Pure.
	 */
	public static Map<String, Entry> reactivate(Collection<Entry> loaded, String group) {
		Map<String, Entry> byId = new LinkedHashMap<>();
		for (Entry e : loaded) {
			byId.put(e.id(), e);
		}
		List<HandDown> all = new ArrayList<>();
		List<String> members = new ArrayList<>();
		for (Entry e : loaded) {
			if (e.status() == Status.UNDONE && e.undo() != null && e.undo().group().equals(group)) {
				members.add(e.id());
				all.addAll(e.undo().handed());
			}
		}
		all.sort(Comparator.comparingInt(HandDown::order).reversed());
		Map<String, Map<Long, Value>> before = new HashMap<>();
		Map<String, Map<Long, Cell>> cellsOf = new HashMap<>(); // the receiving entries' cells by position (Entry.cell is a scan: quadratic here)
		for (HandDown h : all) {
			Entry to = byId.get(h.to());
			if (to == null || !to.active()) {
				continue; // the receiving entry is gone (released or undone itself): nothing to give back
			}
			Map<Long, Value> m = before.computeIfAbsent(h.to(), x -> new HashMap<>());
			Cell c = cellsOf.computeIfAbsent(h.to(), x -> index(to)).get(h.pos());
			Value cur = m.containsKey(h.pos()) ? m.get(h.pos()) : c == null ? null : c.before();
			if (cur != null && cur.equals(h.now())) {
				m.put(h.pos(), h.was());
			}
		}
		Map<String, Entry> out = new LinkedHashMap<>();
		for (var t : before.entrySet()) {
			Entry e = byId.get(t.getKey());
			List<Cell> cs = new ArrayList<>(e.cells().size());
			for (Cell c : e.cells()) {
				Value b = t.getValue().get(c.pos());
				cs.add(b == null ? c : c.withBefore(b));
			}
			out.put(e.id(), e.withCells(cs));
		}
		for (String id : members) {
			out.put(id, byId.get(id).reactivated());
		}
		return out;
	}

	/**
	 * Moves the cells at {@code positions} from {@code from} to {@code to}, each keeping its layer, before and after (a
	 * road's cells that another road still runs on: that road now owns them and its undo restores them). A position
	 * {@code to} already has is left with {@code from}. Returns both entries changed. Pure.
	 */
	public static Map<String, Entry> transfer(Entry from, Entry to, Set<Long> positions) {
		Set<Long> has = new HashSet<>();
		for (Cell c : to.cells()) {
			has.add(c.pos());
		}
		List<Cell> keep = new ArrayList<>();
		List<Cell> given = new ArrayList<>(to.cells());
		for (Cell c : from.cells()) {
			if (positions.contains(c.pos()) && !has.contains(c.pos())) {
				given.add(c);
			} else {
				keep.add(c);
			}
		}
		Map<String, Entry> out = new LinkedHashMap<>();
		out.put(from.id(), from.withCells(keep));
		out.put(to.id(), to.withCells(given));
		return out;
	}

	/**
	 * Folds an older entry entirely covered by newer cells into them (a trophy sign rewritten in the same slot: the new
	 * trophy's before becomes the old one's, so the stack does not grow with every award). Null when any of its cells is
	 * on top (it still shows in the world): then it stays. Otherwise the entries that got its cells' befores (the caller
	 * then drops {@code older}). Pure.
	 */
	public static @Nullable Map<String, Entry> absorb(Collection<Entry> loaded, String older) {
		World never = (p, v) -> {
			throw new IllegalStateException("covered");
		};
		UndoPlan p;
		try {
			p = planUndo(loaded, List.of(older), older, 0L, never, Match.EQUAL);
		} catch (IllegalStateException e) {
			return null;
		}
		if (!p.writes().isEmpty() || p.stats().get(older).restored() + p.stats().get(older).changed() > 0) {
			return null;
		}
		Map<String, Entry> out = new LinkedHashMap<>(p.updated());
		out.remove(older);
		return out;
	}

	/** The stack at {@code pos}: the active cells there, bottom (oldest layer) first, with their entries. Pure. */
	public static List<Map.Entry<Entry, Cell>> stack(Collection<Entry> loaded, long pos) {
		List<Map.Entry<Entry, Cell>> out = new ArrayList<>();
		for (Entry e : loaded) {
			if (!e.active()) {
				continue;
			}
			Cell c = e.cell(pos);
			if (c != null) {
				out.add(Map.entry(e, c));
			}
		}
		out.sort(Comparator.comparingLong((Map.Entry<Entry, Cell> x) -> x.getValue().layer()).thenComparing(x -> x.getKey().id()));
		return out;
	}

	/** An entry's cells by position. */
	private static Map<Long, Cell> index(Entry e) {
		Map<Long, Cell> m = new HashMap<>(Math.max(16, e.cells().size() * 4 / 3 + 1));
		for (Cell c : e.cells()) {
			m.put(c.pos(), c);
		}
		return m;
	}

	/** pos -> the active entries with a cell there, bottom first (by layer; ties by id). */
	private static Map<Long, List<String[]>> stacks(Collection<Entry> entries, Set<Long> positions) {
		Map<Long, List<Object[]>> tmp = new HashMap<>();
		for (Entry e : entries) {
			if (!e.active()) {
				continue;
			}
			for (Cell c : e.cells()) {
				if (positions.contains(c.pos())) {
					tmp.computeIfAbsent(c.pos(), x -> new ArrayList<>()).add(new Object[] {c.layer(), e.id()});
				}
			}
		}
		Map<Long, List<String[]>> out = new HashMap<>();
		for (var t : tmp.entrySet()) {
			List<Object[]> l = t.getValue();
			l.sort(Comparator.comparingLong((Object[] o) -> (Long) o[0]).thenComparing(o -> (String) o[1]));
			List<String[]> ids = new ArrayList<>();
			for (Object[] o : l) {
				ids.add(new String[] {(String) o[1]});
			}
			out.put(t.getKey(), ids);
		}
		return out;
	}

	/** {minX, minY, minZ, maxX, maxY, maxZ} of {@code cells}, or null when empty. */
	static int @Nullable [] box(List<Cell> cells) {
		if (cells.isEmpty()) {
			return null;
		}
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (Cell c : cells) {
			int x = x(c.pos());
			int y = y(c.pos());
			int z = z(c.pos());
			b[0] = Math.min(b[0], x);
			b[1] = Math.min(b[1], y);
			b[2] = Math.min(b[2], z);
			b[3] = Math.max(b[3], x);
			b[4] = Math.max(b[4], y);
			b[5] = Math.max(b[5], z);
		}
		return b;
	}

	// BlockPos.asLong's packing (26 bits x, 12 bits y, 26 bits z), so positions match the game's without loading it

	public static long pos(int x, int y, int z) {
		return net.minecraft.core.BlockPos.asLong(x, y, z);
	}

	public static int x(long pos) {
		return net.minecraft.core.BlockPos.getX(pos);
	}

	public static int y(long pos) {
		return net.minecraft.core.BlockPos.getY(pos);
	}

	public static int z(long pos) {
		return net.minecraft.core.BlockPos.getZ(pos);
	}
}
