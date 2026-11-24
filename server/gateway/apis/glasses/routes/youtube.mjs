import fs from 'node:fs/promises';
import { HttpError } from '../../../lib/http.mjs';
import { clamp } from '../lib/b-upstream.mjs';
export function mapPicks(rows, limit = 25) {
  return rows.filter(r => r.flagged !== false && (r.video_id ?? r.videoId)).sort((a, b) => (Date.parse(b.published_at ?? b.flagged_at) || 0) - (Date.parse(a.published_at ?? a.flagged_at) || 0))
    .slice(0, Math.round(clamp(limit || 25, 1, 25))).map(r => ({ videoId: r.video_id ?? r.videoId, title: r.title ?? '', channel: r.channel_name ?? r.channel ?? '', durationSec: Number(r.duration_seconds ?? r.durationSec ?? 0) }));
}
// A curated video list read from a JSON file (youtube.file / YOUTUBE_LIST_FILE), e.g. written by your own script.
// The official API offers no Watch Later or History, so this is the one list. No file: no lists.
export function register(r, ctx) {
  const cfg = () => ({ key: 'picks', name: 'Picks', file: '', ...(ctx.config.youtube ?? {}) });
  r.get('/youtube/lists', () => { const c = cfg(); return c.file ? [{ key: c.listKey ?? c.key, name: c.listName ?? c.name }] : []; });
  r.get('/youtube/list/:key', async ({ params, query }) => {
    const c = cfg(), key = c.listKey ?? c.key;
    if (['watch-later', 'history'].includes(params.key)) throw new HttpError(404, 'YouTube Watch Later and History are unavailable through the official API');
    if (!c.file || params.key !== key) throw new HttpError(404, 'YouTube list not found');
    let rows;
    // Re-opened on every request (no cache): a writer may replace the file atomically at any time.
    try { rows = JSON.parse(await fs.readFile(c.file, 'utf8')); }
    catch (e) { ctx.log?.('glasses youtube', 'list unreadable', e?.code || e?.name || 'error'); return []; }
    return mapPicks(Array.isArray(rows) ? rows : rows.items ?? rows.videos ?? [], query.limit);
  });
}
