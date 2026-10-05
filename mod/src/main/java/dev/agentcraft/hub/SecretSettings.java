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
 * {@code SecretSettingsTest}. Secrets are write-only: the Foreman never sends one, so the hub never shows, keeps or
 * echoes one.
 * <ul>
 *   <li><b>Secret maps</b> ({@link SettingDef#SECRET_MAP}: a repository's {@code env}, each MCP server's env):
 *       {@code config.get} shows the names only ({@code ["NAME", ...]}). The form stages a partial update
 *       {@code {NAME: "value" | null}} (null removes the variable); values are write-only: once staged they are never
 *       shown again (the row says "new value" / "replaced"), and the DevBridge sees them masked.</li>
 *   <li><b>MCP servers</b> ({@link SettingDef#MCP_SERVERS}: {@code claude.context.mcpServers}): the view is
 *       {@code [{name, type, command?, argCount?, url?, urlHasPath?, headerKeys?, envKeys}]} - the executable, how many
 *       arguments, scheme://host. The form stages entries, each an upsert of one server ({@code {name, type, command?,
 *       args?, url?, env?}}: a part left out keeps the stored one, a part sent replaces it - args as the complete new
 *       list) or {@code {name, remove: true}}.</li>
 * </ul>
 * The checks mirror the Foreman's (it checks again, and its errors win).
 */
public final class SecretSettings {
	/** What an older Foreman showed for every variable of a secret map (a placeholder: never sent as a value). */
	public static final String SET = "(set)";
	/** What an older Foreman showed for a hidden argument (a placeholder: never sent as a value). */
	public static final String HIDDEN = "(hidden)";
	/** What the DevBridge shows for a staged secret value. */
	public static final String STAGED = "(staged)";
	public static final List<String> SERVER_TYPES = List.of("stdio", "http", "sse");
	private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	private static final Pattern SERVER_NAME = Pattern.compile("[\\w-]{1,64}");
	private static final String CLIENT_TOKEN = "AGENTCRAFT_CLIENT_TOKEN";
	private static final int MAX_VALUE = 20_000;
	/** Control characters a value may not hold (tab, newline and carriage return are fine: a PEM key has lines). */
	private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]");
	private static final Pattern CONTROL_ANY = Pattern.compile("[\\x00-\\x1f\\x7f]");

	private SecretSettings() {
	}

	// ------------------------------------------------------------------ secret maps (S1)

	/**
	 * The variable names of a secret map's view (a list of names; an older Foreman's {@code {NAME: "(set)"}}) or of a
	 * staged update ({@code {NAME: value | null}}); anything else: none.
	 */
	public static List<String> keys(@Nullable JsonElement view) {
		List<String> out = new ArrayList<>();
		if (view != null && view.isJsonObject()) {
			out.addAll(view.getAsJsonObject().keySet());
		} else if (view != null && view.isJsonArray()) {
			for (JsonElement x : view.getAsJsonArray()) {
				if (x.isJsonPrimitive() && !out.contains(x.getAsString())) {
					out.add(x.getAsString());
				}
			}
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
		// never quotes the name: whatever was typed there may be a secret
		if (name == null || !ENV_NAME.matcher(name).matches()) {
			return "not a variable name (letters, digits, _)";
		}
		if (repo && name.toUpperCase(Locale.ROOT).startsWith("GIT_")) {
			return "git variables cannot be set for a repository";
		}
		if (name.equals(CLIENT_TOKEN)) {
			return "the client token never reaches agents";
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
		int i = 0;
		for (var e : v.getAsJsonObject().entrySet()) {
			String at = "variable " + ++i; // places, never names or values
			String why = nameProblem(e.getKey(), repo);
			if (why != null) {
				problems.add(at + ": " + why);
				continue;
			}
			JsonElement x = e.getValue();
			if (x.isJsonNull()) {
				continue;
			}
			if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isString()) {
				problems.add(at + ": must be text");
			} else if (x.getAsString().length() > MAX_VALUE) {
				problems.add(at + ": is too long");
			} else if (CONTROL.matcher(x.getAsString()).find()) {
				problems.add(at + ": must not contain control characters");
			} else if (placeholder(x.getAsString())) {
				problems.add(at + ": type the real value (a placeholder is not one)");
			}
		}
		return problems.isEmpty() ? null : String.join("; ", problems);
	}

	/** The view after a staged update applied: the names (current ones in order, removed ones out, new ones last). */
	public static JsonArray appliedView(List<String> current, @Nullable JsonElement patch) {
		JsonArray out = new JsonArray();
		if (patch != null && patch.isJsonNull()) {
			return out; // everything removed
		}
		for (Var v : vars(current, patch)) {
			if (v.state() != VarState.REMOVED) {
				out.add(v.name());
			}
		}
		return out;
	}

	/**
	 * A staged update as the DevBridge and logs may show it: every value {@link #STAGED} (removals stay null), anything
	 * that is not a variable name or a text value - and a staged value that is not an object - {@link #HIDDEN}. Never a
	 * copy of what was typed.
	 */
	public static JsonElement maskPatch(@Nullable JsonElement patch) {
		if (patch == null || patch.isJsonNull()) {
			return JsonNull.INSTANCE;
		}
		if (!patch.isJsonObject()) {
			return new JsonPrimitive(HIDDEN);
		}
		JsonObject out = new JsonObject();
		int i = 0;
		for (var e : patch.getAsJsonObject().entrySet()) {
			i++;
			String k = ENV_NAME.matcher(e.getKey()).matches() ? e.getKey() : HIDDEN + " " + i;
			JsonElement v = e.getValue();
			out.add(k, v.isJsonNull() ? JsonNull.INSTANCE : new JsonPrimitive(v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? STAGED : HIDDEN));
		}
		return out;
	}

	private static JsonObject copy(@Nullable JsonElement patch) {
		return patch != null && patch.isJsonObject() ? patch.getAsJsonObject().deepCopy() : new JsonObject();
	}

	// ------------------------------------------------------------------ MCP servers (S2)

	/**
	 * One MCP server as {@code config.get} shows it: the executable only, how many arguments are stored, the URL as
	 * scheme://host[:port] ({@code urlHasPath}: the stored one has more), the env and header names. Argument values and
	 * the full URL are never shown: they are write-only.
	 */
	public record Server(String name, String type, @Nullable String command, int argCount, @Nullable String url, boolean urlHasPath,
		List<String> envKeys, List<String> headerKeys) {
		public Server {
			type = type == null || !SERVER_TYPES.contains(type) ? (url != null && command == null ? "http" : "stdio") : type;
			argCount = Math.max(0, argCount);
			envKeys = envKeys == null ? List.of() : List.copyOf(envKeys);
			headerKeys = headerKeys == null ? List.of() : List.copyOf(headerKeys);
		}

		public boolean stdio() {
			return "stdio".equals(type);
		}

		/** "npx + 3 arguments" or "https://host/…": what the list shows after the name (never a secret). */
		public String target() {
			if (!stdio()) {
				return url == null ? (urlHasPath ? "(URL not shown)" : "") : url + (urlHasPath ? "/…" : "");
			}
			String c = command == null ? "" : command;
			return argCount == 0 ? c : c + " + " + argCount + (argCount == 1 ? " argument" : " arguments");
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
		JsonElement n = o.get("argCount");
		int argCount = n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber() ? n.getAsInt() : 0;
		JsonElement h = o.get("urlHasPath");
		boolean hasPath = h != null && h.isJsonPrimitive() && h.getAsJsonPrimitive().isBoolean() && h.getAsBoolean();
		return new Server(name, str(o, "type"), str(o, "command"), argCount, str(o, "url"), hasPath, strings(o, "envKeys"), strings(o, "headerKeys"));
	}

	/** The executable of a command line (what the Foreman shows of it). */
	public static String executable(@Nullable String command) {
		String c = command == null ? "" : command.strip();
		int sp = c.indexOf(' ');
		int tab = c.indexOf('\t');
		int cut = sp < 0 ? tab : tab < 0 ? sp : Math.min(sp, tab);
		return cut < 0 ? c : c.substring(0, cut);
	}

	/** scheme://host[:port] of a URL (what the Foreman shows of it), or null. */
	public static @Nullable String origin(@Nullable String url) {
		try {
			URI p = new URI(url == null ? "" : url.strip());
			if (p.getScheme() == null || p.getHost() == null) {
				return null;
			}
			return p.getScheme().toLowerCase(Locale.ROOT) + "://" + p.getHost().toLowerCase(Locale.ROOT) + (p.getPort() >= 0 ? ":" + p.getPort() : "");
		} catch (URISyntaxException e) {
			return null;
		}
	}

	private static boolean hasPath(@Nullable String url) {
		try {
			URI p = new URI(url == null ? "" : url.strip());
			String path = p.getRawPath();
			return (path != null && !path.isEmpty() && !path.equals("/")) || p.getRawQuery() != null || p.getRawFragment() != null;
		} catch (URISyntaxException e) {
			return true;
		}
	}

	/**
	 * The {@code config.set} entry that adds or changes server {@code name}: name and type, then only what is sent -
	 * {@code command}, {@code args} (the complete new list) and {@code url} replace the stored value exactly, null keeps
	 * it - and the env update (stdio only; null or empty: the variables stay as they are).
	 */
	public static JsonObject entry(String name, String type, @Nullable String command, @Nullable List<String> args, @Nullable String url,
		@Nullable JsonElement envPatch) {
		JsonObject e = new JsonObject();
		e.addProperty("name", name);
		e.addProperty("type", type);
		if ("stdio".equals(type)) {
			if (command != null) {
				e.addProperty("command", command);
			}
			if (args != null) {
				JsonArray a = new JsonArray();
				args.forEach(a::add);
				e.add("args", a);
			}
			if (envPatch != null && envPatch.isJsonObject() && !envPatch.getAsJsonObject().isEmpty()) {
				e.add("env", envPatch.deepCopy());
			}
		} else if (url != null) {
			e.addProperty("url", url);
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

	/**
	 * One line of the MCP servers list: the server as it will be (after its staged change), what changed, and which
	 * write-only parts the staged change replaces.
	 */
	public record Row(Server server, boolean added, boolean edited, boolean removed, @Nullable JsonObject envPatch, boolean commandReplaced,
		boolean argsReplaced, boolean urlReplaced) {
	}

	/** The servers as the list shows them: the current ones in order with their staged change, then the added ones. */
	public static List<Row> rows(List<Server> current, @Nullable JsonElement staged) {
		List<Row> out = new ArrayList<>();
		Set<String> names = new LinkedHashSet<>();
		for (Server s : current) {
			names.add(s.name());
			JsonObject e = stagedEntry(staged, s.name());
			if (e == null) {
				out.add(new Row(s, false, false, false, null, false, false, false));
			} else if (e.has("remove")) {
				out.add(new Row(s, false, false, true, null, false, false, false));
			} else {
				out.add(row(e, s, false));
			}
		}
		for (JsonElement x : entries(staged)) {
			if (!x.isJsonObject()) {
				continue;
			}
			JsonObject e = x.getAsJsonObject();
			String name = str(e, "name");
			if (name != null && !names.contains(name) && !e.has("remove")) {
				out.add(row(e, null, true));
			}
		}
		return out;
	}

	private static Row row(JsonObject e, @Nullable Server cur, boolean added) {
		return new Row(fromEntry(e, cur), added, !added, false, envOf(e), e.has("command"), e.has("args"), e.has("url"));
	}

	/** A staged entry as the view will show it: what it sends, else what the current server of the same kind has. */
	public static Server fromEntry(JsonObject e, @Nullable Server cur) {
		String name = str(e, "name");
		String type = str(e, "type");
		boolean stdio = !"http".equals(type) && !"sse".equals(type);
		boolean same = cur != null && cur.stdio() == stdio;
		if (stdio) {
			String command = e.has("command") ? executable(str(e, "command")) : same ? cur.command() : null;
			int argCount = e.has("args") && e.get("args").isJsonArray() ? e.getAsJsonArray("args").size() : same ? cur.argCount() : 0;
			List<String> keys = keys(appliedView(same ? cur.envKeys() : List.of(), envOf(e)));
			return new Server(name == null ? "?" : name, "stdio", command, argCount, null, false, keys, cur == null ? List.of() : cur.headerKeys());
		}
		String url = e.has("url") ? str(e, "url") : null;
		return new Server(name == null ? "?" : name, type, null, 0, url != null ? origin(url) : same ? cur.url() : null,
			url != null ? hasPath(url) : same && cur.urlHasPath(), List.of(), cur == null ? List.of() : cur.headerKeys());
	}

	private static @Nullable JsonObject envOf(JsonObject e) {
		return e.has("env") && e.get("env").isJsonObject() ? e.getAsJsonObject("env") : null;
	}

	/** Values the Foreman refuses as a secret (what a view or the DevBridge shows in its place). */
	public static boolean placeholder(@Nullable String v) {
		if (v == null) {
			return false;
		}
		String t = v.strip().toLowerCase(Locale.ROOT);
		return t.equals(SET) || t.equals(HIDDEN) || t.equals(STAGED) || t.equals("(not shown)") || t.equals("[redacted]");
	}

	/** Why arguments are refused (too many, a placeholder, a control character), or null. Places, never values. */
	public static @Nullable String argsProblem(@Nullable List<String> args) {
		if (args == null) {
			return null;
		}
		if (args.size() > 100) {
			return "args: at most 100";
		}
		for (int i = 0; i < args.size(); i++) {
			String a = args.get(i);
			if (a.length() > 4000 || CONTROL.matcher(a).find()) {
				return "args: argument " + (i + 1) + " is too long or holds a control character";
			}
			int eq = a.indexOf('=');
			if (placeholder(a) || eq >= 0 && placeholder(a.substring(eq + 1))) {
				return "args: argument " + (i + 1) + " is a placeholder: type the real value";
			}
		}
		return null;
	}

	/** The parts of a staged entry for a server of {@code type}'s kind (a staged url is no use to a stdio server). */
	private static @Nullable JsonObject stagedOfKind(@Nullable JsonObject staged, String type) {
		if (staged == null || staged.has("remove")) {
			return null;
		}
		return "stdio".equals(str(staged, "type")) == "stdio".equals(type) ? staged : null;
	}

	/** The command an earlier Done staged for this server, or null. */
	public static @Nullable String stagedCommand(@Nullable JsonObject staged, String type) {
		JsonObject s = stagedOfKind(staged, type);
		return s != null && s.has("command") ? str(s, "command") : null;
	}

	/** The arguments an earlier Done staged for this server (the complete new list), or null. */
	public static @Nullable List<String> stagedArgs(@Nullable JsonObject staged, String type) {
		JsonObject s = stagedOfKind(staged, type);
		return s != null && s.has("args") && s.get("args").isJsonArray() ? strings(s, "args") : null;
	}

	/** The URL an earlier Done staged for this server, or null. */
	public static @Nullable String stagedUrl(@Nullable JsonObject staged, String type) {
		JsonObject s = stagedOfKind(staged, type);
		return s != null && s.has("url") ? str(s, "url") : null;
	}

	/** What a server form sends: null = left out (the Foreman keeps the stored value). */
	public record Send(@Nullable String command, @Nullable List<String> args, @Nullable String url) {
	}

	/**
	 * What the server form sends on Done. {@code cur}: the server as the Foreman shows it (null: not stored yet);
	 * {@code staged}: its entry from an earlier Done, if any; {@code typedCommand} / {@code commandShown}: the command
	 * field and the text it started with. The command goes when edited (else the staged one, else - a server that is
	 * new or changes between stdio and http - the field); args / url go when "Replace…" is on (the field: the complete
	 * new value), else the staged ones, else the field for such a fresh server; otherwise nothing (kept).
	 */
	public static Send toSend(String type, @Nullable Server cur, @Nullable JsonObject staged, String typedCommand, String commandShown,
		boolean replaceArgs, List<String> typedArgs, boolean replaceUrl, String typedUrl) {
		boolean stdio = "stdio".equals(type);
		boolean fresh = cur == null || cur.stdio() != stdio;
		if (stdio) {
			String sc = stagedCommand(staged, type);
			String command = !typedCommand.equals(commandShown) ? typedCommand : sc != null ? sc : fresh ? typedCommand : null;
			List<String> sa = stagedArgs(staged, type);
			List<String> args = replaceArgs ? List.copyOf(typedArgs) : sa != null ? sa : fresh ? List.copyOf(typedArgs) : null;
			return new Send(command, args, null);
		}
		String su = stagedUrl(staged, type);
		return new Send(null, null, replaceUrl ? typedUrl : su != null ? su : fresh ? typedUrl : null);
	}

	/**
	 * The mod's check of one server change before it is staged (the Foreman's rules), or null. {@code cur}: the server
	 * as the Foreman shows it (null: not stored yet); one not stored or changing between stdio and http needs its command
	 * or URL; otherwise null command / args / url keep the stored ones. {@code isNew}: added in this form (its name must
	 * not be one of {@code others}).
	 */
	public static @Nullable String serverProblem(String name, String type, @Nullable String command, @Nullable List<String> args, @Nullable String url,
		@Nullable JsonElement envPatch, @Nullable Server cur, boolean isNew, List<String> others) {
		String n = name == null ? "" : name.strip();
		if (!SERVER_NAME.matcher(n).matches()) {
			return "name: letters, digits, _ or - (at most 64)";
		}
		if (n.equalsIgnoreCase("agentcraft")) {
			return "name: agentcraft is the team tools server";
		}
		if (isNew && others.contains(n)) {
			return "name: already a server";
		}
		if (!SERVER_TYPES.contains(type)) {
			return "type: stdio, http or sse";
		}
		boolean stdio = "stdio".equals(type);
		boolean same = cur != null && cur.stdio() == stdio;
		if (stdio) {
			if (command == null) {
				if (!same) {
					return "command: a stdio server needs a command";
				}
			} else {
				String c = command.strip();
				if (c.isEmpty() || c.length() > 1000 || CONTROL_ANY.matcher(c).find()) {
					return "command: a stdio server needs a command (one line)";
				}
				if (placeholder(c)) {
					return "command: a placeholder is not a command";
				}
			}
			String why = argsProblem(args);
			if (why != null) {
				return why;
			}
			return envPatch == null || envPatch.isJsonNull() ? null : prefix("env", validatePatch(envPatch, false));
		}
		if (url == null) {
			return same ? null : "url: an http(s) URL is needed";
		}
		return urlProblem(url);
	}

	/** Why an http/sse URL is refused (not http(s), spaces or control characters, credentials, a fragment), or null. */
	public static @Nullable String urlProblem(@Nullable String url) {
		String u = url == null ? "" : url;
		if (CONTROL_ANY.matcher(u).find() || u.chars().anyMatch(Character::isWhitespace)) {
			return "url: no spaces or control characters";
		}
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
		if (p.getRawFragment() != null) {
			return "url: no fragment";
		}
		return null;
	}

	private static @Nullable String prefix(String p, @Nullable String why) {
		return why == null ? null : p + ": " + why;
	}

	/** The mod's check of the staged entries (each server once, each entry valid), or null. */
	public static @Nullable String validateEntries(@Nullable JsonElement v, List<Server> servers) {
		if (v == null || v.isJsonNull()) {
			return null;
		}
		if (!v.isJsonArray()) {
			return "a list of servers is needed";
		}
		Set<String> seen = new LinkedHashSet<>();
		List<String> problems = new ArrayList<>();
		int i = 0;
		for (JsonElement x : v.getAsJsonArray()) {
			String at = "server " + ++i; // places, never names or values
			if (!x.isJsonObject()) {
				problems.add(at + ": must be a server");
				continue;
			}
			JsonObject e = x.getAsJsonObject();
			String name = str(e, "name");
			if (name == null || !seen.add(name)) {
				problems.add(at + (name == null ? ": has no name" : ": listed twice"));
				continue;
			}
			Server cur = null;
			for (Server c : servers) {
				if (c.name().equals(name)) {
					cur = c;
				}
			}
			if (e.has("remove")) {
				if (cur == null) {
					problems.add(at + ": no such MCP server");
				}
				continue;
			}
			String type = str(e, "type");
			String why = type == null ? "type: stdio, http or sse" : serverProblem(name, type, e.has("command") ? str(e, "command") : null,
				e.has("args") ? strings(e, "args") : null, e.has("url") ? str(e, "url") : null, envOf(e), cur, false, List.of());
			if (why != null) {
				problems.add(at + " " + why);
			}
		}
		return problems.isEmpty() ? null : String.join("; ", problems);
	}

	/** The view after the staged entries applied (names, counts and origins only, never values). */
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
			if (s.stdio()) {
				if (s.command() != null) {
					o.addProperty("command", s.command());
				}
				o.addProperty("argCount", s.argCount());
			} else {
				if (s.url() != null) {
					o.addProperty("url", s.url());
				}
				o.addProperty("urlHasPath", s.urlHasPath());
			}
			if (!s.headerKeys().isEmpty()) {
				JsonArray k = new JsonArray();
				s.headerKeys().forEach(k::add);
				o.add("headerKeys", k);
			}
			JsonArray k = new JsonArray();
			s.envKeys().forEach(k::add);
			o.add("envKeys", k);
			out.add(o);
		}
		return out;
	}

	/**
	 * Staged entries as the DevBridge and logs may show them: env values, every argument and the URL {@link #STAGED}, a
	 * command line cut to its executable, and anything malformed or unknown (a bad name, a field this mod does not send,
	 * a value of the wrong kind) {@link #HIDDEN}. Never a copy of what was typed.
	 */
	public static JsonElement maskEntries(@Nullable JsonElement staged) {
		if (staged == null || staged.isJsonNull()) {
			return JsonNull.INSTANCE;
		}
		if (!staged.isJsonArray()) {
			return new JsonPrimitive(HIDDEN);
		}
		JsonArray out = new JsonArray();
		for (JsonElement x : staged.getAsJsonArray()) {
			if (!x.isJsonObject()) {
				out.add(HIDDEN);
				continue;
			}
			JsonObject o = new JsonObject();
			for (var f : x.getAsJsonObject().entrySet()) {
				String k = f.getKey();
				JsonElement v = f.getValue();
				String text = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
				switch (k) {
					case "name" -> o.addProperty(k, text != null && SERVER_NAME.matcher(text).matches() ? text : HIDDEN);
					case "type" -> o.addProperty(k, text != null && SERVER_TYPES.contains(text) ? text : HIDDEN);
					case "remove" -> o.add(k, v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? v.deepCopy() : new JsonPrimitive(HIDDEN));
					case "env" -> o.add(k, maskPatch(v));
					case "args" -> {
						if (v.isJsonArray()) {
							JsonArray a = new JsonArray();
							for (int i = 0; i < v.getAsJsonArray().size(); i++) {
								a.add(STAGED);
							}
							o.add(k, a);
						} else {
							o.addProperty(k, HIDDEN);
						}
					}
					case "url" -> o.addProperty(k, text != null ? STAGED : HIDDEN);
					case "command" -> {
						String exe = executable(text);
						o.addProperty(k, text == null ? HIDDEN : !exe.equals(text.strip()) ? exe + " " + STAGED : exe);
					}
					default -> o.addProperty(HIDDEN + " " + (o.size() + 1), HIDDEN);
				}
			}
			out.add(o);
		}
		return out;
	}

	private static List<String> strings(JsonObject o, String k) {
		List<String> out = new ArrayList<>();
		if (o.has(k) && o.get(k).isJsonArray()) {
			for (JsonElement a : o.getAsJsonArray(k)) {
				if (a.isJsonPrimitive()) {
					out.add(a.getAsString());
				}
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
