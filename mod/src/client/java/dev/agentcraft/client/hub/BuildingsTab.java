package dev.agentcraft.client.hub;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.LeadRouting;
import dev.agentcraft.client.building.BlueprintPreview;
import dev.agentcraft.client.building.BuildingWizardFeature;
import dev.agentcraft.client.design.DesignFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.diff.ReviewKit;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Design;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hub.HubScreen.RowDrawer;
import dev.agentcraft.client.hub.HubScreen.Sub;
import dev.agentcraft.client.hud.Keys;
import dev.larattalabs.labui.client.hud.UiBits;
import dev.agentcraft.client.leads.Leads;
import dev.agentcraft.client.leads.LeadsFeature;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The hub's Buildings tab (docs/HUB.md "Hub screen"), extracted from {@link HubScreen} like the other tabs: a switch
 * of five lists, <b>Buildings</b> (the world's buildings with make home, teleport, a two-step remove, edit repos,
 * move and undo move; the walking, trophies and routines toggles under the list), <b>Fixtures</b> (village boards),
 * <b>Blueprints</b> (the browser with the top-down plan, rendered PNG previews when present, Place, Place on the plot,
 * Design new), <b>Designs</b> (building designs with their progress and Cancel) and <b>Roads</b> ({@link RoadsView}).
 * The hub keeps the frame, the tab strip, the buttons ({@link HubScreen#button}) and its public API (thin delegates
 * here, used by the DevBridge); selections are kept by id, so a list changing under the screen never makes a button act
 * on another building. Everything acts through {@link HubActions} / the wizard, never chat commands. Client thread.
 */
final class BuildingsTab implements HubPane {
	private static final int ROW = 22;
	private final HubScreen hub;
	private Sub sub = Sub.BUILDINGS;
	private @Nullable String selectedBuilding;
	/** The selected fixture (Fixtures list), kept apart from the building selection. */
	private @Nullable String selectedFixture;
	private @Nullable String selectedBlueprint;
	private @Nullable String selectedDesign;
	/** The outcome of the last Cancel / Place on the plot pressed here (shown under the buttons). */
	private @Nullable String designNote;
	private boolean designNoteError;
	/** Remove armed for this building id until {@link #armedAt} + {@link HubScreen#CONFIRM_MS}. */
	private @Nullable String armedRemove;
	private long armedAt;
	/** A removal of this building was refused over the player's things: the armed confirm now forces it ("Remove anyway"). */
	private @Nullable String forceRemove;
	private boolean busy;
	/** Blueprint browser view: "plan" (built-in) or a {@link PreviewImages#KINDS} kind; null = best available. */
	private @Nullable String view;
	private int listScroll;
	private int descScroll;
	private @Nullable String descFor;

	// per-frame layout (hit testing)
	private final List<int[]> rowRects = new ArrayList<>();
	private final List<String> rowIds = new ArrayList<>();
	private final List<int[]> viewRects = new ArrayList<>();
	private final List<String> viewIds = new ArrayList<>();
	private int listX;
	private int listY;
	private int listW;
	private int listH;
	private int descX;
	private int descY;
	private int descW;
	private int descRows;
	private int descTotal;

	BuildingsTab(HubScreen hub) {
		this.hub = hub;
	}

	/** Every frame, before anything is drawn (whichever tab shows): drop last frame's hit areas, expire an armed remove. */
	void beginFrame() {
		rowRects.clear();
		rowIds.clear();
		viewRects.clear();
		viewIds.clear();
		descRows = 0;
		listW = 0;
		if (armedRemove != null && !armed()) {
			armedRemove = null;
		}
	}

	/** The hub switched tabs: never carry an armed remove or a list scroll over. */
	void tabChanged() {
		disarm();
		listScroll = 0;
	}

	// ------------------------------------------------------------------ HubPane

	@Override
	public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		drawBuildingsTab(g, x, y, w, h, mx, my);
	}

	/** Up/down: the selection in the list on show; left/right: the list. Everything else is the hub's (Esc, Tab). */
	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (e.isEscape() || Keys.matches(Keys.hub, e) || k == InputConstants.KEY_TAB) {
			return false; // the hub's own keys come first, as before this tab was a pane
		}
		if (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) {
			int d = k == InputConstants.KEY_UP ? -1 : 1;
			if (sub == Sub.DESIGNS) {
				List<Design> list = designs();
				int i = 0;
				for (int j = 0; j < list.size(); j++) {
					if (list.get(j).id().equals(selectedDesign)) {
						i = j;
					}
				}
				if (!list.isEmpty()) {
					selectDesign(list.get(Math.max(0, Math.min(list.size() - 1, i + d))).id());
				}
			} else if (sub == Sub.BUILDINGS) {
				List<Building> list = buildings();
				int i = indexOfBuilding(list, selectedBuilding);
				if (!list.isEmpty()) {
					selectBuilding(list.get(Math.max(0, Math.min(list.size() - 1, i + d))).id());
				}
			} else if (sub == Sub.FIXTURES) {
				List<Building> list = fixtures();
				int i = indexOfBuilding(list, selectedFixture);
				if (!list.isEmpty()) {
					selectBuilding(list.get(Math.max(0, Math.min(list.size() - 1, i + d))).id());
				}
			} else if (sub == Sub.ROADS) {
				hub.roadsView.move(d);
			} else {
				List<Blueprint> list = blueprints();
				int i = 0;
				for (int j = 0; j < list.size(); j++) {
					if (list.get(j).id().equals(selectedBlueprint)) {
						i = j;
					}
				}
				if (!list.isEmpty()) {
					selectBlueprint(list.get(Math.max(0, Math.min(list.size() - 1, i + d))).id());
					view = null;
				}
			}
			return true;
		}
		if (k == InputConstants.KEY_LEFT || k == InputConstants.KEY_RIGHT) {
			Sub[] all = Sub.values();
			setSub(all[Math.max(0, Math.min(all.length - 1, sub.ordinal() + (k == InputConstants.KEY_LEFT ? -1 : 1)))]);
			return true;
		}
		return false;
	}

	private static int indexOfBuilding(List<Building> list, @Nullable String id) {
		for (int j = 0; j < list.size(); j++) {
			if (list.get(j).id().equals(id)) {
				return j;
			}
		}
		return 0;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return false;
	}

	/** A preview-kind chip or a list row (buttons are the hub's, hit before this). */
	@Override
	public boolean mouseClicked(double x, double y, boolean doubleClick) {
		for (int i = 0; i < viewRects.size(); i++) {
			int[] r = viewRects.get(i);
			if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) {
				view = viewIds.get(i);
				return true;
			}
		}
		for (int i = 0; i < rowRects.size(); i++) {
			int[] r = rowRects.get(i);
			if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) {
				String id = rowIds.get(i);
				if (sub == Sub.DESIGNS) {
					selectDesign(id);
				} else if (sub == Sub.BUILDINGS || sub == Sub.FIXTURES) {
					selectBuilding(id);
				} else if (sub == Sub.ROADS) {
					hub.roadsView.select(id);
				} else {
					if (!id.equals(selectedBlueprint)) {
						view = null;
					}
					selectBlueprint(id);
					if (doubleClick) {
						placeBlueprint(id);
					}
				}
				return true;
			}
		}
		return false;
	}

	/** The blueprint description, else the list. */
	@Override
	public boolean mouseScrolled(double x, double y, int d) {
		if (descRows > 0 && x >= descX && x < descX + descW && y >= descY && y < descY + descRows * 10) {
			descScroll = Math.max(0, Math.min(Math.max(0, descTotal - descRows), descScroll + d));
			return true;
		}
		if (x >= listX && x < listX + listW && y >= listY && y < listY + listH) {
			listScroll = Math.max(0, listScroll + d);
			return true;
		}
		return false;
	}

	@Override
	public @Nullable String focus() {
		return null;
	}

	@Override
	public void unfocus() {
	}

	@Override
	public void shown(boolean on) {
	}

	@Override
	public String[] hints() {
		return new String[] {"Tab", "next tab", "←→", "switch list", "↑↓", "select", "Esc", "close"};
	}

	/** DevBridge: {@code dev.hub.state} reports this tab through the hub's own fields (sub, selections, armed remove, ...). */
	@Override
	public JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("sub", sub.name().toLowerCase(Locale.ROOT));
		o.addProperty("descriptionRows", descRows);
		o.addProperty("descriptionLines", descTotal);
		return o;
	}

	int descriptionRows() {
		return descRows;
	}

	int descriptionLines() {
		return descTotal;
	}

	// ------------------------------------------------------------------ state and actions (moved from HubScreen)

	public Sub sub() {
		return sub;
	}

	public void setSub(Sub s) {
		if (s != sub) {
			sub = s;
			disarm();
			listScroll = 0;
		}
	}

	public @Nullable String selectedBuilding() {
		return selectedBuilding;
	}

	public @Nullable String selectedFixture() {
		return selectedFixture;
	}

	/** The selection the list on show acts on: the fixture on the Fixtures list, else the building. */
	public @Nullable String selectedSite() {
		return sub == Sub.FIXTURES ? selectedFixture : selectedBuilding;
	}

	public @Nullable String selectedBlueprint() {
		return selectedBlueprint;
	}

	public @Nullable String selectedDesign() {
		return selectedDesign;
	}

	/** Selects a design by id (kept even when the Foreman has not reported it yet); switches to the designs list. */
	public void selectDesign(String id) {
		hub.setTab(HubTab.BUILDINGS);
		setSub(Sub.DESIGNS);
		if (!id.equals(selectedDesign)) {
			designNote = null;
		}
		selectedDesign = id;
	}

	/** The Foreman's designs, newest first. */
	static List<Design> designs() {
		ForemanState s = Foreman.state();
		List<Design> out = s == null ? new ArrayList<>() : new ArrayList<>(s.designs().values());
		java.util.Collections.reverse(out);
		return out;
	}

	@Nullable String designNote() {
		return designNote;
	}

	public @Nullable String armedRemove() {
		return armed() ? armedRemove : null;
	}

	public boolean busy() {
		return busy;
	}

	public @Nullable String view() {
		return view;
	}

	/** Selects a building (or a fixture) by id (false when there is none); switches to its list. */
	public boolean selectBuilding(String id) {
		Building b = Buildings.get(id);
		if (b == null) {
			return false;
		}
		hub.setTab(HubTab.BUILDINGS);
		setSub(b.isFixture() ? Sub.FIXTURES : Sub.BUILDINGS);
		if (!id.equals(b.isFixture() ? selectedFixture : selectedBuilding)) {
			disarm();
		}
		if (b.isFixture()) {
			selectedFixture = id;
		} else {
			selectedBuilding = id;
		}
		return true;
	}

	/** Selects a blueprint by id (false when it is not loaded); switches to the blueprint browser. */
	public boolean selectBlueprint(String id) {
		if (Blueprints.get(id) == null) {
			return false;
		}
		hub.setTab(HubTab.BUILDINGS);
		setSub(Sub.BLUEPRINTS);
		selectedBlueprint = id;
		return true;
	}

	/** Shows a preview kind ("plan", "iso", "top", "front", "cutaway"); false when the selected blueprint has none. */
	public boolean setView(String kind) {
		if (selectedBlueprint == null) {
			return false;
		}
		if (kind.equals("plan") || PreviewImages.find(selectedBlueprint).stream().anyMatch(f -> f.kind().equals(kind))) {
			view = kind;
			return true;
		}
		return false;
	}

	private boolean armed() {
		return armedRemove != null && System.currentTimeMillis() - armedAt < HubScreen.CONFIRM_MS;
	}

	private void disarm() {
		armedRemove = null;
	}

	List<Building> buildings() {
		return Buildings.buildings();
	}

	static List<Building> fixtures() {
		return Buildings.fixtures();
	}

	static List<Blueprint> blueprints() {
		return List.copyOf(Blueprints.all());
	}

	private @Nullable Building currentBuilding(List<Building> list) {
		if (list.isEmpty()) {
			selectedBuilding = null;
			return null;
		}
		for (Building b : list) {
			if (b.id().equals(selectedBuilding)) {
				return b;
			}
		}
		// the selected one is gone (removed): select the first, and never carry an armed remove over
		disarm();
		selectedBuilding = list.get(0).id();
		return list.get(0);
	}

	private @Nullable Building currentFixture(List<Building> list) {
		if (list.isEmpty()) {
			selectedFixture = null;
			return null;
		}
		for (Building b : list) {
			if (b.id().equals(selectedFixture)) {
				return b;
			}
		}
		disarm();
		selectedFixture = list.get(0).id();
		return list.get(0);
	}

	private @Nullable Blueprint currentBlueprint(List<Blueprint> list) {
		if (list.isEmpty()) {
			selectedBlueprint = null;
			return null;
		}
		for (Blueprint b : list) {
			if (b.id().equals(selectedBlueprint)) {
				return b;
			}
		}
		selectedBlueprint = list.get(0).id();
		view = null;
		return list.get(0);
	}

	// ------------------------------------------------------------------ actions (buttons and DevBridge)

	/** Make home: the selected building (or {@code id}). */
	public java.util.concurrent.CompletableFuture<HubActions.Result> makeHome(String id) {
		return track(HubActions.makeHome(id));
	}

	public java.util.concurrent.CompletableFuture<HubActions.Result> teleport(String id) {
		return track(HubActions.teleport(id)).thenApply(r -> {
			if (r.ok() && hub.mc() != null && hub.mc().gui.screen() == hub) {
				hub.onClose(); // you are there now: show the world
			}
			return r;
		});
	}

	/**
	 * Remove, step one: arms the confirm for {@code id} (returns null). Step two (the same id again within
	 * {@link #HubScreen.CONFIRM_MS}): removes it and returns the future.
	 */
	public java.util.concurrent.@Nullable CompletableFuture<HubActions.Result> removeClick(String id) {
		if (armed() && id.equals(armedRemove)) {
			boolean force = id.equals(forceRemove);
			disarm();
			forceRemove = null;
			return track(HubActions.remove(id, force)).thenApply(r -> {
				if (!r.ok() && !force && r.message().startsWith("Move these")) {
					// a third, explicit confirm takes it down anyway (what was listed is lost)
					forceRemove = id;
					armedRemove = id;
					armedAt = System.currentTimeMillis();
				}
				return r;
			});
		}
		forceRemove = null;
		armedRemove = id;
		armedAt = System.currentTimeMillis();
		return null;
	}

	/** Whether the armed remove of {@code id} would force it (after a "move these first" refusal). */
	public boolean forceArmed(String id) {
		return armed() && id.equals(armedRemove) && id.equals(forceRemove);
	}

	/** Edit repos: the wizard's repo step for this building (wing n = the n-th picked); confirming sends them. */
	public void editRepos(String id) {
		BuildingWizardFeature.openEditRepos(id, hub, repos -> track(HubActions.setRepos(id, repos)));
	}

	/** Move: placement mode for this building (the ghost of its blueprint); confirming moves it there. */
	public @Nullable String move(String id) {
		return BuildingWizardFeature.startMove(id);
	}

	/** Undo move: back to where it stood before its last move. */
	public java.util.concurrent.CompletableFuture<HubActions.Result> undoMove(String id) {
		return track(HubActions.undoMove(id, false));
	}

	private java.util.concurrent.CompletableFuture<HubActions.Result> track(java.util.concurrent.CompletableFuture<HubActions.Result> f) {
		busy = true;
		return f.whenComplete((r, t) -> busy = false);
	}

	/** Place new: the wizard's repo step (then its blueprint step). */
	public void placeNew() {
		BuildingWizardFeature.open();
	}

	/**
	 * Place a blueprint: the wizard's repo step with this blueprint fixed, then straight to placement. A fixture
	 * blueprint (no repos) goes straight to placement.
	 */
	public void placeBlueprint(String id) {
		Blueprint bp = Blueprints.get(id);
		if (bp != null && bp.isFixture()) {
			placeFixture(id);
			return;
		}
		BuildingWizardFeature.openFor(id);
	}

	/** The outcome of the last "Place village board…" that could not start (shown under the Fixtures list). */
	private @Nullable String fixtureNote;

	/** Place a fixture (docs/VILLAGE.md V2): its ghost is up at once (no repo step); null when it is, else why not. */
	public @Nullable String placeFixture(String blueprintId) {
		String why = BuildingWizardFeature.placeFixture(blueprintId);
		fixtureNote = why;
		return why;
	}

	/** "Place village board…": the bundled village board fixture. */
	public @Nullable String placeVillageBoard() {
		return placeFixture(dev.agentcraft.client.village.VillageBoardFeature.BLUEPRINT);
	}

	/** Cancel a queued or running design (design.cancel); the outcome shows under the buttons. */
	public java.util.concurrent.CompletableFuture<String> cancelDesign(String id) {
		designNote = "Cancelling " + id + "…";
		designNoteError = false;
		return DesignFeature.cancel(id).handle((ack, err) -> {
			if (err != null) {
				designNote = "Not cancelled: " + err.getMessage();
				designNoteError = true;
			} else if (!ack.ok()) {
				designNote = "Not cancelled: " + ack.error();
				designNoteError = true;
			} else {
				designNote = "Cancelled " + id;
				designNoteError = false;
			}
			return designNote;
		});
	}

	/** Place on the plot: the repo step, then placement mode locked on the plot marked for this blueprint's design. */
	public @Nullable String placeOnPlot(String blueprintId) {
		String why = DesignFeature.placeOnPlot(blueprintId);
		if (why != null) {
			designNote = why;
			designNoteError = true;
		}
		return why;
	}

	/** Design new: the generator form ({@link HubFeature#designNew}, installed by the design feature). */
	public boolean designNew() {
		Consumer<Screen> hook = HubFeature.designNew;
		if (hook == null) {
			return false;
		}
		hook.accept(hub);
		return true;
	}

	private void drawBuildingsTab(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		List<Building> bs = buildings();
		List<Building> fs = fixtures();
		List<Blueprint> bps = blueprints();
		List<Design> ds = designs();
		// sub switch (left) and Place new / Place village board / Design new (right). Narrow (GUI scale 4, ~426 px): the
		// counts go first (each list says them again), then the right button's label shortens, so the strip never overlaps
		boolean running = ds.stream().anyMatch(d -> d.status().isRunning());
		String rightFull = sub == Sub.DESIGNS ? "Design new…" : sub == Sub.FIXTURES ? "Place village board…" : sub == Sub.ROADS ? null : "Place new…";
		String rightShort = sub == Sub.DESIGNS ? "Design…" : sub == Sub.FIXTURES ? "Place board…" : "Place…";
		// 426 GUI px (4K, auto scale): "Place board…" still ran 1 px past the hub's edge, so Fixtures has a third step
		String rightTiny = sub == Sub.DESIGNS ? "Design…" : "Place…";
		int level = 0;
		int needed = 0;
		for (; level < 4; level++) {
			needed = 0;
			for (Sub s : Sub.values()) {
				needed += hub.bw(subLabel(s, level > 0, bs.size(), fs.size(), bps.size(), ds.size(), running)) + 4;
			}
			if (rightFull != null) {
				needed += hub.bw(level > 2 ? rightTiny : level > 1 ? rightShort : rightFull);
			} else {
				needed -= 4;
			}
			if (needed <= w) {
				break;
			}
		}
		level = Math.min(level, 3);
		int sx = x;
		for (Sub s : Sub.values()) {
			String label = subLabel(s, level > 0, bs.size(), fs.size(), bps.size(), ds.size(), running);
			int sw = hub.bw(label);
			hub.button(g, "sub:" + s.name().toLowerCase(Locale.ROOT), label, sx, y, sw, false, false, false, mx, my, () -> setSub(s));
			if (s == sub) {
				g.fill(sx + 4, y + 18, sx + sw - 4, y + 20, UiStyle.CLAY);
			}
			sx += sw + 4;
		}
		dev.agentcraft.client.road.RoadsFeature.reportStrip(needed, w, level);
		if (rightFull != null) {
			String right = level > 2 ? rightTiny : level > 1 ? rightShort : rightFull;
			if (sub == Sub.DESIGNS) {
				hub.button(g, "design_new", right, x + w - hub.bw(right), y, hub.bw(right), true, HubFeature.designNew == null, false, mx, my, this::designNew);
			} else if (sub == Sub.FIXTURES) {
				boolean can = hub.mc().getSingleplayerServer() != null && Blueprints.get(dev.agentcraft.client.village.VillageBoardFeature.BLUEPRINT) != null;
				hub.button(g, "place_board", right, x + w - hub.bw(right), y, hub.bw(right), true, !can, false, mx, my, this::placeVillageBoard);
			} else {
				hub.button(g, "place_new", right, x + w - hub.bw(right), y, hub.bw(right), true, hub.mc().getSingleplayerServer() == null, false, mx, my,
					this::placeNew);
			}
		}
		y += 26;
		h -= 26;
		switch (sub) {
			case BUILDINGS -> drawBuildings(g, bs, x, y, w, h, mx, my);
			case FIXTURES -> drawFixtures(g, fs, x, y, w, h, mx, my);
			case BLUEPRINTS -> drawBlueprints(g, bps, x, y, w, h, mx, my);
			case DESIGNS -> drawDesigns(g, ds, x, y, w, h, mx, my);
			case ROADS -> hub.roadsView.draw(g, x, y, w, h, mx, my);
		}
	}

	/** A Buildings sub-switch label, with its count unless {@code compact}. */
	private static String subLabel(Sub s, boolean compact, int buildings, int fixtures, int blueprints, int designs, boolean running) {
		String dot = running ? " ●" : "";
		return switch (s) {
			case BUILDINGS -> compact ? "Buildings" : "Buildings " + buildings;
			case FIXTURES -> compact ? "Fixtures" : "Fixtures " + fixtures;
			case BLUEPRINTS -> compact ? "Blueprints" : "Blueprints " + blueprints;
			case DESIGNS -> (compact ? "Designs" : "Designs " + designs) + dot;
			case ROADS -> compact ? "Roads" : "Roads " + dev.agentcraft.building.Roads.all().size();
		};
	}

	/**
	 * "Agents walk between buildings" for this world (docs/WAVE2.md W8, client side, walking.json): a toggle
	 * under the Buildings list column ({@code w} = the column's width); "Walking: On" when the full label does
	 * not fit. Returns the height it takes from the list.
	 */
	private int drawWalkToggle(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
		dev.agentcraft.client.agents.OutdoorRoutes walk = dev.agentcraft.client.agents.OutdoorRoutes.get();
		boolean on = walk.enabled();
		String state = on ? "On" : "Off";
		String full = "Agents walk between buildings: " + state;
		boolean compact = hub.bw(full) > w;
		String label = compact ? "Walking: " + state : full;
		int needed = hub.bw(label);
		hub.button(g, "walk_toggle", label, x, y, Math.min(w, needed), on, false, false, mx, my, () -> walk.setEnabled(!walk.enabled()));
		// compact: the rest of the column says what it means, when there is room for it
		String note = on ? "agents walk outdoors" : "agents teleport";
		int nx = x + needed + 5;
		if (compact && x + w - nx >= hub.font().width(note)) {
			g.text(hub.font(), note, nx, y + 6, UiBits.muted(), false);
		}
		walk.reportUi(needed, w, compact);
		return 24;
	}

	/**
	 * "Trophies for merges and finished goals" for this world (docs/BUILDINGS.md "Trophies", trophies.json): a toggle
	 * under the walking one; "Trophies: On" when the full label does not fit. Returns the height it takes.
	 */
	private int drawTrophyToggle(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
		boolean on = dev.agentcraft.client.trophy.TrophyFeature.enabled();
		String state = on ? "On" : "Off";
		String full = "Trophies for merges and finished goals: " + state;
		boolean compact = hub.bw(full) > w;
		String label = compact ? "Trophies: " + state : full;
		int needed = hub.bw(label);
		hub.button(g, "trophy_toggle", label, x, y, Math.min(w, needed), on, false, false, mx, my,
			() -> dev.agentcraft.client.trophy.TrophyFeature.setEnabled(!dev.agentcraft.client.trophy.TrophyFeature.enabled()));
		String note = on ? "signs on the trophy wall" : "no signs are hung";
		int nx = x + needed + 5;
		if (compact && x + w - nx >= hub.font().width(note)) {
			g.text(hub.font(), note, nx, y + 6, UiBits.muted(), false);
		}
		return 24;
	}

	/**
	 * The village routines for this world (docs/VILLAGE.md V3, routines.json): one row of three toggles under the trophy
	 * one, filled = on. "Night" (idle agents sleep in the building's beds at night), "Stand-ups" (a goal's lead and workers
	 * gather when its first tasks are assigned), "Library" (an agent walks to the library after writing a memory note).
	 * Normal buttons when they fit, else tighter ones, else shorter labels. Returns the height it takes.
	 */
	private int drawRoutineToggles(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
		var routines = dev.agentcraft.client.agents.Routines.get();
		var toggles = dev.agentcraft.routine.RoutineSettings.Toggle.values();
		String[][] labels = {{"Night", "Stand-ups", "Library"}, {"Night", "Stand-ups", "Library"}, {"Night", "Stand", "Lib"}};
		String[] modes = {"normal", "tight", "short"};
		int mode = 0;
		int[] ws = new int[toggles.length];
		int needed = 0;
		for (; mode < modes.length; mode++) {
			needed = 0;
			for (int i = 0; i < toggles.length; i++) {
				ws[i] = mode == 0 ? hub.bw(labels[mode][i]) : hub.font().width(labels[mode][i]) + 12; // UiBits.button keeps 6 px each side
				needed += ws[i] + (i > 0 ? 2 : 0);
			}
			if (needed <= w) {
				break;
			}
		}
		mode = Math.min(mode, modes.length - 1);
		int bx = x;
		for (int i = 0; i < toggles.length; i++) {
			var t = toggles[i];
			boolean on = routines.enabled(t);
			hub.button(g, "routine_toggle:" + t.key, labels[mode][i], bx, y, ws[i], on, false, false, mx, my, () -> routines.setEnabled(t, !routines.enabled(t)));
			bx += ws[i] + 2;
		}
		routines.reportUi(needed, w, modes[mode]);
		return 24;
	}

	void drawList(GuiGraphicsExtractor g, int x, int y, int w, int h, int count, int selected, int mx, int my, RowDrawer drawer) {
		listX = x;
		listY = y;
		listW = w;
		listH = h;
		Panels.inset(g, x, y, w, h);
		int rows = Math.max(1, (h - 6) / ROW);
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, count - rows)));
		if (selected >= 0 && selected < listScroll) {
			listScroll = selected;
		} else if (selected >= listScroll + rows) {
			listScroll = selected - rows + 1;
		}
		for (int r = 0; r < rows && listScroll + r < count; r++) {
			int i = listScroll + r;
			int ry = y + 3 + r * ROW;
			boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + ROW;
			if (i == selected) {
				g.fill(x + 2, ry, x + w - 2, ry + ROW - 1, 0x30D97757);
			} else if (hover) {
				g.fill(x + 2, ry, x + w - 2, ry + ROW - 1, 0x18000000);
			}
			String id = drawer.draw(i, x + 6, ry + 2, w - 12);
			rowRects.add(new int[] {x, ry, w, ROW});
			rowIds.add(id);
		}
		if (count > rows) {
			int trackH = h - 6;
			int thumbH = Math.max(8, trackH * rows / count);
			int ty = y + 3 + (trackH - thumbH) * listScroll / Math.max(1, count - rows);
			Panels.sprite(g, Kit.SCROLL_TRACK, x + w - 7, y + 3, 4, trackH);
			Panels.sprite(g, Kit.SCROLL_THUMB, x + w - 7, ty, 4, thumbH);
		}
	}

	private void drawBuildings(GuiGraphicsExtractor g, List<Building> bs, int x, int y, int w, int h, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Building cur = currentBuilding(bs);
		if (bs.isEmpty()) {
			Panels.inset(g, x, y, w, h);
			int ty = y + 10;
			String also = Keys.build == null || Keys.build.isUnbound() ? "" : " (also " + Keys.label(Keys.build) + ")";
			for (String line : TextUtil.wrapPlain(hub.font(), "No buildings in this world yet. \"Place new…\" opens the building wizard" + also
				+ ": pick repos and a blueprint, then place its ghost. The Blueprints list shows what you can build.", w - 16)) {
				g.text(hub.font(), line, x + 8, ty, muted, false);
				ty += 10;
			}
			return;
		}
		int lw = Math.max(150, Math.min(220, w * 2 / 5));
		// under the list column: "Agents walk between buildings" (per world, W8)
		int routineH = drawRoutineToggles(g, x, y + h - 20, lw, mx, my);
		int trophyH = drawTrophyToggle(g, x, y + h - 20 - routineH, lw, mx, my);
		int toggleH = drawWalkToggle(g, x, y + h - 20 - routineH - trophyH, lw, mx, my) + trophyH + routineH;
		drawList(g, x, y, lw, h - toggleH, bs.size(), bs.indexOf(cur), mx, my, (i, rx, ry, rw) -> {
			Building b = bs.get(i);
			Blueprint bp = Blueprints.get(b.blueprint());
			String name = bp != null ? bp.name() : b.blueprint();
			String head = b.id() + "  " + name;
			int hw = 0;
			if (b.home()) {
				hw = UiBits.dotPillWidth(hub.font(), "home") + 2;
				UiBits.dotPill(g, hub.font(), "done", "home", rx + rw - hw + 2, ry - 1, UiBits.okText());
			}
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), head, rw - hw - 2), rx, ry, ink, false);
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), String.join(", ", b.repos()) + " · " + LeadsFeature.leadLabel(b), rw), rx, ry + 10, muted, false);
			return b.id();
		});
		// detail
		int dx = x + lw + 10;
		int dw = w - lw - 10;
		if (cur == null) {
			return;
		}
		Blueprint bp = Blueprints.get(cur.blueprint());
		int dy = y;
		g.text(hub.font(), TextUtil.ellipsize(hub.font(), cur.id() + " · " + (bp != null ? bp.name() : cur.blueprint()), dw), dx, dy, ink, false);
		dy += 13;
		Anchors.Bounds box = cur.box();
		// the building's lead: its portrait + name, "Marlow (home)", or "no lead: Foreman offline"
		String leadId = Foreman.connected() ? Leads.view().leadOf(cur.id()) : null;
		if (leadId == null && Foreman.connected()) {
			leadId = LeadRouting.MARLOW;
		}
		String[][] facts = {
			{"Lead", LeadsFeature.leadLabel(cur)},
			{"Blueprint", cur.blueprint() + (bp == null ? " (not loaded)" : "")},
			{cur.repos().size() == 1 ? "Repo" : "Repos (wings)", String.join(", ", cur.repos())},
			{"Home", cur.home() ? "yes: idle agents go here" : "no"},
			{"Box", box.minX() + ", " + box.minY() + ", " + box.minZ() + "  ..  " + box.maxX() + ", " + box.maxY() + ", " + box.maxZ()},
			{"Size", (box.maxX() - box.minX() + 1) + " × " + (box.maxY() - box.minY() + 1) + " × " + (box.maxZ() - box.minZ() + 1)},
			{"Rotation", cur.rotation().replace('_', ' ')},
			{"Dimension", HubActions.pretty(cur.dimensionOrDefault()) + (cur.dimension() == null ? " (assumed: old record)" : "")},
			{"Placed", cur.placedAt() > 0 ? UiBits.ago(cur.placedAt()) : "?"},
			{"Check", checkLine(cur)}};
		int labelW = 0;
		for (String[] f : facts) {
			labelW = Math.max(labelW, hub.font().width(f[0]));
		}
		// the buttons (two or three rows) and a note line must stay inside the pane: on a short screen (426x240, the 4K
		// auto GUI scale) they ran over the footer and off the panel. Drop the least useful facts first.
		boolean tpShown = hub.mc().player != null && HubActions.teleportAllowed(hub.mc().player);
		int row1 = hub.bw("Make home") + 4 + (tpShown ? hub.bw("Teleport") + 4 : 0) + hub.bw("Confirm remove");
		int buttonsH = (row1 > dw ? 72 : 48) + 2 + 10;
		java.util.Set<String> dropped = new java.util.HashSet<>();
		String[] dropOrder = {"Placed", "Dimension", "Rotation", "Size", "Box", "Blueprint", "Home"};
		for (int di = 0; di <= dropOrder.length; di++) {
			int fh = 0;
			for (String[] f : facts) {
				if (f[1] != null && !dropped.contains(f[0])) {
					fh += TextUtil.wrapPlain(hub.font(), f[1], dw - labelW - 8 - (f[0].equals("Lead") ? 11 : 0)).size() * 10 + 1;
				}
			}
			if (dy + fh + 6 + buttonsH <= y + h || di == dropOrder.length) {
				break;
			}
			dropped.add(dropOrder[di]);
		}
		for (String[] f : facts) {
			if (f[1] == null || dropped.contains(f[0])) {
				continue;
			}
			g.text(hub.font(), f[0], dx, dy, f[0].equals("Check") ? UiBits.errorText() : muted, false);
			int vx = dx + labelW + 8;
			if (f[0].equals("Lead") && leadId != null) {
				ReviewKit.face(g, hub.font(), leadId, vx, dy - 1, 8);
				vx += 11;
			}
			for (String line : TextUtil.wrapPlain(hub.font(), f[1], dx + dw - vx)) {
				g.text(hub.font(), line, vx, dy, ink, false);
				dy += 10;
			}
			dy += 1;
		}
		dy += 6;
		boolean sp = hub.mc().getSingleplayerServer() != null;
		String id = cur.id();
		int bx = dx;
		String home = "Make home";
		hub.button(g, "home", home, bx, dy, hub.bw(home), false, busy || cur.home() || !sp, false, mx, my, () -> makeHome(id));
		bx += hub.bw(home) + 4;
		// C7: Teleport only with cheats on or in creative/spectator (a survival run walks)
		boolean tpAllowed = hub.mc().player != null && HubActions.teleportAllowed(hub.mc().player);
		if (tpAllowed) {
			String tp = "Teleport";
			hub.button(g, "teleport", tp, bx, dy, hub.bw(tp), false, busy || !sp, false, mx, my, () -> teleport(id));
			bx += hub.bw(tp) + 4;
		}
		boolean armedHere = armed() && id.equals(armedRemove);
		boolean forceHere = forceArmed(id);
		String rm = forceHere ? "Remove anyway" : armedHere ? "Confirm remove" : "Remove…";
		int rmw = hub.bw(rm);
		if (bx + rmw > dx + dw) {
			bx = dx;
			dy += 24;
		}
		hub.button(g, "remove", rm, bx, dy, rmw, armedHere, busy || !sp, !armedHere || forceHere, mx, my, () -> removeClick(id));
		dy += 24;
		// change the building without re-placing it
		bx = dx;
		String er = "Edit repos…";
		hub.button(g, "edit_repos", er, bx, dy, hub.bw(er), false, busy || !sp || Blueprints.get(cur.blueprint()) == null, false, mx, my, () -> editRepos(id));
		bx += hub.bw(er) + 4;
		String mvl = "Move…";
		hub.button(g, "move", mvl, bx, dy, hub.bw(mvl), false, busy || !sp || Blueprints.get(cur.blueprint()) == null, false, mx, my, () -> move(id));
		bx += hub.bw(mvl) + 4;
		if (cur.movedFrom() != null) {
			String um = "Undo move";
			if (bx + hub.bw(um) > dx + dw) {
				bx = dx;
				dy += 24;
			}
			hub.button(g, "undo_move", um, bx, dy, hub.bw(um), false, busy || !sp, false, mx, my, () -> undoMove(id));
		}
		dy += 26;
		String note;
		int noteColor = muted;
		HubActions.Result last = HubActions.last();
		if (forceHere) {
			long left = Math.max(0, (HubScreen.CONFIRM_MS - (System.currentTimeMillis() - armedAt) + 999) / 1000);
			HubActions.Result why = HubActions.last();
			note = (why != null ? why.message() + " " : "") + "Click Remove anyway to take " + id + " down regardless: those things are lost. (" + left + " s)";
			noteColor = UiBits.errorText();
		} else if (armedHere) {
			long left = Math.max(0, (HubScreen.CONFIRM_MS - (System.currentTimeMillis() - armedAt) + 999) / 1000);
			int roads = dev.agentcraft.building.Roads.forBuilding(id).size();
			note = "Click Confirm remove to take " + id + " down: the terrain that was there comes back exactly" + (roads == 0 ? "" : "; its "
				+ (roads == 1 ? "road stays" : roads + " roads stay") + " (Roads offers to remove " + (roads == 1 ? "it" : "them") + ")") + ". (" + left + " s)";
			noteColor = UiBits.errorText();
		} else if (busy) {
			note = "Working…";
		} else if (!sp) {
			note = "Singleplayer only: these act through the integrated server.";
		} else if (last != null) {
			note = last.message();
			noteColor = last.ok() ? UiBits.okText() : UiBits.errorText();
		} else {
			note = tpAllowed ? "Teleport lands at the entrance (in the building's dimension). Remove asks twice."
				: "No Teleport in survival without cheats: walk there (" + cur.box().minX() + ", " + cur.box().minY() + ", " + cur.box().minZ()
					+ "). Remove asks twice.";
		}
		for (String line : TextUtil.wrapPlain(hub.font(), note, dw)) {
			if (dy > y + h - 10) {
				break;
			}
			g.text(hub.font(), line, dx, dy, noteColor, false);
			dy += 10;
		}
	}

	/**
	 * The Fixtures list (docs/VILLAGE.md V2): placed village boards (and any fixture blueprint of the player's), each with
	 * where it stands and Teleport (cheats only), Remove (asks twice; the terrain comes back exactly), Move and Undo move.
	 * Fixtures take no repos, have no lead and are never home, so the building actions are not offered.
	 */
	private void drawFixtures(GuiGraphicsExtractor g, List<Building> fs, int x, int y, int w, int h, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Building cur = currentFixture(fs);
		if (fs.isEmpty()) {
			Panels.inset(g, x, y, w, h);
			int ty = y + 10;
			String text = "No fixtures in this world yet. A village board shows every building, its lead, active goal and PRs, the newest "
				+ "milestones and anything that holds the agents, readable from across the square. \"Place village board…\" puts up its ghost: "
				+ "Enter places it (the terrain is saved first; Remove puts it back exactly).";
			for (String line : TextUtil.wrapPlain(hub.font(), text, w - 16)) {
				g.text(hub.font(), line, x + 8, ty, muted, false);
				ty += 10;
			}
			if (fixtureNote != null) {
				for (String line : TextUtil.wrapPlain(hub.font(), fixtureNote, w - 16)) {
					g.text(hub.font(), line, x + 8, ty + 4, UiBits.errorText(), false);
					ty += 10;
				}
			}
			return;
		}
		int lw = Math.max(150, Math.min(220, w * 2 / 5));
		drawList(g, x, y, lw, h, fs.size(), fs.indexOf(cur), mx, my, (i, rx, ry, rw) -> {
			Building b = fs.get(i);
			Blueprint bp = Blueprints.get(b.blueprint());
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), b.id() + "  " + (bp != null ? bp.name() : b.blueprint()), rw), rx, ry, ink, false);
			Anchors.Bounds bx = b.box();
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), bx.minX() + ", " + bx.minY() + ", " + bx.minZ() + " · " + HubActions.pretty(b.dimensionOrDefault()), rw),
				rx, ry + 10, muted, false);
			return b.id();
		});
		if (cur == null) {
			return;
		}
		int dx = x + lw + 10;
		int dw = w - lw - 10;
		Blueprint bp = Blueprints.get(cur.blueprint());
		int dy = y;
		g.text(hub.font(), TextUtil.ellipsize(hub.font(), cur.id() + " · " + (bp != null ? bp.name() : cur.blueprint()), dw), dx, dy, ink, false);
		dy += 13;
		Anchors.Bounds box = cur.box();
		String[][] facts = {
			{"Kind", "fixture: no repos, no lead"},
			{"Blueprint", cur.blueprint() + (bp == null ? " (not loaded)" : "")},
			{"Box", box.minX() + ", " + box.minY() + ", " + box.minZ() + "  ..  " + box.maxX() + ", " + box.maxY() + ", " + box.maxZ()},
			{"Rotation", cur.rotation().replace('_', ' ')},
			{"Dimension", HubActions.pretty(cur.dimensionOrDefault())},
			{"Placed", cur.placedAt() > 0 ? UiBits.ago(cur.placedAt()) : "?"},
			{"Check", checkLine(cur)}};
		int labelW = 0;
		for (String[] f : facts) {
			labelW = Math.max(labelW, hub.font().width(f[0]));
		}
		// keep the buttons and three lines of the note inside the pane at 426x240: drop the least useful facts first (the
		// buttons take two rows, three when Undo move wraps; the right-click note wraps to three lines there and its last
		// line was cut with a budget of two)
		boolean undoWraps = cur.movedFrom() != null && hub.bw("Move…") + 4 + hub.bw("Undo move") > dw;
		int buttonsH = 24 + 24 + (undoWraps ? 24 : 0) + 2 + 30;
		java.util.Set<String> dropped = new java.util.HashSet<>();
		String[] dropOrder = {"Placed", "Kind", "Rotation", "Dimension", "Blueprint"};
		for (int di = 0; di <= dropOrder.length; di++) {
			int fh = 0;
			for (String[] f : facts) {
				if (f[1] != null && !dropped.contains(f[0])) {
					fh += TextUtil.wrapPlain(hub.font(), f[1], dw - labelW - 8).size() * 10 + 1;
				}
			}
			if (dy + fh + 6 + buttonsH <= y + h || di == dropOrder.length) {
				break;
			}
			dropped.add(dropOrder[di]);
		}
		for (String[] f : facts) {
			if (f[1] == null || dropped.contains(f[0])) {
				continue;
			}
			g.text(hub.font(), f[0], dx, dy, f[0].equals("Check") ? UiBits.errorText() : muted, false);
			int vx = dx + labelW + 8;
			for (String line : TextUtil.wrapPlain(hub.font(), f[1], dx + dw - vx)) {
				g.text(hub.font(), line, vx, dy, ink, false);
				dy += 10;
			}
			dy += 1;
		}
		dy += 6;
		boolean sp = hub.mc().getSingleplayerServer() != null;
		String id = cur.id();
		int bx = dx;
		boolean tpAllowed = hub.mc().player != null && HubActions.teleportAllowed(hub.mc().player);
		if (tpAllowed) {
			String tp = "Teleport";
			hub.button(g, "teleport", tp, bx, dy, hub.bw(tp), false, busy || !sp, false, mx, my, () -> teleport(id));
			bx += hub.bw(tp) + 4;
		}
		boolean armedHere = armed() && id.equals(armedRemove);
		boolean forceHere = forceArmed(id);
		String rm = forceHere ? "Remove anyway" : armedHere ? "Confirm remove" : "Remove…";
		hub.button(g, "remove", rm, bx, dy, hub.bw(rm), armedHere, busy || !sp, !armedHere || forceHere, mx, my, () -> removeClick(id));
		dy += 24;
		bx = dx;
		String mvl = "Move…";
		hub.button(g, "move", mvl, bx, dy, hub.bw(mvl), false, busy || !sp || bp == null, false, mx, my, () -> move(id));
		bx += hub.bw(mvl) + 4;
		if (cur.movedFrom() != null) {
			String um = "Undo move";
			if (bx + hub.bw(um) > dx + dw) {
				bx = dx;
				dy += 24;
			}
			hub.button(g, "undo_move", um, bx, dy, hub.bw(um), false, busy || !sp, false, mx, my, () -> undoMove(id));
		}
		dy += 26;
		String note;
		int noteColor = muted;
		HubActions.Result last = HubActions.last();
		if (forceHere) {
			long left = Math.max(0, (HubScreen.CONFIRM_MS - (System.currentTimeMillis() - armedAt) + 999) / 1000);
			note = (last != null ? last.message() + " " : "") + "Click Remove anyway to take " + id + " down regardless: those things are lost. (" + left
				+ " s)";
			noteColor = UiBits.errorText();
		} else if (armedHere) {
			long left = Math.max(0, (HubScreen.CONFIRM_MS - (System.currentTimeMillis() - armedAt) + 999) / 1000);
			note = "Click Confirm remove to take " + id + " down: the terrain that was there comes back exactly. (" + left + " s)";
			noteColor = UiBits.errorText();
		} else if (busy) {
			note = "Working…";
		} else if (!sp) {
			note = "Singleplayer only: these act through the integrated server.";
		} else if (fixtureNote != null) {
			note = fixtureNote;
			noteColor = UiBits.errorText();
		} else if (last != null) {
			note = last.message();
			noteColor = last.ok() ? UiBits.okText() : UiBits.errorText();
		} else {
			note = "Right-click the board in the world: it opens the hub here, or the Inbox when something needs you.";
		}
		for (String line : TextUtil.wrapPlain(hub.font(), note, dw)) {
			if (dy > y + h - 10) {
				break;
			}
			g.text(hub.font(), line, dx, dy, noteColor, false);
			dy += 10;
		}
	}

	/** The world-start check of a building ({@link Buildings#reports()}), or null when there is nothing to say. */
	private static @Nullable String checkLine(Building b) {
		Buildings.Report r = Buildings.reports().get(b.id());
		return r == null ? null : r.message();
	}

	private void drawBlueprints(GuiGraphicsExtractor g, List<Blueprint> bps, int x, int y, int w, int h, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Blueprint cur = currentBlueprint(bps);
		if (bps.isEmpty()) {
			Panels.inset(g, x, y, w, h);
			int ty = y + 10;
			for (String line : TextUtil.wrapPlain(hub.font(), hub.mc().getSingleplayerServer() == null
				? "Blueprints are loaded by the integrated server: open a singleplayer world."
				: "No blueprints are loaded. Yours go in <game dir>/agentcraft/blueprints (<id>.nbt + <id>.blueprint.json).", w - 16)) {
				g.text(hub.font(), line, x + 8, ty, muted, false);
				ty += 10;
			}
			return;
		}
		int lw = Math.max(140, Math.min(190, w / 3));
		int selected = cur == null ? -1 : bps.indexOf(cur);
		drawList(g, x, y, lw, h, bps.size(), selected, mx, my, (i, rx, ry, rw) -> {
			Blueprint bp = bps.get(i);
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), bp.name(), rw), rx, ry, ink, false);
			Blueprints.Entry e = Blueprints.entry(bp.id());
			String src = e != null && e.source().startsWith("user") ? "yours" : "bundled";
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), kind(bp) + " · " + size(bp) + " · " + src, rw), rx, ry + 10, muted, false);
			return bp.id();
		});
		if (cur == null) {
			return;
		}
		int dx = x + lw + 10;
		int dw = w - lw - 10;
		// view chips: the built-in plan + every rendered preview found
		List<PreviewImages.Found> found = PreviewImages.find(cur.id());
		String shown = view;
		if (shown == null || !shown.equals("plan") && found.stream().noneMatch(f -> f.kind().equals(view))) {
			shown = found.stream().filter(f -> f.kind().equals("iso")).findFirst().map(PreviewImages.Found::kind).orElse("plan");
		}
		int chipX = dx;
		List<String> kinds = new ArrayList<>();
		kinds.add("plan");
		found.forEach(f -> kinds.add(f.kind()));
		for (String k : kinds) {
			String label = k.equals("plan") ? "Plan" : Character.toUpperCase(k.charAt(0)) + k.substring(1);
			int cw = hub.font().width(label) + 10;
			boolean on = k.equals(shown);
			Panels.sprite(g, on ? Kit.TAB_ACTIVE : Kit.TAB_INACTIVE, chipX, y, cw, 13);
			g.text(hub.font(), label, chipX + 5, y + 3, on ? ink : muted, false);
			viewRects.add(new int[] {chipX, y, cw, 13});
			viewIds.add(k);
			chipX += cw + 2;
		}
		if (found.isEmpty()) {
			String none = "no rendered previews";
			if (chipX + 6 + hub.font().width(none) <= dx + dw) {
				g.text(hub.font(), none, chipX + 6, y + 3, UiStyle.color("paper.disabled", 0xFFA39B8E), false);
			}
		}
		int iy = y + 16;
		// image well: as tall as fits above the name line, three description lines and the buttons
		int imgH = Math.max(40, Math.min(170, h - 16 - 15 - 30 - 24));
		int imgW = dw;
		Panels.inset(g, dx, iy, imgW, imgH);
		if (shown.equals("plan")) {
			int side = Math.min(imgH, imgW) - 8;
			BlueprintPreview.draw(g, cur.id(), dx + (imgW - side) / 2, iy + 4, side);
		} else {
			String k = shown;
			PreviewImages.Found f = found.stream().filter(ff -> ff.kind().equals(k)).findFirst().orElse(null);
			if (f == null || !PreviewImages.draw(g, f, dx + 3, iy + 3, imgW - 6, imgH - 6)) {
				String err = f == null ? null : PreviewImages.error(f);
				String msg = err != null ? "Could not show it: " + err : "Loading…";
				int ty = iy + imgH / 2 - 5;
				for (String line : TextUtil.wrapPlain(hub.font(), msg, imgW - 16)) {
					g.text(hub.font(), line, dx + (imgW - hub.font().width(line)) / 2, ty, err != null ? UiBits.errorText() : muted, false);
					ty += 10;
				}
			}
		}
		int ty = iy + imgH + 4;
		g.text(hub.font(), TextUtil.ellipsize(hub.font(), cur.name() + "  ·  " + kind(cur) + "  ·  " + size(cur), dw), dx, ty, ink, false);
		ty += 11;
		// description: wrapped to the column; scrolls (wheel) when it does not fit above the buttons
		if (!cur.id().equals(descFor)) {
			descFor = cur.id();
			descScroll = 0;
		}
		List<String> desc = TextUtil.wrapPlain(hub.font(), cur.description().isEmpty() ? "(no description)" : cur.description(), dw - 8);
		int buttonsY = y + h - 20;
		int room = Math.max(1, (buttonsY - 4 - ty) / 10);
		descTotal = desc.size();
		descRows = Math.min(desc.size(), room);
		descScroll = Math.max(0, Math.min(descScroll, desc.size() - descRows));
		descX = dx;
		descY = ty;
		descW = dw;
		for (int r = 0; r < descRows; r++) {
			g.text(hub.font(), desc.get(descScroll + r), dx, ty + r * 10, muted, false);
		}
		if (desc.size() > descRows) {
			int trackH = descRows * 10;
			int thumbH = Math.max(6, trackH * descRows / desc.size());
			int sy = ty + (trackH - thumbH) * descScroll / Math.max(1, desc.size() - descRows);
			Panels.sprite(g, Kit.SCROLL_TRACK, dx + dw - 4, ty, 4, trackH);
			Panels.sprite(g, Kit.SCROLL_THUMB, dx + dw - 4, sy, 4, thumbH);
		}
		String id = cur.id();
		boolean sp = hub.mc().getSingleplayerServer() != null;
		String pl = "Place…";
		int bx = dx + dw - hub.bw(pl);
		hub.button(g, "place", pl, bx, buttonsY, hub.bw(pl), DesignFeature.plotForBlueprint(id) == null, !sp, false, mx, my, () -> placeBlueprint(id));
		if (DesignFeature.plotForBlueprint(id) != null) {
			String pp = "Place on the plot";
			bx -= 4 + hub.bw(pp);
			hub.button(g, "place_plot", pp, bx, buttonsY, hub.bw(pp), true, !sp, false, mx, my, () -> placeOnPlot(id));
		}
		String dn = "Design new…";
		if (bx - 4 - hub.bw(dn) >= dx) {
			hub.button(g, "design_new", dn, bx - 4 - hub.bw(dn), buttonsY, hub.bw(dn), false, HubFeature.designNew == null, false, mx, my, this::designNew);
		}
	}

	// ------------------------------------------------------------------ Designs

	/** Status dot family of a design. */
	static String family(Design d) {
		return switch (d.status()) {
			case QUEUED -> "waiting";
			case DESIGNING, CHECKING, RENDERING -> "working";
			case DONE -> "done";
			case FAILED -> "error";
			default -> "idle";
		};
	}

	static String title(Design d) {
		String n = d.request().name();
		if (n != null && !n.isBlank()) {
			return n;
		}
		dev.agentcraft.building.DesignSpec.Choice st = dev.agentcraft.building.DesignSpec.style(d.request().style());
		return (st != null ? st.label() : d.request().style()) + (d.request().kind().equals("group") ? " campus" : " building");
	}

	private void drawDesigns(GuiGraphicsExtractor g, List<Design> ds, int x, int y, int w, int h, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Design cur = null;
		for (Design d : ds) {
			if (d.id().equals(selectedDesign)) {
				cur = d;
			}
		}
		if (cur == null && !ds.isEmpty() && (selectedDesign == null || Foreman.state() == null || Foreman.state().design(selectedDesign) == null)
			&& !(selectedDesign != null && selectedDesign.equals(DesignFeature.lastSent()))) {
			cur = ds.get(0);
			selectedDesign = cur.id();
		}
		if (ds.isEmpty()) {
			Panels.inset(g, x, y, w, h);
			int ty = y + 10;
			String msg = !Foreman.connected() ? "The Foreman designs buildings: it is not connected. Start it, then \"Design new…\" here."
				: "No designs yet. \"Design new…\" describes a building (style, size, features, or a plot you mark); the Foreman's design "
				+ "agent builds it as a blueprint, with previews, and it shows up here and in Blueprints.";
			for (String line : TextUtil.wrapPlain(hub.font(), msg, w - 16)) {
				g.text(hub.font(), line, x + 8, ty, muted, false);
				ty += 10;
			}
			return;
		}
		int lw = Math.max(150, Math.min(220, w * 2 / 5));
		Design sel = cur;
		drawList(g, x, y, lw, h, ds.size(), sel == null ? -1 : ds.indexOf(sel), mx, my, (i, rx, ry, rw) -> {
			Design d = ds.get(i);
			String pill = d.status().wire();
			int pw = UiBits.dotPillWidth(hub.font(), pill);
			UiBits.dotPill(g, hub.font(), family(d), pill, rx + rw - pw + 2, ry - 1, d.status() == Protocol.DesignStatus.DONE ? UiBits.okText() : muted);
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), d.id() + "  " + title(d), rw - pw - 4), rx, ry, ink, false);
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), d.step(), rw), rx, ry + 10, muted, false);
			return d.id();
		});
		int dx = x + lw + 10;
		int dw = w - lw - 10;
		if (cur == null) {
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), "Waiting for " + selectedDesign + "…", dw), dx, y, muted, false);
			return;
		}
		Design d = cur;
		int dy = y;
		g.text(hub.font(), TextUtil.ellipsize(hub.font(), d.id() + " · " + title(d), dw), dx, dy, ink, false);
		dy += 13;
		int pw = UiBits.dotPill(g, hub.font(), family(d), d.status().wire(), dx, dy - 1, ink);
		for (String line : TextUtil.wrapPlain(hub.font(), d.step(), dw - pw - 6)) {
			g.text(hub.font(), line, dx + pw + 6, dy, muted, false);
			dy += 10;
			break;
		}
		dy += 4;
		var r = d.request();
		List<String[]> facts = new ArrayList<>();
		facts.add(new String[] {"For", r.kind().equals("group") ? "a group, " + r.wings() + " wings" : "one repo"});
		dev.agentcraft.building.DesignSpec.Choice st = dev.agentcraft.building.DesignSpec.style(r.style());
		facts.add(new String[] {"Style", (st != null ? st.label() : r.style()) + " · " + (r.materials().equals("vanilla") ? "vanilla allowed"
			: "AgentCraft first")});
		facts.add(new String[] {"Features", r.features().isEmpty() ? "none" : String.join(", ", r.features()).replace('_', ' ')});
		facts.add(new String[] {"Limit", r.maxSize().x() + " × " + r.maxSize().y() + " × " + r.maxSize().z() + (DesignFeature.plotForDesign(d.id())
			!= null ? " (from your plot)" : "")});
		if (r.remix() != null) {
			facts.add(new String[] {"Remix", r.remix()});
		}
		if (d.blueprintId() != null) {
			boolean loaded = Blueprints.get(d.blueprintId()) != null;
			facts.add(new String[] {"Blueprint", d.blueprintId() + (d.size() != null ? "  " + d.size().x() + " × " + d.size().y() + " × "
				+ d.size().z() : "") + (loaded ? "" : " (not loaded yet)")});
		}
		facts.add(new String[] {"Requested", UiBits.ago(d.createdAt()) + (d.updatedAt() > d.createdAt() ? " · updated " + UiBits.ago(d.updatedAt())
			: "")});
		int labelW = 0;
		for (String[] f : facts) {
			labelW = Math.max(labelW, hub.font().width(f[0]));
		}
		for (String[] f : facts) {
			g.text(hub.font(), f[0], dx, dy, muted, false);
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), f[1], dw - labelW - 8), dx + labelW + 8, dy, ink, false);
			dy += 11;
		}
		int buttonsY = y + h - 20;
		// notes (the request's) and the error, wrapped into what is left above the buttons
		int room = Math.max(0, (buttonsY - 4 - dy) / 10);
		List<String> extra = new ArrayList<>();
		int errFrom = -1;
		if (d.status() == Protocol.DesignStatus.FAILED && d.error() != null) {
			errFrom = 0;
			extra.addAll(TextUtil.wrapPlain(hub.font(), "Error: " + d.error().strip(), dw));
		}
		if (r.notes() != null && !r.notes().isBlank()) {
			extra.addAll(TextUtil.wrapPlain(hub.font(), "Notes: " + r.notes().strip().replace('\n', ' '), dw));
		}
		int errLines = errFrom < 0 ? 0 : TextUtil.wrapPlain(hub.font(), "Error: " + d.error().strip(), dw).size();
		for (int i = 0; i < Math.min(room, extra.size()); i++) {
			String line = extra.get(i);
			if (i == room - 1 && extra.size() > room) {
				line = TextUtil.ellipsize(hub.font(), line + " …", dw);
			}
			g.text(hub.font(), line, dx, dy, i < errLines ? UiBits.errorText() : muted, false);
			dy += 10;
		}
		// buttons
		boolean sp = hub.mc().getSingleplayerServer() != null;
		int bx = dx + dw;
		String id = d.id();
		if (d.status().isRunning()) {
			String c = "Cancel design";
			bx -= hub.bw(c);
			hub.button(g, "cancel_design", c, bx, buttonsY, hub.bw(c), false, !Foreman.connected(), true, mx, my, () -> cancelDesign(id));
		} else if (d.status() == Protocol.DesignStatus.DONE && d.blueprintId() != null && Blueprints.get(d.blueprintId()) != null) {
			String bp = d.blueprintId();
			boolean plot = DesignFeature.plotForBlueprint(bp) != null;
			String pl = "Place…";
			bx -= hub.bw(pl);
			hub.button(g, "place", pl, bx, buttonsY, hub.bw(pl), !plot, !sp, false, mx, my, () -> placeBlueprint(bp));
			if (plot) {
				String pp = "Place on the plot";
				bx -= 4 + hub.bw(pp);
				hub.button(g, "place_plot", pp, bx, buttonsY, hub.bw(pp), true, !sp, false, mx, my, () -> placeOnPlot(bp));
			}
			String show = "Show blueprint";
			if (bx - 4 - hub.bw(show) >= dx) {
				bx -= 4 + hub.bw(show);
				hub.button(g, "show_blueprint", show, bx, buttonsY, hub.bw(show), false, false, false, mx, my, () -> {
					selectBlueprint(bp);
					setView("iso");
				});
			}
		} else if (d.status() == Protocol.DesignStatus.DONE && d.blueprintId() != null && sp) {
			String rl = "Reload blueprints";
			bx -= hub.bw(rl);
			hub.button(g, "reload", rl, bx, buttonsY, hub.bw(rl), true, false, false, mx, my, () -> DesignFeature.reloadAndSelect(d.blueprintId()));
		}
		if (designNote != null && id.equals(selectedDesign) && buttonsY - 12 > dy) {
			g.text(hub.font(), TextUtil.ellipsize(hub.font(), designNote, dw), dx, buttonsY - 12, designNoteError ? UiBits.errorText() : muted, false);
		}
	}

	static String kind(Blueprint bp) {
		return bp.isFixture() ? "fixture" : bp.isGroup() ? "group, " + bp.wings() + " wings" : "single";
	}

	static String size(Blueprint bp) {
		return String.format(Locale.ROOT, "%d×%d×%d", bp.sizeX(), bp.sizeY(), bp.sizeZ());
	}
}
