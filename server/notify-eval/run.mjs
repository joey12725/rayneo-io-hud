#!/usr/bin/env node
// Harness: runs every case through the classifier N times and writes private/runs/<runId>.json.
//
// Targets (pick one):
//   default            the gateway at env NOTIFY_EVAL_GATEWAY_URL (…/glasses/v1) with NOTIFY_EVAL_GATEWAY_TOKEN
//                      (or GATEWAY_URL / GATEWAY_TOKEN from android/secrets.properties)
//   --base-url <url>   another gateway (…/glasses/v1); token from env NOTIFY_EVAL_GATEWAY_TOKEN
//   --local            an in-process gateway built from server/gateway in this checkout (no deploy needed); its model
//                      calls go to --proxy <url> (any OpenAI-compatible base URL; key in env LLM_API_KEY)
// Datasets: private/datasets-v2/*.json when present (v2: conversation context), else private/datasets/*.json; --v1 forces
//          the v1 datasets, --dataset <file> picks one file. NOTIFY_EVAL_PRIVATE_DIR points at another private/ (e.g. the main checkout's).
// Endpoints:
//   --endpoint route     the production routes with debug:true (default): v2 beeper/t3/call cases -> POST /notify/route with
//                        their `context` (debug lets an eval supply what production builds itself), v2 hermes cases ->
//                        POST /notify/hermes (agent token) in the glasses-notify plugin's body shape; v1 cases -> input only
//   --endpoint classify  POST /notify/classify with the old field set (baseline); scored as notify>=popupAt -> popup
// Options: --repeats 3  --concurrency 4  --kind beeper|t3|call|hermes  --only id,id  --limit N  --label "text"  --popup-at 0.75
//          --split dev|test   deterministic 70/30 split, stratified by kind and expected label, ordered by a hash of the id
//          --timeout 20000 (ms per request)  --dataset <file>
//          --fill <run file>  run only the cases missing from that run and add them to it (after re-collecting)
// Classification requests store nothing on the gateway; this tool never calls any other route.
import { readFileSync, writeFileSync, readdirSync, existsSync, mkdtempSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';
import { randomBytes } from 'node:crypto';
import { dirs, ensureDir, args, gatewayConfig, gateway, ROOT, REPO } from './lib/config.mjs';
import { createHash } from 'node:crypto';
import { classifyBody, metrics, DEFAULT_POPUP_AT, outcome, contractProblems, COST_LABEL } from './lib/derive.mjs';
import { validateDataset } from './lib/schema.mjs';

/** A file in a datasets folder: JSON and not a backup written by the round-2 tools (<kind>.pre-v3[-labels].json). */
export const isDatasetFile = (f) => f.endsWith('.json') && !/\.pre-[\w-]+\.json$/.test(f);
export function datasetFiles({ dataset, v1 = false } = {}) {
  if (dataset) return [dataset];
  const dir = !v1 && existsSync(dirs.datasetsV2) ? dirs.datasetsV2 : dirs.datasets;
  return existsSync(dir) ? readdirSync(dir).filter(isDatasetFile).sort().map(f => path.join(dir, f)) : [];
}

/** Cases from the datasets, filtered. `mixed`: round-robin over kinds (in hash order), so `limit` takes a mix. */
export function loadCases({ dataset, kind, only, limit, v1, split, mixed } = {}) {
  let cases = [];
  for (const f of datasetFiles({ dataset, v1 })) {
    const d = JSON.parse(readFileSync(f, 'utf8'));
    if (!dataset && !Array.isArray(d?.cases)) continue;   // e.g. datasets-v2/label-summary.json
    const errors = validateDataset(d);
    if (errors.length) throw new Error(`${path.basename(f)}: ${errors[0]}`);
    cases.push(...d.cases);
  }
  if (kind) cases = cases.filter(c => c.kind === kind);
  if (split) cases = splitCases(cases, split);
  if (only) { const ids = new Set(only.split(',')); cases = cases.filter(c => ids.has(c.id)); }
  if (mixed) cases = interleaveKinds(cases);
  if (limit) cases = cases.slice(0, Number(limit));
  return cases;
}

// ---- dev/test split ----
export const DEV_SHARE = 0.7;
const splitHash = (id) => createHash('sha256').update('eval-split\u0000' + id).digest('hex');
/**
 * {id -> 'dev'|'test'}: within each stratum (kind, expected label) cases are ordered by a hash of their id and the first
 * round(70%) are dev, the rest test. Deterministic and independent of file order. Exact quotas mean the split is only
 * stable for a fixed case set: adding or relabelling cases in a stratum can move a case at that stratum's 70% boundary
 * between dev and test (at most one per added case), so compare test scores only between runs on the same datasets.
 */
export function assignSplit(cases, devShare = DEV_SHARE) {
  const strata = new Map(), out = new Map();
  for (const c of cases) { const k = `${c.kind}|${c.expected}`; if (!strata.has(k)) strata.set(k, []); strata.get(k).push(c); }
  for (const list of strata.values()) {
    const sorted = [...list].sort((a, b) => splitHash(a.id).localeCompare(splitHash(b.id)));
    const dev = Math.round(sorted.length * devShare);
    sorted.forEach((c, i) => out.set(c.id, i < dev ? 'dev' : 'test'));
  }
  return out;
}
export function splitCases(cases, which) {
  if (!['dev', 'test'].includes(which)) throw new Error('--split must be dev or test');
  const s = assignSplit(cases);
  return cases.filter(c => s.get(c.id) === which);
}
/** Round-robin over kinds, each kind in hash order (a deterministic mixed sample for --limit). */
export function interleaveKinds(cases) {
  const by = new Map();
  for (const c of [...cases].sort((a, b) => splitHash(a.id).localeCompare(splitHash(b.id)))) { if (!by.has(c.kind)) by.set(c.kind, []); by.get(c.kind).push(c); }
  const lists = [...by.keys()].sort().map(k => by.get(k)), out = [];
  for (let i = 0; out.length < cases.length; i++) for (const l of lists) if (i < l.length) out.push(l[i]);
  return out;
}

// ---- production-shaped requests ----
const turnsOf = (turns, label = (t) => t.name || t.speaker) => (turns ?? []).map(t => ({ speaker: label(t), isOwner: t.isOwner === true,
  messages: (t.messages ?? []).map(m => ({ ...(m.id !== undefined ? { id: String(m.id) } : {}), at: m.at, text: m.text, ...(m.priorRoute ? { priorRoute: m.priorRoute } : {}) })) }));
const isV2 = (c) => !!c && typeof c === 'object' && !!c.input && Array.isArray(c.context?.turns);

/** The body the glasses-notify Hermes plugin sends for this exchange (server/hermes-plugins/glasses-notify), plus debug. */
export function hermesBody(c) {
  const x = c.context, who = x.inbound?.sender ?? (x.prompt?.isOwner ? 'owner' : 'other');
  const sender = who === 'owner' ? 'owner' : ['scheduler', 'cron'].includes(who) ? 'scheduler' : who === 'system' ? 'system' : 'other';
  const platform = String(x.platform ?? String(c.input.source ?? '').replace(/^hermes:/, '') ?? 'discord') || 'discord';
  const delivered = platform === 'cron' && x.job ? String(x.job.deliver ?? 'local') !== 'local' : undefined;
  return { platform, chat: x.channel ?? c.input.title ?? '', chatId: c.input.channelId ?? '', isDM: x.isDM === true, sender, ...(sender === 'other' ? { senderName: String(who) } : {}),
    inboundKind: x.inbound?.kind ?? (platform === 'cron' ? 'cron-prompt' : 'request'), ...(x.job?.name ? { job: x.job.name } : {}), ...(delivered !== undefined ? { delivered } : {}),
    prompt: { at: x.prompt.at, text: x.prompt.text ?? '' }, response: { at: x.response.at, text: x.response.text ?? '' },
    turns: turnsOf(x.turns, t => t.isOwner ? 'owner' : t.speaker === 'assistant' || t.speaker === 'joy' ? 'Hermes' : t.speaker), sessionId: x.sessionId ?? '', turnId: c.id, debug: true };
}

/** {path, body, token: 'phone'|'agent'} for one case (or a bare v1 input) on one endpoint. */
export function requestFor(item, endpoint = 'route') {
  const c = item && item.input && item.kind ? item : null, input = c ? c.input : item;
  if (endpoint === 'classify') return { path: '/notify/classify', body: classifyBody(input), token: 'phone' };
  // T3 cases carry no real thread, so the gateway's "thread not watched" rule would suppress every one of them:
  // mark them watched (honoured only together with debug) so the run measures the T3 content rules and the classifier.
  const base = { ...input, debug: true, ...(input.kind === 't3' ? { watched: true } : {}) };
  if (!isV2(c)) return { path: '/notify/route', body: base, token: 'phone' };
  const x = c.context;
  if (c.kind === 'hermes') return { path: '/notify/hermes', body: hermesBody(c), token: 'agent' };
  if (c.kind === 'beeper') return { path: '/notify/route', token: 'phone', body: { ...base, context: { ...(x.target ? { target: { id: x.target.id, at: x.target.at, sender: x.target.sender, text: x.target.text } } : {}), turns: turnsOf(x.turns),
    // Relationship stats as of the message: what production computes from Beeper history.
    ...(x.relationship ? { relationship: x.relationship } : {}) } } };
  if (c.kind === 't3') return { path: '/notify/route', token: 'phone', body: { ...base, context: { threadTitle: x.threadTitle ?? input.title, project: x.project ?? input.conversationTitle, status: x.run?.status ?? x.status ?? input.category,
    prompt: { at: x.prompt?.at, text: x.prompt?.text ?? '', ...(x.prompt?.createdBy === 'agent' || x.prompt?.fromParentAgent || x.prompt?.isOwner === false ? { agentWritten: true } : {}) },
    response: { at: x.response?.at, text: x.response?.text ?? '' }, turns: turnsOf(x.turns) } } };
  return { path: '/notify/route', body: base, token: 'phone' };
}

/** One classification attempt, normalised. `item` is a case or a bare input; `post(path, body, token)` returns {status, json, text, ms}. */
export async function attempt(post, endpoint, item) {
  const { path: p, body, token } = requestFor(item, endpoint);
  try {
    const r = token === 'agent' ? await post(p, body, token) : await post(p, body);
    const j = r.json;
    if (r.status !== 200 || !j || typeof j.route !== 'string') return { status: r.status, ms: r.ms, error: j?.error ?? r.text ?? 'no route in response' };
    const out = { status: r.status, ms: r.ms, route: j.route, importance: typeof j.importance === 'number' ? j.importance : null };
    for (const k of ['reason', 'by', 'open', 'debug']) if (j[k] !== undefined) out[k] = j[k];
    const extra = Object.keys(j).filter(k => !['route', 'importance', 'reason', 'by', 'open', 'debug'].includes(k));
    if (extra.length) out.extra = Object.fromEntries(extra.map(k => [k, j[k]]));
    if (endpoint !== 'classify') { const problems = contractProblems(j); if (problems.length) out.contract = problems; }
    return out;
  } catch (e) { return { status: 0, ms: null, error: String(e.message ?? e).slice(0, 200) }; }
}

export async function runAll(cases, { post, endpoint = 'route', repeats = 3, concurrency = 4, onProgress = () => {} } = {}) {
  const jobs = cases.flatMap(c => Array.from({ length: repeats }, (_, i) => ({ c, i })));
  const results = new Map(cases.map(c => [c.id, { id: c.id, attempts: [] }]));
  let next = 0, done = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, jobs.length) }, async () => {
    while (next < jobs.length) {
      const { c, i } = jobs[next++];
      let a = await attempt(post, endpoint, c);
      if (a.status === 0) a = { ...(await attempt(post, endpoint, c)), retried: true };   // network errors only
      results.get(c.id).attempts[i] = a;
      onProgress(++done, jobs.length);
    }
  }));
  return [...results.values()];
}

async function localGateway(a) {
  const proxyUrl = a.opt('proxy');
  if (!proxyUrl) throw new Error('--local needs --proxy <OpenAI-compatible base URL>');
  // Give the classifier more time than production's 4 s (--classifier-timeout ms).
  return startLocalGateway({ proxyUrl, model: a.opt('model'), classifierTimeoutMs: Number(a.opt('classifier-timeout', 15000)), fetch: globalThis.fetch });
}

/** In-process gateway (glasses module only) from this checkout, listening on 127.0.0.1 with a random token. */
export async function startLocalGateway({ proxyUrl, model, fetch: fetchImpl, classifierTimeoutMs }) {
  const { createApp } = await import(pathToFileURL(path.join(ROOT, '..', 'gateway', 'server.mjs')).href);
  const token = randomBytes(16).toString('hex'), agentToken = randomBytes(16).toString('hex');
  const dataDir = mkdtempSync(path.join(os.tmpdir(), 'notify-eval-gw-'));
  const app = await createApp({
    env: { GATEWAY_DATA: dataDir, GATEWAY_MODULES: 'glasses', LLM_BASE_URL: proxyUrl, GATEWAY_OWNER_NAME: process.env.GATEWAY_OWNER_NAME || 'the owner', ...(model ? { MODEL_FAST: model, MODEL_CLASSIFIER: model } : {}), ...(classifierTimeoutMs ? { NOTIFY_TIMEOUT_MS: String(classifierTimeoutMs) } : {}) },
    log: () => {},
    secrets: { get: async name => {
      if (name === 'GLASSES_API_TOKEN') return token;
      if (name === 'GLASSES_AGENT_TOKEN') return agentToken;
      if (name.startsWith('LLM_API_KEY')) return process.env.LLM_API_KEY || null;
      throw Object.assign(new Error('secret not available locally'), { code: 'SECRET_NOT_FOUND' });
    } },
    deps: { glasses: { fetch: fetchImpl, config: { t3Machines: [], beeperLive: false, beeperSpam: false } } },
  });
  app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
  const url = `http://127.0.0.1:${app.server.address().port}/glasses/v1`;
  return { cfg: { url, token, agentToken }, close: async () => { app.server.closeAllConnections(); await new Promise(r => app.server.close(r)); try { await app.store.flushed(); } catch {} rmSync(dataDir, { recursive: true, force: true }); } };
}

function gitCommit() { try { return execFileSync('git', ['-C', REPO, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).trim(); } catch { return null; } }

export function summaryLines(cases, run, popupAt) {
  const m = metrics(cases, run, { popupAt });
  const pct = x => x === null ? '—' : (100 * x).toFixed(1) + '%';
  const lines = [];
  for (const [k, g] of [['all', m.all], ...Object.entries(m.byKind)]) {
    lines.push(`${k.padEnd(7)} n=${g.n} acc=${pct(g.accuracy)} (clear ${g.scored}) accAll=${pct(g.accuracyAll)} macroF1=${g.macroF1 === null ? '—' : g.macroF1.toFixed(3)} popup P=${pct(g.popup.precision)} R=${pct(g.popup.recall)} cost=${g.cost.total.toFixed(1)} (${g.cost.perCase === null ? '—' : g.cost.perCase.toFixed(3)}/case) flaky=${g.flaky} errors=${g.errors} contract=${g.contract} p50=${g.latency.p50 ?? '—'}ms p95=${g.latency.p95 ?? '—'}ms by=${JSON.stringify(g.byRoute)}`);
  }
  lines.push('cost (clear cases; expected->actual): ' + COST_LABEL);
  lines.push('confusion (rows expected, cols actual: suppress stream popup error)');
  for (const e of ['suppress', 'stream', 'popup']) lines.push(`  ${e.padEnd(9)} ${['suppress', 'stream', 'popup', 'error'].map(x => String(m.all.matrix[e][x]).padStart(5)).join('')}`);
  return lines;
}

async function main() {
  const a = args();
  const endpoint = a.opt('endpoint', 'route');
  if (!['route', 'classify'].includes(endpoint)) throw new Error('--endpoint must be route or classify');
  const split = a.opt('split');
  const allCases = loadCases({ dataset: a.opt('dataset'), kind: a.opt('kind'), only: a.opt('only'), limit: a.opt('limit'), v1: a.flag('v1'), split, mixed: a.flag('mixed') });
  // --fill <run file>: run only the cases that run lacks (e.g. after re-collecting) and add them to it.
  const fill = a.opt('fill') ? JSON.parse(readFileSync(a.opt('fill'), 'utf8')) : null;
  if (fill && fill.endpoint !== endpoint) throw new Error('--fill run uses /notify/' + fill.endpoint);
  const have = new Set((fill?.results ?? []).map(r => r.id));
  const cases = fill ? allCases.filter(c => !have.has(c.id)) : allCases;
  if (fill && !cases.length) { console.log('nothing to fill'); return; }
  if (!cases.length) throw new Error('No cases: put datasets in private/datasets-v2 (see README)');
  const local = a.flag('local') ? await localGateway(a) : null;
  const cfg = local ? local.cfg : a.opt('base-url') ? { url: a.opt('base-url').replace(/\/$/, ''), token: process.env.NOTIFY_EVAL_GATEWAY_TOKEN ?? '', agentToken: process.env.NOTIFY_EVAL_AGENT_TOKEN ?? '' } : gatewayConfig();
  const g = gateway(cfg);
  const repeats = Number(a.opt('repeats', fill?.repeats ?? 3)), popupAt = Number(a.opt('popup-at', DEFAULT_POPUP_AT));
  const startedAt = new Date().toISOString();
  const label = a.opt('label', endpoint === 'classify' ? 'baseline-classify' : 'route');
  const runId = startedAt.replace(/[:.]/g, '-').replace(/-\d{3}Z$/, 'Z') + '-' + label.replace(/[^\w.-]+/g, '-');
  console.log(`${cases.length} cases x ${repeats} -> ${local ? 'local gateway' : new URL(g.url).host} /notify/${endpoint}`);
  let results;
  try {
    results = await runAll(cases, { post: (p, b, token) => g.post(p, b, { timeoutMs: Number(a.opt('timeout', local ? 90000 : 20000)), token }), endpoint, repeats, concurrency: Number(a.opt('concurrency', 4)),
      onProgress: (d, n) => { if (d % 10 === 0 || d === n) process.stdout.write(`\r${d}/${n}`); } });
  } finally { await local?.close(); }
  const run = { schema: 1, runId, label, endpoint, split: split ?? null, datasets: a.opt('dataset') ? 'file' : a.flag('v1') || !existsSync(dirs.datasetsV2) ? 'v1' : 'v2', target: local ? 'local:proxy' : new URL(g.url).host, model: a.opt('model') ?? null,
    gitCommit: gitCommit(), repeats, popupAt, startedAt, finishedAt: new Date().toISOString(), cases: cases.length, results };
  for (const r of run.results) { const o = outcome(r, endpoint, popupAt); r.actual = o.actual; r.flaky = o.flaky; }
  ensureDir(dirs.runs);
  let file = path.join(dirs.runs, runId + '.json'), out = run;
  if (fill) { file = a.opt('fill'); out = { ...fill, results: [...fill.results, ...run.results], cases: fill.results.length + run.results.length, filledAt: run.finishedAt }; }
  writeFileSync(file, JSON.stringify(out, null, 1));
  console.log('\n' + summaryLines(fill ? allCases : cases, out, fill?.popupAt ?? popupAt).join('\n'));
  console.log('wrote ' + path.relative(process.cwd(), file));
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) await main();
