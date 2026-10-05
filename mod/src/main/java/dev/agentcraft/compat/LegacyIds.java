package dev.agentcraft.compat;

import dev.agentcraft.AgentCraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The mod id used to be {@code agentcraft} (upstream's); this fork is {@code agentcraft_worlds} (docs/FORK.md). Data
 * written before the rename still names blocks, block entities and the agent entity {@code agentcraft:<x>}: user and
 * generated blueprints in {@code <gameDir>/agentcraft/blueprints}, the world journal's before/after snapshots, the HQ
 * plan cache. Everything that reads such data runs it through here first, so {@code agentcraft:<x>} becomes
 * {@code agentcraft_worlds:<x>}. (Chunks of old worlds are covered by registry aliases, {@link LegacyAliases}.)
 * Pure: no registry access, so it can be unit-tested without a game.
 */
public final class LegacyIds {
	/** The namespace before the rename (also upstream AgentCraft's mod id). */
	public static final String LEGACY_NAMESPACE = "agentcraft";
	private static final String LEGACY_PREFIX = LEGACY_NAMESPACE + ":";
	private static final String PREFIX = AgentCraft.MOD_ID + ":";

	private LegacyIds() {
	}

	/** True when {@code id} is in the pre-rename namespace ({@code agentcraft:<x>}). */
	public static boolean isLegacy(@Nullable String id) {
		return id != null && id.startsWith(LEGACY_PREFIX) && id.length() > LEGACY_PREFIX.length();
	}

	/** {@code agentcraft:<x>} -> {@code agentcraft_worlds:<x>}; anything else (null too) unchanged. */
	public static @Nullable String id(@Nullable String id) {
		return isLegacy(id) ? PREFIX + id.substring(LEGACY_PREFIX.length()) : id;
	}

	/**
	 * Rewrites, in place, every legacy id in an NBT tree: the string values of {@code Name} (block states in palettes)
	 * and {@code id} (block entities, entities, items) at any depth. Covers structure templates (palette, palettes,
	 * blocks[].nbt, entities[].nbt), journal entries and plan caches. Returns how many values changed.
	 */
	public static int remap(@Nullable Tag tag) {
		if (tag instanceof CompoundTag c) {
			int n = 0;
			for (String key : java.util.List.copyOf(c.keySet())) {
				Tag v = c.get(key);
				if (v instanceof StringTag(String s)) {
					if ((key.equals("Name") || key.equals("id")) && isLegacy(s)) {
						c.putString(key, id(s));
						n++;
					}
				} else {
					n += remap(v);
				}
			}
			return n;
		}
		if (tag instanceof ListTag l) {
			int n = 0;
			for (int i = 0; i < l.size(); i++) {
				n += remap(l.get(i));
			}
			return n;
		}
		return 0;
	}

	/**
	 * A block state string ({@code Block{ns:path}[props]}) in the form template fingerprints hash: this mod's namespace
	 * spelled the pre-rename way, so a building pinned before the rename still matches its template
	 * ({@code TemplateGrid#fingerprint}, {@code Building.Pin}).
	 */
	public static String fingerprintForm(String state) {
		return state.replace(PREFIX, LEGACY_PREFIX);
	}
}
