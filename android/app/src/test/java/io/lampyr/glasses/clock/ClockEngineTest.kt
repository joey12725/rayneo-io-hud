package io.lampyr.glasses.clock

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ClockEngineTest {
    private val chicago = ZoneId.of("America/Chicago")

    /** Fake phone: wall clock, elapsed realtime and boot count move independently. */
    class Clocks(var wall: Long, var elapsed: Long = 1_000_000, var boot: Int = 1) {
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }

    class Recorder : ClockScheduler, ClockAlerter, PhoneClock {
        var wakeups: List<Wakeup> = emptyList()
        val alerts = mutableListOf<List<Ringing>>()
        val acted = mutableListOf<Pair<String, String>>()
        val started = mutableListOf<Int>()
        var allow = true
        override fun sync(wakeups: List<Wakeup>) { this.wakeups = wakeups }
        override fun update(ringing: List<Ringing>) { alerts += ringing }
        override fun act(key: String, action: String) = allow.also { acted += key to action }
        override fun startTimer(seconds: Int, label: String) = true.also { started += seconds }
        override fun setAlarm(hour: Int, minute: Int, days: Set<Int>, label: String) = true
        val audible get() = alerts.lastOrNull().orEmpty().filter { !it.silenced && !it.external }
    }

    private fun at(text: String) = LocalDateTime.parse(text).atZone(chicago).toInstant().toEpochMilli()

    private fun engine(c: Clocks, p: ClockPersistence = MemoryClockPersistence(), r: Recorder = Recorder()) =
        ClockEngine({ c.wall }, { c.elapsed }, { chicago }, { c.boot }, p, r, r, r)

    @Test fun timerCountsDownPausesResumesAndAddsTime() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val e = engine(c)
        val id = e.timerStart(600_000, "Pasta").id!!
        c.advance(328_000)
        assertEquals(272_000, e.remainingMs(e.timer(id)!!))
        assertEquals("Timer 4:32", e.homeSummary())
        assertTrue(e.timerPause(id).ok)
        c.advance(60_000)
        assertEquals(272_000, e.remainingMs(e.timer(id)!!))
        assertTrue(e.timerResume().ok)
        c.advance(2_000)
        assertEquals(270_000, e.remainingMs(e.timer(id)!!))
        assertTrue(e.timerAdd(id, 60_000).ok)
        assertEquals(330_000, e.remainingMs(e.timer(id)!!))
        assertEquals(660_000, e.timer(id)!!.durationMs)
        // Never below one second.
        e.timerAdd(id, -3_600_000)
        assertEquals(1_000, e.remainingMs(e.timer(id)!!))
        assertTrue(e.timerCancel(id).ok)
        assertTrue(e.timers.isEmpty())
        assertNull(e.homeSummary())
    }

    @Test fun timerLimits() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val e = engine(c)
        assertFalse(e.timerStart(0).ok)
        assertFalse(e.timerStart(999).ok)
        assertFalse(e.timerStart(ClockEngine.MAX_TIMER_MS + 1).ok)
        repeat(ClockEngine.MAX_TIMERS) { assertTrue(e.timerStart(60_000L * (it + 1)).ok) }
        assertFalse(e.timerStart(60_000).ok)
        assertEquals("Timer 1:00 +9", e.homeSummary())
        assertTrue(e.timerCancel(all = true).ok)
        assertFalse(e.timerPause().ok)
    }

    @Test fun expiryRingsOnceThroughTickOrWakeupAndGoesSilentAfterTheLimit() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val r = Recorder()
        val e = engine(c, r = r)
        val id = e.timerStart(10_000, "Tea").id!!
        assertEquals(listOf(Wakeup("timer:$id", c.elapsed + 10_000, wall = false)), r.wakeups)
        c.advance(9_999); e.tick()
        assertTrue(e.ringing.isEmpty())
        c.advance(1); e.tick(); e.tick()
        assertEquals(1, e.ringing.size)
        assertEquals("Tea", e.ringing.single().label)
        assertEquals(1, r.audible.size)
        assertTrue(e.timers.isEmpty())
        assertTrue(r.wakeups.any { it.key == "ring:timer:$id" && it.atMs == c.elapsed + 60_000 })
        c.advance(59_000); e.tick()
        assertFalse(e.ringing.single().silenced)
        c.advance(1_000); e.tick()
        assertTrue(e.ringing.single().silenced)
        assertTrue(r.audible.isEmpty())
        // The card stays until it is dismissed.
        assertTrue(e.dismiss().ok)
        assertTrue(e.ringing.isEmpty())
        assertFalse(e.dismiss().ok)
    }

    @Test fun snoozingATimerRestartsItForOneMinute() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val e = engine(c)
        val id = e.timerStart(5_000, "Eggs").id!!
        c.advance(5_000); e.tick()
        assertEquals("+1 min", e.snooze().message)
        assertTrue(e.ringing.isEmpty())
        assertEquals(60_000, e.remainingMs(e.timer(id)!!))
        assertEquals("Eggs", e.timer(id)!!.label)
    }

    @Test fun persistenceSameBootKeepsElapsedAnchorsWhenTheWallClockMovedBack() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val p = MemoryClockPersistence()
        val e = engine(c, p)
        val id = e.timerStart(600_000).id!!
        e.stopwatchStart()
        c.advance(100_000)
        c.wall -= 3_600_000 // wall clock set back an hour while the process was dead
        val again = engine(c, p)
        assertEquals(500_000, again.remainingMs(again.timer(id)!!))
        assertEquals(100_000, again.stopwatchMs())
    }

    @Test fun persistenceAfterRebootUsesWallClock() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val p = MemoryClockPersistence()
        val e = engine(c, p)
        val id = e.timerStart(600_000).id!!
        e.stopwatchStart()
        c.wall += 120_000; c.elapsed = 5_000; c.boot = 2 // rebooted: elapsed restarted
        val again = engine(c, p)
        assertEquals(480_000, again.remainingMs(again.timer(id)!!))
        assertEquals(120_000, again.stopwatchMs())
        assertEquals(2, again.snapshot.bootCount)
        // Saved with the new boot's anchors.
        val third = engine(c, p)
        assertEquals(480_000, third.remainingMs(third.timer(id)!!))
    }

    @Test fun expiryWhileDeadRingsWhenRecentAndShowsSilentlyWhenLate() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val p = MemoryClockPersistence()
        engine(c, p).timerStart(60_000, "Soon")
        c.advance(60_000 + 5 * 60_000)
        val recent = engine(c, p)
        assertFalse(recent.ringing.single().silenced)
        assertEquals(c.wall - 5 * 60_000, recent.ringing.single().sinceWallMs)

        val c2 = Clocks(at("2026-11-11T19:45:00"))
        val p2 = MemoryClockPersistence()
        engine(c2, p2).timerStart(60_000, "Late")
        c2.advance(60_000 + 11 * 60_000)
        val late = engine(c2, p2)
        assertTrue(late.ringing.single().silenced)
    }

    @Test fun wallClockChangeRewritesWallAnchorsOnly() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val r = Recorder()
        val e = engine(c, r = r)
        val id = e.timerStart(600_000).id!!
        val wake = r.wakeups.single()
        c.wall += 7_200_000
        e.onTimeChanged()
        assertEquals(c.wall + 600_000, e.timer(id)!!.endsAtWallMs)
        assertEquals(wake, r.wakeups.single())
        assertEquals(600_000, e.remainingMs(e.timer(id)!!))
    }

    @Test fun stopwatchAccumulatesLapsAndResets() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val p = MemoryClockPersistence()
        val e = engine(c, p)
        assertFalse(e.stopwatchLap().ok)
        e.stopwatchStart(); c.advance(4_200)
        assertEquals("Lap 1 0:04.2", e.stopwatchLap().message)
        c.advance(3_900)
        assertEquals("Lap 2 0:03.9", e.stopwatchLap().message)
        e.stopwatchStop(); c.advance(10_000)
        assertEquals(8_100, e.stopwatchMs())
        e.stopwatchStart(); c.advance(4_200)
        assertEquals(12_300, e.stopwatchMs())
        assertEquals("Stopwatch 0:12", e.homeSummary())
        assertEquals(listOf(4_200L, 8_100L), engine(c, p).snapshot.stopwatch.laps)
        e.stopwatchReset()
        assertEquals(0, e.stopwatchMs())
        assertTrue(e.snapshot.stopwatch.laps.isEmpty())
    }

    @Test fun nextOccurrenceOnceDailyWeekdaysAndAcrossMidnight() {
        val c = Clocks(at("2026-11-11T19:45:00")) // Wednesday
        val e = engine(c)
        val once = AlarmItem("a", 7, 0)
        assertEquals(at("2026-11-12T07:00:00"), e.nextOccurrence(once))
        assertEquals(at("2026-11-11T20:00:00"), e.nextOccurrence(AlarmItem("b", 20, 0)))
        assertEquals(at("2026-11-12T00:05:00"), e.nextOccurrence(AlarmItem("c", 0, 5, (1..7).toSet())))
        // Friday evening: weekdays skip to Monday, weekends to Saturday.
        val friday = at("2026-11-13T19:00:00")
        assertEquals(at("2026-11-16T07:00:00"), e.nextOccurrence(AlarmItem("d", 7, 0, (1..5).toSet()), friday))
        assertEquals(at("2026-11-14T07:00:00"), e.nextOccurrence(AlarmItem("e", 7, 0, setOf(6, 7)), friday))
        // Exactly at the time means the next one, not this one.
        assertEquals(at("2026-11-13T07:00:00"), e.nextOccurrence(AlarmItem("f", 7, 0, (1..7).toSet()), at("2026-11-12T07:00:00")))
    }

    @Test fun nextOccurrenceAcrossDstTransitions() {
        val c = Clocks(at("2026-10-31T12:00:00"))
        val e = engine(c)
        // Fall back 2026-11-01: 1:30 happens twice; the alarm rings at the first one (CDT, UTC-5).
        val fall = e.nextOccurrence(AlarmItem("a", 1, 30, (1..7).toSet()), at("2026-10-31T12:00:00"))!!
        assertEquals(java.time.Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), fall)
        // A daily 7:00 stays 7:00 local on both sides (25-hour day).
        val before = e.nextOccurrence(AlarmItem("b", 7, 0, (1..7).toSet()), at("2026-10-30T12:00:00"))!!
        val after = e.nextOccurrence(AlarmItem("b", 7, 0, (1..7).toSet()), before)!!
        assertEquals(25 * 3_600_000L, after - before)
        // Spring forward 2027-03-14: 2:30 does not exist; it rings at 3:30 CDT.
        val spring = e.nextOccurrence(AlarmItem("c", 2, 30, (1..7).toSet()), at("2027-03-13T12:00:00"))!!
        assertEquals(java.time.Instant.parse("2027-03-14T08:30:00Z").toEpochMilli(), spring)
        // The 23-hour day: 7:00 Mar 13 -> 7:00 Mar 14.
        val mar13 = e.nextOccurrence(AlarmItem("d", 7, 0, (1..7).toSet()), at("2027-03-12T12:00:00"))!!
        assertEquals(23 * 3_600_000L, e.nextOccurrence(AlarmItem("d", 7, 0, (1..7).toSet()), mar13)!! - mar13)
    }

    @Test fun onceAlarmRingsDisablesItselfSnoozesAndAutoSnoozes() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val r = Recorder()
        val e = engine(c, r = r)
        val id = e.alarmSet(20, 0, label = "Call").id!!
        assertEquals(listOf(Wakeup("alarm:$id", at("2026-11-11T20:00:00"), wall = true, alarmClock = true)), r.wakeups)
        c.advance(15 * 60_000); e.tick()
        val ring = e.ringing.single()
        assertEquals("alarm", ring.kind)
        assertEquals("8:00 PM", ring.at)
        assertFalse(e.alarm(id)!!.enabled)
        assertTrue(e.snooze().message!!.startsWith("Snoozed 9 min"))
        assertTrue(e.ringing.isEmpty())
        assertEquals(c.wall + 9 * 60_000, e.alarm(id)!!.snoozedUntilMs)
        c.advance(9 * 60_000); e.tick()
        assertEquals(1, e.ringing.single().snoozes)
        // Unanswered for 5 minutes: snoozes by itself, up to three times, then goes silent.
        repeat(2) {
            c.advance(5 * 60_000); e.tick()
            assertTrue(e.ringing.isEmpty())
            c.advance(9 * 60_000); e.tick()
        }
        assertEquals(3, e.ringing.single().snoozes)
        c.advance(5 * 60_000); e.tick()
        assertTrue(e.ringing.single().silenced)
        e.dismiss()
        assertEquals(0, e.alarm(id)!!.snoozedUntilMs)
        assertTrue(r.wakeups.isEmpty())
    }

    @Test fun repeatingAlarmRearmsSkipsNextAndKeepsOneWakeupPerAlarm() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val r = Recorder()
        val e = engine(c, r = r)
        val a = e.alarmSet(7, 0, (1..5).toSet(), "Wake up").id!!
        val b = e.alarmSet(9, 30, setOf(6, 7)).id!!
        e.alarmSet(10, 0, enabled = false)
        assertEquals(2, r.wakeups.size)
        assertEquals(at("2026-11-12T07:00:00"), r.wakeups.first { it.key == "alarm:$a" }.atMs)
        assertTrue(e.alarmSkipNext(a).ok)
        assertEquals(at("2026-11-13T07:00:00"), e.nextAlarmAt(e.alarm(a)!!))
        c.wall = at("2026-11-13T07:00:00"); c.elapsed += 1; e.tick()
        assertEquals(a, e.ringing.single().id)
        assertTrue(e.alarm(a)!!.enabled)
        e.dismiss(a)
        assertEquals(at("2026-11-16T07:00:00"), e.nextAlarmAt(e.alarm(a)!!))
        assertEquals(b, e.nextAlarm()!!.first.id)
        assertTrue(e.alarmEnable(b, false).ok)
        assertTrue(e.alarmDelete(a).ok)
        assertTrue(r.wakeups.isEmpty())
        assertFalse(e.alarmDelete(a).ok)
        assertFalse(e.alarmSet(24, 0).ok)
    }

    @Test fun missedAlarmsRingOnceNotOncePerMissedDay() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val p = MemoryClockPersistence()
        engine(c, p).alarmSet(7, 0, (1..7).toSet())
        c.wall = at("2026-11-15T07:03:00"); c.elapsed = 10; c.boot = 2
        val e = engine(c, p)
        assertEquals(1, e.ringing.size)
        assertFalse(e.ringing.single().silenced) // three minutes late: still rings
        e.dismiss(); e.tick()
        assertTrue(e.ringing.isEmpty())
        assertEquals(at("2026-11-16T07:00:00"), e.nextAlarm()!!.second)
    }

    @Test fun phoneItemsAreListedAndControlledThroughNotificationActions() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val r = Recorder()
        val e = engine(c, r = r)
        val t = TimerItem("g:k1", "Bread", 0, running = true, endsAtWallMs = c.wall + 90_000, source = SOURCE_PHONE, actions = setOf("pause"))
        e.importPhone(PhoneClockState(timers = listOf(t)))
        assertEquals(90_000, e.remainingMs(e.timers.single()))
        assertTrue(e.timerPause("g:k1").ok)
        assertEquals("k1" to "pause", r.acted.last())
        r.allow = false
        assertFalse(e.timerCancel("g:k1").ok)
        e.importPhone(PhoneClockState(ringing = listOf(Ringing("alarm", "g:k2", sinceWallMs = c.wall, sinceElapsedMs = c.elapsed, source = SOURCE_PHONE, external = true))))
        r.allow = true
        assertTrue(e.snooze().ok)
        assertEquals("k2" to "snooze", r.acted.last())
        assertTrue(e.ringing.isEmpty())
        // Our phone sound never plays for Google Clock items.
        assertTrue(r.audible.isEmpty())
        e.updateSettings { it.copy(importPhone = false) }
        e.importPhone(PhoneClockState(timers = listOf(t)))
        assertTrue(e.timers.isEmpty())
    }

    @Test fun mirroredTimersStartInGoogleClockAndStaySilentHere() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val r = Recorder()
        val e = engine(c, r = r)
        e.updateSettings { it.copy(mirrorTimers = true) }
        val id = e.timerStart(65_500).id!!
        assertEquals(listOf(66), r.started)
        assertFalse(e.timerPause(id).ok)
        c.advance(66_000); e.tick()
        assertTrue(e.ringing.single().external)
        assertTrue(r.audible.isEmpty())
    }

    @Test fun settingsPersistAndAreClamped() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val p = MemoryClockPersistence()
        engine(c, p).updateSettings { it.copy(snoozeMin = 500, timerRingSec = 30) }
        val s = engine(c, p).settings
        assertEquals(60, s.snoozeMin)
        assertEquals(30, s.timerRingSec)
        assertTrue(ClockEngine.needsService(p.json).not())
        engine(c, p).alarmSet(7, 0)
        assertTrue(ClockEngine.needsService(p.json))
        assertFalse(ClockEngine.needsService("not json"))
    }

    @Test fun formatting() {
        assertEquals("4:32", fmtClock(272_000))
        assertEquals("1:04:09", fmtClock(3_849_000))
        assertEquals("0:12.3", fmtClock(12_345, tenths = true))
        assertEquals("10 min", fmtDuration(600_000))
        assertEquals("1 h 30 min", fmtDuration(5_400_000))
        assertEquals("45 s", fmtDuration(45_000))
        assertEquals("12:00 AM", fmtTime(0, 0))
        assertEquals("12:30 PM", fmtTime(12, 30))
        assertEquals("Weekdays", fmtDays((1..5).toSet()))
        assertEquals("Mon Wed", fmtDays(setOf(1, 3)))
    }

    @Test fun skipNextSurvivesTimeZoneAndClockChanges() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val e = engine(c)
        val a = e.alarmSet(7, 0, (1..5).toSet()).id!!
        assertTrue(e.alarmSkipNext(a).ok)
        assertEquals(at("2026-11-13T07:00:00"), e.nextAlarmAt(e.alarm(a)!!))
        // TIME_CHANGED / TIMEZONE_CHANGED (NTP fixes, travel) must not bring the skipped occurrence back.
        c.wall -= 60_000; e.onTimeChanged()
        assertEquals(at("2026-11-13T07:00:00"), e.nextAlarmAt(e.alarm(a)!!))
        c.wall = at("2026-11-12T07:00:00"); c.elapsed += 1; e.tick()
        assertTrue(e.ringing.isEmpty())
        // After the next real occurrence rings the flag is cleared and a backward change re-arms from now again.
        c.wall = at("2026-11-13T07:00:00"); c.elapsed += 1; e.tick()
        assertEquals(a, e.ringing.single().id)
        assertFalse(e.alarm(a)!!.skipped)
    }

    @Test fun phoneClockRingingHoldsTheLensOnlyForTheRingLimit() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val e = engine(c)
        e.importPhone(PhoneClockState(ringing = listOf(Ringing("timer", "g:k1", sinceWallMs = c.wall, sinceElapsedMs = c.elapsed, source = SOURCE_PHONE, external = true))))
        assertTrue(e.holdsLens)
        c.advance(59_000)
        assertTrue(e.holdsLens)
        // Google Clock timers can ring until stopped: the glasses card stays, the lens may sleep.
        c.advance(1_000)
        assertFalse(e.holdsLens)
        assertEquals(1, e.ringing.size)
    }

    @Test fun aSilencedRingFromYesterdayDoesNotSwallowTodaysAlarm() {
        val c = Clocks(at("2026-11-11T19:45:00"))
        val e = engine(c)
        e.updateSettings { it.copy(maxAutoSnoozes = 0) }
        val a = e.alarmSet(7, 0, (1..7).toSet()).id!!
        c.wall = at("2026-11-12T07:00:00"); c.elapsed += 1; e.tick()
        c.advance(301_000); e.tick()
        assertTrue(e.ringing.single().silenced)
        // Never dismissed; the next morning rings again.
        c.wall = at("2026-11-13T07:00:00"); c.elapsed += 1; e.tick()
        val r = e.ringing.single()
        assertEquals(a, r.id)
        assertFalse(r.silenced)
        assertEquals(at("2026-11-13T07:00:00"), r.sinceWallMs)
    }
}
