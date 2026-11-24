import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { HttpError } from '../../../lib/http.mjs';
import { request, cached, invalidate, items, deadline } from './gateway.mjs';
import { GLASSES_PAGE_GUIDANCE } from './display.mjs';
import { parseProxy, proxyFetch, proxyWebSocket } from './tunnel.mjs';
import { persona } from './persona.mjs';

// Appended to every prompt sent from the glasses ("/glasses" triggers the optional glasses skill in the agent).
export const GLASSES_SUFFIX_TEMPLATE = `\n\n/glasses\n(Sent from {{owner}}'s RayNeo glasses. ${GLASSES_PAGE_GUIDANCE} Plain text.)`;
export const GLASSES_SUFFIX = persona(GLASSES_SUFFIX_TEMPLATE, null);
/** t3.glassesSuffix (T3_GLASSES_SUFFIX) replaces the default suffix; {{owner}} is filled in. */
export const glassesSuffix = ctx => ctx?.config?.t3GlassesSuffix ? '\n\n' + persona(ctx.config.t3GlassesSuffix, ctx) : persona(GLASSES_SUFFIX_TEMPLATE, ctx);
export const alias = (machine) => machine.alias || machine.id;
export const label = (machine) => machine.label || alias(machine);
// Background polls skip a machine only after two consecutive unreachable results, for 15 s doubling to 60 s
// while it stays unreachable. User actions pass `force` and always probe the machine.
export const BACKOFF_MIN_MS = 15000, BACKOFF_MAX_MS = 60000;
const availability = new WeakMap();
export function machineState(ctx, m) {
  let states = availability.get(ctx); if (!states) availability.set(ctx, states = new Map());
  const key = alias(m); if (!states.has(key)) states.set(key, { retryAt: 0, lastSeen: null, online: false, failed: false, failures: 0, cause: null, reported: null, needsPairing: false, os: null, canWrite: null, threads: [] });
  return states.get(key);
}
const now = ctx => ctx.now?.() ?? Date.now();

// machines.json lists the T3 Code servers (t3.machinesFile / T3_MACHINES_FILE; see t3-machines.example.json). When
// another tool rewrites it, mount its directory rather than the single file: a single-file bind mount keeps the inode
// it started with, so an atomic replace would never be seen.
// The file is re-read when its inode, mtime or size changes; a failed read or a half-written file keeps
// the last good list instead of making every machine vanish.
export const machinesPaths = (config) => config.t3MachinesFile ? [config.t3MachinesFile] : [];
const machineFiles = new WeakMap();
async function readMachinesFile(ctx) {
  let memo = machineFiles.get(ctx); if (!memo) machineFiles.set(ctx, memo = { key: null, bad: null, list: [] });
  for (const file of machinesPaths(ctx.config)) {
    let info; try { info = await fs.stat(file); } catch { continue; }
    const key = `${file}:${info.ino}:${info.mtimeMs}:${info.size}`;
    if (memo.key === key || memo.bad === key) return memo.list;
    try {
      const list = JSON.parse(await fs.readFile(file, 'utf8')).machines;
      if (!Array.isArray(list)) throw new Error('no machines array');
      if (memo.key) ctx.log?.('t3 machines', 'reloaded', file, list.length + ' machines');
      memo.key = key; memo.bad = null; memo.list = list; return list;
    } catch (e) { memo.bad = key; ctx.log?.('t3 machines', 'read failed, keeping last good list', file, e.message); return memo.list; }
  }
  return memo.list;
}
// Per-machine base URL override (T3_MACHINE_URLS), so the deploy can route a machine differently from the
// URL in machines.json without editing that file: "desktop=https://prod-x.t3coderelay.com,mac=https://..." or a JSON
// object. A machines.json entry may also carry `gatewayUrl`. The override wins, then gatewayUrl, then url.
export function machineUrls(value) {
  if (!value) return {};
  if (typeof value === 'object') return value;
  const text = String(value).trim();
  if (text.startsWith('{')) { try { return JSON.parse(text); } catch { return {}; } }
  return Object.fromEntries(text.split(/[,\s]+/).filter(Boolean).map(pair => { const i = pair.indexOf('='); return i > 0 ? [pair.slice(0, i), pair.slice(i + 1)] : null; }).filter(Boolean));
}
export async function machines(ctx) {
  if (ctx.config.t3Enabled === false) return [];
  const list = Array.isArray(ctx.config.t3Machines) ? ctx.config.t3Machines : await readMachinesFile(ctx);
  const urls = machineUrls(ctx.config.t3MachineUrls);
  return list.filter(m => m && (m.alias || m.id)).map(m => {
    const url = urls[alias(m)] ?? urls[m.id] ?? m.gatewayUrl;
    return url && url !== m.url ? { ...m, url } : m;
  });
}
// Proxy for one machine (lib/tunnel.mjs): tailnet-only machines (*.ts.net) go through a Tailscale sidecar's SOCKS5 or
// HTTP proxy (T3_TAILNET_PROXY), T3 Connect relays stay direct. T3_MACHINE_PROXIES (alias=url, or a JSON
// object; "direct" turns it off for that machine) wins, then a machines.json `gatewayProxy`, then T3_TAILNET_PROXY
// for *.ts.net hosts. Proxy URLs: http://host:port (CONNECT) or socks5h://host:port.
const TAILNET_HOST = /\.ts\.net\.?$/i;
export function machineProxy(ctx, m) {
  const config = ctx.config ?? {}, map = machineUrls(config.t3MachineProxies);
  const chosen = map[alias(m)] ?? map[m.id] ?? m.gatewayProxy ?? (config.t3TailnetProxy && TAILNET_HOST.test(hostOf(m.url)) ? config.t3TailnetProxy : null);
  return parseProxy(chosen);
}
const hostOf = (value) => { try { return new URL(value).hostname; } catch { return ''; } };
const proxied = new Map();
function transport(ctx, m) {
  const proxy = machineProxy(ctx, m);
  if (!proxy) return { fetch: ctx.fetch, WebSocket: ctx.WebSocket, proxy: null };
  // Tests inject the proxied transports; production builds one pair per proxy URL.
  if (ctx.proxyTransport) return { ...ctx.proxyTransport(proxy), proxy };
  if (!proxied.has(proxy.href)) proxied.set(proxy.href, { fetch: proxyFetch(proxy), WebSocket: proxyWebSocket(proxy) });
  return { ...proxied.get(proxy.href), proxy };
}
export async function machine(ctx, name) {
  const found = (await machines(ctx)).filter(m => m.id === name || m.alias === name);
  if (found.length !== 1) throw new HttpError(404, 'Machine not found');
  return found[0];
}
function safeUrl(m, path) {
  const base = new URL(m.url), url = new URL(path, base);
  if (base.origin !== url.origin || base.username || base.password || (base.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(base.hostname))) throw new HttpError(502, 'Invalid T3 machine URL');
  return url;
}
// A machine listed in machines.json with no token the gateway may read is reported as "needs pairing"
// (never hidden, never counted as a network failure).
async function machineToken(ctx, m) {
  const state = machineState(ctx, m);
  const ref = typeof m.secret === 'string' && m.secret.trim() ? (m.secret.includes('@') ? m.secret : m.secret + '') : null;
  let value = null;
  if (ref) {
    try { value = await ctx.secret(ref, { optional: true }); }
    catch (e) { if (e.code !== 'SECRET_NOT_FOUND' && !/allow-list/.test(e.message ?? '')) throw new HttpError(503, 'Secret service unavailable; retry', { secrets: true }); }
  }
  if (typeof value !== 'string' || !value) {
    if (!state.needsPairing) ctx.log?.('t3 machine', alias(m), 'needs pairing', ref ? 'no readable token' : 'no secret name in machines.json');
    state.needsPairing = true; state.online = false;
    throw new HttpError(503, `${label(m)} needs pairing (no token)`, { needsPairing: true });
  }
  state.needsPairing = false;
  return value;
}
function markOnline(ctx, m, state) {
  state.online = true; state.failed = false; state.failures = 0; state.retryAt = 0; state.needsRepair = false; state.lastSeen = new Date(now(ctx)).toISOString();
  if (state.reported !== 'online') ctx.log?.('t3 machine', alias(m), 'online', state.reported === 'offline' ? 'recovered after ' + (state.cause ?? 'failure') : 'first contact');
  state.reported = 'online'; state.cause = null;
}
function markUnreachable(ctx, m, state, path, cause) {
  state.failures++; state.failed = true; state.cause = cause;
  // One blip on a machine that has answered before is not "offline"; a machine never reached is.
  const confirmed = state.failures >= 2 || !state.lastSeen;
  if (state.failures >= 2) state.retryAt = now(ctx) + Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS * 2 ** (state.failures - 2));
  if (confirmed) state.online = false;
  if (confirmed && state.reported !== 'offline') { ctx.log?.('t3 machine', alias(m), 'offline', path.split('?')[0], cause, 'failures=' + state.failures); state.reported = 'offline'; }
  else if (!confirmed) ctx.log?.('t3 machine', alias(m), 'unreachable once', path.split('?')[0], cause);
}
const offlineText = (ctx, m, state) => `${label(m)} is offline (${state.cause ?? 'unreachable'}); retrying in ${Math.max(1, Math.ceil((state.retryAt - now(ctx)) / 1000))} s`;
const UPSTREAM_TEXT = { 404: 'not found', 409: 'conflict; refresh and try again', 429: 'rate limited' };
export async function http(ctx, m, path, method = 'GET', body, { force = false } = {}) {
  const state = machineState(ctx, m);
  if (!force && state.retryAt > now(ctx)) throw new HttpError(503, offlineText(ctx, m, state), { offline: true, retryable: true, needsRepair: state.needsRepair });
  // One budget for every call, token lookup included (a stalled secret service must not hang a poll): a large
  // shell snapshot can take several seconds, and a short re-probe budget would keep a slow but healthy machine
  // offline forever.
  const limit = 8000;
  const budget = deadline({ now: () => now(ctx) }, limit);
  try {
    let auth;
    try { auth = await budget.run(() => machineToken(ctx, m)); }
    catch (e) { if (e instanceof HttpError && e.detail) throw e; throw new HttpError(503, 'Secret service unavailable; retry', { secrets: true }); }
    try {
      const via = transport(ctx, m);
      const result = await budget.run(async () => request(via.proxy ? { ...ctx, fetch: via.fetch } : ctx, safeUrl(m, path), { method, body, timeoutMs: limit, signal: budget.signal, headers: {
        Authorization: 'Bearer ' + auth, 'x-t3-orchestration-protocol': '2',
      } }));
      markOnline(ctx, m, state);
      return result;
    } catch (e) {
      // Only unreachable machines count towards the backoff: network error, timeout, or a gateway-class status
      // from the relay/proxy in front of T3 (502/503/504 while the machine sleeps). An ordinary HTTP error from
      // one call (401/403/404/429/500 on a single thread) must not take the machine offline.
      const upstream = e.detail?.upstreamStatus;
      const unreachable = !e.status || e.detail?.network || (!upstream && [503, 504].includes(e.status)) || [502, 503, 504].includes(upstream);
      if (e.detail?.needsRepair) { state.needsRepair = true; throw new HttpError(e.status, `${label(m)} rejected the gateway's token; re-pair`, e.detail); }
      if (!unreachable) {
        if (!(e instanceof HttpError)) throw e;
        throw new HttpError(e.status, `${label(m)}: ` + (upstream ? (UPSTREAM_TEXT[upstream] ?? 'T3 error') + ` (HTTP ${upstream})` : e.message), e.detail);
      }
      const timedOut = e.detail?.timeout || (e.status === 504 && !e.detail?.network && !upstream);
      const cause = timedOut ? 'timed out' : upstream ? 'HTTP ' + upstream : e.detail?.network || !e.status ? 'network error' : 'HTTP ' + e.status;
      markUnreachable(ctx, m, state, path, cause);
      throw new HttpError(timedOut ? 504 : 503, timedOut ? `${label(m)} timed out` : `${label(m)} unreachable (${cause})`, { network: true, retryable: true, cause });
    }
  } finally { budget.close(); }
}
// T3 answers a rejected RPC with an Effect Exit failure; the tagged error sits somewhere under `cause`.
export function rejection(exit) {
  let found = null;
  const walk = (v, depth) => {
    if (found || !v || typeof v !== 'object' || depth > 8) return;
    if (Array.isArray(v)) { v.forEach(x => walk(x, depth + 1)); return; }
    if (typeof v._tag === 'string' && /Error$/.test(v._tag)) { found = v; return; }
    for (const key of ['cause', 'error', 'failure', 'left', 'right', 'defect', 'errors', 'first', 'second']) walk(v[key], depth + 1);
  };
  walk(exit, 0);
  return found;
}
const short = (value, max = 120) => typeof value === 'string' ? value.replace(/\s+/g, ' ').trim().slice(0, max) : '';
export async function rpc(ctx, m, method, payload = {}, timeoutMs = 8000, { force = false } = {}) {
  const { ticket } = await http(ctx, m, '/api/auth/websocket-ticket', 'POST', undefined, { force });
  if (typeof ticket !== 'string' || !ticket) throw new HttpError(502, `${label(m)}: T3 ticket unavailable`);
  const url = safeUrl(m, '/ws'); url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.search = new URLSearchParams({ orchestrationProtocol: '2', wsTicket: ticket });
  return new Promise((resolve, reject) => {
    // `sent` separates failures that are safe to retry (the request frame never left) from ones whose
    // outcome is unknown.
    let ws, done = false, sent = false; const chunks = [];
    const timer = setTimeout(() => end(sent ? new HttpError(504, `${label(m)} timed out; inspect state before retrying`) : new HttpError(503, `${label(m)}: T3 connection timed out`, { retryable: true })), timeoutMs);
    function end(error, value) {
      if (done) return; done = true; clearTimeout(timer);
      try { ws?.close(); } catch {}
      error ? reject(error) : resolve(value);
    }
    function rejected(exit) {
      const error = rejection(exit), tag = error?._tag ?? 'unknown';
      ctx.log?.('t3 rpc', alias(m), method, 'rejected', tag, error?.code ?? '', error?.requiredScope ?? '', short(error?.message, 200));
      if (error?.code === 'insufficient_scope' || tag === 'EnvironmentScopeRequiredError') {
        if (error.requiredScope === 'orchestration:operate' || !error.requiredScope) machineState(ctx, m).canWrite = false;
        return new HttpError(403, `${label(m)} is read-only (needs ${error.requiredScope ?? 'write'} scope); re-pair the gateway`, { readOnly: true });
      }
      const reason = short(error?.message) || (error?._tag ? error._tag.replace(/^Orchestration(V2)?/, '').replace(/Error$/, '') : '');
      return new HttpError(502, `${label(m)}: T3 command rejected` + (reason ? ': ' + reason : ''));
    }
    try { ws = new (transport(ctx, m).WebSocket)(url); } catch { end(new HttpError(503, `${label(m)}: T3 connection failed`, { retryable: true })); return; }
    ws.onopen = () => { sent = true; ws.send(JSON.stringify({ _tag: 'Request', id: '1', tag: method, payload, headers: [] })); };
    ws.onerror = () => end(sent ? new HttpError(502, `${label(m)}: T3 disconnected; inspect state before retrying`) : new HttpError(503, `${label(m)}: T3 connection failed`, { retryable: true }));
    ws.onclose = () => end(sent ? new HttpError(502, `${label(m)}: T3 disconnected; inspect state before retrying`) : new HttpError(503, `${label(m)}: T3 connection failed`, { retryable: true }));
    ws.onmessage = (event) => {
      try {
        const messages = JSON.parse(event.data);
        for (const msg of Array.isArray(messages) ? messages : [messages]) {
          if (msg._tag === 'Ping') { ws.send(JSON.stringify({ _tag: 'Pong' })); continue; }
          if (msg._tag === 'Chunk' && msg.requestId === '1') {
            chunks.push(...msg.values); ws.send(JSON.stringify({ _tag: 'Ack', requestId: '1' }));
            end(null, { streamSample: chunks, subscriptionClosed: true });
          }
          if (msg._tag === 'Exit' && msg.requestId === '1') msg.exit?._tag === 'Success' ? end(null, msg.exit.value ?? { ok: true }) : end(rejected(msg.exit));
          if (msg._tag === 'Defect') end(new HttpError(502, `${label(m)}: T3 protocol error`));
        }
      } catch { end(new HttpError(502, `${label(m)}: T3 protocol error`)); }
    };
  });
}
// A user action retries once, at once, when the first attempt failed before anything reached T3.
export async function retryOnce(ctx, m, what, fn) {
  try { return await fn(); }
  catch (e) {
    if (!e.detail?.retryable) throw e;
    ctx.log?.('t3 retry', alias(m), what, e.message);
    const wait = ctx.config?.t3RetryDelayMs ?? 500;
    if (wait > 0) await new Promise(resolve => setTimeout(resolve, wait));
    return fn();
  }
}
const ms = (value) => typeof value === 'number' ? value : Date.parse(value) || 0;
export function status(thread) {
  if (thread.settledOverride === 'settled' || (thread.settledAt && ms(thread.settledAt) >= ms(thread.unsettledAt))) return 'settled';
  if (thread.pendingRuntimeRequest) return thread.pendingRuntimeRequest.kind === 'user_input' ? 'input' : 'approval';
  if (thread.pendingGuestMessageCount > 0) return 'request';
  if (thread.usageLimitResetAt || /usage.?limit|rate.?limit|quota|limit.?exceeded/i.test(`${thread.lastErrorClass || ''} ${thread.lastErrorCode || ''}`)) return 'limited';
  if (thread.status === 'failed') return 'failed';
  if (thread.status === 'running' || ['running', 'preparing'].includes(thread.activityRunStatus)) return thread.blockedOnThreadWait ? 'waiting' : 'working';
  return ms(thread.lastVisitedAt) < ms(thread.latestRunCompletedAt || thread.updatedAt) ? 'done' : 'ready';
}
export function mapThread(ctx, m, thread, projects = []) {
  return { machine: alias(m), id: thread.id, title: thread.title || 'Untitled', project: projects.find(p => p.id === thread.projectId)?.title ?? projects.find(p => p.id === thread.projectId)?.name ?? thread.project?.title ?? thread.projectTitle ?? '',
    provider: providerName(thread), status: status(thread), ageSec: Math.max(0, (ctx.now() - ms(thread.updatedAt)) / 1000), updatedAt: thread.updatedAt ?? null, settledAt: thread.settledAt ?? null, step: null };
}
// GET .../bounded returns {snapshotSequence, projection:{thread, runs, messages, turnItems, runtimeRequests, ...}, historyCursor}.
// The projection's `thread` lacks the shell-level run state (status, pendingRuntimeRequest, ...), so merge the
// shell row for that thread on top of it when available.
// `force` (user actions) ignores the background backoff and any cached projection.
export function bounded(ctx, m, id, { force = false } = {}) {
  const key = 't3:bounded:' + alias(m) + ':' + id;
  if (force) invalidate(ctx, key);
  return cached(ctx, key, 10000, async () => {
    const [res, shell] = await Promise.all([http(ctx, m, '/api/orchestration/threads/' + encodeURIComponent(id) + '/bounded', 'GET', undefined, { force }), shellRaw(ctx, m, { force }).catch(() => null)]);
    const projection = res?.projection ?? res ?? {};
    const row = shell?.threads?.find(t => t.id === id) ?? shell?.archivedThreads?.find(t => t.id === id);
    return { ...projection, thread: { ...(projection.thread ?? {}), ...(row ?? {}) } };
  });
}
export function shellRaw(ctx, m, { force = false } = {}) {
  const key = 't3:shell:' + alias(m), state = machineState(ctx, m);
  // A forced read reuses a cached snapshot only while the machine is not in backoff.
  if (force && state.retryAt > now(ctx)) invalidate(ctx, key);
  return cached(ctx, key, 5000, () => http(ctx, m, '/api/orchestration/shell', 'GET', undefined, { force }));
}
const PROVIDER_NAMES = { claudeAgent: 'Claude Code', claude: 'Claude Code', codex: 'Codex', cursor: 'Cursor', cursor_desktop: 'Cursor', opencode: 'OpenCode', grok: 'Grok', antigravity: 'Antigravity', pi: 'Pi', muse: 'Muse Code', kiro: 'Kiro' };
export const providerName = (t) => { const id = t.providerInstanceId ?? t.modelSelection?.instanceId ?? t.modelSelection?.provider ?? t.provider ?? ''; return PROVIDER_NAMES[id] ?? id; };
export function turnItems(projection) {
  const values = [ ...(projection.turnItems ?? projection.items ?? []), ...(projection.turns ?? []).flatMap(t => t.items ?? t.turnItems ?? []), ...(projection.runs ?? []).flatMap(r => [ ...(r.items ?? []), ...(r.turns ?? []).flatMap(t => t.items ?? []) ]) ];
  return [...new Map(values.map((v, i) => [v.id ?? i, v])).values()];
}
const data = (item) => ({ ...item, ...(item.data ?? {}), ...(item.payload ?? {}) });
const stringify = (value) => typeof value === 'string' ? value : value == null ? '' : JSON.stringify(value);
export function step(item) {
  if (!item) return null;
  const v = data(item), input = v.input ?? {};
  const detail = typeof input === 'string' ? input : input.command ?? input.fileName ?? '';
  const labels = {
    command_execution: 'Running command' + (detail || v.command ? ': ' + stringify(detail || v.command) : ''),
    file_change: 'Editing files' + (v.fileName || input.fileName ? ': ' + (v.fileName || input.fileName) : ''),
    web_search: 'Searching the web' + (v.patterns?.[0] || input.patterns?.[0] || v.query ? ': ' + (v.patterns?.[0] || input.patterns?.[0] || v.query) : ''),
    reasoning: 'Thinking' + (v.title ? ': ' + v.title : ''), dynamic_tool: 'Using ' + (v.toolName ?? input.toolName ?? 'tool'), assistant_message: 'Writing',
  };
  const kind = v.type ?? v.kind;
  const label = { command_execution: 'Running command', file_change: 'Editing files', web_search: 'Searching the web', reasoning: 'Thinking', dynamic_tool: 'Using ' + (v.toolName ?? input.toolName ?? 'tool'), assistant_message: 'Writing' }[kind] ?? 'Working';
  const full = labels[kind] ?? v.title ?? 'Working';
  const what = full.startsWith(label + ': ') ? full.slice(label.length + 2) : full === label ? '' : full;
  return { kind, label, what, startedAt: v.startedAt ?? v.createdAt ?? null };
}
const running = (v) => ['running', 'in_progress', 'preparing'].includes(v.status);
const finished = (v) => ['completed', 'complete', 'succeeded', 'failed', 'cancelled'].includes(v.status) || Boolean(v.completedAt);
export function working(projection) {
  const all = turnItems(projection);
  const current = all.findLast(running);
  const updated = all.reduce((a, i) => ms(i.updatedAt) > ms(a) ? i.updatedAt : a, null);
  return { now: current ? { ...step(current), lastOutputAt: updated } : null,
    recent: all.filter(i => finished(i) && (i.type ?? i.kind) !== 'reasoning').slice(-4).map(i => ({ label: [step(i).label, step(i).what].filter(Boolean).join(': '), durSec: Math.max(0, (ms(i.completedAt ?? i.updatedAt) - ms(i.startedAt ?? i.createdAt)) / 1000) })) };
}
export function requestView(thread, projection) {
  const all = turnItems(projection), pending = thread.pendingRuntimeRequest;
  if (!pending && thread.pendingGuestMessageCount > 0) {
    const guests = projection.guestMessages ?? projection.pendingGuestMessages ?? (projection.messages ?? []).filter(m => m.role === 'guest' || m.createdBy === 'guest');
    return { id: guests.at(-1)?.id ?? 'guest', kind: 'guest', questions: [], approval: null,
      messages: guests.map(m => ({ who: 'guest', text: stringify(m.text ?? m.content ?? m.data?.text), at: m.createdAt ?? m.at ?? null })) };
  }
  const input = all.findLast(i => (i.type ?? i.kind) === 'user_input_request' && !finished(i) && (!pending?.id || i.requestId === pending.id || i.runtimeRequestId === pending.id || i.id === pending.turnItemId)) ?? (!pending ? null : all.findLast(i => (i.type ?? i.kind) === 'user_input_request' && !finished(i)));
  if (pending && pending.kind !== 'user_input') {
    const linked = all.find(i => i.id === pending.turnItemId || i.id === pending.itemId || i.runtimeRequestId === pending.id);
    const v = data(linked ?? {});
    return { id: pending.id ?? pending.requestId, kind: 'approval', questions: [], approval: { kind: pending.kind, command: stringify(v.command ?? v.input?.command ?? v.input ?? ''), reason: pending.reason ?? v.reason ?? '' } };
  }
  if (!input && !pending) return null;
  const v = data(input ?? {}), raw = v.questions ?? v.input?.questions ?? pending?.questions ?? [];
  const questions = raw.map(q => ({ id: q.id, header: q.header ?? '', question: q.question ?? '', options: (q.options ?? []).map(o => typeof o === 'string' ? { label: o, description: '' } : { label: o.label, description: o.description ?? '' }), multi: Boolean(q.options?.length && (q.multiSelect ?? q.multi)) }));
  return { id: pending?.id ?? pending?.requestId ?? v.requestId ?? v.runtimeRequestId ?? v.id, kind: questions.some(q => q.multi) ? 'multi' : questions.some(q => q.options.length) ? 'choice' : 'text', questions, approval: null };
}
export function detail(ctx, m, projection, projects = []) {
  const raw = projection.thread ?? projection;
  const thread = mapThread(ctx, m, raw, projects), work = working(projection);
  if (thread.status === 'working' || thread.status === 'waiting') thread.step = work.now;
  const messages = projection.messages ?? turnItems(projection).filter(i => ['user_message', 'assistant_message'].includes(i.type));
  const role = (m) => m.role ?? m.who ?? m.createdBy ?? m.type;
  const history = messages.filter(m => !m.streaming || m.text).filter(m => ['user', 'assistant', 'agent', 'user_message', 'assistant_message'].includes(role(m))).map(m => ({ who: ['user', 'user_message'].includes(role(m)) ? 'user' : 'agent', text: stringify(m.text ?? m.content ?? m.data?.text ?? m.data?.content), at: m.createdAt ?? m.at ?? m.updatedAt ?? null }));
  const latest = history.findLast(m => m.who === 'agent');
  return { thread, latest: latest ? { text: latest.text, at: latest.at } : null, history, working: ['working', 'waiting'].includes(thread.status) ? work : null, request: requestView(raw, projection), runId: raw.activeRunId ?? raw.latestRunId ?? projection.runId ?? projection.runs?.at(-1)?.id ?? null };
}
// One row per machine, from the same state the thread list uses, so "x/y online", the offline count, each
// environment's status and the thread list always agree. `os` and write access come from small cached reads.
export function machineRow(ctx, m) {
  const state = machineState(ctx, m);
  return { machine: alias(m), label: label(m), online: state.online, lastSeen: state.lastSeen, os: state.os ?? null, canWrite: state.canWrite !== false, paired: !state.needsPairing,
    ...(state.online ? {} : { lastSeenLabel: state.lastSeen ? 'Last seen ' + state.lastSeen : 'Never seen', ...(state.cause ? { cause: state.cause } : {}) }),
    ...(state.needsPairing ? { needsPairing: true } : {}), ...(state.needsRepair ? { needsRepair: true } : {}) };
}
// Not under the 't3:' prefix: writes invalidate 't3:*', and these do not change with thread state.
function machineMeta(ctx, m) {
  const state = machineState(ctx, m);
  return Promise.all([
    cached(ctx, 't3meta:env:' + alias(m), 600000, () => http(ctx, m, '/.well-known/t3/environment')).then(d => { state.os = d?.platform?.os ?? d?.os ?? state.os; }).catch(() => {}),
    cached(ctx, 't3meta:session:' + alias(m), 300000, () => http(ctx, m, '/api/auth/session')).then(s => { if (Array.isArray(s?.scopes)) state.canWrite = s.scopes.includes('orchestration:operate'); }).catch(() => {}),
  ]);
}
export async function list(ctx) {
  return cached(ctx, 't3:list', 5000, async () => {
    const results = await Promise.all((await machines(ctx)).map(async m => {
      const state = machineState(ctx, m);
      try {
        const shell = await shellRaw(ctx, m);
        // Subagent/child threads (lineage.parentThreadId) are nested under their parent in T3's
        // sidebar; listing them at the root floods the glasses with unvisited "done" rows.
        const roots = (shell.threads ?? []).filter(raw => !raw.lineage?.parentThreadId && !raw.archivedAt && !raw.deletedAt);
        const [threads] = await Promise.all([Promise.all(roots.map(async raw => {
          const thread = mapThread(ctx, m, raw, shell.projects);
          if (thread.status === 'working') {
            try { const work = working(await bounded(ctx, m, raw.id)); thread.step = work.now; } catch {}
          }
          return thread;
        })), machineMeta(ctx, m)]);
        state.threads = threads;
        return { machine: machineRow(ctx, m), threads };
      } catch {
        // A machine that fails keeps its last-known threads; http() has already logged any state change.
        return { machine: machineRow(ctx, m), threads: state.threads };
      }
    }));
    return { machines: results.map(r => r.machine), threads: results.flatMap(r => r.threads).sort((a, b) => ms(b.updatedAt) - ms(a.updatedAt)) };
  });
}
export async function environments(ctx) { return (await list(ctx)).machines; }
// T3 answers GET /api/projects with {projects:[...], updatedAt}; older shapes are accepted too.
export const projectList = (value) => Array.isArray(value) ? value : value?.projects ?? value?.items ?? value?.data ?? [];
export function projects(ctx, m, { force = false } = {}) {
  const key = 't3:projects:' + alias(m);
  if (force && machineState(ctx, m).retryAt > now(ctx)) invalidate(ctx, key);
  return cached(ctx, key, 30000, async () => projectList(await http(ctx, m, '/api/projects', 'GET', undefined, { force })).filter(p => p && p.id && !p.archivedAt && !p.deletedAt));
}
export async function needYouCounts(ctx) {
  const { threads } = await list(ctx);
  return { needYou: threads.filter(t => ['input', 'approval', 'request', 'limited', 'failed'].includes(t.status)).length, done: threads.filter(t => t.status === 'done').length };
}
export function requireWritable(ctx, m) {
  if (machineState(ctx, m).canWrite === false) throw new HttpError(403, `${label(m)} is read-only; re-pair the gateway with write access`, { readOnly: true });
}
// User action: probes the machine even during backoff, and retries once if nothing reached T3.
export async function dispatch(ctx, m, command) {
  requireWritable(ctx, m);
  const payload = { ...command, commandId: command.commandId ?? randomUUID() };
  const result = await retryOnce(ctx, m, command.type, () => rpc(ctx, m, 'orchestration.dispatchCommand', payload, 8000, { force: true }));
  invalidate(ctx, 't3:'); return result;
}
export async function sendMessage(ctx, m, id, text, mode = 'send', projection) {
  requireWritable(ctx, m);
  projection ??= await retryOnce(ctx, m, 'read thread', () => bounded(ctx, m, id, { force: true }));
  const raw = projection.thread ?? projection;
  if (status(raw) === 'settled') await dispatch(ctx, m, { type: 'thread.unsettle', threadId: id, reason: 'user' });
  const runId = raw.activeRunId ?? raw.latestRunId ?? projection.runId;
  if (mode === 'steer' && !runId) throw new HttpError(409, 'No active run to steer');
  return dispatch(ctx, m, { type: 'message.dispatch', threadId: id, messageId: randomUUID(), text: text + glassesSuffix(ctx), createdBy: 'user', creationSource: 'mobile', attachments: [], deliveryIntent: mode === 'steer' ? 'steer' : 'auto',
    dispatchMode: mode === 'steer' ? { type: 'steer_active', targetRunId: runId } : { type: mode === 'queue' ? 'queue_after_active' : 'start_immediately' } });
}
export function usageWindows(limits, now) {
  return (limits?.windows ?? []).map(w => {
    const usedPercent = Math.max(0, Math.min(100, Number(w.usedPercent) || 0));
    const duration = Number(w.windowDurationMins ?? w.durationMins) || 0;
    const elapsedPercent = duration ? Math.max(0, Math.min(100, 100 * (1 - (ms(w.resetsAt) - now) / (duration * 60000)))) : usedPercent;
    return { label: w.label ?? w.name ?? '', usedPercent, resetsAt: w.resetsAt ?? null, windowDurationMins: duration, pace: usedPercent > elapsedPercent + 5 ? 'ahead' : usedPercent < elapsedPercent - 5 ? 'under' : 'on' };
  });
}
export async function usage(ctx) {
  return cached(ctx, 't3:usage', 10000, () => loadUsage(ctx));
}
async function loadUsage(ctx) {
  const all = await Promise.all((await machines(ctx)).map(async m => {
    try {
      const config = await rpc(ctx, m, 'server.getConfig');
      return (config.providers ?? []).filter(p => p.enabled !== false && p.usageLimits).map(p => ({ provider: p.displayName ?? p.driver ?? p.instanceId, machine: alias(m), instanceId: p.instanceId,
        windows: usageWindows(p.usageLimits, ctx.now()), credits: (Array.isArray(p.usageLimits.resetCredits) ? p.usageLimits.resetCredits : p.usageLimits.resetCredits?.options ?? []).map(c => ({ creditId: c.creditId ?? c.id, label: c.label ?? '', description: c.description ?? '', availableCount: c.availableCount ?? 0 })),
        _key: `${p.usageLimits.sourceId ?? p.instanceId ?? p.driver}:${p.usageLimits.accountId ?? p.accountId ?? p.account?.id ?? p.driver}`, _at: ms(p.usageLimits.checkedAt) }));
    } catch (e) { ctx.log('t3 usage', alias(m), e.message); return []; }
  }));
  const unique = new Map();
  // Same account seen from several machines: prefer a row that has windows, then the freshest check.
  const better = (a, b) => (b.windows.length > 0) - (a.windows.length > 0) || b._at - a._at;
  for (const row of all.flat().filter(r => r.windows.length || r.credits.length)) if (!unique.has(row._key) || better(unique.get(row._key), row) > 0) unique.set(row._key, row);
  return [...unique.values()].map(({ _key, _at, ...row }) => row);
}
// Project default, then the environment's defaults, then the model of the newest thread in that project
// (what the user actually uses there), then T3's fallback, then the default model of a ready provider.
export function modelSelection(project, config, recent) {
  const selected = [project.defaultModelSelection, config.threadDefaults?.modelSelection, config.settings?.defaultModelSelection, config.defaults?.modelSelection, config.defaultModelSelection, recent, config.settings?.fallbackModelSelection].find(s => s?.model);
  if (selected) return selected;
  const providers = (config.providers ?? []).filter(p => p.enabled !== false && (p.status === 'ready' || p.status?.state === 'ready'));
  const provider = providers.find(p => p.default || p.isDefault) ?? providers.find(p => /claudeAgent/i.test(typeof p.driver === 'string' ? p.driver : JSON.stringify(p.driver))) ?? providers.find(p => p.models?.length);
  const model = provider?.models?.find(m => m.default || m.isDefault) ?? provider?.models?.[0];
  if (!model) throw new HttpError(503, 'No default T3 model available');
  const name = typeof provider.driver === 'string' ? provider.driver : typeof provider.id === 'string' ? provider.id : undefined;
  return { ...(name ? { provider: name } : {}), instanceId: provider.instanceId, model: typeof model === 'string' ? model : model.slug ?? model.id };
}
export function recentModelSelection(shell, projectId) {
  return (shell?.threads ?? []).filter(t => t.projectId === projectId && t.modelSelection?.model).sort((a, b) => ms(b.updatedAt) - ms(a.updatedAt))[0]?.modelSelection;
}
/** Raw T3 names for dictation cleanup (slash commands, skills, models) from server.getConfig.
 * The caller filters and caps them (cleanup.mjs mergeVocab). Cached 5 minutes under a key that
 * the `t3:` invalidation after each send does not clear. */
export function vocabularyFrom(config, instanceId) {
  const providers = (config?.providers ?? []).filter(p => p && p.enabled !== false);
  if (instanceId) providers.sort((a, b) => (b.instanceId === instanceId) - (a.instanceId === instanceId));
  const str = (v) => typeof v === 'string' ? v : '';
  const commands = [], skills = [], models = [];
  const add = (holder) => {
    for (const c of Array.isArray(holder?.slashCommands) ? holder.slashCommands : []) commands.push(str(c?.name ?? c));
    for (const s of Array.isArray(holder?.skills) ? holder.skills : []) {
      if (!s || s.enabled === false) continue;
      skills.push(str(s.name));
      if (s.userInvocable !== false) commands.push(str(s.name));
    }
  };
  for (const p of providers) {
    add(p);
    for (const w of Array.isArray(p.workspaceSnapshots) ? p.workspaceSnapshots : []) add(w);
    for (const m of Array.isArray(p.models) ? p.models : []) {
      if (typeof m === 'string') { models.push(m); continue; }
      models.push(str(m?.shortName), str(m?.name), str(m?.slug), ...(Array.isArray(m?.aliases) ? m.aliases.map(str) : []));
    }
  }
  add(config);
  return { keywords: ['ultrathink'], commands: commands.filter(Boolean), skills: skills.filter(Boolean), models: models.filter(Boolean) };
}
export async function vocabulary(ctx, machineName, threadId) {
  const m = await machine(ctx, machineName);
  const [config, shell] = await Promise.all([
    cached(ctx, 'vocab:t3:' + alias(m), 300000, () => rpc(ctx, m, 'server.getConfig')),
    threadId ? shellRaw(ctx, m).catch(() => null) : null,
  ]);
  const row = shell?.threads?.find(t => t.id === threadId);
  return vocabularyFrom(config, row?.providerInstanceId ?? row?.modelSelection?.instanceId);
}
