// Notification routing policy. Deterministic, pure and synchronous, so the phone can mirror it offline in Kotlin
// (android/.../data/NotificationRules.kt). Both sides are tested against the same table:
// server/gateway/test/fixtures/notify-policy.json. Change one, change the other and the fixture.
//
// The tables (which kinds are on, package names, the deny list, the optional dating preset, allowed apps) are data:
// notify-policy.default.json, replaced by the file at notify.policyFile (NOTIFY_POLICY_FILE). The text patterns below
// are generic and stay in code. The owner's names (owner.name / owner.aliases) come in through facts.ownerPattern.
//
// Output of every decision: { route: 'suppress'|'stream'|'popup', importance, reason, by: 'rule'|'model'|'fallback', open? }.
// policy() returns null for a semantic case (ask the classifier model); fallback() is the answer when the model is
// unavailable and never pops up.
import fs from 'node:fs';
import { HttpError } from '../../../lib/http.mjs';

// 'joy' is accepted as an older name for 'assistant'.
export const KINDS = ['beeper', 't3', 'call', 'assistant', 'joy', 'calendar', 'clock', 'app', 'hermes'];
export const ROUTES = ['suppress', 'stream', 'popup'];
const VERIFIED = ['passed', 'failed', 'none'];
const APP_ROUTES = { popup: 0.8, stream: 0.5, suppress: 0 };

const strings = v => Array.isArray(v) && v.every(x => typeof x === 'string');
/** Validates a policy document (merged over the default) and returns it; throws an Error listing every problem. */
export function normalizePolicy(doc, base = null) {
  const problems = [], where = k => `notify policy: ${k}`;
  if (!doc || typeof doc !== 'object' || Array.isArray(doc)) throw new Error('notify policy must be a JSON object');
  const known = ['$comment', '$schema', 'schema', 'kinds', 'packages', 'apps', 'deny', 'beeperNoticeChannels', 't3LongRunMinutes', 'dating'];
  for (const k of Object.keys(doc)) if (!known.includes(k)) problems.push(where(`unknown key "${k}"`));
  const p = {
    schema: 1,
    kinds: { ...(base?.kinds ?? {}), ...(doc.kinds ?? {}) },
    packages: { ...(base?.packages ?? {}), ...(doc.packages ?? {}) },
    apps: { ...(base?.apps ?? {}), ...(doc.apps ?? {}) },
    deny: doc.deny ?? base?.deny ?? {},
    beeperNoticeChannels: doc.beeperNoticeChannels ?? base?.beeperNoticeChannels ?? [],
    t3LongRunMinutes: doc.t3LongRunMinutes ?? base?.t3LongRunMinutes ?? 5,
    dating: { enabled: doc.dating?.enabled ?? base?.dating?.enabled ?? false, apps: doc.dating?.apps ?? base?.dating?.apps ?? {} },
  };
  if (doc.schema !== undefined && doc.schema !== 1) problems.push(where('schema must be 1'));
  for (const [k, v] of Object.entries(p.kinds)) if (typeof v !== 'boolean') problems.push(where(`kinds.${k} must be true or false`));
  for (const k of ['beeper', 't3', 'dialer']) if (!strings(p.packages[k] ?? [])) problems.push(where(`packages.${k} must be a list of package names`));
  for (const [k, v] of Object.entries(p.apps)) if (!(v in APP_ROUTES)) problems.push(where(`apps["${k}"] must be popup, stream or suppress`));
  for (const [k, v] of Object.entries(p.deny)) if (!strings(v)) problems.push(where(`deny.${k} must be a list of package names`));
  if (!strings(p.beeperNoticeChannels)) problems.push(where('beeperNoticeChannels must be a list of channel ids'));
  if (typeof p.t3LongRunMinutes !== 'number' || p.t3LongRunMinutes < 0) problems.push(where('t3LongRunMinutes must be a number'));
  if (typeof p.dating.enabled !== 'boolean') problems.push(where('dating.enabled must be true or false'));
  for (const [pkg, t] of Object.entries(p.dating.apps)) {
    if (!t || typeof t !== 'object') { problems.push(where(`dating.apps["${pkg}"] must be an object`)); continue; }
    for (const k of Object.keys(t)) if (!['match', 'like', 'message', 'deny'].includes(k) || !strings(t[k])) problems.push(where(`dating.apps["${pkg}"].${k} must be one of match/like/message/deny with a list of channel ids`));
  }
  if (problems.length) throw new Error(problems.join('\n'));
  p.packages.beeper ??= []; p.packages.t3 ??= []; p.packages.dialer ??= [];
  return p;
}
export const DEFAULT_POLICY_FILE = new URL('../notify-policy.default.json', import.meta.url);
export const DEFAULT_POLICY = Object.freeze(normalizePolicy(JSON.parse(fs.readFileSync(DEFAULT_POLICY_FILE, 'utf8'))));
const loaded = new Map();
/** The policy for this gateway: the default, or notify.policyFile merged over it (read once per file). */
export function policyFor(ctx) {
  const file = ctx?.config?.notifyPolicyFile;
  if (!file) return DEFAULT_POLICY;
  if (!loaded.has(file)) {
    let doc;
    try { doc = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { throw new Error(`notify policy file ${file} cannot be read as JSON (${e.code ?? e.message})`); }
    loaded.set(file, Object.freeze(normalizePolicy(doc, DEFAULT_POLICY)));
  }
  return loaded.get(file);
}

// Back-compatible views of the default tables.
export const BEEPER_PACKAGE = DEFAULT_POLICY.packages.beeper[0];
export const T3_PACKAGES = DEFAULT_POLICY.packages.t3;
export const DIALER_PACKAGES = DEFAULT_POLICY.packages.dialer;
export const DATING = DEFAULT_POLICY.dating.apps;
export const DENY = DEFAULT_POLICY.deny;

// ---- Text patterns (keep identical to NotificationRules.kt) ----
export const RX = {
  // A bare short-video or Instagram post share (the whole message), or Beeper's stub for one. Ordinary links (a Reddit
  // thread, an article, a YouTube video) are not shares: people he knows sharing those is at least stream.
  share: /^(?:https?:\/\/)?(?:www\.|m\.|vm\.|vt\.)?(?:instagram\.com\/(?:reels?|p|tv|share|stories)\/|tiktok\.com\/|youtube\.com\/shorts\/|facebook\.com\/(?:reels?|share\/[rv])\/|fb\.watch\/)\S*$|^(?: )?sent an? (?:reel|post|story|tiktok|short)\b.*$|^(?: )?shared an? (?:reel|post|story|tiktok|short)\b.*$/i,
  // A short-video link anywhere in the message (with a few words around it).
  shortVideo: /(?:https?:\/\/)?(?:www\.|m\.|vm\.|vt\.)?(?:instagram\.com\/(?:reels?|tv)\/|tiktok\.com\/|youtube\.com\/shorts\/|facebook\.com\/reels?\/|fb\.watch\/)\S*/i,
  instagramLink: /(?:https?:\/\/)?(?:www\.|m\.)?(?:instagram\.com|instagr\.am)\/\S*/i,
  url: /https?:\/\/\S+|\b(?:www\.)?[a-z0-9-]+\.(?:com|org|net|io|co|be|gg|tv|me|ly|app|dev)\/\S*/gi,
  // Reactions to the owner's story never pop up; a reply to it is a real message (at most stream).
  storyReaction: /^(?:[\uE000-\uF8FF] )?(?:reacted(?: \S{1,8})? to your story|liked your story|mentioned you in (?:their|a|his|her) story|reacted to (?:their|a) story)/i,
  storyReply: /^(?:[\uE000-\uF8FF] )?(?:replied to your story|story reply)\b/i,
  media: /^(?:sent (?:a |an )?(?:photo|picture|image|video|gif|sticker|attachment|file|voice message)|photo|image|video|gif|sticker|voice message)(?: \(\d+:\d\d\))?[.!]?$/i,
  reaction: /^(?:reacted\b|liked\s+["“]|loved\s+["“]|laughed at\b|emphasized\b|disliked\b|questioned\b)/i,
  banter: /^(?:lol|lmao|haha+|ha|😂+|👍|❤️|nice|same|\+1|ok|k|yes|no|true|fr|bet)[!.\s]*$/iu,
  // One-time codes, 2FA, verification and security codes (the owner opens Beeper when they need one). A code a friend
  // mentions ("the door code is 4821") is not one: the code must be named as a login/verification/security code.
  otp: /\b\d{4,8}\b.*\b(?:passcode|otp|verification|verify|one[- ]time|2fa)\b|\b(?:passcode|otp|verification|one[- ]time|2fa)\b.*\b\d{4,8}\b|\b(?:verification|security|login|log[- ]in|sign[- ]?in|2fa|two[- ]factor|one[- ]time|authenticat(?:ion|or)|access|otp|pass) ?code\b|\byour (?:[\w'-]+ ){0,3}code (?:is|:)|\bdo(?: not|n'?t) share (?:this|the|your) code\b|\buse (?:code )?\d{4,8} to (?:verify|sign in|log in|confirm|continue)\b|\bis your [\w .'-]{0,30} code\b|\b[A-Z]-\d{6}\b/i,
  // A chat that carries an app's product announcements or release/update posts (at most stream).
  announcements: /(?:^|[#\s|·:/(\[-])(?:announcements?|announce|updates?|changelogs?|release[- ]?notes|releases|patch[- ]?notes|product[- ](?:news|updates?)|news(?:letter)?)(?:$|[\s|·:/)\]-])/i,
  // A morning report, brief or digest (at most stream: he reads it when he gets to it).
  morningReport: /\bmorning (?:report|brief(?:ing)?|digest|summary|update|roundup|news)\b|\bdaily (?:brief(?:ing)?|digest|report|summary|roundup)\b|\bgood morning[,!.]? (?:here'?s|this is) (?:your|today'?s|the)\b/i,
  promo: /unsubscribe|newsletter|limited[- ]time offer|\bpromo(?:tion)?\b|\bsale\b|discount|\b\d+% off\b|reply stop/i,
  datingMatch: /\bit'?s a match\b|\bnew match\b|\byou(?: have| got)? (?:a )?(?:new )?match\b|\bmatched with\b|\byou matched\b|\bmutual match\b/i,
  datingMessage: /\bsent you a (?:new )?message\b|\bnew message\b|\bmessaged you\b|\breplied to you\b|\bwrote to you\b|\bsent you a (?:photo|voice|video)\b/i,
  datingLike: /\blikes? you\b|\bliked you\b|\bnew likes?\b|\bsomeone liked\b|\bsuper ?liked you\b|\bliked your (?:profile|photo|prompt)\b|\bpinged you\b|\bsent you a ping\b/i,
  datingPromo: /\bboost\b|\bupgrade\b|\bpremium\b|\btinder (?:gold|platinum|plus)\b|\bsubscri|\bdiscount|\b\d+% off\b|\boffer\b|\blimited time\b|\bdon'?t miss\b|\bcome back\b|\bwe miss you\b|\btop picks?\b|\brecommended\b|\bpicks? for you\b|\bpeople (?:near|around) you\b|\bnew people\b|\bcomplete your profile\b|\bprofile tips?\b|\badd (?:a |more )?photos?\b|\bupdate your profile\b|\bkeep swiping\b|\bstart swiping\b|\bdaily picks\b|\bstandouts\b|\bspotlight\b|\bit'?s been a while\b|\bcheck out who\b|\bsee who likes you\b|\bexpir(?:e|es|ing)\b|\blast chance\b|\bsurvey\b|\brecommendations?\b|\bnearby\b|\bnear you\b|\bin your area\b|\bstreaks?\b|\bmajestic\b|\bswipe surge\b|\bpassport\b/i,
  t3Needs: /\bneeds? (?:you|your)\b|\bapprov|\bpermission\b|\bquestion\b|\bwaiting for (?:you|input|your)\b|\binput (?:required|needed)\b|\brequest(?:ed|s)? (?:input|approval)\b/i,
  t3Done: /\bfinished\b|\bcompleted?\b|\bdone\b|\bsucceeded\b/i,
  // Calls: CNAM text carriers use for suspected spam (same list as calls/Calls.kt spamCallerLabel).
  spamCaller: /\b(?:spam|scam|fraud|telemarket\w*|robocall)\b/i,
  // A recognisable local business or service by caller name (CNAM, up to 15 characters, often upper case).
  business: /\b(?:pizz(?:a|eria)|domino'?s|papa ?john'?s|little caesars?|restaurant|grill|kitchen|cafe|bistro|diner|deli|taqueria|tacos?|bbq|barbecue|sushi|burgers?|wings|pho|catering|bakery|doordash|door ?dash|uber ?eats|grubhub|postmates|instacart|deliver(?:y|ies)|courier|ups|fedex|usps|dhl|amazon|post office|apartments?|apts?|leasing|property|properties|management|mgmt|residences?|realty|lofts|pharmacy|cvs|walgreens|clinic|dental|dentist|orthodont\w*|veterinar\w*|animal hospital|tires?|collision|mechanic|plumbing|plumber|hvac|locksmith|towing|salon|barber(?:shop)?|cleaners|laundry|storage|movers|moving|pool|spas?|repair)\b/i,
  // Survey companies, call centres and the usual robocall topics are not businesses in this sense.
  notBusiness: /\b(?:survey|polls?|polling|research|opinion|call ?cent(?:er|re)|telemarket\w*|marketing|warranty|insurance|political|campaign|vote|fundrais\w*|donat\w*|sweepstakes|prize|loans?|debt|credit|refinanc\w*|solar|medicare|health ?plan|toll ?free|unknown|unavailable|private|restricted|spam|scam|fraud|robocall)\b/i,
  // A scheduled job's "nothing to deliver" answer (Hermes' own sentinel): the whole reply, or its first or last line.
  silent: /^\s*\[?(?:SILENT|NO[_ ]REPLY)\]?\s*$|^\s*\[SILENT\]|\[SILENT\]\s*$/i,
};
const UUID = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
/** Android Notification.category values a dating app only uses for marketing (CATEGORY_PROMO, CATEGORY_RECOMMENDATION). */
const DATING_SUPPRESS_CATEGORIES = ['promo', 'recommendation'];
const NEEDS_CATEGORIES = ['needs-you', 'needs_you', 'needs-input', 'needs_input', 'approval', 'input', 'request', 'question', 'choice', 'user-input'];
const DONE_CATEGORIES = ['done', 'finished', 'completed', 'complete', 'error', 'failed', 'limited'];
/** A T3 run longer than this that finishes is at least stream (the owner wants to know a long run is done). */
export const T3_LONG_RUN_MS = DEFAULT_POLICY.t3LongRunMinutes * 60000;
const lower = (v) => String(v ?? '').toLowerCase();

export const decision = (route, importance, reason, extra = {}) => ({ route, importance, reason, by: 'rule', ...extra });

/** Old clients (POST /notify/classify) send no kind; infer it from the source package or label. */
export function inferKind(input, pol = DEFAULT_POLICY, assistant = '') {
  const source = lower(input.source), label = `${source} ${lower(input.app)}`;
  if (pol.packages.beeper.includes(source) || /\bbeeper\b/.test(label)) return 'beeper';
  if (pol.packages.t3.includes(source) || /\bt3(?:[ _-]*code)?\b/.test(label)) return 't3';
  if (/\bhermes\b|^assistant\b|^joy\b/.test(source) || (assistant && source === lower(assistant))) return 'assistant';
  if (pol.packages.dialer.includes(source)) return 'call';
  return 'app';
}

const FIELDS = { kind: 20, source: 200, app: 100, title: 200, body: 1000, conversationTitle: 200, sender: 100, network: 100, chatId: 200, channelId: 100, category: 40, callerVerified: 10, target: 200 };
/** Validates and bounds a POST /notify/route body. Unknown keys are ignored. */
/** Validates and bounds a POST /notify/route body. `pol`: the policy (package names); `assistant`: the assistant's name. */
export function routeInput(b, pol = DEFAULT_POLICY, assistant = '') {
  if (!b || typeof b !== 'object' || Array.isArray(b)) throw new HttpError(400, 'Notification object required');
  const out = {};
  for (const [key, max] of Object.entries(FIELDS)) {
    if (b[key] !== undefined && b[key] !== null && typeof b[key] !== 'string') throw new HttpError(400, 'Notification fields must be strings');
    out[key] = String(b[key] ?? '').slice(0, max);
  }
  const group = b.isGroup ?? b.isGroupConversation;
  if (group !== undefined && typeof group !== 'boolean') throw new HttpError(400, 'Invalid group flag');
  out.isGroup = group === true;
  if (b.debug !== undefined && typeof b.debug !== 'boolean') throw new HttpError(400, 'Invalid debug flag');
  out.debug = b.debug === true;
  // Eval only (server/notify-eval): a T3 case with no real thread can be marked watched; honoured only with debug
  // (the body flag or ?debug=1, which routes/notify.mjs re-checks), so a phone can never bypass the watch list.
  if (b.watched !== undefined && typeof b.watched !== 'boolean') throw new HttpError(400, 'Invalid watched flag');
  out.watched = b.watched === true && out.debug;
  if (b.popup !== undefined && typeof b.popup !== 'boolean') throw new HttpError(400, 'Invalid popup flag');
  out.popup = b.popup === true;
  // Calls: what the phone knows about the number (call log, presentation, carrier verdict). Never the number itself.
  if (b.priorAnsweredCalls !== undefined && (typeof b.priorAnsweredCalls !== 'number' || !Number.isFinite(b.priorAnsweredCalls) || b.priorAnsweredCalls < 0)) throw new HttpError(400, 'Invalid priorAnsweredCalls');
  out.priorAnsweredCalls = Math.min(Math.floor(b.priorAnsweredCalls ?? 0), 10000);
  for (const key of ['ownerCalledNumber', 'privateNumber', 'spam', 'business']) {
    if (b[key] !== undefined && typeof b[key] !== 'boolean') throw new HttpError(400, `Invalid ${key}`);
    out[key] = b[key] === true;
  }
  if (b.contact !== undefined && b.contact !== null) {
    const c = b.contact;
    if (typeof c !== 'object' || Array.isArray(c) || typeof c.known !== 'boolean' || (c.starred !== undefined && typeof c.starred !== 'boolean') || (c.name !== undefined && typeof c.name !== 'string')) throw new HttpError(400, 'Invalid contact');
    out.contact = { known: c.known, ...(c.starred !== undefined ? { starred: c.starred } : {}), ...(c.name !== undefined ? { name: c.name.slice(0, 100) } : {}) };
  } else out.contact = null;
  if (out.kind && !KINDS.includes(out.kind)) throw new HttpError(400, 'Invalid kind');
  if (out.kind === 'joy') out.kind = 'assistant';
  if (out.callerVerified && !VERIFIED.includes(out.callerVerified)) throw new HttpError(400, 'Invalid callerVerified');
  out.callerVerified ||= 'none';
  if (!out.kind) out.kind = inferKind(out, pol, assistant);
  // A phone notification from the T3 Code app or a dialer is that kind, whatever the caller said.
  if (out.kind === 'app' && pol.packages.t3.includes(lower(out.source))) out.kind = 't3';
  if (out.kind === 'app' && pol.packages.beeper.includes(lower(out.source))) out.kind = 'beeper';
  return out;
}

const text = (n) => `${n.title} ${n.body}`.trim();
const bodyOnly = (n) => String(n.body ?? '').trim();

function t3Stage(n) {
  const c = lower(n.category);
  if (NEEDS_CATEGORIES.includes(c)) return 'needs';
  if (DONE_CATEGORIES.includes(c)) return 'done';
  if (RX.t3Needs.test(text(n))) return 'needs';
  if (RX.t3Done.test(text(n))) return 'done';
  return null;
}

/** True when the caller name reads as a local business or service (and not a survey company or call centre). */
export const businessCaller = (name) => RX.business.test(String(name ?? '')) && !RX.notBusiness.test(String(name ?? ''));

/**
 * Call policy. A number the owner has had real conversations with (an answered call from it, or a call they
 * placed to it) pops up whatever else is said about it, except a private/withheld number (no history can be matched);
 * then failed verification and suspected spam are suppressed, saved contacts and recognisable local businesses pop up,
 * and every other unknown number goes to the list. A missed call that would have popped up is stream.
 */
function callRules(n) {
  const missed = ['missed', 'missed_call', 'missed-call'].includes(lower(n.category));
  const recents = { open: { app: 'calls', target: 'recents' } }, calls = { open: { app: 'calls' } };
  const up = (importance, reason) => missed ? decision('stream', 0.8, 'call.missed', recents) : decision('popup', importance, reason, calls);
  if (n.privateNumber) return decision('stream', 0.5, 'call.private', recents);
  if ((n.priorAnsweredCalls ?? 0) > 0 || n.ownerCalledNumber) return up(0.95, 'call.history');
  if (n.callerVerified === 'failed') return decision('suppress', 0, 'call.unverified');
  if (n.spam || (n.contact?.known !== true && RX.spamCaller.test(n.title))) return decision('suppress', 0, 'call.spam');
  if (n.contact?.known === true) return up(0.95, 'call.contact');
  if (n.business || businessCaller(n.title)) return up(0.85, 'call.business');
  return decision('stream', 0.5, 'call.unknown', recents);
}

function datingRules(n, table) {
  const ch = lower(n.channelId), has = (list) => (list ?? []).some(c => lower(c) === ch);
  const all = text(n);
  if (ch && has(table.deny)) return decision('suppress', 0.05, 'dating.channel');
  if (ch && has(table.message)) return decision('popup', 0.8, 'dating.message');
  if (DATING_SUPPRESS_CATEGORIES.includes(lower(n.category))) return decision('suppress', 0.05, 'dating.category');
  if (ch && (has(table.match) || has(table.like)) && RX.datingPromo.test(all)) return decision('suppress', 0.05, 'dating.promo');
  if (ch && has(table.match)) return decision('popup', 0.85, 'dating.match');
  if (ch && has(table.like)) return decision('stream', 0.55, 'dating.like');
  if (RX.datingPromo.test(all)) return decision('suppress', 0.05, 'dating.promo');
  if (RX.datingMatch.test(all)) return decision('popup', 0.85, 'dating.match');
  if (RX.datingMessage.test(all)) return decision('popup', 0.8, 'dating.message');
  if (RX.datingLike.test(all)) return decision('stream', 0.55, 'dating.like');
  return null; // Ambiguous: the model decides, offline it is suppressed.
}

/** Reason slug for an app outside the allow-list. */
export function denyReason(source, pol = DEFAULT_POLICY) {
  const s = String(source ?? '');
  for (const [name, list] of Object.entries(pol.deny)) if (list.includes(s)) return 'deny.' + name;
  return 'deny.default';
}

/**
 * Deterministic rules, default deny. `facts` carries what only the gateway knows:
 *  - chat: a /beeper/resolve result (muted, spam, lowImportance...) or null
 *  - watched(key) -> boolean for a T3 thread key "<machine>:<threadId>"; matchWatched(text) -> key|null
 *  - policy: the policy tables (policyFor(ctx)); ownerPattern: a RegExp matching the owner's names, or null
 * Returns a decision, or null when the case is semantic (ask the model).
 */
export function policy(n, facts = {}) {
  const watched = facts.watched ?? (() => false), pol = facts.policy ?? DEFAULT_POLICY;
  const kind = n.kind === 'joy' ? 'assistant' : n.kind;
  if (kind !== 'app' && pol.kinds[kind] === false) return decision('suppress', 0, 'kind.disabled');
  switch (kind) {
    case 'assistant': {
      // The assistant wrote it on purpose. The open target is the phone's assistant screen (screen id "joy").
      const popup = n.popup || lower(n.category) === 'popup';
      return decision(popup ? 'popup' : 'stream', popup ? 0.9 : 0.7, popup ? 'assistant.popup' : 'assistant.stream', { open: { app: 'joy', ...(n.target ? { target: n.target } : {}) } });
    }
    case 'call': return callRules(n);
    case 'calendar': {
      const reminder = ['reminder', 'starting', 'alert', 'event'].includes(lower(n.category));
      return decision(reminder ? 'popup' : 'stream', reminder ? 0.8 : 0.5, reminder ? 'calendar.reminder' : 'calendar.update', { open: { app: 'calendar', ...(n.target ? { target: n.target } : {}) } });
    }
    case 'clock': {
      const ringing = ['alarm', 'timer', 'ringing'].includes(lower(n.category));
      return decision(ringing ? 'popup' : 'stream', ringing ? 0.95 : 0.5, ringing ? 'clock.ringing' : 'clock.update', { open: { app: 'clock', ...(n.target ? { target: n.target } : {}) } });
    }
    case 'hermes': {
      // The assistant's Hermes exchanges on every channel except the glasses (POST /notify/hermes, from the glasses-notify plugin).
      const open = { app: 'joy' };
      const platform = lower(n.source).replace(/^hermes:/, '');
      if (['api_server', 'glasses'].includes(platform)) return decision('suppress', 0, 'hermes.glasses', { open });
      if (!bodyOnly(n)) return decision('suppress', 0, 'hermes.empty', { open });
      if (RX.silent.test(bodyOnly(n))) return decision('suppress', 0, 'hermes.silent', { open });
      if (lower(n.category) === 'local') return decision('suppress', 0, 'hermes.undelivered', { open });
      return null;
    }
    case 't3': {
      const keyed = n.chatId || (/^[^:\s]+:[^:\s]+$/.test(n.source) ? n.source : '');
      // The T3 Code app's own phone notifications: the gateway's T3 notifier (lib/t3-notify.mjs) owns T3 now.
      if (!keyed && !n.watched && pol.packages.t3.includes(lower(n.source))) return decision('suppress', 0, 't3.app');
      const key = keyed || (facts.matchWatched?.(text(n)) ?? '');
      const stage = t3Stage(n);
      // n.watched: an eval case marked watched (debug only, see routeInput), so the content is what is measured.
      if (!n.watched && (!key || !watched(key))) {
        // "Notify for all threads needing me" (the notifier passes facts.needsAny): needs-you on any thread pops up.
        if (key && facts.needsAny && stage === 'needs') return decision('popup', 0.9, 't3.needs-you.all', { open: { app: 't3', target: key } });
        return decision('suppress', 0, 't3.unwatched');
      }
      if (!key) return stage === 'needs' ? decision('popup', 0.9, 't3.needs-you') : stage === 'done' ? decision('stream', 0.6, 't3.done') : null;
      const open = { app: 't3', target: key };
      if (stage === 'needs') return decision('popup', 0.9, 't3.needs-you', { open });
      if (stage === 'done') return decision('stream', 0.6, 't3.done', { open });
      return null;
    }
    case 'beeper': {
      const ch = n.channelId;
      if (ch && pol.beeperNoticeChannels.includes(ch)) return decision('suppress', 0, 'beeper.notice');
      const body = bodyOnly(n), all = text(n);
      if (!body && !n.title) return decision('suppress', 0, 'beeper.empty');
      const open = facts.chat?.chatId ? { app: 'beeper', target: facts.chat.chatId } : { app: 'beeper' };
      if (facts.chat?.resolved) {
        if (facts.chat.muted && !(facts.chat.mentions > 0)) return decision('suppress', 0, 'beeper.muted', { open });
        if (facts.chat.spam?.spam) return decision('suppress', 0, 'beeper.spam', { open });
      }
      if (RX.share.test(body) || shortVideoShare(body)) return decision('suppress', 0.05, 'beeper.share', { open });
      if (RX.storyReaction.test(body)) return decision('suppress', 0.05, 'beeper.story', { open });
      if (RX.reaction.test(body)) return decision('suppress', 0.05, 'beeper.reaction', { open });
      if (n.isGroup && RX.banter.test(body)) return decision('suppress', 0.05, 'beeper.banter', { open });
      if (RX.media.test(body)) return n.isGroup ? decision('suppress', 0.05, 'beeper.media', { open }) : decision('stream', 0.4, 'beeper.media', { open });
      if (RX.otp.test(all) || (facts.chat?.resolved && facts.chat.reason === 'otp')) return decision('suppress', 0.05, 'beeper.otp', { open });
      if (facts.chat?.lowImportance) return decision('stream', 0.3, 'beeper.transactional', { open });
      // The owner's own community and automation chats are never promotions (a chat they write in, or one configured as
      // theirs), and neither is a message that names the owner.
      if (RX.promo.test(all) && !facts.ownerPattern?.test(body) && !facts.ownChat && !engaged(facts.relationship)) return decision('suppress', 0.05, 'beeper.promo', { open });
      return null;
    }
    default: {
      const source = lower(n.source);
      const dating = pol.dating.enabled ? Object.entries(pol.dating.apps).find(([pkg]) => lower(pkg) === source)?.[1] : null;
      if (dating) return datingRules(n, dating);
      if (pol.packages.dialer.includes(source)) return decision('suppress', 0, 'call.dialer');
      const allowed = Object.entries(pol.apps).find(([pkg]) => lower(pkg) === source)?.[1];
      if (allowed) return decision(allowed, APP_ROUTES[allowed], 'app.' + allowed);
      return decision('suppress', 0, denyReason(n.source, pol));
    }
  }
}

/** A short-video link with only a few words around it and no question: a share, not a message. */
export function shortVideoShare(body) {
  const b = String(body ?? '');
  if (!RX.shortVideo.test(b)) return false;
  const rest = b.replace(RX.url, ' ').replace(/\s+/g, ' ').trim();
  return rest.length <= 40 && !/\?/.test(rest);
}
/** The owner writes in this chat (relationship stats from the gateway): their chat, not a broadcast. */
export const engaged = (r) => !!r && ((r.ownerMsgs30d ?? 0) >= 3 || r.tier === 'frequent' || r.tier === 'regular');

/**
 * The highest route a semantic case may get, whatever the model says: {route, reason} or null (no ceiling).
 * Morning reports (any kind) are at most stream; on Beeper, Instagram links, short-video links with a real message
 * around them, story replies and group chats that carry an app's product announcements or update posts never pop up.
 */
export function ceiling(n) {
  const body = bodyOnly(n), head = `${n.title} ${n.conversationTitle}`;
  if (RX.morningReport.test(`${head} ${body.slice(0, 200)}`)) return { route: 'stream', reason: 'cap.morning-report' };
  if (n.kind !== 'beeper') return null;
  if (RX.shortVideo.test(body) || RX.instagramLink.test(body)) return { route: 'stream', reason: 'cap.instagram' };
  if (RX.storyReply.test(body)) return { route: 'stream', reason: 'cap.story' };
  if (n.isGroup && RX.announcements.test(head)) return { route: 'stream', reason: 'cap.announcements' };
  return null;
}
const RANK = { suppress: 0, stream: 1, popup: 2 };
/** Applies a ceiling to a decision (returns it unchanged when within the ceiling). */
export function capped(d, cap) {
  if (!cap || !d || RANK[d.route] <= RANK[cap.route]) return d;
  return { ...d, route: cap.route, importance: Math.min(d.importance, 0.59), cap: cap.reason };
}

/** When a semantic case cannot reach the model. Never a popup (only the model or a rule may interrupt). */
export function fallback(n, facts = {}) {
  if (n.kind === 'beeper') return { route: 'stream', importance: 0.4, reason: 'model unavailable', by: 'fallback', open: facts.chat?.chatId ? { app: 'beeper', target: facts.chat.chatId } : { app: 'beeper' } };
  // T3 on a watched thread that is neither "needs you" nor "finished": suppress.
  if (n.kind === 't3') return { route: 'suppress', importance: 0, reason: 'model unavailable', by: 'fallback' };
  // The assistant wrote it for the owner on purpose: keep it in the list rather than lose it.
  if (n.kind === 'hermes') return { route: 'stream', importance: 0.5, reason: 'model unavailable', by: 'fallback', open: { app: 'joy' } };
  return { route: 'suppress', importance: 0, reason: 'model unavailable', by: 'fallback' };
}

/** The open target a semantic (model) decision gets. */
export function openFor(n, facts = {}) {
  if (n.kind === 'beeper') return facts.chat?.chatId ? { app: 'beeper', target: facts.chat.chatId } : { app: 'beeper' };
  if (n.kind === 't3') { const key = n.chatId || facts.matchWatched?.(text(n)); return key ? { app: 't3', target: key } : { app: 't3' }; }
  if (n.kind === 'hermes') return { app: 'joy' };
  return undefined;
}

/** POST /notify/classify (old phone builds): notify|suppress plus importance, exactly the old response shape. */
export const legacy = (d) => ({ route: d.route === 'suppress' ? 'suppress' : 'notify', importance: d.importance });
