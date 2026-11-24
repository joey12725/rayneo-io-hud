// Per-app agent ops end to end: the phone's REAL manifest (test/fixtures/agent-manifest.json, written and kept in
// sync by android AgentManifestFixtureTest) goes over the agent link, and Hermes's MCP tools/list must expose the
// per-app tools with usable descriptions, schemas and hints. No network, no hardware.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';
import { connectWebSocket } from '../lib/ws.mjs';
import { GLASSES_PREFIX } from '../apis/glasses/lib/mcp.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const MANIFEST = JSON.parse(await readFile(path.join(here, 'fixtures', 'agent-manifest.json'), 'utf8'));
const NOW = Date.parse('2026-11-17T18:00:00Z');

async function fixture(t) {
  const dir = await mkdtemp(path.join(tmpdir(), 'agent-ops-'));
  const store = createStore(dir, { now: () => NOW });
  const app = await createApp({ env: {}, store, log: () => {},
    secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone', GLASSES_AGENT_TOKEN: 'joy' }[name] ?? 'upstream') },
    deps: { glasses: { now: () => NOW, fetch: async () => { throw new Error('No network in tests'); }, config: { t3Machines: [] }, agent: { graceMs: 20 } } } });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  const port = app.server.address().port, base = `http://127.0.0.1:${port}/glasses/v1`;
  t.after(async () => {
    for (const ws of app.websockets) ws.terminate();
    app.server.closeAllConnections(); await new Promise(resolve => app.server.close(resolve));
    await store.flushed(); await rm(dir, { recursive: true, force: true });
  });
  const call = (route, { method = 'GET', body } = {}) => fetch(base + route, { method,
    headers: { Authorization: 'Bearer joy', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  const rpc = async (method, params) => (await call('/mcp', { method: 'POST', body: { jsonrpc: '2.0', id: 1, method, params } })).json();
  const phone = async (answer) => {
    const ws = await connectWebSocket(`ws://127.0.0.1:${port}/glasses/v1/agent/link`, { headers: { Authorization: 'Bearer phone' } });
    t.after(() => ws.terminate());
    const calls = [];
    const ready = new Promise(resolve => ws.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.t === 'ready') resolve();
      if (msg.t === 'call') { calls.push(msg); Promise.resolve(answer(msg)).then(reply => ws.send(JSON.stringify({ t: 'result', id: msg.id, ...reply }))); }
    }));
    ws.send(JSON.stringify({ t: 'hello', v: 1, app: 'test', manifest: MANIFEST }));
    await ready;
    return { calls };
  };
  return { call, rpc, phone };
}

// The per-app tools Hermes relies on (SKILL.md "Per-app tools" lists the same names).
const EXPECTED_TOOLS = {
  beeper: ['list_chats', 'read_chat', 'open_chat', 'send_message', 'react', 'mark_read'],
  t3: ['list_threads', 'read_thread', 'open_thread', 'send_message', 'respond', 'new_thread', 'usage'],
  media: ['now_playing', 'play', 'pause', 'next', 'previous', 'rewind', 'forward', 'play_spotify', 'play_audiobook', 'list_youtube', 'play_youtube'],
  memo: ['list', 'start', 'stop'],
  docs: ['list', 'read', 'open'],
  teleprompter: ['start', 'stop'],
  people: ['recent', 'lookup'],
  health: ['overview', 'metric_history'],
  calendar: ['agenda', 'open_day'],
  report: ['latest'],
  ha: ['panel', 'run_scene'],
  lifelog: ['pause'],
};
// Reachable only through glasses_run / glasses_list_operations (kept off the tool list to save prompt tokens).
const HIDDEN_OPS = ['beeper.mark_spam', 'beeper.mark_not_spam', 't3.list_machines', 't3.list_projects', 't3.use_reset', 'media.set_speed',
  'media.set_volume', 'media.list_spotify', 'media.list_devices', 'media.transfer', 'media.list_audiobooks', 'people.mute', 'people.open',
  'health.open_metric', 'report.open', 'ha.set_alarm', 'docs.scroll', 'memo.read', 'memo.toggle', 'teleprompter.read_script',
  'teleprompter.open', 'teleprompter.scroll', 'lifelog.status', 'lifelog.resume'];

test('the fixture is the phone manifest: well-formed, with every per-app op', () => {
  assert.equal(MANIFEST.v, 1);
  assert.match(MANIFEST.hash, /^[0-9a-f]{64}$/);
  const names = new Set(MANIFEST.ops.map(o => o.name));
  for (const [app, verbs] of Object.entries(EXPECTED_TOOLS)) for (const verb of verbs) assert.ok(names.has(`${app}.${verb}`), `${app}.${verb}`);
  for (const op of HIDDEN_OPS) assert.ok(names.has(op), op);
  for (const op of MANIFEST.ops) {
    assert.match(op.name, /^[a-z][a-z0-9]*\.[a-z][a-z0-9_]*$/);
    assert.ok(['read', 'navigate', 'act', 'send', 'call', 'spend', 'physical'].includes(op.risk), op.name);
  }
});

test('MCP tools/list exposes the per-app tools with good descriptions, schemas and hints', async t => {
  const f = await fixture(t);
  await f.phone(() => ({ ok: true }));
  const tools = (await f.rpc('tools/list')).result.tools;
  const byName = Object.fromEntries(tools.map(x => [x.name, x]));
  for (const [app, verbs] of Object.entries(EXPECTED_TOOLS)) {
    for (const verb of verbs) {
      const tool = byName[`${app}_${verb}`];
      assert.ok(tool, `${app}_${verb} is a tool`);
      assert.ok(tool.description.startsWith(GLASSES_PREFIX), tool.name);
      const own = tool.description.slice(GLASSES_PREFIX.length).trim();
      assert.ok(own.length >= 15 && own.length <= 400, `${tool.name}: description length ${own.length}`);
      assert.ok(tool.title && tool.title.length <= 40, tool.name);
      assert.equal(tool.inputSchema.type, 'object'); assert.equal(tool.inputSchema.additionalProperties, false);
      for (const [param, schema] of Object.entries(tool.inputSchema.properties)) {
        assert.match(param, /^[a-z][a-z0-9_]*$/); assert.ok(schema.description, `${tool.name}.${param} has a description`);
      }
    }
  }
  for (const op of HIDDEN_OPS) assert.equal(byName[op.replace('.', '_')], undefined, `${op} stays off the tool list`);
  // Hints: reads are read-only; sends/calls/physical are destructive and say Robin may confirm; stop ops never ask.
  for (const name of ['beeper_list_chats', 't3_read_thread', 'media_now_playing', 'calendar_agenda', 'health_overview', 'ha_panel']) assert.equal(byName[name].annotations.readOnlyHint, true, name);
  for (const name of ['beeper_send_message', 'beeper_react', 't3_send_message', 't3_respond', 't3_new_thread', 'memo_start', 'ha_run_scene']) {
    assert.equal(byName[name].annotations.destructiveHint, true, name);
    assert.match(byName[name].description, /may be asked to confirm on the glasses/, name);
  }
  for (const name of ['media_pause', 'memo_stop', 'teleprompter_stop', 'lifelog_pause']) assert.doesNotMatch(byName[name].description, /confirm/, name);
  assert.deepEqual(byName.beeper_send_message.inputSchema.required, ['chat', 'text']);
  assert.deepEqual(byName.t3_respond.inputSchema.properties.decision.enum, ['approve', 'approve_for_thread', 'deny']);
  // Prompt budget: the whole tool list stays bounded.
  // 85 since Phone, Clock, Notifications, Status and Settings joined (82 today); their long tail is in ActionRegistry.LONG_TAIL.
  assert.ok(tools.length <= 85, `${tools.length} tools`);
  // Every op, hidden ones included, is listed for glasses_list_operations / glasses_run.
  const ops = await (await f.call('/agent/ops')).json();
  assert.equal(ops.ops.length, MANIFEST.ops.length);
});

test('SKILL.md per-app map names only tools and operations that exist', async () => {
  const skill = await readFile(path.join(here, '..', 'hermes-skill', 'glasses', 'SKILL.md'), 'utf8');
  const section = skill.slice(skill.indexOf('## Per-app tools'), skill.indexOf('## Notes'));
  const tools = new Set(MANIFEST.ops.filter(o => o.tool).map(o => o.name.replace('.', '_')));
  const ops = new Set(MANIFEST.ops.map(o => o.name));
  const named = [...section.matchAll(/`([a-z0-9_.]+)/g)].map(m => m[1]).filter(n => /^[a-z][a-z0-9]*[._][a-z0-9_]+$/.test(n) && !n.includes('*'));
  for (const name of named) {
    if (['glasses_run', 'glasses_list_operations', 'glasses_push_doc', 'app.op', 'code_like_this', 'contact_id', 'reveal_numbers'].includes(name)) continue;
    assert.ok(name.includes('.') ? ops.has(name) : tools.has(name), `SKILL.md names ${name}`);
  }
  // Every per-app tool is in the map.
  for (const tool of tools) if (!/^(glasses|home|joy)_/.test(tool)) assert.ok(section.includes('`' + tool), `SKILL.md lists ${tool}`);
});

test('per-app tools relay to the phone with validated arguments and return its real result', async t => {
  const f = await fixture(t);
  const p = await f.phone(msg => msg.op === 'beeper.send_message' ? { ok: true, message: 'Sent · iMessage' }
    : msg.op === 'glasses.run' ? { ok: true, message: 'Moved to Desktop' }
    : { ok: false, code: 'ambiguous', message: 'Several chats match "a"', data: [{ id: 'c1', name: 'Sam' }] });
  const sent = (await f.rpc('tools/call', { name: 'beeper_send_message', arguments: { chat: 'Sam', text: 'on my way' } })).result;
  assert.equal(sent.isError, false); assert.equal(sent.content[0].text, 'Sent · iMessage');
  assert.deepEqual(p.calls[0].args, { chat: 'Sam', text: 'on my way' });
  assert.equal(p.calls[0].deadlineMs, 15000 + 20000, 'send ops get the confirm window on top of their own timeout');
  const ambiguous = (await f.rpc('tools/call', { name: 'beeper_open_chat', arguments: { chat: 'a' } })).result;
  assert.equal(ambiguous.isError, true); assert.match(ambiguous.content[0].text, /^ambiguous: /);
  // The gateway rejects bad arguments before the phone sees them.
  for (const [name, args] of [['beeper_send_message', { chat: 'Sam' }], ['t3_respond', { thread: '2', decision: 'maybe' }],
    ['media_rewind', { seconds: 0 }], ['calendar_agenda', { days: 30 }], ['beeper_list_chats', { bogus: true }]]) {
    const bad = (await f.rpc('tools/call', { name, arguments: args })).result;
    assert.equal(bad.isError, true, name); assert.match(bad.content[0].text, /^invalid_args: /, name);
  }
  assert.equal(p.calls.length, 2);
  // A hidden op runs through glasses_run.
  const run = (await f.rpc('tools/call', { name: 'glasses_run', arguments: { op: 'media.transfer', args: { device: 'Desktop' } } })).result;
  assert.equal(run.isError, false); assert.equal(p.calls[2].op, 'glasses.run');
});
