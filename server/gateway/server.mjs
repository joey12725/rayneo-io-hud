#!/usr/bin/env node
// RayNeo iO HUD gateway: one small multi-API server. Node 24; one optional dependency.
// Each API is a module under ./apis/<name>/index.mjs exporting `register(api)`;
// it is mounted at /<name>/... . The glasses phone app is the first client.
// Configuration: lib/config.mjs (env vars, then gateway.config.json from GATEWAY_CONFIG).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash, timingSafeEqual } from 'node:crypto';
import { createSecrets } from './lib/secrets.mjs';
import { loadConfig } from './lib/config.mjs';
import { createStore } from './lib/store.mjs';
import { HttpError } from './lib/http.mjs';
import { Router, readBody, readJson, sendJson, ndjson } from './lib/web.mjs';
import { acceptWebSocket, rejectUpgrade } from './lib/ws.mjs';

export { Router, readBody, readJson, sendJson, ndjson };

const DIR = path.dirname(fileURLToPath(import.meta.url));


function safeEq(a, b) {
  const digest = value => createHash('sha256').update(String(value)).digest();
  return timingSafeEqual(digest(a), digest(b));
}


export async function createApp(options = {}) {
  const env = options.env ?? process.env;
  const log = options.log ?? ((...a) => console.log(new Date().toISOString(), ...a));
  const config = options.config ?? loadConfig({ env, file: options.configFile ?? env.GATEWAY_CONFIG });
  const secrets = options.secrets ?? createSecrets({ env, options: config.secrets, log });
  const store = options.store ?? createStore(config.dataDir, { log });
  const router = new Router();
  const wsRouter = new Router(), websockets = new Set();
  const modules = [], contexts = {};
  // Client tokens: map of token-secret-name -> scopes. Each module declares its scope (its name).
  // Tokens are looked up through the secret store by name, never configured inline.
  const clients = config.clients;

  const stripBase = p => {
    if (config.basePath && (p === config.basePath || p.startsWith(config.basePath + '/'))) p = p.slice(config.basePath.length) || '/';
    return p.replace(/^\/api(?=\/)/, '');
  };

  const authFailures = new Map(), authPending = new Map();
  let authActive = 0;
  async function authSecret(name) {
    const hit = authFailures.get(name);
    if (hit && Date.now() < hit.until) throw hit.error;
    if (!authPending.has(name)) {
      authActive++;
      const job = Promise.resolve().then(() => secrets.get(name)).catch(error => {
        // A missing token name is stable; anything else (the secrets service restarting, a timeout) is retried after 2 s
        // rather than refusing every request for 30 s.
        const missing = error?.code === 'SECRET_NOT_FOUND';
        if (!missing) log('error', `auth secret ${name} unavailable: ${error?.message ?? error}`);
        authFailures.set(name, { error, until: Date.now() + (missing ? 30000 : 2000) }); throw error;
      }).finally(() => { authActive--; authPending.delete(name); });
      authPending.set(name, job);
    }
    return authPending.get(name);
  }
  async function authorize(req, scope) {
    const h = req.headers.authorization || '';
    const m = /^Bearer (.+)$/.exec(h);
    if (!m) throw new HttpError(401, 'Missing bearer token');
    let unavailable = false;
    for (const [name, scopes] of Object.entries(clients)) {
      if (!scopes.includes(scope)) continue;
      let v; try { v = await authSecret(name); } catch (e) { if (e.code !== 'SECRET_NOT_FOUND') unavailable = true; continue; }
      if (v && safeEq(v, m[1])) return name;
    }
    if (unavailable) throw new HttpError(503, 'Authentication temporarily unavailable');
    throw new HttpError(403, 'Token not valid for this API');
  }

  const api = (name) => ({
    name, secrets, store, log, env, config,
    route(method, p, handler, opts = {}) { router.add(method, '/' + name + p, handler, { scope: opts.scope ?? name, ...opts }); },
    ws(p, handler, opts = {}) { wsRouter.add('GET', '/' + name + p, handler, { scope: opts.scope ?? name, ...opts }); },
  });

  const modDir = options.modulesDir ?? path.join(DIR, 'apis');
  const only = options.modules ?? (config.modules.length ? config.modules : null);
  for (const entry of fs.existsSync(modDir) ? fs.readdirSync(modDir, { withFileTypes: true }) : []) {
    if (!entry.isDirectory() || (only && !only.includes(entry.name))) continue;
    const file = path.join(modDir, entry.name, 'index.mjs');
    if (!fs.existsSync(file)) continue;
    const mod = await import(pathToFileURL(file).href);
    contexts[entry.name] = await mod.register(api(entry.name), options.deps?.[entry.name] ?? {});
    modules.push(entry.name);
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://local');
    // Public route: <publicUrl>/<module>/... . Accept the configured basePath, /api or bare /<module> prefixes.
    const pathname = stripBase(url.pathname);
    if (pathname === '/health') return sendJson(res, 200, { ok: true, modules });
    const hit = router.match(req.method, pathname);
    if (!hit) return sendJson(res, 404, { error: 'Not found' });
    if (hit.notAllowed) return sendJson(res, 405, { error: 'Method not allowed' });
    const { route, params } = hit;
    const ctx = { req, res, url, params, query: Object.fromEntries(url.searchParams) };
    if (!route.opts.public) ctx.client = await authorize(req, route.opts.scope);
    const out = await route.handler(ctx);
    if (out !== undefined && !res.headersSent) sendJson(res, 200, out);
  }

  const logPath = req => req.url.split('?')[0].replace(/(\/abs\/stream\/)[^/]+/g, '$1[redacted]');
  const server = http.createServer((req, res) => {
    const t0 = Date.now();
    handle(req, res).catch((e) => {
      const status = e instanceof HttpError ? e.status : 500;
      if (status >= 500) log('error', req.method, logPath(req), e && e.stack ? e.stack.split('\n').slice(0, 3).join(' | ') : e);
      if (!res.headersSent && status === 503) res.setHeader('Retry-After', '30');
      if (!res.headersSent) sendJson(res, status, { error: e instanceof HttpError ? e.message : 'Internal error' });
      else try { res.end(); } catch {}
    }).finally(() => {
      if (config.accessLog) log(req.method, logPath(req), res.statusCode, Date.now() - t0 + 'ms');
    });
  });
  server.on('upgrade', (req, socket, head) => {
    socket.on('error', () => {});
    socket.pause();
    const timer = setTimeout(() => rejectUpgrade(socket, 504, 'Upgrade timed out'), 15000); timer.unref();
    let ws;
    (async () => {
      const url = new URL(req.url, 'http://local');
      const pathname = stripBase(url.pathname);
      const hit = wsRouter.match(req.method, pathname);
      if (!hit || hit.notAllowed) throw new HttpError(404, 'WebSocket route not found');
      const { route, params } = hit;
      const client = route.opts.public ? undefined : await authorize(req, route.opts.scope);
      if (socket.destroyed || socket.writableEnded) return;
      ws = acceptWebSocket(req, socket, head, route.opts.websocket);
      clearTimeout(timer);
      websockets.add(ws); ws.once('close', () => websockets.delete(ws));
      // Like HTTP handlers, WebSocket handlers receive request context and auth identity.
      const result = route.handler({ req, ws, url, params, client, query: Object.fromEntries(url.searchParams) });
      await result;
    })().catch(e => {
      if (ws) ws.close(1011, 'WebSocket handler failed');
      else rejectUpgrade(socket, e instanceof HttpError ? e.status : 500, e instanceof HttpError ? e.message : 'Internal error');
    }).finally(() => clearTimeout(timer));
  });
  server.requestTimeout = 0; // streams (assistant chat, long-poll events) manage their own timeouts
  return { server, router, wsRouter, websockets, modules, contexts, secrets, store, config };
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  let app;
  try { app = await createApp(); }
  catch (e) { if (e?.code === 'GATEWAY_CONFIG_INVALID') { console.error(e.message); process.exit(78); } throw e; }
  const { server, modules, websockets, store, config } = app;
  server.listen(config.port, config.host, () => console.log(`gateway listening on :${config.port} modules=${modules.join(',')}`));
  const stop = () => { for (const ws of websockets) ws.close(1001, 'Gateway stopping'); server.close(async () => { try { await store.flushed(); process.exit(0); } catch { process.exit(1); } }); };
  process.on('SIGTERM', stop); process.on('SIGINT', stop);
}
