package io.lampyr.glasses.input

import android.app.*
import android.content.Intent
import android.os.*
import io.lampyr.glasses.MainActivity
import io.lampyr.glasses.service.GlassesService

/** Poll cached connection state only; update the notification when its text actually changes. */
class ControllerNotification(private val service: GlassesService, private val updated: (Notification) -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var previous = ""
    private var closed = false
    /** Extra status (for example ambient capture) appended to the controller line. */
    var extra: () -> String = { "" }
    private val refresh = object : Runnable {
        override fun run() {
            if (closed) return
            val text = listOf(ControllerSetupSection.notificationText(service), extra()).filter { it.isNotBlank() }.joinToString(" · ")
            val signature = text
            if (previous != signature) {
                previous = signature
                val notification = build(text)
                updated(notification)
                service.getSystemService(NotificationManager::class.java).notify(1, notification)
            }
            handler.postDelayed(this, 1_000)
        }
    }
    fun build(text: String = "Controller and glasses starting"): Notification = Notification.Builder(service, "glasses")
        .setContentTitle("RayNeo companion").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view)
        .setSubText(service.microphoneStatus).setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(Notification.Action.Builder(null, "Pocket mode", PendingIntent.getActivity(service, 21, PocketActivity.intent(service), PendingIntent.FLAG_IMMUTABLE)).apply {
            if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(false)
        }.build())
        .setOngoing(true).build()
    fun refreshNow() { if (!closed) { handler.removeCallbacks(refresh); handler.post(refresh) } }
    fun start() { if (!closed) handler.post(refresh) }
    override fun close() { closed = true; handler.removeCallbacksAndMessages(null) }
}
