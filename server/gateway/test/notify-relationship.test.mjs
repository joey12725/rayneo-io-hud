// Notification classifier, round 2: Beeper relationship stats (volume only, cached per chat), the guards that use them, the
// group and burst guards, ceilings (morning reports, Instagram, announcement channels) and the call input fields.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { relationshipStats, relationshipLine, relationshipFor, cleanRelationship, cleanContext, guard, linkOnly } from '../apis/glasses/lib/notify-context.mjs';
import { classifierMessage, runLength } from '../apis/glasses/lib/notify-route.mjs';
import { routeInput, policy, ceiling, capped } from '../apis/glasses/lib/notify-policy.mjs';

const NOW = Date.parse('2026-11-18T01:00:00Z');
const at = (min) => new Date(NOW + min * 60000).toISOString();
const DAY = 24 * 60;
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const d = (route, importance = 0.5) => ({ route, importance, reason: 'x', by: 'model' });

test('relationship stats: 30-day volume, Robin\'s share, his last message, tiers by volume, scaled when history is short', () => {
  const msgs = [];
  for (let i = 0; i < 80; i++) msgs.push({ at: at(-i * 60 - 5), isOwner: i % 3 === 0 });
  msgs.push({ at: at(-40 * DAY), isOwner: true });   // older than 30 days: not counted, proves the window is covered
  const s = relationshipStats(msgs, { ref: NOW });
  assert.equal(s.msgs30d, 80); assert.equal(s.ownerMsgs30d, 27); assert.equal(s.ownerShare, 0.34); assert.equal(s.lastOwnerAt, at(-5));
  assert.equal(s.tier, 'frequent'); assert.equal(s.estimated, false);
  // Only 3 days of history loaded and more exists: counts scaled to 30 days.
  const short = relationshipStats([{ at: at(-3 * DAY), isOwner: false }, { at: at(-DAY), isOwner: true }, { at: at(-60), isOwner: false }], { ref: NOW });
  assert.equal(short.estimated, true); assert.equal(short.msgs30d, 30); assert.equal(short.ownerMsgs30d, 10); assert.equal(short.tier, 'regular');
  assert.equal(relationshipStats([{ at: at(-3 * DAY), isOwner: false }, { at: at(-60), isOwner: false }], { ref: NOW, complete: true }).tier, 'none');
  assert.equal(relationshipStats([{ at: at(-60), isOwner: true }, { at: at(-30), isOwner: false }], { ref: NOW, complete: true }).tier, 'occasional');
  // Messages after the reference time (Robin's later reply to the message being decided) never count.
  assert.equal(relationshipStats([{ at: at(5), isOwner: true }, { at: at(-1), isOwner: false }], { ref: NOW, complete: true }).lastOwnerAt, null);
  assert.equal(relationshipLine(s, at(0), { owner: 'Robin' }), '80 msgs/30d, Robin writes 34%, last reply 5 min ago (frequent contact)');
  assert.equal(relationshipLine(short, at(0), { owner: 'Robin' }), '~30 msgs/30d, Robin writes 33%, last reply 24 h ago (Robin talks with them regularly)');
  assert.match(relationshipLine({ ...s, lastOwnerAt: at(-3 * DAY) }, at(0), { isGroup: true, owner: 'Robin' }), /last message from Robin 3 d ago \(a chat Robin is very active in\)$/);
  assert.equal(cleanRelationship({ msgs30d: -4, ownerShare: 7, tier: 'bestie', lastOwnerAt: 'never' }).tier, 'none');
  assert.equal(cleanRelationship('x'), null);
  assert.deepEqual(cleanContext({ turns: [], relationship: { msgs30d: 5, ownerMsgs30d: 2, ownerShare: 0.4, tier: 'occasional' } }).relationship.msgs30d, 5);
});

const frequent = { msgs30d: 412, ownerMsgs30d: 198, ownerShare: 0.48, lastOwnerAt: at(-120), estimated: false, tier: 'frequent' };
const regular = { msgs30d: 20, ownerMsgs30d: 6, ownerShare: 0.3, lastOwnerAt: at(-DAY), estimated: false, tier: 'regular' };
const stranger = { msgs30d: 1, ownerMsgs30d: 0, ownerShare: 0, lastOwnerAt: null, estimated: false, tier: 'none' };

test('guard: frequent and regular one-to-one contacts are not suppressed for low-content messages; strangers are', () => {
  const ctx = (rel, text, turns = []) => ({ relationship: rel, turns, target: { at: at(0), sender: 'Ann', text } });
  assert.equal(guard(d('suppress', 0.1), ctx(frequent, 'lol')).route, 'stream');
  assert.equal(guard(d('suppress', 0.1), ctx(frequent, 'lol')).guard, 'guard.relationship-floor');
  assert.equal(guard(d('suppress', 0.1), ctx(regular, 'haha')).route, 'stream');
  assert.equal(guard(d('suppress', 0.1), ctx(stranger, 'hey cutie')).route, 'suppress');
  assert.equal(guard(d('suppress', 0.1), ctx(frequent, 'lol'), { isGroup: true }).route, 'suppress', 'groups keep their default');
  // An acknowledgement after a notification: dropped for a regular contact, kept in the list for a frequent one.
  const notified = [{ speaker: 'Ann', isOwner: false, messages: [{ at: at(-2), text: 'dinner at 7?', priorRoute: 'popup', priorImportance: 0.8 }] }];
  assert.equal(guard(d('stream'), ctx(regular, 'ok', notified)).route, 'suppress');
  assert.equal(guard(d('suppress', 0.1), ctx(regular, 'ok', notified)).route, 'suppress');
  const g = guard(d('stream'), ctx(frequent, 'ok', notified));
  assert.equal(g.route, 'stream'); assert.equal(g.guard, 'guard.ack-frequent'); assert.ok(g.importance <= 0.3);
  // An ordinary link from someone Robin has written to is at least stream.
  assert.ok(linkOnly('https://www.reddit.com/r/x/comments/1')); assert.ok(linkOnly('lol https://x.com/a/status/1')); assert.ok(!linkOnly('hi'));
  assert.equal(guard(d('suppress', 0.1), ctx({ ...stranger, tier: 'occasional', lastOwnerAt: at(-10 * DAY) }, 'https://www.reddit.com/r/x/comments/1')).guard, 'guard.link-floor');
  assert.equal(guard(d('suppress', 0.1), ctx(stranger, 'https://www.reddit.com/r/x/comments/1')).route, 'suppress');
  // His own community/automation chats: at least stream, except a bare acknowledgement.
  assert.equal(guard(d('suppress', 0.1), ctx(null, 'sensor 3 offline'), { isGroup: true, ownChat: true }).guard, 'guard.own-chat');
  assert.equal(guard(d('suppress', 0.1), ctx(null, 'ok'), { isGroup: true, ownChat: true }).route, 'suppress');
});

test('guard: a group message not for Robin never pops up; the burst rule only holds back a follow-up when the first popped up', () => {
  const side = { turns: [{ speaker: 'Ben', isOwner: false, messages: [{ at: at(-3), text: 'who has the keys?' }] }], target: { at: at(0), sender: 'Cy', text: 'I do, want them tonight?' } };
  assert.equal(guard(d('popup', 0.8), side, { isGroup: true }).guard, 'guard.group-not-for-owner');
  assert.equal(guard(d('popup', 0.8), side, { isGroup: true, mentions: 1 }).route, 'popup');
  assert.equal(guard(d('popup', 0.8), { ...side, target: { ...side.target, text: 'Robin, want them tonight?' } }, { isGroup: true, ownerPattern: /\brobin\b/i }).route, 'popup');
  const ownerIn = { ...side, turns: [...side.turns, { speaker: 'Robin', isOwner: true, messages: [{ at: at(-2), text: 'anyone up for tacos?' }] }] };
  assert.equal(guard(d('popup', 0.8), ownerIn, { isGroup: true }).route, 'popup');
  assert.equal(guard(d('popup', 0.8), side, { isGroup: false }).route, 'popup');
  // Burst: the first message only streamed, so the follow-up may pop up; after a popup it may not.
  const streamed = { turns: [{ speaker: 'Ann', isOwner: false, messages: [{ at: at(-1), text: 'hey', priorRoute: 'stream' }] }], target: { at: at(0), sender: 'Ann', text: 'can you call me now? car broke down' } };
  assert.equal(guard(d('popup', 0.9), streamed).route, 'popup');
  const popped = { ...streamed, turns: [{ ...streamed.turns[0], messages: [{ at: at(-1), text: 'car broke down', priorRoute: 'popup', priorImportance: 0.9 }] }], target: { at: at(0), sender: 'Ann', text: 'on the highway near the exit' } };
  assert.equal(guard(d('popup', 0.9), popped).route, 'stream');
});

test('ceilings: morning reports, Instagram links and announcement channels are at most stream; T3 run length', () => {
  const morning = routeInput({ kind: 'hermes', source: 'hermes:cron', title: 'Morning report', body: 'Rain at 3', category: 'scheduled' });
  assert.deepEqual(ceiling(morning), { route: 'stream', reason: 'cap.morning-report' });
  assert.deepEqual(capped(d('popup', 0.9), ceiling(morning)), { route: 'stream', importance: 0.59, reason: 'x', by: 'model', cap: 'cap.morning-report' });
  const s = d('stream', 0.4); assert.equal(capped(s, ceiling(morning)), s);
  assert.equal(ceiling(routeInput({ kind: 'hermes', source: 'hermes:discord', title: '#plans', body: 'Booked for 7', category: 'reply' })), null);
  assert.equal(runLength({ prompt: { at: at(-12) }, response: { at: at(0) } }), 12 * 60000);
  assert.equal(runLength({ runMs: 5 }), 5); assert.equal(runLength({}), null);
});

test('calls: the phone\'s call-log facts are validated and drive the rules', () => {
  for (const bad of [{ priorAnsweredCalls: -1 }, { priorAnsweredCalls: '2' }, { ownerCalledNumber: 'yes' }, { privateNumber: 1 }, { spam: 'no' }, { business: 'x' }])
    assert.throws(() => routeInput({ kind: 'call', title: 'x', ...bad }), /Invalid/, JSON.stringify(bad));
  const n = routeInput({ kind: 'call', title: '(312) 555-0100', priorAnsweredCalls: 2.7, ownerCalledNumber: false });
  assert.equal(n.priorAnsweredCalls, 2); assert.equal(policy(n).reason, 'call.history');
  assert.equal(policy(routeInput({ kind: 'call', title: 'Mom', contact: { known: true }, privateNumber: true })).reason, 'call.private');
  assert.equal(policy(routeInput({ kind: 'call', title: 'Mom', contact: { known: true }, callerVerified: 'failed' })).route, 'suppress');
  assert.equal(policy(routeInput({ kind: 'call', title: 'Mom', contact: { known: true }, callerVerified: 'failed', ownerCalledNumber: true })).route, 'popup', 'prior calls override');
});

// ---- HTTP ----

function upstream({ model = () => ({ route: 'suppress', importance: 0.1, reason: 'banter' }), pages = [], title = 'Ann' } = {}) {
  const log = [], beeperCalls = [];
  const fetch = async (url, opts = {}) => {
    const u = new URL(url);
    if (u.pathname.endsWith('/chat/completions')) { const body = JSON.parse(opts.body); log.push(body); return json({ choices: [{ message: { content: JSON.stringify(model(body)) } }] }); }
    if (u.pathname === '/v1/accounts') return json([{ accountID: 'sms', network: 'SMS' }]);
    if (u.pathname === '/v1/chats') return json({ items: [{ id: '!ann:beeper.local', title, accountID: 'sms', type: 'single', unreadCount: 1, lastActivity: at(0), preview: { text: 'lol' } }], hasMore: false });
    if (decodeURIComponent(u.pathname) === '/v1/chats/!ann:beeper.local/messages') {
      beeperCalls.push(u.search);
      const cursor = u.searchParams.get('cursor');
      const i = cursor ? Number(cursor) : 0;
      return json({ items: pages[i] ?? [], hasMore: i + 1 < pages.length, oldestCursor: String(i + 1) });
    }
    return json({});
  };
  return { fetch, models: () => log, beeperCalls: () => beeperCalls };
}
async function app(t, fake, config = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'notify-rel-'));
  const store = createStore(dir, { now: () => NOW });
  const result = await createApp({ env: {}, log: () => {}, store, secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone', GLASSES_AGENT_TOKEN: 'joy' }[name] ?? 'fixture') },
    deps: { glasses: { fetch: fake.fetch, now: () => NOW, config: { t3Machines: [], owner: { name: 'Robin', aliases: ['robin'] }, ...config } } } });
  result.server.listen(0, '127.0.0.1'); await once(result.server, 'listening');
  t.after(async () => { result.server.closeAllConnections(); await new Promise(r => result.server.close(r)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const call = async (route, body) => {
    const r = await fetch(`http://127.0.0.1:${result.server.address().port}/glasses/v1${route}`, { method: 'POST', headers: { Authorization: 'Bearer phone', 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    return { status: r.status, body: await r.json() };
  };
  return { call, store };
}
const msg = (min, mine, i) => ({ id: `r${min}-${i}`, chatID: '!ann:beeper.local', senderID: mine ? '@me:beeper.local' : '@ann:beeper.local', senderName: mine ? 'Robin' : 'Ann', isSender: mine, timestamp: at(min), text: mine ? 'sure' : 'hey' });

test('Beeper: relationship stats come from bounded history pages, are cached per chat, reach the classifier and keep a frequent contact out of suppress', async t => {
  // Three pages, newest first: 60 messages over the last ~10 days (Robin wrote every other one), then older history.
  const pages = [0, 1, 2].map(p => Array.from({ length: 20 }, (_, i) => msg(-(p * 20 + i) * 4 * 60 - 2, i % 2 === 0, i)));
  pages.push([msg(-45 * DAY, false, 0)]);
  const fake = upstream({ pages }), { call } = await app(t, fake);
  const r = await call('/notify/route?debug=1', { kind: 'beeper', title: 'Ann', body: 'lol' });
  assert.equal(r.body.route, 'stream'); assert.equal(r.body.debug.guard, 'guard.relationship-floor');
  const sent = fake.models().at(-1).messages[1].content;
  assert.match(sent, /^RELATIONSHIP: ~?\d+ msgs\/30d, Robin writes 50%, last reply \d+ min ago \(frequent contact\)$/m);
  const fetched = fake.beeperCalls().length;
  assert.ok(fetched <= 1 + 4, 'bounded pages');
  await call('/notify/route?debug=1', { kind: 'beeper', title: 'Ann', body: 'lol' });
  assert.equal(fake.beeperCalls().length - fetched, 1, 'second message: only the context page, relationship cached');
});

test('Beeper eval: a supplied relationship is used only with debug; ceilings apply to the classifier\'s answer', async t => {
  const fake = upstream({ model: () => ({ route: 'popup', importance: 0.9, reason: 'news' }) }), { call } = await app(t, fake);
  const context = { target: { at: at(0), sender: 'Bot', text: 'Morning report: rain at 3' }, turns: [], relationship: { msgs30d: 3, ownerMsgs30d: 0, ownerShare: 0, tier: 'none' } };
  const r = await call('/notify/route', { kind: 'beeper', title: 'Bot', body: 'Morning report: rain at 3', context, debug: true });
  assert.equal(r.body.route, 'stream'); assert.match(r.body.debug.guard, /cap\.morning-report/);
  assert.match(fake.models().at(-1).messages[1].content, /^RELATIONSHIP: 3 msgs\/30d, Robin writes 0%, no message from Robin loaded \(Robin has not written here\)$/m);
  const reel = await call('/notify/route', { kind: 'beeper', title: 'Bot', body: 'is this the bar for tonight? https://www.instagram.com/p/xyz/', context, debug: true });
  assert.equal(reel.body.route, 'stream'); assert.match(reel.body.debug.guard, /cap\.instagram/);
  const msgText = classifierMessage(routeInput({ kind: 'beeper', title: 'Bot', body: 'x' }), { relationship: frequent }, { target: { at: at(0), sender: 'Bot', text: 'x' }, turns: [] }, { owner: 'Robin' });
  assert.match(msgText, /^RELATIONSHIP: 412 msgs\/30d, Robin writes 48%, last reply 2 h ago \(frequent contact\)$/m);
});

test('Beeper: a configured own chat is never a promotion and at least stream', async t => {
  const fake = upstream({ title: 'Lab Automation', model: () => ({ route: 'suppress', importance: 0.1, reason: 'promo' }) }), { call } = await app(t, fake, { notifyOwnChats: ['automation'] });
  const r = await call('/notify/route?debug=1', { kind: 'beeper', title: 'Lab Automation', body: 'new discount code for the relay boards' });
  assert.equal(r.body.by, 'model'); assert.equal(r.body.route, 'stream'); assert.equal(r.body.debug.guard, 'guard.own-chat');
});
