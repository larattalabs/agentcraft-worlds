package dev.agentcraft.client.library;

import com.google.gson.JsonObject;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.MemoryEntry;
import dev.agentcraft.client.world.StationInteractions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.ui.UiRules;
import dev.agentcraft.world.HqWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Memory library (Phase 3, library specialist).
 *
 * <ul>
 *   <li>{@link LibraryScreen}: two-pane reader of {@code Foreman.state().memory()} (scope tabs, the
 *       lead's plan pinned, markdown bodies, live task dots in the plan).</li>
 *   <li>{@link MemoryArchiveRenderer}: shelf labels (scope plate with the note count, one note
 *       title per further block of a row).</li>
 *   <li>Right-click: memory archive opens its scope (binding; empty = all) at the note its label
 *       shows; memory catalog and lecterns <b>inside a recorded building</b> (or the dev HQ) without a book open
 *       the library at the plan; every other lectern is a vanilla lectern (B4).</li>
 *   <li>QA: {@code dev.screen {open:"library"}}, {@code dev.library {scope?, memoryId?, scroll?}}
 *       (opens / drives it, returns its state), {@code dev.library.state}.</li>
 * </ul>
 */
public final class LibraryFeature {
	private LibraryFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.MEMORY_ARCHIVE, ctx -> new MemoryArchiveRenderer());
		MemoryIndex.init();
		DevBridge.registerScreen("library", mc -> new LibraryScreen(null, null));
		StationInteractions.onUse(ModBlocks.MEMORY_ARCHIVE, (player, pos, state, be) -> {
			String scope = be == null ? "" : be.binding();
			int k = MemoryArchiveRenderer.rowIndex(player.level(), pos, state, scope);
			MemoryEntry e = MemoryArchiveRenderer.entryAt(k, scope);
			open(scope, e == null ? null : e.id());
		});
		StationInteractions.onUse(ModBlocks.MEMORY_CATALOG, (player, pos, state, be) -> open(null, null));
		// only lecterns inside a recorded building (or the dev HQ), with no book on them: every other lectern is vanilla
		StationInteractions.onUse(Blocks.LECTERN, LibraryFeature::libraryLectern, (player, pos, state, be) -> {
			MemoryEntry plan = MemoryIndex.plan();
			open(null, plan == null ? null : plan.id());
		});
		registerDev();
	}

	/** Whether a click on this lectern opens the library (UiRules#lecternOpensLibrary). */
	static boolean libraryLectern(Player player, BlockPos pos, BlockState state) {
		String dim = player.level().dimension().identifier().toString();
		boolean inside = UiRules.containing(Buildings.all(), Building::box, Building::dimension, dim, pos.getX(), pos.getY(), pos.getZ()) != null;
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		boolean hq = server != null && HqWorld.isHq(server);
		boolean hasBook = state.hasProperty(LecternBlock.HAS_BOOK) && state.getValue(LecternBlock.HAS_BOOK);
		boolean holdingBook = player.getMainHandItem().is(ItemTags.LECTERN_BOOKS) || player.getOffhandItem().is(ItemTags.LECTERN_BOOKS);
		return UiRules.lecternOpensLibrary(inside, hq, hasBook, holdingBook);
	}

	/** Open the library; {@code scope} null/empty = all notes, {@code memoryId} null = the plan / newest. */
	public static void open(@Nullable String scope, @Nullable String memoryId) {
		Minecraft.getInstance().gui.setScreen(new LibraryScreen(scope, memoryId));
	}

	private static void registerDev() {
		DevBridge.register("dev.library.lectern", 5_000,
			"{x,y,z} -> whether a right-click on the lectern there opens the library (inside a building / dev HQ, no book) or is left to vanilla",
			(req, mc) -> {
				Fields f = Fields.of(req);
				BlockPos pos = new BlockPos((int) f.integer("x", -30_000_000, 30_000_000), (int) f.integer("y", -2048, 2048),
					(int) f.integer("z", -30_000_000, 30_000_000));
				return DevBridge.onClient(mc, () -> {
					if (mc.player == null || mc.level == null) {
						throw new DevBridge.DevException("no world");
					}
					BlockState st = mc.level.getBlockState(pos);
					JsonObject o = new JsonObject();
					o.addProperty("block", st.getBlock().getDescriptionId());
					o.addProperty("lectern", st.is(Blocks.LECTERN));
					String dim = mc.level.dimension().identifier().toString();
					Building b = UiRules.containing(Buildings.all(), Building::box, Building::dimension, dim, pos.getX(), pos.getY(), pos.getZ());
					o.addProperty("building", b == null ? null : b.id());
					o.addProperty("opensLibrary", st.is(Blocks.LECTERN) && libraryLectern(mc.player, pos, st));
					return o;
				});
			});
		DevBridge.register("dev.library", 10_000,
			"{scope?: all|shared|<agentId>, memoryId?, scroll?, open?} -> opens (or drives the open) memory library and returns its state",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String scope = f.has("scope") ? f.str("scope") : null;
				String memoryId = f.has("memoryId") ? f.str("memoryId") : null;
				Double scroll = f.has("scroll") ? f.optNum("scroll", 0, 0, 10_000_000) : null;
				boolean forceOpen = f.optBool("open", false);
				return DevBridge.onClient(mc, () -> {
					Screen cur = mc.gui.screen();
					LibraryScreen screen;
					if (forceOpen || !(cur instanceof LibraryScreen)) {
						screen = new LibraryScreen(scope == null || scope.equals("all") ? null : scope, memoryId);
						mc.gui.setScreen(screen);
					} else {
						screen = (LibraryScreen) cur;
					}
					screen.applyDev(scope, memoryId, scroll == null ? null : scroll.floatValue());
					return screen.stateJson();
				});
			});
		DevBridge.register("dev.library.state", 5_000, "{} -> state of the open memory library ({open:false} when none)",
			(req, mc) -> DevBridge.onClient(mc, () -> {
				if (mc.gui.screen() instanceof LibraryScreen l) {
					JsonObject o = l.stateJson();
					o.addProperty("open", true);
					return o;
				}
				JsonObject o = new JsonObject();
				o.addProperty("open", false);
				o.addProperty("notes", MemoryIndex.count(MemoryIndex.ALL));
				return o;
			}));
	}
}
