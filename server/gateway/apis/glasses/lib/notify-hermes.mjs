// POST /notify/hermes (scope glasses.agent): one completed Hermes exchange (an inbound message -> the assistant's final reply) on
// any channel except the glasses, sent by the glasses-notify Hermes plugin (server/hermes-plugins/glasses-notify).
// Routed like every notification (rules first, then the classifier with the prompt, the response and up to 5 earlier turns),
// then stored and published with the open target {app: 'joy'} (the phone's assistant screen).
//
// Body: {platform, chat, chatId?, threadId?, isDM, sender: 'owner'|'other'|'scheduler'|'system', senderName?,
//        inboundKind?: 'request'|'background-completion'|'cron-delivery'|'cron-prompt', delivered?, job?,
//        prompt: {at, text}, response: {at, text}, turns?: [{speaker, isOwner, messages: [{at, text}]}],
//        sessionId?, turnId?, debug?}
// With debug (evals) nothing is stored or published and no decision is remembered.
import { createHash } from 'node:crypto';
import { HttpError } from '../../../lib/http.mjs';
import { routeInput } from './notify-policy.mjs';
import { routeNotification } from './notify-route.mjs';
import { cleanContext, recentDecisions, annotate } from './notify-context.mjs';
import { ownerName, assistantName } from './persona.mjs';

const SENDERS = ['owner', 'other', 'scheduler', 'system'];
const INBOUND = ['request', 'background-completion', 'cron-delivery', 'cron-prompt'];
const str = (b, key, max, required = false) => {
  const v = b[key];
  if (v === undefined || v === null) { if (required) throw new HttpError(400, `${key} required`); return ''; }
  if (typeof v !== 'string') throw new HttpError(400, `${key} must be a string`);
  return v.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').slice(0, max);
};
const when = (v, name) => {
  if (v === undefined || v === null) return null;
  const t = typeof v === 'number' ? (v < 1e12 ? v * 1000 : v) : Date.parse(v);
  if (!Number.isFinite(t)) throw new HttpError(400, `Invalid ${name}`);
  return new Date(t).toISOString();
};
const tail = (s, max) => s.length > max ? '…' + s.slice(-(max - 1)) : s;

/** Validates and bounds the body. Returns {n (a routeInput), context, key, id, debug}. */
export function hermesInput(b, { now = Date.now(), owner = 'the user', assistant = 'Hermes' } = {}) {
  if (!b || typeof b !== 'object' || Array.isArray(b)) throw new HttpError(400, 'Exchange object required');
  const platform = str(b, 'platform', 40, true).toLowerCase().trim();
  if (!/^[a-z0-9_.-]{1,40}$/.test(platform)) throw new HttpError(400, 'Invalid platform');
  const chat = str(b, 'chat', 200), chatId = str(b, 'chatId', 200), threadId = str(b, 'threadId', 200);
  if (b.isDM !== undefined && typeof b.isDM !== 'boolean') throw new HttpError(400, 'Invalid isDM');
  if (b.delivered !== undefined && typeof b.delivered !== 'boolean') throw new HttpError(400, 'Invalid delivered');
  if (b.debug !== undefined && typeof b.debug !== 'boolean') throw new HttpError(400, 'Invalid debug flag');
  const sender = str(b, 'sender', 20).toLowerCase() || 'owner';
  if (!SENDERS.includes(sender)) throw new HttpError(400, 'Invalid sender');
  const inboundKind = str(b, 'inboundKind', 40) || (platform === 'cron' ? 'cron-prompt' : 'request');
  if (!INBOUND.includes(inboundKind)) throw new HttpError(400, 'Invalid inboundKind');
  const msg = (key, max) => {
    const m = b[key];
    if (!m || typeof m !== 'object' || Array.isArray(m)) throw new HttpError(400, `${key} {at, text} required`);
    return { at: when(m.at, key + '.at') ?? new Date(now).toISOString(), text: str(m, 'text', max) };
  };
  const prompt = msg('prompt', 6000), response = msg('response', 8000);
  if (b.turns !== undefined && !Array.isArray(b.turns)) throw new HttpError(400, 'turns must be an array');
  const job = str(b, 'job', 120), senderName = str(b, 'senderName', 80);
  const isDM = b.isDM === true;
  const category = b.delivered === false ? 'local' : platform === 'cron' || inboundKind === 'cron-delivery' ? 'scheduled' : inboundKind === 'background-completion' ? 'background' : 'reply';
  const title = (platform === 'cron' ? job || chat || 'Scheduled job' : isDM ? `${senderName || 'DM'}` : chat || platform).slice(0, 200);
  const n = routeInput({ kind: 'hermes', source: 'hermes:' + platform, app: assistant, title, conversationTitle: chat.slice(0, 200), sender: assistant,
    body: tail(response.text.trim(), 1000), isGroup: !isDM, channelId: (threadId || chatId).slice(0, 100), category, debug: b.debug === true });
  const key = `hermes:${platform}:${threadId || chatId || chat || 'default'}`;
  const context = cleanContext({ turns: b.turns ?? [], prompt: { ...prompt, isOwner: sender === 'owner', sender: sender === 'owner' ? owner : senderName || sender }, response,
    platform, chat, isDM, ...(job ? { job } : {}) });
  context.prompt.isOwner = sender === 'owner';
  if (sender !== 'owner') context.prompt.sender = senderName || ({ other: 'someone else', scheduler: 'the scheduler (a cron job)', system: `${assistant} itself (background work finished)` }[sender]);
  const sid = str(b, 'sessionId', 200), turnId = str(b, 'turnId', 200);
  const id = 'hermes:' + createHash('sha256').update([platform, threadId || chatId || chat, sid, turnId, response.at, response.text.slice(0, 200)].join('\u0000')).digest('hex').slice(0, 24);
  return { n, context, key, id, debug: b.debug === true, platform, response };
}

/** Routes, stores and publishes one exchange. Debug: route only. */
export async function notifyHermes(ctx, body, { debug: forceDebug = false } = {}) {
  const h = hermesInput(body, { now: ctx.now(), owner: ownerName(ctx), assistant: assistantName(ctx) });
  if (forceDebug) { h.debug = true; h.n.debug = true; }
  if (ctx.store.get('notifications', h.id)) return { route: 'duplicate', id: h.id, stored: false };
  // Earlier exchanges of this conversation that were routed get their marker (popup/stream/suppress).
  h.context.turns = annotate(h.context.turns, recentDecisions(ctx, h.key));
  const d = await routeNotification(ctx, h.n, { context: h.context, decisionKey: h.key });
  if (h.debug || d.route === 'suppress') return { ...d, id: h.id, stored: false };
  const preview = h.response.text.replace(/\s+/g, ' ').trim();
  const row = ctx.store.put('notifications', { id: h.id, source: assistantName(ctx), title: h.n.title || assistantName(ctx), body: preview.length > 1000 ? preview.slice(0, 999) + '…' : preview,
    importance: d.importance, icon: 'joy', route: d.route, reason: d.reason, kind: 'hermes', platform: h.platform,
    open: { screen: 'joy', app: 'joy' }, createdAt: new Date(ctx.now()).toISOString() });
  await ctx.store.flushed();
  ctx.events.publish('notification', row);
  return { ...d, id: h.id, stored: true };
}
