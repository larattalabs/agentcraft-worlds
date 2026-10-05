package dev.agentcraft.client.hud;

import dev.agentcraft.client.mixin.BossHealthOverlayAccessor;
import dev.agentcraft.client.mixin.ChatComponentAccessor;
import dev.agentcraft.hud.HudLayout;
import dev.agentcraft.hud.HudLayout.Env;
import dev.agentcraft.hud.HudLayout.Rect;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * What else is on screen this frame, for {@link HudLayout}: effect icons, boss bars, survival status rows, the chat
 * lines showing right now (the chat area counts only while it shows something: messages fade after 10 s), the
 * connection pill drawn this frame and the top-left minimap room. Client thread.
 */
public final class HudEnv {
	private HudEnv() {
	}

	/** The environment of the frame being drawn ({@code guiW} x {@code guiH} GUI px). */
	public static Env current(Minecraft mc, int guiW, int guiH) {
		int scale = Math.max(1, mc.getWindow().getGuiScale());
		Env e = Env.of(guiW, guiH, scale);
		if (mc.player == null) {
			return e.chat(0, 0);
		}
		int good = 0;
		int bad = 0;
		var screen = mc.gui.screen();
		if (screen == null || !screen.showsActiveEffects()) {
			for (MobEffectInstance i : mc.player.getActiveEffects()) {
				if (i.showIcon()) {
					if (i.getEffect().value().isBeneficial()) {
						good++;
					} else {
						bad++;
					}
				}
			}
		}
		int bars = 0;
		int titleW = 0;
		var events = ((BossHealthOverlayAccessor) mc.gui.hud.getBossOverlay()).agentcraft$events();
		for (LerpingBossEvent ev : events.values()) {
			bars++;
			titleW = Math.max(titleW, mc.font.width(ev.getName()));
		}
		boolean survival = mc.gameMode != null && mc.gameMode.canHurtPlayer();
		int[] chat = chatSize(mc);
		int area = Mth.ceil(ChatComponent.getHeight(mc.options.chatHeightUnfocused().get()) * mc.options.chatScale().get());
		Rect pill = ConnectionBanner.pillBottom > 0
			? new Rect(ConnectionBanner.pillLeft, ConnectionBanner.pillTop, guiW - ConnectionBanner.pillLeft, ConnectionBanner.pillBottom - ConnectionBanner.pillTop)
			: Rect.NONE;
		int[] panel = dev.agentcraft.client.building.BuildPlacement.hudRect();
		Rect other = panel == null ? Rect.NONE : new Rect(panel[0], panel[1], panel[2], panel[3]);
		return e.withPanel(other).effects(good, bad).withDemo(mc.isDemo()).boss(bars, titleW).survival(survival).chat(chat[0], chat[1]).chatArea(area).withPill(pill)
			.withMinimap(HudConfig.get().topLeftOffset());
	}

	/** The chat's width and the height of the lines it shows right now (GUI px); 0, 0 = nothing shows. */
	static int[] chatSize(Minecraft mc) {
		ChatComponent chat = mc.gui.hud.getChat();
		List<GuiMessage.Line> lines = ((ChatComponentAccessor) chat).agentcraft$trimmedMessages();
		int perPage = chat.getLinesPerPage();
		int shown;
		if (chat.isChatFocused()) {
			shown = Math.min(lines.size(), perPage);
		} else {
			int now = mc.gui.hud.getGuiTicks();
			shown = 0;
			for (int i = 0; i < Math.min(lines.size(), perPage); i++) {
				if (now - lines.get(i).addedTime() < 200) {
					shown++;
				}
			}
		}
		if (shown == 0) {
			return new int[] {0, 0};
		}
		double scale = mc.options.chatScale().get();
		int entry = (int) (9 * (mc.options.chatLineSpacing().get() + 1.0));
		int w = ChatComponent.getWidth(mc.options.chatWidth().get());
		return new int[] {Mth.ceil(w * scale), Mth.ceil(shown * entry * scale) + 1};
	}
}
