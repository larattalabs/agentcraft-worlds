package dev.agentcraft.client.building;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.BlueprintTransform;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.console.TextFieldView;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.console.TextModel;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.TextUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;

/**
 * Wizard step 1: pick one repo (a single building) or several (a group building; wing n = the n-th
 * repo picked) from the Foreman's repos. Repos that already have a building are shown, disabled, with
 * the building's id. With the Foreman offline (or with Tab) the repo ids are typed instead.
 *
 * <p>With a fixed blueprint (the hub's "Place" on a blueprint) this is the only step: Next checks the
 * repo count against the blueprint (single: one repo; group: up to its wings) and goes straight to
 * placement mode.
 */
final class RepoPickScreen extends WizardScreen {
	private static final int ROW = 18;

	private record Row(String id, String name, @Nullable String branch, @Nullable String building) {
	}

	private final List<String> preselect;
	/** The blueprint chosen up front (hub), or null for the normal two-step wizard. */
	private final @Nullable String fixedBlueprint;
	/** Lock the ghost here after picking: {x, y, z, turns} (the hub's "Place on the plot"), or null. */
	private final int @Nullable [] lockAt;
	private @Nullable String error;
	/** Edit mode (the hub's "Edit repos…"): the building whose repos are being changed, null = a new building. */
	private @Nullable String editBuilding;
	/** Edit mode: what confirming does with the chosen repos, and where Esc goes. */
	private java.util.function.@Nullable Consumer<List<String>> onEdit;
	private net.minecraft.client.gui.screens.@Nullable Screen back;
	private final List<Row> rows = new ArrayList<>();
	/** Picked repo ids in pick order (= wing order). */
	private final List<String> picked = new ArrayList<>();
	private final TextModel typed = new TextModel(400);
	private final TextFieldView typedView = new TextFieldView();
	private boolean initialized;
	private boolean textMode;
	private int highlight;
	private int scroll;
	private int listY;
	private int visibleRows;
	private int fieldY;

	RepoPickScreen(List<String> preselect) {
		this(preselect, null);
	}

	RepoPickScreen(List<String> preselect, @Nullable String fixedBlueprint) {
		this(preselect, fixedBlueprint, null);
	}

	RepoPickScreen(List<String> preselect, @Nullable String fixedBlueprint, int @Nullable [] lockAt) {
		super("New building: repos");
		this.preselect = List.copyOf(preselect);
		this.fixedBlueprint = fixedBlueprint;
		this.lockAt = lockAt;
	}

	/**
	 * The repo step for changing building {@code id}'s repos (wing n = the n-th picked): its own repos are
	 * preselected and enabled, other buildings' repos disabled; Next hands the choice to {@code onEdit}.
	 */
	static RepoPickScreen forEdit(Building b, java.util.function.Consumer<List<String>> onEdit, net.minecraft.client.gui.screens.@Nullable Screen back) {
		RepoPickScreen s = new RepoPickScreen(b.repos(), b.blueprint(), null);
		s.editBuilding = b.id();
		s.onEdit = onEdit;
		s.back = back;
		return s;
	}

	@Nullable String editBuilding() {
		return editBuilding;
	}

	@Override
	public void onClose() {
		if (editBuilding != null && minecraft != null) {
			minecraft.gui.setScreen(back);
			return;
		}
		super.onClose();
	}

	int @Nullable [] lockAt() {
		return lockAt;
	}

	@Nullable String fixedBlueprint() {
		return fixedBlueprint;
	}

	@Nullable String error() {
		return error;
	}

	@Override
	protected void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		ForemanState s = Foreman.state();
		if (s != null) {
			s.repos().values().stream().sorted(Comparator.comparing(Repo::name, String.CASE_INSENSITIVE_ORDER)).forEach(r -> {
				Building b = Buildings.forRepo(r.id());
				boolean mine = b != null && b.id().equals(editBuilding);
				rows.add(new Row(r.id(), r.name(), r.branch(), b == null || mine ? null : b.id() + " (" + b.blueprint() + ")"));
			});
		}
		for (String id : preselect) {
			Row r = row(id);
			if (r != null && r.building() == null && !picked.contains(id)) {
				picked.add(id);
			}
		}
		setTextMode(rows.isEmpty());
		if (textMode && !preselect.isEmpty()) {
			typed.set(String.join(", ", preselect));
		}
	}

	@Override
	public void removed() {
		setTextMode(false);
		super.removed();
	}

	private @Nullable Row row(String id) {
		for (Row r : rows) {
			if (r.id().equals(id)) {
				return r;
			}
		}
		return null;
	}

	private void setTextMode(boolean on) {
		if (textMode != on && minecraft != null) {
			minecraft.onTextInputFocusChange(this, on);
		}
		textMode = on;
		typed.touch();
	}

	/** The repos chosen so far, in wing order. */
	List<String> chosen() {
		return textMode ? BlueprintTransform.parseRepos(typed.value()) : List.copyOf(picked);
	}

	boolean textMode() {
		return textMode;
	}

	private void toggle(int i) {
		error = null;
		if (i < 0 || i >= rows.size()) {
			return;
		}
		Row r = rows.get(i);
		if (r.building() != null) {
			return;
		}
		if (!picked.remove(r.id())) {
			picked.add(r.id());
		}
	}

	void next() {
		List<String> c = chosen();
		if (c.isEmpty()) {
			return;
		}
		if (fixedBlueprint == null) {
			minecraft.gui.setScreen(new BlueprintPickScreen(c));
			return;
		}
		Blueprint bp = Blueprints.get(fixedBlueprint);
		error = bp == null ? "Blueprint " + fixedBlueprint + " is no longer loaded" : fits(bp, c.size());
		if (error != null) {
			return;
		}
		if (editBuilding != null && onEdit != null) {
			onEdit.accept(c);
			minecraft.gui.setScreen(back);
			return;
		}
		try {
			BuildPlacement.start(bp.id(), c);
			if (lockAt != null) {
				BuildPlacement.lockAt(lockAt[0], lockAt[1], lockAt[2], lockAt[3]);
			}
		} catch (IllegalArgumentException e) {
			error = e.getMessage();
		}
	}

	/** Why {@code bp} cannot take {@code n} repos (null = it can): a single blueprint takes one, a group up to its wings. */
	static @Nullable String fits(Blueprint bp, int n) {
		if (!bp.isGroup() && n != 1) {
			return bp.name() + " is a single building: pick exactly one repo";
		}
		if (bp.isGroup() && n > bp.wings()) {
			return bp.name() + " has " + bp.wings() + " wings: pick at most " + bp.wings() + " repos";
		}
		return null;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (e.isEscape()) {
			onClose();
			return true;
		}
		if (TextKeys.isEnter(e)) {
			next();
			return true;
		}
		if (k == InputConstants.KEY_TAB) {
			if (!rows.isEmpty()) {
				setTextMode(!textMode);
				if (textMode && typed.isEmpty() && !picked.isEmpty()) {
					typed.set(String.join(", ", picked));
				}
			}
			return true;
		}
		if (textMode) {
			TextKeys.handle(e, typed);
			return true;
		}
		if (k == InputConstants.KEY_UP) {
			highlight = Math.max(0, highlight - 1);
			return true;
		}
		if (k == InputConstants.KEY_DOWN) {
			highlight = Math.min(rows.size() - 1, highlight + 1);
			return true;
		}
		if (k == InputConstants.KEY_SPACE) {
			toggle(highlight);
			return true;
		}
		int digit = TextKeys.digit(e);
		if (digit > 0) {
			toggle(scroll + digit - 1);
			highlight = Math.min(rows.size() - 1, scroll + digit - 1);
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (textMode && e.codepoint() >= 32) {
			typed.insert(e.codepointAsString());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (!textMode && e.y() >= listY && e.y() < listY + visibleRows * ROW && e.x() >= cx && e.x() < cx + cw) {
			int i = scroll + (int) ((e.y() - listY) / ROW);
			highlight = Math.min(rows.size() - 1, i);
			toggle(i);
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visibleRows), scroll + (scrollY > 0 ? -1 : 1)));
		return true;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		TextFieldView.Style fieldStyle = new TextFieldView.Style(null, 0, "repo ids, comma separated (wing order)", null, null, 0, 4);
		int maxBody = height - 110;
		visibleRows = Math.max(1, Math.min(rows.size(), (maxBody - 40) / ROW));
		int fieldH = textMode ? typedView.height(font, typed, Math.min(MAX_W, width - 24) - 24, fieldStyle) : 0;
		int bodyH = 22 + (textMode ? 14 + fieldH + 4 : visibleRows * ROW) + 14;
		Blueprint fixed = fixedBlueprint == null ? null : Blueprints.get(fixedBlueprint);
		int y = frame(g, editBuilding != null ? "Repos of " + editBuilding + (fixed != null ? " (" + fixed.name() + ", " + fixed.wings() + " wing"
			+ (fixed.wings() == 1 ? "" : "s") + ")" : "") : fixed != null ? "Repos for " + fixed.name() + (lockAt != null ? " (on the plot)" : "")
			: "1/2  Choose repos", bodyH);
		int muted = UiBits.muted();
		int ink = UiBits.ink();
		g.text(font, "One repo: a single building. Several: a group building,", cx, y, muted, false);
		g.text(font, "one wing per repo in the order you pick them.", cx, y + 10, muted, false);
		y += 22;
		if (textMode) {
			String why = rows.isEmpty() ? "The Foreman is offline (or has no repos): type the repo ids." : "Type repo ids (Tab: back to the list).";
			g.text(font, why, cx, y, muted, false);
			fieldY = y + 14;
			typedView.draw(g, font, typed, cx, fieldY, cw, true, fieldStyle);
			y = fieldY + fieldH + 4;
		} else {
			if (highlight < scroll) {
				scroll = highlight;
			} else if (highlight >= scroll + visibleRows) {
				scroll = highlight - visibleRows + 1;
			}
			listY = y;
			for (int r = 0; r < visibleRows && scroll + r < rows.size(); r++) {
				int i = scroll + r;
				Row row = rows.get(i);
				int ry = y + r * ROW;
				boolean disabled = row.building() != null;
				boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= ry && mouseY < ry + ROW;
				if (i == highlight || hover) {
					g.fill(cx - 2, ry - 1, cx + cw + 2, ry + ROW - 3, 0x20000000);
				}
				int wing = picked.indexOf(row.id());
				checkbox(g, cx, ry + 2, wing >= 0, disabled);
				String num = r < 9 ? Integer.toString(r + 1) : "";
				g.text(font, num, cx + 14, ry + 3, muted, false);
				int nx = cx + 24;
				String right = disabled ? "has " + row.building() : wing >= 0 ? "wing " + (wing + 1) : row.branch() == null ? "" : row.branch();
				int rw = font.width(right);
				String label = TextUtil.ellipsize(font, row.name() + (row.name().equals(row.id()) ? "" : "  " + row.id()), cw - 24 - rw - 8);
				g.text(font, label, nx, ry + 3, disabled ? muted : ink, false);
				g.text(font, right, cx + cw - rw, ry + 3, disabled ? muted : wing >= 0 ? UiBits.okText() : muted, false);
			}
			y += visibleRows * ROW;
		}
		List<String> c = chosen();
		String summary = c.isEmpty() ? "Nothing picked yet" : c.size() == 1 ? "Single building for " + c.get(0)
			: "Group building, " + c.size() + " wings: " + String.join(", ", c);
		if (fixed != null && !c.isEmpty()) {
			summary = fixed.name() + " for " + String.join(", ", c);
		}
		String problem = error != null ? error : fixed != null && !c.isEmpty() ? fits(fixed, c.size()) : null;
		g.text(font, TextUtil.ellipsize(font, problem != null ? problem : summary, cw), cx, y + 2,
			problem != null ? UiBits.errorText() : c.isEmpty() ? muted : ink, false);
		String[] hints = textMode ? new String[] {"Enter", "next", "Esc", "close"} : new String[] {"Space", "pick", "Tab", "type ids", "Enter", "next"};
		footer(g, mouseX, mouseY, hints, btn(fixed != null ? "Place \u203a" : "Next \u203a", 72, true, c.isEmpty(), this::next),
			btn("Cancel", 64, false, false, this::onClose));
	}
}
