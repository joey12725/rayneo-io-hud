package io.lampyr.glasses.service

import android.app.*
import android.content.Context
import android.content.Intent
import io.lampyr.glasses.debug.Trace
import io.lampyr.glasses.MainActivity

/** Only resumed activities establish while-in-use eligibility. A CDM association alone does not. */
object ServiceAccess {
    private val visible = mutableSetOf<Activity>()
    private var observed: Application? = null
    val isVisible get() = visible.isNotEmpty()
    /** Also covers existing setup activities owned by other packages, without editing them. */
    fun observe(application: Application) {
        if (observed === application) return
        observed = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { visible += activity; GlassesService.instance?.refreshForegroundAccess() }
            override fun onActivityPaused(activity: Activity) { visible -= activity }
            override fun onActivityDestroyed(activity: Activity) { visible -= activity }
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) {}
        })
    }
    fun resumed(activity: Activity) {
        visible += activity
        if (start(activity)) GlassesService.instance?.refreshForegroundAccess()
    }
    fun paused(activity: Activity) { visible -= activity }

    fun start(context: Context): Boolean = try {
        context.startForegroundService(Intent(context, GlassesService::class.java))
        true
    } catch (e: RuntimeException) {
        Trace.error("Service", "Unable to start companion foreground service", e)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("service-recovery", "Companion recovery", NotificationManager.IMPORTANCE_DEFAULT))
        try {
            manager.notify(23, Notification.Builder(context, "service-recovery")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Tap to start the glasses service")
                .setContentText("Open the phone app to reconnect")
                .setContentIntent(PendingIntent.getActivity(context, 23, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
        } catch (failure: RuntimeException) { Trace.error("Service", "Unable to post recovery notification", failure) }
        false
    }
}

/** Retain successful promotion for the service lifetime; never claim success after a rejected add. */
class ForegroundAccess(private val promote: (Int) -> Unit, private val report: (Exception) -> Unit) {
    var types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE; private set
    fun has(type: Int) = types and type != 0
    fun enable(type: Int, permitted: Boolean, eligible: Boolean): Boolean {
        if (!permitted) return false
        if (has(type)) return true
        if (!eligible) return false
        return try { promote(types or type); types = types or type; true }
        catch (e: RuntimeException) { report(e); false }
    }
}
