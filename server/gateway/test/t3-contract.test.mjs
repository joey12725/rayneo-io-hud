// T3 contract tests built on the response shapes the real T3 server (0.1.10-nightly) returns:
// GET /api/projects -> ProjectSnapshot {projects:[Project], updatedAt}; GET /api/auth/session ->
// {authenticated, auth, scopes, expiresAt}; GET /.well-known/t3/environment -> {environmentId, label,
// platform:{os,arch}, serverVersion, ...}; WS Exit failures carry tagged errors (EnvironmentScopeRequiredError,
// OrchestrationV2ThreadLaunchError). Shapes were read from the server's own schema and a read-only probe of
// this PC's T3 (127.0.0.1:3773); no personal data.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile, rename } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { http, rpc, machines, machinesPaths, machineState, list, environments, projects, projectList, modelSelection, recentModelSelection, rejection, machineUrls, sendMessage } from '../apis/glasses/lib/t3.mjs';

const NOW = Date.parse('2026-11-17T12:00:00Z');
const stamp = (delta = 0) => new Date(NOW + delta).toISOString();
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

const PROJECT = (id, title, extra = {}) => ({ id, title, workspaceRoot: 'C:/work/' + id, repositoryIdentity: null, faviconPath: null, projectIcon: null, defaultModelSelection: null, defaultThreadEnvMode: 'local', autoPull: false, scripts: [], pinnedAt: null, archivedAt: null, createdAt: stamp(-86400000), updatedAt: stamp(-3600000), deletedAt: null, ...extra });
const PROJECT_SNAPSHOT = { projects: [PROJECT('p1', 'Glasses'), PROJECT('p2', 'Garden', { pinnedAt: stamp(-10) }), PROJECT('p3', 'Old', { archivedAt: stamp(-5000) }), PROJECT('p4', 'Gone', { deletedAt: stamp(-5000) })], updatedAt: stamp() };
const ENVIRONMENT = { environmentId: '00000000-0000-4000-8000-000000000001', label: 'Desk', platform: { os: 'windows', arch: 'x64' }, serverVersion: '0.1.10-nightly', orchestrationProtocolVersion: 2, capabilities: {} };
const SESSION = (scopes) => ({ authenticated: true, auth: { policy: 'desktop-managed-local' }, scopes, sessionMethod: 'bearer-access-token', expiresAt: stamp(86400000) });
const FULL = ['orchestration:read', 'orchestration:operate', 'access:read'], READ_ONLY = ['orchestration:read', 'access:read', 'access:write'];
const SHELL = { schemaVersion: 2, snapshotSequence: 1, projects: [{ id: 'p1', title: 'Glasses' }], archivedThreads: [], threads: [
  { id: 't1', title: 'Fix', projectId: 'p1', status: 'idle', updatedAt: stamp(-1000), modelSelection: { instanceId: 'codex', model: 'gpt-x', options: [] } },
  { id: 't0', title: 'Older', projectId: 'p1', status: 'idle', updatedAt: stamp(-9000), modelSelection: { instanceId: 'claudeAgent', model: 'claude-x' } },
] };

// Fake T3 machine: answers HTTP and records which paths were hit.
function fakeT3({ scopes = FULL, down = () => false } = {}) {
  const hits = [];
  const fetch = async (url, opts) => {
    const u = new URL(url); hits.push(opts.method + ' ' + u.host + u.pathname);
    if (down(u)) throw new Error('getaddrinfo ENOTFOUND');
    if (u.pathname === '/api/projects') return json(PROJECT_SNAPSHOT);
    if (u.pathname === '/api/auth/session') return json(SESSION(scopes));
    if (u.pathname === '/.well-known/t3/environment') return json(ENVIRONMENT);
    if (u.pathname === '/api/orchestration/shell') return json(SHELL);
    if (u.pathname.endsWith('/bounded')) return json({ snapshotSequence: 1, projection: { thread: { id: 't1', title: 'Fix', status: 'idle', latestRunId: 'run' }, messages: [] } });
    if (u.pathname === '/api/auth/websocket-ticket') return json({ ticket: 'ticket' });
    throw new Error('unexpected ' + u.pathname);
  };
  return { fetch, hits };
}
// WebSocket fake: `reply(frame)` returns the Exit value, or {fail: exit} for a failure.
function socket(reply, { sent = [], dropBeforeOpen = () => false } = {}) {
  return class {
    constructor(url) { this.url = new URL(url); queueMicrotask(() => dropBeforeOpen() ? this.onerror?.() : this.onopen?.()); }
    send(raw) {
      const frame = JSON.parse(raw); if (frame._tag !== 'Request') return; sent.push(frame);
      const value = reply(frame);
      queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ _tag: 'Exit', requestId: '1', exit: value?.fail ? { _tag: 'Failure', cause: value.fail } : { _tag: 'Success', value } }) }));
    }
    close() {}
  };
}
const desk = { id: 'desk-env', alias: 'desktop', label: 'StudioDesktop', url: 'https://desk.example', secret: 'T3_DESK' };
const mac = { id: 'mac-env', alias: 'mac', label: "Laptop", url: 'https://mac.example', secret: 'T3_MAC' };
const ctxFor = (over = {}) => { const logs = []; return { logs, now: () => NOW, log: (...a) => logs.push(a.join(' ')), secret: async () => 'token', config: { t3Machines: [mac], t3RetryDelayMs: 0 }, ...over }; };

test('projectList reads T3 ProjectSnapshot and older shapes; projects() drops archived and deleted', async () => {
  assert.deepEqual(projectList(PROJECT_SNAPSHOT).map(p => p.id), ['p1', 'p2', 'p3', 'p4']);
  assert.deepEqual(projectList([{ id: 'a' }]).map(p => p.id), ['a']);
  assert.deepEqual(projectList({ items: [{ id: 'b' }] }).map(p => p.id), ['b']);
  assert.deepEqual(projectList(null), []);
  const ctx = ctxFor({ fetch: fakeT3().fetch });
  assert.deepEqual((await projects(ctx, mac)).map(p => p.id), ['p1', 'p2']);
});

test('GET /t3/projects lists every writable machine, pinned first then by recent activity; read-only machines are skipped', async t => {
  const fake = fakeT3();
  const call = await app(t, async (url, opts) => {
    if (new URL(url).host === 'desk.example' && new URL(url).pathname === '/api/auth/session') return json(SESSION(READ_ONLY));
    return fake.fetch(url, opts);
  }, { t3Machines: [mac, desk] });
  await call('/t3/threads'); // learns scopes
  const rows = await (await call('/t3/projects')).json();
  assert.deepEqual(rows.map(r => [r.machine, r.id]), [['mac', 'p2'], ['mac', 'p1']]);
  assert.deepEqual(rows[0], { machine: 'mac', machineLabel: "Laptop", id: 'p2', title: 'Garden', pinned: true, updatedAt: stamp(-3600000) });
});

test('POST /t3/threads walks the real create path: snapshot project, getConfig, idempotent launch, returned id', async t => {
  const sent = [];
  const WS = socket(frame => frame.tag === 'server.getConfig' ? { settings: { defaultModelSelection: null, fallbackModelSelection: null, defaultRuntimeMode: 'full-access' }, providers: [{ driver: 'claudeAgent', instanceId: 'claudeAgent', status: 'ready', enabled: true, models: [{ slug: 'claude-opus', isDefault: true }] }] }
    : frame.tag === 'orchestration.launchThread' ? { threadId: 'launched-1', projection: {}, resumed: false } : { sequence: 1 }, { sent });
  const call = await app(t, fakeT3().fetch, { t3Machines: [mac] }, WS);
  const res = await call('/t3/threads', { method: 'POST', body: { machine: 'mac', projectId: 'p1', text: 'Say hi please' } });
  assert.equal(res.status, 200);
  const launch = sent.find(f => f.tag === 'orchestration.launchThread').payload;
  assert.deepEqual(await res.json(), { machine: 'mac', id: 'launched-1' });
  assert.equal(launch.threadId, undefined); assert.equal(launch.reuseExistingThread, undefined); assert.equal(launch.projectId, 'p1'); assert.equal(launch.runtimeMode, 'full-access'); assert.equal(launch.interactionMode, 'default');
  // Newest thread in the project decides the model when nothing is configured.
  assert.deepEqual(launch.modelSelection, { instanceId: 'codex', model: 'gpt-x', options: [] });
  for (const key of ['commandId', 'projectId', 'title', 'modelSelection', 'runtimeMode', 'interactionMode', 'workspaceStrategy']) assert.ok(launch[key], key);
  assert.equal((await call('/t3/threads', { method: 'POST', body: { machine: 'mac', projectId: 'p3', text: 'archived' } })).status, 404);
});

test('modelSelection chain: project, configured default, newest thread, fallback, then ready provider default', () => {
  const providers = [{ driver: 'claudeAgent', instanceId: 'claudeAgent', status: 'ready', models: [{ slug: 'a' }, { slug: 'b', isDefault: true }] }];
  assert.equal(modelSelection({ defaultModelSelection: { model: 'proj' } }, { providers }, { model: 'recent' }).model, 'proj');
  assert.equal(modelSelection({}, { settings: { defaultModelSelection: { model: 'set' } }, providers }, { model: 'recent' }).model, 'set');
  assert.equal(modelSelection({}, { settings: { fallbackModelSelection: { model: 'fb' } }, providers }, { model: 'recent' }).model, 'recent');
  assert.equal(modelSelection({}, { settings: { fallbackModelSelection: { model: 'fb' } }, providers }).model, 'fb');
  assert.deepEqual(modelSelection({}, { providers }), { provider: 'claudeAgent', instanceId: 'claudeAgent', model: 'b' });
  assert.equal(recentModelSelection(SHELL, 'p1').model, 'gpt-x');
  assert.equal(recentModelSelection(SHELL, 'nope'), undefined);
});

test('a failed background poll does not block a user send; the send probes the machine at once', async () => {
  let down = true; const sent = [];
  const fake = fakeT3({ down: () => down });
  const ctx = ctxFor({ fetch: fake.fetch, WebSocket: socket(() => ({ sequence: 1 }), { sent }) });
  await list(ctx); await list({ ...ctx }); // two failures -> backoff
  const fresh = ctxFor({ fetch: fake.fetch, WebSocket: socket(() => ({ sequence: 1 }), { sent }) });
  for (let i = 0; i < 2; i++) await assert.rejects(http(fresh, mac, '/api/orchestration/shell'));
  assert.ok(machineState(fresh, mac).retryAt > NOW);
  down = false;
  await sendMessage(fresh, mac, 't1', 'hello');
  assert.equal(sent.at(-1).payload.type, 'message.dispatch');
  assert.equal(machineState(fresh, mac).online, true); assert.equal(machineState(fresh, mac).retryAt, 0);
  assert.ok(fresh.logs.some(l => l.startsWith('t3 machine mac offline')) && fresh.logs.some(l => l.startsWith('t3 machine mac online recovered')));
});

test('a send retries once when nothing reached T3, keeping the same command and message ids; never after the frame was sent', async () => {
  let drops = 1; const sent = [];
  const ctx = ctxFor({ fetch: fakeT3().fetch, WebSocket: socket(() => ({ sequence: 1 }), { sent, dropBeforeOpen: () => drops-- > 0 }) });
  await sendMessage(ctx, mac, 't1', 'retry me');
  assert.equal(sent.length, 1); assert.ok(ctx.logs.some(l => l.startsWith('t3 retry mac message.dispatch')));
  let frames = 0;
  class Silent { constructor() { queueMicrotask(() => this.onopen?.()); } send() { frames++; queueMicrotask(() => this.onclose?.()); } close() {} }
  await assert.rejects(sendMessage(ctxFor({ fetch: fakeT3().fetch, WebSocket: Silent }), mac, 't1', 'once'), /Laptop: T3 disconnected; inspect state before retrying/);
  assert.equal(frames, 1);
});

test('one unreachable result is a blip; transitions are logged exactly once each with the cause', async () => {
  let down = false; let time = NOW;
  const ctx = ctxFor({ now: () => time, fetch: fakeT3({ down: () => down }).fetch });
  await http(ctx, mac, '/api/projects');
  down = true;
  await assert.rejects(http(ctx, mac, '/api/projects'), /Laptop unreachable \(network error\)/);
  assert.equal(machineState(ctx, mac).online, true); assert.equal(machineState(ctx, mac).retryAt, 0);
  await assert.rejects(http(ctx, mac, '/api/projects'));
  assert.equal(machineState(ctx, mac).online, false); assert.equal(machineState(ctx, mac).retryAt, NOW + 15000);
  time += 20000; await assert.rejects(http(ctx, mac, '/api/projects'));
  down = false; time += 40000; await http(ctx, mac, '/api/projects'); await http(ctx, mac, '/api/projects');
  assert.deepEqual(ctx.logs.filter(l => l.startsWith('t3 machine')), [
    't3 machine mac online first contact',
    't3 machine mac unreachable once /api/projects network error',
    't3 machine mac offline /api/projects network error failures=2',
    't3 machine mac online recovered after network error',
  ]);
});

test('a relay 502/503/504 counts as unreachable; other upstream errors name the machine and leave it online', async () => {
  let status = 200;
  const base = fakeT3().fetch;
  const ctx = ctxFor({ fetch: async (url, opts) => status === 200 ? base(url, opts) : json({ error: 'relay' }, status) });
  await http(ctx, mac, '/api/projects');
  status = 502; await assert.rejects(http(ctx, mac, '/api/projects'), /Laptop unreachable \(HTTP 502\)/);
  status = 504; await assert.rejects(http(ctx, mac, '/api/projects'), e => e.status === 503 && e.detail.retryable);
  assert.equal(machineState(ctx, mac).online, false); assert.equal(machineState(ctx, mac).cause, 'HTTP 504');
  status = 200; await http(ctx, mac, '/api/projects', 'GET', undefined, { force: true });
  status = 500; await assert.rejects(http(ctx, mac, '/api/projects'), e => e.status === 502 && e.message === "Laptop: T3 error (HTTP 500)" && !e.detail.retryable);
  status = 404; await assert.rejects(http(ctx, mac, '/api/projects'), e => e.status === 404 && e.message === "Laptop: not found (HTTP 404)");
  assert.equal(machineState(ctx, mac).online, true); assert.equal(machineState(ctx, mac).failures, 0);
});

test('environments and the thread list share one state: counts, os, write access and offline rows agree', async t => {
  const fake = fakeT3({ down: u => u.host === 'desk.example' });
  const call = await app(t, fake.fetch, { t3Machines: [mac, desk] });
  const threads = await (await call('/t3/threads')).json(), envs = await (await call('/t3/environments')).json();
  assert.deepEqual(envs, threads.machines);
  assert.deepEqual(envs.map(e => [e.machine, e.online, e.os]), [['mac', true, 'windows'], ['desktop', false, null]]);
  assert.equal(envs[1].lastSeenLabel, 'Never seen'); assert.equal(envs[1].cause, 'network error'); assert.equal(envs[0].canWrite, true);
  assert.equal(envs.filter(e => !e.online).length, 1);
});

test('a machine with no token is listed as needs pairing, never hidden, and not treated as a network failure', async () => {
  const ctx = ctxFor({ fetch: fakeT3().fetch, secret: async (ref, opts) => { if (ref.startsWith('T3_DESK')) { if (opts?.optional) return null; const e = new Error('Secret not found'); e.code = 'SECRET_NOT_FOUND'; throw e; } return 'token'; },
    config: { t3Machines: [mac, desk, { id: 'new-env', alias: 'laptop', label: 'Laptop', url: 'https://laptop.example' }] } });
  const rows = await environments(ctx);
  assert.deepEqual(rows.map(r => [r.machine, r.online, r.paired, r.needsPairing ?? false]), [['mac', true, true, false], ['desktop', false, false, true], ['laptop', false, false, true]]);
  assert.equal(machineState(ctx, desk).failures, 0);
  await assert.rejects(http(ctx, desk, '/api/projects', 'GET', undefined, { force: true }), /StudioDesktop needs pairing \(no token\)/);
  const notAllowed = ctxFor({ fetch: fakeT3().fetch, secret: async () => { throw new Error('Secret T3_X is not in the gateway allow-list'); } });
  await assert.rejects(http(notAllowed, mac, '/api/projects'), /needs pairing/);
});

test('a read-only token is reported before dispatch and a scope rejection from T3 names the machine and scope', async t => {
  const fake = fakeT3({ scopes: READ_ONLY });
  const call = await app(t, fake.fetch, { t3Machines: [desk] });
  assert.equal((await (await call('/t3/threads')).json()).machines[0].canWrite, false);
  const res = await call('/t3/threads/desktop/t1/messages', { method: 'POST', body: { text: 'hi' } });
  assert.equal(res.status, 403); assert.deepEqual(await res.json(), { error: 'StudioDesktop is read-only; re-pair the gateway with write access' });
  const scoped = socket(() => ({ fail: { _tag: 'Fail', error: { _tag: 'EnvironmentScopeRequiredError', code: 'insufficient_scope', requiredScope: 'orchestration:operate', traceId: 'trace' } } }));
  const ctx = ctxFor({ fetch: fakeT3().fetch, WebSocket: scoped });
  await assert.rejects(rpc(ctx, desk, 'orchestration.dispatchCommand', {}), e => e.status === 403 && /StudioDesktop is read-only \(needs orchestration:operate scope\)/.test(e.message));
  assert.equal(machineState(ctx, desk).canWrite, false);
  const launch = socket(() => ({ fail: [{ _tag: 'Fail', error: { _tag: 'OrchestrationV2ThreadLaunchError', commandId: 'c', projectId: 'p', message: 'Workspace root does not exist' } }] }));
  const logs = ctxFor({ fetch: fakeT3().fetch, WebSocket: launch });
  await assert.rejects(rpc(logs, mac, 'orchestration.launchThread', {}), /T3 command rejected: Workspace root does not exist/);
  assert.ok(logs.logs.some(l => l.startsWith('t3 rpc mac orchestration.launchThread rejected OrchestrationV2ThreadLaunchError')));
  assert.equal(rejection({ _tag: 'Failure', cause: 'opaque' }), null);
});

test('machines.json is re-read when Hermes replaces it (new inode), keeps the last good list on a torn write, and honours URL overrides', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 't3-machines-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'machines.json');
  const write = async (value) => { const tmp = file + '.tmp'; await writeFile(tmp, value); await rename(tmp, file); };
  await write(JSON.stringify({ machines: [mac] }));
  const ctx = ctxFor({ config: { t3MachinesFile: file, t3MachineUrls: 'desktop=https://relay.example' } });
  assert.deepEqual((await machines(ctx)).map(m => m.alias), ['mac']);
  await write(JSON.stringify({ machines: [mac, desk, { label: 'no id' }] }));
  const now = await machines(ctx);
  assert.deepEqual(now.map(m => m.alias), ['mac', 'desktop']); assert.equal(now[1].url, 'https://relay.example');
  assert.ok(ctx.logs.some(l => l.startsWith('t3 machines reloaded')));
  await write('{"machines": [');
  assert.deepEqual((await machines(ctx)).map(m => m.alias), ['mac', 'desktop']);
  assert.deepEqual(machineUrls('{"mac":"https://a.example"}'), { mac: 'https://a.example' });
  assert.deepEqual(machineUrls('mac=https://a.example, desktop=https://b.example'), { mac: 'https://a.example', desktop: 'https://b.example' });
  assert.equal((await machines(ctxFor({ config: { t3Machines: [{ ...desk, gatewayUrl: 'https://gw.example' }] } })))[0].url, 'https://gw.example');
});

async function app(t, fake, config = {}, WebSocket) {
  const dir = await mkdtemp(path.join(tmpdir(), 't3-contract-'));
  const store = createStore(dir, { now: () => NOW });
  const result = await createApp({ env: {}, log: () => {}, store, secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone' }[name] ?? 'upstream-secret') }, deps: { glasses: { fetch: fake, WebSocket: WebSocket ?? socket(() => ({ sequence: 1 })), now: () => NOW, config: { t3Machines: [], t3RetryDelayMs: 0, ...config } } } });
  result.server.listen(0, '127.0.0.1'); await once(result.server, 'listening');
  const base = 'http://127.0.0.1:' + result.server.address().port + '/glasses/v1';
  t.after(async () => { result.server.closeAllConnections(); await new Promise(resolve => result.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  return (route, { method = 'GET', body } = {}) => fetch(base + route, { method, headers: { Authorization: 'Bearer phone', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
}

test('machines.json comes from t3.machinesFile only (lib/config.mjs defaults it under the data directory)', () => {
  assert.deepEqual(machinesPaths({}), []);
  assert.deepEqual(machinesPaths({ t3MachinesFile: '/x/m.json' }), ['/x/m.json']);
});

test('a T3 error reaches the phone as {error} naming the machine and why, with its status', async t => {
  const call = await app(t, async () => { throw new Error('getaddrinfo ENOTFOUND'); }, { t3Machines: [desk] });
  const res = await call('/t3/threads/desktop/t1/messages', { method: 'POST', body: { text: 'hi' } });
  assert.equal(res.status, 503);
  assert.match((await res.json()).error, /^StudioDesktop unreachable \(network error\)/);
});
