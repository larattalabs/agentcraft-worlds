package dev.agentcraft.ui;

import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Pure interaction rules of the client screens (fix wave 1, stream "ui"; docs/FIXWAVE.md). No game or
 * Foreman classes: the client maps its state in, so each rule is unit-tested ({@code UiRulesTest}).
 */
public final class UiRules {
	private UiRules() {
	}

	// ------------------------------------------------------------------ C6 pause

	/**
	 * Whether AgentCraft screens pause a singleplayer game (contract C6). Everyday play (a jar in a normal
	 * launcher) pauses, like vanilla menus, so a Hardcore world is never left running behind a screen. A dev
	 * run ({@code gradlew runClient}) or a client with the DevBridge on keeps the world running for QA.
	 * {@code override} ({@code AGENTCRAFT_PAUSE=0|1}) wins over both. Multiplayer never pauses (vanilla
	 * ignores {@code isPauseScreen} there).
	 */
	public static boolean screensPause(boolean devRun, boolean devBridge, @Nullable Boolean override) {
		if (override != null) {
			return override;
		}
		return !(devRun || devBridge);
	}

	// ------------------------------------------------------------------ agent NPCs

	/**
	 * Agents can be targeted (crosshair, clicks, swings) only while the player sneaks with an empty main
	 * hand: otherwise a right-click goes to the item (eat, block, draw a bow, place) and a swing goes to
	 * the block behind the agent.
	 */
	public static boolean agentTargetable(boolean sneaking, boolean mainHandEmpty) {
		return sneaking && mainHandEmpty;
	}

	/** A use (right-click) on an agent opens its card only for the main hand, sneaking, empty-handed. */
	public static boolean agentUseOpensCard(boolean mainHand, boolean sneaking, boolean mainHandEmpty) {
		return mainHand && agentTargetable(sneaking, mainHandEmpty);
	}

	// ------------------------------------------------------------------ C7 teleport

	/**
	 * The hub's Teleport (contract C7): allowed when the server lets the player use commands (cheats on in
	 * singleplayer, an op in multiplayer) or in creative/spectator. Otherwise it would be a free fast travel
	 * in a survival / Hardcore world, so the button is hidden.
	 */
	public static boolean teleportAllowed(boolean commandsAllowed, boolean creative, boolean spectator) {
		return commandsAllowed || creative || spectator;
	}

	// ------------------------------------------------------------------ lecterns

	/**
	 * A vanilla lectern opens the memory library only when it stands inside a recorded building (or in the
	 * dev HQ world, whose library has them), has no book on it and the player is not holding a book to put
	 * on it. Everywhere else lecterns behave like vanilla (read / place / take books).
	 */
	public static boolean lecternOpensLibrary(boolean insideBuilding, boolean hqWorld, boolean hasBook, boolean holdingBook) {
		return (insideBuilding || hqWorld) && !hasBook && !holdingBook;
	}

	/**
	 * The first of {@code items} whose box contains the block position, in dimension {@code dimension}
	 * ({@code dimensionOf} null = assumed {@code minecraft:overworld}, as {@code Building#dimensionOrDefault}).
	 */
	public static <T> @Nullable T containing(Collection<T> items, Function<T, Anchors.Bounds> box, Function<T, @Nullable String> dimensionOf,
		String dimension, int x, int y, int z) {
		for (T t : items) {
			String d = dimensionOf.apply(t);
			if (!Objects.equals(d == null ? "minecraft:overworld" : d, dimension)) {
				continue;
			}
			Anchors.Bounds b = box.apply(t);
			if (b != null && b.contains(x, y, z)) {
				return t;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Enter

	public enum EnterAction {
		SEND, NEWLINE
	}

	/**
	 * The one Enter rule of every AgentCraft text input. Single-line inputs (console, decision answer, the
	 * agent card's message line): Enter sends, Ctrl+Enter sends too, Shift+Enter inserts a new line where
	 * the input takes several lines. Multi-line inputs (goal thread, notes, plan editor, design notes):
	 * Enter inserts a new line and Ctrl+Enter sends.
	 */
	public static EnterAction enter(boolean multiline, boolean ctrl, boolean shift) {
		if (multiline) {
			return ctrl ? EnterAction.SEND : EnterAction.NEWLINE;
		}
		return shift && !ctrl ? EnterAction.NEWLINE : EnterAction.SEND;
	}

	/** Key hints for the rule above, as (key, verb) pairs. */
	public static String[] enterHints(boolean multiline) {
		return multiline ? new String[] {"Ctrl+Enter", "send", "Enter", "new line"} : new String[] {"Enter", "send", "Shift+Enter", "new line"};
	}

	// ------------------------------------------------------------------ two-press confirms

	/** A second press within {@code windowMs} of arming confirms (agent Stop, card answers, console goals). */
	public static boolean secondPress(long armedAt, long now, long windowMs) {
		return armedAt > 0 && now >= armedAt && now - armedAt <= windowMs;
	}

	// ------------------------------------------------------------------ console

	/**
	 * Plain console text (no {@code /} or {@code @}) creates a goal only after a confirm: the first Enter
	 * shows "Create goal: …? Enter again", the second Enter on the same text creates it. {@code /goal text}
	 * skips the confirm. Editing the text drops the confirm.
	 */
	public static boolean plainGoalConfirmed(@Nullable String armedText, String text) {
		return armedText != null && armedText.strip().equals(text.strip()) && !text.isBlank();
	}

	/**
	 * The repo a goal typed at a console terminal goes to by default: the first of the building's repos
	 * the Foreman knows; null when the terminal stands in no building or none of its repos is known.
	 */
	public static @Nullable String buildingRepo(@Nullable List<String> buildingRepos, Collection<String> knownRepos) {
		if (buildingRepos == null) {
			return null;
		}
		for (String r : buildingRepos) {
			if (knownRepos.contains(r)) {
				return r;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ C2 lead worlds

	/** One lead assignment as the Team tab sees it: the lead, the world that holds it, its last sync (0 = unknown). */
	public record LeadWorld(String leadId, @Nullable String world, long lastSync) {
	}

	/** Other worlds holding leads, as the Team tab lists them. */
	public record OtherWorld(String world, List<String> leads, long lastSync) {
	}

	/**
	 * Worlds other than {@code currentWorld} that hold lead assignments (contract C2), in first-seen order,
	 * with their leads and the newest {@code lastSync}. Assignments without a world (marlow, an old
	 * Foreman) are not listed. With no current world (title screen, multiplayer) every world is "other".
	 */
	public static List<OtherWorld> otherWorlds(Collection<LeadWorld> assignments, @Nullable String currentWorld) {
		Map<String, List<String>> leads = new LinkedHashMap<>();
		Map<String, Long> sync = new LinkedHashMap<>();
		for (LeadWorld a : assignments) {
			if (a.world() == null || a.world().isBlank() || a.world().equals(currentWorld)) {
				continue;
			}
			leads.computeIfAbsent(a.world(), k -> new ArrayList<>()).add(a.leadId());
			sync.merge(a.world(), a.lastSync(), Math::max);
		}
		List<OtherWorld> out = new ArrayList<>();
		leads.forEach((w, l) -> out.add(new OtherWorld(w, List.copyOf(l), sync.getOrDefault(w, 0L))));
		return out;
	}
}
