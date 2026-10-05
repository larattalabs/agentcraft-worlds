package dev.agentcraft.client.mixin;

import java.util.List;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The HUD layout: the chat lines on screen (how tall the chat is right now), so the overlay never covers them. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("trimmedMessages")
	List<GuiMessage.Line> agentcraft$trimmedMessages();
}
