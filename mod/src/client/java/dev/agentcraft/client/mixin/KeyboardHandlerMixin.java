package dev.agentcraft.client.mixin;

import dev.agentcraft.client.building.BuildingWizardFeature;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	/**
	 * While the building wizard is placing (no screen open), its keys (R, arrows, PgUp/PgDn, L, Enter,
	 * Esc, Backspace) are consumed before vanilla sees them, so Esc cancels instead of pausing and L
	 * does not open the advancements. Releases always pass through.
	 */
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void agentcraft$wizardKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (BuildingWizardFeature.onKey(action, event)) {
			ci.cancel();
		}
	}
}
