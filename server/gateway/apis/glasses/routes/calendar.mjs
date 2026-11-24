import { agenda } from '../lib/calendar.mjs';
export function register(r, ctx) { r.get('/calendar/agenda', ({ query }) => agenda(ctx, query.days)); }
