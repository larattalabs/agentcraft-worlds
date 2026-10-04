package dev.agentcraft.client.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.BlueprintTransform;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.BuildingCommands;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.GhostModel;
import dev.agentcraft.building.Occupancy;
import dev.agentcraft.building.Approach;
import dev.agentcraft.building.TerrainFit;
import dev.agentcraft.client.agents.ClientAgentEntity;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Placement mode of the building wizard (client thread only): the chosen blueprint and repos, where
 * the ghost is (origin = the rotated box's minimum corner, as {@link Buildings#place} takes it), its
 * rotation, the conflicts under it and what {@link Buildings#place} would refuse.
 *
 * <p>Position: the ghost's entrance faces the player and its entrance approach ({@code Approach}) ends on the
 * block the player looks at (ray up to {@value #REACH} blocks), the building beyond it, its ground row on the
 * footprint's median surface; looking at nothing puts it where {@code /agentcraft place} would (ground row at
 * the feet, approach end {@link BuildingCommands#GAP} blocks ahead). Turned away (R), the near edge is there. Rotate adds quarter turns to the automatic rotation;
 * nudges are world offsets on top. Lock freezes the spot (and the automatic rotation) so the player
 * can walk around the ghost; a DevBridge start with an explicit origin is locked there.
 */
public final class BuildPlacement {
	/** The placement (or plot-marking) HUD panel drawn last frame (x, y, w, h) or null: toasts stop above it. */
	public static int @org.jspecify.annotations.Nullable [] hudRect() {
		int[] r = PlacementHud.lastRect;
		return r != null ? r : PlotHud.lastRect;
	}

	static final int REACH = 64;
	/** How far below the looked-at spot the ground is searched for (a ray that hits a wall lands on the floor below). */
	private static final int GROUND_SEARCH = 24;
	/** Conflict cells kept for drawing (the counts are always exact). */
	static final int MAX_DRAWN_CONFLICTS = 6000;
	/** Rescan the world under an unmoved ghost this often (blocks change). */
	private static final int RESCAN_TICKS = 10;

	/** Refusal for a player in (or next to) the box: the server's words ({@link Occupancy}). */
	static final String PLAYER_INSIDE = Buildings.PLAYER_IN_BOX;
	/** How far (blocks) a footprint column's surface may differ from the looked-at spot to count for the median height. */
	private static final int SURFACE_REACH = 12;

	/**
	 * An immutable view of what the renderer and HUD draw this frame. {@code obstructed} / {@code blocked} /
	 * {@code water} / {@code lava} / {@code fill} / {@code clear} hold world cells as (x, y, z, exposed-face mask)
	 * quadruples, so a buried blob draws only its outline.
	 *
	 * @param fill the foundation the server will add below the floor ({@link TerrainFit}), {@code clear} the natural
	 *             terrain it will clear above the ground row
	 * @param notes what placing does beside the building (hostile mobs removed, water in the footprint)
	 * @param snapMinY the bottom of the box the placement touches (foundation included)
	 * @param approach the entrance approach ({@link Approach}; its fill and clear cells are drawn with {@code fill} /
	 *                 {@code clear}, its counts are its own), {@code path} its path and slab cells as quadruples
	 * @param snapBox the whole box place() snapshots and checks (foundation and approach included)
	 */
	record View(Blueprint bp, GhostModel model, int ox, int oy, int oz, int turns, String front, int[] obstructed, int obstructedCount,
		int[] blocked, int blockedCount, List<String> refusals, boolean playerInside, boolean locked, boolean pending, boolean forceArmed,
		int[] water, int waterCount, int[] lava, int lavaCount, int[] fill, int fillCount, int[] clear, int clearCount, List<String> notes,
		int snapMinY, Approach.Plan approach, int[] path, Anchors.Bounds snapBox) {
		Anchors.Bounds box() {
			return new Anchors.Bounds(ox, oy, oz, ox + model.sizeX - 1, oy + model.sizeY - 1, oz + model.sizeZ - 1);
		}

		View with(boolean locked, boolean pending, boolean forceArmed) {
			return new View(bp, model, ox, oy, oz, turns, front, obstructed, obstructedCount, blocked, blockedCount, refusals, playerInside, locked,
				pending, forceArmed, water, waterCount, lava, lavaCount, fill, fillCount, clear, clearCount, notes, snapMinY, approach, path, snapBox);
		}
	}

	/** The outcome of a confirm. */
	public record Result(boolean placed, @Nullable String buildingId, String message) {
	}

	private static boolean active;
	private static @Nullable Blueprint bp;
	private static Blueprints.@Nullable Entry entry;
	private static List<String> repos = List.of();
	/** Move mode: the building being moved (its blueprint and repos), null = a new building. */
	private static @Nullable String moving;
	private static GhostModel.@Nullable Cells cells;
	private static final GhostModel[] MODELS = new GhostModel[4];
	private static @Nullable Level level;

	private static int userTurns;
	private static int nudgeX;
	private static int nudgeY;
	private static int nudgeZ;
	private static boolean locked;
	/** Locked spot: x, surface y, z, gap, facing index (into BlueprintTransform.DIRECTIONS). */
	private static int @Nullable [] lockedSpot;
	/** DevBridge: an explicit origin (rotated box minimum) and base turns. */
	private static int @Nullable [] explicitOrigin;
	private static int explicitTurns;

	private static @Nullable View view;
	/** The spot of the last scan: x, y, z, turns (compared exactly). */
	private static int @Nullable [] scanned;
	private static int ticksSinceScan;
	private static boolean pending;
	private static boolean forceArmed;
	private static @Nullable String status;
	private static boolean statusError;
	private static @Nullable Result lastResult;
	private static @Nullable CompletableFuture<Result> inFlight;

	private BuildPlacement() {
	}

	// ------------------------------------------------------------------ lifecycle

	public static boolean active() {
		return active;
	}

	/** Enters placement mode (closes any screen). Throws IllegalArgumentException with a player-facing message. */
	public static void start(String blueprintId, List<String> repoIds) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			throw new IllegalArgumentException("Not in a world");
		}
		if (mc.getSingleplayerServer() == null) {
			throw new IllegalArgumentException("The building wizard places through the integrated server: singleplayer only");
		}
		Blueprint b = Blueprints.get(blueprintId);
		GhostModel.Cells c = TemplateCells.of(blueprintId);
		if (b == null || c == null) {
			throw new IllegalArgumentException("Unknown blueprint '" + blueprintId + "' (known: " + Blueprints.ids() + ")");
		}
		cancelQuietly();
		PlotMarker.cancelQuietly();
		active = true;
		moving = null;
		bp = b;
		entry = Blueprints.entry(blueprintId);
		repos = List.copyOf(repoIds);
		cells = c;
		level = mc.level;
		mc.gui.setScreen(null);
		setStatus(null, false);
		update(mc, true);
		AgentCraft.LOGGER.info("Building wizard: placing {} for {} ({} cells, {} visible)", b.id(), repos, c.count(), model(0).visibleCount());
	}

	/**
	 * Enters placement mode to move building {@code id} (docs/BUILDINGS.md "Move a building"): its blueprint and
	 * repos, confirm runs {@link Buildings#move}. Throws IllegalArgumentException with a player-facing message.
	 */
	public static void startMove(String id) {
		Building b = Buildings.get(id);
		if (b == null) {
			throw new IllegalArgumentException("No building " + id);
		}
		start(b.blueprint(), b.repos());
		moving = id;
		update(Minecraft.getInstance(), true);
	}

	/** The building being moved, or null when placing a new one. */
	static @Nullable String moving() {
		return active ? moving : null;
	}

	/** Locks the ghost at an explicit origin and base rotation (plots, DevBridge). */
	static void lockAt(int x, int y, int z, int turns) {
		lockAt(x, y, z, turns, false);
	}

	/**
	 * Locks the ghost at an explicit origin and base rotation (DevBridge, reproducible shots). {@code ground}: the y is
	 * replaced by the footprint's median surface as the wizard puts it (the surface at the footprint's centre as the
	 * reference), for QA on natural terrain.
	 */
	static void lockAt(int x, int y, int z, int turns, boolean ground) {
		Minecraft mc = Minecraft.getInstance();
		if (ground && mc.level != null && bp != null) {
			GhostModel m = model(Math.floorMod(turns, 4));
			int ref = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + m.sizeX / 2, z + m.sizeZ / 2);
			y = footprintSurface(mc.level, m, x, z, ref) - bp.groundY();
		}
		explicitOrigin = new int[] {x, y, z};
		explicitTurns = Math.floorMod(turns, 4);
		userTurns = 0;
		nudgeX = nudgeY = nudgeZ = 0;
		locked = true;
		update(Minecraft.getInstance(), true);
	}

	public static void cancel() {
		if (active) {
			cancelQuietly();
			setStatus("Placement cancelled", false);
		}
	}

	private static void cancelQuietly() {
		active = false;
		moving = null;
		bp = null;
		entry = null;
		cells = null;
		level = null;
		java.util.Arrays.fill(MODELS, null);
		userTurns = nudgeX = nudgeY = nudgeZ = 0;
		locked = false;
		lockedSpot = null;
		explicitOrigin = null;
		view = null;
		scanned = null;
		pending = false;
		forceArmed = false;
	}

	// ------------------------------------------------------------------ input (keys and DevBridge share these)

	public static void rotate(int quarterTurns) {
		if (!active) {
			return;
		}
		userTurns = Math.floorMod(userTurns + quarterTurns, 4);
		forceArmed = false;
		update(Minecraft.getInstance(), true);
	}

	/** Moves the ghost relative to where the player faces (forward / right) and up. */
	public static void nudge(int forward, int right, int up) {
		Minecraft mc = Minecraft.getInstance();
		if (!active || mc.player == null) {
			return;
		}
		int[] d = GhostModel.relativeToWorld(horizontalFacing(mc.player).getName(), forward, right);
		nudgeX += d[0];
		nudgeZ += d[1];
		nudgeY += up;
		forceArmed = false;
		update(mc, true);
	}

	public static void setLocked(boolean on) {
		Minecraft mc = Minecraft.getInstance();
		if (!active || on == locked) {
			return;
		}
		if (on) {
			lockedSpot = mc.player == null ? null : spot(mc, mc.player);
		} else {
			// unlocking means "follow my look" again: the nudges belonged to the locked spot
			lockedSpot = null;
			explicitOrigin = null;
			nudgeX = nudgeY = nudgeZ = 0;
		}
		locked = on;
		update(mc, true);
	}

	public static boolean locked() {
		return locked;
	}

	/**
	 * Places the building through {@link Buildings#place} on the integrated server. {@code force}
	 * only counts when armed by a refusal over block entities (a second, deliberate confirm).
	 */
	public static CompletableFuture<Result> confirm(boolean force) {
		Minecraft mc = Minecraft.getInstance();
		View v = view;
		if (!active || v == null) {
			return CompletableFuture.completedFuture(new Result(false, null, "Not placing anything"));
		}
		if (pending && inFlight != null) {
			return inFlight;
		}
		if (v.playerInside()) {
			Result r = new Result(false, null, "Not placed: " + PLAYER_INSIDE);
			lastResult = r;
			setStatus(r.message(), true);
			return CompletableFuture.completedFuture(r);
		}
		boolean useForce = force && forceArmed;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return CompletableFuture.completedFuture(new Result(false, null, "Singleplayer only"));
		}
		// capture everything on the client thread; the server task only sees immutable values
		ResourceKey<Level> dim = mc.player.level().dimension();
		String bpId = v.bp().id();
		BlockPos origin = new BlockPos(v.ox(), v.oy(), v.oz());
		Rotation rotation = Rotation.values()[v.turns()];
		List<String> rs = List.copyOf(repos);
		String moveId = moving;
		CompletableFuture<Result> f = new CompletableFuture<>();
		pending = true;
		inFlight = f;
		setStatus((moveId != null ? "Moving " + moveId : "Placing " + v.bp().name()) + "…", false);
		server.execute(() -> {
			Result r;
			try {
				ServerLevel sl = server.getLevel(dim);
				Blueprint b = Blueprints.get(bpId);
				if (sl == null || b == null) {
					throw new Buildings.BuildingException(sl == null ? "That dimension is not loaded" : "Blueprint " + bpId + " is gone (reloaded?)");
				}
				if (moveId != null) {
					Building moved = Buildings.move(sl, moveId, origin, rotation, useForce);
					String note = Buildings.lastNote();
					r = new Result(true, moved.id(), "Moved " + moved.id() + " (" + b.name() + "); its old site is as it was before"
						+ (note == null ? "" : " (" + note + ")") + ". Undo: the hub's Undo move");
				} else {
					Building placed = Buildings.place(sl, b, origin, rotation, rs, useForce);
					String note = Buildings.lastNote();
					r = new Result(true, placed.id(), "Placed " + placed.id() + " (" + b.name() + ") for " + String.join(", ", placed.repos())
						+ (placed.home() ? ", home" : "") + (note == null ? "" : " (" + note + ")") + ". Undo: hub (" + dev.agentcraft.client.hud.Keys.label(dev.agentcraft.client.hud.Keys.hub)
						+ ") > Buildings > " + placed.id() + " > Remove");
				}
			} catch (Buildings.BuildingException e) {
				r = new Result(false, null, e.getMessage());
			} catch (RuntimeException e) {
				AgentCraft.LOGGER.error("Building wizard: placing {} failed", bpId, e);
				r = new Result(false, null, "Placing " + bpId + " failed: " + e);
			}
			Result result = r;
			mc.execute(() -> {
				onResult(result, v);
				f.complete(result);
			});
		});
		return f;
	}

	private static void onResult(Result r, View refused) {
		pending = false;
		inFlight = null;
		lastResult = r;
		if (r.placed()) {
			cancelQuietly();
			setStatus(r.message(), false);
			Toasts.push(new Notify(NotifyLevel.INFO, r.message(), null, System.currentTimeMillis()));
			return;
		}
		// refused: stay in placement mode; over block entities, a second confirm with Shift may force it
		boolean beRefusal = refused.blockedCount() > 0 || r.message().contains("block entit") || r.message().contains("first (moving it");
		boolean arm = active && beRefusal && !r.message().contains("overlaps") && !r.message().contains("already has");
		if (arm) {
			// pin the ghost to the refused box, so the force confirm means exactly the box the refusal described
			explicitOrigin = new int[] {refused.ox(), refused.oy(), refused.oz()};
			explicitTurns = refused.turns();
			userTurns = 0;
			nudgeX = nudgeY = nudgeZ = 0;
			locked = true;
			update(Minecraft.getInstance(), true);
		}
		forceArmed = arm;
		String msg = r.message() + (forceArmed ? r.message().contains("first (moving it") ? " - Shift+Enter moves anyway (they are lost)"
			: " - Shift+Enter places anyway (they come back on remove)" : "");
		setStatus(msg, true);
		Toasts.push(new Notify(NotifyLevel.WARN, "Not placed: " + msg, null, System.currentTimeMillis()));
	}

	// ------------------------------------------------------------------ per tick

	/** Called every client tick. */
	static void tick(Minecraft mc) {
		if (!active) {
			return;
		}
		if (mc.player == null || mc.level == null || mc.level != level) {
			cancelQuietly();
			setStatus("Placement cancelled (left the world)", false);
			return;
		}
		Blueprints.Entry e = bp == null ? null : Blueprints.entry(bp.id());
		if (e == null) {
			cancelQuietly();
			setStatus("Placement cancelled: the blueprint is no longer loaded", true);
			return;
		}
		if (e != entry) {
			// /agentcraft blueprints reload: pick up the new template
			entry = e;
			bp = e.blueprint();
			cells = TemplateCells.of(bp.id());
			java.util.Arrays.fill(MODELS, null);
		}
		update(mc, false);
	}

	private static GhostModel model(int turns) {
		GhostModel m = MODELS[turns];
		if (m == null) {
			m = GhostModel.of(cells, turns);
			MODELS[turns] = m;
		}
		return m;
	}

	private static Direction horizontalFacing(Player p) {
		Direction d = p.getDirection();
		return d.getAxis().isHorizontal() ? d : Direction.SOUTH;
	}

	/**
	 * Where the player points: {x, surfaceY, z, gap, facingIndex}. The looked-at block's open
	 * neighbour, dropped to the ground below it; nothing in reach = the feet, {@code GAP} ahead.
	 */
	static int[] spot(Minecraft mc, Player p) {
		Direction facing = horizontalFacing(p);
		int fi = BlueprintTransform.directionIndex(facing.getName());
		HitResult hr = p.pick(REACH, 1f, true); // fluids too: aiming at a lake lands on its surface, not its bed
		if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
			BlockPos open = bh.getBlockPos().relative(bh.getDirection());
			Level lv = p.level();
			int y = open.getY();
			int floor = Math.max(lv.getMinY(), y - GROUND_SEARCH);
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(open.getX(), y - 1, open.getZ());
			while (m.getY() >= floor) {
				BlockState s = lv.getBlockState(m);
				if (!s.isAir() && !s.canBeReplaced() || !s.getFluidState().isEmpty()) {
					break; // ground, or a fluid's surface
				}
				m.move(Direction.DOWN);
			}
			return new int[] {open.getX(), m.getY() + 1, open.getZ(), 0, fi};
		}
		BlockPos feet = p.blockPosition();
		return new int[] {feet.getX(), feet.getY(), feet.getZ(), BuildingCommands.GAP, fi};
	}

	private static void update(Minecraft mc, boolean force) {
		if (!active || bp == null || cells == null || mc.player == null || mc.level == null) {
			return;
		}
		int turns;
		int ox;
		int oy;
		int oz;
		if (explicitOrigin != null) {
			turns = Math.floorMod(explicitTurns + userTurns, 4);
			ox = explicitOrigin[0];
			oy = explicitOrigin[1];
			oz = explicitOrigin[2];
		} else {
			int[] s = locked && lockedSpot != null ? lockedSpot : spot(mc, mc.player);
			String facing = BlueprintTransform.DIRECTIONS.get(s[4]);
			// entrance towards the player (the opposite of where they look), plus the player's own turns
			int auto = BlueprintTransform.turnsToFace(bp.front(), BlueprintTransform.rotateDirection(facing, 2));
			turns = Math.floorMod(auto + userTurns, 4);
			int rsx = BlueprintTransform.rotatedSizeX(bp.sizeX(), bp.sizeZ(), turns);
			int rsz = BlueprintTransform.rotatedSizeZ(bp.sizeX(), bp.sizeZ(), turns);
			// entrance towards the player: the entrance approach ends on the looked-at block, the building stands beyond it
			int gap = s[3] + (Math.floorMod(userTurns, 4) == 0 ? bp.approach().length() : 0);
			int[] o = BlueprintTransform.originInFront(s[0], s[1], s[2], facing, rsx, rsz, bp.groundY(), gap);
			ox = o[0];
			oz = o[2];
			// the ground row on the footprint's median surface (C4), not just on the looked-at spot's
			oy = footprintSurface(mc.level, model(turns), ox, oz, s[1]) - bp.groundY();
		}
		ox += nudgeX;
		oy += nudgeY;
		oz += nudgeZ;
		int[] spot = {ox, oy, oz, turns};
		boolean same = java.util.Arrays.equals(spot, scanned);
		ticksSinceScan++;
		if (!force && same && ticksSinceScan < RESCAN_TICKS && view != null) {
			if (view.pending() != pending || view.forceArmed() != forceArmed || view.locked() != locked) {
				view = view.with(locked, pending, forceArmed);
			}
			return;
		}
		if (!same) {
			forceArmed = false;
		}
		scanned = spot;
		ticksSinceScan = 0;
		view = scan(mc.level, mc.player, bp, model(turns), ox, oy, oz, turns);
	}

	/**
	 * The ground height under the footprint ({@link TerrainFit#medianSurface}): per footprint column the motion-blocking
	 * surface (fluids count, leaves do not), ignoring columns more than {@link #SURFACE_REACH} from the looked-at
	 * spot's surface {@code spotY} (a cliff, a cave roof); {@code spotY} when no column qualifies.
	 */
	private static int footprintSurface(ClientLevel lv, GhostModel m, int ox, int oz, int spotY) {
		int[] h = m.columnHeights();
		int[] surfaces = new int[h.length];
		java.util.Arrays.fill(surfaces, Integer.MIN_VALUE);
		for (int z = 0; z < m.sizeZ; z++) {
			for (int x = 0; x < m.sizeX; x++) {
				int i = z * m.sizeX + x;
				if (h[i] == 0) {
					continue;
				}
				int y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox + x, oz + z);
				if (Math.abs(y - spotY) <= SURFACE_REACH) {
					surfaces[i] = y;
				}
			}
		}
		return TerrainFit.medianSurface(surfaces, spotY);
	}

	/** Classifies the world under the ghost and works out place()'s (or move()'s) refusals, in its order. */
	private static View scan(ClientLevel lv, Player player, Blueprint b, GhostModel m, int ox, int oy, int oz, int turns) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int sx = m.sizeX;
		int sy = m.sizeY;
		int sz = m.sizeZ;
		TerrainFit.World world = (x, y, z) -> TerrainFit.flags(lv, p.set(x, y, z));
		TerrainFit.Plan plan = TerrainFit.plan(m, ox, oy, oz, world);
		Anchors.Bounds box = new Anchors.Bounds(ox, oy, oz, ox + sx - 1, oy + sy - 1, oz + sz - 1);
		Approach.Plan approach = Approach.forBlueprint(b, turns, box, world);
		// the box place() snapshots and checks: the template's, the foundation below it, the entrance approach
		Anchors.Bounds snapBox = Buildings.snapshotBox(box, plan, approach);
		int qx = snapBox.maxX() - snapBox.minX() + 1;
		int qy = snapBox.maxY() - snapBox.minY() + 1;
		int qz = snapBox.maxZ() - snapBox.minZ() + 1;
		// snapshot-box-local flags: 1 = obstructed, 2 = foreign block entity
		byte[] flags = new byte[qx * qy * qz];
		int obstructedCount = 0;
		for (int i = 0; i < m.count(); i++) {
			p.set(ox + m.x(i), oy + m.y(i), oz + m.z(i));
			BlockState s = lv.getBlockState(p);
			if (GhostModel.classify(m.y(i), m.groundY, s.isAir(), s.canBeReplaced(), false) == GhostModel.Conflict.OBSTRUCTED) {
				obstructedCount++;
				flags[((oy + m.y(i) - snapBox.minY()) * qz + oz + m.z(i) - snapBox.minZ()) * qx + ox + m.x(i) - snapBox.minX()] |= 1;
			}
		}
		// what the approach clears that is more than terrain or plants (a log, a player's blocks) is drawn as obstructed too
		for (int i = 0; i < approach.clear().length; i += 3) {
			int x = approach.clear()[i];
			int y = approach.clear()[i + 1];
			int z = approach.clear()[i + 2];
			if ((world.flags(x, y, z) & TerrainFit.NATURAL) == 0) {
				obstructedCount++;
				flags[((y - snapBox.minY()) * qz + z - snapBox.minZ()) * qx + x - snapBox.minX()] |= 1;
			}
		}
		// block entities anywhere in the snapshot box block placement (place() checks all of it)
		int blockedCount = 0;
		for (int y = 0; y < qy; y++) {
			for (int z = 0; z < qz; z++) {
				for (int x = 0; x < qx; x++) {
					p.set(snapBox.minX() + x, snapBox.minY() + y, snapBox.minZ() + z);
					BlockState s = lv.getBlockState(p);
					if (s.hasBlockEntity() && isForeign(lv, p, s)) {
						blockedCount++;
						flags[(y * qz + z) * qx + x] |= 2;
					}
				}
			}
		}
		int[] obstructed = shell(flags, 1, qx, qy, qz, snapBox.minX(), snapBox.minY(), snapBox.minZ());
		int[] blocked = shell(flags, 2, qx, qy, qz, snapBox.minX(), snapBox.minY(), snapBox.minZ());
		String moveId = moving;
		List<String> withBuilding = new ArrayList<>();
		for (String r : repos) {
			Building has = Buildings.forRepo(r);
			if (has != null && !has.id().equals(moveId)) {
				withBuilding.add(r + " (" + has.id() + ")");
			}
		}
		String dim = lv.dimension().identifier().toString();
		List<String> overlaps = new ArrayList<>();
		for (Building other : Buildings.all()) {
			if (other.dimensionOrDefault().equals(dim) && Building.intersects(other.restoreBox(), snapBox)) {
				overlaps.add(other.id().equals(moveId) ? other.id() + " where it stands now" : other.id());
			}
		}
		List<String> refusals = new ArrayList<>(GhostModel.refusals(repos, b.wings(), withBuilding, snapBox.minY(), box.maxY(), lv.getMinY(),
			lv.getMaxY(), overlaps, blockedCount, false));
		String lava = TerrainFit.lavaRefusal(plan);
		if (lava == null) {
			lava = Approach.lavaRefusal(approach);
		}
		if (lava != null) {
			refusals.add(lava);
		}
		List<String> doors = Buildings.straddling(lv, snapBox, true);
		if (!doors.isEmpty()) {
			refusals.add("a door is cut in half by the box edge (" + doors.get(0) + ")");
		}
		List<Occupancy.Found> found = Occupancy.scan(lv, snapBox, e -> e instanceof ClientAgentEntity);
		List<String> occupied = Occupancy.refusals(found);
		refusals.addAll(occupied);
		boolean inside = found.stream().anyMatch(f -> f.kind() == Occupancy.Kind.PLAYER);
		List<String> notes = new ArrayList<>();
		String gone = Occupancy.removalNote(found);
		if (gone != null) {
			notes.add("placing " + gone);
		}
		String water = TerrainFit.waterWarning(plan);
		if (water != null) {
			notes.add(water + " (blue)");
		}
		String wet = Approach.waterWarning(approach);
		if (wet != null) {
			notes.add(wet + " (blue)");
		}
		String front = BlueprintTransform.rotateDirection(b.front(), turns);
		return new View(b, m, ox, oy, oz, turns, front, obstructed, obstructedCount, blocked, blockedCount, List.copyOf(refusals), inside, locked,
			pending, forceArmed, shellOf(concat(plan.water(), approach.water())), plan.waterCount() + approach.waterCount(),
			shellOf(concat(plan.lava(), approach.lava())), plan.lavaCount() + approach.lavaCount(), shellOf(concat(plan.fill(), approach.fill())),
			plan.fillCount(), shellOf(concat(plan.clear(), approach.clear())), plan.clearCount(), List.copyOf(notes), snapBox.minY(), approach,
			shellOf(approach.path()), snapBox);
	}

	private static int[] concat(int[] a, int[] b) {
		if (b.length == 0) {
			return a;
		}
		int[] out = java.util.Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/** World cell triples as (x, y, z, exposed-face mask) quadruples: faces towards another listed cell are hidden. */
	static int[] shellOf(int[] cells) {
		java.util.Set<Long> set = new java.util.HashSet<>();
		for (int i = 0; i + 2 < cells.length; i += 3) {
			set.add(BlockPos.asLong(cells[i], cells[i + 1], cells[i + 2]));
		}
		IntList out = new IntList();
		for (int i = 0; i + 2 < cells.length && out.size() < MAX_DRAWN_CONFLICTS * 4; i += 3) {
			int x = cells[i];
			int y = cells[i + 1];
			int z = cells[i + 2];
			int mask = 0;
			mask |= set.contains(BlockPos.asLong(x, y - 1, z)) ? 0 : 1;
			mask |= set.contains(BlockPos.asLong(x, y + 1, z)) ? 0 : 2;
			mask |= set.contains(BlockPos.asLong(x, y, z - 1)) ? 0 : 4;
			mask |= set.contains(BlockPos.asLong(x, y, z + 1)) ? 0 : 8;
			mask |= set.contains(BlockPos.asLong(x - 1, y, z)) ? 0 : 16;
			mask |= set.contains(BlockPos.asLong(x + 1, y, z)) ? 0 : 32;
			if (mask != 0) {
				out.add(x, y, z, mask);
			}
		}
		return out.toArray();
	}

	/**
	 * The cells carrying {@code bit} as world (x, y, z, exposed-face mask) quadruples: faces towards a
	 * cell with the same bit are hidden, cells with none exposed are left out (at most
	 * {@link #MAX_DRAWN_CONFLICTS}).
	 */
	private static int[] shell(byte[] flags, int bit, int sx, int sy, int sz, int ox, int oy, int oz) {
		IntList out = new IntList();
		for (int y = 0; y < sy && out.size() < MAX_DRAWN_CONFLICTS * 4; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					if ((flags[(y * sz + z) * sx + x] & bit) == 0) {
						continue;
					}
					int mask = 0;
					mask |= has(flags, bit, x, y - 1, z, sx, sy, sz) ? 0 : 1;
					mask |= has(flags, bit, x, y + 1, z, sx, sy, sz) ? 0 : 2;
					mask |= has(flags, bit, x, y, z - 1, sx, sy, sz) ? 0 : 4;
					mask |= has(flags, bit, x, y, z + 1, sx, sy, sz) ? 0 : 8;
					mask |= has(flags, bit, x - 1, y, z, sx, sy, sz) ? 0 : 16;
					mask |= has(flags, bit, x + 1, y, z, sx, sy, sz) ? 0 : 32;
					if (mask != 0) {
						out.add(ox + x, oy + y, oz + z, mask);
					}
				}
			}
		}
		return out.toArray();
	}

	private static boolean has(byte[] flags, int bit, int x, int y, int z, int sx, int sy, int sz) {
		return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz && (flags[(y * sz + z) * sx + x] & bit) != 0;
	}

	private static boolean isForeign(ClientLevel lv, BlockPos p, BlockState s) {
		BlockEntity be = lv.getBlockEntity(p);
		if (be != null) {
			return !(be instanceof StationBlockEntity);
		}
		// not created on the client: judge by the block (stations are all AgentCraft blocks)
		return !AgentCraft.MOD_ID.equals(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace());
	}

	// ------------------------------------------------------------------ reads

	static @Nullable View view() {
		return active ? view : null;
	}

	static List<String> repos() {
		return repos;
	}

	static @Nullable String status() {
		return status;
	}

	static boolean statusError() {
		return statusError;
	}

	static long statusAt;

	private static void setStatus(@Nullable String s, boolean error) {
		status = s;
		statusError = error;
		statusAt = net.minecraft.util.Util.getMillis();
	}

	static JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("active", active);
		o.addProperty("status", status);
		o.addProperty("statusError", statusError);
		if (lastResult != null) {
			JsonObject r = new JsonObject();
			r.addProperty("placed", lastResult.placed());
			r.addProperty("buildingId", lastResult.buildingId());
			r.addProperty("message", lastResult.message());
			o.add("lastResult", r);
		} else {
			o.add("lastResult", null);
		}
		View v = view();
		if (v == null) {
			return o;
		}
		o.addProperty("blueprint", v.bp().id());
		o.addProperty("moving", moving);
		JsonArray rs = new JsonArray();
		repos.forEach(rs::add);
		o.add("repos", rs);
		JsonArray origin = new JsonArray();
		origin.add(v.ox());
		origin.add(v.oy());
		origin.add(v.oz());
		o.add("origin", origin);
		o.addProperty("turns", v.turns());
		o.addProperty("rotation", BlueprintTransform.rotationName(v.turns()));
		o.addProperty("front", v.front());
		Anchors.Bounds b = v.box();
		o.addProperty("box", b.minX() + "," + b.minY() + "," + b.minZ() + " .. " + b.maxX() + "," + b.maxY() + "," + b.maxZ());
		o.addProperty("locked", v.locked());
		o.addProperty("pending", v.pending());
		o.addProperty("forceArmed", v.forceArmed());
		JsonObject c = new JsonObject();
		c.addProperty("obstructed", v.obstructedCount());
		c.addProperty("blockEntities", v.blockedCount());
		c.addProperty("playerInside", v.playerInside());
		c.addProperty("water", v.waterCount());
		c.addProperty("lava", v.lavaCount());
		c.addProperty("foundation", v.fillCount());
		c.addProperty("cleared", v.clearCount());
		c.addProperty("snapshotMinY", v.snapMinY());
		Anchors.Bounds sb = v.snapBox();
		c.addProperty("snapshotBox", sb.minX() + "," + sb.minY() + "," + sb.minZ() + " .. " + sb.maxX() + "," + sb.maxY() + "," + sb.maxZ());
		Approach.Plan ap = v.approach();
		JsonObject a = new JsonObject();
		a.addProperty("rows", ap.rows());
		a.addProperty("path", ap.pathCount());
		a.addProperty("slabs", ap.slabs().length / 3);
		a.addProperty("fill", ap.fillCount());
		a.addProperty("cleared", ap.clearCount());
		a.addProperty("water", ap.waterCount());
		a.addProperty("lava", ap.lavaCount());
		a.addProperty("blockEntities", ap.blockEntityCount());
		JsonArray feet = new JsonArray();
		for (int f : ap.feet()) {
			feet.add(f);
		}
		a.add("feet", feet);
		if (ap.end() != null) {
			JsonArray e = new JsonArray();
			for (double d : ap.end()) {
				e.add(d);
			}
			a.add("end", e);
		}
		c.add("approach", a);
		JsonArray notes = new JsonArray();
		v.notes().forEach(notes::add);
		c.add("notes", notes);
		JsonArray refs = new JsonArray();
		v.refusals().forEach(refs::add);
		c.add("refusals", refs);
		c.addProperty("wouldPlace", v.refusals().isEmpty());
		o.add("conflicts", c);
		JsonObject r = new JsonObject();
		r.addProperty("cells", v.model().count());
		r.addProperty("visibleCells", v.model().visibleCount());
		r.addProperty("faces", v.model().faceCount());
		r.addProperty("conflictFaces", faces(v.obstructed()) + faces(v.blocked()));
		r.addProperty("lastFrameQuads", GhostRenderer.lastQuads);
		r.addProperty("lastFrameMicros", GhostRenderer.lastNanos / 1000);
		r.addProperty("maxFrameMicros", GhostRenderer.maxNanos / 1000);
		r.addProperty("frames", GhostRenderer.frames);
		o.add("render", r);
		return o;
	}

	private static int faces(int[] quads) {
		int n = 0;
		for (int i = 3; i < quads.length; i += 4) {
			n += Integer.bitCount(quads[i]);
		}
		return n;
	}

	/** A growable int array (no boxing). */
	static final class IntList {
		private int[] a = new int[48];
		private int n;

		void add(int x, int y, int z, int w) {
			if (n + 4 > a.length) {
				a = java.util.Arrays.copyOf(a, a.length * 2);
			}
			a[n++] = x;
			a[n++] = y;
			a[n++] = z;
			a[n++] = w;
		}

		int size() {
			return n;
		}

		int[] toArray() {
			return java.util.Arrays.copyOf(a, n);
		}
	}
}
