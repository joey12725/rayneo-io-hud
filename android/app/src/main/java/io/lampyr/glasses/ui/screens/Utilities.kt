package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import kotlin.math.*
import io.lampyr.glasses.data.uploadLabel

/**
 * Agenda rows in display order: days without events are skipped, except today, which always
 * says whether anything is left so the first line on screen is about today.
 */
private fun agendaEntries(ui: Ui): List<Pair<io.lampyr.glasses.data.CalendarDay, io.lampyr.glasses.data.CalendarEvent?>> =
    currentAgenda(ui).filterIndexed { i, day -> day.events.isNotEmpty() || (i == 0 && day.day == "TODAY") }
        .flatMap { day -> listOf(day to null) + day.events.map { day to it }.ifEmpty { listOf(day to NOTHING_TODAY) } }

/**
 * A cached agenda that crossed midnight (its TODAY is now yesterday, e.g. while offline) drops the
 * past day and renames the day headers, so TODAY never sits over yesterday's events.
 */
private fun currentAgenda(ui: Ui): List<io.lampyr.glasses.data.CalendarDay> {
    val agenda = ui.data.calendar.agenda
    val today = java.time.Instant.ofEpochMilli(ui.nowMs()).atZone(ui.zone).toLocalDate()
    fun date(d: io.lampyr.glasses.data.CalendarDay) = runCatching { java.time.LocalDate.parse(d.date) }.getOrNull()
    val first = agenda.firstOrNull() ?: return agenda
    if (first.day != "TODAY" || date(first) != today.minusDays(1)) return agenda
    return agenda.mapNotNull { d ->
        val on = date(d) ?: return@mapNotNull d
        if (on.isBefore(today)) null
        else d.copy(day = when (on) {
            today -> "TODAY"
            today.plusDays(1) -> "TOMORROW"
            else -> on.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US)).uppercase()
        })
    }
}

private val NOTHING_TODAY = io.lampyr.glasses.data.CalendarEvent("", "No more events today", "")

/** An all-day row is labelled for the day it sits under, so a multi-day event reads "Fri · All day" under FRI. */
private fun agendaWhen(day: io.lampyr.glasses.data.CalendarDay, e: io.lampyr.glasses.data.CalendarEvent, ui: Ui) =
    eventWhen(e.start, e.allDay, if (e.allDay && e.startDate.isNotEmpty()) maxOf(e.startDate, day.date) else e.startDate, e.endDate, e.timeLabel, ui)

class CalendarScreen(ui: Ui) :
    ListView(
        ui,
        "cal",
        items = {
            agendaEntries(ui).map { (day, e) ->
                if (e == null) Row(day.day, sep = true)
                else if (e === NOTHING_TODAY) Row(e.title, selectable = false)
                else {
                    // Gateway events get the day-aware label; fixtures keep their prepared subtitle.
                    val live = e.startDate.isNotEmpty() || runCatching { java.time.Instant.parse(e.start) }.isSuccess
                    Row(
                        e.title,
                        sub = "",
                        subLines =
                            layout((if (live) "" else e.displaySubtitle).ifBlank { listOf(agendaWhen(day, e, ui), e.location).filter { it.isNotBlank() }.joinToString(" · ") }, CW - 60, "s")[
                                0],
                        open = {
                            ui.push(
                                object : ReaderView(
                                    ui,
                                    "ev",
                                    {
                                        day.day +
                                            "\n" +
                                            eventTime(e.start, e.allDay, e.timeLabel, ui) +
                                            "\n" +
                                            e.location +
                                            "\n\n" +
                                            e.notes.ifEmpty { "No notes." }
                                    },
                                    e.title,
                                    "calendar",
                                ) {
                                    override val textContext get() = agendaWhen(day, e, ui)
                                    override fun textContent() = listOf(day.day,
                                        eventTime(e.start, e.allDay, e.timeLabel, ui),
                                        e.location.takeIf { it.isNotBlank() }?.let { "At: $it" }.orEmpty(),
                                        e.notes.ifEmpty { "No notes." }).filter { it.isNotBlank() }.joinToString("\n")
                                }
                            )
                        },
                    )
                }
            }
        },
        chrome = { t ->
            frame(
                ui,
                "Calendar",
                "calendar",
                t,
                railText = listOf(ui.data.calendar.dateLabel.ifEmpty { ui.dateString("EEE MMM d") }),
            )
        },
    ) {
    init {
        refreshHook = { ui.data.calendar.refresh() }
        textItems = {
            val entries = agendaEntries(ui)
            rows().mapIndexed { i, row ->
                val (day, event) = entries[i]
                if (row.sep || event == null || event === NOTHING_TODAY) row
                else row.copy(label = agendaWhen(day, event, ui) + " " + event.title,
                    sub = event.location.takeIf { it.isNotBlank() }, subLines = null)
            }
        }
        animateChrome = false
    }
}

class Status(ui: Ui) : Screen(ui, "status") {
    companion object {
        /** Six 28 px rows fit: battery rows, Wi-Fi/cell, then Brightness only while there are at most four batteries. */
        fun brightnessFits(batteryRows: Int) = batteryRows <= 4
    }
    private var textPage = 0
    private fun statusLines(): List<String> {
        val s = ui.data.status.snapshot
        return listOf(s.link) + s.batteries.mapNotNull { b -> b.level?.let { level ->
            b.label + " " + TextChrome.bar(level, 6) + " ${(level * 100).toInt()}%"
        } ?: if (b.icon == "buds") b.label + " -" else null } + listOf("Wi-Fi " + s.wifi, "Cell " + s.cell) +
            listOfNotNull(ui.data.settings.snapshot.glassesBrightness?.let { "Brightness $it/10" })
    }
    override fun key(k: String): Boolean {
        if (ui.isText && k in listOf("up", "down", "left", "right")) {
            val n = ui.textPages(statusLines().joinToString("\n")).size
            textPage = (textPage + if (k in listOf("down", "right")) 1 else -1).coerceIn(0, n - 1)
            return true
        }
        return false
    }
    override fun onOpen() {
        ui.refresh { ui.data.status.refresh() }
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val status = ui.data.status.snapshot
            frame(ui, "Status", "status", t, railText = listOf(status.link))
            var y = TOP
            // A connected earbud that reports no level keeps its row with a dash (16.4); other rows need a level.
            val rows = status.batteries.filter { it.level != null || it.icon == "buds" }
            rows.forEach { b ->
                icon(b.icon, CX, y, 24, t)
                tx(fit(b.label, 132), CX + 34, y + 2)
                val level = b.level
                if (level != null) {
                    bar(CX + 170, y + 4, CW - 240, 16, level)
                    txR("${floor(level*100+.5).toInt()}%", W - 16, y + 2)
                } else txR("—", W - 16, y + 2, tone = SECONDARY)
                y += 28
            }
            icon("wifi", CX, y, 24, t)
            tx(status.wifi, CX + 34, y + 2)
            icon("cell", CX + 196, y, 24, t)
            tx(status.cell, CX + 226, y + 2)
            y += 28
            // Six rows fit: with more than four battery rows the Brightness row (also in Settings) gives way.
            if (brightnessFits(rows.size)) ui.data.settings.snapshot.glassesBrightness?.let { b ->
                icon("bright", CX, y, 24, t)
                tx("Brightness", CX + 34, y + 2)
                bar(CX + 170, y + 4, CW - 240, 16, b / 10.0)
                txR("$b/10", W - 16, y + 2)
            }
            Unit
        }

    override fun drawText(tg: TextGrid) {
        textPage = TextChrome.reader(tg, "Status", ui.clockString(),
            gridPages(statusLines().joinToString("\n"), tg.cols, tg.rows - 2), textPage)
    }
}

class HomeAssistant(ui: Ui) :
    ListView(
        ui,
        "ha",
        items = {
            val panel = ui.data.ha.panel
            listOf(Row("SCENES", sep = true)) +
                panel.scenes.map { s ->
                    Row(
                        s.name,
                        icon = s.icon,
                        open = {
                            ui.push(
                                ListView(
                                    ui,
                                    "confirm",
                                    items = {
                                        listOf(
                                            Row(
                                                "Run \"${s.name}\"",
                                                icon = "check",
                                                open = {
                                                    ui.perform(after = { ui.pop() }) {
                                                        ui.data.ha.scene(s.id)
                                                    }
                                                },
                                            ),
                                            Row("Cancel", icon = "cross", open = { ui.pop() }),
                                        )
                                    },
                                    chrome = { t -> frame(ui, "Run scene?", s.icon, t) },
                                )
                            )
                        },
                    )
                } +
                listOf(
                    Row("ALARM", sep = true),
                    Row(
                        "Alarm time",
                        icon = "alarm",
                        right = panel.alarm.time,
                        selLabel = "Toggle 7:00 / 8:30",
                        open = {
                            ui.perform {
                                ui.data.ha.alarm(
                                    time =
                                        if (panel.alarm.time == "7:00 AM") "8:30 AM" else "7:00 AM"
                                )
                            }
                        },
                    ),
                    Row(
                        "Alarm style",
                        icon = "alarm",
                        right = panel.alarm.style,
                        selLabel = "Toggle harsh / gentle",
                        open = {
                            ui.perform {
                                ui.data.ha.alarm(
                                    style = if (panel.alarm.style == "Gentle") "Harsh" else "Gentle"
                                )
                            }
                        },
                    ),
                    Row("PRINTERS", sep = true),
                ) +
                panel.printers.map { p ->
                    Row(
                        p.name,
                        icon = "printer",
                        right = p.state,
                        open = {
                            ui.push(
                                ReaderView(
                                    ui,
                                    "pr",
                                    {
                                        ui.data.ha.panel.printers
                                            .find { it.id == p.id }
                                            ?.detail
                                            .orEmpty()
                                    },
                                    p.name,
                                    "printer",
                                )
                            )
                        },
                    )
                }
        },
        chrome = { t -> frame(ui, "Home", "home", t) },
    ) {
    init {
        refreshHook = { ui.data.ha.refresh() }
        textHint = { if (rows().getOrNull(cur)?.label?.startsWith("Alarm") == true) "Y cycle B back" else "Y pick B back" }
        textItems = {
            rows().map { row ->
                val printer = ui.data.ha.panel.printers.find { it.name == row.label }
                if (printer == null) row else {
                    val progress = if (printer.layers > 0) printer.layer.toDouble() / printer.layers else printer.progress
                    val state = if ('%' in printer.state || printer.state.startsWith("Layer "))
                        if (progress >= 1) "Done" else "Printing" else printer.state
                    val summary = "${(progress.coerceIn(0.0, 1.0) * 100).roundToInt()}% $state"
                    row.copy(right = summary, sub = summary.takeIf { it.length > ui.textDisplay.cols / 2 })
                }
            }
        }
    }
    override fun drawText(tg: TextGrid) {
        if (cur == 0) cur = 1
        TextChrome.header(tg, textTitle(), textContext())
        // The three scenes fit with the alarm section and its first item.
        TextChrome.list(tg, textItems().drop(1), cur - 1)
        TextChrome.footer(tg, hint = textHint())
    }

}

class Docs(ui: Ui) :
    ListView(
        ui,
        "docs",
        items = {
            ui.data.docs.documents.map { d ->
                Row(
                    d.title,
                    icon = "docs",
                    open = {
                        ui.push(
                            object :
                                ReaderView(
                                    ui,
                                    "doc",
                                    {
                                        ui.data.docs.documents
                                            .find { it.id == d.id }
                                            ?.text
                                            .orEmpty()
                                    },
                                    d.title,
                                    "docs",
                                ) {
                                override fun onOpen() {
                                    ui.refresh { ui.data.docs.refreshDocument(d.id) }
                                }
                            }
                        )
                    },
                )
            }
        },
        chrome = { t ->
            frame(ui, "Docs", "docs", t, railText = listOf("${ui.data.docs.documents.size} docs"))
        },
    ) {
    init {
        refreshHook = { ui.data.docs.refresh() }
        animateChrome = false
    }
}

class Teleprompter(ui: Ui) : Screen(ui, "tp") {
    var off = 0
    var auto = false
    override val keepAwake get() = auto
    private var last = 0.0
    override val needsAnimation
        get() = auto

    override fun onOpen() {
        ui.refresh { ui.data.memos.refresh() }
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            frame(
                ui,
                "Teleprompter",
                "prompter",
                if (auto) t else 0.0,
                railText = listOf(if (auto) "Auto-scroll" else "Paused"),
            )
            val lines = layout(ui.data.memos.script, CW - 12, "l")
            val rows = (BOTTOM - TOP - 20) / lh("l")
            if (auto && t - last > 1.6) {
                // Keep a 1.6 s pace however often the screen is rendered (animation renders can be paced to 1 s).
                last = if (t - last < 3.2) last + 1.6 else t
                off = min(off + 1, max(0, lines.lastIndex))
            }
            var y = TOP
            lines.drop(off).take(rows).forEach {
                drawLine(it, CX, y, "l")
                y += lh("l")
            }
            txR("${off+1}/${lines.size}", W - 14, BOTTOM - 16, "s", tone = SECONDARY)
        }

    private fun scriptPages(): List<List<String>> = ui.data.memos.script
        .split(Regex("(?<=[.!?])\\s+")).flatMap { ui.textPages(it) }
    private fun scriptLines() = scriptPages().flatten()

    private fun textPageIndex(): Int {
        var end = 0
        return scriptPages().indexOfFirst { end += it.size; off < end }.coerceAtLeast(0)
    }

    override fun key(k: String): Boolean {
        val n = if (ui.isText) scriptLines().size else layout(ui.data.memos.script, CW - 12, "l").size
        if (ui.isText && k in listOf("left", "right")) {
            val ps = scriptPages()
            val index = (textPageIndex() + if (k == "right") 1 else -1).coerceIn(0, ps.lastIndex)
            off = ps.take(index).sumOf { it.size }
            return true
        }
        when (k) {
            "sel" -> {
                auto = !auto
                last = ui.nowS()
            }
            "down",
            "right" -> off = min(n - 1, off + 1)
            "up",
            "left" -> off = max(0, off - 1)
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf(
            "sel" to if (auto) "Pause scrolling" else "Auto-scroll",
            "up" to "Line back",
            "down" to "Line forward",
        )

    override fun drawText(tg: TextGrid) {
        val ls = scriptLines()
        val height = tg.rows - 2
        if (auto && ui.nowS() - last > 1.6) {
            last = ui.nowS()
            off = min(off + 1, ls.lastIndex)
        }
        off = off.coerceIn(0, ls.lastIndex)
        TextChrome.header(tg, "Teleprompter", if (auto) "AUTO" else "Paused")
        val end = scriptPages().take(textPageIndex() + 1).sumOf { it.size }
        ls.drop(off).take(min(height, end - off)).forEachIndexed { i, line -> tg.put(0, i + 1, line) }
        TextChrome.footer(tg, off, ls.size, "Y auto-scroll ^v line")
    }
}

class VoiceMemo(ui: Ui) : Screen(ui, "memo") {
    companion object {
        fun shortcut(ui: Ui) = io.lampyr.glasses.input.AppShortcut(
            "memo.toggle", "memo", "Memo: start/stop", true,
            run = {
                // A call owns the microphone: a shortcut may stop a memo during one, never start one.
                if(!ui.data.memos.snapshot.recording && ui.data.calls.snapshot.calls.isNotEmpty()) return@AppShortcut "Microphone reserved for phone call"
                val label=if(ui.data.memos.snapshot.recording) "Memo stopped" else "Memo started"
                val result=ui.data.memos.toggle()
                result.message ?: if(result.ok) label else "Memo failed"
            }, state = { if(ui.data.memos.snapshot.recording) "Stop voice memo" else "Start voice memo" },
        )
    }
    private var memoPage = 0
    override fun key(k: String): Boolean {
        if (ui.isText || k !in listOf("up", "down")) return false
        memoPage = (memoPage + if (k == "down") 1 else -1).coerceIn(0, maxOf(0, ui.data.memos.snapshot.list.lastIndex))
        return true
    }
    override val needsAnimation
        get() = ui.data.memos.snapshot.recording

    override fun onOpen() {
        ui.refresh { ui.data.memos.refresh() }
    }

    override fun talkTap(): Boolean {
        ui.perform { ui.data.memos.toggle() }
        return true
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val m = ui.data.memos.snapshot
            val s = if (m.recording) floor(t - m.since).toInt() else 0
            frame(
                ui,
                "Voice memo",
                "mic",
                if (m.recording) -t else 0.0,
                railText = listOf(if (m.recording) "RECORDING" else "Ready"),
            )
            tx(if (m.recording) "RECORDING" else "READY", CX, TOP - 2)
            tx("${s/60}:${(s%60).toString().padStart(2,'0')}", CX, TOP + lh(), "l")
            hintText(if (m.recording) "Tap L to stop" else "Tap L to start", CX + 180, TOP + lh() + 18, "s")
            var y = TOP + lh() + lh("l") + 10
            m.stoppedReason?.takeIf { !m.recording }?.let {
                tx(fit(it, CW, "s"), CX, y, "s")
                y += lh("s") + 4
            }
            p.fr(CX, y, CW, 2, tone = TERTIARY)
            y += 8
            tx("TODAY", CX, y, "s", tone = SECONDARY)
            y += lh("s") + 2
            m.list.drop(memoPage).take(1).forEach { memo ->
                icon("mic", CX, y + 4, 20)
                tx(fit(memo.title, CW - 40), CX + 30, y)
                tx(fit(memo.at + " · " + fmtS(memo.durationSec) + " · " + memo.uploadLabel, W - 8 - (CX + 30), "s"), CX + 30, y + lh(), "s", tone = SECONDARY)
                y += lh() + lh("s") + 4
            }
            if (m.list.size > 1) txR("${memoPage + 1}/${m.list.size} ▲▼", W - 14, BOTTOM - 16, "s", tone = SECONDARY)
        }

    override fun legend() = mapOf("talkT" to "Start / stop recording")

    override fun drawText(tg: TextGrid) {
        val m = ui.data.memos.snapshot
        TextChrome.header(tg, "Voice memo", if (m.recording) "RECORDING" else "READY")
        tg.put(0, 1, if (m.recording) fmtS(ui.nowS() - m.since) else "0:00")
        val reasonRows = if (!m.recording && !m.stoppedReason.isNullOrBlank()) gridLines(m.stoppedReason, tg.cols).take(2) else emptyList()
        reasonRows.forEachIndexed { i, line -> tg.put(0, i + 2, line) }
        val section = 2 + reasonRows.size
        TextChrome.section(tg, section, "TODAY")
        m.list.take((tg.rows - section - 2).coerceAtLeast(0)).forEachIndexed { i, memo ->
            // Text rows are narrow: the upload state is shown only while no delivery target has the memo yet.
            TextChrome.pair(tg, i + section + 1, memo.title, memo.at.replace(" PM", "p").replace(" AM", "a") + " " + fmtS(memo.durationSec) +
                memo.uploadLabel.takeUnless { memo.delivered }?.let { " $it" }.orEmpty())
        }
        TextChrome.footer(tg, hint = if (m.recording) "L stop B back" else "L record B back")
    }
}

class Settings(ui: Ui) :
    ListView(
        ui,
        "set",
        items = {
            val s = ui.data.settings.snapshot
            listOf(
                Row(
                    "Brightness",
                    icon = "bright",
                    right = s.glassesBrightness?.let { "$it/10" } ?: "—",
                    sub = "set on the glasses",
                ),
                Row(
                    "App order",
                    icon = "todo",
                    right = "",
                    open = { ui.push(AppOrder(ui)) },
                ),
                Row(
                    "Display & sleep",
                    icon = "moon",
                    sub = "Fade, wake and lock",
                    open = { ui.push(PowerSettings(ui)) },
                ),
                Row(
                    "Head gestures",
                    icon = "glasses",
                    sub = headGestureSummary(ui),
                    open = { ui.push(HeadGestureSettings(ui)) },
                ),
                Row(
                    "Display position",
                    icon = "glasses",
                    sub = displayPositionSummary(ui),
                    open = { ui.push(DisplayPositionSettings(ui)) },
                ),
                Row(
                    "About",
                    icon = "docs",
                    right = if (ui.data is io.lampyr.glasses.data.LiveDataStore) "v${io.lampyr.glasses.BuildConfig.VERSION_NAME}" else "v0.3",
                    open = { ui.flash("RayNeo Companion ${io.lampyr.glasses.BuildConfig.VERSION_NAME}") },
                ),
                Row("Shortcuts", icon = "todo", open = { ui.push(Shortcuts(ui)) }),
            )
        },
        chrome = { t -> frame(ui, "Settings", "settings", t) },
    ) {
    init {
        refreshHook = { ui.data.settings.refresh() }
        textItems = { rows().map { it.asciiText() } }
        textHint = { if (rows().getOrNull(cur)?.open != null) "Y open B back" else "B back" }
    }
}

class AppOrder(ui: Ui) :
    ListView(
        ui,
        "order",
        items = {
            appEntries(ui).map { a ->
                Row(a.label, icon = a.icon, selLabel = "Pick up / drop", open = {})
            }
        },
    ) {
    init {
        textContext = { if (grab) "Moving" else ui.clockString() }
        textHint = { if (grab) "^v move Y drop" else "Y pick up B back" }
    }
    var grab = false

    override fun draw(g: Gfx, t: Double) {
        g.frame(
            ui,
            "App order",
            "todo",
            t,
            railText = listOf(if (grab) "Moving" else "Y to pick up"),
        )
        super.draw(g, t)
    }

    override fun key(k: String): Boolean {
        if (k == "sel") {
            grab = !grab
            ui.flash(if (grab) "Moving " + appEntries(ui).getOrNull(cur)?.label else "Dropped")
            return true
        }
        if (grab && (k == "up" || k == "down")) {
            val j = cur + if (k == "up") -1 else 1
            val s = ui.data.settings.snapshot
            val order = appEntries(ui).map { it.id }.toMutableList()
            if (j !in order.indices) return true
            val value = order[cur]
            order[cur] = order[j]
            order[j] = value
            ui.perform(after = { cur = j }) { ui.data.settings.update(s.copy(appOrder = order)) }
            return true
        }
        return super.key(k)
    }
}
