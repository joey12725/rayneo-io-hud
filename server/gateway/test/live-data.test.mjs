import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { messageText } from '../apis/glasses/lib/beeper.mjs';
import { http, machineState, step, detail, needYouCounts } from '../apis/glasses/lib/t3.mjs';
import { notificationRule, notificationInput } from '../apis/glasses/routes/events.mjs';
import { printerTimeLeft } from '../apis/glasses/routes/ha.mjs';
import { getWeather } from '../apis/glasses/lib/weather.mjs';
import { GLASSES_PAGE_GUIDANCE } from '../apis/glasses/lib/display.mjs';
import { GLASSES_CONTEXT, toolStatus } from '../apis/glasses/lib/hermes.mjs';
import { GLASSES_SUFFIX } from '../apis/glasses/lib/t3.mjs';

const NOW = Date.parse('2026-11-12T20:00:00Z');
const stamp = offset => new Date(NOW + offset).toISOString();
const json = v => new Response(JSON.stringify(v));
async function fixture(t, fake, config = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'live-data-')), store = createStore(dir);
  const app = await createApp({ env: {}, log: () => {}, store, secrets: { get: async name => name === 'GLASSES_API_TOKEN' ? 'phone' : 'fixture' },
    deps: { glasses: { fetch: fake, now: () => NOW, config: { t3Machines: [], owner: { name: 'Robin', aliases: ['robin'] }, composio: { calendarAccount: 'googlecalendar_test', spotifyAccount: 'spotify_test' }, ...config } } } });
  app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
  t.after(async () => { app.server.closeAllConnections(); await new Promise(resolve => app.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  return (route, options = {}) => fetch(`http://127.0.0.1:${app.server.address().port}/glasses/v1${route}`, { ...options, headers: { Authorization: 'Bearer phone', ...options.headers } });
}

test('Beeper media summaries cover official fields, captions and defensive bridge variants', () => {
  const cases = [
    [{ attachments: [{ type: 'img' }] }, 'sent a photo'],
    [{ attachments: [{ type: 'video', duration: 23 }] }, 'sent a video (0:23)'],
    [{ attachments: [{ type: 'audio', duration: 12, isVoiceNote: true }] }, 'voice message (0:12)'],
    [{ attachments: [{ type: 'unknown', mimeType: 'image/gif', isGif: true }] }, 'GIF'],
    [{ attachments: [{ type: 'img', isSticker: true }] }, 'sticker'],
    [{ attachments: [{ type: 'unknown', fileName: 'name.pdf' }] }, 'sent a file: name.pdf'],
    [{ links: [{ url: 'https://www.instagram.com/reel/abc' }] }, '\uE002 sent a reel'],
    [{ links: [{ url: 'https://instagram.com/p/abc' }] }, '\uE001 sent a post'],
    [{ type: 'IMAGE' }, 'sent a photo'],
    [{ type: 'VIDEO' }, 'sent a video'],
    [{ text: 'Look', attachments: [{ mimeType: 'image/png' }] }, 'Look · sent a photo'],
    [{ text: 'https://instagram.com/reel/abc', links: [{ url: 'https://instagram.com/reel/abc' }] }, 'https://instagram.com/reel/abc'],
    [{ attachments: [null, {}] }, 'sent a file'],
  ];
  for (const [message, expected] of cases) assert.equal(messageText(message), expected);
});

test('Beeper routes scrub ID-only senders/titles, truncated members, mentions and reply quotes; totals exceed list cap', async t => {
  const known = '@instagram_123:beeper.local', unknown = '@instagram_456:beeper.local';
  const chats = Array.from({ length: 35 }, (_, i) => ({ id: 'c' + i, type: 'group', title: i ? unknown : '123456789', unreadCount: 2, participants: { items: [], total: 30 }, preview: { senderID: i ? unknown : known, senderName: i ? unknown : '12345', attachments: [{ type: 'img' }], text: `Hey ${known} and ${unknown}` } }));
  const call = await fixture(t, async url => {
    const u = new URL(url);
    if (u.pathname === '/v1/accounts') return json([]);
    if (u.pathname === '/v1/chats') return json({ items: chats });
    if (u.pathname.endsWith('/messages')) return json({ items: [{ id: 'm', senderID: known, senderName: '12345', text: `${known}: hi ${unknown}`, replyTo: { senderID: unknown, senderName: '6789', text: `> ${known} ${unknown}` } }] });
    return json({ ...chats.find(c => '/v1/chats/' + c.id === u.pathname), participants: { items: [{ id: known, fullName: 'Alex Jones' }], total: 30 } });
  });
  const list = await (await call('/beeper/chats')).json();
  assert.equal(list.length, 35); // default limit is now 100 (BEEP: no missing conversations)
  assert.equal(list[0].last.sender, 'Alex Jones'); assert.equal(list[1].last.sender, 'Someone');
  for (const c of list) assert.doesNotMatch(JSON.stringify([c.name, c.last.sender, c.last.text]), /@[^:\s]+:[\w.-]+|123456789|12345/);
  const messages = await (await call('/beeper/chats/c0/messages')).json();
  assert.equal(messages[0].sender, 'Alex Jones'); assert.equal(messages[0].replyTo.sender, 'Someone');
  assert.equal(messages[0].text, 'Alex Jones: hi @someone'); assert.equal(messages[0].replyTo.text, '> Alex Jones @someone');
  assert.deepEqual(await (await call('/beeper/summary')).json(), { unread: 35, unreadChats: 35, unreadMessages: 70, names: Array(35).fill('Group chat'), quiet: { muted: 0, spam: 0 }, total: 35 });
});

test('paged Beeper route forwards opaque before and retains whole upstream pages without gaps', async t => {
  const requested = [];
  const message = i => ({ id: String(i), text: String(i), timestamp: stamp(i * 1000), senderName: 'Alex' });
  const call = await fixture(t, async url => {
    const u = new URL(url);
    if (u.pathname === '/v1/accounts') return json([]);
    if (u.pathname.endsWith('/messages')) {
      requested.push(u.searchParams.get('cursor'));
      return json(u.searchParams.has('cursor') ? { items: Array.from({ length: 10 }, (_, i) => message(i)), hasMore: false } : { items: Array.from({ length: 40 }, (_, i) => message(i + 10)), hasMore: true, oldestCursor: 'opaque+/=' });
    }
    return json({ id: 'c', participants: [] });
  });
  const first = await (await call('/beeper/chats/c/messages?page=true')).json();
  assert.equal(first.messages.length, 40); assert.equal(first.nextBefore, 'opaque+/='); assert.equal(first.loaded, true);
  const older = await (await call('/beeper/chats/c/messages?page=true&before=' + encodeURIComponent(first.nextBefore))).json();
  assert.equal(older.nextBefore, null); assert.equal(requested[1], 'opaque+/=');
  assert.deepEqual([...older.messages, ...first.messages].map(m => Number(m.id)), Array.from({ length: 50 }, (_, i) => i));
});

test('dictation cleanup failure preserves raw text and silence is a distinct successful done', async t => {
  let transcript = 'keep these words', cleanup = 0;
  const call = await fixture(t, async url => { if (String(url).endsWith('/audio/transcriptions')) return json({ text: transcript }); cleanup++; throw Error('offline'); });
  const dictate = async () => {
    const response = await call('/voice/dictate?target=memo', { method: 'POST', headers: { 'Content-Type': 'audio/wav' }, body: Buffer.from('RIFF audio') });
    assert.equal(response.status, 200); return (await response.text()).trim().split('\n').map(JSON.parse).at(-1);
  };
  assert.deepEqual(await dictate(), { phase: 'done', text: transcript, raw: transcript, cleaned: false, silence: false });
  transcript = '  ';
  assert.deepEqual(await dictate(), { phase: 'done', text: '', raw: '', cleaned: false, silence: true }); assert.equal(cleanup, 1);
});

test('the classifier uses actual Beeper group context, protects critical sources, and fails open with Instagram prompt', async t => {
  assert.equal(notificationRule({ source: 'com.beeper.android', isGroupConversation: true, body: 'lol!' }).route, 'suppress');
  assert.equal(notificationRule({ source: 'com.beeper.android', title: 'Robin and friends', isGroupConversation: true, body: 'lol!' }).route, 'suppress');
  assert.equal(notificationRule({ source: 'com.beeper.android', isGroupConversation: false, body: 'lol!' }), null);
  assert.equal(notificationRule({ app: 'T3 Code', body: 'Finished newsletter task' }).route, 'notify');
  assert.equal(notificationRule({ app: 'Beeper', isGroupConversation: true, body: 'Robin, dinner at 7?' }).route, 'notify');
  assert.equal(notificationRule({ app: 'Beeper', isGroupConversation: true, body: 'Robin, this sale is for you' }).route, 'notify');
  assert.throws(() => notificationInput({ isGroupConversation: 'true' }));
  let prompt, supplied;
  const call = await fixture(t, async (_, opts) => { const body = JSON.parse(opts.body); prompt = body.messages[0].content; supplied = body.messages[1].content; throw Error('offline'); });
  const response = await call('/notify/classify', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ app: 'Beeper', network: 'Instagram', isGroupConversation: true, conversationTitle: 'Friends', sender: 'Alex', body: 'Watch this' }) });
  assert.deepEqual(await response.json(), { route: 'notify', importance: .4 }); assert.match(prompt, /Robin's RayNeo smart glasses/); assert.match(supplied, /^CHAT: group chat "Friends" on Instagram$/m); assert.match(supplied, /^from: Alex$/m); assert.match(supplied, /^text: Watch this$/m);
});

test('T3 offline cooldown needs two failures, backs off 15 s doubling to 60 s, preserves last seen, and bounds probes', async () => {
  let time = NOW, calls = 0, available = false;
  const ctx = { now: () => time, secret: async () => 'test', fetch: async (_, opts) => { calls++; assert.ok(opts.signal); if (!available) throw Error('offline'); return json({}); } };
  const machine = { id: 'desktop', url: 'https://fixture.example', secret: 'test' };
  await assert.rejects(http(ctx, machine, '/api/projects'), /desktop unreachable \(network error\)/);
  assert.equal(machineState(ctx, machine).lastSeen, null); assert.equal(machineState(ctx, machine).retryAt, 0);
  await assert.rejects(http(ctx, machine, '/api/projects')); assert.equal(calls, 2); assert.equal(machineState(ctx, machine).retryAt, NOW + 15000);
  await assert.rejects(http(ctx, machine, '/api/projects'), /desktop is offline \(network error\); retrying in 15 s/); assert.equal(calls, 2);
  time += 15001; await assert.rejects(http(ctx, machine, '/api/projects')); assert.equal(calls, 3); assert.equal(machineState(ctx, machine).retryAt, time + 30000);
  time += 30001; await assert.rejects(http(ctx, machine, '/api/projects')); assert.equal(machineState(ctx, machine).retryAt, time + 60000);
  time += 60001; await assert.rejects(http(ctx, machine, '/api/projects')); assert.equal(machineState(ctx, machine).retryAt, time + 60000);
  time += 60001; available = true; await http(ctx, machine, '/api/projects'); assert.equal(calls, 6);
  assert.equal(machineState(ctx, machine).lastSeen, new Date(time).toISOString()); assert.equal(machineState(ctx, machine).retryAt, 0);
  const seen = machineState(ctx, machine).lastSeen;
  available = false; await assert.rejects(http(ctx, machine, '/api/projects')); assert.equal(machineState(ctx, machine).lastSeen, seen);
  assert.equal(machineState(ctx, machine).online, true, 'one blip after a success is not offline');
});

test('T3 human step labels retain raw kind; guest requests expose text and count as needs-you', async () => {
  assert.deepEqual(step({ type: 'command_execution', command: 'pnpm test' }), { kind: 'command_execution', label: 'Running command', what: 'pnpm test', startedAt: null });
  const ctx = { now: () => NOW, config: { t3Machines: [{ id: 'test', url: 'https://test.example', secret: 'test' }] }, secret: async () => 'test', fetch: async () => json({ threads: [{ id: 'guest', pendingGuestMessageCount: 1 }] }) };
  const result = detail(ctx, { id: 'test' }, { thread: { id: 'guest', pendingGuestMessageCount: 1 }, messages: [{ id: 'g', role: 'guest', text: 'Please review this', createdAt: stamp(0) }] });
  assert.equal(result.request.kind, 'guest'); assert.equal(result.request.messages[0].text, 'Please review this'); assert.equal(result.thread.status, 'request');
  assert.deepEqual(await needYouCounts(ctx), { needYou: 1, done: 0 });
});

test('HA discovers same-printer remaining time with units, timestamps and unavailable values', () => {
  const sensor = (id, state, attributes = {}) => ({ entity_id: 'sensor.' + id, state, attributes });
  assert.equal(printerTimeLeft([sensor('other_time_remaining', '999', { unit_of_measurement: 'min' }), sensor('printer_remaining_time', '12', { device_class: 'duration', unit_of_measurement: 'min' })], Date.now(), 'sensor.printer_'), 720);
  assert.equal(printerTimeLeft([sensor('printer_time_left', '1:02:03')], Date.now(), 'sensor.printer_'), 3723);
  assert.equal(printerTimeLeft([sensor('printer_finish_time', stamp(300000), { device_class: 'timestamp' })], NOW, 'sensor.printer_'), 300);
  assert.equal(printerTimeLeft([sensor('printer_time_remaining', 'unavailable')], Date.now(), 'sensor.printer_'), null);
});

test('weather condition has no duplicate temperature; Oura public battery returns nullable latest synced level', async t => {
  const weather = await getWeather({ now: () => NOW, config: { homeLat: 1, homeLon: 2, tz: 'America/Indiana/Knox' }, fetch: async () => json({ hourly: { time: [stamp(0)], temperature_2m: [72], weather_code: [3] } }) });
  assert.deepEqual(weather, { temp: 72, unit: 'F', tempF: 72, condition: 'cloudy', icon: 'cloud' });
  const metric = await getWeather({ now: () => NOW, config: { homeLat: 1, homeLon: 2, tz: 'UTC', units: 'metric' }, fetch: async url => { assert.match(String(url), /temperature_unit=celsius/); return json({ hourly: { time: [stamp(0)], temperature_2m: [21.6], weather_code: [3] } }); } });
  assert.deepEqual([metric.temp, metric.unit, metric.tempF], [22, 'C', 72]);
  await assert.rejects(getWeather({ now: () => NOW, config: {}, fetch: async () => json({ hourly: { time: [stamp(0)], temperature_2m: [null], weather_code: [3] } }) }), /Weather unavailable/);
  const call = await fixture(t, async url => { assert.match(String(url), /ring_battery_level\?latest=true$/); return json({ data: [{ level: 81, timestamp: stamp(0) }] }); });
  assert.deepEqual(await (await call('/health/battery')).json(), { level: .81, measuredAt: stamp(0) });
  const offline = await fixture(t, async () => { throw Error('not supported'); });
  assert.deepEqual(await (await offline('/health/battery')).json(), { level: null, measuredAt: null });
});

test('all glasses prompts and canonical installable skills agree on stock-first page guidance', async () => {
  for (const prompt of [GLASSES_CONTEXT, GLASSES_SUFFIX]) assert.ok(prompt.includes(GLASSES_PAGE_GUIDANCE));
  for (const relative of ['../../../skills/glasses/SKILL.md', '../hermes-skill/glasses/SKILL.md']) assert.ok((await readFile(new URL(relative, import.meta.url), 'utf8')).includes(GLASSES_PAGE_GUIDANCE));
  for (const [name, status] of [['request_user_input', 'waiting'], ['remember', 'remembering'], ['store_note', 'saving'], ['spawn_agent', 'delegating'], ['web_search', 'searching'], ['reason', 'thinking']]) assert.equal(toolStatus(name), status);
});

test('Oura heart-rate window returns sorted in-window samples and rejects bad windows', async t => {
  let seen;
  const call = await fixture(t, async url => { seen = new URL(url); return json({ data: [
    { bpm: 120, timestamp: stamp(-60000) }, { bpm: 95, timestamp: stamp(-120000) }, { bpm: 400, timestamp: stamp(-90000) }, { bpm: 80, timestamp: stamp(-7200000) }] }); });
  const body = await (await call(`/health/heartrate?start=${stamp(-600000)}&end=${stamp(0)}`)).json();
  assert.equal(seen.pathname, '/v2/usercollection/heartrate');
  assert.equal(seen.searchParams.get('start_datetime'), stamp(-600000).replace('.000Z', 'Z'));
  assert.deepEqual(body, { samples: [{ t: stamp(-120000), bpm: 95 }, { t: stamp(-60000), bpm: 120 }] });
  assert.equal((await call(`/health/heartrate?start=${stamp(0)}&end=${stamp(-1)}`)).status, 400);
  assert.equal((await call('/health/heartrate?start=nope&end=nope')).status, 400);
  assert.equal((await call(`/health/heartrate?start=${stamp(-2 * 86400000)}&end=${stamp(0)}`)).status, 400);
});
