# Architecture and API contract

This is the implementation behind [`requirements.md`](requirements.md): how the phone app, the glasses and the gateway fit together, and the HTTP contract between the phone and the gateway. The simulator ([`../simulator/index.html`](../simulator/index.html)) is the pixel reference: the Android renderer is a faithful Kotlin port of it. Configuration names are listed in [configuration.md](configuration.md); deployment is in [gateway.md](gateway.md).

```
 8BitDo Micro ──BT keyboard──▶ ┌────────────── Android phone app (applicationId from -PappId) ──────────────┐
                               │ ControllerAccessibilityService → InputEngine → ViewStack (screens)        │
 RayNeo iO ◀──BLE+RFCOMM 26──▶ │ GlassesService (foreground): render loop → Renderer (540×220 1-bit)       │
   (display, mics)             │      → GlassesTransport: RayNeoTransport | PreviewTransport (on phone)     │
                               │ NotificationListener (policy + classifier) · MediaSessions · Dictation    │
                               │ DataStore: LiveDataStore (gateway) | FakeDataStore (simulator data)       │
                               └───────────────────────────────┬───────────────────────────────────────────┘
                                                               │ HTTPS, one bearer token
                                   <GATEWAY_PUBLIC_URL>/* ──▶ your reverse proxy (TLS) ──▶ gateway :8080
                               ┌──────────────────── RayNeo iO HUD gateway (server/gateway) ───────────────┐
                               │ Node 24, no dependencies. Modules: /glasses/v1 (phone, assistant), /tv/v1. │
                               │ Secrets by name (env, file or Infisical). Talks to: Hermes, T3 Code        │
                               │ machines, Beeper Desktop API, an OpenAI-compatible LLM endpoint, and the   │
                               │ optional integrations (Spotify and Calendar via Composio, Oura, Weight      │
                               │ Gurus, Home Assistant, Audiobookshelf, report files), delivery targets.    │
                               └────────────────────────────────────────────────────────────────────────────┘
```

## 1. Decisions

| Decision | Why |
|---|---|
| Android app renders everything; glasses are a display + mic | Stock firmware, no SDK (HW-01, ARC-01). |
| Kotlin port of the simulator renderer on `android.graphics.Canvas`, thresholded to 1-bit | The simulator is the design reference. Canvas works in a background service; a WebView does not render reliably there. |
| Every screen also renders to a text grid | Stock surfaces are a character grid (ARC-04 level 1). `List`/`Reader` provide this generically; custom screens implement `drawText`. |
| One gateway process for every service | The phone holds one token; provider keys stay on the server (PRV-03). |
| Gateway in Node 24 with no dependencies | Built-in `fetch` + `WebSocket` (needed for T3's RPC); nothing to install or audit. |
| Beeper through the Beeper Desktop API on an always-on machine | It supports reading, sending and reactions (BEEP-30). |
| T3 through T3 Code's own server API on each paired machine | No T3 runs on the gateway host; machines are listed in `t3-machines.json`. |
| Phone polls `GET /glasses/v1/events` (long-poll) for server pushes | Simple, survives NAT and reverse proxies; no FCM dependency. |

## 2. Android app

Single Gradle module `android/app`, package `io.lampyr.glasses`, minSdk 29, targetSdk 35, Kotlin, no Compose (plain Views; the glasses UI is drawn on a Canvas). Dependencies: OkHttp, kotlinx-serialization-json, kotlinx-coroutines, androidx core/appcompat. Tests: JUnit + Robolectric for rendering (golden PNGs).

```
io.lampyr.glasses
├─ render/      Gfx (JS-canvas-like API over android Canvas), Font5, Fonts, Icons, Emoji, RichText
│               (graphemes, parse, layout, pages, drawLine, clampLines, summarizeLinks), AvatarPack,
│               OneBit (threshold + pack), TextGrid (cols×rows char buffer)
├─ ui/          View (interface), Ui (context: stack, data, toast, clock), ListView, ReaderView,
│               Chrome (frame/frameW/bottomRow/hintRow/pageFooter/marquee/bar), Toast
├─ ui/screens/  Home, Apps, Joy (assistant + menu, call), Report, Dictation, Beeper (list, thread, message,
│               actions, reply, react), Notifications, T3 (list, thread/reader/working/history,
│               request, usage, envs, new thread, settled), Media (now playing, menu, ABS, Spotify,
│               devices, speed, YouTube), Calendar, People, Health, Status, HomeAssistant, Docs,
│               Teleprompter, VoiceMemo, Settings (+app order)
├─ input/       Buttons (logical letters), KeyMap (Android keycode → letter, user-editable),
│               InputEngine (taps, 450 ms holds, R2 media layer, lock, grip D-pad rotation),
│               ControllerAccessibilityService (global key capture, CTL-03)
├─ data/        Models, DataStore (interface), FakeDataStore (simulator data), LiveDataStore
│               (GatewayClient), PhoneNotifications, MediaHub (MediaSessions + Spotify)
├─ calls/       Calls (pure snapshots/state/duration/identity), FakeCalls, AndroidCalls
│               (basic telephony), CompanionCallsService (non-UI Telecom), CallSetup (CDM)
├─ glasses/     GlassesTransport (interface), PreviewTransport, RayNeoTransport (BLE auth +
│               RFCOMM ch 26, surfaces, audio), protocol/ (framing, commands)
├─ audio/       MicSource (glasses channel 1, or phone/headset mic fallback), Recorder, Dictation
└─ service/     GlassesService (foreground; owns Ui + render loop), NotificationListener, BootReceiver
```

### 2.1 View contract (port of the simulator's view objects)

```kotlin
interface View {
  val id: String; val kind: String
  fun draw(g: Gfx, t: Double)                 // t = seconds, for animated icons
  fun drawText(tg: TextGrid) {}               // level-1 fallback (List/Reader implement it)
  fun key(k: String): Boolean = false         // 'up','down','left','right','sel','back','act','plus','plusHold'
  fun legend(): Map<String, String> = emptyMap()
  val talk: TalkTarget? get() = null          // L hold target; null = talk to the assistant
  fun talkTap(): Boolean = false              // voice memo: L tap toggles
}
```
`Ui` owns the stack (`push`, `pop`, `replaceTop`, `goHome`), `flash(text)`, the `DataStore`, `nowS()`, and `requestRender()`. Rendering happens at most 10 fps while something animates, otherwise only on change.

### 2.2 Renderer fidelity

`render/` must produce pixel-identical (or within a few pixels) output to the simulator with the 5×7 repo font. Golden tests render each screen with `FakeDataStore` at fixed `t` and compare with PNGs captured from the simulator by `simulator/tools/screen_tour.py`.

The native master is 540×220 (118,800 pixels, 14,850 continuously packed
MSB-first bytes); `render/DisplaySize` owns these dimensions. Body fonts keep
their pixel sizes. Chrome uses a 104 px rail / 72 px icon, header y=8 and rule
y=32, body y=42, and a reserved footer at y=196. Lists scroll instead of shrinking
text. The avatar pack's 192×288 cells are proportionately OR-pooled to 128×192;
full-width readers fit 43 columns × 8 lines, rail readers 33×8, assistant replies 31×9.
Matching raster targets use `RasterFit`'s explicit identity path; FIT/STRETCH
remain for other dimensions and grey quantisation still supports 2/4/8/16 levels.
Stock text remains a separate 26×7 renderer with a 26×5 reader body.

### 2.3 Glasses output

`GlassesTransport.show(frame: Frame)` where `Frame` carries the 540×220 1-bit bitmap **and** a `TextGrid`. `RayNeoTransport` sends the highest level the firmware supports (text surfaces in v1; bitmap if a command is found). `PreviewTransport` draws the bitmap in the phone app (mirror) so everything is testable without hardware (ARC-02).

### 2.4 Input

8BitDo Micro in keyboard mode, paired to the phone. Default key mapping (configurable in the phone app, "Learn keys" wizard): D-pad = arrows, Y = Enter, B = Backspace (the app consumes it), X = I, A = O, L = U, L2 = Space, R = E, R2 = 3, − = Minus, + = Equals, ★ = S, ♥ = H — identical to the simulator's keyboard fallback, so one 8BitDo profile works everywhere.

## 3. Gateway

`server/gateway/`: `server.mjs` (router, auth, module loader), `lib/` (config, secrets, store, http, WebSocket), `apis/<module>/index.mjs`. Run it with Node 24 or the included `Dockerfile` / `docker-compose.example.yml`, behind any reverse proxy that terminates TLS; `GATEWAY_BASE_PATH` lets it live under a path prefix. See [gateway.md](gateway.md) and [`../server/gateway/README.md`](../server/gateway/README.md).

Auth: `Authorization: Bearer <token>`. Tokens are secrets named in `GATEWAY_CLIENTS` (`GLASSES_API_TOKEN` → scope `glasses` for the phone; `GLASSES_AGENT_TOKEN` → scope `glasses.agent` for the assistant's tools; `TV_LAUNCHER_TOKEN` → scope `tv`). Errors: `{"error": "message"}` with HTTP status. Authentication secret-service failures return 503 with `Retry-After: 30`; missing secrets are non-matches. `GATEWAY_SECRET_ALLOW` restricts which secret names can be read (required with the Infisical provider). Failed logins/lookups cache for 30 seconds; secret lookups are single-flight and limited to four concurrently.

### 3.1 Glasses API (`/glasses/v1`)

All times are ISO-8601 UTC strings; durations are seconds. Text fields are plain text (the app summarises links and renders emoji).

**Home**
- `GET /home` → `{weather:{tempF, condition, icon}|null, beeper:{unread, unreadChats, names:[..]}, t3:{needYou, done}, nextEvent:{title, start, location, allDay}|null, nowPlaying: NowPlaying|null}`

**Assistant** (ARC-05, JOY-*)
- `POST /assistant/chat` `{message, sessionId?, quiet?, notifyIfLeft?}` → NDJSON stream of `{type:"session", sessionId}`, `{type:"status", status}` (idle|listening|thinking|working|searching|remembering|saving|speaking|waiting|delegating|error), `{type:"delta", text}`, `{type:"emote", anim}` (optional), `{type:"result", text, sessionId}`, `{type:"error", message}`. The gateway marks the turn as coming from the glasses (a context line, glasses formatting, glasses tools; `hermes.glassesContext`). Session compression may emit another `session` record; retain the final session ID. One turn per session; overlap returns 409. After phone disconnect, the gateway continues consuming Hermes and always publishes `joy_reply` (below; the event type keeps its historical name); the phone decides whether to notify, including for `notifyIfLeft` turns.
- `POST /assistant/sessions` → `{sessionId}` (new conversation).
- `GET /assistant/call/status` → `{available:boolean, reason?:string}` (scope `glasses`). Cached for 30 seconds with a three-second total budget: resolves the call credential and probes the upstream origin with HTTP HEAD, without starting a call or following redirects. Any HTTP response counts as a reachable host, including 404/405 from WebSocket-only servers. Missing config/credential, timeout or unreachable hosts report unavailable. A positive result does not validate the credential or call endpoint; the actual upgrade and WebSocket fallback below remain authoritative.
- `GET /assistant/call` → WebSocket upgrade (scope `glasses`), publicly `wss://<gateway host>/glasses/v1/assistant/call`. Authenticate only with `Authorization: Bearer <phone token>`; never URL/query tokens or bearer subprotocols. `/api` and bare paths also work. Unauthorized/wrong-scope/unknown upgrades return HTTP 401/403/404 and close the socket.

**Live assistant call protocol 1**

The gateway transparently relays complete text/binary messages, preserving kind, bytes and order, to `HERMES_CALL_URL` (no default; for example `ws://hermes:8642/v1/voice/call`). It sends upstream `Authorization: Bearer <HERMES_CALL_TOKEN>` and `X-Assistant-Device: <HERMES_CALL_DEVICE>` (default `glasses`; the header name is configurable with `HERMES_CALL_DEVICE_HEADER` for a voice plugin written against another name). The call credential is a secret read by name (`hermes.callTokenSecret`, default `HERMES_CALL_TOKEN`); include that name in `GATEWAY_SECRET_ALLOW` when an allow-list is set. This credential is never sent to the phone. An empty call URL disables calls. Call URLs contain no credentials, query or fragment.

| Direction | Format |
|---|---|
| Phone → assistant binary | PCM16 little-endian, mono, 16 kHz, 20 ms per message: **640 bytes**. |
| Assistant → phone binary | PCM16 little-endian, mono, 24 kHz, 20 ms per message: **960 bytes**. |
| Phone → assistant text JSON | `{"type":"hello","fw":"..."}`, `{"type":"ping"}` every 20 s, `{"type":"call_start","trigger":"button","preroll_ms":0}`, `{"type":"drained"}` after the playback head confirms queued audio has actually finished playing, `{"type":"hangup","reason":"..."}`. |
| Assistant → phone text JSON | Events named `hello` with `{bridge,protocol}`, `pong`, `accepted`, `ready`, `state` with `{value}` (listening/thinking/speaking; `joy_speaking` is also accepted), `clear` (barge-in: flush playback immediately), and `hangup` with `{reason}`. |

The phone uses discriminator `type`, sends `hello` with `fw:"rayneo-android"`, and starts 640-byte uplink frames only after `ready`. It buffers about 80 ms of 24 kHz playback, pauses and flushes immediately on `clear`, and sends silence frames while muted. Long calls (2026-11-17): the client's runaway cap is 4 hours. A link lost after `ready` (or a bridge hangup `error`/`unavailable`/`busy`/`socket`/`replaced`) reconnects for up to 60 s with backoff, keeping the microphone, audio focus and the LifeLog exclusion; the reconnect sends header `X-Assistant-Call-Resume` (the gateway then replaces this phone's old socket instead of answering busy), `hello` with `caps:["agent","resume","session"]` and `call_start` with `resume:<call id>` from the bridge's `call` message, so a voice bridge that supports resume continues the same realtime session. A downlink burst never ends the call: beyond 750 queued frames (15 s) the oldest audio is dropped. OkHttp pings every 15 s. The client gives cellular calls priority. Unavailable preflight, upgrade failure or an unavailable hangup selects explicitly labelled voice turns.

The upstream event-type field spelling is deliberately not assumed by the relay. The phone owns PCM capture/playback, sample rates, barge-in flushing and the application-level ping; transport ping/pong is handled separately by RFC 6455. Existing turn-based `/assistant/chat` and `/voice/dictate` remain available for fallback.

One active call per authenticated phone-token identity in this single gateway process. A second receives `{"type":"hangup","reason":"busy"}` followed by close **1008**, unless the existing call has received no traffic from either peer for **more than 30 seconds**; then close the old call with **1001** and accept the replacement. Call-duration cap: **4 hours** by default (`HERMES_CALL_MAX_MS`, 0 disables; close 1000), a runaway guard only; the gateway pings both peers every 20 s. A connection carrying `X-Assistant-Call-Resume` always replaces the same identity's live call (close 1001 `Call resumed elsewhere`). Transport inactivity timeout: 60 seconds (1001); close-handshake deadline: two seconds. Valid close codes/reasons propagate to the other peer; abrupt transport closure (local-only 1006) maps to 1011. Either peer's error/closure ends the other side and releases its reservation. Run one replica; these reservations are in memory.

The maximum incoming message is 1 MiB (1009 if exceeded); the startup queue and each outgoing buffer are capped at 2 MiB. Relay writes pause source reads until the destination drains; exceeding a buffer limit closes both peers **1013**, with a short reason, without silently dropping audio. Unconfigured/unreachable/refused upstreams (including HTTP 401/404), or connection setup exceeding eight seconds, send `{"type":"hangup","reason":"unavailable","detail":"<short safe explanation>"}` followed by close **1011**, so the phone can fall back. Logs contain only call open/close, code, duration and byte counts, never audio, JSON payloads, URLs, bearer values or peer-supplied reasons. Plain `ws:` upstream URLs are restricted to single-label compose/private hostnames, RFC1918 IPv4 addresses, and loopback; public hosts require `wss:`. Configure `HERMES_CALL_URL` and the call token secret; the token value lives only in the secrets provider.

**Voice** (VOX-02/03)
- `POST /voice/dictate?target=beeper|t3|assistant|memo|reminder|raw` body = audio (`audio/ogg`, `audio/wav`, `audio/mp4`, `audio/webm`, `audio/mpeg`, `audio/aac`, at most 25 MiB) → NDJSON `{phase:"transcribing"}`, `{phase:"enhancing", raw}`, `{phase:"done", text, raw, cleaned:boolean, silence:boolean}` or `{phase:"error", message}`. `target=assistant` (legacy `joy`) and `target=raw` (legacy `nav`; exact words for lookups such as finding a chat or a contact) skip enhancement. With `routes.dictation` set, finished dictations other than `raw` are also delivered to those targets as text. Cleanup errors preserve the raw transcript with `cleaned:false`; silence returns `phase:"done", text:"", raw:"", cleaned:false, silence:true` and does not end a turn-based call.
- `POST /voice/title` `{text}` → `{title}` (memo titles).
- Dictation context (VOX requirement 5, additive): `/voice/dictate` also takes `chatId` (Beeper) or `machine`, `thread`, `project` (T3); the `done` row adds `mentions:[{token:"@Name", id, name}]` when the speaker tagged chat members. `POST /beeper/chats/:id/messages` accepts `mentions:[{token, id}]` (max 20) and converts them per `BEEPER_MENTIONS`. `POST /voice/cleanup {text, target, chatId?, machine?, thread?}` → `{text, cleaned, mentions}`.

**Beeper** (BEEP-*)
- `GET /beeper/summary` → `{unread, unreadChats, names:[..]}` across all visible chats, independent of the display list cap.
- `GET /beeper/chats?limit=20` → `[{id, name, network, unread, last:{sender, text, mine}}]`
- `GET /beeper/chats/:chatId/messages?limit=30` → `[{id, sender, mine, text, reactions:[{key, count, mine}], replyTo:{sender, text}|null, approval:bool, at:ISO}]` oldest→newest.
- `GET /beeper/chats/:chatId/messages?page=true&before=<opaque cursor>&limit=30` → `{messages:[Message], nextBefore:string|null, loaded:true}`. `before` is the previous `nextBefore`, forwarded unchanged to Beeper. Messages are oldest→newest. In paged mode `limit` is a target minimum: whole upstream pages are retained so opaque cursors never skip a sliced-off message; the legacy array route still caps at `limit`. A common 15-second budget returns completed pages on later-page failure. Android merges pages by message ID, keeps chronological order, and exposes `pageState(chatId)` and `loadOlder(chatId)`.
- `POST /beeper/chats/:chatId/messages` `{text, replyTo?}` → `{id}`
- `POST /beeper/chats/:chatId/messages/:messageId/reactions` `{key}` → `{ok}` ; `DELETE` same path `?key=` removes.
- `POST /beeper/chats/:chatId/read` → `{ok}`

**T3 Code** (T3-*) — `machine` is the alias from your `t3-machines.json` (for example `workstation`, `laptop`, `server`).
- `GET /t3/threads` → `{machines:[Machine], threads:[Thread]}` where `Machine = {machine, label, online, lastSeen, os, canWrite, paired, lastSeenLabel?, cause?, needsPairing?, needsRepair?}` (ready/settled omitted). `Thread = {machine, id, title, project, provider, status, ageSec, updatedAt, step:{kind, label, what, startedAt}|null}` with `status` ∈ input|approval|request|limited|failed|done|working|waiting|ready|settled. Upstream 401 sets `needsRepair:true` (also on `/t3/environments`); the gateway never renews machine credentials.
- `GET /t3/settled?limit=25` → `[Thread]`
- `GET /t3/threads/:machine/:id` → `{thread:Thread, latest:{text, at}|null, history:[{who:"user"|"agent", text, at}], working:{now:{kind, label, what, startedAt, lastOutputAt}|null, recent:[{label, durSec}]}|null, request:Request|null, runId|null}`. `Request = {id, kind:"choice"|"multi"|"text"|"approval"|"guest", questions:[{id, header, question, options:[{label, description}], multi}], approval:{kind, command, reason}|null, messages?:[{who, text, at}]}`. Guest requests expose `messages` for reading; they require a normal thread message reply, rather than `runtime-request.respond`.
- `POST /t3/threads/:machine/:id/messages` `{text, mode:"send"|"queue"|"steer"}` → `{ok}`; the gateway appends the `/glasses` skill (T3-30).
- `POST /t3/threads/:machine/:id/respond` `{requestId, decision?:"accept"|"acceptForSession"|"decline", answers?:{[questionId]: string|string[]}}` → `{ok}`
- `POST /t3/threads/:machine/:id/visit` → `{ok}` (marks Done as seen); opening a settled thread and messaging it un-settles it.
- `GET /t3/projects` → `[{machine, machineLabel, id, title, pinned, updatedAt}]` (from T3's `{projects:[...]}` snapshot; archived/deleted and read-only machines left out; pinned first, then newest activity) ; `POST /t3/threads` `{machine, projectId, text}` → `{machine, id}` (gateway-chosen `threadId` with `reuseExistingThread`, so a retry never starts a second run)
- `GET /t3/usage` → `[{provider, machine, instanceId, windows:[{label, usedPercent, resetsAt, windowDurationMins, pace:"ahead"|"on"|"under"}], credits:[{creditId, label, description, availableCount}]}]` ; `POST /t3/usage/reset` `{machine, instanceId, creditId}` → `{ok}`
- `GET /t3/environments` → `[Machine]`, the same rows as `/t3/threads.machines`. T3 errors are `{error}` naming the machine and reason; the phone shows that text.

**Media** (MED-*) — playback control of phone apps happens on the phone via MediaSessions; these cover server-side sources.
- `GET /spotify/now` → `NowPlaying|null` where `NowPlaying = {source:"spotify"|"abs"|"youtube", title, artist, subtitle, positionSec, durationSec, playing, device, volume, speed}`
- `GET /spotify/home` → `{discoverWeekly:{uri, name}|null, liked:{uri, name}, current:NowPlaying|null, recent:[{uri, name}], playlists:[{uri, name}], errors:["playback"|"playlists"|"recent"]}` (`recent` = recently played playlist contexts only, max 8; `playlists` = other library playlists, max 20; Composio `data_preview` responses are never used as data)
- `POST /spotify/play` `{uri, deviceId?}` (plays on the currently active device, MED-21; with none active, on the phone if it is the only device, else 409 and the glasses show the device picker, which retries with `deviceId`) ; `POST /spotify/control` `{action:"play"|"pause"|"next"|"previous"|"volume", value?}` ; `GET /spotify/devices` → `[{id, name, type, active}]` ; `POST /spotify/transfer` `{deviceId}`
- `GET /abs/recent?limit=5` → `[{id, title, author, chapter, progress, durationSec, currentTimeSec, lastPlayedAt, coverUrl?}]` (from `/api/me` mediaProgress, newest `lastUpdate` first; finished = `isFinished`, `hideFromContinueListening` or progress >= 0.97) ; `POST /abs/play/:id` → `{streamUrl, startSec, title, author, chapter, durationSec, sessionId, tracks?}` (`tracks`: `[{streamUrl, offsetSec, durationSec, title}]`) ; `POST /abs/progress/:id` `{sessionId, currentTimeSec, timeListenedSec}`
  Stream URLs use `<GATEWAY_PUBLIC_URL>/glasses/v1/abs/stream/<ticket>/<trackIndex>` (derived from the request when `GATEWAY_PUBLIC_URL` is empty); no ABS credential reaches the phone. Public `GET /abs/stream/:ticket/:track` needs only its unguessable 32-byte base64url capability, bound to that session's tracks, valid for 12 hours idle. At most 20 tickets are held in memory; oldest tickets are evicted and restart invalidates them. Authenticated `POST /abs/session/:id/close` closes the session and revokes its tickets; upstream 404/410 also revokes them. The gateway pins upstream origin to `ABS_URL`, adds the token server-side, refuses redirects, pipes audio with backpressure and client-disconnect cancellation, and forwards Range/If-Range plus 200/206/416 and Content-Type/Length/Range, Accept-Ranges, ETag, Last-Modified. Gateway logs redact tickets; ingress access logs must also redact stream paths. `startSec` remains the absolute book position; seek each file to `startSec - offsetSec`.

- `GET /youtube/lists` → `[{key, name}]` with the one curated list from `youtube.file` (`YOUTUBE_LIST_KEY`, default `picks`; `YOUTUBE_LIST_NAME`, default `Picks`), or `[]` without a file ; `GET /youtube/list/<key>?limit=25` → `[{videoId, title, channel, durationSec}]`, newest first, at most 25. `watch-later` and `history` keys return HTTP 404 with an explanation: neither list is readable through the official API. No scraping.

**Calendar** — `GET /calendar/agenda?days=3` → `[{day:"TODAY"|"TOMORROW"|"FRI NOV 13", date, events:[{id, title, start, end, allDay, location, notes}]}]` Events also carry inclusive local `startDate`/`endDate`; `/home.nextEvent` adds `end`, `startDate`, `endDate`. The phone labels any event that is not today with its day ("Thu · All day", "Tomorrow 3:00 PM") at draw time.

**Health** — `GET /health/overview` → `[{key:"weight"|"readiness"|"sleep"|"hrv"|"rhr", name, value, measuredAt, series:[{date, value}] (30 days), unit, breakdown:[{label, value?, score?}]}]` Metrics add `measuredKind` (`wake`|`day`|`reading`) and `measuredDay`; breakdown rows add `key`, `unit`, `trend?` and their own `series`, which the Health detail's Y picker charts. Charts use one slot per calendar day of the 7/30-day window. `/report/latest` adds `reportDate`, `today`, `isToday`, `stale`, `ageDays`, `sourceWrittenAt`, `fallback`; an older report is titled "Report from Nov 12".

**Home Assistant** — `GET /ha/panel` → `{scenes:[{id, name, icon}], alarm:{time, style}, printers:[{id, name, state, progress, timeLeftSec, layer, layers, detail}]}` ; `POST /ha/scene` `{id}` ; `POST /ha/alarm` `{time?, style?}` where `time` is a key of `homeAssistant.alarm.times` and `style` one of `homeAssistant.alarm.styles`. Scenes and printers come from `homeAssistant.scenes` / `homeAssistant.printers`.

**Report, docs, people**
- `GET /report/latest` → `{date, generatedAt, cards:[{type, icon, heading, body}]}` (REP-02; from `REPORT_SOURCE_DIR`)
- `GET /docs` → `[{id, title, createdAt}]` ; `GET /docs/:id` → `{id, title, text}` ; `DELETE /docs/:id`
- `GET /people/recent` → `{heard:[Person], recent:[Person]}` ; `Person = {id, name, where, seenAt, notes}`. Built from recognitions the phone reports; `heard` is the last 10 minutes, `notes` is the person's glasses blurb, `where` is `lat,lon` or empty. `GET /people/:id` → `Person` plus `muted`.
- `GET /people/voices` (honours `If-None-Match`) → `{model:"wespeaker-voxceleb-resnet34-LM", dim:256, updatedAt, people:[{id, handle, name, me, muted, status:"confirmed"|"suspected"|"unlinked", blurb, profiles:[{id, embedding}]}]}`. `embedding` is base64 of 256 little-endian float32, unit length. People and their voice embeddings come from `people.json` (`PEOPLE_FILE`, see `server/gateway/people.example.json`); without it the list is empty.
- `POST /people/heard` `{events:[{id, personId, profileId, at, confidence, lat?, lon?, accuracyM?}]}` (max 200, idempotent on `id`) → `{ok, stored}`. Events for unknown, muted or own-voice people are dropped.
- `POST /people/:id/mute` `{muted:boolean}` → `{ok, muted}`.

**Conversations**
- `POST /ambient/conversations` `{clientId, device:"glasses"|"phone", startedAt, endedAt, speechSec, tz, segments:[{offsetSec, at, durationSec}], fixes:[{at, lat, lon, accuracyM?}]}` → `Conversation`. `segments` are in order, the first at offset 0; each says "this position in the speech-only audio was recorded at this UTC time". Idempotent on `clientId`. 422 when `speechSec < 30`. Optional `kind:"conversation"|"memo"` and, for memos only, `title` (≤120 chars); a memo needs only 1 s, is not refused by the pending cap and is delivered before waiting conversations.
- `PUT /ambient/conversations/:id/audio` raw body, `Content-Type: audio/ogg` (also `audio/mp4`, `audio/wav`), up to 95 MB → `Conversation`. The same bytes again are accepted; different bytes get 409.
- `GET /ambient/conversations/:id` → `Conversation = {id, state:"received"|"audio"|"uploaded"|"timed"|"failed", startedAt, endedAt, speechSec, uploadedAt, loadedAt, recordingId, error, deliveries:[{target, name, state:"pending"|"delivered"|"failed", deliveredAt?, error?}]}`. `uploaded` means transcribed and being delivered; `timed` means every routed target has it, with real times and locations per segment; `failed` means every target refused it. See [targets.md](targets.md).
- `GET /ambient/status` → `{pending, uploadedToday, lastUploadAt, lastError, targets}`.

**Notifications and pushes**
- `POST /notify/classify` `{source, app, title, body}` → `{route:"notify"|"suppress", importance}` (the classifier, NOT-02/04; the newer three-way contract is `POST /notify/route`, described in the gateway README). Requires an object with optional string fields, truncated to 100/100/200/1000 characters; ignores other fields. High-importance assistant/T3 rules require source/app identity, never body/title alone. On LLM failure, fallback remains notify at 0.5.
- `GET /events?since=<cursor>&wait=25` → `{cursor, events:[{id, seq, type, at, ...}]}` long-poll (wait clamped to 0–25 seconds). Types: `notification {source, icon, title, body, importance, open:{screen, args}}`, `doc {id, title}`, `open {screen, args}`, `media {action, ...}`, `report {date}`, `reminder {id, ...}`, `joy_reply {text, sessionId, title:"<assistant> replied", body, importance:0.85, open:{screen:"joy"}}`. Doc/reminder deletion publishes the same entity ID with `deleted:true`. Use `seq`/`cursor` for event ordering; an entity ID can recur.
- `GET /reminders` → `[{id, text, condition:{type:"place"|"person", value}, expiresAt}]` ; `DELETE /reminders/:id` ; `POST /reminders` `{text, condition, expiresAt?}` (NOT-06). Omitted expiry defaults to 30 days; expired entries are removed when reminders/state are read. New expiries must be in the future.

State limits: newest 300 notifications within 14 days, newest 200 documents, 500 unexpired reminders (409 when full), 100 assistant sessions and 100 aliases using LRU, 500 events, and 20 converted reports. Action `open`/`args`/media objects are limited to 2 KiB serialized UTF-8 (400 on excess). Writes debounce for 25 ms; `flushed()` gates durable acknowledgements. Invalid collection JSON is quarantined with a timestamp; transient read errors fail with 507 and preserve existing data. Calendar/Beeper pagination returns partial completed pages within a common 15-second budget per operation.

### 3.2 The assistant's glasses tools (`/glasses/v1/assistant/*`, scope `glasses.agent`)

Used by the assistant through its `glasses` skill ([`server/gateway/hermes-skill/glasses/SKILL.md`](../server/gateway/hermes-skill/glasses/SKILL.md), ARC-06) and the MCP server at `POST /glasses/v1/mcp`. Each call emits an event for the phone. Each call emits an event for the phone.
- `POST /assistant/notify` `{title, body, importance?, icon?, open?}` · `POST /assistant/doc` `{title, text}` · `POST /assistant/open` `{screen, args?}` · `POST /assistant/media` `{action:"play_youtube"|"play_spotify"|"pause"|..., ...}` · `POST /assistant/reminder` `{text, condition, expiresAt?}` · `GET /assistant/state` (what the glasses show; lists of reminders/docs).

`GET /assistant/state` returns `{screen, media, reminders, docs}`. Screen/media are the last requested phone actions (or null), not a live phone acknowledgement. Reminder/doc writes wait for async, serialized, fsynced storage and return the stored ID; other actions return `{ok:true}`. `/home` loads all five sources in parallel and returns defaults for unavailable parts after a common six-second budget (`weather:null`, zero unread/T3 counts, `nextEvent:null`, `nowPlaying:null`).

Beeper approval detection is a display heuristic: a non-self message whose sender display name matches the configured assistant name, in a Discord direct chat, containing both ✅/❌ and an approval/action verb (approve, confirm, allow, proceed, react, accept, reject). Group chats and self messages never receive the approval flag. Network names come from Beeper account metadata; archived and explicitly low-priority chats are omitted by default.


## Live data audit implementation (2026-11-12)

Unread summaries traverse all available chat pages independently of the display cap. As with other paginated reads, upstream failure or the common 15-second budget can leave a partial result; live upstream outage behavior still needs deployment verification.

The HTTP contract above is additive: legacy Beeper arrays remain supported. The live phone uses the paged message envelope and the independent unread summary. Display strings (chat titles, senders, text and reply quotes) never expose raw Matrix identifiers. Missing/numeric sender names resolve through the expanded participant list or become Someone; unresolved mentions become @someone. API identity fields (`id`, cursors) remain opaque routing identifiers and must not be rendered. Attachment summaries cover official Beeper `attachments` (`type`, `mimeType`, duration in seconds, `fileName`, `isGif`, `isSticker`, `isVoiceNote`), `links`, and optional message types. Link/reel markers remain U+E001/U+E002. Bridge-specific reel/post typing is defensive; actual Instagram payloads still need live verification. Sources: [Beeper message fields](https://developers.beeper.com/desktop-api-reference/typescript/resources/$shared/), [opaque message pagination](https://developers.beeper.com/desktop-api-reference/typescript/resources/messages/methods/list/).

Notification classification accepts bounded `source`, `app`, `title`, `body`, `conversationTitle`, `sender`, `network` strings and an optional boolean `isGroupConversation`. Assistant, T3, alarm/emergency/security sources always notify before promo rules; group messages addressing the owner (`owner.name`, `owner.aliases`), asking questions, making plans or sharing for them also notify before promo suppression; obvious promotions and pure group reactions suppress. Beeper conversation context comes from Android MessagingStyle/extras and available network labels. Instagram group messages addressing the owner, asking questions, making plans or sharing something for them should notify; uncertainty defaults to notify. HTTP/model failures notify at 0.5 after the same deterministic rules. The phone applies these rules locally on timeout/offline; it does not queue notifications for later classification.

T3 input, approval, request, limited and failed all belong to needs-you (Appendix B). `T3Repository.needYouCount` and Home share that definition. Across successful refreshes, transitions into needs-you and done produce thread-linked notifications (0.9/0.6), deduplicated by machine+thread+status+updatedAt; initial hydration is silent. Machine failures enter a 60-second cooldown with bounded two-second HTTP probes. Previously seen threads and last-seen timestamps survive offline periods; never-seen machines have null wire `lastSeen` and the phone displays Never seen. Thread-list machine snapshots, per-thread `machineOnline`, and `offlineCount` are exposed. Step `kind` stays raw; `label` is human text and `what` is its detail without the label prefix.

Reminders are **armed records**, not fired notifications. Creation emits a `reminder` update with `state:"armed"`; deletion emits `deleted:true`. Android `DataStore.reminders` exposes armed records, refresh and cancel. **Neither person nor place triggers are implemented.** Speaker recognition is unavailable; ambient location's last fix is private capture state and is not wired to reminder matching. Do not promise automatic reminder delivery.

Assistant errors return to idle after 1.7 seconds. Assistant dictation publishes thinking during transcription and clears listening on completion/error/cancel; a blank successful transcript keeps turn-based calls alive. Hermes tool names map to searching, remembering, saving, waiting, delegating, thinking or working, and assistant/run events map to speaking/idle. **The inspected Hermes SSE adapter provides no established emote/expression event or animation-ID contract.** The optional phone `emote` handler is retained (an `emote` row may carry a `state` name, or a numeric `anim` as a legacy id), but the gateway does not invent emotion events (JOY-03 remains limited by upstream evidence).

Voice memos stop at the 24 MiB PCM cap, preserve a valid WAV below the gateway's 25 MiB upload limit, and add a Memo saved notification with the stop reason. `MemoSnapshot.stoppedReason` is also available. Every finished memo of 1 s or more uploads by itself through the conversation route (`kind:"memo"`, Ogg/Opus encoded on the phone, WAV if the encoder is unavailable) and on to the memo delivery targets, with retry; the memo row shows saved / uploading / sent to <target> / failed. Original memos stay in the memo store and are not removed by ambient master retention/deletion. Real device recording limits still need hardware checks.

The existing R2/Y path already calls `StoreMediaControl` → `MediaRepository.control`; it was not phone-session-only. Unsupported or absent phone session actions now return false so Spotify commands reach the gateway's active device. Spotify volume uses its remote route. Gateway progress is interpolated locally only while playing, with a one-second display ticker and refresh at track end (bounded to one attempt per five seconds). `MediaRepository.current` exposes `/spotify/home.current` independently of playlist indices.

Health wire values remain numeric with units and ISO timestamps. Live mapping supplies values such as 165.4 lb, 52 ms, 54 bpm; measured times use local time (6:41 AM today / Yesterday 10:12 PM / Nov 9), and the rail uses the latest raw measurement timestamp. `Metric.hasBreakdown` is false when no observed breakdown exists. Weather condition phrases omit the temperature; unavailable weather has null temperature and `Weather.temperatureLabel` is —. Calendar mapping retains ISO times and adds `timeLabel` / `displaySubtitle`, including All day and no trailing location separator. `/home.nextEvent` now includes `allDay`.

`GET /health/battery` → `{level:number|null, measuredAt:ISO|null}`, level is a fraction 0-1. Contrary to the suspected API limitation, [Oura's current public OpenAPI](https://cloud.ouraring.com/v2/static/json/openapi-1.43.json) includes `ring_battery_level?latest=true`. Samples older than 24 hours and unavailable/unsupported responses expose null; this is cloud-synced battery, not live BLE. Android earbuds use the Bluetooth battery broadcast only for a currently connected Bluetooth audio output, clearing values on disconnect/unknown. No hidden API or reflection is used; devices that do not broadcast remain unavailable. Live status omits unknown battery rows; `Battery.level` is nullable for consumers retaining rows.

No brightness SET command is documented in `docs/rayneo-protocol.md`; `brightness_change` is an observed event. Live settings report transport brightness through `glassesBrightness` (nullable), set `brightnessReadOnly:true`, and do not store a pretend applied value. Legacy `brightness` is 0 when no reading exists; new consumers must use the nullable field. Head gesture and display position preferences remain unimplemented.

The stock-first page guidance is shared by the T3 suffix and the assistant context in `server/gateway/apis/glasses/lib/display.mjs`: Basic (stock) display: 26 columns x 5 lines per page (~130 characters). Image display (custom firmware, native 540x220): about 43 x 8 (full-width reader; 33 x 8 beside the icon rail). Write for the smaller one: 1 page is ideal, 2 is fine, 3 is tolerable, avoid 4+. The installable assistant tool skill is only `server/gateway/hermes-skill/glasses/SKILL.md`; the generic agent reply-format skill is `skills/glasses/SKILL.md`.

### Required UI integration (outside this change's scope)

- Beeper rail: use `unreadTotal`/`unreadChats`. Once `pageState.loaded` is true and messages are nonempty, initialize the cursor to the newest message once; preserve selection by message ID when prepending older pages and call `loadOlder` at the oldest boundary.
- T3: use `needYouCount` (including request) in the rail; show `offlineCount`/`machineOnline`, use step `label`, and display guest request `messages` with a normal reply action. Exact live guest-message projection fields remain unverified; an empty guest list must show that the message is unavailable rather than fabricate text.
- Reminders: show armed list/cancel and the armed confirmation, without claiming recognition/place firing.
- Spotify: wire Currently playing to `media.current`, not playlist index 1.
- Health: skip the breakdown action when `hasBreakdown` is false. Home: use `temperatureLabel` so null is —. Calendar/Home: use mapped time/subtitle fields for All day and missing locations.
- Status: omit nullable/unavailable battery rows. Settings: show `glassesBrightness` read-only; disable unsupported head gesture/display position controls.
- Memo: optionally show `stoppedReason` beside the durable Memo saved notification.

No UI, renderer, simulator or golden fixture changes are part of this implementation. Live credentials, hardware broadcasts, Spotify devices, Oura permissions, Beeper bridge variants and Hermes expression support require deployment/device verification.
