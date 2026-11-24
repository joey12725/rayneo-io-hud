// Conversation context for the notification classifier (POST /notify/route, POST /notify/hermes, the T3 notifier).
//
// A turn is a maximal run of consecutive messages by one speaker. The classifier sees up to MAX_TURNS turns before the target
// message, only those whose last message is within TURN_WINDOW_MS before it; a conversation opener gets none. Every
// message carries its timestamp, and for a message that was itself routed, the route it got ("already notified").
//
// Sources: Beeper context is built here from the Beeper Desktop API (the chat comes from resolveChat) plus the
// per-chat store of recent decisions (DECISIONS, pruned after 4 h). T3 context comes from the T3 notifier, Hermes
// context from the glasses-notify Hermes plugin. An eval may supply `context` directly, but only with debug.
//
// guard(): deterministic checks applied to the model's answer (its prompt says the same; the guard makes it hold).
//
// Relationship stats (relationshipFor / relationshipStats): how much the owner talks with a Beeper chat, by volume only
// (who matters changes over time, so names never enter the rules or the prompt): messages in the last 30 days, the
// share the owner wrote, when they last wrote, and a tier. Computed from a few pages of history and cached per chat.
import { beeper as beeperApi, messageText, sender as senderName, mine } from './beeper.mjs';
import { ownerName } from './persona.mjs';
import { items } from './gateway.mjs';
import { RX } from './notify-policy.mjs';

export const TURN_WINDOW_MS = 4 * 3600000;
export const MAX_TURNS = 5;
export const DECISIONS = 'notify-decisions';
const MAX_DECISIONS = 60;
const clip = (s, n) => { s = String(s ?? '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
const ms = (v) => typeof v === 'number' ? v : Date.parse(v ?? '');

/**
 * `messages`: oldest first, each {id?, at, text, speaker, isOwner, priorRoute?, priorImportance?}. Returns the turns before
 * `targetIndex` (or before the end when targetIndex === messages.length), oldest first:
 * [{speaker, isOwner, messages: [{id?, at, text, priorRoute?, priorImportance?}]}].
 */
export function previousTurns(messages, targetIndex, { targetAt, maxTurns = MAX_TURNS, windowMs = TURN_WINDOW_MS, maxMessagesPerTurn = 8, maxChars = 400 } = {}) {
  const t = ms(targetAt ?? messages[targetIndex]?.at);
  const turns = [];
  let cur = null;
  for (let j = targetIndex - 1; j >= 0; j--) {
    const m = messages[j];
    const at = ms(m.at);
    if (!cur || cur.speaker !== m.speaker || cur.isOwner !== Boolean(m.isOwner) || (cur.speakerId ?? null) !== (m.speakerId ?? null)) {
      if (turns.length >= maxTurns) break;
      if (Number.isFinite(t) && Number.isFinite(at) && t - at > windowMs) break;
      cur = { speaker: m.speaker, isOwner: Boolean(m.isOwner), ...(m.speakerId ? { speakerId: m.speakerId } : {}), messages: [] };
      turns.push(cur);
    }
    cur.messages.push({ ...(m.id !== undefined ? { id: String(m.id) } : {}), at: m.at, text: clip(m.text, maxChars),
      ...(m.priorRoute ? { priorRoute: m.priorRoute } : {}), ...(typeof m.priorImportance === 'number' ? { priorImportance: m.priorImportance } : {}) });
  }
  for (const turn of turns) {
    turn.messages.reverse();
    if (turn.messages.length > maxMessagesPerTurn) { turn.omitted = turn.messages.length - maxMessagesPerTurn; turn.messages = turn.messages.slice(-maxMessagesPerTurn); }
  }
  return turns.reverse();
}

// ---- Per-chat decision store ----

/** Recent decisions of one conversation ("beeper:<chatId>", "hermes:<platform>:<chat>"), newer than 4 h. */
export function recentDecisions(ctx, key) {
  if (!key) return [];
  let doc = null; try { doc = ctx.store.get(DECISIONS, key); } catch {}
  const cutoff = ctx.now() - TURN_WINDOW_MS;
  return (doc?.items ?? []).filter(d => ms(d.at) > cutoff || ms(d.decidedAt) > cutoff);
}
/** Records one routed message (route suppress|stream|popup) and prunes entries older than 4 h. Never throws. */
export function recordDecision(ctx, key, { messageId, at, route, importance, sender }) {
  if (!key || !route) return;
  try {
    const list = recentDecisions(ctx, key).filter(d => !(messageId && d.messageId === String(messageId)));
    list.push({ ...(messageId ? { messageId: String(messageId) } : {}), at: at ?? new Date(ctx.now()).toISOString(), route, importance: typeof importance === 'number' ? importance : null,
      ...(sender ? { sender: clip(sender, 80) } : {}), decidedAt: new Date(ctx.now()).toISOString() });
    ctx.store.put(DECISIONS, { id: key, items: list.slice(-MAX_DECISIONS), updatedAt: new Date(ctx.now()).toISOString() });
  } catch {}
}
/** Prunes expired decisions in every conversation (at most once a minute). Returns how many conversations were dropped. */
export function pruneDecisions(ctx, { force = false } = {}) {
  let removed = 0;
  if (!force && ctx.lastDecisionPrune && ctx.now() - ctx.lastDecisionPrune < 60000) return 0;
  ctx.lastDecisionPrune = ctx.now();
  try {
    for (const doc of ctx.store.all(DECISIONS)) {
      const live = recentDecisions(ctx, doc.id);
      if (!live.length) { ctx.store.del(DECISIONS, doc.id); removed++; }
      else if (live.length !== (doc.items ?? []).length) ctx.store.put(DECISIONS, { ...doc, items: live });
    }
  } catch {}
  return removed;
}
/** Marks each turn message with the route it got: by message id, else by a timestamp within 2 s. */
export function annotate(turns, decisions) {
  if (!decisions?.length) return turns;
  return turns.map(t => ({ ...t, messages: t.messages.map(m => {
    if (m.priorRoute) return m;
    const d = decisions.find(x => (m.id && x.messageId === m.id)) ?? decisions.find(x => !x.messageId && Math.abs(ms(x.at) - ms(m.at)) <= 2000);
    return d ? { ...m, priorRoute: d.route, ...(typeof d.importance === 'number' ? { priorImportance: d.importance } : {}) } : m;
  }) }));
}

// ---- Beeper: context from the Desktop API ----

const fold = (v) => String(v ?? '').normalize('NFKC').toLowerCase().replace(/\s+/g, ' ').trim();

/**
 * Context for a Beeper notification whose chat resolved: the target message (matched by text among the newest
 * messages, else the notification itself at `now`) and the turns before it, annotated with stored decisions.
 * Returns {target: {id?, at, sender, text}, turns, opener} or null when the history cannot be read.
 */
export async function beeperContext(ctx, n, chat, { timeoutMs = ctx.config.contextTimeoutMs ?? 1000 } = {}) {
  if (!chat?.chatId) return null;
  let list;
  try { list = items(await beeperApi(ctx, '/chats/' + encodeURIComponent(chat.chatId) + '/messages', 'GET', undefined, { timeoutMs })); }
  catch { return null; }
  const chatRow = { id: chat.chatId, participants: { items: [] } };
  const msgs = list.filter(m => m && (m.timestamp || m.at))
    .map(m => { const own = mine(m, chatRow); return { id: m.id !== undefined ? String(m.id) : undefined, at: new Date(ms(m.timestamp ?? m.at)).toISOString(), text: messageText(m, chatRow),
      speaker: own ? ownerName(ctx) : clip(senderName(m, chatRow), 80), isOwner: own, ...(!own && m.senderID ? { speakerId: String(m.senderID) } : {}) }; })
    .filter(m => !Number.isNaN(ms(m.at)))
    .sort((a, b) => ms(a.at) - ms(b.at));
  const now = ctx.now(), body = fold(n.body).slice(0, 40);
  // The target: the newest message from someone else whose text starts like the notification body.
  let index = -1;
  for (let i = msgs.length - 1; i >= 0 && index < 0; i--) {
    const m = msgs[i];
    if (m.isOwner || now - ms(m.at) > 30 * 60000) continue;
    if (body && fold(m.text).startsWith(body)) index = i;
  }
  const decisions = recentDecisions(ctx, 'beeper:' + chat.chatId);
  let target, turns;
  if (index >= 0) {
    const m = msgs[index];
    target = { id: m.id, at: m.at, sender: m.speaker, text: m.text };
    turns = previousTurns(msgs, index);
  } else {
    target = { at: new Date(now).toISOString(), sender: n.sender || n.title, text: n.body };
    const before = msgs.filter(m => ms(m.at) <= now);
    turns = previousTurns(before, before.length, { targetAt: now });
  }
  turns = annotate(turns, decisions);
  return { target, turns, opener: turns.length === 0 };
}

// ---- Relationship stats (Beeper) ----

export const RELATIONSHIP_DAYS = 30;
const DAY_MS = 86400000;
const REL_TTL_MS = 30 * 60000, REL_MAX = 500;
const round2 = (x) => Math.round(x * 100) / 100;
/**
 * Pure: relationship stats from messages [{at, isOwner}] (any order) relative to `ref`. When the loaded history does not
 * reach back 30 days, the counts are scaled from the span it covers (estimated: true).
 * Tier: frequent (>= 60 messages and >= 15 by the owner in 30 days), regular (the owner wrote >= 3), occasional (the owner wrote at
 * all in what was loaded), none.
 */
export function relationshipStats(messages, { ref = Date.now(), days = RELATIONSHIP_DAYS, complete = false } = {}) {
  const r = ms(ref), from = r - days * DAY_MS;
  const list = (messages ?? []).map(m => ({ t: ms(m.at), isOwner: m.isOwner === true })).filter(m => Number.isFinite(m.t) && m.t <= r);
  const win = list.filter(m => m.t > from);
  let msgs = win.length, own = win.filter(m => m.isOwner).length, estimated = false;
  const covered = complete || list.some(m => m.t <= from);
  if (!covered && win.length >= 2) {
    const span = r - Math.min(...win.map(m => m.t));
    if (span > 3600000 && span < days * DAY_MS) { const f = days * DAY_MS / span; msgs = Math.round(msgs * f); own = Math.round(own * f); estimated = true; }
  }
  const lastOwner = Math.max(-Infinity, ...list.filter(m => m.isOwner).map(m => m.t));
  const tier = msgs >= 60 && own >= 15 ? 'frequent' : own >= 3 ? 'regular' : Number.isFinite(lastOwner) ? 'occasional' : 'none';
  return { days, msgs30d: msgs, ownerMsgs30d: own, ownerShare: msgs ? round2(own / msgs) : 0, lastOwnerAt: Number.isFinite(lastOwner) ? new Date(lastOwner).toISOString() : null, estimated, tier };
}

/** Bounds a relationship object from a request body (debug evals) or the cache. */
export function cleanRelationship(r) {
  if (!r || typeof r !== 'object' || Array.isArray(r)) return null;
  const num = (v, max) => typeof v === 'number' && Number.isFinite(v) && v >= 0 ? Math.min(v, max) : 0;
  const last = typeof r.lastOwnerAt === 'string' && Number.isFinite(Date.parse(r.lastOwnerAt)) ? new Date(Date.parse(r.lastOwnerAt)).toISOString() : null;
  return { days: RELATIONSHIP_DAYS, msgs30d: Math.round(num(r.msgs30d, 1e6)), ownerMsgs30d: Math.round(num(r.ownerMsgs30d, 1e6)), ownerShare: round2(num(r.ownerShare, 1)),
    lastOwnerAt: last, estimated: r.estimated === true, tier: ['frequent', 'regular', 'occasional', 'none'].includes(r.tier) ? r.tier : 'none' };
}

/**
 * Relationship stats of one Beeper chat from its history (at most `maxPages` pages back to 30 days), cached per chat for
 * 30 minutes. Returns null when the history cannot be read in time.
 */
export async function relationshipFor(ctx, chatId, { timeoutMs = ctx.config.relationshipTimeoutMs ?? 900, maxPages = ctx.config.relationshipPages ?? 4 } = {}) {
  if (!chatId) return null;
  const cache = ctx.relationshipCache ??= new Map(), now = ctx.now();
  const hit = cache.get(chatId);
  if (hit && now - hit.at < REL_TTL_MS) return hit.stats;
  const started = Date.now(), base = '/chats/' + encodeURIComponent(chatId) + '/messages', chatRow = { id: chatId, participants: { items: [] } };
  const seen = new Map(), cursors = new Set();
  let cursor = null, complete = false, pages = 0;
  try {
    while (pages < maxPages) {
      const left = timeoutMs - (Date.now() - started);
      if (left < 100) break;
      const page = await beeperApi(ctx, base + (cursor ? '?direction=before&cursor=' + encodeURIComponent(cursor) : ''), 'GET', undefined, { timeoutMs: left });
      pages++;
      for (const m of items(page)) if (m && (m.timestamp || m.at)) seen.set(String(m.id ?? seen.size), { at: m.timestamp ?? m.at, isOwner: mine(m, chatRow) });
      const oldest = Math.min(...[...seen.values()].map(m => ms(m.at)).filter(Number.isFinite));
      if (!page?.hasMore || !page.oldestCursor || cursors.has(page.oldestCursor)) { complete = true; break; }
      if (oldest <= now - RELATIONSHIP_DAYS * DAY_MS) break;
      cursor = page.oldestCursor; cursors.add(cursor);
    }
  } catch { if (!seen.size) return null; }
  if (!pages) return null;
  const stats = relationshipStats([...seen.values()], { ref: now, complete });
  cache.set(chatId, { at: now, stats });
  if (cache.size > REL_MAX) cache.delete(cache.keys().next().value);
  return stats;
}

/** "412 msgs/30d, Sam writes 48%, last reply 2 h ago (frequent contact)" for the classifier. */
export function relationshipLine(r, ref, { isGroup = false, owner = 'the user' } = {}) {
  if (!r) return '';
  const ago = r.lastOwnerAt ? ageLabel(ms(ref) - ms(r.lastOwnerAt)) : null;
  const Owner = owner[0].toUpperCase() + owner.slice(1);
  const tier = { frequent: isGroup ? `a chat ${owner} is very active in` : 'frequent contact', regular: isGroup ? `${Owner} takes part here` : `${Owner} talks with them regularly`,
    occasional: `${Owner} rarely writes here`, none: `${Owner} has not written here` }[r.tier] ?? '';
  return `${r.estimated ? '~' : ''}${r.msgs30d} msgs/30d, ${owner} writes ${Math.round((r.ownerShare ?? 0) * 100)}%, ${ago ? `last ${isGroup ? `message from ${owner}` : 'reply'} ${ago} ago` : `no message from ${owner} loaded`}${tier ? ` (${tier})` : ''}`;
}
function ageLabel(d) {
  if (!Number.isFinite(d) || d < 0) return 'moments';
  const min = Math.round(d / 60000);
  if (min < 60) return `${Math.max(1, min)} min`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} h`;
  return `${Math.round(h / 24)} d`;
}

// ---- Context supplied with debug (evals) ----

const ROUTE_SET = new Set(['suppress', 'stream', 'popup']);
/** Bounds and normalises a `context` object from a request body (only honoured with debug, or from the Hermes plugin). */
export function cleanContext(raw, { maxTurns = MAX_TURNS } = {}) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const when = (v) => (typeof v === 'string' || typeof v === 'number') && Number.isFinite(ms(v)) ? new Date(ms(v)).toISOString() : null;
  const msg = (m, max) => m && typeof m === 'object' ? { at: when(m.at), text: clip(m.text, max), ...(typeof m.sender === 'string' ? { sender: clip(m.sender, 80) } : {}),
    ...(typeof m.isOwner === 'boolean' ? { isOwner: m.isOwner } : {}), ...(m.createdBy === 'agent' || m.agentWritten === true || m.fromParentAgent === true ? { agentWritten: true } : {}) } : null;
  const turns = (Array.isArray(raw.turns) ? raw.turns : []).slice(-maxTurns).filter(t => t && typeof t === 'object' && Array.isArray(t.messages)).map(t => ({
    speaker: clip(t.name || t.speaker || (t.isOwner ? 'owner' : 'other'), 80), isOwner: t.isOwner === true,
    messages: t.messages.slice(-8).filter(m => m && typeof m === 'object').map(m => ({ ...(m.id !== undefined ? { id: String(m.id).slice(0, 200) } : {}), at: when(m.at), text: clip(m.text, 400),
      ...(ROUTE_SET.has(m.priorRoute) ? { priorRoute: m.priorRoute } : {}), ...(typeof m.priorImportance === 'number' ? { priorImportance: m.priorImportance } : {}) })),
  })).filter(t => t.messages.length);
  const out = { turns };
  if (raw.target) out.target = { ...msg(raw.target, 1000), ...(raw.target.id !== undefined ? { id: String(raw.target.id).slice(0, 200) } : {}) };
  if (raw.prompt) out.prompt = msg(raw.prompt, 3000);
  if (raw.response) out.response = msg(raw.response, 4000);
  for (const k of ['threadTitle', 'project', 'status', 'chat', 'platform', 'job']) if (typeof raw[k] === 'string') out[k] = clip(raw[k], 200);
  if (typeof raw.isDM === 'boolean') out.isDM = raw.isDM;
  const rel = cleanRelationship(raw.relationship);
  if (rel) out.relationship = rel;
  if (typeof raw.runMs === 'number' && Number.isFinite(raw.runMs) && raw.runMs >= 0) out.runMs = Math.min(raw.runMs, 30 * DAY_MS);
  return out;
}

// ---- Rendering for the classifier ----

/** "-12 min", "-2 h 5 min", "-40 s", "+3 min" relative to `ref`. */
export function relTime(at, ref) {
  const a = ms(at), r = ms(ref);
  if (!Number.isFinite(a) || !Number.isFinite(r)) return 'time unknown';
  const d = Math.round((a - r) / 1000), sign = d < 0 ? '-' : '+', s = Math.abs(d);
  if (s < 60) return s <= 5 ? 'same moment' : `${sign}${s} s`;
  const min = Math.round(s / 60);
  if (min < 60) return `${sign}${min} min`;
  return `${sign}${Math.floor(min / 60)} h${min % 60 ? ' ' + (min % 60) + ' min' : ''}`;
}
/** A local clock label, e.g. "Tue 7:41 PM". */
export function clockLabel(at, tz = 'UTC') {
  const t = ms(at);
  if (!Number.isFinite(t)) return 'unknown time';
  try { return new Intl.DateTimeFormat('en-US', { timeZone: tz, weekday: 'short', hour: 'numeric', minute: '2-digit' }).format(new Date(t)); }
  catch { return new Date(t).toISOString(); }
}
// The model must never mistake message text for a block boundary, a header line or an "already notified" marker:
// END markers are lower-cased in any case, a data line that starts like one of the input's own header lines is indented
// and lower-cased, and "<-" (the marker arrow) is broken up.
const HEADER_LINE = /^[ \t]*(?:END[ \t]+(?:CONTEXT|TARGET|PROMPT|RESPONSE)|KIND:|LOCAL TIME|CHAT:|RELATIONSHIP:|CONTEXT\b|TARGET MESSAGE|PROMPT\b|RESPONSE\b|T3 CODE THREAD:|RUN STATE:|RUN LENGTH:|HERMES CHANNEL:|EXCHANGE TYPE:|SOURCE:|from:|text:)/gm;
export const safeText = (s) => String(s ?? '').replace(/<{2,}|>{2,}/g, '<>').replace(/<\s*-/g, '< -')
  .replace(/\bEND[ \t]+(?:CONTEXT|TARGET|PROMPT|RESPONSE)\b/gi, m => m.toLowerCase())
  .replace(HEADER_LINE, m => '  ' + m.trimStart().toLowerCase());
const safe = safeText;
const NOTIFIED = { popup: 'already notified them: popup', stream: 'already in their list: stream', suppress: 'not shown: suppress' };

/** The context block: one line per earlier message, oldest first. */
export function renderTurns(turns, ref, { owner = 'the user' } = {}) {
  if (!turns?.length) return '(none: this message opens the conversation, nothing in the 4 hours before it)';
  const lines = [];
  for (const t of turns) {
    const name = String(t.speaker || 'other');
    const label = clip(/^[a-z]+$/.test(name) ? name[0].toUpperCase() + name.slice(1) : name, 80);
    // Only the owner's own messages carry the owner's name: another person with the same display name says so.
    const ownerKey = owner.replace(/[^\p{L}]/gu, '').toLowerCase();
    const who = t.isOwner ? owner : safe(ownerKey && label.replace(/[^\p{L}]/gu, '').toLowerCase() === ownerKey ? `${label} (another person, not ${owner})` : label);
    if (t.omitted) lines.push(`(${t.omitted} earlier messages from ${who} omitted)`);
    for (const m of t.messages) lines.push(`[${relTime(m.at, ref)}] ${who}: ${safe(m.text)}${m.priorRoute ? `   <- ${NOTIFIED[m.priorRoute]}` : ''}`);
  }
  return lines.join('\n');
}

// ---- Deterministic guard on the model's answer ----

const ASK = /\?|\b(?:can|could|would|will|do|did|are|is) (?:you|u|we)\b|\bplease\b|\bpls\b|\bplz\b|\bcall me\b|\bcall (?:now|asap)\b|\burgent\b|\basap\b|\bemergency\b|\bhelp\b|\bneed (?:you|u|your|to)\b|\bwhere (?:are|r) (?:you|u)\b|\blet me know\b|\blmk\b|\bwhen\b|\bwhat time\b|\bchanged?\b|\bcancel|\blate\b|\binstead\b|\bnow\b/i;
const ACK = /^(?:ok(?:ay)?|kk?|sounds good|sg|perfect|great|cool|awesome|nice|bet|yes|yep|yup|yeah|ya|sure|for sure|deal|done|got it|will do|see (?:you|u|ya)(?: then| there| soon)?|thanks?(?: you)?(?: so much)?|thx|ty|np|no worries|lol|lmao|haha+|ha|love it|amazing|same|true|fr|word|👍+|❤️+|🙏+|😂+|💯)(?:[\s!.,]+(?:ok(?:ay)?|thanks|thank you|see (?:you|u) then|sounds good|perfect|great|lol|haha+|👍|❤️|🙏|😂))*[\s!.,]*$/iu;
export const hasNewAsk = (text) => ASK.test(String(text ?? ''));
// A bare "no" is group banter for the rules, but never an acknowledgement here (it answers or refuses something).
export const isAck = (text) => { const s = String(text ?? '').trim(); return !!s && !/^no\b/i.test(s) && (ACK.test(s) || RX.banter.test(s)); };

/** A message that is only a link (or a link with a few words): an ordinary share. */
const LINK_ONLY = /^\s*(?:[^\n?]{0,40}\s)?https?:\/\/\S+\s*(?:\s[^\n?]{0,40})?$/i;
export const linkOnly = (text) => LINK_ONLY.test(String(text ?? ''));

/**
 * Applies the deterministic guards to a model decision. `context`: {turns, target: {sender, text}, relationship?}.
 * `opts`: {isGroup, mentions, ownChat, ownerPattern} from the resolved chat and the config.
 *  - same run of messages from one sender (no one else spoke since): after a popup in that run, only a message the model
 *    rates clearly more urgent (importance >= that popup's + 0.15, or >= 0.95 when it was below) pops up again; else
 *    stream. If nothing in the run popped up, a follow-up may;
 *  - a follow-up to an already-notified message of the same sender (any earlier turn in the window) that asks nothing new:
 *    an acknowledgement or banter is suppressed (stream for a frequent contact), anything else is at most stream;
 *  - a group message that is not for the owner (no @mention, no owner name in it, and they have not spoken in the last 30 minutes)
 *    never pops up;
 *  - never suppressed: a one-to-one message from a frequent or regular contact (relationship stats, by volume), an
 *    ordinary link from someone the owner has written to, and anything in one of their own chats (except a bare acknowledgement).
 * Returns the decision unchanged, or a copy with the new route and `guard` naming the rule.
 */
export function guard(d, context, { isGroup = false, mentions = 0, ownChat = false, ownerPattern = null } = {}) {
  if (!d || !context || !['popup', 'stream', 'suppress'].includes(d.route)) return d;
  const turns = context.turns ?? [], who = String(context.target?.sender ?? '').trim().toLowerCase(), text = context.target?.text ?? '';
  const rel = context.relationship ?? null;
  const close = !isGroup && !!rel && (rel.tier === 'frequent' || rel.tier === 'regular');
  const sameSender = (t) => !t.isOwner && (!who || String(t.speaker ?? '').trim().toLowerCase() === who);
  const last = turns.at(-1);
  const notified = turns.filter(sameSender).some(t => t.messages.some(m => m.priorRoute === 'popup' || m.priorRoute === 'stream'));
  // The owner asked something after the notified message: a short "ok"/"yes" is the answer they are waiting on, not an ack.
  const answersOwner = !!last?.isOwner && last.messages.some(m => hasNewAsk(m.text));
  const ackAfter = notified && !hasNewAsk(text) && isAck(text) && !answersOwner;
  const floor = (reason) => ({ ...d, route: 'stream', importance: Math.max(Math.min(d.importance, 0.5), 0.3), guard: reason });
  if (d.route === 'suppress') {
    if (ownChat && !isAck(text)) return floor('guard.own-chat');
    if (close && (rel.tier === 'frequent' || !ackAfter)) return floor('guard.relationship-floor');
    if (!isGroup && rel && rel.lastOwnerAt && linkOnly(text)) return floor('guard.link-floor');
    return d;
  }
  const run = last && sameSender(last) ? last.messages : [];
  const runPopups = run.filter(m => m.priorRoute === 'popup');
  let escalated = false, sameRun = false;
  if (d.route === 'popup' && runPopups.length) {
    const prev = Math.max(...runPopups.map(m => typeof m.priorImportance === 'number' ? m.priorImportance : 0.6));
    escalated = d.importance >= prev + 0.15 || (d.importance >= 0.95 && prev < 0.95);
    sameRun = !escalated;
  }
  if (notified && !hasNewAsk(text)) {
    // An acknowledgement after a notification is dropped, also inside the same burst (kept in the list for a frequent contact).
    if (ackAfter) return close && rel.tier === 'frequent' ? { ...d, route: 'stream', importance: Math.min(d.importance, 0.3), guard: 'guard.ack-frequent' }
      : { ...d, route: 'suppress', importance: Math.min(d.importance, 0.2), guard: 'guard.ack' };
    if (sameRun) return { ...d, route: 'stream', guard: 'guard.same-run' };
    if (d.route === 'popup' && !escalated) return { ...d, route: 'stream', guard: 'guard.follow-up' };
  }
  if (sameRun) return { ...d, route: 'stream', guard: 'guard.same-run' };
  if (d.route === 'popup' && isGroup && !(mentions > 0) && !ownerPattern?.test(text)) {
    const ref = ms(context.target?.at);
    const ownerRecent = turns.some(t => t.isOwner && t.messages.some(m => !Number.isFinite(ref) || ref - ms(m.at) <= 30 * 60000));
    if (!ownerRecent) return { ...d, route: 'stream', guard: 'guard.group-not-for-owner' };
  }
  return d;
}
