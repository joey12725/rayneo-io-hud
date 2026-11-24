package io.lampyr.glasses.clock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import io.lampyr.glasses.MainActivity
import io.lampyr.glasses.debug.Trace
import io.lampyr.glasses.service.GlassesService

/** Android collaborators of [ClockEngine]. Created once by [GlassesService]. */
object AndroidClock {
    const val ACTION_WAKE = "io.lampyr.glasses.CLOCK_WAKE"
    const val ACTION_DISMISS = "io.lampyr.glasses.CLOCK_DISMISS"
    const val ACTION_SNOOZE = "io.lampyr.glasses.CLOCK_SNOOZE"
    const val EXTRA_ID = "id"
    private const val PREFS = "clock"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bootCount(context: Context): Int = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)

    fun engine(context: Context): ClockEngine {
        val app = context.applicationContext
        return ClockEngine(
            wallMs = System::currentTimeMillis,
            elapsedMs = SystemClock::elapsedRealtime,
            zone = { java.time.ZoneId.systemDefault() },
            bootCount = { bootCount(app) },
            persistence = PrefsClockPersistence(app),
            scheduler = AlarmManagerScheduler(app),
            alerter = PhoneAlerter(app),
            phone = GooglePhoneClock(app),
        ).also { it.phoneAlarms.attach(AccessibilityPhoneAlarmHost(app), PrefsPhoneAlarmCache(app)) }
    }

    /** Boot: start the service when a timer runs, an alarm is armed or something rings (regardless of "start on boot"). */
    fun needsService(context: Context): Boolean = ClockEngine.needsService(prefs(context).getString("state", null))

    /** Next alarm-clock time of another app (Google Clock), excluding ours. */
    fun nextPhoneAlarm(context: Context): Long? = runCatching {
        val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        if (info.showIntent?.creatorPackage == context.packageName) null else info.triggerTime
    }.getOrNull()
}

class PrefsClockPersistence(context: Context) : ClockPersistence {
    private val prefs = AndroidClock.prefs(context)
    override fun load(): String? = prefs.getString("state", null)
    // commit(): an alarm that is set must survive a crash a moment later.
    override fun save(json: String) { prefs.edit().putString("state", json).commit() }
}

/** Exact AlarmManager wake-ups: timers on elapsed realtime, alarms with setAlarmClock (status-bar icon, Doze-proof). */
class AlarmManagerScheduler(private val context: Context) : ClockScheduler {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val prefs = AndroidClock.prefs(context)
    private val set = mutableMapOf<String, Wakeup>()
    /** False when Android denies exact alarms (API 31+ without the permission); wake-ups are then inexact. */
    var exact = true
        private set

    private fun intent(key: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ClockReceiver::class.java).setAction(AndroidClock.ACTION_WAKE).setData(Uri.parse("clock://wake/" + Uri.encode(key))),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    override fun sync(wakeups: List<Wakeup>) {
        val wanted = wakeups.associateBy { it.key }
        val previous = prefs.getStringSet("scheduled", emptySet()).orEmpty() + set.keys
        (previous - wanted.keys).forEach { key -> runCatching { am.cancel(intent(key)) }; set.remove(key) }
        exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        wanted.values.forEach { w ->
            if (set[w.key] == w) return@forEach
            try {
                val pi = intent(w.key)
                when {
                    w.alarmClock && exact -> am.setAlarmClock(AlarmManager.AlarmClockInfo(w.atMs,
                        PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)), pi)
                    exact -> am.setExactAndAllowWhileIdle(if (w.wall) AlarmManager.RTC_WAKEUP else AlarmManager.ELAPSED_REALTIME_WAKEUP, w.atMs, pi)
                    else -> am.setAndAllowWhileIdle(if (w.wall) AlarmManager.RTC_WAKEUP else AlarmManager.ELAPSED_REALTIME_WAKEUP, w.atMs, pi)
                }
                set[w.key] = w
            } catch (e: SecurityException) {
                exact = false
                Trace.error("Clock", "Exact alarm denied key=${w.key}", e)
                runCatching { am.setAndAllowWhileIdle(if (w.wall) AlarmManager.RTC_WAKEUP else AlarmManager.ELAPSED_REALTIME_WAKEUP, w.atMs, intent(w.key)) }
                set[w.key] = w
            }
        }
        prefs.edit().putStringSet("scheduled", wanted.keys).apply()
    }
}

/**
 * Rings on the phone: looping alarm-stream sound, repeating vibration (vibration only during a phone call) and a
 * heads-up notification with Stop and Snooze / +1 min. Items Google Clock rings for itself are never doubled.
 */
class PhoneAlerter(private val context: Context) : ClockAlerter {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var wake: PowerManager.WakeLock? = null
    private var playing: Set<String> = emptySet()

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    override fun update(ringing: List<Ringing>) {
        val audible = ringing.filter { !it.silenced && !it.external && it.source == SOURCE_GLASSES }
        val keys = audible.map { it.kind + ":" + it.id }.toSet()
        if (keys.isEmpty()) stopSound() else if (keys != playing) startSound()
        playing = keys
        val shown = ringing.filter { it.source == SOURCE_GLASSES }
        // Re-post only when what it says changes, so unrelated clock changes do not pop the heads-up again.
        val signature = shown.lastOrNull()?.let { "${it.kind}:${it.id}:${it.sinceWallMs}:${shown.size}:${audible.isNotEmpty()}" }
        if (signature == posted) return
        posted = signature
        if (shown.isEmpty()) runCatching { nm.cancel(NOTIFICATION_ID) } else runCatching { nm.notify(NOTIFICATION_ID, notification(shown.last(), shown.size, audible.isNotEmpty())) }
    }
    private var posted: String? = "init"

    private fun inCall(): Boolean = context.getSystemService(AudioManager::class.java)?.mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION }

    private fun startSound() {
        stopSound()
        val pm = context.getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glasses:clock").apply { setReferenceCounted(false); acquire(10 * 60_000L) }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        if (!inCall()) runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = attrs
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.onFailure { Trace.error("Clock", "Alarm sound failed", it) }
        runCatching {
            @Suppress("DEPRECATION")
            vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 1), attrs)
            vibrating = true
        }
        Trace.log("Clock", "Alert start inCall=${inCall()}")
    }

    private fun stopSound() {
        ringtone?.let { runCatching { it.stop() } }; ringtone = null
        if (vibrating) runCatching { vibrator()?.cancel() }; vibrating = false
        wake?.let { runCatching { if (it.isHeld) it.release() } }; wake = null
    }

    private fun notification(r: Ringing, count: Int, audible: Boolean): Notification {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timers and alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // We play the alarm sound ourselves on the alarm stream.
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        fun service(action: String, code: Int) = PendingIntent.getService(context, code,
            Intent(context, GlassesService::class.java).setAction(action).putExtra(AndroidClock.EXTRA_ID, r.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = (if (r.kind == "timer") "Timer done" else "Alarm") + (r.label.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(if (count > 1) "$count ringing" else if (audible) "Ringing" else "Silenced")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(!audible)
            .setWhen(r.sinceWallMs)
            .addAction(Notification.Action.Builder(null, "Stop", service(AndroidClock.ACTION_DISMISS, 31)).build())
            .addAction(Notification.Action.Builder(null, if (r.kind == "timer") "+1 min" else "Snooze", service(AndroidClock.ACTION_SNOOZE, 32)).build())
            .setContentIntent(PendingIntent.getActivity(context, 33, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    fun close() { stopSound() }

    companion object {
        const val CHANNEL = "clock-alerts"
        const val NOTIFICATION_ID = 3
    }
}

/**
 * Google Clock. Export uses the AlarmClock intents (needs a background-activity-start exemption: today the
 * accessibility service's binding); control uses the actions on Google Clock's own notifications.
 */
class GooglePhoneClock(private val context: Context) : PhoneClock {
    private fun send(intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.packageManager.getPackageInfo(ClockNotifications.PACKAGE, 0) }.isSuccess) intent.setPackage(ClockNotifications.PACKAGE)
        context.startActivity(intent)
        true
    }.onFailure { Trace.error("Clock", "Phone clock intent failed action=${intent.action}", it) }.getOrDefault(false)

    override fun startTimer(seconds: Int, label: String) = send(Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400)).putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))

    override fun setAlarm(hour: Int, minute: Int, days: Set<Int>, label: String) = send(Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_MESSAGE, label)
        .apply { if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it % 7 + 1 })) } // ISO Mon=1 -> Calendar.MONDAY=2
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true))

    override fun act(key: String, action: String): Boolean = ClockBridge.send(key, action)
}

/** Listener side of the Google Clock import: keeps its live notifications and their actions. Main thread. */
object ClockBridge {
    private val notices = linkedMapOf<String, Pair<ClockNotifications.Notice, Array<Notification.Action>>>()

    fun notice(sbn: StatusBarNotification): ClockNotifications.Notice {
        val n = sbn.notification
        val e = n.extras
        return ClockNotifications.Notice(
            key = sbn.key,
            channel = n.channelId.orEmpty(),
            category = n.category,
            title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            whenMs = n.`when`,
            chronometer = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
            countDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            actions = n.actions?.map { it.title?.toString().orEmpty() }.orEmpty(),
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
    }

    fun posted(context: Context, sbn: StatusBarNotification) {
        notices[sbn.key] = notice(sbn) to (sbn.notification.actions ?: emptyArray())
        publish(context)
    }

    fun removed(context: Context, sbn: StatusBarNotification) {
        if (notices.remove(sbn.key) != null) publish(context)
    }

    fun seed(context: Context, active: Array<StatusBarNotification>?) {
        notices.clear()
        active.orEmpty().filter { it.packageName == ClockNotifications.PACKAGE }.forEach { notices[it.key] = notice(it) to (it.notification.actions ?: emptyArray()) }
        publish(context)
    }

    fun publish(context: Context) {
        val engine = GlassesService.instance?.clockOrNull ?: return
        engine.importPhone(ClockNotifications.state(notices.values.map { it.first }, System.currentTimeMillis(), SystemClock.elapsedRealtime(), AndroidClock.nextPhoneAlarm(context)))
    }

    fun send(key: String, action: String): Boolean {
        val (notice, actions) = notices[key] ?: return false
        val i = ClockNotifications.actionIndex(notice.actions, action) ?: return false
        return runCatching { actions[i].actionIntent.send(); true }.onFailure { Trace.error("Clock", "Phone clock action failed action=$action", it) }.getOrDefault(false)
            .also { Trace.log("Clock", "Phone clock action=$action ok=$it") }
    }
}

/** AlarmManager wake-ups. The engine decides what is due; this only makes sure it runs. */
class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Trace.log("Clock", "Wake ${intent.data?.lastPathSegment}")
        val engine = GlassesService.instance?.clockOrNull
        if (engine != null) engine.tick()
        // Exact-alarm broadcasts may start a foreground service; onCreate loads the state and ticks.
        else runCatching { context.startForegroundService(Intent(context, GlassesService::class.java).setAction(AndroidClock.ACTION_WAKE)) }
            .onFailure { Trace.error("Clock", "Service start from alarm failed", it) }
    }
}
