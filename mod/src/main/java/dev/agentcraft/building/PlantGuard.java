package dev.agentcraft.building;

import dev.agentcraft.journal.Journal;
import dev.agentcraft.journal.Support;
import dev.agentcraft.journal.WorldJournal;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The plants just outside a site (docs/BUILDINGS.md "Vines and hanging plants"): blocks that need a support
 * ({@link Support#needs}) such as vines on the outer faces of logs inside the box, cocoa on them, the vines hanging below
 * those, bamboo or sugar cane above a stalk the box cut, mushrooms beside the box. Placing the site pops the ones whose
 * support it took, and its Remove (whose writes are shape updates for the cells beside the box) can pop more; the box
 * restore cannot bring them back, they are outside it. So placement reads them before anything changes and records each
 * one as a cell of a journal entry of their own ({@link #KIND}, CELL, owned by the building; {@code before} the plant as it
 * was, {@code after} what the placement left: air when it popped, the same plant when it did not). Remove and Move undo it
 * with the site's entry: {@code WorldJournal.apply} writes them after the box, quietly, where the cell still holds
 * {@code after} (a block the player put there, or a plant the player broke, is left).
 *
 * <p><b>Restored, not held</b> (unlike leaves, {@link LeafGuard}): a vine has no persistent state, so keeping one in place
 * while the site stands would leave it floating against the building, popped by the next neighbour update (a player, an
 * agent's door) and then lost anyway (the CELL rule would no longer find its {@code after}). Letting it go at placement
 * (vanilla's shape update, or {@link #settle}) and recording air as its {@code after} gives the undo a stable condition.
 * Server thread.
 */
public final class PlantGuard {
	/** The journal kind of a building's plants ({@link WorldJournal#PLANTS}). */
	public static final String KIND = WorldJournal.PLANTS;
	/** How far around the snapshot box plants are recorded: the box's writes update the cells beside it. */
	public static final int REACH = 2;
	/** How far a run (a vine curtain, cave vines, dripstone, a bamboo or sugar cane stalk) is followed up or down from a recorded cell. */
	public static final int RUN = 48;

	private PlantGuard() {
	}

	/** Reads the world for {@link #select}: whether the cell holds a block that needs a support. */
	public interface World {
		boolean plant(int x, int y, int z);
	}

	/**
	 * The positions to record around {@code box} (Pure): every cell within {@link #REACH} of the box (per axis, outside it)
	 * that holds a plant ({@link Support#needs}), and the vertical runs of plants above and below those (at most
	 * {@link #RUN}): a vine curtain hanging from a vine beside the box pops all the way down, a bamboo stalk all the way up. Cells for which {@code skip} is true (other
	 * entries' cells, standing sites' boxes, the cut plants' guard cells) and cells outside {@code minY..maxY} are left out.
	 */
	public static List<Long> select(Anchors.Bounds box, int minY, int maxY, World world, LongPredicate skip) {
		Set<Long> out = new LinkedHashSet<>();
		int y0 = Math.max(minY, box.minY() - REACH);
		int y1 = Math.min(maxY, box.maxY() + REACH);
		for (int y = y0; y <= y1; y++) {
			for (int z = box.minZ() - REACH; z <= box.maxZ() + REACH; z++) {
				for (int x = box.minX() - REACH; x <= box.maxX() + REACH; x++) {
					if (box.contains(x, y, z) || !world.plant(x, y, z)) {
						continue;
					}
					long p = Journal.pos(x, y, z);
					if (skip.test(p)) {
						continue;
					}
					out.add(p);
					for (int dir = -1; dir <= 1; dir += 2) {
						for (int i = 1, yy = y + dir; i <= RUN && yy >= minY && yy <= maxY; i++, yy += dir) {
							if (box.contains(x, yy, z) || !world.plant(x, yy, z)) {
								break;
							}
							long q = Journal.pos(x, yy, z);
							if (!skip.test(q)) {
								out.add(q);
							}
						}
					}
				}
			}
		}
		return new ArrayList<>(out);
	}

	/**
	 * The plants around {@code box} as they are ({@link #select}; cells in unloaded chunks left out): position -> the
	 * block. Read before the box changes; changes nothing.
	 */
	public static Map<Long, Journal.Value> read(ServerLevel level, Anchors.Bounds box, LongPredicate skip) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		World w = (x, y, z) -> level.hasChunk(x >> 4, z >> 4) && Support.needs(level.getBlockState(p.set(x, y, z)));
		Map<Long, Journal.Value> out = new LinkedHashMap<>();
		for (long pos : select(box, level.getMinY(), level.getMaxY(), w, skip)) {
			out.put(pos, WorldJournal.valueAt(level, BlockPos.of(pos)));
		}
		return out;
	}

	/** The journal cells of {@code read} at {@code layer}: before = as read, after = what is there now (after the placement). */
	public static List<Journal.Cell> cells(ServerLevel level, Map<Long, Journal.Value> read, long layer) {
		List<Journal.Cell> out = new ArrayList<>(read.size());
		for (var e : read.entrySet()) {
			out.add(new Journal.Cell(e.getKey(), layer, e.getValue(), WorldJournal.valueAt(level, BlockPos.of(e.getKey()))));
		}
		return out;
	}

	/**
	 * After a placement: takes away, quietly and with no drops, every block that needs a support ({@link Support#needs}) in
	 * {@code box} or at {@code outside} that can no longer stand there (its support gone), until none is left. Vanilla would
	 * take them too, but some of them later and with drops: a bamboo or sugar cane stalk above a cut one breaks one segment a
	 * tick (scheduled ticks), dropping items the placement's drop clearing (now and 3 ticks later) no longer sees. Taken now,
	 * each outside one is recorded with air as its {@code after} ({@link #cells}) and Remove writes it back; the box's are the
	 * box's (its restore writes them). Returns how many.
	 */
	public static int settle(ServerLevel level, Anchors.Bounds box, java.util.Collection<Long> outside, int flags) {
		List<Long> cand = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = box.minY(); y <= box.maxY(); y++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				for (int x = box.minX(); x <= box.maxX(); x++) {
					if (Support.needs(level.getBlockState(p.set(x, y, z)))) {
						cand.add(p.asLong());
					}
				}
			}
		}
		cand.addAll(outside);
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		return settle(cand, pos -> {
			BlockState s = level.getBlockState(p.set(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos)));
			return Support.needs(s) && !s.canSurvive(level, p);
		}, pos -> level.setBlock(BlockPos.of(pos), air, LeafGuard.quiet(flags)));
	}

	/**
	 * {@link #settle}'s rule (Pure): takes ({@code take}) every candidate that {@code falls} (needs a support it no longer
	 * has), in rounds until a round takes none, so a stalk or a vine curtain goes whole whatever order the candidates come
	 * in (each block only falls once the one holding it is gone). At most {@link #RUN} + 16 rounds. Returns how many.
	 */
	public static int settle(List<Long> cand, LongPredicate falls, java.util.function.LongConsumer take) {
		int n = 0;
		for (int round = 0; round < RUN + 16; round++) {
			int before = n;
			for (long c : cand) {
				if (falls.test(c)) {
					take.accept(c);
					n++;
				}
			}
			if (n == before) {
				break;
			}
		}
		return n;
	}

	/**
	 * Puts recorded plants back after a placement was taken down before its commit (the box already restored): each cell
	 * that holds air or what the placement left gets its before, quietly. Returns how many were written.
	 */
	public static int putBack(ServerLevel level, List<Journal.Cell> cells, int flags) {
		int n = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (Journal.Cell c : cells) {
			p.set(Journal.x(c.pos()), Journal.y(c.pos()), Journal.z(c.pos()));
			BlockState now = level.getBlockState(p);
			BlockState want = WorldJournal.state(level, c.before());
			if (now == want || !(now.isAir() || c.after() != null && now == WorldJournal.state(level, c.after()))) {
				continue;
			}
			level.setBlock(p, want, LeafGuard.quiet(flags));
			n++;
		}
		return n;
	}
}
