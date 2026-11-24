// Beeper push: subscribes to the Desktop API WebSocket (/v1/ws) and publishes one debounced `beeper` event on the
// phone's event stream so chat lists and an open thread refresh within about a second. Probed read-only on Tower
// (2026-11-17): `ready` {version, chatIDs}, then after subscriptions.set -> `subscriptions.updated`, and
// `message.upserted` {chatID, ids, entries, seq, ts} / `chat.upserted` {chatID, ids, seq, ts}. At-most-once and no
// replay, so every (re)connect publishes one reconcile event with empty chatIds. The phone keeps polling as a floor.
import { connectWebSocket } from '../../../lib/ws.mjs';
import { invalidate } from './gateway.mjs';
import { unreadSummary, publishBeeper } from './beeper.mjs';

const TYPES = new Set(['chat.upserted', 'chat.deleted', 'message.upserted', 'message.deleted']);
const summaryKey = (s) => s ? JSON.stringify([s.unread, s.unreadMessages, s.names]) : '';

export function createBeeperLive(ctx, { connect = connectWebSocket, setTimer = setTimeout, clearTimer = clearTimeout, debounceMs = 1000, maxWaitMs = 3000, minGapMs = 3000, onMessages = () => {}, log = () => {} } = {}) {
  let socket = null, stopped = true, retry = null, flushTimer = null, keepalive = null, backoff = 1000;
  let firstPendingAt = 0, lastPublishAt = -Infinity, lastSummary = '', sawMessage = false, reconcile = false;
  const pending = new Set();
  const state = { connected: false, ready: false, connects: 0, events: 0, published: 0, lastError: null };

  async function flush() {
    flushTimer = null;
    const ids = [...pending]; pending.clear();
    const message = sawMessage, forced = reconcile; sawMessage = false; reconcile = false; firstPendingAt = 0;
    let summary = null;
    try { summary = await unreadSummary(ctx); } catch {}
    const key = summaryKey(summary);
    // Pure chat.upserted noise (typing, drafts, read elsewhere with no count change) publishes nothing.
    if (!forced && !message && key === lastSummary) return;
    lastSummary = key; lastPublishAt = ctx.now(); state.published++;
    publishBeeper(ctx, { chatIds: ids.slice(0, 50), ...(summary ? { summary: { unread: summary.unread, unreadMessages: summary.unreadMessages, names: summary.names.slice(0, 10) } } : {}) });
  }
  function schedule() {
    const now = ctx.now();
    if (!firstPendingAt) firstPendingAt = now;
    // Trailing debounce, but never later than maxWait after the first change and never sooner than minGap after the last event.
    const at = Math.max(Math.min(now + debounceMs, firstPendingAt + maxWaitMs), lastPublishAt + minGapMs);
    if (flushTimer) clearTimer(flushTimer);
    flushTimer = setTimer(() => { flush().catch(() => {}); }, Math.max(0, at - now));
    flushTimer?.unref?.();
  }
  /** Marks chats changed (also used by the spam queue when a verdict crosses the threshold). */
  function touch(chatIds = [], { message = false, force = false } = {}) {
    for (const id of chatIds) if (typeof id === 'string' && id) pending.add(id);
    sawMessage ||= message; reconcile ||= force;
    invalidate(ctx, 'beeper:chats');
    schedule();
  }
  function handle(raw) {
    let event;
    try { event = JSON.parse(String(raw)); } catch { return; }
    if (!event || typeof event !== 'object') return;
    if (event.type === 'ready') {
      state.ready = true; backoff = 1000;
      try { socket?.send(JSON.stringify({ type: 'subscriptions.set', chatIDs: ['*'] })); } catch {}
      touch([], { force: true });
      return;
    }
    if (!TYPES.has(event.type)) return;
    state.events++;
    const message = event.type.startsWith('message.') || event.type === 'chat.deleted';
    if (event.type === 'message.upserted') { try { onMessages(event.chatID, event.entries); } catch {} }
    touch(event.chatID ? [event.chatID] : [], { message });
  }
  function reconnect() {
    if (stopped || retry) return;
    const wait = backoff; backoff = Math.min(60000, backoff * 2);
    retry = setTimer(() => { retry = null; open(); }, wait);
    retry?.unref?.();
  }
  async function open() {
    if (stopped) return;
    try {
      const token = await ctx.secret(ctx.config.beeperTokenSecret || 'BEEPER_ACCESS_TOKEN');
      const url = ctx.config.beeperUrl.replace(/^http/, 'ws').replace(/\/$/, '') + '/v1/ws';
      const ws = await connect(url, { headers: { Authorization: 'Bearer ' + token } });
      if (stopped) { try { ws.close(1000, 'stopped'); } catch {} return; }
      socket = ws; state.connected = true; state.connects++; state.lastError = null;
      ws.on('message', (data, binary) => { if (!binary) handle(data); });
      const down = () => {
        if (socket !== ws) return;
        socket = null; state.connected = false; state.ready = false;
        if (keepalive) { clearInterval(keepalive); keepalive = null; }
        reconnect();
      };
      ws.once('close', down); ws.once('error', down);
      keepalive = setInterval(() => { try { ws.ping?.(); } catch {} }, 30000); keepalive.unref?.();
    } catch (e) {
      state.lastError = e?.status ? 'HTTP ' + e.status : 'unreachable';
      log('beeper-live', 'connect failed', state.lastError);
      reconnect();
    }
  }
  return {
    state, touch, handle,
    start() { if (!stopped) return; stopped = false; open(); },
    stop() {
      stopped = true;
      for (const t of [retry, flushTimer]) if (t) clearTimer(t);
      if (keepalive) clearInterval(keepalive);
      retry = flushTimer = keepalive = null;
      try { socket?.close(1000, 'stopped'); } catch {}
      socket = null; state.connected = false;
    },
  };
}
