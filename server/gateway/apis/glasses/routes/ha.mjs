import { readJson } from '../../../lib/web.mjs';
import { HttpError } from '../../../lib/http.mjs';
import { cached, invalidate, json, clamp } from '../lib/b-upstream.mjs';

// The Home Assistant panel is configuration (homeAssistant in gateway.config.json): scene buttons, an optional alarm card
// and 3D-printer cards. URL and token come from the secrets HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN.
//   scenes:   [{ id, name, icon?, entity }]                    entity: script.* or scene.*
//   alarm:    { time: sensor.*, mode: select.*, times: { "7:00 AM": script.* }, styles: ["Gentle", ...] } | null
//   printers: [{ id, name, progress?, state?, online?, entityPrefix? }]  entityPrefix finds "time left" sensors
export function panelConfig(ctx) {
  const c = ctx.config.homeAssistant ?? {};
  return { scenes: Array.isArray(c.scenes) ? c.scenes : [], alarm: c.alarm ?? null, printers: Array.isArray(c.printers) ? c.printers : [] };
}
async function ha(ctx, path, body) {
  if (ctx.config.homeAssistant?.enabled === false) throw new HttpError(503, 'Home Assistant is not enabled on this gateway');
  const [url, token] = await Promise.all([ctx.secret('HOMEASSISTANT_URL'), ctx.secret('HOMEASSISTANT_TOKEN')]);
  return json(ctx, url.replace(/\/$/, '') + '/api' + path, { headers: { Authorization: `Bearer ${token}` }, ...(body ? { method: 'POST', body } : {}) });
}
export function formatAlarmTime(raw, tz = 'UTC') {
  if (!raw || ['unavailable', 'unknown'].includes(raw)) return '';
  // HA's template sensor reports e.g. "Today 08:30" or "Tomorrow 07:00"; take the trailing clock time.
  const time = /(?:^|\s)(\d{1,2}):(\d{2})(?::\d{2})?$/.exec(raw);
  if (time) return `${Number(time[1]) % 12 || 12}:${time[2]} ${Number(time[1]) >= 12 ? 'PM' : 'AM'}`;
  if (/^\d{1,2}:\d{2} [AP]M$/.test(raw)) return raw;
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('en-US', { timeZone: tz, hour: 'numeric', minute: '2-digit' }).format(date);
}
export function printerTimeLeft(states, now = Date.now(), prefix = '') {
  if (!prefix) return null;
  const candidates = states.filter(s => s.entity_id?.startsWith(prefix) && /remain|time_left|time_to_(?:end|finish)|end_time|finish_time/i.test(s.entity_id + ' ' + (s.attributes?.device_class ?? '')));
  for (const s of candidates) {
    if (s.state == null || ['unknown', 'unavailable', ''].includes(s.state)) continue;
    if (s.attributes?.device_class === 'timestamp') {
      const end = Date.parse(s.state); if (Number.isFinite(end)) return Math.max(0, Math.round((end - now) / 1000));
    }
    const clock = /^(\d+):(\d{2})(?::(\d{2}))?$/.exec(s.state);
    if (clock) return Number(clock[1]) * 3600 + Number(clock[2]) * 60 + Number(clock[3] ?? 0);
    const value = Number(s.state); if (!Number.isFinite(value) || value < 0) continue;
    const unit = String(s.attributes?.unit_of_measurement ?? '').toLowerCase();
    const scale = { s: 1, sec: 1, seconds: 1, min: 60, minutes: 60, h: 3600, hours: 3600 }[unit];
    if (scale) return Math.round(value * scale);
  }
  return null;
}
export function mapPanel(states, tz, now = Date.now(), panel = { scenes: [], alarm: null, printers: [] }) {
  const entities = new Map(states.map(s => [s.entity_id, s]));
  const state = id => id ? entities.get(id)?.state : undefined;
  const printers = panel.printers.map(p => {
    const print = entities.get(p.state), info = print?.attributes?.info ?? print?.attributes ?? {};
    const online = state(p.online), progress = state(p.progress);
    const layer = Number(info.current_layer), layers = Number(info.total_layer);
    const name = online === 'off' ? 'offline' : state(p.state) ?? 'unknown';
    return { id: p.id, name: p.name, state: name, progress: clamp(progress, 0, 100), timeLeftSec: printerTimeLeft(states, now, p.entityPrefix ?? ''),
      layer: Number.isFinite(layer) ? layer : null, layers: Number.isFinite(layers) ? layers : null,
      detail: Number.isFinite(layer) && Number.isFinite(layers) ? `Layer ${layer} of ${layers}` : String(name) };
  });
  return { scenes: panel.scenes.map(({ entity, ...scene }) => scene),
    alarm: panel.alarm ? { time: formatAlarmTime(state(panel.alarm.time), tz), style: state(panel.alarm.mode) ?? '' } : { time: '', style: '' },
    printers };
}
export function register(r, ctx) {
  r.get('/ha/panel', () => cached(ctx, 'ha.panel', 5000, async () => mapPanel(await ha(ctx, '/states'), ctx.config.tz, ctx.now(), panelConfig(ctx))));
  r.post('/ha/scene', async ({ req }) => {
    const { id } = await readJson(req), scene = panelConfig(ctx).scenes.find(s => s.id === id);
    if (!scene) throw new HttpError(400, 'Unknown scene');
    await ha(ctx, '/services/script/turn_on', { entity_id: scene.entity }); invalidate(ctx, 'ha.panel'); return { ok: true };
  });
  r.post('/ha/alarm', async ({ req }) => {
    const { time, style } = await readJson(req), alarm = panelConfig(ctx).alarm;
    if (!alarm) throw new HttpError(404, 'No alarm configured');
    if ((!time && !style) || (time !== undefined && !Object.hasOwn(alarm.times, time)) || (style !== undefined && !alarm.styles.includes(style))) throw new HttpError(400, 'Invalid alarm setting');
    if (time) await ha(ctx, '/services/script/turn_on', { entity_id: alarm.times[time] });
    if (style) await ha(ctx, '/services/select/select_option', { entity_id: alarm.mode, option: style });
    invalidate(ctx, 'ha.panel'); return { ok: true };
  });
}
