#!/usr/bin/env node
// Dictation cleanup evals against the real cleanup model. Not part of `node --test` (it calls a live model).
//
// It builds each request with the gateway's own lib/cleanup.mjs (same prompt, delimiter, few-shot turns,
// participant and vocabulary blocks), sends one stateless chat completion, then applies the gateway's
// post-processing (markers, mentions, T3 repairs, output check) before checking the case.
//
// Transports (pick one):
//   --proxy <url>        OpenAI-compatible base URL (e.g. https://api.openai.com/v1); key from env LLM_API_KEY.
//   --gateway <url>      POST <url>/voice/cleanup (after deploy); bearer from env GLASSES_API_TOKEN. Cases with
//                        fixture participants are skipped (the gateway loads real chats).
// Options: --runs N (default 3), --model ID (default $MODEL_CLEANUP or gpt-4o-mini), --only name[,name], --concurrency N (4), --verbose
import { readFileSync } from 'node:fs';
import { buildCleanupMessages, finish, mergeVocab, personName } from '../apis/glasses/lib/cleanup.mjs';

const args = process.argv.slice(2);
const opt = (name, fallback) => { const i = args.indexOf('--' + name); return i >= 0 ? args[i + 1] : fallback; };
const flag = (name) => args.includes('--' + name);
const runs = Number(opt('runs', 3)), model = opt('model', process.env.MODEL_CLEANUP || 'gpt-4o-mini'), concurrency = Number(opt('concurrency', 4));
const only = opt('only') ? new Set(opt('only').split(',')) : null;
const evals = JSON.parse(readFileSync(new URL('../apis/glasses/prompts/cleanup-evals.json', import.meta.url), 'utf8'));
const vocab = mergeVocab(null);

async function complete(messages) {
  const body = JSON.stringify({ model, messages });
  let raw;
  if (opt('proxy')) {
    const r = await fetch(opt('proxy').replace(/\/$/, '') + '/chat/completions', { method: 'POST', body, headers: { 'Content-Type': 'application/json', ...(process.env.LLM_API_KEY ? { Authorization: 'Bearer ' + process.env.LLM_API_KEY } : {}) } });
    raw = await r.text();
  } else throw new Error('Pick a transport: --proxy url or --gateway url');
  const choice = JSON.parse(raw)?.choices?.[0];
  if (!choice) throw new Error('no choice: ' + raw.slice(0, 200));
  // A null content is an empty answer; the gateway then keeps the raw transcript (chat() throws, cleanup falls back).
  return typeof choice.message?.content === 'string' ? choice.message.content.trim() : '';
}
const peopleFor = (c) => (evals.people[c.people] ?? []).map(p => ({ ...p, name: personName(p.name), ...(p.username ? { username: personName(p.username) } : {}) })).filter(p => p.name);

async function runCase(c) {
  if (opt('gateway')) {
    const r = await fetch(opt('gateway').replace(/\/$/, '') + '/voice/cleanup', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + process.env.GLASSES_API_TOKEN }, body: JSON.stringify({ text: c.raw, target: c.target }) });
    return r.json();
  }
  const people = peopleFor(c), canMention = Boolean(c.canMention);
  const { messages } = buildCleanupMessages({ target: c.target, raw: c.raw, people, canMention, vocab: c.target === 't3' ? vocab : null });
  let output = '';
  try { output = await complete(messages); } catch (e) { return { text: c.raw, cleaned: false, mentions: [], error: e.message }; }
  return { ...finish(output, { raw: c.raw, target: c.target, people, canMention, vocab: c.target === 't3' ? vocab : null, guard: opt('guard', 'enforce') }), model: output };
}

const loose = (s) => s.toLowerCase().normalize('NFKD').replace(/\p{M}/gu, '').replace(/[^\p{L}\p{N}\s]/gu, ' ').replace(/\s+/g, ' ').trim();
const words = (s) => [...new Set(loose(s).split(' ').filter(w => w.length >= 4))];
export function check(c, result) {
  const out = result.text ?? '', low = out.toLowerCase(), fails = [];
  if (result.error) fails.push('error: ' + result.error);
  if (result.rejected) fails.push('output check rejected (' + result.rejected + ')');
  for (const s of c.contains ?? []) if (!low.includes(s.toLowerCase())) fails.push('missing "' + s + '"');
  for (const s of c.containsExact ?? []) if (!out.includes(s)) fails.push('missing exact "' + s + '"');
  for (const s of c.notContains ?? []) if (low.includes(s.toLowerCase())) fails.push('contains "' + s + '"');
  for (const r of c.matches ?? []) if (!new RegExp(r, 'i').test(out)) fails.push('no match /' + r + '/');
  for (const r of c.notMatches ?? []) if (new RegExp(r, 'i').test(out)) fails.push('matches /' + r + '/');
  if (c.equalsLoose && loose(out) !== loose(c.raw)) fails.push('not equal (loose)');
  if (c.maxLength && out.length > c.maxLength) fails.push('longer than ' + c.maxLength);
  if (c.minOverlap) { const w = words(c.raw), hay = loose(out); const share = w.filter(x => hay.includes(x)).length / (w.length || 1); if (share < c.minOverlap) fails.push('overlap ' + share.toFixed(2)); }
  if (c.mentions) {
    const got = (result.mentions ?? []).map(m => m.id).sort(), want = [...c.mentions].sort();
    if (JSON.stringify(got) !== JSON.stringify(want)) fails.push('mentions ' + JSON.stringify(got));
  }
  return fails;
}

async function main() {
  const cases = evals.cases.filter(c => (!only || only.has(c.name)) && !(opt('gateway') && c.people));
  const jobs = cases.flatMap(c => Array.from({ length: runs }, (_, i) => ({ c, i })));
  const results = new Map(cases.map(c => [c.name, []]));
  let next = 0;
  await Promise.all(Array.from({ length: Math.max(1, concurrency) }, async () => {
    while (next < jobs.length) {
      const { c } = jobs[next++];
      const result = await runCase(c);
      const fails = check(c, result);
      results.get(c.name).push({ result, fails });
      if (flag('verbose') || fails.length) console.error(`${fails.length ? 'FAIL' : 'ok  '} ${c.name}: ${JSON.stringify(result.text)}${flag("raw") ? " model=" + JSON.stringify(result.model) : ""}${result.mentions?.length ? ' ' + JSON.stringify(result.mentions.map(m => m.token)) : ''}${fails.length ? ' <- ' + fails.join('; ') : ''}`);
    }
  }));
  const pad = (s, n) => String(s).padEnd(n);
  console.log(`\nmodel ${model}, ${runs} run(s) per case\n`);
  console.log(pad('case', 28) + pad('group', 13) + 'pass');
  let failed = 0;
  for (const c of cases) {
    const rows = results.get(c.name), pass = rows.filter(r => !r.fails.length).length;
    if (pass < rows.length) failed++;
    console.log(pad(c.name, 28) + pad(c.group, 13) + `${pass}/${rows.length}`);
  }
  console.log(`\n${cases.length - failed}/${cases.length} cases passed every run`);
  process.exitCode = failed ? 1 : 0;
}
if (import.meta.url === new URL(process.argv[1], 'file:').href || process.argv[1]?.endsWith('cleanup-eval.mjs')) await main();
