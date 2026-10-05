package dev.agentcraft.building;

import dev.agentcraft.journal.Journal;
import dev.agentcraft.journal.WorldJournal;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Leaves just outside a site, kept from decaying while it stands (docs/BUILDINGS.md "Held leaves"; ported from
 * Architect). Placing a site clears logs inside its box; leaves outside the box that hung on those logs then decay, and
 * Remove (which restores only the box) cannot bring them back. So placement makes those leaves persistent and records
 * them as a journal entry of their own ({@link #KIND}, {@link Journal.Policy#CELL}, owned by the building; before = the
 * natural leaf with its {@code distance}, after = the same leaf persistent). Remove and Move undo it with the site's
 * entry: a held leaf gets its natural state back once the box (and its logs) is restored; one the player broke or
 * replaced stays as it is.
 *
 * <p>Which leaves: non-persistent leaves with {@code distance} below 7 (7 decays anyway) within {@link #RADIUS} of the
 * box whose Manhattan distance to the box is at most their {@code distance}. A leaf's {@code distance} is the length of
 * its shortest face path to a log; a path from a log inside the box is at least as long as the leaf's Manhattan distance
 * to the box, so any leaf further away does not depend on the box. Server thread.
 */
public final class LeafGuard {
	/** The journal kind of a building's held leaves ({@link WorldJournal#LEAVES}). */
	public static final String KIND = WorldJournal.LEAVES;
	/** Leaves decay at distance 7, so nothing further than 6 from the box can depend on a log inside it. */
	public static final int RADIUS = 6;
	/** The journal kind of a building's leaf ring ({@link WorldJournal#LEAF_RING}). */
	public static final String RING_KIND = WorldJournal.LEAF_RING;
	/** How far around a snapshot box the leaf ring reaches ({@link #ring}): past box + 7, the reach of an exact Remove. */
	public static final int RING = RADIUS + 2;

	private LeafGuard() {
	}

	/**
	 * Hold and release never notify neighbours ({@link Block#UPDATE_KNOWN_SHAPE}): a shape update makes neighbouring leaves
	 * recompute their {@code distance}, and world generation leaves many of them stale (overlapping trees), so a recompute
	 * would change cells nobody recorded and Remove would not be exact.
	 */
	static int quiet(int flags) {
		return flags | Block.UPDATE_KNOWN_SHAPE;
	}

	/** Manhattan distance from a cell to the box (0 inside). Pure. */
	public static int distanceTo(Anchors.Bounds box, int x, int y, int z) {
		return gap(x, box.minX(), box.maxX()) + gap(y, box.minY(), box.maxY()) + gap(z, box.minZ(), box.maxZ());
	}

	private static int gap(int v, int min, int max) {
		return v < min ? min - v : v > max ? v - max : 0;
	}

	/** Whether a non-persistent leaf with {@code distance} at Manhattan distance {@code fromBox} (> 0) may hang on the box. Pure. */
	public static boolean mayDependOnBox(int distance, int fromBox) {
		return fromBox > 0 && distance < 7 && fromBox <= distance;
	}

	/** Whether two boxes come within {@code d} of each other (per axis). Pure. */
	public static boolean near(Anchors.Bounds a, Anchors.Bounds b, int d) {
		return a.minX() - d <= b.maxX() && b.minX() <= a.maxX() + d && a.minY() - d <= b.maxY() && b.minY() <= a.maxY() + d
			&& a.minZ() - d <= b.maxZ() && b.minZ() <= a.maxZ() + d;
	}

	/**
	 * Makes the leaves around {@code box} that may hang on it persistent (no neighbour updates) and returns them as journal
	 * cells at {@code layer}. Call it before the box is changed: a leaf's {@code distance} is read as it was. Leaves inside
	 * {@code skip} (standing sites' boxes: their cells are theirs) and in unloaded chunks are left alone.
	 */
	public static List<Journal.Cell> hold(ServerLevel level, Anchors.Bounds box, Collection<Anchors.Bounds> skip, long layer, int flags) {
		List<Journal.Cell> out = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int minY = Math.max(level.getMinY(), box.minY() - RADIUS);
		int maxY = Math.min(level.getMaxY(), box.maxY() + RADIUS);
		for (int z = box.minZ() - RADIUS; z <= box.maxZ() + RADIUS; z++) {
			for (int x = box.minX() - RADIUS; x <= box.maxX() + RADIUS; x++) {
				if (!level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				for (int y = minY; y <= maxY; y++) {
					int from = distanceTo(box, x, y, z);
					if (from == 0 || from > RADIUS) {
						continue;
					}
					BlockState s = level.getBlockState(p.set(x, y, z));
					if (!(s.getBlock() instanceof LeavesBlock) || s.getValue(LeavesBlock.PERSISTENT)
						|| !mayDependOnBox(s.getValue(LeavesBlock.DISTANCE), from) || inside(skip, x, y, z)) {
						continue;
					}
					BlockState held = s.setValue(LeavesBlock.PERSISTENT, true);
					level.setBlock(p, held, quiet(flags));
					out.add(new Journal.Cell(p.asLong(), layer, value(s), value(held)));
				}
			}
		}
		return out;
	}

	private static boolean inside(Collection<Anchors.Bounds> boxes, int x, int y, int z) {
		for (Anchors.Bounds b : boxes) {
			if (b.contains(x, y, z)) {
				return true;
			}
		}
		return false;
	}

	private static Journal.Value value(BlockState s) {
		return new Journal.Value(NbtUtils.writeBlockState(s), null);
	}

	/**
	 * The leaf ring of {@code box} (ported from Architect): every leaf within {@link #RING} of the box but outside it, as
	 * journal cells at {@code layer} whose before and after are the leaf as it is now. Read before the box changes. World
	 * generation leaves many distances larger than their nearest log gives (trees generated over each other); any shape
	 * update next to them lets the canopy relax to the true distances, up to about 7 blocks out, so placing and removing a
	 * site would change leaves nobody recorded. The ring's undo (CELL, quiet) gives each one its recorded state back where
	 * the cell still holds the same leaf ({@link #sameLeaf}: only {@code distance} may differ). Cells for which
	 * {@code skip} is true (the cells of other active journal entries: they are theirs) and cells in unloaded chunks are
	 * left out. Changes nothing.
	 */
	public static List<Journal.Cell> ring(ServerLevel level, Anchors.Bounds box, LongPredicate skip, long layer) {
		List<Journal.Cell> out = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int minY = Math.max(level.getMinY(), box.minY() - RING);
		int maxY = Math.min(level.getMaxY(), box.maxY() + RING);
		for (int z = box.minZ() - RING; z <= box.maxZ() + RING; z++) {
			for (int x = box.minX() - RING; x <= box.maxX() + RING; x++) {
				if (!level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				for (int y = minY; y <= maxY; y++) {
					if (box.contains(x, y, z)) {
						continue;
					}
					BlockState s = level.getBlockState(p.set(x, y, z));
					if (!(s.getBlock() instanceof LeavesBlock) || skip.test(p.asLong())) {
						continue;
					}
					Journal.Value v = value(s);
					out.add(new Journal.Cell(p.asLong(), layer, v, v));
				}
			}
		}
		return out;
	}

	/** The box {@link #ring} looks at: {@code box} grown by {@link #RING}. */
	public static Anchors.Bounds ringBounds(Anchors.Bounds box) {
		return new Anchors.Bounds(box.minX() - RING, box.minY() - RING, box.minZ() - RING, box.maxX() + RING, box.maxY() + RING, box.maxZ() + RING);
	}

	/**
	 * Whether {@code now} is still the leaf a ring recorded ({@code recorded}): the same block with the same
	 * {@code persistent} and {@code waterlogged}; only {@code distance} (vanilla's to change) may differ. A leaf the
	 * player broke, replaced, placed or waterlogged is not, so the ring leaves it alone.
	 */
	public static boolean sameLeaf(BlockState now, BlockState recorded) {
		return recorded.getBlock() instanceof LeavesBlock && now.is(recorded.getBlock())
			&& now.getValue(LeavesBlock.PERSISTENT) == recorded.getValue(LeavesBlock.PERSISTENT)
			&& now.getValue(LeavesBlock.WATERLOGGED) == recorded.getValue(LeavesBlock.WATERLOGGED);
	}

	/**
	 * Takes a hold back that never reached the journal (a placement rolled back): cells that still hold a persistent leaf
	 * of the same block get their before (no neighbour updates). Returns how many.
	 */
	public static int release(ServerLevel level, List<Journal.Cell> cells, int flags) {
		int n = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (Journal.Cell c : cells) {
			p.set(Journal.x(c.pos()), Journal.y(c.pos()), Journal.z(c.pos()));
			if (c.after() == null || !stillHeld(level.getBlockState(p), WorldJournal.state(level, c.after()))) {
				continue;
			}
			level.setBlock(p, WorldJournal.state(level, c.before()), quiet(flags));
			n++;
		}
		return n;
	}

	/**
	 * Whether {@code now} is still the leaf a hold made persistent ({@code placed}): the same block, still persistent. Its
	 * {@code distance} and {@code waterlogged} are vanilla's to change while it is held (persistent leaves still recompute
	 * their distance), so they are not compared.
	 */
	public static boolean stillHeld(BlockState now, BlockState placed) {
		return placed.getBlock() instanceof LeavesBlock && placed.getValue(LeavesBlock.PERSISTENT) && now.is(placed.getBlock())
			&& now.getValue(LeavesBlock.PERSISTENT);
	}

	/**
	 * {@code other} (how the other CELL entries recognise their blocks), with leaves recognised as leaves: a persistent
	 * leaf (a hold's after, or a player's leaf in a ring) by {@link #stillHeld}, a natural one (a ring's) by
	 * {@link #sameLeaf}. Their {@code distance} is never compared.
	 */
	public static BiPredicate<BlockState, BlockState> or(BiPredicate<BlockState, BlockState> other) {
		return (now, placed) -> !(placed.getBlock() instanceof LeavesBlock) ? other.test(now, placed)
			: placed.getValue(LeavesBlock.PERSISTENT) ? stillHeld(now, placed) : sameLeaf(now, placed);
	}
}
