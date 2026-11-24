// People: enrolled voices for the phone to recognise, who was heard, and their notes for the glasses.
// The source is a people.json file (people.file / PEOPLE_FILE, see people.example.json); without one the People
// screen is simply empty. Recognition runs on the phone; no audio comes here for it. Heard events and mutes are kept
// in the gateway store, so the file can stay read-only (re-read on every change of its modification time).
//
// people.json: { "model"?: "wespeaker-voxceleb-resnet34-LM", "dim"?: 256,
//                "people": [{ "id", "name", "me"?: bool, "muted"?: bool, "notes"?: "", "where"?: "", "seenAt"?: ISO,
//                             "voices"?: [{ "id", "embedding": [dim floats] | "<base64 float32 LE>" }] }] }
import fs from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { HttpError } from '../../../lib/http.mjs';
import { readJson } from '../../../lib/web.mjs';
import { cached, invalidate } from '../lib/gateway.mjs';

const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;
const ID = /^[A-Za-z0-9_.:-]{1,80}$/;
const HEARD = 'people-heard', MUTES = 'people-mutes';
export const DEFAULT_MODEL = 'wespeaker-voxceleb-resnet34-LM';

// dim floats -> unit-length Float32Array, or null when it is not a usable voice vector.
export function unitVector(values, dim = 256) {
  if (!Array.isArray(values) || values.length !== dim) return null;
  let sum = 0;
  for (const x of values) { if (!Number.isFinite(x)) return null; sum += x * x; }
  const norm = Math.sqrt(sum);
  if (!(norm > 1e-9)) return null;
  return Float32Array.from(values, x => x / norm);
}
export const vectorBase64 = v => Buffer.from(v.buffer, v.byteOffset, v.byteLength).toString('base64');
function embedding(value, dim) {
  if (typeof value === 'string') {
    const buf = Buffer.from(value, 'base64');
    if (buf.length !== dim * 4) return null;
    return unitVector(Array.from({ length: dim }, (_, i) => buf.readFloatLE(i * 4)), dim);
  }
  return unitVector(value, dim);
}

/** Parses people.json (tolerant: bad rows are skipped). Also accepts a bare array of people. */
export function parsePeople(raw) {
  const doc = Array.isArray(raw) ? { people: raw } : raw && typeof raw === 'object' ? raw : { people: [] };
  const dim = Number.isInteger(doc.dim) && doc.dim > 0 && doc.dim <= 4096 ? doc.dim : 256;
  const people = (Array.isArray(doc.people) ? doc.people : []).filter(p => p && typeof p.id === 'string' && ID.test(p.id) && typeof p.name === 'string').map(p => ({
    id: p.id, name: p.name.slice(0, 100), me: p.me === true, muted: p.muted === true, notes: String(p.notes ?? p.blurb ?? '').slice(0, 1000),
    where: String(p.where ?? '').slice(0, 100), seenAt: typeof p.seenAt === 'string' && Number.isFinite(Date.parse(p.seenAt)) ? p.seenAt : null,
    voices: (Array.isArray(p.voices) ? p.voices : []).filter(v => v && typeof v.id === 'string' && ID.test(v.id)).map(v => ({ id: v.id, vector: embedding(v.embedding, dim) })).filter(v => v.vector),
  }));
  return { model: typeof doc.model === 'string' ? doc.model : DEFAULT_MODEL, dim, people };
}

export function register(r, ctx) {
  const file = ctx.config.people?.file || ctx.config.peopleFile || '';
  async function source() {
    if (!file) return { model: DEFAULT_MODEL, dim: 256, people: [], mtime: 0 };
    let stat;
    try { stat = await fs.stat(file); } catch { return { model: DEFAULT_MODEL, dim: 256, people: [], mtime: 0 }; }
    return cached(ctx, 'people-file.' + stat.mtimeMs, 5 * 60000, async () => {
      try { return { ...parsePeople(JSON.parse(await fs.readFile(file, 'utf8'))), mtime: stat.mtimeMs }; }
      catch (e) { ctx.log?.('glasses people', 'people file unreadable', e?.code || e?.name || 'error'); return { model: DEFAULT_MODEL, dim: 256, people: [], mtime: 0 }; }
    });
  }
  const mutedIds = () => new Map(ctx.store.all(MUTES).map(m => [m.id, m.muted]));
  async function voices() {
    const src = await source(), mutes = mutedIds();
    return cached(ctx, `people-voices.${src.mtime}.${ctx.store.all(MUTES).length}.${JSON.stringify([...mutes])}`, 5 * 60000, async () => {
      const people = src.people.map(p => ({ id: p.id, handle: p.id, name: p.name, me: p.me, muted: mutes.has(p.id) ? mutes.get(p.id) : p.muted,
        status: 'confirmed', blurb: p.notes, profiles: p.voices.map(v => ({ id: v.id, embedding: vectorBase64(v.vector) })) })).sort((a, b) => a.id < b.id ? -1 : 1);
      const etag = '"' + createHash('sha256').update(JSON.stringify(people)).digest('base64url').slice(0, 27) + '"';
      return { etag, body: { model: src.model, dim: src.dim, updatedAt: new Date(src.mtime || ctx.now()).toISOString(), people } };
    });
  }
  const where = e => e.lat == null || e.lon == null ? '' : `${e.lat.toFixed(4)},${e.lon.toFixed(4)}`;
  function lastHeard() {
    const latest = new Map();
    for (const e of ctx.store.all(HEARD).sort((a, b) => a.at < b.at ? 1 : -1)) if (!latest.has(e.personId)) latest.set(e.personId, e);
    return [...latest.values()];
  }
  async function cards() {
    const { body } = await voices(), src = await source();
    const known = new Map(body.people.map(p => [p.id, p])), heard = lastHeard();
    const fromEvents = heard.map(e => ({ e, p: known.get(e.personId) })).filter(x => x.p && !x.p.muted && !x.p.me)
      .map(({ e, p }) => ({ id: p.id, name: p.name, where: where(e), seenAt: e.at, notes: p.blurb }));
    const seen = new Set(fromEvents.map(c => c.id));
    // People the file itself says were seen (a hand-kept list, no voices needed) follow the heard ones.
    const fromFile = src.people.filter(p => !seen.has(p.id) && !p.me && !(known.get(p.id)?.muted) && p.seenAt)
      .map(p => ({ id: p.id, name: p.name, where: p.where, seenAt: p.seenAt, notes: p.notes }));
    return [...fromEvents, ...fromFile].sort((a, b) => (Date.parse(b.seenAt) || 0) - (Date.parse(a.seenAt) || 0));
  }

  r.get('/people/voices', async ({ req, res }) => {
    const { etag, body } = await voices();
    if ((req.headers['if-none-match'] || '').replace(/^W\//, '') === etag) { res.writeHead(304, { ETag: etag, 'Cache-Control': 'no-store' }); res.end(); return; }
    res.setHeader('ETag', etag);
    return body;
  });
  r.post('/people/heard', async ({ req }) => {
    const b = await readJson(req);
    if (!Array.isArray(b.events) || b.events.length > 200) throw new HttpError(400, 'Invalid events');
    const { body } = await voices(), known = new Map(body.people.map(p => [p.id, p]));
    let stored = 0;
    for (const e of b.events) {
      if (typeof e?.id !== 'string' || !/^[A-Za-z0-9_-]{8,80}$/.test(e.id) || typeof e.personId !== 'string' || typeof e.profileId !== 'string' ||
          typeof e.at !== 'string' || !ISO.test(e.at) || !Number.isFinite(Date.parse(e.at)) || Date.parse(e.at) > ctx.now() + 5 * 60000 ||
          !Number.isFinite(e.confidence) || Math.abs(e.confidence) > 1) throw new HttpError(400, 'Invalid event');
      const person = known.get(e.personId);
      // Unknown, muted and the owner's own voice are dropped rather than refused: the phone's list may be minutes old.
      if (!person || person.muted || person.me || !person.profiles.some(p => p.id === e.profileId) || ctx.store.get(HEARD, e.id)) continue;
      const fix = Number.isFinite(e.lat) && Number.isFinite(e.lon) && Math.abs(e.lat) <= 90 && Math.abs(e.lon) <= 180;
      ctx.store.put(HEARD, { id: e.id, personId: e.personId, profileId: e.profileId, at: new Date(e.at).toISOString(), confidence: e.confidence,
        lat: fix ? e.lat : null, lon: fix ? e.lon : null, accuracyM: fix && Number.isFinite(e.accuracyM) && e.accuracyM >= 0 && e.accuracyM < 1e6 ? e.accuracyM : null });
      stored++;
    }
    await ctx.store.flushed();
    return { ok: true, stored };
  });
  r.get('/people/recent', async () => {
    const all = await cards(), cutoff = ctx.now() - 10 * 60000;
    return { heard: all.filter(c => Date.parse(c.seenAt) >= cutoff && ctx.store.all(HEARD).some(e => e.personId === c.id)), recent: all };
  });
  r.get('/people/:id', async ({ params }) => {
    const { body } = await voices(), p = body.people.find(x => x.id === params.id);
    if (!p) throw new HttpError(404, 'Person not found');
    const e = lastHeard().find(x => x.personId === p.id), src = (await source()).people.find(x => x.id === p.id);
    return { id: p.id, name: p.name, where: e ? where(e) : src?.where ?? '', seenAt: e?.at ?? src?.seenAt ?? null, notes: p.blurb, muted: p.muted };
  });
  r.post('/people/:id/mute', async ({ req, params }) => {
    const b = await readJson(req);
    if (typeof b.muted !== 'boolean') throw new HttpError(400, 'Invalid muted');
    const { body } = await voices();
    if (!body.people.some(p => p.id === params.id)) throw new HttpError(404, 'Person not found');
    ctx.store.put(MUTES, { id: params.id, muted: b.muted, at: new Date(ctx.now()).toISOString() });
    await ctx.store.flushed();
    invalidate(ctx, 'people-voices');
    return { ok: true, muted: b.muted };
  });
}
