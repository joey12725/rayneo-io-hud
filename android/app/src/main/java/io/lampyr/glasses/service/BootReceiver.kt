package io.lampyr.glasses.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.lampyr.glasses.settings.AppSettings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // AlarmManager forgets everything on reboot and update: a running timer or armed alarm restarts the service.
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) &&
            (AppSettings(context).boot || io.lampyr.glasses.clock.AndroidClock.needsService(context))) {
            runCatching { context.startForegroundService(Intent(context, GlassesService::class.java)) }
        }
    }
}
