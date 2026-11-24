import { HttpError } from '../../../lib/http.mjs';
import { cached, json, localDate, shiftDate, midnight, clamp } from '../lib/b-upstream.mjs';

const weightTokens = new WeakMap();
const finite = v => v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));
const number = v => finite(v) ? Number(v) : null;
const round = v => v == null ? null : Math.round(v * 10) / 10;
const label = key => key.replaceAll('_', ' ').replace(/\b\w/g, c => c.toUpperCase());
const records = data => {
  const rows = Array.isArray(data) ? data : data?.data ?? data?.operations ?? data?.items;
  return Array.isArray(rows) ? rows : [];
};
const timestamp = value => {
  if (value === undefined || value === null || value === '') return null;
  const n = typeof value === 'number' ? (value < 1e12 ? value * 1000 : value) : value;
  const date = new Date(n); return Number.isFinite(date.getTime()) ? date.toISOString() : null;
};
const hasDay = row => /^\d{4}-\d{2}-\d{2}$/.test(row?.day ?? '');

async function weights(ctx) {
  async function login() {
    const [email, password] = await Promise.all([ctx.secret('WEIGHTGURUS_EMAIL'), ctx.secret('WEIGHTGURUS_PASSWORD')]);
    const response = await json(ctx, 'https://api.weightgurus.com/v3/account/login', { method: 'POST', body: { email, password } });
    const token = response.accessToken ?? response.data?.accessToken;
    if (!token) throw new Error('Weight unavailable');
    weightTokens.set(ctx, { token, until: ctx.now() + 3600000 }); return token;
  }
  let hit = weightTokens.get(ctx), token = hit && hit.until > ctx.now() ? hit.token : await login();
  try { return records(await json(ctx, 'https://api.weightgurus.com/v3/operation/', { headers: { Authorization: `Bearer ${token}` } })); }
  catch { weightTokens.delete(ctx); throw new Error('Weight unavailable'); }
}
async function oura(ctx, endpoint, start, end) {
  const token = await ctx.secret('OURA_ACCESS_TOKEN');
  const data = []; let next;
  for (let page = 0; page < 10; page++) {
    const url = new URL(`https://api.ouraring.com/v2/usercollection/${endpoint}`);
    url.search = new URLSearchParams({ start_date: start, end_date: end, ...(next ? { next_token: next } : {}) });
    const result = await json(ctx, url, { headers: { Authorization: `Bearer ${token}` } });
    data.push(...records(result)); next = result?.next_token; if (!next) break;
  }
  return data;
}
function dailySeries(rows, getValue, getDate, start, end) {
  const byDay = new Map();
  for (const row of rows) {
    let date; try { date = getDate(row); } catch { continue; }
    const value = getValue(row);
    if (date >= start && date <= end && finite(value)) byDay.set(date, round(Number(value)));
  }
  return [...byDay].sort(([a], [b]) => a.localeCompare(b)).map(([date, value]) => ({ date, value }));
}
function base(key, name, unit) { return { key, name, value: null, measuredAt: null, series: [], unit, breakdown: [] }; }
const positive = v => finite(v) && Number(v) > 0;
const LABELS = {
  deep_sleep: 'Deep sleep', rem_sleep: 'REM sleep', total_sleep: 'Total sleep score', efficiency: 'Efficiency', latency: 'Latency',
  restfulness: 'Restfulness', timing: 'Timing', activity_balance: 'Activity balance', body_temperature: 'Body temperature',
  hrv_balance: 'HRV balance', previous_day_activity: 'Previous day activity', previous_night: 'Previous night',
  recovery_index: 'Recovery index', resting_heart_rate: 'Resting heart rate', sleep_balance: 'Sleep balance', sleep_regularity: 'Sleep regularity',
};
const signed = (v, unit) => `${v > 0 ? '+' : ''}${v}${unit}`;
const trendOf = v => v > 0 ? 'up' : v < 0 ? 'down' : 'flat';
function duration(min) { const m = Math.round(min); return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`; }
function clock(min) { const m = ((Math.round(min) % 1440) + 1440) % 1440, h = Math.floor(m / 60); return `${h % 12 || 12}:${String(m % 60).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`; }
// Minutes after local midnight; bedtimes before noon belong to the previous evening (+24 h) so the series stays continuous.
function localMinutes(iso, tz, evening = false) {
  const ms = Date.parse(iso ?? ''); if (!Number.isFinite(ms)) return null;
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(ms).map(p => [p.type, p.value]));
  const m = Number(parts.hour) * 60 + Number(parts.minute);
  return evening && m < 720 ? m + 1440 : m;
}
const FORMAT = { min: duration, clock, '%': v => `${Math.round(v)}%`, ms: v => `${Math.round(v)} ms`, bpm: v => `${Math.round(v)} bpm`, brpm: v => `${round(v)} brpm`, '°C': v => signed(round(v), ' °C'), lb: v => `${round(v)} lb`, kg: v => `${round(v)} kg` };
// One breakdown row with its own daily history. `value`/`score` is the metric's latest day only, never an older day.
function row(key, label, unit, series, latestDay, extra = {}) {
  if (!series.length) return null;
  const at = latestDay ? series.find(p => p.date === latestDay) : series.at(-1);
  const out = { key, label, unit, series, ...extra };
  if (at) { if (unit === 'score') out.score = at.value; else out.value = FORMAT[unit] ? FORMAT[unit](at.value) : String(at.value); }
  return out;
}
export function assembleHealth({ weight = [], readiness = [], sleep = [], periods = [] }, now, tz = 'UTC', units = 'imperial') {
  const end = localDate(now, tz), start = shiftDate(end, -29);
  // Weight Gurus stores tenths of a pound.
  const wu = units === 'metric' ? 'kg' : 'lb', toUnit = tenths => wu === 'kg' ? Number(tenths) / 10 * 0.45359237 : Number(tenths) / 10;
  const out = [base('weight', 'Weight', wu), base('readiness', 'Readiness', ''), base('sleep', 'Sleep', ''), base('hrv', 'HRV', 'ms'), base('rhr', 'Resting HR', 'bpm')];
  // Weight Gurus reports 0 when something was not measured; zero is never a real sample.
  const weightRows = weight.filter(w => w && positive(w.weight) && timestamp(w.entryTimestamp)).sort((a, b) => timestamp(a.entryTimestamp).localeCompare(timestamp(b.entryTimestamp)));
  const w = out[0];
  const weighDay = r => localDate(timestamp(r.entryTimestamp), tz);
  w.series = dailySeries(weightRows, r => toUnit(r.weight), weighDay, start, end);
  const latestWeight = weightRows.at(-1);
  if (latestWeight) {
    w.value = round(toUnit(latestWeight.weight));
    try { w.measuredAt = timestamp(latestWeight.entryTimestamp); } catch {}
    w.measuredKind = 'reading'; w.measuredDay = weighDay(latestWeight);
    const week = w.series.filter(p => p.date >= shiftDate(end, -6));
    if (week.length) w.breakdown.push({ key: 'avg7', label: '7-day average', value: `${round(week.reduce((s, p) => s + p.value, 0) / week.length)} ${wu}`, unit: wu });
    for (const [key, name, points] of [['change30', '30-day change', w.series], ['change7', '7-day change', week]]) {
      if (points.length < 2) continue;
      const change = round(points.at(-1).value - points[0].value);
      w.breakdown.push({ key, label: name, value: signed(change, ' ' + wu), unit: wu, trend: trendOf(change) });
    }
    // Weight Gurus stores body fat and muscle in tenths of a percent, like weight in tenths of a pound.
    for (const [key, name, keys] of [['body_fat', 'Body fat', ['bodyFat', 'bodyFatPercentage', 'body_fat']], ['muscle', 'Muscle', ['muscleMass', 'muscle', 'musclePercentage']]]) {
      const field = r => keys.find(k => positive(r[k]));
      const rows = weightRows.filter(field);
      const latest = rows.at(-1);
      if (latest) w.breakdown.push({ key, label: name, value: `${round(Number(latest[field(latest)]) / 10)}%`, unit: '%', series: dailySeries(rows, r => Number(r[field(r)]) / 10, weighDay, start, end) });
    }
  }
  // Use the longest sleep period per day rather than letting a short nap replace the night.
  const nights = new Map();
  for (const p of periods.filter(hasDay)) if (!nights.has(p.day) || (p.total_sleep_duration ?? 0) > (nights.get(p.day).total_sleep_duration ?? 0)) nights.set(p.day, p);
  const nightRows = [...nights.values()].sort((a, b) => a.day.localeCompare(b.day));
  const nightSeries = value => dailySeries(nightRows.filter(p => positive(value(p))), value, p => p.day, start, end);
  for (const [metric, rows] of [[out[1], readiness], [out[2], sleep]]) {
    const sorted = rows.filter(r => hasDay(r) && positive(r.score)).sort((a, b) => a.day.localeCompare(b.day));
    metric.series = dailySeries(sorted, r => clamp(r.score, 0, 100), r => r.day, start, end);
    const latest = sorted.at(-1);
    if (!latest) continue;
    metric.value = clamp(latest.score, 0, 100);
    // Oura's daily `timestamp` is the date at UTC midnight, not a measurement time: use the wake time.
    const wake = timestamp(nights.get(latest.day)?.bedtime_end);
    metric.measuredAt = wake ?? new Date(Date.parse(midnight(latest.day, tz)) + 12 * 3600000).toISOString();
    metric.measuredKind = wake ? 'wake' : 'day'; metric.measuredDay = latest.day;
    const keys = [...new Set(sorted.flatMap(r => Object.keys(r.contributors ?? {})))];
    const contributors = keys.map(key => row(key, LABELS[key] ?? label(key), 'score',
      dailySeries(sorted.filter(r => positive(r.contributors?.[key])), r => clamp(r.contributors[key], 0, 100), r => r.day, start, end), latest.day)).filter(r => r && r.score != null);
    if (metric.key === 'readiness') {
      metric.breakdown = [...contributors, row('temperature', 'Temperature', '°C',
        dailySeries(sorted.filter(r => finite(r.temperature_deviation)), r => Number(r.temperature_deviation), r => r.day, start, end), latest.day)].filter(r => r && (r.value != null || r.score != null));
      continue;
    }
    const totals = new Map();
    for (const p of periods.filter(hasDay)) totals.set(p.day, (totals.get(p.day) ?? 0) + (number(p.total_sleep_duration) ?? 0));
    const total = dailySeries([...totals].filter(([, v]) => v > 0), ([, v]) => Math.round(v / 60), ([d]) => d, start, end);
    const minutes = field => nightSeries(p => positive(p[field]) ? Math.round(Number(p[field]) / 60) : null);
    metric.breakdown = [
      row('total_min', 'Total sleep', 'min', total, latest.day),
      row('bedtime', 'Bedtime', 'clock', nightSeries(p => localMinutes(p.bedtime_start, tz, true)), latest.day),
      row('wake', 'Wake time', 'clock', nightSeries(p => localMinutes(p.bedtime_end, tz)), latest.day),
      ...contributors,
      row('deep_min', 'Deep time', 'min', minutes('deep_sleep_duration'), latest.day),
      row('rem_min', 'REM time', 'min', minutes('rem_sleep_duration'), latest.day),
      row('light_min', 'Light time', 'min', minutes('light_sleep_duration'), latest.day),
      row('awake_min', 'Awake time', 'min', minutes('awake_time'), latest.day),
      row('in_bed_min', 'Time in bed', 'min', minutes('time_in_bed'), latest.day),
      row('latency_min', 'Time to sleep', 'min', minutes('latency'), latest.day),
      row('efficiency_pct', 'Sleep efficiency', '%', nightSeries(p => number(p.efficiency)), latest.day),
      row('hrv_avg', 'Average HRV', 'ms', nightSeries(p => number(p.average_hrv)), latest.day),
      row('hr_lowest', 'Lowest HR', 'bpm', nightSeries(p => number(p.lowest_heart_rate)), latest.day),
      row('hr_avg', 'Average HR', 'bpm', nightSeries(p => number(p.average_heart_rate)), latest.day),
      row('breath', 'Breathing rate', 'brpm', nightSeries(p => number(p.average_breath)), latest.day),
    ].filter(r => r && (r.value != null || r.score != null));
  }
  for (const [metric, field] of [[out[3], 'average_hrv'], [out[4], 'lowest_heart_rate']]) {
    const value = p => field === 'lowest_heart_rate' ? (positive(p.lowest_heart_rate) ? Number(p.lowest_heart_rate) : positive(p.average_heart_rate) ? Number(p.average_heart_rate) : null) : (positive(p[field]) ? Number(p[field]) : null);
    const sorted = nightRows.filter(p => value(p) != null);
    metric.series = dailySeries(sorted, value, p => p.day, start, end);
    const latest = sorted.at(-1);
    if (latest) {
      const wake = timestamp(latest.bedtime_end);
      metric.value = round(value(latest)); metric.measuredAt = wake ?? midnight(latest.day, tz);
      metric.measuredKind = wake ? 'wake' : 'day'; metric.measuredDay = latest.day;
    }
  }
  return out;
}
export function overview(ctx) {
  return cached(ctx, 'health.overview', 300000, async () => {
    const today = localDate(ctx.now(), ctx.config.tz), start = shiftDate(today, -29), end = shiftDate(today, 1);
    const results = await Promise.allSettled([weights(ctx), oura(ctx, 'daily_readiness', start, end), oura(ctx, 'daily_sleep', start, end), oura(ctx, 'sleep', start, end)]);
    const values = results.map(r => r.status === 'fulfilled' ? r.value : []);
    return assembleHealth({ weight: values[0], readiness: values[1], sleep: values[2], periods: values[3] }, ctx.now(), ctx.config.tz, ctx.config.units);
  });
}
export function ringBattery(ctx) {
  return cached(ctx, 'health.battery', 60000, async () => {
    try {
      // Public Oura v2 OpenAPI 1.43: ring_battery_level?latest=true; level is 0–100.
      const token = await ctx.secret('OURA_ACCESS_TOKEN');
      const data = await json(ctx, 'https://api.ouraring.com/v2/usercollection/ring_battery_level?latest=true', { headers: { Authorization: `Bearer ${token}` }, timeoutMs: 3000 });
      const row = records(data).filter(r => finite(r.level) && r.level >= 0 && r.level <= 100).sort((a, b) => (Date.parse(b.timestamp) || b.timestamp_unix || 0) - (Date.parse(a.timestamp) || a.timestamp_unix || 0))[0];
      const measuredAt = timestamp(row?.timestamp ?? row?.timestamp_unix);
      // Cloud battery is the last synced sample, never a claim about a live BLE connection.
      const age = ctx.now() - Date.parse(measuredAt);
      return { level: row && measuredAt && age >= 0 && age < 86400000 ? row.level / 100 : null, measuredAt };
    } catch { return { level: null, measuredAt: null }; }
  });
}
// Oura heart rate for a time window (e.g. one workout). Samples arrive only after the
// ring syncs, so callers must treat an empty or short list as "not synced yet", never as live.
export async function heartRate(ctx, startRaw, endRaw) {
  const start = Date.parse(startRaw ?? ''), end = Date.parse(endRaw ?? '');
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) throw new HttpError(400, 'start and end must be ISO times with end after start');
  if (end - start > 86400000) throw new HttpError(400, 'window is limited to 24 hours');
  const iso = ms => new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');
  return cached(ctx, `health.hr:${start}:${end}`, 60000, async () => {
    const token = await ctx.secret('OURA_ACCESS_TOKEN');
    const rows = []; let next;
    for (let page = 0; page < 10; page++) {
      const url = new URL('https://api.ouraring.com/v2/usercollection/heartrate');
      url.search = new URLSearchParams({ start_datetime: iso(start), end_datetime: iso(end), ...(next ? { next_token: next } : {}) });
      const result = await json(ctx, url, { headers: { Authorization: `Bearer ${token}` } });
      rows.push(...records(result)); next = result?.next_token; if (!next) break;
    }
    const samples = rows.map(r => ({ t: timestamp(r?.timestamp), bpm: number(r?.bpm) }))
      .filter(s => s.t && s.bpm != null && s.bpm > 20 && s.bpm < 250 && Date.parse(s.t) >= start && Date.parse(s.t) <= end)
      .sort((a, b) => a.t.localeCompare(b.t));
    return { samples };
  });
}
export function register(r, ctx) {
  r.get('/health/heartrate', ({ query }) => heartRate(ctx, query.start, query.end));
  r.get('/health/overview', () => overview(ctx));
  r.get('/health/battery', () => ringBattery(ctx));
}
