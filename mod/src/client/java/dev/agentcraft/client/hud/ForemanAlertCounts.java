package dev.agentcraft.client.hud;

import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.FeedKind;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.hub.HubGoals;
import org.jspecify.annotations.Nullable;

/**
 * {@link AlertCounts} from the Foreman state model, until the inbox stream's {@code InboxModel} replaces it:
 * <ul>
 *   <li>decisions: {@link DecisionsFeature#waitingCount()} (what the decisions badge shows);</li>
 *   <li>blocked: tasks with status {@code blocked};</li>
 *   <li>replies: feed {@code message} items from an agent to the user, newer than their read mark: a goal's
 *       ({@code hub-seen.json}, set by opening the goal in the hub) for goal-tagged ones, and the console's
 *       ({@code hub-hud.json repliesSeen}, set while the console is open, and to "now" the first time a world is
 *       seen, so the Foreman's feed history never floods a new world) for all;</li>
 *   <li>hold: {@code foreman.status.hold}.</li>
 * </ul>
 */
public record ForemanAlertCounts(int decisions, int blocked, int replies, Protocol.@Nullable Hold hold) implements AlertCounts {

	public static ForemanAlertCounts compute() {
		ForemanState s = Foreman.state();
		if (s == null || !s.hasData()) {
			return new ForemanAlertCounts(0, 0, 0, null);
		}
		int blocked = 0;
		for (Task t : s.tasks().values()) {
			if (t.status() == TaskStatus.BLOCKED) {
				blocked++;
			}
		}
		String world = HudMemory.world();
		long consoleSeen = HudMemory.repliesBaseline(world);
		int replies = 0;
		for (FeedItem f : s.feed()) {
			if (isReply(f)) {
				// the console shows every reply, so its mark covers goal replies too (and is the new world's baseline)
				long seen = f.goalId() != null ? Math.max(consoleSeen, HubGoals.seen().goalSeen(world, f.goalId())) : consoleSeen;
				if (f.ts() > seen) {
					replies++;
				}
			}
		}
		var st = s.status();
		return new ForemanAlertCounts(DecisionsFeature.waitingCount(), blocked, replies, st == null ? null : st.hold());
	}

	/** An agent's message to the user. */
	public static boolean isReply(FeedItem f) {
		return f.kind() == FeedKind.MESSAGE && f.agentId() != null && !f.agentId().isBlank() && !UiBits.isUser(f.agentId()) && UiBits.isUser(f.to());
	}
}
