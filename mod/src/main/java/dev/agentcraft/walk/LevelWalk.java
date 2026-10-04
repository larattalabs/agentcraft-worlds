package dev.agentcraft.walk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A level (client or server) as {@link WalkCell} codes: the client's outdoor planner reads the client level with these
 * rules, and the server checks a road's route against its own level with the same ones (docs/VILLAGE.md V1). A chunk
 * that is not loaded reads {@link WalkCell#UNLOADED} (nothing is ever loaded or generated). Owning thread only.
 */
public final class LevelWalk implements Terrain {
	private final Level level;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	public LevelWalk(Level level) {
		this.level = level;
	}

	@Override
	public int at(int x, int y, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return WalkCell.UNLOADED;
		}
		if (y < level.getMinY()) {
			return WalkCell.BLOCKED;
		}
		if (y > level.getMaxY()) {
			return WalkCell.OPEN;
		}
		pos.set(x, y, z);
		return classify(level, pos, level.getBlockState(pos));
	}

	/** The {@link WalkCell} code of block state {@code s} at {@code pos}. */
	public static int classify(Level level, BlockPos pos, BlockState s) {
		if (s.isAir()) {
			return WalkCell.OPEN;
		}
		Block b = s.getBlock();
		FluidState fs = s.getFluidState();
		if (!fs.isEmpty() && fs.getType() instanceof LavaFluid) {
			return WalkCell.HAZARD;
		}
		if (b instanceof BaseFireBlock || b instanceof MagmaBlock || b instanceof PowderSnowBlock || b instanceof CampfireBlock
			|| b instanceof CactusBlock || b instanceof SweetBerryBushBlock || b instanceof WitherRoseBlock) {
			return WalkCell.HAZARD;
		}
		if (b instanceof DoorBlock || b instanceof FenceGateBlock || b instanceof TrapDoorBlock) {
			return WalkCell.DOOR; // agents pass through (they open nothing in the world)
		}
		if (b instanceof LeavesBlock || s.is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS)) {
			// trees: solid, never a floor. A head may brush leaves, so without the trunks here a hillside route stepped
			// out onto the log tops inside a canopy downslope
			return WalkCell.LEAVES;
		}
		VoxelShape shape = s.getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return !fs.isEmpty() && fs.getType() instanceof WaterFluid ? WalkCell.WATER : WalkCell.OPEN;
		}
		return WalkCell.solid((int) Math.round(shape.max(Direction.Axis.Y) * 16));
	}
}
