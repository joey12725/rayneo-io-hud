import { connectWebSocket, MAX_BUFFER } from '../../../lib/ws.mjs';
import { cached } from '../lib/gateway.mjs';

// Live voice call: the phone's WebSocket (GET /assistant/call) is relayed to the Hermes voice endpoint at
// hermes.callUrl (HERMES_CALL_URL) with the bearer token from the secret hermes.callTokenSecret (HERMES_CALL_TOKEN).
const secretName = ctx => ctx.config.hermesCallTokenSecret || 'HERMES_CALL_TOKEN';
// Long calls are expected (hours); the cap is only a runaway guard. HERMES_CALL_MAX_MS overrides it, 0 disables it.
export const MAX_CALL_MS = 4 * 60 * 60 * 1000;
const STALE_MS = 30000;
// The phone reconnects a dropped call with this header; it always replaces that phone's previous socket.
const RESUME_HEADER = 'x-assistant-call-resume';

export function maxCallMs(ctx) {
  const value = Number(ctx.config.hermesCallMaxMs ?? MAX_CALL_MS);
  return Number.isFinite(value) && value >= 0 ? value : MAX_CALL_MS;
}

export function callUrl(ctx) {
  if (!ctx.config.hermesCallUrl) throw new Error('Call URL not configured');
  const url = new URL(ctx.config.hermesCallUrl);
  if (!['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('Call URL not configured');
  const host = url.hostname;
  const octets = host.split('.').map(Number);
  const ipv4 = octets.length === 4 && octets.every(n => Number.isInteger(n) && n >= 0 && n <= 255);
  const privateHost = host === '[::1]' || (!host.includes('.') && !host.includes(':')) ||
    (ipv4 && (octets[0] === 10 || octets[0] === 127 || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) || (octets[0] === 192 && octets[1] === 168)));
  if (url.protocol === 'ws:' && !privateHost) throw new Error('Call URL requires wss');
  return url;
}

export function register(r, ctx) {
  const active = new Map();

  async function probe(signal) {
    let url, token;
    try { url = callUrl(ctx); } catch { return { available: false, reason: 'Call URL not configured' }; }
    try { token = await ctx.secret(secretName(ctx)); } catch {}
    if (signal.aborted) return { available: false, reason: 'Call status timed out' };
    if (!token) return { available: false, reason: 'Call credential unavailable' };
    // Any HTTP response proves host reachability. WebSocket-only servers often
    // return 404/405 to HEAD; only the real upgrade can establish call availability.
    url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:';
    try {
      const response = await ctx.fetch(url.origin, { method: 'HEAD', redirect: 'manual', signal });
      await response.body?.cancel();
      return { available: true };
    } catch { return { available: false, reason: 'Call upstream unreachable' }; }
  }
  r.get('/assistant/call/status', () => cached(ctx, 'assistant-call-status', 30000, async () => {
    const abort = new AbortController(); let timer;
    const deadline = new Promise(resolve => {
      timer = setTimeout(() => { abort.abort(); resolve({ available: false, reason: 'Call status timed out' }); }, 3000);
    });
    try { return await Promise.race([probe(abort.signal), deadline]); }
    finally { clearTimeout(timer); }
  }));

  r.ws('/assistant/call', ({ ws: phone, client, req }) => {
    const previous = active.get(client);
    const resume = Boolean(req?.headers?.[RESUME_HEADER]);
    if (previous) {
      // A reconnect means the phone has already given up on its old socket, which may still look alive here.
      if (resume) previous.finish(1001, 'Call resumed elsewhere');
      else if (ctx.now() - previous.lastActivity <= STALE_MS) {
        phone.send(JSON.stringify({ type: 'hangup', reason: 'busy' })); phone.close(1008, 'Call busy'); return;
      } else previous.finish(1001, 'Stale call replaced');
    }

    const started = ctx.now(), abort = new AbortController();
    const waiting = new Set();
    let upstream, ended = false, queued = [], queuedBytes = 0, upBytes = 0, downBytes = 0;
    const session = { lastActivity: started, finish };
    active.set(client, session);
    // An assistant voice call is a glasses conversation: agent-control calls made meanwhile count as from the glasses.
    const endTurn = ctx.agent?.beginGlassesTurn?.() ?? (() => {});
    ctx.log('assistant-call', 'open', { resume });
    const cap = maxCallMs(ctx);
    const timer = cap > 0 ? setTimeout(() => finish(1000, 'Call duration limit'), cap) : null; timer?.unref();
    // Keepalive: a WebSocket ping each way every 20 s keeps proxies from idling the call out and finds dead peers.
    const keepalive = setInterval(() => { if (!ended) { phone.ping(); upstream?.ping(); } }, 20000); keepalive.unref();
    const startupTimer = setTimeout(() => unavailable('Call connection timed out'), 8000); startupTimer.unref();

    function finish(code = 1011, reason = 'Call disconnected') {
      if (ended) return;
      ended = true; endTurn(); clearTimeout(timer); clearInterval(keepalive); clearTimeout(startupTimer); abort.abort(); queued = []; queuedBytes = 0;
      if (active.get(client) === session) active.delete(client);
      // 1006 is local-only and cannot be put on the wire.
      const wireCode = code === 1006 ? 1011 : code;
      phone.close(wireCode, reason); upstream?.close(wireCode, reason);
      phone.resume(); upstream?.resume();
      // No peer-supplied reason, request URL, identity, headers, or payload is logged.
      ctx.log('assistant-call', 'close', { code, durationMs: Math.max(0, ctx.now() - started), upBytes, downBytes });
    }
    function unavailable(detail) {
      if (ended) return;
      phone.send(JSON.stringify({ type: 'hangup', reason: 'unavailable', detail }));
      finish(1011, 'Call unavailable');
    }
    function relay(source, target, data, binary) {
      if (ended) return;
      if (!target.send(data, binary) && !ended && target.state === 'open' && !waiting.has(source)) {
        waiting.add(source); source.pause();
        target.once('drain', () => { waiting.delete(source); if (!ended && source.state === 'open') source.resume(); });
      }
    }
    function watch(peer) {
      peer.on('activity', () => { session.lastActivity = ctx.now(); });
      peer.once('closing', finish);
      peer.once('close', finish);
      peer.once('error', () => finish(1011, 'Call transport failed'));
    }
    watch(phone);
    phone.on('message', (data, binary) => {
      if (ended) return;
      upBytes += data.length;
      if (upstream) relay(phone, upstream, data, binary);
      else {
        queuedBytes += data.length + 64;
        if (queuedBytes > MAX_BUFFER) { finish(1013, 'Call startup buffer limit'); return; }
        queued.push([data, binary]);
      }
    });

    (async () => {
      let url, token;
      try { url = callUrl(ctx); } catch { unavailable('Call URL not configured'); return; }
      try { token = await ctx.secret(secretName(ctx)); } catch {}
      if (ended) return;
      if (!token) { unavailable('Call credential unavailable'); return; }
      try {
        const connected = await connectWebSocket(url, { signal: abort.signal,
          headers: { Authorization: 'Bearer ' + token, [ctx.config.hermesCallDeviceHeader || 'X-Assistant-Device']: ctx.config.hermesCallDevice } });
        if (ended) { connected.close(1001, 'Call ended'); return; }
        upstream = connected; clearTimeout(startupTimer); watch(upstream);
        upstream.on('message', (data, binary) => { if (!ended) { downBytes += data.length; relay(upstream, phone, data, binary); } });
        for (const [data, binary] of queued) relay(phone, upstream, data, binary);
        queued = []; queuedBytes = 0;
      } catch (e) { unavailable(e.status ? 'Upstream HTTP ' + e.status : 'Call upstream unreachable'); }
    })();
  });
}
