// Gateway-owned T3 notifications (lib/t3-notify.mjs): the worker, and the glasses setting
// "Notify for all threads needing me" (GET/POST /t3/notify/settings {needsAll}).
import { readJson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { createT3Notifier, notifySettings, saveNotifySettings } from '../lib/t3-notify.mjs';

export function register(r, ctx) {
  ctx.t3notify = createT3Notifier(ctx);
  if (ctx.config.t3Notify === true) ctx.t3notify.start();
  r.get('/t3/notify/settings', () => notifySettings(ctx));
  r.post('/t3/notify/settings', async ({ req }) => {
    const b = await readJson(req);
    if (!b || typeof b !== 'object' || Array.isArray(b) || typeof b.needsAll !== 'boolean') throw new HttpError(400, 'needsAll (boolean) required');
    return saveNotifySettings(ctx, { needsAll: b.needsAll });
  });
}
