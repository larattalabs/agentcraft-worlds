package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsLogicTest {
	static JsonElement j(String s) {
		return JsonParser.parseString(s);
	}

	static SettingDef def(String json) {
		SettingDef d = SettingDef.parse(j(json));
		assertNotNull(d);
		return d;
	}

	static final String RESULT = """
		{"file":"/home/u/.agentcraft/config.json","settings":[
		 {"key":"claude.maxConcurrent","label":"Max workers","help":"at once","group":"team","type":"int","min":1,"max":16,"value":3,
		  "default":3,"source":"file","live":true},
		 {"key":"claude.prWatch","label":"PR watching","group":"PRs","type":"enum","options":["off","observe","on"],"value":"observe",
		  "default":"observe","source":"flag","live":true,"overriddenBy":"--pr-watch"},
		 {"key":"claude.agents.kit.model","label":"Model","type":"model","options":["claude-opus-4-1","claude-sonnet-4-5","default"],
		  "value":"default","default":"default","source":"default","live":false},
		 {"key":"claude.permissions.allow","type":"stringList","value":["Bash(npm test)"],"default":[],"source":"file","live":true},
		 {"key":"claude.context.mcpServers","type":"map","value":{"github":{"command":"gh-mcp"}},"default":{},"source":"file","live":false},
		 {"key":"weird.thing","type":"color","value":"#fff"},
		 {"label":"no key"}
		]}""";

	@Test
	void parsesSettingDefsAndMcpServers() {
		SettingDef.ConfigView v = SettingDef.ConfigView.parse(j(RESULT).getAsJsonObject());
		assertEquals("/home/u/.agentcraft/config.json", v.file());
		assertEquals(6, v.settings().size(), "the entry without a key is skipped");
		SettingDef mc = v.get("claude.maxConcurrent");
		assertNotNull(mc);
		assertEquals(1.0, mc.min());
		assertEquals(16.0, mc.max());
		assertTrue(mc.live());
		SettingDef pw = v.get("claude.prWatch");
		assertEquals("--pr-watch", pw.overriddenBy());
		assertEquals("flag", pw.source());
		assertEquals(List.of("off", "observe", "on"), pw.options());
		assertEquals(SettingsLogic.PRS, SettingsLogic.groupOf(pw));
		assertEquals(SettingsLogic.TEAM, SettingsLogic.groupOf(v.get("claude.agents.kit.model")));
		assertEquals(SettingsLogic.PERMISSIONS, SettingsLogic.groupOf(v.get("claude.permissions.allow")), "no group: by key");
		assertEquals(List.of(new SettingDef.McpServer("github", "gh-mcp")), v.mcpServers());
		assertTrue(v.get("claude.context.mcpServers").readOnly());
		assertTrue(v.get("weird.thing").readOnly(), "unknown type: shown read-only");
		assertEquals("weird.thing", v.get("weird.thing").label(), "no label: the key");
		assertEquals("kit", SettingsLogic.agentOf("claude.agents.kit.model"));
		assertNull(SettingsLogic.agentOf("claude.workers"));
	}

	@Test
	void mcpServersFromTheResultField() {
		SettingDef.ConfigView v = SettingDef.ConfigView.parse(j("{\"settings\":[],\"mcpServers\":[{\"name\":\"fs\",\"command\":\"npx fs\"},\"plain\"]}")
			.getAsJsonObject());
		assertEquals(List.of(new SettingDef.McpServer("fs", "npx fs"), new SettingDef.McpServer("plain", "")), v.mcpServers());
	}

	@Test
	void stagedDiffDropsEditsBackToCurrentAndRebases() {
		Staged s = new Staged();
		s.set("a", new JsonPrimitive(4), new JsonPrimitive(3));
		s.set("b", new JsonPrimitive(true), new JsonPrimitive(false));
		assertEquals(2, s.size());
		s.set("a", new JsonPrimitive(3.0), new JsonPrimitive(3));
		assertFalse(s.has("a"), "back to the current value (3.0 == 3): no edit");
		assertEquals(new JsonPrimitive(true), s.value("b", new JsonPrimitive(false)));
		assertEquals(new JsonPrimitive(7), s.value("zzz", new JsonPrimitive(7)));
		s.set("c", JsonNull.INSTANCE, new JsonPrimitive("x"));
		assertEquals("[{\"key\":\"b\",\"value\":true},{\"key\":\"c\",\"value\":null}]", s.changes().toString(), "null clears and is sent");
		// another client applied b: the edit goes, c stays
		s.rebase(k -> k.equals("b") ? new JsonPrimitive(true) : new JsonPrimitive("x"));
		assertEquals(List.of("c"), List.copyOf(s.edits().keySet()));
	}

	@Test
	void validatesPerType() {
		SettingDef n = def("{\"key\":\"n\",\"type\":\"int\",\"min\":1,\"max\":16,\"default\":3}");
		assertNull(SettingsLogic.validate(n, new JsonPrimitive(16)));
		assertEquals("at most 16", SettingsLogic.validate(n, new JsonPrimitive(17)));
		assertEquals("at least 1", SettingsLogic.validate(n, new JsonPrimitive(0)));
		assertEquals("a whole number is needed", SettingsLogic.validate(n, new JsonPrimitive(1.5)));
		assertEquals("a value is needed", SettingsLogic.validate(n, JsonNull.INSTANCE));
		SettingDef opt = def("{\"key\":\"t\",\"type\":\"int\"}");
		assertNull(SettingsLogic.validate(opt, JsonNull.INSTANCE), "no default: may be unset");
		SettingDef e = def("{\"key\":\"e\",\"type\":\"enum\",\"options\":[\"off\",\"on\"],\"default\":\"off\"}");
		assertEquals("one of off, on", SettingsLogic.validate(e, new JsonPrimitive("maybe")));
		SettingDef eff = def("{\"key\":\"x\",\"type\":\"effort\",\"default\":\"high\"}");
		assertNull(SettingsLogic.validate(eff, new JsonPrimitive("max")), "effort without options: the built-in levels");
		assertEquals(SettingsLogic.EFFORTS, SettingsLogic.choices(eff));
		SettingDef b = def("{\"key\":\"b\",\"type\":\"bool\",\"default\":false}");
		assertEquals("on or off", SettingsLogic.validate(b, new JsonPrimitive("true")));
		SettingDef leads = def("{\"key\":\"claude.leads\",\"type\":\"agentList\",\"options\":[\"marlow\",\"ines\",\"bram\"],\"default\":[]}");
		assertNull(SettingsLogic.validate(leads, j("[\"marlow\",\"ines\"]")));
		assertTrue(SettingsLogic.validate(leads, j("[\"ines\",\"marlow\"]")).contains("start with marlow"));
		assertEquals("ines is listed twice", SettingsLogic.validate(leads, j("[\"marlow\",\"ines\",\"ines\"]")));
		assertEquals("unknown agent zed", SettingsLogic.validate(leads, j("[\"marlow\",\"zed\"]")));
		SettingDef list = def("{\"key\":\"l\",\"type\":\"stringList\",\"default\":[]}");
		assertEquals("every entry must be text", SettingsLogic.validate(list, j("[\"a\",\" \"]")));
		SettingDef map = def("{\"key\":\"m\",\"type\":\"map\"}");
		assertEquals("read-only here", SettingsLogic.validate(map, j("{}")));
	}

	@Test
	void parsesAndFormatsText() {
		SettingDef n = def("{\"key\":\"n\",\"type\":\"int\",\"default\":3}");
		assertEquals(new JsonPrimitive(12L), SettingsLogic.parseText(n, " 12 ").value());
		assertEquals("not a number: x", SettingsLogic.parseText(n, "x").error());
		assertEquals("a whole number is needed", SettingsLogic.parseText(n, "2.5").error());
		assertEquals("a whole number is needed", SettingsLogic.parseText(n, "").error());
		SettingDef opt = def("{\"key\":\"t\",\"type\":\"int\"}");
		assertEquals(JsonNull.INSTANCE, SettingsLogic.parseText(opt, "").value(), "blank = not set when there is no default");
		SettingDef list = def("{\"key\":\"l\",\"type\":\"stringList\",\"default\":[]}");
		assertEquals(j("[\"a\",\"b c\"]"), SettingsLogic.parseText(list, "a\n\n  b c  \n").value());
		SettingDef s = def("{\"key\":\"s\",\"type\":\"string\"}");
		assertEquals(JsonNull.INSTANCE, SettingsLogic.parseText(s, "  ").value());
		SettingDef sd = def("{\"key\":\"s\",\"type\":\"string\",\"default\":\"\"}");
		assertEquals(new JsonPrimitive("  "), SettingsLogic.parseText(sd, "  ").value());
		assertEquals("on", SettingsLogic.format(sd, new JsonPrimitive(true)));
		assertEquals("3", SettingsLogic.format(sd, new JsonPrimitive(3.0)));
		assertEquals("a, b", SettingsLogic.format(sd, j("[\"a\",\"b\"]")));
		assertEquals("none", SettingsLogic.format(sd, j("[]")));
		assertEquals("not set", SettingsLogic.format(sd, JsonNull.INSTANCE));
		assertEquals("a\nb", SettingsLogic.text(j("[\"a\",\"b\"]")));
		assertEquals("2", SettingsLogic.text(new JsonPrimitive(2.0)));
	}

	@Test
	void wideningChangesNeedAConfirm() {
		assertNotNull(SettingsLogic.widening(SettingsLogic.MODE_KEY, new JsonPrimitive("policy"), new JsonPrimitive("auto")));
		assertNull(SettingsLogic.widening(SettingsLogic.MODE_KEY, new JsonPrimitive("auto"), new JsonPrimitive("policy")), "stricter is fine");
		assertNotNull(SettingsLogic.widening(SettingsLogic.MODE_KEY, new JsonPrimitive("auto"), new JsonPrimitive("bypassPermissions")),
			"an unknown mode counts as the loosest");
		assertNotNull(SettingsLogic.widening(SettingsLogic.MODE_KEY, JsonNull.INSTANCE, new JsonPrimitive("auto")), "unset = policy");
		String deny = SettingsLogic.widening(SettingsLogic.DENY_KEY, j("[\"Bash(rm *)\",\"WebFetch\"]"), j("[\"WebFetch\"]"));
		assertEquals("Remove deny rule Bash(rm *)", deny);
		assertNull(SettingsLogic.widening(SettingsLogic.DENY_KEY, j("[\"a\"]"), j("[\"a\",\"b\"]")), "adding a deny rule is stricter");
		assertTrue(SettingsLogic.widening(SettingsLogic.ALLOW_KEY, j("[]"), j("[\"Bash(npm test)\"]")).startsWith("Add allow rule Bash(npm test)"));
		assertNull(SettingsLogic.widening(SettingsLogic.ALLOW_KEY, j("[\"a\",\"b\"]"), j("[\"a\"]")), "removing an allow rule is stricter");
		assertNotNull(SettingsLogic.widening(SettingsLogic.LOGIN_KEY, new JsonPrimitive(false), new JsonPrimitive(true)));
		assertNotNull(SettingsLogic.widening(SettingsLogic.LOGIN_KEY, new JsonPrimitive(true), new JsonPrimitive(false)), "any change");
		assertNull(SettingsLogic.widening("claude.prWatch", new JsonPrimitive("off"), new JsonPrimitive("on")));
		List<String> w = SettingsLogic.widenings(Map.of(SettingsLogic.ALLOW_KEY, j("[\"x\"]")), k -> j("[]"));
		assertEquals(1, w.size());
	}

	@Test
	void readsTheApplyAck() {
		SettingsLogic.ApplyResult r = SettingsLogic.ApplyResult.parse(j("{\"applied\":[\"a\",\"b\"],\"restartRequired\":[\"b\"],"
			+ "\"overridden\":[{\"key\":\"a\",\"by\":\"--workers\"}]}").getAsJsonObject());
		assertEquals(List.of("a", "b"), r.applied());
		assertEquals(List.of("b"), r.restartRequired());
		assertEquals(Map.of("a", "--workers"), r.overridden());
		assertTrue(SettingsLogic.ApplyResult.parse(null).applied().isEmpty());
	}

	@Test
	void fieldErrorsFromResultOrText() {
		JsonObject res = j("{\"errors\":[{\"key\":\"claude.maxConcurrent\",\"error\":\"at most 16\"},{\"message\":\"file is locked\"}]}")
			.getAsJsonObject();
		Map<String, String> m = SettingsLogic.fieldErrors("ignored", res, List.of());
		assertEquals("at most 16", m.get("claude.maxConcurrent"));
		assertEquals("file is locked", m.get(""));
		List<String> keys = List.of("claude.maxConcurrent", "claude.maxConcurrentTurns", "pr.draft");
		Map<String, String> t = SettingsLogic.fieldErrors("claude.maxConcurrentTurns: must be >= 1; pr.draft must be a boolean; disk full", null,
			keys);
		assertEquals("must be >= 1", t.get("claude.maxConcurrentTurns"));
		assertNull(t.get("claude.maxConcurrent"), "the longer key wins");
		assertEquals("must be a boolean", t.get("pr.draft"));
		assertEquals("disk full", t.get(""));
	}

	@Test
	void groupsAndTeamKeys() {
		assertTrue(SettingsLogic.isTeamKey("claude.taskModels.small"));
		assertTrue(SettingsLogic.isTeamKey("claude.workers"));
		assertFalse(SettingsLogic.isTeamKey("claude.prWatch"));
		assertEquals(SettingsLogic.USAGE, SettingsLogic.groupOf(def("{\"key\":\"claude.useClaudeLogin\",\"type\":\"bool\"}")));
		assertEquals(SettingsLogic.GENERAL, SettingsLogic.groupOf(def("{\"key\":\"userName\",\"type\":\"string\"}")));
		assertEquals(SettingsLogic.CONTEXT, SettingsLogic.groupOf(def("{\"key\":\"x\",\"group\":\"Context\",\"type\":\"string\"}")));
		assertEquals("PRs", SettingsLogic.groupLabel(SettingsLogic.PRS));
		assertEquals(0, SettingsLogic.modeRank("policy"));
		assertEquals(2, SettingsLogic.modeRank("acceptEdits"));
	}
}
