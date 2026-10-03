package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Task Wall panel. Binding (read from the panel origin): empty or anything else = all tasks (columns
 * todo/doing/review/done, blocked cards on top); {@code repo:<repoId>} = only that repo's tasks, under a
 * title strip with the repo's name (a building's wall; {@code repo:#n} is rewritten at placement). Drawn by
 * client taskwall.TaskBoardRenderer from the panel origin.
 */
public class TaskBoardBlockEntity extends StationBlockEntity {
	public TaskBoardBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.TASK_BOARD, pos, state);
	}
}
