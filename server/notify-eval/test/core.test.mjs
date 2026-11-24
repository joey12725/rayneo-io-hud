// Schema, scoring, heuristics and selection (pure code; synthetic data only).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { caseErrors, validateDataset, validateRun } from '../lib/schema.mjs';
import { actual3, majority, outcome, metrics, classifyBody, percentile, contractProblems } from '../lib/derive.mjs';
import { parseProperties } from '../lib/config.mjs';
import { parseJsonAnswer } from '../lib/llm.mjs';

const sample = JSON.parse(readFileSync(new URL('../fixtures/sample-dataset.json', import.meta.url), 'utf8'));
const sampleRun = JSON.parse(readFileSync(new URL('../fixtures/sample-run.json', import.meta.url), 'utf8'));
const synthCalls = JSON.parse(readFileSync(new URL('../fixtures/calls.synthetic.json', import.meta.url), 'utf8'));

test('committed fixtures are valid and wholly synthetic', () => {
  assert.deepEqual(validateDataset(sample), []);
  assert.deepEqual(validateDataset({ schema: 1, cases: synthCalls.cases }), []);
  assert.deepEqual(validateRun(sampleRun), []);
  assert.ok(sample.cases.every(c => c.synthetic === true));
  assert.ok(synthCalls.cases.every(c => c.synthetic === true && c.kind === 'call'));
  assert.ok(synthCalls.cases.some(c => c.input.callerVerified === 'failed'));
});

test('schema rejects bad cases', () => {
  const good = sample.cases[0];
  assert.deepEqual(caseErrors(good), []);
  assert.match(caseErrors({ ...good, expected: 'notify' }).join(), /expected/);
  assert.match(caseErrors({ ...good, kind: 'mail' }).join(), /kind/);
  assert.match(caseErrors({ ...good, input: { ...good.input, phone: '555' } }).join(), /not a \/notify\/route field/);
  assert.match(caseErrors({ ...good, input: { ...good.input, isGroup: 'yes' } }).join(), /isGroup must be boolean/);
  assert.match(caseErrors({ ...good, input: { ...good.input, kind: 't3' } }).join(), /differs/);
  assert.match(caseErrors({ ...good, input: { ...good.input, debug: true } }).join(), /debug/);
  assert.match(caseErrors({ ...good, labelSource: 'guess' }).join(), /labelSource/);
  assert.match(validateDataset({ schema: 1, cases: [good, good] }).join(), /duplicate/);
  assert.match(validateRun({ schema: 1, endpoint: 'x', results: [] }).join(), /endpoint/);
});

test('actual3 maps both APIs', () => {
  assert.equal(actual3({ route: 'popup' }, 'route'), 'popup');
  assert.equal(actual3({ route: 'stream' }, 'route'), 'stream');
  assert.equal(actual3({ route: 'notify', importance: 0.8 }, 'classify'), 'popup');
  assert.equal(actual3({ route: 'notify', importance: 0.74 }, 'classify'), 'stream');
  assert.equal(actual3({ route: 'notify', importance: 0.74 }, 'classify', 0.5), 'popup');
  assert.equal(actual3({ route: 'suppress', importance: 0.9 }, 'classify'), 'suppress');
  assert.equal(actual3({ error: 'timeout' }, 'route'), 'error');
  assert.equal(actual3({ route: 'weird' }, 'route'), 'error');
  // The old binary answer on the new endpoint is a contract break, never scored as stream/popup.
  assert.equal(actual3({ route: 'notify', importance: 0.9 }, 'route'), 'error');
  assert.equal(actual3({ route: 'suppress', importance: 0.1 }, 'route'), 'suppress');
});

test('contractProblems checks /notify/route answers against the shared contract', () => {
  assert.deepEqual(contractProblems({ route: 'popup', importance: 0.9, reason: 'asks the owner', by: 'model' }), []);
  assert.deepEqual(contractProblems({ route: 'stream', importance: 0, reason: 'r', by: 'rule', open: { app: 'beeper', target: 'chat' } }), []);
  assert.equal(contractProblems(null).length, 1);
  const bad = contractProblems({ route: 'notify', importance: 1.5, by: 'llm', open: { target: 1 } });
  assert.equal(bad.length, 5);
  assert.match(bad.join(), /route.*importance.*reason.*by.*open/);
  const run = { endpoint: 'route', results: [{ id: sample.cases[0].id, attempts: [{ route: 'popup', importance: 0.9, contract: ['reason missing'] }, { route: 'popup', importance: 0.9 }] }] };
  assert.equal(metrics(sample.cases, run).all.contract, 1);
});

test('majority, outcome and flakiness', () => {
  assert.equal(majority(['stream', 'popup', 'stream']), 'stream');
  assert.equal(majority(['stream', 'popup']), 'popup');      // ties go to the more interruptive route
  assert.equal(majority(['error', 'suppress']), 'suppress');
  assert.equal(majority([]), 'error');
  const o = outcome({ attempts: [{ route: 'popup' }, { route: 'stream' }, { route: 'popup' }] }, 'route');
  assert.equal(o.actual, 'popup'); assert.equal(o.flaky, true); assert.equal(Math.round(o.agreement * 100), 67);
  assert.equal(outcome({ attempts: [{ route: 'suppress' }, { route: 'suppress' }] }, 'route').flaky, false);
  assert.equal(percentile([5, 1, 3, 2, 4], 50), 3);
  assert.equal(percentile([], 50), null);
});

test('metrics on the sample run', () => {
  const m = metrics(sample.cases, sampleRun);
  assert.equal(m.all.n, sample.cases.length);
  const cellSum = Object.values(m.all.matrix).reduce((n, row) => n + Object.values(row).reduce((a, b) => a + b, 0), 0);
  assert.equal(cellSum, m.all.n);
  // beeper:sample-group-owner expected popup, got stream; beeper:sample-otp expected suppress (ambiguous), got stream.
  assert.equal(m.all.matrix.popup.stream, 1);
  assert.equal(m.byKind.beeper.ambiguous, 2);
  assert.equal(m.byKind.beeper.flaky, 1);                    // sample-dm-chat: stream, popup
  assert.ok(m.all.errors >= 0 && m.all.flaky >= 1);
  assert.equal(m.all.popup.tp + m.all.popup.fn, sample.cases.filter(c => c.expected === 'popup').length);
  // A browser override changes the score.
  const before = m.byKind.beeper.correct;
  const after = metrics(sample.cases, sampleRun, { labels: { 'beeper:sample-group-owner': { expected: 'stream', ambiguous: false } } }).byKind.beeper.correct;
  assert.equal(after, before + 1);
  assert.ok(m.all.latency.p50 > 0);
  assert.equal(m.byKind.call.byRoute.rule > 0, true);
});

test('classifyBody keeps only the old field set', () => {
  assert.deepEqual(classifyBody({ kind: 'beeper', source: 's', title: 't', body: 'b', isGroup: true, chatId: 'c', contact: { known: true } }),
    { source: 's', title: 't', body: 'b', isGroupConversation: true });
});

const at = m => new Date(Date.parse('2026-11-01T12:00:00Z') + m * 60000).toISOString();
const msg = (id, sender, text, min, extra = {}) => ({ id, sender, mine: sender === 'You', text, at: at(min), reactions: [], replyTo: null, ...extra });

test('helpers: properties and JSON answers', () => {
  assert.deepEqual(parseProperties('# c\nA=1\n B = two words \n\nC='), { A: '1', B: 'two words', C: '' });
  assert.deepEqual(parseJsonAnswer('```json\n{"a":1}\n```'), { a: 1 });
  assert.deepEqual(parseJsonAnswer('sure: {"a":2} done'), { a: 2 });
  assert.equal(parseJsonAnswer('nope'), null);
});
