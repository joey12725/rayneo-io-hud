// Gateway configuration (lib/config.mjs): defaults, env, gateway.config.json merged over env, validation messages,
// the documented examples, and GET /glasses/v1/config.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { loadConfig, describe, publicConfig, enabledIntegrations, ConfigError, GATEWAY_DIR } from '../lib/config.mjs';
import { envExample, configExample, starterConfig } from '../tools/config-examples.mjs';
import { createApp } from '../server.mjs';
import { createStore } from '../lib/store.mjs';

async function tempDir(t) { const dir = await mkdtemp(path.join(tmpdir(), 'gateway-config-')); t.after(() => rm(dir, { recursive: true, force: true })); return dir; }
const problemsOf = fn => { try { fn(); } catch (e) { assert.ok(e instanceof ConfigError, e.message); return e.problems; } assert.fail('expected a ConfigError'); };

test('defaults carry nothing personal: UTC, no home, neutral names and models, local hosts', () => {
  const c = loadConfig({ env: {}, file: null });
  assert.equal(c.tz, 'UTC'); assert.deepEqual(c.home, { lat: null, lon: null });
  assert.deepEqual(c.owner, { name: 'the user', aliases: [] }); assert.deepEqual(c.assistant, { name: 'Hermes', workerName: 'Triage' });
  assert.equal(c.publicUrl, ''); assert.equal(c.hermes.callUrl, ''); assert.match(c.hermes.url, /^http:\/\/localhost:/); assert.match(c.beeper.url, /^http:\/\/localhost:/);
  assert.equal(c.models.classifier, c.models.fast, 'the classifier defaults to the fast model');
  assert.equal(c.secrets.provider, 'env'); assert.equal(c.targets, null);
  assert.equal(c.t3.machinesFile, path.join(c.dataDir, 't3-machines.json'));
  assert.ok(!enabledIntegrations(c).includes('weather')); assert.ok(!enabledIntegrations(c).includes('hermes-call'));
  const text = JSON.stringify({ ...c, dataDir: '', t3: { ...c.t3, machinesFile: '' } });
  for (const personal of [/America\//, /192\.168\./, /\b10\.\d+\.\d+\.\d+/, /\.ts\.net/, /\/run\//, /\/mnt\//]) assert.doesNotMatch(text, personal);
});

test('env vars set every key by name; TZ is the fallback for GATEWAY_TZ', () => {
  const c = loadConfig({ env: { GATEWAY_OWNER_NAME: 'Sam', GATEWAY_OWNER_ALIASES: 'sam, sammy', GATEWAY_ASSISTANT_NAME: 'Nova', GATEWAY_HOME_LAT: '52.5', GATEWAY_HOME_LON: '13.4',
    TZ: 'Europe/Berlin', BEEPER_LIVE: '0', GATEWAY_WORKER: 'true', MODEL_FAST: 'small', GATEWAY_CLIENTS: '{"A":["glasses"]}', NOTIFY_OWN_CHATS: 'Bots,Family', HERMES_CALL_URL: 'wss://voice.example/call' }, file: null });
  assert.deepEqual(c.owner, { name: 'Sam', aliases: ['sam', 'sammy'] }); assert.equal(c.assistant.name, 'Nova');
  assert.deepEqual(c.home, { lat: 52.5, lon: 13.4 }); assert.equal(c.tz, 'Europe/Berlin');
  assert.equal(c.beeper.live, false); assert.equal(c.worker, true); assert.equal(c.models.classifier, 'small'); assert.deepEqual(c.clients, { A: ['glasses'] });
  assert.deepEqual(c.notify.ownChats, ['Bots', 'Family']); assert.ok(enabledIntegrations(c).includes('hermes-call')); assert.ok(enabledIntegrations(c).includes('weather'));
  assert.equal(loadConfig({ env: { TZ: 'Europe/Berlin', GATEWAY_TZ: 'Asia/Tokyo' }, file: null }).tz, 'Asia/Tokyo');
});

test('gateway.config.json is merged over env; relative dataDir resolves next to the file', async t => {
  const dir = await tempDir(t), file = path.join(dir, 'gateway.config.json');
  await writeFile(file, JSON.stringify({ $comment: 'mine', owner: { name: 'Sam' }, dataDir: './state', beeper: { enabled: false }, targets: [{ id: 'x', kind: 'directory', path: 'out' }] }));
  const c = loadConfig({ env: { GATEWAY_OWNER_NAME: 'Env Name', GATEWAY_OWNER_ALIASES: 'envy', GATEWAY_CONFIG: file } });
  assert.equal(c.owner.name, 'Sam', 'the file wins'); assert.deepEqual(c.owner.aliases, ['envy'], 'keys the file leaves out keep the env value');
  assert.equal(c.dataDir, path.join(dir, 'state')); assert.equal(c.beeper.enabled, false); assert.equal(c.targets[0].id, 'x');
  assert.ok(!enabledIntegrations(c).includes('beeper'));
});

test('validation names the key, the source and what is expected', async t => {
  const dir = await tempDir(t), file = path.join(dir, 'g.json');
  await writeFile(file, JSON.stringify({ ownr: {}, owner: { aliases: 'sam' }, beeper: { mentions: 'loud', bogus: 1 }, tz: 'Mars/Olympus', home: { lat: 95 }, hermes: { callUrl: 'http://x' }, clients: { A: 'glasses' } }));
  const p = problemsOf(() => loadConfig({ env: {}, file }));
  const has = re => assert.ok(p.some(x => re.test(x)), `${re} in:\n${p.join('\n')}`);
  has(/^unknown key ownr \(did you mean owner\?\)$/); has(/^unknown key beeper\.bogus$/);
  has(/^owner\.aliases in g\.json must be an array of strings \(got string\)$/);
  has(/^beeper\.mentions in g\.json must be one of pill, plain \(got "loud"\)$/);
  has(/tz "Mars\/Olympus" is not a valid IANA time zone/); has(/home\.lat and home\.lon must be set together/); has(/home\.lat must be between -90 and 90/);
  has(/hermes\.callUrl must be a ws:\/\/ or wss:\/\/ URL/); has(/clients must map secret names to arrays of scopes/);
  const envProblems = problemsOf(() => loadConfig({ env: { PORT: 'eighty', BEEPER_LIVE: 'maybe', GATEWAY_CLIENTS: '{nope' }, file: null }));
  assert.deepEqual(envProblems.slice(0, 3).sort(), ['BEEPER_LIVE must be true/false or 1/0 (got "maybe")', 'GATEWAY_CLIENTS must be JSON', 'PORT must be a number (got "eighty")'].sort());
  assert.match(new ConfigError(['x']).message, /^Invalid gateway configuration:\n  - x$/);
  assert.throws(() => loadConfig({ env: {}, file: path.join(dir, 'missing.json') }), /GATEWAY_CONFIG file .* cannot be read \(ENOENT\)/);
  await writeFile(file, '{ not json'); assert.throws(() => loadConfig({ env: {}, file }), /g\.json is not valid JSON/);
});

test('the gateway refuses to start on bad delivery targets, naming the problem', async () => {
  await assert.rejects(createApp({ env: {}, log: () => {}, store: { all: () => [], get: () => null, put() {}, del() {}, replace() {}, flushed: async () => {} }, secrets: { get: async () => null },
    config: loadConfig({ env: { GATEWAY_TARGETS: '[{"id":"a","kind":"ftp"}]', GATEWAY_DATA: tmpdir() }, file: null }) }), e => e instanceof ConfigError && /unknown kind "ftp"/.test(e.message));
});

test('.env.example and gateway.config.example.json document every key and are up to date', async () => {
  const env = await readFile(path.join(GATEWAY_DIR, '.env.example'), 'utf8'), json = await readFile(path.join(GATEWAY_DIR, 'gateway.config.example.json'), 'utf8');
  assert.equal(env, envExample(), 'run node tools/config-examples.mjs'); assert.equal(json, configExample(), 'run node tools/config-examples.mjs');
  for (const leaf of describe()) {
    assert.ok(leaf.env && env.includes(`# ${leaf.env}=`), `${leaf.path} (${leaf.env}) missing from .env.example`);
    let o = JSON.parse(json); for (const k of leaf.path.split('.')) o = o?.[k];
    assert.notEqual(o, undefined, `${leaf.path} missing from gateway.config.example.json`);
  }
  const c = loadConfig({ env: {}, file: path.join(GATEWAY_DIR, 'gateway.config.example.json') });
  assert.equal(c.targets.length, 3); assert.deepEqual(c.routes.memo, ['local', 'assistant']);
  assert.equal(await readFile(path.join(GATEWAY_DIR, 'gateway.config.starter.json'), 'utf8'), starterConfig(), 'run node tools/config-examples.mjs');
});

test('the Docker setup works with the starter config: deployment env vars win, relative paths resolve next to the file', async t => {
  const dir = await tempDir(t), file = path.join(dir, 'gateway.config.json');
  await writeFile(file, starterConfig());
  const env = { GATEWAY_CONFIG: file, GATEWAY_DATA: '/data', GATEWAY_WORKER: '1', PORT: '9090', HERMES_URL: 'http://hermes:8642', GATEWAY_TZ: 'Asia/Tokyo' };
  const c = loadConfig({ env });
  assert.equal(c.dataDir, '/data'); assert.equal(c.worker, true); assert.equal(c.port, 9090); assert.equal(c.hermes.url, 'http://hermes:8642'); assert.equal(c.tz, 'Asia/Tokyo');
  // Even the full reference file never undoes the container's deployment settings.
  const full = loadConfig({ env: { ...env, GATEWAY_CONFIG: path.join(GATEWAY_DIR, 'gateway.config.example.json') } });
  assert.equal(full.dataDir, '/data'); assert.equal(full.worker, true); assert.equal(full.port, 9090);
  await writeFile(file, JSON.stringify({ people: { file: './people.json' }, t3: { machinesFile: 'machines/t3.json' }, notify: { policyFile: '/abs/policy.json' } }));
  const rel = loadConfig({ env: { GATEWAY_CONFIG: file, GATEWAY_DATA: dir } });
  assert.equal(rel.people.file, path.join(dir, 'people.json')); assert.equal(rel.t3.machinesFile, path.join(dir, 'machines', 't3.json')); assert.equal(rel.notify.policyFile, '/abs/policy.json');
  assert.ok(!enabledIntegrations(rel).includes('people'), 'a missing people.json leaves People off');
  await writeFile(path.join(dir, 'people.json'), '{"people":[]}');
  assert.ok(enabledIntegrations(rel).includes('people'));
});

test('Home Assistant is offered to the phone only once something is configured for it', () => {
  assert.ok(!enabledIntegrations(loadConfig({ env: {}, file: null })).includes('home-assistant'));
  assert.ok(enabledIntegrations(loadConfig({ env: { HOMEASSISTANT_SCENES: '[{"id":"m","name":"Movie","entity":"scene.movie"}]' }, file: null })).includes('home-assistant'));
  assert.ok(!enabledIntegrations(loadConfig({ env: { HOMEASSISTANT_ENABLED: '0', HOMEASSISTANT_SCENES: '[{"id":"m","name":"Movie","entity":"scene.movie"}]' }, file: null })).includes('home-assistant'));
});

test('GET /glasses/v1/config returns the public subset only', async t => {
  const dir = await tempDir(t), store = createStore(dir);
  const config = loadConfig({ env: { GATEWAY_DATA: dir, GATEWAY_OWNER_NAME: 'Sam', GATEWAY_OWNER_ALIASES: 'sam', HERMES_CALL_URL: 'wss://voice.example/call', LLM_BASE_URL: 'https://llm.example/v1',
    GATEWAY_TARGETS: JSON.stringify([{ id: 'notes', name: 'Notes', kind: 'webhook', url: 'https://hooks.example/secret-path', auth: { type: 'bearer', secret: 'NOTES_TOKEN' } }]), GATEWAY_ROUTES: '{"memo":["notes"]}' }, file: null });
  const app = await createApp({ config, store, log: () => {}, secrets: { get: async name => ({ GLASSES_API_TOKEN: 'phone' }[name] ?? Promise.reject(Object.assign(new Error('x'), { code: 'SECRET_NOT_FOUND' }))) } });
  app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
  t.after(async () => { app.server.closeAllConnections(); await new Promise(r => app.server.close(r)); await store.flushed(); });
  const res = await fetch(`http://127.0.0.1:${app.server.address().port}/glasses/v1/config`, { headers: { Authorization: 'Bearer phone' } });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(body.owner, { name: 'Sam', aliases: ['sam'] }); assert.deepEqual(body.assistant, { name: 'Hermes', workerName: 'Triage' });
  assert.deepEqual(body.targets, [{ id: 'notes', name: 'Notes', kind: 'webhook' }]); assert.deepEqual(body.routes, { memo: ['notes'], ambient: ['notes'], dictation: [] });
  assert.ok(body.integrations.includes('hermes-call')); assert.equal(body.notifyPolicy.schema, 1); assert.equal(body.notifyPolicy.dating.enabled, false);
  const text = JSON.stringify(body);
  for (const secretish of ['hooks.example', 'NOTES_TOKEN', 'voice.example', 'llm.example', 'GLASSES_API_TOKEN', dir]) assert.ok(!text.includes(secretish), secretish);
  assert.equal((await fetch(`http://127.0.0.1:${app.server.address().port}/glasses/v1/config`)).status, 401);
  assert.deepEqual(publicConfig(config, []).targets, []);
});
