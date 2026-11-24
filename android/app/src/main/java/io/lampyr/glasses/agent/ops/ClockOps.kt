package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.clock.*
import kotlinx.serialization.json.*

/**
 * Clock app ops (requirement 7): timers, stopwatch and alarms on the service-owned ClockEngine. Every op runs on
 * the phone, so results are the real state (no gateway copy to go stale). Stop-type ops never confirm.
 */
object ClockOps : OpProvider {
    private fun AgentCall.done(r: ClockResult, data: JsonElement? = null): OpResult =
        if (r.ok) ok(r.message, data ?: r.id?.let { buildJsonObject { put("id", it) } }) else fail(Codes.UNAVAILABLE, r.message ?: "Failed")

    private fun AgentCall.durationMs(name: String): Long =
        ClockParse.duration(str(name)) ?: fail(Codes.INVALID_ARGS, "Could not read \"$name\"; use e.g. \"10m\", \"1h 30m\", \"90s\" or \"10 minutes\"")

    private fun timerJson(e: ClockEngine, t: TimerItem) = buildJsonObject {
        put("id", t.id)
        put("label", t.label)
        put("state", if (t.running) "running" else "paused")
        put("remainingSec", e.remainingMs(t) / 1000)
        put("durationSec", t.durationMs / 1000)
        if (t.running) put("endsAt", e.fmtWall(e.endsAt(t)))
        put("source", t.source)
    }

    private fun alarmJson(e: ClockEngine, a: AlarmItem) = buildJsonObject {
        put("id", a.id)
        put("time", fmtTime(a.hour, a.minute))
        put("days", fmtDays(a.days))
        put("label", a.label)
        put("enabled", a.enabled)
        e.nextAlarmAt(a)?.let { put("next", e.relativeDay(it)) }
        if (a.snoozedUntilMs > 0) put("snoozedUntil", e.fmtWall(a.snoozedUntilMs))
    }

    private fun ringJson(e: ClockEngine, r: Ringing) = buildJsonObject {
        put("kind", r.kind); put("id", r.id); put("label", r.label); put("since", e.fmtWall(r.sinceWallMs)); put("source", r.source)
        put("silenced", r.silenced)
    }

    private fun AgentCall.alarmId(): String {
        val wanted = str("id")
        val alarms = ui.clock.snapshot.alarms
        alarms.firstOrNull { it.id == wanted }?.let { return it.id }
        // Also accept a time ("7:00 am") or a label.
        val time = ClockParse.time(wanted)
        val matches = alarms.filter { a -> (time != null && a.hour == time.first && a.minute == time.second) || (a.label.isNotBlank() && a.label.equals(wanted, ignoreCase = true)) }
        return when (matches.size) {
            1 -> matches.single().id
            0 -> fail(Codes.FAILED, "No glasses alarm \"$wanted\"; use alarm_list (phone clock alarms are managed on the phone)")
            else -> fail(Codes.AMBIGUOUS, "Several alarms match \"$wanted\"", JsonArray(matches.map { alarmJson(ui.clock, it) }))
        }
    }

    private fun AgentCall.timerId(): String? = strOrNull("id")?.let { wanted ->
        val timers = ui.clock.timers
        timers.firstOrNull { it.id == wanted }?.id
            ?: timers.filter { it.label.equals(wanted, ignoreCase = true) }.let { m ->
                when (m.size) { 1 -> m.single().id; 0 -> fail(Codes.FAILED, "No timer \"$wanted\"; use timer_list"); else -> fail(Codes.AMBIGUOUS, "Several timers are called \"$wanted\"", JsonArray(m.map { timerJson(ui.clock, it) })) }
            }
    }

    private val TIMER_ID = Param.string("id", "Timer id or label from timer_list (default: the soonest)", maxLength = 100)

    override fun ops() = listOf(
        OpSpec("timer.start", "Start a timer", "Start a countdown timer on the user's glasses; it rings on the phone and glasses.",
            params = listOf(
                Param.string("duration", "Length, e.g. \"10m\", \"1h 30m\", \"90s\", \"10 minutes\"", required = true, maxLength = 60),
                Param.string("label", "Short name, e.g. \"Pasta\"", maxLength = 40),
            ),
            summary = { "${it.strOrNull("duration")} ${it.strOrNull("label").orEmpty()}".trim() },
        ) { done(ui.clock.timerStart(durationMs("duration"), strOrNull("label").orEmpty())) },
        OpSpec("timer.list", "List timers", "Running and paused timers (glasses and phone clock) and anything ringing.", risk = Risk.READ) {
            val e = ui.clock
            ok(data = buildJsonObject {
                put("timers", JsonArray(e.timers.take(25).map { timerJson(e, it) }))
                put("ringing", JsonArray(e.ringing.filter { it.kind == "timer" }.map { ringJson(e, it) }))
            })
        },
        OpSpec("timer.pause", "Pause a timer", "Pause a running timer.", params = listOf(TIMER_ID)) { done(ui.clock.timerPause(timerId())) },
        OpSpec("timer.resume", "Resume a timer", "Resume a paused timer.", params = listOf(TIMER_ID)) { done(ui.clock.timerResume(timerId())) },
        OpSpec("timer.cancel", "Cancel a timer", "Cancel a timer, or all timers with all=true.", confirm = Confirm.NEVER,
            params = listOf(TIMER_ID, Param.boolean("all", "Cancel every timer"))) { done(ui.clock.timerCancel(timerId(), bool("all"))) },
        OpSpec("timer.add_time", "Add time to a timer", "Add (or with a leading minus remove) time from a timer, e.g. \"5m\" or \"-1m\".",
            params = listOf(Param.string("duration", "Time to add, e.g. \"5m\", \"30s\", \"-1m\"", required = true, maxLength = 60), TIMER_ID),
        ) {
            val raw = str("duration").trim()
            val ms = durationMs("duration")
            done(ui.clock.timerAdd(timerId(), if (raw.startsWith("-")) -ms else ms))
        },
        OpSpec("stopwatch.start", "Start the stopwatch", "Start (or continue) the glasses stopwatch.") { done(ui.clock.stopwatchStart()) },
        OpSpec("stopwatch.stop", "Stop the stopwatch", "Stop the stopwatch, keeping its time and laps.", confirm = Confirm.NEVER) { done(ui.clock.stopwatchStop()) },
        OpSpec("stopwatch.lap", "Mark a lap", "Mark a lap on the running stopwatch.") { done(ui.clock.stopwatchLap()) },
        OpSpec("stopwatch.reset", "Reset the stopwatch", "Reset the stopwatch to 0 and clear its laps.") { done(ui.clock.stopwatchReset()) },
        OpSpec("stopwatch.state", "Read the stopwatch", "Stopwatch elapsed time, running state and laps.", risk = Risk.READ) {
            val e = ui.clock
            val s = e.snapshot.stopwatch
            ok(data = buildJsonObject {
                put("running", s.running)
                put("elapsed", fmtClock(e.stopwatchMs(), tenths = true))
                put("elapsedMs", e.stopwatchMs())
                put("laps", JsonArray(s.laps.takeLast(25).map { JsonPrimitive(fmtClock(it, tenths = true)) }))
            })
        },
        OpSpec("alarm.set", "Set an alarm", "Set (or with id, edit) a glasses alarm. It rings on the phone and glasses.",
            params = listOf(
                Param.string("time", "Local time, e.g. \"7:30 am\" or \"19:45\"", required = true, maxLength = 30),
                Param.string("days", "Repeat: \"once\" (default), \"daily\", \"weekdays\", \"weekends\" or days like \"mon wed fri\"", maxLength = 60),
                Param.string("label", "Short name", maxLength = 40),
                Param.string("id", "Alarm id to edit (from alarm_list)", maxLength = 20),
            ),
            summary = { "${it.strOrNull("time")} ${it.strOrNull("days").orEmpty()}".trim() },
        ) {
            val (h, m) = ClockParse.time(str("time")) ?: fail(Codes.INVALID_ARGS, "Could not read time \"${str("time")}\"; use e.g. \"7:30 am\"")
            val days = strOrNull("days")?.let { ClockParse.days(it) ?: fail(Codes.INVALID_ARGS, "Could not read days \"$it\"") }
            val id = strOrNull("id")
            val existing = id?.let { ui.clock.alarm(it) ?: fail(Codes.FAILED, "No alarm $it; use alarm_list") }
            done(ui.clock.alarmSet(h, m, days ?: existing?.days ?: emptySet(), strOrNull("label") ?: existing?.label.orEmpty(), id))
        },
        OpSpec("alarm.list", "List alarms", "Glasses alarms, the phone clock's next alarm and anything ringing.", risk = Risk.READ) {
            val e = ui.clock
            ok(data = buildJsonObject {
                put("alarms", JsonArray(e.snapshot.alarms.take(25).map { alarmJson(e, it) }))
                put("phoneNextAlarm", e.phoneState.nextAlarmWallMs?.let { JsonPrimitive(e.relativeDay(it)) } ?: JsonNull)
                put("homeAssistantAlarm", ui.data.ha.panel.alarm.time)
                put("ringing", JsonArray(e.ringing.filter { it.kind == "alarm" }.map { ringJson(e, it) }))
            })
        },
        OpSpec("alarm.delete", "Delete an alarm", "Delete a glasses alarm (id, time or label).",
            params = listOf(Param.string("id", "Alarm id, time or label (see alarm_list)", required = true, maxLength = 40)),
        ) { done(ui.clock.alarmDelete(alarmId())) },
        OpSpec("alarm.enable", "Turn an alarm on or off", "Turn a glasses alarm on or off.",
            params = listOf(Param.string("id", "Alarm id, time or label", required = true, maxLength = 40), Param.boolean("enabled", "true = on (default), false = off")),
        ) { done(ui.clock.alarmEnable(alarmId(), bool("enabled", true))) },
        OpSpec("alarm.dismiss", "Stop what is ringing", "Stop a ringing alarm or timer (default: everything ringing). For an alarm id that is not ringing, skips its next occurrence.",
            params = listOf(Param.string("id", "Ringing item or alarm id (optional)", maxLength = 100)), confirm = Confirm.NEVER,
        ) {
            val id = strOrNull("id")
            val e = ui.clock
            done(if (id != null && e.ringing.none { it.id == id } && e.alarm(id) != null) e.alarmSkipNext(id) else e.dismiss(id))
        },
        OpSpec("alarm.snooze", "Snooze", "Snooze the ringing alarm (a ringing timer gets +1 min).",
            params = listOf(Param.string("id", "Ringing item id (optional)", maxLength = 100), Param.integer("minutes", "Snooze length (default: the user's setting, 9)", min = 1, max = 60)),
            confirm = Confirm.NEVER,
        ) { done(ui.clock.snooze(strOrNull("id"), intOrNull("minutes"))) },
        // EXPERIMENTAL (hidden, glasses_run): the user's existing Google Clock alarms, driven through the accessibility service.
        OpSpec("alarm.phone_list", "List phone clock alarms",
            "EXPERIMENTAL. The user's Google Clock alarms on the phone (time, label, days, on/off). Cached; refresh=true re-reads them by briefly opening Google Clock (phone must be unlocked).",
            params = listOf(Param.boolean("refresh", "Re-read from Google Clock now (default false: last read list)")),
            risk = Risk.READ, tool = false, timeoutMs = 40_000,
        ) {
            val book = ui.clock.phoneAlarms
            val outcome = if (bool("refresh")) book.refresh().also { if (!it.ok) fail(Codes.UNAVAILABLE, it.message) } else null
            ok(outcome?.message ?: if (book.cache.readAtMs == 0L) "Not read yet; call with refresh=true" else null, phoneListJson(ui.clock))
        },
        OpSpec("alarm.phone_toggle", "Switch a phone clock alarm",
            "EXPERIMENTAL. Turn one of the user's existing Google Clock alarms on or off by time (and label when several share a time). Briefly opens Google Clock on the unlocked phone and verifies the switch.",
            params = listOf(
                Param.string("time", "Alarm time, e.g. \"6:30 am\"", required = true, maxLength = 30),
                Param.string("label", "Alarm label, when several alarms share the time", maxLength = 60),
                Param.boolean("enabled", "true = on, false = off (default: flip)"),
            ),
            tool = false, timeoutMs = 45_000,
            summary = { "${it.strOrNull("time")} ${it.strOrNull("label").orEmpty()}".trim() },
        ) {
            val (h, m) = ClockParse.time(str("time")) ?: fail(Codes.INVALID_ARGS, "Could not read time \"${str("time")}\"; use e.g. \"6:30 am\"")
            val label = strOrNull("label")
            val book = ui.clock.phoneAlarms
            var matches = PhoneAlarmMatch.find(book.alarms, h, m, label)
            if (matches.isEmpty()) {
                val read = book.refresh()
                if (!read.ok) fail(Codes.UNAVAILABLE, read.message)
                matches = PhoneAlarmMatch.find(book.alarms, h, m, label)
            }
            val target = when (matches.size) {
                1 -> matches.single()
                0 -> fail(Codes.FAILED, "No phone alarm at ${fmtTime(h, m)}${label?.let { " \"$it\"" }.orEmpty()}", phoneListJson(ui.clock))
                else -> fail(Codes.AMBIGUOUS, "Several phone alarms at ${fmtTime(h, m)}; give the label", JsonArray(matches.map(::phoneAlarmJson)))
            }
            val r = book.setEnabled(target, if (has("enabled")) bool("enabled") else !target.enabled)
            if (r.ok) ok(r.message, r.changed?.let(::phoneAlarmJson)) else fail(Codes.UNAVAILABLE, r.message)
        },
    )

    private fun phoneAlarmJson(a: PhoneAlarm) = buildJsonObject {
        put("time", a.time); put("label", a.label); put("days", a.days); put("enabled", a.enabled)
    }

    private fun phoneListJson(e: ClockEngine) = buildJsonObject {
        val book = e.phoneAlarms
        put("experimental", true)
        put("alarms", JsonArray(book.alarms.take(60).map(::phoneAlarmJson)))
        put("readAt", if (book.cache.readAtMs > 0) JsonPrimitive(e.fmtWall(book.cache.readAtMs)) else JsonNull)
        book.lastError?.let { put("lastError", it) }
    }
}
