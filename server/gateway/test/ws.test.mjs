import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import { EventEmitter, once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { encodeFrame, FrameParser, WebSocketConnection, acceptWebSocket, connectWebSocket, rejectUpgrade } from '../lib/ws.mjs';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';

test('frame codec round-trips both masks and 7/16/64-bit lengths across arbitrary chunks', () => {
  for (const mask of [true, false]) for (const n of [0, 1, 125, 126, 65535, 65536, 100000]) {
    const payload = Buffer.alloc(n, 0xa5), wire = encodeFrame(payload, { mask });
    assert.equal(wire[1] & 127, n < 126 ? n : n <= 65535 ? 126 : 127);
    const parser = new FrameParser({ masked: mask }), messages = [];
    parser.on('message', (data, binary) => messages.push([data, binary]));
    for (let i = 0; i < wire.length; i += 113) parser.push(wire.subarray(i, i + 113));
    assert.deepEqual(messages, [[payload, true]]);
  }
});

test('fragmented UTF-8/binary messages retain order with interleaved control frames', () => {
  const parser = new FrameParser(), events = [];
  for (const kind of ['message', 'ping', 'pong', 'close']) parser.on(kind, (...args) => events.push([kind, ...args]));
  const text = Buffer.from('hi 😀');
  const wire = Buffer.concat([
    encodeFrame(text.subarray(0, 5), { opcode: 1, fin: false, mask: true }),
    encodeFrame('ping', { opcode: 9, mask: true }),
    encodeFrame('pong', { opcode: 10, mask: true }),
    encodeFrame(text.subarray(5), { opcode: 0, mask: true }),
    encodeFrame('a', { opcode: 2, fin: false, mask: true }),
    encodeFrame('b', { opcode: 0, fin: false, mask: true }),
    encodeFrame('c', { opcode: 0, mask: true }),
    encodeFrame(Buffer.from([3, 232]), { opcode: 8, mask: true }),
  ]);
  for (const byte of wire) parser.push(Buffer.from([byte]));
  assert.deepEqual(events, [['ping', Buffer.from('ping')], ['pong', Buffer.from('pong')], ['message', text, false], ['message', Buffer.from('abc'), true], ['close', 1000, '']]);
});

test('frame parser rejects oversized messages before allocation, including accumulated fragments', () => {
  const parser = new FrameParser({ maxMessageSize: 100 });
  assert.throws(() => parser.push(encodeFrame(Buffer.alloc(101), { mask: true }).subarray(0, 6)), { code: 1009 });
  const fragments = new FrameParser({ maxMessageSize: 100 });
  fragments.push(encodeFrame(Buffer.alloc(60), { mask: true, fin: false }));
  assert.throws(() => fragments.push(encodeFrame(Buffer.alloc(41), { mask: true, opcode: 0 })), { code: 1009 });
  const huge = Buffer.from([0x82, 0xff, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0, 0, 0, 0]);
  assert.throws(() => new FrameParser().push(huge), { code: 1009 });
});

test('bad mask, opcodes, RSV, control frames, lengths, continuations and UTF-8 have RFC close codes', () => {
  const cases = [
    [encodeFrame('bad'), 1002],
    [encodeFrame('', { opcode: 3, mask: true }), 1002],
    [Buffer.from([0xc2, 0x80]), 1002],
    [encodeFrame('', { opcode: 9, fin: false, mask: true }), 1002],
    [encodeFrame(Buffer.alloc(126), { opcode: 9, mask: true }), 1002],
    [encodeFrame('', { opcode: 0, mask: true }), 1002],
    [Buffer.from([0x82, 0xfe, 0, 1, 0, 0, 0, 0, 0]), 1002],
    [Buffer.from([0x82, 0xff, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]), 1002],
    [encodeFrame(Buffer.from([0xff]), { opcode: 1, mask: true }), 1007],
    [encodeFrame(Buffer.from([0]), { opcode: 8, mask: true }), 1002],
    [encodeFrame(Buffer.from([3, 237]), { opcode: 8, mask: true }), 1002], // 1005 cannot be sent
    [encodeFrame(Buffer.from([3, 232, 0xff]), { opcode: 8, mask: true }), 1007],
  ];
  for (const [wire, code] of cases) assert.throws(() => new FrameParser().push(wire), { code });
  const parser = new FrameParser();
  parser.push(encodeFrame('', { fin: false, mask: true }));
  assert.throws(() => parser.push(encodeFrame('', { mask: true })), { code: 1002 });
  assert.throws(() => new FrameParser({ masked: false }).push(encodeFrame('', { mask: true })), { code: 1002 });
});

class Socket extends EventEmitter {
  writableLength = 0;
  writes = [];
  setNoDelay() {}
  setTimeout(ms, fn) { this.idle = fn; }
  pause() {}
  resume() {}
  write(frame) { this.writes.push(frame); return this.writableLength === 0; }
  end() { this.destroy(); }
  destroy() { if (!this.destroyed) { this.destroyed = true; this.emit('close'); } }
}

test('bounded writes expose backpressure/drain and overflow closes 1013; idle and close deadline clean up', async () => {
  const socket = new Socket(), ws = new WebSocketConnection(socket, undefined, { maxBufferedAmount: 100, closeTimeoutMs: 10 });
  socket.writableLength = 20;
  assert.equal(ws.send('small'), false); // accepted by socket; caller must await drain
  const drained = once(ws, 'drain'); socket.emit('drain'); await drained;
  assert.equal(ws.send(Buffer.alloc(100)), false); assert.equal(ws.state, 'closing');
  const parser = new FrameParser({ masked: false }); let close;
  parser.on('close', (...args) => { close = args; }); parser.push(socket.writes.at(-1));
  assert.deepEqual(close, [1013, 'Backpressure limit']);
  const closed = once(ws, 'close'); await closed; assert.ok(socket.destroyed);
  const idleSocket = new Socket(), idle = new WebSocketConnection(idleSocket);
  idleSocket.idle(); assert.equal(idle.state, 'closing'); idle.terminate();
});

async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return `ws://127.0.0.1:${server.address().port}`;
}

async function upstreamFixture(t, { status, onConnect = () => {} } = {}) {
  let requests = 0;
  const peers = new Set(), headers = [];
  const server = http.createServer((req, res) => { requests++; res.writeHead(status || 405); res.end(); });
  server.on('upgrade', (req, socket, head) => {
    headers.push(req.headers);
    if (status) { rejectUpgrade(socket, status, 'Synthetic refusal'); return; }
    const ws = acceptWebSocket(req, socket, head); peers.add(ws); ws.once('close', () => peers.delete(ws)); onConnect(ws);
  });
  const url = await listen(server);
  t.after(async () => { for (const peer of peers) peer.terminate(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  return { url, server, peers, headers, requests: () => requests };
}

async function gatewayFixture(t, upstream, { config = {}, secret, now } = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'assistant-call-')), store = createStore(dir), logs = [];
  const app = await createApp({ env: { GATEWAY_BASE_PATH: '/gateway' }, store, log: (...args) => logs.push(args),
    secrets: { get: async name => name === 'GLASSES_API_TOKEN' ? 'synthetic-phone' : name === 'GLASSES_AGENT_TOKEN' ? 'synthetic-joy' : secret ? secret(name) : 'synthetic-call' },
    deps: { glasses: { now, config: { t3Machines: [], hermesCallUrl: upstream?.url, hermesCallDevice: 'synthetic-device', ...config } } } });
  const url = await listen(app.server);
  t.after(async () => { for (const ws of app.websockets) ws.terminate(); app.server.closeAllConnections(); await new Promise(resolve => app.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const connect = (prefix = '', opts = {}) => connectWebSocket(url + prefix + '/glasses/v1/assistant/call', { headers: { Authorization: 'Bearer synthetic-phone' }, ...opts });
  return { ...app, url, connect, logs };
}

// Collect events immediately, including upgrade-head frames, without racing subsequent awaits.
function inbox(ws) {
  const events = [], waiters = [];
  ws.on('message', (data, binary) => { const value = [data, binary]; if (waiters.length) waiters.shift()(value); else events.push(value); });
  return () => events.length ? Promise.resolve(events.shift()) : new Promise(resolve => waiters.push(resolve));
}
const track = (t, ws) => { t.after(() => ws.terminate()); return ws; };

test('gateway upgrade auth uses only headers, honors scopes/prefixes, and rejects unknown routes', async t => {
  const up = await upstreamFixture(t), app = await gatewayFixture(t, up);
  for (const prefix of ['', '/api', '/gateway']) {
    const ws = track(t, await app.connect(prefix));
    const closed = once(ws, 'close'); ws.close(1000, 'Done'); await closed;
  }
  for (const headers of [{}, { 'Sec-WebSocket-Protocol': 'bearer.synthetic-phone' }]) {
    await assert.rejects(app.connect('', { headers }), { status: 401 });
  }
  await assert.rejects(connectWebSocket(app.url + '/glasses/v1/assistant/call?token=synthetic-phone'), { status: 401 });
  await assert.rejects(app.connect('', { headers: { Authorization: 'Bearer synthetic-joy' } }), { status: 403 });
  await assert.rejects(connectWebSocket(app.url + '/glasses/v1/missing'), { status: 404 });
  const response = await fetch(app.url.replace('ws:', 'http:') + '/glasses/v1/assistant/call', { headers: { Authorization: 'Bearer synthetic-phone' } });
  assert.equal(response.status, 404); // upgrades have their own route table
});

test('RFC example handshake accept, malformed key/version, protocol-error close and automatic pong', async t => {
  const up = await upstreamFixture(t, { onConnect: ws => ws.on('message', (data, binary) => ws.send(data, binary)) });
  const raw = async headers => {
    const socket = net.connect(new URL(up.url).port, '127.0.0.1'); t.after(() => socket.destroy());
    await once(socket, 'connect');
    socket.write('GET / HTTP/1.1\r\nHost: local\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n' + headers + '\r\n\r\n');
    return (await once(socket, 'data'))[0].toString();
  };
  // Exercise handshake failures through the gateway's HTTP error handling.
  const app = await gatewayFixture(t, up);
  const request = headers => new Promise(resolve => {
    const req = http.request(app.url.replace('ws:', 'http:') + '/glasses/v1/assistant/call', { headers: { Connection: 'Upgrade', Upgrade: 'websocket', Authorization: 'Bearer synthetic-phone', ...headers } });
    req.on('response', res => { res.resume(); resolve(res); }); req.end();
  });
  assert.equal((await request({ 'Sec-WebSocket-Key': 'bad', 'Sec-WebSocket-Version': '13' })).statusCode, 400);
  const version = await request({ 'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ==', 'Sec-WebSocket-Version': '12' });
  assert.equal(version.statusCode, 426); assert.equal(version.headers['sec-websocket-version'], '13');
  assert.match(await raw('Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13'), /Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK\+xOo=/);
  const ws = track(t, await connectWebSocket(up.url));
  const pong = once(ws, 'pong'); ws.ping('alive'); assert.deepEqual((await pong)[0], Buffer.from('alive'));
  const closed = once(ws, 'close'); ws.socket.write(encodeFrame('unmasked')); assert.equal((await closed)[0], 1002);
});

test('end-to-end relay sends upstream headers and preserves text/binary order and close in both directions', async t => {
  const received = [];
  const up = await upstreamFixture(t, { onConnect: ws => {
    ws.send('{"event":"hello","protocol":1}');
    ws.on('message', (data, binary) => { received.push([data, binary]); ws.send(data, binary); });
  } });
  const app = await gatewayFixture(t, up);
  const phone = track(t, await app.connect('/gateway')), next = inbox(phone);
  // Send immediately, before secret lookup and the upstream handshake have completed.
  const messages = [['{"type":"hello","fw":"synthetic"}', false], [Buffer.alloc(640, 1), true], ['{"kind":"drained"}', false], [Buffer.alloc(960, 2), true]];
  for (const [data, binary] of messages) phone.send(data, binary);
  assert.deepEqual(await next(), [Buffer.from('{"event":"hello","protocol":1}'), false]);
  for (const [data, binary] of messages) assert.deepEqual(await next(), [Buffer.from(data), binary]);
  assert.deepEqual(received, messages.map(([data, binary]) => [Buffer.from(data), binary]));
  assert.equal(up.headers[0].authorization, 'Bearer synthetic-call'); assert.equal(up.headers[0]['x-assistant-device'], 'synthetic-device');
  assert.equal(up.headers[0]['sec-websocket-protocol'], undefined);
  const upstream = [...up.peers][0], upstreamClosed = once(upstream, 'close'), phoneClosed = once(phone, 'close');
  phone.close(4001, 'button');
  assert.deepEqual(await phoneClosed, [4001, 'button']); assert.deepEqual(await upstreamClosed, [4001, 'button']);
  const second = track(t, await app.connect()), secondNext = inbox(second); await secondNext();
  const secondClosed = once(second, 'close'); [...up.peers][0].close(4002, 'finished');
  assert.deepEqual(await secondClosed, [4002, 'finished']);
  const logs = JSON.stringify(app.logs);
  for (const privateText of ['synthetic-call', 'synthetic-phone', 'button', 'finished', '"fw"']) assert.ok(!logs.includes(privateText));
  assert.ok(app.logs.some(row => row[1] === 'close' && row[2].upBytes > 0 && row[2].downBytes > 0));
});

test('busy calls reject with a frame; activity refreshes the lease; only >30s silent calls are replaced', async t => {
  let now = 0;
  const up = await upstreamFixture(t, { onConnect: ws => { ws.send('ready'); ws.on('message', (data, binary) => ws.send(data, binary)); } });
  const app = await gatewayFixture(t, up, { now: () => now });
  const first = track(t, await app.connect()), firstNext = inbox(first); await firstNext();
  now = 30000;
  const busy = track(t, await app.connect()), busyNext = inbox(busy), busyClosed = once(busy, 'close');
  assert.deepEqual(JSON.parse((await busyNext())[0]), { type: 'hangup', reason: 'busy' }); assert.equal((await busyClosed)[0], 1008);
  assert.equal(up.headers.length, 1);
  first.send('still here'); assert.equal((await firstNext())[0].toString(), 'still here');
  now = 60000;
  const alsoBusy = track(t, await app.connect()), busyAgain = inbox(alsoBusy), againClosed = once(alsoBusy, 'close');
  assert.equal(JSON.parse((await busyAgain())[0]).reason, 'busy'); await againClosed;
  now = 60001;
  const firstClosed = once(first, 'close'), replacement = track(t, await app.connect()), replacementNext = inbox(replacement);
  assert.deepEqual(await firstClosed, [1001, 'Stale call replaced']); assert.equal((await replacementNext())[0].toString(), 'ready');
  assert.equal(up.headers.length, 2);
});

test('unavailable upstream 401/404, missing config or token send fallback then close 1011', async t => {
  for (const status of [401, 404]) await t.test('HTTP ' + status, async t => {
    const up = await upstreamFixture(t, { status }), app = await gatewayFixture(t, up);
    const ws = track(t, await app.connect()), next = inbox(ws), closed = once(ws, 'close');
    assert.deepEqual(JSON.parse((await next())[0]), { type: 'hangup', reason: 'unavailable', detail: 'Upstream HTTP ' + status });
    assert.equal((await closed)[0], 1011);
  });
  for (const options of [{ config: { hermesCallUrl: '' } }, { secret: () => null }]) await t.test('missing configuration', async t => {
    const up = await upstreamFixture(t), app = await gatewayFixture(t, up, options);
    const ws = track(t, await app.connect()), next = inbox(ws), closed = once(ws, 'close');
    assert.equal(JSON.parse((await next())[0]).reason, 'unavailable'); assert.equal((await closed)[0], 1011); assert.equal(up.headers.length, 0);
  });
});

test('status checks secret and host without starting calls, caches for 30 seconds, and tolerates HEAD 404', async t => {
  let now = 0;
  const up = await upstreamFixture(t), app = await gatewayFixture(t, up, { now: () => now });
  const status = async app => {
    const response = await fetch(app.url.replace('ws:', 'http:') + '/glasses/v1/assistant/call/status', { headers: { Authorization: 'Bearer synthetic-phone' } });
    assert.equal(response.status, 200); return response.json();
  };
  assert.deepEqual(await status(app), { available: true }); assert.deepEqual(await status(app), { available: true });
  assert.equal(up.requests(), 1); assert.equal(up.headers.length, 0);
  now = 30000; await status(app); assert.equal(up.requests(), 2);
  const refused = await upstreamFixture(t, { status: 404 }), refusedApp = await gatewayFixture(t, refused);
  assert.deepEqual(await status(refusedApp), { available: true }); // host answers; upgrade refusal is reported in-band
  const noSecret = await gatewayFixture(t, up, { secret: () => { throw new Error('private detail'); } });
  assert.deepEqual(await status(noSecret), { available: false, reason: 'Call credential unavailable' });
  const disabled = await gatewayFixture(t, up, { config: { hermesCallUrl: '' } });
  assert.deepEqual(await status(disabled), { available: false, reason: 'Call URL not configured' });
  const offline = await upstreamFixture(t);
  await new Promise(resolve => offline.server.close(resolve));
  const offlineApp = await gatewayFixture(t, offline);
  assert.deepEqual(await status(offlineApp), { available: false, reason: 'Call upstream unreachable' });
});

test('call safety cap closes both peers at four hours even with ongoing activity; long calls stay up', async t => {
  const up = await upstreamFixture(t, { onConnect: ws => ws.send('ready') }), app = await gatewayFixture(t, up);
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const phone = track(t, await app.connect()), next = inbox(phone); await next();
  const upstream = [...up.peers][0], upClosed = once(upstream, 'close'), closed = once(phone, 'close');
  // The old 16-minute cap is gone: an hour-long call is still open.
  t.mock.timers.tick(60 * 60 * 1000); assert.equal(phone.state, 'open');
  t.mock.timers.tick(3 * 60 * 60 * 1000 - 1); assert.equal(phone.state, 'open');
  for (const ws of app.websockets) ws.emit('activity');
  t.mock.timers.tick(1);
  assert.deepEqual(await closed, [1000, 'Call duration limit']); assert.deepEqual(await upClosed, [1000, 'Call duration limit']);
});

test('HERMES_CALL_MAX_MS overrides the cap, 0 disables it, and invalid values keep the default', async () => {
  const { maxCallMs, MAX_CALL_MS } = await import('../apis/glasses/routes/call.mjs');
  assert.equal(MAX_CALL_MS, 4 * 60 * 60 * 1000);
  assert.equal(maxCallMs({ config: {} }), MAX_CALL_MS);
  assert.equal(maxCallMs({ config: { hermesCallMaxMs: 1000 } }), 1000);
  assert.equal(maxCallMs({ config: { hermesCallMaxMs: 0 } }), 0);
  assert.equal(maxCallMs({ config: { hermesCallMaxMs: NaN } }), MAX_CALL_MS);
  assert.equal(maxCallMs({ config: { hermesCallMaxMs: -5 } }), MAX_CALL_MS);
});

test('keepalive pings both peers every 20 seconds while the call is up', async t => {
  let upPings = 0;
  t.mock.timers.enable({ apis: ['setInterval'] });
  const up = await upstreamFixture(t, { onConnect: ws => { ws.on('ping', () => upPings++); ws.send('ready'); } }), app = await gatewayFixture(t, up);
  const phone = track(t, await app.connect()), next = inbox(phone); await next();
  let phonePings = 0; phone.on('ping', () => phonePings++);
  t.mock.timers.tick(20000);
  for (let i = 0; i < 20 && !(phonePings && upPings); i++) await new Promise(resolve => setImmediate(resolve));
  assert.ok(phonePings >= 1 && upPings >= 1, `pings phone=${phonePings} upstream=${upPings}`);
});

test('a resuming reconnect replaces the same phone\'s live socket instead of being refused busy', async t => {
  let now = 0;
  const up = await upstreamFixture(t, { onConnect: ws => ws.send('ready') });
  const app = await gatewayFixture(t, up, { now: () => now });
  const first = track(t, await app.connect()), firstNext = inbox(first); await firstNext();
  now = 1000; // well inside the 30 s busy lease
  const firstClosed = once(first, 'close');
  const resumed = track(t, await app.connect('', { headers: { Authorization: 'Bearer synthetic-phone', 'X-Assistant-Call-Resume': '1' } })), resumedNext = inbox(resumed);
  assert.deepEqual(await firstClosed, [1001, 'Call resumed elsewhere']);
  assert.equal((await resumedNext())[0].toString(), 'ready');
  assert.equal(up.headers.length, 2);
  assert.ok(app.logs.some(row => row[1] === 'open' && row[2]?.resume === true));
});

test('downstream buffer overflow closes both relay peers 1013 instead of silently losing audio', async t => {
  const up = await upstreamFixture(t, { onConnect: ws => ws.send('ready') }), app = await gatewayFixture(t, up);
  const phone = track(t, await app.connect()), next = inbox(phone); await next();
  const upstream = [...up.peers][0], upClosed = once(upstream, 'close'), closed = once(phone, 'close');
  // Force the real gateway destination to exceed its configured bound deterministically.
  [...app.websockets][0].maxBuffer = 512;
  upstream.send(Buffer.alloc(960));
  assert.deepEqual(await closed, [1013, 'Backpressure limit']); assert.deepEqual(await upClosed, [1013, 'Backpressure limit']);
});

test('upstream transport loss closes the phone and releases the active-call reservation', async t => {
  const up = await upstreamFixture(t, { onConnect: ws => ws.send('ready') }), app = await gatewayFixture(t, up);
  const phone = track(t, await app.connect()), next = inbox(phone); await next();
  const closed = once(phone, 'close'); [...up.peers][0].terminate();
  assert.equal((await closed)[0], 1011);
  const replacement = track(t, await app.connect()), replacementNext = inbox(replacement);
  assert.equal((await replacementNext())[0].toString(), 'ready');
});

test('startup buffering is bounded while the upstream credential is pending', async t => {
  const up = await upstreamFixture(t), app = await gatewayFixture(t, up, { secret: () => new Promise(() => {}) });
  const phone = track(t, await app.connect()), closed = once(phone, 'close');
  phone.maxBuffer = 8 * 1024 * 1024;
  for (let i = 0; i < 3; i++) phone.send(Buffer.alloc(1024 * 1024));
  assert.deepEqual(await closed, [1013, 'Call startup buffer limit']); assert.equal(up.headers.length, 0);
});

test('status and call startup have overall deadlines even if secret resolution stalls', async t => {
  const up = await upstreamFixture(t), app = await gatewayFixture(t, up, { secret: () => new Promise(() => {}) });
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const phone = track(t, await app.connect()), next = inbox(phone), closed = once(phone, 'close');
  t.mock.timers.tick(8000);
  assert.deepEqual(JSON.parse((await next())[0]), { type: 'hangup', reason: 'unavailable', detail: 'Call connection timed out' });
  assert.equal((await closed)[0], 1011); assert.equal(up.headers.length, 0);
  const status = app.router.match('GET', '/glasses/v1/assistant/call/status').route.handler();
  await Promise.resolve(); // the shared cache starts its callback in a microtask
  t.mock.timers.tick(3000);
  assert.deepEqual(await status, { available: false, reason: 'Call status timed out' });
});
