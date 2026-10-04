package dev.agentcraft.client.trophy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.Trophies;
import dev.agentcraft.building.Trophy;
import dev.agentcraft.building.TrophyText;
import dev.agentcraft.client.agents.OutdoorRoutes;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.world.ServerTasks;
import dev.agentcraft.trophy.TrophyEvents;
import dev.agentcraft.trophy.TrophyEvents.Award;
import dev.agentcraft.trophy.TrophySettings;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Trophies, client side (docs/BUILDINGS.md "Trophies"): watches the Foreman's goal and task updates ({@link TrophyEvents}
 * decides what earns a plaque) and has the integrated server hang them ({@link Trophies#award}, on the building's own
 * dimension). A snapshot, a placed building and a changed repo list run a catch-up: everything done or merged with no key
 * yet, oldest first. Singleplayer only; nothing at all while this world's toggle ({@code trophies.json}, the hub's
 * Buildings tab) is off. Client thread unless noted.
 */
public final class TrophyFeature {
	private static @Nullable TrophySettings settings;

	private TrophyFeature() {
	}

	public static void init() {
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onSnapshot(ForemanState state) {
				catchUp();
			}

			@Override
			public void onGoal(Protocol.@Nullable Goal previous, Protocol.Goal goal) {
				if (enabled()) {
					ForemanState st = Foreman.state();
					Optional<Award> a = TrophyEvents.goal(previous == null ? null : goalIn(previous, st), goalIn(goal, st), zone());
					a.ifPresent(x -> submit(List.of(x)));
				}
			}

			@Override
			public void onTask(Protocol.@Nullable Task previous, Protocol.Task task) {
				if (enabled()) {
					Optional<Award> a = TrophyEvents.task(previous == null ? null : taskIn(previous), taskIn(task), goalRepos(Foreman.state()), zone());
					a.ifPresent(x -> submit(List.of(x)));
				}
			}
		});
		// a building placed (or its repos changed) can now hold trophies the Foreman already earned; listeners run on the thread that changed it
		Buildings.addListener(list -> Minecraft.getInstance().execute(TrophyFeature::catchUp));
		registerDev();
	}

	// ------------------------------------------------------------------ setting

	static Path settingsFile() {
		return FabricLoader.getInstance().getGameDir().resolve("agentcraft").resolve(TrophySettings.FILE);
	}

	private static TrophySettings settings() {
		if (settings == null) {
			settings = TrophySettings.load(settingsFile());
		}
		return settings;
	}

	/** "Trophies for merges and finished goals" for this world (default on). */
	public static boolean enabled() {
		return settings().enabled(OutdoorRoutes.world());
	}

	/** Sets the toggle for this world and saves trophies.json now; turning it on hangs what was missed while off. */
	public static void setEnabled(boolean on) {
		TrophySettings s = settings();
		String world = OutdoorRoutes.world();
		if (s.set(world, on) || s.dirty()) {
			try {
				s.save(settingsFile());
			} catch (IOException e) {
				AgentCraft.LOGGER.warn("Could not save {}", settingsFile(), e);
			}
		}
		AgentCraft.LOGGER.info("Trophies: {} (world {})", on ? "on" : "off", world);
		if (on) {
			catchUp();
		}
	}

	// ------------------------------------------------------------------ mapping + routing

	private static ZoneId zone() {
		return ZoneId.systemDefault();
	}

	private static Map<String, String> goalRepos(@Nullable ForemanState st) {
		Map<String, String> m = new HashMap<>();
		if (st != null) {
			st.goals().forEach((id, g) -> {
				if (g.repoId() != null) {
					m.put(id, g.repoId());
				}
			});
		}
		return m;
	}

	static TrophyEvents.GoalIn goalIn(Protocol.Goal g, @Nullable ForemanState st) {
		int n = 0;
		if (st != null) {
			for (Protocol.Task t : st.tasks().values()) {
				if (g.id().equals(t.goalId()) && t.status() != Protocol.TaskStatus.CANCELLED) {
					n++;
				}
			}
		}
		return new TrophyEvents.GoalIn(g.id(), g.text(), g.status() == Protocol.GoalStatus.DONE, g.repoId(), g.createdAt(), g.updatedAt(), n);
	}

	static TrophyEvents.TaskIn taskIn(Protocol.Task t) {
		Protocol.TaskPr pr = t.pr();
		return new TrophyEvents.TaskIn(t.id(), t.title(), t.status() == Protocol.TaskStatus.DONE, t.repoId(), t.goalId(),
			pr == null ? null : Integer.toString(pr.id()), pr != null && "merged".equals(pr.status()), pr == null ? 0 : pr.updatedAt(), t.createdAt(), t.updatedAt());
	}

	/** Everything the full state has earned, oldest first. */
	public static void catchUp() {
		ForemanState st = Foreman.state();
		if (st == null || !enabled() || !st.hasData()) {
			return;
		}
		List<TrophyEvents.GoalIn> goals = new ArrayList<>();
		st.goals().values().forEach(g -> goals.add(goalIn(g, st)));
		List<TrophyEvents.TaskIn> tasks = new ArrayList<>();
		st.tasks().values().forEach(t -> tasks.add(taskIn(t)));
		submit(TrophyEvents.newestPerRepo(TrophyEvents.catchUp(goals, tasks, goalRepos(st), zone()), repo -> Trophies.slotsFor(repo).size()));
	}

	/**
	 * Hands awards to the integrated server, one batch per building dimension (their order kept). Repos without a
	 * building are dropped here; a later building catches up.
	 */
	private static void submit(List<Award> awards) {
		Map<String, List<Award>> byDimension = new LinkedHashMap<>();
		for (Award a : awards) {
			Building b = Buildings.forRepo(a.trophy().repo());
			if (b != null) {
				byDimension.computeIfAbsent(b.dimensionOrDefault(), d -> new ArrayList<>()).add(a);
			}
		}
		byDimension.forEach((dimension, batch) -> ServerTasks.run(dimension, level -> {
			for (Award a : batch) {
				Trophies.Result r = Trophies.award(level, a.trophy(), a.key());
				if (r.outcome() != Trophies.Outcome.KNOWN && r.outcome() != Trophies.Outcome.NO_BUILDING) {
					AgentCraft.LOGGER.debug("Trophy {}: {} ({})", a.key(), r.outcome(), r.message());
				}
			}
		}));
	}

	// ------------------------------------------------------------------ DevBridge

	private static void registerDev() {
		DevBridge.register("dev.trophies.award", 15_000, "{repo, kind: pr|merge|goal, title, pr?: number, tasks?: number, force?: false} - QA: hang a trophy for "
			+ "that repo's building now, with a fresh key (no dedupe; slots, replacing the oldest, and the world's toggle still apply unless force) "
			+ "-> {outcome PLACED|NO_BUILDING|NO_SLOTS|NO_ROOM|UNAVAILABLE, building, slot, replaced, lines[], message, enabled}", (req, mc) -> {
				Fields f = Fields.of(req);
				String repo = f.nonBlank("repo");
				String kind = f.nonBlank("kind").toLowerCase(Locale.ROOT);
				String title = f.optStr("title", "");
				int pr = f.optInt("pr", 1, 0, 1_000_000);
				int tasks = f.optInt("tasks", 1, 0, 1000);
				boolean force = f.optBool("force", false);
				LocalDate today = LocalDate.now(zone());
				Trophy t = switch (kind) {
					case "pr" -> Trophy.pr(repo, Integer.toString(pr), title, today);
					case "merge" -> Trophy.merge(repo, "t" + pr, title, today);
					case "goal" -> Trophy.goal(repo, title, tasks, today);
					default -> throw new DevBridge.DevException("kind must be pr, merge or goal");
				};
				return DevBridge.onClient(mc, () -> {
					JsonObject o = new JsonObject();
					boolean on = enabled();
					o.addProperty("enabled", on);
					JsonArray lines = new JsonArray();
					TrophyText.lines(t).forEach(lines::add);
					o.add("lines", lines);
					if (!on && !force) {
						o.addProperty("outcome", "DISABLED");
						o.addProperty("message", "Trophies are off for this world (dev.trophies.award force:true overrides)");
					}
					return o;
				}).thenCompose(o -> {
					if (o.has("outcome")) {
						return java.util.concurrent.CompletableFuture.completedFuture(o);
					}
					String key = "dev:" + kind + ":" + System.nanoTime();
					return ServerTasks.callOnServer(server -> Trophies.award(server, t, key)).thenApply(r -> {
						o.addProperty("outcome", r.outcome().name());
						o.addProperty("building", r.building());
						o.addProperty("slot", r.slot());
						o.addProperty("replaced", r.replaced());
						o.addProperty("message", r.message());
						return o;
					});
				});
			});
		DevBridge.register("dev.trophies.toggle", 10_000, "{on?: bool} - set (or flip) \"Trophies for merges and finished goals\" for this world -> {enabled, world}",
			(req, mc) -> {
				Boolean on = Fields.of(req).optBool("on");
				return DevBridge.onClient(mc, () -> {
					setEnabled(on != null ? on : !enabled());
					JsonObject o = new JsonObject();
					o.addProperty("enabled", enabled());
					o.addProperty("world", OutdoorRoutes.world());
					return o;
				});
			});
		DevBridge.register("dev.trophies.list", 15_000, "{} -> {enabled, world, loaded, awarded[keys], buildings[{id, blueprint, repos, slots[{slot, wing, k, x, y, z, key?, lines?, at?}]}]} "
			+ "- the trophy ledger of this world", (req, mc) ->
				ServerTasks.callOnServer(Trophies::list).thenApply(o -> {
					o.addProperty("enabled", enabled());
					o.addProperty("world", OutdoorRoutes.world());
					return o;
				}));
	}
}
