// Recordings from the phone (memos, ambient conversations): a timing manifest, then the speech-only audio, then the
// phone polls the state and its deliveries. See lib/conversations.mjs and lib/targets.mjs.
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { HttpError } from '../../../lib/http.mjs';
import { readJson } from '../../../lib/web.mjs';
import { parseManifest, view } from '../lib/conversations.mjs';

const EXT = { 'audio/ogg': 'ogg', 'audio/mp4': 'm4a', 'audio/wav': 'wav' };

// Streams the body to `target` without holding it in memory; returns its size and hash.
async function receive(req, target, limit, timeoutMs = 15 * 60000) {
  const tmp = `${target}.${randomUUID()}.tmp`, hash = createHash('sha256');
  let bytes = 0, timedOut = false;
  const timer = setTimeout(() => { timedOut = true; req.destroy(); }, timeoutMs);
  const out = fs.createWriteStream(tmp, { flags: 'wx', mode: 0o600 });
  let failed = null;
  out.on('error', e => { failed = e; }); // a disk error with no listener would take the process down
  try {
    for await (const chunk of req) {
      bytes += chunk.length;
      if (bytes > limit) throw new HttpError(413, 'Request body too large');
      if (failed) throw failed;
      hash.update(chunk);
      if (!out.write(chunk)) await new Promise((resolve, reject) => { out.once('drain', resolve); out.once('error', reject); });
    }
    await new Promise((resolve, reject) => out.end(e => e ? reject(e) : resolve()));
    if (!bytes) throw new HttpError(400, 'Empty audio');
    await fsp.rename(tmp, target);
    return { bytes, sha256: hash.digest('hex') };
  } catch (e) {
    // Wait for the handle to close before removing the file, or the delete can fail and leave it behind.
    if (!out.closed) await new Promise(resolve => { out.once('close', resolve); out.destroy(); });
    await fsp.rm(tmp, { force: true }).catch(() => {});
    if (e instanceof HttpError) throw e;
    if (timedOut) throw new HttpError(408, 'Upload timed out');
    throw e?.syscall ? new HttpError(507, 'Storage unavailable') : new HttpError(400, 'Upload aborted');
  } finally { clearTimeout(timer); }
}

export function register(r, ctx) {
  const conv = ctx.conversations, repo = conv.repo;
  const need = async id => { const c = await repo.conversation(id); if (!c) throw new HttpError(404, 'Conversation not found'); return c; };

  r.post('/ambient/conversations', async ({ req }) => {
    const manifest = parseManifest(await readJson(req));
    // A phone that cannot be drained (targets down for days) must not fill the store or the disk.
    // Memos are few and small and go first, so a backlog of ambient conversations does not block them.
    if (manifest.manifest.kind !== 'memo' && (await repo.uploadStats(new Date(ctx.now()).toISOString())).pending >= conv.cfg.maxPending) throw new HttpError(429, 'Too many conversations waiting');
    return view(await repo.addConversation(manifest));
  });
  r.get('/ambient/conversations/:id', async ({ params }) => view(await need(params.id)));
  r.put('/ambient/conversations/:id/audio', async ({ req, params }) => {
    const ext = EXT[(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase()];
    if (!ext) throw new HttpError(415, 'Unsupported audio type');
    const c = await need(params.id);
    await fsp.mkdir(conv.dir, { recursive: true });
    // Receive first, then compare: a repeat of the same bytes is fine, different bytes for a
    // conversation that already has audio is refused.
    const staged = conv.file({ id: `${c.id}.${randomUUID()}.incoming`, audioExt: ext });
    const got = await receive(req, staged, conv.cfg.maxAudioBytes);
    try {
      // The file goes in place under its own hash first, then the row is claimed: of two uploads racing
      // for one conversation only the winner's bytes are kept, so the stored size and hash always match the file.
      const mine = conv.file({ id: `${c.id}.${got.sha256}`, audioExt: ext });
      if (c.state === 'received') {
        await fsp.rename(staged, mine);
        const saved = await repo.setAudio(c.id, { ext, bytes: got.bytes, sha256: got.sha256, at: new Date(ctx.now()).toISOString() });
        if (saved) { await fsp.rename(mine, conv.file({ id: c.id, audioExt: ext })); return view(saved); }
        await fsp.rm(mine, { force: true });
      }
      const now = await need(c.id);
      if (now.audioSha256 !== got.sha256) throw new HttpError(409, 'Different audio already received');
      return view(now);
    } finally { await fsp.rm(staged, { force: true }).catch(() => {}); }
  });
  r.get('/ambient/status', () => conv.status());
}
