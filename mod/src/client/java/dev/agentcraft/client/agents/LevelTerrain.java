package dev.agentcraft.client.agents;

import dev.agentcraft.walk.Terrain;
import dev.agentcraft.walk.WalkCell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.multiplayer.ClientLevel;
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
 * The client level as {@link WalkCell} codes for the outdoor planner (client thread only: it reads the
 * level directly; the planner runs a budget per tick there instead of on another thread, so nothing is
 * snapshotted). A chunk the client has not loaded reads {@link WalkCell#UNLOADED}.
 */
final class LevelTerrain implements Terrain {
	private final ClientLevel level;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	LevelTerrain(ClientLevel level) {
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

	static int classify(net.minecraft.world.level.Level level, BlockPos pos, BlockState s) {
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
