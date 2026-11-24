import { cached, json } from './b-upstream.mjs';

export function describeWeather(code, night = false) {
  if (code === 0) return { text: night ? 'clear tonight' : 'sunny', icon: night ? 'moon' : 'sun' };
  if (code <= 3) return { text: 'cloudy', icon: 'cloud' };
  if (code <= 48) return { text: 'foggy', icon: 'cloud' };
  if (code >= 95) return { text: 'thunderstorms', icon: 'warn' };
  if ([71, 73, 75, 77, 85, 86].includes(code)) return { text: 'snow', icon: 'cloud' };
  return { text: 'rain', icon: 'cloud' };
}
export function getWeather(ctx) {
  return cached(ctx, 'weather', 600000, async () => {
    if (ctx.config.homeLat == null || ctx.config.homeLon == null) throw new Error('Weather unavailable: set home.lat and home.lon');
    const url = new URL('https://api.open-meteo.com/v1/forecast');
    url.search = new URLSearchParams({ latitude: ctx.config.homeLat, longitude: ctx.config.homeLon, hourly: 'temperature_2m,weather_code,is_day', temperature_unit: ctx.config.units === 'metric' ? 'celsius' : 'fahrenheit', timezone: 'UTC', forecast_days: '2' });
    const data = await json(ctx, url), hourly = data.hourly;
    const index = hourly.time.findLastIndex(t => Date.parse(t.endsWith('Z') ? t : t + 'Z') <= ctx.now());
    const i = Math.max(0, index), temperature = hourly.temperature_2m[i];
    if (typeof temperature !== 'number' || !Number.isFinite(temperature)) throw new Error('Weather unavailable');
    const temp = Math.round(temperature), unit = ctx.config.units === 'metric' ? 'C' : 'F';
    const current = describeWeather(hourly.weather_code[i], hourly.is_day?.[i] === 0);
    const rain = hourly.weather_code.findIndex((c, n) => n > i && n <= i + 8 && c >= 51);
    let condition = current.text;
    if (rain > i && hourly.weather_code[i] < 51) {
      const time = new Intl.DateTimeFormat('en-US', { timeZone: ctx.config.tz, hour: 'numeric' }).format(new Date(hourly.time[rain] + (hourly.time[rain].endsWith('Z') ? '' : 'Z')));
      condition = `${describeWeather(hourly.weather_code[rain]).text} after ${time}`;
    }
    // tempF stays for older phone builds; temp + unit follow config.units.
    return { temp, unit, tempF: unit === 'F' ? temp : Math.round(temp * 9 / 5 + 32), condition: condition.slice(0, 24), icon: current.icon };
  });
}
