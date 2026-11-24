// Harness v2 (synthetic data only): production-shaped requests with context, the dev/test split, macro-F1 and
// weighted cost, and the in-process gateway answering /notify/route and /notify/hermes through a fake model.
import test from 'node:test';
import assert from 'node:assert/strict';
import { requestFor, hermesBody, assignSplit, splitCases, interleaveKinds, runAll, startLocalGateway } from '../run.mjs';
import { metrics, macroF1, costOf } from '../lib/derive.mjs';
import { caseErrors, v2Errors } from '../lib/schema.mjs';

const at = min => new Date(Date.UTC(2026, 10, 18, 1, 0) + min * 60000).toISOString();
const common = { synthetic: true, why: 'w', ambiguous: false, labelSource: 'haiku-swarm-v2' };
const beeper = { ...common, id: 'beeper:s1', kind: 'beeper', at: at(0), expected: 'stream',
  input: { kind: 'beeper', source: 'com.beeper.android', app: 'Beeper', title: 'Sam', body: 'ok see you then', sender: 'Sam', network: 'Instagram', isGroup: false, chatId: '!s:beeper.local' },
  context: { chat: { isGroup: false }, target: { id: '9', sender: 'Sam', at: at(0), text: 'ok see you then' },
    turns: [{ speaker: 'other', name: 'Sam', isOwner: false, messages: [{ id: '7', at: at(-10), text: 'dinner at 7?', priorRoute: 'popup', couldNotify: true }] },
      { speaker: 'owner', name: 'the owner', isOwner: true, messages: [{ id: '8', at: at(-2), text: 'yes!', priorRoute: null }] }] } };
const t3 = { ...common, id: 't3:s1', kind: 't3', at: at(0), expected: 'stream',
  input: { kind: 't3', source: 't3:desktop', app: 'T3 Code', title: 'Fix login', conversationTitle: 'glasses', body: 'All tests pass.', category: 'done' },
  context: { threadTitle: 'Fix login', project: 'glasses', run: { status: 'completed' }, prompt: { at: at(-5), text: 'fix it', createdBy: 'agent', isOwner: false }, response: { at: at(0), text: 'All tests pass.' }, turns: [] } };
const hermes = { ...common, id: 'hermes:s1', kind: 'hermes', at: at(0), expected: 'suppress',
  input: { kind: 'assistant', source: 'hermes:cron', app: 'Hermes', title: 'Ingest', body: 'Ingested 3 notes', sender: 'Hermes', isGroup: true, channelId: 'c1', category: 'scheduled' },
  context: { platform: 'cron', channel: 'Ingest', isDM: false, job: { id: 'j', name: 'Ingest', deliver: 'local' }, inbound: { sender: 'scheduler', isOwner: false, kind: 'cron-prompt' },
    prompt: { at: at(-1), text: 'run ingest' }, response: { at: at(0), text: 'Ingested 3 notes' }, sessionId: 'cron_j_1',
    turns: [{ speaker: 'assistant', isOwner: false, messages: [{ id: 1, at: at(-60), text: 'earlier run', priorRoute: null }] }] } };

test('v2 cases validate (named label pass) and requests carry their context in the production shape', () => {
  for (const c of [beeper, t3, hermes]) { assert.deepEqual(caseErrors(c), [], c.id); }
  assert.deepEqual(v2Errors(t3), []); assert.deepEqual(v2Errors(hermes), []);
  assert.match(caseErrors({ ...beeper, labelSource: 'guess' }).join(), /labelSource/);
  const b = requestFor(beeper);
  assert.equal(b.path, '/notify/route'); assert.equal(b.token, 'phone'); assert.equal(b.body.debug, true);
  assert.deepEqual(b.body.context.target, { id: '9', at: at(0), sender: 'Sam', text: 'ok see you then' });
  assert.deepEqual(b.body.context.turns[0], { speaker: 'Sam', isOwner: false, messages: [{ id: '7', at: at(-10), text: 'dinner at 7?', priorRoute: 'popup' }] });
  const t = requestFor(t3);
  assert.equal(t.body.watched, true); assert.equal(t.body.context.prompt.agentWritten, true); assert.equal(t.body.context.status, 'completed'); assert.equal(t.body.context.response.text, 'All tests pass.');
  const h = requestFor(hermes);
  assert.equal(h.path, '/notify/hermes'); assert.equal(h.token, 'agent');
  assert.deepEqual({ ...hermesBody(hermes), turns: undefined }, { platform: 'cron', chat: 'Ingest', chatId: 'c1', isDM: false, sender: 'scheduler', inboundKind: 'cron-prompt', job: 'Ingest', delivered: false,
    prompt: { at: at(-1), text: 'run ingest' }, response: { at: at(0), text: 'Ingested 3 notes' }, turns: undefined, sessionId: 'cron_j_1', turnId: 'hermes:s1', debug: true });
  assert.equal(hermesBody(hermes).turns[0].speaker, 'Hermes');
  // v1 cases and bare inputs keep the old body; classify keeps the old field set.
  assert.deepEqual(requestFor({ kind: 'beeper', title: 't', body: 'b' }).body, { kind: 'beeper', title: 't', body: 'b', debug: true });
  assert.equal(requestFor(beeper, 'classify').path, '/notify/classify');
});

test('split: deterministic, 70/30 within each (kind, expected label) stratum, independent of order', () => {
  const cases = [];
  for (const kind of ['beeper', 't3', 'hermes']) for (const expected of ['popup', 'stream', 'suppress']) for (let i = 0; i < 20; i++) cases.push({ id: `${kind}:${expected}:${i}`, kind, expected });
  const a = assignSplit(cases), b = assignSplit([...cases].reverse());
  assert.deepEqual([...a].sort(), [...b].sort());
  for (const kind of ['beeper', 't3', 'hermes']) for (const expected of ['popup', 'stream', 'suppress'])
    assert.equal(cases.filter(c => c.kind === kind && c.expected === expected && a.get(c.id) === 'dev').length, 14);
  assert.equal(splitCases(cases, 'dev').length + splitCases(cases, 'test').length, cases.length);
  assert.throws(() => splitCases(cases, 'train'));
  assert.deepEqual(interleaveKinds(cases).slice(0, 3).map(c => c.kind), ['beeper', 'hermes', 't3']);
});

test('metrics: macro-F1 per kind, popup precision/recall and the owner\'s weighted cost on clear cases', () => {
  assert.equal(costOf('popup', 'suppress'), 5); assert.equal(costOf('stream', 'suppress'), 4); assert.equal(costOf('suppress', 'popup'), 3);
  assert.equal(costOf('stream', 'popup'), 2); assert.equal(costOf('popup', 'stream'), 1.5); assert.equal(costOf('suppress', 'stream'), 1.5); assert.equal(costOf('popup', 'error'), 5);
  const cases = [{ id: 'a', kind: 'beeper', expected: 'popup', ambiguous: false }, { id: 'b', kind: 'beeper', expected: 'stream', ambiguous: false },
    { id: 'c', kind: 't3', expected: 'popup', ambiguous: false }, { id: 'd', kind: 't3', expected: 'suppress', ambiguous: true }];
  const run = { endpoint: 'route', results: [['a', 'popup'], ['b', 'popup'], ['c', 'suppress'], ['d', 'popup']].map(([id, route]) => ({ id, attempts: [{ route, importance: 0.5, by: 'model' }] })) };
  const m = metrics(cases, run);
  assert.equal(m.all.cost.total, 2 + 5); assert.equal(m.all.cost.n, 3);
  assert.equal(m.byKind.beeper.popup.precision, 0.5); assert.equal(m.byKind.beeper.popup.recall, 1);
  assert.equal(m.byKind.beeper.macroF1, (2 / 3 + 0) / 2);
  assert.equal(m.byKind.t3.macroF1, 0);
  assert.equal(macroF1({ popup: {}, stream: {}, suppress: {} }), null);
});

test('in-process gateway: v2 beeper, t3 and hermes cases end to end through a fake model', async () => {
  const seen = [];
  const fake = async (url, opts = {}) => {
    if (String(url).endsWith('/chat/completions')) { seen.push(JSON.parse(opts.body).messages[1].content); return new Response(JSON.stringify({ choices: [{ message: { content: '{"route":"popup","importance":0.8,"reason":"needs the owner"}' } }] }), { headers: { 'Content-Type': 'application/json' } }); }
    return new Response('{}', { status: 404 });
  };
  const gw = await startLocalGateway({ proxyUrl: 'http://fake-proxy/v1', fetch: fake });
  try {
    const post = async (p, body, token) => {
      const r = await fetch(gw.cfg.url + p, { method: 'POST', headers: { Authorization: 'Bearer ' + (token === 'agent' ? gw.cfg.agentToken : gw.cfg.token), 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      return { status: r.status, json: await r.json(), ms: 1 };
    };
    const results = await runAll([beeper, t3, hermes, { ...hermes, id: 'hermes:s2', context: { ...hermes.context, job: { ...hermes.context.job, deliver: 'discord:1' } } }], { post, repeats: 1, concurrency: 1 });
    const by = Object.fromEntries(results.map(r => [r.id, r.attempts[0]]));
    // Beeper: the classifier said popup, but Sam's "ok see you then" follows a popup with nothing new asked -> guard suppresses.
    assert.equal(by['beeper:s1'].route, 'suppress'); assert.equal(by['beeper:s1'].debug.guard, 'guard.ack');
    assert.match(seen[0], /dinner at 7\?   <- already notified them: popup/);
    assert.equal(by['t3:s1'].route, 'popup'); assert.equal(by['t3:s1'].by, 'model');
    assert.match(seen[1], /PROMPT \(written by an agent, NOT by the owner; -5 min before the response\)/);
    assert.equal(by['hermes:s1'].route, 'suppress'); assert.equal(by['hermes:s1'].reason, 'hermes.undelivered');
    assert.equal(by['hermes:s2'].route, 'popup'); assert.equal(by['hermes:s2'].extra.stored, false);
    assert.match(seen[2], /scheduled job "Ingest"/);
    assert.ok(results.every(r => !r.attempts[0].contract), JSON.stringify(results.map(r => r.attempts[0].contract)));
  } finally { await gw.close(); }
});
