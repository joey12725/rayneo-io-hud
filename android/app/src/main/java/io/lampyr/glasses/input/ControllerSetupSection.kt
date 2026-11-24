package io.lampyr.glasses.input

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import io.lampyr.glasses.service.GlassesService

/** Text summaries of controller health for the notification and diagnostics. The screen itself is [ControllerActivity]. */
class ControllerSetupSection private constructor() {
    companion object {
        fun status(context: Context): String {
            val enabled = context.getSystemService(AccessibilityManager::class.java).getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { info -> info.packageName == context.packageName && info.name == ControllerAccessibilityService::class.java.name } }
            val runtime = GlassesService.instance?.controller
            val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            val notification = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            val listener = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().split(':')
                .any { ComponentName.unflattenFromString(it)?.let { name -> name.packageName == context.packageName && name.className == "io.lampyr.glasses.data.PhoneNotifications" } == true }
            return "Accessibility: ${if (enabled) "enabled" else "disabled"} / ${if (ControllerAccessibilityService.bound) "bound" else "unbound"}\n" +
                "Controller (${runtime?.sourceName ?: "service stopped"}): ${runtime?.detail ?: "unavailable"}\n" +
                "Battery optimisation exemption: ${if (battery) "granted" else "not granted"}\n" +
                "Notifications: ${if (notification) "allowed" else "blocked"}; notification access: ${if (listener) "enabled" else "disabled"}\n" +
                "Ambient: ${GlassesService.instance?.ambientLocationStatus ?: "open the phone app"}\n" +
                "Pocket mode: ${if (PocketActivity.active) "active (screen on)" else "inactive"}"
        }
        fun notificationText(service: GlassesService): String {
            val runtime = service.controller
            val controller = if (runtime?.connected == true) "Controller ${runtime.sourceName} connected" else "Controller disconnected (${runtime?.sourceName ?: "starting"})"
            val real = io.lampyr.glasses.glasses.GlassesTransports.real
            // While the link retries by itself, say "searching" once instead of flipping connecting/error on every attempt.
            val glasses = real?.retry?.value?.let(io.lampyr.glasses.glasses.ReconnectPolicy::notificationLine)?.ifBlank { null }
                ?: "Glasses ${real?.connectionState?.value?.name?.lowercase() ?: "not configured"}"
            return "$controller · $glasses"
        }
    }
}
