import { call, composioAccount } from './composio.mjs';
import { cached, invalidate, clamp, list } from './b-upstream.mjs';
import { HttpError } from '../../../lib/http.mjs';

// Provider argument names live here so live MCP diagnostics can correct them in one place.
// Page sizes are deliberately small: Composio replaces any tool response above roughly 30 KB with a lossy
// `data_preview` (arrays cut to one or two items, fields hidden behind "...": "N more fields"). Measured on
// 2026-11-17: 20 playlists = 28.7 KB (full), 30 = preview; recently played ~4.4 KB per track (no `market`
// parameter), so 5 per page; saved tracks with `market` ~2 KB per track, so 15 per page.
export const PAGE = { playlists: 15, recent: 5, liked: 15, fallback: 5 };
export const SPOTIFY = {
  state: ['SPOTIFY_GET_PLAYBACK_STATE', () => ({})],
  playlists: ['SPOTIFY_GET_CURRENT_USER_S_PLAYLISTS', ({ offset = 0, limit = PAGE.playlists } = {}) => ({ limit, offset })],
  recent: ['SPOTIFY_GET_RECENTLY_PLAYED_TRACKS', ({ before, limit = PAGE.recent } = {}) => ({ limit, ...(before ? { before } : {}) })],
  devices: ['SPOTIFY_GET_AVAILABLE_DEVICES', () => ({})],
  liked: ['SPOTIFY_GET_USER_S_SAVED_TRACKS', ({ offset = 0, limit = PAGE.liked } = {}) => ({ limit, offset, market: 'US' })],
  play: ['SPOTIFY_START_RESUME_PLAYBACK', x => x], pause: ['SPOTIFY_PAUSE_PLAYBACK', x => x],
  next: ['SPOTIFY_SKIP_TO_NEXT', x => x], previous: ['SPOTIFY_SKIP_TO_PREVIOUS', x => x],
  volume: ['SPOTIFY_SET_PLAYBACK_VOLUME', x => x], transfer: ['SPOTIFY_TRANSFER_PLAYBACK', x => x],
};

/** Composio answered with its lossy `data_preview` summary instead of the payload. Never treat it as data. */
export class TruncatedError extends HttpError {
  constructor() { super(502, 'Spotify response truncated by Composio'); }
}
// Composio wraps tool output inconsistently: {body}, {data}, {data:{data}}. Descend through wrapper-only
// objects until the Spotify payload itself. {successful, data_preview} (no data/body) is a truncated summary.
const WRAPPER_KEYS = new Set(['data', 'data_preview', 'body', 'successful', 'error', 'log_id', 'status', 'message']);
export function unwrap(x) {
  for (let i = 0; i < 4 && x && typeof x === 'object' && !Array.isArray(x); i++) {
    const keys = Object.keys(x);
    if (!keys.every((k) => WRAPPER_KEYS.has(k))) break;
    if (x.data_preview !== undefined && x.data == null && x.body == null) throw new TruncatedError();
    const inner = x.body ?? x.data;
    if (inner === undefined || inner === null || typeof inner !== 'object') break;
    x = inner;
  }
  return x;
}
// A preview can also hide inside an otherwise normal-looking page: "...49 more items" strings or
// objects carrying a "..." key.
const LOSSY_ITEM = /^\.\.\.\s*\d+ more (?:items?|fields?)$/;
export function rowsOf(page) {
  const rows = list(page);
  // Checked one level down as well: saved tracks hide `track.uri` behind {"...": "8 more fields"}.
  const lossy = v => (typeof v === 'string' && LOSSY_ITEM.test(v)) || (v !== null && typeof v === 'object' && !Array.isArray(v) && '...' in v);
  if (rows.some(r => lossy(r) || (r && typeof r === 'object' && Object.values(r).some(lossy)))) throw new TruncatedError();
  return rows;
}

const reason = e => String(e?.detail || e?.message || e).slice(0, 200);
const warn = (ctx, key, e) => { try { ctx.log?.('glasses spotify', key, reason(e)); } catch {} };
export async function spotify(ctx, key, args) {
  const [slug, build] = SPOTIFY[key];
  try { return unwrap(await call(ctx, slug, composioAccount(ctx, 'spotify'), build(args))); }
  catch (e) { warn(ctx, key, e); throw e; }
}

// One offset page; a truncated page is re-read as pages of PAGE.fallback covering the same range.
async function page(ctx, key, offset, limit) {
  try {
    const value = await spotify(ctx, key, { offset, limit });
    return { rows: rowsOf(value), total: Number(value?.total), href: value?.href };
  } catch (e) {
    if (!(e instanceof TruncatedError) || limit <= PAGE.fallback) throw e;
    const parts = [];
    for (let o = offset; o < offset + limit; o += PAGE.fallback) parts.push(o);
    const pages = await Promise.all(parts.map(o => page(ctx, key, o, PAGE.fallback)));
    return { rows: pages.flatMap(p => p.rows), total: pages.find(p => Number.isFinite(p.total))?.total, href: pages[0]?.href };
  }
}

export const playbackState = ctx => cached(ctx, 'spotify.state', 5000, () => spotify(ctx, 'state'));

// The user's library: names for recent contexts and the Playlists section. Names change rarely, so ten minutes.
// A library read with failed pages is kept for one minute only, then retried.
const PLAYLIST_CAP = 300, PLAYLIST_TTL = 10 * 60000, PARTIAL_TTL = 60000;
const userIdFrom = href => /\/users\/([^/?#]+)\//.exec(String(href ?? ''))?.[1];
function loadPlaylists(ctx) {
  return cached(ctx, 'spotify.playlists', PLAYLIST_TTL, async () => {
    const first = await page(ctx, 'playlists', 0, PAGE.playlists);
    const pages = [first];
    let partial = false;
    if (Number.isFinite(first.total)) {
      const offsets = [];
      for (let o = PAGE.playlists; o < Math.min(first.total, PLAYLIST_CAP); o += PAGE.playlists) offsets.push(o);
      const settled = await Promise.allSettled(offsets.map(o => page(ctx, 'playlists', o, PAGE.playlists)));
      for (const s of settled) if (s.status === 'fulfilled') pages.push(s.value); else partial = true;
    } else {
      // No total: walk sequentially until a short page.
      for (let o = PAGE.playlists, last = first; last.rows.length >= PAGE.playlists && o < PLAYLIST_CAP; o += PAGE.playlists) {
        try { pages.push(last = await page(ctx, 'playlists', o, PAGE.playlists)); } catch { partial = true; break; }
      }
    }
    const byUri = new Map();
    for (const p of pages.flatMap(x => x.rows)) {
      if (typeof p?.uri === 'string' && p.uri.startsWith('spotify:playlist:') && typeof p.name === 'string' && p.name.trim() && !byUri.has(p.uri)) byUri.set(p.uri, { uri: p.uri, name: p.name.trim() });
    }
    return { items: [...byUri.values()], userId: userIdFrom(first.href), partial, at: ctx.now() };
  });
}
async function library(ctx) {
  const value = await loadPlaylists(ctx);
  if (value.partial && ctx.now() - value.at >= PARTIAL_TTL) { invalidate(ctx, 'spotify.playlists'); return loadPlaylists(ctx); }
  return value;
}
const playlists = async ctx => (await library(ctx)).items;

// Distinct recently played context URIs, newest first: up to three pages of five tracks, stopping at eight contexts.
const RECENT_PAGES = 3, RECENT_CONTEXTS = 8;
const recentContexts = ctx => cached(ctx, 'spotify.recent', 60000, async () => {
  const uris = [];
  let before;
  for (let i = 0; i < RECENT_PAGES && uris.length < RECENT_CONTEXTS; i++) {
    let value;
    try { value = await spotify(ctx, 'recent', { before }); rowsOf(value); }
    catch (e) { if (!(e instanceof TruncatedError)) throw e; value = await spotify(ctx, 'recent', { before, limit: 2 }); }
    const rows = rowsOf(value);
    for (const item of rows) { const uri = item?.context?.uri; if (typeof uri === 'string' && !uris.includes(uri)) uris.push(uri); }
    before = value?.cursors?.before;
    if (!before || !rows.length) break;
  }
  return uris.slice(0, RECENT_CONTEXTS);
});

const LIKED = /^spotify:(?:user:[^:]+:)?collection$/;
export function mapNow(state, userPlaylists = []) {
  const item = state?.item ?? state?.track;
  if (!item) return null;
  const artist = (item.artists ?? []).map(a => a.name).filter(Boolean).join(', ') || item.show?.publisher || '';
  const context = state.context ?? {};
  const playlist = userPlaylists.find(p => p.uri === context.uri)?.name || context.name || context.metadata?.name || (LIKED.test(context.uri ?? '') ? 'Liked Songs' : item.album?.name) || '';
  return { source: 'spotify', title: item.name ?? '', artist, subtitle: [artist, playlist].filter(Boolean).join(' · '),
    positionSec: (state.progress_ms ?? state.progressMs ?? 0) / 1000, durationSec: (item.duration_ms ?? item.durationMs ?? 0) / 1000,
    playing: Boolean(state.is_playing ?? state.isPlaying), device: state.device?.name ?? '', volume: clamp(state.device?.volume_percent ?? state.device?.volumePercent, 0, 100), speed: 1 };
}
export async function nowPlaying(ctx) {
  const [state, userPlaylists] = await Promise.all([playbackState(ctx), playlists(ctx).catch(() => [])]);
  return mapNow(state, userPlaylists);
}
const DISCOVER_URI = /^spotify:playlist:[A-Za-z0-9]+$/;
const LIBRARY_ROWS = 20;
// Every row here is live: library playlists, true recent contexts, and a Discover Weekly found by name or
// configured by URI. A failed part is reported in `errors` (and logged) instead of silently vanishing.
export async function home(ctx) {
  const [state, lib, recent] = await Promise.allSettled([playbackState(ctx), playlists(ctx), recentContexts(ctx)]);
  const errors = [['playback', state], ['playlists', lib], ['recent', recent]].filter(([, r]) => r.status === 'rejected').map(([k]) => k);
  const userPlaylists = lib.status === 'fulfilled' ? lib.value : [];
  const byUri = new Map(userPlaylists.map(p => [p.uri, p]));
  const configured = ctx.config.spotifyDiscoverWeeklyUri;
  const discover = userPlaylists.find(p => p.name.toLowerCase() === 'discover weekly')
    ?? (typeof configured === 'string' && DISCOVER_URI.test(configured) ? { uri: configured, name: 'Discover Weekly' } : null);
  // A recent context outside the library has no trustworthy name; drop it rather than show "Playlist".
  const recentRows = (recent.status === 'fulfilled' ? recent.value : []).filter(uri => uri !== discover?.uri).map(uri => byUri.get(uri)).filter(Boolean).slice(0, RECENT_CONTEXTS);
  const shown = new Set([discover?.uri, ...recentRows.map(p => p.uri)]);
  return { discoverWeekly: discover ? { uri: discover.uri, name: discover.name } : null, liked: { uri: 'spotify:collection', name: 'Liked Songs' },
    current: state.status === 'fulfilled' ? mapNow(state.value, userPlaylists) : null,
    recent: recentRows.map(p => ({ uri: p.uri, name: p.name })),
    playlists: userPlaylists.filter(p => !shown.has(p.uri)).slice(0, LIBRARY_ROWS).map(p => ({ uri: p.uri, name: p.name })),
    errors };
}

export const NO_DEVICE = 'No active Spotify device';
const DEVICE_ID = /^[A-Za-z0-9_-]{1,128}$/;
async function activeDevice(ctx) {
  // Never select devices[0]; Spotify already knows which device the user is using.
  const state = await spotify(ctx, 'state');
  if (!state?.device?.id) throw new HttpError(409, NO_DEVICE);
  return state.device.id;
}
// Cold start (nothing active): with the default `spotifyColdStart: 'phone'`, play on the phone when it is the
// only available device; otherwise 409 so the glasses show the device picker. 'off' always answers 409.
async function playbackDevice(ctx) {
  try { return await activeDevice(ctx); } catch (e) {
    if (e?.status !== 409 || ctx.config.spotifyColdStart === 'off') throw e;
    const value = await spotify(ctx, 'devices').catch(() => { throw e; });
    const devices = (value?.devices ?? value?.items ?? []).filter(d => d?.id && !d.is_restricted);
    if (devices.length === 1 && String(devices[0].type).toLowerCase() === 'smartphone') { ctx.log?.('glasses spotify', 'cold start on the only device (phone)'); return devices[0].id; }
    throw e;
  }
}
async function likedTracks(ctx) {
  const settled = await Promise.allSettled([0, PAGE.liked, 2 * PAGE.liked].map(o => page(ctx, 'liked', o, PAGE.liked)));
  const uris = [];
  for (const s of settled) if (s.status === 'fulfilled') for (const x of s.value.rows) { const uri = (x?.track ?? x)?.uri; if (typeof uri === 'string' && uri.startsWith('spotify:track:') && !uris.includes(uri)) uris.push(uri); }
  return uris.slice(0, 50);
}
export async function play(ctx, uri, deviceId) {
  if (typeof uri !== 'string' || !/^spotify:(?:(?:playlist|album|track):[A-Za-z0-9]+|collection)$/.test(uri)) throw new HttpError(400, 'Invalid Spotify URI');
  if (deviceId !== undefined && deviceId !== null && (typeof deviceId !== 'string' || !DEVICE_ID.test(deviceId))) throw new HttpError(400, 'Invalid Spotify device');
  const device_id = deviceId || await playbackDevice(ctx);
  let args = uri.startsWith('spotify:track:') ? { uris: [uri] } : { context_uri: uri };
  if (uri === 'spotify:collection') {
    // `spotify:collection` is not a valid player context. Play saved tracks; failing that, the user's collection context.
    const uris = await likedTracks(ctx);
    if (uris.length) args = { uris };
    else {
      const userId = ctx.config.spotifyUserId || (await library(ctx).catch(() => null))?.userId;
      if (!userId || !/^[A-Za-z0-9._-]+$/.test(userId)) throw new HttpError(502, 'Liked Songs unavailable');
      args = { context_uri: `spotify:user:${userId}:collection` };
    }
  }
  await spotify(ctx, 'play', { device_id, ...args }); invalidate(ctx, 'spotify.state'); invalidate(ctx, 'spotify.recent'); return { ok: true };
}
export async function control(ctx, action, value) {
  if (!['play', 'pause', 'next', 'previous', 'volume'].includes(action)) throw new HttpError(400, 'Invalid Spotify action');
  if (action === 'volume' && (!Number.isFinite(value) || value < 0 || value > 100)) throw new HttpError(400, 'Volume must be 0–100');
  await spotify(ctx, action, { device_id: await activeDevice(ctx), ...(action === 'volume' ? { volume_percent: Math.round(value) } : {}) });
  invalidate(ctx, 'spotify.state'); return { ok: true };
}
