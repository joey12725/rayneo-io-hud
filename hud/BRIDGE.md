# Local Hermes bridge

`hud/server.py` is a small loopback-only bridge between the browser HUD and a Hermes API server. It holds the Hermes API key; the browser never sees it.

Run from the repository root with Python 3.10 or newer:

```sh
export HERMES_URL=http://127.0.0.1:8642
export HERMES_API_KEY_FILE=/path/to/hermes-api-key   # a file that contains only the key
python hud/server.py
```

On Windows PowerShell use `$env:HERMES_URL = 'http://127.0.0.1:8642'` and `$env:HERMES_API_KEY_FILE = '<path-to-key-file>'`.

Open `http://127.0.0.1:8765/hud/`. The key file contains only the Hermes API server key (`API_SERVER_KEY` in Hermes). Keep it outside this repository with access restricted to your account. Alternatively supply `HERMES_API_KEY` in the bridge process environment. Never put the key in browser storage, source files or a URL. `.env` files are ignored by Git, but this server deliberately does not load them automatically.

`HERMES_URL` is the API server base URL, without `/v1` or a trailing endpoint. Leave it unset to use the HUD demo without an upstream connection; live requests then return a configuration error. The bridge binds only to loopback. `--port 8766` changes the local port. No server settings, router settings or model selections are changed.

To check connectivity and authentication without running an agent turn, open `http://127.0.0.1:8765/api/health`.

### Reaching a Hermes server on another machine

If Hermes runs on another host that is not reachable directly, forward its API port over SSH in a separate terminal and point `HERMES_URL` at the local end:

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:8642:<hermes-lan-host>:<hermes-api-port> <user>@<ssh-host>
```

`<hermes-lan-host>:<hermes-api-port>` is the address where the Hermes API listens as seen from `<ssh-host>` (if Hermes runs in a container, use the published port, not the container's internal one). Starting the bridge never opens a tunnel or deploys anything by itself.

## Verified upstream contract

Read-only inspection of installed Hermes 0.22.2 source on 2026-11-11 verified `gateway/platforms/api_server.py`:

- `_check_auth`: `Authorization: Bearer <API_SERVER_KEY>`.
- `_handle_create_session`: `POST /api/sessions` with `{}` returns `{"session":{"id":"..."},...}`.
- `_session_chat_user_message`: accepts `{"message":"..."}`.
- `_prepare_session_chat` uses configured routing when no model/provider fields are supplied. The bridge sends none.
- `_handle_session_chat_stream`: `POST /api/sessions/{id}/chat/stream` returns SSE, including `run.started`, `assistant.delta`, `assistant.commentary`, `assistant.completed`, `tool.started/completed/failed/progress`, `approval.request`, `run.completed/failed/cancelled`, and `done`.
- `assistant.completed.content` contains final text. Its `session_id` can rotate after context compression; the bridge returns the effective ID for the next turn.
- The feature flags explicitly report `audio_api: false` and `realtime_voice: false`. No upstream transcription endpoint is assumed.

Source inspection and health reads do not run an agent turn. Live chat execution requires the externally configured URL/key. These tests use mocked upstream traffic; they do not claim a successful production conversation.

The browser saves the returned session ID and passes it in later requests. History persists in Hermes, not in a duplicate browser transcript or local database. A missing/stale upstream session returns an error rather than silently starting an unrelated conversation. Requests on the same session are serialized by rejecting overlapping turns with 409. A transport error after dispatch may mean the turn already ran: inspect the Hermes session before retrying.

## Local API

- `GET /api/health`: read-only upstream health check; returns `ok`, `transcription`, and `voice_mode`. Requires configured URL/key.
- `GET /api/capabilities`: local feature detection, including `transcription` and `streaming`; no upstream turn.
- `POST /api/chat`, JSON `{message, session_id?}`: returns `{type:"result", text, session_id, events}`. With `Accept: application/x-ndjson`, streams `session`, `event`, `delta`, then `result` records. A streaming failure returns an `error` record. `event` records contain the actual upstream event name and a HUD `status`. Tool names are mapped to working/searching/delegating/remembering/saving; this is a presentation heuristic, not a claim that the model emitted an emotional state.
- `POST /api/audio`, multipart field `audio`: returns `{text, transcript}`. The caller sends the resulting text through `/api/chat` after transcription. This endpoint never pretends to be an upstream audio API.

Approval events set `waiting` and tell the user to review in their existing Hermes client. The bridge never autoapproves a tool command. Runtime metadata, tool arguments and full upstream errors are deliberately not forwarded to the browser. Raw assistant response text remains private conversation content.

## Optional local voice transcription

Browser speech recognition can send recognized text directly to `/api/chat`. Browser availability and microphone permissions vary. For uploaded recordings, configure an installed transcription program that accepts a file and writes **only UTF-8 transcript text to stdout**:

```sh
export HERMES_TRANSCRIBE_COMMAND='["/path/to/transcriber","--input","{audio}"]'
```

This is a JSON argument array, executed without a shell. `{audio}` is substituted with a temporary uploaded recording path. The executable and arguments must match your real installed program; the example is a contract, not an installed bundled recognizer. The timeout is 90 seconds. Supported MIME types: WebM, Ogg, WAV, MP4/M4A and MP3. Uploaded files are deleted after transcription. Without this setting `/api/audio` returns 501 with a clear explanation.

## Checks

```sh
python -m unittest discover -s hud -p test_server.py
```

Tests verify real native session request/response shape, absence of model overrides, session rotation, lifecycle mapping, UTF-8/multiline SSE, truncated streams, overlapping sessions, error redaction, voice upload/command execution, NDJSON/JSON responses, request limits, traversal blocking and same-origin checks. No extra Python packages are required.
