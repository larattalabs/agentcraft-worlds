package dev.agentcraft.client.dev;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * DevBridge requests reserve {@code id} (the request id, echoed in the reply) and {@code type} (the command), so no
 * handler may read them as its own payload fields: such a field always holds the request id / command name instead of
 * what the caller meant. {@code settings_field} read its field name from {@code id} and so could never be used (found
 * by the smoke test, tools/smoke.mjs). Scans the client sources for a request's {@code Fields} (named {@code f} or
 * {@code fields}, as every handler names them) read with one of those names; a line reading a nested object's own field
 * says {@code reserved-ok}.
 */
class ReservedFieldsTest {
	private static final Pattern READ = Pattern.compile("\\b(?:f|fields)\\.(?:nonBlank|str|optStr|integer|optInt|optLong|num|optNum|bool|optBool)\\(\"(id|type)\"");

	@Test
	void noHandlerReadsAReservedRequestField() throws IOException {
		Path root = Path.of("src/client/java");
		assertTrue(Files.isDirectory(root), "run from mod/: " + root.toAbsolutePath());
		List<String> hits = new ArrayList<>();
		try (Stream<Path> files = Files.walk(root)) {
			for (Path p : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
				List<String> lines = Files.readAllLines(p);
				for (int i = 0; i < lines.size(); i++) {
					Matcher m = READ.matcher(lines.get(i));
					if (m.find() && !lines.get(i).contains("reserved-ok")) {
						hits.add(root.relativize(p) + ":" + (i + 1) + " reads \"" + m.group(1) + "\"");
					}
				}
			}
		}
		assertTrue(hits.isEmpty(), "DevBridge handlers read reserved request fields (use another name, e.g. `field`, `road`): " + hits);
	}
}
