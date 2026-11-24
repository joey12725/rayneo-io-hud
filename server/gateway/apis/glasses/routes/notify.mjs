// Notification routing (POST /notify/route; POST /notify/hermes for the assistant's Hermes exchanges, scope glasses.agent) and
// T3 threads watched from the glasses (GET/POST /t3/watch).
import { readJson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { routeInput } from '../lib/notify-policy.mjs';
import { routeNotification, T3_WATCH, watchKey, pruneWatches } from '../lib/notify-route.mjs';
import { cleanContext, pruneDecisions } from '../lib/notify-context.mjs';
import { notifyHermes } from '../lib/notify-hermes.mjs';

const id = (value, name) => {
  if (typeof value !== 'string' || !value.trim() || value.length > 200 || /[\s:]/.test(value.trim())) throw new HttpError(400, `Invalid ${name}`);
  return value.trim();
};

export function register(r, ctx) {
  r.post('/notify/route', async ({ req, query }) => {
    const body = await readJson(req), input = routeInput(body);
    pruneDecisions(ctx);
    if (query?.debug === '1' || query?.debug === 'true') { input.debug = true; input.watched = body.watched === true; }
    // Production builds the conversation context itself (Beeper history + stored decisions); only an eval (debug)
    // may supply it, so a phone can never forge "already notified" markers or prompts.
    if (input.debug && body.context) input.context = cleanContext(body.context);
    return routeNotification(ctx, input);
  });
  r.post('/notify/hermes', async ({ req, query }) => {
    const body = await readJson(req);
    pruneDecisions(ctx);
    return notifyHermes(ctx, body, { debug: query?.debug === '1' || query?.debug === 'true' });
  }, { scope: 'glasses.agent' });
  // Watched T3 threads: the glasses notify only about these (needs you = popup, finished = stream).
  // Automatic watches (24 h after a glasses send/answer/new thread) are listed with `auto` and `expiresAt` until they lapse.
  r.get('/t3/watch', () => { pruneWatches(ctx); return ctx.store.all(T3_WATCH).map(({ id: key, machine, threadId, title, createdAt, expiresAt }) => ({ key, machine, id: threadId, title: title ?? '', createdAt, ...(expiresAt ? { auto: true, expiresAt } : {}) }))
    .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))); });
  r.post('/t3/watch', async ({ req }) => {
    const b = await readJson(req);
    if (!b || typeof b !== 'object' || Array.isArray(b)) throw new HttpError(400, 'Watch object required');
    const machine = id(b.machine, 'machine'), threadId = id(b.id, 'thread id');
    if (b.on !== undefined && typeof b.on !== 'boolean') throw new HttpError(400, 'Invalid on flag');
    if (b.title !== undefined && typeof b.title !== 'string') throw new HttpError(400, 'Invalid title');
    const key = watchKey(machine, threadId), on = b.on !== false;
    if (on) ctx.store.put(T3_WATCH, { id: key, machine, threadId, title: String(b.title ?? '').slice(0, 200), createdAt: ctx.store.get(T3_WATCH, key)?.createdAt ?? new Date(ctx.now()).toISOString() });
    else ctx.store.del(T3_WATCH, key);
    await ctx.store.flushed();
    return { ok: true, key, watching: on };
  });
}
