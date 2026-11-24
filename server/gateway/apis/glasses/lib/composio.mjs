import { HttpError } from '../../../lib/http.mjs';

export const READ_ONLY_SLUGS = new Set([
  'SPOTIFY_GET_CURRENTLY_PLAYING_TRACK', 'SPOTIFY_GET_PLAYBACK_STATE', 'SPOTIFY_GET_AVAILABLE_DEVICES',
  'SPOTIFY_GET_RECENTLY_PLAYED_TRACKS', 'SPOTIFY_GET_CURRENT_USER_S_PLAYLISTS', 'SPOTIFY_GET_USER_S_SAVED_TRACKS',
  'GOOGLECALENDAR_LIST_CALENDARS', 'GOOGLECALENDAR_EVENTS_LIST',
]);
const decode = value => { if (typeof value !== 'string') return value; try { return JSON.parse(value); } catch { return null; } };

export function parseEnvelope(text) {
  const direct = decode(text);
  if (direct) return direct;
  // SSE messages may contain several data lines, progress notifications, and a final JSON-RPC response.
  let final;
  for (const block of text.replace(/\r\n/g, '\n').split(/\n\n+/)) {
    const data = block.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n');
    const candidate = decode(data);
    if (candidate && (candidate.result || candidate.error)) final = candidate;
  }
  if (!final) throw new HttpError(502, 'Composio response unavailable');
  return final;
}

// A short provider explanation for logs only (never returned to clients): URLs and long tokens are removed.
export function failureReason(row, response) {
  const raw = [row?.error, response?.error, response?.message, response?.data?.message].find(v => v != null);
  const text = typeof raw === 'string' ? raw : raw?.message ?? (raw ? JSON.stringify(raw) : '');
  return String(text).replace(/https?:\/\/\S+/g, '[url]').replace(/[A-Za-z0-9_\-]{32,}/g, '[redacted]').slice(0, 160);
}
export function outcomes(envelope, tools) {
  if (envelope.error || envelope.result?.isError) throw new HttpError(502, 'Composio request failed');
  const result = envelope.result;
  const payloads = (result?.content ?? []).filter(c => c.type === 'text' || c.text).map(c => decode(c.text)).filter(Boolean);
  const payload = payloads.find(p => p?.data?.results || p?.results) ?? result;
  const rows = payload?.data?.results ?? payload?.results;
  if (!Array.isArray(rows)) throw new HttpError(502, 'Composio response unavailable');
  const used = new Set();
  return tools.map((tool, i) => {
    let index = rows.findIndex((r, j) => !used.has(j) && (r.tool_slug ?? r.toolSlug) === tool.tool_slug);
    if (index < 0) index = used.has(i) ? rows.findIndex((_, j) => !used.has(j)) : i;
    used.add(index);
    const row = rows[index];
    const response = decode(row?.response ?? row);
    const successful = row?.successful !== false && row?.success !== false && !row?.error && response?.successful !== false && response?.success !== false && !!response && !response.error;
    return { slug: tool.tool_slug, successful, data: successful ? decode(response.data ?? response) : null,
      ...(successful ? {} : { error: 'Tool unavailable', ...(failureReason(row, response) ? { reason: failureReason(row, response) } : {}) }) };
  });
}

export async function execute(ctx, tools, { signal } = {}) {
  try {
    const [url, token] = await Promise.all([ctx.secret('COMPOSIO_MCP_URL'), ctx.secret('COMPOSIO_MCP_TOKEN')]);
    const response = await ctx.fetch(url, { method: 'POST', redirect: 'error', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'COMPOSIO_MULTI_EXECUTE_TOOL', arguments: {
        tools, sync_response_to_workbench: false, current_step: 'GLASSES', current_step_metric: '1/1', thought: 'glasses request',
      } } }), signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(20000)]) : AbortSignal.timeout(20000) });
    if (!response.ok) throw new Error('status');
    return outcomes(parseEnvelope(await response.text()), tools);
  } catch { throw new HttpError(502, 'Composio unavailable'); }
}
/** The Composio connected-account id for an app ('calendar' or 'spotify'): composio.calendarAccount / spotifyAccount. */
export function composioAccount(ctx, app) {
  const id = ctx.config.composio?.[app + 'Account'];
  if (!id) throw new HttpError(503, `${app === 'calendar' ? 'Calendar' : 'Spotify'} is not configured (set COMPOSIO_${app.toUpperCase()}_ACCOUNT)`);
  return id;
}
export async function call(ctx, slug, account, args = {}, options) {
  const [outcome] = await execute(ctx, [{ tool_slug: slug, account, arguments: args }], options);
  if (!outcome.successful) throw new HttpError(502, 'Composio tool unavailable', outcome.reason || undefined);
  return outcome.data;
}

export function register(r, ctx) {
  r.get('/_diag/composio', async ({ query }) => {
    if (!READ_ONLY_SLUGS.has(query.slug)) throw new HttpError(400, 'Read-only tool required');
    let args = {}; try { args = JSON.parse(query.args ?? '{}'); } catch { throw new HttpError(400, 'Invalid tool arguments'); }
    if (!args || Array.isArray(args) || typeof args !== 'object') throw new HttpError(400, 'Invalid tool arguments');
    const account = query.account || composioAccount(ctx, query.slug.startsWith('SPOTIFY_') ? 'spotify' : 'calendar');
    return execute(ctx, [{ tool_slug: query.slug, account, arguments: args }]);
  });
}
