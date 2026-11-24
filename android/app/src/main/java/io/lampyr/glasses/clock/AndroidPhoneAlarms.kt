package io.lampyr.glasses.clock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.lampyr.glasses.debug.Trace
import io.lampyr.glasses.input.ControllerAccessibilityService
import kotlinx.coroutines.delay

/** EXPERIMENTAL: [PhoneAlarmHost] over [ControllerAccessibilityService] (see [PhoneAlarmAutomation]). */
class AccessibilityPhoneAlarmHost(private val context: Context) : PhoneAlarmHost {
    private val clock = PhoneAlarmAutomation.CLOCK_PACKAGE
    private fun service() = ControllerAccessibilityService.instance

    override fun blocker(): String? {
        val svc = service() ?: return "Turn on RayNeo Companion in the phone's Accessibility settings"
        if ((svc.serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT == 0)
            return "Turn RayNeo Companion accessibility off and on again so it may read Google Clock"
        if (context.packageManager.getLaunchIntentForPackage(clock) == null) return "Google Clock is not installed"
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) return "Phone screen is off; unlock the phone to use phone alarms"
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return "Phone is locked; unlock it to use phone alarms"
        return null
    }

    private fun appWindows(svc: AccessibilityService) = runCatching { svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } }.getOrDefault(emptyList())

    override fun foreground(): String? {
        val svc = service() ?: return null
        val wins = appWindows(svc)
        val win = wins.firstOrNull { it.isFocused } ?: wins.firstOrNull { it.isActive } ?: wins.firstOrNull()
        return runCatching { win?.root?.packageName?.toString() ?: svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
    }

    override fun openAlarms(): Boolean {
        val svc = service() ?: return false
        return runCatching {
            svc.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(clock).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure { Trace.log("Clock", "phone alarms open failed ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    override fun clockRoot(): UiNode? {
        val svc = service() ?: return null
        val root = runCatching {
            appWindows(svc).firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == clock } }
                ?: svc.rootInActiveWindow?.takeIf { it.packageName?.toString() == clock }
        }.getOrNull()
        return root?.let(::A11yUiNode)
    }

    override fun returnTo(previous: String?) {
        val svc = service() ?: return
        val launch = previous?.takeIf { it != clock }?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null && runCatching { svc.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        SystemClock.sleep(500)
        if (foreground() == clock) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    override suspend fun pause(ms: Long) = delay(ms)
    override fun elapsedMs() = SystemClock.elapsedRealtime()
}

/** [UiNode] over a live AccessibilityNodeInfo. */
class A11yUiNode(private val n: AccessibilityNodeInfo) : UiNode {
    override val text get() = n.text?.toString()
    override val desc get() = n.contentDescription?.toString()
    override val className get() = n.className?.toString()
    override val viewId: String? get() = n.viewIdResourceName
    override val packageName get() = n.packageName?.toString()
    override val checkable get() = n.isCheckable
    override val checked get() = n.isChecked
    override val clickable get() = n.isClickable
    override val scrollable get() = n.isScrollable
    override val collection get() = n.collectionInfo != null
    override val children: List<UiNode> by lazy { (0 until n.childCount).mapNotNull { i -> n.getChild(i)?.let(::A11yUiNode) } }
    override fun click() = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    override fun scrollForward() = n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    override fun scrollBackward() = n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
}

/** The last read phone alarm list, in the clock prefs. */
class PrefsPhoneAlarmCache(context: Context) : ClockPersistence {
    private val prefs = AndroidClock.prefs(context)
    override fun load(): String? = prefs.getString("phoneAlarms", null)
    override fun save(json: String) { prefs.edit().putString("phoneAlarms", json).apply() }
}
