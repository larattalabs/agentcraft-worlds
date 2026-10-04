package dev.agentcraft.client.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.hud.AlertLine;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The counts behind the HUD alert line, the away toast and the hub's tab badges, from one {@link AlertCounts}
 * source (docs/WAVE2.md W5). Cached per Foreman revision for at most 500 ms (read marks change without a
 * revision). Client thread.
 *
 * <p>Merge note: when the inbox stream's {@code InboxModel} lands, call
 * {@code Alerts.setSource(() -> <InboxModel's needs-you counts as an AlertCounts>)} once (e.g. in its feature's
 * init); nothing else changes.
 */
public final class Alerts {
	private static final long MAX_AGE_MS = 500;
	private static Supplier<? extends AlertCounts> source = ForemanAlertCounts::compute;
	private static String sourceName = "foreman-state";
	private static AlertLine cached = AlertLine.NONE;
	private static long cachedRev = Long.MIN_VALUE;
	private static long cachedAt;

	private Alerts() {
	}

	/** Replaces where the counts come from (the Inbox's model at merge). */
	public static void setSource(String name, Supplier<? extends AlertCounts> counts) {
		source = Objects.requireNonNull(counts);
		sourceName = name;
		invalidate();
	}

	public static String sourceName() {
		return sourceName;
	}

	/** Read marks changed (a goal or the console was seen): recount at the next call. */
	public static void invalidate() {
		cachedRev = Long.MIN_VALUE;
	}

	/** The current line (decisions, blocked, replies, PRs, hold). */
	public static AlertLine line() {
		ForemanState s = Foreman.state();
		long rev = s == null ? -1 : s.revision();
		long now = System.currentTimeMillis();
		if (rev != cachedRev || now - cachedAt > MAX_AGE_MS) {
			AlertCounts c = source.get();
			Protocol.ForemanHold h = c.hold();
			cached = new AlertLine(c.decisions(), c.blocked(), c.replies(), c.prs(), h == null ? null : h.reason(), h == null ? null : h.until(),
				h == null ? null : h.message());
			cachedRev = rev;
			cachedAt = now;
		}
		return cached;
	}

	public static JsonObject json() {
		AlertLine a = line();
		long now = System.currentTimeMillis();
		ZoneId z = ZoneId.systemDefault();
		JsonObject o = new JsonObject();
		o.addProperty("source", sourceName);
		o.addProperty("visible", a.visible());
		o.addProperty("decisions", a.decisions());
		o.addProperty("blocked", a.blocked());
		o.addProperty("replies", a.replies());
		o.addProperty("prs", a.prs());
		o.addProperty("needsYou", a.needsYou());
		o.addProperty("hold", a.holdReason());
		o.addProperty("holdUntil", a.holdUntil());
		o.addProperty("holdMessage", a.holdMessage());
		o.addProperty("text", a.text(AlertLine.Level.FULL, z, now));
		JsonArray parts = new JsonArray();
		for (AlertLine.Part p : a.parts(z, now)) {
			JsonObject j = new JsonObject();
			j.addProperty("kind", p.kind());
			j.addProperty("family", p.family());
			j.addProperty("full", p.full());
			j.addProperty("short", p.brief());
			j.addProperty("dots", p.dots());
			parts.add(j);
		}
		o.add("parts", parts);
		return o;
	}
}
