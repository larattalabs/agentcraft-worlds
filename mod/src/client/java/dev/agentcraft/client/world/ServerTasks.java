package dev.agentcraft.client.world;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Run world changes driven by Foreman state (lamp status, podium open, merge station active, ...)
 * on the integrated server thread. Singleplayer only: AgentCraft's HQ is a local world. Keep the
 * work small and idempotent (only set a block state when it actually differs).
 *
 * <pre>
 * ServerTasks.run(level -> {
 *     BlockState s = level.getBlockState(pos);
 *     if (s.getValue(StatusLampBlock.STATUS) != wanted) level.setBlock(pos, s.setValue(StatusLampBlock.STATUS, wanted), Block.UPDATE_CLIENTS);
 * });
 * ServerTasks.callAsPlayer((level, player) -> Buildings.setHome(level.getServer(), id).id())   // hub actions
 *     .thenAccept(id -> ...);                                                                  // back on the client thread
 * </pre>
 */
public final class ServerTasks {
	private ServerTasks() {
	}

	/** Queue {@code task} on the integrated server with the overworld; false when not in singleplayer. */
	public static boolean run(Consumer<ServerLevel> task) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return false;
		}
		server.execute(() -> task.accept(server.overworld()));
		return true;
	}

	/**
	 * Runs {@code work} on the integrated server thread and completes with its result <b>on the client
	 * thread</b> (e.g. {@code Blueprints.reload(server)}). Fails with {@link Refused} when not in
	 * singleplayer. Call from the client thread.
	 */
	public static <T> CompletableFuture<T> callOnServer(java.util.function.Function<net.minecraft.server.MinecraftServer, T> work) {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			return CompletableFuture.failedFuture(new Refused("Singleplayer only: this acts through the integrated server"));
		}
		CompletableFuture<T> f = new CompletableFuture<>();
		server.execute(() -> {
			try {
				T result = work.apply(server);
				mc.execute(() -> f.complete(result));
			} catch (Throwable t) {
				mc.execute(() -> f.completeExceptionally(t));
			}
		});
		return f;
	}

	/** Thrown by {@link #callAsPlayer} work for a refusal meant for the player (the message is shown as is). */
	public static final class Refused extends RuntimeException {
		public Refused(String message) {
			super(message, null, false, false);
		}
	}

	/**
	 * Runs {@code work} on the integrated server thread with the player's own level (their current
	 * dimension) and server-side player, and completes with its result <b>on the client thread</b>. Fails
	 * with {@link Refused} when not in singleplayer or the player is not on the server yet; exceptions
	 * from {@code work} fail the future (call from the client thread).
	 */
	public static <T> CompletableFuture<T> callAsPlayer(BiFunction<ServerLevel, ServerPlayer, T> work) {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return CompletableFuture.failedFuture(new Refused("Singleplayer only: this acts through the integrated server"));
		}
		ResourceKey<Level> dim = mc.player.level().dimension();
		java.util.UUID uuid = mc.player.getUUID();
		CompletableFuture<T> f = new CompletableFuture<>();
		server.execute(() -> {
			try {
				ServerLevel level = server.getLevel(dim);
				ServerPlayer player = server.getPlayerList().getPlayer(uuid);
				if (level == null || player == null) {
					throw new Refused("The player is not on the server (yet)");
				}
				T result = work.apply(level, player);
				mc.execute(() -> f.complete(result));
			} catch (Throwable t) {
				mc.execute(() -> f.completeExceptionally(t));
			}
		});
		return f;
	}
}
