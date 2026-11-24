import { readJson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { text } from '../lib/gateway.mjs';
import { routeInput, legacy, policyFor } from '../lib/notify-policy.mjs';
import { routeNotification } from '../lib/notify-route.mjs';
import { assistantName, mentionsOwnerName } from '../lib/persona.mjs';

const ICONS = new Set('sun cloud moon calendar t3 news party health todo note beeper media book spotify youtube joy bell people home docs prompter mic status settings glasses ring buds phone bright plug printer bulb alarm env gauge plus archive check cross warn clock wifi cell spin'.split(' '));

export function notificationInput(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new HttpError(400, 'Notification object required');
  const result = Object.fromEntries(Object.entries({ source: 100, app: 100, title: 200, body: 1000, conversationTitle: 200, sender: 100, network: 100 }).map(([key, max]) => {
    if (input[key] !== undefined && typeof input[key] !== 'string') throw new HttpError(400, 'Notification fields must be strings');
    return [key, (input[key] ?? '').slice(0, max)];
  }));
  if (input.isGroupConversation !== undefined && typeof input.isGroupConversation !== 'boolean') throw new HttpError(400, 'Invalid group flag');
  return { ...result, isGroupConversation: input.isGroupConversation === true };
}
function smallObject(value, name) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Buffer.byteLength(JSON.stringify(value)) > 2048) throw new HttpError(400, `Invalid ${name} (maximum 2 KB)`);
  return value;
}
// Shared by POST /assistant/notify, /assistant/doc and the MCP tools glasses_notify / glasses_push_doc.
export function assistantNotify(ctx, b = {}) {
  const importance = b.importance ?? 0.85;
  if (typeof importance !== 'number' || !Number.isFinite(importance) || importance < 0 || importance > 1) throw new HttpError(400, 'Invalid importance');
  if (b.icon !== undefined && !ICONS.has(b.icon)) throw new HttpError(400, 'Invalid icon');
  if (b.popup !== undefined && typeof b.popup !== 'boolean') throw new HttpError(400, 'Invalid popup flag');
  // popup: the assistant asks for a card that lights the lens (POST /notify/route semantics); otherwise it only joins the list.
  const data = { source: assistantName(ctx), title: text(b.title, 'title', 200), body: text(b.body, 'body'), importance, icon: b.icon || 'joy', open: smallObject(b.open ?? { screen: 'joy' }, 'open'), route: b.popup === true ? 'popup' : 'stream' };
  const notification = ctx.store.put('notifications', { ...data, createdAt: new Date(ctx.now()).toISOString() });
  ctx.events.publish('notification', notification); return { ok: true, id: notification.id };
}
export async function assistantDoc(ctx, b = {}) {
  const doc = ctx.store.put('docs', { title: text(b.title, 'title', 200), text: text(b.text, 'text', 200000), createdAt: new Date(ctx.now()).toISOString() });
  await ctx.store.flushed();
  ctx.events.publish('doc', { id: doc.id, title: doc.title }); return { id: doc.id };
}
export function notificationRule(input, ctx = null) {
  input = notificationInput(input);
  const source = `${input.source || ''} ${input.app || ''}`;
  const value = `${input.title || ''} ${input.body || ''}`;
  const assistant = assistantName(ctx).toLowerCase();
  if (source.toLowerCase().split(/[^\p{L}\p{N}]+/u).includes(assistant) || /\bhermes\b/i.test(source) || /\bt3(?:[ _-]*code)?\b/i.test(source) || /alarm|emergency|security alert/i.test(source)) return { route: 'notify', importance: 0.95 };
  if (input.isGroupConversation && (mentionsOwnerName(ctx, input.body) || /\?|\b(?:for you|let's|lets|meet|dinner|lunch|plans?)\b/i.test(input.body))) return { route: 'notify', importance: 0.75 };
  if (/unsubscribe|newsletter|limited.time offer|promo(tion)?|\bsale\b|discount|\b\d+% off\b/i.test(value)) return { route: 'suppress', importance: 0.1 };
  if ((input.isGroupConversation || /group|discord|slack/i.test(source)) && /^(lol|lmao|haha+|😂|👍|nice|same|\+1)[!.\s]*$/iu.test(String(input.body || '').trim())) return { route: 'suppress', importance: 0.1 };
  return null;
}

function reminders(ctx) {
  for (const item of ctx.store.all('reminders')) if (Date.parse(item.expiresAt) <= ctx.now()) ctx.store.del('reminders', item.id);
  return ctx.store.all('reminders');
}
async function addReminder(ctx, body) {
  const content = text(body.text);
  const condition = body.condition;
  if (!condition || !['place', 'person'].includes(condition.type)) throw new HttpError(400, 'Invalid condition');
  const value = text(condition.value, 'condition', 500);
  const expires = body.expiresAt ? Date.parse(body.expiresAt) : ctx.now() + 30 * 86400000;
  if (!Number.isFinite(expires) || expires <= ctx.now()) throw new HttpError(400, 'Expiry must be in the future');
  const reminder = ctx.store.put('reminders', { text: content, condition: { type: condition.type, value }, expiresAt: new Date(expires).toISOString() });
  // The store prunes expired reminders on write; never answer with nothing (the request would hang).
  if (!reminder) throw new HttpError(400, 'Expiry must be in the future');
  await ctx.store.flushed();
  ctx.events.publish('reminder', { ...reminder, state: 'armed' });
  return reminder;
}
export function register(r, ctx) {
  const agent = { scope: 'glasses.agent' };
  r.get('/events', async ({ query }) => {
    const wait = Math.max(0, Math.min(25, Number(query.wait ?? 25) || 0));
    const events = await ctx.events.wait(query.since, wait * 1000);
    // The cursor covers exactly the delivered events, so a publish racing this poll is never skipped.
    const cursor = events.length ? events.at(-1).seq : Math.max(0, Number(query.since) || 0);
    return { cursor, events };
  });
  // Old phone builds: a thin wrapper over POST /notify/route with the old {route: notify|suppress, importance} shape.
  r.post('/notify/classify', async ({ req }) => {
    const input = routeInput(notificationInput(await readJson(req)), policyFor(ctx), assistantName(ctx));
    return legacy(await routeNotification(ctx, input));
  });
  r.get('/reminders', () => reminders(ctx));
  r.post('/reminders', async ({ req }) => addReminder(ctx, await readJson(req)));
  r.del('/reminders/:id', ({ params }) => {
    ctx.store.del('reminders', params.id); ctx.events.publish('reminder', { id: params.id, deleted: true }); return { ok: true };
  });
  r.get('/docs', () => ctx.store.all('docs').map(({ id, title, createdAt }) => ({ id, title, createdAt })).sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
  r.get('/docs/:id', ({ params }) => {
    const doc = ctx.store.get('docs', params.id); if (!doc) throw new HttpError(404, 'Document not found');
    return { id: doc.id, title: doc.title, text: doc.text };
  });
  r.del('/docs/:id', ({ params }) => { if (ctx.store.del('docs', params.id)) ctx.events.publish('doc', { id: params.id, deleted: true }); return { ok: true }; });
  r.post('/assistant/notify', async ({ req }) => assistantNotify(ctx, await readJson(req)), agent);
  r.post('/assistant/doc', async ({ req }) => assistantDoc(ctx, await readJson(req)), agent);
  r.post('/assistant/open', async ({ req }) => {
    const b = await readJson(req); const data = { screen: text(b.screen, 'screen', 100), args: smallObject(b.args ?? {}, 'args') };
    ctx.store.put('state', { id: 'screen', ...data }); ctx.events.publish('open', data); return { ok: true };
  }, agent);
  r.post('/assistant/media', async ({ req }) => {
    const b = smallObject(await readJson(req), 'media'); text(b.action, 'action', 100);
    // Do not allow caller-supplied event envelope fields to replace the event type/cursor.
    const { id, seq, type, at, ...data } = b;
    ctx.store.put('state', { ...data, id: 'media' }); ctx.events.publish('media', data); return { ok: true };
  }, agent);
  r.post('/assistant/reminder', async ({ req }) => addReminder(ctx, await readJson(req)), agent);
  r.get('/assistant/state', () => ({ screen: ctx.store.get('state', 'screen'), media: ctx.store.get('state', 'media'), reminders: reminders(ctx), docs: ctx.store.all('docs').map(({ id, title, createdAt }) => ({ id, title, createdAt })) }), agent);
}
