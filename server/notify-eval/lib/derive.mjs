// Scoring shared by the harness (node) and the site (inlined into the page by build.mjs, `export ` stripped).
// Keep this file free of imports and node APIs.

export const ROUTE3 = ['suppress', 'stream', 'popup'];
export const DEFAULT_POPUP_AT = 0.75;
/**
 * The owner's error costs (expected -> actual): hiding something that needed them now is the worst
 * mistake (5); hiding something worth reading (stream -> suppress, 4) is the most noticed error; then noise popping
 * up (3), a list item popping up (2), and a popup or noise landing in the list (1.5). A correct answer costs 0; an error
 * (no usable answer) costs as if nothing was shown (-> suppress).
 */
export const COST = {
  popup: { popup: 0, stream: 1.5, suppress: 5 },
  stream: { popup: 2, stream: 0, suppress: 4 },
  suppress: { popup: 3, stream: 1.5, suppress: 0 },
};
/** One line describing COST, for the harness summary and the site. */
export const COST_LABEL = 'popup→suppress 5, stream→suppress 4, suppress→popup 3, stream→popup 2, popup→stream 1.5, suppress→stream 1.5';
export const costOf = (expected, actual) => COST[expected]?.[actual === 'error' ? 'suppress' : actual] ?? 0;

/** Macro-F1 over the three routes from a confusion matrix {expected: {actual: n}}; routes absent from both sides are skipped. */
export function macroF1(matrix) {
  const f1s = [];
  for (const r of ROUTE3) {
    const tp = matrix[r]?.[r] ?? 0;
    const fn = ROUTE3.concat('error').reduce((s, a) => s + (a === r ? 0 : matrix[r]?.[a] ?? 0), 0);
    const fp = ROUTE3.reduce((s, e) => s + (e === r ? 0 : matrix[e]?.[r] ?? 0), 0);
    if (tp + fn + fp === 0) continue;
    f1s.push(tp ? (2 * tp) / (2 * tp + fp + fn) : 0);
  }
  return f1s.length ? f1s.reduce((a, b) => a + b, 0) / f1s.length : null;
}

/**
 * Three-way route of one attempt. /notify/route answers suppress|stream|popup directly. The old binary
 * /notify/classify answers notify|suppress + importance: notify at or above `popupAt` counts as popup, else stream.
 * Errors (no usable answer) return 'error'.
 */
export function actual3(attempt, endpoint, popupAt = DEFAULT_POPUP_AT) {
  if (!attempt || attempt.error || !attempt.route) return 'error';
  // /notify/route must answer one of the three routes; a binary 'notify' there is a contract break, not a stream/popup.
  if (endpoint !== 'classify') return ROUTE3.includes(attempt.route) ? attempt.route : 'error';
  if (attempt.route === 'suppress') return 'suppress';
  if (attempt.route === 'notify') return typeof attempt.importance === 'number' && attempt.importance >= popupAt ? 'popup' : 'stream';
  return ROUTE3.includes(attempt.route) ? attempt.route : 'error';
}

export const DECIDED_BY = ['rule', 'model', 'fallback'];

/**
 * Problems with one /notify/route answer against the shared routing contract
 * ({route, importance 0..1, reason, by: rule|model|fallback, open?: {app, target?}}). Empty when it conforms.
 */
export function contractProblems(j) {
  const p = [];
  if (!j || typeof j !== 'object') return ['answer is not an object'];
  if (!ROUTE3.includes(j.route)) p.push('route ' + JSON.stringify(j.route) + ' not suppress|stream|popup');
  if (typeof j.importance !== 'number' || !(j.importance >= 0 && j.importance <= 1)) p.push('importance must be a number 0..1');
  if (typeof j.reason !== 'string' || !j.reason.trim()) p.push('reason missing');
  if (!DECIDED_BY.includes(j.by)) p.push('by ' + JSON.stringify(j.by) + ' not rule|model|fallback');
  if (j.open !== undefined && (j.open === null || typeof j.open !== 'object' || typeof j.open.app !== 'string' || (j.open.target !== undefined && typeof j.open.target !== 'string'))) p.push('open must be {app, target?}');
  return p;
}

/** Majority over attempts (ties go to the most interruptive route among the tied, then 'error' last). */
export function majority(routes) {
  const n = {};
  for (const r of routes) n[r] = (n[r] || 0) + 1;
  const order = ['popup', 'stream', 'suppress', 'error'];
  let best = null;
  for (const r of order) if (n[r] && (best === null || n[r] > n[best])) best = r;
  return best ?? 'error';
}

/** Per-case outcome: {actual, flaky, routes, agreement} where agreement is the share of attempts matching the majority. */
export function outcome(result, endpoint, popupAt = DEFAULT_POPUP_AT) {
  const routes = (result?.attempts ?? []).map(a => actual3(a, endpoint, popupAt));
  const actual = routes.length ? majority(routes) : 'error';
  const same = routes.filter(r => r === actual).length;
  return { actual, routes, flaky: new Set(routes).size > 1, agreement: routes.length ? same / routes.length : 0 };
}

export function percentile(values, p) {
  const v = values.filter(x => typeof x === 'number' && Number.isFinite(x)).sort((a, b) => a - b);
  if (!v.length) return null;
  return v[Math.min(v.length - 1, Math.max(0, Math.ceil(p / 100 * v.length) - 1))];
}

/**
 * Metrics over cases joined with a run. `labels` optionally overrides {id: {expected, ambiguous}} (browser corrections).
 * Returns {all, byKind: {beeper, t3, call}}, each {n, scored, correct, accuracy, accuracyAll, matrix, popup: {precision, recall, tp, fp, fn},
 * flaky, errors, contract (attempts breaking the /notify/route contract), ambiguous, latency: {p50, p95}, byRoute: {by -> count}}.
 */
export function metrics(cases, run, { popupAt = DEFAULT_POPUP_AT, labels = {} } = {}) {
  const results = new Map((run?.results ?? []).map(r => [r.id, r]));
  const blank = () => ({ n: 0, scored: 0, correct: 0, correctAll: 0, matrix: Object.fromEntries(ROUTE3.map(e => [e, { suppress: 0, stream: 0, popup: 0, error: 0 }])),
    tp: 0, fp: 0, fn: 0, flaky: 0, errors: 0, contract: 0, ambiguous: 0, ms: [], by: {}, cost: 0, costN: 0,
    clearMatrix: Object.fromEntries(ROUTE3.map(e => [e, { suppress: 0, stream: 0, popup: 0, error: 0 }])) });
  const groups = { all: blank() };
  for (const c of cases) {
    const r = results.get(c.id);
    if (!r) continue;
    const label = labels[c.id] ?? {};
    const expected = label.expected ?? c.expected, ambiguous = label.ambiguous ?? c.ambiguous;
    const o = outcome(r, run.endpoint, popupAt);
    for (const g of [groups.all, groups[c.kind] ??= blank()]) {
      g.n++;
      g.matrix[expected][o.actual]++;
      if (o.actual === expected) g.correctAll++;
      if (ambiguous) g.ambiguous++;
      else { g.scored++; if (o.actual === expected) g.correct++; g.clearMatrix[expected][o.actual]++; g.cost += costOf(expected, o.actual); g.costN++; }
      if (o.actual === 'popup' && expected === 'popup') g.tp++;
      else if (o.actual === 'popup') g.fp++;
      else if (expected === 'popup') g.fn++;
      if (o.flaky) g.flaky++;
      if (o.actual === 'error') g.errors++;
      for (const a of r.attempts ?? []) { if (typeof a.ms === 'number') g.ms.push(a.ms); if (a.contract?.length) g.contract++; const by = a.by ?? (a.error ? 'error' : 'n/a'); g.by[by] = (g.by[by] || 0) + 1; }
    }
  }
  const finish = g => ({ n: g.n, scored: g.scored, correct: g.correct, accuracy: g.scored ? g.correct / g.scored : null, accuracyAll: g.n ? g.correctAll / g.n : null,
    matrix: g.matrix, popup: { tp: g.tp, fp: g.fp, fn: g.fn, precision: g.tp + g.fp ? g.tp / (g.tp + g.fp) : null, recall: g.tp + g.fn ? g.tp / (g.tp + g.fn) : null },
    flaky: g.flaky, errors: g.errors, contract: g.contract, ambiguous: g.ambiguous, latency: { p50: percentile(g.ms, 50), p95: percentile(g.ms, 95) }, byRoute: g.by,
    // Clear (non-ambiguous) cases only: macro-F1 over the three routes and the owner's weighted cost (total and per case).
    macroF1: macroF1(g.clearMatrix), cost: { total: g.cost, perCase: g.costN ? g.cost / g.costN : null, n: g.costN }, clearMatrix: g.clearMatrix });
  const byKind = {};
  for (const k of Object.keys(groups)) if (k !== 'all') byKind[k] = finish(groups[k]);
  return { all: finish(groups.all), byKind };
}

/** Body sent to the old /notify/classify for a /notify/route input (baseline mapping). */
export function classifyBody(input) {
  const out = {};
  for (const k of ['source', 'app', 'title', 'body', 'conversationTitle', 'sender', 'network']) if (typeof input[k] === 'string') out[k] = input[k];
  if (typeof input.isGroup === 'boolean') out.isGroupConversation = input.isGroup;
  return out;
}
