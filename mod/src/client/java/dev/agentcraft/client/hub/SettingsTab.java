package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.hub.SettingDef;
import dev.agentcraft.hub.SettingsLogic;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The hub's Settings tab (docs/HUB.md "Settings tab (mod)"): group chips General / Permissions / Context /
 * Subagents / PRs / Usage, each a form generated from the global {@code config.get} settings of that group (General
 * starts with the client-side HUD section, {@link HudSettingsView})
 * ({@link SettingsForm}; the Team tab's keys are left to the Team tab). Context edits the MCP servers (wave 3 S2: add,
 * edit, remove, their env as a secret map; restart-required); an older Foreman's plain map is listed read-only. Usage also shows the Status tab's plan usage windows read-only. Edits are
 * staged until Apply (one {@code config.set}); widening changes ask a second confirm; changes that need a
 * restart show the restart banner.
 */
final class SettingsTab implements HubPane {
	private final HubScreen hub;
	final SettingsForm form;
	private String group = SettingsLogic.GENERAL;
	private boolean compact;
	private int needed;
	private int available;
	/** General > HUD (client-side, hud.json). */
	final HudSettingsView hud;
	private int mouseX;
	private int mouseY;

	SettingsTab(HubScreen hub) {
		this.hub = hub;
		this.form = new SettingsForm(hub, "settings");
		this.hud = new HudSettingsView(form);
		form.onCtrlEnter(() -> form.apply(scopes(), false));
	}

	static List<ConfigScope> scopes() {
		return List.of(HubConfig.global());
	}

	String group() {
		return group;
	}

	/** Shows a group ({@link SettingsLogic#GROUPS}); false when it is not one. */
	boolean setGroup(String g) {
		String n = SettingsLogic.normalizeGroup(g);
		if (n == null || !SettingsLogic.GROUPS.contains(n)) {
			return false;
		}
		if (!n.equals(group)) {
			group = n;
			form.setFocus(null);
			form.toTop();
		}
		return true;
	}

	/** The rows of the shown group. */
	List<SettingsForm.Row> rows() {
		ConfigScope sc = HubConfig.global();
		List<SettingsForm.Row> rows = new ArrayList<>();
		if (group.equals(SettingsLogic.GENERAL)) {
			// the in-game overlay: client-side (hud.json), so it works with no Foreman and is never staged
			rows.add(new SettingsForm.Section("HUD (this game, applies at once)"));
			rows.add(new SettingsForm.Custom("hud", (g, x, y, w) -> hud.draw(g, hub.font(), x, y, w, mouseX, mouseY)));
			rows.add(new SettingsForm.Section("Foreman"));
		}
		if (group.equals(SettingsLogic.USAGE)) {
			rows.add(new SettingsForm.Section("Plan usage (read-only, as on the Status tab)"));
			rows.add(new SettingsForm.Custom("usage", (g, x, y, w) -> hub.drawUsage(g, x, y + 2, w) + 8));
		}
		if (sc.phase() != ConfigScope.Phase.READY) {
			if (sc.phase() == ConfigScope.Phase.LOADING || sc.phase() == ConfigScope.Phase.IDLE) {
				rows.add(new SettingsForm.Text(Foreman.connected() ? "Loading the settings…" : "The Foreman is not connected: settings load when it is.",
					false));
			}
			return rows;
		}
		boolean any = false;
		boolean editableMcp = false;
		for (SettingDef d : sc.view().settings()) {
			// MCP servers: the editor (type mcpServers) in the list; an older Foreman's read-only map goes to the section below
			boolean oldMcp = d.key().toLowerCase(Locale.ROOT).endsWith("mcpservers") && !SettingDef.MCP_SERVERS.equals(d.type());
			editableMcp |= SettingDef.MCP_SERVERS.equals(d.type());
			if (SettingsLogic.groupOf(d).equals(group) && !(group.equals(SettingsLogic.CONTEXT) && oldMcp)) {
				if (!any && group.equals(SettingsLogic.USAGE)) {
					rows.add(new SettingsForm.Section("Limits"));
				}
				rows.add(new SettingsForm.Setting(sc, d.key(), null));
				any = true;
			}
		}
		if (!any) {
			rows.add(new SettingsForm.Text("Nothing to set in " + SettingsLogic.groupLabel(group) + " (the Foreman lists none).", false));
		}
		if (group.equals(SettingsLogic.CONTEXT) && !editableMcp) {
			rows.add(new SettingsForm.Section("MCP servers (read-only: edit them in " + (sc.view().file() == null ? "config.json" : sc.view().file())
				+ ")"));
			if (sc.view().mcpServers().isEmpty()) {
				rows.add(new SettingsForm.Text("None configured.", false));
			}
			for (SettingDef.McpServer m : sc.view().mcpServers()) {
				rows.add(new SettingsForm.Text(m.name() + (m.command().isBlank() ? "" : "  ·  " + m.command()), false));
			}
		}
		return rows;
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		form.begin();
		mouseX = mx;
		mouseY = my;
		compact = w < 470 || h < 200;
		form.compact(compact);
		available = h;
		ConfigScope sc = HubConfig.global();
		sc.tick();
		int y0 = y;
		// group chips (+ the file on the right)
		int cx = x;
		for (String gr : SettingsLogic.GROUPS) {
			String label = SettingsLogic.groupLabel(gr);
			long staged = sc.staged.edits().keySet().stream().filter(k -> {
				SettingDef d = sc.def(k);
				return d != null && SettingsLogic.groupOf(d).equals(gr);
			}).count();
			if (staged > 0) {
				label += " •";
			}
			cx += form.chip(g, "group:" + gr, label, cx, y, gr.equals(group), true, mx, my, () -> setGroup(gr)) + 3;
		}
		if (!compact && sc.view().file() != null && cx + 40 < x + w) {
			String f = TextUtil.ellipsize(hub.font(), sc.view().file(), x + w - cx - 6);
			g.text(hub.font(), f, x + w - hub.font().width(f), y + 3, UiBits.muted(), false);
		}
		y += SettingsForm.CHIP_H + 5;
		y += form.banners(g, scopes(), x, y, w, mx, my);
		int actionsY = y0 + h - 20;
		int formH = actionsY - 4 - y;
		needed = y - y0 + 24 + 30; // fixed parts + at least a few rows of form
		if (formH > 12) {
			form.draw(g, rows(), x, y, w, formH, mx, my);
		}
		form.actions(g, scopes(), x, actionsY, w, mx, my);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		return form.keyPressed(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return form.charTyped(e);
	}

	@Override
	public boolean mouseClicked(double x, double y, boolean doubleClick) {
		return form.mouseClicked(x, y);
	}

	@Override
	public boolean mouseScrolled(double x, double y, int dir) {
		return form.mouseScrolled(x, y, dir);
	}

	@Override
	public @Nullable String focus() {
		return form.focusKey();
	}

	@Override
	public void unfocus() {
		form.setFocus(null);
	}

	@Override
	public void shown(boolean on) {
	}

	@Override
	public String[] hints() {
		if (form.focus() != null) {
			return new String[] {"Ctrl+Enter", "apply", "Tab", "next field", "Esc", "done typing"};
		}
		return new String[] {"Tab", "next tab", "Ctrl+Enter", "apply", "Esc", "close"};
	}

	@Override
	public JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("group", group);
		JsonArray gs = new JsonArray();
		SettingsLogic.GROUPS.forEach(gs::add);
		o.add("groups", gs);
		o.add("scope", HubConfig.global().state());
		o.add("form", form.state());
		o.add("config", HubConfig.state());
		o.add("layout", layout(hub, compact, needed, available));
		o.add("hud", hud.state());
		return o;
	}

	static JsonObject layout(HubScreen hub, boolean compact, int needed, int available) {
		JsonObject l = new JsonObject();
		l.addProperty("guiWidth", hub.width);
		l.addProperty("guiHeight", hub.height);
		l.addProperty("guiScale", hub.mc() == null ? 0 : hub.mc().getWindow().getGuiScale());
		l.addProperty("compact", compact);
		l.addProperty("needed", needed);
		l.addProperty("available", available);
		l.addProperty("overflow", needed > available);
		return l;
	}
}
