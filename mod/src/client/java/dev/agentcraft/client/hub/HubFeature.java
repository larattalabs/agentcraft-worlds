package dev.agentcraft.client.hub;

import dev.agentcraft.ui.Guard;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.BlueprintTransform;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.building.BuildPlacement;
import dev.agentcraft.client.building.BuildingWizardFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * The AgentCraft hub ({@link HubScreen}, docs/HUB.md "Hub screen"): opened with {@code H} (Options >
 * Controls > AgentCraft, rebindable; vanilla only uses H as F3+H) and the console's {@code /hub [tab]}.
 *
 * <p>QA: screens {@code hub} and {@code hub_<tab>} for {@code dev.screen}; {@code dev.hub.open},
 * {@code dev.hub.state}, {@code dev.hub.action} (see {@link #registerDev}).
 */
public final class HubFeature {
	/**
	 * "Design new" in the blueprint browser: the generator form (docs/HUB.md "Generated buildings"),
	 * installed by the feature that builds it, called with the hub screen as the parent. While null the
	 * button is shown disabled.
	 */
	public static volatile @Nullable Consumer<Screen> designNew;

	private HubFeature() {
	}

	public static void init() {
		// the HUD alert line, away toast and tab badges count what the Inbox's "Needs you" counts (docs/WAVE2.md W5)
		dev.agentcraft.client.hud.Alerts.setSource("inbox", () -> {
			dev.agentcraft.hub.InboxModel.Counts c = Inbox.counts();
			dev.agentcraft.client.foreman.ForemanState s = dev.agentcraft.client.foreman.Foreman.state();
			dev.agentcraft.client.foreman.Protocol.ForemanStatus st = s == null ? null : s.status();
			dev.agentcraft.client.foreman.Protocol.ForemanHold hold = st == null ? null : st.hold();
			return new dev.agentcraft.client.hud.AlertCounts() {
				@Override
				public int decisions() {
					return c.decisions();
				}

				@Override
				public int blocked() {
					return c.blocked();
				}

				@Override
				public int replies() {
					return c.replies();
				}

				@Override
				public int prs() {
					return c.prs();
				}

				@Override
				public dev.agentcraft.client.foreman.Protocol.ForemanHold hold() {
					return hold;
				}
			};
		}, Inbox::revision); // "All read" or a viewed reply changes the badge and line at once, not up to 1.5 s later
		Keys.ensureRegistered();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("hub.tick", () -> {
			while (Keys.hub.consumeClick()) {
				if (mc.player != null && mc.gui.screen() == null && !BuildPlacement.active() && !dev.agentcraft.client.building.PlotMarker.active()) {
					// the last tab of this world; after an away toast the Inbox (else Goals) (docs/WAVE2.md W6)
					open(dev.agentcraft.client.hud.HudWatch.hubTarget());
				}
			}
		}));
		DevBridge.registerScreen("hub", mc -> new HubScreen(HubTab.BUILDINGS));
		for (HubTab t : HubTab.values()) {
			DevBridge.registerScreen("hub_" + t.id, mc -> new HubScreen(t));
		}
		DevBridge.registerScreen("hub_blueprints", mc -> {
			HubScreen s = new HubScreen(HubTab.BUILDINGS);
			s.setSub(HubScreen.Sub.BLUEPRINTS);
			return s;
		});
		DevBridge.registerScreen("hub_status_help", mc -> {
			HubScreen s = new HubScreen(HubTab.STATUS);
			s.status.setView(StatusPane.View.HELP);
			return s;
		});
		DevBridge.registerScreen("hub_designs", mc -> {
			HubScreen s = new HubScreen(HubTab.BUILDINGS);
			s.setSub(HubScreen.Sub.DESIGNS);
			return s;
		});
		for (String gr : dev.agentcraft.hub.SettingsLogic.GROUPS) {
			DevBridge.registerScreen("hub_settings_" + gr, mc -> {
				HubScreen s = new HubScreen(HubTab.SETTINGS);
				s.settings.setGroup(gr);
				return s;
			});
		}
		DevBridge.registerScreen("hub_repo_settings", mc -> {
			// the first repo's settings form
			HubScreen s = new HubScreen(HubTab.REPOS);
			List<Protocol.Repo> rs = ReposTab.repos();
			if (!rs.isEmpty()) {
				s.repos.edit(rs.get(0).id());
			}
			return s;
		});
		for (GoalsTab.View v : GoalsTab.View.values()) {
			// the newest goal (of the Foreman's), opened in that view
			DevBridge.registerScreen("hub_goal_" + v.id(), mc -> {
				HubScreen s = new HubScreen(HubTab.GOALS);
				List<Protocol.Goal> gs = HubGoals.goals(null);
				if (!gs.isEmpty()) {
					s.goals.open(gs.get(0).id(), v);
				}
				return s;
			});
		}
		registerDev();
		HubDev.register();
		InboxDev.register();
	}

	/** Opens the hub at {@code tab} (null = Buildings). Client thread. */
	public static HubScreen open(@Nullable HubTab tab) {
		HubScreen s = new HubScreen(tab == null ? HubTab.BUILDINGS : tab);
		if (tab == null || tab == HubTab.BUILDINGS) {
			// a design finished while the hub was closed: show its blueprint
			String fresh = dev.agentcraft.client.design.DesignFeature.takePendingSelect();
			if (fresh != null && s.selectBlueprint(fresh)) {
				s.setView("iso");
			}
		}
		Minecraft.getInstance().gui.setScreen(s);
		return s;
	}

	// ------------------------------------------------------------------ dev

	static HubScreen requireHub(Minecraft mc) {
		if (mc.gui.screen() instanceof HubScreen h) {
			return h;
		}
		if (mc.player == null) {
			throw new DevBridge.DevException("not in a world");
		}
		return open(null);
	}

	private static void registerDev() {
		DevBridge.register("dev.hub.open", 10_000, "{tab?: " + HubTab.ids() + ", sub?: buildings|blueprints|designs, buildingId?, blueprint?, designId?, "
			+ "view?: plan|iso|top|front|cutaway (Buildings) | thread|plan|instructions|tasks (Goals) | overview|help (Status), goalId?, repoId?, form?: bool (Goals: the new goal "
			+ "form), edit?: bool (Repos: the repoId's settings form), group?: general|permissions|context|subagents|prs|usage (Settings), agentId?: id|models "
			+ "(Team; Inbox: the agent view), filter?: all|needs_you|building:<id>|agent:<id>|podium:<id|home> (Inbox), item?: key|decision id|task id (Inbox)} - "
			+ "open the hub (H) and select; replies with dev.hub.state", (req, mc) -> {
				Fields f = Fields.of(req);
				String tabName = f.optStr("tab", null);
				String sub = f.optStr("sub", null);
				String building = f.optStr("buildingId", null);
				String bp = f.optStr("blueprint", null);
				String view = f.optStr("view", null);
				String design = f.optStr("designId", null);
				String goalId = f.optStr("goalId", null);
				String repoId = f.optStr("repoId", null);
				boolean form = f.optBool("form", false);
				boolean edit = f.optBool("edit", false);
				String group = f.optStr("group", null);
				String agentId = f.optStr("agentId", null);
				String filter = f.optStr("filter", null);
				String item = f.optStr("item", null);
				HubTab tab = tabName == null ? HubTab.BUILDINGS : HubTab.parse(tabName);
				if (tab == null) {
					throw new DevBridge.DevException("tab must be one of " + HubTab.ids());
				}
				return DevBridge.onClient(mc, () -> {
					if (mc.player == null) {
						throw new DevBridge.DevException("not in a world");
					}
					BuildPlacement.cancel();
					HubScreen s = open(tab);
					if (tab == HubTab.INBOX) {
						InboxDev.open(s, filter, item, agentId);
						return state(mc);
					}
					if (tab == HubTab.GOALS || tab == HubTab.REPOS) {
						HubDev.openPane(s, tab, goalId, repoId, view, form);
						if (tab == HubTab.REPOS && edit) {
							String id = repoId != null ? repoId : s.repos.selected() != null ? s.repos.selected() : ReposTab.repos().isEmpty() ? null
								: ReposTab.repos().get(0).id();
							if (id == null) {
								throw new DevBridge.DevException("edit: there is no repo");
							}
							s.repos.edit(id);
						}
						return state(mc);
					}
					if (tab == HubTab.TEAM || tab == HubTab.SETTINGS) {
						SettingsDev.open(s, tab, group, agentId);
						return state(mc);
					}
					if (tab == HubTab.STATUS) {
						StatusPane.View sv = StatusPane.View.parse(view);
						if (view != null && sv == null) {
							throw new DevBridge.DevException("view must be overview or help (Status)");
						}
						s.status.setView(sv == null ? StatusPane.View.OVERVIEW : sv);
						return state(mc);
					}
					if (sub != null) {
						s.setSub(parseSub(sub));
					}
					select(s, building, bp);
					if (design != null) {
						s.selectDesign(design);
					}
					if (view != null && !s.setView(view)) {
						throw new DevBridge.DevException("view " + view + " is not available for blueprint " + s.selectedBlueprint());
					}
					return state(mc);
				});
			});
		DevBridge.register("dev.hub.state", 10_000, "{} - the hub: open, tab, sub, selections, armed remove, last action, buildings, blueprints, "
			+ "rendered previews of the selected blueprint (paths tried, found, load state), buttons on screen", (req, mc) -> DevBridge.onClient(mc,
				() -> state(mc)));
		DevBridge.register("dev.team.release", 15_000, "{world} - the Team tab's Release for a world holding leads (lead.releaseWorld; hub must be open)",
			(req, mc) -> {
				String world = Fields.of(req).nonBlank("world");
				return DevBridge.onClient(mc, () -> {
					if (!(mc.gui.screen() instanceof HubScreen h)) {
						throw new DevBridge.DevException("open the hub first (dev.screen {open:\"hub_team\"})");
					}
					return h.team;
				}).thenCompose(team -> team.releaseWorld(world)).thenApply(note -> {
					JsonObject o = new JsonObject();
					o.addProperty("note", note);
					return o;
				});
			});
		DevBridge.register("dev.team.card", 10_000, "{agent} - the Team tab's Card button: the agent card with the hub as its parent (Esc returns)",
			(req, mc) -> {
				String id = Fields.of(req).nonBlank("agent");
				return DevBridge.onClient(mc, () -> {
					HubScreen h = mc.gui.screen() instanceof HubScreen hs ? hs : new HubScreen(HubTab.TEAM);
					if (!dev.agentcraft.client.agents.AgentsFeature.openCard(id, h)) {
						throw new DevBridge.DevException("no agent '" + id + "'");
					}
					JsonObject o = new JsonObject();
					o.addProperty("screen", mc.gui.screen().getClass().getSimpleName());
					return o;
				});
			});
		DevBridge.register("dev.hub.action", 30_000, "{action: tab|select|view|home|teleport|remove|edit_repos|move|undo_move|place_new|place|place_plot|design_new|"
			+ "cancel_design|" + HubDev.ACTIONS + "|" + SettingsDev.ACTIONS + "|" + InboxDev.ACTIONS + ", tab?, buildingId?, blueprint?, designId?, repos?: [..] | \"a,b\", view?, confirm?: bool} - press a hub button (opens the "
			+ "hub when closed). home/teleport/remove/cancel_design reply after the server/Foreman answered; remove without confirm arms it (a "
			+ "second remove for the same id confirms; refused over the player's things, a third forces it); edit_repos with repos sets them "
			+ "(without: opens the repo screen); move puts up the ghost (then dev.build.*); place_plot = Place on the plot (with repos: straight to placement locked on the plot)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String action = f.nonBlank("action").toLowerCase(Locale.ROOT);
				String building = f.optStr("buildingId", null);
				String bp = f.optStr("blueprint", null);
				String tabName = f.optStr("tab", null);
				String view = f.optStr("view", null);
				boolean confirm = f.optBool("confirm", false);
				List<String> repos = repoList(f);
				String design = f.optStr("designId", null);
				return DevBridge.onClient(mc, () -> InboxDev.handles(action) ? InboxDev.act(mc, requireHub(mc), action, f) : HubDev.handles(action)
					? HubDev.act(mc, requireHub(mc), action, f) : SettingsDev.handles(action)
					? SettingsDev.act(requireHub(mc), action, f) : act(mc, action, building, bp, tabName, view, confirm, repos, design)).thenCompose(x -> x)
					.thenCompose(o -> DevBridge.onClient(mc, () -> {
						JsonObject st = state(mc);
						st.add("result", o);
						return st;
					}));
			});
	}

	private static CompletableFuture<JsonObject> act(Minecraft mc, String action, @Nullable String building, @Nullable String bp,
		@Nullable String tabName, @Nullable String view, boolean confirm, List<String> repos, @Nullable String design) {
		HubScreen s = requireHub(mc);
		JsonObject done = new JsonObject();
		switch (action) {
			case "tab" -> {
				HubTab t = HubTab.parse(tabName);
				if (t == null) {
					throw new DevBridge.DevException("tab must be one of " + HubTab.ids());
				}
				s.setTab(t);
			}
			case "select" -> select(s, building, bp);
			case "view" -> {
				if (view == null || !s.setView(view)) {
					throw new DevBridge.DevException("view " + view + " is not available for blueprint " + s.selectedBlueprint());
				}
			}
			case "home", "teleport", "remove" -> {
				String id = building != null ? building : s.selectedBuilding();
				if (id == null || Buildings.get(id) == null) {
					throw new DevBridge.DevException("buildingId: no building " + id);
				}
				s.selectBuilding(id);
				CompletableFuture<HubActions.Result> f = switch (action) {
					case "home" -> s.makeHome(id);
					case "teleport" -> s.teleport(id);
					default -> {
						CompletableFuture<HubActions.Result> r = s.removeClick(id);
						if (r == null && confirm) {
							r = s.removeClick(id);
						}
						yield r;
					}
				};
				if (f == null) {
					done.addProperty("armed", id);
					done.addProperty("message", "remove armed: send remove again for " + id + " (or confirm:true) within "
						+ HubScreen.CONFIRM_MS / 1000 + " s");
					return CompletableFuture.completedFuture(done);
				}
				return f.thenApply(HubFeature::resultJson);
			}
			case "edit_repos", "move", "undo_move" -> {
				String id = building != null ? building : s.selectedBuilding();
				if (id == null || Buildings.get(id) == null) {
					throw new DevBridge.DevException("buildingId: no building " + id);
				}
				s.selectBuilding(id);
				switch (action) {
					case "edit_repos" -> {
						if (repos.isEmpty()) {
							s.editRepos(id); // the repo screen, as the button does
							done.addProperty("opened", "repos of " + id);
							return CompletableFuture.completedFuture(done);
						}
						return HubActions.setRepos(id, repos).thenApply(HubFeature::resultJson);
					}
					case "move" -> {
						String why = s.move(id);
						if (why != null) {
							throw new DevBridge.DevException(why);
						}
						done.addProperty("placing", "move " + id + ": the ghost is up (dev.build.start-like: dev.build.nudge/lock/confirm)");
						return CompletableFuture.completedFuture(done);
					}
					default -> {
						return s.undoMove(id).thenApply(HubFeature::resultJson);
					}
				}
			}
			case "place_new" -> s.placeNew();
			case "place" -> {
				String id = bp != null ? bp : s.selectedBlueprint();
				if (id == null || Blueprints.get(id) == null) {
					throw new DevBridge.DevException("blueprint: not loaded: " + id);
				}
				if (repos.isEmpty()) {
					s.placeBlueprint(id);
				} else {
					String why = BuildingWizardFeature.placeNow(id, repos);
					if (why != null) {
						throw new DevBridge.DevException(why);
					}
				}
			}
			case "design_new" -> done.addProperty("opened", s.designNew());
			case "place_plot" -> {
				String id = bp != null ? bp : s.selectedBlueprint();
				if (id == null || Blueprints.get(id) == null) {
					throw new DevBridge.DevException("blueprint: not loaded: " + id);
				}
				String why;
				if (repos.isEmpty()) {
					why = s.placeOnPlot(id);
				} else {
					try {
						int[] spot = dev.agentcraft.client.design.DesignFeature.plotSpot(id);
						why = BuildingWizardFeature.placeNowAt(id, repos, spot, spot[3]);
					} catch (IllegalStateException e) {
						why = e.getMessage();
					}
				}
				if (why != null) {
					throw new DevBridge.DevException(why);
				}
			}
			case "cancel_design" -> {
				String id = design != null ? design : s.selectedDesign();
				if (id == null) {
					throw new DevBridge.DevException("designId: which design?");
				}
				s.selectDesign(id);
				return s.cancelDesign(id).thenApply(msg -> {
					JsonObject o = new JsonObject();
					o.addProperty("action", "cancel_design");
					o.addProperty("designId", id);
					o.addProperty("message", msg);
					return o;
				});
			}
			default -> throw new DevBridge.DevException("action must be tab|select|view|home|teleport|remove|place_new|place|place_plot|design_new|"
				+ "cancel_design");
		}
		done.addProperty("action", action);
		return CompletableFuture.completedFuture(done);
	}

	private static void select(HubScreen s, @Nullable String building, @Nullable String bp) {
		if (building != null && !s.selectBuilding(building)) {
			throw new DevBridge.DevException("buildingId: no building " + building);
		}
		if (bp != null && !s.selectBlueprint(bp)) {
			throw new DevBridge.DevException("blueprint: not loaded: " + bp + " (loaded: " + Blueprints.ids() + ")");
		}
	}

	private static HubScreen.Sub parseSub(String s) {
		return switch (s.toLowerCase(Locale.ROOT)) {
			case "buildings" -> HubScreen.Sub.BUILDINGS;
			case "blueprints" -> HubScreen.Sub.BLUEPRINTS;
			case "designs" -> HubScreen.Sub.DESIGNS;
			default -> throw new DevBridge.DevException("sub must be buildings, blueprints or designs");
		};
	}

	private static List<String> repoList(Fields f) {
		JsonElement el = f.json().get("repos");
		if (el == null || el.isJsonNull()) {
			return List.of();
		}
		if (el.isJsonArray()) {
			List<String> out = new ArrayList<>();
			for (JsonElement x : el.getAsJsonArray()) {
				out.add(x.getAsString().trim());
			}
			return out;
		}
		return BlueprintTransform.parseRepos(el.getAsString());
	}

	private static JsonObject resultJson(HubActions.Result r) {
		JsonObject o = new JsonObject();
		o.addProperty("action", r.action());
		o.addProperty("buildingId", r.buildingId());
		o.addProperty("ok", r.ok());
		o.addProperty("message", r.message());
		return o;
	}

	static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		HubScreen s = mc.gui.screen() instanceof HubScreen h ? h : null;
		o.addProperty("open", s != null);
		o.addProperty("key", Keys.label(Keys.hub));
		o.addProperty("singleplayer", mc.getSingleplayerServer() != null);
		o.addProperty("teleportAllowed", mc.player != null && HubActions.teleportAllowed(mc.player));
		o.addProperty("tab", s == null ? null : s.tab().id);
		o.addProperty("sub", s == null ? null : s.sub().name().toLowerCase(Locale.ROOT));
		o.addProperty("selectedBuilding", s == null ? null : s.selectedBuilding());
		o.addProperty("selectedBlueprint", s == null ? null : s.selectedBlueprint());
		o.addProperty("selectedDesign", s == null ? null : s.selectedDesign());
		o.addProperty("designNote", s == null ? null : s.designNote());
		o.addProperty("armedRemove", s == null ? null : s.armedRemove());
		o.addProperty("forceRemoveArmed", s != null && s.armedRemove() != null && s.forceArmed(s.armedRemove()));
		o.addProperty("busy", s != null && s.busy());
		o.addProperty("view", s == null ? null : s.view());
		o.addProperty("designNewAvailable", designNew != null);
		HubActions.Result last = HubActions.last();
		o.add("lastAction", last == null ? null : resultJson(last));
		JsonArray bs = new JsonArray();
		for (Building b : Buildings.all()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", b.id());
			j.addProperty("blueprint", b.blueprint());
			Blueprint bp = Blueprints.get(b.blueprint());
			j.addProperty("blueprintName", bp == null ? null : bp.name());
			JsonArray rs = new JsonArray();
			b.repos().forEach(rs::add);
			j.add("repos", rs);
			j.addProperty("home", b.home());
			j.addProperty("rotation", b.rotation());
			j.addProperty("dimension", b.dimension());
			Anchors.Bounds box = b.box();
			j.addProperty("box", box.minX() + "," + box.minY() + "," + box.minZ() + " .. " + box.maxX() + "," + box.maxY() + "," + box.maxZ());
			j.addProperty("hasEntrance", b.anchors().containsKey("entrance"));
			Anchors.Bounds rb = b.restoreBox();
			j.addProperty("snapshotBox", rb.minX() + "," + rb.minY() + "," + rb.minZ() + " .. " + rb.maxX() + "," + rb.maxY() + "," + rb.maxZ());
			j.addProperty("revision", b.revision());
			if (b.movedFrom() != null) {
				j.addProperty("movedFrom", b.movedFrom().x() + "," + b.movedFrom().y() + "," + b.movedFrom().z() + " " + b.movedFrom().rotation() + " "
					+ b.movedFrom().dimension());
			}
			Buildings.Report rep = Buildings.reports().get(b.id());
			if (rep != null) {
				j.addProperty("check", rep.message());
				j.addProperty("checkProblem", rep.problem());
			}
			String world = Buildings.worldId();
			j.addProperty("leadKey", world == null ? null : dev.agentcraft.building.LeadRouting.key(world, b.id()));
			j.addProperty("lead", dev.agentcraft.client.leads.Leads.view().leadOf(b.id()));
			j.addProperty("leadLabel", dev.agentcraft.client.leads.LeadsFeature.leadLabel(b));
			bs.add(j);
		}
		o.add("buildings", bs);
		JsonArray bps = new JsonArray();
		for (Blueprint bp : HubScreen.blueprints()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", bp.id());
			j.addProperty("name", bp.name());
			j.addProperty("kind", bp.kind());
			j.addProperty("wings", bp.wings());
			j.addProperty("size", HubScreen.size(bp));
			Blueprints.Entry e = Blueprints.entry(bp.id());
			j.addProperty("source", e == null ? null : e.source());
			j.addProperty("previews", PreviewImages.find(bp.id()).size());
			j.addProperty("hasPlot", dev.agentcraft.client.design.DesignFeature.plotForBlueprint(bp.id()) != null);
			bps.add(j);
		}
		o.add("blueprints", bps);
		JsonArray ds = new JsonArray();
		for (var d : HubScreen.designs()) {
			ds.add(dev.agentcraft.client.design.DesignFeature.designJson(d));
		}
		o.add("designs", ds);
		if (s != null && s.selectedBlueprint() != null) {
			o.add("previews", PreviewImages.describe(s.selectedBlueprint()));
		} else {
			o.add("previews", null);
		}
		o.add("previewTextures", PreviewImages.stats());
		o.add("reposTab", s == null ? null : s.repos.state());
		o.add("goalsTab", s == null ? null : s.goals.state());
		o.add("teamTab", s == null ? null : s.team.state());
		o.add("settingsTab", s == null ? null : s.settings.state());
		o.add("inboxTab", s == null ? null : s.inbox.state());
		o.add("statusTab", s == null ? null : s.status.state());
		o.add("tabs", s == null ? null : s.tabsState());
		if (s != null) {
			JsonArray btns = new JsonArray();
			for (String[] b : s.buttonsShown()) {
				JsonObject j = new JsonObject();
				j.addProperty("id", b[0]);
				j.addProperty("label", b[1]);
				j.addProperty("state", b[2]);
				btns.add(j);
			}
			o.add("buttons", btns);
			JsonObject d = new JsonObject();
			d.addProperty("rows", s.descriptionRows());
			d.addProperty("lines", s.descriptionLines());
			o.add("description", d);
		}
		return o;
	}
}
