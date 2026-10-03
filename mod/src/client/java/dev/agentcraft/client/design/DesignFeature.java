package dev.agentcraft.client.design;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.DesignSpec;
import dev.agentcraft.client.building.BuildingWizardFeature;
import dev.agentcraft.client.building.PlotMarker;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanJson;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Design;
import dev.agentcraft.client.foreman.Protocol.DesignStatus;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.hub.HubFeature;
import dev.agentcraft.client.hub.HubScreen;
import dev.agentcraft.client.hub.HubTab;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.client.world.ServerTasks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Generated buildings, mod side (docs/HUB.md "Generated buildings"): the design form
 * ({@link DesignScreen}, the hub's "Design new…" and {@code /hub design}), "Fit a plot…" through
 * {@link PlotMarker}, {@code design.request} / {@code design.cancel}, and what happens when a design
 * finishes: a toast, {@code Blueprints.reload} on the integrated server, the new blueprint selected in
 * the hub's browser, and (when a plot was marked for it) placement locked on that plot.
 *
 * <p>The form's content lives here ({@link #form()}), so going to mark a plot and coming back, or
 * closing and reopening the form, keeps everything typed. Plots are remembered per design and, once it
 * is done, per blueprint (this session). Client thread.
 *
 * <p>QA: {@code dev.design.*}, {@code dev.plot.*} (see {@link #registerDev}); screens {@code design_form},
 * {@code hub_designs}.
 */
public final class DesignFeature {
	private static DesignForm form = new DesignForm();
	/** Where the form returns to (the hub that opened it), or null for the world. */
	private static @Nullable Screen parent;
	private static final Map<String, DesignSpec.Plot> PLOT_BY_DESIGN = new HashMap<>();
	private static final Map<String, DesignSpec.Plot> PLOT_BY_BLUEPRINT = new HashMap<>();
	/** Designs seen queued or running: a snapshot that shows one finished (while offline) still reloads. */
	private static final Set<String> RUNNING = new HashSet<>();
	/** Designs whose done/failed was already handled (no second toast or reload after a reconnect). */
	private static final Set<String> HANDLED = new HashSet<>();
	private static @Nullable String pendingSelect;
	private static boolean sending;
	private static @Nullable String lastSent;
	private static @Nullable String lastReload;

	private DesignFeature() {
	}

	public static void init() {
		HubFeature.designNew = DesignFeature::open;
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onDesign(@Nullable Design previous, Design d) {
				changed(previous == null ? null : previous.status(), d);
			}

			@Override
			public void onSnapshot(ForemanState state) {
				for (Design d : state.designs().values()) {
					if (d.status().isRunning()) {
						RUNNING.add(d.id());
					} else if (RUNNING.contains(d.id())) {
						changed(DesignStatus.DESIGNING, d);
					}
				}
			}
		});
		// the Foreman's own "design ready / failed" notifies are replaced by this feature's toasts (which say what to do next)
		Toasts.addFilter(n -> n.text().startsWith("New building design ready:") || n.text().startsWith("Building design ")
			&& n.text().contains(" failed:"));
		DevBridge.registerScreen("design_form", mc -> new DesignScreen(form, null));
		DesignDev.register();
	}

	// ------------------------------------------------------------------ form

	public static DesignForm form() {
		return form;
	}

	/** Starts over with an empty form (DevBridge {@code reset}). */
	static void resetForm() {
		form = new DesignForm();
	}

	/** Opens the form; {@code back} is where Esc / Cancel go (the hub), null = the world. */
	public static DesignScreen open(@Nullable Screen back) {
		Minecraft mc = Minecraft.getInstance();
		parent = back;
		DesignScreen s = new DesignScreen(form, back);
		mc.gui.setScreen(s);
		return s;
	}

	static @Nullable Screen parent() {
		return parent;
	}

	/** {@code <gameDir>/agentcraft/blueprints}, absolute (what the Foreman writes into and the mod loads from). */
	public static String outDir() {
		return Blueprints.userDir().toAbsolutePath().normalize().toString();
	}

	/** The id of the last design this client requested, or null. */
	public static @Nullable String lastSent() {
		return lastSent;
	}

	static boolean sending() {
		return sending;
	}

	/** The outcome of a submit: the design id, or why not (validation, Foreman offline, refused). */
	public record Sent(@Nullable String designId, @Nullable String error) {
	}

	/**
	 * Validates the form (errors show inline) and sends {@code design.request}; completes on the client
	 * thread. Remembers the plot for the new design when the size came from one.
	 */
	public static CompletableFuture<Sent> submit() {
		DesignForm f = form;
		f.showErrors = true;
		f.sendError = null;
		String out = outDir();
		Map<String, String> errors = f.errors(out);
		if (!errors.isEmpty()) {
			return CompletableFuture.completedFuture(new Sent(null, "Fix the marked fields: " + String.join("; ", errors.values())));
		}
		if (sending) {
			return CompletableFuture.completedFuture(new Sent(null, "Already sending"));
		}
		if (!Foreman.connected()) {
			f.sendError = "The Foreman is not connected: designs are made by it (start it, then try again)";
			return CompletableFuture.completedFuture(new Sent(null, f.sendError));
		}
		try {
			Files.createDirectories(Path.of(out));
		} catch (IOException e) {
			f.sendError = "Could not create " + out + ": " + e.getMessage();
			return CompletableFuture.completedFuture(new Sent(null, f.sendError));
		}
		DesignSpec.Plot plot = DesignForm.PLOT.equals(f.size) ? f.plot : null;
		JsonObject msg = ForemanJson.msg("design.request").put("request", f.requestJson(out)).json();
		sending = true;
		return Foreman.link().send(msg).handle((ack, err) -> {
			sending = false;
			if (err != null) {
				Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				f.sendError = "Not sent: " + c.getMessage();
				return new Sent(null, f.sendError);
			}
			if (!ack.ok()) {
				f.sendError = "The Foreman refused it: " + (ack.error() != null ? ack.error() : "no reason given");
				return new Sent(null, f.sendError);
			}
			String id = designId(ack);
			if (id == null) {
				f.sendError = "The Foreman accepted it but sent no design id";
				return new Sent(null, f.sendError);
			}
			lastSent = id;
			RUNNING.add(id);
			if (plot != null) {
				PLOT_BY_DESIGN.put(id, plot);
			}
			f.showErrors = false;
			AgentCraft.LOGGER.info("Design {} requested ({}{})", id, f.requestJson(out).get("style").getAsString(), plot != null ? ", on a plot" : "");
			return new Sent(id, null);
		});
	}

	private static @Nullable String designId(Ack ack) {
		JsonObject r = ack.result();
		if (r == null) {
			return null;
		}
		if (r.has("designId") && r.get("designId").isJsonPrimitive()) {
			return r.get("designId").getAsString();
		}
		return r.has("id") && r.get("id").isJsonPrimitive() ? r.get("id").getAsString() : null;
	}

	/** {@code design.cancel}; completes on the client thread (check {@code ack.ok()}). */
	public static CompletableFuture<Ack> cancel(String designId) {
		return Foreman.link().send(ForemanJson.msg("design.cancel").put("designId", designId).json());
	}

	/** After a submit: the hub's designs list with the new design selected. */
	static void showInHub(String designId) {
		Minecraft mc = Minecraft.getInstance();
		HubScreen h = parent instanceof HubScreen p ? p : new HubScreen(HubTab.BUILDINGS);
		h.setTab(HubTab.BUILDINGS);
		h.setSub(HubScreen.Sub.DESIGNS);
		h.selectDesign(designId);
		mc.gui.setScreen(h);
	}

	// ------------------------------------------------------------------ plot

	/** "Fit a plot…": closes the form, marks a plot, comes back to the form (with the size filled, unless cancelled). */
	public static void startPlot() {
		DesignSpec.Plot had = form.plot;
		PlotMarker.start(had != null ? had.height() : DesignSpec.DEFAULT_PLOT_HEIGHT, p -> {
			form.setPlot(p);
			reopen();
		}, DesignFeature::reopen);
	}

	private static void reopen() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.gui.setScreen(new DesignScreen(form, parent));
		}
	}

	/** The plot remembered for a finished design's blueprint (this session), or null. */
	public static DesignSpec.@Nullable Plot plotForBlueprint(String blueprintId) {
		return PLOT_BY_BLUEPRINT.get(blueprintId);
	}

	public static DesignSpec.@Nullable Plot plotForDesign(String designId) {
		return PLOT_BY_DESIGN.get(designId);
	}

	/**
	 * "Place on the plot": the repo step, then placement mode locked on the plot (entrance towards the side
	 * it was marked from). Returns why not (no plot, other dimension, blueprint not loaded), or null.
	 */
	public static @Nullable String placeOnPlot(String blueprintId) {
		int[] spot;
		try {
			spot = plotSpot(blueprintId);
		} catch (IllegalStateException e) {
			return e.getMessage();
		}
		BuildingWizardFeature.openForSpot(blueprintId, spot, spot[3]);
		return null;
	}

	/** Where {@code blueprintId} goes on its plot: {ox, oy, oz, turns}. Throws IllegalStateException with the reason it cannot. */
	public static int[] plotSpot(String blueprintId) {
		DesignSpec.Plot p = PLOT_BY_BLUEPRINT.get(blueprintId);
		Blueprint bp = Blueprints.get(blueprintId);
		Minecraft mc = Minecraft.getInstance();
		if (p == null) {
			throw new IllegalStateException("No plot was marked for " + blueprintId);
		}
		if (bp == null) {
			throw new IllegalStateException("Blueprint " + blueprintId + " is not loaded");
		}
		if (mc.player == null) {
			throw new IllegalStateException("Not in a world");
		}
		String here = mc.player.level().dimension().identifier().toString();
		if (!here.equals(p.dimension())) {
			throw new IllegalStateException("The plot is in " + p.dimension() + " and you are in " + here + ": go there to place it");
		}
		return p.placement(bp.front(), bp.sizeX(), bp.sizeZ(), bp.groundY());
	}

	// ------------------------------------------------------------------ progress

	private static void changed(@Nullable DesignStatus before, Design d) {
		if (d.status().isRunning()) {
			RUNNING.add(d.id());
			return;
		}
		boolean wasRunning = before == null ? RUNNING.contains(d.id()) : before.isRunning();
		RUNNING.remove(d.id());
		if (!wasRunning || !HANDLED.add(d.id())) {
			return;
		}
		switch (d.status()) {
			case DONE -> done(d);
			case FAILED -> Toasts.push(new Notify(NotifyLevel.WARN, "Building design " + d.id() + " failed: " + firstLine(d.error() != null
				? d.error() : d.step()) + ". The hub's Designs list has the details.", null, System.currentTimeMillis()));
			default -> {
			}
		}
	}

	private static String firstLine(String s) {
		String l = s.strip();
		int nl = l.indexOf('\n');
		l = nl >= 0 ? l.substring(0, nl) : l;
		return l.length() > 140 ? l.substring(0, 139) + "…" : l;
	}

	/** A design finished: remember its plot by blueprint, reload blueprints, select it in the hub. */
	private static void done(Design d) {
		String bp = d.blueprintId();
		DesignSpec.Plot plot = PLOT_BY_DESIGN.get(d.id());
		if (bp != null && plot != null) {
			PLOT_BY_BLUEPRINT.put(bp, plot);
		}
		String size = d.size() == null ? "" : " (" + d.size().x() + "×" + d.size().y() + "×" + d.size().z() + ")";
		Toasts.push(new Notify(NotifyLevel.INFO, "New building design ready: " + (bp == null ? d.id() : bp) + size + ". "
			+ (plot != null ? "Open the hub to place it on your plot." : "Open the hub to review and place it."), null, System.currentTimeMillis()));
		if (bp == null) {
			return;
		}
		reloadAndSelect(bp);
	}

	/** Reloads blueprints on the integrated server, then selects {@code blueprintId} in the open hub (or the next one opened). */
	public static CompletableFuture<Boolean> reloadAndSelect(String blueprintId) {
		return ServerTasks.callOnServer(server -> {
			Blueprints.reload(server);
			return Blueprints.get(blueprintId) != null;
		}).handle((loaded, err) -> {
			if (err != null) {
				lastReload = blueprintId + ": " + (err.getMessage() == null ? err.toString() : err.getMessage());
				AgentCraft.LOGGER.warn("Design done, but reloading blueprints failed: {}", lastReload);
				return false;
			}
			lastReload = blueprintId + (loaded ? ": loaded" : ": NOT loaded (see the log: Blueprints problems)");
			if (!loaded) {
				Toasts.push(new Notify(NotifyLevel.WARN, "The new blueprint " + blueprintId + " did not load: " + String.join("; ",
					Blueprints.lastProblems()), null, System.currentTimeMillis()));
				return false;
			}
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() instanceof HubScreen h) {
				h.selectBlueprint(blueprintId);
				h.setView("iso");
			} else {
				pendingSelect = blueprintId;
			}
			return true;
		});
	}

	/** The blueprint a finished design asked the hub to select when it next opens (consumed). */
	public static @Nullable String takePendingSelect() {
		String s = pendingSelect;
		pendingSelect = null;
		return s;
	}

	// ------------------------------------------------------------------ DevBridge state

	static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		DesignScreen s = mc.gui.screen() instanceof DesignScreen d ? d : null;
		JsonObject fj = form.stateJson(outDir());
		fj.addProperty("open", s != null);
		fj.addProperty("focus", s == null ? null : s.focus());
		fj.addProperty("busy", sending);
		if (s != null) {
			fj.add("buttons", s.buttonsJson());
			fj.add("layout", s.layoutJson());
		}
		o.add("form", fj);
		o.addProperty("foremanConnected", Foreman.connected());
		o.addProperty("lastSent", lastSent);
		o.addProperty("lastReload", lastReload);
		o.addProperty("pendingSelect", pendingSelect);
		JsonArray ds = new JsonArray();
		ForemanState st = Foreman.state();
		if (st != null) {
			for (Design d : st.designs().values()) {
				ds.add(designJson(d));
			}
		}
		o.add("designs", ds);
		JsonObject plots = new JsonObject();
		PLOT_BY_BLUEPRINT.forEach((k, v) -> plots.add(k, PlotMarker.plotJson(v)));
		o.add("plotsByBlueprint", plots);
		JsonObject byDesign = new JsonObject();
		PLOT_BY_DESIGN.forEach((k, v) -> byDesign.add(k, PlotMarker.plotJson(v)));
		o.add("plotsByDesign", byDesign);
		o.add("plotMode", PlotMarker.state());
		return o;
	}

	public static JsonObject designJson(Design d) {
		JsonObject j = new JsonObject();
		j.addProperty("id", d.id());
		j.addProperty("status", d.status().wire());
		j.addProperty("step", d.step());
		j.addProperty("blueprintId", d.blueprintId());
		j.addProperty("loaded", d.blueprintId() != null && Blueprints.get(d.blueprintId()) != null);
		if (d.size() != null) {
			j.addProperty("size", d.size().x() + "x" + d.size().y() + "x" + d.size().z());
		}
		j.addProperty("previews", d.previews().size());
		j.addProperty("error", d.error());
		j.addProperty("kind", d.request().kind());
		j.addProperty("wings", d.request().wings());
		j.addProperty("style", d.request().style());
		j.addProperty("maxSize", d.request().maxSize().x() + "x" + d.request().maxSize().y() + "x" + d.request().maxSize().z());
		j.addProperty("name", d.request().name());
		j.addProperty("hasPlot", PLOT_BY_DESIGN.containsKey(d.id()));
		return j;
	}
}
