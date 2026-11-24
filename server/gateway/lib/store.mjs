// Bounded JSON collections; debounced, serialized async writes with durable acknowledgements.
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { HttpError } from './http.mjs';

const CAPS = { notifications: 300, docs: 200, reminders: 500, 'assistant-sessions': 100, 'people-heard': 3000, 'people-mutes': 1000, events: 500, 'report-cache': 20, 'beeper-spam': 2000, 'report-state': 5, 't3-watch': 500, 't3-notify': 3000, 'notify-decisions': 500 };
export function createStore(dir, { now = Date.now, log = console.error, io = fsp, read = fs.readFileSync, debounceMs = 25 } = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const mem = new Map(), dirty = new Set(), errors = new Map(), logged = new Set();
  let chain = Promise.resolve(), timer;
  const file = c => path.join(dir, c.replace(/[^a-z0-9_-]/gi, '_') + '.json');
  function failure(c, kind) {
    if (!logged.has(c)) { logged.add(c); log('store', kind, c); }
    return new HttpError(507, 'Storage unavailable');
  }
  function schedule(c) {
    dirty.add(c);
    if (!timer) timer = setTimeout(drain, debounceMs);
  }
  function prune(c, value) {
    let changed = false;
    for (const [id, doc] of value) {
      if ((c === 'notifications' && !(Date.parse(doc.createdAt) > now() - 14 * 86400000)) ||
          (c === 'reminders' && Date.parse(doc.expiresAt) <= now())) { value.delete(id); changed = true; }
    }
    // Notifications/docs are chronological; sessions use Map insertion/access order as LRU.
    if (['notifications', 'docs'].includes(c)) {
      const ordered = [...value].sort((a, b) => (Date.parse(a[1].createdAt) || 0) - (Date.parse(b[1].createdAt) || 0));
      value.clear(); for (const [id, doc] of ordered) value.set(id, doc);
    }
    while (value.size > (CAPS[c] ?? Infinity)) { value.delete(value.keys().next().value); changed = true; }
    if (changed) schedule(c);
    return value;
  }
  function load(c) {
    if (mem.has(c)) return prune(c, mem.get(c));
    let raw;
    try { raw = read(file(c), 'utf8'); }
    catch (e) { if (e.code !== 'ENOENT') throw failure(c, 'read failed'); }
    let value = {};
    if (raw !== undefined) {
      try {
        value = JSON.parse(raw);
        if (!value || typeof value !== 'object' || Array.isArray(value) || Object.values(value).some(d => !d || typeof d !== 'object')) throw new Error('Invalid collection');
      } catch {
        try { fs.renameSync(file(c), file(c) + '.corrupt-' + now() + '-' + randomUUID()); }
        catch { throw failure(c, 'quarantine failed'); }
        failure(c, 'corrupt collection quarantined'); value = {};
      }
    }
    const result = new Map(Object.entries(value)); mem.set(c, result); return prune(c, result);
  }
  async function syncDirectory() {
    let handle;
    try { handle = await io.open(dir, 'r'); await handle.sync(); }
    catch (e) {
      // Windows and some filesystems cannot open/fsync a directory.
      if (!['EINVAL', 'ENOTSUP', 'EISDIR', ...(process.platform === 'win32' ? ['EPERM', 'EACCES', 'EBADF'] : [])].includes(e.code)) throw e;
    } finally { await handle?.close(); }
  }
  async function write(c, data) {
    const target = file(c), tmp = target + '.' + randomUUID() + '.tmp';
    let handle;
    try {
      handle = await io.open(tmp, 'wx', 0o600);
      await handle.writeFile(data); await handle.sync(); await handle.close(); handle = null;
      await syncDirectory(); await io.rename(tmp, target); await syncDirectory(); errors.delete(c);
    } catch {
      errors.set(c, failure(c, 'write failed'));
    } finally { await handle?.close(); await io.unlink(tmp).catch(() => {}); }
  }
  function drain() {
    clearTimeout(timer); timer = null;
    const batch = [...dirty]; dirty.clear();
    if (!batch.length) return chain;
    // Serialize once per batch, outside put(), then use asynchronous file I/O.
    chain = chain.then(async () => {
      for (const c of batch) {
        try { await write(c, JSON.stringify(Object.fromEntries(load(c)))); }
        catch { errors.set(c, failure(c, 'write failed')); }
      }
    });
    return chain;
  }
  return {
    all(c) { return [...load(c).values()]; },
    get(c, id) {
      const value = load(c), doc = value.get(id) ?? null;
      if (doc && c === 'assistant-sessions') { value.delete(id); value.set(id, doc); }
      return doc;
    },
    put(c, doc) {
      const value = load(c), id = doc.id ?? randomUUID();
      if (c === 'reminders' && !value.has(id) && value.size >= CAPS.reminders) throw new HttpError(409, 'Reminder limit reached');
      value.delete(id); value.set(id, { ...doc, id }); prune(c, value); schedule(c); return value.get(id);
    },
    del(c, id) { const had = load(c).delete(id); if (had) schedule(c); return had; },
    replace(c, docs) {
      load(c); // Never overwrite unreadable state, even for a replacement.
      if (c === 'reminders' && docs.length > CAPS.reminders) throw new HttpError(409, 'Reminder limit reached');
      mem.set(c, new Map(docs.map(d => { const id = d.id ?? randomUUID(); return [id, { ...d, id }]; })));
      prune(c, mem.get(c)); schedule(c);
    },
    async flushed() {
      let pending;
      do { pending = drain(); await pending; } while (dirty.size || pending !== chain);
      if (errors.size) throw new HttpError(507, 'Storage unavailable');
    },
  };
}
