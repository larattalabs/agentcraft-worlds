package dev.agentcraft.hub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Finds the Foreman's client token (docs/HUB.md "Client token"): the Foreman writes a random token to
 * {@code <dataDir>/client.token} (new per start) and lists its path in its run file
 * ({@code <home>/<profile>/foreman.json}, and {@code <home>/foreman.json} for the primary one). Without a
 * valid token in {@code hello} a connection is read-only.
 *
 * <p>Which run file: the one whose {@code port} is the port the mod connects to, looked for in the
 * profile's run file ({@code AGENTCRAFT_PROFILE}), then the home run file, then every
 * {@code <home>/<dir>/foreman.json}. Which field holds the path: the first of {@link #PATH_FIELDS} that
 * names a readable file; else {@code client.token} next to the profile's run file ({@code <home>/<profile>}).
 * No run file, or none of these = an older Foreman (no token is sent). Read again on every connect: a
 * restarted Foreman has a new token. Pure (file access only), so it is unit-tested.
 */
public final class ClientToken {
	/** Run-file fields that may hold the token file's path (the Foreman side names one of them). */
	public static final List<String> PATH_FIELDS = List.of("clientTokenFile", "tokenFile", "clientTokenPath", "tokenPath", "clientToken");
	public static final String FILE = "client.token";
	public static final String RUN_FILE = "foreman.json";

	/** The token and where it came from (for dev state; never log the token). {@code token} null = none found. */
	public record Found(@Nullable String token, @Nullable Path runFile, @Nullable Path tokenFile, String note) {
	}

	private ClientToken() {
	}

	/**
	 * The token for the Foreman listening on {@code port}. {@code home}: {@code AGENTCRAFT_HOME} or
	 * {@code ~/.agentcraft}; {@code profile}: {@code AGENTCRAFT_PROFILE} or null.
	 */
	public static Found resolve(Path home, @Nullable String profile, int port) {
		List<Path> candidates = new ArrayList<>();
		if (profile != null && !profile.isBlank()) {
			candidates.add(home.resolve(profile.strip()).resolve(RUN_FILE));
		}
		candidates.add(home.resolve(RUN_FILE));
		if (Files.isDirectory(home)) {
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(home)) {
				List<Path> dirs = new ArrayList<>();
				for (Path d : ds) {
					if (Files.isDirectory(d)) {
						dirs.add(d.resolve(RUN_FILE));
					}
				}
				dirs.sort(null);
				for (Path p : dirs) {
					if (!candidates.contains(p)) {
						candidates.add(p);
					}
				}
			} catch (IOException ignored) {
				// home unreadable: the explicit candidates only
			}
		}
		boolean sawRunFile = false;
		for (Path run : candidates) {
			JsonObject info = readJson(run);
			if (info == null) {
				continue;
			}
			sawRunFile = true;
			Integer p = intField(info, "port");
			if (p == null || p != port) {
				continue;
			}
			for (String field : PATH_FIELDS) {
				String v = strField(info, field);
				if (v == null) {
					continue;
				}
				Path tf = run.getParent().resolve(v);
				String t = readToken(tf);
				if (t != null) {
					return new Found(t, run, tf, "from " + field + " in " + run);
				}
			}
			// no path field (or an unreadable one): the profile dir's client.token
			List<Path> dirs = new ArrayList<>();
			String prof = strField(info, "profile");
			if (prof != null) {
				dirs.add(home.resolve(prof));
			}
			dirs.add(run.getParent());
			for (Path d : dirs) {
				Path tf = d.resolve(FILE);
				String t = readToken(tf);
				if (t != null) {
					return new Found(t, run, tf, "from " + tf + " (run file " + run + " names no token path)");
				}
			}
			return new Found(null, run, null, "run file " + run + " for port " + port + " has no client token (an older Foreman)");
		}
		return new Found(null, null, null, sawRunFile ? "no run file under " + home + " is for port " + port : "no Foreman run file under " + home);
	}

	/** The token in {@code file} (trimmed), or null when it is missing, empty or unreadable. */
	static @Nullable String readToken(Path file) {
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > 4096) {
				return null;
			}
			String t = Files.readString(file, StandardCharsets.UTF_8).strip();
			return t.isEmpty() ? null : t;
		} catch (IOException | SecurityException e) {
			return null;
		}
	}

	private static @Nullable JsonObject readJson(Path file) {
		try {
			if (!Files.isRegularFile(file)) {
				return null;
			}
			JsonElement el = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static @Nullable String strField(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			return null;
		}
		String s = e.getAsString().strip();
		return s.isEmpty() ? null : s;
	}

	private static @Nullable Integer intField(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonPrimitive()) {
			return null;
		}
		try {
			return e.getAsInt();
		} catch (RuntimeException x) {
			return null;
		}
	}
}
