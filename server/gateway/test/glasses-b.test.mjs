import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { execute, parseEnvelope, outcomes } from '../apis/glasses/lib/composio.mjs';
import { groupEvents, mapEvent } from '../apis/glasses/lib/calendar.mjs';
import { getWeather } from '../apis/glasses/lib/weather.mjs';
import { assembleHealth } from '../apis/glasses/routes/health.mjs';
import { validateReport, REPORT_PROMPT } from '../apis/glasses/routes/report.mjs';

const NOW = Date.parse('2026-11-12T18:00:00Z');
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const report = (date = '2026-11-12') => ({ date, generatedAt: '2026-11-12T12:00:00Z', cards: Array.from({ length: 6 }, (_, i) => ({ type: 'note', icon: 'joy', heading: `Card ${i}`, body: 'The toaster has opinions. Breakfast first; dashboards later.' })) });
function envelope(results) {
  return { jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: JSON.stringify({ data: { results } }) }] } };
}
function mcp(slug, data, successful = true) { return { tool_slug: slug, response: { successful, data } }; }
function fakeUpstreams({ tools = {}, http = () => { throw new Error('Unexpected HTTP'); }, calls = [] } = {}) {
  return async (url, opts = {}) => {
    const body = opts.body ? JSON.parse(opts.body) : null;
    calls.push({ url: String(url), opts, body });
    assert.ok(opts.signal, 'Every upstream request has a timeout signal');
    if (String(url).startsWith('https://mcp.invalid/')) {
      const rows = [];
      for (const tool of body.params.arguments.tools) {
        const handler = tools[tool.tool_slug];
        const data = typeof handler === 'function' ? await handler(tool.arguments) : handler;
        rows.push(mcp(tool.tool_slug, data ?? null, handler !== undefined));
      }
      return json(envelope(rows));
    }
    return http(String(url), opts, body);
  };
}
async function fixture(t, fetchImpl, config = {}, log = () => {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'gateway-b-'));
  const store = createStore(path.join(dir, 'store'));
  const drop = path.join(dir, 'glasses'), runs = path.join(dir, 'runs'), picks = path.join(dir, 'picks.json');
  await fs.mkdir(drop); await fs.mkdir(runs);
  const secrets = { get: async ref => ({ GLASSES_API_TOKEN: 'phone-test', GLASSES_AGENT_TOKEN: 'joy-test', COMPOSIO_MCP_URL: 'https://mcp.invalid/credential-should-never-leak', HOMEASSISTANT_URL: 'http://ha.invalid' }[ref.split('@')[0]] ?? 'fake-secret') };
  const app = await createApp({ env: {}, secrets, store, log, deps: { glasses: { now: () => NOW, fetch: fetchImpl ?? (() => { throw new Error('No network allowed'); }), config: { reportSourceDir: runs, youtube: { file: picks, listKey: 'picks', listName: 'Picks' }, reportWatchMs: 0, people: { file: path.join(drop, 'people.json') },
    composio: { calendarAccount: 'googlecalendar_test', spotifyAccount: 'spotify_test' }, absUrl: 'http://audiobookshelf:80', publicUrl: 'https://gateway.example.com/gateway', models: { smart: 'model-smart', fast: 'model-fast', classifier: 'model-fast', cleanup: 'model-fast', transcribe: 'model-stt' },
    homeAssistant: { enabled: true, scenes: [{ id: 'people-over', name: 'People over', icon: 'party', entity: 'script.start_hosting' }],
      alarm: { time: 'sensor.wake_time', mode: 'select.alarm_mode', times: { '7:00 AM': 'script.short_week', '8:30 AM': 'script.long_week' }, styles: ['Gentle', 'Harsh'] },
      printers: [{ id: 'printer', name: 'Workshop Printer', progress: 'sensor.printer_progress', state: 'sensor.printer_print_state', online: 'binary_sensor.printer_online', entityPrefix: 'sensor.printer_' }] }, ...config } } } });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${app.server.address().port}/glasses/v1`;
  async function request(route, { method = 'GET', body, token = 'phone-test' } = {}) {
    const res = await fetch(base + route, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: res.status, value: await res.json() };
  }
  t.after(async () => { app.server.closeAllConnections(); await new Promise(resolve => app.server.close(resolve)); await store.flushed(); await fs.rm(dir, { recursive: true, force: true }); });
  return { request, dir, drop, runs, picks, store };
}

test('Composio JSON, nested JSON-string outcomes, and independent failure', async () => {
  const tools = [{ tool_slug: 'A' }, { tool_slug: 'B' }];
  const env = envelope([{ tool_slug: 'A', response: JSON.stringify({ successful: true, data: { items: [1] } }) }, mcp('B', null, false)]);
  assert.deepEqual(outcomes(parseEnvelope(JSON.stringify(env)), tools), [{ slug: 'A', successful: true, data: { items: [1] } }, { slug: 'B', successful: false, data: null, error: 'Tool unavailable' }]);
  assert.deepEqual(outcomes(envelope([mcp('A', 1), mcp('A', 2)]), [{ tool_slug: 'A' }, { tool_slug: 'A' }]).map(r => r.data), [1, 2]);
});
test('Composio SSE skips progress and parses multiline final JSON-RPC data', async () => {
  const env = envelope([mcp('A', { ok: true })]);
  const text = `event: progress\r\ndata: {"method":"progress"}\r\n\r\nevent: message\r\ndata: ${JSON.stringify(env)}\r\n\r\ndata: [DONE]\r\n\r\n`;
  assert.equal(outcomes(parseEnvelope(text), [{ tool_slug: 'A' }])[0].data.ok, true);
  assert.equal(parseEnvelope(`data: {"jsonrpc":"2.0",\ndata: "result":{"content":[]}}\n\n`).jsonrpc, '2.0');
});
test('Composio errors redact credential-bearing URL and upstream body', async () => {
  const ctx = { secret: async () => 'https://secret.invalid/credential', fetch: async () => { throw new Error('https://secret.invalid/credential'); } };
  await assert.rejects(execute(ctx, [{ tool_slug: 'A' }]), error => error.message === 'Composio unavailable' && !error.stack.includes('secret.invalid'));
});
test('diagnostic endpoint permits explicit read slugs, enforces auth, and rejects mutations', async t => {
  const f = await fixture(t, fakeUpstreams({ tools: { SPOTIFY_GET_PLAYBACK_STATE: { item: null } } }));
  assert.equal((await f.request('/_diag/composio?slug=SPOTIFY_GET_PLAYBACK_STATE', { token: null })).status, 401);
  assert.equal((await f.request('/_diag/composio?slug=SPOTIFY_GET_PLAYBACK_STATE', { token: 'joy-test' })).status, 403);
  assert.equal((await f.request('/_diag/composio?slug=SPOTIFY_START_RESUME_PLAYBACK')).status, 400);
  assert.equal((await f.request('/_diag/composio?slug=SPOTIFY_GET_PLAYBACK_STATE&args=%5B%5D')).status, 400);
  assert.equal((await f.request('/_diag/composio?slug=SPOTIFY_GET_PLAYBACK_STATE')).value[0].successful, true);
});

const state = { item: { name: 'Synthetic Song', artists: [{ name: 'Test Artist' }], duration_ms: 210000, album: { name: 'Test Album' } }, context: { uri: 'spotify:playlist:weekly' }, progress_ms: 42000, is_playing: true, device: { id: 'active-tv', name: 'TV', volume_percent: 37 } };
const playlists = [{ uri: 'spotify:playlist:weekly', name: 'Discover Weekly' }, { uri: 'spotify:playlist:other', name: 'Other' }];
test('Spotify now/home maps playlist subtitle, liked collection, recent order and deduplication', async t => {
  const f = await fixture(t, fakeUpstreams({ tools: { SPOTIFY_GET_PLAYBACK_STATE: state, SPOTIFY_GET_CURRENT_USER_S_PLAYLISTS: { items: playlists }, SPOTIFY_GET_RECENTLY_PLAYED_TRACKS: { items: [{ context: { uri: 'spotify:playlist:other' } }, { context: { uri: 'spotify:playlist:other' } }] } } }));
  const now = (await f.request('/spotify/now')).value;
  assert.deepEqual(now, { source: 'spotify', title: 'Synthetic Song', artist: 'Test Artist', subtitle: 'Test Artist · Discover Weekly', positionSec: 42, durationSec: 210, playing: true, device: 'TV', volume: 37, speed: 1 });
  const home = (await f.request('/spotify/home')).value;
  assert.deepEqual(home.discoverWeekly, playlists[0]); assert.equal(home.liked.uri, 'spotify:collection');
  // Recent holds only recently played contexts; Discover Weekly has its own row and is not repeated.
  assert.deepEqual(home.recent.map(p => p.name), ['Other']); assert.deepEqual(home.playlists, []); assert.deepEqual(home.errors, []);
});
test('Spotify playback targets active state device, track/context/liked mapping, controls and transfer', async t => {
  const calls = [], writes = [];
  const tools = { SPOTIFY_GET_PLAYBACK_STATE: state, SPOTIFY_GET_USER_S_SAVED_TRACKS: { items: [{ track: { uri: 'spotify:track:saved' } }] }, SPOTIFY_GET_AVAILABLE_DEVICES: { devices: [{ id: 'first', name: 'Phone', type: 'Smartphone', is_active: false }, { id: 'active-tv', name: 'TV', type: 'TV', is_active: true }] } };
  for (const slug of ['START_RESUME_PLAYBACK', 'PAUSE_PLAYBACK', 'SKIP_TO_NEXT', 'SKIP_TO_PREVIOUS', 'SET_PLAYBACK_VOLUME', 'TRANSFER_PLAYBACK']) tools[`SPOTIFY_${slug}`] = args => { writes.push({ slug, args }); return {}; };
  const f = await fixture(t, fakeUpstreams({ tools, calls }));
  for (const uri of ['spotify:playlist:weekly', 'spotify:album:album', 'spotify:track:track', 'spotify:collection']) assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri } })).status, 200);
  assert.deepEqual(writes.slice(0, 4).map(x => x.args), [{ device_id: 'active-tv', context_uri: 'spotify:playlist:weekly' }, { device_id: 'active-tv', context_uri: 'spotify:album:album' }, { device_id: 'active-tv', uris: ['spotify:track:track'] }, { device_id: 'active-tv', uris: ['spotify:track:saved'] }]);
  for (const action of ['play', 'pause', 'next', 'previous', 'volume']) assert.equal((await f.request('/spotify/control', { method: 'POST', body: { action, value: 67 } })).status, 200);
  assert.equal(writes.at(-1).args.volume_percent, 67);
  assert.equal((await f.request('/spotify/devices')).value[1].active, true);
  assert.equal((await f.request('/spotify/transfer', { method: 'POST', body: { deviceId: 'first' } })).status, 200);
  assert.deepEqual(writes.at(-1).args.device_ids, ['first']);
  assert.equal((await f.request('/spotify/control', { method: 'POST', body: { action: 'volume', value: 101 } })).status, 400);
  assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri: 'https://bad.invalid' } })).status, 400);
});
test('Spotify missing active device returns 409 and no playback returns null', async t => {
  const f = await fixture(t, fakeUpstreams({ tools: { SPOTIFY_GET_PLAYBACK_STATE: {}, SPOTIFY_GET_CURRENT_USER_S_PLAYLISTS: { items: [] } } }));
  assert.equal((await f.request('/spotify/now')).value, null);
  assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri: 'spotify:track:test' } })).status, 409);
});
test('Spotify finds Discover Weekly past page one with parallel pages of 15 and falls back to the user collection context', async t => {
  const offsets = [], played = [];
  const all = Array.from({ length: 50 }, (_, i) => ({ uri: `spotify:playlist:p${i}`, name: `List ${i}` })).concat([playlists[0]]);
  const f = await fixture(t, fakeUpstreams({ tools: {
    SPOTIFY_GET_PLAYBACK_STATE: state,
    SPOTIFY_GET_CURRENT_USER_S_PLAYLISTS: args => { offsets.push([args.offset, args.limit]); return { href: 'https://api.spotify.com/v1/users/tester/playlists?offset=0', items: all.slice(args.offset, args.offset + args.limit), total: all.length }; },
    SPOTIFY_GET_RECENTLY_PLAYED_TRACKS: { items: [] },
    SPOTIFY_GET_USER_S_SAVED_TRACKS: { items: [] },
    SPOTIFY_START_RESUME_PLAYBACK: args => { played.push(args); return {}; },
  } }));
  const home = (await f.request('/spotify/home')).value;
  assert.deepEqual(offsets, [[0, 15], [15, 15], [30, 15], [45, 15]]); assert.equal(home.discoverWeekly.name, 'Discover Weekly');
  assert.deepEqual(home.recent, []); assert.equal(home.playlists.length, 20); assert.equal(home.playlists[0].name, 'List 0');
  assert.ok(!home.playlists.some(p => p.uri === home.discoverWeekly.uri));
  await f.request('/spotify/home'); assert.equal(offsets.length, 4, 'library cached for ten minutes');
  assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri: 'spotify:collection' } })).status, 200);
  // `spotify:collection` is not a valid player context; the user's own collection context is.
  assert.deepEqual(played[0], { device_id: 'active-tv', context_uri: 'spotify:user:tester:collection' });
});
const preview = { successful: true, data_preview: { data: { items: [{ name: 'Playlist', '...': '5 more fields' }, '...49 more items'], total: 90 } } };
test('Spotify never treats a Composio data_preview as data: truncated pages are re-read in pages of five', async t => {
  const reads = [], logs = [];
  const lib = Array.from({ length: 12 }, (_, i) => ({ uri: `spotify:playlist:q${i}`, name: `Mix ${i}` }));
  const f = await fixture(t, fakeUpstreams({ tools: {
    SPOTIFY_GET_PLAYBACK_STATE: { message: 'No active playback', status: '204' },
    SPOTIFY_GET_CURRENT_USER_S_PLAYLISTS: args => { reads.push([args.offset, args.limit]); return args.limit > 5 ? preview : { items: lib.slice(args.offset, args.offset + args.limit), total: lib.length }; },
    SPOTIFY_GET_RECENTLY_PLAYED_TRACKS: args => args.limit > 2 ? { successful: true, data_preview: { items: [{ context: { uri: 'spotify:playlist:q3' } }, '...4 more items'] } }
      : { items: [{ context: { uri: 'spotify:playlist:q3' } }, { context: { uri: 'spotify:playlist:unknown' } }] },
  } }), {}, (...args) => logs.push(args.join(' ')));
  const home = (await f.request('/spotify/home')).value;
  assert.deepEqual(reads, [[0, 15], [0, 5], [5, 5], [10, 5]]);
  assert.deepEqual(home.recent, [{ uri: 'spotify:playlist:q3', name: 'Mix 3' }], 'unknown contexts are dropped, never shown as "Playlist"');
  assert.equal(home.playlists.length, 11); assert.ok(!JSON.stringify(home).includes('"Playlist"'));
  assert.deepEqual(home.errors, []);
  assert.ok(logs.some(l => /glasses spotify playlists .*truncated/.test(l)), 'truncation is logged: ' + JSON.stringify(logs));
});
test('Spotify recent pages by cursor, stops at eight contexts, and configured Discover Weekly appears', async t => {
  const befores = [];
  const lib = Array.from({ length: 12 }, (_, i) => ({ uri: `spotify:playlist:r${i}`, name: `Recent ${i}` }));
  const f = await fixture(t, fakeUpstreams({ tools: {
    SPOTIFY_GET_PLAYBACK_STATE: {},
    SPOTIFY_GET_CURRENT_USER_S_PLAYLISTS: { items: lib, total: lib.length },
    SPOTIFY_GET_RECENTLY_PLAYED_TRACKS: args => {
      befores.push(args.before ?? null); const n = befores.length - 1;
      return { items: [0, 0, 1, 2, 2].map(i => ({ context: { uri: `spotify:playlist:r${n * 3 + i}` } })), cursors: { before: String(1000 - n) } };
    },
  } }), { spotifyDiscoverWeeklyUri: 'spotify:playlist:dw123' });
  const home = (await f.request('/spotify/home')).value;
  assert.deepEqual(befores, [null, '1000', '999']);
  assert.deepEqual(home.recent.map(p => p.name), Array.from({ length: 8 }, (_, i) => `Recent ${i}`));
  assert.deepEqual(home.discoverWeekly, { uri: 'spotify:playlist:dw123', name: 'Discover Weekly' });
  assert.deepEqual(home.playlists.map(p => p.name), ['Recent 8', 'Recent 9', 'Recent 10', 'Recent 11']);
});
test('Spotify home reports and logs failing parts instead of hiding them', async t => {
  const logs = [];
  const f = await fixture(t, fakeUpstreams({ tools: { SPOTIFY_GET_PLAYBACK_STATE: {} } }), {}, (...args) => logs.push(args.join(' ')));
  const home = await f.request('/spotify/home');
  assert.equal(home.status, 200); assert.deepEqual(home.value.errors, ['playlists', 'recent']); assert.deepEqual(home.value.recent, []);
  assert.ok(logs.some(l => l.startsWith('glasses spotify playlists')) && logs.some(l => l.startsWith('glasses spotify recent')));
});
test('Spotify cold start plays on the phone only when it is the only device; otherwise 409 for the picker; explicit device wins', async t => {
  const played = [];
  let devices = [{ id: 'phone1', name: 'Pixel 10', type: 'Smartphone', is_active: false }];
  const f = await fixture(t, fakeUpstreams({ tools: {
    SPOTIFY_GET_PLAYBACK_STATE: { message: 'No active playback', status: '204' },
    SPOTIFY_GET_AVAILABLE_DEVICES: () => ({ devices }),
    SPOTIFY_START_RESUME_PLAYBACK: args => { played.push(args); return {}; },
    SPOTIFY_PAUSE_PLAYBACK: () => ({}),
  } }));
  assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri: 'spotify:playlist:abc' } })).status, 200);
  assert.deepEqual(played.at(-1), { device_id: 'phone1', context_uri: 'spotify:playlist:abc' });
  devices = [{ id: 'desk', name: 'STUDIODESKTOP', type: 'Computer', is_active: false }];
  const lonely = await f.request('/spotify/play', { method: 'POST', body: { uri: 'spotify:playlist:abc' } });
  assert.equal(lonely.status, 409); assert.equal(lonely.value.error, 'No active Spotify device');
  devices.push({ id: 'phone1', name: 'Pixel 10', type: 'Smartphone' });
  assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri: 'spotify:playlist:abc' } })).status, 409);
  assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri: 'spotify:playlist:abc', deviceId: 'desk' } })).status, 200);
  assert.deepEqual(played.at(-1), { device_id: 'desk', context_uri: 'spotify:playlist:abc' });
  assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri: 'spotify:playlist:abc', deviceId: 'bad id!' } })).status, 400);
  assert.equal((await f.request('/spotify/control', { method: 'POST', body: { action: 'pause' } })).status, 409, 'controls still need an active device');
  assert.equal(played.length, 2);
});
test('Spotify cold start can be switched off', async t => {
  const f = await fixture(t, fakeUpstreams({ tools: { SPOTIFY_GET_PLAYBACK_STATE: {}, SPOTIFY_GET_AVAILABLE_DEVICES: { devices: [{ id: 'phone1', name: 'Pixel', type: 'Smartphone' }, { id: null, name: 'Restricted', type: 'Speaker' }] } } }), { spotifyColdStart: 'off' });
  assert.equal((await f.request('/spotify/play', { method: 'POST', body: { uri: 'spotify:playlist:abc' } })).status, 409);
  // The picker never offers a device it cannot address.
  assert.deepEqual((await f.request('/spotify/devices')).value, [{ id: 'phone1', name: 'Pixel', type: 'Smartphone', active: false }]);
});

test('calendar local day grouping crosses UTC midnight and preserves exclusive all-day boundaries', () => {
  const now = Date.parse('2026-11-13T05:30:00Z'); // Thursday 11:30 PM central time
  const events = [mapEvent({ id: 'late', summary: 'Late', start: { dateTime: '2026-11-13T05:45:00Z' }, end: { dateTime: '2026-11-13T06:15:00Z' } }, 'America/Indiana/Knox'), mapEvent({ id: 'all', summary: 'All day', start: { date: '2026-11-13' }, end: { date: '2026-11-14' } }, 'America/Indiana/Knox')];
  const groups = groupEvents(events, now, 3, 'America/Indiana/Knox');
  assert.deepEqual(groups.map(d => [d.day, d.date]), [['TODAY', '2026-11-12'], ['TOMORROW', '2026-11-13'], ['SAT NOV 14', '2026-11-14']]);
  assert.equal(groups[0].events[0].id, 'late'); assert.equal(groups[1].events[0].id, 'all'); assert.equal(groups[2].events.length, 0);
  assert.equal(events[1].start, '2026-11-13T06:00:00.000Z');
  assert.equal(mapEvent({ start: { date: '2026-03-08' }, end: { date: '2026-03-09' } }, 'America/Indiana/Knox').end, '2026-03-09T05:00:00.000Z');
});
test('calendar queries selected calendars primary first, dedupes shared occurrences and tolerates a failed calendar', async t => {
  const order = [];
  const event = { id: 'a', iCalUID: 'shared', summary: 'Lunch', start: { dateTime: '2026-11-12T19:00:00Z' }, end: { dateTime: '2026-11-12T20:00:00Z' } };
  const f = await fixture(t, fakeUpstreams({ tools: { GOOGLECALENDAR_LIST_CALENDARS: { items: [{ id: 'other', selected: true }, { id: 'primary', primary: true }, { id: 'off', selected: false }, { id: 'bad', selected: true }] }, GOOGLECALENDAR_EVENTS_LIST: args => {
    order.push(args.calendarId); assert.equal(args.timeMin, '2026-11-12T18:00:00.000Z');
    if (args.calendarId === 'bad') throw new Error('Calendar unavailable');
    return { items: [{ ...event, summary: args.calendarId }] };
  } } }));
  const result = await f.request('/calendar/agenda?days=3');
  assert.equal(result.status, 200); assert.deepEqual(order, ['primary', 'other', 'bad']);
  assert.equal(result.value[0].events.length, 1); assert.equal(result.value[0].events[0].title, 'primary');
});
test('calendar paginates and keeps events on the rolling window final day', async t => {
  const calls = [];
  const f = await fixture(t, fakeUpstreams({ tools: {
    GOOGLECALENDAR_LIST_CALENDARS: { items: [{ id: 'primary', primary: true }] },
    GOOGLECALENDAR_EVENTS_LIST: args => {
      calls.push(args);
      return args.pageToken ? { items: [{ id: 'last', summary: 'Final morning', start: { dateTime: '2026-11-15T17:00:00Z' }, end: { dateTime: '2026-11-15T17:30:00Z' } }] } : { items: [], nextPageToken: 'page2' };
    },
  } }));
  const groups = (await f.request('/calendar/agenda?days=3')).value;
  assert.equal(calls[1].pageToken, 'page2'); assert.equal(groups.at(-1).date, '2026-11-15'); assert.equal(groups.at(-1).events[0].title, 'Final morning');
});

test('health assembles daily values, contributors, sleep duration, HRV/RHR fallback and weight breakdown', () => {
  const data = assembleHealth({ weight: [{ weight: 1750, entryTimestamp: '2026-10-15T12:00:00Z' }, { weight: 1740, entryTimestamp: '2026-11-12T12:00:00Z', bodyFat: 20 }], readiness: [{ day: '2026-11-12', score: 81, contributors: { recovery_index: 110, sleep_balance: 75 } }], sleep: [{ day: '2026-11-12', score: 85, contributors: { efficiency: 92 } }], periods: [{ day: '2026-11-12', bedtime_end: '2026-11-12T13:00:00Z', total_sleep_duration: 27000, average_hrv: 42, average_heart_rate: 57 }] }, NOW);
  assert.equal(data.length, 5); assert.equal(data[0].value, 174); assert.equal(data[0].breakdown[1].value, '-1 lb');
  assert.equal(data[1].breakdown[0].score, 100); assert.equal(data[2].breakdown[0].value, '7h 30m');
  assert.equal(data[3].value, 42); assert.equal(data[4].value, 57); assert.equal(data[4].measuredAt, '2026-11-12T13:00:00.000Z');
});
test('health one Oura source failing does not break weight/readiness/HRV, caches for five minutes', async t => {
  const calls = [];
  const f = await fixture(t, fakeUpstreams({ calls, http: (url) => {
    if (url.endsWith('/account/login')) return json({ accessToken: 'fake-weight-token' });
    if (url.endsWith('/operation/')) return json([{ weight: 1802, entryTimestamp: '2026-11-12T12:00:00Z' }]);
    if (url.includes('/daily_readiness?')) return json({ data: [{ day: '2026-11-12', score: 83 }] });
    if (url.includes('/daily_sleep?')) return json({ error: 'not authorized' }, 401);
    if (url.includes('/sleep?')) return json({ data: [{ day: '2026-11-12', total_sleep_duration: 28000, average_hrv: 40, lowest_heart_rate: 49, bedtime_end: '2026-11-12T13:00:00Z' }] });
    throw new Error('Unexpected request');
  } }));
  const result = await f.request('/health/overview');
  assert.equal(result.status, 200); assert.deepEqual(result.value.map(m => m.value), [180.2, 83, null, 40, 49]);
  assert.deepEqual(result.value[2].series, []); const count = calls.length;
  await f.request('/health/overview'); assert.equal(calls.length, count);
});

test('HA panel maps the configured printer, effective alarm time, scene and style writes', async t => {
  const writes = [];
  const states = [{ entity_id: 'sensor.wake_time', state: '07:00:00' }, { entity_id: 'select.alarm_mode', state: 'Gentle' }, { entity_id: 'sensor.printer_progress', state: '42' }, { entity_id: 'sensor.printer_print_state', state: 'printing', attributes: { info: { current_layer: 10, total_layer: 100 } } }, { entity_id: 'binary_sensor.printer_online', state: 'on' }];
  const f = await fixture(t, fakeUpstreams({ http: (url, opts, body) => { if (url.endsWith('/states')) return json(states); writes.push({ url, body }); return json([]); } }));
  const panel = (await f.request('/ha/panel')).value;
  assert.equal(panel.printers.length, 1); assert.equal(panel.printers[0].name, 'Workshop Printer'); assert.equal(panel.printers[0].timeLeftSec, null); assert.equal(panel.printers[0].detail, 'Layer 10 of 100'); assert.equal(panel.alarm.time, '7:00 AM');
  assert.equal((await f.request('/ha/scene', { method: 'POST', body: { id: 'people-over' } })).status, 200);
  assert.equal(writes[0].body.entity_id, 'script.start_hosting');
  assert.equal((await f.request('/ha/alarm', { method: 'POST', body: { time: '8:30 AM', style: 'Harsh' } })).status, 200);
  assert.equal(writes[1].body.entity_id, 'script.long_week'); assert.equal(writes[2].body.option, 'Harsh');
  const count = writes.length;
  assert.equal((await f.request('/ha/alarm', { method: 'POST', body: { time: '7:00 AM', style: 'invalid' } })).status, 400); assert.equal(writes.length, count);
});

const item = (id, title, duration, chapters = []) => ({ id, media: { metadata: { title, authorName: 'Author ' + id }, duration, chapters } });
test('ABS recent reads /me mediaProgress, drops finished, hidden, nearly finished and podcast rows, newest first', async t => {
  const seen = [];
  const progress = [
    { libraryItemId: 'iron', progress: 0.990, currentTime: 41523, duration: 41934, isFinished: false, lastUpdate: 900 },
    { libraryItemId: 'done', progress: 1, currentTime: 100, isFinished: true, lastUpdate: 800 },
    { libraryItemId: 'hidden', progress: 0.4, currentTime: 100, hideFromContinueListening: true, lastUpdate: 790 },
    { libraryItemId: 'pod', episodeId: 'ep1', progress: 0.2, currentTime: 100, lastUpdate: 780 },
    { libraryItemId: 'love', progress: 0.243, currentTime: 2430, duration: 10000, lastUpdate: 700 },
    { libraryItemId: 'f451', progress: 0.001, currentTime: 32, duration: 32000, lastUpdate: 500 },
    { libraryItemId: 'carl', progress: 0.312, currentTime: 3120, duration: 10000, lastUpdate: 600 },
    { libraryItemId: 'unstarted', progress: 0, currentTime: 0, lastUpdate: 950 },
    ...Array.from({ length: 4 }, (_, i) => ({ libraryItemId: 'old' + i, progress: 0.5, currentTime: 50, duration: 100, lastUpdate: 100 - i })),
  ];
  const items = { love: item('love', 'All About Love', 10000, [{ title: 'Ch 3', start: 2000, end: 3000 }]), carl: item('carl', 'Dungeon Crawler Carl', 10000, [{ title: 'Ch 9', start: 3000, end: 4000 }]),
    f451: item('f451', 'Fahrenheit 451', 32000, [{ title: 'Opening', start: 0, end: 600 }]) };
  const f = await fixture(t, fakeUpstreams({ http: url => {
    seen.push(url.replace('http://audiobookshelf:80/api', ''));
    if (url.endsWith('/api/me')) return json({ id: 'u', mediaProgress: progress });
    const id = /\/items\/([^/?]+)\?expanded=1$/.exec(url)?.[1];
    if (id) return items[id] ? json(items[id]) : json(item(id, 'Old ' + id, 100));
    throw new Error('Unexpected ' + url);
  } }));
  const books = (await f.request('/abs/recent')).value;
  assert.deepEqual(books.map(b => b.title), ['All About Love', 'Dungeon Crawler Carl', 'Fahrenheit 451', 'Old old0', 'Old old1'], 'default five rows');
  assert.deepEqual(books[0], { id: 'love', title: 'All About Love', author: 'Author love', chapter: 'Ch 3', progress: 0.243, durationSec: 10000, currentTimeSec: 2430, lastPlayedAt: 700 });
  assert.equal(books[2].progress, 0.001); assert.equal(books[2].chapter, 'Opening');
  assert.ok(!seen.includes('/me/items-in-progress'));
  assert.ok(!seen.some(u => /iron|done|hidden|pod|unstarted/.test(u)), 'excluded books are never fetched');
  assert.equal((await f.request('/abs/recent?limit=25')).value.length, 7, 'max eight, seven qualify');
});
test('ABS treats the 0.97 threshold as finished', async () => {
  const { inProgress, ABS_FINISHED_AT } = await import('../apis/glasses/routes/abs.mjs');
  assert.equal(ABS_FINISHED_AT, 0.97);
  assert.deepEqual(inProgress([{ libraryItemId: 'a', progress: 0.97, currentTime: 1 }, { libraryItemId: 'b', progress: 0.969, currentTime: 1 }, { libraryItemId: 'c', currentTime: 98, duration: 100 }]).map(p => p.libraryItemId), ['b']);
  // A stated progress of 0 on a started row falls back to currentTime/duration.
  assert.deepEqual(inProgress([{ libraryItemId: 'z', progress: 0, currentTime: 99, duration: 100 }, { libraryItemId: 'y', progress: 0, currentTime: 30, duration: 100 }]).map(p => p.libraryItemId), ['y']);
  const { mapBook } = await import('../apis/glasses/routes/abs.mjs');
  assert.equal(mapBook({ id: 'y', media: { duration: 100, metadata: {} } }, { progress: 0, currentTime: 30 }).progress, 0.3);
});
test('ABS public multi-track playback selection and plain-text OK session sync/close', async t => {
  const writes = [];
  const f = await fixture(t, fakeUpstreams({ http: (url, opts, body) => {
    writes.push({ url, body });
    if (url.endsWith('/items/book/play')) return json({ id: 'session', currentTime: 150, duration: 300, mediaMetadata: { title: 'Synthetic Book', authorName: 'Author' }, chapters: [{ title: 'Two', start: 100, end: 300 }], audioTracks: [{ contentUrl: '/api/stream/session/0', startOffset: 0, duration: 100 }, { contentUrl: 'http://private-host/api/stream/session/1', startOffset: 100, duration: 200 }] });
    // Audiobookshelf answers session sync and close with a plain-text OK.
    if (/\/session\/session\/(sync|close)$/.test(url)) return new Response('OK', { status: 200, headers: { 'Content-Type': 'text/plain' } });
    return json({});
  } }));
  const play = (await f.request('/abs/play/book', { method: 'POST' })).value;
  assert.equal(play.startSec, 150); assert.equal(play.chapter, 'Two'); assert.equal(play.tracks.length, 2); assert.equal(play.tracks[1].offsetSec, 100);
  assert.equal(new URL(play.streamUrl).origin, 'https://gateway.example.com'); assert.match(new URL(play.streamUrl).pathname, /^\/gateway\/glasses\/v1\/abs\/stream\/[A-Za-z0-9_-]{43}\/1$/); assert.equal(new URL(play.streamUrl).search, ''); assert.ok(!JSON.stringify(play).includes('fake-secret'));
  const synced = await f.request('/abs/progress/book', { method: 'POST', body: { sessionId: 'session', currentTimeSec: 160, timeListenedSec: 10 } });
  assert.equal(synced.status, 200); assert.deepEqual(synced.value, { ok: true });
  assert.equal(writes.at(-1).url, 'http://audiobookshelf:80/api/session/session/sync'); assert.equal(writes.at(-1).body.currentTime, 160); assert.equal(writes.at(-1).body.timeListened, 10);
  assert.equal((await f.request('/abs/session/session/close', { method: 'POST' })).status, 200);
});
test('ABS progress still fails (so the phone retries) when ABS rejects the sync', async t => {
  const f = await fixture(t, fakeUpstreams({ http: () => new Response('Not Found', { status: 404 }) }));
  assert.equal((await f.request('/abs/progress/book', { method: 'POST', body: { sessionId: 'gone', currentTimeSec: 1, timeListenedSec: 1 } })).status, 502);
});

test('YouTube offers the configured list, newest first and capped at 25', async t => {
  const f = await fixture(t);
  const rows = Array.from({ length: 30 }, (_, i) => ({ video_id: `v${i}`, title: `Video ${i}`, channel_name: 'Test', duration_seconds: 100 + i, published_at: new Date(NOW - i * 86400000).toISOString(), flagged: true }));
  await fs.writeFile(f.picks, JSON.stringify([rows.at(-1), ...rows.slice(0, -1), { video_id: 'no', flagged: false }]));
  const lists = (await f.request('/youtube/lists')).value;
  assert.deepEqual(lists, [{ key: 'picks', name: 'Picks' }]);
  const picks = (await f.request('/youtube/list/picks?limit=100')).value;
  assert.equal(picks.length, 25); assert.deepEqual(picks[0], { videoId: 'v0', title: 'Video 0', channel: 'Test', durationSec: 100 });
  for (const key of ['watch-later', 'history']) {
    const result = await f.request('/youtube/list/' + key);
    assert.equal(result.status, 404); assert.match(result.value.error, /official API/);
  }
  assert.equal((await f.request('/youtube/list/nope')).status, 404);
});
test("The YouTube list re-reads the file on every request, so an atomic replace is visible at once", async t => {
  const f = await fixture(t);
  const row = (id, at) => ({ video_id: id, title: id, channel_name: 'C', duration_seconds: 60, published_at: at, flagged: true });
  await fs.writeFile(f.picks, JSON.stringify([row('old', '2026-11-15T00:00:00Z')]));
  assert.deepEqual((await f.request('/youtube/list/picks')).value.map(v => v.videoId), ['old']);
  const temp = f.picks + '.tmp';
  await fs.writeFile(temp, JSON.stringify([row('old', '2026-11-15T00:00:00Z'), row('new', '2026-11-17T00:00:00Z')])); await fs.rename(temp, f.picks);
  assert.deepEqual((await f.request('/youtube/list/picks')).value.map(v => v.videoId), ['new', 'old']);
});
test('without youtube.file there are no lists; defaults come from the config', async () => {
  const { createContext } = await import('../apis/glasses/lib/context.mjs');
  const store = { all: () => [], get: () => undefined, put() {}, replace() {} };
  const ctx = await createContext({ env: {}, log: () => {}, store, secrets: { get: async () => '' } });
  assert.equal(ctx.config.youtube.file, ''); assert.equal(ctx.config.tz, 'UTC'); assert.equal(ctx.config.homeLat, null);
  assert.equal(ctx.config.spotifyColdStart, 'phone');
});

test('report validation clamps Unicode text and card count, rejects malformed schema/date, falls back on unknown icons', () => {
  const input = report(); input.cards[0].heading = '😀'.repeat(20); input.cards[0].body = 'x'.repeat(250); input.cards.push(...input.cards);
  const output = validateReport(input);
  assert.equal(output.cards.length, 10); assert.equal([...output.cards[0].heading].length, 14); assert.equal(output.cards[0].body.length, 220);
  for (const bad of [{ ...report(), cards: [] }, { ...report(), date: '2026-02-30' }, { ...report(), generatedAt: 'invalid' }]) assert.throws(() => validateReport(bad), /Invalid report/);
  const fallback = validateReport({ ...report(), cards: report().cards.map(c => ({ ...c, icon: 'invented', type: 'weather' })) });
  assert.ok(fallback.cards.every(c => c.icon === 'sun'));
  assert.match(REPORT_PROMPT, /Keep \{\{assistant\}\}'s voice/);
});
test('report synthetic markdown conversion uses the smart model, SHA cache and scoped regeneration; written JSON wins', async t => {
  let conversions = 0;
  const f = await fixture(t, fakeUpstreams({ http: (url, opts, body) => {
    assert.ok(url.endsWith('/chat/completions')); assert.equal(body.model, 'model-smart'); assert.equal(body.response_format.type, 'json_object'); assert.match(body.messages[1].content, /Synthetic morning/);
    conversions++; return json({ choices: [{ message: { content: JSON.stringify(report()) } }] });
  } }));
  const run = path.join(f.runs, '2026-11-12'); await fs.mkdir(run); await fs.writeFile(path.join(run, 'report.md'), '# Synthetic morning\nThe toaster has opinions. Breakfast first; dashboards later.');
  assert.equal((await f.request('/report/latest')).status, 200); await f.request('/report/latest'); assert.equal(conversions, 1);
  assert.equal(f.store.all('report-cache')[0].id.length, 64);
  assert.equal((await f.request('/report/regenerate', { method: 'POST', token: 'joy-test' })).status, 403);
  assert.equal((await f.request('/report/regenerate', { method: 'POST' })).status, 200);
  await f.request('/report/latest'); assert.equal(conversions, 2);
  const joy = report(); joy.cards[0].body = 'Hermes wrote this directly.'; await fs.writeFile(path.join(f.runs, 'morning-report.json'), JSON.stringify(joy));
  assert.equal((await f.request('/report/latest')).value.cards[0].body, 'Hermes wrote this directly.'); assert.equal(conversions, 2);
});
test('report regeneration during an in-flight conversion cannot refill cache with old output', async t => {
  let started, release;
  const beginning = new Promise(resolve => { started = resolve; });
  const blocked = new Promise(resolve => { release = resolve; });
  let conversions = 0;
  const f = await fixture(t, fakeUpstreams({ http: async () => {
    const first = ++conversions === 1;
    if (first) { started(); await blocked; }
    const output = report(); output.cards[0].body = first ? 'Old' : 'New';
    return json({ choices: [{ message: { content: JSON.stringify(output) } }] });
  } }));
  const run = path.join(f.runs, '2026-11-12'); await fs.mkdir(run); await fs.writeFile(path.join(run, 'report.md'), '# Synthetic concurrent report');
  const old = f.request('/report/latest'); await beginning;
  await f.request('/report/regenerate', { method: 'POST' });
  const fresh = await f.request('/report/latest'); assert.equal(fresh.value.cards[0].body, 'New');
  release(); await old;
  assert.equal((await f.request('/report/latest')).value.cards[0].body, 'New'); assert.equal(conversions, 2);
});
test('missing report returns 404, malformed Hermes JSON falls back and older JSON is ignored', async t => {
  const f = await fixture(t);
  assert.deepEqual(await f.request('/report/latest'), { status: 404, value: { error: 'No morning report available' } });
  await fs.writeFile(path.join(f.runs, 'morning-report.json'), JSON.stringify(report('2026-11-11')));
  assert.equal((await f.request('/report/latest')).status, 404);
});
test('people absent returns empty arrays; optional file provides recent people and lookup', async t => {
  const f = await fixture(t);
  assert.deepEqual((await f.request('/people/recent')).value, { heard: [], recent: [] });
  const person = { id: 'test', name: 'Test Person', where: 'Workshop', seenAt: '2026-11-12T12:00:00Z', notes: 'Synthetic fixture' };
  await fs.writeFile(path.join(f.drop, 'people.json'), JSON.stringify([person]));
  assert.deepEqual((await f.request('/people/recent')).value, { heard: [], recent: [person] }); assert.deepEqual((await f.request('/people/test')).value, { ...person, muted: false });
  assert.equal((await f.request('/people/missing')).status, 404);
});
test('weather hourly UTC mapping, rain phrase, ten-minute cache', async () => {
  let calls = 0;
  const ctx = { now: () => NOW, config: { homeLat: 40.7, homeLon: -74.0, tz: 'America/Indiana/Knox' }, fetch: async (url, opts) => { calls++; assert.ok(opts.signal); return json({ hourly: { time: ['2026-11-12T18:00', '2026-11-12T19:00', '2026-11-12T20:00', '2026-11-12T21:00', '2026-11-12T22:00'], temperature_2m: [72, 72, 72, 72, 71], weather_code: [0, 0, 0, 0, 61], is_day: [1, 1, 1, 1, 1] } }); } };
  assert.deepEqual(await getWeather(ctx), { temp: 72, unit: 'F', tempF: 72, condition: 'rain after 4 PM', icon: 'sun' }); await getWeather(ctx); assert.equal(calls, 1);
});

test('spotify unwrap descends through Composio wrappers (data, data.data) and rejects data_preview', async () => {
  const { unwrap, rowsOf, TruncatedError } = await import('../apis/glasses/lib/spotify.mjs');
  assert.deepEqual(unwrap({ data: { items: [1] } }), { items: [1] });
  assert.deepEqual(unwrap({ data: { data: { href: 'h', items: [2] } } }), { href: 'h', items: [2] });
  assert.throws(() => unwrap({ successful: true, data_preview: { cursors: {}, items: [3] } }), TruncatedError);
  assert.throws(() => unwrap({ data: { successful: true, data_preview: { items: [3] } } }), TruncatedError);
  assert.throws(() => rowsOf({ items: [{ uri: 'x' }, '...49 more items'] }), TruncatedError);
  assert.throws(() => rowsOf({ items: [{ track: 1, '...': '8 more fields' }] }), TruncatedError);
  assert.deepEqual(rowsOf({ items: [{ uri: 'x' }] }), [{ uri: 'x' }]);
  // Saved tracks hide the track URI one level down.
  assert.throws(() => rowsOf({ items: [{ added_at: 't', track: { name: 'n', '...': '8 more fields' } }] }), TruncatedError);
  assert.deepEqual(rowsOf({ items: [{ track: { uri: 'spotify:track:a', name: '...And Justice' } }] }).length, 1);
  assert.deepEqual(unwrap({ message: 'No active playback', status: '204' }), { message: 'No active playback', status: '204' });
  assert.deepEqual(unwrap({ is_playing: true, data: 1 }), { is_playing: true, data: 1 });
});
