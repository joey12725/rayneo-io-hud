// Gateway-owned T3 notifications (lib/t3-notify.mjs), automatic watches, the needs-you-anywhere setting, the
// eval-only `watched` flag on /notify/route, and the tailnet proxy route (lib/tunnel.mjs) for tailnet-only machines.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { acceptWebSocket } from '../lib/ws.mjs';
import { createEventBus } from '../apis/glasses/lib/context.mjs';
import { createT3Notifier, notifySettings, saveNotifySettings, stageOf, T3_NOTIFY, T3_NOTIFY_META } from '../apis/glasses/lib/t3-notify.mjs';
import { autoWatch, pruneWatches, isWatched, T3_WATCH, AUTO_WATCH_MS } from '../apis/glasses/lib/notify-route.mjs';
import { policy, routeInput } from '../apis/glasses/lib/notify-policy.mjs';
import { http as t3http, rpc, machineProxy } from '../apis/glasses/lib/t3.mjs';
import { parseProxy, proxyFetch, proxyWebSocket, openTunnel } from '../apis/glasses/lib/tunnel.mjs';

const NOW = Date.parse('2026-11-18T06:00:00Z');
const iso = (t) => new Date(t).toISOString();
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

// ---- fake T3 machines -------------------------------------------------------------------------------------------

/** Machines keyed by host; each has a mutable thread list (shell rows) and per-thread projection extras. */
function fakeMachines() {
  const hosts = new Map(), hits = [];
  const add = (host) => { const m = { threads: [], projections: {}, down: false }; hosts.set(host, m); return m; };
  const fetch = async (url) => {
    const u = new URL(url), m = hosts.get(u.host); hits.push(u.host + u.pathname);
    if (!m || m.down) throw new Error('getaddrinfo ENOTFOUND');
    if (u.pathname === '/api/orchestration/shell') return json({ schemaVersion: 2, projects: [{ id: 'p1', title: 'Glasses' }], threads: m.threads.map(t => ({ ...t })), archivedThreads: [] });
    const bounded = /^\/api\/orchestration\/threads\/([^/]+)\/bounded$/.exec(u.pathname);
    if (bounded) { const id = decodeURIComponent(bounded[1]); return json({ snapshotSequence: 1, projection: { thread: { id }, messages: [], ...(m.projections[id] ?? {}) } }); }
    if (u.pathname === '/api/auth/session') return json({ authenticated: true, scopes: ['orchestration:read', 'orchestration:operate'] });
    if (u.pathname === '/.well-known/t3/environment') return json({ platform: { os: 'windows' } });
    if (u.pathname === '/api/auth/websocket-ticket') return json({ ticket: 'ticket' });
    if (u.pathname === '/api/projects') return json({ projects: [{ id: 'p1', title: 'Glasses' }] });
    throw new Error('unexpected ' + u.pathname);
  };
  return { add, fetch, hits };
}
const thread = (id, title, status, extra = {}) => ({ id, title, projectId: 'p1', status, updatedAt: iso(NOW), createdAt: iso(NOW - 86400000), ...extra });
const MAC = { id: 'mac-env', alias: 'mac', label: 'Mac', url: 'https://mac.example', secret: 'T3_MAC' };
const DESK = { id: 'desk-env', alias: 'desktop', label: 'Desk', url: 'https://desk.example', secret: 'T3_DESK' };

async function harness(t, { dir, machinesList = [MAC, DESK], config = {} } = {}) {
  dir ??= await mkdtemp(path.join(tmpdir(), 't3-notify-'));
  let time = NOW;
  const store = createStore(dir, { now: () => time });
  const logs = [];
  const ctx = { now: () => time, log: (...a) => logs.push(a.join(' ')), secret: async () => 'token', store, config: { t3Machines: machinesList, t3RetryDelayMs: 0, models: { fast: 'm' }, ...config } };
  ctx.events = createEventBus(store, { now: () => time });
  const notifier = createT3Notifier(ctx);
  const advance = (ms = 11000) => { time += ms; };
  const published = () => ctx.events.since(0).filter(e => e.type === 'notification');
  return { ctx, store, notifier, advance, published, logs, dir, setTime: (v) => { time = v; }, time: () => time };
}

test('stageOf maps T3 statuses to needs, finished, active and idle', () => {
  assert.deepEqual(['input', 'approval', 'request', 'done', 'settled', 'failed', 'limited', 'working', 'waiting', 'ready'].map(stageOf),
    ['needs', 'needs', 'needs', 'finished', 'finished', 'finished', 'finished', 'active', 'active', 'idle']);
});

test('notifier: first scan is a silent baseline; needs-you pops up, a watched finish streams, unwatched finishes stay quiet', async t => {
  const fake = fakeMachines(), mac = fake.add('mac.example'), desk = fake.add('desk.example');
  mac.threads = [thread('t1', 'Fix the login flow', 'running', { activityRunStatus: 'running' }), thread('t2', 'Old approval', 'idle', { pendingRuntimeRequest: { id: 'r-old', kind: 'command' } })];
  desk.threads = [thread('d1', 'Desk refactor', 'running')];
  const h = await harness(t);
  h.ctx.fetch = fake.fetch;
  t.after(async () => { await h.store.flushed(); await rm(h.dir, { recursive: true, force: true }); });

  assert.deepEqual(await h.notifier.tick(), []); // baseline: the old pending approval is not announced
  assert.equal(h.published().length, 0);
  assert.ok(h.store.get(T3_NOTIFY_META, 'm:mac').baselinedAt);

  // mac:t1 watched; it asks for approval -> popup with the command and a t3 open target.
  h.store.put(T3_WATCH, { id: 'mac:t1', machine: 'mac', threadId: 't1', title: 'Fix the login flow', createdAt: iso(NOW) });
  h.advance();
  mac.threads[0] = thread('t1', 'Fix the login flow', 'running', { updatedAt: iso(h.time()), pendingRuntimeRequest: { id: 'req-1', kind: 'command', turnItemId: 'i1' } });
  mac.projections.t1 = { turnItems: [{ id: 'i1', type: 'command_execution', status: 'running', input: { command: 'npm test' } }] };
  // desktop:d1 (unwatched) finishes -> nothing.
  desk.threads[0] = thread('d1', 'Desk refactor', 'idle', { updatedAt: iso(h.time()), latestRunCompletedAt: iso(h.time()) });
  const sent = await h.notifier.tick();
  assert.equal(sent.length, 1);
  const [n] = h.published();
  assert.equal(n.route, 'popup'); assert.equal(n.reason, 't3.needs-you'); assert.equal(n.icon, 't3'); assert.equal(n.source, 'T3');
  assert.equal(n.title, 'Fix the login flow'); assert.equal(n.body, 'Needs you · approve npm test');
  assert.deepEqual(n.open, { screen: 't3', args: { target: 'mac:t1' }, app: 't3', target: 'mac:t1' });
  assert.equal(n.id, `t3:mac:t1:approval:${iso(h.time())}`);
  assert.ok(h.store.get('notifications', n.id), 'kept in the notifications collection');

  // Same pending request on the next poll: no repeat.
  h.advance(); assert.deepEqual(await h.notifier.tick(), []);

  // Approved, runs, finishes -> one stream row with the final message.
  h.advance(); mac.threads[0] = thread('t1', 'Fix the login flow', 'running', { updatedAt: iso(h.time()) });
  assert.deepEqual(await h.notifier.tick(), []);
  h.advance(); mac.threads[0] = thread('t1', 'Fix the login flow', 'idle', { updatedAt: iso(h.time()), latestRunId: 'run-1', latestRunCompletedAt: iso(h.time()) });
  mac.projections.t1 = { messages: [{ role: 'assistant', text: 'All tests pass. Pushed the fix.', createdAt: iso(h.time()) }] };
  assert.equal((await h.notifier.tick()).length, 1);
  const done = h.published().at(-1);
  assert.equal(done.route, 'stream'); assert.equal(done.reason, 't3.done'); assert.equal(done.body, 'Finished · All tests pass. Pushed the fix.');
  h.advance(); assert.deepEqual(await h.notifier.tick(), []);
  assert.equal(h.published().length, 2);
});

test('notifier: needs-you on an unwatched thread pops up only while "notify for all threads needing me" is on', async t => {
  const fake = fakeMachines(), mac = fake.add('mac.example');
  mac.threads = [thread('q1', 'Pick a design', 'running')];
  const h = await harness(t, { machinesList: [MAC] });
  h.ctx.fetch = fake.fetch;
  t.after(async () => { await h.store.flushed(); await rm(h.dir, { recursive: true, force: true }); });
  assert.equal(notifySettings(h.ctx).needsAll, true); // default on
  await h.notifier.tick();
  h.advance();
  mac.threads[0] = thread('q1', 'Pick a design', 'running', { updatedAt: iso(h.time()), pendingRuntimeRequest: { id: 'u1', kind: 'user_input' } });
  mac.projections.q1 = { turnItems: [{ id: 'x', type: 'user_input_request', status: 'pending', requestId: 'u1', questions: [{ id: 'a', header: 'Design', question: 'Which layout?', options: ['Grid', 'List'] }] }] };
  await h.notifier.tick();
  const n = h.published().at(-1);
  assert.equal(n.route, 'popup'); assert.equal(n.reason, 't3.needs-you.all'); assert.equal(n.body, 'Needs you · Which layout?');

  // Setting off: the next question on that unwatched thread is quiet; a finish on it is quiet either way.
  h.store.put(T3_NOTIFY_META, { id: 'settings', needsAll: false });
  assert.equal(notifySettings(h.ctx).needsAll, false);
  h.advance(); mac.threads[0] = thread('q1', 'Pick a design', 'running', { updatedAt: iso(h.time()) }); await h.notifier.tick();
  h.advance(); mac.threads[0] = thread('q1', 'Pick a design', 'running', { updatedAt: iso(h.time()), pendingRuntimeRequest: { id: 'u2', kind: 'user_input' } }); await h.notifier.tick();
  h.advance(); mac.threads[0] = thread('q1', 'Pick a design', 'idle', { updatedAt: iso(h.time()), latestRunCompletedAt: iso(h.time()) }); await h.notifier.tick();
  assert.equal(h.published().length, 1);
  // T3_NOTIFY_NEEDS_ALL=0 changes the default only.
  assert.equal(notifySettings({ store: { get: () => null }, config: { t3NotifyNeedsAll: false } }).needsAll, false);
});

test('notifier: dedupe survives a restart, and a transition made while the gateway was down is caught once', async t => {
  const fake = fakeMachines(), mac = fake.add('mac.example');
  mac.threads = [thread('t1', 'Long job', 'running')];
  const first = await harness(t, { machinesList: [MAC] });
  first.ctx.fetch = fake.fetch;
  first.store.put(T3_WATCH, { id: 'mac:t1', machine: 'mac', threadId: 't1', title: 'Long job', createdAt: iso(NOW) });
  await first.notifier.tick();
  first.advance();
  mac.threads[0] = thread('t1', 'Long job', 'running', { updatedAt: iso(first.time()), pendingRuntimeRequest: { id: 'r1', kind: 'command' } });
  await first.notifier.tick();
  assert.equal(first.published().length, 1);
  await first.store.flushed();

  // Restart: new store instance over the same directory, new caches and machine state.
  const second = await harness(t, { dir: first.dir, machinesList: [MAC] });
  second.setTime(first.time() + 60000); second.ctx.fetch = fake.fetch;
  t.after(async () => { await second.store.flushed(); await rm(first.dir, { recursive: true, force: true }); });
  assert.deepEqual(await second.notifier.tick(), [], 'the same pending request is not announced again');
  assert.equal(second.ctx.events.since(0).filter(e => e.type === 'notification').length, 1, 'only the event from before the restart');

  // Down again; the thread is answered and finishes meanwhile; the next start reports the finish once.
  await second.store.flushed();
  mac.threads[0] = thread('t1', 'Long job', 'running', { updatedAt: iso(second.time()) });
  second.advance(); await second.notifier.tick(); await second.store.flushed();
  const third = await harness(t, { dir: first.dir, machinesList: [MAC] });
  third.setTime(second.time() + 120000); third.ctx.fetch = fake.fetch;
  mac.threads[0] = thread('t1', 'Long job', 'idle', { updatedAt: iso(third.time()), latestRunId: 'run-9', latestRunCompletedAt: iso(third.time()) });
  assert.equal((await third.notifier.tick()).length, 1);
  third.advance(); assert.deepEqual(await third.notifier.tick(), []);
  await third.store.flushed();
  const kinds = third.ctx.events.since(0).filter(e => e.type === 'notification').map(e => e.stage);
  assert.deepEqual(kinds, ['needs', 'finished']);
});

test('notifier: offline machines are skipped without losing state; a brand-new thread that already needs you is announced', async t => {
  const fake = fakeMachines(), mac = fake.add('mac.example'), desk = fake.add('desk.example');
  mac.threads = []; desk.down = true;
  const h = await harness(t);
  h.ctx.fetch = fake.fetch;
  t.after(async () => { await h.store.flushed(); await rm(h.dir, { recursive: true, force: true }); });
  await h.notifier.tick();
  assert.equal(h.store.get(T3_NOTIFY_META, 'm:desktop'), null, 'no baseline for a machine never reached');
  h.advance();
  mac.threads = [thread('n1', 'New from glasses', 'running', { createdAt: iso(h.time()), updatedAt: iso(h.time()), pendingRuntimeRequest: { id: 'p', kind: 'file_change' } })];
  await h.notifier.tick();
  assert.equal(h.published().length, 1); assert.equal(h.published()[0].reason, 't3.needs-you.all');
  // A thread that finished quickly between polls and was created from the glasses (auto-watched) still reports.
  autoWatch(h.ctx, 'mac', 'n2', 'Quick one');
  h.advance();
  mac.threads.push(thread('n2', 'Quick one', 'idle', { createdAt: iso(h.time()), updatedAt: iso(h.time()), latestRunCompletedAt: iso(h.time()) }));
  await h.notifier.tick();
  assert.equal(h.published().at(-1).reason, 't3.done');
});

test('notifier: a watched run that ends while open on the desktop (straight to "ready") still reports finished, once', async t => {
  const fake = fakeMachines(), mac = fake.add('mac.example');
  mac.threads = [thread('t1', 'Open on desk', 'running'), thread('t2', 'Visited old', 'idle', { lastVisitedAt: iso(NOW) })];
  const h = await harness(t, { machinesList: [MAC] });
  h.ctx.fetch = fake.fetch;
  t.after(async () => { await h.store.flushed(); await rm(h.dir, { recursive: true, force: true }); });
  autoWatch(h.ctx, 'mac', 't1', 'Open on desk'); autoWatch(h.ctx, 'mac', 't2', 'Visited old');
  await h.notifier.tick();
  h.advance();
  mac.threads[0] = thread('t1', 'Open on desk', 'idle', { updatedAt: iso(h.time()), latestRunId: 'r1', latestRunCompletedAt: iso(h.time()), lastVisitedAt: iso(h.time() + 1) });
  assert.equal((await h.notifier.tick()).length, 1);
  assert.equal(h.published().at(-1).reason, 't3.done');
  assert.equal(h.published().at(-1).stage, 'finished');
  h.advance(); assert.deepEqual(await h.notifier.tick(), [], 'ready -> ready is quiet');
  // An idle (ready) thread that is merely touched never counts as finished.
  h.advance(); mac.threads[1] = thread('t2', 'Visited old', 'idle', { updatedAt: iso(h.time() - 5), lastVisitedAt: iso(h.time()) });
  assert.deepEqual(await h.notifier.tick(), []);
});

test('notifier: the setting and machine baselines survive the per-thread collection hitting its cap', async t => {
  const fake = fakeMachines(), mac = fake.add('mac.example');
  mac.threads = [thread('t1', 'One', 'running')];
  const h = await harness(t, { machinesList: [MAC] });
  h.ctx.fetch = fake.fetch;
  t.after(async () => { await h.store.flushed(); await rm(h.dir, { recursive: true, force: true }); });
  await saveNotifySettings(h.ctx, { needsAll: false });
  await h.notifier.tick();
  for (let i = 0; i < 3100; i++) h.store.put(T3_NOTIFY, { id: 't:mac:filler' + i, status: 'ready' });
  assert.equal(notifySettings(h.ctx).needsAll, false, 'setting kept');
  assert.ok(h.store.get(T3_NOTIFY_META, 'm:mac'), 'baseline kept');
  // Not re-baselined: a watched thread's transition still notifies.
  autoWatch(h.ctx, 'mac', 't1', 'One');
  h.advance(); mac.threads[0] = thread('t1', 'One', 'running', { updatedAt: iso(h.time()), pendingRuntimeRequest: { id: 'q', kind: 'command' } });
  assert.equal((await h.notifier.tick()).length, 1);
});

test('automatic watches last 24 h, extend on use, never downgrade an explicit watch, and are pruned', async t => {
  const h = await harness(t, { machinesList: [] });
  t.after(async () => { await h.store.flushed(); await rm(h.dir, { recursive: true, force: true }); });
  const row = autoWatch(h.ctx, 'mac', 't1', 'Fix');
  assert.equal(row.expiresAt, iso(NOW + AUTO_WATCH_MS)); assert.equal(row.auto, true);
  assert.equal(isWatched(h.ctx, 'mac:t1'), true);
  h.advance(3600000); autoWatch(h.ctx, 'mac', 't1');
  assert.equal(h.store.get(T3_WATCH, 'mac:t1').expiresAt, iso(NOW + 3600000 + AUTO_WATCH_MS)); assert.equal(h.store.get(T3_WATCH, 'mac:t1').title, 'Fix');
  h.store.put(T3_WATCH, { id: 'mac:t2', machine: 'mac', threadId: 't2', title: 'Explicit', createdAt: iso(NOW) });
  autoWatch(h.ctx, 'mac', 't2', 'Explicit');
  assert.equal(h.store.get(T3_WATCH, 'mac:t2').expiresAt, undefined);
  h.advance(AUTO_WATCH_MS);
  assert.equal(isWatched(h.ctx, 'mac:t1'), false, 'lapsed');
  assert.equal(pruneWatches(h.ctx), 1);
  assert.equal(isWatched(h.ctx, 'mac:t2'), true);
});

test('policy: T3 Code app notifications are suppressed (gateway owns T3); needsAny and eval `watched` facts', () => {
  const facts = { watched: () => false };
  assert.equal(policy(routeInput({ source: 'com.t3tools.t3code', title: 'X', body: 'Approval required' }), facts).reason, 't3.app');
  assert.equal(policy(routeInput({ kind: 't3', chatId: 'mac:z', category: 'needs-you' }), facts).reason, 't3.unwatched');
  assert.deepEqual(policy(routeInput({ kind: 't3', chatId: 'mac:z', category: 'needs-you' }), { ...facts, needsAny: true }), { route: 'popup', importance: 0.9, reason: 't3.needs-you.all', by: 'rule', open: { app: 't3', target: 'mac:z' } });
  assert.equal(policy(routeInput({ kind: 't3', chatId: 'mac:z', category: 'done' }), { ...facts, needsAny: true }).reason, 't3.unwatched');
  // watched only counts with debug.
  assert.equal(routeInput({ kind: 't3', watched: true }).watched, false);
  assert.equal(routeInput({ kind: 't3', watched: true, debug: true }).watched, true);
  assert.throws(() => routeInput({ kind: 't3', watched: 'yes', debug: true }), /watched/);
  const evalCase = routeInput({ kind: 't3', source: 't3:desktop', app: 'T3 Code', title: 'T', body: 'Shall I deploy it now?', category: 'needs-input', watched: true, debug: true });
  assert.equal(policy(evalCase, facts).reason, 't3.needs-you', 'pending input always pops up');
  assert.equal(policy({ ...evalCase, category: 'cancelled', body: 'Stopped by the user' }, facts), null, 'semantic: goes to the classifier');
  assert.deepEqual(policy({ ...evalCase, category: 'done' }, facts), { route: 'stream', importance: 0.6, reason: 't3.done', by: 'rule', open: { app: 't3', target: 't3:desktop' } });
  assert.equal(policy({ ...evalCase, source: '', category: 'approval' }, facts).reason, 't3.needs-you');
});

// ---- HTTP surface ---------------------------------------------------------------------------------------------

async function app(t, fake, config = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 't3-notify-app-'));
  const store = createStore(dir, { now: () => NOW });
  const sent = [];
  const WebSocket = class {
    constructor() { queueMicrotask(() => this.onopen?.()); }
    send(raw) { const f = JSON.parse(raw); if (f._tag !== 'Request') return; sent.push(f); queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ _tag: 'Exit', requestId: '1', exit: { _tag: 'Success', value: f.tag === 'server.getConfig' ? { providers: [{ instanceId: 'codex', driver: 'codex', status: 'ready', models: ['gpt-x'] }] } : { threadId: f.tag === 'orchestration.launchThread' ? 'launched-t3' : f.payload.threadId } } }) })); }
    close() {}
  };
  const result = await createApp({ env: {}, log: () => {}, store, secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone' }[name] ?? 'fixture') },
    deps: { glasses: { fetch: async (url, opts) => { const u = new URL(url); if (u.host.endsWith('.example')) return fake.fetch(url, opts); throw new Error('no network: ' + u.host); }, WebSocket, now: () => NOW, config: { t3Machines: [MAC], t3RetryDelayMs: 0, ...config } } } });
  result.server.listen(0, '127.0.0.1'); await once(result.server, 'listening');
  t.after(async () => { result.server.closeAllConnections(); await new Promise(resolve => result.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const call = async (route, { method = 'GET', body } = {}) => {
    const r = await fetch(`http://127.0.0.1:${result.server.address().port}/glasses/v1${route}`, { method, headers: { Authorization: 'Bearer phone', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
    return { status: r.status, body: await r.json() };
  };
  return { call, store, sent };
}

test('HTTP: settings round trip; sending, answering and starting a thread from the glasses auto-watch it for 24 h', async t => {
  const fake = fakeMachines(), mac = fake.add('mac.example');
  mac.threads = [thread('t1', 'Fix the login flow', 'idle'), thread('t2', 'Answer me', 'running', { pendingRuntimeRequest: { id: 'r2', kind: 'command' } })];
  const { call } = await app(t, fake);
  assert.deepEqual((await call('/t3/notify/settings')).body, { needsAll: true });
  assert.equal((await call('/t3/notify/settings', { method: 'POST', body: { needsAll: 'no' } })).status, 400);
  assert.deepEqual((await call('/t3/notify/settings', { method: 'POST', body: { needsAll: false } })).body, { needsAll: false });
  assert.deepEqual((await call('/t3/notify/settings')).body, { needsAll: false });

  await call('/t3/threads'); // learns titles
  const msg = await call('/t3/threads/mac/t1/messages', { method: 'POST', body: { text: 'run the tests' } }); assert.equal(msg.status, 200, JSON.stringify(msg.body));
  assert.equal((await call('/t3/threads/mac/t2/respond', { method: 'POST', body: { requestId: 'r2', decision: 'accept' } })).status, 200);
  const created = await call('/t3/threads', { method: 'POST', body: { machine: 'mac', projectId: 'p1', text: 'Add a dark mode toggle please' } });
  assert.equal(created.status, 200);
  const rows = (await call('/t3/watch')).body;
  const byKey = Object.fromEntries(rows.map(r => [r.key, r]));
  assert.equal(byKey['mac:t1'].auto, true); assert.equal(byKey['mac:t1'].title, 'Fix the login flow'); assert.equal(byKey['mac:t1'].expiresAt, iso(NOW + AUTO_WATCH_MS));
  assert.equal(byKey['mac:t2'].auto, true);
  assert.equal(byKey['mac:' + created.body.id].title, 'Add a dark mode toggle please');
  // An explicit watch replaces the automatic one.
  await call('/t3/watch', { method: 'POST', body: { machine: 'mac', id: 't1', title: 'Fix the login flow' } });
  assert.equal((await call('/t3/watch')).body.find(r => r.key === 'mac:t1').auto, undefined);
});

test('HTTP: /notify/route evaluates T3 content for a case marked watched only with the debug flag (evals)', async t => {
  const fake = fakeMachines();
  const { call } = await app(t, fake, { models: { fast: 'm', classifier: 'm' } });
  const body = { kind: 't3', source: 't3:desktop', app: 'T3 Code', title: 'Thread', body: 'Finished the refactor', category: 'done', watched: true };
  assert.equal((await call('/notify/route', { method: 'POST', body })).body.reason, 't3.unwatched');
  assert.equal((await call('/notify/route', { method: 'POST', body: { ...body, debug: true } })).body.reason, 't3.done');
  assert.equal((await call('/notify/route?debug=1', { method: 'POST', body })).body.reason, 't3.done');
});

// ---- tailnet proxy route ----------------------------------------------------------------------------------------

/** Local proxies (HTTP CONNECT, SOCKS5) that record the targets they tunnel to. */
async function proxies(t) {
  const tunnels = [];
  const pipe = (client, host, port, ok) => {
    const up = net.connect(port, host, () => { ok(); up.pipe(client); client.pipe(up); });
    up.on('error', () => client.destroy()); client.on('error', () => up.destroy());
  };
  const connect = http.createServer();
  connect.on('connect', (req, client) => {
    tunnels.push('connect ' + req.url);
    if (req.url.startsWith('refused')) { client.end('HTTP/1.1 403 Forbidden\r\n\r\n'); return; }
    const i = req.url.lastIndexOf(':'); pipe(client, req.url.slice(0, i), Number(req.url.slice(i + 1)), () => client.write('HTTP/1.1 200 Connection established\r\n\r\n'));
  });
  const socks = net.createServer(client => {
    client.once('data', hello => {
      assert.equal(hello[0], 5);
      client.write(Buffer.from([5, 0]));
      client.once('data', req => {
        const len = req[4], host = req.subarray(5, 5 + len).toString(), port = req.readUInt16BE(5 + len);
        tunnels.push('socks ' + host + ':' + port);
        pipe(client, host === 'loopback.test' ? '127.0.0.1' : host, port, () => client.write(Buffer.from([5, 0, 0, 1, 0, 0, 0, 0, 0, 0])));
      });
    });
  });
  connect.listen(0, '127.0.0.1'); socks.listen(0, '127.0.0.1');
  await Promise.all([once(connect, 'listening'), once(socks, 'listening')]);
  t.after(() => { connect.close(); socks.close(); });
  return { tunnels, connectUrl: 'http://127.0.0.1:' + connect.address().port, socksUrl: 'socks5h://127.0.0.1:' + socks.address().port };
}
/** A local "T3" HTTP server with a WebSocket RPC endpoint. */
async function localT3(t) {
  const seen = [];
  const server = http.createServer((req, res) => {
    seen.push(req.method + ' ' + req.url + ' ' + (req.headers.authorization ?? ''));
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/api/auth/websocket-ticket') return res.end(JSON.stringify({ ticket: 'tk' }));
    if (req.url === '/api/orchestration/shell') return res.end(JSON.stringify({ threads: [{ id: 'x', title: 'Via tailnet', status: 'idle', updatedAt: iso(NOW) }] }));
    if (req.url === '/empty') { res.statusCode = 204; return res.end(); }
    res.statusCode = 404; res.end('{}');
  });
  server.on('upgrade', (req, socket, head) => {
    seen.push('WS ' + req.url);
    const ws = acceptWebSocket(req, socket, head, {});
    ws.on('message', data => { const f = JSON.parse(data.toString()); if (f._tag === 'Request') ws.send(JSON.stringify({ _tag: 'Exit', requestId: '1', exit: { _tag: 'Success', value: { method: f.tag, big: 'x'.repeat(1536 * 1024) } } })); });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  return { seen, port: server.address().port };
}

test('tunnel: proxy URLs are parsed strictly', () => {
  assert.equal(parseProxy('http://hermes:1056').protocol, 'http:');
  assert.equal(parseProxy('socks5h://tailscale.internal:1055').port, '1055');
  for (const bad of ['', 'direct', 'ftp://x:1', 'http://x:1/path', 'nonsense', null]) assert.equal(parseProxy(bad), null, String(bad));
});

test('tunnel: fetch and WebSocket through HTTP CONNECT and SOCKS5 proxies; a refused tunnel is an error', async t => {
  const p = await proxies(t), t3 = await localT3(t);
  for (const proxy of [p.connectUrl, p.socksUrl]) {
    const res = await proxyFetch(proxy)(`http://localhost:${t3.port}/api/orchestration/shell`, { headers: { Authorization: 'Bearer abc' }, signal: AbortSignal.timeout(5000) });
    assert.equal(res.status, 200); assert.equal((await res.json()).threads[0].title, 'Via tailnet');
    const empty = await proxyFetch(proxy)(`http://localhost:${t3.port}/empty`, { method: 'POST', body: '{}' });
    assert.equal(empty.status, 204);
  }
  assert.ok(p.tunnels.includes(`connect localhost:${t3.port}`)); assert.ok(p.tunnels.includes(`socks localhost:${t3.port}`));
  const WS = proxyWebSocket(p.connectUrl);
  const reply = await new Promise((resolve, reject) => {
    const ws = new WS(`ws://localhost:${t3.port}/ws?x=1`);
    ws.onopen = () => ws.send(JSON.stringify({ _tag: 'Request', id: '1', tag: 'server.getConfig', payload: {}, headers: [] }));
    ws.onmessage = (e) => { resolve(JSON.parse(e.data)); ws.close(); };
    ws.onerror = () => reject(new Error('ws failed'));
  });
  assert.equal(reply.exit.value.method, 'server.getConfig'); assert.equal(reply.exit.value.big.length, 1536 * 1024);
  await assert.rejects(openTunnel(parseProxy(p.connectUrl), 'refused.test', 443, { timeoutMs: 2000 }), /refused the tunnel \(HTTP 403\)/);
  await assert.rejects(proxyFetch('http://127.0.0.1:1')(`http://localhost:${t3.port}/`), /proxy/);
});

test('t3: machineProxy picks T3_MACHINE_PROXIES, then gatewayProxy, then T3_TAILNET_PROXY for *.ts.net only', () => {
  const ctx = { config: { t3TailnetProxy: 'socks5h://tailscale.internal:1055', t3MachineProxies: 'vps=http://p:1056,mac=direct' } };
  const desk = { alias: 'desktop', url: 'https://desktop.example-tailnet.ts.net' };
  assert.equal(machineProxy(ctx, desk).href, 'socks5h://tailscale.internal:1055');
  assert.equal(machineProxy(ctx, { alias: 'arcade', url: 'https://prod-1.t3coderelay.com' }), null);
  assert.equal(machineProxy(ctx, { alias: 'vps', url: 'https://prod-2.t3coderelay.com' }).href, 'http://p:1056/');
  assert.equal(machineProxy(ctx, { alias: 'mac', url: 'https://laptop.example-tailnet.ts.net' }), null, 'direct override');
  assert.equal(machineProxy({ config: {} }, { alias: 'x', url: 'https://x.ts.net', gatewayProxy: 'http://g:1' }).href, 'http://g:1/');
  assert.equal(machineProxy({}, desk), null, 'no config: direct');
});

test('t3: a proxied machine uses the tunnel for HTTP and the RPC WebSocket (same token mechanism)', async t => {
  const p = await proxies(t), t3 = await localT3(t);
  const m = { id: 'desk-env', alias: 'desktop', label: 'Desk', url: `http://localhost:${t3.port}`, secret: 'T3_DESKTOP_TOKEN' };
  const ctx = { now: () => NOW, log: () => {}, secret: async (name) => name === 'T3_DESKTOP_TOKEN' ? 'desk-token' : null,
    fetch: async () => { throw new Error('direct fetch must not be used'); }, WebSocket: class { constructor() { throw new Error('direct WebSocket must not be used'); } },
    config: { t3MachineProxies: { desktop: p.socksUrl } } };
  const shell = await t3http(ctx, m, '/api/orchestration/shell');
  assert.equal(shell.threads[0].title, 'Via tailnet');
  const value = await rpc(ctx, m, 'server.getConfig');
  assert.equal(value.method, 'server.getConfig');
  assert.ok(t3.seen.includes('GET /api/orchestration/shell Bearer desk-token'));
  assert.ok(t3.seen.some(s => s.startsWith('WS /ws?orchestrationProtocol=2&wsTicket=tk')));
  assert.ok(p.tunnels.filter(s => s.startsWith('socks')).length >= 3);
});
