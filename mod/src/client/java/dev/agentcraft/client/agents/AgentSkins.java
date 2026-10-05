package dev.agentcraft.client.agents;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Agent skins: {@code assets/agentcraft_worlds/textures/entity/agent/<skin>.png} (64x64, both layers) with
 * the arm model from cast.json ({@code slim} = 3 px arms). Unknown skins fall back to a vanilla
 * default skin so a new Foreman agent still renders.
 */
public final class AgentSkins {
	private static final Map<String, PlayerSkin> CACHE = new HashMap<>();
	/** The skin a lead without its own texture wears. */
	private static final String LEAD_FALLBACK = "marlow";

	private AgentSkins() {
	}

	public static PlayerSkin get(String agentId, String skinId) {
		return get(agentId, skinId, false);
	}

	/**
	 * The skin of an agent; a lead ({@code lead}) without a texture of its own (a new lead whose skin is
	 * not generated yet) wears Marlow's, so it reads as a lead rather than a vanilla default.
	 */
	public static PlayerSkin get(String agentId, String skinId, boolean lead) {
		return CACHE.computeIfAbsent(agentId + "|" + skinId + (lead ? "|lead" : ""), k -> create(agentId, skinId, lead));
	}

	private static PlayerSkin create(String agentId, String skinId, boolean lead) {
		Identifier asset = AgentCraft.id("entity/agent/" + skinId);
		ClientAsset.ResourceTexture tex = new ClientAsset.ResourceTexture(asset);
		Cast.Member m = Cast.get(agentId);
		if (m == null) {
			m = Cast.get(skinId);
		}
		boolean exists = Minecraft.getInstance().getResourceManager().getResource(tex.texturePath()).isPresent();
		if (!exists && lead && !skinId.equals(LEAD_FALLBACK)) {
			AgentCraft.LOGGER.info("No skin texture {} for lead {}; wearing {}'s", tex.texturePath(), agentId, LEAD_FALLBACK);
			return create(LEAD_FALLBACK, LEAD_FALLBACK, false);
		}
		if (!exists) {
			AgentCraft.LOGGER.warn("No skin texture {} for agent {}; using a default skin", tex.texturePath(), agentId);
			return DefaultPlayerSkin.get(java.util.UUID.nameUUIDFromBytes(agentId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		return PlayerSkin.insecure(tex, null, null, m != null && m.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE);
	}

	public static void clear() {
		CACHE.clear();
	}
}
