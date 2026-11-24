// Stateless Streamable-HTTP MCP server for the assistant (POST /glasses/v1/mcp, scope glasses.agent).
// tools/list = a few gateway-native tools + every op the phone registered (op "app.verb" -> tool "app_verb").
// tools/call relays to the phone over the agent link and returns the phone's real result.
// No Mcp-Session-Id is issued and GET (server push) is not offered, so answers are plain JSON.
import { HttpError } from '../../../lib/http.mjs';
import { SENSITIVE } from './agent-link.mjs';
import { assistantNotify, assistantDoc } from '../routes/events.mjs';
import { glassesPins } from './beeper-priority.mjs';

export const PROTOCOL_VERSIONS = ['2025-06-18', '2025-03-26', '2024-11-05'];
export const GLASSES_PREFIX = "Controls the user's RayNeo glasses (through their phone app).";
export const toolName = (op) => op.replace(/\./g, '_');

const INSTRUCTIONS = "Tools for the user's RayNeo smart glasses, driven by the Android app on their phone. " +
  'Every tool except glasses_notify, glasses_push_doc, glasses_link_status and glasses_beeper_pins acts on the live glasses and fails with "offline" when the phone is not connected. ' +
  'Prefer a specific tool; otherwise glasses_get_context, then glasses_open_app / glasses_select_row / glasses_press, then glasses_read_screen to verify. ' +
  'Never claim an action happened unless the tool result says ok. Sends, calls, purchases and physical actions may need the user to confirm on the glasses.';

const NATIVE = [
  {
    name: 'glasses_notify', title: 'Add a glasses notification',
    description: `${GLASSES_PREFIX} Adds a notification to the glasses Notifications list; popup:true also shows a short card that lights the lens (use only when the user must see it now). Works even when the phone is offline; it is delivered when it reconnects.`,
    inputSchema: { type: 'object', properties: {
      title: { type: 'string', description: 'Short title (<= 60 chars reads best)', maxLength: 200 },
      body: { type: 'string', description: 'Body text', maxLength: 4000 },
      importance: { type: 'number', description: '0 to 1, default 0.85', minimum: 0, maximum: 1 },
      icon: { type: 'string', description: 'Icon name, e.g. joy (the assistant), clock, calendar, bell' },
      popup: { type: 'boolean', description: 'true = also pop up on the lens (default false: list only)' },
    }, required: ['title', 'body'], additionalProperties: false },
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false },
    run: async (ctx, args) => { const r = assistantNotify(ctx, args); return { ok: true, message: args.popup === true ? 'Notification added and popped up' : 'Notification added', data: { id: r.id } }; },
  },
  {
    name: 'glasses_push_doc', title: 'Send a document to the glasses',
    description: `${GLASSES_PREFIX} Stores a document (notes, article, directions) in the glasses Docs reader. Works even when the phone is offline.`,
    inputSchema: { type: 'object', properties: {
      title: { type: 'string', description: 'Document title', maxLength: 200 },
      text: { type: 'string', description: 'Plain text body', maxLength: 200000 },
    }, required: ['title', 'text'], additionalProperties: false },
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false },
    run: async (ctx, args) => { const r = await assistantDoc(ctx, args); return { ok: true, message: 'Document sent to the glasses', data: { id: r.id } }; },
  },
  {
    name: 'glasses_link_status', title: 'Is the glasses phone connected?',
    description: `${GLASSES_PREFIX} Says whether the phone app is connected right now, when it was last seen, and how many glasses operations it registers.`,
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
    run: async (ctx) => {
      const seen = ctx.agent.lastSeenAt();
      return { ok: true, message: ctx.agent.online() ? 'Phone connected' : 'Phone offline',
        data: { online: ctx.agent.online(), lastSeenAt: seen ? new Date(seen).toISOString() : null, ops: ctx.agent.manifest()?.ops.length ?? 0 } };
    },
  },
  {
    name: 'glasses_beeper_pins', title: 'Beeper chats pinned on the glasses',
    description: `${GLASSES_PREFIX} Lists the Beeper chats the user pinned on the glasses (kept at the top of their glasses chat list), oldest pin first. Read-only; works even when the phone is offline.`,
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
    run: async (ctx) => {
      const pins = glassesPins(ctx).sort((a, b) => String(a.pinnedAt).localeCompare(String(b.pinnedAt))).map(({ id, name, pinnedAt }) => ({ chatId: id, name, pinnedAt }));
      return { ok: true, message: pins.length ? `${pins.length} pinned` : 'No chats pinned on the glasses', data: { pins } };
    },
  },
];
const NATIVE_NAMES = new Set(NATIVE.map(t => t.name));

export function describe(op) {
  const confirm = op.confirm === 'always' ? ' The user always confirms this on the glasses (Y confirm, B cancel).'
    : op.confirm !== 'never' && SENSITIVE.has(op.risk) ? ' The user may be asked to confirm on the glasses.' : '';
  return `${GLASSES_PREFIX} ${op.description}${confirm}`;
}

/** The MCP tool list: native tools plus the phone's tool-exposed ops (live manifest, else the persisted one). */
export function listTools(ctx) {
  const ops = (ctx.agent.manifest()?.ops ?? []).filter(op => op.tool && !NATIVE_NAMES.has(toolName(op.name)));
  return [
    ...NATIVE.map(({ run, ...tool }) => tool),
    ...ops.map(op => ({
      name: toolName(op.name), title: op.title, description: describe(op), inputSchema: op.inputSchema,
      annotations: { title: op.title, readOnlyHint: op.risk === 'read', destructiveHint: SENSITIVE.has(op.risk), openWorldHint: ['send', 'call', 'spend'].includes(op.risk) },
    })),
  ];
}

/** Result text the model reads: message (or OK) plus compact data; failures are "<code>: <message>". */
export function toolResult(result) {
  if (!result.ok) return { content: [{ type: 'text', text: `${result.code ?? 'failed'}: ${result.message ?? 'The action failed.'}` }], isError: true };
  const data = result.data === undefined || result.data === null ? '' : '\n' + JSON.stringify(result.data);
  return {
    content: [{ type: 'text', text: (result.message || 'OK') + data }],
    ...(result.data && typeof result.data === 'object' && !Array.isArray(result.data) ? { structuredContent: result.data } : {}),
    isError: false,
  };
}

/** Runs a tool by MCP name. Returns a phone-style result, or null when no such tool exists. */
export async function callTool(ctx, name, args = {}) {
  const native = NATIVE.find(t => t.name === name);
  if (native) {
    try { return await native.run(ctx, args ?? {}); }
    catch (e) { return { ok: false, code: 'invalid_args', message: e instanceof HttpError ? e.message : 'Tool failed' }; }
  }
  const op = (ctx.agent.manifest()?.ops ?? []).find(o => toolName(o.name) === name);
  // With the phone offline and no manifest known yet, a glasses tool name still gets the clear offline answer.
  if (!op) return !ctx.agent.online() && /^[a-z][a-z0-9]*_[a-z0-9_]+$/.test(name) ? ctx.agent.call(name) : null;
  return ctx.agent.call(op.name, args ?? {});
}

const rpcError = (id, code, message) => ({ jsonrpc: '2.0', id: id ?? null, error: { code, message } });

/** Handles one JSON-RPC message. Returns the response object, or null for notifications. */
export async function handleRpc(ctx, msg) {
  if (!msg || typeof msg !== 'object' || Array.isArray(msg) || msg.jsonrpc !== '2.0' || typeof msg.method !== 'string') return rpcError(msg?.id, -32600, 'Invalid Request');
  const isNotification = msg.id === undefined;
  if (isNotification) return null;
  const { id, method, params = {} } = msg;
  const ok = (result) => ({ jsonrpc: '2.0', id, result });
  switch (method) {
    case 'initialize': {
      const asked = params?.protocolVersion;
      return ok({
        protocolVersion: PROTOCOL_VERSIONS.includes(asked) ? asked : PROTOCOL_VERSIONS[0],
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: 'glasses', title: 'RayNeo glasses', version: '1.0.0' },
        instructions: INSTRUCTIONS,
      });
    }
    case 'ping': return ok({});
    case 'tools/list': return ok({ tools: listTools(ctx) });
    case 'tools/call': {
      if (!params || typeof params.name !== 'string') return rpcError(id, -32602, 'tools/call needs a tool name');
      if (params.arguments !== undefined && (params.arguments === null || typeof params.arguments !== 'object' || Array.isArray(params.arguments))) return rpcError(id, -32602, 'arguments must be an object');
      const result = await callTool(ctx, params.name, params.arguments ?? {});
      if (!result) return rpcError(id, -32602, `Unknown tool: ${params.name}`);
      return ok(toolResult(result));
    }
    default: return rpcError(id, -32601, `Method not found: ${method}`);
  }
}
