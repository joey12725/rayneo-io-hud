import { request, cached, invalidate, items, deadline } from './gateway.mjs';
import { personName } from './cleanup.mjs';
import { mentionsOwnerName, ownerPattern, assistantName } from './persona.mjs';

export async function beeperAuth(ctx) {
  if (ctx.config.beeperEnabled === false) { const { HttpError } = await import('../../../lib/http.mjs'); throw new HttpError(503, 'Beeper is not enabled on this gateway'); }
  return { Authorization: 'Bearer ' + await ctx.secret(ctx.config.beeperTokenSecret || 'BEEPER_ACCESS_TOKEN') };
}
export async function beeper(ctx, path, method = 'GET', body, options = {}) {
  const result = await request(ctx, ctx.config.beeperUrl + '/v1' + path, { method, body, ...options,
    headers: await beeperAuth(ctx) });
  if (method !== 'GET') invalidate(ctx, 'beeper:');
  return result;
}
export function accounts(ctx) { return cached(ctx, 'beeper:accounts', 30000, async () => items(await beeper(ctx, '/accounts'))); }
export function chats(ctx) {
  return cached(ctx, 'beeper:chats', 5000, async () => {
    const budget = deadline(ctx, ctx.config.paginationBudgetMs ?? 15000);
    try {
      const all = [], cursors = new Set(); let cursor;
      // Bound pagination, but do not silently count only the first screen of unread chats.
      while (!budget.expired()) {
        let response;
        try { response = await budget.run(() => beeper(ctx, '/chats?limit=200' + (cursor ? '&direction=before&cursor=' + encodeURIComponent(cursor) : ''), 'GET', undefined, { signal: budget.signal })); }
        catch (e) { if (all.length || budget.expired()) break; throw e; }
        all.push(...items(response));
        if (!response.hasMore || !response.oldestCursor || cursors.has(response.oldestCursor)) break;
        cursor = response.oldestCursor; cursors.add(cursor);
      }
      return [...new Map(all.map(c => [c.id, c])).values()].sort((a, b) => Date.parse(b.lastActivity || 0) - Date.parse(a.lastActivity || 0));
    } finally { budget.close(); }
  });
}
export const visible = (chat) => !chat.isArchived && !chat.isLowPriority && !chat.lowPriority && chat.priority !== 'low';
export const network = (chat, accts) => accts.find(a => a.accountID === chat.accountID)?.network ?? chat.network ?? 'Beeper';
const participants = (chat) => items(chat?.participants);
// Beeper fills senderName with the raw Matrix ID ("@instagramgo_123:beeper.local") when the
// sender is not among the ~20 participants embedded in the chat row. Never show that.
export const idLike = (name, id) => typeof name !== 'string' || !name.trim() || name === id || /^\d+$/.test(name.trim()) || /@[^:\s<>@]+:[\w-]+(?:\.[\w-]+)+/.test(name) || /^!?[\w-]{12,}:[\w.-]+$/.test(name);
const firstName = (p) => [p?.fullName, p?.displayName, p?.username].find(n => n && !idLike(n, p?.id));
export function scrubIds(value, chat) {
  return String(value ?? '').replace(/@[^:\s<>@]+:[\w-]+(?:\.[\w-]+)+/g, id => firstName(participants(chat).find(p => p.id === id)) || '@someone');
}
/** Bridges deliver some bodies as HTML (`<p><a href="url" rel=…>url</a></p>`) or Markdown
 * links (`[url](url?tracking)`). The glasses show plain text: keep the visible words, reduce a
 * link to its bare URL (which the app then summarises as "link to …" or a reel), drop the markup. */
const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
export function plainText(value) {
  if (typeof value !== 'string') return '';
  let text = value;
  // Only treat the body as HTML when it contains a real tag, so `x<y and y>z` or `<joe@x.com>` survive.
  const TAG = /<\/?(?:a|p|br|div|span|li|ul|ol|b|i|u|s|em|strong|code|pre|blockquote|h[1-6])\b[^>]*>/i;
  if (TAG.test(text)) {
    text = text.replace(/<a\b[^>]*\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*>([\s\S]*?)<\/a>/gi, (_, d, q, inner) => {
      const href = d ?? q ?? '', label = inner.replace(/<[^>]*>/g, '').trim();
      return !label || /^https?:\/\//i.test(label) ? href : label;
    }).replace(/<br\s*\/?>|<\/p>|<\/div>|<\/li>/gi, '\n').replace(new RegExp(TAG.source, 'gi'), '');
  }
  text = text.replace(/\[([^\]\n]*)\]\((https?:\/\/[^)\s]+)\)/g, (_, label, url) => !label.trim() || /^https?:\/\//i.test(label.trim()) ? url : label.trim());
  text = text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
    if (e[0] === '#') { const code = e[1].toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10); return Number.isFinite(code) && (code >= 0x20 || code === 9 || code === 10) && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff) ? String.fromCodePoint(code) : m; }
    return ENTITIES[e.toLowerCase()] ?? m;
  });
  return text.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}
/** Official Desktop API fields: attachments (duration is seconds), links, and type.
 * Unknown bridge extensions are optional; never assume a media URL exists. */
export function messageText(message = {}, chat) {
  message ??= {};
  const text = plainText(message.text);
  const duration = a => Number.isFinite(a.duration) && a.duration > 0 ? ` (${Math.floor(a.duration / 60)}:${String(Math.floor(a.duration % 60)).padStart(2, '0')})` : '';
  const links = [ ...(Array.isArray(message.links) ? message.links : []), ...(Array.isArray(message.linkPreviews) ? message.linkPreviews : []) ];
  const urls = links.map(l => l.originalURL || l.url || '').join(' ');
  const kind = String(message.type ?? '').toLowerCase();
  const reel = /instagram\.com\/reels?\//i.test(urls) || /reel/.test(kind);
  const post = /instagram\.com\/p\//i.test(urls) || /(?:shared_?)?post/.test(kind);
  const summaries = reel ? ['\uE002 sent a reel'] : post ? ['\uE001 sent a post'] : (Array.isArray(message.attachments) ? message.attachments : []).filter(a => a && typeof a === 'object').map(a => {
    const type = String(a.type ?? '').toLowerCase(), mime = String(a.mimeType ?? '').toLowerCase();
    if (a.isSticker || /sticker/.test(type)) return 'sticker';
    if (a.isGif || mime === 'image/gif' || type === 'gif') return 'GIF';
    if (a.isVoiceNote || /voice/.test(type)) return 'voice message' + duration(a);
    if (type === 'video' || mime.startsWith('video/')) return 'sent a video' + duration(a);
    if (type === 'audio' || mime.startsWith('audio/')) return 'voice message' + duration(a);
    if (['img', 'image', 'photo'].includes(type) || mime.startsWith('image/')) return 'sent a photo';
    return 'sent a file' + (typeof a.fileName === 'string' && a.fileName.trim() ? ': ' + a.fileName.trim() : '');
  });
  if (!summaries.length && links.length && !/https?:\/\//i.test(text)) summaries.push('\uE001 shared a link');
  if (!summaries.length && !text) {
    const summary = /image|photo/.test(kind) ? 'sent a photo' : /video/.test(kind) ? 'sent a video' : /audio|voice/.test(kind) ? 'voice message' : /sticker/.test(kind) ? 'sticker' : /gif/.test(kind) ? 'GIF' : /file/.test(kind) ? 'sent a file' : links.length ? '\uE001 shared a link' : 'Message';
    summaries.push(summary);
  }
  // RichText already summarizes URLs (including reels); don't repeat their summary.
  const extra = /https?:\/\//i.test(text) && (reel || post) ? [] : [...new Set(summaries)];
  return scrubIds([text, ...extra].filter(Boolean).join(' · '), chat);
}
export async function fullChat(ctx, chat) {
  if (!chat?.id) return chat;
  try {
    const full = await cached(ctx, 'beeper:members:' + chat.id, 600000, () => beeper(ctx, '/chats/' + encodeURIComponent(chat.id) + '?maxParticipantCount=500'));
    // The list row is fresher than this 10-minute cache: its unread count and preview must win.
    return { ...full, ...chat, id: chat.id, participants: { total: full?.participants?.total ?? chat?.participants?.total,
      items: [...new Map([...participants(chat), ...participants(full)].map(p => [p.id, p])).values()] } };
  } catch { return chat; }
}
export function sender(message, chat) {
  const id = message?.senderID;
  const name = [message?.senderName, message?.sender?.displayName, message?.sender?.fullName, firstName(participants(chat).find(p => p.id === id))].find(n => n && !idLike(n, id));
  return name || (id ? 'Someone' : 'Unknown');
}
/** Like sender(), but looks the sender up in the full participant list when the embedded one is truncated. */
export async function resolveSender(ctx, message, chat) {
  const name = sender(message, chat);
  if (name !== 'Someone' || !chat?.id) return name;
  try {
    const full = await fullChat(ctx, chat);
    return sender(message, full);
  } catch { return name; }
}
export function chatName(chat) {
  const title = chat?.title || chat?.name;
  // A digits-only title is a phone number or SMS short code ("62881"), which is a real name for a
  // chat on phone-number networks; elsewhere a digit string is an internal ID.
  const phoneLike = chat?.type !== 'group' && /sms|messages|imessage|whatsapp|signal|telegram/i.test(String(chat?.network ?? chat?.accountID ?? '')) && /^\+?\d{3,13}$/.test(String(title ?? '').trim());
  if (title && (!idLike(title) || phoneLike)) return scrubIds(title, chat);
  const others = participants(chat).filter(p => !p.isSelf).map(firstName).filter(Boolean).map(n => n.split(/\s+/)[0]);
  if (!others.length) return chat?.type === 'group' ? 'Group chat' : 'Chat';
  const extra = Math.max(0, (chat?.participants?.total ?? others.length + 1) - 1 - Math.min(3, others.length));
  return others.slice(0, 3).join(', ') + (extra ? ` +${extra}` : '');
}
export function mine(message, chat, accts = []) {
  return Boolean(message?.isSender || message?.isSelf || message?.sender?.isSelf || participants(chat).some(p => p.id === message?.senderID && p.isSelf) || accts.some(a => a.user?.id === message?.senderID));
}
export function mapMessage(message, chat, accts, messages, ctx = null) {
  const self = new Set([...participants(chat).filter(p => p.isSelf).map(p => p.id), ...accts.map(a => a.user?.id).filter(Boolean)]);
  const grouped = new Map();
  for (const reaction of message.reactions ?? []) {
    const key = reaction.reactionKey ?? reaction.key; if (!key) continue;
    const group = grouped.get(key) ?? { key, count: 0, mine: false };
    group.count += reaction.count ?? 1; group.mine ||= Boolean(reaction.isSelf || reaction.isSender || reaction.mine || self.has(reaction.participantID)); grouped.set(key, group);
  }
  const reply = message.replyTo ?? messages.find(m => m.id === message.linkedMessageID);
  const name = sender(message, chat), isMine = mine(message, chat, accts);
  // Heuristic only: a non-self assistant bot (named like the configured assistant) in a Discord DM, with both approval
  // emoji and an explicit request/action verb. Never infer approvals from a group chat.
  const bot = new RegExp(`(?<![\\p{L}\\p{N}])${assistantName(ctx).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\p{L}\\p{N}])`, 'iu').test(name);
  const approval = !isMine && /discord/i.test(network(chat, accts)) && ['single', 'dm', 'direct'].includes(chat.type) && bot && /✅/u.test(message.text || '') && /❌/u.test(message.text || '') && /approv|confirm|allow|proceed|react|accept|reject|yes.*no/i.test(message.text || '');
  return { id: message.id, sender: isMine ? 'You' : name, mine: isMine, text: messageText(message, chat), at: message.timestamp ?? '', reactions: [...grouped.values()], replyTo: reply ? { sender: sender(reply, chat), text: messageText(reply, chat) } : null, approval: Boolean(approval) };
}

// ---- Unread semantics, mute and spam (BEEP-01/02) ----
// Spam verdicts live in the gateway store ('beeper-spam', keyed by chat ID). Nothing about spam is ever written
// upstream: no /read, archive, mute or PATCH. A verdict below the threshold changes nothing.
export const SPAM = 'beeper-spam';
export const spamThreshold = (ctx) => { const v = Number(ctx.config?.spamThreshold ?? 0.9); return Number.isFinite(v) && v > 0 && v <= 1 ? v : 0.9; };
export function spamVerdict(ctx, chat) {
  const id = typeof chat === 'string' ? chat : chat?.id;
  if (!id) return null;
  try { return ctx.store?.get(SPAM, id) ?? null; } catch { return null; }
}
export const isSpam = (ctx, chat, verdict = spamVerdict(ctx, chat)) => Boolean(verdict && verdict.verdict === 'spam' && (verdict.source === 'user' || (Number(verdict.confidence) || 0) >= spamThreshold(ctx)));
export const hasUnread = (chat) => (chat?.unreadCount ?? 0) > 0 || chat?.isMarkedUnread === true;
/** An unread @mention (Beeper's count), or the latest message names the owner (owner.name / owner.aliases). */
export const mentionsOwner = (chat, ctx = null) => (chat?.unreadMentionsCount ?? 0) > 0 ||
  Boolean(ctx && ownerPattern(ctx) && chat?.preview && chat.preview.isSender !== true && mentionsOwnerName(ctx, chat.preview.text));
export const isMuted = (chat) => chat?.isMuted === true;
export const isGroup = (chat) => chat?.type === 'group';
/** Listed on the glasses: everything except archived (low-priority chats are listed as quiet rows). */
export const listed = (chat) => !chat?.isArchived;
/** Muted chats stay silent unless they carry an unread @mention of the owner. */
export const silenced = (chat) => isMuted(chat) && !mentionsOwner(chat);
/** A conversation counts toward the badge: inbox (not archived/low priority), unread, not silenced, not spam. */
export const counts = (ctx, chat) => visible(chat) && hasUnread(chat) && !silenced(chat) && !isSpam(ctx, chat);
export function participantSummary(chat) {
  const all = participants(chat), people = all.filter(p => !p.isSelf && !p.isNetworkBot);
  return { count: Math.max(chat?.participants?.total ?? 0, all.length), names: people.map(firstName).filter(Boolean).slice(0, 3) };
}
/** Publishes the phone's `beeper` refresh hint. Only the newest one is kept in the event log (older ones are folded
 * into it while recent), so chat bursts never evict other events or replay a refresh per message after a reconnect. An empty
 * `chatIds` (reconcile) means "refresh every open thread" and is never narrowed by folding. */
export function publishBeeper(ctx, data = {}) {
  let ids = Array.isArray(data.chatIds) ? data.chatIds.filter(id => typeof id === 'string' && id) : [];
  if (ids.length) {
    let older = [];
    try { older = ctx.store?.all('events').filter(e => e.type === 'beeper' && ctx.now() - Date.parse(e.at) < 600000).flatMap(e => Array.isArray(e.chatIds) ? e.chatIds : []) ?? []; } catch {}
    ids = [...new Set([...ids, ...older])];
  }
  return ctx.events.publish('beeper', { ...data, chatIds: ids.slice(0, 50) }, { supersede: true });
}
/** `unread` counts CONVERSATIONS (it used to be messages); the message total is `unreadMessages`. */
export async function unreadSummary(ctx) {
  const all = await chats(ctx);
  const unread = all.filter(c => counts(ctx, c));
  const quiet = all.filter(c => visible(c) && hasUnread(c) && !counts(ctx, c));
  return { unread: unread.length, unreadChats: unread.length, unreadMessages: unread.reduce((sum, c) => sum + (c.unreadCount ?? 0), 0), names: unread.map(chatName),
    quiet: { muted: quiet.filter(silenced).length, spam: quiet.filter(c => isSpam(ctx, c)).length }, total: all.filter(listed).length };
}

const fold = (value) => String(value ?? '').normalize('NFKC').toLowerCase().replace(/\s+/g, ' ').trim();
/** Resolves a phone notification (Beeper Android) to a Desktop API chat by title, network and preview text.
 * There is no shared ID between the two, so an ambiguous match stays unresolved (callers then fail open). */
export async function resolveChat(ctx, input = {}) {
  const title = fold(input.conversationTitle || input.title || input.sender);
  if (!title) return { resolved: false, reason: 'no title', candidates: 0 };
  const [all, accts] = await Promise.all([chats(ctx), accounts(ctx).catch(() => [])]);
  let candidates = all.filter(c => listed(c) && [chatName(c), c.title, c.name].some(n => n && fold(n) === title));
  if (typeof input.isGroup === 'boolean') { const same = candidates.filter(c => isGroup(c) === input.isGroup); if (same.length) candidates = same; }
  const wanted = fold(input.network);
  if (wanted && candidates.length > 1) { const same = candidates.filter(c => { const n = fold(network(c, accts)); return n && (n.includes(wanted) || wanted.includes(n)); }); if (same.length) candidates = same; }
  const body = fold(plainText(input.text ?? input.body ?? '')).slice(0, 40);
  let chat = null, how = '';
  if (body && candidates.length) {
    const match = candidates.filter(c => fold(messageText(c.preview, c)).startsWith(body) || fold(plainText(c.preview?.text)).startsWith(body));
    if (match.length === 1) { chat = match[0]; how = 'preview'; } else if (match.length > 1) candidates = match;
  }
  if (!chat) {
    const recent = candidates.filter(c => Math.abs(ctx.now() - Date.parse(c.lastActivity || 0)) <= 10 * 60000);
    if (recent.length === 1) { chat = recent[0]; how = 'recent'; }
    else if (candidates.length === 1) { chat = candidates[0]; how = 'title'; }
  }
  if (!chat) return { resolved: false, reason: candidates.length ? 'ambiguous' : 'no match', candidates: candidates.length };
  const verdict = spamVerdict(ctx, chat), spam = isSpam(ctx, chat, verdict), quiet = silenced(chat);
  const low = !spam && ['otp', 'transactional'].includes(verdict?.category);
  return { resolved: true, match: how, chatId: chat.id, name: chatName(chat), network: network(chat, accts), isGroup: isGroup(chat), muted: isMuted(chat), mentions: chat.unreadMentionsCount ?? 0,
    spam: { spam, verdict: verdict?.verdict ?? null, confidence: verdict?.confidence ?? null, source: verdict?.source ?? null, category: verdict?.category ?? null },
    notify: !quiet && !spam, importance: low ? 0.3 : null, lowImportance: low, reason: quiet ? 'muted' : spam ? 'spam' : low ? verdict.category : null,
    open: { app: 'beeper', target: chat.id } };
}

// ---- Spoken mentions (VOX context) ----
// A Beeper mention is a Matrix "pill": a link to https://matrix.to/#/<participant id> labelled with the
// display name; the bridge turns it into the network's native mention. Which send-side input form the
// Client API converts to a pill is unverified, so the form sits behind config.beeperMentions.
const MATRIX_ID = /^@[^\s()<>[\]"'`]+:[A-Za-z0-9.-]+(?::\d+)?$/;
export const userLink = (chat) => Number(chat?.capabilities?.formatting?.user_link ?? 0);
/** Members of one chat for the cleanup prompt: numbered, filtered names; the model never sees Matrix ids. */
export async function mentionContext(ctx, chatId, { max = 60 } = {}) {
  const chat = await fullChat(ctx, { id: chatId });
  let others = participants(chat).filter(p => p?.id && !p.isSelf);
  if (others.length > max) {
    // Large groups: the people who spoke recently are the likely tags.
    try {
      const recent = items(await beeper(ctx, '/chats/' + encodeURIComponent(chatId) + '/messages', 'GET', undefined, { timeoutMs: 2500 }))
        .sort((a, b) => Date.parse(b.timestamp || 0) - Date.parse(a.timestamp || 0)).map(m => m.senderID);
      const rank = new Map(); recent.forEach((id, i) => { if (!rank.has(id)) rank.set(id, i); });
      others = [...others].sort((a, b) => (rank.get(a.id) ?? 1e9) - (rank.get(b.id) ?? 1e9));
    } catch {}
  }
  const people = [];
  for (const p of others) {
    const name = personName(firstName(p));
    if (!name) continue;
    const username = personName(p.username);
    people.push({ n: people.length + 1, id: p.id, name, ...(username && username.toLowerCase() !== name.toLowerCase() ? { username } : {}) });
    if (people.length >= max) break;
  }
  return { canMention: userLink(chat) >= 1 && chat?.type === 'group', type: chat?.type ?? null, people };
}
const markdownLabel = (name) => name.replace(/([\\[\]()*_`~<>#|!])/g, '\\$1');
/** The pill form: a Markdown link to the participant's matrix.to URL. */
export const pill = (name, id) => '[' + markdownLabel(name) + '](https://matrix.to/#/' + id + ')';
/** Replace each mention token ("@Zephyr") with the platform form. Unknown ids, missing tokens, mode
 * 'plain', or a chat without user_link leave plain "@Name" (Telegram: "@username" when known). */
export function applyMentions(value, mentions, chat, { mode = 'pill', network: net = '' } = {}) {
  const members = new Map(participants(chat).filter(p => p?.id && !p.isSelf).map(p => [p.id, p]));
  const pills = mode === 'pill' && userLink(chat) >= 1;
  let out = String(value), applied = 0;
  // Text already replaced (a pill's matrix.to URL contains "@<id>") is never searched again.
  const done = [];
  const inside = (at) => done.some(([s, e]) => at >= s && at < e);
  // Longest tokens first, so "@Al" never claims the start of "@Al Smith".
  const list = (Array.isArray(mentions) ? mentions : []).filter(m => typeof m?.token === 'string').sort((a, b) => b.token.length - a.token.length);
  for (const m of list) {
    const member = members.get(m.id);
    if (!member || !m.token.startsWith('@') || m.token.length < 2) continue;
    let at = out.indexOf(m.token);
    while (at >= 0 && (inside(at) || /[\p{L}\p{N}_]/u.test(out[at + m.token.length] ?? ''))) at = out.indexOf(m.token, at + 1);
    if (at < 0) continue;
    const name = m.token.slice(1);
    const username = typeof member.username === 'string' && /^[A-Za-z0-9_.]{2,64}$/.test(member.username) ? member.username : '';
    const replacement = pills && MATRIX_ID.test(member.id) ? pill(name, member.id) : /telegram/i.test(net) && username ? '@' + username : m.token;
    if (replacement === m.token) { done.push([at, at + m.token.length]); continue; }
    out = out.slice(0, at) + replacement + out.slice(at + m.token.length); applied++;
    const delta = replacement.length - m.token.length;
    for (const r of done) if (r[0] > at) { r[0] += delta; r[1] += delta; }
    done.push([at, at + replacement.length]);
  }
  return { text: out, applied };
}
