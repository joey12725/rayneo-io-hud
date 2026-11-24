// Harness, schema and site build, all on synthetic data (no network, no real messages).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { validateDataset, caseErrors, v2Errors } from '../lib/schema.mjs';
import { attempt, runAll, startLocalGateway, requestFor } from '../run.mjs';
import { buildPage } from '../build.mjs';
import { metrics } from '../lib/derive.mjs';

const fixture = name => JSON.parse(readFileSync(new URL('../fixtures/' + name, import.meta.url), 'utf8'));
const at = m => new Date(Date.parse('2026-11-01T12:00:00Z') + m * 60000).toISOString();
const T = Date.parse('2026-11-10T18:00:00Z');
const iso = (h) => new Date(T + h * 3600000).toISOString();

test('harness: request bodies per endpoint, errors and retries', async () => {
  const seen = [];
  const post = async (p, body) => { seen.push({ p, body }); return { status: 200, json: { route: 'popup', importance: 0.9, reason: 'asks the owner', by: 'model', debug: { prompt: 'v1' } }, ms: 5 }; };
  const input = { kind: 'beeper', title: 't', body: 'b', isGroup: true, chatId: 'c' };
  const a = await attempt(post, 'route', input);
  assert.deepEqual(seen[0], { p: '/notify/route', body: { ...input, debug: true } });
  assert.deepEqual(a, { status: 200, ms: 5, route: 'popup', importance: 0.9, reason: 'asks the owner', by: 'model', debug: { prompt: 'v1' } });
  await attempt(post, 'classify', input);
  assert.deepEqual(seen[1], { p: '/notify/classify', body: { title: 't', body: 'b', isGroupConversation: true } });
  assert.match((await attempt(async () => ({ status: 400, json: { error: 'bad' }, ms: 1 }), 'route', input)).error, /bad/);
  const loose = await attempt(async () => ({ status: 200, json: { route: 'notify', importance: 0.9 }, ms: 1 }), 'route', input);
  assert.match(loose.contract.join(), /route.*reason.*by/);
  assert.equal((await attempt(async () => ({ status: 200, json: { route: 'notify', importance: 0.9 }, ms: 1 }), 'classify', input)).contract, undefined);
  assert.equal((await attempt(async () => { throw new Error('ECONNRESET'); }, 'route', input)).status, 0);
  let calls = 0;
  const flaky = async () => { calls++; if (calls === 1) throw new Error('reset'); return { status: 200, json: { route: 'stream', importance: 0.5 }, ms: 3 }; };
  const results = await runAll([{ id: 'x', input }, { id: 'y', input }], { post: flaky, repeats: 2, concurrency: 1 });
  assert.equal(results.length, 2);
  assert.ok(results.every(r => r.attempts.length === 2 && r.attempts.every(x => x.route === 'stream')));
  assert.equal(results[0].attempts[0].retried, true);
});

test('harness: T3 cases are sent marked watched, so the in-process gateway evaluates them instead of "t3.unwatched"', async () => {
  const seen = [];
  await attempt(async (p, body) => { seen.push(body); return { status: 200, json: { route: 'stream', importance: 0.6, reason: 't3.done', by: 'rule' }, ms: 1 }; }, 'route', { kind: 't3', title: 'T', body: 'x' });
  assert.deepEqual(seen[0], { kind: 't3', title: 'T', body: 'x', debug: true, watched: true });
  const fake = async (url) => {
    if (String(url).endsWith('/chat/completions')) return new Response(JSON.stringify({ choices: [{ message: { content: '{"route":"popup","importance":0.8,"reason":"asks the owner"}' } }] }), { headers: { 'Content-Type': 'application/json' } });
    return new Response('{}', { status: 404 });
  };
  const gw = await startLocalGateway({ proxyUrl: 'http://fake-proxy/v1', fetch: fake });
  try {
    const post = async (p, body) => {
      const r = await fetch(gw.cfg.url + p, { method: 'POST', headers: { Authorization: 'Bearer ' + gw.cfg.token, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      return { status: r.status, json: await r.json(), ms: 1 };
    };
    const base = { kind: 't3', source: 't3:desktop', app: 'T3 Code', title: 'Refactor', conversationTitle: 'Glasses' };
    const done = await attempt(post, 'route', { ...base, body: 'Refactor finished, all tests pass.', category: 'done' });
    assert.equal(done.reason, 't3.done'); assert.equal(done.route, 'stream');
    // A run waiting on the owner's input always pops up (rule); a stopped run is semantic (the classifier).
    const needs = await attempt(post, 'route', { ...base, body: 'Two options here. Shall I keep the old API?', category: 'needs-input' });
    assert.equal(needs.by, 'rule'); assert.equal(needs.route, 'popup'); assert.equal(needs.reason, 't3.needs-you');
    const semantic = await attempt(post, 'route', { ...base, body: 'Stopped before the migration step.', category: 'cancelled' });
    assert.equal(semantic.by, 'model'); assert.equal(semantic.route, 'popup');
    // Without the harness flag the same case is suppressed by the watch rule.
    const raw = await post('/notify/route', { ...base, body: 'Refactor finished.', category: 'done', debug: true });
    assert.equal(raw.json.reason, 't3.unwatched');
  } finally { await gw.close(); }
});

test('harness: in-process gateway answers /notify/classify through a fake model', async () => {
  const fake = async (url, opts = {}) => {
    if (String(url).endsWith('/chat/completions')) return new Response(JSON.stringify({ choices: [{ message: { content: '{"route":"notify","importance":0.9}' } }] }), { headers: { 'Content-Type': 'application/json' } });
    return new Response('{}', { status: 404 });
  };
  const gw = await startLocalGateway({ proxyUrl: 'http://fake-proxy/v1', fetch: fake });
  try {
    const post = async (p, body) => {
      const r = await fetch(gw.cfg.url + p, { method: 'POST', headers: { Authorization: 'Bearer ' + gw.cfg.token, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      return { status: r.status, json: await r.json(), ms: 1 };
    };
    const cases = fixture('sample-dataset.json').cases.filter(c => c.kind === 'beeper');
    const results = await runAll(cases, { post, endpoint: 'classify', repeats: 1 });
    assert.ok(results.every(r => r.attempts[0].status === 200 && ['notify', 'suppress'].includes(r.attempts[0].route)));
    const m = metrics(cases, { endpoint: 'classify', results });
    assert.equal(m.all.n, cases.length);
    assert.equal(m.all.errors, 0);
  } finally { await gw.close(); }
});

test('site build: self-contained page, every case embedded, no secret, no script break-out', () => {
  const dataset = fixture('sample-dataset.json'), run = fixture('sample-run.json');
  const evil = { ...dataset.cases[0], id: 'beeper:evil', input: { ...dataset.cases[0].input, body: '</script><script>alert(1)</script>' } };
  const { html, cases, runs } = buildPage({ datasets: [{ ...dataset, cases: [...dataset.cases, evil] }], runs: [run], secrets: ['not-in-the-page-123'] });
  assert.equal(cases, dataset.cases.length + 1); assert.equal(runs, 1);
  for (const c of dataset.cases) assert.ok(html.includes(JSON.stringify(c.id)), c.id);
  assert.equal((html.match(/<\/script/gi) ?? []).length, 3);           // only the page's own three script elements
  assert.ok(!/<script[^>]+src=|<link[^>]+href=|fetch\(/.test(html), 'no external resources or network calls');
  assert.ok(!html.includes('/*DATA*/') && !html.includes('/*APP*/') && !html.includes('/*DERIVE*/') && !html.includes('/*STYLE*/'));
  assert.ok(!/^export /m.test(html));
  assert.ok(html.includes('prefers-color-scheme: dark'));
  assert.throws(() => buildPage({ datasets: [dataset], runs: [run], secrets: ['sample reason'] }), /secret/);
  assert.throws(() => buildPage({ datasets: [{ schema: 1, cases: [{ id: 'x' }] }], runs: [] }), /invalid dataset/);
  // The embedded data parses back.
  const json = html.slice(html.indexOf('<script id="eval-data" type="application/json">') + '<script id="eval-data" type="application/json">'.length, html.indexOf('</script>', html.indexOf('eval-data')));
  const data = JSON.parse(json);
  assert.equal(data.cases.find(c => c.id === 'beeper:evil').input.body, '</script><script>alert(1)</script>');
  assert.ok(data.cases.every(c => ['dev', 'test'].includes(c.split)), 'every case carries its harness split');
  assert.equal(data.tuningLog, null);
  // The tuning log and vote summary are embedded with the same escaping.
  const withLog = buildPage({ datasets: [dataset], runs: [run], tuningLog: '# log\n| a | b |\n|---|---|\n| </script> | 2 |', labelSummary: { perKind: { beeper: { total: 1 } } } }).html;
  assert.equal((withLog.match(/<\/script/gi) ?? []).length, 3);
  assert.ok(withLog.includes('"perKind"'));
  // The page scripts are syntactically valid.
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
  assert.equal(scripts.length, 2);
  for (const s of scripts) assert.doesNotThrow(() => new Function(s));
});

test('schema accepts a v2 hermes case with input kind assistant', () => {
  const c = { id: 'hermes:x', kind: 'hermes', synthetic: true, at: at(1), expected: 'stream', why: 'w', ambiguous: false, labelSource: 'heuristic',
    input: { kind: 'assistant', source: 'hermes:discord', app: 'Hermes', title: 't', body: 'b', sender: 'Hermes' },
    context: { turns: [], prompt: { at: at(0), text: 'p' }, response: { at: at(1), text: 'r' } } };
  assert.deepEqual(caseErrors(c), []);
  assert.deepEqual(v2Errors(c), []);
  assert.ok(v2Errors({ ...c, context: { turns: [] } }).length);
});
test('harness: v2 Beeper cases send their relationship stats in the debug context', () => {
  const c = { id: 'b', kind: 'beeper', input: { kind: 'beeper', title: 'Sam', body: 'hi' }, context: { turns: [], target: { at: iso(0), sender: 'Sam', text: 'hi' }, relationship: { msgs30d: 4, ownerMsgs30d: 2, ownerShare: 0.5, tier: 'occasional' } } };
  assert.deepEqual(requestFor(c).body.context.relationship, c.context.relationship);
  assert.equal(requestFor({ ...c, context: { turns: [] } }).body.context.relationship, undefined);
});
