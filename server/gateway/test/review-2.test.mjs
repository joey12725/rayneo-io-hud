import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { createSecrets, SecretNotFound } from '../lib/secrets.mjs';
import { chats } from '../apis/glasses/lib/beeper.mjs';
import { agenda } from '../apis/glasses/lib/calendar.mjs';
import { request, lru } from '../apis/glasses/lib/gateway.mjs';
import { runTurn } from '../apis/glasses/lib/hermes.mjs';
import { notificationRule } from '../apis/glasses/routes/events.mjs';
import { callUrl } from '../apis/glasses/routes/call.mjs';

const NOW = Date.parse('2026-11-12T18:00:00Z');
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
function temporary(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gateway-review2-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}
async function fixture(t, { fetchImpl, now = () => NOW, env = {}, secrets, store } = {}) {
  const dir = temporary(t), logs = [];
  store ??= createStore(dir, { now, log: (...args) => logs.push(args) });
  const app = await createApp({ env: { ABS_URL: 'http://audiobookshelf:80', ...env }, store, log: (...args) => logs.push(args), secrets: secrets ?? { get: async name => ({ GLASSES_API_TOKEN: 'test-phone', GLASSES_AGENT_TOKEN: 'test-joy' }[name] ?? 'test-provider') },
    deps: { glasses: { now, fetch: fetchImpl ?? (async () => { throw new Error('No upstream'); }) } } });
  app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
  const origin = `http://127.0.0.1:${app.server.address().port}`;
  const call = (route, { method = 'GET', body, token = 'test-phone', headers = {} } = {}) => fetch(origin + '/glasses/v1' + route, {
    method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...headers }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  const play = async () => (await call('/abs/play/book', { method: 'POST' })).json();
  const audio = (url, opts) => fetch(origin + new URL(url).pathname, opts);
  t.after(async () => { app.server.closeAllConnections(); await new Promise(resolve => app.server.close(resolve)); await store.flushed().catch(() => {}); });
  return { ...app, origin, call, play, audio, logs, dir };
}
const session = (id = 'session') => ({ id, currentTime: 5, audioTracks: [{ contentUrl: 'https://attacker.invalid//other.invalid/audio?part=1&token=untrusted', duration: 20 }, { contentUrl: '/audio/two', duration: 10, startOffset: 20 }] });

test('ABS tickets hide provider credentials and proxy pinned range audio and response metadata', async t => {
  const seen = [];
  const f = await fixture(t, { env: { GATEWAY_PUBLIC_URL: 'https://gateway.example/gateway', GATEWAY_BASE_PATH: '/gateway', GATEWAY_ACCESS_LOG: '1' }, fetchImpl: async (url, opts) => {
    if (String(url).endsWith('/play')) return json(session());
    seen.push({ url: new URL(url), opts });
    return new Response('abc', { status: 206, headers: { 'Content-Type': 'audio/mpeg', 'Content-Length': '3', 'Content-Range': 'bytes 1-3/100', 'Accept-Ranges': 'bytes', ETag: '"v1"', 'Last-Modified': 'Thu, 12 Nov 2026 12:00:00 GMT' } });
  } });
  const p = await f.play(), url = new URL(p.streamUrl), ticket = url.pathname.split('/').at(-2);
  assert.equal(url.origin, 'https://gateway.example'); assert.equal(url.search, '');
  assert.match(ticket, /^[A-Za-z0-9_-]{43}$/); assert.equal(Buffer.from(ticket, 'base64url').length, 32);
  assert.equal(new URL(p.tracks[1].streamUrl).pathname.split('/').at(-2), ticket);
  assert.ok(!JSON.stringify(p).includes('test-provider')); assert.ok(!JSON.stringify(p).includes('attacker'));
  const res = await f.audio(p.streamUrl, { headers: { Range: 'bytes=1-3', 'If-Range': '"v1"' } });
  assert.equal(res.status, 206); assert.equal(await res.text(), 'abc');
  for (const [key, expected] of Object.entries({ 'content-type': 'audio/mpeg', 'content-length': '3', 'content-range': 'bytes 1-3/100', 'accept-ranges': 'bytes', etag: '"v1"', 'last-modified': 'Thu, 12 Nov 2026 12:00:00 GMT' })) assert.equal(res.headers.get(key), expected);
  assert.equal(seen[0].url.origin, 'http://audiobookshelf'); assert.equal(seen[0].url.pathname, '/other.invalid/audio');
  assert.equal(seen[0].url.searchParams.get('token'), 'test-provider'); assert.equal(seen[0].url.searchParams.get('part'), '1');
  assert.equal(seen[0].opts.headers.Range, 'bytes=1-3'); assert.equal(seen[0].opts.headers['If-Range'], '"v1"'); assert.equal(seen[0].opts.redirect, 'manual');
  assert.equal((await f.audio(p.streamUrl.replace(/\/0$/, '/2'))).status, 404);
  assert.equal((await f.audio(p.streamUrl.replace(ticket, 'unissued'))).status, 404);
  await delay(5); assert.ok(!JSON.stringify(f.logs).includes(ticket)); assert.ok(!JSON.stringify(f.logs).includes('test-provider'));
});

test('ABS passes 200/416, cancels rejected bodies, refuses redirects and redacts error logs', async t => {
  let status = 200, cancelled = 0;
  const f = await fixture(t, { env: { GATEWAY_ACCESS_LOG: '1' }, fetchImpl: async (url, opts) => {
    if (String(url).endsWith('/play')) return json(session());
    assert.equal(opts.redirect, 'manual');
    return new Response(new ReadableStream({ start(c) { if (status < 300 || status === 416) { c.enqueue(Buffer.from('x')); c.close(); } }, cancel() { cancelled++; } }), {
      status, headers: status === 302 ? { Location: 'https://attacker.invalid' } : { 'Content-Range': 'bytes */10' },
    });
  } });
  const p = await f.play();
  for (const code of [200, 416, 302, 500]) {
    status = code; const res = await f.audio(p.streamUrl); assert.equal(res.status, code < 300 || code === 416 ? code : 502); await res.text();
  }
  assert.equal(cancelled, 2);
  const ticket = new URL(p.streamUrl).pathname.split('/').at(-2);
  assert.ok(!JSON.stringify(f.logs).includes(ticket)); assert.ok(!JSON.stringify(f.logs).includes('test-provider'));
});

test('ABS tickets have sliding 12-hour idle expiry, close revocation and oldest-ticket eviction', async t => {
  let now = NOW, number = 0;
  const f = await fixture(t, { now: () => now, fetchImpl: async url => String(url).endsWith('/play') ? json(session('s' + ++number)) : String(url).endsWith('/close') ? json({}) : new Response('audio') });
  const first = await f.play();
  now += 11 * 3600000; assert.equal((await f.audio(first.streamUrl)).status, 200);
  now += 11 * 3600000; assert.equal((await f.audio(first.streamUrl)).status, 200);
  now += 12 * 3600000; assert.equal((await f.audio(first.streamUrl)).status, 404);
  const closed = await f.play(); assert.equal((await f.call('/abs/session/' + closed.sessionId + '/close', { method: 'POST' })).status, 200);
  assert.equal((await f.audio(closed.streamUrl)).status, 404);
  const oldest = await f.play(); let newest;
  for (let i = 0; i < 20; i++) newest = await f.play();
  assert.equal((await f.audio(oldest.streamUrl)).status, 404); assert.equal((await f.audio(newest.streamUrl)).status, 200);
});

test('ABS delivers audio before EOF and cancels upstream when the client disconnects', async t => {
  let cancelled, signal;
  const cancellation = new Promise(resolve => { cancelled = resolve; });
  const f = await fixture(t, { fetchImpl: async (url, opts) => {
    if (String(url).endsWith('/play')) return json(session());
    signal = opts.signal;
    return new Response(new ReadableStream({ start(c) { c.enqueue(Buffer.from('first chunk')); }, cancel() { cancelled(); } }), { headers: { 'Content-Type': 'audio/mpeg' } });
  } });
  const p = await f.play();
  const res = await f.audio(p.streamUrl);
  const reader = res.body.getReader(); assert.equal(Buffer.from((await reader.read()).value).toString(), 'first chunk');
  await reader.cancel();
  await Promise.race([cancellation, delay(1000).then(() => { throw new Error('Upstream not cancelled'); })]);
  assert.equal(signal.aborted, true);
});

test('ABS backpressure bounds a paused download and closing a session aborts its active stream', async t => {
  let pulls = 0, upstreamSignal, cancelled;
  const cancellation = new Promise(resolve => { cancelled = resolve; });
  const f = await fixture(t, { fetchImpl: async (url, opts) => {
    if (String(url).endsWith('/play')) return json(session());
    if (String(url).endsWith('/close')) return json({});
    upstreamSignal = opts.signal;
    return new Response(new ReadableStream({
      pull(c) { pulls++; c.enqueue(Buffer.alloc(65536)); if (pulls === 4096) c.close(); },
      cancel() { cancelled(); },
    }, { highWaterMark: 0 }), { headers: { 'Content-Type': 'audio/mpeg' } });
  } });
  const p = await f.play();
  const req = http.get(f.origin + new URL(p.streamUrl).pathname);
  req.on('error', () => {}); t.after(() => req.destroy());
  const [res] = await once(req, 'response'); res.on('error', () => {}); res.pause();
  await delay(50);
  assert.ok(pulls < 1024, `Paused client pulled ${pulls * 64} KiB of a 256 MiB stream`);
  assert.equal((await f.call('/abs/session/session/close', { method: 'POST' })).status, 200);
  await Promise.race([cancellation, delay(1000).then(() => { throw new Error('Revoked stream still open'); })]);
  assert.equal(upstreamSignal.aborted, true); req.destroy();
});

test('store quarantines corrupt JSON once and refuses transient read failures without replacing data', async t => {
  const dir = temporary(t), logs = [], target = path.join(dir, 'docs.json');
  fs.writeFileSync(target, '{broken');
  const store = createStore(dir, { log: (...args) => logs.push(args), now: () => NOW });
  assert.deepEqual(store.all('docs'), []); assert.deepEqual(store.all('docs'), []);
  const backup = fs.readdirSync(dir).find(name => name.startsWith('docs.json.corrupt-' + NOW));
  assert.ok(backup); assert.equal(fs.readFileSync(path.join(dir, backup), 'utf8'), '{broken'); assert.equal(logs.length, 1);
  store.put('docs', { id: 'new' }); await store.flushed();
  const original = fs.readFileSync(target, 'utf8');
  const unreadable = createStore(dir, { log: (...args) => logs.push(args), read() { throw Object.assign(new Error('temporary'), { code: 'EACCES' }); } });
  assert.throws(() => unreadable.put('docs', { id: 'lost' }), { status: 507 });
  assert.throws(() => unreadable.replace('docs', []), { status: 507 });
  assert.equal(fs.readFileSync(target, 'utf8'), original); assert.equal(logs.length, 2);
});

test('store batches async writes, fsyncs before rename and preserves errors across collections', async t => {
  const dir = temporary(t), operations = []; let fail = false, active = 0, peak = 0;
  const io = { ...fsp,
    async open(name, flags, mode) {
      if (name === dir) return { async sync() { operations.push('directory sync'); }, async close() {} };
      operations.push('open'); const h = await fsp.open(name, flags, mode);
      return { async writeFile(data) { operations.push('write'); await h.writeFile(data); }, async sync() { operations.push('file sync'); if (fail) throw new Error('disk'); await h.sync(); }, close: () => h.close() };
    },
    async rename(a, b) { active++; peak = Math.max(peak, active); operations.push('rename'); await fsp.rename(a, b); active--; },
  };
  const store = createStore(dir, { io, log() {} });
  for (let i = 0; i < 100; i++) store.put('docs', { id: String(i) });
  assert.deepEqual(operations, []); await store.flushed();
  assert.deepEqual(operations, ['open', 'write', 'file sync', 'directory sync', 'rename', 'directory sync']); assert.equal(peak, 1);
  assert.equal(Object.keys(JSON.parse(fs.readFileSync(path.join(dir, 'docs.json')))).length, 100);
  const before = fs.readFileSync(path.join(dir, 'docs.json'), 'utf8');
  fail = true; store.put('docs', { id: 'failed' }); await assert.rejects(store.flushed(), { status: 507 });
  assert.equal(fs.readFileSync(path.join(dir, 'docs.json'), 'utf8'), before);
  fail = false; store.put('state', { id: 'other' }); await assert.rejects(store.flushed(), { status: 507 });
  store.put('docs', { id: 'retry' }); await store.flushed();
  assert.ok(JSON.parse(fs.readFileSync(path.join(dir, 'docs.json'))).retry);
});

test('concurrent flushed calls drain writes queued during an in-flight fsync', async t => {
  const dir = temporary(t); let release, began;
  const blocked = new Promise(resolve => { release = resolve; });
  const started = new Promise(resolve => { began = resolve; });
  let first = true;
  const io = { ...fsp, async open(name, ...args) {
    const h = await fsp.open(name, ...args);
    if (!String(name).endsWith('.tmp')) return h;
    return { writeFile: data => h.writeFile(data), close: () => h.close(), async sync() {
      if (first) { first = false; began(); await blocked; } await h.sync();
    } };
  } };
  const store = createStore(dir, { io }); store.put('docs', { id: 'first' });
  const one = store.flushed(); await started;
  store.put('state', { id: 'later' }); const two = store.flushed(); release();
  await Promise.all([one, two, store.flushed()]);
  assert.equal(createStore(dir).get('state', 'later').id, 'later');
});

test('prototype names are absent documents and deleting them emits no event; explicit IDs round-trip', async t => {
  const f = await fixture(t);
  for (const id of ['__proto__', 'constructor', 'toString']) {
    assert.equal((await f.call('/docs/' + id)).status, 404);
    assert.equal((await f.call('/docs/' + id, { method: 'DELETE' })).status, 200);
  }
  assert.deepEqual((await (await f.call('/events?wait=0')).json()).events, []);
  f.store.put('docs', { id: '__proto__', title: 'Explicit', text: 'safe' }); await f.store.flushed();
  assert.equal(createStore(f.dir).get('docs', '__proto__').title, 'Explicit');
});

test('bounded collections prune old notifications, cap docs/events and use session/alias LRU', async t => {
  const dir = temporary(t); let now = NOW;
  const store = createStore(dir, { now: () => now });
  const stamp = n => new Date(NOW + n).toISOString();
  for (let i = 0; i < 310; i++) store.put('notifications', { id: String(i), createdAt: stamp(i) });
  assert.equal(store.all('notifications').length, 300); assert.equal(store.get('notifications', '0'), null);
  now += 15 * 86400000; assert.deepEqual(store.all('notifications'), []);
  for (let i = 0; i < 210; i++) store.put('docs', { id: String(i), createdAt: stamp(i) });
  assert.equal(store.all('docs').length, 200); assert.equal(store.get('docs', '0'), null);
  for (let i = 0; i < 510; i++) store.put('events', { id: String(i), seq: i });
  assert.equal(store.all('events').length, 500);
  const aliases = lru(100);
  for (let i = 0; i < 100; i++) { store.put('assistant-sessions', { id: String(i) }); aliases.set(String(i), 'next'); }
  store.get('assistant-sessions', '0'); aliases.get('0'); store.put('assistant-sessions', { id: '100' }); aliases.set('100', 'new');
  assert.equal(store.get('assistant-sessions', '1'), null); assert.ok(store.get('assistant-sessions', '0')); assert.equal(aliases.has('1'), false); assert.equal(aliases.has('0'), true); assert.equal(aliases.size, 100);
  await store.flushed(); assert.equal(createStore(dir, { now: () => now }).all('docs').length, 200);
});

test('reminders reject overflow with a 4xx and recover capacity after expiry', async t => {
  let now = NOW; const f = await fixture(t, { now: () => now });
  for (let i = 0; i < 500; i++) f.store.put('reminders', { id: String(i), expiresAt: new Date(NOW + 1000).toISOString() });
  const body = { text: 'Remember', condition: { type: 'place', value: 'home' } };
  assert.equal((await f.call('/reminders', { method: 'POST', body })).status, 409);
  now += 2000; assert.equal((await f.call('/reminders', { method: 'POST', body })).status, 200);
  assert.equal(f.store.all('reminders').length, 1);
});

test('Hermes rejects oversized or non-object action payloads before retaining events or state', async t => {
  const f = await fixture(t), big = { payload: 'x'.repeat(2048) };
  for (const [route, body] of [['/assistant/notify', { title: 'Hi', body: 'Hello', open: big }], ['/assistant/open', { screen: 'home', args: big }], ['/assistant/media', { action: 'pause', ...big }], ['/assistant/open', { screen: 'home', args: [] }], ['/assistant/notify', { title: 'Hi', body: 'Hello', open: 'anything' }]]) {
    assert.equal((await f.call(route, { method: 'POST', body, token: 'test-joy' })).status, 400);
  }
  assert.deepEqual(f.store.all('events'), []); assert.deepEqual(f.store.all('state'), []); assert.deepEqual(f.store.all('notifications'), []);
});

test('doc/reminder acknowledgements wait for fsync and return 507 without publishing on failure', async t => {
  const dir = temporary(t);
  let release;
  const blocked = new Promise(resolve => { release = resolve; });
  let began; const started = new Promise(resolve => { began = resolve; });
  const io = { ...fsp, async open(name, ...args) {
    const h = await fsp.open(name, ...args);
    if (!String(name).endsWith('.tmp')) return h;
    return { writeFile: data => h.writeFile(data), close: () => h.close(), async sync() { began(); await blocked; throw new Error('disk'); } };
  } };
  const store = createStore(dir, { io, log() {} }), f = await fixture(t, { store });
  let acknowledged = false;
  const response = f.call('/assistant/doc', { method: 'POST', token: 'test-joy', body: { title: 'Document', text: 'Content' } }).then(r => { acknowledged = true; return r; });
  await started; assert.equal(acknowledged, false); release(); assert.equal((await response).status, 507); assert.deepEqual(store.all('events'), []);
  assert.equal((await f.call('/reminders', { method: 'POST', body: { text: 'Hi', condition: { type: 'person', value: 'Friend' } } })).status, 507);
  assert.deepEqual(store.all('events'), []);
});

test('auth infrastructure failures return 503 with Retry-After and single-flight negative caching', async t => {
  let count = 0, release;
  const block = new Promise(resolve => { release = resolve; });
  const f = await fixture(t, { secrets: { async get() { count++; await block; throw new Error('outage'); } } });
  const calls = Array.from({ length: 20 }, () => f.call('/docs'));
  await delay(20); assert.equal(count, 1); release();
  for (const res of await Promise.all(calls)) { assert.equal(res.status, 503); assert.equal(res.headers.get('retry-after'), '30'); }
  assert.equal((await f.call('/docs')).status, 503); assert.equal(count, 1);
  assert.equal((await f.call('/docs', { token: null })).status, 401); assert.equal(count, 1);
});

test('auth distinguishes missing secrets and supports digest comparisons for different token lengths', async t => {
  const f = await fixture(t, { secrets: { async get(name) { if (name === 'GLASSES_API_TOKEN') throw new SecretNotFound(); return 'test-joy'; } } });
  assert.equal((await f.call('/docs')).status, 403);
  assert.equal((await f.call('/assistant/state', { token: 'test-joy' })).status, 200);
  for (const token of ['x', 'test-joy-longer', 'test-joz']) assert.equal((await f.call('/assistant/state', { token })).status, 403);
});

function secretEnv(t, names) {
  const dir = temporary(t), file = path.join(dir, 'infisical.env');
  fs.writeFileSync(file, 'INFISICAL_CLIENT_ID=fixture-id\nINFISICAL_CLIENT_SECRET=fixture-credential\nINFISICAL_PROJECT_ID=fixture-project');
  return { GATEWAY_SECRETS: 'infisical', INFISICAL_ENV_FILE: file, GATEWAY_SECRET_ALLOW: names.join(',') };
}
test('Infisical login/lookup failures are cached, with at most four lookups in flight and the rest queued', async t => {
  const env = secretEnv(t, ['A', 'B', 'C', 'D', 'E']); let now = 0, calls = 0, failLogin = true;
  const secrets = createSecrets({ env, now: () => now, fetchImpl: async url => {
    calls++; if (String(url).endsWith('/login')) return failLogin ? json({}, 503) : json({ accessToken: 'fixture-access' });
    return json({}, 503);
  } });
  await assert.rejects(secrets.get('A')); await assert.rejects(secrets.get('B')); assert.equal(calls, 1);
  now += 30001; failLogin = false; await assert.rejects(secrets.get('A')); assert.equal(calls, 3);
  await assert.rejects(secrets.get('A')); assert.equal(calls, 3);
  let release; const blocked = new Promise(resolve => { release = resolve; }); let active = 0, peak = 0;
  const bounded = createSecrets({ env, fetchImpl: async url => {
    if (String(url).endsWith('/login')) return json({ accessToken: 'fixture-access' });
    active++; peak = Math.max(peak, active); await blocked; active--; return json({}, 404);
  } });
  const pending = ['A', 'B', 'C', 'D'].map(name => bounded.get(name).catch(e => e));
  // A fifth lookup waits for a slot instead of failing "busy" (a cached busy failure used to refuse auth for 30 s).
  await delay(5); const fifth = bounded.get('E').catch(e => e); await delay(5); assert.equal(peak, 4); release();
  assert.ok((await Promise.all([...pending, fifth])).every(e => e.code === 'SECRET_NOT_FOUND')); assert.equal(peak, 4);
  assert.equal(await bounded.get('A', { optional: true }), null);
});

test('infisical fails closed without an allow-list; the env provider reads names; a secrets file permits only own keys', async t => {
  let calls = 0;
  const env = createSecrets({ env: { SOME_TOKEN: 'from-env', FILE_TOKEN_FILE: '/nonexistent/secret' } });
  assert.equal(await env.get('SOME_TOKEN'), 'from-env'); assert.equal(await env.get('MISSING', { optional: true }), null);
  await assert.rejects(env.get('FILE_TOKEN'), /unreadable/);
  const allowed = createSecrets({ env: { A: '1', B: '2', GATEWAY_SECRET_ALLOW: 'A' } });
  assert.equal(await allowed.get('A'), '1'); await assert.rejects(allowed.get('B'), /allow-list/);
  assert.throws(() => createSecrets({ env: { GATEWAY_SECRETS: 'vault' } }), /Unknown secrets provider "vault"/);
  const secrets = createSecrets({ env: { GATEWAY_SECRETS: 'infisical' }, fetchImpl: async () => { calls++; throw new Error('must not fetch'); } });
  await assert.rejects(secrets.get('ANY'), /allow-list/); assert.equal(calls, 0);
  const dir = temporary(t), file = path.join(dir, 'dev.json'); fs.writeFileSync(file, JSON.stringify({ DEV: 'fixture-value' }));
  const dev = createSecrets({ env: { GATEWAY_SECRETS_FILE: file } });
  assert.equal(await dev.get('DEV'), 'fixture-value'); assert.equal(await dev.get('constructor', { optional: true }), null);
});

test('pagination budgets return partial Beeper and calendar results and abort a hung later page', async t => {
  for (const kind of ['beeper', 'calendar']) {
    let pages = 0, lastSignal;
    const ctx = { now: () => NOW, config: { tz: 'America/Indiana/Knox', beeperUrl: 'http://beeper', paginationBudgetMs: 40, composio: { calendarAccount: 'googlecalendar_test' } }, secret: async () => 'fixture', fetch: async (_url, opts) => {
      lastSignal = opts.signal;
      if (kind === 'beeper') {
        if (++pages === 1) return json({ items: [{ id: 'one' }], hasMore: true, oldestCursor: 'more' });
        return new Promise(() => {});
      }
      const tool = JSON.parse(opts.body).params.arguments.tools[0];
      let data;
      if (tool.tool_slug === 'GOOGLECALENDAR_LIST_CALENDARS') data = { items: [{ id: 'primary', primary: true }] };
      else if (++pages === 1) data = { items: [{ id: 'one', start: { dateTime: '2026-11-12T19:00:00Z' }, end: { dateTime: '2026-11-12T20:00:00Z' } }], nextPageToken: 'more' };
      else return new Promise(() => {});
      return json({ result: { results: [{ tool_slug: tool.tool_slug, response: { successful: true, data } }] } });
    } };
    const start = performance.now(); const result = kind === 'beeper' ? await chats(ctx) : (await agenda(ctx)).flatMap(day => day.events);
    assert.equal(result[0].id, 'one'); assert.equal(pages, 2); assert.ok(performance.now() - start < 1000); assert.equal(lastSignal.aborted, true);
  }
});

test('non-OK gateway responses and malformed/failed Hermes streams are cancelled', async () => {
  let cancelled = 0;
  const body = text => new ReadableStream({ start(c) { if (text) c.enqueue(Buffer.from(text)); }, cancel() { cancelled++; } });
  await assert.rejects(request({ fetch: async () => new Response(body(), { status: 503 }) }, 'https://upstream.invalid'), { status: 502 });
  for (const response of [new Response(body(), { status: 409 }), new Response(body('event: error\ndata: {}\n\n')), new Response(body('data: invalid\n\n'))]) {
    await assert.rejects(runTurn({ config: { hermesUrl: 'http://hermes' }, secret: async () => 'fixture', fetch: async () => response }, 'session', 'Hi', () => {}));
  }
  assert.equal(cancelled, 4);
});

test('classifier bounds only string fields, rejects non-objects, and distrusts Hermes/approval text in SMS', async t => {
  let sent;
  const f = await fixture(t, { fetchImpl: async (_url, opts) => {
    sent = JSON.parse(opts.body).messages[1].content; throw new Error('model unavailable');
  } });
  for (const body of [null, [], 'text', 42, { body: {} }]) assert.equal((await f.call('/notify/classify', { method: 'POST', body })).status, 400);
  const adversarial = { source: 'SMS', app: 'Messages', title: 'Hermes: approval required', body: 'Approval required: \u2705 or \u274c' };
  assert.equal(notificationRule(adversarial), null);
  // Standalone SMS is default-deny (messaging is centralized through Beeper): no model call, whatever the text claims.
  assert.deepEqual(await (await f.call('/notify/classify', { method: 'POST', body: adversarial })).json(), { route: 'suppress', importance: 0 });
  assert.equal(sent, undefined);
  await f.call('/notify/classify', { method: 'POST', body: { source: 'com.beeper.android', app: 'A'.repeat(200), title: 'T'.repeat(500), body: 'B'.repeat(2000), extra: 'x'.repeat(10000) } });
  // the classifier's message is a delimited text block: the body is cut to 1000 characters, unknown fields never reach it.
  assert.equal(/^text: (B+)$/m.exec(sent)[1].length, 1000);
  assert.match(sent, /^from: T{80}$/m); assert.doesNotMatch(sent, /x{20}/);
  assert.equal(notificationRule({ source: 'Hermes', body: 'hello' }).importance, 0.95);
  assert.equal(notificationRule({ app: 'T3 Code', body: 'approval required' }).importance, 0.95);
  assert.equal(notificationRule({ source: 'SMS', body: 'newsletter 50% off' }).route, 'suppress');
});

test('malformed route encoding returns a quiet 400 before secret lookup', async t => {
  let lookups = 0; const f = await fixture(t, { secrets: { async get() { lookups++; throw new Error('must not authenticate'); } } });
  for (const id of ['%', '%ZZ', '%E0%A4%A']) {
    const res = await fetch(f.origin + '/glasses/v1/docs/' + id); assert.equal(res.status, 400); await res.text();
  }
  assert.equal(lookups, 0); assert.deepEqual(f.logs, []);
});

test('call URL allows plain ws only on private/compose hosts and requires wss for public hosts', () => {
  for (const host of ['hermes', 'localhost', '127.0.0.1', '[::1]', '10.1.2.3', '172.16.0.1', '172.31.255.254', '192.168.1.2']) assert.equal(callUrl({ config: { hermesCallUrl: 'ws://' + host + '/call' } }).protocol, 'ws:');
  for (const host of ['public.example', '8.8.8.8', '172.32.0.1', '192.169.0.1', '[2001:db8::1]']) {
    assert.throws(() => callUrl({ config: { hermesCallUrl: 'ws://' + host + '/call' } }), /wss/);
    assert.equal(callUrl({ config: { hermesCallUrl: 'wss://' + host + '/call' } }).protocol, 'wss:');
  }
});
