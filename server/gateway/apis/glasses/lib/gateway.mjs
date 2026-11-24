import { HttpError } from '../../../lib/http.mjs';

export function text(value, name = 'text', max = 32000) {
  if (typeof value !== 'string' || !value.trim() || value.length > max) throw new HttpError(400, `Invalid ${name}`);
  return value.trim();
}

export const limit = (value, fallback = 25, max = 100) => Math.max(1, Math.min(max, Number(value) || fallback));
export const items = (value) => Array.isArray(value) ? value : value?.items ?? value?.data ?? [];

// Only generic errors leave an adapter; upstream bodies/URLs may contain credentials.
export async function request(ctx, url, { method = 'GET', body, headers = {}, timeoutMs = 8000, signal } = {}) {
  try {
    const res = await ctx.fetch(url, {
      method, redirect: 'error', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs),
      headers: { Accept: 'application/json', ...(body !== undefined && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}), ...headers },
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
    });
    if (!res.ok) { await res.body?.cancel(); throw new HttpError(res.status === 401 ? 502 : res.status === 404 ? 404 : res.status === 409 ? 409 : 502, res.status === 401 ? 'Upstream authentication needs attention' : 'Upstream request failed', { needsRepair: res.status === 401, upstreamStatus: res.status }); }
    if (res.status === 204) return null;
    return await res.json();
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(502, 'Upstream unavailable', { network: true, ...(e?.name === 'TimeoutError' ? { timeout: true } : {}) });
  }
}

const caches = new WeakMap();
export function cached(ctx, key, ttl, fn) {
  let cache = caches.get(ctx); if (!cache) caches.set(ctx, cache = new Map());
  const hit = cache.get(key);
  if (hit && ctx.now() - hit.at < hit.ttl) return hit.value;
  const value = Promise.resolve().then(fn);
  cache.set(key, { at: ctx.now(), ttl, value });
  // Prune expired entries and cap the size so per-thread projections don't accumulate forever.
  if (cache.size > 64) {
    for (const [k, v] of cache) if (ctx.now() - v.at >= v.ttl) cache.delete(k);
    while (cache.size > 256) cache.delete(cache.keys().next().value);
  }
  value.catch(() => { if (cache.get(key)?.value === value) cache.delete(key); });
  return value;
}
export function invalidate(ctx, prefix) {
  const cache = caches.get(ctx);
  if (cache) for (const key of cache.keys()) if (key.startsWith(prefix)) cache.delete(key);
}

// Small insertion-ordered LRU for session aliases.
export function lru(max) {
  const values = new Map();
  return {
    has: key => values.has(key),
    get(key) { const value = values.get(key); if (values.has(key)) { values.delete(key); values.set(key, value); } return value; },
    set(key, value) { values.delete(key); values.set(key, value); while (values.size > max) values.delete(values.keys().next().value); },
    get size() { return values.size; },
  };
}

// One budget covers every page and secret lookup; race also bounds injected/hung transports.
export function deadline(ctx, timeoutMs = 15000) {
  const until = ctx.now() + timeoutMs, abort = new AbortController();
  const error = new HttpError(504, 'Upstream deadline exceeded');
  let rejectTimeout;
  const timeout = new Promise((_, reject) => { rejectTimeout = reject; });
  timeout.catch(() => {});
  const timer = setTimeout(() => { abort.abort(); rejectTimeout(error); }, timeoutMs);
  return {
    signal: abort.signal,
    expired: () => abort.signal.aborted || ctx.now() >= until,
    run(fn) {
      if (abort.signal.aborted || ctx.now() >= until) { abort.abort(); return Promise.reject(error); }
      return Promise.race([Promise.resolve().then(fn), timeout]);
    },
    close() { clearTimeout(timer); abort.abort(); },
  };
}
