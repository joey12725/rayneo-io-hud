// Agent link: the phone's dedicated control WebSocket (GET /glasses/v1/agent/link, phone scope).
// The phone registers its operations (manifest) and answers `call` frames with `result` frames.
// Calls are never queued or replayed: no open link means an immediate `offline` result.
// Only the manifest is persisted, so tools/list still describes the phone while it is offline.
import { randomUUID, createHash } from 'node:crypto';

export const RISKS = ['read', 'navigate', 'act', 'send', 'call', 'spend', 'physical'];
export const SENSITIVE = new Set(['send', 'call', 'spend', 'physical']);
export const CONFIRMS = ['policy', 'always', 'never'];
export const OP_NAME = /^[a-z][a-z0-9]*\.[a-z][a-z0-9_]*$/;
const HTTP_STATUS = { offline: 503, timeout: 504, busy: 429, unknown_op: 400, invalid_args: 400 };
export const statusFor = (result) => result.ok ? 200 : HTTP_STATUS[result.code] ?? 200;

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const clip = (v, max) => typeof v === 'string' ? v.slice(0, max) : undefined;

/** Normalises one phone-declared op; returns null for anything malformed (it is dropped, not trusted). */
export function cleanOp(op) {
  if (!isObject(op) || typeof op.name !== 'string' || !OP_NAME.test(op.name) || op.name.length > 64) return null;
  if (typeof op.description !== 'string' || !op.description.trim()) return null;
  const schema = isObject(op.inputSchema) ? op.inputSchema : { type: 'object', properties: {} };
  if (schema.type !== 'object' || (schema.properties !== undefined && !isObject(schema.properties))) return null;
  return {
    name: op.name,
    title: clip(op.title, 60) || op.name,
    description: op.description.slice(0, 1000),
    risk: RISKS.includes(op.risk) ? op.risk : 'act',
    confirm: CONFIRMS.includes(op.confirm) ? op.confirm : 'policy',
    tool: op.tool !== false,
    timeoutMs: Math.max(500, Math.min(45000, Number(op.timeoutMs) || 10000)),
    inputSchema: { type: 'object', properties: schema.properties ?? {}, ...(Array.isArray(schema.required) ? { required: schema.required.filter(k => typeof k === 'string') } : {}), additionalProperties: false },
  };
}

export function cleanManifest(manifest) {
  if (!isObject(manifest) || !Array.isArray(manifest.ops)) return null;
  const seen = new Set(), ops = [];
  for (const raw of manifest.ops.slice(0, 400)) {
    const op = cleanOp(raw);
    if (op && !seen.has(op.name)) { seen.add(op.name); ops.push(op); }
  }
  const hash = typeof manifest.hash === 'string' && manifest.hash.length <= 128 ? manifest.hash
    : createHash('sha256').update(JSON.stringify(ops)).digest('hex');
  return { v: 1, hash, ops };
}

/** Minimal JSON-schema check for the subset the phone emits. Returns an error message or null. */
export function validateArgs(schema, args) {
  if (args === undefined || args === null) args = {};
  if (!isObject(args)) return 'arguments must be an object';
  const props = schema?.properties ?? {};
  for (const key of schema?.required ?? []) if (args[key] === undefined || args[key] === null) return `missing required argument "${key}"`;
  for (const [key, value] of Object.entries(args)) {
    const p = props[key];
    if (!p) { if (schema?.additionalProperties === false) return `unknown argument "${key}"`; continue; }
    if (value === null || value === undefined) continue;
    const t = p.type;
    const ok = t === 'string' ? typeof value === 'string'
      : t === 'integer' ? Number.isInteger(value)
      : t === 'number' ? typeof value === 'number' && Number.isFinite(value)
      : t === 'boolean' ? typeof value === 'boolean'
      : t === 'object' ? isObject(value)
      : t === 'array' ? Array.isArray(value) : true;
    if (!ok) return `argument "${key}" must be ${t === 'integer' ? 'an integer' : `a ${t}`}`;
    if (Array.isArray(p.enum) && !p.enum.includes(value)) return `argument "${key}" must be one of ${p.enum.join(', ')}`;
    if (typeof value === 'number' && typeof p.minimum === 'number' && value < p.minimum) return `argument "${key}" must be >= ${p.minimum}`;
    if (typeof value === 'number' && typeof p.maximum === 'number' && value > p.maximum) return `argument "${key}" must be <= ${p.maximum}`;
    if (typeof value === 'string' && typeof p.maxLength === 'number' && value.length > p.maxLength) return `argument "${key}" is longer than ${p.maxLength} characters`;
  }
  return null;
}

function ago(ms) {
  if (ms < 90_000) return `${Math.max(1, Math.round(ms / 1000))} s ago`;
  if (ms < 90 * 60_000) return `${Math.round(ms / 60_000)} min ago`;
  return `${Math.round(ms / 3_600_000)} h ago`;
}

export function createAgentLink(ctx, { graceMs = 2000, confirmMs = 20000 } = {}) {
  const now = () => ctx.now();
  let link = null, lastSeenAt = null, glassesTurns = 0;
  let manifest = cleanManifest(ctx.store.get('agent', 'manifest'));
  const listeners = new Set();

  function setManifest(next) {
    const changed = next.hash !== manifest?.hash;
    manifest = next;
    if (changed) {
      ctx.store.put('agent', { id: 'manifest', ...next, savedAt: new Date(now()).toISOString() });
      for (const fn of listeners) { try { fn(next); } catch {} }
    }
  }

  function failPending(state, code, message) {
    for (const [id, p] of state.pending) { state.pending.delete(id); p.finish({ ok: false, code, message }); }
  }

  function attach(ws, client) {
    const previous = link;
    const state = { ws, client, pending: new Map(), context: null, contextAt: null, app: null, ready: false };
    link = state; lastSeenAt = now();
    if (previous) {
      failPending(previous, 'offline', 'The phone reconnected while this call was running; check its state before retrying.');
      previous.ws.close(1001, 'Replaced by a newer link');
    }
    ctx.log('agent-link', 'open');
    ws.on('message', (data, binary) => {
      lastSeenAt = now();
      if (binary) return;
      let msg; try { msg = JSON.parse(data.toString('utf8')); } catch { return; }
      if (!isObject(msg)) return;
      if (msg.t === 'hello' || msg.t === 'manifest') {
        const next = cleanManifest(msg.manifest);
        if (!next) { ws.send(JSON.stringify({ t: 'error', message: 'Invalid manifest' })); return; }
        state.app = clip(msg.app, 100) ?? state.app; state.ready = true;
        setManifest(next);
        ws.send(JSON.stringify({ t: 'ready', ops: next.ops.length, hash: next.hash }));
      } else if (msg.t === 'context') {
        if (isObject(msg.ctx) && Buffer.byteLength(JSON.stringify(msg.ctx)) <= 64 * 1024) { state.context = msg.ctx; state.contextAt = now(); }
      } else if (msg.t === 'result' && typeof msg.id === 'string') {
        const p = state.pending.get(msg.id); if (!p) return;
        state.pending.delete(msg.id);
        p.finish({ ok: msg.ok === true, ...(typeof msg.message === 'string' ? { message: msg.message.slice(0, 4000) } : {}),
          ...(msg.data !== undefined && msg.data !== null ? { data: msg.data } : {}),
          ...(msg.ok !== true ? { code: typeof msg.code === 'string' ? msg.code.slice(0, 40) : 'failed' } : {}) });
      } else if (msg.t === 'ping') ws.send(JSON.stringify({ t: 'pong' }));
    });
    ws.once('close', () => {
      if (link === state) link = null;
      failPending(state, 'offline', 'The phone disconnected before answering; the action may or may not have happened. Check with glasses_get_context.');
      ctx.log('agent-link', 'close');
    });
  }

  const online = () => !!link?.ready;
  function offline() {
    const seen = lastSeenAt ? ` (last seen ${ago(now() - lastSeenAt)})` : '';
    return { ok: false, code: 'offline', message: `The phone app is not connected to the gateway${seen}. Nothing was done.` };
  }
  const find = (name) => manifest?.ops.find(op => op.name === name) ?? null;

  /** Runs a phone op. Never throws; failures are results with a code. */
  async function call(name, args = {}, { fromGlassesTurn = glassesTurns > 0 } = {}) {
    if (!online()) return offline();
    const spec = find(name);
    if (!spec) return { ok: false, code: 'unknown_op', message: `No glasses operation named ${name}. Use glasses_list_operations.` };
    const invalid = validateArgs(spec.inputSchema, args);
    if (invalid) return { ok: false, code: 'invalid_args', message: invalid };
    // `glasses.run` executes another op by name: its timing and confirmation follow the target.
    const target = name === 'glasses.run' && typeof args?.op === 'string' ? find(args.op) ?? spec : spec;
    const mayConfirm = target.confirm === 'always' || (target.confirm !== 'never' && SENSITIVE.has(target.risk));
    const deadlineMs = target.timeoutMs + (mayConfirm ? confirmMs : 0);
    const state = link, id = 'c_' + randomUUID().replace(/-/g, '').slice(0, 12);
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (!state.pending.delete(id)) return;
        state.ws.send(JSON.stringify({ t: 'cancel', id }));
        resolve({ ok: false, code: 'timeout', message: `The phone did not answer ${name} in time; it may still complete. Check with glasses_get_context.` });
      }, deadlineMs + graceMs);
      timer.unref?.();
      state.pending.set(id, { finish: (result) => { clearTimeout(timer); resolve(result); } });
      const sent = state.ws.send(JSON.stringify({ t: 'call', id, op: name, args: args ?? {}, deadlineMs, origin: { channel: fromGlassesTurn ? 'glasses' : 'other' } }));
      if (sent === false && state.ws.state !== 'open') { state.pending.delete(id); clearTimeout(timer); resolve(offline()); }
    });
  }

  return {
    attach, call, online, find,
    lastSeenAt: () => lastSeenAt,
    manifest: () => manifest,
    app: () => link?.app ?? null,
    /** Last screen context the phone pushed; null while the phone is offline. */
    context: () => link?.context ?? null,
    contextAt: () => link?.contextAt ?? null,
    onManifestChange(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    /** Marks a glasses conversation turn in flight (the heuristic origin for MCP calls). Returns the end function. */
    beginGlassesTurn() { glassesTurns++; let done = false; return () => { if (!done) { done = true; glassesTurns--; } }; },
    glassesTurnActive: () => glassesTurns > 0,
  };
}
