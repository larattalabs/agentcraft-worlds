package dev.agentcraft.client.ui;

import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.ui.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * QA for the fix-wave UI rules (docs/QA.md): {@code dev.state} gains {@code ui} (screens pause?, the open
 * screen's pause flag and parent, the game's paused state, crash-guard counts); {@code dev.ui.pause {on?}}
 * forces C6 pausing on/off for this session (omit {@code on} for the environment's default);
 * {@code dev.guard.inject {kind}} makes the next run of a guarded handler throw (it must be caught,
 * logged once and counted; the game keeps running).
 */
public final class UiDev {
	private UiDev() {
	}

	public static void init() {
		DevBridge.addStateContributor((mc, o) -> o.add("ui", state(mc)));
		DevBridge.register("dev.ui.pause", 5_000, "{on?: bool} - force AgentCraft screens to pause (true) or not (false) in singleplayer; omit = default",
			(req, mc) -> {
				Boolean on = Fields.of(req).optBool("on");
				return DevBridge.onClient(mc, () -> {
					ScreenPause.set(on);
					return state(mc);
				});
			});
		DevBridge.register("dev.player.sneak", 5_000, "{on: bool} - hold (or release) the sneak key, as a held Shift would (agents are targetable only"
			+ " while sneaking with an empty main hand)", (req, mc) -> {
				boolean on = Fields.of(req).bool("on");
				return DevBridge.onClient(mc, () -> {
					mc.options.keyShift.setDown(on);
					JsonObject o = new JsonObject();
					o.addProperty("sneakKeyDown", mc.options.keyShift.isDown());
					o.addProperty("sneaking", mc.player != null && mc.player.isShiftKeyDown());
					o.addProperty("mainHandEmpty", mc.player != null && mc.player.getMainHandItem().isEmpty());
					return o;
				});
			});
		DevBridge.register("dev.guard.inject", 5_000,
			"{kind} - the next run of that guarded client handler throws (agents.tick, agents.plates, hq.tick, wizard.tick, ...); see dev.state ui.guards",
			(req, mc) -> {
				String kind = Fields.of(req).nonBlank("kind");
				return DevBridge.onClient(mc, () -> {
					Guard.inject(kind);
					JsonObject o = new JsonObject();
					o.addProperty("injected", kind);
					return o;
				});
			});
	}

	static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		o.addProperty("screensPause", ScreenPause.pauses());
		Screen s = mc.gui.screen();
		o.addProperty("screen", s == null ? null : s.getClass().getSimpleName());
		o.addProperty("screenPauses", s != null && s.isPauseScreen());
		o.addProperty("gamePaused", mc.isPaused());
		Screen p = s instanceof HasParent hp ? hp.parent() : null;
		o.addProperty("parent", p == null ? null : p.getClass().getSimpleName());
		JsonObject g = new JsonObject();
		Guard.counts().forEach(g::addProperty);
		o.add("guards", g);
		return o;
	}
}
