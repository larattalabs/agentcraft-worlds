package dev.agentcraft.client.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.building.PlaceTiming;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.journal.WorldJournal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * QA of exact restores (docs/BUILDINGS.md "Leaf ring"): a region of the overworld captured on the integrated server, block
 * states plus block entity data, and compared later cell by cell, or hashed.
 * <ul>
 * <li>{@code dev.region.capture {name, x0, y0, z0, x1, y1, z1}} keeps the region in memory under {@code name};</li>
 * <li>{@code dev.region.diff {name, limit?}} reads the same region again and lists the cells that differ;</li>
 * <li>{@code dev.region.hash {x0, y0, z0, x1, y1, z1}} a SHA-256 over every cell's state and block entity data;</li>
 * <li>{@code dev.buildings.timing {reset?}} what the last placement cost the server thread ({@link PlaceTiming}).</li>
 * </ul>
 */
public final class RegionDev {
	/** At most this many cells per region (16 M strings in memory are fine for QA; more is a mistake). */
	static final long MAX_CELLS = 4_000_000;

	private record Region(int x0, int y0, int z0, int x1, int y1, int z1, String[] cells) {
	}

	private static final Map<String, Region> REGIONS = new ConcurrentHashMap<>();

	private RegionDev() {
	}

	public static void init() {
		DevBridge.register("dev.region.capture", 120_000, "{name, x0, y0, z0, x1, y1, z1} - QA: keep the overworld region's block states and block "
			+ "entity data in memory under name (at most 4M cells) -> {name, box, cells, blockEntities, leaves, hash}", (req, mc) -> {
				Fields f = Fields.of(req);
				String name = f.nonBlank("name");
				int[] b = box(f);
				return ServerTasks.callOnServer(server -> {
					Region r = read(server.overworld(), b);
					REGIONS.put(name, r);
					JsonObject o = summary(r);
					o.addProperty("name", name);
					return o;
				});
			});
		DevBridge.register("dev.region.diff", 120_000, "{name, limit?: 200} - QA: read a captured region again -> {box, differ, diffs: [{pos, was, now}] "
			+ "(the first limit), hash}", (req, mc) -> {
				Fields f = Fields.of(req);
				String name = f.nonBlank("name");
				int limit = f.optInt("limit", 200, 0, 1_000_000);
				Region was = REGIONS.get(name);
				if (was == null) {
					throw new DevBridge.DevException("no captured region " + name);
				}
				return ServerTasks.callOnServer(server -> {
					Region now = read(server.overworld(), new int[] {was.x0, was.y0, was.z0, was.x1, was.y1, was.z1});
					JsonObject o = summary(now);
					JsonArray diffs = new JsonArray();
					int differ = 0;
					int sx = was.x1 - was.x0 + 1;
					int sz = was.z1 - was.z0 + 1;
					for (int i = 0; i < was.cells.length; i++) {
						if (was.cells[i].equals(now.cells[i])) {
							continue;
						}
						differ++;
						if (diffs.size() < limit) {
							int x = was.x0 + i % sx;
							int z = was.z0 + (i / sx) % sz;
							int y = was.y0 + i / (sx * sz);
							JsonObject d = new JsonObject();
							d.addProperty("pos", x + "," + y + "," + z);
							d.addProperty("was", was.cells[i]);
							d.addProperty("now", now.cells[i]);
							diffs.add(d);
						}
					}
					o.addProperty("differ", differ);
					o.add("diffs", diffs);
					return o;
				});
			});
		DevBridge.register("dev.region.hash", 120_000, "{x0, y0, z0, x1, y1, z1} - QA: SHA-256 over the overworld region's block states and block "
			+ "entity data -> {box, cells, blockEntities, leaves, hash}", (req, mc) -> {
				int[] b = box(Fields.of(req));
				return ServerTasks.callOnServer(server -> summary(read(server.overworld(), b)));
			});
		DevBridge.register("dev.buildings.timing", 10_000, "{reset?: false} - QA: the last placement's (or move's) time in Buildings.place, the "
			+ "leaf ring's part of it and cell count, the interval between the server ticks around it, the longest tick interval since the last "
			+ "reset, the leaf ticks restores dropped", (req, mc) -> {
				boolean reset = Fields.of(req).optBool("reset", false);
				return ServerTasks.callOnServer(server -> PlaceTiming.json(reset));
			});
	}

	private static int[] box(Fields f) {
		int x0 = (int) f.integer("x0", -30_000_000, 30_000_000);
		int y0 = (int) f.integer("y0", -2048, 2048);
		int z0 = (int) f.integer("z0", -30_000_000, 30_000_000);
		int x1 = (int) f.integer("x1", -30_000_000, 30_000_000);
		int y1 = (int) f.integer("y1", -2048, 2048);
		int z1 = (int) f.integer("z1", -30_000_000, 30_000_000);
		int[] b = {Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1), Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1)};
		long n = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
		if (n > MAX_CELLS) {
			throw new DevBridge.DevException("box too big: " + n + " cells (at most " + MAX_CELLS + ")");
		}
		return b;
	}

	/** The region's cells, x fastest, then z, then y: the state, and " " + block entity data where there is a block entity. */
	private static Region read(ServerLevel level, int[] b) {
		int sx = b[3] - b[0] + 1;
		int sz = b[5] - b[2] + 1;
		int sy = b[4] - b[1] + 1;
		String[] cells = new String[sx * sy * sz];
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int i = 0;
		for (int y = b[1]; y <= b[4]; y++) {
			for (int z = b[2]; z <= b[5]; z++) {
				for (int x = b[0]; x <= b[3]; x++) {
					BlockState s = level.getBlockState(m.set(x, y, z));
					String c = s.toString();
					if (s.hasBlockEntity()) {
						var v = WorldJournal.valueAt(level, m);
						c = c + " " + (v.nbt() == null ? "{}" : v.nbt().toString());
					}
					cells[i++] = c;
				}
			}
		}
		return new Region(b[0], b[1], b[2], b[3], b[4], b[5], cells);
	}

	private static JsonObject summary(Region r) {
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		int bes = 0;
		int leaves = 0;
		for (String c : r.cells) {
			md.update(c.getBytes(StandardCharsets.UTF_8));
			md.update((byte) '\n');
			if (c.indexOf(' ') > 0) {
				bes++;
			}
			if (c.contains("_leaves")) {
				leaves++;
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("box", r.x0 + "," + r.y0 + "," + r.z0 + " .. " + r.x1 + "," + r.y1 + "," + r.z1);
		o.addProperty("cells", r.cells.length);
		o.addProperty("blockEntities", bes);
		o.addProperty("leaves", leaves);
		o.addProperty("hash", HexFormat.of().formatHex(md.digest()));
		return o;
	}
}
