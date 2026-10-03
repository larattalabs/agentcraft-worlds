package dev.agentcraft.client.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.building.BlueprintTransform;
import dev.agentcraft.building.DesignSpec;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Plot-marking mode (docs/HUB.md "Generated buildings": the design form's "Fit a plot…"), built on the
 * placement mode's pieces: the same look ray ({@link BuildPlacement#spot}), keys (the keyboard mixin ->
 * {@link BuildingWizardFeature#onKey}), HUD ({@link PlotHud}) and level renderer ({@link GhostRenderer}).
 * Client thread only.
 *
 * <p>Look at the first corner, Enter; look at the second corner (a translucent rectangle follows the
 * look, with its size and the height limit), Enter. PgUp/PgDn change the height limit (Shift: by 4),
 * Backspace goes back a corner, Esc cancels. The plot's front is the side facing the player when the
 * second corner is confirmed (the entrance goes there); its ground is the lower corner's surface.
 */
public final class PlotMarker {
	/** What the renderer and HUD draw this frame. {@code first} null = still choosing the first corner. */
	record View(int @Nullable [] first, int[] hover, int height, String front, boolean pinned) {
		DesignSpec.Plot plot(String dimension) {
			int[] a = first != null ? first : hover;
			return DesignSpec.Plot.of(a[0], a[1], a[2], hover[0], hover[1], hover[2], height, front, dimension);
		}
	}

	private static boolean active;
	private static @Nullable Level level;
	private static String dimension = "";
	private static int @Nullable [] first;
	/** DevBridge: a pinned hover corner (preview without confirming). */
	private static int @Nullable [] pinned;
	private static @Nullable String frontOverride;
	private static int height = DesignSpec.DEFAULT_PLOT_HEIGHT;
	private static @Nullable Consumer<DesignSpec.Plot> onDone;
	private static @Nullable Runnable onCancel;
	private static @Nullable View view;
	private static DesignSpec.@Nullable Plot last;
	private static @Nullable String status;
	static long statusAt;

	private PlotMarker() {
	}

	public static boolean active() {
		return active;
	}

	/**
	 * Enters plot-marking mode (closes any screen, leaves placement mode). {@code done} gets the plot after
	 * the second corner; {@code cancel} runs on Esc. Throws IllegalArgumentException when not in a world.
	 */
	public static void start(int startHeight, Consumer<DesignSpec.Plot> done, Runnable cancel) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			throw new IllegalArgumentException("Not in a world");
		}
		BuildPlacement.cancel();
		reset();
		active = true;
		level = mc.level;
		dimension = mc.level.dimension().identifier().toString();
		height = DesignSpec.clampY(startHeight);
		onDone = done;
		onCancel = cancel;
		status = null;
		mc.gui.setScreen(null);
		update(mc);
	}

	private static void reset() {
		active = false;
		level = null;
		first = null;
		pinned = null;
		frontOverride = null;
		view = null;
		onDone = null;
		onCancel = null;
	}

	/** Leaves plot mode without calling back (placement starting, leaving the world). */
	static void cancelQuietly() {
		reset();
	}

	/** Esc: leaves plot mode and calls the cancel callback (back to the form). */
	public static void cancel() {
		if (!active) {
			return;
		}
		Runnable c = onCancel;
		reset();
		setStatus("Plot marking cancelled");
		if (c != null) {
			c.run();
		}
	}

	/** Backspace: forget the first corner (or cancel when there is none). */
	static void back() {
		if (!active) {
			return;
		}
		if (first == null) {
			cancel();
			return;
		}
		first = null;
		pinned = null;
		update(Minecraft.getInstance());
	}

	public static void adjustHeight(int delta) {
		if (active) {
			height = DesignSpec.clampY(height + delta);
			update(Minecraft.getInstance());
		}
	}

	public static void setHeight(int h) {
		if (active) {
			height = DesignSpec.clampY(h);
			update(Minecraft.getInstance());
		}
	}

	/** Enter: confirms the corner under the look (or the pinned one). */
	static void confirm() {
		Minecraft mc = Minecraft.getInstance();
		if (!active || mc.player == null) {
			return;
		}
		update(mc);
		View v = view;
		if (v == null) {
			return;
		}
		if (first == null) {
			first = v.hover().clone();
			pinned = null;
			update(mc);
			return;
		}
		DesignSpec.Plot p = v.plot(dimension);
		Consumer<DesignSpec.Plot> done = onDone;
		reset();
		last = p;
		setStatus("Plot " + p.dx() + " × " + p.dz() + ", height " + p.height() + ", entrance " + p.front());
		if (done != null) {
			done.accept(p);
		}
	}

	/**
	 * DevBridge: a corner at {@code x, y, z} ({@code y} = the surface: feet level on the ground). With
	 * {@code confirm} it counts as looked at + Enter; without, it is pinned as the hover corner (a
	 * shootable preview). {@code front} overrides the side the entrance faces (default: towards the player).
	 */
	public static void corner(int x, int y, int z, @Nullable String front, boolean confirm) {
		if (!active) {
			throw new IllegalStateException("not marking a plot");
		}
		if (front != null) {
			if (BlueprintTransform.directionIndex(front) < 0) {
				throw new IllegalArgumentException("front must be north, east, south or west");
			}
			frontOverride = front.toLowerCase(java.util.Locale.ROOT);
		}
		pinned = new int[] {x, y, z};
		update(Minecraft.getInstance());
		if (confirm) {
			confirm();
		}
	}

	/** Called every client tick. */
	static void tick(Minecraft mc) {
		if (!active) {
			return;
		}
		if (mc.player == null || mc.level == null || mc.level != level) {
			reset();
			setStatus("Plot marking cancelled (left the world or dimension)");
			return;
		}
		update(mc);
	}

	private static void update(Minecraft mc) {
		if (!active || mc.player == null) {
			view = null;
			return;
		}
		int[] hover = pinned != null ? pinned : lookCorner(mc, mc.player);
		String front = frontOverride != null ? frontOverride : BlueprintTransform.rotateDirection(facing(mc.player), 2);
		view = new View(first == null ? null : first.clone(), hover, height, front, pinned != null);
	}

	private static int[] lookCorner(Minecraft mc, Player p) {
		int[] s = BuildPlacement.spot(mc, p);
		return new int[] {s[0], s[1], s[2]};
	}

	private static String facing(Player p) {
		Direction d = p.getDirection();
		return d.getAxis().isHorizontal() ? d.getName() : "south";
	}

	// ------------------------------------------------------------------ reads

	static @Nullable View view() {
		return active ? view : null;
	}

	static String dimension() {
		return dimension;
	}

	/** The last plot marked this session (also after it went back to the form), or null. */
	public static DesignSpec.@Nullable Plot last() {
		return last;
	}

	static @Nullable String status() {
		return status;
	}

	private static void setStatus(String s) {
		status = s;
		statusAt = Util.getMillis();
	}

	public static JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("active", active);
		o.addProperty("status", status);
		View v = view();
		if (v != null) {
			o.addProperty("phase", v.first() == null ? "first_corner" : "second_corner");
			o.add("first", v.first() == null ? null : xyz(v.first()));
			o.add("hover", xyz(v.hover()));
			o.addProperty("pinned", v.pinned());
			o.addProperty("height", v.height());
			o.addProperty("front", v.front());
			if (v.first() != null) {
				o.add("plot", plotJson(v.plot(dimension)));
			}
		}
		o.add("last", last == null ? null : plotJson(last));
		return o;
	}

	public static JsonObject plotJson(DesignSpec.Plot p) {
		JsonObject o = new JsonObject();
		o.add("min", xyz(new int[] {p.minX(), p.y(), p.minZ()}));
		o.add("max", xyz(new int[] {p.maxX(), p.y(), p.maxZ()}));
		o.addProperty("x", p.dx());
		o.addProperty("z", p.dz());
		o.addProperty("height", p.height());
		o.addProperty("front", p.front());
		o.addProperty("dimension", p.dimension());
		int[] m = p.maxSize();
		JsonObject ms = new JsonObject();
		ms.addProperty("x", m[0]);
		ms.addProperty("y", m[1]);
		ms.addProperty("z", m[2]);
		o.add("maxSize", ms);
		o.addProperty("clamped", p.tooSmall() || p.tooLarge() || p.height() != m[1]);
		return o;
	}

	private static JsonArray xyz(int[] a) {
		JsonArray j = new JsonArray();
		j.add(a[0]);
		j.add(a[1]);
		j.add(a[2]);
		return j;
	}
}
