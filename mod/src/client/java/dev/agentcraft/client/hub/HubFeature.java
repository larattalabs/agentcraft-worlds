package dev.agentcraft.client.hub;

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
		Keys.ensureRegistered();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (Keys.hub.consumeClick()) {
				if (mc.player != null && mc.gui.screen() == null && !BuildPlacement.active()) {
					open(null);
				}
			}
		});
		DevBridge.registerScreen("hub", mc -> new HubScreen(HubTab.BUILDINGS));
		for (HubTab t : HubTab.values()) {
			DevBridge.registerScreen("hub_" + t.id, mc -> new HubScreen(t));
		}
		DevBridge.registerScreen("hub_blueprints", mc -> {
			HubScreen s = new HubScreen(HubTab.BUILDINGS);
			s.setSub(HubScreen.Sub.BLUEPRINTS);
			return s;
		});
		registerDev();
	}

	/** Opens the hub at {@code tab} (null = Buildings). Client thread. */
	public static HubScreen open(@Nullable HubTab tab) {
		HubScreen s = new HubScreen(tab == null ? HubTab.BUILDINGS : tab);
		Minecraft.getInstance().gui.setScreen(s);
		return s;
	}

	// ------------------------------------------------------------------ dev

	private static HubScreen requireHub(Minecraft mc) {
		if (mc.gui.screen() instanceof HubScreen h) {
			return h;
		}
		if (mc.player == null) {
			throw new DevBridge.DevException("not in a world");
		}
		return open(null);
	}

	private static void registerDev() {
		DevBridge.register("dev.hub.open", 10_000, "{tab?: " + HubTab.ids() + ", sub?: buildings|blueprints, buildingId?, blueprint?, "
			+ "view?: plan|iso|top|front|cutaway} - open the hub (H) and select; replies with dev.hub.state", (req, mc) -> {
				Fields f = Fields.of(req);
				String tabName = f.optStr("tab", null);
				String sub = f.optStr("sub", null);
				String building = f.optStr("buildingId", null);
				String bp = f.optStr("blueprint", null);
				String view = f.optStr("view", null);
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
					if (sub != null) {
						s.setSub(parseSub(sub));
					}
					select(s, building, bp);
					if (view != null && !s.setView(view)) {
						throw new DevBridge.DevException("view " + view + " is not available for blueprint " + s.selectedBlueprint());
					}
					return state(mc);
				});
			});
		DevBridge.register("dev.hub.state", 10_000, "{} - the hub: open, tab, sub, selections, armed remove, last action, buildings, blueprints, "
			+ "rendered previews of the selected blueprint (paths tried, found, load state), buttons on screen", (req, mc) -> DevBridge.onClient(mc,
				() -> state(mc)));
		DevBridge.register("dev.hub.action", 30_000, "{action: tab|select|view|home|teleport|remove|place_new|place|design_new, tab?, buildingId?, "
			+ "blueprint?, repos?: [..] | \"a,b\", view?, confirm?: bool} - press a hub button (opens the hub when closed). home/teleport/"
			+ "remove reply after the server answered; remove without confirm arms it (a second remove for the same id confirms)", (req, mc) -> {
				Fields f = Fields.of(req);
				String action = f.nonBlank("action").toLowerCase(Locale.ROOT);
				String building = f.optStr("buildingId", null);
				String bp = f.optStr("blueprint", null);
				String tabName = f.optStr("tab", null);
				String view = f.optStr("view", null);
				boolean confirm = f.optBool("confirm", false);
				List<String> repos = repoList(f);
				return DevBridge.onClient(mc, () -> act(mc, action, building, bp, tabName, view, confirm, repos)).thenCompose(x -> x)
					.thenCompose(o -> DevBridge.onClient(mc, () -> {
						JsonObject st = state(mc);
						st.add("result", o);
						return st;
					}));
			});
	}

	private static CompletableFuture<JsonObject> act(Minecraft mc, String action, @Nullable String building, @Nullable String bp,
		@Nullable String tabName, @Nullable String view, boolean confirm, List<String> repos) {
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
			default -> throw new DevBridge.DevException("action must be tab|select|view|home|teleport|remove|place_new|place|design_new");
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
			default -> throw new DevBridge.DevException("sub must be buildings or blueprints");
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
		o.addProperty("tab", s == null ? null : s.tab().id);
		o.addProperty("sub", s == null ? null : s.sub().name().toLowerCase(Locale.ROOT));
		o.addProperty("selectedBuilding", s == null ? null : s.selectedBuilding());
		o.addProperty("selectedBlueprint", s == null ? null : s.selectedBlueprint());
		o.addProperty("armedRemove", s == null ? null : s.armedRemove());
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
			Anchors.Bounds box = b.box();
			j.addProperty("box", box.minX() + "," + box.minY() + "," + box.minZ() + " .. " + box.maxX() + "," + box.maxY() + "," + box.maxZ());
			j.addProperty("hasEntrance", b.anchors().containsKey("entrance"));
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
			bps.add(j);
		}
		o.add("blueprints", bps);
		if (s != null && s.selectedBlueprint() != null) {
			o.add("previews", PreviewImages.describe(s.selectedBlueprint()));
		} else {
			o.add("previews", null);
		}
		o.add("previewTextures", PreviewImages.stats());
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
