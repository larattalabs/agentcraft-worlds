package dev.agentcraft.client.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.LinkStatus;
import dev.agentcraft.client.foreman.Protocol.AuthStatus;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.hub.ConnectionHints;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * What the welcome card (W7) and the hub's Status > Keys & help say: every AgentCraft key with its live
 * binding (the AgentCraft category of Options > Controls, so keys added later show up by themselves), every
 * in-world interaction, and a one-line Foreman status.
 */
public final class HelpContent {
	private HelpContent() {
	}

	/** One key: its live label ("H", "?" when unbound), what it does (the Controls name) and whether it is bound. */
	public record KeyRow(String key, String name, boolean bound) {
	}

	/** One in-world interaction: the thing and how to use it. */
	public record Interaction(String what, String how) {
	}

	/** What AgentCraft is, in two lines. */
	public static final List<String> ABOUT = List.of(
		"AgentCraft turns your Claude coding team into a village: each building holds a repo, and agents work at its desks.",
		"Give the team goals; they plan and code. You answer their decisions and review their merges.");

	/** "Place your first building: H > Buildings > Place new" (with the live hub key). */
	public static String firstBuilding() {
		return "Place your first building: " + key(Keys.hub, "H") + " > Buildings > Place new";
	}

	public static final List<Interaction> INTERACTIONS = List.of(
		new Interaction("Decision podium", "right-click: answer the decisions of this building's team"),
		new Interaction("Task board", "right-click a card: that task (status, assignee, PR)"),
		new Interaction("Monitor", "shows its agent's live log; right-click (empty hand): its full log in the Inbox"),
		new Interaction("Console terminal", "right-click, or look at it and press the terminal key: the console for this building's repo"),
		new Interaction("Library", "right-click a memory archive, catalog or a lectern inside a building: the team's notes and plan"),
		new Interaction("Merge station", "right-click: review the merge it shows (diff, then Merge / Request changes)"),
		new Interaction("Agent", "sneak + right-click with an empty hand: the agent's card (task, log, decisions)"));

	/** The AgentCraft keys, in registration order (as Options > Controls lists them). */
	public static List<KeyRow> keys() {
		Keys.ensureRegistered();
		List<KeyRow> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		KeyMapping.Category cat = Keys.hub.getCategory();
		List<KeyMapping> ours = new ArrayList<>();
		for (KeyMapping k : mc.options.keyMappings) {
			if (k.getCategory().equals(cat)) {
				ours.add(k);
			}
		}
		for (KeyMapping k : ours) {
			out.add(new KeyRow(k.isUnbound() ? "not bound" : Keys.label(k), I18n.get(k.getName()), !k.isUnbound()));
		}
		return out;
	}

	/** The key label of a mapping, for prose ("H"). */
	public static String key(KeyMapping k, String fallback) {
		return k == null || k.isUnbound() ? fallback : Keys.label(k);
	}

	/** Status dot family and one line about the Foreman link. */
	public record Status(String family, String text) {
	}

	public static Status foreman() {
		ForemanState st = Foreman.state();
		LinkStatus link = st == null ? null : st.link();
		ForemanStatus fs = st == null ? null : st.status();
		if (link == null) {
			return new Status("idle", "Foreman: unknown");
		}
		if (link.phase() == LinkStatus.Phase.DISABLED) {
			return new Status("idle", "Foreman link is off (AGENTCRAFT_FOREMAN=0)");
		}
		if (link.synced()) {
			if (fs != null && fs.auth() == AuthStatus.FAILED) {
				return new Status("error", "Foreman connected, but Claude can't sign in: see the hub's Status tab");
			}
			String backend = fs == null ? "connected" : fs.backend().wire();
			return new Status("done", "Foreman connected (" + backend + (fs != null && fs.account() != null ? ", " + fs.account() : "") + "): the team is ready");
		}
		if (link.everSynced()) {
			return new Status("waiting", "Reconnecting to the Foreman…");
		}
		String ls = dev.agentcraft.client.launcher.Launcher.state().wire();
		return new Status(ls.equals("starting") || ls.equals("installing") ? "thinking" : "idle", ConnectionHints.title(ls) + ": "
			+ ConnectionHints.detail(ls, ClientEnv.DEV_RUN, Keys.hub == null ? "H" : Keys.label(Keys.hub)));
	}

	public static JsonObject json() {
		JsonObject o = new JsonObject();
		JsonArray ks = new JsonArray();
		for (KeyRow k : keys()) {
			JsonObject j = new JsonObject();
			j.addProperty("key", k.key());
			j.addProperty("name", k.name());
			j.addProperty("bound", k.bound());
			ks.add(j);
		}
		o.add("keys", ks);
		JsonArray is = new JsonArray();
		for (Interaction i : INTERACTIONS) {
			is.add(i.what() + ": " + i.how());
		}
		o.add("interactions", is);
		o.addProperty("foreman", foreman().text());
		return o;
	}
}
