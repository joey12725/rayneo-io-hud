# Configuration reference

Everything that differs between one installation and another is configuration: names, hosts, models, policies, integrations and delivery targets. This page lists it all, in four places:

1. [The gateway](#gateway) (`server/gateway`): environment variables and `gateway.config.json`.
2. [Secrets](#secrets): the credentials the gateway reads by name.
3. [The phone app](#phone-app): build properties, `secrets.properties` and the Settings page.
4. [Tools and plugins](#tools-and-plugins): the Hermes plugin, the web HUD and the developer tools.

## Gateway

The gateway starts from built-in defaults, applies environment variables, then merges `gateway.config.json` (path in `GATEWAY_CONFIG`) over them, so a key set in the file wins over its environment variable. The exception is the deployment keys `port`, `host`, `dataDir` and `worker`: when their environment variable (`PORT`, `HOST`, `GATEWAY_DATA`, `GATEWAY_WORKER`) is set, it wins, so a container's environment is never undone by a config file. Relative file and directory paths in the config file (`people.file`, `t3.machinesFile`, `notify.policyFile`, `report.sourceDir`, `youtube.file`, `secrets.file` and the other `*File` keys) resolve next to the config file; relative paths from environment variables resolve against the working directory. A `directory` delivery target's `path` resolves against `dataDir`. Every key is optional. Invalid values are reported all at once, naming the key and where it came from, and the process exits with status 78.

Two generated examples document every key: [`server/gateway/.env.example`](../server/gateway/.env.example) and [`server/gateway/gateway.config.example.json`](../server/gateway/gateway.config.example.json) (`node server/gateway/tools/config-examples.mjs` rewrites them). The JSON one is a reference: do not copy it whole, because every key in your config file overrides the matching environment variable. Start from [`server/gateway/gateway.config.starter.json`](../server/gateway/gateway.config.starter.json) (owner and assistant names only) and add the keys you need. In the JSON file, nested keys are nested objects: `owner.name` is `{"owner": {"name": "..."}}`.

Values of type `string[]` are comma-separated in environment variables and arrays in JSON. Values of type `json` are JSON in environment variables too.

### Gateway keys

The table below is generated from the gateway's schema (`server/gateway/lib/config.mjs`) by `node tools/docs/configuration.mjs`; CI fails when it is out of date.

<!-- BEGIN GENERATED GATEWAY KEYS (node tools/docs/configuration.mjs) -->

#### Server

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `port` | `PORT` | number | `8080` | HTTP port. A set PORT wins over the config file. |
| `host` | `HOST` | string | `0.0.0.0` | Listen address. A set HOST wins over the config file. |
| `publicUrl` | `GATEWAY_PUBLIC_URL` | string |  | Public base URL of this gateway as the phone reaches it, e.g. https://gateway.example.com. Used for links the gateway hands out (audiobook streams). Empty: derived from the request. |
| `basePath` | `GATEWAY_BASE_PATH` | string |  | Path prefix the gateway is served under behind a reverse proxy, e.g. /gateway. A leading /api is always accepted too. |
| `dataDir` | `GATEWAY_DATA` | string |  | Directory for gateway state (JSON collections, queued audio). Default: &lt;gateway>/.data. A set GATEWAY_DATA wins over the config file. |
| `modules` | `GATEWAY_MODULES` | string[] |  | API modules to load (glasses, tv). Empty: all. |
| `accessLog` | `GATEWAY_ACCESS_LOG` | boolean | `false` | Log one line per request (method, path, status, time; never bodies). |
| `worker` | `GATEWAY_WORKER` | boolean | `false` | Run the background workers in this process (recording uploads, Beeper push, T3 notifications). Exactly one gateway process should. A set GATEWAY_WORKER wins over the config file. |
| `clients` | `GATEWAY_CLIENTS` | json | `{"GLASSES_API_TOKEN":["glasses"],"GLASSES_AGENT_TOKEN":["glasses.agent"],"TV_LAUNCHER_TOKEN":["tv"]}` | Map of token SECRET NAME -> scopes. A bearer token is accepted for a scope when it equals the value of one of these secrets. glasses = the phone app, glasses.agent = the assistant (Hermes), tv = the TV launcher. |
| `units` | `GATEWAY_UNITS` | string | `imperial` | imperial (°F, lb) or metric (°C, kg) for the weather card and weight. (Sent to the phone by `GET /glasses/v1/config`.) |
| `tz` | `GATEWAY_TZ` | string | `UTC` | IANA time zone for dates shown on the glasses (falls back to TZ, then UTC). |
| `targets` | `GATEWAY_TARGETS` | json |  | Delivery targets: [{id, name, kind: directory\|webhook\|hermes\|&lt;registered>, ...}]. Default: one directory target "local" under &lt;dataDir>/recordings. |
| `routes` | `GATEWAY_ROUTES` | json |  | Which targets get what: {memo: [ids], ambient: [ids], dictation: [ids]}. Default: memo and ambient to every target. |

#### `home`

Optional home location for the weather card; weather is off until both are set.

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `home.lat` | `GATEWAY_HOME_LAT` | number |  | Latitude. |
| `home.lon` | `GATEWAY_HOME_LON` | number |  | Longitude. |

#### `owner`

Who wears the glasses. Used in every prompt and to recognise the owner in chats.

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `owner.name` | `GATEWAY_OWNER_NAME` | string | `the user` | Display name of the owner, as prompts should call them. (Sent to the phone by `GET /glasses/v1/config`.) |
| `owner.aliases` | `GATEWAY_OWNER_ALIASES` | string[] |  | Other names and handles the owner goes by (case-insensitive), e.g. a nickname or chat handle. (Sent to the phone by `GET /glasses/v1/config`.) |

#### `assistant`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `assistant.name` | `GATEWAY_ASSISTANT_NAME` | string | `Hermes` | Name of the assistant (the Hermes agent) shown on the glasses and used in prompts. (Sent to the phone by `GET /glasses/v1/config`.) |
| `assistant.workerName` | `GATEWAY_WORKER_NAME` | string | `Triage` | Name of the background notification classifier persona used in its prompts. (Sent to the phone by `GET /glasses/v1/config`.) |

#### `secrets`

Where credentials come from. Config only ever names secrets; values live in the provider.

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `secrets.provider` | `GATEWAY_SECRETS` | string | `env` | env (environment variable NAME or file NAME_FILE), file (JSON at secrets.file) or infisical. |
| `secrets.file` | `GATEWAY_SECRETS_FILE` | string |  | JSON file of {"NAME": "value"} for the file provider. |
| `secrets.allow` | `GATEWAY_SECRET_ALLOW` | string[] |  | If set, only these secret names can be read (required for infisical). |
| `secrets.writable` | `GATEWAY_SECRET_WRITABLE` | string[] |  | Secret names the gateway may write back (rotating OAuth refresh tokens). |
| `secrets.ttlMs` | `GATEWAY_SECRET_TTL_MS` | number | `300000` | How long a secret value is cached. |
| `secrets.infisicalEnvFile` | `INFISICAL_ENV_FILE` | string |  | Optional env-style file with INFISICAL_* keys (the INFISICAL_* environment variables win). |

#### `llm`

Any OpenAI-compatible endpoint (chat completions + audio transcriptions).

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `llm.baseUrl` | `LLM_BASE_URL` | string | `https://api.openai.com/v1` | Base URL ending in /v1. |
| `llm.apiKeySecret` | `LLM_API_KEY_SECRET` | string | `LLM_API_KEY` | Name of the secret holding the API key. (Names a secret; never its value.) |

#### `models`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `models.transcribe` | `MODEL_TRANSCRIBE` | string | `whisper-1` | Speech to text (dictation, memos, recordings). |
| `models.cleanup` | `MODEL_CLEANUP` | string | `gpt-4o-mini` | Dictation cleanup. |
| `models.fast` | `MODEL_FAST` | string | `gpt-4o-mini` | Fast general model (spam checks, small tasks). |
| `models.smart` | `MODEL_SMART` | string | `gpt-4o` | Stronger model (titles, morning report). |
| `models.classifier` | `MODEL_CLASSIFIER` | string |  | Notification classifier. Empty: models.fast. |

#### `hermes`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `hermes.enabled` | `HERMES_ENABLED` | boolean | `true` | Switch the integration off entirely (its routes answer 503). |
| `hermes.url` | `HERMES_URL` | string | `http://localhost:8642` | Hermes API server base URL. |
| `hermes.apiKeySecret` | `HERMES_API_KEY_SECRET` | string | `HERMES_API_SERVER_KEY` | Secret holding the Hermes API server key. (Names a secret; never its value.) |
| `hermes.callUrl` | `HERMES_CALL_URL` | string |  | WebSocket URL of a Hermes voice-call endpoint (ws://...). Empty: live calls are off. |
| `hermes.callDevice` | `HERMES_CALL_DEVICE` | string | `glasses` | Device id the gateway sends to the voice endpoint. |
| `hermes.callDeviceHeader` | `HERMES_CALL_DEVICE_HEADER` | string | `X-Assistant-Device` | Header that carries hermes.callDevice to the voice endpoint (a voice plugin written for an older header name can set it here). |
| `hermes.callTokenSecret` | `HERMES_CALL_TOKEN_SECRET` | string | `HERMES_CALL_TOKEN` | Secret holding the bearer token for the voice endpoint. (Names a secret; never its value.) |
| `hermes.callMaxMs` | `HERMES_CALL_MAX_MS` | number |  | Runaway cap for one call in ms (default 4 h); 0 disables it. |
| `hermes.liveContext` | `HERMES_LIVE_CONTEXT` | boolean | `true` | Send the live glasses state (screen, media, call) with every chat turn. |
| `hermes.glassesContext` | `HERMES_GLASSES_CONTEXT` | string |  | Replaces the context line prefixed to every glasses chat message. {{owner}} and {{assistant}} are filled in. |

#### `beeper`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `beeper.enabled` | `BEEPER_ENABLED` | boolean | `true` | Switch the integration off entirely (its routes answer 503). |
| `beeper.url` | `BEEPER_URL` | string | `http://localhost:23373` | Beeper Desktop API base URL. |
| `beeper.tokenSecret` | `BEEPER_TOKEN_SECRET` | string | `BEEPER_ACCESS_TOKEN` | Secret holding the Beeper Desktop API token. (Names a secret; never its value.) |
| `beeper.live` | `BEEPER_LIVE` | boolean | `true` | Hold the Beeper push WebSocket open (worker only). |
| `beeper.spam` | `BEEPER_SPAM` | boolean | `true` | Classify new chats for spam in the background (worker only). |
| `beeper.spamThreshold` | `BEEPER_SPAM_THRESHOLD` | number | `0.9` | Confidence a spam verdict needs to take effect. |
| `beeper.mentions` | `BEEPER_MENTIONS` | string | `pill` | pill (Matrix pill links where supported) or plain (@Name). |

#### `t3`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `t3.enabled` | `T3_ENABLED` | boolean | `true` | Switch the integration off entirely (its routes answer 503). |
| `t3.machinesFile` | `T3_MACHINES_FILE` | string |  | machines.json listing T3 Code servers (see t3-machines.example.json). Default: &lt;dataDir>/t3-machines.json. |
| `t3.machineUrls` | `T3_MACHINE_URLS` | string |  | Overrides per machine: alias=url,alias=url. |
| `t3.tailnetProxy` | `T3_TAILNET_PROXY` | string |  | SOCKS5/HTTP proxy for *.ts.net machines, e.g. socks5h://tailscale:1055. |
| `t3.machineProxies` | `T3_MACHINE_PROXIES` | string |  | Per machine proxy overrides: alias=url or alias=direct. |
| `t3.notify` | `T3_NOTIFY` | boolean | `true` | Gateway-owned T3 notifications (worker only). |
| `t3.notifyNeedsAll` | `T3_NOTIFY_NEEDS_ALL` | boolean | `true` | Default for "notify for every thread that needs me". |
| `t3.notifyEveryMs` | `T3_NOTIFY_EVERY_MS` | number | `20000` | Polling interval (min 5000). |
| `t3.vocabFile` | `T3_VOCAB_FILE` | string |  | JSON {skills, models, commands, terms} merged into the dictation vocabulary for T3 prompts. |
| `t3.glassesSuffix` | `T3_GLASSES_SUFFIX` | string |  | Text appended to prompts sent from the glasses. Empty: a short note that the message was dictated on the glasses. |

#### `notify`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `notify.policyFile` | `NOTIFY_POLICY_FILE` | string |  | Notification policy JSON (see apis/glasses/notify-policy.default.json). Empty: the default policy. |
| `notify.promptFile` | `NOTIFY_PROMPT_FILE` | string |  | Replaces the classifier prompt (prompts/notify-route.md); {{owner}}, {{assistant}} and {{worker}} are filled in. |
| `notify.standard` | `NOTIFY_STANDARD` | string |  | The owner's own words on what deserves an interruption; added to the classifier prompt. |
| `notify.ownChats` | `NOTIFY_OWN_CHATS` | string[] |  | Lower-case substrings of Beeper chat names that are the owner's own spaces (never promotions). |
| `notify.timeoutMs` | `NOTIFY_TIMEOUT_MS` | number | `4000` | Classifier time budget. |

#### `cleanup`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `cleanup.guard` | `CLEANUP_GUARD` | string | `enforce` | Dictation cleanup output check: enforce, log or off. |
| `cleanup.promptFile` | `CLEANUP_PROMPT_FILE` | string |  | Replaces the generic cleanup prompt (prompts/cleanup.md). |
| `cleanup.vocabFile` | `CLEANUP_VOCAB_FILE` | string |  | JSON {terms: [...], corrections: {"heard": "meant"}} appended to the cleanup prompt. |

#### `homeAssistant`

Home Assistant panel. URL and token come from the secrets HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN. The phone shows it once scenes, an alarm or printers are configured.

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `homeAssistant.enabled` | `HOMEASSISTANT_ENABLED` | boolean | `true` | Switch the integration off entirely (its routes answer 503). |
| `homeAssistant.scenes` | `HOMEASSISTANT_SCENES` | json |  | Scene buttons: [{id, name, icon, entity}] (entity: a script.* or scene.*). |
| `homeAssistant.alarm` | `HOMEASSISTANT_ALARM` | json |  | Optional alarm card: {time: sensor, mode: select, times: {"7:00 AM": script}, styles: [..]}. |
| `homeAssistant.printers` | `HOMEASSISTANT_PRINTERS` | json |  | 3D printer cards: [{id, name, progress, state, online, entityPrefix}]. |

#### `people`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `people.file` | `PEOPLE_FILE` | string |  | people.json: enrolled voices and notes for the People screen (see people.example.json). Empty or missing: People is off. |

#### `conversations`

Memos and ambient recordings from the phone.

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `conversations.transcribe` | `CONVERSATIONS_TRANSCRIBE` | boolean | `true` | Transcribe recordings with models.transcribe before delivery. |
| `conversations.maxAudioMb` | `CONVERSATIONS_MAX_AUDIO_MB` | number | `95` | Largest accepted recording. |
| `conversations.maxPending` | `CONVERSATIONS_MAX_PENDING` | number | `500` | Recordings that may wait for delivery before the phone is told to hold off. |
| `conversations.maxAttempts` | `CONVERSATIONS_MAX_ATTEMPTS` | number | `8` | Delivery attempts per target before it is marked failed. |

#### `report`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `report.sourceDir` | `REPORT_SOURCE_DIR` | string |  | Directory of YYYY-MM-DD/report.md morning reports (or a morning-report.json). Empty: the report screen is empty. |

#### `youtube`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `youtube.file` | `YOUTUBE_LIST_FILE` | string |  | JSON list of videos [{video_id, title, channel_name, duration_seconds}] shown as a curated list. Empty: off. |
| `youtube.listKey` | `YOUTUBE_LIST_KEY` | string | `picks` | List key. |
| `youtube.listName` | `YOUTUBE_LIST_NAME` | string | `Picks` | List name shown on the glasses. |

#### `spotify`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `spotify.discoverWeeklyUri` | `SPOTIFY_DISCOVER_WEEKLY_URI` | string |  | Your Discover Weekly playlist URI (optional). |
| `spotify.userId` | `SPOTIFY_USER_ID` | string |  | Spotify user id (optional). |
| `spotify.coldStart` | `SPOTIFY_COLD_START` | string | `phone` | Where playback starts when nothing is active: phone or off. |

#### `composio`

Calendar and Spotify through a Composio MCP server (secrets COMPOSIO_MCP_URL, COMPOSIO_MCP_TOKEN).

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `composio.calendarAccount` | `COMPOSIO_CALENDAR_ACCOUNT` | string |  | Composio connected-account id for Google Calendar. Empty: calendar is off. |
| `composio.spotifyAccount` | `COMPOSIO_SPOTIFY_ACCOUNT` | string |  | Composio connected-account id for Spotify. |

#### `abs`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `abs.url` | `ABS_URL` | string |  | Audiobookshelf base URL (token: secret AUDIOBOOKSHELF_TOKEN). Empty: audiobooks are off. |

#### `tv`

| Key | Environment variable | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `tv.jamLinkEntity` | `TV_JAM_LINK_ENTITY` | string |  | Home Assistant input_text.* holding a Spotify Jam link to share (optional). |
| `tv.room` | `TV_ROOM` | string | `living room` | Default room name in shared messages. |

<!-- END GENERATED GATEWAY KEYS -->

### Files the gateway reads

| File | Key | Example |
| --- | --- | --- |
| Gateway config | `GATEWAY_CONFIG` | [`gateway.config.example.json`](../server/gateway/gateway.config.example.json) |
| T3 Code machines | `t3.machinesFile` | [`t3-machines.example.json`](../server/gateway/t3-machines.example.json): `{machines: [{id, alias, label, url, secret, gatewayUrl?, gatewayProxy?}]}`. `secret` names the secret holding that machine's T3 bearer token. |
| People | `people.file` | [`people.example.json`](../server/gateway/people.example.json): `{model, dim, people: [{id, name, notes?, where?, seenAt?, me?, voices?: [{id, embedding}]}]}` |
| Notification policy | `notify.policyFile` | [`notify-policy.default.json`](../server/gateway/apis/glasses/notify-policy.default.json) is the default. Your file is merged over it and validated. |
| Classifier prompt | `notify.promptFile` | [`prompts/notify-route.md`](../server/gateway/apis/glasses/prompts/notify-route.md); `{{owner}}`, `{{assistant}}`, `{{worker}}` and `{{standard}}` are filled in. |
| Cleanup prompt and vocabulary | `cleanup.promptFile`, `cleanup.vocabFile`, `t3.vocabFile` | [`prompts/cleanup.md`](../server/gateway/apis/glasses/prompts/cleanup.md) (the default prompt; `prompts/cleanup-glasses.md` is always appended); vocabulary files are `{"terms": [...], "corrections": {"heard": "meant"}}`; [`prompts/t3-vocab.json`](../server/gateway/apis/glasses/prompts/t3-vocab.json) is the built-in T3 list. |
| Morning report | `report.sourceDir` | A folder of report files the gateway turns into cards. |
| YouTube list | `youtube.file` | The curated list the YouTube app shows. |

### Delivery targets

`targets` and `routes` decide where memos, ambient recordings and dictations go. See [targets.md](targets.md).

## Secrets

Configuration only ever **names** a secret. The value comes from the secrets provider chosen with `GATEWAY_SECRETS`:

| Provider | Where values come from |
| --- | --- |
| `env` (default) | The environment variable of that name, or the file named by `<NAME>_FILE` (Docker secrets). |
| `file` | A JSON object `{"NAME": "value"}` at `GATEWAY_SECRETS_FILE`. Rotated tokens are written back for names in `GATEWAY_SECRET_WRITABLE`. |
| `infisical` | Infisical universal auth: `INFISICAL_URL`, `INFISICAL_CLIENT_ID`, `INFISICAL_CLIENT_SECRET`, `INFISICAL_PROJECT_ID`, `INFISICAL_ENV`, `INFISICAL_PATHS` (or the same keys in `INFISICAL_ENV_FILE`). Requires `GATEWAY_SECRET_ALLOW`. |

More providers can be added with `registerSecretsProvider(name, factory)` in `server/gateway/lib/secrets.mjs`. `GATEWAY_SECRET_ALLOW` (any provider) limits which names can be read at all.

Secret names the gateway uses by default. Set only those for the integrations you use:

| Secret | Used for |
| --- | --- |
| `GLASSES_API_TOKEN` | The phone's bearer token (scope `glasses`). Generate a long random string. |
| `GLASSES_AGENT_TOKEN` | The assistant's bearer token (scope `glasses.agent`): Hermes' MCP tools, `/assistant/*`, the glasses-notify plugin. |
| `TV_LAUNCHER_TOKEN` | The optional TV launcher API (scope `tv`). |
| `LLM_API_KEY` | The OpenAI-compatible endpoint (`llm.apiKeySecret`). |
| `HERMES_API_SERVER_KEY` | Hermes' API server key (`hermes.apiKeySecret`). |
| `HERMES_CALL_TOKEN` | Bearer token for the Hermes voice-call endpoint (`hermes.callTokenSecret`). |
| `BEEPER_ACCESS_TOKEN` | Beeper Desktop API token (`beeper.tokenSecret`). |
| `HOMEASSISTANT_URL`, `HOMEASSISTANT_TOKEN` | Home Assistant base URL and long-lived access token. |
| `COMPOSIO_MCP_URL`, `COMPOSIO_MCP_TOKEN` | Composio MCP endpoint for Google Calendar and Spotify. |
| `AUDIOBOOKSHELF_TOKEN` | Audiobookshelf API token (`abs.url` is the server). |
| `OURA_ACCESS_TOKEN` | Oura personal access token. |
| `WEIGHTGURUS_EMAIL`, `WEIGHTGURUS_PASSWORD` | Weight Gurus account. |
| Your own names | Webhook target auth (`targets[].auth.secret`), T3 machine tokens (`t3-machines.json` `secret`). |

`GATEWAY_CLIENTS` maps token secret names to scopes, so you can rename or add tokens: `{"GLASSES_API_TOKEN": ["glasses"], "GLASSES_AGENT_TOKEN": ["glasses.agent"]}`.

## Phone app

### Build properties

Pass with `-P` to Gradle. A plain `./gradlew assembleDebug` needs none of them.

| Property | Default | |
| --- | --- | --- |
| `appId` | `io.github.rayneohud.companion` | The `applicationId`. `android/tools/glassesctl.py` reads the same id from the `APP_ID` environment variable. The Kotlin namespace stays `io.lampyr.glasses`. |
| `embedToken` | `false` | `true` compiles `GATEWAY_TOKEN` from `secrets.properties` into the APK. Private builds only: anyone with the APK can extract it. |
| `requireGateway` | off | Fails assemble/install when `GATEWAY_URL` is missing. |

### `android/secrets.properties`

Optional; copy [`android/secrets.properties.example`](../android/secrets.properties.example). Never commit it (it is in `.gitignore`).

| Key | |
| --- | --- |
| `GATEWAY_URL` | Default gateway address shown in Settings, for example `https://gateway.example.com/glasses/v1`. |
| `GATEWAY_TOKEN` | Only used with `-PembedToken=true`. |

`android/local.properties` holds `sdk.dir` (written by Android Studio, or by hand).

### Settings on the phone

Everything below is edited in the phone app's **Settings** page; nothing needs a rebuild.

| Section | Settings |
| --- | --- |
| Your data | **Live data** (off shows built-in demo content), **Gateway address** (the gateway URL including `/glasses/v1`; `https://` is required, except `http://localhost` through `adb reverse`), **Access token** (the value of `GLASSES_API_TOKEN`, stored encrypted with the Android Keystore), Test, Save. |
| Behaviour | Start when the phone starts; controller grip (portrait or landscape). |
| Glasses display | Display timeout, dim before going dark, whether the first press on a dark lens also acts, unlock-to-Home time, notification pop-ups and how long they stay up, plus stock-firmware text options. |
| Assistant and you | Assistant name (default `Hermes`), the name of the main agent on calls (blank: "Main <assistant>"), your name (default "the user") and other names you go by. |
| Integrations | Hermes, Beeper, T3 Code, Home Assistant, Calendar, Spotify, YouTube, Audiobookshelf, People, Morning report. Switching one off hides its app on the glasses. |
| Notifications | The notification policy as JSON, merged over the default (same format as the gateway's `notify-policy.default.json`). **Load from gateway** fills these and the two sections above from `GET /glasses/v1/config`; this also happens once, automatically, after the first successful connection. |
| Avatar | Current avatar pack and licence; Import zip, Load URL (https), Reset. See [avatar-packs.md](avatar-packs.md). |
| Controller | Controller shortcuts (button holds that run an app action). |
| Order of apps on the glasses | Launcher order. |
| Devices on the Status screen | Which batteries Status shows. |

The phone's own state (`companion-config`, `glasses` and other SharedPreferences) can also be set from a computer with `python android/tools/glassesctl.py pref ...` on debug builds; see [android/DEBUG.md](../android/DEBUG.md).

## Tools and plugins

### Hermes glasses-notify plugin

Set in Hermes' environment; see [`server/hermes-plugins/glasses-notify/README.md`](../server/hermes-plugins/glasses-notify/README.md).

| Variable | Default | |
| --- | --- | --- |
| `GLASSES_AGENT_TOKEN` | (required) | The gateway's `GLASSES_AGENT_TOKEN` value. |
| `GLASSES_NOTIFY_URL` | `http://localhost:8080/glasses/v1/notify/hermes` | The gateway as Hermes reaches it. |
| `GLASSES_NOTIFY_OWNER_NAMES` | (empty) | Comma list of your names/handles in shared chats. |
| `GLASSES_NOTIFY_ASSISTANT_NAME` | `Hermes` | |
| `GLASSES_NOTIFY_SKIP_PLATFORMS` | `api_server` | |
| `GLASSES_NOTIFY_TIMEOUT_S` | `3` | |
| `GLASSES_NOTIFY_DISABLED` | unset | `1` turns it off. |

### Notification eval (`server/notify-eval`)

Developer harness for the notification classifier; see [`server/notify-eval/README.md`](../server/notify-eval/README.md).

| Variable | Default | |
| --- | --- | --- |
| `NOTIFY_EVAL_GATEWAY_URL` | `GATEWAY_URL` from `android/secrets.properties` | The gateway's `…/glasses/v1` base URL. |
| `NOTIFY_EVAL_GATEWAY_TOKEN` | `GATEWAY_TOKEN` from `android/secrets.properties` | The phone token (`GLASSES_API_TOKEN` value). |
| `NOTIFY_EVAL_AGENT_TOKEN` | (empty) | The assistant token (`GLASSES_AGENT_TOKEN` value), needed for the `/notify/hermes` cases. |
| `NOTIFY_EVAL_PRIVATE_DIR` | `server/notify-eval/private` | Where private datasets, runs and the built site live (git-ignored by default). |
| `NOTIFY_EVAL_SECRETS_FILE` | `android/secrets.properties` | Another `secrets.properties` to read `GATEWAY_URL` / `GATEWAY_TOKEN` from. |

### Web HUD bridge (`hud/server.py`)

| Variable | |
| --- | --- |
| `HERMES_URL` | Hermes API server base URL (without `/v1`). Unset: demo only. |
| `HERMES_API_KEY` or `HERMES_API_KEY_FILE` | The Hermes API server key, or a file containing only it. |
| `HERMES_TRANSCRIBE_COMMAND` | Optional JSON argument array of a local transcriber; `{audio}` is replaced by the file path. |

See [hud/BRIDGE.md](../hud/BRIDGE.md).

### Developer tools

| Variable | Used by | |
| --- | --- | --- |
| `APP_ID` | `android/tools/glassesctl.py` | The installed `applicationId` (default `io.github.rayneohud.companion`). |
| `GLASSES_SERIAL`, `ADB` | `glassesctl.py` | adb device serial and adb path. |
| `GLASSESCTL_JDK` | `glassesctl.py` | JDK used to build when `JAVA_HOME` is unset. |
| `CHROME`, `SIMULATOR_URL`, `GOLDEN_ONLY`, `UPDATE_GOLDEN_COVERAGE` | `android/tools/capture_goldens.py` | Chromium path (empty: Playwright's), simulator URL, capture a subset, rewrite coverage. |
| `UPDATE_BITMAP_GOLDENS`, `UPDATE_TEXT_GOLDENS`, `UPDATE_RASTER_GOLDENS`, `UPDATE_PEOPLE_GOLDENS` | Android unit tests | Rewrite golden references instead of comparing (`all` or a comma list of screens for bitmaps). |
