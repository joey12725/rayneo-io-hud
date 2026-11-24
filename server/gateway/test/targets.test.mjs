// Delivery targets (apis/glasses/lib/targets.mjs): directory, webhook (retries, backoff, HMAC and Bearer), hermes,
// the extension point and validation.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, rm, readdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHmac } from 'node:crypto';
import { createTargets, registerTargetKind, validateTargets, resolveTargets, targetKinds, markdown } from '../apis/glasses/lib/targets.mjs';

const T0 = Date.parse('2026-11-16T18:00:00Z');
const item = (over = {}) => ({ id: '1b2c3d4e-0000-4000-8000-000000000001', kind: 'memo', title: 'Grocery list', startedAt: new Date(T0).toISOString(), endedAt: new Date(T0 + 7000).toISOString(),
  tz: 'Europe/Berlin', speechSec: 7, device: 'glasses', fixes: [], audio: null,
  transcript: { text: 'Eggs, milk and bread.', turns: [{ speaker: 'Sam', start: 0, end: 3, at: new Date(T0).toISOString(), text: 'Eggs, milk and bread.' }] }, ...over });
const ctx = (over = {}) => ({ now: () => T0, log: () => {}, config: { dataDir: '/nonexistent', owner: { name: 'Sam' }, assistant: { name: 'Hermes' } },
  secret: async name => ({ HOOK_SECRET: 'hmac-key', HOOK_TOKEN: 'bearer-token' }[name] ?? Promise.reject(Object.assign(new Error('Secret not found'), { code: 'SECRET_NOT_FOUND' }))), ...over });

async function tempDir(t) { const dir = await mkdtemp(path.join(tmpdir(), 'targets-')); t.after(() => rm(dir, { recursive: true, force: true })); return dir; }
async function server(t, handler) {
  const requests = [];
  const s = http.createServer(async (req, res) => {
    const chunks = []; for await (const c of req) chunks.push(c);
    const r = { method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks) };
    requests.push(r);
    const [status, body = ''] = await handler(r, requests.length);
    res.writeHead(status, { 'Content-Type': 'text/plain' }); res.end(body);
  });
  await new Promise(resolve => s.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { s.closeAllConnections(); s.close(resolve); }));
  return { url: `http://127.0.0.1:${s.address().port}/hook`, requests };
}

test('directory target writes the audio, a JSON record and a Markdown transcript under the day folder', async t => {
  const dir = await tempDir(t), audio = path.join(dir, 'in.ogg');
  await writeFile(audio, Buffer.alloc(32, 9));
  const targets = createTargets(ctx({ config: { dataDir: dir } }), { targets: [{ id: 'local', name: 'Folder', kind: 'directory', path: 'recordings' }], routes: { memo: ['local'] } });
  const out = await targets.deliver('local', item({ audio: { file: audio, ext: 'ogg', mime: 'audio/ogg', bytes: 32, sha256: 'x'.repeat(64) } }));
  const day = path.join(dir, 'recordings', '2026-11-16');
  const files = (await readdir(day)).sort();
  assert.equal(files.length, 3); assert.deepEqual(files.map(f => f.split('.').at(-1)), ['json', 'md', 'ogg']);
  assert.deepEqual(out.files.sort(), files.map(f => path.join('2026-11-16', f)).sort());
  const record = JSON.parse(await readFile(path.join(day, files[0]), 'utf8'));
  assert.equal(record.transcript.text, 'Eggs, milk and bread.'); assert.equal(record.audio.file, undefined, 'no local paths leave the gateway');
  assert.match(await readFile(path.join(day, files[1]), 'utf8'), /^# Grocery list\n[\s\S]*\*\*Sam\*\* \(2026-11-16T18:00:00.000Z\): Eggs, milk and bread\./);
  assert.equal((await readFile(path.join(day, files[2]))).length, 32);
  // Redelivery overwrites the same files (idempotent).
  await targets.deliver('local', item({ audio: { file: audio, ext: 'ogg', mime: 'audio/ogg', bytes: 32, sha256: 'x'.repeat(64) } }));
  assert.equal((await readdir(day)).length, 3);
  const noAudio = createTargets(ctx({ config: { dataDir: dir } }), { targets: [{ id: 'txt', kind: 'directory', path: 'text', audio: false, formats: ['md'] }] });
  await noAudio.deliver('txt', item({ audio: { file: audio, ext: 'ogg', mime: 'audio/ogg', bytes: 32, sha256: 'y' } }));
  assert.deepEqual((await readdir(path.join(dir, 'text', '2026-11-16'))).map(f => f.split('.').at(-1)), ['md']);
});

test('webhook target retries 5xx and network errors with exponential backoff, then succeeds; signs with HMAC', async t => {
  const hook = await server(t, (_, n) => n < 3 ? [503, 'busy'] : [200, 'ok']);
  const sleeps = [];
  const targets = createTargets(ctx(), { targets: [{ id: 'hook', name: 'Notes', kind: 'webhook', url: hook.url, retries: 4, backoffMs: 100, auth: { type: 'hmac', secret: 'HOOK_SECRET' }, headers: { 'X-Custom': 'yes' } }] },
    { sleep: async ms => { sleeps.push(ms); } });
  const result = await targets.deliver('hook', item());
  assert.deepEqual(result, { status: 200 });
  assert.equal(hook.requests.length, 3); assert.deepEqual(sleeps, [100, 200]);
  const r = hook.requests[2], body = r.body.toString('utf8'), payload = JSON.parse(body);
  assert.equal(r.method, 'POST'); assert.equal(r.headers['content-type'], 'application/json'); assert.equal(r.headers['x-custom'], 'yes');
  assert.equal(payload.event, 'memo.ready'); assert.equal(payload.target, 'hook'); assert.equal(payload.recording.transcript.text, 'Eggs, milk and bread.');
  assert.equal(r.headers['x-delivery-id'], 'memo-' + item().id);
  const ts = r.headers['x-timestamp'];
  assert.equal(ts, String(T0 / 1000));
  assert.equal(r.headers['x-signature'], 'sha256=' + createHmac('sha256', 'hmac-key').update(`${ts}.${body}`).digest('hex'));
});

test('webhook target: bearer auth, multipart audio, 4xx fails at once, retries are bounded', async t => {
  const dir = await tempDir(t), audio = path.join(dir, 'a.wav'); await writeFile(audio, Buffer.from('RIFFsynthetic'));
  const ok = await server(t, () => [204]);
  const targets = createTargets(ctx(), { targets: [{ id: 'm', kind: 'webhook', url: ok.url, audio: 'multipart', auth: { type: 'bearer', secret: 'HOOK_TOKEN' } }] });
  await targets.deliver('m', item({ audio: { file: audio, ext: 'wav', mime: 'audio/wav', bytes: 13, sha256: 'z' } }));
  const r = ok.requests[0];
  assert.equal(r.headers.authorization, 'Bearer bearer-token'); assert.match(r.headers['content-type'], /^multipart\/form-data; boundary=/);
  const text = r.body.toString('latin1');
  assert.match(text, /name="metadata"; filename="metadata.json"/); assert.match(text, /name="audio"; filename="[^"]+\.wav"/); assert.match(text, /RIFFsynthetic/);

  const refused = await server(t, () => [422, 'bad']);
  const sleeps = [];
  const once = createTargets(ctx(), { targets: [{ id: 'r', kind: 'webhook', url: refused.url, retries: 3, backoffMs: 1 }] }, { sleep: async ms => { sleeps.push(ms); } });
  await assert.rejects(once.deliver('r', item()), e => e.permanent === true && /HTTP 422/.test(e.message));
  assert.equal(refused.requests.length, 1); assert.deepEqual(sleeps, []);

  const down = await server(t, () => [500]);
  const bounded = createTargets(ctx(), { targets: [{ id: 'd', kind: 'webhook', url: down.url, retries: 2, backoffMs: 10 }] }, { sleep: async () => {} });
  await assert.rejects(bounded.deliver('d', item()), e => e.permanent === false && /HTTP 500/.test(e.message));
  assert.equal(down.requests.length, 3);
  const unreachable = createTargets(ctx(), { targets: [{ id: 'u', kind: 'webhook', url: 'http://127.0.0.1:1/hook', retries: 1, backoffMs: 0 }] }, { sleep: async () => {} });
  await assert.rejects(unreachable.deliver('u', item()), /Webhook unreachable/);
  const noSecret = createTargets(ctx(), { targets: [{ id: 'n', kind: 'webhook', url: ok.url, auth: { type: 'bearer', secret: 'MISSING' } }] });
  await assert.rejects(noSecret.deliver('n', item()), /Secret not found/);
});

test('hermes target posts the transcript into a session through the Hermes adapter', async () => {
  const calls = [];
  const hermes = {
    createSession: async () => { calls.push(['create']); return 'sess-1'; },
    runTurn: async (_ctx, sessionId, message) => { calls.push(['turn', sessionId, message]); return { sessionId, text: 'Saved.' }; },
  };
  const targets = createTargets(ctx(), { targets: [{ id: 'h', name: 'Hermes', kind: 'hermes' }, { id: 'fixed', kind: 'hermes', session: 'notes-session', message: '{{assistant}}: file this {{kind}} for {{owner}}: {{transcript}}' }] }, { hermes });
  assert.deepEqual(await targets.deliver('h', item()), { sessionId: 'sess-1' });
  assert.equal(calls[0][0], 'create'); assert.equal(calls[1][1], 'sess-1');
  assert.match(calls[1][2], /^Sam recorded a memo on the glasses \(2026-11-16T18:00:00.000Z\): "Grocery list"/); assert.match(calls[1][2], /<transcript>\nSam: Eggs, milk and bread\.\n<\/transcript>/);
  await targets.deliver('fixed', item({ kind: 'ambient' }));
  assert.deepEqual(calls.at(-1), ['turn', 'notes-session', 'Hermes: file this conversation for Sam: Sam: Eggs, milk and bread.']);
  await assert.rejects(targets.deliver('h', item({ transcript: null })), e => e.permanent === true);
});

test('webhook target signs every attempt with a fresh timestamp', async t => {
  const hook = await server(t, (_, n) => n < 2 ? [503] : [200]);
  let now = T0;
  const targets = createTargets(ctx({ now: () => now }), { targets: [{ id: 'hook', kind: 'webhook', url: hook.url, retries: 2, backoffMs: 1, auth: { type: 'hmac', secret: 'HOOK_SECRET' } }] },
    { sleep: async () => { now += 600000; } });
  await targets.deliver('hook', item());
  const [first, second] = hook.requests;
  assert.equal(first.headers['x-timestamp'], String(T0 / 1000)); assert.equal(second.headers['x-timestamp'], String((T0 + 600000) / 1000));
  const body = second.body.toString('utf8');
  assert.equal(second.headers['x-signature'], 'sha256=' + createHmac('sha256', 'hmac-key').update(`${second.headers['x-timestamp']}.${body}`).digest('hex'));
});

test('hermes target never posts a transcript twice: an accepted turn counts as delivered, a retry reuses its session', async () => {
  const calls = [];
  let mode = 'refuse';
  const hermes = {
    createSession: async () => { calls.push('create'); return 'sess-' + calls.length; },
    runTurn: async (_ctx, sessionId) => {
      calls.push('turn ' + sessionId);
      if (mode === 'refuse') throw Object.assign(new Error('Hermes could not start the turn'), { status: 502 });
      throw Object.assign(new Error('Hermes reply interrupted; check the session before retrying'), { status: 502, accepted: true });
    },
  };
  const targets = createTargets(ctx(), { targets: [{ id: 'h', kind: 'hermes' }] }, { hermes });
  await assert.rejects(targets.deliver('h', item()), /could not start/);
  mode = 'interrupt';
  assert.deepEqual(await targets.deliver('h', item()), { sessionId: 'sess-1', incomplete: true });
  assert.deepEqual(calls, ['create', 'turn sess-1', 'turn sess-1'], 'one session for both attempts');
});

test('unknown kinds, bad specs and dangling routes are refused with clear messages; registerTargetKind extends', async () => {
  const problems = validateTargets([{ id: 'a', kind: 's3' }, { id: 'a', kind: 'directory' }, { id: 'bad id!', kind: 'webhook', url: 'ftp://x' }, { id: 'w', kind: 'webhook', url: 'https://x', auth: { type: 'basic' } }],
    { memo: ['a', 'ghost'], bogus: [] });
  assert.ok(problems.some(p => /targets\[0\] \(a\): unknown kind "s3" \(known: directory, webhook, hermes/.test(p)), problems.join('\n'));
  assert.ok(problems.some(p => /duplicate id "a"/.test(p)));
  assert.ok(problems.some(p => /directory target needs "path"/.test(p)));
  assert.ok(problems.some(p => /"id" must be/.test(p))); assert.ok(problems.some(p => /webhook target needs "url"/.test(p)));
  assert.ok(problems.some(p => /"auth" must be/.test(p)));
  assert.ok(problems.some(p => /routes.memo: no target with id "ghost"/.test(p))); assert.ok(problems.some(p => /routes.bogus: unknown route/.test(p)));
  assert.throws(() => createTargets(ctx(), { targets: [{ id: 'x', kind: 'nope' }] }), /Invalid delivery targets:[\s\S]*unknown kind "nope"/);
  assert.throws(() => registerTargetKind('Bad Kind', { create() {} }), /Invalid target kind/);
  assert.throws(() => registerTargetKind('nocreate', {}), /needs a create/);

  const seen = [];
  registerTargetKind('memory-test', { validate: spec => spec.bucket ? [] : ['needs "bucket"'], create: spec => ({ deliver: async i => { seen.push([spec.bucket, i.id]); return { ok: true }; } }) });
  assert.ok(targetKinds().includes('memory-test'));
  assert.deepEqual(validateTargets([{ id: 'm', kind: 'memory-test' }]), ['targets[0] (m): memory-test target needs "bucket"']);
  const custom = createTargets(ctx(), { targets: [{ id: 'm', kind: 'memory-test', bucket: 'b1' }], routes: { ambient: ['m'] } });
  assert.deepEqual(custom.list(), [{ id: 'm', name: 'm', kind: 'memory-test' }]);
  assert.deepEqual(custom.forRoute('ambient').map(x => x.id), ['m']); assert.deepEqual(custom.forRoute('memo'), []);
  await custom.deliver('m', item()); assert.deepEqual(seen, [['b1', item().id]]);
  await assert.rejects(custom.deliver('gone', item()), e => e.permanent === true);
});

test('defaults: one local directory target, memo and ambient routed to every target, dictation to none', () => {
  const d = resolveTargets({ dataDir: '/data', targets: null, routes: null });
  assert.deepEqual(d.targets, [{ id: 'local', name: 'Recordings folder', kind: 'directory', path: path.join('/data', 'recordings') }]);
  assert.deepEqual(d.routes, { memo: ['local'], ambient: ['local'], dictation: [] });
  const two = resolveTargets({ targets: [{ id: 'a', kind: 'hermes' }, { id: 'b', name: 'B', kind: 'hermes' }], routes: { ambient: ['b'] } });
  assert.deepEqual(two.routes, { memo: ['a', 'b'], ambient: ['b'], dictation: [] }); assert.equal(two.targets[0].name, 'a');
  assert.match(markdown(item({ transcript: null })), /_No transcript\._/);
});
