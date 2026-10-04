package dev.agentcraft.client.hub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.hub.GoalLogic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * DevBridge for the hub's Repos and Goals tabs (mod/DEV.md "Hub"): the pane actions of {@code dev.hub.action}
 * (every button: named actions for those that talk to the Foreman, replying after the ack, plus a generic
 * {@code press {button}} for any button or chip drawn last frame) and the {@code dev.goals.*} helpers that
 * send straight through {@link HubGoals} (no screen needed).
 */
final class HubDev {
	static final String ACTIONS = "press|goal_open|goal_view|goal_back|goal_new|goal_form|goal_submit|goal_send|goal_answer|plan_edit|plan_save|"
		+ "plan_cancel|instr_add|instr_edit|instr_remove|goal_cancel|goal_filter|digest_dismiss|digest_refresh|focus|repo_select|repo_add|"
		+ "repo_remove|repo_place|repo_new_goal|refresh_prs";
	private static final Set<String> NAMES = Set.of(ACTIONS.split("\\|"));

	private HubDev() {
	}

	static boolean handles(String action) {
		return NAMES.contains(action);
	}

	/** dev.hub.open for the Repos/Goals tabs. Client thread. */
	static void openPane(HubScreen s, HubTab tab, @Nullable String goalId, @Nullable String repoId, @Nullable String view, boolean form) {
		if (tab == HubTab.REPOS) {
			if (repoId != null && !s.repos.select(repoId)) {
				throw new DevBridge.DevException("repoId: no repo " + repoId);
			}
			return;
		}
		GoalsTab.View v = view == null ? null : GoalsTab.View.parse(view);
		if (view != null && v == null) {
			throw new DevBridge.DevException("view must be thread, plan, instructions or tasks");
		}
		if (goalId != null) {
			if (HubGoals.goal(goalId) == null) {
				throw new DevBridge.DevException("goalId: no goal " + goalId);
			}
			s.goals.open(goalId, v);
		} else if (v != null) {
			s.goals.setView(v);
		}
		if (form) {
			s.goals.newGoal(repoId, null);
		}
	}

	private static List<String> strings(Fields f, String field) {
		JsonElement el = f.json().get(field);
		List<String> out = new ArrayList<>();
		if (el == null || el.isJsonNull()) {
			return out;
		}
		if (el.isJsonArray()) {
			for (JsonElement x : el.getAsJsonArray()) {
				if (!x.isJsonPrimitive()) {
					throw new DevBridge.DevException("field '" + field + "' must be an array of strings");
				}
				out.add(x.getAsString());
			}
			return out;
		}
		return GoalLogic.instructionLines(f.str(field));
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

	private static GoalsTab goals(HubScreen s) {
		s.setTab(HubTab.GOALS);
		return s.goals;
	}

	private static String openGoal(HubScreen s, Fields f) {
		String id = f.optStr("goalId", null);
		GoalsTab t = goals(s);
		if (id != null) {
			if (HubGoals.goal(id) == null) {
				throw new DevBridge.DevException("goalId: no goal " + id);
			}
			if (!id.equals(t.selected()) || t.formOpen()) {
				t.open(id, null);
			}
			return id;
		}
		if (t.selected() == null) {
			throw new DevBridge.DevException("goalId: which goal? (none is open)");
		}
		return t.selected();
	}

	/** dev.hub.action for the pane actions. Client thread. */
	static CompletableFuture<JsonObject> act(Minecraft mc, HubScreen s, String action, Fields f) {
		switch (action) {
			case "press" -> {
				String id = f.nonBlank("button");
				// only the shown tab's chips (a hidden form keeps last frame's hit list)
				boolean ok = s.press(id) || s.tab() == HubTab.GOALS && s.goals.pressChip(id) || s.tab() == HubTab.TEAM && s.team.form.press(id)
					|| s.tab() == HubTab.SETTINGS && s.settings.form.press(id) || s.tab() == HubTab.REPOS && s.repos.editing() != null && s.repos.form
						.press(id) || s.tab() == HubTab.INBOX && s.inbox.pressChip(id);
				if (!ok) {
					throw new DevBridge.DevException("button: no enabled button or chip '" + id + "' was drawn last frame (see dev.hub.state buttons"
						+ ", goalsTab.chips and the forms' chips: teamTab/settingsTab/reposTab .form.chips)");
				}
				return done(action, "pressed " + id);
			}
			case "focus" -> {
				String field = f.nonBlank("field");
				boolean ok = field.equals("repo_path") ? s.repos.focusPath() : s.goals.focusField(field);
				if (!ok) {
					throw new DevBridge.DevException("field: " + field + " is not on screen (message, plan, instruction, goal_text, goal_branch, "
						+ "goal_instructions, repo_path)");
				}
				return done(action, "focused " + field);
			}
			// ---------------------------------------------------------- goals
			case "goal_open" -> {
				String id = f.nonBlank("goalId");
				if (HubGoals.goal(id) == null) {
					throw new DevBridge.DevException("goalId: no goal " + id);
				}
				String view = f.optStr("view", null);
				GoalsTab.View v = view == null ? null : GoalsTab.View.parse(view);
				if (view != null && v == null) {
					throw new DevBridge.DevException("view must be thread, plan, instructions or tasks");
				}
				goals(s).open(id, v);
				return done(action, "opened " + id);
			}
			case "goal_view" -> {
				GoalsTab.View v = GoalsTab.View.parse(f.nonBlank("view"));
				if (v == null) {
					throw new DevBridge.DevException("view must be thread, plan, instructions or tasks");
				}
				openGoal(s, f);
				goals(s).setView(v);
				return done(action, "view " + v.id());
			}
			case "goal_back" -> {
				goals(s).back();
				return done(action, "back");
			}
			case "goal_new" -> {
				goals(s).newGoal(f.optStr("repoId", null), f.optStr("buildingId", null));
				return done(action, "form open");
			}
			case "goal_form", "goal_submit" -> {
				String buildingId = f.optStr("buildingId", null);
				if (buildingId != null && Buildings.get(buildingId) == null) {
					throw new DevBridge.DevException("buildingId: no building " + buildingId);
				}
				String instr = null;
				if (f.has("instructions")) {
					instr = String.join("\n", strings(f, "instructions"));
				}
				goals(s).fillForm(f.optStr("text", null), f.optStr("repoId", null), buildingId, f.optStr("branch", null), instr);
				if (action.equals("goal_form")) {
					return done(action, "form filled");
				}
				return note(action, s.goals.submitForm());
			}
			case "goal_send" -> {
				openGoal(s, f);
				GoalsTab t = goals(s);
				t.setView(GoalsTab.View.THREAD);
				String text = f.optStr("text", null);
				if (text != null) {
					t.messageField().set(text);
				}
				return note(action, t.sendMessage());
			}
			case "goal_answer" -> {
				openGoal(s, f);
				GoalsTab t = goals(s);
				Protocol.Decision d = t.decision(f.nonBlank("decisionId"));
				if (d == null) {
					throw new DevBridge.DevException("decisionId: no decision " + f.str("decisionId"));
				}
				String option = f.optStr("option", null);
				if (option != null && !d.options().contains(option)) {
					throw new DevBridge.DevException("option must be one of " + d.options());
				}
				String text = f.optStr("text", null);
				if (text != null) {
					t.messageField().set(text);
				}
				return t.answer(d, option).thenApply(msg -> {
					JsonObject o = new JsonObject();
					o.addProperty("action", action);
					// only a sent answer is ok (the arm delay, a first Reject or a missing text are not)
					o.addProperty("ok", msg != null && msg.startsWith(dev.agentcraft.client.hud.UiBits.CHECK));
					o.addProperty("message", msg);
					return o;
				});
			}
			case "plan_edit" -> {
				openGoal(s, f);
				GoalsTab t = goals(s);
				t.setView(GoalsTab.View.PLAN);
				t.editPlan();
				return done(action, "editing");
			}
			case "plan_save" -> {
				openGoal(s, f);
				GoalsTab t = goals(s);
				t.setView(GoalsTab.View.PLAN);
				t.editPlan();
				String body = f.optStr("body", null);
				if (body != null) {
					t.planField().set(body);
				}
				return note(action, t.savePlan());
			}
			case "plan_cancel" -> {
				goals(s).cancelPlan();
				return done(action, "cancelled");
			}
			case "instr_add", "instr_edit" -> {
				openGoal(s, f);
				GoalsTab t = goals(s);
				t.setView(GoalsTab.View.INSTRUCTIONS);
				if (action.equals("instr_edit")) {
					t.editInstruction(f.optInt("index", 0, 0, 999));
				}
				return note(action, t.commitInstruction(f.nonBlank("text")));
			}
			case "instr_remove" -> {
				openGoal(s, f);
				GoalsTab t = goals(s);
				t.setView(GoalsTab.View.INSTRUCTIONS);
				return note(action, t.removeInstruction(f.optInt("index", 0, 0, 999)));
			}
			case "goal_cancel" -> {
				openGoal(s, f);
				GoalsTab t = goals(s);
				CompletableFuture<HubGoals.Note> r = t.cancelClick();
				if (r == null && f.optBool("confirm", false)) {
					r = t.cancelClick();
				}
				if (r == null) {
					return done(action, "cancel armed: send goal_cancel again (or confirm:true) within " + GoalsTab.CONFIRM_MS / 1000 + " s");
				}
				return note(action, r);
			}
			case "goal_filter" -> {
				String b = f.optStr("buildingId", null);
				if (b != null && Buildings.get(b) == null) {
					throw new DevBridge.DevException("buildingId: no building " + b);
				}
				goals(s).setFilter(b);
				return done(action, b == null ? "all buildings" : "building " + b);
			}
			case "digest_dismiss" -> {
				HubGoals.dismissAway();
				return done(action, "dismissed");
			}
			case "digest_refresh" -> {
				goals(s);
				long since = f.optLong("since", HubGoals.seen().tabSeen(HubGoals.world()), 0, Long.MAX_VALUE);
				return HubGoals.requestAway(since).thenApply(st -> {
					JsonObject o = HubGoals.digestJson(st);
					o.addProperty("action", action);
					return o;
				});
			}
			// ---------------------------------------------------------- repos
			case "repo_select" -> {
				s.setTab(HubTab.REPOS);
				String id = f.nonBlank("repoId");
				if (!s.repos.select(id)) {
					throw new DevBridge.DevException("repoId: no repo " + id);
				}
				return done(action, "selected " + id);
			}
			case "repo_add" -> {
				s.setTab(HubTab.REPOS);
				s.repos.startAdd();
				s.repos.setPath(f.nonBlank("path"));
				return note(action, s.repos.add());
			}
			case "repo_remove" -> {
				s.setTab(HubTab.REPOS);
				String id = repoId(s, f);
				CompletableFuture<HubGoals.Note> r = s.repos.removeClick(id);
				if (r == null && f.optBool("confirm", false)) {
					r = s.repos.removeClick(id);
				}
				if (r == null) {
					return done(action, "remove armed: send repo_remove again (or confirm:true) within " + ReposTab.CONFIRM_MS / 1000 + " s");
				}
				return note(action, r);
			}
			case "repo_place" -> {
				String id = repoId(s, f);
				s.repos.placeBuilding(id);
				return done(action, "wizard open with " + id);
			}
			case "repo_new_goal" -> {
				String id = repoId(s, f);
				s.repos.newGoal(id);
				return done(action, "goal form for " + id);
			}
			case "refresh_prs" -> {
				return note(action, s.tab() == HubTab.REPOS ? s.repos.refreshPrs() : HubGoals.refreshPrs());
			}
			default -> throw new DevBridge.DevException("unknown action " + action);
		}
	}

	private static String repoId(HubScreen s, Fields f) {
		String id = f.optStr("repoId", null);
		if (id == null) {
			id = s.repos.selected();
		}
		if (id == null || Foreman.state() == null || Foreman.state().repo(id) == null) {
			throw new DevBridge.DevException("repoId: no repo " + id);
		}
		s.setTab(HubTab.REPOS);
		s.repos.select(id);
		return id;
	}

	// ------------------------------------------------------------------ dev.goals.*

	static void register() {
		DevBridge.register("dev.goals.submit", 30_000, "{text, repoId?, repos?: [..], branch?, instructions?: [..] | \"one per line\"} - goal.submit "
			+ "with the hub form's options (no screen); replies after the ack {ok, message, unsupported, result{goalId}}", (req, mc) -> {
				Fields f = Fields.of(req);
				String text = f.nonBlank("text");
				List<String> repos = strings(f, "repos");
				String repoId = f.optStr("repoId", null);
				if (repoId != null && !repos.contains(repoId)) {
					repos.add(0, repoId);
				}
				String branch = f.optStr("branch", null);
				List<String> instr = strings(f, "instructions");
				return DevBridge.onClient(mc, () -> HubGoals.submit(text, repos, branch, instr)).thenCompose(x -> x).thenApply(HubGoals::noteJson);
			});
		DevBridge.register("dev.goals.message", 30_000, "{goalId, text} - goal.message (shows as \"sending…\" in the thread until the ack)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String goalId = f.nonBlank("goalId");
				String text = f.nonBlank("text");
				return DevBridge.onClient(mc, () -> HubGoals.message(goalId, text)).thenCompose(x -> x).thenApply(HubGoals::noteJson);
			});
		DevBridge.register("dev.goals.plan", 30_000, "{goalId, body} - goal.plan (writes the goal's plan note as the user)", (req, mc) -> {
			Fields f = Fields.of(req);
			String goalId = f.nonBlank("goalId");
			String body = f.str("body");
			return DevBridge.onClient(mc, () -> HubGoals.plan(goalId, body)).thenCompose(x -> x).thenApply(HubGoals::noteJson);
		});
		DevBridge.register("dev.goals.instructions", 30_000, "{goalId, instructions: [..] | \"one per line\"} - goal.instructions (replaces them)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String goalId = f.nonBlank("goalId");
				if (!f.has("instructions")) {
					throw new DevBridge.DevException("field 'instructions' is required");
				}
				List<String> instr = strings(f, "instructions");
				return DevBridge.onClient(mc, () -> HubGoals.instructions(goalId, instr)).thenCompose(x -> x).thenApply(HubGoals::noteJson);
			});
		DevBridge.register("dev.goals.digest", 30_000, "{since?: ms (default: the Goals tab's last look), goalId?} - goal.digest; replies with the "
			+ "digest (also shown as the Goals tab's away panel, or the goal's own digest with goalId)", (req, mc) -> {
				Fields f = Fields.of(req);
				Long sinceArg = f.has("since") ? f.integer("since", 0, Long.MAX_VALUE) : null;
				String goalId = f.optStr("goalId", null);
				return DevBridge.onClient(mc, () -> {
					long since = sinceArg != null ? sinceArg : goalId != null ? HubGoals.seen().goalSeen(HubGoals.world(), goalId)
						: HubGoals.seen().tabSeen(HubGoals.world());
					return goalId != null ? HubGoals.requestGoal(goalId, since) : HubGoals.requestAway(since);
				}).thenCompose(x -> x).thenApply(HubGoals::digestJson);
			});
		DevBridge.register("dev.goals.seen", 10_000, "{reset?: bool, tabAgoMs?: ms} - hub-seen.json for this world (reset: forget it; tabAgoMs: "
			+ "pretend the Goals tab was last looked at that long ago, to test the away digest)", (req, mc) -> {
				Fields f = Fields.of(req);
				boolean reset = f.optBool("reset", false);
				long ago = f.optLong("tabAgoMs", -1, -1, Long.MAX_VALUE);
				return DevBridge.onClient(mc, () -> {
					if (reset || ago >= 0) {
						HubGoals.resetSeen(ago >= 0 ? System.currentTimeMillis() - ago : 0);
					}
					JsonObject o = new JsonObject();
					o.addProperty("world", HubGoals.world());
					o.addProperty("file", HubGoals.seenFile().toString());
					o.add("seen", HubGoals.seen().toJson());
					return o;
				});
			});
	}
}
