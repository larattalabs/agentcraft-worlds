package dev.agentcraft.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.Cast;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hub.SecretSettings;
import dev.agentcraft.hub.SettingDef;
import dev.agentcraft.hub.SettingsLogic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The one form renderer behind the hub's Team tab, Settings tab and a repo's "Edit settings" (docs/HUB.md
 * "Team and Settings tabs"): rows generated from {@link SettingDef}s by type (bool checkbox, enum / model /
 * effort chips, int stepper with a typed value, string field, string list one per line, agent list toggle
 * chips, a repo role picker, secret maps (a repository's env: names with "(set)", values write-only) and the MCP
 * servers list with add / edit / remove (wave 3 S1/S2, {@link SecretSettings}), plain maps read-only), each with its source badge (file/flag/env/default), "restart"
 * when it is not live, "overridden by --flag" and its problem in red, in a scrolled area; plus the shared
 * chrome: the read-only banner (the Foreman did not accept the client token), the restart banner with
 * "Restart Foreman" (reconnecting state), the second confirm naming widening changes, and Apply / Revert.
 * Values are staged in the rows' {@link ConfigScope}s until Apply. Client thread.
 */
final class SettingsForm {
	/** One line of a form. */
	sealed interface Row permits Setting, Section, Text, Custom {
	}

	/** A setting of {@code scope} ({@code label} overrides the setting's own). */
	record Setting(ConfigScope scope, String key, @Nullable String label) implements Row {
	}

	record Section(String title) implements Row {
	}

	record Text(String text, boolean error) implements Row {
	}

	/** Something drawn by the caller in the scrolled area (usage windows): draw returns its height. */
	record Custom(String id, Drawer drawer) implements Row {
	}

	@FunctionalInterface
	interface Drawer {
		int draw(GuiGraphicsExtractor g, int x, int y, int w);
	}

	private record Hit(String id, int x, int y, int w, int h, boolean enabled, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	static final int CHIP_H = 14;
	private final HubScreen hub;
	private final String prefix;
	private final Map<String, HubField> fields = new HashMap<>();
	private final Map<HubField, Setting> fieldRows = new HashMap<>();
	private final List<HubField> drawnFields = new ArrayList<>();
	private @Nullable HubField focus;
	private final List<Hit> hits = new ArrayList<>();
	private int offset;
	private int content;
	private int[] area = new int[4];
	private boolean compact;
	private @Nullable String note;
	private boolean noteError;
	private final List<JsonObject> rowsState = new ArrayList<>();
	/** Helper fields of the secret editors (a variable's name and value, the MCP server form) -> their id. */
	private final Map<HubField, String> auxIds = new HashMap<>();
	/** What Enter does in a helper field (next field, Set, Done). */
	private final Map<HubField, Runnable> auxSubmit = new HashMap<>();
	/** The last problem of a secret editor (a bad variable name, a server that does not check), by editor id. */
	private final Map<String, String> auxProblems = new HashMap<>();
	/** The MCP server being added or edited (one at a time), null = none. */
	private @Nullable McpDraft draft;
	/** What Ctrl+Enter does (the owner's Apply). */
	private Runnable onCtrlEnter = () -> {
	};

	SettingsForm(HubScreen hub, String prefix) {
		this.hub = hub;
		this.prefix = prefix;
	}

	private Font font() {
		return hub.font();
	}

	void onCtrlEnter(Runnable r) {
		onCtrlEnter = r;
	}

	void compact(boolean c) {
		compact = c;
	}

	/** Called at the start of the owner's draw. */
	void begin() {
		hits.clear();
		drawnFields.clear();
		for (HubField f : fields.values()) {
			f.beginFrame();
		}
		area = new int[4];
		rowsState.clear();
	}

	void toTop() {
		offset = 0;
	}

	@Nullable String note() {
		return note;
	}

	void setNote(@Nullable String n, boolean err) {
		note = n;
		noteError = err;
	}

	// ------------------------------------------------------------------ the scrolled rows

	/** Draws {@code rows} in a scrolled area; returns the content height (all rows). */
	int draw(GuiGraphicsExtractor g, List<Row> rows, int x, int y, int w, int h, int mx, int my) {
		area = new int[] {x, y, w, h};
		int max = Math.max(0, content - h);
		offset = Math.max(0, Math.min(offset, max));
		int cw = w - 8;
		g.enableScissor(x, y, x + w, y + h);
		int ry = y - offset;
		for (Row r : rows) {
			ry += switch (r) {
				case Section s -> section(g, s.title(), x, ry, cw);
				case Text t -> text(g, t.text(), t.error() ? UiBits.errorText() : UiBits.muted(), x, ry, cw) + 4;
				case Custom c -> c.drawer().draw(g, x, ry, cw);
				case Setting s -> setting(g, s, x, ry, cw, mx, my);
			};
		}
		g.disableScissor();
		content = ry + offset - y;
		if (content > h) {
			TextUtil.Scroll sc = new TextUtil.Scroll().update(content, h);
			sc.scrollBy(-1_000_000);
			sc.scrollBy(offset);
			Panels.scrollbar(g, x + w - 6, y, h, sc, false);
		}
		if (focus != null && !drawnFields.contains(focus)) {
			setFocus(null);
		}
		return content;
	}

	int content() {
		return content;
	}

	private int section(GuiGraphicsExtractor g, String title, int x, int y, int w) {
		g.text(font(), title, x, y + 3, UiStyle.CLAY_DARK, false);
		Panels.divider(g, x, y + 13, w);
		return 18;
	}

	private int text(GuiGraphicsExtractor g, String s, int color, int x, int y, int w) {
		int h = 0;
		for (String line : TextUtil.wrapPlain(font(), s, w)) {
			g.text(font(), line, x, y + h, color, false);
			h += 10;
		}
		return h;
	}

	private boolean visible(int y, int h) {
		return area[2] > 0 && y + h > area[1] && y < area[1] + area[3];
	}

	private void hit(String id, int x, int y, int w, int h, boolean enabled, Runnable action) {
		if (visible(y, h)) {
			int y0 = Math.max(y, area[1]);
			int y1 = Math.min(y + h, area[1] + area[3]);
			hits.add(new Hit(id, x, y0, w, y1 - y0, enabled, action));
		}
	}

	/** A chip outside the scrolled area (group chips, roster toggles): not clipped. */
	int chip(GuiGraphicsExtractor g, String id, String label, int x, int y, boolean on, boolean enabled, int mx, int my, Runnable action) {
		int w = chipW(label);
		drawChip(g, label, x, y, w, on, enabled, mx, my);
		hits.add(new Hit(id, x, y, w, CHIP_H, enabled, action));
		return w;
	}

	private int rowChip(GuiGraphicsExtractor g, String id, String label, int x, int y, boolean on, boolean enabled, int mx, int my, Runnable action) {
		int w = chipW(label);
		drawChip(g, label, x, y, w, on, enabled, mx, my);
		hit(id, x, y, w, CHIP_H, enabled, action);
		return w;
	}

	private void drawChip(GuiGraphicsExtractor g, String label, int x, int y, int w, boolean on, boolean enabled, int mx, int my) {
		Panels.sprite(g, on ? Kit.TAB_ACTIVE : Kit.TAB_INACTIVE, x, y, w, CHIP_H, enabled ? 0xFFFFFFFF : 0x90FFFFFF);
		if (enabled && !on && mx >= x && mx < x + w && my >= y && my < y + CHIP_H) {
			g.fill(x + 1, y + 1, x + w - 1, y + CHIP_H - 1, 0x14000000);
		}
		int color = !enabled ? UiStyle.color("paper.disabled", 0xFFA39B8E) : on ? UiBits.ink() : UiBits.muted();
		g.text(font(), label, x + 6, y + 3, color, false);
	}

	int chipW(String label) {
		return font().width(label) + 12;
	}

	private String fieldId(Setting s) {
		return s.scope().id() + "|" + s.key();
	}

	private HubField field(Setting s, SettingDef d) {
		String id = fieldId(s);
		HubField f = fields.get(id);
		boolean multi = multiLine(d);
		if (f == null || f.multiLine != multi) {
			f = new HubField(prefix + ":" + s.key(), multi ? 8000 : 2000, multi, d.type().equals(SettingDef.STRING_LIST) ? "one per line"
				: d.type().equals(SettingDef.INT) ? "" : "not set");
			fields.put(id, f);
			f.set(SettingsLogic.text(s.scope().value(s.key())));
		}
		fieldRows.put(f, s);
		return f;
	}

	private static boolean multiLine(SettingDef d) {
		return d.type().equals(SettingDef.STRING_LIST) || d.type().equals(SettingDef.STRING) && (d.key().endsWith(".prompt") || d.key().endsWith(
			"Instructions") && !d.type().equals(SettingDef.BOOL));
	}

	/** Draws one setting; returns its height. */
	private int setting(GuiGraphicsExtractor g, Setting s, int x, int y, int w, int mx, int my) {
		ConfigScope scope = s.scope();
		SettingDef d = scope.def(s.key());
		int y0 = y;
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		if (d == null) {
			g.text(font(), TextUtil.ellipsize(font(), (s.label() != null ? s.label() : s.key()) + ": not reported by the Foreman", w), x, y, muted, false);
			return 14;
		}
		boolean editable = !d.readOnly() && scope.phase() == ConfigScope.Phase.READY && !scope.readOnly() && !scope.busy();
		JsonElement v = scope.value(s.key());
		boolean changed = scope.staged.has(s.key());
		String label = s.label() != null ? s.label() : d.label();
		// line 1: [checkbox] label .......... changed · restart · source
		int lx = x;
		String id = prefix + ":" + s.key();
		if (d.type().equals(SettingDef.BOOL)) {
			boolean on = v.isJsonPrimitive() && v.getAsBoolean();
			Panels.sprite(g, on ? Kit.CHECKBOX_CHECKED : Kit.CHECKBOX, x, y + 1, 10, 10, editable ? 0xFFFFFFFF : 0x90FFFFFF);
			hit(id, x, y, Math.min(w, 13 + font().width(label) + 4), 12, editable, () -> scope.set(s.key(), new JsonPrimitive(!on)));
			lx += 13;
		}
		int badgesW = badges(g, d, changed, x + w, y, false);
		g.text(font(), TextUtil.ellipsize(font(), label, Math.max(20, w - (lx - x) - badgesW - 6)), lx, y + 2, ink, false);
		badges(g, d, changed, x + w, y, true);
		y += 14;
		// the control
		switch (d.type()) {
			case SettingDef.BOOL -> {
			}
			case SettingDef.ENUM, SettingDef.MODEL, SettingDef.EFFORT -> {
				List<String> choices = SettingsLogic.choices(d);
				if (choices.isEmpty()) {
					y += textField(g, s, d, x, y, w, editable);
				} else {
					y += chips(g, id, choices, v.isJsonPrimitive() ? v.getAsString() : null, x, y, w, editable, mx, my, c -> scope.set(s.key(),
						new JsonPrimitive(c)), d.def().isJsonNull() && !d.type().equals(SettingDef.ENUM) && !choices.contains("default") ? () -> scope.set(
							s.key(), JsonNull.INSTANCE) : null);
				}
			}
			case SettingDef.INT -> {
				int cx = x;
				long cur = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsLong() : d.def().isJsonPrimitive() ? d.def().getAsLong() : 0;
				boolean canDown = editable && (d.min() == null || cur - 1 >= d.min());
				boolean canUp = editable && (d.max() == null || cur + 1 <= d.max());
				cx += rowChip(g, id + ":-", "−", cx, y, false, canDown, mx, my, () -> stepTo(s, d, cur - 1)) + 3;
				HubField f = field(s, d);
				sync(s, f);
				int fw = 56;
				f.draw(g, font(), cx, y, fw, 1, f == focus);
				drawn(f, editable, y);
				cx += fw + 3;
				cx += rowChip(g, id + ":+", "+", cx, y, false, canUp, mx, my, () -> stepTo(s, d, cur + 1)) + 6;
				String range = d.min() != null && d.max() != null ? SettingsLogic.format(d, new JsonPrimitive(d.min())) + "–" + SettingsLogic.format(d,
					new JsonPrimitive(d.max())) : d.min() != null ? "≥ " + SettingsLogic.format(d, new JsonPrimitive(d.min())) : d.max() != null ? "≤ "
						+ SettingsLogic.format(d, new JsonPrimitive(d.max())) : "";
				if (d.def().isJsonNull()) {
					cx += rowChip(g, id + ":unset", "not set", cx, y, v.isJsonNull(), editable, mx, my, () -> scope.set(s.key(), JsonNull.INSTANCE)) + 6;
				}
				if (!range.isEmpty() && cx + font().width(range) < x + w) {
					g.text(font(), range, cx, y + 3, muted, false);
				}
				y += CHIP_H + 3;
			}
			case SettingDef.AGENT_LIST -> {
				List<String> sel = SettingsLogic.strings(v);
				LinkedHashSet<String> all = new LinkedHashSet<>(sel);
				all.addAll(agentCandidates(d));
				int cx = x;
				int n = 0;
				for (String a : all) {
					boolean on = sel.contains(a);
					String lab = (on && orderMatters(d) ? sel.indexOf(a) + 1 + "·" : "") + UiBits.agentName(a);
					int cw = chipW(lab);
					if (cx > x && cx + cw > x + w) {
						cx = x;
						y += CHIP_H + 3;
					}
					cx += rowChip(g, id + ":" + a, lab, cx, y, on, editable && !(on && orderMatters(d) && a.equals("marlow")), mx, my, () -> {
						List<String> next = new ArrayList<>(SettingsLogic.strings(scope.value(s.key())));
						if (!next.remove(a)) {
							next.add(a);
						}
						scope.set(s.key(), SettingDef.strings(next));
					}) + 3;
					n++;
				}
				if (n == 0) {
					g.text(font(), "none", x, y + 3, muted, false);
				}
				y += CHIP_H + 3;
			}
			case SettingDef.SECRET_MAP -> y += secretMap(g, id, SecretSettings.keys(scope.current(s.key())), () -> scope.staged.value(s.key(), null),
				"env".equals(s.key()) && scope.repoId != null, x, y, w, editable, mx, my, p -> stageSecret(scope, s.key(), p));
			case SettingDef.MCP_SERVERS -> y += mcpServers(g, s, id, x, y, w, editable, mx, my);
			case SettingDef.STRING, SettingDef.STRING_LIST -> {
				if (scope.repoId != null && s.key().startsWith("roles.")) {
					y += rolePicker(g, s, d, x, y, w, editable, mx, my);
				} else {
					y += textField(g, s, d, x, y, w, editable);
				}
			}
			default -> {
				// map or an unknown type: read-only
				String shown = SettingsLogic.format(d, v);
				for (String line : TextUtil.wrapPlain(font(), shown + (d.readOnly() ? "  (read-only here)" : ""), w)) {
					g.text(font(), line, x, y, ink, false);
					y += 10;
				}
				y += 2;
			}
		}
		// help, override, problem
		if (!d.help().isBlank()) {
			if (compact) {
				g.text(font(), TextUtil.ellipsize(font(), d.help(), w), x, y, muted, false);
				// cut to one line here: the whole text as a tooltip on hover (426x240, the 4K auto scale)
				if (font().width(d.help()) > w && area != null && mx >= x && mx < x + w && my >= y - 1 && my < y + 9 && my >= area[1]
					&& my < area[1] + area[3]) {
					g.setTooltipForNextFrame(font(), font().split(net.minecraft.network.chat.Component.literal(d.help()), Math.max(120, w * 2 / 3)), mx, my);
				}
				y += 10;
			} else {
				y += text(g, d.help(), muted, x, y, w);
			}
		}
		String over = scope.overridden.get(s.key());
		if (over == null) {
			over = d.overriddenBy();
		}
		if (over != null) {
			y += text(g, "Overridden by " + over + ": the file's value applies once it is no longer given", UiStyle.CLAY_DARK, x, y, w);
		}
		String problem = scope.problem(s.key());
		if (problem != null) {
			y += text(g, UiBits.CROSS + " " + problem, UiBits.errorText(), x, y, w);
		}
		y += compact ? 4 : 7;
		JsonObject st = new JsonObject();
		st.addProperty("scope", scope.id());
		st.addProperty("key", s.key());
		st.addProperty("label", label);
		st.addProperty("type", d.type());
		st.add("value", d.secret() && changed ? SettingsLogic.masked(d, v) : v.deepCopy()); // secret values never echoed
		st.add("current", d.value().deepCopy());
		st.addProperty("staged", changed);
		st.addProperty("source", d.source());
		st.addProperty("live", d.live());
		st.addProperty("overriddenBy", over);
		st.addProperty("problem", problem);
		st.addProperty("editable", editable);
		st.addProperty("y", y0 - area[1] + offset);
		st.addProperty("visible", visible(y0, y - y0));
		rowsState.add(st);
		return y - y0;
	}

	/** Right-aligned badges ending at {@code right}; returns their width (draw=false: measure only). */
	private int badges(GuiGraphicsExtractor g, SettingDef d, boolean changed, int right, int y, boolean draw) {
		List<String[]> bs = new ArrayList<>(); // text, color
		if (changed) {
			bs.add(new String[] {"changed", Integer.toString(UiStyle.CLAY_DARK)});
		}
		if (!d.live()) {
			bs.add(new String[] {"restart", Integer.toString(UiBits.muted())});
		}
		bs.add(new String[] {d.source(), Integer.toString(d.source().equals("default") ? UiBits.muted() : UiBits.ink())});
		int w = 0;
		Kit.Padding p = Kit.padding("pill");
		for (String[] b : bs) {
			w += font().width(b[0]) + p.left() + p.right() + 3;
		}
		if (draw) {
			int bx = right - w;
			for (String[] b : bs) {
				bx += Panels.pill(g, font(), b[0], bx, y, Integer.parseInt(b[1])) + 3;
			}
		}
		return w;
	}

	private void stepTo(Setting s, SettingDef d, long n) {
		s.scope().set(s.key(), new JsonPrimitive(n));
		HubField f = fields.get(fieldId(s));
		if (f != null) {
			f.set(Long.toString(n));
		}
	}

	private int textField(GuiGraphicsExtractor g, Setting s, SettingDef d, int x, int y, int w, boolean editable) {
		HubField f = field(s, d);
		sync(s, f);
		int lines = f.multiLine ? (compact ? 3 : d.type().equals(SettingDef.STRING_LIST) ? 5 : 4) : 1;
		int h = f.draw(g, font(), x, y, w, lines, f == focus);
		drawn(f, editable, y);
		return h + 3;
	}

	private void drawn(HubField f, boolean editable, int y) {
		if (editable && visible(y, 12)) {
			drawnFields.add(f);
		}
	}

	/** An unfocused field shows the staged/current value (unless what was typed does not parse). */
	private void sync(Setting s, HubField f) {
		if (f == focus || s.scope().parseErrors.containsKey(s.key())) {
			return;
		}
		String want = SettingsLogic.text(s.scope().value(s.key()));
		if (!f.value().equals(want)) {
			f.set(want);
		}
	}

	/** Wrapping choice chips (plus "not set" when {@code unset} is given); returns the height. */
	private int chips(GuiGraphicsExtractor g, String id, List<String> choices, @Nullable String selected, int x, int y, int w, boolean editable, int mx,
		int my, java.util.function.Consumer<String> pick, @Nullable Runnable unset) {
		int y0 = y;
		int cx = x;
		List<String> all = new ArrayList<>(choices);
		if (selected != null && !all.contains(selected)) {
			all.add(selected); // a value the Foreman's options do not list (still shown as selected)
		}
		for (String c : all) {
			int cw = chipW(c);
			if (cx > x && cx + cw > x + w) {
				cx = x;
				y += CHIP_H + 3;
			}
			cx += rowChip(g, id + ":" + c, c, cx, y, c.equals(selected), editable, mx, my, () -> pick.accept(c)) + 3;
		}
		if (unset != null) {
			String lab = "not set";
			if (cx > x && cx + chipW(lab) > x + w) {
				cx = x;
				y += CHIP_H + 3;
			}
			rowChip(g, id + ":unset", lab, cx, y, selected == null, editable, mx, my, unset);
		}
		return y - y0 + CHIP_H + 3;
	}

	/**
	 * A repo's {@code roles.<agent>}: "not set" + the agent file ids (the setting's {@code options}, else the
	 * repo's {@code repo.agents}), else a text field.
	 */
	private int rolePicker(GuiGraphicsExtractor g, Setting s, SettingDef d, int x, int y, int w, boolean editable, int mx, int my) {
		ConfigScope scope = s.scope();
		List<Protocol.RepoAgentFile> files = scope.agents();
		List<String> ids = new ArrayList<>(d.options());
		if (ids.isEmpty() && files != null) {
			for (Protocol.RepoAgentFile f : files) {
				ids.add(f.id());
			}
		}
		if (ids.isEmpty()) {
			int h = textField(g, s, d, x, y, w, editable);
			String why = files == null ? scope.agentsLoading() ? "loading the repo's agent files…" : scope.agentsError() : scope.agentsError() != null
				? scope.agentsError() : "no .claude/agents files in this repo";
			if (why != null) {
				g.text(font(), TextUtil.ellipsize(font(), why, w), x, y + h, UiBits.muted(), false);
				h += 10;
			}
			return h;
		}
		JsonElement v = scope.value(s.key());
		return chips(g, prefix + ":" + s.key(), ids, SettingsLogic.isUnset(d, v) || !v.isJsonPrimitive() ? null : v.getAsString(), x, y, w, editable, mx,
			my, c -> scope.set(s.key(), new JsonPrimitive(c)), () -> scope.set(s.key(), SettingsLogic.unsetValue(d)));
	}

	/** Who an agent list may hold: its options, else the cast (leads for a leads list, workers otherwise). */
	static List<String> agentCandidates(SettingDef d) {
		if (!d.options().isEmpty()) {
			return d.options();
		}
		boolean leads = d.key().endsWith("leads");
		List<String> out = new ArrayList<>();
		for (Cast.Member m : Cast.members().values()) {
			if ("lead".equals(m.role()) == leads) {
				out.add(m.id());
			}
		}
		return out;
	}

	private static boolean orderMatters(SettingDef d) {
		return d.key().endsWith("leads");
	}

	// ------------------------------------------------------------------ secret settings (wave 3 S1/S2)

	/** A secret setting's update staged in its scope; an empty update stages nothing. */
	private static void stageSecret(ConfigScope scope, String key, JsonElement update) {
		boolean empty = update.isJsonObject() && update.getAsJsonObject().isEmpty() || update.isJsonArray() && update.getAsJsonArray().isEmpty();
		if (empty) {
			scope.unstage(key);
		} else {
			scope.set(key, update);
		}
	}

	/** A helper field of the secret editors (not bound to a setting: typing in it stages nothing). */
	private HubField aux(String id, boolean multi, String placeholder, int max) {
		String fid = "aux|" + id;
		HubField f = fields.get(fid);
		if (f == null || f.multiLine != multi) {
			f = new HubField(prefix + ":" + id, max, multi, placeholder);
			fields.put(fid, f);
		}
		auxIds.put(f, id);
		return f;
	}

	/** DevBridge: puts text into a helper field drawn last frame ({@code id} as {@code form.chips}/focus name it); false when there is none. */
	boolean setAux(String id, String text) {
		for (HubField f : drawnFields) {
			if (id.equals(auxIds.get(f))) {
				f.set(text);
				return true;
			}
		}
		return false;
	}

	/**
	 * A secret map: one line per variable (name, "(set)" / "new value" / "removed", Replace and Remove or Undo), then a
	 * name field, a value field and Set. Values are write-only: typed into the value field, staged on Set (the field
	 * clears) and never shown again. {@code update} supplies the staged partial update now; {@code stage} takes the next.
	 * Returns the height.
	 */
	private int secretMap(GuiGraphicsExtractor g, String id, List<String> current, java.util.function.Supplier<@Nullable JsonElement> update,
		boolean repo, int x, int y, int w, boolean editable, int mx, int my, java.util.function.Consumer<JsonObject> stage) {
		int y0 = y;
		int muted = UiBits.muted();
		List<SecretSettings.Var> vars = SecretSettings.vars(current, update.get());
		HubField nameF = aux(id + ":name", false, "NAME", 200);
		HubField valueF = aux(id + ":value", false, "value (write-only)", 20_000);
		if (vars.isEmpty()) {
			g.text(font(), "No variables.", x, y + 3, muted, false);
			y += CHIP_H + 2;
		}
		for (SecretSettings.Var v : vars) {
			String n = v.name();
			int cx = x + w;
			if (v.state() == SecretSettings.VarState.SET) {
				cx -= chipW("Remove");
				rowChip(g, id + ":remove:" + n, "Remove", cx, y, false, editable, mx, my, () -> stage.accept(SecretSettings.withVar(update.get(), current, n,
					null)));
				cx -= 3 + chipW("Replace");
				rowChip(g, id + ":replace:" + n, "Replace", cx, y, false, editable, mx, my, () -> {
					nameF.set(n);
					valueF.set("");
					setFocus(valueF);
				});
			} else {
				cx -= chipW("Undo");
				rowChip(g, id + ":undo:" + n, "Undo", cx, y, false, editable, mx, my, () -> stage.accept(SecretSettings.undo(update.get(), n)));
			}
			String st = switch (v.state()) {
				case SET -> SecretSettings.SET;
				case NEW -> "new value";
				case REPLACED -> "new value (replaces)";
				case REMOVED -> "removed";
			};
			int stColor = v.state() == SecretSettings.VarState.SET ? muted : v.state() == SecretSettings.VarState.REMOVED ? UiBits.errorText()
				: UiStyle.CLAY_DARK;
			int room = cx - x - 6;
			int stW = font().width(st) + 6;
			String nm = TextUtil.ellipsize(font(), n, Math.max(20, room - stW));
			if (visible(y, CHIP_H)) {
				g.text(font(), nm, x, y + 3, UiBits.ink(), false);
				if (font().width(nm) + stW <= room) {
					g.text(font(), st, x + font().width(nm) + 6, y + 3, stColor, false);
				}
			}
			y += CHIP_H + 2;
		}
		if (editable) {
			int setW = chipW("Set");
			int nw = Math.max(50, (w - setW - 6) * 2 / 5);
			int vw = w - nw - setW - 6;
			Runnable set = () -> {
				String n = nameF.value().strip();
				String why = SecretSettings.nameProblem(n, repo);
				if (why == null && valueF.value().isEmpty()) {
					why = (n.isEmpty() ? "the variable" : n) + ": type a value (Remove takes a variable out)";
				}
				if (why != null) {
					auxProblems.put(id, why);
					return;
				}
				stage.accept(SecretSettings.withVar(update.get(), current, n, valueF.value()));
				nameF.set("");
				valueF.set(""); // write-only: the value is not kept in the field
				auxProblems.remove(id);
			};
			auxSubmit.put(nameF, () -> setFocus(valueF));
			auxSubmit.put(valueF, set);
			int fh = nameF.draw(g, font(), x, y, nw, 1, nameF == focus);
			drawn(nameF, true, y);
			valueF.draw(g, font(), x + nw + 3, y, vw, 1, valueF == focus);
			drawn(valueF, true, y);
			rowChip(g, id + ":set", "Set", x + w - setW, y, false, true, mx, my, set);
			y += Math.max(fh, CHIP_H) + 3;
			String why = auxProblems.get(id);
			if (why != null) {
				y += text(g, UiBits.CROSS + " " + why, UiBits.errorText(), x, y, w);
			}
		}
		return y - y0;
	}

	/** The MCP server form's state: a new server or an edit of {@code original}. */
	private static final class McpDraft {
		final String scopeKey;
		final @Nullable String original;
		String type;
		final List<String> envKeys;
		@Nullable JsonObject env;

		McpDraft(String scopeKey, @Nullable String original, String type, List<String> envKeys, @Nullable JsonObject env) {
			this.scopeKey = scopeKey;
			this.original = original;
			this.type = type;
			this.envKeys = envKeys;
			this.env = env;
		}
	}

	/**
	 * The MCP servers: one entry per server (name, type, the command or URL, its variables; Edit, Remove or Undo), Add
	 * server, and while one is added or edited its form (name for a new one, type, command and arguments or URL, the
	 * environment as a secret map; Done stages it, Cancel drops it). Each server's change is one entry of the update
	 * ({@link SecretSettings#entry} / {@link SecretSettings#removal}). Returns the height.
	 */
	private int mcpServers(GuiGraphicsExtractor g, Setting s, String id, int x, int y, int w, boolean editable, int mx, int my) {
		int y0 = y;
		ConfigScope scope = s.scope();
		String key = s.key();
		int muted = UiBits.muted();
		java.util.function.Supplier<@Nullable JsonElement> stagedNow = () -> scope.staged.value(key, null);
		List<SecretSettings.Server> cur = SecretSettings.servers(scope.current(key));
		List<SecretSettings.Row> rows = SecretSettings.rows(cur, stagedNow.get());
		String scopeKey = scope.id() + "|" + key;
		if (rows.isEmpty()) {
			g.text(font(), "No MCP servers.", x, y + 3, muted, false);
			y += CHIP_H + 2;
		}
		for (SecretSettings.Row r : rows) {
			SecretSettings.Server sv = r.server();
			String n = sv.name();
			int cx = x + w;
			boolean changed = r.added() || r.edited() || r.removed();
			if (changed) {
				cx -= chipW("Undo");
				rowChip(g, id + ":undo:" + n, "Undo", cx, y, false, editable, mx, my, () -> {
					stageSecret(scope, key, SecretSettings.without(stagedNow.get(), n));
					if (draft != null && n.equals(draft.original)) {
						draft = null;
					}
				});
			}
			if (!r.removed()) {
				if (!r.added()) {
					if (!changed) {
						cx -= chipW("Remove");
						rowChip(g, id + ":remove:" + n, "Remove", cx, y, false, editable, mx, my, () -> stageSecret(scope, key, SecretSettings.withEntry(
							stagedNow.get(), SecretSettings.removal(n))));
					}
				}
				cx -= 3 + chipW("Edit");
				rowChip(g, id + ":edit:" + n, "Edit", cx, y, draft != null && n.equals(draft.original), editable, mx, my, () -> startDraft(scopeKey, r,
					cur));
			}
			String badge = r.added() ? "new" : r.edited() ? "changed" : r.removed() ? "removed" : "";
			int room = cx - x - 6;
			if (visible(y, CHIP_H)) {
				String head = n + "  " + sv.type();
				String hs = TextUtil.ellipsize(font(), head, Math.max(20, room - (badge.isEmpty() ? 0 : font().width(badge) + 6)));
				g.text(font(), hs, x, y + 3, r.removed() ? muted : UiBits.ink(), false);
				if (!badge.isEmpty() && font().width(hs) + font().width(badge) + 6 <= room) {
					g.text(font(), badge, x + font().width(hs) + 6, y + 3, r.removed() ? UiBits.errorText() : UiStyle.CLAY_DARK, false);
				}
			}
			y += CHIP_H + 1;
			String detail = sv.target() + (sv.envKeys().isEmpty() ? "" : "  ·  env: " + String.join(", ", sv.envKeys()));
			if (visible(y, 10)) {
				g.text(font(), TextUtil.ellipsize(font(), detail, w - 8), x + 8, y, muted, false);
			}
			y += 12;
		}
		if (draft == null || !draft.scopeKey.equals(scopeKey)) {
			rowChip(g, id + ":add", "Add server…", x, y, false, editable, mx, my, () -> {
				draft = new McpDraft(scopeKey, null, "stdio", List.of(), null);
				for (String f : List.of("name", "command", "args", "url")) {
					aux("mcp:" + f, f.equals("args"), "", 4000).set("");
				}
				auxProblems.remove("mcp");
			});
			return y + CHIP_H + 3 - y0;
		}
		y += 2;
		return y - y0 + mcpDraft(g, scope, key, stagedNow, cur, x, y, w, editable, mx, my);
	}

	/** Opens the form on a listed server (its staged change, if any, else as the Foreman shows it). */
	private void startDraft(String scopeKey, SecretSettings.Row r, List<SecretSettings.Server> cur) {
		SecretSettings.Server sv = r.server();
		List<String> envKeys = List.of(); // the variables it has now (an added server: none yet)
		for (SecretSettings.Server c : cur) {
			if (c.name().equals(sv.name())) {
				envKeys = c.envKeys();
			}
		}
		draft = new McpDraft(scopeKey, sv.name(), sv.type(), envKeys, r.envPatch() == null ? null : r.envPatch().deepCopy());
		aux("mcp:name", false, "", 64).set(sv.name());
		aux("mcp:command", false, "", 1000).set(sv.command() == null ? "" : sv.command());
		aux("mcp:args", true, "", 4000).set(String.join("\n", sv.args()));
		aux("mcp:url", false, "", 2000).set(sv.url() == null ? "" : sv.url());
		auxProblems.remove("mcp");
	}

	private int mcpDraft(GuiGraphicsExtractor g, ConfigScope scope, String key, java.util.function.Supplier<@Nullable JsonElement> stagedNow,
		List<SecretSettings.Server> cur, int x, int y, int w, boolean editable, int mx, int my) {
		McpDraft d = draft;
		int y0 = y;
		int muted = UiBits.muted();
		int labelW = font().width("Command") + 6;
		g.text(font(), d.original == null ? "New MCP server" : "Edit " + d.original, x, y + 1, UiStyle.CLAY_DARK, false);
		y += 12;
		HubField nameF = aux("mcp:name", false, "letters, digits, _ or -", 64);
		if (d.original == null) {
			g.text(font(), "Name", x, y + 3, muted, false);
			int fy = y;
			y += nameF.draw(g, font(), x + labelW, y, w - labelW, 1, nameF == focus) + 3;
			drawn(nameF, editable, fy);
		}
		g.text(font(), "Type", x, y + 3, muted, false);
		int cx = x + labelW;
		for (String t : SecretSettings.SERVER_TYPES) {
			cx += rowChip(g, "mcp:type:" + t, t, cx, y, t.equals(d.type), editable, mx, my, () -> d.type = t) + 3;
		}
		y += CHIP_H + 3;
		HubField commandF = aux("mcp:command", false, "e.g. npx -y @modelcontextprotocol/server-filesystem", 1000);
		HubField argsF = aux("mcp:args", true, "arguments, one per line", 4000);
		HubField urlF = aux("mcp:url", false, "https://host/path (no credentials, no query)", 2000);
		Runnable done = () -> commitDraft(scope, key, stagedNow, cur);
		auxSubmit.put(nameF, () -> setFocus("stdio".equals(d.type) ? commandF : urlF));
		auxSubmit.put(commandF, done);
		auxSubmit.put(urlF, done);
		if ("stdio".equals(d.type)) {
			g.text(font(), "Command", x, y + 3, muted, false);
			int fy = y;
			y += commandF.draw(g, font(), x + labelW, y, w - labelW, 1, commandF == focus) + 3;
			drawn(commandF, editable, fy);
			g.text(font(), "Args", x, y + 3, muted, false);
			fy = y;
			y += argsF.draw(g, font(), x + labelW, y, w - labelW, compact ? 2 : 3, argsF == focus) + 3;
			drawn(argsF, editable, fy);
			g.text(font(), "Environment (values are never shown)", x, y + 1, muted, false);
			y += 12;
			y += secretMap(g, "mcp:env", d.envKeys, () -> d.env, false, x, y, w, editable, mx, my, p -> d.env = p.isEmpty() ? null : p);
		} else {
			g.text(font(), "URL", x, y + 3, muted, false);
			int fy = y;
			y += urlF.draw(g, font(), x + labelW, y, w - labelW, 1, urlF == focus) + 3;
			drawn(urlF, editable, fy);
		}
		String why = auxProblems.get("mcp");
		if (why != null) {
			y += text(g, UiBits.CROSS + " " + why, UiBits.errorText(), x, y, w);
		}
		int bx = x;
		bx += rowChip(g, "mcp:done", d.original == null ? "Add" : "Done", bx, y, true, editable, mx, my, done) + 3;
		rowChip(g, "mcp:cancel", "Cancel", bx, y, false, true, mx, my, () -> {
			draft = null;
			auxProblems.remove("mcp");
		});
		y += CHIP_H + 3;
		return y - y0;
	}

	/** Done / Add: checks the form and stages the server's entry (an edit that changes nothing stages nothing). */
	private void commitDraft(ConfigScope scope, String key, java.util.function.Supplier<@Nullable JsonElement> stagedNow, List<SecretSettings.Server> cur) {
		McpDraft d = draft;
		if (d == null) {
			return;
		}
		String name = d.original != null ? d.original : aux("mcp:name", false, "", 64).value().strip();
		List<String> args = new ArrayList<>();
		for (String a : aux("mcp:args", true, "", 4000).value().split("\n", -1)) {
			if (!a.strip().isEmpty()) {
				args.add(a.strip());
			}
		}
		boolean stdio = "stdio".equals(d.type);
		SecretSettings.Server sv = new SecretSettings.Server(name, d.type, stdio ? aux("mcp:command", false, "", 1000).value().strip() : null, stdio ? args
			: List.of(), stdio ? null : aux("mcp:url", false, "", 2000).value().strip(), d.envKeys);
		List<String> others = new ArrayList<>();
		for (SecretSettings.Row r : SecretSettings.rows(cur, stagedNow.get())) {
			others.add(r.server().name());
		}
		JsonObject env = stdio ? d.env : null;
		String why = SecretSettings.serverProblem(sv, env, d.original == null, others);
		if (why == null && !stdio && d.env != null && !d.env.isEmpty()) {
			why = "env: only stdio servers have variables";
		}
		if (why != null) {
			auxProblems.put("mcp", why);
			return;
		}
		JsonElement staged = stagedNow.get();
		SecretSettings.Server was = null;
		for (SecretSettings.Server c : cur) {
			if (c.name().equals(name)) {
				was = c;
			}
		}
		boolean unchanged = was != null && (env == null || env.isEmpty()) && was.type().equals(sv.type()) && java.util.Objects.equals(was.command(),
			sv.command()) && was.args().equals(sv.args()) && java.util.Objects.equals(was.url(), sv.url());
		stageSecret(scope, key, unchanged ? SecretSettings.without(staged, name) : SecretSettings.withEntry(staged, SecretSettings.entry(sv, env)));
		draft = null;
		auxProblems.remove("mcp");
	}

	// ------------------------------------------------------------------ chrome

	/**
	 * The banners above a form: read-only connection, restart needed / restarting, a load error with Retry,
	 * the second confirm naming widening changes. Returns the height used.
	 */
	int banners(GuiGraphicsExtractor g, List<ConfigScope> scopes, int x, int y, int w, int mx, int my) {
		int y0 = y;
		ForemanState fs = Foreman.state();
		boolean readOnly = fs != null && fs.readOnly() || scopes.stream().anyMatch(ConfigScope::readOnly);
		if (readOnly) {
			y += banner(g, UiBits.CROSS + " " + Foreman.READ_ONLY + ": nothing can be changed from here. The mod reads the token named in the "
				+ "Foreman's run file (AGENTCRAFT_HOME / AGENTCRAFT_PROFILE); a reconnect reads it again.", true, x, y, w);
		}
		boolean restarting = HubConfig.restarting();
		List<String> rr = HubConfig.restartRequired();
		if (restarting || !rr.isEmpty()) {
			String rs = "Restart Foreman";
			int bw = hub.bw(rs);
			String msg;
			if (restarting) {
				var link = Foreman.link() == null ? null : Foreman.link().status();
				msg = "Restarting the Foreman… reconnecting" + (link != null && link.attempt() > 0 ? " (attempt " + link.attempt() + ")" : "")
					+ " · " + HubConfig.restartingForMs() / 1000 + " s";
			} else {
				msg = UiBits.plural(rr.size(), "setting waits", "settings wait") + " for a Foreman restart: " + String.join(", ", rr)
					+ ". Running turns are interrupted and resumed.";
			}
			int tw = w - bw - 6;
			List<String> lines = compact ? List.of(TextUtil.ellipsize(font(), msg, tw)) : TextUtil.wrapPlain(font(), msg, tw);
			lines = lines.size() > 3 ? lines.subList(0, 3) : lines;
			int bh = Math.max(20, lines.size() * 10 + 4);
			Panels.inset(g, x, y, w, bh);
			int ty = y + (bh - lines.size() * 10) / 2 + 1;
			for (String l : lines) {
				g.text(font(), l, x + 4, ty, restarting ? UiBits.muted() : UiStyle.CLAY_DARK, false);
				ty += 10;
			}
			hub.button(g, "foreman_restart", restarting ? "Restarting…" : rs, x + w - bw, y + (bh - 20) / 2, bw, !restarting, restarting
				|| !Foreman.connected() || readOnly, false, mx, my, this::restart);
			y += bh + 3;
		} else if (HubConfig.restartNote() != null && !compact) {
			g.text(font(), TextUtil.ellipsize(font(), HubConfig.restartNote(), w), x, y, HubConfig.restartNoteError() ? UiBits.errorText() : UiBits
				.muted(), false);
			y += 11;
		}
		List<ConfigScope> failed = new ArrayList<>();
		for (ConfigScope s : scopes) {
			if (s.phase() == ConfigScope.Phase.FAILED && !s.readOnly()) {
				failed.add(s);
			}
		}
		if (!failed.isEmpty()) {
			// one line (the first failure, e.g. the global settings), not one per repo
			ConfigScope s = failed.getFirst();
			String retry = "Retry";
			int bw = hub.bw(retry);
			String msg = (s.error() == null ? "Settings not loaded" : s.error()) + (failed.size() > 1 ? " (+" + (failed.size() - 1) + " more)" : "");
			g.text(font(), TextUtil.ellipsize(font(), msg, w - bw - 6), x, y + 6, UiBits.errorText(), false);
			hub.button(g, "settings_retry", retry, x + w - bw, y, bw, false, !Foreman.connected() || s.unsupported(), false, mx, my, () -> failed
				.forEach(ConfigScope::load));
			y += 23;
		}
		List<String> wide = new ArrayList<>();
		boolean asking = scopes.stream().anyMatch(s -> s.confirm() != null);
		if (asking) {
			wide = HubConfig.widenings(scopes);
		}
		if (asking && !wide.isEmpty()) {
			String ok = "Confirm and apply";
			String back = "Back";
			String msg = "These changes let agents do more. Apply them? " + String.join(" · ", wide);
			List<String> lines = TextUtil.wrapPlain(font(), msg, w - 8);
			int maxLines = compact ? 2 : 5;
			if (lines.size() > maxLines) {
				lines = new ArrayList<>(lines.subList(0, maxLines));
				lines.set(maxLines - 1, TextUtil.ellipsize(font(), lines.get(maxLines - 1) + "…", w - 8));
			}
			int bh = lines.size() * 10 + 28;
			Panels.inset(g, x, y, w, bh);
			int ty = y + 4;
			for (String l : lines) {
				g.text(font(), l, x + 4, ty, UiBits.errorText(), false);
				ty += 10;
			}
			int bx = x + 4;
			hub.button(g, "settings_confirm", ok, bx, ty + 2, hub.bw(ok), false, scopes.stream().anyMatch(ConfigScope::busy), true, mx, my,
				() -> apply(scopes, true));
			hub.button(g, "settings_confirm_back", back, bx + hub.bw(ok) + 4, ty + 2, hub.bw(back), false, false, false, mx, my, () -> scopes.forEach(
				ConfigScope::cancelConfirm));
			y += bh + 3;
		}
		return y - y0;
	}

	private int banner(GuiGraphicsExtractor g, String msg, boolean error, int x, int y, int w) {
		List<String> lines = compact ? List.of(TextUtil.ellipsize(font(), msg, w - 8)) : TextUtil.wrapPlain(font(), msg, w - 8);
		lines = lines.size() > 3 ? lines.subList(0, 3) : lines;
		int bh = lines.size() * 10 + 6;
		Panels.inset(g, x, y, w, bh);
		int ty = y + 4;
		for (String l : lines) {
			g.text(font(), l, x + 4, ty, error ? UiBits.errorText() : UiBits.muted(), false);
			ty += 10;
		}
		return bh + 3;
	}

	/** Apply / Revert and the last note, in one row at {@code y}. */
	void actions(GuiGraphicsExtractor g, List<ConfigScope> scopes, int x, int y, int w, int mx, int my) {
		int n = 0;
		boolean problems = false;
		boolean busy = false;
		boolean ready = false;
		boolean readOnly = Foreman.state() != null && Foreman.state().readOnly();
		for (ConfigScope s : scopes) {
			n += s.staged.size();
			problems |= !s.parseErrors.isEmpty();
			busy |= s.busy();
			ready |= s.phase() == ConfigScope.Phase.READY;
			readOnly |= s.readOnly();
		}
		String ap = n > 0 ? "Apply " + n : "Apply";
		int aw = hub.bw(ap);
		hub.button(g, "settings_apply", ap, x, y, aw, true, n == 0 || busy || !ready || readOnly || !Foreman.connected(), false, mx, my,
			() -> apply(scopes, false));
		String rv = "Revert";
		int rw = hub.bw(rv);
		boolean dirty = scopes.stream().anyMatch(ConfigScope::dirty);
		hub.button(g, "settings_revert", rv, x + aw + 4, y, rw, false, !dirty || busy, false, mx, my, () -> revert(scopes));
		String msg = note;
		boolean err = noteError;
		if (msg == null) {
			for (ConfigScope s : scopes) {
				if (s.note() != null) {
					msg = s.note();
					err = s.noteError();
				}
			}
		}
		if (msg == null && problems) {
			msg = "Some fields do not parse";
			err = true;
		}
		if (msg != null) {
			int tx = x + aw + rw + 12;
			g.text(font(), TextUtil.ellipsize(font(), msg, x + w - tx), tx, y + 6, err ? UiBits.errorText() : UiBits.muted(), false);
		}
	}

	CompletableFuture<HubGoals.Note> apply(List<ConfigScope> scopes, boolean confirmed) {
		setFocus(null);
		note = null;
		return HubConfig.applyAll(scopes, confirmed).thenApply(nt -> {
			setNote(nt.message(), !nt.ok());
			return nt;
		});
	}

	void revert(List<ConfigScope> scopes) {
		setFocus(null);
		for (ConfigScope s : scopes) {
			s.revert();
		}
		for (var e : fields.entrySet()) {
			Setting s = fieldRows.get(e.getValue());
			if (s != null) {
				e.getValue().set(SettingsLogic.text(s.scope().value(s.key())));
			}
		}
		draft = null;
		auxProblems.clear();
		for (var e : auxIds.entrySet()) {
			e.getKey().set("");
		}
		setNote("Reverted", false);
	}

	CompletableFuture<HubGoals.Note> restart() {
		setFocus(null);
		return HubConfig.restart().thenApply(nt -> {
			setNote(nt.message(), !nt.ok());
			return nt;
		});
	}

	// ------------------------------------------------------------------ input

	@Nullable HubField focus() {
		return focus;
	}

	@Nullable String focusKey() {
		Setting s = focus == null ? null : fieldRows.get(focus);
		return s != null ? s.key() : focus == null ? null : auxIds.get(focus);
	}

	void setFocus(@Nullable HubField f) {
		if (f == focus) {
			return;
		}
		focus = f;
		if (f != null) {
			f.model.touch();
		}
		hub.textFocus(f != null);
	}

	/** Focuses the field of {@code key} (DevBridge); false when it is not drawn. */
	boolean focusKey(String key) {
		for (HubField f : drawnFields) {
			Setting s = fieldRows.get(f);
			if (s != null && s.key().equals(key) || key.equals(auxIds.get(f))) {
				setFocus(f);
				return true;
			}
		}
		return false;
	}

	boolean keyPressed(KeyEvent e) {
		if (focus == null) {
			if (TextKeys.isEnter(e) && e.hasControlDown()) {
				onCtrlEnter.run();
				return true;
			}
			return false;
		}
		int k = e.key();
		if (e.isEscape()) {
			setFocus(null);
			return true;
		}
		if (k == InputConstants.KEY_TAB) {
			if (!drawnFields.isEmpty()) {
				int i = drawnFields.indexOf(focus);
				setFocus(drawnFields.get(Math.floorMod(i + (e.hasShiftDown() ? -1 : 1), drawnFields.size())));
			}
			return true;
		}
		if (TextKeys.isEnter(e)) {
			if (e.hasControlDown()) {
				commit(focus);
				onCtrlEnter.run();
			} else if (focus.multiLine) {
				focus.model.insert("\n");
				commit(focus);
			} else {
				Runnable submit = auxSubmit.get(focus);
				setFocus(null);
				if (submit != null) {
					submit.run();
				}
			}
			return true;
		}
		focus.key(font(), e);
		commit(focus);
		return true;
	}

	boolean charTyped(CharacterEvent e) {
		if (focus != null && e.codepoint() >= 32) {
			focus.model.insert(e.codepointAsString());
			commit(focus);
			return true;
		}
		return false;
	}

	private void commit(HubField f) {
		Setting s = fieldRows.get(f);
		if (s != null) {
			s.scope().setText(s.key(), f.value());
			note = null;
		}
	}

	/** A click on a chip, checkbox or field drawn last frame; true = consumed. */
	boolean mouseClicked(double x, double y) {
		for (Hit h : List.copyOf(hits)) {
			if (h.contains(x, y)) {
				if (h.enabled()) {
					setFocus(null);
					h.action().run();
				}
				return true;
			}
		}
		if (area[2] > 0 && x >= area[0] && x < area[0] + area[2] && y >= area[1] && y < area[1] + area[3]) {
			for (HubField f : drawnFields) {
				if (f.click(font(), x, y)) {
					setFocus(f);
					return true;
				}
			}
		}
		if (focus != null) {
			setFocus(null);
		}
		return false;
	}

	boolean mouseScrolled(double x, double y, int dir) {
		if (area[2] > 0 && x >= area[0] && x < area[0] + area[2] && y >= area[1] && y < area[1] + area[3]) {
			offset = Math.max(0, Math.min(Math.max(0, content - area[3]), offset + dir * 20));
			return true;
		}
		return false;
	}

	/** Presses a chip/checkbox drawn last frame (DevBridge "press"); false when there is none or it is disabled. */
	boolean press(String id) {
		for (Hit h : List.copyOf(hits)) {
			if (h.id().equals(id) && h.enabled()) {
				h.action().run();
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ DevBridge

	JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("focus", focusKey());
		o.addProperty("note", note);
		o.addProperty("noteError", noteError);
		JsonObject sc = new JsonObject();
		sc.addProperty("offset", offset);
		sc.addProperty("content", content);
		sc.addProperty("view", area[3]);
		o.add("scroll", sc);
		JsonArray rows = new JsonArray();
		rowsState.forEach(rows::add);
		o.add("rows", rows);
		JsonArray chips = new JsonArray();
		for (Hit h : hits) {
			JsonObject j = new JsonObject();
			j.addProperty("id", h.id());
			j.addProperty("enabled", h.enabled());
			chips.add(j);
		}
		o.add("chips", chips);
		return o;
	}
}
