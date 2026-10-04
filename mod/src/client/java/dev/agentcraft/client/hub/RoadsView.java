package dev.agentcraft.client.hub;

import com.google.gson.JsonObject;
import dev.agentcraft.building.Blueprint;
import dev.agentcraft.building.Blueprints;
import dev.agentcraft.building.Building;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.building.Road;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.road.RoadsFeature;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * The hub's Buildings > Roads view (docs/VILLAGE.md V1, docs/HUB.md): building pairs of the player's dimension with their
 * road route (length, status) and road, and the roads whose building is gone (offered for removal). A pair without a road
 * has the options (width 1-3, lanterns, bridges over shallow water) and <b>Lay road…</b>, which shows the road's ghost in
 * the world (Enter lays it, Esc cancels); a road has a two-step <b>Remove road…</b> that puts every cell back. Row ids:
 * {@code pair:<a>|<b>} and {@code road:<id>}. Button ids (dev.hub.action press): {@code road_width}, {@code road_lanterns},
 * {@code road_bridge}, {@code road_lay}, {@code road_plan}, {@code road_remove}, {@code road_keep}.
 */
final class RoadsView {
	private final HubScreen s;
	private @Nullable String selected;
	private @Nullable String armed;
	private long armedAt;
	private @Nullable String note;
	private boolean noteError;

	RoadsView(HubScreen s) {
		this.s = s;
	}

	/** A list row: a building pair or a road whose building is gone. */
	private record Row(String id, RoadsFeature.@Nullable Pair pair, @Nullable Road orphan) {
	}

	private static List<Row> rows() {
		List<Row> out = new ArrayList<>();
		for (Road r : RoadsFeature.orphans()) {
			out.add(new Row("road:" + r.id(), null, r));
		}
		for (RoadsFeature.Pair p : RoadsFeature.pairs()) {
			if (p.road() != null && out.stream().anyMatch(x -> x.orphan() != null && x.orphan().id().equals(p.road().id()))) {
				continue; // listed as offered for removal
			}
			out.add(new Row("pair:" + p.key(), p, null));
		}
		return out;
	}

	@Nullable String selected() {
		return selected;
	}

	/** Selects a row by id ({@code pair:b1|b2}, {@code road:r2}) and plans that pair's route when it has none yet. */
	boolean select(String id) {
		for (Row r : rows()) {
			if (r.id().equals(id)) {
				if (!id.equals(selected)) {
					armed = null;
					note = null;
				}
				selected = id;
				ensurePlanned(r);
				return true;
			}
		}
		return false;
	}

	void move(int d) {
		List<Row> rs = rows();
		if (rs.isEmpty()) {
			return;
		}
		int i = 0;
		for (int j = 0; j < rs.size(); j++) {
			if (rs.get(j).id().equals(selected)) {
				i = j;
			}
		}
		select(rs.get(Math.max(0, Math.min(rs.size() - 1, i + d))).id());
	}

	private static void ensurePlanned(Row r) {
		RoadsFeature.Pair p = r.pair();
		if (p != null && p.road() == null && RoadsFeature.route(p.a(), p.b()) == null) {
			RoadsFeature.plan(p.a(), p.b(), false);
		}
	}

	private boolean armed(String roadId) {
		return roadId.equals(armed) && System.currentTimeMillis() - armedAt < HubScreen.CONFIRM_MS;
	}

	/** Remove road, step one arms it, step two (within the confirm time) removes it. */
	void removeClick(String roadId) {
		if (armed(roadId)) {
			armed = null;
			note = "Removing " + roadId + "…";
			noteError = false;
			RoadsFeature.remove(roadId).thenAccept(r -> {
				note = r.message();
				noteError = !r.ok();
			});
			return;
		}
		armed = roadId;
		armedAt = System.currentTimeMillis();
	}

	void layClick(String a, String b) {
		note = "Planning the road…";
		noteError = false;
		RoadsFeature.startPreview(a, b, RoadsFeature.options(), true).handle((pv, t) -> {
			if (t != null) {
				Throwable c = t.getCause() != null ? t.getCause() : t;
				note = c.getMessage();
				noteError = true;
			} else {
				note = "Showing the road: Enter lays it, Esc cancels";
				noteError = false;
			}
			return null;
		});
	}

	private static String name(String buildingId) {
		Building b = Buildings.get(buildingId);
		if (b == null) {
			return buildingId + " (gone)";
		}
		Blueprint bp = Blueprints.get(b.blueprint());
		return buildingId + " " + (bp != null ? bp.name() : b.blueprint());
	}

	void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		Font font = s.font();
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		List<Row> rs = rows();
		if (rs.isEmpty()) {
			Panels.inset(g, x, y, w, h);
			int ty = y + 10;
			for (String line : TextUtil.wrapPlain(font, "Roads join two buildings of this dimension whose entrances are at most 256 blocks apart, along the "
				+ "route agents walk. Place a second building to lay one.", w - 16)) {
				g.text(font, line, x + 8, ty, muted, false);
				ty += 10;
			}
			RoadsFeature.reportUi(ty - y, h);
			return;
		}
		Row cur = null;
		for (Row r : rs) {
			if (r.id().equals(selected)) {
				cur = r;
			}
		}
		if (cur == null) {
			cur = rs.get(0);
			selected = cur.id();
			armed = null;
			ensurePlanned(cur);
		}
		int lw = Math.max(150, Math.min(220, w * 2 / 5));
		int selIndex = rs.indexOf(cur);
		s.drawList(g, x, y, lw, h, rs.size(), selIndex, mx, my, (i, rx, ry, rw) -> {
			Row r = rs.get(i);
			if (r.orphan() != null) {
				Road o = r.orphan();
				g.text(font, TextUtil.ellipsize(font, o.id() + "  " + o.a() + " ↔ " + o.b(), rw), rx, ry, ink, false);
				String why = RoadsFeature.offers().getOrDefault(o.id(), (Buildings.get(o.a()) == null ? o.a() : o.b()) + " is gone");
				g.text(font, TextUtil.ellipsize(font, "! " + why + ": remove it?", rw), rx, ry + 10, UiBits.errorText(), false);
				return r.id();
			}
			RoadsFeature.Pair p = r.pair();
			int pw = 0;
			if (p.road() != null) {
				pw = UiBits.dotPillWidth(font, p.road().id()) + 2;
				UiBits.dotPill(g, font, "done", p.road().id(), rx + rw - pw + 2, ry - 1, UiBits.okText());
			}
			g.text(font, TextUtil.ellipsize(font, p.a() + " ↔ " + p.b(), rw - pw - 2), rx, ry, ink, false);
			g.text(font, TextUtil.ellipsize(font, rowLine(p), rw), rx, ry + 10, muted, false);
			return r.id();
		});
		int dx = x + lw + 10;
		int dw = w - lw - 10;
		int dy = y;
		boolean sp = s.mc().getSingleplayerServer() != null;
		boolean busy = RoadsFeature.busy();
		if (cur.orphan() != null) {
			Road o = cur.orphan();
			g.text(font, TextUtil.ellipsize(font, "Road " + o.id() + ": " + o.a() + " ↔ " + o.b(), dw), dx, dy, ink, false);
			dy += 13;
			String why = RoadsFeature.offers().getOrDefault(o.id(), (Buildings.get(o.a()) == null ? o.a() : o.b()) + " is gone");
			dy = wrap(g, font, why + ": the road leads " + (why.endsWith("moved") ? "to the old site" : "nowhere") + ". Remove it, or keep it as a path.",
				dx, dy, dw, UiBits.errorText(), 3);
			dy = wrap(g, font, roadLine(o), dx, dy, dw, muted, 2) + 4;
			int bx = dx;
			String rm = armed(o.id()) ? "Confirm remove" : "Remove road…";
			s.button(g, "road_remove", rm, bx, dy, s.bw(rm), armed(o.id()), busy || !sp, true, mx, my, () -> removeClick(o.id()));
			bx += s.bw(rm) + 4;
			if (RoadsFeature.offers().containsKey(o.id())) {
				String keep = "Keep it";
				s.button(g, "road_keep", keep, bx, dy, s.bw(keep), false, busy, false, mx, my, () -> RoadsFeature.keep(o.id()));
			}
			dy += 24;
			dy = drawNote(g, font, dx, dy, dw, y + h, armed(o.id()) ? "Click Confirm remove: every cell the road changed comes back as it was "
				+ "(cells you changed since stay)." : null);
			RoadsFeature.reportUi(dy - y, h);
			return;
		}
		RoadsFeature.Pair p = cur.pair();
		g.text(font, TextUtil.ellipsize(font, name(p.a()) + " ↔ " + name(p.b()), dw), dx, dy, ink, false);
		dy += 13;
		Road road = p.road();
		if (road != null) {
			dy = wrap(g, font, roadLine(road), dx, dy, dw, ink, 2);
			for (String n : road.notes()) {
				dy = wrap(g, font, n, dx, dy, dw, muted, 1);
				break;
			}
			dy += 4;
			String rm = armed(road.id()) ? "Confirm remove" : "Remove road…";
			s.button(g, "road_remove", rm, dx, dy, s.bw(rm), armed(road.id()), busy || !sp, true, mx, my, () -> removeClick(road.id()));
			dy += 24;
			dy = drawNote(g, font, dx, dy, dw, y + h, armed(road.id()) ? "Click Confirm remove: every cell the road changed comes back as it "
				+ "was (cells you changed since stay)." : "Remove road… asks twice and puts every cell back as it was.");
			RoadsFeature.reportUi(dy - y, h);
			return;
		}
		RoadsFeature.RouteState rs0 = RoadsFeature.route(p.a(), p.b());
		String route;
		int routeColor = muted;
		if (rs0 == null) {
			route = "Route: not planned yet";
		} else if (rs0.found()) {
			route = "Route: " + Math.round(rs0.length) + " blocks, entrance to entrance, walkable both ways";
			routeColor = ink;
		} else if ("planning".equals(rs0.status)) {
			route = "Route: planning…";
		} else {
			route = "No route: " + rs0.why;
			routeColor = UiBits.errorText();
		}
		dy = wrap(g, font, route, dx, dy, dw, routeColor, 2) + 3;
		// options for the road to lay
		int bx = dx;
		String wl = "Width " + RoadsFeature.width();
		s.button(g, "road_width", wl, bx, dy, s.bw(wl), false, false, false, mx, my, RoadsFeature::cycleWidth);
		bx += s.bw(wl) + 4;
		String ll = "Lanterns: " + (RoadsFeature.lanterns() ? "On" : "Off");
		s.button(g, "road_lanterns", ll, bx, dy, s.bw(ll), RoadsFeature.lanterns(), false, false, mx, my,
			() -> RoadsFeature.setLanterns(!RoadsFeature.lanterns()));
		bx += s.bw(ll) + 4;
		String bl = "Bridges: " + (RoadsFeature.bridge() ? "On" : "Off");
		if (bx + s.bw(bl) > dx + dw) {
			bx = dx;
			dy += 22;
		}
		s.button(g, "road_bridge", bl, bx, dy, s.bw(bl), RoadsFeature.bridge(), false, false, mx, my, () -> RoadsFeature.setBridge(!RoadsFeature.bridge()));
		dy += 24;
		bx = dx;
		String lay = "Lay road…";
		boolean canLay = sp && !busy && (rs0 == null || !"failed".equals(rs0.status));
		s.button(g, "road_lay", lay, bx, dy, s.bw(lay), true, !canLay, false, mx, my, () -> layClick(p.a(), p.b()));
		bx += s.bw(lay) + 4;
		String again = "Plan again";
		s.button(g, "road_plan", again, bx, dy, s.bw(again), false, rs0 != null && "planning".equals(rs0.status), false, mx, my,
			() -> RoadsFeature.plan(p.a(), p.b(), true));
		dy += 24;
		dy = drawNote(g, font, dx, dy, dw, y + h, !sp ? "Singleplayer only: roads are laid through the integrated server."
			: "Lay road… shows it in the world first: tan is paved, orange cleared, red left out. Enter lays it; Remove puts it all back. "
				+ (RoadsFeature.bridge() ? "Shallow water gets a plank bridge." : "Shallow water is skipped (Bridges: Off)."));
		RoadsFeature.reportUi(dy - y, h);
	}

	private static String rowLine(RoadsFeature.Pair p) {
		if (p.road() != null) {
			return p.road().cellCount() + " cells · width " + p.road().width() + " · " + p.road().lanternCount() + " lanterns";
		}
		RoadsFeature.RouteState rs = RoadsFeature.route(p.a(), p.b());
		if (rs == null) {
			return Math.round(p.distance()) + " blocks apart · no road";
		}
		if (rs.found()) {
			return Math.round(rs.length) + " block route · no road";
		}
		return "planning".equals(rs.status) ? "planning the route…" : "no route";
	}

	private static String roadLine(Road r) {
		return "Road " + r.id() + " · width " + r.width() + " · " + r.cellCount() + " cells · " + r.lanternCount() + " lantern"
			+ (r.lanternCount() == 1 ? "" : "s") + (r.bridge() ? " · bridges" : "") + (r.created() > 0 ? " · laid " + UiBits.ago(r.created()) : "");
	}

	private static int wrap(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int color, int maxLines) {
		List<String> lines = TextUtil.wrapPlain(font, text, w);
		for (int i = 0; i < lines.size() && i < maxLines; i++) {
			String l = i == maxLines - 1 && lines.size() > maxLines ? TextUtil.ellipsize(font, lines.get(i) + " …", w) : lines.get(i);
			g.text(font, l, x, y, color, false);
			y += 10;
		}
		return y;
	}

	/** The note under the buttons: the last outcome here, else {@code hint}; never past the pane's bottom. */
	private int drawNote(GuiGraphicsExtractor g, Font font, int x, int y, int w, int bottom, @Nullable String hint) {
		String text = note != null ? note : hint;
		int color = note != null ? (noteError ? UiBits.errorText() : UiBits.okText()) : UiBits.muted();
		if (armed != null && hint != null && armed(armed)) {
			text = hint;
			color = UiBits.errorText();
		}
		if (text == null) {
			return y;
		}
		for (String line : TextUtil.wrapPlain(font, text, w)) {
			if (y > bottom - 10) {
				break;
			}
			g.text(font, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	JsonObject state() {
		JsonObject o = new JsonObject();
		o.addProperty("selected", selected);
		o.addProperty("armedRemove", armed != null && armed(armed) ? armed : null);
		o.addProperty("note", note);
		o.addProperty("rows", rows().size());
		return o;
	}
}
