package dev.agentcraft.compat;

import dev.agentcraft.AgentCraft;
import java.util.List;
import net.fabricmc.fabric.api.event.registry.FabricRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Registry aliases {@code agentcraft:<x>} -> {@code agentcraft_worlds:<x>} (Fabric API's
 * {@link FabricRegistry#addAlias}) for every block, item, block entity type, entity type and creative tab this mod
 * registers, so worlds saved before the rename (blocks in chunks, block entities, the agent entity, items in
 * inventories) still load. Defensively skipped when a mod {@code agentcraft} (upstream) is loaded too: its entries
 * would own those ids. Installing both is not supported anyway (shared {@code dev.agentcraft} classes and mixin
 * packages, command root and game-dir folder; docs/FORK.md "Mod id").
 */
public final class LegacyAliases {
	private LegacyAliases() {
	}

	/** Call once, after every registry entry of the mod is registered. */
	public static void register() {
		if (FabricLoader.getInstance().isModLoaded(LegacyIds.LEGACY_NAMESPACE)) {
			AgentCraft.LOGGER.warn("Mod '{}' (upstream AgentCraft) is loaded too (not supported, docs/FORK.md \"Mod id\"): no {}:* -> {}:* aliases",
				LegacyIds.LEGACY_NAMESPACE, LegacyIds.LEGACY_NAMESPACE, AgentCraft.MOD_ID);
			return;
		}
		int n = alias(BuiltInRegistries.BLOCK) + alias(BuiltInRegistries.ITEM) + alias(BuiltInRegistries.BLOCK_ENTITY_TYPE)
			+ alias(BuiltInRegistries.ENTITY_TYPE) + alias(BuiltInRegistries.CREATIVE_MODE_TAB);
		AgentCraft.LOGGER.info("Legacy ids: {} aliases {}:* -> {}:*", n, LegacyIds.LEGACY_NAMESPACE, AgentCraft.MOD_ID);
	}

	private static int alias(Registry<?> registry) {
		List<Identifier> ours = registry.keySet().stream().filter(id -> id.getNamespace().equals(AgentCraft.MOD_ID)).toList();
		for (Identifier id : ours) {
			((FabricRegistry) registry).addAlias(Identifier.fromNamespaceAndPath(LegacyIds.LEGACY_NAMESPACE, id.getPath()), id);
		}
		return ours.size();
	}
}
