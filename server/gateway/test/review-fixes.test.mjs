// Regressions for the independent review findings on the gateway.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { streamUrl } from '../apis/glasses/routes/abs.mjs';
import { createEventBus } from '../apis/glasses/lib/context.mjs';
import { cached } from '../apis/glasses/lib/gateway.mjs';
import { createStore } from '../lib/store.mjs';
import { readBody } from '../lib/web.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'joyapi-review-'));

test('ABS stream URL cannot be redirected to another origin by a crafted upstream path', () => {
  const ctx = { config: { absUrl: 'https://audible.example' } };
  const evil = streamUrl(ctx, 'https://internal.invalid//attacker.invalid/audio?x=1', 'TOKEN');
  assert.equal(new URL(evil).origin, 'https://audible.example');
  assert.equal(new URL(evil).searchParams.get('token'), 'TOKEN');
  assert.equal(new URL(evil).searchParams.get('x'), '1');
  assert.equal(new URL(streamUrl(ctx, '/api/items/1/file/2', 'T')).pathname, '/api/items/1/file/2');
});

test('event cursor only covers delivered events, so a racing publish is not skipped', async () => {
  const bus = createEventBus(createStore(tmp()));
  bus.publish('a');
  const first = await bus.wait(0, 0);
  bus.publish('b'); // published after the snapshot but before the response is built
  const cursor = first.length ? first.at(-1).seq : 0;
  assert.equal(cursor, 1);
  const next = await bus.wait(cursor, 0);
  assert.deepEqual(next.map((e) => e.type), ['b']);
});

test('store.flushed rejects when the latest write failed', async () => {
  const dir = tmp();
  const store = createStore(dir);
  store.put('docs', { id: 'ok', title: 'fine' });
  await store.flushed();
  fs.rmSync(dir, { recursive: true, force: true });
  fs.writeFileSync(dir, 'not a directory'); // further writes now fail
  store.put('docs', { id: 'lost', title: 'x' });
  await assert.rejects(store.flushed(), /Storage unavailable/);
  fs.rmSync(dir, { force: true });
});

test('cache prunes expired entries instead of growing forever', async () => {
  let now = 0;
  const ctx = { now: () => now };
  for (let i = 0; i < 300; i++) await cached(ctx, 'k' + i, 10, async () => i);
  now = 1000;
  await cached(ctx, 'fresh', 10, async () => 1);
  // All 300 earlier entries expired and were pruned; a fresh call recomputes.
  let calls = 0;
  await cached(ctx, 'k1', 10, async () => { calls++; return 1; });
  assert.equal(calls, 1);
});

test('readBody enforces size and deadline', async () => {
  await assert.rejects(readBody(Readable.from([Buffer.alloc(10), Buffer.alloc(10)]), 15), /too large/);
  const stalled = new Readable({ read() {} });
  stalled.push(Buffer.alloc(4));
  await assert.rejects(readBody(stalled, 1000, 50), /timed out/);
});

test('Beeper never shows a raw Matrix ID as a sender or chat name', async () => {
  const { sender, chatName, resolveSender } = await import('../apis/glasses/lib/beeper.mjs');
  const id = '@instagramgo_123456:beeper.local';
  const chat = { id: 'c1', type: 'group', title: 'the boys', participants: { items: [{ id: '@a:beeper.local', fullName: 'Ann Lee' }], total: 129, hasMore: true } };
  assert.equal(sender({ senderID: id, senderName: id }, chat), 'Someone');
  assert.equal(sender({ senderID: '@a:beeper.local', senderName: '@a:beeper.local' }, chat), 'Ann Lee');
  assert.equal(chatName(chat), 'the boys');
  assert.equal(chatName({ type: 'group', title: '', participants: { items: [{ id: 'me', isSelf: true }, { id: '1', fullName: 'Ann Lee' }, { id: '2', fullName: 'Bo Li' }, { id: '3', username: 'cy' }, { id: '4', fullName: 'Di' }], total: 6 } }), 'Ann, Bo, cy +2');
  assert.equal(chatName({ type: 'group', title: '!abcdefghijklmnop:beeper.local' }), 'Group chat');
  let asked = '';
  const ctx = { now: () => 0, config: { beeperUrl: 'http://b' }, secret: async () => 't',
    fetch: async (url) => { asked = String(url); return new Response(JSON.stringify({ id: 'c1', participants: { items: [{ id, fullName: 'Real Name' }] } }), { headers: { 'content-type': 'application/json' } }); } };
  assert.equal(await resolveSender(ctx, { senderID: id, senderName: id }, chat), 'Real Name');
  assert.match(asked, /maxParticipantCount=500/);
});

test('a slow but healthy T3 machine is not marked offline by the fail-fast probe', async () => {
  const { http, machineState } = await import('../apis/glasses/lib/t3.mjs');
  const m = { id: 'slow', alias: 'slow', url: 'https://slow.example', secret: 'S' };
  const ctx = { now: () => Date.now(), config: {}, secret: async () => 't',
    fetch: async () => { await new Promise(r => setTimeout(r, 2500)); return new Response('{"ok":true}', { headers: { 'content-type': 'application/json' } }); } };
  assert.deepEqual(await http(ctx, m, '/x'), { ok: true });
  assert.equal(machineState(ctx, m).online, true);
});

test('Beeper HTML and Markdown link bodies become plain text', async () => {
  const { plainText, messageText } = await import('../apis/glasses/lib/beeper.mjs');
  const url = 'https://www.instagram.com/reel/AbC123/?id=1&is_sponsored=false';
  assert.equal(plainText(`<p><a href="${url.replace(/&/g, '&amp;')}" rel="noopener noreferrer" target="_blank">${url.replace(/&/g, '&amp;')}</a></p>`), url);
  assert.equal(plainText(`[${url}](${url}&amp;x=1)`), url + '&x=1');
  assert.equal(plainText('see <b>this</b><br>now &amp; <a href="https://a.example/x">here</a>'), 'see this\nnow & here');
  assert.equal(plainText('2 < 3 and a [note] (not a link)'), '2 < 3 and a [note] (not a link)');
  assert.doesNotMatch(messageText({ text: `<p><a href="${url}" target="_blank">${url}</a></p>` }), /<|HREF|noopener/i);
});

test('final review: plainText keeps non-HTML angle text; ids and short codes are not over-matched', async () => {
  const { plainText, idLike, scrubIds, chatName } = await import('../apis/glasses/lib/beeper.mjs');
  assert.equal(plainText('mail me <joe@x.com> if x<y and y>z'), 'mail me <joe@x.com> if x<y and y>z');
  assert.equal(plainText('a&#1;b&#xD800;c&#10;d'), 'a&#1;b&#xD800;c\nd');
  assert.equal(chatName({ type: 'single', network: 'Google Messages', title: '62881' }), '62881');
  assert.equal(chatName({ type: 'single', network: 'Instagram', title: '123456789' }), 'Chat');
  assert.equal(scrubIds('@bob:thanks for that', {}), '@bob:thanks for that');
  assert.equal(scrubIds('hi @instagramgo_1:beeper.local', {}), 'hi @someone');
});
test('final review: an ordinary HTTP error does not take a T3 machine offline', async () => {
  const { http, machineState } = await import('../apis/glasses/lib/t3.mjs');
  const m = { id: 'm1', alias: 'm1', url: 'https://m1.example', secret: 'S' };
  let status = 500;
  const ctx = { now: () => Date.now(), config: {}, secret: async () => 't', fetch: async () => new Response('{"ok":true}', { status, headers: { 'content-type': 'application/json' } }) };
  await assert.rejects(http(ctx, m, '/x'));
  status = 200;
  assert.deepEqual(await http(ctx, m, '/x'), { ok: true });
  assert.equal(machineState(ctx, m).online, true);
});
