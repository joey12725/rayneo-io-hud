// Regression: bursts of "Authentication temporarily unavailable" . Lookups beyond four must wait,
// not fail, and an Infisical hiccup must not cost a value the gateway already had.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createSecrets } from '../lib/secrets.mjs';

function rig({ fail = () => false, delayMs = 20 } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'secrets-'));
  const file = path.join(dir, 'infisical.env');
  fs.writeFileSync(file, 'INFISICAL_CLIENT_ID=id\nINFISICAL_CLIENT_SECRET=s\nINFISICAL_PROJECT_ID=p\n');
  let clock = 1_000_000, inFlight = 0, maxInFlight = 0;
  const fetchImpl = async (url) => {
    if (url.includes('/auth/')) return new Response(JSON.stringify({ accessToken: 't', expiresIn: 3600 }), { status: 200 });
    const name = decodeURIComponent(url.split('/raw/')[1].split('?')[0]);
    inFlight++; maxInFlight = Math.max(maxInFlight, inFlight);
    await new Promise(r => setTimeout(r, delayMs));
    inFlight--;
    if (fail(name)) return new Response('boom', { status: 500 });
    return new Response(JSON.stringify({ secret: { secretValue: 'v-' + name } }), { status: 200 });
  };
  const names = Array.from({ length: 10 }, (_, i) => 'S' + i);
  const secrets = createSecrets({ env: { GATEWAY_SECRETS: 'infisical', INFISICAL_ENV_FILE: file, GATEWAY_SECRET_ALLOW: names.join(','), GATEWAY_SECRET_TTL_MS: '1000' }, fetchImpl, now: () => clock });
  return { secrets, names, advance: ms => { clock += ms; }, max: () => maxInFlight };
}

test('ten concurrent lookups all succeed with at most four in flight', async () => {
  const r = rig();
  const values = await Promise.all(r.names.map(n => r.secrets.get(n)));
  assert.deepEqual(values, r.names.map(n => 'v-' + n));
  assert.ok(r.max() <= 4, `max in flight ${r.max()}`);
});

test('an Infisical error after the cache expires keeps serving the last value', async () => {
  let broken = false;
  const r = rig({ fail: () => broken });
  assert.equal(await r.secrets.get('S1'), 'v-S1');
  r.advance(5000); broken = true;
  assert.equal(await r.secrets.get('S1'), 'v-S1');
  assert.equal(await r.secrets.get('S1'), 'v-S1');
});

test('a secret never seen still fails when Infisical errors', async () => {
  const r = rig({ fail: () => true });
  await assert.rejects(r.secrets.get('S2'), /lookup failed/);
});
