package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.clock.*
import io.lampyr.glasses.data.ActionResult
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import kotlin.math.floor
import kotlinx.coroutines.launch

/*
 * Clock app (requirement 7): Timer, Stopwatch and Alarms. State lives in the service-owned ClockEngine (ui.clock),
 * so everything keeps running when these screens are closed, the Ui is rebuilt, or the app restarts.
 *
 * 8BitDo controls: Y = select/primary, X = secondary, B = back, D-pad = move/change, + = new, L = say a time.
 */

private fun ClockResult.toAction() = if (ok) ActionResult(message = message) else ActionResult.failure(message ?: "Failed")

/** Hold L in the Clock app: "10 minutes", "pasta 8 minutes", "7:30 am", "weekdays at 6". Parsed on the phone. */
fun clockTalk(ui: Ui, prefer: String) = TalkTarget(
    label = if (prefer == "alarm") "Say an alarm time" else "Say a timer",
    target = "raw", // The gateway's raw-transcript path: exact words, no LLM rewrite.
    send = { text -> clockVoice(ui, text, prefer) },
)

fun clockVoice(ui: Ui, text: String, prefer: String = "timer"): ActionResult {
    val result = when (val c = ClockParse.command(text, prefer)) {
        is ClockParse.Command.Timer -> ui.clock.timerStart(c.ms, c.label)
        is ClockParse.Command.Alarm -> ui.clock.alarmSet(c.hour, c.minute, c.days, c.label)
        null -> ClockResult.fail("Didn't catch a time. Try \"10 minutes\" or \"7:30 am\"")
    }
    if (result.ok) {
        // Back on the screen that shows the new item.
        while (ui.top is DictationScreen) ui.pop()
        val timer = ui.clock.timer(result.id)
        (ui.top as? TimerScreen)?.focus(result.id)
        if (timer != null && ui.top !is TimerScreen) ui.push(TimerScreen(ui).also { it.focus(result.id) })
    }
    return result.toAction()
}

private fun timerName(ui: Ui, t: TimerItem): String = t.label.ifBlank {
    if (t.source == SOURCE_PHONE) "Phone timer" else "Timer " + (ui.clock.timers.indexOf(t) + 1)
}

/** The Clock hub: one row per tool, plus settings. */
class ClockHub(ui: Ui) : ListView(ui, "clock", items = { clockHubRows(ui) }, chrome = { t ->
    val running = ui.clock.timers.count { it.running }
    frame(ui, "Clock", "clock", t, railText = listOf(if (running > 0) "$running running" else ""))
}) {
    init {
        animateChrome = false
        textTitle = { "Clock" }
        target = clockTalk(ui, "timer")
        leg = mapOf("talkH" to "Say a timer or alarm")
    }
    // The service's 1 Hz clock ticker re-renders this screen; no 10 fps animation over Bluetooth for a seconds display.
    override val needsAnimation get() = false
}

private fun clockHubRows(ui: Ui): List<Row> {
    val c = ui.clock
    val rows = mutableListOf<Row>()
    c.ringing.lastOrNull()?.let { r ->
        rows += Row(if (r.kind == "timer") "Timer done" else "Alarm", sub = r.label.ifBlank { r.at }.ifBlank { null }, icon = "alarm",
            right = "STOP", open = { ui.push(ClockRinging(ui)) })
    }
    val t = c.timers.firstOrNull()
    rows += Row(
        "Timer",
        sub = when {
            t == null -> "Last ${fmtClock(c.snapshot.lastTimerMs)} - X to start"
            !t.running -> "${timerName(ui, t)} - paused"
            else -> "${timerName(ui, t)} - ends ${c.fmtWall(ui.nowMs() + c.remainingMs(t))}"
        },
        icon = "clock",
        right = when {
            t == null -> "Off"
            !t.running -> "Paused"
            else -> fmtClock(c.remainingMs(t))
        },
        open = { ui.push(TimerScreen(ui)) },
        act = {
            if (c.timers.isEmpty()) ui.flash(c.timerStart(c.snapshot.lastTimerMs).message ?: "Started")
            else ui.push(TimerScreen(ui))
        },
        actLabel = if (t == null) "Repeat last timer" else "Timers",
    )
    val sw = c.snapshot.stopwatch
    val laps = sw.laps.size
    rows += Row(
        "Stopwatch",
        sub = (if (sw.running) "Running" else "Stopped") + if (laps > 0) " - $laps lap" + (if (laps == 1) "" else "s") else "",
        icon = "gauge",
        right = fmtClock(c.stopwatchMs(), tenths = true),
        open = { ui.push(StopwatchScreen(ui)) },
        act = { ui.flash((if (sw.running) c.stopwatchStop() else c.stopwatchStart()).message ?: "") },
        actLabel = if (sw.running) "Stop stopwatch" else "Start stopwatch",
    )
    val next = c.nextAlarm()
    val phone = c.phoneState.nextAlarmWallMs
    rows += Row(
        "Alarms",
        sub = when {
            next != null -> c.relativeDay(next.second).replaceFirstChar { it.uppercase() } + (next.first.label.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
            phone != null -> "Phone clock " + c.relativeDay(phone)
            else -> "No alarms"
        },
        icon = "alarm",
        right = next?.let { fmtTime(it.first.hour, it.first.minute) } ?: phone?.let { c.fmtWall(it) } ?: "None",
        open = { ui.push(AlarmsScreen(ui)) },
    )
    rows += Row("Settings", icon = "settings", open = { ui.push(ClockSettingsScreen(ui)) })
    return rows
}

/** One page per timer (glasses and phone clock), then a "New" page. ◀▶ change page. */
class TimerScreen(ui: Ui) : Screen(ui, "timer") {
    var page = 0
    private var focusId: String? = null
    private var newMs = ui.clock.snapshot.lastTimerMs
    private var cancelArmed = 0.0
    private val timers get() = ui.clock.timers
    val pages get() = timers.size + 1

    fun focus(id: String?) {
        focusId = id
        timers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { page = it }
    }

    private fun current(): TimerItem? {
        val list = timers
        focusId?.let { id -> list.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { page = it } }
        page = page.coerceIn(0, list.size)
        return list.getOrNull(page).also { focusId = it?.id }
    }

    // Redrawn once a second by the service's clock ticker (timers show whole seconds).
    override val needsAnimation get() = false
    override val talk get() = clockTalk(ui, "timer")

    override fun draw(g: Gfx, t: Double) = with(g) {
        val timer = current()
        val running = timers.count { it.running }
        frame(ui, "Timer", "clock", t, railText = listOf(if (running > 0) "$running running" else "", "${page + 1}/$pages"))
        if (timer == null) {
            tx("NEW TIMER", CX, TOP - 2, "m")
            tx(fmtClock(newMs), CX, TOP + lh("m"), "xl")
            tx(fit("X preset  L say a time", CW, "s"), CX, TOP + lh("m") + lh("xl") + 6, "s", tone = SECONDARY)
            hintRow("Y start  ▲▼ time  X preset  ◀▶ page")
            return@with
        }
        val left = ui.clock.remainingMs(timer)
        tx(fit(timerName(ui, timer), CW - 110, "m"), CX, TOP - 2, "m")
        txR(when { timer.source == SOURCE_PHONE -> "PHONE"; !timer.running -> "PAUSED"; else -> "RUNNING" }, W - 14, TOP, "s", tone = SECONDARY)
        tx(fmtClock(left), CX, TOP + lh("m"), "xl")
        val detail = (if (timer.running) "Ends ${ui.clock.fmtWall(ui.nowMs() + left)}" else "Paused") +
            (if (timer.durationMs > 0) " - ${fmtClock(timer.durationMs)} total" else "")
        val y = TOP + lh("m") + lh("xl") + 6
        tx(fit(detail, CW, "s"), CX, y, "s", tone = SECONDARY)
        if (timer.durationMs > 0) bar(CX, y + lh("s") + 6, CW, 12, left.toDouble() / timer.durationMs)
        hintRow(if (ui.nowS() < cancelArmed) "X again to cancel" else (if (timer.running) "Y pause" else "Y resume") + "  X cancel  ▲▼ 1 min  ◀▶ page")
    }

    override fun key(k: String): Boolean {
        val timer = current()
        when (k) {
            "left" -> { page = (page - 1).coerceAtLeast(0); focusId = timers.getOrNull(page)?.id; return true }
            "right" -> { page = (page + 1).coerceAtMost(timers.size); focusId = timers.getOrNull(page)?.id; return true }
            "plus" -> { page = timers.size; focusId = null; return true }
        }
        if (timer == null) {
            when (k) {
                "up" -> newMs = stepUp(newMs)
                "down" -> newMs = stepDown(newMs)
                "act" -> newMs = PRESETS.firstOrNull { it > newMs } ?: PRESETS.first()
                "sel" -> {
                    val r = ui.clock.timerStart(newMs)
                    ui.flash(r.message ?: "Started")
                    if (r.ok) focus(r.id)
                }
                else -> return false
            }
            return true
        }
        val result = when (k) {
            "sel" -> if (timer.running) ui.clock.timerPause(timer.id) else ui.clock.timerResume(timer.id)
            "up" -> ui.clock.timerAdd(timer.id, 60_000)
            "down" -> ui.clock.timerAdd(timer.id, -60_000)
            "act" -> if (ui.nowS() < cancelArmed) { cancelArmed = 0.0; ui.clock.timerCancel(timer.id).also { focusId = null } }
                else { cancelArmed = ui.nowS() + 2; ui.requestRender(); return true }
            else -> return false
        }
        ui.flash(result.message ?: if (result.ok) "Done" else "Failed")
        return true
    }

    override fun legend() = if (current() == null) mapOf("sel" to "Start", "up" to "Longer", "down" to "Shorter", "act" to "Next preset", "left" to "Previous timer", "talkH" to "Say a timer")
        else mapOf("sel" to "Pause / resume", "act" to "Cancel (press twice)", "up" to "+1 min", "down" to "-1 min", "left" to "Previous", "right" to "Next", "plus" to "New timer", "talkH" to "Say a timer")

    override fun drawText(tg: TextGrid) {
        val timer = current()
        TextChrome.header(tg, "Timer", "${page + 1}/$pages")
        if (timer == null) {
            tg.put(0, 1, "New timer")
            tg.put(0, 2, "Set ${fmtClock(newMs)}")
            tg.put(0, 3, TextChrome.fit("X preset L say it", tg.cols))
            TextChrome.footer(tg, hint = "Y start ^v time X preset")
            return
        }
        val left = ui.clock.remainingMs(timer)
        TextChrome.pair(tg, 1, timerName(ui, timer), when { timer.source == SOURCE_PHONE -> "PHONE"; !timer.running -> "PAUSED"; else -> "RUN" })
        tg.put(0, 2, TextChrome.fit("${fmtClock(left)} left", tg.cols))
        tg.put(0, 3, TextChrome.fit(if (timer.running) "ends ${ui.clock.fmtWall(ui.nowMs() + left)}" else "paused", tg.cols))
        if (tg.rows >= 7 && timer.durationMs > 0) tg.put(0, 4, TextChrome.bar(left.toDouble() / timer.durationMs, (tg.cols - 2).coerceAtMost(20)))
        TextChrome.footer(tg, hint = if (ui.nowS() < cancelArmed) "X again to cancel" else (if (timer.running) "Y pause" else "Y resume") + " X cancel ^v 1min")
    }

    companion object {
        val PRESETS = listOf(1, 3, 5, 10, 15, 20, 30, 45, 60).map { it * 60_000L }
        fun stepUp(ms: Long) = (when { ms < 60_000 -> ms + 10_000; ms < 600_000 -> ms + 60_000; else -> ms + 300_000 }).coerceAtMost(ClockEngine.MAX_TIMER_MS)
        fun stepDown(ms: Long) = (when { ms <= 60_000 -> ms - 10_000; ms <= 600_000 -> ms - 60_000; else -> ms - 300_000 }).coerceAtLeast(10_000)
    }
}

/** Stopwatch with laps. Y start/stop, X lap (running) or reset (stopped, press twice when there are laps). */
class StopwatchScreen(ui: Ui) : Screen(ui, "stopwatch") {
    var lapTop = 0
    private var resetArmed = 0.0
    private val sw get() = ui.clock.snapshot.stopwatch

    override val needsAnimation get() = sw.running

    private fun lapRows(): List<Triple<String, Long, Long>> {
        val laps = sw.laps
        return laps.indices.reversed().map { i -> Triple("Lap ${i + 1}", laps[i] - (laps.getOrNull(i - 1) ?: 0), laps[i]) }
    }

    override fun draw(g: Gfx, t: Double) = with(g) {
        val s = sw
        frame(ui, "Stopwatch", "gauge", t, railText = listOf(if (s.running) "Running" else "Stopped"))
        tx(if (s.running) "RUNNING" else "STOPPED", CX, TOP - 2, "m")
        tx(fmtClock(ui.clock.stopwatchMs(), tenths = true), CX, TOP + lh("m"), "xl")
        var y = TOP + lh("m") + lh("xl") + 6
        val rows = lapRows()
        lapTop = lapTop.coerceIn(0, (rows.size - 3).coerceAtLeast(0))
        if (rows.isEmpty()) tx(if (s.running) "X marks a lap" else "No laps", CX, y, "s", tone = SECONDARY)
        rows.drop(lapTop).take(3).forEach { (name, lap, total) ->
            tx(name, CX, y, "s", tone = SECONDARY)
            tx(fmtClock(lap, tenths = true), CX + 120, y, "s")
            txR(fmtClock(total, tenths = true), W - 14, y, "s", tone = SECONDARY)
            y += lh("s")
        }
        hintRow(when {
            ui.nowS() < resetArmed -> "X again to reset"
            s.running -> "Y stop  X lap"
            else -> "Y start  X reset" + if (rows.size > 3) "  ▲▼ laps" else ""
        })
    }

    override fun key(k: String): Boolean {
        val c = ui.clock
        val r = when (k) {
            "sel" -> if (sw.running) c.stopwatchStop() else c.stopwatchStart()
            "act" -> when {
                sw.running -> c.stopwatchLap()
                (sw.laps.isNotEmpty() || sw.accumulatedMs > 0) && ui.nowS() >= resetArmed -> { resetArmed = ui.nowS() + 2; ui.requestRender(); return true }
                else -> { resetArmed = 0.0; c.stopwatchReset() }
            }
            "up" -> { lapTop = (lapTop - 1).coerceAtLeast(0); return true }
            "down" -> { lapTop += 1; return true }
            else -> return false
        }
        if (k == "act" && sw.running) ui.flash(r.message ?: "Lap") else if (!r.ok) ui.flash(r.message ?: "Failed")
        return true
    }

    override fun legend() = mapOf("sel" to if (sw.running) "Stop" else "Start", "act" to if (sw.running) "Lap" else "Reset", "up" to "Earlier laps", "down" to "Later laps")

    override fun drawText(tg: TextGrid) {
        val s = sw
        TextChrome.header(tg, "Stopwatch", if (s.running) "RUN" else "STOP")
        tg.put(0, 1, fmtClock(ui.clock.stopwatchMs(), tenths = true))
        val rows = lapRows()
        val space = tg.rows - 3
        if (rows.isEmpty()) tg.put(0, 2, if (s.running) "X marks a lap" else "No laps")
        rows.drop(lapTop.coerceIn(0, (rows.size - space).coerceAtLeast(0))).take(space).forEachIndexed { i, (name, lap, _) ->
            TextChrome.pair(tg, 2 + i, name, fmtClock(lap, tenths = true))
        }
        TextChrome.footer(tg, hint = when {
            ui.nowS() < resetArmed -> "X again to reset"
            s.running -> "Y stop X lap"
            else -> "Y start X reset"
        })
    }
}

/** Glasses alarms (Y on/off, X edit), a New row, the phone clock's next alarm and the Home Assistant alarm. */
class AlarmsScreen(ui: Ui) : ListView(ui, "alarms", items = { alarmRows(ui) }, chrome = { t ->
    frame(ui, "Alarms", "alarm", t, railText = listOf(ui.clock.snapshot.alarms.count { it.enabled }.let { if (it > 0) "$it on" else "" }))
}) {
    init {
        animateChrome = false
        textTitle = { "Alarms" }
        target = clockTalk(ui, "alarm")
        textHint = {
            val r = rows().getOrNull(cur)
            when {
                r?.act != null -> "Y on/off X edit"
                r?.selLabel?.endsWith("on phone") == true -> "Y on/off (phone)"
                r?.open != null -> "Y open B back"
                else -> "B back"
            }
        }
        leg = mapOf("talkH" to "Say an alarm time")
    }
}

private fun alarmRows(ui: Ui): List<Row> {
    val c = ui.clock
    val rows = mutableListOf(Row("GLASSES", sep = true))
    c.snapshot.alarms.sortedWith(compareBy({ it.hour }, { it.minute })).forEach { a ->
        val next = c.nextAlarmAt(a)
        rows += Row(
            fmtTime(a.hour, a.minute),
            sub = listOfNotNull(fmtDays(a.days), next?.let { if (a.snoozedUntilMs > 0) "snoozed" else c.relativeDay(it).substringBefore(' ') }, a.label.ifBlank { null }).joinToString(" - "),
            right = if (a.enabled || a.snoozedUntilMs > 0) "On" else "Off",
            open = { ui.flash(c.alarmEnable(a.id, !a.enabled).message ?: "") },
            selLabel = if (a.enabled) "Turn off" else "Turn on",
            act = { ui.push(AlarmSet(ui, a.id)) },
            actLabel = "Edit",
        )
    }
    rows += Row("New alarm", icon = "plus", open = { ui.push(AlarmSet(ui, null)) })
    rows += Row("PHONE", sep = true)
    val phone = c.phoneState
    rows += Row(
        "Phone clock",
        right = phone.nextAlarmWallMs?.let { c.fmtWall(it) } ?: "None",
        open = {
            val key = phone.nextAlarmKey
            if (key != null && "dismiss" in phone.nextAlarmActions && c.phone.act(key, "dismiss")) ui.flash("Skipped the next phone alarm")
            else ui.flash("Manage phone alarms on the phone")
        },
        selLabel = "Skip next (when the phone offers it)",
    )
    rows += Row("Home alarm", right = ui.data.ha.panel.alarm.time, open = { ui.push(ui.app("ha")) })
    rows += phoneAlarmRows(ui)
    return rows
}

/**
 * EXPERIMENTAL: the user's existing Google Clock alarms (clock/PhoneAlarms.kt). Reading or switching one briefly opens
 * Google Clock on the unlocked phone through the accessibility service, then returns to the previous app.
 */
private fun phoneAlarmRows(ui: Ui): List<Row> {
    val book = ui.clock.phoneAlarms
    val rows = mutableListOf(Row("PHONE ALARMS", sep = true))
    val status = book.busy?.let { "$it..." } ?: book.lastError ?: book.cache.readAtMs.takeIf { it > 0 }?.let { "read " + ui.clock.fmtWall(it) }
    rows += Row(
        if (book.alarms.isEmpty()) "Read phone alarms" else "Refresh phone alarms",
        sub = listOfNotNull("Experimental", status).joinToString(" - "),
        right = if (book.busy != null) "..." else book.alarms.size.takeIf { it > 0 }?.toString().orEmpty(),
        open = { phoneAlarmRun(ui) { book.refresh() } },
        selLabel = "Read (opens Google Clock)",
    )
    book.alarms.forEach { a ->
        rows += Row(
            a.time,
            sub = listOfNotNull(a.days.ifBlank { null }, a.label.ifBlank { null }).joinToString(" - ").ifEmpty { null },
            right = if (a.enabled) "On" else "Off",
            open = { phoneAlarmRun(ui) { book.setEnabled(a, !a.enabled) } },
            selLabel = if (a.enabled) "Turn off on phone" else "Turn on on phone",
        )
    }
    return rows
}

private fun phoneAlarmRun(ui: Ui, block: suspend () -> PhoneAlarmOutcome) {
    if (ui.clock.phoneAlarms.busy != null) { ui.flash("Phone alarms busy"); return }
    ui.flash("Opening Google Clock")
    ui.scope.launch { ui.flash(block().message) }
}

/** New or edit alarm. ◀▶ field (hour, minute, AM/PM, repeat), ▲▼ change, Y save, X delete (twice). */
class AlarmSet(ui: Ui, val alarmId: String?) : Screen(ui, "alarm-set") {
    private val existing = alarmId?.let { ui.clock.alarm(it) }
    var hour = existing?.hour ?: 7
    var minute = existing?.minute ?: 0
    var days: Set<Int> = existing?.days ?: emptySet()
    var label = existing?.label.orEmpty()
    var field = 0
    private var deleteArmed = 0.0

    override val needsAnimation get() = false
    override val talk get() = TalkTarget("Say the alarm time", "raw", send = { text ->
        val time = ClockParse.time(text)
        if (time == null) ActionResult.failure("Didn't catch a time") else {
            hour = time.first; minute = time.second
            ClockParse.days(text)?.let { days = it }
            while (ui.top is DictationScreen) ui.pop()
            ActionResult(message = fmtTime(hour, minute))
        }
    })

    private val title get() = if (existing == null) "New alarm" else "Edit alarm"

    override fun draw(g: Gfx, t: Double) = with(g) {
        frame(ui, title, "alarm", t)
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val hs = h12.toString()
        val ms = minute.toString().padStart(2, '0')
        val y = TOP + 4
        tx("$hs:$ms", CX, y, "xl")
        val ampm = if (hour < 12) "AM" else "PM"
        val ax = CX + tw("$hs:$ms", "xl") + 10
        tx(ampm, ax, y + lh("xl") - lh("m") - 4, "m")
        val uy = y + lh("xl") + 2
        when (field) {
            0 -> p.fr(CX, uy, tw(hs, "xl"), 4)
            1 -> p.fr(CX + tw("$hs:", "xl"), uy, tw(ms, "xl"), 4)
            2 -> p.fr(ax, uy, tw(ampm, "m"), 4)
        }
        val ry = uy + 14
        tx("Repeat", CX, ry + 2, "s", tone = SECONDARY)
        tx(fmtDays(days), CX + 100, ry, "m")
        if (field == 3) p.fr(CX + 100, ry + lh("m") + 1, tw(fmtDays(days), "m"), 3)
        if (label.isNotBlank()) tx(fit(label, CW - 100, "s"), CX + 100, ry + lh("m") + 8, "s", tone = SECONDARY)
        hintRow(if (ui.nowS() < deleteArmed) "X again to delete" else "◀▶ field  ▲▼ change  Y save" + if (existing != null) "  X delete" else "")
    }

    override fun key(k: String): Boolean {
        when (k) {
            "left" -> field = (field + 3) % 4
            "right" -> field = (field + 1) % 4
            "up", "down" -> {
                val d = if (k == "up") 1 else -1
                when (field) {
                    0 -> hour = (hour + d + 24) % 24
                    1 -> minute = if (d > 0) (minute - minute % 5 + 5) % 60 else if (minute % 5 != 0) minute - minute % 5 else (minute + 55) % 60
                    2 -> hour = (hour + 12) % 24
                    3 -> {
                        val i = REPEATS.indexOf(days)
                        days = REPEATS[((if (i < 0) 0 else i) + d + REPEATS.size) % REPEATS.size]
                    }
                }
            }
            "sel" -> {
                val r = ui.clock.alarmSet(hour, minute, days, label, alarmId)
                ui.flash(r.message ?: "Saved")
                if (r.ok) ui.pop()
            }
            "act" -> {
                if (existing == null) return true
                if (ui.nowS() < deleteArmed) { ui.flash(ui.clock.alarmDelete(existing.id).message ?: "Deleted"); ui.pop() }
                else { deleteArmed = ui.nowS() + 2; ui.requestRender() }
            }
            else -> return false
        }
        return true
    }

    override fun legend() = mapOf("left" to "Previous field", "right" to "Next field", "up" to "Change", "down" to "Change", "sel" to "Save") +
        (if (existing != null) mapOf("act" to "Delete (press twice)") else emptyMap()) + mapOf("talkH" to "Say the time")

    override fun drawText(tg: TextGrid) {
        TextChrome.header(tg, title)
        tg.put(0, 1, fmtTime(hour, minute))
        tg.put(0, 2, TextChrome.fit("Repeat ${fmtDays(days)}", tg.cols))
        tg.put(0, 3, "Set: " + listOf("hour", "minute", "AM/PM", "repeat")[field])
        TextChrome.footer(tg, hint = if (ui.nowS() < deleteArmed) "X again to delete" else "<> field ^v set Y save")
    }

    companion object {
        val REPEATS = listOf(emptySet(), (1..7).toSet(), (1..5).toSet(), setOf(6, 7))
    }
}

/**
 * The ringing card: takes over the display (waking a locked lens) when a timer or alarm goes off.
 * Y stops; B (or X) gives a timer +1 min and snoozes an alarm. Controls stay at full brightness.
 */
class ClockRinging(ui: Ui) : Screen(ui, "clock-ring", "clock-ring") {
    private val current get() = ui.clock.ringing.lastOrNull()
    override val keepAwake get() = ui.clock.holdsLens
    override val consumesShortcuts get() = true
    override val needsAnimation get() = true

    private fun title(r: Ringing) = if (r.kind == "timer") "TIMER DONE" else "ALARM"
    private fun hint(r: Ringing) = if (r.kind == "timer") "Y stop  B +1 min" else "Y stop  B snooze ${ui.clock.settings.snoozeMin} min"
    private fun big(r: Ringing) = if (r.kind == "timer") "+" + fmtClock(ui.nowMs() - r.sinceWallMs) else r.at.substringBefore(' ').ifBlank { ui.clock.fmtWall(r.sinceWallMs).substringBefore(' ') }
    private fun detail(r: Ringing): String = when {
        r.silenced -> "Silenced - since ${ui.clock.fmtWall(r.sinceWallMs)}"
        r.source == SOURCE_PHONE -> "Phone clock"
        r.kind == "timer" -> "Finished at ${ui.clock.fmtWall(r.sinceWallMs)}"
        else -> if (r.snoozes > 0) "Snoozed ${r.snoozes}x" else "Ringing"
    }

    override fun draw(g: Gfx, t: Double) = with(g) {
        val r = current ?: run { frameW(ui, "Clock"); tx("Nothing ringing", X0, TOP + 10, "m"); return@with }
        val n = ui.clock.ringing.size
        frameW(ui, title(r), if (n > 1) "1 of $n" else null)
        if (floor((t - t0) * 2).toInt() % 2 == 0) icon("alarm", X0, TOP + 6, 48)
        tx(fit(r.label.ifBlank { if (r.kind == "timer") "Timer" else "Alarm" }, XW - 64, "m"), X0 + 64, TOP + 6, "m")
        tx(fit(detail(r), XW - 64, "s"), X0 + 64, TOP + 6 + lh("m") + 4, "s", tone = SECONDARY)
        val text = big(r)
        tx(text, X0, TOP + 64, "xl")
        if (r.kind == "alarm") tx(r.at.substringAfter(' ', ""), X0 + tw(text, "xl") + 10, TOP + 64 + lh("xl") - lh("m") - 4, "m")
        hintRow(hint(r), safety = true)
    }

    override fun key(k: String): Boolean {
        val r = current ?: run { if (k == "back" || k == "sel") ui.pop(); return true }
        when (k) {
            "sel" -> ui.flash(ui.clock.dismiss(r.id).message ?: "Stopped")
            "back", "act" -> ui.flash(ui.clock.snooze(r.id).message ?: "Snoozed")
            else -> return true // The D-pad cannot dismiss by accident.
        }
        return true
    }

    override fun legend() = current?.let { mapOf("sel" to "Stop", "back" to if (it.kind == "timer") "+1 min" else "Snooze", "act" to if (it.kind == "timer") "+1 min" else "Snooze") } ?: emptyMap()

    override fun drawText(tg: TextGrid) {
        val r = current
        if (r == null) { TextChrome.header(tg, "Clock"); tg.put(0, 1, "Nothing ringing"); TextChrome.footer(tg, hint = "B back"); return }
        val n = ui.clock.ringing.size
        TextChrome.header(tg, title(r), if (n > 1) "1/$n" else "")
        tg.put(0, 1, TextChrome.fit(r.label.ifBlank { if (r.kind == "timer") "Timer" else "Alarm" }, tg.cols))
        tg.put(0, 2, TextChrome.fit(if (r.kind == "timer") big(r) + " over" else r.at, tg.cols))
        tg.put(0, 3, TextChrome.fit(detail(r), tg.cols))
        TextChrome.footer(tg, hint = if (r.kind == "timer") "Y stop B +1min" else "Y stop B snooze")
    }
}

/** Ring lengths, snooze and the Google Clock import/export switches. Y changes the focused row. */
class ClockSettingsScreen(ui: Ui) : ListView(ui, "clock-settings", items = { clockSettingRows(ui) }, chrome = { t ->
    frame(ui, "Clock settings", "settings", t)
}) {
    init {
        animateChrome = false
        textTitle = { "Clock settings" }
        textHint = { "Y change B back" }
    }
}

private fun <T> cycle(values: List<T>, current: T): T = values[(values.indexOf(current) + 1) % values.size]

private fun clockSettingRows(ui: Ui): List<Row> {
    val c = ui.clock
    val s = c.settings
    fun onOff(b: Boolean) = if (b) "On" else "Off"
    fun set(change: (ClockSettings) -> ClockSettings) { c.updateSettings(change); ui.requestRender() }
    return listOf(
        Row("Show phone clock", right = onOff(s.importPhone), open = { set { it.copy(importPhone = !it.importPhone) } }, selLabel = "Toggle"),
        Row("Timers to phone", right = onOff(s.mirrorTimers), open = { set { it.copy(mirrorTimers = !it.mirrorTimers) } }, selLabel = "Toggle"),
        Row("Alarms to phone", right = onOff(s.mirrorAlarms), open = { set { it.copy(mirrorAlarms = !it.mirrorAlarms) } }, selLabel = "Toggle"),
        Row("Timer rings for", right = if (s.timerRingSec < 60) "${s.timerRingSec} s" else "${s.timerRingSec / 60} min",
            open = { set { it.copy(timerRingSec = cycle(listOf(30, 60, 120, 300), it.timerRingSec)) } }, selLabel = "Change"),
        Row("Alarm rings for", right = "${s.alarmRingSec / 60} min", open = { set { it.copy(alarmRingSec = cycle(listOf(60, 180, 300, 600), it.alarmRingSec)) } }, selLabel = "Change"),
        Row("Snooze", right = "${s.snoozeMin} min", open = { set { it.copy(snoozeMin = cycle(listOf(5, 9, 10, 15), it.snoozeMin)) } }, selLabel = "Change"),
    )
}
