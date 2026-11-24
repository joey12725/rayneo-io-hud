// POST /notify/route and POST /notify/hermes: deterministic policy (lib/notify-policy.mjs) first, then the classifier
// model for semantic cases, with conversation context (lib/notify-context.mjs) and a deterministic guard on its answer.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chat } from './proxy.mjs';
import { resolveChat } from './beeper.mjs';
import { policy, fallback, openFor, ROUTES, ceiling, capped, policyFor } from './notify-policy.mjs';
import { persona, ownerName, assistantName, ownerPattern } from './persona.mjs';
import { beeperContext, recordDecision, renderTurns, relTime, clockLabel, guard, safeText, relationshipFor, relationshipLine, cleanRelationship } from './notify-context.mjs';

const PROMPT_FILE = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'prompts', 'notify-route.md');
/**
 * The prompt and its version id (first line "version: <id>"), with {{owner}}, {{assistant}}, {{worker}} and the owner's
 * own standard ({{standard}}, notify.standard) filled in. Read once per file; the eval harness reports the version.
 */
export function classifierPrompt(file = PROMPT_FILE, ctx = null) {
  const raw = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
  const match = /^version:\s*(\S+)\s*\n/.exec(raw);
  const standard = String(ctx?.config?.notifyStandard ?? '').trim();
  const text = persona((match ? raw.slice(match[0].length) : raw).trim(), ctx, { standard: standard ? `\n${persona("{{owner}}'s own standard, in their words", ctx)}:\n"${standard}"\n` : '' });
  return { version: match?.[1] ?? 'unversioned', text };
}
const prompts = new WeakMap();
const prompt = (ctx = null) => {
  if (!ctx) return classifierPrompt();
  if (!prompts.has(ctx)) prompts.set(ctx, classifierPrompt(ctx.config?.notifyPromptFile || PROMPT_FILE, ctx));
  return prompts.get(ctx);
};

export const T3_WATCH = 't3-watch';
export const watchKey = (machine, id) => `${machine}:${id}`;
// A row with `expiresAt` is an automatic watch (a thread created, messaged or answered from the glasses, 24 h);
// a row without it is an explicit watch (X in a thread > Watch) and never expires.
export const AUTO_WATCH_MS = 24 * 3600000;
const live = (ctx, row) => !row.expiresAt || Date.parse(row.expiresAt) > ctx.now();
const watchedRows = (ctx) => { try { return ctx.store.all(T3_WATCH).filter(r => live(ctx, r)); } catch { return []; } };
/** Drops expired automatic watches. Returns how many were removed. */
export function pruneWatches(ctx) {
  let removed = 0;
  try { for (const row of ctx.store.all(T3_WATCH)) if (!live(ctx, row)) { ctx.store.del(T3_WATCH, row.id); removed++; } } catch {}
  return removed;
}
/** Watches a thread for 24 h from now (extends an automatic watch; an explicit watch stays explicit). */
export function autoWatch(ctx, machine, threadId, title = '') {
  if (!machine || !threadId) return null;
  const key = watchKey(machine, threadId), old = ctx.store.get(T3_WATCH, key);
  if (old && !old.expiresAt) return old;
  return ctx.store.put(T3_WATCH, { id: key, machine, threadId, title: String(title || old?.title || '').slice(0, 200), createdAt: old?.createdAt ?? new Date(ctx.now()).toISOString(),
    expiresAt: new Date(ctx.now() + AUTO_WATCH_MS).toISOString(), auto: true });
}
export const isWatched = (ctx, key) => watchedRows(ctx).some(r => r.id === key);
function t3Facts(ctx) {
  const rows = watchedRows(ctx);
  const keys = new Set(rows.map(r => r.id));
  return {
    watched: (key) => keys.has(key),
    // The T3 Code Android app's notifications carry no thread id: match a watched thread by its title.
    matchWatched: (value) => {
      const hay = String(value ?? '').toLowerCase();
      const hits = rows.filter(r => typeof r.title === 'string' && r.title.trim().length >= 4 && hay.includes(r.title.trim().toLowerCase()));
      return hits.length === 1 ? hits[0].id : null;
    },
  };
}

async function beeperFacts(ctx, n) {
  let timer;
  const timeout = new Promise(resolve => { timer = setTimeout(() => resolve(null), ctx.config.resolveTimeoutMs ?? 1500); });
  try {
    return await Promise.race([resolveChat(ctx, { title: n.title, conversationTitle: n.conversationTitle, sender: n.sender, text: n.body, network: n.network, isGroup: n.isGroup }).catch(() => null), timeout]);
  } finally { clearTimeout(timer); }
}

/** Strict validation of the model's JSON: exactly route, importance, reason. */
export function validVerdict(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const keys = Object.keys(value);
  if (keys.some(k => !['route', 'importance', 'reason'].includes(k))) return null;
  if (!ROUTES.includes(value.route)) return null;
  if (typeof value.importance !== 'number' || !Number.isFinite(value.importance) || value.importance < 0 || value.importance > 1) return null;
  if (value.reason !== undefined && typeof value.reason !== 'string') return null;
  const reason = String(value.reason ?? '').replace(/\s+/g, ' ').trim().slice(0, 80) || 'model';
  // A popup the model itself rates below 0.6 is not worth interrupting for.
  const route = value.route === 'popup' && value.importance < 0.6 ? 'stream' : value.route;
  return { route, importance: value.importance, reason };
}


const one = (v, max = 200) => String(v ?? '').replace(/\s+/g, ' ').trim().slice(0, max);
const safeBlock = safeText;
/** Keeps the end of a long text (the summary or the question sits at the end). */
const tailOf = (s, max) => { s = String(s ?? '').trim(); return s.length > max ? '…' + s.slice(-(max - 1)) : s; };

/**
 * The classifier's user message: a facts header, the delimited context block (earlier turns with relative times, speakers and
 * already-notified markers), the target message, and for T3/Hermes the prompt and the response.
 */
export function classifierMessage(n, facts = {}, c = null, { tz = 'UTC', now = Date.now(), owner = 'the user', assistant = 'Hermes' } = {}) {
  const ref = c?.target?.at ?? c?.response?.at ?? new Date(now).toISOString();
  const out = [`KIND: ${n.kind}`, `LOCAL TIME OF THE ${n.kind === 'beeper' ? 'MESSAGE' : 'EVENT'}: ${clockLabel(ref, tz)} (${tz})`];
  const chatFacts = facts.chat?.resolved ? facts.chat : null;
  if (n.kind === 'beeper') {
    const group = chatFacts ? chatFacts.isGroup : n.isGroup;
    out.push(`CHAT: ${group ? `group chat "${one(n.conversationTitle || n.title, 120)}"` : 'one-to-one chat'} on ${one(chatFacts?.network || n.network || 'Beeper', 60)}${chatFacts && chatFacts.mentions > 0 ? ` (${owner} is @mentioned)` : ''}`);
    const rel = c?.relationship ?? facts.relationship;
    if (rel) out.push(`RELATIONSHIP: ${relationshipLine(rel, ref, { isGroup: group, owner })}`);
  } else if (n.kind === 't3') {
    out.push(`T3 CODE THREAD: "${one(c?.threadTitle || n.title, 160)}" in project "${one(c?.project || n.conversationTitle, 120)}"`, `RUN STATE: ${one(c?.status || n.category, 40) || 'unknown'}${n.body && !c?.response ? ` · ${one(n.body, 200)}` : ''}`);
    const run = runLength(c);
    if (run !== null) out.push(`RUN LENGTH: ${run < 60000 ? '<1 min' : Math.round(run / 60000) + ' min'}${run > (facts.policy?.t3LongRunMinutes ?? 5) * 60000 ? ' (a long run)' : ''}`);
  } else if (n.kind === 'hermes') {
    out.push(`HERMES CHANNEL: ${one(c?.platform || String(n.source).replace(/^hermes:/, ''), 40)} · ${c?.isDM ? 'direct message' : `"${one(c?.chat || n.title, 120)}"`}${c?.job ? ` · scheduled job "${one(c.job, 80)}"` : ''}`, `EXCHANGE TYPE: ${one(n.category, 40) || 'reply'}`);
  } else {
    out.push(`SOURCE: ${one(n.source, 100)} (${one(n.app, 60)})${n.channelId ? ` channel ${one(n.channelId, 60)}` : ''}${n.category ? ` category ${one(n.category, 40)}` : ''}`);
  }
  if (c) out.push('', `CONTEXT (earlier turns, oldest first; times relative to the ${n.kind === 'beeper' ? 'target message' : 'response'})`, renderTurns(c.turns, ref, { owner }), 'END CONTEXT');
  if (n.kind === 't3' || n.kind === 'hermes') {
    const p = c?.prompt, r = c?.response;
    const by = !p ? '' : p.agentWritten ? `written by an agent, NOT by ${owner}` : p.isOwner === false ? `from ${one(p.sender || 'someone else', 60)}, not ${owner}` : `from ${owner}`;
    out.push('', `PROMPT (${p ? `${by}; ${relTime(p.at, ref)} before the response` : 'not available'})`, p ? safeBlock(tailOf(p.text, 1500)) : '(none)', 'END PROMPT');
    out.push('', `RESPONSE (${n.kind === 't3' ? "the agent's final message" : `${assistant}'s reply`}; ${clockLabel(r?.at ?? ref, tz)})`, safeBlock(tailOf(r?.text || n.body, 2500)), 'END RESPONSE');
  } else {
    const t = c?.target;
    const from = n.kind === 'beeper' ? one(t?.sender || n.sender || n.title, 80) : one(n.title, 120);
    const body = t?.text || [n.kind === 'beeper' ? '' : n.title, n.body].filter(Boolean).join(' · ');
    out.push('', 'TARGET MESSAGE (decide this one)', `from: ${from}`, `text: ${safeBlock(body)}`, 'END TARGET');
  }
  return out.join('\n');
}

async function askClassifier(ctx, n, facts, context, { timeoutMs = ctx.config.notifyTimeoutMs ?? 4000 } = {}) {
  const p = prompt(ctx);
  const model = ctx.config.models.classifier ?? ctx.config.models.fast;
  const messages = [{ role: 'system', content: p.text }, { role: 'user', content: classifierMessage(n, facts, context, { tz: ctx.config.tz, now: ctx.now(), owner: ownerName(ctx), assistant: assistantName(ctx) }) }];
  let raw = null;
  const options = { json: true, timeoutMs, onRaw: (content) => { raw = content; } };
  // Deterministic in practice: temperature 0 unless the proxy has refused it once (then it is left out).
  const temperature = ctx.classifierNoTemperature ? undefined : (ctx.config.classifierTemperature ?? 0);
  let result;
  try { result = await chat(ctx, model, messages, { ...options, temperature }); }
  catch (e) {
    if (temperature === undefined || e?.detail?.upstreamStatus !== 400) throw e;
    ctx.classifierNoTemperature = true;
    result = await chat(ctx, model, messages, options);
  }
  return { result, raw, model, version: p.version };
}

// The T3 stage rule that only stands in for the model when the run's prompt and response are available: a finished run.
// A run that needs the owner's input (approval, question, choice) always pops up, so its rule is final.
const T3_STAGE_RULES = new Set(['t3.done']);
/** How long the run took (prompt to response), from the context; null when unknown. */
export function runLength(c) {
  if (typeof c?.runMs === 'number') return c.runMs;
  const a = Date.parse(c?.prompt?.at ?? ''), b = Date.parse(c?.response?.at ?? '');
  return Number.isFinite(a) && Number.isFinite(b) && b >= a ? b - a : null;
}
/** A configured own community/automation chat (NOTIFY_OWN_CHATS substrings of the chat name). */
function ownChat(ctx, n, chat) {
  const list = ctx.config.notifyOwnChats ?? [];
  if (!list.length) return false;
  const names = [chat?.name, n.conversationTitle, n.isGroup ? n.title : ''].filter(Boolean).map(x => String(x).toLowerCase());
  return list.some(w => names.some(x => x.includes(w)));
}

/**
 * The whole route decision. Never throws for model or upstream failures.
 * `extra`: facts only an in-process caller supplies (the T3 notifier's needsAny and run context, the Hermes route's
 * context and decisionKey); never from a request body. `n.context` is an eval-supplied context, honoured only with debug.
 */
export async function routeNotification(ctx, n, extra = {}) {
  const started = Date.now();
  const { context: suppliedContext, decisionKey: suppliedKey, ...rest } = extra;
  const base = { policy: policyFor(ctx), ownerPattern: ownerPattern(ctx) };
  const facts = n.kind === 't3' ? { ...base, ...t3Facts(ctx), ...rest } : { ...base, ...rest };
  if (n.kind === 'beeper') {
    facts.chat = await beeperFacts(ctx, n);
    facts.ownChat = ownChat(ctx, n, facts.chat);
    // How much the owner talks with this chat (volume only). An eval supplies it in its context (debug only).
    const supplied = n.debug ? cleanRelationship(n.context?.relationship ?? suppliedContext?.relationship) : null;
    if (supplied) facts.relationship = supplied;
    else if (facts.chat?.resolved) facts.relationship = await relationshipFor(ctx, facts.chat.chatId).catch(() => null);
  }
  const key = suppliedKey ?? (n.kind === 'beeper' && facts.chat?.chatId ? 'beeper:' + facts.chat.chatId : null);
  // One conversation is decided one message at a time: in a burst (five messages in a second) each message must see
  // the decision the one before it got, or every one of them would pop up. The wait is bounded (phone budget).
  if (!key || n.debug) return decide(ctx, n, facts, { started, suppliedContext, key });
  const release = await chatLock(ctx, key, ctx.config.routeLockWaitMs ?? 3000);
  try { return await decide(ctx, n, facts, { started, suppliedContext, key }); } finally { release(); }
}

/** Per-conversation mutex; waits at most `maxWaitMs` for the previous holder, then goes ahead. Returns release(). */
function chatLock(ctx, key, maxWaitMs) {
  const locks = ctx.notifyLocks ??= new Map();
  const prev = locks.get(key) ?? Promise.resolve();
  let release;
  const mine = new Promise(resolve => { release = resolve; });
  const tail = prev.then(() => mine);
  locks.set(key, tail);
  let timer;
  const waited = Promise.race([prev, new Promise(resolve => { timer = setTimeout(resolve, maxWaitMs); })]).finally(() => clearTimeout(timer));
  return waited.then(() => () => { release(); if (locks.get(key) === tail) locks.delete(key); });
}

async function decide(ctx, n, facts, { started, suppliedContext, key }) {
  // The phone waits NOTIFY_ROUTE_TIMEOUT_MS (6 s) for the whole call; production keeps context + the model inside that.
  const budget = n.debug ? Infinity : (ctx.config.routeBudgetMs ?? 5500);
  const left = () => budget - (Date.now() - started);
  let context = suppliedContext ?? (n.debug ? n.context ?? null : null);
  const rule = policy(n, facts);
  // With a T3 run's prompt and response, the model judges the content; the stage rule is its fallback.
  const t3Semantic = n.kind === 't3' && !!rule && T3_STAGE_RULES.has(rule.reason) && !!context?.response?.text;
  const cap = rule && !t3Semantic ? null : ceiling(n);
  let out = t3Semantic ? null : rule, debug, guarded = null;
  if (!out) {
    if (n.kind === 'beeper' && !context && facts.chat?.resolved) context = await beeperContext(ctx, n, facts.chat, { timeoutMs: Math.max(300, Math.min(ctx.config.contextTimeoutMs ?? 1000, left() - 2500)) }).catch(() => null);
    if (n.kind === 'beeper' && context && facts.relationship && !context.relationship) context = { ...context, relationship: facts.relationship };
    let verdict;
    try {
      verdict = await askClassifier(ctx, n, facts, context, { timeoutMs: Math.max(1500, Math.min(ctx.config.notifyTimeoutMs ?? 4000, left())) });
      const valid = validVerdict(verdict.result);
      if (!valid) throw new Error('invalid');
      const open = t3Semantic ? rule.open : openFor(n, facts);
      out = { ...valid, by: 'model', ...(open ? { open } : {}) };
      if (n.kind === 'beeper' && context) {
        const g = guard(out, context, { isGroup: facts.chat?.resolved ? facts.chat.isGroup : n.isGroup, mentions: facts.chat?.mentions ?? 0, ownChat: facts.ownChat, ownerPattern: facts.ownerPattern });
        if (g !== out) { guarded = g.guard; const { guard: _g, ...d } = g; out = d; }
      }
      // A long run that finished is never dropped: the owner wants to know it is done.
      if (t3Semantic && out.route === 'suppress' && (runLength(context) ?? 0) > facts.policy.t3LongRunMinutes * 60000) { guarded = 'guard.t3-long-run'; out = { ...out, route: 'stream', importance: Math.max(out.importance, 0.5) }; }
    } catch (e) {
      out = t3Semantic ? rule : fallback(n, facts);
      if (n.debug) debug = { error: e?.message === 'invalid' ? 'invalid model output' : 'model unavailable' };
    }
    // Ceilings (morning reports, Instagram links, announcement channels): at most stream, whatever the model said.
    const c = capped(out, cap);
    if (c !== out) { guarded = guarded ? guarded + ',' + c.cap : c.cap; const { cap: _c, ...d } = c; out = d; }
    if (n.debug) debug = { ...debug, promptVersion: prompt(ctx).version, model: verdict?.model ?? ctx.config.models.classifier ?? ctx.config.models.fast, raw: verdict?.raw ?? null,
      context: context ? { turns: context.turns?.length ?? 0, messages: (context.turns ?? []).reduce((s, t) => s + t.messages.length, 0), notified: (context.turns ?? []).some(t => t.messages.some(m => m.priorRoute)), targetMatched: !!context.target?.id } : null,
      ...(guarded ? { guard: guarded } : {}), ...(t3Semantic ? { stageRule: rule.reason } : {}) };
  }
  // Remember what this message got, so the next one in the conversation knows (never for an eval/debug call).
  if (key && !n.debug) recordDecision(ctx, key, { messageId: context?.target?.id, at: context?.target?.at ?? context?.response?.at, route: out.route, importance: out.importance, sender: context?.target?.sender });
  const ms = Date.now() - started;
  // One line per decision, never any notification text (the model's free-text reason may paraphrase the message, so it is not logged).
  try { ctx.log('notify', `route=${out.route} by=${out.by} kind=${n.kind} src=${String(n.source).replace(/\s+/g, '_').slice(0, 60) || '-'} ch=${String(n.channelId).replace(/\s+/g, '_').slice(0, 40) || '-'} reason=${out.by === 'model' ? 'model' : String(out.reason).replace(/\s+/g, '_').slice(0, 40)}${guarded ? ' guard=' + guarded : ''}${context ? ' ctx=' + (context.turns?.length ?? 0) : ''} ms=${ms}${out.by === 'model' || debug ? ' v=' + prompt(ctx).version : ''}`); } catch {}
  return n.debug ? { ...out, debug: { ...debug, ms, promptVersion: prompt(ctx).version, resolved: facts.chat ? { resolved: !!facts.chat.resolved, match: facts.chat.match ?? null, reason: facts.chat.reason ?? null } : null } } : out;
}
