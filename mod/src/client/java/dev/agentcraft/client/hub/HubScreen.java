package dev.agentcraft.client.hub;

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
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.LinkStatus;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Design;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.client.foreman.Protocol.UsageWindow;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.diff.ReviewKit;
import dev.agentcraft.client.leads.Leads;
import dev.agentcraft.client.leads.LeadsFeature;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.layout.Anchors;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The AgentCraft hub (docs/HUB.md "Hub screen"): one paper window with tabs for everything a player
 * sets or does in AgentCraft. Built: <b>Buildings</b> (the world's buildings with make home, teleport and
 * a two-step remove; a blueprint browser with the top-down plan, rendered PNG previews when present,
 * Place, Place on the plot and Design new; the building designs with their progress, Cancel and, when
 * done, the new blueprint) and <b>Status</b> (Foreman link, backend, auth, account, plan
 * usage, versions, DevBridge). The other tabs show what they will hold.
 *
 * <p>Everything acts through {@link HubActions} / the wizard, never chat commands, so it works without
 * operator permission (Hardcore). Selections are kept by id, so a list changing under the screen never
 * makes a button act on another building. Not a pause screen.
 */
public final class HubScreen extends Screen {
	static final int MAX_W = 600;
	static final int MAX_H = 380;
	private static final int ROW = 22;
	private static final int TAB_H = 20;
	/** How long an armed Remove waits for its confirming second click. */
	static final long CONFIRM_MS = 6000;
	private static final DateTimeFormatter RESETS = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT);

	/** Buildings tab: the world's buildings, the blueprint browser or the building designs. */
	public enum Sub {
		BUILDINGS, BLUEPRINTS, DESIGNS
	}

	record Btn(String id, String label, int x, int y, int w, boolean primary, boolean disabled, boolean danger, Runnable action) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + 20;
		}
	}

	private HubTab tab;
	private Sub sub = Sub.BUILDINGS;
	private @Nullable String selectedBuilding;
	private @Nullable String selectedBlueprint;
	private @Nullable String selectedDesign;
	/** The outcome of the last Cancel / Place on the plot pressed here (shown under the buttons). */
	private @Nullable String designNote;
	private boolean designNoteError;
	/** Remove armed for this building id until {@link #armedAt} + {@link #CONFIRM_MS}. */
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
	private final List<Btn> buttons = new ArrayList<>();
	private final List<int[]> tabRects = new ArrayList<>();
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
	final ReposTab repos = new ReposTab(this);
	final GoalsTab goals = new GoalsTab(this);
	final TeamTab team = new TeamTab(this);
	final SettingsTab settings = new SettingsTab(this);
	final InboxTab inbox = new InboxTab(this);
	final StatusPane status = new StatusPane(this);
	// tab strip layout last frame (dev.hub.state tabs): badges per tab id, compact = badges shrunk to dots
	private int tabsNeeded;
	private int tabsAvailable;
	private boolean tabsCompact;
	private boolean tabsOverflow;
	private boolean opened;
	private boolean textInput;

	public HubScreen(HubTab tab) {
		super(Component.literal("AgentCraft hub"));
		this.tab = tab;
	}

	/** The pane of a tab with its own state and input (Repos, Goals), or null. */
	@Nullable HubPane pane(HubTab t) {
		return switch (t) {
			case INBOX -> inbox;
			case REPOS -> repos;
			case GOALS -> goals;
			case TEAM -> team;
			case SETTINGS -> settings;
			case STATUS -> status;
			default -> null;
		};
	}

	@Nullable HubPane pane() {
		return pane(tab);
	}

	@Override
	protected void init() {
		super.init();
		if (!opened) {
			opened = true;
			HubGoals.checkAway();
		}
		// also on coming back from a screen opened over the hub (task, decision): removed() hid the pane
		HubPane p = pane();
		if (p != null) {
			p.shown(true);
		}
	}

	@Override
	public boolean isInputCaptured() {
		HubPane p = pane();
		return p != null && p.focus() != null;
	}

	/** A pane's text field gained or lost focus: SDL text input follows (typed characters are only delivered while on). */
	void textFocus(boolean on) {
		if (on != textInput && minecraft != null) {
			textInput = on;
			minecraft.onTextInputFocusChange(this, on);
		}
	}

	net.minecraft.client.gui.Font font() {
		return font;
	}

	net.minecraft.client.Minecraft mc() {
		return minecraft;
	}

	/** Presses a button drawn last frame by id (DevBridge: "every button"); false when there is none or it is disabled. */
	boolean press(String id) {
		for (Btn b : List.copyOf(buttons)) {
			if (b.id().equals(id)) {
				if (b.disabled()) {
					return false;
				}
				b.action().run();
				return true;
			}
		}
		return false;
	}

	boolean hasButton(String id) {
		return buttons.stream().anyMatch(b -> b.id().equals(id));
	}

	@Override
	public boolean isPauseScreen() {
		return dev.agentcraft.client.ui.ScreenPause.pauses();
	}

	@Override
	public void removed() {
		PreviewImages.releaseAll();
		HubPane p = pane();
		if (p != null) {
			p.unfocus();
			p.shown(false);
		}
		textFocus(false);
		HubGoals.flush(true);
		super.removed();
	}

	// ------------------------------------------------------------------ state (DevBridge too)

	public HubTab tab() {
		return tab;
	}

	public void setTab(HubTab t) {
		if (t != tab) {
			HubPane old = pane();
			if (old != null) {
				old.unfocus();
				if (opened) {
					old.shown(false);
				}
			}
			tab = t;
			HubPane now = pane();
			if (now != null && opened) {
				now.shown(true);
			}
			disarm();
			listScroll = 0;
		}
	}

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

	public @Nullable String selectedBlueprint() {
		return selectedBlueprint;
	}

	public @Nullable String selectedDesign() {
		return selectedDesign;
	}

	/** Selects a design by id (kept even when the Foreman has not reported it yet); switches to the designs list. */
	public void selectDesign(String id) {
		setTab(HubTab.BUILDINGS);
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

	/** Selects a building by id (false when there is none); switches to the buildings list. */
	public boolean selectBuilding(String id) {
		if (Buildings.get(id) == null) {
			return false;
		}
		setTab(HubTab.BUILDINGS);
		setSub(Sub.BUILDINGS);
		if (!id.equals(selectedBuilding)) {
			disarm();
		}
		selectedBuilding = id;
		return true;
	}

	/** Selects a blueprint by id (false when it is not loaded); switches to the blueprint browser. */
	public boolean selectBlueprint(String id) {
		if (Blueprints.get(id) == null) {
			return false;
		}
		setTab(HubTab.BUILDINGS);
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
		return armedRemove != null && System.currentTimeMillis() - armedAt < CONFIRM_MS;
	}

	private void disarm() {
		armedRemove = null;
	}

	List<Building> buildings() {
		return Buildings.all();
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
			if (r.ok() && minecraft != null && minecraft.gui.screen() == this) {
				onClose(); // you are there now: show the world
			}
			return r;
		});
	}

	/**
	 * Remove, step one: arms the confirm for {@code id} (returns null). Step two (the same id again within
	 * {@link #CONFIRM_MS}): removes it and returns the future.
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
		BuildingWizardFeature.openEditRepos(id, this, repos -> track(HubActions.setRepos(id, repos)));
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

	/** Place a blueprint: the wizard's repo step with this blueprint fixed, then straight to placement. */
	public void placeBlueprint(String id) {
		BuildingWizardFeature.openFor(id);
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
		hook.accept(this);
		return true;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		HubPane p = pane();
		if (p != null && p.keyPressed(e)) {
			return true;
		}
		if (e.isEscape() || Keys.matches(Keys.hub, e)) {
			onClose();
			return true;
		}
		if (k == InputConstants.KEY_TAB) {
			HubTab[] all = HubTab.values();
			setTab(all[Math.floorMod(tab.ordinal() + (e.hasShiftDown() ? -1 : 1), all.length)]);
			return true;
		}
		if (tab == HubTab.BUILDINGS && (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN)) {
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
		if (tab == HubTab.BUILDINGS && (k == InputConstants.KEY_LEFT || k == InputConstants.KEY_RIGHT)) {
			Sub[] all = Sub.values();
			setSub(all[Math.max(0, Math.min(all.length - 1, sub.ordinal() + (k == InputConstants.KEY_LEFT ? -1 : 1)))]);
			return true;
		}
		return super.keyPressed(e);
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
	public boolean charTyped(net.minecraft.client.input.CharacterEvent e) {
		HubPane p = pane();
		if (p != null && p.charTyped(e)) {
			return true;
		}
		return super.charTyped(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		for (int i = 0; i < tabRects.size(); i++) {
			int[] r = tabRects.get(i);
			if (e.x() >= r[0] && e.x() < r[0] + r[2] && e.y() >= r[1] && e.y() < r[1] + r[3]) {
				setTab(HubTab.values()[i]);
				return true;
			}
		}
		for (Btn b : List.copyOf(buttons)) {
			if (b.hit(e.x(), e.y())) {
				if (!b.disabled()) {
					b.action().run();
				}
				return true;
			}
		}
		HubPane pane = pane();
		if (pane != null && pane.mouseClicked(e.x(), e.y(), doubleClick)) {
			return true;
		}
		for (int i = 0; i < viewRects.size(); i++) {
			int[] r = viewRects.get(i);
			if (e.x() >= r[0] && e.x() < r[0] + r[2] && e.y() >= r[1] && e.y() < r[1] + r[3]) {
				view = viewIds.get(i);
				return true;
			}
		}
		for (int i = 0; i < rowRects.size(); i++) {
			int[] r = rowRects.get(i);
			if (e.x() >= r[0] && e.x() < r[0] + r[2] && e.y() >= r[1] && e.y() < r[1] + r[3]) {
				String id = rowIds.get(i);
				if (sub == Sub.DESIGNS) {
					selectDesign(id);
				} else if (sub == Sub.BUILDINGS) {
					selectBuilding(id);
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
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int d = scrollY > 0 ? -1 : 1;
		HubPane pane = pane();
		if (pane != null && pane.mouseScrolled(x, y, d)) {
			return true;
		}
		if (descRows > 0 && x >= descX && x < descX + descW && y >= descY && y < descY + descRows * 10) {
			descScroll = Math.max(0, Math.min(Math.max(0, descTotal - descRows), descScroll + d));
			return true;
		}
		if (x >= listX && x < listX + listW && y >= listY && y < listY + listH) {
			listScroll = Math.max(0, listScroll + d);
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractBlurredBackground(g);
		g.fillGradient(0, 0, width, height, UiStyle.withAlpha(UiStyle.INK, 50), UiStyle.withAlpha(UiStyle.INK, 100));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		buttons.clear();
		tabRects.clear();
		rowRects.clear();
		rowIds.clear();
		viewRects.clear();
		viewIds.clear();
		descRows = 0;
		listW = 0;
		if (armedRemove != null && !armed()) {
			armedRemove = null;
		}
		int pw = Math.min(MAX_W, width - 16);
		int ph = Math.min(MAX_H, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		Kit.Padding pad = Kit.padding("panel_paper");
		int cx = px + pad.left();
		int cy = py + pad.top();
		int cw = pw - pad.left() - pad.right();
		int bottom = py + ph - pad.bottom();
		Panels.panel(g, px, py, pw, ph);
		Panels.header(g, font, "AgentCraft hub", cx - 2, cy - 2, cw + 4);
		String keyHint = Keys.label(Keys.hub);
		int kw = UiBits.hintsWidth(font, keyHint, "close");
		UiBits.hints(g, font, cx + cw - kw - 2, cy - 1, false, keyHint, "close");
		int y = cy + 16;
		drawTabs(g, cx, y, cw, mouseX, mouseY);
		y += TAB_H + 6;
		int footerY = bottom - 12;
		switch (tab) {
			case BUILDINGS -> drawBuildingsTab(g, cx, y, cw, footerY - 4 - y, mouseX, mouseY);
			case STATUS -> status.draw(g, cx, y, cw, footerY - 4 - y, mouseX, mouseY);
			case REPOS -> repos.draw(g, cx, y, cw, footerY - 4 - y, mouseX, mouseY);
			case GOALS -> goals.draw(g, cx, y, cw, footerY - 4 - y, mouseX, mouseY);
			case TEAM -> team.draw(g, cx, y, cw, footerY - 4 - y, mouseX, mouseY);
			case SETTINGS -> settings.draw(g, cx, y, cw, footerY - 4 - y, mouseX, mouseY);
			case INBOX -> inbox.draw(g, cx, y, cw, footerY - 4 - y, mouseX, mouseY);
			default -> drawComingNext(g, cx, y, cw, footerY - 4 - y);
		}
		HubPane hp = pane();
		String[] hints = hp != null ? hp.hints() : tab == HubTab.BUILDINGS ? new String[] {"Tab", "next tab", "←→", "buildings/blueprints/designs", "↑↓", "select",
			"Esc", "close"} : new String[] {"Tab", "next tab", "Esc", "close"};
		ForemanState fst = Foreman.state();
		if (fst != null && fst.readOnly()) {
			// never fail silently: every action is refused on a read-only connection
			g.text(font, TextUtil.ellipsize(font, UiBits.CROSS + " " + Foreman.READ_ONLY + ": actions are refused", cw), cx, footerY + 1,
				UiBits.errorText(), false);
		} else if (UiBits.hintsWidth(font, hints) <= cw) {
			UiBits.hints(g, font, cx, footerY, false, hints);
		}
	}

	/** Width the tab strip needs (every tab's label + padding) and had last frame (DevBridge layout: 7 tabs at ~426 GUI px). */
	int tabStripNeeded() {
		int n = 0;
		for (HubTab t : HubTab.values()) {
			n += font.width(t.label) + 16 + 2;
		}
		return n - 2;
	}

	int tabStripAvailable() {
		return tabStripW;
	}

	private int tabStripW;

	private void drawTabs(GuiGraphicsExtractor g, int x0, int y, int w, int mx, int my) {
		tabStripW = w;
		int edge = UiStyle.color("palette.ui.panel_edge");
		g.fill(x0, y + TAB_H - 1, x0 + w, y + TAB_H, edge);
		// badges (docs/WAVE2.md W5): a status dot and the count after the label; when the strip would not fit,
		// just the dot; still too wide: the labels are cut
		HubTab[] all = HubTab.values();
		TabBadges.Badge[] badges = new TabBadges.Badge[all.length];
		int full = 0;
		int dots = 0;
		for (int i = 0; i < all.length; i++) {
			badges[i] = TabBadges.of(all[i]);
			int base = font.width(all[i].label) + 16 + 2;
			full += base + (badges[i] == null ? 0 : 3 + 9 + font.width(Integer.toString(badges[i].count())));
			dots += base + (badges[i] == null ? 0 : 3 + 7);
		}
		full -= 2;
		dots -= 2;
		tabsNeeded = full;
		tabsAvailable = w;
		tabsCompact = full > w;
		tabsOverflow = dots > w;
		int squeeze = tabsOverflow ? (int) Math.ceil((dots - w) / (double) all.length) : 0;
		int x = x0;
		for (int i = 0; i < all.length; i++) {
			HubTab t = all[i];
			boolean active = t == tab;
			TabBadges.Badge b = badges[i];
			String label = squeeze > 0 ? TextUtil.ellipsize(font, t.label, Math.max(12, font.width(t.label) - squeeze)) : t.label;
			int lw = font.width(label);
			int bw = b == null ? 0 : tabsCompact ? 3 + 7 : 3 + 9 + font.width(Integer.toString(b.count()));
			int tw = lw + 16 + bw;
			int ty = active ? y : y + 2;
			Panels.sprite(g, active ? Kit.TAB_ACTIVE : Kit.TAB_INACTIVE, x, ty, tw, active ? TAB_H : TAB_H - 2);
			int color = active ? UiBits.ink() : t.built ? UiBits.muted() : UiStyle.color("paper.disabled", 0xFFA39B8E);
			g.text(font, label, x + 8, y + 7, color, false);
			if (b != null) {
				int bx = x + 8 + lw + 3;
				Panels.sprite(g, Kit.dot(b.family(), false), bx, y + 8, 7, 7);
				if (!tabsCompact) {
					g.text(font, Integer.toString(b.count()), bx + 9, y + 7, UiStyle.status(b.family()), false);
				}
			}
			tabRects.add(new int[] {x, y, tw, TAB_H});
			x += tw + 2;
		}
	}

	/** dev.hub.state tabs: each tab's badge and the strip's layout. */
	com.google.gson.JsonObject tabsState() {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		com.google.gson.JsonObject bs = new com.google.gson.JsonObject();
		for (HubTab t : HubTab.values()) {
			TabBadges.Badge b = TabBadges.of(t);
			bs.addProperty(t.id, b == null ? 0 : b.count());
		}
		o.add("badges", bs);
		o.addProperty("needed", tabsNeeded);
		o.addProperty("available", tabsAvailable);
		o.addProperty("compact", tabsCompact);
		o.addProperty("overflow", tabsOverflow);
		return o;
	}

	Btn button(GuiGraphicsExtractor g, String id, String label, int x, int y, int w, boolean primary, boolean disabled, boolean danger,
		int mx, int my, Runnable action) {
		Btn b = new Btn(id, label, x, y, w, primary, disabled, danger, action);
		buttons.add(b);
		UiBits.ButtonState st = disabled ? UiBits.ButtonState.DISABLED : b.hit(mx, my) ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL;
		UiBits.button(g, font, label, 0, x, y, w, primary, st, danger);
		return b;
	}

	int bw(String label) {
		return UiBits.buttonWidth(font, label, 0);
	}

	// ------------------------------------------------------------------ Buildings tab

	private void drawBuildingsTab(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		List<Building> bs = buildings();
		List<Blueprint> bps = blueprints();
		List<Design> ds = designs();
		// sub switch (left) and Place new / Design new (right)
		int sx = x;
		for (Sub s : Sub.values()) {
			String label = switch (s) {
				case BUILDINGS -> "Buildings " + bs.size();
				case BLUEPRINTS -> "Blueprints " + bps.size();
				case DESIGNS -> "Designs " + ds.size() + (ds.stream().anyMatch(d -> d.status().isRunning()) ? " ●" : "");
			};
			int sw = bw(label);
			button(g, "sub:" + s.name().toLowerCase(Locale.ROOT), label, sx, y, sw, false, false, false, mx, my, () -> setSub(s));
			if (s == sub) {
				g.fill(sx + 4, y + 18, sx + sw - 4, y + 20, UiStyle.CLAY);
			}
			sx += sw + 4;
		}
		if (sub == Sub.DESIGNS) {
			String dn = "Design new…";
			button(g, "design_new", dn, x + w - bw(dn), y, bw(dn), true, HubFeature.designNew == null, false, mx, my, this::designNew);
		} else {
			String place = "Place new…";
			button(g, "place_new", place, x + w - bw(place), y, bw(place), true, minecraft.getSingleplayerServer() == null, false, mx, my,
				this::placeNew);
		}
		y += 26;
		h -= 26;
		switch (sub) {
			case BUILDINGS -> drawBuildings(g, bs, x, y, w, h, mx, my);
			case BLUEPRINTS -> drawBlueprints(g, bps, x, y, w, h, mx, my);
			case DESIGNS -> drawDesigns(g, ds, x, y, w, h, mx, my);
		}
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
		boolean compact = bw(full) > w;
		String label = compact ? "Walking: " + state : full;
		int needed = bw(label);
		button(g, "walk_toggle", label, x, y, Math.min(w, needed), on, false, false, mx, my, () -> walk.setEnabled(!walk.enabled()));
		// compact: the rest of the column says what it means, when there is room for it
		String note = on ? "agents walk outdoors" : "agents teleport";
		int nx = x + needed + 5;
		if (compact && x + w - nx >= font.width(note)) {
			g.text(font, note, nx, y + 6, UiBits.muted(), false);
		}
		walk.reportUi(needed, w, compact);
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

	@FunctionalInterface
	interface RowDrawer {
		/** Draws row {@code i}; returns its id. */
		String draw(int i, int x, int y, int w);
	}

	private void drawBuildings(GuiGraphicsExtractor g, List<Building> bs, int x, int y, int w, int h, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Building cur = currentBuilding(bs);
		if (bs.isEmpty()) {
			Panels.inset(g, x, y, w, h);
			int ty = y + 10;
			String also = Keys.build == null || Keys.build.isUnbound() ? "" : " (also " + Keys.label(Keys.build) + ")";
			for (String line : TextUtil.wrapPlain(font, "No buildings in this world yet. \"Place new…\" opens the building wizard" + also
				+ ": pick repos and a blueprint, then place its ghost. The Blueprints list shows what you can build.", w - 16)) {
				g.text(font, line, x + 8, ty, muted, false);
				ty += 10;
			}
			return;
		}
		int lw = Math.max(150, Math.min(220, w * 2 / 5));
		// under the list column: "Agents walk between buildings" (per world, W8)
		int toggleH = drawWalkToggle(g, x, y + h - 20, lw, mx, my);
		drawList(g, x, y, lw, h - toggleH, bs.size(), bs.indexOf(cur), mx, my, (i, rx, ry, rw) -> {
			Building b = bs.get(i);
			Blueprint bp = Blueprints.get(b.blueprint());
			String name = bp != null ? bp.name() : b.blueprint();
			String head = b.id() + "  " + name;
			int hw = 0;
			if (b.home()) {
				hw = UiBits.dotPillWidth(font, "home") + 2;
				UiBits.dotPill(g, font, "done", "home", rx + rw - hw + 2, ry - 1, UiBits.okText());
			}
			g.text(font, TextUtil.ellipsize(font, head, rw - hw - 2), rx, ry, ink, false);
			g.text(font, TextUtil.ellipsize(font, String.join(", ", b.repos()) + " · " + LeadsFeature.leadLabel(b), rw), rx, ry + 10, muted, false);
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
		g.text(font, TextUtil.ellipsize(font, cur.id() + " · " + (bp != null ? bp.name() : cur.blueprint()), dw), dx, dy, ink, false);
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
			labelW = Math.max(labelW, font.width(f[0]));
		}
		for (String[] f : facts) {
			if (f[1] == null) {
				continue;
			}
			g.text(font, f[0], dx, dy, f[0].equals("Check") ? UiBits.errorText() : muted, false);
			int vx = dx + labelW + 8;
			if (f[0].equals("Lead") && leadId != null) {
				ReviewKit.face(g, font, leadId, vx, dy - 1, 8);
				vx += 11;
			}
			for (String line : TextUtil.wrapPlain(font, f[1], dx + dw - vx)) {
				g.text(font, line, vx, dy, ink, false);
				dy += 10;
			}
			dy += 1;
		}
		dy += 6;
		boolean sp = minecraft.getSingleplayerServer() != null;
		String id = cur.id();
		int bx = dx;
		String home = "Make home";
		button(g, "home", home, bx, dy, bw(home), false, busy || cur.home() || !sp, false, mx, my, () -> makeHome(id));
		bx += bw(home) + 4;
		// C7: Teleport only with cheats on or in creative/spectator (a survival run walks)
		boolean tpAllowed = minecraft.player != null && HubActions.teleportAllowed(minecraft.player);
		if (tpAllowed) {
			String tp = "Teleport";
			button(g, "teleport", tp, bx, dy, bw(tp), false, busy || !sp, false, mx, my, () -> teleport(id));
			bx += bw(tp) + 4;
		}
		boolean armedHere = armed() && id.equals(armedRemove);
		boolean forceHere = forceArmed(id);
		String rm = forceHere ? "Remove anyway" : armedHere ? "Confirm remove" : "Remove…";
		int rmw = bw(rm);
		if (bx + rmw > dx + dw) {
			bx = dx;
			dy += 24;
		}
		button(g, "remove", rm, bx, dy, rmw, armedHere, busy || !sp, !armedHere || forceHere, mx, my, () -> removeClick(id));
		dy += 24;
		// change the building without re-placing it
		bx = dx;
		String er = "Edit repos…";
		button(g, "edit_repos", er, bx, dy, bw(er), false, busy || !sp || Blueprints.get(cur.blueprint()) == null, false, mx, my, () -> editRepos(id));
		bx += bw(er) + 4;
		String mvl = "Move…";
		button(g, "move", mvl, bx, dy, bw(mvl), false, busy || !sp || Blueprints.get(cur.blueprint()) == null, false, mx, my, () -> move(id));
		bx += bw(mvl) + 4;
		if (cur.movedFrom() != null) {
			String um = "Undo move";
			if (bx + bw(um) > dx + dw) {
				bx = dx;
				dy += 24;
			}
			button(g, "undo_move", um, bx, dy, bw(um), false, busy || !sp, false, mx, my, () -> undoMove(id));
		}
		dy += 26;
		String note;
		int noteColor = muted;
		HubActions.Result last = HubActions.last();
		if (forceHere) {
			long left = Math.max(0, (CONFIRM_MS - (System.currentTimeMillis() - armedAt) + 999) / 1000);
			HubActions.Result why = HubActions.last();
			note = (why != null ? why.message() + " " : "") + "Click Remove anyway to take " + id + " down regardless: those things are lost. (" + left + " s)";
			noteColor = UiBits.errorText();
		} else if (armedHere) {
			long left = Math.max(0, (CONFIRM_MS - (System.currentTimeMillis() - armedAt) + 999) / 1000);
			note = "Click Confirm remove to take " + id + " down: the terrain that was there comes back exactly. (" + left + " s)";
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
		for (String line : TextUtil.wrapPlain(font, note, dw)) {
			if (dy > y + h - 10) {
				break;
			}
			g.text(font, line, dx, dy, noteColor, false);
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
			for (String line : TextUtil.wrapPlain(font, minecraft.getSingleplayerServer() == null
				? "Blueprints are loaded by the integrated server: open a singleplayer world."
				: "No blueprints are loaded. Yours go in <game dir>/agentcraft/blueprints (<id>.nbt + <id>.blueprint.json).", w - 16)) {
				g.text(font, line, x + 8, ty, muted, false);
				ty += 10;
			}
			return;
		}
		int lw = Math.max(140, Math.min(190, w / 3));
		int selected = cur == null ? -1 : bps.indexOf(cur);
		drawList(g, x, y, lw, h, bps.size(), selected, mx, my, (i, rx, ry, rw) -> {
			Blueprint bp = bps.get(i);
			g.text(font, TextUtil.ellipsize(font, bp.name(), rw), rx, ry, ink, false);
			Blueprints.Entry e = Blueprints.entry(bp.id());
			String src = e != null && e.source().startsWith("user") ? "yours" : "bundled";
			g.text(font, TextUtil.ellipsize(font, kind(bp) + " · " + size(bp) + " · " + src, rw), rx, ry + 10, muted, false);
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
			int cw = font.width(label) + 10;
			boolean on = k.equals(shown);
			Panels.sprite(g, on ? Kit.TAB_ACTIVE : Kit.TAB_INACTIVE, chipX, y, cw, 13);
			g.text(font, label, chipX + 5, y + 3, on ? ink : muted, false);
			viewRects.add(new int[] {chipX, y, cw, 13});
			viewIds.add(k);
			chipX += cw + 2;
		}
		if (found.isEmpty()) {
			String none = "no rendered previews";
			if (chipX + 6 + font.width(none) <= dx + dw) {
				g.text(font, none, chipX + 6, y + 3, UiStyle.color("paper.disabled", 0xFFA39B8E), false);
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
				for (String line : TextUtil.wrapPlain(font, msg, imgW - 16)) {
					g.text(font, line, dx + (imgW - font.width(line)) / 2, ty, err != null ? UiBits.errorText() : muted, false);
					ty += 10;
				}
			}
		}
		int ty = iy + imgH + 4;
		g.text(font, TextUtil.ellipsize(font, cur.name() + "  ·  " + kind(cur) + "  ·  " + size(cur), dw), dx, ty, ink, false);
		ty += 11;
		// description: wrapped to the column; scrolls (wheel) when it does not fit above the buttons
		if (!cur.id().equals(descFor)) {
			descFor = cur.id();
			descScroll = 0;
		}
		List<String> desc = TextUtil.wrapPlain(font, cur.description().isEmpty() ? "(no description)" : cur.description(), dw - 8);
		int buttonsY = y + h - 20;
		int room = Math.max(1, (buttonsY - 4 - ty) / 10);
		descTotal = desc.size();
		descRows = Math.min(desc.size(), room);
		descScroll = Math.max(0, Math.min(descScroll, desc.size() - descRows));
		descX = dx;
		descY = ty;
		descW = dw;
		for (int r = 0; r < descRows; r++) {
			g.text(font, desc.get(descScroll + r), dx, ty + r * 10, muted, false);
		}
		if (desc.size() > descRows) {
			int trackH = descRows * 10;
			int thumbH = Math.max(6, trackH * descRows / desc.size());
			int sy = ty + (trackH - thumbH) * descScroll / Math.max(1, desc.size() - descRows);
			Panels.sprite(g, Kit.SCROLL_TRACK, dx + dw - 4, ty, 4, trackH);
			Panels.sprite(g, Kit.SCROLL_THUMB, dx + dw - 4, sy, 4, thumbH);
		}
		String id = cur.id();
		boolean sp = minecraft.getSingleplayerServer() != null;
		String pl = "Place…";
		int bx = dx + dw - bw(pl);
		button(g, "place", pl, bx, buttonsY, bw(pl), DesignFeature.plotForBlueprint(id) == null, !sp, false, mx, my, () -> placeBlueprint(id));
		if (DesignFeature.plotForBlueprint(id) != null) {
			String pp = "Place on the plot";
			bx -= 4 + bw(pp);
			button(g, "place_plot", pp, bx, buttonsY, bw(pp), true, !sp, false, mx, my, () -> placeOnPlot(id));
		}
		String dn = "Design new…";
		if (bx - 4 - bw(dn) >= dx) {
			button(g, "design_new", dn, bx - 4 - bw(dn), buttonsY, bw(dn), false, HubFeature.designNew == null, false, mx, my, this::designNew);
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
			for (String line : TextUtil.wrapPlain(font, msg, w - 16)) {
				g.text(font, line, x + 8, ty, muted, false);
				ty += 10;
			}
			return;
		}
		int lw = Math.max(150, Math.min(220, w * 2 / 5));
		Design sel = cur;
		drawList(g, x, y, lw, h, ds.size(), sel == null ? -1 : ds.indexOf(sel), mx, my, (i, rx, ry, rw) -> {
			Design d = ds.get(i);
			String pill = d.status().wire();
			int pw = UiBits.dotPillWidth(font, pill);
			UiBits.dotPill(g, font, family(d), pill, rx + rw - pw + 2, ry - 1, d.status() == Protocol.DesignStatus.DONE ? UiBits.okText() : muted);
			g.text(font, TextUtil.ellipsize(font, d.id() + "  " + title(d), rw - pw - 4), rx, ry, ink, false);
			g.text(font, TextUtil.ellipsize(font, d.step(), rw), rx, ry + 10, muted, false);
			return d.id();
		});
		int dx = x + lw + 10;
		int dw = w - lw - 10;
		if (cur == null) {
			g.text(font, TextUtil.ellipsize(font, "Waiting for " + selectedDesign + "…", dw), dx, y, muted, false);
			return;
		}
		Design d = cur;
		int dy = y;
		g.text(font, TextUtil.ellipsize(font, d.id() + " · " + title(d), dw), dx, dy, ink, false);
		dy += 13;
		int pw = UiBits.dotPill(g, font, family(d), d.status().wire(), dx, dy - 1, ink);
		for (String line : TextUtil.wrapPlain(font, d.step(), dw - pw - 6)) {
			g.text(font, line, dx + pw + 6, dy, muted, false);
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
			labelW = Math.max(labelW, font.width(f[0]));
		}
		for (String[] f : facts) {
			g.text(font, f[0], dx, dy, muted, false);
			g.text(font, TextUtil.ellipsize(font, f[1], dw - labelW - 8), dx + labelW + 8, dy, ink, false);
			dy += 11;
		}
		int buttonsY = y + h - 20;
		// notes (the request's) and the error, wrapped into what is left above the buttons
		int room = Math.max(0, (buttonsY - 4 - dy) / 10);
		List<String> extra = new ArrayList<>();
		int errFrom = -1;
		if (d.status() == Protocol.DesignStatus.FAILED && d.error() != null) {
			errFrom = 0;
			extra.addAll(TextUtil.wrapPlain(font, "Error: " + d.error().strip(), dw));
		}
		if (r.notes() != null && !r.notes().isBlank()) {
			extra.addAll(TextUtil.wrapPlain(font, "Notes: " + r.notes().strip().replace('\n', ' '), dw));
		}
		int errLines = errFrom < 0 ? 0 : TextUtil.wrapPlain(font, "Error: " + d.error().strip(), dw).size();
		for (int i = 0; i < Math.min(room, extra.size()); i++) {
			String line = extra.get(i);
			if (i == room - 1 && extra.size() > room) {
				line = TextUtil.ellipsize(font, line + " …", dw);
			}
			g.text(font, line, dx, dy, i < errLines ? UiBits.errorText() : muted, false);
			dy += 10;
		}
		// buttons
		boolean sp = minecraft.getSingleplayerServer() != null;
		int bx = dx + dw;
		String id = d.id();
		if (d.status().isRunning()) {
			String c = "Cancel design";
			bx -= bw(c);
			button(g, "cancel_design", c, bx, buttonsY, bw(c), false, !Foreman.connected(), true, mx, my, () -> cancelDesign(id));
		} else if (d.status() == Protocol.DesignStatus.DONE && d.blueprintId() != null && Blueprints.get(d.blueprintId()) != null) {
			String bp = d.blueprintId();
			boolean plot = DesignFeature.plotForBlueprint(bp) != null;
			String pl = "Place…";
			bx -= bw(pl);
			button(g, "place", pl, bx, buttonsY, bw(pl), !plot, !sp, false, mx, my, () -> placeBlueprint(bp));
			if (plot) {
				String pp = "Place on the plot";
				bx -= 4 + bw(pp);
				button(g, "place_plot", pp, bx, buttonsY, bw(pp), true, !sp, false, mx, my, () -> placeOnPlot(bp));
			}
			String show = "Show blueprint";
			if (bx - 4 - bw(show) >= dx) {
				bx -= 4 + bw(show);
				button(g, "show_blueprint", show, bx, buttonsY, bw(show), false, false, false, mx, my, () -> {
					selectBlueprint(bp);
					setView("iso");
				});
			}
		} else if (d.status() == Protocol.DesignStatus.DONE && d.blueprintId() != null && sp) {
			String rl = "Reload blueprints";
			bx -= bw(rl);
			button(g, "reload", rl, bx, buttonsY, bw(rl), true, false, false, mx, my, () -> DesignFeature.reloadAndSelect(d.blueprintId()));
		}
		if (designNote != null && id.equals(selectedDesign) && buttonsY - 12 > dy) {
			g.text(font, TextUtil.ellipsize(font, designNote, dw), dx, buttonsY - 12, designNoteError ? UiBits.errorText() : muted, false);
		}
	}

	static String kind(Blueprint bp) {
		return bp.isGroup() ? "group, " + bp.wings() + " wings" : "single";
	}

	static String size(Blueprint bp) {
		return String.format(Locale.ROOT, "%d×%d×%d", bp.sizeX(), bp.sizeY(), bp.sizeZ());
	}

	// ------------------------------------------------------------------ Status tab

	/** The Overview; returns the bottom of the taller column (StatusPane reports the layout). */
	int drawStatus(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		ForemanState s = Foreman.state();
		LinkStatus link = Foreman.link() != null ? Foreman.link().status() : null;
		ForemanStatus st = s == null ? null : s.status();
		int colW = (w - 12) / 2;
		int lx = x;
		int ly = y;
		ly = section(g, "Foreman", lx, ly, colW);
		String phase = link == null ? "?" : switch (link.phase()) {
			case SYNCED -> "connected";
			case CONNECTING -> "connecting…";
			case HANDSHAKE -> "handshake…";
			case WAITING_RETRY -> link.everSynced() ? "offline (retrying)" : "not connected (retrying)";
			case DISABLED -> "off (AGENTCRAFT_FOREMAN=0)";
		};
		String family = link == null ? "idle" : link.synced() ? "done" : link.phase() == LinkStatus.Phase.DISABLED ? "idle" : "waiting";
		Panels.dot(g, family, lx, ly + 1, false);
		g.text(font, TextUtil.ellipsize(font, phase + (link != null && link.synced() ? "" : link != null && link.attempt() > 0 ? " · attempt "
			+ link.attempt() : ""), colW - 10), lx + 10, ly, ink, false);
		ly += 11;
		ly = fact(g, "URL", link == null ? "?" : link.url(), lx, ly, colW);
		if (link != null && link.lastError() != null && !link.synced()) {
			ly = fact(g, "Last error", link.lastError(), lx, ly, colW);
		}
		if (link != null) {
			ly = fact(g, "Since", UiBits.ago(link.sinceMs()), lx, ly, colW);
		}
		if (s != null && s.isStale()) {
			ly = fact(g, "State", "last known (Foreman offline)", lx, ly, colW);
		}
		ly += 6;
		ly = section(g, "Backend and account", lx, ly, colW);
		if (st == null) {
			ly = fact(g, "", "No status from the Foreman yet.", lx, ly, colW);
		} else {
			ly = fact(g, "Backend", st.backend().wire(), lx, ly, colW);
			ly = fact(g, "Auth", st.auth().wire(), lx, ly, colW);
			ly = fact(g, "Account", st.account() == null ? "—" : st.account(), lx, ly, colW);
			if (st.userName() != null) {
				ly = fact(g, "You", st.userName(), lx, ly, colW);
			}
			if (st.message() != null && !st.message().isBlank()) {
				ly = fact(g, "Note", st.message(), lx, ly, colW);
			}
		}
		// right column
		int rx = x + colW + 12;
		int ry = y;
		ry = section(g, "Plan usage", rx, ry, colW);
		ry += drawUsage(g, rx, ry, colW);
		if (st != null && st.costUsd() != null && st.costUsd() > 0) {
			ry = fact(g, "Spend", String.format(Locale.ROOT, "$%.2f (estimated, this profile)", st.costUsd()), rx, ry, colW);
		}
		ry += 6;
		ry = section(g, "Versions", rx, ry, colW);
		ry = fact(g, "Mod", modVersion(), rx, ry, colW);
		ry = fact(g, "Foreman", st == null ? "?" : st.version(), rx, ry, colW);
		ry += 6;
		ry = section(g, "DevBridge", rx, ry, colW);
		ry = fact(g, "", DevBridge.status(), rx, ry, colW);
		return Math.max(ly, ry);
	}

	/** The plan usage windows (bars, percent, reset time), as on the Status tab; returns the height. Also the Settings tab's Usage group. */
	int drawUsage(GuiGraphicsExtractor g, int rx, int ry, int colW) {
		int y0 = ry;
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		ForemanState s = Foreman.state();
		ForemanStatus st = s == null ? null : s.status();
		List<UsageWindow> windows = st == null || st.usage() == null ? List.of() : st.usage().windows();
		if (windows.isEmpty()) {
			for (String line : TextUtil.wrapPlain(font, st == null ? "Unknown until the Foreman reports."
				: "Not reported (the plan reports usage with a claude.ai login).", colW)) {
				g.text(font, line, rx, ry, muted, false);
				ry += 10;
			}
		} else {
			for (UsageWindow uw : windows) {
				String pct = Math.round(uw.pct()) + "%";
				g.text(font, TextUtil.ellipsize(font, uw.label(), colW - font.width(pct) - 6), rx, ry, ink, false);
				g.text(font, pct, rx + colW - font.width(pct), ry, uw.pct() >= 90 ? UiBits.errorText() : ink, false);
				ry += 10;
				Panels.progress(g, rx, ry, colW, uw.pct() / 100.0, uw.pct() >= 90 ? "red" : uw.pct() >= 70 ? "brass" : "sage");
				ry += 8;
				if (uw.resetsAt() != null) {
					g.text(font, "resets " + RESETS.format(Instant.ofEpochMilli(uw.resetsAt()).atZone(ZoneId.systemDefault())), rx, ry, muted, false);
					ry += 10;
				}
				ry += 3;
			}
			if (st.usage().updatedAt() > 0) {
				g.text(font, "updated " + UiBits.ago(st.usage().updatedAt()), rx, ry, muted, false);
				ry += 10;
			}
		}
		return ry - y0;
	}

	static String modVersion() {
		return FabricLoader.getInstance().getModContainer(AgentCraft.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	int section(GuiGraphicsExtractor g, String title, int x, int y, int w) {
		g.text(font, title, x, y, UiStyle.CLAY_DARK, false);
		Panels.divider(g, x, y + 10, w);
		return y + 16;
	}

	int fact(GuiGraphicsExtractor g, String label, @Nullable String value, int x, int y, int w) {
		int lw = label.isEmpty() ? 0 : 58;
		if (!label.isEmpty()) {
			g.text(font, label, x, y, UiBits.muted(), false);
		}
		for (String line : TextUtil.wrapPlain(font, value == null ? "—" : value, w - lw)) {
			g.text(font, line, x + lw, y, UiBits.ink(), false);
			y += 10;
		}
		return y + 1;
	}

	// ------------------------------------------------------------------ coming next

	private void drawComingNext(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		int bw = Math.min(w, 360);
		int bx = x + (w - bw) / 2;
		int lines = 0;
		List<List<String>> wrapped = new ArrayList<>();
		for (String item : tab.comingNext) {
			List<String> l = TextUtil.wrapPlain(font, item, bw - 30);
			wrapped.add(l);
			lines += l.size();
		}
		int bh = 34 + lines * 10 + wrapped.size() * 4 + 8;
		int by = y + Math.max(0, (h - bh) / 3);
		Panels.inset(g, bx, by, bw, bh);
		g.text(font, tab.label + ": coming next", bx + 10, by + 9, UiBits.ink(), false);
		Panels.divider(g, bx + 10, by + 20, bw - 20);
		int ty = by + 28;
		for (List<String> item : wrapped) {
			Panels.dot(g, "idle", bx + 12, ty + 1, false);
			for (String line : item) {
				g.text(font, line, bx + 24, ty, UiBits.muted(), false);
				ty += 10;
			}
			ty += 4;
		}
	}

	// ------------------------------------------------------------------ DevBridge

	/** Button ids drawn last frame with their state (DevBridge: what is on screen). */
	List<String[]> buttonsShown() {
		List<String[]> out = new ArrayList<>();
		for (Btn b : buttons) {
			out.add(new String[] {b.id(), b.label(), b.disabled() ? "disabled" : b.primary() ? "primary" : "normal"});
		}
		return out;
	}

	int descriptionRows() {
		return descRows;
	}

	int descriptionLines() {
		return descTotal;
	}
}
