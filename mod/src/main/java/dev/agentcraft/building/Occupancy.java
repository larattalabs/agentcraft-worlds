package dev.agentcraft.building;

import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Who is in the way of a placement (docs/BUILDINGS.md "Occupancy"): {@link Buildings#place} and the wizard's
 * ghost use the same rules, so the ghost says what the server will say.
 * <ul>
 * <li>a player whose box grown by one block touches the placement box: refused (they would be built into the
 * walls or sealed in);</li>
 * <li>tamed / owned animals (dogs, cats, horses, parrots...) and leashed ones: refused, named;</li>
 * <li>hostile mobs that would despawn anyway (no name, no picked-up loot, not persistent): removed with a note,
 * so a zombie wandering through does not block a build at night; named or persistent ones refuse (a mob that
 * picked up the player's gear carries it);</li>
 * <li>any other living entity (villagers, animals, armor stands) and anything else (item frames, minecarts, boats)
 * refuses, named;</li>
 * <li>dropped items and XP inside the box refuse ("pick them up first": they would be sealed into the walls);
 * stray projectiles (arrows in the ground) are removed.</li>
 * </ul>
 * Removal is the safer UX for hostiles: the player cannot shoo a creeper out of a box, and refusing until morning
 * is worse; everything that may matter to the player refuses instead.
 */
public final class Occupancy {
	/** What an entity in the box is. */
	public enum Kind {
		PLAYER, OWNED, HOSTILE, LIVING, ITEM, PROJECTILE, OTHER
	}

	/**
	 * One entity in the way.
	 *
	 * @param name what to call it ("you", "zombie", "Rex (wolf)")
	 * @param keep whether it matters to the player (a name, picked-up loot, persistence): never removed
	 */
	public record Found(Kind kind, String name, boolean keep) {
		/** Whether placing removes it instead of refusing. */
		public boolean removable() {
			return kind == Kind.PROJECTILE || kind == Kind.HOSTILE && !keep;
		}
	}

	private Occupancy() {
	}

	// ------------------------------------------------------------------ pure rules

	/** The refusals for what was found, grouped by name ("3 zombies"), in a stable order (players first). Empty = only removable ones. */
	public static List<String> refusals(List<Found> found) {
		List<String> out = new ArrayList<>();
		if (found.stream().anyMatch(f -> f.kind() == Kind.PLAYER)) {
			out.add(Buildings.PLAYER_IN_BOX);
		}
		Map<String, Integer> owned = count(found, f -> f.kind() == Kind.OWNED);
		if (!owned.isEmpty()) {
			out.add("pets in the box: " + join(owned) + " (lead them out)");
		}
		Map<String, Integer> others = count(found, f -> !f.removable() && f.kind() != Kind.PLAYER && f.kind() != Kind.OWNED && f.kind() != Kind.ITEM);
		if (!others.isEmpty()) {
			out.add("in the box: " + join(others) + " (move them out)");
		}
		Map<String, Integer> items = count(found, f -> f.kind() == Kind.ITEM);
		if (!items.isEmpty()) {
			out.add("dropped items in the box: " + join(items) + " (pick them up first)");
		}
		return out;
	}

	/** The note for what placing removes ("removes 2 zombies, 1 skeleton in the box"), or null when nothing. */
	public static String removalNote(List<Found> found) {
		Map<String, Integer> hostile = count(found, f -> f.kind() == Kind.HOSTILE && f.removable());
		if (hostile.isEmpty()) {
			return null;
		}
		return "removes " + join(hostile) + " in the box";
	}

	private static Map<String, Integer> count(List<Found> found, Predicate<Found> which) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Found f : found) {
			if (which.test(f)) {
				out.merge(f.name(), 1, Integer::sum);
			}
		}
		return out;
	}

	private static String join(Map<String, Integer> counts) {
		List<String> parts = new ArrayList<>();
		int shown = 0;
		for (var e : counts.entrySet()) {
			if (shown++ == 5) {
				parts.add("...");
				break;
			}
			parts.add(e.getValue() == 1 ? e.getKey() : e.getValue() + " × " + e.getKey());
		}
		return String.join(", ", parts);
	}

	// ------------------------------------------------------------------ entities

	/** The box as an entity AABB (cells fill x..x+1). */
	public static AABB aabb(Anchors.Bounds box) {
		return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
	}

	/**
	 * Everything in the way of a placement over {@code box} in {@code level}, client or server. {@code ignore}
	 * skips entities that are not really there (the client's AgentCraft NPCs).
	 */
	public static List<Found> scan(Level level, Anchors.Bounds box, Predicate<Entity> ignore) {
		AABB area = aabb(box);
		List<Found> out = new ArrayList<>();
		for (Player p : level.players()) {
			if (!p.isSpectator() && !ignore.test(p) && p.getBoundingBox().inflate(1).intersects(area)) {
				out.add(new Found(Kind.PLAYER, p.getPlainTextName(), true));
			}
		}
		for (Entity e : level.getEntities((Entity) null, area, e -> !(e instanceof Player) && e.isAlive() && !ignore.test(e))) {
			out.add(classify(e));
		}
		return out;
	}

	/** One entity's kind and name. */
	public static Found classify(Entity e) {
		String type = e.getType().getDescription().getString().toLowerCase(Locale.ROOT);
		String name = e.hasCustomName() ? e.getCustomName().getString() + " (" + type + ")" : type;
		boolean owned = e instanceof TamableAnimal t && t.isTame() || e instanceof OwnableEntity o && o.getOwnerReference() != null
			|| e instanceof Mob m && m.isLeashed();
		if (owned) {
			return new Found(Kind.OWNED, name, true);
		}
		if (e instanceof ItemEntity item) {
			String what = item.getItem().getHoverName().getString();
			return new Found(Kind.ITEM, item.getItem().getCount() > 1 ? what + " ×" + item.getItem().getCount() : what, true);
		}
		if (e instanceof ExperienceOrb) {
			return new Found(Kind.PROJECTILE, "experience", false); // harmless: XP is removed, it never matters
		}
		if (e instanceof Projectile) {
			return new Found(Kind.PROJECTILE, type, false);
		}
		if (e instanceof Enemy && e instanceof Mob m) {
			return new Found(Kind.HOSTILE, name, e.hasCustomName() || m.isPersistenceRequired());
		}
		if (e instanceof LivingEntity) {
			return new Found(Kind.LIVING, name, true);
		}
		return new Found(Kind.OTHER, name, true);
	}
}
