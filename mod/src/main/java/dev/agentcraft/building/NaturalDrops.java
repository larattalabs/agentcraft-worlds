package dev.agentcraft.building;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Natural drops (docs/BUILDINGS.md "Safe remove"): what trees and plants drop by themselves around a building: leaves
 * decaying after the placement cleared their logs (saplings, sticks, apples), leaf litter, seeds and flowers from plants
 * the placement or the approach cleared. They are nobody's items: they never block a Remove (or a Move's old site) and
 * are not listed as the player's dropped items. An item a player threw is always the player's, whatever it is. The rule
 * on ids and tag flags is pure ({@link #natural(String, boolean, boolean, boolean)}, NaturalDropsTest).
 */
public final class NaturalDrops {
	/** Items that only ever lie around because a plant or a tree dropped them (beside the sapling and flower tags). */
	static final Set<String> IDS = Set.of("minecraft:stick", "minecraft:apple", "minecraft:wheat_seeds", "minecraft:beetroot_seeds",
		"minecraft:pumpkin_seeds", "minecraft:melon_seeds", "minecraft:torchflower_seeds", "minecraft:pitcher_pod", "minecraft:leaf_litter",
		"minecraft:pink_petals", "minecraft:wildflowers", "minecraft:sweet_berries", "minecraft:glow_berries", "minecraft:cocoa_beans",
		"minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern", "minecraft:bush", "minecraft:firefly_bush",
		"minecraft:dead_bush", "minecraft:short_dry_grass", "minecraft:tall_dry_grass", "minecraft:cactus_flower", "minecraft:mangrove_propagule",
		"minecraft:vine", "minecraft:moss_carpet", "minecraft:hanging_roots", "minecraft:spore_blossom");

	private NaturalDrops() {
	}

	/**
	 * Whether an item lying in a site is a natural drop: not thrown by a player, and a sapling, a flower or one of
	 * {@link #IDS}. Pure.
	 *
	 * @param id the item id ({@code minecraft:stick})
	 * @param sapling whether the item is in {@code #saplings}
	 * @param flower whether the item is in {@code #flowers}
	 * @param thrownByPlayer whether a player dropped or threw it
	 */
	public static boolean natural(String id, boolean sapling, boolean flower, boolean thrownByPlayer) {
		if (thrownByPlayer) {
			return false;
		}
		return sapling || flower || IDS.contains(id);
	}

	/** {@link #natural(String, boolean, boolean, boolean)} for a dropped item stack. Server or client. */
	public static boolean natural(ItemEntity e) {
		ItemStack s = e.getItem();
		if (s.isEmpty() || s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
			return false; // a named item is somebody's
		}
		String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
		return natural(id, s.is(ItemTags.SAPLINGS), s.is(BlockItemTags.FLOWERS.item()), e.getOwner() instanceof Player);
	}
}
