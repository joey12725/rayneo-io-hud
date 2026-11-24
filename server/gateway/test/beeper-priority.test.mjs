import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { SPAM } from '../apis/glasses/lib/beeper.mjs';
import { importance, historySignals, prioritize, scoreUnread, nameScore, cleanQuery, findChats, confident, beeperPinned, order, PINS, IMPORTANT, asksQuestion }
  from '../apis/glasses/lib/beeper-priority.mjs';

const NOW = Date.parse('2026-11-17T18:00:00Z');
const stamp = (delta = 0) => new Date(NOW + delta).toISOString();
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const ACCOUNTS = [{ accountID: 'ig', network: 'Instagram' }, { accountID: 'sms', network: 'Google Messages' }, { accountID: 'dc', network: 'Discord' }];
// Synthetic chats only: no real names or message text.
const chat = (id, minutesAgo, extra = {}) => ({ id, title: id, accountID: 'ig', type: 'single', unreadCount: 0, lastActivity: stamp(-minutesAgo * 60000),
  participants: { items: [{ id: 'me', isSelf: true }, { id: 'p-' + id, fullName: 'Person ' + id }], total: 2 }, preview: { id: 'pv-' + id, senderID: 'p-' + id, text: 'hello' }, ...extra });
const group = (id, minutesAgo, extra = {}) => chat(id, minutesAgo, { type: 'group', participants: { items: [{ id: 'me', isSelf: true }, { id: 'a-' + id, fullName: 'Avery Stone' }, { id: 'b-' + id, fullName: 'Blake Moss' }], total: 3 }, ...extra });

function upstream(rows, { messages = () => [], delayMs = 0 } = {}) {
  const log = [];
  const fetch = async (url, opts = {}) => {
    const u = new URL(url), method = opts.method ?? 'GET';
    log.push({ method, path: u.pathname });
    if (method !== 'GET') return json({ success: true });
    if (u.pathname === '/v1/accounts') return json(ACCOUNTS);
    if (u.pathname === '/v1/chats') return json({ items: rows(), hasMore: false });
    if (u.pathname.endsWith('/messages')) {
      if (delayMs) await new Promise(resolve => setTimeout(resolve, delayMs));
      return json({ items: messages(decodeURIComponent(u.pathname.split('/')[3])) });
    }
    return json(rows().find(c => '/v1/chats/' + encodeURIComponent(c.id) === u.pathname) ?? {});
  };
  return { fetch, log, writes: () => log.filter(r => r.method !== 'GET') };
}
async function app(t, fake) {
  const dir = await mkdtemp(path.join(tmpdir(), 'beeper-pri-'));
  const store = createStore(dir, { now: () => NOW });
  const result = await createApp({ env: {}, log: () => {}, store, secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone', GLASSES_AGENT_TOKEN: 'joy' }[name] ?? 'fixture') }, deps: { glasses: { fetch: fake.fetch, now: () => NOW, config: { t3Machines: [] } } } });
  result.server.listen(0, '127.0.0.1'); await once(result.server, 'listening');
  t.after(async () => { result.server.closeAllConnections(); await new Promise(resolve => result.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const call = (route, { method = 'GET', body, token = 'phone' } = {}) => fetch(`http://127.0.0.1:${result.server.address().port}/glasses/v1${route}`, { method, headers: { Authorization: 'Bearer ' + token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  return { call, store };
}
const ctxFor = (store = null) => ({ now: () => NOW, store, config: {} });

test('importance: mentions, a question to Robin in a DM, a direct person; short codes and groups score less', () => {
  assert.deepEqual(importance(chat('dm', 1, { unreadCount: 1, preview: { senderID: 'p-dm', text: 'are you free tonight?' } })), { score: 0.5, why: ['question', 'direct'] });
  assert.deepEqual(importance(chat('dm', 1, { unreadCount: 1 })), { score: 0.2, why: ['direct'] });
  assert.deepEqual(importance(group('g', 1, { unreadCount: 4, unreadMentionsCount: 1 })), { score: 0.5, why: ['mention'] });
  assert.deepEqual(importance(group('g', 1, { unreadCount: 4, preview: { senderID: 'a-g', text: 'who is coming?' } })), { score: 0.1, why: ['question'] });
  assert.deepEqual(importance(chat('62881', 1, { title: '62881', accountID: 'sms', unreadCount: 1, preview: { text: 'Reply YES?' } })), { score: 0.3, why: ['question'] });
  // Robin's own question is not a question to him.
  assert.deepEqual(importance(chat('dm', 1, { preview: { senderID: 'me', isSender: true, text: 'you there?' } })).why, ['direct']);
  assert.equal(asksQuestion('can you grab the speaker'), true);
  assert.equal(asksQuestion('see you soon'), false);
});

test('history signals: frequent and quick replies make an unread DM important', () => {
  const c = chat('dm', 1, { unreadCount: 1 });
  const at = m => stamp(-m * 60000);
  const msgs = [
    { senderID: 'p-dm', timestamp: at(300) }, { senderID: 'me', isSender: true, timestamp: at(296) },
    { senderID: 'p-dm', timestamp: at(200) }, { senderID: 'me', isSender: true, timestamp: at(190) },
    { senderID: 'p-dm', timestamp: at(100) }, { senderID: 'me', isSender: true, timestamp: at(98) },
    { senderID: 'p-dm', timestamp: at(1) },
  ];
  const s = historySignals(msgs, c, NOW);
  assert.deepEqual(s, { sent: 3, recent: true, replyMs: 4 * 60000, replies: 3 });
  assert.deepEqual(importance(c, s), { score: 0.6, why: ['direct', 'frequent', 'quick'] });
  assert.ok(importance(c, s).score >= IMPORTANT);
  // Old conversations do not count as frequent; slow replies are not quick.
  const old = historySignals(msgs.map(m => ({ ...m, timestamp: new Date(Date.parse(m.timestamp) - 30 * 86400000).toISOString() })), c, NOW);
  assert.equal(old.recent, false);
  assert.deepEqual(importance(c, { sent: 5, recent: true, replyMs: 3 * 3600000, replies: 4 }).why, ['direct', 'frequent']);
});

test('priority order: pins (Beeper pin, favourite label, glasses pin), important, unread, quiet, then read by recency', async t => {
  const rows = [
    chat('read-new', 1),
    chat('unread-dm', 2, { unreadCount: 1 }),
    group('unread-group', 3, { unreadCount: 6 }),
    chat('question-dm', 9, { unreadCount: 2, preview: { senderID: 'p-question-dm', text: 'are you coming?' } }),
    group('mention-group', 20, { unreadCount: 3, unreadMentionsCount: 1 }),
    group('muted-group', 4, { unreadCount: 39, isMuted: true }),
    chat('62881', 5, { title: '62881', accountID: 'sms', unreadCount: 1 }),
    chat('low', 6, { unreadCount: 7, isLowPriority: true, isMuted: true }),
    chat('beeper-pin', 300, { isPinned: true }),
    chat('fav', 200, { labels: [{ id: 'l1', name: 'Favorites' }] }),
    chat('glasses-pin', 400),
    chat('read-old', 500),
    chat('archived', 0, { isArchived: true, unreadCount: 3 }),
  ];
  const fake = upstream(() => rows), { call, store } = await app(t, fake);
  store.put(SPAM, { id: '62881', verdict: 'spam', confidence: 0.97, source: 'model', category: 'marketing' });
  const pin = await call('/beeper/chats/glasses-pin/pin', { method: 'POST', body: { pinned: true } });
  assert.deepEqual(await pin.json(), { ok: true, id: 'glasses-pin', pinned: true });
  const list = await (await call('/beeper/chats')).json();
  assert.deepEqual(list.map(c => c.id), ['fav', 'beeper-pin', 'glasses-pin', 'question-dm', 'mention-group', 'unread-dm', 'unread-group', 'muted-group', '62881', 'low', 'read-new', 'read-old']);
  assert.deepEqual(list.map(c => c.priority), ['pinned', 'pinned', 'pinned', 'important', 'important', 'unread', 'unread', 'quiet', 'quiet', 'quiet', 'read', 'read']);
  const byId = Object.fromEntries(list.map(c => [c.id, c]));
  assert.equal(byId['beeper-pin'].pinned, true); assert.equal(byId.fav.pinned, true); assert.equal(byId['glasses-pin'].glassesPinned, true);
  assert.equal(byId['glasses-pin'].pinned, false);
  assert.deepEqual(byId['question-dm'].why, ['question', 'direct']);
  assert.equal(byId['question-dm'].importance, 0.5);
  assert.equal(byId['62881'].spam, true); assert.equal(byId['62881'].counts, false);
  // Recent keeps the BEEP-01/02 order behind the pins: counting unread chats, then everything else by recency.
  const historyReads = () => fake.log.filter(r => r.path.endsWith('/messages')).length;
  const before = historyReads();
  const recent = await (await call('/beeper/chats?order=recent')).json();
  assert.equal(historyReads(), before, 'order=recent reads no message history');
  assert.ok(recent.every(c => c.importance === 0));
  assert.deepEqual(recent.map(c => c.id), ['fav', 'beeper-pin', 'glasses-pin', 'unread-dm', 'unread-group', 'question-dm', 'mention-group', 'read-new', 'muted-group', '62881', 'low', 'read-old']);
  // The badge is unchanged: unread conversations without muted or spam ones.
  assert.equal((await (await call('/beeper/summary')).json()).unread, 4);
  // Pins are a gateway record only: the one upstream write is never a PATCH and none was made.
  assert.deepEqual(fake.writes(), []);
});

test('message history lifts a DM Robin answers quickly; a slow Beeper never holds the list past the budget', async t => {
  const rows = [chat('a', 1, { unreadCount: 1 }), chat('b', 5, { unreadCount: 1 })];
  const at = m => stamp(-m * 60000);
  const history = id => id !== 'b' ? [] : [
    { id: '1', senderID: 'p-b', timestamp: at(60) }, { id: '2', senderID: 'me', isSender: true, timestamp: at(58) },
    { id: '3', senderID: 'p-b', timestamp: at(40) }, { id: '4', senderID: 'me', isSender: true, timestamp: at(39) },
    { id: '5', senderID: 'p-b', timestamp: at(20) }, { id: '6', senderID: 'me', isSender: true, timestamp: at(19) },
  ];
  const fake = upstream(() => rows, { messages: history }), { call } = await app(t, fake);
  const list = await (await call('/beeper/chats')).json();
  assert.deepEqual(list.map(c => [c.id, c.priority]), [['b', 'important'], ['a', 'unread']]);
  assert.deepEqual(list[0].why, ['direct', 'frequent', 'quick']);

  const slow = upstream(() => rows, { messages: history, delayMs: 400 });
  const ctx = { ...ctxFor(), fetch: slow.fetch, secret: async () => 'fixture', config: { beeperUrl: 'http://beeper' } };
  const started = Date.now();
  const scores = await scoreUnread(ctx, rows, { budgetMs: 50 });
  assert.ok(Date.now() - started < 300, 'scores return at the budget');
  assert.deepEqual([...scores.keys()], ['a', 'b']);
  assert.equal(scores.get('b').score, 0.2); // no history in time: scored on the chat row alone
  // The late lookup finishes in the background (warming the cache) but never rewrites the scores already returned.
  await new Promise(resolve => setTimeout(resolve, 500));
  assert.equal(scores.get('b').score, 0.2);
});

test('prioritize is pure over chats and scores; order() falls back to priority', () => {
  const ctx = ctxFor();
  const rows = [chat('x', 1), chat('y', 2, { unreadCount: 1 }), chat('z', 3, { unreadCount: 1 })];
  const scores = new Map([['z', { score: 0.9, why: ['mention'] }], ['y', { score: 0.1, why: [] }]]);
  assert.deepEqual(prioritize(ctx, rows, scores).map(r => r.c.id), ['z', 'y', 'x']);
  assert.deepEqual(prioritize(ctx, rows, scores, 'recent').map(r => r.c.id), ['y', 'z', 'x']);
  assert.equal(order('RECENT'), 'recent'); assert.equal(order('bogus'), 'priority'); assert.equal(order(undefined), 'priority');
  assert.equal(beeperPinned({ labels: [{ id: '1', name: 'Work' }] }), false);
  assert.equal(beeperPinned({ labels: [{ id: '1', name: 'VIP' }] }), true);
});

test('glasses pins: stored in the gateway, listed for Hermes (route and MCP tool), removable, validated', async t => {
  const rows = [chat('c1', 1, { title: 'Pin me' }), chat('c2', 2)];
  const fake = upstream(() => rows), { call, store } = await app(t, fake);
  assert.equal((await call('/beeper/chats/c1/pin', { method: 'POST', body: { pinned: 'yes' } })).status, 400);
  await call('/beeper/chats/c1/pin', { method: 'POST', body: { pinned: true } });
  await call('/beeper/chats/c2/pin', { method: 'POST', body: { pinned: true } });
  const pins = await (await call('/beeper/pins')).json();
  assert.deepEqual(pins.map(p => [p.id, p.name]), [['c1', 'Pin me'], ['c2', 'c2']]);
  assert.ok(store.all(PINS).every(p => p.pinnedAt));
  const events = await (await call('/events?since=0&wait=0')).json();
  assert.ok(events.events.some(e => e.type === 'beeper' && e.chatIds.includes('c2')));
  const mcp = await (await call('/mcp', { method: 'POST', token: 'joy', body: { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'glasses_beeper_pins', arguments: {} } } })).json();
  assert.equal(mcp.result.isError, false);
  assert.deepEqual(mcp.result.structuredContent.pins.map(p => p.chatId), ['c1', 'c2']);
  await call('/beeper/chats/c1/pin', { method: 'POST', body: { pinned: false } });
  await call('/beeper/chats/c2/pin', { method: 'DELETE' });
  assert.deepEqual(await (await call('/beeper/pins')).json(), []);
  assert.deepEqual(fake.writes(), []);
});

test('find: spoken names match titles, people, prefixes and misheard spellings; filler words are dropped', () => {
  assert.equal(cleanQuery('Find the chat with Sam.'), 'sam');
  assert.equal(cleanQuery('open my conversation with Avery Stone'), 'avery stone');
  assert.equal(cleanQuery('go to the boys group chat'), 'boys');
  assert.ok(nameScore('boys', 'The Boys') >= 0.8);
  assert.equal(cleanQuery('  Zoë  '), 'zoe');
  assert.equal(nameScore('sam', 'Sam'), 1);
  assert.equal(nameScore('sam', 'Samantha Reyes'), 0.9);
  assert.equal(nameScore('reyes', 'Samantha Reyes'), 0.86);
  assert.ok(nameScore('jon', 'John') >= 0.5);
  assert.ok(nameScore('caitlin', 'Kaitlyn') >= 0.5);
  assert.equal(nameScore('xavier', 'Sam'), 0);
  assert.equal(nameScore('', 'Sam'), 0);
});

test('find ranks the DM with a person above a group they are in, skips archived chats, and says when it is sure', async t => {
  const rows = [
    group('Weekend crew', 1),
    chat('dm-avery', 50, { title: 'Avery Stone' }),
    chat('Avery old', 5, { isArchived: true, title: 'Avery Stone' }),
    chat('dm-blake', 60, { title: 'Blake Moss' }),
    chat('dm-blair', 70, { title: 'Blair Moss' }),
  ];
  const ctx = ctxFor();
  const found = findChats(ctx, rows, 'avery');
  assert.deepEqual(found.map(m => m.chat.id), ['dm-avery', 'Weekend crew']);
  assert.equal(confident(found), true);
  const moss = findChats(ctx, rows, 'moss');
  assert.equal(confident(moss), false); // Blake Moss and Blair Moss are equally likely
  assert.deepEqual(findChats(ctx, rows, 'weekend crew').map(m => m.chat.id)[0], 'Weekend crew');
  assert.deepEqual(findChats(ctx, rows, 'nobody here'), []);

  const fake = upstream(() => rows), { call } = await app(t, fake);
  const res = await (await call('/beeper/find?q=' + encodeURIComponent('find Avery'))).json();
  assert.equal(res.query, 'avery'); assert.equal(res.confident, true);
  assert.deepEqual(res.matches.map(m => m.id), ['dm-avery', 'Weekend crew']);
  assert.equal(res.matches[0].name, 'Avery Stone'); assert.ok(res.matches[0].match.score >= 0.9);
  assert.equal((await call('/beeper/find')).status, 400);
  assert.deepEqual(fake.writes(), []);
});
