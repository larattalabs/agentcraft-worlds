package dev.agentcraft.client.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.BlueprintTransform;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.BuildingCommands;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.hud.Keys;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The building wizard (docs/BUILDINGS.md "Wizard (client)"): {@code /agentcraft build} or {@code B}
 * opens {@link RepoPickScreen} (step 1), then {@link BlueprintPickScreen} (step 2), then placement
 * mode ({@link BuildPlacement}): a translucent ghost ({@link GhostRenderer}) that follows the
 * player's look, a HUD ({@link PlacementHud}) and keys handled before vanilla
 * ({@code KeyboardHandlerMixin} -> {@link #onKey}): R rotate (Shift+R back), arrows nudge, PgUp/PgDn
 * raise/lower, L lock, Enter place, Shift+Enter force (after a block-entity refusal), Esc/Backspace
 * cancel.
 *
 * <p>Singleplayer only: confirm runs {@code Buildings.place} on the integrated server. The key is
 * not gated on the gamemaster level the {@code /agentcraft} commands need: in singleplayer the world
 * is the player's own, and placing is explicit and reversible ({@code /agentcraft remove}).
 *
 * <p>QA: {@code dev.build.*} (see {@link #registerDev}), screens {@code build_repos} and
 * {@code build_blueprints} via {@code dev.screen}.
 */
public final class BuildingWizardFeature {
	private BuildingWizardFeature() {
	}

	public static void init() {
		Keys.ensureRegistered();
		BuildingCommands.wizardOpener = player -> Minecraft.getInstance().execute(BuildingWizardFeature::open);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (Keys.build.consumeClick()) {
				if (mc.player != null && mc.gui.screen() == null && !BuildPlacement.active() && !PlotMarker.active()) {
					open();
				}
			}
			BuildPlacement.tick(mc);
			PlotMarker.tick(mc);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
			BuildPlacement.cancel();
			PlotMarker.cancelQuietly();
		}));
		LevelRenderEvents.COLLECT_SUBMITS.register(GhostRenderer::submit);
		LevelRenderEvents.COLLECT_SUBMITS.register(GhostRenderer::submitPlot);
		HudElementRegistry.addLast(AgentCraft.id("hud/building_wizard"), new PlacementHud());
		HudElementRegistry.addLast(AgentCraft.id("hud/plot_marker"), new PlotHud());
		DevBridge.registerScreen("build_repos", mc -> new RepoPickScreen(List.of()));
		DevBridge.registerScreen("build_blueprints", mc -> new BlueprintPickScreen(defaultRepos(1)));
		registerDev();
	}

	/** Opens step 1 (cancels a placement in progress, keeping its repos picked). */
	public static void open() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		List<String> keep = BuildPlacement.active() ? BuildPlacement.repos() : List.of();
		BuildPlacement.cancel();
		mc.gui.setScreen(new RepoPickScreen(keep));
	}

	/**
	 * Opens the repo step for a blueprint chosen up front (the hub's "Place"): picking repos goes straight
	 * to placement mode with it. Cancels a placement in progress.
	 */
	public static void openFor(String blueprintId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		BuildPlacement.cancel();
		mc.gui.setScreen(new RepoPickScreen(List.of(), blueprintId));
	}

	/**
	 * The repo step for a blueprint that goes on a known spot (the hub's "Place on the plot"): picking repos
	 * enters placement mode locked at {@code origin} (rotated box minimum) with {@code turns}; the player can
	 * still rotate, nudge or unlock it, and nothing is placed without Enter.
	 */
	public static void openForSpot(String blueprintId, int[] origin, int turns) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		BuildPlacement.cancel();
		mc.gui.setScreen(new RepoPickScreen(List.of(), blueprintId, new int[] {origin[0], origin[1], origin[2], turns}));
	}

	/** What {@code dev.build.state} reports about placement mode. */
	public static JsonObject placementState() {
		return BuildPlacement.state();
	}

	/** {@link #placeNow}, then locks the ghost at {@code origin} with {@code turns}. */
	public static @Nullable String placeNowAt(String blueprintId, List<String> repos, int[] origin, int turns) {
		String why = placeNow(blueprintId, repos);
		if (why == null) {
			BuildPlacement.lockAt(origin[0], origin[1], origin[2], turns);
		}
		return why;
	}

	/**
	 * Enters placement mode with {@code blueprintId} for {@code repos} (closes any screen). Returns why it
	 * cannot (unknown blueprint, wrong repo count, not singleplayer), or null when placement started.
	 */
	public static @Nullable String placeNow(String blueprintId, List<String> repos) {
		Blueprint bp = Blueprints.get(blueprintId);
		if (bp == null) {
			return "Unknown blueprint '" + blueprintId + "'";
		}
		if (repos.isEmpty()) {
			return "Name at least one repo";
		}
		String why = RepoPickScreen.fits(bp, repos.size());
		if (why != null) {
			return why;
		}
		try {
			BuildPlacement.start(blueprintId, repos);
			return null;
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
	}

	/**
	 * Placement-mode keys, called by the keyboard mixin before vanilla handles a key. Returns true when
	 * the key was consumed. Only while placing with no screen open; releases always pass.
	 */
	public static boolean onKey(int action, KeyEvent e) {
		if (action == InputConstants.RELEASE || !BuildPlacement.active() && !PlotMarker.active()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null) {
			return false;
		}
		boolean repeat = action != InputConstants.PRESS;
		if (PlotMarker.active()) {
			return plotKey(e, repeat);
		}
		switch (e.key()) {
			case InputConstants.KEY_R -> {
				if (!repeat) {
					BuildPlacement.rotate(e.hasShiftDown() ? -1 : 1);
				}
			}
			case InputConstants.KEY_UP -> BuildPlacement.nudge(1, 0, 0);
			case InputConstants.KEY_DOWN -> BuildPlacement.nudge(-1, 0, 0);
			case InputConstants.KEY_LEFT -> BuildPlacement.nudge(0, -1, 0);
			case InputConstants.KEY_RIGHT -> BuildPlacement.nudge(0, 1, 0);
			case InputConstants.KEY_PAGEUP -> BuildPlacement.nudge(0, 0, 1);
			case InputConstants.KEY_PAGEDOWN -> BuildPlacement.nudge(0, 0, -1);
			case InputConstants.KEY_L -> {
				if (!repeat) {
					BuildPlacement.setLocked(!BuildPlacement.locked());
				}
			}
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				if (!repeat) {
					BuildPlacement.confirm(e.hasShiftDown());
				}
			}
			case InputConstants.KEY_ESCAPE, InputConstants.KEY_BACKSPACE -> {
				if (!repeat) {
					BuildPlacement.cancel();
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	/** Plot-marking keys: Enter corner, PgUp/PgDn height (Shift: 4), Backspace back a corner, Esc cancel. */
	private static boolean plotKey(KeyEvent e, boolean repeat) {
		switch (e.key()) {
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				if (!repeat) {
					PlotMarker.confirm();
				}
			}
			case InputConstants.KEY_PAGEUP -> PlotMarker.adjustHeight(e.hasShiftDown() ? 4 : 1);
			case InputConstants.KEY_PAGEDOWN -> PlotMarker.adjustHeight(e.hasShiftDown() ? -4 : -1);
			case InputConstants.KEY_BACKSPACE -> {
				if (!repeat) {
					PlotMarker.back();
				}
			}
			case InputConstants.KEY_ESCAPE -> {
				if (!repeat) {
					PlotMarker.cancel();
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	/** Up to {@code n} Foreman repos without a building (for the screen factories); "demo" when there are none. */
	static List<String> defaultRepos(int n) {
		List<String> out = new ArrayList<>();
		ForemanState s = Foreman.state();
		if (s != null) {
			for (Repo r : s.repos().values()) {
				if (out.size() < n && Buildings.forRepo(r.id()) == null) {
					out.add(r.id());
				}
			}
		}
		if (out.isEmpty()) {
			out.add("demo");
		}
		return out;
	}

	// ------------------------------------------------------------------ dev

	private static List<String> repoList(Fields f, String field) {
		JsonElement el = f.json().get(field);
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

	private static int[] xyz(Fields f, String field) {
		JsonElement el = f.json().get(field);
		String[] parts;
		if (el.isJsonArray()) {
			JsonArray a = el.getAsJsonArray();
			if (a.size() != 3) {
				throw new DevBridge.DevException(field + " must be [x, y, z]");
			}
			return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
		}
		parts = el.getAsString().trim().split("[\\s,]+");
		if (parts.length != 3) {
			throw new DevBridge.DevException(field + " must be \"x y z\" or [x, y, z]");
		}
		try {
			return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
		} catch (NumberFormatException e) {
			throw new DevBridge.DevException(field + " must hold integers");
		}
	}

	private static void registerDev() {
		DevBridge.register("dev.build.open", 10_000, "{step?: repos|blueprints, repos?: [..] | \"a,b\", blueprint?} - open a wizard screen "
			+ "(blueprints: for repos, default the first Foreman repo without a building)", (req, mc) -> {
				Fields f = Fields.of(req);
				String step = f.optStr("step", "repos");
				List<String> repos = repoList(f, "repos");
				String bp = f.optStr("blueprint", null);
				return DevBridge.onClient(mc, () -> {
					if (mc.player == null) {
						throw new DevBridge.DevException("not in a world");
					}
					BuildPlacement.cancel();
					switch (step) {
						case "repos" -> mc.gui.setScreen(new RepoPickScreen(repos));
						case "blueprints" -> {
							BlueprintPickScreen s = new BlueprintPickScreen(repos.isEmpty() ? defaultRepos(1) : repos);
							mc.gui.setScreen(s);
							if (bp != null && !s.select(bp)) {
								throw new DevBridge.DevException("blueprint " + bp + " is not offered for these repos");
							}
						}
						default -> throw new DevBridge.DevException("step must be repos or blueprints");
					}
					return screenState(mc);
				});
			});
		DevBridge.register("dev.build.start", 10_000, "{blueprint, repos: [..] | \"a,b\", origin?: [x,y,z] (rotated box minimum; locks the ghost "
			+ "there), turns?: 0-3 | rotation name} - enter placement mode", (req, mc) -> {
				Fields f = Fields.of(req);
				String bp = f.nonBlank("blueprint");
				List<String> repos = repoList(f, "repos");
				if (repos.isEmpty()) {
					throw new DevBridge.DevException("repos: name at least one repo");
				}
				int[] origin = f.has("origin") ? xyz(f, "origin") : null;
				int turns = 0;
				if (f.has("turns")) {
					JsonElement t = f.json().get("turns");
					turns = t.getAsJsonPrimitive().isNumber() ? t.getAsInt() : BlueprintTransform.parseTurns(t.getAsString());
					if (turns < 0 || turns > 3) {
						throw new DevBridge.DevException("turns must be 0-3 or a rotation name");
					}
				}
				int fturns = turns;
				return DevBridge.onClient(mc, () -> {
					try {
						BuildPlacement.start(bp, repos);
					} catch (IllegalArgumentException e) {
						throw new DevBridge.DevException(e.getMessage());
					}
					if (origin != null) {
						BuildPlacement.lockAt(origin[0], origin[1], origin[2], fturns);
					} else if (fturns != 0) {
						BuildPlacement.rotate(fturns);
					}
					return BuildPlacement.state();
				});
			});
		DevBridge.register("dev.build.state", 10_000, "{} - placement mode: blueprint, repos, origin, rotation, box, conflicts "
			+ "{obstructed, blockEntities, refusals, wouldPlace}, render stats, last result; plus the open wizard screen", (req, mc) -> DevBridge
				.onClient(mc, () -> {
					JsonObject o = BuildPlacement.state();
					o.add("screen", screenState(mc).get("screen"));
					return o;
				}));
		DevBridge.register("dev.build.rotate", 10_000, "{turns?: 1} - rotate the ghost by quarter turns (clockwise; negative = back)", (req, mc) -> {
			int t = Fields.of(req).optInt("turns", 1, -3, 3);
			return DevBridge.onClient(mc, () -> {
				requireActive();
				BuildPlacement.rotate(t);
				return BuildPlacement.state();
			});
		});
		DevBridge.register("dev.build.nudge", 10_000, "{forward?, right?, up?} - move the ghost (blocks, relative to where the player faces)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				int fw = f.optInt("forward", 0, -256, 256);
				int rt = f.optInt("right", 0, -256, 256);
				int up = f.optInt("up", 0, -256, 256);
				return DevBridge.onClient(mc, () -> {
					requireActive();
					BuildPlacement.nudge(fw, rt, up);
					return BuildPlacement.state();
				});
			});
		DevBridge.register("dev.build.lock", 10_000, "{on?: bool (default: toggle)} - lock the ghost where it is / follow the look again",
			(req, mc) -> {
				Boolean on = Fields.of(req).optBool("on");
				return DevBridge.onClient(mc, () -> {
					requireActive();
					BuildPlacement.setLocked(on == null ? !BuildPlacement.locked() : on);
					return BuildPlacement.state();
				});
			});
		DevBridge.register("dev.build.confirm", 30_000, "{force?: bool} - place it (Enter; force = Shift+Enter, only after a block-entity "
			+ "refusal); replies when the server answered: {placed, buildingId, message} + state", (req, mc) -> {
				boolean force = Fields.of(req).optBool("force", false);
				return DevBridge.onClient(mc, () -> {
					requireActive();
					return BuildPlacement.confirm(force);
				}).thenCompose(f -> f).thenCompose(r -> DevBridge.onClient(mc, () -> {
					JsonObject o = BuildPlacement.state();
					o.addProperty("placed", r.placed());
					o.addProperty("buildingId", r.buildingId());
					o.addProperty("message", r.message());
					return o;
				}));
			});
		DevBridge.register("dev.build.cancel", 10_000, "{} - leave placement mode (Esc)", (req, mc) -> DevBridge.onClient(mc, () -> {
			BuildPlacement.cancel();
			return BuildPlacement.state();
		}));
	}

	private static void requireActive() {
		if (!BuildPlacement.active()) {
			throw new DevBridge.DevException("not in placement mode (dev.build.start first)");
		}
	}

	private static JsonObject screenState(Minecraft mc) {
		JsonObject o = new JsonObject();
		JsonObject sc = new JsonObject();
		if (mc.gui.screen() instanceof RepoPickScreen rs) {
			sc.addProperty("step", "repos");
			sc.addProperty("textMode", rs.textMode());
			sc.addProperty("blueprint", rs.fixedBlueprint());
			sc.addProperty("onPlot", rs.lockAt() != null);
			sc.addProperty("error", rs.error());
			JsonArray a = new JsonArray();
			rs.chosen().forEach(a::add);
			sc.add("chosen", a);
		} else if (mc.gui.screen() instanceof BlueprintPickScreen bs) {
			sc.addProperty("step", "blueprints");
			JsonArray a = new JsonArray();
			bs.repos().forEach(a::add);
			sc.add("repos", a);
			sc.addProperty("selected", bs.current() == null ? null : bs.current().id());
			sc.addProperty("descriptionRows", bs.descriptionRowsShown());
		} else {
			sc = null;
		}
		o.add("screen", sc);
		return o;
	}
}
