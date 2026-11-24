// Shared state for the glasses module: config, secrets, store, and the event bus that the
// phone long-polls (GET /glasses/v1/events). Upstream clients live in ./<name>.mjs and are
// created from this context (they read secrets by name via ctx.secret()).
import { randomUUID } from 'node:crypto';
import { createConversations } from './conversations.mjs';
import { createAgentLink } from './agent-link.mjs';
import { createTargets, resolveTargets, validateTargets } from './targets.mjs';
import { loadConfig, ConfigError } from '../../../lib/config.mjs';

export function createEventBus(store, { max = 500, now = Date.now } = {}) {
  const waiters = new Set();
  let seq = Math.max(0, ...store.all('events').map((e) => e.seq || 0));
  function publish(type, data = {}, { supersede = false } = {}) {
    const eventId = randomUUID();
    const ev = { ...data, id: data.id ?? eventId, seq: ++seq, type, at: new Date(now()).toISOString() };
    // A superseding event (a pure "refresh now" hint such as `beeper`) replaces older ones of its type, so a busy
    // chat can never push other events out of the capped log or replay hundreds of refreshes after a reconnect.
    if (supersede) for (const old of store.all('events')) if (old.type === type) store.del('events', old.id);
    // Docs/reminders use `id` for the entity. Persist under a separate key so
    // successive events for that same entity never overwrite an earlier cursor.
    store.put('events', { ...ev, id: eventId, entityId: data.id });
    const all = store.all('events');
    if (all.length > max) store.replace('events', all.sort((a, b) => a.seq - b.seq).slice(-max));
    for (const w of waiters) w();
    return ev;
  }
  function since(cursor) {
    const c = Number(cursor) || 0;
    return store.all('events').filter((e) => e.seq > c).sort((a, b) => a.seq - b.seq).map(({ entityId, ...e }) => entityId === undefined ? e : { ...e, id: entityId });
  }
  // Resolves with events after `cursor`, waiting up to waitMs for at least one.
  function wait(cursor, waitMs) {
    const now = since(cursor);
    if (now.length || waitMs <= 0) return Promise.resolve(now);
    return new Promise((resolve) => {
      const done = () => { clearTimeout(timer); waiters.delete(done); resolve(since(cursor)); };
      const timer = setTimeout(done, waitMs);
      waiters.add(done);
    });
  }
  return { publish, since, wait, cursor: () => seq };
}

/**
 * The flat `ctx.config` the glasses adapters read, from the gateway configuration (lib/config.mjs). Tests and embedders
 * may pass `deps.config` with any of these keys to override them.
 */
export function glassesConfig(c) {
  const { targets, routes } = resolveTargets(c);
  return {
    // Identity and presentation
    owner: c.owner, assistant: c.assistant, tz: c.tz, units: c.units, dataDir: c.dataDir, publicUrl: c.publicUrl,
    homeLat: c.home.lat, homeLon: c.home.lon,
    // LLM endpoint (OpenAI compatible) and models
    proxyUrl: c.llm.baseUrl, llmKeySecret: c.llm.apiKeySecret, models: { ...c.models },
    // Hermes (chat, sessions, voice-call relay)
    hermesEnabled: c.hermes.enabled, hermesUrl: c.hermes.url, hermesKeySecret: c.hermes.apiKeySecret,
    hermesCallUrl: c.hermes.enabled ? c.hermes.callUrl : '', hermesCallDevice: c.hermes.callDevice, hermesCallDeviceHeader: c.hermes.callDeviceHeader, hermesCallTokenSecret: c.hermes.callTokenSecret,
    // Call runaway cap in ms (default 4 h in routes/call.mjs); 0 disables it.
    hermesCallMaxMs: c.hermes.callMaxMs ?? undefined, hermesLiveContext: c.hermes.liveContext, hermesGlassesContext: c.hermes.glassesContext,
    // Beeper: push (WebSocket /v1/ws) and background spam classification run in the worker process only.
    beeperEnabled: c.beeper.enabled, beeperUrl: c.beeper.url, beeperTokenSecret: c.beeper.tokenSecret,
    beeperLive: c.worker && c.beeper.enabled && c.beeper.live, beeperSpam: c.worker && c.beeper.enabled && c.beeper.spam,
    spamThreshold: c.beeper.spamThreshold, beeperMentions: c.beeper.mentions,
    // T3 Code machines (machines.json); gateway-owned T3 notifications poll in the worker process.
    t3Enabled: c.t3.enabled, t3MachinesFile: c.t3.machinesFile, t3MachineUrls: c.t3.machineUrls, t3TailnetProxy: c.t3.tailnetProxy,
    t3MachineProxies: c.t3.machineProxies, t3Notify: c.worker && c.t3.enabled && c.t3.notify, t3NotifyNeedsAll: c.t3.notifyNeedsAll,
    t3NotifyEveryMs: c.t3.notifyEveryMs, t3VocabFile: c.t3.vocabFile, t3GlassesSuffix: c.t3.glassesSuffix,
    // Notifications: policy JSON, classifier prompt, the owner's own standard, own chats, time budget.
    notifyPolicyFile: c.notify.policyFile, notifyPromptFile: c.notify.promptFile, notifyStandard: c.notify.standard,
    notifyOwnChats: c.notify.ownChats.map(x => x.toLowerCase()), notifyTimeoutMs: c.notify.timeoutMs,
    // Dictation cleanup: output check ('enforce' falls back to the raw transcript, 'log' only logs, 'off'), prompt and vocabulary.
    cleanupGuard: c.cleanup.guard, cleanupPromptFile: c.cleanup.promptFile, cleanupVocabFile: c.cleanup.vocabFile,
    homeAssistant: c.homeAssistant, people: c.people,
    conversations: { transcribe: c.conversations.transcribe, maxAudioBytes: Math.round(c.conversations.maxAudioMb * 1024 * 1024),
      maxPending: c.conversations.maxPending, maxAttempts: c.conversations.maxAttempts },
    targets, routes,
    reportSourceDir: c.report.sourceDir, youtube: { ...c.youtube },
    spotifyDiscoverWeeklyUri: c.spotify.discoverWeeklyUri, spotifyUserId: c.spotify.userId, spotifyColdStart: c.spotify.coldStart,
    composio: { ...c.composio }, absUrl: c.abs.url,
  };
}

export async function createContext(api, deps = {}) {
  const env = api.env;
  const gateway = api.config ?? loadConfig({ env, file: null });
  const config = { ...glassesConfig(gateway), ...(deps.config || {}) };
  const ctx = {
    env,
    log: api.log,
    store: api.store,
    secrets: api.secrets,
    fetch: deps.fetch ?? globalThis.fetch,         // tests inject a fake fetch
    WebSocket: deps.WebSocket ?? globalThis.WebSocket,
    now: deps.now ?? (() => Date.now()),
    secret: (name, opts) => api.secrets.get(name, opts),
    gateway, config,
  };
  ctx.events = createEventBus(api.store, { now: ctx.now });
  // Agent control link to the phone (MCP + REST mirror relay through it).
  ctx.agent = createAgentLink(ctx, deps.agent ?? {});
  // Delivery targets for memos, recordings and dictations (lib/targets.mjs).
  const problems = validateTargets(config.targets, config.routes);
  if (problems.length) throw new ConfigError(problems);
  ctx.targets = deps.targets ?? createTargets(ctx, { targets: config.targets, routes: config.routes }, deps.targetDeps ?? {});
  // Recording queue: transcription, then delivery. The worker only runs where it is switched on.
  ctx.conversations = createConversations(ctx, deps);
  if (gateway.worker) ctx.conversations.start();
  return ctx;
}
