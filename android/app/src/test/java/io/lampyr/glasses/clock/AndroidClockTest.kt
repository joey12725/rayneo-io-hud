package io.lampyr.glasses.clock

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidClockTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun schedulerSetsExactWakeupsAndCancelsStaleOnes() {
        val am = context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = AlarmManagerScheduler(context)
        scheduler.sync(listOf(Wakeup("timer:t1", 60_000, wall = false), Wakeup("alarm:a1", 1_800_000_000_000, wall = true, alarmClock = true)))
        val shadow = shadowOf(am)
        assertEquals(2, shadow.scheduledAlarms.size)
        assertEquals(1_800_000_000_000, am.nextAlarmClock?.triggerTime)
        assertTrue(scheduler.exact)
        // The engine resends its whole set: the timer was cancelled.
        scheduler.sync(listOf(Wakeup("alarm:a1", 1_800_000_000_000, wall = true, alarmClock = true)))
        assertEquals(1, shadow.scheduledAlarms.size)
        // A fresh process (after reboot or update) cancels what the previous one left.
        AlarmManagerScheduler(context).sync(emptyList())
        assertEquals(0, shadow.scheduledAlarms.size)
    }

    @Test fun alerterPostsAHeadsUpWithStopAndSnoozeAndClearsIt() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alerter = PhoneAlerter(context)
        alerter.update(listOf(Ringing("alarm", "a1", "Wake up", sinceWallMs = 1, sinceElapsedMs = 1, at = "7:00 AM")))
        val n = shadowOf(nm).getNotification(PhoneAlerter.NOTIFICATION_ID)
        assertNotNull(n)
        assertEquals(listOf("Stop", "Snooze"), n.actions.map { it.title.toString() })
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(PhoneAlerter.CHANNEL).importance)
        // Items Google Clock rings for are not doubled on our side and not posted.
        alerter.update(listOf(Ringing("timer", "g:k", sinceWallMs = 1, sinceElapsedMs = 1, source = SOURCE_PHONE, external = true)))
        assertNull(shadowOf(nm).getNotification(PhoneAlerter.NOTIFICATION_ID))
        alerter.close()
    }

    @Test fun needsServiceReadsPersistedState() {
        AndroidClock.prefs(context).edit().clear().commit()
        assertFalse(AndroidClock.needsService(context))
        val engine = ClockEngine({ 1_800_000_000_000 }, { 0 }, persistence = PrefsClockPersistence(context))
        engine.timerStart(60_000)
        assertTrue(AndroidClock.needsService(context))
    }
}
