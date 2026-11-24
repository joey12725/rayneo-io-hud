// Agent control: the phone's link (WS, phone scope), the MCP endpoint and its REST mirror (agent scope, glasses.agent).
import { readBody, readJson, sendJson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { statusFor } from '../lib/agent-link.mjs';
import { handleRpc, listTools, callTool } from '../lib/mcp.mjs';

export function register(r, ctx) {
  const agent = { scope: 'glasses.agent' };
  const iso = (ms) => ms ? new Date(ms).toISOString() : null;

  // Phone -> gateway. One link per phone; a newer link replaces the old one.
  r.ws('/agent/link', ({ ws, client }) => ctx.agent.attach(ws, client), { websocket: { idleTimeoutMs: 75000 } });

  r.get('/agent/ops', () => ({
    online: ctx.agent.online(), lastSeenAt: iso(ctx.agent.lastSeenAt()), manifestHash: ctx.agent.manifest()?.hash ?? null,
    ops: ctx.agent.manifest()?.ops ?? [], tools: listTools(ctx),
  }), agent);
  r.get('/agent/context', () => ({
    online: ctx.agent.online(), lastSeenAt: iso(ctx.agent.lastSeenAt()), app: ctx.agent.app(), context: ctx.agent.context(),
  }), agent);
  // Same code path as MCP tools/call. Accepts an op name ("glasses.press") or a tool name ("glasses_press").
  r.post('/agent/call', async ({ req, res }) => {
    const b = await readJson(req);
    if (!b || typeof b !== 'object' || Array.isArray(b) || typeof b.op !== 'string' || !b.op) throw new HttpError(400, 'Body must be {op, args?}');
    if (b.args !== undefined && (b.args === null || typeof b.args !== 'object' || Array.isArray(b.args))) throw new HttpError(400, 'args must be an object');
    const result = b.op.includes('.') ? await ctx.agent.call(b.op, b.args ?? {}) : await callTool(ctx, b.op, b.args ?? {})
      ?? { ok: false, code: 'unknown_op', message: `No glasses tool named ${b.op}` };
    sendJson(res, statusFor(result), result);
  }, agent);

  r.post('/mcp', async ({ req, res }) => {
    let body;
    try { body = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8')); }
    catch (e) { if (e instanceof HttpError) throw e; return sendJson(res, 400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } }); }
    const replies = Array.isArray(body) ? (await Promise.all(body.map(m => handleRpc(ctx, m)))).filter(Boolean) : [await handleRpc(ctx, body)].filter(Boolean);
    if (!replies.length) { res.writeHead(202, { 'Cache-Control': 'no-store' }); res.end(); return; }
    sendJson(res, 200, Array.isArray(body) ? replies : replies[0]);
  }, agent);
}
