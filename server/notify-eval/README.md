# Notification eval

Datasets, a harness and a static report site for the gateway's notification classifier: the model behind
`POST /glasses/v1/notify/route` and `POST /glasses/v1/notify/hermes` (and, as a baseline, the old binary
`POST /notify/classify`). Node 24, no dependencies.

The site is one self-contained HTML page. The data is embedded at build time and the page makes no network requests.

## Privacy

Datasets built from your own messages, contacts, T3 threads or calls belong only under `server/notify-eval/private/`
(or `NOTIFY_EVAL_PRIVATE_DIR`), which is git-ignored. Only code, the schema and the synthetic fixtures in `fixtures/`
are committed; every case there is invented (`synthetic: true`).

- Never store phone numbers in a dataset; keep a salted hash if you need to match calls.
- The gateway bearer comes from env `NOTIFY_EVAL_GATEWAY_URL` / `NOTIFY_EVAL_GATEWAY_TOKEN` (or `GATEWAY_URL` /
  `GATEWAY_TOKEN` in `android/secrets.properties`). It is only ever put in an `Authorization` header, and `build.mjs`
  refuses to write a page that contains it.
- A built site from real data contains other people's messages: keep it private.

## Environment

| Variable | Default | |
| --- | --- | --- |
| `NOTIFY_EVAL_GATEWAY_URL` | `GATEWAY_URL` from `android/secrets.properties` | The gateway's `…/glasses/v1` base URL. |
| `NOTIFY_EVAL_GATEWAY_TOKEN` | `GATEWAY_TOKEN` from `android/secrets.properties` | The phone token. |
| `NOTIFY_EVAL_AGENT_TOKEN` | (empty) | The assistant token (`GLASSES_AGENT_TOKEN` value), needed for `/notify/hermes` cases. |
| `NOTIFY_EVAL_PRIVATE_DIR` | `private/` here | Private datasets, runs and the built site. |
| `NOTIFY_EVAL_SECRETS_FILE` | `android/secrets.properties` | Another `secrets.properties` to read the gateway URL and token from. |

## Layout

```
lib/config.mjs      paths, secrets.properties reader, read-only gateway client
lib/schema.mjs      case / dataset / run validation (the dataset format)
lib/derive.mjs      scoring (three-way route, majority, flakiness, metrics, weighted error cost); inlined into the site
lib/llm.mjs         stateless chat completions against an OpenAI-compatible endpoint (for your own labelling tools)
run.mjs             harness -> private/runs/<runId>.json
build.mjs           static site -> private/dist/index.html
site/               page template, styles and the client script
fixtures/           synthetic dataset, run and call cases (tests and demos)
```

## Datasets

A dataset is `{schema: 1|2, cases: [...]}` (see `lib/schema.mjs`). Each case has an `id`, a `kind` (`beeper`, `t3`,
`call`, `hermes`), the `/notify/route` `input` the phone would send, the `expected` route (`suppress`, `stream`,
`popup`), `why`, `ambiguous`, a `labelSource` (`heuristic`, `llm`, `owner`, `owner-policy` or a named labelling pass)
and, for schema 2, a `context` (earlier turns, the target message, the T3 or Hermes prompt and response, relationship
stats). Put your datasets in `private/datasets-v2/*.json` (or `private/datasets/*.json` for schema 1).

## Running

```sh
# Against a deployed gateway (route endpoint with debug: nothing is stored or published).
NOTIFY_EVAL_GATEWAY_URL=https://gateway.example.com/glasses/v1 NOTIFY_EVAL_GATEWAY_TOKEN=... node run.mjs --repeats 3
# Hermes cases need the agent token too: NOTIFY_EVAL_AGENT_TOKEN=...

# Against an in-process gateway built from this checkout (no deploy), with any OpenAI-compatible endpoint.
LLM_API_KEY=... node run.mjs --local --proxy https://api.openai.com/v1 --model gpt-4o-mini --split dev

node build.mjs            # private/dist/index.html from private datasets and runs
node build.mjs --sample   # the synthetic fixture only
```

Options: `--endpoint route|classify`, `--repeats N`, `--concurrency N`, `--kind beeper|t3|call|hermes`, `--only id,id`,
`--limit N`, `--label text`, `--popup-at 0.75`, `--split dev|test` (deterministic 70/30, stratified by kind and
expected label), `--timeout ms`, `--dataset file`, `--fill run.json` (run only the cases a run is missing).

## Tests

```sh
node --test server/notify-eval/test/
```
