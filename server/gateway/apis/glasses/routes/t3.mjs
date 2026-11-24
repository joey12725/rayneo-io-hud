import { randomUUID } from 'node:crypto';
import { readJson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { text, limit, invalidate } from '../lib/gateway.mjs';
import { autoWatch } from '../lib/notify-route.mjs';
import { alias, label, machines, machine, machineState, rpc, bounded, detail, list, environments, projects, sendMessage, dispatch, usage, modelSelection, recentModelSelection, requireWritable, retryOnce, glassesSuffix, shellRaw } from '../lib/t3.mjs';

const ms = (value) => Date.parse(value ?? '') || 0;
// Best effort: a failed store write must not fail a command that already reached T3.
function watchFromGlasses(ctx, m, id, title) {
  try { autoWatch(ctx, alias(m), id, title); } catch (e) { ctx.log?.('t3 watch', alias(m), 'auto-watch failed', e?.message ?? 'error'); }
}

export function register(r, ctx) {
  r.get('/t3/threads', async () => {
    const result = await list(ctx); return { machines: result.machines, threads: result.threads.filter(t => !['ready', 'settled'].includes(t.status)) };
  });
  // T3-03: the 25 most recently settled threads, newest settlement first. Threads already read but not yet
  // settled ("ready") are listed first (max 10) so they stay openable from the glasses; they don't use the 25.
  r.get('/t3/settled', async ({ query }) => {
    const { threads } = await list(ctx), n = limit(query.limit, 25, 25);
    const settled = threads.filter(t => t.status === 'settled').sort((a, b) => Date.parse(b.settledAt || b.updatedAt || 0) - Date.parse(a.settledAt || a.updatedAt || 0)).slice(0, n);
    const ready = threads.filter(t => t.status === 'ready').slice(0, 10);
    return [...ready, ...settled];
  });
  // Opening a thread is a user action: it probes the machine even while background polls back off.
  r.get('/t3/threads/:machine/:id', async ({ params }) => {
    const m = await machine(ctx, params.machine);
    const projection = await retryOnce(ctx, m, 'read thread', () => bounded(ctx, m, params.id, { force: true }));
    const [known, shell] = await Promise.all([projects(ctx, m).catch(() => []), shellRaw(ctx, m).catch(() => null)]);
    return detail(ctx, m, projection, [...known, ...(shell?.projects ?? [])]);
  });
  r.post('/t3/threads/:machine/:id/messages', async ({ req, params }) => {
    const b = await readJson(req), mode = b.mode ?? 'send';
    if (!['send', 'queue', 'steer'].includes(mode)) throw new HttpError(400, 'Invalid message mode');
    const m = await machine(ctx, params.machine);
    await sendMessage(ctx, m, params.id, text(b.text), mode);
    // Messaged from the glasses: watch the thread for 24 h (lib/t3-notify.mjs notifies needs-you and finished).
    watchFromGlasses(ctx, m, params.id, machineState(ctx, m).threads?.find(t => t.id === params.id)?.title);
    return { ok: true };
  });
  r.post('/t3/threads/:machine/:id/respond', async ({ req, params }) => {
    const b = await readJson(req); text(b.requestId, 'requestId', 1000);
    if (b.decision !== undefined && !['accept', 'acceptForSession', 'decline'].includes(b.decision)) throw new HttpError(400, 'Invalid decision');
    if (b.answers !== undefined && (!b.answers || typeof b.answers !== 'object' || Array.isArray(b.answers) || Object.values(b.answers).some(v => typeof v !== 'string' && !(Array.isArray(v) && v.every(s => typeof s === 'string'))))) throw new HttpError(400, 'Invalid answers');
    if (!b.decision && !b.answers) throw new HttpError(400, 'Missing response');
    const m = await machine(ctx, params.machine);
    requireWritable(ctx, m);
    const projection = await retryOnce(ctx, m, 'read thread', () => bounded(ctx, m, params.id, { force: true })), raw = projection.thread ?? projection, pending = raw.pendingRuntimeRequest;
    const shown = detail(ctx, m, projection).request;
    if (!shown || shown.id !== b.requestId) throw new HttpError(409, 'Request is no longer pending');
    if ([pending?.responseMode, pending?.responseCapability].includes('message')) {
      const answer = b.answers ? Object.entries(b.answers).map(([id, value]) => `${id}: ${Array.isArray(value) ? value.join(', ') : value}`).join('\n') : b.decision;
      await sendMessage(ctx, m, params.id, answer, 'send', projection);
    } else await dispatch(ctx, m, { type: 'runtime-request.respond', threadId: params.id, requestId: b.requestId, ...(b.decision ? { decision: b.decision } : {}), ...(b.answers ? { answers: b.answers } : {}) });
    watchFromGlasses(ctx, m, params.id, raw.title);
    return { ok: true };
  });
  r.post('/t3/threads/:machine/:id/visit', async ({ params }) => {
    await dispatch(ctx, await machine(ctx, params.machine), { type: 'thread.visit', threadId: params.id, visitedAt: new Date(ctx.now()).toISOString() }); return { ok: true };
  });
  // Projects a new thread can start in: machines that are reachable and writable, pinned first, then by the
  // newest thread activity in the project, then title. Archived and deleted projects are left out.
  r.get('/t3/projects', async () => (await Promise.all((await machines(ctx)).map(async m => {
    const state = machineState(ctx, m);
    if (state.canWrite === false || state.needsPairing) return [];
    try {
      const [rows, shell] = await Promise.all([projects(ctx, m), shellRaw(ctx, m).catch(() => null)]);
      const active = new Map();
      for (const t of shell?.threads ?? []) if (t.projectId && ms(t.updatedAt) > (active.get(t.projectId) ?? 0)) active.set(t.projectId, ms(t.updatedAt));
      return rows.map(p => ({ machine: alias(m), machineLabel: label(m), id: p.id, title: p.title ?? p.name ?? 'Project', pinned: Boolean(p.pinnedAt), updatedAt: p.updatedAt ?? null, _active: Math.max(active.get(p.id) ?? 0, ms(p.updatedAt)) }));
    } catch (e) { ctx.log?.('t3 projects', alias(m), e.message); return []; }
  }))).flat().sort((a, b) => (b.pinned - a.pinned) || (b._active - a._active) || a.title.localeCompare(b.title)).map(({ _active, ...p }) => p));
  // New thread: T3 allocates the thread id. The one commandId is the idempotency key (T3 keeps a launch receipt per
  // commandId), so the retry after a timeout returns the same thread instead of starting a second agent run.
  // reuseExistingThread is NOT for this: it claims an existing empty thread and fails for a new id (2026-11-18).
  // (was: one gateway-chosen threadId with reuseExistingThread, so a retry after a timeout returns the
  // same thread instead of starting a second agent run.
  r.post('/t3/threads', async ({ req }) => {
    const b = await readJson(req), input = text(b.text), projectId = text(b.projectId, 'projectId', 1000);
    const m = await machine(ctx, b.machine);
    requireWritable(ctx, m);
    const [known, config, shell] = await Promise.all([
      retryOnce(ctx, m, 'projects', () => projects(ctx, m, { force: true })),
      retryOnce(ctx, m, 'server.getConfig', () => rpc(ctx, m, 'server.getConfig', {}, 8000, { force: true })),
      // Forced too: during a background backoff an unforced read fails at once and the newest-thread model is lost.
      shellRaw(ctx, m, { force: true }).catch(() => null),
    ]);
    const project = known.find(p => p.id === projectId); if (!project) throw new HttpError(404, `Project not found on ${label(m)}`);
    const payload = {
      commandId: randomUUID(), projectId, title: input.split(/\s+/).slice(0, 8).join(' ').slice(0, 100), generateTitle: true, creationSource: 'mobile',
      modelSelection: modelSelection(project, config, recentModelSelection(shell, projectId)), runtimeMode: project.defaultRuntimeMode ?? config.threadDefaults?.runtimeMode ?? config.settings?.defaultRuntimeMode ?? config.defaultRuntimeMode ?? 'approval-required', interactionMode: 'default', workspaceStrategy: { type: 'root' },
      initialMessage: { messageId: randomUUID(), text: input + glassesSuffix(ctx), attachments: [] },
    };
    const result = await retryOnce(ctx, m, 'launchThread', () => rpc(ctx, m, 'orchestration.launchThread', payload, 30000, { force: true }));
    const id = result?.threadId ?? result?.thread?.id ?? result?.id;
    if (!id) throw new HttpError(502, `${label(m)} launched no thread id`);
    ctx.log?.('t3 thread launched', alias(m), result?.resumed ? 'resumed' : 'new');
    watchFromGlasses(ctx, m, id, payload.title);
    invalidate(ctx, 't3:'); return { machine: alias(m), id };
  });
  r.get('/t3/usage', () => usage(ctx));
  r.post('/t3/usage/reset', async ({ req }) => {
    const b = await readJson(req), m = await machine(ctx, b.machine), instanceId = text(b.instanceId, 'instanceId', 1000), creditId = text(b.creditId, 'creditId', 1000);
    const config = await retryOnce(ctx, m, 'server.getConfig', () => rpc(ctx, m, 'server.getConfig', {}, 8000, { force: true }));
    const provider = config.providers?.find(p => p.instanceId === instanceId && p.enabled !== false);
    const creditList = provider?.usageLimits?.resetCredits; const credit = (Array.isArray(creditList) ? creditList : creditList?.options ?? []).find(c => (c.creditId ?? c.id) === creditId);
    if (!credit || credit.availableCount <= 0) throw new HttpError(409, 'Reset credit unavailable');
    const sourceId = credit.sourceId ?? provider.usageLimits.sourceId, accountId = credit.accountId ?? provider.usageLimits.accountId;
    await rpc(ctx, m, 'provider.consumeResetCredit', sourceId && accountId ? { sourceId, accountId, creditId } : { instanceId, creditId }, 8000, { force: true });
    invalidate(ctx, 't3:'); return { ok: true };
  });
  // Same rows as /t3/threads.machines (one source of truth).
  r.get('/t3/environments', () => environments(ctx));
}
