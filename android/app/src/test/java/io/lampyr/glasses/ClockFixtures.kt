package io.lampyr.glasses

import io.lampyr.glasses.clock.*
import io.lampyr.glasses.ui.Ui

/**
 * Shared Clock app fixture for the text and bitmap goldens (mirrored by `clockDemo()` in simulator/index.html):
 * frozen at 7:45 PM Wed Nov 11 with elapsed realtime 0, a "Pasta" timer with 4:32 left of 10:00, a stopped
 * stopwatch at 0:12.3 with two laps and a weekday 7:00 AM "Wake up" alarm.
 */
object ClockFixtures {
    fun demo(ui: Ui): Ui {
        val now = ui.nowMs()
        ui.clock.restore(ClockSnapshot(
            timers = listOf(TimerItem("t1", "Pasta", 600_000, true, endsAtElapsedMs = 272_000, endsAtWallMs = now + 272_000, createdAtWallMs = now - 328_000)),
            stopwatch = StopwatchState(accumulatedMs = 12_300, laps = listOf(4_200, 8_100)),
            alarms = listOf(AlarmItem("a2", 7, 0, (1..5).toSet(), "Wake up", armedFromMs = now)),
            nextId = 3,
        ))
        return ui
    }

    fun ringingTimer(ui: Ui): Ui {
        ui.clock.restore(ClockSnapshot(ringing = listOf(Ringing("timer", "t1", "Pasta", sinceWallMs = ui.nowMs(), sinceElapsedMs = 0, durationMs = 600_000))))
        return ui
    }

    fun ringingAlarm(ui: Ui): Ui {
        ui.clock.restore(ClockSnapshot(
            alarms = listOf(AlarmItem("a2", 7, 0, (1..5).toSet(), "Wake up", armedFromMs = ui.nowMs())),
            ringing = listOf(Ringing("alarm", "a2", "Wake up", sinceWallMs = ui.nowMs(), sinceElapsedMs = 0, at = "7:00 AM")),
        ))
        return ui
    }
}
