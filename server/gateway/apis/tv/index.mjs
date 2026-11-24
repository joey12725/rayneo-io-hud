// TV launcher API: mounted at /tv/v1/*, scope `tv` (token secret TV_LAUNCHER_TOKEN in GATEWAY_CLIENTS).
// Serves an Android TV launcher the things a TV can't do on its own:
//   GET  /tv/v1/spotify/recent-playlists?limit=6       recently played playlists (via the glasses Spotify adapter)
//   GET  /tv/v1/share/destinations?limit=25&pinned=a,b Beeper chats as {id, name, network, pinned} only
//   POST /tv/v1/share/jam {destinationId, room}         sends the Spotify Jam link to one chat
// Privacy rule: the TV never receives message content, previews or participants. Destinations carry exactly
// four fields, whatever Beeper returns.
import { readJson } from '../../lib/web.mjs';
import { HttpError } from '../../lib/http.mjs';
import { home, playbackState } from '../glasses/lib/spotify.mjs';
import { beeper, chats, accounts, chatName, network, visible } from '../glasses/lib/beeper.mjs';
import { request, cached } from '../glasses/lib/gateway.mjs';
import { loadConfig } from '../../lib/config.mjs';

const CHAT_ID = /^![A-Za-z0-9]{6,64}:[A-Za-z0-9.-]{1,64}$/;
const HTTP_URL = /^https:\/\/[^\s<>"']{4,500}$/;
const ENTITY = /^input_text\.[a-z0-9_]{1,80}$/;

export function createTvContext(api, deps = {}) {
  const env = api.env ?? {};
  const c = api.config ?? loadConfig({ env, file: null });
  return {
    env, log: api.log, store: api.store, secrets: api.secrets,
    fetch: deps.fetch ?? globalThis.fetch,
    now: deps.now ?? (() => Date.now()),
    secret: (name, opts) => api.secrets.get(name, opts),
    events: { publish: () => {} },
    config: {
      beeperEnabled: c.beeper.enabled, beeperUrl: c.beeper.url, beeperTokenSecret: c.beeper.tokenSecret,
      paginationBudgetMs: 15000,
      spotifyColdStart: 'off',
      spotifyDiscoverWeeklyUri: c.spotify.discoverWeeklyUri,
      spotifyUserId: c.spotify.userId,
      composio: { ...c.composio },
      tz: c.tz,
      // Optional HA input_text holding a pasted Spotify Jam link (Spotify's API cannot create Jam links).
      jamLinkEntity: ENTITY.test(c.tv.jamLinkEntity || '') ? c.tv.jamLinkEntity : '',
      room: c.tv.room,
      ...deps.config,
    },
  };
}

const clampInt = (v, d, max) => Math.max(1, Math.min(max, Number.parseInt(v, 10) || d));
const cleanRoom = (v, fallback = 'living room') => (typeof v === 'string' ? v.replace(/\s+/g, ' ').trim().slice(0, 40) : '') || fallback;

export async function recentPlaylists(ctx, limit) {
  const h = await home(ctx);
  return { playlists: h.recent.slice(0, limit).map(p => ({ uri: p.uri, name: p.name, image: null, owner: null })), partial: h.errors.includes('recent') };
}

export async function destinations(ctx, limit, pinnedIds = []) {
  const [all, accts] = await Promise.all([chats(ctx), accounts(ctx).catch(() => [])]);
  const row = (c, pinned) => ({ id: c.id, name: chatName(c), network: network(c, accts), pinned });
  const byId = new Map(all.map(c => [c.id, c]));
  const pinned = pinnedIds.map(id => byId.get(id)).filter(c => c && !c.isArchived).map(c => row(c, true));
  const taken = new Set(pinned.map(p => p.id));
  const recent = all.filter(c => !taken.has(c.id) && visible(c) && !c.isReadOnly).slice(0, limit).map(c => row(c, false));
  return { destinations: [...pinned, ...recent] };
}

async function haJamLink(ctx) {
  if (!ctx.config.jamLinkEntity) return null;
  try {
    const [url, token] = await Promise.all([ctx.secret('HOMEASSISTANT_URL'), ctx.secret('HOMEASSISTANT_TOKEN')]);
    const s = await request(ctx, String(url).replace(/\/+$/, '').replace(/\/api$/, '') + '/api/states/' + ctx.config.jamLinkEntity,
      { headers: { Authorization: 'Bearer ' + token }, timeoutMs: 5000 });
    const v = String(s?.state ?? '').trim();
    return HTTP_URL.test(v) && /(^|\.)spotify\.(com|link)\//.test(new URL(v).hostname + '/') ? v : null;
  } catch { return null; }
}

function nowPlayingLink(state) {
  const item = state?.item ?? state?.track;
  const ctxUrl = state?.context?.external_urls?.spotify;
  const url = (typeof ctxUrl === 'string' && ctxUrl) || item?.external_urls?.spotify;
  if (typeof url !== 'string' || !HTTP_URL.test(url)) return null;
  const artist = (item?.artists ?? []).map(a => a.name).filter(Boolean).join(', ');
  return { url, title: [item?.name, artist].filter(Boolean).join(' — ') };
}

export async function sendJam(ctx, destinationId, room) {
  if (typeof destinationId !== 'string' || !CHAT_ID.test(destinationId)) throw new HttpError(400, 'Invalid destination');
  // Only chats Beeper lists (not archived, writable) can receive the link.
  const chat = (await chats(ctx)).find(c => c.id === destinationId);
  if (!chat || chat.isArchived || chat.isReadOnly) throw new HttpError(404, 'That conversation is not available');
  let text;
  const jam = await haJamLink(ctx);
  if (jam) text = `🎧 Join the Spotify Jam in the ${room}: ${jam}`;
  else {
    let state = null;
    try { state = await playbackState(ctx); } catch {}
    const now = nowPlayingLink(state);
    if (!now) throw new HttpError(409, 'Nothing is playing on Spotify and no Jam link is set');
    text = `🎧 Listening in the ${room}${now.title ? ` — ${now.title}` : ''}: ${now.url}`;
  }
  await beeper(ctx, '/chats/' + encodeURIComponent(destinationId) + '/messages', 'POST', { text });
  return { ok: true, kind: jam ? 'jam' : 'now_playing' };
}

export async function register(api, deps = {}) {
  const ctx = createTvContext(api, deps);
  api.route('GET', '/v1/ping', () => ({ ok: true }), { public: true });
  api.route('GET', '/v1/spotify/recent-playlists', ({ query }) =>
    cached(ctx, 'tv.recent.' + query.limit, 60000, () => recentPlaylists(ctx, clampInt(query.limit, 6, 20))));
  api.route('GET', '/v1/share/destinations', ({ query }) => {
    const pinned = String(query.pinned ?? '').split(',').map(s => s.trim()).filter(id => CHAT_ID.test(id)).slice(0, 5);
    return destinations(ctx, clampInt(query.limit, 25, 50), pinned);
  });
  api.route('POST', '/v1/share/jam', async ({ req }) => {
    const b = await readJson(req);
    return sendJam(ctx, b.destinationId, cleanRoom(b.room, ctx.config.room));
  });
  return ctx;
}
