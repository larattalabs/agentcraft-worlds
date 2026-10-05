package dev.agentcraft.client.foreman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The generated {@link Protocol} against the Foreman's own protocol examples (foreman/src/protocol-examples.ts, dumped
 * to src/test/resources/protocol-examples.json by {@code npm run gen:java-protocol}): every example decodes into its
 * record with no field lost or changed when written back, and no enum value falls back to UNKNOWN.
 */
class ProtocolExamplesTest {
	private static JsonObject examples() throws Exception {
		try (Reader r = new InputStreamReader(Objects.requireNonNull(ProtocolExamplesTest.class.getResourceAsStream("/protocol-examples.json"),
			"protocol-examples.json (npm run gen:java-protocol)"), StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r).getAsJsonObject();
		}
	}

	@Test
	void serverExamplesRoundTrip() throws Exception {
		roundTrip(examples().getAsJsonObject("server"), Protocol.SERVER_MESSAGES);
	}

	@Test
	void clientExamplesRoundTrip() throws Exception {
		roundTrip(examples().getAsJsonObject("client"), Protocol.CLIENT_MESSAGES);
	}

	private static void roundTrip(JsonObject byType, Map<String, Class<? extends Record>> registry) {
		assertEquals(new TreeSet<>(registry.keySet()), new TreeSet<>(byType.keySet()), "one example per message type");
		for (Map.Entry<String, JsonElement> e : byType.entrySet()) {
			String type = e.getKey();
			JsonObject body = e.getValue().getAsJsonObject().deepCopy();
			assertEquals(type, body.get("type").getAsString());
			body.remove("v");
			body.remove("type");
			body.remove("id");
			Record msg = ForemanJson.GSON.fromJson(body, registry.get(type));
			assertNotNull(msg, type);
			subset(body, ForemanJson.GSON.toJsonTree(msg), type);
			List<String> unknown = new ArrayList<>();
			unknownEnums(msg, type, unknown);
			assertTrue(unknown.isEmpty(), "enum values the Java mirror does not know: " + unknown);
		}
	}

	/** Every field of {@code in} is in {@code out} with the same value (out may add defaults for missing fields). */
	private static void subset(JsonElement in, JsonElement out, String path) {
		if (in.isJsonNull()) {
			return;
		}
		if (out == null || out.isJsonNull()) {
			fail(path + ": lost (" + in + ")");
		}
		if (in.isJsonObject()) {
			assertTrue(out.isJsonObject(), path + ": not an object: " + out);
			for (Map.Entry<String, JsonElement> e : in.getAsJsonObject().entrySet()) {
				subset(e.getValue(), out.getAsJsonObject().get(e.getKey()), path + "." + e.getKey());
			}
		} else if (in.isJsonArray()) {
			assertTrue(out.isJsonArray(), path + ": not an array: " + out);
			JsonArray a = in.getAsJsonArray();
			JsonArray b = out.getAsJsonArray();
			assertEquals(a.size(), b.size(), path + ": size");
			for (int i = 0; i < a.size(); i++) {
				subset(a.get(i), b.get(i), path + "[" + i + "]");
			}
		} else if (in.getAsJsonPrimitive().isNumber()) {
			assertTrue(out.isJsonPrimitive() && out.getAsJsonPrimitive().isNumber(), path + ": not a number: " + out);
			assertEquals(in.getAsDouble(), out.getAsDouble(), 0.0, path);
		} else {
			assertEquals(in, out, path);
		}
	}

	private static void unknownEnums(Object o, String path, List<String> found) {
		if (o instanceof Enum<?> e) {
			if (e.name().equals("UNKNOWN") && !(o instanceof Protocol.CiStatus) && !(o instanceof Protocol.AuthStatus)) {
				found.add(path);
			}
		} else if (o instanceof Record r) {
			for (RecordComponent c : r.getClass().getRecordComponents()) {
				try {
					unknownEnums(c.getAccessor().invoke(r), path + "." + c.getName(), found);
				} catch (ReflectiveOperationException ex) {
					throw new AssertionError(ex);
				}
			}
		} else if (o instanceof List<?> l) {
			for (int i = 0; i < l.size(); i++) {
				unknownEnums(l.get(i), path + "[" + i + "]", found);
			}
		} else if (o instanceof Map<?, ?> m) {
			m.forEach((k, v) -> unknownEnums(v, path + "." + k, found));
		}
	}

	@Test
	void toleratesNewerAndOlderForemen() {
		JsonObject a = JsonParser.parseString("""
			{"id":"kit","name":"Kit","role":"worker","color":"#2E78C6","skin":"kit","state":"dancing","activity":"x",
			 "station":"desk","paused":false,"active":true,"futureField":{"a":1}}""").getAsJsonObject();
		Protocol.Agent agent = ForemanJson.read(a, Protocol.Agent.class);
		assertEquals(Protocol.AgentState.UNKNOWN, agent.state(), "unknown enum value -> UNKNOWN");
		assertEquals("idle", agent.state().family());

		Protocol.Task t = ForemanJson.read(JsonParser.parseString("{\"id\":\"t1\",\"status\":\"doing\"}"), Protocol.Task.class);
		assertEquals("t1", t.title(), "missing title -> id");
		assertEquals(List.of(), t.deps(), "missing required list -> empty");
		assertEquals(Protocol.CiStatus.UNKNOWN, t.ci());
		assertNull(t.pr());

		Protocol.Snapshot s = ForemanJson.read(JsonParser.parseString("{\"foreman\":{\"backend\":\"sim\",\"auth\":\"ok\"}}"),
			Protocol.Snapshot.class);
		assertNull(s.leads(), "leads stays null on a Foreman from before leads per building");
		assertEquals("?", s.foreman().version());
		assertTrue(s.agents().isEmpty());
	}

	@Test
	void camelCaseWireValues() {
		JsonObject def = JsonParser.parseString("""
			{"key":"claude.leads","label":"Leads","help":"h","group":"team","type":"agentList","value":["marlow"],
			 "default":[],"source":"default","live":false}""").getAsJsonObject();
		Protocol.SettingDef d = ForemanJson.read(def, Protocol.SettingDef.class);
		assertEquals(Protocol.SettingType.AGENT_LIST, d.type());
		assertEquals("agentList", ForemanJson.GSON.toJsonTree(d).getAsJsonObject().get("type").getAsString());
		assertTrue(d.defaultValue().isJsonArray(), "the keyword field default is read through @SerializedName");
		assertEquals(Protocol.SettingType.MCP_SERVERS, ForemanJson.GSON.fromJson("\"mcpServers\"", Protocol.SettingType.class));
	}

	@Test
	void helpersOnGeneratedRecords() {
		Protocol.Goal g = ForemanJson.read(JsonParser.parseString("{\"id\":\"g1\",\"text\":\"x\",\"status\":\"active\",\"repoId\":\"a\"}"),
			Protocol.Goal.class);
		assertEquals(List.of("a"), g.allRepos());
		assertEquals("marlow", g.lead());
		assertTrue(g.isOpen());
		Protocol.Decision d = ForemanJson.read(JsonParser.parseString("{\"id\":\"d1\",\"status\":\"open\",\"options\":[\"Post\"],\"textAllowed\":false}"),
			Protocol.Decision.class);
		assertTrue(d.isOpen());
		assertFalse(d.freeText());
		Protocol.TaskPr pr = ForemanJson.read(JsonParser.parseString("{\"url\":\"u\",\"id\":3,\"status\":\"merged\",\"threads\":{\"open\":1,\"new\":2}}"),
			Protocol.TaskPr.class);
		assertFalse(pr.isOpen());
		assertEquals(2, pr.threads().newCount());
		assertTrue(Protocol.DesignStatus.DONE.isFinal());
	}
}
