package dev.agentcraft.client.console;

import dev.agentcraft.ui.Guard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.client.console.ConsoleCommands.Completion;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.hud.Keys;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Command console: {@link ConsoleScreen} on the console key ({@code `}, rebindable in Controls), on
 * Enter while looking at a console terminal, or a right-click on one. Input language:
 * {@link ConsoleCommands} (docs/protocol.md "Console mapping"); sending + acks: {@link ConsoleActions}.
 * The terminal's leaning screen shows the prompt, the last command and what is waiting
 * ({@link ConsoleTerminalRenderer}).
 *
 * <p>QA: {@code dev.screen {open:"console"}} then {@code dev.type "@ju"} (autocomplete + ghost);
 * {@code dev.console {prefill?, submit?}} opens it with text (and presses Enter);
 * {@code dev.console.parse {text}} shows what an input would do without sending it.
 */
public final class ConsoleFeature {
	private ConsoleFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.CONSOLE_TERMINAL, ctx -> new ConsoleTerminalRenderer());
		Keys.ensureRegistered();
		DevBridge.registerScreen("console", mc -> ConsoleScreen.forDev(null));
		dev.agentcraft.client.world.StationInteractions.onUse(ModBlocks.CONSOLE_TERMINAL, (player, pos, state, be) -> openAtTerminal(pos));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("console.tick", () -> {
			if (mc.player == null) {
				return;
			}
			while (Keys.console.consumeClick()) {
				if (mc.gui.screen() == null) {
					open(null, true);
				}
			}
			while (Keys.terminal.consumeClick()) {
				if (mc.gui.screen() == null && lookingAtTerminal(mc)) {
					openAtTerminal(((BlockHitResult) mc.hitResult).getBlockPos());
				}
			}
		}));
		registerDev();
	}

	public static void open(String prefill, boolean byKey) {
		Minecraft mc = Minecraft.getInstance();
		ConsoleScreen s = new ConsoleScreen(prefill);
		if (byKey) {
			s.openedByKey();
		}
		mc.gui.setScreen(s);
	}

	/** The console of the terminal at {@code pos}: goals default to the repo of the building it stands in. */
	public static void openAtTerminal(net.minecraft.core.BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		ConsoleScreen s = new ConsoleScreen(null);
		dev.agentcraft.building.Building b = terminalBuilding(mc, pos);
		if (b != null) {
			s.atBuilding(b.id(), b.repos());
		}
		mc.gui.setScreen(s);
	}

	/** The recorded building whose box holds the block (in the player's dimension), or null. */
	static dev.agentcraft.building.@org.jspecify.annotations.Nullable Building terminalBuilding(Minecraft mc, net.minecraft.core.BlockPos pos) {
		if (mc.level == null) {
			return null;
		}
		String dim = mc.level.dimension().identifier().toString();
		return dev.agentcraft.ui.UiRules.containing(dev.agentcraft.building.Buildings.all(), dev.agentcraft.building.Building::box,
			dev.agentcraft.building.Building::dimension, dim, pos.getX(), pos.getY(), pos.getZ());
	}

	private static boolean lookingAtTerminal(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit == null || hit.getType() != HitResult.Type.BLOCK || mc.level == null) {
			return false;
		}
		BlockState st = mc.level.getBlockState(((BlockHitResult) hit).getBlockPos());
		return st.getBlock() == ModBlocks.CONSOLE_TERMINAL;
	}

	// ------------------------------------------------------------------ dev

	private static void registerDev() {
		DevBridge.register("dev.console", 15_000,
			"{prefill?: text, submit?: bool, rosterCard?: agentId, terminal?: 'x y z'} - open the console (with text in the input; submit presses Enter"
				+ " (plain text: the first Enter asks, a second submit creates the goal); rosterCard = right-click that roster chip: the agent card, Esc"
				+ " back to the console; terminal = as the console terminal block there opens it: goals default to its building's repo) and report its state",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String rosterCard = f.optStr("rosterCard", null);
				String terminal = f.optStr("terminal", null);
				String prefill = f.has("prefill") ? f.str("prefill") : null;
				boolean submit = f.optBool("submit", false);
				boolean open = f.optBool("open", true);
				return DevBridge.onClient(mc, () -> {
					if (terminal != null) {
						String[] p = terminal.trim().split("[ ,]+");
						if (p.length != 3) {
							throw new DevBridge.DevException("terminal: 'x y z'");
						}
						openAtTerminal(new net.minecraft.core.BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
						if (prefill != null && mc.gui.screen() instanceof ConsoleScreen cs) {
							cs.setValue(prefill);
						}
					} else if (open && !(mc.gui.screen() instanceof ConsoleScreen)) {
						mc.gui.setScreen(ConsoleScreen.forDev(prefill));
					} else if (prefill != null && mc.gui.screen() instanceof ConsoleScreen cs) {
						cs.setValue(prefill);
					}
					if (submit && mc.gui.screen() instanceof ConsoleScreen cs) {
						cs.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
					}
					if (rosterCard != null && mc.gui.screen() instanceof ConsoleScreen cs
						&& !dev.agentcraft.client.agents.AgentsFeature.openCard(rosterCard, cs)) {
						throw new DevBridge.DevException("no agent '" + rosterCard + "'");
					}
					return state(mc);
				});
			});
		DevBridge.register("dev.console.parse", 10_000, "{text} - what the console would do with this input (nothing is sent)", (req, mc) -> {
			String text = Fields.of(req).str("text");
			return DevBridge.onClient(mc, () -> {
				JsonObject o = new JsonObject();
				var intent = ConsoleCommands.parse(text, Foreman.state());
				for (Map.Entry<String, Object> e : ConsoleCommands.toMap(intent).entrySet()) {
					o.add(e.getKey(), DevBridge.GSON.toJsonTree(e.getValue()));
				}
				o.addProperty("describe", ConsoleCommands.describe(intent, Foreman.state()));
				JsonArray comps = new JsonArray();
				for (Completion c : ConsoleCommands.complete(text, text.length(), Foreman.state())) {
					comps.add(c.replacement());
				}
				o.add("completions", comps);
				return o;
			});
		});
	}

	static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		if (mc.gui.screen() instanceof ConsoleScreen cs) {
			o.addProperty("open", true);
			o.addProperty("value", cs.value());
			o.addProperty("ghost", cs.ghost());
			JsonArray comps = new JsonArray();
			for (Completion c : cs.completions()) {
				comps.add(c.label());
			}
			o.add("completions", comps);
			o.addProperty("repoChooser", cs.repoChooserOpen());
			o.addProperty("goalConfirm", cs.goalConfirmArmed());
			o.addProperty("building", cs.buildingId());
			o.addProperty("preferRepo", cs.preferRepo());
			o.addProperty("parent", cs.parent() == null ? null : cs.parent().getClass().getSimpleName());
			var intent = cs.intent();
			o.addProperty("intent", intent == null ? null : ConsoleCommands.describe(intent, Foreman.state()));
		} else {
			o.addProperty("open", false);
		}
		o.add("actions", DevBridge.GSON.toJsonTree(ConsoleActions.stats()));
		o.addProperty("historySize", ConsoleLog.history().size());
		o.addProperty("draft", ConsoleLog.draft());
		o.addProperty("lines", ConsoleLog.lines().size());
		return o;
	}
}
