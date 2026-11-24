// Recordings from the phone (memos, ambient conversations): upload, transcription, delivery to targets, and People.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createHash } from 'node:crypto';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { parseManifest, realTime, nearestFix, buildTranscript, createConversations } from '../apis/glasses/lib/conversations.mjs';
import { unitVector, parsePeople } from '../apis/glasses/routes/people.mjs';

const T0 = Date.parse('2026-11-16T18:00:00Z');
const iso = ms => new Date(ms).toISOString();
const manifest = (over = {}) => ({ clientId: 'conv-0001', device: 'glasses', startedAt: iso(T0), endedAt: iso(T0 + 20 * 60000), speechSec: 95, tz: 'Europe/Berlin',
  segments: [{ offsetSec: 0, at: iso(T0), durationSec: 40 }, { offsetSec: 40, at: iso(T0 + 12 * 60000), durationSec: 55 }],
  fixes: [{ at: iso(T0), lat: 48.13743, lon: 11.57549, accuracyM: 12 }], ...over });
const memo = (over = {}) => ({ clientId: 'a5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b', device: 'glasses', kind: 'memo', title: 'Recording a Quick Memo',
  startedAt: iso(T0 + 3600000), endedAt: iso(T0 + 3607000), speechSec: 7.2, tz: 'Europe/Berlin',
  segments: [{ offsetSec: 0, at: iso(T0 + 3600000), durationSec: 7.2 }], fixes: [], ...over });

/** A gateway with a stub transcriber and a recording target that the test can make fail. */
async function app(t, { now = () => T0, config = {}, targets, routes } = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'glasses-conv-'));
  const store = createStore(dir, { now });
  const transcribed = [], delivered = [];
  const transcriber = { fail: false, result: { text: 'Hello there. Hi.', segments: [{ start: 1.5, end: 4, text: 'Hello there.' }, { start: 41, end: 44, text: 'Hi.' }] } };
  const transcribe = async (file, mime) => { transcribed.push({ file, mime, bytes: (await readFile(file)).length }); if (transcriber.fail) throw new Error('down'); return transcriber.result; };
  const sink = { fail: false, permanent: false };
  const { registerTargetKind } = await import('../apis/glasses/lib/targets.mjs');
  registerTargetKind('test-sink', { create: () => ({ async deliver(item) {
    if (sink.fail) throw Object.assign(new Error('sink down'), { permanent: sink.permanent });
    delivered.push(item);
  } }) });
  const cfg = { t3Machines: [], dataDir: dir, owner: { name: 'Sam', aliases: [] }, people: { file: path.join(dir, 'people.json') },
    targets: targets ?? [{ id: 'sink', name: 'Test sink', kind: 'test-sink' }], routes: routes ?? { memo: ['sink'], ambient: ['sink'], dictation: [] }, ...config };
  const result = await createApp({ env: { GATEWAY_DATA: dir }, log: () => {}, store, secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone' }[name] ?? 'x') },
    deps: { glasses: { fetch: async () => { throw new Error('No network in tests'); }, now, transcribe, config: cfg } } });
  result.server.listen(0, '127.0.0.1'); await once(result.server, 'listening');
  const base = 'http://127.0.0.1:' + result.server.address().port + '/glasses/v1';
  t.after(async () => { result.server.closeAllConnections(); await new Promise(resolve => result.server.close(resolve)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const call = async (route, { method = 'GET', body, raw, headers = {} } = {}) => {
    const res = await fetch(base + route, { method, headers: { Authorization: 'Bearer phone', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers }, body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined) });
    const text = await res.text();
    return { status: res.status, headers: res.headers, value: text ? JSON.parse(text) : null };
  };
  const row = id => store.get('recordings', id);
  // The worker the gateway built (the same instance the routes use).
  return { call, dir, store, row, transcribed, delivered, transcriber, sink, tick: () => result.contexts.glasses.conversations.tick() };
}

test('manifest validation is strict and rejects short conversations', () => {
  const ok = parseManifest(manifest());
  assert.equal(ok.speechSec, 95); assert.equal(ok.manifest.segments.length, 2); assert.equal(ok.manifest.fixes[0].accuracyM, 12);
  for (const [over, status] of [[{ clientId: 'x' }, 400], [{ device: 'tv' }, 400], [{ startedAt: 'yesterday' }, 400], [{ endedAt: iso(T0 - 1) }, 400],
    [{ speechSec: 10 }, 422], [{ tz: 'Mars/Base' }, 400], [{ segments: [] }, 400], [{ segments: [{ offsetSec: 5, at: iso(T0), durationSec: 1 }] }, 400],
    [{ fixes: [{ at: iso(T0), lat: 91, lon: 0 }] }, 400]]) assert.throws(() => parseManifest(manifest(over)), e => e.status === status, JSON.stringify(over));
});

test('memo manifests: kind memo is accepted down to one second, keeps its title, and is strict', () => {
  const m = parseManifest(memo());
  assert.deepEqual([m.manifest.kind, m.manifest.title, m.speechSec], ['memo', 'Recording a Quick Memo', 7.2]);
  assert.equal(parseManifest(memo({ speechSec: 1, title: undefined })).manifest.title, undefined);
  assert.equal(parseManifest(memo({ title: '   ' })).manifest.title, undefined);
  assert.equal(parseManifest(manifest()).manifest.kind, undefined, 'conversations keep their stored shape');
  for (const [over, status] of [[{ speechSec: 0.9 }, 422], [{ kind: 'note' }, 400], [{ title: 'x'.repeat(121) }, 400], [{ title: 7 }, 400]])
    assert.throws(() => parseManifest(memo(over)), e => e.status === status, JSON.stringify(over));
  assert.throws(() => parseManifest(manifest({ speechSec: 7 })), e => e.status === 422, 'a conversation without kind still needs 30 s');
  assert.throws(() => parseManifest(manifest({ title: 'Not for conversations' })), e => e.status === 400);
});

test('transcript segments are put at their real time (midpoint piece) and nearest location fix', () => {
  const m = parseManifest(manifest());
  assert.equal(realTime(1.5, 4, m.manifest.segments), iso(T0 + 1500));
  // Starts a moment before the boundary at 40 s but mostly after it: placed after the 12-minute gap.
  assert.equal(realTime(39.6, 44, m.manifest.segments), iso(T0 + 12 * 60000));
  assert.equal(realTime(41, 44, m.manifest.segments), iso(T0 + 12 * 60000 + 1000));
  assert.equal(nearestFix(iso(T0 + 60000), m.manifest.fixes).lat, 48.13743);
  assert.equal(nearestFix(iso(T0 + 3600000), m.manifest.fixes), null, 'no fix within 15 minutes');
  const tr = buildTranscript({ text: 'Hello there. Hi.', segments: [{ start: 1.5, end: 4, text: ' Hello there. ' }, { start: 41, end: 44, text: 'Hi.' }, { start: 50, end: 51, text: ' ' }] }, { ...m, speechSec: 95 }, 'Speaker');
  assert.deepEqual(tr.turns.map(x => [x.text, x.at, x.lat ?? null]), [['Hello there.', iso(T0 + 1500), 48.13743], ['Hi.', iso(T0 + 12 * 60000 + 1000), 48.13743]]);
  const plain = buildTranscript({ text: 'Just text.' }, { ...m, speechSec: 95 }, 'Sam');
  assert.deepEqual(plain.turns.map(x => [x.speaker, x.text, x.at]), [['Sam', 'Just text.', iso(T0)]]);
});

test('conversation upload: idempotent manifest, streamed audio with hash, repeat and conflict', async t => {
  const f = await app(t);
  assert.equal((await f.call('/ambient/conversations', { method: 'POST', body: manifest({ speechSec: 10 }) })).status, 422);
  const made = await f.call('/ambient/conversations', { method: 'POST', body: manifest() });
  assert.equal(made.status, 200); assert.equal(made.value.state, 'received'); assert.deepEqual(made.value.deliveries, []);
  assert.equal((await f.call('/ambient/conversations', { method: 'POST', body: manifest() })).value.id, made.value.id);
  const audio = Buffer.alloc(300000, 7), url = `/ambient/conversations/${made.value.id}/audio`;
  assert.equal((await f.call(url, { method: 'PUT', raw: audio, headers: { 'Content-Type': 'text/plain' } })).status, 415);
  assert.equal((await f.call('/ambient/conversations/00000000-0000-4000-8000-000000000000/audio', { method: 'PUT', raw: audio, headers: { 'Content-Type': 'audio/ogg' } })).status, 404);
  const put = await f.call(url, { method: 'PUT', raw: audio, headers: { 'Content-Type': 'audio/ogg' } });
  assert.equal(put.value.state, 'audio');
  const row = f.row(made.value.id);
  assert.equal(row.audioBytes, 300000); assert.equal(row.audioSha256, createHash('sha256').update(audio).digest('hex'));
  assert.deepEqual(await readdir(path.join(f.dir, 'ambient')), [`${made.value.id}.ogg`]);
  assert.equal((await f.call(url, { method: 'PUT', raw: audio, headers: { 'Content-Type': 'audio/ogg' } })).status, 200);
  assert.equal((await f.call(url, { method: 'PUT', raw: Buffer.alloc(10, 1), headers: { 'Content-Type': 'audio/ogg' } })).status, 409);
  assert.deepEqual(await readdir(path.join(f.dir, 'ambient')), [`${made.value.id}.ogg`]);
  assert.deepEqual((await readFile(path.join(f.dir, 'ambient', `${made.value.id}.ogg`))).equals(audio), true);
  assert.equal((await f.call(url.replace('/audio', ''))).value.state, 'audio');
  const status = (await f.call('/ambient/status')).value;
  assert.equal(status.pending, 1); assert.deepEqual(status.targets, [{ id: 'sink', name: 'Test sink', kind: 'test-sink' }]);
  assert.equal('waveFlagged' in status, false);
});

test('audio over the size cap is refused and leaves nothing behind', async t => {
  const f = await app(t, { config: { conversations: { maxAudioBytes: 1000 } } });
  const made = await f.call('/ambient/conversations', { method: 'POST', body: manifest() });
  assert.equal((await f.call(`/ambient/conversations/${made.value.id}/audio`, { method: 'PUT', raw: Buffer.alloc(5000), headers: { 'Content-Type': 'audio/ogg' } })).status, 413);
  assert.deepEqual(await readdir(path.join(f.dir, 'ambient')), []);
  assert.equal(f.row(made.value.id).state, 'received');
});

test('worker transcribes once, delivers to every routed target, reports deliveries, then deletes the audio', async t => {
  let now = T0;
  const f = await app(t, { now: () => now });
  const made = await f.call('/ambient/conversations', { method: 'POST', body: manifest() });
  await f.call(`/ambient/conversations/${made.value.id}/audio`, { method: 'PUT', raw: Buffer.alloc(2000, 1), headers: { 'Content-Type': 'audio/ogg' } });
  await f.tick();
  const view = (await f.call(`/ambient/conversations/${made.value.id}`)).value;
  assert.equal(view.state, 'timed');
  assert.deepEqual(view.deliveries, [{ target: 'sink', name: 'Test sink', state: 'delivered', deliveredAt: iso(T0) }]);
  assert.equal(f.transcribed.length, 1); assert.equal(f.transcribed[0].mime, 'audio/ogg'); assert.equal(f.transcribed[0].bytes, 2000);
  const item = f.delivered[0];
  assert.equal(item.kind, 'ambient'); assert.equal(item.id, made.value.id); assert.match(item.title, /^Conversation · Nov 16, 7:00 PM$/);
  assert.equal(item.transcript.turns[1].at, iso(T0 + 12 * 60000 + 1000)); assert.equal(item.transcript.turns[0].lat, 48.13743);
  assert.deepEqual(await readdir(path.join(f.dir, 'ambient')), []);
  assert.equal((await f.call('/ambient/status')).value.uploadedToday, 1);
});

test('a target that is down is retried with backoff; a refusal fails only that delivery', async t => {
  let now = T0;
  const f = await app(t, { now: () => now, config: { conversations: { maxAttempts: 2 } },
    targets: [{ id: 'sink', name: 'Test sink', kind: 'test-sink' }, { id: 'files', name: 'Folder', kind: 'directory', path: 'out' }], routes: { memo: ['sink', 'files'], ambient: ['sink'] } });
  const made = await f.call('/ambient/conversations', { method: 'POST', body: memo() });
  await f.call(`/ambient/conversations/${made.value.id}/audio`, { method: 'PUT', raw: Buffer.alloc(100, 2), headers: { 'Content-Type': 'audio/ogg' } });
  f.sink.fail = true;
  await f.tick();
  let view = (await f.call(`/ambient/conversations/${made.value.id}`)).value;
  assert.equal(view.state, 'uploaded');
  assert.deepEqual(view.deliveries.map(d => [d.target, d.state]), [['sink', 'pending'], ['files', 'delivered']]);
  assert.equal(Date.parse(f.row(made.value.id).nextAttemptAt), now + 2 * 60000);
  await f.tick(); assert.equal(f.row(made.value.id).deliveries[0].attempts, 1, 'not retried before the backoff');
  now += 2 * 60000; await f.tick();
  view = (await f.call(`/ambient/conversations/${made.value.id}`)).value;
  assert.equal(view.state, 'timed', 'one target delivered, so the recording is done');
  assert.deepEqual(view.deliveries.map(d => [d.target, d.state, d.error ?? null]), [['sink', 'failed', 'sink down'], ['files', 'delivered', null]]);
  assert.equal(f.transcribed.length, 1, 'transcribed once, not per attempt');
  const day = await readdir(path.join(f.dir, 'out'));
  assert.deepEqual((await readdir(path.join(f.dir, 'out', day[0]))).map(n => n.split('.').at(-1)).sort(), ['json', 'md', 'ogg']);
});

test('every target refusing fails the recording; transcription outages back off and recover', async t => {
  let now = T0;
  const f = await app(t, { now: () => now });
  const a = await f.call('/ambient/conversations', { method: 'POST', body: memo() });
  await f.call(`/ambient/conversations/${a.value.id}/audio`, { method: 'PUT', raw: Buffer.alloc(100, 3), headers: { 'Content-Type': 'audio/ogg' } });
  f.sink.fail = true; f.sink.permanent = true;
  await f.tick();
  const failed = (await f.call(`/ambient/conversations/${a.value.id}`)).value;
  assert.equal(failed.state, 'failed'); assert.match(failed.error, /Test sink: sink down/);
  assert.deepEqual(await readdir(path.join(f.dir, 'ambient')), [], 'the audio is freed');

  f.sink.fail = false; f.transcriber.fail = true;
  const b = await f.call('/ambient/conversations', { method: 'POST', body: memo({ clientId: 'memo-second-1' }) });
  await f.call(`/ambient/conversations/${b.value.id}/audio`, { method: 'PUT', raw: Buffer.alloc(100, 4), headers: { 'Content-Type': 'audio/ogg' } });
  await f.tick();
  assert.equal(f.row(b.value.id).state, 'audio'); assert.equal(f.row(b.value.id).attempts, 1);
  f.transcriber.fail = false; now += 2 * 60000; await f.tick();
  assert.equal(f.row(b.value.id).state, 'timed');
  assert.equal(f.delivered.at(-1).transcript.turns[0].speaker, 'Sam', 'memo lines are the owner speaking');
});

test('memos go before older conversations, keep their title, and the pending cap spares memos', async t => {
  const f = await app(t, { config: { conversations: { maxPending: 1 } } });
  const conv = await f.call('/ambient/conversations', { method: 'POST', body: manifest() });
  await f.call(`/ambient/conversations/${conv.value.id}/audio`, { method: 'PUT', raw: Buffer.alloc(100, 3), headers: { 'Content-Type': 'audio/ogg' } });
  const made = await f.call('/ambient/conversations', { method: 'POST', body: memo() });
  assert.equal(made.status, 200, 'a memo is not blocked by the pending cap'); assert.equal(made.value.kind, 'memo');
  assert.equal((await f.call('/ambient/conversations', { method: 'POST', body: manifest({ clientId: 'conv-0009' }) })).status, 429);
  const wav = Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(40, 1), Buffer.alloc(1000, 2)]);
  const put = await f.call(`/ambient/conversations/${made.value.id}/audio`, { method: 'PUT', raw: wav, headers: { 'Content-Type': 'audio/wav' } });
  assert.equal(put.status, 200); assert.equal(put.value.state, 'audio');
  await f.tick();
  assert.deepEqual(f.delivered.map(i => [i.kind, i.title]), [['memo', 'Recording a Quick Memo'], ['ambient', 'Conversation · Nov 16, 7:00 PM']]);
  assert.equal(f.transcribed[0].mime, 'audio/wav');
});

test('a recording with no routed targets finishes without delivering anything', async t => {
  const f = await app(t, { routes: { memo: [], ambient: ['sink'] } });
  const made = await f.call('/ambient/conversations', { method: 'POST', body: memo() });
  await f.call(`/ambient/conversations/${made.value.id}/audio`, { method: 'PUT', raw: Buffer.alloc(100, 3), headers: { 'Content-Type': 'audio/ogg' } });
  await f.tick();
  const view = (await f.call(`/ambient/conversations/${made.value.id}`)).value;
  assert.equal(view.state, 'timed'); assert.deepEqual(view.deliveries, []); assert.equal(f.delivered.length, 0);
});

const vector = seed => Array.from(unitVector(Array.from({ length: 256 }, (_, i) => Math.sin(seed * (i + 1)))));
test('people.json: voices, ETag, heard events, recent cards and mutes', async t => {
  const f = await app(t);
  assert.deepEqual((await f.call('/people/recent')).value, { heard: [], recent: [] }, 'no file: empty');
  assert.deepEqual((await f.call('/people/voices')).value.people, []);
  await writeFile(path.join(f.dir, 'people.json'), JSON.stringify({ people: [
    { id: 'alex', name: 'Alex Example', notes: 'Met at the lake.', voices: [{ id: 'alex-1', embedding: vector(1) }] },
    { id: 'me', name: 'Sam', me: true, voices: [{ id: 'me-1', embedding: Buffer.from(Float32Array.from(vector(2)).buffer).toString('base64') }] },
    { id: 'robin', name: 'Robin Sample', where: 'Bookshop', seenAt: iso(T0 - 86400000), notes: 'Lends books.' },
    { id: 'bad id with spaces', name: 'Nope' }] }));
  const first = await f.call('/people/voices');
  assert.equal(first.value.dim, 256);
  assert.deepEqual(first.value.people.map(p => [p.id, p.name, p.me, p.muted, p.profiles.length]), [['alex', 'Alex Example', false, false, 1], ['me', 'Sam', true, false, 1], ['robin', 'Robin Sample', false, false, 0]]);
  assert.equal(Buffer.from(first.value.people[0].profiles[0].embedding, 'base64').length, 1024);
  assert.equal((await f.call('/people/voices', { headers: { 'If-None-Match': first.headers.get('etag') } })).status, 304);

  const event = (id, personId, profileId, at) => ({ id, personId, profileId, at: iso(at), confidence: 0.7, lat: 48.13743, lon: 11.57549 });
  const posted = await f.call('/people/heard', { method: 'POST', body: { events: [event('evt-00001', 'alex', 'alex-1', T0 - 60000), event('evt-00002', 'me', 'me-1', T0),
    event('evt-00004', 'nobody', 'alex-1', T0), event('evt-00005', 'alex', 'other-1', T0)] } });
  assert.equal(posted.value.stored, 1, 'own voice, unknown person and a mismatched profile are dropped');
  for (const over of [{ at: iso(T0 + 3600000) }, { confidence: 1e39 }]) assert.equal((await f.call('/people/heard', { method: 'POST', body: { events: [{ ...event('evt-00009', 'alex', 'alex-1', T0), ...over }] } })).status, 400);
  const recent = (await f.call('/people/recent')).value;
  assert.deepEqual(recent.heard, [{ id: 'alex', name: 'Alex Example', where: '48.1374,11.5755', seenAt: iso(T0 - 60000), notes: 'Met at the lake.' }]);
  assert.deepEqual(recent.recent.map(p => p.id), ['alex', 'robin']);
  assert.deepEqual((await f.call('/people/robin')).value, { id: 'robin', name: 'Robin Sample', where: 'Bookshop', seenAt: iso(T0 - 86400000), notes: 'Lends books.', muted: false });
  assert.equal((await f.call('/people/nobody')).status, 404);

  assert.equal((await f.call('/people/alex/mute', { method: 'POST', body: { muted: 'yes' } })).status, 400);
  assert.equal((await f.call('/people/alex/mute', { method: 'POST', body: { muted: true } })).value.muted, true);
  assert.equal((await f.call('/people/nobody/mute', { method: 'POST', body: { muted: true } })).status, 404);
  assert.deepEqual((await f.call('/people/voices')).value.people.map(p => p.muted), [true, false, false]);
  assert.deepEqual((await f.call('/people/recent')).value.recent.map(p => p.id), ['robin']);
  await f.call('/people/alex/mute', { method: 'POST', body: { muted: false } });
  assert.equal((await f.call('/people/recent')).value.recent.length, 2);
});

test('parsePeople accepts a bare array and skips unusable voices', () => {
  const p = parsePeople([{ id: 'a', name: 'A', voices: [{ id: 'v1', embedding: [1, 2] }, { id: 'v2', embedding: vector(3) }] }, { name: 'no id' }]);
  assert.equal(p.people.length, 1); assert.deepEqual(p.people[0].voices.map(v => v.id), ['v2']); assert.equal(p.dim, 256);
});

test('createConversations works without targets and reports them in status', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'glasses-conv-bare-'));
  try {
    const store = createStore(dir);
    const conv = createConversations({ store, now: Date.now, log: () => {}, config: { dataDir: dir } });
    const status = await conv.status();
    assert.deepEqual(status.targets, []); assert.equal(status.pending, 0);
    await store.flushed();
  } finally { await rm(dir, { recursive: true, force: true }); }
});
