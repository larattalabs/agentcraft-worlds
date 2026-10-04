package dev.agentcraft.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Trophies (docs/BUILDINGS.md "Trophies"): vanilla plaques a repo's building gets for merged PRs and finished goals. A
 * trophy is a <b>waxed dark-oak wall sign</b> hung at one of the building's trophy slots ({@link TrophySlots}, from its
 * pin), so it survives the mod being removed and nobody can edit it. Placed with {@link Block#UPDATE_CLIENTS} only (no
 * neighbour updates), only into air or our own earlier sign, only with a sturdy block behind it and only inside the
 * building's box; the box's snapshot covers the slot cells, so Remove and Move put the site back exactly. The ledger
 * ({@link TrophyLedger}, {@code <world>/agentcraft-trophies.json}) makes awards idempotent and remembers what hangs
 * where. Loaded when a world starts, cleared when it stops. Server thread unless noted.
 */
public final class Trophies {
	/** The trophy block: vanilla, matches the walnut trophy walls. */
	static final Block SIGN = Blocks.DARK_OAK_WALL_SIGN;
	/** Sync to clients, nothing else: no neighbour updates, no shape updates around it. */
	static final int FLAGS = Block.UPDATE_CLIENTS;

	private static volatile @Nullable TrophyLedger ledger;
	private static volatile @Nullable Path file;
	/** The ledger file exists but could not be read: nothing is awarded (it would be overwritten). */
	private static volatile boolean loadFailed;

	private Trophies() {
	}

	/** Register after {@link Buildings#init()}: the ledger is pruned against the buildings the world start settled. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(Trophies::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ledger = null;
			file = null;
			loadFailed = false;
		});
	}

	/** What {@link #award} did. */
	public enum Outcome {
		/** Hung (a new sign, or an old trophy's sign rewritten). */
		PLACED,
		/** The key was awarded before: nothing done. */
		KNOWN,
		/** The repo has no building in this world (not recorded: a later building catches up). */
		NO_BUILDING,
		/** The building has no trophy slots for the repo's wing (not recorded). */
		NO_SLOTS,
		/** Every slot was blocked (not air, not our sign, or no sturdy support): not recorded. */
		NO_ROOM,
		/** No world, the building's dimension is not loaded, or the ledger could not be read (not recorded). */
		UNAVAILABLE
	}

	/**
	 * @param building the building it went to (or would have)
	 * @param slot the slot it hangs in (raw anchor name)
	 * @param replaced the key of the trophy it replaced (all slots were full), else null
	 */
	public record Result(Outcome outcome, @Nullable String building, @Nullable String slot, @Nullable String replaced, String message) {
		public boolean placed() {
			return outcome == Outcome.PLACED;
		}
	}

	/** {@link #award(MinecraftServer, Trophy, String)} for the level's server. */
	public static Result award(ServerLevel level, Trophy t, String key) {
		return award(level.getServer(), t, key);
	}

	/**
	 * Hangs {@code t} in the building of {@code t.repo()} (in that building's own dimension), in its wing's first free
	 * slot, else over its oldest trophy; a blocked slot is skipped. A known {@code key} does nothing. The key is only
	 * recorded when a sign was hung, so a repo that gets a building (or slots) later can still catch up.
	 */
	public static Result award(MinecraftServer server, Trophy t, String key) {
		TrophyLedger l = ledger;
		if (l == null || loadFailed) {
			return new Result(Outcome.UNAVAILABLE, null, null, null, loadFailed ? TrophyLedger.FILE + " could not be read (see the log)" : "no world");
		}
		if (l.awarded(key)) {
			return new Result(Outcome.KNOWN, null, null, null, key + " was awarded before");
		}
		Building b = Buildings.forRepo(t.repo());
		if (b == null) {
			return new Result(Outcome.NO_BUILDING, null, null, null, t.repo() + " has no building");
		}
		List<TrophySlots.Slot> slots = slots(b, t.repo());
		if (slots.isEmpty()) {
			return new Result(Outcome.NO_SLOTS, b.id(), null, null, b.id() + " (" + b.blueprint() + ") has no trophy slots for " + t.repo()
				+ (b.pin() == null ? " (placed before AgentCraft remembered blueprint versions)" : ""));
		}
		ServerLevel level = Buildings.levelOf(server, b);
		if (level == null) {
			return new Result(Outcome.UNAVAILABLE, b.id(), null, null, b.dimensionOrDefault() + " is not loaded");
		}
		List<String> lines = TrophyText.lines(t);
		Map<String, TrophyLedger.Entry> taken = l.slots(b.id());
		for (TrophySlots.Slot s : TrophySlots.order(slots, taken)) {
			TrophyLedger.Entry was = taken.get(s.name());
			if (!hang(level, s, lines, was != null)) {
				continue;
			}
			l.put(b.id(), s.name(), new TrophyLedger.Entry(key, lines, TrophySlots.nextAt(System.currentTimeMillis(), taken)));
			l.markAwarded(key);
			save();
			AgentCraft.LOGGER.info("Trophy {} hung in {} slot {} at {},{},{}{}", key, b.id(), s.name(), s.x(), s.y(), s.z(),
				was == null ? "" : " (replaced " + was.key() + ")");
			return new Result(Outcome.PLACED, b.id(), s.name(), was == null ? null : was.key(), "hung in " + b.id() + " " + s.name());
		}
		return new Result(Outcome.NO_ROOM, b.id(), null, null, "every trophy slot of " + b.id() + " for " + t.repo()
			+ " is blocked (not air, or nothing sturdy behind it)");
	}

	/** Whether {@code key} was awarded in this world (false without a world). */
	public static boolean known(String key) {
		TrophyLedger l = ledger;
		return l != null && l.awarded(key);
	}

	/** {@code repo}'s trophy slots in its building (fill order), empty without a building, pin or slots. */
	public static List<TrophySlots.Slot> slotsFor(String repo) {
		Building b = Buildings.forRepo(repo);
		return b == null ? List.of() : slots(b, repo);
	}

	private static List<TrophySlots.Slot> slots(Building b, String repo) {
		Building.Pin pin = b.pin();
		if (pin == null) {
			return List.of();
		}
		int wing = TrophySlots.wingFor(b.repos(), pin.group(), repo);
		return wing < 1 ? List.of() : TrophySlots.forWing(pin.wingAnchors(), wing, b.box());
	}

	/** The sign cells of every trophy slot of a building (its pin's), as {@link TrophySlots#cell} keys. Any thread. */
	static Set<Long> cells(Building b) {
		return b.pin() == null ? Set.of() : TrophySlots.cells(b.pin().wingAnchors());
	}

	/**
	 * Hangs a waxed sign with {@code lines} at {@code s}: only into air, or over our own sign when {@code ours} (the
	 * ledger says one of ours hangs there), and only with a sturdy face behind. False (nothing changed) otherwise.
	 */
	static boolean hang(ServerLevel level, TrophySlots.Slot s, List<String> lines, boolean ours) {
		BlockPos pos = new BlockPos(s.x(), s.y(), s.z());
		Direction facing = Direction.from2DDataValue(s.facing());
		BlockPos back = pos.offset(s.backX(), 0, s.backZ());
		BlockState here = level.getBlockState(pos);
		if (!here.isAir() && !(ours && here.is(SIGN))) {
			return false;
		}
		if (!level.getBlockState(back).isFaceSturdy(level, back, facing)) {
			return false;
		}
		BlockState sign = SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing);
		if (here != sign && !level.setBlock(pos, sign, FLAGS)) {
			return false;
		}
		if (!(level.getBlockEntity(pos) instanceof SignBlockEntity be)) {
			level.setBlock(pos, here, FLAGS); // cannot happen for a vanilla sign; never leave a blank one
			return false;
		}
		List<Component> msgs = new ArrayList<>(TrophyText.LINES);
		for (int i = 0; i < TrophyText.LINES; i++) {
			msgs.add(Component.literal(i < lines.size() ? lines.get(i) : ""));
		}
		be.setText(new SignText(msgs, msgs, DyeColor.BLACK, false), SignTextSlot.FRONT);
		be.setText(SignText.EMPTY, SignTextSlot.BACK);
		be.setWaxed(true);
		be.setChanged();
		level.sendBlockUpdated(pos, sign, sign, FLAGS);
		return true;
	}

	// ------------------------------------------------------------------ building lifecycle (called by Buildings)

	/**
	 * After a move ({@link Buildings#move}, also its undo): hangs the building's trophies again at its new site, slot by
	 * slot (a slot the new site lacks or blocks drops out of the ledger; its key stays awarded).
	 */
	static void rehang(MinecraftServer server, Building moved) {
		TrophyLedger l = ledger;
		if (l == null || loadFailed) {
			return;
		}
		Map<String, TrophyLedger.Entry> entries = Map.copyOf(l.slots(moved.id()));
		if (entries.isEmpty()) {
			return;
		}
		ServerLevel level = Buildings.levelOf(server, moved);
		Map<String, TrophySlots.Slot> byName = new java.util.HashMap<>();
		if (moved.pin() != null) {
			for (TrophySlots.Slot s : TrophySlots.all(moved.pin().wingAnchors())) {
				if (s.inside(moved.box())) {
					byName.put(s.name(), s);
				}
			}
		}
		int hung = 0;
		for (var e : entries.entrySet()) {
			TrophySlots.Slot s = byName.get(e.getKey());
			if (level != null && s != null && hang(level, s, e.getValue().lines(), false)) {
				hung++;
			} else {
				l.clear(moved.id(), e.getKey());
			}
		}
		save();
		AgentCraft.LOGGER.info("Moved {}: re-hung {} of {} trophies", moved.id(), hung, entries.size());
	}

	/**
	 * A building's record was dropped ({@link Buildings#forget}): its slots leave the ledger, its keys stay awarded. A
	 * removed building keeps them until the next world start ({@link #load} prunes): a removal the disk never saw comes
	 * back with its record, and then its signs must still read as ours.
	 */
	static void forgetBuilding(String id) {
		TrophyLedger l = ledger;
		if (l != null && !loadFailed && l.dropBuilding(id)) {
			save();
		}
	}

	// ------------------------------------------------------------------ DevBridge / persistence

	/** A trophy sign hanging in a building's slot (the ledger's entry), for the village board (docs/VILLAGE.md V2). */
	public record Hung(String building, String slot, String key, List<String> lines, long at) {
		public Hung {
			lines = List.copyOf(lines);
		}
	}

	/**
	 * Every trophy hanging now (the ledger's slots of the world's buildings), an immutable copy. Server thread: the ledger
	 * is only changed there, so the client asks for it through the integrated server.
	 */
	public static List<Hung> hung() {
		TrophyLedger l = ledger;
		if (l == null) {
			return List.of();
		}
		List<Hung> out = new java.util.ArrayList<>();
		for (String b : List.copyOf(l.buildingIds())) {
			l.slots(b).forEach((slot, e) -> out.add(new Hung(b, slot, e.key(), e.lines(), e.at())));
		}
		return List.copyOf(out);
	}

	/**
	 * The ledger as JSON for the DevBridge ({@code dev.trophies.list}): {@code awarded} keys and per building the hung
	 * trophies with their slot cell. Server thread.
	 */
	public static JsonObject list(MinecraftServer server) {
		JsonObject o = new JsonObject();
		TrophyLedger l = ledger;
		o.addProperty("loaded", l != null);
		o.addProperty("loadFailed", loadFailed);
		if (l == null) {
			return o;
		}
		JsonArray keys = new JsonArray();
		l.awardedKeys().forEach(keys::add);
		o.add("awarded", keys);
		JsonArray bs = new JsonArray();
		for (Building b : Buildings.buildings()) {
			JsonObject bo = new JsonObject();
			bo.addProperty("id", b.id());
			bo.addProperty("blueprint", b.blueprint());
			JsonArray rs = new JsonArray();
			b.repos().forEach(rs::add);
			bo.add("repos", rs);
			Map<String, TrophyLedger.Entry> taken = l.slots(b.id());
			JsonArray slots = new JsonArray();
			for (TrophySlots.Slot s : b.pin() == null ? List.<TrophySlots.Slot>of() : TrophySlots.all(b.pin().wingAnchors())) {
				JsonObject so = new JsonObject();
				so.addProperty("slot", s.name());
				so.addProperty("wing", s.wing());
				so.addProperty("k", s.k());
				so.addProperty("x", s.x());
				so.addProperty("y", s.y());
				so.addProperty("z", s.z());
				TrophyLedger.Entry e = taken.get(s.name());
				if (e != null) {
					so.addProperty("key", e.key());
					so.addProperty("at", e.at());
					JsonArray ls = new JsonArray();
					e.lines().forEach(ls::add);
					so.add("lines", ls);
				}
				slots.add(so);
			}
			bo.add("slots", slots);
			bs.add(bo);
		}
		o.add("buildings", bs);
		return o;
	}

	private static void load(MinecraftServer server) {
		Path f = server.getWorldPath(LevelResource.ROOT).resolve(TrophyLedger.FILE);
		file = f;
		loadFailed = false;
		try {
			TrophyLedger l = TrophyLedger.read(f);
			// slots of buildings that are gone (removed and settled at this start, or forgotten) leave the ledger
			Set<String> live = new java.util.HashSet<>();
			Buildings.buildings().forEach(b -> live.add(b.id()));
			boolean pruned = false;
			for (String id : List.copyOf(l.buildingIds())) {
				if (!live.contains(id)) {
					pruned |= l.dropBuilding(id);
				}
			}
			ledger = l;
			if (pruned) {
				save();
			}
			if (!l.awardedKeys().isEmpty()) {
				AgentCraft.LOGGER.info("Loaded {} trophy key(s), {} building(s) with trophies", l.awardedKeys().size(), l.buildingIds().size());
			}
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not read {}; no trophies are awarded in this session (the file is left as is)", f, e);
			ledger = new TrophyLedger();
			loadFailed = true;
		}
	}

	private static void save() {
		TrophyLedger l = ledger;
		Path f = file;
		if (l == null || f == null || loadFailed) {
			return;
		}
		try {
			l.save(f);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", f, e);
		}
	}
}
