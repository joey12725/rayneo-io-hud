// Dataset and run schemas. A case is:
//   {id, kind: beeper|t3|call, synthetic, input: <exact POST /notify/route body>, expected: suppress|stream|popup,
//    why, ambiguous, labelSource: heuristic|llm|owner, ...optional at, context, signals, labels}
// Schema 2 (datasets-v2) adds kind hermes (input.kind assistant; older datasets say joy) and conversation context: context.turns, context.target,
// and for t3/hermes context.prompt/context.response, each with timestamps. v2 datasets are checked with v2Errors.
// `context` and `signals` are evidence for labelling and for the site; they are never sent to the classifier.
export const KINDS = ['beeper', 't3', 'call', 'hermes'];
// The /notify/route input kind for each case kind when it differs (a Hermes reply is an assistant notification).
export const INPUT_KIND_OF = { hermes: 'assistant' };
export const ROUTES = ['suppress', 'stream', 'popup'];
export const LABEL_SOURCES = ['heuristic', 'llm', 'owner', 'owner-policy'];
export const INPUT_KINDS = ['beeper', 't3', 'call', 'assistant', 'joy', 'calendar', 'clock', 'app'];

// Field types of the /notify/route request body (shared notification-routing contract).
const INPUT_FIELDS = {
  kind: 'string', source: 'string', app: 'string', title: 'string', body: 'string', conversationTitle: 'string', sender: 'string',
  network: 'string', isGroup: 'boolean', chatId: 'string', channelId: 'string', category: 'string', contact: 'object',
  callerVerified: 'string', debug: 'boolean',
  // Calls: what the phone knows about the number at ring time (call log before this call, presentation,
  // spam verdict, business caller ID). Never the number or a name.
  priorAnsweredCalls: 'number', ownerCalledNumber: 'boolean', privateNumber: 'boolean', spam: 'boolean', business: 'boolean',
};

/** Returns a list of problems (empty when valid). */
export function caseErrors(c) {
  const e = [];
  if (!c || typeof c !== 'object') return ['case is not an object'];
  if (typeof c.id !== 'string' || !c.id) e.push('id missing');
  if (!KINDS.includes(c.kind)) e.push(`kind ${JSON.stringify(c.kind)} not one of ${KINDS}`);
  if (typeof c.synthetic !== 'boolean') e.push('synthetic must be boolean');
  if (!ROUTES.includes(c.expected)) e.push(`expected ${JSON.stringify(c.expected)} not one of ${ROUTES}`);
  if (typeof c.why !== 'string' || !c.why) e.push('why missing');
  if (typeof c.ambiguous !== 'boolean') e.push('ambiguous must be boolean');
  // heuristic | llm | owner, or a named labelling pass such as "haiku-swarm-v2" (v2 vote rounds).
  if (!LABEL_SOURCES.includes(c.labelSource) && !/^[a-z0-9]+(?:-[a-z0-9]+)*-(?:swarm|vote)s?(?:-v\d+)?$/.test(String(c.labelSource ?? ''))) e.push(`labelSource ${JSON.stringify(c.labelSource)} invalid`);
  const i = c.input;
  if (!i || typeof i !== 'object' || Array.isArray(i)) e.push('input missing');
  else {
    if (!INPUT_KINDS.includes(i.kind)) e.push('input.kind invalid');
    else if (i.kind !== (INPUT_KIND_OF[c.kind] ?? c.kind)) e.push('input.kind differs from kind');
    for (const [k, v] of Object.entries(i)) {
      if (!(k in INPUT_FIELDS)) e.push(`input.${k} is not a /notify/route field`);
      else if (typeof v !== INPUT_FIELDS[k] || (INPUT_FIELDS[k] === 'object' && (v === null || Array.isArray(v)))) e.push(`input.${k} must be ${INPUT_FIELDS[k]}`);
    }
    if (i.contact !== undefined && i.contact && typeof i.contact.known !== 'boolean') e.push('input.contact.known must be boolean');
    if (i.callerVerified !== undefined && !['passed', 'failed', 'none'].includes(i.callerVerified)) e.push('input.callerVerified invalid');
    if (i.debug !== undefined) e.push('input.debug is added by the harness, not stored');
  }
  return e;
}

export function validateDataset(d) {
  const errors = [];
  if (!d || ![1, 2].includes(d.schema) || !Array.isArray(d.cases)) return ['dataset must be {schema: 1|2, cases: [...]}'];
  const seen = new Set();
  d.cases.forEach((c, n) => {
    for (const p of caseErrors(c)) errors.push(`case ${n} (${c?.id}): ${p}`);
    if (seen.has(c?.id)) errors.push(`duplicate id ${c.id}`); seen.add(c?.id);
  });
  return errors;
}

export function validateRun(r) {
  const errors = [];
  if (!r || r.schema !== 1 || !Array.isArray(r.results)) return ['run must be {schema: 1, results: [...]}'];
  if (!['route', 'classify'].includes(r.endpoint)) errors.push('endpoint must be route or classify');
  for (const x of r.results) {
    if (typeof x.id !== 'string') errors.push('result without id');
    if (!Array.isArray(x.attempts)) errors.push(`result ${x.id} without attempts`);
  }
  return errors;
}

/** Extra checks for schema 2 cases: context with turns and timestamps. */
export function v2Errors(c) {
  const e = [], x = c.context;
  if (!x || typeof x !== 'object') return ['context missing'];
  if (!Array.isArray(x.turns)) e.push('context.turns must be an array');
  else for (const t of x.turns) {
    if (typeof t.isOwner !== 'boolean' || !t.speaker || !Array.isArray(t.messages) || !t.messages.length) { e.push('bad turn'); break; }
    if (t.messages.some(m => !m.at || Number.isNaN(Date.parse(m.at)))) { e.push('turn message without timestamp'); break; }
  }
  if (!c.at || Number.isNaN(Date.parse(c.at))) e.push('at missing');
  if (c.kind === 'beeper' && !(x.target?.at && x.chat)) e.push('beeper context.target/chat missing');
  if ((c.kind === 't3' || c.kind === 'hermes') && !(x.prompt?.at && x.response?.at)) e.push(c.kind + ' context.prompt/response with timestamps missing');
  return e;
}
