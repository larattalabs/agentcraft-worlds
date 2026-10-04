package dev.agentcraft.client.ui;

import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/** A screen that returns to the screen it was opened from on Esc (QA reads it through {@code dev.state} "ui.parent"). */
public interface HasParent {
	@Nullable Screen parent();
}
