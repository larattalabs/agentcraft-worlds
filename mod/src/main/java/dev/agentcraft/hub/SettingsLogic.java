package dev.agentcraft.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The pure rules behind the hub's Team and Settings tabs (docs/HUB.md "Team and Settings tabs"): which tab
 * and group a setting belongs to, comparing, validating, formatting and parsing values per
 * {@link SettingDef} type, which staged changes widen what agents may do (they need a second confirm), and
 * reading the {@code config.set} ack (applied / restart / overridden, per-field errors). Unit-tested in
 * {@code SettingsLogicTest}.
 */
public final class SettingsLogic {
	public static final String GENERAL = "general";
	public static final String PERMISSIONS = "permissions";
	public static final String CONTEXT = "context";
	public static final String SUBAGENTS = "subagents";
	public static final String PRS = "prs";
	public static final String USAGE = "usage";
	public static final String TEAM = "team";
	/** The Settings tab's groups, in chip order. */
	public static final List<String> GROUPS = List.of(GENERAL, PERMISSIONS, CONTEXT, SUBAGENTS, PRS, USAGE);
	/** Effort levels when a setting lists none. */
	public static final List<String> EFFORTS = List.of("low", "medium", "high", "max");
	/** Permission modes from strict to loose (docs: "policy" default, "auto" = Claude Code's classifier); unknown = loosest. */
	public static final List<String> MODES = List.of("policy", "auto");
	public static final String MODE_KEY = "claude.permissions.mode";
	public static final String ALLOW_KEY = "claude.permissions.allow";
	public static final String DENY_KEY = "claude.permissions.deny";
	public static final String LOGIN_KEY = "claude.useClaudeLogin";

	private static final List<String> TEAM_KEYS = List.of("claude.workers", "claude.leads", "claude.leadModel", "claude.leadEffort",
		"claude.workerModel", "claude.effort", "claude.maxConcurrent", "claude.throttleConcurrent", "claude.maxConcurrentTurns",
		"claude.designModel", "claude.leadReview");

	private SettingsLogic() {
	}

	// ------------------------------------------------------------------ where a setting goes

	/** Team tab keys: the roster, the agents' profiles, models, effort and concurrency. */
	public static boolean isTeamKey(String key) {
		return TEAM_KEYS.contains(key) || key.startsWith("claude.agents.") || key.startsWith("claude.taskModels.");
	}

	/** {@code claude.agents.kit.model} -> "kit"; null for other keys. */
	public static @Nullable String agentOf(String key) {
		if (!key.startsWith("claude.agents.")) {
			return null;
		}
		String rest = key.substring("claude.agents.".length());
		int dot = rest.indexOf('.');
		return dot <= 0 ? null : rest.substring(0, dot);
	}

	/** The group a setting shows in: {@link #TEAM} for Team keys, else its {@code group} when known, else by key. */
	public static String groupOf(SettingDef d) {
		if (isTeamKey(d.key())) {
			return TEAM;
		}
		String g = normalizeGroup(d.group());
		if (g != null) {
			return g;
		}
		String k = d.key();
		if (k.startsWith("claude.permissions.")) {
			return PERMISSIONS;
		}
		if (k.startsWith("claude.context.")) {
			return CONTEXT;
		}
		if (k.startsWith("claude.subagents")) {
			return SUBAGENTS;
		}
		if (k.startsWith("claude.pr")) {
			return PRS;
		}
		if (k.equals("claude.maxBudgetUsdPerTurn") || k.equals(LOGIN_KEY)) {
			return USAGE;
		}
		return GENERAL;
	}

	/** "Permissions" / "PRs" / "pull requests" -> the group id, null when not one of {@link #GROUPS} (or team). */
	public static @Nullable String normalizeGroup(@Nullable String g) {
		if (g == null || g.isBlank()) {
			return null;
		}
		String k = g.strip().toLowerCase(Locale.ROOT).replace(" ", "");
		return switch (k) {
			case "general" -> GENERAL;
			case "permissions", "permission" -> PERMISSIONS;
			case "context" -> CONTEXT;
			case "subagents", "subagent" -> SUBAGENTS;
			case "prs", "pr", "pullrequests" -> PRS;
			case "usage" -> USAGE;
			case "team", "models" -> TEAM;
			default -> null;
		};
	}

	/** "PRs" for prs, else capitalised. */
	public static String groupLabel(String g) {
		return switch (g) {
			case PRS -> "PRs";
			default -> Character.toUpperCase(g.charAt(0)) + g.substring(1);
		};
	}

	/** The choices of a model/effort/enum setting: its options, else (effort) {@link #EFFORTS}. */
	public static List<String> choices(SettingDef d) {
		if (!d.options().isEmpty()) {
			return d.options();
		}
		return SettingDef.EFFORT.equals(d.type()) ? EFFORTS : List.of();
	}

	// ------------------------------------------------------------------ values

	/** JSON equality with numbers compared by value (1 == 1.0) and null == JsonNull. */
	public static boolean same(@Nullable JsonElement a, @Nullable JsonElement b) {
		a = a == null ? JsonNull.INSTANCE : a;
		b = b == null ? JsonNull.INSTANCE : b;
		if (a.isJsonPrimitive() && b.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber()) {
			return new BigDecimal(a.getAsString()).compareTo(new BigDecimal(b.getAsString())) == 0;
		}
		return a.equals(b);
	}

	/** The strings of a JSON array (other values skipped); empty for anything else. */
	public static List<String> strings(@Nullable JsonElement el) {
		List<String> out = new ArrayList<>();
		if (el != null && el.isJsonArray()) {
			for (JsonElement x : el.getAsJsonArray()) {
				if (x.isJsonPrimitive()) {
					out.add(x.getAsString());
				}
			}
		}
		return out;
	}

	/** Short display text: on/off, numbers without ".0", lists joined, "not set" for null. */
	public static String format(SettingDef d, @Nullable JsonElement v) {
		if (v == null || v.isJsonNull()) {
			return "not set";
		}
		if (v.isJsonPrimitive()) {
			JsonPrimitive p = v.getAsJsonPrimitive();
			if (p.isBoolean()) {
				return p.getAsBoolean() ? "on" : "off";
			}
			if (p.isNumber()) {
				return new BigDecimal(p.getAsString()).stripTrailingZeros().toPlainString();
			}
			String s = p.getAsString();
			return s.isEmpty() ? "(empty)" : s;
		}
		if (v.isJsonArray()) {
			List<String> xs = strings(v);
			return xs.isEmpty() ? "none" : String.join(", ", xs);
		}
		if (v.isJsonObject()) {
			List<String> parts = new ArrayList<>();
			for (var e : v.getAsJsonObject().entrySet()) {
				parts.add(e.getKey() + (e.getValue().isJsonPrimitive() ? " = " + e.getValue().getAsString() : ""));
			}
			return parts.isEmpty() ? "none" : String.join(", ", parts);
		}
		return v.toString();
	}

	/** The text a field starts with for this value: lists one per line, null empty. */
	public static String text(@Nullable JsonElement v) {
		if (v == null || v.isJsonNull()) {
			return "";
		}
		if (v.isJsonArray()) {
			return String.join("\n", strings(v));
		}
		if (v.isJsonPrimitive()) {
			JsonPrimitive p = v.getAsJsonPrimitive();
			return p.isNumber() ? new BigDecimal(p.getAsString()).stripTrailingZeros().toPlainString() : p.getAsString();
		}
		return v.toString();
	}

	/** A typed field's text as a value (or the problem). */
	public record Parsed(@Nullable JsonElement value, @Nullable String error) {
	}

	/**
	 * Parses what was typed for {@code d}: int (whole number), string (blank = not set when the default is
	 * not set), stringList (one entry per line, blank lines dropped). The result is then {@link #validate}d.
	 */
	public static Parsed parseText(SettingDef d, String text) {
		String t = text == null ? "" : text;
		switch (d.type()) {
			case SettingDef.INT -> {
				String s = t.strip();
				if (s.isEmpty()) {
					return d.def().isJsonNull() ? new Parsed(JsonNull.INSTANCE, null) : new Parsed(null, "a whole number is needed");
				}
				try {
					BigDecimal n = new BigDecimal(s);
					if (n.stripTrailingZeros().scale() > 0) {
						return new Parsed(null, "a whole number is needed");
					}
					return new Parsed(new JsonPrimitive(n.longValueExact()), null);
				} catch (NumberFormatException | ArithmeticException e) {
					return new Parsed(null, "not a number: " + s);
				}
			}
			case SettingDef.STRING_LIST, SettingDef.AGENT_LIST -> {
				JsonArray a = new JsonArray();
				for (String line : t.split("\n", -1)) {
					String l = line.strip();
					if (!l.isEmpty()) {
						a.add(l);
					}
				}
				return new Parsed(a, null);
			}
			default -> {
				if (t.isBlank() && d.def().isJsonNull()) {
					return new Parsed(JsonNull.INSTANCE, null);
				}
				return new Parsed(new JsonPrimitive(t), null);
			}
		}
	}

	/**
	 * The mod's own check of a value before Apply (the Foreman checks again and its errors win): type,
	 * int range, a choice among the options, list entries; null ("not set") only where the default is not set.
	 */
	public static @Nullable String validate(SettingDef d, @Nullable JsonElement v) {
		if (d.readOnly()) {
			return "read-only here";
		}
		if (v == null || v.isJsonNull()) {
			return d.def().isJsonNull() || SettingDef.MODEL.equals(d.type()) || SettingDef.EFFORT.equals(d.type()) ? null : "a value is needed";
		}
		switch (d.type()) {
			case SettingDef.BOOL -> {
				return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? null : "on or off";
			}
			case SettingDef.INT -> {
				if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
					return "a whole number is needed";
				}
				BigDecimal n = new BigDecimal(v.getAsString());
				if (n.stripTrailingZeros().scale() > 0) {
					return "a whole number is needed";
				}
				if (d.min() != null && n.compareTo(BigDecimal.valueOf(d.min())) < 0) {
					return "at least " + format(d, new JsonPrimitive(d.min()));
				}
				if (d.max() != null && n.compareTo(BigDecimal.valueOf(d.max())) > 0) {
					return "at most " + format(d, new JsonPrimitive(d.max()));
				}
				return null;
			}
			case SettingDef.ENUM, SettingDef.MODEL, SettingDef.EFFORT -> {
				if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
					return "pick one of the choices";
				}
				List<String> c = choices(d);
				return c.isEmpty() || c.contains(v.getAsString()) ? null : "one of " + String.join(", ", c);
			}
			case SettingDef.STRING -> {
				return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? null : "text is needed";
			}
			case SettingDef.STRING_LIST, SettingDef.AGENT_LIST -> {
				if (!v.isJsonArray()) {
					return "a list is needed";
				}
				LinkedHashSet<String> seen = new LinkedHashSet<>();
				for (JsonElement x : v.getAsJsonArray()) {
					if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isString() || x.getAsString().isBlank()) {
						return "every entry must be text";
					}
					if (!seen.add(x.getAsString()) && SettingDef.AGENT_LIST.equals(d.type())) {
						return x.getAsString() + " is listed twice";
					}
					if (SettingDef.AGENT_LIST.equals(d.type()) && !d.options().isEmpty() && !d.options().contains(x.getAsString())) {
						return "unknown agent " + x.getAsString();
					}
				}
				if (SettingDef.AGENT_LIST.equals(d.type()) && d.key().endsWith("leads") && !seen.isEmpty() && !seen.getFirst().equals("marlow")) {
					return "the leads start with marlow (home and repos without a building)";
				}
				return null;
			}
			default -> {
				return null;
			}
		}
	}

	// ------------------------------------------------------------------ widening changes

	/** How strict a permission mode is (0 = policy); unknown modes count as the loosest. */
	public static int modeRank(@Nullable String mode) {
		int i = mode == null ? 0 : MODES.indexOf(mode);
		return i < 0 ? MODES.size() : i;
	}

	/**
	 * What a change widens (named for the second confirm), or null: the permission mode moving to a looser
	 * one, a deny rule removed, an allow rule added, or any change of {@code claude.useClaudeLogin}.
	 */
	public static @Nullable String widening(String key, @Nullable JsonElement old, @Nullable JsonElement now) {
		switch (key) {
			case MODE_KEY -> {
				String a = old == null || old.isJsonNull() ? MODES.get(0) : old.getAsString();
				String b = now == null || now.isJsonNull() ? MODES.get(0) : now.getAsString();
				return modeRank(b) > modeRank(a) ? "Permission mode " + a + " → " + b + " (looser: fewer tool calls go through AgentCraft's policy)"
					: null;
			}
			case DENY_KEY -> {
				List<String> removed = new ArrayList<>(strings(old));
				removed.removeAll(strings(now));
				return removed.isEmpty() ? null : "Remove deny rule" + (removed.size() > 1 ? "s " : " ") + String.join(", ", removed);
			}
			case ALLOW_KEY -> {
				List<String> added = new ArrayList<>(strings(now));
				added.removeAll(strings(old));
				return added.isEmpty() ? null : "Add allow rule" + (added.size() > 1 ? "s " : " ") + String.join(", ", added)
					+ " (skips AgentCraft's policy)";
			}
			case LOGIN_KEY -> {
				if (same(old, now)) {
					return null;
				}
				boolean on = now != null && now.isJsonPrimitive() && now.getAsBoolean();
				return on ? "Use the claude CLI's claude.ai login (personal use only; after a restart)"
					: "Stop using the claude.ai login (needs an API key or cloud provider; after a restart)";
			}
			default -> {
				return null;
			}
		}
	}

	/** The widening changes among {@code staged}, compared with each setting's current value. */
	public static List<String> widenings(Map<String, JsonElement> staged, Function<String, @Nullable JsonElement> current) {
		List<String> out = new ArrayList<>();
		for (var e : staged.entrySet()) {
			String w = widening(e.getKey(), current.apply(e.getKey()), e.getValue());
			if (w != null) {
				out.add(w);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ the config.set ack

	/** A config.set ack result: keys applied now, keys waiting for a restart, keys a flag/env still overrides. */
	public record ApplyResult(List<String> applied, List<String> restartRequired, Map<String, String> overridden) {
		public static ApplyResult parse(@Nullable JsonObject r) {
			if (r == null) {
				return new ApplyResult(List.of(), List.of(), Map.of());
			}
			Map<String, String> ov = new LinkedHashMap<>();
			JsonElement o = r.get("overridden");
			if (o != null && o.isJsonArray()) {
				for (JsonElement x : o.getAsJsonArray()) {
					if (x.isJsonObject() && SettingDef.str(x.getAsJsonObject(), "key") != null) {
						String by = SettingDef.str(x.getAsJsonObject(), "by");
						ov.put(SettingDef.str(x.getAsJsonObject(), "key"), by == null ? "a flag" : by);
					}
				}
			}
			return new ApplyResult(strings(r.get("applied")), strings(r.get("restartRequired")), ov);
		}
	}

	/**
	 * Per-field errors of a refused config.set: {@code result.errors} ([{key, error|message}] or {key: msg}),
	 * else the error text split on ";" / newlines into "key: problem" parts whose key is one of {@code keys}
	 * (longest match first). What names no key goes under "" (shown above the form).
	 */
	public static Map<String, String> fieldErrors(@Nullable String error, @Nullable JsonObject result, Collection<String> keys) {
		Map<String, String> out = new LinkedHashMap<>();
		JsonElement errs = result == null ? null : result.get("errors");
		if (errs != null && errs.isJsonArray()) {
			for (JsonElement x : errs.getAsJsonArray()) {
				if (x.isJsonObject()) {
					String k = SettingDef.str(x.getAsJsonObject(), "key");
					String m = SettingDef.str(x.getAsJsonObject(), "error");
					if (m == null) {
						m = SettingDef.str(x.getAsJsonObject(), "message");
					}
					out.merge(k == null ? "" : k, m == null ? "invalid" : m, (a, b) -> a + "; " + b);
				}
			}
			return out;
		}
		if (errs != null && errs.isJsonObject()) {
			for (var e : errs.getAsJsonObject().entrySet()) {
				out.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
			}
			return out;
		}
		if (error == null || error.isBlank()) {
			return out;
		}
		List<String> byLength = new ArrayList<>(keys);
		byLength.sort((a, b) -> b.length() - a.length());
		for (String part : error.split("[;\n]")) {
			String p = part.strip();
			if (p.isEmpty()) {
				continue;
			}
			String hit = null;
			for (String k : byLength) {
				int i = p.indexOf(k);
				if (i >= 0 && (i == 0 || !Character.isLetterOrDigit(p.charAt(i - 1)) && p.charAt(i - 1) != '.')
					&& (i + k.length() == p.length() || !Character.isLetterOrDigit(p.charAt(i + k.length())) && p.charAt(i + k.length()) != '.')) {
					hit = k;
					break;
				}
			}
			if (hit == null) {
				out.merge("", p, (a, b) -> a + "; " + b);
			} else {
				String msg = p.substring(p.indexOf(hit) + hit.length()).replaceFirst("^[\\s:\\-–]+", "").strip();
				out.merge(hit, msg.isEmpty() ? p : msg, (a, b) -> a + "; " + b);
			}
		}
		return out;
	}
}
