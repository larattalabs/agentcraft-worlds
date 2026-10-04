package dev.agentcraft.client.hub;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.client.world.ServerTasks;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * What the hub's building buttons do, on the integrated server through {@link ServerTasks#callAsPlayer}
 * (no operator permission: the hub calls {@link Buildings} directly, never a chat command). Every action
 * completes on the client thread with a {@link Result}, which is also kept as {@link #last()} and shown
 * as a toast.
 *
 * <p>Remove and Teleport refuse unless the player is in the building's dimension ({@link Building#dimension()}):
 * removing pastes the saved terrain into the level it is given, so doing it in the wrong dimension would
 * wreck that box and lose the snapshot. Records from before the dimension was recorded fall back to
 * checking that the box in the player's dimension holds AgentCraft stations ({@link Buildings#stationCount}). Remove also refuses while the player stands in the box (the
 * terrain coming back would bury them), and Teleport only lands on a free two-block-high spot with a
 * floor (survival-safe: no fall, no suffocation, no lava).
 */
public final class HubActions {
	/** The outcome of a hub action (message meant for the player). */
	public record Result(String action, @Nullable String buildingId, boolean ok, String message) {
	}

	private static volatile @Nullable Result last;

	private HubActions() {
	}

	public static @Nullable Result last() {
		return last;
	}

	/** Makes {@code id} the home building (idle agents go there). */
	public static CompletableFuture<Result> makeHome(String id) {
		return run("home", id, (level, player) -> {
			Building b = Buildings.setHome(level.getServer(), id);
			return b.id() + " (" + b.blueprint() + ") is now home";
		});
	}

	/** Puts back what was in the building's box before it was placed, then forgets it. */
	public static CompletableFuture<Result> remove(String id) {
		return remove(id, false);
	}

	/**
	 * {@link #remove(String)}; {@code force} (a further explicit confirm after a "move these first" refusal) takes
	 * it down although things the building did not bring are inside (they are lost).
	 */
	public static CompletableFuture<Result> remove(String id, boolean force) {
		return run("remove", id, (level, player) -> {
			Building b = requireHere(level, id, "remove");
			BlockPos feet = player.blockPosition();
			Anchors.Bounds box = b.box();
			if (box.contains(feet.getX(), feet.getY(), feet.getZ()) || box.contains(feet.getX(), feet.getY() + 1, feet.getZ())) {
				throw new Buildings.BuildingException("Step out of " + id + " first: removing it puts the old terrain back where you stand");
			}
			Buildings.remove(level, id, force);
			return "Removed " + id + " (" + b.blueprint() + "); the area is as it was before" + (force ? " (what was listed is gone)" : "");
		});
	}

	/** Gives the building other repos (wing n = repos[n-1]) without re-placing it ({@link Buildings#setRepos}). */
	public static CompletableFuture<Result> setRepos(String id, java.util.List<String> repos) {
		return run("edit_repos", id, (level, player) -> {
			Building b = Buildings.setRepos(level.getServer(), id, repos);
			return id + " now hosts " + String.join(", ", b.repos()) + " (its lead is told)";
		});
	}

	/** Moves the building back to where it stood before its last move ({@link Buildings#undoMove}). */
	public static CompletableFuture<Result> undoMove(String id, boolean force) {
		return run("undo_move", id, (level, player) -> {
			Building b = Buildings.undoMove(level.getServer(), id, force);
			String note = Buildings.lastNote();
			return id + " is back at " + b.box().minX() + ", " + b.box().minY() + ", " + b.box().minZ() + (note == null ? "" : " (" + note + ")")
				+ "; the site it left is as it was";
		});
	}

	/** Teleports the player to the building's entrance (same dimension, a safe spot near the anchor). */
	public static CompletableFuture<Result> teleport(String id) {
		return run("teleport", id, (level, player) -> {
			Building b = requireHere(level, id, "teleport to");
			Anchor a = b.anchors().get("entrance");
			if (a == null) {
				a = b.anchors().get("spawn");
			}
			Anchors.Bounds box = b.box();
			double x = a != null ? a.x() : (box.minX() + box.maxX() + 1) / 2.0;
			double y = a != null ? a.y() : box.minY() + 1;
			double z = a != null ? a.z() : (box.minZ() + box.maxZ() + 1) / 2.0;
			float yaw = a != null ? a.yaw() : player.getYRot();
			double[] spot = safeSpot(level, player, x, y, z);
			if (spot == null) {
				throw new Buildings.BuildingException("No safe spot at " + id + "'s entrance (" + Math.round(x) + ", " + Math.round(y) + ", "
					+ Math.round(z) + "): it is blocked; nothing was done");
			}
			player.stopRiding();
			player.teleportTo(level, spot[0], spot[1], spot[2], Set.of(), yaw, 0f, true);
			player.resetFallDistance();
			return "Teleported to " + id + " (" + b.blueprint() + ")";
		});
	}

	/**
	 * The building, if it is in the player's dimension; otherwise a refusal naming both. A record from
	 * before buildings recorded their dimension falls back to the old check: its box in this level must
	 * hold AgentCraft stations.
	 */
	private static Building requireHere(ServerLevel level, String id, String verb) throws Buildings.BuildingException {
		Building b = Buildings.get(id);
		if (b == null) {
			throw new Buildings.BuildingException("No building " + id);
		}
		String here = Buildings.dimensionId(level);
		if (b.dimension() != null) {
			if (!b.dimension().equals(here)) {
				throw new Buildings.BuildingException(id + " is in " + pretty(b.dimension()) + " and you are in " + pretty(here) + ": go there to "
					+ verb + " it; nothing was done");
			}
			return b;
		}
		if (Buildings.stationCount(level, b.box()) == 0) {
			throw new Buildings.BuildingException("Found no AgentCraft stations in " + id + "'s box in " + pretty(here) + " (an old record "
				+ "without its dimension, assumed " + pretty(Building.OVERWORLD) + "): it is in another dimension (or its stations were broken). "
				+ "Go there to " + verb + " it; nothing was done");
		}
		return b;
	}

	/** "minecraft:the_nether" -> "the nether"; other namespaces stay as they are. */
	public static String pretty(String dimension) {
		return dimension.startsWith("minecraft:") ? dimension.substring(10).replace('_', ' ') : dimension;
	}

	/**
	 * A spot at most 4 blocks above (or 1 below) the anchor where the player fits with no collision, no
	 * lava at the feet or head, and something to stand on: {x, y, z} feet position, or null.
	 */
	static double @Nullable [] safeSpot(ServerLevel level, ServerPlayer player, double x, double y, double z) {
		int by = (int) Math.floor(y);
		for (int dy : new int[] {0, 1, -1, 2, 3, 4}) {
			double fy = by + dy;
			AABB bb = player.getDimensions(player.getPose()).makeBoundingBox(x, fy, z);
			BlockPos feet = BlockPos.containing(x, fy, z);
			BlockPos below = feet.below();
			if (!level.noCollision(player, bb) || level.getFluidState(feet).is(FluidTags.LAVA) || level.getFluidState(feet.above()).is(FluidTags.LAVA)) {
				continue;
			}
			if (level.getBlockState(below).getCollisionShape(level, below).isEmpty() || level.getFluidState(below).is(FluidTags.LAVA)) {
				continue;
			}
			return new double[] {x, fy, z};
		}
		return null;
	}

	/** Server work that may refuse with a {@link Buildings.BuildingException}. */
	@FunctionalInterface
	interface Work {
		String apply(ServerLevel level, ServerPlayer player) throws Buildings.BuildingException;
	}

	private static CompletableFuture<Result> run(String action, @Nullable String id, Work work) {
		return ServerTasks.callAsPlayer((level, player) -> {
			try {
				return new Result(action, id, true, work.apply(level, player));
			} catch (Buildings.BuildingException e) {
				return new Result(action, id, false, e.getMessage());
			}
		}).exceptionally(t -> {
			Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
			if (!(c instanceof ServerTasks.Refused)) {
				AgentCraft.LOGGER.error("Hub action {} {} failed", action, id, c);
			}
			return new Result(action, id, false, c instanceof ServerTasks.Refused ? c.getMessage() : action + " failed: " + c);
		}).thenApply(r -> {
			last = r;
			Toasts.push(new Notify(r.ok() ? NotifyLevel.INFO : NotifyLevel.WARN, r.message(), null, System.currentTimeMillis()));
			return r;
		});
	}
}
