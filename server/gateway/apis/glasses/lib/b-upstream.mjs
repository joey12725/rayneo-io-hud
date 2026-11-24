import { HttpError } from '../../../lib/http.mjs';

// All errors crossing this boundary are deliberately credential-free.
// `allowText`: a 2xx answer whose body is not JSON (Audiobookshelf session sync/close reply with plain `OK`)
// is a success and resolves to null instead of failing as 502.
export async function json(ctx, url, { method = 'GET', headers = {}, body, timeoutMs = 15000, allowText = false } = {}) {
  try {
    const response = await ctx.fetch(url, {
      method, redirect: 'error', headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) throw new Error('status');
    if (allowText) { if (response.status === 204) return null; const text = await response.text(); try { return text ? JSON.parse(text) : null; } catch { return null; } }
    return response.status === 204 ? null : await response.json();
  } catch { throw new HttpError(502, 'Service unavailable'); }
}

const caches = new WeakMap();
export function cached(ctx, key, ttl, load) {
  let cache = caches.get(ctx); if (!cache) caches.set(ctx, cache = new Map());
  const hit = cache.get(key);
  if (hit && hit.until > ctx.now()) return hit.promise;
  const promise = Promise.resolve().then(load).catch((error) => { cache.delete(key); throw error; });
  cache.set(key, { until: ctx.now() + ttl, promise });
  return promise;
}
export function invalidate(ctx, key) { caches.get(ctx)?.delete(key); }
export const clamp = (n, min, max) => Math.max(min, Math.min(max, Number(n) || 0));
export const list = (value) => {
  const rows = Array.isArray(value) ? value : value?.items ?? value?.data;
  return Array.isArray(rows) ? rows : [];
};
export const isoNow = (ctx) => new Date(ctx.now()).toISOString();
export function localDate(value, tz = 'UTC') {
  return new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value));
}
export function shiftDate(date, days) { return new Date(Date.parse(date + 'T12:00:00Z') + days * 86400000).toISOString().slice(0, 10); }
export function midnight(date, tz = 'UTC') {
  let ms = Date.parse(date + 'T00:00:00Z');
  for (let i = 0; i < 3; i++) {
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(ms).map(p => [p.type, p.value]));
    ms += Date.parse(date + 'T00:00:00Z') - Date.parse(`${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}Z`);
  }
  return new Date(ms).toISOString();
}
