package dev.agentcraft.client.hub;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The hub's tabs, in order (docs/HUB.md "Hub screen"). Tabs a later wave fills show a short "coming
 * next" panel listing what they will hold.
 */
public enum HubTab {
	BUILDINGS("buildings", "Buildings", true, List.of()),
	REPOS("repos", "Repos", false, List.of(
		"The repos the Foreman knows (path, branch, head, uncommitted changes).",
		"Per-repo settings: test and lint commands, merge policy, protected paths.",
		"Add a repo by path; until then: /repo add <path> in the console.")),
	GOALS("goals", "Goals", false, List.of(
		"Submit a goal to a repo, continue a branch or an earlier session.",
		"Open goals with their progress, tasks and who works on them.",
		"Until then: type a goal in the console (`).")),
	TEAM("team", "Team", false, List.of(
		"The agents: role, model, effort and shift.",
		"Pause, resume, put on or take off shift.",
		"Until then: /pause, /resume, /stop and /spawn in the console.")),
	SETTINGS("settings", "Settings", false, List.of(
		"Permissions, context, connectors and session history.",
		"Usage limits and what happens when one is reached.",
		"Until then: the Foreman's profile settings and launch flags.")),
	STATUS("status", "Status", true, List.of());

	public final String id;
	public final String label;
	/** Built in this wave (false = the "coming next" panel). */
	public final boolean built;
	/** What the tab will hold, for the "coming next" panel. */
	public final List<String> comingNext;

	HubTab(String id, String label, boolean built, List<String> comingNext) {
		this.id = id;
		this.label = label;
		this.built = built;
		this.comingNext = comingNext;
	}

	/** The tab named {@code s} (id or label, any case; "1".."6" by position), or null. */
	public static @Nullable HubTab parse(@Nullable String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String k = s.strip().toLowerCase(Locale.ROOT);
		for (HubTab t : values()) {
			if (t.id.equals(k) || t.id.startsWith(k) && k.length() >= 3) {
				return t;
			}
		}
		if (k.length() == 1 && k.charAt(0) >= '1' && k.charAt(0) <= '0' + values().length) {
			return values()[k.charAt(0) - '1'];
		}
		return null;
	}

	/** "buildings, repos, goals, team, settings, status". */
	public static String ids() {
		StringBuilder b = new StringBuilder();
		for (HubTab t : values()) {
			if (b.length() > 0) {
				b.append(", ");
			}
			b.append(t.id);
		}
		return b.toString();
	}
}
