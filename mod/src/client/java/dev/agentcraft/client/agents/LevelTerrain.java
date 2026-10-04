package dev.agentcraft.client.agents;

import dev.agentcraft.walk.Terrain;
import dev.agentcraft.walk.WalkCell;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;

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

	/** {@link dev.agentcraft.walk.LevelWalk#classify} (the server checks road routes with the same rules). */
	static int classify(net.minecraft.world.level.Level level, BlockPos pos, BlockState s) {
		return dev.agentcraft.walk.LevelWalk.classify(level, pos, s);
	}
}
