package dev.agentcraft.client.hub;

import dev.larattalabs.labui.client.hud.UiBits;
import dev.agentcraft.client.launcher.Launcher;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.agentcraft.launcher.LauncherPlan;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Status tab's "Launcher" section (docs/HUB.md "Foreman launcher"): what the mod's Foreman launcher did (disabled,
 * node missing and how to install it, installing, starting, running: ours or reused, pid, version, log; crashed with the
 * log's last lines; an older Foreman it did not start), with Start, Restart (only for one the game started) and Open log.
 */
final class LauncherSection {
	private LauncherSection() {
	}

	/** One line for the state, as the section's first line and in dev state. */
	static String headline() {
		return switch (Launcher.state()) {
			case IDLE -> "not checked yet";
			case DISABLED -> "off";
			case NODE_MISSING -> "Node.js not found";
			case NO_SOURCE -> "no checkout to run from";
			case INSTALLING -> "installing (npm ci)…";
			case STARTING -> "starting…";
			case RUNNING -> Launcher.ours() ? "running · started by the game" : "running · reused";
			case RUNNING_OLDER -> "running (older version)";
			case CRASHED -> "crashed";
			case BLOCKED -> "could not start";
			case STOPPED -> "stopped";
		};
	}

	static String dot() {
		return switch (Launcher.state()) {
			case RUNNING -> "done";
			case STARTING, INSTALLING -> "thinking";
			case RUNNING_OLDER, BLOCKED, NODE_MISSING, NO_SOURCE -> "waiting";
			case CRASHED -> "error";
			default -> "idle";
		};
	}

	/**
	 * A button that is clickable only while it is fully inside the Overview's visible rows (the Overview scrolls: a button
	 * scrolled under the chips must not catch their clicks); otherwise it is only drawn (the scissor clips it).
	 */
	private static void button(HubScreen hub, GuiGraphicsExtractor g, String id, String label, int x, int y, boolean primary, boolean disabled,
		int mx, int my, Runnable action) {
		if (y >= hub.statusClipTop && y + 20 <= hub.statusClipBottom) {
			hub.button(g, id, label, x, y, hub.bw(label), primary, disabled, false, mx, my, action);
		} else {
			UiBits.button(g, hub.font(), label, 0, x, y, hub.bw(label), primary, disabled ? UiBits.ButtonState.DISABLED : UiBits.ButtonState.NORMAL, false);
		}
	}

	/** Draws the section at (x, y), {@code w} wide; returns the y below it. */
	static int draw(HubScreen hub, GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
		var font = hub.font();
		y = hub.section(g, "Launcher", x, y, w);
		Panels.dot(g, dot(), x, y + 1, false);
		g.text(font, TextUtil.ellipsize(font, headline(), w - 10), x + 10, y, UiBits.ink(), false);
		y += 11;
		LauncherPlan.State s = Launcher.state();
		y = hub.fact(g, "", Launcher.detail(), x, y, w);
		if (s == LauncherPlan.State.INSTALLING && Launcher.installLine() != null) {
			y = hub.fact(g, "npm", Launcher.installLine(), x, y, w);
		}
		if (Launcher.pid() > 0 && (s == LauncherPlan.State.RUNNING || s == LauncherPlan.State.RUNNING_OLDER || s == LauncherPlan.State.STARTING)) {
			String version = Launcher.runningVersion() == null ? "" : " · v" + Launcher.runningVersion();
			String commit = Launcher.runningCommit() == null ? "" : " · " + LauncherPlan.shortSha(Launcher.runningCommit());
			y = hub.fact(g, "PID", Launcher.pid() + version + commit, x, y, w);
		}
		LauncherPlan.Source src = Launcher.source();
		String runs = Launcher.runningRoot();
		if (runs != null && (src == null || src.root() == null || !java.nio.file.Path.of(runs).equals(src.root()))
			&& (s == LauncherPlan.State.RUNNING || s == LauncherPlan.State.RUNNING_OLDER)) {
			y = hub.fact(g, "Runs from", runs, x, y, w);
		}
		if (src != null && src.root() != null) {
			y = hub.fact(g, "Checkout", src.root() + " (" + src.origin() + ")", x, y, w);
		}
		if (s == LauncherPlan.State.NODE_MISSING) {
			y = hub.fact(g, "Install", "brew install node (or mise use -g node@22, or " + LauncherPlan.NODE_INSTALL_URL + "), then Start", x, y, w);
		}
		Path log = Launcher.logFile();
		if (log != null && s != LauncherPlan.State.RUNNING_OLDER && (Launcher.ours() || s == LauncherPlan.State.CRASHED)) {
			y = hub.fact(g, "Log", log.toString(), x, y, w);
		}
		// buttons
		int bx = x;
		y += 2;
		boolean busy = Launcher.busy();
		if (Launcher.canStart()) {
			String l = "Start";
			button(hub, g, "launcher_start", l, bx, y, true, busy, mx, my, Launcher::start);
			bx += hub.bw(l) + 4;
		}
		if (Launcher.canRestart()) {
			String l = "Restart";
			button(hub, g, "launcher_restart", l, bx, y, false, busy, mx, my, Launcher::restart);
			bx += hub.bw(l) + 4;
		}
		if (log != null && (Launcher.ours() || s == LauncherPlan.State.CRASHED)) {
			String l = "Open log";
			button(hub, g, "launcher_log", l, bx, y, false, !java.nio.file.Files.exists(log), mx, my, Launcher::openLog);
			bx += hub.bw(l) + 4;
		}
		y = bx > x ? y + 22 : y;
		// a crash: the log's last lines, under the buttons (the Overview scrolls)
		List<String> tail = Launcher.logTail();
		if (s == LauncherPlan.State.CRASHED && !tail.isEmpty()) {
			g.text(font, "Last lines of the log:", x, y, UiBits.muted(), false);
			y += 10;
			for (String line : tail.subList(Math.max(0, tail.size() - 8), tail.size())) {
				g.text(font, TextUtil.ellipsize(font, line, w), x, y, UiBits.errorText(), false);
				y += 10;
			}
			y += 2;
		}
		return y;
	}
}
