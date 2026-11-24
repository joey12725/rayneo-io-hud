// Gateway-owned T3 notifications (worker process only, GATEWAY_WORKER=1). Every machine's shell snapshot is polled
// (T3 offers no push stream the gateway can hold open per machine with the gateway's tokens; the shell is one cached GET),
// and each root thread's status transitions are turned into notifications:
//   needs-you: approval request, question/choice or user input pending, guest request (status input|approval|request)
//   finished:  a run that was working/waiting/needing you is now done, settled, failed or usage limited
// Policy (lib/notify-policy.mjs, through routeNotification): a watched thread (explicit, or automatic for 24 h
// after a glasses new thread/send/answer) notifies both kinds; with "Notify for all threads needing me" on
// (default) needs-you on ANY thread pops up too; finished on an unwatched thread never notifies.
// State is one store document per thread (collection t3-notify), so a restart neither repeats nor loses a
// transition; the first scan of a machine only records a baseline (no flood of old threads).
import { machines, shellRaw, status, alias, bounded, detail } from './t3.mjs';
import { routeNotification, isWatched, pruneWatches, watchKey } from './notify-route.mjs';
import { routeInput } from './notify-policy.mjs';
import { previousTurns } from './notify-context.mjs';

export const T3_NOTIFY = 't3-notify';
// Settings and per-machine baselines live apart from the per-thread documents: the thread collection is capped
// (lib/store.mjs evicts the least recently written), and losing the setting or a baseline to that eviction would
// silently reset "notify all needing me" or re-baseline a machine.
export const T3_NOTIFY_META = 't3-notify-meta';
const NEEDS = ['input', 'approval', 'request'];
const ACTIVE = ['working', 'waiting', ...NEEDS];
const FINISHED = ['done', 'settled', 'failed', 'limited'];
const ms = (value) => typeof value === 'number' ? value : Date.parse(value ?? '') || 0;
const clip = (value, max = 160) => String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, max);

export const stageOf = (s) => NEEDS.includes(s) ? 'needs' : FINISHED.includes(s) ? 'finished' : ACTIVE.includes(s) ? 'active' : 'idle';

/** {needsAll}: the glasses setting if it was ever changed, else T3_NOTIFY_NEEDS_ALL (default on). */
export function notifySettings(ctx) {
  let doc = null; try { doc = ctx.store.get(T3_NOTIFY_META, 'settings'); } catch {}
  return { needsAll: typeof doc?.needsAll === 'boolean' ? doc.needsAll : ctx.config.t3NotifyNeedsAll !== false };
}
export async function saveNotifySettings(ctx, { needsAll }) {
  ctx.store.put(T3_NOTIFY_META, { id: 'settings', needsAll: needsAll === true, updatedAt: new Date(ctx.now()).toISOString() });
  await ctx.store.flushed();
  return notifySettings(ctx);
}

// What identifies "this" needs-you request and "this" finished run, so neither repeats.
const needsSig = (raw, s) => `${s}:${raw.pendingRuntimeRequest?.id ?? raw.pendingRuntimeRequest?.requestId ?? (s === 'request' ? 'guest' + (raw.pendingGuestMessageCount ?? '') + ':' + (raw.updatedAt ?? '') : raw.updatedAt ?? '')}`;
const doneSig = (raw, s) => `${s}:${raw.latestRunId ?? raw.activeRunId ?? ''}:${raw.latestRunCompletedAt ?? raw.settledAt ?? raw.updatedAt ?? ''}`;

async function threadView(ctx, m, raw, projects = []) {
  try { const projection = await bounded(ctx, m, raw.id); return { view: detail(ctx, m, projection, projects), projection }; } catch { return { view: null, projection: null }; }
}

const ROLE = (x) => x.role ?? x.who ?? x.createdBy ?? x.type;
/**
 * What the classifier needs to judge a transition (requirement: prompt + response, with timestamps, thread title and project):
 * the prompt is the user-role message that started the latest run (marked agentWritten when another agent wrote it),
 * the response is the agent's final message, and the turns are the thread's earlier exchanges within 4 h.
 */
export function runContext(view, projection, raw, { stage, status, title, project, request, owner = 'the user' } = {}) {
  const history = (view?.history ?? []).filter(h => String(h.text ?? '').trim());
  if (!history.length) return null;
  // createdBy is only on the raw projection messages: match them up by position.
  const msgs = (projection?.messages ?? []).filter(x => ['user', 'assistant', 'agent', 'user_message', 'assistant_message'].includes(ROLE(x)));
  const agentWritten = (h) => { const x = msgs.find(y => (y.createdAt ?? y.at ?? y.updatedAt) === h.at && String(y.text ?? y.content ?? '').slice(0, 40) === String(h.text).slice(0, 40)); return x?.createdBy === 'agent' || x?.creationSource === 'agent'; };
  let ri = -1;
  for (let i = history.length - 1; i >= 0; i--) if (history[i].who === 'agent') { ri = i; break; }
  let pi = -1;
  for (let i = (ri >= 0 ? ri : history.length) - 1; i >= 0; i--) if (history[i].who === 'user') { pi = i; break; }
  const response = ri >= 0 ? history[ri] : null;
  const promptMsg = pi >= 0 ? history[pi] : null;
  // Earlier turns: each user message and the final agent message before the next user message.
  const before = history.slice(0, pi >= 0 ? pi : 0).filter((h, i, all) => h.who === 'user' || !all[i + 1] || all[i + 1].who === 'user')
    .map(h => { const agent = h.who === 'user' && agentWritten(h); return { at: h.at, text: h.text, speaker: h.who === 'user' ? (agent ? 'agent' : owner) : 'assistant', isOwner: h.who === 'user' && !agent }; });
  const ref = promptMsg?.at ?? response?.at;
  return {
    threadTitle: title, project, status: stage === 'needs' ? `needs you (${status})${request ? ': ' + request : ''}` : status,
    ...(promptMsg ? { prompt: { at: promptMsg.at, text: String(promptMsg.text).slice(-3000), ...(agentWritten(promptMsg) ? { agentWritten: true } : { isOwner: true, sender: owner }) } } : {}),
    response: { at: response?.at ?? raw.latestRunCompletedAt ?? raw.updatedAt, text: String(response?.text ?? '').slice(-4000) },
    turns: ref ? previousTurns(before, before.length, { targetAt: ref }) : [],
  };
}

async function describe(ctx, m, raw, s, kind, view) {
  if (kind === 'needs') {
    const req = view?.request;
    const what = req?.approval ? 'approve ' + (clip(req.approval.command, 120) || clip(req.approval.reason, 120) || req.approval.kind || 'request')
      : req?.kind === 'guest' ? 'guest message waiting'
      : req?.questions?.length ? clip(req.questions[0].question || req.questions[0].header, 140)
      : { input: 'input needed', approval: 'approval needed', request: 'request waiting' }[s];
    return 'Needs you · ' + what;
  }
  if (s === 'failed') return 'Failed' + (raw.lastErrorClass || raw.lastErrorCode ? ' · ' + clip(raw.lastErrorClass || raw.lastErrorCode, 80) : '');
  if (s === 'limited') return 'Stopped · usage limit reached';
  const latest = clip(view?.latest?.text, 160);
  return 'Finished' + (latest ? ' · ' + latest : '');
}

export function createT3Notifier(ctx, { everyMs = ctx.config.t3NotifyEveryMs ?? 20000, log = (...a) => ctx.log?.(...a) } = {}) {
  let timer = null, running = null;
  const get = (c, id) => { try { return ctx.store.get(c, id); } catch { return null; } };

  async function emit(m, raw, s, kind, settings, projects = []) {
    const key = watchKey(alias(m), raw.id), watched = isWatched(ctx, key);
    if (kind === 'finished' && !watched) return null;
    if (kind === 'needs' && !watched && !settings.needsAll) return null;
    const id = `t3:${alias(m)}:${raw.id}:${s}:${raw.updatedAt ?? ''}`; // same id the phone's own poll used
    if (ctx.store.get('notifications', id)) return null;
    const title = clip(raw.title, 200) || 'Untitled';
    const { view, projection } = await threadView(ctx, m, raw, projects);
    const body = await describe(ctx, m, raw, s, kind, view);
    const input = routeInput({ kind: 't3', source: key, chatId: key, app: 'T3 Code', title, body, category: kind === 'needs' ? 'needs-you' : 'done' });
    let context = null;
    try { context = runContext(view, projection, raw, { stage: kind, status: s, title, project: view?.thread?.project ?? '', request: kind === 'needs' ? body.replace(/^Needs you · /, '') : '', owner: ctx.config.owner?.name || 'the user' }); } catch {}
    // With the run's prompt and response the classifier judges the content (the stage rule stays the fallback).
    if (context && kind === 'needs' && !context.response.text) context.response.text = body;
    const d = await routeNotification(ctx, input, { needsAny: settings.needsAll, ...(context?.response?.text ? { context } : {}) });
    if (d.route === 'suppress') return null;
    // `open` carries both the routing contract ({app, target}) and the phone's OpenTarget shape ({screen, args}).
    const row = ctx.store.put('notifications', { id, source: 'T3', title, body, importance: d.importance, icon: 't3', route: d.route, reason: d.reason,
      open: { screen: 't3', args: { target: key }, app: 't3', target: key }, machine: alias(m), threadId: raw.id, stage: kind, createdAt: new Date(ctx.now()).toISOString() });
    ctx.events.publish('notification', row);
    return row;
  }

  async function scan(m, shell, settings) {
    const a = alias(m), mid = 'm:' + a, mdoc = get(T3_NOTIFY_META, mid), first = !mdoc;
    const since = mdoc?.baselinedAt ? ms(mdoc.baselinedAt) : ctx.now();
    const sent = [];
    for (const raw of shell?.threads ?? []) {
      if (!raw?.id || raw.lineage?.parentThreadId || raw.archivedAt || raw.deletedAt) continue;
      const key = watchKey(a, raw.id), tid = 't:' + key, prev = get(T3_NOTIFY, tid), s = status(raw);
      // A run that ended while its thread was open on the desktop goes straight to "ready" (visited): still a
      // finished run for a watched thread, but only as an observed transition from an active status.
      const stage = s === 'ready' && ACTIVE.includes(prev?.status) ? 'finished' : stageOf(s);
      const nSig = stage === 'needs' ? needsSig(raw, s) : prev?.needsSig ?? null;
      const dSig = stage === 'finished' ? doneSig(raw, s) : prev?.doneSig ?? null;
      // A thread first seen after the baseline is new: it can notify (needs-you at once, or a run that started and
      // finished between two polls). One that predates the baseline only notifies on an observed transition.
      const fresh = !prev && ms(raw.createdAt ?? raw.updatedAt) >= since;
      if (!first) {
        let kind = null;
        if (stage === 'needs' && nSig !== prev?.needsSig && (prev || ms(raw.updatedAt) >= since)) kind = 'needs';
        else if (stage === 'finished' && dSig !== prev?.doneSig && (prev ? ACTIVE.includes(prev.status) : fresh)) kind = 'finished';
        if (kind) {
          try { const row = await emit(m, raw, s, kind, settings, shell?.projects ?? []); if (row) sent.push(row.id); }
          catch (e) { log('t3 notify', a, 'emit failed', e?.message ?? 'error'); }
        }
      }
      if (!prev || prev.status !== s || prev.needsSig !== nSig || prev.doneSig !== dSig)
        ctx.store.put(T3_NOTIFY, { id: tid, machine: a, threadId: raw.id, status: s, needsSig: nSig, doneSig: dSig, seenAt: new Date(ctx.now()).toISOString() });
    }
    if (first) { ctx.store.put(T3_NOTIFY_META, { id: mid, machine: a, baselinedAt: new Date(ctx.now()).toISOString() }); log('t3 notify', a, 'baseline', (shell?.threads ?? []).length + ' threads'); }
    return sent;
  }

  async function tick() {
    if (running) return running;
    running = (async () => {
      const sent = [];
      try {
        pruneWatches(ctx);
        const settings = notifySettings(ctx);
        for (const m of await machines(ctx)) {
          let shell;
          try { shell = await shellRaw(ctx, m); } catch { continue; } // offline/backoff: t3.mjs already logs transitions
          sent.push(...await scan(m, shell, settings));
        }
        await ctx.store.flushed();
      } catch (e) { log('t3 notify', 'tick failed', e?.message ?? 'error'); }
      finally { running = null; }
      return sent;
    })();
    return running;
  }
  return {
    tick,
    start() { if (!timer) { timer = setInterval(() => { tick(); }, everyMs); timer.unref?.(); tick(); } },
    stop() { clearInterval(timer); timer = null; },
  };
}
