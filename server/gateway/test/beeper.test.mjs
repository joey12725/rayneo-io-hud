import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once, EventEmitter } from 'node:events';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { createEventBus } from '../apis/glasses/lib/context.mjs';
import { unreadSummary, counts, resolveChat, isSpam, SPAM, publishBeeper } from '../apis/glasses/lib/beeper.mjs';
import { spamCandidate, validVerdict, classifyChat, needsVerdict, createSpamQueue, basisOf } from '../apis/glasses/lib/beeper-spam.mjs';
import { createBeeperLive } from '../apis/glasses/lib/beeper-live.mjs';

const NOW = Date.parse('2026-11-17T18:00:00Z');
const stamp = (delta = 0) => new Date(NOW + delta).toISOString();
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const ACCOUNTS = [{ accountID: 'ig', network: 'Instagram' }, { accountID: 'sms', network: 'Google Messages' }, { accountID: 'dc', network: 'Discord' }];
const chat = (id, extra = {}) => ({ id, title: id, accountID: 'ig', type: 'single', unreadCount: 0, lastActivity: stamp(-Number(id.replace(/\D/g, '') || 0) * 60000), participants: { items: [{ id: 'me', isSelf: true }, { id: 'p-' + id, fullName: 'Person ' + id }], total: 2 }, preview: { id: 'pv-' + id, senderID: 'p-' + id, text: 'hello from ' + id }, ...extra });

/** Fake upstream: Beeper Desktop API + model proxy. Records every request so tests can pin "no upstream writes". */
function upstream(rows, { model = () => ({ spam: true, confidence: 0.97, category: 'marketing', reason: 'promo link' }), messages = () => [{ id: 'm1', text: 'SALE 50% off reply STOP', timestamp: stamp() }] } = {}) {
  const log = [];
  const fetch = async (url, opts = {}) => {
    const u = new URL(url), method = opts.method ?? 'GET';
    log.push({ method, path: u.pathname });
    if (u.pathname.endsWith('/chat/completions')) return json({ choices: [{ message: { content: JSON.stringify(model(JSON.parse(opts.body))) } }] });
    if (method !== 'GET') return json({ success: true });
    if (u.pathname === '/v1/accounts') return json(ACCOUNTS);
    if (u.pathname === '/v1/chats') return json({ items: rows(), hasMore: false });
    if (u.pathname.endsWith('/messages')) return json({ items: messages(u.pathname) });
    return json(rows().find(c => '/v1/chats/' + encodeURIComponent(c.id) === u.pathname) ?? {});
  };
  return { fetch, log, writes: () => log.filter(r => r.method !== 'GET' && !r.path.endsWith('/chat/completions')) };
}

async function context(t, fake, config = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'beeper-'));
  const store = createStore(dir, { now: () => NOW });
  t.after(async () => { await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  return { store, events: createEventBus(store, { now: () => NOW }), now: () => NOW, fetch: fake.fetch, secret: async () => 'fixture', log: () => {},
    config: { beeperUrl: 'http://beeper', proxyUrl: 'http://proxy/v1', models: { fast: 'fast' }, spamThreshold: 0.9, ...config } };
}

async function app(t, fake, config = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'beeper-app-'));
  const store = createStore(dir, { now: () => NOW });
  const result = await createApp({ env: {}, log: () => {}, store, secrets: { get: async name => name === 'GLASSES_API_TOKEN' ? 'phone' : 'fixture' }, deps: { glasses: { fetch: fake.fetch, now: () => NOW, config: { t3Machines: [], ...config } } } });
  result.server.listen(0, '127.0.0.1'); await once(result.server, 'listening');
  t.after(async () => { result.server.closeAllConnections(); await new Promise(resolve => result.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const call = (route, { method = 'GET', body } = {}) => fetch(`http://127.0.0.1:${result.server.address().port}/glasses/v1${route}`, { method, headers: { Authorization: 'Bearer phone', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  return { call, store };
}

test('summary counts unread CONVERSATIONS, excluding muted (unless @mentioned), low priority and confident spam', async t => {
  const rows = [
    chat('c1', { unreadCount: 39, isMuted: true, type: 'group' }),                 // muted: quiet
    chat('c2', { unreadCount: 2, isMuted: true, unreadMentionsCount: 1 }),         // muted but mentions Robin: counts
    chat('c3', { unreadCount: 16, type: 'group' }),                                // counts
    chat('c4', { unreadCount: 0, isMarkedUnread: true }),                          // marked unread: counts
    chat('c5', { unreadCount: 7, isLowPriority: true }),                           // low priority: not counted
    chat('c6', { unreadCount: 1, accountID: 'sms', title: '62881' }),              // spam 0.95: quiet
    chat('c7', { unreadCount: 1, accountID: 'sms', title: '+1 312 555 0100' }),   // spam 0.89: below threshold, counts
    chat('c8', { unreadCount: 3 }),                                                // user marked spam: quiet
    chat('c9', { unreadCount: 0 }),
    chat('c10', { unreadCount: 4, isArchived: true }),
  ];
  const fake = upstream(() => rows), ctx = await context(t, fake);
  ctx.store.put(SPAM, { id: 'c6', verdict: 'spam', confidence: 0.95, source: 'model' });
  ctx.store.put(SPAM, { id: 'c7', verdict: 'spam', confidence: 0.89, source: 'model' });
  ctx.store.put(SPAM, { id: 'c8', verdict: 'spam', confidence: 1, source: 'user' });
  const summary = await unreadSummary(ctx);
  assert.deepEqual(summary, { unread: 4, unreadChats: 4, unreadMessages: 2 + 16 + 0 + 1, names: ['c2', 'c3', 'c4', '+1 312 555 0100'], quiet: { muted: 1, spam: 2 }, total: 9 });
  assert.equal(isSpam(ctx, rows[5]), true); assert.equal(isSpam(ctx, rows[6]), false);
  assert.equal(counts({ ...ctx, config: { ...ctx.config, spamThreshold: 0.85 } }, rows[6]), false); // the threshold is a setting
  assert.deepEqual(fake.writes(), []);
});

test('chat list (order=recent): counting chats first, muted/spam keep recency, 100 by default, offset pages, additive row fields', async t => {
  const rows = Array.from({ length: 120 }, (_, i) => chat('c' + i, i === 5 ? { unreadCount: 39, isMuted: true, type: 'group' } : i === 50 ? { unreadCount: 2 } : i === 7 ? { unreadCount: 1, isLowPriority: true } : {}));
  const fake = upstream(() => rows), { call } = await app(t, fake);
  const list = await (await call('/beeper/chats?order=recent')).json();
  assert.equal(list.length, 100);
  assert.equal(list[0].id, 'c50'); assert.equal(list[0].counts, true);
  const muted = list.findIndex(c => c.id === 'c5');
  assert.equal(muted, 6); // not hoisted: c0..c4 then c5 (after the one counting chat)
  assert.deepEqual([list[muted].muted, list[muted].counts, list[muted].unread, list[muted].isGroup, list[muted].type], [true, false, 39, true, 'group']);
  const low = list.find(c => c.id === 'c7'); assert.equal(low.lowPriority, true); assert.equal(low.counts, false);
  assert.deepEqual(list[1].participants, { count: 2, names: ['Person c0'] });
  const next = await (await call('/beeper/chats?order=recent&limit=100&offset=100')).json();
  assert.equal(next.length, 20); assert.equal(new Set([...list, ...next].map(c => c.id)).size, 120);
  assert.equal((await (await call('/beeper/chats?limit=500')).json()).length, 120);
  assert.deepEqual(fake.writes(), []);
});

test('resolve maps a Beeper phone notification to a chat with mute/spam rules and a deep link', async t => {
  const rows = [
    chat('c1', { title: 'Sam', preview: { id: 'p1', text: 'you coming tonight?' }, accountID: 'ig' }),
    chat('c2', { title: 'Sam', preview: { id: 'p2', text: 'different text' }, accountID: 'sms', lastActivity: stamp(-3 * 3600000) }),
    chat('c3', { title: 'the boys', type: 'group', isMuted: true, unreadCount: 3, accountID: 'ig' }),
    chat('c4', { title: '62881', accountID: 'sms', unreadCount: 1 }),
    chat('c5', { title: '55555', accountID: 'sms', unreadCount: 1 }),
    chat('c6', { title: 'Twin', lastActivity: stamp(-3600000) }), chat('c7', { title: 'Twin', lastActivity: stamp(-7200000) }),
  ];
  const fake = upstream(() => rows), { call, store } = await app(t, fake);
  store.put(SPAM, { id: 'c4', verdict: 'spam', confidence: 0.96, source: 'model', category: 'marketing' });
  store.put(SPAM, { id: 'c5', verdict: 'ham', confidence: 0.9, source: 'model', category: 'otp' });
  const resolve = async body => (await call('/beeper/resolve', { method: 'POST', body })).json();
  const dm = await resolve({ title: 'Sam', sender: 'Sam', text: 'you coming tonight?' });
  assert.deepEqual([dm.resolved, dm.chatId, dm.network, dm.isGroup, dm.muted, dm.notify, dm.match], [true, 'c1', 'Instagram', false, false, true, 'preview']);
  assert.deepEqual(dm.open, { app: 'beeper', target: 'c1' }); assert.equal(dm.spam.spam, false);
  assert.equal((await resolve({ title: 'Sam', text: 'nope', network: 'Google Messages' })).chatId, 'c2');
  const group = await resolve({ conversationTitle: 'The Boys', sender: 'Ann', text: 'lol', isGroupConversation: true });
  assert.deepEqual([group.chatId, group.muted, group.notify, group.reason], ['c3', true, false, 'muted']);
  const spam = await resolve({ title: '62881', text: 'x' });
  assert.deepEqual([spam.chatId, spam.notify, spam.reason, spam.spam.spam, spam.spam.confidence], ['c4', false, 'spam', true, 0.96]);
  const otp = await resolve({ title: '55555', text: 'Your code is 123456' });
  assert.deepEqual([otp.notify, otp.importance, otp.lowImportance, otp.reason], [true, 0.3, true, 'otp']);
  assert.deepEqual(await resolve({ title: 'Twin', text: 'hm' }), { resolved: false, reason: 'ambiguous', candidates: 2 });
  assert.deepEqual(await resolve({ title: 'Nobody' }), { resolved: false, reason: 'no match', candidates: 0 });
  assert.equal((await call('/beeper/resolve', { method: 'POST', body: [] })).status, 400);
  assert.deepEqual(fake.writes(), []);
});

test('resolve is a library function too and fails open when Beeper is down', async t => {
  const ctx = await context(t, { fetch: async () => { throw new Error('down'); } });
  await assert.rejects(resolveChat(ctx, { title: 'Sam' }));
  const { call } = await app(t, { fetch: async () => { throw new Error('down'); } });
  assert.deepEqual(await (await call('/beeper/resolve', { method: 'POST', body: { title: 'Sam' } })).json(), { resolved: false, reason: 'unavailable', candidates: 0 });
});

test('user spam override is stored in the gateway only, publishes a beeper event, and can be cleared', async t => {
  const rows = [chat('c1', { unreadCount: 2, accountID: 'sms', title: '62881' })];
  const fake = upstream(() => rows), { call, store } = await app(t, fake);
  assert.equal((await (await call('/beeper/summary')).json()).unread, 1);
  assert.equal((await call('/beeper/chats/c1/spam', { method: 'POST', body: { spam: 'yes' } })).status, 400);
  const marked = await (await call('/beeper/chats/c1/spam', { method: 'POST', body: { spam: true } })).json();
  assert.equal(marked.ok, true); assert.equal(store.get(SPAM, 'c1').source, 'user');
  const [row] = await (await call('/beeper/chats')).json();
  assert.deepEqual([row.spam, row.counts, row.spamSource, row.unread], [true, false, 'user', 2]); // still listed and readable
  assert.equal((await (await call('/beeper/summary')).json()).unread, 0);
  assert.equal((await (await call('/beeper/spam')).json())[0].id, 'c1');
  const events = await (await call('/events?since=0&wait=0')).json();
  assert.deepEqual(events.events.filter(e => e.type === 'beeper').map(e => e.chatIds), [['c1']]);
  await call('/beeper/chats/c1/spam', { method: 'POST', body: { spam: false } });
  assert.equal((await (await call('/beeper/summary')).json()).unread, 1);
  await call('/beeper/chats/c1/spam', { method: 'DELETE' });
  assert.equal(store.get(SPAM, 'c1'), null);
  assert.deepEqual(fake.writes(), []); // nothing is ever written upstream to Beeper for spam
});

test('spam candidates: single chats on phone networks titled by a number or short code only', () => {
  const cases = [
    [{ id: 'a', type: 'single', accountID: 'sms', title: '62881' }, true],
    [{ id: 'b', type: 'single', accountID: 'sms', title: '+1 (555) 555-0100' }, true],
    [{ id: 'c', type: 'single', accountID: 'sms', title: 'Mom' }, false],
    [{ id: 'd', type: 'group', accountID: 'sms', title: '62881' }, false],
    [{ id: 'e', type: 'single', accountID: 'ig', title: '62881' }, false],
    [{ id: 'f', type: 'single', accountID: 'sms', title: '12' }, false],
  ];
  for (const [c, expected] of cases) assert.equal(spamCandidate(c, ACCOUNTS), expected, c.id);
});

test('model output is validated strictly; OTP/transactional are never spam', () => {
  assert.equal(validVerdict(null), null); assert.equal(validVerdict({ spam: 'yes', confidence: 1 }), null);
  assert.equal(validVerdict({ spam: true, confidence: 1.2 }), null); assert.equal(validVerdict({ spam: true, confidence: 0.9, extra: 1 }), null);
  assert.deepEqual(validVerdict({ spam: true, confidence: 0.99, category: 'otp', reason: 'code' }), { verdict: 'ham', confidence: 0.99, category: 'otp', reason: 'code' });
  assert.deepEqual(validVerdict({ spam: true, confidence: 0.95, category: 'weird' }), { verdict: 'spam', confidence: 0.95, category: 'unknown', reason: '' });
});

test('classification: the classifier verdict stored, replies force ham, garbage stores nothing, user override wins', async t => {
  let output = { spam: true, confidence: 0.97, category: 'marketing', reason: 'promo' }, sent = [{ id: 'm1', text: 'SALE', timestamp: stamp() }], prompts = [];
  const row = chat('c1', { unreadCount: 1, accountID: 'sms', title: '62881' });
  const fake = upstream(() => [row], { model: body => { prompts.push(body); return output; }, messages: () => sent });
  const ctx = await context(t, fake);
  const verdict = await classifyChat(ctx, row, ACCOUNTS);
  assert.deepEqual([verdict.verdict, verdict.confidence, verdict.source, verdict.basis, verdict.network], ['spam', 0.97, 'model', basisOf(row), 'Google Messages']);
  assert.match(prompts[0].messages[0].content, /untrusted/); assert.equal(JSON.parse(prompts[0].messages[1].content).isShortCode, true);
  assert.equal(needsVerdict(ctx, row), false); // confident spam is not re-run for the same or later messages
  assert.equal(needsVerdict(ctx, { ...row, preview: { id: 'newer' } }), false);
  ctx.store.put(SPAM, { ...verdict, verdict: 'ham', confidence: 0.6 });
  assert.equal(needsVerdict(ctx, { ...row, preview: { id: 'newer' } }), true);
  ctx.store.del(SPAM, 'c1'); output = 'garbage';
  assert.equal(await classifyChat(ctx, row, ACCOUNTS), null); assert.equal(ctx.store.get(SPAM, 'c1'), null);
  sent = [{ id: 'm1', text: 'hi', timestamp: stamp(-1000) }, { id: 'm2', text: 'who is this', isSender: true, timestamp: stamp() }];
  assert.deepEqual([(await classifyChat(ctx, row, ACCOUNTS)).verdict, ctx.store.get(SPAM, 'c1').source], ['ham', 'rule']);
  ctx.store.put(SPAM, { id: 'c1', verdict: 'ham', confidence: 1, source: 'user' });
  assert.equal(needsVerdict(ctx, row), false);
  assert.deepEqual(fake.writes(), []);
});

test('background queue: only when enabled, single flight, rate limited, publishes when a verdict crosses the threshold, replies clear spam', async t => {
  const rows = [chat('c1', { unreadCount: 1, accountID: 'sms', title: '62881' }), chat('c2', { unreadCount: 1, accountID: 'sms', title: '62882' }), chat('c3', { unreadCount: 1, accountID: 'sms', title: 'Mom' })];
  let calls = 0;
  const fake = upstream(() => rows, { model: () => { calls++; return { spam: true, confidence: 0.95, category: 'marketing', reason: 'promo' }; } });
  const off = createSpamQueue(await context(t, fake));
  assert.equal(off.offer(rows[0], ACCOUNTS), false);
  const ctx = await context(t, fake, { beeperSpam: true });
  const changed = [], timers = [];
  const queue = createSpamQueue(ctx, { onChange: ids => changed.push(...ids), perMinute: 1, setTimer: (fn, ms) => { timers.push(ms); return { unref() {} }; } });
  assert.equal(queue.offer(rows[2], ACCOUNTS), false); // named contact
  assert.equal(queue.offer(rows[0], ACCOUNTS), true); assert.equal(queue.offer(rows[1], ACCOUNTS), true);
  for (let i = 0; i < 50 && !changed.length; i++) await wait(5);
  await wait(10);
  assert.deepEqual(changed, ['c1']); assert.equal(calls, 1); assert.deepEqual(queue.pending, ['c2']); assert.equal(timers.length, 1); // second waits for the rate window
  assert.equal(queue.onMessages('c1', [{ id: 'x', isSender: true }]), true);
  assert.equal(ctx.store.get(SPAM, 'c1').verdict, 'ham'); assert.deepEqual(changed, ['c1', 'c1']);
  queue.stop();
  assert.deepEqual(fake.writes(), []);
});

class FakeSocket extends EventEmitter {
  sent = []; closed = false;
  send(value) { this.sent.push(JSON.parse(value)); }
  ping() {}
  close() { this.closed = true; this.emit('close', 1000); }
}

test('live: subscribes on ready, debounces a burst into one beeper event, reconciles on reconnect, rate-limits', async t => {
  const rows = [chat('c1', { unreadCount: 1 })];
  const fake = upstream(() => rows), ctx = await context(t, fake);
  const published = [], publish = ctx.events.publish;
  ctx.events.publish = (type, data, options) => { if (type === 'beeper') published.push(data); return publish(type, data, options); };
  const sockets = [], urls = [], seen = [];
  const live = createBeeperLive(ctx, { debounceMs: 20, maxWaitMs: 60, minGapMs: 80, onMessages: (id, entries) => seen.push([id, entries.length]),
    connect: async (url, options) => { urls.push([url, options.headers.Authorization]); const s = new FakeSocket(); sockets.push(s); return s; } });
  t.after(() => live.stop());
  live.start();
  for (let i = 0; i < 50 && !sockets.length; i++) await wait(2);
  assert.deepEqual(urls[0], ['ws://beeper/v1/ws', 'Bearer fixture']);
  sockets[0].emit('message', Buffer.from(JSON.stringify({ type: 'ready', version: 1, chatIDs: [] })), false);
  assert.deepEqual(sockets[0].sent, [{ type: 'subscriptions.set', chatIDs: ['*'] }]);
  await wait(60);
  const beeperEvents = () => published;
  assert.equal(beeperEvents().length, 1); assert.deepEqual(beeperEvents()[0].chatIds, []); // reconcile after (re)connect
  assert.deepEqual(beeperEvents()[0].summary, { unread: 1, unreadMessages: 1, names: ['c1'] });
  for (let i = 0; i < 5; i++) { sockets[0].emit('message', Buffer.from(JSON.stringify({ type: 'message.upserted', chatID: 'c1', ids: [String(i)], entries: [{ id: String(i) }], seq: i, ts: NOW })), false); await wait(5); }
  await wait(150);
  assert.equal(beeperEvents().length, 2); assert.deepEqual(beeperEvents()[1].chatIds, ['c1']);
  assert.equal(seen.length, 5);
  // chat.upserted noise with no summary change publishes nothing.
  sockets[0].emit('message', Buffer.from(JSON.stringify({ type: 'chat.upserted', chatID: 'c1', ids: ['c1'], seq: 9, ts: NOW })), false);
  await wait(150);
  assert.equal(beeperEvents().length, 2);
  sockets[0].emit('close', 1006);
  for (let i = 0; i < 400 && sockets.length < 2; i++) await wait(5); // 1 s backoff
  assert.equal(sockets.length, 2);
  sockets[1].emit('message', Buffer.from(JSON.stringify({ type: 'ready' })), false);
  await wait(150);
  assert.equal(beeperEvents().length, 3); assert.deepEqual(beeperEvents()[2].chatIds, []);
  // Only the newest refresh hint stays in the capped event log.
  assert.equal(ctx.events.since(0).filter(e => e.type === 'beeper').length, 1);
  assert.deepEqual(fake.writes(), []);
});

test('beeper refresh hints supersede each other in the event log and never evict other events', async t => {
  const ctx = await context(t, upstream(() => []));
  ctx.events.publish('doc', { id: 'd1', title: 'Doc' });
  for (let i = 0; i < 600; i++) publishBeeper(ctx, { chatIds: ['c' + (i % 3)] });
  const all = ctx.events.since(0);
  assert.deepEqual(all.map(e => e.type), ['doc', 'beeper']);
  assert.deepEqual([...all[1].chatIds].sort(), ['c0', 'c1', 'c2']); // recent ids are folded into the newest hint
  publishBeeper(ctx, { chatIds: [] }); // a reconcile stays "every open thread"
  assert.deepEqual(ctx.events.since(all[1].seq).map(e => e.chatIds), [[]]);
  assert.equal(ctx.events.since(0).length, 2);
});

test('background queue: a chat whose classification stored nothing is not retried until a new message', async t => {
  const rows = [chat('c1', { unreadCount: 1, accountID: 'sms', title: '62881' })];
  let calls = 0;
  const fake = upstream(() => rows, { model: () => { calls++; return { verdict: 'nonsense' }; } });
  const ctx = await context(t, fake, { beeperSpam: true });
  const queue = createSpamQueue(ctx);
  t.after(() => queue.stop());
  assert.equal(queue.offer(rows[0], ACCOUNTS), true);
  for (let i = 0; i < 100 && (calls < 1 || queue.running); i++) await wait(5);
  assert.equal(calls, 1); assert.equal(ctx.store.get(SPAM, 'c1'), null);
  assert.equal(queue.offer(rows[0], ACCOUNTS), false); // same latest message: backed off
  rows[0] = { ...rows[0], preview: { ...rows[0].preview, id: 'pv-new' } };
  assert.equal(queue.offer(rows[0], ACCOUNTS), true); // a new message gets a fresh attempt
  for (let i = 0; i < 100 && (calls < 2 || queue.running); i++) await wait(5);
  assert.equal(calls, 2);
  assert.deepEqual(fake.writes(), []);
});
