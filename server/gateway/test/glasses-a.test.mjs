import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { createEventBus } from '../apis/glasses/lib/context.mjs';
import { status, rpc, detail, step, usage, usageWindows, modelSelection, GLASSES_SUFFIX } from '../apis/glasses/lib/t3.mjs';
import { mapMessage } from '../apis/glasses/lib/beeper.mjs';
import { sseEvents, toolStatus, GLASSES_CONTEXT } from '../apis/glasses/lib/hermes.mjs';
import { notificationRule } from '../apis/glasses/routes/events.mjs';

const NOW = Date.parse('2026-11-12T12:00:00Z');
const stamp = (delta = 0) => new Date(NOW + delta).toISOString();
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const sse = (name, payload) => `event: ${name}\r\ndata: ${JSON.stringify(payload)}\r\n\r\n`;
const lines = (value) => value.trim().split('\n').filter(Boolean).map(line => JSON.parse(line));
const machine = { id: 'machine-a', alias: 'mac', label: 'Mac', url: 'https://t3.example', secret: 'T3_MAC_TOKEN' };
const provider = { driver: 'claudeAgent', displayName: 'Claude', enabled: true, status: 'ready', instanceId: 'claude-shared', models: [{ slug: 'sonnet', default: true }], usageLimits: { checkedAt: stamp(), sourceId: 'anthropic', accountId: 'shared', windows: [{ label: '5h', usedPercent: 50, resetsAt: stamp(150 * 60000), windowDurationMins: 300 }], resetCredits: [{ creditId: 'reset', label: 'Reset', description: 'One reset', availableCount: 1 }] } };

class FakeWebSocket {
  static sent = [];
  static urls = [];
  static handler = (request) => request.tag === 'server.getConfig' ? { providers: [provider] } : request.tag === 'orchestration.launchThread' ? { threadId: 'new-thread' } : { ok: true };
  static chunk = false;
  constructor(url) { FakeWebSocket.urls.push(new URL(url)); queueMicrotask(() => this.onopen?.()); }
  send(raw) {
    const frame = JSON.parse(raw); FakeWebSocket.sent.push(frame);
    if (frame._tag !== 'Request') return;
    queueMicrotask(() => {
      this.onmessage?.({ data: JSON.stringify({ _tag: 'Ping' }) });
      if (FakeWebSocket.chunk) this.onmessage?.({ data: JSON.stringify({ _tag: 'Chunk', requestId: '1', values: [{ sample: true }] }) });
      else this.onmessage?.({ data: JSON.stringify([{ _tag: 'Exit', requestId: 'different', exit: { _tag: 'Success', value: 'ignored' } }, { _tag: 'Exit', requestId: '1', exit: { _tag: 'Success', value: FakeWebSocket.handler(frame) } }]) });
    });
  }
  close() { this.onclose?.(); }
}

async function app(t, fake, config = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'glasses-a-'));
  const store = createStore(dir, { now: () => NOW }); // same fixed clock as the app, or the store prunes by the real date
  const result = await createApp({ env: {}, log: () => {}, store, secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone', GLASSES_AGENT_TOKEN: 'joy' }[name] ?? 'upstream-secret') }, deps: { glasses: { fetch: fake ?? (async () => { throw new Error('No network in tests'); }), WebSocket: FakeWebSocket, now: () => NOW, config: { t3Machines: [], ...config } } } });
  result.server.listen(0, '127.0.0.1'); await once(result.server, 'listening');
  const base = 'http://127.0.0.1:' + result.server.address().port + '/glasses/v1';
  t.after(async () => { result.server.closeAllConnections(); await new Promise(resolve => result.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const call = (route, { method = 'GET', body, token = 'phone', ...opts } = {}) => fetch(base + route, { method, headers: { Authorization: 'Bearer ' + token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...opts.headers }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}), ...Object.fromEntries(Object.entries(opts).filter(([key]) => key !== 'headers')) });
  return { ...result, base, call };
}

test('T3 status table follows precedence and real shell fields', async t => {
  const cases = [
    [{ settledOverride: 'settled', pendingRuntimeRequest: { kind: 'user_input' } }, 'settled'],
    [{ settledAt: stamp(), unsettledAt: stamp(-1000) }, 'settled'],
    [{ settledAt: stamp(-1000), unsettledAt: stamp(), status: 'running' }, 'working'],
    [{ settledAt: stamp() }, 'settled'],
    [{ pendingRuntimeRequest: { kind: 'user_input' }, pendingGuestMessageCount: 2 }, 'input'],
    [{ pendingRuntimeRequest: { kind: 'command' } }, 'approval'],
    [{ pendingGuestMessageCount: 1, usageLimitResetAt: stamp(1000) }, 'request'],
    [{ usageLimitResetAt: stamp(1000), status: 'failed' }, 'limited'],
    [{ lastErrorClass: 'UsageLimitError' }, 'limited'],
    [{ lastErrorCode: 'usage_limit' }, 'limited'],
    [{ status: 'failed' }, 'failed'],
    [{ status: 'running' }, 'working'],
    [{ activityRunStatus: 'running' }, 'working'],
    [{ activityRunStatus: 'preparing' }, 'working'],
    [{ status: 'running', blockedOnThreadWait: true }, 'waiting'],
    [{ latestRunCompletedAt: stamp(), lastVisitedAt: stamp(-1000) }, 'done'],
    [{ updatedAt: stamp(), lastVisitedAt: stamp(-1000) }, 'done'],
    [{ updatedAt: stamp(), lastVisitedAt: stamp() }, 'ready'],
    [{ latestRunCompletedAt: stamp(-2000), updatedAt: stamp(), lastVisitedAt: stamp(-1000) }, 'ready'],
  ];
  for (const [thread, expected] of cases) await t.test(expected + ': ' + JSON.stringify(thread), () => assert.equal(status(thread), expected));
});

test('phone and Hermes scope separation applies in both directions', async t => {
  const { call } = await app(t);
  for (const route of ['/assistant/notify', '/assistant/doc', '/assistant/open', '/assistant/media', '/assistant/reminder']) assert.equal((await call(route, { method: 'POST', body: {} })).status, 403);
  assert.equal((await call('/assistant/state')).status, 403);
  for (const route of ['/events?wait=0', '/docs', '/reminders', '/t3/threads']) assert.equal((await call(route, { token: 'joy' })).status, 403);
  assert.equal((await call('/assistant/chat', { token: 'joy', method: 'POST', body: { message: 'hello' } })).status, 403);
  assert.equal((await call('/docs', { token: 'wrong' })).status, 403);
});

test('Hermes tools store docs/state/reminders and publish phone events; expiry and deletion', async t => {
  const { call, store } = await app(t);
  const tool = async (route, body) => { const response = await call(route, { method: 'POST', body, token: 'joy' }); assert.equal(response.status, 200); return response.json(); };
  const doc = await tool('/assistant/doc', { title: 'Synthetic note', text: 'A short document.' });
  assert.deepEqual(await (await call('/docs')).json(), [{ id: doc.id, title: 'Synthetic note', createdAt: stamp() }]);
  assert.deepEqual(await (await call('/docs/' + doc.id)).json(), { id: doc.id, title: 'Synthetic note', text: 'A short document.' });
  await tool('/assistant/open', { screen: 'docs', args: { id: doc.id } });
  await tool('/assistant/media', { action: 'pause' });
  await tool('/assistant/notify', { title: 'Hello', body: 'A message' });
  const reminder = await tool('/assistant/reminder', { text: 'Remember this', condition: { type: 'person', value: 'Friend' } });
  assert.equal(Date.parse(reminder.expiresAt), NOW + 30 * 86400000);
  const second = await (await call('/reminders', { method: 'POST', body: { text: 'At the store', condition: { type: 'place', value: 'Store' }, expiresAt: stamp(1000) } })).json();
  store.put('reminders', { id: 'expired', text: 'Old', condition: { type: 'place', value: 'Old' }, expiresAt: stamp(-1000) });
  assert.equal((await (await call('/reminders')).json()).length, 2);
  const state = await (await call('/assistant/state', { token: 'joy' })).json();
  assert.equal(state.screen.screen, 'docs'); assert.equal(state.media.action, 'pause'); assert.equal(state.docs.length, 1); assert.equal(state.reminders.length, 2);
  const events = await (await call('/events?wait=0')).json();
  assert.equal(events.cursor, 6); assert.deepEqual(events.events.map(e => e.type), ['doc', 'open', 'media', 'notification', 'reminder', 'reminder']);
  assert.equal((await (await call('/events?since=' + events.cursor + '&wait=0')).json()).events.length, 0);
  const pending = call('/events?since=' + events.cursor + '&wait=25');
  await tool('/assistant/open', { screen: 'home' });
  assert.equal((await (await pending).json()).events[0].screen, 'home');
  assert.deepEqual(await (await call('/docs/' + doc.id, { method: 'DELETE' })).json(), { ok: true });
  assert.equal((await call('/docs/' + doc.id)).status, 404);
  assert.equal((await call('/reminders/' + second.id, { method: 'DELETE' })).status, 200);
  assert.equal((await call('/reminders', { method: 'POST', body: { text: 'Invalid', condition: { type: 'time', value: 'now' } } })).status, 400);
  assert.equal((await call('/reminders', { method: 'POST', body: { text: 'Expired', condition: { type: 'place', value: 'Store' }, expiresAt: stamp(-1) } })).status, 400);
});

test('POST /notify/classify is a thin wrapper over /notify/route with the old response shape', async t => {
  let calls = 0, answer = { route: 'suppress', importance: 0.2, reason: 'banter' };
  const { call } = await app(t, async (url, opts) => {
    if (!String(url).includes('/chat/completions')) throw new Error('No Beeper in this test');
    calls++; const body = JSON.parse(opts.body); assert.equal(body.model, 'gpt-4o-mini'); assert.equal(body.response_format.type, 'json_object'); assert.equal(body.temperature, 0);
    return json({ choices: [{ message: { content: JSON.stringify(answer) } }] });
  });
  const classify = async body => (await call('/notify/classify', { method: 'POST', body })).json();
  assert.deepEqual(await classify({ source: 'Hermes', title: 'Hello' }), { route: 'notify', importance: 0.7 });
  assert.deepEqual(await classify({ app: 'T3', title: 'Needs you' }), { route: 'suppress', importance: 0 }); // unwatched thread
  assert.deepEqual(await classify({ source: 'io.homeassistant.companion.android', title: 'Integration stale' }), { route: 'suppress', importance: 0 });
  assert.deepEqual(await classify({ title: 'Newsletter', body: '50% off' }), { route: 'suppress', importance: 0 });
  assert.equal((await classify({ source: 'com.beeper.android', isGroupConversation: true, body: 'lol!' })).route, 'suppress');
  assert.equal(calls, 0);
  assert.deepEqual(await classify({ source: 'com.beeper.android', title: 'Sam', body: 'Hello there' }), { route: 'suppress', importance: 0.2 });
  answer = { route: 'popup', importance: 0.9, reason: 'asks Robin' };
  assert.deepEqual(await classify({ source: 'com.beeper.android', title: 'Sam', body: 'Dinner at 7?' }), { route: 'notify', importance: 0.9 });
  answer = { route: 'invalid', importance: 'high' };
  assert.deepEqual(await classify({ source: 'com.beeper.android', title: 'Sam', body: 'An ordinary message' }), { route: 'notify', importance: 0.4 });
  assert.equal(notificationRule({ title: 'Promo sale' }).route, 'suppress');
});

test('the classifier falls back on proxy failure without exposing upstream errors', async t => {
  const { call } = await app(t, async () => { throw new Error('private-upstream-secret'); });
  const response = await (await call('/notify/route', { method: 'POST', body: { kind: 'beeper', source: 'com.beeper.android', title: 'Sam', body: 'Hello', debug: true } })).json();
  assert.equal(response.route, 'stream'); assert.equal(response.by, 'fallback'); assert.doesNotMatch(JSON.stringify(response), /private-upstream-secret/);
  assert.deepEqual(await (await call('/notify/classify', { method: 'POST', body: { source: 'com.beeper.android', title: 'Hello', body: 'Hi' } })).json(), { route: 'notify', importance: 0.4 });
});

test('the classifier model call is bounded by its timeout and never pops up on timeout', async t => {
  assert.equal(notificationRule({ source: 'T3-needs-you' }).importance, 0.95);
  const { call } = await app(t, async (_url, opts) => new Promise((resolve, reject) => opts.signal.addEventListener('abort', () => reject(opts.signal.reason), { once: true })), { notifyTimeoutMs: 300, resolveTimeoutMs: 200 });
  const start = performance.now();
  const routed = await (await call('/notify/route', { method: 'POST', body: { kind: 'beeper', title: 'Sam', body: 'Ambiguous' } })).json();
  assert.equal(routed.route, 'stream'); assert.equal(routed.by, 'fallback');
  assert.ok(performance.now() - start < 2500);
});

test('event bus preserves successive entity events, retained cursor and envelope fields', async t => {
  const { store } = await app(t);
  const bus = createEventBus(store, { max: 3 });
  bus.publish('doc', { id: 'same-document', title: 'Created' });
  bus.publish('doc', { id: 'same-document', deleted: true });
  assert.equal(bus.since(0).length, 2); assert.ok(bus.since(0).every(e => e.id === 'same-document'));
  bus.publish('open', { screen: 'home', type: 'bad', seq: -1, at: 'bad' });
  assert.equal(bus.since(2)[0].type, 'open'); assert.equal(bus.since(2)[0].seq, 3);
  bus.publish('media', { action: 'pause' });
  assert.deepEqual(bus.since(0).map(e => e.seq), [2, 3, 4]);
  await store.flushed();
  assert.equal(createEventBus(store).cursor(), 4);
});

test('SSE incremental parser handles split UTF-8, CRLF, comments, multiline and EOF', async () => {
  const bytes = Buffer.from(': keepalive\r\nevent: assistant.delta\r\ndata: {"delta":\r\ndata: "café"}\r\n\r\nevent: done\ndata: {}');
  const stream = new ReadableStream({ start(controller) { for (let i = 0; i < bytes.length; i++) controller.enqueue(bytes.subarray(i, i + 1)); controller.close(); } });
  const result = []; for await (const event of sseEvents(stream)) result.push(event);
  assert.deepEqual(result, [{ name: 'assistant.delta', payload: { delta: 'café' } }, { name: 'done', payload: {} }]);
  for (const [name, expected] of Object.entries({ delegate_task: 'delegating', spawn: 'delegating', web_search: 'searching', memory_recall: 'remembering', write_file: 'saving', shell: 'working' })) assert.equal(toolStatus(name), expected);
});

test('SSE parser rejects oversized and malformed records', async () => {
  const drain = async value => { for await (const _event of sseEvents(new Response(value).body)) { /* drain */ } };
  await assert.rejects(drain('data: ' + 'x'.repeat(2 * 1024 * 1024 + 1)), /too large/);
  await assert.rejects(drain('data: {bad}\n\n'), SyntaxError);
});

test('Hermes sessions and NDJSON: status mapping, session rotation, final event and contextual prefix', async t => {
  const statuses = ['run.started', 'tool.started', 'tool.completed', 'tool.progress', 'tool.failed', 'approval.request', 'assistant.commentary'];
  const tools = ['', 'delegate_task', 'web_search', 'memory_recall', 'write_file', '', ''];
  const seen = [];
  const { call } = await app(t, async (url, opts) => {
    seen.push({ url: String(url), body: JSON.parse(opts.body) });
    if (String(url).endsWith('/api/sessions')) return json({ session: { id: 'session-1' } });
    return new Response(statuses.map((name, i) => sse(name, { tool_name: tools[i] })).join('') + sse('assistant.delta', { delta: 'Hi' }) + sse('assistant.completed', { content: 'Hi Robin.', session_id: 'session-2' }) + sse('run.completed', {}) + sse('done', {}));
  });
  assert.deepEqual(await (await call('/assistant/sessions', { method: 'POST', body: {} })).json(), { sessionId: 'session-1' });
  const response = await call('/assistant/chat', { method: 'POST', body: { message: 'Hello', sessionId: 'session-1', notifyIfLeft: true } });
  assert.match(response.headers.get('content-type'), /ndjson/);
  const events = lines(await response.text());
  assert.deepEqual(events[0], { type: 'session', sessionId: 'session-1' });
  assert.deepEqual(events.at(-1), { type: 'result', text: 'Hi Robin.', sessionId: 'session-2' });
  const values = events.filter(e => e.type === 'status').map(e => e.status);
  for (const v of ['thinking', 'delegating', 'searching', 'remembering', 'saving', 'waiting', 'speaking']) assert.ok(values.includes(v), v);
  assert.ok(seen[1].body.message.startsWith(GLASSES_CONTEXT)); assert.equal(seen[1].body.model, undefined); assert.equal(seen[1].body.provider, undefined);
  const pushed = (await (await call('/events?wait=0')).json()).events[0];
  assert.equal(pushed.type, 'joy_reply'); assert.equal(pushed.sessionId, 'session-2'); assert.equal(pushed.importance, 0.85); assert.deepEqual(pushed.open, { screen: 'joy' });
  await (await call('/assistant/chat', { method: 'POST', body: { sessionId: 'session-1', message: 'Again' } })).text();
  assert.match(seen[2].url, /session-2\/chat\/stream$/);
});

test('Hermes continues consuming after phone disconnect and rejects overlapping turns', async t => {
  let controller, finish;
  const completed = new Promise(resolve => { finish = resolve; });
  const { call, store } = await app(t, async () => new Response(new ReadableStream({ start(c) { controller = c; c.enqueue(Buffer.from(sse('run.started', {}))); } })));
  const abort = new AbortController();
  const response = await call('/assistant/chat', { method: 'POST', body: { message: 'Hello', sessionId: 'busy' }, signal: abort.signal });
  const reader = response.body.getReader(); await reader.read();
  assert.equal((await call('/assistant/chat', { method: 'POST', body: { message: 'Overlap', sessionId: 'busy' } })).status, 409);
  abort.abort(); await reader.cancel().catch(() => {});
  const put = store.put.bind(store); store.put = (collection, doc) => { const result = put(collection, doc); if (collection === 'assistant-sessions') finish(); return result; };
  controller.enqueue(Buffer.from(sse('assistant.delta', { delta: 'Still here' }) + sse('assistant.completed', { content: 'Final after disconnect', session_id: 'rotated' }) + sse('done', {}))); controller.close();
  await completed;
  const events = await (await call('/events?wait=0')).json();
  assert.equal(events.events[0].text, 'Final after disconnect'); assert.equal(events.events[0].sessionId, 'rotated');
});

test('Hermes malformed/incomplete upstream stream emits error without private details', async t => {
  const { call } = await app(t, async () => new Response(sse('assistant.delta', { delta: 'partial' })));
  const values = lines(await (await call('/assistant/chat', { method: 'POST', body: { message: 'Hi', sessionId: 's' } })).text());
  assert.equal(values.at(-1).type, 'error'); assert.equal(values.at(-2).status, 'error');
  assert.equal((await call('/assistant/chat', { method: 'POST', body: { message: 'Hi', sessionId: '../bad' } })).status, 400);
});

test('voice transcribes multipart audio then enhances, skips Hermes cleanup, and clamps memo title', async t => {
  let enhanced = 0;
  const { call, base } = await app(t, async (url, opts) => {
    if (String(url).endsWith('/audio/transcriptions')) {
      assert.ok(opts.body instanceof FormData); assert.equal(opts.body.get('model'), 'whisper-1'); assert.ok(opts.body.get('file').size > 0);
      return json({ text: 'um hello there' });
    }
    const b = JSON.parse(opts.body); enhanced++;
    if (b.model === 'gpt-4o') return json({ choices: [{ message: { content: 'one two three four five six seven eight' } }] });
    assert.match(b.messages[0].content, /Never answer/); assert.match(b.messages[0].content, /identifiers/);
    assert.match(b.messages[0].content, /^You clean up dictated text/); assert.match(b.messages[0].content, /FINAL CHECK/);
    assert.equal(b.model, 'gpt-4o-mini'); assert.match(b.messages.at(-1).content, /^<<<DICTATION ([0-9a-f]{12})>>>\num hello there\n<<<END \1>>>$/);
    return json({ choices: [{ message: { content: 'Hello there.' } }] });
  });
  const dictate = async (target, contentType = 'audio/ogg') => fetch(base + '/voice/dictate?target=' + target, { method: 'POST', headers: { Authorization: 'Bearer phone', 'Content-Type': contentType }, body: Buffer.from('synthetic audio') });
  assert.deepEqual(lines(await (await dictate('t3')).text()), [{ phase: 'transcribing' }, { phase: 'enhancing', raw: 'um hello there' }, { phase: 'done', raw: 'um hello there', text: 'Hello there.', cleaned: true, silence: false }]);
  assert.deepEqual(lines(await (await dictate('joy', 'audio/aac')).text()).map(v => v.phase), ['transcribing', 'done']);
  assert.deepEqual(lines(await (await dictate('raw', 'audio/aac')).text()).map(v => v.phase), ['transcribing', 'done']);
  assert.deepEqual(lines(await (await dictate('nav', 'audio/aac')).text()).map(v => v.phase), ['transcribing', 'done']);
  assert.equal(enhanced, 1);
  assert.deepEqual(await (await call('/voice/title', { method: 'POST', body: { text: 'A note' } })).json(), { title: 'one two three four five six' });
  assert.equal((await dictate('joy', 'text/plain')).status, 415);
  assert.equal((await dictate('invalid')).status, 400);
});

test('voice rejects oversized audio and emits a transcribing error on upstream failure', async t => {
  const { base } = await app(t);
  const response = await fetch(base + '/voice/dictate?target=joy', { method: 'POST', headers: { Authorization: 'Bearer phone', 'Content-Type': 'audio/webm' }, body: Buffer.from('x') });
  assert.deepEqual(lines(await response.text()).map(e => e.phase), ['transcribing', 'error']);
  const oversized = await fetch(base + '/voice/dictate', { method: 'POST', headers: { Authorization: 'Bearer phone', 'Content-Type': 'audio/mpeg' }, body: Buffer.alloc(25 * 1024 * 1024 + 1) });
  assert.equal(oversized.status, 413);
});

test('Beeper contracts: account network, unread, sort/filter, sender/reactions/replies/approval and writes', async t => {
  const accts = [{ accountID: 'discord', network: 'Discord', user: { id: 'me' } }];
  const chat = { id: 'c', title: 'Hermes', accountID: 'discord', network: 'wrong', unreadCount: 3, type: 'single', lastActivity: stamp(), participants: { items: [{ id: 'me', fullName: 'Robin', isSelf: true }, { id: 'bot', fullName: 'Hermes', isBot: true }] }, preview: { senderID: 'bot', text: 'Latest' } };
  const messages = [{ id: '2', senderID: 'bot', senderName: 'Hermes', text: 'Please approve: react ✅ or ❌', timestamp: stamp(), linkedMessageID: '1', reactions: [{ reactionKey: '✅', participantID: 'me' }, { reactionKey: '✅', participantID: 'other' }] }, { id: '1', senderID: 'me', isSender: true, text: 'Earlier', timestamp: stamp(-1000) }];
  const writes = [];
  const { call } = await app(t, async (url, opts) => {
    const u = new URL(url);
    if (opts.method !== 'GET') { writes.push({ path: u.pathname, method: opts.method, body: opts.body && JSON.parse(opts.body) }); return json(u.pathname.endsWith('/messages') ? { pendingMessageID: 'sent' } : { success: true }); }
    if (u.pathname === '/v1/accounts') return json(accts);
    if (u.pathname === '/v1/chats') return json({ items: [{ ...chat, id: 'archived', isArchived: true }, { ...chat, id: 'low', isLowPriority: true }, { ...chat, id: 'old', unreadCount: 0, lastActivity: stamp(-1000) }, chat], hasMore: false });
    if (u.pathname.endsWith('/messages')) return json({ items: messages });
    return json(chat);
  });
  const list = await (await call('/beeper/chats?limit=1')).json();
  assert.deepEqual(list, [{ id: 'c', name: 'Hermes', network: 'Discord', unread: 3, last: { sender: 'Hermes', text: 'Latest', mine: false }, type: 'single', isGroup: false, muted: false, mentions: 0, markedUnread: false, lowPriority: false, spam: false, spamConfidence: 0, spamCategory: '', spamSource: '', counts: true, participants: { count: 2, names: ['Hermes'] }, lastActivity: stamp(), priority: 'unread', importance: 0.2, why: ['direct'], pinned: false, glassesPinned: false }]);
  const result = await (await call('/beeper/chats/c/messages?limit=30')).json();
  assert.equal(result[0].id, '1'); assert.equal(result[0].mine, true); assert.equal(result[0].sender, 'You');
  assert.deepEqual(result[1].reactions, [{ key: '✅', count: 2, mine: true }]); assert.equal(result[1].approval, true); assert.deepEqual(result[1].replyTo, { sender: 'Robin', text: 'Earlier' }); // quoted sender keeps the display name
  assert.deepEqual(await (await call('/beeper/chats/c/messages', { method: 'POST', body: { text: 'Reply', replyTo: '2' } })).json(), { id: 'sent' });
  for (const [route, options] of [['/beeper/chats/c/messages/2/reactions', { method: 'POST', body: { key: '✅' } }], ['/beeper/chats/c/messages/2/reactions?key=' + encodeURIComponent('✅'), { method: 'DELETE' }], ['/beeper/chats/c/read', { method: 'POST', body: {} }]]) assert.deepEqual(await (await call(route, options)).json(), { ok: true });
  assert.equal(writes[0].body.replyToMessageID, '2'); assert.equal(writes[1].body.reactionKey, '✅'); assert.ok(writes[2].path.endsWith('/' + encodeURIComponent('✅')));
  const home = await (await call('/home')).json(); assert.deepEqual(home.beeper, { unread: 1, unreadChats: 1, unreadMessages: 3, names: ['Hermes'], quiet: { muted: 0, spam: 0 }, total: 3 }); assert.deepEqual(home.t3, { needYou: 0, done: 0 });
});

test('T3 WS RPC frames Request, Ping/Pong, Chunk/Ack, Exit and redacts failures', async () => {
  FakeWebSocket.sent = []; FakeWebSocket.urls = [];
  const ctx = { fetch: async () => json({ ticket: 'ticket' }), secret: async () => 'secret', WebSocket: FakeWebSocket };
  const result = await rpc(ctx, machine, 'server.getConfig'); assert.equal(result.providers[0].instanceId, 'claude-shared');
  assert.deepEqual(FakeWebSocket.sent[0], { _tag: 'Request', id: '1', tag: 'server.getConfig', payload: {}, headers: [] });
  assert.equal(FakeWebSocket.sent[1]._tag, 'Pong'); assert.equal(FakeWebSocket.urls[0].protocol, 'wss:'); assert.equal(FakeWebSocket.urls[0].searchParams.get('orchestrationProtocol'), '2'); assert.equal(FakeWebSocket.urls[0].searchParams.get('wsTicket'), 'ticket');
  FakeWebSocket.chunk = true;
  try { assert.deepEqual(await rpc(ctx, machine, 'stream'), { streamSample: [{ sample: true }], subscriptionClosed: true }); assert.equal(FakeWebSocket.sent.at(-1)._tag, 'Ack'); } finally { FakeWebSocket.chunk = false; }
  class FailingSocket extends FakeWebSocket { send(raw) { if (JSON.parse(raw)._tag === 'Request') queueMicrotask(() => this.onmessage({ data: JSON.stringify({ _tag: 'Exit', requestId: '1', exit: { _tag: 'Failure', cause: 'private-secret' } }) })); } }
  await assert.rejects(rpc({ ...ctx, WebSocket: FailingSocket }, machine, 'server.getConfig'), /T3 command rejected/);
});

test('T3 bounded projection maps messages only, step/recent durations and input/approval', () => {
  const projection = { thread: { id: 't', title: 'Thread', status: 'running', updatedAt: stamp(), activeRunId: 'run', pendingRuntimeRequest: { id: 'req', kind: 'user_input' } }, messages: [{ role: 'user', text: 'Hello', createdAt: stamp(-10000) }, { role: 'tool', text: 'Private tool output' }, { role: 'assistant', text: 'Reply', createdAt: stamp() }], turns: [{ items: [
    { id: 'command', type: 'command_execution', input: 'npm test', status: 'completed', startedAt: stamp(-5000), completedAt: stamp(-3000), updatedAt: stamp(-3000) },
    { id: 'thought', type: 'reasoning', title: 'Deciding', status: 'completed', startedAt: stamp(-2000), completedAt: stamp(-1000) },
    { id: 'running', type: 'file_change', fileName: 'app.mjs', status: 'running', startedAt: stamp(-1000), updatedAt: stamp() },
    { id: 'question', requestId: 'req', type: 'user_input_request', status: 'waiting', questions: [{ id: 'q', header: 'Pick', question: 'Which?', options: [{ label: 'A', description: 'First' }], multiSelect: true }] },
  ] }] };
  const result = detail({ now: () => NOW }, machine, projection);
  assert.deepEqual(result.history.map(m => m.who), ['user', 'agent']); assert.equal(result.latest.text, 'Reply'); assert.equal(result.request.kind, 'multi'); assert.equal(result.request.questions[0].multi, true); assert.equal(result.runId, 'run');
  projection.thread.pendingRuntimeRequest = null;
  const work = detail({ now: () => NOW }, machine, projection).working;
  assert.equal(work.now.label, 'Editing files'); assert.equal(work.now.what, 'app.mjs'); assert.equal(work.now.lastOutputAt, stamp()); assert.deepEqual(work.recent, [{ label: 'Running command: npm test', durSec: 2 }]);
  projection.thread.pendingRuntimeRequest = { id: 'approve', kind: 'command', turnItemId: 'command', reason: 'Needs permission' };
  const approval = detail({ now: () => NOW }, machine, projection).request;
  assert.deepEqual(approval.approval, { kind: 'command', command: 'npm test', reason: 'Needs permission' });
});

test('T3 route shapes, offline degradation, working-only projections, and mutation schemas', async t => {
  FakeWebSocket.sent = [];
  const projections = new Map(); let boundedReads = 0;
  const shell = { projects: [{ id: 'p', title: 'Project' }], threads: [
    { id: 'work', title: 'Work', projectId: 'p', status: 'running', activeRunId: 'run', updatedAt: stamp(), modelSelection: { provider: 'claudeAgent' } },
    { id: 'input', title: 'Input', pendingRuntimeRequest: { id: 'req', kind: 'user_input' }, updatedAt: stamp(-1000) },
    { id: 'done', title: 'Done', updatedAt: stamp(-2000) },
    { id: 'ready', title: 'Ready', updatedAt: stamp(-3000), lastVisitedAt: stamp() },
    { id: 'settled', title: 'Settled', settledOverride: 'settled', updatedAt: stamp(-4000) },
  ] };
  for (const thread of shell.threads) projections.set(thread.id, { thread, messages: [{ role: 'assistant', text: 'Latest text', createdAt: stamp() }], turnItems: thread.id === 'work' ? [{ id: 'item', type: 'command_execution', input: 'node --test', status: 'running', startedAt: stamp(-1000), updatedAt: stamp() }] : thread.id === 'input' ? [{ id: 'q-item', requestId: 'req', type: 'user_input_request', status: 'waiting', questions: [{ id: 'q', question: 'Pick?', options: [{ label: 'A' }] }] }] : [] });
  const { call } = await app(t, async (url, opts) => {
    const u = new URL(url); if (u.host === 'offline.example') throw new Error('Offline');
    assert.equal(opts.headers['x-t3-orchestration-protocol'], '2');
    if (u.pathname.endsWith('/shell')) return json(shell);
    if (u.pathname.endsWith('/bounded')) { boundedReads++; return json(projections.get(decodeURIComponent(u.pathname.split('/').at(-2)))); }
    // Real T3 shape (GET /api/projects -> ProjectSnapshot {projects, updatedAt}); a bare-array fake hid the bug.
    if (u.pathname === '/api/projects') return json({ projects: [{ id: 'p', title: 'Project', defaultModelSelection: { provider: 'claudeAgent', instanceId: 'claude-shared', model: 'sonnet' }, archivedAt: null, deletedAt: null }, { id: 'old', title: 'Archived', archivedAt: stamp(-1000), deletedAt: null }], updatedAt: stamp() });
    if (u.pathname.endsWith('/websocket-ticket')) return json({ ticket: 'ticket' });
    if (u.pathname === '/.well-known/t3/environment') return json({ platform: { os: 'darwin' } });
    if (u.pathname === '/api/auth/session') return json({ authenticated: true, scopes: ['orchestration:read', 'orchestration:operate'] });
    throw new Error('Unexpected fake URL');
  }, { t3Machines: [machine, { ...machine, id: 'offline', alias: 'desktop', url: 'https://offline.example' }] });
  const list = await (await call('/t3/threads')).json();
  assert.equal(list.machines[1].online, false); assert.deepEqual(list.threads.map(t => t.status), ['working', 'input', 'done']); assert.equal(list.threads[0].project, 'Project'); assert.equal(list.threads[0].step.label, 'Running command'); assert.equal(list.threads[0].step.what, 'node --test'); assert.equal(boundedReads, 1);
  assert.deepEqual((await (await call('/t3/settled')).json()).map(t => t.id), ['ready', 'settled']);
  const input = await (await call('/t3/threads/mac/input')).json(); assert.equal(input.request.kind, 'choice'); assert.equal(input.latest.text, 'Latest text'); assert.equal(input.working, null);
  const send = (route, body) => call(route, { method: 'POST', body });
  assert.equal((await send('/t3/threads/mac/settled/messages', { text: 'Continue', mode: 'queue' })).status, 200);
  const commands = () => FakeWebSocket.sent.filter(f => f._tag === 'Request' && f.tag === 'orchestration.dispatchCommand').map(f => f.payload);
  assert.equal(commands()[0].type, 'thread.unsettle'); assert.equal(commands()[0].reason, 'user');
  let command = commands().at(-1); assert.equal(command.dispatchMode.type, 'queue_after_active'); assert.equal(command.creationSource, 'mobile'); assert.equal(command.createdBy, 'user'); assert.equal(command.text, 'Continue' + GLASSES_SUFFIX); assert.deepEqual(command.attachments, []);
  assert.equal((await send('/t3/threads/mac/work/messages', { text: 'Steer', mode: 'steer' })).status, 200);
  command = commands().at(-1); assert.deepEqual(command.dispatchMode, { type: 'steer_active', targetRunId: 'run' }); assert.equal(command.deliveryIntent, 'steer');
  assert.equal((await send('/t3/threads/mac/input/respond', { requestId: 'req', answers: { q: 'A' } })).status, 200);
  assert.equal(commands().at(-1).type, 'runtime-request.respond'); assert.deepEqual(commands().at(-1).answers, { q: 'A' });
  projections.get('input').thread.pendingRuntimeRequest.responseMode = 'message';
  assert.equal((await send('/t3/threads/mac/input/respond', { requestId: 'req', answers: { q: ['A', 'B'] } })).status, 200);
  assert.equal(commands().at(-1).type, 'message.dispatch'); assert.match(commands().at(-1).text, /^q: A, B/);
  assert.equal((await send('/t3/threads/mac/work/visit', {})).status, 200); assert.equal(commands().at(-1).visitedAt, stamp());
  assert.deepEqual(await (await call('/t3/projects')).json(), [{ machine: 'mac', machineLabel: 'Mac', id: 'p', title: 'Project', pinned: false, updatedAt: null }]);
  assert.deepEqual(await (await send('/t3/threads', { machine: 'mac', projectId: 'p', text: 'Build something small' })).json(), { machine: 'mac', id: 'new-thread' });
  const launch = FakeWebSocket.sent.findLast(f => f.tag === 'orchestration.launchThread').payload;
  // T3 allocates the id; reuseExistingThread would make T3 look for an existing empty thread and fail (2026-11-18).
  assert.equal(launch.threadId, undefined); assert.equal(launch.reuseExistingThread, undefined); assert.match(launch.commandId, /^[0-9a-f-]{36}$/); assert.equal(launch.projectId, 'p');
  assert.equal((await send('/t3/threads', { machine: 'mac', projectId: 'old', text: 'Archived' })).status, 404);
  assert.equal(launch.generateTitle, true); assert.ok(launch.initialMessage.text.endsWith(GLASSES_SUFFIX)); assert.equal(launch.runtimeMode, 'approval-required'); assert.deepEqual(launch.workspaceStrategy, { type: 'root' }); assert.equal(launch.modelSelection.model, 'sonnet');
  const usage = await (await call('/t3/usage')).json(); assert.equal(usage[0].windows[0].pace, 'on'); assert.equal(usage[0].credits[0].availableCount, 1);
  assert.deepEqual(await (await send('/t3/usage/reset', { machine: 'mac', instanceId: 'claude-shared', creditId: 'reset' })).json(), { ok: true });
  assert.deepEqual(FakeWebSocket.sent.findLast(f => f.tag === 'provider.consumeResetCredit').payload, { sourceId: 'anthropic', accountId: 'shared', creditId: 'reset' });
  const environments = await (await call('/t3/environments')).json(); assert.equal(environments[0].os, 'darwin'); assert.equal(environments[1].online, false);
  const machinesNow = (await (await call('/t3/threads')).json()).machines;
  assert.deepEqual(environments.map(e => [e.machine, e.online]), machinesNow.map(e => [e.machine, e.online])); assert.equal(machinesNow[0].os, 'darwin'); assert.equal(machinesNow[0].canWrite, true);
  const ids = commands().map(c => c.commandId); assert.equal(new Set(ids).size, ids.length); assert.ok(ids.every(id => /^[0-9a-f-]{36}$/.test(id)));
});

test('T3 usage pacing and fallback default model selection', () => {
  const windows = usageWindows({ windows: [20, 50, 80].map(usedPercent => ({ usedPercent, resetsAt: stamp(150 * 60000), windowDurationMins: 300 })) }, NOW);
  assert.deepEqual(windows.map(w => w.pace), ['under', 'on', 'ahead']);
  assert.equal(modelSelection({}, { providers: [provider] }).model, 'sonnet');
  assert.equal(modelSelection({}, { threadDefaults: { modelSelection: { model: 'default' } } }).model, 'default');
  assert.equal(modelSelection({}, { settings: { defaultModelSelection: { model: 'configured' } } }).model, 'configured');
});

test('T3 step kinds, text questions, message authors and approval decisions match the contract', () => {
  for (const [item, label] of [
    [{ type: 'command_execution', input: 'pwd' }, 'Running command: pwd'],
    [{ type: 'file_change', fileName: 'file.mjs' }, 'Editing files: file.mjs'],
    [{ type: 'web_search', patterns: ['test query'] }, 'Searching the web: test query'],
    [{ type: 'reasoning', title: 'Plan' }, 'Thinking: Plan'],
    [{ type: 'dynamic_tool', toolName: 'tool' }, 'Using tool'],
    [{ type: 'assistant_message' }, 'Writing'],
  ]) assert.equal([step(item).label, step(item).what].filter(Boolean).join(': '), label);
  const result = detail({ now: () => NOW }, machine, { thread: { id: 'text', pendingRuntimeRequest: { id: 'r', kind: 'user_input' } }, messages: [{ createdBy: 'user', text: 'Question' }, { createdBy: 'agent', text: 'Answer' }, { createdBy: 'system', text: 'Omit' }], turnItems: [{ id: 'q', requestId: 'r', type: 'user_input_request', questions: [{ id: 'text', header: 'Text', question: 'What text?' }] }] });
  assert.equal(result.request.kind, 'text'); assert.equal(result.request.questions[0].multi, false); assert.deepEqual(result.request.questions[0].options, []); assert.deepEqual(result.history.map(m => m.who), ['user', 'agent']);
});

test('Beeper approval heuristic excludes group chats, self and ordinary emoji messages', () => {
  const message = { id: 'm', senderName: 'Hermes', senderID: 'bot', text: 'Please approve ✅ or ❌' }, chat = { network: 'Discord', type: 'single' };
  assert.equal(mapMessage(message, chat, [], []).approval, true);
  assert.equal(mapMessage(message, { ...chat, type: 'group' }, [], []).approval, false);
  assert.equal(mapMessage({ ...message, isSender: true }, chat, [], []).approval, false);
  assert.equal(mapMessage({ ...message, text: 'I like ✅ and ❌' }, chat, [], []).approval, false);
});

test('T3 usage deduplicates account across machines and prefers freshest checkedAt', async () => {
  class UsageSocket {
    constructor(url) { this.host = new URL(url).host; queueMicrotask(() => this.onopen?.()); }
    send(raw) {
      const frame = JSON.parse(raw); if (frame._tag !== 'Request') return;
      const p = { ...provider, instanceId: this.host, usageLimits: { ...provider.usageLimits, checkedAt: this.host.startsWith('fresh') ? stamp() : stamp(-10000) } };
      queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ _tag: 'Exit', requestId: '1', exit: { _tag: 'Success', value: { providers: [p, { ...p, enabled: false, instanceId: 'disabled' }] } } }) }));
    }
    close() {}
  }
  const ctx = { config: { t3Machines: [machine, { ...machine, id: 'fresh', alias: 'fresh', url: 'https://fresh.example' }] }, fetch: async () => json({ ticket: 'ticket' }), secret: async () => 'secret', WebSocket: UsageSocket, now: () => NOW };
  const result = await usage(ctx); assert.equal(result.length, 1); assert.equal(result[0].machine, 'fresh');
});

test('T3 reports rejected credentials as needing re-pair without credential renewal', async t => {
  let writes = 0;
  const { call } = await app(t, async (_url, opts) => { if (opts.method !== 'GET') writes++; return json({ error: 'private-secret' }, 401); }, { t3Machines: [machine] });
  const list = await (await call('/t3/threads')).json(); assert.equal(list.machines[0].needsRepair, true); assert.equal(list.machines[0].online, false); assert.equal(writes, 0);
  assert.equal((await (await call('/t3/environments')).json())[0].needsRepair, true);
});

test('T3 RPC timeout closes socket and does not retry a mutation', async () => {
  let sent = 0, closed = 0;
  class SilentSocket { constructor() { queueMicrotask(() => this.onopen?.()); } send() { sent++; } close() { closed++; } }
  const ctx = { fetch: async () => json({ ticket: 'ticket' }), secret: async () => 'secret', WebSocket: SilentSocket };
  await assert.rejects(rpc(ctx, machine, 'orchestration.dispatchCommand', {}, 20), /inspect state/);
  assert.equal(sent, 1); assert.equal(closed, 1);
});

test('home returns complete fallback shape when every upstream fails', async t => {
  const { call } = await app(t);
  const home = await (await call('/home')).json();
  assert.deepEqual(home, { weather: null, beeper: { unread: 0, names: [] }, t3: { needYou: 0, done: 0 }, nextEvent: null, nowPlaying: null });
});

test('home enforces a common six-second budget with hung optional upstreams', async t => {
  const { call } = await app(t, async () => new Promise(() => {}));
  const start = performance.now();
  const result = await (await call('/home')).json();
  const elapsed = performance.now() - start;
  assert.ok(elapsed >= 5900 && elapsed < 7500, `${elapsed} ms`);
  assert.deepEqual(result.beeper, { unread: 0, names: [] }); assert.equal(result.weather, null); assert.equal(result.nowPlaying, null);
});
