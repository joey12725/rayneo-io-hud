// Agent control: phone link (WS), MCP endpoint, REST mirror and per-turn context. No network, no hardware.
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { connectWebSocket } from '../lib/ws.mjs';
import { createAgentLink, validateArgs, cleanManifest } from '../apis/glasses/lib/agent-link.mjs';
import { glassesNow } from '../apis/glasses/lib/hermes.mjs';
import { toolResult, GLASSES_PREFIX } from '../apis/glasses/lib/mcp.mjs';

const NOW = Date.parse('2026-11-17T18:00:00Z');
const MANIFEST = { v: 1, hash: 'h1', ops: [
  { name: 'glasses.get_context', title: 'Read glasses context', description: 'What is on the glasses now.', risk: 'read', inputSchema: { type: 'object', properties: {} } },
  { name: 'glasses.press', title: 'Press a button', description: 'Press a remote button.', risk: 'navigate', timeoutMs: 500,
    inputSchema: { type: 'object', properties: { button: { type: 'string', enum: ['y', 'b', 'up'] }, times: { type: 'integer', minimum: 1, maximum: 10 } }, required: ['button'] } },
  { name: 'glasses.type_text', title: 'Type text', description: 'Send text to the current target.', risk: 'send', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } },
  { name: 'joy.read_reply', title: 'Read Hermes reply', description: 'Hidden from tools.', risk: 'read', tool: false },
  { name: 'BAD NAME', description: 'dropped' },
] };

async function fixture(t, { store, dir, keep = false, config = {} } = {}) {
  dir ??= await mkdtemp(path.join(tmpdir(), 'agent-'));
  store ??= createStore(dir, { now: () => NOW });
  const app = await createApp({ env: {}, store, log: () => {},
    secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone', GLASSES_AGENT_TOKEN: 'joy' }[name] ?? 'upstream') },
    deps: { glasses: { now: () => NOW, fetch: async () => { throw new Error('No network in tests'); }, config: { t3Machines: [], ...config }, agent: { graceMs: 20 } } } });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  const port = app.server.address().port, base = `http://127.0.0.1:${port}/glasses/v1`;
  t.after(async () => {
    for (const ws of app.websockets) ws.terminate();
    app.server.closeAllConnections(); await new Promise(resolve => app.server.close(resolve));
    await store.flushed(); if (!keep) await rm(dir, { recursive: true, force: true });
  });
  const call = (route, { method = 'GET', body, token = 'joy', headers = {} } = {}) => fetch(base + route, { method,
    headers: { Authorization: 'Bearer ' + token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers },
    ...(body !== undefined ? { body: typeof body === 'string' ? body : JSON.stringify(body) } : {}) });
  const rpc = async (method, params, id = 1) => (await call('/mcp', { method: 'POST', body: { jsonrpc: '2.0', id, method, params } })).json();
  const phone = async (answer, { manifest = MANIFEST } = {}) => {
    const ws = await connectWebSocket(`ws://127.0.0.1:${port}/glasses/v1/agent/link`, { headers: { Authorization: 'Bearer phone' } });
    t.after(() => ws.terminate());
    const frames = [];
    let ready;
    const readyP = new Promise(resolve => { ready = resolve; });
    ws.on('message', data => {
      const msg = JSON.parse(data.toString()); frames.push(msg);
      if (msg.t === 'ready') ready(msg);
      if (msg.t === 'call' && answer) Promise.resolve(answer(msg)).then(reply => reply && ws.send(JSON.stringify({ t: 'result', id: msg.id, ...reply })));
    });
    ws.send(JSON.stringify({ t: 'hello', v: 1, app: 'test', manifest }));
    await readyP;
    return { ws, frames, calls: () => frames.filter(f => f.t === 'call') };
  };
  return { ...app, base, call, rpc, phone, store, dir, port };
}

test('MCP initialize negotiates the protocol version and notifications get 202', async t => {
  const f = await fixture(t);
  const init = await f.rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'hermes' } });
  assert.equal(init.result.protocolVersion, '2025-03-26');
  assert.equal(init.result.serverInfo.name, 'glasses');
  assert.ok(init.result.capabilities.tools);
  assert.equal((await f.rpc('initialize', { protocolVersion: '1999-01-01' })).result.protocolVersion, '2025-06-18');
  const note = await f.call('/mcp', { method: 'POST', body: { jsonrpc: '2.0', method: 'notifications/initialized' } });
  assert.equal(note.status, 202); assert.equal(await note.text(), '');
  assert.deepEqual((await f.rpc('ping')).result, {});
  assert.equal((await f.rpc('resources/list')).error.code, -32601);
  const parse = await f.call('/mcp', { method: 'POST', body: '{not json' });
  assert.equal(parse.status, 400); assert.equal((await parse.json()).error.code, -32700);
  const batch = await (await f.call('/mcp', { method: 'POST', body: [{ jsonrpc: '2.0', id: 1, method: 'ping' }, { jsonrpc: '2.0', method: 'notifications/x' }] })).json();
  assert.deepEqual(batch, [{ jsonrpc: '2.0', id: 1, result: {} }]);
});

test('MCP is Hermes-scoped, stateless POST only; the phone link is phone-scoped', async t => {
  const f = await fixture(t);
  assert.equal((await f.call('/mcp', { method: 'POST', token: 'phone', body: { jsonrpc: '2.0', id: 1, method: 'ping' } })).status, 403);
  assert.equal((await f.call('/mcp', { method: 'POST', token: '', body: {} })).status, 401);
  assert.equal((await f.call('/mcp')).status, 405);
  assert.equal((await f.call('/mcp', { method: 'DELETE' })).status, 405);
  assert.equal((await f.call('/agent/ops', { token: 'phone' })).status, 403);
  const url = f.base.replace('http:', 'ws:') + '/agent/link';
  await assert.rejects(connectWebSocket(url, { headers: { Authorization: 'Bearer joy' } }), { status: 403 });
  await assert.rejects(connectWebSocket(url), { status: 401 });
});

test('offline phone: native tools listed, glasses tools fail clearly and nothing is queued', async t => {
  const f = await fixture(t);
  const names = (await f.rpc('tools/list')).result.tools.map(x => x.name);
  assert.deepEqual(names, ['glasses_notify', 'glasses_push_doc', 'glasses_link_status', 'glasses_beeper_pins']);
  const res = (await f.rpc('tools/call', { name: 'glasses_get_context', arguments: {} })).result;
  assert.equal(res.isError, true); assert.match(res.content[0].text, /^offline: The phone app is not connected/);
  const rest = await f.call('/agent/call', { method: 'POST', body: { op: 'glasses.press', args: { button: 'y' } } });
  assert.equal(rest.status, 503); assert.equal((await rest.json()).code, 'offline');
  // Connecting afterwards never replays the earlier calls.
  const p = await f.phone(() => ({ ok: true }));
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(p.calls().length, 0);
  const status = (await f.rpc('tools/call', { name: 'glasses_link_status' })).result;
  assert.equal(status.structuredContent.online, true); assert.equal(status.structuredContent.ops, 4);
});

test('tools/list reflects the connected phone manifest with glasses-prefixed descriptions and hints', async t => {
  const f = await fixture(t);
  await f.phone();
  const tools = (await f.rpc('tools/list')).result.tools;
  const byName = Object.fromEntries(tools.map(x => [x.name, x]));
  assert.ok(byName.glasses_get_context && byName.glasses_press && byName.glasses_type_text);
  assert.equal(byName.joy_read_reply, undefined, 'tool:false ops are not MCP tools');
  assert.equal(tools.some(x => /BAD/.test(x.name)), false);
  for (const tool of tools) assert.ok(tool.description.startsWith(GLASSES_PREFIX), tool.name);
  assert.equal(byName.glasses_get_context.annotations.readOnlyHint, true);
  assert.equal(byName.glasses_type_text.annotations.destructiveHint, true);
  assert.match(byName.glasses_type_text.description, /confirm on the glasses/);
  assert.equal(byName.glasses_press.inputSchema.additionalProperties, false);
  const ops = await (await f.call('/agent/ops')).json();
  assert.equal(ops.online, true); assert.equal(ops.manifestHash, 'h1'); assert.equal(ops.ops.length, 4);
});

test('tools/call relays to the phone and returns its real result; args are validated first', async t => {
  const f = await fixture(t);
  const p = await f.phone(msg => msg.op === 'glasses.press'
    ? { ok: true, message: 'Pressed y', data: { screen: { id: 'beeper' } } }
    : { ok: false, code: 'unavailable', message: 'This screen has no text input' });
  const ok = (await f.rpc('tools/call', { name: 'glasses_press', arguments: { button: 'y', times: 2 } })).result;
  assert.equal(ok.isError, false);
  assert.equal(ok.content[0].text, 'Pressed y\n{"screen":{"id":"beeper"}}');
  assert.deepEqual(ok.structuredContent, { screen: { id: 'beeper' } });
  const sent = p.calls()[0];
  assert.equal(sent.op, 'glasses.press'); assert.deepEqual(sent.args, { button: 'y', times: 2 });
  assert.equal(sent.origin.channel, 'other'); assert.equal(sent.deadlineMs, 500);
  const fail = (await f.rpc('tools/call', { name: 'glasses_type_text', arguments: { text: 'hi' } })).result;
  assert.equal(fail.isError, true); assert.equal(fail.content[0].text, 'unavailable: This screen has no text input');
  assert.equal(p.calls()[1].deadlineMs, 10000 + 20000, 'send-risk ops get the confirm window');
  for (const args of [{}, { button: 'z' }, { button: 'y', times: 11 }, { button: 'y', extra: 1 }, { button: 1 }]) {
    const bad = (await f.rpc('tools/call', { name: 'glasses_press', arguments: args })).result;
    assert.equal(bad.isError, true); assert.match(bad.content[0].text, /^invalid_args: /);
  }
  assert.equal(p.calls().length, 2, 'invalid calls never reach the phone');
  assert.equal((await f.rpc('tools/call', { name: 'nope_tool' })).error.code, -32602);
  // REST mirror, by op or tool name.
  const rest = await f.call('/agent/call', { method: 'POST', body: { op: 'glasses.press', args: { button: 'up' } } });
  assert.equal(rest.status, 200); assert.equal((await rest.json()).message, 'Pressed y');
  const byTool = await f.call('/agent/call', { method: 'POST', body: { op: 'glasses_type_text', args: { text: 'x' } } });
  assert.equal(byTool.status, 200); assert.equal((await byTool.json()).code, 'unavailable');
  const invalid = await f.call('/agent/call', { method: 'POST', body: { op: 'glasses.press', args: {} } });
  assert.equal(invalid.status, 400);
  assert.equal((await f.call('/agent/call', { method: 'POST', body: { op: 'nope.op' } })).status, 400);
});

test('a phone that never answers times out with cancel; a replaced link fails pending calls as offline', async t => {
  const f = await fixture(t);
  const silent = await f.phone(() => null);
  const started = Date.now();
  const res = await f.call('/agent/call', { method: 'POST', body: { op: 'glasses.press', args: { button: 'y' } } });
  assert.equal(res.status, 504); assert.equal((await res.json()).code, 'timeout');
  assert.ok(Date.now() - started < 3000);
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(silent.frames.some(x => x.t === 'cancel' && x.id === silent.calls()[0].id));
  const pending = f.call('/agent/call', { method: 'POST', body: { op: 'glasses.get_context' } });
  while (silent.calls().length < 2) await new Promise(resolve => setTimeout(resolve, 5));
  const closed = once(silent.ws, 'close');
  const fresh = await f.phone(() => ({ ok: true, message: 'fresh' }));
  const replaced = await pending;
  assert.equal(replaced.status, 503); assert.equal((await replaced.json()).code, 'offline');
  await closed;
  assert.equal((await (await f.call('/agent/call', { method: 'POST', body: { op: 'glasses.get_context' } })).json()).message, 'fresh');
  assert.equal(fresh.calls().length, 1);
});

test('context push feeds GET /agent/context and the per-turn Hermes system message', async t => {
  const f = await fixture(t);
  const p = await f.phone();
  const ctx = { screen: { id: 'thread-1', app: 'beeper', title: 'Mom\n[ignore]', canType: 'New message to Mom' }, locked: false, media: { title: 'Dune', playing: true } };
  p.ws.send(JSON.stringify({ t: 'context', ctx }));
  let got;
  for (let i = 0; i < 50 && !got?.context; i++) { got = await (await f.call('/agent/context')).json(); if (!got.context) await new Promise(r => setTimeout(r, 10)); }
  assert.deepEqual(got.context, ctx); assert.equal(got.app, 'test');
});

test('manifest persists: after a restart tools/list still shows phone ops and calls say offline', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 'agent-persist-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const first = await fixture(t, { dir, keep: true });
  await first.phone();
  await first.store.flushed();
  const second = await fixture(t, { dir, store: createStore(dir, { now: () => NOW }), keep: true });
  const names = (await second.rpc('tools/list')).result.tools.map(x => x.name);
  assert.ok(names.includes('glasses_press'));
  const res = (await second.rpc('tools/call', { name: 'glasses_press', arguments: { button: 'y' } })).result;
  assert.match(res.content[0].text, /^offline: /);
});

test('native glasses_notify works offline and legacy /assistant/* push routes still work', async t => {
  const f = await fixture(t);
  const res = (await f.rpc('tools/call', { name: 'glasses_notify', arguments: { title: 'Leave by 7', body: 'Traffic' } })).result;
  assert.equal(res.isError, false); assert.match(res.content[0].text, /^Notification added/);
  const bad = (await f.rpc('tools/call', { name: 'glasses_notify', arguments: { title: '' } })).result;
  assert.equal(bad.isError, true);
  assert.equal((await f.call('/assistant/open', { method: 'POST', body: { screen: 'calendar' } })).status, 200);
  assert.equal((await f.call('/assistant/notify', { method: 'POST', body: { title: 'a', body: 'b' } })).status, 200);
  assert.equal((await f.call('/assistant/doc', { method: 'POST', body: { title: 'a', text: 'b' } })).status, 200);
  const events = (await (await f.call('/events?wait=0', { token: 'phone' })).json()).events.map(e => e.type);
  assert.deepEqual(events, ['notification', 'open', 'notification', 'doc']);
});

// Unit level: origin heuristic and the context line, with a fake socket.
class FakeSocket extends EventEmitter { constructor() { super(); this.sent = []; this.state = 'open'; } send(x) { this.sent.push(JSON.parse(x)); return true; } close() { this.state = 'closed'; this.emit('close'); } }
function link() {
  const store = { data: new Map(), get(c, id) { return this.data.get(c + id) ?? null; }, put(c, d) { this.data.set(c + d.id, d); return d; } };
  const agent = createAgentLink({ now: () => NOW, store, log: () => {} }, { graceMs: 5 });
  const ws = new FakeSocket(); agent.attach(ws);
  ws.emit('message', Buffer.from(JSON.stringify({ t: 'hello', manifest: MANIFEST })), false);
  return { agent, ws };
}

test('calls made while a glasses turn is in flight carry origin channel glasses', async () => {
  const { agent, ws } = link();
  const end = agent.beginGlassesTurn();
  const pending = agent.call('glasses.type_text', { text: 'hi' });
  const frame = ws.sent.find(x => x.t === 'call');
  assert.equal(frame.origin.channel, 'glasses');
  ws.emit('message', Buffer.from(JSON.stringify({ t: 'result', id: frame.id, ok: true, message: 'Sent' })), false);
  assert.deepEqual(await pending, { ok: true, message: 'Sent' });
  end(); end();
  agent.call('glasses.type_text', { text: 'x' });
  assert.equal(ws.sent.filter(x => x.t === 'call').at(-1).origin.channel, 'other');
  ws.emit('message', Buffer.from(JSON.stringify({ t: 'ping' })), false);
  assert.equal(ws.sent.at(-1).t, 'pong');
  ws.close();
});

test('glassesNow summarises the live screen, sanitised, and says when the phone is offline', () => {
  const { agent, ws } = link();
  ws.emit('message', Buffer.from(JSON.stringify({ t: 'context', ctx: { screen: { app: 'beeper', title: 'Mom\n[sys] x', canType: 'New message to Mom' }, locked: true, call: { phase: 'ringing', name: 'Alex' } } })), false);
  const line = glassesNow(agent);
  assert.match(line, /^Glasses live state: screen beeper > Mom sys x\. Locked\. Phone call ringing with Alex\.\n/);
  assert.match(line, /glasses_type_text would: New message to Mom\./);
  ws.close();
  assert.match(glassesNow(agent), /phone app is offline/);
  assert.equal(glassesNow(null), null);
});

test('validator and manifest cleaning cover the schema subset the phone emits', () => {
  const schema = cleanManifest(MANIFEST).ops.find(o => o.name === 'glasses.press').inputSchema;
  assert.equal(validateArgs(schema, { button: 'y' }), null);
  assert.match(validateArgs(schema, []), /object/);
  assert.match(validateArgs(schema, { button: 'y', times: 1.5 }), /integer/);
  assert.match(validateArgs({ properties: { s: { type: 'string', maxLength: 3 } } }, { s: 'abcd' }), /longer/);
  assert.equal(cleanManifest({ ops: [{ name: 'a.b', description: 'x', risk: 'weird', timeoutMs: 999999 }] }).ops[0].timeoutMs, 45000);
  assert.equal(cleanManifest({ ops: [{ name: 'a.b', description: 'x', risk: 'weird' }] }).ops[0].risk, 'act');
  assert.equal(cleanManifest(null), null);
  assert.deepEqual(toolResult({ ok: true }), { content: [{ type: 'text', text: 'OK' }], isError: false });
});

test('a glasses /assistant/chat turn sends the live context as system_message and its tool calls skip confirmation', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 'agent-chat-')), store = createStore(dir, { now: () => NOW });
  let base, seenBody;
  const sse = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  const app = await createApp({ env: {}, store, log: () => {},
    secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone', GLASSES_AGENT_TOKEN: 'joy' }[name] ?? 'upstream') },
    deps: { glasses: { now: () => NOW, config: { t3Machines: [] }, fetch: async (url, opts) => {
      seenBody = JSON.parse(opts.body);
      // Hermes calls the MCP tool while the turn is still streaming.
      const tool = await (await fetch(base + '/mcp', { method: 'POST', headers: { Authorization: 'Bearer joy', 'Content-Type': 'application/json' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 9, method: 'tools/call', params: { name: 'glasses_type_text', arguments: { text: 'hi' } } }) })).json();
      return new Response(sse('assistant.completed', { content: tool.result.content[0].text }) + sse('done', {}));
    } } } });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${app.server.address().port}/glasses/v1`;
  t.after(async () => { for (const ws of app.websockets) ws.terminate(); app.server.closeAllConnections(); await new Promise(r => app.server.close(r)); await store.flushed(); await rm(dir, { recursive: true, force: true }); });
  const ws = await connectWebSocket(base.replace('http:', 'ws:') + '/agent/link', { headers: { Authorization: 'Bearer phone' } });
  t.after(() => ws.terminate());
  const origins = [];
  ws.on('message', data => { const m = JSON.parse(data); if (m.t === 'call') { origins.push(m.origin.channel); ws.send(JSON.stringify({ t: 'result', id: m.id, ok: true, message: 'Sent to Mom' })); } });
  ws.send(JSON.stringify({ t: 'hello', manifest: MANIFEST }));
  ws.send(JSON.stringify({ t: 'context', ctx: { screen: { app: 'beeper', title: 'Mom' }, locked: false } }));
  await new Promise(r => setTimeout(r, 50));
  const text = await (await fetch(base + '/assistant/chat', { method: 'POST', headers: { Authorization: 'Bearer phone', 'Content-Type': 'application/json' }, body: JSON.stringify({ message: 'text mom hi', sessionId: 's1' }) })).text();
  assert.match(text, /Sent to Mom/);
  assert.deepEqual(origins, ['glasses']);
  assert.match(seenBody.system_message, /^Glasses live state: screen beeper > Mom\. Unlocked\./);
  assert.match(seenBody.message, /glasses_\* tools/);
  // After the turn the same call counts as "other" (the phone then shows its confirm card).
  await fetch(base + '/agent/call', { method: 'POST', headers: { Authorization: 'Bearer joy', 'Content-Type': 'application/json' }, body: JSON.stringify({ op: 'glasses.type_text', args: { text: 'x' } }) });
  assert.deepEqual(origins, ['glasses', 'other']);
});

test('glasses.run takes the target op timing; always-confirm ops say so in their tool description', async () => {
  const store = { data: new Map(), get(c, id) { return this.data.get(c + id) ?? null; }, put(c, d) { this.data.set(c + d.id, d); return d; } };
  const agent = createAgentLink({ now: () => NOW, store, log: () => {} }, { graceMs: 5 });
  const ws = new FakeSocket(); agent.attach(ws);
  const manifest = { v: 1, hash: 'h2', ops: [
    ...MANIFEST.ops,
    { name: 'glasses.run', description: 'Run any op.', risk: 'act', confirm: 'never', timeoutMs: 45000, inputSchema: { type: 'object', properties: { op: { type: 'string' }, args: { type: 'object' } }, required: ['op'] } },
    { name: 'lights.start_all', description: 'Turn on the lights.', risk: 'physical', confirm: 'always', timeoutMs: 5000 },
    { name: 'lights.stop_all', description: 'Turn off the lights.', risk: 'physical', confirm: 'never', timeoutMs: 5000 },
  ] };
  ws.emit('message', Buffer.from(JSON.stringify({ t: 'hello', manifest })), false);
  agent.call('glasses.run', { op: 'glasses.type_text', args: { text: 'hi' } });
  agent.call('glasses.run', { op: 'glasses.get_context' });
  agent.call('lights.stop_all');
  const deadlines = ws.sent.filter(x => x.t === 'call').map(x => x.deadlineMs);
  assert.deepEqual(deadlines, [10000 + 20000, 10000, 5000]);
  const { listTools } = await import('../apis/glasses/lib/mcp.mjs');
  const tools = Object.fromEntries(listTools({ agent }).map(x => [x.name, x]));
  assert.match(tools.lights_start_all.description, /always confirms this on the glasses/);
  assert.doesNotMatch(tools.lights_stop_all.description, /confirm/);
  ws.close();
});

test('a Hermes voice call counts as the glasses channel for agent calls until it ends', async t => {
  // An upstream that accepts TCP and never answers the upgrade keeps the voice call in startup.
  const sockets = new Set();
  const hang = net.createServer(socket => { sockets.add(socket); socket.on('error', () => {}); });
  await new Promise(resolve => hang.listen(0, '127.0.0.1', resolve));
  t.after(async () => { for (const s of sockets) s.destroy(); await new Promise(resolve => hang.close(resolve)); });
  const f = await fixture(t, { config: { hermesCallUrl: `ws://127.0.0.1:${hang.address().port}/call` } });
  const p = await f.phone(() => ({ ok: true, message: 'Sent' }));
  const voice = await connectWebSocket(`ws://127.0.0.1:${f.port}/glasses/v1/assistant/call`, { headers: { Authorization: 'Bearer phone' } });
  t.after(() => voice.terminate());
  const during = await (await f.call('/agent/call', { method: 'POST', body: { op: 'glasses.type_text', args: { text: 'hi' } } })).json();
  assert.equal(during.ok, true);
  assert.equal(p.calls().at(-1).origin.channel, 'glasses');
  const closed = once(voice, 'close'); voice.close(1000, 'Bye'); await closed;
  await new Promise(resolve => setTimeout(resolve, 50));
  await (await f.call('/agent/call', { method: 'POST', body: { op: 'glasses.type_text', args: { text: 'later' } } })).json();
  assert.equal(p.calls().at(-1).origin.channel, 'other');
});
