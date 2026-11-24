import { randomBytes } from 'node:crypto';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { readJson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { cached, invalidate, json, clamp, list } from '../lib/b-upstream.mjs';

async function abs(ctx, pathname, opts = {}) {
  if (!ctx.config.absUrl) throw new HttpError(503, 'Audiobookshelf is not configured (ABS_URL)');
  const token = await ctx.secret('AUDIOBOOKSHELF_TOKEN');
  return json(ctx, ctx.config.absUrl.replace(/\/(?:api)?\/?$/, '') + '/api' + pathname, { ...opts, headers: { Authorization: `Bearer ${token}` } });
}
const metadata = item => item?.media?.metadata ?? item?.libraryItem?.media?.metadata ?? item?.mediaMetadata ?? {};
const chapterAt = (chapters, seconds) => (chapters ?? []).find(c => seconds >= c.start && seconds < c.end)?.title ?? '';
// A book at or past this fraction counts as finished. Audiobookshelf leaves books that stopped in the
// closing credits (98-100%) unfinished, so without this they crowd out the books actually in progress.
// Also finished: `isFinished` and `hideFromContinueListening`. Read-only: nothing is marked finished in ABS.
export const ABS_FINISHED_AT = 0.97;
export const ABS_RECENT_DEFAULT = 5, ABS_RECENT_MAX = 8;
// ABS `progress`, or currentTime/duration when `progress` is missing or 0 for a started book (older rows).
const fraction = (p, duration = p?.duration) => {
  const stated = p?.progress === null || p?.progress === undefined ? NaN : Number(p.progress);
  if (Number.isFinite(stated) && stated > 0) return stated;
  return Number(duration) > 0 && Number(p?.currentTime) > 0 ? Number(p.currentTime) / Number(duration) : (Number.isFinite(stated) ? stated : 0);
};
// The user's media progress (GET /api/me -> mediaProgress), unfinished books only, most recently listened first.
export function inProgress(mediaProgress) {
  return list(mediaProgress).filter(p => p && typeof p.libraryItemId === 'string' && p.libraryItemId && !p.episodeId && !p.isFinished && !p.hideFromContinueListening
    && Number(p.currentTime) > 0 && fraction(p) < ABS_FINISHED_AT)
    .sort((a, b) => (Number(b.lastUpdate) || 0) - (Number(a.lastUpdate) || 0));
}
export function mapBook(item, override) {
  const meta = metadata(item), progress = override ?? item.userMediaProgress ?? item.mediaProgress ?? item.progress ?? {};
  const currentTimeSec = Number(progress.currentTime ?? item.currentTime ?? 0);
  const durationSec = Number(item.media?.duration ?? item.duration ?? item.libraryItem?.media?.duration ?? progress.duration ?? 0);
  return { id: item.libraryItemId ?? item.libraryItem?.id ?? item.id ?? progress.libraryItemId, title: meta.title ?? item.title ?? '', author: meta.authorName ?? meta.authors?.map(a => a.name).join(', ') ?? '',
    chapter: chapterAt(item.media?.chapters ?? item.chapters ?? item.libraryItem?.media?.chapters, currentTimeSec),
    progress: clamp(fraction({ ...progress, currentTime: currentTimeSec }, durationSec), 0, 1), durationSec, currentTimeSec,
    lastPlayedAt: Number(progress.lastUpdate) || 0 };
}
export function streamUrl(ctx, contentUrl, token) {
  // Copy only path + query onto the configured upstream; upstream hosts cannot redirect credentials.
  const base = new URL(ctx.config.absUrl);
  const upstream = new URL(contentUrl, base);
  const url = new URL(base.href);
  url.pathname = '/' + upstream.pathname.replace(/^\/+/, '');
  url.search = upstream.search;
  if (url.origin !== base.origin) throw new HttpError(502, 'Unexpected stream location');
  url.searchParams.set('token', token); return url.href;
}
export function register(r, ctx) {
  const tickets = new Map(), idleMs = 12 * 60 * 60 * 1000;
  function revoke(ticket) {
    const entry = tickets.get(ticket); tickets.delete(ticket);
    for (const abort of entry?.active ?? []) abort.abort();
  }
  function prune() {
    for (const [ticket, entry] of tickets) if (ctx.now() - entry.at >= idleMs) revoke(ticket);
  }
  r.get('/abs/stream/:ticket/:track', async ({ params, req, res }) => {
    prune();
    const entry = tickets.get(params.ticket);
    if (!entry || !/^(0|[1-9][0-9]*)$/.test(params.track) || !entry.tracks[Number(params.track)]) throw new HttpError(404, 'Stream unavailable');
    entry.at = ctx.now();
    const abort = new AbortController(); entry.active.add(abort);
    const disconnect = () => abort.abort();
    req.once('aborted', disconnect); res.once('close', disconnect);
    const timer = setTimeout(disconnect, 30000);
    let upstream;
    try {
      const token = await ctx.secret('AUDIOBOOKSHELF_TOKEN');
      if (abort.signal.aborted) throw new Error('Stream aborted');
      upstream = await ctx.fetch(streamUrl(ctx, entry.tracks[Number(params.track)], token), {
        redirect: 'manual', signal: abort.signal,
        headers: { 'Accept-Encoding': 'identity', ...(req.headers.range ? { Range: req.headers.range } : {}), ...(req.headers['if-range'] ? { 'If-Range': req.headers['if-range'] } : {}) },
      });
      clearTimeout(timer);
      if (![200, 206, 416].includes(upstream.status)) {
        if ([404, 410].includes(upstream.status)) revoke(params.ticket);
        throw new HttpError(502, 'Audio upstream unavailable');
      }
      const headers = { 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' };
      for (const name of ['Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges', 'ETag', 'Last-Modified']) {
        const value = upstream.headers.get(name); if (value !== null) headers[name] = value;
      }
      res.writeHead(upstream.status, headers);
      if (!upstream.body) { res.end(); return; }
      const activity = new Transform({ transform(chunk, encoding, done) { entry.at = ctx.now(); done(null, chunk); } });
      await pipeline(Readable.fromWeb(upstream.body), activity, res, { signal: abort.signal });
    } catch {
      if (!res.destroyed && !res.headersSent) throw new HttpError(502, 'Audio upstream unavailable');
      if (res.headersSent) res.destroy();
    } finally {
      clearTimeout(timer); entry.active.delete(abort);
      req.off('aborted', disconnect); res.off('close', disconnect);
      if (!upstream?.body?.locked) await upstream?.body?.cancel().catch(() => {});
      abort.abort();
    }
  }, { public: true });
  r.post('/abs/session/:id/close', async ({ params }) => {
    for (const [ticket, entry] of tickets) if (entry.sessionId === params.id) revoke(ticket);
    await abs(ctx, `/session/${encodeURIComponent(params.id)}/close`, { method: 'POST', body: {}, allowText: true });
    return { ok: true };
  });
  r.get('/abs/recent', async ({ query }) => {
    const books = await cached(ctx, 'abs.recent', 60000, async () => {
      const rows = inProgress((await abs(ctx, '/me'))?.mediaProgress).slice(0, ABS_RECENT_MAX);
      // items-in-progress rows are minified (no progress, no chapters); the expanded item has chapters.
      const items = await Promise.all(rows.map(p => abs(ctx, `/items/${encodeURIComponent(p.libraryItemId)}?expanded=1`)
        .catch(e => { ctx.log?.('glasses abs', 'item unavailable', e?.message); return null; })));
      return rows.map((p, i) => items[i] && mapBook(items[i], p)).filter(Boolean);
    });
    return books.slice(0, Math.round(clamp(query.limit || ABS_RECENT_DEFAULT, 1, ABS_RECENT_MAX)));
  });
  r.post('/abs/play/:id', async ({ params, req }) => {
    const session = await abs(ctx, `/items/${encodeURIComponent(params.id)}/play`, { method: 'POST', body: { supportedMimeTypes: ['audio/mpeg', 'audio/mp4', 'audio/ogg', 'audio/flac'], mediaPlayer: 'glasses-phone' } });
    prune();
    const ticket = randomBytes(32).toString('base64url');
    // The phone fetches the streams from here: publicUrl, else the address it used for this request.
    const host = String(req?.headers?.['x-forwarded-host'] ?? req?.headers?.host ?? '').split(',')[0].trim();
    const proto = String(req?.headers?.['x-forwarded-proto'] ?? 'http').split(',')[0].trim();
    let base;
    try { base = new URL(ctx.config.publicUrl || `${proto}://${host}`); } catch { throw new HttpError(503, 'Public gateway URL not configured'); }
    if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) throw new HttpError(503, 'Public gateway URL not configured');
    const ticketBase = base.href.replace(/\/$/, '') + '/glasses/v1/abs/stream/' + ticket;
    const startSec = Number(session.currentTime ?? session.startTime ?? 0);
    const tracks = (session.audioTracks ?? []).map((t, index) => ({ streamUrl: ticketBase + '/' + index, offsetSec: Number(t.startOffset ?? t.startTime ?? 0), durationSec: Number(t.duration ?? 0), title: t.title ?? '' }));
    const track = tracks.find(t => startSec >= t.offsetSec && startSec < t.offsetSec + t.durationSec) ?? tracks.findLast(t => t.offsetSec <= startSec) ?? tracks[0];
    if (!track) throw new HttpError(502, 'Audiobook stream unavailable');
    tickets.set(ticket, { sessionId: session.id ?? session.sessionId, tracks: session.audioTracks.map(t => t.contentUrl), at: ctx.now(), active: new Set() });
    while (tickets.size > 20) revoke(tickets.keys().next().value);
    const meta = metadata(session);
    return { streamUrl: track.streamUrl, startSec, title: meta.title ?? '', author: meta.authorName ?? meta.authors?.map(a => a.name).join(', ') ?? '',
      chapter: chapterAt(session.chapters ?? session.libraryItem?.media?.chapters, startSec), sessionId: session.id ?? session.sessionId,
      durationSec: Number(session.duration ?? session.libraryItem?.media?.duration ?? tracks.reduce((n, t) => Math.max(n, t.offsetSec + t.durationSec), 0)), ...(tracks.length > 1 ? { tracks } : {}) };
  });
  r.post('/abs/progress/:id', async ({ params, req }) => {
    const b = await readJson(req);
    if (typeof b.sessionId !== 'string' || !b.sessionId || !Number.isFinite(b.currentTimeSec) || b.currentTimeSec < 0 || !Number.isFinite(b.timeListenedSec) || b.timeListenedSec < 0) throw new HttpError(400, 'Invalid playback progress');
    // ABS answers `OK` as plain text. Once it has accepted the sync this must return 200, or the phone
    // re-sends the same listened seconds and ABS counts them again.
    await abs(ctx, `/session/${encodeURIComponent(b.sessionId)}/sync`, { method: 'POST', body: { currentTime: b.currentTimeSec, timeListened: b.timeListenedSec, libraryItemId: params.id }, allowText: true });
    invalidate(ctx, 'abs.recent'); return { ok: true };
  });
}
