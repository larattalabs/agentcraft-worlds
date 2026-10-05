// MessageBus: agent<->agent, agent<->user messages, plus the activity feed.
import type { Ctx } from './context.js';
import type { FeedItem, FeedKind } from './protocol.js';
import type { BusMessage } from './store.js';
import { truncate } from './util/text.js';
import { userName } from './user.js';

export interface FeedExtra {
  agentId?: string;
  to?: string;
  goalId?: string;
  /** hint: tag the item with this task's goal */
  taskId?: string;
}

/** feed text limit of a goal-tagged message (other feed items: 400) */
export const GOAL_MESSAGE_MAX = 2000;

export class MessageBus {
  private listeners: Array<(m: BusMessage) => void> = [];

  constructor(private ctx: Ctx) {}

  /**
   * Append to the activity feed (persisted + broadcast). `goalId` tags the item with its goal;
   * `taskId` is a hint only (not sent): the item gets that task's goal.
   */
  feed(kind: FeedKind, text: string, extra: FeedExtra = {}): FeedItem {
    // a goal's thread (goal messages and the lead's replies) keeps longer messages
    const max = kind === 'message' && this.goalOf(extra) ? GOAL_MESSAGE_MAX : 400;
    const item: FeedItem = { ts: this.ctx.now(), kind, text: truncate(text, max) };
    if (extra.agentId) item.agentId = extra.agentId;
    if (extra.to) item.to = extra.to;
    const goalId = this.goalOf(extra);
    if (goalId) item.goalId = goalId;
    this.ctx.store.pushFeed(item);
    this.ctx.emit({ type: 'feed.add', item });
    return item;
  }

  /** The goal a feed item / message is about: explicit, else the hinted task's goal. */
  goalOf(extra: { goalId?: string; taskId?: string }): string | undefined {
    if (extra.goalId) return extra.goalId;
    if (!extra.taskId) return undefined;
    return this.ctx.store.data.tasks.find((t) => t.id === extra.taskId)?.goalId;
  }

  /**
   * Send a message. `from` is an agent id or "user"; `to` is an agent id, "user" or "all".
   * Agents speak through `agent.say` (speech bubble); every message is also a feed item, tagged
   * with its goal when it is about one. `goalMessage`: the user's message to a goal's lead
   * (goal.message): feed kind `message` from "user", and it stays out of the lead's ordinary inbox
   * (it runs as its own turn for that goal, see goalInbox).
   */
  send(from: string, to: string, text: string, opts: { goalId?: string; taskId?: string; goalMessage?: boolean; feedText?: string } = {}): BusMessage {
    const goalId = this.goalOf(opts);
    const msg: BusMessage = { id: this.ctx.store.nextId('m'), ts: this.ctx.now(), from, to, text, readBy: [], ...(goalId ? { goalId } : {}), ...(opts.goalMessage && goalId ? { goalMessage: true } : {}) };
    this.ctx.store.pushMessage(msg);
    if (from !== 'user') {
      const say = { type: 'agent.say' as const, agentId: from, text: truncate(text, 600), ts: msg.ts, ...(to ? { to } : {}) };
      this.ctx.emit(say);
      this.feed('message', text, { agentId: from, to, ...(goalId ? { goalId } : {}) });
    } else if (msg.goalMessage) {
      // feedText: what the player's thread shows when the lead's text is a longer prompt (plan/instruction edits)
      this.feed('message', opts.feedText ?? text, { agentId: 'user', to, goalId });
    } else {
      this.feed('user', text, { to, ...(goalId ? { goalId } : {}) });
    }
    for (const l of this.listeners) l(msg);
    return msg;
  }

  onMessage(listener: (m: BusMessage) => void): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  /** Unread messages addressed to `agentId` (directly or via "all"), oldest first. */
  inbox(agentId: string, opts: { markRead?: boolean } = {}): BusMessage[] {
    const out = this.ctx.store.data.messages.filter(
      (m) => !m.goalMessage && m.from !== agentId && (m.to === agentId || m.to === 'all') && !m.readBy.includes(agentId),
    );
    if (opts.markRead && out.length) {
      for (const m of out) m.readBy.push(agentId);
      this.ctx.store.markDirty();
    }
    return out;
  }

  /** Unread goal messages (goal.message) to `agentId`, oldest first; optionally for one goal. */
  goalInbox(agentId: string, goalId?: string): BusMessage[] {
    return this.ctx.store.data.messages.filter((m) => m.goalMessage && m.to === agentId && !m.readBy.includes(agentId) && (!goalId || m.goalId === goalId));
  }

  /** Mark specific messages as consumed by `agentId`. */
  markRead(agentId: string, ids: string[]): void {
    let changed = false;
    for (const m of this.ctx.store.data.messages) {
      if (ids.includes(m.id) && !m.readBy.includes(agentId)) {
        m.readBy.push(agentId);
        changed = true;
      }
    }
    if (changed) this.ctx.store.markDirty();
  }

  /** Undo markRead (a turn that was to answer them never ran: they are offered again). */
  markUnread(agentId: string, ids: string[]): void {
    let changed = false;
    for (const m of this.ctx.store.data.messages) {
      if (ids.includes(m.id) && m.readBy.includes(agentId)) {
        m.readBy = m.readBy.filter((r) => r !== agentId);
        changed = true;
      }
    }
    if (changed) this.ctx.store.markDirty();
  }

  /** Recent conversation involving an agent (for prompts). */
  history(agentId: string, limit = 20): BusMessage[] {
    return this.ctx.store.data.messages
      .filter((m) => m.from === agentId || m.to === agentId || m.to === 'all')
      .slice(-limit);
  }
}

/** Format inbox messages for injection into an agent prompt / tool result. */
export function formatInbox(msgs: BusMessage[], nameOf: (id: string) => string): string {
  return msgs
    .map((m) => `- from ${m.from === 'user' ? `the user (${userName()})` : nameOf(m.from)}${m.to === 'all' ? ' to everyone' : ''}: ${m.text}`)
    .join('\n');
}
