# Running the gateway

The gateway (`server/gateway`) is the phone app's only server. It holds every credential, talks to Hermes, Beeper, T3 Code and the optional services, routes notifications, and delivers voice memos and recordings to your [targets](targets.md). The phone holds one token for it and nothing else.

It is a single Node 24 process with no npm dependencies. Its own README has the full route list and internals: [server/gateway/README.md](../server/gateway/README.md). Every setting is listed in [configuration.md](configuration.md).

## Run it

### With Node

```sh
cd server/gateway
cp gateway.config.starter.json gateway.config.json   # then add only the keys you need (gateway.config.example.json lists them all)
export GLASSES_API_TOKEN=$(openssl rand -hex 32)       # the phone's token
export GLASSES_AGENT_TOKEN=$(openssl rand -hex 32)     # the assistant's token
export LLM_API_KEY=sk-...                               # any OpenAI-compatible endpoint (LLM_BASE_URL)
GATEWAY_CONFIG=./gateway.config.json GATEWAY_WORKER=1 node server.mjs
```

`curl http://localhost:8080/health` answers when it is up. With no configuration at all it still starts (owner "the user", assistant "Hermes", time zone UTC, integrations pointing at `localhost`); integrations that cannot be reached simply report errors on their screens.

### With Docker

`server/gateway/Dockerfile` and `server/gateway/docker-compose.example.yml` are a hardened starting point (read-only root, no capabilities, one data volume):

```sh
cd server/gateway
cp docker-compose.example.yml docker-compose.yml
cp .env.example .env                                         # uncomment what you use, add the secrets
mkdir -p config && cp gateway.config.starter.json config/gateway.config.json   # add only the keys you need
docker compose up -d
```

Keep the config file small: keys in it override the matching environment variables in `.env`, except the deployment settings (`PORT`, `HOST`, `GATEWAY_DATA`, `GATEWAY_WORKER`) that the compose file sets. Drop-in files such as `people.json` or `t3-machines.json` go in `config/` next to it and are referenced with relative paths (`"people": {"file": "./people.json"}`). The compose file publishes the gateway on `127.0.0.1:8080` only. If Hermes, Beeper Desktop or Home Assistant run as containers on the same host, join their network and use their service names (for example `HERMES_URL=http://hermes:8642`).

### One worker

Background work (transcribing and delivering recordings, Beeper push, T3 notifications) runs only in a process started with `GATEWAY_WORKER=1`. Run exactly one such process. Live voice-call reservations are kept in memory, so run one replica.

## Put it behind HTTPS

The phone only talks to `https://` gateways (or `http://localhost` through `adb reverse` while developing). Put the gateway behind any reverse proxy or tunnel that terminates TLS: Caddy, nginx, Traefik, Cloudflare Tunnel, Tailscale Funnel and so on.

- The proxy must pass WebSocket upgrades (`/glasses/v1/assistant/call` and `/glasses/v1/agent/link`).
- Allow long requests: `/glasses/v1/events` long-polls for 25 seconds, and audio uploads can reach 95 MB.
- Serving under a path prefix (`https://example.com/hud/...`) works: set `GATEWAY_BASE_PATH=/hud`, and `GATEWAY_PUBLIC_URL=https://example.com/hud` so audiobook stream links point at the right place.
- Configure the proxy's access log to redact `/glasses/v1/abs/stream/*` (those paths are capability URLs).

A minimal Caddy example:

```
gateway.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

The phone's gateway address is then `https://gateway.example.com/glasses/v1`.

## Tokens and scopes

Every request carries `Authorization: Bearer <token>`. A token is accepted for a scope when it equals the value of a secret listed for that scope in `GATEWAY_CLIENTS`. By default:

| Secret | Scope | Who uses it |
| --- | --- | --- |
| `GLASSES_API_TOKEN` | `glasses` | The phone app (Settings → Access token). |
| `GLASSES_AGENT_TOKEN` | `glasses.agent` | The assistant: Hermes' MCP connection, the `/assistant/*` tools, the glasses-notify plugin. |
| `TV_LAUNCHER_TOKEN` | `tv` | The optional Android TV launcher API (`/tv/v1`). |

Secret values come from the provider in `GATEWAY_SECRETS`: environment variables (default), a JSON file, or Infisical. See [configuration.md § Secrets](configuration.md#secrets).

## Hermes

[Hermes](https://github.com/NousResearch/hermes-agent) is the assistant. The gateway uses it four ways:

1. **Chat.** The glasses' assistant app streams turns through `POST /glasses/v1/assistant/chat` into Hermes' session API. Set `HERMES_URL` (default `http://localhost:8642`) and the secret `HERMES_API_SERVER_KEY` (Hermes' API server key).
2. **Glasses control.** Hermes drives the glasses through the gateway's MCP server: add an MCP server named `glasses` to Hermes with the URL `<gateway>/glasses/v1/mcp` and the header `Authorization: Bearer <GLASSES_AGENT_TOKEN>`. Install [`server/gateway/hermes-skill/glasses/`](../server/gateway/hermes-skill/glasses/SKILL.md) as Hermes' `glasses` skill so the agent knows the tools and the page sizes.
3. **Notifications.** The [glasses-notify plugin](../server/hermes-plugins/glasses-notify/README.md) sends each finished Hermes conversation turn (Discord, cron jobs, other platforms) to the gateway, which decides popup, list or nothing.
4. **Voice calls (optional).** Set `HERMES_CALL_URL` to a WebSocket voice endpoint provided by a Hermes voice plugin and the secret `HERMES_CALL_TOKEN`. The gateway relays the phone's audio frames unchanged; the protocol is in [architecture.md](architecture.md#31-glasses-api-glassesv1). Without it, the call screen falls back to turn-based voice.

Hermes can also be a [delivery target](targets.md#hermes) for memos and recordings.

## Beeper

[Beeper](https://www.beeper.com) brings every chat network into one inbox; the glasses read, reply and react through the [Beeper Desktop API](https://developers.beeper.com). Run Beeper Desktop on an always-on machine, enable its API, and set `BEEPER_URL` (default `http://localhost:23373`) and the secret `BEEPER_ACCESS_TOKEN`.

- `GATEWAY_WORKER=1` subscribes to Beeper's live events so new messages reach the glasses within seconds (`BEEPER_LIVE=0` turns this off).
- Spam detection for unknown numbers uses the fast model (`BEEPER_SPAM=0` turns it off).
- Your name and aliases (`owner.name`, `owner.aliases`) are used to spot @mentions of you.
- On the phone, Beeper's own notifications are routed by the notification policy; messaging apps outside Beeper stay off the glasses by default.

## T3 Code

[T3 Code](https://github.com/pingdotgg/t3code) manages coding agents. The glasses list threads that need you, read and answer agent questions, approve commands, queue or steer messages and start new threads, on every machine you pair.

1. Copy [`t3-machines.example.json`](../server/gateway/t3-machines.example.json) to `t3-machines.json` and list each T3 Code server: an `id`, the `alias` the glasses show, a `label`, its `url` and the name of the secret holding a bearer token for it (`secret`).
2. Point `T3_MACHINES_FILE` (or `t3.machinesFile`) at the file and provide each token as a secret.
3. Machines reachable only on a tailnet can go through a proxy (`T3_TAILNET_PROXY`, or per machine `T3_MACHINE_PROXIES` / `gatewayProxy`); the compose example has a Tailscale sidecar.

With `GATEWAY_WORKER=1` the gateway watches threads itself and notifies when a thread needs you or finishes (`T3_NOTIFY`, `T3_NOTIFY_NEEDS_ALL`). Messages sent from the glasses get a short suffix asking the agent to answer briefly (`T3_GLASSES_SUFFIX`); the optional [`/glasses` reply skill](../skills/glasses/SKILL.md) does the same on the agent side.

## Models

Transcription, dictation cleanup, titles, the morning report, spam checks and the notification classifier all use one OpenAI-compatible endpoint: `LLM_BASE_URL` (default OpenAI) with the secret `LLM_API_KEY`. Pick models with `MODEL_TRANSCRIBE`, `MODEL_CLEANUP`, `MODEL_FAST`, `MODEL_SMART` and `MODEL_CLASSIFIER`. Any compatible server works (a local one included), as long as it offers chat completions and audio transcriptions.

## Optional integrations

Each is off or empty until configured; the phone only shows an app for an integration the gateway reports as configured. Switch any of them off on the phone (Settings → Integrations) to hide its app.

| Integration | What to set |
| --- | --- |
| Google Calendar, Spotify | Composio MCP: secrets `COMPOSIO_MCP_URL`, `COMPOSIO_MCP_TOKEN`; `COMPOSIO_CALENDAR_ACCOUNT`, `COMPOSIO_SPOTIFY_ACCOUNT`. Spotify options: `SPOTIFY_DISCOVER_WEEKLY_URI`, `SPOTIFY_USER_ID`, `SPOTIFY_COLD_START`. |
| Home Assistant | Secrets `HOMEASSISTANT_URL`, `HOMEASSISTANT_TOKEN`; `homeAssistant.scenes`, `homeAssistant.alarm`, `homeAssistant.printers` in the config file. The phone shows the Home app once at least one of those three is set. |
| Audiobookshelf | `ABS_URL` and the secret `AUDIOBOOKSHELF_TOKEN`. Set `GATEWAY_PUBLIC_URL` so stream links work. |
| Oura, Weight Gurus | Secrets `OURA_ACCESS_TOKEN`; `WEIGHTGURUS_EMAIL`, `WEIGHTGURUS_PASSWORD`. |
| Weather | `GATEWAY_HOME_LAT`, `GATEWAY_HOME_LON`. |
| Morning report | `REPORT_SOURCE_DIR`: a folder with `morning-report.json` (used as-is) or `YYYY-MM-DD/report.md` (turned into cards by the smart model). |
| YouTube | `YOUTUBE_LIST_FILE` (the curated list), `YOUTUBE_LIST_KEY`, `YOUTUBE_LIST_NAME`. |
| People | `PEOPLE_FILE` ([format](../server/gateway/people.example.json)). People stays off while the file does not exist. |
| TV launcher | `TV_LAUNCHER_TOKEN`, `TV_ROOM`, `TV_JAM_LINK_ENTITY`. |

## Notifications

The phone asks the gateway (`POST /glasses/v1/notify/route`) whether each notification should pop up on the lens, go to the list, or stay on the phone. Deterministic rules decide first, from the policy in [`notify-policy.default.json`](../server/gateway/apis/glasses/notify-policy.default.json): Beeper, T3 Code, the assistant and Hermes, calls, calendar and clock are on; every other app is off unless you allow it. A model (the classifier, `MODEL_CLASSIFIER`) only decides the semantic cases, such as whether a group message is for you.

To change it, copy the default, edit it and point `NOTIFY_POLICY_FILE` at the copy; the phone also lets you edit the policy and can load it from the gateway. `NOTIFY_STANDARD` describes, in your own words, what deserves a pop-up. [`server/notify-eval/`](../server/notify-eval/README.md) has a harness for testing classifier prompts against your own labelled examples.

## Upgrading and data

All state lives in `GATEWAY_DATA` (default `server/gateway/.data`, or the `/data` volume in Docker): JSON collections and queued audio. Back up that directory. Configuration problems at start-up are reported together and the process exits with status 78, so a bad edit never starts a half-configured gateway.

## Tests

```sh
node --test server/gateway/test/
```

The tests need no network and no credentials.
