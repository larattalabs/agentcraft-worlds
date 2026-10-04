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
	REPOS("repos", "Repos", true, List.of()),
	GOALS("goals", "Goals", true, List.of()),
	TEAM("team", "Team", true, List.of()),
	SETTINGS("settings", "Settings", true, List.of()),
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
