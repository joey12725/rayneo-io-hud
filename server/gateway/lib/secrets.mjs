// Credentials are read BY NAME through a pluggable provider, chosen with GATEWAY_SECRETS (or `secrets.provider`
// in gateway.config.json):
//
//   env        (default) the value of the environment variable NAME, or the contents of the file named by NAME_FILE
//              (Docker/Kubernetes secrets).
//   file       a JSON object {"NAME": "value"} at GATEWAY_SECRETS_FILE. Writable: rotated tokens are saved back.
//   infisical  Infisical universal auth, configured entirely from the environment (INFISICAL_URL,
//              INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET, INFISICAL_PROJECT_ID, INFISICAL_ENV, INFISICAL_PATHS)
//              or from an env-style file at INFISICAL_ENV_FILE holding the same keys.
//
// A provider registered with registerSecretsProvider(name, factory) can be selected the same way.
// When GATEWAY_SECRET_ALLOW is set, only the names in it can be read (always required for infisical so a
// misconfigured gateway cannot read the whole project). Values are cached for a short time and never logged.
import fs from 'node:fs';

export class SecretNotFound extends Error {
  constructor() { super('Secret not found'); this.code = 'SECRET_NOT_FOUND'; }
}

const list = v => (Array.isArray(v) ? v : String(v ?? '').split(',')).map(s => String(s).trim()).filter(Boolean);

function parseEnvFile(file) {
  const c = {};
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const l = line.trim(); if (!l || l.startsWith('#') || !l.includes('=')) continue;
    const i = l.indexOf('='); c[l.slice(0, i).trim()] = l.slice(i + 1).trim().replace(/^"|"$/g, '');
  }
  return c;
}

const providers = new Map();
/** Adds a secrets provider: factory({ env, options, fetchImpl, now }) -> { lookup(name, path) -> value, store?(name, value, path) }. */
export function registerSecretsProvider(name, factory) {
  if (!/^[a-z][a-z0-9_-]*$/.test(name)) throw new Error(`Invalid secrets provider name: ${name}`);
  providers.set(name, factory);
}

registerSecretsProvider('env', ({ env }) => ({
  async lookup(name) {
    if (typeof env[name] === 'string' && env[name] !== '') return env[name];
    const file = env[name + '_FILE'];
    if (file) {
      try { return fs.readFileSync(file, 'utf8').replace(/\r?\n$/, ''); }
      catch { throw new Error(`Secret file for ${name} is unreadable`); }
    }
    throw new SecretNotFound();
  },
}));

registerSecretsProvider('file', ({ options }) => {
  const file = options.file;
  if (!file) throw new Error('GATEWAY_SECRETS=file needs GATEWAY_SECRETS_FILE (a JSON object of NAME: value)');
  let data;
  const load = () => {
    if (data) return data;
    try { data = JSON.parse(fs.readFileSync(file, 'utf8')); }
    catch (e) { throw new Error(`Secrets file ${file} is unreadable or not JSON`); }
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error(`Secrets file ${file} must hold a JSON object`);
    return data;
  };
  return {
    async lookup(name) { const d = load(); if (Object.hasOwn(d, name) && d[name] != null) return String(d[name]); throw new SecretNotFound(); },
    async store(name, value) {
      const d = load(); d[name] = value;
      const tmp = file + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(d, null, 2), { mode: 0o600 }); fs.renameSync(tmp, file);
    },
  };
});

registerSecretsProvider('infisical', ({ env, options, fetchImpl, now }) => {
  let token = null, tokenExp = 0, cfg = null, loginPending = null, loginFailure = null;
  function config() {
    if (cfg) return cfg;
    const c = { ...(options.infisicalEnvFile ? parseEnvFile(options.infisicalEnvFile) : {}) };
    for (const k of ['INFISICAL_URL', 'INFISICAL_CLIENT_ID', 'INFISICAL_CLIENT_SECRET', 'INFISICAL_PROJECT_ID', 'INFISICAL_ENV', 'INFISICAL_PATHS']) if (env[k]) c[k] = env[k];
    const next = { base: (c.INFISICAL_URL || 'http://localhost:8080').replace(/\/$/, ''), env: c.INFISICAL_ENV || 'prod', id: c.INFISICAL_CLIENT_ID,
      secret: c.INFISICAL_CLIENT_SECRET, project: c.INFISICAL_PROJECT_ID, paths: list(c.INFISICAL_PATHS || '/') };
    if (!next.id || !next.secret || !next.project) throw new Error('Incomplete Infisical config: set INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET and INFISICAL_PROJECT_ID');
    return (cfg = next);
  }
  async function login() {
    if (token && now() < tokenExp) return token;
    if (loginFailure && now() < loginFailure.until) throw loginFailure.error;
    if (loginPending) return loginPending;
    loginPending = (async () => {
      const c = config();
      const r = await fetchImpl(c.base + '/api/v1/auth/universal-auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ clientId: c.id, clientSecret: c.secret }), signal: AbortSignal.timeout(15000) });
      if (!r.ok) { await r.body?.cancel(); throw new Error('Infisical auth failed'); }
      const j = await r.json();
      if (typeof j.accessToken !== 'string' || !j.accessToken) throw new Error('Infisical auth failed');
      token = j.accessToken; tokenExp = now() + Math.max(60, (j.expiresIn || 600) - 60) * 1000;
      return token;
    })().catch(error => { loginFailure = { error, until: now() + 30000 }; throw error; }).finally(() => { loginPending = null; });
    return loginPending;
  }
  return {
    requiresAllowList: true,
    async lookup(name, path) {
      const t = await login(), c = config();
      for (const p of path ? [path] : c.paths) {
        const url = `${c.base}/api/v3/secrets/raw/${encodeURIComponent(name)}?workspaceId=${c.project}&environment=${c.env}&secretPath=${encodeURIComponent(p)}`;
        const r = await fetchImpl(url, { headers: { Authorization: 'Bearer ' + t }, signal: AbortSignal.timeout(15000) });
        if (r.status === 200) {
          const j = await r.json(); const value = j?.secret?.secretValue;
          if (value != null) return value;
          throw new Error('Infisical response invalid');
        }
        if (!r.bodyUsed) await r.body?.cancel();
        if (r.status !== 404) throw new Error('Infisical lookup failed');
      }
      throw new SecretNotFound();
    },
    async store(name, value, path) {
      const t = await login(), c = config();
      const r = await fetchImpl(`${c.base}/api/v3/secrets/raw/${encodeURIComponent(name)}`, { method: 'PATCH', headers: { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' }, body: JSON.stringify({ workspaceId: c.project, environment: c.env, secretPath: path || c.paths[0], secretValue: value }), signal: AbortSignal.timeout(15000) });
      if (!r.ok) throw new Error(`Could not update secret ${name} (HTTP ${r.status})`);
    },
  };
});

/**
 * options (all optional, normally from lib/config.mjs): { provider, file, allow[], writable[], ttlMs, infisicalEnvFile }.
 * Without options they are read from the environment (GATEWAY_SECRETS, GATEWAY_SECRETS_FILE, GATEWAY_SECRET_ALLOW,
 * GATEWAY_SECRET_WRITABLE, GATEWAY_SECRET_TTL_MS, INFISICAL_ENV_FILE).
 */
export function createSecrets({ env = process.env, options = {}, log = () => {}, fetchImpl = fetch, now = Date.now } = {}) {
  const opts = {
    provider: options.provider ?? env.GATEWAY_SECRETS ?? (env.GATEWAY_SECRETS_FILE ? 'file' : 'env'),
    file: options.file ?? env.GATEWAY_SECRETS_FILE ?? '',
    allow: list(options.allow ?? env.GATEWAY_SECRET_ALLOW),
    writable: list(options.writable ?? env.GATEWAY_SECRET_WRITABLE),
    ttlMs: Number(options.ttlMs ?? env.GATEWAY_SECRET_TTL_MS ?? 300000),
    infisicalEnvFile: options.infisicalEnvFile ?? env.INFISICAL_ENV_FILE ?? '',
  };
  const factory = providers.get(opts.provider);
  if (!factory) throw new Error(`Unknown secrets provider "${opts.provider}" (GATEWAY_SECRETS): use ${[...providers.keys()].join(', ')}`);
  const provider = factory({ env, options: opts, fetchImpl, now });
  const allow = new Set(opts.allow), writable = new Set(opts.writable);
  const allowed = name => allow.size ? allow.has(name) : !provider.requiresAllowList;
  const cache = new Map(), failures = new Map(), pending = new Map();
  let active = 0;
  const waiting = [];
  const acquire = () => active < 4 ? (active++, Promise.resolve()) : new Promise(resolve => waiting.push(resolve));
  const release = () => { const next = waiting.shift(); if (next) next(); else active--; };

  // "NAME" or "NAME@/path" (the path only means something to providers with folders, such as Infisical).
  function parseRef(ref) { const i = ref.indexOf('@'); return i < 0 ? { name: ref, path: null } : { name: ref.slice(0, i), path: ref.slice(i + 1) || null }; }

  async function lookup(ref) {
    const { name, path } = parseRef(ref);
    if (!allowed(name)) throw new Error(`Secret ${name} is not in the gateway allow-list (GATEWAY_SECRET_ALLOW)`);
    const hit = cache.get(ref);
    if (hit && now() - hit.at < opts.ttlMs) return hit.value;
    const value = await provider.lookup(name, path);
    cache.set(ref, { value, at: now() }); failures.delete(ref);
    return value;
  }

  async function get(ref, { optional = false } = {}) {
    try {
      const hit = failures.get(ref);
      if (hit && now() < hit.until) { if (hit.stale && cache.has(ref)) return cache.get(ref).value; throw hit.error; }
      if (!pending.has(ref)) {
        // At most four lookups run at once; the rest wait for a slot instead of failing.
        const job = acquire().then(() => lookup(ref)).catch(error => {
          // A provider hiccup with a value we already had: keep serving it (logged once per failure window).
          const last = cache.get(ref);
          if (last && error?.code !== 'SECRET_NOT_FOUND') {
            if (!failures.has(ref)) log('secrets', `lookup failed, using last value: ${error?.message ?? error}`);
            failures.set(ref, { error, until: now() + 30000, stale: true });
            return last.value;
          }
          failures.set(ref, { error, until: now() + (error?.code === 'SECRET_NOT_FOUND' ? 30000 : 5000) }); throw error;
        }).finally(() => { release(); pending.delete(ref); });
        pending.set(ref, job);
      }
      return await pending.get(ref);
    } catch (e) { if (optional && e.code === 'SECRET_NOT_FOUND') return null; throw e; }
  }

  async function set(ref, value) {
    const { name, path } = parseRef(ref);
    if (!writable.has(name) || !allowed(name)) throw new Error(`Secret ${name} is not writable by the gateway (GATEWAY_SECRET_WRITABLE)`);
    if (!provider.store) throw new Error(`The ${opts.provider} secrets provider is read-only`);
    await provider.store(name, value, path);
    cache.set(ref, { value, at: now() }); failures.delete(ref);
  }

  return { get, set, allowed, provider: opts.provider, invalidate: (ref) => { cache.delete(ref); failures.delete(ref); } };
}
