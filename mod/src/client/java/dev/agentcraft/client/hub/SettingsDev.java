package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.hub.SettingDef;
import dev.agentcraft.hub.SettingsLogic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * DevBridge for the hub's Team and Settings tabs and a repo's settings (mod/DEV.md "Team and Settings tabs"):
 * the {@code dev.hub.action}s for every control (named apart from the Buildings tab's actions), opening the
 * tabs, and fakes for {@code config.get} / {@code repo.agents} so the forms can be driven before the
 * Foreman side exists.
 */
final class SettingsDev {
	/** The contract's names (docs/HUB.md: "set {key, value}, apply, revert, confirm, restart") -> the actions. */
	static final java.util.Map<String, String> ALIASES = java.util.Map.of("set", "settings_set", "apply", "settings_apply", "revert",
		"settings_revert", "confirm", "settings_confirm", "restart", "foreman_restart");
	static final String ACTIONS = "set|apply|revert|confirm|restart|settings_set|settings_text|settings_focus|settings_apply|settings_confirm|settings_confirm_back|settings_revert|"
		+ "settings_group|settings_reload|settings_fake|settings_fake_agents|foreman_restart|team_select|team_back|team_on|team_lead|repo_settings|"
		+ "repo_settings_done";
	private static final Set<String> NAMES = Set.of(ACTIONS.split("\\|"));

	private SettingsDev() {
	}

	static boolean handles(String action) {
		return NAMES.contains(action);
	}

	/** dev.hub.open for team/settings (and the repos tab's editor with {@code edit}). Client thread. */
	static void open(HubScreen s, HubTab tab, @Nullable String group, @Nullable String agentId) {
		if (tab == HubTab.SETTINGS && group != null && !s.settings.setGroup(group)) {
			throw new DevBridge.DevException("group must be one of " + String.join(", ", SettingsLogic.GROUPS));
		}
		if (tab == HubTab.TEAM && agentId != null && !s.team.select(agentId)) {
			throw new DevBridge.DevException("agentId: not in the roster: " + agentId + " (" + String.join(", ", TeamTab.roster()) + ", models)");
		}
	}

	/** The scope a key belongs to: the repo's with {@code repoId}, else the global one. */
	private static ConfigScope scope(Fields f) {
		String repoId = f.optStr("repoId", null);
		if (repoId == null) {
			return HubConfig.global();
		}
		if (Foreman.state() == null || Foreman.state().repo(repoId) == null) {
			throw new DevBridge.DevException("repoId: no repo " + repoId);
		}
		return HubConfig.repo(repoId);
	}

	/** The form and scopes of the tab shown (team, settings, or the repo being edited). */
	private record Shown(SettingsForm form, List<ConfigScope> scopes) {
	}

	private static Shown shown(HubScreen s) {
		return switch (s.tab()) {
			case TEAM -> new Shown(s.team.form, TeamTab.scopes());
			case SETTINGS -> new Shown(s.settings.form, SettingsTab.scopes());
			case REPOS -> {
				if (s.repos.editing() == null) {
					throw new DevBridge.DevException("no settings form on screen: open the Team or Settings tab, or a repo's settings (repo_settings)");
				}
				yield new Shown(s.repos.form, List.of(HubConfig.repo(s.repos.editing())));
			}
			default -> throw new DevBridge.DevException("no settings form on screen: open the Team or Settings tab, or a repo's settings (repo_settings)");
		};
	}

	private static CompletableFuture<JsonObject> done(String action, String message) {
		JsonObject o = new JsonObject();
		o.addProperty("action", action);
		o.addProperty("message", message);
		return CompletableFuture.completedFuture(o);
	}

	private static CompletableFuture<JsonObject> note(String action, CompletableFuture<HubGoals.Note> f) {
		return f.thenApply(n -> {
			JsonObject o = HubGoals.noteJson(n);
			o.addProperty("action", action);
			return o;
		});
	}

	static CompletableFuture<JsonObject> act(HubScreen s, String name, Fields f) {
		String action = ALIASES.getOrDefault(name, name);
		switch (action) {
			case "settings_set" -> {
				ConfigScope sc = scope(f);
				String key = f.nonBlank("key");
				if (!f.has("value")) {
					throw new DevBridge.DevException("field 'value' is required (JSON; null = not set)");
				}
				SettingDef d = sc.def(key);
				if (d == null) {
					throw new DevBridge.DevException("key: " + sc.id() + " has no setting " + key + (sc.phase() != ConfigScope.Phase.READY
						? " (settings not loaded: " + sc.phase() + ")" : ""));
				}
				if (d.readOnly()) {
					throw new DevBridge.DevException("key: " + key + " is read-only here");
				}
				JsonElement v = f.json().get("value");
				sc.set(key, v);
				JsonObject o = new JsonObject();
				o.addProperty("action", action);
				o.addProperty("staged", sc.staged.has(key));
				o.addProperty("problem", sc.problem(key));
				o.addProperty("widening", SettingsLogic.widening(key, sc.current(key), sc.value(key)));
				return CompletableFuture.completedFuture(o);
			}
			case "settings_text" -> {
				ConfigScope sc = scope(f);
				String key = f.nonBlank("key");
				if (sc.def(key) == null) {
					throw new DevBridge.DevException("key: " + sc.id() + " has no setting " + key);
				}
				sc.setText(key, f.str("text"));
				JsonObject o = new JsonObject();
				o.addProperty("action", action);
				o.addProperty("staged", sc.staged.has(key));
				o.addProperty("problem", sc.problem(key));
				return CompletableFuture.completedFuture(o);
			}
			case "settings_focus" -> {
				String key = f.nonBlank("key");
				if (!shown(s).form().focusKey(key)) {
					throw new DevBridge.DevException("key: no editable text field for " + key + " was drawn last frame");
				}
				return done(action, "focused " + key);
			}
			case "settings_apply", "settings_confirm" -> {
				Shown sh = shown(s);
				boolean confirm = action.equals("settings_confirm") || f.optBool("confirm", false);
				return note(action, sh.form().apply(sh.scopes(), confirm));
			}
			case "settings_confirm_back" -> {
				shown(s).scopes().forEach(ConfigScope::cancelConfirm);
				return done(action, "confirm dropped");
			}
			case "settings_revert" -> {
				Shown sh = shown(s);
				sh.form().revert(sh.scopes());
				return done(action, "reverted");
			}
			case "settings_group" -> {
				s.setTab(HubTab.SETTINGS);
				String g = f.nonBlank("group");
				if (!s.settings.setGroup(g)) {
					throw new DevBridge.DevException("group must be one of " + String.join(", ", SettingsLogic.GROUPS));
				}
				return done(action, "group " + s.settings.group());
			}
			case "settings_reload" -> {
				return note(action, scope(f).load());
			}
			case "settings_fake" -> {
				ConfigScope sc = scope(f);
				JsonObject result = f.obj("result").json();
				sc.fake(result.deepCopy());
				return done(action, "loaded " + sc.view().settings().size() + " fake settings into " + sc.id());
			}
			case "settings_fake_agents" -> {
				ConfigScope sc = scope(f);
				if (sc.repoId == null) {
					throw new DevBridge.DevException("repoId is required");
				}
				JsonElement a = f.json().get("agents");
				if (a == null || !a.isJsonArray()) {
					throw new DevBridge.DevException("field 'agents' must be an array of {name, path?, description?, model?} or names");
				}
				List<Protocol.RepoAgentFile> list = new ArrayList<>();
				for (JsonElement x : a.getAsJsonArray()) {
					if (x.isJsonPrimitive()) {
						list.add(new Protocol.RepoAgentFile(x.getAsString(), x.getAsString(), null, null, null));
					} else if (x.isJsonObject()) {
						list.add(dev.agentcraft.client.foreman.ForemanJson.read(x, Protocol.RepoAgentFile.class));
					}
				}
				sc.fakeAgents(list);
				return done(action, list.size() + " fake agent files for " + sc.repoId);
			}
			case "foreman_restart" -> {
				SettingsForm form = s.tab() == HubTab.TEAM ? s.team.form : s.tab() == HubTab.REPOS ? s.repos.form : s.settings.form;
				return note(action, form.restart());
			}
			case "team_select" -> {
				s.setTab(HubTab.TEAM);
				String id = f.optStr("agentId", TeamTab.MODELS);
				if (!s.team.select(id)) {
					throw new DevBridge.DevException("agentId: not in the roster: " + id + " (" + String.join(", ", TeamTab.roster()) + ", models)");
				}
				return done(action, "selected " + id);
			}
			case "team_back" -> {
				s.setTab(HubTab.TEAM);
				s.team.back();
				return done(action, "back");
			}
			case "team_on" -> {
				s.setTab(HubTab.TEAM);
				String id = f.nonBlank("agentId");
				requireReady();
				if (TeamTab.isLead(id)) {
					throw new DevBridge.DevException("agentId: " + id + " is a lead (use team_lead {inUse})");
				}
				s.team.setOnTeam(id, f.bool("on"));
				return staged(action);
			}
			case "team_lead" -> {
				s.setTab(HubTab.TEAM);
				String id = f.nonBlank("agentId");
				requireReady();
				if (f.has("inUse")) {
					s.team.setLeadInUse(id, f.bool("inUse"));
				}
				if (f.has("move")) {
					int d = f.optInt("move", 0, -1, 1);
					if (d != 0 && !s.team.moveLead(id, d)) {
						throw new DevBridge.DevException("move: " + id + " cannot move " + (d < 0 ? "up" : "down") + " (marlow stays first)");
					}
				}
				return staged(action);
			}
			case "repo_settings" -> {
				s.setTab(HubTab.REPOS);
				String id = f.optStr("repoId", s.repos.selected());
				if (id == null || Foreman.state() == null || Foreman.state().repo(id) == null) {
					throw new DevBridge.DevException("repoId: no repo " + id);
				}
				s.repos.edit(id);
				return done(action, "editing " + id);
			}
			case "repo_settings_done" -> {
				s.repos.edit(null);
				return done(action, "done");
			}
			default -> throw new DevBridge.DevException("unknown action " + action);
		}
	}

	private static void requireReady() {
		ConfigScope sc = HubConfig.global();
		if (sc.phase() != ConfigScope.Phase.READY) {
			throw new DevBridge.DevException("the global settings are not loaded (" + sc.phase() + (sc.error() == null ? "" : ": " + sc.error()) + ")");
		}
	}

	private static CompletableFuture<JsonObject> staged(String action) {
		JsonObject o = new JsonObject();
		o.addProperty("action", action);
		JsonObject st = new JsonObject();
		HubConfig.global().staged.edits().forEach((k, v) -> st.add(k, v.deepCopy()));
		o.add("staged", st);
		JsonArray w = new JsonArray();
		HubConfig.global().widenings().forEach(w::add);
		o.add("widening", w);
		return CompletableFuture.completedFuture(o);
	}
}
