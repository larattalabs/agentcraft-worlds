package dev.agentcraft.client.decisions;

import dev.larattalabs.labui.ui.Guard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.client.agents.AgentsFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.world.ServerTasks;
import dev.agentcraft.client.world.StationInteractions;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Decision Podium + decision GUI. Open decisions come in {@link DecisionQueue} order; the screen is
 * {@link DecisionScreen} (keys 1-9, Tab through the queue). Opened with {@code J} (rebindable), a
 * right-click on the podium, the HUD badge hint, or {@code /decide} in the console. The podium's
 * {@code open} block state follows "any decision open" (set on the integrated server, only when it
 * differs) and its renderer shows the waiting count and the first question above the desk.
 *
 * <p>QA: {@code dev.screen {open:"decision"}} (queue head), {@code dev.decision {decisionId?|kind?}} opens a
 * specific one, {@code dev.decisions} reports the queue and the open screen's state.
 */
public final class DecisionsFeature {
	/** Decisions answered from this client that the Foreman has not confirmed yet (id -> since ms). */
	private static final Map<String, Long> ANSWERING = new HashMap<>();
	/** Last answers sent from this client (for dev.decisions). */
	private static final Map<String, String> ANSWERS = new HashMap<>();
	/** Podium block states we asked the server to change (pos -> wanted open), to not repeat. */
	private static final Map<BlockPos, Boolean> PODIUM_PENDING = new HashMap<>();

	private DecisionsFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.DECISION_PODIUM, ctx -> new DecisionPodiumRenderer());
		Keys.ensureRegistered();
		DevBridge.registerScreen("decision", mc -> new DecisionScreen(null, null));
		StationInteractions.onUse(ModBlocks.DECISION_PODIUM, (player, pos, state, be) -> openPodium(player.level(), pos));
		// the agent card's Answer / Decide: exactly that decision, Esc back to the card (merges: the diff feature's screen)
		for (DecisionKind kind : DecisionKind.values()) {
			if (kind != DecisionKind.MERGE) {
				AgentsFeature.registerDecisionScreen(kind, (mc, d, parent) -> new DecisionScreen(d.id(), parent));
			}
		}
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("agentcraft_worlds.decisions.tick", () -> {
			if (mc.player == null) {
				return;
			}
			while (Keys.decisions.consumeClick()) {
				if (mc.gui.screen() == null) {
					openQueue(null, null);
				}
			}
		}));
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onDecision(@Nullable Decision previous, Decision decision) {
				if (!decision.isOpen()) {
					ANSWERING.remove(decision.id());
				}
			}

			@Override
			public void onSnapshot(ForemanState state) {
				ANSWERING.keySet().removeIf(id -> state.decision(id) == null || !state.decision(id).isOpen());
			}
		});
		registerDev();
	}

	// ------------------------------------------------------------------ API for other features

	/** Open the decision screen at {@code decisionId} (null = the queue head). */
	public static void openQueue(@Nullable String decisionId, @Nullable Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new DecisionScreen(decisionId, parent));
	}

	/**
	 * A podium's right-click (W4, docs/WAVE2.md): the hub Inbox filtered to that podium's decisions
	 * ({@link dev.agentcraft.client.leads.Leads.View#podiumFor}, the filter wave 1 gave the podium), with "All decisions"
	 * for the full queue. A podium outside every building (the HQ studio) shows what the home podium shows.
	 */
	public static void openPodium(net.minecraft.world.level.Level level, BlockPos pos) {
		String building = dev.agentcraft.client.leads.Leads.buildingAt(level, pos.getX(), pos.getY(), pos.getZ(), 0);
		dev.agentcraft.client.hub.Inbox.openPodium(building);
	}

	/**
	 * The podium's queue in the decision screen (before wave 2 its right-click; now the Inbox's "Decision screen" and
	 * {@code dev.decision {podium, screen:true}}), with a "show all" switch.
	 */
	public static void openPodiumScreen(net.minecraft.world.level.Level level, BlockPos pos) {
		String building = dev.agentcraft.client.leads.Leads.buildingAt(level, pos.getX(), pos.getY(), pos.getZ(), 0);
		dev.agentcraft.client.leads.Leads.View v = dev.agentcraft.client.leads.Leads.view();
		String label = building == null || building.equals(v.homeBuilding()) ? "home podium" : building + "'s podium";
		Minecraft.getInstance().gui.setScreen(new DecisionScreen(null, null, d -> dev.agentcraft.client.leads.Leads.view().podiumShows(building, d), label));
	}

	/** An answer was sent from this client (HUD/podium can stop counting it right away). */
	public static void markAnswering(String id) {
		ANSWERING.put(id, System.currentTimeMillis());
	}

	public static void unmarkAnswering(String id) {
		ANSWERING.remove(id);
	}

	public static boolean isAnswering(String id) {
		Long t = ANSWERING.get(id);
		return t != null && System.currentTimeMillis() - t < 15_000;
	}

	/**
	 * Answers a decision the way every answer path does (the decision screen, the hub's goal thread): marks it
	 * as being answered (podiums, badge and the queue skip it), sends {@code decision.answer}, records the
	 * answer on success and unmarks it on a refusal. Completes on the client thread with null when the
	 * Foreman took it, else the reason it did not.
	 */
	public static java.util.concurrent.CompletableFuture<@Nullable String> answer(String id, @Nullable String option, @Nullable String text) {
		markAnswering(id);
		return Foreman.answer(id, option, text).handle((ack, err) -> {
			if (err == null && ack != null && ack.ok()) {
				recordAnswer(id, option, text);
				return null;
			}
			unmarkAnswering(id);
			if (err != null) {
				Throwable c = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
				return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
			}
			return ack == null ? "no answer from the Foreman" : ack.error() != null ? ack.error() : "the Foreman refused the answer";
		});
	}

	public static void recordAnswer(String id, @Nullable String option, @Nullable String text) {
		ANSWERS.put(id, (option == null ? "" : option) + (text == null ? "" : " | " + text));
	}

	/** Open decisions that are not being answered right now (the HUD badge count). */
	public static int waitingCount() {
		int n = 0;
		for (Decision d : DecisionQueue.open()) {
			if (!isAnswering(d.id())) {
				n++;
			}
		}
		return n;
	}

	/** Called by the podium renderer (client thread) with the podium's state: keep {@code open} in sync. */
	static void syncPodium(BlockPos pos, BlockState state, boolean wantOpen) {
		if (!state.hasProperty(DecisionPodiumBlock.OPEN)) {
			return;
		}
		boolean is = state.getValue(DecisionPodiumBlock.OPEN);
		if (is == wantOpen) {
			PODIUM_PENDING.remove(pos);
			return;
		}
		Boolean pending = PODIUM_PENDING.get(pos);
		if (pending != null && pending == wantOpen) {
			return;
		}
		BlockPos p = pos.immutable();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		PODIUM_PENDING.put(p, wantOpen);
		// the podium is in the player's level (a renderer only sees that one): set it in that dimension
		ServerTasks.run(mc.level.dimension(), level -> {
			BlockState s = level.getBlockState(p);
			if (s.getBlock() instanceof DecisionPodiumBlock && s.getValue(DecisionPodiumBlock.OPEN) != wantOpen) {
				level.setBlock(p, s.setValue(DecisionPodiumBlock.OPEN, wantOpen), Block.UPDATE_CLIENTS);
			}
		});
	}

	// ------------------------------------------------------------------ dev

	private static void registerDev() {
		DevBridge.register("dev.decision", 10_000,
			"{decisionId?, kind?: question|permission|merge, preview?: bool (sample permission, nothing sent), podium?: [x,y,z] (as that podium's right-click: "
				+ "the hub Inbox filtered to its decisions; with screen:true the podium-scoped decision screen instead), showAll?: bool} - open the decision "
				+ "screen at that decision (default: the queue head)", (req, mc) -> {
				Fields f = Fields.of(req);
				if (f.has("podium")) {
					com.google.gson.JsonArray a = f.json().getAsJsonArray("podium");
					BlockPos at = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
					boolean all = f.optBool("showAll", false);
					boolean screen = f.optBool("screen", false);
					return DevBridge.onClient(mc, () -> {
						if (mc.level == null) {
							throw new DevBridge.DevException("not in a world");
						}
						if (screen) {
							openPodiumScreen(mc.level, at);
							if (all && mc.gui.screen() instanceof DecisionScreen ds) {
								ds.toggleShowAll();
							}
						} else {
							openPodium(mc.level, at);
							if (all) {
								dev.agentcraft.client.hub.Inbox.open("all");
							}
						}
						JsonObject o = state(mc);
						o.addProperty("inboxFilter", dev.agentcraft.client.hub.Inbox.filter().id());
						o.addProperty("podiumBuilding", dev.agentcraft.client.leads.Leads.buildingAt(mc.level, at.getX(), at.getY(), at.getZ(), 0));
						return o;
					});
				}
				String id = f.has("decisionId") ? f.nonBlank("decisionId") : null;
				String kind = f.has("kind") ? f.nonBlank("kind").toLowerCase(Locale.ROOT) : null;
				boolean preview = f.optBool("preview", false);
				return DevBridge.onClient(mc, () -> {
					if (preview) {
						mc.gui.setScreen(DecisionScreen.preview(dev.agentcraft.client.permissions.PermissionsFeature.sample()));
						return state(mc);
					}
					String target = id;
					if (target == null && kind != null) {
						DecisionKind k = switch (kind) {
							case "question" -> DecisionKind.QUESTION;
							case "permission" -> DecisionKind.PERMISSION;
							case "merge" -> DecisionKind.MERGE;
							default -> throw new DevBridge.DevException("kind must be question, permission or merge");
						};
						Decision d = DecisionQueue.firstOfKind(k);
						if (d == null) {
							throw new DevBridge.DevException("no open " + kind + " decision");
						}
						target = d.id();
					}
					if (target != null && (Foreman.state() == null || Foreman.state().decision(target) == null)) {
						throw new DevBridge.DevException("no decision " + target);
					}
					openQueue(target, null);
					return state(mc);
				});
			});
		DevBridge.register("dev.decisions", 10_000, "{} - the decision queue (HUD order) and the open decision screen's state", (req, mc) -> DevBridge
			.onClient(mc, () -> state(mc)));
	}

	static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		JsonArray q = new JsonArray();
		for (Decision d : DecisionQueue.open()) {
			JsonObject e = new JsonObject();
			e.addProperty("id", d.id());
			e.addProperty("kind", d.kind().wire());
			e.addProperty("agentId", d.agentId());
			e.addProperty("question", d.question());
			JsonArray opts = new JsonArray();
			d.options().forEach(opts::add);
			e.add("options", opts);
			e.addProperty("answering", isAnswering(d.id()));
			e.addProperty("podium", dev.agentcraft.client.leads.Leads.view().podiumFor(d));
			e.addProperty("freeText", d.freeText());
			q.add(e);
		}
		o.add("queue", q);
		o.addProperty("waiting", waitingCount());
		JsonObject answers = new JsonObject();
		ANSWERS.forEach(answers::addProperty);
		o.add("sentAnswers", answers);
		if (mc.gui.screen() instanceof DecisionScreen ds) {
			JsonObject sc = new JsonObject();
			sc.addProperty("current", ds.currentId());
			sc.addProperty("highlight", ds.highlight());
			sc.addProperty("textFocused", ds.textFocused());
			sc.addProperty("requestChanges", ds.requestChangesMode());
			sc.addProperty("answerText", ds.answerText());
			sc.addProperty("status", ds.status());
			sc.addProperty("lastAnswer", ds.lastAnswer());
			sc.addProperty("armed", ds.armed());
			sc.addProperty("preview", ds.isPreview());
			sc.addProperty("scope", ds.scopeLabel());
			sc.addProperty("scoped", ds.scoped());
			sc.addProperty("parent", ds.parent() == null ? null : ds.parent().getClass().getSimpleName());
			sc.add("panel", ds.panel().state());
			o.add("screen", sc);
		} else {
			o.add("screen", null);
		}
		// the podium bubble reserves screen space in the nameplate layout: plates overlapping it (0 when settled)
		JsonObject pod = new JsonObject();
		pod.addProperty("reserved", dev.agentcraft.client.agents.PlateLayout.reservedCount());
		pod.addProperty("plateOverlaps", dev.agentcraft.client.agents.PlateLayout.reservedOverlaps());
		o.add("podium", pod);
		return o;
	}
}
