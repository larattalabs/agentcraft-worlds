package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Village board panel (docs/VILLAGE.md V2). Binding: empty = the whole village (every building of the world); nothing
 * else is defined yet. Drawn by the client's village.VillageBoardRenderer from the panel origin.
 */
public class VillageBoardBlockEntity extends StationBlockEntity {
	public VillageBoardBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.VILLAGE_BOARD, pos, state);
	}
}
