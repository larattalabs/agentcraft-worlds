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
		SettingDef env = def("{\"key\":\"env\",\"type\":\"secretMap\",\"value\":{\"NODE_PATH\":\"(set)\",\"API_KEY\":\"(set)\"},\"default\":{}}");
		assertFalse(env.readOnly(), "secret maps are edited (wave 3 S1)");
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
		assertEquals(j("{\"API_KEY\":\"(set)\",\"EXTRA\":\"(set)\"}"), SecretSettings.appliedView(cur, p));
		SettingDef env = def("{\"key\":\"env\",\"type\":\"secretMap\",\"value\":{\"NODE_PATH\":\"(set)\",\"API_KEY\":\"(set)\"}}");
		assertEquals(j("{\"API_KEY\":\"(set)\",\"EXTRA\":\"(set)\"}"), SettingsLogic.applied(env, p));
		assertEquals(j("{}"), SecretSettings.appliedView(cur, com.google.gson.JsonNull.INSTANCE), "null removes them all");
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
	}

	// ------------------------------------------------------------------ MCP servers (S2)

	static final String VIEW = "[{\"name\":\"fs\",\"type\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"fs-mcp\",\"--token\",\"(hidden)\"],"
		+ "\"envKeys\":[\"ROOT\"]},{\"name\":\"docs\",\"type\":\"http\",\"url\":\"https://docs.contoso.example/mcp\",\"envKeys\":[]}]";

	@Test
	void mcpServersParseAndAreEditable() {
		SettingDef d = def("{\"key\":\"claude.context.mcpServers\",\"type\":\"mcpServers\",\"group\":\"context\",\"value\":" + VIEW
			+ ",\"default\":[],\"live\":false}");
		assertFalse(d.readOnly());
		List<Server> s = SecretSettings.servers(d.value());
		assertEquals(2, s.size());
		assertEquals("npx -y fs-mcp --token (hidden)", s.get(0).target());
		assertEquals(List.of("ROOT"), s.get(0).envKeys());
		assertEquals("http", s.get(1).type());
		assertEquals("fs, docs", SettingsLogic.format(d, d.value()));
		// the old read-only list still reads the new shape
		assertEquals(List.of(new SettingDef.McpServer("fs", "npx"), new SettingDef.McpServer("docs", "https://docs.contoso.example/mcp")),
			SettingDef.ConfigView.parse(j("{\"settings\":[],\"mcpServers\":" + VIEW + "}").getAsJsonObject()).mcpServers());
	}

	@Test
	void entriesUpsertRemoveAndShowInTheList() {
		List<Server> cur = SecretSettings.servers(j(VIEW));
		Server fs = cur.get(0);
		// edit fs: one env variable replaced; its args go back as shown ("(hidden)" stays: the Foreman keeps the original)
		JsonObject env = SecretSettings.withVar(null, fs.envKeys(), "ROOT", "/srv/notes");
		JsonArray staged = SecretSettings.withEntry(null, SecretSettings.entry(fs, env));
		staged = SecretSettings.withEntry(staged, SecretSettings.removal("docs"));
		Server added = new Server("pocket-notes", "sse", null, List.of(), "https://notes.contoso.example/sse", List.of());
		staged = SecretSettings.withEntry(staged, SecretSettings.entry(added, null));
		assertEquals(j("[{\"name\":\"fs\",\"type\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"fs-mcp\",\"--token\",\"(hidden)\"],"
			+ "\"env\":{\"ROOT\":\"/srv/notes\"}},{\"name\":\"docs\",\"remove\":true},{\"name\":\"pocket-notes\",\"type\":\"sse\","
			+ "\"url\":\"https://notes.contoso.example/sse\"}]"), staged);
		List<Row> rows = SecretSettings.rows(cur, staged);
		assertEquals(3, rows.size());
		assertTrue(rows.get(0).edited());
		assertEquals(List.of("ROOT"), rows.get(0).server().envKeys());
		assertTrue(rows.get(1).removed());
		assertTrue(rows.get(2).added());
		// a second edit of the same server replaces its entry
		JsonArray again = SecretSettings.withEntry(staged, SecretSettings.entry(fs, null));
		assertEquals(3, again.size());
		assertFalse(again.get(0).getAsJsonObject().has("env"));
		assertNull(SettingsLogic.validate(def("{\"key\":\"claude.context.mcpServers\",\"type\":\"mcpServers\",\"value\":" + VIEW + "}"), staged));
		// applied: names and envKeys only
		JsonArray view = SecretSettings.appliedServers(cur, staged);
		assertEquals(2, view.size());
		assertFalse(view.toString().contains("/srv/notes"));
		assertEquals("pocket-notes", view.get(1).getAsJsonObject().get("name").getAsString());
		// undo
		assertEquals(2, SecretSettings.without(staged, "docs").size());
		// masked for the DevBridge
		assertFalse(SecretSettings.maskEntries(staged).toString().contains("/srv/notes"));
	}

	@Test
	void serversAreCheckedLikeTheForemanDoes() {
		List<String> names = List.of("fs", "docs");
		assertNull(SecretSettings.serverProblem(new Server("notes", "stdio", "node server.js", List.of(), null, List.of()), null, true, names));
		assertNotNull(SecretSettings.serverProblem(new Server("fs", "stdio", "x", List.of(), null, List.of()), null, true, names), "taken");
		assertNull(SecretSettings.serverProblem(new Server("fs", "stdio", "x", List.of(), null, List.of()), null, false, names), "an edit");
		assertNotNull(SecretSettings.serverProblem(new Server("bad name", "stdio", "x", List.of(), null, List.of()), null, true, names));
		assertNotNull(SecretSettings.serverProblem(new Server("AgentCraft", "stdio", "x", List.of(), null, List.of()), null, true, names));
		assertNotNull(SecretSettings.serverProblem(new Server("n", "stdio", " ", List.of(), null, List.of()), null, true, names), "no command");
		assertNotNull(SecretSettings.serverProblem(new Server("n", "stdio", "x", List.of(), null, List.of()), j("{\"1A\":\"v\"}"), true, names));
		assertNull(SecretSettings.serverProblem(new Server("n", "http", null, List.of(), "https://h.contoso.example/mcp", List.of()), null, true,
			names));
		assertNotNull(SecretSettings.urlProblem("https://user:pw@h.contoso.example/mcp"), "credentials");
		assertNotNull(SecretSettings.urlProblem("https://h.contoso.example/mcp?key=1"), "query");
		assertNotNull(SecretSettings.urlProblem("https://h.contoso.example/mcp#x"), "fragment");
		assertNotNull(SecretSettings.urlProblem("ftp://h.contoso.example/"), "not http");
		assertNotNull(SecretSettings.urlProblem("not a url"));
		SettingDef d = def("{\"key\":\"claude.context.mcpServers\",\"type\":\"mcpServers\",\"value\":" + VIEW + "}");
		assertNotNull(SettingsLogic.validate(d, j("[{\"name\":\"ghost\",\"remove\":true}]")), "no such server");
		assertNotNull(SettingsLogic.validate(d, j("[{\"name\":\"fs\",\"remove\":true},{\"name\":\"fs\",\"remove\":true}]")), "twice");
		assertNotNull(SettingsLogic.validate(d, j("[{\"name\":\"n\",\"command\":\"x\"}]")), "no type");
	}
}
