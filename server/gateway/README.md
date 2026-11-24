# RayNeo iO HUD gateway

The server half of RayNeo iO HUD: one small Node 24 process (no npm dependencies) between the Android companion app and
the services it talks to: the **Hermes** agent (chat, sessions, live voice calls, MCP tools back into the glasses),
**Beeper** (every chat network), **T3 Code** (coding-agent threads), notification routing, and delivery of voice memos
and ambient recordings to targets you choose. Optional integrations: Home Assistant, Spotify and Google Calendar through
Composio, Audiobookshelf, Oura and Weight Gurus, a morning report, a curated YouTube list, a People screen.

Nothing in here is tied to one person or one house: names, hosts, models and policies are configuration.

## Quick start

```sh
cd server/gateway
cp .env.example .env                                  # every setting, all commented out
cp gateway.config.starter.json gateway.config.json    # optional; add only the keys you need
export GLASSES_API_TOKEN=$(openssl rand -hex 32)       # the phone's token
export GLASSES_AGENT_TOKEN=$(openssl rand -hex 32)     # the Hermes agent's token
export LLM_API_KEY=...                                  # any OpenAI-compatible endpoint (LLM_BASE_URL)
GATEWAY_CONFIG=./gateway.config.json node server.mjs
```

Or with Docker: `docker-compose.example.yml` and `Dockerfile` (copy the compose file, put `gateway.config.json` in
`./config/`, `docker compose up -d`). Put it behind HTTPS before the phone reaches it over the internet.

Tests: `node --test server/gateway/test/` from the repository root (no network, no credentials, temporary state).

## Configuration

`lib/config.mjs` defines every key once (type, default, env var, description). The gateway starts from the defaults,
applies environment variables, then merges `gateway.config.json` (path in `GATEWAY_CONFIG`) over them. Problems are
reported all at once, naming the key, where it came from and what was expected, and the process exits with status 78:

```
Invalid gateway configuration:
  - unknown key ownr (did you mean owner?)
  - beeper.mentions in gateway.config.json must be one of pill, plain (got "loud")
  - tz "Mars/Olympus" is not a valid IANA time zone (GATEWAY_TZ)
```

`.env.example`, `gateway.config.example.json` (a reference listing every key) and `gateway.config.starter.json` (the file
to copy) are generated from the schema (`node tools/config-examples.mjs`; a test fails when they are stale). The main groups:

| Group | What it sets |
| --- | --- |
| `owner {name, aliases}` | Who wears the glasses. Used in every prompt (`{{owner}}`), to recognise the owner in chats (mentions, "not for the owner" in groups, promotions that name them) and as the memo speaker. Default `the user`. |
| `assistant {name, workerName}` | The assistant's name on the glasses and in prompts (default `Hermes`), and the notification classifier persona (default `Triage`). |
| `tz`, `home {lat, lon}` | Time zone (default `UTC`) and an optional location for the weather card (off until set). |
| `llm`, `models` | One OpenAI-compatible endpoint (`LLM_BASE_URL`, key from the secret `LLM_API_KEY`) and the models for transcription, cleanup, fast, smart and classifier work. |
| `hermes`, `beeper`, `t3` | The native integrations. Each has `enabled`; hosts default to `localhost`. `hermes.callUrl` (`HERMES_CALL_URL`) turns on live voice calls. |
| `notify` | Notification policy file, classifier prompt override, the owner's own standard, own chats, time budget. |
| `cleanup` | Dictation cleanup check, prompt override and a vocabulary file. |
| `targets`, `routes` | Where memos, recordings and dictations go (below). |
| `secrets` | Where credentials come from (below). |
| `clients` | Which token (by secret name) may use which scope: `glasses` (phone), `glasses.agent` (Hermes), `tv`. |

`GET /glasses/v1/config` (phone scope) returns the public part: assistant and owner names, enabled integrations, the
targets (`id`, `name`, `kind` only), the routes and the notification policy, so the phone can seed its own settings.
It never contains secret names or values, hosts, URLs or file paths.

### Secrets

Config only ever **names** secrets. `GATEWAY_SECRETS` picks the provider:

- `env` (default): the environment variable of that name, or the file named by `<NAME>_FILE` (Docker secrets).
- `file`: a JSON object `{"NAME": "value"}` at `GATEWAY_SECRETS_FILE`; rotated tokens are written back for names in
  `GATEWAY_SECRET_WRITABLE`.
- `infisical`: Infisical universal auth from `INFISICAL_URL`, `INFISICAL_CLIENT_ID`, `INFISICAL_CLIENT_SECRET`,
  `INFISICAL_PROJECT_ID`, `INFISICAL_ENV`, `INFISICAL_PATHS` (or an env-style `INFISICAL_ENV_FILE`). It requires
  `GATEWAY_SECRET_ALLOW`, the list of names the gateway may read.

More providers: `registerSecretsProvider(name, factory)` in `lib/secrets.mjs`. Lookups are cached
(`GATEWAY_SECRET_TTL_MS`), at most four run at once, and a provider hiccup keeps serving the last value.

### Delivery targets (memos, recordings, dictations)

The phone uploads a voice memo or an ambient recording (`POST /ambient/conversations` with a timing manifest, then
`PUT /ambient/conversations/:id/audio`, then it polls `GET /ambient/conversations/:id`). The worker transcribes the
audio with `models.transcribe` (`verbose_json` segments when the endpoint offers them), puts every segment at the real
time it was said (the manifest maps the speech-only audio back onto the clock) and at the nearest location fix, then
hands it to every target routed for that kind (`apis/glasses/lib/targets.mjs`):

```json
"targets": [
  { "id": "local", "name": "Recordings folder", "kind": "directory", "path": "./recordings" },
  { "id": "notes", "name": "Notes webhook", "kind": "webhook", "url": "https://notes.example.com/hook",
    "auth": { "type": "hmac", "secret": "NOTES_WEBHOOK_SECRET" }, "audio": "multipart", "retries": 3, "backoffMs": 2000 },
  { "id": "assistant", "name": "Hermes", "kind": "hermes" }
],
"routes": { "memo": ["local", "assistant"], "ambient": ["local"], "dictation": ["notes"] }
```

- `directory`: `<path>/<YYYY-MM-DD>/<time>-<kind>-<id>.{json,md}` plus the audio (`audio: false` skips it; `formats`).
- `webhook`: POSTs `{event: "<kind>.ready", target, recording}` as JSON, or multipart (`metadata` JSON part plus an
  `audio` file part) with `audio: "multipart"`. `auth: {type: "bearer", secret}` sends `Authorization: Bearer`;
  `{type: "hmac", secret, header?}` sends `X-Signature: sha256=<hex HMAC-SHA256 of "<X-Timestamp>.<JSON>">` and
  `X-Timestamp`. Every request carries `X-Delivery-Id`. Network errors, 408, 429 and 5xx are retried with exponential
  backoff (`retries`, `backoffMs`, `timeoutMs`); any other 4xx fails that delivery at once.
- `hermes`: posts the transcript into a Hermes session (a new one per item, or `session`), with an optional
  `message` template (`{{owner}} {{assistant}} {{kind}} {{title}} {{date}} {{transcript}}`).
- Your own kind: `registerTargetKind('s3', { validate(spec) -> [problems], create(spec, ctx, deps) -> { deliver(item) } })`.
  `deliver` resolves when delivered; throw to retry, or throw an error with `permanent: true` to fail at once.

Without `targets` there is one `directory` target under `<dataDir>/recordings`, and memos and recordings go to every
target. A failing target is retried with backoff up to `CONVERSATIONS_MAX_ATTEMPTS`; the others are not held up.

The phone sees `state` (`received`, `audio`, `uploaded` = transcribed and delivering, `timed` = done, `failed` = every
target refused) and `deliveries: [{target, name, state: pending|delivered|failed, deliveredAt?, error?}]`.
`GET /ambient/status` returns `{pending, uploadedToday, lastUploadAt, lastError, targets}`. Queue rows live in the
gateway store (collection `recordings`), so no database is needed.

### People

`people.file` (`PEOPLE_FILE`, see `people.example.json`) lists people with optional voice embeddings for on-phone
recognition, notes and a last-seen place. Heard events and mutes are kept in the gateway store. Without the file the
People screen is empty.

### Notification policy

`apis/glasses/notify-policy.default.json` is the default policy: which kinds are on (Beeper, T3 Code, the assistant
and Hermes, calls, calendar, clock), package names, the deny list, apps you allow (`apps: {"<package>": "popup"|"stream"}`)
and an opt-in dating preset (`dating.enabled`). Copy it, change it, and point `NOTIFY_POLICY_FILE` at the copy; your
file is merged over the default and validated. The phone mirrors the rules (`test/fixtures/notify-policy.json` is the
shared table both sides are tested against).

## Layout and extending it

- `server.mjs`: HTTP/WebSocket routing, base-path handling, bearer scopes, API module loader.
- `lib/`: `config.mjs`, `secrets.mjs`, the JSON document store, HTTP errors, a dependency-free RFC 6455 WebSocket.
- `apis/glasses/`: the phone API (`/glasses/v1`). `lib/context.mjs` builds `ctx.config` from the gateway config;
  `lib/` holds the adapters, `routes/` one area per file exporting `register(r, ctx)`, `prompts/` the prompts.
- `apis/tv/`: a small API for an Android TV launcher (`/tv/v1`, scope `tv`): recently played Spotify playlists, Beeper
  chats as share destinations (names only, never message content), and sharing a Spotify Jam link.
- `hermes-skill/glasses/SKILL.md`: the skill to install into Hermes so the agent can drive the glasses.
- `tools/`: `config-examples.mjs` (regenerates the examples), `cleanup-eval.mjs` (dictation cleanup evals against a
  live model: `--proxy <base url>` with `LLM_API_KEY`, or `--gateway <url>`).

To add a glasses endpoint, create `apis/glasses/routes/<area>.mjs` exporting `register(r, ctx)` and use
`r.get/post/put/del('/path', handler)`. Use `ctx.fetch` with an explicit timeout and `ctx.secret('NAME')`. Never log
URLs containing credentials, authorization headers or upstream error bodies. To add another API, create
`apis/<name>/index.mjs` exporting `register(api, deps)` and add its scope to `clients`.

WebSocket modules register `r.ws('/path', ({ws, req, client, params, query}) => { ... }, opts)` (other APIs use `api.ws`). Upgrade routes use the same secret-name bearer authentication, scopes and prefixes as HTTP. `client` is the authenticated token's secret name, never its value. Only the `Authorization` header authenticates: URL tokens and `Sec-WebSocket-Protocol: bearer.*` are not accepted. Unknown/unauthorized upgrades receive HTTP errors before closing. HTTP and WebSocket route tables are separate.

`ws.on('message', (bytes, binary) => ...)` receives complete messages, preserving text bytes; `ws.send(bytes, binary)` preserves their kind. A false return means pause the source with `source.pause()` until `ws` emits `drain`; `source.resume()` restarts reads. The default outgoing buffer cap is 2 MiB (overflow closes 1013), maximum incoming message is 1 MiB (1009), idle timeout is 60 seconds (1001), and close-handshake deadline is two seconds. `closing` reports local/remote close initiation; `close` reports final transport closure. Use `close(code, reason)` on the other peer for cleanup. Routes can override transport limits through `opts.websocket`. Frames require client masks, validate UTF-8, and support fragmentation and interleaved ping/pong/close; extensions and subprotocols are not negotiated. `connectWebSocket(url, {headers, signal, timeoutMs})` uses `node:http`/`https` upgrade with an eight-second default handshake deadline and the same bounded transport. Local Node 24.18's built-in client supports a headers option; the shared client supplies explicit drain handling, rejection status and full RFC close-code forwarding across Node 24 releases.

## Routes

All below use `/glasses/v1` and scope `glasses` unless specified. `/glasses/v1/...`, `/api/glasses/v1/...` and
`<basePath>/glasses/v1/...` are equivalent. `GET /health` is public liveness; `GET /glasses/v1/ping` is public.

| Area | Routes |
| --- | --- |
| Config | `GET /config` (public subset of the gateway config) |
| Home/events | `GET /home`, `GET /events?since=&wait=` |
| Assistant | `POST /assistant/chat`, `POST /assistant/sessions`, `GET /assistant/call` (WebSocket), `GET /assistant/call/status` |
| Voice | `POST /voice/dictate?target=&chatId=&machine=&thread=&project=`, `POST /voice/cleanup`, `POST /voice/title` |
| Beeper | `GET /beeper/summary`, `GET /beeper/chats`, `GET/POST /beeper/chats/:chatId/messages`, `POST/DELETE /beeper/chats/:chatId/messages/:messageId/reactions`, `POST /beeper/chats/:chatId/read`, `POST/DELETE /beeper/chats/:chatId/spam`, `GET /beeper/spam`, `POST /beeper/resolve`, `GET /beeper/find`, `GET /beeper/pins`, `POST/DELETE /beeper/chats/:chatId/pin` |
| T3 | `GET /t3/threads`, `GET /t3/settled`, `GET /t3/threads/:machine/:id`, `POST /t3/threads/:machine/:id/messages`, `POST /t3/threads/:machine/:id/respond`, `POST /t3/threads/:machine/:id/visit`, `GET /t3/projects`, `POST /t3/threads`, `GET /t3/usage`, `POST /t3/usage/reset`, `GET /t3/environments`, `GET/POST /t3/watch`, `GET/POST /t3/notify/settings` |
| Spotify | `GET /spotify/now`, `GET /spotify/home`, `POST /spotify/play`, `POST /spotify/control`, `GET /spotify/devices`, `POST /spotify/transfer` |
| Audiobookshelf | `GET /abs/recent?limit=5`, `POST /abs/play/:id`, `POST /abs/progress/:id`, `POST /abs/session/:id/close`, public `GET /abs/stream/:ticket/:track` |
| YouTube | `GET /youtube/lists`, `GET /youtube/list/:key?limit=25` |
| Calendar/health/HA | `GET /calendar/agenda?days=3`, `GET /health/overview`, `GET /ha/panel`, `POST /ha/scene`, `POST /ha/alarm` |
| Report/docs | `GET /report/latest`, `POST /report/regenerate`, `GET /docs`, `GET/DELETE /docs/:id` |
| People | `GET /people/voices`, `POST /people/heard`, `GET /people/recent`, `GET /people/:id`, `POST /people/:id/mute` |
| Recordings | `POST /ambient/conversations`, `PUT /ambient/conversations/:id/audio`, `GET /ambient/conversations/:id`, `GET /ambient/status` |
| Notifications/reminders | `POST /notify/route`, `POST /notify/hermes` (scope `glasses.agent`), `POST /notify/classify` (old phone builds), `GET/POST /reminders`, `DELETE /reminders/:id` |
| Diagnostics | `GET /_diag/composio?slug=&account=&args=` |
| Assistant tools | `POST /assistant/notify`, `POST /assistant/doc`, `POST /assistant/open`, `POST /assistant/media`, `POST /assistant/reminder`, `GET /assistant/state` (scope `glasses.agent`) |
| Agent control | `GET /agent/link` (WebSocket, phone), `POST /mcp` (MCP server `glasses`, scope `glasses.agent`), `GET /agent/ops`, `GET /agent/context`, `POST /agent/call {op, args}` (scope `glasses.agent`) |

Spotify home finds Discover Weekly by its actual name among the user's playlists, or uses `SPOTIFY_DISCOVER_WEEKLY_URI` (`spotify:playlist:<id>`) when it is not in the library (the API does not list it for most accounts). Liked Songs always has URI `spotify:collection`; playback sends up to 45 saved-track URIs, otherwise the context `spotify:user:<id>:collection` (`SPOTIFY_USER_ID`, else derived from the playlists `href`). `recent` holds only distinct recently played playlist contexts (three cursor pages of five tracks, at most eight), named from the library; a context outside the library is dropped instead of shown as "Playlist". `playlists` holds up to 20 other library playlists. `errors` lists the parts that failed (`playback`, `playlists`, `recent`); each upstream failure is logged as `glasses spotify <key> <reason>`.

Composio replaces any tool response above roughly 30 KB with a lossy `data_preview` (arrays cut to one or two items plus "...N more items"). The gateway never treats a preview as data: it requests small pages (playlists 15, recently played 5, saved tracks 15 with `market`), and re-reads a truncated page as pages of five. The library is cached for ten minutes (one minute when a page failed).

Playback resolves the current device from Spotify playback state. With nothing active, `SPOTIFY_COLD_START=phone` (default) plays on the only available device when it is a smartphone; otherwise `POST /spotify/play` answers 409 `No active Spotify device` and the glasses show the device picker, which retries with `{uri, deviceId}`. `SPOTIFY_COLD_START=off` always answers 409. Controls still require an active device. MCP tool argument spellings require live verification using the diagnostic route; all mappings live in the Spotify/calendar tables.

YouTube: with `youtube.file` set, `GET /youtube/lists` returns one list `{key, name}` (`youtube.listKey`,
`youtube.listName`) and `GET /youtube/list/:key` returns it newest first, at most 25, re-reading the file on every
request (mount its directory, not the single file, if another program replaces it). `watch-later` and `history` are
404: the official API cannot read them.

### Live voice call (Hermes)

The phone upgrades `GET /glasses/v1/assistant/call` with its bearer. The gateway opens `HERMES_CALL_URL` with
`Authorization: Bearer <HERMES_CALL_TOKEN>` and `X-Assistant-Device: <HERMES_CALL_DEVICE>` (the header name is
`hermes.callDeviceHeader` / `HERMES_CALL_DEVICE_HEADER`, for a voice plugin that expects another name) and relays text and binary
frames unchanged and in order. A reconnect carries `X-Assistant-Call-Resume`. The voice endpoint is your Hermes voice
plugin; the gateway does not interpret its messages.

One call per authenticated phone-token identity is allowed in this gateway process. A second receives `{"type":"hangup","reason":"busy"}` and close 1008 unless the old call has received no traffic from either peer for **more than** 30 seconds; then the old call closes 1001 and is replaced. Every call ends after `HERMES_CALL_MAX_MS` (default 4 hours; 0 disables), a runaway guard; both peers get a WebSocket ping every 20 s. A reconnect from the phone carries `X-Assistant-Call-Resume` and always replaces that identity's live call (no busy answer). Keep this service at one replica; reservations are in memory. Unexpected disconnect/error closes the peer; valid close codes/reasons propagate (local-only 1006 maps to 1011). Startup and each outgoing queue are bounded at 2 MiB; overflow closes both peers with 1013. No audio or credentials are logged, only open/close, code, duration and byte totals.

Missing config/credential, an eight-second connection-setup timeout, handshake refusal (including HTTP 401/404), or unreachable upstream sends `{"type":"hangup","reason":"unavailable","detail":"<short safe explanation>"}` then closes 1011, allowing the app to fall back to turn-based voice. An empty `HERMES_CALL_URL` explicitly disables calls. `GET /assistant/call/status` returns `{available:boolean,reason?:string}` with a shared 30-second cache and three-second overall budget. It checks secret resolution and HEAD to the upstream origin without starting a call or following redirects. Any HTTP response proves the host answers (including 404/405 from WebSocket-only servers). This is a reachability hint, not proof that the credential or call endpoint will be accepted; the actual upgrade and fallback frame remain authoritative.

ABS starts a playback session and returns gateway URLs such as `<publicUrl>/glasses/v1/abs/stream/<ticket>/<trackIndex>`. `GATEWAY_PUBLIC_URL` sets the public gateway base, including its path prefix (empty: the scheme and host the phone used, from `X-Forwarded-Proto`/`X-Forwarded-Host` or `Host`); `ABS_URL` is the private upstream. The phone plays these URLs directly, without extra headers. The ABS API token stays on the gateway. `GET /abs/stream/:ticket/:track` is public: its 32-byte random base64url ticket authorizes only the session's fixed track list. Tickets expire after 12 hours idle, are lost on restart, and are capped at 20 (oldest evicted). `POST /abs/session/:id/close` closes a session and revokes its tickets immediately; an upstream 404/410 also revokes them. Active streams stop on client disconnect or ticket revocation. `GET /abs/recent` reads the user's `mediaProgress` from `GET /api/me` (items-in-progress rows carry no progress or chapters), skips podcast episodes and books that are finished (`isFinished`, `hideFromContinueListening`, or progress >= 0.97, `ABS_FINISHED_AT`), sorts by `lastUpdate` newest first, and returns up to `limit` (default 5, max 8) with chapter from the expanded item. The gateway never writes finished state to ABS. Session sync/close accept ABS's plain-text `OK`. Audio is piped with backpressure; Range/If-Range and audio response metadata/status (200/206/416) pass through, and redirects are refused. Gateway logs redact tickets; configure ingress access logs to redact `/abs/stream/*` as well. Never log stream URLs.

For multiple audio files, `startSec` is the absolute book position; `tracks` contains `{streamUrl, offsetSec, durationSec, title}`. Seek the selected file to `startSec - offsetSec` and continue with following tracks. Returned book details also include `chapter` and `durationSec`. The playback JSON shape is unchanged; stream URLs now point to this gateway.

Calendar selects primary and other selected/nonhidden calendars, primary first, deduplicates shared events by iCal UID and occurrence start, and groups them in the configured time zone (`GATEWAY_TZ`). The rolling `[now, now + days]` window includes its partial final calendar day. All-day start/end values are converted to UTC at local midnight, preserving exclusive end dates. Every event also carries inclusive local `startDate`/`endDate` (`YYYY-MM-DD`; all-day dates are Google's verbatim, end minus one day), and all-day events are grouped by those dates. `/home.nextEvent` is a timed event in progress or still to come today, else an all-day event covering today, else the earliest future event, with `end`, `startDate` and `endDate`; the phone labels non-today events with their day. Health returns five independent rows; unavailable metrics have `value:null`, `measuredAt:null`, and empty series. Series have observed daily values within the last 30 days; no invented samples. Zero readings (Weight Gurus' unmeasured body fat/muscle, empty Oura fields) are not samples and are omitted. `measuredAt` for sleep, readiness, HRV and RHR is the wake time (`bedtime_end` of the day's longest period; never Oura's date-only `timestamp`), with `measuredKind` (`wake`, `day` or `reading`) and `measuredDay`. Breakdown rows carry `key`, `unit` (`score`, `min`, `clock`, `%`, `ms`, `bpm`, `brpm`, `°C`, `lb`), the latest `value`/`score`, an optional `trend` (`up`/`down`/`flat`, weight changes are signed) and their own 30-day `series`, so the phone can chart any sleep quantity or readiness/sleep contributor. Body fat and muscle are tenths of a percent upstream. Home Assistant: the panel (scenes, an optional alarm card and printer cards) is `homeAssistant` in the config; with
nothing configured the panel is empty. A printer card finds a "time left" sensor by `entityPrefix`; none means
`timeLeftSec: null`.

### T3 machines

`t3.machinesFile` (default `<dataDir>/t3-machines.json`, see `t3-machines.example.json`) lists the T3 Code servers,
each with the name of the secret holding its token. `T3_MACHINE_URLS` overrides a machine's URL for the gateway;
tailnet-only machines (`*.ts.net`) go through `T3_TAILNET_PROXY` (a Tailscale sidecar's SOCKS5 or HTTP proxy) or a
per-machine `T3_MACHINE_PROXIES` entry. Prompts sent from the glasses get a short suffix (`T3_GLASSES_SUFFIX`).

Machine state is one record per machine, shared by `/t3/threads.machines`, `/t3/environments`, the root `x/y online` count and the offline count. A background poll marks a machine offline only after two consecutive unreachable results (network error, timeout, or a 502/503/504 from the relay in front of T3), then skips it for 15 s doubling to 60 s while it stays down; any success resets it. User actions (open a thread, send, answer, visit, new thread, reset credit) always probe the machine even during that backoff and retry once at once when the first attempt failed before the command reached T3 (never after the WebSocket request frame was written). Every transition (`t3 machine <alias> offline|online <cause>`) and every rejected RPC (`t3 rpc <alias> <method> rejected <tag>`) is logged once without credentials. Errors name the machine and the reason (`<label> unreachable (network error)`, `<label> unreachable (HTTP 502)`, `<label>: not found (HTTP 404)`, `<label> is read-only; re-pair the gateway with write access`), and the phone shows that text instead of `Gateway error (503)`.

### Beeper unread, mute, spam and push

- `GET /beeper/summary`: `unread` (and `unreadChats`) count **conversations** that are unread (`unreadCount > 0` or marked unread), in the inbox (not archived or low priority), not muted unless they carry an unread @mention (`unreadMentionsCount > 0`), and not spam. `unreadMessages` is the message total over those conversations; `quiet: {muted, spam}` counts the unread conversations left out; `total` is the number of listed chats.
- `GET /beeper/chats?limit=100&offset=0` (limit default 100, max 500): counting chats first, then every other non-archived chat (muted, spam and low-priority included) by recency. Additive row fields: `type`, `isGroup`, `muted`, `mentions`, `markedUnread`, `lowPriority`, `spam`, `spamConfidence`, `spamCategory`, `spamSource`, `counts`, `participants: {count, names}`, `lastActivity`.
- `POST /beeper/resolve` `{title, conversationTitle, sender, text, network, isGroup}` resolves a Beeper phone notification to `{resolved, match, chatId, name, network, isGroup, muted, mentions, spam: {spam, verdict, confidence, source, category}, notify, importance (0.3 for OTP/transactional, else null), lowImportance, reason, open: {app:"beeper", target: chatId}}`, or `{resolved:false, reason, candidates}` (ambiguous titles stay unresolved; 1.5 s budget; fails open). Library form: `resolveChat(ctx, input)` in `lib/beeper.mjs`.
- Spam: store collection `beeper-spam` keyed by chat ID `{verdict: spam|ham, confidence, category, reason, source: model|rule|user, basis, network, classifiedAt}`. The fast model classifies, in the background (single flight, at most 10 per minute), unread single chats on phone-number networks whose title is a bare number or short code. A verdict takes effect at `BEEPER_SPAM_THRESHOLD` (default 0.9) or when `source` is `user`. OTP/transactional/personal are never spam. A message the owner sends turns an automatic verdict into ham. `POST /beeper/chats/:chatId/spam {spam: bool}` stores a user override, `DELETE` clears it, `GET /beeper/spam` lists verdicts. Nothing is ever written upstream to Beeper for spam.
- Priority (`lib/beeper-priority.mjs`): `GET /beeper/chats?order=priority|recent` (default `priority`). Priority lists pinned chats (Beeper `isPinned`, a Beeper label named Favorites/Starred/VIP/Priority/Important, or a glasses pin), then important unread chats (score >= 0.5, highest first), other counting unread chats, quiet unread chats (muted, spam, low priority), then the rest, each by recency. Recent lists the pins, then the unread order. The score is deterministic: @mention 0.5, a question from the other side 0.3 in a DM (0.1 in a group), a direct chat with a person 0.2, the owner wrote 3+ of the last page of messages within 14 days 0.2, their median reply under 15 min 0.2 (message history is read only for counting unread chats, cached 10 min, 1.2 s budget). Extra row fields: `priority` (pinned|important|unread|quiet|read), `importance`, `why`, `pinned`, `glassesPinned`. The unread badge is unchanged.
- Find: `GET /beeper/find?q=<spoken name>&limit=5` drops filler words ("find the chat with ...") and fuzzy-matches chat titles and participant names (prefixes, word matches, misheard spellings) over every listed chat. It returns `{query, confident, matches: [row + match: {score, on}]}`; a DM with the person ranks above a group they are in.
- Glasses pins: store collection `beeper-pins` `{id: chatId, name, pinnedAt}`. `POST /beeper/chats/:chatId/pin {pinned: bool}`, `DELETE /beeper/chats/:chatId/pin`, `GET /beeper/pins` (at most 50). The assistant reads them with the read-only MCP tool `glasses_beeper_pins`. Nothing is written to Beeper.
- Push: the worker (`GATEWAY_WORKER=1`) subscribes to Beeper's `/v1/ws` (`subscriptions.set ["*"]`) and publishes one debounced `beeper` event `{chatIds, summary}` on `/events` (1 s trailing, at most 3 s, at least 3 s apart; one reconcile event with empty `chatIds` per (re)connect). Only the newest `beeper` hint is kept in the event log (recent `chatIds` are folded into it), so chat bursts never evict other events; the phone also conflates them into at most one running and one queued refresh. `BEEPER_LIVE=0` / `BEEPER_SPAM=0` switch push / classification off.

**Context-aware dictation.** The phone adds the destination to `/voice/dictate`: `chatId` for Beeper, `machine`
plus `thread` (or `project`) for T3, `target=assistant` for words sent to the assistant as said. Cleanup
(`apis/glasses/lib/cleanup.mjs`) builds the prompt from the generic `prompts/cleanup.md` (or `CLEANUP_PROMPT_FILE`),
your vocabulary (`CLEANUP_VOCAB_FILE`: `{"terms": [...], "corrections": {"heard": "meant"}}`) and
`prompts/cleanup-glasses.md`, wraps the transcript in a per-request random delimiter, adds few-shot turns showing
instruction-like dictation being cleaned rather than carried out, and appends the Beeper chat's numbered participants
or the T3 vocabulary (live `server.getConfig` lists merged with `prompts/t3-vocab.json` and `T3_VOCAB_FILE`). A
deterministic pass then repairs `ultrathink`, `/command` and hyphenated skill names, and `CLEANUP_GUARD` falls back
to the raw transcript when the result looks like a translation, an answer or a rewrite. With `routes.dictation` set,
every finished dictation is also delivered (text only) to those targets.

### T3 notifications (gateway-owned, `lib/t3-notify.mjs`)

The worker (`GATEWAY_WORKER=1`; `T3_NOTIFY=0` switches it off) polls every machine's shell snapshot every 20 s (`T3_NOTIFY_EVERY_MS`, at least 5000; the snapshot is the same cached read the thread list uses, and offline machines in backoff are skipped). Per root thread it detects **needs-you** (status `input`, `approval` or `request`: approval request, question/choice, user input, guest request; one notification per pending request id) and **finished** (a thread seen working, waiting or needing you is now `done`, `settled`, `failed` or `limited`, or `ready` when the run ended while the thread was open on the desktop; one per run). Each is routed through `routeNotification` (kind `t3`, `chatId` `machine:threadId`), stored in `notifications` and published as a `notification` event: `{id: "t3:<machine>:<thread>:<status>:<updatedAt>", source: "T3", icon: "t3", title, body ("Needs you · approve npm test", "Finished · <last agent message>"), route, reason, importance, open: {app: "t3", target, screen: "t3", args: {target}}}`.

Policy: a watched thread notifies both kinds (needs-you = popup, finished = stream). Watches are explicit (`POST /t3/watch`) or automatic: creating a thread, sending to one or answering one from the glasses watches it for 24 h (`auto: true, expiresAt` in `GET /t3/watch`; repeated use extends it; an explicit watch never expires). "Notify for all threads needing me" (`GET/POST /t3/notify/settings {needsAll}`, default on, `T3_NOTIFY_NEEDS_ALL=0` changes the default) also pops up needs-you on unwatched threads (reason `t3.needs-you.all`); finished on an unwatched thread never notifies. State is one document per thread in the store collection `t3-notify` (`t:<machine>:<thread>`: last status and the needs/finished signatures; capped at 3000, least recently written evicted), with the setting and the per-machine baselines (`m:<machine>`) kept apart in `t3-notify-meta` so that cap can never reset them, so restarts neither repeat nor drop a transition. The first scan of a machine is a silent baseline. The T3 Code Android app's own notifications are no longer used: `/notify/route` suppresses them (`t3.app`), and the phone no longer derives T3 notifications from its thread poll.

## Notification routing (`POST /notify/route`)

Body: `{kind: "beeper"|"t3"|"call"|"assistant"|"calendar"|"clock"|"app"|"hermes", source?, app?, title?, body?,
conversationTitle?, sender?, network?, isGroup?, chatId?, channelId?, category?, contact?: {known, starred?, name?},
callerVerified?: "passed"|"failed"|"none", priorAnsweredCalls?, ownerCalledNumber?, privateNumber?, spam?, business?,
debug?, watched?, context?}` (`joy` is accepted as an older name for `assistant`). Answer: `{route:
"suppress"|"stream"|"popup", importance 0..1, reason, by: "rule"|"model"|"fallback", open?: {app, target?}, debug?}`.
`stream` = the Notifications list only; `popup` = the list and a card that lights the lens.

Deterministic rules first (the policy above), then the classifier model for semantic cases. Its prompt is
`prompts/notify-route.md` (first line `version: <id>`; `{{owner}}`, `{{assistant}}`, `{{worker}}` and
`{{standard}}` are filled in), or `NOTIFY_PROMPT_FILE`. The call uses `models.classifier`, temperature 0, JSON mode
and `NOTIFY_TIMEOUT_MS`. The output must be exactly `{route, importance, reason}`; a popup below 0.6 becomes stream.
On any model failure the answer is the fallback, which never pops up. One log line per decision, never any
notification text.

### Conversation context and the guard (`apis/glasses/lib/notify-context.mjs`)

The classifier's user message is a delimited text block: a header (kind, local time, chat/thread/channel), `CONTEXT ... END CONTEXT`
(up to 5 earlier turns from the 4 hours before; a turn is one speaker's consecutive messages; each line
`[-12 min] Sam: text`, with `<- already notified them: popup|stream` or `<- not shown: suppress` when that message was
routed), then `TARGET MESSAGE` (Beeper, apps) or `PROMPT` / `RESPONSE` (T3, Hermes). An opener has no context.

- **Beeper**: the gateway builds the context itself: the chat from `resolveChat`, its recent messages from the Beeper
  Desktop API (`GET /chats/:id/messages`), the target matched by text, and the store collection `notify-decisions`
  (one document per conversation: message id/time -> route and importance, pruned after 4 h, capped at 500 documents).
  Every non-debug decision is recorded there. A body `context` is honoured only with debug (evals).
- **Guard** on the classifier's Beeper answer: in the same run of messages from one sender, after a popup only a message the classifier rates
  clearly more urgent (importance >= that popup's + 0.15, or >= 0.95) pops up again, else stream (`guard.same-run`); a
  follow-up to an already-notified message of the same sender that asks nothing new is suppressed when it is an
  acknowledgement or banter (`guard.ack`), else at most stream (`guard.follow-up`).
- **T3**: the notifier passes the run's prompt (the user-role message that started it, `agentWritten` when another
  agent wrote it), the response (the agent's final message), timestamps, thread title, project and earlier turns. With
  that context the classifier judges watched runs and needs-you; the stage rule (needs you = popup, finished = stream) is the
  fallback when the classifier is unavailable, and a needs-you run the classifier wants to suppress is kept in the list (`guard.t3-needs-floor`).
- **Hermes** (`POST /notify/hermes`, scope `glasses.agent`, `lib/notify-hermes.mjs`): one completed exchange from the
  glasses-notify Hermes plugin (`server/hermes-plugins/glasses-notify`): `{platform, chat, chatId?, threadId?, isDM,
  sender: owner|other|scheduler|system, senderName?, inboundKind?, delivered?, job?, prompt: {at, text}, response: {at,
  text}, turns?, sessionId?, turnId?}`. Rules (kind `hermes`, mirrored on the phone): `api_server` (glasses) suppress,
  empty or `[SILENT]`/`NO_REPLY` suppress, a scheduled job kept local (`delivered: false`) suppress; everything else
  the classifier, fallback stream. Not suppressed -> stored in `notifications` (`source: <assistant name>`, `icon: joy`, `open: {screen: joy, app:
  joy}`; `joy` is the phone's id for the assistant screen) and published; the same exchange is never stored twice (`route: duplicate`). `?debug=1` or `debug: true`
  routes only (no store, no publish, no decision recorded).

