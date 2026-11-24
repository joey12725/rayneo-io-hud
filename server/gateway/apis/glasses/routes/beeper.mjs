import { readJson } from '../../../lib/web.mjs';
import { text, limit, items, deadline } from '../lib/gateway.mjs';
import { beeper, accounts, chats, visible, network, mine, mapMessage, chatName, resolveSender, messageText, fullChat, unreadSummary, idLike,
  SPAM, spamVerdict, isSpam, counts, isMuted, isGroup, listed, hasUnread, participantSummary, resolveChat, publishBeeper, applyMentions } from '../lib/beeper.mjs';
import { createSpamQueue } from '../lib/beeper-spam.mjs';
import { createBeeperLive } from '../lib/beeper-live.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { PINS, glassesPins, beeperPinned, prioritize, scoreUnread, findChats, confident, cleanQuery, order as listOrder } from '../lib/beeper-priority.mjs';

const enc = encodeURIComponent;
/** Runs `fn` over `values` with at most `n` in flight, preserving order. */
async function mapLimit(values, n, fn) {
  const out = new Array(values.length); let next = 0;
  await Promise.all(Array.from({ length: Math.min(n, values.length) }, async () => { while (next < values.length) { const i = next++; out[i] = await fn(values[i], i); } }));
  return out;
}
const str = (value, max) => typeof value === 'string' ? value.slice(0, max) : undefined;
export function register(r, ctx) {
  // Push from Beeper's WebSocket and background spam classification run only in the worker process (production),
  // or when switched on explicitly; tests leave both off unless they opt in.
  const live = ctx.config.beeperLive === true ? createBeeperLive(ctx, { onMessages: (id, entries) => ctx.beeperSpam?.onMessages(id, entries), log: (...a) => ctx.log?.(...a) }) : null;
  ctx.beeperLive = live;
  ctx.beeperSpam = createSpamQueue(ctx, { onChange: ids => live ? live.touch(ids, { force: true }) : publishBeeper(ctx, { chatIds: ids }) });
  live?.start();

  r.get('/beeper/summary', () => unreadSummary(ctx));
  /** One list row as the phone reads it (also used by /beeper/find). */
  async function row(raw, accts, extra = {}) {
    const c = raw.type === 'group' && (raw.participants?.hasMore || idLike(raw.preview?.senderName, raw.preview?.senderID)) ? await fullChat(ctx, raw) : raw;
    const verdict = spamVerdict(ctx, c), spam = isSpam(ctx, c, verdict);
    return { id: c.id, name: chatName(c), network: network(c, accts), unread: c.unreadCount ?? 0,
      last: { sender: mine(c.preview, c, accts) ? 'You' : await resolveSender(ctx, c.preview, c), text: messageText(c.preview, c), mine: mine(c.preview, c, accts) },
      type: isGroup(c) ? 'group' : 'single', isGroup: isGroup(c), muted: isMuted(c), mentions: c.unreadMentionsCount ?? 0, markedUnread: c.isMarkedUnread === true,
      lowPriority: !visible(c) && listed(c), spam, spamConfidence: verdict && verdict.source !== 'user' ? verdict.confidence ?? 0 : spam ? 1 : 0, spamCategory: verdict?.category ?? '',
      spamSource: verdict?.source ?? '', counts: counts(ctx, c), participants: participantSummary(c), lastActivity: c.lastActivity ?? '', ...extra };
  }
  r.get('/beeper/chats', async ({ query }) => {
    const [all, accts] = await Promise.all([chats(ctx), accounts(ctx)]);
    // order=priority (default): pins, important unread, other unread, quiet unread (muted, spam, low priority), then the
    // rest, each by recency. order=recent: pins, then counting unread, then the rest (the BEEP-01/02 order). Counting
    // unread chats always precede the rest, so the list and its rail total never miss them. Each row names its tier
    // so the phone can group it (and collapse the quiet ones) without re-deriving it.
    const shown = all.filter(c => query.includeArchived === 'true' || listed(c));
    const mode = listOrder(query.order);
    // Recent ignores importance, so it reads no message history.
    const scores = mode === 'priority' ? await scoreUnread(ctx, shown).catch(() => new Map()) : new Map();
    const pins = new Set(glassesPins(ctx).map(p => p.id));
    const ordered = prioritize(ctx, shown, scores, mode);
    const offset = Math.max(0, Math.min(10000, Math.floor(Number(query.offset) || 0)));
    const page = ordered.slice(offset, offset + limit(query.limit, 100, 500));
    for (const { c } of page) ctx.beeperSpam.offer(c, accts);
    // Fetch the full participant list only for groups whose embedded list is truncated or whose preview sender is
    // unresolved, at most six upstream calls at a time; one upstream call per row would be far too many.
    return mapLimit(page, 6, ({ c, tier }) => row(c, accts, { priority: tier, importance: scores.get(c.id)?.score ?? 0, why: scores.get(c.id)?.why ?? [],
      pinned: beeperPinned(c), glassesPinned: pins.has(c.id) }));
  });
  // Spoken "find a chat": fuzzy match on titles and participant names over every listed chat (not just the phone's page).
  r.get('/beeper/find', async ({ query }) => {
    const q = text(query.q, 'q', 200);
    const [all, accts] = await Promise.all([chats(ctx), accounts(ctx)]);
    const matches = findChats(ctx, all.filter(listed), q, { max: limit(query.limit, 5, 10) });
    const pins = new Set(glassesPins(ctx).map(p => p.id));
    const rows = await mapLimit(matches, 4, m => row(m.chat, accts, { pinned: beeperPinned(m.chat), glassesPinned: pins.has(m.chat.id), match: { score: m.score, on: m.on } }));
    return { query: cleanQuery(q), confident: confident(matches), matches: rows };
  });
  // Glasses pins keep a chat at the top of the glasses list. Stored only in the gateway (the assistant reads them); never PATCHed upstream.
  r.get('/beeper/pins', () => glassesPins(ctx).sort((a, b) => String(a.pinnedAt).localeCompare(String(b.pinnedAt))));
  r.post('/beeper/chats/:chatId/pin', async ({ req, params }) => {
    const b = await readJson(req);
    if (typeof b?.pinned !== 'boolean') throw new HttpError(400, 'pinned must be true or false');
    const id = text(params.chatId, 'chatId', 1000);
    if (b.pinned) {
      const existing = ctx.store.get(PINS, id);
      if (!existing && glassesPins(ctx).length >= 50) throw new HttpError(400, 'Too many pins');
      const known = (await chats(ctx).catch(() => [])).find(c => c.id === id);
      ctx.store.put(PINS, { id, name: known ? chatName(known) : str(b.name, 200) ?? '', pinnedAt: existing?.pinnedAt ?? new Date(ctx.now()).toISOString() });
    } else ctx.store.del(PINS, id);
    await ctx.store.flushed();
    publishBeeper(ctx, { chatIds: [id] });
    return { ok: true, id, pinned: b.pinned };
  });
  r.del('/beeper/chats/:chatId/pin', async ({ params }) => {
    const id = text(params.chatId, 'chatId', 1000);
    if (ctx.store.del(PINS, id)) { await ctx.store.flushed(); publishBeeper(ctx, { chatIds: [id] }); }
    return { ok: true, id, pinned: false };
  });
  // Notification pipeline hook: resolves a Beeper phone notification (conversation title, sender, text) to a chat so the
  // notifier can deep-link {app:"beeper", target: chatId} and apply mute/spam rules. Bounded to 1.5 s; fails open.
  r.post('/beeper/resolve', async ({ req }) => {
    const b = await readJson(req);
    if (!b || typeof b !== 'object' || Array.isArray(b)) throw new HttpError(400, 'Notification object required');
    const input = { title: str(b.title, 200), conversationTitle: str(b.conversationTitle, 200), sender: str(b.sender, 200), text: str(b.text ?? b.body, 1000), network: str(b.network, 100),
      ...(typeof (b.isGroup ?? b.isGroupConversation) === 'boolean' ? { isGroup: b.isGroup ?? b.isGroupConversation } : {}) };
    let timer;
    const timeout = new Promise(resolve => { timer = setTimeout(() => resolve({ resolved: false, reason: 'timeout', candidates: 0 }), ctx.config.resolveTimeoutMs ?? 1500); });
    try { return await Promise.race([resolveChat(ctx, input).catch(() => ({ resolved: false, reason: 'unavailable', candidates: 0 })), timeout]); }
    finally { clearTimeout(timer); }
  });
  // Per-chat spam override from the glasses (thread X menu). Stored only in the gateway; never written upstream.
  r.get('/beeper/spam', () => ctx.store.all(SPAM).sort((a, b) => String(b.classifiedAt).localeCompare(String(a.classifiedAt))));
  r.post('/beeper/chats/:chatId/spam', async ({ req, params }) => {
    const b = await readJson(req);
    if (typeof b?.spam !== 'boolean') throw new HttpError(400, 'spam must be true or false');
    const id = text(params.chatId, 'chatId', 1000);
    const verdict = ctx.store.put(SPAM, { id, verdict: b.spam ? 'spam' : 'ham', confidence: 1, category: 'user', reason: b.spam ? 'Marked spam by the owner' : 'Marked not spam by the owner', source: 'user', classifiedAt: new Date(ctx.now()).toISOString() });
    await ctx.store.flushed();
    publishBeeper(ctx, { chatIds: [id] });
    return { ok: true, id, spam: b.spam, verdict };
  });
  r.del('/beeper/chats/:chatId/spam', async ({ params }) => {
    const id = text(params.chatId, 'chatId', 1000);
    if (ctx.store.del(SPAM, id)) { await ctx.store.flushed(); publishBeeper(ctx, { chatIds: [id] }); }
    return { ok: true };
  });
  r.get('/beeper/chats/:chatId/messages', async ({ params, query }) => {
    const count = limit(query.limit, 30, 200), path = '/chats/' + enc(params.chatId) + '/messages';
    const budget = deadline(ctx, ctx.config.paginationBudgetMs ?? 15000);
    try {
    const before = query.before ? text(query.before, 'before', 4000) : null;
    const [response, chat, accts] = await budget.run(() => Promise.all([beeper(ctx, path + (before ? '?direction=before&cursor=' + enc(before) : ''), 'GET', undefined, { signal: budget.signal }), fullChat(ctx, { id: params.chatId }), accounts(ctx)]));
    let page = response;
    const messages = [...items(response)], cursors = new Set(before ? [before] : []);
    while (messages.length < count && page.hasMore && page.oldestCursor && !cursors.has(page.oldestCursor)) {
      cursors.add(page.oldestCursor);
      try { page = await budget.run(() => beeper(ctx, path + '?direction=before&cursor=' + enc(page.oldestCursor), 'GET', undefined, { signal: budget.signal })); }
      catch { break; }
      messages.push(...items(page));
      if (cursors.size >= 20) break;
    }
    const all = [...new Map(messages.map(m => [m.id, m])).values()].filter(m => !m.isHidden && !m.isDeleted).sort((a, b) => Date.parse(a.timestamp || 0) - Date.parse(b.timestamp || 0));
    // Opaque upstream cursors cover a whole page: keep every fetched message in paged mode,
    // otherwise slicing here would skip the older half of a page on loadOlder().
    const mapped = (query.page === 'true' ? all : all.slice(-count)).map(m => mapMessage(m, chat, accts, all, ctx));
    return query.page === 'true' ? { messages: mapped, nextBefore: page.hasMore && mapped.length && page.oldestCursor !== before ? page.oldestCursor ?? null : null, loaded: true } : mapped;
    } finally { budget.close(); }
  });
  r.post('/beeper/chats/:chatId/messages', async ({ req, params }) => {
    const b = await readJson(req);
    let body = text(b.text);
    // Spoken mentions from dictation: [{token: "@Zephyr", id: <participant id>}], converted to the platform form.
    const mentions = (Array.isArray(b.mentions) ? b.mentions : []).slice(0, 20).filter(m => typeof m?.token === 'string' && typeof m?.id === 'string' && m.token.length <= 100 && m.id.length <= 300);
    if (mentions.length) {
      // A failed chat lookup must not lose the message: it goes out with plain "@Name" tokens.
      try {
        const [chat, accts] = await Promise.all([fullChat(ctx, { id: params.chatId }), accounts(ctx).catch(() => [])]);
        body = applyMentions(body, mentions, chat, { mode: ctx.config.beeperMentions ?? 'pill', network: network(chat ?? {}, accts) }).text;
      } catch { ctx.log?.('beeper mentions skipped: chat lookup failed'); }
    }
    const result = await beeper(ctx, '/chats/' + enc(params.chatId) + '/messages', 'POST', { text: body, ...(b.replyTo ? { replyToMessageID: text(b.replyTo, 'replyTo', 1000) } : {}) });
    return { id: result.pendingMessageID ?? result.id ?? result.messageID };
  });
  const path = '/beeper/chats/:chatId/messages/:messageId/reactions';
  const upstream = (p) => '/chats/' + enc(p.chatId) + '/messages/' + enc(p.messageId) + '/reactions';
  r.post(path, async ({ req, params }) => { const b = await readJson(req); await beeper(ctx, upstream(params), 'POST', { reactionKey: text(b.key, 'key', 200) }); return { ok: true }; });
  r.del(path, async ({ params, query }) => { await beeper(ctx, upstream(params) + '/' + enc(text(query.key, 'key', 200)), 'DELETE'); return { ok: true }; });
  r.post('/beeper/chats/:chatId/read', async ({ params }) => { await beeper(ctx, '/chats/' + enc(params.chatId) + '/read', 'POST', {}); return { ok: true }; });
}
