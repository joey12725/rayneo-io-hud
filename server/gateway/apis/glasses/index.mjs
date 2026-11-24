// Glasses API module: mounted at /glasses/v1/*. Each file in ./routes exports
// `register(r, ctx)` where r.get/post/del/ws(path, handler, opts) add routes under /v1
// and ctx is shared state (events bus, upstream clients) built in ./lib.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createContext } from './lib/context.mjs';

const DIR = path.dirname(fileURLToPath(import.meta.url));

export async function register(api, deps = {}) {
  const ctx = await createContext(api, deps);
  const r = {
    get: (p, h, o) => api.route('GET', '/v1' + p, h, o),
    post: (p, h, o) => api.route('POST', '/v1' + p, h, o),
    put: (p, h, o) => api.route('PUT', '/v1' + p, h, o),
    del: (p, h, o) => api.route('DELETE', '/v1' + p, h, o),
    ws: (p, h, o) => api.ws('/v1' + p, h, o),
  };
  const dir = path.join(DIR, 'routes');
  for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.mjs')).sort()) {
    const mod = await import(pathToFileURL(path.join(dir, f)).href);
    await mod.register(r, ctx);
  }
  return ctx;
}
