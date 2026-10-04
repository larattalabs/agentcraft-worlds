package dev.agentcraft.client.building;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.BlueprintTransform;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;

/**
 * Wizard step 2: pick a blueprint for the chosen repos: single blueprints for one repo, group
 * blueprints with at least as many wings as repos for several. Shows the name, kind, size and
 * description, and a top-down preview of the template (each column's highest block in its map colour,
 * shaded by height, turned so the entrance is at the bottom). Confirm closes the screen and starts
 * placement mode.
 */
final class BlueprintPickScreen extends WizardScreen {
	private static final int ROW = 22;
	private static final int PREVIEW = 116;
	/** Widest the detail column grows (the description wraps to it). */
	private static final int DETAIL_MAX = 200;

	private final List<String> repos;
	private final List<Blueprint> list = new ArrayList<>();
	private boolean initialized;
	private int selected;
	private int scroll;
	private int listY;
	private int listW;
	private int visibleRows;
	private @Nullable String error;
	/** Description rows shown, first row shown, and where the description is (the wheel scrolls it there). */
	private int descRows;
	private int descScroll;
	private @Nullable String descFor;
	private int descX;
	private int descY;
	private int descW;

	BlueprintPickScreen(List<String> repos) {
		super("New building: blueprint");
		this.repos = List.copyOf(repos);
	}

	@Override
	protected void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		int n = repos.size();
		for (Blueprint bp : Blueprints.all()) {
			if (n <= 1 ? !bp.isGroup() : bp.isGroup() && bp.wings() >= n) {
				list.add(bp);
			}
		}
	}

	List<String> repos() {
		return repos;
	}

	@Nullable Blueprint current() {
		return selected >= 0 && selected < list.size() ? list.get(selected) : null;
	}

	/** Selects a blueprint by id (DevBridge); false when it is not offered for these repos. */
	boolean select(String id) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).id().equals(id)) {
				selected = i;
				return true;
			}
		}
		return false;
	}

	/** The most wings any loaded group blueprint has (0 = none loaded). */
	static int maxWings() {
		int m = 0;
		for (Blueprint bp : Blueprints.all()) {
			if (bp.isGroup()) {
				m = Math.max(m, bp.wings());
			}
		}
		return m;
	}

	static int splitAt(int n, int maxWings, boolean anySingle) {
		return BlueprintTransform.splitAt(n, maxWings, anySingle);
	}

	/** Too few wings: no blueprint takes all the repos (null when one does). */
	@Nullable String tooFewWings() {
		if (!list.isEmpty() || repos.size() <= 1) {
			return null;
		}
		int m = maxWings();
		return "No blueprint has " + repos.size() + " wings" + (m > 0 ? " (the most is " + m + ")" : "") + ".";
	}

	/** Design new: the generator form for a group of this many wings (back here on Esc). */
	void designNew() {
		dev.agentcraft.client.design.DesignFeature.openFor(repos.size(), this);
	}

	/** Split: a building for the first part of the repos now, the rest in a second one afterwards. */
	void split() {
		int k = splitAt(repos.size(), maxWings(), Blueprints.all().stream().anyMatch(b -> !b.isGroup()));
		if (k <= 0) {
			return;
		}
		List<String> rest = repos.subList(k, repos.size());
		Toasts.push(new Notify(NotifyLevel.INFO, "Split: placing a building for " + String.join(", ", repos.subList(0, k))
			+ " now; then " + (rest.size() == 1 ? "one for " : "another for ") + String.join(", ", rest) + " (B or the hub's Place new)", null,
			System.currentTimeMillis()));
		minecraft.gui.setScreen(new BlueprintPickScreen(repos.subList(0, k)));
	}

	private void back() {
		minecraft.gui.setScreen(new RepoPickScreen(repos));
	}

	void place() {
		Blueprint bp = current();
		if (bp == null) {
			return;
		}
		try {
			BuildPlacement.start(bp.id(), repos);
		} catch (IllegalArgumentException e) {
			error = e.getMessage();
			Toasts.push(new Notify(NotifyLevel.WARN, e.getMessage(), null, System.currentTimeMillis()));
		}
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (e.isEscape()) {
			onClose();
			return true;
		}
		if (TextKeys.isEnter(e)) {
			place();
			return true;
		}
		if (k == InputConstants.KEY_BACKSPACE) {
			back();
			return true;
		}
		if (k == InputConstants.KEY_UP) {
			selected = Math.max(0, selected - 1);
			return true;
		}
		if (k == InputConstants.KEY_DOWN) {
			selected = Math.min(list.size() - 1, selected + 1);
			return true;
		}
		int digit = TextKeys.digit(e);
		if (digit > 0 && scroll + digit - 1 < list.size()) {
			selected = scroll + digit - 1;
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.y() >= listY && e.y() < listY + visibleRows * ROW && e.x() >= cx && e.x() < cx + listW) {
			int i = scroll + (int) ((e.y() - listY) / ROW);
			if (i < list.size()) {
				if (i == selected && doubleClick) {
					place();
				}
				selected = i;
			}
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (descRows > 0 && x >= descX && x < descX + descW && y >= descY && y < descY + descRows * 10) {
			descScroll = Math.max(0, descScroll + (scrollY > 0 ? -1 : 1));
			return true;
		}
		scroll = Math.max(0, Math.min(Math.max(0, list.size() - visibleRows), scroll + (scrollY > 0 ? -1 : 1)));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int muted = UiBits.muted();
		int ink = UiBits.ink();
		// at 426x240 (4K, auto GUI scale) a fixed 116 px preview pushed Place/Back below the screen: the preview shrinks first
		int maxBody = Math.max(100, height - 110);
		// the right column: preview, facts, description (wrapped to the column, scrolled when long)
		int panelW = Math.min(MAX_W, width - 24);
		int contentW = panelW - Kit.padding("panel_paper").left() - Kit.padding("panel_paper").right();
		int rw = Math.max(100, Math.min(DETAIL_MAX, contentW - 150));
		Blueprint cur = current();
		List<String> facts = cur == null ? List.of() : TextUtil.wrapPlain(font, "Entrance at the bottom \u2193 \u00b7 ground row " + cur.groundY(), rw);
		List<String> desc = cur == null ? List.of()
			: TextUtil.wrapPlain(font, cur.description().isEmpty() ? cur.id() : cur.description(), rw - 8);
		int factsH = facts.size() * 10 + 3;
		int pv = Math.max(48, Math.min(Math.min(PREVIEW, rw), maxBody - 18 - 4 - factsH - 20));
		int descRoom = Math.max(2, (maxBody - 18 - pv - 4 - factsH) / 10);
		descRows = Math.min(desc.size(), descRoom);
		descScroll = Math.max(0, Math.min(descScroll, desc.size() - descRows));
		int detailH = pv + 4 + factsH + descRows * 10;
		visibleRows = Math.max(1, Math.min(Math.max(1, list.size()), (maxBody - 30) / ROW));
		int bodyH = 14 + Math.max(detailH, visibleRows * ROW) + 14;
		int y = frame(g, "2/2  Choose a blueprint", bodyH);
		String forWhat = repos.size() == 1 ? "Single building for " + repos.get(0) : "Group building for " + String.join(", ", repos);
		g.text(font, TextUtil.ellipsize(font, forWhat, cw), cx, y, muted, false);
		y += 14;
		listW = Math.max(100, cw - rw - 12);
		listY = y;
		if (list.isEmpty()) {
			String few = tooFewWings();
			int k = splitAt(repos.size(), maxWings(), Blueprints.all().stream().anyMatch(b -> !b.isGroup()));
			String need = repos.size() == 1 ? "No single blueprint is loaded. /agentcraft blueprints lists them; yours go in <game dir>/agentcraft/blueprints."
				: few + " Design new… makes one with " + repos.size() + " wings" + (repos.size() > dev.agentcraft.building.DesignSpec.MAX_WINGS
					? " (at most " + dev.agentcraft.building.DesignSpec.MAX_WINGS + ")" : "")
				+ (k > 0 ? "; Split places a building for the first " + k + " now and one for the rest after." : ".");
			int ly = y + 2;
			for (String line : TextUtil.wrapPlain(font, need, listW)) {
				g.text(font, line, cx, ly, ly == y + 2 ? UiBits.errorText() : muted, false);
				ly += 10;
			}
		}
		if (selected < scroll) {
			scroll = selected;
		} else if (selected >= scroll + visibleRows) {
			scroll = selected - visibleRows + 1;
		}
		for (int r = 0; r < visibleRows && scroll + r < list.size(); r++) {
			int i = scroll + r;
			Blueprint bp = list.get(i);
			int ry = y + r * ROW;
			boolean hover = mouseX >= cx && mouseX < cx + listW && mouseY >= ry && mouseY < ry + ROW;
			if (i == selected) {
				g.fill(cx - 2, ry - 1, cx + listW, ry + ROW - 2, 0x30D97757);
			} else if (hover) {
				g.fill(cx - 2, ry - 1, cx + listW, ry + ROW - 2, 0x18000000);
			}
			g.text(font, TextUtil.ellipsize(font, (r < 9 ? (r + 1) + "  " : "") + bp.name(), listW - 4), cx, ry + 1, ink, false);
			String meta = (bp.isGroup() ? "group \u00b7 " + bp.wings() + " wings" : "single") + " \u00b7 " + size(bp);
			g.text(font, TextUtil.ellipsize(font, meta, listW - 4 - (r < 9 ? 12 : 0)), cx + (r < 9 ? 12 : 0), ry + 11, muted, false);
		}
		int pxl = cx + cw - rw;
		if (cur != null) {
			if (!cur.id().equals(descFor)) {
				descFor = cur.id();
				descScroll = 0;
			}
			int px0 = pxl + (rw - pv) / 2;
			Panels.inset(g, px0, y, pv, pv);
			BlueprintPreview.draw(g, cur.id(), px0 + 4, y + 4, pv - 8);
			int dy = y + pv + 4;
			for (String line : facts) {
				g.text(font, line, pxl, dy, muted, false);
				dy += 10;
			}
			dy += 3;
			descX = pxl;
			descY = dy;
			descW = rw;
			for (int r = 0; r < descRows; r++) {
				g.text(font, desc.get(descScroll + r), pxl, dy + r * 10, ink, false);
			}
			if (desc.size() > descRows) {
				// a thin scrollbar on the column's right edge (the wheel over the text scrolls it)
				int trackH = descRows * 10;
				int thumbH = Math.max(6, trackH * descRows / desc.size());
				int ty = dy + (trackH - thumbH) * descScroll / Math.max(1, desc.size() - descRows);
				Panels.sprite(g, Kit.SCROLL_TRACK, pxl + rw - 4, dy, 4, trackH);
				Panels.sprite(g, Kit.SCROLL_THUMB, pxl + rw - 4, ty, 4, thumbH);
			}
		} else {
			descRows = 0;
		}
		if (error != null) {
			g.text(font, TextUtil.ellipsize(font, error, cw), cx, y + Math.max(detailH, visibleRows * ROW) + 2, UiBits.errorText(), false);
		}
		if (tooFewWings() != null) {
			boolean canDesign = repos.size() <= dev.agentcraft.building.DesignSpec.MAX_WINGS;
			boolean canSplit = splitAt(repos.size(), maxWings(), Blueprints.all().stream().anyMatch(b -> !b.isGroup())) > 0;
			footer(g, mouseX, mouseY, new String[] {"Bksp", "back"}, btn("Design new\u2026", 84, true, !canDesign, this::designNew),
				btn("Split", 48, false, !canSplit, this::split), btn("\u2039 Back", 64, false, false, this::back));
			return;
		}
		footer(g, mouseX, mouseY, new String[] {"Enter", "place", "Bksp", "back"}, btn("Place \u203a", 72, true, cur == null, this::place),
			btn("\u2039 Back", 64, false, false, this::back));
	}

	/** Description lines in total for the selected blueprint (DevBridge: whether it all fits or scrolls). */
	int descriptionRowsShown() {
		return descRows;
	}

	private static String size(Blueprint bp) {
		return String.format(Locale.ROOT, "%d×%d×%d", bp.sizeX(), bp.sizeY(), bp.sizeZ());
	}
}
