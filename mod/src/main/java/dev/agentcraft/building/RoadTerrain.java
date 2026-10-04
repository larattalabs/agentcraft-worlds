package dev.agentcraft.building;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.LilyPadBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;

/**
 * A level (client or server) as {@link RoadPlan} kinds: the hub's ghost and {@link Roads#lay} classify with the same
 * function, so the ghost shows what the server will do. Only natural blocks are ever kinds a road changes (the
 * substrate, sand, gravel, natural stone, plants, snow, leaves); anything a player builds with is {@link RoadPlan#BUILT}
 * or {@link RoadPlan#BUILT_OPEN}. A chunk that is not loaded is {@link RoadPlan#UNLOADED}: nothing is loaded or
 * generated. Owning thread only.
 */
public final class RoadTerrain implements RoadPlan.World {
	private final Level level;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	public RoadTerrain(Level level) {
		this.level = level;
	}

	@Override
	public int at(int x, int y, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return RoadPlan.UNLOADED;
		}
		if (y < level.getMinY() || y > level.getMaxY()) {
			return y < level.getMinY() ? RoadPlan.BUILT : RoadPlan.AIR;
		}
		pos.set(x, y, z);
		return kind(level, pos, level.getBlockState(pos));
	}

	/** The {@link RoadPlan} kind of {@code s} at {@code pos}. */
	public static int kind(Level level, BlockPos pos, BlockState s) {
		if (s.isAir()) {
			return RoadPlan.AIR;
		}
		if (s.hasBlockEntity()) {
			return RoadPlan.BLOCK_ENTITY;
		}
		FluidState fl = s.getFluidState();
		if (fl.is(FluidTags.LAVA)) {
			return RoadPlan.LAVA;
		}
		if (!fl.isEmpty()) {
			return RoadPlan.WATER; // water and anything waterlogged (seagrass, a waterlogged leaf): never touched
		}
		Block b = s.getBlock();
		if (b instanceof BaseFireBlock || b instanceof LightBlock) {
			return RoadPlan.BUILT_OPEN; // fire spreads, a light block is the player's
		}
		if (b instanceof LeavesBlock || s.is(BlockTags.LEAVES)) {
			return RoadPlan.LEAVES;
		}
		if (s.is(BlockTags.LOGS) || b instanceof CactusBlock) {
			return RoadPlan.LOG;
		}
		if (b instanceof DoublePlantBlock || b instanceof SugarCaneBlock || b instanceof BambooStalkBlock) {
			return RoadPlan.STACK;
		}
		if (b instanceof SnowLayerBlock || b instanceof LilyPadBlock || b instanceof MushroomBlock || s.is(BlockTags.REPLACEABLE_BY_TREES)
			|| s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.SMALL_FLOWERS)) {
			return RoadPlan.PLANT;
		}
		if (s.is(Blocks.DIRT_PATH)) {
			return RoadPlan.PATH;
		}
		if (b instanceof FarmlandBlock) {
			return RoadPlan.BUILT; // a field: the player's
		}
		if (s.is(BlockTags.MUD)) {
			return RoadPlan.MUD;
		}
		if (s.is(BlockTags.DIRT) || s.is(BlockTags.GRASS_BLOCKS) || s.is(BlockTags.MOSS_BLOCKS)) {
			return RoadPlan.DIRT;
		}
		if (s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)) {
			return RoadPlan.SAND;
		}
		if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.ORES) || s.is(BlockTags.BADLANDS_TERRACOTTA)
			|| s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.TERRACOTTA)) {
			return RoadPlan.STONE;
		}
		if (s.canBeReplaced()) {
			return RoadPlan.PLANT; // other replaceable natural cover (a modded grass)
		}
		return s.getCollisionShape(level, pos).isEmpty() ? RoadPlan.BUILT_OPEN : RoadPlan.BUILT;
	}
}
