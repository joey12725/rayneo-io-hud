import { deadline } from './gateway.mjs';
import { call, composioAccount } from './composio.mjs';
import { cached, clamp, localDate, shiftDate, midnight } from './b-upstream.mjs';

export const CALENDAR = {
  calendars: { slug: 'GOOGLECALENDAR_LIST_CALENDARS', args: () => ({ maxResults: 250 }) },
  events: { slug: 'GOOGLECALENDAR_EVENTS_LIST', args: (calendarId, timeMin, timeMax, pageToken) => ({ calendarId, timeMin, timeMax, singleEvents: true, orderBy: 'startTime', maxResults: 2500, ...(pageToken ? { pageToken } : {}) }) },
};
const items = data => data?.items ?? data?.body?.items ?? (Array.isArray(data) ? data : []);
async function run(ctx, budget, key, ...args) { const tool = CALENDAR[key]; return budget.run(() => call(ctx, tool.slug, composioAccount(ctx, 'calendar'), tool.args(...args), { signal: budget.signal })); }
async function calendarEvents(ctx, budget, calendarId, min, max) {
  const all = []; let token;
  for (let page = 0; page < 20 && !budget.expired(); page++) {
    let result;
    try { result = await run(ctx, budget, 'events', calendarId, min, max, token); }
    catch (e) { if (all.length || budget.expired()) break; throw e; }
    all.push(...items(result)); token = result?.nextPageToken ?? result?.next_page_token;
    if (!token) break;
  }
  return { items: all };
}
export function mapEvent(event, tz) {
  const start = event.start ?? {}, end = event.end ?? {};
  const allDay = Boolean(start.date && !start.dateTime && !start.date_time);
  return { id: event.id ?? event.iCalUID ?? '', title: event.summary ?? event.title ?? '(Untitled)',
    start: allDay ? midnight(start.date, tz) : new Date(start.dateTime ?? start.date_time).toISOString(),
    end: end.date ? midnight(end.date, tz) : new Date(end.dateTime ?? end.date_time ?? start.dateTime ?? start.date_time).toISOString(),
    allDay, location: event.location ?? '', notes: event.description ?? '', ...eventDates(start, end, allDay, tz) };
}
// Inclusive local dates. All-day events keep Google's dates verbatim (end is exclusive there), so a
// date-only event never drifts a day when a UTC instant is rendered in another zone.
function eventDates(start, end, allDay, tz) {
  if (allDay) {
    const startDate = start.date, last = end.date ? shiftDate(end.date, -1) : start.date;
    return { startDate, endDate: last < startDate ? startDate : last };
  }
  const s = Date.parse(start.dateTime ?? start.date_time), e = Date.parse(end.dateTime ?? end.date_time ?? start.dateTime ?? start.date_time);
  const startDate = localDate(s, tz), endDate = localDate(e > s ? e - 1 : s, tz);
  return { startDate, endDate };
}
const covers = (e, date, tz) => e.allDay && e.startDate && e.endDate ? e.startDate <= date && date <= e.endDate
  : localDate(e.start, tz) <= date && e.end > midnight(date, tz) && e.start < midnight(shiftDate(date, 1), tz);
export function groupEvents(events, now, days, tz = 'UTC') {
  const today = localDate(now, tz), tomorrow = shiftDate(today, 1);
  return Array.from({ length: days }, (_, i) => {
    const date = shiftDate(today, i);
    const day = date === today ? 'TODAY' : date === tomorrow ? 'TOMORROW' : new Intl.DateTimeFormat('en-US', { timeZone: tz, weekday: 'short', month: 'short', day: 'numeric' }).format(new Date(midnight(date, tz))).replaceAll(',', '').toUpperCase();
    return { day, date, events: events.filter(e => covers(e, date, tz)).sort((a, b) => Number(b.allDay) - Number(a.allDay) || a.start.localeCompare(b.start)) };
  });
}
export function agenda(ctx, requestedDays = 3) {
  const days = Math.round(clamp(requestedDays || 3, 1, 30));
  return cached(ctx, `calendar.${days}`, 30000, async () => {
    const budget = deadline(ctx, ctx.config.paginationBudgetMs ?? 15000);
    try {
      const tz = ctx.config.tz ?? 'UTC', now = new Date(ctx.now()).toISOString();
      const max = new Date(ctx.now() + days * 86400000).toISOString();
      const calendars = items(await run(ctx, budget, 'calendars').catch(e => { if (budget.expired()) return []; throw e; })).filter(c => c.primary || (c.selected !== false && c.hidden !== true)).sort((a, b) => Number(Boolean(b.primary)) - Number(Boolean(a.primary)));
      if (!calendars.length) calendars.push({ id: 'primary' });
      const results = await Promise.allSettled(calendars.map(c => calendarEvents(ctx, budget, c.id, now, max)));
      const seen = new Set(), events = [];
      for (const result of results) {
        if (result.status !== 'fulfilled') continue;
        for (const raw of items(result.value)) {
          if (raw.status === 'cancelled') continue;
          let event; try { event = mapEvent(raw, tz); } catch { continue; }
          const key = `${raw.iCalUID ?? raw.id ?? event.title}|${event.start}`;
          if (!seen.has(key)) { seen.add(key); events.push(event); }
        }
      }
      if (results.length && results.every(r => r.status === 'rejected')) throw results[0].reason;
      // The rolling window includes the partial final calendar day (now + days).
      const firstDay = localDate(ctx.now(), tz), lastDay = localDate(Date.parse(max), tz);
      const dayCount = Math.round((Date.parse(lastDay) - Date.parse(firstDay)) / 86400000) + 1;
      return groupEvents(events.filter(e => e.start < max && e.end > now), ctx.now(), dayCount, tz);
    } finally { budget.close(); }
  });
}
// Home's badge: a timed event in progress or still to come today, else an all-day event covering
// today, else the earliest future event. An all-day event starts at local midnight, so plain
// start order would let it hide the day's real next meeting.
export function pickNextEvent(events, now, tz = 'UTC') {
  const today = localDate(now, tz);
  const live = events.filter(e => Date.parse(e.end) > now).sort((a, b) => a.start.localeCompare(b.start));
  const dates = e => e.startDate ? e : { ...e, ...(e.allDay ? { startDate: localDate(e.start, tz), endDate: localDate(Date.parse(e.end) - 1, tz) } : { startDate: localDate(e.start, tz), endDate: localDate(Math.max(Date.parse(e.start), Date.parse(e.end) - 1), tz) }) };
  const event = live.find(e => !e.allDay && dates(e).startDate <= today) ?? live.find(e => e.allDay && dates(e).startDate <= today && today <= dates(e).endDate) ?? live[0];
  if (!event) return null;
  const { startDate, endDate } = dates(event);
  return { title: event.title, start: event.start, end: event.end, location: event.location, allDay: event.allDay, startDate, endDate };
}
export async function nextEvent(ctx) {
  const seen = new Set(), events = [];
  for (const e of (await agenda(ctx, 3)).flatMap(d => d.events)) { const key = `${e.id}|${e.start}`; if (!seen.has(key)) { seen.add(key); events.push(e); } }
  return pickNextEvent(events, ctx.now(), ctx.config.tz ?? 'UTC');
}
