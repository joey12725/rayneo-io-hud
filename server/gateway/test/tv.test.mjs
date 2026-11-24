import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';

const NOW = Date.parse('2026-11-18T06:00:00Z');
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const ACCOUNTS = [{ accountID: 'dc', network: 'Discord' }, { accountID: 'ig', network: 'Instagram' }];
const GENERAL = '!IsCO77D7m2rQZKPQVbi2:beeper.local';
const CHATS = [
  { id: '!aaaaaaaa:beeper.local', title: 'Sam', accountID: 'ig', type: 'single', lastActivity: '2026-11-18T05:00:00Z', preview: { text: 'SECRET MESSAGE' }, participants: { items: [{ id: 'x', fullName: 'Sam Doe' }], total: 2 } },
  { id: GENERAL, title: '#general', accountID: 'dc', type: 'group', lastActivity: '2026-11-17T05:00:00Z', preview: { text: 'SECRET TOO' } },
  { id: '!archived1:beeper.local', title: 'Old', accountID: 'ig', isArchived: true, lastActivity: '2026-11-18T04:00:00Z' },
];

function upstream({ jamState = 'unknown', playing = true } = {}) {
  const log = [];
  const fetch = async (url, opts = {}) => {
    const u = new URL(url), method = opts.method ?? 'GET';
    log.push({ method, path: u.pathname, body: opts.body });
    if (u.pathname === '/v1/accounts') return json(ACCOUNTS);
    if (u.pathname === '/v1/chats') return json({ items: CHATS, hasMore: false });
    if (u.pathname.startsWith('/api/states/')) return json({ state: jamState });
    if (u.pathname.endsWith('/messages') && method === 'POST') return json({ id: 'sent' });
    if (u.host === 'composio') {
      const call = JSON.parse(opts.body);
      const slug = call.params?.arguments?.tools?.[0]?.tool_slug;
      const data = slug === 'SPOTIFY_GET_PLAYBACK_STATE' && playing
        ? { item: { name: 'Song', artists: [{ name: 'Artist' }], external_urls: { spotify: 'https://open.spotify.com/track/abc' } }, is_playing: true }
        : {};
      return json({ jsonrpc: '2.0', id: call.id, result: { content: [{ type: 'text', text: JSON.stringify({ data: { results: [{ tool_slug: slug, response: { successful: true, data } }] } }) }] } });
    }
    return json({}, 404);
  };
  return { fetch, log };
}

async function app(t, fake) {
  const dir = await mkdtemp(path.join(tmpdir(), 'tv-'));
  const store = createStore(dir, { now: () => NOW });
  t.after(async () => { await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const secrets = { get: async name => ({ TV_LAUNCHER_TOKEN: 'tv-token', HOMEASSISTANT_URL: 'http://ha:8123', COMPOSIO_MCP_URL: 'http://composio/mcp' }[name.split('@')[0]] ?? 'fixture') };
  const env = { GATEWAY_CLIENTS: '{"TV_LAUNCHER_TOKEN":["tv"]}', TV_JAM_LINK_ENTITY: 'input_text.tv_launcher_jam_link', COMPOSIO_SPOTIFY_ACCOUNT: 'spotify_test' };
  const { server } = await createApp({ env, log: () => {}, store, secrets, modules: ['tv'], deps: { tv: { fetch: fake.fetch, now: () => NOW } } });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  t.after(() => new Promise(r => server.close(r)));
  const base = `http://127.0.0.1:${server.address().port}/tv/v1`;
  return (p, init = {}) => fetch(base + p, { ...init, headers: { Authorization: 'Bearer tv-token', 'Content-Type': 'application/json', ...init.headers } });
}

test('tv: destinations expose only id/name/network/pinned, pinned first, archived dropped', async t => {
  const call = await app(t, upstream());
  const r = await call('/share/destinations?limit=25&pinned=' + encodeURIComponent(GENERAL));
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.deepEqual(body.destinations.map(d => Object.keys(d).sort()), [['id', 'name', 'network', 'pinned'], ['id', 'name', 'network', 'pinned']]);
  assert.equal(body.destinations[0].id, GENERAL);
  assert.equal(body.destinations[0].pinned, true);
  assert.equal(body.destinations[0].network, 'Discord');
  assert.ok(!JSON.stringify(body).includes('SECRET'));
});

test('tv: wrong token is refused', async t => {
  const call = await app(t, upstream());
  const r = await call('/share/destinations', { headers: { Authorization: 'Bearer nope' } });
  assert.equal(r.status, 403);
});

test('tv: jam uses the Home Assistant Jam link when set', async t => {
  const fake = upstream({ jamState: 'https://spotify.link/AbCdEf' });
  const call = await app(t, fake);
  const r = await call('/share/jam', { method: 'POST', body: JSON.stringify({ destinationId: GENERAL, room: 'Living Room' }) });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true, kind: 'jam' });
  const sent = fake.log.find(x => x.method === 'POST' && x.path.endsWith('/messages'));
  assert.match(JSON.parse(sent.body).text, /Living Room: https:\/\/spotify\.link\/AbCdEf$/);
});

test('tv: jam falls back to the now-playing link, and refuses unknown chats', async t => {
  const fake = upstream();
  const call = await app(t, fake);
  const ok = await call('/share/jam', { method: 'POST', body: JSON.stringify({ destinationId: GENERAL, room: 'Bedroom' }) });
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).kind, 'now_playing');
  const bad = await call('/share/jam', { method: 'POST', body: JSON.stringify({ destinationId: '!unknown12:beeper.local' }) });
  assert.equal(bad.status, 404);
  const archived = await call('/share/jam', { method: 'POST', body: JSON.stringify({ destinationId: '!archived1:beeper.local' }) });
  assert.equal(archived.status, 404);
});

test('tv: jam with nothing playing and no link explains why', async t => {
  const call = await app(t, upstream({ playing: false }));
  const r = await call('/share/jam', { method: 'POST', body: JSON.stringify({ destinationId: GENERAL }) });
  assert.equal(r.status, 409);
  assert.match((await r.json()).error, /Nothing is playing/);
});
