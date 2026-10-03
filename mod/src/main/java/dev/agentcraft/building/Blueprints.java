package dev.agentcraft.building;

import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

/**
 * The blueprint registry (docs/BUILDINGS.md "Blueprints"): bundled sidecars
 * {@code data/<ns>/blueprints/<id>.blueprint.json} with their template {@code data/<ns>/structure/<id>.nbt}
 * (through the server's resource manager and structure template manager, so data packs can add more),
 * then the user's folder {@code <gameDir>/agentcraft/blueprints/} ({@code <id>.blueprint.json} +
 * {@code <id>.nbt}); a user blueprint overrides a bundled one with the same id.
 *
 * <p>Loaded when a server starts, on a data pack reload and on {@code /agentcraft blueprints reload};
 * cleared when it stops. Broken blueprints are logged and skipped. Reads are safe from any thread.
 */
public final class Blueprints {
	public static final String RESOURCE_DIR = "blueprints";
	public static final String SIDECAR_SUFFIX = ".blueprint.json";

	/** A loaded blueprint: sidecar + template + where it came from ({@code bundled} / the user file). */
	public record Entry(Blueprint blueprint, StructureTemplate template, String source) {
	}

	private static volatile Map<String, Entry> entries = Map.of();
	private static volatile List<String> lastProblems = List.of();

	private Blueprints() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(Blueprints::reload);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, rm, ok) -> {
			if (ok) {
				reload(server);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> entries = Map.of());
	}

	public static @Nullable Blueprint get(String id) {
		Entry e = entries.get(id);
		return e == null ? null : e.blueprint();
	}

	public static @Nullable Entry entry(String id) {
		return entries.get(id);
	}

	public static @Nullable StructureTemplate template(String id) {
		Entry e = entries.get(id);
		return e == null ? null : e.template();
	}

	/** All blueprints, sorted by id. */
	public static Collection<Blueprint> all() {
		return entries.values().stream().map(Entry::blueprint).toList();
	}

	public static Collection<String> ids() {
		return entries.keySet();
	}

	/** Problems found by the last load (one line each), for the reload command's feedback. */
	public static List<String> lastProblems() {
		return lastProblems;
	}

	/** {@code <gameDir>/agentcraft/blueprints}. */
	public static Path userDir() {
		return FabricLoader.getInstance().getGameDir().resolve("agentcraft").resolve(RESOURCE_DIR);
	}

	/** Reloads everything. Call on the server thread. */
	public static synchronized void reload(MinecraftServer server) {
		Map<String, Entry> map = new TreeMap<>();
		List<String> problems = new ArrayList<>();
		loadBundled(server, map, problems);
		loadUser(server, map, problems);
		entries = Collections.unmodifiableMap(map);
		lastProblems = List.copyOf(problems);
		AgentCraft.LOGGER.info("Blueprints: {} loaded {}{}", map.size(), map.keySet(), problems.isEmpty() ? "" : ", " + problems.size() + " problem(s)");
	}

	private static void loadBundled(MinecraftServer server, Map<String, Entry> map, List<String> problems) {
		Map<Identifier, Resource> found = server.getResourceManager().listResources(RESOURCE_DIR, id -> id.getPath().endsWith(SIDECAR_SUFFIX));
		for (var e : found.entrySet()) {
			Identifier file = e.getKey();
			String where = file.toString();
			try (Reader r = new InputStreamReader(e.getValue().open(), StandardCharsets.UTF_8)) {
				Blueprint bp = Blueprint.fromJson(JsonParser.parseReader(r).getAsJsonObject());
				String fileId = file.getPath().substring(RESOURCE_DIR.length() + 1, file.getPath().length() - SIDECAR_SUFFIX.length());
				if (!fileId.equals(bp.id())) {
					throw new IllegalArgumentException("file name " + fileId + " does not match id " + bp.id());
				}
				Identifier templateId = Identifier.fromNamespaceAndPath(file.getNamespace(), bp.id());
				// the manager caches lookups (misses too) for the whole session; remove() only drops that cache entry
				server.getStructureTemplateManager().remove(templateId);
				Optional<StructureTemplate> t = server.getStructureTemplateManager().get(templateId);
				if (t.isEmpty()) {
					throw new IllegalArgumentException("no template data/" + file.getNamespace() + "/structure/" + bp.id() + ".nbt");
				}
				accept(map, problems, bp, t.get(), "bundled " + where);
			} catch (Exception ex) {
				problem(problems, where, ex);
			}
		}
	}

	private static void loadUser(MinecraftServer server, Map<String, Entry> map, List<String> problems) {
		Path dir = userDir();
		if (!Files.isDirectory(dir)) {
			return;
		}
		List<Path> sidecars;
		try (Stream<Path> s = Files.list(dir)) {
			sidecars = s.filter(p -> p.getFileName().toString().endsWith(SIDECAR_SUFFIX)).sorted().toList();
		} catch (IOException e) {
			problem(problems, dir.toString(), e);
			return;
		}
		for (Path p : sidecars) {
			try {
				Blueprint bp = Blueprint.fromJson(JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject());
				String name = p.getFileName().toString();
				String fileId = name.substring(0, name.length() - SIDECAR_SUFFIX.length());
				if (!fileId.equals(bp.id())) {
					throw new IllegalArgumentException("file name " + fileId + " does not match id " + bp.id());
				}
				Path nbt = dir.resolve(bp.id() + ".nbt");
				if (!Files.exists(nbt)) {
					throw new IllegalArgumentException("no template " + nbt.getFileName());
				}
				accept(map, problems, bp, readTemplate(server, nbt), "user " + p);
			} catch (Exception ex) {
				problem(problems, p.toString(), ex);
			}
		}
	}

	/** Reads a gzipped structure template file, upgrading it to the running game version. */
	public static StructureTemplate readTemplate(MinecraftServer server, Path nbt) throws IOException {
		CompoundTag tag = NbtIo.readCompressed(nbt, NbtAccounter.unlimitedHeap());
		int version = NbtUtils.getDataVersion(tag, 500);
		tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(server.getFixerUpper(), tag, version);
		StructureTemplate t = new StructureTemplate();
		t.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		return t;
	}

	private static void accept(Map<String, Entry> map, List<String> problems, Blueprint bp, StructureTemplate t, String source) {
		Vec3i size = t.getSize();
		if (size.getX() != bp.sizeX() || size.getY() != bp.sizeY() || size.getZ() != bp.sizeZ()) {
			throw new IllegalArgumentException("sidecar size " + bp.sizeX() + "x" + bp.sizeY() + "x" + bp.sizeZ() + " does not match template "
				+ size.getX() + "x" + size.getY() + "x" + size.getZ());
		}
		for (String w : bp.warnings()) {
			AgentCraft.LOGGER.warn("Blueprint {}: {}", bp.id(), w);
		}
		Entry old = map.put(bp.id(), new Entry(bp, t, source));
		if (old != null) {
			AgentCraft.LOGGER.info("Blueprint {} from {} overrides {}", bp.id(), source, old.source());
		}
	}

	private static void problem(List<String> problems, String where, Exception e) {
		String msg = where + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
		problems.add(msg);
		AgentCraft.LOGGER.warn("Skipping blueprint {}", msg);
	}
}
