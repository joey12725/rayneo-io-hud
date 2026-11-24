# Delivery targets

Voice memos, ambient conversations and (optionally) finished dictations leave the gateway through **delivery targets**. A target is a place the gateway hands a transcribed item to: a folder, a webhook, a Hermes session, or a kind you add yourself. Routes say which items go to which targets.

The code is `server/gateway/apis/glasses/lib/targets.mjs`; the worker that transcribes and delivers is `server/gateway/apis/glasses/lib/conversations.mjs`.

## How an item gets there

1. The phone uploads a recording: `POST /glasses/v1/ambient/conversations` with a timing manifest (`kind: "memo"` for a memo), then `PUT /glasses/v1/ambient/conversations/:id/audio` with the Ogg/Opus (or WAV) audio. See the [API contract](architecture.md#31-glasses-api-glassesv1).
2. The gateway worker (`GATEWAY_WORKER=true` on exactly one process) transcribes the audio with `models.transcribe` on your OpenAI-compatible endpoint, using `verbose_json` segments when the endpoint offers them. Every segment is placed at the real clock time it was said (the manifest maps speech-only audio back onto the clock) and at the nearest location fix.
3. The item is handed to every target routed for its kind. A failing target is retried with exponential backoff, up to `CONVERSATIONS_MAX_ATTEMPTS` attempts; the other targets are not held up.
4. The phone polls `GET /glasses/v1/ambient/conversations/:id` and shows the result. The memo row reads "sent to <target name>".

Status the phone sees:

| `state` | Meaning |
| --- | --- |
| `received` | Manifest stored, waiting for audio. |
| `audio` | Audio stored, waiting for the worker. |
| `uploaded` | Transcribed, being delivered. |
| `timed` | Every routed target has it. |
| `failed` | Every routed target refused it. |

Each status also carries `deliveries: [{target, name, state: "pending"|"delivered"|"failed", deliveredAt?, error?}]`. `GET /glasses/v1/ambient/status` returns `{pending, uploadedToday, lastUploadAt, lastError, targets}`.

Dictations are different: with `routes.dictation` set, every finished `POST /voice/dictate` (except `target=raw` lookups) is also delivered to those targets as text, in the background, without audio.

## Configuration

Targets and routes live in `gateway.config.json` (or the `GATEWAY_TARGETS` / `GATEWAY_ROUTES` environment variables as JSON):

```json
{
  "targets": [
    { "id": "local", "name": "Recordings folder", "kind": "directory", "path": "./recordings" },
    { "id": "notes", "name": "Notes webhook", "kind": "webhook", "url": "https://notes.example.com/hooks/glasses",
      "auth": { "type": "hmac", "secret": "NOTES_WEBHOOK_SECRET" }, "audio": "multipart", "retries": 3, "backoffMs": 2000 },
    { "id": "assistant", "name": "Hermes", "kind": "hermes" }
  ],
  "routes": { "memo": ["local", "assistant"], "ambient": ["local"], "dictation": ["notes"] }
}
```

Every target has an `id` (1 to 64 letters, digits, `-` or `_`), a `name` the phone shows (defaults to the id) and a `kind`. Routes are `memo`, `ambient` and `dictation`; each lists target ids. The gateway refuses to start when a target or route is invalid, and names the problem.

Without `targets` there is one `directory` target called "Recordings folder" under `<dataDir>/recordings`. Without `routes`, memos and ambient recordings go to every target and dictations go nowhere.

`GET /glasses/v1/config` tells the phone each target's `id`, `name` and `kind`, never URLs, paths or secret names.

## Kinds

### `directory`

Writes `<path>/<YYYY-MM-DD>/<start time>-<kind>-<id>.json` and `.md` (the transcript with speakers and times), plus the audio file. Relative paths are resolved against the gateway's data directory.

| Option | Default | |
| --- | --- | --- |
| `path` | (required) | Directory the gateway can write. |
| `audio` | `true` | `false` skips copying the audio. |
| `formats` | `["json", "md"]` | Which transcript files to write. |

Files are written with mode 600.

### `webhook`

POSTs the item to a URL.

| Option | Default | |
| --- | --- | --- |
| `url` | (required) | `http://` or `https://`. Redirects are refused. |
| `method` | `POST` | |
| `audio` | `false` | `"multipart"` sends `multipart/form-data` with a `metadata` JSON part and an `audio` file part. Otherwise the body is JSON without audio. |
| `auth` | none | `{"type": "bearer", "secret": "NAME"}` sends `Authorization: Bearer <value of NAME>`. `{"type": "hmac", "secret": "NAME", "header": "X-Signature"}` signs the request (below). |
| `headers` | none | Extra request headers (strings). |
| `retries` | `3` | Retries within one attempt (0 to 10), for network errors, 408, 429 and 5xx. |
| `backoffMs` | `1000` | First retry delay; doubles each retry. |
| `timeoutMs` | `30000` | Per request. |

Any other 4xx fails that delivery at once and is not retried later. Secret names are read from the gateway's secrets provider (see [configuration.md](configuration.md#secrets)).

Every request carries `X-Delivery-Id: <kind>-<item id>`, which stays the same across retries, so receivers can de-duplicate.

The JSON body (or the `metadata` part):

```json
{
  "event": "memo.ready",
  "target": "notes",
  "recording": {
    "id": "c0ffee00-0000-4000-8000-000000000000",
    "kind": "memo",
    "title": "Grocery list and Saturday plan",
    "startedAt": "2026-11-11T17:02:00.000Z",
    "endedAt": "2026-11-11T17:03:58.000Z",
    "tz": "Europe/Berlin",
    "speechSec": 118,
    "device": "glasses",
    "transcript": {
      "text": "Milk, eggs, coffee...",
      "turns": [{ "speaker": "Sam", "start": 0, "end": 6.2, "at": "2026-11-11T17:02:00.000Z", "text": "Milk, eggs, coffee...", "lat": 52.52, "lon": 13.405 }]
    },
    "fixes": [{ "at": "2026-11-11T17:02:03.000Z", "lat": 52.52, "lon": 13.405 }],
    "audio": { "ext": "ogg", "mime": "audio/ogg", "bytes": 48213, "sha256": "..." }
  }
}
```

`event` is `memo.ready`, `ambient.ready` or `dictation.ready`. `transcript` is `null` when transcription is off (`CONVERSATIONS_TRANSCRIBE=false`); `audio` is `null` for dictations.

**HMAC signature.** With `auth.type: "hmac"` the gateway sends `X-Timestamp: <unix seconds>` and `X-Signature: sha256=<hex>`, where the hex is HMAC-SHA256, keyed with the secret's value, of `<X-Timestamp>.<JSON>`. For a multipart request the JSON is the `metadata` part. A minimal receiver in Node:

```js
import http from 'node:http';
import { createHmac, timingSafeEqual } from 'node:crypto';

const SECRET = process.env.NOTES_WEBHOOK_SECRET;
http.createServer(async (req, res) => {
  let body = '';
  for await (const chunk of req) body += chunk;
  const ts = req.headers['x-timestamp'] ?? '';
  const expected = 'sha256=' + createHmac('sha256', SECRET).update(`${ts}.${body}`).digest('hex');
  const given = String(req.headers['x-signature'] ?? '');
  const fresh = Math.abs(Date.now() / 1000 - Number(ts)) < 300;
  if (!fresh || given.length !== expected.length || !timingSafeEqual(Buffer.from(given), Buffer.from(expected))) {
    res.writeHead(401).end();
    return;
  }
  const { event, recording } = JSON.parse(body);
  console.log(event, recording.title, recording.transcript?.text);
  res.writeHead(204).end();
}).listen(8090);
```

(This receiver handles JSON bodies; for `audio: "multipart"`, parse the form and verify against the `metadata` part.)

### `hermes`

Posts the transcript into a Hermes session through the gateway's Hermes adapter (`hermes.url`, `hermes.apiKeySecret`), so the assistant can file it, summarise it or act on it.

| Option | Default | |
| --- | --- | --- |
| `session` | `"new"` | A Hermes session id to append to, or `"new"` for one session per item. |
| `message` | built in | Template. Placeholders: `{{owner}}`, `{{assistant}}`, `{{kind}}` (`memo`, `conversation` or `dictation`), `{{title}}`, `{{date}}`, `{{transcript}}`. |

An item without a transcript fails permanently for this kind.

## Adding a kind

Register a kind in a module the gateway imports (for example a new file next to `targets.mjs`, imported from `apis/glasses/lib/context.mjs`):

```js
import { registerTargetKind, publicItem, DeliveryError } from './targets.mjs';

registerTargetKind('s3', {
  // Return a list of problems with this target's options; an empty list means valid.
  validate: spec => (spec.bucket ? [] : ['needs "bucket"']),
  // Called once at start-up. ctx has config, secret(name), fetch and log.
  create(spec, ctx) {
    return {
      async deliver(item) {
        // item: { id, kind, title, startedAt, endedAt, tz, speechSec, device, transcript, fixes,
        //         audio: { file, ext, mime, bytes, sha256 } | null }  (audio.file is a local path)
        const res = await ctx.fetch(`https://${spec.bucket}.example-storage.com/${item.id}.json`, {
          method: 'PUT', body: JSON.stringify(publicItem(item)),
          headers: { Authorization: 'Bearer ' + await ctx.secret(spec.tokenSecret) },
        });
        if (res.status === 403) throw new DeliveryError('Storage refused the token', { permanent: true });
        if (!res.ok) throw new Error(`Storage answered ${res.status}`); // retried with backoff
      },
    };
  },
});
```

`deliver` resolving means delivered. Throwing retries the item later (up to `CONVERSATIONS_MAX_ATTEMPTS`); throwing an error with `permanent: true` (or a `DeliveryError` created with `{permanent: true}`) fails it at once. Use `publicItem(item)` for anything that leaves the gateway: it drops local file paths. Add a test next to `server/gateway/test/targets.test.mjs`.
