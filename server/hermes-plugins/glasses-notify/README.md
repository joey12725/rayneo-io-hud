# glasses-notify (Hermes plugin)

Sends each completed exchange of the Hermes agent (an inbound message -> the assistant's final response) to the RayNeo
iO HUD gateway, which decides popup, list (stream) or nothing with its rules and its classifier model
(`POST /glasses/v1/notify/hermes`, scope `glasses.agent`, see `server/gateway/apis/glasses/lib/notify-hermes.mjs`).

- Every platform except `api_server` (the glasses channel and internal pipeline calls): Discord channels, threads and
  DMs, including messages from people other than the owner, background-work completions, `[Cron delivery: …]`
  follow-ups, CLI and one-shot sessions.
- Scheduled jobs (`platform=cron`): the job's output, with the job name and whether Hermes delivers it (a job kept
  `local` is sent with `delivered: false`, and the gateway suppresses it; `[SILENT]`/`NO_REPLY` answers are suppressed too).
- Subagent turns (a parent session) and Hermes' own notes (compaction, task-list restore) are skipped.
- Body: `{platform, chat, chatId, threadId, isDM, sender: owner|other|scheduler|system, senderName, inboundKind, job,
  delivered, prompt: {at, text}, response: {at, text}, turns, sessionId, turnId}`. `turns` holds up to 5 earlier turns
  (`{speaker, isOwner, messages}`) of the same conversation from the last 4 hours, from the plugin's in-memory history
  (empty after a Hermes restart).
- Who is the owner: in shared multi-user sessions Hermes prefixes messages with `[name]`; a name listed in
  `GLASSES_NOTIFY_OWNER_NAMES` is the owner, any other is someone else. Unprefixed messages and DMs are the owner's.
- Observer only: both hooks return None and never raise. Delivery runs on one daemon thread with a bounded queue
  (50; overflow is dropped) and a 3 s HTTP timeout, so a slow or down gateway never delays a turn.

Hooks: `pre_llm_call` (turn start time, subagent flag) and `post_llm_call` (fired once per turn by
`agent/turn_finalizer.py` with `session_id, turn_id, user_message, assistant_response, platform`). Chat name, ids, chat
type and sender come from `gateway.session_context.get_session_env` (`HERMES_SESSION_*`, `HERMES_CRON_AUTO_DELIVER_*`).

## Settings (Hermes environment)

| Name | Default | |
|---|---|---|
| `GLASSES_AGENT_TOKEN` | (required) | gateway bearer, scope `glasses.agent` (the gateway's `GLASSES_AGENT_TOKEN` secret) |
| `GLASSES_NOTIFY_URL` | `http://localhost:8080/glasses/v1/notify/hermes` | the gateway as Hermes reaches it |
| `GLASSES_NOTIFY_OWNER_NAMES` | (empty, recommended) | comma list of the owner's names/handles in shared chats |
| `GLASSES_NOTIFY_ASSISTANT_NAME` | `Hermes` | the assistant's name in the conversation history |
| `GLASSES_NOTIFY_SKIP_PLATFORMS` | `api_server` | comma list |
| `GLASSES_NOTIFY_TIMEOUT_S` | `3` | 0.5 to 10 |
| `GLASSES_NOTIFY_DISABLED` | unset | `1` turns it off |

## Install

```sh
# 1. Copy the plugin into Hermes' plugin directory (without the tests).
cp -r server/hermes-plugins/glasses-notify "$HERMES_HOME/plugins/" && rm -rf "$HERMES_HOME/plugins/glasses-notify/tests"
# 2. Enable it: add "- glasses-notify" under plugins.enabled in $HERMES_HOME/config.yaml (Hermes only loads listed plugins).
# 3. Set GLASSES_AGENT_TOKEN, GLASSES_NOTIFY_URL and GLASSES_NOTIFY_OWNER_NAMES in Hermes' environment and restart Hermes.
# 4. Check the log for "glasses-notify registered (skip: api_server)".
```

## Tests

```sh
python -m unittest discover -s server/hermes-plugins/glasses-notify/tests
```
