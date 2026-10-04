package dev.agentcraft.block;

import dev.agentcraft.block.entity.VillageBoardBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The village board (docs/VILLAGE.md V2): a connectable wall display like the task board (same panel shape and
 * connection rule: same block, same facing, adjacent), drawn by the client's {@code village.VillageBoardRenderer}
 * from the panel origin with every building, the newest milestones and holds. Placed by the {@code village_board}
 * fixture blueprint; it only ever connects to other village board blocks, never to a task board.
 */
public class VillageBoardBlock extends PanelBlock {
	public VillageBoardBlock(BlockBehaviour.Properties properties) {
		super(properties, 3);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new VillageBoardBlockEntity(pos, state);
	}
}
