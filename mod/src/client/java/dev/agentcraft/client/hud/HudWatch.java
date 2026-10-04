package dev.agentcraft.client.hud;

import com.google.gson.JsonObject;
import dev.agentcraft.building.Buildings;
import dev.agentcraft.client.console.ConsoleScreen;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.hub.HubGoals;
import dev.agentcraft.client.hub.HubScreen;
import dev.agentcraft.client.hub.HubTab;
import dev.agentcraft.client.ui.HasParent;
import dev.agentcraft.hud.HudPrefs;
import dev.agentcraft.hud.HudRules;
import dev.agentcraft.ui.Guard;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * The wave 2 HUD's client-tick side (docs/WAVE2.md W5-W7):
 * <ul>
 *   <li>while the hub is on screen (also under a task / decision screen opened from it): it counts as seen
 *       now ({@code hub-hud.json hubSeenAt}) and its tab is remembered per world, so {@code H} reopens it;</li>
 *   <li>while the console is open: replies that belong to no goal count as seen;</li>
 *   <li>away (W6): on joining a world (once the Foreman is connected) and after {@link HudRules#AWAY_MS}
 *       without the hub, {@code goal.digest {since}} is asked for; when a goal moved, a toast "Since you were
 *       away: N goals moved, M need you" with the hub key. Until the hub is opened again, {@code H} then opens
 *       the Inbox when there is one, else the Goals tab, whose away panel shows that digest;</li>
 *   <li>the welcome card (W7) on joining a world with no building ({@link HudRules#welcomeDue}).</li>
 * </ul>
 */
public final class HudWatch {
	/** The welcome card waits this long after joining (the world settles, the link connects). */
	private static final long WELCOME_DELAY_MS = 2500;
	/** {@code AGENTCRAFT_WELCOME=0}: never open the welcome card by itself (scripted QA worlds); dev.onboarding still shows it. */
	private static final boolean AUTO_WELCOME = dev.agentcraft.client.ClientEnv.flag("AGENTCRAFT_WELCOME", true);

	private static boolean joinPending;
	private static long joinedAt;
	private static long lastCheckAt;
	private static boolean welcomeShown;
	/** An away toast was shown and the hub has not been opened since: H goes to the Inbox / Goals. */
	private static boolean awayPending;
	private static boolean checking;
	private static @Nullable String lastAwayText;
	private static long lastAwaySince;
	private static int lastAwayGoals = -1;

	private HudWatch() {
	}

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(HudWatch::joined));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
			HudMemory.flush(true);
			joinPending = false;
			awayPending = false;
			welcomeShown = false;
			checking = false;
		}));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("hud.watch", () -> tick(mc)));
	}

	static void joined() {
		checking = false;
		joinPending = true;
		joinedAt = System.currentTimeMillis();
		lastCheckAt = 0;
		welcomeShown = false;
		awayPending = false;
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		HudPrefs p = HudMemory.prefs();
		String world = HudMemory.world();
		HudMemory.repliesBaseline(world); // first time in this world: the feed's history is not news
		Screen screen = mc.gui.screen();
		HubScreen hub = hubUnder(screen);
		if (hub != null) {
			p.markHubSeen(world, now);
			p.setLastTab(world, hub.tab().id);
			awayPending = false;
		}
		if (under(screen, ConsoleScreen.class)) {
			long before = p.repliesSeen(world);
			p.markRepliesSeen(world, now);
			if (before != p.repliesSeen(world)) {
				Alerts.invalidate();
			}
		}
		// away (W6)
		ForemanState s = Foreman.state();
		boolean connected = Foreman.connected() && s != null && s.hasData();
		long since = HudRules.awaySince(hubSeen(p, world), p.lastAwayToastAt(world));
		if (!checking && HudRules.awayDue(now, since, joinPending, lastCheckAt, hub != null, connected)) {
			checkAway(since, now);
		}
		if (joinPending && since <= 0 && connected) {
			joinPending = false; // hub never opened in this world: nothing to compare with (the welcome card covers new worlds)
		}
		// welcome (W7)
		if (!welcomeShown && AUTO_WELCOME && now - joinedAt >= WELCOME_DELAY_MS && mc.gui.overlay() == null) {
			boolean sp = mc.getSingleplayerServer() != null;
			boolean hq = sp && dev.agentcraft.world.HqWorld.isHq(mc.getSingleplayerServer());
			if (HudRules.welcomeDue(sp, hq, Buildings.loadFailed(), Buildings.buildings().size(), p.welcomeDismissed(world), false, screen != null)) {
				welcomeShown = true;
				mc.gui.setScreen(new WelcomeScreen());
			} else if (screen == null) {
				welcomeShown = true; // decided for this join
			}
		}
		HudMemory.flush(false);
	}

	/** When the hub was last on screen; before wave 2 kept that, the Goals tab's mark from hub-seen.json. */
	private static long hubSeen(HudPrefs p, String world) {
		long t = p.hubSeenAt(world);
		return t > 0 ? t : HubGoals.seen().tabSeen(world);
	}

	/** Asks for the digest since {@code since}; a toast when a goal moved. Client thread. */
	static CompletableFuture<HubGoals.DigestState> checkAway(long since, long now) {
		checking = true;
		lastCheckAt = now;
		joinPending = false;
		Minecraft mc = Minecraft.getInstance();
		return HubGoals.requestAway(since).whenComplete((st, err) -> mc.execute(() -> {
			checking = false;
			if (st == null || st.digest == null) {
				return;
			}
			int moved = 0;
			for (Protocol.GoalDigest gd : st.digest.goals()) {
				if (!gd.lines().isEmpty()) {
					moved++;
				}
			}
			lastAwaySince = since;
			lastAwayGoals = moved;
			String text = HudRules.awayText(moved, Alerts.line().needsYou());
			lastAwayText = text;
			if (text == null) {
				st.dismissed = true; // nothing happened: no "nothing happened" panel on the Goals tab either
				return;
			}
			HudMemory.prefs().setLastAwayToastAt(HudMemory.world(), System.currentTimeMillis());
			HudMemory.flush(true);
			awayPending = true;
			Toasts.push(new Notify(Alerts.line().needsYou() > 0 ? NotifyLevel.NEED_USER : NotifyLevel.INFO, text, null, System.currentTimeMillis()),
				HelpContent.key(Keys.hub, "H"), "catch up");
		}));
	}

	/** Where {@code H} opens the hub: after an away toast the Inbox (else Goals), otherwise the last tab of this world. */
	public static HubTab hubTarget() {
		if (awayPending) {
			HubTab inbox = HubTab.parse("inbox");
			return inbox != null ? inbox : HubTab.GOALS;
		}
		HubTab last = HubTab.parse(HudMemory.prefs().lastTab(HudMemory.world()));
		return last != null ? last : HubTab.BUILDINGS;
	}

	public static void dismissWelcome() {
		HudMemory.prefs().setWelcomeDismissed(HudMemory.world(), true);
		HudMemory.flush(true);
	}

	/** dev.onboarding: forget the dismissal (and let the next join show it again). */
	static void resetWelcome() {
		HudMemory.prefs().setWelcomeDismissed(HudMemory.world(), false);
		HudMemory.flush(true);
		welcomeShown = false;
	}

	/** The hub on screen, directly or under screens opened from it, or null. */
	static @Nullable HubScreen hubUnder(@Nullable Screen s) {
		for (int i = 0; s != null && i < 8; i++) {
			if (s instanceof HubScreen h) {
				return h;
			}
			s = s instanceof HasParent hp ? hp.parent() : null;
		}
		return null;
	}

	static boolean under(@Nullable Screen s, Class<? extends Screen> type) {
		for (int i = 0; s != null && i < 8; i++) {
			if (type.isInstance(s)) {
				return true;
			}
			s = s instanceof HasParent hp ? hp.parent() : null;
		}
		return false;
	}

	/** dev.away: pretend the hub was last open {@code minutes} ago and check now. */
	static CompletableFuture<HubGoals.DigestState> simulateAway(int minutes) {
		long now = System.currentTimeMillis();
		String world = HudMemory.world();
		HudMemory.prefs().setHubSeenAtForTest(world, now - minutes * 60_000L);
		HudMemory.prefs().setLastAwayToastAt(world, 0);
		HudMemory.flush(true);
		return checkAway(HudRules.awaySince(HudMemory.prefs().hubSeenAt(world), 0), now);
	}

	static JsonObject json() {
		long now = System.currentTimeMillis();
		HudPrefs p = HudMemory.prefs();
		String world = HudMemory.world();
		JsonObject o = new JsonObject();
		o.addProperty("world", world);
		o.addProperty("hubSeenAt", p.hubSeenAt(world));
		o.addProperty("lastAwayToastAt", p.lastAwayToastAt(world));
		long since = HudRules.awaySince(hubSeen(p, world), p.lastAwayToastAt(world));
		o.addProperty("awaySince", since);
		o.addProperty("awayForMs", since <= 0 ? 0 : now - since);
		o.addProperty("joinPending", joinPending);
		o.addProperty("lastCheckAt", lastCheckAt);
		o.addProperty("checking", checking);
		o.addProperty("awayPending", awayPending);
		o.addProperty("lastAwaySince", lastAwaySince);
		o.addProperty("lastAwayGoals", lastAwayGoals);
		o.addProperty("lastAwayText", lastAwayText);
		o.addProperty("hubTarget", hubTarget().id);
		o.addProperty("lastTab", p.lastTab(world));
		o.addProperty("repliesSeen", p.repliesSeen(world));
		o.addProperty("welcomeDismissed", p.welcomeDismissed(world));
		o.addProperty("welcomeShownThisJoin", welcomeShown);
		return o;
	}
}
