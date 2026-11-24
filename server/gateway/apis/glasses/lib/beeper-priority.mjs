// Beeper chat priority (BEEP-PRI): which chats the glasses list first, glasses pins, and spoken "find a chat".
// Everything here is read-only towards Beeper. Glasses pins live only in the gateway store ('beeper-pins'), so
// The assistant sees them through GET /beeper/pins and the glasses_beeper_pins MCP tool; nothing is PATCHed upstream.
import { items } from './gateway.mjs';
import { beeper, chatName, counts, hasUnread, isGroup, mentionsOwner, mine, plainText, isSpam, idLike } from './beeper.mjs';

export const PINS = 'beeper-pins';
export const ORDERS = ['priority', 'recent'];
/** A counting unread chat at or above this score is "important" and listed right after the pins. */
export const IMPORTANT = 0.5;
/** Beeper labels that count as favourites (Beeper's own pin is `isPinned`). */
const FAVOURITE = /^(?:fav(?:ou?rite)?s?|starred|star|vip|priority|important|pinned)$/i;

export const order = (value) => ORDERS.includes(String(value ?? '').toLowerCase()) ? String(value).toLowerCase() : 'priority';
export function glassesPins(ctx) {
  try { return ctx.store?.all(PINS) ?? []; } catch { return []; }
}
const pinSet = (ctx) => new Set(glassesPins(ctx).map(p => p.id));
/** Pinned or favourited in Beeper itself. */
export const beeperPinned = (chat) => chat?.isPinned === true || (Array.isArray(chat?.labels) && chat.labels.some(l => FAVOURITE.test(String(l?.name ?? '').trim())));

// ---- Importance (cheap and deterministic) ----
// Message history signals, per chat and kept for 10 minutes outside the shared 'beeper:' cache (every Beeper write
// clears that cache, and opening a chat writes /read).
const historyCache = new WeakMap();
const HISTORY_TTL = 10 * 60000;
/** From one page of messages: how many the owner wrote, when they last wrote, and how fast they usually answer. */
export function historySignals(messages, chat, now) {
  const list = [...messages].filter(m => m && !m.isHidden && !m.isDeleted).sort((a, b) => Date.parse(a.timestamp || 0) - Date.parse(b.timestamp || 0));
  let sent = 0, lastSent = 0; const gaps = [];
  for (let i = 0; i < list.length; i++) {
    if (!mine(list[i], chat)) continue;
    sent++; lastSent = Math.max(lastSent, Date.parse(list[i].timestamp || 0) || 0);
    const before = list[i - 1];
    if (before && !mine(before, chat)) { const gap = Date.parse(list[i].timestamp || 0) - Date.parse(before.timestamp || 0); if (Number.isFinite(gap) && gap >= 0) gaps.push(gap); }
  }
  gaps.sort((a, b) => a - b);
  const median = gaps.length ? gaps[Math.floor((gaps.length - 1) / 2)] : null;
  return { sent, recent: lastSent > 0 && now - lastSent <= 14 * 86400000, replyMs: median, replies: gaps.length };
}
async function history(ctx, chat) {
  let cache = historyCache.get(ctx); if (!cache) historyCache.set(ctx, cache = new Map());
  const hit = cache.get(chat.id);
  if (hit && ctx.now() - hit.at < HISTORY_TTL) return hit.value;
  const value = items(await beeper(ctx, '/chats/' + encodeURIComponent(chat.id) + '/messages', 'GET', undefined, { timeoutMs: 2500 }));
  const signals = historySignals(value, chat, ctx.now());
  cache.set(chat.id, { at: ctx.now(), value: signals });
  if (cache.size > 300) cache.delete(cache.keys().next().value);
  return signals;
}
/** Some text that asks the owner something: a question mark, or a direct "can you / are you / do you …" opener. */
export const asksQuestion = (text) => /\?\s*$|\?\s|\?$/.test(text) || /^(?:can|could|would|will|are|do|did|have|is|when|where|what|who|how|why|you)\b.*\byou\b/i.test(text.trim());
/** A person, not an SMS short code or bare number (those are spam candidates, never "important"). */
const person = (chat) => !isGroup(chat) && !/^\+?\d{3,13}$/.test(String(chat?.title ?? '').trim()) && !idLike(chatName(chat));
/** 0..1 importance with the reasons that produced it. `signals` is optional message history. */
export function importance(chat, signals = null, ctx = null) {
  const why = []; let score = 0;
  const last = chat?.preview, theirs = last && !mine(last, chat) && last.isSender !== true;
  if (mentionsOwner(chat, ctx)) { score += 0.5; why.push('mention'); }
  if (theirs && asksQuestion(plainText(last.text ?? ''))) { score += isGroup(chat) ? 0.1 : 0.3; why.push('question'); }
  if (person(chat)) { score += 0.2; why.push('direct'); }
  if (signals?.sent >= 3 && signals.recent) { score += 0.2; why.push('frequent'); }
  if (signals?.replies >= 2 && signals.replyMs !== null && signals.replyMs <= 15 * 60000) { score += 0.2; why.push('quick'); }
  return { score: Math.min(1, Math.round(score * 100) / 100), why };
}
/** Scores every counting unread chat. History is fetched for at most `max` of them, six at a time, within `budgetMs`;
 * a slow or failed lookup just scores without history. Returns Map id -> {score, why}. */
export async function scoreUnread(ctx, chats, { max = 20, budgetMs = 1200 } = {}) {
  const unread = chats.filter(c => counts(ctx, c));
  const out = new Map(unread.map(c => [c.id, importance(c, null, ctx)]));
  const wanted = unread.slice(0, max);
  if (!wanted.length) return out;
  let next = 0;
  const work = Promise.all(Array.from({ length: Math.min(6, wanted.length) }, async () => {
    while (next < wanted.length) {
      const c = wanted[next++];
      try { out.set(c.id, importance(c, await history(ctx, c), ctx)); } catch {}
    }
  }));
  let timer;
  await Promise.race([work, new Promise(resolve => { timer = setTimeout(resolve, budgetMs); timer.unref?.(); })]);
  clearTimeout(timer);
  // Lookups still running after the budget keep filling `out` (and the history cache); hand back a snapshot so a row's
  // reported importance always matches the tier it was ordered by.
  return new Map(out);
}

// ---- Ordering ----
const recency = (a, b) => (Date.parse(b.lastActivity || 0) || 0) - (Date.parse(a.lastActivity || 0) || 0);
/** The list tier of a chat: pinned, important, unread, quiet (unread but muted, spam or low priority) or read. */
export function tier(ctx, chat, scores, pins = pinSet(ctx)) {
  if (beeperPinned(chat) || pins.has(chat.id)) return 'pinned';
  if (counts(ctx, chat)) return (scores.get(chat.id)?.score ?? 0) >= IMPORTANT ? 'important' : 'unread';
  if (hasUnread(chat)) return 'quiet';
  return 'read';
}
const TIERS = ['pinned', 'important', 'unread', 'quiet', 'read'];
/** Priority: pins, important unread (by score), other unread, quiet unread, then the rest, each by recency.
 * Recent (the previous order): pins, then counting unread chats, then everything else by recency. */
export function prioritize(ctx, chats, scores = new Map(), mode = 'priority') {
  const pins = pinSet(ctx);
  const ranked = chats.map(c => ({ c, tier: tier(ctx, c, scores, pins) }));
  if (order(mode) === 'recent') {
    const rank = (r) => r.tier === 'pinned' ? 0 : counts(ctx, r.c) ? 1 : 2;
    return ranked.sort((a, b) => rank(a) - rank(b) || recency(a.c, b.c));
  }
  return ranked.sort((a, b) => TIERS.indexOf(a.tier) - TIERS.indexOf(b.tier)
    || (a.tier === 'important' ? (scores.get(b.c.id)?.score ?? 0) - (scores.get(a.c.id)?.score ?? 0) : 0) || recency(a.c, b.c));
}

// ---- Find a chat by a spoken name ----
const FILLER = /^(?:(?:please|hey|ok(?:ay)?|um+|uh+)\s+)*(?:(?:find|open|show|go\s+to|jump\s+to|search(?:\s+for)?|get|pull\s+up)\s+)?(?:(?:my|the)\s+)?(?:(?:chat|conversation|messages?|thread|group(?:\s+chat)?|dm)\s+(?:with|from|for)\s+)?/i;
export function normalize(value) {
  return String(value ?? '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}
export function cleanQuery(value) {
  return normalize(String(value ?? '').replace(/[.!?,]+$/g, '').replace(FILLER, '').replace(/\s+(?:chat|conversation|group(?:\s+chat)?|messages?|thread|on\s+\w+)$/i, ''));
}
function levenshtein(a, b) {
  if (a === b) return 0; if (!a.length) return b.length; if (!b.length) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const row = [i];
    for (let j = 1; j <= b.length; j++) row[j] = Math.min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = row;
  }
  return prev[b.length];
}
const similar = (a, b) => 1 - levenshtein(a, b) / Math.max(a.length, b.length);
/** 0..1: how well a spoken query names `candidate`. The same rules run on the phone (BeeperPriority.kt). */
export function nameScore(query, candidate) {
  const q = normalize(query), n = normalize(candidate);
  if (!q || !n) return 0;
  if (q === n) return 1;
  const qc = q.replace(/ /g, ''), nc = n.replace(/ /g, '');
  if (qc === nc) return 0.98;
  const qt = q.split(' '), nt = n.split(' ');
  if (nc.startsWith(qc) && qc.length >= 3) return 0.9;
  if (qt.every(t => nt.some(w => w.startsWith(t))) && qc.length >= 2) return 0.86;
  const exact = qt.filter(t => nt.includes(t)).length;
  if (exact) return 0.6 + 0.2 * exact / qt.length;
  if (qc.length >= 4 && nc.includes(qc)) return 0.7;
  // Misheard names: each spoken word against its closest name word.
  const fuzzy = qt.map(t => Math.max(...nt.map(w => similar(t, w)))).reduce((s, v) => s + v, 0) / qt.length;
  const whole = similar(qc, nc);
  const best = Math.max(fuzzy, whole);
  return best >= 0.7 ? Math.round(best * 0.8 * 100) / 100 : 0;
}
/** Names a chat can be found by: its title and (for a DM) the person; group members count slightly less. */
export function chatNames(chat) {
  const people = items(chat?.participants).filter(p => p && !p.isSelf && !p.isNetworkBot);
  const own = [chatName(chat), chat?.title].filter(Boolean);
  const member = people.flatMap(p => [p.fullName, p.displayName, p.username]).filter(n => n && !idLike(n, null));
  return { own: [...new Set(own)], member: [...new Set(member)], direct: !isGroup(chat) };
}
/** Best matching chats for a spoken name, most likely first: [{chat, score, on}]. */
export function findChats(ctx, chats, query, { max = 5 } = {}) {
  const q = cleanQuery(query);
  if (!q) return [];
  const pins = pinSet(ctx);
  const found = [];
  for (const chat of chats) {
    if (chat?.isArchived) continue;
    const names = chatNames(chat);
    let score = 0, on = '';
    for (const n of names.own) { const s = nameScore(q, n); if (s > score) { score = s; on = n; } }
    for (const n of names.member) { const s = nameScore(q, n) * (names.direct ? 0.97 : 0.85); if (s > score) { score = s; on = n; } }
    if (score < 0.5) continue;
    // Small, deterministic tie-breaks: a pinned or unread chat, then the more recent one.
    const boost = (pins.has(chat.id) || beeperPinned(chat) ? 0.02 : 0) + (hasUnread(chat) && !isSpam(ctx, chat) ? 0.01 : 0);
    found.push({ chat, score: Math.min(1, Math.round((score + boost) * 1000) / 1000), on });
  }
  return found.sort((a, b) => b.score - a.score || recency(a.chat, b.chat)).slice(0, max);
}
/** The first match is a clear answer: good on its own and well ahead of the second. */
export const confident = (matches) => matches.length > 0 && matches[0].score >= 0.8 && (matches.length === 1 || matches[0].score - matches[1].score >= 0.08);
