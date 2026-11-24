import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { policy, fallback, routeInput, inferKind, legacy, ceiling, normalizePolicy, DEFAULT_POLICY } from '../apis/glasses/lib/notify-policy.mjs';
import { ownerPattern } from '../apis/glasses/lib/persona.mjs';
import { classifierPrompt, validVerdict } from '../apis/glasses/lib/notify-route.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = JSON.parse(await readFile(path.join(HERE, 'fixtures', 'notify-policy.json'), 'utf8'));
const NOW = Date.parse('2026-11-17T18:00:00Z');
const stamp = (delta = 0) => new Date(NOW + delta).toISOString();
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

const POLICY = normalizePolicy(FIXTURE.policy, DEFAULT_POLICY);
const OWNER = ownerPattern({ config: { owner: FIXTURE.owner } });
const facts = (c) => ({
  policy: POLICY, ownerPattern: OWNER,
  watched: (key) => FIXTURE.watched.includes(key),
  matchWatched: (value) => Object.entries(FIXTURE.watchedTitles).find(([, title]) => String(value).toLowerCase().includes(title.toLowerCase()))?.[0] ?? null,
  ...(c.facts ?? {}),
});

test('every row of Robin\'s policy table (shared fixture with the phone mirror)', () => {
  assert.ok(FIXTURE.cases.length >= 60);
  for (const c of FIXTURE.cases) {
    const input = routeInput(c.input);
    const got = policy(input, facts(c));
    if (c.expect === null) {
      assert.equal(got, null, c.name);
      const fb = fallback(input, facts(c));
      assert.equal(fb.route, c.fallback.route, c.name + ' (fallback)');
      assert.notEqual(fb.route, 'popup', c.name);
      assert.equal(fb.by, 'fallback');
    } else {
      assert.ok(got, c.name + ': expected a rule decision');
      assert.equal(got.route, c.expect.route, c.name);
      assert.equal(got.reason, c.expect.reason, c.name);
      assert.equal(got.by, 'rule');
      assert.ok(got.importance >= 0 && got.importance <= 1, c.name);
    }
    if (c.cap !== undefined) assert.deepEqual(ceiling(input), c.cap, c.name + ' (cap)');
  }
});

test('every real Tinder and Feeld channel id routes by channel, catch-alls fall to the text rules', () => {
  let checked = 0;
  for (const [pkg, channels] of Object.entries(FIXTURE.datingChannels)) {
    for (const [channelId, reason] of Object.entries(channels)) {
      const input = routeInput({ kind: 'app', source: pkg, channelId, title: 'Riley', body: 'Hey' });
      const got = policy(input, { policy: POLICY });
      if (reason === 'text') { assert.equal(got, null, `${pkg} ${channelId}`); assert.equal(fallback(input).route, 'suppress'); }
      else assert.equal(got?.reason, reason, `${pkg} ${channelId}`);
      checked++;
    }
  }
  assert.equal(checked, 12);
  // Text patterns never override a known channel: a message on the message channel pops up even if it mentions a boost.
  assert.equal(policy(routeInput({ kind: 'app', source: 'com.tinder', channelId: 'MESSAGES', title: 'Riley', body: 'my friend bought a boost lol' }), { policy: POLICY }).route, 'popup');
  // Off by default: the dating preset is opt-in.
  assert.equal(policy(routeInput({ kind: 'app', source: 'com.tinder', channelId: 'MATCHES', title: 'Riley', body: 'Hey' })).reason, 'deny.default');
});

test('route input validation, kind inference for old clients, legacy shape', () => {
  for (const bad of [null, [], 'x', { title: 4 }, { kind: 'sms' }, { callerVerified: 'maybe' }, { contact: { known: 'yes' } }, { isGroup: 'true' }, { debug: 1 }])
    assert.throws(() => routeInput(bad), /4|Invalid|required|strings/, JSON.stringify(bad));
  const big = routeInput({ title: 'T'.repeat(500), body: 'B'.repeat(5000), source: 'S'.repeat(500) });
  assert.equal(big.title.length, 200); assert.equal(big.body.length, 1000); assert.equal(big.source.length, 200);
  assert.equal(inferKind({ source: 'com.beeper.android' }), 'beeper');
  assert.equal(inferKind({ source: '', app: 'T3 Code' }), 't3');
  assert.equal(inferKind({ source: 'Hermes' }), 'assistant');
  assert.equal(inferKind({ source: 'io.homeassistant.companion.android' }), 'app');
  assert.equal(routeInput({ kind: 'app', source: 'com.beeper.android' }).kind, 'beeper');
  assert.equal(routeInput({ isGroupConversation: true }).isGroup, true);
  assert.deepEqual(legacy({ route: 'popup', importance: 0.9 }), { route: 'notify', importance: 0.9 });
  assert.deepEqual(legacy({ route: 'suppress', importance: 0 }), { route: 'suppress', importance: 0 });
});

test('the classifier prompt lives in its own versioned file and the output is validated strictly', async () => {
  const p = classifierPrompt();
  assert.match(p.version, /^notify-route@\d+$/);
  assert.match(p.text, /popup/); assert.match(p.text, /untrusted data/); assert.doesNotMatch(p.text, /^version:/m);
  const dir = await mkdtemp(path.join(tmpdir(), 'classifier-')); const file = path.join(dir, 'p.md');
  await writeFile(file, 'no version line\nbody'); assert.equal(classifierPrompt(file).version, 'unversioned'); await rm(dir, { recursive: true, force: true });
  assert.deepEqual(validVerdict({ route: 'popup', importance: 0.9, reason: 'asks Robin' }), { route: 'popup', importance: 0.9, reason: 'asks Robin' });
  assert.equal(validVerdict({ route: 'popup', importance: 0.5, reason: 'meh' }).route, 'stream'); // weak popup is clamped
  for (const bad of [null, [], { route: 'notify', importance: 0.5 }, { route: 'stream', importance: 2 }, { route: 'stream', importance: '0.5' }, { route: 'stream', importance: 0.5, extra: 1 }, { route: 'stream', importance: 0.5, reason: 4 }])
    assert.equal(validVerdict(bad), null, JSON.stringify(bad));
});

const CHATS = [
  { id: '!muted:beeper.local', title: 'Muted group', accountID: 'ig', type: 'group', isMuted: true, unreadCount: 3, lastActivity: stamp(-60000), preview: { text: 'Anyone around?' } },
  { id: '!sam:beeper.local', title: 'Sam', accountID: 'ig', type: 'single', unreadCount: 1, lastActivity: stamp(-30000), preview: { text: 'Are we still on for dinner at 7?' } },
  { id: '!spam:beeper.local', title: '+13125550100', accountID: 'sms', type: 'single', unreadCount: 1, lastActivity: stamp(-30000), preview: { text: 'You won a cruise' } },
];
function upstream({ model = () => ({ route: 'popup', importance: 0.9, reason: 'asks Robin about tonight' }), status = 200, rejectTemperature = false } = {}) {
  const log = [];
  const fetch = async (url, opts = {}) => {
    const u = new URL(url), method = opts.method ?? 'GET';
    if (u.pathname.endsWith('/chat/completions')) {
      const body = JSON.parse(opts.body); log.push({ model: true, body });
      if (rejectTemperature && 'temperature' in body) return json({ error: 'temperature unsupported' }, 400);
      if (status !== 200) return json({ error: 'x' }, status);
      const answer = model(body);
      return json({ choices: [{ message: { content: typeof answer === 'string' ? answer : JSON.stringify(answer) } }] });
    }
    log.push({ method, path: u.pathname });
    if (u.pathname === '/v1/accounts') return json([{ accountID: 'ig', network: 'Instagram' }, { accountID: 'sms', network: 'Google Messages' }]);
    if (u.pathname === '/v1/chats') return json({ items: CHATS, hasMore: false });
    return json({});
  };
  return { fetch, log, models: () => log.filter(l => l.model) };
}
async function app(t, fake, config = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'notify-route-'));
  const store = createStore(dir, { now: () => NOW });
  const lines = [];
  const policyFile = path.join(dir, 'policy.json');
  await writeFile(policyFile, JSON.stringify(FIXTURE.policy));
  const result = await createApp({ env: { GATEWAY_DATA: dir }, log: (...args) => lines.push(args.join(' ')), store, secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone', GLASSES_AGENT_TOKEN: 'joy' }[name] ?? 'fixture') }, deps: { glasses: { fetch: fake.fetch, now: () => NOW, config: { t3Machines: [], owner: FIXTURE.owner, notifyPolicyFile: policyFile, ...config } } } });
  result.server.listen(0, '127.0.0.1'); await once(result.server, 'listening');
  t.after(async () => { result.server.closeAllConnections(); await new Promise(resolve => result.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const call = async (route, { method = 'GET', body, token = 'phone' } = {}) => {
    const r = await fetch(`http://127.0.0.1:${result.server.address().port}/glasses/v1${route}`, { method, headers: { Authorization: 'Bearer ' + token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
    return { status: r.status, body: await r.json() };
  };
  return { call, store, lines };
}

test('Beeper: resolves the chat, mute and spam suppress before any model call, open target is the thread', async t => {
  const fake = upstream(), { call } = await app(t, fake);
  const muted = await call('/notify/route', { method: 'POST', body: { kind: 'beeper', source: 'com.beeper.android', title: 'Muted group', conversationTitle: 'Muted group', body: 'Anyone around?', isGroup: true } });
  assert.equal(muted.body.route, 'suppress'); assert.equal(muted.body.reason, 'beeper.muted');
  assert.equal(fake.models().length, 0);
  const dm = await call('/notify/route', { method: 'POST', body: { kind: 'beeper', source: 'com.beeper.android', channelId: 'MESSAGES_5', title: 'Sam', body: 'Are we still on for dinner at 7?' } });
  assert.deepEqual(dm.body, { route: 'popup', importance: 0.9, reason: 'asks Robin about tonight', by: 'model', open: { app: 'beeper', target: '!sam:beeper.local' } });
  const prompt = fake.models().at(-1).body;
  assert.equal(prompt.temperature, 0); assert.equal(prompt.response_format.type, 'json_object');
  assert.match(prompt.messages[0].content, /Robin's RayNeo smart glasses/);
  assert.match(prompt.messages[1].content, /^CHAT: one-to-one chat on Instagram$/m);
  assert.match(prompt.messages[1].content, /^text: Are we still on for dinner at 7\?$/m);
  assert.ok(fake.log.every(l => l.model || l.method === 'GET'), 'routing never writes to Beeper');
});

test('Beeper: stored spam verdict suppresses; share links never reach the classifier; unresolved chat still gets the Beeper app', async t => {
  const fake = upstream({ model: () => ({ route: 'stream', importance: 0.5, reason: 'chatter' }) }), { call, store } = await app(t, fake);
  store.put('beeper-spam', { id: '!spam:beeper.local', verdict: 'spam', confidence: 0.97, source: 'model' });
  const spam = await call('/notify/route', { method: 'POST', body: { kind: 'beeper', title: '+13125550100', body: 'You won a cruise', network: 'SMS' } });
  assert.equal(spam.body.reason, 'beeper.spam');
  const reel = await call('/notify/route', { method: 'POST', body: { kind: 'beeper', title: 'Sam', body: 'https://www.instagram.com/reel/abc/' } });
  assert.equal(reel.body.route, 'suppress'); assert.equal(reel.body.reason, 'beeper.share');
  assert.equal(fake.models().length, 0);
  const unknown = await call('/notify/route', { method: 'POST', body: { kind: 'beeper', title: 'Nobody', body: 'Hi there, long time' } });
  assert.deepEqual(unknown.body, { route: 'stream', importance: 0.5, reason: 'chatter', by: 'model', open: { app: 'beeper' } });
});

test('the classifier failure modes: invalid JSON, garbage, upstream error and timeout all fall back to stream and never pop up', async t => {
  for (const fake of [upstream({ model: () => 'not json' }), upstream({ model: () => ({ route: 'yes' }) }), upstream({ status: 500 })]) {
    const { call } = await app(t, fake);
    const r = await call('/notify/route', { method: 'POST', body: { kind: 'beeper', title: 'Sam', body: 'Are we still on for dinner at 7?' } });
    assert.equal(r.body.route, 'stream'); assert.equal(r.body.by, 'fallback'); assert.equal(r.body.importance, 0.4);
    assert.deepEqual(r.body.open, { app: 'beeper', target: '!sam:beeper.local' });
  }
  const dating = await (await app(t, upstream({ status: 500 }))).call('/notify/route', { method: 'POST', body: { kind: 'app', source: 'com.takimi.android', title: 'Taimi', body: 'Alex is online now' } });
  assert.equal(dating.body.route, 'suppress'); assert.equal(dating.body.by, 'fallback');
});

test('temperature 0 is dropped once when the proxy refuses it; debug returns the raw model output and prompt version', async t => {
  const fake = upstream({ rejectTemperature: true }), { call, lines } = await app(t, fake);
  const first = await call('/notify/route?debug=1', { method: 'POST', body: { kind: 'beeper', title: 'Sam', body: 'Are we still on for dinner at 7?' } });
  assert.equal(first.body.route, 'popup'); assert.equal(first.body.by, 'model');
  assert.match(first.body.debug.promptVersion, /^notify-route@/);
  assert.equal(JSON.parse(first.body.debug.raw).route, 'popup');
  assert.equal(typeof first.body.debug.ms, 'number'); assert.equal(first.body.debug.resolved.resolved, true);
  assert.equal(fake.models().length, 2); // refused with temperature, retried without
  await call('/notify/route', { method: 'POST', body: { kind: 'beeper', title: 'Sam', body: 'Second one?' } });
  assert.equal(fake.models().length, 3); assert.equal('temperature' in fake.models().at(-1).body, false);
  const plain = await call('/notify/route', { method: 'POST', body: { kind: 'beeper', title: 'Sam', body: 'Third?', debug: false } });
  assert.equal(plain.body.debug, undefined);
  // One decision line each, with no notification text.
  const notify = lines.filter(l => l.startsWith('notify'));
  assert.equal(notify.length, 3);
  assert.match(notify[0], /route=popup by=model kind=beeper .*ms=\d+ v=notify-route@/);
  // the classifier's free-text reason ('asks Robin about tonight') can paraphrase the message: it is logged as reason=model.
  assert.match(notify[0], /reason=model /);
  assert.ok(notify.every(l => !/dinner|Sam|Second|Third|tonight/.test(l)), notify.join('\n'));
});

test('T3: watched threads are stored on the gateway; only they notify (needs you popup, finished stream)', async t => {
  const fake = upstream(), { call } = await app(t, fake);
  assert.deepEqual((await call('/t3/watch')).body, []);
  assert.equal((await call('/t3/watch', { method: 'POST', body: { machine: 'mac', id: 'bad id' } })).status, 400);
  assert.equal((await call('/t3/watch', { method: 'POST', body: { machine: 'mac' } })).status, 400);
  assert.deepEqual((await call('/t3/watch', { method: 'POST', body: { machine: 'mac', id: 't1', title: 'Fix the login flow' } })).body, { ok: true, key: 'mac:t1', watching: true });
  const listed = (await call('/t3/watch')).body;
  assert.equal(listed.length, 1); assert.deepEqual({ ...listed[0], createdAt: undefined }, { key: 'mac:t1', machine: 'mac', id: 't1', title: 'Fix the login flow', createdAt: undefined });
  const route = async (body) => (await call('/notify/route', { method: 'POST', body: { kind: 't3', ...body } })).body;
  assert.deepEqual(await route({ chatId: 'mac:t1', title: 'Fix the login flow', category: 'needs-you' }), { route: 'popup', importance: 0.9, reason: 't3.needs-you', by: 'rule', open: { app: 't3', target: 'mac:t1' } });
  assert.equal((await route({ chatId: 'mac:t1', category: 'done' })).route, 'stream');
  assert.equal((await route({ chatId: 'mac:t2', category: 'needs-you' })).route, 'suppress');
  assert.equal((await call('/notify/route', { method: 'POST', body: { source: 'com.t3tools.t3code', title: 'Fix the login flow', body: 'Approval required' } })).body.reason, 't3.app'); // the gateway's T3 notifier owns T3
  assert.deepEqual((await call('/t3/watch', { method: 'POST', body: { machine: 'mac', id: 't1', on: false } })).body, { ok: true, key: 'mac:t1', watching: false });
  assert.equal((await route({ chatId: 'mac:t1', category: 'needs-you' })).route, 'suppress');
  assert.equal(fake.models().length, 0);
});

test('Hermes /assistant/notify accepts a popup hint and the event carries the route', async t => {
  const fake = upstream(), { call, store } = await app(t, fake);
  assert.equal((await call('/assistant/notify', { method: 'POST', token: 'joy', body: { title: 'Leave now', body: 'Traffic', popup: 'yes' } })).status, 400);
  await call('/assistant/notify', { method: 'POST', token: 'joy', body: { title: 'Leave now', body: 'Traffic is building', popup: true } });
  await call('/assistant/notify', { method: 'POST', token: 'joy', body: { title: 'FYI', body: 'Report ready' } });
  const events = store.all('events').filter(e => e.type === 'notification');
  assert.deepEqual(events.map(e => e.route), ['popup', 'stream']);
  const routed = (await call('/notify/route', { method: 'POST', body: { kind: 'joy', title: 'x', popup: true } })).body;
  assert.equal(routed.route, 'popup'); assert.deepEqual(routed.open, { app: 'joy' });
});
