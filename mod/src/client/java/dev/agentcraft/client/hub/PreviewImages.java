package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Blueprints;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Rendered blueprint previews ({@code <id>.preview-iso.png}, {@code -top}, {@code -front},
 * {@code -cutaway}, written by the offline renderer next to a blueprint, docs/HUB.md) for the hub's
 * blueprint browser. Looked for in the user folder ({@code <gameDir>/agentcraft/blueprints/}) first,
 * then, for bundled blueprints, in the mod's resources ({@code data/<ns>/blueprints/}). Any of them may
 * be missing (the renderer is optional): the browser then shows only the built-in top-down plan.
 *
 * <p>Discovery is cached per blueprint for {@value #RESCAN_MS} ms. Images are read off the client thread
 * and turned into {@link DynamicTexture}s on it, lazily (only the view being shown), at most
 * {@value #MAX_SIDE} px a side, and evicted oldest-first beyond {@value #MAX_TEXTURES} textures or
 * {@value #MAX_BYTES} bytes; {@link #releaseAll} frees everything (the hub calls it when it closes).
 * A file is keyed by path + modification time + size, so a regenerated preview is picked up. Client
 * thread only.
 */
final class PreviewImages {
	/** View kinds in display order. */
	static final List<String> KINDS = List.of("iso", "top", "front", "cutaway");
	static final int MAX_SIDE = 2048;
	static final int MAX_TEXTURES = 8;
	static final long MAX_BYTES = 48L << 20;
	static final long RESCAN_MS = 2000;

	enum State {
		LOADING, READY, FAILED
	}

	/** One preview file found for a blueprint. {@code key} identifies its content version. */
	record Found(String kind, String where, @Nullable Path file, @Nullable String resource, String key) {
	}

	private record Discovery(List<Found> found, List<String> tried, long at) {
	}

	private static final class Slot {
		final String key;
		State state = State.LOADING;
		@Nullable Identifier texture;
		int width;
		int height;
		@Nullable String error;

		Slot(String key) {
			this.key = key;
		}

		long bytes() {
			return (long) width * height * 4;
		}
	}

	private static final Map<String, Discovery> DISCOVERED = new HashMap<>();
	/** Loaded (or loading) images by key, oldest first. */
	private static final LinkedHashMap<String, Slot> SLOTS = new LinkedHashMap<>(16, 0.75f, true);
	private static int counter;

	private PreviewImages() {
	}

	/** The preview files of a blueprint (cached for {@value #RESCAN_MS} ms). */
	static List<Found> find(String blueprintId) {
		return discover(blueprintId).found();
	}

	private static Discovery discover(String blueprintId) {
		long now = Util.getMillis();
		Discovery d = DISCOVERED.get(blueprintId);
		if (d != null && now - d.at() < RESCAN_MS) {
			return d;
		}
		List<Found> found = new ArrayList<>();
		List<String> tried = new ArrayList<>();
		Blueprints.Entry e = Blueprints.entry(blueprintId);
		String ns = AgentCraft.MOD_ID;
		boolean bundled = e == null || e.source().startsWith("bundled");
		if (e != null && bundled) {
			// "bundled agentcraft_worlds:blueprints/studio.blueprint.json"
			String src = e.source().substring("bundled".length()).strip();
			int colon = src.indexOf(':');
			if (colon > 0) {
				ns = src.substring(0, colon);
			}
		}
		for (String kind : KINDS) {
			String name = blueprintId + ".preview-" + kind + ".png";
			Path user = Blueprints.userDir().resolve(name);
			tried.add(user.toString());
			if (Files.isRegularFile(user)) {
				try {
					found.add(new Found(kind, "user", user, null, user + "@" + Files.getLastModifiedTime(user).toMillis() + "#" + Files.size(user)));
					continue;
				} catch (IOException ex) {
					// vanished between the checks: try the bundled one
				}
			}
			if (bundled) {
				String res = "/data/" + ns + "/" + Blueprints.RESOURCE_DIR + "/" + name;
				tried.add("resource " + res);
				if (PreviewImages.class.getResource(res) != null) {
					found.add(new Found(kind, "bundled", null, res, "res:" + res));
				}
			}
		}
		d = new Discovery(List.copyOf(found), List.copyOf(tried), now);
		DISCOVERED.put(blueprintId, d);
		return d;
	}

	/** The state of an image, starting its load when it is not cached yet. */
	static State request(Found f) {
		Slot s = SLOTS.get(f.key());
		if (s != null) {
			return s.state;
		}
		Slot slot = new Slot(f.key());
		SLOTS.put(f.key(), slot);
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture.supplyAsync(() -> {
			try {
				if (f.file() != null) {
					return Files.readAllBytes(f.file());
				}
				try (InputStream in = PreviewImages.class.getResourceAsStream(f.resource())) {
					if (in == null) {
						throw new IOException("resource missing");
					}
					return in.readAllBytes();
				}
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}, Util.ioPool()).whenComplete((bytes, err) -> mc.execute(() -> finish(slot, bytes, err)));
		return slot.state;
	}

	private static void finish(Slot slot, byte @Nullable [] bytes, @Nullable Throwable err) {
		if (SLOTS.get(slot.key) != slot) {
			return; // released meanwhile
		}
		if (err != null || bytes == null) {
			fail(slot, err == null ? "no data" : String.valueOf(err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
			return;
		}
		NativeImage img;
		try {
			img = NativeImage.read(bytes);
		} catch (IOException | RuntimeException e) {
			fail(slot, "not a PNG (" + e.getMessage() + ")");
			return;
		}
		if (img.getWidth() > MAX_SIDE || img.getHeight() > MAX_SIDE) {
			fail(slot, img.getWidth() + "x" + img.getHeight() + " is larger than " + MAX_SIDE + " px a side");
			img.close();
			return;
		}
		Identifier id = AgentCraft.id("hub_preview/" + (counter++));
		slot.width = img.getWidth();
		slot.height = img.getHeight();
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "agentcraft hub preview " + slot.key, img));
		slot.texture = id;
		slot.state = State.READY;
		evict();
	}

	private static void fail(Slot slot, String why) {
		slot.state = State.FAILED;
		slot.error = why;
		AgentCraft.LOGGER.warn("Hub: blueprint preview {} unusable: {}", slot.key, why);
	}

	private static void evict() {
		long bytes = 0;
		int textures = 0;
		for (Slot s : SLOTS.values()) {
			if (s.texture != null) {
				bytes += s.bytes();
				textures++;
			}
		}
		Iterator<Slot> it = SLOTS.values().iterator();
		while ((textures > MAX_TEXTURES || bytes > MAX_BYTES) && it.hasNext()) {
			Slot s = it.next();
			if (s.texture == null) {
				continue;
			}
			bytes -= s.bytes();
			textures--;
			Minecraft.getInstance().getTextureManager().release(s.texture);
			it.remove();
		}
	}

	/**
	 * Draws a ready image fitted into {@code w x h} (aspect kept, centred). Returns false when it is not
	 * ready (loading or failed); the caller draws a placeholder.
	 */
	static boolean draw(GuiGraphicsExtractor g, Found f, int x, int y, int w, int h) {
		if (request(f) != State.READY) {
			return false;
		}
		Slot s = SLOTS.get(f.key());
		if (s == null || s.texture == null) {
			return false;
		}
		double scale = Math.min((double) w / s.width, (double) h / s.height);
		int dw = Math.max(1, (int) Math.floor(s.width * scale));
		int dh = Math.max(1, (int) Math.floor(s.height * scale));
		g.blit(RenderPipelines.GUI_TEXTURED, s.texture, x + (w - dw) / 2, y + (h - dh) / 2, 0, 0, dw, dh, s.width, s.height, s.width, s.height);
		return true;
	}

	static @Nullable String error(Found f) {
		Slot s = SLOTS.get(f.key());
		return s == null ? null : s.error;
	}

	/** Frees every texture (the hub closed) and forgets discoveries. */
	static void releaseAll() {
		for (Slot s : SLOTS.values()) {
			if (s.texture != null) {
				Minecraft.getInstance().getTextureManager().release(s.texture);
			}
		}
		SLOTS.clear();
		DISCOVERED.clear();
	}

	/** DevBridge: what was looked for and found for {@code blueprintId}, and each image's load state. */
	static JsonObject describe(String blueprintId) {
		Discovery d = discover(blueprintId);
		JsonObject o = new JsonObject();
		JsonArray tried = new JsonArray();
		d.tried().forEach(tried::add);
		o.add("tried", tried);
		JsonArray found = new JsonArray();
		for (Found f : d.found()) {
			JsonObject j = new JsonObject();
			j.addProperty("kind", f.kind());
			j.addProperty("where", f.where());
			j.addProperty("path", f.file() != null ? f.file().toString() : f.resource());
			Slot s = SLOTS.get(f.key());
			j.addProperty("state", s == null ? "not loaded" : s.state.name().toLowerCase(java.util.Locale.ROOT));
			if (s != null && s.state == State.READY) {
				j.addProperty("width", s.width);
				j.addProperty("height", s.height);
			}
			j.addProperty("error", s == null ? null : s.error);
			found.add(j);
		}
		o.add("found", found);
		return o;
	}

	/** DevBridge: textures held and their bytes. */
	static JsonObject stats() {
		JsonObject o = new JsonObject();
		int textures = 0;
		long bytes = 0;
		for (Slot s : SLOTS.values()) {
			if (s.texture != null) {
				textures++;
				bytes += s.bytes();
			}
		}
		o.addProperty("textures", textures);
		o.addProperty("bytes", bytes);
		o.addProperty("maxTextures", MAX_TEXTURES);
		o.addProperty("maxBytes", MAX_BYTES);
		return o;
	}
}
