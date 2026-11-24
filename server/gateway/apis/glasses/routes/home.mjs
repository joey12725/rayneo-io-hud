import { unreadSummary } from '../lib/beeper.mjs';
import { needYouCounts } from '../lib/t3.mjs';

async function optional(file, name, ctx) {
  try { return await (await import(file))[name](ctx); } catch { return null; }
}
export function register(r, ctx) {
  r.get('/home', async () => {
    const defaults = { weather: null, beeper: { unread: 0, names: [] }, t3: { needYou: 0, done: 0 }, nextEvent: null, nowPlaying: null };
    const tasks = {
      weather: () => optional('../lib/weather.mjs', 'getWeather', ctx), beeper: () => unreadSummary(ctx), t3: () => needYouCounts(ctx),
      nextEvent: () => optional('../lib/calendar.mjs', 'nextEvent', ctx), nowPlaying: () => optional('../lib/spotify.mjs', 'nowPlaying', ctx),
    };
    // A common deadline preserves every part that completes within the six-second budget.
    let timer;
    const deadline = new Promise(resolve => { timer = setTimeout(resolve, 6000); });
    await Promise.all(Object.entries(tasks).map(async ([key, fn]) => {
      const value = await Promise.race([Promise.resolve().then(fn).catch(() => defaults[key]), deadline.then(() => defaults[key])]);
      defaults[key] = value;
    }));
    clearTimeout(timer); return defaults;
  });
}
