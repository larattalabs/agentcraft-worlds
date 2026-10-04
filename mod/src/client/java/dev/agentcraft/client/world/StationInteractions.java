package dev.agentcraft.client.world;

import dev.agentcraft.block.entity.StationBlockEntity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Right-click on a station block, handled on the client (e.g. open the console screen on a terminal,
 * the library on an archive, the decision GUI on the podium). One handler per block; the click is
 * consumed client-side and not sent to the server. Sneak-right-click with an item still places
 * blocks normally. A handler registered with a {@link Filter} only takes the clicks the filter accepts;
 * the rest go to vanilla (e.g. lecterns outside AgentCraft buildings).
 *
 * <pre>
 * StationInteractions.onUse(ModBlocks.CONSOLE_TERMINAL, (player, pos, state, be) -> Minecraft.getInstance().gui.setScreen(new ConsoleScreen()));
 * </pre>
 */
public final class StationInteractions {
	@FunctionalInterface
	public interface Handler {
		void use(Player player, BlockPos pos, BlockState state, @Nullable StationBlockEntity be);
	}

	/** Which clicks on the block the handler takes (false = vanilla handles the click). */
	@FunctionalInterface
	public interface Filter {
		boolean accepts(Player player, BlockPos pos, BlockState state);
	}

	private record Entry(Filter filter, Handler handler) {
	}

	private static final Map<Block, Entry> HANDLERS = new ConcurrentHashMap<>();
	private static boolean registered;

	private StationInteractions() {
	}

	public static void onUse(Block block, Handler handler) {
		onUse(block, (player, pos, state) -> true, handler);
	}

	/** Like {@link #onUse(Block, Handler)}, for the clicks {@code filter} accepts only. */
	public static synchronized void onUse(Block block, Filter filter, Handler handler) {
		HANDLERS.put(block, new Entry(filter, handler));
		if (!registered) {
			registered = true;
			UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
				if (!level.isClientSide() || player.isSecondaryUseActive()) {
					return InteractionResult.PASS;
				}
				BlockPos pos = hit.getBlockPos();
				BlockState state = level.getBlockState(pos);
				Entry h = HANDLERS.get(state.getBlock());
				if (h == null || !h.filter().accepts(player, pos, state)) {
					return InteractionResult.PASS;
				}
				h.handler().use(player, pos, state, level.getBlockEntity(pos) instanceof StationBlockEntity be ? be : null);
				return InteractionResult.FAIL;
			});
		}
	}
}
