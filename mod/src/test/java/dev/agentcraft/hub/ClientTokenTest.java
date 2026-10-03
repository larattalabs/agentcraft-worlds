package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientTokenTest {
	@TempDir
	Path home;

	private void write(Path p, String s) throws IOException {
		Files.createDirectories(p.getParent());
		Files.writeString(p, s);
	}

	@Test
	void noRunFileIsAnOlderOrStoppedForeman() {
		ClientToken.Found f = ClientToken.resolve(home, "claude", 7878);
		assertNull(f.token());
		assertTrue(f.note().contains("no Foreman run file"));
	}

	@Test
	void runFileWithoutTokenPathIsAnOlderForeman() throws IOException {
		write(home.resolve("claude/foreman.json"), "{\"pid\":1,\"port\":7878,\"profile\":\"claude\"}");
		ClientToken.Found f = ClientToken.resolve(home, "claude", 7878);
		assertNull(f.token());
		assertEquals(home.resolve("claude/foreman.json"), f.runFile());
	}

	@Test
	void tokenPathFieldIsRead() throws IOException {
		Path tok = home.resolve("sim/client.token");
		write(tok, "abc123\n");
		write(home.resolve("sim/foreman.json"), "{\"port\":7900,\"profile\":\"sim\",\"tokenFile\":\"" + tok.toString().replace("\\", "\\\\") + "\"}");
		ClientToken.Found f = ClientToken.resolve(home, "sim", 7900);
		assertEquals("abc123", f.token());
		assertEquals(tok, f.tokenFile());
	}

	@Test
	void portMustMatchAndOtherProfilesAreScanned() throws IOException {
		write(home.resolve("a/client.token"), "tokA");
		write(home.resolve("a/foreman.json"), "{\"port\":7001,\"profile\":\"a\",\"clientTokenFile\":\"client.token\"}");
		write(home.resolve("b/client.token"), "tokB");
		write(home.resolve("b/foreman.json"), "{\"port\":7002,\"profile\":\"b\",\"clientTokenFile\":\"client.token\"}");
		// the home run file points at a (the primary), but we connect to b's port
		write(home.resolve("foreman.json"), "{\"port\":7001,\"profile\":\"a\",\"clientTokenFile\":\"a/client.token\"}");
		assertEquals("tokB", ClientToken.resolve(home, null, 7002).token());
		assertEquals("tokA", ClientToken.resolve(home, null, 7001).token());
		assertNull(ClientToken.resolve(home, null, 7003).token());
	}

	@Test
	void fallsBackToClientTokenInTheProfileDir() throws IOException {
		write(home.resolve("claude/client.token"), "fallback");
		write(home.resolve("foreman.json"), "{\"port\":7878,\"profile\":\"claude\",\"someNewName\":\"x\"}");
		assertEquals("fallback", ClientToken.resolve(home, null, 7878).token());
	}

	@Test
	void emptyTokenFileCountsAsNone() throws IOException {
		write(home.resolve("p/client.token"), "  \n");
		write(home.resolve("p/foreman.json"), "{\"port\":1,\"tokenFile\":\"client.token\"}");
		assertNull(ClientToken.resolve(home, "p", 1).token());
	}
}
