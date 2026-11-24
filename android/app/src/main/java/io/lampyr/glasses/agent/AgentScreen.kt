package io.lampyr.glasses.agent

import io.lampyr.glasses.render.TextGrid
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.serialization.json.*

/** Reads the glasses screen as data for the agent. Works in bitmap mode too (renders the text view off-screen). */
object AgentScreen {
    const val MAX_TEXT = 4_000
    const val MAX_ROWS = 60

    /** Which app a view belongs to (AppEntry ids, plus "home", "apps", "calls"). */
    fun appOf(v: View): String? = when (v) {
        is AgentConfirm -> "glasses"
        is Home -> "home"
        is Apps -> "apps"
        is Joy, is JoyMenu, is JoyCall -> "joy"
        is Beeper, is BeeperThread, is MessageActions, is ReplyScreen, is MessageReader, is ReactionPicker -> "beeper"
        is PhoneCallScreen, is CallAudio, is CallsHistory -> "calls"
        is PhoneRoot, is PhoneNumbers, is PhoneSearch, is PhoneConfirm -> "phone"
        is NowPlayingScreen, is MediaMenu, is Audiobooks, is Spotify, is MediaDevices, is MediaSpeed, is YouTubeList -> "media"
        is T3Root, is T3Thread, is T3Reader, is T3Working, is T3History, is T3Approval, is T3Question, is T3Usage,
        is T3Environments, is T3Settled, is NewThread -> "t3"
        is Notifications, is ArmedReminders -> "notifications"
        is People, is MutedPeople, is PersonCard, is QuickPeople -> "people"
        is Health, is HealthDetail -> "health"
        is Report -> "report"
        is CalendarScreen -> "calendar"
        is Status -> "status"
        is HomeAssistant -> "ha"
        is Docs -> "docs"
        is Teleprompter -> "teleprompter"
        is VoiceMemo -> "memo"
        is Settings, is AppOrder, is Shortcuts, is ShortcutActions -> "settings"
        is ClockHub, is TimerScreen, is StopwatchScreen, is AlarmsScreen, is AlarmSet, is ClockRinging, is ClockSettingsScreen -> "clock"
        is DictationScreen -> v.target.target
        else -> null
    }

    /** The app of the top screen; generic lists/readers inherit the app of the screen that opened them. */
    fun currentApp(ui: Ui): String? = ui.stack.asReversed().firstNotNullOfOrNull(::appOf)

    /** The text view rendered into the current text-display size, without disturbing pagination state. */
    fun grid(ui: Ui, v: View = ui.top): List<String> {
        val reader = v as? ReaderView
        val list = v as? ListView
        val page = reader?.page
        val cur = list?.cur
        val joyPage = ui.joyPage
        val tg = TextGrid(ui.textDisplay.cols, ui.textDisplay.rows, uppercase = false)
        try { v.drawText(tg) } catch (_: Exception) {}
        if (page != null) reader.page = page
        if (cur != null) list.cur = cur
        ui.joyPage = joyPage
        return tg.lines().map { it.trimEnd() }
    }

    fun title(ui: Ui, v: View = ui.top): String = when (v) {
        is ListView -> runCatching { v.textTitle() }.getOrDefault("")
        is ReaderView -> v.textTitle
        else -> grid(ui, v).firstOrNull()?.trim().orEmpty()
    }

    /** Full text of the current screen: an explicit agentText(), a reader's whole text, or the rendered text view. */
    fun text(ui: Ui, v: View = ui.top): String {
        val explicit = v.agentText()
        val body = explicit ?: (v as? ReaderView)?.let { runCatching { it.textContent() }.getOrNull() }
            ?: grid(ui, v).dropLastWhile { it.isBlank() }.joinToString("\n")
        return body.take(MAX_TEXT)
    }

    fun rows(v: ListView): JsonArray {
        val all = runCatching { v.rows() }.getOrDefault(emptyList())
        return JsonArray(all.take(MAX_ROWS).mapIndexed { i, r ->
            buildJsonObject {
                put("index", i)
                put("label", r.label)
                r.sub?.let { put("sub", it) }
                if (r.right.isNotBlank()) put("right", r.right)
                if (r.sep) put("heading", true)
                put("selectable", !r.sep && r.selectable)
                if (r.open != null) put("open", r.selLabel ?: "Open")
                if (r.act != null) put("act", r.actLabel ?: "Action")
                if (i == v.cur) put("selected", true)
            }
        })
    }

    fun canType(v: View): String? = v.talk?.takeUnless { it.direct }?.label

    fun screen(ui: Ui, includeText: Boolean, grid: Boolean = false): JsonObject {
        val v = ui.top
        return buildJsonObject {
            put("id", v.id); put("kind", v.kind)
            currentApp(ui)?.let { put("app", it) }
            put("title", title(ui, v))
            put("stack", JsonArray(ui.stack.map { JsonPrimitive(it.id) }))
            put("legend", buildJsonObject { runCatching { v.legend() }.getOrDefault(emptyMap()).forEach { (k, d) -> put(k, d) } })
            canType(v)?.let { put("canType", it) }
            if (v is ListView) {
                val count = runCatching { v.rows().size }.getOrDefault(0)
                put("rowCount", count); put("selected", v.cur)
                if (includeText) put("rows", rows(v))
            }
            if (v is ReaderView) put("page", v.page + 1)
            if (includeText) put("text", text(ui, v))
            if (grid) put("grid", JsonArray(grid(ui, v).map(::JsonPrimitive)))
        }
    }

    fun context(registry: ActionRegistry, includeText: Boolean): JsonObject {
        val ui = registry.ui
        val calls = ui.data.calls.snapshot
        val call = calls.ringing ?: calls.ongoing
        val media = ui.data.media.nowPlaying
        val app = currentApp(ui)
        return buildJsonObject {
            put("screen", screen(ui, includeText))
            put("locked", ui.locked)
            put("quiet", ui.quiet)
            put("listening", ui.listening.on)
            put("display", if (ui.isText) "text" else "bitmap")
            put("call", call?.let { buildJsonObject { put("phase", it.phase.name.lowercase()); put("name", it.name) } } ?: JsonNull)
            put("media", media?.let { buildJsonObject { put("source", it.source); put("title", it.title); put("playing", it.playing) } } ?: JsonNull)
            put("joy", buildJsonObject { put("status", ui.data.joy.snapshot.status); put("voiceCall", ui.data.joy.call.active) })
            put("toast", ui.currentToast?.let(::JsonPrimitive) ?: JsonNull)
            // Ops of the app on screen that can run right now: a cheap hint of what makes sense here.
            put("screenOps", JsonArray(registry.ops.values.filter { it.app == app && registry.unavailable(it) == null }.map { JsonPrimitive(it.name) }))
        }
    }
}
