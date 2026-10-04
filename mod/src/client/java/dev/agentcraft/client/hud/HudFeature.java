package dev.agentcraft.client.hud;

import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/**
 * HUD: the Foreman connection pill / auth banner ({@link ConnectionBanner}), the boss-bar style goal
 * progress with the decisions badge and the alert line ({@link GoalBar}, {@link Alerts}), paper toasts for
 * {@code notify} ({@link Toasts}), the decision bell / done chime ({@link HudSounds}), and the wave 2 check-in
 * helpers ({@link HudWatch}: away toast, H to the last tab, welcome card).
 *
 * <p>QA: {@code dev.toast {text, level?, decisionId?}} shows a toast without the Foreman,
 * {@code dev.hud.state} reports what the HUD shows (waiting count, alert line, goal bar pick, toasts, sounds,
 * away state), {@code dev.away {minutes}}, {@code dev.onboarding {reset?, show?}}; screens {@code welcome},
 * {@code hub_status_help}.
 */
public final class HudFeature {
	private HudFeature() {
	}

	public static void init() {
		Keys.ensureRegistered();
		HudElementRegistry.addLast(AgentCraft.id("hud/connection"), dev.agentcraft.client.ui.GuardedHud.of("hud.connection", new ConnectionBanner()));
		HudElementRegistry.addLast(AgentCraft.id("hud/goal"), dev.agentcraft.client.ui.GuardedHud.of("hud.goal", new GoalBar()));
		HudElementRegistry.addLast(AgentCraft.id("hud/toasts"), dev.agentcraft.client.ui.GuardedHud.of("hud.toasts", new Toasts()));
		Toasts.init();
		HudSounds.init();
		HudWatch.init();
		DevBridge.registerScreen("welcome", mc -> new WelcomeScreen());
		DevBridge.register("dev.onboarding", 10_000, "{reset?: bool, show?: bool} - the welcome card: reset forgets its dismissal in this world, "
			+ "show opens it; replies with the onboarding state", (req, mc) -> {
				Fields f = Fields.of(req);
				boolean reset = f.optBool("reset", false);
				boolean show = f.optBool("show", false);
				return DevBridge.onClient(mc, () -> {
					if (mc.player == null) {
						throw new DevBridge.DevException("not in a world");
					}
					if (reset) {
						HudWatch.resetWelcome();
					}
					if (show) {
						mc.gui.setScreen(new WelcomeScreen());
					}
					JsonObject o = HudWatch.json();
					o.addProperty("screen", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
					o.add("welcomeLayout", WelcomeScreen.layoutJson());
					o.add("help", HelpContent.json());
					return o;
				});
			});
		DevBridge.register("dev.away", 30_000, "{minutes: 1-100000} - pretend the hub was last open that long ago and run the away check now "
			+ "(goal.digest since then; toast when a goal moved); replies {digest, toastShown, text, away}", (req, mc) -> {
				int minutes = Fields.of(req).optInt("minutes", 15, 1, 100_000);
				return DevBridge.onClient(mc, () -> {
					if (mc.player == null) {
						throw new DevBridge.DevException("not in a world");
					}
					if (!dev.agentcraft.client.foreman.Foreman.connected()) {
						throw new DevBridge.DevException("the Foreman is not connected");
					}
					int before = Toasts.shown();
					return HudWatch.simulateAway(minutes).thenCompose(st -> DevBridge.onClient(mc, () -> {
						JsonObject o = new JsonObject();
						o.add("digest", dev.agentcraft.client.hub.HubGoals.digestJson(st));
						o.addProperty("toastShown", Toasts.shown() > before);
						o.addProperty("text", Toasts.shown() > before ? Toasts.lastText() : null);
						o.addProperty("hint", Toasts.shown() > before ? Toasts.lastHint() : null);
						o.add("away", HudWatch.json());
						return o;
					}));
				}).thenCompose(x -> x);
			});
		// QA: the vanilla key binds screen, to check the AgentCraft category (dev.screen {open:"keybinds"})
		DevBridge.registerScreen("keybinds", mc -> new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(null, mc.options));
		DevBridge.register("dev.toast", 10_000, "{text, level?: info|warn|need_user, decisionId?} - show an in-game toast (no Foreman needed)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String text = f.nonBlank("text");
				String level = f.has("level") ? f.nonBlank("level").toLowerCase(Locale.ROOT) : "info";
				String did = f.has("decisionId") ? f.nonBlank("decisionId") : null;
				NotifyLevel lv = switch (level) {
					case "info" -> NotifyLevel.INFO;
					case "warn" -> NotifyLevel.WARN;
					case "need_user" -> NotifyLevel.NEED_USER;
					default -> throw new DevBridge.DevException("level must be info, warn or need_user");
				};
				return DevBridge.onClient(mc, () -> {
					Toasts.push(new Notify(lv, text, did, System.currentTimeMillis()));
					return hudState();
				});
			});
		DevBridge.register("dev.hud.state", 10_000, "{} - what the AgentCraft HUD shows: decisions waiting, the alert line (text, parts, layout), "
			+ "the goal bar's pick, away state, toasts, sounds", (req, mc) -> DevBridge
			.onClient(mc, HudFeature::hudState));
		DevBridge.register("dev.hud.guiScale", 10_000, "{scale: 0 (auto) - 6} - change the GUI scale for this session (layout checks; not saved)",
			(req, mc) -> {
				int scale = Fields.of(req).optInt("scale", 3, 0, 6);
				return DevBridge.onClient(mc, () -> {
					mc.options.guiScale().set(scale);
					JsonObject o = new JsonObject();
					o.addProperty("guiScale", mc.getWindow().getGuiScale());
					o.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth());
					o.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
					return o;
				});
			});
	}

	static JsonObject hudState() {
		JsonObject o = new JsonObject();
		o.addProperty("waiting", DecisionsFeature.waitingCount());
		var mc = net.minecraft.client.Minecraft.getInstance();
		o.addProperty("hudHidden", mc.gui.hud.isHidden());
		JsonObject alert = Alerts.json();
		alert.addProperty("drawn", GoalBar.alertLevel != null);
		alert.addProperty("level", GoalBar.alertLevel);
		JsonObject layout = new JsonObject();
		layout.addProperty("needed", GoalBar.alertNeeded);
		layout.addProperty("available", GoalBar.alertAvailable);
		layout.addProperty("overflow", GoalBar.alertOverflow);
		int[] box = GoalBar.alertBox;
		layout.addProperty("x", box[0]);
		layout.addProperty("y", box[1]);
		layout.addProperty("w", box[2]);
		layout.addProperty("h", box[3]);
		layout.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth());
		layout.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
		layout.addProperty("guiScale", mc.getWindow().getGuiScale());
		alert.add("layout", layout);
		o.add("alert", alert);
		var pick = GoalBar.lastPick;
		JsonObject gb = new JsonObject();
		gb.addProperty("goalId", pick == null ? null : pick.id());
		gb.addProperty("index", pick == null ? 0 : pick.index());
		gb.addProperty("open", pick == null ? 0 : pick.open());
		gb.addProperty("more", pick == null ? 0 : pick.more());
		gb.addProperty("pinned", pick != null && pick.pinned());
		o.add("goalBar", gb);
		o.add("away", HudWatch.json());
		o.addProperty("lastToast", Toasts.lastText());
		o.addProperty("lastToastHint", Toasts.lastHint());
		o.addProperty("toastsActive", Toasts.active());
		o.addProperty("toastsShown", Toasts.shown());
		o.addProperty("goalBarBottom", GoalBar.bottom);
		o.addProperty("goalBarRight", GoalBar.right);
		o.addProperty("pillLeft", ConnectionBanner.pillLeft);
		o.addProperty("pillBottom", ConnectionBanner.pillBottom);
		// something the goal bar drew last frame intersected the connection pill (should never be true)
		o.addProperty("goalBarPillClash", GoalBar.pillClash);
		o.addProperty("soundsEnabled", HudSounds.enabled());
		o.addProperty("forcedMute", HudSounds.forcedMute());
		o.addProperty("bells", HudSounds.bells());
		o.addProperty("chimes", HudSounds.chimes());
		o.addProperty("lastSound", HudSounds.lastEvent());
		o.addProperty("consoleKey", Keys.label(Keys.console));
		o.addProperty("decisionsKey", Keys.label(Keys.decisions));
		o.addProperty("terminalKey", Keys.label(Keys.terminal));
		o.addProperty("hubKey", Keys.label(Keys.hub));
		// what Options > Controls shows for them (proves the lang keys resolve)
		JsonObject names = new JsonObject();
		for (var k : new net.minecraft.client.KeyMapping[] {Keys.console, Keys.terminal, Keys.decisions, Keys.build}) {
			if (k != null) {
				names.addProperty(k.getName(), net.minecraft.client.resources.language.I18n.get(k.getName()) + " [" + k.getCategory().label().getString() + "]");
			}
		}
		o.add("keyNames", names);
		return o;
	}
}
