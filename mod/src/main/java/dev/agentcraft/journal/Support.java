package dev.agentcraft.journal;

import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseCoralPlantTypeBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FrogspawnBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SporeBlossomBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks that need a support and pop off when a neighbour changes while it is missing (docs/BUILDINGS.md "Vines and
 * hanging plants"). Two sets; blocks with a block entity are in neither. Any thread.
 *
 * <ul>
 * <li>{@link #late}: the blocks a box restore must write after the rest of the box, quietly. A restore writes full blocks
 * first, then the other blocks lowest first, so these would be written before their support and the next write beside
 * them pops them: blocks that hang from above or sit on a side (vines, cocoa, glow lichen and the other multiface blocks,
 * hanging roots, cave and weeping vines, pointed dripstone, spore blossoms, pale hanging moss), the halves of two-block
 * plants (the lower half is written before the upper one and pops at the next write beside it), and mushrooms (they check
 * the light whenever a neighbour changes, and a building's lamps are still in the light engine while its box is
 * restored). Blocks that stand on the block below (grass, flowers, snow layers, carpets) are not: the order already writes
 * their support first, and a quiet write could keep a stale neighbour (a grass block under snow would keep
 * {@code snowy=false} next to an air placeholder).</li>
 * <li>{@link #needs}: every block that needs a support: {@link #late} plus the ones standing on the block below (plants,
 * saplings, sugar cane, cactus, bamboo, kelp and twisting vines, snow layers, carpets, dripleaf, coral, chorus, amethyst
 * buds). Around a site these are recorded ({@code building.PlantGuard}): a placement takes their support when the box cuts a
 * stack (bamboo, sugar cane) or a plant stands on a cell it changes.</li>
 * </ul>
 */
public final class Support {
	private Support() {
	}

	/** Whether a box restore writes {@code s} after the rest of the box, quietly. */
	public static boolean late(BlockState s) {
		if (s.hasBlockEntity()) {
			return false;
		}
		Block b = s.getBlock();
		return b instanceof VineBlock || b instanceof CocoaBlock || b instanceof MultifaceBlock || b instanceof HangingRootsBlock
			|| b instanceof CaveVinesBlock || b instanceof CaveVinesPlantBlock || b instanceof WeepingVinesBlock
			|| b instanceof WeepingVinesPlantBlock || b instanceof PointedDripstoneBlock || b instanceof SporeBlossomBlock
			|| b instanceof HangingMossBlock || b instanceof MushroomBlock || b instanceof DoublePlantBlock;
	}

	/** Whether {@code s} needs a support: {@link #late}, or a block standing on the block below. */
	public static boolean needs(BlockState s) {
		if (s.hasBlockEntity()) {
			return false;
		}
		Block b = s.getBlock();
		return late(s) || b instanceof VegetationBlock || b instanceof SugarCaneBlock || b instanceof CactusBlock || b instanceof BambooStalkBlock
			|| b instanceof BambooSaplingBlock || b instanceof GrowingPlantBlock || b instanceof SnowLayerBlock || b instanceof CarpetBlock
			|| b instanceof MossyCarpetBlock || b instanceof BigDripleafBlock || b instanceof BigDripleafStemBlock
			|| b instanceof BaseCoralPlantTypeBlock || b instanceof FrogspawnBlock || b instanceof AmethystClusterBlock
			|| b instanceof ChorusPlantBlock || b instanceof ChorusFlowerBlock;
	}
}
