package dev.agentcraft.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.agentcraft.hub.InboxModel.Counts;
import dev.agentcraft.hub.InboxModel.Filter;
import dev.agentcraft.hub.InboxModel.Group;
import dev.agentcraft.hub.InboxModel.Hold;
import dev.agentcraft.hub.InboxModel.Item;
import dev.agentcraft.hub.InboxModel.Kind;
import dev.agentcraft.hub.InboxModel.Row;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxModelTest {
	static final ZoneId UTC = ZoneOffset.UTC;

	static Item decision(String id, long ts, String agent, boolean open, String building, String podium) {
		return new Item("d:" + id, Kind.DECISION, ts, agent, "g1", building, "Q " + id, "", id, open, podium, "question", null);
	}

	static Item reply(String agent, long ts, String text, String goal, String building) {
		return new Item(InboxModel.replyKey(agent, ts, text), Kind.REPLY, ts, agent, goal, building, text, "", null, false, null, null, null);
	}

	static Item blocked(String task, long ts, String agent, String building) {
		return new Item("t:" + task, Kind.BLOCKED, ts, agent, "g1", building, task, "tests fail", task, true, null, null, null);
	}

	static Item hold(long until) {
		return new Item("hold", Kind.HOLD, 50, null, null, null, "usage", "5h limit", null, true, null, "usage", until);
	}

	static Item pr(String task, long ts) {
		return new Item("pr:" + task, Kind.PR, ts, "kit", "g1", "b3", "PR #4", "checks failing", task, true, null, "open", null);
	}

	static Item agentRow(String agent) {
		return new Item("agent:" + agent, Kind.AGENT, 0, agent, null, null, agent, "", null, false, null, null, null);
	}

	@Test
	void groupsNewestFirstWithTheHoldPinned() {
		Set<String> read = Set.of();
		List<Item> items = List.of(decision("d1", 100, "marlow", true, "b3", null), reply("kit", 300, "done", "g1", "b3"),
			blocked("t4", 200, "wren", "b5"), hold(10_000), decision("d0", 400, "kit", false, "b3", "b3"), pr("t7", 150));
		List<Row> rows = InboxModel.build(items, it -> read.contains(it.key()));
		List<String> keys = rows.stream().map(r -> r.item().key()).toList();
		assertEquals("hold", keys.get(0), "the hold is pinned on top of Needs you");
		assertEquals(List.of("hold", InboxModel.replyKey("kit", 300, "done"), "t:t4", "pr:t7", "d:d1", "d:d0"), keys);
		assertEquals(Group.UPDATES, rows.get(5).group(), "a closed decision is an update");
		Counts c = InboxModel.counts(rows);
		assertEquals(new Counts(1, 1, 1, 1, new Hold("usage", 10_000L, "5h limit")), c);
		assertEquals(5, c.needsYou());
		assertEquals("1 decision · 1 blocked · 1 reply · 1 PR · usage paused until 00:00", c.line(UTC));
	}

	@Test
	void aReadReplyMovesToUpdatesAndStopsCounting() {
		Item r = reply("kit", 300, "done", "g1", "b3");
		List<Row> unread = InboxModel.build(List.of(r), it -> false);
		assertEquals(Group.NEEDS_YOU, unread.get(0).group());
		assertTrue(unread.get(0).unread());
		List<Row> read = InboxModel.build(List.of(r), it -> true);
		assertEquals(Group.UPDATES, read.get(0).group());
		assertFalse(read.get(0).unread());
		assertEquals(0, InboxModel.counts(read).replies());
		assertEquals("", InboxModel.counts(read).line(UTC));
		// an open decision needs you whether it was viewed or not
		List<Row> d = InboxModel.build(List.of(decision("d1", 1, "kit", true, null, null)), it -> true);
		assertEquals(Group.NEEDS_YOU, d.get(0).group());
		assertFalse(d.get(0).unread());
	}

	@Test
	void capsTheUpdates() {
		List<Item> items = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			items.add(decision("c" + i, i, "kit", false, null, null));
			items.add(reply("kit", 1000 + i, "r" + i, null, null));
		}
		List<Row> rows = InboxModel.build(items, it -> true);
		assertEquals(InboxModel.MAX_CLOSED_DECISIONS + InboxModel.MAX_READ_REPLIES, rows.size());
		assertEquals(1099, rows.get(0).item().ts(), "newest first");
	}

	@Test
	void filters() {
		List<Item> items = List.of(decision("d1", 100, "marlow", true, "b3", null), decision("d2", 110, "wren", true, "b5", "b5"),
			reply("kit", 300, "done", "g1", "b3"), blocked("t4", 200, "wren", "b5"), hold(0), agentRow("wren"));
		List<Row> rows = InboxModel.build(items, it -> false);
		assertEquals(5, InboxModel.filter(rows, Filter.ALL, "b3", true).size(), "the agent row only shows under its agent filter");
		assertEquals(5, InboxModel.filter(rows, Filter.NEEDS_YOU, "b3", true).size());
		assertEquals(List.of("hold", "r", "d:d1"), keys(InboxModel.filter(rows, Filter.building("b3"), "b3", true)), "the hold shows everywhere");
		assertEquals(List.of("agent:wren", "t:t4", "d:d2"), keys(InboxModel.filter(rows, Filter.agent("wren"), "b3", true)));
		// podiums: b5's podium shows d2 (its target), the home podium (b3) shows d1 (target home)
		assertEquals(List.of("d:d2"), keys(InboxModel.filter(rows, Filter.podium("b5"), "b3", true)));
		assertEquals(List.of("d:d1"), keys(InboxModel.filter(rows, Filter.podium(null), "b3", true)));
		assertEquals(List.of("d:d1"), keys(InboxModel.filter(rows, Filter.podium("b3"), "b3", true)), "the home building's podium = home");
		assertEquals(2, InboxModel.filter(rows, Filter.podium("b5"), "b3", false).size(), "an older Foreman: every podium shows all");
	}

	private static List<String> keys(List<Row> rows) {
		return rows.stream().map(r -> r.item().key().startsWith("r:") ? "r" : r.item().key()).toList();
	}

	@Test
	void filterIdsRoundTrip() {
		for (Filter f : List.of(Filter.ALL, Filter.NEEDS_YOU, Filter.building("b3"), Filter.agent("kit"), Filter.podium("b3"), Filter.podium(null))) {
			assertEquals(f, Filter.parse(f.id()));
		}
		assertEquals(Filter.agent("kit"), Filter.parse("agent:@kit"));
		assertEquals(Filter.NEEDS_YOU, Filter.parse("Needs you"));
		assertNull(Filter.parse("agent:"));
		assertNull(Filter.parse("nope"));
		assertNull(Filter.parse(null));
	}

	@Test
	void rules() {
		assertTrue(InboxModel.isReply("message", "kit", "user"));
		assertFalse(InboxModel.isReply("message", "user", "kit"), "your own message");
		assertFalse(InboxModel.isReply("message", "kit", "wren"), "between agents");
		assertFalse(InboxModel.isReply("message", "kit", "all"), "a broadcast");
		assertFalse(InboxModel.isReply("task", "kit", "user"));
		assertTrue(InboxModel.prNeedsAttention("pr", "open", "failing", null));
		assertTrue(InboxModel.prNeedsAttention("pr", "changes", "passing", 0));
		assertTrue(InboxModel.prNeedsAttention("pr", "approved", "passing", 2));
		assertFalse(InboxModel.prNeedsAttention("pr", "open", "pending", 0));
		assertFalse(InboxModel.prNeedsAttention("pr", "merged", "failing", 3));
		assertFalse(InboxModel.prNeedsAttention("doing", "open", "failing", 3));
		assertEquals("changes requested · checks failing · 2 new threads", InboxModel.prReason("changes", "failing", 2));
		assertEquals(InboxModel.replyKey("kit", 5, "a"), InboxModel.replyKey("kit", 5, "a"));
		assertFalse(InboxModel.replyKey("kit", 5, "a").equals(InboxModel.replyKey("kit", 5, "b")));
	}

	@Test
	void holdTexts() {
		assertEquals("usage paused until 14:20", InboxModel.holdText(new Hold("usage", 51_600_000L, ""), UTC));
		assertEquals("usage paused", InboxModel.holdText(new Hold("usage", null, ""), UTC));
		assertEquals("login failed: agents paused", InboxModel.holdText(new Hold("auth", null, "x"), UTC));
		assertEquals("Claude offline: retry at 14:20", InboxModel.holdText(new Hold("offline", 51_600_000L, ""), UTC));
		assertEquals("odd", InboxModel.holdText(new Hold("future", null, "odd"), UTC));
		List<String> ex = InboxModel.holdExplain(new Hold("usage", 51_600_000L, "5h window at 100%"), UTC);
		assertTrue(ex.get(1).contains("14:20"));
		assertEquals("Foreman: 5h window at 100%", ex.get(ex.size() - 1));
		assertEquals("2 decisions · 3 replies", new Counts(2, 0, 3, 0, null).line(UTC));
		assertEquals(0, Counts.NONE.needsYou());
	}

	@Test
	void hubSeenInboxMarksPersistAndOldFilesLoad(@TempDir Path dir) throws Exception {
		// a version 1 file (no inbox) loads and keeps its goal marks
		HubSeen old = HubSeen.fromJson(JsonParser.parseString("{\"version\":1,\"worlds\":{\"W\":{\"tab\":5,\"goals\":{\"g1\":7}}}}"));
		assertEquals(7, old.goalSeen("W", "g1"));
		assertFalse(old.inboxRead("W", "d:d1", 1, null, null));
		assertTrue(old.inboxRead("W", "r:kit:6:x", 6, "kit", "g1"), "a reply in a goal opened later counts as read");
		assertFalse(old.inboxRead("W", "r:kit:8:x", 8, "kit", "g1"));
		HubSeen s = new HubSeen();
		long c0 = s.changes();
		s.markInboxItem("W", "d:d1", 100);
		assertTrue(s.changes() > c0);
		s.markAgent("W", "kit", 200);
		s.markInboxAll("W", 50);
		assertTrue(s.inboxRead("W", "d:d1", 100, null, null));
		assertFalse(s.inboxRead("W", "d:d1", 101, null, null), "it happened again after the view");
		assertTrue(s.inboxRead("W", "r:kit:150:x", 150, "kit", null), "the agent card saw it");
		assertTrue(s.inboxRead("W", "t:t4", 40, null, null), "mark all read");
		assertFalse(s.inboxRead("W", "t:t4", 60, null, null));
		s.markInboxAll("W", 10); // never moves back
		assertEquals(50, s.inboxAll("W"));
		// round trip through the file
		Path f = dir.resolve(HubSeen.FILE);
		s.markGoal("W", "g2", 300);
		s.save(f);
		assertTrue(Files.readString(f).contains("\"inbox\""));
		HubSeen back = HubSeen.load(f);
		assertEquals(100, back.inboxItem("W", "d:d1"));
		assertEquals(50, back.inboxAll("W"));
		assertTrue(back.inboxRead("W", "r:kit:150:x", 150, "kit", null));
		assertEquals(300, back.goalSeen("W", "g2"));
		// resetWorld (the Goals tab's dev reset) keeps the inbox; resetInbox forgets it
		back.resetWorld("W", 0);
		assertEquals(50, back.inboxAll("W"));
		back.resetInbox("W");
		assertEquals(0, back.inboxAll("W"));
		assertFalse(back.inboxRead("W", "d:d1", 100, null, null));
		// malformed inbox parts are skipped
		HubSeen bad = HubSeen.fromJson(JsonParser.parseString("{\"worlds\":{\"W\":{\"inbox\":{\"all\":\"x\",\"items\":[1],\"agents\":{\"kit\":-4}}}}}"));
		assertEquals(0, bad.inboxAll("W"));
		assertFalse(bad.inboxRead("W", "k", 1, "kit", null));
	}

	@Test
	void inboxItemMarksAreCapped() {
		HubSeen s = new HubSeen();
		for (int i = 0; i < HubSeen.MAX_INBOX_ITEMS + 10; i++) {
			s.markInboxItem("W", "k" + i, i + 1);
		}
		assertEquals(0, s.inboxItem("W", "k0"), "the oldest-seen are dropped");
		assertEquals(HubSeen.MAX_INBOX_ITEMS + 10, s.inboxItem("W", "k" + (HubSeen.MAX_INBOX_ITEMS + 9)));
	}
}
