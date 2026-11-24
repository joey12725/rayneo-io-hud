// Gateway configuration: built-in defaults, then environment variables, then gateway.config.json (path from
// GATEWAY_CONFIG) merged over them. Every key is described once in SCHEMA below; the schema drives env parsing,
// validation (clear errors that name the key and the env var), the public subset served to the phone and the
// documented examples (.env.example, gateway.config.example.json; test/config.test.mjs keeps them complete).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const GATEWAY_DIR = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

export class ConfigError extends Error {
  constructor(problems) {
    super('Invalid gateway configuration:\n' + problems.map(p => '  - ' + p).join('\n'));
    this.code = 'GATEWAY_CONFIG_INVALID'; this.problems = problems;
  }
}

// Leaf: { type, env?, default, doc, secret?, public?, values?, deploy?, isPath? }. `deploy: true`: a deployment key (port, data
// directory, worker) where a set environment variable wins over the file, so a container's environment is never undone by a
// copied config file. `isPath: true`: a file or directory; a relative value from the config file resolves next to that file. Types: string, number, boolean, string[], json.
// Group: { doc?, props: { ... } }. `secret: true` marks a key that names a secret (never its value) and is never public.
const s = (def, env, doc, extra = {}) => ({ type: 'string', default: def, env, doc, ...extra });
const n = (def, env, doc, extra = {}) => ({ type: 'number', default: def, env, doc, ...extra });
const b = (def, env, doc, extra = {}) => ({ type: 'boolean', default: def, env, doc, ...extra });
const list = (def, env, doc, extra = {}) => ({ type: 'string[]', default: def, env, doc, ...extra });
const json = (def, env, doc, extra = {}) => ({ type: 'json', default: def, env, doc, ...extra });
const enabled = (env, doc) => b(true, env, doc ?? 'Switch the integration off entirely (its routes answer 503).');

export const SCHEMA = {
  port: n(8080, 'PORT', 'HTTP port. A set PORT wins over the config file.', { deploy: true }),
  host: s('0.0.0.0', 'HOST', 'Listen address. A set HOST wins over the config file.', { deploy: true }),
  publicUrl: s('', 'GATEWAY_PUBLIC_URL', 'Public base URL of this gateway as the phone reaches it, e.g. https://gateway.example.com. Used for links the gateway hands out (audiobook streams). Empty: derived from the request.'),
  basePath: s('', 'GATEWAY_BASE_PATH', 'Path prefix the gateway is served under behind a reverse proxy, e.g. /gateway. A leading /api is always accepted too.'),
  dataDir: s('', 'GATEWAY_DATA', 'Directory for gateway state (JSON collections, queued audio). Default: <gateway>/.data. A set GATEWAY_DATA wins over the config file.', { deploy: true, isPath: true }),
  modules: list([], 'GATEWAY_MODULES', 'API modules to load (glasses, tv). Empty: all.'),
  accessLog: b(false, 'GATEWAY_ACCESS_LOG', 'Log one line per request (method, path, status, time; never bodies).'),
  worker: b(false, 'GATEWAY_WORKER', 'Run the background workers in this process (recording uploads, Beeper push, T3 notifications). Exactly one gateway process should. A set GATEWAY_WORKER wins over the config file.', { deploy: true }),
  clients: json({ GLASSES_API_TOKEN: ['glasses'], GLASSES_AGENT_TOKEN: ['glasses.agent'], TV_LAUNCHER_TOKEN: ['tv'] }, 'GATEWAY_CLIENTS',
    'Map of token SECRET NAME -> scopes. A bearer token is accepted for a scope when it equals the value of one of these secrets. glasses = the phone app, glasses.agent = the assistant (Hermes), tv = the TV launcher.'),
  units: s('imperial', 'GATEWAY_UNITS', 'imperial (°F, lb) or metric (°C, kg) for the weather card and weight.', { values: ['imperial', 'metric'], public: true }),
  tz: s('UTC', 'GATEWAY_TZ', 'IANA time zone for dates shown on the glasses (falls back to TZ, then UTC).'),
  home: { doc: 'Optional home location for the weather card; weather is off until both are set.', props: {
    lat: n(null, 'GATEWAY_HOME_LAT', 'Latitude.', { nullable: true }),
    lon: n(null, 'GATEWAY_HOME_LON', 'Longitude.', { nullable: true }),
  } },
  owner: { doc: 'Who wears the glasses. Used in every prompt and to recognise the owner in chats.', props: {
    name: s('the user', 'GATEWAY_OWNER_NAME', 'Display name of the owner, as prompts should call them.', { public: true }),
    aliases: list([], 'GATEWAY_OWNER_ALIASES', 'Other names and handles the owner goes by (case-insensitive), e.g. a nickname or chat handle.', { public: true }),
  } },
  assistant: { props: {
    name: s('Hermes', 'GATEWAY_ASSISTANT_NAME', 'Name of the assistant (the Hermes agent) shown on the glasses and used in prompts.', { public: true }),
    workerName: s('Triage', 'GATEWAY_WORKER_NAME', 'Name of the background notification classifier persona used in its prompts.', { public: true }),
  } },
  secrets: { doc: 'Where credentials come from. Config only ever names secrets; values live in the provider.', props: {
    provider: s('env', 'GATEWAY_SECRETS', 'env (environment variable NAME or file NAME_FILE), file (JSON at secrets.file) or infisical.'),
    file: s('', 'GATEWAY_SECRETS_FILE', 'JSON file of {"NAME": "value"} for the file provider.', { isPath: true }),
    allow: list([], 'GATEWAY_SECRET_ALLOW', 'If set, only these secret names can be read (required for infisical).'),
    writable: list([], 'GATEWAY_SECRET_WRITABLE', 'Secret names the gateway may write back (rotating OAuth refresh tokens).'),
    ttlMs: n(300000, 'GATEWAY_SECRET_TTL_MS', 'How long a secret value is cached.'),
    infisicalEnvFile: s('', 'INFISICAL_ENV_FILE', 'Optional env-style file with INFISICAL_* keys (the INFISICAL_* environment variables win).', { isPath: true }),
  } },
  llm: { doc: 'Any OpenAI-compatible endpoint (chat completions + audio transcriptions).', props: {
    baseUrl: s('https://api.openai.com/v1', 'LLM_BASE_URL', 'Base URL ending in /v1.'),
    apiKeySecret: s('LLM_API_KEY', 'LLM_API_KEY_SECRET', 'Name of the secret holding the API key.', { secret: true }),
  } },
  models: { props: {
    transcribe: s('whisper-1', 'MODEL_TRANSCRIBE', 'Speech to text (dictation, memos, recordings).'),
    cleanup: s('gpt-4o-mini', 'MODEL_CLEANUP', 'Dictation cleanup.'),
    fast: s('gpt-4o-mini', 'MODEL_FAST', 'Fast general model (spam checks, small tasks).'),
    smart: s('gpt-4o', 'MODEL_SMART', 'Stronger model (titles, morning report).'),
    classifier: s('', 'MODEL_CLASSIFIER', 'Notification classifier. Empty: models.fast.'),
  } },
  hermes: { props: {
    enabled: enabled('HERMES_ENABLED'),
    url: s('http://localhost:8642', 'HERMES_URL', 'Hermes API server base URL.'),
    apiKeySecret: s('HERMES_API_SERVER_KEY', 'HERMES_API_KEY_SECRET', 'Secret holding the Hermes API server key.', { secret: true }),
    callUrl: s('', 'HERMES_CALL_URL', 'WebSocket URL of a Hermes voice-call endpoint (ws://...). Empty: live calls are off.'),
    callDevice: s('glasses', 'HERMES_CALL_DEVICE', 'Device id the gateway sends to the voice endpoint.'),
    callDeviceHeader: s('X-Assistant-Device', 'HERMES_CALL_DEVICE_HEADER', 'Header that carries hermes.callDevice to the voice endpoint (a voice plugin written for an older header name can set it here).'),
    callTokenSecret: s('HERMES_CALL_TOKEN', 'HERMES_CALL_TOKEN_SECRET', 'Secret holding the bearer token for the voice endpoint.', { secret: true }),
    callMaxMs: n(null, 'HERMES_CALL_MAX_MS', 'Runaway cap for one call in ms (default 4 h); 0 disables it.', { nullable: true }),
    liveContext: b(true, 'HERMES_LIVE_CONTEXT', 'Send the live glasses state (screen, media, call) with every chat turn.'),
    glassesContext: s('', 'HERMES_GLASSES_CONTEXT', 'Replaces the context line prefixed to every glasses chat message. {{owner}} and {{assistant}} are filled in.'),
  } },
  beeper: { props: {
    enabled: enabled('BEEPER_ENABLED'),
    url: s('http://localhost:23373', 'BEEPER_URL', 'Beeper Desktop API base URL.'),
    tokenSecret: s('BEEPER_ACCESS_TOKEN', 'BEEPER_TOKEN_SECRET', 'Secret holding the Beeper Desktop API token.', { secret: true }),
    live: b(true, 'BEEPER_LIVE', 'Hold the Beeper push WebSocket open (worker only).'),
    spam: b(true, 'BEEPER_SPAM', 'Classify new chats for spam in the background (worker only).'),
    spamThreshold: n(0.9, 'BEEPER_SPAM_THRESHOLD', 'Confidence a spam verdict needs to take effect.'),
    mentions: s('pill', 'BEEPER_MENTIONS', 'pill (Matrix pill links where supported) or plain (@Name).', { values: ['pill', 'plain'] }),
  } },
  t3: { props: {
    enabled: enabled('T3_ENABLED'),
    machinesFile: s('', 'T3_MACHINES_FILE', 'machines.json listing T3 Code servers (see t3-machines.example.json). Default: <dataDir>/t3-machines.json.', { isPath: true }),
    machineUrls: s('', 'T3_MACHINE_URLS', 'Overrides per machine: alias=url,alias=url.'),
    tailnetProxy: s('', 'T3_TAILNET_PROXY', 'SOCKS5/HTTP proxy for *.ts.net machines, e.g. socks5h://tailscale:1055.'),
    machineProxies: s('', 'T3_MACHINE_PROXIES', 'Per machine proxy overrides: alias=url or alias=direct.'),
    notify: b(true, 'T3_NOTIFY', 'Gateway-owned T3 notifications (worker only).'),
    notifyNeedsAll: b(true, 'T3_NOTIFY_NEEDS_ALL', 'Default for "notify for every thread that needs me".'),
    notifyEveryMs: n(20000, 'T3_NOTIFY_EVERY_MS', 'Polling interval (min 5000).'),
    vocabFile: s('', 'T3_VOCAB_FILE', 'JSON {skills, models, commands, terms} merged into the dictation vocabulary for T3 prompts.', { isPath: true }),
    glassesSuffix: s('', 'T3_GLASSES_SUFFIX', 'Text appended to prompts sent from the glasses. Empty: a short note that the message was dictated on the glasses.'),
  } },
  notify: { props: {
    policyFile: s('', 'NOTIFY_POLICY_FILE', 'Notification policy JSON (see apis/glasses/notify-policy.default.json). Empty: the default policy.', { isPath: true }),
    promptFile: s('', 'NOTIFY_PROMPT_FILE', 'Replaces the classifier prompt (prompts/notify-route.md); {{owner}}, {{assistant}} and {{worker}} are filled in.', { isPath: true }),
    standard: s('', 'NOTIFY_STANDARD', "The owner's own words on what deserves an interruption; added to the classifier prompt."),
    ownChats: list([], 'NOTIFY_OWN_CHATS', "Lower-case substrings of Beeper chat names that are the owner's own spaces (never promotions)."),
    timeoutMs: n(4000, 'NOTIFY_TIMEOUT_MS', 'Classifier time budget.'),
  } },
  cleanup: { props: {
    guard: s('enforce', 'CLEANUP_GUARD', 'Dictation cleanup output check: enforce, log or off.', { values: ['enforce', 'log', 'off'] }),
    promptFile: s('', 'CLEANUP_PROMPT_FILE', 'Replaces the generic cleanup prompt (prompts/cleanup.md).', { isPath: true }),
    vocabFile: s('', 'CLEANUP_VOCAB_FILE', 'JSON {terms: [...], corrections: {"heard": "meant"}} appended to the cleanup prompt.', { isPath: true }),
  } },
  homeAssistant: { doc: 'Home Assistant panel. URL and token come from the secrets HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN. The phone shows it once scenes, an alarm or printers are configured.', props: {
    enabled: enabled('HOMEASSISTANT_ENABLED'),
    scenes: json([], 'HOMEASSISTANT_SCENES', 'Scene buttons: [{id, name, icon, entity}] (entity: a script.* or scene.*).'),
    alarm: json(null, 'HOMEASSISTANT_ALARM', 'Optional alarm card: {time: sensor, mode: select, times: {"7:00 AM": script}, styles: [..]}.', { nullable: true }),
    printers: json([], 'HOMEASSISTANT_PRINTERS', '3D printer cards: [{id, name, progress, state, online, entityPrefix}].'),
  } },
  people: { props: {
    file: s('', 'PEOPLE_FILE', 'people.json: enrolled voices and notes for the People screen (see people.example.json). Empty or missing: People is off.', { isPath: true }),
  } },
  conversations: { doc: 'Memos and ambient recordings from the phone.', props: {
    transcribe: b(true, 'CONVERSATIONS_TRANSCRIBE', 'Transcribe recordings with models.transcribe before delivery.'),
    maxAudioMb: n(95, 'CONVERSATIONS_MAX_AUDIO_MB', 'Largest accepted recording.'),
    maxPending: n(500, 'CONVERSATIONS_MAX_PENDING', 'Recordings that may wait for delivery before the phone is told to hold off.'),
    maxAttempts: n(8, 'CONVERSATIONS_MAX_ATTEMPTS', 'Delivery attempts per target before it is marked failed.'),
  } },
  targets: json(null, 'GATEWAY_TARGETS', 'Delivery targets: [{id, name, kind: directory|webhook|hermes|<registered>, ...}]. Default: one directory target "local" under <dataDir>/recordings.', { nullable: true }),
  routes: json(null, 'GATEWAY_ROUTES', 'Which targets get what: {memo: [ids], ambient: [ids], dictation: [ids]}. Default: memo and ambient to every target.', { nullable: true }),
  report: { props: {
    sourceDir: s('', 'REPORT_SOURCE_DIR', 'Directory of YYYY-MM-DD/report.md morning reports (or a morning-report.json). Empty: the report screen is empty.', { isPath: true }),
  } },
  youtube: { props: {
    file: s('', 'YOUTUBE_LIST_FILE', 'JSON list of videos [{video_id, title, channel_name, duration_seconds}] shown as a curated list. Empty: off.', { isPath: true }),
    listKey: s('picks', 'YOUTUBE_LIST_KEY', 'List key.'),
    listName: s('Picks', 'YOUTUBE_LIST_NAME', 'List name shown on the glasses.'),
  } },
  spotify: { props: {
    discoverWeeklyUri: s('', 'SPOTIFY_DISCOVER_WEEKLY_URI', 'Your Discover Weekly playlist URI (optional).'),
    userId: s('', 'SPOTIFY_USER_ID', 'Spotify user id (optional).'),
    coldStart: s('phone', 'SPOTIFY_COLD_START', 'Where playback starts when nothing is active: phone or off.'),
  } },
  composio: { doc: 'Calendar and Spotify through a Composio MCP server (secrets COMPOSIO_MCP_URL, COMPOSIO_MCP_TOKEN).', props: {
    calendarAccount: s('', 'COMPOSIO_CALENDAR_ACCOUNT', 'Composio connected-account id for Google Calendar. Empty: calendar is off.'),
    spotifyAccount: s('', 'COMPOSIO_SPOTIFY_ACCOUNT', 'Composio connected-account id for Spotify.'),
  } },
  abs: { props: {
    url: s('', 'ABS_URL', 'Audiobookshelf base URL (token: secret AUDIOBOOKSHELF_TOKEN). Empty: audiobooks are off.'),
  } },
  tv: { props: {
    jamLinkEntity: s('', 'TV_JAM_LINK_ENTITY', 'Home Assistant input_text.* holding a Spotify Jam link to share (optional).'),
    room: s('living room', 'TV_ROOM', 'Default room name in shared messages.'),
  } },
};

const ENV_FALLBACK = { GATEWAY_TZ: ['TZ'] };

function walk(schema, fn, prefix = []) {
  for (const [key, node] of Object.entries(schema)) {
    if (node.props) walk(node.props, fn, [...prefix, key]); else fn([...prefix, key], node);
  }
}
/** Every leaf: [{ path: 'owner.name', env, type, default, doc, secret, public, values }]. */
export function describe() {
  const out = [];
  walk(SCHEMA, (p, node) => out.push({ path: p.join('.'), ...node }));
  return out;
}
function setPath(obj, p, value) { let o = obj; for (const k of p.slice(0, -1)) o = (o[k] ??= {}); o[p.at(-1)] = value; }
function getPath(obj, p) { let o = obj; for (const k of p) { if (o == null || typeof o !== 'object') return undefined; o = o[k]; } return o; }
const clone = v => v === undefined ? v : JSON.parse(JSON.stringify(v));

function fromEnv(raw, node, name, problems) {
  switch (node.type) {
    case 'string': return raw;
    case 'number': {
      const v = Number(raw);
      if (raw.trim() === '' || !Number.isFinite(v)) { problems.push(`${name} must be a number (got "${raw}")`); return undefined; }
      return v;
    }
    case 'boolean': {
      if (/^(1|true|yes|on)$/i.test(raw)) return true;
      if (/^(0|false|no|off)$/i.test(raw)) return false;
      problems.push(`${name} must be true/false or 1/0 (got "${raw}")`); return undefined;
    }
    case 'string[]': return raw.split(',').map(x => x.trim()).filter(Boolean);
    case 'json': try { return JSON.parse(raw); } catch { problems.push(`${name} must be JSON`); return undefined; }
  }
}
const kindOf = v => v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v;
function check(value, node, where, problems) {
  if (value === null && (node.nullable || node.default === null)) return;
  const bad = expected => problems.push(`${where} must be ${expected} (got ${kindOf(value)})`);
  switch (node.type) {
    case 'string': if (typeof value !== 'string') return bad('a string'); break;
    case 'number': if (typeof value !== 'number' || !Number.isFinite(value)) return bad('a number'); break;
    case 'boolean': if (typeof value !== 'boolean') return bad('true or false'); break;
    case 'string[]': if (!Array.isArray(value) || value.some(x => typeof x !== 'string')) return bad('an array of strings'); break;
  }
  if (node.values && !node.values.includes(value)) problems.push(`${where} must be one of ${node.values.join(', ')} (got "${value}")`);
}
function closest(word, options) {
  let best = null, score = Infinity;
  const dist = (a, b) => { const d = Array.from({ length: b.length + 1 }, (_, i) => i); for (let i = 1; i <= a.length; i++) { let prev = d[0]; d[0] = i; for (let j = 1; j <= b.length; j++) { const t = d[j]; d[j] = Math.min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1)); prev = t; } } return d[b.length]; };
  for (const o of options) { const x = dist(word.toLowerCase(), o.toLowerCase()); if (x < score) { score = x; best = o; } }
  return score <= 3 ? best : null;
}
function unknownKeys(obj, schema, prefix, problems) {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return;
  for (const [key, value] of Object.entries(obj)) {
    if (key.startsWith('$') || key.startsWith('//')) continue; // "$schema", "//": comments
    const node = schema[key], where = [...prefix, key].join('.');
    if (!node) { const hint = closest(key, Object.keys(schema)); problems.push(`unknown key ${where}${hint ? ` (did you mean ${[...prefix, hint].join('.')}?)` : ''}`); continue; }
    if (node.props) {
      if (!value || typeof value !== 'object' || Array.isArray(value)) problems.push(`${where} must be an object (got ${kindOf(value)})`);
      else unknownKeys(value, node.props, [...prefix, key], problems);
    }
  }
}

/** Extra checks registered by modules (e.g. delivery targets): fn(config) -> [problem strings]. */
const validators = [];
export function registerConfigValidator(fn) { validators.push(fn); }

/**
 * Builds the configuration. `file` defaults to env.GATEWAY_CONFIG; `overrides` (tests) is merged last.
 * Throws ConfigError listing every problem.
 */
export function loadConfig({ env = process.env, file = env.GATEWAY_CONFIG, overrides, read = fs.readFileSync } = {}) {
  const problems = [], config = {};
  walk(SCHEMA, (p, node) => setPath(config, p, clone(node.default)));
  walk(SCHEMA, (p, node) => {
    if (!node.env) return;
    let name = node.env, raw = env[name];
    for (const alt of ENV_FALLBACK[name] ?? []) if ((raw === undefined || raw === '') && env[alt]) { raw = env[alt]; name = alt; }
    if (raw === undefined || (raw === '' && node.type !== 'string')) return;
    const v = fromEnv(raw, node, name, problems);
    if (v !== undefined) setPath(config, p, v);
  });
  let fileData = null;
  if (file) {
    let text;
    try { text = read(file, 'utf8'); } catch (e) { throw new ConfigError([`GATEWAY_CONFIG file ${file} cannot be read (${e.code ?? e.message})`]); }
    try { fileData = JSON.parse(text); } catch (e) { throw new ConfigError([`${path.basename(file)} is not valid JSON: ${e.message}`]); }
    if (!fileData || typeof fileData !== 'object' || Array.isArray(fileData)) throw new ConfigError([`${path.basename(file)} must hold a JSON object`]);
    unknownKeys(fileData, SCHEMA, [], problems);
  }
  const envSet = node => node.env && env[node.env] !== undefined && env[node.env] !== '';
  const fromFileDir = new Set();
  for (const layer of [fileData, overrides]) {
    if (!layer) continue;
    walk(SCHEMA, (p, node) => {
      const v = getPath(layer, p);
      if (v === undefined || (layer === fileData && node.deploy && envSet(node))) return;
      setPath(config, p, clone(v));
      if (node.isPath) { if (layer === fileData) fromFileDir.add(p.join('.')); else fromFileDir.delete(p.join('.')); }
    });
  }
  walk(SCHEMA, (p, node) => {
    const v = getPath(config, p), fromFile = fileData && getPath(fileData, p) !== undefined;
    check(v, node, fromFile ? `${p.join('.')} in ${path.basename(file)}` : node.env && env[node.env] !== undefined ? `${node.env} (${p.join('.')})` : p.join('.'), problems);
  });
  // Relative file and directory paths: next to the config file when they came from it, else from the working directory.
  walk(SCHEMA, (p, node) => {
    const v = getPath(config, p);
    if (node.isPath && typeof v === 'string' && v) setPath(config, p, path.resolve(fromFileDir.has(p.join('.')) ? path.dirname(path.resolve(file)) : process.cwd(), v));
  });
  if (!config.dataDir) config.dataDir = path.join(GATEWAY_DIR, '.data');
  if (!config.models.classifier) config.models.classifier = config.models.fast;
  if (!config.t3.machinesFile) config.t3.machinesFile = path.join(config.dataDir, 't3-machines.json');
  if (config.t3.notifyEveryMs < 5000) config.t3.notifyEveryMs = 5000;
  try { new Intl.DateTimeFormat('en-US', { timeZone: config.tz }); } catch { problems.push(`tz "${config.tz}" is not a valid IANA time zone (GATEWAY_TZ)`); }
  if ((config.home.lat == null) !== (config.home.lon == null)) problems.push('home.lat and home.lon must be set together (GATEWAY_HOME_LAT, GATEWAY_HOME_LON)');
  if (config.home.lat != null && Math.abs(config.home.lat) > 90) problems.push('home.lat must be between -90 and 90');
  if (config.home.lon != null && Math.abs(config.home.lon) > 180) problems.push('home.lon must be between -180 and 180');
  if (!config.clients || typeof config.clients !== 'object' || Array.isArray(config.clients) || Object.values(config.clients).some(v => !Array.isArray(v) || v.some(x => typeof x !== 'string')))
    problems.push('clients must map secret names to arrays of scopes, e.g. {"GLASSES_API_TOKEN": ["glasses"]} (GATEWAY_CLIENTS)');
  if (config.basePath && !/^\/[A-Za-z0-9._~\/-]*[^/]$/.test(config.basePath)) problems.push(`basePath must start with / and not end with / (got "${config.basePath}")`);
  if (config.publicUrl && !/^https?:\/\/[^\s/]+/.test(config.publicUrl)) problems.push(`publicUrl must be an http(s) URL (got "${config.publicUrl}")`);
  for (const [key, url] of [['llm.baseUrl', config.llm.baseUrl], ['hermes.url', config.hermes.url], ['beeper.url', config.beeper.url]])
    if (url && !/^https?:\/\//.test(url)) problems.push(`${key} must be an http(s) URL (got "${url}")`);
  if (!/^[A-Za-z0-9-]+$/.test(config.hermes.callDeviceHeader)) problems.push(`hermes.callDeviceHeader must be an HTTP header name (got "${config.hermes.callDeviceHeader}")`);
  if (config.hermes.callUrl && !/^wss?:\/\//.test(config.hermes.callUrl)) problems.push(`hermes.callUrl must be a ws:// or wss:// URL (HERMES_CALL_URL)`);
  for (const scene of Array.isArray(config.homeAssistant.scenes) ? config.homeAssistant.scenes : [null])
    if (!scene || typeof scene.id !== 'string' || typeof scene.name !== 'string' || typeof scene.entity !== 'string') { problems.push('homeAssistant.scenes must be [{id, name, icon?, entity}]'); break; }
  for (const printer of Array.isArray(config.homeAssistant.printers) ? config.homeAssistant.printers : [null])
    if (!printer || typeof printer.id !== 'string' || typeof printer.name !== 'string') { problems.push('homeAssistant.printers must be [{id, name, progress?, state?, online?, entityPrefix?}]'); break; }
  const alarm = config.homeAssistant.alarm;
  if (alarm != null && (typeof alarm !== 'object' || typeof alarm.time !== 'string' || typeof alarm.mode !== 'string' || !alarm.times || typeof alarm.times !== 'object' || !Array.isArray(alarm.styles)))
    problems.push('homeAssistant.alarm must be {time, mode, times: {label: script}, styles: [..]} or null');
  for (const fn of validators) problems.push(...fn(config));
  if (problems.length) throw new ConfigError(problems);
  return config;
}

/** Integrations that are switched on and have what they need to run, for the phone. */
export function enabledIntegrations(config) {
  const on = [];
  if (config.hermes.enabled) on.push('hermes');
  if (config.hermes.enabled && config.hermes.callUrl) on.push('hermes-call');
  if (config.beeper.enabled) on.push('beeper');
  if (config.t3.enabled) on.push('t3');
  const ha = config.homeAssistant;
  if (ha.enabled && ((ha.scenes?.length ?? 0) > 0 || (ha.printers?.length ?? 0) > 0 || ha.alarm != null)) on.push('home-assistant');
  if (config.people.file && fs.existsSync(config.people.file)) on.push('people');
  if (config.report.sourceDir) on.push('report');
  if (config.youtube.file) on.push('youtube');
  if (config.composio.calendarAccount) on.push('calendar');
  if (config.composio.spotifyAccount) on.push('spotify');
  if (config.abs.url) on.push('audiobooks');
  if (config.home.lat != null) on.push('weather');
  return on;
}

/** GET /glasses/v1/config: what the phone may know. Never secret names or values, URLs or file paths. */
export function publicConfig(config, targets = []) {
  return {
    schema: 1,
    assistant: { name: config.assistant.name, workerName: config.assistant.workerName },
    owner: { name: config.owner.name, aliases: [...config.owner.aliases] },
    tz: config.tz,
    units: config.units,
    integrations: enabledIntegrations(config),
    targets: targets.map(t => ({ id: t.id, name: t.name, kind: t.kind })),
    routes: Object.fromEntries(Object.entries(config.routes ?? {}).map(([k, v]) => [k, [...v]])),
  };
}
