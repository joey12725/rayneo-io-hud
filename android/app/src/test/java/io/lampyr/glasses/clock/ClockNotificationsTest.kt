package io.lampyr.glasses.clock

import io.lampyr.glasses.clock.ClockNotifications.Item
import io.lampyr.glasses.clock.ClockNotifications.Notice
import org.junit.Assert.*
import org.junit.Test

/**
 * Google Clock notification shapes. The channel ids are the ones Google Clock 9.3 registers on the user's Pixel
 * (dumpsys notification); action titles follow AOSP DeskClock. Capture real fixtures per android/GLASSES.md.
 */
class ClockNotificationsTest {
    private val now = 1_800_000_000_000L
    private val el = 5_000_000L

    @Test fun runningPausedAndFiringTimers() {
        val running = ClockNotifications.classify(Notice("k1", "Timers v2", title = "Pasta", text = "Timer", whenMs = now + 272_000,
            chronometer = true, countDown = true, actions = listOf("Pause", "+1:00", "Reset")), now, el) as Item.Timer
        assertTrue(running.timer.running)
        assertEquals("g:k1", running.timer.id)
        assertEquals("Pasta", running.timer.label)
        assertEquals(now + 272_000, running.timer.endsAtWallMs)
        assertEquals(setOf("pause", "add", "cancel"), running.timer.actions)

        val paused = ClockNotifications.classify(Notice("k1", "Timers v2", title = "Timer", text = "4:32 remaining",
            actions = listOf("Resume", "Reset")), now, el) as Item.Timer
        assertFalse(paused.timer.running)
        assertEquals(272_000, paused.timer.remainingMs)
        assertEquals("", paused.timer.label)

        val firing = ClockNotifications.classify(Notice("k1", "Firing", category = "alarm", title = "Time's up", text = "Pasta",
            whenMs = now - 5_000, actions = listOf("Stop", "+1:00")), now, el) as Item.Firing
        assertEquals("timer", firing.ringing.kind)
        assertEquals(now - 5_000, firing.ringing.sinceWallMs)
        assertEquals(el - 5_000, firing.ringing.sinceElapsedMs)
        assertTrue(firing.ringing.external)
        assertEquals(setOf("add", "dismiss", "cancel"), firing.ringing.actions)
    }

    @Test fun firingUpcomingAndSnoozedAlarms() {
        val firing = ClockNotifications.classify(Notice("k2", "Firing", category = "alarm", title = "Alarm", text = "7:00 AM",
            actions = listOf("Snooze", "Stop")), now, el) as Item.Firing
        assertEquals("alarm", firing.ringing.kind)
        assertEquals(SOURCE_PHONE, firing.ringing.source)
        val upcoming = ClockNotifications.classify(Notice("k3", "Upcoming Alarms v2", title = "Upcoming alarm", text = "Thu 7:00 AM",
            whenMs = now + 3_600_000, actions = listOf("Dismiss now")), now, el) as Item.Upcoming
        assertEquals("upcoming", upcoming.state)
        assertEquals(setOf("dismiss"), upcoming.actions)
        val snoozed = ClockNotifications.classify(Notice("k4", "Snoozed Alarms v2", title = "Snoozed", whenMs = now + 540_000), now, el) as Item.Upcoming
        assertEquals("snoozed", snoozed.state)
    }

    @Test fun ignoresOtherChannelsAndGroupSummaries() {
        assertNull(ClockNotifications.classify(Notice("s", "Stopwatch v2", chronometer = true), now, el))
        assertNull(ClockNotifications.classify(Notice("m", "Missed Alarms v2"), now, el))
        assertNull(ClockNotifications.classify(Notice("b", "Bedtime"), now, el))
        assertNull(ClockNotifications.classify(Notice("g", "Timers v2", groupSummary = true), now, el))
        assertNull(ClockNotifications.classify(Notice("w", "Workflows v2"), now, el))
    }

    @Test fun actionsMatchByTitleAndMissingActionsReturnNull() {
        val titles = listOf("Pause", "Add 1 minute", "Delete")
        assertEquals(0, ClockNotifications.actionIndex(titles, "pause"))
        assertEquals(1, ClockNotifications.actionIndex(titles, "add"))
        assertEquals(2, ClockNotifications.actionIndex(titles, "cancel"))
        assertNull(ClockNotifications.actionIndex(titles, "snooze"))
        assertNull(ClockNotifications.actionIndex(emptyList(), "dismiss"))
        assertNull(ClockNotifications.actionIndex(titles, "unknown"))
    }

    @Test fun foldsIntoPhoneState() {
        val state = ClockNotifications.state(listOf(
            Notice("k1", "Timers v2", title = "Bread", whenMs = now + 60_000, chronometer = true, countDown = true, actions = listOf("Pause")),
            Notice("k3", "Upcoming Alarms v2", whenMs = now + 3_600_000, actions = listOf("Dismiss")),
            Notice("s", "Stopwatch v2"),
        ), now, el, nextAlarmWallMs = null)
        assertEquals(1, state.timers.size)
        assertEquals(now + 3_600_000, state.nextAlarmWallMs)
        assertEquals("k3", state.nextAlarmKey)
        assertEquals("upcoming", state.nextAlarmState)
        assertEquals(now + 7_200_000, ClockNotifications.state(emptyList(), now, el, now + 7_200_000).nextAlarmWallMs)
    }

    @Test fun aRunningTimerWithTheAlarmCategoryIsNotFiring() {
        // AOSP DeskClock sets CATEGORY_ALARM on running timer notifications too.
        val running = ClockNotifications.classify(Notice("k1", "Timers v2", category = "alarm", title = "Pasta", whenMs = now + 60_000,
            chronometer = true, countDown = true, actions = listOf("Pause")), now, el)
        assertTrue(running is Item.Timer)
        assertNull(ClockNotifications.classify(Notice("u", "Upcoming Alarms v2", category = "alarm"), now, el) as? Item.Firing)
        // An unknown channel with the alarm category still counts as ringing.
        assertTrue(ClockNotifications.classify(Notice("f", "Alarms ringing", category = "alarm", title = "Alarm"), now, el) is Item.Firing)
    }
}
