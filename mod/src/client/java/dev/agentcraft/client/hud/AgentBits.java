package dev.agentcraft.client.hud;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.monitor.ScreenStyle;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.labui.client.world.SpeechBubble;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The agent-shaped helpers that lab-ui's {@code UiStyle}, {@code UiBits}, {@code ScreenStyle} and {@code SpeechBubble} left
 * behind when they moved out (they read the Foreman and the cast): agent name colours, display names, the user, and the
 * portraits. Same behaviour as before the move.
 */
public final class AgentBits {
	/** Not an opaque ARGB colour, so never a loaded ui-style token: "no token for this agent". */
	private static final int NO_TOKEN = 0;

	/** "@name" addressees of the speech bubbles: the Foreman agent's name, and the cast member's paper colour. */
	public static final SpeechBubble.Addressees ADDRESSEES = new SpeechBubble.Addressees() {
		@Override
		public @Nullable String name(String id) {
			ForemanState st = Foreman.state();
			Agent a = st == null ? null : st.agent(id);
			return a != null ? a.name() : null;
		}

		@Override
		public @Nullable Integer color(String id) {
			return Cast.get(id) != null ? agentOnLight(id) : null;
		}
	};

	private AgentBits() {
	}

	// ------------------------------------------------------------------ colours

	/** Agent name colour on dark surfaces (nameplates, HUD, console): ui-style {@code agents.<id>.text_on_dark}, then the cast; else cream. */
	public static int agentOnDark(String agentId) {
		int c = UiStyle.color("agents." + agentId + ".text_on_dark", NO_TOKEN);
		if (c != NO_TOKEN) {
			return c;
		}
		Cast.Member m = Cast.get(agentId);
		return m != null ? 0xFF000000 | m.textOnDark() : UiStyle.CREAM;
	}

	/** Agent name colour on paper GUIs: ui-style {@code agents.<id>.text_on_light}, then the cast; else ink. */
	public static int agentOnLight(String agentId) {
		int c = UiStyle.color("agents." + agentId + ".text_on_light", NO_TOKEN);
		if (c != NO_TOKEN) {
			return c;
		}
		Cast.Member m = Cast.get(agentId);
		return m != null ? 0xFF000000 | m.textOnLight() : UiStyle.INK;
	}

	/** Agent name colour on a monitor screen (was {@code ScreenStyle.name}). */
	public static int onScreen(ScreenStyle st, String agentId) {
		return st.dark() ? agentOnDark(agentId) : agentOnLight(agentId);
	}

	/** Agent name colour on paper; the user ("user"/"you") in clay-dark. */
	public static int nameOnLight(@Nullable String agentId) {
		if (agentId == null || agentId.isEmpty()) {
			return UiBits.ink();
		}
		if (isUser(agentId)) {
			return UiStyle.CLAY_DARK;
		}
		return agentOnLight(agentId);
	}

	public static int nameOnDark(@Nullable String agentId) {
		if (agentId == null || agentId.isEmpty()) {
			return UiBits.cream();
		}
		if (isUser(agentId)) {
			return UiStyle.CLAY;
		}
		return agentOnDark(agentId);
	}

	// ------------------------------------------------------------------ names

	public static boolean isUser(@Nullable String id) {
		return id != null && (id.equals("user") || id.equals("you") || id.equalsIgnoreCase(userName()));
	}

	/**
	 * The person the team works for, as the Foreman reports it (foreman.status userName, set with
	 * --user-name / AGENTCRAFT_USER_NAME; default the OS account name). "You" before the first status.
	 */
	public static String userName() {
		var st = Foreman.state().status();
		String n = st == null ? null : st.userName();
		return n == null || n.isBlank() ? "You" : n;
	}

	/** Display name of an agent id (Foreman name, then cast name, then the id). */
	public static String agentName(@Nullable String agentId) {
		if (agentId == null || agentId.isEmpty()) {
			return "";
		}
		if (isUser(agentId)) {
			return "You";
		}
		if (agentId.equals("all")) {
			return "everyone";
		}
		Agent a = Foreman.state() == null ? null : Foreman.state().agent(agentId);
		if (a != null) {
			return a.name();
		}
		Cast.Member m = Cast.get(agentId);
		if (m != null) {
			return m.name();
		}
		return Character.toUpperCase(agentId.charAt(0)) + agentId.substring(1);
	}

	// ------------------------------------------------------------------ portraits

	private static Identifier portraitTexture(String agentId, boolean framed) {
		return AgentCraft.id("textures/gui/portrait/" + agentId + (framed ? "_framed" : "") + ".png");
	}

	public static boolean hasPortrait(@Nullable String agentId) {
		return agentId != null && Cast.get(agentId) != null;
	}

	/**
	 * The agent's 8x8 face at {@code scale} (1 = 8 px). Unknown agents (or the user) get a small
	 * round placeholder in their colour so rows stay aligned.
	 */
	public static void face(GuiGraphicsExtractor g, @Nullable String agentId, int x, int y, int scale) {
		int s = 8 * Math.max(1, scale);
		if (hasPortrait(agentId)) {
			g.blit(RenderPipelines.GUI_TEXTURED, portraitTexture(agentId, false), x, y, 0, 0, s, s, 8, 8, 8, 8);
			return;
		}
		// placeholder: a status-style dot centred in the face box
		int d = Math.min(7, s);
		Panels.sprite(g, Kit.dot(isUser(agentId) ? "waiting" : "idle", false), x + (s - d) / 2, y + (s - d) / 2, 7, 7);
	}

	/** The 20x20 framed portrait (brass rim + identity ring) at {@code scale}. */
	public static void framedPortrait(GuiGraphicsExtractor g, @Nullable String agentId, int x, int y, int scale) {
		int s = 20 * Math.max(1, scale);
		if (hasPortrait(agentId)) {
			g.blit(RenderPipelines.GUI_TEXTURED, portraitTexture(agentId, true), x, y, 0, 0, s, s, 20, 20, 20, 20);
			return;
		}
		Panels.sprite(g, Kit.PANEL_INSET, x, y, s, s);
		face(g, agentId, x + (s - 8 * scale) / 2, y + (s - 8 * scale) / 2, scale);
	}
}
