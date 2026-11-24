import { HttpError } from '../../../lib/http.mjs';
import { request } from './gateway.mjs';
import { GLASSES_PAGE_GUIDANCE } from './display.mjs';
import { persona, assistantName } from './persona.mjs';

export const GLASSES_CONTEXT_TEMPLATE = `[From {{owner}}'s RayNeo glasses. ${GLASSES_PAGE_GUIDANCE} Plain text, no markdown tables. Control the glasses with the glasses_* tools (see your glasses skill).]`;
/** The line prefixed to every glasses chat message (hermes.glassesContext replaces it). */
export const glassesContext = ctx => persona(ctx?.config?.hermesGlassesContext || GLASSES_CONTEXT_TEMPLATE, ctx);
export const GLASSES_CONTEXT = persona(GLASSES_CONTEXT_TEMPLATE, null);
const one = (value, max = 80) => typeof value === 'string' ? value.replace(/[\r\n\t]+/g, ' ').replace(/[[\]]/g, '').trim().slice(0, max) : '';
/** Per-turn live context, sent as Hermes' ephemeral system message (never stored in the session). */
export function glassesNow(agent, ctx = null) {
  if (!agent) return null;
  if (!agent.online()) return 'Glasses live state: the phone app is offline, so glasses control tools (glasses_*) will fail with "offline"; glasses_notify and glasses_push_doc still work.';
  const c = agent.context() ?? {}, s = c.screen ?? {};
  const where = [one(s.app, 30), one(s.title || s.id, 60)].filter(Boolean).join(' > ') || 'unknown screen';
  const media = c.media && one(c.media.title) ? ` Media ${c.media.playing ? 'playing' : 'paused'}: ${one(c.media.title)}.` : '';
  const call = c.call ? ` Phone call ${one(c.call.phase, 20) || 'active'}${one(c.call.name, 40) ? ' with ' + one(c.call.name, 40) : ''}.` : '';
  const type = one(s.canType, 60) ? ` On this screen glasses_type_text would: ${one(s.canType, 60)}.` : '';
  return `Glasses live state: screen ${where}. ${c.locked ? 'Locked' : 'Unlocked'}.${media}${call}\n` +
    `Control ${persona("{{owner}}'s", ctx)} glasses with the glasses_* tools (call glasses_get_context when unsure); only report an action as done when its tool result is ok.${type}`;
}
const validId = (id) => typeof id === 'string' && /^[A-Za-z0-9_-]{1,200}$/.test(id);
export function toolStatus(name = '') {
  name = name.toLowerCase();
  if (/delegate|subagent|spawn/.test(name)) return 'delegating';
  if (/search|browse|web_/.test(name)) return 'searching';
  if (/memory|recall|remember|brain.*(?:read|find)/.test(name)) return 'remembering';
  if (/write|save|store|create.*doc|reminder|note/.test(name)) return 'saving';
  if (/wait|approval|request.*input/.test(name)) return 'waiting';
  if (/think|reason|plan/.test(name)) return 'thinking';
  return 'working';
}

// Incremental UTF-8 + multiline SSE records, including CRLF and a final record at EOF.
export async function* sseEvents(body) {
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let buffer = '', name = 'message', data = [], size = 0;
  function line(value) {
    size += Buffer.byteLength(value, 'utf8') + 1;
    if (size > 2 * 1024 * 1024) throw new HttpError(502, 'Hermes event too large');
    if (!value) {
      const event = data.length ? { name, payload: JSON.parse(data.join('\n')) } : null;
      name = 'message'; data = []; size = 0; return event;
    }
    if (value.startsWith('event:')) name = value.slice(6).trim();
    if (value.startsWith('data:')) data.push(value.slice(5).replace(/^ /, ''));
    return null;
  }
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true });
    let end;
    while ((end = buffer.indexOf('\n')) >= 0) {
      const value = buffer.slice(0, end).replace(/\r$/, ''); buffer = buffer.slice(end + 1);
      const event = line(value); if (event) yield event;
    }
    if (Buffer.byteLength(buffer) + size > 2 * 1024 * 1024) throw new HttpError(502, 'Hermes event too large');
  }
  buffer += decoder.decode();
  if (buffer) { const event = line(buffer.replace(/\r$/, '')); if (event) yield event; }
  const event = line(''); if (event) yield event;
}

function hermesEnabled(ctx) { if (ctx.config.hermesEnabled === false) throw new HttpError(503, 'Hermes is not enabled on this gateway'); }
async function hermesAuth(ctx) {
  hermesEnabled(ctx);
  const key = await ctx.secret(ctx.config.hermesKeySecret || 'HERMES_API_SERVER_KEY', { optional: true });
  return key ? { Authorization: 'Bearer ' + key } : {};
}
export async function createSession(ctx) {
  const result = await request(ctx, ctx.config.hermesUrl + '/api/sessions', { method: 'POST', body: {}, headers: await hermesAuth(ctx) });
  const id = result?.session?.id;
  if (!validId(id)) throw new HttpError(502, 'Hermes returned invalid session');
  return id;
}
export async function runTurn(ctx, sessionId, message, emit, onSession = () => {}) {
  if (!validId(sessionId)) throw new HttpError(400, 'Invalid sessionId');
  let response;
  try {
    response = await ctx.fetch(ctx.config.hermesUrl + '/api/sessions/' + encodeURIComponent(sessionId) + '/chat/stream', {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(300000),
      headers: { ...await hermesAuth(ctx), 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ message: glassesContext(ctx) + '\n' + message, ...(ctx.agent && ctx.config.hermesLiveContext !== false ? { system_message: glassesNow(ctx.agent, ctx) } : {}) }),
    });
  } catch { throw new HttpError(502, 'Hermes unavailable'); }
  let accepted = false;
  try {
    if (!response.ok || !response.body) throw new HttpError(response.status === 409 ? 409 : 502, 'Hermes could not start the turn');
    accepted = true; // Hermes has the message from here on: a later failure must not make a caller send it again.
    let text = '', completed = false, terminal = false;
    for await (const { name, payload } of sseEvents(response.body)) {
      if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new HttpError(502, 'Invalid Hermes event');
      if (validId(payload.session_id) && payload.session_id !== sessionId) {
        sessionId = payload.session_id; onSession(sessionId); emit({ type: 'session', sessionId });
      }
      if (name === 'assistant.delta') {
        if (typeof payload.delta === 'string') { text += payload.delta; emit({ type: 'status', status: 'speaking' }); emit({ type: 'delta', text: payload.delta }); }
      } else if (name === 'assistant.completed') {
        if (typeof payload.content !== 'string') throw new HttpError(502, 'Invalid Hermes reply');
        text = payload.content; completed = true;
      } else if (['error', 'run.failed', 'run.cancelled', 'run.queued'].includes(name)) throw new HttpError(502, `${assistantName(ctx)} could not complete the turn`);
      else if (name === 'done') { terminal = true; break; }
      else if (name === 'approval.request') emit({ type: 'status', status: 'waiting' });
      else if (name.startsWith('tool.')) emit({ type: 'status', status: toolStatus(payload.tool_name ?? payload.name) });
      else if (name === 'assistant.commentary') emit({ type: 'status', status: 'speaking' });
      else if (name === 'run.completed') emit({ type: 'status', status: 'idle' });
      else if (name === 'run.started') emit({ type: 'status', status: 'thinking' });
    }
    if (!completed || !terminal) throw new HttpError(502, `${assistantName(ctx)} reply interrupted; check the session before retrying`);
    return { text, sessionId };
  } catch (e) {
    if (accepted && e && typeof e === 'object') e.accepted = true;
    throw e;
  } finally { if (!response.body?.locked) await response.body?.cancel().catch(() => {}); }
}
