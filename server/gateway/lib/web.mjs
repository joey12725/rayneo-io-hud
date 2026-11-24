// HTTP helpers shared by server.mjs and API modules (kept separate to avoid a circular import
// with server.mjs, whose top-level await loads the modules).
import { HttpError } from './http.mjs';

const MAX_BODY = 32 * 1024 * 1024;

export class Router {
  constructor() { this.routes = []; }
  add(method, pattern, handler, opts = {}) {
    const keys = [];
    const re = new RegExp('^' + pattern.replace(/\/:([A-Za-z_]+)/g, (_, k) => { keys.push(k); return '/([^/]+)'; }) + '/?$');
    this.routes.push({ method, re, keys, handler, opts });
  }
  match(method, pathname) {
    let allowed = false;
    for (const r of this.routes) {
      const m = r.re.exec(pathname);
      if (!m) continue;
      allowed = true;
      if (r.method !== method) continue;
      const params = {};
      try { r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); }); }
      catch { throw new HttpError(400, 'Invalid path encoding'); }
      return { route: r, params };
    }
    return allowed ? { notAllowed: true } : null;
  }
}
// Reads a request body with a size cap and an overall deadline; a stalled or oversized upload is
// destroyed so its buffers are released (review finding: unbounded slow uploads).
export async function readBody(req, limit = MAX_BODY, timeoutMs = 60000) {
  const chunks = []; let size = 0, timedOut = false;
  const timer = setTimeout(() => { timedOut = true; req.destroy(); }, timeoutMs);
  try {
    for await (const c of req) {
      size += c.length;
      if (size > limit) throw new HttpError(413, 'Request body too large'); // buffers are released; Node closes the socket after the 413
      chunks.push(c);
    }
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(timedOut ? 408 : 400, timedOut ? 'Upload timed out' : 'Upload aborted');
  } finally { clearTimeout(timer); }
  if (timedOut) throw new HttpError(408, 'Upload timed out');
  return Buffer.concat(chunks);
}

export async function readJson(req) {
  const b = await readBody(req, 4 * 1024 * 1024);
  if (!b.length) return {};
  try { return JSON.parse(b.toString('utf8')); } catch { throw new HttpError(400, 'Invalid JSON body'); }
}

export function sendJson(res, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': body.length, 'Cache-Control': 'no-store' });
  res.end(body);
}

// NDJSON stream helper: returns write(obj) and end().
export function ndjson(res) {
  res.writeHead(200, { 'Content-Type': 'application/x-ndjson; charset=utf-8', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' });
  return { write: (o) => res.write(JSON.stringify(o) + '\n'), end: () => res.end() };
}
