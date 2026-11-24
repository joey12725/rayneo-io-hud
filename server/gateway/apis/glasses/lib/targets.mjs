// Delivery targets: where memos, ambient recordings and (optionally) dictations go once the gateway has them.
//
// Config (gateway.config.json):
//   "targets": [
//     { "id": "local", "name": "Recordings folder", "kind": "directory", "path": "/data/recordings" },
//     { "id": "notes", "name": "Notes app", "kind": "webhook", "url": "https://notes.example.com/hook",
//       "auth": { "type": "hmac", "secret": "NOTES_WEBHOOK_SECRET" }, "audio": "multipart" },
//     { "id": "assistant", "name": "Hermes", "kind": "hermes" }
//   ],
//   "routes": { "memo": ["local", "assistant"], "ambient": ["local"], "dictation": ["notes"] }
//
// Kinds shipped here:
//   directory  writes <path>/<YYYY-MM-DD>/<stamp>-<kind>-<id>.{json,md} plus the audio (audio: false to skip it).
//   webhook    POSTs JSON {event: "recording.ready", recording} to `url` (audio: "multipart" sends a multipart body with a
//              `metadata` JSON part and an `audio` file part instead). auth: {type: "bearer", secret} sends
//              Authorization: Bearer <secret value>; {type: "hmac", secret, header?} sends X-Signature: sha256=<hex HMAC
//              of the body> and X-Timestamp. Retries network errors, 408, 429 and 5xx with exponential backoff
//              (retries, backoffMs, timeoutMs); other 4xx fail at once.
//   hermes     posts the transcript into a Hermes session (a new one per item, or `session`) through lib/hermes.mjs, with
//              an optional `message` template ({{owner}} {{assistant}} {{kind}} {{title}} {{date}} {{transcript}}).
//
// Extension point: registerTargetKind('s3', { validate(spec) -> [problems], create(spec, ctx, deps) -> { deliver(item) } }).
// A deliver() that resolves counts as delivered; one that throws is retried by the caller (conversations worker)
// until CONVERSATIONS_MAX_ATTEMPTS. Throw an error with `permanent: true` to fail at once.
//
// The item handed to deliver():
//   { id, kind: 'memo'|'ambient'|'dictation', title, startedAt, endedAt, tz, speechSec, device,
//     transcript: { text, turns: [{ speaker, start, end, at, text, lat?, lon? }] } | null,
//     fixes: [{ at, lat, lon }], audio: { file, ext, mime, bytes, sha256 } | null }
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHmac } from 'node:crypto';

const KINDS = new Map();
const ID = /^[A-Za-z0-9_-]{1,64}$/;
export const ROUTE_NAMES = ['memo', 'ambient', 'dictation'];
export const MIME = { ogg: 'audio/ogg', m4a: 'audio/mp4', wav: 'audio/wav', webm: 'audio/webm', mp3: 'audio/mpeg', aac: 'audio/aac' };

export class DeliveryError extends Error {
  constructor(message, { permanent = false, status } = {}) { super(message); this.permanent = permanent; this.status = status; }
}

/** Adds a target kind. `kind`: lower-case id; `impl`: { validate?(spec) -> string[], create(spec, ctx, deps) -> { deliver(item) } }. */
export function registerTargetKind(kind, impl) {
  if (typeof kind !== 'string' || !/^[a-z][a-z0-9_-]{0,31}$/.test(kind)) throw new Error(`Invalid target kind "${kind}"`);
  if (typeof impl === 'function') impl = { create: impl };
  if (!impl || typeof impl.create !== 'function') throw new Error(`Target kind "${kind}" needs a create(spec, ctx) function`);
  KINDS.set(kind, impl);
}
export const targetKinds = () => [...KINDS.keys()];

const words = v => String(v ?? '').trim();
export function fill(template, vars) {
  return String(template).replace(/\{\{\s*(\w+)\s*\}\}/g, (_, k) => vars[k] ?? '');
}
const sleepFor = ms => new Promise(resolve => setTimeout(resolve, ms));
const dayOf = (iso, tz) => { try { return new Intl.DateTimeFormat('en-CA', { timeZone: tz || 'UTC', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(iso)); } catch { return String(iso).slice(0, 10); } };

/** The JSON form of an item that leaves the gateway (no local file paths). */
export function publicItem(item) {
  const { audio, ...rest } = item;
  return { ...rest, audio: audio ? { ext: audio.ext, mime: audio.mime, bytes: audio.bytes, sha256: audio.sha256 } : null };
}
export function markdown(item) {
  const lines = [`# ${item.title || (item.kind === 'memo' ? 'Memo' : item.kind === 'dictation' ? 'Dictation' : 'Recording')}`, '',
    `- Kind: ${item.kind}`, `- Started: ${item.startedAt}`, ...(item.endedAt ? [`- Ended: ${item.endedAt}`] : []), ...(item.tz ? [`- Time zone: ${item.tz}`] : []), ''];
  const turns = item.transcript?.turns ?? [];
  if (turns.length) for (const t of turns) lines.push(`**${t.speaker || 'Speaker'}**${t.at ? ` (${t.at})` : ''}: ${t.text}`, '');
  else if (item.transcript?.text) lines.push(item.transcript.text, '');
  else lines.push('_No transcript._', '');
  return lines.join('\n');
}

// ---- directory
registerTargetKind('directory', {
  validate: spec => typeof spec.path === 'string' && spec.path ? [] : ['needs "path" (a directory the gateway can write)'],
  create(spec, ctx) {
    const root = path.resolve(ctx.config?.dataDir ?? '.', spec.path);
    const formats = Array.isArray(spec.formats) ? spec.formats : ['json', 'md'];
    return {
      async deliver(item) {
        const dir = path.join(root, dayOf(item.startedAt, item.tz));
        await fs.mkdir(dir, { recursive: true });
        const stamp = String(item.startedAt).replace(/[:.]/g, '-').replace(/[^0-9A-Za-z_-]/g, '').slice(0, 24);
        const base = path.join(dir, `${stamp}-${item.kind}-${String(item.id).replace(/[^A-Za-z0-9_-]/g, '').slice(0, 40)}`);
        const write = async (file, data) => { const tmp = `${file}.tmp`; await fs.writeFile(tmp, data, { mode: 0o600 }); await fs.rename(tmp, file); };
        const files = [];
        if (spec.audio !== false && item.audio?.file) { const f = `${base}.${item.audio.ext}`; await fs.copyFile(item.audio.file, f); files.push(f); }
        if (formats.includes('json')) { const f = `${base}.json`; await write(f, JSON.stringify(publicItem(item), null, 2)); files.push(f); }
        if (formats.includes('md')) { const f = `${base}.md`; await write(f, markdown(item)); files.push(f); }
        return { files: files.map(f => path.relative(root, f)) };
      },
    };
  },
});

// ---- webhook
registerTargetKind('webhook', {
  validate(spec) {
    const p = [];
    if (typeof spec.url !== 'string' || !/^https?:\/\//.test(spec.url)) p.push('needs "url" (http or https)');
    if (spec.auth != null && (typeof spec.auth !== 'object' || !['bearer', 'hmac'].includes(spec.auth.type) || typeof spec.auth.secret !== 'string' || !spec.auth.secret))
      p.push('"auth" must be {type: "bearer"|"hmac", secret: "SECRET_NAME"}');
    if (spec.audio != null && ![false, true, 'multipart', 'none'].includes(spec.audio)) p.push('"audio" must be "multipart" or false');
    if (spec.headers != null && (typeof spec.headers !== 'object' || Object.values(spec.headers).some(v => typeof v !== 'string'))) p.push('"headers" must map names to strings');
    return p;
  },
  create(spec, ctx, deps = {}) {
    const retries = Number.isInteger(spec.retries) ? Math.max(0, Math.min(spec.retries, 10)) : 3;
    const backoffMs = Number(spec.backoffMs) >= 0 ? Number(spec.backoffMs) : 1000;
    const timeoutMs = Number(spec.timeoutMs) > 0 ? Number(spec.timeoutMs) : 30000;
    const sleep = deps.sleep ?? sleepFor;
    const multipart = spec.audio === true || spec.audio === 'multipart';
    async function body(item) {
      const json = JSON.stringify({ event: `${item.kind}.ready`, target: spec.id, recording: publicItem(item) });
      if (!multipart || !item.audio?.file) return { payload: json, signed: json, headers: { 'Content-Type': 'application/json' } };
      const form = new FormData();
      form.append('metadata', new Blob([json], { type: 'application/json' }), 'metadata.json');
      form.append('audio', new Blob([await fs.readFile(item.audio.file)], { type: item.audio.mime }), `${item.id}.${item.audio.ext}`);
      return { payload: form, signed: json, headers: {} };
    }
    return {
      async deliver(item) {
        const { payload, signed, headers } = await body(item);
        const bearer = spec.auth?.type === 'bearer' ? 'Bearer ' + await ctx.secret(spec.auth.secret) : null;
        const hmacKey = spec.auth?.type === 'hmac' ? await ctx.secret(spec.auth.secret) : null;
        let last;
        for (let attempt = 0; attempt <= retries; attempt++) {
          if (attempt) await sleep(backoffMs * 2 ** (attempt - 1));
          // Signed per attempt, so a receiver that checks X-Timestamp freshness accepts late retries too.
          const auth = {};
          if (bearer) auth.Authorization = bearer;
          if (hmacKey != null) {
            const ts = String(Math.floor((ctx.now?.() ?? Date.now()) / 1000));
            auth[spec.auth.header || 'X-Signature'] = 'sha256=' + createHmac('sha256', hmacKey).update(`${ts}.${signed}`).digest('hex');
            auth['X-Timestamp'] = ts;
          }
          let res;
          try {
            res = await (ctx.fetch ?? fetch)(spec.url, { method: spec.method || 'POST', redirect: 'error', signal: AbortSignal.timeout(timeoutMs),
              headers: { ...(spec.headers ?? {}), ...headers, ...auth, 'X-Delivery-Id': `${item.kind}-${item.id}` }, body: payload });
          } catch { last = new DeliveryError('Webhook unreachable'); continue; }
          await res.body?.cancel().catch(() => {});
          if (res.ok) return { status: res.status };
          last = new DeliveryError(`Webhook answered HTTP ${res.status}`, { status: res.status, permanent: res.status >= 400 && res.status < 500 && ![408, 429].includes(res.status) });
          if (last.permanent) throw last;
        }
        throw last;
      },
    };
  },
});

// ---- hermes
export const HERMES_MESSAGE = '{{owner}} recorded a {{kind}} on the glasses ({{date}}){{titleLine}}. Keep it for later; reply with one short line.\n\n<transcript>\n{{transcript}}\n</transcript>';
registerTargetKind('hermes', {
  validate: spec => spec.message != null && typeof spec.message !== 'string' ? ['"message" must be a string template'] : [],
  create(spec, ctx, deps = {}) {
    // Session created for a recording whose turn has not been accepted yet: a retry reuses it instead of opening another.
    const sessions = new Map();
    return {
      async deliver(item) {
        const adapter = deps.hermes ?? await import('./hermes.mjs');
        const text = item.transcript?.turns?.length ? item.transcript.turns.map(t => `${t.speaker || 'Speaker'}: ${t.text}`).join('\n') : words(item.transcript?.text);
        if (!text) throw new DeliveryError('Nothing to send to Hermes (no transcript)', { permanent: true });
        const message = fill(spec.message || HERMES_MESSAGE, {
          owner: ctx.config?.owner?.name ?? 'the user', assistant: ctx.config?.assistant?.name ?? 'Hermes', kind: item.kind === 'ambient' ? 'conversation' : item.kind,
          title: item.title ?? '', titleLine: item.title ? `: "${item.title}"` : '', date: item.startedAt, transcript: text.slice(0, 60000),
        });
        const key = `${item.kind}-${item.id}`;
        const sessionId = spec.session && spec.session !== 'new' ? spec.session : sessions.get(key) ?? await adapter.createSession(ctx);
        if (!spec.session || spec.session === 'new') { sessions.delete(key); sessions.set(key, sessionId); while (sessions.size > 200) sessions.delete(sessions.keys().next().value); }
        let result;
        try { result = await adapter.runTurn(ctx, sessionId, message, () => {}, () => {}); }
        catch (e) {
          // Hermes accepted the transcript and only the reply stream failed: posting it again would duplicate it.
          if (e?.accepted) { sessions.delete(key); return { sessionId, incomplete: true }; }
          throw e;
        }
        sessions.delete(key);
        return { sessionId: result?.sessionId ?? sessionId };
      },
    };
  },
});

/** Validates `targets` and `routes`; returns problem strings (registered with lib/config.mjs). */
export function validateTargets(targets, routes) {
  const problems = [], ids = new Set();
  if (targets != null && !Array.isArray(targets)) return ['targets must be an array of {id, name, kind, ...}'];
  for (const [i, t] of (targets ?? []).entries()) {
    const where = `targets[${i}]${t && typeof t.id === 'string' ? ` (${t.id})` : ''}`;
    if (!t || typeof t !== 'object' || Array.isArray(t)) { problems.push(`${where} must be an object`); continue; }
    if (typeof t.id !== 'string' || !ID.test(t.id)) problems.push(`${where}: "id" must be 1-64 letters, digits, - or _`);
    else if (ids.has(t.id)) problems.push(`${where}: duplicate id "${t.id}"`); else ids.add(t.id);
    if (t.name != null && typeof t.name !== 'string') problems.push(`${where}: "name" must be a string`);
    const kind = KINDS.get(t.kind);
    if (!kind) { problems.push(`${where}: unknown kind "${t.kind}" (known: ${targetKinds().join(', ')}; add more with registerTargetKind)`); continue; }
    for (const p of kind.validate?.(t) ?? []) problems.push(`${where}: ${t.kind} target ${p}`);
  }
  if (routes != null) {
    if (typeof routes !== 'object' || Array.isArray(routes)) return [...problems, 'routes must be {memo: [ids], ambient: [ids], dictation: [ids]}'];
    for (const [name, list] of Object.entries(routes)) {
      if (!ROUTE_NAMES.includes(name)) { problems.push(`routes.${name}: unknown route (use ${ROUTE_NAMES.join(', ')})`); continue; }
      if (!Array.isArray(list) || list.some(x => typeof x !== 'string')) { problems.push(`routes.${name} must be an array of target ids`); continue; }
      for (const id of list) if (targets != null && !ids.has(id)) problems.push(`routes.${name}: no target with id "${id}"`);
    }
  }
  return problems;
}

/** Fills in the defaults: one directory target under <dataDir>/recordings; memo and ambient go to every target. */
export function resolveTargets(config) {
  const targets = config.targets ?? [{ id: 'local', name: 'Recordings folder', kind: 'directory', path: path.join(config.dataDir ?? '.', 'recordings') }];
  const all = targets.map(t => t.id);
  const routes = { memo: all, ambient: all, dictation: [], ...(config.routes ?? {}) };
  return { targets: targets.map(t => ({ ...t, name: t.name || t.id })), routes };
}

/**
 * Builds the live targets for the gateway. `deps` (tests): { hermes adapter, sleep }.
 * Returns { list, get(id), forRoute(name) -> [target], deliver(id, item) }.
 */
export function createTargets(ctx, { targets = [], routes = {} } = {}, deps = {}) {
  const problems = validateTargets(targets, routes);
  if (problems.length) throw new Error('Invalid delivery targets:\n  - ' + problems.join('\n  - '));
  const live = new Map(targets.map(spec => [spec.id, { id: spec.id, name: spec.name || spec.id, kind: spec.kind, impl: KINDS.get(spec.kind).create(spec, ctx, deps) }]));
  return {
    list: () => [...live.values()].map(({ id, name, kind }) => ({ id, name, kind })),
    get: id => live.get(id) ?? null,
    forRoute: name => (routes[name] ?? []).map(id => live.get(id)).filter(Boolean),
    async deliver(id, item) {
      const t = live.get(id);
      if (!t) throw new DeliveryError(`Target ${id} is no longer configured`, { permanent: true });
      return t.impl.deliver(item);
    },
  };
}
