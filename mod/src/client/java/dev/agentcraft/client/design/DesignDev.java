package dev.agentcraft.client.design;

import com.google.gson.JsonObject;
import dev.agentcraft.client.building.PlotMarker;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.hub.HubScreen;
import net.minecraft.client.Minecraft;

/**
 * DevBridge commands of the design form and plot marking (mod/DEV.md "Generated buildings"). Every
 * reply carries {@code dev.design.state} (or {@code dev.plot.state}) so a driver sees the result.
 */
final class DesignDev {
	private DesignDev() {
	}

	private static final String FIELDS = "fields?: {kind?: single|group, wings?, style?, materials?, features?: [..] | \"a,b\", size?: S|M|L|plot, "
		+ "maxSize?: {x,y,z} (explicit limit), remix?, name?, notes?}";

	static void register() {
		DevBridge.register("dev.design.open", 10_000, "{" + FIELDS + ", reset?: bool, parent?: hub|none (default hub)} - open the design form "
			+ "(the hub's Design new…), set fields; replies with dev.design.state", (req, mc) -> {
				Fields f = Fields.of(req);
				boolean reset = f.optBool("reset", false);
				String parent = f.optStr("parent", "hub");
				return DevBridge.onClient(mc, () -> {
					requireWorld(mc);
					PlotMarker.cancel();
					if (reset) {
						DesignFeature.resetForm();
					}
					if (f.has("fields")) {
						DesignFeature.form().apply(f.obj("fields"));
					}
					DesignFeature.open(parent.equals("none") ? null : mc.gui.screen() instanceof HubScreen h ? h : new HubScreen(
						dev.agentcraft.client.hub.HubTab.BUILDINGS));
					return DesignFeature.state(mc);
				});
			});
		DevBridge.register("dev.design.submit", 30_000, "{" + FIELDS + "} - set fields and press \"Design it\": replies after the Foreman's ack "
			+ "with {sent: {designId, error}} + dev.design.state (errors shown inline when invalid; on success the hub's Designs list opens)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				return DevBridge.onClient(mc, () -> {
					requireWorld(mc);
					if (f.has("fields")) {
						DesignFeature.form().apply(f.obj("fields"));
					}
					DesignScreen s = mc.gui.screen() instanceof DesignScreen d ? d : DesignFeature.open(null);
					return s.submit();
				}).thenCompose(x -> x).thenCompose(sent -> DevBridge.onClient(mc, () -> {
					JsonObject o = DesignFeature.state(mc);
					JsonObject r = new JsonObject();
					r.addProperty("designId", sent.designId());
					r.addProperty("error", sent.error());
					o.add("sent", r);
					return o;
				}));
			});
		DevBridge.register("dev.design.state", 10_000, "{} - the form (open, fields, maxSize, plot, errors, request as sent), the Foreman's "
			+ "designs, remembered plots, plot mode", (req, mc) -> DevBridge.onClient(mc, () -> DesignFeature.state(mc)));
		DevBridge.register("dev.design.cancel", 20_000, "{designId} - design.cancel (the hub's Cancel); replies after the ack", (req, mc) -> {
			String id = Fields.of(req).nonBlank("designId");
			return DevBridge.onClient(mc, () -> {
				if (!Foreman.connected()) {
					throw new DevBridge.DevException("the Foreman is not connected");
				}
				return DesignFeature.cancel(id);
			}).thenCompose(x -> x).thenCompose(ack -> DevBridge.onClient(mc, () -> {
				JsonObject o = DesignFeature.state(mc);
				o.addProperty("ok", ack.ok());
				o.addProperty("ackError", ack.error());
				return o;
			}));
		});
		DevBridge.register("dev.design.place", 10_000, "{blueprint, repos?: [..] | \"a,b\"} - \"Place on the plot\": with repos straight into "
			+ "placement mode locked on the remembered plot, else the repo step; replies with dev.build.state-like {origin, turns}", (req, mc) -> {
				Fields f = Fields.of(req);
				String bp = f.nonBlank("blueprint");
				java.util.List<String> repos = f.has("repos") ? (f.json().get("repos").isJsonArray()
					? f.json().get("repos").getAsJsonArray().asList().stream().map(e -> e.getAsString().strip()).toList()
					: dev.agentcraft.building.BlueprintTransform.parseRepos(f.str("repos"))) : java.util.List.of();
				return DevBridge.onClient(mc, () -> {
					requireWorld(mc);
					JsonObject o = new JsonObject();
					if (repos.isEmpty()) {
						String why = DesignFeature.placeOnPlot(bp);
						if (why != null) {
							throw new DevBridge.DevException(why);
						}
						o.addProperty("step", "repos");
						return o;
					}
					int[] spot;
					try {
						spot = DesignFeature.plotSpot(bp);
					} catch (IllegalStateException e) {
						throw new DevBridge.DevException(e.getMessage());
					}
					String why = dev.agentcraft.client.building.BuildingWizardFeature.placeNowAt(bp, repos, spot, spot[3]);
					if (why != null) {
						throw new DevBridge.DevException(why);
					}
					o.addProperty("step", "placement");
					o.add("placement", dev.agentcraft.client.building.BuildingWizardFeature.placementState());
					return o;
				});
			});

		DevBridge.register("dev.plot.start", 10_000, "{height?: 6..48} - \"Fit a plot…\": close the form and enter plot-marking mode (Esc "
			+ "returns to the form)", (req, mc) -> {
				Fields f = Fields.of(req);
				Integer h = f.has("height") ? f.optInt("height", 16, 6, 48) : null;
				return DevBridge.onClient(mc, () -> {
					requireWorld(mc);
					try {
						DesignFeature.startPlot();
					} catch (IllegalArgumentException e) {
						throw new DevBridge.DevException(e.getMessage());
					}
					if (h != null) {
						PlotMarker.setHeight(h);
					}
					return PlotMarker.state();
				});
			});
		DevBridge.register("dev.plot.corner", 10_000, "{x, y, z, front?: north|east|south|west, confirm?: bool (default true), height?} - a "
			+ "corner (y = the surface: feet level on the ground); confirm = look + Enter (the second one returns to the form with maxSize "
			+ "filled); confirm:false pins it as the looked-at corner (a shootable preview). front: the entrance side (default: towards "
			+ "the player)", (req, mc) -> {
				Fields f = Fields.of(req);
				int x = (int) f.integer("x", -30_000_000, 30_000_000);
				int y = (int) f.integer("y", -2048, 2048);
				int z = (int) f.integer("z", -30_000_000, 30_000_000);
				String front = f.optStr("front", null);
				boolean confirm = f.optBool("confirm", true);
				Integer h = f.has("height") ? f.optInt("height", 16, 6, 48) : null;
				return DevBridge.onClient(mc, () -> {
					if (!PlotMarker.active()) {
						throw new DevBridge.DevException("not marking a plot (dev.plot.start first)");
					}
					if (h != null) {
						PlotMarker.setHeight(h);
					}
					try {
						PlotMarker.corner(x, y, z, front, confirm);
					} catch (IllegalArgumentException | IllegalStateException e) {
						throw new DevBridge.DevException(e.getMessage());
					}
					JsonObject o = PlotMarker.state();
					o.addProperty("formOpen", mc.gui.screen() instanceof DesignScreen);
					o.add("form", DesignFeature.form().stateJson(DesignFeature.outDir()));
					return o;
				});
			});
		DevBridge.register("dev.plot.state", 10_000, "{} - plot mode: phase, corners, hover, height, front, the plot and its maxSize; the last "
			+ "plot marked", (req, mc) -> DevBridge.onClient(mc, PlotMarker::state));
		DevBridge.register("dev.plot.cancel", 10_000, "{} - Esc: leave plot mode, back to the form", (req, mc) -> DevBridge.onClient(mc, () -> {
			PlotMarker.cancel();
			JsonObject o = PlotMarker.state();
			o.addProperty("formOpen", mc.gui.screen() instanceof DesignScreen);
			return o;
		}));
	}

	private static void requireWorld(Minecraft mc) {
		if (mc.player == null) {
			throw new DevBridge.DevException("not in a world");
		}
	}
}
