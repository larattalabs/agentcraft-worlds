package dev.agentcraft.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The hub's side of the settings that hold secrets (docs/WAVE3.md contracts S1 and S2), pure, unit-tested in
 * {@code SecretSettingsTest}.
 * <ul>
 *   <li><b>Secret maps</b> ({@link SettingDef#SECRET_MAP}: a repository's {@code env}, each MCP server's env):
 *       {@code config.get} shows {@code {NAME: "(set)"}}, names only. The form stages a partial update
 *       {@code {NAME: "value" | null}} (null removes the variable); values are write-only: once staged they are never
 *       shown again (the row says "new value" / "replaced"), and the DevBridge sees them masked.</li>
 *   <li><b>MCP servers</b> ({@link SettingDef#MCP_SERVERS}: {@code claude.context.mcpServers}): the view is
 *       {@code [{name, type, command?, args?, url?, envKeys}]}; the form stages entries, each an upsert of one server
 *       ({@code {name, type, command?, args?, url?, env?}}, env in the partial form above) or {@code {name, remove: true}}.
 *       Arguments the Foreman shows as "(hidden)" go back as shown: it keeps the stored original in that place.</li>
 * </ul>
 * The checks mirror the Foreman's (it checks again, and its errors win).
 */
public final class SecretSettings {
	/** What {@code config.get} shows for every variable of a secret map. */
	public static final String SET = "(set)";
	/** An argument the Foreman does not show (a credential); sent back in the same place it keeps the original. */
	public static final String HIDDEN = "(hidden)";
	/** What the DevBridge shows for a staged secret value. */
	public static final String STAGED = "(staged)";
	public static final List<String> SERVER_TYPES = List.of("stdio", "http", "sse");
	private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	private static final Pattern SERVER_NAME = Pattern.compile("[\\w-]{1,64}");
	private static final String CLIENT_TOKEN = "AGENTCRAFT_CLIENT_TOKEN";
	private static final int MAX_VALUE = 20_000;

	private SecretSettings() {
	}

	// ------------------------------------------------------------------ secret maps (S1)

	/** The variable names of a secret map's view ({@code {NAME: "(set)"}}; anything else: none). */
	public static List<String> keys(@Nullable JsonElement view) {
		List<String> out = new ArrayList<>();
		if (view != null && view.isJsonObject()) {
			out.addAll(view.getAsJsonObject().keySet());
		}
		return out;
	}

	/** How a variable shows: set (as it is), new or replaced (a staged value), removed (staged null). */
	public enum VarState {
		SET, NEW, REPLACED, REMOVED
	}

	public record Var(String name, VarState state) {
	}

	/** The rows of a secret map: the current variables in order (with their staged change), then the new ones. */
	public static List<Var> vars(List<String> current, @Nullable JsonElement patch) {
		JsonObject p = patch != null && patch.isJsonObject() ? patch.getAsJsonObject() : new JsonObject();
		List<Var> out = new ArrayList<>();
		for (String k : current) {
			JsonElement v = p.get(k);
			out.add(new Var(k, v == null ? VarState.SET : v.isJsonNull() ? VarState.REMOVED : VarState.REPLACED));
		}
		for (String k : p.keySet()) {
			if (!current.contains(k) && !p.get(k).isJsonNull()) {
				out.add(new Var(k, VarState.NEW));
			}
		}
		return out;
	}

	/**
	 * The staged partial update with {@code name} set to {@code value} (null: removed). Removing a variable that is not
	 * set (only staged) just drops it from the update. Returns a new object; empty = nothing staged.
	 */
	public static JsonObject withVar(@Nullable JsonElement patch, List<String> current, String name, @Nullable String value) {
		JsonObject p = copy(patch);
		if (value == null) {
			if (current.contains(name)) {
				p.add(name, JsonNull.INSTANCE);
			} else {
				p.remove(name);
			}
		} else {
			p.addProperty(name, value);
		}
		return p;
	}

	/** The staged update without any change to {@code name} (Undo). */
	public static JsonObject undo(@Nullable JsonElement patch, String name) {
		JsonObject p = copy(patch);
		p.remove(name);
		return p;
	}

	/** Why {@code name} cannot be set, or null. {@code repo}: a repository's env (git's variables are refused too). */
	public static @Nullable String nameProblem(String name, boolean repo) {
		if (name == null || !ENV_NAME.matcher(name).matches()) {
			return "\"" + (name == null ? "" : name.length() > 40 ? name.substring(0, 40) : name) + "\" is not a variable name (letters, digits, _)";
		}
		if (repo && name.toUpperCase(Locale.ROOT).startsWith("GIT_")) {
			return name + ": git variables cannot be set for a repository";
		}
		if (name.equals(CLIENT_TOKEN)) {
			return name + ": the client token never reaches agents";
		}
		return null;
	}

	/** The mod's check of a staged partial update (names, text values), or null. */
	public static @Nullable String validatePatch(@Nullable JsonElement v, boolean repo) {
		if (v == null || v.isJsonNull()) {
			return null; // null: every variable removed
		}
		if (!v.isJsonObject()) {
			return "variables are needed";
		}
		List<String> problems = new ArrayList<>();
		for (var e : v.getAsJsonObject().entrySet()) {
			String why = nameProblem(e.getKey(), repo);
			if (why != null) {
				problems.add(why);
				continue;
			}
			JsonElement x = e.getValue();
			if (x.isJsonNull()) {
				continue;
			}
			if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isString()) {
				problems.add(e.getKey() + ": must be text");
			} else if (x.getAsString().length() > MAX_VALUE) {
				problems.add(e.getKey() + ": is too long");
			} else if (x.getAsString().indexOf('\0') >= 0) {
				problems.add(e.getKey() + ": must not contain a NUL character");
			}
		}
		return problems.isEmpty() ? null : String.join("; ", problems);
	}

	/** The view after a staged update applied: {@code {NAME: "(set)"}} (current names in order, removed ones out, new ones last). */
	public static JsonObject appliedView(List<String> current, @Nullable JsonElement patch) {
		JsonObject out = new JsonObject();
		if (patch != null && patch.isJsonNull()) {
			return out; // everything removed
		}
		for (Var v : vars(current, patch)) {
			if (v.state() != VarState.REMOVED) {
				out.addProperty(v.name(), SET);
			}
		}
		return out;
	}

	/** A staged update with every value replaced by {@link #STAGED} (removals stay null): for the DevBridge and logs. */
	public static JsonElement maskPatch(@Nullable JsonElement patch) {
		if (patch == null || !patch.isJsonObject()) {
			return patch == null ? JsonNull.INSTANCE : patch.deepCopy();
		}
		JsonObject out = new JsonObject();
		for (var e : patch.getAsJsonObject().entrySet()) {
			out.add(e.getKey(), e.getValue().isJsonNull() ? JsonNull.INSTANCE : new JsonPrimitive(STAGED));
		}
		return out;
	}

	private static JsonObject copy(@Nullable JsonElement patch) {
		return patch != null && patch.isJsonObject() ? patch.getAsJsonObject().deepCopy() : new JsonObject();
	}

	// ------------------------------------------------------------------ MCP servers (S2)

	/** One MCP server as {@code config.get} shows it (args may hold "(hidden)", the URL has no credentials or query). */
	public record Server(String name, String type, @Nullable String command, List<String> args, @Nullable String url, List<String> envKeys) {
		public Server {
			type = type == null || !SERVER_TYPES.contains(type) ? (url != null && command == null ? "http" : "stdio") : type;
			args = args == null ? List.of() : List.copyOf(args);
			envKeys = envKeys == null ? List.of() : List.copyOf(envKeys);
		}

		/** "npx -y fs-mcp /tmp" or the URL: what the list shows after the name. */
		public String target() {
			if (!"stdio".equals(type)) {
				return url == null ? "" : url;
			}
			StringBuilder b = new StringBuilder(command == null ? "" : command);
			for (String a : args) {
				b.append(' ').append(a);
			}
			return b.toString();
		}
	}

	/** The servers of an {@code mcpServers} view (entries without a name are skipped). */
	public static List<Server> servers(@Nullable JsonElement view) {
		List<Server> out = new ArrayList<>();
		if (view == null || !view.isJsonArray()) {
			return out;
		}
		for (JsonElement x : view.getAsJsonArray()) {
			if (!x.isJsonObject()) {
				continue;
			}
			Server s = server(x.getAsJsonObject());
			if (s != null) {
				out.add(s);
			}
		}
		return out;
	}

	private static @Nullable Server server(JsonObject o) {
		String name = str(o, "name");
		if (name == null || name.isBlank()) {
			return null;
		}
		List<String> args = new ArrayList<>();
		if (o.has("args") && o.get("args").isJsonArray()) {
			for (JsonElement a : o.getAsJsonArray("args")) {
				if (a.isJsonPrimitive()) {
					args.add(a.getAsString());
				}
			}
		}
		List<String> envKeys = new ArrayList<>();
		if (o.has("envKeys") && o.get("envKeys").isJsonArray()) {
			for (JsonElement a : o.getAsJsonArray("envKeys")) {
				if (a.isJsonPrimitive()) {
					envKeys.add(a.getAsString());
				}
			}
		}
		return new Server(name, str(o, "type"), str(o, "command"), args, str(o, "url"), envKeys);
	}

	/**
	 * The {@code config.set} entry that adds or replaces {@code s}: name, type, then command + args (stdio) or url (http,
	 * sse), and the env update (stdio only; null or empty: the variables stay as they are).
	 */
	public static JsonObject entry(Server s, @Nullable JsonElement envPatch) {
		JsonObject e = new JsonObject();
		e.addProperty("name", s.name());
		e.addProperty("type", s.type());
		if ("stdio".equals(s.type())) {
			e.addProperty("command", s.command() == null ? "" : s.command());
			if (!s.args().isEmpty()) {
				JsonArray a = new JsonArray();
				s.args().forEach(a::add);
				e.add("args", a);
			}
			if (envPatch != null && envPatch.isJsonObject() && !envPatch.getAsJsonObject().isEmpty()) {
				e.add("env", envPatch.deepCopy());
			}
		} else {
			e.addProperty("url", s.url() == null ? "" : s.url());
		}
		return e;
	}

	/** The {@code config.set} entry that removes a server. */
	public static JsonObject removal(String name) {
		JsonObject e = new JsonObject();
		e.addProperty("name", name);
		e.addProperty("remove", true);
		return e;
	}

	/** The staged entries with {@code entry} in place of any earlier one for the same server. */
	public static JsonArray withEntry(@Nullable JsonElement staged, JsonObject entry) {
		String name = str(entry, "name");
		JsonArray out = new JsonArray();
		boolean put = false;
		for (JsonElement x : entries(staged)) {
			if (x.isJsonObject() && name != null && name.equals(str(x.getAsJsonObject(), "name"))) {
				if (!put) {
					out.add(entry.deepCopy());
					put = true;
				}
			} else {
				out.add(x.deepCopy());
			}
		}
		if (!put) {
			out.add(entry.deepCopy());
		}
		return out;
	}

	/** The staged entries without the change to {@code name} (Undo). */
	public static JsonArray without(@Nullable JsonElement staged, String name) {
		JsonArray out = new JsonArray();
		for (JsonElement x : entries(staged)) {
			if (!(x.isJsonObject() && name.equals(str(x.getAsJsonObject(), "name")))) {
				out.add(x.deepCopy());
			}
		}
		return out;
	}

	/** The staged entry for {@code name}, or null. */
	public static @Nullable JsonObject stagedEntry(@Nullable JsonElement staged, String name) {
		for (JsonElement x : entries(staged)) {
			if (x.isJsonObject() && name.equals(str(x.getAsJsonObject(), "name"))) {
				return x.getAsJsonObject();
			}
		}
		return null;
	}

	/** One line of the MCP servers list: the server as it will be (after its staged change) and what changed. */
	public record Row(Server server, boolean added, boolean edited, boolean removed, @Nullable JsonObject envPatch) {
	}

	/** The servers as the list shows them: the current ones in order with their staged change, then the added ones. */
	public static List<Row> rows(List<Server> current, @Nullable JsonElement staged) {
		List<Row> out = new ArrayList<>();
		Set<String> names = new LinkedHashSet<>();
		for (Server s : current) {
			names.add(s.name());
			JsonObject e = stagedEntry(staged, s.name());
			if (e == null) {
				out.add(new Row(s, false, false, false, null));
			} else if (e.has("remove")) {
				out.add(new Row(s, false, false, true, null));
			} else {
				out.add(new Row(fromEntry(e, s.envKeys()), false, true, false, envOf(e)));
			}
		}
		for (JsonElement x : entries(staged)) {
			if (!x.isJsonObject()) {
				continue;
			}
			JsonObject e = x.getAsJsonObject();
			String name = str(e, "name");
			if (name != null && !names.contains(name) && !e.has("remove")) {
				out.add(new Row(fromEntry(e, List.of()), true, false, false, envOf(e)));
			}
		}
		return out;
	}

	/** A staged entry as a server (env names: the current ones with the update applied). */
	private static Server fromEntry(JsonObject e, List<String> envKeys) {
		Server s = server(e);
		JsonObject env = envOf(e);
		List<String> keys = "stdio".equals(s == null ? null : s.type()) ? keys(appliedView(envKeys, env)) : List.of();
		return s == null ? new Server("?", "stdio", "", List.of(), null, keys) : new Server(s.name(), s.type(), s.command(), s.args(), s.url(), keys);
	}

	private static @Nullable JsonObject envOf(JsonObject e) {
		return e.has("env") && e.get("env").isJsonObject() ? e.getAsJsonObject("env") : null;
	}

	/**
	 * The mod's check of one server before it is staged (the Foreman's rules), or null. {@code others}: the names of
	 * the other servers (a new server's name must be free).
	 */
	public static @Nullable String serverProblem(Server s, @Nullable JsonElement envPatch, boolean isNew, List<String> others) {
		String name = s.name() == null ? "" : s.name().strip();
		if (!SERVER_NAME.matcher(name).matches()) {
			return "name: letters, digits, _ or - (at most 64)";
		}
		if (name.equalsIgnoreCase("agentcraft")) {
			return "name: agentcraft is the team tools server";
		}
		if (isNew && others.contains(name)) {
			return "name: " + name + " is already a server";
		}
		if (!SERVER_TYPES.contains(s.type())) {
			return "type: stdio, http or sse";
		}
		if ("stdio".equals(s.type())) {
			String c = s.command() == null ? "" : s.command().strip();
			if (c.isEmpty() || c.length() > 1000 || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0 || c.indexOf('\0') >= 0) {
				return "command: a stdio server needs a command (one line)";
			}
			if (s.args().size() > 100) {
				return "args: at most 100";
			}
			return envPatch == null || envPatch.isJsonNull() ? null : prefix("env", validatePatch(envPatch, false));
		}
		return urlProblem(s.url());
	}

	/** Why an http/sse URL is refused (not http(s), credentials, a query or fragment), or null. */
	public static @Nullable String urlProblem(@Nullable String url) {
		String u = url == null ? "" : url.strip();
		URI p;
		try {
			p = new URI(u);
		} catch (URISyntaxException e) {
			return "url: an http(s) URL is needed";
		}
		String scheme = p.getScheme() == null ? "" : p.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https") || p.getHost() == null) {
			return "url: an http(s) URL is needed";
		}
		if (p.getRawUserInfo() != null) {
			return "url: no credentials in the URL (put them into config.json headers)";
		}
		if (p.getRawQuery() != null || p.getRawFragment() != null) {
			return "url: no query or fragment";
		}
		return null;
	}

	/**
	 * Why edited arguments would write a placeholder into config.json: the Foreman puts back a hidden argument only where
	 * the same shown text stands at the same index ({@code shownBefore}: the server's arguments as {@code config.get}
	 * showed them; a new server has none). A "(hidden)" (or "--x=(hidden)") anywhere else must be typed again. Null = fine.
	 */
	public static @Nullable String hiddenArgsProblem(List<String> shownBefore, List<String> edited) {
		for (int i = 0; i < edited.size(); i++) {
			String a = edited.get(i);
			if ((a.equals(HIDDEN) || a.endsWith("=" + HIDDEN)) && (i >= shownBefore.size() || !shownBefore.get(i).equals(a))) {
				return "args: the hidden argument " + (i + 1) + " moved (or is new): type its real value again";
			}
		}
		return null;
	}

	private static @Nullable String prefix(String p, @Nullable String why) {
		return why == null ? null : p + ": " + why;
	}

	/** The mod's check of the staged entries (each server once, each entry valid, hidden arguments in place), or null. */
	public static @Nullable String validateEntries(@Nullable JsonElement v, List<Server> servers) {
		List<String> current = new ArrayList<>();
		servers.forEach(s -> current.add(s.name()));
		if (v == null || v.isJsonNull()) {
			return null;
		}
		if (!v.isJsonArray()) {
			return "a list of servers is needed";
		}
		Set<String> seen = new LinkedHashSet<>();
		List<String> problems = new ArrayList<>();
		for (JsonElement x : v.getAsJsonArray()) {
			if (!x.isJsonObject()) {
				problems.add("every entry must be a server");
				continue;
			}
			JsonObject e = x.getAsJsonObject();
			String name = str(e, "name");
			if (name == null || !seen.add(name)) {
				problems.add(name == null ? "an entry has no name" : name + ": listed twice");
				continue;
			}
			if (e.has("remove")) {
				if (!current.contains(name)) {
					problems.add(name + ": no such MCP server");
				}
				continue;
			}
			Server s = server(e);
			String why = s == null ? "name missing" : serverProblem(s, envOf(e), false, List.of());
			if (s != null && str(e, "type") == null) {
				why = "type: stdio, http or sse";
			}
			if (why == null && s != null) {
				List<String> before = List.of();
				for (Server c : servers) {
					if (c.name().equals(name)) {
						before = c.args();
					}
				}
				why = hiddenArgsProblem(before, s.args());
			}
			if (why != null) {
				problems.add(name + " " + why);
			}
		}
		return problems.isEmpty() ? null : String.join("; ", problems);
	}

	/** The view after the staged entries applied ({@code envKeys} only, never values). */
	public static JsonArray appliedServers(List<Server> current, @Nullable JsonElement staged) {
		JsonArray out = new JsonArray();
		for (Row r : rows(current, staged)) {
			if (r.removed()) {
				continue;
			}
			Server s = r.server();
			JsonObject o = new JsonObject();
			o.addProperty("name", s.name());
			o.addProperty("type", s.type());
			if (s.command() != null && "stdio".equals(s.type())) {
				o.addProperty("command", s.command());
			}
			if (!s.args().isEmpty() && "stdio".equals(s.type())) {
				JsonArray a = new JsonArray();
				s.args().forEach(a::add);
				o.add("args", a);
			}
			if (s.url() != null && !"stdio".equals(s.type())) {
				o.addProperty("url", s.url());
			}
			JsonArray k = new JsonArray();
			s.envKeys().forEach(k::add);
			o.add("envKeys", k);
			out.add(o);
		}
		return out;
	}

	/** Staged entries with every env value replaced by {@link #STAGED}: for the DevBridge and logs. */
	public static JsonElement maskEntries(@Nullable JsonElement staged) {
		if (staged == null || !staged.isJsonArray()) {
			return staged == null ? JsonNull.INSTANCE : staged.deepCopy();
		}
		JsonArray out = new JsonArray();
		for (JsonElement x : staged.getAsJsonArray()) {
			if (x.isJsonObject() && x.getAsJsonObject().has("env")) {
				JsonObject o = x.getAsJsonObject().deepCopy();
				o.add("env", maskPatch(o.get("env")));
				out.add(o);
			} else {
				out.add(x.deepCopy());
			}
		}
		return out;
	}

	private static List<JsonElement> entries(@Nullable JsonElement staged) {
		List<JsonElement> out = new ArrayList<>();
		if (staged != null && staged.isJsonArray()) {
			staged.getAsJsonArray().forEach(out::add);
		}
		return out;
	}

	private static @Nullable String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
