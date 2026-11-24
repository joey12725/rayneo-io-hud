import { readJson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { home, nowPlaying, play, control, spotify } from '../lib/spotify.mjs';
import { invalidate } from '../lib/b-upstream.mjs';
export function register(r, ctx) {
  r.get('/spotify/now', () => nowPlaying(ctx));
  r.get('/spotify/home', () => home(ctx));
  r.post('/spotify/play', async ({ req }) => { const b = await readJson(req); return play(ctx, b.uri, b.deviceId); });
  r.post('/spotify/control', async ({ req }) => { const b = await readJson(req); return control(ctx, b.action, b.value); });
  r.get('/spotify/devices', async () => {
    const value = await spotify(ctx, 'devices');
    return (value?.devices ?? value?.items ?? []).filter(d => typeof d?.id === 'string' && d.id).map(d => ({ id: d.id, name: d.name, type: d.type, active: Boolean(d.is_active ?? d.isActive) }));
  });
  r.post('/spotify/transfer', async ({ req }) => {
    const { deviceId } = await readJson(req);
    if (typeof deviceId !== 'string' || !deviceId.trim()) throw new HttpError(400, 'Device required');
    await spotify(ctx, 'transfer', { device_ids: [deviceId], play: true }); invalidate(ctx, 'spotify.state'); return { ok: true };
  });
}
