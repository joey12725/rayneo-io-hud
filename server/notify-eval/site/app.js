// notification Eval page. Data is embedded at build time; the page makes no network requests.
// All case text is untrusted: it is only ever inserted with textContent.
(function () {
  'use strict';
  const DATA = JSON.parse(document.getElementById('eval-data').textContent);
  const cases = DATA.cases, runs = DATA.runs;
  const byId = new Map(cases.map(c => [c.id, c]));
  const KEY = 'notify-eval:labels:v1';
  const $ = id => document.getElementById(id);
  const pct = x => x === null || x === undefined ? '—' : (100 * x).toFixed(1) + '%';
  const num = (x, d = 3) => x === null || x === undefined ? '—' : Number(x).toFixed(d);
  const KIND_ORDER = ['beeper', 't3', 'hermes', 'call'];
  const KINDS = [...KIND_ORDER.filter(k => cases.some(c => c.kind === k)), ...[...new Set(cases.map(c => c.kind))].filter(k => !KIND_ORDER.includes(k))];
  const ts = at => at ? String(at).slice(0, 19).replace('T', ' ') : '';
  // Signed offset of `at` from the case's own time, e.g. "-12 min".
  function rel(at, ref) {
    const d = (Date.parse(at) - Date.parse(ref)) / 1000;
    if (!Number.isFinite(d)) return '';
    const a = Math.abs(d), w = a < 90 ? `${Math.round(a)} s` : a < 5400 ? `${Math.round(a / 60)} min` : a < 172800 ? `${(a / 3600).toFixed(1)} h` : `${Math.round(a / 86400)} d`;
    return (d < 0 ? '-' : d > 0 ? '+' : '') + w;
  }

  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === false || v === null || v === undefined) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'style') el.setAttribute('style', v);
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat()) if (kid !== null && kid !== undefined && kid !== false) el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    return el;
  }
  const chip = (route, extra) => h('span', { class: 'chip r-' + route + (extra ? ' ' + extra : ''), text: route });

  // ---- local overrides (per browser; export to make them permanent) ----
  function loadOverrides() { try { return JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch { return {}; } }
  let overrides = loadOverrides();
  function saveOverrides() { try { localStorage.setItem(KEY, JSON.stringify(overrides)); } catch {} renderAll(); }
  const eff = c => { const o = overrides[c.id]; return { expected: o?.expected ?? c.expected, ambiguous: o?.ambiguous ?? c.ambiguous, why: o?.why || c.why, overridden: Boolean(o) }; };

  // ---- state (filters live in the URL hash) ----
  const state = { run: runs.length ? runs[runs.length - 1].runId : '', compare: '', popupAt: null, sel: null, view: 'cases', f: {} };
  function readHash() {
    const p = new URLSearchParams(location.hash.slice(1));
    for (const [k, v] of p) { if (k === 'run' || k === 'compare' || k === 'sel' || k === 'view') state[k] = v; else if (k === 'popupAt') state.popupAt = Number(v); else state.f[k] = v; }
  }
  function writeHash() {
    const p = new URLSearchParams();
    if (state.run) p.set('run', state.run);
    if (state.compare) p.set('compare', state.compare);
    if (state.popupAt !== null) p.set('popupAt', String(state.popupAt));
    if (state.sel) p.set('sel', state.sel);
    if (state.view !== 'cases') p.set('view', state.view);
    for (const [k, v] of Object.entries(state.f)) if (v) p.set(k, v);
    history.replaceState(null, '', '#' + p.toString());
  }
  const runById = id => runs.find(r => r.runId === id) || null;
  const curRun = () => runById(state.run);
  const popupAt = run => state.popupAt ?? run?.popupAt ?? DEFAULT_POPUP_AT;
  const resultMap = run => new Map((run?.results || []).map(r => [r.id, r]));

  function caseOutcome(run, map, c) {
    const r = map.get(c.id);
    return r ? outcome(r, run.endpoint, popupAt(run)) : null;
  }

  // ---- header ----
  function renderHeader() {
    $('built').textContent = `built ${DATA.builtAt.slice(0, 16).replace('T', ' ')} · ${cases.length} cases · ${runs.length} run${runs.length === 1 ? '' : 's'}`;
    for (const [sel, withNone] of [[$('run'), false], [$('compare'), true]]) {
      sel.replaceChildren(...(withNone ? [h('option', { value: '', text: 'none' })] : []),
        ...runs.map(r => h('option', { value: r.runId, text: `${r.label}${r.split && !String(r.label).startsWith(r.split) ? ' [' + r.split + ']' : ''} · ${r.endpoint} · ${r.datasets || 'v1'} · ${r.startedAt.slice(0, 16).replace('T', ' ')}` })));
    }
    $('run').value = state.run; $('compare').value = state.compare;
    const run = curRun();
    $('thresholdBox').hidden = !run || run.endpoint !== 'classify';
    $('popupAt').value = popupAt(run); $('popupAtOut').textContent = Number(popupAt(run)).toFixed(2);
    $('runInfo').textContent = run
      ? `Run ${run.runId}: ${run.endpoint === 'classify' ? 'old /notify/classify (binary; notify ≥ threshold counts as popup)' : '/notify/route'} on ${run.target}${run.model ? ' · model ' + run.model : ''} · ${run.repeats} repeat${run.repeats === 1 ? '' : 's'} per case · ${run.split ? run.split + ' split (' + run.results.length + ' cases)' : 'all cases (' + run.results.length + ')'} · datasets ${run.datasets || 'v1'}${run.datasets === 'v1' || !run.datasets ? ' (scored against the current labels)' : ''} · commit ${run.gitCommit || '?'}`
      : 'No run yet: only the labelled dataset is shown.';
  }

  // ---- summary + confusion matrices ----
  function renderSummary() {
    const run = curRun();
    const labels = Object.fromEntries(Object.entries(overrides).map(([id, o]) => [id, { expected: o.expected, ambiguous: o.ambiguous }]));
    const box = $('summary'), mbox = $('matrices');
    if (!run) {
      const counts = k => ['suppress', 'stream', 'popup'].map(r => `${r} ${cases.filter(c => (!k || c.kind === k) && eff(c).expected === r).length}`).join(' · ');
      box.replaceChildren(...[['all', null], ...KINDS.map(k => [k, k])].map(([n, k]) => h('div', { class: 'card' }, h('h2', { text: n }), h('div', { class: 'small', text: counts(k) }))));
      mbox.replaceChildren(); return;
    }
    const m = metrics(cases, run, { popupAt: popupAt(run), labels });
    const groups = [['All', m.all, ''], ...KINDS.filter(k => m.byKind[k]).map(k => [k, m.byKind[k], k])];
    box.replaceChildren(...groups.map(([name, g]) => h('div', { class: 'card' },
      h('h2', { text: name }),
      h('div', { class: 'big', text: pct(g.accuracy) }),
      h('div', { class: 'small muted', text: `accuracy on ${g.scored} clear cases (${g.ambiguous} ambiguous excluded)` }),
      h('dl', {},
        h('dt', { text: 'weighted cost / case' }), h('dd', { text: `${num(g.cost.perCase)} (${num(g.cost.total, 1)})` }),
        h('dt', { text: 'macro-F1' }), h('dd', { text: num(g.macroF1) }),
        h('dt', { text: 'accuracy, all cases' }), h('dd', { text: pct(g.accuracyAll) }),
        h('dt', { text: 'popup precision' }), h('dd', { text: `${pct(g.popup.precision)} (${g.popup.tp}/${g.popup.tp + g.popup.fp})` }),
        h('dt', { text: 'popup recall' }), h('dd', { text: `${pct(g.popup.recall)} (${g.popup.tp}/${g.popup.tp + g.popup.fn})` }),
        h('dt', { text: 'flaky cases' }), h('dd', { text: `${g.flaky}/${g.n}` }),
        h('dt', { text: 'errors' }), h('dd', { text: String(g.errors) }),
        run.endpoint === 'route' ? [h('dt', { text: 'contract breaks' }), h('dd', { text: String(g.contract) })] : null,
        h('dt', { text: 'latency p50 / p95' }), h('dd', { text: `${g.latency.p50 ?? '—'} / ${g.latency.p95 ?? '—'} ms` }),
        h('dt', { text: 'decided by' }), h('dd', { text: Object.entries(g.byRoute).map(([k, v]) => `${k} ${v}`).join(', ') || '—' })))));
    mbox.replaceChildren(...groups.map(([name, g, kind]) => {
      const cols = ['suppress', 'stream', 'popup', 'error'];
      return h('div', { class: 'card' }, h('h2', { text: `${name}: expected × actual` }),
        h('table', { class: 'matrix' },
          h('tr', {}, h('th', { text: 'exp \\ act' }), ...cols.map(c => h('th', { text: c }))),
          ...['suppress', 'stream', 'popup'].map(e => {
            const rowTotal = cols.reduce((n, a) => n + g.matrix[e][a], 0) || 1;
            return h('tr', {}, h('th', { text: e }), ...cols.map(a => h('td', {
              class: e === a ? 'diag' : 'off', style: e === a ? null : `--w:${Math.round(60 * g.matrix[e][a] / rowTotal)}`, title: `filter: ${kind || 'all'} expected ${e}, actual ${a}`,
              text: String(g.matrix[e][a]),
              onclick: () => { state.f = { ...state.f, kind, expected: e, actual: a }; syncFilterInputs(); renderList(); writeHash(); } })));
          })));
    }));
  }

  // ---- filters ----
  function syncFilterInputs() {
    for (const el of document.querySelectorAll('[data-f]')) {
      const v = state.f[el.dataset.f] || '';
      if (el.type === 'checkbox') el.checked = v === '1'; else el.value = v;
    }
  }
  function matches(c, run, map, cmp, cmpMap) {
    const f = state.f, e = eff(c), o = run ? caseOutcome(run, map, c) : null;
    if (f.kind && c.kind !== f.kind) return false;
    if (f.split && c.split !== f.split) return false;
    if (f.votes === 'unanimous' && c.votes?.agreement !== 'unanimous') return false;
    if (f.votes === 'split' && (!c.votes || c.votes.agreement === 'unanimous')) return false;
    if (f.votes === 'v1changed' && !(c.v1Expected && c.v1Expected !== c.expected)) return false;
    if (f.votes === 'v2changed' && !(c.v2Expected && c.v2Expected !== c.expected)) return false;
    if (f.votes === 'policy' && !c.policy) return false;
    if (f.expected && e.expected !== f.expected) return false;
    if (f.actual && (!o || o.actual !== f.actual)) return false;
    if (f.ambiguous === 'only' && !e.ambiguous) return false;
    if (f.ambiguous === 'exclude' && e.ambiguous) return false;
    if (f.synthetic === 'only' && !c.synthetic) return false;
    if (f.synthetic === 'exclude' && c.synthetic) return false;
    if (f.source === 'override' && !e.overridden) return false;
    if (f.source === 'disagree' && !(c.labels?.llm && c.labels?.heuristic && c.labels.llm.expected !== c.labels.heuristic.expected)) return false;
    if (f.source && !['override', 'disagree'].includes(f.source) && c.labelSource !== f.source) return false;
    if (f.mismatch === '1' && (!o || o.actual === e.expected)) return false;
    if (f.flaky === '1' && (!o || !o.flaky)) return false;
    if (f.changed === '1') { const p = cmp ? caseOutcome(cmp, cmpMap, c) : null; if (!o || !p || p.actual === o.actual) return false; }
    if (f.q) {
      const x = c.context || {};
      const hay = [c.id, c.input.title, c.input.body, c.input.sender, c.input.conversationTitle, c.input.network, e.why, x.chatName, x.project, x.prompt?.text, x.response?.text,
        ...(x.turns || []).flatMap(t => (t.messages || []).map(m => m.text)), ...(c.votes?.voters || []).map(v => v.reason)].join(' ').toLowerCase();
      if (!hay.includes(f.q.toLowerCase())) return false;
    }
    return true;
  }

  // ---- list ----
  function renderList() {
    const run = curRun(), map = resultMap(run), cmp = runById(state.compare), cmpMap = resultMap(cmp);
    const shown = cases.filter(c => matches(c, run, map, cmp, cmpMap));
    // Disagreements and mismatches first, then by kind.
    const rank = c => { const o = run ? caseOutcome(run, map, c) : null; return (o && o.actual !== eff(c).expected ? 0 : 2) + (c.labels?.llm && c.labels?.heuristic && c.labels.llm.expected !== c.labels.heuristic.expected ? 0 : 1); };
    shown.sort((a, b) => rank(a) - rank(b) || KINDS.indexOf(a.kind) - KINDS.indexOf(b.kind) || a.id.localeCompare(b.id));
    const list = $('list');
    if (!shown.length) { list.replaceChildren(h('div', { class: 'empty', text: 'No cases match these filters.' })); return; }
    list.replaceChildren(h('div', { class: 'small muted', style: 'padding:6px 10px', text: `${shown.length} of ${cases.length} cases` }), ...shown.map(c => {
      const e = eff(c), o = run ? caseOutcome(run, map, c) : null;
      return h('div', { class: 'row' + (state.sel === c.id ? ' sel' : ''), onclick: () => { state.sel = c.id; writeHash(); renderList(); renderDetail(); } },
        h('div', {}, h('div', { class: 'kind', text: c.kind }), c.split ? h('span', { class: 'chip split-' + c.split, text: c.split }) : null, c.synthetic ? h('span', { class: 'chip badge-syn', text: 'SYN' }) : null),
        h('div', { style: 'min-width:0' }, h('div', { class: 'title', text: c.input.title || '(no title)' }), h('div', { class: 'body', text: (c.input.sender && c.input.isGroup ? c.input.sender + ': ' : '') + (c.input.body || '') })),
        h('div', { class: 'routes' }, chip(e.expected, e.ambiguous ? 'badge-amb' : ''), h('span', { class: 'muted', text: '→' }), o ? chip(o.actual) : h('span', { class: 'muted', text: '—' }),
          o ? h('span', { class: o.actual === e.expected ? 'mark-ok' : 'mark-bad', text: o.actual === e.expected ? '✓' : '✗' }) : null,
          o && o.flaky ? h('span', { class: 'chip', title: o.routes.join(', '), text: 'flaky' }) : null,
          c.votes && c.votes.agreement !== 'unanimous' ? h('span', { class: 'chip', title: 'Haiku votes ' + Object.entries(c.votes.counts || {}).map(([k, v]) => `${k} ${v}`).join(', '), text: '2/3' }) : null,
          c.labelSource === 'owner-policy' ? h('span', { class: 'chip', title: `Owner policy: ${c.policy?.rule || ''}${c.policy?.from ? ' (votes said ' + c.policy.from + ')' : ''}`, text: 'policy' }) : null,
          e.overridden ? h('span', { class: 'chip', text: 'edited' }) : null));
    }));
  }

  // ---- detail ----
  const words = s => s === null || s === undefined ? '—' : s < 90 ? `${s} s` : s < 5400 ? `${Math.round(s / 60)} min` : `${(s / 3600).toFixed(1)} h`;
  function signalsText(c) {
    const s = c.signals || {};
    if (c.kind === 'beeper') return [
      s.repliedAfterSec !== null && s.repliedAfterSec !== undefined ? `Owner replied after ${words(s.repliedAfterSec)}` : 'Owner did not reply (within 12 h)',
      s.activeBeforeSec !== null && s.activeBeforeSec !== undefined ? `Owner last wrote ${words(s.activeBeforeSec)} before` : null,
      s.reacted ? 'Owner reacted' : null, s.quoted ? 'Owner quoted it' : null,
      ...['question', 'plans', 'addressed', 'urgent', 'otp', 'promo', 'reaction', 'share', 'link', 'banter', 'stub', 'shortcode', 'replyToOwner'].filter(k => s[k]).map(k => 'signal: ' + k),
    ].filter(Boolean);
    if (c.kind === 't3') return [`run status ${s.status}`, `took ${words(s.durationSec)}`, s.pendingInput ? 'asked the owner for input' : null,
      s.nextUserAfterSec !== null && s.nextUserAfterSec !== undefined ? `Owner's next message after ${words(s.nextUserAfterSec)}` : 'no later message from the owner'].filter(Boolean);
    if (c.kind === 'hermes') return [s.delaySec !== undefined ? `answered ${words(s.delaySec)} after the prompt` : null, s.priorTurns !== undefined ? `${s.priorTurns} earlier turns` : null,
      s.delivered !== undefined ? (s.delivered ? 'delivered' : 'not delivered') : null].filter(Boolean);
    if (c.kind === 'call') { const x = c.context || {}; return [x.role ? `role: ${x.role}${x.roleUncertain ? ' (uncertain)' : ''}` : null, x.outcome ? `outcome: ${x.outcome}, ${x.durationSec ?? 0} s` : null,
      x.priorCalls90d !== undefined ? `${x.priorCalls90d} earlier calls in 90 days` : null, x.ownerCalledThisNumber ? 'Owner has called this number' : null, x.localTime ? `at ${x.localTime}` : null].filter(Boolean); }
    return [];
  }
  function votesBlock(c) {
    const out = [];
    if (c.policy) out.push(h('div', { class: 'labelbar small' }, h('strong', { text: "Owner policy: " }), h('span', { text: c.policy.rule }),
      c.labelSource === 'owner-policy' || c.policy.changed ? h('span', { class: 'mark-bad', text: `override: votes said ${c.policy.from || '?'}${c.policy.fromAmbiguous ? ' (ambiguous)' : ''}, policy sets ${c.expected}` })
        : h('span', { class: 'muted', text: 'matched; votes already agreed' })));
    if (c.v2Expected) out.push(h('div', { class: 'labelbar small' }, h('strong', { text: 'previous label (v2): ' }), chip(c.v2Expected, c.v2Ambiguous ? 'badge-amb' : ''), h('span', { class: 'muted', text: `(${c.v2LabelSource || '?'}${c.v2Ambiguous ? ', ambiguous' : ''})` }),
      c.v2Expected !== c.expected ? h('span', { class: 'mark-bad', text: `changed to ${c.expected}` }) : h('span', { class: 'mark-ok', text: 'unchanged' })),
      c.v2Why ? h('div', { class: 'small muted', text: 'previous why: ' + c.v2Why }) : null);
    if (c.v1Expected) out.push(h('div', { class: 'labelbar small' }, h('strong', { text: 'v1 label: ' }), chip(c.v1Expected), h('span', { class: 'muted', text: `(${c.v1LabelSource || '?'})` }),
      c.v1Expected !== c.expected ? h('span', { class: 'mark-bad', text: `changed to ${c.expected}` }) : h('span', { class: 'mark-ok', text: 'unchanged' })));
    const v = c.votes;
    if (!v) return out;
    out.push(h('h3', { text: `Haiku votes${c.votesV2 ? ' (v3, blind)' : ''} · ${v.agreement === 'unanimous' ? 'unanimous' : '2 of 3'} · majority ${v.majority} · mean confidence ${num(v.meanConfidence, 2)}` }));
    out.push(h('table', { class: 'attempts' }, h('tr', {}, ...['voter', 'label', 'conf', 'reason'].map(t => h('th', { text: t }))),
      ...(v.voters || []).map(p => h('tr', {}, h('td', { text: p.voter }), h('td', {}, chip(p.label)), h('td', { text: num(p.confidence, 2) }), h('td', { text: p.reason || '' })))));
    const flags = [v.popupSuppressSplit ? 'popup/suppress split' : null, v.lowConfidenceMajority ? 'low-confidence majority' : null].filter(Boolean);
    if (flags.length) out.push(h('div', { class: 'small mark-bad', text: flags.join(' · ') }));
    const pv = c.votesV2;
    if (pv?.voters?.length) out.push(h('details', {}, h('summary', { class: 'small', text: `previous votes (v2) · majority ${pv.majority} · ${pv.agreement === 'unanimous' ? 'unanimous' : '2 of 3'}` }),
      h('table', { class: 'attempts' }, h('tr', {}, ...['voter', 'label', 'conf', 'reason'].map(t => h('th', { text: t }))),
        ...pv.voters.map(p => h('tr', {}, h('td', { text: p.voter }), h('td', {}, chip(p.label)), h('td', { text: num(p.confidence, 2) }), h('td', { text: p.reason || '' }))))));
    return out;
  }
  function msgLine(m, ref) {
    return h('div', { class: 'msg' }, h('span', { class: 'ts', title: m.at || '', text: m.at ? `${ts(m.at)} (${rel(m.at, ref)})` : '' }), h('span', { class: 'mtext', text: m.text || '' }),
      m.priorRoute ? chip(m.priorRoute) : null,
      m.caseId && byId.has(m.caseId) ? h('button', { type: 'button', class: 'linkish', text: 'case', onclick: () => { state.sel = m.caseId; writeHash(); renderList(); renderDetail(); } }) : null);
  }
  function contextBlock(c) {
    const x = c.context || {}, out = [];
    if (x.originalRequest) out.push(h('h3', { text: 'Original request' }), h('div', { class: 'turn owner' }, h('div', { class: 'who', text: x.originalRequest.sender || 'owner' }), msgLine(x.originalRequest, c.at)));
    if (Array.isArray(x.turns)) {
      out.push(h('h3', { text: `Conversation context · ${x.turns.length} earlier turn${x.turns.length === 1 ? '' : 's'}${x.windowHours ? ` within ${x.windowHours} h` : ''}${x.opener ? ' · opener' : ''}` }));
      if (!x.turns.length) out.push(h('div', { class: 'small muted', text: x.opener ? 'Opener: nothing earlier inside the window.' : 'No earlier turns.' }));
      for (const t of x.turns) out.push(h('div', { class: 'turn' + (t.isOwner ? ' owner' : '') }, h('div', { class: 'who', text: t.isOwner ? 'Owner' : (t.name || t.speaker) }), ...(t.messages || []).map(m => msgLine(m, c.at))));
    }
    if (x.prompt) out.push(h('h3', { text: 'Prompt' }), h('div', { class: 'turn' + (x.prompt.isOwner ? ' owner' : '') },
      h('div', { class: 'who', text: `${x.prompt.isOwner ? 'Owner' : x.prompt.speaker || x.prompt.sender || 'other'}${x.prompt.agentWritten || x.prompt.fromParentAgent ? ' (agent-written)' : ''}` }), msgLine(x.prompt, c.at)));
    if (x.response) out.push(h('h3', { text: `Response${x.response.delaySec !== undefined ? ` · ${words(x.response.delaySec)} after the prompt` : ''}${x.response.length ? ` · ${x.response.length} chars` : ''}` }),
      h('div', { class: 'turn' }, h('div', { class: 'who', text: c.kind === 't3' ? 'agent' : 'Hermes' }), msgLine(x.response, c.at)));
    if (x.target) out.push(h('h3', { text: 'Target message' }), h('div', { class: 'turn target' }, h('div', { class: 'who', text: x.target.sender || '' }), msgLine(x.target, c.at)));
    if (x.relationship) { const r = x.relationship; out.push(h('h3', { text: 'Relationship stats (as of the message)' }), h('div', { class: 'small' },
      `tier ${r.tier ?? '—'} · ${r.msgs30d ?? '—'} messages in ${r.days ?? 30} days · owner wrote ${r.ownerMsgs30d ?? '—'} (${r.ownerShare === null || r.ownerShare === undefined ? '—' : Math.round(100 * r.ownerShare) + '%'})`
      + ` · owner last wrote ${r.lastOwnerAt ? ts(r.lastOwnerAt) + ' (' + rel(r.lastOwnerAt, c.at) + ')' : 'never in the window'}${r.estimated ? ' · estimated' : ''}${r.windowCovered === false ? ' · history does not cover the window' : ''}`)); }
    if (x.call) { const k = x.call; out.push(h('h3', { text: 'Call facts (before the call)' }), h('div', { class: 'small', text:
      [`${k.priorAnsweredCalls ?? 0} earlier answered calls`, k.ownerCalledNumber ? 'Owner has called this number' : 'Owner never called this number', k.privateNumber || k.private ? 'private number' : null, k.spam ? 'spam' : null, k.business ? 'business' : null].filter(Boolean).join(' · ') })); }
    if (x.chat) out.push(h('div', { class: 'small muted', text: `chat: ${x.chat.type || (x.chat.isGroup ? 'group' : 'single')}, ${x.chat.participants ?? '?'} participants${x.chat.muted ? ', muted' : ''}${x.chat.lowPriority ? ', low priority' : ''}${x.chat.spam ? ', spam' : ''}` }));
    return out;
  }
  function renderDetail() {
    const box = $('detail'), c = byId.get(state.sel);
    if (!c) { box.replaceChildren(h('p', { class: 'muted', text: 'Select a case.' })); return; }
    const run = curRun(), r = run ? resultMap(run).get(c.id) : null, e = eff(c), o = r ? outcome(r, run.endpoint, popupAt(run)) : null;
    const o2 = overrides[c.id] || {};
    const note = h('input', { type: 'text', placeholder: 'why (optional)', value: o2.why || '' });
    const setLabel = patch => { overrides[c.id] = { expected: e.expected, ambiguous: e.ambiguous, ...o2, ...patch, why: note.value || o2.why || '', at: new Date().toISOString() }; saveOverrides(); };
    const x = c.context || {};
    const prev = Array.isArray(x.turns) ? [] : x.previous || [];
    box.replaceChildren(
      h('div', { class: 'labelbar' }, h('strong', { text: c.id }), h('span', { class: 'kind', text: c.kind }), c.split ? h('span', { class: 'chip split-' + c.split, title: 'harness split (run.mjs --split)', text: c.split }) : null, c.synthetic ? h('span', { class: 'chip badge-syn', text: 'SYNTHETIC' }) : null,
        c.bucket ? h('span', { class: 'chip', text: c.bucket === 'want' ? 'picked as want' : 'picked as do-not-want' }) : null, c.at ? h('span', { class: 'muted small', text: c.at.slice(0, 16).replace('T', ' ') }) : null),
      h('h3', { text: 'Expected' }),
      h('div', { class: 'labelbar' }, chip(e.expected, e.ambiguous ? 'badge-amb' : ''), e.ambiguous ? h('span', { class: 'chip', text: 'ambiguous' }) : null,
        h('span', { class: 'small muted', text: `label: ${e.overridden ? 'local override' : c.labelSource}` })),
      h('div', { text: e.why }),
      h('div', { class: 'labelbar' },
        ...['suppress', 'stream', 'popup'].map(rt => h('button', { type: 'button', class: e.overridden && e.expected === rt ? 'on' : '', text: rt, onclick: () => setLabel({ expected: rt }) })),
        h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: e.ambiguous, onchange: ev => setLabel({ ambiguous: ev.target.checked }) }), 'ambiguous'),
        note, h('button', { type: 'button', text: 'Save note', onclick: () => setLabel({}) }),
        e.overridden ? h('button', { type: 'button', text: 'Undo override', onclick: () => { delete overrides[c.id]; saveOverrides(); } }) : null),
      ...votesBlock(c),
      h('h3', { text: c.votes ? 'v1 pre-labels' : 'Pre-labels' }),
      ...['heuristic', 'llm', 'owner'].filter(k => c.labels?.[k]).map(k => h('div', { class: 'small' }, h('strong', { text: k + (c.labels[k].model ? ` (${c.labels[k].model})` : '') + ': ' }), chip(c.labels[k].expected), ' ', c.labels[k].ambiguous ? '(ambiguous) ' : '', c.labels[k].why || '')),
      h('h3', { text: 'Actual' }),
      !r ? h('div', { class: 'muted', text: run ? 'Not in this run.' : 'No run selected.' }) : h('div', {},
        h('div', { class: 'labelbar' }, chip(o.actual), h('span', { class: o.actual === e.expected ? 'mark-ok' : 'mark-bad', text: o.actual === e.expected ? 'match' : 'mismatch' }),
          o.flaky ? h('span', { class: 'chip', text: `flaky: ${o.routes.join(', ')}` }) : h('span', { class: 'small muted', text: `${Math.round(o.agreement * 100)}% agreement` })),
        h('table', { class: 'attempts' }, h('tr', {}, ...['#', 'route', 'importance', 'by', 'reason', 'ms'].map(t => h('th', { text: t }))),
          ...r.attempts.map((x, i) => h('tr', {}, h('td', { text: String(i + 1) }), h('td', {}, x.error ? chip('error') : chip(actual3(x, run.endpoint, popupAt(run))), x.route && run.endpoint === 'classify' ? h('span', { class: 'small muted', text: ' ' + x.route }) : null),
            h('td', { text: x.importance ?? '—' }), h('td', { text: x.by ?? '—' }), h('td', { text: (x.error ? `${x.status || ''} ${x.error}` : x.reason ?? '—') + (x.contract?.length ? ` [contract: ${x.contract.join('; ')}]` : '') }), h('td', { text: x.ms ?? '—' })))),
        r.attempts.some(x => x.debug || x.open || x.extra) ? h('details', {}, h('summary', { text: 'raw debug' }), h('pre', { text: JSON.stringify(r.attempts.map(x => ({ open: x.open, debug: x.debug, extra: x.extra })), null, 1) })) : null),
      ...contextBlock(c),
      h('h3', { text: 'Signals' }), h('ul', { class: 'small' }, ...signalsText(c).map(t => h('li', { text: t }))),
      prev.length ? h('h3', { text: 'Earlier in the chat' }) : null,
      ...prev.map(p => h('div', { class: 'prev' }, h('span', { class: 'who', text: (p.mine ? 'Owner' : p.sender) + ': ' }), p.text || '')),
      h('h3', { text: 'Input sent to /notify/route' }), h('pre', { text: JSON.stringify(c.input, null, 1) }),
      c.context ? h('details', {}, h('summary', { text: 'raw context' }), h('pre', { text: JSON.stringify({ ...c.context, previous: undefined }, null, 1) })) : null,
    );
  }

  // ---- runs overview + tuning log ----
  const mdInline = text => text.replace(/\*\*/g, '').replace(/`/g, '');
  function renderMarkdown(md) {
    const out = [], lines = md.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      if (/^\s*\|/.test(l)) {
        const rows = [];
        while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(lines[i++]);
        i--;
        const cells = r => r.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(v => mdInline(v.trim()));
        const isSep = r => /^\s*\|[\s|:-]+\|\s*$/.test(r), hasHead = rows.length > 1 && isSep(rows[1]);
        const [head, ...rest] = hasHead ? rows.filter(r => !isSep(r)) : [null, ...rows.filter(r => !isSep(r))];
        out.push(h('div', { class: 'tablewrap' }, h('table', { class: 'attempts log' }, head ? h('tr', {}, ...cells(head).map(t => h('th', { text: t }))) : null,
          ...rest.map(r => h('tr', {}, ...cells(r).map(t => h('td', { text: t })))))));
      } else if (/^#{1,6} /.test(l)) out.push(h(/^# /.test(l) ? 'h2' : 'h3', { text: mdInline(l.replace(/^#+ /, '')) }));
      else if (/^\s*- /.test(l)) {
        const items = [];
        while (i < lines.length && /^\s*- /.test(lines[i])) items.push(lines[i++].replace(/^\s*- /, ''));
        i--;
        out.push(h('ul', { class: 'small' }, ...items.map(t => h('li', { text: mdInline(t) }))));
      } else if (l.trim()) out.push(h('p', { class: 'small', text: mdInline(l) }));
    }
    return out;
  }
  function renderTuning() {
    const head = ['run', 'split', 'datasets', 'clear / cases', 'cost / case', 'accuracy', 'macro-F1', ...KINDS.map(k => 'F1 ' + k), 'popup P', 'popup R', 'flaky', 'errors'];
    const rows = runs.map(r => {
      const m = metrics(cases, r, { popupAt: popupAt(r) }), a = m.all;
      return h('tr', { class: r.runId === state.run ? 'sel' : '', title: 'open this run in the Cases view', onclick: () => { state.run = r.runId; state.view = 'cases'; state.popupAt = null; writeHash(); renderAll(); } },
        h('td', { text: r.label }), h('td', { text: r.split || 'all' }), h('td', { text: r.datasets || 'v1' }), h('td', { text: `${a.scored}/${a.n}` }),
        h('td', { text: num(a.cost.perCase) }), h('td', { text: pct(a.accuracy) }), h('td', { text: num(a.macroF1) }),
        ...KINDS.map(k => h('td', { text: m.byKind[k] ? num(m.byKind[k].macroF1) : '—' })), h('td', { text: pct(a.popup.precision) }), h('td', { text: pct(a.popup.recall) }),
        h('td', { text: String(a.flaky) }), h('td', { text: String(a.errors) }));
    });
    const ls = DATA.labelSummary;
    $('tuningView').replaceChildren(
      h('div', { class: 'card' }, h('h2', { text: 'Runs' }),
        h('div', { class: 'tablewrap' }, h('table', { class: 'attempts log runs' }, h('tr', {}, ...head.map(t => h('th', { text: t }))), ...rows)),
        h('p', { class: 'small muted', text: 'All runs are scored against the labels embedded in this page; cost, accuracy and F1 use clear (non-ambiguous) cases. Weighted cost: ' + COST_LABEL + '; lower is better. Runs made on the v1 datasets are scored here against the current labels. Click a run to open it.' })),
      ls ? h('div', { class: 'card' }, h('h2', { text: `Label votes (${ls.labelSource || 'swarm'})` }),
        h('div', { class: 'tablewrap' }, h('table', { class: 'attempts log' }, h('tr', {}, ...['kind', 'cases', 'popup', 'stream', 'suppress', 'unanimous', '2 of 3', 'ambiguous', 'policy overrides', 'changed vs previous'].map(t => h('th', { text: t }))),
          ...Object.entries(ls.perKind).map(([k, v]) => h('tr', {}, ...[k, v.total, v.popup, v.stream, v.suppress, v.unanimous, v.twoOfThree, v.ambiguous, v.policyOverridden, v.changed ?? v.changedVsV2].map(t => h('td', { text: String(t ?? '—') })))))),
        ls.rule ? h('p', { class: 'small muted', text: ls.rule }) : null) : null,
      DATA.tuningLog ? h('div', { class: 'card md' }, ...renderMarkdown(DATA.tuningLog)) : h('div', { class: 'card muted', text: 'No tuning log embedded.' }));
  }
  function renderView() {
    const tuning = state.view === 'tuning';
    $('tuningView').hidden = !tuning; $('casesView').hidden = tuning;
    for (const [id, on] of [['tabCases', !tuning], ['tabTuning', tuning]]) { $(id).classList.toggle('on', on); $(id).setAttribute('aria-selected', String(on)); }
    if (tuning) renderTuning();
  }

  function renderOverrides() {
    const n = Object.keys(overrides).length;
    $('overrideCount').textContent = n ? `${n} local label override${n === 1 ? '' : 's'} (saved only in this browser until exported)` : 'No local label overrides.';
  }
  function renderAll() { renderHeader(); renderView(); renderSummary(); renderOverrides(); renderList(); renderDetail(); }

  // ---- wiring ----
  readHash(); if (!runById(state.run)) state.run = runs.length ? runs[runs.length - 1].runId : '';
  syncFilterInputs();
  $('tabCases').addEventListener('click', () => { state.view = 'cases'; writeHash(); renderView(); });
  $('tabTuning').addEventListener('click', () => { state.view = 'tuning'; writeHash(); renderView(); });
  $('run').addEventListener('change', ev => { state.run = ev.target.value; state.popupAt = null; writeHash(); renderAll(); });
  $('compare').addEventListener('change', ev => { state.compare = ev.target.value; writeHash(); renderList(); });
  $('popupAt').addEventListener('input', ev => { state.popupAt = Number(ev.target.value); writeHash(); renderAll(); });
  for (const el of document.querySelectorAll('[data-f]')) el.addEventListener(el.type === 'search' ? 'input' : 'change', () => {
    state.f[el.dataset.f] = el.type === 'checkbox' ? (el.checked ? '1' : '') : el.value; writeHash(); renderList();
  });
  $('clearFilters').addEventListener('click', () => { state.f = {}; syncFilterInputs(); writeHash(); renderList(); });
  $('export').addEventListener('click', () => {
    const rows = Object.entries(overrides).map(([id, o]) => ({ id, expected: o.expected, ambiguous: Boolean(o.ambiguous), why: o.why || '', at: o.at }));
    const blob = new Blob([JSON.stringify({ schema: 1, kind: 'label-corrections', exportedAt: new Date().toISOString(), corrections: rows }, null, 1)], { type: 'application/json' });
    const a = h('a', { href: URL.createObjectURL(blob), download: `labels-${new Date().toISOString().slice(0, 10)}.json` });
    document.body.append(a); a.click(); a.remove();
  });
  $('import').addEventListener('change', async ev => {
    const file = ev.target.files[0]; if (!file) return;
    try {
      const j = JSON.parse(await file.text());
      for (const x of j.corrections || []) if (byId.has(x.id) && ['suppress', 'stream', 'popup'].includes(x.expected)) overrides[x.id] = { expected: x.expected, ambiguous: Boolean(x.ambiguous), why: String(x.why || ''), at: x.at };
      saveOverrides();
    } catch { alert('Not a corrections file'); }
    ev.target.value = '';
  });
  $('clearOverrides').addEventListener('click', () => { if (confirm('Delete all local label overrides in this browser?')) { overrides = {}; saveOverrides(); } });
  renderAll();
})();
