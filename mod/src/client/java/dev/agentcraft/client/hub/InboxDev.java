package dev.agentcraft.client.hub;

import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.FrameScheduler;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.hub.InboxModel;
import dev.agentcraft.hub.InboxModel.Filter;
import dev.agentcraft.hub.InboxModel.Item;
import dev.agentcraft.hub.InboxModel.Kind;
import dev.agentcraft.hub.InboxModel.Row;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * DevBridge for the hub Inbox (mod/DEV.md "Inbox"): {@code dev.inbox.state}, {@code dev.agent.log}, the
 * {@code inbox_*} actions of {@code dev.hub.action} (one per control), {@code dev.hub.open {tab:"inbox", filter?, item?,
 * agentId?}} and the screens {@code hub_inbox} / {@code hub_inbox_<kind>}.
 */
final class InboxDev {
	static final String ACTIONS = "inbox_filter|inbox_select|inbox_back|inbox_mark_all_read|inbox_answer|inbox_answer_press|inbox_answer_text|inbox_reply|"
		+ "inbox_retry|inbox_open_task|inbox_open_card|inbox_open_thread|inbox_open_tasks|inbox_open_diff|inbox_open_decision|inbox_refresh_prs|"
		+ "inbox_log_older|inbox_log_scroll|inbox_detail_scroll|inbox_focus|inbox_reset_read";
	private static final Set<String> NAMES = Set.of(ACTIONS.split("\\|"));

	private InboxDev() {
	}

	static boolean handles(String action) {
		return NAMES.contains(action);
	}

	/** dev.hub.open {tab:"inbox"}: filter (or agentId: the agent view), then the item. Client thread. */
	static void open(HubScreen s, @Nullable String filter, @Nullable String item, @Nullable String agentId) {
		if (filter != null) {
			Filter f = Filter.parse(filter);
			if (f == null) {
				throw new DevBridge.DevException("filter must be all, needs_you, building:<id>, agent:<id>, podium:<id|home>");
			}
			s.inbox.setFilter(f);
		}
		if (agentId != null) {
			s.inbox.setFilter(Filter.agent(agentId));
			s.inbox.select("agent:" + agentId);
		}
		if (item != null) {
			String key = resolve(item);
			if (Inbox.rows(Inbox.filter()).stream().noneMatch(r -> r.item().key().equals(key))) {
				s.inbox.setFilter(Filter.ALL);
				if (Inbox.rows(Filter.ALL).stream().noneMatch(r -> r.item().key().equals(key))) {
					throw new DevBridge.DevException("item: no inbox item " + item + " (see dev.inbox.state items[].key)");
				}
			}
			s.inbox.select(key);
		}
	}

	/** An item key, or a bare decision / task id ("d4" -> "d:d4", "t5" -> the blocked or PR item of t5). */
	static String resolve(String item) {
		if (item.contains(":") || item.equals("hold")) {
			return item;
		}
		for (Row r : Inbox.rows(Filter.ALL)) {
			if (item.equals(r.item().refId())) {
				return r.item().key();
			}
		}
		return item;
	}

	private static InboxTab tab(HubScreen s) {
		s.setTab(HubTab.INBOX);
		return s.inbox;
	}

	private static Item item(InboxTab t, Fields f) {
		String key = f.optStr("item", null);
		if (key != null) {
			String k = resolve(key);
			if (Inbox.rows(Inbox.filter()).stream().noneMatch(r -> r.item().key().equals(k))) {
				t.setFilter(Filter.ALL);
			}
			t.select(k);
		}
		Item it = t.currentItem();
		if (it == null) {
			throw new DevBridge.DevException("item: which item? (none is selected; see dev.inbox.state items[].key)");
		}
		return it;
	}

	private static CompletableFuture<JsonObject> done(String action, @Nullable String message) {
		JsonObject o = new JsonObject();
		o.addProperty("action", action);
		o.addProperty("message", message);
		return CompletableFuture.completedFuture(o);
	}

	private static CompletableFuture<JsonObject> after(String action, CompletableFuture<String> f) {
		return f.thenApply(m -> {
			JsonObject o = new JsonObject();
			o.addProperty("action", action);
			o.addProperty("ok", m != null && m.startsWith(UiBits.CHECK));
			o.addProperty("message", m);
			return o;
		});
	}

	/** dev.hub.action inbox_*. Client thread. */
	static CompletableFuture<JsonObject> act(Minecraft mc, HubScreen s, String action, Fields f) {
		InboxTab t = tab(s);
		switch (action) {
			case "inbox_filter" -> {
				Filter fl = Filter.parse(f.nonBlank("filter"));
				if (fl == null) {
					throw new DevBridge.DevException("filter must be all, needs_you, building:<id>, agent:<id>, podium:<id|home>");
				}
				t.setFilter(fl);
				if (fl.type() == InboxModel.FilterType.AGENT) {
					t.select("agent:" + fl.arg());
				}
				return done(action, "filter " + fl.id());
			}
			case "inbox_select" -> {
				Item it = item(t, f);
				return done(action, "selected " + it.key());
			}
			case "inbox_back" -> {
				t.back();
				return done(action, "back to the list");
			}
			case "inbox_mark_all_read" -> {
				Inbox.markAllRead();
				return done(action, "everything is read");
			}
			case "inbox_reset_read" -> {
				Inbox.resetRead();
				return done(action, "this world's inbox marks are forgotten: everything is unread");
			}
			case "inbox_answer", "inbox_answer_text" -> {
				Item it = item(t, f);
				Decision d = InboxTab.decisionOf(it);
				if (d == null) {
					throw new DevBridge.DevException("item " + it.key() + " is not a decision in the Foreman's list");
				}
				String option = action.equals("inbox_answer_text") ? null : f.optStr("option", null);
				String text = f.optStr("text", null);
				if (option != null && !d.options().contains(option)) {
					throw new DevBridge.DevException("option must be one of " + d.options());
				}
				if (option == null && text == null) {
					throw new DevBridge.DevException("option (one of " + d.options() + ") or text is required");
				}
				var p = t.panel();
				p.bind(d, false);
				p.armNow(); // the DevBridge does not wait out the arm delay (a confirm in progress is kept)
				if (text != null) {
					p.setText(text);
				}
				boolean sent = option != null ? (option.equals(dev.agentcraft.client.foreman.Protocol.REQUEST_CHANGES) && text != null
					? p.chooseWith(d, option, text) : p.choose(d, option)) : p.sendText(d);
				JsonObject o = new JsonObject();
				o.addProperty("action", action);
				o.addProperty("sent", sent);
				o.addProperty("note", p.note());
				o.add("panel", p.state());
				return CompletableFuture.completedFuture(o);
			}
			case "inbox_answer_press" -> {
				Item it = item(t, f);
				Decision d = InboxTab.decisionOf(it);
				String id = f.nonBlank("button");
				t.panel().armNow();
				if (d == null || !t.panel().press(d, id)) {
					throw new DevBridge.DevException("button: no answer button '" + id + "' was drawn last frame (see dev.inbox.state panel.buttons)");
				}
				JsonObject o = new JsonObject();
				o.addProperty("action", action);
				o.add("panel", t.panel().state());
				return CompletableFuture.completedFuture(o);
			}
			case "inbox_reply" -> {
				Item it = item(t, f);
				if (it.kind() != Kind.REPLY && it.kind() != Kind.AGENT) {
					throw new DevBridge.DevException("inbox_reply needs a reply or an agent view (item " + it.key() + " is a " + it.kind().id() + ")");
				}
				if (f.has("text")) {
					t.replyField().set(f.str("text"));
				}
				return after(action, t.sendReply(it));
			}
			case "inbox_retry" -> {
				Item it = item(t, f);
				if (it.kind() != Kind.BLOCKED || it.refId() == null) {
					throw new DevBridge.DevException("inbox_retry needs a blocked task (item " + it.key() + ")");
				}
				return after(action, t.retry(it.refId()));
			}
			case "inbox_open_task" -> {
				Item it = item(t, f);
				if (it.refId() == null || it.kind() != Kind.BLOCKED && it.kind() != Kind.PR) {
					throw new DevBridge.DevException("inbox_open_task needs a blocked task or a PR");
				}
				t.openTask(it.refId());
				return done(action, "task " + it.refId());
			}
			case "inbox_open_card" -> {
				Item it = item(t, f);
				return done(action, t.openCard(it.agentId()) ? "card of " + it.agentId() : t.note());
			}
			case "inbox_open_thread" -> {
				t.openThread(item(t, f));
				return done(action, t.note());
			}
			case "inbox_open_tasks" -> {
				t.openGoalTasks(item(t, f));
				return done(action, t.note());
			}
			case "inbox_open_diff" -> {
				Item it = item(t, f);
				Decision d = InboxTab.decisionOf(it);
				if (d == null || d.kind() != dev.agentcraft.client.foreman.Protocol.DecisionKind.MERGE) {
					throw new DevBridge.DevException("inbox_open_diff needs a merge decision");
				}
				t.panel().reviewDiff(d);
				return done(action, mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
			}
			case "inbox_open_decision" -> {
				Item it = item(t, f);
				if (it.kind() != Kind.DECISION || it.refId() == null) {
					throw new DevBridge.DevException("inbox_open_decision needs a decision");
				}
				t.openDecisionScreen(it.refId());
				return done(action, "decision screen at " + it.refId());
			}
			case "inbox_refresh_prs" -> {
				return after(action, t.refreshPrs());
			}
			case "inbox_log_older" -> {
				Item it = item(t, f);
				if (it.kind() != Kind.AGENT || it.agentId() == null) {
					throw new DevBridge.DevException("inbox_log_older needs the agent view (filter agent:<id>)");
				}
				AgentLogView lv = t.log(it.agentId());
				return lv.start().thenCompose(x -> lv.loadOlder()).thenApply(x -> {
					JsonObject o = new JsonObject();
					o.addProperty("action", action);
					o.add("log", lv.state(0, 0));
					return o;
				});
			}
			case "inbox_log_scroll" -> {
				int rows = (int) f.integer("rows", -100_000, 100_000);
				t.scrollLog(rows);
				return done(action, "scrolled " + rows);
			}
			case "inbox_detail_scroll" -> {
				// a detail that does not fit flows (layout.detail.flow): its whole column scrolls; rows are text lines
				int rows = (int) f.integer("rows", -100_000, 100_000);
				t.scrollDetail(rows);
				return done(action, "scrolled the detail " + rows + " lines (see layout.detail.offset/max after a frame)");
			}
			case "inbox_focus" -> {
				String field = f.nonBlank("field");
				Item it = item(t, f);
				if (field.equals("reply")) {
					if (it.kind() != Kind.REPLY && it.kind() != Kind.AGENT) {
						throw new DevBridge.DevException("reply: the item has no reply box (a reply or the agent view)");
					}
					t.focusReply();
				} else if (field.equals("answer")) {
					Decision d = InboxTab.decisionOf(it);
					if (d == null) {
						throw new DevBridge.DevException("answer: the item is not a decision");
					}
					t.panel().focusText(true);
				} else {
					throw new DevBridge.DevException("field must be reply or answer");
				}
				return done(action, "focus " + t.focus());
			}
			default -> throw new DevBridge.DevException("unknown inbox action " + action);
		}
	}

	static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		HubScreen s = mc.gui.screen() instanceof HubScreen h ? h : null;
		o.addProperty("open", s != null && s.tab() == HubTab.INBOX);
		InboxModel.Counts c = Inbox.counts();
		JsonObject counts = new JsonObject();
		counts.addProperty("decisions", c.decisions());
		counts.addProperty("blocked", c.blocked());
		counts.addProperty("replies", c.replies());
		counts.addProperty("prs", c.prs());
		counts.addProperty("hold", c.hold() == null ? null : InboxModel.holdText(c.hold(), ZoneId.systemDefault()));
		counts.addProperty("needsYou", c.needsYou());
		counts.addProperty("line", c.line(ZoneId.systemDefault()));
		o.add("counts", counts);
		o.addProperty("revision", Inbox.revision());
		o.addProperty("filter", Inbox.filter().id());
		if (s != null && s.tab() == HubTab.INBOX) {
			JsonObject t = s.inbox.state();
			for (var e : t.entrySet()) {
				o.add(e.getKey(), e.getValue());
			}
		} else {
			com.google.gson.JsonArray items = new com.google.gson.JsonArray();
			for (Row r : Inbox.rows(Inbox.filter())) {
				items.add(InboxTab.rowJson(r));
			}
			o.add("items", items);
		}
		return o;
	}

	static void register() {
		DevBridge.register("dev.inbox.state", 10_000, "{} - the hub Inbox: counts (the HUD line's), filter, items[] (key, kind, group, unread, ...), and "
			+ "while the Inbox tab is open its selection, detail item, answer panel, agent log and layout", (req, mc) -> DevBridge.onClient(mc, () -> state(
				mc)));
		DevBridge.register("dev.agent.log", 20_000, "{agentId, older?: bool} - opens the Inbox's agent view (card summary + full log) and replies once "
			+ "the first page (or with older:true the page before) is in: {log{entries, fetched, pages, more, error, ...}}", (req, mc) -> {
				Fields f = Fields.of(req);
				String agentId = f.nonBlank("agentId");
				boolean older = f.optBool("older", false);
				return DevBridge.onClient(mc, () -> {
					if (mc.player == null) {
						throw new DevBridge.DevException("not in a world");
					}
					HubScreen s = Inbox.openAgent(agentId);
					AgentLogView lv = s.inbox.log(agentId);
					CompletableFuture<Void> first = lv.start();
					return older ? first.thenCompose(x -> lv.loadOlder()).thenApply(x -> lv) : first.thenApply(x -> lv);
				}).thenCompose(x -> x).thenCompose(lv -> FrameScheduler.afterFrames(2).thenApply(x -> lv)).thenCompose(lv -> DevBridge.onClient(mc,
					() -> {
						JsonObject o = new JsonObject();
						o.add("log", lv.state(0, 0));
						o.add("inbox", state(mc));
						return o;
					}));
			});
		DevBridge.register("dev.monitor.open", 10_000, "{x, y, z} - a monitor's right-click: the Inbox's view of the agent that panel shows (the feed "
			+ "monitor: the Inbox); replies {agent, inbox state}", (req, mc) -> {
				Fields f = Fields.of(req);
				int x = (int) f.integer("x", -30_000_000, 30_000_000);
				int y = (int) f.integer("y", -2048, 2048);
				int z = (int) f.integer("z", -30_000_000, 30_000_000);
				return DevBridge.onClient(mc, () -> {
					if (mc.level == null) {
						throw new DevBridge.DevException("not in a world");
					}
					String agent = dev.agentcraft.client.monitor.MonitorFeature.agentAt(mc.level, new net.minecraft.core.BlockPos(x, y, z));
					if (agent == null) {
						throw new DevBridge.DevException("no monitor at " + x + " " + y + " " + z);
					}
					if (agent.equals("feed")) {
						Inbox.open(null);
					} else {
						Inbox.openAgent(agent);
					}
					JsonObject o = state(mc);
					o.addProperty("agent", agent);
					return o;
				});
			});
		for (Kind k : Kind.values()) {
			// the newest item of that kind selected (the agent view: whoever has the newest item, else the first agent)
			DevBridge.registerScreen("hub_inbox_" + k.id(), mc -> {
				HubScreen s = new HubScreen(HubTab.INBOX);
				if (k == Kind.AGENT) {
					List<String> agents = Inbox.agentsForFilter();
					if (!agents.isEmpty()) {
						s.inbox.setFilter(Filter.agent(agents.get(0)));
						s.inbox.select("agent:" + agents.get(0));
					}
					return s;
				}
				s.inbox.setFilter(Filter.ALL);
				Item best = null;
				for (Row r : Inbox.rows(Filter.ALL)) {
					if (r.item().kind() == k && (best == null || r.item().ts() > best.ts())) {
						best = r.item();
					}
				}
				if (best != null) {
					s.inbox.select(best.key());
				}
				return s;
			});
		}
	}
}
