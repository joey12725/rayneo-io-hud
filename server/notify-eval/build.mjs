#!/usr/bin/env node
// Static site generator: one self-contained HTML page with the datasets and runs embedded at build time.
//
//   node build.mjs [--out private/dist/index.html] [--dataset <file> ...] [--run <file> ...] [--tuning-log <file>] [--v1] [--sample]
// Defaults: every private/datasets-v2/*.json when that folder exists (else private/datasets/*.json; --v1 forces v1), every
// private/runs/*.json, and private/tuning-log.md when present. Files without a `cases` array (datasets-v2/label-summary.json)
// are skipped; label-summary.json is embedded as the vote summary. Each case gets its harness split (dev|test, the same
// assignSplit as run.mjs --split). --sample builds from the committed synthetic fixture only, for tests and demos.
// The build refuses to write a page that contains the gateway token.
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { dirs, ensureDir, ROOT, gatewayConfig } from './lib/config.mjs';
import { validateDataset, validateRun } from './lib/schema.mjs';
import { assignSplit, isDatasetFile } from './run.mjs';

const read = f => JSON.parse(readFileSync(f, 'utf8'));
// Dataset backups (<kind>.pre-v3[-labels].json) are skipped.
const jsonFiles = dir => existsSync(dir) ? readdirSync(dir).filter(isDatasetFile).sort().map(f => path.join(dir, f)) : [];

/** Keeps only what the page needs from a run (drops nothing the detail view shows). */
function slimRun(r) {
  return { runId: r.runId, label: r.label, endpoint: r.endpoint, split: r.split ?? null, datasets: r.datasets ?? null, target: r.target, model: r.model ?? null, gitCommit: r.gitCommit ?? null, repeats: r.repeats,
    popupAt: r.popupAt, startedAt: r.startedAt, finishedAt: r.finishedAt, results: r.results.map(x => ({ id: x.id, attempts: x.attempts })) };
}

export function buildPage({ datasets, runs, builtAt = new Date().toISOString(), secrets = [], tuningLog = null, labelSummary = null }) {
  let cases = [];
  for (const d of datasets) {
    const errors = validateDataset(d);
    if (errors.length) throw new Error('invalid dataset: ' + errors[0]);
    cases.push(...d.cases);
  }
  const split = assignSplit(cases);
  cases = cases.map(c => ({ ...c, split: split.get(c.id) }));
  for (const r of runs) { const errors = validateRun(r); if (errors.length) throw new Error('invalid run: ' + errors[0]); }
  const ids = new Set(cases.map(c => c.id));
  const data = { builtAt, tuningLog: typeof tuningLog === 'string' ? tuningLog : null, labelSummary: labelSummary?.perKind ? { perKind: labelSummary.perKind, total: labelSummary.total ?? null, rule: labelSummary.rule ?? null, labelSource: labelSummary.labelSource ?? null } : null, cases, runs: runs.map(slimRun).map(r => ({ ...r, results: r.results.filter(x => ids.has(x.id)) })).sort((a, b) => a.startedAt.localeCompare(b.startedAt)) };
  const derive = readFileSync(path.join(ROOT, 'lib', 'derive.mjs'), 'utf8').replace(/^export /gm, '');
  const template = readFileSync(path.join(ROOT, 'site', 'index.html'), 'utf8');
  // JSON inside <script type="application/json">: escape "<" so no message text can close the element.
  const BS = String.fromCharCode(92), LS = String.fromCharCode(0x2028), PS = String.fromCharCode(0x2029);
  const embedded = JSON.stringify(data).split('<').join(BS + 'u003c').split(LS).join(BS + 'u2028').split(PS).join(BS + 'u2029');
  const html = template
    .replace('/*STYLE*/', () => readFileSync(path.join(ROOT, 'site', 'style.css'), 'utf8'))
    .replace('/*DATA*/', () => embedded)
    .replace('/*DERIVE*/', () => derive)
    .replace('/*APP*/', () => readFileSync(path.join(ROOT, 'site', 'app.js'), 'utf8'));
  for (const s of secrets) if (s && s.length >= 8 && html.includes(s)) throw new Error('refusing to build: the page would contain a secret');
  return { html, cases: cases.length, runs: data.runs.length };
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  const argv = process.argv.slice(2);
  const many = name => argv.flatMap((v, i) => argv[i - 1] === '--' + name ? [v] : []);
  const sample = argv.includes('--sample');
  const datasetDir = !argv.includes('--v1') && existsSync(dirs.datasetsV2) ? dirs.datasetsV2 : dirs.datasets;
  const datasetFiles = many('dataset').length ? many('dataset') : sample ? [path.join(ROOT, 'fixtures', 'sample-dataset.json')] : jsonFiles(datasetDir);
  const loaded = datasetFiles.map(f => [f, read(f)]);
  const datasets = loaded.filter(([, d]) => Array.isArray(d?.cases)).map(([, d]) => d);
  const summaryOf = name => loaded.find(([f, d]) => !Array.isArray(d?.cases) && path.basename(f) === name)?.[1] ?? null;
  const labelSummary = summaryOf('label-summary-v3.json') ?? summaryOf('label-summary.json');
  const logFile = many('tuning-log')[0] ?? (sample ? null : path.join(path.dirname(dirs.runs), 'tuning-log.md'));
  const tuningLog = logFile && existsSync(logFile) ? readFileSync(logFile, 'utf8') : null;
  const runFiles = many('run').length ? many('run') : sample ? [path.join(ROOT, 'fixtures', 'sample-run.json')] : jsonFiles(dirs.runs);
  const out = argv[argv.indexOf('--out') + 1] && argv.includes('--out') ? argv[argv.indexOf('--out') + 1] : path.join(sample ? path.join(ROOT, 'dist') : dirs.dist, 'index.html');
  const { token } = (() => { try { return gatewayConfig(); } catch { return {}; } })();
  const { html, cases, runs } = buildPage({ datasets, runs: runFiles.map(read), tuningLog, labelSummary, secrets: [token, process.env.LLM_API_KEY] });
  ensureDir(path.dirname(out));
  writeFileSync(out, html);
  console.log(`wrote ${path.relative(process.cwd(), out)}: ${cases} cases, ${runs} runs, ${(html.length / 1024).toFixed(0)} KB`);
}
