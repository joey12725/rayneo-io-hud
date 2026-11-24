// Calendar day labels, health history and morning-report freshness (requirements 11-13).
// Synthetic data only; every upstream is a fake fetch.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createStore } from '../lib/store.mjs';
import { createContext } from '../apis/glasses/lib/context.mjs';
import { groupEvents, mapEvent, pickNextEvent } from '../apis/glasses/lib/calendar.mjs';
import { assembleHealth } from '../apis/glasses/routes/health.mjs';
import { latestReport, ensureToday, fallbackReport, validateReport, startReportWatcher } from '../apis/glasses/routes/report.mjs';

const TZ = 'America/Indiana/Knox';
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const allDay = (id, start, end) => mapEvent({ id, summary: id, start: { date: start }, end: { date: end } }, TZ);
const timed = (id, start, end) => mapEvent({ id, summary: id, start: { dateTime: start }, end: { dateTime: end } }, TZ);

test('calendar events carry inclusive local start and end dates, all-day dates verbatim', () => {
  const friendsgiving = allDay('Friendsgiving', '2026-11-19', '2026-11-20');
  assert.equal(friendsgiving.start, '2026-11-19T06:00:00.000Z');
  assert.deepEqual([friendsgiving.startDate, friendsgiving.endDate], ['2026-11-19', '2026-11-19']);
  const trip = allDay('Trip', '2026-11-19', '2026-11-22'); // Thu-Sat; Google's end date is exclusive
  assert.deepEqual([trip.startDate, trip.endDate], ['2026-11-19', '2026-11-21']);
  // 11:30 PM to 12:30 AM central time crosses local midnight; ending exactly at midnight stays on the start day.
  const late = timed('Late', '2026-11-18T05:30:00Z', '2026-11-18T06:30:00Z');
  assert.deepEqual([late.startDate, late.endDate], ['2026-11-17', '2026-11-18']);
  const toMidnight = timed('Until midnight', '2026-11-18T05:00:00Z', '2026-11-18T06:00:00Z');
  assert.deepEqual([toMidnight.startDate, toMidnight.endDate], ['2026-11-17', '2026-11-17']);
  const dst = allDay('DST', '2026-03-08', '2026-03-09');
  assert.deepEqual([dst.startDate, dst.endDate, dst.end], ['2026-03-08', '2026-03-08', '2026-03-09T05:00:00.000Z']);
});

test('multi-day all-day events appear only on their own local days', () => {
  const now = Date.parse('2026-11-18T01:46:00Z'); // Tue Nov 17, 7:46 PM central time
  const trip = allDay('Trip', '2026-11-19', '2026-11-22');
  const groups = groupEvents([trip, allDay('Friendsgiving', '2026-11-19', '2026-11-20')], now, 6, TZ);
  assert.deepEqual(groups.map(d => [d.date, d.events.map(e => e.id).join(',')]), [
    ['2026-11-17', ''], ['2026-11-18', ''], ['2026-11-19', 'Trip,Friendsgiving'], ['2026-11-20', 'Trip'], ['2026-11-21', 'Trip'], ['2026-11-22', ''],
  ]);
});

test('next event: Friendsgiving two days out keeps its date; a timed event today beats an all-day event', () => {
  const tuesdayEvening = Date.parse('2026-11-18T01:46:00Z');
  const thursday = [allDay('Friendsgiving', '2026-11-19', '2026-11-20'), timed('DIY night', '2026-11-20T01:00:00Z', '2026-11-20T03:00:00Z')];
  assert.deepEqual(pickNextEvent(thursday, tuesdayEvening, TZ), { title: 'Friendsgiving', start: '2026-11-19T06:00:00.000Z', end: '2026-11-20T06:00:00.000Z', location: '', allDay: true, startDate: '2026-11-19', endDate: '2026-11-19' });
  const thursdayNoon = Date.parse('2026-11-19T18:00:00Z');
  assert.equal(pickNextEvent(thursday, thursdayNoon, TZ).title, 'DIY night');
  assert.equal(pickNextEvent(thursday, Date.parse('2026-11-20T03:30:00Z'), TZ).title, 'Friendsgiving'); // 9:30 PM, DIY night over
  // In progress since yesterday still counts as the current timed event.
  const overnight = [allDay('Holiday', '2026-11-18', '2026-11-19'), timed('Overnight', '2026-11-18T05:00:00Z', '2026-11-18T07:00:00Z')];
  assert.equal(pickNextEvent(overnight, Date.parse('2026-11-18T06:30:00Z'), TZ).title, 'Overnight');
});

test('next event around midnight: 11:59 PM the day before is tomorrow, 12:00 AM is today', () => {
  const event = [allDay('Friendsgiving', '2026-11-19', '2026-11-20')];
  for (const [at, today] of [['2026-11-19T05:59:59Z', '2026-11-18'], ['2026-11-19T06:00:00Z', '2026-11-19']]) {
    const next = pickNextEvent(event, Date.parse(at), TZ);
    assert.equal(next.startDate, '2026-11-19');
    assert.equal(new Intl.DateTimeFormat('en-CA', { timeZone: TZ }).format(new Date(at)), today);
  }
  assert.equal(pickNextEvent(event, Date.parse('2026-11-20T06:00:00Z'), TZ), null);
});

const weigh = (weight, at, extra = {}) => ({ weight, entryTimestamp: at, ...extra });
const NOW = Date.parse('2026-11-17T19:00:00Z');

test('health weight: signed change with trend, zero body fat and muscle hidden, latest positive used', () => {
  const [weightMetric] = assembleHealth({ weight: [
    weigh(1728, '2026-10-23T12:00:00Z', { bodyFat: 212, muscleMass: 402 }), weigh(1746, '2026-11-10T16:07:36Z', { bodyFat: 0, muscleMass: 0 }),
  ] }, NOW);
  const byKey = Object.fromEntries(weightMetric.breakdown.map(b => [b.key, b]));
  assert.deepEqual([byKey.change30.value, byKey.change30.trend], ['+1.8 lb', 'up']);
  assert.equal(byKey.body_fat.value, '21.2%'); assert.equal(byKey.muscle.value, '40.2%');
  assert.ok(!weightMetric.breakdown.some(b => b.value === 0 || b.value === '0' || b.value === '0%'));
  const loss = assembleHealth({ weight: [weigh(1760, '2026-11-12T12:00:00Z'), weigh(1742, '2026-11-16T12:00:00Z', { bodyFat: 0 })] }, NOW, TZ)[0];
  const change = Object.fromEntries(loss.breakdown.map(b => [b.key, b]));
  assert.deepEqual([change.change30.value, change.change30.trend, change.change7.value, change.change7.trend], ['-1.8 lb', 'down', '-1.8 lb', 'down']);
  assert.equal(change.body_fat, undefined); assert.equal(change.muscle, undefined);
  const kg = assembleHealth({ weight: [weigh(1760, '2026-11-12T12:00:00Z'), weigh(1742, '2026-11-16T12:00:00Z')] }, NOW, TZ, 'metric')[0];
  assert.equal(kg.unit, 'kg'); assert.equal(kg.value, 79); assert.equal(Object.fromEntries(kg.breakdown.map(b => [b.key, b])).change7.value, '-0.8 kg');
  const flat = assembleHealth({ weight: [weigh(1750, '2026-11-12T12:00:00Z'), weigh(1750, '2026-11-16T12:00:00Z'), weigh(0, '2026-11-17T12:00:00Z')] }, NOW, TZ)[0];
  assert.equal(flat.breakdown.find(b => b.key === 'change30').trend, 'flat'); assert.equal(flat.value, 175);
  assert.equal(flat.series.length, 2, 'a zero weigh-in is not a sample');
});

const ouraDays = (n, from = '2026-10-19') => Array.from({ length: n }, (_, i) => new Date(Date.parse(from + 'T12:00:00Z') + i * 86400000).toISOString().slice(0, 10));
function sleepFixture() {
  const days = ouraDays(30);
  return {
    sleep: days.map((day, i) => ({ day, score: 60 + i % 20, timestamp: `${day}T00:00:00+00:00`, contributors: { deep_sleep: 50 + i, efficiency: 99, latency: 70, rem_sleep: 63, restfulness: 92, timing: 98, total_sleep: 44 } })),
    readiness: days.map((day, i) => ({ day, score: 70 + i % 10, timestamp: `${day}T00:00:00+00:00`, temperature_deviation: i % 2 ? 0.2 : -0.1, contributors: { activity_balance: 80, body_temperature: 90, hrv_balance: 60 + i, previous_day_activity: 70, previous_night: 75, recovery_index: 85, resting_heart_rate: 88, sleep_balance: 77, sleep_regularity: 66 } })),
    periods: days.flatMap((day, i) => [
      { day, bedtime_start: `${day}T0${4 + i % 3}:42:00Z`, bedtime_end: `${day}T13:01:59Z`, total_sleep_duration: 18120 + i * 60, deep_sleep_duration: 4320, rem_sleep_duration: 5400, light_sleep_duration: 9000, awake_time: 1800, time_in_bed: 21000, efficiency: 88, latency: 600, average_hrv: 54, lowest_heart_rate: 46, average_heart_rate: 52, average_breath: 14.5 },
      { day, bedtime_start: `${day}T20:00:00Z`, bedtime_end: `${day}T20:20:00Z`, total_sleep_duration: 1200, average_hrv: 10 }, // a nap
    ]),
  };
}

test('health sleep and readiness: wake time instead of the date-only Oura timestamp', () => {
  const metrics = assembleHealth(sleepFixture(), NOW, TZ);
  for (const key of ['readiness', 'sleep', 'hrv', 'rhr']) {
    const m = metrics.find(x => x.key === key);
    assert.equal(m.measuredAt, '2026-11-17T13:01:59.000Z', key); assert.equal(m.measuredKind, 'wake'); assert.equal(m.measuredDay, '2026-11-17');
  }
  const noPeriods = assembleHealth({ sleep: [{ day: '2026-11-17', score: 68, timestamp: '2026-11-17T00:00:00+00:00' }] }, NOW, TZ)[2];
  assert.equal(noPeriods.measuredAt, '2026-11-17T18:00:00.000Z'); assert.equal(noPeriods.measuredKind, 'day');
  assert.ok(!metrics.some(m => /T00:00:00/.test(m.measuredAt ?? '')));
});

test('health breakdown rows carry stable keys, units and a daily series per metric', () => {
  const metrics = assembleHealth(sleepFixture(), NOW, TZ);
  const sleep = metrics.find(m => m.key === 'sleep'), readiness = metrics.find(m => m.key === 'readiness');
  const keys = sleep.breakdown.map(b => b.key);
  for (const key of ['total_min', 'bedtime', 'wake', 'deep_sleep', 'efficiency', 'latency', 'rem_sleep', 'restfulness', 'timing', 'total_sleep', 'deep_min', 'rem_min', 'light_min', 'awake_min', 'in_bed_min', 'latency_min', 'efficiency_pct', 'hrv_avg', 'hr_lowest', 'hr_avg', 'breath']) assert.ok(keys.includes(key), key);
  assert.equal(new Set(keys).size, keys.length, 'keys are unique');
  assert.equal(new Set(sleep.breakdown.map(b => b.label)).size, keys.length, 'labels are unique (Total sleep vs Total sleep score)');
  for (const b of [...sleep.breakdown, ...readiness.breakdown]) {
    assert.equal(b.series.length, 30, b.key); assert.ok(b.unit, b.key);
    assert.ok(b.series.every((p, i, all) => /^\d{4}-\d{2}-\d{2}$/.test(p.date) && (!i || all[i - 1].date < p.date)), b.key);
  }
  const row = key => sleep.breakdown.find(b => b.key === key);
  assert.equal(row('total_min').value, '5h 51m', 'night plus nap, as before'); assert.equal(row('deep_min').value, '1h 12m'); assert.equal(row('latency_min').value, '10m');
  assert.equal(row('efficiency_pct').value, '88%'); assert.equal(row('hrv_avg').value, '54 ms', 'the nap does not replace the night');
  assert.equal(row('deep_sleep').score, 79); assert.equal(row('wake').value, '7:01 AM');
  // Bedtimes of 11:42 PM, 12:42 AM and 1:42 AM (CDT in October) stay one continuous evening scale.
  assert.deepEqual(row('bedtime').series.slice(0, 3).map(p => p.value), [1422, 1482, 1542]);
  assert.equal(readiness.breakdown.find(b => b.key === 'hrv_balance').series.at(-1).value, 89);
  assert.equal(readiness.breakdown.find(b => b.key === 'temperature').value, '+0.2 °C');
  assert.equal(readiness.breakdown.find(b => b.key === 'resting_heart_rate').label, 'Resting heart rate');
});

test('health hides metrics without real samples instead of reporting zero', () => {
  const metrics = assembleHealth({ periods: [{ day: '2026-11-17', bedtime_end: '2026-11-17T13:00:00Z', average_hrv: 0, lowest_heart_rate: 0, average_heart_rate: 0 }], sleep: [{ day: '2026-11-17', score: 0, contributors: { efficiency: 0 } }] }, NOW, TZ);
  for (const m of metrics) { assert.equal(m.value, null, m.key); assert.deepEqual(m.series, [], m.key); assert.deepEqual(m.breakdown, [], m.key); }
});

async function reportContext(t, { now, fetch, config = {} } = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'gateway-report-'));
  const store = createStore(path.join(dir, 'store'));
  const drop = path.join(dir, 'glasses'), runs = path.join(dir, 'runs');
  await fs.mkdir(drop); await fs.mkdir(runs);
  const secrets = { get: async () => 'fake-secret' };
  const make = () => createContext({ env: {}, log: () => {}, store, secrets }, { now: () => now.value, fetch: fetch ?? (() => { throw new Error('No network allowed'); }), config: { reportSourceDir: runs, tz: 'America/Indiana/Knox', reportWatchMs: 0, reportSettleMs: 0, ...config } });
  const ctx = await make();
  t.after(async () => { await store.flushed(); await fs.rm(dir, { recursive: true, force: true }); });
  async function write(date, markdown) { await fs.mkdir(path.join(runs, date), { recursive: true }); await fs.writeFile(path.join(runs, date, 'report.md'), markdown); }
  return { ctx, store, drop, runs, write, make };
}
const modelCards = date => ({ choices: [{ message: { content: JSON.stringify({ date, generatedAt: '2026-11-17T11:50:00Z', cards: Array.from({ length: 6 }, (_, i) => ({ type: 'note', icon: 'joy', heading: `Card ${i}`, body: 'Breakfast first; dashboards later.' })) }) } }] });
const MORNING = `# Morning report 2026-11-17

Good morning! The kettle is judging you.

## Weather
Cold front at **3 PM**; bring a jacket. See [radar](https://example.invalid/radar).

## Today's plan
- 9:30 dentist
- 1:00 client call

| Task | Owner |
|---|---|
| Ship the fix | Robin |
`;

test('report from an older day is marked stale with its age and source time', async t => {
  const now = { value: Date.parse('2026-11-18T01:46:00Z') }; // Tue Nov 17 evening central time
  const f = await reportContext(t, { now, fetch: async () => json(modelCards('2026-11-12')) });
  await f.write('2026-11-12', '# Old report\nStill here.');
  const report = await latestReport(f.ctx);
  assert.deepEqual([report.date, report.reportDate, report.today, report.isToday, report.stale, report.ageDays], ['2026-11-12', '2026-11-12', '2026-11-17', false, true, 5]);
  assert.match(report.sourceWrittenAt, /^\d{4}-\d{2}-\d{2}T/);
  await f.write('2026-11-17', MORNING);
  const fresh = await latestReport(f.ctx);
  assert.deepEqual([fresh.date, fresh.stale, fresh.isToday, fresh.ageDays], ['2026-11-17', false, true, 0]);
});

test('report watcher converts today once, announces once and does not repeat after a restart', async t => {
  const now = { value: Date.parse('2026-11-17T11:50:00Z') };
  let conversions = 0;
  const f = await reportContext(t, { now, fetch: async () => { conversions++; return json(modelCards('2026-11-17')); } });
  assert.equal(await ensureToday(f.ctx), null, 'nothing yet');
  await f.write('2026-11-12', '# Old\nOld news.');
  assert.equal(await ensureToday(f.ctx), null, 'an older report is never announced');
  assert.equal(f.ctx.events.since(0).length, 0);
  await f.write('2026-11-17', MORNING);
  const report = await ensureToday(f.ctx);
  assert.equal(report.date, '2026-11-17'); assert.equal(report.fallback, false); assert.equal(conversions, 1);
  await ensureToday(f.ctx);
  const events = f.ctx.events.since(0);
  assert.deepEqual(events.map(e => e.type), ['report', 'notification']);
  assert.deepEqual([events[0].date, events[1].title, events[1].open.screen, events[1].importance], ['2026-11-17', 'Morning report ready', 'report', 0.35]);
  assert.equal(conversions, 1, 'pre-converted and cached: the phone read costs nothing');
  assert.equal((await latestReport(f.ctx)).cards[0].heading, 'Card 0'); assert.equal(conversions, 1);
  const restarted = await f.make();
  await ensureToday(restarted);
  assert.equal(restarted.events.since(0).length, 2, 'restart does not re-announce');
  // The poller starts from register() and can be switched off with reportWatchMs: 0.
  assert.equal(startReportWatcher(f.ctx), null);
  const watcher = startReportWatcher({ ...f.ctx, config: { ...f.ctx.config, reportWatchMs: 60000 } }); watcher.stop();
});

test('report conversion failure (model quota) serves deterministic cards, never a 502', async t => {
  const now = { value: Date.parse('2026-11-17T11:50:00Z') };
  let attempts = 0, quota = true;
  const f = await reportContext(t, { now, fetch: async () => { attempts++; return quota ? json({ error: 'quota' }, 429) : json(modelCards('2026-11-17')); } });
  await f.write('2026-11-17', MORNING);
  const report = await latestReport(f.ctx);
  assert.equal(report.fallback, true); assert.equal(report.date, '2026-11-17'); assert.equal(report.stale, false);
  assert.deepEqual(report.cards.map(c => c.heading), ['Morning report', 'Weather', "Today's plan"]);
  assert.equal(report.cards[1].icon, 'sun'); assert.equal(report.cards[1].body, 'Cold front at 3 PM; bring a jacket. See radar.');
  assert.equal(report.cards[2].body, '• 9:30 dentist • 1:00 client call Task · Owner Ship the fix · Robin');
  assert.ok(report.cards.every(c => [...c.heading].length <= 14 && [...c.body].length <= 220));
  await latestReport(f.ctx); assert.equal(attempts, 1, 'retry waits, cached fallback served');
  await ensureToday(f.ctx);
  assert.deepEqual(f.ctx.events.since(0).map(e => e.type), ['report', 'notification'], 'a fallback report is still announced');
  quota = false; now.value += 16 * 60000;
  assert.equal((await latestReport(f.ctx)).fallback, true, 'served immediately while the retry runs');
  await new Promise(resolve => setTimeout(resolve, 20));
  const upgraded = await latestReport(f.ctx);
  assert.equal(upgraded.fallback, false); assert.equal(upgraded.cards[0].heading, 'Card 0'); assert.equal(attempts, 2);
  assert.equal(f.ctx.events.since(0).at(-1).type, 'report'); assert.equal(f.ctx.events.since(0).at(-1).upgraded, true);
});

test('a slow model does not block the reader: fallback now, report event when the model finishes', async t => {
  const now = { value: Date.parse('2026-11-17T11:50:00Z') };
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const f = await reportContext(t, { now, fetch: async () => { await gate; return json(modelCards('2026-11-17')); }, config: { reportWaitMs: 10 } });
  await f.write('2026-11-17', MORNING);
  const first = await latestReport(f.ctx);
  assert.equal(first.fallback, true); assert.equal(first.converting, true);
  release(); await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal((await latestReport(f.ctx)).cards[0].heading, 'Card 0');
  assert.deepEqual(f.ctx.events.since(0).map(e => [e.type, e.upgraded]), [['report', true]]);
});

test("Hermes's JSON for today wins and is announced once", async t => {
  const now = { value: Date.parse('2026-11-17T11:50:00Z') };
  const f = await reportContext(t, { now });
  await f.write('2026-11-12', '# Old');
  const joy = { date: '2026-11-17', generatedAt: '2026-11-17T11:00:00Z', cards: ['Hi', 'Rain', 'Bye'].map(h => ({ type: 'note', icon: 'joy', heading: h, body: `${h} from Hermes.` })) };
  await fs.writeFile(path.join(f.runs, 'morning-report.json'), JSON.stringify(joy));
  assert.equal((await ensureToday(f.ctx)).cards[0].body, 'Hi from Hermes.');
  await ensureToday(f.ctx);
  assert.deepEqual(f.ctx.events.since(0).map(e => e.type), ['report', 'notification']);
});

test('fallback conversion is bounded and validates as a report', () => {
  const long = Array.from({ length: 14 }, (_, i) => `## Section ${i}\n${'Words keep going here. '.repeat(30)}`).join('\n');
  const report = fallbackReport(long, '2026-11-17', Date.parse('2026-11-17T12:00:00Z'));
  assert.equal(report.cards.length, 10);
  assert.ok(report.cards.every(c => [...c.body].length <= 220));
  assert.equal(validateReport(report).cards.length, 10);
  assert.equal(fallbackReport('', '2026-11-17', 0).cards.length, 1);
});

test('the watcher waits for a report.md that is still being written', async t => {
  const now = { value: Date.parse('2026-11-17T11:50:00Z') };
  let conversions = 0;
  const f = await reportContext(t, { now, fetch: async () => { conversions++; return json(modelCards('2026-11-17')); }, config: { reportSettleMs: 60000 } });
  await f.write('2026-11-17', MORNING);
  assert.equal(await ensureToday(f.ctx), null, 'just written: not yet');
  assert.equal(conversions, 0); assert.equal(f.ctx.events.since(0).length, 0);
  const settled = new Date(Date.now() - 120000);
  await fs.utimes(path.join(f.runs, '2026-11-17', 'report.md'), settled, settled);
  assert.equal((await ensureToday(f.ctx)).date, '2026-11-17');
  assert.deepEqual(f.ctx.events.since(0).map(e => e.type), ['report', 'notification']);
});
