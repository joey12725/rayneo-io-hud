import { readJson, ndjson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { text, lru } from '../lib/gateway.mjs';
import { createSession, runTurn } from '../lib/hermes.mjs';
import { assistantName } from '../lib/persona.mjs';

// The assistant (Hermes) from the glasses: POST /assistant/sessions, POST /assistant/chat (NDJSON stream).

export function register(r, ctx) {
  const busy = new Set(), aliases = lru(100);
  r.post('/assistant/sessions', async () => ({ sessionId: await createSession(ctx) }));
  r.post('/assistant/chat', async ({ req, res }) => {
    const body = await readJson(req); const message = text(body.message, 'message');
    let sessionId = body.sessionId ?? await createSession(ctx);
    if (typeof sessionId !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(sessionId)) throw new HttpError(400, 'Invalid sessionId');
    const visited = new Set();
    while (aliases.has(sessionId) && !visited.has(sessionId)) { visited.add(sessionId); sessionId = aliases.get(sessionId); }
    ctx.store.get('assistant-sessions', sessionId); // Refresh the session's bounded LRU entry.
    if (busy.has(sessionId)) throw new HttpError(409, 'Conversation already has a running turn');
    const reserved = new Set([sessionId]); busy.add(sessionId);
    const original = sessionId;
    const out = ndjson(res);
    let left = res.destroyed;
    res.on('close', () => { left = true; });
    let lastStatus = null;
    const emit = (event) => {
      if (event.type === 'status') { if (event.status === lastStatus) return; lastStatus = event.status; }
      if (!left && !res.destroyed) { try { out.write(event); } catch { left = true; } }
    };
    try {
      emit({ type: 'session', sessionId }); emit({ type: 'status', status: 'thinking' });
      // While a glasses turn is in flight, agent-control calls count as coming from the glasses channel.
      const endTurn = ctx.agent.beginGlassesTurn();
      let result;
      try {
        result = await runTurn(ctx, sessionId, message, emit, (id) => {
          busy.add(id); reserved.add(id); aliases.set(sessionId, id); aliases.set(original, id); sessionId = id;
        });
      } finally { endTurn(); }
      ctx.store.put('assistant-sessions', { id: result.sessionId, text: result.text, updatedAt: new Date(ctx.now()).toISOString() });
      // Always publish, including quiet/notifyIfLeft turns; the phone owns notification visibility.
      ctx.events.publish('joy_reply', { ...result, title: `${assistantName(ctx)} replied`, body: result.text, importance: 0.85, open: { screen: 'joy' } });
      emit({ type: 'result', ...result });
    } catch (e) {
      // Streaming headers are already sent, so an upstream 409 (session busy elsewhere) is reported in-band.
      emit({ type: 'status', status: 'error' });
      emit(e?.status === 409 ? { type: 'error', code: 409, message: `${assistantName(ctx)} is still answering another message` } : { type: 'error', message: `${assistantName(ctx)} turn failed; check the session before retrying` });
    } finally { for (const id of reserved) busy.delete(id); if (!left) out.end(); }
  });
}
