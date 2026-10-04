package dev.agentcraft.client.mixin;

import dev.agentcraft.client.agents.OutdoorRoutes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change the client applies (server updates, predictions) reaches {@code sendBlockUpdated}: the
 * outdoor route cache drops routes passing near it (docs/WAVE2.md W8). Optional ({@code require = 0}): if a
 * later version renames the method, cached routes are still re-checked before reuse and while walked.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelBlockMixin {
	@Inject(method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
		+ "Lnet/minecraft/world/level/block/state/BlockState;I)V", at = @At("HEAD"), require = 0)
	private void agentcraft$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		if (oldState != newState) {
			try {
				OutdoorRoutes.get().onBlockChanged(pos);
			} catch (Throwable t) {
				dev.agentcraft.AgentCraft.LOGGER.warn("walk: block change hook failed", t);
			}
		}
	}
}
