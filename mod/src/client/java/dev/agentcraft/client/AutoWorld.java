package dev.agentcraft.client;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.world.AutoWorldSpec;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import dev.agentcraft.client.mixin.BackupConfirmScreenAccessor;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Boots straight into the "AgentCraft HQ" world without any clicks: the first time the title
 * screen appears, the world is loaded if it exists, or created (creative, peaceful, superflat
 * grass meadow with no structures/decoration) if it does not. Disable with AGENTCRAFT_AUTOWORLD=0.
 * AGENTCRAFT_AUTOWORLD_NAME / _PRESET (flat | normal) / _SEED pick another world ({@link AutoWorldSpec});
 * only the "AgentCraft HQ" name gets the HQ rules and studio.
 */
public final class AutoWorld {
	private static boolean attempted;
	/** True while AutoWorld itself is opening its world (so its confirm screens may be auto-answered). */
	private static boolean openingHq;

	private AutoWorld() {
	}

	public static void init() {
		if (!ClientEnv.AUTO_WORLD) {
			AgentCraft.LOGGER.info("AutoWorld disabled (AGENTCRAFT_AUTOWORLD=0)");
			return;
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> openingHq = false);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (openingHq && screen instanceof BackupConfirmScreen backup) {
				// Registry content changed since the HQ world was saved (a block/entity was renamed or removed
				// while developing). The HQ world is generated, so take Fabric's backup and load it instead of
				// waiting forever on "Missing content detected!" in an unattended run.
				openingHq = false;
				AgentCraft.LOGGER.warn("AutoWorld: '{}' needs confirmation ({}); making a backup and loading it",
					spec == null ? "?" : spec.name(), screen.getTitle().getString());
				client.execute(() -> ((BackupConfirmScreenAccessor) backup).agentcraft$onProceed().proceed(true, false));
				return;
			}
			if (attempted) {
				return;
			}
			if (screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen) {
				attempted = true;
				// Never switch screens from inside another screen's init.
				client.execute(() -> openOrCreate(client));
			}
		});
	}

	private static AutoWorldSpec spec;

	public static void openOrCreate(Minecraft mc) {
		try {
			spec = AutoWorldSpec.from(ClientEnv::raw);
			String name = spec.name();
			if (mc.getLevelSource().levelExists(name)) {
				AgentCraft.LOGGER.info("AutoWorld: loading existing world '{}'", name);
				openingHq = true;
				mc.createWorldOpenFlows().openWorld(name, () -> mc.gui.setScreen(new TitleScreen()));
			} else {
				AgentCraft.LOGGER.info("AutoWorld: creating world '{}' ({}, seed {})", name, spec.preset(), spec.seed());
				LevelSettings settings = new LevelSettings(
					name,
					GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
					true,
					WorldDataConfiguration.DEFAULT
				);
				WorldOptions options = new WorldOptions(spec.seed(), false, false);
				mc.createWorldOpenFlows().createFreshLevel(name, settings, options,
					spec.preset() == AutoWorldSpec.Preset.FLAT ? AutoWorld::meadowDimensions : WorldPresets::createNormalWorldDimensions,
					new TitleScreen());
			}
		} catch (Exception e) {
			AgentCraft.LOGGER.error("AutoWorld failed; staying on the title screen", e);
			mc.gui.setScreen(new TitleScreen());
		}
	}

	/** Normal dimensions, with the overworld replaced by a flat plains meadow (grass top at y=64). */
	private static WorldDimensions meadowDimensions(HolderLookup.Provider registries) {
		Holder<Biome> plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		FlatLevelGeneratorSettings base = new FlatLevelGeneratorSettings(Optional.of(HolderSet.empty()), plains, List.of());
		// y=-64 bedrock, stone up to 60, dirt 61..63, grass 64.
		List<FlatLayerInfo> layers = List.of(
			new FlatLayerInfo(1, Blocks.BEDROCK),
			new FlatLayerInfo(124, Blocks.STONE),
			new FlatLayerInfo(3, Blocks.DIRT),
			new FlatLayerInfo(1, Blocks.GRASS_BLOCK)
		);
		FlatLevelGeneratorSettings flat = base.withBiomeAndLayers(layers, Optional.of(HolderSet.empty()), plains);
		return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
	}
}
