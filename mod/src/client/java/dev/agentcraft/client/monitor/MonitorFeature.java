package dev.agentcraft.client.monitor;

import dev.agentcraft.ui.Guard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.block.entity.MonitorBlockEntity;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * Monitors: each desk monitor streams its agent's live log ({@code agent.log}) on the screen, see
 * {@link MonitorRenderer}. Binding = agent id; an empty binding resolves to the agent whose
 * {@code monitor_<id>} anchor lies on the panel, otherwise the screen shows the team activity feed
 * (binding {@code feed} does that explicitly).
 *
 * <p>Change tracking: a Foreman listener bumps a per-agent log / agent sequence number, and each
 * screen re-lays itself out only when the numbers it was built from change.
 *
 * <p>Dev: {@code dev.displays {look?: paper|dark|split, monitors?: true}} switches the screen look
 * live (split = alternate per monitor, for side-by-side comparison) and lists the laid-out screens.
 */
public final class MonitorFeature {
	public enum Look {
		PAPER, DARK, SPLIT
	}

	private static Look look = Look.DARK;
	private static final Map<String, Long> LOG_SEQ = new HashMap<>();
	private static final Map<String, Long> AGENT_SEQ = new HashMap<>();
	private static long epoch;
	private static final Map<BlockPos, MonitorScreen> SCREENS = new HashMap<>();
	private static final Map<BlockPos, String> RESOLVED = new HashMap<>();
	private static long resolvedLayoutRevision = -1;
	private static String resolvedLayoutName = "";

	private MonitorFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.MONITOR, ctx -> new MonitorRenderer());
		// W4: a monitor's right-click opens its agent's view in the hub Inbox (card summary + full log); the feed
		// monitor opens the Inbox itself
		dev.agentcraft.client.world.StationInteractions.onUse(dev.agentcraft.block.ModBlocks.MONITOR, (player, pos, state, be) -> {
			String agent = agentAt(player.level(), pos);
			if (agent == null || agent.equals("feed")) {
				dev.agentcraft.client.hub.Inbox.open(null);
			} else {
				dev.agentcraft.client.hub.Inbox.openAgent(agent);
			}
		});
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onSnapshot(ForemanState st) {
				epoch++;
			}

			@Override
			public void onLog(String agentId, List<LogEntry> entries) {
				LOG_SEQ.merge(agentId, 1L, Long::sum);
			}

			@Override
			public void onAgent(@Nullable Agent previous, Agent agent) {
				AGENT_SEQ.merge(agent.id(), 1L, Long::sum);
				AGENT_SEQ.merge("feed", 1L, Long::sum);
			}

			@Override
			public void onFeed(FeedItem item) {
				LOG_SEQ.merge("feed", 1L, Long::sum);
			}

			@Override
			public void onGoal(@Nullable Goal previous, Goal goal) {
				AGENT_SEQ.merge("feed", 1L, Long::sum);
			}
		});
		// forget screens whose panel has not been drawn for a while (broken, unloaded, re-shaped)
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("monitor.sweep", () -> {
			if (mc.level == null || (mc.level.getGameTime() % 200) != 0) {
				return;
			}
			long now = System.nanoTime();
			Iterator<MonitorScreen> it = SCREENS.values().iterator();
			while (it.hasNext()) {
				if (now - it.next().lastUsedNanos > 30_000_000_000L) {
					it.remove();
				}
			}
		}));
		DevBridge.register("dev.displays", 10_000, "{look?: paper|dark|split, reset?: bool} -> monitor look, laid-out monitor screens, "
			+ "display CPU cost per frame since the last reset", (req, mc) -> {
			Fields f = Fields.of(req);
			String l = f.optStr("look", null);
			boolean reset = f.optBool("reset", false);
			return DevBridge.onClient(mc, () -> {
				if (l != null) {
					try {
						look = Look.valueOf(l.toUpperCase(Locale.ROOT));
					} catch (IllegalArgumentException e) {
						throw new DevBridge.DevException("look must be paper, dark or split (got " + l + ")");
					}
				}
				JsonObject o = new JsonObject();
				o.addProperty("look", look.name().toLowerCase(Locale.ROOT));
				JsonArray arr = new JsonArray();
				for (MonitorScreen m : SCREENS.values()) {
					JsonObject j = new JsonObject();
					j.addProperty("pos", m.origin.getX() + " " + m.origin.getY() + " " + m.origin.getZ());
					j.addProperty("agent", m.agentId);
					j.addProperty("mode", m.mode.name().toLowerCase(Locale.ROOT));
					j.addProperty("style", m.style == null ? "" : m.style.id());
					j.addProperty("size", m.panelW + "x" + m.panelH);
					j.addProperty("ppb", m.ppb);
					j.addProperty("rows", m.rows.size());
					j.addProperty("ageMs", (System.nanoTime() - m.lastUsedNanos) / 1_000_000L);
					arr.add(j);
				}
				o.add("monitors", arr);
				o.add("stats", DisplayStats.json());
				if (reset) {
					DisplayStats.reset();
				}
				return o;
			});
		});
	}

	static MonitorScreen screen(BlockPos origin) {
		return SCREENS.computeIfAbsent(origin.immutable(), MonitorScreen::new);
	}

	/** Log change counter for an agent (or "feed"): bumps on every append and on every snapshot. */
	public static long logSeq(String agentId) {
		return LOG_SEQ.getOrDefault(agentId, 0L) + (epoch << 32);
	}

	static long agentSeq(String agentId) {
		return AGENT_SEQ.getOrDefault(agentId, 0L) + (epoch << 32);
	}

	static ScreenStyle styleFor(BlockPos origin) {
		return switch (look) {
			case PAPER -> ScreenStyle.PAPER;
			case DARK -> ScreenStyle.DARK;
			case SPLIT -> ((origin.getX() + origin.getZ()) & 2) == 0 ? ScreenStyle.PAPER : ScreenStyle.DARK;
		};
	}

	public static Look look() {
		return look;
	}

	/**
	 * The agent the monitor panel holding {@code pos} shows ("feed" = the team feed), null when there is no monitor there.
	 * Client thread.
	 */
	public static @Nullable String agentAt(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
		if (!(st.getBlock() instanceof dev.agentcraft.block.PanelBlock)) {
			return null;
		}
		net.minecraft.core.BlockPos origin = dev.agentcraft.block.PanelBlock.origin(level, pos, st);
		net.minecraft.world.level.block.state.BlockState os = level.getBlockState(origin);
		if (!(level.getBlockEntity(origin) instanceof MonitorBlockEntity be)) {
			return null;
		}
		int[] wh = dev.agentcraft.block.PanelBlock.extent(level, origin, os);
		Direction facing = os.hasProperty(dev.agentcraft.block.PanelBlock.FACING) ? os.getValue(dev.agentcraft.block.PanelBlock.FACING) : Direction.NORTH;
		return resolveAgent(be, be.binding(), facing, wh[0], wh[1]);
	}

	/**
	 * The agent a monitor shows: its binding, else the agent whose {@code monitor_<id>} anchor lies
	 * on this panel, else "feed" (the team activity feed).
	 */
	static String resolveAgent(MonitorBlockEntity be, String binding, Direction facing, int w, int h) {
		if (!binding.isEmpty()) {
			return binding;
		}
		Anchors.Layout layout = Anchors.current();
		long revision = Buildings.regionsSignature() * 31 + layout.revision();
		if (revision != resolvedLayoutRevision || !layout.name().equals(resolvedLayoutName)) {
			RESOLVED.clear();
			resolvedLayoutRevision = revision;
			resolvedLayoutName = layout.name();
		}
		BlockPos origin = be.getBlockPos();
		String cached = RESOLVED.get(origin);
		if (cached != null) {
			return cached;
		}
		String found = "feed";
		Direction right = facing.getCounterClockWise();
		// every building's monitor_<id> anchors (the HQ studio / home first): an anchor names one panel
		search:
		for (Anchors.Layout l : Buildings.layouts()) {
			for (Map.Entry<String, Anchor> e : l.anchors().entrySet()) {
				if (!e.getKey().startsWith(AnchorNames.MONITOR_PREFIX)) {
					continue;
				}
				Anchor a = e.getValue();
				// surface-centre anchors may sit on the block boundary: also try a point nudged into the panel
				BlockPos p = BlockPos.containing(a.x(), a.y(), a.z());
				BlockPos q = BlockPos.containing(a.x() - facing.getStepX() * 0.3, a.y(), a.z() - facing.getStepZ() * 0.3);
				if (onPanel(origin, right, w, h, p) || onPanel(origin, right, w, h, q)) {
					found = e.getKey().substring(AnchorNames.MONITOR_PREFIX.length());
					break search;
				}
			}
		}
		RESOLVED.put(origin.immutable(), found);
		return found;
	}

	private static boolean onPanel(BlockPos origin, Direction right, int w, int h, BlockPos p) {
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				if (origin.relative(right, i).above(j).equals(p)) {
					return true;
				}
			}
		}
		return false;
	}
}
