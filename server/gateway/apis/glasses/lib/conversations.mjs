// Recordings from the phone (voice memos and ambient conversations): phone -> here -> transcription -> delivery targets.
//
// The phone sends a timing manifest and a speech-only audio file per recording (POST /ambient/conversations, then PUT
// .../audio, then it polls the state). A worker transcribes the audio with the configured OpenAI-compatible model
// (models.transcribe), puts every transcript segment at the real time it was said (the manifest says where each piece
// of the speech-only audio sits on the clock) and at the nearest location fix, then hands the result to every delivery
// target routed for that kind (lib/targets.mjs: routes.memo / routes.ambient).
//
// States (unchanged for the phone): received -> audio -> uploaded (transcribed, deliveries in progress) -> timed (every
// delivery settled and at least one delivered, or nothing to deliver to) | failed. Each delivery is reported as
// { target, name, state: 'pending'|'delivered'|'failed', error? } in `deliveries`.
//
// Queue rows live in the gateway store (collection `recordings`), so no database is needed.
import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { HttpError } from '../../../lib/http.mjs';
import { MIME } from './targets.mjs';

const MINUTE = 60000, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
export const MEMO_MIN_SEC = 1;
export const COLLECTION = 'recordings';
export const isMemo = c => c?.manifest?.kind === 'memo';
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;
const time = (v, name) => {
  if (typeof v !== 'string' || !ISO.test(v) || !Number.isFinite(Date.parse(v))) throw new HttpError(400, `Invalid ${name}`);
  return new Date(v).toISOString();
};
const number = (v, name, min, max) => {
  if (typeof v !== 'number' || !Number.isFinite(v) || v < min || v > max) throw new HttpError(400, `Invalid ${name}`);
  return v;
};

// Strict check of what the phone sent; returns the row to store.
export function parseManifest(b, { minSpeechSec = 30 } = {}) {
  if (!b || typeof b !== 'object' || Array.isArray(b)) throw new HttpError(400, 'Invalid conversation');
  if (typeof b.clientId !== 'string' || !/^[A-Za-z0-9_-]{8,80}$/.test(b.clientId)) throw new HttpError(400, 'Invalid clientId');
  const device = b.device ?? 'glasses';
  if (!['glasses', 'phone'].includes(device)) throw new HttpError(400, 'Invalid device');
  const startedAt = time(b.startedAt, 'startedAt'), endedAt = time(b.endedAt, 'endedAt');
  if (Date.parse(endedAt) < Date.parse(startedAt) || Date.parse(endedAt) - Date.parse(startedAt) > 2 * DAY) throw new HttpError(400, 'Invalid endedAt');
  const speechSec = number(b.speechSec, 'speechSec', 0, 2 * 86400);
  if (b.tz != null) { try { if (typeof b.tz !== 'string' || b.tz.length > 64) throw 0; new Intl.DateTimeFormat('en-US', { timeZone: b.tz }); } catch { throw new HttpError(400, 'Invalid tz'); } }
  if (!Array.isArray(b.segments) || !b.segments.length || b.segments.length > 5000) throw new HttpError(400, 'Invalid segments');
  let last = -1;
  const segments = b.segments.map(s => {
    const offsetSec = number(s?.offsetSec, 'segment offset', 0, 2 * 86400);
    if (offsetSec <= last) throw new HttpError(400, 'Segments must be in order');
    last = offsetSec;
    return { offsetSec, at: time(s.at, 'segment time'), durationSec: number(s.durationSec, 'segment duration', 0, 2 * 86400) };
  });
  if (segments[0].offsetSec !== 0) throw new HttpError(400, 'First segment must start at 0');
  if (b.fixes != null && (!Array.isArray(b.fixes) || b.fixes.length > 2000)) throw new HttpError(400, 'Invalid fixes');
  const fixes = (b.fixes ?? []).map(f => ({ at: time(f?.at, 'fix time'), lat: number(f.lat, 'lat', -90, 90), lon: number(f.lon, 'lon', -180, 180),
    ...(f.accuracyM == null ? {} : { accuracyM: number(f.accuracyM, 'accuracy', 0, 100000) }) }));
  // A voice memo (kind "memo") takes the same route; it may be as short as a second and carries its title.
  const kind = b.kind ?? 'conversation';
  if (!['conversation', 'memo'].includes(kind)) throw new HttpError(400, 'Invalid kind');
  if (b.title != null && (kind !== 'memo' || typeof b.title !== 'string' || b.title.length > 120)) throw new HttpError(400, 'Invalid title');
  if (speechSec < (kind === 'memo' ? MEMO_MIN_SEC : minSpeechSec)) throw new HttpError(422, 'Too little speech to submit');
  const title = kind === 'memo' && b.title?.trim() ? b.title.trim() : null;
  return { clientId: b.clientId, device, startedAt, endedAt, speechSec, tz: b.tz ?? null,
    manifest: { segments, fixes, ...(kind === 'memo' ? { kind, ...(title ? { title } : {}) } : {}) } };
}

/**
 * Real clock time of an offset into the speech-only audio. A transcript segment is placed in the audio piece that holds
 * its midpoint (segment starts often run a moment early), so the first words after a gap get the time after the gap.
 */
export function realTime(start, end, pieces) {
  if (typeof start !== 'number' || !pieces?.length) return null;
  const mid = typeof end === 'number' && end > start ? (start + end) / 2 : start;
  let piece = pieces[0];
  for (const s of pieces) { if (s.offsetSec <= mid) piece = s; else break; }
  return new Date(Date.parse(piece.at) + Math.round(Math.max(0, start - piece.offsetSec) * 1000)).toISOString();
}
/** Nearest location fix within maxGapS of `at`, or null. */
export function nearestFix(at, fixes, maxGapS = 900) {
  const t = Date.parse(at);
  let best = null, gap = Infinity;
  for (const f of fixes ?? []) { const g = Math.abs(Date.parse(f.at) - t) / 1000; if (g < gap) { gap = g; best = f; } }
  return best && gap <= maxGapS ? best : null;
}
/** Transcription result ({text, segments?: [{start, end, text}]}) + manifest -> the transcript handed to targets. */
export function buildTranscript(result, c, speaker) {
  const segments = Array.isArray(result?.segments) && result.segments.length ? result.segments
    : [{ start: 0, end: null, text: String(result?.text ?? '') }];
  const turns = segments.map(s => ({ speaker, start: typeof s.start === 'number' ? s.start : null, end: typeof s.end === 'number' ? s.end : null, text: String(s.text ?? '').trim() }))
    .filter(t => t.text).map(t => {
      const at = realTime(t.start, t.end, c.manifest.segments), fix = at ? nearestFix(at, c.manifest.fixes) : null;
      return { ...t, at, ...(fix ? { lat: fix.lat, lon: fix.lon } : {}) };
    });
  return { text: String(result?.text ?? turns.map(t => t.text).join(' ')).trim(), turns };
}

/** The phone's view of a recording. */
export const view = c => ({ id: c.id, kind: c.manifest?.kind ?? 'conversation', state: c.state, startedAt: c.startedAt, endedAt: c.endedAt, speechSec: c.speechSec,
  uploadedAt: c.uploadedAt ?? null, loadedAt: c.loadedAt ?? null, error: c.state === 'failed' ? c.lastError : null,
  deliveries: (c.deliveries ?? []).map(d => ({ target: d.target, name: d.name, state: d.state, ...(d.deliveredAt ? { deliveredAt: d.deliveredAt } : {}), ...(d.state === 'failed' && d.error ? { error: d.error } : {}) })) });

/** Queue rows in the gateway's JSON store. Same async interface a database-backed repo would offer. */
export function createStoreRepo(store) {
  const all = () => store.all(COLLECTION);
  const save = async row => { store.put(COLLECTION, row); await store.flushed(); return { ...row }; };
  return {
    async addConversation(c) {
      const found = all().find(r => r.clientId === c.clientId);
      if (found) return { ...found };
      return save({ id: randomUUID(), ...c, state: 'received', attempts: 0, receivedAt: new Date().toISOString(), uploadedAt: null, loadedAt: null,
        lastError: null, nextAttemptAt: null, transcript: null, deliveries: [] });
    },
    async conversation(id) { const r = store.get(COLLECTION, id); return r ? { ...r } : null; },
    async setAudio(id, { ext, bytes, sha256, at }) {
      const r = store.get(COLLECTION, id);
      if (!r || r.state !== 'received') return null;
      return save({ ...r, state: 'audio', audioExt: ext, audioBytes: bytes, audioSha256: sha256, audioAt: at });
    },
    async update(id, patch) { const r = store.get(COLLECTION, id); if (!r) return null; return save({ ...r, ...patch }); },
    async nextDue(now) {
      return all().filter(r => ['audio', 'uploaded'].includes(r.state) && (!r.nextAttemptAt || r.nextAttemptAt <= now))
        .sort((a, b) => Number(isMemo(b)) - Number(isMemo(a)) || (a.startedAt < b.startedAt ? -1 : 1)).map(r => ({ ...r })); // memos first
    },
    async uploadStats(now) {
      const rows = all(), done = rows.filter(r => r.loadedAt);
      const lastError = rows.filter(r => r.lastError).sort((a, b) => String(b.audioAt ?? b.receivedAt).localeCompare(String(a.audioAt ?? a.receivedAt)))[0]?.lastError ?? null;
      return { lastUploadAt: done.map(r => r.loadedAt).sort().at(-1) ?? null, uploadedToday: done.filter(r => Date.parse(r.loadedAt) > Date.parse(now) - DAY).length,
        pending: rows.filter(r => ['received', 'audio', 'uploaded'].includes(r.state)).length, lastError };
    },
    async prune(before) { for (const r of all()) if (['timed', 'failed'].includes(r.state) && (r.loadedAt ?? r.receivedAt) < before) store.del(COLLECTION, r.id); },
  };
}

export function createConversations(ctx, deps = {}) {
  const cfg = { maxAttempts: 8, maxPending: 500, keepDays: 30,
    maxAudioBytes: 95 * 1024 * 1024, // under common reverse-proxy request limits; an hour of speech is about 15 MB
    transcribe: true, dataDir: ctx.config.dataDir ?? '.data', ...(ctx.config.conversations ?? {}) };
  const repo = deps.repo ?? createStoreRepo(ctx.store);
  const transcribe = deps.transcribe ?? (async (file, mime) => (await import('./proxy.mjs')).transcribeDetailed(ctx, await fs.readFile(file), mime));
  const dir = path.join(cfg.dataDir, 'ambient');
  const file = c => path.join(dir, `${c.id}.${c.audioExt}`);
  const at = () => new Date(ctx.now()).toISOString();
  const note = e => e instanceof HttpError || e?.name === 'DeliveryError' || typeof e?.permanent === 'boolean' ? String(e.message).slice(0, 200) : 'Unexpected error';
  const routeOf = c => isMemo(c) ? 'memo' : 'ambient';
  let busy = false, lastHourly = 0, timer = null;

  function item(c) {
    const local = new Date(c.startedAt).toLocaleString('en-US', { timeZone: c.tz || ctx.config.tz || 'UTC', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
    return { id: c.id, kind: routeOf(c), title: isMemo(c) ? c.manifest.title || `Memo · ${local}` : `Conversation · ${local}`,
      startedAt: c.startedAt, endedAt: c.endedAt, tz: c.tz, speechSec: c.speechSec, device: c.device, transcript: c.transcript ?? null, fixes: c.manifest.fixes ?? [],
      audio: c.audioExt ? { file: file(c), ext: c.audioExt, mime: MIME[c.audioExt], bytes: c.audioBytes, sha256: c.audioSha256 } : null };
  }
  const backoff = attempts => new Date(ctx.now() + Math.min(2 ** attempts * MINUTE, 6 * HOUR)).toISOString();

  // Transcribe (once) and set up the deliveries for this recording's route.
  async function prepare(c) {
    const targets = ctx.targets?.forRoute(routeOf(c)) ?? [];
    let transcript = c.transcript;
    if (!transcript && cfg.transcribe) {
      const speaker = isMemo(c) ? ctx.config.owner?.name ?? 'the user' : 'Speaker';
      transcript = buildTranscript(await transcribe(file(c), MIME[c.audioExt]), c, speaker);
    }
    return repo.update(c.id, { state: 'uploaded', uploadedAt: at(), transcript: transcript ?? null, attempts: 0, lastError: null, nextAttemptAt: null,
      deliveries: targets.map(t => ({ target: t.id, name: t.name, state: 'pending', attempts: 0 })) });
  }
  async function deliver(c) {
    const deliveries = [];
    for (const d of c.deliveries) {
      if (d.state !== 'pending') { deliveries.push(d); continue; }
      try { await ctx.targets.deliver(d.target, item(c)); deliveries.push({ ...d, state: 'delivered', deliveredAt: at(), error: null }); ctx.log('glasses recordings', 'delivered', c.id, d.target); }
      catch (e) {
        const attempts = (d.attempts ?? 0) + 1, failed = e?.permanent === true || attempts >= cfg.maxAttempts;
        deliveries.push({ ...d, attempts, state: failed ? 'failed' : 'pending', error: note(e) });
        ctx.log('glasses recordings', failed ? 'delivery failed' : 'delivery retry', c.id, d.target); // ids only: no titles or text in logs
      }
    }
    const pending = deliveries.filter(d => d.state === 'pending');
    if (pending.length) {
      const attempts = Math.max(...pending.map(d => d.attempts ?? 0));
      return repo.update(c.id, { deliveries, attempts, lastError: pending[0].error ?? null, nextAttemptAt: backoff(attempts) });
    }
    const delivered = deliveries.some(d => d.state === 'delivered') || !deliveries.length;
    await fs.rm(file(c), { force: true }).catch(() => {});
    return repo.update(c.id, { deliveries, state: delivered ? 'timed' : 'failed', loadedAt: at(), nextAttemptAt: null,
      lastError: delivered ? null : deliveries.map(d => `${d.name}: ${d.error}`).join('; ').slice(0, 300) });
  }
  // Back off and try again. An outage (network, model endpoint down) never fails a recording for good; only repeated
  // refusals do, and then its audio is removed (the phone keeps its own copy).
  async function later(c, e) {
    const attempts = (c.attempts ?? 0) + 1, outage = e instanceof HttpError && (e.detail?.network || e.status === 503);
    const failed = !outage && attempts >= cfg.maxAttempts;
    await repo.update(c.id, { attempts, lastError: note(e), nextAttemptAt: failed ? null : backoff(attempts), ...(failed ? { state: 'failed', loadedAt: at() } : {}) });
    if (failed) { ctx.log('glasses recordings', 'failed', c.id); await fs.rm(file(c), { force: true }).catch(() => {}); }
  }
  // Files left by an interrupted upload (the process died mid-request) are removed after an hour.
  async function sweep() {
    for (const name of await fs.readdir(dir).catch(() => [])) {
      if (!/\.(tmp|incoming\.\w+)$|\.[0-9a-f]{64}\.\w+$/.test(name)) continue;
      const stat = await fs.stat(path.join(dir, name)).catch(() => null);
      if (stat && ctx.now() - stat.mtimeMs > HOUR) await fs.rm(path.join(dir, name), { force: true }).catch(() => {});
    }
    await repo.prune?.(new Date(ctx.now() - cfg.keepDays * DAY).toISOString());
  }
  async function process(c) {
    if (c.state === 'audio') {
      try { c = await prepare(c); } catch (e) { await later(c, e); return; }
    }
    await deliver(c);
  }
  async function tick() {
    if (busy) return;
    busy = true;
    try {
      for (const c of await repo.nextDue(at())) {
        try { await process(c); } catch (e) { ctx.log('glasses recordings', note(e)); }
      }
      if (ctx.now() - lastHourly >= HOUR) { lastHourly = ctx.now(); await sweep(); }
    } catch (e) { ctx.log('glasses recordings', note(e)); }
    finally { busy = false; }
  }

  return {
    cfg, repo, dir, file, tick, item,
    start(everyMs = 15000) { if (!timer) { timer = setInterval(tick, everyMs); timer.unref(); } },
    async stop() { clearInterval(timer); timer = null; },
    async status() {
      const stats = await repo.uploadStats(at());
      return { ...stats, targets: ctx.targets?.list() ?? [] };
    },
  };
}
