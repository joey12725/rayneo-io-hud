package io.lampyr.glasses.clock

import kotlinx.serialization.Serializable

/**
 * Clock app state (requirement 7). Owned by [ClockEngine] in the foreground service, persisted as JSON.
 *
 * Every running quantity has two anchors: an elapsed-realtime one (immune to wall-clock changes, counts deep
 * sleep) used for in-process math, and a wall-clock one used after a reboot, when elapsed time restarts at 0.
 */
@Serializable
data class TimerItem(
    /** "t1", "t2", ... for glasses timers; "g:<notification key>" for timers imported from Google Clock. */
    val id: String,
    val label: String = "",
    /** Total length including added minutes. */
    val durationMs: Long,
    val running: Boolean = true,
    val endsAtElapsedMs: Long = 0,
    val endsAtWallMs: Long = 0,
    /** Remaining time while paused. */
    val remainingMs: Long = 0,
    val createdAtWallMs: Long = 0,
    /** "glasses" (this app) or "phone" (imported from Google Clock; never persisted). */
    val source: String = SOURCE_GLASSES,
    /** Also started in Google Clock (export setting); Google Clock rings for it, so we stay silent. */
    val mirrored: Boolean = false,
    /** Imported only: which notification actions Google Clock offers (logical names, see [ClockNotifications]). */
    val actions: Set<String> = emptySet(),
)

@Serializable
data class StopwatchState(
    val running: Boolean = false,
    val startedElapsedMs: Long = 0,
    val startedWallMs: Long = 0,
    /** Total before the current run. */
    val accumulatedMs: Long = 0,
    /** Cumulative totals at each lap, oldest first, capped at [ClockEngine.MAX_LAPS]. */
    val laps: List<Long> = emptyList(),
)

@Serializable
data class AlarmItem(
    val id: String,
    /** Local time, 24 h. */
    val hour: Int,
    val minute: Int,
    /** ISO days 1 = Monday .. 7 = Sunday; empty = once. */
    val days: Set<Int> = emptySet(),
    val label: String = "",
    val enabled: Boolean = true,
    /** The next occurrence is the first one strictly after this wall time (skipping one = moving it forward). */
    val armedFromMs: Long = 0,
    /** A snoozed alarm rings again at this wall time (0 = not snoozed). Works for disabled once-alarms too. */
    val snoozedUntilMs: Long = 0,
    val snoozes: Int = 0,
    val mirrored: Boolean = false,
    /** [armedFromMs] was moved forward by "skip next"; a wall-clock change must not undo that. */
    val skipped: Boolean = false,
)

@Serializable
data class Ringing(
    /** "timer" or "alarm". */
    val kind: String,
    val id: String,
    val label: String = "",
    /** When it started ringing (the timer's expiry or the alarm time), wall clock and elapsed realtime. */
    val sinceWallMs: Long,
    val sinceElapsedMs: Long,
    /** Silenced: the card stays until dismissed, but no sound and the lens may sleep. */
    val silenced: Boolean = false,
    val snoozes: Int = 0,
    val source: String = SOURCE_GLASSES,
    /** Timer length, for the "+1 min" restart and the agent's list. */
    val durationMs: Long = 0,
    /** Alarm time label ("7:00 AM"), for the card. */
    val at: String = "",
    /** Google Clock rings for this one (mirrored or imported): we never play our own sound. */
    val external: Boolean = false,
    /** Imported only: the Google Clock notification actions available. */
    val actions: Set<String> = emptySet(),
)

@Serializable
data class ClockSettings(
    /** How long a finished timer rings before it goes silent (the card stays). Default: 60 s. */
    val timerRingSec: Int = 60,
    /** How long an alarm rings before it snoozes by itself. Default: 5 min. */
    val alarmRingSec: Int = 300,
    /** Snooze length. Default: 9 min. */
    val snoozeMin: Int = 9,
    /** Automatic snoozes after an unanswered alarm before it goes silent. */
    val maxAutoSnoozes: Int = 3,
    /** Show and control Google Clock's running timers and ringing alarms (on by default). */
    val importPhone: Boolean = true,
    /** Also start glasses timers in Google Clock (off by default: it cannot pause, list or cancel them afterwards). */
    val mirrorTimers: Boolean = false,
    /** Also create glasses alarms in Google Clock (off by default: it cannot list, edit or delete them afterwards). */
    val mirrorAlarms: Boolean = false,
)

@Serializable
data class ClockSnapshot(
    val timers: List<TimerItem> = emptyList(),
    val stopwatch: StopwatchState = StopwatchState(),
    val alarms: List<AlarmItem> = emptyList(),
    val ringing: List<Ringing> = emptyList(),
    /** For one-press "repeat last" and the New page's starting value. */
    val lastTimerMs: Long = 600_000,
    val nextId: Int = 1,
    /** Boot the elapsed anchors belong to. */
    val bootCount: Int = -1,
    val settings: ClockSettings = ClockSettings(),
)

/** What Google Clock shows right now (from its notifications and AlarmManager.getNextAlarmClock); never persisted. */
data class PhoneClockState(
    val timers: List<TimerItem> = emptyList(),
    val ringing: List<Ringing> = emptyList(),
    /** Next alarm any app registered with setAlarmClock, except ours. */
    val nextAlarmWallMs: Long? = null,
    /** "upcoming" / "snoozed" when Google Clock shows a notification for it. */
    val nextAlarmState: String = "",
    /** Notification key of the upcoming/snoozed alarm notification, for "skip next". */
    val nextAlarmKey: String? = null,
    val nextAlarmActions: Set<String> = emptySet(),
)

data class ClockResult(val ok: Boolean = true, val message: String? = null, val id: String? = null) {
    companion object { fun fail(message: String) = ClockResult(false, message) }
}

/** One AlarmManager wake-up. [wall] true = RTC time (alarms), false = elapsed realtime (timers, ring limits). */
data class Wakeup(val key: String, val atMs: Long, val wall: Boolean, val alarmClock: Boolean = false)

const val SOURCE_GLASSES = "glasses"
const val SOURCE_PHONE = "phone"
