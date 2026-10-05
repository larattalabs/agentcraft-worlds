package dev.agentcraft.client.hud;

import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.hud.HudPeek;
import dev.agentcraft.hud.HudSettings;
import dev.agentcraft.ui.Guard;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/**
 * HUD: the Foreman connection pill / auth banner ({@link ConnectionBanner}), the overlay in the chosen style (Off, Pill,
 * Pill+, Panel: {@link HudOverlay}, {@link PillStyle}, {@link GoalBar}; settings {@link HudConfig}, cycle key), paper toasts for
 * {@code notify} ({@link Toasts}), the decision bell / done chime ({@link HudSounds}), and the wave 2 check-in
 * helpers ({@link HudWatch}: away toast, H to the last tab, welcome card).
 *
 * <p>QA: {@code dev.toast {text, level?, decisionId?}} shows a toast without the Foreman,
 * {@code dev.hud.state} reports what the HUD shows (style, position, size, rect, peek, hidden reason, overlaps, waiting
 * count, alert line, goal bar pick, toasts, sounds, away state), {@code dev.hud.set} changes the overlay settings,
 * {@code dev.hud.peek} shows a peek, {@code dev.away {minutes}}, {@code dev.onboarding {reset?, show?}}; screens {@code welcome},
 * {@code hub_status_help}.
 */
public final class HudFeature {
	/** dev.hud.vanilla's toast: 10 s. */
	private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId QA_TOAST =
		new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(10_000L);

	private HudFeature() {
	}

	public static void init() {
		Keys.ensureRegistered();
		HudElementRegistry.addLast(AgentCraft.id("hud/connection"), dev.agentcraft.client.ui.GuardedHud.of("hud.connection", new ConnectionBanner()));
		// the overlay (style from hub Settings > General > HUD); keeps the old element id and guard kind
		HudElementRegistry.addLast(AgentCraft.id("hud/goal"), dev.agentcraft.client.ui.GuardedHud.of("hud.goal", new HudOverlay()));
		HudElementRegistry.addLast(AgentCraft.id("hud/toasts"), dev.agentcraft.client.ui.GuardedHud.of("hud.toasts", new Toasts()));
		Toasts.init();
		HudPeeks.init();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("hud.overlay", () -> {
			HudCombat.tick(mc);
			while (Keys.hudStyle != null && Keys.hudStyle.consumeClick()) {
				cycleStyle(mc);
			}
		}));
		HudSounds.init();
		HudWatch.init();
		DevBridge.registerScreen("welcome", mc -> new WelcomeScreen());
		DevBridge.register("dev.onboarding", 10_000, "{reset?: bool, show?: bool, press?: open_hub|got_it} - the welcome card: reset forgets its "
			+ "dismissal in this world, show opens it, press presses a button of the open card; replies with the onboarding state", (req, mc) -> {
				Fields f = Fields.of(req);
				boolean reset = f.optBool("reset", false);
				boolean show = f.optBool("show", false);
				String press = f.optStr("press", null);
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
					if (press != null) {
						if (!(mc.gui.screen() instanceof WelcomeScreen w) || !w.press(press)) {
							throw new DevBridge.DevException("press: no welcome card open with a button '" + press + "' (open_hub, got_it; draw a frame first)");
						}
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
		DevBridge.register("dev.hud.set", 10_000, "{style?: off|pill|pill_plus|panel, position?: top_right|top_left|bottom_left|bottom_right|"
			+ "right_middle, size?: s|m|l, peek?, autoHide?, hideInCombat?, toasts?: needs_you|all, topLeftOffset?: 0-200, cycle?: bool (the cycle "
			+ "key), clearChat?: bool, clearPeek?: bool} - change the overlay settings (saved to hud.json like the hub's Settings > General > HUD); "
			+ "replies dev.hud.state", (req, mc) -> {
				Fields f = Fields.of(req);
				HudSettings.Style style = f.has("style") ? parsed("style", HudSettings.parseStyle(f.nonBlank("style"))) : null;
				HudSettings.Position pos = f.has("position") ? parsed("position", HudSettings.parsePosition(f.nonBlank("position"))) : null;
				HudSettings.Size size = f.has("size") ? parsed("size", HudSettings.parseSize(f.nonBlank("size"))) : null;
				HudSettings.Toasts toasts = f.has("toasts") ? parsed("toasts", HudSettings.parseToasts(f.nonBlank("toasts"))) : null;
				Boolean peek = f.has("peek") ? f.optBool("peek", true) : null;
				Boolean autoHide = f.has("autoHide") ? f.optBool("autoHide", true) : null;
				Boolean combat = f.has("hideInCombat") ? f.optBool("hideInCombat", false) : null;
				Integer offset = f.has("topLeftOffset") ? f.optInt("topLeftOffset", HudSettings.OFFSET_DEFAULT, 0, HudSettings.OFFSET_MAX) : null;
				boolean cycle = f.optBool("cycle", false);
				boolean clearChat = f.optBool("clearChat", false);
				boolean clearPeek = f.optBool("clearPeek", false);
				return DevBridge.onClient(mc, () -> {
					HudSettings s = HudConfig.get();
					if (style != null) {
						s.setStyle(style);
					}
					if (pos != null) {
						s.setPosition(pos);
					}
					if (size != null) {
						s.setSize(size);
					}
					if (toasts != null) {
						s.setToasts(toasts);
					}
					if (peek != null) {
						s.setPeek(peek);
					}
					if (autoHide != null) {
						s.setAutoHide(autoHide);
					}
					if (combat != null) {
						s.setHideInCombat(combat);
					}
					if (offset != null) {
						s.setTopLeftOffset(offset);
					}
					if (cycle) {
						cycleStyle(mc);
					}
					HudConfig.save();
					if (clearChat) {
						mc.gui.hud.getChat().clearMessages(false);
					}
					if (clearPeek) {
						HudPeeks.clear();
					}
					return hudState();
				});
			});
		DevBridge.register("dev.hud.vanilla", 10_000, "{subtitles?: bool (the Show Subtitles option, this session, not saved), toast?: text (a "
			+ "vanilla system toast in the top right, 10 s)} - put vanilla's HUD extras on screen for the layout checks; the sidebar comes from "
			+ "/scoreboard objectives setdisplay sidebar, more toasts from /advancement or /recipe, subtitles from /playsound; replies dev.hud.state",
			(req, mc) -> {
				Fields f = Fields.of(req);
				Boolean subtitles = f.has("subtitles") ? f.optBool("subtitles", true) : null;
				String toast = f.has("toast") ? f.nonBlank("toast") : null;
				return DevBridge.onClient(mc, () -> {
					if (subtitles != null) {
						mc.options.showSubtitles().set(subtitles);
					}
					if (toast != null) {
						net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.gui.toastManager(), QA_TOAST, net.minecraft.network.chat.Component
							.literal(toast), net.minecraft.network.chat.Component.literal("AgentCraft layout check"));
					}
					return hudState();
				});
			});
		DevBridge.register("dev.hud.peek", 10_000, "{text, kind?: task_done|pr_merged|decision|goal_done} - show a peek on the overlay (whatever the "
			+ "peek setting); replies dev.hud.state", (req, mc) -> {
				Fields f = Fields.of(req);
				String text = f.nonBlank("text");
				String kind = f.has("kind") ? f.nonBlank("kind").toLowerCase(Locale.ROOT) : HudPeek.TASK_DONE;
				if (!List.of(HudPeek.TASK_DONE, HudPeek.PR_MERGED, HudPeek.DECISION, HudPeek.GOAL_DONE).contains(kind)) {
					throw new DevBridge.DevException("kind must be task_done, pr_merged, decision or goal_done");
				}
				return DevBridge.onClient(mc, () -> {
					HudPeeks.force(kind, text);
					return hudState();
				});
			});
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

	private static <T> T parsed(String field, @org.jspecify.annotations.Nullable T v) {
		if (v == null) {
			throw new DevBridge.DevException("unknown " + field);
		}
		return v;
	}

	/** The cycle key: next style, saved, and said on the action bar. */
	static void cycleStyle(net.minecraft.client.Minecraft mc) {
		HudSettings s = HudConfig.get();
		HudSettings.Style st = s.cycleStyle();
		HudConfig.save();
		if (mc.player != null) {
			mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal("AgentCraft HUD: " + st.label()), false);
		}
	}

	static JsonObject hudState() {
		JsonObject o = new JsonObject();
		HudOverlay.state(o);
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
		o.add("toasts", Toasts.json());
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
		o.addProperty("hudStyleKey", Keys.label(Keys.hudStyle));
		// what Options > Controls shows for them (proves the lang keys resolve)
		JsonObject names = new JsonObject();
		for (var k : new net.minecraft.client.KeyMapping[] {Keys.console, Keys.terminal, Keys.decisions, Keys.build, Keys.hudStyle}) {
			if (k != null) {
				names.addProperty(k.getName(), net.minecraft.client.resources.language.I18n.get(k.getName()) + " [" + k.getCategory().label().getString() + "]");
			}
		}
		o.add("keyNames", names);
		return o;
	}
}
