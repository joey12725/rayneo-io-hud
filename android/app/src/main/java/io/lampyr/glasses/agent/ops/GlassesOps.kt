package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.OpenTarget
import io.lampyr.glasses.input.InputEngine
import io.lampyr.glasses.ui.ListView
import io.lampyr.glasses.ui.Ui
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * Generic system and navigation ops (agent-control owner). These alone cover "anything the remote can do":
 * read the screen, press any button, open any app, pick a list row, type text where the wearer would dictate.
 */
object GlassesOps : OpProvider {
    /** Remote buttons the agent may press. The talk button is excluded: it opens the microphone (use type_text). */
    val BUTTONS: List<String> = InputEngine.BIND.filterValues { it != "talk" }.keys.toList()

    /** Screens for open_app: every app plus the global destinations of the remote's shortcut buttons. */
    val EXTRA_SCREENS = listOf("home", "apps", "now-playing", "calls")
    val SCREENS: List<String> get() = EXTRA_SCREENS + APP_ENTRIES.map { it.id }
    private val ALIASES = mapOf("prompter" to "teleprompter", "notifs" to "notifications", "np" to "now-playing", "home-assistant" to "ha")

    /** The settled context after a UI action, so a navigation loop needs one tool call per step. */
    private fun AgentCall.after(message: String) = ok(message, registry.context(includeText = false))

    private fun lockedReason(ui: Ui) = if (ui.locked) "The glasses are locked; call glasses_unlock first" else null

    fun openScreen(ui: Ui, id: String) {
        when (id) {
            "home" -> ui.goHome()
            "apps" -> ui.push(Apps(ui))
            "now-playing" -> ui.push(NowPlayingScreen(ui))
            "calls" -> ui.openCalls()
            else -> ui.open(OpenTarget(id))
        }
    }

    override fun ops() = listOf(
        OpSpec(
            "glasses.get_context", "Read glasses context",
            "What is on the user's glasses right now: screen, app, title, full screen text, list rows, button legend, lock, media, call and the assistant state.",
            risk = Risk.READ,
        ) { ok(data = registry.context(includeText = true)) },

        OpSpec(
            "glasses.read_screen", "Read the glasses screen",
            "Text of the current glasses screen (works in image mode too), its list rows with indexes, and what each button does here. grid=true adds the exact character grid.",
            params = listOf(Param.boolean("grid", "Also return the rendered character grid")),
            risk = Risk.READ,
        ) { ok(data = AgentScreen.screen(ui, includeText = true, grid = bool("grid"))) },

        OpSpec(
            "glasses.press", "Press a remote button",
            "Press a button on the user's glasses remote exactly as they would: y select, b back, x action, a now playing, r notifications, r2 play/pause, " +
                "l2 quiet (on the assistant), minus lock, plus context action, star apps, heart people, arrows move. Returns the new context.",
            params = listOf(
                Param.string("button", "Button to press", required = true, enum = BUTTONS),
                Param.boolean("hold", "Hold it (~0.6 s) instead of tapping, e.g. b hold = home, plus hold = context menu"),
                Param.integer("times", "Repeat the tap this many times (default 1)", min = 1, max = 10),
            ),
            risk = Risk.NAVIGATE, timeoutMs = 12_000,
        ) {
            val input = registry.input ?: fail(Codes.UNAVAILABLE, "Remote input is not ready yet")
            val button = str("button")
            // A ringing call takes over the remote (Y answers, X declines): answering is the user's, not a navigation step.
            if (ui.data.calls.snapshot.ringing != null) fail(Codes.UNAVAILABLE, "A phone call is ringing; the remote buttons answer or decline it, so the user has to")
            // The "Call Mom?" card is the user's one deliberate press; the assistant places calls with call_place (confirm policy applies).
            if (ui.top is PhoneConfirm) fail(Codes.UNAVAILABLE, "The glasses show a call confirmation, which only the user answers; use call_place to call someone")
            if (ui.locked && button != "minus") fail(Codes.UNAVAILABLE, "The glasses are locked, so the remote ignores ${button}; call glasses_unlock first")
            val previous = input.source
            input.source = "agent"
            try {
                repeat(int("times", 1)) {
                    if (ui.top is PhoneConfirm) return@repeat // a repeated Y must not confirm a call card it opened
                    input.press(button)
                    if (bool("hold")) delay(InputEngine.HOLD_MS + 150)
                    input.release(button)
                }
            } finally { input.source = previous }
            after("Pressed $button${if (bool("hold")) " (hold)" else ""}")
        },

        OpSpec(
            "glasses.open_app", "Open a glasses app",
            "Open an app or screen on the glasses. 'home' returns to Home; 'apps' shows the app grid; 'now-playing' the media player; 'calls' the phone call screen.",
            params = listOf(Param.string("app", "App or screen id (see glasses_list_apps)", required = true, enum = SCREENS + ALIASES.keys)),
            risk = Risk.NAVIGATE, available = ::lockedReason,
        ) {
            val id = str("app").let { ALIASES[it] ?: it }
            openScreen(ui, id)
            after("Opened ${APP_ENTRIES.firstOrNull { it.id == id }?.label ?: id}")
        },

        OpSpec(
            "glasses.list_apps", "List glasses apps",
            "The apps on the user's glasses in their order (ids for glasses_open_app), plus the extra screens home, apps, now-playing and calls.",
            risk = Risk.READ,
        ) {
            ok(data = buildJsonObject {
                put("apps", JsonArray(appEntries(ui).map { buildJsonObject { put("id", it.id); put("label", it.label) } }))
                put("screens", JsonArray(EXTRA_SCREENS.map(::JsonPrimitive)))
            })
        },

        OpSpec(
            "glasses.back", "Go back", "Go back one screen on the glasses (the B button).",
            risk = Risk.NAVIGATE, available = ::lockedReason,
        ) {
            if (ui.ignoreCallInput("back")) fail(Codes.UNAVAILABLE, "A call is ringing; answer or decline it first")
            if (!ui.top.key("back")) ui.pop()
            ui.requestRender()
            after("Back")
        },

        OpSpec(
            "glasses.home", "Go to Home", "Return the glasses to the Home screen (B hold).",
            risk = Risk.NAVIGATE, available = ::lockedReason,
        ) {
            if (ui.ignoreCallInput("back")) fail(Codes.UNAVAILABLE, "A call is ringing; answer or decline it first")
            ui.goHome()
            after("Home")
        },

        OpSpec(
            "glasses.select_row", "Pick a list row",
            "On a list screen, pick a row by index (from glasses_read_screen) or by label, then open it (Y, default), run its action (X) or just focus it.",
            params = listOf(
                Param.integer("index", "Row index from glasses_read_screen rows", min = 0, max = 500),
                Param.string("label", "Row label (case-insensitive; exact match first, then contains)", maxLength = 200),
                Param.string("action", "open (Y, default), act (X) or focus", enum = listOf("open", "act", "focus")),
            ),
            risk = Risk.NAVIGATE,
            available = { ui -> lockedReason(ui) ?: if (ui.top !is ListView) "This screen is not a list; use glasses_press" else null },
        ) {
            val list = ui.top as ListView
            val rows = list.rows()
            val index = intOrNull("index") ?: strOrNull("label")?.let { wanted ->
                val pickable = rows.withIndex().filter { !it.value.sep && it.value.selectable }
                val exact = pickable.filter { it.value.label.equals(wanted, ignoreCase = true) }
                val loose = exact.ifEmpty { pickable.filter { it.value.label.contains(wanted, ignoreCase = true) } }
                when (loose.size) {
                    0 -> fail(Codes.FAILED, "No row matching \"$wanted\"")
                    1 -> loose.single().index
                    else -> fail(Codes.AMBIGUOUS, "Several rows match \"$wanted\"",
                        JsonArray(loose.map { buildJsonObject { put("index", it.index); put("label", it.value.label) } }))
                }
            } ?: fail(Codes.INVALID_ARGS, "Give index or label")
            val row = rows.getOrNull(index) ?: fail(Codes.INVALID_ARGS, "No row $index (the list has ${rows.size})")
            if (row.sep || !row.selectable) fail(Codes.UNAVAILABLE, "Row $index (${row.label}) cannot be selected")
            list.cur = index
            when (strOrNull("action") ?: "open") {
                "open" -> if (row.open == null || !list.key("sel")) fail(Codes.UNAVAILABLE, "Row \"${row.label}\" has nothing to open")
                "act" -> if (!list.key("act")) fail(Codes.UNAVAILABLE, "Row \"${row.label}\" has no X action")
            }
            ui.requestRender()
            after("Selected ${row.label}")
        },

        OpSpec(
            "glasses.type_text", "Type into the glasses",
            "Send text where the user would dictate on the current screen (e.g. a Beeper thread sends a message, a T3 thread a prompt). " +
                "glasses_read_screen's canType says what it does. mode=steer steers a running T3 turn.",
            params = listOf(
                Param.string("text", "The text, exactly as it should be sent", required = true, maxLength = 4000),
                Param.string("mode", "send (default) or steer", enum = listOf("send", "steer")),
            ),
            risk = Risk.SEND, timeoutMs = 20_000,
            available = { ui ->
                val target = ui.top.talk
                when {
                    ui.top.kind == "joy" || target?.direct == true -> "Typing on the assistant's own screen would message it; answer in the conversation instead"
                    target == null -> "This screen has no text input"
                    else -> null
                }
            },
            summary = { call -> "${call.ui.top.talk?.label.orEmpty()}: ${call.strOrNull("text").orEmpty().take(80)}" },
        ) {
            val target = ui.top.talk ?: fail(Codes.UNAVAILABLE, "This screen has no text input")
            val text = str("text")
            val result = if (strOrNull("mode") == "steer") (target.steer ?: fail(Codes.UNAVAILABLE, "Steer is not available here"))(text) else target.send(text)
            ui.requestRender()
            done(result, "Sent (${target.label})")
        },

        OpSpec(
            "glasses.lock", "Lock the glasses", "Lock the glasses (blank display; the remote only unlocks), like a minus tap.",
            risk = Risk.ACT,
        ) {
            registry.input?.setLocked(true) ?: run { ui.locked = true }
            ui.requestRender()
            if (!ui.locked) fail(Codes.UNAVAILABLE, "The glasses stay unlocked during a phone call")
            ok("Locked")
        },

        OpSpec(
            "glasses.unlock", "Unlock the glasses", "Unlock the glasses and show the current screen again.",
            risk = Risk.ACT,
        ) {
            registry.input?.setLocked(false) ?: run { ui.locked = false; ui.flash("Unlocked") }
            ui.requestRender()
            after("Unlocked")
        },

        OpSpec(
            "glasses.show_toast", "Flash a message", "Flash a short message on the glasses for about two seconds.",
            params = listOf(Param.string("text", "Message, up to 60 characters", required = true, maxLength = 60)),
            risk = Risk.ACT,
        ) { ui.flash(str("text")); ok("Shown") },

        OpSpec(
            "glasses.list_operations", "List glasses operations",
            "Every glasses operation (including ones not offered as tools), with risk and whether it can run right now. Run any of them with glasses_run.",
            params = listOf(Param.string("app", "Only this app (e.g. beeper, joy, glasses)")),
            risk = Risk.READ,
        ) {
            val app = strOrNull("app")
            ok(data = JsonArray(registry.ops.values.filter { app == null || it.app == app }.map { spec ->
                buildJsonObject {
                    put("name", spec.name); put("title", spec.title); put("description", spec.description)
                    put("risk", spec.risk.wire); put("tool", spec.tool)
                    put("params", JsonArray(spec.params.map { JsonPrimitive(it.name + if (it.required) "" else "?") }))
                    registry.unavailable(spec)?.let { put("unavailable", it) }
                }
            }))
        },

        OpSpec(
            "glasses.run", "Run a glasses operation",
            "Run any glasses operation by name (see glasses_list_operations), including ones that are not separate tools. Confirmation follows the target operation.",
            params = listOf(
                Param.string("op", "Operation name, e.g. joy.read_reply", required = true, maxLength = 64),
                Param.obj("args", "Arguments for that operation"),
            ),
            risk = Risk.ACT, confirm = Confirm.NEVER, timeoutMs = OpSpec.MAX_TIMEOUT_MS,
        ) {
            val target = str("op")
            if (target == "glasses.run") fail(Codes.INVALID_ARGS, "glasses.run cannot run itself")
            registry.call(target, obj("args"), id, origin)
        },
    )
}
