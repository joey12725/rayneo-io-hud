package io.lampyr.glasses

import io.lampyr.glasses.audio.Dictation
import io.lampyr.glasses.audio.DictationEvent
import io.lampyr.glasses.clock.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.render.TextGrid
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClockUiTest {
    private class Timers : Scheduler {
        override fun after(delayMs: Long, block: () -> Unit): Scheduled = object : Scheduled { override fun cancel() {} }
    }
    private class NoAudio : Dictation {
        override fun start(target: String, onEvent: (DictationEvent) -> Unit) {}
        override fun stop() {}
        override fun cancel() {}
    }
    private class NoMedia : MediaControl { override fun toggle() {}; override fun direction(direction: String) {} }

    /** A Ui whose clocks move: [advance] moves wall and elapsed time together. */
    private class Fixture(val engine: ClockEngine? = null) : AutoCloseable {
        var wall = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
        var elapsedS = 100.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = engine ?: ClockEngine.memory({ wall }, { (elapsedS * 1000).toLong() }, { ZoneId.of("America/Chicago") })
        val ui = Ui(FakeDataStore(RuntimeEnvironment.getApplication(), { elapsedS }, { wall }, scope), scope = scope,
            nowS = { elapsedS }, nowMs = { wall }, zone = ZoneId.of("America/Chicago"), clock = clock)
        val input = InputEngine(ui, NoAudio(), NoMedia(), Timers())
        fun advance(ms: Long) { wall += ms; elapsedS += ms / 1000.0; clock.tick() }
        fun tap(key: String) { input.press(key); input.release(key) }
        fun text(view: View = ui.top) = TextGrid(26, 7, uppercase = false).also { view.drawText(it) }.lines()
        override fun close() { input.close(); ui.close() }
    }

    @Test fun ringingTimerTakesOverALockedDisplayAndRelocksWhenStopped() = Fixture().use { f ->
        f.ui.push(Apps(f.ui))
        f.ui.locked = true
        f.clock.timerStart(5_000, "Tea")
        f.advance(5_000)
        assertTrue(f.ui.top is ClockRinging)
        assertFalse(f.ui.locked)
        assertTrue(f.ui.keepAwake)
        assertTrue(f.ui.awake)
        assertEquals("TIMER DONE", f.text().first().trim())
        f.tap("y")
        assertTrue(f.clock.ringing.isEmpty())
        assertEquals("apps", f.ui.top.id)
        assertTrue(f.ui.locked)
    }

    @Test fun bGivesATimerOneMoreMinuteAndSnoozesAnAlarm() = Fixture().use { f ->
        val id = f.clock.timerStart(5_000).id!!
        f.advance(5_000)
        f.tap("b")
        assertTrue(f.clock.ringing.isEmpty())
        assertEquals(60_000, f.clock.remainingMs(f.clock.timer(id)!!))
        assertEquals("home", f.ui.top.id)
        f.clock.timerCancel(all = true)
        // Ringing ends after the alarm limit by snoozing; B snoozes too.
        f.clock.alarmSet(19, 46)
        f.advance(60_000)
        assertTrue(f.ui.top is ClockRinging)
        f.tap("b")
        assertTrue(f.clock.snapshot.alarms.single().snoozedUntilMs > 0)
        // The D-pad never stops an alarm by accident.
        f.advance(9 * 60_000)
        assertTrue(f.ui.top is ClockRinging)
        f.tap("up"); f.tap("left")
        assertEquals(1, f.clock.ringing.size)
        f.tap("x")
        assertTrue(f.clock.ringing.isEmpty())
    }

    @Test fun ringingWaitsForARingingPhoneCall() = Fixture().use { f ->
        val calls = f.ui.data.calls as io.lampyr.glasses.calls.FakeCalls
        calls.incoming()
        f.clock.timerStart(1_000)
        f.advance(1_000)
        assertEquals("phone-call", f.ui.top.kind)
        calls.remoteEnd(calls.snapshot.ringing!!.id)
        assertTrue(f.ui.top is ClockRinging)
    }

    @Test fun navigatingAwayKeepsTheTimerRingingWithoutReturningEverySecond() = Fixture().use { f ->
        f.clock.timerStart(1_000)
        f.advance(1_000)
        f.tap("b") // +1 min
        f.advance(60_000)
        assertTrue(f.ui.top is ClockRinging)
        f.ui.push(Apps(f.ui))
        f.advance(1_000)
        assertEquals("apps", f.ui.top.id)
        assertEquals("Timer done", f.ui.clockSummary())
        f.clock.dismiss()
        assertTrue(f.ui.stack.none { it is ClockRinging })
    }

    @Test fun engineOutlivesAUiSessionLikeReloadData() {
        val first = Fixture()
        val engine = first.clock
        engine.timerStart(600_000, "Pasta")
        first.close()
        Fixture(engine).use { f ->
            val screen = TimerScreen(f.ui)
            f.ui.push(screen)
            assertTrue(f.text(screen)[1].startsWith("Pasta"))
            engine.timerAdd(null, 60_000) // the closed Ui no longer listens
            assertTrue(f.text(screen)[2].startsWith("11:00 left"))
        }
    }

    @Test fun homeShowsTheRunningTimerOnTheWeatherRow() = Fixture().use { f ->
        f.clock.timerStart(600_000)
        f.advance(328_000)
        assertTrue(f.text(f.ui.top).joinToString("\n"), f.text(f.ui.top).any { "Timer 4:32" in it })
        f.clock.timerCancel(all = true)
        f.clock.stopwatchStart(); f.advance(12_000)
        assertTrue(f.text(f.ui.top).any { "Stopwatch 0:12" in it })
    }

    @Test fun timerScreenNewPagePresetsStartPauseAddAndTwoStepCancel() = Fixture().use { f ->
        val s = TimerScreen(f.ui).also { f.ui.push(it) }
        assertEquals("Set 10:00", f.text(s)[2].trim())
        s.key("up"); assertEquals("Set 15:00", f.text(s)[2].trim())
        s.key("down"); s.key("down"); assertEquals("Set 9:00", f.text(s)[2].trim())
        s.key("act"); assertEquals("Set 10:00", f.text(s)[2].trim())
        s.key("act"); s.key("sel")
        val t = f.clock.timers.single()
        assertEquals(900_000, t.durationMs)
        assertEquals("1/2", f.text(s)[0].takeLast(3))
        s.key("sel"); assertFalse(f.clock.timers.single().running)
        s.key("sel"); assertTrue(f.clock.timers.single().running)
        s.key("up"); assertEquals(960_000, f.clock.remainingMs(f.clock.timers.single()))
        s.key("act"); assertEquals(1, f.clock.timers.size)
        assertTrue(f.text(s).last().startsWith("X again to cancel"))
        f.elapsedS += 3; s.key("act"); assertEquals(1, f.clock.timers.size) // window expired: arms again
        s.key("act"); assertTrue(f.clock.timers.isEmpty())
        assertEquals("New timer", f.text(s)[1].trim())
    }

    @Test fun stopwatchStartLapStopAndConfirmedReset() = Fixture().use { f ->
        val s = StopwatchScreen(f.ui).also { f.ui.push(it) }
        s.key("sel"); f.advance(4_200)
        s.key("act"); f.advance(3_900)
        s.key("act"); s.key("sel")
        assertEquals(listOf(4_200L, 8_100L), f.clock.snapshot.stopwatch.laps)
        assertEquals("0:08.1", f.text(s)[1].trim())
        assertTrue(f.text(s)[2].startsWith("Lap 2"))
        s.key("act"); assertEquals(2, f.clock.snapshot.stopwatch.laps.size)
        s.key("act"); assertEquals(0L, f.clock.stopwatchMs())
        assertTrue(f.clock.snapshot.stopwatch.laps.isEmpty())
    }

    @Test fun alarmsCreateToggleEditAndDelete() = Fixture().use { f ->
        val list = AlarmsScreen(f.ui).also { f.ui.push(it) }
        list.cur = list.rows().indexOfFirst { it.label == "New alarm" }
        list.key("sel")
        val set = f.ui.top as AlarmSet
        set.key("up") // hour 8
        set.key("right"); set.key("up") // minute 05
        set.key("right"); set.key("right"); set.key("up") // repeat: Daily
        set.key("sel")
        val a = f.clock.snapshot.alarms.single()
        assertEquals(8 to 5, a.hour to a.minute)
        assertEquals((1..7).toSet(), a.days)
        assertSame(list, f.ui.top)
        list.cur = list.rows().indexOfFirst { it.label == "8:05 AM" }
        list.key("sel"); assertFalse(f.clock.alarm(a.id)!!.enabled)
        list.key("act")
        val edit = f.ui.top as AlarmSet
        edit.key("act"); edit.key("act")
        assertTrue(f.clock.snapshot.alarms.isEmpty())
        // The phone clock and Home Assistant alarm stay where they are; the Home row opens Home Assistant.
        list.cur = list.rows().indexOfFirst { it.label == "Home alarm" }
        list.key("sel"); assertEquals("ha", f.ui.top.id)
    }

    @Test fun phoneAlarmsSectionReadsAndTogglesGoogleClock() = Fixture().use { f ->
        val fake = io.lampyr.glasses.clock.FakeGoogleClock(mutableListOf(
            io.lampyr.glasses.clock.PhoneAlarm(6, 30, "Wake up", "Mon, Tue, Wed, Thu, Fri", true),
            io.lampyr.glasses.clock.PhoneAlarm(9, 0, "", "Not scheduled", false),
        ))
        f.clock.phoneAlarms.worker = Dispatchers.Unconfined
        f.clock.phoneAlarms.attach(fake, io.lampyr.glasses.clock.MemoryClockPersistence())
        val list = AlarmsScreen(f.ui).also { f.ui.push(it) }
        val rows = list.rows()
        val sep = rows.indexOfFirst { it.label == "PHONE ALARMS" }
        assertTrue(sep > rows.indexOfFirst { it.label == "Home alarm" })
        assertEquals("Experimental", rows[sep + 1].sub)
        list.cur = sep + 1
        list.key("sel")
        val after = list.rows()
        assertEquals("Refresh phone alarms", after[sep + 1].label)
        assertEquals("2", after[sep + 1].right)
        assertTrue(after[sep + 1].sub!!.startsWith("Experimental - read "))
        assertEquals(listOf("6:30 AM", "9:00 AM"), after.drop(sep + 2).map { it.label })
        assertEquals("Mon, Tue, Wed, Thu, Fri - Wake up", after[sep + 2].sub)
        list.cur = sep + 3
        assertEquals("Y on/off (phone)", list.textHint())
        list.key("sel")
        assertTrue(fake.alarms[1].enabled)
        assertEquals("On", list.rows()[sep + 3].right)
        // Glasses alarms are untouched.
        assertTrue(f.clock.snapshot.alarms.isEmpty())
    }

    @Test fun spokenRequestsAreParsedOnThePhone() = Fixture().use { f ->
        f.ui.push(ClockHub(f.ui))
        assertEquals("raw", f.ui.top.talk!!.target)
        assertTrue(clockVoice(f.ui, "pasta eight minutes").ok)
        assertEquals("Pasta", f.clock.timers.single().label)
        assertTrue(f.ui.top is TimerScreen)
        assertTrue(clockVoice(f.ui, "wake me up at 6:30 on weekdays").ok)
        assertEquals((1..5).toSet(), f.clock.snapshot.alarms.single().days)
        assertFalse(clockVoice(f.ui, "how are you").ok)
    }

    @Test fun hubRepeatsTheLastTimerAndTheAppIsInTheLauncher() = Fixture().use { f ->
        appEntries(f.ui).map { it.id }.let { ids -> assertEquals(ids.indexOf("status") + 1, ids.indexOf("clock")) } // launcher order: Clock follows Status
        assertTrue(f.ui.app("clock") is ClockHub)
        assertTrue(f.ui.openTarget("clock", null))
        val hub = f.ui.top as ClockHub
        hub.cur = 0
        hub.key("act")
        assertEquals(600_000, f.clock.timers.single().durationMs)
        assertTrue(f.text(hub)[1].contains("10:00"))
        hub.cur = 3; hub.key("sel")
        assertTrue(f.ui.top is ClockSettingsScreen)
        (f.ui.top as ClockSettingsScreen).key("sel")
        assertFalse(f.clock.settings.importPhone)
    }

    @Test fun aWallClockChangeDoesNotPushTheCardBackAfterNavigatingAway() = Fixture().use { f ->
        f.clock.timerStart(1_000)
        f.advance(1_000)
        assertTrue(f.ui.top is ClockRinging)
        f.ui.push(Apps(f.ui))
        f.wall -= 30_000
        f.clock.onTimeChanged()
        assertEquals("apps", f.ui.top.id)
    }

    @Test fun aGoogleClockItemStopsHoldingTheLensAfterTheRingLimit() = Fixture().use { f ->
        f.clock.importPhone(PhoneClockState(ringing = listOf(Ringing("timer", "g:k1", sinceWallMs = f.wall, sinceElapsedMs = 0, source = SOURCE_PHONE, external = true))))
        assertTrue(f.ui.top is ClockRinging)
        assertTrue(f.ui.keepAwake)
        f.advance(61_000)
        assertTrue(f.ui.top is ClockRinging)
        assertFalse(f.ui.keepAwake)
    }
}
