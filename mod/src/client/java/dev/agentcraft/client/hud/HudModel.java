package dev.agentcraft.client.hud;

import dev.agentcraft.client.decisions.DecisionQueue;
import dev.agentcraft.client.decisions.DecisionScreen;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.foreman.Protocol.UsageWindow;
import dev.agentcraft.hud.AlertLine;
import dev.agentcraft.hud.HudPeek;
import dev.agentcraft.hud.HudRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What the overlay shows, gathered once per frame from the Foreman state (or made up for the settings preview): the
 * goal the bar shows ({@link HudRules#pickGoal}) with its task counts, the open goals, decisions waiting and the next
 * one, the alert line, who is working, plan usage and the hold, and the peek showing. Every style draws from this.
 * Client thread.
 */
public final class HudModel {
	/** The goal shown. */
	public record GoalView(String id, String text, GoalStatus status, double progress, long updatedAt) {
		public boolean open() {
			return status == GoalStatus.PLANNING || status == GoalStatus.ACTIVE;
		}

		public int pct() {
			return (int) Math.round(Math.max(0, Math.min(1, progress)) * 100);
		}
	}

	public boolean hasData;
	public boolean stale;
	public @Nullable GoalView goal;
	/** The goal bar's pick (QA). */
	public HudRules.@Nullable Pick pick;
	public int more;
	/** Status dot families of the open goals in bar order, and which one is shown (Pill+). */
	public List<String> goalDots = List.of();
	public int goalIndex;
	/** doing review todo blocked done */
	public final int[] counts = new int[5];
	public int totalTasks;
	public String planningLine = "";
	/** Decisions waiting (0 while the decision screen is open: the badge would point at itself). */
	public int waiting;
	public @Nullable String nextDecision;
	public AlertLine alert = AlertLine.NONE;
	/** Agent ids at work right now. */
	public List<String> working = List.of();
	/** The shortest plan usage window (null = not reported). */
	public @Nullable String usageLabel;
	public double usagePct = -1;
	public HudPeek.@Nullable Peek peek;

	/** An open goal (planning or working). */
	public boolean activeGoal() {
		return goal != null && goal.open();
	}

	/** Something needs the player: a decision waiting or anything on the alert line. */
	public boolean needsPlayer() {
		return waiting > 0 || alert.visible();
	}

	// ------------------------------------------------------------------ live

	private static long candRevision = -1;
	private static final List<HudRules.GoalCand> CANDS = new ArrayList<>();
	private static final Map<String, Goal> OPEN = new HashMap<>();
	private static long countRevision = -1;
	private static @Nullable String countGoal;
	private static final int[] COUNTS = new int[5];
	private static int total;

	/** The model of this frame from the Foreman state (no data: {@link #hasData} false). */
	public static HudModel live() {
		HudModel m = new HudModel();
		ForemanState s = Foreman.state();
		if (s == null || !s.hasData()) {
			return m;
		}
		m.hasData = true;
		m.stale = s.isStale();
		long now = System.currentTimeMillis();
		HudRules.Pick pick = pick(s, now);
		m.pick = pick;
		Goal goal = pick == null ? null : s.goals().get(pick.id());
		if (goal == null) {
			goal = s.goal();
		}
		if (goal != null) {
			m.goal = new GoalView(goal.id(), UiBits.oneLine(goal.text()), goal.status(), goal.progress(), goal.updatedAt());
			recount(s, goal);
			System.arraycopy(COUNTS, 0, m.counts, 0, 5);
			m.totalTasks = total;
			if (goal.status() == GoalStatus.PLANNING) {
				m.planningLine = GoalBar.planningLine(s);
			}
		}
		m.more = pick == null ? 0 : pick.more();
		List<String> dots = new ArrayList<>();
		for (HudRules.GoalCand c : HudRules.order(CANDS)) {
			Goal g = OPEN.get(c.id());
			dots.add(g == null ? "idle" : g.status() == GoalStatus.PLANNING ? "thinking" : c.urgency() > 0 ? "waiting" : "working");
		}
		m.goalDots = List.copyOf(dots);
		m.goalIndex = pick == null ? 0 : pick.index();
		boolean decisionScreen = Minecraft.getInstance().gui.screen() instanceof DecisionScreen;
		m.waiting = decisionScreen ? 0 : DecisionsFeature.waitingCount();
		Decision next = DecisionQueue.first();
		m.nextDecision = next == null ? null : UiBits.oneLine(next.question());
		m.alert = Alerts.line();
		List<String> work = new ArrayList<>();
		for (Agent a : s.agents().values()) {
			if (!Boolean.FALSE.equals(a.active()) && !Boolean.TRUE.equals(a.paused()) && working(a.state())) {
				work.add(a.id());
			}
		}
		m.working = List.copyOf(work);
		var st = s.status();
		if (st != null && st.usage() != null && !st.usage().windows().isEmpty()) {
			UsageWindow w = st.usage().windows().get(0);
			m.usageLabel = w.label();
			m.usagePct = w.pct();
		}
		m.peek = HudPeeks.current(now);
		return m;
	}

	static boolean working(AgentState st) {
		return switch (st) {
			case THINKING, READING, EDITING, RUNNING, TESTING -> true;
			default -> false;
		};
	}

	private static HudRules.@Nullable Pick pick(ForemanState s, long now) {
		if (s.revision() != candRevision) {
			candRevision = s.revision();
			CANDS.clear();
			OPEN.clear();
			Map<String, Integer> urgency = new HashMap<>();
			for (var d : s.decisions().values()) {
				String gid = d.isOpen() ? dev.agentcraft.client.hub.HubGoals.goalOf(d) : null;
				if (gid != null) {
					urgency.merge(gid, 1, Integer::sum);
				}
			}
			for (Task t : s.tasks().values()) {
				if (t.status() == TaskStatus.BLOCKED && t.goalId() != null) {
					urgency.merge(t.goalId(), 1, Integer::sum);
				}
			}
			for (Goal g : s.goals().values()) {
				CANDS.add(new HudRules.GoalCand(g.id(), g.isOpen(), urgency.getOrDefault(g.id(), 0), g.updatedAt(), g.createdAt()));
				if (g.isOpen()) {
					OPEN.put(g.id(), g);
				}
			}
		}
		Goal latest = s.goal();
		return HudRules.pickGoal(CANDS, now, latest == null ? null : latest.id());
	}

	private static void recount(ForemanState s, Goal goal) {
		if (s.revision() == countRevision && java.util.Objects.equals(goal.id(), countGoal)) {
			return;
		}
		countRevision = s.revision();
		countGoal = goal.id();
		java.util.Arrays.fill(COUNTS, 0);
		total = 0;
		// tasks without a goal id (an old Foreman) count only while there is a single goal
		boolean single = s.goals().size() <= 1;
		for (Task t : s.tasks().values()) {
			if (t.goalId() == null ? !single : !t.goalId().equals(goal.id())) {
				continue;
			}
			int i = switch (t.status()) {
				case DOING -> 0;
				case REVIEW -> 1;
				case TODO -> 2;
				case BLOCKED -> 3;
				case DONE -> 4;
				default -> -1;
			};
			if (i >= 0) {
				COUNTS[i]++;
				total++;
			}
		}
	}

	// ------------------------------------------------------------------ sample (settings preview without a Foreman)

	/** A made-up model for the preview when there is no live goal: a goal under way, one decision, two agents at work. */
	public static HudModel sample() {
		HudModel m = new HudModel();
		m.hasData = true;
		long now = System.currentTimeMillis();
		m.goal = new GoalView("sample", "Add an export command", GoalStatus.ACTIVE, 0.4, now);
		m.counts[0] = 2;
		m.counts[1] = 1;
		m.counts[2] = 3;
		m.counts[4] = 4;
		m.totalTasks = 10;
		m.more = 1;
		m.goalDots = List.of("working", "thinking");
		m.waiting = 1;
		m.nextDecision = "Merge the tag parser into main?";
		m.alert = new AlertLine(1, 1, 0, null, null, null);
		m.working = List.of("kit", "juniper");
		m.usageLabel = "5h";
		m.usagePct = 62;
		return m;
	}
}
