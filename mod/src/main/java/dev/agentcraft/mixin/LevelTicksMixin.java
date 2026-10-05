package dev.agentcraft.mixin;

import dev.agentcraft.journal.LeafTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaf ticks scheduled while AgentCraft restores a site are dropped ({@link LeafTicks}, docs/BUILDINGS.md "Leaf ring"):
 * the restored leaves keep their recorded distances instead of relaxing. Only active inside a restore on the server
 * thread; every other tick is scheduled as usual.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {
	@Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
	private void agentcraft$dropLeafTicks(ScheduledTick<T> tick, CallbackInfo ci) {
		if (LeafTicks.drops(tick.type())) {
			ci.cancel();
		}
	}
}
