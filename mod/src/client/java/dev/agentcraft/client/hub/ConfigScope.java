package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanJson;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.ProtocolSupport;
import dev.agentcraft.client.hud.AgentBits;
import dev.agentcraft.hub.SettingDef;
import dev.agentcraft.hub.SettingsLogic;
import dev.agentcraft.hub.Staged;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * One set of settings the hub edits: the global config ({@code repoId} null) or one repo's
 * {@code repoSettings} (docs/HUB.md "Team and Settings tabs"). Holds what {@code config.get} returned, the
 * staged edits ({@link Staged}), per-field problems (typed text that does not parse, the mod's own check,
 * the Foreman's refusal) and the pending second confirm for a widening change; Apply sends one
 * {@code config.set}. Reloads itself when the Foreman reports {@code config.changed} or after a reconnect,
 * keeping staged edits (rebased). For a repo it also loads {@code repo.agents} (the roles picker).
 * Client thread.
 */
final class ConfigScope {
	enum Phase {
		IDLE, LOADING, READY, FAILED
	}

	final @Nullable String repoId;
	private Phase phase = Phase.IDLE;
	private @Nullable String error;
	private boolean unsupported;
	private boolean readOnly;
	private SettingDef.ConfigView view = new SettingDef.ConfigView(null, List.of(), List.of());
	private long loadedRev = -1;
	private int loadedSnapshots = -1;
	final Staged staged = new Staged();
	/** key -> the Foreman's (or the mod's) problem with the staged value; "" = not about one field. */
	final Map<String, String> errors = new LinkedHashMap<>();
	/** key -> typed text that does not parse (the staged value is left as it was). */
	final Map<String, String> parseErrors = new LinkedHashMap<>();
	/** key -> the flag/env that still overrides it (from the last Apply). */
	final Map<String, String> overridden = new LinkedHashMap<>();
	/** The widening changes waiting for "Confirm and apply" (null = none asked). */
	private @Nullable List<String> confirm;
	private boolean busy;
	private @Nullable String note;
	private boolean noteError;
	// repo.agents (repo scopes)
	private @Nullable List<ProtocolSupport.RepoAgentFile> agents;
	private @Nullable String agentsError;
	private boolean agentsLoading;
	private int agentsSnapshots = -1;

	ConfigScope(@Nullable String repoId) {
		this.repoId = repoId;
	}

	String id() {
		return repoId == null ? "global" : "repo:" + repoId;
	}

	Phase phase() {
		return phase;
	}

	@Nullable String error() {
		return error;
	}

	boolean unsupported() {
		return unsupported;
	}

	/** config.get was refused as a read-only connection (no valid client token). */
	boolean readOnly() {
		return readOnly || Foreman.state() != null && Foreman.state().readOnly();
	}

	SettingDef.ConfigView view() {
		return view;
	}

	boolean busy() {
		return busy;
	}

	@Nullable String note() {
		return note;
	}

	boolean noteError() {
		return noteError;
	}

	void setNote(@Nullable String n, boolean err) {
		note = n;
		noteError = err;
	}

	@Nullable List<String> confirm() {
		return confirm;
	}

	boolean dirty() {
		return !staged.isEmpty() || !parseErrors.isEmpty();
	}

	// ------------------------------------------------------------------ values

	/** The setting, or one synthesised for a key the Foreman did not list (a repo's {@code roles.<agent>}, an agent's profile). */
	@Nullable SettingDef def(String key) {
		SettingDef d = view.get(key);
		if (d != null) {
			return d;
		}
		return synthesize(key);
	}

	private @Nullable SettingDef synthesize(String key) {
		if (phase != Phase.READY) {
			return null;
		}
		if (repoId != null && key.startsWith("roles.")) {
			String agent = key.substring(6);
			Protocol.Repo r = Foreman.state() == null ? null : Foreman.state().repo(repoId);
			String cur = r != null && r.settings() != null ? r.settings().roles().get(agent) : null;
			return new SettingDef(key, "Role of " + AgentBits.agentName(agent), "One of the repo's .claude/agents files: the "
				+ "agent's role, prompt and model in this repo.", "agents", SettingDef.STRING, List.of(), null, null, new JsonPrimitive(cur == null ? ""
					: cur), new JsonPrimitive(""), cur == null ? "default" : "file", true, null);
		}
		String agent = SettingsLogic.agentOf(key);
		if (repoId == null && agent != null) {
			String field = key.substring(("claude.agents." + agent + ".").length());
			boolean lead = dev.agentcraft.Cast.get(agent) != null && "lead".equals(dev.agentcraft.Cast.get(agent).role());
			return switch (field) {
				case "title" -> new SettingDef(key, "Title", "Role title on the nameplate, e.g. \"Frontend\".", "team", SettingDef.STRING, List.of(),
					null, null, JsonNull.INSTANCE, JsonNull.INSTANCE, "default", true, null);
				case "prompt" -> new SettingDef(key, "Specialty", "What this agent specialises in: goes into its own prompt and the lead's team list.",
					"team", SettingDef.STRING, List.of(), null, null, JsonNull.INSTANCE, JsonNull.INSTANCE, "default", true, null);
				case "model" -> new SettingDef(key, "Model", "Wins over the " + (lead ? "lead" : "worker") + " model for this agent.", "team",
					SettingDef.MODEL, optionsOf(lead ? "claude.leadModel" : "claude.workerModel"), null, null, JsonNull.INSTANCE, JsonNull.INSTANCE,
					"default", true, null);
				case "effort" -> new SettingDef(key, "Effort", "Wins over the " + (lead ? "lead" : "worker") + " effort for this agent.", "team",
					SettingDef.EFFORT, optionsOf(lead ? "claude.leadEffort" : "claude.effort"), null, null, JsonNull.INSTANCE, JsonNull.INSTANCE,
					"default", true, null);
				default -> null;
			};
		}
		return null;
	}

	private List<String> optionsOf(String key) {
		SettingDef d = view.get(key);
		return d == null ? List.of() : d.options();
	}

	/** The current (applied) value of a key. */
	JsonElement current(String key) {
		SettingDef d = def(key);
		return d == null ? JsonNull.INSTANCE : d.value();
	}

	/** The value the form shows: staged, else current. */
	JsonElement value(String key) {
		JsonElement v = staged.value(key, current(key));
		return v == null ? JsonNull.INSTANCE : v;
	}

	/** Stages a value (validated by the mod at once; the Foreman checks again on Apply). */
	void set(String key, @Nullable JsonElement v) {
		SettingDef d = def(key);
		staged.set(key, v, current(key));
		parseErrors.remove(key);
		errors.remove(key);
		confirm = null;
		if (d != null && staged.has(key)) {
			String why = SettingsLogic.validate(d, v);
			if (why != null) {
				errors.put(key, why);
			}
		}
		if (note != null && !busy) {
			note = null;
		}
	}

	/** Drops the staged edit of {@code key} (a secret setting whose update became empty). */
	void unstage(String key) {
		staged.remove(key);
		parseErrors.remove(key);
		errors.remove(key);
		confirm = null;
	}

	/** Typed text for {@code key}: staged when it parses, else remembered as a parse problem. */
	void setText(String key, String text) {
		SettingDef d = def(key);
		if (d == null) {
			return;
		}
		SettingsLogic.Parsed p = SettingsLogic.parseText(d, text);
		if (p.error() != null) {
			parseErrors.put(key, p.error());
			confirm = null;
			return;
		}
		set(key, p.value());
	}

	void revert() {
		staged.clear();
		parseErrors.clear();
		errors.clear();
		confirm = null;
		note = null;
	}

	/** Problem shown under a field: a parse problem, the mod's or the Foreman's. */
	@Nullable String problem(String key) {
		String p = parseErrors.get(key);
		return p != null ? p : errors.get(key);
	}

	// ------------------------------------------------------------------ load

	/** Loads when never loaded, after config.changed, or after a (re)connect. Call every frame the form is shown. */
	void tick() {
		ForemanState s = Foreman.state();
		if (s == null || !Foreman.connected() || busy) {
			return;
		}
		boolean stale = phase == Phase.IDLE || phase == Phase.READY && loadedRev != s.configRevision()
			|| phase != Phase.LOADING && loadedSnapshots != s.snapshotCount();
		if (stale) {
			load();
		}
		if (repoId != null && agents == null && !agentsLoading && agentsSnapshots != s.snapshotCount()) {
			loadAgents();
		}
	}

	CompletableFuture<HubGoals.Note> load() {
		ForemanState s = Foreman.state();
		if (!Foreman.connected()) {
			return CompletableFuture.completedFuture(HubGoals.Note.failed("Settings: the Foreman is not connected"));
		}
		phase = phase == Phase.READY ? Phase.READY : Phase.LOADING;
		boolean wasReady = phase == Phase.READY;
		loadedRev = s.configRevision();
		loadedSnapshots = s.snapshotCount();
		CompletableFuture<HubGoals.Note> out = new CompletableFuture<>();
		Foreman.configGet(repoId).whenComplete((ack, err) -> {
			if (err != null) {
				phase = wasReady ? Phase.READY : Phase.FAILED;
				error = "Settings not loaded: " + describe(err);
				out.complete(HubGoals.Note.failed(error));
				return;
			}
			accept(ack);
			out.complete(phase == Phase.READY ? HubGoals.Note.ok("Loaded " + view.settings().size() + " settings", ack.result())
				: new HubGoals.Note(false, error == null ? "?" : error, unsupported, null));
		});
		return out;
	}

	/** Takes a config.get ack (also DevBridge's fake one). */
	void accept(Ack ack) {
		if (!ack.ok()) {
			unsupported = Foreman.unsupported(ack);
			readOnly = ForemanState.isReadOnlyError(ack.error());
			error = unsupported ? "Editing settings needs a newer Foreman (it does not know config.get)" : Foreman.refusal("Loading the settings", ack);
			phase = Phase.FAILED;
			return;
		}
		view = SettingDef.ConfigView.parse(ack.result());
		phase = Phase.READY;
		error = null;
		unsupported = false;
		readOnly = false;
		staged.rebase(this::current);
		parseErrors.keySet().removeIf(k -> view.get(k) == null && synthesize(k) == null);
		// re-check what is still staged against the new definitions
		errors.clear();
		for (var e : staged.edits().entrySet()) {
			SettingDef d = def(e.getKey());
			String why = d == null ? null : SettingsLogic.validate(d, e.getValue());
			if (why != null) {
				errors.put(e.getKey(), why);
			}
		}
	}

	/** DevBridge: load a fake config.get result (the Foreman side may not exist yet). */
	void fake(JsonObject result) {
		accept(new Ack("dev", true, null, result));
		ForemanState s = Foreman.state();
		if (s != null) {
			loadedRev = s.configRevision();
			loadedSnapshots = s.snapshotCount();
		}
	}

	private void loadAgents() {
		if (repoId == null) {
			return;
		}
		agentsLoading = true;
		agentsSnapshots = Foreman.state().snapshotCount();
		Foreman.repoAgents(repoId).whenComplete((ack, err) -> {
			agentsLoading = false;
			if (err != null) {
				agentsError = "agents not loaded: " + describe(err);
				return;
			}
			if (!ack.ok()) {
				agentsError = Foreman.unsupported(ack) ? "the roles picker needs a newer Foreman" : Foreman.refusal("repo.agents", ack);
				agents = List.of();
				return;
			}
			List<ProtocolSupport.RepoAgentFile> list = new ArrayList<>();
			JsonElement a = ack.result() == null ? null : ack.result().get("agents");
			if (a != null && a.isJsonArray()) {
				for (JsonElement x : a.getAsJsonArray()) {
					try {
						ProtocolSupport.RepoAgentFile f = ForemanJson.read(x, ProtocolSupport.RepoAgentFile.class);
						if (f != null && !f.name().isBlank()) {
							list.add(f);
						}
					} catch (RuntimeException ignored) {
						// skip a malformed entry
					}
				}
			}
			agents = List.copyOf(list);
			agentsError = null;
		});
	}

	/** The repo's agent files (null while loading or for the global scope). */
	@Nullable List<ProtocolSupport.RepoAgentFile> agents() {
		return agents;
	}

	@Nullable String agentsError() {
		return agentsError;
	}

	boolean agentsLoading() {
		return agentsLoading;
	}

	/** DevBridge: fake repo.agents. */
	void fakeAgents(List<ProtocolSupport.RepoAgentFile> list) {
		agents = List.copyOf(list);
		agentsError = null;
		agentsLoading = false;
	}

	// ------------------------------------------------------------------ apply

	/** Asks for the second confirm of this scope's widening changes (none: clears it). */
	void askConfirm() {
		List<String> w = widenings();
		confirm = w.isEmpty() ? null : w;
		if (confirm != null) {
			setNote("Confirm: " + String.join("; ", w), true);
		}
	}

	/** Drops a pending confirm ("Back"). */
	void cancelConfirm() {
		confirm = null;
		note = null;
	}

	/** Widening changes among the staged ones (each needs to be named in a second confirm). */
	List<String> widenings() {
		return SettingsLogic.widenings(staged.edits(), this::current);
	}

	/**
	 * Apply: refuses while a field has a problem; a widening change first asks for a confirm naming it (the
	 * returned note says so, nothing is sent) unless {@code confirmed}; then one config.set.
	 */
	CompletableFuture<HubGoals.Note> apply(boolean confirmed) {
		String where = repoId == null ? "Settings" : repoId + "'s settings";
		if (staged.isEmpty() && parseErrors.isEmpty()) {
			return done(HubGoals.Note.failed("Nothing to apply"));
		}
		if (!parseErrors.isEmpty() || errors.keySet().stream().anyMatch(k -> !k.isEmpty() && staged.has(k))) {
			return done(HubGoals.Note.failed("Fix the marked fields first"));
		}
		if (!Foreman.connected()) {
			return done(HubGoals.Note.failed(where + ": the Foreman is not connected"));
		}
		List<String> wide = widenings();
		if (!wide.isEmpty() && !confirmed) {
			confirm = wide;
			setNote("Confirm: " + String.join("; ", wide), true);
			return CompletableFuture.completedFuture(new HubGoals.Note(false, "confirm needed: " + String.join("; ", wide), false, null));
		}
		confirm = null;
		busy = true;
		setNote("Applying " + dev.larattalabs.labui.client.hud.UiBits.plural(staged.size(), "change", "changes") + "…", false);
		JsonArray changes = staged.changes();
		List<String> keys = new ArrayList<>(staged.edits().keySet());
		CompletableFuture<HubGoals.Note> out = new CompletableFuture<>();
		Foreman.configSet(repoId, changes).whenComplete((ack, err) -> {
			busy = false;
			HubGoals.Note n;
			if (err != null) {
				n = HubGoals.Note.failed(where + " not applied: " + describe(err));
			} else if (!ack.ok()) {
				errors.clear();
				List<String> known = new ArrayList<>(keys);
				view.settings().forEach(d -> known.add(d.key()));
				errors.putAll(SettingsLogic.fieldErrors(ack.error(), ack.result(), known));
				n = new HubGoals.Note(false, Foreman.unsupported(ack) ? "Applying settings needs a newer Foreman" : Foreman.refusal("Apply", ack),
					Foreman.unsupported(ack), ack.result());
			} else {
				SettingsLogic.ApplyResult r = SettingsLogic.ApplyResult.parse(ack.result());
				// what applied is now the current value (until the reload confirms it)
				List<SettingDef> next = new ArrayList<>();
				for (SettingDef d : view.settings()) {
					// a secret setting's staged value is an update: its view after it (names only) becomes current
					next.add(staged.has(d.key()) ? d.withValue(SettingsLogic.applied(d, staged.value(d.key(), d.value())), "file") : d);
				}
				view = new SettingDef.ConfigView(view.file(), next, view.mcpServers());
				staged.clear();
				errors.clear();
				overridden.clear();
				overridden.putAll(r.overridden());
				if (!r.restartRequired().isEmpty() && Foreman.state() != null) {
					Foreman.state().addRestartRequired(r.restartRequired());
				}
				StringBuilder b = new StringBuilder("Applied " + dev.larattalabs.labui.client.hud.UiBits.plural(keys.size(), "change", "changes"));
				if (!r.restartRequired().isEmpty()) {
					b.append(" · ").append(r.restartRequired().size()).append(" after a restart");
				}
				if (!r.overridden().isEmpty()) {
					List<String> ov = new ArrayList<>();
					r.overridden().forEach((k, by) -> ov.add(k + " (" + by + ")"));
					b.append(" · still overridden: ").append(String.join(", ", ov));
				}
				n = HubGoals.Note.ok(b.toString(), ack.result());
				load();
			}
			setNote(n.message(), !n.ok());
			out.complete(n);
		});
		return out;
	}

	private CompletableFuture<HubGoals.Note> done(HubGoals.Note n) {
		setNote(n.message(), !n.ok());
		return CompletableFuture.completedFuture(n);
	}

	static String describe(Throwable err) {
		Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
		return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
	}

	// ------------------------------------------------------------------ DevBridge

	JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("scope", id());
		o.addProperty("repoId", repoId);
		o.addProperty("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
		o.addProperty("error", error);
		o.addProperty("unsupported", unsupported);
		o.addProperty("readOnly", readOnly());
		o.addProperty("file", view.file());
		o.addProperty("settings", view.settings().size());
		o.addProperty("busy", busy);
		o.addProperty("note", note);
		o.addProperty("noteError", noteError);
		JsonObject st = new JsonObject();
		staged.edits().forEach((k, v) -> st.add(k, SettingsLogic.masked(def(k), v))); // secret values: "(staged)"
		o.add("staged", st);
		JsonObject er = new JsonObject();
		errors.forEach(er::addProperty);
		parseErrors.forEach(er::addProperty);
		o.add("errors", er);
		JsonObject ov = new JsonObject();
		overridden.forEach(ov::addProperty);
		o.add("overridden", ov);
		JsonArray w = new JsonArray();
		widenings().forEach(w::add);
		o.add("widening", w);
		if (confirm != null) {
			JsonArray c = new JsonArray();
			confirm.forEach(c::add);
			o.add("confirm", c);
		} else {
			o.add("confirm", null);
		}
		if (repoId != null) {
			JsonArray ag = new JsonArray();
			if (agents != null) {
				agents.forEach(a -> ag.add(a.name()));
			}
			o.add("agents", agents == null ? null : ag);
			o.addProperty("agentsError", agentsError);
		}
		return o;
	}
}
