package dev.agentcraft.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One editable setting as {@code config.get} describes it (docs/HUB.md "Team and Settings tabs"):
 * {@code key} is the config.json path ({@code claude.prWatch}, {@code claude.agents.kit.model}; for a repo
 * {@code land}, {@code pr.draft}, {@code roles.kit}), {@code type} one of {@link #TYPES}, {@code value} and
 * {@code def} (the default) as JSON (never null: {@link JsonNull} = not set), {@code source}
 * file|flag|env|default, {@code live} = applied without a restart, {@code overriddenBy} = the flag/env that
 * wins over the file. The hub renders a form from these; nothing about a setting is hard-coded in the mod.
 */
public record SettingDef(String key, String label, String help, String group, String type, List<String> options, @Nullable Double min,
	@Nullable Double max, JsonElement value, JsonElement def, String source, boolean live, @Nullable String overriddenBy, boolean readOnlyFlag) {
	public static final String BOOL = "bool";
	public static final String INT = "int";
	public static final String ENUM = "enum";
	public static final String STRING = "string";
	public static final String STRING_LIST = "stringList";
	public static final String MODEL = "model";
	public static final String EFFORT = "effort";
	public static final String AGENT_LIST = "agentList";
	public static final String MAP = "map";
	/** S1 (docs/WAVE3.md): {@code {NAME: "(set)"}}, names only; staged as a partial update {@code {NAME: "value" | null}}. */
	public static final String SECRET_MAP = "secretMap";
	/** S2: {@code [{name, type, command?, args?, url?, envKeys}]}; staged as upserts {@code {name, ...}} / {@code {name, remove: true}}. */
	public static final String MCP_SERVERS = "mcpServers";
	public static final List<String> TYPES = List.of(BOOL, INT, ENUM, STRING, STRING_LIST, MODEL, EFFORT, AGENT_LIST, MAP, SECRET_MAP, MCP_SERVERS);

	public SettingDef {
		label = label == null || label.isBlank() ? key : label;
		help = help == null ? "" : help;
		group = group == null ? "" : group;
		type = type == null ? STRING : type;
		options = options == null ? List.of() : List.copyOf(options);
		value = value == null ? JsonNull.INSTANCE : value;
		def = def == null ? JsonNull.INSTANCE : def;
		source = source == null ? "default" : source;
	}

	/** A type this mod knows how to edit (anything else is shown read-only). */
	public boolean knownType() {
		return TYPES.contains(type);
	}

	/** The same definition without the read-only flag (synthesised settings). */
	public SettingDef(String key, String label, String help, String group, String type, List<String> options, @Nullable Double min,
		@Nullable Double max, JsonElement value, JsonElement def, String source, boolean live, @Nullable String overriddenBy) {
		this(key, label, help, group, type, options, min, max, value, def, source, live, overriddenBy, false);
	}

	/** Shown but never edited here: marked {@code readOnly} by the Foreman, plain maps and types this mod does not know (secret maps and MCP servers are edited). */
	public boolean readOnly() {
		return readOnlyFlag || MAP.equals(type) || !knownType();
	}

	/** Whether this setting holds secrets ({@link #SECRET_MAP}, {@link #MCP_SERVERS}): staged values are never shown or echoed. */
	public boolean secret() {
		return SECRET_MAP.equals(type) || MCP_SERVERS.equals(type);
	}

	/** The same setting with another current value (a config.set that applied, a fake for tests). */
	public SettingDef withValue(JsonElement v, String newSource) {
		return new SettingDef(key, label, help, group, type, options, min, max, v, def, newSource, live, overriddenBy, readOnlyFlag);
	}

	/** Parses one {@code SettingDef} object; null when it has no key. Unknown fields are ignored. */
	public static @Nullable SettingDef parse(JsonElement el) {
		if (el == null || !el.isJsonObject()) {
			return null;
		}
		JsonObject o = el.getAsJsonObject();
		String key = str(o, "key");
		if (key == null || key.isBlank()) {
			return null;
		}
		List<String> options = new ArrayList<>();
		if (o.has("options") && o.get("options").isJsonArray()) {
			for (JsonElement x : o.getAsJsonArray("options")) {
				if (x.isJsonPrimitive()) {
					options.add(x.getAsString());
				}
			}
		}
		String type = str(o, "type");
		return new SettingDef(key, str(o, "label"), str(o, "help"), str(o, "group"), type, options, num(o, "min"), num(o, "max"),
			o.has("value") ? o.get("value") : JsonNull.INSTANCE, o.has("default") ? o.get("default") : JsonNull.INSTANCE, str(o, "source"),
			o.has("live") && o.get("live").isJsonPrimitive() && o.get("live").getAsBoolean(), str(o, "overriddenBy"), o.has("readOnly") && o.get(
				"readOnly").isJsonPrimitive() && o.get("readOnly").getAsBoolean());
	}

	/** An MCP server as config.get lists it: name and command only (read-only, no env). */
	public record McpServer(String name, String command) {
	}

	/** A config.get ack result: the file it reads, the settings, the MCP servers (global only). */
	public record ConfigView(@Nullable String file, List<SettingDef> settings, List<McpServer> mcpServers) {
		public ConfigView {
			settings = settings == null ? List.of() : List.copyOf(settings);
			mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
		}

		public @Nullable SettingDef get(String key) {
			for (SettingDef d : settings) {
				if (d.key().equals(key)) {
					return d;
				}
			}
			return null;
		}

		/**
		 * Parses the ack result {@code {file, settings: SettingDef[], mcpServers?}}. MCP servers come from
		 * {@code mcpServers} ([{name, command}] or {name: command | {command}}) or, failing that, from a
		 * {@code map} setting whose key ends in {@code mcpServers}.
		 */
		public static ConfigView parse(@Nullable JsonObject result) {
			if (result == null) {
				return new ConfigView(null, List.of(), List.of());
			}
			List<SettingDef> defs = new ArrayList<>();
			JsonElement s = result.get("settings");
			if (s != null && s.isJsonArray()) {
				for (JsonElement x : s.getAsJsonArray()) {
					SettingDef d = SettingDef.parse(x);
					if (d != null) {
						defs.add(d);
					}
				}
			}
			List<McpServer> mcp = new ArrayList<>(servers(result.get("mcpServers")));
			if (mcp.isEmpty()) {
				for (SettingDef d : defs) {
					if (d.key().toLowerCase(Locale.ROOT).endsWith("mcpservers")) {
						mcp.addAll(servers(d.value()));
					}
				}
			}
			return new ConfigView(str(result, "file"), defs, mcp);
		}

		private static List<McpServer> servers(@Nullable JsonElement el) {
			List<McpServer> out = new ArrayList<>();
			if (el == null) {
				return out;
			}
			if (el.isJsonArray()) {
				for (JsonElement x : el.getAsJsonArray()) {
					if (x.isJsonObject()) {
						String n = str(x.getAsJsonObject(), "name");
						if (n != null) {
							String c = str(x.getAsJsonObject(), "command");
							out.add(new McpServer(n, c == null ? str(x.getAsJsonObject(), "url") == null ? "" : str(x.getAsJsonObject(), "url") : c));
						}
					} else if (x.isJsonPrimitive()) {
						out.add(new McpServer(x.getAsString(), ""));
					}
				}
			} else if (el.isJsonObject()) {
				for (var e : el.getAsJsonObject().entrySet()) {
					JsonElement v = e.getValue();
					String c = v.isJsonPrimitive() ? v.getAsString() : v.isJsonObject() ? str(v.getAsJsonObject(), "command") : null;
					if (c == null && v.isJsonObject()) {
						c = str(v.getAsJsonObject(), "url");
					}
					out.add(new McpServer(e.getKey(), c == null ? "" : c));
				}
			}
			return out;
		}
	}

	static @Nullable String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static @Nullable Double num(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return e.getAsDouble();
	}

	/** JSON array of strings (for tests and fakes). */
	public static JsonArray strings(List<String> xs) {
		JsonArray a = new JsonArray();
		xs.forEach(a::add);
		return a;
	}
}
