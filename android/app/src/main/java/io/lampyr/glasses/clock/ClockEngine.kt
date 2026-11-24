package io.lampyr.glasses.clock

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json

/** Where the engine keeps its JSON (SharedPreferences on the phone, memory in tests). */
interface ClockPersistence {
    fun load(): String?
    fun save(json: String)
}

class MemoryClockPersistence(var json: String? = null) : ClockPersistence {
    override fun load() = json
    override fun save(json: String) { this.json = json }
}

/** Exact wake-ups (AlarmManager on the phone). [sync] receives the complete wanted set every time. */
fun interface ClockScheduler {
    fun sync(wakeups: List<Wakeup>)
    companion object { val NONE = ClockScheduler {} }
}

/** Phone sound + vibration + heads-up notification. [update] receives everything ringing every time. */
fun interface ClockAlerter {
    fun update(ringing: List<Ringing>)
    companion object { val NONE = ClockAlerter {} }
}

/** Google Clock: export through AlarmClock intents, control through its notification actions. */
interface PhoneClock {
    fun startTimer(seconds: Int, label: String): Boolean = false
    fun setAlarm(hour: Int, minute: Int, days: Set<Int>, label: String): Boolean = false
    /** Sends Google Clock's notification action [action] ("pause", "resume", "add", "cancel", "dismiss", "snooze") for [key]. */
    fun act(key: String, action: String): Boolean = false
    companion object { val NONE = object : PhoneClock {} }
}

/**
 * Timers, stopwatch and alarms (requirement 7). Pure Kotlin: no Android imports, so it is unit-tested with fake
 * clocks. The service owns the only real instance; Ui sessions come and go around it.
 *
 * Every mutation persists synchronously, resyncs the wake-ups and the phone alert, then notifies listeners.
 * Call it from the main thread (same convention as Ui). [tick] and the AlarmManager wake-ups are idempotent:
 * whichever arrives first moves a due timer or alarm to [ClockSnapshot.ringing].
 */
class ClockEngine(
    private val wallMs: () -> Long,
    private val elapsedMs: () -> Long,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val bootCount: () -> Int = { 0 },
    private val persistence: ClockPersistence = MemoryClockPersistence(),
    var scheduler: ClockScheduler = ClockScheduler.NONE,
    var alerter: ClockAlerter = ClockAlerter.NONE,
    var phone: PhoneClock = PhoneClock.NONE,
) {
    var snapshot: ClockSnapshot = ClockSnapshot()
        private set
    var phoneState: PhoneClockState = PhoneClockState()
        private set
    private val listeners = mutableListOf<() -> Unit>()
    /** EXPERIMENTAL: the user's Google Clock alarms, read and switched through the accessibility service (cached list). */
    val phoneAlarms = PhoneAlarmBook(wallMs).also { it.onChange = { notifyListeners() } }

    init { load() }

    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    val settings get() = snapshot.settings

    fun updateSettings(change: (ClockSettings) -> ClockSettings): ClockResult {
        val next = change(snapshot.settings)
        commit(snapshot.copy(settings = next.copy(
            timerRingSec = next.timerRingSec.coerceIn(5, 600),
            alarmRingSec = next.alarmRingSec.coerceIn(30, 1800),
            snoozeMin = next.snoozeMin.coerceIn(1, 60),
            maxAutoSnoozes = next.maxAutoSnoozes.coerceIn(0, 10),
        )))
        if (!snapshot.settings.importPhone && phoneState != PhoneClockState()) importPhone(PhoneClockState())
        return ClockResult(message = "Saved")
    }

    // ---------------------------------------------------------------- timers

    /** Glasses timers then imported Google Clock timers, soonest first (paused ones last). */
    val timers: List<TimerItem>
        get() = (snapshot.timers + phoneState.timers).sortedWith(compareBy({ !it.running }, { remainingMs(it) }))

    fun remainingMs(t: TimerItem): Long = when {
        !t.running -> t.remainingMs
        t.source == SOURCE_PHONE -> t.endsAtWallMs - wallMs()
        else -> t.endsAtElapsedMs - elapsedMs()
    }.coerceAtLeast(0)

    /** Wall time a timer ends at (now + remaining). */
    fun endsAt(t: TimerItem): Long = wallMs() + remainingMs(t)

    fun timer(id: String?): TimerItem? = if (id == null) timers.firstOrNull { it.running } ?: timers.firstOrNull() else timers.firstOrNull { it.id == id }

    fun timerStart(durationMs: Long, label: String = ""): ClockResult {
        if (durationMs < 1_000 || durationMs > MAX_TIMER_MS) return ClockResult.fail("Timers run from 1 second to 24 hours")
        if (snapshot.timers.size >= MAX_TIMERS) return ClockResult.fail("Up to $MAX_TIMERS timers at once")
        val id = "t${snapshot.nextId}"
        val clean = label.trim().take(40)
        val mirrored = snapshot.settings.mirrorTimers && phone.startTimer(((durationMs + 999) / 1000).toInt(), clean)
        val t = TimerItem(id, clean, durationMs, true, elapsedMs() + durationMs, wallMs() + durationMs, createdAtWallMs = wallMs(), mirrored = mirrored)
        commit(snapshot.copy(timers = snapshot.timers + t, nextId = snapshot.nextId + 1, lastTimerMs = durationMs))
        return ClockResult(message = "Timer ${fmtDuration(durationMs)}" + if (mirrored) " (also in phone clock)" else "", id = id)
    }

    fun timerPause(id: String? = null): ClockResult {
        val t = (if (id == null) timers.firstOrNull { it.running } else timer(id)) ?: return ClockResult.fail("No running timer")
        if (!t.running) return ClockResult(message = "Already paused", id = t.id)
        if (t.source == SOURCE_PHONE) return phoneAct(t.id, "pause", "Paused")
        if (t.mirrored) return ClockResult.fail("This timer also runs in the phone clock, which cannot be paused from here")
        replaceTimer(t.copy(running = false, remainingMs = remainingMs(t)))
        return ClockResult(message = "Paused", id = t.id)
    }

    fun timerResume(id: String? = null): ClockResult {
        val t = (if (id == null) timers.firstOrNull { !it.running } else timer(id)) ?: return ClockResult.fail("No paused timer")
        if (t.running) return ClockResult(message = "Already running", id = t.id)
        if (t.source == SOURCE_PHONE) return phoneAct(t.id, "resume", "Resumed")
        replaceTimer(t.copy(running = true, endsAtElapsedMs = elapsedMs() + t.remainingMs, endsAtWallMs = wallMs() + t.remainingMs, remainingMs = 0))
        return ClockResult(message = "Resumed", id = t.id)
    }

    /** Adds (or with a negative delta removes) time; never below 1 s remaining. */
    fun timerAdd(id: String? = null, deltaMs: Long): ClockResult {
        val t = timer(id) ?: return ClockResult.fail("No timer")
        if (t.source == SOURCE_PHONE) {
            if (deltaMs != 60_000L) return ClockResult.fail("The phone clock only adds 1 minute at a time")
            return phoneAct(t.id, "add", "+1 min")
        }
        if (t.mirrored) return ClockResult.fail("This timer also runs in the phone clock, which cannot be changed from here")
        val left = remainingMs(t)
        val next = (left + deltaMs).coerceIn(1_000, MAX_TIMER_MS)
        val d = next - left
        replaceTimer(if (t.running) t.copy(durationMs = (t.durationMs + d).coerceAtLeast(1_000), endsAtElapsedMs = t.endsAtElapsedMs + d, endsAtWallMs = t.endsAtWallMs + d)
            else t.copy(durationMs = (t.durationMs + d).coerceAtLeast(1_000), remainingMs = next))
        return ClockResult(message = (if (d >= 0) "+" else "-") + fmtDuration(kotlin.math.abs(d)) + ", " + fmtDuration(next) + " left", id = t.id)
    }

    fun timerCancel(id: String? = null, all: Boolean = false): ClockResult {
        if (all) {
            val n = snapshot.timers.size
            phoneState.timers.forEach { phone.act(it.id.removePrefix("g:"), "cancel") }
            commit(snapshot.copy(timers = emptyList()))
            return ClockResult(message = if (n == 1) "Timer cancelled" else "$n timers cancelled")
        }
        val t = timer(id) ?: return ClockResult.fail("No timer")
        if (t.source == SOURCE_PHONE) return phoneAct(t.id, "cancel", "Cancelled")
        commit(snapshot.copy(timers = snapshot.timers.filterNot { it.id == t.id }))
        return ClockResult(message = if (t.mirrored) "Cancelled here; it still runs in the phone clock" else "Timer cancelled", id = t.id)
    }

    private fun replaceTimer(t: TimerItem) = commit(snapshot.copy(timers = snapshot.timers.map { if (it.id == t.id) t else it }))

    private fun phoneAct(id: String, action: String, done: String): ClockResult {
        val key = id.removePrefix("g:")
        return if (phone.act(key, action)) ClockResult(message = done, id = id)
        else ClockResult.fail("The phone clock offers no \"$action\" here; use the phone")
    }

    // ---------------------------------------------------------------- stopwatch

    fun stopwatchMs(): Long {
        val s = snapshot.stopwatch
        return if (s.running) s.accumulatedMs + (elapsedMs() - s.startedElapsedMs).coerceAtLeast(0) else s.accumulatedMs
    }

    fun stopwatchStart(): ClockResult {
        val s = snapshot.stopwatch
        if (s.running) return ClockResult(message = "Already running")
        commit(snapshot.copy(stopwatch = s.copy(running = true, startedElapsedMs = elapsedMs(), startedWallMs = wallMs())))
        return ClockResult(message = "Stopwatch started")
    }

    fun stopwatchStop(): ClockResult {
        val s = snapshot.stopwatch
        if (!s.running) return ClockResult(message = "Already stopped")
        val total = stopwatchMs()
        commit(snapshot.copy(stopwatch = s.copy(running = false, accumulatedMs = total)))
        return ClockResult(message = "Stopped at ${fmtClock(total, tenths = true)}")
    }

    fun stopwatchLap(): ClockResult {
        val s = snapshot.stopwatch
        if (!s.running) return ClockResult.fail("Start the stopwatch first")
        val total = stopwatchMs()
        val laps = (s.laps + total).takeLast(MAX_LAPS)
        commit(snapshot.copy(stopwatch = s.copy(laps = laps)))
        return ClockResult(message = "Lap ${s.laps.size + 1} ${fmtClock(total - (s.laps.lastOrNull() ?: 0), tenths = true)}")
    }

    fun stopwatchReset(): ClockResult {
        commit(snapshot.copy(stopwatch = StopwatchState()))
        return ClockResult(message = "Stopwatch reset")
    }

    // ---------------------------------------------------------------- alarms

    fun alarm(id: String) = snapshot.alarms.firstOrNull { it.id == id }

    fun alarmSet(hour: Int, minute: Int, days: Set<Int> = emptySet(), label: String = "", id: String? = null, enabled: Boolean = true): ClockResult {
        if (hour !in 0..23 || minute !in 0..59) return ClockResult.fail("Time must be between 0:00 and 23:59")
        if (days.any { it !in 1..7 }) return ClockResult.fail("Days are 1 (Monday) to 7 (Sunday)")
        val clean = label.trim().take(40)
        val existing = id?.let(::alarm)
        if (id != null && existing == null) return ClockResult.fail("No alarm $id")
        if (existing == null && snapshot.alarms.size >= MAX_ALARMS) return ClockResult.fail("Up to $MAX_ALARMS alarms")
        val newId = existing?.id ?: "a${snapshot.nextId}"
        val mirrored = (existing?.mirrored ?: false) || (enabled && snapshot.settings.mirrorAlarms && phone.setAlarm(hour, minute, days, clean))
        val a = AlarmItem(newId, hour, minute, days.toSortedSet(), clean, enabled, armedFromMs = wallMs(), mirrored = mirrored)
        commit(snapshot.copy(
            alarms = if (existing != null) snapshot.alarms.map { if (it.id == newId) a else it } else snapshot.alarms + a,
            nextId = if (existing != null) snapshot.nextId else snapshot.nextId + 1,
        ))
        val next = nextAlarmAt(a)
        return ClockResult(message = "Alarm ${fmtTime(hour, minute)}" + (next?.let { ", " + relativeDay(it) } ?: ""), id = newId)
    }

    fun alarmEnable(id: String, on: Boolean): ClockResult {
        val a = alarm(id) ?: return ClockResult.fail("No alarm $id")
        commit(snapshot.copy(alarms = snapshot.alarms.map {
            if (it.id == id) it.copy(enabled = on, armedFromMs = if (on && !it.enabled) wallMs() else it.armedFromMs, skipped = it.skipped && !(on && !it.enabled), snoozedUntilMs = if (on) it.snoozedUntilMs else 0, snoozes = if (on) it.snoozes else 0) else it
        }, ringing = if (on) snapshot.ringing else snapshot.ringing.filterNot { it.kind == "alarm" && it.id == id }))
        return ClockResult(message = "Alarm ${fmtTime(a.hour, a.minute)} ${if (on) "on" else "off"}", id = id)
    }

    fun alarmDelete(id: String): ClockResult {
        val a = alarm(id) ?: return ClockResult.fail("No alarm $id")
        commit(snapshot.copy(alarms = snapshot.alarms.filterNot { it.id == id }, ringing = snapshot.ringing.filterNot { it.kind == "alarm" && it.id == id }))
        return ClockResult(message = "Alarm ${fmtTime(a.hour, a.minute)} deleted" + if (a.mirrored) "; delete it in the phone clock too" else "", id = id)
    }

    /** Skips the next occurrence of an upcoming alarm (a once-alarm is simply turned off). */
    fun alarmSkipNext(id: String): ClockResult {
        val a = alarm(id) ?: return ClockResult.fail("No alarm $id")
        if (a.days.isEmpty()) return alarmEnable(id, false)
        val next = nextOccurrence(a, a.armedFromMs.coerceAtLeast(wallMs() - 1)) ?: return ClockResult.fail("Alarm is off")
        commit(snapshot.copy(alarms = snapshot.alarms.map { if (it.id == id) it.copy(armedFromMs = next, snoozedUntilMs = 0, snoozes = 0, skipped = true) else it }))
        return ClockResult(message = "Skipped ${relativeDay(next)}; next " + (nextAlarmAt(alarm(id)!!)?.let(::relativeDay) ?: "none"), id = id)
    }

    /** First occurrence strictly after [afterMs], in the current zone. DST gaps resolve forward, overlaps to the first. */
    fun nextOccurrence(a: AlarmItem, afterMs: Long = wallMs()): Long? {
        val z = zone()
        val start = Instant.ofEpochMilli(afterMs).atZone(z).toLocalDate()
        for (d in 0..8) {
            val date = start.plusDays(d.toLong())
            val at = ZonedDateTime.of(date, LocalTime.of(a.hour, a.minute), z).toInstant().toEpochMilli()
            if (at > afterMs && (a.days.isEmpty() || date.dayOfWeek.value in a.days)) return at
        }
        return null
    }

    /** When this alarm rings next (snooze first), or null when it is off. */
    fun nextAlarmAt(a: AlarmItem): Long? = when {
        a.snoozedUntilMs > 0 -> a.snoozedUntilMs
        !a.enabled -> null
        else -> nextOccurrence(a, a.armedFromMs.coerceAtLeast(0))
    }

    /** The soonest glasses alarm, with its time. */
    fun nextAlarm(): Pair<AlarmItem, Long>? = snapshot.alarms.mapNotNull { a -> nextAlarmAt(a)?.let { a to it } }.minByOrNull { it.second }

    // ---------------------------------------------------------------- ringing

    /** Glasses and Google Clock entries, oldest first. */
    val ringing: List<Ringing> get() = snapshot.ringing + phoneState.ringing

    fun dismiss(id: String? = null): ClockResult {
        val targets = if (id == null) ringing else ringing.filter { it.id == id }
        if (targets.isEmpty()) return ClockResult.fail("Nothing is ringing")
        var failed = false
        targets.filter { it.source == SOURCE_PHONE }.forEach { if (!phone.act(it.id.removePrefix("g:"), "dismiss")) failed = true }
        val ids = targets.map { it.kind + ":" + it.id }.toSet()
        commit(snapshot.copy(
            ringing = snapshot.ringing.filterNot { it.kind + ":" + it.id in ids },
            alarms = snapshot.alarms.map { a -> if ("alarm:" + a.id in ids) a.copy(snoozedUntilMs = 0, snoozes = 0) else a },
        ))
        if (phoneState.ringing.isNotEmpty()) phoneState = phoneState.copy(ringing = phoneState.ringing.filterNot { it.kind + ":" + it.id in ids }).also { notifyListeners() }
        return if (failed) ClockResult.fail("Stopped here; stop it on the phone too") else ClockResult(message = "Stopped", id = targets.first().id)
    }

    /** Alarms snooze ([minutes], default the setting); timers restart for one minute ("+1 min"). */
    fun snooze(id: String? = null, minutes: Int? = null): ClockResult {
        val r = (if (id == null) ringing.lastOrNull() else ringing.lastOrNull { it.id == id }) ?: return ClockResult.fail("Nothing is ringing")
        if (r.source == SOURCE_PHONE) {
            val ok = phone.act(r.id.removePrefix("g:"), if (r.kind == "timer") "add" else "snooze")
            if (!ok) return ClockResult.fail("The phone clock offers no ${if (r.kind == "timer") "+1 min" else "snooze"} here")
            phoneState = phoneState.copy(ringing = phoneState.ringing.filterNot { it === r })
            notifyListeners()
            return ClockResult(message = if (r.kind == "timer") "+1 min" else "Snoozed", id = r.id)
        }
        val rest = snapshot.ringing.filterNot { it.kind == r.kind && it.id == r.id }
        if (r.kind == "timer") {
            val t = TimerItem(r.id, r.label, 60_000, true, elapsedMs() + 60_000, wallMs() + 60_000, createdAtWallMs = wallMs())
            commit(snapshot.copy(ringing = rest, timers = snapshot.timers.filterNot { it.id == r.id } + t))
            return ClockResult(message = "+1 min", id = r.id)
        }
        val mins = (minutes ?: snapshot.settings.snoozeMin).coerceIn(1, 60)
        val until = wallMs() + mins * 60_000L
        commit(snapshot.copy(ringing = rest, alarms = snapshot.alarms.map { if (it.id == r.id) it.copy(snoozedUntilMs = until, snoozes = r.snoozes + 1) else it }))
        return ClockResult(message = "Snoozed $mins min, until ${fmtWall(until)}", id = r.id)
    }

    // ---------------------------------------------------------------- system hooks

    /**
     * Moves due timers and alarms to ringing and applies the ring limits. Called once a second while something
     * runs, from every AlarmManager wake-up, and on start. Idempotent.
     */
    fun tick() {
        val now = wallMs()
        val el = elapsedMs()
        val s = snapshot
        var ringing = s.ringing
        var changed = false
        val pendingAutoSnooze = mutableListOf<Ringing>()
        val timers = s.timers.filter { t ->
            if (!t.running || t.endsAtElapsedMs > el) return@filter true
            val late = el - t.endsAtElapsedMs
            ringing = ringing.filterNot { it.kind == "timer" && it.id == t.id } + Ringing("timer", t.id, t.label,
                // The ring limit counts from when it starts ringing here (a late notice still rings its full time).
                sinceWallMs = now - late, sinceElapsedMs = el, silenced = late > MISSED_MS,
                durationMs = t.durationMs, external = t.mirrored)
            changed = true
            false
        }
        val alarms = s.alarms.map { a ->
            var next = a
            if (a.snoozedUntilMs in 1..now) {
                ringing = ringing.filterNot { it.kind == "alarm" && it.id == a.id } + alarmRing(a, a.snoozedUntilMs, a.snoozes, now, el)
                next = next.copy(snoozedUntilMs = 0)
                changed = true
            }
            if (next.enabled) nextOccurrence(next, next.armedFromMs)?.let { first ->
                if (first <= now) {
                    // Several occurrences missed (phone off for days): ring for the latest one only.
                    var at = first
                    while (true) { val n = nextOccurrence(next, at) ?: break; if (n > now) break; at = n }
                    // A still-ringing entry keeps ringing; a silenced one left over from an earlier day (never
                    // dismissed) must not swallow this occurrence.
                    val prev = ringing.firstOrNull { it.kind == "alarm" && it.id == a.id }
                    if (prev == null || prev.silenced)
                        ringing = ringing.filterNot { it.kind == "alarm" && it.id == a.id } + alarmRing(next, at, 0, now, el)
                    // Re-arm from now: occurrences missed while the phone was off ring once, not once each.
                    next = next.copy(armedFromMs = maxOf(at, now), enabled = next.days.isNotEmpty(), snoozes = 0, skipped = false)
                    changed = true
                }
            }
            next
        }
        val limits = s.settings
        ringing = ringing.mapNotNull { r ->
            if (r.silenced || r.source != SOURCE_GLASSES) return@mapNotNull r
            val limit = if (r.kind == "timer") limits.timerRingSec * 1000L else limits.alarmRingSec * 1000L
            if (el - r.sinceElapsedMs < limit) return@mapNotNull r
            changed = true
            if (r.kind == "alarm" && r.snoozes < limits.maxAutoSnoozes && alarms.any { it.id == r.id }) {
                pendingAutoSnooze += r
                null
            } else r.copy(silenced = true)
        }
        if (!changed) return
        var alarmsAfter = alarms
        pendingAutoSnooze.forEach { r ->
            alarmsAfter = alarmsAfter.map { if (it.id == r.id) it.copy(snoozedUntilMs = now + limits.snoozeMin * 60_000L, snoozes = r.snoozes + 1) else it }
        }
        commit(s.copy(timers = timers, alarms = alarmsAfter, ringing = ringing))
    }

    /** How long an item of [kind] rings before it goes silent (timers) or snoozes itself (alarms). */
    fun ringLimitMs(kind: String): Long = if (kind == "timer") settings.timerRingSec * 1000L else settings.alarmRingSec * 1000L

    /**
     * Whether [r] should still sound and hold the lens lit. Glasses items are silenced by [tick] after the limit;
     * Google Clock items may stay in its notification indefinitely (its timers ring until stopped), so they count
     * only for the same limit, measured from when they started.
     */
    fun ringingLoud(r: Ringing): Boolean = !r.silenced && (r.source == SOURCE_GLASSES || wallMs() - r.sinceWallMs < ringLimitMs(r.kind))

    /** True while a ringing item should keep the glasses display awake. */
    val holdsLens: Boolean get() = ringing.any(::ringingLoud)

    private fun alarmRing(a: AlarmItem, at: Long, snoozes: Int, now: Long, el: Long) =
        Ringing("alarm", a.id, a.label, sinceWallMs = at, sinceElapsedMs = el, silenced = now - at > MISSED_MS,
            snoozes = snoozes, at = fmtTime(a.hour, a.minute), external = a.mirrored)

    /** Wall clock or time zone changed: rebase wall anchors on the (unchanged) elapsed ones. */
    fun onTimeChanged() {
        val now = wallMs(); val el = elapsedMs()
        val s = snapshot
        commit(s.copy(
            timers = s.timers.map { if (it.running) it.copy(endsAtWallMs = now + (it.endsAtElapsedMs - el)) else it },
            stopwatch = if (s.stopwatch.running) s.stopwatch.copy(startedWallMs = now - (el - s.stopwatch.startedElapsedMs)) else s.stopwatch,
            ringing = s.ringing.map { it.copy(sinceWallMs = now - (el - it.sinceElapsedMs)) },
            // A clock moved backwards may ring an occurrence again, as any alarm clock does. A skipped occurrence
            // stays skipped (its armedFromMs is in the future on purpose; time-zone changes and NTP fixes send this too).
            alarms = s.alarms.map { if (it.armedFromMs > now && !it.skipped) it.copy(armedFromMs = now) else it },
        ))
        tick()
    }

    /** Google Clock's current timers / ringing items / next alarm (from the notification listener). */
    fun importPhone(state: PhoneClockState) {
        val next = if (snapshot.settings.importPhone) state else PhoneClockState(nextAlarmWallMs = state.nextAlarmWallMs)
        if (next == phoneState) return
        phoneState = next
        notifyListeners()
    }

    /** True while something needs the 1 Hz ticker (a running glasses timer or stopwatch, or anything ringing). */
    val active: Boolean
        get() = snapshot.timers.any { it.running } || snapshot.stopwatch.running || ringing.isNotEmpty() || phoneState.timers.any { it.running }

    /** Home's weather row while something runs: "Timer 4:32", "Timer 4:32 +1", "Stopwatch 0:12". */
    fun homeSummary(): String? {
        ringing.lastOrNull()?.let { return if (it.kind == "timer") "Timer done" + (it.label.takeIf { l -> l.isNotBlank() }?.let { l -> " - $l" } ?: "") else "Alarm ringing" }
        val running = timers.filter { it.running }
        running.firstOrNull()?.let { t -> return "Timer ${fmtClock(remainingMs(t))}" + if (running.size > 1) " +${running.size - 1}" else "" }
        if (snapshot.stopwatch.running) return "Stopwatch ${fmtClock(stopwatchMs())}"
        return null
    }

    /** Every AlarmManager wake-up the current state needs. */
    fun wakeups(): List<Wakeup> {
        val s = snapshot
        val out = mutableListOf<Wakeup>()
        s.timers.filter { it.running }.forEach { out += Wakeup("timer:${it.id}", it.endsAtElapsedMs, wall = false) }
        s.alarms.forEach { a -> nextAlarmAt(a)?.let { out += Wakeup("alarm:${a.id}", it, wall = true, alarmClock = true) } }
        s.ringing.filter { !it.silenced }.forEach { r ->
            val limit = if (r.kind == "timer") s.settings.timerRingSec * 1000L else s.settings.alarmRingSec * 1000L
            out += Wakeup("ring:${r.kind}:${r.id}", r.sinceElapsedMs + limit, wall = false)
        }
        return out
    }

    // ---------------------------------------------------------------- formatting

    fun fmtWall(ms: Long): String = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(zone()).format(Instant.ofEpochMilli(ms))

    /** "today 7:00 AM", "tomorrow 7:00 AM", "Mon 7:00 AM". */
    fun relativeDay(ms: Long): String {
        val z = zone()
        val day = Instant.ofEpochMilli(ms).atZone(z).toLocalDate()
        val today = Instant.ofEpochMilli(wallMs()).atZone(z).toLocalDate()
        val prefix = when (day) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> DateTimeFormatter.ofPattern("EEE", Locale.US).format(day)
        }
        return "$prefix ${fmtWall(ms)}"
    }

    // ---------------------------------------------------------------- persistence

    private fun load() {
        val loaded = persistence.load()?.let { runCatching { json.decodeFromString(ClockSnapshot.serializer(), it) }.getOrNull() } ?: ClockSnapshot()
        val boot = bootCount()
        val now = wallMs(); val el = elapsedMs()
        snapshot = if (loaded.bootCount == boot) loaded else loaded.copy(
            bootCount = boot,
            // Elapsed time restarted with the boot: rebuild the elapsed anchors from the wall-clock ones.
            timers = loaded.timers.map { if (it.running) it.copy(endsAtElapsedMs = el + (it.endsAtWallMs - now)) else it },
            stopwatch = loaded.stopwatch.let { if (it.running) it.copy(startedElapsedMs = el - (now - it.startedWallMs)) else it },
            ringing = loaded.ringing.map { it.copy(sinceElapsedMs = el - (now - it.sinceWallMs)) },
        )
        sync(save = loaded.bootCount != boot)
        tick()
    }

    private fun commit(next: ClockSnapshot) {
        snapshot = next.copy(bootCount = bootCount())
        sync(save = true)
        notifyListeners()
    }

    private fun sync(save: Boolean) {
        if (save) runCatching { persistence.save(json.encodeToString(ClockSnapshot.serializer(), snapshot)) }
        runCatching { scheduler.sync(wakeups()) }
        runCatching { alerter.update(snapshot.ringing) }
    }

    /** Replaces the whole state (tests, goldens and the debug bridge's fixtures). */
    fun restore(state: ClockSnapshot) = commit(state)

    /** Re-sends the wake-ups and alert (after the service attached the real collaborators). */
    fun resync() { sync(save = false); tick() }

    private fun notifyListeners() = listeners.toList().forEach { runCatching { it() } }

    companion object {
        const val MAX_TIMERS = 10
        const val MAX_ALARMS = 20
        const val MAX_LAPS = 99
        const val MAX_TIMER_MS = 24 * 3_600_000L
        /** A timer or alarm noticed this late (phone off, app killed) shows silently instead of ringing. */
        const val MISSED_MS = 10 * 60_000L
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }

        /** True when persisted [json] has a running timer or stopwatch, an armed or snoozed alarm, or something ringing. */
        fun needsService(json: String?): Boolean {
            val s = json?.let { runCatching { this.json.decodeFromString(ClockSnapshot.serializer(), it) }.getOrNull() } ?: return false
            return s.timers.any { it.running } || s.stopwatch.running || s.ringing.isNotEmpty() || s.alarms.any { it.enabled || it.snoozedUntilMs > 0 }
        }

        /** In-memory engine with no Android collaborators: tests, goldens and the default Ui. */
        fun memory(wallMs: () -> Long, elapsedMs: () -> Long, zone: () -> ZoneId = { ZoneId.systemDefault() }) =
            ClockEngine(wallMs, elapsedMs, zone)
    }
}

/** "4:32", "1:04:09"; with [tenths] "0:12.3". */
fun fmtClock(ms: Long, tenths: Boolean = false): String {
    val total = ms.coerceAtLeast(0)
    val s = total / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val x = s % 60
    val base = if (h > 0) "$h:${m.toString().padStart(2, '0')}:${x.toString().padStart(2, '0')}" else "$m:${x.toString().padStart(2, '0')}"
    return if (tenths && h == 0L) "$base.${total % 1000 / 100}" else base
}

/** "10 min", "1 h 30 min", "45 s". */
fun fmtDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
    return listOfNotNull(if (h > 0) "$h h" else null, if (m > 0) "$m min" else null, if (x > 0 && h == 0L) "$x s" else null)
        .joinToString(" ").ifEmpty { "0 s" }
}

fun fmtTime(hour: Int, minute: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "$h:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

/** "Once", "Daily", "Weekdays", "Weekends" or "Mon Wed Fri". */
fun fmtDays(days: Set<Int>): String = when (days) {
    emptySet<Int>() -> "Once"
    (1..7).toSet() -> "Daily"
    (1..5).toSet() -> "Weekdays"
    setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
}
