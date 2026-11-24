// Shared configuration for the notification eval tools. Node 24, no dependencies.
// Secrets are never printed: callers get them through gateway() and only ever put them in headers.
import { existsSync, readFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));   // server/notify-eval
export const REPO = path.resolve(ROOT, '..', '..');
// Everything with real message text, names or call metadata lives here and is git-ignored.
export const PRIVATE = process.env.NOTIFY_EVAL_PRIVATE_DIR || path.join(ROOT, 'private');
export const dirs = {
  raw: path.join(PRIVATE, 'raw'),
  datasets: path.join(PRIVATE, 'datasets'),
  runs: path.join(PRIVATE, 'runs'),
  dist: path.join(PRIVATE, 'dist'),
  datasetsV2: path.join(PRIVATE, 'datasets-v2'),
  labelBatches: path.join(PRIVATE, 'label-batches'),
};
export function ensureDir(dir) { mkdirSync(dir, { recursive: true }); return dir; }

/** Parses a Java .properties file (key=value, # comments). */
export function parseProperties(text) {
  const out = {};
  for (const line of String(text).split(/\r?\n/)) {
    const m = /^\s*([^#!=\s][^=]*?)\s*=\s*(.*)$/.exec(line);
    if (m) out[m[1]] = m[2].trim();
  }
  return out;
}

/** Finds android/secrets.properties: NOTIFY_EVAL_SECRETS_FILE, then this checkout. */
export function secretsFile() {
  const candidates = [process.env.NOTIFY_EVAL_SECRETS_FILE, path.join(REPO, 'android', 'secrets.properties')];
  return candidates.find(p => p && existsSync(p)) ?? null;
}

/** Gateway base URL (…/glasses/v1) and bearer. Env NOTIFY_EVAL_GATEWAY_URL / NOTIFY_EVAL_GATEWAY_TOKEN override the properties file. */
export function gatewayConfig(env = process.env) {
  const file = secretsFile();
  const props = file ? parseProperties(readFileSync(file, 'utf8')) : {};
  const url = (env.NOTIFY_EVAL_GATEWAY_URL || props.GATEWAY_URL || '').replace(/\/$/, '');
  const token = env.NOTIFY_EVAL_GATEWAY_TOKEN || props.GATEWAY_TOKEN || '';
  // POST /notify/hermes is the agent's route (scope glasses.agent): only needed for hermes cases against a deployed gateway.
  const agentToken = env.NOTIFY_EVAL_AGENT_TOKEN || '';
  return { url, token, agentToken, source: env.NOTIFY_EVAL_GATEWAY_URL ? 'env' : file ? 'secrets.properties' : 'none' };
}

/** Read-only GET helper for the live gateway. Throws with the status, never with the token. */
export function gateway(cfg = gatewayConfig()) {
  if (!cfg.url || !cfg.token) throw new Error('No gateway URL/token: set NOTIFY_EVAL_GATEWAY_URL and NOTIFY_EVAL_GATEWAY_TOKEN or provide android/secrets.properties');
  const headers = { Authorization: 'Bearer ' + cfg.token };
  return {
    url: cfg.url,
    async get(p, { timeoutMs = 30000 } = {}) {
      const r = await fetch(cfg.url + p, { headers, signal: AbortSignal.timeout(timeoutMs) });
      if (!r.ok) throw new Error(`GET ${p.split('?')[0]} -> HTTP ${r.status}`);
      return r.json();
    },
    async post(p, body, { timeoutMs = 30000, token } = {}) {
      const started = performance.now();
      if (token === 'agent' && !cfg.agentToken) throw new Error('POST ' + p + ' needs the agent token: set NOTIFY_EVAL_AGENT_TOKEN (or use --local)');
      const auth = token === 'agent' ? { Authorization: 'Bearer ' + cfg.agentToken } : headers;
      const r = await fetch(cfg.url + p, { method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs) });
      const text = await r.text();
      let json = null; try { json = JSON.parse(text); } catch {}
      return { status: r.status, json, text: json ? undefined : text.slice(0, 500), ms: Math.round(performance.now() - started) };
    },
  };
}

export const args = (argv = process.argv.slice(2)) => ({
  opt(name, fallback) { const i = argv.indexOf('--' + name); return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback; },
  flag(name) { return argv.includes('--' + name); },
});
