package io.lampyr.glasses.input

import io.lampyr.glasses.debug.Trace

import android.accessibilityservice.AccessibilityService
import android.content.*
import android.app.KeyguardManager
import android.os.PowerManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.lampyr.glasses.service.GlassesService

class ControllerAccessibilityService : AccessibilityService() {
    private lateinit var keys: KeyMap
    private val wake = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { showPocketIfLocked() }
    }

    override fun onServiceConnected() {
        keys = KeyMap(this)
        Trace.log("Input", "Accessibility bind")
        bound = true
        instance = this
        GlassesService.instance?.controller?.inputPathChanged()
        registerReceiver(wake, IntentFilter(Intent.ACTION_SCREEN_ON))
        io.lampyr.glasses.service.ServiceAccess.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        if (GlassesService.instance?.controller?.shizuku?.state?.ready != true)
            GlassesService.instance?.controllerInput()?.releaseAll()
    }

    private fun showPocketIfLocked() {
        if (GlassesService.instance?.controller?.shizuku?.state?.ready == true) return
        if (!::keys.isInitialized || PocketActivity.active) return
        if (!getSharedPreferences("controller", MODE_PRIVATE).getBoolean("pocketOnWake", false)) return
        if (!getSystemService(PowerManager::class.java).isInteractive || !getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (!InputDevice.getDeviceIds().any { keys.isController(InputDevice.getDevice(it)) }) return
        // AOSP API 35 binds accessibility with BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS.
        // SCREEN_ON has no originating device: this opt-in covers any wake with the pad attached.
        runCatching { startActivity(PocketActivity.intent(this)) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        io.lampyr.glasses.settings.KeyLearning.consume?.let { if (it(event)) return true }
        if (!::keys.isInitialized) return false
        if (!keys.isController(event.device)) return false
        if (GlassesService.instance == null) io.lampyr.glasses.service.ServiceAccess.start(this)
        showPocketIfLocked()
        GlassesService.instance?.controller?.key(event)
        // Consume even unmapped keys and cold-start events; never replay a lost wake/start press.
        return true
    }

    override fun onDestroy() {
        Trace.log("Input", "Accessibility unbind")
        bound = false
        if (instance === this) instance = null
        GlassesService.instance?.controller?.inputPathChanged()
        if (::keys.isInitialized) { unregisterReceiver(wake); keys.close() }
        if (GlassesService.instance?.controller?.shizuku?.state?.ready != true)
            GlassesService.instance?.controllerInput()?.releaseAll()
        super.onDestroy()
    }

    companion object {
        @Volatile var bound = false; private set
        /** The bound service, for the experimental Google Clock automation (clock/AndroidPhoneAlarms.kt). */
        @Volatile var instance: ControllerAccessibilityService? = null; private set
        fun available(context: Context): Boolean = bound && context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { info -> info.packageName == context.packageName && info.name == ControllerAccessibilityService::class.java.name } }
    }
}
