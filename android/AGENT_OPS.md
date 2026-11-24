# Agent operations: letting the assistant drive your app

The assistant (Hermes by default) controls the glasses through **operations** that the phone registers. The phone sends its list
(the manifest) to the gateway over the agent link (`GET /glasses/v1/agent/link`, WebSocket); the gateway turns
every operation into an MCP tool for the assistant (`POST /glasses/v1/mcp`, op `beeper.send_message` becomes tool
`beeper_send_message`) and relays each call back to the phone, which runs it and returns the real result.
Code: `app/src/main/java/io/lampyr/glasses/agent/`.

Generic ops already exist (`glasses.get_context`, `read_screen`, `press`, `open_app`, `select_row`,
`type_text`, `back`, `home`, `lock`, `unlock`, `list_apps`, `list_operations`, `run`, `show_toast`), so the assistant
can already do anything the remote can do. Per-app ops make that reliable and cheap: one call
`beeper.send_message {chat:"Mom", text:"..."}` instead of six presses.

## 1. Add your file

Create `agent/ops/<App>Ops.kt` with `object <App>Ops : OpProvider`. It is found by name; you do **not** edit
`ActionRegistry.kt`. The names it looks for (`PLANNED_PROVIDERS`): `BeeperOps`, `T3Ops`, `MediaOps`,
`NotificationOps`, `CallOps`, `ClockOps`, `PeopleOps`, `HealthOps`, `CalendarOps`, `ReportOps`,
`HomeAssistantOps`, `DocsOps`, `MemoOps`, `TeleprompterOps`, `StatusOps`, `SettingsOps`,
`ShortcutsOps`, `LifelogOps`, `NavOps`. A different app appends its name to that list (one line).
It must be an `object` (a `class` fails at startup). `GlassesOps`, `HomeOps` and `JoyOps` are built in.

Call **repositories and `Ui` methods**, never synthesize key presses, and do not edit other apps' screens.

## 2. The API

```kotlin
OpSpec(
    name: String,                 // "app.verb_noun": ^[a-z][a-z0-9]*\.[a-z][a-z0-9_]*$
    title: String,                // <= 40 chars; shown on the confirm card ("Send Beeper message")
    description: String,          // <= 300 chars, for the model; the gateway prefixes "Controls the user's RayNeo glasses"
    params: List<Param> = emptyList(),
    risk: Risk = Risk.ACT,
    confirm: Confirm = Confirm.POLICY,   // ALWAYS / NEVER override the wearer's policy for this op
    tool: Boolean = true,         // false: not an MCP tool; still reachable via glasses_run + glasses_list_operations
    timeoutMs: Long = 10_000,     // 500..45_000 (the confirm window is added on top)
    available: (Ui) -> String? = { null },   // null = can run now; else a short reason -> code "unavailable"
    summary: (AgentCall) -> String = { "" }, // one line for the confirm card: "to Mom: running late"
    run: suspend AgentCall.() -> OpResult,   // runs on the main thread, under timeoutMs
)
```

**Naming.** `app` is the AppEntry id (`beeper`, `t3`, `media`, `calls`, `notifications`, `people`, `ha`, ...).
Verb first: `list_chats`, `read_chat`, `send_message`, `react`, `mark_read`, `play_audiobook`, `set_speed`.
Every op that takes an id has a sibling read op that returns ids, and also accepts a human name where one exists.

**Params** (JSON-schema helpers; names are `lower_snake`, each needs a description):

```kotlin
Param.string("chat", "Chat id or name", required = true, enum = null, maxLength = 200)
Param.integer("limit", "How many (default 10)", min = 1, max = 25)
Param.number("speed", "Playback speed", min = 0.5, max = 3.0)
Param.boolean("unread_only", "Only unread chats")
Param.obj("answers", "Question id -> answer")
```

Arguments are validated twice (gateway and phone): unknown keys, missing required, wrong type, enum, bounds,
`maxLength` all fail with `invalid_args` before `run` is called. Inside `run`, read them with `str(name)`
(required, non-blank), `strOrNull`, `int(name, default)`, `intOrNull`, `num`, `bool(name, default)`, `obj`, `has`.

**Risk** decides the confirm card:

| Risk | Use for | Confirm |
|---|---|---|
| `READ` | reads only | never |
| `NAVIGATE` | moves around the UI | never |
| `ACT` | local, undoable changes (pause, mark read, lock) | never |
| `SEND` | text leaves the device (message, reply, T3 prompt) | policy |
| `CALL` | places or starts a call | policy |
| `SPEND` | money, credits, usage resets | policy |
| `PHYSICAL` | the physical world (HA scenes) | policy |

Policy (setting `agent.confirm` in the app prefs, default `outside_glasses`): sensitive ops run without a
prompt when the request came from the glasses conversation, and show the confirm card otherwise (Y confirm,
B cancel, 20 s with no answer = cancel). `always` / `never` are the other values. Stop-type ops must use
`confirm = Confirm.NEVER` (`media.pause`, `calls.end`, `joy.end_voice_call`).

**Results.** Read ops return compact data; action ops say what actually happened:

```kotlin
ok(data = buildJsonObject { ... })     // lists capped at 25 items, text at 4,000 chars
ok("Paused")                           // one line, true
done(repositoryActionResult, "Sent to Mom")  // ActionResult -> ok (its own message wins) or failed
fail(Codes.UNAVAILABLE, "No call is ringing")            // throws; becomes {ok:false, code, message}
fail(Codes.AMBIGUOUS, "Several chats match \"sam\"", candidatesJson)
```

Codes: `invalid_args`, `unavailable` (not in this state), `ambiguous` (candidates in data), `permission`
(name the Android permission), `failed` (ran and failed; use the repository message). The registry adds
`unknown_op`, `timeout`, `denied` (the user pressed B), `busy`; the link/gateway add `offline`. An exception
thrown from `run` becomes `failed` with its message. Never report success you did not get ("do it, don't
simulate it"). Trace logs every call (op, ok, code, ms) but never arguments.

## 3. Test it

Copy `app/src/test/java/io/lampyr/glasses/agent/ExampleOpsTest.kt`:

```kotlin
@Test fun sendsAndResolvesNames(): Unit = ActionRegistryTest.Harness(listOf(BeeperOps)).use { h ->
    val r = h.call("beeper.send_message", mapOf("chat" to "Sam", "text" to "hi"), Origin.GLASSES_TURN)
    assertTrue(r.message, r.ok)                               // FakeDataStore records the send
    assertEquals(Codes.INVALID_ARGS, h.call("beeper.send_message", mapOf("chat" to "Sam")).code)
    val pending = h.callAsync("beeper.send_message", mapOf("chat" to "Sam", "text" to "x")) // not from glasses
    assertTrue(h.ui.top is AgentConfirm); h.input.press("b"); h.input.release("b")
    assertEquals(Codes.DENIED, runBlocking { pending.await() }.code)
}
```

`ActionRegistryTest.everyRegisteredOpHasAValidManifestEntry` checks every registered op (name, title and
description limits, schema). Run: `./gradlew :app:testDebugUnitTest --tests 'io.lampyr.glasses.agent.*'`.
`AgentManifestFixtureTest` keeps the phone's real manifest in `server/gateway/test/fixtures/agent-manifest.json`
(the gateway test `agent-ops.test.mjs` checks the assistant's MCP `tools/list` and the SKILL.md map against it): after you
add or change ops it fails and writes `app/build/agent-manifest.json`; copy that over the fixture.
Shared helpers for ops files (`resolve` id-or-name lookup with `ambiguous` candidates, `show` push-and-verify,
`lockedReason`, `showNowPlaying`, `clip`) are in `agent/ops/OpsSupport.kt`.
On hardware: `POST /glasses/v1/agent/call {"op":"beeper.list_chats"}` with the agent token, and
`glassesctl.py trace` shows `Agent call ...` lines.

## 4. Worked example (compiled as `ExampleOps` in the tests, under the app name `example`)

```kotlin
package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.Chat
import kotlinx.serialization.json.*

object BeeperOps : OpProvider {
    private fun AgentCall.chat(): Chat {
        val wanted = str("chat")
        val chats = ui.data.beeper.chats
        chats.firstOrNull { it.id == wanted }?.let { return it }
        val exact = chats.filter { it.name.equals(wanted, ignoreCase = true) }
        val matches = exact.ifEmpty { chats.filter { it.name.contains(wanted, ignoreCase = true) } }
        return when (matches.size) {
            1 -> matches.single()
            0 -> fail(Codes.FAILED, "No chat named \"$wanted\"; use beeper_list_chats")
            else -> fail(Codes.AMBIGUOUS, "Several chats match \"$wanted\"",
                JsonArray(matches.take(8).map { buildJsonObject { put("id", it.id); put("name", it.name) } }))
        }
    }

    override fun ops() = listOf(
        OpSpec("beeper.list_chats", "List Beeper chats",
            "the user's chats, most recent first: id, name, network and unread count.",
            params = listOf(Param.boolean("unread_only", "Only chats with unread messages")),
            risk = Risk.READ,
        ) {
            ui.data.beeper.refreshChats()
            val chats = ui.data.beeper.chats.filter { !bool("unread_only") || it.unread > 0 }
            ok(data = JsonArray(chats.take(25).map {
                buildJsonObject { put("id", it.id); put("name", it.name); put("network", it.network); put("unread", it.unread) }
            }))
        },
        OpSpec("beeper.send_message", "Send Beeper message",
            "Send a text message to one of the user's chats (chat id or name).",
            params = listOf(
                Param.string("chat", "Chat id or name (see beeper_list_chats)", required = true, maxLength = 200),
                Param.string("text", "Message text, exactly as it should be sent", required = true, maxLength = 4000),
            ),
            risk = Risk.SEND, timeoutMs = 15_000,
            available = { ui -> if (ui.data.beeper.chats.isEmpty()) "No chats are loaded yet" else null },
            summary = { call -> "to ${call.strOrNull("chat")}: ${call.strOrNull("text").orEmpty().take(60)}" },
        ) {
            val chat = chat()
            done(ui.data.beeper.send(chat.id, str("text")), "Sent to ${chat.name}")
        },
    )
}
```

Keep tool descriptions to one or two sentences: every tool's schema is in every the assistant prompt. Rarely used ops
should be `tool = false` (the assistant still finds them through `glasses_list_operations` and runs them with
`glasses_run`). When you add tools, also add a line for your app to
`server/gateway/hermes-skill/glasses/SKILL.md` ("Per-app tools").
