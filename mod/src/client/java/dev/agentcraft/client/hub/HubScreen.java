package dev.agentcraft.client.hub;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
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
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The AgentCraft hub (docs/HUB.md "Hub screen"): one paper window with tabs for everything a player
 * sets or does in AgentCraft. The hub draws the frame, the tab strip with its badges, the buttons (hit
 * testing and the DevBridge's "press") and the footer hints; each tab is a {@link HubPane} with its own
 * state and input ({@link InboxTab}, {@link BuildingsTab}, {@link ReposTab}, {@link GoalsTab},
 * {@link TeamTab}, {@link SettingsTab}, {@link StatusPane}). The Buildings tab's public API stays here as
 * thin delegates (the DevBridge and other features call it).
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

	/** Buildings tab: the world's buildings, its fixtures (village boards, docs/VILLAGE.md V2), the blueprint browser, the building designs or the roads between buildings. */
	public enum Sub {
		BUILDINGS, FIXTURES, BLUEPRINTS, DESIGNS, ROADS
	}

	record Btn(String id, String label, int x, int y, int w, boolean primary, boolean disabled, boolean danger, Runnable action) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + 20;
		}
	}

	private HubTab tab;

	// per-frame layout (hit testing)
	private final List<Btn> buttons = new ArrayList<>();
	private final List<int[]> tabRects = new ArrayList<>();
	/** Buildings > Roads (docs/VILLAGE.md V1); before the Buildings tab, which draws it. */
	final RoadsView roadsView = new RoadsView(this);
	/** The Buildings tab (its five lists), a pane like the others. */
	final BuildingsTab buildingsTab = new BuildingsTab(this);
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
			case BUILDINGS -> buildingsTab;
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
			buildingsTab.tabChanged();
		}
	}

	// ------------------------------------------------------------------ the Buildings tab's API (BuildingsTab; DevBridge and features)

	public Sub sub() {
		return buildingsTab.sub();
	}

	public void setSub(Sub s) {
		buildingsTab.setSub(s);
	}

	public @Nullable String selectedBuilding() {
		return buildingsTab.selectedBuilding();
	}

	public @Nullable String selectedFixture() {
		return buildingsTab.selectedFixture();
	}

	/** The selection the list on show acts on: the fixture on the Fixtures list, else the building. */
	public @Nullable String selectedSite() {
		return buildingsTab.selectedSite();
	}

	public @Nullable String selectedBlueprint() {
		return buildingsTab.selectedBlueprint();
	}

	public @Nullable String selectedDesign() {
		return buildingsTab.selectedDesign();
	}

	/** Selects a design by id (kept even when the Foreman has not reported it yet); switches to the designs list. */
	public void selectDesign(String id) {
		buildingsTab.selectDesign(id);
	}

	/** The Foreman's designs, newest first. */
	static List<Design> designs() {
		return BuildingsTab.designs();
	}

	@Nullable String designNote() {
		return buildingsTab.designNote();
	}

	public @Nullable String armedRemove() {
		return buildingsTab.armedRemove();
	}

	public boolean busy() {
		return buildingsTab.busy();
	}

	public @Nullable String view() {
		return buildingsTab.view();
	}

	/** Selects a building (or a fixture) by id (false when there is none); switches to its list. */
	public boolean selectBuilding(String id) {
		return buildingsTab.selectBuilding(id);
	}

	/** Selects a blueprint by id (false when it is not loaded); switches to the blueprint browser. */
	public boolean selectBlueprint(String id) {
		return buildingsTab.selectBlueprint(id);
	}

	/** Shows a preview kind ("plan", "iso", "top", "front", "cutaway"); false when the selected blueprint has none. */
	public boolean setView(String kind) {
		return buildingsTab.setView(kind);
	}

	List<Building> buildings() {
		return buildingsTab.buildings();
	}

	static List<Building> fixtures() {
		return BuildingsTab.fixtures();
	}

	static List<Blueprint> blueprints() {
		return BuildingsTab.blueprints();
	}

	public java.util.concurrent.CompletableFuture<HubActions.Result> makeHome(String id) {
		return buildingsTab.makeHome(id);
	}

	public java.util.concurrent.CompletableFuture<HubActions.Result> teleport(String id) {
		return buildingsTab.teleport(id);
	}

	/** Remove: arms the confirm (null), or removes on the second click within {@link #CONFIRM_MS} ({@link BuildingsTab#removeClick}). */
	public java.util.concurrent.@Nullable CompletableFuture<HubActions.Result> removeClick(String id) {
		return buildingsTab.removeClick(id);
	}

	public boolean forceArmed(String id) {
		return buildingsTab.forceArmed(id);
	}

	public void editRepos(String id) {
		buildingsTab.editRepos(id);
	}

	public @Nullable String move(String id) {
		return buildingsTab.move(id);
	}

	public java.util.concurrent.CompletableFuture<HubActions.Result> undoMove(String id) {
		return buildingsTab.undoMove(id);
	}

	public void placeNew() {
		buildingsTab.placeNew();
	}

	public void placeBlueprint(String id) {
		buildingsTab.placeBlueprint(id);
	}

	public @Nullable String placeFixture(String blueprintId) {
		return buildingsTab.placeFixture(blueprintId);
	}

	public @Nullable String placeVillageBoard() {
		return buildingsTab.placeVillageBoard();
	}

	public java.util.concurrent.CompletableFuture<String> cancelDesign(String id) {
		return buildingsTab.cancelDesign(id);
	}

	public @Nullable String placeOnPlot(String blueprintId) {
		return buildingsTab.placeOnPlot(blueprintId);
	}

	public boolean designNew() {
		return buildingsTab.designNew();
	}

	/** A scrolled list of rows (Buildings tab lists, {@link RoadsView}); drawn by {@link BuildingsTab#drawList}. */
	void drawList(GuiGraphicsExtractor g, int x, int y, int w, int h, int count, int selected, int mx, int my, RowDrawer drawer) {
		buildingsTab.drawList(g, x, y, w, h, count, selected, mx, my, drawer);
	}

	@FunctionalInterface
	interface RowDrawer {
		/** Draws row {@code i}; returns its id. */
		String draw(int i, int x, int y, int w);
	}

	static String family(Design d) {
		return BuildingsTab.family(d);
	}

	static String title(Design d) {
		return BuildingsTab.title(d);
	}

	static String kind(Blueprint bp) {
		return BuildingsTab.kind(bp);
	}

	static String size(Blueprint bp) {
		return BuildingsTab.size(bp);
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
		return super.keyPressed(e);
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
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int d = scrollY > 0 ? -1 : 1;
		HubPane pane = pane();
		if (pane != null && pane.mouseScrolled(x, y, d)) {
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
		buildingsTab.beginFrame();
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
		int by = y;
		int bh = footerY - 4 - y;
		// a failing tab is logged once and counted (dev.state ui.guards "hub.draw"), never a crash of the game
		boolean drawn = dev.agentcraft.ui.Guard.call("hub.draw", () -> {
			switch (tab) {
				case BUILDINGS -> buildingsTab.draw(g, cx, by, cw, bh, mouseX, mouseY);
				case STATUS -> status.draw(g, cx, by, cw, bh, mouseX, mouseY);
				case REPOS -> repos.draw(g, cx, by, cw, bh, mouseX, mouseY);
				case GOALS -> goals.draw(g, cx, by, cw, bh, mouseX, mouseY);
				case TEAM -> team.draw(g, cx, by, cw, bh, mouseX, mouseY);
				case SETTINGS -> settings.draw(g, cx, by, cw, bh, mouseX, mouseY);
				case INBOX -> inbox.draw(g, cx, by, cw, bh, mouseX, mouseY);
				default -> drawComingNext(g, cx, by, cw, bh);
			}
			return true;
		}, false);
		if (!drawn) {
			g.text(font, TextUtil.ellipsize(font, UiBits.CROSS + " This tab failed to draw (see the game log)", cw), cx, by + 4, UiBits.errorText(), false);
		}
		HubPane hp = pane();
		String[] hints = hp != null ? hp.hints() : tab == HubTab.BUILDINGS ? new String[] {"Tab", "next tab", "←→", "switch list", "↑↓", "select",
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


	// ------------------------------------------------------------------ Status tab

	/** The Overview; returns the bottom of the taller column (StatusPane reports the layout). */
	/** The Status Overview's visible rows this frame (screen y), so buttons scrolled out of view are not clickable. */
	int statusClipTop = Integer.MIN_VALUE;
	int statusClipBottom = Integer.MAX_VALUE;

	int drawStatus(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
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
		ly = LauncherSection.draw(this, g, lx, ly, colW, mx, my);
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
		if (st != null) {
			Protocol.ForemanHold hold = st.hold();
			if (hold != null) {
				// the Inbox's hold item sends the player here ("Status"): say what holds the agents
				ry = fact(g, "Paused", dev.agentcraft.hud.AlertLine.holdText(hold.reason(), hold.until(), java.time.ZoneId.systemDefault(),
					System.currentTimeMillis(), false), rx, ry, colW); // one line: the Overview does not scroll (the message is in the Inbox item)
			}
		}
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
		return buildingsTab.descriptionRows();
	}

	int descriptionLines() {
		return buildingsTab.descriptionLines();
	}
}
