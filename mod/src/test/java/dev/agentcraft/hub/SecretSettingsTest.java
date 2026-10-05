package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.hub.SecretSettings.Row;
import dev.agentcraft.hub.SecretSettings.Server;
import dev.agentcraft.hub.SecretSettings.Var;
import dev.agentcraft.hub.SecretSettings.VarState;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretSettingsTest {
	static JsonElement j(String s) {
		return JsonParser.parseString(s);
	}

	static SettingDef def(String json) {
		return SettingDef.parse(j(json));
	}

	// ------------------------------------------------------------------ secret maps (S1)

	@Test
	void secretMapsAreEditableAndShowNamesOnly() {
		SettingDef env = def("{\"key\":\"env\",\"type\":\"secretMap\",\"value\":[\"NODE_PATH\",\"API_KEY\"],\"default\":{}}");
		assertFalse(env.readOnly(), "secret maps are edited (wave 3 S1)");
		// an older Foreman's {NAME: "(set)"} view still reads
		assertEquals(List.of("NODE_PATH", "API_KEY"), SecretSettings.keys(j("{\"NODE_PATH\":\"(set)\",\"API_KEY\":\"(set)\"}")));
		assertTrue(env.secret());
		assertTrue(def("{\"key\":\"m\",\"type\":\"map\",\"value\":{}}").readOnly(), "plain maps stay read-only");
		assertEquals("NODE_PATH, API_KEY", SettingsLogic.format(env, env.value()));
		// a staged update never shows its values, even through format
		assertEquals("NODE_PATH", SettingsLogic.format(env, j("{\"NODE_PATH\":\"s3cret\"}")));
	}

	@Test
	void stagingSetsReplacesRemovesAndUndoes() {
		List<String> cur = List.of("NODE_PATH", "API_KEY");
		JsonObject p = SecretSettings.withVar(null, cur, "API_KEY", "new-key");
		p = SecretSettings.withVar(p, cur, "NODE_PATH", null);
		p = SecretSettings.withVar(p, cur, "EXTRA", "x");
		assertEquals(List.of(new Var("NODE_PATH", VarState.REMOVED), new Var("API_KEY", VarState.REPLACED), new Var("EXTRA", VarState.NEW)),
			SecretSettings.vars(cur, p));
		assertEquals(j("{\"API_KEY\":\"new-key\",\"NODE_PATH\":null,\"EXTRA\":\"x\"}"), p);
		// removing a variable that is only staged drops it from the update
		JsonObject q = SecretSettings.withVar(p, cur, "EXTRA", null);
		assertFalse(q.has("EXTRA"));
		q = SecretSettings.undo(q, "API_KEY");
		q = SecretSettings.undo(q, "NODE_PATH");
		assertTrue(q.isEmpty(), "nothing left to stage");
		// after Apply the current value is names only, in order, new ones last
		assertEquals(j("[\"API_KEY\",\"EXTRA\"]"), SecretSettings.appliedView(cur, p));
		SettingDef env = def("{\"key\":\"env\",\"type\":\"secretMap\",\"value\":[\"NODE_PATH\",\"API_KEY\"]}");
		assertEquals(j("[\"API_KEY\",\"EXTRA\"]"), SettingsLogic.applied(env, p));
		assertEquals(j("[]"), SecretSettings.appliedView(cur, com.google.gson.JsonNull.INSTANCE), "null removes them all");
	}

	@Test
	void stagedValuesAreMaskedForTheDevBridge() {
		SettingDef env = def("{\"key\":\"env\",\"type\":\"secretMap\",\"value\":{}}");
		JsonElement m = SettingsLogic.masked(env, j("{\"API_KEY\":\"sk-live-123\",\"OLD\":null}"));
		assertEquals(j("{\"API_KEY\":\"(staged)\",\"OLD\":null}"), m);
		assertFalse(m.toString().contains("sk-live"));
		SettingDef plain = def("{\"key\":\"userName\",\"type\":\"string\",\"value\":\"Sam\"}");
		assertEquals(j("\"Sam\""), SettingsLogic.masked(plain, j("\"Sam\"")));
	}

	@Test
	void variableNamesFollowTheForemansRules() {
		assertNull(SecretSettings.nameProblem("NODE_PATH", true));
		assertNull(SecretSettings.nameProblem("_x1", false));
		assertNotNull(SecretSettings.nameProblem("1ABC", false));
		assertNotNull(SecretSettings.nameProblem("A-B", false));
		assertNotNull(SecretSettings.nameProblem("", false));
		assertNotNull(SecretSettings.nameProblem("GIT_DIR", true), "a repository refuses git's variables");
		assertNull(SecretSettings.nameProblem("GIT_DIR", false), "an MCP server may have them");
		assertNotNull(SecretSettings.nameProblem("AGENTCRAFT_CLIENT_TOKEN", false));
		SettingDef env = def("{\"key\":\"env\",\"type\":\"secretMap\",\"value\":{}}");
		assertNull(SettingsLogic.validate(env, j("{\"A\":\"1\",\"B\":null}")));
		assertNotNull(SettingsLogic.validate(env, j("{\"A\":1}")));
		assertNotNull(SettingsLogic.validate(env, j("{\"GIT_SSH\":\"x\"}")));
		assertNotNull(SettingsLogic.validate(env, j("[\"A\"]")));
		// a placeholder or a control character is never sent as a value (the Foreman refuses it too)
		assertNotNull(SettingsLogic.validate(env, j("{\"A\":\"(set)\"}")));
		assertNotNull(SettingsLogic.validate(env, j("{\"A\":\" (Hidden) \"}")));
		assertNotNull(SettingsLogic.validate(env, j("{\"A\":\"x\\u0007\"}")));
		assertNull(SettingsLogic.validate(env, j("{\"A\":\"line\\nline\"}")));
	}

	// ------------------------------------------------------------------ MCP servers (S2)

	static final String VIEW = "[{\"name\":\"fs\",\"type\":\"stdio\",\"command\":\"npx\",\"argCount\":4,\"envKeys\":[\"ROOT\"]},"
		+ "{\"name\":\"docs\",\"type\":\"http\",\"url\":\"https://docs.contoso.example\",\"urlHasPath\":true,\"headerKeys\":[\"Authorization\"],\"envKeys\":[]}]";

	static Server stdio(String name, String command, int args) {
		return new Server(name, "stdio", command, args, null, false, List.of(), List.of());
	}

	@Test
	void mcpServersShowNoArgumentOrUrlPath() {
		SettingDef d = def("{\"key\":\"claude.context.mcpServers\",\"type\":\"mcpServers\",\"group\":\"context\",\"value\":" + VIEW
			+ ",\"default\":[],\"live\":false}");
		assertFalse(d.readOnly());
		List<Server> s = SecretSettings.servers(d.value());
		assertEquals(2, s.size());
		assertEquals("npx + 4 arguments", s.get(0).target());
		assertEquals(List.of("ROOT"), s.get(0).envKeys());
		assertEquals("http", s.get(1).type());
		assertEquals("https://docs.contoso.example/…", s.get(1).target());
		assertEquals(List.of("Authorization"), s.get(1).headerKeys());
		assertEquals("fs, docs", SettingsLogic.format(d, d.value()));
		assertEquals("node", SecretSettings.executable(" node /srv/x.js --token t "));
		assertEquals("https://h.example:8443", SecretSettings.origin("HTTPS://H.Example:8443/mcp/secret?k=v"));
	}

	@Test
	void entriesSendOnlyWhatChanged() {
		List<Server> cur = SecretSettings.servers(j(VIEW));
		Server fs = cur.get(0);
		// edit fs: one env variable replaced; command, args and url left out (the Foreman keeps them exactly)
		JsonObject env = SecretSettings.withVar(null, fs.envKeys(), "ROOT", "/srv/notes");
		JsonArray staged = SecretSettings.withEntry(null, SecretSettings.entry("fs", "stdio", null, null, null, env));
		staged = SecretSettings.withEntry(staged, SecretSettings.removal("docs"));
		staged = SecretSettings.withEntry(staged, SecretSettings.entry("pocket-notes", "sse", null, null, "https://notes.contoso.example/sse/k3y", null));
		assertEquals(j("[{\"name\":\"fs\",\"type\":\"stdio\",\"env\":{\"ROOT\":\"/srv/notes\"}},{\"name\":\"docs\",\"remove\":true},"
			+ "{\"name\":\"pocket-notes\",\"type\":\"sse\",\"url\":\"https://notes.contoso.example/sse/k3y\"}]"), staged);
		List<Row> rows = SecretSettings.rows(cur, staged);
		assertEquals(3, rows.size());
		assertTrue(rows.get(0).edited());
		assertFalse(rows.get(0).argsReplaced());
		assertEquals(4, rows.get(0).server().argCount(), "kept");
		assertEquals("npx", rows.get(0).server().command());
		assertTrue(rows.get(1).removed());
		assertTrue(rows.get(2).added());
		assertEquals("https://notes.contoso.example/…", rows.get(2).server().target());
		// replacing the arguments: the complete new list, counted, never shown
		JsonArray again = SecretSettings.withEntry(staged, SecretSettings.entry("fs", "stdio", null, List.of("--token", "t0k3n-value"), null, null));
		assertEquals(3, again.size());
		assertFalse(again.get(0).getAsJsonObject().has("env"));
		Row r = SecretSettings.rows(cur, again).get(0);
		assertTrue(r.argsReplaced());
		assertEquals(2, r.server().argCount());
		assertNull(SettingsLogic.validate(def("{\"key\":\"claude.context.mcpServers\",\"type\":\"mcpServers\",\"value\":" + VIEW + "}"), again));
		// applied: names, counts and origins only
		JsonArray view = SecretSettings.appliedServers(cur, again);
		assertEquals(2, view.size());
		assertEquals(j("{\"name\":\"fs\",\"type\":\"stdio\",\"command\":\"npx\",\"argCount\":2,\"envKeys\":[\"ROOT\"]}"), view.get(0));
		assertEquals(j("{\"name\":\"pocket-notes\",\"type\":\"sse\",\"url\":\"https://notes.contoso.example\",\"urlHasPath\":true,\"envKeys\":[]}"), view.get(1));
		for (String secret : List.of("/srv/notes", "t0k3n-value", "k3y")) {
			assertFalse(view.toString().contains(secret), secret);
			assertFalse(SecretSettings.maskEntries(again).toString().contains(secret), "masked for the DevBridge: " + secret);
		}
		assertFalse(SettingsLogic.masked(def("{\"key\":\"claude.context.mcpServers\",\"type\":\"mcpServers\",\"value\":[]}"), again).toString().contains("t0k3n"));
		// a command line is masked down to its executable
		JsonElement m = SecretSettings.maskEntries(SecretSettings.withEntry(null, SecretSettings.entry("x", "stdio", "node /srv/x.js --token abc", null, null, null)));
		assertEquals("node (staged)", m.getAsJsonArray().get(0).getAsJsonObject().get("command").getAsString());
		// undo
		assertEquals(2, SecretSettings.without(staged, "docs").size());
	}

	@Test
	void placeholdersAreNeverSentAsArguments() {
		assertNull(SecretSettings.argsProblem(List.of("-y", "fs-mcp", "--token", "a-new-token")));
		assertNull(SecretSettings.argsProblem(null), "left out: kept");
		assertNotNull(SecretSettings.argsProblem(List.of("--token", "(hidden)")));
		assertNotNull(SecretSettings.argsProblem(List.of("--api-key=(hidden)")));
		assertNotNull(SecretSettings.argsProblem(List.of("(staged)")));
		assertNotNull(SecretSettings.argsProblem(List.of("a\u0000b")));
		SettingDef d = def("{\"key\":\"claude.context.mcpServers\",\"type\":\"mcpServers\",\"value\":" + VIEW + "}");
		assertNotNull(SettingsLogic.validate(d, j("[{\"name\":\"fs\",\"type\":\"stdio\",\"args\":[\"--token\",\"(hidden)\"]}]")));
		assertNull(SettingsLogic.validate(d, j("[{\"name\":\"fs\",\"type\":\"stdio\",\"args\":[\"--token\",\"new\"]}]")));
	}

	@Test
	void serversAreCheckedLikeTheForemanDoes() {
		List<String> names = List.of("fs", "docs");
		List<Server> cur = SecretSettings.servers(j(VIEW));
		Server fs = cur.get(0);
		Server docs = cur.get(1);
		assertNull(SecretSettings.serverProblem("notes", "stdio", "node server.js", null, null, null, null, true, names));
		assertNotNull(SecretSettings.serverProblem("fs", "stdio", "x", null, null, null, null, true, names), "taken");
		assertNull(SecretSettings.serverProblem("fs", "stdio", null, null, null, null, fs, false, names), "an edit keeps the command");
		assertNotNull(SecretSettings.serverProblem("bad name", "stdio", "x", null, null, null, null, true, names));
		assertNotNull(SecretSettings.serverProblem("AgentCraft", "stdio", "x", null, null, null, null, true, names));
		assertNotNull(SecretSettings.serverProblem("n", "stdio", " ", null, null, null, null, true, names), "no command");
		assertNotNull(SecretSettings.serverProblem("n", "stdio", null, null, null, null, null, true, names), "a new server needs a command");
		assertNotNull(SecretSettings.serverProblem("docs", "stdio", null, null, null, null, docs, false, names), "http -> stdio needs a command");
		assertNotNull(SecretSettings.serverProblem("fs", "http", null, null, null, null, fs, false, names), "stdio -> http needs a url");
		assertNull(SecretSettings.serverProblem("docs", "sse", null, null, null, null, docs, false, names), "http -> sse keeps the url");
		assertNotNull(SecretSettings.serverProblem("n", "stdio", "x", null, null, j("{\"1A\":\"v\"}"), null, true, names));
		assertNull(SecretSettings.serverProblem("n", "http", null, null, "https://h.contoso.example/mcp?key=1", null, null, true, names), "a query is fine now");
		assertNotNull(SecretSettings.urlProblem("https://user:pw@h.contoso.example/mcp"), "credentials");
		assertNotNull(SecretSettings.urlProblem("https://h.contoso.example/mcp#x"), "fragment");
		assertNotNull(SecretSettings.urlProblem("https://h.contoso.example/a b"), "space");
		assertNotNull(SecretSettings.urlProblem("https://h.contoso.example/a\nb"), "newline");
		assertNotNull(SecretSettings.urlProblem("ftp://h.contoso.example/"), "not http");
		assertNotNull(SecretSettings.urlProblem("not a url"));
		SettingDef d = def("{\"key\":\"claude.context.mcpServers\",\"type\":\"mcpServers\",\"value\":" + VIEW + "}");
		assertNotNull(SettingsLogic.validate(d, j("[{\"name\":\"ghost\",\"remove\":true}]")), "no such server");
		assertNotNull(SettingsLogic.validate(d, j("[{\"name\":\"fs\",\"remove\":true},{\"name\":\"fs\",\"remove\":true}]")), "twice");
		assertNotNull(SettingsLogic.validate(d, j("[{\"name\":\"n\",\"command\":\"x\"}]")), "no type");
		assertNull(stdio("n", "x", 0).url());
		// an added server edited again: its own staged name is no clash
		assertNull(SecretSettings.serverProblem("notes", "stdio", "node", null, null, null, null, false, List.of("fs", "docs", "notes")));
	}

	@Test
	void theFormSendsOnlyWhatChangedAndKeepsWhatAnEarlierDoneStaged() {
		List<Server> cur = SecretSettings.servers(j(VIEW));
		Server fs = cur.get(0);
		Server docs = cur.get(1);
		// an untouched edit: nothing sent (all kept)
		assertEquals(new SecretSettings.Send(null, null, null), SecretSettings.toSend("stdio", fs, null, "npx", "npx", false, List.of(), false, ""));
		// a changed command, replaced arguments (empty clears them)
		assertEquals(new SecretSettings.Send("uvx", null, null), SecretSettings.toSend("stdio", fs, null, "uvx", "npx", false, List.of(), false, ""));
		assertEquals(new SecretSettings.Send(null, List.of(), null), SecretSettings.toSend("stdio", fs, null, "npx", "npx", true, List.of(), false, ""));
		// a new server: what the fields hold
		assertEquals(new SecretSettings.Send("node", List.of("a.js"), null), SecretSettings.toSend("stdio", null, null, "node", "", false, List.of("a.js"), false, ""));
		// an added server opened again and Done without changes: its staged command line and args stay (the field shows only the executable)
		JsonObject added = SecretSettings.entry("notes", "stdio", "node /srv/n.js --token t", List.of("--k", "v"), null, null);
		assertEquals(new SecretSettings.Send("node /srv/n.js --token t", List.of("--k", "v"), null), SecretSettings.toSend("stdio", null, added, "node", "node", false,
			List.of(), false, ""));
		// a retyped server (http -> stdio) opened again: the same
		JsonObject retyped = SecretSettings.entry("docs", "stdio", "docs-mcp --key x", List.of("--y"), null, null);
		assertEquals(new SecretSettings.Send("docs-mcp --key x", List.of("--y"), null), SecretSettings.toSend("stdio", docs, retyped, "docs-mcp", "docs-mcp", false,
			List.of(), false, ""));
		// a staged URL stays unless replaced; switching kind ignores the other kind's staged parts
		JsonObject url = SecretSettings.entry("docs", "http", null, null, "https://docs.contoso.example/v2/k", null);
		assertEquals(new SecretSettings.Send(null, null, "https://docs.contoso.example/v2/k"), SecretSettings.toSend("http", docs, url, "", "", false, List.of(), false, ""));
		assertEquals(new SecretSettings.Send(null, null, "https://n.example/x"), SecretSettings.toSend("http", docs, url, "", "", false, List.of(), true, "https://n.example/x"));
		assertEquals(new SecretSettings.Send("", List.of(), null), SecretSettings.toSend("stdio", docs, url, "", "", false, List.of(), false, ""));
		assertNull(SecretSettings.stagedArgs(url, "stdio"));
	}

	@Test
	void maskingNeverCopiesWhatWasTypedAndMessagesNeverQuoteIt() {
		// malformed / unknown values are hidden, not copied
		assertEquals(j("\"(hidden)\""), SecretSettings.maskPatch(j("\"API_KEY=hunter2-a\"")));
		assertEquals(j("\"(hidden)\""), SecretSettings.maskPatch(j("[\"hunter2-b\"]")));
		JsonElement p = SecretSettings.maskPatch(j("{\"OK\":\"hunter2-c\",\"API_KEY=hunter2-d\":null,\"N\":5,\"O\":{\"x\":\"hunter2-e\"}}"));
		assertEquals(j("{\"OK\":\"(staged)\",\"(hidden) 2\":null,\"N\":\"(hidden)\",\"O\":\"(hidden)\"}"), p);
		JsonElement m = SecretSettings.maskEntries(j("[\"hunter2-f\",{\"name\":\"hunter2 g\",\"type\":\"hunter2-h\",\"args\":\"hunter2-i\",\"url\":5,"
			+ "\"command\":\"node --token hunter2-j\",\"hunter2-k\":\"hunter2-l\",\"remove\":\"hunter2-m\",\"env\":[\"hunter2-n\"]}]"));
		assertFalse(m.toString().contains("hunter2"), m.toString());
		assertEquals(j("\"(hidden)\""), SecretSettings.maskEntries(j("{\"name\":\"hunter2-o\"}")));
		// validation names places, never names
		String why = SecretSettings.validatePatch(j("{\"GOOD\":\"x\",\"API_KEY=hunter2-p\":\"v\",\"GIT_DIR\":\"v\"}"), true);
		assertEquals("variable 2: not a variable name (letters, digits, _); variable 3: git variables cannot be set for a repository", why);
		String entries = SecretSettings.validateEntries(j("[{\"name\":\"hunter2-q\",\"remove\":true},{\"name\":\"bad name hunter2\",\"type\":\"stdio\",\"command\":\"x\"}]"),
			SecretSettings.servers(j(VIEW)));
		assertNotNull(entries);
		assertFalse(entries.contains("hunter2"), entries);
		assertTrue(entries.startsWith("server 1: no such MCP server"), entries);
		assertFalse(String.valueOf(SecretSettings.nameProblem("hunter2-r=x", false)).contains("hunter2"));
	}
}
